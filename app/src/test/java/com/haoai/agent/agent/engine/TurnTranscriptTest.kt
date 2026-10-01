package com.haoai.agent.agent.engine

import com.haoai.agent.agent.model.ToolCallData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TurnTranscript 契约单测：正文与思考**成对**丢弃，提交判定只有一处。
 * 引擎侧的接线（重发时真的走这里、UI 收到撤句事件）由 AgentEngineLoopTest 覆盖。
 */
class TurnTranscriptTest {

    @Test
    fun `重发丢弃时正文和思考一起清`() {
        var discarded = 0
        val t = TurnTranscript(onDiscard = { discarded++ })
        t.reasoningDelta("旧思考链")
        t.delta("半截答案")
        assertTrue(t.hasContent)
        t.discard()
        assertEquals("", t.textString())
        assertNull("思考链不得留在重试答案上", t.reasoningOrNull())
        assertFalse(t.hasContent)
        assertEquals(1, discarded)
    }

    @Test
    fun `没有残句时不惊动 UI`() {
        var discarded = 0
        val t = TurnTranscript(onDiscard = { discarded++ })
        t.discard()
        assertEquals("连一个 delta 都没收到就不该发清屏事件", 0, discarded)
    }

    @Test
    fun `空正文带工具调用仍要成条`() {
        val t = TurnTranscript()
        // 模型只调工具不回话时正文为空，但协议要求 assistant 的 tool_calls 必须落一条消息
        val msg = t.buildAssistant(listOf(ToolCallData("c1", "bash", "{}")))
        assertEquals("assistant", msg?.role)
        assertEquals(1, msg?.calls?.size)
        assertNull(msg?.reasoning)
    }

    @Test
    fun `纯空白不成条`() {
        val t = TurnTranscript()
        t.delta("   \n ")
        assertNull("不该往历史里塞空气泡", t.buildAssistant())
        assertFalse(t.hasContent)
    }
}
