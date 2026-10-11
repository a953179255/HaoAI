package com.haoai.agent.ui.chat

import com.haoai.agent.platform.FileEntry
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

/** 工作区统计纯逻辑单测（设置·工作区浏览行副标题的数据层）。 */
class WorkspaceShellLogicTest {

    private fun dayStart(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun entry(path: String, size: Long, agoDays: Long = 0) = FileEntry(
        path, size, dayStart() - agoDays * 86_400_000L + (if (agoDays == 0L) 3_600_000L else 0L)
    )

    @Test
    fun `目录条目不计入文件数`() {
        val s = workspaceStatsOf(listOf(entry("docs/", 0), entry("a.md", 10)), System.currentTimeMillis())
        assertEquals(1, s.fileCount)
        assertEquals(10L, s.totalBytes)
    }

    @Test
    fun `今日与历史分档`() {
        val now = System.currentTimeMillis()
        val s = workspaceStatsOf(
            listOf(entry("a.md", 10, agoDays = 0), entry("b.md", 20, agoDays = 2)), now
        )
        assertEquals(2, s.fileCount)
        assertEquals(1, s.todayCount)
    }

    @Test
    fun `空工作区统计为零`() {
        val s = workspaceStatsOf(emptyList(), System.currentTimeMillis())
        assertEquals(0, s.fileCount)
        assertEquals(0L, s.totalBytes)
        assertEquals(0, s.todayCount)
    }

    @Test
    fun `副标题KB档与今日段`() {
        val text = formatWorkspaceStats(WorkspaceStats(14, 92 * 1024L, 6))
        assertEquals("14 文件 · 92 KB · 今天产出 6 个", text)
    }

    @Test
    fun `副标题无今日产出时省略该段`() {
        val text = formatWorkspaceStats(WorkspaceStats(3, 512L, 0))
        assertEquals("3 文件 · 512 B", text)
    }

    @Test
    fun `副标题MB档一位小数`() {
        val text = formatWorkspaceStats(WorkspaceStats(2, (2.5 * 1048576).toLong(), 0))
        assertEquals("2 文件 · 2.5 MB", text)
    }
}
