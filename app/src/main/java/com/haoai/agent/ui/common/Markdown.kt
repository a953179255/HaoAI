package com.haoai.agent.ui.common

import com.haoai.agent.R

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Web
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.withLink
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/** 解析后的 Markdown 块：类型化区分，渲染端按类型分发。 */
sealed class MdBlock {
    /** v2：类型化段落（heading 0-6）。行内是 MdInline 树，支持嵌套/可点链接/行内公式。 */
    data class Paragraph(val inlines: List<MdInline>, val heading: Int = 0) : MdBlock()

    /** v2 兼容旧文本路径的段落（内部工具/兜底用）。 */
    data class Text(val text: String, val heading: Int = 0) : MdBlock()
    data class Code(val lang: String, val code: String, val closed: Boolean) : MdBlock()
    data class Mermaid(val code: String, val closed: Boolean) : MdBlock()

    /** LaTeX 公式（块级 $$...$$）；流式未闭合 $$ 时 closed=false（⑦ 保守按永久开启处理）。 */
    data class Math(val latex: String, val closed: Boolean = true) : MdBlock()
    /** v2：类型化单元格（行内样式可进表格）。 */
    data class Table(
        val header: List<List<MdInline>>,
        val rows: List<List<List<MdInline>>>,
        val aligns: List<Int>,
        val raw: String = ""
    ) : MdBlock()

    /** 表格骨架占位（⑦）：表头已到、分隔行未齐时先占位，避免"纯文本闪现→跳变成表格"。 */
    data class TableSkeleton(val header: List<String>) : MdBlock()

    /** v2：引用块（内容是子块列表，可嵌套任意块）。 */
    data class Quote(val children: List<MdBlock>) : MdBlock()

    /** v2：列表（ordered 区分有序/无序；loose=松散列表项间距大；item.checked 非空=任务列表项）。 */
    data class ListBlock(
        val ordered: Boolean,
        val loose: Boolean,
        val items: List<Item>
    ) : MdBlock() {
        data class Item(val checked: Boolean?, val children: List<MdBlock>)
    }

    /** v2：水平分隔线 ---。 */
    data object Rule : MdBlock()

    /**
     * 独立成段的图片（![](url)）：网络 http(s) 或本地文件路径，渲染端异步解码。
     * 混在文字行里的图片仍走行内占位（🖼 alt），不在此列。
     */
    data class Image(val alt: String, val url: String) : MdBlock()
}

/** 对齐方式：0 左 / 1 中 / 2 右。 */
internal const val ALIGN_LEFT = 0
internal const val ALIGN_CENTER = 1
internal const val ALIGN_RIGHT = 2

/**
 * 流式期虚拟闭合（防 "$$\max…" 字面量闪现）：源文本尾部的未闭合 $$ 补一个
 * "$$" 让 AST 走公式分支，完成时真闭合到达自然定型。代码围栏内的 $$ 不算。
 */
fun closeUnclosedMath(src: String): String {
    var inFence = false
    var inMath = false
    for (line in src.split('\n')) {
        val trimmed = line.trim()
        if (fenceLine.matches(line)) inFence = !inFence
        else if (!inFence && trimmed == "$$") inMath = !inMath
        else if (!inFence && !inMath && mathLine.matches(line)) { /* 单行成对，不改变状态 */ }
    }
    return if (inMath) src + "\n$$" else src
}

private val fenceLine = Regex("^```(.*)$")
private val tableDivider = Regex("^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$")
private val mathLine = Regex("^\\s*\\$\\$(.+?)\\$\\$\\s*$")

/**
 * ③ 已结算边界（frozen-prefix）：返回 src 中最后一个
 * 「代码围栏与块级公式之外」的空行末尾偏移。该偏移之前的块均已定型、不会再被
 * 后续 token 改变，可冻结复用；之后的尾部文本才需要随流式重解析。
 * 返回 0 表示尚无可冻结前缀。
 */
fun settledBoundary(src: String): Int {
    var inFence = false
    var inMath = false
    var offset = 0
    var settled = 0
    for (line in src.split('\n')) {
        val trimmed = line.trim()
        if (fenceLine.matches(line)) inFence = !inFence
        else if (!inFence && (trimmed == "$$" || mathLine.matches(line))) inMath = !inMath
        offset += line.length + 1
        if (!inFence && !inMath && trimmed.isEmpty()) settled = offset
    }
    // 边界不能落在未闭合围栏/公式内部（上面的状态扫描已保证）
    return if (inFence || inMath) 0 else settled
}

/**
 * 独立成段图片块的可放大宿主：
 * ① LocalMessageImages —— 当前消息的图集（url,alt 序对，Quote/List 嵌套块已递归收进来）；
 *    ImageBlockView 点击时以此建 lightbox 的横滑页集，找不到宿主提供时退化为单图。
 * ② LocalImageLightboxLauncher —— 宿主（ChatScreen）注入的全屏查看器启动器；
 *    为 null（设置子页等裸用 MarkdownText 的场合）时 ImageBlockView 自挂查看器。
 */
val LocalMessageImages = androidx.compose.runtime.compositionLocalOf<List<Pair<String, String>>> { emptyList() }
val LocalImageLightboxLauncher =
    androidx.compose.runtime.compositionLocalOf<((List<Pair<String, String>>, String) -> Unit)?> { null }

/** 递归收集消息内独立成段的图片块（引用/列表嵌套里的也算），供 lightbox 多图滑动。 */
private fun collectMessageImages(blocks: List<MdBlock>): List<Pair<String, String>> =
    blocks.flatMap { b ->
        when (b) {
            is MdBlock.Image -> listOf(b.url to b.alt)
            is MdBlock.Quote -> collectMessageImages(b.children)
            is MdBlock.ListBlock -> b.items.flatMap { collectMessageImages(it.children) }
            else -> emptyList()
        }
    }

/** Markdown 渲染入口（v2 AST 管线）：ChatScreen 两处调用点的唯一门面。 */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    streaming: Boolean = false,
    @Suppress("UNUSED_PARAMETER") showCursor: Boolean = false
) {
    // 流式期预处理：未闭合 $$ 虚拟闭合（v7.6 起的既有设计，v2 重写时一度丢失——
    // 真机症状：流式公式先显示 "$$\max..." 字面量再跳变）。代码块内的 $$ 不算。
    val parseSource = if (streaming) closeUnclosedMath(text) else text
    // 首帧同步解析防闪烁；后续文本变化走后台线程（mapLatest 自动丢弃过期任务，
    // 打字速率 >> 解析速率时天然合并）。
    // 冻结前缀仍生效：settledBoundary 之外的尾部子串独立解析后按块表拼接。
    var blocks by remember { mutableStateOf(parseMarkdownAst(parseSource)) }
    var parsedFor by remember { mutableStateOf(parseSource) }
    var frozenSrc by remember { mutableStateOf("") }
    var frozenBlocks by remember { mutableStateOf<List<MdBlock>>(emptyList()) }
    LaunchedEffect(parseSource, streaming) {
        if (parsedFor == parseSource) return@LaunchedEffect
        val result = withContext(Dispatchers.Default) {
            if (streaming && parseSource.startsWith(frozenSrc)) {
                val b = settledBoundary(parseSource)
                if (b > frozenSrc.length) {
                    frozenBlocks = parseMarkdownAst(parseSource.take(b))
                    frozenSrc = parseSource.take(b)
                }
                if (frozenSrc.isEmpty()) parseMarkdownAst(parseSource)
                else frozenBlocks + parseMarkdownAst(parseSource.substring(frozenSrc.length))
            } else {
                frozenSrc = ""
                frozenBlocks = emptyList()
                parseMarkdownAst(parseSource)
            }
        }
        blocks = result
        parsedFor = parseSource
    }
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val baseColor = MaterialTheme.colorScheme.onBackground
    // 本消息的图集（多图 lightbox 横滑用）；流式期 blocks 每次刷新重算，代价 O(块数) 可忽略
    val gallery = androidx.compose.runtime.remember(blocks) { collectMessageImages(blocks) }
    androidx.compose.runtime.CompositionLocalProvider(LocalMessageImages provides gallery) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            blocks.forEachIndexed { blockIndex, block ->
                MdBlockView(
                    block = block,
                    dark = dark,
                    streaming = streaming,
                    listLevel = 0,
                    // 打字机渐显只挂在整个消息最后一个段落上
                    isLastParagraph = block is MdBlock.Paragraph && blockIndex == blocks.lastIndex,
                    textColor = baseColor
                )
            }
        }
    }
}

