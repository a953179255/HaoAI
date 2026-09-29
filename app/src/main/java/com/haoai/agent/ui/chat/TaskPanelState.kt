package com.haoai.agent.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 顶栏任务面板的展开态。
 *
 * 这两颗状态原先是 `ChatScreen` 里的 `remember { mutableStateOf(false) }`。
 * 去一趟设置页再回来，聊天界面这棵 Composable 树会整个重建（MainActivity 是按 `screen`
 * 变量换屏，不是叠层），remember 被清零，紧跟着那条"新清单到达就自动展开"的效果
 * 也会重新跑一遍 —— 用户看到的就是**每次返回会话界面，任务面板都重新展开一次**
 * （2026-09-30 报的现场缺陷），而且他自己刚收起的那一步也一并丢了。
 *
 * 搬到这儿挂到 activity 作用域的 `ChatViewModel` 上，换屏不影响它。
 *
 * 语义保持 v9 方案B：**按会话绑定** —— 切会话/新建会话时归位，
 * 不让他处开过的面板残留到新会话里。
 *
 * 自动展开多了道闸：只在清单**真的换了一份**（指纹变了）时来一次。
 * 原来那道 `LaunchedEffect(todoItems.firstOrNull()?.id)` 没有这层记忆，
 * 冷启动、重进本会话都会再触发一遍，把已经收起的面板又拉开。
 */
class TaskPanelState {

    /** 面板是展开（清单可见）还是收成一颗胶囊。 */
    var expanded by mutableStateOf(false)
        private set

    /** /task 强制可见：清单为空或已全部完成时也要把面板摆出来。 */
    var forcedVisible by mutableStateOf(false)
        private set

    private var owner: String? = null

    /** 上一次"替用户自动展开"时看到的清单指纹。同一份清单只自动展开一次。 */
    private var autoExpandedFor: String? = null

    /** 当前面板归属的会话（测试与诊断用）。 */
    val ownerSessionId: String? get() = owner

    /**
     * 进入某个会话。**同一个会话反复进入不重置** —— 这正是"去设置页再回来"不该重新展开的原因；
     * 会话换了才归位（v9 方案B）。
     */
    fun onEnter(sessionId: String?) {
        if (owner == sessionId) return
        owner = sessionId
        expanded = false
        forcedVisible = false
        autoExpandedFor = null
    }

    fun toggleExpanded() { expanded = !expanded }

    /** `/task`：可见性与展开一起翻（与界面上原来那两行赋值同语义）。 */
    fun toggleForcedVisible() {
        forcedVisible = !forcedVisible
        expanded = !expanded
    }

    /**
     * 清单到位时要不要自动展开一次；返回 true 表示这次真的展开了。
     *
     * [signature] 传清单首条的 id（沿用原来效果的关键字）。空清单不展开；
     * 与上次自动展开时同一份清单也不展开——**用户收起来之后不会再被拉开**。
     */
    fun maybeAutoExpand(signature: String?): Boolean {
        if (signature.isNullOrBlank() || signature == autoExpandedFor) return false
        autoExpandedFor = signature
        expanded = true
        return true
    }
}
