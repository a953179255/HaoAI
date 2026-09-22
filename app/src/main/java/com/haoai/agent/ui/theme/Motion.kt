package com.haoai.agent.ui.theme

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
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
    private val liquidEasing = CubicBezierEasing(0.3f, 1.2f, 0.4f, 1f)

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
    val sheetSpec: AnimationSpec<Float>
        get() = when (style) {
            MotionStyle.Liquid -> spring(dampingRatio = 0.85f, stiffness = 300f)
            MotionStyle.Snappy -> spring(dampingRatio = 1f, stiffness = 480f)
            MotionStyle.Gentle -> tween(300, easing = gentleEasing)
        }

    // ── 展开/收起（任务面板 / 思考链 / 上下文详情）──
    val expandSpec: AnimationSpec<Float>
        get() = when (style) {
            MotionStyle.Liquid -> spring(dampingRatio = 0.85f, stiffness = 380f)
            MotionStyle.Snappy -> spring(dampingRatio = 1f, stiffness = 560f)
            MotionStyle.Gentle -> tween(260, easing = gentleEasing)
        }

    // ── 页面转场（MainActivity AnimatedContent）：位移比例 / 时长 / 缓动 ──
    val pageSlideFraction: Float
        get() = when (style) {
            MotionStyle.Liquid -> 0.28f
            MotionStyle.Snappy -> 0.12f
            MotionStyle.Gentle -> 0.18f
        }

    val pageMs: Int
        get() = when (style) {
            MotionStyle.Liquid -> 400
            MotionStyle.Snappy -> 190
            MotionStyle.Gentle -> 280
        }

    val pageEasing: Easing
        get() = when (style) {
            MotionStyle.Liquid -> liquidEasing
            MotionStyle.Snappy -> CubicBezierEasing(0.2f, 0f, 0f, 1f)
            MotionStyle.Gentle -> gentleEasing
        }

    // ── 通用淡入淡出时长 ──
    val fadeMs: Int
        get() = when (style) {
            MotionStyle.Liquid -> 160
            MotionStyle.Snappy -> 110
            MotionStyle.Gentle -> 240
        }
}
