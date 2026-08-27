package com.haoai.agent.ui.chat

import androidx.compose.ui.graphics.Color
import com.haoai.agent.agent.model.ChatMessage
import com.haoai.agent.agent.model.ToolCallData

data class ContextUsage(
    val usedTokens: Int,
    val totalTokens: Int,
    val systemTokens: Int,
    val toolsTokens: Int,
    val historyTokens: Int
) {
    val percentage: Float get() = if (totalTokens > 0) usedTokens.toFloat() / totalTokens else 0f
    val remainingTokens: Int get() = (totalTokens - usedTokens).coerceAtLeast(0)

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

        fun estimateMessageTokens(msg: ChatMessage): Int {
            var tokens = MSG_OVERHEAD
            tokens += estimateStringTokens(msg.content)
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
