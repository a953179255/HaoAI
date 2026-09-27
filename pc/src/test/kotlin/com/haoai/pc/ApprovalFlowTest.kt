package com.haoai.pc

import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.net.URI
import java.net.InetSocketAddress
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * 整条用户链路的全栈测试：真 HTTP 服务 + 真 SSE + 真引擎 + 真网关（假的，但是真协议）。
 *
 * 为什么非要有这份：`/api/decide` 的"允许一次"曾经**一直是拒绝**，而 75 条单测全绿 ——
 * 因为它们都是直接调引擎或存储，没有一条从网页那个口子进去。
 * 中间那层（请求体只能读一次、字段名两边要对得上、SSE 事件名要对得上、
 * 审批要真的能把写放行）完全是靠这份测试才有人看着。
 *
 * 判据也按用例声明：允许 ⇒ 文件出现且内容对；拒绝 ⇒ 文件不出现。
 * 不看返回值 200 就算过 —— 上一版的 200 后面跟着的就是拒绝。
 *
 * 多会话并行之后，"按到达顺序吐下一段帧"的假网关不够用了：两条会话同时在问模型，
 * 谁先问到不确定，帧就会串台（表现是 B 的回答带着 A 的工具调用）。
 * 所以网关改成**按请求里出现的标记**选剧本，见 [Canned]。
 */
class ApprovalFlowTest {

    companion object {
        private lateinit var gateway: HttpServer
        private lateinit var web: WebServer
        private lateinit var base: String
        private lateinit var ws: File
        private val http = HttpClient.newHttpClient()

        /** 一条会话的剧本：请求体里出现 marker 就打这一段，marker 是从用户那句话里来的。 */
        private class Canned(val marker: String, val turns: List<List<ByteArray>>) {
            val idx = AtomicInteger(0)
            fun next(): List<ByteArray> = turns[minOf(idx.getAndIncrement(), turns.size - 1)]
        }

        private val canned = Collections.synchronizedList(mutableListOf<Canned>())

        /** 兜底剧本（没匹配到标记时用）：每个请求依次吐下一段，用完重复最后一段。 */
        private val script = Collections.synchronizedList(mutableListOf<List<ByteArray>>())
        private val which = AtomicInteger(0)

        data class Evt(val ev: String, val data: String, val sid: String)

        private val events = ConcurrentLinkedQueue<Evt>()

        /** 谁都没匹配上时的兜底回答：一句就收尾，免得测试挂在那里等 15 秒超时。 */
        private val DEFAULT_TURN: List<ByteArray> = listOf(
            frame("""{"choices":[{"index":0,"finish_reason":null,"delta":{"role":"assistant","content":"(没匹配到剧本)"}}]}"""),
            frame("""{"choices":[{"index":0,"finish_reason":"stop","delta":{}}]}"""),
            frame("[DONE]")
        )

        private fun frame(s: String) = ("data: $s\n\n").toByteArray(Charsets.UTF_8)

        /** 一个带 tool_call 的回合。用 JSON 构造器拼，不手工转义 —— arguments 本身就是 JSON 字符串。 */
        private fun toolCallTurn(id: String, name: String, args: String): List<ByteArray> {
            val head = buildJsonObject {
                put("choices", buildJsonArray {
                    add(buildJsonObject {
                        put("index", 0)
                        put("delta", buildJsonObject {
                            put("role", "assistant")
                            put("content", "我先动手")
                            put("tool_calls", buildJsonArray {
                                add(buildJsonObject {
                                    put("index", 0); put("id", id); put("type", "function")
                                    put("function", buildJsonObject { put("name", name); put("arguments", args) })
                                })
                            })
                        })
                    })
                })
            }
            return listOf(
                frame(head.toString()),
                frame("""{"choices":[{"index":0,"finish_reason":"tool_calls","delta":{}}]}"""),
                frame("""{"usage":{"prompt_tokens":10,"completion_tokens":5}}"""),
                frame("[DONE]")
            )
        }

        private fun textTurn(text: String): List<ByteArray> = listOf(
            frame("""{"choices":[{"index":0,"finish_reason":null,"delta":{"role":"assistant","content":"$text"}}]}"""),
            frame("""{"choices":[{"index":0,"finish_reason":"stop","delta":{}}]}"""),
            frame("[DONE]")
        )

        private fun get(path: String): String = http.send(
            HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
            HttpResponse.BodyHandlers.ofString()
        ).body()

        private fun post(path: String, obj: Map<String, String>): Pair<Int, String> {
            val body = buildJsonObject { obj.forEach { (k, v) -> put(k, v) } }.toString()
            val r = http.send(
                HttpRequest.newBuilder(URI.create(base + path))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString()
            )
            return r.statusCode() to r.body()
        }

        private fun jsonOf(s: String) = Json.parseToJsonElement(s).jsonObject

        /** 某条会话现在是否在跑。必须带 sid：服务级的 running 在多会话下没有意义。 */
        private fun isRunning(sid: String): Boolean =
            jsonOf(get("/api/state?sid=$sid"))["running"]?.jsonPrimitive?.content == "true"

        private fun awaitEvent(name: String, timeoutMs: Long = 15_000): String? =
            awaitMatch({ it.ev == name }, timeoutMs)

        /** 只认"属于这条会话"的事件 —— 并行测试的判据核心：事件串台必须当场失败。 */
        private fun awaitEventFrom(name: String, sid: String, timeoutMs: Long = 15_000): String? =
            awaitMatch({ it.ev == name && it.sid == sid }, timeoutMs)

        private fun awaitMatch(p: (Evt) -> Boolean, timeoutMs: Long = 15_000): String? {
            val until = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < until) {
                events.firstOrNull(p)?.let { events.remove(it); return it.data }
                Thread.sleep(40)
            }
            return null
        }

