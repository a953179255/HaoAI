package com.haoai.pc

import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 「这句参考了知识库里哪几段」（对标 Octop 的 KB 引用出处）。
 *
 * 四段主张，缺一都不是"做完了"：
 * 1. **出处从工具那一层结构化地交出来**，不是让前端去正则拆给模型看的散文 ——
 *    文案一改引用就丢，而这种丢法界面上一切正常。
 * 2. **按 tool_call id 关联**，不按消息下标：压缩/删句会让下标整体前移，
 *    按它配对的表现是"引用挂在别的卡底下"，看着像功能正常。
 * 3. **刷新之后还在**：只随 SSE 流一次的字段，回合收尾那次 hydrate 重画就没了
 *    （#111 那条降级提示就是同一个形状；引擎跑完还会主动 hydrate 一次）。
 * 4. **消息删了引用跟着删**：不然"回到这一句之前"之后，界面上还挂着已经不存在的出处。
 */
class KbCiteTest {

    companion object {
        private lateinit var home: File

        @BeforeClass
        @JvmStatic
        fun setUp() {
            home = Files.createTempDirectory("haoai-kbcite-home").toFile()
            System.setProperty("haoai.home", File(home, "home").absolutePath)
        }
    }

    private fun workspace(): File = Files.createTempDirectory("haoai-kbcite-ws").toFile().apply { mkdirs() }

    private class Scripted(private val turns: MutableList<AssistantTurn>) : ChatClient {
        var calls = 0
        override fun chat(messages: List<Msg>, tools: List<ToolSchema>, onText: (String) -> Unit): AssistantTurn {
            calls++
            val t = if (turns.isNotEmpty()) turns.removeAt(0)
            else AssistantTurn("收尾", emptyList(), Usage(), "stop")
            onText(t.text)
            return t
        }
    }

    private class Always : Gate {
        override fun approve(title: String, detail: String, kind: String) = true
        override fun ask(question: String, options: List<String>) = options.firstOrNull() ?: ""
        override fun ask(req: AskReq) = ask(req.question, req.options.map { it.label })
    }

    private fun turn(text: String, calls: List<ToolCall> = emptyList()) =
        AssistantTurn(text, calls, Usage(10, 5), if (calls.isEmpty()) "stop" else "tool_calls")

    /** 一个带「液态玻璃」词条的库；`kb` 空着让工具走"每回合自动带上"那条默认路。 */
    private fun termKb(name: String = "引用库"): String {
        val b = Knowledge.create(name)!!.first!!
        Knowledge.addDoc(
            b.id, "术语.md",
            "# 术语\n\n液态玻璃：一种把背景模糊后叠上来的做法。\n别的条目：与本题无关。".toByteArray()
        )
        return b.id
    }

    private fun engine(
        ws: File,
        turns: MutableList<AssistantTurn>,
        events: MutableList<Ev> = mutableListOf()
    ): Pair<Engine, Session> {
        val session = Session("cite" + System.nanoTime(), ws)
        return Engine(session, PcSettings(permissionMode = "auto"), builtinTools(), Always(),
            { e -> events += e }, Scripted(turns)) to session
    }

    @Test
    fun `a search_knowledge round hands the hits over as structured citations`() {
        val kb = termKb()
        val ws = workspace()
        val events = mutableListOf<Ev>()
        val (engine, _) = engine(
            ws,
            mutableListOf(
                turn("先查库", listOf(ToolCall("c1", "search_knowledge", """{"q":"液态玻璃","kb":"$kb"}"""))),
                turn("按库里的说法答")
            ),
            events
        )
        engine.submit("液态玻璃是什么")

        val snap = engine.citesSnapshot()
        assertTrue("检索跑成功了却没交出出处：${engine.messages().last { it.role == "tool" }.content}",
            snap.isNotEmpty())
        val f = snap.first().second
        assertEquals("引用库", f.kbName)
        assertEquals("术语.md", f.doc)
        assertEquals("没配 embedUrl 就该标字面", "字面", f.how)
        assertTrue("片段里要真有那句话：" + f.snippet, f.snippet.contains("液态玻璃"))
        // 出处同时随 ToolEnd 事件流出去（界面上实时那一画靠它）
        val end = events.filterIsInstance<Ev.ToolEnd>().first { it.name == "search_knowledge" }
        assertTrue("事件里没带 cites：卡片只能显示「查过了」而不能显示「查到了哪几段」", end.cites.isNotEmpty())
        assertEquals(end.id, snap.first().first)   // 事件与清单指的是同一次调用
    }

