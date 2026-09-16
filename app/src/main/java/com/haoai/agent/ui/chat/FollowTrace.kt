package com.haoai.agent.ui.chat

/**
 * 跟随判定的**内存**环形缓冲（诊断用）。
 *
 * 为什么是内存而不是直接落盘：2026-09-16 上一版探针直接在滚动路径里写文件，
 * 跳变频繁时变成**主线程 IO**，把"快速滚动卡顿"放大了（用户实测）。
 * 现在只在内存里记最近若干条，需要时由 debug 深链一次性导出：
 *
 *   adb shell am start -d "haoai://debug/followdump" com.haoai.agent
 *   adb shell run-as com.haoai.agent cat files/follow-probe.txt
 *
 * （魅族 ROM 屏蔽 app logcat，只能走"写文件 + run-as 读"这条路，但写入时机要可控。）
 */
object FollowTrace {
    private const val CAP = 240
    private val buf = ArrayDeque<String>(CAP)
    private var startedAt = 0L

    @Synchronized
    fun add(line: String) {
        if (startedAt == 0L) startedAt = System.currentTimeMillis()
        if (buf.size >= CAP) buf.removeFirst()
        buf.addLast("+${System.currentTimeMillis() - startedAt}ms $line")
    }

    @Synchronized
    fun dump(): String {
        if (buf.isEmpty()) return "(空：本次进程内没有记录到跟随判定)"
        return buildString {
            append("跟随判定内存轨迹（共 ").append(buf.size).append(" 条，最新在最后）\n")
            append("字段：busy=连续滚动帧数 atBottom=本条是否贴底 prev=上一帧是否贴底 ")
            append("delta=末项底边超出内容末端的像素 pinned=本次是否发生贴底滚动 follow=是否允许贴底\n")
            append("─".repeat(72)).append('\n')
            buf.forEach { append(it).append('\n') }
        }
    }

    @Synchronized
    fun clear() {
        buf.clear()
        startedAt = 0L
    }
}
