package com.haoai.agent.agent.provider

import com.haoai.agent.data.ProviderConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.IOException
import kotlin.random.Random

/**
 * E2 请求级重试（实现 ProviderClient，包装在真实客户端之上）：
 * 网络错误 / HTTP 429 / 5xx / 空流（无任何 delta 即失败）时指数退避重试
 * （1s/2s/4s ± 抖动），最多 3 次（1 原始 + 2 重试）。
 * 已产出任何 delta 后失败 → 不重试（防重复输出），按原错误抛回；
 * 400/401/403 业务错误不重试（400 专属 overflow 路径，由引擎另行处理）。
 */
class ResilientCall(
    private val resolve: (ProviderConfig) -> ProviderClient,
    private val maxAttempts: Int = 3
) : ProviderClient {

    private fun hasContent(e: SseEvent): Boolean = when (e) {
        is SseEvent.Delta -> true
        is SseEvent.Reasoning -> true
        else -> false
    }

    override suspend fun chatStream(
        provider: ProviderConfig,
        apiKey: String,
        messages: List<ApiMessage>,
        tools: List<ApiTool>,
        reasoningEffort: String?
    ): Flow<SseEvent> = flow {
        var attempt = 0
        while (true) {
            attempt++
            var emitted = false
            try {
                resolve(provider).chatStream(provider, apiKey, messages, tools, reasoningEffort).collect { e ->
                    if (!emitted && hasContent(e)) emitted = true
                    emit(e)
                }
                return@flow
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (e: IOException) {
                // 已产出内容：重试会整段重放，违反防重复原则——直接抛
                if (emitted) throw e
                if (!isRetryable(e)) throw e
                if (attempt >= maxAttempts) throw e
                delayWithJitter(attempt)
            } catch (e: Exception) {
                throw e
            }
        }
    }

    override suspend fun testConnection(provider: ProviderConfig, apiKey: String): Result<String> =
        resolve(provider).testConnection(provider, apiKey)

    private fun isRetryable(e: IOException): Boolean {
        val msg = e.message.orEmpty()
        val m = Regex("^HTTP (\\d{3})").find(msg)
        if (m != null) {
            val code = m.groupValues[1].toInt()
            // 429 / 5xx 可重试；400/401/403 业务错误不重试
            return code == 429 || code >= 500
        }
        // 无 HTTP 标记：连接/读超时/DNS 等网络错误
        return true
    }

    private suspend fun delayWithJitter(attempt: Int) {
        val base = when (attempt) {
            1 -> 1_000L
            2 -> 2_000L
            else -> 4_000L
        }
        val jitter = Random.nextLong(0, base / 3)
        kotlinx.coroutines.delay(base + jitter)
    }
}
