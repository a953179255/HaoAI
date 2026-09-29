package com.haoai.agent.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * screen 换屏的返回层级。
 *
 * 起因是真机缺陷：在「电脑联动」页用手势返回，**不是回上一级，而是直接把应用退掉了**。
 * 根因是这个 App 的换屏是一个 `screen: Int`（不是 NavHost），返回键不会自己退一屏——
 * 每一屏得自己接住返回键，没接住的那一屏就把键交给了系统，于是 Activity finish。
 * 修法除了给那一屏补上处理器，还把"上一级是谁"集中成一张表 [ScreenNav]，
 * 由 MainActivity 兜底接住没人接的屏——这张表就是那层兜底的依据，所以它自己得先钉住。
 */
class ScreenNavTest {

    /** MainActivity 现在能渲染的全部屏号（0..19，含设置 section 子页 9..16）。 */
    private val allScreens = (0..19).toList()

    @Test
    fun `电脑联动的上一级是设置根，不是退出应用`() {
        assertEquals(1, ScreenNav.parentOf(19))
        // 曾经这一屏没接返回键：parentOf 是这张表唯一能替它兜底的地方
        assertTrue("未知屏不许当成根（那样返回键就又交给系统了）", !ScreenNav.isRoot(19))
    }

    @Test
    fun `聊天页是唯一没有上一级的屏`() {
        assertNull(ScreenNav.parentOf(0))
        assertTrue(ScreenNav.isRoot(0))
        allScreens.filter { it != 0 }.forEach {
            assertTrue("屏 $it 不该被当成根", ScreenNav.parentOf(it) != null)
        }
    }

    @Test
    fun `每一屏的上一级都必须比它浅`() {
        for (s in allScreens) {
            val p = ScreenNav.parentOf(s) ?: continue
            assertNotEquals("屏 $s 的上一级不能是它自己", s, p)
            assertTrue(
                "屏 $s(深度${ScreenNav.depth(s)}) 的上一级 屏 $p(深度${ScreenNav.depth(p)}) 不比它浅" +
                    "——转场方向会判成同层，返回键也会打转",
                ScreenNav.depth(p) < ScreenNav.depth(s)
            )
        }
    }

    @Test
    fun `从任何一屏往回退都能退到聊天页，不会打转`() {
        for (s in allScreens) {
            var cur = s
            var steps = 0
            while (!ScreenNav.isRoot(cur)) {
                cur = ScreenNav.parentOf(cur) ?: break
                steps++
                assertTrue("屏 $s 的返回链没收敛（走到 $cur）", steps <= 4)
            }
            assertEquals("屏 $s 返回链的终点", 0, cur)
        }
    }

    @Test
    fun `用户纠正过的那两条返回路径保持在上一级`() {
        // 记忆库是从「记忆与梦境」进来的，回设置主页就是"跳了一级"（用户 2026-09 反馈过）
        assertEquals(11, ScreenNav.parentOf(2))
        // 搜索服务目录的上一级是「搜索服务」，不是设置主页
        assertEquals(17, ScreenNav.parentOf(18))
        // 全部会话/浏览器/设置根都回聊天
        listOf(1, 4, 7).forEach { assertEquals(0, ScreenNav.parentOf(it)) }
    }

    @Test
    fun `表外的屏号兜底回设置根而不是退出应用`() {
        // 深链能直接把任意登记过的屏号摆到前台；没登记的新屏不能变成"返回键＝退出"
        assertEquals(1, ScreenNav.parentOf(20))
        assertEquals(1, ScreenNav.parentOf(99))
        assertTrue(ScreenNav.depth(99) >= 1)
    }
}