    @Test
    fun `citations are keyed by tool_call id and come back when the session reopens`() {
        val kb = termKb("重开库")
        val ws = workspace()
        val (engine, session) = engine(
            ws,
            mutableListOf(
                turn("查一下", listOf(ToolCall("ck1", "search_knowledge", """{"q":"液态玻璃","kb":"$kb"}"""))),
                turn("答完了")
            )
        )
        engine.submit("液态玻璃是什么")
        engine.persistNow()

        // 盘上：cid 必须等于那条 tool 消息的 tool_call_id —— 界面就是按这对上的
        val root = kotlinx.serialization.json.Json.parseToJsonElement(session.file.readText()).jsonObject
        val toolCid = root["messages"]!!.jsonArray.map { it.jsonObject }
            .firstOrNull { it["name"]?.jsonPrimitive?.contentOrNull == "search_knowledge" }
            ?.get("tool_call_id")?.jsonPrimitive?.contentOrNull
        assertNotNull("历史里没有那条 tool 消息", toolCid)
        val stored = root["cites"]!!.jsonArray.map { it.jsonObject }
        assertTrue("落盘的引用没挂在那条 tool 消息的 id 上：" + stored,
            stored.any { it["cid"]?.jsonPrimitive?.contentOrNull == toolCid })
        assertEquals("重开库", stored.first()["kbName"]?.jsonPrimitive?.contentOrNull)

        // 重开（另起一个引擎读同一份会话文件）—— 刷新页面走的就是这条路
        val again = Session(session.id, ws)
        val reopened = Engine(again, PcSettings(permissionMode = "auto"), builtinTools(), Always(),
            { }, Scripted(mutableListOf()))
        val snap = reopened.citesSnapshot()
        assertTrue("重开之后引用没了：界面上那一行只活过一次", snap.isNotEmpty())
        assertEquals(toolCid, snap.first().first)
    }

    @Test
    fun `a failed or empty search contributes no citations`() {
        val ws = workspace()
        val (engine, _) = engine(
            ws,
            mutableListOf(
                // 库 id 不存在 → 工具报错，这时候不能把上一次成功检索的出处挂过来
                turn("查个不存在的库", listOf(ToolCall("bx", "search_knowledge", """{"q":"液态玻璃","kb":"没有这个库"}"""))),
                turn("那我不查了")
            )
        )
        engine.submit("液态玻璃是什么")
        assertTrue("失败的检索不该留下引用", engine.citesSnapshot().isEmpty())
        val end = engine.messages().last { it.role == "tool" }
        val text = end.content!!
        assertTrue("工具该老实说没查到，而不是硬凑：" + text,
            text.contains("没有可用的知识库") || text.contains("没查到"))
    }

    /**
     * 只读分享快照也要带出处。
     *
     * 这里刻意**照抄服务端那两行映射**（messages → Row(带 cid)、citesSnapshot → 按 cid 分组）：
     * 快照的错从来不在渲染器里，而在"配对的那一步"。只测 `Share.render` 喂一份手搓的 map，
     * 产品里那两行写错了照样全绿。
     */
    @Test
    fun `the read-only snapshot carries the same citations`() {
        val kb = termKb("快照库")
        val ws = workspace()
        val (engine, _) = engine(
            ws,
            mutableListOf(
                turn("查一下", listOf(ToolCall("sh1", "search_knowledge", """{"q":"液态玻璃","kb":"$kb"}"""))),
                turn("答完了")
            )
        )
        engine.submit("液态玻璃是什么")

        val rows = engine.messages().map {
            Share.Row(it.role, it.name ?: "", it.content ?: "", it.callId ?: "")
        }
        val cs = engine.citesSnapshot()
            .groupBy({ it.first }, { Share.CiteLine(it.second.kbName, it.second.doc, it.second.how, it.second.snippet) })
        val html = Share.render("液态玻璃是什么", "", "mock", "auto", 1_700_000_000_000L, rows, cs)

        assertTrue("快照里没有库名：" + html.takeLast(600), html.contains("快照库"))
        assertTrue("快照里没有文档名", html.contains("术语.md"))
        assertTrue("快照里没有命中的那段原文", html.contains("背景模糊后叠上来"))
        assertTrue("出处要有自己的样式段（不混在工具那一行的截断文本里）", html.contains("class=\"cite\""))
        // 负判据：没有出处的行不许自己长出一行"参考了知识库"
        val bare = Share.render("没查库", "", "mock", "auto", 1_700_000_000_000L, rows, emptyMap())
        assertFalse("喂空清单还画出了出处", bare.contains("class=\"cite\""))
        // 三元组那条老入口不能被改坏（ShareTest 与 CLI 还在用）
        assertTrue(
            Share.render("旧入口", "", "mock", "auto", 1_700_000_000_000L,
                listOf(Triple("user", "", "你好"))).contains("你好")
        )
    }

    @Test
    fun `deleting the round takes its citations with it`() {
        val kb = termKb("删句库")
        val ws = workspace()
        val (engine, session) = engine(
            ws,
            mutableListOf(
                turn("查一下", listOf(ToolCall("dz1", "search_knowledge", """{"q":"液态玻璃","kb":"$kb"}"""))),
                turn("答完了")
            )
        )
        engine.submit("液态玻璃是什么")
        assertEquals(1, engine.citesSnapshot().size)
        val at = engine.messages().indexOfFirst { it.role == "user" }
        val (n, _) = engine.deleteAt(at)
        assertTrue("没删掉任何东西（判据要的是真的删）", n > 0)

        val root = kotlinx.serialization.json.Json.parseToJsonElement(session.file.readText()).jsonObject
        val cites = root["cites"]?.jsonArray ?: kotlinx.serialization.json.JsonArray(emptyList())
        assertTrue("话都删了还留着出处：" + cites, cites.isEmpty())
        assertTrue(engine.citesSnapshot().isEmpty())
    }
}
