package com.haoai.pc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
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
        var sent: Pair<String, String>? = null
        override fun digestJson() = """{"ok":true,"items":[],"count":0}"""
        override fun lanSend(sid: String, text: String): String {
            sent = sid to text
            return "已交给电脑：会话 " + (sid.ifBlank { "s9" })
        }

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
        // 路由清单从源码里取，不再手抄一份：手抄那份每次加接口都要记得改，
        // 而"忘了改"的表现是这条测试红掉 —— 红两次之后人就会把测试删了。
        val src = File("src/main/kotlin/com/haoai/pc/Lan.kt")
        assertTrue("找不到 Lan.kt（挪了源码位置就该同步改这里）：" + src.absolutePath, src.isFile)
        val served = Regex("\"/lan/[a-z]+\"").findAll(src.readText())
            .map { it.value.trim('"') }.toSet()
        assertTrue("源码里一个路由都没抓到，这条测试就白跑了：" + served, served.size >= 6)
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
        val (st2, _) = post("/lan/send", """{"sid":"","text":"派个活"}""")
        assertEquals("派活这条是「让电脑动手」，没 token 一律 401", 401, st2)
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
    fun `the digest is read-only and needs a token`() {
        val (st, _) = get("/lan/digest")
        assertEquals("没配对读不到结果汇总", 401, st)
        val (st2, body) = get("/lan/digest", paired())
        assertEquals(200, st2)
        assertTrue("该回一份 items：" + body, body.contains(""","items":"""))
    }

    // ---- 从手机派活（默认关，且它是"让这台电脑动手"的那条口）----

    @Test
    fun `sending from the phone is off until the PC ticks it`() {
        val tk = paired()
        val (st, body) = post("/lan/send", """{"sid":"","text":"把录屏剪成 30 秒"}""", tk)
        assertEquals(200, st)
        assertTrue("没勾开关就该挡住：" + body, body.contains("允许从手机派活"))
        assertNull("挡住了就不该走到执行侧", host.sent)
    }

    @Test
    fun `an unpaired device cannot send even after the switch is on`() {
        LanStore.saveAllowSend(true)
        val (st, _) = post("/lan/send", """{"sid":"","text":"随便一句"}""", "")
        assertEquals("没 token 一律 401", 401, st)
    }

    @Test
    fun `the phone sentence arrives intact with quotes and newlines`() {
        val tk = paired()
        LanStore.saveAllowSend(true)
        val body = """{"sid":"","text":"先读 \"README\"\n再列一下文件"}"""
        val (st, txt) = post("/lan/send", body, tk)
        assertEquals(200, st)
        assertTrue("该回一句人话：" + txt, txt.contains("已交给电脑"))
        assertEquals("正文里的引号与换行不许被吃掉", "先读 \"README\"\n再列一下文件", host.sent?.second)
        assertEquals("空 sid = 另起一条", "", host.sent?.first)

        // `\\n` 是"转义过的反斜杠 + n"，不是换行：手机发来的原文里贴 Windows 路径是常态，
        // 用两次 replace 反转义会把它折成换行，路径当场断成两截。
        post("/lan/send", """{"sid":"","text":"C:\\nx"}""", tk)
        assertEquals("反斜杠后面那个 n 不许变成换行", "C:\\nx", host.sent?.second)
    }

    @Test
    fun `an empty or absurdly long sentence is refused in plain words`() {
        val tk = paired()
        LanStore.saveAllowSend(true)
        val (_, e1) = post("/lan/send", """{"sid":"","text":"   "}""", tk)
        assertTrue("空正文：" + e1, e1.contains("要 text"))
        val (_, e2) = post("/lan/send", "{\"sid\":\"\",\"text\":\"" + "字".repeat(2100) + "\"}", tk)
        assertTrue("超长该说上限：" + e2, e2.contains("上限") && e2.contains("2000"))
        assertNull("两条都不该走到执行侧", host.sent)
    }

    @Test
    fun `the allow-send switch survives the endpoint being toggled`() {
        LanStore.saveAllowSend(true)
        LanStore.save(true, 8720)
        assertTrue("开关跟着 lan.json 走，不该被开/关端点冲掉", LanStore.allowSend())
        LanStore.save(false, 8720)
        assertTrue(LanStore.allowSend())
        LanStore.saveAllowSend(false)
        assertFalse(LanStore.allowSend())
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
