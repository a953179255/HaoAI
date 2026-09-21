package com.haoai.agent.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 玻璃质感**实时调参**：玻璃实验室（GlassLabActivity）写入，全项目读取。
 *
 * 为什么做成单例 State：滑杆拖动 → 这里变化 → 所有读它的玻璃（卡片/顶栏）
 * 当帧重组，效果即时可见；不用改代码重新编译。
 *
 * 会话内生效（重启回到默认值）。[reset] 一键还原出厂默认；
 * 定稿后把数值固化进 [HaoDimens] 即可（实验室里有"复制参数"按钮，粘贴给开发就行）。
 */
object GlassTuning {

    /** 出厂默认（实测定稿）：卡片 blur 8 / lens(16, 32) / 白雾 0.48；顶栏 blur 15 / 白雾 0.55 */
    const val DEFAULT_BLUR = 8f
    const val DEFAULT_LENS_HEIGHT = 16f
    const val DEFAULT_LENS_AMOUNT_MUL = 2f
    const val DEFAULT_VEIL = 0.48f
    const val DEFAULT_CORNER = 16f
    const val DEFAULT_LENS_FULL = false
    const val DEFAULT_CA = true

    /** 顶栏与卡片分开调：顶栏有标题文字，需要更实的磨砂（用户实测 blur≈15 合适） */
    const val DEFAULT_BAR_BLUR = 15f
    const val DEFAULT_BAR_VEIL = 0.55f

    /** 背景模糊 dp（磨砂感的主要来源）——卡片用 */
    var blur by mutableFloatStateOf(DEFAULT_BLUR)

    /** 折射高度 dp —— 即 lens 的边缘环带宽度；通栏大卡建议 16~40 */
    var lensHeight by mutableFloatStateOf(DEFAULT_LENS_HEIGHT)

    /** 折射强度倍数 —— 位移量 = 折射高度 × 此值（演示 App 的比例是 2） */
    var lensAmountMul by mutableFloatStateOf(DEFAULT_LENS_AMOUNT_MUL)

    /** 白雾（卡片表面不透明度） */
    var veil by mutableFloatStateOf(DEFAULT_VEIL)

    /** 圆角 dp */
    var corner by mutableFloatStateOf(DEFAULT_CORNER)

    /** 整面折射：折射高度自动改为"短边一半"，四边环带在中心汇合 */
    var lensFull by mutableStateOf(DEFAULT_LENS_FULL)

    /** 色差（边缘红蓝分离） */
    var ca by mutableStateOf(DEFAULT_CA)

    /** 顶栏背景模糊 dp（与卡片分开调：顶栏有标题文字，需要更实的磨砂） */
    var barBlur by mutableFloatStateOf(DEFAULT_BAR_BLUR)

    /** 顶栏白雾 */
    var barVeil by mutableFloatStateOf(DEFAULT_BAR_VEIL)

    /** 一键还原出厂默认（玻璃实验室的"还原默认"按钮） */
    fun reset() {
        blur = DEFAULT_BLUR
        lensHeight = DEFAULT_LENS_HEIGHT
        lensAmountMul = DEFAULT_LENS_AMOUNT_MUL
        veil = DEFAULT_VEIL
        corner = DEFAULT_CORNER
        lensFull = DEFAULT_LENS_FULL
        ca = DEFAULT_CA
        barBlur = DEFAULT_BAR_BLUR
        barVeil = DEFAULT_BAR_VEIL
    }
}
