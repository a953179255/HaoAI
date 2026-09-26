package com.haoai.pc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files

/**
 * S3 常驻交互进程的回归。
 *
 * 关键不是"能起一个进程"（那是一次性 shell 工具就会的事），而是
 * **同一个进程、状态在多次 send 之间保留**、以及**关掉之后不泄漏**。
 * 这两条一旦退化，表现就是"agent 说它执行了，其实每条命令都开在 new shell 里"。
 */
class PtyTest {

    private class AllowGate : Gate {
        override fun approve(title: String, detail: String, kind: String) = true
        override fun ask(question: String, options: List<String>) = ""
    }

    private class DenyGate : Gate {
        override fun approve(title: String, detail: String, kind: String) = false
        override fun ask(question: String, options: List<String>) = ""
    }

    /** 常驻进程是**全局单例**表，用例之间必须清干净，否则上一条漏的进程会让下一条误判。 */
    @org.junit.Before
    fun cleanSlate() {
        ProcRegistry.closeAll()
    }

    private fun ctx(gate: Gate = AllowGate()) =
        ToolCtx(Files.createTempDirectory("haoai-pty").toFile().apply { mkdirs() }, PcSettings(), "ask", gate)

    private fun args(json: String) = Json.parseToJsonElement(json).jsonObject

    @Test
    fun `a persistent shell answers and keeps state between sends`() {
        assumeTrue("这台机器上没有 Git Bash", ShellLauncher.persistentForName("bash") != null)
        val c = ctx()
        val opened = ShellOpenTool().run(args("""{"label":"calc","shell":"bash"}"""), c)
        assertFalse(opened.content, opened.error)
        val id = Regex("已启动 (\\S+)").find(opened.content)!!.groupValues[1]
        try {
            ShellSendTool().run(args("""{"id":"$id","text":"x=hello"}"""), c)
            val r1 = ShellReadTool().run(args("""{"id":"$id","wait_ms":2500}"""), c)
            assertFalse(r1.content, r1.error)

            val D = '$'   // shell 变量要用字面量 $，Kotlin 里只能绕出来
            val sendCmd = "{\"id\":\"$id\",\"text\":\"echo got-${D}x-${D}((6*7))\"}"
            val second = ShellSendTool().run(args(sendCmd), c)
            assertFalse(second.content, second.error)
            val r2 = ShellReadTool().run(args("""{"id":"$id","wait_ms":3000}"""), c)
            // got-hello-42：变量 x 还在 → 是同一个进程；算术展开 → 真的在跑 bash
            assertTrue("没拿到预期回显：[${r2.content}]", r2.content.contains("got-hello-42"))
        } finally {
            ProcRegistry.close(id)
        }
    }

    @Test
    fun `closed processes disappear from the list and nothing is left running`() {
        assumeTrue("这台机器上没有 Git Bash", ShellLauncher.persistentForName("bash") != null)
        val c = ctx()
        val opened = ShellOpenTool().run(args("""{"label":"tmp","shell":"bash"}"""), c)
        val id = Regex("已启动 (\\S+)").find(opened.content)!!.groupValues[1]
        assertEquals(1, ProcRegistry.list().count { it.id == id })
        val closed = ShellCloseTool().run(args("""{"id":"$id"}"""), c)
        assertFalse(closed.content, closed.error)
        assertTrue(ProcRegistry.list().none { it.id == id })
        val after = ShellReadTool().run(args("""{"id":"$id"}"""), c)
        assertTrue("关掉后还能读，说明句柄没清", after.error)
    }

    @Test
    fun `a denied open starts no process`() {
        val c = ctx(DenyGate())
        val r = ShellOpenTool().run(args("""{"label":"nope","shell":"bash"}"""), c)
        assertTrue("被拒了却还是起了进程", r.error)
        assertTrue(ProcRegistry.list().isEmpty())
    }

    @Test
    fun `idle reaper collects processes whose shell already exited`() {
        assumeTrue("这台机器上没有 Git Bash", ShellLauncher.persistentForName("bash") != null)
        val c = ctx()
        val opened = ShellOpenTool().run(args("""{"label":"bye","shell":"bash"}"""), c)
        val id = Regex("已启动 (\\S+)").find(opened.content)!!.groupValues[1]
        ShellSendTool().run(args("""{"id":"$id","text":"exit"}"""), c)
        Thread.sleep(1500)
        val n = ProcRegistry.reapIdle()
        assertEquals(1, n)
        assertTrue(ProcRegistry.list().isEmpty())
    }

    @Test
    fun `list tool reports what is alive`() {
        assumeTrue("这台机器上没有 Git Bash", ShellLauncher.persistentForName("bash") != null)
        val c = ctx()
        val opened = ShellOpenTool().run(args("""{"label":"visible","shell":"bash"}"""), c)
        val id = Regex("已启动 (\\S+)").find(opened.content)!!.groupValues[1]
        try {
            val l = ShellListTool().run(args("{}"), c)
            assertTrue(l.content, l.content.contains(id))
            assertTrue(l.content, l.content.contains("visible"))
        } finally {
            ProcRegistry.close(id)
        }
    }
}
