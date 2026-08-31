package com.haoai.agent.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 5.4 运行账本纯逻辑单测：按月分文件写入、summarize 时界聚合（今日/周/月/全量、
 * 按模型/用途/会话）、90 天清理。文件 IO 走 @TempDir，不碰 Android 框架。
 */
class UsageLedgerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun initLedger() {
        UsageLedger.init(tmp.newFolder("files"))
    }

    private fun entry(
        kind: String = "llm", ts: Long, sessionId: String? = "s1",
        purpose: String? = "chat", model: String? = "m1",
        pin: Int = 100, pout: Int = 50, tool: String? = null
    ): UsageLedger.Entry = UsageLedger.Entry(
        kind = kind, ts = ts, sessionId = sessionId, purpose = purpose,
        model = model, promptTokens = pin, completionTokens = pout, tool = tool
    )

    @Test
    fun `按月分文件名`() {
        // 2026-09-01 12:00 UTC+8 ≈ ts；仅验证 yyyyMM 格式与年份月位
        val name = UsageLedger.fileNameFor(1788244800000L)
        assertTrue("usage-$name", Regex("usage-20\\d{4}\\.jsonl").matches(name))
    }

    @Test
    fun `写入后 summarize 聚合正确`() {
        initLedger()
        val now = System.currentTimeMillis()
        UsageLedger.appendNow(entry(ts = now, pin = 100, pout = 50))
        UsageLedger.appendNow(entry(ts = now, sessionId = "s2", model = "m2", pin = 30, pout = 10))
        UsageLedger.appendNow(entry(kind = "tool", ts = now, tool = "bash", pin = 0, pout = 0))
        val s = UsageLedger.summarize()
        assertEquals(3, s.entries)
        assertEquals(130L, s.totalIn)
        assertEquals(60L, s.totalOut)
        assertEquals(2, s.byModel.size)
        assertEquals("m1", s.byModel[0].model) // 按总 token 排序，m1=150 > m2=40
        assertEquals(1, s.byPurpose.size)
        assertEquals(2, s.bySession.size)
    }

    @Test
    fun `会话过滤 summarize`() {
        initLedger()
        val now = System.currentTimeMillis()
        UsageLedger.appendNow(entry(ts = now, sessionId = "s1", pin = 10))
        UsageLedger.appendNow(entry(ts = now, sessionId = "s2", pin = 999, pout = 999))
        val s = UsageLedger.summarize("s1")
        assertEquals(1, s.entries)
        assertEquals(10L, s.totalIn)
        assertEquals(1, s.bySession.size)
    }

    @Test
    fun `九十天清理保留当月`() {
        initLedger()
        val now = System.currentTimeMillis()
        // 当月文件 + 100 天前的月份文件
        UsageLedger.appendNow(entry(ts = now))
        val oldTs = now - 100L * 24 * 3600 * 1000
        UsageLedger.appendNow(entry(ts = oldTs))
        val d = java.io.File(tmp.root, "files/usage")
        val before = d.listFiles().orEmpty().size
        UsageLedger.sweep(now)
        val after = d.listFiles().orEmpty()
        assertTrue(before >= 1)
        // 100 天前的月份文件应被清掉（若与当月同月则该文件保留——边界按月整删）
        val oldMonth = UsageLedger.fileNameFor(oldTs)
        if (oldMonth != UsageLedger.fileNameFor(now)) {
            assertTrue("旧月份文件应被清理", after.none { it.name == oldMonth })
        }
        assertTrue(after.any { it.name == UsageLedger.fileNameFor(now) })
    }

    @Test
    fun `清空全部`() {
        initLedger()
        UsageLedger.appendNow(entry(ts = System.currentTimeMillis()))
        UsageLedger.clearAll()
        assertEquals(0, UsageLedger.summarize().entries)
    }
}
