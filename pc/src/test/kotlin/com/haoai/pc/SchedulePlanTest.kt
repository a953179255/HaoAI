package com.haoai.pc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * 一句话排期的判据。
 *
 * 全部用**固定的 now**（周一早上 6:00）构造，不依赖真实时钟 —— 否则"明天九点"这种
 * 断言会在跨午夜或换时区时自己红。测试跑在哪个时区都成立，因为期望值也是用同一个
 * 时区的 Calendar 算出来的。
 */
class SchedulePlanTest {
    /** 2026-09-28 是周一；取本地 06:00 整。 */
    private val now: Long = Calendar.getInstance().apply {
        set(2026, 8, 28, 6, 0, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun day(offset: Int, h: Int, m: Int): Long =
        Calendar.getInstance().apply {
            timeInMillis = now
            add(Calendar.DAY_OF_YEAR, offset)
            set(Calendar.HOUR_OF_DAY, h)
            set(Calendar.MINUTE, m)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private fun ok(raw: String): SchedulePlan.At {
        val (at, err) = SchedulePlan.parse(raw, now)
        assertNotNull("「$raw」该解析出来，实际报错：$err", at)
        return at!!
    }

    private fun err(raw: String): String {
        val (at, e) = SchedulePlan.parse(raw, now)
        assertNull("「$raw」不该被猜出一个时间：$at", at)
        assertTrue("报错要说人话：" + e, e.isNotBlank())
        return e
    }

    // ---- 间隔 ----

    @Test
    fun `intervals come in minutes`() {
        assertEquals("interval", ok("每 30 分钟").kind)
        assertEquals(30, ok("每 30 分钟").every)
        assertEquals(120, ok("每隔2小时").every)
        assertEquals(30, ok("每半小时").every)
        assertEquals(45, ok("每45分钟跑一次").every)
        assertEquals("每 45 分钟", ok("每45分钟跑一次").echo)
    }

    // ---- 每天 ----

    @Test
    fun `daily accepts digits and chinese hours`() {
        assertEquals("daily", ok("每天 09:00").kind)
        assertEquals("09:00", ok("每天 09:00").at)
        assertEquals("09:00", ok("每天早上九点").at)
        assertEquals("22:30", ok("每天晚上十点半").at)
        assertEquals("15:00", ok("每天下午3点").at)
        assertEquals("12:00", ok("每天中午12点").at)
        assertEquals("00:00", ok("每天晚上12点").at)
    }

    @Test
    fun `one day can carry several moments`() {
        val at = ok("每天 7:30 和 21:30")
        assertEquals("07:30,21:30", at.at)
        assertEquals("每天 07:30,21:30", at.echo)
    }

    // ---- 每周 ----

    @Test
    fun `weekly reads bare weekday lists`() {
        val a = ok("每周一三五 8 点")
        assertEquals("weekly", a.kind)
        assertEquals("0,2,4", a.days)
        assertEquals("08:00", a.at)
        val b = ok("每周五 18:00")
        assertEquals("4", b.days)
        assertEquals("18:00", b.at)
        assertEquals("5,6", ok("每个周末 10点").days)
    }

    // ---- 一次性 ----

    @Test
    fun `relative and dated one-shots land on the right minute`() {
        val soon = ok("半小时后")
        assertEquals("once", soon.kind)
        assertEquals(now + 30 * 60_000L, soon.runAt)
        assertEquals(now + 20 * 60_000L, ok("20分钟后").runAt)
        assertEquals(day(1, 9, 0), ok("明天早上9点").runAt)
        assertEquals(day(2, 20, 0), ok("后天晚上8点").runAt)
        assertEquals(day(0, 22, 30), ok("今晚十点半").runAt)
    }

    @Test
    fun `a moment already past today is refused, not silently moved`() {
        assertTrue(err("今天 03:00").contains("已经过了"))
    }

    // ---- 不猜 ----

    @Test
    fun `missing clock time is refused instead of defaulting`() {
        assertTrue(err("每天").contains("几点"))
        assertTrue(err("每周五").contains("几点"))
        assertTrue(err("帮我把仓库看一下").contains("没看懂"))
        assertTrue(err("").contains("先写一句"))
    }

    @Test
    fun `the echo is what the user should see before saving`() {
        assertEquals("每周一、周三、周五 08:00", ok("每周一三五 8 点").echo)
        assertEquals("约 30 分钟后（2026-09-28 06:30）", ok("半小时后").echo)
    }

    // ---- 中文数字 ----

    @Test
    fun `chinese numerals up to ninety-nine`() {
        assertEquals(10, SchedulePlan.num("十"))
        assertEquals(12, SchedulePlan.num("十二"))
        assertEquals(20, SchedulePlan.num("二十"))
        assertEquals(23, SchedulePlan.num("二十三"))
        assertEquals(2, SchedulePlan.num("两"))
        assertEquals(45, SchedulePlan.num("45"))
        assertNull(SchedulePlan.num("abc"))
    }

    // ---- 调度算法（槽位模型）----

    private fun sched(kind: String, at: String, days: String = "", runAt: Long = 0L, last: Long = 0L) =
        Schedule(
            id = "s1", name = "n", prompt = "p", kind = kind, at = at,
            days = days, runAt = runAt, created = now, lastRun = last
        )

    @Test
    fun `daily fires at the next unfired moment of the day`() {
        val s = sched("daily", "07:30,21:30")
        // 06:00 还没到 07:30 → 不跑
        assertTrue(!Schedule.dueNow(s, now))
        assertEquals("新建的那条从创建之后第一个槽位算起", day(0, 7, 30), Schedule.nextDue(s, now))
        // 08:00：07:30 这个点过了且今天没跑过 → 补一次
        assertTrue(Schedule.dueNow(s, day(0, 8, 0)))
        // 跑过 07:30 之后，下一次是今晚 21:30，而不是明天
        val after = s.copy(lastRun = day(0, 7, 30))
        assertEquals(day(0, 21, 30), Schedule.nextDue(after, day(0, 12, 0)))
        // 下午两点才建"每天 09:00"：不该当场补跑，等明早
        val late = sched("daily", "09:00").copy(created = day(0, 14, 0))
        assertEquals(day(1, 9, 0), Schedule.nextDue(late, day(0, 14, 0)))
        val done = s.copy(lastRun = day(0, 21, 30))
        assertEquals(day(1, 7, 30), Schedule.nextDue(done, day(0, 23, 0)))
    }

    @Test
    fun `weekly only fires on the listed weekdays`() {
        val mon = sched("weekly", "08:00", "0,2,4")
        assertEquals(day(0, 8, 0), Schedule.nextDue(mon, now))
        // 周一跑完之后，下一个槽是周三，而不是下周一
        val ran = mon.copy(lastRun = day(0, 8, 0))
        assertEquals(day(2, 8, 0), Schedule.nextDue(ran, day(0, 9, 0)))
    }

    @Test
    fun `a one-shot fires once and never again`() {
        val at = day(0, 9, 0)
        val s = sched("once", "", runAt = at)
        assertTrue(Schedule.dueNow(s, day(0, 9, 1)))
        assertTrue("跑过了就该闭嘴", !Schedule.dueNow(s.copy(lastRun = at), day(0, 9, 5)))
        assertTrue(
            "过点太久（人早忘了）不补跑",
            !Schedule.dueNow(s, day(1, 9, 0))
        )
    }

    @Test
    fun `a malformed clock string means the task simply never runs`() {
        val s = sched("daily", "25:99")
        assertEquals(0L, Schedule.nextDue(s, now))
        assertTrue(!Schedule.dueNow(s, day(3, 12, 0)))
        assertTrue("disabled 的条目一律不跑", !Schedule.dueNow(sched("daily", "09:00").copy(enabled = false), now))
    }

    @Test
    fun `interval keeps its own arithmetic`() {
        val s = sched("interval", "").copy(every = 15)
        assertEquals(now + 15 * 60_000L, Schedule.nextDue(s, now))
        assertTrue(Schedule.dueNow(s, now + 16 * 60_000L))
    }
}
