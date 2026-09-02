package com.haoai.agent.ui.common

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

/** 解析后的 Markdown 块：类型化区分，渲染端按类型分发。 */
sealed class MdBlock {
    data class Text(val text: String, val heading: Int = 0) : MdBlock()
    data class Code(val lang: String, val code: String, val closed: Boolean) : MdBlock()
    data class Mermaid(val code: String, val closed: Boolean) : MdBlock()

    /** LaTeX 公式（块级 $$...$$）。 */
    data class Math(val latex: String) : MdBlock()
    data class Table(val header: List<String>, val rows: List<List<String>>, val aligns: List<Int>) : MdBlock()
}

/** 对齐方式：0 左 / 1 中 / 2 右。 */
private const val ALIGN_LEFT = 0
private const val ALIGN_CENTER = 1
private const val ALIGN_RIGHT = 2

private val fenceLine = Regex("^```(.*)$")
private val tableDivider = Regex("^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$")
private val mathLine = Regex("^\\s*\\$\\$(.+?)\\$\\$\\s*$")

/** 解析 Markdown 为块列表；未闭合围栏标记 closed=false（流式输出兼容）。 */
fun parseMarkdownBlocks(src: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val lines = src.split('\n')
    var i = 0
    val textBuffer = StringBuilder()

    fun flushText() {
        val chunk = textBuffer.toString()
        textBuffer.setLength(0)
        chunk.split(Regex("\n[ \t]*\n+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { para ->
                val h = Regex("^#{1,6}\\s").find(para)
                val level = h?.value?.count { it == '#' } ?: 0
                blocks += MdBlock.Text(
                    text = if (level > 0) para.substringAfter(' ').trim() else para,
                    heading = level
                )
            }
    }

    fun isTableRow(line: String): Boolean =
        line.trim().startsWith("|") || (line.contains('|') && line.trim().endsWith("|"))

    while (i < lines.size) {
        val line = lines[i]

        // 1) 代码围栏 / mermaid
        val fence = fenceLine.find(line)
        if (fence != null) {
            flushText()
            val lang = fence.groupValues[1].trim().lowercase()
            val body = StringBuilder()
            i++
            var closed = false
            while (i < lines.size) {
                if (lines[i].trimEnd() == "```") {
                    closed = true
                    i++
                    break
                }
                body.append(lines[i]).append('\n')
                i++
            }
            if (lang == "mermaid") blocks += MdBlock.Mermaid(body.toString(), closed)
            else blocks += MdBlock.Code(lang, body.toString(), closed)
            continue
        }

        // 2) 块级公式：$$ 单行成对 或 $$ 起始到 $$ 结束
        val inlineMath = mathLine.find(line)
        if (inlineMath != null) {
            flushText()
            blocks += MdBlock.Math(inlineMath.groupValues[1].trim())
            i++
            continue
        }
        if (line.trim() == "$$") {
            flushText()
            val body = StringBuilder()
            i++
            while (i < lines.size) {
                if (lines[i].trim() == "$$") {
                    i++
                    break
                }
                body.append(lines[i]).append('\n')
                i++
            }
            blocks += MdBlock.Math(body.toString().trim())
            continue
        }

        // 3) 表格：当前行含 | 且下一行是 --- 分隔行
        if (isTableRow(line) && i + 1 < lines.size && tableDivider.containsMatchIn(lines[i + 1])) {
            flushText()
            fun splitRow(row: String): List<String> =
                row.trim().removePrefix("|").removeSuffix("|").split('|').map { it.trim() }
            val header = splitRow(line)
            val aligns = splitRow(lines[i + 1]).map { cell ->
                val t = cell.trim()
                when {
                    t.startsWith(":") && t.endsWith(":") -> ALIGN_CENTER
                    t.endsWith(":") -> ALIGN_RIGHT
                    else -> ALIGN_LEFT
                }
            }
            i += 2
            val rows = mutableListOf<List<String>>()
            while (i < lines.size && isTableRow(lines[i]) && lines[i].trim().isNotEmpty()) {
                val cells = splitRow(lines[i]).toMutableList()
                while (cells.size < header.size) cells.add("")
                rows.add(cells.take(header.size))
                i++
            }
            blocks += MdBlock.Table(header, rows, aligns)
            continue
        }

        // 4) 普通文本段落
        textBuffer.append(line).append('\n')
        i++
    }
    flushText()
    return blocks
}

@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier) {
    val blocks = remember(text) { parseMarkdownBlocks(text) }
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Code -> CodeBlock(block.lang, block.code, block.closed, dark)
                is MdBlock.Mermaid -> MermaidBlock(block.code, dark)
                is MdBlock.Math -> FormulaBlock(block.latex, dark)
                is MdBlock.Table -> TableBlock(block)
                is MdBlock.Text -> Text(
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

/** 代码块：顶部条（语言标签 + 复制）+ 语法高亮 + 横向滚动；流式未闭合时提示生成中。 */
@Composable
private fun CodeBlock(lang: String, code: String, closed: Boolean, dark: Boolean) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val colors = if (dark) CodeHighlight.darkColors() else CodeHighlight.lightColors()
    val bg = if (dark) Color(0xFF06080D).copy(alpha = 0.88f) else Color(0xFFF7F8FA)
    val plain = colors.plain

    Surface(
        color = bg,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = lang.ifBlank { "text" },
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = plain.copy(alpha = 0.6f)
                )
                if (!closed) {
                    Text(
                        text = "  生成中…",
                        style = MaterialTheme.typography.labelSmall,
                        color = plain.copy(alpha = 0.45f)
                    )
                }
                Spacer(Modifier.weight(1f))
                Icon(
                    imageVector = Icons.Filled.ContentCopy,
                    contentDescription = "复制代码",
                    tint = plain.copy(alpha = 0.65f),
                    modifier = Modifier
                        // 点按复制可用；长按会进入正文选择态（新版 Compose 已移除
                        // disableSelection，与 上游 行为一致，可接受）
                        .size(16.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .clickable {
                            clipboard.setText(AnnotatedString(code))
                            android.widget.Toast.makeText(context, "已复制代码", android.widget.Toast.LENGTH_SHORT).show()
                        }
                )
            }
            SelectionContainer {
                Text(
                    text = CodeHighlight.highlight(code.trimEnd('\n'), lang, colors),
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

/** 表格：简单网格渲染，超宽横向滚动，奇偶行底色区分。 */
@Composable
private fun TableBlock(table: MdBlock.Table) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val bg = if (dark) Color(0xFF141A24) else Color(0xFFF4F6FA)
    val altBg = if (dark) Color(0xFF1A2130) else Color(0xFFEAEFF7)

    Surface(
        color = bg,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            Modifier
                .horizontalScroll(rememberScrollState())
                .padding(vertical = 4.dp)
        ) {
            Row(Modifier.padding(horizontal = 8.dp)) {
                table.header.forEachIndexed { c, h ->
                    Text(
                        text = buildInline(h),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                        textAlign = alignOf(table.aligns, c),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }
            table.rows.forEachIndexed { _, cells ->
                Row(
                    Modifier
                        .padding(horizontal = 8.dp)
                        .background(altBg, RoundedCornerShape(8.dp))
                ) {
                    cells.forEachIndexed { c, cell ->
                        Text(
                            text = buildInline(cell),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.9f),
                            textAlign = alignOf(table.aligns, c),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                }
            }
        }
    }
}

private fun alignOf(aligns: List<Int>, col: Int): TextAlign = when (aligns.getOrElse(col) { ALIGN_LEFT }) {
    ALIGN_CENTER -> TextAlign.Center
    ALIGN_RIGHT -> TextAlign.Right
    else -> TextAlign.Left
}

/** 公式块：KaTeX 离线资产就绪时走 WebView 渲染，未就绪降级为浅底等宽样式。 */
@Composable
private fun FormulaBlock(latex: String, dark: Boolean) {
    val context = LocalContext.current
    val assetsReady = remember {
        runCatching { context.assets.open("katex/katex.min.js").use { true } }.getOrDefault(false)
    }
    if (!assetsReady) {
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
    } else {
        WebViewBlock(
            html = katexHtml(latex, dark),
            baseUrl = "file:///android_asset/katex/"
        )
    }
}

/** Mermaid 图表块：资产就绪走 WebView（渲染失败降级显示源码），未就绪同上。 */
@Composable
private fun MermaidBlock(code: String, dark: Boolean) {
    val context = LocalContext.current
    val assetsReady = remember {
        runCatching { context.assets.open("mermaid/mermaid.min.js").use { true } }.getOrDefault(false)
    }
    if (!assetsReady) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = code.trim(),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
                modifier = Modifier
                    .padding(12.dp)
                    .horizontalScroll(rememberScrollState())
            )
        }
    } else {
        WebViewBlock(
            html = mermaidHtml(code, dark),
            baseUrl = "file:///android_asset/mermaid/"
        )
    }
}

// ===== WebView 渲染（KaTeX / Mermaid 离线引擎，复用池上限 3 个实例）=====

private object WebViewPool {
    private const val MAX_ACTIVE = 3
    private val idle = ArrayDeque<WebView>()
    private val active = mutableListOf<WebView>()

    @Synchronized
    fun acquire(context: Context): WebView {
        // 上限保护：最旧实例直接销毁，避免长消息里公式/图表块过多把内存吃穿
        while (active.size >= MAX_ACTIVE) {
            active.removeFirst().destroy()
        }
        val w = idle.removeFirstOrNull() ?: create(context)
        active.add(w)
        return w
    }

    @Synchronized
    fun release(w: WebView) {
        active.remove(w)
        if (idle.size < MAX_ACTIVE) idle.addLast(w) else w.destroy()
    }

    private fun create(context: Context): WebView = WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        webViewClient = WebViewClient()
    }
}

/** JS→Android 高度回报桥：KaTeX/Mermaid 渲染完成后把内容高度通知 Compose 侧。 */
private class HeightBridge(private val onHeight: (Int) -> Unit) {
    @JavascriptInterface
    fun reportHeight(h: Int) {
        android.os.Handler(android.os.Looper.getMainLooper()).post { onHeight(h) }
    }
}

/** JS 字符串字面量：JSON 转义后防 </script> 提前闭合。 */
private fun jsString(s: String): String =
    kotlinx.serialization.json.JsonPrimitive(s).toString().replace("</", "<\\/")

/** HTML 文本转义：用于把不可信内容（模型输出）放进 HTML 文档的文本位。 */
private fun htmlEscape(s: String): String =
    s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

private fun katexHtml(latex: String, dark: Boolean): String {
    val lat = jsString(latex)
    val textColor = if (dark) "#E6EBF5" else "#171B26"
    return "<!DOCTYPE html><html><head><meta charset=\"utf-8\">" +
        "<link rel=\"stylesheet\" href=\"katex.min.css\">" +
        "<script src=\"katex.min.js\"></script>" +
        "<style>body{margin:0;padding:4px;background:transparent;overflow:hidden}" +
        "#content{color:" + textColor + "}</style></head><body>" +
        "<div id=\"content\"></div><script>" +
        "function renderDone(){HeightBridge.reportHeight(document.body.scrollHeight);}" +
        "try{katex.render(" + lat + ",document.getElementById('content')," +
        "{displayMode:true,throwOnError:false});}catch(e){" +
        "document.getElementById('content').textContent=" + lat + ";}" +
        "renderDone();</script></body></html>"
}

private fun mermaidHtml(code: String, dark: Boolean): String {
    val codeJs = jsString(code)
    val theme = if (dark) "dark" else "default"
    return "<!DOCTYPE html><html><head><meta charset=\"utf-8\">" +
        "<script src=\"mermaid.min.js\"></script>" +
        "<style>body{margin:0;padding:4px;background:transparent;overflow:hidden}" +
        ".mermaid svg{max-width:100%}</style></head><body>" +
        "<div class=\"mermaid\">" + htmlEscape(code) + "</div>" +
        "<script>" +
        "var failed=false;" +
        "try{mermaid.initialize({startOnLoad:false,theme:'" + theme + "'});" +
        "mermaid.run({querySelector:'.mermaid'}).catch(function(e){" +
        "document.body.textContent=" + codeJs + ";failed=true;" +
        "HeightBridge.reportHeight(document.body.scrollHeight);});}catch(e){" +
        "document.body.textContent=" + codeJs + ";}" +
        "window.addEventListener('load',function(){if(!failed)HeightBridge.reportHeight(document.body.scrollHeight);});" +
        "</script></body></html>"
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun WebViewBlock(html: String, baseUrl: String) {
    val context = LocalContext.current
    val density = LocalDensity.current
    var heightDp by remember { mutableIntStateOf(72) }

    AndroidView(
        modifier = Modifier
            .fillMaxWidth()
            .height(heightDp.dp),
        factory = { ctx ->
            val wv = WebViewPool.acquire(ctx)
            wv.addJavascriptInterface(
                HeightBridge { h ->
                    if (h in 1..4000) heightDp = h
                },
                "HeightBridge"
            )
            wv
        },
        update = { wv ->
            if (wv.tag != html) {
                wv.tag = html
                wv.loadDataWithBaseURL(baseUrl, html, "text/html", "utf-8", null)
            }
        },
        onRelease = { wv -> WebViewPool.release(wv) }
    )
    // context 仅由池内创建使用；显式引用避免未使用告警
    check(context != null)
}

private val inlineRegex = Regex(
    "`([^`\n]+)`" +
        "|\\*\\*([^*\n]+)\\*\\*" +
        "|__([^_\n]+)__" +
        "|(?<![*\\w])\\*([^*\n]+)\\*(?!\\*)" +
        "|~~(.+?)~~" +
        "|\\$([^$\n]+?)\\$(?!\\$)" +
        "|\\[([^\\]\n]+)]\\((https?://[^)\n]+)\\)"
)

/** 行内样式：code/粗/斜/删除线/链接 + 行内公式降级（浅底等宽，KaTeX 只做块级）。 */
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
        val inlineMath = m.groups[6]?.value
        val linkText = m.groups[7]?.value
        val linkUrl = m.groups[8]?.value
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

            // 行内公式降级：浅底等宽斜体（内联不嵌 WebView，保证行内排版稳定）
            inlineMath != null -> pushStyle(
                SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    fontStyle = FontStyle.Italic,
                    background = Color(0x2A7A8CA8)
                )
            ).also { append(inlineMath); pop() }

            linkText != null && linkUrl != null -> pushStyle(
                SpanStyle(color = Color(0xFF8AB4FF), textDecoration = TextDecoration.Underline)
            ).also { append(linkText); pop() }
        }
        index = m.range.last + 1
    }
    if (index < normalizedLines.length) append(normalizedLines.substring(index))
}
