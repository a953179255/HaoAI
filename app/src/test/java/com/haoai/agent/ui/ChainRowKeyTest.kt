package com.haoai.agent.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * flushChain 行 key 避让规则（2026-10-14 修「执行期头像丢失」）。
 * 病根：「思考+说话+调用」三合一消息会落两行（内容行+尾行），天然 key 同为 m.id
 * → headFlags.toMap() 被尾行覆盖 → 内容行 showHead=false → 执行期头像+昵称消失。
 */
class ChainRowKeyTest {

    @Test
    fun `三合一消息的内容行加c后缀避让尾行`() {
        // reasoning 起链（headId=m.id）+ 有正文 + 带调用 → 内容行必须让位
        val content = chainRowKey("m1", pendingEmpty = false, hasText = true, msgId = "m1", msgHasToolCalls = true)
        val tail = chainRowKey("m1", pendingEmpty = false, hasText = false, msgId = "m2", msgHasToolCalls = true)
        assertEquals("m1#c", content)
        assertNotEquals(content, tail)
    }

    @Test
    fun `后续消息借链落行不误加后缀`() {
        // 链首是更早的消息（headId=m1），本消息是 m2 → 天然 key 本就不同，保持原样
        assertEquals("m1", chainRowKey("m1", pendingEmpty = false, hasText = true, msgId = "m2", msgHasToolCalls = true))
    }

    @Test
    fun `纯文本行仍走t后缀`() {
        assertEquals("m1#t", chainRowKey("m1", pendingEmpty = true, hasText = true, msgId = "m1", msgHasToolCalls = false))
    }

    @Test
    fun `收尾兜底的尾行本体不加后缀`() {
        // text 为空 = 兜底 flush 的尾行，没有内容行兄弟需要避让
        assertEquals("m1", chainRowKey("m1", pendingEmpty = false, hasText = false, msgId = "m1", msgHasToolCalls = true))
    }

    @Test
    fun `仅思考加说话无调用的合并终答行保持原key`() {
        // 最常见形态：链+终答合并成一行，无尾行 → 不加后缀（finalRowKey 等下游不受影响）
        assertEquals("m1", chainRowKey("m1", pendingEmpty = false, hasText = true, msgId = "m1", msgHasToolCalls = false))
    }

    @Test
    fun `两轮三合一消息连续落行全部唯一`() {
        // m1/m2 都是三合一形态：m1 内容行=m1#c、m2 内容行借 m1 的链=m1、m2 尾行=m2 —— 全不撞
        val keys = listOf(
            chainRowKey("m1", false, true, "m1", true),   // m1 内容行
            chainRowKey("m1", false, true, "m2", true),   // m2 内容行（借 m1 尾链）
            chainRowKey("m2", false, false, "m3", true),  // m2 尾行
        )
        assertEquals(3, keys.toSet().size)
    }
}
