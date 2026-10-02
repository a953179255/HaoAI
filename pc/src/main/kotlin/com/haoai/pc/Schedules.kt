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
import java.util.Calendar

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
    /**
     * interval = 每 `every` 分钟；daily = 每天 `at`（可以 "07:30,21:30" 多个时刻）；
     * weekly = 每周 `days` 的 `at`；once = 到 `runAt` 这个时刻跑一次。
     */
    val kind: String = "interval",
    val every: Int = 60,
    val at: String = "09:00",
    /** 周一 = 0 …… 周日 = 6，逗号分隔（weekly 才用）。 */
    val days: String = "",
    /** 一次性任务的时刻（epoch ms；once 才用）。 */
    val runAt: Long = 0L,
    /** 非空 = 到点跑这条任务链（几句按顺序跑在同一会话里），`prompt` 就不再看。 */
    val flow: String = "",
    /**
     * 非空 = 到点用**这张专家卡**起那条会话（人设、模型、目录、档位都跟着卡走）。
     *
     * 为什么要有：Octop 的 cron_jobs 第一列就是 `agent_id` —— "每天早上让**运维那个专家**
     * 看一遍报错日志"和"让通用助手看一遍"是两个东西。没有这一列，定时任务只能跑裸句子：
     * 用的是全局默认模型、全局工作区、全局档位，而用户挑好的那套配置只在手动开会话时生效。
     * 卡被删了**不静默降级**成"没有角色"，见 [scheduleExpert]。
     */
    val preset: String = "",
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
         * - daily / weekly：取"上一次跑过之后"的第一个槽位 —— 睡过头就只补那一次；
         *   新建的那条从**创建时刻之后**第一个槽位算起，所以下午两点建"每天 09:00"
         *   不会当场跑一遍（旧版会：那是把"补跑"和"刚建好"混成了一件事）。
         * - once：过点太久（>6 小时）就当错过了，不再半夜补跑一条人已经忘了的任务。
         */
        fun nextDue(s: Schedule, now: Long): Long {
            if (!s.enabled) return 0L
            return when (s.kind) {
                "once" -> if (s.runAt > 0L && s.lastRun < s.runAt) s.runAt else 0L
                "interval" -> {
                    val every = if (s.every <= 0) 60 else s.every
                    val base = if (s.lastRun > 0) s.lastRun else s.created
                    base + every * MINUTE
                }
                else -> nextSlot(s, if (s.lastRun > 0) s.lastRun else s.created)
            }
        }

        /** 从 `from` 之后（不含）第一个该跑的时刻；找不到回 0。 */
        fun nextSlot(s: Schedule, from: Long): Long {
            val times = s.at.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            if (times.isEmpty()) return 0L
            val wantDays = when (s.kind) {
                "weekly" -> s.days.split(",").mapNotNull { it.trim().toIntOrNull() }.filter { it in 0..6 }.toSet()
                else -> (0..6).toSet()
            }
            if (wantDays.isEmpty()) return 0L
            val day0 = startOfDay(from)
            var best = 0L
            // 往后找 8 天足够覆盖"每周某天 + 跨周"，再多就是配置错了
            for (off in 0..8) {
                val day = day0 + off * DAY
                if (((Calendar.getInstance().apply { timeInMillis = day }
                        .get(Calendar.DAY_OF_WEEK) + 5) % 7) !in wantDays) continue
                for (t in times) {
                    val ms = atOn(day, t)
                    if (ms > from && (best == 0L || ms < best)) best = ms
                }
                if (best != 0L) return best
            }
            return 0L
        }

        private const val DAY = 24 * 60 * MINUTE

        private fun startOfDay(ms: Long): Long {
            val c = Calendar.getInstance().apply { timeInMillis = ms }
            c.set(Calendar.HOUR_OF_DAY, 0)
            c.set(Calendar.MINUTE, 0)
            c.set(Calendar.SECOND, 0)
            c.set(Calendar.MILLISECOND, 0)
            return c.timeInMillis
        }

        /** 某天 HH:MM 的时间戳；写法不对回 0（表示这条不跑，而不是偷偷用默认值）。 */
        fun atOn(dayStart: Long, hhmm: String): Long {
            val parts = hhmm.split(":")
            val h = parts.getOrNull(0)?.toIntOrNull() ?: return 0L
            val m = parts.getOrNull(1)?.toIntOrNull() ?: return 0L
            if (h !in 0..23 || m !in 0..59) return 0L
            return dayStart + (h * 60 + m) * MINUTE
        }

        /** 该不该现在触发。 */
        fun dueNow(s: Schedule, now: Long): Boolean {
            val due = nextDue(s, now)
            if (due !in 1..now) return false
            // 一次性任务过点太久就别补了：人早忘了，半夜跑一条只会白花 token
            if (s.kind == "once" && now - due > 6 * 60 * MINUTE) return false
            return true
        }

    }
}

/**
 * 定时任务指定的那张专家卡还在不在。**单独一个纯函数**是因为这条判断决定"跑不跑"：
 * 卡被删了要**明确失败并记在 lastError 上**，绝不能静默降级成"没有角色"继续跑 ——
 * 那样跑出来的是全局默认模型在全局工作区里执行一句话，用户以为跑的是"运维专家"，
 * 而它可能正在往另一个目录里写文件。定时任务是无人值守的，这种偏差没人会当场发现。
 */
internal fun scheduleExpert(s: Schedule): Pair<Preset?, String?> {
    if (s.preset.isBlank()) return null to null
    val p = Presets.find(s.preset)
        ?: return null to "这张专家卡不在了（${s.preset}），任务没跑：去「专家」页补一张，或改掉这条任务的指定"
    return p to null
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
                days = o["days"]?.jsonPrimitive?.contentOrNull ?: "",
                runAt = o["runAt"]?.jsonPrimitive?.longOrNull ?: 0L,
                flow = o["flow"]?.jsonPrimitive?.contentOrNull ?: "",
                preset = o["preset"]?.jsonPrimitive?.contentOrNull ?: "",
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
                """"every":${s.every},"at":${js(s.at)},"days":${js(s.days)},"runAt":${s.runAt},""" +
                """"flow":${js(s.flow)},"preset":${js(s.preset)},"created":${s.created},""" +
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

    // 注意：这是 Thread.run（调度线程的主循环），不是工具的 suspend run ——
    // B15 批量给工具加 suspend 时这里被正则误伤过一次，改回来。
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
