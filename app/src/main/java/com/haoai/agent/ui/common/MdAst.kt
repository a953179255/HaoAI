package com.haoai.agent.ui.common

import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.impl.ListCompositeNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.parser.MarkdownParser

/**
 * v2 Markdown 渲染升级（对齐 上游 观感）：
 * 解析层从自写逐行状态机换成 intellij-markdown 的 GFM fork（Apache-2.0，
 * 上游/markdown 仓库自带 Apache LICENSE——主仓库 AGPL 不涉及此依赖），
 * 拿到真 AST：引用块/嵌套列表/任务列表/hr/表格单元格行内样式从此全部可得。
 *
 * 设计约束：
 * - 输出仍是现有 MdBlock 模型（扩展而非替换），Code/Math/Mermaid/Skeleton 的
 *   渲染器零改动复用；新增 Quote/ListBlock/Rule 与类型化行内
 * - 纯函数 + LRU 缓存（同旧 parseMarkdownBlocks），Dispatchers.Default 可调
 * - 解析异常兜底：整段按纯文本段落，绝不丢内容
 */

/** 类型化行内节点（v2）：支持任意嵌套 + 可点链接 + 行内公式节点。 */
sealed interface MdInline {
    data class Run(val text: String) : MdInline
    data class Strong(val children: List<MdInline>) : MdInline
    data class Emph(val children: List<MdInline>) : MdInline
    data class Del(val children: List<MdInline>) : MdInline
    data class CodeSpan(val code: String) : MdInline
    data class MathSpan(val latex: String) : MdInline
    data class Link(val children: List<MdInline>, val url: String) : MdInline
    data class Image(val alt: String, val url: String) : MdInline
}

private val mdFlavour by lazy { GFMFlavourDescriptor(useSafeLinks = false) }
private val mdParser by lazy { MarkdownParser(mdFlavour) }

private val mdAstCache: MutableMap<String, List<MdBlock>> =
    java.util.Collections.synchronizedMap(
        object : LinkedHashMap<String, List<MdBlock>>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<MdBlock>>) =
                size > 48
        }
    )

/** Markdown 源文本 → 块列表（AST 解析，带 LRU 缓存）。纯函数，后台线程安全。 */
fun parseMarkdownAst(src: String): List<MdBlock> {
    mdAstCache[src]?.let { return it }
    val result = runCatching { mapFile(src) }.getOrElse {
        listOf(MdBlock.Paragraph(listOf(MdInline.Run(src)), 0))
    }
    mdAstCache[src] = result
    return result
}

private fun mapFile(src: String): List<MdBlock> {
    val tree = mdParser.buildMarkdownTreeFromString(src)
    val blocks = tree.children.mapNotNull { mapBlock(it, src) }.toMutableList()
    // 流式表格骨架：源文本以「含 | 的行」结尾（分隔行还没流出来）且 AST 尚未识别出
    // 表格 → 用骨架占位，避免表头闪现为普通段落文本
    val trimmed = src.trimEnd()
    val lines = trimmed.split('\n')
    if (lines.size >= 2) {
        val last = lines.last()
        val prev = lines[lines.size - 2]
        if (last.contains('|') && last.count { it == '|' } >= 2 &&
            prev.contains('|') && !trimmed.endsWith("---") &&
            blocks.lastOrNull() is MdBlock.Paragraph
        ) {
            val header = prev.trim().removePrefix("|").removeSuffix("|")
                .split('|').map { it.trim() }.filter { it.isNotEmpty() }
            if (header.isNotEmpty()) {
                blocks[blocks.size - 1] = MdBlock.TableSkeleton(header)
            }
        }
    }
    return blocks
}

