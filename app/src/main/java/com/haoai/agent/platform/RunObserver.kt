package com.haoai.agent.platform

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 进程级任务进度广播中枢（编排层可观测性的单一数据源）。
 *
 * 采集端：ChatViewModel 在引擎事件回调里写入（工具状态 / 流式摘要 / 待审批）。
 * 消费端：KeepAliveService（动态通知）、AgentOverlayService（悬浮窗）——两者都
 * 可能活在 Activity 已销毁的时刻，所以放进程级 object 而非 ViewModel。
 *
 * 借鉴：上游 InputProcessingState（唯一进度广播源，主界面/悬浮窗/通知共用）+
 * 上游 SessionActivityTracker（服务订阅工具状态）+ 上游 _TaskCardState
 * （单条任务卡 upsert）。
 */
object RunObserver {

    /** 一条工具/阶段的实时状态（通知与悬浮窗共用）。 */
    data class Step(
        val callId: String,
        val name: String,
        val brief: String,
        val state: String // running / done / error
    )

    data class RunState(
        val sessionId: String? = null,
        /** 用户目标（runGoal 截断版），任务卡标题行。 */
        val goal: String = "",
        /** 最近 N 条工具/阶段状态（新在前，最多 4 条展示）。 */
        val steps: List<Step> = emptyList(),
        /** 流式回答当前累积（节流版，用于悬浮窗展开态正文）。 */
        val streamTail: String = "",
        /** todo 清单镜像（上游 持久进度卡思想：步骤清单 pending/in_progress/completed）。 */
        val todos: List<Triple<String, String, String>> = emptyList(), // (text,status,priority)
        /** 非空 = 有待审批操作，通知升级为高优先级并挂「查看/批准」动作。 */
        val approvalTitle: String? = null,
        val approvalDetail: String? = null,
        /** 任务开始时间（通知计 Duration 用）。 */
        val startedAt: Long = 0L
    ) {
        val active: Boolean get() = sessionId != null
    }

    private val _state = MutableStateFlow(RunState())
    val state = _state.asStateFlow()

    /** 应用是否在前台（MainActivity onResume/onPause 维护）：悬浮窗仅后台显示。 */
    @Volatile var appForeground: Boolean = true

    /** 通知/悬浮窗刷新节流（上游：notify 是 binder IPC 且系统限流）。 */
    @Volatile var lastNotifyAt: Long = 0

    /** 停止当前任务：路由到 AgentRunRegistry（D16 跨实例停止句柄）。 */
    fun requestStop() {
        val id = _state.value.sessionId ?: return
        AgentRunRegistry.stopFor(id)
    }

    fun start(sessionId: String, goal: String) {
        _state.value = RunState(
            sessionId = sessionId,
            goal = goal,
            startedAt = System.currentTimeMillis()
        )
    }

    fun setSteps(steps: List<Step>) {
        if (!_state.value.active) return
        _state.value = _state.value.copy(steps = steps.take(4))
    }

    fun setStreamTail(text: String) {
        if (!_state.value.active) return
        _state.value = _state.value.copy(streamTail = text.take(400))
    }

    fun setTodos(todos: List<Triple<String, String, String>>) {
        if (!_state.value.active) return
        _state.value = _state.value.copy(todos = todos)
    }

    fun setApproval(title: String?, detail: String? = null) {
        if (!_state.value.active && title != null) return
        _state.value = _state.value.copy(approvalTitle = title, approvalDetail = detail)
    }

    fun end() {
        _state.value = RunState()
    }
}
