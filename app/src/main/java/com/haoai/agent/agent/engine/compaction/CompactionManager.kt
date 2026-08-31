package com.haoai.agent.agent.engine.compaction

import com.haoai.agent.agent.model.ChatMessage
import com.haoai.agent.agent.provider.ApiMessage
import com.haoai.agent.agent.provider.OpenAiCompatClient
import com.haoai.agent.data.ProviderConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

class CompactionManager(
    private val client: com.haoai.agent.agent.provider.ProviderClient,
    private val settings: CompactionSettings = CompactionSettings()
) {
    private var lastCompactionTime = 0L

    /** 5.4 账本：压缩摘要 LLM 调用记账（不引会话依赖，由调用方可选注入）。 */
    var onLlmUsage: ((promptTokens: Int, completionTokens: Int, ok: Boolean) -> Unit)? = null

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
            if (i < cutoff && msg.role == ChatMessage.ROLE_TOOL && msg.content.length > settings.toolOutputKeepChars) {
                val original = msg.content.length
                val pruned = msg.content.take(settings.toolOutputKeepChars) + "\n...[已修剪]"
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
            val newText = messagesToText(messages)
            CompactionPrompts.incrementalSummaryPrompt(existingSummary, newText, budget)
        }

        val summary = try {
            callLlmForSummary(prompt, provider, apiKey)
        } catch (ce: CancellationException) {
            // 用户停止/协程取消：不能落进下面的 fallback，否则会把截断垃圾摘要持久化污染会话
            throw ce
        } catch (_: Exception) {
            // LLM 调用失败，使用 fallback 截断
            val allText = messages.map { "${it.role}: ${it.content.take(200)}" }
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
        val flow = client.chatStream(provider, apiKey, messages, tools = emptyList())
        val sb = StringBuilder()
        var up = 0; var uc = 0
        withTimeoutOrNull(60_000L) {
            flow.collect { event ->
                when (event) {
                    is com.haoai.agent.agent.provider.SseEvent.Delta -> sb.append(event.text)
                    is com.haoai.agent.agent.provider.SseEvent.Reasoning -> {}
                    is com.haoai.agent.agent.provider.SseEvent.Completed -> {}
                    is com.haoai.agent.agent.provider.SseEvent.Usage -> { up = event.promptTokens; uc = event.completionTokens }
                }
            }
        }
        val result = sb.toString().trim()
        onLlmUsage?.invoke(up, uc, result.isNotBlank())
        if (result.isBlank()) throw Exception("Empty summary response")
        return result
    }

    private fun messagesToText(messages: List<ChatMessage>): String = buildString {
        for (msg in messages) {
            when (msg.role) {
                ChatMessage.ROLE_USER -> appendLine("[用户]: ${msg.content}")
                ChatMessage.ROLE_ASSISTANT -> {
                    appendLine("[助手]: ${msg.content}")
                    for (tc in msg.toolCalls) {
                        appendLine("  [工具调用] ${tc.name}(${tc.argumentsJson.take(200)})")
                    }
                }
                ChatMessage.ROLE_TOOL -> {
                    val brief = if (msg.content.length > 300) msg.content.take(300) + "..." else msg.content
                    appendLine("  [工具结果] $brief")
                }
            }
        }
    }

    private fun estimateSavedTokens(messages: List<ChatMessage>, contextWindow: Int, summaryBudget: Int): Int {
        val currentTokens = messages.sumOf {
            com.haoai.agent.ui.chat.ContextUsage.estimateMessageTokens(it)
        }
        return (currentTokens - summaryBudget).coerceAtLeast(0)
    }
}

data class CompactionResult(
    val summary: String,
    val tokensSaved: Int
)
