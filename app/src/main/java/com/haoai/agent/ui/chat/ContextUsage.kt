package com.haoai.agent.ui.chat

import androidx.compose.ui.graphics.Color
import com.haoai.agent.agent.model.ChatMessage
import com.haoai.agent.agent.model.ToolCallData
import com.haoai.agent.agent.tools.TextCap

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
         * 截断必须走同一个 TextCap.middle：head65%/tail25% + 省略标记，自己 take() 会算少。
         */
        fun estimateMessageTokens(msg: ChatMessage, toolContentCap: Int = 0): Int {
            var tokens = MSG_OVERHEAD
            val content =
                if (toolContentCap > 0 && msg.role == ChatMessage.ROLE_TOOL && msg.content.length > toolContentCap)
                    TextCap.middle(msg.content, toolContentCap)
                else msg.content
            tokens += estimateStringTokens(content)
            for (tc in msg.toolCalls) {
                tokens += estimateStringTokens(tc.name)
                tokens += estimateStringTokens(tc.argumentsJson)
            }
            if (!msg.imageData.isNullOrBlank()) tokens += IMAGE_TOKENS
            return tokens
        }

        /**
         * 「真实请求会发出去的那部分历史」的 token 估算：只算最近 [maxHistory] 条，
         * 且 tool 结果按 [toolContentCap] 截断后再算。
         *
         * 为什么要有它：AgentEngine.buildApiMessages 发的就是这个窗口，
         * 但压缩触发（maybeCompact）、handoff 催办、压缩后裁剪（trimCompactedHistory）
         * 三处此前都在拿「全量消息 + 未截断的 STORED_CAP 正文」估算。一个 100+ 条、
         * 每条工具结果上万的会话里，这个数能高估好几倍，直接表现是
         * **远未到窗口就提前压缩**（答得比应有的浅）与**过度裁剪历史**。
         * 统一走这里，四个消费点（含 UI 面板）口径不再各写一遍。
         */
        fun estimateRequestHistoryTokens(
            messages: List<ChatMessage>,
            maxHistory: Int,
            toolContentCap: Int
        ): Int = messages.takeLast(maxHistory).sumOf { estimateMessageTokens(it, toolContentCap) }

        /**
         * 按 token 预算从尾部选出发给模型的历史窗口起点下标（替代「固定取最近 N 条」）。
         *
         * 为什么要按 token：80 条既可能是 3 万 token（短问答），也可能是 30 万 token
         * （带大段工具结果），前者白白丢历史、后者直接撞 overflow。
         * 返回值会前推到最近一条边界消息（通常是 user），避免把 assistant(tool_calls)+tool
         * 结果拦腰切开（OpenAI 兼容端会以 400 拒绝孤儿 tool 消息）。
         *
         * 做成对类型无感的下标版本：引擎拿 StoredMessage、UI 面板拿 ChatMessage，
         * 两边共用同一份选窗逻辑，不再各写一遍近似循环（口径漂移就是这么来的）。
         *
         * @param budgetTokens 历史可用预算；@param maxMessages 条数硬上限（估算失准时的保险）
         * @return 窗口起点下标；至少返回一条的起点（单条就超预算也不返回空窗口）
         */
        fun <T> windowStart(
            messages: List<T>,
            tokenOf: (T) -> Int,
            isBoundary: (T) -> Boolean,
            budgetTokens: Int,
            maxMessages: Int
        ): Int {
            if (messages.isEmpty()) return 0
            val floor = (messages.size - maxMessages).coerceAtLeast(0)
            var acc = 0
            var from = messages.size
            var i = messages.size - 1
            while (i >= floor) {
                val t = tokenOf(messages[i])
                if (from < messages.size && acc + t > budgetTokens) break
                acc += t
                from = i
                i--
            }
            val aligned = (from until messages.size).firstOrNull { isBoundary(messages[it]) }
            return aligned ?: from
        }

        fun estimateSystemTokens(systemPrompt: String): Int =
            estimateStringTokens(systemPrompt).coerceAtLeast(SYSTEM_BASE_TOKENS)

        fun estimateToolsTokens(toolsJson: String): Int = estimateStringTokens(toolsJson)
    }
}
