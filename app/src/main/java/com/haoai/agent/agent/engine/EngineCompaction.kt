package com.haoai.agent.agent.engine

import com.haoai.agent.agent.model.ChatMessage
import com.haoai.agent.agent.model.ToolCallData
import com.haoai.agent.agent.provider.ApiMessage
import com.haoai.agent.agent.provider.SseEvent
import com.haoai.agent.agent.tools.TextCap
import com.haoai.agent.agent.tools.ToolContext
import com.haoai.agent.agent.tools.toApi
import com.haoai.agent.data.ProviderConfig
import com.haoai.agent.data.toModel
import com.haoai.agent.data.toStored
import kotlinx.coroutines.CancellationException

/**
 * 上下文压缩：链式摘要目标、触发判定、溢出恢复、水位落账、压缩前记忆冲刷。
 *
 * 关键不变式（改动前务必读）：压缩**只推进水位，不删原文**。
 * 摘要 + 水位 + tokensBefore 三个字段合起来才是"压过什么"的完整记录，
 * 任一处在没有另一处配合的情况下被改写，都会造成"摘要与实际发送内容不一致"。
 */
/** 5.3 压缩摘要路由链（点6 链化）：主+备用按序；空配置回落主模型。 */
internal suspend fun AgentEngine.summarizerChain(): List<Pair<ProviderConfig, String>> {
    val t = runCatching { summarizeTarget?.invoke() }.getOrNull() ?: emptyList()
    val chain = if (t.isEmpty()) listOf(provider to apiKey) else t
    compactProvider = chain.first().first
    return chain
}

