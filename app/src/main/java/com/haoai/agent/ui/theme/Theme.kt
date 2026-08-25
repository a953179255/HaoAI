package com.haoai.agent.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val HaoDarkColors = darkColorScheme(
    primary = Color(0xFF8AB4FF),
    onPrimary = Color(0xFF0A1B33),
    primaryContainer = Color(0xFF27436E),
    onPrimaryContainer = Color(0xFFD6E4FF),
    secondary = Color(0xFF9FCBFF),
    onSecondary = Color(0xFF0A1B33),
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
    primary = Color(0xFF2C5FC4),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD9E2FF),
    onPrimaryContainer = Color(0xFF0A1B33),
    secondary = Color(0xFF4A608C),
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

@Composable
fun HaoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) HaoDarkColors else HaoLightColors,
        content = content
    )
}
