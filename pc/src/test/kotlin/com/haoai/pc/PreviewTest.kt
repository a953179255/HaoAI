package com.haoai.pc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
 * 浏览器预览面板的后端判据。
 *
 * 为什么单拎一层测：真浏览器只会走"一切正常"那条路，而这批代码里会出事的全在边界上 ——
 * 坐标换算算出负数或越界就是"点到页面外面"，网址白名单漏一个 `file://`
 * 就等于给这条通道开了个读本地文件的口。这些用真浏览器反而测不到。
 */
class PreviewTest {

    // ---- 纯逻辑 ----

    @Test
    fun `a bare host gets https and real urls pass through`() {
        assertEquals("https://example.com", PreviewPanel.safeUrl("example.com"))
        assertEquals("http://127.0.0.1:8000/x?a=1", PreviewPanel.safeUrl("  http://127.0.0.1:8000/x?a=1 "))
        // 大小写不改写：这条通道的职责是"收/拒"，不是替人重写网址
        assertEquals("HTTPS://a.b/c", PreviewPanel.safeUrl("HTTPS://a.b/c"))
        assertEquals("http://localhost:8080/x", PreviewPanel.safeUrl("localhost:8080/x"))
    }

    @Test
    fun `file javascript and data urls are refused`() {
        // 这三个是"看着像网址、其实是要么读盘要么注脚本"的
        listOf("file:///C:/Windows/win.ini", "javascript:alert(1)", "data:text/html,<script>alert(1)</script>",
            "about:config", "chrome://settings").forEach {
            assertNull("该拒：$it", PreviewPanel.safeUrl(it))
        }
        assertNull(PreviewPanel.safeUrl(""))
        assertNull(PreviewPanel.safeUrl("   "))
    }

    @Test
    fun `control chars and absurd length are refused`() {
        assertNull(PreviewPanel.safeUrl("http://a.b/" + String(charArrayOf('\n'))))
        assertNull(PreviewPanel.safeUrl("http://a.b/" + "x".repeat(2100)))
        assertNotNull(PreviewPanel.safeUrl("http://a.b/" + "x".repeat(500)))
    }

    @Test
    fun `an image point scales to the page point`() {
        // 画面 270x200 显示，页面视口 1080x800：图正中就是页面正中
        val p = PreviewPanel.mapClick(135.0, 100.0, 270, 200, 1080, 800)
        assertEquals(540 to 400, p)
        val q = PreviewPanel.mapClick(0.0, 0.0, 270, 200, 1080, 800)
        assertEquals(0 to 0, q)
    }

    @Test
    fun `points outside the frame clamp into the viewport`() {
        // 边框/滚动条上误点一下，不该变成"点到页面外面"（CDP 会照单全收）
        val p = PreviewPanel.mapClick(9999.0, 9999.0, 270, 200, 1080, 800)
        assertEquals(1079 to 799, p)
        val n = PreviewPanel.mapClick(-40.0, -12.0, 270, 200, 1080, 800)
        assertEquals(0 to 0, n)
    }

    @Test
    fun `nothing is dispatched before the page is measured`() {
        assertEquals(0 to 0, PreviewPanel.mapClick(10.0, 10.0, 0, 0, 0, 0))
        assertEquals(0 to 0, PreviewPanel.mapClick(10.0, 10.0, 270, 200, 0, 800))
    }

    @Test
    fun `the flag-off state json carries the where-to-turn-it-on note`() {
        val o = Json.parseToJsonElement(
            PreviewPanel.stateJson(false, PreviewPanel.OFF_NOTE, "", "", 0, 0, emptyList())
        ).jsonObject
        assertEquals("false", o["on"]!!.jsonPrimitive.content)
        assertTrue("要说清去哪开：" + o["note"]!!.jsonPrimitive.content,
            o["note"]!!.jsonPrimitive.content.contains("browser_control"))
        assertEquals(0, o["pages"]!!.jsonArray.size)
    }

