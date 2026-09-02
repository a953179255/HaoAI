package com.haoai.agent.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext

/**
 * 主题种子色板：每颗种子覆盖浅/深两套 primary 系 + secondary，
 * 其余中性色沿用液态玻璃底（深蓝黑/浅灰蓝），保证玻璃观感一致。
 */
data class SeedPalette(
    val label: String,
    val lightPrimary: Color, val lightOnPrimary: Color,
    val lightContainer: Color, val lightOnContainer: Color,
    val darkPrimary: Color, val darkOnPrimary: Color,
    val darkContainer: Color, val darkOnContainer: Color,
    val lightSecondary: Color, val darkSecondary: Color
)

/** 预设主题色（index 即 SettingsStore.themeSeed）。 */
val THEME_SEEDS: List<SeedPalette> = listOf(
    SeedPalette(
        "液态玻璃绿",
        Color(0xFF1EA84F), Color(0xFFFFFFFF), Color(0xFFD5F5E1), Color(0xFF0B3D22),
        Color(0xFF4ADE80), Color(0xFF062D16), Color(0xFF14532D), Color(0xFFBBF7D0),
        Color(0xFF2F7D4F), Color(0xFF86EFAC)
    ),
    SeedPalette(
        "海洋蓝",
        Color(0xFF1B66C9), Color(0xFFFFFFFF), Color(0xFFD3E4FF), Color(0xFF001C3B),
        Color(0xFF9FC3FF), Color(0xFF003062), Color(0xFF00478E), Color(0xFFD3E4FF),
        Color(0xFF545F70), Color(0xFFBBC7DB)
    ),
    SeedPalette(
        "典雅紫",
        Color(0xFF7B4BC8), Color(0xFFFFFFFF), Color(0xFFEADDFF), Color(0xFF25005A),
        Color(0xFFD6BBFF), Color(0xFF3F1B80), Color(0xFF57329E), Color(0xFFEADDFF),
        Color(0xFF655B70), Color(0xFFCFC2DC)
    ),
    SeedPalette(
        "暖阳橙",
        Color(0xFF8F4C00), Color(0xFFFFFFFF), Color(0xFFFFDCC2), Color(0xFF2E1500),
        Color(0xFFFFB77C), Color(0xFF4E2600), Color(0xFF6F3A00), Color(0xFFFFDCC2),
        Color(0xFF74593F), Color(0xFFE3C0A4)
    ),
    SeedPalette(
        "樱花粉",
        Color(0xFF984061), Color(0xFFFFFFFF), Color(0xFFFFD9E2), Color(0xFF3E001D),
        Color(0xFFFFB1C8), Color(0xFF5E1133), Color(0xFF7B2949), Color(0xFFFFD9E2),
        Color(0xFF74565F), Color(0xFFE3BDC7)
    ),
    SeedPalette(
        "珊瑚红",
        Color(0xFF9C4145), Color(0xFFFFFFFF), Color(0xFFFFDAD6), Color(0xFF410002),
        Color(0xFFFFB4AB), Color(0xFF5F1512), Color(0xFF7E2A2E), Color(0xFFFFDAD6),
        Color(0xFF775652), Color(0xFFFFDAD5)
    ),
    SeedPalette(
        "青碧",
        Color(0xFF00696D), Color(0xFFFFFFFF), Color(0xFF6FF6FB), Color(0xFF002022),
        Color(0xFF4CDADE), Color(0xFF003739), Color(0xFF004F53), Color(0xFF6FF6FB),
        Color(0xFF4A6365), Color(0xFFB1CBCD)
    ),
    SeedPalette(
        "天青",
        Color(0xFF006780), Color(0xFFFFFFFF), Color(0xFFB4EBFF), Color(0xFF001F2A),
        Color(0xFF5CD5F4), Color(0xFF003544), Color(0xFF004D61), Color(0xFFB4EBFF),
        Color(0xFF4C616B), Color(0xFFB3C9D4)
    )
)

