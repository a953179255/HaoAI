package com.haoai.agent.ui.common

import android.graphics.Color as AwtColor
import android.graphics.drawable.Drawable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import ru.noties.jlatexmath.JLatexMathDrawable

/**
 * v2.1 公式原生渲染（上游 同引擎 JLatexMath）：替代 KaTeX+WebView 管线。
 * 根因回顾：WebView 池上限 3 → 公式密集消息互相销毁出现空白；固定 fillMaxWidth
 * 裁掉超宽公式；流式重载闪烁。原生 Drawable 绘制零上述问题，且行内公式可以真渲染。
 * 依赖 GPL-2.0+Classpath 例外（com.github.noties:jlatexmath-android，可合法链接）。
 */

/** 块级公式：居中原生绘制，超宽横向滚动（对齐 上游 MathBlock 行为）。 */
@Composable
fun MathBlockNative(latex: String, dark: Boolean, textSizeSp: Float = 17f) {
    val textColor = if (dark) androidx.compose.ui.graphics.Color(0xFFE6EBF5) else androidx.compose.ui.graphics.Color(0xFF171B26)
    val bg = if (dark) AwtColor.TRANSPARENT else AwtColor.TRANSPARENT
    // Drawable 构建是纯 CPU 计算（TeX 排版），按 latex+配色 remember 缓存
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val drawable = remember(latex, dark, textSizeSp) {
        buildMathDrawable(latex, textColor.toArgb(), bg, textSizeSp * density, context)
    }
    if (drawable == null) {
        // 非法 LaTeX 降级：源码文本（上游 LatexText 同款回退）
        FormulaFallback(latex, dark)
        return
    }
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .horizontalScroll(rememberScrollState())
    ) {
        Box(Modifier.padding(horizontal = 12.dp)) {
            MathDrawableView(drawable)
        }
    }
}

/** 行内公式：baseline 对齐的原生小图（宽度自适应，不截断）。 */
@Composable
fun MathInlineNative(latex: String, dark: Boolean, textSizeSp: Float = 15f) {
    val textColor = if (dark) androidx.compose.ui.graphics.Color(0xFFE6EBF5) else androidx.compose.ui.graphics.Color(0xFF171B26)
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val drawable = remember(latex, dark, textSizeSp) {
        buildMathDrawable(latex, textColor.toArgb(), AwtColor.TRANSPARENT, textSizeSp * density, context)
    }
    if (drawable == null) {
        Text(
            text = latex,
            fontFamily = FontFamily.Serif,
            fontStyle = FontStyle.Italic,
            fontSize = textSizeSp.sp,
            color = if (dark) androidx.compose.ui.graphics.Color(0xFFE6EBF5) else androidx.compose.ui.graphics.Color(0xFF171B26)
        )
        return
    }
    MathDrawableView(drawable)
}

@Composable
private fun MathDrawableView(drawable: Drawable) {
    AndroidView(
        modifier = Modifier.padding(vertical = 2.dp),
        factory = { ctx ->
            android.widget.ImageView(ctx).apply {
                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                adjustViewBounds = true
            }
        },
        update = { iv ->
            if (iv.tag !== drawable) {
                iv.tag = drawable
                iv.setImageDrawable(drawable)
            }
        }
    )
}

/** TeX 排版 + Drawable 构建；失败返回 null（调用端降级源码文本）。 */
private fun buildMathDrawable(
    latex: String,
    colorArgb: Int,
    bgArgb: Int,
    textSizePx: Float,
    context: android.content.Context
): Drawable? = runCatching {
    // noties 库要求初始化（字体资源经 assets 加载）；库自带 InitProvider 自动注册，
    // 显式调用幂等兜底（ContentProvider 未合并进 manifest 的构建变体防漏）
    ru.noties.jlatexmath.JLatexMathAndroid.init(context)
    JLatexMathDrawable.builder(latex)
        .textSize(textSizePx)
        .color(colorArgb)
        .background(bgArgb)
        .align(JLatexMathDrawable.ALIGN_CENTER)
        .build()
}.onFailure {
    android.util.Log.e("MathNative", "latex build failed: ${it.message} | latex=${latex.take(60)}", it)
}.getOrNull()

/** 公式渲染失败时的源码回退（等宽 + 浅底）。 */
@Composable
private fun FormulaFallback(latex: String, dark: Boolean) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = latex,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f),
            modifier = Modifier
                .padding(12.dp)
                .horizontalScroll(rememberScrollState())
        )
    }
}
