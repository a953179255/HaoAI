package com.haoai.pc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 多 Agent 的那层状态机：**专家实例 + 跨专家收件箱**。
 *
 * 全部脱开模型与网页测：`Agents.runner` 是注入的假执行口，
 * 判据打在"谁在跑、消息排在谁那里、同一个专家会不会被并发踩"上 ——
 * 这些恰恰是只有真模型才能跑出来、但**跟模型答什么完全无关**的错。
 */
class AgentsTest {

    companion object {
        @BeforeClass
        @JvmStatic
        fun home() {
            val dir = Files.createTempDirectory("haoai-agents-home").toFile()
            System.setProperty("haoai.home", File(dir, "home").absolutePath)
        }
    }

    @Before
    fun clear() {
        Agents.reset()
        Agents.runner = null
        Agents.followup = null
        Agents.notify = null
        Presets.file().delete()
        Agents.file().delete()
    }

    private fun card(id: String, name: String = id) =
        Preset(id = id, name = name, persona = "你是$name", model = "", workspace = "", mode = "auto")

    private fun json() = Json.parseToJsonElement(Agents.json()).jsonObject

    @Test
    fun `asking an expert that does not exist is refused, and points at agent_list`() {
        val (out, err) = Agents.ask("ghost", "user", "在吗")
        assertEquals("", out)
        assertTrue("要说清没有这张卡：" + err, err!!.contains("没有这张专家卡"))
        assertTrue("还要指一条出路（模型看到这句会去调 agent_list，而不是自己瞎猜）",
            err.contains("agent_list"))
    }

    @Test
    fun `a sync ask returns the peer's answer and counts one turn`() {
        Presets.update(card("coder", "代码"))
        Agents.runner = { preset, text -> "[$preset] $text → 答完了" to null }
        val (out, err) = Agents.ask("coder", "user", "看下这个报错")
        assertNull(err)
        assertEquals("[coder] 看下这个报错 → 答完了", out)
        val s = Agents.state("coder")!!
        assertEquals(AgentState.IDLE, s.state)
        assertEquals(1, s.turns)
    }

    /** 执行口没接上要说破：返回空字符串会被模型当成"对方说完了，什么都没说"。 */
    @Test
    fun `no runner is an error, not an empty answer`() {
        Presets.update(card("coder"))
        val (out, err) = Agents.ask("coder", "user", "嗨")
        assertEquals("", out)
        assertTrue("要说出执行口：" + err, err!!.contains("执行口"))
    }

    @Test
    fun `a failing runner marks the instance failed and keeps the reason`() {
        Presets.update(card("coder"))
        Agents.runner = { _, _ -> "" to "网关 401" }
        val (_, err) = Agents.ask("coder", "user", "嗨")
        assertTrue(err!!.contains("网关 401"))
        val s = Agents.state("coder")!!
        assertEquals(AgentState.FAILED, s.state)
        assertTrue(s.lastError.contains("网关 401"))
    }

    /**
     * 最值钱的一条：**同一个专家不许并发**。
     * 两张会话同时在同一个工作区里写文件，坏法是没有规律的，而且事后看不出谁写的。
     */
    @Test
    fun `the same expert is serialised while different experts run in parallel`() {
        Presets.update(card("a", "甲")); Presets.update(card("b", "乙"))
        val enter = CountDownLatch(1)
        val release = CountDownLatch(1)
        Agents.runner = { preset, _ ->
            if (preset == "a") { enter.countDown(); release.await(5, TimeUnit.SECONDS) }
            "好了:$preset" to null
        }
        val t = Thread { Agents.ask("a", "user", "占住甲") }
        t.start()
        assertTrue("第一个调用没进到 runner", enter.await(3, TimeUnit.SECONDS))
        val (busy, err) = Agents.ask("a", "user", "再问甲")
        assertNull("忙的时候不该报错，要给模型一句能照着办的话", err)
        assertTrue("要说清在忙：" + busy, busy.contains("正在忙"))
        // 另一个专家不受影响
        assertEquals("好了:b", Agents.ask("b", "user", "问乙").first)
        release.countDown()
        t.join(3000)
        assertEquals("甲跑完之后要回到待命", AgentState.IDLE, Agents.state("a")!!.state)
    }

    @Test
    fun `background delivery runs later and hands the reply back to the sender`() {
        Presets.update(card("a", "甲"))
        val done = CountDownLatch(1)
        var sentTo = ""
        var sentText = ""
        Agents.runner = { _, _ -> "后台答完了" to null }
        Agents.followup = { sid, text -> sentTo = sid; sentText = text; done.countDown() }
        val (out, err) = Agents.ask("a", "pcSRC", "帮我查一下", mode = "background")
        assertNull(err)
        assertTrue("要回一句带编号的回执：" + out, out.contains("收件箱") && out.contains("#ib"))
        assertTrue("后台消息要在 5 秒内被投出去", done.await(5, TimeUnit.SECONDS))
        assertEquals("回信送回发信人那条会话", "pcSRC", sentTo)
        assertTrue("回信要写明是谁回的：" + sentText, sentText.contains("甲 的回信") && sentText.contains("后台答完了"))
        val ib = json()["inbox"]!!.jsonArray
        assertTrue("收件箱里这条要标成已完成", ib.any {
            it.jsonObject["state"]!!.jsonPrimitive.content == InboxMsg.DONE })
    }

