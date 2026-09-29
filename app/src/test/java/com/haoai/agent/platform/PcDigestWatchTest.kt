package com.haoai.agent.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "定时任务跑完了"要不要震手机一下。
 *
 * 这条通道的失败方式不是"不响"，而是**响得让人把通知权限关掉**：
 * 后台轮询 20 秒一次，任何一次判重失灵，人一天就要被同一条结果打断几千次。
 * 所以判据全照着"最坏情况会不会发生"来写，不测"能不能弹出来"那一半。
 */
class PcDigestWatchTest {

    private fun item(t: Long, title: String = "早间简报") =
        PcDigestItem(t = t, at = "07:30", title = title, verdict = "跑完了", text = "今天有 3 条更新")

    @Test
    fun `the first sight of history never buzzes`() {
        val w = PcDigestWatch()
        // 手机上刚配好对，电脑上攒着 30 条历史结果：一条都不许响
        val hist = (1L..30L).map { item(it * 1000) }
        assertTrue("装上就震 = 人会把权限关掉：" + w.onDigest(hist), w.onDigest(hist).isEmpty())
    }

    @Test
    fun `a result that finishes later does buzz once`() {
        val w = PcDigestWatch()
        w.onDigest(listOf(item(1000)))                       // 第一次：登记
        val fresh = w.onDigest(listOf(item(1000), item(2000)))
        assertEquals(listOf(2000L), fresh.map { it.t })
        assertTrue("同一条不该第二次响：" + w.onDigest(listOf(item(1000), item(2000))),
            w.onDigest(listOf(item(1000), item(2000))).isEmpty())
    }

    @Test
    fun `an entry without a stable key is never announced`() {
        val w = PcDigestWatch()
        // t=0（或负数）没法判重：弹了就等于每 20 秒弹一次。宁可不提醒。
        w.onDigest(listOf(item(0), item(-5)))
        assertTrue("没有稳定主键就不该响：" + w.onDigest(listOf(item(0), item(-5))),
            w.onDigest(listOf(item(0), item(-5))).isEmpty())
    }

    @Test
    fun `several finished while away come back oldest first`() {
        val w = PcDigestWatch()
        w.onDigest(listOf(item(1000)))
        val fresh = w.onDigest(listOf(item(1000), item(4000), item(2000), item(3000)))
        assertEquals("调用方拿最后一条当最新的，顺序错了就报成旧的那次",
            listOf(2000L, 3000L, 4000L), fresh.map { it.t })
    }

    @Test
    fun `re-pairing re-primes instead of inheriting the old machine's timeline`() {
        val w = PcDigestWatch()
        w.onDigest(listOf(item(1000)))
        w.onDigest(listOf(item(2000)))
        w.reset()
        // 换了一台电脑：它那边的历史结果不能因为 t 恰好没见过就一条条震，
        // 也不能因为 t 撞上旧机器的数字就被当成"说过了"。
        assertTrue(w.onDigest(listOf(item(9000), item(9500))).isEmpty())
        assertEquals(listOf(10000L), w.onDigest(listOf(item(9000), item(9500), item(10000))).map { it.t })
    }

    @Test
    fun `an empty list is not an event`() {
        val w = PcDigestWatch()
        assertTrue(w.onDigest(emptyList()).isEmpty())
        assertTrue(w.onDigest(emptyList()).isEmpty())
    }
}
