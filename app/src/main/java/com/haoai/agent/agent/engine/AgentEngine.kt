package com.haoai.agent.agent.engine


/**
 * HaoAI 的回合引擎：把"用户一句话"跑成一个带工具循环的完整回合。
 *
 * 本文件只留三样：构造契约、回合循环 runTurn、以及必须回指循环的状态收口
 * （finishTurn / todos / 中断修复 / runBtw）。其余按能力域拆在同目录：
 *   EngineContext.kt    系统提示、开销估算、历史选窗、组装请求
 *   EngineToolRun.kt    一次工具调用：审批 → hook → 执行 → 落库（含 E6 并行段）
 *   EngineSubagents.kt  子代理运行时（research / work）
 *   EngineCompaction.kt 压缩触发、链式摘要、溢出恢复、水位落账
 *   EngineMemory.kt     记忆提取与运行账本
 *   EngineDelegation.kt 视觉 / 音频委派
 *   EngineHistory.kt    历史配对净化（纯函数）   EngineHelpers.kt 无状态的文本与判定小工具
 *   EngineLimits.kt     常量与阈值               EnginePrompts.kt 辅助系统提示词
 *
 * 拆法沿用三家参考实现的共同判据：**能不能回指回合循环**。只读输入、产出数据、
 * 不起效应的才搬出去；搬出去的函数一律是 AgentEngine 的扩展函数，因此引擎的部分成员
 * 从 private 降为 internal（模块内可见）—— 这是"一个类跨多文件"的必然代价，
 * 函数体逐字未改，行为零差异（83 条单测与设备实测同覆盖）。
 */
import com.haoai.agent.agent.memory.DailyJournal
import com.haoai.agent.agent.memory.MemoryBank
import com.haoai.agent.agent.model.ChatMessage
import com.haoai.agent.agent.model.ToolCallData
import com.haoai.agent.agent.policy.ApprovalRequest
import com.haoai.agent.agent.policy.PolicyEngine
import com.haoai.agent.agent.provider.ApiMessage
import com.haoai.agent.agent.provider.SseEvent
import com.haoai.agent.agent.tools.SubAgentRunner
import com.haoai.agent.agent.tools.TodoStore
import com.haoai.agent.agent.tools.Tool
import com.haoai.agent.agent.tools.ToolContext
import com.haoai.agent.agent.tools.ToolRegistry
import com.haoai.agent.agent.tools.ToolResult
import com.haoai.agent.agent.tools.toApi
import com.haoai.agent.data.ProviderConfig
import com.haoai.agent.data.StoredSession
import com.haoai.agent.data.toModel
import com.haoai.agent.data.toStored
import com.haoai.agent.platform.FileBackend
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import java.io.File

