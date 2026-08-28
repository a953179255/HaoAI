package com.haoai.agent.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
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
 * - 可选删除带 [deleteWidth]：**两段式**——第一段拖入删除带松手只「上膛」（卡片推满、
 *   红色高亮、动作按钮隐藏），并不删除；上膛后再往右拖一次（或点删除带、点卡面取消）
 *   才执行 [onDeleteSwipe]。未提供 [deleteWidth] 则偏移钳制在呼出宽度内。
 *
 * 误触保护：[anyOpen] 为 true（列表里有任一卡展开）时，点卡片只收回，不触发 [onClick]；
 * 上膛态点卡片 = 取消上膛回到普通展开。[content] 收到组件生成好的 cardClick 回调。
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
    val density = LocalDensity.current
    val openPx = with(density) { openWidth.toPx() }
    val deletePx = with(density) { (deleteWidth ?: openWidth).toPx() }
    val armConfirmPx = with(density) { 40.dp.toPx() }
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    // 两段式删除：第一段只上膛（推满删除带），第二段再拖/点击才真删
    var deleteArmed by remember { mutableStateOf(false) }

    // 外部开合（展开另一张 / 操作完成 / 抽屉收起）统一由此驱动
    LaunchedEffect(isOpen) {
        if (!isOpen) deleteArmed = false
        val target = when {
            !isOpen -> 0f
            deleteArmed -> deletePx
            else -> openPx
        }
        if (offset.value < target - 0.5f || offset.value > target + 0.5f) offset.animateTo(target)
    }

    val cardClick = {
        when {
            // 上膛态点卡面 = 取消上膛，收回普通展开
            deleteArmed -> {
                deleteArmed = false
                scope.launch { offset.animateTo(openPx) }
                Unit
            }
            isOpen || anyOpen -> onOpenChange(false)
            else -> onClick()
        }
    }

    Box(
        modifier
            .fillMaxWidth()
            .clipToBounds()
    ) {
        // 卡片后方：操作按钮区（露出部分）；进入删除带时随进度淡出，为垃圾桶让位
        Row(
            Modifier
                .matchParentSize()
                .graphicsLayer {
                    val reveal = (offset.value / openPx).coerceIn(0f, 1f)
                    val over = ((offset.value - openPx) / (deletePx - openPx).coerceAtLeast(1f)).coerceIn(0f, 1f)
                    alpha = reveal * (1f - over)
                }
                .padding(start = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = actions
        )
        // 删除带：圆角红色容器；上膛态实心红 + 白图标，可点击直接删除
        if (deleteWidth != null) {
            val progress = ((offset.value - openPx) / (deletePx - openPx).coerceAtLeast(1f)).coerceIn(0f, 1f)
            val bandAlpha = if (deleteArmed) 1f else progress
            if (bandAlpha > 0f) {
                Box(
                    Modifier
                        .matchParentSize()
                        .graphicsLayer { alpha = bandAlpha },
                    contentAlignment = Alignment.CenterStart
                ) {
                    Box(
                        Modifier
                            .padding(start = 4.dp, top = 5.dp, bottom = 5.dp)
                            .fillMaxHeight()
                            .width(64.dp)
                            .background(
                                if (deleteArmed) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.error.copy(alpha = 0.16f),
                                RoundedCornerShape(14.dp)
                            )
                            .clickable(enabled = deleteArmed) { onDeleteSwipe() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = if (deleteArmed) "点击删除" else "继续右拖以上膛删除",
                            tint = if (deleteArmed) Color.White else MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(22.dp)
                        )
                    }
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
                        var armedTravel = 0f // 上膛后继续右拖的累计量（第二段确认删除）
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
                                if (deleteArmed) {
                                    if (delta.x > 0f) {
                                        // 上膛态右拖：累计确认量，位置轻微阻尼跟随
                                        armedTravel += delta.x
                                        val overPull = (armedTravel * 0.3f).coerceAtMost(with(density) { 12.dp.toPx() })
                                        scope.launch { offset.snapTo(deletePx + overPull) }
                                    } else {
                                        armedTravel = 0f
                                        val raw = (offset.value + delta.x).coerceIn(0f, deletePx)
                                        scope.launch { offset.snapTo(raw) }
                                    }
                                } else {
                                    val raw = offset.value + delta.x
                                    val next = if (raw <= deletePx) raw
                                    else deletePx + (raw - deletePx) * 0.3f // 删除带外阻尼
                                    scope.launch { offset.snapTo(next.coerceAtLeast(0f)) }
                                }
                            }
                        }
                        if (decided && owned) {
                            when {
                                // 第二段：上膛态继续右拖 → 真删除
                                deleteArmed && armedTravel >= armConfirmPx -> {
                                    deleteArmed = false
                                    scope.launch { offset.animateTo(0f) }
                                    onOpenChange(false)
                                    onDeleteSwipe()
                                }
                                // 上膛态左拖：拉回普通展开（超过一半则整体收起）
                                deleteArmed && offset.value < openPx / 2f -> {
                                    deleteArmed = false
                                    scope.launch { offset.animateTo(0f) }
                                    onOpenChange(false)
                                }
                                deleteArmed && offset.value < openPx + (deletePx - openPx) * 0.3f -> {
                                    deleteArmed = false
                                    scope.launch { offset.animateTo(openPx) }
                                    onOpenChange(true)
                                }
                                deleteArmed -> {
                                    // 上膛态小幅晃动：回到上膛位
                                    scope.launch { offset.animateTo(deletePx) }
                                    onOpenChange(true)
                                }
                                // 第一段：拖入删除带松手 → 只上膛，不删除
                                deleteWidth != null &&
                                    offset.value >= openPx + (deletePx - openPx) * 0.55f -> {
                                    deleteArmed = true
                                    scope.launch { offset.animateTo(deletePx) }
                                    onOpenChange(true)
                                }
                                offset.value >= openPx / 2f -> {
                                    deleteArmed = false
                                    scope.launch { offset.animateTo(openPx) }
                                    onOpenChange(true)
                                }
                                else -> {
                                    deleteArmed = false
                                    scope.launch { offset.animateTo(0f) }
                                    onOpenChange(false)
                                }
                            }
                        }
                    }
                }
        ) {
            content(cardClick)
        }
    }
}
