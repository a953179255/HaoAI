package com.haoai.pc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
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
 * 会话级工具开关。
 *
 * 判据不看界面上那个勾，看**发给网关的请求体里 tools 还有没有它**；
 * 再看模型硬调一把已关掉的工具时执行侧挡不挡得住 —— 可见性挡不住记忆。
 */
class ToolToggleTest {

    companion object {
        private lateinit var gateway: com.sun.net.httpserver.HttpServer
        private lateinit var bodies: MutableList<String>
        private lateinit var server: WebServer
        private lateinit var base: String
        private val http = HttpClient.newHttpClient()
        /** 只让"硬调"那次任务的第一轮返回 tool_calls：第二轮回正常收尾，循环才不会转到底。 */
        private val HALLUCINATE = java.util.concurrent.atomic.AtomicBoolean(false)

        @BeforeClass
        @JvmStatic
        fun setUp() {
            val home = Files.createTempDirectory("haoai-tool-home").toFile()
            System.setProperty("haoai.home", File(home, "home").absolutePath)
            bodies = java.util.Collections.synchronizedList(mutableListOf<String>())
            gateway = com.sun.net.httpserver.HttpServer.create(
                java.net.InetSocketAddress("127.0.0.1", 0), 0
            )
            gateway.createContext("/v1/chat/completions") { ex ->
                val body = String(ex.requestBody.readBytes(), Charsets.UTF_8)
                bodies += body
                // 带"硬调"的那一轮先回一个 shell 的 tool_calls，用来验执行侧挡不挡；
                // 一旦历史里出现了拒绝语，就正常收尾，免得无限循环。
                val first = body.contains("硬调") && HALLUCINATE.compareAndSet(false, true)
                val frame = if (first) {
                    """{"choices":[{"index":0,"finish_reason":"tool_calls","delta":{"tool_calls":[{""" +
                        """"index":0,"id":"c1","type":"function","function":{"name":"shell",""" +
                        """"arguments":"{\"command\":\"echo hi\"}"}}]}}]}"""
                } else {
                    """{"choices":[{"index":0,"finish_reason":"stop","delta":{"content":"好了"}}]}"""
                }
                val b = ("data: " + frame + "\n\ndata: [DONE]\n\n").toByteArray()
                ex.responseHeaders.add("Content-Type", "text/event-stream")
                ex.sendResponseHeaders(200, b.size.toLong())
                ex.responseBody.use { it.write(b) }
                ex.close()
            }
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

    private fun get(path: String): String = http.send(
        HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
        HttpResponse.BodyHandlers.ofString()
    ).body()

    private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject

    private fun newSession(tag: String): String {
        val dir = File(Env.home.parentFile, "tws-" + tag).apply { mkdirs() }
        val r = obj(post("/api/new", "{\"ws\":\"" + dir.absolutePath.replace("\\", "\\\\") + "\"}"))
        return r["id"]!!.jsonPrimitive.content
    }

    private fun offState(sid: String, name: String): Boolean? =
        obj(get("/api/state?sid=$sid"))["tools"]!!.jsonArray
            .firstOrNull { it.jsonObject["name"]?.jsonPrimitive?.content == name }
            ?.jsonObject?.get("off")?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()

    /** 网关收到的那次请求里，tools 数组有没有这把工具。 */
    private fun sentHasTool(body: String, name: String): Boolean =
        obj(body)["tools"]?.jsonArray?.any {
            it.jsonObject["function"]?.jsonObject?.get("name")?.jsonPrimitive?.content == name
        } == true

    private fun awaitIdle(sid: String, ms: Long = 20_000): Boolean {
        val until = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < until) {
            if (!obj(get("/api/state?sid=$sid"))["running"]!!.jsonPrimitive.content.toBoolean()) return true
            Thread.sleep(120)
        }
        return false
    }

    @Test
    fun `the catalog lists tools and shell starts on`() {
        val sid = newSession("t0")
        assertEquals("新会话该是全开的", false, offState(sid, "shell"))
        val n = obj(get("/api/state?sid=$sid"))["tools"]!!.jsonArray.size
        assertTrue("工具清单不该是空的，实际 $n", n >= 10)
    }

    @Test
    fun `turning a tool off affects only this session and reaches the gateway`() {
        val a = newSession("t1")
        val b = newSession("t2")
        assertTrue(obj(post("/api/tool", """{"sid":"$a","name":"shell","on":"0"}"""))["ok"]
            ?.jsonPrimitive?.content == "true")
        assertEquals("A 该关掉", true, offState(a, "shell"))
        assertEquals("B 不该受影响", false, offState(b, "shell"))
        bodies.clear()
        post("/api/task", """{"sid":"$a","text":"随便说一句"}""")
        assertTrue(awaitIdle(a))
        post("/api/task", """{"sid":"$b","text":"随便说一句"}""")
        assertTrue(awaitIdle(b))
        val sentA = bodies.first { it.contains("随便说一句") }
        assertFalse("A 的请求体里不该还有 shell", sentHasTool(sentA, "shell"))
        val sentB = bodies.last { it.contains("随便说一句") }
        assertTrue("B 的请求体里 shell 该在", sentHasTool(sentB, "shell"))
    }

    /** 可见性挡不住"模型凭记忆硬调"：执行侧必须再挡一次，并且说清去哪儿开。 */
    @Test
    fun `a hallucinated call to a disabled tool is refused at execution`() {
        val sid = newSession("t3")
        post("/api/tool", """{"sid":"$sid","name":"shell","on":"0"}""")
        bodies.clear()
        HALLUCINATE.set(false)
        post("/api/task", """{"sid":"$sid","text":"硬调一下"}""")
        assertTrue("等不到收尾", awaitIdle(sid))
        val mine = bodies.filter { it.contains("硬调") }
        assertTrue("这条会话该有两次请求（一次硬调、一次收尾），实际 ${mine.size}", mine.size >= 2)
        val refused = mine.last()
        assertTrue("模型硬调 shell 时没被挡住，最后一请求的历史：" +
            (obj(refused)["messages"]?.jsonArray?.lastOrNull()
                ?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull ?: "?"),
            refused.contains("被关掉了"))
        assertFalse("拒绝之后不该再带着 shell 去请求", sentHasTool(refused, "shell"))
    }

    @Test
    fun `the switch survives a restart because it is written into the session file`() {
        val sid = newSession("t4")
        post("/api/tool", """{"sid":"$sid","name":"shell","on":"0"}""")
        val text = File(Env.sessionsDir, "pc-$sid.json").readText()
        assertTrue("会话文件里要写着关掉的工具", text.contains("\"toolsOff\":[\"shell\"]"))
        // 丢掉内存里那个引擎实例，逼 server 从盘上重建一个：状态要还是关着的
        post("/api/delete", """{"id":"$sid"}""")
        post("/api/untrash", """{"name":"${File(Env.sessionsDir, ".trash").listFiles()
            ?.firstOrNull { it.name.endsWith("pc-$sid.json") }?.name ?: ""}"}""")
        post("/api/open", """{"id":"$sid"}""")
        assertEquals("重开之后该还是关着", true, offState(sid, "shell"))
    }
}
