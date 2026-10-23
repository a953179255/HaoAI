package com.haoai.agent.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** 批4b 三档改造：工作区切换迁移器内核（同名不覆盖/递归/防御）。 */
class WorkspaceMigrateTest {

    private fun tmpDir(tag: String): File =
        Files.createTempDirectory("haoai-ws-$tag").toFile().apply { deleteOnExit() }

    @Test
    fun `递归复制保留子目录结构并计数`() {
        val src = tmpDir("src").apply {
            resolve("MEMORY.md").writeText("记忆")
            resolve("skills/web/SKILL.md").apply { parentFile.mkdirs() }.writeText("技能")
            resolve("notes").apply { mkdirs() } // 空目录不算文件数
        }
        val dst = tmpDir("dst")
        val copied = copyDirTree(src, dst)
        assertEquals(2, copied)
        assertEquals("记忆", dst.resolve("MEMORY.md").readText())
        assertEquals("技能", dst.resolve("skills/web/SKILL.md").readText())
        assertTrue(dst.resolve("notes").isDirectory)
    }

    @Test
    fun `目标同名文件不覆盖`() {
        val src = tmpDir("s2").apply { resolve("MEMORY.md").writeText("新版") }
        val dst = tmpDir("d2").apply { resolve("MEMORY.md").writeText("旧版") }
        val copied = copyDirTree(src, dst)
        assertEquals(0, copied)
        assertEquals("旧版", dst.resolve("MEMORY.md").readText())
    }

    @Test
    fun `源不存在或为文件返回零`() {
        val missing = File(tmpDir("s3"), "ghost")
        assertEquals(0, copyDirTree(missing, tmpDir("d3a")))
        val plainFile = tmpDir("s4").apply { resolve("a.txt").writeText("x") }.resolve("a.txt")
        assertEquals(0, copyDirTree(plainFile, tmpDir("d4")))
    }

    @Test
    fun `进度回调逐文件递增且跨子目录累计`() {
        val src = tmpDir("s5").apply {
            resolve("a.txt").writeText("1")
            resolve("b.txt").writeText("2")
            resolve("sub/c.txt").apply { parentFile.mkdirs() }.writeText("3")
        }
        val dst = tmpDir("d5")
        val seen = mutableListOf<Int>()
        val copied = copyDirTree(src, dst) { done -> seen.add(done); true }
        assertEquals(3, copied)
        // 全局计数跨递归累计：应为 1,2,3 各一次（顺序按遍历，末值必为 3）
        assertEquals(listOf(1, 2, 3), seen.sorted())
    }

    @Test
    fun `进度回调返回false中止复制`() {
        val src = tmpDir("s6").apply {
            for (i in 1..10) resolve("f$i.txt").writeText("x")
        }
        val dst = tmpDir("d6")
        var calls = 0
        // 回调语义：true=继续 false=中止；done<3 即复制到第 3 个时返回 false 停止
        val copied = copyDirTree(src, dst) { done -> calls = done; done < 3 }
        // 第 3 次回调返回 false → 中止，内核上抛 -1；前 3 个文件已落盘
        assertEquals(-1, copied)
        assertEquals(3, calls)
    }
}
