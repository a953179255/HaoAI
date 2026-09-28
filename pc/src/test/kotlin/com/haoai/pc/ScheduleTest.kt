package com.haoai.pc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
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
 * 定时任务这一层的回归。
 *
 * 为什么单独有这份：调度最容易出错的不是"到没到点"，而是**睡醒之后怎么算**。
 * 笔记本合上八小时，开机那一刻补跑八次还是一次，代码里就是一个比较符的区别，
 * 而补跑八次的后果是网关被自己刷爆、用户开机先收到八条无关回复。
 * 所以这里把"不补跑"钉成断言，而不是留在注释里。
 */
class ScheduleTest {

    private var lastSid = ""

    private fun clock(h: Int, m: Int, dayOffset: Int = 0): Long {
        val c = java.util.Calendar.getInstance()
        c.set(java.util.Calendar.HOUR_OF_DAY, h)
        c.set(java.util.Calendar.MINUTE, m)
        c.set(java.util.Calendar.SECOND, 0)
        c.set(java.util.Calendar.MILLISECOND, 0)
        if (dayOffset != 0) c.add(java.util.Calendar.DAY_OF_MONTH, dayOffset)
        return c.timeInMillis
    }

    private fun sched(
        id: String = "sc1", kind: String = "interval", every: Int = 30, at: String = "09:00",
        created: Long = System.currentTimeMillis(), lastRun: Long = 0L, enabled: Boolean = true,
        name: String = "看日志", prompt: String = "把报错日志读一遍", lastSid: String = ""
    ) = Schedule(
        id = id, name = name, prompt = prompt, kind = kind, every = every, at = at,
        created = created, lastRun = lastRun, enabled = enabled, lastSid = lastSid
    )

    @Test
    fun `interval counts from creation until it has run once`() {
        val now = System.currentTimeMillis()
        assertFalse("才过 10 分钟，30 分钟一条不该触发",
            Schedule.dueNow(sched(created = now - 10 * MINUTE), now))
        assertTrue("过了 31 分钟该触发", Schedule.dueNow(sched(created = now - 31 * MINUTE), now))
    }

    @Test
    fun `interval counts from lastRun after the first fire`() {
        val now = System.currentTimeMillis()
        val ran = sched(created = now - 5 * HOUR, lastRun = now - 5 * MINUTE)
        assertFalse("上次跑是 5 分钟前，30 分钟的间隔不该重跑", Schedule.dueNow(ran, now))
        assertEquals(now - 5 * MINUTE + 30 * MINUTE, Schedule.nextDue(ran, now))
    }

    /** 这条是本批最重要的取舍：睡八小时醒来只跑一次，不追补八次。 */
    @Test
    fun `a slept machine fires once, not eight times`() {
        val now = System.currentTimeMillis()
        val slept = sched(created = now - 10 * HOUR, lastRun = now - 8 * HOUR)
        assertTrue("醒来第一次确实到点了", Schedule.dueNow(slept, now))
        val after = slept.copy()
        after.lastRun = now
        assertFalse("补跑第二次就是刷爆网关", Schedule.dueNow(after, now))
    }

    @Test
    fun `daily catches up after a sleep but a freshly made task waits for its slot`() {
        // 早上建、机器睡过 09:00、下午醒来 → 补那一次（这是"不补跑"里唯一的例外）
        val born = sched(kind = "daily", at = "09:00").copy(created = clock(7, 0))
        assertTrue("点已过且今天没跑过，就该补这一次", Schedule.dueNow(born, clock(14, 0)))
        // 下午才建"每天 09:00" → 不该当场跑一遍：要立刻看效果有点「跑一次」，
        // 保存一个排期就等于烧一次 token，是旧版把"补跑"和"刚建好"混成了一件事。
        val late = sched(kind = "daily", at = "09:00").copy(created = clock(14, 0))
        assertFalse(Schedule.dueNow(late, clock(14, 30)))
        assertEquals(clock(9, 0, 1), Schedule.nextDue(late, clock(14, 30)))
        val ran = born.copy()
        ran.lastRun = clock(14, 0)
        assertFalse("今天跑过了，要等到明天九点", Schedule.dueNow(ran, clock(14, 5)))
        assertEquals(clock(9, 0, 1), Schedule.nextDue(ran, clock(14, 0)))
    }