// ================= v2 渲染组件：递归块视图 + 类型化行内 =================

/** 链接点击处理：系统浏览器打开（URI 失败回退 Toast）。 */
private fun openLink(context: android.content.Context, url: String) {
    runCatching {
        context.startActivity(
            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.onFailure {
        android.widget.Toast.makeText(context, "无法打开链接", android.widget.Toast.LENGTH_SHORT).show()
    }
}

/**
 * 递归块视图：所有块类型统一入口（Quote/List 内层复用同一渲染）。
 * @param listLevel 嵌套层级（bullet 分级 •◦▪ 用）
 */
@Composable
private fun MdBlockView(
    block: MdBlock,
    dark: Boolean,
    streaming: Boolean,
    listLevel: Int,
    isLastParagraph: Boolean,
    textColor: Color
) {
    when (block) {
        is MdBlock.Code ->
            // html/svg 代码块 = 可渲染的视觉效果稿：自动内联渲染（效果/代码可切换）
            when {
                block.lang.lowercase() in setOf("html", "htm", "svg") -> HtmlArtifactBlock(block, dark)
                // diff/patch 围栏 → 着色差异视图（+N −M 常驻）；解析失败自动回落代码块
                block.lang.lowercase() in setOf("diff", "patch", "udiff") -> DiffBlockView(block, dark)
                else -> CodeBlock(block.lang, block.code, block.closed, dark)
            }
        is MdBlock.Mermaid -> MermaidBlock(block.code, dark)
        is MdBlock.Image -> ImageBlockView(block, dark)
        is MdBlock.Math -> FormulaBlock(block.latex, dark)
        is MdBlock.Table -> TableBlock(block)
        is MdBlock.TableSkeleton -> TableSkeletonBlock(block.header)
        is MdBlock.Rule -> Box(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp)
                .height(1.dp)
                .background(textColor.copy(alpha = 0.12f))
        )
        is MdBlock.Quote -> QuoteBlockView(block, dark, streaming, listLevel, textColor)
        is MdBlock.ListBlock -> ListBlockView(block, dark, streaming, listLevel, textColor)
        is MdBlock.Paragraph -> ParagraphView(block, streaming, isLastParagraph, textColor, dark)
        is MdBlock.Text -> Text(
            text = if (block.heading > 0) block.text else block.text,
            style = MaterialTheme.typography.bodyMedium,
            color = textColor
        )
    }
}

/** 段落/标题：类型化行内 → AnnotatedString（嵌套样式 + 可点链接 + 行内公式真渲染）。 */
@Composable
private fun ParagraphView(
    block: MdBlock.Paragraph,
    streaming: Boolean,
    isLastParagraph: Boolean,
    textColor: Color,
    dark: Boolean = false
) {
    val context = LocalContext.current
    val primary = MaterialTheme.colorScheme.primary
    val onSurface = MaterialTheme.colorScheme.onSurface
    val density = LocalDensity.current
    // 行内树→AnnotatedString + 行内公式占位（JLatexMath 实测尺寸）。
    // 按 inlines 引用相等缓存（data class），流式期历史段落零重算零重排
    val rendered = remember(block.inlines, textColor, density, dark) {
        val r = buildInlineTyped(
            block.inlines, context, primary, onSurface, dark,
            inlineCodeColor = if (dark) Color(0xFF61AFEF) else Color(0xFF4078F2)
        )
        val contents = buildMathContents(r.math, context, textColor, density, dark)
        InlineMathRender(r.text, r.math, contents)
    }
    // v4-3 打字机渐显（保留）：只挂最后一个段落；append(ann) 会复制全部
    // span/link 注记，渐显覆盖不丢链接可点性
    val typeInAnn = if (streaming && isLastParagraph) typeInTail(rendered.text) else rendered.text
    Text(
        text = typeInAnn,
        style = when (block.heading) {
            1 -> MaterialTheme.typography.headlineSmall
            2 -> MaterialTheme.typography.titleLarge
            3 -> MaterialTheme.typography.titleMedium
            0 -> MaterialTheme.typography.bodyMedium
            else -> MaterialTheme.typography.titleSmall
        },
        color = textColor,
        inlineContent = rendered.inlineContents
    )
}

/** 行内公式渲染产物：AnnotatedString + 占位公式表 + 合成好的 inlineContent。 */
internal class InlineMathRender(
    val text: AnnotatedString,
    val math: Map<String, String>,
    val inlineContents: Map<String, InlineTextContent> = emptyMap()
)

/** 公式占位符→InlineTextContent：JLatexMath 实测量尺寸，失败降级衬线斜体源码。 */
private fun buildMathContents(
    math: Map<String, String>,
    context: android.content.Context,
    textColor: Color,
    density: androidx.compose.ui.unit.Density,
    dark: Boolean
): Map<String, InlineTextContent> = math.mapValues { (_, latex) ->
        val d = runCatching {
            ru.noties.jlatexmath.JLatexMathAndroid.init(context)
            ru.noties.jlatexmath.JLatexMathDrawable.builder(latex)
                .textSize(with(density) { 15.sp.toPx() })
                .color(textColor.copy(alpha = 0.95f).toArgb())
                .build()
        }.onFailure {
            android.util.Log.e("MdInlineMath", "latex build failed: ${it.message} | latex=${latex.take(60)}", it)
        }.getOrNull()
        if (d != null) {
            val wSp = with(density) { d.intrinsicWidth.coerceAtLeast(1).toSp() }
            val hSp = with(density) { d.intrinsicHeight.coerceAtLeast(1).toSp() }
            InlineTextContent(
                Placeholder(wSp, hSp, PlaceholderVerticalAlign.AboveBaseline)
            ) {
                MathInlineNative(latex, dark)
            }
        } else {
            InlineTextContent(
                Placeholder(1.sp * latex.length.coerceIn(3, 24), 18.sp, PlaceholderVerticalAlign.AboveBaseline)
            ) {
                Text(
                    latex,
                    fontFamily = FontFamily.Serif,
                    fontStyle = FontStyle.Italic,
                    fontSize = 13.sp,
                    maxLines = 1
                )
            }
        }
}

/**
 * 类型化行内树 → AnnotatedString：递归携带 SpanStyle（嵌套自然合成）。
 * 链接用 LinkAnnotation.Url 真可点；行内公式埋占位符（InlineTextContent），
 * 实际尺寸由 JLatexMath Drawable 实测量出——真渲染不截断。
 */
private fun buildInlineTyped(
    inlines: List<MdInline>,
    context: android.content.Context,
    primary: Color,
    onSurface: Color,
    dark: Boolean,
    inlineCodeColor: Color? = null
): InlineMathRender {
    val mathSlots = LinkedHashMap<String, String>()
    val ann = buildAnnotatedString {
        fun emit(list: List<MdInline>, style: SpanStyle) {
            for (node in list) {
                when (node) {
                    is MdInline.Run -> if (node.text.isNotEmpty()) {
                        pushStyle(style)
                        append(node.text)
                        pop()
                    }
                    is MdInline.Strong -> emit(node.children, style.copy(fontWeight = FontWeight.Bold))
                    is MdInline.Emph -> emit(node.children, style.copy(fontStyle = FontStyle.Italic))
                    is MdInline.Del -> emit(node.children, style.copy(textDecoration = TextDecoration.LineThrough))
                    is MdInline.CodeSpan -> {
                        // 行内代码蓝字浅底（inlineCodeColor 覆盖时）
                        pushStyle(
                            style.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp,
                                color = inlineCodeColor ?: style.color,
                                background = onSurface.copy(alpha = 0.08f)
                            )
                        )
                    append(node.code)
                    pop()
                }
                is MdInline.MathSpan -> {
                    // 行内公式：占位符 id 埋进文本，尺寸由 JLatexMath 实测（见 ParagraphView）
                    val id = "math${mathSlots.size}"
                    mathSlots[id] = node.latex
                    appendInlineContent(id, node.latex)
                }
                is MdInline.Link -> withLink(
                    LinkAnnotation.Url(
                        node.url,
                        TextLinkStyles(SpanStyle(color = primary, textDecoration = TextDecoration.Underline))
                    ) { _ -> openLink(context, node.url) }
                ) {
                    emit(node.children, style)
                }
                is MdInline.Image -> {
                    pushStyle(style.copy(color = primary, fontStyle = FontStyle.Italic))
                    append("🖼 ")
                    append(node.alt.ifBlank { "图片" })
                    pop()
                }
            }
        }
    }
    emit(inlines, SpanStyle())
    }
    return InlineMathRender(ann, mathSlots)
}

