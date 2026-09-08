package com.haoai.agent.ui.common.richtext

import android.graphics.Rect
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.takeOrElse
import ru.noties.jlatexmath.JLatexMathDrawable

/**
 * JLatexMath 原生公式渲染（方案 A）：块级 MathBlock / 行内 MathInline。
 * 渲染失败（非法 LaTeX）自动降级为等宽文本显示源码，不抛异常不出空白。
 */
object MathText {

    /** 行内公式占位尺寸预测量（InlineTextContent 需要提前知道宽高）。 */
    fun assumeInlineSize(latex: String, fontSizeSp: Float): SizeSp {
        val rect = runCatching {
            JLatexMathDrawable.builder(latex)
                .textSize(fontSizeSp)
                .padding(0)
                .build()
                .bounds
        }.getOrElse { Rect(0, 0, 0, 0) }
        return SizeSp(rect.width().toFloat().sp, rect.height().toFloat().sp)
    }

    data class SizeSp(val widthSp: TextUnit, val heightSp: TextUnit)

    fun buildDrawable(
        latex: String,
        fontSizePx: Float,
        color: Int,
        background: Int
    ): JLatexMathDrawable? = runCatching {
        JLatexMathDrawable.builder(latex)
            .textSize(fontSizePx)
            .color(color)
            .background(background)
            .padding(0)
            .align(JLatexMathDrawable.ALIGN_LEFT)
            .build()
    }.onFailure { it.printStackTrace() }.getOrNull()
}

/** 块级公式：居中 + 超宽横向滚动。 */
@Composable
fun MathBlock(
    latex: String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = TextUnit.Unspecified,
    color: Color = Color.Unspecified
) {
    val style = LocalTextStyle.current
    val resolvedFontSize = fontSize.takeOrElse { style.fontSize }
    val density = LocalDensity.current
    val fg = androidx.compose.material3.LocalContentColor.current.let { if (color != Color.Unspecified) color else it }

    val drawable = remember(latex, resolvedFontSize, fg) {
        with(density) {
            MathText.buildDrawable(
                latex = latex,
                fontSizePx = resolvedFontSize.toPx(),
                color = fg.toArgb(),
                background = android.graphics.Color.TRANSPARENT
            )
        }
    }

    if (drawable != null) {
        Box(
            modifier = modifier
                .horizontalScroll(rememberScrollState())
                .padding(vertical = 4.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            with(density) {
                Canvas(
                    modifier = Modifier.size(
                        width = drawable.bounds.width().toDp(),
                        height = drawable.bounds.height().toDp()
                    )
                ) {
                    drawable.draw(drawContext.canvas.nativeCanvas)
                }
            }
        }
    } else {
        // 非法 LaTeX：源码降级
        Text(
            text = latex,
            fontFamily = FontFamily.Monospace,
            fontSize = resolvedFontSize,
            modifier = modifier
                .horizontalScroll(rememberScrollState())
                .padding(vertical = 4.dp)
        )
    }
}

/** 行内公式：InlineTextContent 子内容，按占位尺寸绘制。 */
@Composable
fun MathInline(
    latex: String,
    fontSize: TextUnit,
    modifier: Modifier = Modifier
) {
    val style = LocalTextStyle.current
    val resolved = fontSize.takeOrElse { style.fontSize }
    val density = LocalDensity.current
    val fg = androidx.compose.material3.LocalContentColor.current

    val drawable = remember(latex, resolved, fg) {
        with(density) {
            MathText.buildDrawable(
                latex = latex,
                fontSizePx = resolved.toPx(),
                color = fg.toArgb(),
                background = android.graphics.Color.TRANSPARENT
            )
        }
    }

    if (drawable != null) {
        with(density) {
            Canvas(
                modifier = modifier.size(
                    width = drawable.bounds.width().toDp(),
                    height = drawable.bounds.height().toDp()
                )
            ) {
                drawable.draw(drawContext.canvas.nativeCanvas)
            }
        }
    } else {
        Text(text = latex, fontFamily = FontFamily.Monospace, fontSize = resolved, modifier = modifier)
    }
}
