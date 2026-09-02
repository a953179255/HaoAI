package com.haoai.agent.agent.workflow

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.haoai.agent.HaoApplication
import com.haoai.agent.agent.workflow.WorkflowStore.RunRecord
import com.haoai.agent.agent.workflow.WorkflowStore.Step
import com.haoai.agent.agent.workflow.WorkflowStore.Trigger
import com.haoai.agent.agent.workflow.WorkflowStore.WorkflowDef
import com.haoai.agent.agent.engine.AgentEngine
import com.haoai.agent.data.StoredSession
import com.haoai.agent.agent.policy.PermissionMode
import com.haoai.agent.agent.policy.PolicyEngine
import com.haoai.agent.agent.tools.ToolRegistry
import com.haoai.agent.agent.tools.ToolContext
import com.haoai.agent.data.ProviderConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.serialization.json.jsonObject
import java.io.File

/**
 * 工作流执行器：逐步跑 steps。prompt 步 = 一次性 AgentEngine 完整循环（与
 * AgentWorker 定时任务同构：无人值守 approve=YOLO 模式判定）；tool 步 = 直调
 * ToolRegistry 单工具（PolicyEngine 审批，headless 下同样按模式放行/拒绝）。
 */
object WorkflowRunner {

    data class Progress(val stepIndex: Int, val total: Int, val summary: String)

    /** 无人值守执行是否放行高危工具（与 AgentWorker 同约定）。 */
    fun approveFor(mode: PermissionMode): suspend (com.haoai.agent.agent.policy.ApprovalRequest) -> Boolean =
        { mode == PermissionMode.YOLO }

    suspend fun run(
        container: com.haoai.agent.data.AppContainer,
        def: WorkflowDef,
        onProgress: (suspend (Progress) -> Unit)? = null
    ): RunRecord {
        val started = System.currentTimeMillis()
        val mode = container.settingsFlow.value.permissionMode
        val logs = mutableListOf<String>()
        var ok = true

        val provider0 = container.activeProvider()
        val provider: ProviderConfig? = if (provider0 == null) null
        else if (provider0.baseUrl.startsWith("local")) {
            if (container.llama.ensureStarted(container.llama.findModel()?.absolutePath)) {
                provider0.copy(baseUrl = com.haoai.agent.platform.llama.LlamaServerController.LOCAL_BASE_URL)
            } else null
        } else provider0

        for ((i, step) in def.steps.withIndex()) {
            onProgress?.invoke(Progress(i + 1, def.steps.size, stepBrief(step)))
            val result = runCatching {
                when (step.type) {
                    "tool" -> runToolStep(container, def, step, mode)
                    else -> runPromptStep(container, def, step, provider, mode)
                }
            }
            result.fold(
                onSuccess = { out -> logs.add("✓ ${stepBrief(step)}：${out.take(120)}") },
                onFailure = { e ->
                    ok = false
                    logs.add("✗ ${stepBrief(step)}：${e.message ?: e.javaClass.simpleName}")
                }
            )
            if (!ok && step.stopOnError) break
        }

        val summary = (if (ok) "成功" else "部分失败") + "：" + logs.joinToString("；").take(400)
        return RunRecord(
            ts = started, ok = ok, summary = summary
        ).also { WorkflowStore.save(def.copy(lastRun = it)) }
    }

    private fun stepBrief(step: WorkflowStore.Step): String =
        if (step.type == "tool") "工具 ${step.tool}" else "指令「${step.text.take(20)}」"

    private suspend fun runPromptStep(
        container: com.haoai.agent.data.AppContainer,
        def: WorkflowDef,
        step: WorkflowStore.Step,
        provider: ProviderConfig?,
        mode: PermissionMode
    ): String {
        val p = provider ?: throw Exception("未配置模型服务")
        val session = StoredSession.create(container.workspace.workspaceUriForSession)
        session.title = "▶ ${def.name}"
        val st = container.settingsFlow.value
        val engine = AgentEngine(
            httpClient = container.clientFor(p),
            provider = p,
            apiKey = container.cipher.decrypt(p.apiKeyCipher),
            customPrompt = st.customPrompt,
            policy = PolicyEngine(mode),
            approve = approveFor(mode),
            session = session,
            persist = { container.sessionStore.save(session) },
            backend = container.workspace.current,
            appFilesDir = container.appFilesDir,
            workspaceLabel = "工作空间",
            memoryBank = container.memoryBank,
            memoryEnabled = st.memoryEnabled,
            journal = container.journal,
            autoLearn = false, // 工作流辅助运行不自动沉淀记忆（防噪声）
            reasoningEffort = st.reasoningEffort,
            okHttpClient = container.okHttpClient,
            appContext = container.appContext,
            backgroundScope = container.applicationScope,
            vscreenEnabled = st.vscreenEnabled && android.os.Build.VERSION.SDK_INT >= 30,
            vscreenBitrateKbps = st.vscreenBitrateKbps,
            budgetHint = { com.haoai.agent.data.UsageLedger.budgetHint(st.dailyTokenBudgetK) }
        )
        var out = ""
        engine.runTurn(
            userText = step.text,
            onDelta = { frag -> if (out.length < 2000) out += frag },
            onEvent = { }
        )
        if (out.isBlank()) throw Exception("无输出")
        return out
    }

