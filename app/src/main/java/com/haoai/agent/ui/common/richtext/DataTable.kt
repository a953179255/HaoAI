package com.haoai.agent.ui.common.richtext

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max

/**
 * 方案 A 表格（上游 DataTable.kt 移植精简版）：
 * SubcomposeLayout 两阶段测量——第一阶段自然宽度估列宽（min/max 约束），
 * 第二阶段按最终列宽放置。长单元格在列宽上限内换行，不挤压其他列。
 * 宽度超出视口时外层 horizontalScroll；窄表格拉伸铺满。
 *
 * 单元格内容是完整行内渲染（@Composable lambda），支持粗体/行内代码/行内公式。
 */
@Composable
fun DataTable(
    headers: List<@Composable () -> Unit>,
    rows: List<List<@Composable () -> Unit>>,
    modifier: Modifier = Modifier,
    cellPadding: Dp = 6.dp,
    columnMinWidths: List<Dp> = List(headers.size.coerceAtLeast(1)) { 72.dp },
    columnMaxWidths: List<Dp> = List(headers.size.coerceAtLeast(1)) { 220.dp }
) {
    val borderColor = MaterialTheme.colorScheme.outlineVariant
    val headerBg = MaterialTheme.colorScheme.surfaceVariant
    val shape = MaterialTheme.shapes.small
    val hScroll = rememberScrollState()

    BoxWithConstraints(modifier = modifier) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = shape,
            modifier = Modifier
                .clip(shape)
                .border(BorderStroke(0.5.dp, borderColor), shape)
        ) {
            Box(Modifier.horizontalScroll(hScroll)) {
                SubcomposeLayout { constraints ->
                    val columnCount = max(headers.size, rows.maxOfOrNull { it.size } ?: 0)
                    if (columnCount == 0) return@SubcomposeLayout layout(0, 0) {}

                    val infinity = Constraints.Infinity
                    val unbounded = Constraints(0, infinity, 0, infinity)
                    val minPx = IntArray(columnCount) { columnMinWidths.getOrNull(it)?.roundToPx() ?: 0 }
                    val maxPx = IntArray(columnCount) { columnMaxWidths.getOrNull(it)?.roundToPx() ?: Int.MAX_VALUE }
                    val colWidths = IntArray(columnCount)
                    val headerP1 = arrayOfNulls<Placeable>(columnCount)
                    val bodyP1 = arrayOfNulls<Placeable>(rows.size * columnCount)

                    fun measureCell(slot: String, col: Int, content: @Composable () -> Unit): Placeable {
                        val measurables = subcompose(slot) { content() }
                        val c = if (maxPx[col] != Int.MAX_VALUE) Constraints(0, maxPx[col], 0, infinity) else unbounded
                        val p = measurables.first().measure(c)
                        colWidths[col] = max(colWidths[col], max(p.width, minPx[col])).coerceAtMost(maxPx[col])
                        return p
                    }

                    // 阶段一：自然测量
                    headers.forEachIndexed { c, content ->
                        headerP1[c] = measureCell("h_$c", c) {
                            Box(
                                Modifier
                                    .background(headerBg)
                                    .padding(cellPadding)
                            ) { content() }
                        }
                    }
                    rows.forEachIndexed { r, row ->
                        row.forEachIndexed { c, content ->
                            bodyP1[r * columnCount + c] = measureCell("b_${r}_$c", c) {
                                Box(Modifier.padding(cellPadding)) { content() }
                            }
                        }
                    }

                    // 收缩：总宽不超视口时按比例摊（窄表铺满）
                    val natural = colWidths.sum()
                    val viewport = constraints.maxWidth
                    if (natural in 1 until viewport) {
                        val factor = viewport.toFloat() / natural
                        for (c in 0 until columnCount) {
                            colWidths[c] = (colWidths[c] * factor).toInt().coerceIn(minPx[c], maxPx[c])
                        }
                    }

                    // 阶段二：按最终列宽放置
                    val headerP2 = Array(columnCount) { c ->
                        subcompose("h2_$c") {
                            Box(
                                Modifier
                                    .background(headerBg)
                                    .padding(cellPadding)
                            ) { headers[c]() }
                        }.first().measure(Constraints.fixedWidth(colWidths[c]))
                    }
                    val bodyP2 = Array(rows.size) { r ->
                        Array(columnCount) { c ->
                            subcompose("b2_${r}_$c") {
                                Box(Modifier.padding(cellPadding)) { rows[r].getOrNull(c)?.invoke() }
                            }.first().measure(Constraints.fixedWidth(colWidths[c]))
                        }
                    }

                    val rowHeights = IntArray(rows.size) { r ->
                        bodyP2[r].maxOfOrNull { it.height } ?: 0
                    }
                    val headerHeight = headerP2.maxOfOrNull { it.height } ?: 0
                    val totalWidth = colWidths.sum()
                    val totalHeight = headerHeight + rowHeights.sum()

                    layout(totalWidth, totalHeight) {
                        var y = 0
                        headerP2.forEachIndexed { c, p ->
                            p.placeRelative(
                                colWidths.take(c).sum(), y
                            )
                        }
                        y += headerHeight
                        bodyP2.forEachIndexed { r, cells ->
                            cells.forEachIndexed { c, p ->
                                p.placeRelative(colWidths.take(c).sum(), y)
                            }
                            y += rowHeights[r]
                        }
                    }
                }
            }
        }
    }
}

/** 表头默认文本样式（粗体小号）。 */
@Composable
fun HeaderText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onBackground
    )
}

/** 单元格默认文本样式。 */
@Composable
fun CellText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.9f)
    )
}
