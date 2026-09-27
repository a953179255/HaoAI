package com.haoai.pc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File

/**
 * 定时任务：到点自己起一条会话去跑一句话。
 *
 * 为什么 PC 端要有：手机端早就有定时任务，而桌面这台机器才是真正常开着的 ——
 * "每天早上把仓库的报错日志看一遍并写份摘要"这种活，只有常驻的机器能替人干。
 *
 * 存在 `HAOAI_HOME/schedules.json`，与权限规则、技能一样都在状态根里，不进仓库。
 */
data class Schedule(
    val id: String,
    val name: String,
    val prompt: String,
    /** interval = 每 `every` 分钟；daily = 每天 `at` 的 HH:MM。 */
    val kind: String = "interval",
    val every: Int = 60,
    val at: String = "09:00",
    val created: Long = System.currentTimeMillis(),
    var enabled: Boolean = true,
    var lastRun: Long = 0L,
    var lastSid: String = "",
    var lastError: String = ""
) {
    companion object {
        const val MINUTE = 60_000L

        /** 今天（或明天）那个 HH:MM 的时间戳。写法不对就返回 0，表示这条不跑。 */
        fun timeTodayAt(at: String, now: Long): Long {
            val parts = at.split(":")
            val h = parts.getOrNull(0)?.toIntOrNull() ?: return 0L
            val m = parts.getOrNull(1)?.toIntOrNull() ?: return 0L
            if (h !in 0..23 || m !in 0..59) return 0L
            val cal = java.util.Calendar.getInstance()
            cal.timeInMillis = now
            cal.set(java.util.Calendar.HOUR_OF_DAY, h)
            cal.set(java.util.Calendar.MINUTE, m)
            cal.set(java.util.Calendar.SECOND, 0)
            cal.set(java.util.Calendar.MILLISECOND, 0)
            return cal.timeInMillis
        }

        /**
         * 下一次该在什么时候跑（epoch ms；0 = 不会跑）。
         *
         * 关键取舍：**不补跑**。笔记本睡了八小时，醒来时一口气触发八次任务既刷爆网关，
         * 也不是用户要的（他要的是"现在这一次的结果"）。所以：
         * - interval：从上一次（或创建时刻）往后推，睡醒最多补一次；
         * - daily：今天这个点已过且今天还没跑过 → 立刻跑一次；否则等明天。
         */
        fun nextDue(s: Schedule, now: Long): Long {
            if (!s.enabled) return 0L
            return if (s.kind == "daily") {
                val today = timeTodayAt(s.at, now)
                if (today == 0L) return 0L
                val day = 24 * 60 * MINUTE
                // 今天这个点：没过点它就是将来值（dueNow 判 false），过了点它就是过去值（判 true），
                // 所以"补一次"不需要额外分支；只有今天已经跑过才要推到明天。
                if (s.lastRun > 0 && s.lastRun >= today) today + day else today
            } else {
                val every = if (s.every <= 0) 60 else s.every
                val base = if (s.lastRun > 0) s.lastRun else s.created
                base + every * MINUTE
            }
        }

        /** 该不该现在触发。 */
        fun dueNow(s: Schedule, now: Long): Boolean {
            val due = nextDue(s, now)
            return due in 1..now
        }
    }
}

/** `HAOAI_HOME/schedules.json` 的读写。坏文件按"没有任务"处理，别让一个逗号让服务起不来。 */
object Schedules {
    fun file(): File = File(Env.home, "schedules.json")

    fun load(): MutableList<Schedule> = runCatching {
        val root = Json.parseToJsonElement(file().readText())
        val arr = if (root is JsonArray) root
        else root.jsonObject["items"]?.jsonArray ?: JsonArray(emptyList())
        arr.mapNotNull { el ->
            val o = el.jsonObject
            val id = o["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            Schedule(
                id = id,
                name = o["name"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { id },
                prompt = o["prompt"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                kind = o["kind"]?.jsonPrimitive?.contentOrNull ?: "interval",
                every = o["every"]?.jsonPrimitive?.intOrNull ?: 60,
                at = o["at"]?.jsonPrimitive?.contentOrNull ?: "09:00",
                created = o["created"]?.jsonPrimitive?.longOrNull ?: System.currentTimeMillis(),
                enabled = o["enabled"]?.jsonPrimitive?.contentOrNull != "false",
                lastRun = o["lastRun"]?.jsonPrimitive?.longOrNull ?: 0L,
                lastSid = o["lastSid"]?.jsonPrimitive?.contentOrNull ?: "",
                lastError = o["lastError"]?.jsonPrimitive?.contentOrNull ?: ""
            )
        }.toMutableList()
    }.getOrDefault(mutableListOf())

    fun save(list: List<Schedule>) {
        val body = list.joinToString(",", "[", "]") { s ->
            """{"id":${js(s.id)},"name":${js(s.name)},"prompt":${js(s.prompt)},"kind":${js(s.kind)},""" +
                """"every":${s.every},"at":${js(s.at)},"created":${s.created},""" +
                """"enabled":${s.enabled},"lastRun":${s.lastRun},"lastSid":${js(s.lastSid)},""" +
                """"lastError":${js(s.lastError)}}"""
        }
        runCatching {
            file().parentFile?.mkdirs()
            file().writeText(body)
        }
    }

    /** 改一条（按 id 找到就地改，找不到就加上）。返回改完的全量列表，省得调用方再读一次盘。 */
    fun update(item: Schedule): MutableList<Schedule> {
        val list = load()
        val i = list.indexOfFirst { it.id == item.id }
        if (i >= 0) list[i] = item else list += item
        save(list)
        return list
    }

    fun remove(id: String): Boolean {
        val list = load()
        val kept = list.filterNot { it.id == id }
        if (kept.size == list.size) return false
        save(kept)
        return true
    }

    private fun js(s: String): String = "\"" +
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
}

/**
 * 每 5 秒看一遍有没有到点的任务。
 *
 * 为什么用轮询而不是精确定时器：这台机器会睡眠，`Timer` 在睡眠期间不触发、醒来也不会补，
 * 而轮询天然把"睡醒了发现过期"这件事变成一次普通判断（补不补由 [Schedule.nextDue] 定）。
 *
 * `fire` 由服务端注入（起会话、跑任务、把新会话 id 还回来），这一层只负责判断时机与记账 ——
 * 和工具层的 `Gate` 同一个思路：调度与执行分开，两边才都能单独测。
 */
class Scheduler(private val fire: (Schedule) -> Unit) : Thread("haoai-sched") {
    @Volatile
    var stopped = false

    init {
        isDaemon = true
    }

    override fun run() {
        while (!stopped) {
            runCatching { tick(System.currentTimeMillis()) }
            try { sleep(5_000) } catch (e: InterruptedException) { return }
        }
    }

    /**
     * 跑一轮检查，返回这一轮触发了几条。测试直接调它，不用等线程。
     *
     * 先判时机再交给 `fire`，**记账（lastRun / lastSid / lastError）由 fire 那边做** ——
     * 它才知道新会话开成了没有。这里只兜住异常，免得一条坏任务把调度线程弄死。
     */
    fun tick(now: Long): Int {
        var fired = 0
        for (s in Schedules.load()) {
            if (!Schedule.dueNow(s, now)) continue
            fired++
            runCatching { fire(s) }.onFailure { e ->
                s.lastError = e.message ?: e.javaClass.simpleName
                Schedules.update(s)
            }
        }
        return fired
    }
}
