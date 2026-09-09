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
 * 顶栏一体任务区（方案B · 底缘耳片，用户选型 2026-09）：
 * - 标题行「任务 N/M」+ 细进度条（+进行中任务名）；
 * - 默认只列未完成任务（滚动窗口 ≤140dp、底部渐隐暗示可滚、自动定位到进行中项）；
 *   已完成项收进「展开已完成 N 条 ⌄」，点开追加显示；
 * - 底缘中央拉手（横条把手+⌃）= 收起，点整条拉手或标题行均可；拉手常驻不随滚动走；
 * - 长清单策略：20 条任务时展开态最大约 190dp，消息区保留可见空间。
 * 展开回调由外部（ChatScreen）持有状态，收起走 onCollapse。
 */
@Composable
fun TopTaskSection(
    items: List<TodoItem>,
    onCollapse: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val doneCount = items.count { it.status == "completed" }
    val total = items.size
    val progress = if (total > 0) doneCount.toFloat() / total else 0f
    val activeIndex = items.indexOfFirst { it.status == "in_progress" }
    val activeTask = items.getOrNull(activeIndex)

    // 已完成项收纳：默认只列未完成；showDone 时全部显示
    var showDone by remember { mutableStateOf(false) }
    val pendingItems = remember(items) { items.filterNot { it.status == "completed" } }
    val displayItems = if (showDone) items else pendingItems
    val doneVisible = doneCount > 0

    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    // 新清单/进行中项变化时自动滚到进行中任务（默认视图以未完成任务开头）
    LaunchedEffect(activeIndex, showDone, pendingItems.size) {
        if (!showDone && pendingItems.isNotEmpty()) {
            // 未完成视图里进行中项的相对位置
            val target = pendingItems.indexOfFirst { it.status == "in_progress" }
            if (target > 0) listState.animateScrollToItem(target.coerceAtLeast(0))
        } else if (showDone && activeIndex >= 0) {
            listState.animateScrollToItem(activeIndex.coerceAtLeast(0))
        }
    }

    Column(modifier.fillMaxWidth()) {
        // 标题行（点击 = 收起；也可只点底部拉手）
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { onCollapse() },
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
            if (!showDone && activeTask != null) {
                Spacer(Modifier.width(8.dp))
                Text(
                    "进行中: ${activeTask.text}",
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(6.dp))
        // 自绘进度条（无 stopIndicator 圆点）
        Box(
            Modifier
                .fillMaxWidth()
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
        Spacer(Modifier.height(4.dp))
        // 清单：滚动窗口 ≤140dp；底部渐隐暗示可滚
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
            // 底部渐隐（内容可滚时才画）
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
        // 已完成收纳开关
        if (doneVisible) {
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
        Spacer(Modifier.height(8.dp))
    }
    // 底缘拉手：贴玻璃底缘（外层 Column 只剩水平 padding，拉手下探 8dp 抵消原
    // vertical padding，圆角底缘与玻璃一体）；点整条收起（常驻、不随清单滚动）
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center
    ) {
        androidx.compose.material3.Surface(
            onClick = onCollapse,
            shape = RoundedCornerShape(bottomStart = 14.dp, bottomEnd = 14.dp),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f),
            modifier = Modifier.size(width = 120.dp, height = 30.dp)
        ) {
            Row(
                Modifier.fillMaxSize(),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 横条把手
                Box(
                    Modifier
                        .size(width = 26.dp, height = 3.5.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f))
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "⌃",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }
        }
    }
}
