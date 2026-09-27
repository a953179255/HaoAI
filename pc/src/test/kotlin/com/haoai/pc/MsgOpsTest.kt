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
 * 消息级操作里新加的两件事：**删掉某一句**、**把某条会话置顶**。
 *
 * 判据都不看接口返回 200：删完要真的少掉若干条、且下一次请求的历史里没有孤儿 tool 回复；
 * 置顶要真的排在时间序前面，并且换个进程读盘还在（字段落在会话文件里，不是内存里）。
 */
class MsgOpsTest {

    companion object {
        private lateinit var gateway: com.sun.net.httpserver.HttpServer
        private lateinit var server: WebServer
        private lateinit var base: String
        private val http = HttpClient.newHttpClient()

        @BeforeClass
        @JvmStatic
        fun setUp() {
            val home = Files.createTempDirectory("haoai-msgops-home").toFile()
            System.setProperty("haoai.home", File(home, "home").absolutePath)
            gateway = com.sun.net.httpserver.HttpServer.create(
                java.net.InetSocketAddress("127.0.0.1", 0), 0
            )
            gateway.createContext("/v1/chat/completions") { ex ->
                ex.requestBody.readBytes()
                val b = ("data: " +
                    """{"choices":[{"index":0,"finish_reason":"stop","delta":{"content":"好的"}}]}""" +
                    "\n\ndata: [DONE]\n\n").toByteArray()
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
    private fun arr(s: String) = Json.parseToJsonElement(s).jsonArray

    private fun newSession(tag: String): String {
        val dir = File(Env.home.parentFile, "mws-" + tag).apply { mkdirs() }
        val r = obj(post("/api/new", "{\"ws\":\"" + dir.absolutePath.replace("\\", "\\\\") + "\"}"))
        return r["id"]!!.jsonPrimitive.content
    }

    private fun runTask(sid: String, text: String) {
        post("/api/task", """{"sid":"$sid","text":"$text"}""")
        val until = System.currentTimeMillis() + 15_000
        while (obj(get("/api/state?sid=$sid"))["running"]!!.jsonPrimitive.content.toBoolean() &&
            System.currentTimeMillis() < until) Thread.sleep(120)
    }

    private fun msgs(sid: String) = arr(obj(get("/api/state?sid=$sid"))["messages"]!!.toString())
    private fun roles(sid: String) = msgs(sid).map { it.jsonObject["role"]?.jsonPrimitive?.content }

    @Test
    fun `deleting a user line takes its whole turn with it`() {
        val sid = newSession("d1")
        runTask(sid, "第一句")
        runTask(sid, "第二句")
        val before = roles(sid)
        assertTrue("该有两句用户话，实际：$before", before.count { it == "user" } >= 2)
        val secondUser = before.indexOfLast { it == "user" }
        val r = obj(post("/api/delmsg", """{"sid":"$sid","index":"$secondUser"}"""))
        assertTrue("删不掉：" + r["error"]?.jsonPrimitive?.content, r["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("删的应该是这一句和它带出来的回答", 2,
            r["removed"]!!.jsonPrimitive.content.toInt())
        val after = roles(sid)
        assertEquals("剩下的用户话", 1, after.count { it == "user" })
        assertFalse("不该留下孤儿 tool 回复", after.contains("tool") && !after.contains("assistant"))
    }

    @Test
    fun `an index that is not there is refused without touching history`() {
        val sid = newSession("d2")
        runTask(sid, "只有一句")
        val n = msgs(sid).size
        val r = obj(post("/api/delmsg", """{"sid":"$sid","index":"900"}"""))
        assertFalse(r["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(n, msgs(sid).size)
    }

    /** 置顶要赢过时间序，而且要落在文件里：只改内存的话重启就回到原样。 */
    @Test
    fun `pin puts a session above the newer ones and survives a reload`() {
        val old = newSession("p1")
        Thread.sleep(20)
        val fresh = newSession("p2")
        fun order() = arr(get("/api/sessions")).map { it.jsonObject["id"]?.jsonPrimitive?.content }
        assertTrue("默认该是新的在前：" + order(), order().indexOf(fresh) < order().indexOf(old))
        assertTrue(obj(post("/api/pin", """{"id":"$old","pinned":"true"}"""))["ok"]
            ?.jsonPrimitive?.content == "true")
        assertEquals("置顶的那条要排第一", old, order().first())
        assertTrue("字段要落到会话文件里",
            File(Env.sessionsDir, "pc-$old.json").readText().contains("\"pinned\":true"))
        post("/api/pin", """{"id":"$old","pinned":"false"}""")
        assertTrue("取消之后回到时间序：" + order(), order().indexOf(fresh) < order().indexOf(old))
    }
}