/**
 * 纯样式差异说明：表格单元格与正文共用 [buildInlineTyped]（行内公式占位符在
 * 固定列宽下照常参与测量换行），仅行内代码颜色由调用方决定。
 */

/** 引用块：左竖线 + 浅底 + 子块递归。 */
@Composable
private fun QuoteBlockView(
    block: MdBlock.Quote,
    dark: Boolean,
    streaming: Boolean,
    listLevel: Int,
    textColor: Color
) {
    val lineColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(topEnd = 8.dp, bottomEnd = 8.dp))
            .background(textColor.copy(alpha = 0.05f))
    ) {
        Box(
            Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(lineColor)
        )
        Column(
            Modifier
                .padding(start = 12.dp, end = 10.dp, top = 8.dp, bottom = 8.dp)
                .animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            block.children.forEach { child ->
                MdBlockView(child, dark, streaming, listLevel, isLastParagraph = false, textColor = textColor.copy(alpha = 0.92f))
            }
        }
    }
}

/** 列表：无序 bullet 按层级 •◦▪，有序用数字，任务列表带勾选标记。 */
@Composable
private fun ListBlockView(
    block: MdBlock.ListBlock,
    dark: Boolean,
    streaming: Boolean,
    level: Int,
    textColor: Color
) {
    val bullets = listOf("•", "◦", "▪")
    Column(
        Modifier.animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(if (block.loose) 6.dp else 2.dp)
    ) {
        block.items.forEachIndexed { index, item ->
            Row {
                val marker: String = when {
                    item.checked == true -> "☑"
                    item.checked == false -> "☐"
                    block.ordered -> "${index + 1}."
                    else -> bullets[level % bullets.size]
                }
                val markerColor = when {
                    item.checked == true -> Color(0xFF7BD88F)
                    item.checked == false -> textColor.copy(alpha = 0.55f)
                    else -> textColor.copy(alpha = 0.6f)
                }
                Text(
                    text = marker,
                    style = MaterialTheme.typography.bodyMedium,
                    color = markerColor,
                    modifier = Modifier
                        .widthIn(min = 18.dp)
                        .padding(top = 1.dp)
                )
                Column(
                    verticalArrangement = Arrangement.spacedBy(if (block.loose) 6.dp else 2.dp)
                ) {
                    item.children.forEach { child ->
                        if (child is MdBlock.Paragraph) {
                            ParagraphView(
                                child, streaming,
                                isLastParagraph = false,
                                textColor = textColor
                            )
                        } else {
                            MdBlockView(child, dark, streaming, level + 1, isLastParagraph = false, textColor)
                        }
                    }
                }
            }
        }
    }
}

/** 代码等宽字体：JetBrains Mono（OFL），带 -> → / => ⇒ 连字。 */
private val CodeFontFamily = FontFamily(
    Font(R.font.jetbrains_mono_regular)
)

/** 代码块：顶部条（语言标签 + 复制）+ 语法高亮 + 软换行；流式未闭合时提示生成中。 */
@Composable
private fun CodeBlock(lang: String, code: String, closed: Boolean, dark: Boolean) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val colors = if (dark) CodeHighlight.darkColors() else CodeHighlight.lightColors()
    val bg = if (dark) Color(0xFF06080D).copy(alpha = 0.88f) else Color(0xFFF7F8FA)
    val plain = colors.plain
    // 块级缓存：语法高亮全量正则扫描只在代码/语言/配色变化时重算（Colors 为 data class）
    // v0.18.1：闭合块走全局 LRU——pop 重建聊天页时历史代码块命中缓存不再重扫
    val highlighted = remember(code, lang, colors) {
        if (closed) CodeHighlight.highlightCached(code.trimEnd('\n'), lang, colors)
        else CodeHighlight.highlight(code.trimEnd('\n'), lang, colors)
    }
    // 批2b：闭合块逐行渲染（行号槽 + 折叠 + 全屏的载体）；流式未闭合保持整块
    // 单 Text——内容每帧都在长，逐行 Row 每帧重排整块不值当，行号也未定型。
    val lines = remember(highlighted) { if (closed) splitAnnotatedPerLine(highlighted) else emptyList() }
    val collapseAt = CODE_COLLAPSE_AT
    var expanded by remember(code) { mutableStateOf(false) }
    val collapsed = closed && lines.size > collapseAt && !expanded
    var fullscreen by remember(code) { mutableStateOf(false) }
    val gutterPad = if (closed && lines.size > 1) gutterWidthOf(lines.size) else 0

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
                if (closed && lines.size > 1) {
                    Text(
                        text = "  ${lines.size} 行",
                        style = MaterialTheme.typography.labelSmall,
                        color = plain.copy(alpha = 0.45f)
                    )
                }
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
                        // disableSelection，行为一致，可接受）
                        .size(16.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .clickable {
                            clipboard.setText(AnnotatedString(code))
                            android.widget.Toast.makeText(context, "已复制代码", android.widget.Toast.LENGTH_SHORT).show()
                        }
                )
                // 批2b：全屏查看器（定稿：长代码不用在气泡里滚，字号/换行可调）
                if (closed) {
                    Text(
                        text = "⤢ 全屏",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { fullscreen = true }
                            .padding(horizontal = 6.dp, vertical = 3.dp)
                    )
                }
            }
            SelectionContainer {
                if (closed) {
                    val shown = if (collapsed) lines.take(collapseAt) else lines
                    Column(Modifier.padding(horizontal = 12.dp).padding(bottom = 4.dp)) {
                        shown.forEachIndexed { i, line ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                                if (gutterPad > 0) {
                                    // 行号槽：右对齐、压暗——参照 diff 卡同款口径
                                    Text(
                                        text = (i + 1).toString().padStart(gutterPad),
                                        fontFamily = CodeFontFamily,
                                        fontSize = 12.5.sp,
                                        lineHeight = 18.sp,
                                        color = plain.copy(alpha = 0.32f),
                                        modifier = Modifier.padding(end = 10.dp)
                                    )
                                }
                                Text(
                                    // 空行必须占高：Text("") 是零高度，行号会错位
                                    text = if (line.text.isEmpty()) AnnotatedString(" ") else line,
                                    fontFamily = CodeFontFamily,
                                    fontSize = 12.5.sp,
                                    lineHeight = 18.sp,
                                    softWrap = true,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                    if (lines.size > collapseAt) {
                        // 尾部折叠条（定稿：默认只露 24 行，长代码不撑爆消息流）
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp))
                                .clickable { expanded = !expanded }
                                .padding(horizontal = 12.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (collapsed) "展开全部 ${lines.size} 行 ▾" else "收起 ▴",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                } else {
                    Text(
                        text = highlighted,
                        fontFamily = CodeFontFamily,
                        fontSize = 12.5.sp,
                        lineHeight = 18.sp,
                        // 软换行：长行折行不横滚，手机上不用双向找内容
                        softWrap = true,
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 10.dp)
                            .fillMaxWidth()
                    )
                }
            }
        }
    }
    if (fullscreen) {
        FullscreenCodeDialog(
            title = "${lang.ifBlank { "text" }} · ${lines.size} 行",
            lines = lines,
            dark = dark,
            onDismiss = { fullscreen = false }
        )
    }
}

