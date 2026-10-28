package com.haoai.agent.provider

import com.haoai.agent.agent.provider.ApiMessage
import com.haoai.agent.agent.provider.OpenAiCompatClient
import com.haoai.agent.data.ProviderConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

/**
 * 停止键能否立刻打断「模型连接中」的回合（2026-10-28 真机实锤的回归锁）。
 *
 * ## 现场
 *
 * 用户按输入框右侧的停止键，任务一直不停，UI 状态胶囊持续转圈，
 * 引擎实际又跑了 120.5 秒才收尾（= OkHttp readTimeout 120s）。
 *
 * ## 根因
 *
 * SSE 读循环是**阻塞 socket 读**（`source.readUtf8Line()`），协程取消
 * 不会自动中断它。旧代码用 `invokeOnCompletion { call.cancel() }` 挂取消钩子，
 * 而 invokeOnCompletion 只在协程**彻底结束**后才回调 —— 而结束的前提正是这个
 * 阻塞读读完了，形成死锁：
 *
 * ```
 * 取消请求发出 → 读还阻塞 → 协程不结束 → 钩子不触发
 *             → call.cancel() 不执行 → 一直卡到 readTimeout（120s）
 * ```
 *
 * 修复是让「取消请求的瞬间」就断链（见 StreamCancelWatchdog）。
 *
 * ## 本测试如何复现
 *
 * 起一个真实服务器：**发完 HTTP 响应头后保持连接、永不发任何数据**。
 * 这样客户端的 `awaitResponse` 能正常返回（响应头到了），随后卡在
 * `source.readUtf8Line()` 这个**真正的阻塞读**上——与现场「上游排队、
 * 连接挂着不出 token」的场景同构。
 *
 * 断言：取消后 1 秒内收集协程必须收尾（旧行为下会卡到 readTimeout=120s）。
 */
class StreamCancelTest {

    @Test
    fun cancel_interrupts_blocked_stream_read_promptly() = runBlocking {
        ServerSocket(0).use { server ->
            val accepted = mutableListOf<java.net.Socket>()
            Thread {
                while (!server.isClosed) {
                    try {
                        val sock = server.accept()
                        accepted += sock
                        // 发完响应头就停住：响应体永远不来 → 客户端卡在 readUtf8Line()
                        runCatching {
                            sock.getOutputStream().apply {
                                write(
                                    ("HTTP/1.1 200 OK\r\n" +
                                        "Content-Type: text/event-stream\r\n" +
                                        "Cache-Control: no-cache\r\n" +
                                        "Connection: keep-alive\r\n" +
                                        "\r\n").toByteArray()
                                )
                                flush()
                            }
                        }
                    } catch (_: IOException) {
                        break
                    }
                }
            }.apply { isDaemon = true }.start()

            val client = OkHttpClient.Builder()
                .readTimeout(120, TimeUnit.SECONDS)
                .build()
            val provider = ProviderConfig(
                id = "p",
                name = "静默服务器",
                baseUrl = "http://127.0.0.1:${server.localPort}/v1",
                model = "m"
            )

            val openAi = OpenAiCompatClient(client)
            var finished = false
            val job = launch(Dispatchers.IO) {
                try {
                    openAi.chatStream(
                        provider = provider,
                        apiKey = "k",
                        messages = listOf(ApiMessage(role = "user", content = "hi")),
                        tools = emptyList(),
                        reasoningEffort = null
                    ).collect { }
                } catch (_: Exception) {
                    // 取消导致的 CancellationException / IOException("Canceled") 都算数
                } finally {
                    finished = true
                }
            }

            // 等服务器收到连接（证明请求已发出）
            val connected = withTimeoutOrNull(10_000L) {
                while (accepted.isEmpty()) kotlinx.coroutines.delay(20)
                true
            }
            assertTrue("服务器未收到连接，测试前提不成立", connected == true)

            // 等读循环真正卡住
            kotlinx.coroutines.delay(500)
            assertTrue("收集协程不应已提前结束（说明没卡在阻塞读上）", !finished)

            // 取消：必须在 1 秒内打断阻塞读、让协程收尾
            job.cancel()
            val stopped = withTimeoutOrNull(1_000L) {
                while (!finished) kotlinx.coroutines.delay(20)
                true
            }

            assertTrue(
                "取消后 1 秒内阻塞读仍未被打断（停止键会卡到 readTimeout=120s）",
                stopped == true
            )
            assertTrue("收集协程未收尾", finished)
            accepted.forEach { runCatching { it.close() } }
        }
        Unit
    }
}
