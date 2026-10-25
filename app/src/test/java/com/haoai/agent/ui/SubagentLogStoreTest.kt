package com.haoai.agent.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * P4b 子代理过程旁路存储：回合结束后历史链卡回看的数据层。
 * 覆盖 round trip / 覆盖写 / 损坏回退 / 会话隔离 / 彻底删除 / 路径过滤。
 */
class SubagentLogStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store() = SubagentLogStore(tmp.newFolder("subagent_logs"))

    private fun line(
        id: String, index: Int, state: String, result: String? = null
    ) = SubagentLine(
        id = id, index = index, total = 4, state = state,
        tokensUsed = 1234L + index, brief = "fetch(example.com) → 200",
        task = "task $index 原文", steps = listOf("fetch → 200", "summarize"),
        lastAssistant = "中间结论 $index", finalResult = result
    )

    @Test
    fun `save then load round trip`() {
        val s = store()
        val lines = listOf(
            line("sa_1", 1, "DONE", "结论一"),
            line("sa_2", 2, "DONE", "结论二")
        )
        s.save("sidA", "call_1", lines)
        s.flushForTests()
        assertEquals(lines, s.load("sidA", "call_1"))
    }

    @Test
    fun `later save overwrites earlier snapshot`() {
        val s = store()
        s.save("sidA", "call_1", listOf(line("sa_1", 1, "RUNNING")))
        s.flushForTests()
        val done = listOf(line("sa_1", 1, "DONE", "最终结论"))
        s.save("sidA", "call_1", done)
        s.flushForTests()
        assertEquals(done, s.load("sidA", "call_1"))
    }

    @Test
    fun `empty lines are not persisted`() {
        val s = store()
        s.save("sidA", "call_1", emptyList())
        s.flushForTests()
        assertFalse(s.has("sidA", "call_1"))
        assertNull(s.load("sidA", "call_1"))
    }

    @Test
    fun `missing file returns null`() {
        val s = store()
        assertNull(s.load("sidA", "no_such_call"))
    }

    @Test
    fun `corrupted file returns null not crash`() {
        val s = store()
        s.save("sidA", "call_1", listOf(line("sa_1", 1, "DONE", "x")))
        s.flushForTests()
        // 截断主文件、删掉 .bak：模拟写一半进程被杀的残留
        val f = File(tmp.root, "subagent_logs/sidA/call_1.json")
        f.writeText("""[{"id":"sa_1","index":1,"total""")
        File(f.parentFile, "call_1.json.bak").delete()
        assertNull(s.load("sidA", "call_1"))
    }

    @Test
    fun `sessions are isolated by sid`() {
        val s = store()
        s.save("sidA", "call_1", listOf(line("sa_1", 1, "DONE", "A 的结论")))
        s.flushForTests()
        s.save("sidB", "call_1", listOf(line("sa_1", 1, "DONE", "B 的结论")))
        s.flushForTests()
        assertEquals("A 的结论", s.load("sidA", "call_1")?.single()?.finalResult)
        assertEquals("B 的结论", s.load("sidB", "call_1")?.single()?.finalResult)
    }

    @Test
    fun `clearSession removes only that session`() {
        val s = store()
        s.save("sidA", "call_1", listOf(line("sa_1", 1, "DONE")))
        s.flushForTests()
        s.save("sidB", "call_1", listOf(line("sa_1", 1, "DONE")))
        s.flushForTests()
        s.clearSession("sidA")
        assertFalse(s.has("sidA", "call_1"))
        assertTrue(s.has("sidB", "call_1"))
    }

    @Test
    fun `callId with path separators is sanitized`() {
        val s = store()
        s.save("sidA", "../../evil", listOf(line("sa_1", 1, "DONE")))
        s.flushForTests()
        // 过滤后落在 sidA 目录内，不会越出根
        assertTrue(s.has("sidA", "../../evil"))
        assertFalse(File(tmp.root, "evil.json").exists())
    }

    @Test
    fun `unicode content survives round trip`() {
        val s = store()
        val lines = listOf(
            line("sa_1", 1, "DONE", "中文结论：三条猜测两条被推翻，一条成立 ✅")
        )
        s.save("sidA", "call_u", lines)
        s.flushForTests()
        assertEquals(lines, s.load("sidA", "call_u"))
    }
}
