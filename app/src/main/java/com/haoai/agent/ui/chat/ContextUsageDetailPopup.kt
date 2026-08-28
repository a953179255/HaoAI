package com.haoai.agent.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.LayerBackdrop

@Composable
fun ContextUsageDetailPopup(
    usage: ContextUsage,
    backdrop: LayerBackdrop,
    modifier: Modifier = Modifier
) {
    val color = when {
        usage.percentage > 0.9f -> MaterialTheme.colorScheme.error
        usage.percentage > 0.75f -> Color(0xFFFF6D00)
        usage.percentage > 0.5f -> Color(0xFFFFD600)
        else -> Color(0xFF00C853)
    }

    com.haoai.agent.ui.common.GlassPanel(
        backdrop = backdrop,
        modifier = modifier
            .padding(top = 6.dp, end = 4.dp)
            .widthIn(min = 220.dp),
        radius = 16.dp,
        surfaceAlpha = 0.72f
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(
                "上下文使用详情",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { usage.percentage.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = color,
                trackColor = color.copy(alpha = 0.15f),
                // M3 1.3+ 默认在轨道末端画一个进度色圆点（stop indicator）：
                // 未用到也会显示实心绿点，与「剩余轨道=浅色」的预期不符，去掉并消除间隙
                gapSize = 0.dp,
                drawStopIndicator = {}
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "${fmtTok(usage.usedTokens)} / ${fmtTok(usage.totalTokens)}  (${(usage.percentage * 100).toInt()}%)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f))
            Spacer(Modifier.height(6.dp))
            DetailRow("系统提示", usage.systemTokens)
            DetailRow("工具定义", usage.toolsTokens)
            DetailRow("历史消息", usage.historyTokens)
            Spacer(Modifier.height(4.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f))
            Spacer(Modifier.height(4.dp))
            DetailRow("剩余可用", usage.remainingTokens, highlight = true)
        }
    }
}

@Composable
private fun DetailRow(label: String, tokens: Int, highlight: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 1.5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            fmtTok(tokens),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (highlight) FontWeight.Bold else FontWeight.Normal,
            color = if (highlight) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface
        )
    }
}

private fun fmtTok(n: Int): String = when {
    n >= 1_000_000 -> String.format("%.1fM", n / 1_000_000.0)
    n >= 1_000 -> String.format("%.1fK", n / 1_000.0)
    else -> "$n"
}
