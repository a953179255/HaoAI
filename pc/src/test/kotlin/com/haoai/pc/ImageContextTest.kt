package com.haoai.pc

import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
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
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files

/**
 * 图片进上下文（多模态）这条线。
 *
 * 为什么单独测：这条路上有两个"看不见的坑" ——
 * ① 历史里存的是路径还是 base64（存错一次，会话文件就被一张截图撑爆）；
 * ② 浏览器能提交任意路径，而图片会被 base64 之后**发到远端网关**，
 *    所以"不许逃出工作区"不是洁癖，是把读文件的口子开给浏览器的区别。
 * 判据因此落在网关真收到的请求体上，不是"接口回了 200"。
 */
class ImageContextTest {

    companion object {
        /** 只写魔数 + 一点内容：认类型只看头几个字节，不需要一张真图。 */
        fun png(at: File, tag: String): File {
            at.parentFile?.mkdirs()
            val head = byteArrayOf(
                0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(),
                0x0D, 0x0A, 0x1A, 0x0A
            )
            at.writeBytes(head + ("$tag-".repeat(64)).toByteArray())
            return at
        }

        private lateinit var gateway: HttpServer
        private lateinit var bodies: MutableList<String>
        private lateinit var server: WebServer
        private lateinit var base: String
        private lateinit var ws: File
        private lateinit var home: File
        private val http = HttpClient.newHttpClient()

        @BeforeClass
        @JvmStatic
        fun setUp() {
            home = Files.createTempDirectory("haoai-img-home").toFile()
            System.setProperty("haoai.home", File(home, "home").absolutePath)
            ws = File(home, "ws").apply { mkdirs() }
            bodies = mutableListOf()
            gateway = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            gateway.createContext("/v1/chat/completions") { ex ->
                bodies += String(ex.requestBody.readBytes(), Charsets.UTF_8)
                val b = ("data: " +
                    """{"choices":[{"index":0,"finish_reason":"stop","delta":{"content":"看到了"}}]}""" +
                    "\n\ndata: [DONE]\n\n").toByteArray()
                ex.responseHeaders.add("Content-Type", "text/event-stream")
                ex.sendResponseHeaders(200, b.size.toLong())
                ex.responseBody.use { it.write(b) }
                ex.close()
            }
            gateway.start()
            PcSettings.save(PcSettings(
                workspace = ws.absolutePath, permissionMode = "auto",
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

    @Test
    fun `magic bytes decide, not the extension`() {
        val dir = Files.createTempDirectory("haoai-img-mime").toFile()
        assertNotNull("PNG 头要认得", Images.mime(png(File(dir, "a.png"), "P")))
        assertEquals("image/png", Images.mime(png(File(dir, "lieslike.txt"), "P")))
        // 扩展名叫 .png 但内容是文本：认不出就不发，别让网关报错拖垮整轮
        val fake = File(dir, "fake.png").apply { writeText("这只是文字，不是图") }
        assertEquals(null, Images.mime(fake))
        assertEquals(null, Images.dataUrl(fake.absolutePath))
        assertEquals(null, Images.mime(File(dir, "nope-here.png")))
    }

    @Test
    fun `a plain user message keeps a string content so non-vision gateways stay happy`() {
        val o = msgJson(Msg("user", "普通一句话"))
        assertEquals("普通一句话", o["content"]!!.jsonPrimitive.content)
        assertFalse(o.toString().contains("image_url"))
    }

    @Test
    fun `an image turns the user content into text plus image_url parts`() {
        val f = png(File(Files.createTempDirectory("haoai-img-wire").toFile(), "one.png"), "Q")
        val o = msgJson(Msg("user", "看这张", images = listOf(f.absolutePath)))
        val parts = o["content"] as? JsonArray ?: error("content 应当是数组：$o")
        assertEquals(2, parts.size)
        assertEquals("text", parts[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("看这张", parts[0].jsonObject["text"]!!.jsonPrimitive.content)
        val url = parts[1].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content
        assertTrue("data URL 要带类型：" + url.take(24), url.startsWith("data:image/png;base64,"))
        // 一张坏图不该把整轮对话弄发不出去：只留文本
        val mixed = msgJson(Msg("user", "两张", images = listOf(f.absolutePath, "/没有这个文件.png")))
        assertEquals(2, (mixed["content"] as JsonArray).size)
    }

    @Test
    fun `history stores the path, never the base64`() {
        val s = io.openSession("img-store")
        val f = png(File(s.workspace, "shot.png"), "S")
        val e = Engine(s, PcSettings.load(), builtinTools(), AllowAll(), {}, Plain())
        e.submit("先看这张", listOf(f.absolutePath))
        val txt = s.file.readText()
        assertTrue("路径要在历史里：" + f.name, txt.contains("shot.png"))
        assertFalse("base64 不许进会话文件（一次截图就是几 MB）", txt.contains("base64"))
        val back = Json.parseToJsonElement(txt).jsonObject["messages"]!!.jsonArray
        val withImg = back.firstOrNull { it.jsonObject["images"] != null }
        assertNotNull(withImg)
        assertEquals(
            listOf(f.absolutePath),
            withImg!!.jsonObject["images"]!!.jsonArray.map { it.jsonPrimitive.content }
        )
    }

    @Test
    fun `the context ring counts the real wire cost of an image`() {
        val s = io.openSession("img-ctx")
        val f = png(File(s.workspace, "big.png"), "B")
        val plain = Engine(s, PcSettings.load(), builtinTools(), AllowAll(), {}, Plain())
        plain.submit("没有图")
        val a = plain.contextChars()
        val s2 = io.openSession("img-ctx2")
        val with = Engine(s2, PcSettings.load(), builtinTools(), AllowAll(), {}, Plain())
        with.submit("有图", listOf(f.absolutePath))
        val b = with.contextChars()
        assertTrue("一张 ${f.length()} 字节的图按 base64 算应当明显抬高上下文（$a → $b）", b - a > f.length())
    }

    /** 端到端：从网页那个接口进去，看网关真的收到了 image_url。 */
    @Test
    fun `an attached image reaches the gateway as image_url`() {
        val inside = png(File(ws, "notes/shot.png"), "I")
        val outside = png(File(home, "private-key.png"), "O")
        bodies.clear()
        val sid = post("/api/new", "{}").let { Json.parseToJsonElement(it).jsonObject["id"]!!.jsonPrimitive.content }
        post(
            "/api/task",
            """{"text":"这张图里有什么","sid":"$sid","images":["notes/shot.png",""" +
                """"${esc(outside.absolutePath)}"]}"""
        )
        val until = System.currentTimeMillis() + 15_000
        while (bodies.isEmpty() && System.currentTimeMillis() < until) Thread.sleep(100)
        assertTrue("网关该收到一次请求", bodies.isNotEmpty())
        val body = bodies.first()
        assertTrue("请求体里要有 image_url", body.contains("image_url"))
        assertTrue("工作区里那张要真的递出去（base64）", body.contains("data:image/png;base64,"))
        assertFalse(
            "工作区外面那条路径绝对不许被递出去：" + outside.name,
            body.contains("private-key") || base64Of(outside) in body
        )
        assertEquals("看到了", lastText(sid))
    }

    private fun base64Of(f: File): String = Images.dataUrl(f.absolutePath).orEmpty()

    private fun lastText(sid: String): String {
        val until = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < until) {
            val st = Json.parseToJsonElement(get("/api/state?sid=$sid")).jsonObject
            val msgs = st["messages"]?.jsonArray ?: emptyList()
            val ans = msgs.lastOrNull { it.jsonObject["role"]?.jsonPrimitive?.content == "assistant" }
                ?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
            if (!ans.isNullOrBlank()) return ans
            Thread.sleep(150)
        }
        return ""
    }

    private class Plain : ChatClient {
        override fun chat(messages: List<Msg>, tools: List<ToolSchema>, onText: (String) -> Unit) =
            AssistantTurn("看到了", emptyList(), Usage(5, 3), "stop")
    }

    private class AllowAll : Gate {
        override fun approve(title: String, detail: String, kind: String) = true
        override fun ask(question: String, options: List<String>) = options.firstOrNull() ?: ""
    }

    /** 只为了拿一个带工作区的 Session（引擎构造要它）。 */
    private object io {
        fun openSession(id: String): Session =
            Session(id, Files.createTempDirectory("haoai-img-sess").toFile().apply { mkdirs() })
    }

    private fun esc(p: String): String = p.replace("\\", "\\\\").replace("\"", "\\\"")

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
}
