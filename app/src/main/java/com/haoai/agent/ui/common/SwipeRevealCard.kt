package com.haoai.agent.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChangeConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 通用「右滑呼出操作按钮」卡片（iOS 邮件式）：按钮区在卡片左后方，卡片右移露出。
 *
 * 手势要点：
 * - 方向锁：totalX/totalY 任一轴先越过 touchSlop 即定格方向；纵向先越线则永不接管，
 *   事件全部穿透给外层（列表滚动/翻页），竖滑歪一点不会再误触发呼出。
 * - 未展开仅右滑接管；已展开双向接管（可左滑收回）。
 * - 可选删除带 [deleteWidth]：从呼出位继续右划（或划开后再次右划）进入删除带，
 *   背景垃圾桶随进度渐显，松手越过阈值回调 [onDeleteSwipe]；未提供则偏移钳制在呼出宽度内。
 *
 * 误触保护：[anyOpen] 为 true（列表里有任一卡展开）时，点卡片只收回，不触发 [onClick]；
 * [content] 收到组件生成好的 cardClick 回调，调用方直接绑到卡面点击上。
 *
 * @param openWidth   呼出宽度
 * @param deleteWidth 删除带总宽（含呼出宽度）；null = 无删除区
 */
@Composable
fun SwipeRevealCard(
    isOpen: Boolean,
    anyOpen: Boolean,
    onClick: () -> Unit,
    onOpenChange: (Boolean) -> Unit,
    openWidth: Dp,
    deleteWidth: Dp? = null,
    onDeleteSwipe: () -> Unit = {},
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit,
    content: @Composable (cardClick: () -> Unit) -> Unit
) {
    val openPx = with(LocalDensity.current) { openWidth.toPx() }
    val deletePx = with(LocalDensity.current) { (deleteWidth ?: openWidth).toPx() }
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    // 外部开合（展开另一张 / 操作完成 / 抽屉收起）统一由此驱动
    LaunchedEffect(isOpen) {
        val target = if (isOpen) openPx else 0f
        if (offset.value < target - 0.5f || offset.value > target + 0.5f) offset.animateTo(target)
    }

    val cardClick = {
        if (isOpen || anyOpen) onOpenChange(false) else onClick()
    }

    Box(
        modifier
            .fillMaxWidth()
            .clipToBounds()
    ) {
        // 卡片后方：操作按钮区（露出部分）
        Row(
            Modifier
                .matchParentSize()
                .graphicsLayer { alpha = (offset.value / openPx).coerceIn(0f, 1f) }
                .padding(start = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = actions
        )
        // 继续右划的删除带：垃圾桶随超出呼出宽度的进度渐显
        if (deleteWidth != null) {
            val over = offset.value - openPx
            if (over > 0f) {
                Box(
                    Modifier
                        .matchParentSize()
                        .graphicsLayer { alpha = (over / (deletePx - openPx)).coerceIn(0f, 1f) },
                    contentAlignment = Alignment.CenterStart
                ) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "删除",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .padding(start = 34.dp)
                            .size(22.dp)
                    )
                }
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .graphicsLayer { translationX = offset.value }
                .pointerInput(isOpen, openPx, deletePx) {
                    val slop = viewConfiguration.touchSlop
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var totalX = 0f
                        var totalY = 0f
                        var decided = false
                        // 已展开的卡片双向接管（可左滑收回）；未展开时仅右滑接管
                        var owned = isOpen
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (change.changedToUp()) break
                            val delta = change.positionChange()
                            if (decided && !owned) {
                                if (change.positionChangeConsumed()) break
                            } else if (!decided) {
                                if (change.positionChangeConsumed()) break
                                totalX += delta.x
                                totalY += delta.y
                                // 方向锁：先越过 slop 的轴赢；纵向赢则整段手势让给列表滚动/翻页
                                if (abs(totalX) > slop || abs(totalY) > slop) {
                                    decided = true
                                    if (!owned) owned = abs(totalX) > abs(totalY) && totalX > 0f
                                }
                            }
                            if (decided && owned && delta != Offset.Zero) {
                                change.consume()
                                val raw = offset.value + delta.x
                                val next = if (raw <= deletePx) raw
                                else deletePx + (raw - deletePx) * 0.3f // 删除带外阻尼
                                scope.launch { offset.snapTo(next.coerceAtLeast(0f)) }
                            }
                        }
                        if (decided && owned) {
                            val deleting = deleteWidth != null && offset.value >= openPx + (deletePx - openPx) * 0.55f
                            val target = when {
                                deleting -> 0f
                                offset.value >= openPx / 2f -> openPx
                                else -> 0f
                            }
                            scope.launch { offset.animateTo(target) }
                            onOpenChange(target >= openPx)
                            if (deleting) onDeleteSwipe()
                        }
                    }
                }
        ) {
            content(cardClick)
        }
    }
}