private fun mapBlock(node: ASTNode, src: String): MdBlock? = when (node.type) {
    MarkdownElementTypes.PARAGRAPH -> {
        // fork 会把块级公式（无论单行还是多行 $$）包进 PARAGRAPH——段落里仅含
        // 一个 BLOCK_MATH 时直接提升为公式块，避免走进行内分支变字面量
        val mathChild = node.children.singleOrNull { it.type == GFMElementTypes.BLOCK_MATH }
        if (mathChild != null && node.children.size == 1) {
            MdBlock.Math(
                latex = src.substring(mathChild.startOffset, mathChild.endOffset)
                    .trim().removePrefix("$$").removeSuffix("$$").trim(),
                closed = true
            )
        } else {
            MdBlock.Paragraph(mapInlines(node.children, src))
        }
    }
    MarkdownElementTypes.ATX_1 -> heading(node, src, 1)
    MarkdownElementTypes.ATX_2 -> heading(node, src, 2)
    MarkdownElementTypes.ATX_3 -> heading(node, src, 3)
    MarkdownElementTypes.ATX_4 -> heading(node, src, 4)
    MarkdownElementTypes.ATX_5 -> heading(node, src, 5)
    MarkdownElementTypes.ATX_6 -> heading(node, src, 6)
    MarkdownElementTypes.SETEXT_1 -> heading(node, src, 1)
    MarkdownElementTypes.SETEXT_2 -> heading(node, src, 2)
    MarkdownElementTypes.CODE_FENCE -> codeFence(node, src)
    MarkdownElementTypes.CODE_BLOCK -> MdBlock.Code(
        lang = "",
        code = src.substring(node.startOffset, node.endOffset).trimEnd('\n') + "\n",
        closed = true
    )
    GFMElementTypes.BLOCK_MATH -> MdBlock.Math(
        latex = src.substring(node.startOffset, node.endOffset)
            .trim().removePrefix("$$").removeSuffix("$$").trim(),
        closed = true
    )
    MarkdownElementTypes.BLOCK_QUOTE -> MdBlock.Quote(
        node.children.mapNotNull { mapBlock(it, src) }.ifEmpty {
            listOf(MdBlock.Paragraph(mapInlines(node.children, src), 0))
        }
    )
    MarkdownElementTypes.UNORDERED_LIST -> listBlock(node, src, ordered = false)
    MarkdownElementTypes.ORDERED_LIST -> listBlock(node, src, ordered = true)
    GFMElementTypes.TABLE -> table(node, src)
    MarkdownTokenTypes.HORIZONTAL_RULE -> MdBlock.Rule
    else -> null
}

private fun heading(node: ASTNode, src: String, level: Int): MdBlock =
    MdBlock.Paragraph(
        // ATX/SETEXT 的标记符叶子（### 或 === 下划线）不是内容，跳过
        mapInlinesFiltered(
            node.children, src,
            setOf(MarkdownTokenTypes.ATX_HEADER, MarkdownTokenTypes.SETEXT_CONTENT, MarkdownTokenTypes.EOL)
        ),
        level
    )

private fun codeFence(node: ASTNode, src: String): MdBlock {
    val raw = src.substring(node.startOffset, node.endOffset)
    val lang = raw.lineSequence().firstOrNull()?.removePrefix("```")?.trim()?.lowercase().orEmpty()
    val closed = node.children.any { it.type == MarkdownTokenTypes.CODE_FENCE_END }
    // CODE_FENCE_CONTENT 叶子是"单行内容不含换行符"——必须以 \n 连接，
    // 否则多行代码挤成一行（fork AST 的既有行为）
    val code = node.children
        .filter { it.type == MarkdownTokenTypes.CODE_FENCE_CONTENT }
        .joinToString("\n") { src.substring(it.startOffset, it.endOffset) }
    return if (lang == "mermaid") MdBlock.Mermaid(code, closed)
    else MdBlock.Code(lang, code, closed)
}

private fun listBlock(node: ASTNode, src: String, ordered: Boolean): MdBlock {
    val loose = (node as? ListCompositeNode)?.loose ?: false
    val items = node.children.mapNotNull { item ->
        if (item.type != MarkdownElementTypes.LIST_ITEM) return@mapNotNull null
        val checked = item.children.firstOrNull { it.type == GFMTokenTypes.CHECK_BOX }
            ?.let { box ->
                val t = src.substring(box.startOffset, box.endOffset)
                t.length > 1 && t[1] != ' '
            }
        val children = item.children
            .filter { it.type != GFMTokenTypes.CHECK_BOX }
            .mapNotNull { mapBlock(it, src) }
            .ifEmpty {
                // 紧凑列表兜底：内容不包 PARAGRAPH 时直接是行内 token
                listOf(MdBlock.Paragraph(mapInlines(item.children, src), 0))
            }
        MdBlock.ListBlock.Item(checked, children)
    }
    return MdBlock.ListBlock(ordered, loose, items)
}

