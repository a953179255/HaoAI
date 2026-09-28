package com.haoai.pc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 检查点（"回到这次任务之前"）的判据。
 *
 * 回滚是用户最信任的一步，也是最不容错的一步：
 * 退错版本、退到中间态、静默漏掉一个文件，比"不能回滚"更糟。
 * 所以这里每条都在**真文件系统**上跑真工具（write/edit），再验盘上的字节。
 */
class CheckpointTest {

    private class YesGate : Gate {
        override fun approve(title: String, detail: String, kind: String) = true
        override fun ask(question: String, options: List<String>) = ""
    }

    private lateinit var ws: File

    private fun ctx(run: String = "r1", sid: String = "s1") =
        ToolCtx(ws, PcSettings(), "auto", YesGate(), mutableListOf(), run, sid)

    private fun args(s: String): kotlinx.serialization.json.JsonObject =
        Json.parseToJsonElement(s).jsonObject

    private fun write(c: ToolCtx, path: String, content: String) {
        val r = WriteTool().run(args("""{"path":"$path","content":"$content"}"""), c)
        assertFalse("write 失败：" + r.content, r.error)
    }

    private fun edit(c: ToolCtx, path: String, old: String, new: String) {
        val r = EditTool().run(
            args("""{"path":"$path","old_string":"$old","new_string":"$new"}"""), c)
        assertFalse("edit 失败：" + r.content, r.error)
    }

    @Before
    fun setUp() {
        val home = Files.createTempDirectory("haoai-ck").toFile()
        // 每条测试一个状态根：账本是全局追加的，共用会把别的测试的轮次混进列表里
        System.setProperty("haoai.home", File(home, "state").absolutePath)
        ws = File(home, "ws").apply { mkdirs() }
    }

    @Test
    fun `rewinding a run restores every file it touched`() {
        val seed = ctx("")
        write(seed, "a.txt", "one")
        write(seed, "b.txt", "two")
        val r = ctx("r-run")
        write(r, "a.txt", "ONE")
        edit(r, "b.txt", "two", "TWO")
        assertEquals("先确认改过了", "ONE", File(ws, "a.txt").readText())

        val rep = Checkpoints.rewind(ws, "r-run")
        assertTrue(rep.note, rep.ok)
        assertEquals("两个都该还原：" + rep.restored, 2, rep.restored.size)
        assertEquals("one", File(ws, "a.txt").readText())
        assertEquals("TWO 该被退掉", "two", File(ws, "b.txt").readText())
    }

    @Test
    fun `the earliest snapshot wins when a file is touched twice`() {
        write(ctx(""), "a.txt", "v0")
        val r = ctx("r-twice")
        write(r, "a.txt", "v1")
        write(r, "a.txt", "v2")
        val rep = Checkpoints.rewind(ws, "r-twice")
        assertTrue(rep.note, rep.ok)
        assertEquals("退到这一轮**开始之前**，不是最后一步之前", "v0", File(ws, "a.txt").readText())
    }

    @Test
    fun `a file created by the run is deleted on rewind`() {
        val r = ctx("r-new")
        write(r, "made.txt", "这轮新建的")
        assertTrue(File(ws, "made.txt").isFile)
        val rep = Checkpoints.rewind(ws, "r-new")
        assertTrue(rep.note, rep.ok)
        assertEquals(listOf("made.txt"), rep.deleted)
        assertFalse("新建的文件该被删掉", File(ws, "made.txt").exists())
    }

    @Test
    fun `paths outside the workspace are refused and left alone`() {
        val outside = File(ws.parentFile, "evil.txt")
        outside.writeText("别碰我")
        Checkpoints.note(ctx("r-out"), outside, null)
        val rep = Checkpoints.rewind(ws, "r-out")
        assertFalse("该报出来而不是静默跳过", rep.ok)
        assertTrue("没处理的那条要说清楚：" + rep.missing, rep.missing.isNotEmpty())
        assertEquals("工作区外的文件一个字都不该动", "别碰我", outside.readText())
    }

    @Test
    fun `a missing snapshot is reported not skipped`() {
        write(ctx(""), "a.txt", "v0")
        val r = ctx("r-snap")
        write(r, "a.txt", "v1")
        val snap = Checkpoints.entries().first { it.run == "r-snap" && it.snap.isNotBlank() }.snap
        assertTrue(File(snap).delete())
        val rep = Checkpoints.rewind(ws, "r-snap")
        assertFalse("快照没了就不该说成功", rep.ok)
        assertTrue(rep.missing.joinToString(), rep.missing.any { it.contains("快照") })
    }

