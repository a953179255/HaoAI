package com.haoai.agent.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 批3a：文件树纯逻辑口径钉死测试。
 *
 * 树的状态分两块——children（rel → listDir 结果）和 expanded（展开的目录 rel），
 * flattenTree 把它们拼成扁平行表喂 LazyColumn。判据全在这儿，Compose 侧只是消费。
 */
class TreeLogicTest {

    private val sample = mapOf(
        "" to listOf("src/", "README.md"),
        "src" to listOf("main/", "Test.kt"),
        "src/main" to listOf("App.kt")
    )

    @Test
    fun `只展开根时只出根行`() {
        val rows = flattenTree(sample, setOf(""))
        assertEquals(
            listOf("src/", "README.md"),
            rows.map { if (it.isDir) it.name + "/" else it.name }
        )
        assertEquals(listOf(0, 0), rows.map { it.depth })
    }

    @Test
    fun `展开目录后子行深度递增且带完整路径`() {
        val rows = flattenTree(sample, setOf("", "src"))
        val srcMain = rows.first { it.name == "main" }
        assertEquals("src/main", srcMain.path)
        assertEquals(1, srcMain.depth)
        assertTrue(rows.any { it.path == "src/Test.kt" })
        // 未展开 src/main 之前，App.kt 不出现在行表
        assertTrue(rows.none { it.path == "src/main/App.kt" })
    }

    @Test
    fun `深层全展开后末级文件路径完整`() {
        val rows = flattenTree(sample, setOf("", "src", "src/main"))
        assertEquals(
            "src/main/App.kt",
            rows.first { it.name == "App.kt" }.path
        )
        assertEquals(2, rows.first { it.name == "App.kt" }.depth)
    }

    @Test
    fun `未加载的目录按未展开处理不崩`() {
        // expanded 记了但 children 没这层（异步还没回来）→ 出行不出内容
        val rows = flattenTree(sample, setOf("", "src"))
        assertTrue(rows.any { it.path == "src/main" && it.isDir })
        assertTrue(rows.none { it.path.startsWith("src/main/") })
    }

    // ════════ 祖先链（定位用） ════════

    @Test
    fun `祖先链含根且逐级不含末段文件名`() {
        assertEquals(
            listOf("", "a", "a/b"),
            ancestorsOf("a/b/c.txt")
        )
    }

    @Test
    fun `根层文件祖先只有根`() {
        assertEquals(listOf(""), ancestorsOf("hello.txt")) }

    @Test
    fun `反斜杠与首斜杠归一`() {
        assertEquals(listOf("", "site"), ancestorsOf("/site/page.html"))
    }

    // ════════ rel 归一 ════════

    @Test
    fun `normalizeRel 去重斜杠与点段`() {
        assertEquals("src/main/App.kt", normalizeRel("/src//main/./App.kt"))
        assertEquals("a.txt", normalizeRel("a.txt"))
    }

    // ════════ 搜索过滤 ════════

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
