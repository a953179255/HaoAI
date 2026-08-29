package com.haoai.agent.ui.common

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChangeConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 通用「右滑呼出操作按钮」卡片（iOS 邮件式）：按钮区在卡片左后方，卡片右移露出。
 *
 * 手势要点：
 * - 方向锁：totalX/totalY 任一轴先越过 touchSlop 即定格方向；纵向先越线则永不接管，
 *   事件全部穿透给外层（列表滚动/翻页），竖滑歪一点不会再误触发呼出。
 * - 未展开仅右滑接管；已展开双向接管（可左滑收回）。
 * - 可选删除带 [deleteWidth]：**两段式**——第一段拖入删除带松手只「上膛」（删除带居中
 *   浮现、卡面贴住其右侧、红色高亮、动作按钮隐藏），并不删除；上膛后再往右拖一次
 *   （或点删除带、点卡面取消）才执行 [onDeleteSwipe]。未提供 [deleteWidth] 则偏移钳制在呼出宽度内。
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
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val openPx = with(density) { openWidth.toPx() }
    val deletePx = with(density) { (deleteWidth ?: openWidth).toPx() }
    val armConfirmPx = with(density) { 40.dp.toPx() }
    // 删除带靠左贴边显示（用户反馈：不必居中于呼出区，靠左更顺手）；
    // 上膛位 = 删除带右缘 + 8dp，卡面贴住删除按钮
    val bandWidth = 64.dp
    val bandStart = 4.dp
    val armedPx = with(density) { (bandStart + bandWidth + 8.dp).toPx() }
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    // 两段式删除：第一段只上膛（推满删除带），第二段再拖/点击才真删
    var deleteArmed by remember { mutableStateOf(false) }
    // 第二段拖拽已越过删除确认阈值：松手即删（文案从「删除会话」切到「松手删除」）
    var confirmReady by remember { mutableStateOf(false) }
    // 上膛锚点：上膛瞬间的卡片位置。armed 右拖用「锚点 + 累计量」绝对定位，
    // 不读 offset.value——snapTo 是异步协程，快速拖动时相对计算会读到旧值造成抽搐
    var armedAnchor by remember { mutableStateOf(0f) }



    // 外部开合（展开另一张 / 操作完成 / 抽屉收起）统一由此驱动
    LaunchedEffect(isOpen) {
        if (!isOpen) { deleteArmed = false; confirmReady = false }
        val target = when {
            !isOpen -> 0f
            deleteArmed -> armedPx
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
            // 水平出界裁剪必须用绘制期 clipRect 而非 clipToBounds/clip：后者是真正的
            // 离屏层（graphicsLayer clip），其方形层边界会被玻璃卡片的 backdrop 采样
            // 反复回画，在卡片四角闪「直角残影」（点击会话触发收回动画时必现）
            .drawWithContent {
                clipRect(right = size.width) { this@drawWithContent.drawContent() }
            }
    ) {
        // 卡片后方：操作按钮区（露出部分）；上膛态隐藏，为居中的删除带让位
        Row(
            Modifier
                .matchParentSize()
                .graphicsLayer {
                    val reveal = (offset.value / openPx).coerceIn(0f, 1f)
                    // 无删除带时 span=0：over 必须恒 0，否则分母退化成 1px，
                    // 手指按住的微抖跨越 openPx 会让按钮在全显/全隐间闪烁
                    val span = deletePx - openPx
                    val over = if (span > 1f) ((offset.value - openPx) / span).coerceIn(0f, 1f) else 0f
                    alpha = if (deleteArmed) 0f else reveal * (1f - over)
                }
                .padding(start = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = actions
        )
        // 删除带：圆角红色容器，靠左贴边；上膛态实心红 + 「删除会话」呼吸提示，
        // 再右滑越阈值变「松手删除」（点击删除带仍为备用路径）
        if (deleteWidth != null) {
            val progress = ((offset.value - openPx) / (deletePx - openPx).coerceAtLeast(1f)).coerceIn(0f, 1f)
            val bandAlpha = if (deleteArmed) 1f else progress
            val armPulse = rememberInfiniteTransition(label = "armPulse").animateFloat(
                initialValue = 0.55f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(620), RepeatMode.Reverse),
                label = "armPulseAlpha"
            )
            if (bandAlpha > 0f) {
                Box(
                    Modifier
                        .matchParentSize()
                        .graphicsLayer { alpha = bandAlpha },
                    contentAlignment = Alignment.CenterStart
                ) {
                    Box(
                        Modifier
                            .padding(start = bandStart, top = 5.dp, bottom = 5.dp)
                            .fillMaxHeight()
                            .width(bandWidth)
                            .background(
                                if (deleteArmed) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.error.copy(alpha = 0.16f),
                                RoundedCornerShape(14.dp)
                            )
                            .clickable(enabled = deleteArmed) { onDeleteSwipe() },
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = when {
                                    confirmReady -> "松手删除会话"
                                    deleteArmed -> "再右滑或点击删除会话"
                                    else -> "继续右拖以上膛删除"
                                },
                                tint = if (deleteArmed) Color.White else MaterialTheme.colorScheme.error,
                                modifier = Modifier
                                    .size(19.dp)
                                    // 仅「继续右滑」版（带文字）下移：补偿图标字面偏上 + 文字行高，
                                    // 使图标上边距≈文字下边距；无文字版保持正中不偏移
                                    .then(if (deleteArmed) Modifier.offset(y = 3.dp) else Modifier)
                            )
                            if (deleteArmed) {
                                Text(
                                    if (confirmReady) "松手删除" else "删除会话",
                                    color = Color.White.copy(alpha = armPulse.value),
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                // 布局期平移而非 graphicsLayer.translationX：离屏层的方形边界会被玻璃卡片的
                // backdrop 采样反复回画，在四角积累成「直角阴影」残影（offset 不创建层）
                .offset { IntOffset(offset.value.toInt(), 0) }
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
                        // 手势内累计位移：Animatable.snapTo 是异步协程，高频拖动时 offset.value
                        // 滞后真实位置（实测上膛在滑动末帧才随机触发）——判定与结算一律用 dragX
                        var dragX = offset.value
                        // 上膛必须发生在上一段手势：同一段右滑里即时上膛后不再接管确认段，
                        // 杜绝「一次右滑到底直接删掉」——确认删除需松手后再来一段或点删除带
                        val armedAtStart = deleteArmed
                        confirmReady = false
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
                                if (deleteArmed && armedAtStart) {
                                    // 统一锚点公式：左右拖同一条连续函数，微抖不跳变；左拖超过
                                    // 16dp 才退膛
                                    armedTravel += delta.x
                                    val next = (armedAnchor + armedTravel * 0.8f)
                                        .coerceIn(armedPx, deletePx + with(density) { 34.dp.toPx() })
                                    dragX = next
                                    scope.launch { offset.snapTo(next) }
                                    if (armedTravel >= armConfirmPx) confirmReady = true
                                    if (armedTravel < -with(density) { 16.dp.toPx() }) {
                                        deleteArmed = false
                                        confirmReady = false
                                    }
                                } else {
                                    dragX = (dragX + delta.x).coerceAtLeast(0f)
                                    val next = if (dragX <= deletePx) dragX
                                    else deletePx + (dragX - deletePx) * 0.3f // 删除带外阻尼
                                    scope.launch { offset.snapTo(next) }
                                    // 拖入删除带深处即时上膛（带触觉提示），不必先松手；
                                    // 判定用同步的 dragX，时机不再随机漂移
                                    if (deleteWidth != null && !deleteArmed &&
                                        dragX >= openPx + (deletePx - openPx) * 0.55f
                                    ) {
                                        deleteArmed = true
                                        armedTravel = 0f
                                        armedAnchor = dragX
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    }
                                }
                            }
                        }
                        if (decided && owned) {
                            when {
                                // 第二段：上膛态继续右拖越过阈值 → 松手真删除
                                deleteArmed && armedTravel >= armConfirmPx -> {
                                    deleteArmed = false
                                    confirmReady = false
                                    scope.launch { offset.animateTo(0f) }
                                    onOpenChange(false)
                                    onDeleteSwipe()
                                }
                                // 上膛态松手：回到贴住删除带的上膛位；锚点同步到回位目标，
                                // 否则即时上膛的深处锚点会让二次手势位置跳变
                                deleteArmed -> {
                                    confirmReady = false
                                    armedAnchor = armedPx
                                    scope.launch { offset.animateTo(armedPx) }
                                    onOpenChange(true)
                                }
                                // 第一段：拖入删除带松手 → 只上膛，不删除
                                deleteWidth != null &&
                                    dragX >= openPx + (deletePx - openPx) * 0.55f -> {
                                    deleteArmed = true
                                    armedAnchor = armedPx
                                    scope.launch { offset.animateTo(armedPx) }
                                    onOpenChange(true)
                                }
                                dragX >= openPx / 2f -> {
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
