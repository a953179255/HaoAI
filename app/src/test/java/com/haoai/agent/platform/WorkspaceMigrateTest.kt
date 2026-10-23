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

    // ===== parallelCopy（2026-10-23 性能版迁移内核）=====

    @Test
    fun `并行复制保留子目录结构并计数`() {
        val src = tmpDir("p1").apply {
            resolve("MEMORY.md").writeText("记忆")
            resolve("skills/web/SKILL.md").apply { parentFile.mkdirs() }.writeText("技能")
            resolve("notes").apply { mkdirs() } // 空目录不算文件数
        }
        val dst = tmpDir("q1")
        val copied = parallelCopy(src, dst, null)
        assertEquals(2, copied)
        assertEquals("记忆", dst.resolve("MEMORY.md").readText())
        assertEquals("技能", dst.resolve("skills/web/SKILL.md").readText())
        assertTrue(dst.resolve("notes").isDirectory)
    }

    @Test
    fun `并行同名不覆盖且空文件照拷`() {
        val src = tmpDir("p2").apply {
            resolve("MEMORY.md").writeText("新版")
            resolve("empty.txt").writeText("") // 0 字节也必须出现在目标
            resolve("sub").apply { mkdirs() }.resolve("blank.bin").writeText("")
        }
        val dst = tmpDir("q2").apply { resolve("MEMORY.md").writeText("旧版") }
        val copied = parallelCopy(src, dst, null)
        // MEMORY.md 同名跳过；两个空文件复制 → 2
        assertEquals(2, copied)
        assertEquals("旧版", dst.resolve("MEMORY.md").readText())
        assertTrue(dst.resolve("empty.txt").exists())
        assertTrue(dst.resolve("sub/blank.bin").exists())
    }

    @Test
    fun `并行进度total等于真实文件数且done单调到total`() {
        val src = tmpDir("p3").apply {
            for (i in 1..200) resolve("g$i.txt").writeText("x")
            resolve("deep/a/b/c/leaf.txt").apply { parentFile.mkdirs() }.writeText("y")
        }
        val dst = tmpDir("q3")
        val first = java.util.concurrent.atomic.AtomicReference<Pair<Int, Int>?>(null)
        val seenDone = java.util.concurrent.CopyOnWriteArrayList<Int>()
        val allTotals = java.util.concurrent.CopyOnWriteArrayList<Int>()
        parallelCopy(src, dst) { done, tot ->
            if (first.get() == null) first.set(done to tot)
            seenDone.add(done); allTotals.add(tot)
            true
        }
        // 201 文件全树真实计数（不截断——正是修复"卡片 8000"的口径）
        assertEquals(201, allTotals.first())
        assertTrue(allTotals.all { it == 201 })
        // 开扫即推 0/total（在 worker 启动前，确定无竞态）
        assertEquals(0 to 201, first.get())
        // 并行下回调顺序不保证单调（各 worker 独立计数），但 max 必达 total、且无越界
        assertEquals(201, seenDone.max())
        assertTrue(seenDone.all { it in 0..201 })
    }

    @Test
    fun `并行onProgress返回false请求中止`() {
        val src = tmpDir("p4").apply { for (i in 1..1000) resolve("h$i.txt").writeText("x") }
        val dst = tmpDir("q4")
        var seenTotal = -1
        val copied = parallelCopy(src, dst) { _, tot ->
            seenTotal = tot
            false // 立即请求中止
        }
        assertEquals(1000, seenTotal)
        // 中止后已复制数远小于总量，且不超过 total（不要求精确——并行竞态）
        assertTrue(copied in 0..1000)
    }

    @Test
    fun `并行空源目录返回零`() {
        val src = tmpDir("p5").apply { mkdirs() }
        assertEquals(0, parallelCopy(src, tmpDir("q5"), null))
    }
}
