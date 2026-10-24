package com.haoai.agent.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider

/**
 * 全屏 Compose Dialog 沉浸化收尾件（2026-10-24 查看器灰带修复 + 状态栏图标可见性）：
 * 1) Dialog 靠 `DialogProperties(decorFitsSystemWindows = false)` 延伸到系统条后面
 *    （走属性比 view.parent 直取窗口在本项目 Compose 版本上可靠），这里再把状态栏
 *    /导航栏底色抹透明，不然窗口按主题默认色给系统条画一道底；
 * 2) 系统条图标的深浅是「每个窗口自己」的属性——普通界面全在 Activity 一个窗口里，
 *    共用 AdaptiveStatusBarIcons 那一份；Dialog 是独立窗口，必须自己声明，否则继承
 *    到的默认值与主题错配（浅色主题配到白图标 → 状态栏内容隐形）。[surface] 是
 *    系统条底下那块底色：亮度低→白图标、高→深图标（状态栏/导航栏同用这一块），
 *    跟随主题的壳传主题背景色，恒定深壳（如 HTML 预览）直接传壳色。
 * 在 Dialog 内容作用域里调用一次即可。
 */
@Suppress("DEPRECATION")
@Composable
internal fun FullscreenDialogImmersive(surface: Color) {
    val view = LocalView.current
    // isAppearanceLightStatusBars 语义=「系统条底是浅色」→系统画深色图标；
    // 深底传 true 的 lightIcons 要反过来。参考 AdaptiveStatusBar 的 light=!dark。
    val darkSurface = surface.luminance() < 0.5f
    LaunchedEffect(view, darkSurface) {
        var p = view.parent
        while (p != null && p !is DialogWindowProvider) p = (p as? android.view.View)?.parent
        (p as? DialogWindowProvider)?.window?.let { w ->
            w.addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            w.statusBarColor = android.graphics.Color.TRANSPARENT
            w.navigationBarColor = android.graphics.Color.TRANSPARENT
            val c = androidx.core.view.WindowCompat.getInsetsController(w, w.decorView)
            c.isAppearanceLightStatusBars = !darkSurface
            c.isAppearanceLightNavigationBars = !darkSurface
        }
    }
}