    @Test
    fun `a stopped expert refuses both sync and background`() {
        Presets.update(card("a", "甲"))
        Agents.runner = { _, _ -> "x" to null }
        Agents.start("a")
        Agents.stop("a")
        assertTrue(Agents.ask("a", "user", "嗨").second!!.contains("已经被停"))
        val e2 = Agents.ask("a", "user", "嗨", mode = "background").second
        assertTrue("后台也不能绕过停止：" + e2, e2!!.contains("已经被停"))
        assertEquals("被拒的投递不许留在收件箱里", 0, json()["inbox"]!!.jsonArray.size)
    }

    @Test
    fun `the inbox is capped and says so instead of dropping silently`() {
        Presets.update(card("a", "甲"))
        // 不启 worker：手动把队列填满（ensureWorker 一旦被调就会开始消费）
        repeat(Agents.MAX_INBOX) {
            val (_, e) = Agents.enqueue(
                InboxMsg("ib$it", "a", "user", "第 $it 条"))
            assertNull("第 $it 条不该被拒", e)
        }
        val (m, err) = Agents.enqueue(InboxMsg("ibX", "a", "user", "超出的那条"))
        assertNull(m)
        assertTrue("满了要说清上限：" + err, err!!.contains("收件箱满了") && err.contains("200"))
    }

    @Test
    fun `the roster gives the model card ids and who is busy`() {
        assertTrue("一张卡都没有时要说清去哪建：" + Agents.roster(),
            Agents.roster().contains("还没有任何专家卡"))
        Presets.update(card("a", "甲")); Presets.update(card("b", "乙"))
        Agents.start("a")
        val r = Agents.roster()
        assertTrue("要给卡 id（模型填 to 用的就是它）：" + r, r.contains("preset=a"))
        assertTrue("要有专长一句话", r.contains("没写专长") || r.contains("甲"))
        assertTrue("要标出谁被起过：" + r, r.contains("待命"))
        assertTrue("没启动过的也要在（不然模型以为只有跑过的才算专家）", r.contains("没启动过"))
    }

    @Test
    fun `instances survive a restart but running never lies about itself`() {
        Presets.update(card("a", "甲"))
        Agents.start("a", "pc123")
        Agents.markBusy("a")
        assertTrue("agents.json 要落盘", Agents.file().isFile)
        Agents.reset()
        Agents.restore()
        val s = Agents.state("a")!!
        assertEquals("会话 id 要跟着回来（否则重启后同事失忆）", "pc123", s.sid)
        assertEquals("running 一律降级成 idle：那个线程已经没了", AgentState.IDLE, s.state)
    }

    @Test
    fun `the json gives the interface everything it needs to paint one row`() {
        Presets.update(card("a", "甲"))
        Agents.start("a", "pc9")
        val a = json()["agents"]!!.jsonArray.single().jsonObject
        assertEquals("a", a["preset"]!!.jsonPrimitive.content)
        assertEquals("甲", a["name"]!!.jsonPrimitive.content)
        assertEquals("待命", a["stateName"]!!.jsonPrimitive.content)
        assertEquals("pc9", a["sid"]!!.jsonPrimitive.content)
        assertEquals(0, a["turns"]!!.jsonPrimitive.int)
        assertNotNull(a["lastError"])
        assertTrue(json().containsKey("inbox"))
        assertTrue(json().containsKey("busy"))
    }

    // ---- 线有没有接上 ----

    @Test
    fun `the shell wires the runner and the route, and the tools exist`() {
        val server = File("src/main/kotlin/com/haoai/pc/Server.kt").readText(Charsets.UTF_8)
        assertTrue("启动时要接执行口（不接则 ask_agent 永远报没接上）", server.contains("wireAgents()"))
        assertTrue("要有 /api/agents 路由", server.contains("\"/api/agents\" -> agents(ex)"))
        val w = File("src/main/kotlin/com/haoai/pc/ServerAgents.kt")
        assertTrue("缺 ServerAgents.kt", w.isFile)
        val ws = w.readText(Charsets.UTF_8)
        assertTrue("三件事都要做：读回状态、注入 runner、起收件箱线程",
            listOf("Agents.restore()", "Agents.runner =", "Agents.ensureWorker()").all { ws.contains(it) })
        assertTrue("要复用对方那条常驻会话（每次新建就等于对方不记得）",
            ws.contains("sessions.containsKey(it)") && ws.contains("Agents.attach(p.id, it)"))
        assertTrue("对方正在跑另一件事时要拒，而不是把两句话搅进同一条引擎",
            ws.contains("if (m.running)"))
        val tools = File("src/main/kotlin/com/haoai/pc/Tools.kt").readText(Charsets.UTF_8)
        assertTrue("两把工具都要注册进内置表",
            tools.contains("AgentListTool(), AskAgentTool()"))
        assertTrue("agent_list 读的是 roster", tools.contains("Agents.roster()"))
        assertTrue("ask_agent 走 Agents.ask 并带上发信会话", tools.contains("Agents.ask(to, ctx.sid, text, mode)"))
    }
}
