package com.haoai.pc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File

/**
 * 一次运行的文件检查点 —— "回到这次任务之前"的底座。
 *
 * 已有的 `.haoai-snap/` 只解决"单个文件改坏了退回上一版"，而 vibe coding 里真正想按的那颗钮是
 * **"这次任务做的所有改动全部撤销"**：跑歪了的不止一个文件，还可能新建了三个。
 * 所以这里记的是"这一轮动过哪些路径、各自改之前是什么样（或本来不存在）"。
 *
 * 快照本身不复制第二份 —— 复用 `Snapshots` 已经写下的那个文件，这里只记指针。
 * 同一条路径在一轮里被改三次，回滚要的是**最早**那一份（= 这轮开始前的样子）。
 */
object Checkpoints {
    private val json = Json { ignoreUnknownKeys = true }

    /** 一条记录：这一轮动了这个路径，改之前的快照在 snap（空 = 这轮之前它不存在）。 */
    data class Entry(
        val run: String, val sid: String, val ts: Long, val goal: String,
        val path: String, val snap: String
    )

    val file: File get() = File(Env.home, "checkpoints.jsonl")
    private const val KEEP = 4000

    /** 一轮开始登记一条头行（带任务名）。没有它，列表里就不知道这一轮是干什么的。 */
    fun begin(sid: String, run: String, goal: String) =
        write(Entry(run, sid, System.currentTimeMillis(), goal.take(200), "", ""))

    fun note(ctx: ToolCtx, target: File, snap: File?) {
        val run = ctx.runId
        if (run.isBlank()) return
        val rel = runCatching { ctx.rel(target) }.getOrDefault(target.absolutePath)
        write(Entry(run, ctx.sid, System.currentTimeMillis(), "", rel, snap?.absolutePath ?: ""))
    }

    private fun write(e: Entry) {
        runCatching {
            file.parentFile?.mkdirs()
            file.appendText(
                buildJsonObject {
                    put("run", e.run); put("sid", e.sid); put("ts", e.ts); put("goal", e.goal)
                    put("path", e.path); put("snap", e.snap)
                }.toString() + "\n"
            )
            prune()
        }
    }

    private fun prune() {
        runCatching {
            val lines = file.readLines().filter { it.isNotBlank() }
            if (lines.size > KEEP) file.writeText(lines.takeLast(KEEP).joinToString("\n") + "\n")
        }
    }

    /** 新的在前。坏行直接跳过 —— 账本自己不该成为新故障点。 */
    fun entries(limit: Int = 800): List<Entry> = runCatching {
        file.readLines().asReversed().mapNotNull { line ->
            runCatching {
                val o = json.parseToJsonElement(line).jsonObject
                Entry(
                    o["run"]?.jsonPrimitive?.content ?: return@runCatching null,
                    o["sid"]?.jsonPrimitive?.content ?: "",
                    o["ts"]?.jsonPrimitive?.longOrNull ?: 0L,
                    o["goal"]?.jsonPrimitive?.content ?: "",
                    o["path"]?.jsonPrimitive?.content ?: "",
                    o["snap"]?.jsonPrimitive?.content ?: ""
                )
            }.getOrNull()
        }
    }.getOrDefault(emptyList()).take(limit)

    /** 按运行分组（新的在前）。头行（path=""）带着这一轮的任务名。 */
    fun runs(sid: String = "", limit: Int = 40): List<Pair<String, List<Entry>>> {
        val grouped = LinkedHashMap<String, MutableList<Entry>>()
        for (e in entries()) {
            if (sid.isNotEmpty() && e.sid != sid) continue
            grouped.getOrPut(e.run) { mutableListOf() }.add(e)
        }
        return grouped.entries.map { (k, v) -> k to v.sortedBy { it.ts } }.take(limit)
    }

