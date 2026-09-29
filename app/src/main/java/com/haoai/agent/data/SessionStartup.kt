package com.haoai.agent.data

/**
 * 冷启动该打开哪一条会话。
 *
 * 这儿的规则以前是「抽屉那份列表的第一项」，而那份列表是**置顶优先**的
 * （`ChatViewModel.refreshSessions()`：`compareByDescending { it.pinned }.thenByDescending { it.updatedAt }`），
 * 结果就是：把一个会话置顶，本意只是"让我好找"，却顺手把每次打开应用要跳进去的会话也改了。
 * 排序管的是**看得见**，不该管**打开哪条** —— 这两件事得分开。
 *
 * 规则（按序，取到就返回）：
 *  1. 上次看过的那条（[SessionStore.lastOpenedId]），前提是它还在、且没被丢进回收站；
 *  2. 否则取最近更新的那条（`updatedAt` 最大）—— 置顶不参与这一步；
 *  3. 一条都没有 → null，调用方自己去新建。
 *
 * 入参 [visible] 的顺序**不作依据**（单测故意把置顶项摆在第一位来证明这点），
 * 所以传抽屉那份排序还是 `SessionStore.list()` 那份都行。
 */
object SessionStartup {

    fun pick(visible: List<StoredSession>, lastOpenedId: String?): String? {
        val alive = visible.filter { it.deletedAt == 0L }
        if (alive.isEmpty()) return null
        val last = lastOpenedId?.let { id -> alive.firstOrNull { it.id == id } }
        if (last != null) return last.id
        return alive.maxByOrNull { it.updatedAt }?.id
    }
}
