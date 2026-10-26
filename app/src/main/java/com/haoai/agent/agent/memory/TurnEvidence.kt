package com.haoai.agent.agent.memory

import com.haoai.agent.data.StoredMessage

/**
 * 每轮记忆提取的素材与联动判定（2026-10-26 对齐 Hermes 细节）：
 * - 素材带工具链：旧版只拼 user/assistant 正文（尾部 8 条×500 字），
 *   "用户纠正了做法/踩坑后换路径"的证据在工具调用与结果里，全被滤掉了。
 *   现在按 HistoryDigest 同款行格式补上工具行，总预算仍由 TextCap(4000) 兜底。
 * - 主通道联动：本回合模型已主动调 memory 工具沉淀（hermes 的计数器清零语义），
 *   兜底提取当轮跳过——省一次辅助调用，也少一分与主通道的重复面。
 */
object TurnEvidence {

    /** memory 工具名（MemoryTool.name）。 */
    private const val MEMORY_TOOL = "memory"

    /** 最多收集几条用户/助手正文行（旧版口径不变）。 */
    private const val MAX_TEXT_LINES = 8

    /** 正文行截断。 */
    private const val TEXT_CLIP = 400

    /** 工具调用参数摘要截断。 */
    private const val TOOL_ARGS_CLIP = 120

    /** 工具结果截断。 */
    private const val TOOL_RESULT_CLIP = 160

    /** 工具行总字符预算（从正文预算外挤，不撑大辅助请求）。 */
    private const val TOOL_BUDGET = 1000

    /**
     * 本回合（最后一条用户消息之后）模型是否主动调过 memory 工具。
     * hermes 语义：调过即清零评审计数器——主通道干完活，兜底不跑。
     */
    fun memoryToolUsedThisTurn(messages: List<StoredMessage>): Boolean {
        for (i in messages.indices.reversed()) {
            val m = messages[i]
            when (m.role) {
                "user" -> return false
                "assistant" -> if (m.toolCalls.any { it.name == MEMORY_TOOL }) return true
                "tool" -> if (m.toolName == MEMORY_TOOL) return true
            }
        }
        return false
    }

    /**
     * 从消息尾部构建提取素材：最近 [MAX_TEXT_LINES] 条用户/助手正文 +
     * 窗口内的工具调用/结果行（新→旧回走，预算耗尽即止，保证保住最新的）。
     * 全空返回空串（调用方以长度门槛决定跳过）。
     */
    fun buildTranscript(messages: List<StoredMessage>): String {
        val lines = ArrayList<String>()
        var textCount = 0
        var toolBudget = TOOL_BUDGET
        for (i in messages.indices.reversed()) {
            if (textCount >= MAX_TEXT_LINES) break
            val m = messages[i]
            when (m.role) {
                "user" -> if (m.content.isNotBlank()) {
                    textCount++
                    lines += "用户：${m.content.replace(Regex("\\s+"), " ").trim().take(TEXT_CLIP)}"
                }
                "assistant" -> {
                    m.content.trim().takeIf { it.isNotBlank() }?.let {
                        textCount++
                        lines += "助手：${it.replace(Regex("\\s+"), " ").trim().take(TEXT_CLIP)}"
                    }
                    m.toolCalls.forEach { tc ->
                        if (toolBudget > 0) {
                            val args = tc.argumentsJson.replace(Regex("\\s+"), " ").take(TOOL_ARGS_CLIP)
                            val line = "助手 ▸ 调用工具 ${tc.name}（$args）"
                            toolBudget -= line.length
                            lines += line
                        }
                    }
                }
                "tool" -> {
                    val body = m.content.replace(Regex("\\s+"), " ").trim().take(TOOL_RESULT_CLIP)
                    if (toolBudget > 0 && (body.isNotEmpty() || m.error)) {
                        val line = "工具 ${m.toolName ?: "tool"} ◂${if (m.error) "失败" else "结果"}：$body"
                        toolBudget -= line.length
                        lines += line
                    }
                }
            }
        }
        lines.reverse()
        return lines.joinToString("\n")
    }
}
