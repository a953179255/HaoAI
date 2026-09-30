package com.haoai.agent.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import kotlin.math.abs

/**
 * 装饰动画驱动（v2，按用户裁决回归全帧率）：跟随系统刷新率的脉冲/扫光相位。
 *
 * 用 withFrameNanos（Compose 帧时钟）驱动——时钟跑在系统当前刷新率上：
 * 120Hz 屏就是 120fps，省电模式降到 60 就跟着 60，视觉与原 infiniteTransition 一致。
 * 用户裁决：帧数低的涩感远比发热难受，不牺牲流畅换降温。
 *
 * **返回 [State] 而不是裸 Float 是刻意的**：订阅发生在调用方读取（`.value` 或
 * 委托变量）的作用域。值只在 `if (live)`、`if (deleteArmed)` 这类分支里读的场合，
 * 分支没走 → 写入零订阅 → 不产出渲染帧（帧时钟本身不逼 RenderThread 出帧，
 * 静置页面实测 0-2fps）；分支走到（动画可见）→ 写入照常出帧、全帧率丝滑。
 * delay(33) 限帧版已被否决：30fps 的动画涩感用户实测不可接受。
 *
 * [enabled]=false 直接不启动协程：已知"组合着但必然看不见"的场合（抽屉关闭时
 * 的会话行——offset 平移无离屏层；任务面板展开且无进行中项）用它整个关掉。
 *
 * [reverse]=true 对应 infiniteRepeatable(…, RepeatMode.Reverse) 的三角波形：
 * 完整周期是 tween 时长的两倍（去程+回程），调用方传 2×时长。
 */
@Composable
fun rememberPulse(
    min: Float,
    max: Float,
    periodMs: Int,
    reverse: Boolean = false,
    enabled: Boolean = true,
): State<Float> {
    val phase = remember { mutableFloatStateOf(min) }
    if (enabled) {
        LaunchedEffect(periodMs, reverse) {
            while (true) {
                androidx.compose.runtime.withFrameNanos { now ->
                    val p = ((now / 1_000_000L) % periodMs) / periodMs.toFloat()
                    val v = if (reverse) 1f - abs(2f * p - 1f) else p
                    phase.floatValue = min + (max - min) * v
                }
            }
        }
    }
    return phase
}
