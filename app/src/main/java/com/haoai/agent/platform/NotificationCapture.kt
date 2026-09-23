package com.haoai.agent.platform

import android.service.notification.NotificationListenerService
import com.haoai.agent.HaoApplication
import kotlinx.coroutines.launch
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
        // Phase 6 工作流通知触发：匹配到 enabled 工作流的关键词时入队执行（防抖：每关键词 60s 一次）
        // 整段挪 IO：onNotificationPosted 是通知服务主线程，WorkflowStore.list 会全量读盘
        val app = application as? HaoApplication ?: return
        app.container.applicationScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { dispatchWorkflowTriggers(container = app.container, title = title, text = text) }
        }
    }

    private var lastTriggerAt = mutableMapOf<String, Long>()

    private fun dispatchWorkflowTriggers(
        container: com.haoai.agent.data.AppContainer,
        title: String,
        text: String
    ) {
        val now = System.currentTimeMillis()
        val content = title + "\n" + text
        com.haoai.agent.agent.workflow.WorkflowStore.list().forEach { def ->
            if (!def.enabled || def.pendingConfirm || def.trigger.type != "notification") return@forEach
            val kw = def.trigger.config.trim()
            if (kw.isEmpty() || !content.contains(kw, ignoreCase = true)) return@forEach
            val last = synchronized(lastTriggerAt) { lastTriggerAt[def.id] ?: 0L }
            if (now - last < 60_000L) return@forEach
            synchronized(lastTriggerAt) { lastTriggerAt[def.id] = now }
            container.applicationScope.launch {
                runCatching { com.haoai.agent.agent.workflow.WorkflowRunner.run(container, def, trigger = "notification") }
            }
        }
    }

    private fun appNameOf(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(
            packageManager.getApplicationInfo(pkg, 0)
        ).toString()
    }.getOrDefault(pkg)
}
