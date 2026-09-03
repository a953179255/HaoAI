package com.haoai.agent.ui.chat

import androidx.compose.ui.graphics.Color
import com.haoai.agent.agent.model.ChatMessage
import com.haoai.agent.agent.model.ToolCallData

data class ContextUsage(
    val usedTokens: Int,
    val totalTokens: Int,
    val systemTokens: Int,
    val toolsTokens: Int,
    val historyTokens: Int,
    /** 系统提示中的动态注入块（记忆/技能索引/日志/MCP 摘要/预算提示），与基础系统提示分列 */
    val injectedTokens: Int = 0,
    /** 上下文压缩摘要：真实请求作为独立 system 消息注入，此前完全没计入 */
    val summaryTokens: Int = 0,
    /** 单次回复上限（max_tokens）：占用窗口但非"已用"，剩余可用需扣除 */
    val reservedTokens: Int = 0
) {
    val percentage: Float get() = if (totalTokens > 0) usedTokens.toFloat() / totalTokens else 0f
    val remainingTokens: Int get() = (totalTokens - usedTokens - reservedTokens).coerceAtLeast(0)

    fun progressColor(safe: Color, warn: Color, danger: Color, critical: Color): Color = when {
        percentage > 0.9f -> critical
        percentage > 0.75f -> danger
        percentage > 0.5f -> warn
        else -> safe
    }

    companion object {
        private const val CJK_RATIO = 1.5
        private const val OTHER_RATIO = 0.25
        private const val MSG_OVERHEAD = 4
        private const val IMAGE_TOKENS = 2000
        private const val SYSTEM_BASE_TOKENS = 3000

        fun estimateStringTokens(text: String): Int {
            if (text.isEmpty()) return 0
            var cjk = 0
            for (ch in text) {
                if (ch.code in 0x4E00..0x9FFF || ch.code in 0x3400..0x4DBF) cjk++
            }
            val other = text.length - cjk
            return (cjk * CJK_RATIO + other * OTHER_RATIO).toInt()
        }

        /**
         * @param toolContentCap >0 时按真实请求口径截断 tool 结果内容
         * （AgentEngine.buildApiMessages 对每条 tool 消息做 TextCap.middle(REQ_CAP)），
         * 否则按全文估算——长工具循环会话会显著高估。
         */
        fun estimateMessageTokens(msg: ChatMessage, toolContentCap: Int = 0): Int {
            var tokens = MSG_OVERHEAD
            val content =
                if (toolContentCap > 0 && msg.role == ChatMessage.ROLE_TOOL && msg.content.length > toolContentCap)
                    msg.content.take(toolContentCap)
                else msg.content
            tokens += estimateStringTokens(content)
            for (tc in msg.toolCalls) {
                tokens += estimateStringTokens(tc.name)
                tokens += estimateStringTokens(tc.argumentsJson)
            }
            if (!msg.imageData.isNullOrBlank()) tokens += IMAGE_TOKENS
            return tokens
        }

        fun estimateSystemTokens(systemPrompt: String): Int =
            estimateStringTokens(systemPrompt).coerceAtLeast(SYSTEM_BASE_TOKENS)

        fun estimateToolsTokens(toolsJson: String): Int = estimateStringTokens(toolsJson)

        fun calculate(
            messages: List<ChatMessage>,
            systemPrompt: String,
            toolsJson: String,
            contextWindow: Int
        ): ContextUsage {
            val sysTok = estimateSystemTokens(systemPrompt)
            val toolsTok = estimateToolsTokens(toolsJson)
            val histTok = messages.sumOf { estimateMessageTokens(it) }
            return ContextUsage(
                usedTokens = sysTok + toolsTok + histTok,
                totalTokens = contextWindow,
                systemTokens = sysTok,
                toolsTokens = toolsTok,
                historyTokens = histTok
            )
        }
    }
}
