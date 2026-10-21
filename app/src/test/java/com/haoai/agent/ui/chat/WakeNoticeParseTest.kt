package com.haoai.agent.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 后台子代理唤醒通知解析（SubagentNoticeCard 收起态计数来源）。
 * 文案格式与 BackgroundSubagentCoordinator.buildWakeNotice 是跨层契约，
 * 两侧任一改动都要跑这里——格式对不上必须返回 null 让 UI 走保守默认，
 * 不能崩、不能把别人的消息误判成通知卡。
 */
class WakeNoticeParseTest {

    @Test
    fun `标准单路完成通知`() {
        val t = "[task-notification] 有 1 路后台子代理已结束（完成 1、失败 0），结果如下。\n" +
            "\n请基于这些结果向用户做必要的补充说明。\n" +
            "\n1. 子代理 sa_1（完成）\n任务：x\n结果：\ny"
        val s = parseWakeNotice(t)!!
        assertEquals(1, s.total)
        assertEquals(1, s.done)
        assertEquals(0, s.failed)
    }

    @Test
    fun `多路混合完成失败`() {
        val t = "[task-notification] 有 4 路后台子代理已结束（完成 3、失败 1），结果如下。"
        val s = parseWakeNotice(t)!!
        assertEquals(4, s.total)
        assertEquals(3, s.done)
        assertEquals(1, s.failed)
    }

    @Test
    fun `非通知消息返回null`() {
        assertNull(parseWakeNotice("普通用户消息 [task-notification] 字样在中间不算"))
        assertNull(parseWakeNotice(""))
        assertNull(parseWakeNotice("[task-notification] 有 路后台子代理已结束（完成 x、失败 y）"))
    }

    @Test
    fun `文案漂移返回null不崩`() {
        // 拼装侧改了标点/措辞 → 解析必须静默 null（UI 走默认 1/1），不能抛
        assertNull(parseWakeNotice("[task-notification] 有 1 路后台子代理结束啦（完成 1、失败 0）"))
        assertNull(parseWakeNotice("[task-notification] 有 2 路后台子代理已结束（完成 2）"))
    }
}
