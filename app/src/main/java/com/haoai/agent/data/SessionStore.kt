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

    // 会话可能很大（数百条消息），主线程每追加一条就同步序列化+写盘会造成明显卡顿：
    // save 只更新内存缓存，序列化与写盘交给单线程后台执行（最新状态覆盖旧写入）。
    private val io = java.util.concurrent.Executors.newSingleThreadExecutor()

    /** 最新未落盘状态：save 后、写盘完成前，load/list 必须能看到。 */
    private val latest = java.util.concurrent.ConcurrentHashMap<String, StoredSession>()

    /** 已落盘解析结果缓存（按 mtime 失效），避免 list() 每次全量读盘解析。 */
    private val diskCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, StoredSession>>()

    private fun fileOf(id: String) = File(dir, "$id.json")

    private fun parseFile(f: File): StoredSession? =
        HaoJson.readTextSafe(f)?.let { t ->
            runCatching { HaoJson.json.decodeFromString(StoredSession.serializer(), t) }.getOrNull()
        }

    fun list(): List<StoredSession> {
        val files = dir.listFiles { f -> f.extension == "json" } ?: return emptyList()
        val merged = LinkedHashMap<String, StoredSession>()
        for (f in files) {
            val id = f.nameWithoutExtension
            latest[id]?.let { merged[id] = it; continue }
            val mtime = f.lastModified()
            val hit = diskCache[id]
            val parsed = if (hit != null && hit.first == mtime) hit.second
            else parseFile(f)?.also { diskCache[id] = mtime to it } ?: continue
            merged[id] = parsed
        }
        return merged.values.sortedByDescending { it.updatedAt }
    }

    fun load(id: String): StoredSession? {
        latest[id]?.let { return it }
        val f = fileOf(id)
        if (!f.exists()) return null
        val mtime = f.lastModified()
        val hit = diskCache[id]
        return hit?.takeIf { it.first == mtime }?.second
            ?: parseFile(f)?.also { diskCache[id] = mtime to it }
    }

    @Synchronized
    fun save(session: StoredSession) {
        session.updatedAt = System.currentTimeMillis()
        if (session.title == "新会话") {
            session.messages.firstOrNull { it.role == ChatMessage.ROLE_USER && it.content.isNotBlank() }
                ?.let { session.title = it.content.take(24).replace('\n', ' ') }
        }
        // 快照：引擎随时会向 session.messages 追加消息，后台序列化必须基于不可变快照
        val snapshot = session.copy(messages = session.messages.toMutableList())
        latest[session.id] = session
        diskCache[session.id] = snapshot.updatedAt to snapshot
        io.execute {
            runCatching {
                HaoJson.writeAtomic(
                    fileOf(snapshot.id),
                    HaoJson.json.encodeToString(StoredSession.serializer(), snapshot)
                )
            }
        }
    }

    fun delete(id: String) {
        latest.remove(id)
        diskCache.remove(id)
        io.execute { runCatching { fileOf(id).delete() } }
    }
}
