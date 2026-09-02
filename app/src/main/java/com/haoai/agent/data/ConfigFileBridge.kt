package com.haoai.agent.data

import android.util.Log
import com.haoai.agent.agent.policy.PermissionMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * 配置桥（对标 上游 上游.json，C6：配置源位于状态目录 filesDir/state/haoai.config.json）：
 * - render：把 AppSettings + MCP/SSH 分区渲染成状态目录镜像（apiKey/headers 一律 `****` 掩码）
 * - 监听：文件被外部（adb/文件管理器）改动后自动解析校验 → 经 AppContainer.updateSettings 合并入库
 * - agent 侧入口是 config_get/config_set 工具（恒审批，见 ConfigTools）；本桥保留 2s 轮询兜底
 * - 严格校验（上游 式）：未知键/非法值/缺必填 → 整体拒绝并保留上次配置，结果写 config-bridge.log
 * - 回滚兜底：每次 apply 前把当前配置存滚动快照（state/config-snapshots/，保留 10 份），
 *   设置页可一键回退——改坏配置不再依赖对话修复（自锁死保护）
 */
class ConfigFileBridge(
    private val file: File,
    private val cipher: KeystoreCipher,
    private val updateSettings: ((AppSettings) -> AppSettings) -> Unit,
    private val readSettings: () -> AppSettings,
    /** MCP/SSH 分区当前值（校验 headers 掩码沿用与 diff 对比用），由 AppContainer 注入。 */
    private val readMcp: () -> List<com.haoai.agent.agent.mcp.McpServerConfig> = { emptyList() },
    private val readSsh: () -> List<com.haoai.agent.agent.tools.shell.SshBackend.Target> = { emptyList() },
    /** MCP/SSH 分区同步回调（apply 成功后落各自存储+重连），返回人读备注（可空）。 */
    private val onExtChanged: (Parsed) -> String = { "" }
) {
    companion object {
        private const val TAG = "ConfigBridge"
        const val MASK = "****"

        /** C3 白名单单一常量源：桥渲染/校验、config_set、语义摘要三方共用，杜绝漂移。 */
        val SETTINGS_KEYS = setOf(
            "reply_max_tokens", "context_length", "local_context_length",
            "memory_enabled", "auto_learn", "deep_dream",
            // C4 A 组行为字段
            "permission_mode", "fallback_chain",
            "memory_extract_provider", "title_provider", "summarize_provider",
            "daily_token_budget_k", "keep_alive", "dream_provider", "dream_idle_minutes",
            // 外观主题组（枚举/范围校验，改错可即时回改，无安全风险）
            "theme_mode", "theme_seed", "amoled_mode", "bubble_opacity",
            "wallpaper_global", "dynamic_color", "reasoning_effort"
        )

        /** C3 顶层合法键（*_removed 为显式删除开关，见 apply）。 */
        val TOP_LEVEL_KEYS = setOf(
            "providers", "settings", "providers_removed",
            "mcp_servers", "mcp_servers_removed", "ssh_targets", "ssh_targets_removed"
        )

        val PROVIDER_KEYS = setOf(
            "id", "name", "baseUrl", "model", "protocol", "apiKey",
            "contextLength", "maxTokens", "active"
        )

        /** MCP 服务器分区字段（toolCache 是派生态，不渲染也不接受）。 */
        val MCP_KEYS = setOf(
            "id", "name", "url", "kind", "command", "headers",
            "enabled", "approvalLevel", "allowPlaintext"
        )

        val SSH_KEYS = setOf("id", "name", "host", "port", "user")

        /** C4 permission_mode 枚举（与 PermissionMode 一一对应，lowercase）。 */
        val PERMISSION_MODES = setOf("always_ask", "ask_writes", "yolo")

        /** C3/C4 数值钳制边界（reply/context 与旧 update_settings 路径一致）。 */
        const val REPLY_MAX_TOKENS_MIN = 256
        const val REPLY_MAX_TOKENS_MAX = 1_000_000
        const val CONTEXT_LENGTH_MIN = 1024
        const val CONTEXT_LENGTH_MAX = 10_000_000
        const val LOCAL_CONTEXT_MIN = 2048
        const val LOCAL_CONTEXT_MAX = 262_144

        /** 端侧条目 id（豁免显式删除；与 LlamaServerController.LOCAL_PROVIDER_ID 同值）。 */
        const val LOCAL_PROVIDER_ID = "local"

        /**
         * C2 公共脱敏：把 JSON 文本中所有 `"apiKey": "<非空非****>"` 的值替换为 `****`。
         * 按键名+值整体匹配，不会把其他字段值里恰好出现的 apiKey 字样误当键。
         * 供桥（拒绝路径）与 SnapshotHook（快照脱敏）共用。
         */
        fun maskApiKeys(text: String): String =
            Regex("""("apiKey"\s*:\s*")([^"]*)(")""").replace(text) { m ->
                val v = m.groupValues[2]
                if (v.isBlank() || v == MASK) m.value
                else m.groupValues[1] + MASK + m.groupValues[3]
            }

        /**
         * MCP headers 对象内敏感值脱敏（Authorization 等自定义头）。render 输出本身已掩码，
         * 此函数用于拒绝路径覆盖文件与修正副本——与 maskApiKeys 同一承诺：明文密钥不落盘。
         */
        fun maskHeaderValues(text: String): String =
            Regex("""("headers"\s*:\s*\{)([^}]*)(\})""").replace(text) { m ->
                val inner = Regex("""("[^"]*"\s*:\s*")([^"]*)(")""").replace(m.groupValues[2]) { v ->
                    if (v.groupValues[2].isBlank() || v.groupValues[2] == MASK) v.value
                    else v.groupValues[1] + MASK + v.groupValues[3]
                }
                m.groupValues[1] + inner + m.groupValues[3]
            }
    }

    data class ApplyResult(val ok: Boolean, val message: String)

    /** C1 config_set 预检结果：err 非空=补丁非法（免审批直接拒绝）；diff 供审批弹窗展示。 */
    data class Preview(val diff: String = "", val err: String? = null)

    /** 解析产物：AppSettings + MCP/SSH 分区变更（null=补丁未涉及该分区；removed=显式删除开关）。 */
    data class Parsed(
        val settings: AppSettings,
        val message: String,
        val ok: Boolean,
        val mcpServers: List<com.haoai.agent.agent.mcp.McpServerConfig>? = null,
        val mcpRemoved: Boolean = false,
        val sshTargets: List<com.haoai.agent.agent.tools.shell.SshBackend.Target>? = null,
        val sshRemoved: Boolean = false
    )

    private fun activeProviderOf(s: AppSettings): ProviderConfig? =
        s.providers.find { it.id == s.activeProviderId } ?: s.providers.firstOrNull()

    /** 渲染当前设置到镜像 JSON（apiKey/headers 掩码化）。永不输出 *_removed（镜像闭合）。 */
    fun render(settings: AppSettings): String {
        val active = activeProviderOf(settings)
        val providers = settings.providers.map { p ->
            buildJsonObject {
                put("id", p.id)
                put("name", p.name)
                put("baseUrl", p.baseUrl)
                put("model", p.model)
                put("protocol", p.protocol)
                put("apiKey", if (p.apiKeyCipher.isNotBlank()) MASK else "")
                put("contextLength", p.contextLength)
                put("maxTokens", p.maxTokens)
                put("active", p.id == active?.id)
            }
        }
        val st = buildJsonObject {
            put("reply_max_tokens", active?.maxTokens ?: 0)
            put("context_length", active?.contextLength ?: 0)
            put("local_context_length", settings.localContextLength)
            put("memory_enabled", settings.memoryEnabled)
            put("auto_learn", settings.autoLearn)
            put("deep_dream", settings.deepDream)
            // C4 A 组
            put("permission_mode", permissionModeToKey(settings.permissionMode))
            put("fallback_chain", JsonArray(settings.fallbackChain.map { JsonPrimitive(it) }))
            put("memory_extract_provider", settings.memoryExtractProviderId)
            put("title_provider", settings.titleProviderId)
            put("summarize_provider", settings.summarizeProviderId)
            put("daily_token_budget_k", settings.dailyTokenBudgetK)
            put("keep_alive", settings.keepAlive)
            put("dream_provider", settings.dreamProviderId)
            put("dream_idle_minutes", settings.dreamIdleMinutes)
            // 外观主题组
            put("theme_mode", settings.themeMode)
            put("theme_seed", settings.themeSeed)
            put("amoled_mode", settings.amoledMode)
            put("bubble_opacity", settings.bubbleOpacity)
            put("wallpaper_global", settings.wallpaperGlobal)
            put("dynamic_color", settings.dynamicColor)
            put("reasoning_effort", settings.reasoningEffort)
        }
        val mcp = readMcp().map { s ->
            buildJsonObject {
                put("id", s.id)
                put("name", s.name)
                put("url", s.url)
                put("kind", s.kind)
                put("command", s.command)
                put("headers", buildJsonObject {
                    s.headers.forEach { (k, v) -> put(k, if (v.isNotBlank()) MASK else "") }
                })
                put("enabled", s.enabled)
                put("approvalLevel", s.approvalLevel)
                put("allowPlaintext", s.allowPlaintext)
            }
        }
        val ssh = readSsh().map { t ->
            buildJsonObject {
                put("id", t.id)
                put("name", t.name)
                put("host", t.host)
                put("port", t.port)
                put("user", t.user)
            }
        }
        return Json { encodeDefaults = true }.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("providers", JsonArray(providers))
                put("settings", st)
                put("mcp_servers", JsonArray(mcp))
                put("ssh_targets", JsonArray(ssh))
            }
        )
    }

    /** 解析并应用外部配置；严格校验，失败整体拒绝。 */
    fun apply(raw: String): ApplyResult {
        val p = applyFull(raw)
        return ApplyResult(p.ok, p.message)
    }

    /** apply + 同步 MCP/SSH 分区（config_set 轮询路径统一走这里；备注拼进结果消息）。 */
    fun applyFull(raw: String): Parsed {
        val p = parseToSettings(raw)
        if (!p.ok) return p
        snapshotNow()
        val extNote = runCatching { onExtChanged(p) }.getOrDefault("")
        updateSettings { p.settings }
        val msg = p.message + extNote
        log(msg)
        return p.copy(message = msg)
    }

    /**
     * 与 apply 完全同源的解析（C1 语义 diff 与 config_set 共用）：成功返回将要生效的
     * Parsed；失败返回错误原因（ok=false，settings 为当前配置原值）。
     * C3：providers 合并式 + baseUrl/protocol 重认证 + 数值钳制；
     * 回滚/主题/MCP/SSH 分区校验同在此单一入口。
     */
    fun parseToSettings(raw: String): Parsed {
        val cur = readSettings()
        val fail: (String) -> Parsed = { msg ->
            log("拒绝: $msg")
            Parsed(cur, msg, false)
        }
        val root = try {
            Json { isLenient = true }.parseToJsonElement(raw).jsonObject
        } catch (e: Exception) {
            return fail("JSON 解析失败: ${e.message?.take(120)}")
        }
        val unknownTop = root.keys - TOP_LEVEL_KEYS
        if (unknownTop.isNotEmpty()) return fail("未知顶层键: ${unknownTop.joinToString()}")

        val errors = mutableListOf<String>()
        val newProviders = mutableListOf<ProviderConfig>()
        val seenIds = mutableSetOf<String>()
        var newActiveId = cur.activeProviderId

        val provArr = root["providers"]?.let { runCatching { it as kotlinx.serialization.json.JsonArray }.getOrNull() }
            ?: JsonArray(emptyList())

        provArr.forEachIndexed { i, el ->
            fun failP(msg: String) { errors += "providers[$i] $msg" }
            val p = runCatching { el.jsonObject }.getOrNull() ?: run { failP("必须是对象"); return@forEachIndexed }
            val unknownP = p.keys - PROVIDER_KEYS
            if (unknownP.isNotEmpty()) { failP("未知键: ${unknownP.joinToString()}"); return@forEachIndexed }
            val name = p.str("name") ?: run { failP("缺 name"); return@forEachIndexed }
            val baseUrl = p.str("baseUrl") ?: run { failP("缺 baseUrl"); return@forEachIndexed }
            val model = p.str("model") ?: run { failP("缺 model"); return@forEachIndexed }
            if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://") && !baseUrl.startsWith("local://")) {
                failP("baseUrl 必须 http(s):// 或 local://"); return@forEachIndexed
            }
            val protocol = p.str("protocol") ?: "openai_compat"
            if (protocol !in setOf("openai_compat", "anthropic")) {
                failP("protocol 仅支持 openai_compat/anthropic"); return@forEachIndexed
            }
            val contextLength = p.intOr("contextLength") ?: 0
            val maxTokens = p.intOr("maxTokens") ?: 0
            if (contextLength < 0 || maxTokens < 0) {
                failP("contextLength/maxTokens 不能为负"); return@forEachIndexed
            }
            // C3.5：0 保留（=自动），>0 按同界钳制
            val ctxClamped = if (contextLength > 0) contextLength.coerceIn(CONTEXT_LENGTH_MIN, CONTEXT_LENGTH_MAX) else 0
            val maxClamped = if (maxTokens > 0) maxTokens.coerceIn(REPLY_MAX_TOKENS_MIN, REPLY_MAX_TOKENS_MAX) else 0
            val id = p.str("id") ?: UUID.randomUUID().toString()
            val apiKeyRaw = p.str("apiKey") ?: ""
            val active = p.booleanOr("active") ?: false
            val existing = cur.providers.find { it.id == id }
            // C3 纵深防御：已有 provider 的 baseUrl/protocol 变更且 key 是掩码/空 → 强制重新认证
            if (existing != null && (apiKeyRaw.isBlank() || apiKeyRaw == MASK) &&
                (existing.baseUrl != baseUrl || existing.protocol != protocol)
            ) {
                failP("provider「$name」的 baseUrl/protocol 变更必须同时提供明文 apiKey（掩码视为拒绝）")
                return@forEachIndexed
            }
            val newCipher = when {
                apiKeyRaw.isBlank() || apiKeyRaw == MASK -> {
                    if (existing == null) { failP("新 provider 必须提供 apiKey（不能是 ****/空）"); return@forEachIndexed }
                    existing.apiKeyCipher
                }
                else -> cipher.encrypt(apiKeyRaw) ?: run {
                    failP("apiKey 加密失败（Keystore 不可用）"); return@forEachIndexed
                }
            }
            // 同 id 后写覆盖先写（补丁内重复条目按更新语义）
            newProviders.removeAll { it.id == id }
            newProviders += ProviderConfig(
                id = id, name = name, baseUrl = baseUrl, model = model,
                apiKeyCipher = newCipher, protocol = protocol,
                contextLength = ctxClamped, maxTokens = maxClamped
            )
            seenIds += id
            if (active) newActiveId = id
        }
        if (errors.isNotEmpty()) return fail(errors.take(3).joinToString("；"))

        // C3.1 合并式：镜像缺席的 provider 默认保留；顶层 providers_removed=true 才显式删除。
        // absentKept 按 id 去重（历史全量替换路径可能残留重复的 local 条目）
        val providersRemoved = root["providers_removed"]?.booleanOrNullOr() == true
        val removedIds = if (providersRemoved) {
            cur.providers.filter { it.id !in seenIds && it.id != LOCAL_PROVIDER_ID }.map { it.id }
        } else emptyList()
        val absentKept = cur.providers
            .filter { it.id !in seenIds && it.id !in removedIds }
            .distinctBy { it.id }
        // local 端侧条目始终保留（豁免显式删除），排在其后
        val merged = newProviders + absentKept.filter { it.id != LOCAL_PROVIDER_ID } +
            absentKept.filter { it.id == LOCAL_PROVIDER_ID }
        val activeTarget = merged.find { it.id == newActiveId } ?: merged.firstOrNull()
        var next = cur.copy(
            providers = merged,
            activeProviderId = activeTarget?.id ?: cur.activeProviderId
        )

        val st = root["settings"]?.let { runCatching { it.jsonObject }.getOrNull() } ?: JsonObject(emptyMap())
        val unknownS = st.keys - SETTINGS_KEYS
        if (unknownS.isNotEmpty()) return fail("settings 未知键: ${unknownS.joinToString()}")

        st["reply_max_tokens"]?.intOrNullOr()?.let { v ->
            next = next.withActive(maxTokens = v.coerceIn(REPLY_MAX_TOKENS_MIN, REPLY_MAX_TOKENS_MAX))
        }
        st["context_length"]?.intOrNullOr()?.let { v ->
            next = next.withActive(contextLength = v.coerceIn(CONTEXT_LENGTH_MIN, CONTEXT_LENGTH_MAX))
        }
        st["local_context_length"]?.intOrNullOr()?.let { v ->
            next = next.copy(localContextLength = v.coerceIn(LOCAL_CONTEXT_MIN, LOCAL_CONTEXT_MAX))
        }
        st["memory_enabled"]?.booleanOrNullOr()?.let { next = next.copy(memoryEnabled = it) }
        st["auto_learn"]?.booleanOrNullOr()?.let { next = next.copy(autoLearn = it) }
        st["deep_dream"]?.booleanOrNullOr()?.let { next = next.copy(deepDream = it) }

        // ── C4 A 组行为字段（枚举/引用校验失败整体拒绝）──
        st["permission_mode"]?.let { el ->
            val s = (el as? JsonPrimitive)?.content?.trim()?.lowercase()
            if (s == null || s !in PERMISSION_MODES) {
                return fail("permission_mode 仅支持 ${PERMISSION_MODES.joinToString("/")}")
            }
            next = next.copy(permissionMode = permissionModeFromKey(s))
        }
        st["fallback_chain"]?.let { el ->
            val arr = el as? JsonArray
                ?: return fail("fallback_chain 必须是 providerId 字符串数组")
            val ids = arr.mapNotNull { (it as? JsonPrimitive)?.content?.trim() }.filter { it.isNotBlank() }
            val bad = ids.filter { pid -> next.providers.none { it.id == pid } }
            if (bad.isNotEmpty()) {
                return fail("fallback_chain 引用了不存在的 providerId: ${bad.joinToString()}")
            }
            next = next.copy(fallbackChain = ids)
        }
        for ((key, apply2) in PURPOSE_FIELDS) {
            st[key]?.let { el ->
                val s = (el as? JsonPrimitive)?.content?.trim().orEmpty()
                if (s.isNotEmpty() && s != "local" && next.providers.none { it.id == s }) {
                    return fail("$key 引用了不存在的 providerId: $s")
                }
                next = apply2(next, s)
            }
        }
        st["daily_token_budget_k"]?.intOrNullOr()?.let { v ->
            next = next.copy(dailyTokenBudgetK = v.coerceIn(0, 10_000))
        }
        st["keep_alive"]?.booleanOrNullOr()?.let { next = next.copy(keepAlive = it) }
        st["dream_provider"]?.let { el ->
            val s = (el as? JsonPrimitive)?.content?.trim().orEmpty()
            if (s.isNotEmpty() && s != "local" && next.providers.none { it.id == s }) {
                return fail("dream_provider 引用了不存在的 providerId: $s")
            }
            next = next.copy(dreamProviderId = s)
        }
        st["dream_idle_minutes"]?.intOrNullOr()?.let { v ->
            next = next.copy(dreamIdleMinutes = v.coerceIn(5, 240))
        }

        // ── 外观主题组（枚举/范围校验）──
        st["theme_mode"]?.let { el ->
            val s = (el as? JsonPrimitive)?.content?.trim()?.lowercase()
            if (s == null || s !in setOf("light", "dark", "system")) {
                return fail("theme_mode 仅支持 light/dark/system")
            }
            next = next.copy(themeMode = s)
        }
        st["theme_seed"]?.intOrNullOr()?.let { v ->
            next = next.copy(themeSeed = v.coerceIn(0, 100))
        }
        st["amoled_mode"]?.booleanOrNullOr()?.let { next = next.copy(amoledMode = it) }
        st["bubble_opacity"]?.let { el ->
            val d = (el as? JsonPrimitive)?.let { runCatching { it.double }.getOrNull() }
                ?: return fail("bubble_opacity 必须是 0.3-1.0 的数值")
            next = next.copy(bubbleOpacity = d.coerceIn(0.3, 1.0).toFloat())
        }
        st["wallpaper_global"]?.booleanOrNullOr()?.let { next = next.copy(wallpaperGlobal = it) }
        st["dynamic_color"]?.booleanOrNullOr()?.let { next = next.copy(dynamicColor = it) }
        st["reasoning_effort"]?.let { el ->
            val s = (el as? JsonPrimitive)?.content?.trim()?.lowercase().orEmpty()
            if (s.isNotEmpty() && s !in setOf("low", "medium", "high")) {
                return fail("reasoning_effort 仅支持 空/low/medium/high")
            }
            next = next.copy(reasoningEffort = s)
        }

        // ── MCP 服务器分区（按 id 合并；headers 值 **** = 沿用原值）──
        var mcpList: List<com.haoai.agent.agent.mcp.McpServerConfig>? = null
        var mcpRemoved = false
        root["mcp_servers"]?.let { el ->
            val arr = el as? JsonArray ?: return fail("mcp_servers 必须是数组")
            val curMcp = readMcp()
            // 只收集补丁条目（与 providers 合并语义一致）：缺席保留，*_removed=true 才删缺席者
            val out = LinkedHashMap<String, com.haoai.agent.agent.mcp.McpServerConfig>()
            arr.forEachIndexed { i, e2 ->
                fun failM(msg: String) { errors += "mcp_servers[$i] $msg" }
                val o = e2 as? JsonObject ?: run { failM("必须是对象"); return@forEachIndexed }
                val unknown = o.keys - MCP_KEYS
                if (unknown.isNotEmpty()) { failM("未知键: ${unknown.joinToString()}"); return@forEachIndexed }
                val name = o.str("name") ?: run { failM("缺 name"); return@forEachIndexed }
                val id = o.str("id") ?: UUID.randomUUID().toString()
                val kind = o.str("kind") ?: "http"
                if (kind !in setOf("http", "stdio")) { failM("kind 仅支持 http/stdio"); return@forEachIndexed }
                val url = o.str("url") ?: ""
                val command = o.str("command") ?: ""
                if (kind == "http" && !url.startsWith("http://") && !url.startsWith("https://")) {
                    failM("http 类型 url 必须 http(s)://"); return@forEachIndexed
                }
                if (kind == "stdio" && command.isBlank()) { failM("stdio 类型必须提供 command"); return@forEachIndexed }
                val approval = o.str("approvalLevel") ?: "write"
                if (approval !in setOf("write", "read")) { failM("approvalLevel 仅支持 write/read"); return@forEachIndexed }
                val existing = curMcp.find { it.id == id }
                val headersIn = o["headers"] as? JsonObject
                val headers = LinkedHashMap<String, String>()
                headersIn?.forEach { (hk, hv) ->
                    val raw = (hv as? JsonPrimitive)?.content ?: ""
                    when {
                        raw == MASK -> {
                            val old = existing?.headers?.get(hk)
                            if (old == null) { failM("headers.$hk 是掩码但服务器不存在该键（新服务器必须写明文）"); return@forEachIndexed }
                            headers[hk] = old
                        }
                        else -> headers[hk] = raw
                    }
                }
                if (headersIn == null && existing != null) headers.putAll(existing.headers)
                out[id] = com.haoai.agent.agent.mcp.McpServerConfig(
                    id = id, name = name, url = url, kind = kind, command = command,
                    headers = headers,
                    enabled = o.booleanOr("enabled") ?: existing?.enabled ?: true,
                    approvalLevel = approval,
                    allowPlaintext = o.booleanOr("allowPlaintext") ?: existing?.allowPlaintext ?: false,
                    toolCache = existing?.toolCache ?: emptyList()
                )
            }
            mcpRemoved = root["mcp_servers_removed"]?.booleanOrNullOr() == true
            mcpList = out.values.toList()
        }
        if (errors.isNotEmpty()) return fail(errors.take(3).joinToString("；"))

        // ── SSH 目标分区（按 id 合并；无凭据字段，凭据仅 UI 管理）──
        var sshList: List<com.haoai.agent.agent.tools.shell.SshBackend.Target>? = null
        var sshRemoved = false
        root["ssh_targets"]?.let { el ->
            val arr = el as? JsonArray ?: return fail("ssh_targets 必须是数组")
            // 只收集补丁条目（与 providers 合并语义一致）：缺席保留，ssh_targets_removed=true 才删
            val out = LinkedHashMap<String, com.haoai.agent.agent.tools.shell.SshBackend.Target>()
            arr.forEachIndexed { i, e2 ->
                fun failS(msg: String) { errors += "ssh_targets[$i] $msg" }
                val o = e2 as? JsonObject ?: run { failS("必须是对象"); return@forEachIndexed }
                val unknown = o.keys - SSH_KEYS
                if (unknown.isNotEmpty()) { failS("未知键: ${unknown.joinToString()}"); return@forEachIndexed }
                val name = o.str("name") ?: run { failS("缺 name"); return@forEachIndexed }
                val host = o.str("host") ?: run { failS("缺 host"); return@forEachIndexed }
                val user = o.str("user") ?: run { failS("缺 user"); return@forEachIndexed }
                val port = o.intOr("port") ?: 22
                if (port !in 1..65535) { failS("port 必须 1-65535"); return@forEachIndexed }
                val id = o.str("id") ?: UUID.randomUUID().toString()
                out[id] = com.haoai.agent.agent.tools.shell.SshBackend.Target(id, name, host, port, user)
            }
            sshRemoved = root["ssh_targets_removed"]?.booleanOrNullOr() == true
            sshList = out.values.toList()
        }
        if (errors.isNotEmpty()) return fail(errors.take(3).joinToString("；"))

        // C3.2 数量骤降警告（显式删除过半时提示，agent 当轮可见、审批弹窗可见）
        var warning = ""
        if (providersRemoved && removedIds.isNotEmpty() &&
            removedIds.size >= (merged.size + removedIds.size) / 2
        ) {
            val names = cur.providers.filter { it.id in removedIds }.joinToString("、") { it.name }
            warning = "（警告：已删除 ${removedIds.size} 个 provider：$names）"
        }

        val activeDesc = activeTarget?.let { "当前 = ${it.name}" } ?: "无 provider"
        val msg = "已应用: ${merged.size} 个 provider（新增/更新 ${newProviders.size}），$activeDesc$warning"
        return Parsed(next, msg, true, mcpList, mcpRemoved, sshList, sshRemoved)
    }

    /** C4 内部任务模型路由字段（memory_extract_provider 等）：id 须存在、"local" 或空串（=主模型）。 */
    private val PURPOSE_FIELDS = listOf(
        "memory_extract_provider" to { s: AppSettings, v: String -> s.copy(memoryExtractProviderId = v) },
        "title_provider" to { s: AppSettings, v: String -> s.copy(titleProviderId = v) },
        "summarize_provider" to { s: AppSettings, v: String -> s.copy(summarizeProviderId = v) }
    )

    /**
     * C1/C6 语义 diff：对比当前配置与「将要生效的配置」，生成人读变更清单
     * （config_set 审批弹窗与工具回显共用）。字段口径与 parseToSettings 同源。
     */
    /** 审批弹窗语义 diff：面向普通用户的人话描述（技术取值放箭头后备查）。 */
    fun semanticSummary(old: AppSettings, new: AppSettings): String {
        val lines = mutableListOf<String>()
        val oldIds = old.providers.associateBy { it.id }
        val newIds = new.providers.associateBy { it.id }
        newIds.forEach { (id, p) ->
            val o = oldIds[id]
            when {
                o == null -> lines += "新增模型服务「${p.name}」（模型：${p.model}）"
                else -> {
                    val ch = mutableListOf<String>()
                    if (o.baseUrl != p.baseUrl) ch += "接口地址 ${o.baseUrl} → ${p.baseUrl}"
                    if (o.protocol != p.protocol) ch += "接口协议 ${o.protocol} → ${p.protocol}"
                    if (o.model != p.model) ch += "模型 ${o.model} → ${p.model}"
                    if (o.apiKeyCipher != p.apiKeyCipher) ch += "API 密钥已更新（内容已隐藏）"
                    if (ch.isNotEmpty()) lines += "修改模型服务「${p.name}」：${ch.joinToString("；")}"
                }
            }
        }
        oldIds.forEach { (id, o) ->
            if (id !in newIds) lines += "删除模型服务「${o.name}」"
        }
        val oldActive = activeProviderOf(old)
        val newActive = activeProviderOf(new)
        if (oldActive?.id != newActive?.id && newActive != null) {
            lines += "当前使用的模型服务切换为「${newActive.name}」"
        }
        fun cmp(label: String, ov: Any?, nv: Any?) {
            if (ov != nv) lines += "修改「$label」：${humanValue(ov)} → ${humanValue(nv)}"
        }
        fun cmpProvider(label: String, oldId: String?, newId: String?) {
            val a = providerLabel(old, oldId)
            val b = providerLabel(new, newId)
            if (a != b) lines += "修改「$label」：$a → $b"
        }
        cmp("单次回复上限（token）", oldActive?.maxTokens, newActive?.maxTokens)
        cmp("上下文窗口（token）", oldActive?.contextLength, newActive?.contextLength)
        cmp("端侧上下文窗口（token）", old.localContextLength, new.localContextLength)
        cmp("记忆系统", old.memoryEnabled, new.memoryEnabled)
        cmp("自动学习", old.autoLearn, new.autoLearn)
        cmp("闲置时整理记忆", old.deepDream, new.deepDream)
        cmp("权限模式", humanPermission(old.permissionMode), humanPermission(new.permissionMode))
        if (new.fallbackChain != old.fallbackChain) {
            val added = new.fallbackChain - old.fallbackChain
            val removed = old.fallbackChain - new.fallbackChain
            val parts = mutableListOf<String>()
            if (added.isNotEmpty()) parts += "新增 ${added.joinToString("、") { providerLabel(new, it) }}"
            if (removed.isNotEmpty()) parts += "移除 ${removed.joinToString("、") { providerLabel(old, it) }}"
            lines += "修改「模型降级链」：${parts.joinToString("；")}"
        }
        cmpProvider("记忆提取模型", old.memoryExtractProviderId, new.memoryExtractProviderId)
        cmpProvider("会话标题模型", old.titleProviderId, new.titleProviderId)
        cmpProvider("压缩摘要模型", old.summarizeProviderId, new.summarizeProviderId)
        cmp("每日 token 预算（千）", old.dailyTokenBudgetK, new.dailyTokenBudgetK)
        cmp("后台常驻", old.keepAlive, new.keepAlive)
        cmpProvider("记忆整理模型", old.dreamProviderId, new.dreamProviderId)
        cmp("灭屏闲置触发（分钟）", old.dreamIdleMinutes, new.dreamIdleMinutes)
        cmp("外观主题", humanTheme(old.themeMode), humanTheme(new.themeMode))
        cmp("主题色", old.themeSeed, new.themeSeed)
        cmp("AMOLED 纯黑", old.amoledMode, new.amoledMode)
        cmp("气泡不透明度", old.bubbleOpacity, new.bubbleOpacity)
        cmp("壁纸全局应用", old.wallpaperGlobal, new.wallpaperGlobal)
        cmp("动态取色", old.dynamicColor, new.dynamicColor)
        cmp("思考等级", old.reasoningEffort.ifBlank { "默认" }, new.reasoningEffort.ifBlank { "默认" })
        return lines.joinToString("\n").ifBlank { "（无字段变化）" }.take(500)
    }

    private fun humanValue(v: Any?): String = when (v) {
        null -> "（无）"
        is Boolean -> if (v) "开" else "关"
        is String -> v.ifBlank { "（未设置）" }
        else -> v.toString()
    }

    private fun humanPermission(mode: com.haoai.agent.agent.policy.PermissionMode): String = when (
        permissionModeToKey(mode)
    ) {
        "yolo" -> "全自动"
        "ask_writes" -> "写入时询问"
        else -> "全部询问"
    }

    private fun humanTheme(theme: String?): String = when (theme) {
        "light" -> "浅色"
        "dark" -> "深色"
        else -> "跟随系统"
    }

    /** 供应商 id → 人话名（「名称」；未设置/未知回退 id）。 */
    private fun providerLabel(st: AppSettings, id: String?): String {
        if (id.isNullOrBlank()) return "未设置"
        val p = st.providers.find { it.id == id.trim() } ?: return id
        return "「${p.name}」"
    }

    /** MCP/SSH 分区语义 diff（审批弹窗用）；外部连接面变更附醒目提示。 */
    private fun extDiff(parsed: Parsed): String {
        val lines = mutableListOf<String>()
        parsed.mcpServers?.let { new ->
            val cur = readMcp().associateBy { it.id }
            var touched = false
            new.forEach { s ->
                val o = cur[s.id]
                when {
                    o == null -> { lines += "新增 MCP 外部服务器「${s.name}」（${s.kind} ${s.url.ifBlank { s.command }}）"; touched = true }
                    o.url != s.url || o.command != s.command || o.kind != s.kind -> {
                        lines += "修改 MCP 服务器「${s.name}」：地址 → ${s.url.ifBlank { s.command }}"; touched = true
                    }
                    o.enabled != s.enabled -> { lines += "${if (s.enabled) "启用" else "停用"} MCP 服务器「${s.name}」"; touched = true }
                    o.approvalLevel != s.approvalLevel -> {
                        lines += "修改 MCP 服务器「${s.name}」调用审批：${humanApprovalLevel(o.approvalLevel)} → ${humanApprovalLevel(s.approvalLevel)}"; touched = true
                    }
                    o.allowPlaintext != s.allowPlaintext -> {
                        lines += "修改 MCP 服务器「${s.name}」明文 http 白名单：${if (s.allowPlaintext) "开" else "关"}"; touched = true
                    }
                }
            }
            if (parsed.mcpRemoved) {
                cur.keys.filter { k -> new.none { it.id == k } }.forEach { id ->
                    lines += "删除 MCP 服务器「${cur[id]?.name}」"; touched = true
                }
            }
            if (touched) lines += "⚠ MCP 服务器变更会改变代理可调用的外部工具，请确认来源可信"
        }
        parsed.sshTargets?.let { new ->
            val cur = readSsh().associateBy { it.id }
            var touched = false
            new.forEach { t ->
                val o = cur[t.id]
                when {
                    o == null -> { lines += "新增远程连接「${t.name}」（${t.user}@${t.host}:${t.port}）"; touched = true }
                    o.host != t.host || o.port != t.port || o.user != t.user -> {
                        lines += "修改远程连接「${t.name}」：地址 → ${t.user}@${t.host}:${t.port}"; touched = true
                    }
                    o.name != t.name -> { lines += "远程连接「${o.name}」改名为「${t.name}」"; touched = true }
                }
            }
            if (parsed.sshRemoved) {
                cur.keys.filter { k -> new.none { it.id == k } }.forEach { id ->
                    lines += "删除远程连接「${cur[id]?.name}」"; touched = true
                }
            }
            if (touched) lines += "⚠ 远程连接变更会影响代理想远程执行命令的范围，请确认"
        }
        return lines.joinToString("\n")
    }

    /** MCP 审批级别的人话表述。 */
    private fun humanApprovalLevel(level: String): String = if (level == "read") "免审批" else "每次询问"

    /**
     * C6 config_set 入口：把工具补丁合并进当前镜像再走 apply 同一严格校验。
     * 补丁键：providers / mcp_servers / ssh_targets（均按 id 合并）+ 各 *_removed 开关 +
     * settings 白名单键平铺（不接受嵌套 settings 对象，避免双形态漂移）。
     * 返回 (合并后文本, 错误)；错误时不需要 apply（补丁结构非法）。
     */
    fun mergePatch(patch: JsonObject): Pair<String, String?> {
        val current = try {
            Json { isLenient = true }.parseToJsonElement(render(readSettings())).jsonObject
        } catch (e: Exception) {
            return "" to "内部错误：当前配置渲染失败 ${e.message?.take(80)}"
        }
        val unknownKeys = patch.keys - SETTINGS_KEYS -
            setOf("providers", "providers_removed", "mcp_servers", "mcp_servers_removed", "ssh_targets", "ssh_targets_removed")
        if (unknownKeys.isNotEmpty()) {
            return "" to "未知补丁键: ${unknownKeys.joinToString()}（settings 字段请直接平铺在补丁顶层）"
        }

        // 数组分区统一按 id 合并（无 id 的新条目现场补 id）
        fun mergeInto(curEl: JsonElement?, arr: JsonArray): JsonArray {
            val byId = LinkedHashMap<String, JsonObject>()
            ((curEl as? JsonArray) ?: JsonArray(emptyList())).forEach { e2 ->
                (e2 as? JsonObject)?.let { o -> o.str("id")?.let { byId[it] = o } }
            }
            arr.forEach { e2 ->
                (e2 as? JsonObject)?.let { o ->
                    val id = o.str("id") ?: UUID.randomUUID().toString()
                    byId[id] = if (o.containsKey("id")) o else JsonObject(o + ("id" to JsonPrimitive(id)))
                }
            }
            return JsonArray(byId.values.toList())
        }
        // *_removed=true 时补丁名单即"完整保留名单"：不与现有数组合并（否则缺席条目
        // 被旧数据顶回、删除永远不生效，preview 与 apply 同错）；掩码 apiKey 由解析层沿用旧密文
        val providersRemoved = patch["providers_removed"]?.booleanOrNullOr() == true
        val mcpRemoved = patch["mcp_servers_removed"]?.booleanOrNullOr() == true
        val sshRemoved = patch["ssh_targets_removed"]?.booleanOrNullOr() == true
        val mergedProviders: JsonArray = when (val el = patch["providers"]) {
            null -> (current["providers"] as? JsonArray) ?: JsonArray(emptyList())
            is JsonArray ->
                if (providersRemoved) mergeInto(JsonArray(emptyList()), el)
                else mergeInto(current["providers"], el)
            else -> return "" to "providers 必须是数组"
        }
        val mergedMcp: JsonArray? = when (val el = patch["mcp_servers"]) {
            null -> current["mcp_servers"] as? JsonArray
            is JsonArray ->
                if (mcpRemoved) mergeInto(JsonArray(emptyList()), el)
                else mergeInto(current["mcp_servers"], el)
            else -> return "" to "mcp_servers 必须是数组"
        }
        val mergedSsh: JsonArray? = when (val el = patch["ssh_targets"]) {
            null -> current["ssh_targets"] as? JsonArray
            is JsonArray ->
                if (sshRemoved) mergeInto(JsonArray(emptyList()), el)
                else mergeInto(current["ssh_targets"], el)
            else -> return "" to "ssh_targets 必须是数组"
        }

        // settings 白名单键平铺进 settings 对象；*_removed 开关透传顶层
        val stPatch = JsonObject(patch.filterKeys { it in SETTINGS_KEYS })
        val topPatch = JsonObject(patch.filterKeys { it.endsWith("_removed") })
        val mergedText = buildJsonObject {
            current.forEach { (k, v) ->
                if (k != "settings" && k != "providers" && k != "mcp_servers" && k != "ssh_targets") put(k, v)
            }
            put("providers", mergedProviders)
            mergedMcp?.let { put("mcp_servers", it) }
            mergedSsh?.let { put("ssh_targets", it) }
            put("settings", buildJsonObject {
                ((current["settings"] as? JsonObject) ?: JsonObject(emptyMap())).forEach { (k, v) -> put(k, v) }
                stPatch.forEach { (k, v) -> put(k, v) }
            })
            topPatch.forEach { (k, v) -> put(k, v) }
        }.toString()
        return mergedText to null
    }

    /** C1 config_set 预检（合并→解析→语义 diff，不落库）；补丁非法/校验失败返回 err，免审批直接拒绝。 */
    fun previewPatch(patch: JsonObject): Preview {
        val (merged, err) = mergePatch(patch)
        if (err != null) return Preview(err = err)
        val cur = readSettings()
        val parsed = parseToSettings(merged)
        if (!parsed.ok) return Preview(err = parsed.message)
        val base = semanticSummary(cur, parsed.settings)
        val ext = extDiff(parsed)
        val parts = listOf(base, ext).filter { it.isNotBlank() && it != "（无字段变化）" }
        val diff = (if (parts.isEmpty()) listOf("（无字段变化）") else parts).joinToString("\n")
        return Preview(diff = diff.take(700))
    }

    /** C2 拒绝路径脱敏：生成修正用副本（apiKey 与 MCP headers 密钥均脱敏）并覆盖原文件，明文不落盘。 */
    fun rejectAndSanitize(raw: String, reason: String) {
        val masked = maskHeaderValues(maskApiKeys(raw))
        val ts = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
        val copy = File(file.parentFile, "${file.name}.REJECTED.$ts")
        runCatching { copy.writeText(masked) }
        runCatching {
            HaoJson.writeAtomic(file, masked)
            lastRenderedHash = masked.toByteArray(Charsets.UTF_8).md5()
            lastExternalHash = lastRenderedHash
        }
        log("拒绝: $reason（已对 apiKey 脱敏，明文不落盘；修正副本：${copy.name}）")
    }

    // ── 回滚兜底（自锁死保护）：apply 前滚动快照，设置页一键回退 ──

    private val snapshotDir: File get() = File(file.parentFile, "config-snapshots")

    /** 存当前配置快照（保留最近 10 份，文件名即时间戳）。 */
    fun snapshotNow() {
        runCatching {
            snapshotDir.mkdirs()
            val ts = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
            HaoJson.writeAtomic(
                File(snapshotDir, "$ts.json"),
                HaoJson.json.encodeToString(AppSettings.serializer(), readSettings())
            )
            snapshotDir.listFiles()?.sortedByDescending { it.name }?.drop(10)?.forEach { it.delete() }
        }
    }

    /** 快照文件名列表（新→旧）。 */
    fun listSnapshots(): List<String> =
        snapshotDir.listFiles()?.map { it.name }?.sortedDescending() ?: emptyList()

    /** 回退到指定快照（文件名白名单校验防路径穿越）；成功返回 true。 */
    fun restoreSnapshot(name: String): Boolean {
        if (!Regex("""\d{8}-\d{6}\.json""").matches(name)) return false
        val f = File(snapshotDir, name)
        val s = HaoJson.readJsonSafe(f, AppSettings.serializer()) ?: return false
        updateSettings { s }
        log("回退: 已恢复配置快照 $name")
        return true
    }

    private fun permissionModeToKey(m: PermissionMode): String = when (m) {
        PermissionMode.ALWAYS_ASK -> "always_ask"
        PermissionMode.ASK_WRITES -> "ask_writes"
        PermissionMode.YOLO -> "yolo"
    }

    private fun permissionModeFromKey(s: String): PermissionMode = when (s) {
        "always_ask" -> PermissionMode.ALWAYS_ASK
        "yolo" -> PermissionMode.YOLO
        else -> PermissionMode.ASK_WRITES
    }

    private fun AppSettings.withActive(maxTokens: Int? = null, contextLength: Int? = null): AppSettings {
        val active = activeProviderOf(this) ?: return this
        return copy(providers = providers.map {
            if (it.id == active.id) it.copy(
                maxTokens = maxTokens ?: it.maxTokens,
                contextLength = contextLength ?: it.contextLength
            ) else it
        })
    }

    private fun JsonObject.str(key: String): String? {
        val v = this[key] ?: return null
        return (v as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun JsonObject.intOr(key: String): Int? =
        (this[key] as? JsonPrimitive)?.let { runCatching { it.int }.getOrNull() }

    private fun JsonObject.booleanOr(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.let { runCatching { it.boolean }.getOrNull() }

    private fun JsonElement.intOrNullOr(): Int? =
        (this as? JsonPrimitive)?.let { runCatching { it.int }.getOrNull() }

    private fun JsonElement.booleanOrNullOr(): Boolean? =
        (this as? JsonPrimitive)?.let { runCatching { it.boolean }.getOrNull() }

    /** config-bridge.log 固定在状态目录（Backlog#6：不再随工作区漂移；超 512KB 滚动截断）。 */
    private val logFile: File get() = File(file.parentFile ?: File("."), "config-bridge.log")

    private fun log(msg: String) {
        runCatching {
            logFile.parentFile?.mkdirs()
            if (logFile.length() > 512 * 1024) {
                logFile.writeText(logFile.readLines().takeLast(400).joinToString("\n") + "\n")
            }
            logFile.appendText(
                "${java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())}  $msg\n"
            )
        }
    }

    @Volatile private var lastRenderedHash: String? = null
    @Volatile private var lastExternalHash: String? = null
    private var watchJob: Job? = null

    /** 启动监听：每 2s 检查一次；内容两次采样稳定一致且不同于本次渲染结果 → apply。 */
    fun startWatching(scope: CoroutineScope) {
        if (watchJob != null) return
        watchJob = scope.launch {
            renderAndUpdate()
            while (true) {
                delay(2000)
                runCatching {
                    val h = file.md5()
                    if (h == null) {
                        // 文件被删：下轮 renderAndUpdate 重建
                        lastExternalHash = null
                    } else if (h != lastExternalHash) {
                        lastExternalHash = h
                        delay(500)
                        val h2 = file.md5()
                        if (h2 != null && h2 == h && h != lastRenderedHash) {
                            val raw = runCatching { file.readText() }.getOrNull()
                            if (raw != null) {
                                // 成功才回写规范化镜像；拒绝时 C2 脱敏覆盖原文件并另存修正副本
                                val r = apply(raw)
                                if (r.ok) renderAndUpdate() else rejectAndSanitize(raw, r.message)
                            }
                        }
                    }
                }.onFailure { e ->
                    Log.w(TAG, "watch error: ${e.message}")
                }
            }
        }
    }

    /** 渲染并写盘（记录 hash，避免自噬）。 */
    fun renderAndUpdate() {
        val settings = readSettings()
        val text = render(settings)
        val hash = text.toByteArray(Charsets.UTF_8).md5()
        lastRenderedHash = hash
        lastExternalHash = hash
        runCatching { HaoJson.writeAtomic(file, text) }
            .onFailure { Log.w(TAG, "render write failed: ${it.message}") }
    }

    /** settings 变化后由 AppContainer 调用：重渲染镜像。 */
    fun onSettingsChanged() {
        runCatching { renderAndUpdate() }
    }

    private fun File.md5(): String? {
        if (!exists() || !isFile) return null
        return runCatching { inputStream().use { it.readBytes() }.md5() }.getOrNull()
    }

    private fun ByteArray.md5(): String =
        MessageDigest.getInstance("MD5").digest(this).joinToString("") { "%02x".format(it) }
}