    @Test
    fun `daily in the future does not fire`() {
        /*
         * created 必须钉死，不能留默认值。默认是 System.currentTimeMillis()，而 daily 的
         * nextDue 走的是 `nextSlot(s, created)` —— 它算的是"这条排期出生后第一个该跑的点"，
         * 跟传进来的 now 无关。于是晚上 23:00 之后跑测试，出生点已经越过今天那一格，
         * 答案就成了"明天 23:00"，这条断言就红了（实测 23:33 跑全量时炸在这里，
         * 而白天跑一直是绿的 —— 一个只在夜里红的测试等于没有测试）。
         */
        val late = sched(kind = "daily", at = "23:00", created = clock(7, 0))
        assertFalse(Schedule.dueNow(late, clock(14, 0)))
        assertEquals(clock(23, 0), Schedule.nextDue(late, clock(14, 0)))
    }

    /** 上面那条为什么必须钉 created：daily 的答案根本不看传进来的 now，只看出生点。 */
    @Test
    fun `daily's next slot keys off the birth moment, not off the moment you ask`() {
        val s = sched(kind = "daily", at = "23:00", created = clock(7, 0))
        val morning = Schedule.nextDue(s, clock(8, 0))
        val night = Schedule.nextDue(s, clock(22, 0))
        assertEquals("同一个出生点，早晚问两次要给同一个答案", morning, night)
        assertEquals(clock(23, 0), morning)
        // 出生点已经在今晚那一格之后 → 第一格是明天的，而不是"现在补一次"
        val bornLate = sched(kind = "daily", at = "23:00", created = clock(23, 30))
        assertEquals(clock(23, 0, 1), Schedule.nextDue(bornLate, clock(23, 45)))
        assertFalse(Schedule.dueNow(bornLate, clock(23, 45)))
    }

    /** 写法不对的时间不是"崩"也不是"零点跑"，是这条根本不跑。 */
    @Test
    fun `a bad clock string means never, not midnight`() {
        listOf("25:00", "9", "09:60", "abc", "").forEach { bad ->
            val s = sched(kind = "daily", at = bad)
            assertEquals("$bad 不该算出时间", 0L, Schedule.nextDue(s, clock(14, 0)))
            assertFalse(Schedule.dueNow(s, clock(14, 0)))
        }
    }

    @Test
    fun `disabled task never fires even when overdue`() {
        val now = System.currentTimeMillis()
        val off = sched(created = now - 5 * HOUR, enabled = false)
        assertEquals(0L, Schedule.nextDue(off, now))
        assertFalse(Schedule.dueNow(off, now))
    }

    @Test
    fun `every 为零不是除零也不是每刻都跑`() {
        val now = System.currentTimeMillis()
        val zero = sched(every = 0, created = now)
        assertEquals("写成 0 要退回默认 60 分钟，不能变成每秒触发", now + 60 * MINUTE, Schedule.nextDue(zero, now))
    }

    @Test
    fun `store round-trips chinese and newlines`() {
        Schedules.save(listOf(
            sched(created = 1_700_000_000_000L, name = "早间巡检", lastSid = "pcab12",
                prompt = "第一行\n第二行带\"引号\"与\\反斜杠")
        ))
        val back = Schedules.load()
        assertEquals(1, back.size)
        assertEquals("早间巡检", back[0].name)
        assertEquals("第一行\n第二行带\"引号\"与\\反斜杠", back[0].prompt)
        assertEquals("pcab12", back[0].lastSid)
        assertEquals(1_700_000_000_000L, back[0].created)
        assertTrue(back[0].enabled)
    }

    /** 一个逗号不能让整个服务起不来：坏文件当空表，而不是抛出去。 */
    @Test
    fun `corrupt file reads as empty instead of throwing`() {
        Schedules.save(listOf(sched()))
        Schedules.file().writeText("{ this is not json ]")
        assertEquals(0, Schedules.load().size)
    }

