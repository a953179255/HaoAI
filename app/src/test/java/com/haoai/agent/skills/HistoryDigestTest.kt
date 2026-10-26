package com.haoai.agent.skills

import com.haoai.agent.agent.skills.HistoryDigest
import com.haoai.agent.data.StoredMessage
import com.haoai.agent.data.StoredSession
import com.haoai.agent.data.StoredToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「从历史会话学习」素材管线 Hermes 化单测：全量转录含工具链、
 * 复盘会话防递归、预算截断、实质会话筛选。
 */
class HistoryDigestTest {

    private fun session(
        title: String,
        updatedAt: Long,
        messages: List<StoredMessage>
    ) = StoredSession(
        id = title, title = title, createdAt = updatedAt - 1, updatedAt = updatedAt,
        messages = messages.toMutableList()
    )

    private fun msg(role: String, content: String = "", error: Boolean = false,
                    toolName: String? = null, toolCalls: List<StoredToolCall> = emptyList()) =
        StoredMessage(role = role, content = content, error = error,
            toolName = toolName, toolCalls = toolCalls)

    @Test
    fun `工具调用链完整渲染进素材`() {
        val s = session("修bug", 100, listOf(
            msg("user", "帮我修这个编译错误"),
            msg("assistant", "我来看看", toolCalls = listOf(
                StoredToolCall("1", "bash", "{\"command\":\"gradle build\"}")
            )),
            msg("tool", "BUILD FAILED ...", toolName = "bash"),
            msg("assistant", "换个方法", toolCalls = listOf(
                StoredToolCall("2", "bash", "{\"command\":\"gradle clean build\"}")
            )),
            msg("tool", "BUILD SUCCESSFUL", toolName = "bash"),
            msg("assistant", "修好了：clean 之后再 build")
        ))
        val out = HistoryDigest.render(listOf(s))
        assertTrue("调用与参数进素材", out.contains("▸ 调用工具 bash"))
        assertTrue("失败结果进素材", out.contains("BUILD FAILED"))
        assertTrue("成功结果进素材", out.contains("BUILD SUCCESSFUL"))
        assertTrue("会话头带消息数", out.contains("（6 条消息）"))
    }

    @Test
    fun `复盘会话不作为素材防递归`() {
        val review = session("技能复盘 · 10-24", 200, listOf(msg("user", "x"), msg("assistant", "y")))
        val normal = session("正常任务", 100, listOf(msg("user", "任务A"), msg("assistant", "完成")))
        val out = HistoryDigest.render(listOf(review, normal))
        assertFalse(out.contains("技能复盘"))
        assertTrue(out.contains("正常任务"))
    }

    @Test
    fun `智能标题改名的复盘会话仍被指纹排除`() {
        // 实测：智能标题把"技能复盘 · …"改名成"安全命令审批技能复盘"，标题前缀过滤失效
        val renamed = session("安全命令审批技能复盘", 200, listOf(
            msg("user", "[技能复盘] 下面附上了最近几个会话的对话节选。"),
            msg("assistant", "（旧摘要正文）")
        ))
        val normal = session("正常任务", 100, listOf(msg("user", "任务A")))
        val out = HistoryDigest.render(listOf(renamed, normal))
        assertFalse("改名复盘会话不得混入素材", out.contains("安全命令审批技能复盘"))
        assertFalse("旧摘要不得随素材带入", out.contains("旧摘要正文"))
        assertTrue(out.contains("正常任务"))
    }

    @Test
    fun `工具失败带标记_正文截断到2000字`() {
        val s = session("长文", 100, listOf(
            msg("user", "长任务"),
            msg("assistant", "超长正文".repeat(500)), // 5000 字
            msg("tool", " boom", error = true, toolName = "bash")
        ))
        val out = HistoryDigest.render(listOf(s))
        assertTrue("错误标记", out.contains("◂失败"))
        assertFalse("正文截 2000", out.contains("超长正文".repeat(2001)))
    }

    @Test
    fun `预算截断有标注_且不超预算`() {
        val msgs = (1..200).map { msg("user", "消息$it ".repeat(20)) } // 每条 ~640 字
        val s = session("超长会话", 100, msgs)
        val out = HistoryDigest.render(listOf(s), budgetChars = 5000)
        assertTrue("截断标注", out.contains("已达复盘素材预算"))
        // 预算约束素材本体；截断标注行是元信息，允许 ~100 字余量
        assertTrue("素材不超预算（含标注余量）", out.length <= 5000 + 100)
    }

    @Test
    fun `只取最近3个实质会话_空会话跳过`() {
        val empty = session("空会话", 400, listOf(msg("assistant", "没用户消息")))
        val noText = session("纯工具", 300, listOf(msg("tool", "r", toolName = "bash")))
        val s1 = session("会话1", 200, listOf(msg("user", "a"), msg("assistant", "b")))
        val s2 = session("会话2", 100, listOf(msg("user", "c")))
        val s3 = session("会话3", 50, listOf(msg("user", "d")))
        val s4 = session("会话4", 10, listOf(msg("user", "e")))
        val out = HistoryDigest.render(listOf(empty, noText, s1, s2, s3, s4))
        assertFalse("无用户消息的跳过", out.contains("空会话") || out.contains("纯工具"))
        assertFalse("只取最近 3 个", out.contains("会话4"))
        assertTrue(out.contains("会话1") && out.contains("会话2") && out.contains("会话3"))
    }

    @Test
    fun `全部无实质会话返回空`() {
        val review = session("技能复盘 · 10-24", 100, listOf(msg("user", "x")))
        assertEquals("", HistoryDigest.render(listOf(review)))
        assertEquals("", HistoryDigest.render(emptyList()))
    }
}