    @Test
    fun `an unknown run says so in words`() {
        val rep = Checkpoints.rewind(ws, "never-happened")
        assertFalse(rep.ok)
        assertTrue(rep.note, rep.note.contains("没有登记"))
    }

    @Test
    fun `a run with no id leaves no ledger entry`() {
        // CLI 单发一条命令、测试里手搓 ctx，都不该往账本里塞东西
        write(ctx(""), "a.txt", "one")
        assertTrue(Checkpoints.entries().isEmpty())
    }

    @Test
    fun `the list names each run and counts its files`() {
        Checkpoints.begin("s1", "r1", "把三份笔记改成中文")
        val c = ctx("r1")
        write(c, "n1.md", "一")
        write(c, "n2.md", "二")
        Checkpoints.begin("s1", "empty-run", "什么都没动")
        val o = Json.parseToJsonElement(Checkpoints.listJson("s1")).jsonObject
        assertEquals("只列真动过文件的轮次", 1, o["items"]!!.jsonArray.size)
        val row = o["items"]!!.jsonArray[0].jsonObject
        assertEquals("r1", row["run"]!!.jsonPrimitive.content)
        assertEquals(2, row["files"]!!.jsonPrimitive.content.toInt())
        assertEquals("把三份笔记改成中文", row["goal"]!!.jsonPrimitive.content)
    }

    @Test
    fun `the ledger stays bounded`() {
        repeat(4200) { Checkpoints.note(ctx("rb"), File(ws, "f$it.txt"), null) }
        val lines = Checkpoints.file.readLines().count { it.isNotBlank() }
        assertTrue("账本该有上限，实际 $lines", lines <= 4000)
        assertTrue("留下的是最近的记录", Checkpoints.entries(1).first().path.endsWith("f4199.txt"))
    }

    /* ---- "回到这一句之前"（v0.70.0）---- */

    @Test
    fun `a message index finds the run that produced it`() {
        Checkpoints.begin("s1", "run-a", "第一轮", at = 0)
        Checkpoints.begin("s1", "run-b", "第二轮", at = 4)
        Checkpoints.begin("s1", "run-c", "第三轮", at = 8)
        assertEquals("点在这轮中间也算这一轮", "run-b" to 4, Checkpoints.runCovering("s1", 6))
        assertEquals("点在这轮第一句上", "run-b" to 4, Checkpoints.runCovering("s1", 4))
        assertEquals("最早那轮从 0 开始", "run-a" to 0, Checkpoints.runCovering("s1", 0))
        assertEquals("别的会话的消息不该混进来", null, Checkpoints.runCovering("s2", 6))
        assertEquals("退要连着后面的轮次一起退", listOf("run-b", "run-c"), Checkpoints.runsFrom("s1", 4))
        assertEquals(listOf("run-c"), Checkpoints.runsFrom("s1", 8))
    }

    @Test
    fun `a run recorded before the index existed is never guessed at`() {
        // 老账本没有 at 字段。宁可回退不了，也不能猜一个轮次去退 —— 退错版本比不能退危险得多。
        Checkpoints.begin("s1", "old-run", "早于这个功能的轮次")
        assertEquals(-1, Checkpoints.entries().first { it.path.isEmpty() }.at)
        assertEquals(null, Checkpoints.runCovering("s1", 3))
        assertEquals(emptyList<String>(), Checkpoints.runsFrom("s1", 0))
    }

    @Test
    fun `rewinding several runs goes back to before the first of them`() {
        write(ctx(""), "a.txt", "v0")
        Checkpoints.begin("s1", "r-a", "改一次", at = 0)
        write(ctx("r-a"), "a.txt", "v1")
        write(ctx("r-a"), "made.txt", "第一轮建的")
        Checkpoints.begin("s1", "r-b", "再改一次", at = 3)
        write(ctx("r-b"), "a.txt", "v2")
        write(ctx("r-b"), "late.txt", "第二轮建的")
        val rep = Checkpoints.rewindAll(ws, listOf("r-a", "r-b"))
        assertTrue(rep.note, rep.ok)
        assertEquals("同一路径取这批里最早那份：那才是这一轮开始前的样子",
            "v0", File(ws, "a.txt").readText())
        assertFalse("两轮各自新建的文件都要消失", File(ws, "made.txt").exists())
        assertFalse(File(ws, "late.txt").exists())
        assertEquals(listOf("late.txt", "made.txt"), rep.deleted.sorted())
        assertTrue("话说了几轮：" + rep.note, rep.note.contains("2 轮"))
    }
}
