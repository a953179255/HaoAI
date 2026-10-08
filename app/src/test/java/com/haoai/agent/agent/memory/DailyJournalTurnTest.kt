package com.haoai.agent.agent.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Files

/**
 * 回合落笔（[DailyJournal.recordTurn]，2026-10-09 补的产品缺口）的回归锁。
 *
 * 钉三件事：
 * 1. 聊天回合确实写出一条近期日志（此前日志只有 handoff/显式 journal 两个入口，
 *    日常聊天"近期动态/今日日志"永远是 0——用户实锤"日志没了"的根因）；
 * 2. importance=2 钉死在晋升线（PROMOTE_THRESHOLD=4）之下：日志只当"今天干了啥"
 *    的展示与注入上下文，绝不混进长期记忆库；
 * 3. 同一回合文本重复落笔时被 append 的同文去重吸收（不刷量）。
 */
class DailyJournalTurnTest {

    private fun journal(): DailyJournal =
        DailyJournal(Files.createTempDirectory("haoai-journal").toFile())

    @Test
    fun `recordTurn writes one entry with chat source`() {
        val j = journal()
        val e = j.recordTurn("帮我查一下明天的天气", "明天多云转晴，24 到 31 度，适合出门。")
        assertNotNull(e)
        assertEquals(2, e!!.importance)                       // 低于 PROMOTE_THRESHOLD=4
        assertEquals("chat", e.source)
        assertEquals(1, j.count())
        assertEquals("帮我查一下明天的天气 → 明天多云转晴，24 到 31 度，适合出门。", e.content)
    }

    @Test
    fun `empty user text writes nothing`() {
        val j = journal()
        assertNull(j.recordTurn("   ", "回答"))
        assertEquals(0, j.count())
    }

    @Test
    fun `empty assistant text still logs the question`() {
        val j = journal()
        val e = j.recordTurn("把桌面的截图整理成文档", "")
        assertNotNull(e)
        assertEquals("把桌面的截图整理成文档", e!!.content)
    }

    @Test
    fun `same turn does not duplicate`() {
        val j = journal()
        j.recordTurn("记一下：周五交付", "好的，已记下")
        val again = j.recordTurn("记一下：周五交付", "好的，已记下")
        assertNull(again)                                     // append 同文去重
        assertEquals(1, j.count())
    }

    @Test
    fun `long texts are truncated inside record limits`() {
        val j = journal()
        val e = j.recordTurn("问".repeat(200), "答".repeat(200))
        assertNotNull(e)
        // 60 + " → " + 40 = 103 字符内（append 还会 take(300)，这里是更紧的源头限）
        assertEquals(true, e!!.content.length <= 105)
    }
}
