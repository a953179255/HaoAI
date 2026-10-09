package com.haoai.agent.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.haoai.agent.ui.theme.HaoDimens
import com.haoai.agent.ui.theme.HaoTone
import com.haoai.agent.ui.theme.haoChipBg
import com.haoai.agent.ui.theme.haoDividerColor
import com.haoai.agent.ui.theme.haoRowPressColor
import com.haoai.agent.ui.theme.haoToneBadgeBg
import com.haoai.agent.ui.theme.haoToneInk
import com.kyant.backdrop.backdrops.LayerBackdrop

/**
 * 设置类页面的**统一列表语言**（设置首页与全部子页共用）。
 *
 * 设计规则（对应已确认的"目标 A"）：
 * - 底色只表达"是不是异常"：常态入口一律 [HaoTone.Accent] 徽标，只有需要用户处理才用
 *   [HaoTone.Warn]，破坏性操作才用 [HaoTone.Danger]。
 * - 状态不靠卡片底色表达，改用行尾的 [HaoChip]（待处理 / 未接入 / 已就绪…）。
 * - 一"组"相关设置装进**同一块玻璃**（[HaoGroup]），组内行之间用半像素分隔线，
 *   不再每行各自一张卡（改造前 11 个入口 = 11 种底色，是"凌乱"的主要来源）。
 */

// ────────────────────────────── 分组 ──────────────────────────────

/**
 * 玻璃分组卡：圆角 [HaoDimens.groupRadius]，内含若干 [HaoRow]。
 *
 * 两个关键实现点（都是踩过的坑）：
 * 1. `floating = true` ⇒ 走库原生 highlight + shadow + innerShadow，**不再手画描边**
 *    （手画描边在玻璃上必然四边深浅不一，实测 186/188/225）。
 * 2. 内层 `Column` 自带一次 `clip(圆角)`：因为 floating 路径**跳过了外层裁剪**
 *    （否则库的外阴影会被裁光），行按压高亮会溢出圆角 ⇒ 由这次内层裁剪负责形状，
 *    行本身**不自带圆角**（"行通栏 + 高亮不自带 clip"铁律）。
 */
@Composable
fun HaoGroup(
    backdrop: LayerBackdrop,
    modifier: Modifier = Modifier,
    refract: Boolean? = null,
    /** 关闭折射（列表里卡片很多时可传 false 降开销；观感=磨砂玻璃） */
    lens: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    val onWp = com.haoai.agent.ui.theme.LocalOnWallpaper.current
    val tuning = com.haoai.agent.ui.theme.GlassTuning
    // 壁纸模式下圆角/折射/模糊/白雾全部走实时调参（主题外观页写入）
    val r = if (onWp) tuning.corner.dp else HaoDimens.groupRadius
    // 活形状提供者（2026-10-09 圆角残影四修）：lambda 内读 corner State，
    // 库 placeWithLayer 的 layerBlock 应用期调它 → 注册快照订阅 → 滑杆拖动
    // Compose 自动失效层属性取新圆角（绕开 updateLayerBlock 的引用相同短路）。
    val liveShape: (() -> androidx.compose.ui.graphics.Shape)? = if (onWp) {
        { androidx.compose.foundation.shape.RoundedCornerShape(tuning.corner.dp) }
    } else null
    GlassPanel(
        backdrop = backdrop,
        modifier = modifier.fillMaxWidth(),
        radius = r,
        shapeProvider = liveShape,
        // 页面组独立档（2026-10-09 方案A拆分）：白雾/磨砂走 pageVeil/pageBlur，
        // 聊天卡片不再被这里牵动
        surfaceAlpha = com.haoai.agent.ui.theme.haoPageCardSurfaceAlpha(),
        lensRadius = if (lens) tuning.lensHeight.dp else 0.dp,
        lensAmountMul = tuning.lensAmountMul,
        lensFull = tuning.lensFull && lens,
        chromaticAberration = lens,
        blurRadius = com.haoai.agent.ui.theme.haoGroupBlurRadius(),
        floating = true,
        refract = refract
    ) {
        Column(
            Modifier.clip(RoundedCornerShape(r)),
            content = content
        )
    }
}

/** 组内行分隔线：0.5dp 半像素，通栏（与 mockup 的 inset hairline 一致）。 */
@Composable
fun HaoDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(0.5.dp)
            .background(haoDividerColor())
    )
}

// ────────────────────────────── 行 ──────────────────────────────

/**
 * 设置行。三种形态由参数组合决定：
 * - 导航行：`onClick != null`（自动带 chevron + 按压高亮）
 * - 开关行 / 选择行：`onClick == null`，把控件放进 [trailing]
 * - 说明行：`icon == null && trailing == null`
 *
 * @param divider 是否在本行**上方**画分隔线（组内第 2 行起传 true）
 */
