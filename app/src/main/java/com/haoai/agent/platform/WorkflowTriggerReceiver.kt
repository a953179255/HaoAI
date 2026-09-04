package com.haoai.agent.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.haoai.agent.HaoApplication
import com.haoai.agent.agent.workflow.WorkflowRunner
import com.haoai.agent.agent.workflow.WorkflowStore
import kotlinx.coroutines.launch

/**
 * 工作流外部触发入口：Tasker / MacroDroid / adb 广播拉起。
 *
 *   am broadcast -a com.haoai.agent.WORKFLOW_RUN \
 *     --es id <工作流id|留空用name> --es name <工作流名> --es token <令牌>
 *
 * 令牌是每个工作流的 16 位随机串（WorkflowStore.externalToken 懒生成，管理页可见），
 * 不匹配直接忽略——exported 接收器防其他应用乱拉流程的唯一闸门。
 * 只响应 enabled 且未处于待确认的流程；3 秒防抖防 Tasker 重试风暴。
 */
class WorkflowTriggerReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_RUN) return
        val token = intent.getStringExtra(EXTRA_TOKEN)?.trim().orEmpty()
        val id = intent.getStringExtra(EXTRA_ID)?.trim().orEmpty()
        val name = intent.getStringExtra(EXTRA_NAME)?.trim().orEmpty()
        if (token.isEmpty() || (id.isEmpty() && name.isEmpty())) return

        val all = WorkflowStore.list()
        val def = all.firstOrNull { it.id == id } ?: all.firstOrNull { it.name == name }
        if (def == null || !def.enabled || def.pendingConfirm) return
        if (WorkflowStore.externalToken(def) != token) return

        val now = System.currentTimeMillis()
        if (now - lastTriggerAt < 3_000L) return
        lastTriggerAt = now

        val container = (context.applicationContext as? HaoApplication)?.container ?: return
        container.applicationScope.launch {
            runCatching { WorkflowRunner.run(container, def, trigger = "external") }
        }
    }

    private var lastTriggerAt = 0L

    companion object {
        const val ACTION_RUN = "com.haoai.agent.WORKFLOW_RUN"
        const val EXTRA_ID = "id"
        const val EXTRA_NAME = "name"
        const val EXTRA_TOKEN = "token"
    }
}