private fun table(node: ASTNode, src: String): MdBlock {
    // fork 的表格结构：TABLE → HEADER（首行，CELL 列表）+ ROW*（数据行）+ TABLE_SEPARATOR
    val headerRow = node.children.firstOrNull { it.type == GFMElementTypes.HEADER }
    val bodyRows = node.children.filter { it.type == GFMElementTypes.ROW }
    val separator = node.children.firstOrNull { it.type == GFMTokenTypes.TABLE_SEPARATOR }
    val aligns = separator?.let {
        splitCells(src.substring(it.startOffset, it.endOffset)).map { cell ->
            val t = cell.trim()
            when {
                t.startsWith(":") && t.endsWith(":") -> ALIGN_CENTER
                t.endsWith(":") -> ALIGN_RIGHT
                else -> ALIGN_LEFT
            }
        }
    } ?: emptyList()
    fun rowCells(row: ASTNode): List<List<MdInline>> =
        row.children.filter { it.type == GFMTokenTypes.CELL }.map { cell ->
            parseInlineFragment(src.substring(cell.startOffset, cell.endOffset))
        }
    val header = headerRow?.let { rowCells(it) } ?: emptyList()
    val body = bodyRows.map { rowCells(it) }
    return MdBlock.Table(header, body, aligns)
}

// ---------- 行内映射 ----------

/** 表格单元格等片段的行内解析：对片段整树做一次迷你解析再取顶层行内。 */
private val fragmentCache: MutableMap<String, List<MdInline>> =
    java.util.Collections.synchronizedMap(
        object : LinkedHashMap<String, List<MdInline>>(128, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<MdInline>>) =
                size > 256
        }
    )

fun parseInlineFragment(text: String): List<MdInline> {
    fragmentCache[text]?.let { return it }
    val result = runCatching {
        val tree = mdParser.buildMarkdownTreeFromString(text)
        mapInlines(tree.children, text)
    }.getOrElse { listOf(MdInline.Run(text)) }
    fragmentCache[text] = result
    return result
}

private fun mapInlines(children: List<ASTNode>, src: String): List<MdInline> =
    mapInlinesFiltered(children, src, emptySet())

