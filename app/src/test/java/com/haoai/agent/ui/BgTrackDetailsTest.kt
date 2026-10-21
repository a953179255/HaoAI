package com.haoai.agent.ui

import com.haoai.agent.agent.engine.SubagentHandle
import com.haoai.agent.platform.BackgroundSubagentCoordinator
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 浮标/详情后台轨迹 → UI 明细的 display index/total 派生单测。
 *
 * 钉死 2026-10-21 实机实锤的 bug：后台派发路径（AgentEngine.launchBackground）
 * 把 index/total 写死 1/1，两路后台子代理的脸谱色与编号全场撞成「1 号青」，
 * 浮标与详情都指不出"第几路"。修法 = 按派发序（tracksFor 已按句柄 id 排序）
 * 位置派生，total 与 tracks.size 联动。
 */
class BgTrackDetailsTest {

    private fun handle(id: String, state: String = "RUNNING", task: String = "任务$id") =
        SubagentHandle(
            id = id, callId = "call_$id", index = 1, total = 1, task = task,
            job = kotlinx.coroutines.Job(), state = state, isBackground = true
        )

    private fun track(h: SubagentHandle) =
        BackgroundSubagentCoordinator.BackgroundTrack("sid", h)

    @Test
    fun `两路后台编号不撞车`() {
        val details = listOf(track(handle("sa_1")), track(handle("sa_2"))).toBgDetails()
        assertEquals(2, details.size)
        assertEquals(1, details[0].index)
        assertEquals(2, details[1].index)
        // 脸谱取色按 index 循环：两路必须落到色板不同位（青 / 橙）
        assertEquals(2, details[0].total)
        assertEquals(2, details[1].total)
    }

    @Test
    fun `三路全员编号自洽`() {
        val details = (1..3).map { track(handle("sa_$it")) }.toBgDetails()
        assertEquals(listOf(1, 2, 3), details.map { it.index })
        assertEquals(listOf(3, 3, 3), details.map { it.total })
    }

    @Test
    fun `单路仍是 1 1`() {
        val details = listOf(track(handle("sa_7"))).toBgDetails()
        assertEquals(1, details[0].index)
        assertEquals(1, details[0].total)
    }

    @Test
    fun `handle 里写死的 1 1 被位置派生覆盖`() {
        // 模拟引擎侧真的全传 1/1（launchBackground 现状）：派生值不许透传
        val details = listOf(track(handle("sa_1")), track(handle("sa_2")), track(handle("sa_3")))
            .toBgDetails()
        details.forEachIndexed { i, d ->
            assertEquals(i + 1, d.index)
            assertEquals(3, d.total)
        }
    }

    @Test
    fun `状态与任务词透传不丢`() {
        val details = listOf(track(handle("sa_1", state = "DONE", task = "调研竞品"))).toBgDetails()
        assertEquals("DONE", details[0].state)
        assertEquals("调研竞品", details[0].task)
        assertEquals("sa_1", details[0].id)
    }
}
