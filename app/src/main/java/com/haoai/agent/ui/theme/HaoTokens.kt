package com.haoai.agent.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp

/**
 * HaoAI 语义色板 —— 全项目**只允许**这五档色调。
 *
 * 为什么需要：改造前全项目 UI 层有 125 个硬编码色值，光是"绿"就有
 * `3FA37A / 7BD88F / 1EA84F / 00C853` 四种、"警示橙"有
 * `D9913F / B06A12 / FFC46B / FFD600 / FF6D00 / FF7A45` 六种。
 * 问题不在"某个颜色难看"，而是**同一角色有太多颜色** —— 这才是"凌乱"的根源。
 *
 * 规则（设置页与所有子页共用）：
 * - **Accent**：品牌绿，唯一强调色。常态入口、主操作、"正常"状态都用它。
 * - **Warn**：只有"需要你处理"才用（例：无障碍未启用、rootfs 损坏）。
 * - **Danger**：破坏性操作（删除/清空/重置）。
 * - **Neutral**：中性信息（未接入、未初始化）与次要图标。
 * - **Info**：极少用，仅明确表示"信息性提示"时。
 *
 * 每档提供两个变量：**墨色**（`ink`，用于图标与文字，保证对比度）
 * 与**底色**（`bg`，用于徽标/胶囊，低透明度）。
 */
enum class HaoTone { Accent, Warn, Danger, Neutral, Info }

/**
 * 当前页面是否**铺着壁纸**（"壁纸应用于所有页面"开启 + 确实设了壁纸）。
 *
 * 由 MainActivity 在 HaoTheme 外层提供一次；[HaoGroup] 与语义色函数据此
 * **把玻璃不透明度往上抬一档** —— 白 58% 的玻璃压在一张花壁纸上，花色会从
 * 卡片里透出来（真机实测：图案盖过分隔线、图标徽标被吃掉、几张卡"花色不齐"）。
 * 规则：**不透明度跟着"身后是什么"走** —— 素色底 0.58 / 壁纸上的正文卡 0.82。
 */
val LocalOnWallpaper = androidx.compose.runtime.staticCompositionLocalOf { false }

/** 深色主题判定（全项目统一口径：背景亮度 < 0.5）。 */
@Composable
fun haoIsDark(): Boolean =
    MaterialTheme.colorScheme.background.luminance() < 0.5f

/**
 * 色调**墨色**：用于图标、chip 文字、强调数字。
 * 浅色主题取更深的同色相（保证压在浅底上有对比度）；
 * 深色主题取更亮的同色相（压深底可读）。**不要**用色板原色当文字色。
 */
