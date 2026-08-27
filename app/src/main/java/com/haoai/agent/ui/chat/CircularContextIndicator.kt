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
    modifier: Modifier = Modifier
) {
    val animatedProgress by animateFloatAsState(
        targetValue = usage.percentage.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
        label = "ctxProgress"
    )
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
            .background(color.copy(alpha = 0.12f))
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
