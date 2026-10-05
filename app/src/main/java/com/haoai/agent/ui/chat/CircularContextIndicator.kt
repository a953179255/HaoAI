package com.haoai.agent.ui.chat

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun CircularContextIndicator(
    usage: ContextUsage,
    onClick: () -> Unit,
    // 详情面板展开时底盘加深：触发点显示激活态（面板改为顶栏下展开后不再有 Popup 自关高亮）
    expanded: Boolean = false,
    modifier: Modifier = Modifier
) {
    // 方案A：去掉 300ms 补间。流式输出期 percentage 每 40ms 就涨一次新目标，
    // animateFloatAsState 的补间永远在追赶、永不停歇 → 整屏被拖到满帧（实测
    // 正文流式期 60fps 的元凶）。占用本身涨得平缓（25Hz 步进零点几个百分点），
    // 直读当前值肉眼无跳变，省掉常驻动画。
    val animatedProgress = usage.percentage.coerceIn(0f, 1f)
    val color = when {
        usage.percentage > 0.9f -> MaterialTheme.colorScheme.error
        usage.percentage > 0.75f -> Color(0xFFFF6D00)
        usage.percentage > 0.5f -> Color(0xFFFFD600)
        else -> Color(0xFF00C853)
    }
    val pctText = "${(usage.percentage * 100).toInt()}%"

    Box(
        modifier = modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = if (expanded) 0.30f else 0.12f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(
            progress = { animatedProgress },
            modifier = Modifier
                .size(26.dp)
                .rotate(-90f),
            color = color,
            strokeWidth = 2.5.dp,
            trackColor = color.copy(alpha = 0.15f)
        )
        Text(
            text = pctText,
            fontSize = 7.5.sp,
            fontWeight = FontWeight.Bold,
            color = color,
            lineHeight = 8.sp
        )
    }
}