    @Test
    fun `update replaces by id and remove reports whether it deleted`() {
        Schedules.save(emptyList())
        Schedules.update(sched(name = "旧名"))
        Schedules.update(sched(name = "新名"))
        assertEquals("同 id 应当覆盖而不是加一条", 1, Schedules.load().size)
        assertEquals("新名", Schedules.load()[0].name)
        assertTrue(Schedules.remove("sc1"))
        assertFalse(Schedules.remove("sc1"))
        assertEquals(0, Schedules.load().size)
    }

    @Test
    fun `tick fires only the due ones and records a failing fire`() {
        val now = System.currentTimeMillis()
        Schedules.save(listOf(
            sched(id = "due", created = now - 5 * HOUR),
            sched(id = "soon", created = now),
            sched(id = "boom", created = now - 5 * HOUR)
        ))
        val hit = mutableListOf<String>()
        val s = Scheduler { x ->
            if (x.id == "boom") error("网关炸了")
            hit += x.id
            x.lastRun = System.currentTimeMillis()
            Schedules.update(x)
        }
        assertEquals("到点的两条都要被看见（其中一条炸了）", 2, s.tick(System.currentTimeMillis()))
        assertEquals(listOf("due"), hit)
        val after = Schedules.load().associateBy { it.id }
        assertTrue("跑成的那条要记账", (after["due"]?.lastRun ?: 0L) > 0L)
        assertEquals("炸了的那条要把原因留在表里，不然界面上只看到它一直不跑",
            "网关炸了", after["boom"]?.lastError)
        assertEquals("没到点的不能被记成跑过", 0L, after["soon"]?.lastRun)
    }

    @Test
    fun `api adds lists toggles and deletes`() {
        Schedules.save(emptyList())
        val first = items(post("/api/schedules", """{"name":"早间巡检","prompt":"看看日志"}""").second)
        assertEquals(1, first.size)
        val o = first[0].jsonObject
        assertEquals("早间巡检", o["name"]!!.jsonPrimitive.content)
        val id = o["id"]!!.jsonPrimitive.content
        // 名字没填时拿句子前 18 字顶上，列表里不该出现空白行
        post("/api/schedules", """{"prompt":"这条没有名字，应当用句子当前缀"}""")
        val two = items(get("/api/schedules"))
        assertEquals(2, two.size)
        assertEquals("这条没有名字，应当用句子当前缀".take(18), two.last().jsonObject["name"]!!.jsonPrimitive.content)
        // 开关要真的翻转（界面上那个"暂停"就靠它）
        assertFalse(enabled(id, post("/api/schedules", """{"op":"toggle","id":"$id"}""").second))
        assertTrue(enabled(id, post("/api/schedules", """{"op":"toggle","id":"$id"}""").second))
        assertEquals(1, items(post("/api/schedules", """{"op":"del","id":"$id"}""").second).size)
        assertEquals("删不存在的 id 不该把别的带走", 1,
            items(post("/api/schedules", """{"op":"del","id":"sc-nope"}""").second).size)
    }

