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
