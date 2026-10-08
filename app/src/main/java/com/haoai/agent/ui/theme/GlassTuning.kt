package com.haoai.agent.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 玻璃质感**实时调参**：设置页「主题外观」写入，全项目读取。
 *
 * 为什么做成单例 State：滑杆拖动 → 这里变化 → 全 App 玻璃即时更新。
 * ⚠️ 读取通道（2026-10-08 冻结修复定案）：玻璃组件的 blur/lens 必须在
 * `drawBackdrop(effects = {...})` lambda **内部**直接读本单例——effects 跑在
 * 库节点的 observeReads 里，值变化 → onObservedReadsChanged → 重算 renderEffect，
 * 不依赖重组（组合期读值传给参数只对能重组到的 GlassPanel 调用点有效，
 * LazyColumn 离屏缓存/跨页场景会滞留旧值——实测 pixel diff = 0）。
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

    /**
     * 弹层白雾映射：滑杆 veil（默认 0.48）按 [anchor]/0.48 等比放大到各弹层
     * 原有的档位锚点（对话框/消息操作 0.92、底部弹层 0.72、Popup 0.52）——
     * 滑杆停默认值时外观与硬编码时代零差别；下限 anchor×0.6 保可读
     * （弹层压在正文上，太透字会穿出来）。滑杆标着"影响：弹层"却一直没接，
     * 2026-10-08 用户实测发现后补上。
     */
    fun popupVeil(anchor: Float): Float = (veil / DEFAULT_VEIL * anchor).coerceIn(anchor * 0.6f, 0.98f)

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