/** 批2b：代码块折叠阈值（约 2/3 屏，超过就该折叠/全屏而不是在气泡里滚）。 */
private const val CODE_COLLAPSE_AT = 24

/**
 * 批2b：整块高亮结果按行切分，样式与链接标注随区间保留（subSequence 语义）。
 * 行号槽逐行渲染的前提；internal 进单测钉死口径。
 */
internal fun splitAnnotatedPerLine(ann: AnnotatedString): List<AnnotatedString> {
    val out = ArrayList<AnnotatedString>()
    var start = 0
    for (i in ann.text.indices) {
        if (ann.text[i] == '\n') {
            out.add(ann.subSequence(start, i))
            start = i + 1
        }
    }
    out.add(ann.subSequence(start, ann.text.length))
    return out
}

/** 行号槽宽度 = 最大行号位数（1..9→1, 10..99→2, ≥100→3）。 */
internal fun gutterWidthOf(lineCount: Int): Int = when {
    lineCount < 10 -> 1
    lineCount < 100 -> 2
    else -> 3
}

/**
 * 批2b：代码全屏查看器（效果图定稿 ovCode）——深色壳 + 行号 + 底部控件条：
 * 字号步进（A− / A+，12.5 起，10..20 夹住）与软换行开关。关换行后长行横向
 * 共滚（整块一条 HorizontalScrollState，行与行同步移动，接近代码编辑器手感），
 * 此时行号槽隐藏——横滚态下行号已失去对齐意义。
 * 批2c 起 internal：只读文件查看器（FileViewerDialog）复用这个全屏壳——
 * 同一形态（行号槽/换行开关/字号步进）在聊天内和查看器里表现一致。
 */
@Composable
internal fun FullscreenCodeDialog(
    title: String,
    lines: List<AnnotatedString>,
    dark: Boolean,
    onDismiss: () -> Unit
) {
    val plain = (if (dark) CodeHighlight.darkColors() else CodeHighlight.lightColors()).plain
    var fontSp by androidx.compose.runtime.saveable.rememberSaveable { mutableFloatStateOf(12.5f) }
    var wrap by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(true) }
    val vScroll = rememberScrollState()
    val hScroll = rememberScrollState()
    val gutterPad = if (wrap && lines.size > 1) gutterWidthOf(lines.size) else 0
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .background(Color(0xFF10151A))
                .statusBarsPadding()
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFE8EEEA),
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "关闭",
                    fontSize = 12.sp,
                    color = Color(0xFF9AA8A0),
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .clickable { onDismiss() }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(vScroll)
            ) {
                if (wrap) {
                    Column(Modifier.padding(horizontal = 14.dp)) {
                        lines.forEachIndexed { i, line ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                                if (gutterPad > 0) {
                                    Text(
                                        text = (i + 1).toString().padStart(gutterPad),
                                        fontFamily = CodeFontFamily,
                                        fontSize = fontSp.sp,
                                        lineHeight = (fontSp * 1.45f).sp,
                                        color = plain.copy(alpha = 0.32f),
                                        modifier = Modifier.padding(end = 10.dp)
                                    )
                                }
                                Text(
                                    text = if (line.text.isEmpty()) AnnotatedString(" ") else line,
                                    fontFamily = CodeFontFamily,
                                    fontSize = fontSp.sp,
                                    lineHeight = (fontSp * 1.45f).sp,
                                    softWrap = true,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                } else {
                    Column(
                        Modifier
                            .horizontalScroll(hScroll)
                            .padding(horizontal = 14.dp)
                    ) {
                        lines.forEach { line ->
                            Text(
                                text = if (line.text.isEmpty()) AnnotatedString(" ") else line,
                                fontFamily = CodeFontFamily,
                                fontSize = fontSp.sp,
                                lineHeight = (fontSp * 1.45f).sp,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }
            }
            // 底部控件条：字号步进 + 换行开关（效果图定稿 .ctlbar）
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF161D26))
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    "A−",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = plain.copy(alpha = if (fontSp <= 10f) 0.3f else 0.85f),
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = fontSp > 10f) { fontSp -= 1f }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
                Text(
                    "${fontSp.toInt()}sp",
                    fontSize = 12.sp,
                    color = plain.copy(alpha = 0.6f),
                    modifier = Modifier.width(40.dp)
                )
                Text(
                    "A+",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = plain.copy(alpha = if (fontSp >= 20f) 0.3f else 0.85f),
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = fontSp < 20f) { fontSp += 1f }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
                Spacer(Modifier.weight(1f))
                Text(
                    if (wrap) "↩ 换行：开" else "↩ 换行：关",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (wrap) MaterialTheme.colorScheme.primary
                    else plain.copy(alpha = 0.6f),
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { wrap = !wrap }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }
    }
}

/**
 * ```diff 代码块 → 差异视图（v3 新增）：解析 unified diff 的 ± 行着色，
 * 头部常驻 +N −M 统计；超 120 行折叠可展开。解析不出任何 ± 行（模型拿
 * diff 围栏贴了别的东西）→ 回落普通代码块——宁可不染也别错染。
 */
@Composable
private fun DiffBlockView(block: MdBlock.Code, dark: Boolean) {
    val parsed = remember(block.code) { TextDiff.parseUnifiedDiff(block.code.trimEnd('\n')) }
    if (parsed == null) {
        CodeBlock(block.lang, block.code, block.closed, dark)
        return
    }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val plain = (if (dark) CodeHighlight.darkColors() else CodeHighlight.lightColors()).plain
    val bg = if (dark) Color(0xFF06080D).copy(alpha = 0.88f) else Color(0xFFF7F8FA)
    val addBg = if (dark) Color(0x2E3F9E5C) else Color(0x142E7D32)
    val addFg = if (dark) Color(0xFF9FD8AE) else Color(0xFF0F6B33)
    val delBg = if (dark) Color(0x33C25549) else Color(0x14C62828)
    val delFg = if (dark) Color(0xFFF3B3AC) else Color(0xFF8C1D18)
    val ctxFg = plain.copy(alpha = 0.75f)
    val added = parsed.count { it.type == DiffType.ADDED }
    val removed = parsed.count { it.type == DiffType.REMOVED }
    var expanded by remember(block.code) { mutableStateOf(false) }
    val collapseAt = 120
    val shown = if (!expanded && parsed.size > collapseAt) parsed.take(collapseAt) else parsed

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
                    text = "diff",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = plain.copy(alpha = 0.6f)
                )
                Text(
                    text = "  +$added",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = addFg
                )
                Text(
                    text = " −$removed",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = delFg
                )
                if (!block.closed) {
                    Text(
                        text = "  生成中…",
                        style = MaterialTheme.typography.labelSmall,
                        color = plain.copy(alpha = 0.45f)
                    )
                }
                Spacer(Modifier.weight(1f))
                Icon(
                    imageVector = Icons.Filled.ContentCopy,
                    contentDescription = "复制 diff",
                    tint = plain.copy(alpha = 0.65f),
                    modifier = Modifier
                        .size(16.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .clickable {
                            clipboard.setText(AnnotatedString(block.code))
                            android.widget.Toast.makeText(context, "已复制 diff", android.widget.Toast.LENGTH_SHORT).show()
                        }
                )
            }
            SelectionContainer {
                Column(Modifier.fillMaxWidth()) {
                    shown.forEach { l ->
                        val (rowBg, fg, prefix) = when (l.type) {
                            DiffType.ADDED -> Triple(addBg, addFg, "+")
                            DiffType.REMOVED -> Triple(delBg, delFg, "−")
                            DiffType.SAME -> Triple(Color.Transparent, ctxFg, " ")
                        }
                        Text(
                            text = prefix + l.text,
                            fontFamily = CodeFontFamily,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            color = fg,
                            softWrap = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(rowBg)
                                .padding(horizontal = 12.dp, vertical = 1.dp)
                        )
                    }
                }
            }
            if (!expanded && parsed.size > collapseAt) {
                Text(
                    text = "展开全部 ${parsed.size} 行",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { expanded = true }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }
    }
}

