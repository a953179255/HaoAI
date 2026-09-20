package com.haoai.agent.agent.engine

import com.haoai.agent.agent.model.ChatMessage

enum class ToolRunState { RUNNING, DONE, DENIED, ERROR }

data class ToolUpdate(
    val callId: String,
    val state: ToolRunState,
    val brief: String? = null,
    val preview: String? = null
)

sealed interface TurnEvent

data class MessageAdded(val message: ChatMessage) : TurnEvent

/**
 * 引擎丢掉了已上屏的残句（瞬态退避重发 / 溢出压缩重发）。
 * UI 收到必须把流式气泡清回去：否则用户盯着一段永远不会成为最终答案的文字，
 * 而落库的那条里没有它 —— 屏幕和历史分叉。
 */
object StreamReset : TurnEvent

data class ToolChanged(val update: ToolUpdate) : TurnEvent

data class Finished(val error: String?) : TurnEvent

/**
 * E7a 子代理进度上报：spawn_agents 每路完成时触发（state/用量/简报），
 * UI 据此刷新对应卡片（不阻塞主卡片）。
 * @param callId 关联的 spawn 工具调用 id
 * @param index 1..total 路序号
 */
data class SubagentUpdate(
    val callId: String,
    /** P2 子代理句柄 id（sa_N），任务卡终止按钮按此定位。 */
    val id: String,
    val index: Int,
    val total: Int,
    val state: String,
    val tokensUsed: Long,
    val brief: String
) : TurnEvent

/** E7a 工具侧上报载荷（ToolContext.onSubagentEvent），引擎补 callId 后转成 SubagentUpdate。 */
data class SubagentReport(
    /** P2 子代理句柄 id（sa_N）。 */
    val id: String = "",
    val index: Int = 1,
    val total: Int = 1,
    val state: String,
    val tokensUsed: Long = 0,
    val brief: String
)
