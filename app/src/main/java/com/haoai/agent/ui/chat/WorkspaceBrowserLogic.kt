package com.haoai.agent.ui.chat

import com.haoai.agent.platform.FileEntry

/**
 * 批4b（2026-10-23 文件管理瘦身）：工作区浏览单页的纯逻辑（进 JVM 单测）。
 *
 * 对标定稿（报告§5 OpenMinis 形态）：三 Tab 壳退役，「目录」职能降级为
 * **扁平列表 + 面包屑**单页——点目录=整页换成该目录内容（不再原地展开成树），
 * 点上级=面包屑回跳。行数据两路合成：listDir(cur) 给「本层有什么」（目录前置
 * 排序是后端口径），walk 全量清单给文件行补大小/修改时间（目录行不显示元信息）。
 */

/** 浏览页一行：文件带大小/时间，目录带子项数（walk 视角，可能为 0=未统计）。 */
data class BrowserRow(
    val rel: String,
    val name: String,
    val isDir: Boolean,
    val size: Long,
    val lastModified: Long
)

/**
 * listDir 输出（目录名带尾 "/"）+ walk 文件清单 → 当前目录行表。
 * curRel 为 "" 时 rel=名字直用；否则拼 "cur/name"（面包屑与钻入共用这套口径）。
 * walk 缺条目的文件行（理论不该发生，防御）出 size=0/时间=0，UI 侧显示占位。
 */
internal fun buildBrowserRows(
    names: List<String>,
    entries: List<FileEntry>,
    curRel: String
): List<BrowserRow> {
    val byPath = HashMap<String, FileEntry>()
    for (e in entries) byPath[e.path.replace('\\', '/')] = e
    val prefix = if (curRel.isEmpty()) "" else "$curRel/"
    return names.map { raw ->
        val isDir = raw.endsWith("/")
        val name = if (isDir) raw.dropLast(1) else raw
        BrowserRow(
            rel = prefix + name,
            name = name,
            isDir = isDir,
            size = if (isDir) 0 else byPath[prefix + name]?.size ?: 0,
            lastModified = if (isDir) 0 else byPath[prefix + name]?.lastModified ?: 0
        )
    }
}

/** 面包屑段："" → 空列表（根=工作区一枚 chip）；"a/b" → ["a","b"]。 */
internal fun crumbSegments(curRel: String): List<String> =
    curRel.split('/').filter { it.isNotBlank() }

/** "a/b/c.txt" → "a/b"；顶层 → ""。 */
internal fun parentOf(rel: String): String = rel.substringBeforeLast('/', "")

/** 行大小：B/KB/MB 列表副标题，够短不溢出）。 */
internal fun formatRowSize(bytes: Long): String = when {
    bytes <= 0 -> ""
    bytes >= 1L shl 20 -> "%.1f MB".format(bytes / 1048576.0)
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "${bytes} B"
}

/**
 * 行时间（对标 OpenMinis 副标题口径）：今天「HH:mm」、本年「MM-dd HH:mm」、跨年「yyyy-MM-dd」。
 * 自然日判定用 Calendar（与 workspaceStatsOf 的"今日"同一口径，防时区/DST 误判）。
 */
internal fun formatRowTime(ms: Long, nowMs: Long): String {
    if (ms <= 0) return ""
    fun cal(t: Long) = java.util.Calendar.getInstance().apply { timeInMillis = t }
    val a = cal(ms); val b = cal(nowMs)
    fun dayStart(c: java.util.Calendar) = c.apply {
        set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis
    val hm = "%02d:%02d".format(
        a.get(java.util.Calendar.HOUR_OF_DAY), a.get(java.util.Calendar.MINUTE)
    )
    return when {
        dayStart(a) == dayStart(b) -> hm
        a.get(java.util.Calendar.YEAR) == b.get(java.util.Calendar.YEAR) ->
            "%02d-%02d %s".format(
                a.get(java.util.Calendar.MONTH) + 1, a.get(java.util.Calendar.DAY_OF_MONTH), hm
            )
        else -> "%04d-%02d-%02d".format(
            a.get(java.util.Calendar.YEAR), a.get(java.util.Calendar.MONTH) + 1,
            a.get(java.util.Calendar.DAY_OF_MONTH)
        )
    }
}
