package com.haoai.agent.agent.engine

import com.haoai.agent.agent.model.ChatMessage
import com.haoai.agent.data.StoredMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 历史窗口头锚定：条数/预算裁剪发生时，最早的任务原话必须留在窗口里并挂裁剪标记。
 * 91ba4cc9 会话实测：停止后模型看不到会话开头的任务，把「查 LOL 赛程」猜成
 * 「分析新闻简报 skill」——回归锁。
 */
class HistoryWindowHeadAnchorTest {

    private fun user(c: String) = StoredMessage(role = ChatMessage.ROLE_USER, content = c)
    private fun assistant(c: String = "") = StoredMessage(role = ChatMessage.ROLE_ASSISTANT, content = c)

    @Test
    fun `no trim returns window unchanged`() {
        val msgs = listOf(user("task"), assistant("hi"), user("next"))
        assertEquals(msgs, historyWindowWithHeadAnchor(msgs, 0))
    }

    @Test
    fun `trim re-anchors first real user message with marker`() {
        val msgs = listOf(
            user("[系统提示] 对话历史即将超出上下文窗口…"), // 系统注入不算任务头
            assistant("work"),
            user("查询 LOL 世界赛瑞士轮第三天的赛程"),
            assistant("doing", ),
            user("继续")
        )
        val out = historyWindowWithHeadAnchor(msgs, 3)
        assertEquals(1 + 2, out.size) // 头 + 窗口
        val head = out.first()
        assertTrue(head.content.startsWith("[系统] 本会话更早的 3 条历史"))
        assertTrue(head.content.contains("查询 LOL 世界赛瑞士轮第三天的赛程"))
        // 标记点名了可用的回查工具
        assertTrue(head.content.contains("session_search"))
        assertEquals(msgs.drop(3), out.drop(1))
    }

    @Test
    fun `head already inside window returns window unchanged`() {
        // 头消息落在窗口起点或之后（窗口从系统注入之后开始切）→ 不再加锚
        val msgs = listOf(
            user("[系统提示] nudge"),
            user("task"),
            assistant("a"),
            user("b")
        )
        val out = historyWindowWithHeadAnchor(msgs, 1)
        assertEquals(msgs.drop(1), out)
    }

    @Test
    fun `no real user head falls back to bare window`() {
        val msgs = listOf(assistant("only assistant"), user("[系统恢复] 中断续跑"))
        val out = historyWindowWithHeadAnchor(msgs, 1)
        assertEquals(msgs.drop(1), out)
    }

    @Test
    fun `oversized head content is capped`() {
        val big = user("任务" + "x".repeat(5000))
        val msgs = listOf(big, assistant("a"), user("b"))
        val out = historyWindowWithHeadAnchor(msgs, 2)
        assertEquals(2, out.size)
        assertTrue(out.first().content.length < 5000)
        assertTrue(out.first().content.contains("[…原始消息过长已截断]"))
        assertTrue(out.first().content.contains("任务"))
        assertFalse(out.first().content.endsWith("x"))
    }
}
