package com.haoai.pc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertNotNull
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
 * 子任务要能**单独**停，而且总闸按下去时它得一起停。
 *
 * 两条判据都只能靠"多快收的尾"来验：子任务每轮在网关那里睡 1.2 秒，
 * 停对了 1~2 秒就结束，停不对要跑满 [CHILD_ROUNDS] 轮（7 秒以上）。
 * 看返回值 200 是没用的 —— 接口一直都会回 200。
 */
class SubStopTest {

    companion object {
        private const val MARK = "子任务现场"
        private const val CHILD_ROUNDS = 6

        private lateinit var gateway: com.sun.net.httpserver.HttpServer
        private lateinit var server: WebServer
        private lateinit var base: String
        private val http = HttpClient.newHttpClient()
        private val hits = java.util.concurrent.atomic.AtomicInteger(0)

        @BeforeClass
        @JvmStatic
        fun setUp() {
            val home = Files.createTempDirectory("haoai-substop-home").toFile()
            System.setProperty("haoai.home", File(home, "home").absolutePath)
            gateway = com.sun.net.httpserver.HttpServer.create(
                java.net.InetSocketAddress("127.0.0.1", 0), 0
            )
            gateway.createContext("/v1/chat/completions") { ex ->
                val body = String(ex.requestBody.readBytes(), Charsets.UTF_8)
                val rounds = Regex("\"role\"\\s*:\\s*\"tool\"").findAll(body).count()
                /*
                 * "这是子任务在问还是父任务在问"只能看 user 消息的正文。
                 *
                 * 先写成 `body.contains(MARK)` 时，子任务明明停住了、父任务却一路收到
                 * shell 调用跑到第 6 轮 —— 因为父任务历史里那条 assistant 的 tool_call
                 * 参数就带着这个标记（它就是照那句话派的活）。假网关认错了人，
                 * 症状却长得像"停不掉子任务"。（同一族坑：判据读的东西先确认它读的是谁。）
                 */
                val msgs = Json.parseToJsonElement(body).jsonObject["messages"]!!.jsonArray
                val child = msgs.any {
                    val o = it.jsonObject
                    o["role"]?.jsonPrimitive?.content == "user" &&
                        (o["content"]?.jsonPrimitive?.contentOrNull ?: "").contains(MARK)
                }
                hits.incrementAndGet()
                val sse = when {
                    child && rounds >= CHILD_ROUNDS -> textSse("数完了：一共数了 $CHILD_ROUNDS 轮。")
                    child -> {
                        Thread.sleep(1_200)   // 每轮慢一点，界面上才来得及点"停掉它"
                        toolSse("shell", """{"command":"echo round-$rounds","shell":"bash","timeout":30}""")
                    }
                    rounds > 0 -> textSse("子任务回来了，我照它的结论收尾。")
                    else -> toolSse("task",
                        """{"prompt":"$MARK：一轮一轮数，数到六再说","label":"数数"}""")
                }
                ex.responseHeaders.add("Content-Type", "text/event-stream")
                ex.sendResponseHeaders(200, sse.size.toLong())
                ex.responseBody.use { it.write(sse) }
                ex.close()
            }
            gateway.executor = java.util.concurrent.Executors.newCachedThreadPool()
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

        /**
         * 帧用 kotlinx 自己拼，不手搓字符串。
         *
         * `tool_calls` 必须嵌一层 `function`，而 arguments 本身又是 JSON ——
         * 手写转义错过一次，症状是"引擎永远等不到第二轮请求"，看着像产品坏了。
         */
        private fun toolSse(name: String, args: String): ByteArray {
            val frame = buildJsonObject {
                put("choices", buildJsonArray {
                    add(buildJsonObject {
                        put("index", 0)
                        put("delta", buildJsonObject {
                            putJsonArray("tool_calls") {
                                add(buildJsonObject {
                                    put("id", "call_" + System.nanoTime())
                                    put("type", "function")
                                    put("index", 0)
                                    put("function", buildJsonObject {
                                        put("name", name); put("arguments", args)
                                    })
                                })
                            }
                        })
                    })
                })
            }.toString()
            return ("data: $frame\n\n" +
                """data: {"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}""" +
                "\n\ndata: [DONE]\n\n").toByteArray()
        }

        private fun textSse(t: String): ByteArray {
            val frame = buildJsonObject {
                put("choices", buildJsonArray {
                    add(buildJsonObject {
                        put("index", 0)
                        put("delta", buildJsonObject { put("content", t) })
                        put("finish_reason", "stop")
                    })
                })
            }.toString()
            return ("data: $frame\n\n" + "\ndata: [DONE]\n\n").toByteArray()
        }
    }

    private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject

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

    private fun newSession(tag: String): String {
        val dir = File(Env.home.parentFile, "sws-" + tag).apply { mkdirs() }
        val r = obj(post("/api/new", "{\"ws\":\"" + dir.absolutePath.replace("\\", "\\\\") + "\"}"))
        return r["id"]!!.jsonPrimitive.content
    }

    private fun state(sid: String) = obj(get("/api/state?sid=$sid"))

    private fun subsOf(sid: String): List<String> =
        (state(sid)["subs"]?.jsonArray ?: emptyList()).mapNotNull { it.jsonPrimitive.contentOrNull }

    private fun waitSubs(sid: String, ms: Long = 15_000): List<String> {
        val until = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < until) {
            val s = subsOf(sid)
            if (s.isNotEmpty()) return s
            Thread.sleep(150)
        }
        return emptyList()
    }

