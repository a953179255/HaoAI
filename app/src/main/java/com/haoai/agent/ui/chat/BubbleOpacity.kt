package com.haoai.agent.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext

/**
 * 「气泡 / 卡片不透明度」滑杆（设置 0.3–1.0）的统一映射。
 *
 * 2026-10-09 修复两处名实不符：
 * ① 旧映射 100% 时助手气泡只有 0.97、用户气泡 0.93（注释自己承认"几乎不透明"），
 *    滑杆显示 100% 却仍见壁纸透底——现在 100% 一律精确到 1.0（真·不透明）。
 * ② 工具调用卡/链卡/思考面板 8 处硬编码 surface 0.94，完全不吃滑杆，
 *    导致"卡片比气泡更透"（100% 时卡 0.94 < 泡 0.97）——卡片现随滑杆联动，
 *    档 [0.80, 1.0]：低端保可读地板（花壁纸上工具行文字不能虚），
 *    且恒 ≥ 助手气泡（差值 0.35×(1−t)），观感上"卡比泡实"的层级在任何档位不倒挂。
 */
internal object BubbleOpacity {
    /** 滑杆值 → 0..1 斜坡；越界先钳到 [0.3, 1.0]（与 SettingsStore 口径一致）。 */
    fun ramp(opacity: Float): Float = (opacity.coerceIn(0.3f, 1f) - 0.3f) / 0.7f

    /** 用户气泡（primary 底）：30%→0.14 轻透，100%→1.0 实色。 */
    fun userAlpha(opacity: Float): Float = 0.14f + 0.86f * ramp(opacity)

    /** 助手气泡（surface 底）：30%→0.45，100%→1.0。 */
    fun assistantAlpha(opacity: Float): Float = 0.45f + 0.55f * ramp(opacity)

    /** 消息流内工具卡表面（链卡/胶囊/思考面板）：30%→0.80 地板，100%→1.0。 */
    fun cardAlpha(opacity: Float): Float = 0.80f + 0.20f * ramp(opacity)
}

/** 当前滑杆设置值；非 HaoApplication 宿主（预览/测试）回退默认 0.7。 */
@Composable
fun chatBubbleOpacity(): Float {
    val app = LocalContext.current.applicationContext
        as? com.haoai.agent.HaoApplication ?: return 0.7f
    val settings by app.container.settingsFlow.collectAsState()
    return settings.bubbleOpacity
}

/** 气泡不透明度（设置 30%-100%）映射为 (用户气泡 alpha, 助手气泡 alpha)；100% 时全不透明。 */
@Composable
fun chatBubbleAlphas(): Pair<Float, Float> {
    val o = chatBubbleOpacity()
    return BubbleOpacity.userAlpha(o) to BubbleOpacity.assistantAlpha(o)
}

/** 工具调用卡/链卡/思考面板表面 alpha，随滑杆联动。 */
@Composable
fun chatCardAlpha(): Float = BubbleOpacity.cardAlpha(chatBubbleOpacity())
