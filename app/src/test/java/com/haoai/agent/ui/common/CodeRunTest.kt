package com.haoai.agent.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 批3e：内联运行纯逻辑（通道归一/能力门/命令成形/输出截断）。 */
class CodeRunTest {

    @Test
    fun `语言归一到通道`() {
        assertEquals(CodeRun.Channel.SHELL, CodeRun.channelOf("bash"))
        assertEquals(CodeRun.Channel.SHELL, CodeRun.channelOf("Sh"))
        assertEquals(CodeRun.Channel.PYTHON, CodeRun.channelOf("python3"))
        assertEquals(CodeRun.Channel.PYTHON, CodeRun.channelOf("py"))
        assertEquals(CodeRun.Channel.NODE, CodeRun.channelOf("JavaScript"))
    }

    @Test
    fun `不可执行语言不给通道`() {
        assertNull(CodeRun.channelOf("kotlin"))
        assertNull(CodeRun.channelOf("ts"))      // 需要编译器，宁缺毋滥
        assertNull(CodeRun.channelOf("console")) // 带提示符的示范文本，喂 sh 会误导
        assertNull(CodeRun.channelOf(""))
    }

    @Test
    fun `能力门_shell 恒可跑_解释器要沙箱`() {
        assertTrue(CodeRun.available(CodeRun.Channel.SHELL, hasSandbox = false))
        assertFalse(CodeRun.available(CodeRun.Channel.PYTHON, hasSandbox = false))
        assertTrue(CodeRun.available(CodeRun.Channel.PYTHON, hasSandbox = true))
        assertTrue(CodeRun.available(CodeRun.Channel.NODE, hasSandbox = true))
    }

    @Test
    fun `RunEnv 组合判定`() {
        assertFalse(RunEnv(shellReady = false, sandboxReady = true).supports("python"))
        assertTrue(RunEnv(shellReady = true, sandboxReady = false).supports("bash"))
        assertFalse(RunEnv(shellReady = true, sandboxReady = false).supports("python"))
        assertTrue(RunEnv(shellReady = true, sandboxReady = true).supports("js"))
        assertFalse(RunEnv(shellReady = true, sandboxReady = true).supports("kotlin"))
    }

    @Test
    fun `命令引用临时文件并透传退出码`() {
        val cmd = CodeRun.cmdFor(CodeRun.Channel.SHELL, ".haoai_run_abc123.sh")
        assertTrue(cmd.startsWith("sh '.haoai_run_abc123.sh'"))
        assertTrue(cmd.contains("rm -f '.haoai_run_abc123.sh'"))
        assertTrue(cmd.endsWith("exit \$rc"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `命令拒收非统一命名文件`() {
        CodeRun.cmdFor(CodeRun.Channel.PYTHON, "evil.py")
    }

    @Test
    fun `临时名形状可被校验`() {
        val n = CodeRun.tempFileName(CodeRun.Channel.PYTHON)
        assertTrue(n.startsWith(CodeRun.RUN_FILE_PREFIX))
        assertTrue(n.endsWith(".py"))
        // 造出来的名字必须能通过 cmdFor（两函数口径一致）
        CodeRun.cmdFor(CodeRun.Channel.PYTHON, n)
    }

    @Test
    fun `输出截断保头尾`() {
        val big = (1..2000).joinToString("\n") { "line $it" }
        val capped = CodeRun.capOutput(big, maxChars = 200)
        assertTrue(capped.length < big.length)
        assertTrue(capped.startsWith("line 1"))
        assertTrue(capped.contains("省略中间"))
        assertTrue(capped.endsWith("line 2000"))
    }

    @Test
    fun `短输出原样`() {
        assertEquals("ok", CodeRun.capOutput("ok"))
    }
}
