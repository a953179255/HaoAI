package com.haoai.agent.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 通知「停止」按钮的路由：广播 → RunObserver → AgentRunRegistry（跨实例停止句柄）。 */
class RunActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_STOP_RUN -> RunObserver.requestStop()
        }
    }

    companion object {
        const val ACTION_STOP_RUN = "com.haoai.agent.action.STOP_RUN"
    }
}
