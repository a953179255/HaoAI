package com.haoai.agent.agent.model

import kotlinx.serialization.Serializable

@Serializable
data class ToolCallData(
    val id: String,
    val name: String,
    val argumentsJson: String
)

@Serializable
data class ChatMessage(
    val role: String,
    val content: String = "",
    val toolCalls: List<ToolCallData> = emptyList(),
    val toolCallId: String? = null,
    val toolName: String? = null,
    val error: Boolean = false,
    /** 用户附加图片的 data URL（base64），仅端侧多模态模型使用。 */
    val imageData: String? = null,
    /** 思考过程文本（reasoning_content / <think>），仅展示用，不回传 API。 */
    val reasoning: String? = null,
    val ts: Long = System.currentTimeMillis()
) {
    companion object {
        const val ROLE_SYSTEM = "system"
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"
        const val ROLE_TOOL = "tool"
    }
}
