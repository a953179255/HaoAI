package com.haoai.agent.agent.engine

import com.haoai.agent.agent.model.ChatMessage
import com.haoai.agent.agent.provider.ApiMessage
import com.haoai.agent.agent.tools.TextCap
import com.haoai.agent.agent.tools.ToolRegistry
import com.haoai.agent.agent.tools.toolCallToApi
import com.haoai.agent.data.toModel
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.haoai.agent.ui.chat.ContextUsage

/**
 * 上下文与请求组装：系统提示、开销估算、历史选窗、把会话翻成协议消息。
 *
 * 拆出来的判据与三家参考实现一致：**能不能回指回合循环**。这里的函数只读
 * session/provider/tools 三类输入，产出字符串与消息列表，不起效应、不推进循环状态，
 * 所以搬动是纯搬运；反过来 runTurn 本体留在引擎文件里。
 */
internal fun AgentEngine.buildSystemText(markMemoryUse: Boolean = false): String =
    buildSystemTextWithInjected(markMemoryUse).first

/**
 * (系统提示全文, 动态注入块拼接文本[记忆/技能索引/日志/MCP 摘要/预算提示])。
 * 第二项仅供上下文用量拆分估算（estimateOverheadBreakdown）；真实请求路径只用全文。
 * token 启发式按字符线性可加减，基础提示 = 全文 − 注入，buildSuffix 为注入块
 * 加的小标题归入基础提示（误差可忽略）。
 */
internal fun AgentEngine.buildSystemTextWithInjected(markMemoryUse: Boolean = false): Pair<String, String> {
    val shellAvailable = backend?.shellWorkdir() != null
    val dateText = SimpleDateFormat("yyyy-MM-dd EEEE", Locale.CHINA).format(Date())
    // 3.3：当前 shell 后端说明 + 沙箱能力探测（探测异步跑一次，下一轮注入）
    val sandbox = com.haoai.agent.platform.sandbox.SandboxEnv.resolve(
        appFilesDir, appContext?.applicationInfo?.nativeLibraryDir, backend?.shellWorkdir()
    )
    val shellNote = when {
        !shellAvailable -> ""
        sandbox != null -> {
            if (backgroundScope != null) {
                com.haoai.agent.agent.tools.shell.SandboxProbe.ensureStarted(sandbox, backgroundScope)
            }
            com.haoai.agent.platform.sandbox.SandboxEnv.describe(
                sandbox, com.haoai.agent.agent.tools.shell.SandboxProbe.summary() ?: ""
            )
        }
        else -> "Shell 后端：toybox（Android /system/bin/sh，工具集有限）；用户在 设置 → Linux 环境 安装发行版后 bash 将自动切换到 glibc 沙箱。"
    }
    val memory = memorySnippet(markMemoryUse)
    val skillIndex = com.haoai.agent.agent.skills.SkillStore.promptIndex()
    val journalBlock = journalSnippet()
    val mcpSummary = com.haoai.agent.agent.mcp.McpManager.promptSummary()
    val budget = budgetHint()
    // 能力声明片段：把当前模型的输入/输出
    // 模态与工具支持显式写进系统提示词，让模型知道边界并主动绕行，而非中途引用被剥离
    // 的能力导致任务断裂报错。端侧模型（llama）不走此注入（本地能力另由 vision 探测）。
    // 配置了委派模型时提示词会点名 delegate_to_vision/transcribe_audio 工具；
    // delegateTarget 是 suspend 闭包，此处直接检查设置字符串（buildSystemText 非挂起上下文）
    val delegateReady = delegateTarget != null
    val capabilityNote =
        if (provider.baseUrl.contains("127.0.0.1") || provider.baseUrl.startsWith("local")) ""
        else com.haoai.agent.data.CapabilityResolver.capabilityPromptFragment(provider.caps(), delegateReady).orEmpty()
    val full = SystemPrompt.PREFIX +
        SystemPrompt.buildSuffix(
            workspaceLabel, shellAvailable, dateText, customPrompt, memory,
            a11yAvailable = com.haoai.agent.platform.a11y.HaoAccessibilityService.connected(),
            identity = identity,
            skillIndex = skillIndex,
            journalBlock = journalBlock,
            mcpSummary = mcpSummary,
            shellNote = shellNote,
            vscreenAvailable = vscreenEnabled,
            toolsGroupHint = toolsGroupHintText(),
            capabilityNote = capabilityNote
        ) + budget + todoProgressLine(consume = markMemoryUse)
    val injected = memory + skillIndex + journalBlock + mcpSummary + budget + capabilityNote
    return full to injected
}