private val HaoDarkColors = darkColorScheme(
    primary = Color(0xFF4ADE80),
    onPrimary = Color(0xFF062D16),
    primaryContainer = Color(0xFF14532D),
    onPrimaryContainer = Color(0xFFBBF7D0),
    secondary = Color(0xFF86EFAC),
    onSecondary = Color(0xFF062D16),
    tertiary = Color(0xFF7BD88F),
    background = Color(0xFF0B0E14),
    onBackground = Color(0xFFE6EBF5),
    surface = Color(0xFF10141E),
    onSurface = Color(0xFFE6EBF5),
    surfaceVariant = Color(0xFF1A2130),
    onSurfaceVariant = Color(0xFFA9B4C8),
    outline = Color(0xFF454F63),
    error = Color(0xFFFF867C),
    onError = Color(0xFF37080A)
)

private val HaoLightColors = lightColorScheme(
    primary = Color(0xFF1EA84F),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD5F5E1),
    onPrimaryContainer = Color(0xFF0B3D22),
    secondary = Color(0xFF2F7D4F),
    onSecondary = Color(0xFFFFFFFF),
    tertiary = Color(0xFF256B41),
    background = Color(0xFFF5F7FC),
    onBackground = Color(0xFF171B26),
    surface = Color(0xFFFDFEFF),
    onSurface = Color(0xFF171B26),
    surfaceVariant = Color(0xFFE3E8F4),
    onSurfaceVariant = Color(0xFF49526A),
    outline = Color(0xFF79839C),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF)
)

/** AMOLED 纯黑底（上游 同款思路：只换中性底色，主色保持）。 */
private val AmoledBlack = Color(0xFF000000)
private val AmoledSurface = Color(0xFF0A0A0C)

@Composable
fun HaoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    seedIndex: Int = 0,
    amoled: Boolean = false,
    wallpaper: android.graphics.Bitmap? = null,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    var colorScheme = when {
        // 动态取色，优先级：App 聊天壁纸主色（用户直接可见，换壁纸配色即变）
        // → 系统壁纸 Material You（Android 12+）→ 种子色板
        dynamicColor && wallpaper != null ->
            if (darkTheme) schemeFromSeed(wallpaperSeedColor(wallpaper), dark = true)
            else schemeFromSeed(wallpaperSeedColor(wallpaper), dark = false)
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        else -> {
            val base = if (darkTheme) HaoDarkColors else HaoLightColors
            val seed = THEME_SEEDS.getOrElse(seedIndex) { THEME_SEEDS[0] }
            if (seedIndex == 0) base else base.copy(
                primary = if (darkTheme) seed.darkPrimary else seed.lightPrimary,
                onPrimary = if (darkTheme) seed.darkOnPrimary else seed.lightOnPrimary,
                primaryContainer = if (darkTheme) seed.darkContainer else seed.lightContainer,
                onPrimaryContainer = if (darkTheme) seed.darkOnContainer else seed.lightOnContainer,
                secondary = if (darkTheme) seed.darkSecondary else seed.lightSecondary,
                onSecondary = if (darkTheme) Color(0xFF06121E) else Color(0xFFFFFFFF),
                tertiary = if (darkTheme) seed.darkPrimary else seed.lightPrimary
            )
        }
    }
    if (darkTheme && amoled) {
        colorScheme = colorScheme.copy(
            background = AmoledBlack,
            surface = AmoledSurface,
            surfaceVariant = Color(0xFF121217)
        )
    }
    // 壁纸深浅标志：叠在壁纸上的小字据此自适应灰度（见 wallpaperAdaptiveGray）
    val wallpaperDark = wallpaper?.let { wp -> remember(wp) { wallpaperIsDark(wp) } }
    CompositionLocalProvider(LocalWallpaperDark provides wallpaperDark) {
        MaterialTheme(colorScheme = colorScheme, content = content)
    }
}

/** 壁纸是否偏暗：HaoTheme 计算一次，供全树读取；null = 未设壁纸。 */
val LocalWallpaperDark = staticCompositionLocalOf<Boolean?> { null }

/**
 * 壁纸平均亮度判深浅：与 wallpaperSeedColor 同款 48×48 降采样，
 * Rec.709 相对亮度求均值，阈值 0.5。
 */