/**
 * 表格 v2：SubcomposeLayout 两阶段测量——
 * ① 各单元格自然宽度估列宽；② 固定列宽重测出行高，行内 Row 对齐排布。
 * 列宽 [72,240]dp 限幅，长单元格自动换行不再拉爆横滚；整表超宽才横向滚动。
 * 单元格经类型化行内渲染（粗体/代码/行内公式都可在表格内）。
 */
@Composable
private fun TableBlock(table: MdBlock.Table) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val bg = if (dark) Color(0xFF141A24) else Color(0xFFF7F9FC)
    val altBg = if (dark) Color(0xFF1A2130) else Color(0xFFEFF3F9)
    val lineColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.10f)
    val textColor = MaterialTheme.colorScheme.onBackground
    val density = LocalDensity.current
    val colCount = maxOf(table.header.size, table.rows.maxOfOrNull { it.size } ?: 0, 1)
    // 缓存键：表格原文 + 会改变 maxIntrinsicWidth(px) 的样式指纹。
    // 原文空（解析器未填 raw）时退化为"不缓存"，宁可不省也不能算错列宽。
    val tableTypography = MaterialTheme.typography
    val widthKey = remember(table.raw, density, dark, tableTypography) {
        "v1\u0001" + table.raw +
            "\u0001" + density.density + "|" + density.fontScale +
            "|" + tableTypography.bodySmall.fontSize.value +
            "|" + tableTypography.labelMedium.fontSize.value +
            "|" + (if (dark) 1 else 0)
    }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    // 头部动作：复制原始 markdown；下载走 SAF 存 CSV
    // v3：递归取文本——旧版 else->"" 把加粗/链接/删除线/图片单元格导出成空字段
    fun cellText(cells: List<MdInline>): String = cells.joinToString("") { inline ->
        when (inline) {
            is MdInline.Run -> inline.text
            is MdInline.CodeSpan -> inline.code
            is MdInline.MathSpan -> "$${inline.latex}$"
            is MdInline.Strong -> cellText(inline.children)
            is MdInline.Emph -> cellText(inline.children)
            is MdInline.Del -> cellText(inline.children)
            is MdInline.Link -> cellText(inline.children)
            is MdInline.Image -> inline.alt
        }
    }
    val tableCsv = remember(table) {
        fun esc(f: String) = if (f.any { it == ',' || it == '"' || it == '\n' }) "\"${f.replace("\"", "\"\"")}\"" else f
        buildString {
            appendLine(table.header.joinToString(",") { esc(cellText(it)) })
            table.rows.forEach { row -> appendLine(row.joinToString(",") { esc(cellText(it)) }) }
        }
    }
    val csvLauncher = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openOutputStream(uri)?.use { it.write(tableCsv.toByteArray()) }
        }.onFailure {
            android.widget.Toast.makeText(context, "保存失败", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    // 单元格文本测量器：自然宽度（maxLines=1）与限宽换行高度两用
    @Composable
    fun CellText(
        inlines: List<MdInline>,
        align: TextAlign,
        header: Boolean,
        modifier: Modifier
    ) {
        val context = LocalContext.current
        val primary = MaterialTheme.colorScheme.primary
        val onSurface = MaterialTheme.colorScheme.onSurface
        val density = LocalDensity.current
        // 单元格与正文同一条渲染管线：行内公式真渲染（占位符在固定列宽下照常换行测量）
        val rendered = remember(inlines, textColor, density, dark) {
            val r = buildInlineTyped(
                inlines, context, primary, onSurface, dark,
                inlineCodeColor = if (dark) Color(0xFF61AFEF) else Color(0xFF4078F2)
            )
            InlineMathRender(r.text, r.math, buildMathContents(r.math, context, textColor, density, dark))
        }
        Text(
            text = rendered.text,
            style = if (header) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodySmall,
            fontWeight = if (header) FontWeight.Bold else FontWeight.Normal,
            color = textColor.copy(alpha = if (header) 1f else 0.92f),
            textAlign = align,
            inlineContent = rendered.inlineContents,
            modifier = modifier
        )
    }

    Surface(
        color = bg,
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(0.75.dp, MaterialTheme.colorScheme.onBackground.copy(alpha = 0.22f)),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
    ) {
        // Surface 内容是 BoxScope：头部栏 + 表格必须显式纵向排列。
        // BoxWithConstraints 提供卡片真实可用宽度——横滚容器内 SubcomposeLayout
        // 的约束是 Infinity，"窄表拉伸铺满"必须由这层宽度驱动（否则窄表右侧露底）
        androidx.compose.foundation.layout.BoxWithConstraints {
        val cardMaxW = maxWidth
        Column {
        // 头部动作栏：固定不随表格横滚，底部分隔线
        Row(
            Modifier
                .fillMaxWidth()
                .drawBehind {
                    // 与表格网格同色系（onBackground 22%），宽线更清晰
                    drawLine(
                        textColor.copy(alpha = 0.22f),
                        Offset(0f, size.height - 0.5f),
                        Offset(size.width, size.height - 0.5f),
                        1.5f
                    )
                }
                .padding(horizontal = 12.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "表格",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = textColor.copy(alpha = 0.55f)
            )
            Spacer(Modifier.weight(1f))
            val iconTint = textColor.copy(alpha = 0.5f)
            Icon(
                Icons.Filled.ContentCopy,
                contentDescription = "复制表格",
                tint = iconTint,
                modifier = Modifier
                    .size(17.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .clickable {
                        clipboard.setText(AnnotatedString(table.raw.ifBlank { tableCsv }))
                        android.widget.Toast.makeText(context, "已复制表格 Markdown", android.widget.Toast.LENGTH_SHORT).show()
                    }
            )
            Spacer(Modifier.width(14.dp))
            Icon(
                Icons.Filled.Download,
                contentDescription = "下载表格 CSV",
                tint = iconTint,
                modifier = Modifier
                    .size(17.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .clickable {
                        csvLauncher.launch("table_" + java.time.LocalDateTime.now().toString().replace(":", "").replace("-", "").take(12) + ".csv")
                    }
            )
        }
        val scroll = rememberScrollState()
        Column(
            Modifier
                .horizontalScroll(scroll)
                .padding(vertical = 2.dp)
        ) {
            // 两阶段测量容器（网格线：几何在 measure 产出、drawBehind 消费）
            val gridRef = remember { java.util.concurrent.atomic.AtomicReference<GridGeom?>(null) }
            // 网格观感：中等浅灰（比底色明显、比文字淡得多）
            val gridLine = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.22f)
            val linePx = with(density) { 0.75.dp.toPx() }.coerceAtLeast(1f)
            // v3 隔行底色：altBg 早已定值（643 行）但一直没接线——绘制期按 rowHeights
            // 给偶数数据行铺条带（row0=表头不铺；表头下的第 2、4… 条数据行铺）
            val zebra = altBg
            androidx.compose.ui.layout.SubcomposeLayout(
                modifier = Modifier.drawBehind {
                    gridRef.get()?.let { g ->
                        val w = g.contentW.toFloat()
                        val h = g.totalH.toFloat()
                        // 条带先铺，行/列线后画压在上面
                        var yy = 0f
                        g.rowHeights.forEachIndexed { ri, rowH ->
                            if (ri > 0 && ri % 2 == 0) {
                                drawRect(zebra, Offset(0f, yy), Size(w, rowH.toFloat()))
                            }
                            yy += rowH
                        }
                        // 外缘已由 Surface border 提供（含头部栏整体圆角），这里只画内部行/列分隔
                        yy = 0f
                        for (rowH in g.rowHeights.dropLast(1)) {
                            yy += rowH
                            drawLine(gridLine, Offset(0f, yy), Offset(w, yy), linePx)
                        }
                        var xx = 0f
                        for (colW in g.colWidths.dropLast(1)) {
                            xx += colW
                            drawLine(gridLine, Offset(xx, 0f), Offset(xx, h), linePx)
                        }
                    }
                }
            ) { constraints ->
                val densityF = density.density
                val maxW = constraints.maxWidth
                val minColPx = (72 * densityF).toInt()
                val maxColPx = (240 * densityF).toInt()
                val padPx = (10 * densityF).toInt()
                val alignOf = { c: Int ->
                    when (table.aligns.getOrElse(c) { ALIGN_LEFT }) {
                        ALIGN_CENTER -> TextAlign.Center
                        ALIGN_RIGHT -> TextAlign.Right
                        else -> TextAlign.Left
                    }
                }
                val allRows = listOf(true to table.header) + table.rows.map { false to it }
                // 列宽自然值只依赖「表格原文 + 样式指纹」，与可用宽度无关 ⇒ 可跨 measure 复用。
                val __cachedW = if (table.raw.isNotBlank()) TableWidthCache.get(widthKey) else null
                val __useCache = __cachedW != null && __cachedW.size == colCount
                // 阶段 1：自然宽度测列宽（maxLines=1 的样式测量）—— 命中缓存则整轮跳过
                var natural = if (__useCache) __cachedW!!.copyOf() else IntArray(colCount)
                if (!__useCache) subcompose("measure") {
                    allRows.forEach { (isHeader, cells) ->
                        cells.forEachIndexed { c, cell ->
                            Box {
                                CellText(
                                    cell, alignOf(c), isHeader,
                                    Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                )
                            }
                        }
                    }
                }.forEachIndexed { idx, measurable ->
                    val c = idx % colCount
                    // maxIntrinsicWidth 可能为 0（空单元格）——列宽下限在 minColPx 兜底
                    val w = measurable.maxIntrinsicWidth(0)
                        .coerceAtLeast(1)
                        .coerceIn(minColPx, maxColPx)
                    if (w > natural[c]) natural[c] = w
                }
                if (!__useCache && table.raw.isNotBlank()) TableWidthCache.put(widthKey, natural)
                // 窄表拉伸：目标宽度优先取横滚约束（直接嵌入场景），Infinity 时
                // 退回 BoxWithConstraints 给的卡片可用宽度（horizontalScroll 内约束无界）
                val naturalSum = natural.sum()
                val boundedMax = if (constraints.maxWidth != Constraints.Infinity) {
                    constraints.maxWidth
                } else {
                    with(density) { cardMaxW.toPx() }.toInt()
                }
                if (boundedMax > 0 && naturalSum < boundedMax) {
                    val deficit = boundedMax - naturalSum
                    val totalWeight = natural.sum().toFloat().coerceAtLeast(1f)
                    natural = IntArray(colCount) { c ->
                        natural[c] + (deficit * (natural[c] / totalWeight)).toInt()
                    }
                    // Int 舍入差补到最后一列：保证列宽和 == 容器宽（否则右缘缺线露底）
                    natural[natural.size - 1] += boundedMax - natural.sum()
                }
                // contentW 取有限值（无界时不拉伸，就是自然总宽）
                val sum = natural.sum()
                val contentW = if (boundedMax > 0) maxOf(sum, boundedMax) else sum
                // 阶段 2：固定列宽测行高并摆放（slot id 用稳定行号）
                val rowPlaceables = allRows.mapIndexed { ri, (isHeader, cells) ->
                    val placeables = subcompose("row$ri") {
                        cells.forEachIndexed { c, cell ->
                            Box {
                                CellText(
                                    cell, alignOf(c), isHeader,
                                    Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                )
                            }
                        }
                    }.mapIndexed { idx, measurable ->
                        val c = idx % colCount
                        // Constraints() 全新构造（不用 constraints.copy——Infinity 位面
                        // 混入具体值会编码出 632461575 这类非法宽度直接崩）
                        measurable.measure(
                            Constraints(
                                minWidth = natural[c].coerceAtLeast(1),
                                maxWidth = natural[c].coerceAtLeast(1),
                                minHeight = 0,
                                maxHeight = Constraints.Infinity
                            )
                        )
                    }
                    placeables to (placeables.maxOfOrNull { it.height } ?: 0)
                }
                val totalH = rowPlaceables.sumOf { it.second }
                // 网格线几何（px）在测量期产出，绘制期消费（同帧 UI 线程，顺序有保证）
                gridRef.set(GridGeom(rowPlaceables.map { it.second }, natural.toList(), contentW, totalH))
                layout(contentW, totalH) {
                    var y = 0
                    rowPlaceables.forEach { (placeables, rowH) ->
                        var x = 0
                        placeables.forEachIndexed { c, p ->
                            p.place(x, y + (rowH - p.height) / 2)
                            x += natural[c]
                        }
                        y += rowH
                    }
                }
            }
        }
        }
        }
    }
}

/**
 * 表格「自然列宽」缓存（跨组合、跨 measure 存活）。
 *
 * 为什么需要：TableBlock 用 SubcomposeLayout，**measure lambda 每次测量都会重新执行**——
 * 阶段 1 要对全表每个单元格 subcompose + `maxIntrinsicWidth` 各来一遍。真机实测
 * （2026-09-18）：一张 5×5 = 25 格的表整体测量 60~80ms，且同一条消息滚回来会**再测一次**
 * （同一表格实测 80ms → 34ms 两次，历史探针记录）。
 *
 * 缓存键 = 表格原文 + 影响文本测量的样式指纹（密度/字体缩放/两级字号/深浅色）。
 * 自然列宽**与可用宽度无关**（按可用宽度拉伸是阶段 1 之后的纯算术），所以可以安全缓存；
 * 命中时整轮跳过阶段 1，阶段 2 的子组合与摆放保持原样 —— **视觉零变化**。
 */
private object TableWidthCache {
    private const val MAX_ENTRIES = 24
    private val lru = object : LinkedHashMap<String, IntArray>(MAX_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, IntArray>
        ): Boolean = size > MAX_ENTRIES
    }

    @Synchronized
    fun get(key: String): IntArray? = lru[key]?.copyOf()

    @Synchronized
    fun put(key: String, widths: IntArray) {
        lru[key] = widths.copyOf()
    }
}

private fun alignOf(aligns: List<Int>, col: Int): TextAlign = when (aligns.getOrElse(col) { ALIGN_LEFT }) {
    ALIGN_CENTER -> TextAlign.Center
    ALIGN_RIGHT -> TextAlign.Right
    else -> TextAlign.Left
}

/** 表格网格线几何（px，测量期产出供 drawBehind 消费）。 */
private data class GridGeom(
    val rowHeights: List<Int>,
    val colWidths: List<Int>,
    val contentW: Int,
    val totalH: Int
)

/** ⑦ 表格骨架占位：表头已到、分隔行未齐时显示 shimmer 灰条，分隔行到达后无缝换成真表格。 */
@Composable
private fun TableSkeletonBlock(header: List<String>) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val bg = if (dark) Color(0xFF141A24) else Color(0xFFF4F6FA)
    val base = if (dark) Color(0xFF2A3342) else Color(0xFFDDE3EC)
    val hi = if (dark) Color(0xFF3D4A61) else Color(0xFFC3CCDA)
    val phase by rememberPulse(0f, 1f, 1400)
    val brush = androidx.compose.ui.graphics.Brush.linearGradient(
        colors = listOf(base, hi, base),
        start = androidx.compose.ui.geometry.Offset((phase * 2f - 0.5f) * 360f, 0f),
        end = androidx.compose.ui.geometry.Offset((phase * 2f + 0.5f) * 360f, 0f)
    )
    Surface(
        color = bg,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(
                "表格生成中…",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
            )
            Spacer(Modifier.height(6.dp))
            // 表头 + 三行骨架条（列宽按表头文字长度粗略加权）
            val widths = header.map { (it.length.coerceIn(3, 14) / 14f) }
            repeat(4) { r ->
                Row(Modifier.padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    widths.forEach { w ->
                        Box(
                            Modifier
                                .weight(w)
                                .height(if (r == 0) 11.dp else 9.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(brush)
                        )
                    }
                }
            }
        }
    }
}

