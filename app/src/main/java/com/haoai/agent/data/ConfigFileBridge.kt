package com.haoai.agent.data

import android.util.Log
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
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * 工作区配置文件桥（对标 上游 上游.json）：
 * - render：把 AppSettings 渲染成工作区 `haoai.config.json`（API key 一律 `****` 掩码，不留明文）
 * - 监听：文件被外部（agent/用户）改动后自动解析校验 → 经 AppContainer.updateSettings 合并入库；
 *   apiKey 写明文时立即用 KeystoreCipher 加密（真源 settings.json 始终是密文）
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
        /** settings 白名单：与 UpdateSettingsTool 同一清单，其余键一律拒绝 */
        private val SETTINGS_KEYS = setOf(
            "reply_max_tokens", "context_length", "local_context_length",
            "memory_enabled", "auto_learn", "deep_dream"
        )
        private val PROVIDER_KEYS = setOf(
            "id", "name", "baseUrl", "model", "protocol", "apiKey",
            "contextLength", "maxTokens", "active"
        )
    }

    data class ApplyResult(val ok: Boolean, val message: String)

    private fun activeProviderOf(s: AppSettings): ProviderConfig? =
        s.providers.find { it.id == s.activeProviderId } ?: s.providers.firstOrNull()

    /** 渲染当前设置到镜像 JSON（apiKey 掩码化）。 */
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
        val root = try {
            Json { isLenient = true }.parseToJsonElement(raw).jsonObject
        } catch (e: Exception) {
            return reject("JSON 解析失败: ${e.message?.take(120)}")
        }
        val unknownTop = root.keys - setOf("providers", "settings")
        if (unknownTop.isNotEmpty()) return reject("未知顶层键: ${unknownTop.joinToString()}")

        val cur = readSettings()
        val errors = mutableListOf<String>()
        val newProviders = mutableListOf<ProviderConfig>()
        val keptIds = mutableSetOf<String>()

        val provArr = root["providers"]?.let { runCatching { it as kotlinx.serialization.json.JsonArray }.getOrNull() } ?: JsonArray(emptyList())
        var newActiveId = cur.activeProviderId

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
            val id = p.str("id") ?: UUID.randomUUID().toString()
            val apiKeyRaw = p.str("apiKey") ?: ""
            val active = p.booleanOr("active") ?: false
            val existing = cur.providers.find { it.id == id }
            val newCipher = when {
                apiKeyRaw.isBlank() || apiKeyRaw == MASK -> {
                    if (existing == null) { fail("新 provider 必须提供 apiKey（不能是 ****/空）"); return@forEachIndexed }
                    existing.apiKeyCipher
                }
                else -> cipher.encrypt(apiKeyRaw) ?: run {
                    fail("apiKey 加密失败（Keystore 不可用）"); return@forEachIndexed
                }
            }
            newProviders += ProviderConfig(
                id = id, name = name, baseUrl = baseUrl, model = model,
                apiKeyCipher = newCipher, protocol = protocol,
                contextLength = contextLength, maxTokens = maxTokens
            )
            keptIds += id
            if (active) newActiveId = id
        }
        if (errors.isNotEmpty()) return reject(errors.take(3).joinToString("；"))

        val st = root["settings"]?.let { runCatching { it.jsonObject }.getOrNull() } ?: JsonObject(emptyMap())
        val unknownS = st.keys - SETTINGS_KEYS
        if (unknownS.isNotEmpty()) return reject("settings 未知键: ${unknownS.joinToString()}")

        // providers 全量替换为镜像内容（镜像删除的 provider 即从真源移除）；保留 local 端侧条目
        val merged = newProviders + cur.providers.filter { it.id == "local" }
        val activeTarget = merged.find { it.id == newActiveId } ?: merged.firstOrNull()
        var next = cur.copy(
            providers = merged,
            activeProviderId = activeTarget?.id ?: cur.activeProviderId
        )
        st["reply_max_tokens"]?.intOrNullOr()?.let { v ->
            next = next.withActive(maxTokens = v)
        }
        st["context_length"]?.intOrNullOr()?.let { v ->
            next = next.withActive(contextLength = v)
        }
        st["local_context_length"]?.intOrNullOr()?.let { v ->
            next = next.copy(localContextLength = v.coerceIn(2048, 262144))
        }
        st["memory_enabled"]?.booleanOrNullOr()?.let { next = next.copy(memoryEnabled = it) }
        st["auto_learn"]?.booleanOrNullOr()?.let { next = next.copy(autoLearn = it) }
        st["deep_dream"]?.booleanOrNullOr()?.let { next = next.copy(deepDream = it) }

        updateSettings { next }
        val msg = "已应用: ${newProviders.size} 个 provider，${activeTarget?.let { "当前 = ${it.name}" } ?: "无 provider"}"
        log(msg)
        return ApplyResult(true, msg)
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

    private fun reject(msg: String): ApplyResult {
        log("拒绝: $msg")
        return ApplyResult(false, msg)
    }

    private val logFile: File get() = File(file.parentFile?.parentFile ?: File("."), "config-bridge.log")

    private fun log(msg: String) {
        runCatching {
            logFile.parentFile?.mkdirs()
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
                                // 成功才回写镜像（apiKey 掩码化）；拒绝时保留 agent 原文件，
                                // 便于告之失败原因后原样修正
                                if (apply(raw).ok) renderAndUpdate()
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
