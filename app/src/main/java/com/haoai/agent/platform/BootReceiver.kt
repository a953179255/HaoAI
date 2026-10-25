package com.haoai.agent.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Phase 6 工作流 boot 触发：开机后把 enabled 且 trigger=boot 的工作流立即执行一次；
 * schedule 触发不依赖本 receiver（WorkManager 自持久化，应用启动时 Scheduler 同步）。
 *
 * 执行改投 WorkflowRunWorker（WorkManager）：工作流是分钟级的完整 Agent 回合，
 * 原先整条跑在 goAsync 广播窗口内（后台广播约 60s），超 ANR 时限进程被杀、
 * 工作流中途夭折且无恢复。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        runCatching {
            com.haoai.agent.agent.workflow.WorkflowStore.list()
                .filter { it.enabled && !it.pendingConfirm && it.trigger.type == "boot" }
                .forEach { WorkflowRunWorker.enqueue(context, it.id, "boot", delayMs = 3_000) }
        }
    }
}
