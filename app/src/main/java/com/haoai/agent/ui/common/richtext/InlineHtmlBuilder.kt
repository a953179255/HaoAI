package com.haoai.agent.ui.common.richtext

import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/**
 * 行内 DOM 节点序列 → AnnotatedString（+ 行内公式占位的 InlineTextContent）。
 *
 * 对应 上游 appendHtmlInlineNode / HtmlInlineGroup 的精简版：
 * b/strong、i/em、del/s、u、code、a、span.math(inline)、font 前景色。
 * 未知标签递归子节点（安全面：只映射白名单样式，不解析任意 CSS）。
 *
 * LaTeX 公式在 HTML 中为 <span class="math inline">（fork HtmlGenerator 产物），
 * 以 InlineTextContent 占位（宽度按 JLatexMath 预测量，见 MathInline.kt）。
 */
object InlineHtmlBuilder {

    /** 一次行内构建的结果：文本 + 行内占位内容。 */
    data class Result(
        val text: AnnotatedString,
        val inlineContents: Map<String, InlineTextContent>
    )

    fun build(
        nodes: List<Node>,
        colors: InlineColors,
        formulaFontSizeSp: Float
    ): Result {
        val contents = LinkedHashMap<String, InlineTextContent>()
        val text = buildAnnotatedString {
            appendNodes(nodes, colors, formulaFontSizeSp, contents)
        }
        return Result(text, contents)
    }

    private fun AnnotatedString.Builder.appendNodes(
        nodes: List<Node>,
        colors: InlineColors,
        formulaSp: Float,
        contents: MutableMap<String, InlineTextContent>
    ) {
        for (node in nodes) {
            when (node) {
                is TextNode -> append(node.text())
                is Element -> appendElement(node, colors, formulaSp, contents)
            }
        }
    }

    private fun AnnotatedString.Builder.appendElement(
        el: Element,
        colors: InlineColors,
        formulaSp: Float,
        contents: MutableMap<String, InlineTextContent>
    ) {
        // 行内公式：占位 + JLatexMath 渲染（MathInline.kt 提供 composable）
        if (el.hasClass("math") && el.attr("inline") == "true") {
            val formula = el.text()
            if (formula.isNotBlank()) {
                val size = MathText.assumeInlineSize(formula, formulaSp)
                contents.putIfAbsent(
                    formula,
                    InlineTextContent(
                        placeholder = androidx.compose.ui.text.Placeholder(
                            width = size.widthSp,
                            height = size.heightSp,
                            placeholderVerticalAlign =
                            androidx.compose.ui.text.PlaceholderVerticalAlign.TextCenter
                        ),
                        children = { MathInline(latex = formula, fontSize = formulaSp.sp) }
                    )
                )
                appendInlineContent(formula)
            }
            return
        }

        when (el.tagName().lowercase()) {
            "b", "strong" -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                appendNodes(el.childNodes(), colors, formulaSp, contents)
            }

            "i", "em" -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                appendNodes(el.childNodes(), colors, formulaSp, contents)
            }

            "del", "s", "strike" -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                appendNodes(el.childNodes(), colors, formulaSp, contents)
            }

            "u" -> withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) {
                appendNodes(el.childNodes(), colors, formulaSp, contents)
            }

            "code" -> withStyle(
                SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    background = colors.inlineCodeBg
                )
            ) {
                append(' ')
                append(el.text())
                append(' ')
            }

            "br" -> append('\n')

            "a" -> {
                val href = el.attr("href")
                if (href.isNotEmpty()) {
                    pushStyle(SpanStyle(color = colors.link, textDecoration = TextDecoration.Underline))
                    appendNodes(el.childNodes(), colors, formulaSp, contents)
                    pop()
                } else {
                    appendNodes(el.childNodes(), colors, formulaSp, contents)
                }
            }

            "img" -> {
                // 行内图片降级为 alt 文本（块级图片由块级渲染器处理）
                val alt = el.attr("alt")
                if (alt.isNotEmpty()) append("[图像: $alt]")
            }

            else -> appendNodes(el.childNodes(), colors, formulaSp, contents)
        }
    }
}

/** 行内渲染配色（由主题解析，避免在构建函数里读 CompositionLocal）。 */
data class InlineColors(
    val link: Color,
    val inlineCodeBg: Color
)
