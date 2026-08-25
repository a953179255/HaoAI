package com.haoai.agent.ui.common

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class MdBlock(
    val code: Boolean,
    val text: String,
    val heading: Int = 0
)

fun parseMarkdownBlocks(src: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val fence = Regex("```(\\w*)[ \\t]*\\n?([\\s\\S]*?)(?:```|$)")
    var last = 0
    for (m in fence.findAll(src)) {
        if (m.range.first > last) {
            blocks += textBlocks(src.substring(last, m.range.first))
        }
        blocks += MdBlock(code = true, text = m.groupValues[2])
        last = m.range.last + 1
    }
    if (last < src.length) blocks += textBlocks(src.substring(last))
    return blocks
}

private fun textBlocks(chunk: String): List<MdBlock> =
    chunk.split(Regex("\n[ \\t]*\n+"))
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { para ->
            val h = Regex("^#{1,6}\\s").find(para)
            val level = h?.value?.count { it == '#' } ?: 0
            MdBlock(code = false, text = if (level > 0) para.substringAfter(' ').trim() else para, heading = level)
        }

@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier) {
    val blocks = remember(text) { parseMarkdownBlocks(text) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { block ->
            if (block.code) {
                CodeBlock(block.text)
            } else {
                Text(
                    text = buildInline(block.text),
                    style = when (block.heading) {
                        1 -> MaterialTheme.typography.headlineSmall
                        2 -> MaterialTheme.typography.titleLarge
                        3 -> MaterialTheme.typography.titleMedium
                        0 -> MaterialTheme.typography.bodyMedium
                        else -> MaterialTheme.typography.titleSmall
                    },
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
        }
    }
}

@Composable
private fun CodeBlock(code: String) {
    Surface(
        color = Color(0xFF06080D).copy(alpha = 0.88f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        SelectionContainer {
            Text(
                text = code.trimEnd(),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.5.sp,
                lineHeight = 18.sp,
                color = Color(0xFFCFE1FF),
                modifier = Modifier
                    .padding(12.dp)
                    .horizontalScroll(rememberScrollState())
            )
        }
    }
}

private val inlineRegex = Regex(
    "`([^`\n]+)`" +
        "|\\*\\*([^*\n]+)\\*\\*" +
        "|__([^_\n]+)__" +
        "|(?<![*\\w])\\*([^*\n]+)\\*(?!\\*)" +
        "|~~(.+?)~~" +
        "|\\[([^\\]\n]+)]\\((https?://[^)\n]+)\\)"
)

fun buildInline(text: String): AnnotatedString = buildAnnotatedString {
    val normalizedLines = text.split('\n').joinToString("\n") { line ->
        when {
            line.startsWith("- ") || line.startsWith("* ") -> "•  " + line.substring(2)
            line.startsWith("+ ") -> "•  " + line.substring(2)
            Regex("^\\d+[.)]\\s").containsMatchIn(line) -> line
            else -> line
        }
    }

    var index = 0
    for (m in inlineRegex.findAll(normalizedLines)) {
        append(normalizedLines.substring(index, m.range.first))
        val code = m.groups[1]?.value
        val bold1 = m.groups[2]?.value
        val bold2 = m.groups[3]?.value
        val italic = m.groups[4]?.value
        val strike = m.groups[5]?.value
        val linkText = m.groups[6]?.value
        val linkUrl = m.groups[7]?.value
        when {
            code != null -> pushStyle(
                SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    background = Color(0x3A8AB4FF),
                    fontSize = 13.sp
                )
            ).also { append(code); pop() }

            bold1 != null || bold2 != null -> pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                .also { append(bold1 ?: bold2 ?: ""); pop() }

            italic != null -> pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                .also { append(italic); pop() }

            strike != null -> pushStyle(SpanStyle(textDecoration = TextDecoration.LineThrough))
                .also { append(strike); pop() }

            linkText != null && linkUrl != null -> pushStyle(
                SpanStyle(color = Color(0xFF8AB4FF), textDecoration = TextDecoration.Underline)
            ).also { append(linkText); pop() }
        }
        index = m.range.last + 1
    }
    if (index < normalizedLines.length) append(normalizedLines.substring(index))
}
