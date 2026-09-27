package com.haoai.pc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date

/**
 * 用量账本：**每次模型回合**往 `HAOAI_HOME/usage.jsonl` 追加一行。
 *
 * 为什么不是"从会话文件里现算"（之前界面上那行「今天 / 近 7 天 / 全部」就是现算的）：
 * 会话只有一个累计总数和一个 `updated` 时间 —— 用 `updated` 判"今天"，
 * 于是昨天跑了三万 token、今天动了一下标题，那三万就全算成今天的了。
 * 按回合落账之后，"哪天花的、哪个模型花的、这次成没成"才是量出来的而不是猜的。
 *
 * 追加式 jsonl 而不是一个 JSON 数组：读的时候不用整份解析，写的时候不用整份重写，
 * 中途断电最坏只坏最后一行 —— 而坏行会被 `rows()` 丢掉，不会把整个看板变成 500。
 */
object UsageLedger {

    data class Row(
        val t: Long, val model: String, val sid: String,
        val prompt: Int, val completion: Int, val ms: Long, val ok: Boolean
    )

    fun file(): File = File(Env.home, "usage.jsonl")

    fun add(model: String, sid: String, prompt: Int, completion: Int, ms: Long, ok: Boolean) {
        val line = buildJsonObject {
            put("t", System.currentTimeMillis())
            put("model", model)
            put("sid", sid)
            put("prompt", prompt)
            put("completion", completion)
            put("ms", ms)
            put("ok", ok)
        }.toString()
        runCatching {
            file().parentFile?.mkdirs()
            file().appendText(line + "\n")
        }
    }

    private fun int(o: JsonObject, k: String): Int =
        o[k]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0

    private fun long(o: JsonObject, k: String): Long =
        o[k]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L

    fun rows(): List<Row> = runCatching {
        file().readLines().mapNotNull { l ->
            if (l.isBlank()) return@mapNotNull null
            runCatching {
                val o = Json.parseToJsonElement(l).jsonObject
                Row(
                    t = long(o, "t"),
                    model = o["model"]?.jsonPrimitive?.contentOrNull ?: "（未知）",
                    sid = o["sid"]?.jsonPrimitive?.contentOrNull ?: "",
                    prompt = int(o, "prompt"),
                    completion = int(o, "completion"),
                    ms = long(o, "ms"),
                    ok = o["ok"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: true
                )
            }.getOrNull()        // 半行/坏行直接丢：看板不该因为一次断电就整块打不开
        }
    }.getOrDefault(emptyList())

    /** 本地日期的柱状标签（"天"是人看钟表的那个数，不是 UTC）。 */
    private fun dayOf(t: Long): String = DAY.get().format(Date(t))
    private fun dayOffset(days: Int): String =
        DAY.get().format(Date(System.currentTimeMillis() - days * 86400_000L))

    private val DAY = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat = SimpleDateFormat("yyyy-MM-dd")
    }

    private fun agg(list: List<Row>): String =
        """{"n":${list.size},"prompt":${list.sumOf { it.prompt }},""" +
            """"completion":${list.sumOf { it.completion }},"ms":${list.sumOf { it.ms }}}"""

    /**
     * 一次算齐界面上用量页要的所有数：今天 / 近 7 天 / 全部、按模型分行、
     * 近 14 天的柱子、成功率与平均速度。
     *
     * 柱子按"补全 token"高度算 —— 人想知道的是"哪天真正在干活"，
     * 而输入 token 会被一次长上下文拉爆，看不出差别。
     */
    fun report(): String {
        val all = rows()
        val now = System.currentTimeMillis()
        val today = dayOf(now)
        val weekStart = dayOffset(6)
        val byDay = all.groupBy { dayOf(it.t) }
        val days = (13 downTo 0).map { i ->
            val d = dayOffset(i)
            val rs = byDay[d].orEmpty()
            """{"d":"$d","prompt":${rs.sumOf { it.prompt }},""" +
                """"completion":${rs.sumOf { it.completion }},"n":${rs.size}}"""
        }
        val models = all.groupBy { it.model }.entries
            .sortedByDescending { it.value.sumOf { r -> r.prompt + r.completion } }
            .map { (m, rs) ->
                """{"model":${quote(m)},"n":${rs.size},"prompt":${rs.sumOf { it.prompt }},""" +
                    """"completion":${rs.sumOf { it.completion }},"ms":${rs.sumOf { it.ms }}}"""
            }
        val okN = all.count { it.ok }
        val timed = all.filter { it.ms > 0 && it.completion > 0 }
        val tps = if (timed.isEmpty()) 0.0
        else timed.sumOf { it.completion }.toDouble() / (timed.sumOf { it.ms } / 1000.0)
        return """{"today":${agg(all.filter { dayOf(it.t) == today })},""" +
            """"week":${agg(all.filter { dayOf(it.t) >= weekStart })},""" +
            """"all":${agg(all)},""" +
            """"models":[${models.joinToString(",")}],""" +
            """"days":[${days.joinToString(",")}],""" +
            """"okRate":${if (all.isEmpty()) 0.0 else okN.toDouble() / all.size},""" +
            """"tps":${"%.1f".format(tps)},""" +
            """"rows":${all.size},"backfilled":${file().exists() && all.isNotEmpty()}}"""
    }

    // 模型名可能是本地 gguf 的**完整 Windows 路径**（一串反斜杠），不转义就是一份非法 JSON
    private fun quote(s: String): String = "\"" +
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
}
