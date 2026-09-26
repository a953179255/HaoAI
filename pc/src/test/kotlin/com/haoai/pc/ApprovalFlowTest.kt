package com.haoai.pc

import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.net.URI
import java.net.InetSocketAddress
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * 整条用户链路的全栈测试：真 HTTP 服务 + 真 SSE + 真引擎 + 真网关（假的，但是真协议）。
 *
 * 为什么非要有这份：`/api/decide` 的"允许一次"曾经**一直是拒绝**，而 75 条单测全绿 ——
 * 因为它们都是直接调引擎或存储，没有一条从网页那个口子进去。
 * 中间那层（请求体只能读一次、字段名两边要对得上、SSE 事件名要对得上、
 * 审批要真的能把写放行）完全是靠这份测试才有人看着。
 *
 * 判据也按用例声明：允许 ⇒ 文件出现且内容对；拒绝 ⇒ 文件不出现。
 * 不看返回值 200 就算过 —— 上一版的 200 后面跟着的就是拒绝。
 */
class ApprovalFlowTest {

    companion object {
        private lateinit var gateway: HttpServer
        private lateinit var web: WebServer
        private lateinit var base: String
        private lateinit var ws: File
        private val http = HttpClient.newHttpClient()

        /** 每个请求依次吐出脚本里的下一段帧；用完了就重复最后一段（引擎会多问一轮）。 */
        private val script = Collections.synchronizedList(mutableListOf<List<ByteArray>>())
        private val which = AtomicInteger(0)
        val events = ConcurrentLinkedQueue<Pair<String, String>>()

        private fun frame(s: String) = ("data: $s\n\n").toByteArray(Charsets.UTF_8)

        /** 一个带 tool_call 的回合。用 JSON 构造器拼，不手工转义 —— arguments 本身就是 JSON 字符串。 */
        private fun toolCallTurn(id: String, name: String, args: String): List<ByteArray> {
            val head = buildJsonObject {
                put("choices", buildJsonArray {
                    add(buildJsonObject {
                        put("index", 0)
                        put("delta", buildJsonObject {
                            put("role", "assistant")
                            put("content", "我先动手")
                            put("tool_calls", buildJsonArray {
                                add(buildJsonObject {
                                    put("index", 0); put("id", id); put("type", "function")
                                    put("function", buildJsonObject { put("name", name); put("arguments", args) })
                                })
                            })
                        })
                    })
                })
            }
            return listOf(
                frame(head.toString()),
                frame("""{"choices":[{"index":0,"finish_reason":"tool_calls","delta":{}}]}"""),
                frame("""{"usage":{"prompt_tokens":10,"completion_tokens":5}}"""),
                frame("[DONE]")
            )
        }

        private fun textTurn(text: String): List<ByteArray> = listOf(
            frame("""{"choices":[{"index":0,"finish_reason":null,"delta":{"role":"assistant","content":"$text"}}]}"""),
            frame("""{"choices":[{"index":0,"finish_reason":"stop","delta":{}}]}"""),
            frame("[DONE]")
        )

        private fun get(path: String): String = http.send(
            HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
            HttpResponse.BodyHandlers.ofString()
        ).body()

        private fun post(path: String, obj: Map<String, String>): Pair<Int, String> {
            val body = buildJsonObject { obj.forEach { (k, v) -> put(k, v) } }.toString()
            val r = http.send(
                HttpRequest.newBuilder(URI.create(base + path))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString()
            )
            return r.statusCode() to r.body()
        }

        private fun awaitEvent(name: String, timeoutMs: Long = 15_000): String? {
            val until = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < until) {
                events.firstOrNull { it.first == name }?.let { events.remove(it); return it.second }
                Thread.sleep(50)
            }
            return null
        }

        @BeforeClass
        @JvmStatic
        fun setUp() {
            val home = Files.createTempDirectory("haoai-flow-home").toFile()
            System.setProperty("haoai.home", File(home, "home").absolutePath)
            ws = File(home, "ws").apply { mkdirs() }

            gateway = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            gateway.createContext("/v1/chat/completions") { ex ->
                ex.requestBody.readBytes()
                val frames = script[minOf(which.getAndIncrement(), script.size - 1)]
                ex.responseHeaders.add("Content-Type", "text/event-stream")
                ex.sendResponseHeaders(200, 0)
                ex.responseBody.use { os -> frames.forEach { os.write(it); os.flush() } }
                ex.close()
            }
            gateway.start()

            val st = PcSettings(
                baseUrl = "http://127.0.0.1:${gateway.address.port}/v1",
                model = "flowtest", permissionMode = "ask", workspace = ws.absolutePath
            )
            web = WebServer(st, 0)
            base = "http://127.0.0.1:${web.start()}"

            Thread {
                runCatching {
                    val conn = http.send(
                        HttpRequest.newBuilder(URI.create("$base/api/events")).GET().build(),
                        HttpResponse.BodyHandlers.ofLines()
                    )
                    var pendingEvent = ""
                    conn.body().forEach { line ->
                        when {
                            line.startsWith("event:") -> pendingEvent = line.substring(6).trim()
                            line.startsWith("data:") && pendingEvent.isNotEmpty() -> {
                                events.add(pendingEvent to line.substring(5).trim())
                                pendingEvent = ""
                            }
                        }
                    }
                }
            }.apply { isDaemon = true; start() }
            Thread.sleep(500)
        }