fun wallpaperIsDark(bitmap: android.graphics.Bitmap): Boolean {
    val small = android.graphics.Bitmap.createScaledBitmap(bitmap, 48, 48, true)
    var sum = 0f
    for (y in 0 until 48) for (x in 0 until 48) {
        val c = small.getPixel(x, y)
        sum += (0.2126f * android.graphics.Color.red(c) +
            0.7152f * android.graphics.Color.green(c) +
            0.0722f * android.graphics.Color.blue(c)) / 255f
    }
    return sum / (48 * 48) < 0.5f
}

/**
 * 壁纸自适应灰：统计小字等弱化的文字叠在壁纸上时，深壁纸用灰白、
 * 浅壁纸用灰黑（取另一套色板的 onSurfaceVariant，保证与背景有色差
 * 又不刺眼）；无壁纸时回退按主题底色亮度判断。
 */
@Composable
fun wallpaperAdaptiveGray(alpha: Float = 0.8f): Color {
    val onDark = LocalWallpaperDark.current
        ?: (MaterialTheme.colorScheme.background.luminance() < 0.5f)
    return (if (onDark) HaoDarkColors.onSurfaceVariant else HaoLightColors.onSurfaceVariant)
        .copy(alpha = alpha)
}

/**
 * 从聊天壁纸提取主色（轻量 Palette）：降采样后按 16 级/通道量化计数，
 * 取「饱和度 × 出现次数」加权重高的色桶，排除近灰白黑。
 */
fun wallpaperSeedColor(bitmap: android.graphics.Bitmap): Color? {
    val small = android.graphics.Bitmap.createScaledBitmap(bitmap, 48, 48, true)
    val counts = HashMap<Int, Int>()
    for (y in 0 until 48) for (x in 0 until 48) {
        val c = small.getPixel(x, y)
        val key = ((android.graphics.Color.red(c) shr 4) shl 8) or
            ((android.graphics.Color.green(c) shr 4) shl 4) or
            (android.graphics.Color.blue(c) shr 4)
        counts[key] = (counts[key] ?: 0) + 1
    }
    var best = -1
    var bestScore = 0f
    for ((k, n) in counts) {
        val r = ((k shr 8) and 0xF) * 17
        val g = ((k shr 4) and 0xF) * 17
        val b = (k and 0xF) * 17
        val mx = maxOf(r, g, b)
        val mn = minOf(r, g, b)
        if (mx == 0) continue
        val sat = (mx - mn) / mx.toFloat()
        if (sat < 0.18f) continue
        val score = sat * n
        if (score > bestScore) {
            bestScore = score
            best = k
        }
    }
    if (best < 0) return null
    return Color(
        ((best shr 8) and 0xF) * 17,
        ((best shr 4) and 0xF) * 17,
        (best and 0xF) * 17
    )
}

/** 由壁纸主色派生一套协调的 primary 系配色（HSV 明度/饱和度调制，中性色沿用玻璃底）。 */
private fun schemeFromSeed(seed: Color?, dark: Boolean) = run {
    val base = if (dark) HaoDarkColors else HaoLightColors
    if (seed == null) return@run base
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(seed.toArgb(), hsv)
    fun h(s: Float, v: Float) = Color(android.graphics.Color.HSVToColor(floatArrayOf(hsv[0], s, v)))
    if (dark) base.copy(
        primary = h(0.55f, 0.74f),
        onPrimary = h(0.60f, 0.14f),
        primaryContainer = h(0.48f, 0.32f),
        onPrimaryContainer = h(0.38f, 0.86f),
        secondary = h(0.28f, 0.68f),
        onSecondary = h(0.55f, 0.15f),
        tertiary = h(0.45f, 0.70f)
    ) else base.copy(
        primary = h(0.62f, 0.50f),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = h(0.55f, 0.88f),
        onPrimaryContainer = h(0.68f, 0.24f),
        secondary = h(0.34f, 0.42f),
        onSecondary = Color(0xFFFFFFFF),
        tertiary = h(0.55f, 0.46f)
    )
}
