package com.haoai.agent.agent.provider

import com.haoai.agent.data.ProviderConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.IOException

/**
 * 5.2 Provider 失败降级链：主 Provider 网络错误 / HTTP 5xx / 429 时按备用链
 * 切换重试（每级 1 次）。只在【首个事件发出前】切换——流式中途失败不切换
 * （上下文协议差异 + 用户已看到的半截回复不重放），直接抛原始错误。
 * 全部失败时抛首个错误。
 */
class FallbackClient(
    private val resolve: (ProviderConfig) -> ProviderClient,
    private val lookup: (String) -> ProviderConfig?,
    private val decrypt: (ProviderConfig) -> String,
    private val fallbackIds: List<String>,
    /** 切换成功时通知 UI（「已降级到 X」）。 */
    private val onFallback: (name: String) -> Unit = {}
) : ProviderClient {

    override suspend fun chatStream(
        provider: ProviderConfig,
        apiKey: String,
        messages: List<ApiMessage>,
        tools: List<ApiTool>,
        reasoningEffort: String?
    ): Flow<SseEvent> = flow {
        var lastError: Exception? = null

        // 主 Provider
        var emitted = false
        try {
            resolve(provider).chatStream(provider, apiKey, messages, tools, reasoningEffort).collect {
                emitted = true
                emit(it)
            }
            return@flow
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            android.util.Log.w("HaoFallback", "primary failed (emitted=$emitted): ${e.javaClass.simpleName}: ${e.message?.take(120)}")
            if (emitted || !isFallbackEligible(e)) throw e
            if (lastError == null) lastError = e // 保留首个（主服务）错误，全链失败时抛回
        }

        // 备用链（跳过与主相同的 id）
        for (id in fallbackIds) {
            if (id == provider.id) continue
            val fb = lookup(id) ?: continue
            if (fb.baseUrl.startsWith("local")) continue
            emitted = false
            try {
                val key = decrypt(fb)
                resolve(fb).chatStream(fb, key, messages, tools, reasoningEffort).collect {
                    if (!emitted) {
                        emitted = true
                        onFallback(fb.name)
                    }
                    emit(it)
                }
                return@flow
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                if (emitted || !isFallbackEligible(e)) throw e
                if (lastError == null) lastError = e
            }
        }
        throw lastError ?: IOException("降级链全部失败")
    }

    override suspend fun testConnection(provider: ProviderConfig, apiKey: String): Result<String> =
        resolve(provider).testConnection(provider, apiKey)

    companion object {
        /** 可降级错误：网络层 IOException（无 HTTP 前缀）+ HTTP 5xx + 429；4xx 业务错误不切。 */
        fun isFallbackEligible(e: Exception): Boolean {
            if (e !is IOException) return false
            val msg = e.message.orEmpty()
            val m = Regex("^HTTP (\\d{3})").find(msg) ?: return true // 无 HTTP 标记 = 连接/读超时/DNS 等网络错误
            val code = m.groupValues[1].toInt()
            return code >= 500 || code == 429
        }
    }
}
