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
        val opened = ShellOpenTool().runB(args("""{"label":"calc","shell":"bash"}"""), c)
        assertFalse(opened.content, opened.error)
        val id = Regex("已启动 (\\S+)").find(opened.content)!!.groupValues[1]
        try {
            ShellSendTool().runB(args("""{"id":"$id","text":"x=hello"}"""), c)
            val r1 = ShellReadTool().runB(args("""{"id":"$id","wait_ms":2500}"""), c)
            assertFalse(r1.content, r1.error)

            val D = '$'   // shell 变量要用字面量 $，Kotlin 里只能绕出来
            val sendCmd = "{\"id\":\"$id\",\"text\":\"echo got-${D}x-${D}((6*7))\"}"
            val second = ShellSendTool().runB(args(sendCmd), c)
            assertFalse(second.content, second.error)
            val r2 = ShellReadTool().runB(args("""{"id":"$id","wait_ms":3000}"""), c)
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
        val opened = ShellOpenTool().runB(args("""{"label":"tmp","shell":"bash"}"""), c)
        val id = Regex("已启动 (\\S+)").find(opened.content)!!.groupValues[1]
        assertEquals(1, ProcRegistry.list().count { it.id == id })
        val closed = ShellCloseTool().runB(args("""{"id":"$id"}"""), c)
        assertFalse(closed.content, closed.error)
        assertTrue(ProcRegistry.list().none { it.id == id })
        val after = ShellReadTool().runB(args("""{"id":"$id"}"""), c)
        assertTrue("关掉后还能读，说明句柄没清", after.error)
    }

    @Test
    fun `a denied open starts no process`() {
        val c = ctx(DenyGate())
        val r = ShellOpenTool().runB(args("""{"label":"nope","shell":"bash"}"""), c)
        assertTrue("被拒了却还是起了进程", r.error)
        assertTrue(ProcRegistry.list().isEmpty())
    }

    @Test
    fun `idle reaper collects processes whose shell already exited`() {
        assumeTrue("这台机器上没有 Git Bash", ShellLauncher.persistentForName("bash") != null)
        val c = ctx()
        val opened = ShellOpenTool().runB(args("""{"label":"bye","shell":"bash"}"""), c)
        val id = Regex("已启动 (\\S+)").find(opened.content)!!.groupValues[1]
        ShellSendTool().runB(args("""{"id":"$id","text":"exit"}"""), c)
        Thread.sleep(1500)
        val n = ProcRegistry.reapIdle()
        assertEquals(1, n)
        assertTrue(ProcRegistry.list().isEmpty())
    }

    @Test
    fun `list tool reports what is alive`() {
        assumeTrue("这台机器上没有 Git Bash", ShellLauncher.persistentForName("bash") != null)
        val c = ctx()
        val opened = ShellOpenTool().runB(args("""{"label":"visible","shell":"bash"}"""), c)
        val id = Regex("已启动 (\\S+)").find(opened.content)!!.groupValues[1]
        try {
            val l = ShellListTool().runB(args("{}"), c)
            assertTrue(l.content, l.content.contains(id))
            assertTrue(l.content, l.content.contains("visible"))
        } finally {
            ProcRegistry.close(id)
        }
    }
    /**
     * 两个消费者各看各的：面板（人）与 shell_read（模型）读同一个进程。
     *
     * 这是把 `lines` 换成"带序号的缓冲 + 各自游标"的全部理由 ——
     * 共用一条队列时，谁先 poll 走那行对方就永远看不到，
     * 表现是"界面上明明有输出，模型却说它没看到"。
     */
    @Test
    fun `panel and model each get their own copy of the same output`() {
        assumeTrue("这台机器上没有 Git Bash", ShellLauncher.persistentForName("bash") != null)
        val opened = ProcRegistry.open("共享", "bash", Files.createTempDirectory("haoai-pty2").toFile().apply { mkdirs() }, "")
        assertTrue(opened.exceptionOrNull()?.message ?: "", opened.isSuccess)
        val l = opened.getOrThrow()
        try {
            ProcRegistry.send(l.id, "echo 同一行两边都要看到", true).getOrThrow()
            // 面板先到先看
            val (cur, uiLines) = waitUntil { l.since(0L, 400) }
            assertTrue("面板没看到：" + uiLines, uiLines.any { it.contains("同一行两边都要看到") })
            // 模型这一路也必须看得到（它有自己的游标）
            val read = waitUntilText { ProcRegistry.read(l.id, 2500, 4000).getOrDefault("") }
            assertTrue("模型没看到：" + read, read.contains("同一行两边都要看到"))
            // 面板再轮询一次不该拿到重复行（游标已经推进到 cur）
            assertTrue("面板拿到了重复行", l.since(cur, 400).second.isEmpty())
        } finally {
            ProcRegistry.close(l.id)
        }
    }

    /** 缓冲必须有界：无人值守跑一晚上一条 `yes` 就能把内存吃掉。 */
    @Test
    fun `the tail buffer is bounded`() {
        val dir = Files.createTempDirectory("haoai-pty3").toFile().apply { mkdirs() }
        val l = ProcRegistry.open("有界", "bash", dir, "").getOrThrow()
        try {
            repeat(ProcRegistry.Live.TAIL + 500) { l.push("行" + it) }
            val (last, lines) = l.since(0L, ProcRegistry.Live.TAIL + 1000)
            assertTrue("缓冲该有界，实际 " + lines.size, lines.size <= ProcRegistry.Live.TAIL)
            assertEquals("留下的必须是最近的", "行" + (ProcRegistry.Live.TAIL + 499), lines.last())
            assertTrue("最老的该被挤掉", lines.none { it == "行0" })
            // 只取游标之后的：面板轮询靠这个不重复拿同一批
            assertTrue(l.since(last, 10).second.isEmpty())
        } finally {
            ProcRegistry.close(l.id)
        }
    }

    /**
     * TTY 的核心判据：`test -t 0` 必须为真（管道会话恒 1）。
     * 为什么不能直接 echo "isatty-yes"：TTY 会把**输入也回显**出来，命令原文里就带着那些字，
     * 断言分不清是"跑出来的"还是"被回显的"——`rc=$?` 展开后只出现在输出里，才分得开。
     */
    @Test
    fun `tty session is a real tty and keeps state between sends`() {
        assumeTrue("非 Windows 跳过", Env.isWindows)
        assumeTrue("这台机器上没有 Git Bash", ShellLauncher.persistentForName("bash") != null)
        val c = ctx()
        val opened = ShellOpenTool().runB(args("""{"label":"tty","shell":"bash","tty":"true"}"""), c)
        assertFalse(opened.content, opened.error)
        assertTrue("结果行里要标明 tty：" + opened.content, opened.content.contains("tty=true"))
        val id = Regex("已启动 (\\S+)").find(opened.content)!!.groupValues[1]
        try {
            val s0 = ShellSendTool().runB(args("""{"id":"$id","text":"x=hello"}"""), c)
            assertFalse(s0.content, s0.error)
            val D = '$'
            val cmd = "{\"id\":\"$id\",\"text\":\"test -t 0; echo rc=${D}?; echo got-${D}x\"}"
            val s1 = ShellSendTool().runB(args(cmd), c)
            assertFalse(s1.content, s1.error)
            val r = ShellReadTool().runB(args("""{"id":"$id","wait_ms":5000}"""), c)
            assertFalse(r.content, r.error)
            assertTrue("stdin 不是 TTY（没拿到 rc=0）：[${r.content}]", r.content.contains("rc=0"))
            assertFalse("同一份输出里不许出现 rc=1（命令原文只有 rc=${D}?，出现 rc=1 就是 test 判了假）：[${r.content}]", r.content.contains("rc=1"))
            assertTrue("状态没跨 send 保住：[${r.content}]", r.content.contains("got-hello"))
        } finally {
            ProcRegistry.close(id)
        }
    }

    /**
     * Ctrl+C：TTY 里0x03 是信号（打断 sleep）；管道里它只是缓冲里的一个字节，
     * `echo` 要等 sleep 自然结束（30 秒）才轮得到 —— 所以 6 秒内拿到 after-interrupt
     * 就同时证明了"中断生效"与"不是管道"。
     */
    @Test
    fun `ctrl-c interrupts a long command in tty mode`() {
        assumeTrue("非 Windows 跳过", Env.isWindows)
        assumeTrue("这台机器上没有 Git Bash", ShellLauncher.persistentForName("bash") != null)
        val c = ctx()
        val opened = ShellOpenTool().runB(args("""{"label":"tty2","shell":"bash","tty":"true"}"""), c)
        assertFalse(opened.content, opened.error)
        val id = Regex("已启动 (\\S+)").find(opened.content)!!.groupValues[1]
        try {
            val s0 = ShellSendTool().runB(args("""{"id":"$id","text":"sleep 30"}"""), c)
            assertFalse(s0.content, s0.error)
            Thread.sleep(900)
            // enter=false：把 0x03 当原始字节送进终端（tty 行编辑把它变成 SIGINT）
            val s1 = ShellSendTool().runB(args("""{"id":"$id","text":"\u0003","enter":false}"""), c)
            assertFalse(s1.content, s1.error)
            Thread.sleep(300)
            val s2 = ShellSendTool().runB(args("""{"id":"$id","text":"echo after-interrupt"}"""), c)
            assertFalse(s2.content, s2.error)
            val r = ShellReadTool().runB(args("""{"id":"$id","wait_ms":6000}"""), c)
            assertFalse(r.content, r.error)
            assertTrue("中断没生效（6 秒内没等到 after-interrupt，sleep 30 还在占着）：[${r.content}]", r.content.contains("after-interrupt"))
        } finally {
            ProcRegistry.close(id)
        }
    }

    private fun waitUntil(poll: () -> Pair<Long, List<String>>): Pair<Long, List<String>> {
        var last = 0L to emptyList<String>()
        repeat(40) {
            last = poll()
            if (last.second.isNotEmpty()) return last
            Thread.sleep(150)
        }
        return last
    }

    private fun waitUntilText(poll: () -> String): String {
        var last = ""
        repeat(20) {
            last = poll()
            if (last.isNotBlank()) return last
            Thread.sleep(200)
        }
        return last
    }
}