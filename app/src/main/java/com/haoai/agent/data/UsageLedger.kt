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

    /** 方案B 仪表盘：工具调用聚合（按时段窗口内）。 */
    data class ToolAgg(val tool: String, val calls: Int)

    /** 方案B 仪表盘：趋势图单桶（今日=4小时段 / 本周=日 / 本月=日），peak=窗口内峰值桶。 */
    data class DayBar(val label: String, val tokens: Long, val peak: Boolean = false)

    /**
     * 方案B 时段仪表盘单时段聚合（0=今日 1=本周 2=本月）。
     * 成功率/耗时/速度来自账本已有的 ok 与 durationMs 字段（此前未展示）。
     */
    data class PeriodStats(
        val range: Int,
        val inTok: Long, val outTok: Long,
        val llmCalls: Int, val toolCalls: Int,
        val failCalls: Int,
        val llmDurationMs: Long,
        val prevTotal: Long,
        val rangeLabel: String,
        val byModel: List<ModelAgg>,
        val byPurpose: List<PurposeAgg>,
        val bySession: List<SessionAgg>,
        val byTool: List<ToolAgg>,
        val approved: Int, val denied: Int, val blocked: Int,
        val days: List<DayBar>
    ) {
        val total: Long get() = inTok + outTok
        val calls: Int get() = llmCalls + toolCalls
        fun successPct(): Int? = if (calls <= 0) null else ((calls - failCalls) * 100 / calls)
        fun avgSeconds(): Double? = if (llmCalls <= 0 || llmDurationMs <= 0) null else llmDurationMs / 1000.0 / llmCalls
        fun tps(): Double? = if (llmDurationMs <= 0) null else outTok / (llmDurationMs / 1000.0)
    }

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
        executor.execute {
            runCatching { appendNow(entry) }.onFailure {
                android.util.Log.w("HaoLedger", "ledger write failed: ${it.message}")
            }
        }
    }

    /** 同步追加（测试用；主流程一律走异步 [add]）。 */
    fun appendNow(entry: Entry) {
        val d = dir ?: return
        synchronized(lock) {
            File(d, fileNameFor(entry.ts)).appendText(HaoJson.json.encodeToString(Entry.serializer(), entry) + "\n")
            // M17（2026-10-10）：写成功后滚动累加"今日合计"缓存。
            // entry 属于今天 → 直接累加，零磁盘；写入的不是今天（补记历史）→ 不动缓存；
            // 缓存日期落后于真实今天（跨天）→ 置空，下次 todayTokens() 懒加载。
            if (todayCacheDate == todayKey(entry.ts)) {
                todayCacheSum += entry.promptTokens.toLong().coerceAtLeast(0) +
                    entry.completionTokens.toLong().coerceAtLeast(0)
            } else if (todayCacheDate != null && todayCacheDate != todayKey()) {
                todayCacheDate = null
                todayCacheSum = 0L
            }
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
        synchronized(lock) {
            d.listFiles()?.forEach { runCatching { it.delete() } }
            // M17：文件删了，今日缓存必须同步置空，否则预算提示会拿着旧累计继续算
            todayCacheDate = null
            todayCacheSum = 0L
        }
    }

    // ---------- 方案B 时段仪表盘 ----------

    private class PAcc {
        var inTok = 0L; var outTok = 0L
        var llmCalls = 0; var toolCalls = 0
        var failCalls = 0
        var llmDurationMs = 0L
        val models = HashMap<String, LongArray>()     // [in,out,count]
        val purposes = HashMap<String, LongArray>()
        val sessions = HashMap<String, LongArray>()
        val tools = HashMap<String, Int>()
        var approved = 0; var denied = 0; var blocked = 0
        val bars = LinkedHashMap<Long, LongArray>()   // bucketKey -> [tokens, llmDurationMs, outTok]
    }

    /** 桶起始毫秒：今日/本周=自然日；本月=自然日（30 格）。 */
    private fun bucketStart(ts: Long, range: Int, dayStart: Long, weekStart: Long, monthStart: Long): Long? {
        val cal = java.util.Calendar.getInstance()
        fun dayFloor(t: Long): Long {
            cal.timeInMillis = t
            cal.set(java.util.Calendar.HOUR_OF_DAY, 0); cal.set(java.util.Calendar.MINUTE, 0)
            cal.set(java.util.Calendar.SECOND, 0); cal.set(java.util.Calendar.MILLISECOND, 0)
            return cal.timeInMillis
        }
        return when (range) {
            0 -> { // 今日按 4 小时分 6 桶：00,04,08,12,16,20
                val d = dayFloor(ts)
                if (d != dayStart) null
                else d + ((ts - d) / (4L * 3600_000L)) * 4L * 3600_000L
            }
            1 -> { val d = dayFloor(ts); if (d >= weekStart) d else null }   // 本周按日 7 桶
            2 -> { val d = dayFloor(ts); if (d >= monthStart) d else null }  // 本月按日 30 桶
            else -> null
        }
    }

    private fun bucketLabel(bucket: Long, range: Int): String = when (range) {
        0 -> "${bucket / 3600_000L % 24 * 4}"
        1, 2 -> {
            val cal = java.util.Calendar.getInstance()
            cal.timeInMillis = bucket
            "${cal.get(java.util.Calendar.DAY_OF_MONTH)}日"
        }
        else -> ""
    }

    /** 遍历本月全部日期桶（用于补齐空桶，让柱状图无数据日也占位）。 */
    private fun expectedBuckets(range: Int, dayStart: Long, weekStart: Long, monthStart: Long): List<Long> {
        val out = mutableListOf<Long>()
        when (range) {
            0 -> for (h in 0 until 24 step 4) out.add(dayStart + h * 3600_000L)
            1 -> {
                var d = weekStart
                val cal = java.util.Calendar.getInstance()
                while (d <= dayStart) { out.add(d); cal.timeInMillis = d; cal.add(java.util.Calendar.DAY_OF_MONTH, 1); d = cal.timeInMillis }
            }
            2 -> {
                var d = monthStart
                val cal = java.util.Calendar.getInstance()
                while (d <= dayStart) { out.add(d); cal.timeInMillis = d; cal.add(java.util.Calendar.DAY_OF_MONTH, 1); d = cal.timeInMillis }
            }
        }
        return out
    }

    /**
     * 方案B 仪表盘聚合：一次读账本，三个时段各出一份 PeriodStats。
     * prevTotal 取上一窗口总 token（今日→昨日 / 本周→上周 / 本月→上月），供环比。
     */
    fun dashboard(): List<PeriodStats> {
        val entries = readAll()
        val cal = java.util.Calendar.getInstance()
        val dayStart = cal.clone().let { c ->
            c as java.util.Calendar
            c.set(java.util.Calendar.HOUR_OF_DAY, 0); c.set(java.util.Calendar.MINUTE, 0)
            c.set(java.util.Calendar.SECOND, 0); c.set(java.util.Calendar.MILLISECOND, 0)
            c.timeInMillis
        }
        val weekStart = cal.clone().let { c ->
            c as java.util.Calendar
            c.set(java.util.Calendar.DAY_OF_WEEK, java.util.Calendar.MONDAY)
            c.set(java.util.Calendar.HOUR_OF_DAY, 0); c.set(java.util.Calendar.MINUTE, 0)
            c.set(java.util.Calendar.SECOND, 0); c.set(java.util.Calendar.MILLISECOND, 0)
            c.timeInMillis
        }
        val monthStart = cal.clone().let { c ->
            c as java.util.Calendar
            c.set(java.util.Calendar.DAY_OF_MONTH, 1)
            c.set(java.util.Calendar.HOUR_OF_DAY, 0); c.set(java.util.Calendar.MINUTE, 0)
            c.set(java.util.Calendar.SECOND, 0); c.set(java.util.Calendar.MILLISECOND, 0)
            c.timeInMillis
        }
        val offsets = longArrayOf(dayStart, weekStart, monthStart)
        val prevStart = longArrayOf(
            dayStart - 24L * 3600_000L,
            weekStart - 7L * 24L * 3600_000L,
            run {
                cal.timeInMillis = monthStart
                cal.add(java.util.Calendar.MONTH, -1)
                cal.timeInMillis
            }
        )
        val accs = Array(3) { PAcc() }
        val prevTotals = LongArray(3)

        for (e in entries) {
            val pin = e.promptTokens.toLong().coerceAtLeast(0)
            val pout = e.completionTokens.toLong().coerceAtLeast(0)
            val total = pin + pout
            for (r in 0..2) {
                if (e.ts >= offsets[r] && e.ts < prevStart[r]) prevTotals[r] += total
            }
            for (r in 0..2) {
                val start = offsets[r]
                if (e.ts < start) continue
                val end = when (r) { 0 -> dayStart + 24L * 3600_000L; 1 -> weekStart + 7L * 24L * 3600_000L; else -> monthStart + 31L * 24L * 3600_000L }
                if (e.ts >= end) continue
                val a = accs[r]
                a.inTok += pin; a.outTok += pout
                when (e.kind) {
                    "llm" -> {
                        a.llmCalls++
                        if (!e.ok) a.failCalls++
                        if (e.durationMs > 0) a.llmDurationMs += e.durationMs
                        val mk = e.model.orEmpty().ifBlank { "unknown" }
                        a.models.getOrPut(mk) { LongArray(3) }.let { it[0] += pin; it[1] += pout; it[2]++ }
                        val pk = e.purpose.orEmpty().ifBlank { "chat" }
                        a.purposes.getOrPut(pk) { LongArray(3) }.let { it[0] += pin; it[1] += pout; it[2]++ }
                        val sk = e.sessionId.orEmpty().ifBlank { "无会话" }
                        a.sessions.getOrPut(sk) { LongArray(3) }.let { it[0] += pin; it[1] += pout; it[2]++ }
                        val bucket = bucketStart(e.ts, r, dayStart, weekStart, monthStart)
                        if (bucket != null) a.bars.getOrPut(bucket) { LongArray(3) }.let {
                            it[0] += total; it[1] += if (e.durationMs > 0) e.durationMs else 0L; it[2] += pout
                        }
                    }
                    "tool" -> {
                        a.toolCalls++
                        if (!e.ok) a.failCalls++
                        val tk = e.tool.orEmpty().ifBlank { "unknown" }
                        a.tools.merge(tk, 1, Int::plus)
                        when (e.policyDecision) {
                            "approved", "direct" -> a.approved++
                            "denied" -> a.denied++
                            "blocked" -> a.blocked++
                        }
                        val sk = e.sessionId.orEmpty().ifBlank { "无会话" }
                        a.sessions.getOrPut(sk) { LongArray(3) }.let { it[2]++ }
                    }
                }
            }
        }

        val rangeLabels = listOf("今日", "本周", "本月")
        return (0..2).map { r ->
            val a = accs[r]
            val expected = expectedBuckets(r, dayStart, weekStart, monthStart)
            var maxTok = 0L
            expected.forEach { b -> maxTok = maxOf(maxTok, a.bars[b]?.get(0) ?: 0L) }
            val days = expected.map { b ->
                val v = a.bars[b]?.get(0) ?: 0L
                DayBar(bucketLabel(b, r), v, peak = v > 0 && v == maxTok)
            }
            PeriodStats(
                range = r,
                inTok = a.inTok, outTok = a.outTok,
                llmCalls = a.llmCalls, toolCalls = a.toolCalls,
                failCalls = a.failCalls,
                llmDurationMs = a.llmDurationMs,
                prevTotal = prevTotals[r],
                rangeLabel = rangeLabels[r],
                byModel = a.models.map { ModelAgg(it.key, it.value[0], it.value[1], it.value[2].toInt()) }
                    .sortedByDescending { it.promptTokens + it.completionTokens },
                byPurpose = a.purposes.map { PurposeAgg(it.key, it.value[0], it.value[1], it.value[2].toInt()) }
                    .sortedByDescending { it.promptTokens + it.completionTokens },
                bySession = a.sessions.map { SessionAgg(it.key, it.value[0], it.value[1], it.value[2].toInt()) }
                    .sortedByDescending { it.promptTokens + it.completionTokens },
                byTool = a.tools.map { ToolAgg(it.key, it.value) }.sortedByDescending { it.calls },
                approved = a.approved, denied = a.denied, blocked = a.blocked,
                days = days
            )
        }
    }

    /**
     * 今日（输入+输出）token 合计，供 5.1 预算判定。
     *
     * ## M17（2026-10-10）：滚动缓存，不再每轮全量解析
     *
     * 原实现 `summarize()` → `readAll()`：每次调用把**所有**月度账本文件
     * `readLines` + 逐行反序列化一遍，只为算一个"今日合计"。而本函数挂在
     * `budgetHint` → 系统提示构建路径上，**每轮请求都跑**——账本随使用线性
     * 增长（单次可达数十 MB 文本 + 数十万次对象分配），全部发生在请求的
     * 关键路径上。
     *
     * 改为与 [appendNow] 同锁的滚动缓存：
     * · 缓存命中当天 → 直接返回内存值，零磁盘；
     * · 未初始化/跨天 → 只解析**当月**文件（比全量小一个量级），一次性填缓存；
     * · [clearAll] 删文件 → 置空缓存。
     * 光按文件 mtime 做缓存是不够的：当天文件每轮都在追加、mtime 每轮都变，
     * 缓存会恒失效——所以必须"写入侧增量累加"，这正是与 appendNow 同锁的原因。
     */
    fun todayTokens(): Long {
        val d = dir ?: return 0L
        val today = todayKey()
        synchronized(lock) {
            if (todayCacheDate == today) return todayCacheSum
            val todayStart = java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
            }.timeInMillis
            val monthFile = File(d, fileNameFor(System.currentTimeMillis()))
            var sum = 0L
            if (monthFile.isFile) {
                runCatching {
                    monthFile.readLines().forEach { line ->
                        if (line.isBlank()) return@forEach
                        runCatching { HaoJson.json.decodeFromString(Entry.serializer(), line) }
                            .getOrNull()?.let { e ->
                                if (e.ts >= todayStart) {
                                    sum += e.promptTokens.toLong().coerceAtLeast(0) +
                                        e.completionTokens.toLong().coerceAtLeast(0)
                                }
                            }
                    }
                }
            }
            todayCacheDate = today
            todayCacheSum = sum
            return sum
        }
    }

    private fun todayKey(nowMs: Long = System.currentTimeMillis()): String =
        SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(nowMs))

    /** 今日合计缓存（日期键 + 金额），由 [appendNow] 增量维护、[todayTokens] 懒加载。 */
    @Volatile private var todayCacheDate: String? = null
    @Volatile private var todayCacheSum: Long = 0L

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
