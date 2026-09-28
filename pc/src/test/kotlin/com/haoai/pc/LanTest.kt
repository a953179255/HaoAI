package com.haoai.pc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files

/**
 * 手机联动（局域网通道）的判据。
 *
 * 这一批的代码全是"给别人从网上打"的，所以测的重点不是功能跑通，而是：
 * 没配对的东西进不来、盘上不躺明文凭据、镜像里不漏密钥与绝对路径。
 * 这些用真手机点一遍是点不出来的 —— 得故意发坏请求。
 */
class LanTest {
    private val http = HttpClient.newHttpClient()
    private lateinit var home: File
    private var server: LanServer? = null
    private var port = 0
    private val host = FakeHost()

    private class FakeHost : LanHost {
        var decided: Pair<String, String>? = null
        override fun sessionsJson() = """{"ok":true,"items":[{"id":"s1","title":"跑一条任务"}]}"""
        override fun sessionJson(sid: String) = """{"ok":true,"sid":${quote(sid)},"items":[]}"""
        override fun pendingJson() = """{"ok":true,"items":[{"id":"a1","kind":"approval","sid":"s1"}]}"""
        override fun decide(id: String, decision: String): String {
            decided = id to decision
            return "已按你的决定放行：$decision"
        }

        private fun quote(s: String) = "\"" + s + "\""
    }

    @Before
    fun setUp() {
        home = Files.createTempDirectory("haoai-lan-home").toFile()
        System.setProperty("haoai.home", File(home, "state").absolutePath)
        File(home, "state").mkdirs()
        port = ServerSocket(0).use { it.localPort }
        server = LanServer(host, port).also { assertTrue("局域网端点起不来", it.start()) }
    }

    @After
    fun tearDown() {
        runCatching { server?.stop() }
        runCatching { home.deleteRecursively() }
    }

    // ---- 小工具 ----

