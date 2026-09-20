package com.haoai.agent.data

import com.haoai.agent.agent.model.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「中断是数据不是标志位」的判定层单测：断在哪一步、能不能续、续之前要不要核实副作用，
 * 全部由已落库的消息推出来（纯函数），UI 与引擎共用同一份结论。
 */
class InterruptionVerdictTest {

    private fun user(t: String) = StoredMessage(role = ChatMessage.ROLE_USER, content = t)
    private fun say(t: String, done: Boolean = false) = StoredMessage(
        role = ChatMessage.ROLE_ASSISTANT, content = t,
        durationMs = if (done) 1000L else null
    )
    private fun call(id: String, name: String = "bash") = StoredMessage(
        role = ChatMessage.ROLE_ASSISTANT, content = "",
        toolCalls = listOf(StoredToolCall(id, name, "{}"))
    )
    private fun result(id: String, name: String = "bash") = StoredMessage(
        role = ChatMessage.ROLE_TOOL, content = "ok", toolCallId = id, toolName = name
    )

    @Test
    fun `已发起未回结果的调用被识别为dangling`() {
        val open = listOf(user("做点事"), call("c1"))
        assertEquals(listOf("c1"), StoredSession.danglingCalls(open).map { it.id })
        assertEquals(StoredSession.TAIL_DANGLING_CALLS, StoredSession.tailShapeOf(open))
        val closed = listOf(user("做点事"), call("c1"), result("c1"))
        assertTrue(StoredSession.danglingCalls(closed).isEmpty())
        assertEquals(StoredSession.TAIL_TOOL, StoredSession.tailShapeOf(closed))
    }

    @Test
    fun `只带工具调用的assistant不再被当空消息跳过`() {
        // 旧判据只看 content，工具循环里"没回话只调工具"的那条会被跳过，
        // 于是"停在工具执行中"被误报成更早的形态
        val msgs = listOf(user("第一步"), say("好的"), call("c9", "web_search"), result("c9", "web_search"))
        assertEquals(StoredSession.TAIL_TOOL, StoredSession.tailShapeOf(msgs))
    }

    @Test
    fun `答完之后才断的会话判为已完成`() {
        val done = listOf(user("问题"), say("完整回答", done = true))
        assertTrue(StoredSession.lastRoundCompleted(done))
        assertFalse(StoredSession.lastRoundCompleted(listOf(user("问题"), say("半截"))))
        val note = StoredSession.interruptionNote(StoredSession.RUN_INTERRUPTED, done)
        assertTrue("该告诉用户不必续跑：$note", note.contains("已经答完"))
    }

    @Test
    fun `判决文案按断点分道`() {
        val dangling = listOf(user("跑个命令"), call("c1"))
        assertTrue(
            StoredSession.interruptionNote(StoredSession.RUN_INTERRUPTED, dangling).contains("副作用")
        )
        assertEquals(
            "消息已发出但任务未开始执行，可继续",
            StoredSession.interruptionNote(StoredSession.RUN_INTERRUPTED, listOf(user("还没人理")))
        )
        assertTrue(
            StoredSession.interruptionNote(StoredSession.RUN_TURNCAPPED, emptyList())
                .contains("轮数上限")
        )
        // 尾部形态优先于结束状态：turncapped 但明显断在生成中途时，说断点更有用
        assertEquals(
            "回答生成到一半中断，可继续生成",
            StoredSession.interruptionNote(StoredSession.RUN_TURNCAPPED, listOf(say("半截")))
        )
    }
}
