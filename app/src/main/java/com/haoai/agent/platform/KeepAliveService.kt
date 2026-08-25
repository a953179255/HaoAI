package com.haoai.agent.platform

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.haoai.agent.R

class KeepAliveService : Service() {

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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("HaoAI 正在运行")
            .setContentText("任务将在后台继续执行")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
        return START_STICKY
    }

    companion object {
        const val CHANNEL_ID = "haoai_keepalive"
        const val NOTIFICATION_ID = 4201

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
