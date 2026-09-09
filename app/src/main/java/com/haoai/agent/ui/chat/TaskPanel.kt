package com.haoai.agent.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.haoai.agent.agent.tools.TodoItem
import com.haoai.agent.ui.common.GlassPanel
import com.kyant.backdrop.backdrops.LayerBackdrop

/** 方案 A 统一缓动：easeOutQuint 近似（一镜到底、尾段轻落）。 */
private val MorphEase = androidx.compose.animation.core.CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

/**
 * 任务悬浮件（方案 A · 形状连续形变，用户选型 2026-09）：
 * 胶囊 ⇄ 面板是同一颗玻璃——宽度从胶囊固有宽插值到全宽、圆角/折射半径跟随，
 * 高度由清单 AnimatedVisibility 生长（animateContentSize 平滑），420ms easeOutQuint。
 * 收起态内容：呼吸点 + 进行中任务名（截断）+ N/M + ⌄；展开态内容交叉淡入：
 * 「任务」+ N/M + 通栏进度条 + ⌃（旋转由形变进度驱动，非独立动画）。
 * 细线/直角阴影修复：lensRadius 压到 13dp（折射环宽 15.6dp ≤ 圆角半径，圆角外
 * 不溢出方形光晕）；surfaceAlpha 提到 0.50（顶栏底缘发丝线不再透进胶囊）。
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

    val infinite = androidx.compose.animation.core.rememberInfiniteTransition(label = "pillDot")
    val breath by infinite.animateFloat(
        0.35f, 1f,
        androidx.compose.animation.core.infiniteRepeatable(
            androidx.compose.animation.core.tween(900),
            androidx.compose.animation.core.RepeatMode.Reverse
        ), label = "pillBreath"
    )

    val density = androidx.compose.ui.platform.LocalDensity.current
    // 胶囊固有宽（收起态内容实测；首次测量前直接用全宽避免 0 宽闪跳）
    var pillPx by remember { mutableFloatStateOf(0f) }

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .padding(top = 4.dp, end = 12.dp, start = 12.dp),
        contentAlignment = Alignment.TopEnd
    ) {
        val fullPx = with(density) { maxWidth.toPx() }
        val wPx = if (pillPx <= 0f) fullPx else pillPx + (fullPx - pillPx) * morph
        val corner = androidx.compose.ui.unit.lerp(17.dp, 15.dp, morph)

        // 隐形测量行（alpha=0 不渲染但保持自然布局）：与收起版内容同构，在玻璃外量固有宽。
        // 三个关键坑（均已踩过）：
        // 1) 放玻璃内 → 节点尺寸被父约束 coerce 成玻璃宽 →「玻璃宽→测量→玻璃宽」自锁；
        // 2) 容器 height(0.dp) → Text 在高度 0 约束下宽度同样塌缩，量到的只有 padding；
        // 3) onSizeChanged 放在 padding 之后（链上内层）→ 量到的是去掉 50dp padding 的
        //    纯文字宽，玻璃按此设宽装不下内容。挂在 Box 外层量「含 padding 完整宽」。
        Box(
            Modifier
                .alpha(0f)
                .onSizeChanged { if (it.width > 0) pillPx = it.width.toFloat() }
        ) {
            Row(
                Modifier.padding(start = 12.dp, end = 38.dp),
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
                    modifier = Modifier.widthIn(max = 130.dp)
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    "$doneCount/$total",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        GlassPanel(
            backdrop = backdrop,
            radius = corner,
            lensRadius = 13.dp,
            surfaceAlpha = 0.50f,
            modifier = Modifier
                .width(with(density) { wPx.toDp() })
                .animateContentSize(animationSpec = tween(420, easing = MorphEase))
        ) {
            Column {
                // ── 头部：两版内容按形变进度交叉淡入。收起态整行可点展开；
                // 展开态仅右上圆钮触发收起（整行可点会让圆钮形同虚设，用户反馈 2026-09-10）。
                // indication=null 禁 ripple：玻璃件按压语言统一为形变/辉光，不出方形涟漪
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(34.dp)
                        .clip(RoundedCornerShape(corner))
                        .clickable(
                            interactionSource = null,
                            indication = null,
                            enabled = !expanded
                        ) { onToggle() },
                    contentAlignment = Alignment.CenterStart
                ) {
                    // 收起版：● 进行中任务名 · N/M（纯显示，宽度由玻璃外的隐形测量行驱动；
                    // 右距 38dp = chevron 圆钮区 30dp + 8dp 间隙，内容与圆钮互不叠压）
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
                            modifier = Modifier.widthIn(max = 130.dp)
                        )
                        Spacer(Modifier.width(7.dp))
                        Text(
                            "$doneCount/$total",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    // 展开版：任务 · N/M · 短进度条（固定 64dp，不通栏——通栏会顶到圆钮
                    // 且视觉上"一条线横贯整个面板"，用户反馈 2026-09-10；右距 38dp 让位圆钮）
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 12.dp, end = 38.dp)
                            .alpha(morph),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "任务",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "$doneCount/$total",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                        Spacer(Modifier.width(10.dp))
                        Box(
                            Modifier
                                .width(64.dp)
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f))
                        ) {
                            Box(
                                Modifier
                                    .fillMaxWidth(progress)
                                    .height(4.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(MaterialTheme.colorScheme.primary)
                            )
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
                // ── 清单：展开时纵向生长（头部高度不变，总高由 animateContentSize 平滑）──
                AnimatedVisibility(
                    visible = expanded,
                    enter = expandVertically(animationSpec = tween(420, easing = MorphEase)) + androidx.compose.animation.fadeIn(),
                    exit = shrinkVertically(tween(240)) + androidx.compose.animation.fadeOut(tween(160))
                ) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 160.dp)
                            .padding(bottom = 6.dp)
                    ) {
                        itemsIndexed(items, key = { _, item -> item.id }) { _, item ->
                            TaskItemRow(item)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TaskPanel(
    items: List<TodoItem>,
    backdrop: LayerBackdrop,
    forced: Boolean = false,
    onDismiss: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    if (items.isEmpty() && !forced) return

    val hasIncomplete = items.any { it.status != "completed" && it.status != "cancelled" }
    if (!hasIncomplete && !forced) return

    var expanded by remember { mutableStateOf(true) }
    // × 手动关闭：与 forced 无关地对任何来源的面板生效；
    // 清单被整体替换（新任务首条 id 变化）时重置，同一清单的状态更新不复活
    var dismissed by remember(items.firstOrNull()?.id) { mutableStateOf(false) }
    if (dismissed && !forced) return
    val doneCount = items.count { it.status == "completed" }
    val total = items.size
    val progress = if (total > 0) doneCount.toFloat() / total else 0f

    val activeTask = items.firstOrNull { it.status == "in_progress" }
        ?: items.firstOrNull { it.status == "pending" }

    GlassPanel(
        backdrop = backdrop,
        radius = 14.dp,
        surfaceAlpha = 0.28f,
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize()
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "任务",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "$doneCount/$total",
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
                if (!expanded && activeTask != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        activeTask.text,
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                if (items.isEmpty()) {
                    // 强制展开的空态
                    Text(
                        "暂无任务",
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.weight(1f)
                    )
                }
                onDismiss?.let {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "关闭任务面板",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .size(16.dp)
                            .clickable {
                                it()
                                dismissed = true
                            }
                    )
                    Spacer(Modifier.width(4.dp))
                }
                // 图标语义 = 点击后动作：展开态显示 ⌃（收起），收起态显示 ⌄（展开）
                Icon(
                    if (expanded) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }
            Spacer(Modifier.height(4.dp))
            if (items.isNotEmpty()) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    strokeCap = StrokeCap.Round,
                    gapSize = 0.dp,
                    drawStopIndicator = {}
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(spring(stiffness = Spring.StiffnessMedium)),
                exit = shrinkVertically(spring(stiffness = Spring.StiffnessMedium))
            ) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 180.dp)
                        .padding(top = 6.dp)
                ) {
                    itemsIndexed(items, key = { _, item -> item.id }) { _, item ->
                        TaskItemRow(item)
                    }
                }
            }
        }
    }
}

@Composable
private fun TaskItemRow(item: TodoItem) {
    val isDone = item.status == "completed"
    val isCancelled = item.status == "cancelled"
    val isDoing = item.status == "in_progress"

    val alpha by animateFloatAsState(
        targetValue = if (isDone || isCancelled) 0.45f else 1f,
        label = "taskAlpha"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .alpha(alpha),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TaskStatusIcon(status = item.status, priority = item.priority)
        Spacer(Modifier.width(8.dp))
        Text(
            item.text,
            style = MaterialTheme.typography.bodySmall,
            fontSize = 13.sp,
            color = when {
                isDone || isCancelled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                else -> MaterialTheme.colorScheme.onSurface
            },
            textDecoration = if (isDone || isCancelled) TextDecoration.LineThrough else null,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun TaskStatusIcon(status: String, priority: String) {
    val size = 16.dp
    when (status) {
        "completed" -> {
            Text(
                "✓",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                modifier = Modifier.width(size)
            )
        }
        "in_progress" -> {
            Text(
                "●",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.width(size)
            )
        }
        "cancelled" -> {
            Text(
                "–",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.error.copy(alpha = 0.6f),
                modifier = Modifier.width(size)
            )
        }
        else -> {
            val color = when (priority) {
                "high" -> MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                "low" -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            }
            Text(
                "○",
                fontSize = 12.sp,
                color = color,
                modifier = Modifier.width(size)
            )
        }
    }
}


/**
 * 顶栏一体任务区（方案二原版 · 锚定标题行旋转钮，用户验收 2026-09）：
 * - 标题行一行排完：「任务」+ N/M + 通栏进度条（拉满中间空间）+ 28dp 旋转钮；
 * - 旋转钮永远锚定标题行最右，收展只做图标 180° spring 旋转（展开⌃/收起⌄）——
 *   位置零变动，收起后入口就在原地，不存在"没地方打开"；
 * - 展开态清单直接全列（完成项划线置灰、进行中呼吸点），无二级收纳开关；
 *   超 140dp 面板内滚动 + 底部渐隐 + 自动定位进行中项；
 * - 无底缘拉手、无右下耳片（旧方案B悬挂件全部废弃）。
 */
