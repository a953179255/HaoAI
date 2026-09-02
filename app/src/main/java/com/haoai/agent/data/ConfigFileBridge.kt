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
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * 配置桥（对标 上游 上游.json，C6：配置源位于状态目录 filesDir/state/haoai.config.json）：
 * - render：把 AppSettings 渲染成状态目录 `haoai.config.json`（API key 一律 `****` 掩码，不留明文）
 * - 监听：文件被外部（adb/文件管理器）改动后自动解析校验 → 经 AppContainer.updateSettings 合并入库；
 *   apiKey 写明文时立即用 KeystoreCipher 加密（真源 settings.json 始终是密文）
 * - agent 侧入口是 config_get/config_set 工具（恒审批，见 ConfigTools）；本桥保留 2s 轮询
 *   作为「用户手改状态目录文件」的兜底路径
 * - 严格校验（上游 式）：未知键/非法值/缺必填 → 整体拒绝并保留上次配置，结果写 config-bridge.log
 */
class ConfigFileBridge(
    private val file: File,
    private val cipher: KeystoreCipher,
    private val updateSettings: ((AppSettings) -> AppSettings) -> Unit,
    private val readSettings: () -> AppSettings
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
            "daily_token_budget_k", "keep_alive", "dream_provider", "dream_idle_minutes"
        )

        /** C3 顶层合法键（providers_removed 为显式删除开关，见 apply）。 */
        val TOP_LEVEL_KEYS = setOf("providers", "settings", "providers_removed")

        /** 端侧条目 id（豁免显式删除；与 LlamaServerController.LOCAL_PROVIDER_ID 同值）。 */
        const val LOCAL_PROVIDER_ID = "local"

        val PROVIDER_KEYS = setOf(
            "id", "name", "baseUrl", "model", "protocol", "apiKey",
            "contextLength", "maxTokens", "active"
        )

        /** C4 permission_mode 枚举（与 PermissionMode 一一对应，lowercase）。 */
        val PERMISSION_MODES = setOf("always_ask", "ask_writes", "yolo")

        /** C3/C4 数值钳制边界（reply/context 与旧 update_settings 路径一致）。 */
        const val REPLY_MAX_TOKENS_MIN = 256
        const val REPLY_MAX_TOKENS_MAX = 1_000_000
        const val CONTEXT_LENGTH_MIN = 1024
        const val CONTEXT_LENGTH_MAX = 10_000_000
        const val LOCAL_CONTEXT_MIN = 2048
        const val LOCAL_CONTEXT_MAX = 262_144

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
    }

    data class ApplyResult(val ok: Boolean, val message: String)

    /** C1 config_set 预检结果：err 非空=补丁非法（免审批直接拒绝）；diff 供审批弹窗展示。 */
    data class Preview(val diff: String = "", val err: String? = null)

    private fun activeProviderOf(s: AppSettings): ProviderConfig? =
        s.providers.find { it.id == s.activeProviderId } ?: s.providers.firstOrNull()

    /** 渲染当前设置到镜像 JSON（apiKey 掩码化）。永不输出 providers_removed（镜像闭合，正常往返不触发删除）。 */
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
        }
        return Json { encodeDefaults = true }.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("providers", JsonArray(providers))
                put("settings", st)
            }
        )
    }

    /** 解析并应用外部配置；严格校验，失败整体拒绝。 */
    fun apply(raw: String): ApplyResult {
        val (next, msg, ok) = parseToSettings(raw)
        if (!ok) return ApplyResult(false, msg)
        updateSettings { next }
        log(msg)
        return ApplyResult(true, msg)
    }

    /**
     * 与 apply 完全同源的解析（C1 语义 diff 与 config_set 共用）：成功返回将要生效的
     * AppSettings 与结果消息；失败返回错误原因（ok=false，settings 为当前配置原值）。
     * C3：providers 合并式（缺席保留 + providers_removed 显式删除）；baseUrl/protocol
     * 变更且 key 为掩码 → 重认证拒绝；数值钳制对齐旧路径。
     */
    fun parseToSettings(raw: String): Triple<AppSettings, String, Boolean> {
        val cur = readSettings()
        val root = try {
            Json { isLenient = true }.parseToJsonElement(raw).jsonObject
        } catch (e: Exception) {
            return Triple(cur, rejected("JSON 解析失败: ${e.message?.take(120)}"), false)
        }
        val unknownTop = root.keys - TOP_LEVEL_KEYS
        if (unknownTop.isNotEmpty()) return Triple(cur, rejected("未知顶层键: ${unknownTop.joinToString()}"), false)

        val errors = mutableListOf<String>()
        val newProviders = mutableListOf<ProviderConfig>()
        val seenIds = mutableSetOf<String>()
        var newActiveId = cur.activeProviderId

        val provArr = root["providers"]?.let { runCatching { it as kotlinx.serialization.json.JsonArray }.getOrNull() }
            ?: JsonArray(emptyList())

        provArr.forEachIndexed { i, el ->
            fun fail(msg: String) { errors += "providers[$i] $msg" }
            val p = runCatching { el.jsonObject }.getOrNull() ?: run { fail("必须是对象"); return@forEachIndexed }
            val unknownP = p.keys - PROVIDER_KEYS
            if (unknownP.isNotEmpty()) { fail("未知键: ${unknownP.joinToString()}"); return@forEachIndexed }
            val name = p.str("name") ?: run { fail("缺 name"); return@forEachIndexed }
            val baseUrl = p.str("baseUrl") ?: run { fail("缺 baseUrl"); return@forEachIndexed }
            val model = p.str("model") ?: run { fail("缺 model"); return@forEachIndexed }
            if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://") && !baseUrl.startsWith("local://")) {
                fail("baseUrl 必须 http(s):// 或 local://"); return@forEachIndexed
            }
            val protocol = p.str("protocol") ?: "openai_compat"
            if (protocol !in setOf("openai_compat", "anthropic")) {
                fail("protocol 仅支持 openai_compat/anthropic"); return@forEachIndexed
            }
            val contextLength = p.intOr("contextLength") ?: 0
            val maxTokens = p.intOr("maxTokens") ?: 0
            if (contextLength < 0 || maxTokens < 0) {
                fail("contextLength/maxTokens 不能为负"); return@forEachIndexed
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
                fail("provider「$name」的 baseUrl/protocol 变更必须同时提供明文 apiKey（掩码视为拒绝）")
                return@forEachIndexed
            }
            val newCipher = when {
                apiKeyRaw.isBlank() || apiKeyRaw == MASK -> {
                    if (existing == null) { fail("新 provider 必须提供 apiKey（不能是 ****/空）"); return@forEachIndexed }
                    existing.apiKeyCipher
                }
                else -> cipher.encrypt(apiKeyRaw) ?: run {
                    fail("apiKey 加密失败（Keystore 不可用）"); return@forEachIndexed
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
        if (errors.isNotEmpty()) return Triple(cur, rejected(errors.take(3).joinToString("；")), false)

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
        if (unknownS.isNotEmpty()) return Triple(cur, rejected("settings 未知键: ${unknownS.joinToString()}"), false)

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
                return Triple(cur, rejected("permission_mode 仅支持 ${PERMISSION_MODES.joinToString("/")}"), false)
            }
            next = next.copy(permissionMode = permissionModeFromKey(s))
        }
        st["fallback_chain"]?.let { el ->
            val arr = el as? JsonArray
                ?: return Triple(cur, rejected("fallback_chain 必须是 providerId 字符串数组"), false)
            val ids = arr.mapNotNull { (it as? JsonPrimitive)?.content?.trim() }.filter { it.isNotBlank() }
            val bad = ids.filter { pid -> next.providers.none { it.id == pid } }
            if (bad.isNotEmpty()) {
                return Triple(cur, rejected("fallback_chain 引用了不存在的 providerId: ${bad.joinToString()}"), false)
            }
            next = next.copy(fallbackChain = ids)
        }
        for ((key, apply2) in PURPOSE_FIELDS) {
            st[key]?.let { el ->
                val s = (el as? JsonPrimitive)?.content?.trim().orEmpty()
                if (s.isNotEmpty() && s != "local" && next.providers.none { it.id == s }) {
                    return Triple(cur, rejected("$key 引用了不存在的 providerId: $s"), false)
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
                return Triple(cur, rejected("dream_provider 引用了不存在的 providerId: $s"), false)
            }
            next = next.copy(dreamProviderId = s)
        }
        st["dream_idle_minutes"]?.intOrNullOr()?.let { v ->
            next = next.copy(dreamIdleMinutes = v.coerceIn(5, 240))
        }

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
        return Triple(next, msg, true)
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
    fun semanticSummary(old: AppSettings, new: AppSettings): String {
        val lines = mutableListOf<String>()
        val oldIds = old.providers.associateBy { it.id }
        val newIds = new.providers.associateBy { it.id }
        newIds.forEach { (id, p) ->
            val o = oldIds[id]
            when {
                o == null -> lines += "+ provider [${p.name}] = ${p.baseUrl} / ${p.model}"
                o.baseUrl != p.baseUrl || o.protocol != p.protocol -> {
                    val ch = mutableListOf<String>()
                    if (o.baseUrl != p.baseUrl) ch += "baseUrl ${o.baseUrl} → ${p.baseUrl}"
                    if (o.protocol != p.protocol) ch += "protocol ${o.protocol} → ${p.protocol}"
                    lines += "~ provider [${p.name}] ${ch.joinToString(" / ")}"
                }
                o.model != p.model -> lines += "~ provider [${p.name}] model ${o.model} → ${p.model}"
            }
        }
        oldIds.forEach { (id, o) ->
            if (id !in newIds) lines += "- provider [${o.name}]"
        }
        val oldActive = activeProviderOf(old)
        val newActive = activeProviderOf(new)
        if (oldActive?.id != newActive?.id && newActive != null) {
            lines += "◉ 当前模型 → [${newActive.name}]"
        }
        fun cmp(name: String, ov: Any?, nv: Any?) {
            if (ov != nv) lines += "* $name: $ov → $nv"
        }
        cmp("单次回复上限", oldActive?.maxTokens, newActive?.maxTokens)
        cmp("云端上下文窗口", oldActive?.contextLength, newActive?.contextLength)
        cmp("local_context_length", old.localContextLength, new.localContextLength)
        cmp("记忆系统", old.memoryEnabled, new.memoryEnabled)
        cmp("自动学习", old.autoLearn, new.autoLearn)
        cmp("闲置整理记忆", old.deepDream, new.deepDream)
        cmp("permission_mode", permissionModeToKey(old.permissionMode), permissionModeToKey(new.permissionMode))
        if (new.fallbackChain != old.fallbackChain) {
            val added = new.fallbackChain - old.fallbackChain
            val removed = old.fallbackChain - new.fallbackChain
            val parts = listOfNotNull(
                if (added.isNotEmpty()) "+[${added.joinToString()}]" else null,
                if (removed.isNotEmpty()) "-[${removed.joinToString()}]" else null
            )
            lines += "* 降级链 ${parts.joinToString(" ")}"
        }
        cmp("memory_extract_provider", old.memoryExtractProviderId, new.memoryExtractProviderId)
        cmp("title_provider", old.titleProviderId, new.titleProviderId)
        cmp("summarize_provider", old.summarizeProviderId, new.summarizeProviderId)
        cmp("daily_token_budget_k", old.dailyTokenBudgetK, new.dailyTokenBudgetK)
        cmp("keep_alive", old.keepAlive, new.keepAlive)
        cmp("dream_provider", old.dreamProviderId, new.dreamProviderId)
        cmp("dream_idle_minutes", old.dreamIdleMinutes, new.dreamIdleMinutes)
        return lines.joinToString("\n").ifBlank { "（无字段变化）" }.take(500)
    }

    /**
     * C6 config_set 入口：把工具补丁合并进当前镜像再走 apply 同一严格校验。
     * 补丁键：providers（按 id 合并，改哪个传哪个）/ providers_removed / settings 白名单键平铺
     * （不接受嵌套 settings 对象，避免双形态漂移）。
     * 返回 (合并后文本, 错误)；错误时不需要 apply（补丁结构非法）。
     */
    fun mergePatch(patch: JsonObject): Pair<String, String?> {
        val current = try {
            Json { isLenient = true }.parseToJsonElement(render(readSettings())).jsonObject
        } catch (e: Exception) {
            return "" to "内部错误：当前配置渲染失败 ${e.message?.take(80)}"
        }
        val unknownKeys = patch.keys - SETTINGS_KEYS - setOf("providers", "providers_removed")
        if (unknownKeys.isNotEmpty()) {
            return "" to "未知补丁键: ${unknownKeys.joinToString()}（settings 字段请直接平铺在补丁顶层）"
        }

        // patch.providers：按 id 合并进当前列表（无 id 的新条目现场补 id）
        val mergedProviders: JsonArray = when (val el = patch["providers"]) {
            null -> JsonArray(emptyList())
            is JsonArray -> {
                val byId = LinkedHashMap<String, JsonObject>()
                ((current["providers"] as? JsonArray) ?: JsonArray(emptyList())).forEach { e2 ->
                    (e2 as? JsonObject)?.let { o ->
                        o.str("id")?.let { byId[it] = o }
                    }
                }
                el.forEach { e2 ->
                    val o = e2 as? JsonObject
                        ?: return "" to "providers 每项必须是对象"
                    val id = o.str("id") ?: UUID.randomUUID().toString()
                    byId[id] = if (o.containsKey("id")) o else JsonObject(o + ("id" to JsonPrimitive(id)))
                }
                JsonArray(byId.values.toList())
            }
            else -> return "" to "providers 必须是数组"
        }

        // settings 白名单键平铺进 settings 对象；providers_removed 透传顶层
        val stPatch = JsonObject(patch.filterKeys { it in SETTINGS_KEYS })
        val topPatch = JsonObject(patch.filterKeys { it == "providers_removed" })
        val mergedText = buildJsonObject {
            current.forEach { (k, v) -> if (k != "settings" && k != "providers") put(k, v) }
            put("providers", mergedProviders)
            put("settings", buildJsonObject {
                ((current["settings"] as? JsonObject) ?: JsonObject(emptyMap())).forEach { (k, v) -> put(k, v) }
                stPatch.forEach { (k, v) -> put(k, v) }
            })
            topPatch.forEach { (k, v) -> put(k, v) }
        }.toString()
        return mergedText to null
    }

    /** C2 拒绝路径脱敏：生成修正用副本（apiKey 已脱敏）并覆盖原文件，明文不落盘。 */
    fun rejectAndSanitize(raw: String, reason: String) {
        val masked = maskApiKeys(raw)
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

    /** 拒绝路径统一文案（parseToSettings 内部用，同时落 config-bridge.log）。 */
    private fun rejected(msg: String): String {
        log("拒绝: $msg")
        return msg
    }

    /** C1 config_set 预检（合并→解析→语义 diff，不落库）；补丁非法/校验失败返回 err，免审批直接拒绝。 */
    fun previewPatch(patch: JsonObject): Preview {
        val (merged, err) = mergePatch(patch)
        if (err != null) return Preview(err = err)
        val cur = readSettings()
        val (next, msg, ok) = parseToSettings(merged)
        if (!ok) return Preview(err = msg)
        return Preview(diff = semanticSummary(cur, next))
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
