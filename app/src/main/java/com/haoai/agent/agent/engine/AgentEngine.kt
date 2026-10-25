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
import com.haoai.agent.agent.flags.HaoFlag
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
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
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
    /** v2：脑根（记忆/技能/待办的固定私有目录）。默认从 appFilesDir 派生，EngineFactory 显式传 container.brainDir。 */
    internal val brainDir: File = java.io.File(appFilesDir, "brain"),
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
    /** E5 单轮 token 熔断上限（prompt+completion 累计）；**0=停用**。2026-10-25 对齐 hermes（无默认花费熔断）设置项已移除；机制保留，单测显式开启。 */
    internal val turnTokenCap: Int = 0,
    /** E5b 圈数熔断：单轮工具调用累计上限；**0=停用**。防失控交给 loopGuard 的空转/循环检测（hermes 无总次数上限，同姿势）。 */
    internal val toolCallCap: Int = 0,
    /** E5b 软提醒：达单轮 token 上限 70% 时注入一次精简收尾提醒（不中断循环）。仅在 turnTokenCap>0 时有意义。 */
    internal val softBudgetWarn: Boolean = true,
    /** E5 连续工具失败熔断阈值（复用 E3 conFailCount）；**0=停用**。一刀切计数会误杀「换参数重试」的真实工作
     *  （hermes FAILURE_TOLERANT 语义：换着法子失败是正常工作）——失败场景交给 loopGuard 的精确重放/无进展检测。 */
    internal val toolFailCap: Int = 0,
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
    internal val searchProviderFn: () -> com.haoai.agent.agent.tools.SearchProviderConfig? = { null },
    /**
     * A2（2026-10-15）运行中插话的环顶销账钩子。
     * 插话消息入队那一刻就已落进会话历史（D17 批3 的设计），引擎本轮组装请求
     * 经 buildApiMessages 天然带上——模型"看得见"不需要引擎做什么；这里只做记账：
     * 把已被本轮携带的插话从 VM 队列摘除（否则回合正常结束 drainQueue 会把它
     * 当排队任务再执行一遍=双跑），并把气泡从"插话待生效"转正。
     * 返回摘除的条数仅用于调试日志。无人值守/子代理传默认空实现=无插话源。
     */
    internal val steeringDrain: (String) -> Unit = {},
    /**
     * P4 后台子代理完成上报（ZCode task-notification 同款契约）：spawn_agent(background=true)
     * 派出的子代理在回合结束后继续运行，DONE/ERROR 终态时以 (sessionId, handle) 回调调用方。
     * 引擎层不落任何协调逻辑（批合并/唤醒/防并发都在调用方注入的协调器里）；
     * null=不上报（测试与旧语义）。STOPPED/CANCELLED 不上报——那是显式终止，结果可当场回收。
     */
    internal val onBackgroundSubagentDone: ((sessionId: String, handle: SubagentHandle) -> Unit)? = null,
    /**
     * P4 后台句柄 launch 瞬间上报（右上浮标数据源）：runSubAgent 注册句柄后同步回调，
     * 只做入表登记必须即返。与 [onBackgroundSubagentDone] 一一配对（launch/终态）。
     */
    internal val onBackgroundSubagentLaunched: ((sessionId: String, handle: SubagentHandle) -> Unit)? = null
) {

    /** 解析生效工具组：profile 空→会话分组；否则按预设（与 ToolRegistry.ALL_GROUPS 对齐）。 */
    internal fun activeGroupsForProfile(sessionGroups: Set<String>?): Set<String>? = when (toolProfile) {
        "minimal" -> setOf(ToolRegistry.GROUP_CORE)
        "coding" -> setOf(ToolRegistry.GROUP_CORE, ToolRegistry.GROUP_EXTENDED)
        "full" -> null
        else -> sessionGroups
    }

    internal val todoStore = TodoStore(brainDir)  // v2：待办属脑，固定 brainDir/todos

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
                    runSubAgent(task, parentCtx, 1, 1, mode, id, isBackground = true)
                } catch (ce: CancellationException) {
                    // stop_agent 终止：句柄状态（STOPPED）与部分结果已在 runSubAgent 内
                    // 落账。防御性兜底：句柄仍停在 RUNNING 说明取消来自其他路径（未来新增
                    // 的 withTimeout/子协程取消传播）——不上报会让协调器永等这路完成通知
                    activeSubagents[id]?.let { h ->
                        if (h.state == "RUNNING") {
                            h.state = "ERROR"
                            h.finalResult = "子代理 ${h.id} 被取消（非正常终止路径）。\n\n${h.partialSummary()}"
                            notifyBackgroundDone(h, ok = false)
                        }
                    }
                } catch (e: Exception) {
                    activeSubagents[id]?.let { h ->
                        h.state = "ERROR"
                        h.finalResult = "子代理执行失败：${e.message ?: e.javaClass.simpleName}\n\n${h.partialSummary()}"
                        // P4：runSubAgent 内部 catch 不到的异常逃逸到这层时也要上报，
                        // 否则协调器永远等不到这路完成通知（DONE/ERROR 之外的静默丢失）
                        if (h.isBackground) notifyBackgroundDone(h, ok = false)
                    }
                }
            }
        }
    }

    /**
     * P4 后台子代理终态上报（DONE/ERROR 调用）：在引擎自持的 subagentScope 里 fire-and-forget，
     * 不阻塞子代理收尾路径；协调器侧自行决定批合并/让路/唤醒。回调异常不得反噬子代理协程。
     */
    internal fun notifyBackgroundDone(handle: SubagentHandle, ok: Boolean) {
        val cb = onBackgroundSubagentDone ?: return
        subagentScope.launch {
            runCatching { cb(session.id, handle) }
        }
    }

    /** P2-5 重放保护状态：turn 内最后一次非 READ 工具执行的签名与结果。
     *  LLM 流瞬态重试会重发同一工具序列，紧邻同名同参的写类调用直接返回上次结果。 */
    internal var lastExecutedSig: String? = null
    internal var lastExecutedResult: ToolResult? = null

    /** E4b tools_enable 生效标记：工具组变更后主循环尾重建工具清单（下一轮 LLM 请求生效）。 */
    @Volatile internal var _groupsDirty = false

    /**
     * 轮内系统提示估算缓存（markMemoryUse=false 的只读估算共享一次构建，见
     * buildSystemTextWithInjected 注释）。失效：runTurn 入口、主循环每圈顶
     * （上一圈工具结果已落库）、nudge 注入后。
     */
    @Volatile internal var sysTextEstimateCache: Pair<String, String>? = null

    /** E9 todo 进度联动：清单变更后的下一轮注入一次进度行。 */
    internal var todoDirty = false
    private var todoLastSnapshot: List<com.haoai.agent.agent.tools.TodoItem>? = null

    /** E3 工具连续失败计数（成功清零）。
     *  生命周期=引擎实例（调用方当前每回合重建）：即"回合内连续"语义。跨回合保留
     *  会让上一回合的失败累积进新回合的开局判定（可能开局即熔断），刻意不做会话级。 */
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
        // 失败容忍（hermes FAILURE_TOLERANT 同款）：bash 的测试红/编译错、搜索空结果、
        // 页面超时都是正常工作输出，换参数重试不应被强制收尾；同参同果重放仍照常 halt。
        failureTolerant = setOf("bash", "web_fetch", "web_search", "browser_navigate"),
        // 圈数熔断与主循环 E5b 同阈值双轨：守卫版在 finishCall 内 Halt（无 RUN_TURNCAPPED
        // 记账、文案不同），统一走主循环 E5b 那条带收尾指令+终态记账的路径，这里关闭
        loopCap = 0
    )

    /** 5.7 已追加过自改进提示的技能（会话级限一次）。
     *  引擎实例实际由调用方每回合重建（ChatViewModel buildEngine），挂实例上"限一次"
     *  会退化为"每回合一次"重复注入提示烧 token——注册表按 session.id 进程级持有。
     *  写校验提示（ValidateWriteHook）同语义同去处。 */
    internal val hintedSkills: MutableSet<String> = sessionHintState(session.id).hintedSkills
    internal val hintedWrites: MutableSet<String> = sessionHintState(session.id).hintedWrites

    /** E7b 横切 hooks：before（快照）+ after（E3 升级/技能提示/写文件校验），列表序执行。 */
    internal val hooks: List<ToolHook> = listOf(
        SnapshotHook(appFilesDir, session.id, backend),
        EscalationHook(conFailCount),
        SkillHintHook(hintedSkills),
        ValidateWriteHook(hintedWrites)
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
        imageData: List<String> = emptyList(),
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
        // 回合级收口状态一并归位：当前调用方都每回合 buildEngine 所以未爆发，但 runTurn 是
        // public API，同一实例跑第二个回合时残留的 runEndState 会让 finishTurn 恒走
        // rollbackTodos（natural=false）、残留的熔断旗会让 E5b 提前收尾。
        runEndState = null
        _loopFailedCap = false
        _loopStallHalt = false
        sysTextEstimateCache = null
        if (resuming) closeDanglingCalls(onEvent)
        // 本会话已用过的 tool_call id：新来的调用必须避开，否则跨轮撞号会让 UI 行状态与
        // pairSanitized 的配对判定互相顶名（详见 uniquifyCallIds 的注释）
        val usedCallIds = existingCallIds(session.messages).toMutableSet()
        if (appendUser) {
            appendAndNotify(
                ChatMessage(
                    role = ChatMessage.ROLE_USER,
                    content = when {
                        imageData.isNotEmpty() ->
                            imageData.indices.joinToString("\n") { "[图片${it + 1}]" } +
                                (if (userText.isNotBlank()) "\n$userText" else "")
                        audioPath != null -> "[音频]\n$userText".trim()
                        videoPath != null -> "[视频]\n$userText".trim()
                        else -> userText
                    },
                    images = imageData,
                    imageData = imageData.firstOrNull(),
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
            subagentControl,
            subResultSpillOn = HaoFlag.enabled(HaoFlag.TOOL_RESULT_SPILL, flagOverrides)
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
                if (MAX_TURNS > 0 && st.beginRound() > MAX_TURNS) {
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
                // A3：本轮供应商结束原因（length=被 max_tokens 截断，工具参数可能是半截）
                var stopReason: String? = null

                // A2（2026-10-15）运行中插话：用户在任务跑动中把排队消息标为「插话」后，
                // 该消息已在会话历史里，本轮 buildApiMessages 会带上（模型即刻生效）；
                // 这里环顶向 VM 销账（从队列摘除+旗标转正），防回合结束时被当排队任务二次执行。
                // v7 的「排队任务」语义不变：未标插话的排队消息依然由 VM 在回合正常结束后接管。
                steeringDrain(session.id)

                // E9 todo 变更检测：本轮执行过 todo 工具 → 下一轮注入进度行
                val todoNow = todoStore.load(session.id)
                if (todoLastSnapshot != null && todoNow != todoLastSnapshot) {
                    todoDirty = true
                }
                todoLastSnapshot = todoNow

                // 圈顶失效：上一圈工具结果/nudge 已落库，估算缓存作废重算
                sysTextEstimateCache = null

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
                                        is SseEvent.Completed -> {
                                            calls = uniquifyCallIds(ev.toolCalls, usedCallIds)
                                            stopReason = ev.stopReason
                                        }
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
                            // P4：join+超时替代纯 delay 轮询。旧写法流结束后仍要睡满一个 5s 周期才退出，
                            // 每轮请求固定多等 0~5s（快回合最伤：10 轮任务最多白攒 50 秒）；
                            // join 返回=流已收口立刻放行，超时才做一次挂死检查，语义与旧版一致。
                            val finished = withTimeoutOrNull(5_000L) { collectJob.join(); true } ?: false
                            if (finished) break
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

                // A3（2026-10-15）：被 max_tokens 截断的一轮，工具调用参数可能是半截 JSON
                // （截在合法边界内的还会"看着像能跑"）——pi 的处理是整批按失败回填、零执行。
                // 显式 length/max_tokens 才触发；null/未知一律放过（供应商乱报的代价更大）。
                if (stopReason == "length" || stopReason == "max_tokens") {
                    for (call in calls) {
                        val err = ToolResult(
                            "本轮回复被单次输出上限截断（$stopReason），${call.name} 的参数可能是半截 JSON，" +
                                "已按失败处理、未执行。请把该调用拆小：缩短参数文本/分批写入/减少本轮工具数，" +
                                "然后重新调用。",
                            true
                        )
                        onEvent(ToolChanged(ToolUpdate(call.id, ToolRunState.ERROR, briefOf(call), "输出被截断")))
                        finishCall(call, buildJsonObject {}, ctx, err, ToolRunState.ERROR, 0L, "length-truncated", onEvent)
                    }
                    // 本轮 assistant 已在上方 isFinal 判定处落库一次；这里再追加会得到
                    // 「assistant(tool_calls) → tool(结果) → assistant(同一批 tool_calls)」的
                    // 尾部无结果毒历史——OpenAI 兼容端对未应答 tool_calls 返回 400，且已持久化，
                    // 该会话之后每轮都 400。只补 tool 结果，不再落 assistant。
                    continue
                }

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
                        subagentControl,
                        subResultSpillOn = HaoFlag.enabled(HaoFlag.TOOL_RESULT_SPILL, flagOverrides)
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
            // 2026-10-16 用户实测修复：流式中异常中断（供应商 500 / 断网 / 看门狗）时，
            // 本轮已产出的正文原先只留在 transcript 与流式气泡里，收尾 endStreaming 一清
            // 就连带丢失；用户随后发的"继续执行"落在空档处，观感像插到了旧过程前面。
            // 仿用户停止分支（见上 CancellationException）补落，标注意外中断。
            if (transcript.hasContent) {
                appendAndNotify(
                    ChatMessage(
                        role = ChatMessage.ROLE_ASSISTANT,
                        content = transcript.textString() + "\n\n*意外中断：以上进度已保留*",
                        // 思考过程一并留下：中断不应把思考链丢掉（与停止分支同口径）
                        reasoning = transcript.reasoningOrNull(),
                        pt = st.promptTokens.toInt().takeIf { it > 0 } ?: 0,
                        ct = st.completionTokens.toInt().takeIf { it > 0 } ?: 0,
                        ms = System.currentTimeMillis() - turnStartMs,
                        model = provider.model,
                        reasoningMs = transcript.reasoningMsOrNull()
                    ),
                    onEvent
                )
            }
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
        // P3→P4 后台子代理收口：前台句柄仍在收口之列（同步派发不该有 RUNNING 残留，兜底取消）；
        // 后台句柄（isBackground）一律放行——回合结束它们继续跑，完成后经 onBackgroundSubagentDone
        // 交协调器注入结果并起唤醒回合（ZCode task-notification 同款）。旧实现无差别取消，
        // 是"后台模式名存实亡"的根因。只取消 Job、不动 subagentScope 本身——
        // 取消整个 scope 会让引擎实例的后续回合再也派不出后台子代理（launch 静默不执行）。
        activeSubagents.values.forEach { h ->
            if (h.state == "RUNNING" && !h.isBackground) {
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

        // 窗口化：旁路提问不落库、无工具循环，但原先全量历史直发——长会话改标题/插话
        // 的成本与主请求同量级。窗口 = 首条 user 消息（标题生成的主题锚点）+ 最近 20 条。
        // 本路径把 tool 消息折叠成 user、不传 toolCalls，无配对截断问题
        val all = session.messages
        val head = all.firstOrNull { it.role == ChatMessage.ROLE_USER }
        val tail = all.takeLast(20)
        val windowed = if (head != null && !tail.contains(head)) listOf(head) + tail else tail

        val apiMessages = mutableListOf<com.haoai.agent.agent.provider.ApiMessage>()
        if (sysText.isNotBlank()) {
            apiMessages.add(com.haoai.agent.agent.provider.ApiMessage(role = "system", content = sysText))
        }
        windowed.forEach { m ->
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

/**
 * 技术摘要提取：优先取错误 JSON 的 message 字段值，否则清洗原文；截短到 cap。
 *
 * 2026-10-25 真机实证修复（用户任务显示「出错了：\」）：旧正则 [^"] 遇到 message 值
 * 以转义引号开头的错误体（网关把模型名包在引号里 → "message":"\"…\""）会在 \ 处
 * 停下，捕获组只剩一个反斜杠——真实错误原因被整个吃掉。改为转义感知捕获 +
 * JSON 反转义；提取结果清洗后若退化成空/纯标点，逐级回落到清洗原文。
 */
private fun shortTechDetailShared(raw: String, cap: Int = 100): String {
    // (?:\\.|[^"\\])* ：吃掉 \" \\ \n 等转义对，或普通字符，直到真正的收尾引号
    val fromJson = Regex(""""message"\s*:\s*"((?:\\.|[^"\\]){0,300})"""").find(raw)
        ?.groupValues?.get(1)
        // JSON 反转义：先 \\ 后 \" 顺序不可反（否则 \\ 被误当转义引号的前半）
        ?.replace("\\\\", "\u0000")
        ?.replace("\\\"", "\"")
        ?.replace("\\n", "\n")
        ?.replace("\\t", " ")
        ?.replace("\u0000", "\\")
    val collapse = Regex("\\s+")
    val fromJsonCleaned = fromJson
        ?.replace(Regex("[{}\"\\r\\n]+"), " ")
        ?.let { collapse.replace(it, " ").trim() }
    val rawCleaned = collapse.replace(raw.replace(Regex("[{}\"\\r\\n]+"), " "), " ").trim()
    // 逐级回落：message 提取 → 清洗原文。两边都只剩标点（如裸 "\"）时宁可给原文，
    // 一个反斜杠对排查毫无价值，JSON 原文至少看得见错误形状
    val degenerate = Regex("^[\\p{Punct}\\s]*$")
    return when {
        !fromJsonCleaned.isNullOrBlank() && !degenerate.matches(fromJsonCleaned) -> fromJsonCleaned.take(cap)
        rawCleaned.isNotBlank() && !degenerate.matches(rawCleaned) -> rawCleaned.take(cap)
        else -> raw.take(cap)
    }
}


/**
 * 会话级"限一次"提示状态注册表（进程级）：引擎实例由调用方每回合重建，
 * hintedSkills（技能自改进提示）与 ValidateWriteHook 的写校验提示语义都是
 * "每会话最多一次"，挂实例上会退化成每回合重复注入（噪音+token）。按
 * session.id 持有；条目量=每会话几十字节，不随会话删除清理（同 id 重开
 * 重新提示一次无碍）。conFailCount 不在此列（连续失败计数刻意保持回合级，
 * 见其字段注释）。
 */
private val engineSessionHints = java.util.concurrent.ConcurrentHashMap<String, SessionHintState>()

internal class SessionHintState {
    val hintedSkills: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    val hintedWrites: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
}

internal fun sessionHintState(sessionId: String): SessionHintState =
    engineSessionHints.getOrPut(sessionId) { SessionHintState() }
