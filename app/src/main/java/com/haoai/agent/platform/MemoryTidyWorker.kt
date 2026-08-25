package com.haoai.agent.platform

import android.content.Context
import androidx.work.WorkManager
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.haoai.agent.HaoApplication
import com.haoai.agent.agent.memory.MemoryConsolidation

/**
 * 记忆固化（上游 "dreaming" 的本地实现）：
 * - 规则层（始终执行）：重要日志晋升长期库 → 过期日志清理 → 去重整理，零成本
 * - 深度梦境层（设置开启时）：按用户选择的「记忆管理模型」（默认端侧小模型，
 *   也可选云端服务）对长期记忆做语义去重合并；不可用自动回退规则层。
 * - 固化结果写入工作区 DREAMS.md 与 dreaming/ 目录供人工审查。
 * - 触发：充电 + 灭屏闲置 N 分钟（DreamTriggerMonitor），或设置页手动触发。
 */
class MemoryTidyWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as HaoApplication).container
        val settings = container.settingsFlow.value
        runCatching {
            if (settings.deepDream && settings.memoryEnabled) {
                val wasRunning =
                    container.llama.state.value is com.haoai.agent.platform.llama.LlamaState.Running
                val target = runCatching { container.resolveDreamTarget() }.getOrNull()
                val report = if (target != null) {
                    runCatching {
                        MemoryConsolidation.runDeep(
                            container.memoryBank, container.journal,
                            container.client, target.provider, target.apiKey
                        )
                    }.getOrElse { MemoryConsolidation.run(container.memoryBank, container.journal) }
                } else {
                    MemoryConsolidation.run(container.memoryBank, container.journal)
                }
                // 端侧服务是本次为整理而拉起的，用完即停
                if (target?.isLocal == true && !wasRunning) {
                    runCatching { container.llama.stop() }
                }
                WorkspaceDocs.appendDreamReport(
                    container, report,
                    deep = target != null,
                    deepMergedNote = if (target == null) "（模型不可用，已回退规则整理）" else ""
                )
            } else {
                val report = MemoryConsolidation.run(container.memoryBank, container.journal)
                WorkspaceDocs.appendDreamReport(container, report, deep = false)
            }
            container.syncWorkspaceDocs()
        }
        return Result.success()
    }

    companion object {
        /** 手动触发或条件满足时的一次性执行。 */
        fun enqueueOnce(context: Context, force: Boolean) {
            val request = androidx.work.OneTimeWorkRequestBuilder<MemoryTidyWorker>()
                .setInputData(androidx.work.Data.Builder().putBoolean("force", force).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "haoai_memory_tidy_once",
                androidx.work.ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }
}
