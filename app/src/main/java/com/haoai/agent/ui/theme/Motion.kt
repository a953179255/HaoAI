package com.haoai.agent.ui.theme

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** 液态纵深曲线（2026-10-27 效果图定稿 cubic-bezier(.24,.9,.26,1)，快出缓落） */
val LiquidPageEasing = CubicBezierEasing(0.24f, 0.9f, 0.26f, 1f)
/** 快速换位/上浮曲线（效果图 quick 段） */
val QuickPageEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

/**
 * 全局动画风格（三套完整语言，覆盖前端全部动效位置）：
 * - **Liquid 液态纵深**（默认）：页面转场=新页滑入盖上、旧页缩退成玻璃卡（结构见下）；
 *   弹层 spring 带一次过冲回弹，从触发点缩放 0.92→1 并上浮，底部弹窗越界回弹。
 * - **Snappy 丝滑横滑**：页面转场=190ms 轻位移换位淡入淡出，无满幅搬运；
 *   弹层高刚度 spring 无回弹，120-170ms，跟手优先。
 * - **Gentle 柔和穿梭**：页面转场=旧页原地淡出、新页上浮淡入，零横向运动；
 *   弹层 FastOutSlowIn 220-280ms，淡入为主 + 0.96 微缩放，安静。
 *
 * 位置覆盖：页面转场 / 抽屉 / 玻璃弹层(GlassPopup) / 弹窗(GlassAlertDialog) /
 * 底部弹窗(GlassBottomSheet) / 按压反馈(玻璃辉光) / 滑杆 / 展开收起 / 浮钮 / 开关 / 用量环。
 * 接入通道在 ui/common/Glass.kt 与 MainActivity（转场）。
 */
enum class MotionStyle(val label: String) {
    Liquid("液态纵深"),
    Snappy("丝滑横滑"),
    Gentle("柔和穿梭")
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
    // 2026-10-27 结构化重做（交互效果图方案获用户批准）：三档从「同一运动的
    // 曲线快慢」升级为「三种运动结构」——旧实现三档只差 spring/tween 参数，
    // 运动结构完全相同（满幅对滑+缩放沉底+纱幕），肉眼无法分辨档位。现在：
    // - **Liquid 液态纵深**：新页全幅滑入盖上，旧页原地缩退成玻璃卡（缩 0.94
    //   + 左移 16% + 变暗 26% + 圆角 26，由页面 chrome 的 retreat 进度驱动），
    //   壁纸恒定不动；430ms。
    // - **Snappy 丝滑横滑**：新页 36% 轻位移淡入 190ms，旧页 -16% 让位淡出
    //   170ms，无满幅搬运，壁纸恒定。
    // - **Gentle 柔和穿梭**：旧页原地淡出 140ms → 新页上浮 18dp 淡入 300ms
    //   （延迟 70ms），零横向运动。
    // 纱幕（TransitionScrim）随结构化**退役**：三种结构都不再需要净色接底
    // ——液态/丝滑的壁纸恒定可见，柔和是原地交叉淡化。
    // 滑动/淡出在 MainActivity 的 transitionSpec 声明；缩放/暗角/圆角/扫光
    // chrome 在 AnimatedContent 内容层按进度驱动（graphicsLayer，纯 GPU）。
    // 弹层/抽屉/按压仍走各自档位曲线（popupSpec/pressSpec/sheetSpec 不动）。

    /** 液态纵深总时长：滑入滑出与缩退 chrome 共用一条时间线 */
    val pageLiquidMs: Int get() = 430
    /** 丝滑横滑：进 190 / 出 170 */
    val pageSnappyInMs: Int get() = 190
    val pageSnappyOutMs: Int get() = 170
    /** 柔和穿梭：旧页淡出 140；新页延迟 70ms 后 300ms 上浮淡入 */
    val pageGentleOutMs: Int get() = 140
    val pageGentleInMs: Int get() = 300
    val pageGentleInDelayMs: Int get() = 70

    // ── 通用淡入淡出时长 ──
    val fadeMs: Int
        get() = when (style) {
            MotionStyle.Liquid -> 160
            MotionStyle.Snappy -> 110
            MotionStyle.Gentle -> 240
        }
}
