package com.haoai.agent.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.clip
import com.haoai.agent.ui.theme.GlassTuning
import com.haoai.agent.ui.theme.parseHexColor
import com.haoai.agent.ui.theme.toHex
import com.kyant.backdrop.backdrops.LayerBackdrop

/** 色相条 / 色轮共用彩虹色序（首尾同色，sweep 与 horizontal 均无缝衔接）。 */
internal val PickerHueColors = listOf(
    Color(0xFFFF3B30), Color(0xFFFF9500), Color(0xFFFFCC00), Color(0xFF34C759),
    Color(0xFF00C7BE), Color(0xFF007AFF), Color(0xFF5856D6), Color(0xFFAF52DE),
    Color(0xFFFF2D55), Color(0xFFFF3B30)
)

/**
 * 色盘核心（SV 面板 + 色相条）——设置「主题色」3 槽弹层与引导/档案的
 * 单色弹层共用同一套手势与视觉。
 * SV 面板：横向=饱和度，纵向=明度；背景双层渐变（白→纯色 再叠 透明→黑）。
 * [onChange] 回调 (hue, sat, vel)，手势中逐帧驱动调用方状态。
 *
 * ⚠️ 手势 lambda 由 pointerInput(Unit) 长期持有（不随重组重启），直接读参数
 * 会拿到**首组合的旧值**——实测「先拖 SV 面板改 sat/vel，再点色相条」时
 * sat/vel 被打回初始值（2026-10-13）。三个分量一律过 rememberUpdatedState。
 */
@Composable
internal fun ColorWheel(
    hue: Float,
    sat: Float,
    vel: Float,
    onChange: (Float, Float, Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val hueState = rememberUpdatedState(hue)
    val satState = rememberUpdatedState(sat)
    val velState = rememberUpdatedState(vel)
    val cur = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, vel)))
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(150.dp)
            .clip(RoundedCornerShape(14.dp))
            .pointerInput(Unit) {
                detectTapGestures { pos ->
                    onChange(
                        hueState.value,
                        (pos.x / size.width).coerceIn(0f, 1f),
                        (1f - pos.y / size.height).coerceIn(0f, 1f)
                    )
                }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    change.consume()
                    onChange(
                        hueState.value,
                        (change.position.x / size.width).coerceIn(0f, 1f),
                        (1f - change.position.y / size.height).coerceIn(0f, 1f)
                    )
                }
            }
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(listOf(Color.White, Color.hsv(hue, 1f, 1f)))
                )
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
        )
        Box(
            Modifier
                .offset(x = maxWidth * sat - 9.dp, y = maxHeight * (1f - vel) - 9.dp)
                .size(18.dp)
                .clip(CircleShape)
                .background(cur.copy(alpha = 0.4f))
        )
        Box(
            Modifier
                .offset(x = maxWidth * sat - 9.dp, y = maxHeight * (1f - vel) - 9.dp)
                .size(18.dp)
                .border(2.5.dp, Color.White, CircleShape)
        )
    }
    // 色相条
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .height(16.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Brush.horizontalGradient(PickerHueColors))
            .pointerInput(Unit) {
                detectTapGestures { pos ->
                    onChange(
                        (pos.x / size.width * 360f).coerceIn(0f, 359.9f),
                        satState.value,
                        velState.value
                    )
                }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    change.consume()
                    onChange(
                        (change.position.x / size.width * 360f).coerceIn(0f, 359.9f),
                        satState.value,
                        velState.value
                    )
                }
            }
    ) {
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .offset(x = maxWidth * (hue / 360f) - 10.dp)
                .size(20.dp)
                .clip(CircleShape)
                .background(Color.hsv(hue, 1f, 1f))
                .border(2.5.dp, Color.White, CircleShape)
        )
    }
}

/**
 * 单色盘弹层（液态玻璃材质，弹窗内降级磨砂——禁自引用折射防崩溃）。
 * 与设置「主题色」弹层共用 [ColorWheel]，但**只有单槽**：拖色盘即预览，
 * 「用这个颜色」= 直接确认回调（无槽位选择、无保存到槽的中间步骤）——
 * 引导名片步与「我的档案」编辑的场景只有一个底色要改。
 */
@Composable
fun SingleColorPickerDialog(
    backdrop: LayerBackdrop,
    initialHex: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    title: String = "自定义颜色",
    hint: String = "拖动选色，确认后作为头像底色"
) {
    val init = parseHexColor(initialHex) ?: Color(0xFF5B8DEF)
    val initHsv = FloatArray(3).also { android.graphics.Color.colorToHSV(init.toArgb(), it) }
    var hue by remember { mutableFloatStateOf(initHsv[0]) }
    var sat by remember { mutableFloatStateOf(initHsv[1]) }
    var vel by remember { mutableFloatStateOf(initHsv[2]) }
    val cur = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, vel)))
    val hexText = cur.toHex()

    CompositionLocalProvider(LocalGlassRefract provides false) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.30f))
                .clickable(
                    interactionSource = null,
                    indication = null,
                    onClick = onDismiss
                ),
            contentAlignment = Alignment.Center
        ) {
            GlassPanel(
                backdrop = backdrop,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 22.dp),
                radius = 28.dp,
                lensRadius = GlassTuning.lensHeight.dp,
                lensAmount = GlassTuning.lensAmount.dp,
                depthEffect = GlassTuning.depthEffect,
                surfaceAlpha = 0.92f,
                blurRadius = 28.dp,
                chromaticAberration = GlassTuning.ca
            ) {
                Column(
                    Modifier
                        .clickable(interactionSource = null, indication = null) {}
                        .padding(horizontal = 16.dp)
                        .padding(top = 16.dp, bottom = 14.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            title,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            hexText,
                            style = MaterialTheme.typography.labelMedium,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        hint,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp, bottom = 10.dp)
                    )
                    ColorWheel(
                        hue = hue, sat = sat, vel = vel,
                        onChange = { h, s, v -> hue = h; sat = s; vel = v }
                    )
                    // 预览：当前色大圆点
                    Row(
                        Modifier.padding(top = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(cur)
                                .border(1.5.dp, MaterialTheme.colorScheme.background, CircleShape)
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "这就是头像底色的效果",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // 取消=表面液态按钮（无着色）；确认=主色实底（按钮铁律）
                        LiquidGlassButton(
                            onClick = onDismiss,
                            backdrop = backdrop,
                            shape = RoundedCornerShape(percent = 50),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                "取消",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f),
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                            )
                        }
                        LiquidGlassButton(
                            onClick = { onConfirm(hexText) },
                            backdrop = backdrop,
                            shape = RoundedCornerShape(percent = 50),
                            modifier = Modifier.weight(1f),
                            surfaceColor = MaterialTheme.colorScheme.primary
                        ) {
                            Text(
                                "用这个颜色",
                                color = MaterialTheme.colorScheme.onPrimary,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
