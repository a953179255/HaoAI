package com.haoai.agent.ui.chat

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.haoai.agent.agent.tools.TodoItem
import com.haoai.agent.ui.common.GlassPanel
import com.kyant.backdrop.backdrops.LayerBackdrop

/**
 * 「进行中」徽标的专用暖色。
 *
 * 为什么不用 `colorScheme.tertiary`：本应用主题的 tertiary 是 0xFF256B41（深绿），与 primary
 * （0xFF1EA84F）同色系 —— 徽标就失去了"颜色冗余强化"（只剩形状差异）。这里取与聊天区既有暖色
 * 语汇（STOPPED/DENIED 用 0xFFFFC46B、上下文用量用 0xFFFF6D00）同一族、且对白底对比度
 * ≈4.3:1（非文本 ≥3:1 达标）的琥珀色。深浅主题下都能用（叠在玻璃面上仍可辨）。
 */
private val DoingAmber = androidx.compose.ui.graphics.Color(0xFFBA7517)

/** 方案 A 统一缓动：easeOutQuint 近似（一镜到底、尾段轻落）。 */
private val MorphEase = androidx.compose.animation.core.CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

/**
 * 任务悬浮件（方案 A + E，用户选型 2026-09）：
 * 胶囊 ⇄ 面板是同一颗玻璃，宽/高/圆角全由 morph 单一进度源插值（420ms easeOutQuint）——
 * 高度 = 34dp 头部 + 清单固有高 × ease(morph)，中间帧边界四角恒定圆角；
 * 清单跟随玻璃高度被裁剪显露，无独立动画源。
 * 收起态：呼吸点 + 进行中任务名（截断）+ N/M + 圆钮，**胶囊宽度固定两档**
 * （进行中 200dp / 空闲 150dp）——原跟任务名浮动会让形变起点每换一步跳一次
 * （2026-09-20 用户选型 B：固定宽度 + 名字截断）。
 * 展开态交叉淡入：「任务」+ N/M + **分段刻度进度条**（每段 = 1 个任务，天然表达 N/M；
 * total > 8 时字段过窄，退化为等比条 + 3 条刻度 + 填充端圆点）。
 * 两态都只有右侧 LiquidGlassButton 触发切换。
 * 固有尺寸测量五坑（详见 memory）：测量一律外置玻璃外独立隐形容器（alpha=0
 * 保持自然布局），玻璃 fixed 约束会把内部子项测量 coerce（宽度自锁/高度压扁死锁）。
 * 细线/直角阴影修复：lensRadius 压到 13dp（折射环宽 ≤ 圆角半径）；surfaceAlpha 0.50。
 */
