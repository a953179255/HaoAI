package com.haoai.agent.platform

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B10 第二步：手机端「充电自动整理」在闲置期内发现 MEMORY.md 被改过（PC 端在写）就让位。
 *
 * 这条判据防的是两边同时重写整份文件 —— 各按自己的内存副本 render(parse())，
 * 后写的把先写的覆盖掉，且两边都"成功"、没有任何报错。所以判据只问一件事：
 * 该让位的时候让了没有、不该让位的时候（文件没人动过）别白白跳过一夜。
 */
class DreamYieldTest {

    private val idleMin = 30
    private val now = 1_700_000_000_000L

    @Test
    fun `闲置期内被写过就让位`() {
        // 闹钟等了 30 分钟，文件在第 10 分钟被 PC 改过 → 让位
        assertTrue(DreamYield.pcWroteDuringIdle(now - 10 * 60_000L, now, idleMin))
    }

    @Test
    fun `闲置期之前写过不让位`() {
        // 文件是两小时前（上一次整理/白天对话）写的，这轮闲置里没人动 → 照常跑
        assertFalse(DreamYield.pcWroteDuringIdle(now - 2 * 60 * 60_000L, now, idleMin))
    }

    @Test
    fun `正好在窗口边界上不让位`() {
        // 恰好等于闲置窗口：那是上一轮的尾巴，不是这一轮等待期间的写入
        assertFalse(DreamYield.pcWroteDuringIdle(now - idleMin * 60_000L, now, idleMin))
    }

    @Test
    fun `文件不存在不让位`() {
        // mtime=0（SAF 无路径/还没建过）= 没人写过 → 不能因为读不到就永远跳过
        assertFalse(DreamYield.pcWroteDuringIdle(0L, now, idleMin))
    }

    @Test
    fun `mtime 在未来也让位`() {
        // 时钟漂移让 delta 变负：判据取保守方向（让位只晚一夜，覆盖会丢记忆）
        assertTrue(DreamYield.pcWroteDuringIdle(now + 5 * 60_000L, now, idleMin))
    }

    @Test
    fun `闲置分钟数按设置夹在 1 到 240 之间`() {
        // 设置里这个值本来就 coerceIn(1,240)；这里钉住判据自身不因离谱入参算出负/零窗口：
        // idle=0 被夹成 1 分钟 → 半分钟前的写入仍算「窗口内」；240 分钟封顶 → 5 小时前的写入不算
        assertTrue(DreamYield.pcWroteDuringIdle(now - 30_000L, now, 0))
        assertFalse(DreamYield.pcWroteDuringIdle(now - 300 * 60_000L, now, 240))
    }
}
