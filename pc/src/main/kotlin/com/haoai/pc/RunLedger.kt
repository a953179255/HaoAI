package com.haoai.pc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date

/**
 * 运行历史：每跑完一轮（一次用户输入 = 若干次模型往返）往 `HAOAI_HOME/runs.jsonl` 追一行。
 *
 * 为什么单独一份而不是从会话文件里现算：会话只有"最后更新时间"和一个累计总数 ——
 * 定时任务跑完就把上一条的时间盖掉了，"昨晚三点那次到底成没成、跑了几轮、说了什么"
 * 全查不出来。自动化不敢开，缺的就是这块可回看的东西。
 *
 * 与 [UsageLedger] 的分工：那份按**模型回合**记 token（算钱用），这份按**整次运行**记
 * 结果与耗时（回看与重跑用）。一次运行通常有几十个回合，两边合不起来也不该合。
 *
 * 追加式 jsonl：读时不必整份解析，写时不必整份重写，坏行丢掉即可。
 */
object RunLedger {

    data class Row(
        val t: Long, val sid: String, val title: String, val goal: String,
        val trigger: String, val turns: Int, val ms: Long, val stopped: Boolean, val out: String
    ) {
        val at: String get() =
            if (t <= 0L) "?" else SimpleDateFormat("MM-dd HH:mm:ss").format(Date(t))
        val dur: String get() = if (ms < 1000) ms.toString() + "ms" else "%.1fs".format(ms / 1000.0)
        /** 结果那一列给人看的话：被停 ≠ 失败，要说清是哪一种。 */
        val verdict: String get() = when {
            stopped -> "被停止（跑了 " + turns + " 轮）"
            turns == 0 -> "没跑起来"
            else -> "跑了 " + turns + " 轮 · " + dur
        }
    }

    /** 最多留多少条：一天几十次运行，500 条约等于两周的流水，文件不超过几百 KB。 */
    const val KEEP = 500

    fun file(): File = File(Env.home, "runs.jsonl")

    fun add(sid: String, title: String, goal: String, trigger: String,
            turns: Int, ms: Long, stopped: Boolean, out: String) {
        runCatching {
            val f = file()
            f.parentFile?.mkdirs()
            val line = buildJsonObject {
                put("t", System.currentTimeMillis())
                put("sid", sid)
                put("title", title)
                put("goal", goal.take(200))
                put("trigger", trigger)
                put("turns", turns)
                put("ms", ms)
                put("stopped", stopped)
                put("out", out.replace('\n', ' ').take(200))
            }.toString()
            f.appendText(line + System.lineSeparator())
            prune(f)
        }
    }

    /** 倒序（最近的在前）。坏行跳过而不是整份报错。 */
    fun rows(limit: Int = 60): List<Row> {
        val f = file()
        if (!f.isFile) return emptyList()
        val lines = runCatching { f.readLines() }.getOrDefault(emptyList())
        val out = ArrayList<Row>()
        for (i in lines.lastIndex downTo 0) {
            if (out.size >= limit) break
            val r = parse(lines[i]) ?: continue
            out += r
        }
        return out
    }

    private fun parse(line: String): Row? = runCatching {
        val o: JsonObject = Json.parseToJsonElement(line).jsonObject
        Row(
            t = o["t"]?.jsonPrimitive?.longOrNull ?: 0L,
            sid = o["sid"]?.jsonPrimitive?.contentOrNull ?: "",
            title = o["title"]?.jsonPrimitive?.contentOrNull ?: "",
            goal = o["goal"]?.jsonPrimitive?.contentOrNull ?: "",
            trigger = o["trigger"]?.jsonPrimitive?.contentOrNull ?: "手动",
            turns = o["turns"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
            ms = o["ms"]?.jsonPrimitive?.longOrNull ?: 0L,
            stopped = o["stopped"]?.jsonPrimitive?.contentOrNull == "true",
            out = o["out"]?.jsonPrimitive?.contentOrNull ?: ""
        )
    }.getOrNull()

    private fun prune(f: File) {
        val lines = runCatching { f.readLines() }.getOrDefault(emptyList())
        if (lines.size <= KEEP) return
        runCatching { f.writeText(lines.takeLast(KEEP).joinToString(System.lineSeparator()) + System.lineSeparator()) }
    }

    /** 给 `/api/runs` 的那份 JSON（最近的在前）。 */
    fun json(limit: Int = 60): String {
        val rs = rows(limit)
        val items = rs.joinToString(",") { r ->
            buildJsonObject {
                put("t", r.t)
                put("at", r.at)
                put("sid", r.sid)
                put("title", r.title)
                put("goal", r.goal)
                put("trigger", r.trigger)
                put("turns", r.turns)
                put("ms", r.ms)
                put("dur", r.dur)
                put("stopped", r.stopped)
                put("verdict", r.verdict)
                put("out", r.out)
            }.toString()
        }
        return """{"ok":true,"count":""" + rs.size + ",\"total\":" + countAll() +
            ",\"items\":[" + items + "]}"
    }

    private fun countAll(): Int = runCatching {
        (file().takeIf { it.isFile }?.readLines() ?: emptyList()).count { l -> parse(l) != null }
    }.getOrDefault(0)
}