private fun mapInlinesFiltered(
    children: List<ASTNode>,
    src: String,
    skip: Set<IElementType>
): List<MdInline> {
    val out = mutableListOf<MdInline>()
    fun emit(node: MdInline) {
        // 相邻 Run 合并，避免碎片化（也保证空格去重逻辑有效）
        if (node is MdInline.Run && node.text.isEmpty()) return
        val last = out.lastOrNull()
        if (node is MdInline.Run && last is MdInline.Run) {
            out[out.size - 1] = MdInline.Run(last.text + node.text)
        } else out.add(node)
    }
    for (c in children) {
        if (c.type in skip) continue
        when (c.type) {
            MarkdownTokenTypes.TEXT, MarkdownTokenTypes.ESCAPED_BACKTICKS ->
                emit(MdInline.Run(src.substring(c.startOffset, c.endOffset)))
            MarkdownTokenTypes.WHITE_SPACE -> {
                val prev = (out.lastOrNull() as? MdInline.Run)?.text
                if (prev != null && prev.endsWith(" ")) continue
                emit(MdInline.Run(" "))
            }
            MarkdownTokenTypes.HARD_LINE_BREAK -> emit(MdInline.Run("\n"))
            MarkdownElementTypes.EMPH ->
                emit(MdInline.Emph(mapInlinesFiltered(c.children, src, setOf(MarkdownTokenTypes.EMPH))))
            MarkdownElementTypes.STRONG ->
                emit(MdInline.Strong(mapInlinesFiltered(c.children, src, setOf(MarkdownTokenTypes.EMPH))))
            GFMElementTypes.STRIKETHROUGH ->
                emit(MdInline.Del(mapInlinesFiltered(c.children, src, setOf(GFMTokenTypes.TILDE))))
            MarkdownElementTypes.CODE_SPAN -> emit(
                MdInline.CodeSpan(
                    src.substring(c.startOffset, c.endOffset)
                        .removeSurrounding("`").removeSurrounding("`").replace("` `", " ")
                )
            )
            GFMElementTypes.INLINE_MATH -> emit(
                MdInline.MathSpan(
                    src.substring(c.startOffset, c.endOffset)
                        .trim().removePrefix("$").removeSuffix("$").trim()
                )
            )
            MarkdownElementTypes.INLINE_LINK -> {
                val url = c.children.firstOrNull { it.type == MarkdownElementTypes.LINK_DESTINATION }
                    ?.let { src.substring(it.startOffset, it.endOffset) }.orEmpty()
                val textNode = c.children.firstOrNull { it.type == MarkdownElementTypes.LINK_TEXT }
                val children = textNode?.let { mapInlinesFiltered(it.children, src, emptySet()) }
                    ?: listOf(MdInline.Run(url))
                emit(MdInline.Link(children, url))
            }
            MarkdownElementTypes.FULL_REFERENCE_LINK, MarkdownElementTypes.SHORT_REFERENCE_LINK -> {
                val textNode = c.children.firstOrNull { it.type == MarkdownElementTypes.LINK_TEXT }
                val label = textNode
                    ?.let { src.substring(it.startOffset, it.endOffset) }
                    ?.removePrefix("[")
                    ?.removeSuffix("]")
                    .orEmpty()
                emit(MdInline.Link(listOf(MdInline.Run(label.ifBlank { "链接" })), ""))
            }
            MarkdownElementTypes.AUTOLINK, GFMTokenTypes.GFM_AUTOLINK, MarkdownTokenTypes.EMAIL_AUTOLINK -> {
                val url = src.substring(c.startOffset, c.endOffset)
                emit(MdInline.Link(listOf(MdInline.Run(url)), url))
            }
            MarkdownElementTypes.IMAGE -> {
                val url = c.children.firstOrNull { it.type == MarkdownElementTypes.LINK_DESTINATION }
                    ?.let { src.substring(it.startOffset, it.endOffset) }.orEmpty()
                val alt = c.children.firstOrNull { it.type == MarkdownElementTypes.LINK_TEXT }
                    ?.let { it.children.joinToString("") { n -> src.substring(n.startOffset, n.endOffset) } }
                    .orEmpty()
                emit(MdInline.Image(alt, url))
            }
            GFMElementTypes.BLOCK_MATH -> emit(
                // 段落内夹带的块级公式（未被单节点提升兜住的形态）→ 行内公式 span
                MdInline.MathSpan(
                    src.substring(c.startOffset, c.endOffset)
                        .trim().removePrefix("$$").removeSuffix("$$").trim()
                )
            )
            else -> {
                // 引用续行的 "> " 标记：fork 解析成 PARAGRAPH 内的 BLOCK_QUOTE 叶子。
                // 注意 TokenTypes.BLOCK_QUOTE（"> "标记）与 ElementTypes.BLOCK_QUOTE
                // （复合容器）toString 同名但是两个对象——这里必须匹配 Token 版
                if (c.type == MarkdownTokenTypes.BLOCK_QUOTE && c.children.isEmpty()) continue
                if (c.children.isEmpty()) {
                    // 未知叶子（保留原文防丢字）
                    val t = src.substring(c.startOffset, c.endOffset)
                    if (t.isNotEmpty()) emit(MdInline.Run(t))
                } else {
                    mapInlinesFiltered(c.children, src, skip).forEach { emit(it) }
                }
            }
        }
    }
    return out
}

private fun splitCells(row: String): List<String> =
    row.trim().removePrefix("|").removeSuffix("|").split('|').map { it.trim() }