/** 注入记忆时带上当前用户消息做主题相关性打分；markMemoryUse 仅在真实请求路径为 true（token 估算不计数）。 */
internal fun AgentEngine.memorySnippet(markMemoryUse: Boolean = false): String {
    if (!memoryEnabled || depth != 0) return ""
    val bank = memoryBank ?: return ""
    val query = session.messages.lastOrNull { it.role == ChatMessage.ROLE_USER }?.content
    val (snippet, ids) = bank.promptSnippetIds(query?.take(300))
    if (markMemoryUse) bank.markInjected(ids, query)
    return snippet
}

internal fun AgentEngine.journalSnippet(): String =
    if (memoryEnabled && depth == 0) journal?.promptSnippet().orEmpty() else ""

/**
 * E9 todo 进度行：仅在真实请求路径（consume=true，buildApiMessages）注入并消费标志；
 * 估算路径（estimateOverheadTokens/maybeNudgeHandoff）只读不消费——否则标志会被
 * 估算先行清掉，进度行永远进不了真实请求。
 */
internal fun AgentEngine.todoProgressLine(consume: Boolean): String {
    if (!todoDirty || !consume) return ""
    todoDirty = false
    val items = todoStore.load(session.id)
    if (items.isEmpty()) return ""
    val done = items.count { it.status == "completed" }
    val active = items.firstOrNull { it.status == "in_progress" } ?: items.firstOrNull { it.status == "pending" }
    val head = active?.text?.take(30) ?: ""
    val human = if (done == items.size) "全部完成" else "待办 ${items.size} 项：已完成 $done、进行中 ${items.count { it.status == "in_progress" }}"
    return "\n[任务进度] $human${if (head.isNotEmpty()) "（$head）" else ""}"
}

/** E4b 分层注入提示：告知模型未加载的工具组与启用方式（全开/无缺省时为空）。 */
internal fun AgentEngine.toolsGroupHintText(): String {
    val active = session.activeGroups?.toSet() ?: return ""
    val disabled = ToolRegistry.ALL_GROUPS - active
    if (disabled.isEmpty()) return ""
    val parts = buildList {
        if (ToolRegistry.GROUP_EXTENDED in disabled) {
            add("extended（无障碍操作/内置浏览器/虚拟屏/相机/定位/设备工具包/工作流等）")
        }
        if (ToolRegistry.GROUP_MCP in disabled) add("mcp（MCP 外部服务器工具）")
    }
    return "## 工具分组\n" +
        "- 本会话未加载的工具组：${parts.joinToString("；")}。这些组的工具不在你的工具清单里。\n" +
        "- 需要时先调用 tools_enable（group=\"组名\"）启用：本轮工具执行完成后即生效，本会话保持。\n" +
        "- 未启用前不要臆测调用这些组的工具。"
}

/** 能力门控（统一走 CapabilityResolver）：模型标记不支持 tools 时清空工具清单，降级纯对话。 */
internal fun AgentEngine.gateTools(apiTools: List<com.haoai.agent.agent.provider.ApiTool>): List<com.haoai.agent.agent.provider.ApiTool> =
    if (provider.caps().tools == false) emptyList() else apiTools

internal fun AgentEngine.estimateOverheadTokens(): Pair<Int, Int> =
    com.haoai.agent.ui.chat.ContextUsage.estimateStringTokens(buildSystemText()) to
        (_toolsTokenCache ?: TOOLS_BASE_TOKENS)

/** 拆分估算 (基础系统提示, 动态注入[记忆/技能/日志/MCP/预算], 工具定义)，供上下文详情面板。 */
internal fun AgentEngine.estimateOverheadBreakdown(): Triple<Int, Int, Int> {
    val (full, injected) = buildSystemTextWithInjected()
    val sysTok = com.haoai.agent.ui.chat.ContextUsage.estimateStringTokens(full)
    val injTok = com.haoai.agent.ui.chat.ContextUsage.estimateStringTokens(injected)
    return Triple(
        (sysTok - injTok).coerceAtLeast(0),
        injTok,
        _toolsTokenCache ?: TOOLS_BASE_TOKENS
    )
}

