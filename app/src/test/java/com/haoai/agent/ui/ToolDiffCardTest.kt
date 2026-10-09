package com.haoai.agent.ui

import com.haoai.agent.ui.chat.DiffRowKind
import com.haoai.agent.ui.chat.contextOf
import com.haoai.agent.ui.common.DiffLine
import com.haoai.agent.ui.common.DiffType
import com.haoai.agent.ui.common.TextDiff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 批1f（write/edit 内联 diff）的行号与取行口径钉死测试。
 *
 * 这一层最容易错在两个地方，且错了都不报错、只是"看着别扭"：
 * ①行号——新增行在旧文件里根本不存在，硬给它一个号就等于谎报位置；
 * ②取行——把整份文件铺出来会把对话冲垮，但只留变更行又看不出改在哪一段。
 */
class ToolDiffCardTest {

    private fun rows(old: String, new: String) = contextOf(TextDiff.diffText(old, new).lines)

    // ══════════ 行号口径 ══════════

    @Test
    fun `新增行没有旧行号，其余走旧文件行号`() {
        val r = rows("a\nb\nc", "a\nB\nc")
        val removed = r.first { it.text == "b" }
        val added = r.first { it.text == "B" }
        assertEquals("删除行走旧行号", 2, removed.oldNo)
        assertNull("新增行在旧文件里不存在，不该给行号", added.oldNo)
        assertEquals(DiffRowKind.REMOVED, removed.kind)
        assertEquals(DiffRowKind.ADDED, added.kind)
    }

    @Test
    fun `未变更行按旧文件顺序连续编号`() {
        // 改动落在尾部，上下文窗口只带出 b/c/d —— 首行 a 离变更超过 CONTEXT_LINES，
        // 本来就不该出现在内联视图里（这正是取行口径要的效果）。
        val r = rows("a\nb\nc\nd", "a\nb\nc\nd\nZ")
        val sames = r.filter { it.kind == DiffRowKind.SAME }.map { it.oldNo }
        assertEquals("未变更行应拿到旧文件真实行号 2..4", listOf(2, 3, 4), sames)
        assertTrue("首行 a 超出上下文窗口，不该出现", r.none { it.text == "a" })
    }

    // ══════════ 取行：变更 + 上下文 ══════════

    @Test
    fun `短文件改动全部保留`() {
        val r = rows("a\nb\nc", "a\nB\nc")
        val texts = r.map { it.text }
        assertTrue("短改动应全保留，实际=$texts", texts.containsAll(listOf("a", "b", "B", "c")))
    }

    @Test
    fun `远处未变更内容被折叠`() {
        val old = (1..60).joinToString("\n") { "line$it" }
        // 注意不能用 replace("line5",…)：它会连 line50~line59 一起换掉，
        // 一下变成 11 处改动，"远处"就不存在了（第一版就是这么写错的）。
        val new = old.lines().map { if (it == "line5") "CHANGED" else it }.joinToString("\n")
        val texts = rows(old, new).map { it.text }
        assertTrue("改动附近应在", texts.contains("CHANGED"))
        assertTrue("远处无关行应折叠掉", !texts.contains("line50"))
    }

    @Test
    fun `折叠处插分隔标记`() {
        val old = (1..60).joinToString("\n") { "line$it" }
        val new = old.replace("line5", "X").replace("line55", "Y")
        val texts = rows(old, new).map { it.text }
        assertTrue("两块改动之间应有 ⋯ 分隔", texts.contains("⋯"))
    }

    @Test
    fun `文件开头不留分隔标记`() {
        val old = (1..60).joinToString("\n") { "line$it" }
        val new = old.replace("line2", "HEADCHANGE")
        val first = rows(old, new).first()
        assertEquals("首行不该是分隔标记", DiffRowKind.SAME, first.kind)
        assertTrue("首行内容不该是 ⋯", first.text != "⋯")
    }

    @Test
    fun `全未变更时不取行（空改动不占变更卡位）`() {
        // 无增删行 → 变更卡整张不该出现，由 ChatViewModel.ensureDiff 提前拦掉
        assertTrue(rows("same\ntext", "same\ntext").isEmpty())
    }

    @Test
    fun `新建文件每行都是新增且无旧行号`() {
        // 注意：这里直接构造 diffText("x\ny\nz", "") 的等价输入前，先确认空串会污染 diff。
        // ChatViewModel 对新建文件不走 diffText（before==null 直接全记 ADDED），
        // 否则 "".split('\n') == [""] 会先匹配上，凭空多出一行 "−"。
        val asNew = contextOf(
            listOf(
                DiffLine(DiffType.ADDED, "x"),
                DiffLine(DiffType.ADDED, "y"),
                DiffLine(DiffType.ADDED, "z")
            )
        )
        assertTrue(asNew.all { it.kind == DiffRowKind.ADDED })
        assertTrue("新增行不该有旧行号", asNew.all { it.oldNo == null })
    }

    @Test
    fun `空串喂给 diffText 会产生假删除行（新建文件必须绕开这条路）`() {
        val polluted = TextDiff.diffText("", "x\ny").lines
        assertTrue(
            "\"\" 会先匹配上并产出一行 REMOVED —— 这是新建文件不能走 diffText 的原因",
            polluted.any { it.type == DiffType.REMOVED }
        )
    }

    @Test
    fun `大改动块会超过自动展开阈值（供 UI 判收起）`() {
        // 60 行全替换 → 取行后仍远大于 12 行阈值，UI 会默认收起，头部只留 +N−M
        val old = (1..60).joinToString("\n") { "old$it" }
        val new = (1..60).joinToString("\n") { "new$it" }
        assertTrue("应超过 12 行阈值", rows(old, new).size > 12)
    }
}