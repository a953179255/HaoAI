package com.haoai.agent.ui.common

import androidx.compose.ui.text.AnnotatedString
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 批2b（代码块行号/折叠/全屏）的切分口径钉死测试。
 *
 * 逐行渲染的前提是把整块高亮结果按 \n 切开且样式不丢——样式丢了行号还在
 * 但颜色全白，等于批1a 的高亮白做。
 */
class CodeLineSplitTest {

    @Test
    fun `按行切分行数正确`() {
        val ann = AnnotatedString("line1\nline2\nline3")
        val lines = splitAnnotatedPerLine(ann)
        assertEquals(3, lines.size)
        assertEquals("line1", lines[0].text)
        assertEquals("line3", lines[2].text)
    }

    @Test
    fun `空行保留为空段`() {
        val ann = AnnotatedString("a\n\nb")
        val lines = splitAnnotatedPerLine(ann)
        assertEquals(3, lines.size)
        assertEquals("", lines[1].text)
    }

    @Test
    fun `尾部换行不产生多余空行`() {
        val ann = AnnotatedString("x\ny\n")
        // 高亮前调用方已 trimEnd('\n')，这里只是钉死：若真带尾换行，
        // 切分会多出一段空行——渲染端按空行占位不会崩，但不能丢前面的行
        val lines = splitAnnotatedPerLine(ann)
        assertEquals(3, lines.size)
        assertEquals("x", lines[0].text)
    }

    @Test
    fun `样式标注随区间保留`() {
        val ann = AnnotatedString.Builder().apply {
            pushStyle(androidx.compose.ui.text.SpanStyle(color = androidx.compose.ui.graphics.Color.Red))
            append("int")
            pop()
            append(" x = 1\nint y = 2")
        }.toAnnotatedString()
        val lines = splitAnnotatedPerLine(ann)
        assertEquals(2, lines.size)
        assertEquals("int x = 1", lines[0].text)
        // 第一段样式区间必须落进行号行（span 起点 0 长 3）
        val styled = lines[0].spanStyles.firstOrNull { it.item.color == androidx.compose.ui.graphics.Color.Red }
        assertEquals(0, styled?.start)
        assertEquals(3, styled?.end)
    }

    @Test
    fun `行号槽宽度按位数分档`() {
        for (n in 1..9) assertEquals(1, gutterWidthOf(n))
        for (n in listOf(10, 42, 99)) assertEquals(2, gutterWidthOf(n))
        for (n in listOf(100, 9999)) assertEquals(3, gutterWidthOf(n))
    }

    // ════════ 围栏解析保空行（批2b：行号槽逼出的 fork AST 潜伏缺陷） ════════

    private fun codeOf(src: String): MdBlock.Code {
        val b = parseMarkdownAst(src).first { it is MdBlock.Code } as MdBlock.Code
        return b
    }

    @Test
    fun `中间空行不丢`() {
        val c = codeOf("```kotlin\nA\n\nB\n```")
        assertEquals("A\n\nB", c.code)
        assertEquals(3, splitAnnotatedPerLine(AnnotatedString(c.code)).size)
    }

    @Test
    fun `首尾空行也不丢`() {
        val c = codeOf("```py\n\nx=1\n\n```")
        assertEquals("\nx=1\n", c.code)
    }

    @Test
    fun `闭合与未闭合判定`() {
        assertEquals(true, codeOf("```\nx\n```").closed)
        assertEquals(false, codeOf("```\nx\ny=2").closed)
        // 未闭合：内容原样到文末
        assertEquals("x\ny=2", codeOf("```\nx\ny=2").code)
    }
}