    @Test
    fun `every page tab survives the json round trip`() {
        val pages = listOf("id1" to "第一个页面" to "http://a/1", "id2" to "第二个" to "http://a/2")
            .map { Triple(it.first.first, it.first.second, it.second) }
        val raw = PreviewPanel.stateJson(true, "", "http://a/2", "第二个", 1200, 700, pages)
        val o = Json.parseToJsonElement(raw).jsonObject
        assertEquals(1200, o["vw"]!!.jsonPrimitive.content.toInt())
        val rows = o["pages"]!!.jsonArray
        assertEquals(2, rows.size)
        assertEquals("id2", rows[1].jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals("第二个", rows[1].jsonObject["title"]!!.jsonPrimitive.content)
        assertEquals("http://a/2", rows[1].jsonObject["url"]!!.jsonPrimitive.content)
    }

    // ---- HTTP 层：开关关着时这套端点必须"什么都不会动" ----

    companion object {
        private lateinit var server: WebServer
        private lateinit var base: String
        private val http = HttpClient.newHttpClient()

        @BeforeClass
        @JvmStatic
        fun setUp() {
            val home = Files.createTempDirectory("haoai-preview-home").toFile()
            System.setProperty("haoai.home", File(home, "home").absolutePath)
            val ws = File(home, "ws").apply { mkdirs() }
            // browser_control 默认关：这一层测的就是"关着时它到底动不动浏览器"
            PcSettings.save(PcSettings(workspace = ws.absolutePath, permissionMode = "auto"))
            server = WebServer(PcSettings.load(), port = 0)
            base = "http://127.0.0.1:" + server.start()
        }

        @AfterClass
        @JvmStatic
        fun tearDown() {
            runCatching { server.stop() }
        }

        private fun get(path: String): Pair<Int, String> {
            val r = http.send(HttpRequest.newBuilder(URI.create(base + path))
                .GET().build(), HttpResponse.BodyHandlers.ofString())
            return r.statusCode() to r.body()
        }

        private fun post(path: String, body: String): Pair<Int, String> {
            val r = http.send(HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString())
            return r.statusCode() to r.body()
        }
    }

    @Test
    fun `state with the flag off reports off without touching a browser`() {
        val (code, body) = get("/api/preview/state")
        assertEquals(200, code)
        val o = Json.parseToJsonElement(body).jsonObject
        assertEquals("false", o["on"]!!.jsonPrimitive.content)
        assertTrue(o["note"]!!.jsonPrimitive.content.contains("browser_control"))
    }

    @Test
    fun `a bad url is rejected before the flag is even looked at`() {
        // 顺序很重要：网址白名单在"要不要起浏览器"之前，所以坏网址不会先把一台 Edge 拉起来
        val (code, body) = post("/api/preview/open", """{"url":"file:///C:/Windows/win.ini"}""")
        assertEquals(200, code)
        val o = Json.parseToJsonElement(body).jsonObject
        assertEquals("false", o["ok"]!!.jsonPrimitive.content)
        assertTrue(o["error"]!!.jsonPrimitive.content.contains("http/https"))
    }

    @Test
    fun `a good url with the flag off says where to turn it on`() {
        val o = Json.parseToJsonElement(post("/api/preview/open",
            """{"url":"http://127.0.0.1:8000/"}""").second).jsonObject
        assertEquals("false", o["ok"]!!.jsonPrimitive.content)
        assertTrue(o["error"]!!.jsonPrimitive.content.contains("实验特性"))
    }

    @Test
    fun `the frame endpoint answers 404 so the panel can see there is no frame`() {
        // 面板靠这个非 200 把"这帧没拿到"显示成人话；回 200 + 空 body 会让 <img> 静默不响
        val (code, body) = get("/api/preview/frame")
        assertEquals(404, code)
        assertTrue(body.contains("browser_control") || body.contains("浏览器"))
    }

    @Test
    fun `an unknown input action is reported instead of silently doing nothing`() {
        // 开关关着时先被闸挡下 —— 这条测的是"绝不会把不认的动作当成成功"
        val o = Json.parseToJsonElement(post("/api/preview/input",
            """{"kind":"teleport","x":1,"y":1}""").second).jsonObject
        assertEquals("false", o["ok"]!!.jsonPrimitive.content)
    }
}
