package com.haoai.agent.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import kotlin.math.abs

/**
 * 装饰动画专用低频驱动（发热治理 P0-3）。
 *
 * shimmer 渐变、呼吸点、脉冲这类纯装饰动画原先用 infiniteTransition 全帧率驱动：
 * 120Hz 屏上每秒 120 次重组+重绘，而聊天页的玻璃元素背后是全屏 backdrop——
 * 每一帧都连带一次全屏采样重模糊，运行期 GPU/CPU 被装饰动画钉死在满帧。
 *
 * 改成 delay(33) 驱动的 ~30fps 状态写入：肉眼几乎无差别，帧产出直接降到 1/4。
 * 刻意不用 withFrameNanos/动画时钟——delay 走 Handler 队列，不会把 vsync 钉在全速。
 *
 * **[enabled]=false 时直接早退**，不组合任何状态与协程：delay 驱动与旧
 * infiniteTransition 有本质区别——后者等帧时钟，页面静止时跟着停摆；前者只要在
 * 组合里就每 33ms 写一次状态。所以"当前不需要动画"的场合（任务面板展开且无
 * 进行中项等挂机态）必须显式关掉，否则挂机页被钉在常驻 30fps（真机实测）。
 * 返回裸 Float：调用方的读取随组合发生，组合了才订阅，不组合零成本。
 */
@Composable
fun rememberPulse(
    min: Float,
    max: Float,
    periodMs: Int,
    reverse: Boolean = false,
    enabled: Boolean = true,
): Float {
    if (!enabled) return min
    val phase = remember { mutableFloatStateOf(min) }
    LaunchedEffect(periodMs, reverse) {
        val t0 = System.nanoTime() / 1_000_000L
        while (true) {
            val p = ((System.nanoTime() / 1_000_000L - t0) % periodMs) / periodMs.toFloat()
            val v = if (reverse) 1f - abs(2f * p - 1f) else p
            phase.floatValue = min + (max - min) * v
            kotlinx.coroutines.delay(33)
        }
    }
    return phase.floatValue
}
