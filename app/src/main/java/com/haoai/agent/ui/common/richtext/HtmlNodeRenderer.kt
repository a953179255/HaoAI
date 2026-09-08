package com.haoai.agent.ui.common.richtext

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.haoai.agent.ui.common.CodeHighlight
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/**
 * 方案 A 渲染管线第二段：Jsoup DOM 树 → Compose 原生组件（递归）。
 *
 * 块级标签白名单映射（对齐 上游 HtmlBodyNode）：
 * p / h1-h6 / ul / ol / li(含 task-list-item 勾选框) / pre>code / blockquote /
 * table / hr / div / span.math(块级)。未知标签递归子节点。
 * 行内序列交给 [InlineHtmlBuilder]。
 */
object HtmlNodeRenderer {

    @Composable
    fun renderNode(node: Node) {
        when (node) {
            is TextNode -> {
                val t = node.text()
                if (t.isNotBlank()) Text(text = t, style = MaterialTheme.typography.bodyMedium)
            }
            is Element -> renderElement(node)
        }
    }

    @Composable
    private fun renderElement(el: Element) {
        when (el.tagName().lowercase()) {
            "p" -> Paragraph(el)

            "h1", "h2", "h3", "h4", "h5", "h6" -> Heading(el)

            "ul" -> ListBlock(el, ordered = false)
            "ol" -> ListBlock(el, ordered = true)

            "pre" -> el.selectFirst("code")?.let { CodeBlock(el, it) }

            "blockquote" -> Blockquote(el)

            "table" -> TableBlock(el)

            "hr" -> androidx.compose.material3.HorizontalDivider(
                modifier = Modifier.padding(vertical = 8.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
                thickness = 0.5.dp
            )

            "span" -> {
                if (el.hasClass("math") && el.attr("inline") != "true") {
                    MathBlock(latex = el.text(), modifier = Modifier.fillMaxWidth())
                } else {
                    InlineSequence(listOf(el))
                }
            }

            "img" -> {
                val alt = el.attr("alt")
                if (alt.isNotEmpty()) {
                    Text("[图像: $alt]", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            "div", "body", "html" -> Column {
                el.childNodes().forEach { renderNode(it) }
            }

            else -> Column {
                el.childNodes().forEach { renderNode(it) }
            }
        }
    }

    // ---------- 段落 / 标题 ----------

    @Composable
    private fun Paragraph(el: Element) {
        InlineSequence(el.childNodes())
    }

    @Composable
    private fun Heading(el: Element) {
        val level = el.tagName().substring(1).toIntOrNull() ?: 1
        val style = when (level) {
            1 -> MaterialTheme.typography.headlineSmall
            2 -> MaterialTheme.typography.titleLarge
            3 -> MaterialTheme.typography.titleMedium
            4 -> MaterialTheme.typography.titleSmall
            else -> MaterialTheme.typography.bodyLarge
        }
        val inline = rememberInline(el.childNodes())
        Text(
            text = inline.text,
            inlineContent = inline.inlineContents,
            style = style,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
        )
    }

    // ---------- 列表（真结构：递归嵌套 + 任务勾选框） ----------

    @Composable
    private fun ListBlock(el: Element, ordered: Boolean, level: Int = 0) {
        val bulletBase = when (level % 3) {
            0 -> "•"; 1 -> "◦"; else -> "▪"
        }
        var orderedIndex = 1
        Column(
            modifier = Modifier.padding(start = (level * 10).dp, top = 2.dp, bottom = 2.dp)
        ) {
            el.children().forEach { item ->
                if (item.tagName().lowercase() != "li") return@forEach
                val isTask = item.hasClass("task-list-item")
                val checkbox = item.selectFirst("input[type=checkbox]")
                val checked = checkbox?.hasAttr("checked") == true

                Row(verticalAlignment = Alignment.Top) {
                    if (isTask && checkbox != null) {
                        // 任务列表：原生勾选框（上游 同款视觉）
                        Box(
                            Modifier
                                .padding(end = 6.dp, top = 3.dp)
                                .size(15.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(
                                    MaterialTheme.colorScheme.primary.copy(
                                        alpha = if (checked) 0.18f else 0.08f
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            if (checked) {
                                Text("✓", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold)
                            }
                        }
                    } else {
                        Text(
                            text = if (ordered) "${orderedIndex++}. " else "$bulletBase ",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(end = 6.dp)
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        // 直接子内容（排除嵌套 ul/ol 和 checkbox input）
                        val direct = item.childNodes().filter { n ->
                            !(n is Element && (n.tagName().lowercase() in listOf("ul", "ol") ||
                                    (n.tagName().lowercase() == "input" && n.attr("type") == "checkbox")))
                        }
                        direct.chunkedInline().forEach { group ->
                            InlineSequence(group)
                        }
                        // 嵌套列表递归
                        item.children().forEach { child ->
                            when (child.tagName().lowercase()) {
                                "ul" -> ListBlock(child, ordered = false, level = level + 1)
                                "ol" -> ListBlock(child, ordered = true, level = level + 1)
                            }
                        }
                    }
                }
            }
        }
    }

    /** 把混合行内节点序列按连续段分组（块级子元素自成分组）。 */
    private fun List<Node>.chunkedInline(): List<List<Node>> {
        val groups = mutableListOf<MutableList<Node>>()
        for (n in this) {
            val isBlock = n is Element && n.tagName().lowercase() in listOf("p", "pre", "table", "blockquote")
            if (isBlock) groups.add(mutableListOf(n))
            else groups.lastOrNull()?.add(n) ?: groups.add(mutableListOf(n))
        }
        return groups
    }

    // ---------- 代码块 ----------

    @Composable
    private fun CodeBlock(pre: Element, codeEl: Element) {
        val language = codeEl.classNames()
            .firstOrNull { it.startsWith("language-") }
            ?.removePrefix("language-") ?: "text"
        val code = codeEl.wholeText().trimEnd('\n')
        val dark = MaterialTheme.colorScheme.background.luminanceDark()
        val colors = if (dark) CodeHighlight.darkColors() else CodeHighlight.lightColors()
        val bg = if (dark) Color(0xFF06080D).copy(alpha = 0.88f) else Color(0xFFF7F8FA)
        val highlighted = remember(code, language, colors) {
            CodeHighlight.highlightCached(code, language, colors)
        }
        val clipboard = LocalClipboardManager.current
        val context = LocalContext.current

        Surface(
            color = bg,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
        ) {
            Column {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = language,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = colors.plain.copy(alpha = 0.6f)
                    )
                    Spacer(Modifier.weight(1f))
                    Icon(
                        imageVector = Icons.Filled.ContentCopy,
                        contentDescription = "复制代码",
                        tint = colors.plain.copy(alpha = 0.65f),
                        modifier = Modifier
                            .size(16.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .clickable {
                                clipboard.setText(AnnotatedString(code))
                                android.widget.Toast.makeText(
                                    context, "已复制代码", android.widget.Toast.LENGTH_SHORT
                                ).show()
                            }
                    )
                }
                SelectionContainer {
                    Text(
                        text = highlighted,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.5.sp,
                        lineHeight = 18.sp,
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 10.dp)
                            .horizontalScroll(rememberScrollState())
                    )
                }
            }
        }
    }

    // ---------- 引用块 ----------

    @Composable
    private fun Blockquote(el: Element) {
        val borderColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
        val bgColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp)
                .background(bgColor, RoundedCornerShape(4.dp))
                .padding(8.dp)
                .drawBehind {
                    // 左侧竖条：填充 Row 左缘 3dp
                    drawRect(
                        color = borderColor,
                        size = androidx.compose.ui.geometry.Size(6f, size.height)
                    )
                }
        ) {
            Column(Modifier.padding(start = 5.dp)) {
                el.childNodes().forEach { renderNode(it) }
            }
        }
    }

    // ---------- 表格 ----------

    @Composable
    private fun TableBlock(el: Element) {
        val headerCells = el.select("thead tr th").ifEmpty { el.select("thead tr td") }
        val firstBodyRow = el.select("tbody tr").firstOrNull()
        val bodyRowCount = el.select("tbody tr").size
        val columnCount = headerCells.size.takeIf { it > 0 }
            ?: firstBodyRow?.select("td")?.size
            ?: return

        val headers: List<@Composable () -> Unit> = List(columnCount) { c ->
            {
                if (c < headerCells.size) {
                    val cell = headerCells[c]
                    val inline = rememberInline(cell.childNodes())
                    Text(
                        text = inline.text,
                        inlineContent = inline.inlineContents,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
            }
        }

        val bodyRows = el.select("tbody tr")
        val rows: List<List<@Composable () -> Unit>> = bodyRows.map { tr ->
            val cells = tr.select("td")
            List(columnCount) { c ->
                {
                    if (c < cells.size) {
                        val inline = rememberInline(cells[c].childNodes())
                        Text(
                            text = inline.text,
                            inlineContent = inline.inlineContents,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.9f)
                        )
                    }
                }
            }
        }

        DataTable(
            headers = headers,
            rows = rows,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp)
        )
    }

    // ---------- 行内序列 ----------

    @Composable
    private fun InlineSequence(nodes: List<Node>) {
        val inline = rememberInline(nodes)
        Text(
            text = inline.text,
            inlineContent = inline.inlineContents,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(vertical = 2.dp)
        )
    }

    @Composable
    private fun rememberInline(nodes: List<Node>): InlineHtmlBuilder.Result {
        val dark = MaterialTheme.colorScheme.background.luminanceDark()
        val colors = InlineColors(
            link = MaterialTheme.colorScheme.primary,
            inlineCodeBg = if (dark) Color(0x3AFFFFFF) else Color(0x14000000)
        )
        return remember(nodes.toKey(), colors) {
            InlineHtmlBuilder.build(nodes, colors, 15f)
        }
    }

    /** 节点序列 → 稳定缓存键（outerHtml 拼接）。 */
    private fun List<Node>.toKey(): String =
        joinToString("") { if (it is Element) it.outerHtml() else it.toString() }

    // ---------- 工具 ----------

    private fun Color.luminanceDark(): Boolean =
        (0.299f * red + 0.587f * green + 0.114f * blue) < 0.5f
}