/**
 * 公式块 v2.1：JLatexMath 原生绘制。
 * 弃用 KaTeX+WebView——池上限 3 导致公式密集消息互相销毁（真机公式空白根因）、
 * 固定宽裁掉超宽公式、流式重载闪烁；原生绘制三者全消，失败降级源码文本。
 */
@Composable
private fun FormulaBlock(latex: String, dark: Boolean) {
    MathBlockNative(latex, dark)
}

// ===== Markdown 图片块（![](url)：http(s)/本地文件异步解码，LruCache；失败回落 alt 占位）=====
// internal：ImageLightbox（全屏查看器）复用同一套解码，气泡缩略与全屏共享位图不重解码

internal val mdImageCache = android.util.LruCache<String, ImageBitmap>(24)
internal val mdImageClient by lazy {
    okhttp3.OkHttpClient.Builder()
        .connectTimeout(java.time.Duration.ofSeconds(10))
        .readTimeout(java.time.Duration.ofSeconds(15))
        .build()
}

/** http(s) 下载或本地文件读取 → 采样解码（长边 ≤1080）。任何一步失败返回 null。 */
internal fun decodeMarkdownImage(url: String): ImageBitmap? =
    runCatching {
        val bytes = when {
            url.startsWith("http://") || url.startsWith("https://") ->
                mdImageClient.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { resp ->
                    if (!resp.isSuccessful) return@runCatching null
                    resp.body?.bytes()
                }
            url.startsWith("data:") ->
                android.util.Base64.decode(url.substringAfter("base64,", ""), android.util.Base64.DEFAULT)
            url.startsWith("/") -> java.io.File(url).takeIf { it.canRead() }?.readBytes()
            else -> null
        } ?: return@runCatching null
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1080) sample *= 2
        val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.asImageBitmap()
    }.onFailure {
        android.util.Log.w("MdImage", "decode fail url=$url: ${it.javaClass.simpleName}: ${it.message}")
    }.getOrNull()

