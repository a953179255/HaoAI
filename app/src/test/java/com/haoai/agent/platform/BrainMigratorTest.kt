package com.haoai.agent.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** v2 脑分家：一次性迁移的安全性回归（目标赢/校验失败保留源/幂等/材料不碰）。 */
class BrainMigratorTest {

    private fun tmp(tag: String): File =
        Files.createTempDirectory("haoai-brain-$tag").toFile().apply { deleteOnExit() }

    private fun brainSrc(tag: String): Pair<File, File> {
        val ws = tmp("ws-$tag").apply {
            resolve("MEMORY.md").writeText("- [id1] 旧记忆")
            resolve("USER.md").writeText("用户画像")
            resolve("memory").apply { mkdirs() }.resolve("2026-10-23.md").writeText("日记")
            resolve("skills").apply { mkdirs() }.resolve("web-research").apply { mkdirs() }
                .resolve("SKILL.md").writeText("技能")
            resolve("todos").apply { mkdirs() }.resolve("s1.json").writeText("[]")
            resolve("dreaming").apply { mkdirs() }.resolve("2026-10-22.md").writeText("报告")
            // 材料：绝不碰
            resolve("repos").apply { mkdirs() }.resolve("code.c").writeText("材料")
            resolve("report.md").writeText("材料")
        }
        val brain = tmp("brain-$tag")
        return ws to brain
    }

    @Test
    fun `全新安装直接写marker不搬任何东西`() {
        val (ws, brain) = brainSrc("fresh")
        // 全新：工作区也是空的（模拟新装）
        ws.listFiles()?.forEach { it.deleteRecursively() }
        val r = BrainMigrator.splitIfNeeded(brain, ws, tmp("filesdir"))
        assertEquals(0, r.moved)
        assertTrue(r.ok)
        assertTrue(BrainMigrator.isSplitDone(brain))
    }

    @Test
    fun `旧安装脑文件搬进brain且源被清掉内容一致`() {
        val (ws, brain) = brainSrc("move")
        val r = BrainMigrator.splitIfNeeded(brain, ws, tmp("filesdir"))
        // 4 目录 + 2 文件 = 6 条
        assertEquals(6, r.moved)
        assertTrue(r.ok)
        assertEquals("- [id1] 旧记忆", brain.resolve("MEMORY.md").readText())
        assertEquals("用户画像", brain.resolve("USER.md").readText())
        assertEquals("日记", brain.resolve("memory/2026-10-23.md").readText())
        assertEquals("技能", brain.resolve("skills/web-research/SKILL.md").readText())
        assertEquals("[]", brain.resolve("todos/s1.json").readText())
        assertEquals("报告", brain.resolve("dreaming/2026-10-22.md").readText())
        // 源里脑条目已消失
        assertFalse(ws.resolve("MEMORY.md").exists())
        assertFalse(ws.resolve("skills").exists())
        // 材料原地不动
        assertTrue(ws.resolve("repos/code.c").exists())
        assertTrue(ws.resolve("report.md").exists())
        assertFalse(brain.resolve("repos").exists())
    }

    @Test
    fun `目标赢不覆盖且源旧副本保留降级成材料`() {
        val (ws, brain) = brainSrc("target-wins")
        brain.resolve("MEMORY.md").writeText("- [id9] 新脑更新") // 上次分家成果
        val r = BrainMigrator.splitIfNeeded(brain, ws, tmp("filesdir"))
        assertEquals(1, r.skipped) // MEMORY.md 跳过，其余 5 条照搬
        assertEquals(5, r.moved)
        // 脑里的新内容没被旧工作区副本冲掉
        assertEquals("- [id9] 新脑更新", brain.resolve("MEMORY.md").readText())
        // 源的旧副本保留（不误删用户数据）
        assertTrue(ws.resolve("MEMORY.md").exists())
    }

    @Test
    fun `filesDir来源的todos也被收编`() {
        val ws = tmp("ws-legacy")
        val filesDir = tmp("filesdir-legacy").apply {
            resolve("todos").apply { mkdirs() }.resolve("s9.json").writeText("[{}]")
        }
        val brain = tmp("brain-legacy")
        val r = BrainMigrator.splitIfNeeded(brain, ws, filesDir)
        assertEquals(1, r.moved)
        assertEquals("[{}]", brain.resolve("todos/s9.json").readText())
        assertFalse(filesDir.resolve("todos").exists())
    }

    @Test
    fun `marker已写第二次调用是彻底no-op`() {
        val (ws, brain) = brainSrc("idem")
        BrainMigrator.splitIfNeeded(brain, ws, tmp("fd"))
        val brainBefore = brain.walkTopDown().map { it.name }.sorted().joinToString()
        // 再塞一个脑文件进工作区，模拟分家后用户又放了个同名文件（不应被二次搬运）
        ws.resolve("USER.md").writeText("x")
        val r = BrainMigrator.splitIfNeeded(brain, ws, tmp("fd2"))
        assertEquals(0, r.moved)
        assertEquals(0, r.failed)
        assertEquals(brainBefore, brain.walkTopDown().map { it.name }.sorted().joinToString())
    }

    @Test
    fun `脑根不可写整体失败零搬运且条目全部尝试过`() {
        val (ws, brain) = brainSrc("rootfile")
        // brainRoot 位置被一个普通文件占了 → 所有条目都搬不了。
        // failed>=6 同时证明"一条失败不中断整轮"（6 个脑条目全部尝试过）
        val brainAsFile = brain.parentFile.resolve("brain-as-file-${System.nanoTime()}")
        brainAsFile.writeText("not a dir")
        val r = BrainMigrator.splitIfNeeded(brainAsFile, ws, tmp("fd"))
        assertFalse(r.ok)
        assertTrue(r.failed >= 6)
        // 源全保留
        assertTrue(ws.resolve("MEMORY.md").exists())
        assertTrue(ws.resolve("skills").exists())
        // 无 marker
        assertFalse(BrainMigrator.isSplitDone(brainAsFile))
        brainAsFile.delete()
    }

    @Test
    fun `来源根等于脑根时防自搬`() {
        val brain = tmp("self")
        brain.resolve("MEMORY.md").writeText("已有")
        val r = BrainMigrator.splitIfNeeded(brain, brain)
        assertEquals(0, r.moved)
        assertTrue(r.ok)
        assertEquals("已有", brain.resolve("MEMORY.md").readText())
    }
}
