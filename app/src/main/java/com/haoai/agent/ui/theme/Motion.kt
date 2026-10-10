package com.haoai.agent.ui.theme

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 全局动画风格（三套完整语言，覆盖前端全部动效位置）：
 * - **Liquid 液态玻璃**（默认）：spring 带一次过冲回弹，弹层从触发点缩放 0.92→1 并上浮，
 *   底部弹窗越界回弹 —— 有物理感、有"果冻感"，与液态玻璃语言同源。
 * - **Snappy 丝滑响应**：高刚度 spring 无回弹，120-170ms。跟手性优先，即来即用。
 * - **Gentle 柔和渐显**：FastOutSlowIn 220-280ms，淡入为主 + 0.96 微缩放，弱化位移，安静。
 *
 * 位置覆盖：页面转场 / 抽屉 / 玻璃弹层(GlassPopup) / 弹窗(GlassAlertDialog) /
 * 底部弹窗(GlassBottomSheet) / 按压反馈(玻璃辉光) / 滑杆 / 展开收起 / 浮钮 / 开关 / 用量环。
 * 接入通道在 ui/common/Glass.kt 与 MainActivity（转场）。
 */
enum class MotionStyle(val label: String) {
    Liquid("液态玻璃"),
    Snappy("丝滑响应"),
    Gentle("柔和渐显")
}

object MotionTheme {
    var style by mutableStateOf(MotionStyle.Liquid)

    private val gentleEasing = FastOutSlowInEasing

    // ── 弹层（GlassPopup / GlassAlertDialog）：缩放起点 / 上浮距离 / 规格 ──
    val popupFromScale: Float
        get() = when (style) {
            MotionStyle.Liquid -> 0.92f
            MotionStyle.Snappy -> 0.97f
            MotionStyle.Gentle -> 0.96f
        }

    val popupSlideDp: Float
        get() = when (style) {
            MotionStyle.Liquid -> 10f
            MotionStyle.Snappy -> 0f
            MotionStyle.Gentle -> 4f
        }

    val popupSpec: AnimationSpec<Float>
        get() = when (style) {
            MotionStyle.Liquid -> spring(dampingRatio = 0.8f, stiffness = 340f)
            MotionStyle.Snappy -> spring(dampingRatio = 1f, stiffness = 520f)
            MotionStyle.Gentle -> tween(260, easing = gentleEasing)
        }

    // ── 按压辉光（LiquidGlassButton 等）：辉光进度规格 ──
    val pressSpec: AnimationSpec<Float>
        get() = when (style) {
            MotionStyle.Liquid -> spring(dampingRatio = 0.55f, stiffness = 600f)
            MotionStyle.Snappy -> spring(dampingRatio = 1f, stiffness = 700f)
            MotionStyle.Gentle -> tween(200, easing = gentleEasing)
        }

    // ── 底部弹窗（GlassBottomSheet）──
    // 只接**进场**：Liquid 档轻微过冲 = 面板滑到顶后越界回弹，即文档说的
    // "底部弹窗越界回弹"；退场目标是滑出屏幕，过冲会先冲过头再弹回来，
    // 视觉错误 —— 退场保持组件内的快速 tween。
    // stiffness=850：底弹窗进场位移 2200px，此刚度收敛 ≈240ms（与原 tween 一致），
    // 接线不改变默认档手感，只补上原本就设计的轻微回弹。
    val sheetSpec: AnimationSpec<Float>
        get() = when (style) {
            MotionStyle.Liquid -> spring(dampingRatio = 0.82f, stiffness = 850f)
            MotionStyle.Snappy -> spring(dampingRatio = 1f, stiffness = 1200f)
            MotionStyle.Gentle -> tween(300, easing = gentleEasing)
        }

    // （expandSpec 已删 2026-10-10：零引用的旧设计残留。尺寸展开/收起动画
    // 不适合带过冲的 spring —— 高度过冲 = 内容溢出裁切再弹回，各展开点
    // 的固定曲线（tween 180-260ms）是各自调好的，不做全局档位。）

    // ── 页面转场（MainActivity AnimatedContent）──────────────────────
    //
    // 转场接线缺口修复（2026-10-10）：原 pageSlideFraction/pageMs/pageEasing 是
    // **早期转场设计**（28% 部分位移 + 固定时长）的参数，而转场后来重做成了
    // push/pop 双向全屏滑入 + 缩放沉底 —— 属性与实现语义对不上，一直没接，
    // 结果是设置里切动效档位对页面转场完全无效（审查点名的"设置项部分失效"）。
    //
    // 修法：把**当前实测调好的参数**作为 Liquid（默认）档参数化进来，三档
    // 各有可感知差异；过时的三个属性删除（留着就是"看起来有开关其实没接"）。
    // 注意 Liquid 档值 = 重做转场时的实测值，接上后默认体验零变化。

    /** 页面滑入/滑出位移规格（IntOffset）。 */
    val pageSlideSpec: androidx.compose.animation.core.FiniteAnimationSpec<androidx.compose.ui.unit.IntOffset>
        get() = when (style) {
            MotionStyle.Liquid -> spring(
                dampingRatio = 0.9f,
                stiffness = Spring.StiffnessMedium,
                visibilityThreshold = androidx.compose.ui.unit.IntOffset(1, 1)
            )
            // 丝滑响应：临界阻尼 + 高刚度，快而不弹
            MotionStyle.Snappy -> spring(
                dampingRatio = 1f,
                stiffness = 2500f,
                visibilityThreshold = androidx.compose.ui.unit.IntOffset(1, 1)
            )
            MotionStyle.Gentle -> tween(320, easing = gentleEasing)
        }

    /** 页面淡入/淡出规格。 */
    val pageFadeSpec: androidx.compose.animation.core.FiniteAnimationSpec<Float>
        get() = when (style) {
            MotionStyle.Liquid -> tween(300, easing = FastOutSlowInEasing)
            MotionStyle.Snappy -> tween(160, easing = FastOutSlowInEasing)
            MotionStyle.Gentle -> tween(280, easing = gentleEasing)
        }

    /** 页面缩放（旧页沉底/回位）规格。 */
    val pageScaleSpec: androidx.compose.animation.core.FiniteAnimationSpec<Float>
        get() = when (style) {
            MotionStyle.Liquid -> spring(
                dampingRatio = 0.9f,
                stiffness = Spring.StiffnessMedium,
                visibilityThreshold = 0.001f
            )
            MotionStyle.Snappy -> spring(dampingRatio = 1f, stiffness = 2500f, visibilityThreshold = 0.001f)
            MotionStyle.Gentle -> tween(280, easing = gentleEasing)
        }

    // ── 通用淡入淡出时长 ──
    val fadeMs: Int
        get() = when (style) {
            MotionStyle.Liquid -> 160
            MotionStyle.Snappy -> 110
            MotionStyle.Gentle -> 240
        }
}
