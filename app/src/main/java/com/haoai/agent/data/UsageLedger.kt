package com.haoai.agent.data

import kotlinx.serialization.Serializable
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 运行账本（5.4）：每次 LLM 调用与工具调用的 JSONL 追加记录。
 * 按月分文件 filesDir/usage/usage-YYYYMM.jsonl；写失败静默（账本绝不影响主流程）；
 * 90 天前的月份文件由 [sweep] 清理。
 */
object UsageLedger {

    @Serializable
    data class Entry(
        /** "llm" | "tool" */
        val kind: String,
        val ts: Long,
        val sessionId: String? = null,
        /** llm 用途：chat/subagent/memory/title/btw/compact/dream */
        val purpose: String? = null,
        val model: String? = null,
        val promptTokens: Int = 0,
        val completionTokens: Int = 0,
        /** tool 名（kind=tool 时） */
        val tool: String? = null,
        /** READ/WRITE/EXEC（kind=tool 时） */
        val risk: String? = null,
        /** approved/denied/blocked/direct/unknown */
        val policyDecision: String? = null,
        val durationMs: Long = 0,
        val ok: Boolean = true
    )

    data class ModelAgg(val model: String, val promptTokens: Long, val completionTokens: Long, val calls: Int)
    data class SessionAgg(val sessionId: String, val promptTokens: Long, val completionTokens: Long, val calls: Int)
    data class PurposeAgg(val purpose: String, val promptTokens: Long, val completionTokens: Long, val calls: Int)

    data class Summary(
        val totalIn: Long, val totalOut: Long,
        val todayIn: Long, val todayOut: Long,
        val weekIn: Long, val weekOut: Long,
        val monthIn: Long, val monthOut: Long,
        val byModel: List<ModelAgg>,
        val bySession: List<SessionAgg>,
        val byPurpose: List<PurposeAgg>,
        val entries: Int
    )

    @Volatile private var dir: File? = null
    private val executor = Executors.newSingleThreadExecutor()
    private val lock = Any()

    fun init(filesDir: File) {
        dir = File(filesDir, "usage").apply { mkdirs() }
        executor.execute { runCatching { sweep() } }
    }

    fun add(entry: Entry) {
        executor.execute { runCatching { appendNow(entry) } }
    }

    /** 同步追加（测试用；主流程一律走异步 [add]）。 */
    fun appendNow(entry: Entry) {
        val d = dir ?: return
        synchronized(lock) {
            File(d, fileNameFor(entry.ts)).appendText(HaoJson.json.encodeToString(Entry.serializer(), entry) + "\n")
        }
    }

    fun fileNameFor(ts: Long): String {
        val fmt = SimpleDateFormat("yyyyMM", Locale.US)
        return "usage-${fmt.format(Date(ts))}.jsonl"
    }

    /** 清理 90 天前的月份文件（整个文件按月边界判断，落后不足一个月的保留）。 */
    fun sweep(nowMs: Long = System.currentTimeMillis()) {
        val d = dir ?: return
        val cutoffMonth = (nowMs - 90L * 24 * 3600 * 1000) / 1000L
        val fmt = SimpleDateFormat("yyyyMM", Locale.US)
        val cutoff = fmt.format(Date(cutoffMonth * 1000L)).toInt()
        d.listFiles()?.forEach { f ->
            val m = Regex("usage-(\\d{6})\\.jsonl").find(f.name)?.groupValues?.get(1)?.toIntOrNull() ?: return@forEach
            if (m < cutoff) runCatching { f.delete() }
        }
    }

    private fun readAll(): List<Entry> {
        val d = dir ?: return emptyList()
        val out = mutableListOf<Entry>()
        d.listFiles()?.filter { it.name.startsWith("usage-") }?.sortedBy { it.name }?.forEach { f ->
            runCatching {
                f.readLines().forEach { line ->
                    if (line.isBlank()) return@forEach
                    runCatching { HaoJson.json.decodeFromString(Entry.serializer(), line) }.getOrNull()?.let { out.add(it) }
                }
            }
        }
        return out
    }

    private class Acc {
        var todayIn = 0L; var todayOut = 0L
        var weekIn = 0L; var weekOut = 0L
        var monthIn = 0L; var monthOut = 0L
        val models = mutableMapOf<String, LongArray>()    // [in,out,count]
        val sessions = mutableMapOf<String, LongArray>()
        val purposes = mutableMapOf<String, LongArray>()
    }

