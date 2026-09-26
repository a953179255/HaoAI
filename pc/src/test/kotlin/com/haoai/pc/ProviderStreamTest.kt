package com.haoai.pc

import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetSocketAddress

/**
 * 真 HTTP + 真 SSE 的网关测试。
 *
 * 为什么单独有这份：`EngineFlowTest` 把模型换成脚本，于是 **流式解析这条最容易被改坏的路
 * 完全没有覆盖**。这里补上，顺手钉死三件实测过的事：
 * 1. 一个中文字（3 个 UTF-8 字节）被 TCP 分片从中间切开时，不能变成 U+FFFD；
 * 2. `tool_calls.arguments` 分片到达时要按 index 拼回完整 JSON；
 * 3. 非 2xx 要抛 [ProviderError] 并带上 transient 判据（429/5xx 可重试，400 不可）。
 */
class ProviderStreamTest {

    private data class Fixture(val port: Int, val requests: MutableList<String>, val server: HttpServer)

    /** 起一个假网关，按 chunks 原样吐出去（每片一次 write + flush，制造真实的分片边界）。 */
    private fun serve(chunks: List<ByteArray>, status: Int = 200, body: String = ""): Fixture {
        val reqs = mutableListOf<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/chat/completions") { ex ->
            reqs.add(ex.requestHeaders.getFirst("Authorization") ?: "")
            ex.requestBody.readBytes()
            if (status != 200) {
                val b = body.toByteArray()
                ex.sendResponseHeaders(status, b.size.toLong())
                ex.responseBody.use { it.write(b) }
            } else {
                ex.responseHeaders.add("Content-Type", "text/event-stream")
                ex.sendResponseHeaders(200, 0)
                ex.responseBody.use { os ->
                    chunks.forEach {
                        os.write(it)
                        os.flush()
                        Thread.sleep(2)
                    }
                }
            }
            ex.close()
        }
        server.start()
        return Fixture(server.address.port, reqs, server)
    }

    private fun data(s: String) = ("data: " + s + "\n\n").toByteArray(Charsets.UTF_8)

    /** 把要塞进 JSON 字符串的分片转义掉（arguments 里全是引号）。 */
    private fun jsonEscape(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")

    @Test
    fun `streams chinese text split across tcp chunks without corruption`() {
        val full = "我要把清单里的中文标题都留着：建文件、改第一行、读回来核对。"
        val frames = mutableListOf<ByteArray>()
        full.chunked(6).forEach { piece ->
            frames += data(
                """{"choices":[{"index":0,"finish_reason":null,"delta":{"content":"$piece"}}]}"""
            )
        }
        frames += data("""{"choices":[{"index":0,"finish_reason":"stop","delta":{}}]}""")
        frames += data("[DONE]")
        // 把帧的**字节**再按 4 一切：一个汉字 3 字节，必然有片从字中间切开
        val wire = frames.flatMap { f -> f.toList().chunked(4).map { it.toByteArray() } }
        val f = serve(wire)
        try {
            val received = StringBuilder()
            val r = Provider("http://127.0.0.1:${f.port}/v1", "k", "m").chat(
                listOf(Msg("user", "hi")), emptyList()
            ) { received.append(it) }
            assertEquals(full, r.text)
            assertEquals("流式回调拼起来也要等于全文", full, received.toString())
            assertEquals("stop", r.finishReason)
        } finally {
            f.server.stop(0)
        }
    }

    /** 上面那份写得太绕，直接用一个手工构造的分片序列再验一次更清楚的场景。 */
    @Test
    fun `tool call arguments arrive in fragments and reassemble into valid json`() {
        val args = """{"items":[{"text":"建 hello.txt","status":"doing"},{"text":"改第一行","status":"pending"}]}"""
        val pieces = mutableListOf<ByteArray>()
        // 按**字符**切 5 个一片（跨字节切分的场景由上面那个用例负责）：
        // 这里要验的是"arguments 分片到达 → 按 index 拼回完整 JSON"这条拼装逻辑。
        var i = 0
        var first = true
        while (i < args.length) {
            val frag = args.substring(i, minOf(args.length, i + 5))
            val delta = if (first) {
                """{"choices":[{"index":0,"finish_reason":null,"delta":{"role":"assistant","tool_calls":[{"index":0,"id":"call_9","type":"function","function":{"name":"todo","arguments":""}}]}}]}"""
            } else {
                """{"choices":[{"index":0,"finish_reason":null,"delta":{"tool_calls":[{"index":0,"function":{"arguments":""}}]}}]}"""
            }
            pieces.add(data(delta.replace("\"\"", "\"${jsonEscape(frag)}\"")))
            first = false
            i += 5
        }
        pieces.add(data("""{"choices":[{"index":0,"finish_reason":"tool_calls","delta":{}}]}"""))
        pieces.add(data("""{"usage":{"prompt_tokens":123,"completion_tokens":45}}"""))
        pieces.add(data("[DONE]"))

        val f = serve(pieces.map { it })
        try {
            val r = Provider("http://127.0.0.1:${f.port}/v1", "sk-test", "m").chat(
                listOf(Msg("user", "hi")), listOf(ToolSchema("todo", "d", emptyObj()))
            ) { }
            assertEquals(1, r.calls.size)
            assertEquals("call_9", r.calls[0].id)
            assertEquals("todo", r.calls[0].name)
            // 关键断言：拼回来的 arguments 必须是**合法 JSON** 且中文完好
            val obj = Json.parseToJsonElement(r.calls[0].args).jsonObject
            val first0 = obj["items"]!!.jsonArray0()[0].jsonObject
            assertEquals("建 hello.txt", first0["text"]!!.jsonPrimitive.content)
            assertEquals("改第一行", obj["items"]!!.jsonArray0()[1].jsonObject["text"]!!.jsonPrimitive.content)
            assertEquals(123, r.usage.promptTokens)
            assertEquals(45, r.usage.completionTokens)
            assertEquals("tool_calls", r.finishReason)
            assertEquals("Bearer sk-test", f.requests.first())
        } finally {
            f.server.stop(0)
        }
    }

    @Test
    fun `http errors become provider errors with a retry hint`() {
        val f = serve(emptyList(), status = 429, body = """{"error":{"message":"slow down"}}""")
        try {
            Provider("http://127.0.0.1:${f.port}/v1", "k", "m").chat(listOf(Msg("user", "hi")), emptyList()) { }
            fail("429 应该抛异常")
        } catch (e: ProviderError) {
            assertEquals(429, e.status)
            assertTrue("429 必须标成可重试", e.transient)
            assertTrue(e.message!!.contains("slow down"))
        } finally {
            f.server.stop(0)
        }

        val f2 = serve(emptyList(), status = 400, body = """{"error":{"message":"bad request"}}""")
        try {
            Provider("http://127.0.0.1:${f2.port}/v1", "k", "m").chat(listOf(Msg("user", "hi")), emptyList()) { }
            fail("400 应该抛异常")
        } catch (e: ProviderError) {
            assertTrue("400 不该重试", !e.transient)
        } finally {
            f2.server.stop(0)
        }
    }

    private fun kotlinx.serialization.json.JsonElement.jsonArray0(): kotlinx.serialization.json.JsonArray =
        this as kotlinx.serialization.json.JsonArray

    private fun emptyObj(): kotlinx.serialization.json.JsonObject =
        kotlinx.serialization.json.buildJsonObject { }
}
