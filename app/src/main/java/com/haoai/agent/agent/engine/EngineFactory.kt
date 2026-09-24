package com.haoai.agent.agent.engine

import com.haoai.agent.agent.policy.ApprovalRequest
import com.haoai.agent.agent.policy.PolicyEngine
import com.haoai.agent.agent.provider.ProviderClient
import com.haoai.agent.agent.tools.ToolResult
import com.haoai.agent.data.AppContainer
import com.haoai.agent.data.ConfigFileBridge
import com.haoai.agent.data.ProviderConfig
import com.haoai.agent.data.StoredSession
import com.haoai.agent.data.UsageLedger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * AgentEngine 唯一构造入口。
 *
 * 为什么要有它：引擎构造器有 40 个参数，其中「能力闭包」（配置读写 / 模型路由 / 能力委派）
 * 只依赖 AppContainer + 设置，三处调用点（聊天页、定时任务、工作流）本来必然相同，
 * 却各自手抄过一遍——结果 AgentWorker 与 WorkflowRunner 漏传 6 个参数，
 * 使无人值守任务里 config_set 恒返回「配置修改不可用」、delegate_to_vision/transcribe_audio 根本不注册、
 * 压缩摘要没有备用模型链。新增能力时只需改这里一处。
 */
object EngineFactory {

    /**
     * 交互态参数：只有前台聊天才有审批弹窗、用量上报、自身状态渲染、Plan 门与插话队列。
     * 无人值守场景用 [unattended]（approve 由权限模式决定，其余全默认）。
     */
    data class Interaction(
        val policy: PolicyEngine,
        val approve: suspend (ApprovalRequest) -> Boolean,
        val onUsage: (suspend (Long, Long) -> Unit)? = null,
        val statusProvider: () -> String = { "" },
        val identity: String = "",
        val onToolChange: (() -> Unit)? = null,
        val planGate: () -> Boolean = { false }
    )

    /** 交互身份块：Agent 名字与性格，由设置在 VM 侧渲染成提示词片段。 */
    fun identityOf(agentName: String, soul: String): String = buildString {
        if (agentName.isNotBlank()) {
            append("## 你的身份\n")
            append("- 你的名字：$agentName（用户给你起的，请以此名字自称）\n")
            if (soul.isNotBlank()) append("- 你的性格：$soul\n")
        }
    }

