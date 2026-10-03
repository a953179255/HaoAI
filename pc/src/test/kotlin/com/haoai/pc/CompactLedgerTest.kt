package com.haoai.pc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 「上下文压缩自己那一次模型调用」必须进 Token 统计（对标 Octop 第 4 条的口径问题）。
 *
 * Octop 那张图顶部挂了一句"部分消耗（如记忆总结、召回等）尚未纳入统计"。
 * HaoAI 这边只有这一个**非回合**的模型调用点，所以能做得更实：直接记进账本，
 * 并在页面上写清它是系统花的、不算回合。
 *
 * 三段主张：
 * 1. 这一笔**真的落了账**，且 `kind=compact`（不是冒充成一个回合）；
 * 2. 会话累计（右栏那个数）**也把它算进去**了 —— 只落账不累加，界面上两处数会对不上；
 * 3. 报表的 `compact` 计数与账本一致（口径只有一处：`aggFull`）。
 */
class CompactLedgerTest {

    companion object {
        private lateinit var home: File

        @BeforeClass
        @JvmStatic
        fun setUp() {
            home = Files.createTempDirectory("haoai-compact-home").toFile()
            System.setProperty("haoai.home", File(home, "home").absolutePath)
        }
    }

    private fun workspace(): File = Files.createTempDirectory("haoai-compact-ws").toFile().apply { mkdirs() }

    /**
     * 认得出"这是压缩在问话"的假客户端：压缩那次**不带工具表**（见 `Engine.compactOnce`
     * 里那句 `client.chat(listOf(...), emptyList())`），而回合调用一定带 `schemas()`。
     * 别用提示词里的某个词去认 —— 提示文案一改，这条用例就会静默地不再触发，
     * 表现是"账本里少了那一行"，而原因藏在压缩没被认出来这件事里。
     */
    private class CClient(private val turns: MutableList<AssistantTurn>) : ChatClient {
        var compactCalls = 0
        override fun chat(messages: List<Msg>, tools: List<ToolSchema>, onText: (String) -> Unit): AssistantTurn {
            if (tools.isEmpty()) {
                compactCalls++
                // 长度要过 40 字这道"模型摘要才作数"的闸，否则会被机器兜底替掉
                val text = "压缩出来的摘要：前面读了三份资料并改了两个文件，" +
                    "结论是保持现状，剩下的尾巴交给保留窗口。"
                onText(text)
                return AssistantTurn(text, emptyList(), Usage(1200, 30, 700), "stop")
            }
            val t = if (turns.isNotEmpty()) turns.removeAt(0)
            else AssistantTurn("收尾", emptyList(), Usage(), "stop")
            onText(t.text)
            return t
        }
    }

    private class Yes : Gate {
        override fun approve(title: String, detail: String, kind: String) = true
        override fun ask(question: String, options: List<String>) = options.firstOrNull() ?: ""
        override fun ask(req: AskReq) = ask(req.question, req.options.map { it.label })
    }

    @Test
    fun `a compounding round books its own model call into the ledger`() {
        val sid = "cmp" + System.nanoTime()
        val session = Session(sid, workspace())
        session.mode = "auto"
        // 触发线压到很小：几轮之后正文就超线，别为了测这件事写二十轮
        val settings = PcSettings(
            permissionMode = "auto",
            compactTriggerChars = 600,
            compactKeepTail = 4
        )
        val turns = mutableListOf(
            // 第一答给一大段正文，让后面几轮的开头真的超线
            AssistantTurn("先记下这份资料：" + "资".repeat(1200), emptyList(), Usage(500, 20, 0), "stop")
        )
        val client = CClient(turns)
        val engine = Engine(session, settings, builtinTools(), Yes(), { }, client)
        engine.submit("第一轮：把这份资料读一遍并总结")
        /*
         * 压缩只在**回合开头**发生（`maybeCompact` 在请求之前），而且头部不足 4 条时
         * 压了反而更啰嗦 —— 所以一次 submit 不可能触发它。这里连着说到第五轮：
         * 前四轮攒出 8 条历史 + 一段长正文，第五轮开头才真的折。
         * 这条用例顺带钉住这个形状：如果哪天"压一次"变成"每轮都压"，compact 行数会翻，
         * 判据就会红在这里（迟滞那条判据在 CompressTest 里）。
         */
        repeat(3) { engine.submit("第 ${it + 2} 轮：继续") }
        engine.submit("第五轮：现在给我结论")

        assertTrue("压缩那一次没被真发出去（compact 行数=${
            UsageLedger.rows().count { it.sid == sid && it.kind == UsageLedger.COMPACT }}）",
            client.compactCalls >= 1)
        val mine = UsageLedger.rows().filter { it.sid == sid }
        val compact = mine.filter { it.kind == UsageLedger.COMPACT }
        assertTrue("账本里没有 kind=compact 的那一行：" + mine.map { it.kind }, compact.isNotEmpty())
        assertEquals("一次压缩该落一行", client.compactCalls, compact.size)
        assertEquals(1200, compact.first().prompt)
        assertEquals(30, compact.first().completion)
        assertEquals("缓存命中也要带上（那是要折扣计费的）", 700, compact.first().cached)
        // 会话累计也要把它算进去：只落账不累加，右栏那个数与账本会对不上。
        // 用 >= 而不是 ==：回合那一次的 usage 由引擎按重试/降级累加，具体数不是这条主张的重点，
        // "压缩那 1200/30 进了累计"才是 —— 少了它，右边那个数就永远比账本小一截。
        assertTrue("会话累计没把压缩那次算进去：prompt=" + engine.totalPrompt,
            engine.totalPrompt >= 1200L)
        assertTrue("会话累计没把压缩那次算进去：completion=" + engine.totalCompletion,
            engine.totalCompletion >= 30L)
    }

    @Test
    fun `the report counts compactions from the same place the rows come from`() {
        // 自己落一行再读报表：不依赖上一条用例跑没跑（同一个 JVM 里用例顺序不保证）
        val sid = "rpt" + System.nanoTime()
        UsageLedger.add("m-rpt", sid, 10, 5, 20, true, 0, "", "", UsageLedger.COMPACT)
        val json = UsageLedger.report(0L, 0L, "", "")
        // 只钉"这个口径存在且不为 0"：报表是一整串手搓 JSON，逐字符钉它等于把实现钉死，
        // 而"数对不对"这件事上面那条用例已经按行钉过了。
        assertTrue("报表里没有 compact 这个口径：" + json.take(240), json.contains("\"compact\":"))
        assertTrue("compact 计数没把这一行算进去", Regex("\"compact\":[1-9]").containsMatchIn(json))
        // 反向：按 kind=main 筛的时候不许把压缩混进去
        val main = UsageLedger.pick(kind = UsageLedger.MAIN)
        assertTrue("按回合筛却拿到了压缩行", main.none { it.sid == sid })
    }
}
