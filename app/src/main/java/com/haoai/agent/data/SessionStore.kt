package com.haoai.agent.data

import android.content.Context
import com.haoai.agent.agent.model.ChatMessage
import com.haoai.agent.agent.model.ToolCallData
import kotlinx.serialization.Serializable
import java.io.File
import java.util.UUID

@Serializable
data class StoredToolCall(val id: String, val name: String, val argumentsJson: String)

@Serializable
data class StoredMessage(
    val role: String,
    val content: String = "",
    val toolCalls: List<StoredToolCall> = emptyList(),
    val toolCallId: String? = null,
    val toolName: String? = null,
    val error: Boolean = false,
    val imageData: String? = null,
    val ts: Long = System.currentTimeMillis()
)

@Serializable
data class StoredSession(
    val id: String,
    var title: String,
    val createdAt: Long,
    var updatedAt: Long,
    val messages: MutableList<StoredMessage> = mutableListOf(),
    var workspaceUri: String? = null
) {
    companion object {
        fun create(workspaceUri: String?): StoredSession {
            val now = System.currentTimeMillis()
            return StoredSession(
                id = UUID.randomUUID().toString(),
                title = "新会话",
                createdAt = now,
                updatedAt = now,
                workspaceUri = workspaceUri
            )
        }
    }
}

fun StoredMessage.toModel(): ChatMessage = ChatMessage(
    role = role,
    content = content,
    toolCalls = toolCalls.map { ToolCallData(it.id, it.name, it.argumentsJson) },
    toolCallId = toolCallId,
    toolName = toolName,
    error = error,
    imageData = imageData,
    ts = ts
)

fun ChatMessage.toStored(): StoredMessage = StoredMessage(
    role = role,
    content = content,
    toolCalls = toolCalls.map { StoredToolCall(it.id, it.name, it.argumentsJson) },
    toolCallId = toolCallId,
    toolName = toolName,
    error = error,
    imageData = imageData,
    ts = ts
)

class SessionStore(context: Context) {

    private val dir = File(context.filesDir, "sessions").apply { mkdirs() }

    private fun fileOf(id: String) = File(dir, "$id.json")

    fun list(): List<StoredSession> =
        dir.listFiles { f -> f.extension == "json" }
            ?.mapNotNull { f ->
                runCatching {
                    HaoJson.json.decodeFromString(StoredSession.serializer(), f.readText())
                }.getOrNull()
            }
            ?.sortedByDescending { it.updatedAt }
            ?: emptyList()

    fun load(id: String): StoredSession? =
        runCatching {
            val f = fileOf(id)
            if (f.exists()) HaoJson.json.decodeFromString(StoredSession.serializer(), f.readText()) else null
        }.getOrNull()

    fun save(session: StoredSession) {
        runCatching {
            session.updatedAt = System.currentTimeMillis()
            if (session.title == "新会话") {
                session.messages.firstOrNull { it.role == ChatMessage.ROLE_USER && it.content.isNotBlank() }
                    ?.let { session.title = it.content.take(24).replace('\n', ' ') }
            }
            fileOf(session.id).writeText(HaoJson.json.encodeToString(StoredSession.serializer(), session))
        }
    }

    fun delete(id: String) {
        runCatching { fileOf(id).delete() }
    }
}