    private fun awaitIdle(sid: String, ms: Long): Boolean {
        val until = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < until) {
            if (state(sid)["running"]!!.jsonPrimitive.content.toBoolean().not()) return true
            Thread.sleep(150)
        }
        return false
    }

    @Test
    fun `state reports which subtasks are running`() {
        val sid = newSession("s1")
        val subs = post("/api/task", """{"sid":"$sid","text":"派个子任务去数数"}""")
        assertTrue("task 没受理：" + subs.take(120), obj(subs)["ok"]?.jsonPrimitive?.content == "true")
        val live = waitSubs(sid)
        assertTrue("子任务在跑，/api/state 的 subs 却是空的（刷新后就再也停不掉它）", live.isNotEmpty())
        assertTrue("标签该是模型给的那个：" + live, live[0].startsWith("数数"))
        awaitIdle(sid, 30_000)
        assertTrue("跑完就该没有在跑的子任务：" + subsOf(sid), subsOf(sid).isEmpty())
    }

    @Test
    fun `stopping one subtask lets the parent finish without killing the turn`() {
        val sid = newSession("s2")
        post("/api/task", """{"sid":"$sid","text":"派个子任务去数数"}""")
        val live = waitSubs(sid)
        assertTrue("等不到在跑的子任务", live.isNotEmpty())
        val atStop = hits.get()
        val r = obj(post("/api/substop", """{"sid":"$sid","label":"${live[0]}"}"""))
        assertEquals("停一条子任务该回 ok:true，实际 " + r, "true", r["ok"]?.jsonPrimitive?.content)
        assertTrue("该收得住尾", awaitIdle(sid, 30_000))
        /*
         * 判据是"停下来之后还问了模型几次"，不是"几秒内结束" ——
         * 时间这条在慢机器上会自己红（第一次跑就是这么红的：4.5s 卡得太死，
         * 而 shell 每次都要起一个 bash）。轮数才是这件事的真正语义：停对了就不会跑满 $CHILD_ROUNDS 轮。
         */
        val after = hits.get() - atStop
        assertTrue("停掉之后不该继续把 $CHILD_ROUNDS 轮数完，实际又问了 $after 次", after <= 3)
        val hist = (state(sid)["messages"]?.jsonArray ?: emptyList()).map { it.jsonObject }
        val note = hist.filter { it["role"]?.jsonPrimitive?.content == "tool" &&
            it["name"]?.jsonPrimitive?.content == "task" }
            .map { it["content"]?.jsonPrimitive?.content ?: "" }
        assertTrue("父任务要收到「被中断」这句，而不是「没有结论」：" + note,
            note.any { it.contains("子任务被中断") })
        // 停一条子任务不该把这一轮整个中止：父任务还要能自己收尾
        assertTrue("父任务没接着收尾：" + hist.map { it["role"]?.jsonPrimitive?.content },
            hist.any { it["role"]?.jsonPrimitive?.content == "assistant" &&
                (it["content"]?.jsonPrimitive?.content ?: "").contains("照它的结论收尾") })
    }

    @Test
    fun `the master stop also stops the running subtask`() {
        val sid = newSession("s3")
        post("/api/task", """{"sid":"$sid","text":"派个子任务去数数"}""")
        assertTrue("等不到在跑的子任务", waitSubs(sid).isNotEmpty())
        val atStop = hits.get()
        post("/api/stop", """{"sid":"$sid"}""")
        assertTrue("按了总闸，那条子任务还在自己转圈", awaitIdle(sid, 25_000))
        val after = hits.get() - atStop
        assertTrue("总闸按下去之后还问了模型 $after 次 —— 子任务没跟着停（不该跑满 $CHILD_ROUNDS 轮）",
            after <= 3)
        assertTrue("停止之后不该留下在跑的子任务：" + subsOf(sid), subsOf(sid).isEmpty())
    }

    @Test
    fun `stopping a subtask that already finished says so`() {
        val sid = newSession("s4")
        val r = obj(post("/api/substop", """{"sid":"$sid","label":"根本没这条"}"""))
        assertEquals("不存在的子任务不能回 ok:true", "false", r["ok"]?.jsonPrimitive?.content)
        assertTrue("得说清为什么：" + r,
            (r["error"]?.jsonPrimitive?.content ?: "").contains("不在跑"))
    }
}
