package com.haoai.agent.platform

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.SystemClock
import androidx.core.content.ContextCompat

/**
 * 梦境触发监视器（条件触发，非每日定时）：
 * 充电中 + 屏幕关闭持续 N 分钟 → 自动执行一次记忆整理。
 * - 亮屏或断电立即取消；触发前再复核条件，避免误整理。
 * - 用 AlarmManager(ELAPSED_REALTIME_WAKEUP) 计时：灭屏 suspend 期间照常走时
 *   （Handler.postDelayed 基于 uptimeMillis，灭屏即停走，不可用）。
 */
object DreamTriggerMonitor {

    private const val ACTION_FIRE = "com.haoai.agent.DREAM_FIRE"
    private var registered = false

    fun init(context: Context) {
        val app = context.applicationContext
        // 取代旧的每日定时任务：清理历史在册的周期作业
        runCatching {
            androidx.work.WorkManager.getInstance(app)
                .cancelUniqueWork("haoai_memory_tidy")
        }
        if (registered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(ACTION_FIRE)
        }
        ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        registered = true
        // 冷启动即处于充电+灭屏（如夜间被系统杀掉后重启），补排一次
        if (isScreenOff(app)) armIfCharging(app)
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val app = context.applicationContext
            when (intent.action) {
                ACTION_FIRE -> fire(app, manual = false)
                Intent.ACTION_SCREEN_OFF -> armIfCharging(app)
                Intent.ACTION_SCREEN_ON -> disarm(app)
                Intent.ACTION_POWER_DISCONNECTED -> disarm(app)
                Intent.ACTION_POWER_CONNECTED ->
                    if (isScreenOff(app)) armIfCharging(app)
            }
        }
    }

    private fun firePendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context, 3001,
            Intent(ACTION_FIRE).setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun isScreenOff(context: Context): Boolean {
        return try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            pm.isInteractive.not()
        } catch (e: Exception) {
            false
        }
    }

    private fun isCharging(context: Context): Boolean = try {
        val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        (sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
    } catch (e: Exception) {
        false
    }

    private fun idleMinutes(context: Context): Int =
        (context.applicationContext as com.haoai.agent.HaoApplication)
            .container.settingsFlow.value.dreamIdleMinutes.coerceIn(1, 240)

    private fun armIfCharging(context: Context) {
        disarm(context)
        if (!isCharging(context)) return
        val minutes = idleMinutes(context)
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val trigger = SystemClock.elapsedRealtime() + minutes * 60_000L
        val ok = runCatching {
            if (am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, firePendingIntent(context))
            } else {
                am.setWindow(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, 5 * 60_000L, firePendingIntent(context))
            }
        }.isSuccess
        android.util.Log.d("HaoDream", "charge+idle, dream in $minutes min (alarm=$ok)")
    }

    /** 触发整理。manual=true 跳过充电复核（手动入口复用）。 */
    fun fire(context: Context, manual: Boolean) {
        val app = context.applicationContext
        val st = (app as com.haoai.agent.HaoApplication).container.settingsFlow.value
        if (!st.memoryEnabled) return
        if (!manual && (!isCharging(app) || !isScreenOff(app))) {
            android.util.Log.d("HaoDream", "fire skipped: charging=${isCharging(app)} screenOff=${isScreenOff(app)}")
            return
        }
        android.util.Log.d("HaoDream", "fire! manual=$manual")
        MemoryTidyWorker.enqueueOnce(app, force = manual)
    }

    private fun disarm(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        runCatching { am.cancel(firePendingIntent(context)) }
    }
}
