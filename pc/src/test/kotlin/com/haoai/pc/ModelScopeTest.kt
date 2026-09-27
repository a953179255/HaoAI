package com.haoai.pc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
 * 按会话换模型（`POST /api/model`）。
 *
 * 为什么单独测：全局只有一个模型时，"这条会话想拿本地小模型试个简单问题"
 * 唯一的办法是改全局，而那会同时把另外三条正在跑的会话换掉 —— 参考实现都是按会话选的。
 * 判据不看返回值，看**网关真收到哪个 model**：状态报得对但发出去还是全局那份，等于没改。
 */
class ModelScopeTest {

    companion object {
        private lateinit var gateway: com.sun.net.httpserver.HttpServer
        private lateinit var bodies: MutableList<String>
        private lateinit var server: WebServer
        private lateinit var base: String
        private val http = HttpClient.newHttpClient()

        @BeforeClass
        @JvmStatic
        fun setUp() {
            val home = Files.createTempDirectory("haoai-model-home").toFile()
            System.setProperty("haoai.home", File(home, "home").absolutePath)
            bodies = mutableListOf()
            gateway = com.sun.net.httpserver.HttpServer.create(
                java.net.InetSocketAddress("127.0.0.1", 0), 0
            )
            gateway.createContext("/v1/chat/completions") { ex ->
                bodies += String(ex.requestBody.readBytes(), Charsets.UTF_8)
                val b = ("data: " +
                    """{"choices":[{"index":0,"finish_reason":"stop","delta":{"content":"好了"}}]}""" +
                    "\n\ndata: [DONE]\n\n").toByteArray()
                ex.responseHeaders.add("Content-Type", "text/event-stream")
                ex.sendResponseHeaders(200, b.size.toLong())
                ex.responseBody.use { it.write(b) }
                ex.close()
            }
            gateway.createContext("/v1/models") { ex ->
                val b = ("""{"data":[{"id":"全局模型"},{"id":"本地 7B"},{"id":"云端大模型"}]}""")
                    .toByteArray(Charsets.UTF_8)
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(200, b.size.toLong())
                ex.responseBody.use { it.write(b) }
                ex.close()
            }
            gateway.start()
            PcSettings.save(PcSettings(
                workspace = File(home, "ws").apply { mkdirs() }.absolutePath,
                permissionMode = "auto", model = "全局模型",
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

    /*
     * 必须带 ws 新建：`/api/new` 在手边那条还空着的时候会**复用**它（这是 v0.1x 修的
     * "一路点新任务刷出一堆空会话"），于是这里两次 newSession 拿到的是同一条 id，
     * "只改一条不影响别条"就没法测了 —— 第一次跑就是这么假失败了一次。
     */
    private fun jesc(p: String): String = p.replace("\\", "\\\\")

    private fun newSessionDir(tag: String): String {
        val dir = File(Env.home.parentFile, "ws-" + tag).apply { mkdirs() }
        return jesc(dir.absolutePath)
    }

    private fun newSession(tag: String = System.nanoTime().toString(16)): String =
        obj(post("/api/new", """{"ws":"${newSessionDir(tag)}"}"""))["id"]!!.jsonPrimitive.content

    private fun modelOf(sid: String): String =
        obj(get("/api/state?sid=$sid"))["model"]!!.jsonPrimitive.content

    private fun currentInList(sid: String): String =
        kotlinx.serialization.json.Json.parseToJsonElement(get("/api/models?sid=$sid"))
            .let { it as kotlinx.serialization.json.JsonArray }
            .firstOrNull { it.jsonObject["current"]!!.jsonPrimitive.content == "true" }
            ?.jsonObject?.get("id")?.jsonPrimitive?.content ?: ""

    /** 弹窗里打勾的那一行要跟 chip 显示的是同一个模型（以前按全局标，切过会话就标错行）。 */
    @Test
    fun `the model list ticks the session own model`() {
        val sid = newSession("tick")
        assertEquals("全局模型", currentInList(sid))
        obj(post("/api/model", """{"model":"本地 7B","sid":"$sid"}"""))
        assertEquals("本地 7B", currentInList(sid))
        val other = newSession("untouched")
        assertEquals("另一条不受影响", "全局模型", currentInList(other))
    }

    @Test
    fun `an empty model name is refused`() {
        assertFalse(obj(post("/api/model", """{"model":"  "}"""))["ok"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `a session that does not exist cannot be given a model`() {
        val d = obj(post("/api/model", """{"model":"m","sid":"nope-not-here"}"""))
        assertFalse(d["ok"]!!.jsonPrimitive.content.toBoolean())
        assertTrue((d["error"]?.jsonPrimitive?.content ?: "").contains("没有这条会话"))
    }

    /** 最值钱的一条：只改这一条，别的会话与全局默认都不动。 */
    @Test
    fun `switching one session leaves the others and the global default alone`() {
        val a = newSession("a")
        val b = newSession("b")
        assertTrue(obj(post("/api/model", """{"model":"本地 7B","sid":"$a"}"""))["ok"]
            ?.jsonPrimitive?.content?.toBoolean() == true)
        assertEquals("本地 7B", modelOf(a))
        assertEquals("全局模型", modelOf(b))
        assertEquals("全局设置不能被一次按会话的切换改掉",
            "全局模型", obj(get("/api/settings"))["model"]!!.jsonPrimitive.content)
    }

    @Test
    fun `scope all moves the default and every live session`() {
        val a = newSession("all")
        obj(post("/api/model", """{"model":"云端大模型","scope":"all"}"""))
        assertEquals("云端大模型", modelOf(a))
        assertEquals("云端大模型", obj(get("/api/settings"))["model"]!!.jsonPrimitive.content)
        // 之后新建的会话也要继承新的默认
        assertEquals("云端大模型", modelOf(newSession()))
        obj(post("/api/model", """{"model":"全局模型","scope":"all"}"""))
    }

    /** 状态报对了还不算：发请求时那条消息必须真的用会话自己的模型。 */
    @Test
    fun `the per-session model is the one that reaches the gateway`() {
        val sid = newSession("wire")
        obj(post("/api/model", """{"model":"只这条","sid":"$sid"}"""))
        bodies.clear()
        post("/api/task", """{"text":"说一句就好","sid":"$sid"}""")
        val until = System.currentTimeMillis() + 15_000
        while (bodies.isEmpty() && System.currentTimeMillis() < until) Thread.sleep(100)
        assertTrue("网关该收到一次请求", bodies.isNotEmpty())
        val sent = obj(bodies.last())["model"]!!.jsonPrimitive.content
        assertEquals("发出去的要按会话的模型，不是全局那份", "只这条", sent)
    }
}
