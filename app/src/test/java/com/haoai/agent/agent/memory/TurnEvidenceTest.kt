package com.haoai.agent.agent.memory

import com.haoai.agent.data.StoredMessage
import com.haoai.agent.data.StoredToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 每轮记忆提取素材单测：工具链行渲染、当轮 memory 调用检测（hermes 计数器清零语义）、
 * 预算约束、新→旧回走的保新性。
 */
class TurnEvidenceTest {

    private fun msg(
        role: String,
        content: String = "",
        error: Boolean = false,
        toolName: String? = null,
        calls: List<StoredToolCall> = emptyList()
    ) = StoredMessage(role = role, content = content, error = error, toolName = toolName, toolCalls = calls)

    // ---------- memoryToolUsedThisTurn ----------

    @Test
    fun `当轮模型调过memory工具_判定为真`() {
        val msgs = listOf(
            msg("user", "记住我对花生过敏"),
            msg("assistant", "", calls = listOf(StoredToolCall("1", "memory", "{\"action\":\"save\"}"))),
            msg("tool", "已保存", toolName = "memory"),
            msg("assistant", "好的，已记住")
        )
        assertTrue(TurnEvidence.memoryToolUsedThisTurn(msgs))
    }

    @Test
    fun `memory调用在上一轮_判定为假`() {
        val msgs = listOf(
            msg("user", "旧任务"),
            msg("assistant", "", calls = listOf(StoredToolCall("1", "memory", "{}"))),
            msg("tool", "已保存", toolName = "memory"),
            msg("user", "新问题"),
            msg("assistant", "回答")
        )
        assertFalse("上一轮的 memory 调用不应触发当轮跳过", TurnEvidence.memoryToolUsedThisTurn(msgs))
    }

    @Test
    fun `其他工具调用不触发`() {
        val msgs = listOf(
            msg("user", "跑个命令"),
            msg("assistant", "", calls = listOf(StoredToolCall("1", "bash", "{}"))),
            msg("tool", "ok", toolName = "bash"),
            msg("assistant", "完成")
        )
        assertFalse(TurnEvidence.memoryToolUsedThisTurn(msgs))
    }

    @Test
    fun `无消息返回假`() {
        assertFalse(TurnEvidence.memoryToolUsedThisTurn(emptyList()))
    }

    // ---------- buildTranscript ----------

    @Test
    fun `工具调用与结果渲染进素材_失败带标记`() {
        val msgs = listOf(
            msg("user", "修这个编译错误"),
            msg("assistant", "我试试", calls = listOf(StoredToolCall("1", "bash", "{\"command\":\"gradle build\"}"))),
            msg("tool", "BUILD FAILED", error = true, toolName = "bash"),
            msg("assistant", "", calls = listOf(StoredToolCall("2", "bash", "{\"command\":\"gradle clean build\"}"))),
            msg("tool", "BUILD SUCCESSFUL", toolName = "bash"),
            msg("assistant", "修好了")
        )
        val out = TurnEvidence.buildTranscript(msgs)
        assertTrue("调用行", out.contains("调用工具 bash（{\"command\":\"gradle build\"}）"))
        assertTrue("结果行", out.contains("工具 bash ◂结果：BUILD SUCCESSFUL"))
        assertTrue("失败标记", out.contains("◂失败"))
        assertTrue("正文行", out.contains("用户：修这个编译错误") && out.contains("助手：修好了"))
        assertEquals("时间正序", out.indexOf("修这个编译错误") < out.indexOf("BUILD FAILED"), true)
    }

    @Test
    fun `空工具结果且无错误不渲染`() {
        val msgs = listOf(
            msg("user", "任务"),
            msg("assistant", "", calls = listOf(StoredToolCall("1", "web_search", "{}"))),
            msg("tool", "", toolName = "web_search"),
            msg("assistant", "结论")
        )
        val out = TurnEvidence.buildTranscript(msgs)
        assertFalse("空结果行省略", out.contains("◂结果："))
        assertTrue(out.contains("调用工具 web_search"))
    }

    @Test
    fun `预算约束_且优先保住最新的消息`() {
        // 12 条超长用户消息（旧版口径 8 条会全留，这里验证总预算兜底）
        val msgs = (1..12).map { msg("user", "消息$it ".repeat(120)) } // 每条 ~960 字
        val out = TurnEvidence.buildTranscript(msgs)
        assertTrue("总长有界（8行×400+行头）", out.length <= 8 * 404 + 40)
        assertFalse("最旧的消息被挤出", out.contains("消息1 "))
        assertTrue("最新的消息保住", out.contains("消息12"))
    }

    @Test
    fun `全空素材返回空串`() {
        assertEquals("", TurnEvidence.buildTranscript(emptyList()))
        assertEquals("", TurnEvidence.buildTranscript(listOf(msg("assistant", ""), msg("tool", ""))))
    }
}
