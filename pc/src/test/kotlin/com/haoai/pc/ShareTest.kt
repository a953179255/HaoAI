package com.haoai.pc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * 只读分享快照的判据。
 *
 * 这份东西是"发给别人看"的，所以两条最要紧：
 * ① 页面里不许有可执行的东西（脚本、外部资源）—— 一条会话的正文是模型写的，
 *    把它原样塞进 HTML 而不转义，就是把"给人看"变成"在别人机器上跑"；
 * ② 读回来的名字不能跑出快照目录 —— 那个 name 来自 URL。
 */
class ShareTest {

    companion object {
        private lateinit var gateway: com.sun.net.httpserver.HttpServer
        private lateinit var server: WebServer
        private lateinit var base: String
        private val http = HttpClient.newHttpClient()
        private const val REPLY = "快照判据的结论：这一句要在页面里看得见"

        @BeforeClass
        @JvmStatic
        fun setUpClass() {
            val home = Files.createTempDirectory("haoai-share-home").toFile()
            System.setProperty("haoai.home", File(home, "home").absolutePath)
            File(home, "home").mkdirs()
            val ws = File(home, "ws").apply { mkdirs() }
            gateway = com.sun.net.httpserver.HttpServer.create(
                java.net.InetSocketAddress("127.0.0.1", 0), 0
            )
            gateway.createContext("/v1/chat/completions") { ex ->
                ex.requestBody.readBytes()
                val b = ("data: " +
                    """{"choices":[{"index":0,"finish_reason":"stop","delta":{"content":"$REPLY"}}]}""" +
                    "\n\ndata: [DONE]\n\n").toByteArray()
                ex.responseHeaders.add("Content-Type", "text/event-stream")
                ex.sendResponseHeaders(200, b.size.toLong())
                ex.responseBody.use { it.write(b) }
                ex.close()
            }
            gateway.start()
            PcSettings.save(PcSettings(
                workspace = ws.absolutePath, permissionMode = "auto", model = "share-test",
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
        HttpRequest.newBuilder(URI.create(base + path)).GET().build(), HttpResponse.BodyHandlers.ofString()
    ).body()

    private fun field(json: String, key: String): String =
        Json.parseToJsonElement(json).jsonObject[key]?.jsonPrimitive?.contentOrNull ?: ""

    private fun sample() = Share.render(
        "剪片这条", "G:\\素材", "share-test", "auto", System.currentTimeMillis(),
        listOf(
            Triple("user", "", "把今天的录屏剪成 30 秒"),
            Triple("assistant", "", "好的，先抽关键帧<script>alert('xss')</script>"),
            Triple("tool", "media", "exit=0\n已写出 clip.mp4")
        )
    )

    // ---- 页面本身 ----

    @Test
    fun `every message shows up and nothing in the page can execute`() {
        val html = sample()
        assertTrue("用户那句要在", html.contains("把今天的录屏剪成 30 秒"))
        assertTrue("助手的半句要在", html.contains("好的，先抽关键帧"))
        assertTrue("工具行只留第一行", html.contains("exit=0") && !html.contains("已写出 clip.mp4"))
        // 正文里带的 script 标签必须被转义成文本，不能成为标签
        assertTrue("该被转义：" + html.substringAfter("关键帧").take(40),
            html.contains("&lt;script&gt;"))
        assertFalse("整页不该有一个真 script 标签", Regex("<script[ >]").containsMatchIn(html))
        assertFalse("不该引外部资源", html.contains("src=") || html.contains("link rel"))
        assertFalse("不该出现 http 外链", html.contains("http://") || html.contains("https://"))
        assertTrue("标题与模型工作区都在", html.contains("剪片这条") && html.contains("share-test"))
    }

    @Test
    fun `an empty session still makes a readable page`() {
        // 类型参数是必须的：`render` 现在有两个入口（三元组 / 带出处的 Row），
        // 光写 emptyList() 编译器推不出该走哪一条（报的是"推不出 T"，看着像产品写坏了）
        val html = Share.render("空的", "", "", "ask", System.currentTimeMillis(),
            emptyList<Triple<String, String, String>>())
        assertTrue("要说清是空的，别给一张白页：" + html.takeLast(120), html.contains("还没有说过话"))
    }

    @Test
    fun `markdown in the answer becomes structure, not raw syntax`() {
        val html = Share.mdToHtml(
            "## 小结\n- 工具链路跑通了\n- 用 `run_code` 验过\n\n```python\nprint('hi')\n```\n"
        )
        assertTrue("小标题：" + html, html.contains("<h4>小结</h4>"))
        assertTrue("列表项：" + html, html.contains("<li>工具链路跑通了</li>"))
        assertTrue("行内代码：" + html, html.contains("<code>run_code</code>"))
        assertTrue("代码块：" + html, html.contains("<pre class=\"code\"") && html.contains("print('hi')"))
        assertFalse("围栏符号不该再露出来：" + html, html.contains("```"))
        assertFalse("整页不许有脚本", Regex("<script[ >]").containsMatchIn(html))
    }

    @Test
    fun `an unterminated fence or a script inside the text cannot break the page`() {
        assertTrue("没收尾的围栏也要出内容：" + Share.mdToHtml("```python\nprint(1)\n"),
            Share.mdToHtml("```python\nprint(1)\n").contains("print(1)"))
        val evil = Share.mdToHtml("看这个 <img src=x onerror=alert(1)> 与 <script>bad()</script>")
        assertFalse("标签必须被转义：" + evil, evil.contains("<img") || evil.contains("<script"))
        assertTrue("文本还在：" + evil, evil.contains("&lt;script&gt;"))
    }

    // ---- 名字与目录 ----

    @Test
    fun `a name from the url cannot walk out of the share dir`() {
        for (bad in listOf("../settings.json", "..\\..\\windows\\win.ini", "x.htm", "", "a b.html"))
            assertNull("该拒绝：$bad", Share.safeName(bad))
        val ok = Share.nameFor("pc12ab34cd")
        assertTrue("自己写的名字要认：" + ok, Share.safeName(ok) == ok)
        // 真去读一次：目录外的东西读不出来
        File(Env.home, "secret.html").writeText("不该被读到")
        assertNull(Share.read("../secret.html"))
        assertNull(Share.read("nope.html"))
        File(Env.home, "secret.html").delete()
    }

    // ---- 接口 ----

    @Test
    fun `the endpoint writes a file and serves the same page back`() {
        val sid = field(post("/api/new", "{}"), "id")
        post("/api/task", """{"sid":${quote(sid)},"text":"说一句给快照用的话"}""")
        val until = System.currentTimeMillis() + 30_000L
        while (System.currentTimeMillis() < until && !get("/api/state?sid=$sid").contains(REPLY)) Thread.sleep(200)
        assertTrue("会话没收尾，快照无从谈起", get("/api/state?sid=$sid").contains(REPLY))

        val made = post("/api/share", """{"sid":${quote(sid)}}""")
        assertTrue("该成功：" + made.take(120), made.contains("\"ok\":true"))
        val name = field(made, "name")
        val path = field(made, "path")
        assertTrue("文件真在盘上：" + path, File(path).isFile)
        assertTrue("落在 share 目录里，不在用户工作区", File(path).parentFile.name == "share")
        val page = get("/api/share?name=$name")
        assertTrue("页面里要有那句结论", page.contains(REPLY))
        assertFalse("页面里不该有输入框或按钮（只读）", page.contains("<button") || page.contains("<input"))
        assertTrue("读不出来的名字要挡掉", get("/api/share?name=../../settings.json").contains("没有这个快照"))
    }

    @Test
    fun `a session nobody spoke in still exports and says so`() {
        val sid = field(post("/api/new", "{}"), "id")
        val made = post("/api/share", """{"sid":${quote(sid)}}""")
        assertTrue(made.take(120), made.contains("\"ok\":true"))
        assertEquals(0, field(made, "blocks").toInt())
        assertTrue(get("/api/share?name=" + field(made, "name")).contains("还没有说过话"))
    }

    private fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
