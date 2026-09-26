package com.haoai.pc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
 * HTTP 层的回归。
 *
 * 为什么单独要一层：`/api/decide` 的"允许一次"曾经**一直是拒绝** ——
 * 处理器里每个字段各调一次 `field(ex, …)`，而 `HttpExchange.requestBody` 是一次性的流，
 * 于是只有第一个字段拿得到值，`decision` 永远是空串。75 条单测全绿也照样漏，
 * 因为它们测的是引擎/存储，没人从 HTTP 口进去。
 *
 * 所以这里只盯一类事：**一个请求体里要读多个字段的接口，字段必须都拿得到**。
 */
class WebApiTest {

    companion object {
        private lateinit var server: WebServer
        private lateinit var base: String
        private val http = HttpClient.newHttpClient()
        private lateinit var settingsFile: File

        @BeforeClass
        @JvmStatic
        fun setUp() {
            val home = Files.createTempDirectory("haoai-web-home").toFile()
            System.setProperty("haoai.home", File(home, "home").absolutePath)
            val ws = File(home, "ws").apply { mkdirs() }
            settingsFile = Env.settingsFile
            PcSettings.save(PcSettings(workspace = ws.absolutePath, permissionMode = "ask"))
            server = WebServer(PcSettings.load(), port = 0)
            val port = server.start()
            base = "http://127.0.0.1:$port"
        }

        @AfterClass
        @JvmStatic
        fun tearDown() {
            runCatching { server.stop() }
        }

        private fun post(path: String, body: String): Pair<Int, String> {
            val r = http.send(
                HttpRequest.newBuilder(URI.create(base + path))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString()
            )
            return r.statusCode() to r.body()
        }

        private fun get(path: String): String = http.send(
            HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
            HttpResponse.BodyHandlers.ofString()
        ).body()

        private fun newSessionId(): String {
            val s = Session("web" + System.nanoTime(), File(Env.home, "ws"))
            s.title.set("原标题")
            s.file.parentFile?.mkdirs()
            s.file.writeText(
                """{"id":"${s.id}","title":"原标题","workspace":"${s.workspace.absolutePath.replace("\\", "\\\\")}",""" +
                    """"mode":"ask","updated":1,"messages":[{"role":"user","content":"一句话"}]}"""
            )
            return s.id
        }
    }

    @Test
    fun `a two-field body reaches both fields`() {
        val id = newSessionId()
        val (code, body) = post("/api/rename", """{"id":"$id","title":"改名成功"}""")
        assertEquals("两个字段的重命名请求被拒了（第二个字段读不到就是这个症状）：$body", 200, code)
        assertTrue(get("/api/sessions").contains("改名成功"))
    }

    @Test
    fun `a blank title is refused without touching the file`() {
        val id = newSessionId()
        val (code, _) = post("/api/rename", """{"id":"$id","title":"   "}""")
        assertEquals(404, code)
        assertTrue("被拒的请求还是改了标题", get("/api/sessions").contains("原标题"))
    }

    @Test
    fun `an unknown session id answers 404 instead of 500`() {
        val (code, _) = post("/api/rename", """{"id":"does-not-exist","title":"x"}""")
        assertEquals(404, code)
        val (c2, _) = post("/api/delete", """{"id":"does-not-exist"}""")
        assertEquals(404, c2)
    }

    /**
     * 设置面板一次要写六七个字段 —— 和当年把 decide 写坏的同一类接口。
     * 这里不测 UI，只测"多字段写进去后每个字段都真的生效了"。
     */
    @Test
    fun `a multi-field settings post applies every field`() {
        val dir = Files.createTempDirectory("haoai-web-ws").toFile()
        val (code, _) = post(
            "/api/settings",
            """{"model":"glm-9.test","baseUrl":"https://example.test/v1",""" +
                """"workspace":"${dir.absolutePath.replace("\\", "\\\\")}","mode":"auto",""" +
                """"flags":{"desktop_control":true}}"""
        )
        assertEquals(200, code)
        val saved = Json.parseToJsonElement(settingsFile.readText()).jsonObject
        assertEquals("glm-9.test", saved["model"]?.jsonPrimitive?.content)
        assertEquals("https://example.test/v1", saved["baseUrl"]?.jsonPrimitive?.content)
        assertEquals("auto", saved["permissionMode"]?.jsonPrimitive?.content)
        // 存进 JSON 时反斜杠被转义，读回来就是原样路径 —— 别拿转义后的字面量比
        assertEquals(dir.absolutePath, saved["workspace"]?.jsonPrimitive?.content)
        assertEquals(
            "flags 只保留与默认值不同的覆盖（desktop_control 默认关，开=覆盖）",
            "true", saved["flags"]?.jsonObject?.get("desktop_control")?.jsonPrimitive?.content
        )
    }
}
