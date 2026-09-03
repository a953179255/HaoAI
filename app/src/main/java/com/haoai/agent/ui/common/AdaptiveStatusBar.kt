package com.haoai.agent.ui.common

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * 状态栏图标颜色自适应顶部实际亮度。
 *
 * 背景：MainActivity 用无参 enableEdgeToEdge()，图标深浅默认跟随「系统」日/夜，
 * 于是系统深色 + App 浅色时顶部是浅底、图标却是白色 → 看不见。这里改由 App 自己决定：
 *  1) 先按 App 主题给个初值（浅色→深色图标），避免首帧白图标一闪（方法 A 兜底）；
 *  2) 再对状态栏那条真实合成像素做 PixelCopy 采样，亮度高→深色图标、低→浅色图标，
 *     带迟滞阈值防抖闪（方法 B，壁纸/渐变/玻璃着色/遮罩全都自动正确）。
 *
 * sampleKey 变化（主题/主题色/动态色/AMOLED/页面切换/壁纸更换）时重采样。
 * 聊天滚动不影响顶部：通栏玻璃 TopBar 采样的是固定的壁纸/主题 backdrop 层，非滚动内容。
 */
@Composable
fun AdaptiveStatusBarIcons(dark: Boolean, sampleKey: Any?) {
    val context = LocalContext.current
    val view = LocalView.current
    val window: Window? = (context as? Activity)?.window
        ?: (view.context as? Activity)?.window

    LaunchedEffect(dark, sampleKey, window) {
        val win = window ?: return@LaunchedEffect
        val decor = win.decorView
        // 初值：按 App 主题，立刻纠正「系统深色→白图标」的错配
        setLightStatusIcons(win, light = !dark)
        if (decor.width == 0 || !view.isShown) return@LaunchedEffect
        // 等两帧，确保新主题/壁纸已真正画出来，再采样
        withFrameNanos { }
        withFrameNanos { }
        val top = statusBarHeightPx(decor)
        if (top <= 0) return@LaunchedEffect
        val bmp = pixelCopyStrip(win, Rect(0, 0, decor.width, top)) ?: return@LaunchedEffect
        val lum = averageLuminance(bmp)
        bmp.recycle()
        // 迟滞：>0.60 转深色图标，<0.50 转浅色图标，中间维持当前，避免临界壁纸来回跳
        val cur = isLightStatusIcons(win)
        val next = if (lum > 0.60f) true else if (lum < 0.50f) false else cur
        setLightStatusIcons(win, light = next)
    }
}


private fun controller(window: Window): WindowInsetsControllerCompat =
    WindowCompat.getInsetsController(window, window.decorView)

private fun setLightStatusIcons(window: Window, light: Boolean) {
    runCatching { controller(window).isAppearanceLightStatusBars = light }
}

private fun isLightStatusIcons(window: Window): Boolean =
    runCatching { controller(window).isAppearanceLightStatusBars }.getOrDefault(false)

private fun statusBarHeightPx(decor: View): Int {
    val insets = decor.rootWindowInsets ?: return 0
    return runCatching {
        WindowInsetsCompat.toWindowInsetsCompat(insets, decor)
            .getInsets(WindowInsetsCompat.Type.statusBars()).top
    }.getOrDefault(0)
}

/** 截取窗口某矩形区域的真实像素；失败（未聚焦/安全窗口等）返回 null。 */
private suspend fun pixelCopyStrip(window: Window, rect: Rect): Bitmap? =
    suspendCancellableCoroutine { cont ->
        val w = rect.width().coerceAtLeast(1)
        val h = rect.height().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val handler = Handler(Looper.getMainLooper())
        try {
            PixelCopy.request(window, rect, bmp, { result ->
                if (result == PixelCopy.SUCCESS) {
                    if (cont.isActive) cont.resume(bmp) else bmp.recycle()
                } else {
                    bmp.recycle()
                    if (cont.isActive) cont.resume(null)
                }
            }, handler)
        } catch (_: Throwable) {
            bmp.recycle()
            if (cont.isActive) cont.resume(null)
        }
    }

/** 顶部条平均亮度（Rec.709，0~1）：降采样后逐点求均值。 */
private fun averageLuminance(bmp: Bitmap): Float {
    val targetW = 48
    val targetH = (targetW * bmp.height / bmp.width).coerceAtLeast(1)
    val small = Bitmap.createScaledBitmap(bmp, targetW, targetH, true)
    var sum = 0f
    var n = 0
    for (y in 0 until targetH) for (x in 0 until targetW) {
        val c = small.getPixel(x, y)
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        sum += (0.2126f * r + 0.7152f * g + 0.0722f * b) / 255f
        n++
    }
    if (small !== bmp) small.recycle()
    return if (n > 0) sum / n else 0f
}
