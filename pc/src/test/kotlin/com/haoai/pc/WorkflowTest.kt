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
 * 任务链（工作流）的判据。
 *
 * 这一层唯一的主张是"几步跑在**同一条会话**里、按顺序、后一步看得见前一步"。
 * 所以判据不看界面说了什么，看**网关按什么顺序收到什么**：
 * 只要最后那次请求的 messages 里三句都在，就同时证明了"同一条会话"与"顺序"。
 */
class WorkflowTest {

    companion object {
        private lateinit var gateway: com.sun.net.httpserver.HttpServer
        private lateinit var bodies: MutableList<String>
        private lateinit var server: WebServer
        private lateinit var base: String
        private val http = HttpClient.newHttpClient()

        @BeforeClass
        @JvmStatic
        fun setUp() {
            val home = Files.createTempDirectory("haoai-wf-home").toFile()
            System.setProperty("haoai.home", File(home, "home").absolutePath)
            bodies = java.util.Collections.synchronizedList(mutableListOf<String>())
            gateway = com.sun.net.httpserver.HttpServer.create(
                java.net.InetSocketAddress("127.0.0.1", 0), 0
            )
            gateway.createContext("/v1/chat/completions") { ex ->
                val body = String(ex.requestBody.readBytes(), Charsets.UTF_8)
                bodies += body
                val b = ("data: " +
                    """{"choices":[{"index":0,"finish_reason":"stop","delta":{"content":"这一步做完了"}}]}""" +
                    "\n\ndata: [DONE]\n\n").toByteArray()
                ex.responseHeaders.add("Content-Type", "text/event-stream")
                ex.sendResponseHeaders(200, b.size.toLong())
                ex.responseBody.use { it.write(b) }
                ex.close()
            }
            gateway.start()
            PcSettings.save(PcSettings(
                workspace = File(home, "ws").apply { mkdirs() }.absolutePath,
                permissionMode = "auto", model = "链测试模型",
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

    private fun items(resp: String) =
        Json.parseToJsonElement(resp).jsonObject["items"]!!.jsonArray.toList()

    // ---- 存储与解析 ----

    @Test
    fun `steps come from lines and are capped`() {
        val steps = Workflows.parseSteps("  第一步  \n\n 第二步\n第三步 ")
        assertEquals(listOf("第一步", "第二步", "第三步"), steps)
        assertEquals("上限 " + Workflows.MAX_STEPS + " 步",
            Workflows.MAX_STEPS, Workflows.parseSteps((1..40).map { "第 $it 步" }.joinToString("\n")).size)
        assertTrue(Workflows.parseSteps("   \n\n").isEmpty())
    }

    @Test
    fun `a chain survives the round trip with quotes and newlines inside a step`() {
        val id = "wftest1"
        Workflows.update(Workflow(id, "剪片", listOf("先读 \"README\"", "再剪 30 秒")))
        val back = Workflows.find(id)!!
        assertEquals("再剪 30 秒", back.steps[1])
        assertEquals("先读 \"README\"", back.steps[0])
        val j = Json.parseToJsonElement(Workflows.json()).jsonObject
        val row = j["items"]!!.jsonArray.first { it.jsonObject["id"]!!.jsonPrimitive.content == id }.jsonObject
        assertEquals(2, row["count"]!!.jsonPrimitive.content.toInt())
        Workflows.remove(id)
        assertFalse(Workflows.load().any { it.id == id })
    }

    // ---- 真的按顺序跑在同一条会话里 ----

    @Test
    fun `running a chain sends every step into the same session in order`() {
        val created = items(post("/api/workflows",
            """{"name":"三步链","steps":"链甲第一步\n链乙第二步\n链丙第三步"}"""))
        val row = created.last().jsonObject
        val wfId = row["id"]!!.jsonPrimitive.content
        assertEquals(3, row["count"]!!.jsonPrimitive.content.toInt())

        bodies.clear()
        val run = post("/api/workflows", """{"op":"run","id":"$wfId"}""")
        assertTrue("跑起来该回 items：" + run.take(80), run.contains("\"ok\":true"))

        // 三步 = 至少三次模型调用；慢一步就等它，别用固定 sleep 猜
        val until = System.currentTimeMillis() + 40_000L
        while (bodies.size < 3 && System.currentTimeMillis() < until) Thread.sleep(200)
        assertTrue("网关只收到 ${bodies.size} 次，链没接力下去", bodies.size >= 3)

        val last = bodies.last()
        assertTrue("第三步该看得见第一步：" + last.take(200), last.contains("链甲第一步"))
        assertTrue("第三步该看得见第二步", last.contains("链乙第二步"))
        assertTrue("这一步自己也得在", last.contains("链丙第三步"))
        // 第二步、第三步的请求里都还带着第一步 —— 历史累积在同一条会话里，这正是"链"的证明
        assertEquals("每一步的请求都该带着第一步（同一段历史）", 3,
            bodies.count { it.contains("链甲第一步") })

        post("/api/workflows", """{"op":"del","id":"$wfId"}""")
    }

    @Test
    fun `an empty chain and a missing one both say so instead of starting nothing`() {
        val empty = post("/api/workflows", """{"name":"空链","steps":"   "}""")
        assertTrue("一行都没有就该拒绝：" + empty.take(90), empty.contains("至少写一行"))
        val miss = post("/api/workflows", """{"op":"run","id":"nope-not-here"}""")
        assertTrue("该回一句人话：" + miss, miss.contains("没有这条任务链"))
    }

    @Test
    fun `a scheduled task can run a chain instead of one sentence`() {
        val wf = items(post("/api/workflows", """{"name":"定时跑链","steps":"链丁第一步\n链戊第二步"}""")).last().jsonObject
        val wfId = wf["id"]!!.jsonPrimitive.content
        val sid = "schedflow1"
        Schedules.update(Schedule(id = sid, name = "到点跑链", prompt = "这句该被忽略",
            kind = "interval", every = 1, flow = wfId))   // every 的单位是分钟
        bodies.clear()
        // 把创建时刻往前挪两分钟，让 interval=60 的它现在就到期，然后走一轮 tick（不靠真实等待）
        val due = Schedules.load().first { it.id == sid }
        Schedules.update(due.copy(created = System.currentTimeMillis() - 120_000L))
        val fired = server.schedules()!!.tick(System.currentTimeMillis())
        val cur = Schedules.load().first { it.id == sid }
        assertTrue("tick 该触发一条（created=${cur.created} every=${cur.every} flow=${cur.flow} " +
            "nextDue=${Schedule.nextDue(cur, System.currentTimeMillis())}）实际 $fired", fired >= 1)
        val until = System.currentTimeMillis() + 40_000L
        while (bodies.size < 2 && System.currentTimeMillis() < until) Thread.sleep(200)
        assertTrue("链的两步都该到网关，实际 ${bodies.size} 次", bodies.size >= 2)
        assertTrue("跑的是链不是那句 prompt", bodies.last().contains("链丁第一步"))
        assertFalse("配了链就不该把 prompt 那句发出去", bodies.last().contains("这句该被忽略"))
        assertTrue("跑完要记账，否则每 5 秒再触发一次",
            (Schedules.load().first { it.id == sid }.lastRun) > 0L)
        Schedules.remove(sid)
        post("/api/workflows", """{"op":"del","id":"$wfId"}""")
    }

    @Test
    fun `the schedule json carries the flow so the UI can show what will run`() {
        Schedules.save(listOf(Schedule(id = "flowjson", name = "带链", prompt = "p", flow = "wf9")))
        val row = items(get("/api/schedules")).first {
            it.jsonObject["id"]!!.jsonPrimitive.content == "flowjson"
        }.jsonObject
        assertEquals("wf9", row["flow"]!!.jsonPrimitive.contentOrNull)
        Schedules.remove("flowjson")
    }
}