/**
 * 实际生效的思考等级（按模型控制）：模型条目覆盖 > 全局设置。
 * "off" 返回 null（请求不发 reasoning_effort，即使用户全局配了等级）。
 */
internal fun AgentEngine.effectiveEffort(): String? {
    val v = provider.effectiveReasoningEffort(reasoningEffort)
    return v.takeIf { it.isNotBlank() && it != "off" }
}

/** 聊天模型的真实上下文窗口（本地端侧固定按 32K 保守算）。 */
internal fun AgentEngine.chatContextWindow(): Int {
    val isLocal = provider.baseUrl.contains("127.0.0.1") || provider.baseUrl.startsWith("local")
    return if (isLocal) 32768 else provider.effectiveContextLength()
}

/**
 * 还没被摘要覆盖的消息：压缩水位之后的那一段。
 * 水位 id 找不到（旧会话没这个字段、或那条已被存储上限裁掉）时按整段处理，
 * 宁可多发一点也不要静默丢掉上下文。
 */
internal fun AgentEngine.uncompactedMessages(): List<com.haoai.agent.data.StoredMessage> {
    val all = session.messages
    val wid = session.compactedThroughId ?: return all
    val idx = all.indexOfFirst { it.id == wid }
    return if (idx < 0) all else all.drop(idx + 1)
}

/**
 * 「下一次请求会发出去多少 token」——压缩触发与 handoff 催办都按这个数决策。
 *
 * 优先用供应商真值锚定：上一轮请求的 promptTokens 是**实际计费**的上下文规模，
 * 它已经包含系统提示 + 工具定义 + 当时那整段历史，所以只需要补上锚点之后新增的
 * 消息，再把两轮之间上下文开销的变化（记忆/技能注入变动、工具组启用）按当下口径
 * 加减回去。纯字符估算只在没有锚点时兜底 —— 那套启发式在工具密集会话里能高估
 * 一个数量级，后果是"远没到窗口就提前压缩"（答得比应有的浅）。
 *
 * 锚点自动失效的情况：消息数比锚点还少（编辑重发/删除截断过历史）→ 退回全量估算；
 * 压缩推进水位时显式置 null（水位之前的段落不再进请求，锚点算出来会偏高）。
 *
 * internal 是给单测钉数值用的；UI 上下文面板要的是 system/tools/history 分项，
 * 那个展示口径别复用这里（真值给不出分项）。
 */
internal fun AgentEngine.estimateContextTokens(): Int {
    val all = session.messages
    val (sysTok, toolsTok) = estimateOverheadTokens()
    val a = session.usageAnchor
    if (a != null && a.promptTokens > 0 && a.messageCount in 0..all.size) {
        val added = all.subList(a.messageCount, all.size).sumOf {
            com.haoai.agent.ui.chat.ContextUsage.estimateMessageTokens(it.toModel(), REQ_CAP)
        }
        return (a.promptTokens - a.overheadTokens + sysTok + toolsTok + added).coerceAtLeast(0)
    }
    val history = uncompactedMessages().sumOf {
        com.haoai.agent.ui.chat.ContextUsage.estimateMessageTokens(it.toModel(), REQ_CAP)
    }
    return history + sysTok + toolsTok
}

/** 历史可用预算 = 窗口 − 系统提示 − 工具定义 − 已有摘要 − 单次回复上限 − 安全余量。 */
internal fun AgentEngine.historyBudgetTokens(): Int {
    val (sysTok, toolsTok) = estimateOverheadTokens()
    val summaryTok = com.haoai.agent.ui.chat.ContextUsage.estimateStringTokens(
        session.compactionSummary.orEmpty()
    )
    val reserved = provider.effectiveMaxTokens().coerceAtLeast(0)
    return (chatContextWindow() - sysTok - toolsTok - summaryTok - reserved - HISTORY_MARGIN_TOKENS)
        .coerceAtLeast(MIN_HISTORY_BUDGET_TOKENS)
}

