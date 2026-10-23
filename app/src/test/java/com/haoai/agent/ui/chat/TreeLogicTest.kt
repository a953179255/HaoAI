package com.haoai.agent.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 批4b 瘦身：flattenTree/ancestorsOf 随三 Tab 壳退役，本测试只钉死还活着的
 * normalizeRel / searchPaths（查看器与浏览页共用口径）。
 */
class TreeLogicTest {

    @Test
    fun `normalizeRel 去重斜杠与点段`() {
        assertEquals("src/main/App.kt", normalizeRel("/src//main/./App.kt"))
        assertEquals("a.txt", normalizeRel("a.txt"))
    }

    @Test
    fun `搜索按大小写不敏感子串匹配并排序封顶`() {
        val entries = listOf("a/B.kt", "x/c.txt", "a/b.txt", "z/ignore.md")
            .map { com.haoai.agent.platform.FileEntry(it, 0L, 0L) }
        // 大小写不敏感：query "b" 命中 a/B.kt 与 a/b.txt；默认排序（大写先）
        assertEquals(listOf("a/B.kt", "a/b.txt"), searchPaths(entries, "b"))
        // "B.kt" 不含 ".t"，只有两个 .txt 命中
        assertEquals(listOf("a/b.txt", "x/c.txt"), searchPaths(entries, ".t"))
        // 空查询不返回全量（搜索态由 UI 的 null 表示）
        assertTrue(searchPaths(entries, "").isEmpty())
        assertEquals(2, searchPaths(entries, "t", limit = 2).size)
    }
}
