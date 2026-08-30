package com.haoai.agent.platform

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * 通知读取服务（2.4 notifications_read）：系统绑定的 NotificationListenerService，
 * 捕获到达的通知到内存环形缓冲（上限 50 条，只保留文本元信息）。
 * 需要用户在系统设置授予「通知使用权」；同时是 Phase 6 通知触发工作流的前置。
 */
class NotificationCapture : NotificationListenerService() {

    data class Captured(
        val packageName: String,
        val appName: String,
        val title: String,
        val text: String,
        val postedAt: Long
    )

    companion object {
        /** 环形缓冲：最新在前，容量 50。 */
        val recent = ArrayDeque<Captured>()

        var connected: Boolean = false
            private set

        private const val CAP = 50

        fun push(c: Captured) {
            synchronized(recent) {
                recent.addFirst(c)
                while (recent.size > CAP) recent.removeLast()
            }
        }
    }

    override fun onListenerConnected() {
        connected = true
        // 连接时回放当前活跃通知
        activeNotifications?.forEach { capture(it) }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        capture(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // 只做捕获不做移除跟踪：读取场景关心"最近有什么通知"
    }

    private fun capture(sbn: StatusBarNotification?) {
        if (sbn == null || sbn.isOngoing) return
        val extras = sbn.notification?.extras ?: return
        val title = extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return
        push(
            Captured(
                packageName = sbn.packageName,
                appName = appNameOf(sbn.packageName),
                title = title.take(120),
                text = text.take(300),
                postedAt = sbn.postTime
            )
        )
    }

    private fun appNameOf(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(
            packageManager.getApplicationInfo(pkg, 0)
        ).toString()
    }.getOrDefault(pkg)
}
