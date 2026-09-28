package com.haoai.agent.platform

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.haoai.agent.MainActivity
import com.haoai.agent.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 前台服务：①保活（沿用）②任务卡通知（新增）。
 *
 * 通知不再是一成不变的「任务将在后台继续执行」，而是单条动态任务卡：标题=目标、正文=当前步骤/todo 进度、运行中挂
 * 「停止」action、有待审批时升级高优先级提示。1s 节拍刷新（
 * notify 是 binder IPC 且系统限流，无脑高频会被丢弃）。
 */
class KeepAliveService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "后台运行",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "保持 HaoAI 任务在后台继续运行"
                setShowBadge(false)
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_APPROVAL,
                "任务待确认",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Agent 等待你确认危险操作时提醒"
            }
        )
        // 跨端待批：这条通道跟着保活服务的命走（服务被省电策略杀掉就没有提醒，
        // 这件事在 README 里明确写成"未验证/不保证"，不假装任何情况下都收得到）
        PcWatchdog.init(applicationContext)
        PcWatchdog.start()
        scope.launch { observe() }
    }

    /** 1s 节拍：RunObserver 变化 → 重建通知。 */
    private suspend fun observe() {
        var lastRender: RunObserver.RunState? = null
        while (scope.isActive) {
            val st = RunObserver.state.value
            val changed = st != lastRender
            val throttled = System.currentTimeMillis() - RunObserver.lastNotifyAt < 1000
            if (changed && !throttled) {
                RunObserver.lastNotifyAt = System.currentTimeMillis()
                lastRender = st
                runCatching { notify(st) }
            }
            delay(500)
        }
    }

    private fun notify(st: RunObserver.RunState) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (!st.active) {
            // 任务结束：通知回落为静态保活文案（特殊审批渠道随之清空）
            nm.cancel(CHANNEL_APPROVAL, APPROVAL_ID)
            nm.notify(NOTIFICATION_ID, idleNotification())
            return
        }
        val pi = chatPendingIntent()
        val stopPi = PendingIntent.getBroadcast(
            this, 1,
            Intent(this, RunActionReceiver::class.java).setAction(RunActionReceiver.ACTION_STOP_RUN),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val sb = StringBuilder()
        st.steps.take(3).forEach { step ->
            sb.append(when (step.state) {
                "running" -> "▸ "
                "error" -> "✕ "
                else -> "✓ "
            }).append(step.brief.ifBlank { step.name }).append('\n')
        }
        val inProgress = st.todos.filter { it.second == "in_progress" }.size
        val done = st.todos.count { it.second == "completed" }
        val content = buildString {
            if (sb.isNotBlank()) append(sb.toString().trimEnd())
            if (st.todos.isNotEmpty()) {
                if (isNotEmpty()) append('\n')
                append("任务清单 $done/${st.todos.size} 完成")
                if (inProgress > 0) append(" · $inProgress 项进行中")
            }
            if (st.approvalTitle != null) {
                if (isNotEmpty()) append('\n')
                append("⏸ 等待确认：${st.approvalTitle}")
            }
            if (isEmpty()) append("正在思考…")
        }
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(st.goal.ifBlank { "HaoAI 正在运行" })
            .setContentText(content)
            .setContentIntent(pi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        if (content.length > 40 || st.steps.isNotEmpty()) builder.setStyle(
            NotificationCompat.BigTextStyle().bigText(content)
        )
        builder.addAction(
            0, "停止",
            stopPi
        )
        if (st.approvalTitle != null) {
            // 审批/提问门控：升级到高优渠道（响铃/横幅）单独发一条，点按直达聊天页
            // ask_user 提问（P2）：通知直接挂选项按钮，点了任务就继续；>3 项或自由输入走应用
            val askBuilder = NotificationCompat.Builder(this, CHANNEL_APPROVAL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(if (st.ask != null) "需要你决定" else "等待你的确认")
                .setContentText(st.approvalTitle)
                .setStyle(NotificationCompat.BigTextStyle().bigText(st.approvalDetail ?: st.approvalTitle))
                .setContentIntent(pi)
                .setAutoCancel(true)
            st.ask?.let { ask ->
                if (ask.allowFreeText || ask.options.size > 3) {
                    val note = buildString {
                        append(st.approvalDetail ?: st.approvalTitle ?: "")
                        append("\n\n（打开应用可自由输入或查看全部选项）")
                    }
                    askBuilder.setStyle(NotificationCompat.BigTextStyle().bigText(note))
                }
                ask.options.take(3).forEachIndexed { i, label ->
                    askBuilder.addAction(0, label, askAnswerPi(ask.askId, i))
                }
            }
            nm.notify(CHANNEL_APPROVAL, APPROVAL_ID, askBuilder.build())
        } else {
            nm.cancel(CHANNEL_APPROVAL, APPROVAL_ID)
        }
        nm.notify(NOTIFICATION_ID, builder.build())
    }

    private fun idleNotification(): Notification {
        val pi = chatPendingIntent()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("HaoAI 正在运行")
            .setContentText("任务将在后台继续执行")
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    private fun chatPendingIntent(): PendingIntent {
        val backIntent = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = android.net.Uri.parse("haoai://debug/chat")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        return PendingIntent.getActivity(
            this, 0, backIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    /** ask_user 快捷选项按钮：广播 → RunObserver.askAnswerSink → ChatViewModel 挂起点。 */
    private fun askAnswerPi(askId: String, index: Int): PendingIntent =
        PendingIntent.getBroadcast(
            this, 200 + index,
            Intent(this, RunActionReceiver::class.java)
                .setAction(RunActionReceiver.ACTION_ANSWER_ASK)
                .putExtra(RunActionReceiver.EXTRA_ASK_ID, askId)
                .putExtra(RunActionReceiver.EXTRA_OPTION_INDEX, index),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification: Notification = idleNotification()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            // Android 15+ 用 specialUse（dataSync 有 6 小时/日系统上限）；旧版本走 dataSync
            if (android.os.Build.VERSION.SDK_INT >= 34)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            else
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "haoai_keepalive"
        const val CHANNEL_APPROVAL = "haoai_run_approval"
        const val NOTIFICATION_ID = 4201
        private const val APPROVAL_ID = 4202

        fun start(context: android.content.Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, KeepAliveService::class.java)
            )
        }

        fun stop(context: android.content.Context) {
            context.stopService(Intent(context, KeepAliveService::class.java))
        }
    }
}