/** 这一轮真正会发出去的历史：水位之后 + token 预算窗口 + 条数保险上限。 */
internal fun AgentEngine.requestHistoryWindow(): List<com.haoai.agent.data.StoredMessage> {
    val msgs = uncompactedMessages()
    val from = com.haoai.agent.ui.chat.ContextUsage.windowStart(
        msgs,
        { com.haoai.agent.ui.chat.ContextUsage.estimateMessageTokens(it.toModel(), REQ_CAP) },
        { it.role == ChatMessage.ROLE_USER },
        historyBudgetTokens(),
        MAX_HISTORY
    )
    return msgs.drop(from)
}

/** 供 UI 上下文面板复用：按引擎真实请求口径算「这一轮历史会占多少 token」。
 *  面板自己另算一份是当初口径漂移的根源，现在统一从这里取。 */
internal fun AgentEngine.estimateSentHistoryTokens(): Int = requestHistoryWindow().sumOf {
    com.haoai.agent.ui.chat.ContextUsage.estimateMessageTokens(it.toModel(), REQ_CAP)
}

internal fun AgentEngine.buildApiMessages(): List<ApiMessage> {
    val systemText = buildSystemText(markMemoryUse = true)

    // 配对感知裁剪：窗口切割可能把 assistant(tool_calls) 切掉却留下它的 tool 结果，
    // OpenAI 兼容端会以 400 拒绝孤儿 tool 消息（且重试复现，会话就此卡死）。
    // 注意这里必须是**旧→新**的正序：requestHistoryWindow 已经按预算从前往后截好，
    // 原先"倒序取 N 条再倒回来"的第二次 asReversed 不能省 —— 少了它整段历史
    // 会以新→旧发出，模型看到的最后一条变成会话里最老的那句（d55ac4d 引入，
    // 表现为"追问当没看见、把第一个问题又答一遍"）。repairBlankCallIds 也按正序配对 id。
    val history = requestHistoryWindow()
        .repairBlankCallIds()
        .pairSanitized()
        .mapNotNull { m ->
            when (m.role) {
                ChatMessage.ROLE_USER -> {
                    val caps = provider.caps()
                    val parts = mutableListOf<com.haoai.agent.agent.provider.ApiContentPart>()
                    val notes = StringBuilder()
                    var text = m.content
                    // 图像：有 image-in 直发，否则注记降级
                    if (!m.imageData.isNullOrBlank()) {
                        if (caps.hasImage) parts.add(
                            com.haoai.agent.agent.provider.ApiContentPart(
                                type = "image_url",
                                imageUrl = com.haoai.agent.agent.provider.ApiImageUrl(url = m.imageData)
                            )
                        ) else notes.append("\n[系统] 用户附带了一张图片，但当前模型不支持图像输入，图片未发送。不要假装看到，按「模型能力声明」绕行（shell 转码/screen 读控件树），必要时提示换支持图像的模型。")
                    }
                    // 音频：有 audio-in 时读文件转 input_audio 直发（OpenAI 兼容），否则注记降级
                    if (!m.audioPath.isNullOrBlank()) {
                        if (caps.hasAudio) {
                            val b64 = runCatching {
                                val f = java.io.File(m.audioPath)
                                if (f.exists() && f.length() <= 8L * 1024 * 1024)
                                    android.util.Base64.encodeToString(f.readBytes(), android.util.Base64.NO_WRAP)
                                else null
                            }.getOrNull()
                            if (b64 != null) {
                                val fmt = m.audioPath.substringAfterLast('.', "mp3").lowercase()
                                    .let { if (it == "mpeg") "mp3" else it }
                                parts.add(
                                    com.haoai.agent.agent.provider.ApiContentPart(
                                        type = "input_audio",
                                        inputAudio = com.haoai.agent.agent.provider.ApiInputAudio(data = b64, format = fmt)
                                    )
                                )
                            } else notes.append("\n[系统] 用户附带了音频，但文件过大/不可读，未发送。请用 shell 工具处理本机文件 ${m.audioPath}（转码/截取片段），不要中断任务。")
                        } else notes.append("\n[系统] 用户附带了音频（本机文件 ${m.audioPath}），但当前模型不支持音频输入，未发送。请用 shell/ASR 工具转写文本后再处理，不要中断任务。")
                    }
                    // 视频：几乎无 chat 模型原生支持，统一给本地路径让 Agent 用 ffmpeg 抽帧/抽音轨绕行
                    if (!m.videoPath.isNullOrBlank()) {
                        notes.append("\n[系统] 用户附带了视频文件，本机路径：${m.videoPath}。当前模型不直接处理视频，请用 shell 工具（ffmpeg 抽关键帧后逐帧当图像分析、或抽音轨转写）提取信息，不要中断任务。")
                    }
                    text = text + notes.toString()
                    if (parts.isEmpty()) ApiMessage(role = "user", content = text)
                    else {
                        parts.add(0, com.haoai.agent.agent.provider.ApiContentPart(type = "text", text = text))
                        ApiMessage(role = "user", content = null, parts = parts)
                    }
                }
                ChatMessage.ROLE_ASSISTANT -> {
                    if (m.content.isBlank() && m.toolCalls.isEmpty()) null
                    else ApiMessage(
                        role = "assistant",
                        content = m.content.ifBlank { null },
                        toolCalls = m.toolCalls.map {
                            toolCallToApi(it.id, it.name, it.argumentsJson)
                        }.ifEmpty { null }
                    )
                }
                ChatMessage.ROLE_TOOL -> ApiMessage(
                    role = "tool",
                    content = TextCap.middle(m.content, REQ_CAP),
                    toolCallId = m.toolCallId,
                    name = m.toolName
                )
                else -> null
            }
        }
    return listOf(ApiMessage(role = "system", content = systemText)) + history
}

