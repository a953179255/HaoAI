package com.haoai.pc

import java.util.Calendar

/**
 * 一句话排期（`SchedulePlan`）：把"每天早上九点""每 30 分钟""明天下午 3 点半""每周一三五 8 点"
 * 这种中文写成 [At]（Schedule 的时间字段 + 一句给人看的复述）。
 *
 * 为什么用规则而不是让模型解析：这条链路的输出直接决定"什么时候真的会起一个任务、
 * 会不会花用户的钱"。模型解析错一个"下午"就是差 12 小时，而且没人会去核对；
 * 规则解析错，测试能当场抓到，并且**复述句**（echo）会显示在界面上让人确认。
 * 参考实现里 ZCODE 的定时任务也是"表单 + 一句人话预览"，不是自由文本直接入库。
 *
 * 解析不出来就返回一句人话说明哪里没看懂，绝不猜一个默认时间 ——
 * 猜错的任务会在人睡着的时候跑，比不跑更糟。
 */
object SchedulePlan {
    /** kind/every/at/days/runAt 与 Schedule 的字段一一对应；echo 是给人看的复述。 */
    data class At(
        val kind: String,
        val every: Int,
        val at: String,
        val days: String,
        val runAt: Long,
        val echo: String
    )

    private const val MINUTE = 60_000L

    /** 周一 = 0 …… 周日 = 6（与 Schedule.days 的写法一致，人读的"周一"就是这里的 0）。 */
    private val WEEK = mapOf(
        '一' to 0, '二' to 1, '三' to 2, '四' to 3, '五' to 4, '六' to 5, '日' to 6, '天' to 6
    )

    private val DIGITS = mapOf(
        '零' to 0, '〇' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4,
        '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9
    )

    /** 阿拉伯数字与 1~99 的中文数字。解析不出来返回 null。 */
    fun num(s: String): Int? {
        val t = s.trim()
        if (t.isEmpty()) return null
        t.toIntOrNull()?.let { return it }
        val i = t.indexOf('十')
        if (i >= 0) {
            if (t.length == 1) return 10
            val tens = if (i == 0) 1 else (DIGITS[t[0]] ?: return null)
            val rest = t.substring(i + 1)
            val ones = if (rest.isEmpty()) 0 else (DIGITS[rest[0]] ?: return null)
            return tens * 10 + ones
        }
        if (t.length == 1) return DIGITS[t[0]]
        // "二十三" 之外的多字写法（如 "9点30"）不在这里管
        var v = 0
        for (c in t) v = v * 10 + (DIGITS[c] ?: return null)
        return v
    }

    /**
     * 从一段话里把所有时间点都挑出来（升序、去重），返回 "HH:MM,HH:MM" 与"有没有出现过下午/晚上"。
     *
     * 支持：`9:30`、`07:05`、`九点`、`十点半`、`8点15分`、`下午3点`、`晚上 9 时`。
     */
    private fun times(raw: String): Pair<String, Boolean> {
        val s = raw.replace("：", ":")
        val pm = s.contains("下午") || s.contains("晚上") || s.contains("傍晚") || s.contains("半夜") ||
            s.contains("深夜") || s.contains("今晚")
        val found = sortedSetOf<String>()
        Regex("""(\d{1,2}):(\d{2})""").findAll(s).forEach { m ->
            val h = m.groupValues[1].toIntOrNull() ?: return@forEach
            val mi = m.groupValues[2].toIntOrNull() ?: return@forEach
            add(found, h, mi, pm)
        }
        // 中文/阿拉伯的「N点M分 / N点半 / N时」
        val cn = """([0-9]{1,2}|[一二两三四五六七八九十]{1,3})\s*[点时]\s*(半|[0-9]{1,2}分?|一刻|三刻|)"""
        Regex(cn).findAll(s).forEach { m ->
            val g = m.groups
            val h = num(g[1]!!.value) ?: return@forEach
            val min = when (g[2]!!.value.trim()) {
                "" -> 0
                "半" -> 30
                "一刻" -> 15
                "三刻" -> 45
                else -> (g[2]!!.value.replace("分", "").trim().toIntOrNull() ?: return@forEach)
            }
            add(found, h, min, pm)
        }
        return (if (found.isEmpty()) "" else found.joinToString(",")) to pm
    }

    private fun add(into: MutableSet<String>, h: Int, m: Int, pm: Boolean) {
        if (m > 59) return
        var hh = h
        // "下午3点" 写成 15:00；已经 >12 的写法照原样，别把 22 变成 10。
        // "晚上12点/半夜12点" 是人说的零点（00:00），不是中午。
        if (pm && hh == 12) hh = 0 else if (pm && hh in 1..11) hh += 12
        if (hh > 23) return
        into.add("%02d:%02d".format(hh, m))
    }

    /** 从一段话里挑出"每周几"，返回 "0,4" 这种（周一=0）。没有则空串。 */
    private fun days(raw: String): String {
        val s = raw.replace("星期", "周")
        if (s.contains("周末") && !Regex("""周[一二三四五六日天]""").containsMatchIn(s)) return "5,6"
        val out = sortedSetOf<Int>()
        // "每周一三五" 只有第一个带"周"，所以抓到 周X 之后把紧跟的裸字也吃掉
        Regex("""周([一二三四五六日天])([一二三四五六日天]*)""").findAll(s).forEach { m ->
            for (c in m.groupValues[1] + m.groupValues[2]) {
                val d = WEEK[c] ?: continue
                out.add(d)
            }
        }
        return out.joinToString(",")
    }