@Composable
private fun ImageBlockView(block: MdBlock.Image, dark: Boolean) {
    val key = remember(block.url) { block.url.hashCode().toString() }
    var bmp by remember(block.url) { mutableStateOf(mdImageCache.get(key)) }
    var failed by remember(block.url) { mutableStateOf(false) }
    var zoomed by remember(block.url) { mutableStateOf(false) }
    val gallery = LocalMessageImages.current
    val launcher = LocalImageLightboxLauncher.current
    LaunchedEffect(block.url) {
        if (bmp != null) return@LaunchedEffect
        val loaded = withContext(Dispatchers.IO) { decodeMarkdownImage(block.url) }
        if (loaded != null) {
            mdImageCache.put(key, loaded)
            bmp = loaded
        } else {
            failed = true
        }
    }
    // 点击打开：图集里找不到本 url（解析时序边角）就退化为单图；宿主有启动器交宿主
    fun openFullscreen() {
        val list = if (gallery.any { it.first == block.url }) gallery else listOf(block.url to block.alt)
        launcher?.invoke(list, block.url) ?: run { zoomed = true }
    }
    if (zoomed && launcher == null) {
        ImageLightbox(listOf(block.url to block.alt), block.url) { zoomed = false }
    }
    if (bmp != null) {
        Surface(
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 80.dp, max = 320.dp)
                        .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                        .clickable { openFullscreen() },
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        bmp!!,
                        contentDescription = block.alt.ifBlank { "图片（点开放大）" },
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                    // 右下角放大角标：图片可点开的 affordance（点图主体同样触发）
                    Text(
                        "⤢",
                        color = Color.White,
                        fontSize = 12.sp,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(6.dp)
                            .background(Color(0x66000000), RoundedCornerShape(6.dp))
                            .padding(horizontal = 5.dp, vertical = 1.dp)
                    )
                }
                if (block.alt.isNotBlank()) {
                    Text(
                        block.alt,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                        modifier = Modifier.padding(start = 10.dp, end = 10.dp, bottom = 8.dp)
                    )
                }
            }
        }
    } else if (!failed) {
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 90.dp),
            contentAlignment = Alignment.Center
        ) {
            androidx.compose.material3.CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp
            )
        }
    } else {
        Text(
            "🖼 " + block.alt.ifBlank { "图片（加载失败）" },
            fontStyle = FontStyle.Italic,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ===== HTML/SVG 效果稿（lang=html/svg 代码块 → 紧凑卡片 + 全屏查看）=====

/**
 * 效果稿卡片：聊天里不再内联塞高 WebView（裁切 + 双层滚动打架 + 撑爆消息流），
 * 显示一张紧凑卡片，点开全屏沉浸查看/交互；「查看代码」随时展开源码。
 */
@Composable
private fun HtmlArtifactBlock(block: MdBlock.Code, dark: Boolean) {
    var showCode by remember(block.code) { mutableStateOf(false) }
    var fullscreen by remember(block.code) { mutableStateOf(false) }
    // 卡片
    Surface(
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable { fullscreen = true }
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFF7C5CE0), Color(0xFF4F8FE0)))),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.Web,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(21.dp)
                )
            }
            Column(
                Modifier
                    .weight(1f)
                    .padding(start = 11.dp)
            ) {
                Text(
                    "效果稿 · ${block.lang.uppercase()}",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "单文件 · 可交互 · 点按全屏查看",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            Text(
                "打开",
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f), RoundedCornerShape(999.dp))
                    .padding(horizontal = 12.dp, vertical = 5.dp)
            )
        }
    }
    // 次级操作：查看代码（展开后在卡片下方流式显示源码）
    Text(
        if (showCode) "收起代码" else "查看代码",
        fontSize = 11.5.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .padding(top = 5.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable { showCode = !showCode }
            .padding(horizontal = 8.dp, vertical = 4.dp)
    )
    if (showCode) {
        CodeBlock(block.lang, block.code, block.closed, dark)
    }
    if (fullscreen) {
        FullscreenHtmlDialog(
            title = "效果稿 · ${block.lang.uppercase()}",
            html = block.code,
            baseUrl = null,
            onDismiss = { fullscreen = false }
        )
    }
}

