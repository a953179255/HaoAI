package com.haoai.agent.ui.chat

import com.haoai.agent.platform.FileEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/** 批4b：工作区浏览单页纯逻辑（行合成/面包屑/父子路径/格式化）。 */
class WorkspaceBrowserLogicTest {

    private val entries = listOf(
        FileEntry("src/App.kt", 2048, 1000L),
        FileEntry("src/main/deep.txt", 300, 2000L),
        FileEntry("README.md", 5 * 1024 * 1024, 3000L),
    )

    @Test
    fun `根层行合成：目录保留尾名与 isDir，文件带 walk 元信息`() {
        val rows = buildBrowserRows(listOf("src/", "README.md"), entries, "")
        assertEquals(2, rows.size)
        val dir = rows[0]
        assertEquals("src", dir.rel)
        assertEquals(true, dir.isDir)
        assertEquals(0L, dir.size) // 目录不显示大小
        val f = rows[1]
        assertEquals("README.md", f.rel)
        assertEquals(5 * 1024 * 1024, f.size)
        assertEquals(3000L, f.lastModified)
    }

    @Test
    fun `子层行合成 rel 带父前缀`() {
        val rows = buildBrowserRows(listOf("main/", "App.kt"), entries, "src")
        assertEquals("src/main", rows[0].rel)
        assertEquals("src/App.kt", rows[1].rel)
        assertEquals(2048L, rows[1].size)
    }

    @Test
    fun `walk 缺条目的文件行防御出零元信息不崩`() {
        val rows = buildBrowserRows(listOf("ghost.bin"), emptyList(), "")
        assertEquals(1, rows.size)
        assertEquals(0L, rows[0].size)
        assertEquals(0L, rows[0].lastModified)
    }

    @Test
    fun `面包屑段`() {
        assertTrue(crumbSegments("").isEmpty())
        assertEquals(listOf("a"), crumbSegments("a"))
        assertEquals(listOf("a", "b", "c"), crumbSegments("a/b/c"))
    }

    @Test
    fun `父路径`() {
        assertEquals("", parentOf("a.txt"))
        assertEquals("a/b", parentOf("a/b/c.txt"))
        assertEquals("", parentOf("a"))
    }

    @Test
    fun `大小格式化`() {
        assertEquals("", formatRowSize(0))
        assertEquals("900 B", formatRowSize(900))
        assertEquals("2 KB", formatRowSize(2048))
        assertEquals("1.0 MB", formatRowSize(1048576))
    }

    @Test
    fun `时间三档：今天时分-本年月日-跨年年月日`() {
        val now = System.currentTimeMillis()
        fun at(daysAgo: Long, hour: Int = 12): Long =
            Calendar.getInstance().apply {
                timeInMillis = now
                add(Calendar.DAY_OF_YEAR, -daysAgo.toInt())
                set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, 30)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }.timeInMillis
        // 今天 → HH:mm
        val today = formatRowTime(at(0), now)
        assertEquals(true, today.matches(Regex("\\d{2}:30")))
        // 本年（30 天前多半跨年边界不管，取 2 天前稳在本年内）→ MM-dd HH:mm
        val two = formatRowTime(at(2), now)
        if (two.length == 16) {
            // 同年：格式 MM-dd HH:mm
            assertEquals(true, two.matches(Regex("\\d{2}-\\d{2} \\d{2}:30")))
        }
        // 3 年前 → yyyy-MM-dd
        val old = Calendar.getInstance().apply {
            timeInMillis = now; add(Calendar.YEAR, -3)
        }.timeInMillis
        assertTrue(formatRowTime(old, now).matches(Regex("\\d{4}-\\d{2}-\\d{2}")))
        assertEquals("", formatRowTime(0, now))
    }
}
