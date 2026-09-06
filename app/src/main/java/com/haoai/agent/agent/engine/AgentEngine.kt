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
import com.haoai.agent.agent.tools.takeSafe
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
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
    /** C6/C1 config_set 落地（JSON patch → 桥严格校验入库），由 VM 层注入；每次调用引擎强制审批。 */
    private val configMutator: (suspend (JsonObject) -> com.haoai.agent.agent.tools.ToolResult)? = null,
    /** C6 config_get 数据源：渲染当前配置镜像（apiKey 掩码）。 */
    private val configRender: () -> String = { "" },
    /** C1 config_set 预检：合并补丁→同源解析→语义 diff；err 非空=补丁非法（免审批直接拒绝）。 */
    private val configPreview: suspend (JsonObject) -> com.haoai.agent.data.ConfigFileBridge.Preview = {
        com.haoai.agent.data.ConfigFileBridge.Preview(err = "配置预检不可用", diff = "")
    },
    /** 工具状态变更回调（todo 修改后刷新 UI）。 */
    private val onToolChange: (() -> Unit)? = null,
    /** 4.3 虚拟屏后台自动化：设置页总开关 ∧ API 30+（由调用方合并判定）。 */
    private val vscreenEnabled: Boolean = false,
    /** 4.3 虚拟屏画面码率档位（kbps），映射截图清晰度（见 VirtualScreenController.presetFor）。 */
    private val vscreenBitrateKbps: Int = 3000,
    /** 5.1 每日预算提示（≥70% 注入精简提醒、超预算注入警告），由调用方按设置计算。 */
    private val budgetHint: () -> String = { "" },
    /** 5.3 模型路由：记忆提取专用链（主+备用，借鉴 上游 模型组）；空=回落主模型。 */
    private val memoryTarget: (suspend () -> List<Pair<ProviderConfig, String>>)? = null,
    /** 5.3 模型路由：上下文压缩摘要专用链。 */
    private val summarizeTarget: (suspend () -> List<Pair<ProviderConfig, String>>)? = null,
    /** 5.3 专用目标的协议客户端解析（缺省仍用主 httpClient）。 */
    private val auxClientFor: ((ProviderConfig) -> com.haoai.agent.agent.provider.ProviderClient)? = null,
    /** 5.6 Plan 模式门：true 时 WRITE/EXEC 工具不执行，返回引导文本继续循环。 */
    private val planGate: () -> Boolean = { false },
    /** E5 单轮 token 熔断上限（prompt+completion 累计）；0=不限。无人值守默认 15 万，交互聊天走设置（默认 25 万）。 */
    private val turnTokenCap: Int = 150_000,
    /** E5b 圈数熔断：单轮工具调用累计上限；0=不限。防失控循环的主力（行业默认 20~500，取 80）。 */
    private val toolCallCap: Int = 80,
    /** E5b 软提醒：达单轮 token 上限 70% 时注入一次精简收尾提醒（不中断循环）。 */
    private val softBudgetWarn: Boolean = true,
    /** E5 连续工具失败熔断阈值（复用 E3 conFailCount）；0=仅 token 熔断。 */
    private val toolFailCap: Int = 8,
    /** E8 循环内插话队列：生成期间用户新指令入队，引擎在安全间隙合并注入。 */
    private val interjectQueue: java.util.concurrent.ConcurrentLinkedQueue<String>? = null
) {

    private val todoStore = TodoStore(appFilesDir)

    /** E1 轮次结束状态：由 runTurn 生命周期填写（idle/interrupted/turncapped/failed）；null=进行中。 */
    @Volatile var runEndState: String? = null

    /** E5 连续工具失败熔断信号（主循环尾检查后复位）。 */
    private var _loopFailedCap = false

    /** E4b tools_enable 生效标记：工具组变更后主循环尾重建工具清单（下一轮 LLM 请求生效）。 */
    @Volatile private var _groupsDirty = false

    /** E9 todo 进度联动：清单变更后的下一轮注入一次进度行。 */
    private var todoDirty = false
    private var todoLastSnapshot: List<com.haoai.agent.agent.tools.TodoItem>? = null

    /** E3 会话级工具连续失败计数（成功清零）。 */
    private val conFailCount = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** 5.7 已追加过自改进提示的技能（引擎生命周期=会话，天然满足每会话限一次）。 */
    private val hintedSkills = mutableSetOf<String>()

    /** E7b 横切 hooks：before（快照）+ after（E3 升级/技能提示/写文件校验），列表序执行。 */
    private val hooks: List<ToolHook> = listOf(
        SnapshotHook(appFilesDir, session.id, backend),
        EscalationHook(conFailCount),
        SkillHintHook(hintedSkills),
        ValidateWriteHook()
    )

    /** 5.6 本回合拦截过 WRITE/EXEC（供 UI 判定模型产出的是计划）。 */
    var planIntercepted: Boolean = false
        private set

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

    /** 5.3 压缩摘要路由链（点6 链化）：主+备用按序；空配置回落主模型。 */
    private suspend fun summarizerChain(): List<Pair<ProviderConfig, String>> {
        val t = runCatching { summarizeTarget?.invoke() }.getOrNull() ?: emptyList()
        val chain = if (t.isEmpty()) listOf(provider to apiKey) else t
        compactProvider = chain.first().first
        return chain
    }

    /** 点6：链式压缩——按序尝试各目标，单个失败（非取消）降级下一个；全失败抛最后错误。 */
    private suspend fun compactWithChain(
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

    suspend fun runTurn(
        userText: String,
        onDelta: (String) -> Unit,
        onEvent: (TurnEvent) -> Unit,
        imageData: String? = null,
        audioPath: String? = null,
        videoPath: String? = null,
        onReasoning: (String) -> Unit = {}
    ) {
        // 整轮统计起点：用户发出 → 最终回复落库（含工具循环全部 LLM 调用与工具执行）
        val turnStartMs = System.currentTimeMillis()
        var turnPrompt = 0L
        var turnCompletion = 0L
        /** E5b 单轮工具调用累计（圈数熔断计数）。 */
        var turnToolCalls = 0
        /** E5b 软提醒只注入一次。 */
        var softWarned = false
        /** 熔断触发后置位：本轮不执行工具，下一轮无工具纯文本总结后结束。 */
        var forceFinish = false
        appendAndNotify(
            ChatMessage(
                role = ChatMessage.ROLE_USER,
                content = when {
                    imageData != null -> "[图片]\n$userText".trim()
                    audioPath != null -> "[音频]\n$userText".trim()
                    videoPath != null -> "[视频]\n$userText".trim()
                    else -> userText
                },
                imageData = imageData,
                audioPath = audioPath,
                videoPath = videoPath
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
            configRender = configRender,
            onToolChange = onToolChange,
            vscreenEnabled = vscreenEnabled,
            vscreenBitrateKbps = vscreenBitrateKbps
        )
        val subAgentRunner: SubAgentRunner? =
            if (depth == 0) SubAgentRunner { task, parentCtx, index, total ->
                runSubAgent(task, parentCtx, index, total)
            } else null
        val handoffTool = com.haoai.agent.agent.tools.HandoffTool { summary, imp ->
            runCatching {
                journal?.append(handoffEvent(summary), importance = imp, source = "handoff")
            }
            compactHistory(summary)
        }
        val toolsEnableTool = com.haoai.agent.agent.tools.ToolsEnableTool { group ->
            enableToolGroup(group)
        }
        // E4b 工具分层：按会话 activeGroups 注入（null=全开兼容旧会话）；handoff/tools_enable 恒在
        var tools = ToolRegistry.build(ctx, subAgentRunner, session.activeGroups?.toSet()) +
            handoffTool + toolsEnableTool
        var apiTools = gateTools(tools.map { it.toApi() })

        // E4a 工具定义 token 估算动态化：真实序列化各工具 JSON 求和（兜底下限 3500；门控清空则记 0）
        _toolsTokenCache = if (apiTools.isEmpty()) 0 else estimateToolsTokens(apiTools)

        // 压缩检查：在主循环前判断是否需要压缩
        maybeCompact(onEvent)

        val streamBuf = StringBuilder()
        try {
            var turns = 0
            while (true) {
                currentCoroutineContext().ensureActive()
                // E1：已完成请求轮数持久化（turncapped 续跑参考）
                session.runTurnsUsed = turns
                if (++turns > MAX_TURNS) {
                    runEndState = com.haoai.agent.data.StoredSession.RUN_TURNCAPPED
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

                // E8 间隙 A：本轮工具执行结束、下一轮 LLM 请求前——合并注入插话
                val interjections = mutableListOf<String>()
                interjectQueue?.let { q ->
                    while (true) q.poll()?.let { interjections += it } ?: break
                }
                if (interjections.isNotEmpty()) {
                    appendAndNotify(
                        ChatMessage(
                            role = ChatMessage.ROLE_USER,
                            content = "[用户插话] " + interjections.joinToString(
                                separator = "\n"
                            )
                        ),
                        onEvent
                    )
                }

                // E9 todo 变更检测：本轮执行过 todo 工具 → 下一轮注入进度行
                val todoNow = todoStore.load(session.id)
                if (todoLastSnapshot != null && todoNow != todoLastSnapshot) {
                    todoDirty = true
                }
                todoLastSnapshot = todoNow

                maybeNudgeHandoff(onEvent)

                // 单轮流式收集（overflow 自动压缩后的重试复用同一段逻辑，避免双份维护）
                var lastStreamEventAt = 0L
                suspend fun collectStream() {
                    lastStreamEventAt = System.currentTimeMillis()
                    coroutineScope {
                        val collectJob = launch {
                            httpClient.chatStream(provider, apiKey, buildApiMessagesWithSummary(), apiTools, reasoningEffort.ifBlank { null })
                                .collect { ev ->
                                    lastStreamEventAt = System.currentTimeMillis()
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
                        // E9b 流式看门狗：90 秒无任何事件判定流挂死（供应商断流/SSE 假活），
                        // 中断后按瞬态错误走退避重试，而不是让整轮永远停在"正在思考"
                        while (collectJob.isActive) {
                            delay(5_000)
                            if (System.currentTimeMillis() - lastStreamEventAt > 90_000L) {
                                throw java.io.IOException("stream stall: 流式响应超过 90 秒无数据（看门狗中断）")
                            }
                        }
                    }
                }
                try {
                    collectStream()
                } catch (e: Exception) {
                    // Overflow 检测：自动压缩后重试一次
                    val emsg = e.message ?: ""
                    if (compactionManager.isOverflowError(emsg) && !compactionManager.isCoolingDown()) {
                        // 清掉首次失败已累积的半截流式输出，避免重试答案拼接在残句后面
                        streamBuf.setLength(0)
                        handleOverflow(emsg, onEvent) { collectStream() }
                    } else if (isTransientHttpError(e)) {
                        // 429/超时/网关抖动：供应商限流在多轮工具任务里很常见（每步一请求），
                        // 退避重试而不是整轮失败；等待期可被取消，清残句避免拼接错乱
                        val backoffsSec = intArrayOf(5, 12, 25)
                        var last: Exception? = e
                        for (sec in backoffsSec) {
                            onEvent(ToolChanged(ToolUpdate("rate-limit", ToolRunState.RUNNING, "供应商限流", "HTTP 错误，${sec}s 后自动重试")))
                            delay(sec * 1000L)
                            currentCoroutineContext().ensureActive()
                            streamBuf.setLength(0)
                            reasoningBuf.setLength(0)
                            try {
                                collectStream()
                                onEvent(ToolChanged(ToolUpdate("rate-limit", ToolRunState.DONE, "供应商限流", "已恢复")))
                                last = null
                                break
                            } catch (re: Exception) {
                                last = re
                                if (!isTransientHttpError(re)) throw re
                            }
                        }
                        last?.let { throw it }
                    } else {
                        throw e
                    }
                }

                // E5 单轮成本熔断（100%）：保留模型已生成内容，注入收尾指令，
                // 下一轮 apiTools 已清空 → 纯文本总结后自然结束（比直接 break 多一次调用，换来可用收场）。
                // forceFinish 已置位时跳过：否则累计值恒 ≥ 上限，收尾轮会在 [A] 再次触发熔断形成死循环
                if (!forceFinish && turnTokenCap > 0 && turnPrompt + turnCompletion >= turnTokenCap) {
                    runEndState = com.haoai.agent.data.StoredSession.RUN_TURNCAPPED
                    if (streamBuf.isNotBlank()) {
                        appendAndNotify(
                            ChatMessage(role = ChatMessage.ROLE_ASSISTANT, content = streamBuf.toString()),
                            onEvent
                        )
                    }
                    appendAndNotify(
                        ChatMessage(
                            role = ChatMessage.ROLE_USER,
                            content = "[成本熔断] 本轮已消耗约 ${turnPrompt + turnCompletion} tokens，达到上限（${turnTokenCap}）。请立即总结当前进度与剩余步骤，然后结束本轮，不要尝试调用工具。"
                        ),
                        onEvent
                    )
                    onEvent(ToolChanged(ToolUpdate("cap-break", ToolRunState.DONE, "成本熔断", "token 上限")))
                    apiTools = emptyList()
                    forceFinish = true
                    continue
                }

                if (streamBuf.isNotBlank() || calls.isNotEmpty()) {
                    // calls 为空（或熔断收尾轮）= 本轮无工具调用、循环即将 break：这是最终回复，
                    // 把整轮累计的 token/耗时/模型名挂上（中间轮的 assistant 片段不带，避免重复展示）
                    val isFinal = calls.isEmpty() || forceFinish
                    appendAndNotify(
                        ChatMessage(
                            role = ChatMessage.ROLE_ASSISTANT,
                            content = streamBuf.toString(),
                            toolCalls = maskSecretArgs(calls),
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

                if (calls.isEmpty() || forceFinish) break

                turnToolCalls += calls.size

                // E6 同轮多工具并行分组：READ 且在白名单内的调用并发执行
                // （ALWAYS_ASK 模式 READ 也审批，审批弹窗必须在主协程，不能进并发块）。
                // 工具体在 IO 协程并发（信号量限 4），事件/账本/落库统一回主协程按原 calls 顺序串行收尾：
                // 既保证会话回放顺序，也避免阻塞调用线程（与 browser_* 的 withContext(Main) 互等死锁）
                // 和多线程并发写 session.messages/liveTools 的数据竞争。
                val parallelCalls = calls.filter {
                    policy.riskOf(it.name) == com.haoai.agent.agent.policy.RiskLevel.READ &&
                        it.name in PARALLEL_SAFE &&
                        !policy.requiresApproval(it.name)
                }
                val serialCalls = calls.filterNot { it in parallelCalls }
                executeParallelCalls(parallelCalls, tools, ctx, onEvent)
                for (call in serialCalls) {
                    currentCoroutineContext().ensureActive()
                    executeCall(call, tools, ctx, onEvent)
                }
                // E4b tools_enable 生效点：组变更后重建工具清单，下一轮请求即带新组
                if (_groupsDirty) {
                    _groupsDirty = false
                    tools = ToolRegistry.build(ctx, subAgentRunner, session.activeGroups?.toSet()) +
                        handoffTool + toolsEnableTool
                    apiTools = gateTools(tools.map { it.toApi() })
                    _toolsTokenCache = if (apiTools.isEmpty()) 0 else estimateToolsTokens(apiTools)
                }
                // E5 连续工具失败熔断：达到阈值直接 break 输出失败总结
                if (_loopFailedCap) {
                    _loopFailedCap = false
                    appendAndNotify(
                        ChatMessage(
                            role = ChatMessage.ROLE_ASSISTANT,
                            content = "工具连续失败达到阈值，本轮已终止。请拆解任务或检查失败工具的用法。",
                        ),
                        onEvent
                    )
                    onEvent(ToolChanged(ToolUpdate("fail-cap", ToolRunState.DONE, "失败熔断", "连续工具失败")))
                    break
                }

                // E5b 圈数熔断：单轮工具调用累计达上限 → 注入收尾指令，下一轮无工具纯文本总结
                if (!forceFinish && toolCallCap > 0 && turnToolCalls >= toolCallCap) {
                    runEndState = com.haoai.agent.data.StoredSession.RUN_TURNCAPPED
                    appendAndNotify(
                        ChatMessage(
                            role = ChatMessage.ROLE_USER,
                            content = "[圈数熔断] 本轮已执行 $turnToolCalls 次工具调用，达到上限（$toolCallCap）。请立即总结当前进度与剩余步骤，然后结束本轮，不要尝试调用工具。"
                        ),
                        onEvent
                    )
                    onEvent(ToolChanged(ToolUpdate("call-cap", ToolRunState.DONE, "圈数熔断", "工具调用次数")))
                    apiTools = emptyList()
                    forceFinish = true
                }

                // E5b 软提醒（70%）：一次性注入，只提醒不熔断；已熔断或关闭软提醒时跳过
                if (softBudgetWarn && !softWarned && !forceFinish && turnTokenCap > 0 &&
                    turnPrompt + turnCompletion >= turnTokenCap * 0.7
                ) {
                    softWarned = true
                    appendAndNotify(
                        ChatMessage(
                            role = ChatMessage.ROLE_USER,
                            content = "[成本提醒] 本轮已消耗约 ${turnPrompt + turnCompletion} tokens，达到上限（${turnTokenCap}）的 70%。请精简后续步骤，尽快收尾任务。"
                        ),
                        onEvent
                    )
                    onEvent(ToolChanged(ToolUpdate("budget-warn", ToolRunState.DONE, "成本提醒", "70%")))
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
            runEndState = com.haoai.agent.data.StoredSession.RUN_FAILED
            ledgerLlm("chat", turnPrompt, turnCompletion, System.currentTimeMillis() - turnStartMs, ok = false)
            val msg = ChatMessage(
                role = ChatMessage.ROLE_ASSISTANT,
                content = friendlyError(e.message ?: e.javaClass.simpleName),
                error = true
            )
            appendAndNotify(msg, onEvent)
            onEvent(Finished(friendlyErrorText(e.message ?: "未知错误")))
        }
        if (runEndState == null) runEndState = com.haoai.agent.data.StoredSession.RUN_IDLE
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
        // E7a：按调用注入 callId 与子代理进度上报桥（子代理工具经 parentCtx 回调 → SubagentUpdate 事件）
        val callCtx = subagentBridge(ctx, call, onEvent)

        var result: ToolResult = ToolResult("")
        var finalState: ToolRunState = ToolRunState.DONE
        var toolStartMs = 0L
        var decision: String? = null
        var handledByHook = false

        // 5.6 Plan 模式：READ 之外的工具一律不执行，引导模型产出计划文本
        if (planGate() && tool != null && policy.riskOf(call.name) != com.haoai.agent.agent.policy.RiskLevel.READ) {
            this.planIntercepted = true
            result = ToolResult(
                "[Plan 模式] 工具 ${call.name} 已被拦截（计划阶段不执行改动）。" +
                    "请基于已有信息给出完整执行计划：步骤、涉及文件、预期结果，等待用户批准后再执行。",
                false
            )
            finalState = ToolRunState.DONE
            val storedP = TextCap.middle(result.content, STORED_CAP)
            appendAndNotify(
                ChatMessage(role = ChatMessage.ROLE_TOOL, content = storedP, toolCallId = call.id, toolName = call.name),
                onEvent
            )
            onEvent(ToolChanged(ToolUpdate(call.id, ToolRunState.DONE, briefOf(call), "已拦截（Plan 模式）")))
            return
        }

        // E7b before hooks：Plan 门与审批之后、工具执行之前（快照在此拍）。返回 Handled 时直接落库
        try {
            for (h in hooks) {
                if (h.names.isNotEmpty() && call.name !in h.names) continue
                val d = h.before(call, args, callCtx)
                if (d is ToolHook.HookDecision.Handled) {
                    result = d.result
                    finalState = if (d.result.isError) ToolRunState.ERROR else ToolRunState.DONE
                    decision = "hook"
                    handledByHook = true
                    break
                }
            }
        } catch (ce: kotlin.coroutines.cancellation.CancellationException) {
            throw ce
        } catch (e: Exception) {
            android.util.Log.w("HaoEngine", "hook before failed: ${e.message}")
        }

        when {
            // hook 已处理（如 Plan 拦截的等价 case），跳过工具执行
            handledByHook -> Unit

            tool == null -> {
                // E4b：模型可能凭系统提示里的分组描述臆测调用未注入的工具 —— 给出 tools_enable 引导
                result = unknownToolResult(call.name)
                finalState = ToolRunState.ERROR
                decision = "unknown"
            }

            call.name == "bash" && policy.checkShellBlocked(args.optString("command")) != null -> {
                result = ToolResult(policy.checkShellBlocked(args.optString("command")) ?: "被拦截", true)
                finalState = ToolRunState.DENIED
                decision = "blocked"
            }

            // C6/C1 config_set 恒审批：预检（补丁非法 → 免审批直接拒绝）→ 语义 diff 审批 → 同步 apply。
            // 无论权限模式如何都必须经用户批准（配置写不变量，含 permission_mode 自提权场景）。
            call.name == "config_set" -> {
                val preview = configPreview(args)
                if (preview.err != null) {
                    result = ToolResult(
                        "配置被拒绝：${preview.err}（未生效；修正参数后重新调用 config_set 即可）",
                        true
                    )
                    finalState = ToolRunState.ERROR
                    decision = "invalid"
                } else {
                    val granted = approve(ApprovalRequest.ConfigChange(preview.diff.ifBlank { "（无字段变化）" }))
                    if (!granted) {
                        result = ToolResult("用户拒绝了配置修改。", true)
                        finalState = ToolRunState.DENIED
                        decision = "denied"
                    } else {
                        decision = "approved"
                        toolStartMs = System.currentTimeMillis()
                        result = invokeTool(tool, args, callCtx)
                        if (result.isError) finalState = ToolRunState.ERROR
                    }
                }
            }

            policy.requiresApproval(call.name) -> {
                val request = buildApprovalRequest(call, args)
                val granted = approve(request)
                if (!granted) {
                    result = ToolResult("用户拒绝了本次操作。", true)
                    finalState = ToolRunState.DENIED
                    decision = "denied"
                } else {
                    decision = "approved"
                    toolStartMs = System.currentTimeMillis()
                    result = invokeTool(tool, args, callCtx)
                    if (result.isError) finalState = ToolRunState.ERROR
                }
            }

            else -> {
                // YOLO 等免审批模式同样要拍快照（5.5 回滚依赖），否则全自动下写入无档可回
                toolStartMs = System.currentTimeMillis()
                result = invokeTool(tool, args, callCtx)
                if (result.isError) finalState = ToolRunState.ERROR
            }
        }
        finishCall(call, args, callCtx, result, finalState, toolStartMs, decision, onEvent)
    }

    /** E7a：按调用注入 callId 与子代理进度上报桥（串行/并行两路共用）。 */
    private fun subagentBridge(ctx: ToolContext, call: ToolCallData, onEvent: (TurnEvent) -> Unit): ToolContext =
        ctx.copy(
            currentCallId = call.id,
            onSubagentEvent = { report ->
                onEvent(
                    SubagentUpdate(
                        call.id, report.index, report.total,
                        report.state, report.tokensUsed, report.brief
                    )
                )
            }
        )

    /** E4b：模型可能凭系统提示里的分组描述臆测调用未注入的工具 —— 给出 tools_enable 引导。 */
    private fun unknownToolResult(name: String): ToolResult {
        val g = ToolRegistry.groupOf(name)
        val groups = session.activeGroups?.toSet()
        return if (groups != null && g != ToolRegistry.GROUP_CORE && g !in groups) {
            ToolResult(
                "工具 $name 属于未启用的「$g」工具组。请先调用 tools_enable（group=\"$g\"）启用后再使用。",
                true
            )
        } else {
            ToolResult("未知工具：$name", true)
        }
    }

    /**
     * 执行收尾：账本 / E5 连续失败熔断 / E7b after hooks / 结果落库 / 状态事件。
     * 串行路径（executeCall）与并行段（executeParallelCalls）共用；
     * 会在主协程按序执行，禁止放进并发块（session.messages 与事件回调非线程安全）。
     */
    private suspend fun finishCall(
        call: ToolCallData,
        args: JsonObject,
        callCtx: ToolContext,
        result: ToolResult,
        finalState: ToolRunState,
        toolStartMs: Long,
        decision: String?,
        onEvent: (TurnEvent) -> Unit
    ) {
        if (toolStartMs > 0) {
            ledgerTool(
                call.name, System.currentTimeMillis() - toolStartMs,
                ok = !result.isError, decision = decision ?: "direct"
            )
        }
        // E5 连续工具失败熔断：达到阈值直接 break 并输出失败总结（不交给模型发挥）
        if (toolFailCap > 0 && result.isError && (conFailCount[call.name] ?: 0) >= toolFailCap) {
            _loopFailedCap = true
        }

        // E7b hooks：E3 失败升级 / 5.7 技能提示 / E7c 写文件校验统一在 after 阶段按列表序执行
        var finalResult = result
        for (h in hooks) {
            if (h.names.isNotEmpty() && call.name !in h.names) continue
            finalResult = h.after(call, args, callCtx, finalResult)
        }
        var storedContent = TextCap.middle(finalResult.content, STORED_CAP)
        val message = ChatMessage(
            role = ChatMessage.ROLE_TOOL,
            content = storedContent,
            toolCallId = call.id,
            toolName = call.name,
            error = finalResult.isError
        )
        appendAndNotify(message, onEvent)
        // 图像注入通路（4.2 browser_screenshot）：工具结果带图时追加一条 user 图像消息，
        // 复用既有 imageData → image_url 转换，OpenAI/Anthropic 两协议均可消费
        finalResult.imageDataUrl?.let { img ->
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

    /** E6 并行段单次调用的准备态：参数解析在主协程完成，工具体并发执行。 */
    private class PreparedCall(
        val call: ToolCallData,
        val tool: Tool?,
        val args: JsonObject,
        val ctx: ToolContext
    )

    /**
     * E6 并行执行：READ 白名单调用在 IO 协程并发跑工具体（信号量限 4），
     * 结果按原 calls 顺序在主协程经 finishCall 串行收尾（顺序可回放，零数据竞争）。
     * 单调用退化为完整 executeCall（保留 Plan 门等特判语义）。
     */
    private suspend fun executeParallelCalls(
        calls: List<ToolCallData>,
        tools: List<Tool>,
        ctx: ToolContext,
        onEvent: (TurnEvent) -> Unit
    ) {
        if (calls.isEmpty()) return
        if (calls.size == 1) {
            executeCall(calls.first(), tools, ctx, onEvent)
            return
        }
        val prepared = calls.map { call ->
            PreparedCall(call, tools.firstOrNull { it.name == call.name }, parseArgs(call.argumentsJson), ctx)
        }
        prepared.forEach { p ->
            onEvent(ToolChanged(ToolUpdate(p.call.id, ToolRunState.RUNNING, briefOf(p.call))))
        }
        val gate = Semaphore(PARALLEL_MAX_CONCURRENCY)
        coroutineScope {
            val bodies = prepared.map { p ->
                async(Dispatchers.IO) {
                    gate.withPermit { runParallelBody(p) }
                }
            }
            prepared.zip(bodies).forEach { (p, body) ->
                val (result, state, elapsed) = body.await()
                finishCall(p.call, p.args, p.ctx, result, state, elapsed, "direct", onEvent)
            }
        }
    }

    /** E6 并行工具体：白名单调用均为 READ 免审批，直接执行即可。 */
    private suspend fun runParallelBody(p: PreparedCall): Triple<ToolResult, ToolRunState, Long> {
        if (p.tool == null) return Triple(unknownToolResult(p.call.name), ToolRunState.ERROR, 0L)
        val start = System.currentTimeMillis()
        val result = invokeTool(p.tool, p.args, p.ctx)
        return Triple(result, if (result.isError) ToolRunState.ERROR else ToolRunState.DONE, System.currentTimeMillis() - start)
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

    /** E6 并行段辅助：在 CoroutineScope 接收者内 map async（并发 ≤4 由调用方分桶）。 */
    private suspend fun runSubAgent(task: String, parentCtx: ToolContext, index: Int = 1, total: Int = 1): String {
        val childCtx = ToolContext(
            parentCtx.backend, parentCtx.shellDir, parentCtx.todoStore,
            parentCtx.appFilesDir, sessionId = parentCtx.sessionId,
            memoryBank = parentCtx.memoryBank, journal = parentCtx.journal, depth = parentCtx.depth + 1,
            httpClient = parentCtx.httpClient, appContext = parentCtx.appContext,
            statusProvider = parentCtx.statusProvider
        )  // httpClient/appContext 随 parentCtx 透传；子代理只读工具集，不注册相机定位与设置修改
        // E7a 进度上报（经 parentCtx 回调；失败也上报 ERROR，不拖垮整卡）
        fun report(state: String, tokens: Long, brief: String) {
            try {
                parentCtx.onSubagentEvent?.invoke(
                    com.haoai.agent.agent.engine.SubagentReport(index, total, state, tokens, brief)
                )
            } catch (_: Exception) {
            }
        }
        report("RUNNING", 0, task)
        val tools = ToolRegistry.readOnly(childCtx)
        val apiTools = gateTools(tools.map { it.toApi() })
        // 注意：不更新引擎级 _toolsTokenCache——那是主循环工具清单的开销估算，
        // 子代理只读工具集远小于主清单，覆写会让压缩/催办判断在本轮剩余时间持续低估

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
            try {
                httpClient.chatStream(provider, apiKey, msgs, apiTools, reasoningEffort.ifBlank { null }).collect { ev ->
                    when (ev) {
                        is SseEvent.Delta -> buf.append(ev.text)
                        is SseEvent.Reasoning -> Unit
                        is SseEvent.Completed -> calls = ev.toolCalls
                        is SseEvent.Usage -> { subPrompt += ev.promptTokens; subCompletion += ev.completionTokens }
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                ledgerLlm("subagent", subPrompt, subCompletion, System.currentTimeMillis() - subStart, ok = false)
                report("ERROR", subPrompt + subCompletion, "失败：${e.message ?: e.javaClass.simpleName}")
                throw e
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
        report("DONE", subPrompt + subCompletion, finalText.ifBlank { "（未给出结论）" })
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
            // 5.3 记忆提取路由链（点6）：主+备用按序尝试，空配置回落主模型
            val memChain = runCatching { memoryTarget?.invoke() }.getOrNull() ?: emptyList()
            val memTargets = if (memChain.isEmpty()) listOf(provider to apiKey) else memChain
            for ((memProv, memKey) in memTargets) {
                val r = runCatching {
                    val memClient = auxClientFor?.invoke(memProv) ?: httpClient
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
                if (r.isSuccess) break
                (r.exceptionOrNull() as? kotlinx.coroutines.CancellationException)?.let { throw it }
                android.util.Log.w("HaoMemory", "记忆提取目标 ${memProv.name}/${memProv.model} 失败，降级链上下一个")
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

    /** E4a/E4b 工具定义 token 估算：真实序列化各工具 JSON 求和（兜底下限 3500）。 */
    private fun estimateToolsTokens(apiTools: List<com.haoai.agent.agent.provider.ApiTool>): Int = runCatching {
        apiTools.sumOf { t ->
            com.haoai.agent.ui.chat.ContextUsage.estimateStringTokens(
                com.haoai.agent.data.HaoJson.json.encodeToString(
                    com.haoai.agent.agent.provider.ApiTool.serializer(), t
                )
            )
        }
    }.getOrNull()?.coerceAtLeast(TOOLS_BASE_TOKENS) ?: TOOLS_BASE_TOKENS

    /** E4b tools_enable 回调：把组并入会话 activeGroups、持久化并标记主循环重建工具清单。 */
    private fun enableToolGroup(group: String): String {
        val g = group.trim().lowercase()
        if (g == ToolRegistry.GROUP_CORE) {
            return "core 组是常驻工具组（读写/编辑/bash/搜索/记忆/待办等），无需也无法启用。"
        }
        if (g != ToolRegistry.GROUP_EXTENDED && g != ToolRegistry.GROUP_MCP) {
            return "未知工具组「$group」。可用组：extended（无障碍操作/内置浏览器/虚拟屏/相机/定位/设备工具包/工作流等）、mcp（MCP 外部服务器工具）。"
        }
        val current = session.activeGroups?.toSet() ?: ToolRegistry.ALL_GROUPS
        if (g in current) return "工具组 $g 已处于启用状态。"
        session.activeGroups = (current + g).toSortedSet().toList()
        persist()
        _groupsDirty = true
        return "已启用 $g 工具组，相关工具已注入（本会话保持）。现在可以直接使用该组的工具。"
    }

    /** E4b 分层注入提示：告知模型未加载的工具组与启用方式（全开/无缺省时为空）。 */
    private fun toolsGroupHintText(): String {
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

    private fun buildSystemText(markMemoryUse: Boolean = false): String =
        buildSystemTextWithInjected(markMemoryUse).first

    /**
     * (系统提示全文, 动态注入块拼接文本[记忆/技能索引/日志/MCP 摘要/预算提示])。
     * 第二项仅供上下文用量拆分估算（estimateOverheadBreakdown）；真实请求路径只用全文。
     * token 启发式按字符线性可加减，基础提示 = 全文 − 注入，buildSuffix 为注入块
     * 加的小标题归入基础提示（误差可忽略）。
     */
    private fun buildSystemTextWithInjected(markMemoryUse: Boolean = false): Pair<String, String> {
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
        // 能力声明片段（对齐 上游 capabilityPromptFragment）：把当前模型的输入/输出
        // 模态与工具支持显式写进系统提示词，让模型知道边界并主动绕行，而非中途引用被剥离
        // 的能力导致任务断裂报错。端侧模型（llama）不走此注入（本地能力另由 vision 探测）。
        val capabilityNote =
            if (provider.baseUrl.contains("127.0.0.1") || provider.baseUrl.startsWith("local")) ""
            else com.haoai.agent.data.CapabilityResolver.capabilityPromptFragment(provider.caps()).orEmpty()
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

    /**
     * E9 todo 进度行：仅在真实请求路径（consume=true，buildApiMessages）注入并消费标志；
     * 估算路径（estimateOverheadTokens/maybeNudgeHandoff）只读不消费——否则标志会被
     * 估算先行清掉，进度行永远进不了真实请求。
     */
    private fun todoProgressLine(consume: Boolean): String {
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

    /** 真实固定开销估算（系统提示 + 工具定义），供压缩判断与 UI 使用量指示器；不发起网络。 */
    private var _toolsTokenCache: Int? = null

    /** 能力门控（统一走 CapabilityResolver）：模型标记不支持 tools 时清空工具清单，降级纯对话。 */
    private fun gateTools(apiTools: List<com.haoai.agent.agent.provider.ApiTool>): List<com.haoai.agent.agent.provider.ApiTool> =
        if (provider.caps().tools == false) emptyList() else apiTools

    fun estimateOverheadTokens(): Pair<Int, Int> =
        com.haoai.agent.ui.chat.ContextUsage.estimateStringTokens(buildSystemText()) to
            (_toolsTokenCache ?: TOOLS_BASE_TOKENS)

    /** 拆分估算 (基础系统提示, 动态注入[记忆/技能/日志/MCP/预算], 工具定义)，供上下文详情面板。 */
    fun estimateOverheadBreakdown(): Triple<Int, Int, Int> {
        val (full, injected) = buildSystemTextWithInjected()
        val sysTok = com.haoai.agent.ui.chat.ContextUsage.estimateStringTokens(full)
        val injTok = com.haoai.agent.ui.chat.ContextUsage.estimateStringTokens(injected)
        return Triple(
            (sysTok - injTok).coerceAtLeast(0),
            injTok,
            _toolsTokenCache ?: TOOLS_BASE_TOKENS
        )
    }

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

    /**
     * C2 补口：config_set 补丁里的明文密钥不进会话 JSON 与压缩摘要上下文
     * （执行已按原始参数完成，历史回放只需协议有效的 arguments）。
     */
    private fun maskSecretArgs(calls: List<ToolCallData>): List<ToolCallData> =
        calls.map { c ->
            if (c.name == "config_set") {
                c.copy(argumentsJson = com.haoai.agent.data.ConfigFileBridge.maskApiKeys(c.argumentsJson))
            } else c
        }

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
            // C1：config_set 审批展示语义 diff（preview 失败时不会走到这里——executeCall 先行拦截）
            "config_set" -> ApprovalRequest.ConfigChange(configChangeSummary(args))
            else -> ApprovalRequest.Generic(call.name, args.toString().take(400))
        }

    /** C1 语义 diff：合并补丁 → 同源解析 → 与当前配置对比生成人读变更清单（后台执行，失败给可读原因）。 */
    private suspend fun configChangeSummary(args: JsonObject): String =
        configPreview.invoke(args).let { p ->
            p.err ?: p.diff.ifBlank { "（无字段变化）" }
        }

    private fun briefOf(call: ToolCallData): String =
        com.haoai.agent.agent.tools.ToolBrief.of(call.name, call.argumentsJson)

    private fun previewOf(content: String): String =
        content.lineSequence().firstOrNull()?.takeSafe(160) ?: ""

    // ── 上下文压缩 ──────────────────────────────────────────────────

    /** 检查是否需要压缩，需要则执行。 */
    private suspend fun maybeCompact(onEvent: (TurnEvent) -> Unit) {
        if (compactionManager.isCoolingDown()) return
        val chain = summarizerChain()
        val sp = chain.first().first
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

        val result = compactWithChain(prunedMsgs, chain)
        session.compactionSummary = result.summary
        trimCompactedHistory()
        persist()
        onEvent(MessageAdded(ChatMessage(
            role = ChatMessage.ROLE_ASSISTANT,
            content = "[系统] 上下文已压缩，释放约 ${result.tokensSaved} tokens"
        )))
    }

    /**
     * 供应商瞬态错误（值得退避重试）：优先用异常携带的结构化状态码（429/408/5xx），
     * 无码时退回保守文本匹配（仅显式关键字，不再含 "http 5" 宽匹配——
     * 防 400 错误体里碰巧含 "http 500" 字样被误判而重发全上下文）。
     */
    private fun isTransientHttpError(e: Exception): Boolean {
        (e as? com.haoai.agent.agent.provider.ProviderHttpException)?.httpCode?.let { code ->
            return code == 429 || code == 408 || code >= 500
        }
        val m = e.message.orEmpty().lowercase()
        return "http 429" in m || "timeout" in m ||
            "timed out" in m || "connection reset" in m || "eofexception" in m || "stream stall" in m
    }

    /**
     * 当前供应商模型是否支持图像输入——统一走 CapabilityResolver
     * （手动覆盖 > models.dev 检测 > 旧 vision > 名称启发式 > 乐观默认）。
     */
    private fun providerSupportsVision(): Boolean = provider.caps().hasImage

    /**
     * 把供应商原始报错翻译成普通用户能看懂、知道下一步该干嘛的话。
     * 实现在文件级 friendlyErrorText（气泡与顶部 SnackBar 共用），技术细节收敛为短摘要。
     */
    private fun friendlyError(raw: String): String = friendlyErrorText(raw)

    /** Overflow 恢复：检测 API 返回的 context_length_exceeded 错误，自动压缩后重试。 */
    private suspend fun handleOverflow(
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
        val chatMsgs = session.messages.map { it.toModel() }

        // 强制压缩（点6：链式降级）
        val result = compactWithChain(chatMsgs, chain)
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
        val chain = summarizerChain()
        val chatMsgs = session.messages.map { it.toModel() }
        val result = compactWithChain(chatMsgs, chain)
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

        /** E4a 工具定义开销兜底下限：正常路径按真实序列化求和（_toolsTokenCache），
         *  仅异常/未构建时回退此值。 */
        const val TOOLS_BASE_TOKENS = 3500
        const val STORED_CAP = 16_000
        const val REQ_CAP = 4_000
        const val SUB_MAX_TURNS = 10
        /** E6 并行安全白名单：纯读无全局状态副作用；新增成员必须逐个评审（a11y/相机/定位永不入列）。 */
        val PARALLEL_SAFE = setOf(
            "read", "grep", "glob", "web_fetch", "web_search", "memory",
            "list_apps", "app_status", "browser_read", "browser_find", "todo"
        )

        /** E6 并发上限：同时执行的并行工具体数量。 */
        const val PARALLEL_MAX_CONCURRENCY = 4
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

/** 引擎错误的人话翻译：气泡与顶部 SnackBar 共用同一份话术（UI 层捕获异常时也调它）。 */
fun friendlyErrorText(raw: String): String {
    val m = raw.lowercase()
    val detail = shortTechDetailShared(raw)
    return when {
        // 余额/配额类：余额不足、配额超限
        "insufficient balance" in m || "balance=" in m || "quota" in m && "exceed" in m ||
            "insufficient_user_quota" in m || "arrears" in m ->
            "⚠️ 这个 AI 账号的话费用完了，需要去服务商充值或换一个账号（设置 → 模型供应商）。"
        // 模型不支持图像（多模态截图发给纯文本模型）
        "do not support image" in m || "not support image" in m || "image input" in m && "not" in m ->
            "⚠️ 当前模型看不了图。任务里需要识别屏幕截图，请在设置里换一个支持图像的模型（比如带 vision 的型号）再试。"
        // 401/403 密钥问题
        "http 401" in m || "http 403" in m || "invalid api key" in m || "unauthorized" in m ->
            "⚠️ AI 账号验证失败：密钥可能填错或过期了，请到 设置 → 模型供应商 检查密钥。"
        // 429 限流（isTransientHttpError 已自动重试，到这里说明重试耗尽）
        "http 429" in m || "rate limit" in m ->
            "⏳ AI 服务太忙（限流），自动重试了几次仍失败。等一两分钟再发一次就行。"
        // 400 参数类：分"带图发给纯文本模型"与其他
        "http 400" in m ->
            "⚠️ 请求被 AI 服务拒绝（参数不兼容）。如果刚才在跑屏幕截图类任务，多半是当前模型不支持图像，换个支持图像的模型；否则可能是这个模型与 App 协议不完全兼容，换一个模型供应商试试。（技术详情：$detail）"
        // DNS 域名解析失败：OkHttp 文案是 "Unable to resolve host"，此前漏翻译直出原文
        "unable to resolve host" in m || "no address associated" in m || "unknownhost" in m || "unknown host" in m ->
            "⚠️ 连不上模型服务器（域名解析失败）。请检查手机网络是否可用——Wi-Fi 或流量至少要通一个；如果在用代理/VPN，开关一次再发。"
        // 超时/网络
        "timeout" in m || "timed out" in m || "connection" in m || "econnrefused" in m ->
            "⚠️ 连不上 AI 服务：网络不稳定或服务暂时不可用，稍后再试一次。"
        // 兜底：人话开头 + 收敛后的技术摘要（不再倾泻半截 JSON）
        else -> "出错了：$detail"
    }
}

/** 技术摘要提取：优先取错误 JSON 的 message 字段值，否则清洗原文；截短到 cap。 */
private fun shortTechDetailShared(raw: String, cap: Int = 100): String {
    val fromJson = Regex(""""message"\s*:\s*"([^"]{1,200})""").find(raw)?.groupValues?.get(1)
    val cleaned = (fromJson ?: raw)
        .replace(Regex("[{}\"\\r\\n]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
    return cleaned.take(cap)
}