@Composable
fun HaoRow(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    tone: HaoTone = HaoTone.Accent,
    /** 图标徽标的装饰色序号（HaoIconPalette）；-1 = 跟随 tone。设置页各入口的颜色区分用 */
    tintIndex: Int = -1,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    showChevron: Boolean = false,
    enabled: Boolean = true,
    divider: Boolean = false,
    onClick: (() -> Unit)? = null,
    titleColor: Color? = null,
) {
    if (divider) HaoDivider()

    val interaction = remember { MutableInteractionSource() }
    val press = rememberPressFeedback(interaction)
    val clickMod = if (onClick != null && enabled) {
        Modifier.clickable(
            interactionSource = interaction,
            // 禁 ripple：玻璃件的按压语言是"整行染色 + 微缩放"，不用方形涟漪
            indication = null
        ) { press.wrap(onClick)() }
    } else Modifier

    Row(
        Modifier
            .fillMaxWidth()
            // 高亮通栏、不自带圆角：形状交给 HaoGroup 的内层 clip 统一裁（圆角铁律）
            .background(if (press.pressed && onClick != null && enabled) haoRowPressColor() else Color.Transparent)
            .then(clickMod)
            .padding(horizontal = HaoDimens.rowPaddingH, vertical = HaoDimens.rowPaddingV),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(HaoDimens.rowGap)
    ) {
        if (icon != null) {
            HaoBadge(icon = icon, tone = tone, enabled = enabled, tintIndex = tintIndex)
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium.copy(
                    // 纯玻璃配方白雾只有 0.15，深色文字需要 1px 浅色晕才能从花纹里浮出来
                    shadow = androidx.compose.ui.graphics.Shadow(
                        color = Color.White.copy(alpha = 0.65f),
                        offset = androidx.compose.ui.geometry.Offset(0f, 1f),
                        blurRadius = 3f
                    )
                ),
                fontWeight = FontWeight.Medium,
                color = titleColor ?: MaterialTheme.colorScheme.onSurface.copy(
                    alpha = if (enabled) 1f else 0.45f
                )
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall.copy(
                        // 纯玻璃配方（白雾 0.24）下灰字压在最花的区域只有 ~1.4:1 ——
                        // 加深到 onSurface + 浅色晕才读得了
                        shadow = androidx.compose.ui.graphics.Shadow(
                            color = Color.White.copy(alpha = 0.6f),
                            offset = androidx.compose.ui.geometry.Offset(0f, 1f),
                            blurRadius = 3f
                        )
                    ),
                    color = MaterialTheme.colorScheme.onSurface.copy(
                        alpha = if (enabled) 0.78f else 0.4f
                    ),
                    maxLines = 1,
                    // 长路径（如工作空间 /storage/emulated/0/Android/data/…）要省略号收尾，
                    // 默认 Clip 会在字中间硬切，看着像渲染坏了
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }
        }
        trailing?.invoke(this)
        if (showChevron) {
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/** 图标徽标：统一尺寸/圆角，底色由 [tone] 决定（这是"底色只表达异常"的落点）。 */
@Composable
fun HaoBadge(
    icon: ImageVector,
    tone: HaoTone = HaoTone.Accent,
    enabled: Boolean = true,
    /** 装饰色序号（HaoIconPalette）——设置页各入口的颜色区分用；≥0 时优先于 tone */
    tintIndex: Int = -1,
    contentDescription: String? = null
) {
    Box(
        Modifier
            .size(HaoDimens.badgeSize)
            .background(
                if (tintIndex >= 0) com.haoai.agent.ui.theme.haoIconBadgeBg(tintIndex)
                else haoToneBadgeBg(tone),
                RoundedCornerShape(HaoDimens.badgeRadius)
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = if (tintIndex >= 0) com.haoai.agent.ui.theme.haoIconTint(tintIndex).copy(alpha = if (enabled) 1f else 0.5f)
            else haoToneInk(tone).copy(alpha = if (enabled) 1f else 0.5f),
            modifier = Modifier.size(HaoDimens.badgeIcon)
        )
    }
}

// ────────────────────────────── 状态胶囊 ──────────────────────────────

/**
 * 行尾状态胶囊。**只表达"要不要你处理"**，不要拿它做分类色。
 *
 * 用法对照：正常/已完成 → [HaoTone.Accent]；需要处理 → [HaoTone.Warn]；
 * 未接入/未初始化/中性信息 → [HaoTone.Neutral]；失败 → [HaoTone.Danger]。
 */
@Composable
fun HaoChip(text: String, tone: HaoTone = HaoTone.Neutral) {
    Box(
        Modifier
            .background(haoChipBg(tone), CircleShape)
            .padding(
                horizontal = HaoDimens.chipPaddingH,
                vertical = HaoDimens.chipPaddingV
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = haoToneInk(tone),
            maxLines = 1
        )
    }
}

// ────────────────────────────── 段落标题 ──────────────────────────────

/**
 * 子页内的段落标题（如"云端服务 / 端侧模型 / 采样参数"）。
 * 左对齐到分组卡边缘（[HaoDimens.pagePaddingH] 之外的 0），带一根 3dp 强调竖条。
 *
 * ⚠️ 2026-10-09 方案A：墨色走 [adaptiveOnSurface]（壁纸深浅优先），不再用
 * 主题 onSurface——浅色主题的黑字压深色壁纸几乎不可见（用户实测"看不清"）；
 * 首页分组标签 [HaoGroupLabel] 也统一进这套"竖条+裸字自适应"语言。
 */
@Composable
fun HaoSectionTitle(text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(top = 10.dp, bottom = 6.dp)
    ) {
        Box(
            Modifier
                .size(width = 3.dp, height = 13.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape)
        )
        Text(
            text,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = com.haoai.agent.ui.theme.adaptiveOnSurface()
        )
    }
}

/** 分组卡内的说明段落（长文本，左内边距与行内容对齐）。 */
@Composable
fun HaoHint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(
            start = HaoDimens.rowPaddingH,
            end = HaoDimens.rowPaddingH,
            top = 2.dp,
            bottom = 10.dp
        )
    )
}

