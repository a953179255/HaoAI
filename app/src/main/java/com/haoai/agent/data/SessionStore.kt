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
    /** 消息唯一 id（旧 JSON 缺省时补新生成，与 ChatMessage.id 对应）。 */
    val id: String = UUID.randomUUID().toString(),
    val role: String,
    val content: String = "",
    val toolCalls: List<StoredToolCall> = emptyList(),
    val toolCallId: String? = null,
    val toolName: String? = null,
    val error: Boolean = false,
    val imageData: String? = null,
    val reasoning: String? = null,
    val ts: Long = System.currentTimeMillis()
)

@Serializable
data class StoredSession(
    val id: String,
    var title: String,
    val createdAt: Long,
    var updatedAt: Long,
    val messages: MutableList<StoredMessage> = mutableListOf(),
    var workspaceUri: String? = null,
    /** 回收站：>0 表示已删除（该时间戳），7 天后自动清理；0=正常会话。 */
    var deletedAt: Long = 0,
    /** 上下文压缩摘要（CompactionManager 生成）。非空时表示历史已被压缩。 */
    var compactionSummary: String? = null,
    /** 智能标题已生成过（只生成一次，避免每轮都花 token）。 */
    var titleAuto: Boolean = false,
    /** 置顶：列表排序最前（抽屉/全部会话页共用）。 */
    var pinned: Boolean = false
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
    id = id,
    role = role,
    content = content,
    toolCalls = toolCalls.map { ToolCallData(it.id, it.name, it.argumentsJson) },
    toolCallId = toolCallId,
    toolName = toolName,
    error = error,
    imageData = imageData,
    reasoning = reasoning,
    ts = ts
)

fun ChatMessage.toStored(): StoredMessage = StoredMessage(
    id = id,
    role = role,
    content = content,
    toolCalls = toolCalls.map { StoredToolCall(it.id, it.name, it.argumentsJson) },
    toolCallId = toolCallId,
    toolName = toolName,
    error = error,
    imageData = imageData,
    reasoning = reasoning,
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

    /**
     * 彻底删除墓碑：deleteForever 后迟到的 save（后台引擎 persist 回调）会把已删会话
     * 重新写回磁盘造成「删除复活」；load/save/list 都要先过这道闸。
     */
    private val tombstones: MutableSet<String> =
        java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())

    private fun fileOf(id: String) = File(dir, "$id.json")

    private fun parseFile(f: File): StoredSession? =
        HaoJson.readJsonSafe(f, StoredSession.serializer())

    fun list(): List<StoredSession> {
        val files = dir.listFiles { f -> f.extension == "json" } ?: return emptyList()
        val merged = LinkedHashMap<String, StoredSession>()
        for (f in files) {
            val id = f.nameWithoutExtension
            if (id in tombstones) continue
            latest[id]?.let { if (it.deletedAt == 0L) merged[id] = it; continue }
            val mtime = f.lastModified()
            val hit = diskCache[id]
            val parsed = if (hit != null && hit.first == mtime) hit.second
            else parseFile(f)?.also { diskCache[id] = mtime to it } ?: continue
            if (parsed.deletedAt == 0L) merged[id] = parsed
        }
        return merged.values.sortedByDescending { it.updatedAt }
    }

    /** 回收站内容（按删除时间倒序）。 */
    fun listDeleted(): List<StoredSession> {
        val files = dir.listFiles { f -> f.extension == "json" } ?: return emptyList()
        val merged = LinkedHashMap<String, StoredSession>()
        for (f in files) {
            val id = f.nameWithoutExtension
            if (id in tombstones) continue
            latest[id]?.let { if (it.deletedAt > 0L) merged[id] = it; continue }
            val mtime = f.lastModified()
            val hit = diskCache[id]
            val parsed = if (hit != null && hit.first == mtime) hit.second
            else parseFile(f)?.also { diskCache[id] = mtime to it } ?: continue
            if (parsed.deletedAt > 0L) merged[id] = parsed
        }
        return merged.values.sortedByDescending { it.deletedAt }
    }

    fun load(id: String): StoredSession? {
        if (id in tombstones) return null
        latest[id]?.let { return it }
        val f = fileOf(id)
        if (!f.exists()) return null
        val mtime = f.lastModified()
        val hit = diskCache[id]
        return hit?.takeIf { it.first == mtime }?.second
            ?: parseFile(f)?.also { diskCache[id] = mtime to it }
    }

    @Synchronized
    fun save(session: StoredSession, touch: Boolean = true) {
        // 已彻底删除的会话：静默丢弃迟到的持久化（引擎后台 persist 等）
        if (session.id in tombstones) return
        if (touch) {
            session.updatedAt = System.currentTimeMillis()
            if (session.title == "新会话") {
                session.messages.firstOrNull { it.role == ChatMessage.ROLE_USER && it.content.isNotBlank() }
                    ?.let { session.title = it.content.take(24).replace('\n', ' ') }
            }
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

    /** 删除单条消息（消息长按操作）。返回是否删除成功。 */
    fun deleteMessage(sessionId: String, messageId: String): Boolean {
        val s = load(sessionId) ?: return false
        val idx = s.messages.indexOfFirst { it.id == messageId }
        if (idx < 0) return false
        s.messages.removeAt(idx)
        save(s)
        return true
    }

    /**
     * 截断：删除 [messageId] 及其后全部消息（重新生成/编辑重发用）。
     * [inclusive] = false 时保留 messageId 本身（编辑重发先改内容再截断其后）。
     */
    fun truncateAfter(sessionId: String, messageId: String, inclusive: Boolean = true): Boolean {
        val s = load(sessionId) ?: return false
        val idx = s.messages.indexOfFirst { it.id == messageId }
        if (idx < 0) return false
        val keep = if (inclusive) idx else idx + 1
        if (keep >= s.messages.size) return false
        while (s.messages.size > keep) s.messages.removeAt(s.messages.size - 1)
        save(s)
        return true
    }

    /** 删除 → 进回收站（软删除），7 天后由 purgeExpired 彻底清理。 */
    fun delete(id: String) {
        val s = load(id) ?: run {
            latest.remove(id); diskCache.remove(id)
            io.execute { runCatching { fileOf(id).delete() } }
            return
        }
        s.deletedAt = System.currentTimeMillis()
        save(s)
    }

    /** 从回收站恢复。 */
    fun restore(id: String) {
        val s = load(id) ?: return
        s.deletedAt = 0
        save(s)
    }

    /** 置顶/取消置顶（touch=false：不刷新 updatedAt，不影响「最近使用」排序）。 */
    fun setPinned(id: String, pinned: Boolean) {
        val s = load(id) ?: return
        s.pinned = pinned
        save(s, touch = false)
    }

    /** 彻底删除（回收站内或直接）。连同 .bak/.tmp 一起清掉，不留隐私残留。 */
    fun deleteForever(id: String) {
        tombstones.add(id)
        latest.remove(id)
        diskCache.remove(id)
        io.execute {
            runCatching { fileOf(id).delete() }
            runCatching { File(dir, "$id.json.bak").delete() }
            runCatching { File(dir, "$id.json.tmp").delete() }
        }
    }

    companion object {
        /** 回收站保留时长：7 天。 */
        const val TRASH_RETENTION_MS = 7L * 24 * 60 * 60 * 1000
    }

    /** 清理回收站中超过保留期的会话（应用启动/删除时调用）。 */
    fun purgeExpiredTrash() {
        val now = System.currentTimeMillis()
        listDeleted().forEach { s ->
            if (now - s.deletedAt > TRASH_RETENTION_MS) deleteForever(s.id)
        }
    }
}