        private fun awaitIdle(sid: String, timeoutMs: Long = 15_000): Boolean {
            val until = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < until) {
                if (!isRunning(sid)) return true
                Thread.sleep(120)
            }
            return !isRunning(sid)
        }

        @BeforeClass
        @JvmStatic
        fun setUp() {
            val home = Files.createTempDirectory("haoai-flow-home").toFile()
            System.setProperty("haoai.home", File(home, "home").absolutePath)
            ws = File(home, "ws").apply { mkdirs() }

            gateway = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            gateway.createContext("/v1/chat/completions") { ex ->
                val body = runCatching {
                    ex.requestBody.readBytes().toString(Charsets.UTF_8)
                }.getOrDefault("")
                val frames = canned.firstOrNull { body.contains(it.marker) }?.next()
                    ?: script.getOrNull(minOf(which.getAndIncrement(), maxOf(script.size - 1, 0)))
                    ?: DEFAULT_TURN
                ex.responseHeaders.add("Content-Type", "text/event-stream")
                ex.sendResponseHeaders(200, 0)
                ex.responseBody.use { os -> frames.forEach { os.write(it); os.flush() } }
                ex.close()
            }
            gateway.start()

            val st = PcSettings(
                baseUrl = "http://127.0.0.1:${gateway.address.port}/v1",
                model = "flowtest", permissionMode = "ask", workspace = ws.absolutePath
            )
            web = WebServer(st, 0)
            base = "http://127.0.0.1:${web.start()}"

            Thread {
                runCatching {
                    val conn = http.send(
                        HttpRequest.newBuilder(URI.create("$base/api/events")).GET().build(),
                        HttpResponse.BodyHandlers.ofLines()
                    )
                    var pendingEvent = ""
                    var lastId = ""
                    conn.body().forEach { line ->
                        when {
                            // 会话 id 走 SSE 的 id: 字段（浏览器以 lastEventId 暴露），这里照同样规则解析
                            line.startsWith("id:") -> lastId = line.substring(3).trim()
                            line.startsWith("event:") -> pendingEvent = line.substring(6).trim()
                            line.startsWith("data:") && pendingEvent.isNotEmpty() -> {
                                events.add(Evt(pendingEvent, line.substring(5).trim(), lastId))
                                pendingEvent = ""
                            }
                        }
                    }
                }
            }.apply { isDaemon = true; start() }
            Thread.sleep(500)
        }

        @AfterClass
        @JvmStatic
        fun tearDown() {
            runCatching { web.stop() }
            runCatching { gateway.stop(0) }
        }
    }

    /**
     * 开一条新会话并提交任务。
     *
     * 每条测试都用**自己的 sid**：不再依赖"整个服务只有一条会话"这个前提，
     * 上一条测试留在后台的任务也就污染不到这一条的判据。
     *
     * 注意服务端会复用"还一条没说过"的当前会话（免得一路点新任务留下一堆空文件），
     * 所以这里不假设每次都拿到新 id —— 只假设拿到的那条是干净的。
     */
    private fun runTask(prompt: String, turns: List<List<ByteArray>>): String {
        canned.clear(); script.clear(); which.set(0); events.clear()
        val (newCode, newBody) = post("/api/new", emptyMap())
        assertEquals("开不出一条干净会话：$newCode $newBody", 200, newCode)
        val sid = jsonOf(newBody)["id"]?.jsonPrimitive?.content
        assertNotNull("响应里没有会话 id：$newBody", sid)
        // 标记就是这次任务的第一句话：假网关靠它在请求体里认出"这是哪条会话在问"
        canned.add(Canned(prompt, turns))
        val (code, body) = post("/api/task", mapOf("text" to prompt, "sid" to sid!!))
        assertEquals("提交任务失败：$code $body", 200, code)
        return sid
    }

    @Test
    fun `allow_once really lets the write through`() {
        val prompt = "写一个 approved.txt"
        val sid = runTask(prompt, listOf(
            toolCallTurn("call_w", "write", """{"path":"approved.txt","content":"approved by user"}"""),
            textTurn("写完了")
        ))

        val raw = awaitEventFrom("approval", sid)
        assertNotNull("没收到审批事件（SSE 或闸口断了）。已收到：$events", raw)
        val d = jsonOf(raw!!)
        assertEquals("write", d["tool"]?.jsonPrimitive?.content)
        assertTrue("审批卡上没说要写哪个文件：$raw", d["title"]?.jsonPrimitive?.content!!.contains("approved.txt"))

        // 刷新页面（=重读 /api/state）时必须还能看到这条待决审批：
        // 它只通过 SSE 推过一次，找不到就等于引擎在无人知晓地卡 300 秒。
        val pend = jsonOf(get("/api/state?sid=$sid"))["pending"]?.jsonArray
        assertNotNull("待决审批没进 /api/state（刷新页面就丢了）", pend)
        assertTrue("state.pending 里不是这条：$pend",
            pend!!.any {
                val o = Json.parseToJsonElement(it.toString()).jsonObject
                o["ev"]?.jsonPrimitive?.content == "approval" &&
                    o["data"]?.jsonObject?.get("id")?.jsonPrimitive?.content == d["id"]!!.jsonPrimitive.content
            })
        // 别的会话不该看到这条待决：pending 必须按 sid 过滤，否则刷新后会给一条
        // 完全无关的会话弹一个不属于它的确认框
        val other = jsonOf(post("/api/new", emptyMap()).second)["id"]?.jsonPrimitive?.content
        assertTrue("另一条会话也拿到了这条 pending：串台了",
            jsonOf(get("/api/state?sid=$other"))["pending"]!!.jsonArray.isNullOrEmpty())

        val (code, _) = post("/api/decide", mapOf("id" to d["id"]!!.jsonPrimitive.content,
            "decision" to "allow_once", "answer" to ""))
        assertEquals(200, code)

        assertNotNull("审批放行后没有工具结束事件", awaitEventFrom("tool", sid))

        val f = File(ws, "approved.txt")
        val until = System.currentTimeMillis() + 15_000
        while (!f.isFile && System.currentTimeMillis() < until) Thread.sleep(100)
        assertTrue("点了「允许一次」，文件却没写出来 —— 这就是修之前那个 bug", f.isFile)
        assertEquals("approved by user", f.readText())
        // 答完之后必须从 pending 里消失，否则刷新一次就弹一次"已经批过的"框
        val after = jsonOf(get("/api/state?sid=$sid"))["pending"]?.jsonArray
        assertTrue("答完了还挂在 pending 里：$after", after.isNullOrEmpty())
        /*
         * 审批结论要留在历史里。内联卡答完就地收起，之后如果历史里查不到，
         * "这个文件到底是用户点头写的、还是自动写的"就永远说不清 —— 而这是权限层最该留痕的一件事。
         */
        val msgs = jsonOf(get("/api/state?sid=$sid"))["messages"]!!.jsonArray.map {
            Json.parseToJsonElement(it.toString()).jsonObject
        }
        // 只挑 role=tool 那条：assistant 那条也带 name="write"（从它的 calls 里补出来的），
        // 按名字找会先撞上它
        val wrote = msgs.firstOrNull {
            it["role"]?.jsonPrimitive?.content == "tool" && it["name"]?.jsonPrimitive?.content == "write"
        }
        assertNotNull("历史里没有 write 这条工具消息", wrote)
        assertEquals("审批结论没落到那条工具消息上",
            "允许一次", wrote!!["note"]?.jsonPrimitive?.content)
        assertTrue("回合没收尾", awaitIdle(sid))
    }

    /**
     * 多会话并行 —— 这一批要立起来的东西。
     *
     * 上一版的保护是"跑着的时候一切会话就 409"，代价是一次只能干一件事，
     * 而这恰恰是 codex / opencode / ZCode 都不接受的限制。
     * 现在要正面验：两条会话同时卡在各自的审批上，放行之后各自写各自的文件，
     * 而且**每一条事件都落在自己那条会话的 sid 上**（串台的检测在 awaitEventFrom 里）。
     */
    @Test
    fun `two sessions run at the same time without crossing streams`() {
        canned.clear(); script.clear(); events.clear()
        val promptA = "并行任务甲：写 par-a.txt"
        val promptB = "并行任务乙：写 par-b.txt"
        canned.add(Canned(promptA, listOf(
            toolCallTurn("call_a", "write", """{"path":"par-a.txt","content":"A"}"""),
            textTurn("甲完成")
        )))
        canned.add(Canned(promptB, listOf(
            toolCallTurn("call_b", "write", """{"path":"par-b.txt","content":"B"}"""),
            textTurn("乙完成")
        )))

        val a = jsonOf(post("/api/new", emptyMap()).second)["id"]!!.jsonPrimitive.content
        assertEquals("第一条没跑起来", 200, post("/api/task", mapOf("text" to promptA, "sid" to a)).first)
        val b = jsonOf(post("/api/new", emptyMap()).second)["id"]!!.jsonPrimitive.content
        assertNotEquals("开第二条时拿到了同一条会话", a, b)
        assertEquals("第二条没跑起来", 200, post("/api/task", mapOf("text" to promptB, "sid" to b)).first)

        val apprA = awaitEventFrom("approval", a)
        val apprB = awaitEventFrom("approval", b)
        assertNotNull("甲没有弹出审批（$events）", apprA)
        assertNotNull("乙没有弹出审批（$events）", apprB)
        val da = jsonOf(apprA!!)
        val db = jsonOf(apprB!!)
        assertTrue("审批卡上没写 par-a.txt：$apprA",
            da["title"]!!.jsonPrimitive.content.contains("par-a.txt"))
        assertTrue("两条会话的审批串了：${da["title"]} vs ${db["title"]}",
            db["title"]!!.jsonPrimitive.content.contains("par-b.txt"))

        // 两条都在等确认 ⇒ 服务级的"只许跑一个"必须已经不成立了
        assertTrue("甲没在跑", isRunning(a))
        assertTrue("乙没在跑", isRunning(b))
        val runningInList = Json.parseToJsonElement(get("/api/sessions")).jsonArray
            .filter { it.jsonObject["running"]?.jsonPrimitive?.content == "true" }
        assertTrue("列表里标成正在跑的不到两条：${get("/api/sessions")}", runningInList.size >= 2)

        post("/api/decide", mapOf("id" to da["id"]!!.jsonPrimitive.content,
            "decision" to "allow_once", "answer" to ""))
        post("/api/decide", mapOf("id" to db["id"]!!.jsonPrimitive.content,
            "decision" to "allow_once", "answer" to ""))

        assertTrue("甲的文件没写出来", waitFile(File(ws, "par-a.txt")))
        assertTrue("乙的文件没写出来", waitFile(File(ws, "par-b.txt")))
        assertEquals("A", File(ws, "par-a.txt").readText())
        assertEquals("B", File(ws, "par-b.txt").readText())

        assertTrue("甲跑完没收尾", awaitIdle(a))
        assertTrue("乙跑完没收尾", awaitIdle(b))
        // 各自的事件必须只落在自己的 sid 上：走到这里没超时就已经证明了
        assertNotNull("甲没有收尾回答", awaitEventFrom("answer", a))
        assertNotNull("乙没有收尾回答", awaitEventFrom("answer", b))
    }

    private fun waitFile(f: File, timeoutMs: Long = 15_000): Boolean {
        val until = System.currentTimeMillis() + timeoutMs
        while (!f.isFile && System.currentTimeMillis() < until) Thread.sleep(100)
        return f.isFile
    }

    /**
     * 停止只打指定那条会话。
     *
     * 老缺陷的另一种变体：`/api/stop` 如果对着"当前会话"或全局引擎置位，
     * 后台那条正在跑的就成了停不下来的孤儿（实测过：running 一直挂着 true 到 60 轮跑完，
     * 而界面上是一条空会话）。现在停 A 不能碰 B，A 自己也不能把待确认的工具做完。
     */
    @Test
    fun `stop only stops the session you point at`() {
        canned.clear(); script.clear(); events.clear()
        val promptA = "停止测试甲：写 stop-a.txt"
        val promptB = "停止测试乙：写 stop-b.txt"
        canned.add(Canned(promptA, listOf(
            toolCallTurn("call_sa", "write", """{"path":"stop-a.txt","content":"A"}"""),
            textTurn("甲完成")
        )))
        canned.add(Canned(promptB, listOf(
            toolCallTurn("call_sb", "write", """{"path":"stop-b.txt","content":"B"}"""),
            textTurn("乙完成")
        )))

        val a = jsonOf(post("/api/new", emptyMap()).second)["id"]!!.jsonPrimitive.content
        post("/api/task", mapOf("text" to promptA, "sid" to a))
        val b = jsonOf(post("/api/new", emptyMap()).second)["id"]!!.jsonPrimitive.content
        post("/api/task", mapOf("text" to promptB, "sid" to b))

        val apprA = awaitEventFrom("approval", a)
        val apprB = awaitEventFrom("approval", b)
        assertNotNull("甲没进入待确认，测不了停止的作用域", apprA)
        assertNotNull("乙没进入待确认，测不了停止的作用域", apprB)

        post("/api/stop", mapOf("sid" to a))
        assertTrue("按了停止，这条还在跑（孤儿）", awaitIdle(a))
        assertFalse("停止之后甲还是把文件写出去了", File(ws, "stop-a.txt").exists())
        // 乙的待确认不能因为"停了甲"被顺手拒掉：那会让用户以为是自己点错了
        assertTrue("停一条会话把另一条也停了", isRunning(b))
        val pendB = jsonOf(get("/api/state?sid=$b"))["pending"]!!.jsonArray
        assertTrue("乙的待确认被停甲时一起清掉了：$pendB", pendB.isNotEmpty())
        val idB = jsonOf(pendB.first().toString()).jsonObject["data"]!!.jsonObject["id"]!!
            .jsonPrimitive.content
        post("/api/decide", mapOf("id" to idB, "decision" to "allow_once", "answer" to ""))
        assertTrue("乙答完审批也跑不完", awaitIdle(b))
        assertTrue("乙的文件没写出来", waitFile(File(ws, "stop-b.txt")))
    }

    /** 同一条会话里排队不行：第二个任务会写进同一段历史，事后看不出哪条输出属于哪条。 */
    @Test
    fun `a second task on the same running session waits its turn`() {
        val sid = runTask("占位：写 busy.txt", listOf(
            toolCallTurn("call_busy", "write", """{"path":"busy.txt","content":"x"}"""),
            textTurn("结束")
        ))
        assertNotNull("没进入待决审批状态，测不了并发保护", awaitEventFrom("approval", sid))

        val (code, body) = post("/api/task", mapOf("text" to "同一条会话上的第二个", "sid" to sid))
        assertEquals("跑着的时候第二句该被收下排队，而不是被拒：$body", 200, code)
        // 收了，但**不能同时跑**：这条会话还停在第一句的待决审批上
        assertTrue("第二句没进队列", stateQueue(sid).contains("同一条会话上的第二个"))
        // 排队不等于并发：第一句还停在待决审批上，第二句必须还在队列里等着
        assertTrue("第一句该还在跑（第二句不该抢同一段历史）", isRunning(sid))
        assertTrue("第二句该还在队列里等着", stateQueue(sid).contains("同一条会话上的第二个"))

        // 但**切换会话**不再被拒：后台继续跑，事件按 sid 分流
        val opened = post("/api/open", mapOf("id" to sid))
        assertEquals("切到正在跑的会话被拒了（旧的限制没拆干净）：${opened.second}", 200, opened.first)
        assertTrue("切过去之后看不到它在跑", isRunning(sid))

        post("/api/stop", mapOf("sid" to sid))
        assertTrue(awaitIdle(sid))
        // 停止之后排队的句子不该自己接着往下说
        assertEquals("按了停止，队列该清空（文字由前端退回输入框）",
            emptyList<String>(), stateQueue(sid))
    }

    /** 队列现在是什么：/api/state 里的 queue[].t */
    private fun stateQueue(sid: String): List<String> =
        Json.parseToJsonElement(get("/api/state?sid=$sid")).jsonObject["queue"]?.jsonArray
            ?.map { it.jsonObject["t"]?.jsonPrimitive?.contentOrNull ?: "" } ?: emptyList()

    @Test
    fun `deny leaves the file unwritten`() {
        val prompt = "写一个 denied.txt"
        val sid = runTask(prompt, listOf(
            toolCallTurn("call_d", "write", """{"path":"denied.txt","content":"nope"}"""),
            textTurn("那我不写了")
        ))
        val raw = awaitEventFrom("approval", sid)
        assertNotNull(raw)
        val d = jsonOf(raw!!)
        post("/api/decide", mapOf("id" to d["id"]!!.jsonPrimitive.content,
            "decision" to "deny", "answer" to ""))
        // 事件名是 answer（引擎的 TextDone），不是 done：写错这里会白等 15 秒超时
        assertNotNull("拒绝之后回合也没收尾", awaitEventFrom("answer", sid))
        Thread.sleep(300)
        assertFalse("被拒绝了却还是写了文件", File(ws, "denied.txt").exists())
    }

    /**
     * 设置抽屉一次要写 5 个字段。当年 `decide` 就是因为"一个请求体读多次"只认第一个字段，
     * 这条测试存在的全部理由就是把同一类错误钉在设置接口上。
     *
     * 现在还多钉一件事：改完设置要**立刻反映到活着的引擎上**（模型与网关客户端是
     * 构造时抓走的，不换的话界面显示新模型、发请求用旧模型）。
     *
     * 注意它改的是**整个服务共享的**档位与工作区，所以必须在 finally 里还原：
     * 不还原的话，先跑完这条再跑审批那两条，档位已经变成 auto —— 不再弹审批，
     * 那两条会报"没收到审批事件"，看起来像审批坏了。（第一次跑就踩到了，
     * 单跑通过、合跑失败，就是这个共享状态在作怪。）
     */
    @Test
    fun `the settings drawer payload changes every field it sends`() {
        val dir = Files.createTempDirectory("haoai-flow-ws2").toFile()
        val before = jsonOf(get("/api/settings"))
        val wasMode = before["mode"]?.jsonPrimitive?.content ?: "ask"
        val wasWs = before["workspace"]?.jsonPrimitive?.content ?: ws.absolutePath
        // 先立着一条会话，才有"活着的引擎"可验
        val live = jsonOf(post("/api/new", emptyMap()).second)["id"]!!.jsonPrimitive.content
        try {
            val (code, _) = post(
                "/api/settings",
                mapOf(
                    "model" to "glm-flow.1",
                    "baseUrl" to "http://127.0.0.1:${gateway.address.port}/v1",
                    "workspace" to dir.absolutePath, "mode" to "auto"
                )
            )
            assertEquals(200, code)
            val got = jsonOf(get("/api/settings"))
            assertEquals("glm-flow.1", got["model"]?.jsonPrimitive?.content)
            assertEquals("auto", got["mode"]?.jsonPrimitive?.content)
            // 全局设置改了，这条会话看到的模型也必须已经是新的（旧写法：只有新会话才生效）
            assertEquals("活着的会话还拿着旧模型：改了设置也不生效",
                "glm-flow.1", jsonOf(get("/api/state?sid=$live"))["model"]?.jsonPrimitive?.content)
            // 而工作区是**每条会话自己的**：改了全局默认不该把已有会话拽到新目录
            assertEquals("已有会话被改到了新工作区",
                File(wasWs).canonicalPath, File(jsonOf(get("/api/state?sid=$live"))
                    ["workspace"]!!.jsonPrimitive.content).canonicalPath)
        } finally {
            post("/api/settings", mapOf(
                "model" to "flowtest", "workspace" to wasWs, "mode" to wasMode
            ))
        }
    }
}