    /**
     * @param turnTokenCap / toolCallCap 由调用方按「交互=设置值 / 无人值守=15万与80硬限 / 总开关关闭=0」算好后传入。
     * @param backgroundScope 传 null 表示不挂到应用级作用域（定时任务在 WorkManager 线程里跑）。
     */
    fun newEngine(
        container: AppContainer,
        session: StoredSession,
        provider: ProviderConfig,
        interaction: Interaction,
        workspaceLabel: String,
        turnTokenCap: Int,
        toolCallCap: Int,
        autoLearn: Boolean = true,
        backgroundScope: CoroutineScope? = null,
        softBudgetWarn: Boolean = true,
        toolFailCap: Int = 8
    ): AgentEngine {
        val st = container.settingsFlow.value
        return AgentEngine(
            httpClient = container.clientFor(provider),
            provider = provider,
            apiKey = container.resolveApiKey(provider),
            customPrompt = st.customPrompt,
            policy = interaction.policy,
            approve = interaction.approve,
            session = session,
            persist = { container.sessionStore.save(session) },
            backend = container.workspace.current,
            appFilesDir = container.appFilesDir,
            workspaceLabel = workspaceLabel,
            memoryBank = container.memoryBank,
            memoryEnabled = st.memoryEnabled,
            journal = container.journal,
            autoLearn = autoLearn,
            reasoningEffort = st.reasoningEffort,
            okHttpClient = container.okHttpClient,
            appContext = container.appContext,
            identity = interaction.identity,
            onUsage = interaction.onUsage,
            backgroundScope = backgroundScope,
            statusProvider = interaction.statusProvider,
            // C6/C1 统一配置入口：三处调用点共用同一套桥（聊天页曾独享，定时/工作流因此恒失败）
            configRender = { container.configBridge.render(container.settingsFlow.value) },
            configPreview = { patch ->
                withContext(Dispatchers.IO) {
                    runCatching { container.configBridge.previewPatch(patch) }.getOrElse {
                        ConfigFileBridge.Preview(err = it.message ?: "预检失败", diff = "")
                    }
                }
            },
            configMutator = { args ->
                withContext(Dispatchers.IO) {
                    runCatching {
                        val (merged, err) = container.configBridge.mergePatch(args)
                        if (err != null) {
                            ToolResult("配置被拒绝：$err（未生效）", true)
                        } else {
                            // applyFull 内部：快照 → 入库 → MCP/SSH 分区同步（含连接状态备注）
                            val p = container.configBridge.applyFull(merged)
                            if (!p.ok) ToolResult(
                                "配置被拒绝：${p.message}（未生效，修正后重新调用 config_set 即可）", true
                            ) else ToolResult("配置已应用：${p.message}")
                        }
                    }.getOrElse { ToolResult("配置修改失败：${it.message}", true) }
                }
            },
            onToolChange = interaction.onToolChange,
            vscreenEnabled = st.vscreenEnabled &&
                android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R,
            vscreenBitrateKbps = st.vscreenBitrateKbps,
            budgetHint = { UsageLedger.budgetHint(st.dailyTokenBudgetK) },
            turnTokenCap = turnTokenCap,
            toolCallCap = toolCallCap,
            softBudgetWarn = softBudgetWarn,
            toolFailCap = toolFailCap,
            // 5.3 模型路由 + P2 能力委派：同样三处共用，未配置时引擎自行不注册（不占工具 token）
            memoryTarget = {
                container.resolvePurposeTargets(st.memoryExtractProviderId, st.memoryExtractFallbackIds)
                    .map { it.provider to it.apiKey }
            },
            summarizeTarget = {
                container.resolvePurposeTargets(st.summarizeProviderId, st.summarizeFallbackIds)
                    .map { it.provider to it.apiKey }
            },
            auxClientFor = { p -> container.clientFor(p) },
            delegateTarget = { kind ->
                val id = when (kind) {
                    "vision" -> st.visionProviderId.trim()
                    else -> st.asrProviderId.trim()
                }
                if (id.isBlank()) emptyList()
                else container.resolvePurposeTargets(id, emptyList()).map { it.provider to it.apiKey }
            },
            planGate = interaction.planGate,
            toolProfile = st.toolProfile,
            sessionSearchFn = { q ->
                val res = com.haoai.agent.ui.sessions.searchSessions(container.sessionStore.list(), q)
                (res.titleHits.map { s -> s.title to "（标题命中）" } +
                    res.contentHits.map { h -> h.session.title to h.snippet })
            },
            // 搜索主后端：每次搜索现取，改完设置不必重启引擎；key 在这一步才解密
            searchProviderFn = { container.searchProviderConfig() }
        )
    }

    /** 无人值守（定时任务 / 工作流）交互态：审批只看权限模式，无 UI 回调。 */
    fun unattended(mode: com.haoai.agent.agent.policy.PermissionMode): Interaction = Interaction(
        policy = PolicyEngine(mode),
        approve = { mode == com.haoai.agent.agent.policy.PermissionMode.YOLO }
    )

    /** 与既有引擎默认值对齐的无人值守熔断硬限。 */
    const val UNATTENDED_TOKEN_CAP = 150_000
    const val UNATTENDED_TOOL_CALL_CAP = 80

    /** 供调用方按总开关折算熔断上限：关闭=完全不熔断（0）。 */
    fun capsFor(costBreakerEnabled: Boolean, unattended: Boolean, st: com.haoai.agent.data.AppSettings): Pair<Int, Int> =
        if (!costBreakerEnabled) 0 to 0
        else if (unattended) UNATTENDED_TOKEN_CAP to UNATTENDED_TOOL_CALL_CAP
        else st.turnTokenCap to st.toolCallCap
}