@Composable
fun haoToneInk(tone: HaoTone): Color {
    val dark = haoIsDark()
    return when (tone) {
        // accent：浅色直接用主题 primary（种子色板会按背景压明度），深色提亮
        HaoTone.Accent -> if (dark) Color(0xFF86EFAC) else MaterialTheme.colorScheme.primary
        HaoTone.Warn -> if (dark) Color(0xFFFFD9A0) else Color(0xFF8A5A12)
        HaoTone.Danger -> if (dark) Color(0xFFFFB4AC) else Color(0xFF8F1D18)
        HaoTone.Info -> if (dark) Color(0xFF9EC5FF) else Color(0xFF185FA5)
        HaoTone.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

/**
 * 色调**主色**：用于需要"实色"的地方（进度点、强调竖条、开关选中）。
 */
@Composable
fun haoToneMain(tone: HaoTone): Color {
    val dark = haoIsDark()
    return when (tone) {
        HaoTone.Accent -> MaterialTheme.colorScheme.primary
        HaoTone.Warn -> if (dark) Color(0xFFFFC46B) else Color(0xFFD9913F)
        HaoTone.Danger -> MaterialTheme.colorScheme.error
        HaoTone.Info -> if (dark) Color(0xFF7FB0FF) else Color(0xFF2C7BE5)
        HaoTone.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

/**
 * 色调**徽标底**（图标方块的底色）。尺寸见 [HaoDimens.badgeSize]。
 * 浅色 10~18%、深色 18~28%：深底上需要更高透明度才看得见。
 */
@Composable
fun haoToneBadgeBg(tone: HaoTone): Color {
    val dark = haoIsDark()
    val onWp = LocalOnWallpaper.current
    // 纯玻璃配方下白雾只有 0.15，徽标底再加强一档才不会被花纹吃掉
    val liquid = HAO_LIQUID_ON_WALLPAPER && onWp
    val a = when (tone) {
        HaoTone.Accent -> if (dark) 0.20f else if (liquid) 0.34f else if (onWp) 0.24f else 0.10f
        HaoTone.Warn -> if (dark) 0.24f else if (liquid) 0.42f else if (onWp) 0.32f else 0.16f
        HaoTone.Danger -> if (dark) 0.24f else if (liquid) 0.40f else if (onWp) 0.30f else 0.14f
        HaoTone.Info -> if (dark) 0.22f else if (liquid) 0.38f else if (onWp) 0.28f else 0.12f
        HaoTone.Neutral -> if (dark) 0.12f else if (liquid) 0.26f else if (onWp) 0.16f else 0.06f
    }
    return haoToneMain(tone).copy(alpha = a)
}

/**
 * 状态胶囊底色/字色的语义档位（不是每个色调都能当胶囊底色，
 * 胶囊只表达"需要你注意吗"这一件事）。
 */
@Composable
fun haoChipBg(tone: HaoTone): Color {
    val dark = haoIsDark()
    val onWp = LocalOnWallpaper.current
    val liquid = HAO_LIQUID_ON_WALLPAPER && onWp
    val a = when (tone) {
        HaoTone.Accent -> if (dark) 0.20f else if (liquid) 0.36f else if (onWp) 0.26f else 0.12f
        HaoTone.Warn -> if (dark) 0.26f else if (liquid) 0.44f else if (onWp) 0.34f else 0.18f
        HaoTone.Danger -> if (dark) 0.24f else if (liquid) 0.40f else if (onWp) 0.30f else 0.14f
        HaoTone.Info -> if (dark) 0.22f else if (liquid) 0.38f else if (onWp) 0.28f else 0.12f
        HaoTone.Neutral -> if (dark) 0.12f else if (liquid) 0.28f else if (onWp) 0.18f else 0.06f
    }
    return haoToneMain(tone).copy(alpha = a)
}

/** 行/卡的分隔线色（配合 0.5dp 高度使用）。 */
@Composable
fun haoDividerColor(): Color {
    val dark = haoIsDark()
    val onWp = LocalOnWallpaper.current
    // 分隔线在纯玻璃配方下要再加强一档（白雾只有 0.15）
    return MaterialTheme.colorScheme.onSurface.copy(
        alpha = if (dark) 0.14f
        else if (HAO_LIQUID_ON_WALLPAPER && onWp) 0.24f
        else if (onWp) 0.17f else 0.10f
    )
}

/**
 * 行的按压高亮色（禁 ripple 时的静止替代）。
 * 实测 0.05 时整行亮度只降 11.4/255 —— 能看出但偏弱，提到 0.07。
 * 仍然很克制：靠"整行轻微染色"表达，不用涟漪方框（玻璃件按压语言）。
 */
@Composable
fun haoRowPressColor(): Color {
    val dark = haoIsDark()
    val onWp = LocalOnWallpaper.current
    return MaterialTheme.colorScheme.onSurface.copy(
        alpha = if (dark) 0.11f else if (onWp) 0.11f else 0.07f
    )
}

/**
 * 尺寸规范：一处定义、全项目引用。改一处即可整体调齐。
 *
 * 注意 [groupRadius] 必须与 [HaoGroup] 内层裁剪用的一致 ——
 * 圆角不一致会导致"行的高亮与卡片轮廓对不上"（历史上连错三版的坑）。
 */
object HaoDimens {
    /** 分组玻璃卡圆角 */
    val groupRadius = 16.dp
    /** 图标徽标（32dp 方 + 10dp 圆角） */
    val badgeSize = 32.dp
    val badgeRadius = 10.dp
    /** 徽标内图标尺寸 */
    val badgeIcon = 17.dp
    /** 行内边距 */
    val rowPaddingH = 14.dp
    val rowPaddingV = 11.dp
    /** 行内元素间距 */
    val rowGap = 12.dp
    /**
     * 内容卡（分组卡 / 列表卡 / 段落卡）的表面不透明度。
     * **全项目内容卡统一用这一个值** —— 改前散落 0.16 / 0.20 / 0.22 / 0.24 / 0.30 / 0.58 六种，
     * 同一个 App 里卡片"白度"各不相同，这也是"凌乱"的来源之一。
     */
    const val cardSurfaceAlpha = 0.58f

    /** 小统计块（指标瓦片）比内容卡更透一档：与内容卡形成层级差，而不是同一种白。 */
    const val tileSurfaceAlpha = 0.34f

    /**
     * 壁纸上的内容卡不透明度（**默认方案**）：壁纸会把花色透过 58% 的玻璃，把卡片、
     * 分隔线、徽标全部"糊"在一起。82% 时卡片成为一块稳定的可读表面。
     */
    const val cardSurfaceAlphaOnWallpaper = 0.82f

    /**
     * **液态方案**（用户可选）：0.62 + 加厚模糊。
     *
     * 为什么单降不透明度会翻车：0.82 时卡片的 lens 折射被表面色盖住 82%，
     * 看起来就是磨砂玻璃；降到 0.58 能看见折射了，但壁纸花色又透出来（"太透"）。
     * 正解是把**背景模糊加厚**（5.3dp → 18dp）：壁纸在卡片里化成一团柔和的色雾，
     * 此时低不透明度也依然可读 —— 这才是 iOS 那套液态玻璃的做法。
     */
    const val cardSurfaceAlphaOnWallpaperLiquid = 0.48f
    val groupBlurRadiusOnWallpaperLiquid = 4.dp
    val groupLensRadiusOnWallpaper = 24.dp

    /** 壁纸上的统计瓦片（比内容卡仍透一档，保持层级差）。 */
    const val tileSurfaceAlphaOnWallpaper = 0.64f

    /**
     * 页面顶栏（返回 + 标题）的表面不透明度。
     * 顶栏是"锚点"：它压在壁纸最花的位置、又承载导航，**比内容卡更需要稳定感**，
     * 所以壁纸模式下要更实（否则标题和返回箭头淹没在花纹里 —— 用户实测反馈"太透明"）。
     */
    const val pageBarSurfaceAlpha = 0.30f
    // 液态顶栏：不透明度让位给模糊 —— 0.80 时折射全被盖住（用户实测"只是磨砂"）
    const val pageBarSurfaceAlphaOnWallpaper = 0.48f

    /** 顶栏模糊半径（C 配方下 8dp）。 */
    val pageBarBlurRadius = 12.dp
    val pageBarBlurRadiusOnWallpaper = 4.dp

    /** 顶栏折射环（之前 lensRadius=0，是"没折射"最重的地方）。 */
    val pageBarLensRadiusOnWallpaper = 20.dp

    /** 分组之间 / 分组与页边距 */
    val groupGap = 12.dp
    val pagePaddingH = 16.dp
    /** 状态胶囊 */
    val chipPaddingH = 9.dp
    val chipPaddingV = 3.dp
}

/**
 * 按钮档位（**全项目只此五级**，不要再就地取色）。
 *
 * ⚠️ 2026-09-21 用户实测后定案：**主 / 次 / 轻量三档的外观完全一致**
 * （主色实底 + 白字）—— 层级改由**排序与文案**表达，不用透明度差、不用彩色文字。
 * 之前 0.92 / 0.58 / 0.44 的透明度差加轻量档的绿字，在页面上读起来是
 * "三个按钮不一样"，而不是"有层级"。
 *
 * 所以这三档保留枚举名只是**语义标注**（调用处写清这是主操作还是次要操作），
 * 外观统一由 [haoButtonColors] 决定。真正有外观差异的是另外两档：
 * [Danger]（破坏性，红）与 [Warning]（回退性，琥珀）—— 用**色相**表达语义。
 *
 * 规则：破坏性操作一律 [Danger]（不要用品牌色）；回退性操作用 [Warning]。
 */
enum class HaoButtonLevel { Primary, Secondary, Tonal, Danger, Warning }

/**
 * 级 → (底色, 文字色)。底色一律低透明度叠在玻璃上，文字用"墨色"保证对比度。
 *
 * **壁纸上整档加强**：低透明度色块压在花壁纸上会被花纹吃掉 —— 实测"选择文件夹 (SAF)"
 * （次级 0.25）和"恢复默认目录"（警告 0.18）在壁纸页上**完全看不出是个按钮**。
 * 语义档位不变，只是把底色做"实"到能在花纹上立住；次级按钮在壁纸上改用白字（底已偏实）。
 */
@Composable
fun haoButtonColors(level: HaoButtonLevel): Pair<Color, Color> {
    val onWp = LocalOnWallpaper.current
    val primary = MaterialTheme.colorScheme.primary

    // 主 / 次 / 轻量：**同一种观感** —— 主色实底 + 白字（见 [HaoButtonLevel] 说明）。
    // 壁纸上再抬一档不透明度（低透明度色块压在花壁纸上会被花纹吃掉）。
    if (level == HaoButtonLevel.Primary ||
        level == HaoButtonLevel.Secondary ||
        level == HaoButtonLevel.Tonal
    ) {
        return primary.copy(alpha = if (onWp) 0.94f else 0.90f) to
            MaterialTheme.colorScheme.onPrimary
    }
    // Danger / Warning：换**色相**表达语义，材质与文字规则跟上面完全一致
    // （实底 + 白字）—— 改前是"浅色底 + 彩色字"，和动作按钮摆在一起又是一种不一致。
    if (level == HaoButtonLevel.Danger) {
        return haoToneMain(HaoTone.Danger).copy(alpha = if (onWp) 0.90f else 0.88f) to
            MaterialTheme.colorScheme.onError
    }
    // 琥珀主色偏浅、压不住白字，用"琥珀墨色"做底（对比 ≈6:1）
    return haoToneInk(HaoTone.Warn).copy(alpha = if (onWp) 0.94f else 0.90f) to Color.White
}

/**
 * 壁纸卡片是否走**液态方案**（低不透明度 + 加厚模糊）。
 *
 * 一处开关切换全局观感，方便对比两套：`true` = 液态玻璃（折射可见、更通透），
 * `false` = 磨砂实底（更稳、更"平"）。
 */
// 2026-09-21 用户实测：0.82+弱模糊 读起来是磨砂、没有折射 —— 液态方案 =
// 低不透明度 + 加厚模糊，折射露出来的同时依然可读
const val HAO_LIQUID_ON_WALLPAPER = true

/** 内容卡实际使用的表面不透明度：素色底 [HaoDimens.cardSurfaceAlpha] / 壁纸上更实。
 *  2026-10-09 起只管**聊天表面**（气泡旁胶囊/任务卡/收尾行）；设置/管理/抽屉的
 *  页面卡片走 [haoPageCardSurfaceAlpha]（独立档，用户要求拆开调）。 */
@Composable
fun haoCardSurfaceAlpha(): Float = when {
    !LocalOnWallpaper.current -> HaoDimens.cardSurfaceAlpha
    HAO_LIQUID_ON_WALLPAPER -> {
        val wpDark = LocalWallpaperDark.current
        val themeDark = haoIsDark()
        when {
            wpDark == null -> HaoDimens.cardSurfaceAlphaOnWallpaperLiquid
            wpDark != themeDark -> if (themeDark) 0.34f else 0.50f
            else -> GlassTuning.veil          // 主题外观页实时调
        }
    }
    else -> HaoDimens.cardSurfaceAlphaOnWallpaper
}

/**
 * **页面玻璃卡**（设置/管理/抽屉卡片、统计瓦片、备注条）的表面不透明度
 * （2026-10-09 方案A拆分）：壁纸上恒等于滑杆 pageVeil——不再藏"壁纸深浅≠
 * 主题深浅时写死 0.34/0.50"的分支（那条分支正是用户反馈"卡片白雾滑杆控制
 * 不了设置卡片"的直接原因：深壁纸+浅主题恰好命中写死值，滑杆整个失灵）。
 * 素色底沿用原卡片常数。
 */
@Composable
fun haoPageCardSurfaceAlpha(): Float = when {
    !LocalOnWallpaper.current -> HaoDimens.cardSurfaceAlpha
    else -> GlassTuning.pageVeil
}

/** 分组玻璃的背景模糊半径（页面组档，2026-10-09 拆分：原 GlassTuning.blur
 *  继续管聊天卡片/弹层，pageBlur 管设置/管理页卡片）。 */
@Composable
fun haoGroupBlurRadius(): androidx.compose.ui.unit.Dp =
    if (LocalOnWallpaper.current && HAO_LIQUID_ON_WALLPAPER) GlassTuning.pageBlur.dp
    else HaoDimens.groupRadius / 3f

/** 统计瓦片实际使用的表面不透明度（2026-10-09 并入页面档：瓦片都在页面组里，
 *  原先 0.64/0.34 两个写死值不吃任何滑杆）。 */
@Composable
fun haoTileSurfaceAlpha(): Float = haoPageCardSurfaceAlpha()

/** 页面顶栏实际使用的表面不透明度。 */
@Composable
fun haoPageBarSurfaceAlpha(): Float =
    if (LocalOnWallpaper.current) GlassTuning.barVeil
    else HaoDimens.pageBarSurfaceAlpha

/** 页面顶栏的背景模糊半径（壁纸上加厚，让穿过的内容化成色雾）。 */
@Composable
fun haoPageBarBlurRadius(): androidx.compose.ui.unit.Dp =
    if (LocalOnWallpaper.current) GlassTuning.barBlur.dp
    else HaoDimens.pageBarBlurRadius

/** 页面顶栏的折射环半径（0 = 无折射；C 配方下 16dp）。 */
@Composable
fun haoPageBarLensRadius(): androidx.compose.ui.unit.Dp =
    if (LocalOnWallpaper.current) GlassTuning.lensHeight.dp else 0.dp

/** 页面顶栏的折射强度倍数（统一值；此前 GlassPanel 默认 2 写死，2026-10-08 接入）。 */
@Composable
fun haoPageBarLensAmountMul(): Float = GlassTuning.lensAmountMul

// ── 全 App 统一玻璃配方访问器（2026-10-08 用户裁决：折射/模糊全局一致）──
// 素色底（非壁纸）上折射无物可弯，一律关；壁纸上走调参值。

/** 统一折射环带宽度（非壁纸=0 关闭）。 */
@Composable
fun haoGlassLensRadius(): androidx.compose.ui.unit.Dp =
    if (LocalOnWallpaper.current) GlassTuning.lensHeight.dp else 0.dp

/** 统一折射强度倍数。 */
@Composable
fun haoGlassLensAmountMul(): Float = GlassTuning.lensAmountMul

/** 统一背景模糊（非壁纸回退 radius/3 的既有约定由调用方处理）。 */
@Composable
fun haoGlassBlurRadius(): androidx.compose.ui.unit.Dp = GlassTuning.blur.dp

/** 输入框表面不透明度：与卡片同构，但壁纸上调参分支走 inputVeil（2026-10-08 独立）。 */
@Composable
fun haoInputSurfaceAlpha(): Float = when {
    !LocalOnWallpaper.current -> HaoDimens.cardSurfaceAlpha
    HAO_LIQUID_ON_WALLPAPER -> {
        val wpDark = LocalWallpaperDark.current
        val themeDark = haoIsDark()
        when {
            wpDark == null -> HaoDimens.cardSurfaceAlphaOnWallpaperLiquid
            wpDark != themeDark -> if (themeDark) 0.34f else 0.50f
            else -> GlassTuning.inputVeil
        }
    }
    else -> HaoDimens.cardSurfaceAlphaOnWallpaper
}

/** 统一色差开关。 */
@Composable
fun haoGlassCa(): Boolean = GlassTuning.ca

// ────────────────────────────── 图标徽标的彩色色板 ──────────────────────────────

/**
 * 设置页图标的**装饰色板**（仅用于图标徽标，不改卡片底色/语义）。
 * 用户实测：全部用强调色绿会单调，希望各入口有颜色区分 —— 但与"底色只表达异常"
 * 的规则并存：**语义色（Warn/Danger）仍然优先**，装饰色只用于常态入口的图标。
 * 每色给浅色主题的深色版与深色主题的亮色版。
 */
val HaoIconPalette = listOf(
    Color(0xFF1EA84F) to Color(0xFF7BE3A0),  // 绿（品牌）
    Color(0xFF2C7BE5) to Color(0xFF8CC0FF),  // 蓝
    Color(0xFF7C5CE0) to Color(0xFFC3B4FF),  // 紫
    Color(0xFFE0893C) to Color(0xFFFFC98A),  // 橙
    Color(0xFF18A99C) to Color(0xFF8FE3DF),  // 青
    Color(0xFFE0559E) to Color(0xFFFFA8D2),  // 粉
)

/** 按序号取装饰色：light=深色版（浅底上作图标/深色底上提亮）。 */
@Composable
fun haoIconTint(index: Int): Color {
    val (light, dark) = HaoIconPalette[index % HaoIconPalette.size]
    return if (haoIsDark()) dark else light
}

/** 按序号取装饰徽标底色（低透明度）。 */
@Composable
fun haoIconBadgeBg(index: Int): Color {
    val (light, dark) = HaoIconPalette[index % HaoIconPalette.size]
    val c = if (haoIsDark()) dark else light
    return c.copy(alpha = if (haoIsDark()) 0.30f else 0.14f)
}
