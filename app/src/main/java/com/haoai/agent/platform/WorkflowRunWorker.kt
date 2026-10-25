package com.haoai.agent.platform

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.haoai.agent.HaoApplication
import java.util.concurrent.TimeUnit

/**
 * 工作流后台执行 Worker（boot / 外部触发共用）。
 *
 * 触发入口原先都在广播协程里直接跑 WorkflowRunner（每个 prompt 步是完整
 * AgentEngine 回合，耗时分钟级）：boot 路径整条工作流跑在 goAsync 窗口内
 * （后台广播约 60s，超时进程被杀、工作流中途夭折）；外部触发路径 fire-and-forget
 * 连进程锚都没有（Tasker 拉起的进程随时被冻结/回收）。统一改投 WorkManager
 * （同 AgentWorker 模式），由它负责进程存活。
 */
class WorkflowRunWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as? HaoApplication)?.container
            ?: return Result.failure()
        val id = inputData.getString(KEY_WORKFLOW_ID) ?: return Result.failure()
        val trigger = inputData.getString(KEY_TRIGGER) ?: "boot"
        val def = com.haoai.agent.agent.workflow.WorkflowStore.list()
            .firstOrNull { it.id == id }
            ?: return Result.success() // 已被删除：不算失败
        return try {
            com.haoai.agent.agent.workflow.WorkflowRunner.run(container, def, trigger = trigger)
            Result.success()
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (e: Throwable) {
            android.util.Log.w(
                "HaoWorkflow",
                "工作流 ${def.name} 执行失败（$trigger）：${e.message ?: e.javaClass.simpleName}"
            )
            Result.failure()
        }
    }

    companion object {
        const val KEY_WORKFLOW_ID = "workflowId"
        const val KEY_TRIGGER = "trigger"

        /** 唯一名按工作流 id：同一工作流的并发触发只跑一次（KEEP 保正在跑的那次）。 */
        fun enqueue(context: Context, workflowId: String, trigger: String, delayMs: Long = 0) {
            runCatching {
                val builder = OneTimeWorkRequestBuilder<WorkflowRunWorker>()
                    .setInputData(workDataOf(KEY_WORKFLOW_ID to workflowId, KEY_TRIGGER to trigger))
                    .addTag("haoai-workflow-$workflowId")
                if (delayMs > 0) builder.setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                WorkManager.getInstance(context).enqueueUniqueWork(
                    "haoai-workflow-run-$workflowId", ExistingWorkPolicy.KEEP, builder.build()
                )
            }.onFailure {
                android.util.Log.w("HaoWorkflow", "工作流投递失败：${it.message}")
            }
        }
    }
}
