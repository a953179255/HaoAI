package com.haoai.agent.platform

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * D16 进程级 Agent 运行登记表。
 *
 * 引擎运行挂在 ChatViewModel 的 viewModelScope 上，而 ChatViewModel 是
 * Activity/ViewModelStore 作用域：MainActivity 生命周期结束后 viewModelScope
 * 一起取消，这正是 E1「持久化 running == 引擎已灭」假设的来源。但存在两个漏洞：
 *
 *  1. 同进程多实例：launchMode 修复前（或 ROM 异常行为）点图标/分享进入可能新建
 *     第二个 MainActivity + ChatViewModel，旧实例仍在跑引擎。新实例把持久化的
 *     running 误判为「进程死亡」，弹假「任务中断」横幅；且新实例 job 为空，
 *     没有任何途径停掉旧实例上的任务。
 *  2. 同实例切走再切回：selectSession 的死亡检测同样会误标。
 *
 * 这里按 sessionId 登记「谁在跑 + 怎么停」，供：
 *  - selectSession 死亡检测甄别：登记表活着 ≠ 引擎已灭，不得标中断
 *  - 跨实例停止路由：任意实例的停止键都能停到真正的引擎
 *  - 同会话双引擎防并发（send/resumeRun 前置拒绝）
 */
object AgentRunRegistry {

    /** sessionId → 停止句柄（宿主 ChatViewModel 的 stop lambda）。 */
    private val active = ConcurrentHashMap<String, () -> Unit>()

    private val _activeIds = MutableStateFlow<Set<String>>(emptySet())

    /** 活跃运行的 sessionId 集合（响应式，供 UI 合成停止键显示）。 */
    val activeIds = _activeIds.asStateFlow()

    fun register(sessionId: String, stop: () -> Unit) {
        active[sessionId] = stop
        _activeIds.value = active.keys.toSet()
    }

    /** 条件摘除：只移除自己注册的句柄，避免覆盖/误删后注册的同会话登记。 */
    fun unregister(sessionId: String, stop: () -> Unit) {
        active.remove(sessionId, stop)
        _activeIds.value = active.keys.toSet()
    }

    fun isActive(sessionId: String): Boolean = active.containsKey(sessionId)

    /** 当前登记方的停止句柄；未登记返回 null。用于宿主身份比较，防路由递归。 */
    fun ownerOf(sessionId: String): (() -> Unit)? = active[sessionId]

    /** 路由停止：触发该会话登记方的停止逻辑；未登记为空操作。 */
    fun stopFor(sessionId: String) {
        active[sessionId]?.invoke()
    }
}
