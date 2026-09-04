package com.haoai.agent.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.launch
import com.haoai.agent.HaoApplication

/**
 * Phase 6 工作流 boot 触发：开机后把 enabled 且 trigger=boot 的工作流立即执行一次；
 * schedule 触发不依赖本 receiver（WorkManager 自持久化，应用启动时 Scheduler 同步）。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val container = (context.applicationContext as HaoApplication).container
        val goAsync = goAsync()
        container.applicationScope.launch {
            runCatching {
                com.haoai.agent.agent.workflow.WorkflowStore.list()
                    .filter { it.enabled && !it.pendingConfirm && it.trigger.type == "boot" }
                    .forEach { def ->
                        runCatching { com.haoai.agent.agent.workflow.WorkflowRunner.run(container, def, trigger = "boot") }
                    }
            }
            goAsync.finish()
        }
    }
}