class AgentEngine(
    internal val httpClient: com.haoai.agent.agent.provider.ProviderClient,
    internal val provider: ProviderConfig,
    internal val apiKey: String,
    internal val customPrompt: String,
    internal val policy: PolicyEngine,
    internal val approve: suspend (ApprovalRequest) -> Boolean,
    internal val session: StoredSession,
    internal val persist: () -> Unit,
    internal val backend: FileBackend?,
    internal val appFilesDir: File,
    internal val workspaceLabel: String,
    internal val memoryBank: MemoryBank? = null,
    internal val memoryEnabled: Boolean = true,
    internal val journal: DailyJournal? = null,
    internal val autoLearn: Boolean = true,
    internal val reasoningEffort: String = "",
    internal val okHttpClient: okhttp3.OkHttpClient? = null,
    internal val appContext: android.content.Context? = null,
    internal val identity: String = "",
    internal val onUsage: (suspend (Long, Long) -> Unit)? = null,
    internal val depth: Int = 0,
    internal val backgroundScope: CoroutineScope? = null,
    /** 动态渲染自身运行状态（app_status 工具按需读取，不注入系统提示）。 */
    internal val statusProvider: () -> String = { "" },
    /** C6/C1 config_set 落地（JSON patch → 桥严格校验入库），由 VM 层注入；每次调用引擎强制审批。 */
    internal val configMutator: (suspend (JsonObject) -> com.haoai.agent.agent.tools.ToolResult)? = null,
    /** C6 config_get 数据源：渲染当前配置镜像（apiKey 掩码）。 */
    internal val configRender: () -> String = { "" },
    /** ask_user 提问门：模型发起"暂停等用户拍板"，由前台聊天 VM 注入弹卡挂起；
     *  null（无人值守/子代理）时 AskUserTool 按"取默认假设继续"指引收场。 */
    internal val askUser: (suspend (com.haoai.agent.agent.tools.AskUserRequest) -> com.haoai.agent.agent.tools.AskUserAnswer)? = null,
    /** ask_user_batch 整批问卷门：同 [askUser] 生死条件，VM 层注入本地循环出题卡。 */
    internal val askUserBatch: (suspend (com.haoai.agent.agent.tools.AskUserBatchRequest) -> List<String>)? = null,
    /** C1 config_set 预检：合并补丁→同源解析→语义 diff；err 非空=补丁非法（免审批直接拒绝）。 */
    internal val configPreview: suspend (JsonObject) -> com.haoai.agent.data.ConfigFileBridge.Preview = {
        com.haoai.agent.data.ConfigFileBridge.Preview(err = "配置预检不可用", diff = "")
    },
    /** 工具状态变更回调（todo 修改后刷新 UI）。 */
    internal val onToolChange: (() -> Unit)? = null,
    /** 4.3 虚拟屏后台自动化：设置页总开关 ∧ API 30+（由调用方合并判定）。 */
    internal val vscreenEnabled: Boolean = false,
    /** 4.3 虚拟屏画面码率档位（kbps），映射截图清晰度（见 VirtualScreenController.presetFor）。 */
    internal val vscreenBitrateKbps: Int = 3000,
    /** 5.1 每日预算提示（≥70% 注入精简提醒、超预算注入警告），由调用方按设置计算。 */
    internal val budgetHint: () -> String = { "" },
    /** S6 特性开关：用户在「实验特性」里的显式覆盖（key → on）。未覆盖的 key 走 HaoFlag 默认值。 */
    internal val flagOverrides: Map<String, Boolean> = emptyMap(),
    /** 5.3 模型路由：记忆提取专用链（主+备用）；空=回落主模型。 */
    internal val memoryTarget: (suspend () -> List<Pair<ProviderConfig, String>>)? = null,
    /** 5.3 模型路由：上下文压缩摘要专用链。 */
    internal val summarizeTarget: (suspend () -> List<Pair<ProviderConfig, String>>)? = null,
    /** 5.3 专用目标的协议客户端解析（缺省仍用主 httpClient）。 */
    internal val auxClientFor: ((ProviderConfig) -> com.haoai.agent.agent.provider.ProviderClient)? = null,
    /**
     * P2 能力委派：按 kind（"vision"/"asr"）返回委派目标链（provider+key），由 VM 解析设置。
     * 空链/null = 未配置，委派工具不注册（省工具 token）。
     */
    internal val delegateTarget: (suspend (String) -> List<Pair<ProviderConfig, String>>)? = null,
    /** 5.6 Plan 模式门：true 时 WRITE/EXEC 工具不执行，返回引导文本继续循环。 */
    internal val planGate: () -> Boolean = { false },
    /** E5 单轮 token 熔断上限（prompt+completion 累计）；0=不限。无人值守默认 15 万，交互聊天走设置（默认 25 万）。 */
    internal val turnTokenCap: Int = 150_000,
    /** E5b 圈数熔断：单轮工具调用累计上限；0=不限。防失控循环的主力（行业默认 20~500，取 80）。 */
    internal val toolCallCap: Int = 80,
    /** E5b 软提醒：达单轮 token 上限 70% 时注入一次精简收尾提醒（不中断循环）。 */
    internal val softBudgetWarn: Boolean = true,
    /** E5 连续工具失败熔断阈值（复用 E3 conFailCount）；0=仅 token 熔断。 */
    internal val toolFailCap: Int = 8,
    /** B4 会话搜索数据源：UI 与工具共用 SessionStore 投影；null=不注册 session_search。 */
    internal val sessionSearchFn: ((String) -> List<Pair<String, String>>)? = null,
    /**
     * B6 工具 profile 预设（设置 toolProfile）：""=会话 activeGroups；minimal=仅 core；
     * coding=core+extended；full=全开（null）。会话已有显式分组时 profile 仅作覆盖开关。
     */
    internal val toolProfile: String = "",
    /**
     * 「设置 → 搜索服务」的主后端（含已解密 key）。每次搜索时现取，改完设置不必重启引擎。
     * null / backend=builtin 时 web_search 只走内置免 key 链。
     */
    internal val searchProviderFn: () -> com.haoai.agent.agent.tools.SearchProviderConfig? = { null }
) {

    /** 解析生效工具组：profile 空→会话分组；否则按预设（与 ToolRegistry.ALL_GROUPS 对齐）。 */
    internal fun activeGroupsForProfile(sessionGroups: Set<String>?): Set<String>? = when (toolProfile) {
        "minimal" -> setOf(ToolRegistry.GROUP_CORE)
        "coding" -> setOf(ToolRegistry.GROUP_CORE, ToolRegistry.GROUP_EXTENDED)
        "full" -> null
        else -> sessionGroups
    }

    internal val todoStore = TodoStore(appFilesDir)

    /** E1 轮次结束状态：由 runTurn 生命周期填写（idle/interrupted/turncapped/failed）；null=进行中。 */
    @Volatile var runEndState: String? = null

    /** E5 连续工具失败熔断信号（主循环尾检查后复位）。 */
    internal var _loopFailedCap = false

    /** B1 空转守卫 hard stop：置位后主循环尾强制 forceFinish 收尾（与 _loopFailedCap 同路径）。 */
    internal var _loopStallHalt = false

    /** B2 回合污点：抓到外部网络正文后置位，autoExtract 一律按 untrusted 溯源入库。 */
    @Volatile internal var turnTainted = false

    /** P2 运行中子代理注册表（key=handle id，生命周期=引擎实例即一个回合）。 */
    internal val activeSubagents = java.util.concurrent.ConcurrentHashMap<String, SubagentHandle>()
    internal val subagentSeq = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * P3 后台子代理作用域：独立协程树，不挂在工具调用的 withContext/withTimeout 上，
     * 因此不会被 180s 工具超时连坐取消；回合结束时由 finishTurn 统一收口（工具描述已承诺"回合结束一并终止"）。
     */
    private val subagentScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
    )

    /** P2 干预实现：stop=协作标志+取消协程；steer=消息入队下一轮消费。 */
    internal val subagentControl = object : SubagentControl {
        override fun find(callId: String?, index: Int): SubagentHandle? =
            activeSubagents.values.firstOrNull { it.callId != null && it.callId == callId && it.index == index }

        override fun byId(id: String): SubagentHandle? = activeSubagents[id]

        override fun stop(id: String): Boolean {
            val h = activeSubagents[id] ?: return false
            if (h.state != "RUNNING") return false
            h.state = "STOPPING"
            h.job.cancel()
            return true
        }

        override fun steer(id: String, message: String): Boolean {
            val h = activeSubagents[id] ?: return false
            if (h.state != "RUNNING") return false
            h.steering.add(message)
            return true
        }

        override fun newId(): String = "sa_${subagentSeq.incrementAndGet()}"

        override fun launchBackground(
            task: String,
            parentCtx: com.haoai.agent.agent.tools.ToolContext,
            mode: String,
            id: String
        ) {
            subagentScope.launch {
                try {
                    // 结果由 runSubAgent 自己写进句柄 finalResult（:1205），此处不再重复赋值：
                    // 写成 `activeSubagents[id]?.finalResult = runSubAgent(...)` 时 Kotlin 先算左侧，
                    // 那一刻 id 还没注册 → 整条赋值是空操作，后台结果永远收不到。
                    runSubAgent(task, parentCtx, 1, 1, mode, id)
                } catch (ce: CancellationException) {
                    // 终止/回合收口：句柄状态与部分结果已在 runSubAgent 内落账
                } catch (e: Exception) {
                    activeSubagents[id]?.let { h ->
                        h.state = "ERROR"
                        h.finalResult = "子代理执行失败：${e.message ?: e.javaClass.simpleName}\n\n${h.partialSummary()}"
                    }
                }
            }
        }
    }

    /** P2-5 重放保护状态：turn 内最后一次非 READ 工具执行的签名与结果。
     *  LLM 流瞬态重试会重发同一工具序列，紧邻同名同参的写类调用直接返回上次结果。 */
    internal var lastExecutedSig: String? = null
    internal var lastExecutedResult: ToolResult? = null

    /** E4b tools_enable 生效标记：工具组变更后主循环尾重建工具清单（下一轮 LLM 请求生效）。 */
    @Volatile internal var _groupsDirty = false

    /** E9 todo 进度联动：清单变更后的下一轮注入一次进度行。 */
    internal var todoDirty = false
    private var todoLastSnapshot: List<com.haoai.agent.agent.tools.TodoItem>? = null

    /** E3 会话级工具连续失败计数（成功清零）。 */
    internal val conFailCount = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /**
     * B1 工具空转守卫：按回合复位；无人值守时 hard stop 更积极。
     * 观测在 finishCall，决策注入引导或置 forceFinish（见 ToolLoopGuard）。
     */
    internal val loopGuard = ToolLoopGuard(
        identicalWarnAfter = 3,
        identicalBlockAfter = 5,
        sameToolFailWarnAfter = 3,
        sameToolFailHaltAfter = 8,
        maxCyclePeriod = 4,
        hardStopEnabled = false,
        loopCap = if (toolCallCap in 1..200) toolCallCap else 0
    )

    /** 5.7 已追加过自改进提示的技能（引擎生命周期=会话，天然满足每会话限一次）。 */
    internal val hintedSkills = mutableSetOf<String>()

    /** E7b 横切 hooks：before（快照）+ after（E3 升级/技能提示/写文件校验），列表序执行。 */
    internal val hooks: List<ToolHook> = listOf(
        SnapshotHook(appFilesDir, session.id, backend),
        EscalationHook(conFailCount),
        SkillHintHook(hintedSkills),
        ValidateWriteHook()
    )

    /** 5.6 本回合拦截过 WRITE/EXEC（供 UI 判定模型产出的是计划）。 */
    var planIntercepted: Boolean = false
        internal set

    internal val compactionManager = com.haoai.agent.agent.engine.compaction.CompactionManager(
        httpClient,
        maxHistory = MAX_HISTORY,
        toolContentCap = REQ_CAP
    ).apply {
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

    @Volatile internal var compactProvider: ProviderConfig? = null

    /**
     * 续跑入口的修复：把「已发起、没有结果」的工具调用补成一条明确的未确认结果。
     *
     * 位置必须在落恢复指令**之前**：tool 消息要紧跟它所属的那条 assistant，中间插一条
     * user 会让 OpenAI 兼容端以 400 拒掉整次请求。
     *
     * 为什么非补不可：
     * 1. 不补的话 pairSanitized 会把"调用没有结果"的那条 assistant 整条丢出请求
     *    ——中断前那一轮做了什么，对模型完全不可见；
     * 2. 模型只记得自己发起过调用，不记得结果没落地，于是要么当它已完成继续往下走，
     *    要么原样重跑一遍（写类工具就是重复副作用）。
     * 三家参考实现同一条纪律：**绝不自动重放非幂等工具**（OpenClaw resume-policy 只有
     * replaySafe 才重放；pi 的 ToolCall 带 replay:"never"|"safe"；Hermes 在破坏性工具
     * 执行前先把 tool-call 回合落库）。这里按现成的 RiskLevel 分道：只读工具鼓励重试，
     * 写类工具要求先核实。
     */
    private fun closeDanglingCalls(onEvent: (TurnEvent) -> Unit) {
        com.haoai.agent.data.StoredSession.danglingCalls(session.messages).forEach { call ->
            val readOnly = policy.riskOf(call.name) == com.haoai.agent.agent.policy.RiskLevel.READ
            appendAndNotify(
                ChatMessage(
                    role = ChatMessage.ROLE_TOOL,
                    content = if (readOnly) {
                        "[系统恢复] 上次进程中断，${call.name} 的这次调用没有返回结果。" +
                            "它是只读工具，重试没有副作用，请重新调用一次拿到结果。"
                    } else {
                        "[系统恢复] 上次进程中断，${call.name} 的这次调用没有返回结果，" +
                            "它的副作用可能已经发生。禁止用相同参数盲目重跑：先用只读方式核实当前状态" +
                            "（文件是否已存在/内容是否已改、命令是否已生效），确认未完成再重做。"
                    },
                    callId = call.id,
                    name = call.name,
                    error = true
                ),
                onEvent
            )
        }
    }

    suspend fun runTurn(
        userText: String,
        onDelta: (String) -> Unit,
        onEvent: (TurnEvent) -> Unit,
        imageData: String? = null,
        audioPath: String? = null,
        videoPath: String? = null,
        onReasoning: (String) -> Unit = {},
        /** true=中断后续跑：轮数接着上次算，避免每续一次就把 60 轮上限重新给满。 */
        resuming: Boolean = false,
        /**
         * false=这条用户指令**已经在会话历史里**了，引擎只负责接着它干活，不再落一遍。
         * 排队消息在入队那一刻就落进历史让用户看到"已发送"，提升为任务时再落一次
         * 就会出现同一条指令两条气泡（且模型看到的是重复指令，会再答一遍）。
         */
        appendUser: Boolean = true
    ) {
        // 整轮统计起点：用户发出 → 最终回复落库（含工具循环全部 LLM 调用与工具执行）
        val turnStartMs = System.currentTimeMillis()
        // 本轮的循环状态集中在 TurnState 里（原先散在 7 个可变局部变量 + StoredSession + 引擎字段）
        val st = TurnState(resumedTurnsUsed = if (resuming) session.runTurnsUsed else 0)
        if (!resuming) session.runTurnsUsed = 0
        // P2-5 重放保护按 turn 归零（跨 turn 的合法重复调用不受影响）
        lastExecutedSig = null
        lastExecutedResult = null
        loopGuard.reset()
        turnTainted = false
        if (resuming) closeDanglingCalls(onEvent)
        // 本会话已用过的 tool_call id：新来的调用必须避开，否则跨轮撞号会让 UI 行状态与
        // pairSanitized 的配对判定互相顶名（详见 uniquifyCallIds 的注释）
        val usedCallIds = existingCallIds(session.messages).toMutableSet()
        if (appendUser) {
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
        }

        val shellDir = backend?.shellWorkdir()
            ?: File(appFilesDir, "shell-home").apply { mkdirs() }
        val sessionSearch: ((String) -> List<Pair<String, String>>)? =
            if (depth == 0 && sessionSearchFn != null) ({ q -> sessionSearchFn!!.invoke(q) }) else null
        val ctx = ToolContext(
            backend, shellDir, todoStore, appFilesDir,
            sessionId = session.id,
            memoryBank, journal, depth, okHttpClient, appContext,
            statusProvider = statusProvider,
            configMutator = configMutator,
            configRender = configRender,
            askUser = askUser,
            askUserBatch = askUserBatch,
            onToolChange = onToolChange,
            vscreenEnabled = vscreenEnabled,
            vscreenBitrateKbps = vscreenBitrateKbps,
            sessionSearch = sessionSearch,
            searchProvider = searchProviderFn()
        )
        val subAgentRunner: SubAgentRunner? =
            if (depth == 0) SubAgentRunner { task, parentCtx, index, total, mode, id ->
                runSubAgent(task, parentCtx, index, total, mode, id)
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
        // P2 委派工具：仅当设置里配了委派模型才注册（未配置时不占工具清单 token）
        val delegateTools = buildList<com.haoai.agent.agent.tools.Tool> {
            if (delegateTarget != null) {
                add(com.haoai.agent.agent.tools.DelegateVisionTool { p, q -> delegateVisionRequest(p, q) })
                add(com.haoai.agent.agent.tools.TranscribeAudioTool { p -> delegateAsrRequest(p) })
            }
        }
        var tools = ToolRegistry.build(
            ctx, subAgentRunner,
            activeGroupsForProfile(session.activeGroups?.toSet()),
            subagentControl
        ) + handoffTool + toolsEnableTool + delegateTools
        var apiTools = gateTools(tools.map { it.toApi() })

        // E4a 工具定义 token 估算动态化：真实序列化各工具 JSON 求和（兜底下限 3500；门控清空则记 0）
        _toolsTokenCache = if (apiTools.isEmpty()) 0 else estimateToolsTokens(apiTools)

        // 压缩检查：在主循环前判断是否需要压缩
        maybeCompact(onEvent)

        val transcript = TurnTranscript(onDelta, onReasoning) { onEvent(StreamReset) }
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                // E1：已完成请求轮数持久化（turncapped 续跑参考）
                session.runTurnsUsed = st.turns
                // B3 轮次预算软提醒：达上限 80% 一次性注入（对齐 E5b 70% 成本提醒机制）
                if (!st.budgetWarned && MAX_TURNS >= 10 && st.turns >= MAX_TURNS * 8 / 10) {
                    st.budgetWarned = true
                    st.nudges +=
                        "[轮次提醒] 本任务已进行 ${st.turns} 轮（上限 $MAX_TURNS）。请在剩余轮次内收敛：" +
                            "完成关键步骤并准备给出最终回答，未完成项在任务清单中如实标注。"
                }
                if (st.beginRound() > MAX_TURNS) {
                    runEndState = com.haoai.agent.data.StoredSession.RUN_TURNCAPPED
                    finishTurn(natural = false)
                    appendAndNotify(
                        ChatMessage(
                            role = ChatMessage.ROLE_ASSISTANT,
                            content = st.capNotice(MAX_TURNS, resuming)
                        ),
                        onEvent
                    )
                    break
                }

                transcript.reset()
                var calls: List<ToolCallData> = emptyList()

                // v7：循环内插话已升级为「排队任务」语义（ChatViewModel 全权管理队列）——
                // 引擎不再在间隙消费队列，排队消息在回合正常结束后由 VM 作为新任务执行。

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
                            httpClient.chatStream(provider, apiKey, buildApiMessagesWithSummary(st.nudges), apiTools, effectiveEffort())
                                .collect { ev ->
                                    lastStreamEventAt = System.currentTimeMillis()
                                    when (ev) {
                                        is SseEvent.Delta -> transcript.delta(ev.text)
                                        is SseEvent.Reasoning -> transcript.reasoningDelta(ev.text)
                                        is SseEvent.Completed -> calls = uniquifyCallIds(ev.toolCalls, usedCallIds)
                                        is SseEvent.Usage -> {
                                            st.promptTokens += ev.promptTokens
                                            st.completionTokens += ev.completionTokens
                                            // 真值锚点：供应商报的输入 tokens 就是"这次请求实际发出的
                                            // 上下文规模"（含系统提示+工具定义+当时那整段历史）。
                                            // 流式期间没有新消息入库，此刻的 size 即那次请求的规模。
                                            // 每轮一次，与压缩判定同频，不多花一次系统提示构建。
                                            if (ev.promptTokens > 0) {
                                                val (sys, tools) = estimateOverheadTokens()
                                                session.usageAnchor = com.haoai.agent.data.UsageAnchor(
                                                    promptTokens = ev.promptTokens,
                                                    messageCount = session.messages.size,
                                                    overheadTokens = sys + tools
                                                )
                                            }
                                            onUsage?.invoke(ev.promptTokens.toLong(), ev.completionTokens.toLong())
                                        }
                                    }
                                }
                        }
                        // E9b 流式看门狗：90 秒无任何事件判定流挂死（供应商断流/SSE 假活），
                        // 中断后按瞬态错误走退避重试，而不是让整轮永远停在"正在思考"
                        while (collectJob.isActive) {
                            delay(5_000)
                            // 180s：原 90s 会误杀 glm 的长思考（实测思考 97.8s 无 delta 合法存在），
                            // 且看门狗重试会让 turn 内已执行的工具重放（e2e P2-5）。
                            // 网络真死时 120s readTimeout 先兜底，这里只防 SSE 假活。
                            if (System.currentTimeMillis() - lastStreamEventAt > 180_000L) {
                                throw java.io.IOException("stream stall: 流式响应超过 180 秒无数据（看门狗中断）")
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
                        // 清掉首次失败已累积的半截流式输出（正文和思考都要丢：
                        // 只清正文会让重试答案挂着上一轮的思考链落库），并让 UI 撤掉残句
                        transcript.discard()
                        handleOverflow(emsg, onEvent) { collectStream() }
                    } else if (isTransientHttpError(e)) {
                        // 429/超时/网关抖动：供应商限流在多轮工具任务里很常见（每步一请求），
                        // 退避重试而不是整轮失败；等待期可被取消，清残句避免拼接错乱
                        // v6：简报区分真实限流（429）与其他瞬态网络错误，不再一律报「限流」
                        val isRealRateLimit = (e as? com.haoai.agent.agent.provider.ProviderHttpException)?.httpCode == 429
                        val brief = if (isRealRateLimit) "供应商限流" else "网络波动"
                        val backoffsSec = SUB_BACKOFFS_SEC
                        var last: Exception? = e
                        for (sec in backoffsSec) {
                            onEvent(ToolChanged(ToolUpdate("rate-limit", ToolRunState.RUNNING, brief, if (isRealRateLimit) "HTTP 429，${sec}s 后自动重试" else "网络错误，${sec}s 后自动重试")))
                            // 残句在判定要重发的那一刻就先撤掉（含 UI 气泡）：它已经不可能
                            // 成为答案，却会在 5~25s 的退避里一直挂在屏幕上冒充正文。
                            transcript.discard()
                            delay(sec * 1000L)
                            currentCoroutineContext().ensureActive()
                            try {
                                collectStream()
                                onEvent(ToolChanged(ToolUpdate("rate-limit", ToolRunState.DONE, brief, "已恢复")))
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
                // st.forceFinish 已置位时跳过：否则累计值恒 ≥ 上限，收尾轮会在 [A] 再次触发熔断形成死循环
                if (!st.forceFinish && turnTokenCap > 0 && st.promptTokens + st.completionTokens >= turnTokenCap) {
                    runEndState = com.haoai.agent.data.StoredSession.RUN_TURNCAPPED
                    transcript.buildAssistant()?.let { appendAndNotify(it, onEvent) }
                    appendAndNotify(
                        ChatMessage(
                            role = ChatMessage.ROLE_USER,
                            content = "[成本熔断] 本轮已累计计费约 ${st.promptTokens + st.completionTokens} tokens，达到上限（${turnTokenCap}）。" +
                                "注意这是**逐轮累加的计费量**（每次工具往返都要把整段上下文重新发一遍），不是上下文窗口占用。" +
                                "请立即总结当前进度与剩余步骤，然后结束本轮，不要尝试调用工具。"
                        ),
                        onEvent
                    )
                    onEvent(ToolChanged(ToolUpdate("cap-break", ToolRunState.DONE, "成本熔断", "token 上限")))
                    apiTools = emptyList()
                    st.forceFinish = true
                    continue
                }

                // calls 为空（或熔断收尾轮）= 本轮无工具调用、循环即将 break：这是最终回复，
                // 把整轮累计的 token/耗时/模型名挂上（中间轮的 assistant 片段不带，避免重复展示）
                val isFinal = calls.isEmpty() || st.forceFinish
                transcript.buildAssistant(
                    toolCalls = maskSecretArgs(calls),
                    stats = if (isFinal) AssistantStats(
                        promptTokens = st.promptTokens.toInt(),
                        completionTokens = st.completionTokens.toInt(),
                        durationMs = System.currentTimeMillis() - turnStartMs,
                        model = provider.model
                    ) else null
                )?.let { appendAndNotify(it, onEvent) }
                currentCoroutineContext().ensureActive()

                if (calls.isEmpty() || st.forceFinish) {
                    // todo 兜底收尾：面板/通知卡只在 todo 工具被调用时刷新，而多数模型建完清单后
                    // 不再回头更新——B 终态收口统一处理：自然结束（已产出最终回答）残留 pending/
                    // in_progress 代收 completed；熔断收尾轮结束则回滚 pending（任务没做完）。
                    // 用户停止走取消异常、失败走异常分支——各自在 catch 里收口。
                    // 仅 depth==0 主循环触发（子代理共享父会话 todoStore，不得收父清单）。
                    finishTurn(natural = runEndState == null)
                    break
                }

                st.toolCalls += calls.size

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
                // 检索纪律靠系统提示（研究收敛契约）+ webCache 去重兜底，不注入次数提醒
                // （openclaw 同款：对话循环里不打扰，重复目标由缓存挡掉）
                // E4b tools_enable 生效点：组变更后重建工具清单，下一轮请求即带新组
                if (_groupsDirty) {
                    _groupsDirty = false
                    // 与首轮 :319 同构：必须带上 subagentControl（否则 stop/steer/collect_agent 消失）
                    // 与 delegateTools（否则委派视觉/转写工具消失）——曾漏传导致 tools_enable 后能力静默降级
                    tools = ToolRegistry.build(
                        ctx, subAgentRunner,
                        activeGroupsForProfile(session.activeGroups?.toSet()),
                        subagentControl
                    ) + handoffTool + toolsEnableTool + delegateTools
                    apiTools = gateTools(tools.map { it.toApi() })
                    _toolsTokenCache = if (apiTools.isEmpty()) 0 else estimateToolsTokens(apiTools)
                }
                // E5 连续工具失败熔断：达到阈值直接 break 输出失败总结
                if (_loopFailedCap || _loopStallHalt) {
                    val stall = _loopStallHalt
                    _loopFailedCap = false
                    _loopStallHalt = false
                    finishTurn(natural = false)
                    appendAndNotify(
                        ChatMessage(
                            role = ChatMessage.ROLE_ASSISTANT,
                            content = if (stall) {
                                "工具空转守卫已终止本轮（检测到无进展的重复调用）。请基于已有信息总结，或换一种方法后重新发起任务。"
                            } else {
                                "工具连续失败达到阈值，本轮已终止。请拆解任务或检查失败工具的用法。"
                            },
                        ),
                        onEvent
                    )
                    onEvent(
                        ToolChanged(
                            ToolUpdate(
                                if (stall) "stall-cap" else "fail-cap",
                                ToolRunState.DONE,
                                if (stall) "空转熔断" else "失败熔断",
                                if (stall) "重复无进展" else "连续工具失败"
                            )
                        )
                    )
                    break
                }

                // E5b 圈数熔断：单轮工具调用累计达上限 → 注入收尾指令，下一轮无工具纯文本总结
                if (!st.forceFinish && toolCallCap > 0 && st.toolCalls >= toolCallCap) {
                    runEndState = com.haoai.agent.data.StoredSession.RUN_TURNCAPPED
                    appendAndNotify(
                        ChatMessage(
                            role = ChatMessage.ROLE_USER,
                            content = "[圈数熔断] 本轮已执行 $st.toolCalls 次工具调用，达到上限（$toolCallCap）。请立即总结当前进度与剩余步骤，然后结束本轮，不要尝试调用工具。"
                        ),
                        onEvent
                    )
                    onEvent(ToolChanged(ToolUpdate("call-cap", ToolRunState.DONE, "圈数熔断", "工具调用次数")))
                    apiTools = emptyList()
                    st.forceFinish = true
                }

                // E5b 软提醒（70%）：一次性、只提醒不熔断；已熔断或关闭软提醒时跳过。
                // 两个约束都是实测来的：
                // - **只在还有得省时提**（本轮仍在调工具、任务清单仍有未完成项）。用户截图那次
                //   是任务 3/3 已完成、文章已交付，提醒还插在中间——模型只能回一句"已交付完毕"，
                //   提醒本身成了噪音。
                // - **不落库**（见 TurnState.nudges）：落库的提醒会被后续每一轮反复重传，
                //   一个"成本提醒"自己制造长期成本，语义上正好相反。
                if (!st.softWarned && !st.forceFinish && softBudgetWarn && turnTokenCap > 0 &&
                    st.totalTokens >= turnTokenCap * 0.7 &&
                    budgetNudgeWorthIt(
                        calledToolsThisRound = calls.isNotEmpty(),
                        todoHasOpenItems = todoStore.load(session.id)
                            .any { it.status != "completed" && it.status != "cancelled" }
                    )
                ) {
                    st.softWarned = true
                    st.nudges += "[成本提醒] 本轮已累计计费约 ${st.totalTokens} tokens，" +
                        "达到上限（${turnTokenCap}）的 70%。这是逐轮累加的计费量（每次工具往返都要重发整段上下文），" +
                        "不是上下文占用。请精简后续步骤：别再重复检索同一批结果，尽快收尾任务。"
                    onEvent(ToolChanged(ToolUpdate("budget-warn", ToolRunState.DONE, "成本提醒", "70%")))
                }
            }
            onEvent(Finished(null))
            ledgerLlm("chat", st.promptTokens, st.completionTokens, System.currentTimeMillis() - turnStartMs, ok = true)
            maybeExtractMemory()
        } catch (ce: CancellationException) {
            ledgerLlm("chat", st.promptTokens, st.completionTokens, System.currentTimeMillis() - turnStartMs, ok = false)
            // B 终态收口：用户停止 → in_progress 回滚 pending（非挂起操作，取消态下安全）
            finishTurn(natural = false)
            if (transcript.hasContent) {
                appendAndNotify(
                    ChatMessage(
                        role = ChatMessage.ROLE_ASSISTANT,
                        content = transcript.textString() + "\n\n*[已停止]*",
                        // 思考过程一并留下：此前停止会把气泡里的思考链丢掉
                        reasoning = transcript.reasoningOrNull(),
                        // 用户中途停止：已消耗的部分也记上（best effort，供统计行展示）
                        // 规范字段 pt/ct 是非空 Int（PC 侧锁）：旧的 takeIf null 语义落回 0
                        // （st.promptTokens 是 TurnState 的字段 —— 曾被按行改名连坐成 st.pt，手工修回）
                        pt = st.promptTokens.toInt().takeIf { it > 0 } ?: 0,
                        ct = st.completionTokens.toInt().takeIf { it > 0 } ?: 0,
                        ms = System.currentTimeMillis() - turnStartMs,
                        model = provider.model,
                        reasoningMs = transcript.reasoningMsOrNull()
                    ),
                    onEvent
                )
            }
            onEvent(Finished("已停止"))
            throw ce
        } catch (e: Exception) {
            runEndState = com.haoai.agent.data.StoredSession.RUN_FAILED
            ledgerLlm("chat", st.promptTokens, st.completionTokens, System.currentTimeMillis() - turnStartMs, ok = false)
            // B 终态收口：失败 → in_progress 回滚 pending
            finishTurn(natural = false)
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

    /**
     * todo 兜底收尾：把残留的 in_progress/pending 项标为 completed——模型已给出最终回答，
     * 此时残留即漏标。保存后触发 onToolChange，任务面板/通知卡立即刷新。
     */
    private fun finalizeTodos() {
        runCatching {
            val items = todoStore.load(session.id)
            if (items.isNotEmpty() && items.any { it.status == "in_progress" || it.status == "pending" }) {
                todoStore.save(
                    session.id,
                    items.map {
                        if (it.status == "in_progress" || it.status == "pending") it.copy(status = "completed") else it
                    }
                )
                onToolChange?.invoke()
            }
        }
    }

    /**
     * todo 回滚：非自然结束（用户停止/失败）时，in_progress 回 pending——
     * 任务已死但清单还显示"进行中"是失真；诚实状态便于续跑时接续。
     */
    private fun rollbackTodos() {
        runCatching {
            val items = todoStore.load(session.id)
            if (items.isNotEmpty() && items.any { it.status == "in_progress" }) {
                todoStore.save(
                    session.id,
                    items.map { if (it.status == "in_progress") it.copy(status = "pending") else it }
                )
                onToolChange?.invoke()
            }
        }
    }

    /**
     * B 终态收口：回合结束路径共用的收尾出口。
     * 自然完成（natural=true）→ 残留清单项代标 completed；停止/失败/熔断收尾轮结束
     * （natural=false）→ in_progress 回滚 pending。子代理不得动父清单（depth>0 跳过）。
     */
    private fun finishTurn(natural: Boolean) {
        if (depth != 0) return
        // P3 后台子代理收口：只取消仍在跑的每个子代理 Job，不动 subagentScope 本身——
        // 取消整个 scope 会让引擎实例的后续回合再也派不出后台子代理（launch 静默不执行）。
        activeSubagents.values.forEach { h ->
            if (h.state == "RUNNING") {
                h.state = "CANCELLED"
                h.job.cancel()
            }
        }
        runCatching {
            if (natural) finalizeTodos() else rollbackTodos()
        }
    }

    /** 真实固定开销估算（系统提示 + 工具定义），供压缩判断与 UI 使用量指示器；不发起网络。 */
    internal var _toolsTokenCache: Int? = null

    internal fun appendAndNotify(msg: ChatMessage, onEvent: (TurnEvent) -> Unit) {
        session.messages.add(msg.toStored())
        persist()
        onEvent(MessageAdded(session.messages.last().toModel()))
    }

    /**
     * 把供应商原始报错翻译成普通用户能看懂、知道下一步该干嘛的话。
     * 实现在文件级 friendlyErrorText（气泡与顶部 SnackBar 共用），技术细节收敛为短摘要。
     */
    private fun friendlyError(raw: String): String = friendlyErrorText(raw)

    suspend fun runBtw(
        question: String,
        onDelta: (String) -> Unit,
        onReasoning: (String) -> Unit,
        /** 5.4 账本用途标记；5.3 标题路由传 "title"。 */
        purpose: String = "btw"
    ) {
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

