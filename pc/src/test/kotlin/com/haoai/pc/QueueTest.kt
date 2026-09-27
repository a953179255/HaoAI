package com.haoai.pc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files

/**
 * 一条会话正在跑的时候又发了一句 —— 排队，而不是 409。
 *
 * 为什么单独测：以前"这条会话正在跑，先按停止再发新任务"把用户手打的下一句挡在门外，
 * 而那一句话十有八九就是要紧接着说的；按停止又会杀掉跑了十分钟的活。
 * 判据不看接口说了什么，看**网关按什么顺序收到什么**：排队若没接上，第二句永远不会到网关；
 * 撤回若没生效，第二句会到 —— 两种失败模式都能被这一条区分出来。
 */
class QueueTest {

    companion object {
        private lateinit var gateway: com.sun.net.httpserver.HttpServer
        private lateinit var bodies: MutableList<String>
        private lateinit var server: WebServer
        private lateinit var base: String
        private val http = HttpClient.newHttpClient()

        @BeforeClass
        @JvmStatic
        fun setUp() {
            val home = Files.createTempDirectory("haoai-queue-home").toFile()
            System.setProperty("haoai.home", File(home, "home").absolutePath)
            bodies = java.util.Collections.synchronizedList(mutableListOf<String>())
            gateway = com.sun.net.httpserver.HttpServer.create(
                java.net.InetSocketAddress("127.0.0.1", 0), 0
            )
            gateway.createContext("/v1/chat/completions") { ex ->
                val body = String(ex.requestBody.readBytes(), Charsets.UTF_8)
                bodies += body
                // 带"慢"的那一句让网关拖 1.8 秒：不给这段时间，第二句永远撞不到"正在跑"上，
                // 测出来的就只是"连着发了两句"，排队这条分支根本没走到。
                if (body.contains("慢")) Thread.sleep(1800)
                val b = ("data: " +
                    """{"choices":[{"index":0,"finish_reason":"stop","delta":{"content":"好了"}}]}""" +
                    "\n\ndata: [DONE]\n\n").toByteArray()
                ex.responseHeaders.add("Content-Type", "text/event-stream")
                ex.sendResponseHeaders(200, b.size.toLong())
                ex.responseBody.use { it.write(b) }
                ex.close()
            }
            gateway.start()
            PcSettings.save(PcSettings(
                workspace = File(home, "ws").apply { mkdirs() }.absolutePath,
                permissionMode = "auto", model = "排队测试模型",
                baseUrl = "http://127.0.0.1:${gateway.address.port}/v1"
            ))
            server = WebServer(PcSettings.load(), port = 0)
            base = "http://127.0.0.1:${server.start()}"
            server.schedules()?.stopped = true
        }

        @AfterClass
        @JvmStatic
        fun tearDown() {
            runCatching { server.stop() }
            runCatching { gateway.stop(0) }
        }
    }

    private fun post(path: String, body: String): String = http.send(
        HttpRequest.newBuilder(URI.create(base + path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
        HttpResponse.BodyHandlers.ofString()
    ).body()

    private fun get(path: String): String = http.send(
        HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
        HttpResponse.BodyHandlers.ofString()
    ).body()

    private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject
    private fun jesc(p: String) = p.replace("\\", "\\\\")

    private fun newSession(tag: String): String {
        val dir = File(Env.home.parentFile, "qws-" + tag).apply { mkdirs() }
        val r = obj(post("/api/new", "{\"ws\":\"" + jesc(dir.absolutePath) + "\"}"))
        return r["id"]!!.jsonPrimitive.content
    }

    private fun queueOf(sid: String): List<String> =
        obj(get("/api/state?sid=$sid"))["queue"]!!.jsonArray.map {
            it.jsonObject["t"]?.jsonPrimitive?.contentOrNull ?: ""
        }

    private fun running(sid: String): Boolean =
        obj(get("/api/state?sid=$sid"))["running"]!!.jsonPrimitive.content.toBoolean()

    private fun awaitIdle(sid: String, ms: Long = 15_000): Boolean {
        val until = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < until) {
            if (!running(sid)) return true
            Thread.sleep(120)
        }
        return !running(sid)
    }

    /** 最后一句用户话：网关收到的请求体里 messages 的最后一条 user content。 */
    private fun lastUser(body: String): String {
        val msgs = obj(body)["messages"]?.jsonArray ?: return ""
        return msgs.lastOrNull { it.jsonObject["role"]?.jsonPrimitive?.content == "user" }
            ?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull ?: ""
    }

    @Test
    fun `a second message while running is queued and then runs`() {
        val sid = newSession("q1")
        bodies.clear()
        assertTrue(obj(post("/api/task", """{"sid":"$sid","text":"慢一点的那句"}"""))["ok"]
            ?.jsonPrimitive?.content == "true")
        assertTrue("第一句该在跑", running(sid))
        val second = obj(post("/api/task", """{"sid":"$sid","text":"紧接着的第二句"}"""))
        assertTrue("第二句不该被拒：" + second["error"]?.jsonPrimitive?.content,
            second["ok"]?.jsonPrimitive?.content == "true")
        assertEquals("排队的应该只有那一句", listOf("紧接着的第二句"), queueOf(sid))
        assertTrue("等不到两条跑完", awaitIdle(sid))
        val got = bodies.map { lastUser(it) }
        assertTrue("网关该收到两次，实际：$got", got.size >= 2)
        assertTrue("第一句没先到：" + got.first(), got.first().contains("慢一点的那句"))
        assertTrue("第二句没接上（排队没生效）：" + got.last(), got.last().contains("紧接着的第二句"))
    }

    @Test
    fun `withdrawing a queued message means it never reaches the gateway`() {
        val sid = newSession("q2")
        bodies.clear()
        post("/api/task", """{"sid":"$sid","text":"慢一点的第三句"}""")
        post("/api/task", """{"sid":"$sid","text":"这句要撤回"}""")
        assertEquals(listOf("这句要撤回"), queueOf(sid))
        assertTrue(obj(post("/api/unqueue", """{"sid":"$sid","at":"0"}"""))["ok"]
            ?.jsonPrimitive?.content == "true")
        assertEquals("撤回之后队列该空", emptyList<String>(), queueOf(sid))
        assertTrue("等不到第一轮结束", awaitIdle(sid))
        Thread.sleep(600)
        assertTrue("撤回的那句还是发出去了：" + bodies.map { lastUser(it) },
            bodies.none { lastUser(it).contains("这句要撤回") })
        assertEquals("会话不该还挂着队列", emptyList<String>(), queueOf(sid))
    }

    @Test
    fun `an empty queue is what a fresh session reports`() {
        assertEquals(emptyList<String>(), queueOf(newSession("q3")))
    }
}
