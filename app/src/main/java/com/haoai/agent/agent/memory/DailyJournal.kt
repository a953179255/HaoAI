package com.haoai.agent.agent.memory

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@kotlinx.serialization.Serializable
data class JournalEntry(
    val id: String,
    val content: String,
    val importance: Int = 3,
    val createdAt: Long = System.currentTimeMillis(),
    val source: String = "auto"
)

@kotlinx.serialization.Serializable
data class JournalDay(
    val date: String,
    val items: MutableList<JournalEntry> = mutableListOf()
)

/**
 * 每日情景记忆（episodic 层），上游 式 Markdown 日记：
 * 存储于 <appFilesDir>/memory/YYYY-MM-DD.md，每行一条：
 *   `- HH:mm | imp=N | src=xxx | 内容`
 * 纯文本可直接查看/手动补充；程序解析失败的行为忽略，不会崩溃。
 * 来源：handoff 交接自动记录（handoff）、模型主动记录（model）、手动添加（manual）。
 * 夜间由 MemoryConsolidation 固化：重要条目晋升长期库，过期（默认 7 天）清理。
 */
class DailyJournal(
    private val appFilesDir: File,
    private val keepDays: Int = 7,
    storageDir: File? = null
) {

    private val storageRoot: File? = storageDir
    private val dir get() = storageRoot ?: File(appFilesDir, "memory")
    private val legacyDir get() = File(appFilesDir, "journal")
    private val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.CHINA)

    init {
        migrateLegacyJson()
    }

    /** 一次性迁移旧日志（JSON 或 内部 memory 目录下的 Markdown）到最终存储目录。 */
    private fun migrateLegacyJson() {
        runCatching {
            // 中间版本可能已把 md 迁到内部 memory/，先搬到目标目录
            if (storageRoot != null) {
                val internalMd = File(appFilesDir, "memory")
                val mds = internalMd.listFiles { f -> f.name.endsWith(".md") }
                if (!mds.isNullOrEmpty()) {
                    dir.mkdirs()
                    for (f in mds) {
                        val out = File(dir, f.name)
                        if (!out.exists()) runCatching { f.copyTo(out) }
                    }
                    internalMd.deleteRecursively()
                }
            }
            val files = legacyDir.listFiles { f -> f.name.endsWith(".json") } ?: return
            if (files.isEmpty()) {
                if (legacyDir.exists()) legacyDir.deleteRecursively()
                return
            }
            dir.mkdirs()
            for (f in files) {
                val day = runCatching {
                    com.haoai.agent.data.HaoJson.json.decodeFromString(
                        LegacyDay.serializer(), f.readText()
                    )
                }.getOrNull() ?: continue
                val out = File(dir, f.name.removeSuffix(".json") + ".md")
                if (out.exists()) continue
                out.writeText(buildString {
                    appendLine("# ${day.date}")
                    day.items.forEach { e ->
                        appendLine(formatLine(
                            timeFmt.format(Date(e.createdAt)), e.importance, e.source, e.content
                        ))
                    }
                })
            }
            legacyDir.deleteRecursively()
        }
    }

    @kotlinx.serialization.Serializable
    private data class LegacyDay(val date: String, val items: List<LegacyEntry> = emptyList())

    @kotlinx.serialization.Serializable
    private data class LegacyEntry(
        val id: String = "",
        val content: String,
        val importance: Int = 3,
        val createdAt: Long = 0,
        val source: String = "auto"
    )

    private fun today(): String = dayFmt.format(Date())

    private fun fileFor(date: String) = File(dir, "$date.md")

    // 近几天日志缓存：promptSnippet 每轮注入系统提示，避免反复读盘解析
    @Volatile
    private var recentCache: Pair<Int, List<JournalDay>>? = null

    private fun invalidateCache() {
        recentCache = null
    }

    private fun formatLine(time: String, importance: Int, source: String, content: String) =
        "- $time | imp=${importance.coerceIn(1, 5)} | src=${source.take(10)} | ${sanitize(content).take(300)}"

    /** 行格式用 " | " 分隔字段，正文里的分隔符必须替换，否则解析截断且去重失效。 */
    private fun sanitize(content: String): String =
        content.replace('\n', ' ').replace(" | ", " ｜ ")

    @Synchronized
    fun append(content: String, importance: Int = 3, source: String = "auto"): JournalEntry? {
        val text = sanitize(content.trim())
        if (text.isEmpty()) return null
        dir.mkdirs()
        val date = today()
        val f = fileFor(date)
        if (!f.exists()) f.writeText("# $date\n")
        val existing = parseFile(f)
        // 入库内容会被 take(300) 截断，去重必须按同一口径比较，否则长文本反复追加
        if (existing.any { it.content == text.take(300) }) return null
        val entry = JournalEntry(
            id = Integer.toHexString((text + System.currentTimeMillis()).hashCode()),
            content = text.take(300),
            importance = importance.coerceIn(1, 5),
            createdAt = System.currentTimeMillis(),
            source = source.take(10)
        )
        f.appendText(formatLine(timeFmt.format(Date()), entry.importance, entry.source, text) + "\n")
        trimToMax(f, existing.size + 1)
        invalidateCache()
        return entry
    }

    /**
     * 每日配额（保留最新的）：普通记录 MAX_PER_DAY 条；handoff 交接独立配额 MAX_HANDOFF_PER_DAY，
     * 互不挤占——长任务多的用户，主动记录的事件不会被自动交接日志顶掉。
     */
    private fun trimToMax(f: File, currentCount: Int) {
        if (currentCount <= MAX_PER_DAY) return
        val lines = f.readLines()
        val header = lines.takeWhile { !it.startsWith("- ") }
        val body = lines.drop(header.size)
        val isHandoff = body.map { it.contains("src=handoff") }
        // 按原始时间顺序保留：普通条目取最新 50 条，handoff 取最新 10 条
        val keepNormal = body.indices.filter { !isHandoff[it] }.takeLast(MAX_PER_DAY).toHashSet()
        val keepHandoff = body.indices.filter { isHandoff[it] }.takeLast(MAX_HANDOFF_PER_DAY).toHashSet()
        val kept = body.filterIndexed { i, _ -> i in keepNormal || i in keepHandoff }
        com.haoai.agent.data.HaoJson.writeAtomic(f, (header + kept).joinToString("\n").trimEnd() + "\n")
    }

    private fun parseFile(f: File): List<JournalEntry> {
        val date = f.name.removeSuffix(".md")
        val dayMs = runCatching {
            dayFmt.isLenient = false
            dayFmt.parse(date)?.time
        }.getOrNull() ?: return emptyList()
        return f.readLines().mapNotNull { line ->
            parseLine(line, date, dayMs)
        }
    }

    private fun parseLine(line: String, date: String, dayMs: Long): JournalEntry? {
        if (!line.startsWith("- ")) return null
        val parts = line.removePrefix("- ").split(" | ")
        if (parts.size < 4) return null
        val time = parts[0].trim()
        val imp = parts.firstOrNull { it.startsWith("imp=") }
            ?.removePrefix("imp=")?.trim()?.toIntOrNull() ?: 3
        val src = parts.firstOrNull { it.startsWith("src=") }
            ?.removePrefix("src=")?.trim()?.takeIf { it.isNotEmpty() } ?: "auto"
        val content = parts.drop(2).firstOrNull { !it.startsWith("imp=") && !it.startsWith("src=") }
            ?.trim() ?: return null
        val ts = runCatching {
            dayFmt.parse(date)?.let { d -> timeFmt.parse(time)?.let { t ->
                val cal = java.util.Calendar.getInstance()
                cal.time = d
                cal.set(java.util.Calendar.HOUR_OF_DAY, t.hours)
                cal.set(java.util.Calendar.MINUTE, t.minutes)
                cal.timeInMillis
            } }
        }.getOrNull() ?: dayMs
        return JournalEntry(
            id = Integer.toHexString((content + ts).hashCode()),
            content = content,
            importance = imp.coerceIn(1, 5),
            createdAt = ts,
            source = src
        )
    }

    /** 近 n 天的日志，按日期倒序（带缓存，写入/清理时失效）。 */
    @Synchronized
    fun recent(days: Int = 2): List<JournalDay> {
        recentCache?.let { (d, v) -> if (d == days) return v }
        if (!dir.exists()) return emptyList()
        val files = dir.listFiles { f -> f.name.endsWith(".md") } ?: return emptyList()
        val result = files.mapNotNull { f ->
            val items = runCatching { parseFile(f) }.getOrDefault(emptyList())
            if (items.isEmpty()) null
            else JournalDay(f.name.removeSuffix(".md"), items.toMutableList())
        }.sortedByDescending { it.date }.take(days)
        recentCache = days to result
        return result
    }

    fun allDays(): List<JournalDay> = recent(Int.MAX_VALUE)

    fun count(): Int = allDays().sumOf { it.items.size }

    /** 清理超过 days 天的日志文件，返回清理条数。 */
    @Synchronized
    fun expire(days: Int = keepDays): Int {
        if (!dir.exists()) return 0
        val files = dir.listFiles { f -> f.name.endsWith(".md") } ?: return 0
        var removed = 0
        for (f in files) {
            val dayMs = runCatching {
                dayFmt.isLenient = false
                dayFmt.parse(f.name.removeSuffix(".md"))?.time
            }.getOrNull() ?: continue
            if (System.currentTimeMillis() - dayMs > days * 86_400_000L) {
                removed += parseFile(f).size
                f.delete()
            }
        }
        if (removed > 0) invalidateCache()
        return removed
    }

    @Synchronized
    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
        invalidateCache()
    }

    /** 当前日志存储目录（备份/导出用）：workspace memory/ 或内部回退目录。 */
    fun currentStorageDir(): File = dir

    /** 注入系统提示词：近 n 天动态摘要。 */
    fun promptSnippet(days: Int = 2, capPerItem: Int = 120): String {
        val rec = recent(days)
        if (rec.isEmpty()) return ""
        return buildString {
            appendLine("## 近期动态（最近发生的事，回答时参考）")
            for (day in rec) {
                appendLine("### ${day.date}")
                day.items.takeLast(15).forEach { e ->
                    appendLine("- ${e.content.take(capPerItem)}")
                }
            }
        }.trimEnd()
    }

    companion object {
        const val MAX_PER_DAY = 50

        /** handoff 交接独立配额：不挤占每日 50 条主动记录。 */
        const val MAX_HANDOFF_PER_DAY = 10
    }
}