    private fun get(path: String, token: String = ""): Pair<Int, String> {
        val b = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path")).timeout(
            java.time.Duration.ofSeconds(5)).GET()
        if (token.isNotEmpty()) b.header("X-HaoAI-Token", token)
        val r = http.send(b.build(), HttpResponse.BodyHandlers.ofString())
        return r.statusCode() to r.body()
    }

    private fun post(path: String, body: String, token: String = ""): Pair<Int, String> {
        val b = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path"))
            .timeout(java.time.Duration.ofSeconds(5))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
        if (token.isNotEmpty()) b.header("X-HaoAI-Token", token)
        val r = http.send(b.build(), HttpResponse.BodyHandlers.ofString())
        return r.statusCode() to r.body()
    }

    /** 走一遍正常配对，换回明文 token。 */
    private fun paired(name: String = "测试机"): String {
        val (code, _) = LanStore.issueCode()
        val (st, body) = post("/lan/pair", """{"code":"$code","name":"$name"}""")
        assertEquals("配对该成功：" + body, 200, st)
        return Json.parseToJsonElement(body).jsonObject["token"]!!.jsonPrimitive.content
    }

    // ---- 配对码 ----

    @Test
    fun `a pairing code is six digits and single use`() {
        val (code, until) = LanStore.issueCode()
        assertTrue("六位数字：$code", Regex("""\d{6}""").matches(code))
        assertTrue("有效期该在未来", until > System.currentTimeMillis())
        assertTrue(LanStore.redeemCode(code))
        assertFalse("用过就该作废，不能被第二次redeem", LanStore.redeemCode(code))
    }

    @Test
    fun `eight wrong guesses kill the code`() {
        val (code, _) = LanStore.issueCode()
        val wrong = if (code == "000000") "000001" else "000000"
        repeat(8) { assertFalse(LanStore.redeemCode(wrong)) }
        assertFalse("猜错够多次之后，连**正确**的码也不该再放行", LanStore.redeemCode(code))
    }

    @Test
    fun `pairing with a wrong code gets 403 and no token`() {
        LanStore.issueCode()
        val (st, body) = post("/lan/pair", """{"code":"000001","name":"路人"}""")
        assertEquals(403, st)
        assertFalse("不该发 token：" + body, body.contains("\"token\""))
    }

    // ---- token 与落盘 ----

    @Test
    fun `the device file keeps a hash, never the token`() {
        val token = paired("魅族 20 Pro")
        val raw = LanStore.file.readText(Charsets.UTF_8)
        assertFalse("明文 token 不许进盘：" + raw.take(120), raw.contains(token))
        assertEquals("只存哈希", 64, Json.parseToJsonElement(raw).jsonObject["devices"]!!
            .jsonArray[0].jsonObject["hash"]!!.jsonPrimitive.content.length)
        assertTrue(LanStore.verify(token) != null)
        assertTrue("错的 token 不该被认", LanStore.verify(token.reversed()) == null)
        assertTrue("空 token 不该被认", LanStore.verify("") == null)
    }

    @Test
    fun `the phone page is served without a token and carries no secrets`() {
        val (st, html) = get("/")
        assertEquals(200, st)
        assertTrue("该是给人看的配对页：" + html.take(80), html.contains("配对码"))
        assertTrue(html.contains("/lan/pair"))
        assertFalse("页面里不许出现任何凭据或密钥" + html.length, html.contains("sk-"))
    }

    @Test
    fun `every lan path the phone page calls really exists`() {
        val (_, html) = get("/")
        val called = Regex("/lan/[a-z]+").findAll(html).map { it.value }.toSet()
        val served = setOf("/lan/pair", "/lan/sessions", "/lan/session", "/lan/pending",
            "/lan/decide", "/lan/unpair", "/lan/health")
        // 页面上写的路径若拼错，手机上只会表现为"一直转圈"—— 静态就能挡掉
        assertTrue("页面调了不存在的路径：" + (called - served - "/lan/"), called.all { it in served })
    }

    @Test
    fun `every lan endpoint refuses an unauthenticated request`() {
        for (path in listOf("/lan/sessions", "/lan/pending", "/lan/session?sid=s1")) {
            val (st, body) = get(path)
            assertEquals("$path 该 401", 401, st)
            assertTrue(body.contains("令牌"))
        }
        val (st, _) = post("/lan/decide", """{"id":"a1","decision":"allow_once"}""")
        assertEquals("决定这条更要挡", 401, st)
    }

    @Test
    fun `health is the only thing answerable without a token`() {
        val (st, body) = get("/lan/health")
        assertEquals(200, st)
        assertTrue(body.contains("haoai-pc"))
    }

    @Test
    fun `a paired device can read the mirror and the host sees it`() {
        val token = paired()
        val (st, body) = get("/lan/sessions", token)
        assertEquals(200, st)
        assertTrue(body.contains("跑一条任务"))
        val o = Json.parseToJsonElement(get("/lan/pending", token).second).jsonObject
        assertEquals("a1", o["items"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a decision from the phone reaches the engine gate`() {
        val token = paired()
        val (st, body) = post("/lan/decide", """{"id":"a1","decision":"allow_once"}""", token)
        assertEquals(200, st)
        assertTrue(body, body.contains("已按你的决定放行"))
        assertEquals("a1" to "allow_once", host.decided)
    }

    @Test
    fun `only the three known decisions are accepted`() {
        val token = paired()
        for (bad in listOf("allow_forever", "yes", "")) {
            val (_, body) = post("/lan/decide", """{"id":"a1","decision":"$bad"}""", token)
            assertTrue("$bad 该被拒：" + body, body.contains("\"ok\":false"))
        }
        assertEquals(null, host.decided)
    }

    @Test
    fun `unpairing kills the token immediately`() {
        val token = paired("临时手机")
        val dev = LanStore.verify(token)!!
        assertTrue(LanStore.remove(dev.hash.take(12)))
        assertTrue("解除之后同一个 token 不该还能用", LanStore.verify(token) == null)
        assertEquals(401, get("/lan/sessions", token).first)
    }

    @Test
    fun `the lan state lives in its own file, not in settings`() {
        paired("看文件位置")
        assertTrue(LanStore.file.name.endsWith("lan.json"))
        val settings = Env.settingsFile
        if (settings.isFile) {
            assertFalse("设置是整份回给前端的，凭据不能进去",
                settings.readText(Charsets.UTF_8).contains("hash"))
        }
    }

    @Test
    fun `the real server mirror carries no key and no absolute workspace path`() {
        runCatching { server?.stop() }
        server = null
        val ws = File(home, "工作区").apply { mkdirs() }
        PcSettings.save(PcSettings(workspace = ws.absolutePath, baseUrl = "http://127.0.0.1:1/v1"))
        val web = WebServer(PcSettings.load(), port = 0)
        val wp = web.start()
        try {
            // 空列表什么都验不出来：先经 HTTP 口建一条真会话，镜像里才有一行可看
            http.send(HttpRequest.newBuilder(URI("http://127.0.0.1:$wp/api/new"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build(),
                HttpResponse.BodyHandlers.ofString())
            val json = web.sessionsJson()
            assertFalse("不许漏绝对路径：" + json, json.contains(ws.absolutePath.replace('\\', '/')))
            assertFalse("不许漏反斜杠路径：" + json, json.contains(ws.absolutePath))
            assertFalse("不许漏密钥：" + json, json.contains("sk-"))
            assertTrue("镜像该有一行：" + json, json.contains("\"items\":[{"))
            assertTrue("只给目录名：" + json, json.contains(ws.name))
        } finally {
            runCatching { web.stop() }
        }
    }

    @Test
    fun `the live port is left on disk so the CLI can find the running instance`() {
        runCatching { server?.stop() }
        server = null
        PcSettings.save(PcSettings(baseUrl = "http://127.0.0.1:1/v1"))
        val marker = File(Env.home, "webport")
        val web = WebServer(PcSettings.load(), port = 0)
        val actual = web.start()
        try {
            assertTrue("该拿到真端口：$actual", actual > 0)
            // 写死 8712 的 CLI 在 --port 换端口 / 端口被占自动挪位之后会找不到服务，
            // 现象是"没有正在跑的服务"—— 而服务其实好好的。所以盘上必须记**真**端口。
            assertEquals("盘上记的必须是实际绑定成功的端口", actual.toString(), marker.readText().trim())
        } finally {
            runCatching { web.stop() }
        }
        assertFalse("停了就该擦掉，否则 CLI 会去敲一个没人听的端口", marker.exists())
    }
}
