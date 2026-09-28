package com.haoai.pc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
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
 * 角色卡（预设 agent）的判据。
 *
 * 这一层的主张很具体：**用角色卡开一条会话，那条会话就得是卡上写的那样** ——
 * 模型、工作区、档位、人设四样一起带上，少一样都是"看着选了其实没用上"。
 * 所以除了存储 round-trip，还验到网关收到的那次请求里到底有没有这段人设：
 * 界面写着"直播助手"而模型读不到它，是最难被发现的一类错。
 */
class PresetTest {

    companion object {
        private lateinit var gateway: com.sun.net.httpserver.HttpServer
        private lateinit var bodies: MutableList<String>
        private lateinit var server: WebServer
        private lateinit var base: String
        private lateinit var wsRole: File
        private val http = HttpClient.newHttpClient()

        @BeforeClass
        @JvmStatic
        fun setUp() {
            val home = Files.createTempDirectory("haoai-preset-home").toFile()
            System.setProperty("haoai.home", File(home, "home").absolutePath)
            wsRole = File(home, "role-ws").apply { mkdirs() }
            bodies = java.util.Collections.synchronizedList(mutableListOf<String>())
            gateway = com.sun.net.httpserver.HttpServer.create(
                java.net.InetSocketAddress("127.0.0.1", 0), 0
            )
            gateway.createContext("/v1/chat/completions") { ex ->
                bodies += String(ex.requestBody.readBytes(), Charsets.UTF_8)
                val b = ("data: " +
                    """{"choices":[{"index":0,"finish_reason":"stop","delta":{"content":"这个角色做完了"}}]}""" +
                    "\n\ndata: [DONE]\n\n").toByteArray()
                ex.responseHeaders.add("Content-Type", "text/event-stream")
                ex.sendResponseHeaders(200, b.size.toLong())
                ex.responseBody.use { it.write(b) }
                ex.close()
            }
            gateway.start()
            PcSettings.save(PcSettings(
                workspace = File(home, "ws").apply { mkdirs() }.absolutePath,
                permissionMode = "ask", model = "全局默认模型",
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

    private fun items(resp: String) =
        Json.parseToJsonElement(resp).jsonObject["items"]!!.jsonArray.toList()

    /** Windows 路径里有反斜杠，塞进 JSON 之前必须转义（`Server.quote` 是私有的）。 */
    private fun jq(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    // ---- 存储 ----

    @Test
    fun `a nasty persona survives the round-trip`() {
        val nasty = "第一行有\"引号\"和 \\ 反斜杠\n第二行是中文：剪片子、发弹幕\n第三行结尾带空格   "
        val id = "pr-roundtrip"
        Presets.update(Preset(id, "回读测试", nasty, "m", "", "auto"))
        val back = Presets.find(id)
        assertEquals("人设原样读回来（含引号、反斜杠、换行、行尾空格）", nasty, back?.persona)
        assertEquals("auto", back?.mode)
        assertTrue("json 里存的必须还是合法 json",
            Json.parseToJsonElement(Presets.file().readText()).jsonArray.size > 0)
        Presets.remove(id)
    }

    @Test
    fun `a corrupt store reads as empty and stays writable`() {
        val f = Presets.file()
        f.parentFile?.mkdirs()
        val keep = Presets.load().toList()
        f.writeText("""[{"id":"broken","name":"缺个括号" """)
        assertTrue("坏文件要读成空，不能抛：" + Presets.load(), Presets.load().isEmpty())
        Presets.update(Preset("pr-after-corrupt", "覆盖写回", "人设", "", "", ""))
        assertEquals("坏文件要能被一次正常的保存覆盖掉", 1, Presets.load().size)
        Presets.save(keep)
    }

    @Test
    fun `the cap holds and an unknown mode falls back`() {
        val start = Presets.load().size
        (start until Presets.MAX).forEach { Presets.update(Preset("pr-cap$it", "卡$it", "", "", "", "")) }
        assertEquals("存到上限就该停", Presets.MAX, Presets.load().size)
        Presets.update(Preset("pr-cap-overflow", "多出来的那张", "", "", "", ""))
        assertEquals("第 ${Presets.MAX + 1} 张不许挤进来", Presets.MAX, Presets.load().size)
        val bogus = Preset("pr-bogus-mode", "档位不对", "", "", "", "yolo")
        assertEquals("非法档位回落到全局默认，不当成 ask 也不当成 auto",
            "ask", Presets.modeOf(bogus.copy(mode = ""), "ask"))
        assertEquals("合法档位照用", "plan", Presets.modeOf(bogus.copy(mode = "plan"), "ask"))
        (0 until Presets.MAX).forEach { Presets.remove("pr-cap$it") }
        Presets.remove("pr-after-corrupt")
    }

    // ---- 用它开一条会话 ----

    @Test
    fun `starting from a preset carries model workspace and mode`() {
        val saved = items(post("/api/presets",
            """{"name":"直播助手","persona":"你在直播时口述，要短要快",""" +
                """"model":"角色专属模型","workspace":${jq(wsRole.absolutePath)},"mode":"auto"}""")).last()
        val id = saved.jsonObject["id"]!!.jsonPrimitive.content
        val created = post("/api/new", """{"preset":"$id"}""")
        assertTrue("建会话要回 ok：" + created.take(90), created.contains("\"ok\":true"))
        val sid = field(created, "id")
        assertEquals("顶栏该立刻显示角色名", "直播助手", field(created, "role"))

        val st = get("/api/state?sid=$sid")
        assertEquals("模型要换成卡上那个", "角色专属模型", field(st, "model"))
        assertEquals("档位要换成卡上那个", "auto", field(st, "mode"))
        assertEquals("工作区要换成卡上那个",
            wsRole.canonicalFile.absolutePath.replace('\\', '/'),
            field(st, "workspace").replace('\\', '/'))
        assertEquals("角色名要跟着这条会话报出去", "直播助手", field(st, "role"))
        post("/api/presets", """{"op":"del","id":"$id"}""")
    }

    @Test
    fun `the persona actually reaches the model`() {
        val marker = "本次角色判据标记：只用三句话回答"
        val saved = items(post("/api/presets",
            """{"name":"标记角色","persona":"$marker","mode":"auto"}""")).last()
        val id = saved.jsonObject["id"]!!.jsonPrimitive.content
        val sid = field(post("/api/new", """{"preset":"$id"}"""), "id")
        bodies.clear()
        post("/api/task", """{"sid":"$sid","text":"说一句你好"}""")
        val until = System.currentTimeMillis() + 30_000L
        while (bodies.isEmpty() && System.currentTimeMillis() < until) Thread.sleep(150)
        assertTrue("网关一次都没收到请求，角色卡没接上引擎", bodies.isNotEmpty())
        val req = bodies.first()
        assertTrue("系统提示里该有「本次角色」这一节", req.contains("本次角色"))
        assertTrue("人设原文要发出去，不是只在界面上显示：" + req.take(200), req.contains(marker))
        post("/api/presets", """{"op":"del","id":"$id"}""")
    }

    @Test
    fun `a preset whose workspace is gone says so instead of falling back`() {
        // 存的时候目录还在、用的时候没了（改名/挪盘/插拔移动硬盘）：这条只能绕过界面直接写库来造。
        val dir = Files.createTempDirectory("haoai-preset-was").toFile()
        val gone = File(dir, "sub").apply { mkdirs() }
        Presets.update(Preset("pr-gone", "目录已消失", "人设", "", gone.absolutePath, ""))
        gone.delete()
        val r = post("/api/new", """{"preset":"pr-gone"}""")
        assertTrue("目录没了要拒绝：" + r.take(90), r.contains("打不开"))
        assertFalse("不能悄悄建在别处（那等于在人没选的仓库里写文件）", r.contains("\"ok\":true"))
        // 现在就打不开的目录，存的时候也要拦一次
        val bad = post("/api/presets", """{"name":"存不进去","persona":"y","workspace":"Z:\\没有这个盘"}""")
        assertTrue("存的时候就该拒绝：" + bad.take(90), bad.contains("打不开"))
        Presets.remove("pr-gone")
        dir.deleteRecursively()
    }

    @Test
    fun `a missing preset id is a sentence not a crash`() {
        val r = post("/api/new", """{"preset":"pr-not-here"}""")
        assertTrue("要说清没有这个角色卡：" + r.take(90), r.contains("没有这个角色卡"))
    }
}
