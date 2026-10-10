package com.haoai.agent.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 电脑联动通知动作接收器（2026-10-10 从 PcWatchdog.kt 尾部拆出：
 * 独立类按类名可检索、可单测；manifest 注册点 .platform.PcActionReceiver 不变）。
 */
class PcActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_PC_DECIDE) return
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        val decision = intent.getStringExtra(EXTRA_DECISION) ?: return
        // 同一个按钮口，两种语义：审批送的是那三个决定值，提问送的是回答文字
        val isAnswer = intent.getBooleanExtra(EXTRA_IS_ANSWER, false)
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { if (isAnswer) PcWatchdog.answer(id, decision) else PcWatchdog.decide(id, decision) }
                .onFailure { android.util.Log.w("HaoPcLink", "点决定没送到：${it.message}") }
        }
    }

    companion object {
        const val ACTION_PC_DECIDE = "com.haoai.agent.action.PC_DECIDE"
        const val EXTRA_ID = "pc_ask_id"
        const val EXTRA_DECISION = "pc_decision"
        const val EXTRA_IS_ANSWER = "pc_is_answer"
    }
}
