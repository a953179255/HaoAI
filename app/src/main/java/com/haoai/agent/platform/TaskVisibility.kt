package com.haoai.agent.platform

import android.app.ActivityManager
import android.content.Context

/**
 * 4.3 运行时任务视图隐藏：Agent 运行期间把本应用任务从系统「最近任务」中移除，
 * 避免用户误滑关闭中断进行中的自动化。公共 API ActivityManager.getAppTasks() +
 * AppTask.setExcludeFromRecents（上游 同款机制，只作用于本应用自己的任务）。
 *
 * 兜底恢复：上次被系统强杀时任务可能留在隐藏态，应用下次打开时若无 Agent 运行则恢复可见。
 */
object TaskVisibility {

    @Volatile private var hidden = false

    /** 切换任务最近任务可见性；隐藏幂等，恢复总是同步系统状态（进程被杀后本地状态可能丢失）。返回当前是否隐藏。 */
    fun apply(context: Context, hide: Boolean): Boolean {
        if (hide && hidden) return true
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return hidden
        return runCatching {
            // getAppTasks 为已废弃但仍可用的公共 API（仅返回本 app 的任务列表）
            am.appTasks.forEach { it.setExcludeFromRecents(hide) }
            hidden = hide
            hidden
        }.getOrElse {
            android.util.Log.w("TaskVisibility", "setExcludeFromRecents 失败：${it.message}")
            hidden
        }
    }

    /** 应用打开时兜底恢复：Agent 未在运行 → 清理可能残留的任务隐藏态（含强杀遗留）。 */
    fun restoreIfIdle(context: Context, agentRunning: Boolean) {
        if (!agentRunning) apply(context, false)
    }
}
