package com.haoai.agent.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import androidx.compose.ui.graphics.asImageBitmap

@Composable
fun rememberAppBackdrop(wallpaper: android.graphics.Bitmap? = null): LayerBackdrop =
    rememberLayerBackdrop {
        if (wallpaper != null) {
            // 壁纸 cover-fit 铺满，给玻璃提供可折射的真实纹理（不加压层，保持通透）
            val canvasW = size.width
            val canvasH = size.height
            val scale = maxOf(canvasW / wallpaper.width, canvasH / wallpaper.height)
            val dw = wallpaper.width * scale
            val dh = wallpaper.height * scale
            drawImage(
                wallpaper.asImageBitmap(),
                dstOffset = androidx.compose.ui.unit.IntOffset(
                    ((canvasW - dw) / 2f).toInt().coerceAtMost(0),
                    ((canvasH - dh) / 2f).toInt().coerceAtMost(0)
                ),
                dstSize = androidx.compose.ui.unit.IntSize(dw.toInt(), dh.toInt())
            )
        } else {
            // 默认浅色渐变：深色底会让液态玻璃上的文字难以辨认
            drawRect(Brush.verticalGradient(listOf(Color(0xFFD8E4F2), Color(0xFFEDF2F8))))
        }
        drawContent()
    }

fun Modifier.appLayer(backdrop: LayerBackdrop): Modifier = this.layerBackdrop(backdrop)

@Composable
fun GlassPanel(
    backdrop: LayerBackdrop,
    modifier: Modifier = Modifier,
    radius: Dp = 24.dp,
    surfaceAlpha: Float = 0.26f,
    tint: Color? = null,
    content: @Composable () -> Unit
) {
    Box(
        modifier
            .drawBackdrop(
                backdrop = backdrop,
                shape = { RoundedCornerShape(radius) },
                effects = {
                    vibrancy()
                    blur(radius.toPx() / 3f)
                    lens(radius.toPx() * 0.9f, radius.toPx() * 2f)
                },
                onDrawSurface = {
                    drawRect(Color.White.copy(alpha = surfaceAlpha))
                    if (tint != null) {
                        drawRect(tint, blendMode = androidx.compose.ui.graphics.BlendMode.Hue)
                        drawRect(tint.copy(alpha = 0.35f))
                    }
                }
            )
    ) {
        content()
    }
}

/** 子页面通用玻璃页头：返回键 + 标题 + 可选动作区，悬浮在内容层之上的液态玻璃条。 */
@Composable
fun GlassPageBar(
    backdrop: LayerBackdrop,
    title: String,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {}
) {
    GlassPanel(
        backdrop = backdrop,
        modifier = modifier.fillMaxWidth(),
        radius = 24.dp,
        surfaceAlpha = 0.14f
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            if (onBack != null) {
                androidx.compose.material3.IconButton(onClick = onBack) {
                    androidx.compose.material3.Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        tint = androidx.compose.material3.MaterialTheme.colorScheme.onBackground
                    )
                }
            }
            androidx.compose.material3.Text(
                title,
                style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f).padding(horizontal = 6.dp)
            )
            actions()
        }
    }
}