/** 点6：链式压缩——按序尝试各目标，单个失败（非取消）降级下一个；全失败抛最后错误。 */
internal suspend fun AgentEngine.compactWithChain(
    chatMsgs: List<com.haoai.agent.agent.model.ChatMessage>,
    chain: List<Pair<ProviderConfig, String>>
): com.haoai.agent.agent.engine.compaction.CompactionResult {
    var lastErr: Throwable? = null
    for ((p, k) in chain) {
        val isLocal = p.baseUrl.contains("127.0.0.1") || p.baseUrl.startsWith("local")
        val cw = if (isLocal) 32768 else p.effectiveContextLength()
        try {
            return compactionManager.compact(
                messages = chatMsgs,
                existingSummary = session.compactionSummary,
                provider = p,
                apiKey = k,
                contextWindow = cw
            )
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (e: Throwable) {
            lastErr = e
            android.util.Log.w("HaoCompact", "压缩目标 ${p.name}/${p.model} 失败，尝试链上下一个", e)
        }
    }
    throw lastErr ?: IllegalStateException("压缩摘要无可用模型")
}

/**
 * E 压缩前记忆冲刷轮：独立单轮 LLM 调用，只允许 memory 工具。
 * 输入=当前对话，要求模型沉淀值得长期化的事实/偏好/决定；没有可沉淀时直接返回。
 * 失败静默（冲刷是锦上添花，绝不阻塞压缩主流程）；沉淀结果即时落盘，压缩随后照常执行。
 */
internal suspend fun AgentEngine.memoryFlushTurn() {
    val flushTools = listOf(
        com.haoai.agent.agent.tools.MemoryTool()
    )
    val msgs = mutableListOf(
        ApiMessage(role = "system", content = MEMORY_FLUSH_SYSTEM),
        ApiMessage(
            role = "user",
            content = "以下对话即将被压缩。请把其中值得长期保留的事实/偏好/决定（若无则直接回答\"无\"）" +
                "用 memory 工具的 save/merge 写入长期记忆。只沉淀长期有效的信息，不要记录任务过程。\n\n" +
                session.messages.takeLast(40).joinToString("\n\n") {
                    "[${it.role}] ${it.content.take(600)}"
                }
        )
    )
    var calls: List<ToolCallData> = emptyList()
    httpClient.chatStream(provider, apiKey, msgs, gateTools(flushTools.map { it.toApi() }), effectiveEffort())
        .collect { ev ->
            when (ev) {
                is SseEvent.Completed -> calls = ev.toolCalls
                else -> {}
            }
        }
    val ctx = ToolContext(
        backend = null, shellDir = null, todoStore = todoStore, appFilesDir = appFilesDir,
        sessionId = session.id, memoryBank = memoryBank, journal = journal, depth = depth,
        appContext = appContext
    )
    for (call in calls) {
        val tool = flushTools.firstOrNull { it.name == call.name } ?: continue
        // 结果只记日志不落会话——assistant 侧没存，tool 消息入库即成孤儿（API 构建时
        // 会被 pairSanitized 裁掉，白占存储与估算）。沉淀本身已经由 tool.run 落盘完成。
        runCatching {
            val flushArgs = parseArgs(call.args) ?: return@runCatching
            val result = tool.run(flushArgs, ctx)
            android.util.Log.d(
                "HaoEngine",
                "memory flush: ${call.name} → ${TextCap.middle(result.content, 120)}"
            )
        }
    }
}

/** 检查是否需要压缩，需要则执行（压缩前先跑一轮记忆冲刷）。 */
internal suspend fun AgentEngine.maybeCompact(onEvent: (TurnEvent) -> Unit) {
    if (compactionManager.isCoolingDown()) return
    val chain = summarizerChain()
    val sp = chain.first().first
    val isLocal = sp.baseUrl.contains("127.0.0.1") || sp.baseUrl.startsWith("local")
    val contextWindow = if (isLocal) 32768 else sp.effectiveContextLength()
    val chatMsgs = uncompactedMessages().map { it.toModel() }
    // 触发判定统一走 estimateContextTokens：供应商真值优先、字符估算兜底。
    // （旧口径在这里现算全量历史 + 系统提示 + 工具定义，工具密集会话能高估一个数量级）
    val usedTokens = estimateContextTokens()
    if (!compactionManager.shouldCompact(usedTokens, contextWindow)) return

    // E 压缩前记忆冲刷（openclaw memory-flush 同思路）：压缩会丢过程细节，而值得长期化的
    // 事实/偏好恰可能只存在于过程里——压缩前先让主模型把重要上下文写进记忆/日志。
    // 只允许 memory 工具（写路径白名单），独立小轮不进主对话历史。
    runCatching {
        memoryFlushTurn()
    }.onFailure { android.util.Log.w("HaoEngine", "memory flush failed: ${it.message}") }

    // 预修剪工具输出
    val prunedMsgs = compactionManager.prePruneToolOutputs(chatMsgs)

    val result = compactWithChain(prunedMsgs, chain)
    session.compactionSummary = result.summary
    // 压掉多少按"决策时用的那个数"记账（真值口径），事后能判断这次压得对不对
    session.compactedTokensBefore = usedTokens
    markCompactedThrough()
    persist()
    onEvent(MessageAdded(ChatMessage(
        role = ChatMessage.ROLE_ASSISTANT,
        content = "[系统] 上下文已压缩，释放约 ${result.tokensSaved} tokens"
    )))
}

/** Overflow 恢复：检测 API 返回的 context_length_exceeded 错误，自动压缩后重试。 */
internal suspend fun AgentEngine.handleOverflow(
    error: String,
    onEvent: (TurnEvent) -> Unit,
    retryBlock: suspend () -> Unit
) {
    if (!compactionManager.isOverflowError(error)) throw Exception(error)
    if (compactionManager.isCoolingDown()) throw Exception(error)
    val chain = summarizerChain()
    val sp = chain.first().first
    val isLocal = sp.baseUrl.contains("127.0.0.1") || sp.baseUrl.startsWith("local")
    val contextWindow = if (isLocal) 32768 else sp.effectiveContextLength()
    val chatMsgs = uncompactedMessages().map { it.toModel() }

    // 强制压缩（点6：链式降级）
    val result = compactWithChain(chatMsgs, chain)
    session.compactionSummary = result.summary
    session.compactedTokensBefore = estimateContextTokens()
    markCompactedThrough()
    persist()
    onEvent(MessageAdded(ChatMessage(
        role = ChatMessage.ROLE_ASSISTANT,
        content = "[系统] 上下文溢出，已自动压缩并重试"
    )))
    retryBlock()
}

/** 用交接文档替换早期历史：保留文档 + 最近几条消息。 */
internal fun AgentEngine.compactHistory(summary: String) {
    val msgs = session.messages
    if (msgs.size <= HANDOFF_KEEP) return
    val doc = ChatMessage(
        role = ChatMessage.ROLE_USER,
        content = "$HANDOFF_MARKER\n$summary\n\n（以上为此前对话的压缩交接，请基于它继续当前任务）"
    )
    val kept = msgs.takeLast(HANDOFF_KEEP).pairSanitized()
    msgs.clear()
    msgs.add(doc.toStored())
    msgs.addAll(kept)
    persist()
}

/**
 * 算出「摘要能代表到哪一条消息」——即水位候选；没有任何旧段可丢时返回 null。
 * 与"推进水位"分开写，是因为手动 /compact 得先知道这次值不值得花模型调用。
 */
internal fun AgentEngine.prospectiveWatermark(): String? {
    val msgs = session.messages
    val keep = compactionManager.keepRecentTokens()
    var acc = 0
    var keepFrom = msgs.size
    for (i in msgs.indices.reversed()) {
        // 按真实请求口径算（tool 结果 REQ_CAP 截断）：若按落库全文（可达 STORED_CAP=16000）估算，
        // keepRecentTokens 预算会被提前耗尽，水位推进得比设计值更远。
        acc += com.haoai.agent.ui.chat.ContextUsage.estimateMessageTokens(msgs[i].toModel(), REQ_CAP)
        if (acc > keep) break
        keepFrom = i
    }
    // 对齐到用户消息边界：不能把 assistant(toolCalls)/tool 序列拦腰切开
    var start = keepFrom
    while (start < msgs.size && msgs[start].role != ChatMessage.ROLE_USER) start++
    if (start <= 0 || start >= msgs.size) return null
    return msgs[start - 1].id
}

/**
 * 压缩落账：把已被摘要代表的那段历史打上水位标记，**不物理删除**。
 * 构建请求时只取水位之后的消息，所以 token 一样是减下来的；
 * 但原文仍在会话里 —— 摘要写坏了可以重来，用户也不会看到历史凭空消失。
 * （旧实现是从 messages 里 drop 掉，压过头就没有补救办法。）
 */
internal fun AgentEngine.markCompactedThrough(wid: String? = prospectiveWatermark()) {
    if (wid == null) return
    session.compactedThroughId = wid
    // 锚点作废：水位之前的段落不再进请求，那条真值算的已经不是当前这段上下文的规模了
    session.usageAnchor = null
    enforceStoredRetention()
}

/**
 * 存储上限保险：不再物理删历史意味着会话文件会无界增长（手机端不可接受）。
 * 只裁水位**之前**的最旧消息 —— 它们已由摘要代表，且远超回查所需的窗口；
 * 水位之后的一个字都不动。
 */
internal fun AgentEngine.enforceStoredRetention() {
    val msgs = session.messages
    if (msgs.size <= SESSION_MAX_MESSAGES) return
    val watermarkIdx = session.compactedThroughId?.let { wid ->
        msgs.indexOfFirst { it.id == wid }
    } ?: -1
    if (watermarkIdx <= 0) return
    val over = (msgs.size - SESSION_MAX_MESSAGES).coerceAtMost(watermarkIdx)
    if (over > 0) msgs.subList(0, over).clear()
}

/**
 * 手动压缩（/compact）。
 *
 * @return 这次从上下文里丢出去（改由摘要代表）的 token 数；
 *   **null = 整段历史都还在"最近保留窗口"里，压不动**。这时直接不花模型调用返回：
 *   水位推不动而只写摘要的话，摘要会作为 system 消息永久挂在每次请求上，
 *   token 不减反增 —— 一个语义是"释放空间"的命令做出反效果不诚实
 *   （设备实测：6 条小消息的会话按 /compact 就产生了 488 字符摘要 + 水位没动）。
 */
internal suspend fun AgentEngine.compactNow(): Int? {
    val wid = prospectiveWatermark() ?: return null
    val before = estimateContextTokens()
    val chain = summarizerChain()
    val chatMsgs = uncompactedMessages().map { it.toModel() }
    val result = compactWithChain(chatMsgs, chain)
    session.compactionSummary = result.summary
    session.compactedTokensBefore = before
    // 手动 /compact 以前只写摘要、不推水位：摘要 + 全量历史一起发出去，token 只增不减
    markCompactedThrough(wid)
    persist()
    return (before - estimateContextTokens()).coerceAtLeast(0)
}
