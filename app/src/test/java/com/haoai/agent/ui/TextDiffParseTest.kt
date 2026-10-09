package com.haoai.agent.ui

import com.haoai.agent.ui.common.DiffType
import com.haoai.agent.ui.common.TextDiff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * ```diff 围栏解析口径钉死（批 1c）：DiffBlockView 的数据入口。
 * 规则：± 成行、@@ 与元信息丢弃、无前导空格的裸行也是上下文；
 * 全无可 ± 行返回 null → 调用方回落普通代码块（宁缺勿错染）。
 */
class TextDiffParseTest {

    @Test
    fun `unified diff hunk maps prefixes to types`() {
        val text = """
            |--- a/App.kt
            |+++ b/App.kt
            |@@ -1,4 +1,5 @@
            | package demo
            |-val a = 1
            |+val a = 2
            |+// added line
            | fun main() {}
        """.trimMargin()
        val lines = TextDiff.parseUnifiedDiff(text)!!
        assertEquals(DiffType.SAME, lines[0].type)
        assertEquals("package demo", lines[0].text)
        assertEquals(DiffType.REMOVED, lines[1].type)
        assertEquals("val a = 1", lines[1].text)
        assertEquals(DiffType.ADDED, lines[2].type)
        assertEquals("val a = 2", lines[2].text)
        assertEquals(DiffType.ADDED, lines[3].type)
        assertEquals("// added line", lines[3].text)
        assertEquals(DiffType.SAME, lines[4].type)
        assertEquals("fun main() {}", lines[4].text)
    }

    @Test
    fun `header and metadata lines are dropped`() {
        val lines = TextDiff.parseUnifiedDiff(
            "diff --git a/x b/x\nindex 123..456 100644\nnew file mode 100644\n--- /dev/null\n+++ b/x\n@@ 0,1 +1 @@\n+hello"
        )!!
        assertEquals(1, lines.size)
        assertEquals(DiffType.ADDED, lines[0].type)
    }

    @Test
    fun `context lines without leading space are still same`() {
        // 模型常省略 unified diff 的前导空格
        val lines = TextDiff.parseUnifiedDiff("unchanged line\n+added\n-removed")!!
        assertEquals(listOf(DiffType.SAME, DiffType.ADDED, DiffType.REMOVED), lines.map { it.type })
    }

    @Test
    fun `pure prose in diff fence returns null for codeblock fallback`() {
        assertNull(TextDiff.parseUnifiedDiff("这段代码改了按钮颜色\n第二行说明"))
        assertNull(TextDiff.parseUnifiedDiff(""))
    }

    @Test
    fun `minus inside line is not a removal marker`() {
        // 行内 a--、b + 1 不误拆；真实 ± 行驱动解析
        val lines = TextDiff.parseUnifiedDiff("x = a-- ; y = b + 1\n+val z = 2")!!
        assertEquals(2, lines.size)
        assertEquals(DiffType.SAME, lines[0].type)
        assertEquals("x = a-- ; y = b + 1", lines[0].text)
        assertEquals(DiffType.ADDED, lines[1].type)
        assertEquals("val z = 2", lines[1].text)
    }
}
