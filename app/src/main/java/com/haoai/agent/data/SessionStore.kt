package com.haoai.agent.data

import android.content.Context
import com.haoai.agent.agent.model.ChatMessage
import com.haoai.agent.agent.tools.takeSafe
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
    /** 音频/视频附件的本机文件路径（不存 base64，避免会话 JSON 膨胀）。 */
    val audioPath: String? = null,
    val videoPath: String? = null,
    val reasoning: String? = null,
    /** 本轮（含工具循环）累计输入/输出 tokens 与整轮耗时、模型名；旧 JSON 缺省 null。 */
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val durationMs: Long? = null,
    val model: String? = null,
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
    /** 上下文压缩摘要（CompactionManager 生成）。非空时表示更早的历史已被摘进这段摘要。 */
    var compactionSummary: String? = null,
    /**
     * 压缩水位：这条消息**及其之前**的内容已由 [compactionSummary] 覆盖，构建请求时不再发送。
     *
     * 为什么用水位而不是把旧消息删掉：原先压缩会从 messages 里物理移除旧消息，
     * 于是摘要写错了/压过头就没有补救办法，用户在聊天页也会看到历史凭空消失。
     * 现在原文全部留在会话里（可回查、可再压、可导出），只是不进请求。
     * null=旧会话未压缩过，按整段历史处理。
     */
    var compactedThroughId: String? = null,
    /** 智能标题已生成过（只生成一次，避免每轮都花 token）。 */
    var titleAuto: Boolean = false,
    /** 置顶：列表排序最前（抽屉/全部会话页共用）。 */
    var pinned: Boolean = false,
    /** E1 引擎运行状态机：null=空闲（旧会话零迁移）；running=执行中；其他=中断/封顶/失败（可恢复）。 */
    var runState: String? = null,
    /** E1 本轮目标（用户指令摘要 ≤200 字），供恢复横幅展示与恢复注入。 */
    var runGoal: String? = null,
    /** E1 本轮已用轮数（turncapped 时续跑参考）。 */
    var runTurnsUsed: Int = 0,
    /** E4b 工具分层：会话内已启用的工具组（core 恒开）。null=全开（升级前旧会话零感知）；新会话默认仅 core。 */
    var activeGroups: List<String>? = null
) {
    companion object {
        const val RUN_IDLE = "idle"
        const val RUN_INTERRUPTED = "interrupted"
        const val RUN_TURNCAPPED = "turncapped"
        const val RUN_FAILED = "failed"

        /** 中断尾部形态（OpenMinis InterruptedTailDetector 式）：断在哪决定恢复横幅说什么。 */
        const val TAIL_CLEAN = "clean"
        const val TAIL_UNANSWERED_USER = "unanswered_user"
        const val TAIL_TOOL = "tool"
        const val TAIL_PARTIAL_ASSISTANT = "partial_assistant"

        /** 是否需要展示恢复入口（显式主动停止/完成除外）。 */
        fun resumable(runState: String?): Boolean =
            runState == RUN_INTERRUPTED || runState == RUN_TURNCAPPED || runState == RUN_FAILED

        /**
         * 从消息尾部判定中断形态：最后一条实质消息是什么，任务就断在哪一步。
         * 用户消息没有回复=还没开始干；停在 tool=工具循环中断；assistant=回答生成到一半。
         * 纯函数、运行时判定不入库；判定与活性分离（是否在跑由调用方先行 gate，
         * 把两者混在一起会把"还在等回复的 turn"误报成中断）。
         */
        fun tailShapeOf(messages: List<StoredMessage>): String {
            for (i in messages.indices.reversed()) {
                val m = messages[i]
                if (m.content.isBlank() && m.toolName == null) continue
                return when {
                    m.role == ChatMessage.ROLE_USER -> TAIL_UNANSWERED_USER
                    m.role == ChatMessage.ROLE_TOOL || (m.role == ChatMessage.ROLE_ASSISTANT && m.toolName != null) -> TAIL_TOOL
                    else -> TAIL_PARTIAL_ASSISTANT
                }
            }
            return TAIL_CLEAN
        }

        fun create(workspaceUri: String?): StoredSession {
            val now = System.currentTimeMillis()
            return StoredSession(
                id = UUID.randomUUID().toString(),
                title = "新会话",
                createdAt = now,
                updatedAt = now,
                workspaceUri = workspaceUri,
                // E4b 新会话默认仅注入 core 工具组（token 明显下降）；extended/mcp 由模型 tools_enable 按需启用
                activeGroups = listOf(com.haoai.agent.agent.tools.ToolRegistry.GROUP_CORE)
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
    audioPath = audioPath,
    videoPath = videoPath,
    reasoning = reasoning,
    promptTokens = promptTokens,
    completionTokens = completionTokens,
    durationMs = durationMs,
    model = model,
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
    audioPath = audioPath,
    videoPath = videoPath,
    reasoning = reasoning,
    promptTokens = promptTokens,
    completionTokens = completionTokens,
    durationMs = durationMs,
    model = model,
    ts = ts
)

/** 会话存储。[dir] 重载供单测用临时目录跑真实 IO；生产走 [AppContainer] 的 Context 构造。 */
class SessionStore(private val dir: File) {

    constructor(context: Context) : this(File(context.filesDir, "sessions").apply { mkdirs() })

    // 会话可能很大（数百条消息），主线程每追加一条就同步序列化+写盘会造成明显卡顿：
    // save 只更新内存缓存，序列化与写盘交给单线程后台执行（最新状态覆盖旧写入）。
    private val io = java.util.concurrent.Executors.newSingleThreadExecutor()

    /**
     * 待写快照：同一会话只保留最后一次，后写覆盖前写。
     * 一轮对话里 appendAndNotify 会触发几十次 save，若每次都排队全量序列化，
     * 队列会越积越长且前面的写入毫无意义（很快就被后面那版覆盖）。
     */
    private val pending = java.util.concurrent.ConcurrentHashMap<String, StoredSession>()

    /** 实际发生的写盘次数（观测合并效果用；单测断言它远小于 save 调用次数）。 */
    internal val writes = java.util.concurrent.atomic.AtomicInteger()

    /** 是否已有 drain 任务排队/执行中；与 [save] 共用 this 锁，见 [takePending]。 */
    private var drainScheduled = false

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

    /**
     * 全部会话的最新可见状态 = 磁盘 ∪ 内存。
     *
     * 只看磁盘会漏掉「save 之后、写盘落地之前」的新会话：写盘在后台线程排队，
     * 期间抽屉/会话页列不出来这条会话。所以 latest 里未落盘的也要并进来。
     */
    private fun allStored(): List<StoredSession> {
        val merged = LinkedHashMap<String, StoredSession>()
        val files = dir.listFiles { f -> f.extension == "json" }.orEmpty()
        for (f in files) {
            val id = f.nameWithoutExtension
            if (id in tombstones) continue
            latest[id]?.let { merged[id] = it; continue }
            val mtime = f.lastModified()
            val hit = diskCache[id]
            val parsed = if (hit != null && hit.first == mtime) hit.second
            else parseFile(f)?.also { diskCache[id] = mtime to it } ?: continue
            merged[id] = parsed
        }
        for ((id, s) in latest) if (id !in merged && id !in tombstones) merged[id] = s
        return merged.values.toList()
    }

    fun list(): List<StoredSession> =
        allStored().filter { it.deletedAt == 0L }.sortedByDescending { it.updatedAt }

    /** 回收站内容（按删除时间倒序）。 */
    fun listDeleted(): List<StoredSession> =
        allStored().filter { it.deletedAt > 0L }.sortedByDescending { it.deletedAt }

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
                    ?.let { session.title = it.content.takeSafe(24).replace('\n', ' ') }
            }
        }
        // 快照：引擎随时会向 session.messages 追加消息，后台序列化必须基于不可变快照
        val snapshot = session.copy(messages = session.messages.toMutableList())
        latest[session.id] = session
        diskCache[session.id] = snapshot.updatedAt to snapshot
        pending[session.id] = snapshot
        if (!drainScheduled) {
            drainScheduled = true
            io.execute { drainPending() }
        }
    }

    /**
     * 取一份待写快照；取空时顺手在同一次持锁里清掉调度标记。
     * 清空必须和"没有待写了"这个判定原子：否则 save 可能刚把快照放进 pending、
     * 又看到 drainScheduled=true 而不排队，drain 随后清标记退出，那次写入就永远搁置。
     */
    @Synchronized
    private fun takePending(): StoredSession? {
        val id = pending.keys.firstOrNull() ?: run { drainScheduled = false; return null }
        return pending.remove(id)
    }

    private fun drainPending() {
        while (true) {
            val snapshot = takePending() ?: return
            if (snapshot.id in tombstones) continue
            writes.incrementAndGet()
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
            latest.remove(id); diskCache.remove(id); pending.remove(id)
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
        pending.remove(id)
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