@Composable
fun TaskFloat(
    items: List<TodoItem>,
    expanded: Boolean,
    onToggle: () -> Unit,
    backdrop: LayerBackdrop,
    modifier: Modifier = Modifier
) {
    val doneCount = items.count { it.status == "completed" }
    val total = items.size
    val progress = if (total > 0) doneCount.toFloat() / total else 0f
    val activeTask = items.firstOrNull { it.status == "in_progress" }
    val hasPending = items.any { it.status != "completed" && it.status != "cancelled" }

    // 形变主进度：0=胶囊 1=面板（单一真源驱动宽/圆角/图标旋转/内容交叉淡入）
    val morph by animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        animationSpec = tween(420, easing = MorphEase),
        label = "taskFloatMorph"
    )

    // P0-3：呼吸脉冲按需组合——只有收起态胶囊点、展开态的进行中行内点需要它。
    // rememberPulse 是 delay(33) 驱动，只要在组合里就每 33ms 写一次状态（旧
    // infiniteTransition 等帧时钟、会随页面静止一起停摆，delay 不会）——
    // 展开+无进行中任务的挂机页曾被它钉在常驻 30fps（真机实测 447帧/15s）。
    val breath by com.haoai.agent.ui.common.rememberPulse(
        0.35f, 1f, 1800, reverse = true,
        enabled = !expanded || activeTask != null
    )

    val density = androidx.compose.ui.platform.LocalDensity.current
    // 清单固有高（方案 E：morph 单一进度源插值高度的目标基准）
    var listPx by remember { mutableFloatStateOf(0f) }

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            // bottom = 22dp 是**给库的外阴影留出余量**：本组件外面套着
            // AnimatedVisibility(expandVertically/shrinkVertically)，它默认 clip = true，
            // 裁剪边界 = 内容边界。原先玻璃几乎撑满容器（左右仅 12dp、上下 4dp/0dp），
            // 外阴影被整条吃掉（实测面板下缘外侧亮度 253.9 → 253.9，零投影）；
            // 上下文面板因为容器横向有富余，阴影才看得见。留 22dp 后阴影能自然溢出。
            .padding(top = 4.dp, end = 12.dp, start = 12.dp, bottom = 22.dp),
        contentAlignment = Alignment.TopEnd
    ) {
        val fullPx = with(density) { maxWidth.toPx() }
        // 收起态胶囊宽度：**内容实测**（2026-10-01 用户反馈：固定两档在"无进行中任务"
        // 档位右侧留一大块空白）。隐形测量容器量头部内容自然宽（点 + 任务名(≤130dp
        // 截断) + N/M + 左右内距），宽度随内容变化；锚点 TopEnd 右缘钉住，左缘伸缩。
        // 任务名每换一步左缘会跳一次——固定两档当年就是为治这个，用户裁决贴合优先。
        // 未量到首帧（pillContentPx=0）退回旧两档兜底。
        var pillContentPx by remember { mutableFloatStateOf(0f) }
        Row(
            Modifier
                .alpha(0f)
                .wrapContentSize(Alignment.TopStart, unbounded = true)
                .onSizeChanged { if (it.width > 0) pillContentPx = it.width.toFloat() }
                .padding(start = 12.dp, end = 38.dp)
        ) {
            if (hasPending) {
                Box(Modifier.size(7.dp))
                Spacer(Modifier.width(7.dp))
            }
            Text(
                activeTask?.text ?: "任务",
                style = MaterialTheme.typography.labelMedium,
                fontSize = 11.5.sp,
                maxLines = 1,
                modifier = Modifier.widthIn(max = 130.dp)
            )
            Spacer(Modifier.width(7.dp))
            Text(
                "$doneCount/$total",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold
            )
        }
        val measuredPillPx = if (pillContentPx > 0f) {
            with(density) { (pillContentPx + 2f).toDp() }   // +2px 抗锯齿余量
        } else (if (hasPending) 200.dp else 150.dp)
        val pillPx = with(density) {
            measuredPillPx.toPx().coerceAtMost(fullPx - with(density) { 16.dp.toPx() })
        }
        val wPx = pillPx + (fullPx - pillPx) * morph
        val corner = androidx.compose.ui.unit.lerp(17.dp, 15.dp, morph)
        // 方案 E：高度 = 头部 34dp + 清单高 × morphEase(morph)，单一进度源驱动，
        // 圆角全程恒定——中间帧四角全圆（双动画叠加时底角会被拉直，已翻车）
        val headerPx = with(density) { 34.dp.toPx() }
        val targetListPx = if (listPx <= 0f) 0f else listPx
        val revealEase = MorphEase.transform(morph)
        val hPx = headerPx + targetListPx * revealEase

        // 隐形测量区（alpha=0 不渲染但保持自然布局）：玻璃外量**清单固有高**。
        // （胶囊宽度 2026-10-01 起也改内容实测，量法同款；玻璃内测量五坑的注意事项
        //  仍适用于下面这个高度测量容器）
        // 清单固有高测量：独立容器 + wrapContentSize 钉在右上（不占布局空间），
        // 宽度给足（BoxWithConstraints 全宽）保证行内文本不折行、行高真实
        Column(
            Modifier
                .alpha(0f)
                .wrapContentSize(Alignment.TopStart, unbounded = true)
                .width(with(density) { fullPx.toDp() })
                .onSizeChanged { if (it.height > 0) listPx = it.height.toFloat() }
                .padding(bottom = 6.dp)
        ) {
            items.forEachIndexed { index, item ->
                TaskItemRow(
                    item = item,
                    isFirst = index == 0,
                    isLast = index == items.lastIndex,
                    prevDone = index > 0 && items[index - 1].status == "completed",
                    breath = breath
                )
            }
        }

        GlassPanel(
            backdrop = backdrop,
            radius = corner,
            lensRadius = 13.dp,
            // 0.50 → 0.58：与上下文面板统一材质（浅色主题下视觉差极小，价值在一致性）
            surfaceAlpha = 0.58f,
            // 浮层强化：分层交给库原生 highlight + shadow + innerShadow
            // （原先手画描边在玻璃上四边深浅不一：左 186 / 右 188 / 下 225）
            floating = true,
            modifier = Modifier
                .width(with(density) { wPx.toDp() })
                .height(with(density) { hPx.toDp() })
        ) {
            Column {
                // ── 头部：两版内容按形变进度交叉淡入。两态都只有右侧圆钮触发切换
                // （整行可点会让圆钮形同虚设，用户反馈 2026-09-10；胶囊态同样仅圆钮）。
                // indication=null 禁 ripple：玻璃件按压语言统一为形变/辉光，不出方形涟漪
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(34.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    // 收起版：● 进行中任务名 · N/M（纯显示）。胶囊宽度固定两档（见 wPx）；
                    // 名字用 weight(fill = false) 吃满可用宽后省略号截断——不再写死 130dp 上限，
                    // 名字再长也撑不破胶囊。右距 38dp = 圆钮 26dp + 4dp 边距 + 8dp 间隙
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 12.dp, end = 38.dp)
                            .alpha(1f - morph),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (hasPending) {
                            Box(
                                Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = breath))
                            )
                            Spacer(Modifier.width(7.dp))
                        }
                        Text(
                            activeTask?.text ?: "任务",
                            style = MaterialTheme.typography.labelMedium,
                            fontSize = 11.5.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Spacer(Modifier.width(7.dp))
                        Text(
                            "$doneCount/$total",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    // 展开版：「任务」+ N/M + 分段刻度进度条（2026-09-20 定稿 AD 整合版）。
                    // 每段 = 1 个任务：等比条撑满时"左半绿、右半空"，长且单调，也读不出共几项；
                    // 分段后长度有了节拍，且天然表达 N/M。total > 8 时段过窄，退化见下。
                    // 右距 38dp 为圆钮让位（26dp 圆钮 + 4dp 边距 + 8dp 间隙），进度段不得越过。
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 12.dp, end = 38.dp)
                            .alpha(morph),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "任务",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "$doneCount/$total",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
                        )
                        Spacer(Modifier.width(10.dp))
                        if (total in 1..8) {
                            // 正常档：分段刻度条，每段 = 1 个任务（段数即项数）
                            Row(
                                Modifier.weight(1f),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                items.forEach { seg ->
                                    val segColor = when (seg.status) {
                                        "completed" -> MaterialTheme.colorScheme.primary
                                        "in_progress" ->
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                                        "cancelled" ->
                                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                                        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                                    }
                                    Box(
                                        Modifier
                                            .weight(1f)
                                            .height(6.dp)
                                            .clip(RoundedCornerShape(3.dp))
                                            .background(segColor)
                                            .then(
                                                if (seg.status == "in_progress")
                                                    Modifier.border(
                                                        1.dp,
                                                        MaterialTheme.colorScheme.primary,
                                                        RoundedCornerShape(3.dp)
                                                    )
                                                else Modifier
                                            )
                                    )
                                }
                            }
                        } else {
                            // 退化档（> 8 项，段会窄到不可辨）：等比条 + 3 条刻度 + 填充端圆点
                            val fbTrack = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                            val fbFill = MaterialTheme.colorScheme.primary
                            val fbTick = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)
                            Canvas(
                                Modifier
                                    .weight(1f)
                                    .height(8.dp)
                            ) {
                                val cy = size.height / 2f
                                val barH = 4.dp.toPx()
                                val rr = CornerRadius(barH / 2f, barH / 2f)
                                drawRoundRect(
                                    color = fbTrack,
                                    topLeft = Offset(0f, cy - barH / 2f),
                                    size = Size(size.width, barH),
                                    cornerRadius = rr
                                )
                                val fillW = size.width * progress
                                if (fillW > 0f) {
                                    drawRoundRect(
                                        color = fbFill,
                                        topLeft = Offset(0f, cy - barH / 2f),
                                        size = Size(fillW, barH),
                                        cornerRadius = rr
                                    )
                                }
                                listOf(0.25f, 0.5f, 0.75f).forEach { f ->
                                    val x = size.width * f
                                    drawLine(
                                        fbTick,
                                        Offset(x, cy - 4.dp.toPx()),
                                        Offset(x, cy + 4.dp.toPx()),
                                        1.dp.toPx()
                                    )
                                }
                                if (fillW > 0f) {
                                    drawCircle(fbFill, 3.5.dp.toPx(), Offset(fillW, cy))
                                }
                            }
                        }
                    }
                    //  chevron：位置随宽度滑到右缘，旋转由 morph 驱动（0°=⌃收起 / 180°=⌄展开）。
                    //  磨砂降级（refract=false）：真折射会隔着胶囊采到身后高饱和内容
                    //  （如绿色消息泡），比磨砂后的胶囊表面深一大截、喧宾夺主；
                    //  26dp（r13）与胶囊端帽 r17 同心（右距 4dp = 17-13），弧度吻合
                    com.haoai.agent.ui.common.LiquidGlassButton(
                        onClick = onToggle,
                        backdrop = backdrop,
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 4.dp)
                            .size(26.dp),
                        shape = CircleShape,
                        refract = false,
                        surfaceColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
                    ) {
                        Icon(
                            Icons.Filled.ExpandLess,
                            contentDescription = if (expanded) "收起任务" else "展开任务",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .size(14.dp)
                                .graphicsLayer { rotationZ = 180f - morph * 180f }
                        )
                    }
                }
                // ── 清单（方案 E）：纯显示，固有高由玻璃外隐形区量得（listPx），
                // 玻璃高度 morph 插值裁到哪露到哪——无独立动画源，边界形状全程受控。
                // 完全收起后移出组合；淡入与高度生长同步（reveal 驱动）。
                if (expanded || morph > 0.01f) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .alpha(((morph - 0.3f) / 0.7f).coerceIn(0f, 1f))
                            .padding(bottom = 6.dp)
                    ) {
                        items.forEachIndexed { index, item ->
                            TaskItemRow(
                                item = item,
                                isFirst = index == 0,
                                isLast = index == items.lastIndex,
                                prevDone = index > 0 && items[index - 1].status == "completed",
                                breath = breath
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 清单行（2026-09-20 定稿：AD 整合版 + 微变体 1）：
 *
 * - **废除整行 alpha 0.45**：它与文字自身 alpha 相乘（0.45 × 0.5 = 0.22），把完成项文字
 *   的实际对比度压到 ≈1.6:1（正文要求 ≥4.5:1）——信息被"抹掉"而不是"弱化"。改为**文字单层
 *   alpha**：完成/取消 0.62、待办 0.88、进行中 1.0（实测口径 ≈4.8:1 / ≈11:1 / 最高）；
 * - **左侧 16dp 节点列**：时间轴轨道（上/下段）+ 几何状态徽标，整体读作一条进度轨道；
 *   段色由相邻节点状态决定（上一节点已完成 → primary，否则 onSurface 12%）；首行不画上段、
 *   末行不画下段，天然收口；
 * - **轨道画在行内**：`drawBehind` 置于 `padding` 之前 ⇒ 画布覆盖含 padding 的整行高度，
 *   相邻行的段首尾相接；**行高不变** ⇒ 玻璃外那个隐形高度测量无需改动（形变零风险）。
 *   上段止于徽标上缘、下段起于徽标下缘 —— 徽标直径即缺口，空心徽标也无需底色遮罩；
 * - 微变体 1：进行中行加 primary 8% 圆角底 + 文字半粗体（一眼抓到"正在做的那件"）。
 */
@Composable
private fun TaskItemRow(
    item: TodoItem,
    isFirst: Boolean,
    isLast: Boolean,
    /** 上一个节点是否已完成（决定本行"上段"轨道的颜色） */
    prevDone: Boolean,
    /** 呼吸值：复用胶囊呼吸点的既有动画源，不新增动画源 */
    breath: Float
) {
    val isDone = item.status == "completed"
    val isCancelled = item.status == "cancelled"
    val isDoing = item.status == "in_progress"

    val railDone = MaterialTheme.colorScheme.primary
    val railTodo = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    val badgeHalf = 8.dp
    val rowTint = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)

    Box(
        Modifier
            .fillMaxWidth()
            .then(
                if (isDoing) {
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(rowTint)
                } else Modifier
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .drawBehind {
                    val cx = 14.dp.toPx() + badgeHalf.toPx()
                    val cy = size.height / 2f
                    val r = badgeHalf.toPx()
                    val stroke = 2.dp.toPx()
                    if (!isFirst) {
                        drawLine(
                            if (prevDone) railDone else railTodo,
                            Offset(cx, 0f), Offset(cx, cy - r), stroke, StrokeCap.Round
                        )
                    }
                    if (!isLast) {
                        drawLine(
                            if (isDone) railDone else railTodo,
                            Offset(cx, cy + r), Offset(cx, size.height), stroke, StrokeCap.Round
                        )
                    }
                }
                .padding(start = 14.dp, end = 12.dp, top = 5.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TaskStatusIcon(status = item.status, priority = item.priority, breath = breath)
            Spacer(Modifier.width(12.dp))
            Text(
                item.text,
                style = MaterialTheme.typography.bodySmall,
                fontSize = if (isDoing) 13.5.sp else 13.sp,
                fontWeight = if (isDoing) FontWeight.SemiBold else FontWeight.Normal,
                color = when {
                    isDone || isCancelled ->
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f)
                    isDoing -> MaterialTheme.colorScheme.onSurface
                    else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.88f)
                },
                textDecoration = if (isDone || isCancelled) TextDecoration.LineThrough else null,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/**
 * 状态徽标（2026-09-20 定稿）：**几何区分四态**，颜色只做冗余强化 ——
 * 灰度或色觉障碍下仍可辨（WCAG 1.4.1）。直径固定 16dp，与轨道端点对齐
 * （轨道停在徽标边缘，所以空心徽标不必铺底色遮罩）。
 * 完成 = 实心圆 + 白勾；进行中 = 2dp 琥珀环 + 内点（脉冲，复用 breath）；
 * 取消 = 环 + 斜杠；待办 = 1.5dp 空环（高优先用 error 色）。
 */
@Composable
private fun TaskStatusIcon(status: String, priority: String, breath: Float) {
    val badgeSize = 16.dp
    val primary = MaterialTheme.colorScheme.primary
    val onVar = MaterialTheme.colorScheme.onSurfaceVariant
    val error = MaterialTheme.colorScheme.error
    Canvas(Modifier.size(badgeSize)) {
        val w = size.width
        when (status) {
            "completed" -> {
                drawCircle(primary, radius = w / 2f)
                val tick = 1.8.dp.toPx()
                drawLine(
                    Color.White,
                    Offset(w * 0.28f, w * 0.52f), Offset(w * 0.44f, w * 0.68f), tick, StrokeCap.Round
                )
                drawLine(
                    Color.White,
                    Offset(w * 0.44f, w * 0.68f), Offset(w * 0.72f, w * 0.34f), tick, StrokeCap.Round
                )
            }
            "in_progress" -> {
                drawCircle(DoingAmber, radius = w / 2f - 1.dp.toPx(), style = Stroke(2.dp.toPx()))
                drawCircle(DoingAmber.copy(alpha = breath), radius = 2.5.dp.toPx())
            }
            "cancelled" -> {
                val c = onVar.copy(alpha = 0.55f)
                drawCircle(c, radius = w / 2f - 0.8.dp.toPx(), style = Stroke(1.5.dp.toPx()))
                drawLine(
                    c, Offset(w * 0.28f, w * 0.72f), Offset(w * 0.72f, w * 0.28f),
                    1.5.dp.toPx(), StrokeCap.Round
                )
            }
            else -> {
                val c = when (priority) {
                    "high" -> error.copy(alpha = 0.75f)
                    "low" -> onVar.copy(alpha = 0.4f)
                    else -> onVar.copy(alpha = 0.65f)
                }
                drawCircle(c, radius = w / 2f - 0.8.dp.toPx(), style = Stroke(1.5.dp.toPx()))
            }
        }
    }
}