/**
 * 在系统提示前注入压缩摘要（如果有），并把**本回合的软提醒挂在请求末尾**。
 *
 * [nudges] 是成本/轮次这类"让模型接下来收敛一点"的提示：它们只对本次请求有意义，
 * 所以走这条临时通道，而不是 appendAndNotify 落进会话历史——落库的提醒会被之后每一轮
 * 反复重传（一条 ≈120 tokens × 剩余所有轮次），而且回合结束后它已经没有任何价值。
 * 角色仍用 user：与落库版本同形，各家网关的配对校验行为已经过实测，不引入新协议风险。
 */
internal fun AgentEngine.buildApiMessagesWithSummary(
    nudges: List<String> = emptyList()
): List<ApiMessage> {
    val msgs = buildApiMessages()
    val tail = nudges.map { ApiMessage(role = "user", content = it) }
    val base = if (tail.isEmpty()) msgs else msgs + tail
    val summary = session.compactionSummary
    if (summary.isNullOrBlank()) return base
    // 将摘要作为系统消息前缀注入
    val summaryMsg = ApiMessage(
        role = "system",
        content = "[上下文压缩摘要]\n$summary\n[/上下文压缩摘要]\n\n以上是之前对话的压缩摘要，请基于此继续。"
    )
    return listOf(summaryMsg) + base
}

/**
 * token 占用逼近上限时，注入一次性提示让模型主动调用 handoff 压缩上下文。
 * 与自动压缩同口径按 token 占用比例触发（旧版按消息条数，短消息密集时占用不足 10% 就误触发）；
 * 以近期是否已有交接文档 / 是否已提示过来去重。
 */
internal fun AgentEngine.maybeNudgeHandoff(onEvent: (TurnEvent) -> Unit) {
    val msgs = session.messages
    if (msgs.size < 8) return
    val recentNudged = msgs.takeLast(6).any {
        it.role == ChatMessage.ROLE_USER && it.content.contains("[系统提示]")
    }
    val recentHasDoc = msgs.takeLast(20).any {
        it.role == ChatMessage.ROLE_USER && it.content.startsWith(HANDOFF_MARKER)
    }
    if (recentNudged || recentHasDoc) return
    val isLocal = provider.baseUrl.contains("127.0.0.1") || provider.baseUrl.startsWith("local")
    val contextWindow = if (isLocal) 32768 else provider.effectiveContextLength()
    if (contextWindow <= 0) return
    // 与 maybeCompact 共用同一个口径（真值优先），两处不再各算一份近似
    val usedTokens = estimateContextTokens()
    if (usedTokens.toFloat() / contextWindow < HANDOFF_NUDGE_RATIO) return
    appendAndNotify(
        ChatMessage(
            role = ChatMessage.ROLE_USER,
            content = "[系统提示] 对话历史即将超出上下文窗口。请立即调用 handoff 工具，" +
                "用五段式（目标/约束/已完成/关键决定/下一步）总结当前任务，然后继续执行。"
        ),
        onEvent
    )
}
