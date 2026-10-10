package com.haoai.agent.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「气泡 / 卡片不透明度」滑杆映射（BubbleOpacity）单测。
 *
 * 钉死 2026-10-09 修复的两个名实不符：
 * ① 滑杆 100% → 气泡/卡片 alpha 精确 1.0（此前 0.97/0.93/卡 0.94 恒不联动）；
 * ② 卡片档恒 ≥ 助手气泡档（"工具卡比气泡更透"不再可能）。
 */
class BubbleOpacityTest {

    private fun assertClose(expected: Float, actual: Float) =
        assertEquals(expected, actual, 1e-6f)

    @Test
    fun `滑杆满档全部精确不透明`() {
        assertClose(1f, BubbleOpacity.userAlpha(1f))
        assertClose(1f, BubbleOpacity.assistantAlpha(1f))
        assertClose(1f, BubbleOpacity.cardAlpha(1f))
    }

    @Test
    fun `滑杆下限保留可读性梯度`() {
        assertClose(0.14f, BubbleOpacity.userAlpha(0.3f))
        assertClose(0.45f, BubbleOpacity.assistantAlpha(0.3f))
        // 卡片低端有地板：消息流上的工具文字不许虚穿
        assertClose(0.80f, BubbleOpacity.cardAlpha(0.3f))
    }

    @Test
    fun `卡片恒不低于助手气泡`() {
        for (i in 0..70) {
            val o = 0.3f + i * 0.01f
            assert(BubbleOpacity.cardAlpha(o) >= BubbleOpacity.assistantAlpha(o)) {
                "档位 $o 卡片 ${BubbleOpacity.cardAlpha(o)} 比气泡 ${BubbleOpacity.assistantAlpha(o)} 更透"
            }
        }
    }

    @Test
    fun `默认档位07映射到中间值`() {
        // t = (0.7-0.3)/0.7 = 4/7 ≈ 0.5714
        val t = BubbleOpacity.ramp(0.7f)
        assertClose(0.5714286f, t)
        assertClose(0.14f + 0.86f * t, BubbleOpacity.userAlpha(0.7f))
        assertClose(0.45f + 0.55f * t, BubbleOpacity.assistantAlpha(0.7f))
        assertClose(0.80f + 0.20f * t, BubbleOpacity.cardAlpha(0.7f))
    }

    @Test
    fun `越界输入先钳位不炸映射`() {
        assertClose(1f, BubbleOpacity.userAlpha(1.8f))
        assertClose(0.45f, BubbleOpacity.assistantAlpha(0f))
        assertClose(0.80f, BubbleOpacity.cardAlpha(-2f))
    }
}
