package com.haoai.agent.agent.flags

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S6 特性开关的解析规则回归锁。纯查表，不碰 android/磁盘。
 *
 * 这里最要紧的一条是 `enabled` 的优先级：**REMOVED 一律关，用户覆盖无效**。
 * 反面案例来自 hermes #96814：配置项被接受、能读能写，但代码里没人读它 ——
 * 运维以为自己装了保险丝，其实没有。所以每条规则都要有断言钉住。
 */
class HaoFlagsTest {

    @Test
    fun `keys are unique and lookupable`() {
        assertEquals(HaoFlag.entries.size, HaoFlag.entries.map { it.key }.distinct().size)
        HaoFlag.entries.forEach { assertEquals(it, HaoFlag.byKey(it.key)) }
        assertNull(HaoFlag.byKey("no_such_flag"))
    }

    @Test
    fun `no override falls back to the enum default`() {
        HaoFlag.entries.forEach { flag ->
            assertEquals(flag.defaultOn, HaoFlag.enabled(flag, emptyMap()))
        }
    }

    @Test
    fun `user override beats the default`() {
        val flag = HaoFlag.TOOL_RESULT_SPILL
        assertFalse("默认应为关（实验特性不默认改变用户磁盘行为）", flag.defaultOn)
        assertTrue(HaoFlag.enabled(flag, mapOf(flag.key to true)))
        // 显式 false 也要能压住（将来 STABLE 项允许用户关）
        assertFalse(HaoFlag.enabled(flag, mapOf(flag.key to false)))
    }

    @Test
    fun `unknown keys are ignored`() {
        // 表里没有的 key 不该把任何开关点亮
        HaoFlag.entries.forEach { flag ->
            assertEquals(flag.defaultOn, HaoFlag.enabled(flag, mapOf("ghost_flag" to true)))
        }
    }

    @Test
    fun `visible hides removed flags`() {
        // 现在还没有 REMOVED 项；这条锁住的是"以后下线了别留在设置页上骗人"
        assertTrue(HaoFlag.visible().all { it.stage != FlagStage.REMOVED })
        assertEquals(HaoFlag.entries.size, HaoFlag.visible().size)
    }

    @Test
    fun `compactOverrides drops values equal to the default`() {
        val flag = HaoFlag.TOOL_RESULT_SPILL
        // 与默认相同（false）→ 不写进设置文件
        assertTrue(HaoFlag.compactOverrides(mapOf(flag.key to false)).isEmpty())
        // 与默认不同（true）→ 保留
        assertEquals(mapOf(flag.key to true), HaoFlag.compactOverrides(mapOf(flag.key to true)))
        // 未知 key 不落地
        assertTrue(HaoFlag.compactOverrides(mapOf("ghost_flag" to true)).isEmpty())
    }
}
