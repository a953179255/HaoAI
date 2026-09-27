package com.haoai.pc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
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
 * 手动压缩（界面上那颗「现在压缩一次」与 `/compact`）。
 *
 * 自动压缩早就有了，但它只在**下一轮请求前**、且过了触发线才动手。长会话里人常有这个判断：
 * "这段调研没用了，先收一收再往下走" —— 不该逼他等到 6 万字，也不该为此重开一条会话。
 * 所以这里盯的是手动这条路的三条硬性质：绕得开触发线、不许把会话压坏、跑着的时候不许压。
 */
class ManualCompactTest {

    private class Plain : ChatClient {
        override fun chat(messages: List<Msg>, tools: List<ToolSchema>, onText: (String) -> Unit) =
            AssistantTurn("收到", emptyList(), Usage(3, 2), "stop")
    }

    private class AllowAll : Gate {
        override fun approve(title: String, detail: String, kind: String) = true
        override fun ask(question: String, options: List<String>) = options.firstOrNull() ?: ""
    }

    private fun dir(): File = Files.createTempDirectory("haoai-mcmp").toFile().apply { mkdirs() }

    /** 触发线拉到很高：这样"压了"只可能是人按的，不会和自动那条混在一起。 */
    private fun engine(id: String, at: File): Engine = Engine(
        Session(id, at), PcSettings(permissionMode = "auto", compactTriggerChars = 9_999_999,
            workspace = at.absolutePath),
        builtinTools(), AllowAll(), {}, Plain()
    )

    private fun grow(e: Engine, rounds: Int) {
        repeat(rounds) { e.submit("第 $it 句话，说得长一点好让正文真的占地方：" + "内容".repeat(200)) }
    }

    @Test
    fun `manual compact folds the head even when the trigger line is far away`() {
        val e = engine("m" + System.nanoTime(), dir())
        grow(e, 6)
        val before = e.messages().size
        // 量历史正文本身：摘要是有代价的，句子太短时"总上下文"甚至可能变大，
        // 那不该是这条测试的判据（自动压缩只在长会话上才有净收益）
        val bodyBefore = e.messages().sumOf { it.content?.length ?: 0 }
        val cut = e.compactNow()
        assertTrue("按了就该压（$before 条历史，触发线远在天边）", cut >= 4)
        assertEquals("历史要短掉正好那几条", before - cut, e.messages().size)
        val bodyAfter = e.messages().sumOf { it.content?.length ?: 0 }
        assertTrue("历史正文要真的降下来（$bodyBefore → $bodyAfter）", bodyAfter < bodyBefore)
        // 压完发给模型的窗口里不许有"孤儿 tool 回复"——这条不变量由同一套切点规则保证
        val roles = e.messages().map { it.role }
        assertFalse("不许出现没有对应调用的 tool 回复",
            roles.contains("tool") && !roles.contains("assistant"))
    }

    @Test
    fun `a too-short history is left alone instead of being eaten`() {
        val e = engine("s" + System.nanoTime(), dir())
        grow(e, 1)
        val before = e.messages().size
        assertEquals("两条以内压了反而更啰嗦，要原样返回 0", 0, e.compactNow())
        assertEquals(before, e.messages().size)
    }

    /** 手动压缩不在回合里，没人替它落盘：不写一次，刷新就回到压缩前的样子。 */
    @Test
    fun `manual compact writes itself to disk so a refresh keeps the summary`() {
        val id = "p" + System.nanoTime()
        val at = dir()
        val e = engine(id, at)
        grow(e, 6)
        e.compactNow()
        val f = File(Env.sessionsDir, "pc-$id.json")
        assertTrue("会话文件要在：" + f.absolutePath, f.isFile)
        val o = Json.parseToJsonElement(f.readText()).jsonObject
        val summary = o["summary"]?.jsonPrimitive?.contentOrNull.orEmpty()
        assertTrue("摘要要落盘", summary.isNotBlank())
        assertTrue("水位要落盘",
            (o["compactedThrough"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0) >= 4)
    }

    companion object {
        private lateinit var server: WebServer
        private lateinit var base: String
        private lateinit var gateway: com.sun.net.httpserver.HttpServer
        private val http = HttpClient.newHttpClient()

        @BeforeClass
        @JvmStatic
        fun setUp() {
            val home = Files.createTempDirectory("haoai-mcmp-home").toFile()
            System.setProperty("haoai.home", File(home, "home").absolutePath)
            gateway = com.sun.net.httpserver.HttpServer.create(
                java.net.InetSocketAddress("127.0.0.1", 0), 0
            )
            // 慢网关：存在的唯一理由是让"这条正在跑"这个分支真的被测到
            gateway.createContext("/v1/chat/completions") { ex ->
                Thread.sleep(4_000)
                val b = ("data: " +
                    """{"choices":[{"index":0,"finish_reason":"stop","delta":{"content":"好了"}}]}""" +
                    "\n\ndata: [DONE]\n\n").toByteArray()
                ex.responseHeaders.add("Content-Type", "text/event-stream")
                ex.sendResponseHeaders(200, b.size.toLong())
                ex.responseBody.use { it.write(b) }
                ex.close()
            }
            gateway.start()
            PcSettings.save(PcSettings(
                workspace = File(home, "ws").apply { mkdirs() }.absolutePath,
                permissionMode = "auto",
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

    @Test
    fun `the endpoint refuses an unknown session`() {
        val d = Json.parseToJsonElement(post("/api/compact", """{"sid":"nope"}""")).jsonObject
        assertFalse(d["ok"]!!.jsonPrimitive.content.toBoolean())
    }

    /** 跑着的时候压：切点会和回合正在写的那段历史打架，所以必须挡下来并把理由说清。 */
    @Test
    fun `the endpoint refuses to compact a session that is running`() {
        val sid = Json.parseToJsonElement(post("/api/new", "{}")).jsonObject["id"]!!.jsonPrimitive.content
        post("/api/task", """{"text":"慢慢说，别急着收尾","sid":"$sid"}""")
        Thread.sleep(600)                          // 网关故意睡 4 秒，这个窗口里它一定还在跑
        val d = Json.parseToJsonElement(post("/api/compact", """{"sid":"$sid"}""")).jsonObject
        assertFalse("正在跑的那条不许压", d["ok"]!!.jsonPrimitive.content.toBoolean())
        assertTrue("要说清为什么", (d["error"]?.jsonPrimitive?.contentOrNull ?: "").contains("正在跑"))
        post("/api/stop", """{"sid":"$sid"}""")    // 收尾，别把线程留给后面的测试
    }
}
