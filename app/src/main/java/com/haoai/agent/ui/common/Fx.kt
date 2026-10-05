package com.haoai.agent.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import kotlin.math.abs

/**
 * 装饰动画驱动（v3，方案 A：生成期降 GPU 发热，观感零损）。
 *
 * 用 withFrameNanos（Compose 帧时钟）驱动相位，但**把写入节流到 [minIntervalMs]**
 * （默认 33ms ≈ 30Hz）：
 *  - 相位始终由**真实时间戳**算出（`now % period`），不是累加步进——所以掉帧/节流
 *    都不会造成跳变或速度漂移，慢速动画（1.6s 的流光扫过）在 30Hz 下肉眼与全帧率无异。
 *  - 只有"写入了新值"才 invalidate → 才逼 RenderThread 出帧 → 才触发采样宿主
 *    重录 + 玻璃重磨。节流写入 = 直接把生成期整屏出帧从 72fps 压到约 30fps，
 *    GPU 重磨次数减半。**这就是降发的核心杠杆**。
 *
 * 为什么安全（不会重蹈当初 delay(33) 被否的覆辙）：
 *  当初被否是因为把**跟手动画**（拖抽屉/切页/按压/滚动）也降了帧——涩。
 *  而本函数**只服务纯装饰**（流光、呼吸点、待删除脉冲、表格骨架、低频转圈），
 *  全仓没有任何跟手动画经它驱动（那些走各自的 Animatable/snapshotFlow 通道，
 *  帧率通道彼此独立）。所以降它的频率，物理上碰不到用户手感里的"流畅"。
 *
 * **返回 [State] 而不是裸 Float 是刻意的**：订阅发生在调用方读取（`.value` 或
 * 委托变量）的作用域。值只在 `if (live)`、`if (deleteArmed)` 这类分支里读的场合，
 * 分支没走 → 写入零订阅 → 不产出渲染帧；分支走到（动画可见）→ 节流后照常出帧。
 * [enabled]=false 直接不启动协程：已知"组合着但必然看不见"的场合用它整个关掉。
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
    minIntervalMs: Int = 33,
): State<Float> {
    val phase = remember { mutableFloatStateOf(min) }
    if (enabled) {
        LaunchedEffect(periodMs, reverse, minIntervalMs) {
            var lastWrite = 0L
            while (true) {
                androidx.compose.runtime.withFrameNanos { now ->
                    // 写入节流：距上次不足 minIntervalMs 就本帧不写（不动值=不出帧）
                    if (now - lastWrite < minIntervalMs * 1_000_000L) return@withFrameNanos
                    lastWrite = now
                    val p = ((now / 1_000_000L) % periodMs) / periodMs.toFloat()
                    val v = if (reverse) 1f - abs(2f * p - 1f) else p
                    phase.floatValue = min + (max - min) * v
                }
            }
        }
    }
    return phase
}

/**
 * 低频自绘转圈（替代库内 CircularProgressIndicator 的无限动画）。
 *
 * 库的进度环走自己的无限动画，全帧率出帧、无法从外部限频，而且它一旦被放进
 * 采样宿主（消息列表）内部，每次旋转都会触发整屏 backdrop 重录 + 玻璃重磨——
 * 是 rememberPulse 之外的第二个"逼帧源"。这里改走 [rememberPulse]（已节流到
 * 30Hz），观感上仍是匀速转的加载环（1 圈/秒），但出帧频率与其余装饰动画同步。
 *
 * 只用于**装饰性 indeterminate**（思考中/运行中）。确定性进度（如上下文占用环，
 * animateFloatAsState 有限时长动画）不适用——那种只在数值变化时短暂动，不常驻烧帧。
 */
@Composable
fun SlowSpinner(
    size: Dp,
    strokeWidth: Dp,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val spin by rememberPulse(0f, 1f, 1000)
    Canvas(modifier = modifier.size(size)) {
        val sw = strokeWidth.toPx()
        val d = size.toPx() - sw
        val cx = size.toPx() / 2f
        rotate(spin * 360f, pivot = Offset(cx, cx)) {
            drawArc(
                color = color,
                startAngle = 0f,
                sweepAngle = 280f,
                useCenter = false,
                topLeft = Offset(sw / 2f, sw / 2f),
                size = Size(d, d),
                style = Stroke(width = sw, cap = StrokeCap.Round)
            )
        }
    }
}
