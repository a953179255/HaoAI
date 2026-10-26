package com.haoai.agent.agent.skills

import com.haoai.agent.data.StoredMessage
import com.haoai.agent.data.StoredSession

/**
 * 「从历史会话学习」素材管线（2026-10-25 对齐 Hermes background_review）：
 * 喂完整对话转录——用户/助手正文 + 工具调用 + 工具结果（含成败），大字符预算；
 * 取代旧的"全局 6 会话×尾部 8 条×260 字×8000 字"纯文本摘要（工具过程全丢，
 * 而踩坑→修复的证据恰恰在工具链里）。
 *
 * 预算口径：Hermes 复盘输入兜底 120k token（按上下文窗口 75% 动态放大）；
 * 手机端网关有请求体上限、模型 ctx 参差，取保守折中 40k 字符（≈4-6 万 token）。
 */
object HistoryDigest {

    /** 全局字符预算（UTF-8 约 120KB，低于网关 body 上限）。 */
    const val CHAR_BUDGET = 40_000

    /** 复盘的会话数：料变全量后 3 个足够（原 6 个是摘要时代的补偿）。 */
    const val SESSION_COUNT = 3

    /** 单条工具结果截断（长输出只留头部；Hermes toolResultOverflow=omit 同思想）。 */
    private const val TOOL_RESULT_CLIP = 600

    /** 单条正文截断（原 260 字是为了塞下 6 会话；现在给足上下文）。 */
    private const val TEXT_CLIP = 2000

    /** 复盘任务标记：复盘会话的首条用户消息以此开头。智能标题会把复盘会话改名
     *  （实测"技能复盘 · …"被改成"安全命令审批技能复盘"），标题前缀不可靠，
     *  以消息指纹为主——复盘转录再进素材会拿二手摘要自我强化。 */
    private const val REVIEW_TASK_TAG = "[技能复盘]"

    /** 标题前缀兜底（learnFromHistory 生成"技能复盘 · MM-dd HH:mm"）。 */
    private const val REVIEW_TITLE_PREFIX = "技能复盘"

    /**
     * 渲染复盘素材：最近 [SESSION_COUNT] 个实质会话的全量转录（含工具链），全局预算截断。
     * 复盘会话与空会话跳过；[budgetChars] 供测试注入。
     */
    fun render(sessions: List<StoredSession>, budgetChars: Int = CHAR_BUDGET): String {
        val picked = sessions
            .filter { !it.title.startsWith(REVIEW_TITLE_PREFIX) }
            .filter { s -> s.messages.none { it.role == "user" && it.content.startsWith(REVIEW_TASK_TAG) } }
            .sortedByDescending { it.updatedAt }
            .filter { s -> s.messages.any { it.role == "user" && it.content.isNotBlank() } }
            .take(SESSION_COUNT)
        if (picked.isEmpty()) return ""
        val sb = StringBuilder()
        for (s in picked) {
            val head = "── 会话「${s.title}」（${s.messages.size} 条消息）──"
            if (sb.isNotEmpty() && sb.length + head.length + 200 > budgetChars) break
            sb.appendLine(head)
            for (m in s.messages) {
                val line = renderLine(m) ?: continue
                if (sb.length + line.length > budgetChars) {
                    sb.appendLine("（已达复盘素材预算，本会话剩余消息省略）")
                    return sb.toString().trimEnd()
                }
                sb.append(line)
            }
        }
        return sb.toString().trimEnd()
    }

    /** 单条消息 → 转录行；不构成证据的消息（空 thinking 中转等）返回 null。 */
    private fun renderLine(m: StoredMessage): String? = when (m.role) {
        "user" -> m.content.takeIf { it.isNotBlank() }?.let { "用户：${it.replace(Regex("\\s+"), " ").trim().take(TEXT_CLIP)}\n" }
        "assistant" -> buildString {
            m.content.trim().takeIf { it.isNotBlank() }?.let { append("助手：${it.take(TEXT_CLIP)}\n") }
            m.toolCalls.forEach { tc ->
                val arg = tc.argumentsJson.replace(Regex("\\s+"), " ").take(200)
                append("助手 ▸ 调用工具 ${tc.name}（$arg）\n")
            }
        }.takeIf { it.isNotEmpty() }
        "tool" -> {
            val name = m.toolName ?: "tool"
            val tag = if (m.error) "失败" else "结果"
            val body = m.content.replace(Regex("\\s+"), " ").trim().take(TOOL_RESULT_CLIP)
            if (body.isEmpty() && !m.error) null
            else "工具 $name ◂$tag：$body\n"
        }
        else -> null
    }
}
