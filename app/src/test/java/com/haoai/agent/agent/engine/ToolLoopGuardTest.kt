package com.haoai.agent.agent.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B1 工具空转守卫的纯函数回归锁：identical 连击、周期循环、same-tool 失败、成功清零。
 * 不碰网络/磁盘。
 */
class ToolLoopGuardTest {

    private fun guard(
        warn: Int = 3,
        block: Int = 5,
        failWarn: Int = 3,
        failHalt: Int = 8,
        hard: Boolean = false
    ) = ToolLoopGuard(
        identicalWarnAfter = warn,
        identicalBlockAfter = block,
        sameToolFailWarnAfter = failWarn,
        sameToolFailHaltAfter = failHalt,
        maxCyclePeriod = 4,
        hardStopEnabled = hard,
        loopCap = 0
    )

    @Test
    fun identicalStreak_warnsThenHalt() {
        val g = guard(warn = 3, block = 5)
        assertEquals(ToolLoopGuard.Decision.None, g.observe("bash", "ls", "ok", false, true))
        assertEquals(ToolLoopGuard.Decision.None, g.observe("bash", "ls", "ok", false, true))
        val w = g.observe("bash", "ls", "ok", false, true)
        assertTrue(w is ToolLoopGuard.Decision.Warn)
        assertEquals(ToolLoopGuard.Decision.None, g.observe("bash", "ls", "ok", false, true))
        // streak 继续到 5 → Halt（第 5 次观察时 identicalStreak==5）
        // 第3次已 Warn，第4次 None（warnedThisTurn），第5次 streak=5 >= block → Halt
        val h = g.observe("bash", "ls", "ok", false, true)
        assertTrue("expected Halt got $h", h is ToolLoopGuard.Decision.Halt)
    }

    @Test
    fun differentArgs_resetIdentical() {
        val g = guard(warn = 3, block = 5)
        g.observe("bash", "ls", "ok", false, true)
        g.observe("bash", "ls", "ok", false, true)
        val next = g.observe("bash", "pwd", "ok2", false, true)
        assertEquals(ToolLoopGuard.Decision.None, next)
    }

    @Test
    fun cycleDetection_alternatingPattern() {
        val g = guard(warn = 3, block = 99)
        // A B A B 四次（全参全果相同）应触发周期 Warn（第 4 次闭合 period=2）
        val r1 = g.observe("read", "a", "ra", false, true)
        val r2 = g.observe("grep", "b", "rb", false, true)
        val r3 = g.observe("read", "a", "ra", false, true)
        val r4 = g.observe("grep", "b", "rb", false, true)
        assertEquals(ToolLoopGuard.Decision.None, r1)
        assertEquals(ToolLoopGuard.Decision.None, r2)
        assertEquals(ToolLoopGuard.Decision.None, r3)
        assertTrue("expected cycle Warn got $r4", r4 is ToolLoopGuard.Decision.Warn)
    }

    @Test
    fun sameToolFailures_warnAtThreshold() {
        val g = guard(failWarn = 3, failHalt = 8, warn = 99, block = 99)
        g.observe("browser_navigate", "u1", "err1", true, false)
        g.observe("browser_navigate", "u2", "err2", true, false)
        val w = g.observe("browser_navigate", "u3", "err3", true, false)
        assertTrue(w is ToolLoopGuard.Decision.Warn)
    }

    @Test
    fun success_clearsFailureStreak() {
        val g = guard(failWarn = 3, failHalt = 8, warn = 99, block = 99)
        g.observe("browser_navigate", "u1", "e1", true, false)
        g.observe("browser_navigate", "u2", "e2", true, false)
        g.observe("browser_navigate", "u2", "ok", false, false)
        // 成功清零后再两次失败不应 warn
        g.observe("browser_navigate", "u3", "e3", true, false)
        val d = g.observe("browser_navigate", "u4", "e4", true, false)
        assertEquals(ToolLoopGuard.Decision.None, d)
    }

    @Test
    fun reset_clearsHistory() {
        val g = guard(warn = 2, block = 99)
        g.observe("bash", "ls", "ok", false, true)
        g.observe("bash", "ls", "ok", false, true)
        g.reset()
        val d = g.observe("bash", "ls", "ok", false, true)
        assertEquals(ToolLoopGuard.Decision.None, d)
    }

    @Test
    fun loopCap_haltWhenExceeded() {
        val g = ToolLoopGuard(
            identicalWarnAfter = 99, identicalBlockAfter = 99,
            sameToolFailWarnAfter = 99, sameToolFailHaltAfter = 99,
            maxCyclePeriod = 4, hardStopEnabled = false, loopCap = 2
        )
        g.observe("read", "1", "a", false, true)
        g.observe("read", "2", "b", false, true)
        val d = g.observe("read", "3", "c", false, true)
        assertTrue(d is ToolLoopGuard.Decision.Halt)
    }
}