        @AfterClass
        @JvmStatic
        fun tearDown() {
            runCatching { web.stop() }
            runCatching { gateway.stop(0) }
        }
    }

    private fun runTask(prompt: String, turns: List<List<ByteArray>>) {
        script.clear(); script.addAll(turns); which.set(0)
        events.clear()
        post("/api/new", emptyMap())
        val (code, _) = post("/api/task", mapOf("text" to prompt))
        assertEquals(200, code)
    }

    @Test
    fun `allow_once really lets the write through`() {
        runTask("写一个 approved.txt", listOf(
            toolCallTurn("call_w", "write", """{"path":"approved.txt","content":"approved by user"}"""),
            textTurn("写完了")
        ))

        val raw = awaitEvent("approval")
        assertNotNull("没收到审批事件（SSE 或闸口断了）。已收到：$events", raw)
        val d = Json.parseToJsonElement(raw!!).jsonObject
        assertEquals("write", d["tool"]?.jsonPrimitive?.content)
        assertTrue("审批卡上没说要写哪个文件：$raw", d["title"]?.jsonPrimitive?.content!!.contains("approved.txt"))

        // 刷新页面（=重读 /api/state）时必须还能看到这条待决审批：
        // 它只通过 SSE 推过一次，找不到就等于引擎在无人知晓地卡 300 秒。
        val pend = Json.parseToJsonElement(get("/api/state")).jsonObject["pending"]?.jsonArray
        assertNotNull("待决审批没进 /api/state（刷新页面就丢了）", pend)
        assertTrue("state.pending 里不是这条：$pend",
            pend!!.any {
                val o = Json.parseToJsonElement(it.toString()).jsonObject
                o["ev"]?.jsonPrimitive?.content == "approval" &&
                    o["data"]?.jsonObject?.get("id")?.jsonPrimitive?.content == d["id"]!!.jsonPrimitive.content
            })

        val (code, _) = post("/api/decide", mapOf("id" to d["id"]!!.jsonPrimitive.content,
            "decision" to "allow_once", "answer" to ""))
        assertEquals(200, code)

        val toolOut = awaitEvent("tool", 15_000)
        assertNotNull("审批放行后没有工具结束事件", toolOut)

        val f = File(ws, "approved.txt")
        val until = System.currentTimeMillis() + 15_000
        while (!f.isFile && System.currentTimeMillis() < until) Thread.sleep(100)
        assertTrue("点了「允许一次」，文件却没写出来 —— 这就是修之前那个 bug", f.isFile)
        assertEquals("approved by user", f.readText())
        // 答完之后必须从 pending 里消失，否则刷新一次就弹一次"已经批过的"框
        val after = Json.parseToJsonElement(get("/api/state")).jsonObject["pending"]?.jsonArray
        assertTrue("答完了还挂在 pending 里：$after", after.isNullOrEmpty())
    }

    @Test
    fun `deny leaves the file unwritten`() {
        runTask("写一个 denied.txt", listOf(
            toolCallTurn("call_d", "write", """{"path":"denied.txt","content":"nope"}"""),
            textTurn("那我不写了")
        ))
        val raw = awaitEvent("approval")
        assertNotNull(raw)
        val d = Json.parseToJsonElement(raw!!).jsonObject
        post("/api/decide", mapOf("id" to d["id"]!!.jsonPrimitive.content,
            "decision" to "deny", "answer" to ""))
        // 事件名是 answer（引擎的 TextDone），不是 done：写错这里会白等 15 秒超时
        assertNotNull("拒绝之后回合也没收尾", awaitEvent("answer", 15_000))
        Thread.sleep(300)
        assertFalse("被拒绝了却还是写了文件", File(ws, "denied.txt").exists())
    }

    /**
     * 设置抽屉一次要写 5 个字段。当年 `decide` 就是因为"一个请求体读多次"只认第一个字段，
     * 这条测试存在的全部理由就是把同一类错误钉在设置接口上。
     *
     * 注意它改的是**整个服务共享的**档位与工作区，所以必须在 finally 里还原：
     * 不还原的话，先跑完这条再跑审批那两条，档位已经变成 auto —— 不再弹审批，
     * 那两条会报"没收到审批事件"，看起来像审批坏了。（第一次跑就踩到了，
     * 单跑通过、合跑失败，就是这个共享状态在作怪。）
     */
    @Test
    fun `the settings drawer payload changes every field it sends`() {
        val dir = Files.createTempDirectory("haoai-flow-ws2").toFile()
        val before = Json.parseToJsonElement(
            http.send(HttpRequest.newBuilder(URI.create("$base/api/settings")).GET().build(),
                HttpResponse.BodyHandlers.ofString()).body()
        ).jsonObject
        val wasMode = before["mode"]?.jsonPrimitive?.content ?: "ask"
        val wasWs = before["workspace"]?.jsonPrimitive?.content ?: ws.absolutePath
        try {
            val (code, _) = post(
                "/api/settings",
                mapOf(
                    "model" to "glm-flow.1",
                    "baseUrl" to "http://127.0.0.1:${gateway.address.port}/v1",
                    "workspace" to dir.absolutePath, "mode" to "auto"
                )
            )
            assertEquals(200, code)
            val got = Json.parseToJsonElement(
                http.send(HttpRequest.newBuilder(URI.create("$base/api/settings")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()).body()
            ).jsonObject
            assertEquals("glm-flow.1", got["model"]?.jsonPrimitive?.content)
            assertEquals("auto", got["mode"]?.jsonPrimitive?.content)
        } finally {
            post("/api/settings", mapOf(
                "model" to "flowtest", "workspace" to wasWs, "mode" to wasMode
            ))
        }
    }
}
