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
 *
 * ## 2026-10-21 追加：记忆从"单会话"升到"按会话各记一份"
 *
 * 旧实现只有一个 `owner` 槽位，切去会话 B 再切回 A 会被当成"第一次见这个会话"：
 * `onEnter` 把 `expanded` 拍回 false、**并把自动展开的指纹记忆也清空**，紧跟着
 * `maybeAutoExpand` 认为"这是份新清单"又把面板拉开 —— 用户刚收起的那一步照样丢
 * （用户本次实测点名）。所以两样记忆都要按会话分桶：
 *
 * - `expandedBySession`：切走再切回，恢复你离开时的样子；
 * - `autoExpandedBySession`：换会话不再清空指纹，收起过的那份清单仍是"已自动展开过"。
 */
class TaskPanelState {

    /** 面板是展开（清单可见）还是收成一颗胶囊。 */
    var expanded by mutableStateOf(false)
        private set

    /** /task 强制可见：清单为空或已全部完成时也要把面板摆出来。 */
    var forcedVisible by mutableStateOf(false)
        private set

    private var owner: String? = null

    /** 每个会话各自的展开态：切走再切回要恢复（2026-10-21）。 */
    private val expandedBySession = HashMap<String, Boolean>()

    /** 每个会话各自"上次替用户自动展开时看到的清单指纹"。同一份清单只自动展开一次。 */
    private val autoExpandedBySession = HashMap<String, String?>()

    /** 当前面板归属的会话（测试与诊断用）。 */
    val ownerSessionId: String? get() = owner

    /**
     * 进入某个会话。**同一个会话反复进入不重置** —— 这正是"去设置页再回来"不该重新展开的原因；
     * 会话换了才归位（v9 方案B）。
     *
     * 归位时先把离开那个会话当下的展开态存档，再取出新会话上次的状态 —— 于是
     * "在 A 收起 → 去 B → 回 A" 拿回的是收起态（旧实现拍回 false 后又被自动展开覆盖）。
     * 指纹记忆**不在这里清**：清了就等于把"这份清单已经自动展开过"忘掉。
     */
    fun onEnter(sessionId: String?) {
        if (owner == sessionId) return
        owner?.let { expandedBySession[keyOf(it)] = expanded }
        owner = sessionId
        expanded = sessionId?.let { expandedBySession[keyOf(it)] } ?: false
        forcedVisible = false
    }

    fun toggleExpanded() {
        expanded = !expanded
        owner?.let { expandedBySession[keyOf(it)] = expanded }
    }

    /** `/task`：可见性与展开一起翻（与界面上原来那两行赋值同语义）。 */
    fun toggleForcedVisible() {
        forcedVisible = !forcedVisible
        expanded = !expanded
        owner?.let { expandedBySession[keyOf(it)] = expanded }
    }

    /**
     * 回合正常结束时把面板收成胶囊（2026-10-21，开关默认开）。
     *
     * 与 [toggleExpanded] 的区别：这是**系统收起**，只改展开态、不动"用户手动干预过"
     * 的语义 —— 下一轮新清单（指纹变了）仍然会自动展开一次。
     */
    fun collapseOnFinish(sessionId: String?) {
        // 结束的可能是后台会话（D17 多会话并行）：用户正在看别的会话时不能收他的面板
        if (owner != sessionId) return
        if (!expanded) return
        expanded = false
        expandedBySession[keyOf(sessionId)] = false
    }

    /** 会话被彻底删除：连它的存档一起清掉，不然这两张表只会长不会缩。 */
    fun forget(sessionId: String?) {
        val k = sessionId?.let { keyOf(it) }
        if (k != null) {
            expandedBySession.remove(k)
            autoExpandedBySession.remove(k)
        }
        if (owner == sessionId) {
            owner = null
            expanded = false
            forcedVisible = false
        }
    }

    /**
     * 清单到位时要不要自动展开一次；返回 true 表示这次真的展开了。
     *
     * [signature] 传清单首条的 id（沿用原来效果的关键字）。空清单不展开；
     * 与本会话上次自动展开时同一份清单也不展开——**用户收起来之后不会再被拉开**。
     * 指纹按会话分桶，所以"在 A 收起 → 去 B → 回 A"不会被当成新清单。
     */
    fun maybeAutoExpand(signature: String?): Boolean {
        if (signature.isNullOrBlank()) return false
        val k = keyOf(owner)
        if (autoExpandedBySession[k] == signature) return false
        autoExpandedBySession[k] = signature
        expanded = true
        owner?.let { expandedBySession[keyOf(it)] = true }
        return true
    }

    /** 未建会话时 sessionId 可能为 null，给它一个稳定键，记忆不至于落空。 */
    private fun keyOf(sessionId: String?): String = sessionId ?: ""
}
