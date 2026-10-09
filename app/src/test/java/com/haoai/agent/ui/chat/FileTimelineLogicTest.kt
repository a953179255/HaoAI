package com.haoai.agent.ui.chat

import com.haoai.agent.agent.tools.snapshot.FileSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 批3d：版本时间轴纯逻辑（归组/版本序列/选择态）。 */
class FileTimelineLogicTest {

    private fun meta(callId: String, path: String, ts: Long) =
        FileSnapshot.Meta(callId, path, ts, 100)

    @Test
    fun `多文件按最近变更排序`() {
        val files = groupHistory(
            listOf(
                meta("a1", "old.txt", 1000),
                meta("b1", "new.txt", 2000),
                meta("b2", "new.txt", 3000)
            )
        )
        assertEquals(listOf("new.txt", "old.txt"), files.map { it.relPath })
        assertEquals(2, files[0].versionCount)
        assertEquals(3000L, files[0].lastTs)
    }

    @Test
    fun `路径归一合并同文件`() {
        val files = groupHistory(listOf(meta("a1", "/src//a.txt", 1), meta("a2", "src/a.txt", 2)))
        assertEquals(1, files.size)
        assertEquals("src/a.txt", files[0].relPath)
    }

    @Test
    fun `版本序列含初始版时前置`() {
        val metas = listOf(meta("c1", "a.txt", 1), meta("c2", "a.txt", 2))
        val v = buildVersions(metas, hasInitial = true)
        assertEquals(listOf("初始版", "V1", "V2"), v.map { it.label })
        assertEquals("c1:before", v[0].key)
        assertEquals("c2:after", v[2].key)
    }

    @Test
    fun `会话内新建文件无初始版`() {
        val metas = listOf(meta("c1", "a.txt", 1))
        val v = buildVersions(metas, hasInitial = false)
        assertEquals(listOf("V1"), v.map { it.label })
    }

    @Test
    fun `versionsOf 只取目标文件保持时间序`() {
        val picked = versionsOf(
            listOf(meta("a1", "x.txt", 1), meta("b1", "y.txt", 2), meta("a2", "x.txt", 3)),
            "x.txt"
        )
        assertEquals(listOf("a1", "a2"), picked.map { it.callId })
    }

    @Test
    fun `选择态最多两个先进先出`() {
        assertEquals(listOf("k1", "k2"), toggleSelect(toggleSelect(emptyList(), "k1"), "k2"))
        assertEquals(listOf("k2", "k3"), toggleSelect(listOf("k1", "k2"), "k3"))
        assertEquals(listOf("k1"), toggleSelect(listOf("k1", "k2"), "k2"))
    }

    @Test
    fun `空 metas 空清单`() {
        assertTrue(groupHistory(emptyList()).isEmpty())
        assertTrue(buildVersions(emptyList(), true).isEmpty())
    }
}
