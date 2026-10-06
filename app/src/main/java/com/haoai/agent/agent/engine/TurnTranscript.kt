package com.haoai.agent.agent.engine

import com.haoai.agent.agent.model.ChatMessage
import com.haoai.agent.agent.model.ToolCallData

/** 只有"最终回复"那条 assistant 才带的统计（中间轮不带，避免重复展示）。 */
data class AssistantStats(
    val promptTokens: Int,
    val completionTokens: Int,
    val durationMs: Long,
    val model: String
)

/**
 * 一轮流式回执的唯一累积点：正文 + 思考 + 「这一轮到底产出了什么」。
 *
 * 为什么值得从主循环里拆出来（原先是两个裸 StringBuilder 散在 runTurn 各处）：
 *
 * 1. **残句必须成对丢弃**。瞬态退避重试、溢出自动压缩重试都要先把上一次的半截输出清掉
 *    再重发。两处各自手写 `setLength(0)` 时，overflow 那条只清了正文、漏了思考 ——
 *    重试成功的答案会挂着上一次的思考链落库，用户在"思考过程"里看到两段不相干的推理。
 * 2. **提交判定只此一处**：正文为空但带了 toolCalls 仍要成条（协议要求 assistant 的
 *    工具调用必须落一条消息），纯空白不成条。
 * 3. delta 的"累积"和"转发给 UI"绑成一个动作，不会再出现只清不转、或只转不清。
 *
 * UI 侧的 40ms 节流缓冲（ChatViewModel.textBuf）不在此列，也不该合并：那是**渲染投影**
 * （每 40ms 上一次屏），不是第二份事实来源；这里才是回合内文本的唯一事实。
 * 但重试丢残句时两边必须同时丢 —— 只清引擎这边，UI 气泡会一直挂着永远不会成为
 * 最终答案的那段文字（供应商 429 退避后肉眼可见），所以 [discard] 要通知 UI。
 */
class TurnTranscript(
    private val onDelta: (String) -> Unit = {},
    private val onReasoning: (String) -> Unit = {},
    /** UI 侧同步丢弃已上屏的残句（仅 [discard] 调用，见类注释）。 */
    private val onDiscard: () -> Unit = {}
) {

    private val text = StringBuilder()
    private val reasoning = StringBuilder()
    /** 思考段首/尾 delta 的墙钟（摘要行「X.X秒」用；reset/discard 清零重计）。 */
    private var reasoningStartMs = 0L
    private var reasoningEndMs = 0L

    /** 本轮是否已产出可见正文（纯空白按"没有"处理）。 */
    val hasContent: Boolean get() = text.isNotBlank()

    fun delta(frag: String) {
        text.append(frag)
        onDelta(frag)
    }

    fun reasoningDelta(frag: String) {
        val now = System.currentTimeMillis()
        if (reasoning.isEmpty()) reasoningStartMs = now
        reasoningEndMs = now
        reasoning.append(frag)
        onReasoning(frag)
    }

    /** 本轮思考耗时（首字→尾字），无思考返回 null。 */
    fun reasoningMsOrNull(): Long? =
        if (reasoning.isNotEmpty() && reasoningEndMs > reasoningStartMs)
            reasoningEndMs - reasoningStartMs else null

    /** 重发前清残句：正文与思考一起丢，别留下"新答案 + 旧思考"的混拼。 */
    fun reset() {
        text.setLength(0)
        reasoning.setLength(0)
        reasoningStartMs = 0L
        reasoningEndMs = 0L
    }

    /** 同上，但额外要求 UI 撤掉已经画出去的残句（失败重试路径用这个）。 */
    fun discard() {
        val had = text.isNotEmpty() || reasoning.isNotEmpty()
        reset()
        if (had) onDiscard()
    }

    fun textString(): String = text.toString()

    fun reasoningOrNull(): String? = reasoning.toString().ifBlank { null }

    /** 无正文且无工具调用时返回 null（这一轮什么都没产出，不该往历史里塞空气泡）。 */
    fun buildAssistant(
        toolCalls: List<ToolCallData> = emptyList(),
        stats: AssistantStats? = null
    ): ChatMessage? {
        if (!hasContent && toolCalls.isEmpty()) return null
        return ChatMessage(
            role = ChatMessage.ROLE_ASSISTANT,
            content = textString(),
            calls = toolCalls,                        // ChatMessage 规范字段是 calls（目标侧）
            reasoning = reasoningOrNull(),
            pt = stats?.promptTokens ?: 0,            // AssistantStats 源字段名不动
            ct = stats?.completionTokens ?: 0,
            ms = stats?.durationMs ?: 0L,             // pt/ct/ms 非空（PC 侧锁）：null 落 0
            model = stats?.model,
            reasoningMs = reasoningMsOrNull()
        )
    }
}
