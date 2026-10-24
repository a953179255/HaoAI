package com.haoai.agent.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider

/**
 * 全屏 Compose Dialog 沉浸化（2026-10-24 查看器灰带根因修复）的收尾件：
 * Dialog 靠 `DialogProperties(decorFitsSystemWindows = false)` 延伸到系统条
 * 后面（走属性比 view.parent 拿窗口在本项目 Compose 版本上可靠），这里再
 * 把状态栏/导航栏底色抹透明——不然窗口会按主题默认色给系统条画一道底，
 * 压暗下层界面的灰带换成主题色带，观感同样是割裂。
 * 在 Dialog 内容作用域里调用一次即可。
 */
@Suppress("DEPRECATION")
@Composable
internal fun FullscreenDialogImmersive() {
    val view = LocalView.current
    LaunchedEffect(view) {
        var p = view.parent
        while (p != null && p !is DialogWindowProvider) p = (p as? android.view.View)?.parent
        (p as? DialogWindowProvider)?.window?.let { w ->
            w.addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            w.statusBarColor = android.graphics.Color.TRANSPARENT
            w.navigationBarColor = android.graphics.Color.TRANSPARENT
        }
    }
}
