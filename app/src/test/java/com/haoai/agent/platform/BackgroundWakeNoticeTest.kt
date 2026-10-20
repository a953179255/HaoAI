package com.haoai.agent.platform

import com.haoai.agent.agent.engine.SubagentHandle
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P4 唤醒通知文本的回归锁：文本是喂给主代理的指令面，格式坏了模型就会
 * 重复劳动 / 漏报失败 / 把通知当用户提问。全部纯函数，不碰 Android。
 */
class BackgroundWakeNoticeTest {

    private fun handle(
        id: String,
        task: String,
        isBackground: Boolean = true,
        state: String = "RUNNING",
        finalResult: String? = null
    ) = SubagentHandle(
        id = id, callId = "call-1", index = 1, total = 1, task = task,
        job = Job(), state = state, isBackground = isBackground, finalResult = finalResult
    )

    @Test
    fun singleCompletionCarriesTaskAndResult() {
        val text = buildWakeNotice(
            listOf(
                BackgroundSubagentCoordinator.Notice(
                    "sa_3", "调研三款开源 Agent 的后台任务机制", ok = true,
                    result = "结论：ZCode 用 task-notification 唤醒主代理。"
                )
            )
        )
        assertTrue("必须带显式前缀（模型与 UI 都靠它识别系统通知）", text.startsWith("[task-notification]"))
        assertTrue("要说明这是 1 路结束", text.contains("有 1 路后台子代理已结束"))
        assertTrue("完成/失败计数要对", text.contains("完成 1、失败 0"))
        assertTrue("要带任务原文", text.contains("调研三款开源 Agent 的后台任务机制"))
        assertTrue("要带结果正文", text.contains("task-notification 唤醒主代理"))
        assertTrue("要有行为指引（防模型重复劳动）", text.contains("不要重复子代理已完成的工作"))
    }

    @Test
    fun mixedBatchCountsDoneAndFailedSeparately() {
        val text = buildWakeNotice(
            listOf(
                BackgroundSubagentCoordinator.Notice("sa_1", "任务A", ok = true, result = "A 的结论"),
                BackgroundSubagentCoordinator.Notice("sa_2", "任务B", ok = false, result = "子代理执行失败：网关 500")
            )
        )
        assertTrue(text.contains("有 2 路后台子代理已结束"))
        assertTrue("完成/失败计数要分开报", text.contains("完成 1、失败 1"))
        assertTrue("失败路要标出失败", text.contains("（失败）"))
        assertTrue("完成路要标出完成", text.contains("（完成）"))
        assertTrue("失败结果要完整带出", text.contains("网关 500"))
        // 逐路编号：模型按编号引用更稳
        assertTrue(text.contains("1. 子代理 sa_1"))
        assertTrue(text.contains("2. 子代理 sa_2"))
    }

    @Test
    fun oversizedResultIsCapped() {
        val long = "头-MARKER-" + "x".repeat(20_000) + "-TAIL-MARKER"
        val text = buildWakeNotice(
            listOf(BackgroundSubagentCoordinator.Notice("sa_9", "长结果任务", ok = true, result = long))
        )
        assertTrue("截断后头部要保留", text.contains("头-MARKER-"))
        assertFalse(
            "超长结果必须截断（唤醒回合还要叠系统提示，别把窗口撑爆）",
            text.contains("TAIL-MARKER")
        )
        assertTrue(
            "截断上限应为 RESULT_CAP（实际 ${text.length}）",
            text.length < BackgroundSubagentCoordinator.RESULT_CAP + 600
        )
    }

    @Test
    fun longTaskLineIsTruncatedToo() {
        val text = buildWakeNotice(
            listOf(
                BackgroundSubagentCoordinator.Notice("sa_4", "T-" + "y".repeat(5_000), ok = true, result = "ok")
            )
        )
        assertTrue("任务行截断后仍要以编号行开头", text.contains("1. 子代理 sa_4"))
        assertFalse("超长任务原文不该整段进通知", text.contains("y".repeat(1_000)))
    }

    /** 引擎上报闸门同源的语义锁：非后台句柄不产生通知（协调器入口判 isBackground）。 */
    @Test
    fun onlyBackgroundHandlesCarryTheFlag() {
        assertTrue(handle("sa_1", "t").isBackground)
        assertFalse(handle("sa_1", "t", isBackground = false).isBackground)
        assertEquals("RUNNING", handle("sa_1", "t").state)
    }
}
