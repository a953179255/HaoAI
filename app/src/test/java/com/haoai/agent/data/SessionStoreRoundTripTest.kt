package com.haoai.agent.data

import com.haoai.agent.agent.model.ChatMessage
import com.haoai.agent.agent.model.ToolCallData
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * B15 第二片（会话模型统一）的保真锁：
 * `ChatMessage`（= com.haoai.core.Msg，规范名 calls/callId/name/pt/ct/ms）
 * 经 `toStored → toModel` 往返必须**逐字段相等** —— 改名只许发生在转换边界上，
 * 磁盘 DTO（StoredMessage/StoredToolCall）一个字不动。
 *
 * 夹具刻意**不含 PC 端专属字段**（diff/sub/note/notice/images/media）：
 * 磁盘 DTO 从来不存它们（移动端也从不产生），往返丢它们是设计而非缺陷。
 */
class SessionStoreRoundTripTest {

    @Test
    fun `model to stored to model keeps every shared field`() {
        val m = ChatMessage(
            id = "m1",
            role = ChatMessage.ROLE_ASSISTANT,
            content = "正文内容",
            calls = listOf(ToolCallData("c1", "write", """{"path":"a.txt"}""")),
            callId = null,
            name = "write",
            reasoning = "想一下",
            error = true,
            imageData = "data:image/png;base64,xx",
            audioPath = "/a/b.mp3",
            videoPath = "/a/b.mp4",
            pt = 11,
            ct = 22,
            ms = 33L,
            model = "glm-x",
            ts = 123456789L
        )
        val back = m.toStored().toModel()
        assertEquals("往返逐字段相等（改名期最怕的就是这里悄悄丢一个）", m, back)
    }

    @Test
    fun `nullable dto fields fall back to non-null core defaults`() {
        // 旧 JSON 里 pt/ct/duration/toolName 缺省 null：落到 core 的非空字段要有确定语义
        val s = StoredMessage(role = ChatMessage.ROLE_TOOL, content = "结果")
        val m = s.toModel()
        assertEquals("pt 兜 0", 0, m.pt)
        assertEquals("ct 兜 0", 0, m.ct)
        assertEquals("ms 兜 0", 0L, m.ms)
        assertEquals("name 兜空串", "", m.name)
        assertEquals("空调用列表", emptyList<ToolCallData>(), m.calls)
    }

    @Test
    fun `tool result mapping round-trips callId and name`() {
        val m = ChatMessage(
            role = ChatMessage.ROLE_TOOL, content = "ok",
            callId = "call_42", name = "bash"
        )
        val st = m.toStored()
        assertEquals("磁盘字段名保持旧契约（老会话文件要能读）", "call_42", st.toolCallId)
        assertEquals("bash", st.toolName)
        assertEquals(m, st.toModel())
    }
}
