package com.haoai.agent.agent.tools.snapshot

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 写文件前快照（1.3）：write/edit 执行前把工作区文件的原始内容存到 app 私有目录，
 * 支撑审批弹窗 diff 回看与 /undo 回滚（5.5 前置）。
 *
 * 存储布局：filesDir/snapshots/<sessionId>/
 *   ├── manifest.jsonl      每行一条元数据（追加式）
 *   ├── <callId>.before     执行前全文
 *   └── <callId>.after      执行后全文（回看"该次变更"不依赖文件后续状态）
 *
 * 目录刻意放 filesDir（app 私有）：工作区是 Agent 可见的，快照绝不写进工作区。
 */
object FileSnapshot {

    @Serializable
    data class Meta(
        val callId: String,
        val path: String,
        val ts: Long,
        val bytes: Int
    )

    private const val MAX_PER_SESSION = 200
    private const val MAX_TOTAL_BYTES = 50L * 1024 * 1024
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun dir(filesDir: File, sessionId: String) = File(filesDir, "snapshots/$sessionId")
    private fun manifest(dir: File) = File(dir, "manifest.jsonl")

    /**
     * 拍快照。[before]/[after] 为执行前后的文件全文；新文件（原不存在）时 before 传 null。
     * 落盘与淘汰都在调用方 IO Dispatcher 上执行。
     */
    fun snapshot(
        filesDir: File,
        sessionId: String,
        callId: String,
        path: String,
        before: String?,
        after: String
    ) {
        val d = dir(filesDir, sessionId).apply { mkdirs() }
        if (before != null) File(d, "$callId.before").writeText(before)
        File(d, "$callId.after").writeText(after)
        val meta = Meta(callId, path, System.currentTimeMillis(), after.toByteArray(Charsets.UTF_8).size)
        manifest(d).appendText(json.encodeToString(Meta.serializer(), meta) + "\n")
        enforceRetention(d)
    }

    /** 读某次变更的快照：返回 (元数据, before?, after)。 */
    fun read(filesDir: File, sessionId: String, callId: String): Triple<Meta, String?, String?>? {
        val d = dir(filesDir, sessionId)
        val meta = listSession(filesDir, sessionId).firstOrNull { it.callId == callId } ?: return null
        val before = File(d, "$callId.before").takeIf { it.exists() }?.readText()
        val after = File(d, "$callId.after").takeIf { it.exists() }?.readText()
        return Triple(meta, before, after)
    }

    /** 本会话全部快照元数据（时间正序）。 */
    fun listSession(filesDir: File, sessionId: String): List<Meta> {
        val m = manifest(dir(filesDir, sessionId))
        if (!m.exists()) return emptyList()
        return runCatching {
            m.readLines().filter { it.isNotBlank() }.map {
                json.decodeFromString(Meta.serializer(), it)
            }
        }.getOrDefault(emptyList())
    }

    /** 保留策略：每会话 200 个或总量 50MB，超限淘汰最旧（含 manifest 行与文件）。 */
    private fun enforceRetention(d: File) {
        val metas = listSession(d.parentFile ?: return, d.name).toMutableList()
        if (metas.isEmpty()) return
        val totalBytes = metas.sumOf { it.bytes.toLong() }
        var overflow = metas.size > MAX_PER_SESSION || totalBytes > MAX_TOTAL_BYTES
        if (!overflow) return
        val removed = mutableSetOf<String>()
        while (overflow && metas.isNotEmpty()) {
            val oldest = metas.removeFirst()
            removed.add(oldest.callId)
            File(d, "${oldest.callId}.before").delete()
            File(d, "${oldest.callId}.after").delete()
            overflow = metas.size > MAX_PER_SESSION ||
                metas.sumOf { it.bytes.toLong() } > MAX_TOTAL_BYTES
        }
        if (removed.isNotEmpty()) {
            m(d).writeText(metas.joinToString("\n") { json.encodeToString(Meta.serializer(), it) })
        }
    }

    // manifest() 返回 File，这里包一层避免表达式重排
    private fun m(d: File) = manifest(d)
}
