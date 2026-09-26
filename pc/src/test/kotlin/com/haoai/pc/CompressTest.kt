package com.haoai.pc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 上下文压缩的回归。
 *
 * 这套测试只盯一件事：**压缩不能把会话弄坏**。判据不是"摘要写得好不好"
 * （那要真模型才能评，见任务 #38），而是三条硬的：
 * ① 压完之后发给模型的窗口里不许出现"孤儿 tool 回复"（有回复没有对应的调用 ⇒ 网关 400）；
 * ② 模型不可用时必须还能压（兜底摘要），否则省 token 的机制自己成了故障点；
 * ③ 摘要与水位要跟着会话落库，重开不能丢、也不能把同一段压第二遍。
 */
class CompressTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun setUp() {
            val tmp = Files.createTempDirectory("haoai-compact-home").toFile()
            System.setProperty("haoai.home", File(tmp, "home").absolutePath)
        }
    }

    /** 脚本模型：认出"压缩提示"并按需回答，其余轮次反复读同一个大文件把窗口撑爆。 */
    private class Coder(private val onCompact: () -> String) : ChatClient {
        var rounds = 0
        var compactCalls = 0
        var lastRequest: List<Msg> = emptyList()
        override fun chat(messages: List<Msg>, tools: List<ToolSchema>, onText: (String) -> Unit): AssistantTurn {
            val last = messages.lastOrNull()?.content ?: ""
            if (last.startsWith("下面是一段更早的对话")) {
                compactCalls++
                return AssistantTurn(onCompact(), emptyList(), Usage(7, 3), "stop")
            }
            rounds++
            lastRequest = messages
            return if (rounds <= 8) AssistantTurn(
                "再读一次 big.txt",
                listOf(ToolCall("r$rounds", "read", """{"path":"big.txt"}""")),
                Usage(20, 10), "tool_calls"
            ) else AssistantTurn("结束", emptyList(), Usage(20, 10), "stop")
        }
    }

    /** 只答一轮、不产生任何新历史：用来验"重开会话"之后窗口里有什么。 */
    private class Plain : ChatClient {
        var request: List<Msg> = emptyList()
        override fun chat(messages: List<Msg>, tools: List<ToolSchema>, onText: (String) -> Unit): AssistantTurn {
            request = messages
            return AssistantTurn("收到，说完了", emptyList(), Usage(1, 1), "stop")
        }
    }

    private class AllowAll : Gate {
        override fun approve(title: String, detail: String, kind: String) = true
        override fun ask(question: String, options: List<String>) = options.firstOrNull() ?: ""
    }

    private fun ws(): File {
        val dir = Files.createTempDirectory("haoai-compact").toFile().apply { mkdirs() }
        // 一次 read 就 ~7k 字符，一两轮就过触发线
        dir.resolve("big.txt").writeText((1..200).joinToString("\n") { "第 $it 行：这是一段用来撑大上下文的中文内容" })
        return dir
    }

    private fun settings(trigger: Int = 3_000) = PcSettings(
        permissionMode = "auto", maxTurns = 30,
        compactTriggerChars = trigger, compactKeepTail = 6
    )

    private fun engine(dir: File, client: ChatClient, id: String = "c" + System.nanoTime()): Engine {
        val s = Session(id, dir)
        s.mode = "auto"
        return Engine(s, settings(), builtinTools(), AllowAll(), {}, client)
    }

    /** 发给模型的窗口里，每条 tool 回复都必须能追溯到同窗口内一次 tool_calls。 */
    private fun assertNoOrphans(msgs: List<Msg>, where: String) {
        val declared = msgs.filter { it.role == "assistant" }.flatMap { c -> c.calls.map { it.id } }.toSet()
        msgs.filter { it.role == "tool" }.forEach {
            assertTrue("$where 出现了孤儿 tool 回复 ${it.callId}（网关会直接 400）", it.callId in declared)
        }
    }

    @Test
    fun `short conversations are not compacted at all`() {
        val plain = Plain()
        val e = engine(ws(), plain)
        e.submit("说句话就好")
        assertEquals("短会话也去压了一次（白花一次模型调用）", 0, e.compactedCount)
        assertNull(e.summary)
        assertTrue("请求里出现了前情摘要", plain.request.none { (it.content ?: "").contains("【前情摘要") })
    }

    @Test
    fun `over-budget history gets folded and the request stays protocol-legal`() {
        val coder = Coder { "【模型摘要】用户一直在反复读取 big.txt；读过 8 次；没有失败；待办为空。" }
        val e = engine(ws(), coder)
        e.submit("反复读 big.txt 直到我喊停")

        assertTrue("跑了 8 轮大输出居然没触发压缩", coder.compactCalls > 0)
        assertNotNull("摘要没生成", e.summary)
        assertTrue("没用模型给的那份：${e.summary}", e.summary!!.contains("模型摘要"))
        assertTrue("水位没记：${e.compactedCount}", e.compactedCount > 0)
        // 压完之后发给模型的窗口要真的收住（原始历史 8×~7.4k ≈ 59k 字符）
        val sentChars = coder.lastRequest.sumOf { it.content?.length ?: 0 }
        assertTrue("压完没收住：$sentChars 字符", sentChars < 30_000)
        // 迟滞：尾巴本身就超线时，不该每一轮都再压一次（那等于白花模型调用）
        assertTrue("压了 ${coder.compactCalls} 次，迟滞没生效（8 轮历史最多压 4 次）",
            coder.compactCalls <= 4)
        assertNoOrphans(coder.lastRequest, "压缩后发给模型的窗口")
        assertTrue("发给模型的窗口里没有前情摘要",
            coder.lastRequest.any { (it.content ?: "").contains("【前情摘要") })
    }

    /**
     * 压缩时模型不可用 ⇒ 必须退回机器摘要。
     *
     * 这条是整件事的关键：如果压缩依赖模型，那么"网关挂了"会连带"长会话也开不了"，
     * 一个省 token 的机制就变成了新的故障点。
     */
    @Test
    fun `compaction falls back to a machine digest when the model call fails`() {
        var sawCompact = false
        val coder = object : ChatClient {
            var rounds = 0
            override fun chat(messages: List<Msg>, tools: List<ToolSchema>, onText: (String) -> Unit): AssistantTurn {
                val last = messages.lastOrNull()?.content ?: ""
                if (last.startsWith("下面是一段更早的对话")) {
                    sawCompact = true
                    throw ProviderError("网关 503", 503, true)
                }
                rounds++
                return if (rounds <= 8) AssistantTurn("再读",
                    listOf(ToolCall("q$rounds", "read", """{"path":"big.txt"}""")), Usage(9, 9), "tool_calls")
                else AssistantTurn("结束", emptyList(), Usage(9, 9), "stop")
            }
        }
        val e = engine(ws(), coder)
        e.submit("反复读 big.txt")
        assertTrue("没试过压缩", sawCompact)
        assertNotNull("模型失败后就没有摘要了", e.summary)
        assertTrue("兜底摘要没列出结构化事实：${e.summary}",
            e.summary!!.contains("调用统计") && e.summary!!.contains("big.txt"))
        assertTrue("兜底摘要不该有模型腔：${e.summary}", !e.summary!!.contains("模型摘要"))
    }

    @Test
    fun `summary and watermark survive a reload and are not regenerated`() {
        val dir = ws()
        // 摘要文本要明显长于 Engine 里"短于 40 字符就当成模型没好好答"的那条线，
        // 否则会静默退回机器兜底，测的就不是"模型摘要能不能活过重启"了。
        val first = engine(
            dir,
            Coder {
                "【模型摘要】用户要求反复读取 big.txt 以撑大上下文；已读过若干次；" +
                    "没有失败与被拒的调用；待办为空；第一段被折掉了。"
            },
            id = "reload-1"
        )
        first.submit("反复读 big.txt")
        val sum = first.summary
        assertNotNull("第一遍就没压出来", sum)
        val mark = first.compactedCount
        assertTrue("水位是 0：$mark", mark > 0)

        // 新引擎 = 重开会话：摘要与水位必须从文件里回来
        val plain = Plain()
        val second = engine(dir, plain, id = "reload-1")
        assertEquals("重开后摘要丢了", sum, second.summary)
        assertEquals("重开后水位变了", mark, second.compactedCount)
        second.submit("继续")
        assertTrue("重开后第一次请求就该带上前情摘要",
            plain.request.any { (it.content ?: "").contains("【前情摘要") })
        assertTrue("摘要文本没进窗口：${plain.request.map { (it.content ?: "").take(20) }}",
            plain.request.any { (it.content ?: "").contains("第一段被折掉了") })
    }

    @Test
    fun `the compaction prompt actually carries the head it is summarising`() {
        var prompt = ""
        val coder = object : ChatClient {
            var rounds = 0
            override fun chat(messages: List<Msg>, tools: List<ToolSchema>, onText: (String) -> Unit): AssistantTurn {
                val last = messages.lastOrNull()?.content ?: ""
                if (last.startsWith("下面是一段更早的对话")) {
                    prompt = last
                    return AssistantTurn("好，摘要：反复读 big.txt。", emptyList(), Usage(1, 1), "stop")
                }
                rounds++
                return if (rounds <= 8) AssistantTurn("再读",
                    listOf(ToolCall("p$rounds", "read", """{"path":"big.txt"}""")), Usage(3, 3), "tool_calls")
                else AssistantTurn("结束", emptyList(), Usage(3, 3), "stop")
            }
        }
        engine(ws(), coder).submit("反复读 big.txt")
        assertTrue("提示里没带上要压的内容", prompt.contains("big.txt"))
        assertTrue("提示里没带上工具调用（模型无从知道读过几次）", prompt.contains("read"))
        assertTrue("提示没要求只列事实：\n${prompt.take(120)}", prompt.contains("不要下结论"))
    }

    @Test
    fun `digest counts calls and lists paths without inventing conclusions`() {
        val msgs = listOf(
            Msg("user", "把 a.txt 和 b.txt 都改一下"),
            Msg("assistant", "先写 a", calls = listOf(
                ToolCall("1", "write", """{"path":"a.txt","content":"x"}"""),
                ToolCall("2", "write", """{"path":"b.txt","content":"y"}"""))),
            Msg("tool", "规则拒绝：不允许", callId = "1", name = "write"),
            Msg("tool", "已写入", callId = "2", name = "write")
        )
        val d = Compactor.digest(msgs)
        assertTrue(d, d.contains("write×2"))
        assertTrue(d, d.contains("a.txt") && d.contains("b.txt"))
        assertTrue(d, d.contains("用户提过") && d.contains("失败或被拒"))
        assertTrue("机器摘要不该出现自创结论：$d", !d.contains("结论是"))
    }

    @Test
    fun `mergeSummary keeps the newest lines when it overflows`() {
        // 追加式水位：超限时从**上面**裁，最新压进去的那段必须还活着
        val e = engine(ws(), Plain())
        val old = (1..400).joinToString("\n") { "旧行 $it " + "长".repeat(30) }
        val fresh = "【最新】这一段必须活着"
        val out = e.mergeSummary(old, fresh)
        assertTrue("压完把最新的一段裁掉了：${out.take(80)}", out.contains(fresh))
        assertTrue("没裁到上限以内：${out.length}", out.length <= 8_200)
        assertTrue("没说明裁了什么：${out.take(40)}", out.contains("已省略"))
    }
}
