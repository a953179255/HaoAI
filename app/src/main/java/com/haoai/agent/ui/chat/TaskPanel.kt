package com.haoai.agent.ui.chat

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
 * 任务悬浮件（方案 A + E，用户选型 2026-09）：
 * 胶囊 ⇄ 面板是同一颗玻璃，宽/高/圆角全由 morph 单一进度源插值（420ms easeOutQuint）——
 * 高度 = 34dp 头部 + 清单固有高 × ease(morph)，中间帧边界四角恒定圆角；
 * 清单跟随玻璃高度被裁剪显露，无独立动画源。
 * 收起态内容：呼吸点 + 进行中任务名（截断）+ N/M + 圆钮；展开态交叉淡入：
 * 「任务」+ N/M + 64dp 短进度条。两态都只有右侧 LiquidGlassButton 触发切换。
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
    // 清单固有高（方案 E：morph 单一进度源插值高度的目标基准）
    var listPx by remember { mutableFloatStateOf(0f) }

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .padding(top = 4.dp, end = 12.dp, start = 12.dp),
        contentAlignment = Alignment.TopEnd
    ) {
        val fullPx = with(density) { maxWidth.toPx() }
        val wPx = if (pillPx <= 0f) fullPx else pillPx + (fullPx - pillPx) * morph
        val corner = androidx.compose.ui.unit.lerp(17.dp, 15.dp, morph)
        // 方案 E：高度 = 头部 34dp + 清单高 × morphEase(morph)，单一进度源驱动，
        // 圆角全程恒定——中间帧四角全圆（双动画叠加时底角会被拉直，已翻车）
        val headerPx = with(density) { 34.dp.toPx() }
        val targetListPx = if (listPx <= 0f) 0f else listPx
        val revealEase = MorphEase.transform(morph)
        val hPx = headerPx + targetListPx * revealEase

        // 隐形测量区（alpha=0 不渲染但保持自然布局）：玻璃外量固有尺寸。
        // 五坑全集：玻璃内测量会被玻璃自身 fixed 约束 coerce（宽度三坑 + 高度第四坑）；
        // 同一容器混测两个维度会互相污染——清单 fillMaxWidth 撑满父宽，
        // 把胶囊宽测量的 Box 一起撑成通栏（胶囊变全宽第五坑），必须拆开各自 wrap。
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
            items.forEach { item ->
                TaskItemRow(item)
            }
        }

        GlassPanel(
            backdrop = backdrop,
            radius = corner,
            lensRadius = 13.dp,
            surfaceAlpha = 0.50f,
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
                    // 展开版：任务 · N/M · 进度条（占满"任务 N/M"右侧的剩余宽度；
                    // 2026-09-11 用户反馈固定 64dp 太短。右距 38dp 仍为圆钮让位）
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
                        items.forEach { item ->
                            TaskItemRow(item)
                        }
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
            // 左距 14dp：✓/○ 图标列与头部「任务」标题对齐（此前 0dp 顶到玻璃缘，
            // 用户反馈 2026-09-10）
            .padding(start = 14.dp, end = 12.dp)
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


