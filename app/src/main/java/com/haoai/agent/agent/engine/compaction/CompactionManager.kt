package com.haoai.agent.agent.engine.compaction

import com.haoai.agent.agent.model.ChatMessage
import com.haoai.agent.agent.provider.ApiMessage
import com.haoai.agent.agent.provider.OpenAiCompatClient
import com.haoai.agent.data.ProviderConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

class CompactionManager(
    private val client: com.haoai.agent.agent.provider.ProviderClient,
    private val settings: CompactionSettings = CompactionSettings(),
    /** 真实请求的历史窗口与 tool 截断口径（由引擎传 MAX_HISTORY / REQ_CAP），供节省量估算对齐。 */
    private val maxHistory: Int = Int.MAX_VALUE,
    private val toolContentCap: Int = 0
) {
    private var lastCompactionTime = 0L

    /** 5.4 账本：压缩摘要 LLM 调用记账（不引会话依赖，由调用方可选注入）。 */
    var onLlmUsage: ((promptTokens: Int, completionTokens: Int, ok: Boolean) -> Unit)? = null

    /** 5.3 模型路由：压缩摘要走专用模型时按 provider 解析协议客户端（缺省用构造的主 client）。 */
    var clientResolver: ((ProviderConfig) -> com.haoai.agent.agent.provider.ProviderClient)? = null

    /** 判断是否需要触发压缩。 */
    fun shouldCompact(usedTokens: Int, contextWindow: Int): Boolean {
        if (contextWindow <= 0) return false
        val ratio = usedTokens.toFloat() / contextWindow
        return ratio >= settings.triggerThreshold
    }

    /** 压缩冷却中。 */
    fun isCoolingDown(): Boolean =
        System.currentTimeMillis() - lastCompactionTime < settings.cooldownMs

    /** 压缩后保留的最近 token 数（供引擎裁剪历史用）。 */
    fun keepRecentTokens(): Int = settings.keepRecentTokens

    /** 工具输出预修剪：替换旧工具结果为占位符，释放 token。返回修剪后的消息列表。 */
    fun prePruneToolOutputs(messages: List<ChatMessage>): List<ChatMessage> {
        if (settings.toolOutputKeepChars <= 0) return messages
        var savedChars = 0
        val len = messages.size
        val cutoff = (len - 6).coerceAtLeast(0)
        return messages.mapIndexed { i, msg ->
            // content 现为 String?（与 PC 侧 Msg 对齐）：null 没东西可剪，直接跳过
            val c = msg.content
            if (i < cutoff && msg.role == ChatMessage.ROLE_TOOL && c != null && c.length > settings.toolOutputKeepChars) {
                val original = c.length
                val pruned = c.take(settings.toolOutputKeepChars) + "\n...[已修剪]"
                savedChars += original - pruned.length
                msg.copy(content = pruned)
            } else {
                msg
            }
        }
    }

    /** 执行压缩，返回摘要文本。 */
    suspend fun compact(
        messages: List<ChatMessage>,
        existingSummary: String?,
        provider: ProviderConfig,
        apiKey: String,
        contextWindow: Int
    ): CompactionResult {
        val budget = settings.summaryBudget(contextWindow)
        val context = messagesToText(messages)

        val prompt = if (existingSummary.isNullOrBlank()) {
            CompactionPrompts.initialSummaryPrompt(context, budget)
        } else {
            // 全量传入：只取尾部会造成两轮压缩之间的消息既不在旧摘要也不进新摘要，信息无声丢失
            CompactionPrompts.incrementalSummaryPrompt(existingSummary, context, budget)
        }

        val summary = try {
            callLlmForSummary(prompt, provider, apiKey)
        } catch (ce: CancellationException) {
            // 用户停止/协程取消：不能落进下面的 fallback，否则会把截断垃圾摘要持久化污染会话
            throw ce
        } catch (_: Exception) {
            // LLM 调用失败，使用 fallback 截断
            val allText = messages.map { "${it.role}: ${it.content?.take(200) ?: ""}" }
            CompactionPrompts.fallbackTruncate(allText)
        }

        lastCompactionTime = System.currentTimeMillis()
        return CompactionResult(
            summary = summary,
            tokensSaved = estimateSavedTokens(messages, contextWindow, budget)
        )
    }

    /** 检测 overflow 错误。 */
    fun isOverflowError(error: String): Boolean {
        val lower = error.lowercase()
        return lower.contains("context_length_exceeded") ||
            lower.contains("maximum context length") ||
            lower.contains("too many tokens") ||
            lower.contains("context window") ||
            lower.contains("400") && lower.contains("token")
    }

    private suspend fun callLlmForSummary(
        prompt: String,
        provider: ProviderConfig,
        apiKey: String
    ): String {
        val messages = listOf(ApiMessage(role = "user", content = prompt))
        val flow = (clientResolver?.invoke(provider) ?: client).chatStream(provider, apiKey, messages, tools = emptyList())
        val sb = StringBuilder()
        var up = 0; var uc = 0
        // 超时返回 null：把半截摘要按失败处理（走 fallbackTruncate），绝不把截断垃圾当成功入库
        val completed = withTimeoutOrNull(60_000L) {
            flow.collect { event ->
                when (event) {
                    is com.haoai.agent.agent.provider.SseEvent.Delta -> sb.append(event.text)
                    is com.haoai.agent.agent.provider.SseEvent.Reasoning -> {}
                    is com.haoai.agent.agent.provider.SseEvent.Completed -> {}
                    is com.haoai.agent.agent.provider.SseEvent.Usage -> { up = event.promptTokens; uc = event.completionTokens }
                }
            }
            true
        } ?: false
        val result = if (completed) sb.toString().trim() else ""
        onLlmUsage?.invoke(up, uc, result.isNotBlank())
        if (result.isBlank()) throw Exception("Empty summary response")
        return result
    }

    private fun messagesToText(messages: List<ChatMessage>): String = buildString {
        for (msg in messages) {
            when (msg.role) {
                ChatMessage.ROLE_USER -> appendLine("[用户]: ${msg.content ?: ""}")
                ChatMessage.ROLE_ASSISTANT -> {
                    appendLine("[助手]: ${msg.content}")
                    for (tc in msg.calls) {
                        appendLine("  [工具调用] ${tc.name}(${tc.args.take(200)})")
                    }
                }
                ChatMessage.ROLE_TOOL -> {
                    val cc = msg.content ?: ""
                    val brief = if (cc.length > 300) cc.take(300) + "..." else cc
                    appendLine("[工具结果] $brief")
                }
            }
        }
    }

    /**
     * 估算这次压缩实际释放的 token 数。
     *
     * ## M12 修复（2026-10-10）：原来系统性高估约 keepRecentTokens
     *
     * 原实现 `currentTokens - summaryBudget`，只减了摘要预算。但压缩**不是**
     * "把整段历史换成摘要"—— 压缩后仍然**保留** [settings.keepRecentTokens]
     * 的近期消息（见 `EngineEngine.prospectiveWatermark`：从尾部累加到超预算
     * 才停，那段原样留着）。
     *
     * 所以压缩后的真实规模 ≈ 保留窗口 + 摘要，减数里必须把保留窗口也算进去：
     * ```
     * before = currentTokens
     * after  = keepRecentTokens + summaryBudget
     * saved  = before - after
     * ```
     * 原式等于把保留窗口也当成"省下来了"，恒定多报约 18000 tokens。
     * 用户看到的「释放约 N tokens」因此长期虚高 —— 而这个数字的用途正是
     * 让用户判断"这次压缩值不值"，虚高等于这个功能没有参考价值。
     *
     * 两个边界：
     * · 保留窗口可能大于压缩前的规模（历史本来就短），`coerceAtLeast(0)` 兜底；
     * · 估算口径必须与真实请求一致（tool 结果按 REQ_CAP 截断），
     *   否则工具密集会话误差可达一个数量级 —— 这是构造参数传进来的原因。
     */
    private fun estimateSavedTokens(messages: List<ChatMessage>, contextWindow: Int, summaryBudget: Int): Int {
        val currentTokens = com.haoai.agent.ui.chat.ContextUsage.estimateRequestHistoryTokens(
            messages, maxHistory, toolContentCap
        )
        val after = settings.keepRecentTokens + summaryBudget
        return (currentTokens - after).coerceAtLeast(0)
    }
}

data class CompactionResult(
    val summary: String,
    val tokensSaved: Int
)
