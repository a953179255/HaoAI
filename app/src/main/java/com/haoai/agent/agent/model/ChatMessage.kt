package com.haoai.agent.agent.model

import kotlinx.serialization.Serializable
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * 兜底 tool_call id 的序号：**进程内单调**，绝不按轮/按响应重置。
 *
 * 为什么单独拎出来：不少供应商流式不带 tool_call id（deepseek-v4-flash 直接回 `"id": ""`，
 * 回传空 id 会被服务端 400 拒），客户端只能自己生成。原先两个客户端各自用「本轮下标」生成
 * `call_0_0 / call_1_1 …`，计数器每轮归零 → 一个 15 轮的回合只产出 4 个不同 id，
 * 引擎与 UI 的按-id 查表全部跨轮串号（后果与根治点见 engine 的 [uniquifyCallIds]）。
 */
private val toolCallIdSeq = AtomicLong()

/** 生成一个不会与上一轮重复的兜底 call id。 */
fun newFallbackCallId(prefix: String = "call"): String = "${prefix}_${toolCallIdSeq.incrementAndGet()}"

@Serializable
data class ToolCallData(
    val id: String,
    val name: String,
    val argumentsJson: String
)

@Serializable
data class ChatMessage(
    /** 消息唯一 id（旧 JSON 缺省时补新生成，用于长按操作/截断/搜索定位）。 */
    val id: String = UUID.randomUUID().toString(),
    val role: String,
    val content: String = "",
    val toolCalls: List<ToolCallData> = emptyList(),
    val toolCallId: String? = null,
    val toolName: String? = null,
    val error: Boolean = false,
    /** 用户附加图片的 data URL（base64），仅端侧多模态模型使用。 */
    val imageData: String? = null,
    /** 用户附加音频的本机文件路径（应用私有 attachments 目录）；模型有 audio-in 时读取转 input_audio。 */
    val audioPath: String? = null,
    /** 用户附加视频的本地文件路径（应用私有目录）；引擎注记路径让 Agent 用 ffmpeg 抽帧/抽音轨绕行。 */
    val videoPath: String? = null,
    /** 思考过程文本（reasoning_content / <think>），仅展示用，不回传 API。 */
    val reasoning: String? = null,
    /** 本轮（含工具循环多次调用）累计输入 tokens，assistant 消息统计行用。 */
    val promptTokens: Int? = null,
    /** 本轮累计输出 tokens。 */
    val completionTokens: Int? = null,
    /** 整轮耗时（用户发出→回复落库，含工具执行），毫秒。 */
    val durationMs: Long? = null,
    /** 生成该回复的模型名（统计行/操作面板元信息展示）。 */
    val model: String? = null,
    val ts: Long = System.currentTimeMillis()
) {
    companion object {
        const val ROLE_SYSTEM = "system"
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"
        const val ROLE_TOOL = "tool"
    }
}
