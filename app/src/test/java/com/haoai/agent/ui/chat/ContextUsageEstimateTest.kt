package com.haoai.agent.ui.chat

import com.haoai.agent.agent.model.ChatMessage
import com.haoai.agent.agent.tools.TextCap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 上下文估算口径。它决定"什么时候压缩"和"压缩后删多少历史"，
 * 口径偏了不会报错，只会表现为提前变傻——所以要有数值得钉住。
 */
class ContextUsageEstimateTest {

    private fun toolMsg(id: String, content: String) =
        ChatMessage(role = ChatMessage.ROLE_TOOL, content = content, toolCallId = id, toolName = "web_fetch")

    private fun userMsg(text: String) = ChatMessage(role = ChatMessage.ROLE_USER, content = text)

    /** 模拟一轮工具密集的任务：100 组 (user + 16000 字符工具结果)，共 200 条。 */
    private fun toolHeavySession(): List<ChatMessage> = buildList {
        repeat(100) { i ->
            add(userMsg("第 $i 步指令"))
            add(toolMsg("c$i", "x".repeat(16_000)))
        }
    }

    /** 真实请求只发最近 MAX_HISTORY=80 条，且每条 tool 结果按 REQ_CAP=4000 中间截断。 */
    @Test
    fun requestWindowEstimateIsFarBelowNaiveFullHistoryEstimate() {
        val msgs = toolHeavySession()
        val naive = msgs.sumOf { ContextUsage.estimateMessageTokens(it) }
        val real = ContextUsage.estimateRequestHistoryTokens(msgs, 80, 4_000)

        assertTrue("新口径应显著低于全量口径（naive=$naive real=$real）", real * 3 < naive)
        // 钉住量级：全量高估到 6 倍以上时，说明「提前压缩」的病灶又回来了
        assertTrue("预期高估 6 倍以上，实际 naive/real=${naive.toFloat() / real}", naive.toFloat() / real > 6f)
    }

    /** 截断必须与请求用同一个 TextCap.middle（head65%/tail25%+省略标记），自己 take() 会算少。 */
    @Test
    fun toolCapMatchesRequestTruncationExactly() {
        val long = "结".repeat(9_000) + "tail".repeat(1_000)
        val msg = toolMsg("c1", long)
        val expected = 4 + ContextUsage.estimateStringTokens(TextCap.middle(long, 4_000))
        assertEquals(expected, ContextUsage.estimateMessageTokens(msg, 4_000))
    }

    /** 选窗必须落在用户消息边界上：不能把 assistant(tool_calls)+tool 序列拦腰切开。 */
    @Test
    fun windowStartAlignsToBoundary() {
        val msgs = buildList {
            add(userMsg("边界0"))
            repeat(6) { add(toolMsg("c$it", "y".repeat(4000))) }   // 每条约 1000 token
        }
        val start = ContextUsage.windowStart(
            msgs,
            { ContextUsage.estimateMessageTokens(it, 4_000) },
            { it.role == ChatMessage.ROLE_USER },
            budgetTokens = 2500,
            maxMessages = 80
        )
        // 前推只在「后面还有边界消息」时发生；没有就停在原起点——宁可发中段也不返回空历史。
        // 每条工具结果约 1004 token，预算 2500 → 只容得下尾部 2 条 → 起点 5，其后没有 user 可对齐。
        assertEquals("预算只够 2 条时，无边界可前推则保持原起点", 5, start)
        val secondBoundary = buildList {
            add(userMsg("旧边界")); add(toolMsg("a", "y".repeat(4000)))
            add(userMsg("新边界")); add(toolMsg("b", "y".repeat(4000)))
        }
        val s2 = ContextUsage.windowStart(
            secondBoundary,
            { ContextUsage.estimateMessageTokens(it, 4_000) },
            { it.role == ChatMessage.ROLE_USER },
            budgetTokens = 1100,
            maxMessages = 80
        )
        assertEquals("应选中最后一个用户边界开始的段落", 2, s2)
    }

    /** token 预算说话，条数只是保险：短消息可以多发、长消息必须少发。 */
    @Test
    fun budgetDrivesSelectionNotMessageCount() {
        val shorts = List(200) { userMsg("短句 $it") }
        fun start(budget: Int, max: Int) = ContextUsage.windowStart(
            shorts, { ContextUsage.estimateMessageTokens(it, 4_000) }, { true }, budget, max
        )
        assertEquals("预算充足时受条数上限约束", 120, start(1_000_000, 80))
        // 预算用被测函数自身导出，别手算 token（CJK 混合长度容易差一个）
        val one = ContextUsage.estimateMessageTokens(shorts.last(), 4_000)
        assertEquals("预算刚好一条时只留最后一条", shorts.size - 1, start(one, 80))
        assertEquals("两条预算就带上倒数第二条", shorts.size - 2, start(one * 2, 80))
        assertTrue("预算极紧也不返回空历史", start(1, 80) < shorts.size)
    }

    /** 短会话（未越过窗口）两种口径应当只差截断，不该把历史整段丢掉。 */
    @Test
    fun shortSessionIsNotTruncatedByWindow() {
        val msgs = listOf(userMsg("你好"), toolMsg("c1", "y".repeat(200)))
        assertEquals(
            msgs.sumOf { ContextUsage.estimateMessageTokens(it, 4_000) },
            ContextUsage.estimateRequestHistoryTokens(msgs, 80, 4_000)
        )
    }
}
