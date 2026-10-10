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
        // Flyme 广播防火墙会拦掉系统 SCREEN_OFF/ON 广播（用户实测：
        // "skipped by policy at enqueue: Firewall blocked ... SCREEN_OFF"），
        // 补一条 DisplayManager binder 回调通道（不经过广播，不受防火墙管辖）。
        // ⚠️ 这个 DisplayListener **有意不反注册**（2026-10-10 写死约束）：本监视器
        // 随 AppContainer 进程级常驻、注册幂等（registered 标志），listener 只挂一份，
        // 不构成累积泄漏；将来若改成可停用的实例，必须连这个 listener 一起存引用并反注册。
        runCatching {
            val dm = app.getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            dm.registerDisplayListener(
                object : android.hardware.display.DisplayManager.DisplayListener {
                    override fun onDisplayAdded(displayId: Int) {}
                    override fun onDisplayRemoved(displayId: Int) {}
                    override fun onDisplayChanged(displayId: Int) {
                        if (isScreenOff(app)) armIfCharging(app) else disarm(app)
                    }
                },
                handler
            )
        }
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

    /**
     * 自动梦境时间窗：仅 00:00–07:00 执行（用户要求的夜间前置条件）。
     * 手动触发不受此限制。
     */
    private fun inDreamWindow(): Boolean {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return hour in 0..6
    }

    private fun armIfCharging(context: Context) {
        disarm(context)
        if (!isCharging(context)) return
        // 窗外不排布闹钟；窗口开始后（灭屏/充电事件或冷启动）会重新尝试
        if (!inDreamWindow()) {
            android.util.Log.d("HaoDream", "outside dream window (00:00-07:00), skip arm")
            return
        }
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

    /** 触发整理。manual=true 跳过充电与时间窗复核（手动入口复用）。 */
    fun fire(context: Context, manual: Boolean) {
        val app = context.applicationContext
        val st = (app as com.haoai.agent.HaoApplication).container.settingsFlow.value
        if (!st.memoryEnabled) return
        if (!manual && (!isCharging(app) || !isScreenOff(app) || !inDreamWindow())) {
            android.util.Log.d(
                "HaoDream",
                "fire skipped: charging=${isCharging(app)} screenOff=${isScreenOff(app)} inWindow=${inDreamWindow()}"
            )
            return
        }
        // B10 第二步：两端共写一份 MEMORY.md，自动整理先让位 —— 闲置期内文件被改过
        // （通常是 PC 端在跑它自己的整理/写记忆），本轮跳过并留一行原因（见 DreamYield）。
        if (!manual) {
            val container = (app as com.haoai.agent.HaoApplication).container
            val idle = idleMinutes(app)
            val mtime = runCatching { container.memoryBank.storageFile().lastModified() }.getOrDefault(0L)
            if (DreamYield.pcWroteDuringIdle(mtime, System.currentTimeMillis(), idle)) {
                android.util.Log.d("HaoDream", "yield: MEMORY.md modified during ${idle}min idle window (pc writing), skip this round")
                WorkspaceDocs.appendDreamSkip(container, "MEMORY.md 在闲置的 ${idle} 分钟内被改过（PC 端在写），本轮自动整理让位")
                return
            }
        }
        android.util.Log.d("HaoDream", "fire! manual=$manual")
        MemoryTidyWorker.enqueueOnce(app, force = manual)
    }

    private fun disarm(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        runCatching { am.cancel(firePendingIntent(context)) }
    }
}
