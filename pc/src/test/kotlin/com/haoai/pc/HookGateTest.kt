package com.haoai.pc

import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * `pre-tool` 同步闸的判据（S7，对标 ZCODE PreToolUse：退出码 0 放行 / 2 拦截）。
 *
 * 主张三条，缺一条这条闸就不能信：
 * ① 退出码 2 **拦下**，且脚本的 stdout 就是给人和模型看的理由；
 * ② 退出码 0 放行；
 * ③ **失败与超时只记账不拦** —— 钩子自己坏了不该把所有工具锁死
 *    （硬规矩 1 对闸同样成立；它的反面是"配一次坏钩子，agent 从此什么都干不了"）。
 *
 * 走真进程：shell 用 cmd（Windows 自带，不设 Assume）；echo 的文案用 ASCII ——
 * .bat 是按 OEM 代码页读的，断言中文等于断言乱码。
 */
class HookGateTest {

    companion object {
        private lateinit var home: File

        @BeforeClass
        @JvmStatic
        fun setUp() {
            home = Files.createTempDirectory("haoai-gate-home").toFile()
            System.setProperty("haoai.home", File(home, "home").absolutePath)
            Hooks.save(emptyList())
        }

        @AfterClass
        @JvmStatic
        fun tearDown() {
            runCatching { Hooks.save(emptyList()) }
            System.clearProperty("haoai.home")
        }
    }

    private fun ctx() = Hooks.Ctx(
        sid = "s1", runId = "r1", title = "t", model = "m",
        workspace = home, mode = "auto", trigger = "task", stopped = false, finalText = ""
    )

    private fun preToolHook(id: String, cmd: String, timeout: Int = 10) = Hook(
        id = id, name = id, event = Hooks.PRE_TOOL, command = cmd,
        shell = "cmd", timeoutSec = timeout
    )

    @Test
    fun `exit 2 blocks and the stdout becomes the reason`() {
        Assume.assumeTrue("这台机器上没有 cmd，跳过真进程判据", ShellLauncher.forName("cmd") != null)
        Hooks.save(listOf(preToolHook("b1", "echo not-allowed-here& exit /b 2")))
        val g = Hooks.gate(Hooks.PRE_TOOL, ctx())
        assertTrue("退出码 2 该拦下", g.blocked)
        assertEquals("拦下的钩子要报得出名字", "b1", g.hook)
        assertTrue("stdout 该是理由：${g.reason}", g.reason.contains("not-allowed-here"))
        val last = Hooks.load().first().last
        assertTrue("拦下也要记账：$last", last.contains("exit=2"))
    }

    @Test
    fun `exit 0 passes through and is recorded`() {
        Assume.assumeTrue(ShellLauncher.forName("cmd") != null)
        Hooks.save(listOf(preToolHook("p1", "echo all-good& exit /b 0")))
        val g = Hooks.gate(Hooks.PRE_TOOL, ctx())
        assertFalse("退出码 0 不该拦", g.blocked)
        assertTrue("放行也要记账：${Hooks.load().first().last}",
            Hooks.load().first().last.contains("exit=0"))
    }

    @Test
    fun `a failing hook is recorded but never blocks`() {
        Assume.assumeTrue(ShellLauncher.forName("cmd") != null)
        Hooks.save(listOf(preToolHook("f1", "exit /b 3")))
        val g = Hooks.gate(Hooks.PRE_TOOL, ctx())
        assertFalse("退出码 3 是钩子自己失败，不许变成拦截", g.blocked)
        assertTrue("失败要记在条目上：${Hooks.load().first().last}",
            Hooks.load().first().last.contains("exit=3"))
    }

    @Test
    fun `a timeout does not block`() {
        Assume.assumeTrue(ShellLauncher.forName("cmd") != null)
        // ping 攒 25 秒 ≈ 必超时（timeoutSec=1）；被杀掉的进程没有退出码可读
        Hooks.save(listOf(preToolHook("t1", "ping -n 25 127.0.0.1 > nul", timeout = 1)))
        val g = Hooks.gate(Hooks.PRE_TOOL, ctx())
        assertFalse("超时=没结论，不许当拦截", g.blocked)
        assertTrue("超时要记在条目上：${Hooks.load().first().last}",
            Hooks.load().first().last.contains("超时"))
    }

    @Test
    fun `six events, chinese labels, and load keeps the new event names`() {
        assertEquals("事件面要与 ROADMAP S7 的清单一致", 6, Hooks.EVENTS.size)
        for (e in Hooks.EVENTS) {
            assertTrue("$e 该有中文标签", Hooks.eventLabel(e) != e)
        }
        // load() 老逻辑会把不认识的事件摔回 run-end —— 新事件必须原样活下来
        Hooks.save(listOf(Hook(id = "k1", name = "k1", event = Hooks.POST_TOOL_FAIL, command = "x")))
        assertEquals("post-tool-fail 不该被摔回 run-end", Hooks.POST_TOOL_FAIL, Hooks.load().first().event)
        // 下拉框那两份数据都来自 json()：events 与 eventLabels
        val j = Hooks.json()
        assertTrue("json 要带 eventLabels", j.contains("\"eventLabels\""))
        assertTrue("标签要真在 json 里", j.contains(Hooks.eventLabel(Hooks.PRE_TOOL)))
        assertTrue("事件清单要全", j.contains(Hooks.SESSION_START) && j.contains(Hooks.USER_PROMPT))
        Hooks.save(emptyList())
    }
}
