package com.haoai.agent.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 系统事件芯片分类器单测（2026-10-22 中断控制重设计）。
 * 背景：引擎/协调器注入的文本必须按 role=user 落库（LLM 只读 user/assistant），
 * 界面按前缀识别渲染成芯片——**每个注入前缀都要在这里有用例**，漏一个就是一起
 * 「冒充用户绿气泡」事故（用户已两次点名：task-notification、恢复/熔断家族）。
 * 与拼装侧（buildWakeNotice / AgentEngine / EngineCompaction / resumeRun）是跨层
 * 文案契约：拼装改文案这里必须红。
 */
class SystemEventClassifyTest {

    @Test
    fun `回传通知判绿勾档`() {
        val v = classifySystemEvent(
            "[task-notification] 有 2 路后台子代理已结束（完成 2、失败 0），结果如下。"
        )!!
        assertEquals(SysTone.WAKE, v.tone)
        assertEquals("后台子代理已结束 · 2/2 完成", v.title)
        assertNull(v.metric)
    }

    @Test
    fun `回传有失败进标题`() {
        val v = classifySystemEvent(
            "[task-notification] 有 4 路后台子代理已结束（完成 3、失败 1），结果如下。"
        )!!
        assertEquals("后台子代理已结束 · 3/4 完成 · 1 失败", v.title)
    }

    @Test
    fun `恢复通知判蓝档并进步数`() {
        val v = classifySystemEvent(
            "[系统恢复] 上次任务在第 15 步中断，未完成目标：查 BD 搭配。请先评估当前进度，再继续执行。"
        )!!
        assertEquals(SysTone.RESUME, v.tone)
        assertEquals("恢复执行 · 继续第 15 步的任务", v.title)
    }

    @Test
    fun `旧历史轮字措辞同样识别`() {
        // 2026-10-22 前落库的历史用「第 N 轮中断」，芯片必须照常认（RESUME_STEP_RE [轮步]）
        val v = classifySystemEvent(
            "[系统恢复] 上次任务在第 7 轮中断，未完成目标：x。请先评估当前进度，再继续执行。"
        )!!
        assertEquals("恢复执行 · 继续第 7 步的任务", v.title)
    }

    @Test
    fun `成本熔断判琥珀档带用量指标`() {
        val v = classifySystemEvent(
            "[成本熔断] 本轮已累计计费约 275860 tokens，达到上限（250000）。请立即总结。"
        )!!
        assertEquals(SysTone.CAP, v.tone)
        assertEquals("成本熔断 · 已指示 Agent 立即收尾", v.title)
        assertEquals("275.9k / 250k", v.metric)
    }
    fun `圈数熔断判琥珀档带次数指标`() {
        val v = classifySystemEvent(
            "[圈数熔断] 本轮已执行 41 次工具调用，达到上限（40）。请立即总结当前进度。"
        )!!
        assertEquals(SysTone.CAP, v.tone)
        assertEquals("圈数熔断 · 工具调用达上限收尾", v.title)
        assertEquals("41 / 40 次", v.metric)
    }

    @Test
    fun `压缩交接文档判信息档`() {
        val v = classifySystemEvent("## 任务交接文档\n目标：…\n（以上为此前对话的压缩交接）")!!
        assertEquals(SysTone.INFO, v.tone)
        assertEquals("对话历史已压缩 · 生成交接文档", v.title)
    }

    @Test
    fun `正常用户消息不接管`() {
        assertNull(classifySystemEvent("帮我看看这个 BD 怎么搭配"))
        assertNull(classifySystemEvent("[图片1]\n配图的真实用户消息"))
        assertNull(classifySystemEvent("[web_fetch] 图片（当前视觉状态，供图像分析）"))
    }

    @Test
    fun `数字漂移容错进芯片摘要降级`() {
        // 前缀命中→必须继续画芯片（防冒充优先），只是步数/用量解析不到时降级：
        // 标题走无数字兜底、metric=null。绝不能因为漂移返回 null 掉回绿气泡。
        val cost = classifySystemEvent("[成本熔断] 本轮计费约 twenty tokens 达到上限（约吗）")!!
        assertEquals(SysTone.CAP, cost.tone)
        assertNull(cost.metric)
        val resume = classifySystemEvent("[系统恢复] 上次任务中断于不明位置")!!
        assertEquals(SysTone.RESUME, resume.tone)
        assertEquals("恢复执行 · 继续中断的任务", resume.title)
    }

    @Test
    fun `数字缩写边界`() {
        assertEquals("9999", fmtTokensK("9999"))
        assertEquals("10k", fmtTokensK("10000"))
        assertEquals("275.9k", fmtTokensK("275860"))
        assertEquals("250k", fmtTokensK("250000"))
        assertEquals("100.1k", fmtTokensK("100099"))
    }
}