    /**
     * 解析。返回 (结果, "") 或 (null, 一句"哪里没看懂")。
     *
     * `now` 用来算"半小时后""明天九点"这类相对时间，测试里传固定值，不依赖真实时钟。
     */
    fun parse(rawIn: String, now: Long): Pair<At?, String> {
        val raw = rawIn.trim()
        if (raw.isEmpty()) return null to "先写一句什么时候，比如「每天早上九点」"
        val t = raw.replace(" ", "")

        // 1) 每 N 分钟 / 每 N 小时 / 每半小时
        // 1) 每 N 分钟 / 每 N 小时 / 每半小时
        //    「半小时」整体当单位：数字位留空，否则"半"会被数字组吃掉
        Regex("""每隔?(\d{1,4}|[一二两三四五六七八九十]{1,3})?\s*(半小时|个小时|分钟|小时|分)(?:钟)?""").find(t)?.let { m ->
            val n = m.groupValues[1]
            val every = when (m.groupValues[2]) {
                "半小时" -> 30
                "个小时", "小时" -> (num(n) ?: return@let) * 60
                else -> num(n) ?: return@let
            }
            if (every < 1) return null to "「$raw」里的间隔太小了，至少 1 分钟"
            return At("interval", every, "", "", 0L, "每 $every 分钟") to ""
        }

        // 2) N 分钟/小时之后 → 一次性
        Regex("""(\d{1,4}|[一二两三四五六七八九十]{1,3}|半)\s*(分钟|分|小时|个小时)\s*(之)?后""").find(t)?.let { m ->
            val g = m.groupValues[1]
            val mins = if (g == "半") 30 else {
                val n = num(g) ?: return@let
                if (m.groupValues[2].contains("小时")) n * 60 else n
            }
            val whenMs = now + mins * MINUTE
            return At("once", 60, "", "", atOf(whenMs), "约 ${mins} 分钟后（" + stamp(whenMs) + "）") to ""
        }

        val (atList, _) = times(t)
        val wk = days(t)
        val daily = t.contains("每天") || t.contains("每日") || t.contains("天天")

        // 3) 每周几天（"每周" 单独出现按"周一到周五"？不 —— 没写清就不猜）
        if (wk.isNotEmpty() && (t.contains("每周") || t.contains("每星期") || t.contains("周末"))) {
            if (atList.isEmpty()) return null to "「$raw」没说几点。写成「每周五 18:00」这样"
            return At("weekly", 60, atList, wk, 0L, "每" + dayText(wk) + " " + atList) to ""
        }

        // 4) 每天几点（可多个时刻）
        if (daily) {
            if (atList.isEmpty()) return null to "「$raw」没说几点。写成「每天早上 7:30」这样"
            return At("daily", 60, atList, "", 0L, "每天 " + atList) to ""
        }

        // 5) 明天 / 后天 / 今天 / 今晚 + 时间点 → 一次性
        val dayOffset = when {
            t.contains("后天") -> 2
            t.contains("明天") || t.contains("明日") -> 1
            t.contains("今晚") || t.contains("今天") || t.contains("今日") -> 0
            else -> -1
        }
        if (dayOffset >= 0) {
            if (atList.isEmpty()) return null to "「$raw」没说几点。写成「明天 9:00」或「今晚十点半」这样"
            val first = atList.split(",").first()
            val cal = Calendar.getInstance().apply { timeInMillis = now }
            cal.add(Calendar.DAY_OF_YEAR, dayOffset)
            val ms = applyTime(cal, first)
            if (ms <= now && dayOffset == 0) return null to "「$raw」这个点今天已经过了，说明天或写具体日期"
            return At("once", 60, "", "", atOf(ms), "一次性 " + stamp(ms)) to ""
        }

        // 6) 只给了时间点：今天剩下的时间里跑一次
        if (atList.isNotEmpty()) {
            val cal = Calendar.getInstance().apply { timeInMillis = now }
            val ms = applyTime(cal, atList.split(",").first())
            val whenMs = if (ms <= now) ms + 24 * 60 * MINUTE else ms
            return At("once", 60, "", "", atOf(whenMs), "一次性 " + stamp(whenMs)) to ""
        }
        return null to "没看懂「$raw」。会说「每 30 分钟」「每天早上九点」「每周一三五 8 点」「明天下午 3 点半」「半小时后」"
    }

    fun dayText(daysCsv: String): String {
        val names = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
        val list = daysCsv.split(",").mapNotNull { it.toIntOrNull() }
        return if (list == (0..4).toList()) "工作日" else list.joinToString("、") { names.getOrElse(it) { "?" } }
    }

    private fun applyTime(cal: Calendar, hhmm: String): Long {
        val parts = hhmm.split(":")
        cal.set(Calendar.HOUR_OF_DAY, parts.getOrNull(0)?.toIntOrNull() ?: 9)
        cal.set(Calendar.MINUTE, parts.getOrNull(1)?.toIntOrNull() ?: 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    /** 对齐到分钟：调度器按分钟判断，留秒数会让复述和实际触发差一眼。 */
    private fun atOf(ms: Long): Long = ms - Math.floorMod(ms, MINUTE)

    /** 把一条已存的排期用人话复述一遍（界面上"什么时候跑"就显示这个）。 */
    fun describe(kind: String, every: Int, at: String, days: String, runAt: Long): String = when (kind) {
        "interval" -> "每 " + (if (every <= 0) 60 else every) + " 分钟"
        "daily" -> "每天 " + at
        "weekly" -> "每" + dayText(days) + " " + at
        "once" -> if (runAt > 0L) "一次性 " + stamp(runAt) else "一次性（没定时刻）"
        else -> kind + " " + at
    }

    private fun stamp(ms: Long): String {
        val cal = Calendar.getInstance().apply { timeInMillis = ms }
        return "%04d-%02d-%02d %02d:%02d".format(
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH),
            cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE)
        )
    }
}
