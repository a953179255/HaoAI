package com.haoai.agent.platform.a11y

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import kotlinx.coroutines.delay

/**
 * 无障碍服务统一门禁与引导：
 *
 * 1. 已连接 → 放行（零开销）；
 * 2. 已被一次性授予 WRITE_SECURE_SETTINGS（adb pm grant，非 Shizuku）→ 静默写入开启，
 *    服务自动连接，全程不离开当前界面；
 * 3. 都没有 → 自动弹出系统无障碍开关页（多级兜底），轮询等待用户开启后放行，
 *    超时才把失败交给模型转告用户。
 *
 * 「设置 → 去开启」按钮与全部手机操作/虚拟屏工具共用，保证入口行为一致。
 */
object A11yGate {

    val serviceComponent = ComponentName(
        "com.haoai.agent",
        "com.haoai.agent.platform.a11y.HaoAccessibilityService"
    )

    private const val DETAILS_ACTION = "android.settings.ACCESSIBILITY_DETAILS_SETTINGS"
    private const val FRAGMENT_ARG_KEY = ":settings:fragment_args_key"

    /** 弹页冷却：同一次任务里模型连发多个 a11y 工具时，避免超时后反复抢屏弹设置页。 */
    @Volatile
    private var lastPromptAt = 0L

    /**
     * 等待无障碍可用。返回 null = 已可用（放行）；非 null = 超时/无法引导，给模型的失败说明。
     * 在工具的 IO 协程里轮询，总等待控制在工具超时（180s）之内。
     */
    suspend fun awaitEnabled(appContext: Context?, waitUserMs: Long = 45_000L): String? {
        if (HaoAccessibilityService.instance != null) return null
        val ctx = appContext ?: return hint()
        // 路径①：静默自启——adb 授过 WRITE_SECURE_SETTINGS 的设备，不弹页、不离开当前界面
        if (trySelfEnable(ctx)) {
            val until = System.currentTimeMillis() + 8_000L
            while (System.currentTimeMillis() < until) {
                if (HaoAccessibilityService.instance != null) return null
                delay(300)
            }
        }
        // 路径②：弹系统无障碍页，等用户手动开启（冷却期内不重复抢屏，但继续等）
        if (System.currentTimeMillis() - lastPromptAt > 8_000L) {
            lastPromptAt = System.currentTimeMillis()
            runCatching { openEnablePage(ctx) }
        }
        val until = System.currentTimeMillis() + waitUserMs
        while (System.currentTimeMillis() < until) {
            if (HaoAccessibilityService.instance != null) return null
            delay(500)
        }
        return hint()
    }

    /**
     * 跳转无障碍开关页，多级兜底（各 ROM 权限不一，每级失败落下一级）：
     * 1) 服务详情开关页——最短路径；Android 12L+ 受签名权限 OPEN_ACCESSIBILITY_DETAILS_SETTINGS
     *    保护（实测 Flyme 上连 shell 都被拒），多数 ROM 会抛 SecurityException；
     * 2) AOSP 无障碍列表页（已下载的应用分组，HaoAI 在首屏；带定位参数，部分 ROM 会高亮目标行）；
     * 3) ACTION_ACCESSIBILITY_SETTINGS 通用兜底（任何 ROM 都有，Flyme 上落「辅助功能」聚合页）。
     */
    fun openEnablePage(context: Context) {
        val flat = serviceComponent.flattenToString()
        runCatching {
            context.startActivity(
                Intent(DETAILS_ACTION)
                    .putExtra(Intent.EXTRA_COMPONENT_NAME, serviceComponent)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        }
        runCatching {
            context.startActivity(
                Intent()
                    .setClassName("com.android.settings", "com.android.settings.Settings\$AccessibilitySettingsActivity")
                    .putExtra(FRAGMENT_ARG_KEY, flat)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        }
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /**
     * 静默开启：把 HaoAI 追加进 enabled_accessibility_services 并置 accessibility_enabled=1。
     * WRITE_SECURE_SETTINGS 是签名级权限，普通应用拿不到，只能 adb 一次性授予——
     * 授予本身是用户的一次显式动作，之后自动开启即代表用户意愿。
     */
    private fun trySelfEnable(ctx: Context): Boolean {
        val granted = ctx.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) return false
        return runCatching {
            val cr = ctx.contentResolver
            val cur = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
            val flat = serviceComponent.flattenToString()
            val merged = if (cur.split(':').any { it.equals(flat, ignoreCase = true) }) cur
            else if (cur.isBlank()) flat else "$cur:$flat"
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, merged)
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            true
        }.getOrDefault(false)
    }

    /** 超时后给模型的说明：让模型停下转告用户，而不是继续盲试。 */
    private fun hint(): String =
        "等待无障碍授权超时：已自动弹出系统无障碍开关页，但等待期内未见开启。请停止当前步骤并告知用户二选一：" +
            "① 在弹出的「无障碍 → HaoAI 自动化」页打开开关，下次任务会自动继续；" +
            "② 电脑连一次 adb 执行 pm grant com.haoai.agent android.permission.WRITE_SECURE_SETTINGS" +
            "（一次性授权），之后需要时 HaoAI 会静默自动开启，不再弹页。"
}
