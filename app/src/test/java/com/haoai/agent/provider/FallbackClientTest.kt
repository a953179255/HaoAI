package com.haoai.agent.provider

import com.haoai.agent.agent.provider.ApiMessage
import com.haoai.agent.agent.provider.ApiTool
import com.haoai.agent.agent.provider.FallbackClient
import com.haoai.agent.agent.provider.ProviderClient
import com.haoai.agent.agent.provider.SseEvent
import com.haoai.agent.data.ProviderConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * 5.2 降级链纯逻辑单测：可降级错误判定、主败备成、4xx 不降级、
 * 流式中途失败不切换（已发出事件后直接抛原始错误）。
 */
class FallbackClientTest {

    private val provider = ProviderConfig(id = "p1", name = "主", baseUrl = "https://a.example/v1", model = "m")
    private val backup = ProviderConfig(id = "p2", name = "备", baseUrl = "https://b.example/v1", model = "m2")

    /** 行为脚本返回事件序列；元素为 Exception 时在该位置抛出。 */
    private fun fake(behavior: (String) -> List<Any>): ProviderClient =
        object : ProviderClient {
            override suspend fun chatStream(
                provider: ProviderConfig,
                apiKey: String,
                messages: List<ApiMessage>,
                tools: List<ApiTool>,
                reasoningEffort: String?
            ): Flow<SseEvent> = flow {
                behavior(provider.id).forEach { el ->
                    when (el) {
                        is SseEvent -> emit(el)
                        is Exception -> throw el
                    }
                }
            }

            override suspend fun testConnection(provider: ProviderConfig, apiKey: String): Result<String> =
                Result.success("ok")
        }

    private fun fallbackClient(primary: ProviderClient, bak: ProviderClient, onFallback: (String) -> Unit = {}) =
        FallbackClient(
            resolve = { if (it.id == "p2") bak else primary },
            lookup = { if (it == "p2") backup else null },
            decrypt = { "" },
            fallbackIds = listOf("p2"),
            onFallback = onFallback
        )

    private val msgs = listOf(ApiMessage(role = "user", content = "hi"))

    @Test
    fun `可降级错误判定`() {
        assertTrue(FallbackClient.isFallbackEligible(IOException("HTTP 500（x）：boom")))
        assertTrue(FallbackClient.isFallbackEligible(IOException("HTTP 429（x）：rate")))
        assertTrue(FallbackClient.isFallbackEligible(IOException("timeout connect")))
        assertTrue(!FallbackClient.isFallbackEligible(IOException("HTTP 401（x）：bad key")))
        assertTrue(!FallbackClient.isFallbackEligible(IllegalStateException("x")))
    }

    @Test
    fun `主败备成并发降级通知`() = runBlocking {
        val primary = fake { listOf<Any>(IOException("HTTP 503（主）：down")) }
        val bak = fake { id -> if (id == "p2") listOf<Any>(SseEvent.Delta("ok")) else emptyList() }
        var notice = ""
        val out = fallbackClient(primary, bak) { notice = it }
            .chatStream(provider, "", msgs, emptyList(), null).toList()
        assertEquals("ok", (out.single() as SseEvent.Delta).text)
        assertEquals("备", notice)
    }

    @Test
    fun `4xx 不降级直接抛原始错误`() = runBlocking {
        val primary = fake { listOf<Any>(IOException("HTTP 401（主）：bad key")) }
        val bak = fake { listOf<Any>(SseEvent.Delta("should not")) }
        try {
            fallbackClient(primary, bak).chatStream(provider, "", msgs, emptyList(), null).toList()
            throw AssertionError("should throw")
        } catch (e: IOException) {
            assertTrue(e.message.orEmpty().contains("401"))
        }
    }

    @Test
    fun `流式中途失败不切换`() = runBlocking {
        val primary = fake { id ->
            if (id == "p1") listOf(SseEvent.Delta("half-"), IOException("HTTP 502 mid-stream")) else emptyList()
        }
        val bak = fake { listOf<Any>(SseEvent.Delta("fallback text")) }
        try {
            fallbackClient(primary, bak).chatStream(provider, "", msgs, emptyList(), null).toList()
            throw AssertionError("should throw")
        } catch (e: IOException) {
            assertTrue(e.message.orEmpty().contains("502"))
        }
    }

    @Test
    fun `全链失败抛首错`() = runBlocking {
        val primary = fake { listOf<Any>(IOException("HTTP 500 primary")) }
        val bak = fake { listOf<Any>(IOException("HTTP 500 backup")) }
        try {
            fallbackClient(primary, bak).chatStream(provider, "", msgs, emptyList(), null).toList()
            throw AssertionError("should throw")
        } catch (e: IOException) {
            assertTrue(e.message.orEmpty().contains("primary"))
        }
    }
}