    /** 空句子不能收：收下了它就是一条永远在报错的任务。 */
    @Test
    fun `api refuses an empty prompt`() {
        Schedules.save(emptyList())
        val (code, body) = post("/api/schedules", """{"prompt":"   "}""")
        assertEquals(200, code)
        assertFalse(Json.parseToJsonElement(body).jsonObject["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("空句子不该被加进任务表", 0, Schedules.load().size)
    }

    /** nextDue 由服务端算好：界面只负责显示，不再自己解析 HH:MM。 */
    @Test
    fun `api returns nextDue for the list`() {
        Schedules.save(listOf(sched(created = System.currentTimeMillis() - 70 * MINUTE)))
        val due = items(get("/api/schedules"))[0].jsonObject["nextDue"]!!.jsonPrimitive.long
        assertTrue("到点的任务 nextDue 应当已过期（界面据此显示「就是现在」）", due > 0 && due <= System.currentTimeMillis())
        Schedules.save(listOf(sched(created = System.currentTimeMillis() - 5 * HOUR, enabled = false)))
        assertEquals("关掉的没有下一次", 0L, items(get("/api/schedules"))[0].jsonObject["nextDue"]!!.jsonPrimitive.long)
    }

    /**
     * 到点真的能起一条**新会话**，而且标题是任务名。
     *
     * 这条是全批最值钱的断言：前面几条都在测"什么时候跑"，只有它测"跑了之后用户看得见"。
     */
    @Test
    fun `run starts a new session named after the task`() {
        val before = items(get("/api/sessions")).size
        Schedules.save(listOf(sched(name = "晨会材料", prompt = "整理昨天的改动")))
        val id = items(get("/api/schedules"))[0].jsonObject["id"]!!.jsonPrimitive.content
        val ran = items(post("/api/schedules", """{"op":"run","id":"$id"}""").second)[0].jsonObject
        assertTrue("跑一次要记账", ran["lastRun"]!!.jsonPrimitive.long > 0L)
        val sid = ran["lastSid"]!!.jsonPrimitive.content
        assertTrue("要留下新会话的 id", sid.isNotBlank())
        lastSid = sid
        val listed = get("/api/sessions")
        assertTrue(
            "侧栏里要看得见这条任务会话（要找 $sid，列表里是 " +
                items(listed).mapNotNull {
                    it.jsonObject["id"]?.jsonPrimitive?.contentOrNull
                }.joinToString(",") + "）",
            listed.contains(sid)
        )
        assertTrue("标题要用任务名，不能是一串 id 或「新会话」", listed.contains("晨会材料"))
        assertEquals("只多出一条，不是把当前会话也用掉了", before + 1, items(listed).size)
        /*
         * 再等到它真的跑完，核对里面说过话、有回复。
         * 只断言"会话出现了"是不够的：本批第一版的假网关把帧分隔写成了原始串里的
         * \n（那两个字符），一帧都解不出来，任务其实每条都在报错，而测试全绿 ——
         * 判据必须落在"结果"上，不是"有这个东西"。
         */
        val deadline = System.currentTimeMillis() + 8_000
        while (state().contains("\"running\":true") && System.currentTimeMillis() < deadline) Thread.sleep(50)
        val st = state()
        assertFalse("假网关会立刻 stop，卡住就是链路断了", st.contains("\"running\":true"))
        assertTrue("任务句子要进会话", st.contains("整理昨天的改动"))
        assertTrue("而且要有真回复", st.contains("跑完了"))
    }

    private fun state(): String = get("/api/state?sid=$lastSid")

    /**
     * 并行满了时的一次失败触发，不许在列表里留下空白会话。
     *
     * 上一版是先建会话再判上限：用户只是发送失败，侧栏却多出一条"新会话"，
     * 定时任务撞上这个就更糟 —— 每五分钟留一条壳。`maxRunning = 0` 就是为了让这条能被测。
     */
    @Test
    fun `a refused start leaves no empty session`() {
        val full = WebServer(PcSettings.load(), port = 0, maxRunning = 0)
        val url = "http://127.0.0.1:${full.start()}"
        try {
            val before = items(getFrom(url + "/api/sessions")).size
            val (code, _) = postTo(url, "/api/task", """{"text":"这条发不出去"}""")
            assertEquals("满了要回 409", 409, code)
            assertEquals("一次失败的发送不该在列表里留下新会话", before, items(getFrom(url + "/api/sessions")).size)
        } finally {
            runCatching { full.stop() }
        }
    }

    /** `/api/sessions` 是裸数组，`/api/schedules` 包在 items 里：两处都要能读。 */
    private fun items(resp: String) = when (val el = Json.parseToJsonElement(resp)) {
        is JsonArray -> el.toList()
        else -> el.jsonObject["items"]!!.jsonArray.toList()
    }

    private fun enabled(id: String, resp: String): Boolean =
        items(resp).first { it.jsonObject["id"]!!.jsonPrimitive.content == id }
            .jsonObject["enabled"]!!.jsonPrimitive.content.toBoolean()

    private fun getFrom(url: String): String = http.send(
        HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString()
    ).body()

    private fun postTo(base: String, path: String, body: String): Pair<Int, String> = send(base + path, body)

    private fun post(path: String, body: String): Pair<Int, String> = send(base + path, body)

    private fun send(url: String, body: String): Pair<Int, String> {
        val r = http.send(
            HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString()
        )
        return r.statusCode() to r.body()
    }

    private fun get(path: String): String = http.send(
        HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
        HttpResponse.BodyHandlers.ofString()
    ).body()

    companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE

        private lateinit var server: WebServer
        private lateinit var base: String
        private lateinit var gateway: com.sun.net.httpserver.HttpServer
        private val http = HttpClient.newHttpClient()

        @BeforeClass
        @JvmStatic
        fun setUp() {
            val home = Files.createTempDirectory("haoai-sched-home").toFile()
            System.setProperty("haoai.home", File(home, "home").absolutePath)
            val ws = File(home, "ws").apply { mkdirs() }
            // 假网关：一条不带工具调用的流式回答，让"跑一次"真的能跑完而不碰任何网络
            gateway = com.sun.net.httpserver.HttpServer.create(
                java.net.InetSocketAddress("127.0.0.1", 0), 0
            )
            gateway.createContext("/v1/chat/completions") { ex ->
                // 帧分隔必须是真换行：写在三引号原始串里的 \n 是两个字符，
                // Provider 一帧都解不出来，于是"跑一次"其实一直在报错（本批第一版就这样假绿过）
                val body = ("data: " +
                    """{"choices":[{"index":0,"finish_reason":"stop","delta":{"content":"跑完了"}}]}""" +
                    "\n\ndata: [DONE]\n\n").toByteArray()
                ex.responseHeaders.add("Content-Type", "text/event-stream")
                ex.sendResponseHeaders(200, body.size.toLong())
                ex.responseBody.use { it.write(body) }
                ex.close()
            }
            gateway.start()
            PcSettings.save(PcSettings(
                workspace = ws.absolutePath, permissionMode = "auto",
                baseUrl = "http://127.0.0.1:${gateway.address.port}/v1"
            ))
            server = WebServer(PcSettings.load(), port = 0)
            base = "http://127.0.0.1:${server.start()}"
            /*
             * 临时 home 是全新的，此刻任务表一定是空的，所以调度线程起跑那一轮什么也不会做；
             * 这里再把它停掉，免得某个测试留下的"已过点"任务在五秒后被后台线程顺手跑掉 ——
             * 测试要的是"我调 tick 才跑"，不是"看谁先抢到"。
             */
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
    fun `the api takes a one-line schedule and refuses to guess`() {
        Schedules.save(emptyList())
        val resp = items(post("/api/schedules",
            """{"name":"剪片子","prompt":"把今天的录屏剪成 30 秒","when":"每周一三五 8 点"}""").second)
        assertEquals(1, resp.size)
        val o = resp[0].jsonObject
        assertEquals("weekly", o["kind"]!!.jsonPrimitive.content)
        assertEquals("0,2,4", o["days"]!!.jsonPrimitive.content)
        assertEquals("08:00", o["at"]!!.jsonPrimitive.content)
        assertTrue("列表要回一句人话复述：" + o["when"]!!.jsonPrimitive.content,
            o["when"]!!.jsonPrimitive.content.contains("每周一、周三、周五"))
        // 看不懂就明确拒绝，并且**不许留下一条半成品任务**：
        // 猜错的时间会在人睡着的时候起真任务、花真 token
        val (st, body) = post("/api/schedules", """{"prompt":"x","when":"每天"}""")
        assertEquals(200, st)
        assertTrue("该说清缺什么：" + body, body.contains("几点"))
        assertEquals("被拒的那条不该落盘", 1, items(get("/api/schedules")).size)
        // 预览口：界面边打边显示"理解成什么、下一次什么时候跑"
        val p = get("/api/sched/parse?when=" + java.net.URLEncoder.encode("半小时后", "UTF-8"))
        assertTrue("预览该给出一次性的复述：" + p, p.contains(""""kind":"once"""") && p.contains("30 分钟后"))
        assertTrue("预览要给出下一次时间：" + p, p.contains("nextText"))
        val pb = post("/api/sched/parse", """{"when":"每周一三五 8 点"}""").second
        assertTrue("正文里带 when 也要能解析：" + pb, pb.contains("每周一、周三、周五"))
    }
}
