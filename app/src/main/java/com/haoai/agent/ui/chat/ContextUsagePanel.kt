package com.haoai.agent.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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

/**
 * 上下文用量详情面板：从顶栏玻璃下沿向下展开、水平居中（不再是锚定右上角的 Popup）。
 * 行口径与真实请求一致：基础系统提示 / 动态注入 / 工具定义 / 压缩摘要 /
 * 历史（最近 80 条、tool 结果按 4K 字符截断）/ 回复预留（max_tokens）。
 */
@Composable
fun ContextUsagePanel(
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
        modifier = modifier.width(300.dp),
        radius = 16.dp,
        // 统一玻璃配方（2026-10-08）：原不传 lens → 继承 radius16×默认倍数2=位移32，
        // 现走全局调参（默认 16×1）
        lensRadius = com.haoai.agent.ui.theme.GlassTuning.lensHeight.dp,
        lensAmount = com.haoai.agent.ui.theme.GlassTuning.lensAmount.dp,
        depthEffect = com.haoai.agent.ui.theme.GlassTuning.depthEffect,
        blurRadius = com.haoai.agent.ui.theme.GlassTuning.blur.dp,
        chromaticAberration = com.haoai.agent.ui.theme.GlassTuning.ca,
        // 0.72 → 0.58：与任务面板统一材质（0.72 几乎是不透明的白，实测与背景仅 1.03:1）
        surfaceAlpha = 0.58f,
        // 浮层强化：与任务面板同一层级语言（库原生三件套）
        floating = true
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
            if (usage.injectedTokens > 0) DetailRow("记忆/技能注入", usage.injectedTokens)
            DetailRow("工具定义", usage.toolsTokens)
            if (usage.summaryTokens > 0) DetailRow("压缩摘要", usage.summaryTokens)
            DetailRow("历史消息", usage.historyTokens)
            if (usage.reservedTokens > 0) DetailRow("回复预留", usage.reservedTokens)
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
