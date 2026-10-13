package com.haoai.agent.ui.chat

import com.haoai.agent.ui.ChatRow
import com.haoai.agent.ui.ChainStep
import com.haoai.agent.ui.UiTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 轮级折叠分组判据（2026-10-14 修复「任务中段说话误触发收起」后补的回归锁）：
 * 折叠只认「终答落行且回合结束」，运行期（active=true）任何形态都不许出汇总条——
 * 引擎把中间叙述与最终回复走同一条流式通道，曾经的 streamingText 提前折叠判据
 * 无法区分二者，导致模型每次任务中段发言都误触收起、说完又展开（真机实锤的抽搐）。
 */
class RenderSlotsFoldTest {

    private fun chainRow(key: String, text: String = "", steps: Int = 1) = ChatRow(
        key = key, id = key, role = "assistant", text = text,
        chainSteps = (0 until steps).map { ChainStep.Think("思考$key-$it", 100L, "$key-$it") }
    )

    private fun toolRow(key: String) = ChatRow(
        key = key, id = key, role = "assistant", text = "",
        chainSteps = listOf(ChainStep.Tool(UiTool(key, "web_search", "搜索")))
    )

    private fun userRow(key: String) = ChatRow(key = key, id = key, role = "user", text = "帮我找科技新闻")

    private fun finalRow(key: String) = ChatRow(
        key = key, id = key, role = "assistant", text = "这是最终回复",
        completionTokens = 512, durationMs = 30_000
    )

    private fun List<RenderSlot>.groups() = filterIsInstance<RenderSlot.Group>()

    @Test
    fun `运行中段叙述行在窗口末组也不出汇总条`() {
        // 复现用户真机 bug：模型开场白/任务中段叙述落成「链+文」行后仍在跑，
        // 曾经的 taillessBar 会在此时弹汇总条把过程收起，叙述说完又展开（抽搐）。
        val rows = listOf(
            userRow("u1"),
            chainRow("r1", text = "好的，我来帮你找今天的科技新闻", steps = 2),
            toolRow("r2")
        )
        val slots = buildRenderSlots(rows, active = true)
        val groups = slots.groups()
        assertEquals(1, groups.size)
        // 关键断言：无尾行 ⇒ groupFinished=false ⇒ 不出汇总条、全程展开
        assertNull(groups[0].tailRow)
    }

    @Test
    fun `运行中即使末组是带正文的链行也不折叠`() {
        // 叙述行本身就是末行（工具还在 pending 未落行）——同样不许折
        val rows = listOf(userRow("u1"), chainRow("r1", text = "搜索结果很丰富，让我精读几个关键点", steps = 3))
        val slots = buildRenderSlots(rows, active = true)
        assertNull(slots.groups().single().tailRow)
    }

    @Test
    fun `终答落行且回合结束才折叠并拆出尾行`() {
        val rows = listOf(
            userRow("u1"),
            chainRow("r1", text = "好的，我来帮你找", steps = 2),
            toolRow("r2"),
            chainRow("r3", text = "整理完毕", steps = 1),
            finalRow("r4")
        )
        val slots = buildRenderSlots(rows, active = false)
        val groups = slots.groups()
        assertEquals(1, groups.size)
        // 尾行=最终回复（带统计字段），过程段收进汇总条
        assertEquals("r4", groups[0].tailRow?.key)
        assertEquals(listOf("r1", "r2", "r3"), groups[0].group.rows.map { it.key })
    }

    @Test
    fun `历史会话打开即收起`() {
        // active=false 的历史态：链+终答合并行（真实引擎最常见形态）也认
        val rows = listOf(
            userRow("u1"),
            ChatRow(
                key = "r1", id = "r1", role = "assistant",
                text = "最终答案",
                chainSteps = listOf(ChainStep.Tool(UiTool("t1", "web_search", "搜索"))),
                completionTokens = 300, durationMs = 12_000
            )
        )
        val slots = buildRenderSlots(rows, active = false)
        val groups = slots.groups()
        assertEquals(1, groups.size)
        assertEquals("r1", groups[0].tailRow?.key)
        assertTrue(groups[0].group.mergedAnswer != null)
    }

    @Test
    fun `纯问答轮不出汇总条`() {
        val rows = listOf(userRow("u1"), ChatRow(key = "r1", id = "r1", role = "assistant", text = "直接回答"))
        val slots = buildRenderSlots(rows, active = false)
        assertTrue(slots.groups().isEmpty())
        assertEquals(2, slots.filterIsInstance<RenderSlot.Plain>().size)
    }

    @Test
    fun `多轮历史各自成组只折已完成轮`() {
        // 上一轮已结束（折叠），本轮正在跑（展开）——互不影响
        val rows = listOf(
            userRow("u0"), chainRow("p1", steps = 2), finalRow("p2"),
            userRow("u1"), chainRow("c1", text = "正在找", steps = 2)
        )
        val slots = buildRenderSlots(rows, active = true)
        val groups = slots.groups()
        assertEquals(2, groups.size)
        assertEquals("p2", groups[0].tailRow?.key)   // 旧轮：已收尾
        assertNull(groups[1].tailRow)                 // 新轮：运行中不折
    }
}
