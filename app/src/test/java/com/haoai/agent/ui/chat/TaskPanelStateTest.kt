package com.haoai.agent.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 顶栏任务面板的展开态。
 *
 * 现场缺陷（用户 2026-09-30 报）：会话里有任务面板时，**进设置再返回会话界面，
 * 面板会重新展开一次**，像是整个界面重新加载了一遍。
 * 根因两条叠在一起：
 *  1. 展开态原本是 ChatScreen 里的 `remember { mutableStateOf(false) }`，而 MainActivity 是按
 *     `screen` 变量换屏（不是叠层），回聊天界面时这棵 Composable 树整个重建 → remember 归零；
 *  2. 归零之后那条"新清单到达就自动展开"的效果（`LaunchedEffect(todoItems.firstOrNull()?.id)`）
 *     在**首次组合**时也会跑一遍 → 面板又被拉开。
 * 修法是把状态挂到 activity 作用域的 VM 上（[TaskPanelState]），并给自动展开加一道
 * "同一份清单只展开一次"的闸。
 *
 * 用例 1、2 是那两条根因各自的回归闸；用例 5 保证 v9 方案B（按会话绑定）没被我改松。
 */
class TaskPanelStateTest {

    private val s1 = "session-1"
    private val s2 = "session-2"

    @Test
    fun `进设置再回聊天界面不该把面板重新展开一次`() {
        val p = TaskPanelState()
        p.onEnter(s1)
        assertTrue("新清单到达时要展开", p.maybeAutoExpand("todo-a"))
        // 用户没动它，去设置页再回来：屏幕重建 = 再 onEnter 同会话 + 清单指纹没变
        p.onEnter(s1)
        assertFalse("同一份清单不该再自动展开", p.maybeAutoExpand("todo-a"))
        assertTrue(p.expanded)   // 保持他离开时的样子（还展开着）
    }

    @Test
    fun `用户自己收起后重进本会话不会再被拉开`() {
        val p = TaskPanelState()
        p.onEnter(s1)
        p.maybeAutoExpand("todo-a")
        p.toggleExpanded()               // 手动收成胶囊
        assertFalse(p.expanded)
        p.onEnter(s1)                    // 去设置 → 回来
        p.maybeAutoExpand("todo-a")
        assertFalse("手动收起这一步必须算数", p.expanded)
    }

    @Test
    fun `真的来了新清单时才自动展开一次`() {
        val p = TaskPanelState()
        p.onEnter(s1)
        assertTrue(p.maybeAutoExpand("todo-a"))
        p.toggleExpanded()
        assertTrue("清单换了（首条 id 变了）要再展开一次", p.maybeAutoExpand("todo-b"))
        assertFalse(p.maybeAutoExpand("todo-b"))
    }

    @Test
    fun `空清单或没有清单时不自动展开`() {
        val p = TaskPanelState()
        p.onEnter(s1)
        assertFalse(p.maybeAutoExpand(null))
        assertFalse(p.maybeAutoExpand(""))
        assertFalse(p.expanded)
    }

    @Test
    fun `换会话仍然归位（v9 方案B：他处开过的面板不残留到新会话）`() {
        val p = TaskPanelState()
        p.onEnter(s1)
        p.maybeAutoExpand("todo-a")
        p.toggleForcedVisible()          // /task 强制可见
        assertTrue(p.forcedVisible)
        p.onEnter(s2)                    // 切到另一个会话
        assertFalse("新会话不该带着上一个会话的展开态", p.expanded)
        assertFalse("新会话不该带着上一个会话的 /task 强制可见", p.forcedVisible)
        assertEquals(s2, p.ownerSessionId)
        // 换回去算"新一次到达那份清单"，可以再展开
        p.onEnter(s1)
        assertTrue(p.maybeAutoExpand("todo-a"))
    }

    @Test
    fun `task 命令同时翻可见性与展开，翻两次回到原状`() {
        val p = TaskPanelState()
        p.onEnter(s1)
        assertFalse(p.forcedVisible); assertFalse(p.expanded)
        p.toggleForcedVisible()          // /task 第一次：空清单也要看得见
        assertTrue(p.forcedVisible); assertTrue(p.expanded)
        p.toggleForcedVisible()          // 第二次：收回
        assertFalse(p.forcedVisible); assertFalse(p.expanded)
    }

    @Test
    fun `还没进过任何会话时没有归属`() {
        val p = TaskPanelState()
        assertNull(p.ownerSessionId)
        assertFalse(p.expanded)
        assertFalse(p.forcedVisible)
    }
}
