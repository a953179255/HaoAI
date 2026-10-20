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
    fun `换会话仍然归位，切回来恢复离开时的样子（v9 方案B + 按会话记忆）`() {
        val p = TaskPanelState()
        p.onEnter(s1)
        p.toggleForcedVisible()          // /task 强制可见：顺带把面板翻成展开并存档
        assertTrue(p.forcedVisible)
        p.maybeAutoExpand("todo-a")      // 清单到达（已经是展开态，只记指纹）
        p.onEnter(s2)                    // 切到另一个会话
        assertFalse("新会话不该带着上一个会话的展开态", p.expanded)
        assertFalse("新会话不该带着上一个会话的 /task 强制可见", p.forcedVisible)
        assertEquals(s2, p.ownerSessionId)
        // 切回去：**恢复离开时的样子**（那时是展开），而不是被当成新清单再自动拉一次
        p.onEnter(s1)
        assertTrue("切回 A 应恢复离开时的展开态", p.expanded)
        assertFalse("A 这份清单已经自动展开过，不该再拉开", p.maybeAutoExpand("todo-a"))
    }

    @Test
    fun `在 A 收起后切去 B 再切回 A，收起这一步必须还在`() {
        // 2026-10-21 用户实测点名：旧实现切回 A 会把面板重新拉开
        val p = TaskPanelState()
        p.onEnter(s1)
        p.maybeAutoExpand("todo-a")
        p.toggleExpanded()               // 用户手动收成胶囊
        assertFalse(p.expanded)

        p.onEnter(s2)                    // 切去 B：B 自己的清单照常展开
        assertTrue(p.maybeAutoExpand("todo-b"))

        p.onEnter(s1)                    // 切回 A
        assertFalse("切回 A 要恢复离开时的收起态", p.expanded)
        // 界面那条 LaunchedEffect(首条 id) 重跑一遍，也不许把它拉开
        assertFalse(p.maybeAutoExpand("todo-a"))
        assertFalse(p.expanded)
    }

    @Test
    fun `回合正常结束收起面板：只收当前看的会话，且不阻断新清单自动展开`() {
        val p = TaskPanelState()
        p.onEnter(s1)
        p.maybeAutoExpand("todo-a")
        assertTrue(p.expanded)

        p.collapseOnFinish(s2)           // 后台会话结束：不许动用户正在看的面板
        assertTrue("别的会话结束不该收当前面板", p.expanded)

        p.collapseOnFinish(s1)
        assertFalse(p.expanded)
        // 系统收起不算"用户手动收起"：下一轮新清单照常自动展开一次
        assertTrue(p.maybeAutoExpand("todo-b"))

        // 收起态也要按会话记住：切走再切回仍是收起
        p.collapseOnFinish(s1)
        p.onEnter(s2)
        p.onEnter(s1)
        assertFalse(p.expanded)
    }

    @Test
    fun `删除会话后不再留着它的存档`() {
        val p = TaskPanelState()
        p.onEnter(s1)
        p.maybeAutoExpand("todo-a")
        p.forget(s1)
        p.onEnter(s1)                    // 同名重新进入（重建场景）：当新会话
        assertFalse(p.expanded)
        assertTrue("清过指纹，新清单要能再展开", p.maybeAutoExpand("todo-a"))
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
