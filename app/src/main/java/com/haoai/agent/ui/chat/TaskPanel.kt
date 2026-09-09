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
import androidx.compose.foundation.layout.fillMaxSize
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
 * 顶栏一体任务区（方案二 · 锚定标题行旋转钮，用户选型 2026-09）：
 * - 标题行 = 玻璃面板唯一常驻部分：「任务」+ N/M + 进行中提示 + 进度条 + 旋转钮；
 * - 旋转钮永远锚定标题行最右，收展只做图标 180° spring 旋转（展开⌃/收起⌄）——
 *   位置零变动，收起后入口就在原地，不存在"没地方打开"；
 * - 清单 AnimatedVisibility 挂标题行下：滚动窗口 ≤140dp + 底部渐隐 + 自动定位进行中项；
 * - 已完成项收进「展开已完成 N 条 ⌄」，点开追加显示；
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
    val activeTask = items.firstOrNull { it.status == "in_progress" }

    // 已完成项收纳：默认只列未完成；showDone 时全部显示
    var showDone by remember { mutableStateOf(false) }
    val pendingItems = remember(items) { items.filterNot { it.status == "completed" } }
    val displayItems = if (showDone) items else pendingItems

    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(expanded, showDone, pendingItems.size) {
        if (!expanded) return@LaunchedEffect
        if (showDone) {
            val ai = items.indexOfFirst { it.status == "in_progress" }
            if (ai > 0) listState.animateScrollToItem(ai)
        } else {
            val pi = pendingItems.indexOfFirst { it.status == "in_progress" }
            if (pi > 0) listState.animateScrollToItem(pi)
        }
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
            // 细进度条（收起态也在标题行里常驻，一眼看进度）
            Box(
                Modifier
                    .width(56.dp)
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
            Spacer(Modifier.width(8.dp))
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
        // 清单区
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(spring(stiffness = Spring.StiffnessMedium)) + androidx.compose.animation.fadeIn(),
            exit = shrinkVertically(spring(stiffness = Spring.StiffnessMedium)) + androidx.compose.animation.fadeOut()
        ) {
            Column {
                Box {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 140.dp)
                    ) {
                        itemsIndexed(displayItems, key = { _, item -> item.id }) { _, item ->
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
                if (doneCount > 0) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { showDone = !showDone }
                            .padding(vertical = 5.dp),
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center
                    ) {
                        Text(
                            if (showDone) "收起已完成 ⌃" else "展开已完成 $doneCount 条 ⌄",
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.75f)
                        )
                    }
                }
            }
        }
    }
}