/** 行内小间距（徽标与文字之间等场景复用）。 */
@Composable
fun HaoGap(width: androidx.compose.ui.unit.Dp = HaoDimens.rowGap) {
    Spacer(Modifier.width(width))
}

// ────────────────────────────── 开关 ──────────────────────────────

/**
 * 设置项开关（2026-10-08 起 = [LiquidToggle] 的门面，全项目统一液态玻璃）。
 *
 * 历史：曾是扁平规范件，因 LiquidToggle 关闭态白雾压白卡看不见而弃用；
 * 现在关闭态雾色已改 onSurface 规范（见 Glass.kt drawCapsule 注释），白压白根因
 * 已修，全 App 开关统一换回液态款：52×32、镜面球拇指、拖拽跟手过半提交、
 * 按压鼓起 + 指尖光斑、CLOCK_TICK 触感。
 *
 * [backdrop] 给页面采样源则轨道折射壁纸；null（或内容层 LocalGlassRefract=false）
 * 自动退化本地磨砂，不会自引用崩溃。原 44×26 平面规范的"开=primary、关=onSurface
 * 18%/22%"配色由 LiquidToggle 内部保持一致。
 */
@Composable
fun HaoSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    backdrop: com.kyant.backdrop.backdrops.LayerBackdrop? = null
) {
    LiquidToggle(
        checked = checked,
        onCheckedChange = onCheckedChange,
        backdrop = backdrop,
        modifier = modifier,
        enabled = enabled
    )
}

// ────────────────────────────── 空状态 ──────────────────────────────

/**
 * 空状态卡：**不要再写"一段裸文案挂在页面上"**。
 *
 * 改前 MCP/技能库/定时任务/工作流的空状态各是一段 `Text(padding(24.dp))`，
 * 没有卡片、没有图标、字距样样不同 —— 同一个 App 里"设置页有卡、管理页没卡"
 * 也是两套语言。统一为：玻璃卡 + 强调色徽标 + 标题 + 一句怎么开始的提示。
 */
@Composable
fun HaoEmptyState(
    backdrop: LayerBackdrop,
    icon: ImageVector,
    title: String,
    hint: String? = null,
    action: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    HaoGroup(backdrop = backdrop, modifier = modifier) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            HaoBadge(icon = icon, tone = HaoTone.Accent)
            Spacer(Modifier.height(12.dp))
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            if (hint != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    hint,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
            if (action != null) {
                Spacer(Modifier.height(16.dp))
                action()
            }
        }
    }
}

// ────────────────────────────── 页面说明条 ──────────────────────────────

/**
 * 页面级说明条：**必须画在玻璃上，不要裸放在壁纸上**。
 *
 * 小号灰字（`labelSmall` + `onSurfaceVariant`）压在带花纹的壁纸/照片上会直接失去可读性——
 * 实测"关于页的说明段""用量页的输入/输出行""MCP 页开头的接入说明"三处最明显。
 *
 * ⚠️ 曾经的错误解法是给整页加一层白色遮罩来"提亮背景"，代价是整张壁纸被蒙白
 * （用户实测反馈"太白了、看不清壁纸"）。**正解是把需要的文字放进玻璃**，
 * 而不是把整个页面压白。
 */
@Composable
fun HaoNote(
    backdrop: LayerBackdrop,
    text: String,
    modifier: Modifier = Modifier
) {
    HaoGroup(backdrop = backdrop, modifier = modifier) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(
                horizontal = HaoDimens.rowPaddingH,
                vertical = 11.dp
            )
        )
    }
}

/**
 * 组间小标题（设置首页的分块标签）。
 *
 * ⚠️ 2026-10-09 方案A：与 [HaoSectionTitle] 统一成"竖条 + 裸字自适应"一种
 * 语言——原先垫一块 60% 玻璃底（用户实测"背景框太丑"），且首页带框、子页裸字
 * 是同一层级的两套表达。字色 [adaptiveOnSurface] 跟壁纸走，深壁纸也读得了，
 * 不再需要玻璃底垫可读性。字号保持 labelSmall（首页标签比子页段标题轻一档）。
 */
@Composable
fun HaoGroupLabel(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier.padding(bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            Modifier
                .size(width = 3.dp, height = 13.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape)
        )
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = com.haoai.agent.ui.theme.adaptiveOnSurface()
        )
    }
}
