package com.haoai.pc

import java.io.File
import java.time.Instant
import java.time.ZoneId

/**
 * 主动关心（B8，Octop ProactiveCare 的简化版）：隔 1~3 天、在 9~21 点的随机时刻，
 * 读近 7 天的情绪日记，生成一句 ≤200 字的关心语，落 `memory-care.jsonl` 供
 * 记忆页签展示（v1 不做推送 —— PC→手机的文本推送链路另算一笔，别在这里挖）。
 *
 * 默认关（[flags] 的 proactiveCare，设置=0/1）：它会主动花一次模型调用，
 * 该由用户点头才开。开关一关，下一次 tick 把闹钟归零（重新从明天起算）。
 */
object ProactiveCare {
    @Volatile
    private var nextAt = 0L
    @Volatile
    private var started = false

    fun start() {
        if (started) return
        synchronized(this) {
            if (started) return
            started = true
            Thread {
                while (true) {
                    runCatching { Thread.sleep(6 * 3600_000L); tick() }
                }
            }.apply { isDaemon = true; name = "proactive-care" }.start()
        }
    }

    /** 每 6 小时被线程叫一次；到点才烧调用。 */
    fun tick() {
        runCatching {
            val s = PcSettings.load()
            if (s.flags["proactiveCare"] != true) { nextAt = 0L; return }
            val now = System.currentTimeMillis()
            if (nextAt == 0L) { nextAt = nextSlot(now, 1); return }
            if (now < nextAt) return
            nextAt = nextSlot(now, (1..3).random())   // 每次关心后歇 1~3 天，别变成每日打扰
            val eps = Episodes.recent(7)
            if (eps.isEmpty()) return                 // 没素材不硬造——关心要挂得上真实的事
            val prompt = buildString {
                appendLine("你了解这个用户。近 7 天的日记摘录如下，写一句自然的关心（≤120 字，口语，别用「作为助手」这种腔）。")
                appendLine("要挂得上具体的事（如果日记里有），别泛泛问「最近好吗」。只输出那句话本身。")
                eps.take(8).forEach { appendLine(it) }
            }
            val r = chatClient(PcSettings.load()).chat(listOf(Msg("user", prompt)), emptyList()) { }
            val text = r.text.trim().take(240)
            if (text.length < 8) return
            File(Env.home, "memory-care.jsonl").appendText(
                """{"ts":${System.currentTimeMillis()},"text":${text.replace("\"", "\\\"").replace("\n", " ")}}""" + "\n"
            )
        }.getOrDefault(Unit)
    }

    /** 下一个触发点：[daysAway] 天后的 9~21 点里随机一分钟（在活动时段外不扰）。 */
    private fun nextSlot(now: Long, daysAway: Int): Long {
        val z = ZoneId.systemDefault()
        val d = Instant.ofEpochMilli(now).atZone(z).plusDays(daysAway.toLong()).toLocalDate()
        return d.atTime(9 + (0..12).random(), (0..59).random()).atZone(z).toInstant().toEpochMilli()
    }
}
