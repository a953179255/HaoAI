package com.haoai.agent.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 横竖屏自动开/收侧栏的那道闸。
 *
 * 真机缺陷（用户 2026-09-30 报）：**从设置页返回聊天时，侧边栏会自动收起**。
 * 而代码里明明写着返回时要恢复展开（`MainActivity` 设置页的 onBack 走 [DrawerController.snapOpen]）。
 * 两边打架的地方是这个效果：
 * ```
 * LaunchedEffect(landscapeTwoPane) {          // 键是"方向"
 *     if (landscapeTwoPane) … else drawer.close()
 * }
 * ```
 * 注释说"只在进入横屏这一刻跑"，但 `LaunchedEffect` 在**每次进入聊天页**（首次组合）都会跑一遍——
 * 从设置返回就是一次重新进入，于是竖屏分支把刚恢复的侧栏立刻关掉。
 * 闸在 [DrawerController.onPaneMode]：方向没变就不动抽屉。
 *
 * 这里测的是闸本身；"重进聊天页"在测试里就是第二次调用 `onPaneMode(false)`。
 */
class DrawerPaneModeTest {

    @Test
    fun `同一方向重进聊天页不该再动抽屉`() {
        val d = DrawerController()
        assertTrue("首次进入要按当前方向结算一次（与旧版一致）", d.onPaneMode(false))
        // 去设置 → 返回聊天：方向没变，这次不能碰抽屉
        assertFalse(d.onPaneMode(false))
        assertFalse(d.onPaneMode(false))
    }

    @Test
    fun `方向真的变了才结算`() {
        val d = DrawerController()
        d.onPaneMode(false)                    // 竖屏进入
        assertTrue("竖→横：要展开成两栏", d.onPaneMode(true))
        assertTrue("横→竖：要收起盖层侧栏", d.onPaneMode(false))
        assertFalse(d.onPaneMode(false))
    }

    @Test
    fun `横屏常驻态下从设置返回也不重复展开`() {
        val d = DrawerController()
        assertTrue(d.onPaneMode(true))
        assertFalse("两栏态重进聊天页不该再跑一次 snapOpen", d.onPaneMode(true))
    }
}