    /**
     * 回到这一轮开始之前。三条硬规矩：
     * ① 只在**这条会话的工作区**里动手，越界的路径一律不碰并说出来；
     * ② 同一路径取**最早**那份快照 —— 取最后一份等于只撤销最后一步；
     * ③ 快照不在/写不回去就**逐条报出来**，回滚这件事上"没说"比"报错"危险得多。
     */
    fun rewind(workspace: File, run: String): RewindReport {
        val ws = runCatching { workspace.canonicalFile }.getOrElse { workspace }
        val rows = entries().filter { it.run == run && it.path.isNotEmpty() }
        if (rows.isEmpty()) return RewindReport(false,
            "这一轮没有登记过文件改动（它可能没动过文件，或者记录太早被清掉了）",
            emptyList(), emptyList(), emptyList())
        val firstByPath = LinkedHashMap<String, Entry>()
        for (e in rows.sortedBy { it.ts }) firstByPath.putIfAbsent(e.path, e)
        val restored = mutableListOf<String>()
        val deleted = mutableListOf<String>()
        val missing = mutableListOf<String>()
        for ((rel, e) in firstByPath) {
            val target = inside(ws, rel)
            if (target == null) { missing += "$rel（不在工作区里，没敢动）"; continue }
            if (e.snap.isBlank()) {
                if (!target.isFile) continue
                val ok = runCatching { target.delete() }.getOrDefault(false)
                if (ok) deleted += rel else missing += "$rel（删不掉）"
                continue
            }
            val snap = File(e.snap)
            if (!snap.isFile) { missing += "$rel（快照文件已经不在了）"; continue }
            if (!trustedSource(ws, snap)) { missing += "$rel（快照位置不认识，没敢用）"; continue }
            val done = runCatching {
                target.parentFile?.mkdirs(); snap.copyTo(target, overwrite = true); true
            }.getOrDefault(false)
            if (done) restored += rel else missing += "$rel（写不回去）"
        }
        val note = buildString {
            append("回到这轮之前：还原 ${restored.size} 个")
            if (deleted.isNotEmpty()) append("、删掉它新建的 ${deleted.size} 个")
            if (missing.isNotEmpty()) append("；${missing.size} 个没能处理")
        }
        return RewindReport(missing.isEmpty(), note, restored, deleted, missing)
    }

    /** 快照只可能来自这两个地方：这条会话工作区里的 .haoai-snap，或状态根。别的一律不信。 */
    private fun trustedSource(ws: File, snap: File): Boolean {
        val p = runCatching { snap.canonicalFile.path }.getOrDefault(snap.absolutePath)
        return p.startsWith(File(ws, ".haoai-snap").absolutePath) ||
            p.startsWith(File(Env.home, "").absolutePath)
    }

    private fun inside(ws: File, rel: String): File? {
        val f = runCatching {
            (if (File(rel).isAbsolute) File(rel) else File(ws, rel)).canonicalFile
        }.getOrNull() ?: return null
        return if (f.path == ws.path || f.path.startsWith(ws.path + File.separator)) f else null
    }

    data class RewindReport(
        val ok: Boolean, val note: String,
        val restored: List<String>, val deleted: List<String>, val missing: List<String>
    )

    /** 界面上那一列：这一轮做了什么、动了几个文件、能不能整轮退回。 */
    fun listJson(sid: String, wsName: String = ""): String {
        val rows = runs(sid).mapNotNull { (run, es) ->
            val files = es.filter { it.path.isNotEmpty() }
            if (files.isEmpty()) return@mapNotNull null
            val head = es.firstOrNull { it.path.isEmpty() }
            val ts = head?.ts ?: files.first().ts
            val goal = (head?.goal ?: "").ifBlank { files.joinToString(" ") { it.path }.take(60) }
            """{"run":${q(run)},"ts":$ts,"goal":${q(goal)},"files":${files.size}}"""
        }
        val ws = if (wsName.isEmpty()) "" else "\"ws\":" + q(wsName) + ","
        return """{"ok":true,$ws"count":${rows.size},"items":[${rows.joinToString(",")}]}"""
    }

    private fun q(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ") + "\""
}
