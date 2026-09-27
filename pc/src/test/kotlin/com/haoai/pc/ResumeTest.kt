package com.haoai.pc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * 断点恢复：跑一半被杀，重启之后要认得那条现场，并且能**接着做**而不是重问一遍。
 *
 * 判据不看界面上有没有横幅，看两件事：
 * ① 没跑完的会话文件里留着 `runState`，正常收尾的**不**留（不然横幅会一直举着）；
 * ② 点续跑之后，网关收到的请求体里**带着中断前那几轮的历史**（这才叫"接着"）。
 */
class ResumeTest {

    companion object {
        private lateinit var gateway: com.sun.net.httpserver.HttpServer
        private lateinit var bodies: MutableList<String>
        private lateinit var server: WebServer
        private lateinit var base: String
        private val http = HttpClient.newHttpClient()

        @BeforeClass
        @JvmStatic
        fun setUp() {
            val home = Files.createTempDirectory("haoai-resume-home").toFile()
            System.setProperty("haoai.home", File(home, "home").absolutePath)
            bodies = java.util.Collections.synchronizedList(mutableListOf<String>())
            gateway = com.sun.net.httpserver.HttpServer.create(
                java.net.InetSocketAddress("127.0.0.1", 0), 0
            )
            gateway.createContext("/v1/chat/completions") { ex ->
                val body = String(ex.requestBody.readBytes(), Charsets.UTF_8)
                bodies += body
                // 带"卡住"的那一句永远不答：模拟"跑到一半进程被杀"，
                // 而不是靠 sleep 猜时间 —— 猜的时间一短，测试就会时好时坏。
                if (body.contains("卡住")) Thread.sleep(90_000)
                val b = ("data: " +
                    """{"choices":[{"index":0,"finish_reason":"stop","delta":{"content":"好了"}}]}""" +
                    "\n\ndata: [DONE]\n\n").toByteArray()
                ex.responseHeaders.add("Content-Type", "text/event-stream")
                ex.sendResponseHeaders(200, b.size.toLong())
                ex.responseBody.use { it.write(b) }
                ex.close()
            }
            /*
             * 默认执行器是"一条线程顺序处理"：那句 90 秒不答的请求会把后面所有请求堵死，
             * 于是"续跑的那次请求没到网关"看着像产品坏了，其实是假网关只有一条lane。
             */
            gateway.executor = java.util.concurrent.Executors.newCachedThreadPool()
            gateway.start()
            PcSettings.save(PcSettings(
                workspace = File(home, "ws").apply { mkdirs() }.absolutePath,
                permissionMode = "auto", model = "mock",
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

    private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject

    private fun newSession(tag: String): String {
        val dir = File(Env.home.parentFile, "rws-" + tag).apply { mkdirs() }
        val r = obj(post("/api/new", "{\"ws\":\"" + dir.absolutePath.replace("\\", "\\\\") + "\"}"))
        return r["id"]!!.jsonPrimitive.content
    }

    private fun fileOf(id: String) = File(Env.sessionsDir, "pc-$id.json")

    private fun awaitIdle(sid: String, ms: Long = 20_000): Boolean {
        val until = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < until) {
            val s = obj(get("/api/state?sid=$sid"))
            if (!s["running"]!!.jsonPrimitive.content.toBoolean()) return true
            Thread.sleep(120)
        }
        return false
    }

    private fun get(path: String): String = http.send(
        HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
        HttpResponse.BodyHandlers.ofString()
    ).body()

    /** 等会话文件里的 runState 变成想要的那样：落盘是异步的，不能假设写完就能读到。 */
    private fun waitRunState(id: String, present: Boolean, ms: Long = 10_000): String? {
        val until = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < until) {
            val t = runCatching { fileOf(id).readText() }.getOrNull()
            if (t != null && t.contains("runState") == present) return t
            Thread.sleep(150)
        }
        return null
    }

    /**
     * 等那句请求真的到了网关再 clear()。
     *
     * 落盘发生在调用模型**之前**，所以"看见 runState 了"并不等于"请求已经到了网关"：
     * 早清一次，那条 90 秒不答的请求就会在 clear 之后落进列表，
     * 于是"丢掉现场不该顺手发请求"这条断言被自己的量具绊倒（第一次跑就是这么红的）。
     */
    private fun waitBodyAtGateway(needle: String, ms: Long = 10_000): Boolean {
        val until = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < until) {
            if (bodies.any { it.contains(needle) }) return true
            Thread.sleep(120)
        }
        return false
    }

    @Test
    fun `an unfinished run leaves runState on disk`() {
        val sid = newSession("r1")
        post("/api/task", """{"sid":"$sid","text":"卡住：先把这件事做完"}""")
        val text = waitRunState(sid, true) ?: error("会话文件里一直没出现 runState")
        assertTrue("runState 里要带着原目标：" + text.take(200),
            text.contains("\"runState\"") && text.contains("先把这件事做完"))
    }

    @Test
    fun `a run that finishes normally clears the state`() {
        val sid = newSession("r2")
        post("/api/task", """{"sid":"$sid","text":"正常说一句就好"}""")
        assertTrue("等不到收尾", awaitIdle(sid))
        assertFalse("正常收尾之后不该留现场：" + fileOf(sid).readText().take(200),
            fileOf(sid).readText().contains("runState"))
    }

    /** 最值钱的一条：换一个引擎实例（等价于重启进程）之后，认得这条并能接着做。 */
    @Test
    fun `a fresh engine sees the interrupted run and resuming keeps the history`() {
        val sid = newSession("r3")
        post("/api/task", """{"sid":"$sid","text":"卡住：调研一下某个库"}""")
        assertTrue("等不到现场落盘", waitRunState(sid, true) != null)

        val server2 = WebServer(PcSettings.load(), port = 0)
        val base2 = "http://127.0.0.1:${server2.start()}"
        try {
            val st = obj(http.send(
                HttpRequest.newBuilder(URI.create("$base2/api/state?sid=$sid")).GET().build(),
                HttpResponse.BodyHandlers.ofString()).body())
            val rs = st["runState"]?.jsonObject
            assertTrue("新实例没把现场读回来：" + st.toString().take(200), rs != null)
            assertTrue((rs!!["goal"]?.jsonPrimitive?.contentOrNull ?: "").contains("调研一下某个库"))

            assertTrue("等不到那句卡住请求到达网关", waitBodyAtGateway("调研一下某个库"))
            bodies.clear()
            val r = obj(http.send(
                HttpRequest.newBuilder(URI.create("$base2/api/resume"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("""{"sid":"$sid"}""")).build(),
                HttpResponse.BodyHandlers.ofString()).body())
            assertTrue("续跑没启动：" + r["error"]?.jsonPrimitive?.content,
                r["ok"]?.jsonPrimitive?.content == "true")
            val until = System.currentTimeMillis() + 15_000
            var sent: String? = null
            while (sent == null && System.currentTimeMillis() < until) {
                sent = bodies.firstOrNull { it.contains("接着上次") && it.contains("调研一下某个库") }
                if (sent == null) Thread.sleep(150)
            }
            assertTrue("网关没收到带着原目标的那一次续跑请求", sent != null)
            assertTrue("续跑不该把历史丢掉（要接着做，不是重头问一遍）",
                obj(sent!!)["messages"]!!.toString().contains("卡住：调研一下某个库"))
        } finally {
            runCatching { server2.stop() }
        }
    }

    @Test
    fun `abandon clears the state without running anything`() {
        val sid = newSession("r4")
        post("/api/task", """{"sid":"$sid","text":"卡住：丢掉这段"}""")
        assertTrue("等不到现场落盘", waitRunState(sid, true) != null)
        assertTrue("等不到那句卡住请求到达网关", waitBodyAtGateway("丢掉这段"))
        bodies.clear()
        assertEquals("true", obj(post("/api/abandon", """{"sid":"$sid"}"""))["ok"]
            ?.jsonPrimitive?.content)
        assertFalse(fileOf(sid).readText().contains("runState"))
        Thread.sleep(600)
        assertEquals("丢掉现场不该顺手发一次请求", 0, bodies.size)
    }
}