@Composable
fun TopTaskSection(
    items: List<TodoItem>,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val doneCount = items.count { it.status == "completed" }
    val total = items.size
    val progress = if (total > 0) doneCount.toFloat() / total else 0f

    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(expanded, items.size) {
        if (!expanded) return@LaunchedEffect
        val ai = items.indexOfFirst { it.status == "in_progress" }
        if (ai > 0) listState.animateScrollToItem(ai)
    }

    // 旋转钮：展开 0°（⌃=向上收起）/ 收起 180°（⌄=向下展开），spring 回弹
    val rot by animateFloatAsState(
        targetValue = if (expanded) 0f else 180f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow),
        label = "taskToggleRot"
    )

    Column(modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        // 标题行（整行可点切换，右侧旋转钮为显式 affordance）
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { onToggle() }
                .padding(horizontal = 2.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "任务",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "$doneCount/$total",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
            Spacer(Modifier.width(10.dp))
            // 通栏进度条：拉满「计数」与旋转钮之间的整段中间空间（两态常驻）
            Box(
                Modifier
                    .weight(1f)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f))
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(progress)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
            Spacer(Modifier.width(10.dp))
            Box(
                Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                    .clickable { onToggle() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.ExpandLess,
                    contentDescription = if (expanded) "收起任务" else "展开任务",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(17.dp)
                        .graphicsLayer { rotationZ = rot }
                )
            }
        }
        // 清单区：展开即全列（一层结构，无二级收纳开关）
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(spring(stiffness = Spring.StiffnessMedium)) + androidx.compose.animation.fadeIn(),
            exit = shrinkVertically(spring(stiffness = Spring.StiffnessMedium)) + androidx.compose.animation.fadeOut()
        ) {
            Box {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 140.dp)
                ) {
                    itemsIndexed(items, key = { _, item -> item.id }) { _, item ->
                        TaskItemRow(item)
                    }
                }
                val canScroll = listState.canScrollForward || listState.canScrollBackward
                if (canScroll) {
                    Box(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(16.dp)
                            .background(
                                androidx.compose.ui.graphics.Brush.verticalGradient(
                                    listOf(
                                        MaterialTheme.colorScheme.surface.copy(alpha = 0f),
                                        MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
                                    )
                                )
                            )
                    )
                }
            }
        }
    }
}