    private suspend fun runToolStep(
        container: com.haoai.agent.data.AppContainer,
        def: WorkflowDef,
        step: WorkflowStore.Step,
        mode: PermissionMode
    ): String {
        val session = StoredSession.create(container.workspace.workspaceUriForSession)
        session.title = "▶ ${def.name}"
        val shellDir = container.workspace.current?.shellWorkdir() ?: File(container.appFilesDir, "shell-home").apply { mkdirs() }
        val st = container.settingsFlow.value
        val ctx = ToolContext(
            container.workspace.current, shellDir,
            com.haoai.agent.agent.tools.TodoStore(container.appFilesDir), container.appFilesDir,
            sessionId = session.id,
            container.memoryBank, container.journal, 0, container.okHttpClient, container.appContext,
            onToolChange = null,
            vscreenEnabled = st.vscreenEnabled && android.os.Build.VERSION.SDK_INT >= 30,
            vscreenBitrateKbps = st.vscreenBitrateKbps
        )
        val tools = ToolRegistry.build(ctx, null) +
            com.haoai.agent.agent.tools.HandoffTool { _, _ -> } // 工作流 tool 步禁用交接（无会话上下文）
        val tool = tools.firstOrNull { it.name == step.tool }
            ?: throw Exception("未知工具：${step.tool}")
        val args = runCatching {
            com.haoai.agent.data.HaoJson.json.parseToJsonElement(step.args.ifBlank { "{}" }).jsonObject
        }.getOrElse { throw Exception("参数 JSON 非法：${it.message}") }
        val approve = approveFor(mode)
        val req = com.haoai.agent.agent.policy.ApprovalRequest.Generic(step.tool, "工作流「${def.name}」步骤调用")
        if (com.haoai.agent.agent.policy.PolicyEngine(mode).requiresApproval(step.tool) && !approve(req)) {
            throw Exception("审批拒绝（无人值守 ${mode} 模式）")
        }
        val result = withContext(Dispatchers.IO) {
            withTimeoutOrNull(180_000.milliseconds) { tool.run(args, ctx) } ?: throw Exception("工具执行超时")
        }
        if (result.isError) throw Exception(result.content.take(160))
        return result.content
    }

    /** schedule 触发：入队一次性 Worker（unique work，REPLACE 保持单实例）。 */
    fun enqueueSchedule(context: Context, defId: String, spec: String) {
        val delayMs = nextDelayMs(spec) ?: return
        val request = OneTimeWorkRequestBuilder<WorkflowWorker>()
            .setInitialDelay(java.time.Duration.ofMillis(delayMs))
            .setInputData(workDataOf(WorkflowWorker.KEY_DEF_ID to defId))
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork("haoai-wf-$defId", ExistingWorkPolicy.REPLACE, request)
    }

    fun cancelSchedule(context: Context, defId: String) {
        WorkManager.getInstance(context).cancelUniqueWork("haoai-wf-$defId")
    }

    /** Scheduler 同款 spec 文法的下次触发延迟。 */
    fun nextDelayMs(spec: String): Long? {
        val now = System.currentTimeMillis()
        return when {
            spec == "hourly" -> 3600_000L
            spec.startsWith("every:") -> {
                val m = Regex("every:(\\d+)([mhd])").find(spec) ?: return null
                val n = m.groupValues[1].toLong()
                when (m.groupValues[2]) {
                    "m" -> n * 60_000L
                    "h" -> n * 3_600_000L
                    else -> n * 86_400_000L
                }
            }
            spec.startsWith("daily:") -> {
                val m = Regex("daily:(\\d{2}):(\\d{2})").find(spec) ?: return null
                val cal = java.util.Calendar.getInstance().apply {
                    set(java.util.Calendar.HOUR_OF_DAY, m.groupValues[1].toInt())
                    set(java.util.Calendar.MINUTE, m.groupValues[2].toInt())
                    set(java.util.Calendar.SECOND, 0)
                    set(java.util.Calendar.MILLISECOND, 0)
                }
                var next = cal.timeInMillis
                if (next <= now) next += 86_400_000L
                next - now
            }
            else -> null
        }
    }

}

/** schedule 触发的执行 Worker。 */
class WorkflowWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val defId = inputData.getString(KEY_DEF_ID) ?: return Result.failure()
        val container = (applicationContext as HaoApplication).container
        val def = WorkflowStore.get(defId)
        if (def == null || !def.enabled) {
            // 工作流已被删/停：链条终止
            return Result.success()
        }
        withContext(Dispatchers.IO) { WorkflowRunner.run(container, def) }
        // 保持调度链：按 spec 继续入队下一次
        WorkflowRunner.enqueueSchedule(applicationContext, def.id, def.trigger.config)
        return Result.success()
    }

    companion object { const val KEY_DEF_ID = "defId" }
}
