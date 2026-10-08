package com.haoai.agent.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 玻璃质感**实时调参**：主题外观设置页 / 玻璃实验室写入，全项目读取。
 *
 * 为什么做成单例 State：滑杆拖动 → 这里变化 → 所有读它的玻璃（卡片/顶栏/输入框/
 * 抽屉/面板）当帧重组，效果即时可见；不用改代码重新编译。
 *
 * 持久化：单例只是运行态镜像，真源在 AppSettings.glass（GlassParams）；
 * 启动时 [loadFrom] 灌入，调参页防抖 [snapshot] 落盘。
 *
 * 统一原则（2026-10-08 用户裁决）：**全 App 玻璃共享一套折射/模糊配方**——
 * 之前输入框 52dp、弹窗 56dp、卡片 32dp 各写各的，观感打架。定稿默认
 * 折射 16×1 + 模糊 4（用户在实验室实测选定）。顶栏保留独立白雾（标题文字
 * 需要更实的底，用户未抱怨过）；对话框/消息操作面板保留更不透明的表面
 * （可读性优先），但折射/模糊并入统一值。
 */
object GlassTuning {

    /** 出厂默认（2026-10-08 实验室定稿）：blur 4 / lens(16, ×1) / 白雾 0.48；
     *  顶栏白雾 0.55（文字底）；输入框模糊 4 */
    const val DEFAULT_BLUR = 4f
    const val DEFAULT_LENS_HEIGHT = 16f
    const val DEFAULT_LENS_AMOUNT_MUL = 1f
    const val DEFAULT_VEIL = 0.48f
    const val DEFAULT_CORNER = 16f
    const val DEFAULT_LENS_FULL = false
    const val DEFAULT_CA = true

    /** 顶栏与卡片分开调：顶栏有标题文字，需要更实的磨砂（用户实测 blur≈15 合适；
     *  折射/倍数与全局统一，只有模糊和白雾是顶栏专属档） */
    const val DEFAULT_BAR_BLUR = 15f
    const val DEFAULT_BAR_VEIL = 0.55f
    const val DEFAULT_INPUT_BLUR = 4f
    const val DEFAULT_INPUT_VEIL = 0.48f

    /** 背景模糊 dp（磨砂感的主要来源）——全 App 玻璃统一 */
    var blur by mutableFloatStateOf(DEFAULT_BLUR)

    /** 折射高度 dp —— 即 lens 的边缘环带宽度；统一值 */
    var lensHeight by mutableFloatStateOf(DEFAULT_LENS_HEIGHT)

    /** 折射强度倍数 —— 位移量 = 折射高度 × 此值；统一值 */
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

    /** 输入框背景模糊 dp（折射/倍数走统一值，模糊单独一档：输入框常年压在正文上） */
    var inputBlur by mutableFloatStateOf(DEFAULT_INPUT_BLUR)

    /** 输入框白雾（2026-10-08 起独立于卡片，用户单独调） */
    var inputVeil by mutableFloatStateOf(DEFAULT_INPUT_VEIL)

    /** 从持久化参数灌入（App 启动时调用） */
    fun loadFrom(p: com.haoai.agent.data.GlassParams) {
        blur = p.blur
        lensHeight = p.lensHeight
        lensAmountMul = p.lensAmountMul
        veil = p.veil
        corner = p.corner
        lensFull = p.lensFull
        ca = p.ca
        barBlur = p.barBlur
        barVeil = p.barVeil
        inputBlur = p.inputBlur
        inputVeil = p.inputVeil
    }

    /** 当前值快照为持久化参数（调参页防抖落盘用） */
    fun snapshot() = com.haoai.agent.data.GlassParams(
        blur = blur,
        lensHeight = lensHeight,
        lensAmountMul = lensAmountMul,
        veil = veil,
        corner = corner,
        lensFull = lensFull,
        ca = ca,
        barBlur = barBlur,
        barVeil = barVeil,
        inputBlur = inputBlur,
        inputVeil = inputVeil
    )

    /** 一键还原出厂默认（"还原默认"按钮） */
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
        inputBlur = DEFAULT_INPUT_BLUR
        inputVeil = DEFAULT_INPUT_VEIL
    }
}
