package com.haoai.agent.platform

/**
 * B10 第二步：两端共写一份 MEMORY.md 时，手机端的「充电自动整理」默认让位。
 *
 * 判据：闲置闹钟触发（梦醒）时看文件 mtime —— 若它在**刚等过的这段闲置窗口**里被改过，
 * 说明这轮等待期间有别人（通常是 PC 端在跑它自己的整理/写记忆）动过这份文件，
 * 本轮整理跳过并留一行原因。让位是安全方向：跳过一轮最多晚一夜；而两边各按自己的
 * 内存副本重写整份文件，会把对方刚写的内容覆盖掉（这正是 B10 要防的那种坏）。
 *
 * 为什么不用 config-bridge.log 做信号：配置桥只同步 haoai.config.json，不带 MEMORY.md
 * （WorkspaceDocs.syncAll 特意不碰它，覆盖会丢数据），所以 mtime 才是这份文件的直接信号。
 * 手机自己写盘也会刷新 mtime —— 但自动整理只在「充电 + 灭屏闲置 N 分钟 + 00:00–07:00」
 * 之后触发，此时把窗口内的改动让给对方是保守且无害的（手动触发不受此限）。
 */
object DreamYield {

    /**
     * 闲置期内 MEMORY.md 被改过吗（= PC 端在写，本轮该让位）。
     *
     * @param memoryMtime MEMORY.md 的 lastModified；0 或负数 = 文件不存在/读不到 → 不让位
     * @param now 触发时刻（毫秒）
     * @param idleMinutes 梦醒前等过的闲置分钟数（与 DreamTriggerMonitor 的闹钟同一来源）
     */
    fun pcWroteDuringIdle(memoryMtime: Long, now: Long, idleMinutes: Int): Boolean {
        if (memoryMtime <= 0L) return false
        val windowMs = idleMinutes.coerceIn(1, 240) * 60_000L
        // delta < 窗口 = 这次等待期间改的；delta 为负（mtime 在未来，时钟漂移）也算「刚写过」——
        // 让位的代价只是晚一夜，覆盖对方写入的代价是丢记忆，所以判据取保守方向。
        return now - memoryMtime < windowMs
    }
}
