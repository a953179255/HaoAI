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

data class ToolChanged(val update: ToolUpdate) : TurnEvent

data class Finished(val error: String?) : TurnEvent