    /**
     * 汇总全部账本记录；[filterSession] 非空时只统计该会话（此时周/月与分布栏同样只含该会话）。
     * today 以本地日历日界；week 以本周一为界。
     */
    fun summarize(filterSession: String? = null): Summary {
        val entries = readAll().let { if (filterSession != null) it.filter { e -> e.sessionId == filterSession } else it }
        val cal = java.util.Calendar.getInstance()
        val todayStart = cal.apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        val weekStart = cal.apply {
            set(java.util.Calendar.DAY_OF_WEEK, java.util.Calendar.MONDAY)
        }.timeInMillis
        val monthStart = cal.apply {
            set(java.util.Calendar.DAY_OF_MONTH, 1); set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis

        val acc = Acc()
        for (e in entries) {
            val pin = e.promptTokens.toLong().coerceAtLeast(0)
            val pout = e.completionTokens.toLong().coerceAtLeast(0)
            if (e.ts >= todayStart) { acc.todayIn += pin; acc.todayOut += pout }
            if (e.ts >= weekStart) { acc.weekIn += pin; acc.weekOut += pout }
            if (e.ts >= monthStart) { acc.monthIn += pin; acc.monthOut += pout }
            when (e.kind) {
                "llm" -> {
                    val key = e.model.orEmpty().ifBlank { "unknown" }
                    acc.models.getOrPut(key) { LongArray(3) }.let { it[0] += pin; it[1] += pout; it[2]++ }
                    val pk = e.purpose.orEmpty().ifBlank { "chat" }
                    acc.purposes.getOrPut(pk) { LongArray(3) }.let { it[0] += pin; it[1] += pout; it[2]++ }
                    val sid = e.sessionId.orEmpty().ifBlank { "无会话" }
                    acc.sessions.getOrPut(sid) { LongArray(3) }.let { it[0] += pin; it[1] += pout; it[2]++ }
                }
                "tool" -> {
                    val sid = e.sessionId.orEmpty().ifBlank { "无会话" }
                    acc.sessions.getOrPut(sid) { LongArray(3) }.let { it[2]++ }
                }
            }
        }
        val toModel = { m: Map.Entry<String, LongArray> ->
            ModelAgg(m.key, m.value[0], m.value[1], m.value[2].toInt())
        }
        val toPurpose = { m: Map.Entry<String, LongArray> ->
            PurposeAgg(m.key, m.value[0], m.value[1], m.value[2].toInt())
        }
        return Summary(
            totalIn = entries.sumOf { it.promptTokens.toLong().coerceAtLeast(0) },
            totalOut = entries.sumOf { it.completionTokens.toLong().coerceAtLeast(0) },
            todayIn = acc.todayIn, todayOut = acc.todayOut,
            weekIn = acc.weekIn, weekOut = acc.weekOut,
            monthIn = acc.monthIn, monthOut = acc.monthOut,
            byModel = acc.models.map(toModel).sortedByDescending { it.promptTokens + it.completionTokens },
            bySession = acc.sessions.map { SessionAgg(it.key, it.value[0], it.value[1], it.value[2].toInt()) }
                .sortedByDescending { it.promptTokens + it.completionTokens },
            byPurpose = acc.purposes.map(toPurpose).sortedByDescending { it.promptTokens + it.completionTokens },
            entries = entries.size
        )
    }

    /** 清空全部账本文件（设置页按钮）。 */
    fun clearAll() {
        val d = dir ?: return
        synchronized(lock) { d.listFiles()?.forEach { runCatching { it.delete() } } }
    }

    /** 今日（输入+输出）token 合计，供 5.1 预算判定。 */
    fun todayTokens(): Long = summarize().let { it.todayIn + it.todayOut }

    /**
     * 5.1 每日预算提示文案（注入 SystemPrompt 尾部）：
     * budgetK≤0 关闭；≥70% 提醒精简；≥100% 警告超预算。
     */
    fun budgetHint(budgetK: Int): String {
        if (budgetK <= 0) return ""
        val budget = budgetK * 1000L
        val used = todayTokens()
        if (used >= budget) {
            return "\n[每日预算警告] 今日 token 已超预算（${used / 1000}K/${budgetK}K）。如非用户明确要求，避免一切非必要工具调用，回复尽量精简。"
        }
        if (used >= budget * 7 / 10) {
            return "\n[每日预算] 今日 token 已用 ${used * 100 / budget}%，回复请更精简。"
        }
        return ""
    }

    /** 5.1 预算是否已超（定时任务/梦境固化跳过判定）。 */
    fun budgetExhausted(budgetK: Int): Boolean =
        budgetK > 0 && todayTokens() >= budgetK * 1000L
}
