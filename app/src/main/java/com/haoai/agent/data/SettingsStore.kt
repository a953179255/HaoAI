package com.haoai.agent.data

import android.content.Context
import com.haoai.agent.agent.policy.PermissionMode
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
data class ProviderConfig(
    val id: String,
    val name: String,
    val baseUrl: String,
    val model: String,
    val apiKeyCipher: String = "",
    /** 上下文窗口（tokens）。0 = 按模型名自动推测。 */
    val contextLength: Int = 0,
    /** 单次回复上限（max_tokens）。0 = 供应商默认（部分服务仅 4K，易截断）。 */
    val maxTokens: Int = 0
) {
    /** 实际生效的上下文窗口：未配置时按模型名推测。 */
    fun effectiveContextLength(): Int =
        contextLength.takeIf { it > 0 } ?: guessContextLength(model)

    /** 实际生效的单次回复上限：未配置时本地 4096，云端交供应商默认。 */
    fun effectiveMaxTokens(): Int =
        maxTokens.takeIf { it > 0 } ?: if (baseUrl.contains("127.0.0.1") || baseUrl.startsWith("local")) 4096 else 0

    companion object {
        /** 按模型名推测上下文窗口（常见模型速查，未命中给保守默认）。 */
        fun guessContextLength(model: String): Int {
            val m = model.lowercase()
            return when {
                "1m" in m || "1-million" in m -> 1_000_000
                "gemini-2.5-pro" in m || "gemini-2.0" in m -> 1_048_576
                "gemini" in m -> 1_048_576
                "gpt-4.1" in m || "gpt-5" in m -> 400_000
                "claude" in m -> 200_000
                "o1" in m || "o3" in m || "o4" in m -> 200_000
                "kimi" in m || "moonshot" in m -> 256_000
                "deepseek" in m -> 131_072
                "qwen" in m -> 131_072
                "glm-4" in m || "glm4" in m -> 131_072
                "grok" in m -> 131_072
                "llama-3" in m || "llama3" in m -> 131_072
                "gpt-4o" in m || "gpt-4-turbo" in m -> 128_000
                "gpt-4" in m -> 8_192
                "gpt-3.5" in m -> 16_385
                else -> 32_768
            }
        }
    }
}

@Serializable
data class AppSettings(
    val providers: List<ProviderConfig> = emptyList(),
    val activeProviderId: String? = null,
    val permissionMode: PermissionMode = PermissionMode.ASK_WRITES,
    val keepAlive: Boolean = true,
    val customPrompt: String = "",
    val memoryEnabled: Boolean = true,
    val deepDream: Boolean = false,
    val autoLearn: Boolean = true,
    val dreamProviderId: String = "local",
    val dreamIdleMinutes: Int = 15,
    val themeMode: String = "light",
    val reasoningEffort: String = "",
    val localModelFile: String? = null,
    val agentName: String = "",
    val soul: String = "",
    val onboarded: Boolean = false,
    val tokenInTotal: Long = 0,
    val tokenOutTotal: Long = 0,
    /** 今日用量（跨天自动归零）。tokenDay 为 yyyy-MM-dd。 */
    val tokenDay: String = "",
    val tokenInToday: Long = 0,
    val tokenOutToday: Long = 0,
    /** 端侧推理上下文窗口（llama.cpp -c 参数）。Agent 场景系统提示+工具定义就数千 token，默认 64K。 */
    val localContextLength: Int = 65536
)

class SettingsStore(context: Context) {

    private val file: File = File(context.filesDir, "settings/settings.json")

    // 设置写入频率低但序列化+磁盘 IO 不该卡主线程；单线程串行保证顺序
    private val io = java.util.concurrent.Executors.newSingleThreadExecutor()

    @Serializable
    private data class Wrapped(val settings: AppSettings = AppSettings())

    fun load(): AppSettings =
        HaoJson.readTextSafe(file)?.let { t ->
            runCatching { HaoJson.json.decodeFromString(Wrapped.serializer(), t).settings }.getOrNull()
        } ?: AppSettings()

    fun save(settings: AppSettings) {
        io.execute {
            runCatching {
                HaoJson.writeAtomic(file, HaoJson.json.encodeToString(Wrapped.serializer(), Wrapped(settings)))
            }.onFailure {
                android.util.Log.w("HaoSettings", "设置保存失败（检查文件属主/权限）: ${it.message}")
            }
        }
    }
}
