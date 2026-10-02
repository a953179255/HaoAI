package com.haoai.agent.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 界面上那根"已等 N 秒"的起点规则。
 *
 * 钉的不是算术，是**起点从哪来**：移动端换一次屏＝整棵 Compose 树重建，
 * 显示方自己在组合里 `val t0 = System.currentTimeMillis()` 起表，回到聊天时秒数就从 0 重跳
 * （任务没重启，是量它的尺被重置了）。所以第一条用例刻意长成"刚重新组合"的样子：
 * 回合起点在 5 秒前，而本次组合的起点就是"现在" —— 读数必须是 5 秒，不是 0。
 */
class ElapsedClockTest {

    private val now = 1_790_000_000_000L

    @Test fun `回合起点优先，重新组合那一次不许归零`() {
        assertEquals(5_000L, ElapsedClock.ms(now - 5_000L, now, now))
        assertEquals(123_456L, ElapsedClock.ms(now - 123_456L, now, now))
    }

    @Test fun `没记回合起点时才用本次组合的起点`() {
        assertEquals(2_000L, ElapsedClock.ms(0L, now - 2_000L, now))
        assertEquals(2_000L, ElapsedClock.ms(-1L, now - 2_000L, now))   // 脏值同义：当没记上
    }

    @Test fun `两个起点都没有就说零秒，不拿 epoch 零算天文数字`() {
        assertEquals(0L, ElapsedClock.ms(0L, 0L, now))
    }

    @Test fun `起点在未来时不打负数`() {
        assertEquals(0L, ElapsedClock.ms(now + 60_000L, now + 30_000L, now))
    }

    @Test fun `跨档读数按秒显示不跳档`() {
        // 界面是 String.format("%.1fs", ms/1000.0)：这里保证毫秒数本身连续，
        // 不会出现"某一拍突然归零"——那是起点被换掉才会有的形状
        var prev = -1L
        for (step in 0..40) {
            val ms = ElapsedClock.ms(now, now, now + step * 100L)
            assertTrue("第 $step 拍读数倒退：$prev → $ms", ms >= prev)
            prev = ms
        }
        assertEquals(4_000L, prev)
    }
}
