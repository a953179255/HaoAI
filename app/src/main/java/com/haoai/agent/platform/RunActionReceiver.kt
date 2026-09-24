package com.haoai.agent.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 通知动作路由：
 * - 「停止」→ RunObserver → AgentRunRegistry（跨实例停止句柄）
 * - ask_user 快捷选项（P2）→ RunObserver.askAnswerSink → 前台 ChatViewModel 的提问挂起点
 *   （sink 由持有 pendingAsk 的 ChatViewModel 注入；进程活着才有运行中的提问，广播必达）
 */
class RunActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_STOP_RUN -> RunObserver.requestStop()
            ACTION_ANSWER_ASK -> {
                val askId = intent.getStringExtra(EXTRA_ASK_ID) ?: return
                val index = intent.getIntExtra(EXTRA_OPTION_INDEX, -1)
                RunObserver.askAnswerSink?.invoke(askId, index)
            }
        }
    }

    companion object {
        const val ACTION_STOP_RUN = "com.haoai.agent.action.STOP_RUN"
        const val ACTION_ANSWER_ASK = "com.haoai.agent.action.ANSWER_ASK"
        const val EXTRA_ASK_ID = "ask_id"
        const val EXTRA_OPTION_INDEX = "option_index"
    }
}
