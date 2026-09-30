package com.haoai.agent.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 诊断用**内存**轨迹缓冲（跟随判定 + 慢帧），需要时经 debug 深链一次导出：
 *
 *   adb shell am start -d "haoai://debug/followdump" com.haoai.agent
 *   adb shell run-as com.haoai.agent cat files/follow-probe.txt
 *
 * 为什么是内存：滚动路径上做主线程 IO 会自己制造卡顿（2026-09-16 踩过）。
 * 两个独立缓冲：跟随判定帧（高频，环形 240 条）+ 慢帧记录（低频，环形 60 条）。
 *
 * [enabled] 探针总开关（发热治理 P0-2，默认关）：跟随轨迹字符串是**每渲染帧**拼一条、
 * 慢帧探针更是自续 120Hz Choreographer 回调 + 每帧两次 Debug.getRuntimeStat——
 * 这套东西只为排查滚动卡顿服务，平时开着就是纯发热。followdump 深链打开它。
 */
object FollowTrace {
    var enabled by mutableStateOf(false)
        private set

    internal fun enable() {
        enabled = true
    }

    private const val CAP = 240
    private const val SLOW_CAP = 60
    private val buf = ArrayDeque<String>(CAP)
    private val slow = ArrayDeque<String>(SLOW_CAP)
    private var startedAt = 0L

    private fun stamp(): String {
        if (startedAt == 0L) startedAt = System.currentTimeMillis()
        return "+${System.currentTimeMillis() - startedAt}ms "
    }

    /** 跟随判定每帧一行（高频）。 */
    @Synchronized
    fun add(line: String) {
        if (buf.size >= CAP) buf.removeFirst()
        buf.addLast(stamp() + line)
    }

    /** 慢帧记录（仅 >=50ms 才写，低频）。 */
    @Synchronized
    fun addSlow(line: String) {
        if (slow.size >= SLOW_CAP) slow.removeFirst()
        slow.addLast(stamp() + line)
    }

    @Synchronized
    fun dump(): String = buildString {
        append("=== 慢帧记录（>=50ms，共 ").append(slow.size).append(" 条）===\n")
        append("字段：帧间隔 / 本帧可见行区间 / 上一帧可见行区间 / 总行数 / 各可见行(索引:高度px)\n")
        append("判读：卡顿时区间相对上一帧扩张的那一侧就是新进入的行；高度异常大的那行是首要嫌疑。\n")
        if (slow.isEmpty()) append("(无：本次进程内没有 >=50ms 的帧)\n")
        else slow.forEach { append(it).append('\n') }
        append('\n')
        append("=== 跟随判定轨迹（共 ").append(buf.size).append(" 条，最新在最后）===\n")
        append("字段：busy=连续滚动帧数 atBottom=本条是否贴底 prev=上一帧是否贴底 ")
        append("delta=末项底边超出内容末端的像素 follow=是否允许贴底 pinned=本次是否发生贴底滚动 ")
        append("scrolled=本轮用户是否滚过 lastIdx=末项索引/末索引 itemSize=末项高度 visible=可见行数\n")
        if (buf.isEmpty()) append("(空)\n") else buf.forEach { append(it).append('\n') }
    }

    @Synchronized
    fun clear() {
        buf.clear()
        slow.clear()
        startedAt = 0L
    }
}