/**
 * 全屏 HTML 查看层：批2a 起收敛为 [HtmlPreviewDialog] 的薄封装——
 * 效果稿（消息正文 html 围栏）与 mermaid 全屏仍从这里进，壳/进度条/加载逻辑
 * 统一在 ui/common/HtmlPreviewDialog.kt（与更多面板预览、产物卡预览同源）。
 */
@Composable
private fun FullscreenHtmlDialog(
    title: String,
    html: String,
    baseUrl: String?,
    onDismiss: () -> Unit
) {
    HtmlPreviewDialog(
        title = title,
        source = HtmlPreviewSource.Content(html = html, baseUrl = baseUrl, dedupKey = html),
        onDismiss = onDismiss
    )
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
        val html = remember(code, dark) { mermaidHtml(code, dark) }
        var fullscreen by remember(code) { mutableStateOf(false) }
        Box(Modifier.fillMaxWidth()) {
            // 大图内滚限高：此前自动高上限 4000dp，一张大时序图能把消息流撑爆
            Box(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                WebViewBlock(
                    html = html,
                    baseUrl = "file:///android_asset/mermaid/"
                )
            }
            Surface(
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                shape = RoundedCornerShape(999.dp),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .clickable { fullscreen = true }
            ) {
                Text(
                    "⤢ 全屏",
                    fontSize = 10.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
        }
        if (fullscreen) {
            FullscreenHtmlDialog(
                title = "示意图 · mermaid",
                html = html,
                baseUrl = "file:///android_asset/mermaid/",
                onDismiss = { fullscreen = false }
            )
        }
    }
}

// ===== WebView 渲染（KaTeX / Mermaid 离线引擎，复用池上限 3 个实例）=====

// 批2a：提为 internal——HtmlPreviewDialog（统一预览壳）与本文件的
// 效果稿/mermaid 全屏共用同一池，不给第二个池第二个内存上限的机会。
internal object WebViewPool {
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
        // 批2a：产物卡预览要 loadUrl(file:// 工作区)，且 file:// baseUrl 的
        // 相对资源引用同样受本开关辖制——不开就是白屏。页面 JS 读文件仍被
        // allowFileAccessFromFileURLs（默认 false）拦住，扩的只是标签/资源加载。
        settings.allowFileAccess = true
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

/**
 * 流式打字机动效 v9（方案S「星布 + 墨色偏移」，用户选型）：
 * 尾部 [TYPE_IN_TAIL] 字符随机延迟淡入（40~220ms 错开），且先以主题色低透明度
 * 出现，随机延迟（140~300ms）后翻转为正文色——「变色苏醒」双色两段式。
 * 帧时钟窗口 = 最大淡入延迟 + 淡入时长 + 翻转时长。
 * SpanStyle 用 CurrentPositionalAlpha——无法直接拿主题色，改用 composition local
 * 不行（Text 的 color 在上层），故通过 LocalContentColor 读取前景色。
 */
private const val TYPE_IN_TAIL = 12
private const val TYPE_IN_FADE_MS = 180
private const val TYPE_IN_FLIP_MS = 220

@Composable
private fun typeInTail(ann: AnnotatedString): AnnotatedString {
    // 文本变化时刻：尾部字符同批到达（40ms flusher 批），共享同一到达时刻
    var batchAt by remember { mutableLongStateOf(0L) }
    var lastLen by remember { mutableStateOf(-1) }
    if (ann.text.length != lastLen) {
        lastLen = ann.text.length
        batchAt = System.currentTimeMillis()
    }
    // 随机时钟：按「批号」缓存每字符的淡入/翻转延迟（同批同延迟=星布错落来自批次差）
    // 用 length 哈希派生伪随机——重组稳定，不因重组重掷（避免字符来回跳变）
    val delayFor = { idx: Int ->
        val h = (idx * 2654435761L) xor (batchAt / 1000L)
        ((h ushr 16) % 181).toInt() // 0..180ms 随机淡入延迟
    }
    val flipFor = { idx: Int ->
        val h = (idx * 40503L) xor (batchAt / 997L)
        140 + ((h ushr 12) % 161).toInt() // 140..300ms 随机翻转延迟
    }
    // 帧时钟：驱动动效全程，结束后停止循环
    var now by remember { mutableLongStateOf(0L) }
    val totalWindow = 220L + TYPE_IN_FLIP_MS + 64
    LaunchedEffect(ann.text.length) {
        while (isActive) {
            now = System.currentTimeMillis()
            kotlinx.coroutines.delay(32)
            if (now - batchAt > totalWindow) break
        }
    }
    val fg = androidx.compose.material3.LocalContentColor.current
    val primary = MaterialTheme.colorScheme.primary
    val plain = ann.text
    val tailStart = (plain.length - TYPE_IN_TAIL).coerceAtLeast(0)
    if (tailStart >= plain.length) return ann
    val age = now - batchAt
    if (age >= totalWindow) return ann // 全部已定格
    return buildAnnotatedString {
        append(ann)
        val fadeSpan = TYPE_IN_FADE_MS.toFloat()
        val flipSpan = TYPE_IN_FLIP_MS.toFloat()
        for (i in tailStart until plain.length) {
            val d = delayFor(i)
            val f = flipFor(i)
            val charAge = age - d
            val alpha = if (charAge <= 0f) 0f
            else (charAge / fadeSpan).coerceIn(0f, 1f).let { 0.1f + 0.9f * it }
            if (alpha <= 0.01f) {
                // 尚未浮现：完全透明（保留占位，避免右侧文字突然跳入）
                addStyle(SpanStyle(color = fg.copy(alpha = 0f)), i, i + 1)
                continue
            }
            val flipAge = age - f
            val color = if (flipAge <= 0f) {
                // 阶段一：主题色墨滴
                primary.copy(alpha = 0.75f)
            } else {
                // 阶段二：翻转向正文色
                val t = (flipAge / flipSpan).coerceIn(0f, 1f)
                lerp(primary, fg, t)
            }
            addStyle(SpanStyle(color = color.copy(alpha = alpha)), i, i + 1)
        }
    }
}

// v6.1：StreamingCursorGlyph 已删除——U+FFFC 占位符在 SelectionContainer 内被
// 渲染成「OBJ」方框（inline content 不生效），流式光标方案整体废弃；
// 打字机字符渐显（typeInTail）本身即"进行中"信号
