package com.haoai.agent.agent.engine

import com.haoai.agent.agent.memory.DailyJournal
import com.haoai.agent.agent.memory.MemoryBank
import com.haoai.agent.agent.model.ChatMessage
import com.haoai.agent.agent.model.ToolCallData
import com.haoai.agent.agent.policy.ApprovalRequest
import com.haoai.agent.agent.policy.PolicyEngine
import com.haoai.agent.agent.provider.ApiMessage
import com.haoai.agent.agent.provider.OpenAiCompatClient
import com.haoai.agent.agent.provider.SseEvent
import com.haoai.agent.agent.tools.SubAgentRunner
import com.haoai.agent.agent.tools.TodoStore
import com.haoai.agent.agent.tools.TextCap
import com.haoai.agent.agent.tools.optBool
import com.haoai.agent.agent.tools.Tool
import com.haoai.agent.agent.tools.ToolContext
import com.haoai.agent.agent.tools.ToolRegistry
import com.haoai.agent.agent.tools.ToolResult
import com.haoai.agent.agent.tools.optDouble
import com.haoai.agent.agent.tools.optInt
import com.haoai.agent.agent.tools.optString
import com.haoai.agent.agent.tools.toApi
import com.haoai.agent.agent.tools.toolCallToApi
import com.haoai.agent.data.HaoJson
import com.haoai.agent.data.ProviderConfig
import com.haoai.agent.data.StoredSession
import com.haoai.agent.data.toModel
import com.haoai.agent.data.toStored
import com.haoai.agent.platform.FileBackend
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AgentEngine(
    private val httpClient: com.haoai.agent.agent.provider.ProviderClient,
    private val provider: ProviderConfig,
    private val apiKey: String,
    private val customPrompt: String,
    private val policy: PolicyEngine,
    private val approve: suspend (ApprovalRequest) -> Boolean,
    private val session: StoredSession,
    private val persist: () -> Unit,
    private val backend: FileBackend?,
    private val appFilesDir: File,
    private val workspaceLabel: String,
    private val memoryBank: MemoryBank? = null,
    private val memoryEnabled: Boolean = true,
    private val journal: DailyJournal? = null,
    private val autoLearn: Boolean = true,
    private val reasoningEffort: String = "",
    private val okHttpClient: okhttp3.OkHttpClient? = null,
    private val appContext: android.content.Context? = null,
    private val identity: String = "",
    private val onUsage: (suspend (Long, Long) -> Unit)? = null,
    private val depth: Int = 0,
    private val backgroundScope: CoroutineScope? = null,
    /** 动态渲染自身运行状态（app_status 工具按需读取，不注入系统提示）。 */
    private val statusProvider: () -> String = { "" },
    /** 白名单设置修改（update_settings 工具，走用户审批）。 */
    private val configMutator: (JsonObject) -> String = { "设置修改不可用" },
    /** 工具状态变更回调（todo 修改后刷新 UI）。 */
    private val onToolChange: (() -> Unit)? = null,
    /** 4.3 虚拟屏后台自动化：设置页总开关 ∧ API 30+（由调用方合并判定）。 */
    private val vscreenEnabled: Boolean = false,
    /** 5.1 每日预算提示（≥70% 注入精简提醒、超预算注入警告），由调用方按设置计算。 */
    private val budgetHint: () -> String = { "" },
    /** 5.3 模型路由：记忆提取专用 (provider, apiKey)；null/异常回落主模型。 */
    private val memoryTarget: (suspend () -> Pair<ProviderConfig, String>?)? = null,
    /** 5.3 模型路由：上下文压缩摘要专用 (provider, apiKey)。 */
    private val summarizeTarget: (suspend () -> Pair<ProviderConfig, String>?)? = null,
    /** 5.3 专用目标的协议客户端解析（缺省仍用主 httpClient）。 */
    private val auxClientFor: ((ProviderConfig) -> com.haoai.agent.agent.provider.ProviderClient)? = null
) {

    private val todoStore = TodoStore(appFilesDir)
    private val compactionManager = com.haoai.agent.agent.engine.compaction.CompactionManager(httpClient).apply {
        // 5.4 账本：压缩摘要调用记账（purpose=compact）
        onLlmUsage = { pin, pout, ok ->
            com.haoai.agent.data.UsageLedger.add(
                com.haoai.agent.data.UsageLedger.Entry(
                    kind = "llm", ts = System.currentTimeMillis(),
                    sessionId = session.id,
                    purpose = "compact", model = compactProvider?.model ?: provider.model,
                    promptTokens = pin, completionTokens = pout, ok = ok
                )
            )
        }
        clientResolver = { p -> auxClientFor?.invoke(p) ?: httpClient }
    }

    @Volatile private var compactProvider: ProviderConfig? = null

    /** 5.3 压缩摘要路由：配置了专用模型则返回 (provider, apiKey)，否则主模型。 */
    private suspend fun summarizerRoute(): Pair<ProviderConfig, String> {
        val t = runCatching { summarizeTarget?.invoke() }.getOrNull() ?: return provider to apiKey
        compactProvider = t.first
        return t.first to t.second
    }

    suspend fun runTurn(
        userText: String,
        onDelta: (String) -> Unit,
        onEvent: (TurnEvent) -> Unit,
        imageData: String? = null,
        onReasoning: (String) -> Unit = {}
    ) {
        // 整轮统计起点：用户发出 → 最终回复落库（含工具循环全部 LLM 调用与工具执行）
        val turnStartMs = System.currentTimeMillis()
        var turnPrompt = 0L
        var turnCompletion = 0L
        appendAndNotify(
            ChatMessage(
                role = ChatMessage.ROLE_USER,
                content = if (imageData != null) "[图片]\n$userText".trim() else userText,
                imageData = imageData
            ),
            onEvent
        )

        val shellDir = backend?.shellWorkdir()
            ?: File(appFilesDir, "shell-home").apply { mkdirs() }
        val ctx = ToolContext(
            backend, shellDir, todoStore, appFilesDir,
            sessionId = session.id,
            memoryBank, journal, depth, okHttpClient, appContext,
            statusProvider = statusProvider,
            configMutator = configMutator,
            onToolChange = onToolChange,
            vscreenEnabled = vscreenEnabled
        )
        val subAgentRunner: SubAgentRunner? =
            if (depth == 0) SubAgentRunner { task, parentCtx -> runSubAgent(task, parentCtx) } else null
        val tools = ToolRegistry.build(ctx, subAgentRunner) + com.haoai.agent.agent.tools.HandoffTool { summary, imp ->
            runCatching {
                journal?.append(handoffEvent(summary), importance = imp, source = "handoff")
            }
            compactHistory(summary)
        }
        val apiTools = tools.map { it.toApi() }

        // 压缩检查：在主循环前判断是否需要压缩
        maybeCompact(onEvent)

        val streamBuf = StringBuilder()
        try {
            var turns = 0
            while (true) {
                currentCoroutineContext().ensureActive()
                if (++turns > MAX_TURNS) {
                    appendAndNotify(
                        ChatMessage(
                            role = ChatMessage.ROLE_ASSISTANT,
                            content = "已达单次任务轮数上限（$MAX_TURNS）。请拆分任务或开新会话继续。"
                        ),
                        onEvent
                    )
                    break
                }

                streamBuf.setLength(0)
                val reasoningBuf = StringBuilder()
                var calls: List<ToolCallData> = emptyList()

                maybeNudgeHandoff(onEvent)

                try {
                    httpClient.chatStream(provider, apiKey, buildApiMessagesWithSummary(), apiTools, reasoningEffort.ifBlank { null })
                        .collect { ev ->
                            when (ev) {
                                is SseEvent.Delta -> {
                                    streamBuf.append(ev.text)
                                    onDelta(ev.text)
                                }
                                is SseEvent.Reasoning -> {
                                    reasoningBuf.append(ev.text)
                                    onReasoning(ev.text)
                                }
                                is SseEvent.Completed -> calls = ev.toolCalls
                                is SseEvent.Usage -> {
                                    turnPrompt += ev.promptTokens
                                    turnCompletion += ev.completionTokens
                                    onUsage?.invoke(ev.promptTokens.toLong(), ev.completionTokens.toLong())
                                }
                            }
                        }
                } catch (e: Exception) {
                    // Overflow 检测：自动压缩后重试一次
                    val emsg = e.message ?: ""
                    if (compactionManager.isOverflowError(emsg) && !compactionManager.isCoolingDown()) {
                        // 清掉首次失败已累积的半截流式输出，避免重试答案拼接在残句后面
                        streamBuf.setLength(0)
                        handleOverflow(emsg, onEvent) {
                            // 重试：重新收集
                            httpClient.chatStream(provider, apiKey, buildApiMessagesWithSummary(), apiTools, reasoningEffort.ifBlank { null })
                                .collect { ev ->
                                    when (ev) {
                                        is SseEvent.Delta -> { streamBuf.append(ev.text); onDelta(ev.text) }
                                        is SseEvent.Reasoning -> { reasoningBuf.append(ev.text); onReasoning(ev.text) }
                                        is SseEvent.Completed -> calls = ev.toolCalls
                                        is SseEvent.Usage -> {
                                            turnPrompt += ev.promptTokens
                                            turnCompletion += ev.completionTokens
                                            onUsage?.invoke(ev.promptTokens.toLong(), ev.completionTokens.toLong())
                                        }
                                    }
                                }
                        }
                    } else {
                        throw e
                    }
                }

                if (streamBuf.isNotBlank() || calls.isNotEmpty()) {
                    // calls 为空 = 本轮无工具调用、循环即将 break：这是最终回复，
                    // 把整轮累计的 token/耗时/模型名挂上（中间轮的 assistant 片段不带，避免重复展示）
                    val isFinal = calls.isEmpty()
                    appendAndNotify(
                        ChatMessage(
                            role = ChatMessage.ROLE_ASSISTANT,
                            content = streamBuf.toString(),
                            toolCalls = calls,
                            reasoning = reasoningBuf.toString().ifBlank { null },
                            promptTokens = if (isFinal) turnPrompt.toInt() else null,
                            completionTokens = if (isFinal) turnCompletion.toInt() else null,
                            durationMs = if (isFinal) System.currentTimeMillis() - turnStartMs else null,
                            model = if (isFinal) provider.model else null
                        ),
                        onEvent
                    )
                }
                currentCoroutineContext().ensureActive()

                if (calls.isEmpty()) break

                for (call in calls) {
                    currentCoroutineContext().ensureActive()
                    executeCall(call, tools, ctx, onEvent)
                }
            }
            onEvent(Finished(null))
            ledgerLlm("chat", turnPrompt, turnCompletion, System.currentTimeMillis() - turnStartMs, ok = true)
            maybeExtractMemory()
        } catch (ce: CancellationException) {
            ledgerLlm("chat", turnPrompt, turnCompletion, System.currentTimeMillis() - turnStartMs, ok = false)
            if (streamBuf.isNotBlank()) {
                appendAndNotify(
                    ChatMessage(
                        role = ChatMessage.ROLE_ASSISTANT,
                        content = streamBuf.toString() + "\n\n*[已停止]*",
                        // 用户中途停止：已消耗的部分也记上（best effort，供统计行展示）
                        promptTokens = turnPrompt.toInt().takeIf { it > 0 },
                        completionTokens = turnCompletion.toInt().takeIf { it > 0 },
                        durationMs = System.currentTimeMillis() - turnStartMs,
                        model = provider.model
                    ),
                    onEvent
                )
            }
            onEvent(Finished("已停止"))
            throw ce
        } catch (e: Exception) {
            ledgerLlm("chat", turnPrompt, turnCompletion, System.currentTimeMillis() - turnStartMs, ok = false)
            val msg = ChatMessage(
                role = ChatMessage.ROLE_ASSISTANT,
                content = "出错了：${e.message ?: e.javaClass.simpleName}",
                error = true
            )
            appendAndNotify(msg, onEvent)
            onEvent(Finished(e.message ?: "未知错误"))
        }
    }

    private suspend fun executeCall(
        call: ToolCallData,
        tools: List<Tool>,
        ctx: ToolContext,
        onEvent: (TurnEvent) -> Unit
    ) {
        onEvent(ToolChanged(ToolUpdate(call.id, ToolRunState.RUNNING, briefOf(call))))

        val tool = tools.firstOrNull { it.name == call.name }
        val args = parseArgs(call.argumentsJson)

        var result: ToolResult = ToolResult("")
        var finalState: ToolRunState = ToolRunState.DONE
        var toolStartMs = 0L
        var decision: String? = null

        when {
            tool == null -> {
                result = ToolResult("未知工具：${call.name}", true)
                finalState = ToolRunState.ERROR
                decision = "unknown"
            }

            call.name == "bash" && policy.checkShellBlocked(args.optString("command")) != null -> {
                result = ToolResult(policy.checkShellBlocked(args.optString("command")) ?: "被拦截", true)
                finalState = ToolRunState.DENIED
                decision = "blocked"
            }

            // update_settings 属于配置写操作：无论权限模式如何都必须经用户批准（上游 提案式）
            policy.requiresApproval(call.name) || call.name == "update_settings" -> {
                val request = buildApprovalRequest(call, args)
                val granted = approve(request)
                if (!granted) {
                    result = ToolResult("用户拒绝了本次操作。", true)
                    finalState = ToolRunState.DENIED
                    decision = "denied"
                } else {
                    decision = "approved"
                    snapshotBeforeWrite(call, args)
                    toolStartMs = System.currentTimeMillis()
                    result = invokeTool(tool, args, ctx)
                    if (result.isError) finalState = ToolRunState.ERROR
                }
            }

            else -> {
                // YOLO 等免审批模式同样要拍快照（5.5 回滚依赖），否则全自动下写入无档可回
                snapshotBeforeWrite(call, args)
                toolStartMs = System.currentTimeMillis()
                result = invokeTool(tool, args, ctx)
                if (result.isError) finalState = ToolRunState.ERROR
            }
        }
        if (toolStartMs > 0) {
            ledgerTool(
                call.name, System.currentTimeMillis() - toolStartMs,
                ok = !result.isError, decision = decision ?: "direct"
            )
        }

        val storedContent = TextCap.middle(result.content, STORED_CAP)
        val message = ChatMessage(
            role = ChatMessage.ROLE_TOOL,
            content = storedContent,
            toolCallId = call.id,
            toolName = call.name,
            error = result.isError
        )
        appendAndNotify(message, onEvent)
        // 图像注入通路（4.2 browser_screenshot）：工具结果带图时追加一条 user 图像消息，
        // 复用既有 imageData → image_url 转换，OpenAI/Anthropic 两协议均可消费
        result.imageDataUrl?.let { img ->
            appendAndNotify(
                ChatMessage(
                    role = ChatMessage.ROLE_USER,
                    content = "[${call.name}] 页面截图（当前视觉状态，供图像分析）",
                    imageData = img
                ),
                onEvent
            )
        }
        onEvent(
            ToolChanged(
                ToolUpdate(call.id, finalState, briefOf(call), previewOf(result.content))
            )
        )
    }

    /**
     * 1.3/5.5 写前快照：审批通过或免审批直执行时，文件尚未修改，此刻读原文最可靠。
     * 新建文件（原不存在）无原文可存，只有 after 可供回看（readText 对不存在文件抛异常，置 null）。
     */
    private suspend fun snapshotBeforeWrite(call: ToolCallData, args: JsonObject) {
        if (call.name != "write" && call.name != "edit") return
        runCatching {
            val path = args.optString("path")
            val before = runCatching { backend?.readText(path) }.getOrNull()
            val after = when (call.name) {
                "write" -> args.optString("content")
                else -> {
                    val old = args.optString("old_string")
                    val new = args.optString("new_string")
                    val replaceAll = args.optBool("replace_all")
                    before?.let {
                        if (replaceAll) it.replace(old, new) else it.replaceFirst(old, new)
                    }
                }
            }
            if (after != null) {
                com.haoai.agent.agent.tools.snapshot.FileSnapshot.snapshot(
                    appFilesDir, session.id, call.id, path, before, after
                )
            }
        }
    }

    private suspend fun invokeTool(tool: Tool, args: JsonObject, ctx: ToolContext): ToolResult =
        try {
            // bash（3.3 多后端）允许显式放宽到 600s（长构建），按请求 +20s 余量；其余工具维持 180s
            val budget = if (tool.name == "bash") {
                (args.optInt("timeout_ms") ?: 30_000).coerceIn(1000, 600_000) + 20_000L
            } else TOOL_TIMEOUT_MS
            // 工具实现普遍含文件/网络 IO：统一切到 IO 线程，避免卡主线程
            // （browser_* 工具内部自行 withContext(Main) 操作 WebView，嵌套切换安全）
            withTimeout(budget) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    tool.run(args, ctx)
                }
            }
        } catch (ce: kotlin.coroutines.cancellation.CancellationException) {
            // 区分「工具超时」与「用户停止」：超时只作废本次调用，不能静默杀掉整轮任务
            if (ce is kotlinx.coroutines.TimeoutCancellationException) {
                ToolResult("工具执行超时，请拆小任务或加大 timeout 重试", true)
            } else {
                throw ce
            }
        } catch (e: Exception) {
            ToolResult("工具执行失败：${e.message ?: e.javaClass.simpleName}", true)
        }

    private suspend fun runSubAgent(task: String, parentCtx: ToolContext): String {
        val childCtx = ToolContext(
            parentCtx.backend, parentCtx.shellDir, parentCtx.todoStore,
            parentCtx.appFilesDir, sessionId = parentCtx.sessionId,
            memoryBank = parentCtx.memoryBank, journal = parentCtx.journal, depth = parentCtx.depth + 1,
            httpClient = parentCtx.httpClient, appContext = parentCtx.appContext,
            statusProvider = parentCtx.statusProvider
        )  // httpClient/appContext 随 parentCtx 透传；子代理只读工具集，不注册相机定位与设置修改
        val tools = ToolRegistry.readOnly(childCtx)
        val apiTools = tools.map { it.toApi() }

        val msgs = mutableListOf(
            ApiMessage(role = "system", content = SUBAGENT_SYSTEM),
            ApiMessage(role = "user", content = task)
        )
        var finalText = ""
        var turns = 0
        var subPrompt = 0L
        var subCompletion = 0L
        val subStart = System.currentTimeMillis()
        while (turns++ < SUB_MAX_TURNS) {
            currentCoroutineContext().ensureActive()
            val buf = StringBuilder()
            var calls: List<ToolCallData> = emptyList()
            httpClient.chatStream(provider, apiKey, msgs, apiTools, reasoningEffort.ifBlank { null }).collect { ev ->
                when (ev) {
                    is SseEvent.Delta -> buf.append(ev.text)
                    is SseEvent.Reasoning -> Unit
                    is SseEvent.Completed -> calls = ev.toolCalls
                    is SseEvent.Usage -> { subPrompt += ev.promptTokens; subCompletion += ev.completionTokens }
                }
            }
            finalText = buf.toString()
            if (calls.isEmpty()) break
            msgs.add(
                ApiMessage(
                    role = "assistant",
                    content = finalText.ifBlank { null },
                    toolCalls = calls.map { toolCallToApi(it.id, it.name, it.argumentsJson) }
                )
            )
            for (call in calls) {
                currentCoroutineContext().ensureActive()
                val tool = tools.firstOrNull { it.name == call.name }
                val result = try {
                    withTimeout(TOOL_TIMEOUT_MS) {
                        tool?.run(parseArgs(call.argumentsJson), childCtx)
                    } ?: ToolResult("未知工具：${call.name}", true)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    ToolResult("失败：${e.message ?: e.javaClass.simpleName}", true)
                }
                msgs.add(
                    ApiMessage(
                        role = "tool",
                        content = TextCap.middle(result.content, 6000),
                        toolCallId = call.id,
                        name = call.name
                    )
                )
            }
        }
        ledgerLlm("subagent", subPrompt, subCompletion, System.currentTimeMillis() - subStart, ok = true)
        return finalText.ifBlank { "子代理未给出结论" }
    }

    /** 注入记忆时带上当前用户消息做主题相关性打分；markMemoryUse 仅在真实请求路径为 true（token 估算不计数）。 */
    private fun memorySnippet(markMemoryUse: Boolean = false): String {
        if (!memoryEnabled || depth != 0) return ""
        val bank = memoryBank ?: return ""
        val query = session.messages.lastOrNull { it.role == ChatMessage.ROLE_USER }?.content
        val (snippet, ids) = bank.promptSnippetIds(query?.take(300))
        if (markMemoryUse) bank.markInjected(ids)
        return snippet
    }

    private fun journalSnippet(): String =
        if (memoryEnabled && depth == 0) journal?.promptSnippet().orEmpty() else ""

    /** 从五段式交接文档提取「已完成/下一步」要点，作为当日事件落盘。 */
    private fun handoffEvent(summary: String): String {
        val picked = mutableListOf<String>()
        var section = ""
        for (raw in summary.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("#") -> section = line.trimStart('#').trim()
                line.startsWith("-") && (section.contains("已完成") || section.contains("下一步")) ->
                    picked.add(line.drop(1).trim())
            }
        }
        val body = picked.joinToString("；").take(200).ifBlank {
            summary.replace(Regex("[#*`>]"), "").lineSequence()
                .filter { it.isNotBlank() }.joinToString("；").take(180)
        }
        return "任务进展：$body"
    }

    /** 5.4 运行账本：LLM 调用记账（写失败静默，绝不影响主流程）。 */
    private fun ledgerLlm(purpose: String, promptTokens: Long, completionTokens: Long, durationMs: Long, ok: Boolean, model: String? = null, sessionId: String? = null) {
        if (purpose != "chat") android.util.Log.d("HaoLedger", "llm purpose=$purpose model=${model ?: provider.model} pin=$promptTokens pout=$completionTokens ok=$ok")
        com.haoai.agent.data.UsageLedger.add(
            com.haoai.agent.data.UsageLedger.Entry(
                kind = "llm", ts = System.currentTimeMillis(),
                sessionId = sessionId ?: session.id,
                purpose = purpose, model = model ?: provider.model,
                promptTokens = promptTokens.toInt(), completionTokens = completionTokens.toInt(),
                durationMs = durationMs, ok = ok
            )
        )
    }

    private fun ledgerTool(name: String, durationMs: Long, ok: Boolean, decision: String) {
        com.haoai.agent.data.UsageLedger.add(
            com.haoai.agent.data.UsageLedger.Entry(
                kind = "tool", ts = System.currentTimeMillis(),
                sessionId = session.id,
                tool = name, risk = policy.riskOf(name).name,
                policyDecision = decision, durationMs = durationMs, ok = ok
            )
        )
    }

    private fun maybeExtractMemory() {
        val bank = memoryBank ?: return
        val scope = backgroundScope ?: return
        if (!memoryEnabled || !autoLearn || depth != 0) return
        // 端侧模型跳过自动记忆提取：辅助请求会冲掉 llama-server 单 slot 前缀缓存，
        // 让 Agent 工具循环的每轮请求全量重算 prefill（手机上每轮多花几十秒）
        if (provider.baseUrl.contains("127.0.0.1") || provider.baseUrl.startsWith("local")) return
        val recent = session.messages
            .filter {
                (it.role == ChatMessage.ROLE_USER || it.role == ChatMessage.ROLE_ASSISTANT) &&
                    it.content.isNotBlank()
            }
            .takeLast(8)
        // 门槛：没有实质用户输入（纯指令/太短）就不值得沉淀，直接跳过
        val userText = recent.lastOrNull { it.role == ChatMessage.ROLE_USER }?.content.orEmpty()
        if (userText.length < 12) return
        val transcript = recent
            .joinToString("\n") { m ->
                val who = if (m.role == ChatMessage.ROLE_USER) "用户" else "助手"
                "$who：${m.content.take(500)}"
            }
            .trim()
        if (transcript.length < 80) return
        scope.launch {
            runCatching {
                // 5.3 记忆提取路由：配置了专用模型则走专用（挂起解析须在协程内）
                val memRoute = runCatching { memoryTarget?.invoke() }.getOrNull()
                val memProv = memRoute?.first ?: provider
                val memKey = memRoute?.second ?: apiKey
                val memClient = if (memRoute != null) (auxClientFor?.invoke(memProv) ?: httpClient) else httpClient
                val buf = StringBuilder()
                var mp = 0L; var mc = 0L
                val mstart = System.currentTimeMillis()
                memClient.chatStream(
                    memProv, memKey,
                    listOf(
                        ApiMessage(role = "system", content = EXTRACT_SYSTEM),
                        ApiMessage(role = "user", content = TextCap.middle(transcript, 4000))
                    ),
                    emptyList()
                ).collect { ev ->
                    when (ev) {
                        is SseEvent.Delta -> buf.append(ev.text)
                        is SseEvent.Usage -> { mp += ev.promptTokens; mc += ev.completionTokens }
                        else -> {}
                    }
                }
                ledgerLlm("memory", mp, mc, System.currentTimeMillis() - mstart, ok = true, model = memProv.model)
                parseMemories(buf.toString()).take(2).forEach { (content, tags) ->
                    bank.remember(content, tags, importance = 2, source = "auto")
                }
            }
        }
    }

    private fun parseMemories(text: String): List<Pair<String, List<String>>> {
        val start = text.indexOf('[')
        val end = text.lastIndexOf(']')
        if (start < 0 || end <= start) return emptyList()
        return runCatching {
            val arr = HaoJson.json.parseToJsonElement(text.substring(start, end + 1)).jsonArray
            arr.mapNotNull { el ->
                val obj = el as? JsonObject ?: return@mapNotNull null
                val content = obj["content"]?.jsonPrimitive?.contentOrNull?.trim() ?: return@mapNotNull null
                if (content.isEmpty()) return@mapNotNull null
                val tags = runCatching {
                    obj["tags"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
                }.getOrDefault(emptyList())
                content.take(300) to tags
            }
        }.getOrDefault(emptyList())
    }

    private fun buildSystemText(markMemoryUse: Boolean = false): String {
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
        return SystemPrompt.PREFIX +
            SystemPrompt.buildSuffix(
                workspaceLabel, shellAvailable, dateText, customPrompt, memorySnippet(markMemoryUse),
                a11yAvailable = com.haoai.agent.platform.a11y.HaoAccessibilityService.connected(),
                identity = identity,
                skillIndex = com.haoai.agent.agent.skills.SkillStore.promptIndex(),
                journalBlock = journalSnippet(),
                mcpSummary = com.haoai.agent.agent.mcp.McpManager.promptSummary(),
                shellNote = shellNote,
                vscreenAvailable = vscreenEnabled
            ) + budgetHint()
    }

    /** 真实固定开销估算（系统提示 + 工具定义），供压缩判断与 UI 使用量指示器；不发起网络。 */
    fun estimateOverheadTokens(): Pair<Int, Int> =
        com.haoai.agent.ui.chat.ContextUsage.estimateStringTokens(buildSystemText()) to TOOLS_BASE_TOKENS

    private fun buildApiMessages(): List<ApiMessage> {
        val systemText = buildSystemText(markMemoryUse = true)

        // 配对感知裁剪：窗口切割可能把 assistant(tool_calls) 切掉却留下它的 tool 结果，
        // OpenAI 兼容端会以 400 拒绝孤儿 tool 消息（且重试复现，会话就此卡死）。
        val history = session.messages.asReversed()
            .take(MAX_HISTORY)
            .asReversed()
            .repairBlankCallIds()
            .pairSanitized()
            .mapNotNull { m ->
                when (m.role) {
                    ChatMessage.ROLE_USER ->
                        if (!m.imageData.isNullOrBlank()) ApiMessage(
                            role = "user",
                            content = null,
                            parts = listOf(
                                com.haoai.agent.agent.provider.ApiContentPart(type = "text", text = m.content),
                                com.haoai.agent.agent.provider.ApiContentPart(
                                    type = "image_url",
                                    imageUrl = com.haoai.agent.agent.provider.ApiImageUrl(url = m.imageData)
                                )
                            )
                        )
                        else ApiMessage(role = "user", content = m.content)
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

    /** 在系统提示前注入压缩摘要（如果有）。 */
    private fun buildApiMessagesWithSummary(): List<ApiMessage> {
        val msgs = buildApiMessages()
        val summary = session.compactionSummary
        if (summary.isNullOrBlank()) return msgs
        // 将摘要作为系统消息前缀注入
        val summaryMsg = ApiMessage(
            role = "system",
            content = "[上下文压缩摘要]\n$summary\n[/上下文压缩摘要]\n\n以上是之前对话的压缩摘要，请基于此继续。"
        )
        return listOf(summaryMsg) + msgs
    }

    private fun appendAndNotify(msg: ChatMessage, onEvent: (TurnEvent) -> Unit) {
        session.messages.add(msg.toStored())
        persist()
        onEvent(MessageAdded(session.messages.last().toModel()))
    }

    /**
     * token 占用逼近上限时，注入一次性提示让模型主动调用 handoff 压缩上下文。
     * 与自动压缩同口径按 token 占用比例触发（旧版按消息条数，短消息密集时占用不足 10% 就误触发）；
     * 以近期是否已有交接文档 / 是否已提示过来去重。
     */
    private fun maybeNudgeHandoff(onEvent: (TurnEvent) -> Unit) {
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
        // 与 maybeCompact 相同口径：历史 + 系统提示 + 工具定义的真实占用
        val (sysTok, toolsTok) = estimateOverheadTokens()
        val usedTokens = msgs.sumOf {
            com.haoai.agent.ui.chat.ContextUsage.estimateMessageTokens(it.toModel())
        } + sysTok + toolsTok
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

    /** 用交接文档替换早期历史：保留文档 + 最近几条消息。 */
    private fun compactHistory(summary: String) {
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
     * 剔除因裁剪/压缩而失去配对的消息：
     * 1) 没有对应 assistant(tool_calls) 的孤儿 tool 结果；
     * 2) 其全部调用都缺少结果的 assistant(tool_calls)。
     * 保证发给供应商的历史始终满足严格的调用配对约束。
     */
    /**
     * 修复历史中遗留的空 tool call id（如 deepseek-v4-flash 偶发流式返回 "id": ""）。
     * 请求要求 assistant.tool_calls[].id 非空且与 tool.tool_call_id 严格配对：
     * 按最近 assistant 轮次的调用队列、同名优先，为空 id 派生一致的替代 id；
     * 配不上对的孤儿 tool 结果直接丢弃（后续 pairSanitized 也会兜底清理）。
     */
    private fun List<com.haoai.agent.data.StoredMessage>.repairBlankCallIds(): List<com.haoai.agent.data.StoredMessage> {
        val dirty = any { (it.role == ChatMessage.ROLE_ASSISTANT && it.toolCalls.any { c -> c.id.isBlank() }) ||
            (it.role == ChatMessage.ROLE_TOOL && it.toolCallId.isNullOrBlank()) }
        if (!dirty) return this
        var seq = 0
        val out = mutableListOf<com.haoai.agent.data.StoredMessage>()
        // 当前 assistant 轮次可分配的 id：name -> 待消费 id 队列
        var pendingIds = LinkedHashMap<String, MutableList<String>>()
        for (m in this) {
            when (m.role) {
                ChatMessage.ROLE_ASSISTANT -> {
                    pendingIds = LinkedHashMap()
                    val calls = m.toolCalls.map { c ->
                        val id = if (c.id.isBlank()) "call_fix_${seq++}" else c.id
                        pendingIds.getOrPut(c.name) { mutableListOf() }.add(id)
                        c.copy(id = id)
                    }
                    out += m.copy(toolCalls = calls)
                }
                ChatMessage.ROLE_TOOL -> {
                    if (!m.toolCallId.isNullOrBlank()) { out += m; continue }
                    val id = pendingIds[m.toolName]?.removeFirstOrNull()
                        ?: pendingIds.values.firstOrNull { it.isNotEmpty() }?.removeFirstOrNull()
                    if (id != null) out += m.copy(toolCallId = id)
                }
                else -> out += m
            }
        }
        return out
    }

    private fun List<com.haoai.agent.data.StoredMessage>.pairSanitized(): List<com.haoai.agent.data.StoredMessage> {
        val calledIds = flatMap { if (it.role == ChatMessage.ROLE_ASSISTANT) it.toolCalls.map { c -> c.id } else emptyList() }
            .toSet()
        val step1 = filterNot { it.role == ChatMessage.ROLE_TOOL && (it.toolCallId == null || it.toolCallId !in calledIds) }
        val answeredIds = step1.filter { it.role == ChatMessage.ROLE_TOOL }.mapNotNull { it.toolCallId }.toSet()
        // 逐 call 过滤：多工具调用被中途取消时会产生「部分回答」的 assistant 消息，
        // 未回答的 call 若保留会导致 API 400，且坏历史已持久化、会话永久卡死
        val out = mutableListOf<com.haoai.agent.data.StoredMessage>()
        for (m in step1) {
            if (m.role == ChatMessage.ROLE_ASSISTANT && m.toolCalls.isNotEmpty()) {
                val answered = m.toolCalls.filter { it.id in answeredIds }
                if (answered.isEmpty()) continue
                out += if (answered.size == m.toolCalls.size) m else m.copy(toolCalls = answered)
            } else {
                out += m
            }
        }
        return out
    }

    private fun parseArgs(json: String): JsonObject =
        runCatching {
            HaoJson.json.parseToJsonElement(json.ifBlank { "{}" })
        }.getOrNull() as? JsonObject ?: buildJsonObject {}

    private suspend fun buildApprovalRequest(call: ToolCallData, args: JsonObject): ApprovalRequest =
        when (call.name) {
            "bash" -> ApprovalRequest.ExecOp(args.optString("command"))
            "write" -> {
                val path = args.optString("path")
                val newContent = args.optString("content")
                // 审批时文件尚未被改：现读现算 diff（1.3 审查视图数据源）
                val oldText = runCatching { backend?.readText(path) }.getOrNull()
                val diff = if (oldText != null) {
                    com.haoai.agent.ui.common.TextDiff.diffText(oldText, newContent).lines
                } else emptyList()
                ApprovalRequest.WriteOp(
                    "write",
                    path,
                    "${newContent.toByteArray(Charsets.UTF_8).size} 字节内容",
                    isNewFile = oldText == null,
                    diff = diff
                )
            }
            "edit" -> {
                val path = args.optString("path")
                val old = args.optString("old_string")
                val new = args.optString("new_string")
                val replaceAll = args.optBool("replace_all")
                val oldText = runCatching { backend?.readText(path) }.getOrNull()
                val newText = oldText?.let { if (replaceAll) it.replace(old, new) else it.replaceFirst(old, new) }
                val diff = if (oldText != null && newText != null) {
                    com.haoai.agent.ui.common.TextDiff.diffText(oldText, newText).lines
                } else emptyList()
                ApprovalRequest.WriteOp(
                    "edit",
                    path,
                    "- ${old.take(300)}\n+ ${new.take(300)}",
                    isNewFile = false,
                    diff = diff
                )
            }
            "update_settings" -> ApprovalRequest.Generic(
                "update_settings",
                settingsChangeSummary(args)
            )
            else -> ApprovalRequest.Generic(call.name, args.toString().take(400))
        }

    /** 把 update_settings 的参数翻译成人可读的变更清单，供审批弹窗展示。 */
    private fun settingsChangeSummary(args: JsonObject): String {
        val labels = mapOf(
            "reply_max_tokens" to "单次回复上限",
            "context_length" to "云端上下文窗口",
            "local_context_length" to "端侧上下文窗口",
            "memory_enabled" to "记忆系统",
            "auto_learn" to "自动学习",
            "deep_dream" to "闲置整理记忆"
        )
        return args.entries.joinToString("\n") { (k, v) ->
            val label = labels[k] ?: k
            when (v) {
                is kotlinx.serialization.json.JsonPrimitive -> "$label → ${v.content}"
                else -> "$label → $v"
            }
        }.ifBlank { "无变更" }.take(400)
    }

    private fun briefOf(call: ToolCallData): String {
        val args = parseArgs(call.argumentsJson)
        return when (call.name) {
            "bash" -> args.optString("command").lineSequence().firstOrNull()?.take(90) ?: ""
            "read", "write", "edit" -> args.optString("path")
            "grep" -> "/${args.optString("pattern")}/"
            "glob" -> args.optString("pattern")
            "web_fetch" -> args.optString("url")
            "web_search" -> args.optString("query")
            "todo" -> args.optString("action", "view") +
                args.optString("text").takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
            "memory" -> args.optString("action", "list") +
                (args.optString("content").ifBlank { args.optString("query") })
                .takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
            "screen" -> "读取屏幕"
            "tap" -> args.optInt("index")?.let { "[$it]" }
                ?: args.optString("text").ifBlank { args.optString("view_id") }
                .ifBlank { "(${args.optString("x")},${args.optString("y")})" }
            "swipe" -> "滑动"
            "scroll" -> "滚动 ${args.optString("direction", "down")}"
            "find" -> "查找「${args.optString("text")}」"
            "wait" -> "等待 ${args.optString("mode", "text")}" +
                args.optString("text").takeIf { it.isNotBlank() }?.let { "「$it」" }.orEmpty()
            "type_text" -> "输入：${args.optString("text").take(40)}"
            "key" -> args.optString("action")
            "launch_app" -> args.optString("package")
            "list_apps" -> "列出应用"
            "browser_search" -> "搜索「${args.optString("query")}」"
            "browser_open" -> args.optString("url")
            "browser_navigate" -> args.optString("url")
            "browser_read" -> "读取页面结构"
            "browser_click" -> args.optInt("index")?.let { "[$it]" } ?: ""
            "browser_input" -> "[${args.optInt("index")}] 输入：${args.optString("text").take(30)}"
            "browser_scroll" -> "滚动 ${args.optString("direction", "down")}"
            "browser_find" -> "查找「${args.optString("text")}」"
            "browser_back" -> "后退"
            "browser_screenshot" -> "页面截图"
            "schedule" -> args.optString("action", "list") +
                args.optString("name").takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
            "spawn_agent" -> args.optString("task").take(60)
            "spawn_agents" -> "${(args["tasks"] as? kotlinx.serialization.json.JsonArray)?.size ?: 0} 个并行子任务"
            "app_status" -> "读取运行状态"
            "update_settings" -> settingsChangeSummary(args).lineSequence().firstOrNull() ?: "修改设置"
            "camera" -> "拍照"
            "location" -> "获取当前位置"
            else -> ""
        }
    }

    private fun previewOf(content: String): String =
        content.lineSequence().firstOrNull()?.take(160) ?: ""

    // ── 上下文压缩 ──────────────────────────────────────────────────

    /** 检查是否需要压缩，需要则执行。 */
    private suspend fun maybeCompact(onEvent: (TurnEvent) -> Unit) {
        if (compactionManager.isCoolingDown()) return
        val (sp, sk) = summarizerRoute()
        val isLocal = sp.baseUrl.contains("127.0.0.1") || sp.baseUrl.startsWith("local")
        val contextWindow = if (isLocal) 32768 else sp.effectiveContextLength()
        val chatMsgs = session.messages.map { it.toModel() }
        // 用真实系统提示估算：记忆/日志/技能索引注入后可达 1 万+ tokens，
        // 旧的固定 3000 底数严重低估，导致压缩触发过晚、频繁撞 overflow
        val (sysTok, toolsTok) = estimateOverheadTokens()
        val usedTokens = chatMsgs.sumOf { com.haoai.agent.ui.chat.ContextUsage.estimateMessageTokens(it) } +
            sysTok + toolsTok
        if (!compactionManager.shouldCompact(usedTokens, contextWindow)) return

        // 预修剪工具输出
        val prunedMsgs = compactionManager.prePruneToolOutputs(chatMsgs)

        val result = compactionManager.compact(
            messages = prunedMsgs,
            existingSummary = session.compactionSummary,
            provider = sp,
            apiKey = sk,
            contextWindow = contextWindow
        )
        session.compactionSummary = result.summary
        trimCompactedHistory()
        persist()
        onEvent(MessageAdded(ChatMessage(
            role = ChatMessage.ROLE_ASSISTANT,
            content = "[系统] 上下文已压缩，释放约 ${result.tokensSaved} tokens"
        )))
    }

    /** Overflow 恢复：检测 API 返回的 context_length_exceeded 错误，自动压缩后重试。 */
    private suspend fun handleOverflow(
        error: String,
        onEvent: (TurnEvent) -> Unit,
        retryBlock: suspend () -> Unit
    ) {
        if (!compactionManager.isOverflowError(error)) throw Exception(error)
        if (compactionManager.isCoolingDown()) throw Exception(error)
        val (sp, sk) = summarizerRoute()
        val isLocal = sp.baseUrl.contains("127.0.0.1") || sp.baseUrl.startsWith("local")
        val contextWindow = if (isLocal) 32768 else sp.effectiveContextLength()
        val chatMsgs = session.messages.map { it.toModel() }

        // 强制压缩
        val result = compactionManager.compact(
            messages = chatMsgs,
            existingSummary = session.compactionSummary,
            provider = sp,
            apiKey = sk,
            contextWindow = contextWindow
        )
        session.compactionSummary = result.summary
        trimCompactedHistory()
        persist()
        onEvent(MessageAdded(ChatMessage(
            role = ChatMessage.ROLE_ASSISTANT,
            content = "[系统] 上下文溢出，已自动压缩并重试"
        )))
        retryBlock()
    }

    /**
     * 压缩成功后，把已被摘要覆盖的旧消息移出会话，仅保留 keepRecentTokens 内的尾部。
     * 不裁剪的话请求仍是「摘要 + 全量历史」，token 只增不减，压缩与 overflow 恢复形同虚设。
     */
    private fun trimCompactedHistory() {
        val msgs = session.messages
        val keep = compactionManager.keepRecentTokens()
        var acc = 0
        var keepFrom = msgs.size
        for (i in msgs.indices.reversed()) {
            acc += com.haoai.agent.ui.chat.ContextUsage.estimateMessageTokens(msgs[i].toModel())
            if (acc > keep) break
            keepFrom = i
        }
        // 对齐到用户消息边界：不能把 assistant(toolCalls)/tool 序列拦腰截断
        var start = keepFrom
        while (start < msgs.size && msgs[start].role != ChatMessage.ROLE_USER) start++
        if (start <= 0 || start >= msgs.size) return
        val kept = msgs.drop(start)
        msgs.clear()
        msgs.addAll(kept)
    }

    // ── 手动压缩（/compact 命令） ────────────────────────────────────

    suspend fun compactNow(): String? {
        val (sp, sk) = summarizerRoute()
        val isLocal = sp.baseUrl.contains("127.0.0.1") || sp.baseUrl.startsWith("local")
        val contextWindow = if (isLocal) 32768 else sp.effectiveContextLength()
        val chatMsgs = session.messages.map { it.toModel() }
        val result = compactionManager.compact(
            messages = chatMsgs,
            existingSummary = session.compactionSummary,
            provider = sp,
            apiKey = sk,
            contextWindow = contextWindow
        )
        session.compactionSummary = result.summary
        persist()
        return result.summary
    }

    // ── /btw 附带问题（不写入历史） ─────────────────────────────────

    suspend fun runBtw(
        question: String,
        onDelta: (String) -> Unit,
        onReasoning: (String) -> Unit,
        /** 5.4 账本用途标记；5.3 标题路由传 "title"。 */
        purpose: String = "btw"
    ) {
        val isLocal = provider.baseUrl.contains("127.0.0.1") || provider.baseUrl.startsWith("local")
        val contextWindow = if (isLocal) 32768 else provider.effectiveContextLength()

        val sysParts = mutableListOf<String>()
        if (customPrompt.isNotBlank()) sysParts.add(customPrompt)
        if (identity.isNotBlank()) sysParts.add(identity)
        val sysText = sysParts.joinToString("\n\n")

        val apiMessages = mutableListOf<com.haoai.agent.agent.provider.ApiMessage>()
        if (sysText.isNotBlank()) {
            apiMessages.add(com.haoai.agent.agent.provider.ApiMessage(role = "system", content = sysText))
        }
        session.messages.forEach { m ->
            apiMessages.add(com.haoai.agent.agent.provider.ApiMessage(
                role = when (m.role) {
                    ChatMessage.ROLE_USER -> "user"
                    ChatMessage.ROLE_ASSISTANT -> "assistant"
                    else -> "user"
                },
                content = m.content
            ))
        }
        apiMessages.add(com.haoai.agent.agent.provider.ApiMessage(role = "user", content = question))

        var btwUsage: Pair<Long, Long> = 0L to 0L
        val btwStart = System.currentTimeMillis()
        try {
            httpClient.chatStream(
                provider = provider,
                apiKey = apiKey,
                messages = apiMessages,
                tools = emptyList(),
                reasoningEffort = reasoningEffort.ifBlank { null }
            ).collect { ev ->
                when (ev) {
                    is SseEvent.Delta -> onDelta(ev.text)
                    is SseEvent.Reasoning -> onReasoning(ev.text)
                    is SseEvent.Usage -> btwUsage = ev.promptTokens.toLong() to ev.completionTokens.toLong()
                    else -> {}
                }
            }
            ledgerLlm(purpose, btwUsage.first, btwUsage.second, System.currentTimeMillis() - btwStart, ok = true)
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            ledgerLlm(purpose, btwUsage.first, btwUsage.second, System.currentTimeMillis() - btwStart, ok = false)
            throw e
        }
    }

    companion object {
        const val MAX_TURNS = 60
        const val MAX_HISTORY = 80
        const val TOOL_TIMEOUT_MS = 180_000L

        /** 工具定义 JSON 的近似 token 开销（工具数量与 schema 固定时误差可接受）。 */
        const val TOOLS_BASE_TOKENS = 3500
        const val STORED_CAP = 16_000
        const val REQ_CAP = 4_000
        const val SUB_MAX_TURNS = 10
        /** handoff 催办阈值：token 占用比例（自动压缩 0.5 之后、危险线 0.9 之前）。 */
        const val HANDOFF_NUDGE_RATIO = 0.75f
        const val HANDOFF_KEEP = 6
        const val HANDOFF_MARKER = "## 任务交接文档"

        val EXTRACT_SYSTEM = """
            [MEMORY-EXTRACT] 你是记忆守门员。只提取满足全部条件的稳定信息：
            1) 用户明确表达的长期偏好、身份信息（名字/职业/城市）、长期项目背景、重要约定或纠正你的教训；
            2) 半年后仍然有效；
            3) 不查资料就能复述价值。
            严禁提取：本次任务的执行细节、代码/命令、临时状态、寒暄、你自己的回答内容、常识。
            只输出 JSON 数组，每项 {"content":"...","tags":["..."]}，最多 2 条，每条一句话且自包含；
            没有符合条件的内容必须输出 []。拿不准就不记——漏记一条无关紧要，记错会永久污染记忆库。
        """.trimIndent()

        val SUBAGENT_SYSTEM = """
            [SUBAGENT] 你是被主代理派出的只读研究子代理。只做调研与只读操作（read/grep/glob/web_fetch/memory），
            禁止写入或执行命令。高效检索，最后输出简明、结构化的结论（要点 + 证据路径）。
        """.trimIndent()
    }
}
