package com.haoai.agent.ui.chat

import com.haoai.agent.platform.FileEntry

/**
 * 工作区共享壳的纯逻辑（进 JVM 单测；UI 壳只是消费）。
 */

/** 工作区统计：文件数 / 总字节 / 今日（naturalDay 内）改动数——设置·工作区页浏览行的副标题。 */
data class WorkspaceStats(val fileCount: Int, val totalBytes: Long, val todayCount: Int)

/** 格式化副标题（效果图定稿口径："14 文件 · 92 KB · 今天产出 6 个"）。 */
internal fun formatWorkspaceStats(s: WorkspaceStats): String {
    val size = when {
        s.totalBytes >= 1L shl 20 -> "%.1f MB".format(s.totalBytes / 1048576.0)
        s.totalBytes >= 1024 -> "${s.totalBytes / 1024} KB"
        else -> "${s.totalBytes} B"
    }
    return "${s.fileCount} 文件 · $size" +
        (if (s.todayCount > 0) " · 今天产出 ${s.todayCount} 个" else "")
}

/**
 * walk 结果 → 统计。`nowMillis` 由调用方传入（单测可注入）；"今日"=本地时区
 * 自然日 0 点起（用 Calendar 口径，跨时区/DST 不误判）。
 */
internal fun workspaceStatsOf(
    entries: List<FileEntry>,
    nowMillis: Long
): WorkspaceStats {
    val cal = java.util.Calendar.getInstance().apply { timeInMillis = nowMillis }
    cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
    cal.set(java.util.Calendar.MINUTE, 0)
    cal.set(java.util.Calendar.SECOND, 0)
    cal.set(java.util.Calendar.MILLISECOND, 0)
    val dayStart = cal.timeInMillis
    var files = 0
    var bytes = 0L
    var today = 0
    for (e in entries) {
        if (e.path.endsWith("/")) continue // 目录条目不计（walk 口径以 "/" 结尾）
        files++
        bytes += e.size
        if (e.lastModified >= dayStart) today++
    }
    return WorkspaceStats(files, bytes, today)
}
