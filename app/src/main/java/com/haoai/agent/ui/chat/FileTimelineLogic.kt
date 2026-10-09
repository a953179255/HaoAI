package com.haoai.agent.ui.chat

import com.haoai.agent.agent.tools.snapshot.FileSnapshot

/**
 * 批3d：文件版本时间轴的纯逻辑层。
 *
 * 数据源是写前快照 manifest（FileSnapshot.listSession，时间正序）：每条 Meta 是
 * 一次 write/edit 的「变更前+变更后」全文对，所以任意两版的 diff 都能离线重建，
 * 不依赖文件当前状态。注意口径：/undo 回滚也会生成 redo 快照进时间轴——如实显示，
 * 它就是文件历史的一部分。淘汰（200 条/50MB 上限）导致的断层无法补偿，面板
 * 只在头部报版本数，不假装历史完整。
 */

/** 时间轴左栏一个文件：路径 + 版本数 + 最近一次变更时间。 */
data class TimelineFile(val relPath: String, val versionCount: Int, val lastTs: Long)

/** manifest 按文件归组（rel 归一口径），最近变更的文件排前面。 */
internal fun groupHistory(metas: List<FileSnapshot.Meta>): List<TimelineFile> {
    val byPath = LinkedHashMap<String, MutableList<FileSnapshot.Meta>>()
    for (meta in metas) {
        byPath.getOrPut(normalizeRel(meta.path)) { mutableListOf() }.add(meta)
    }
    return byPath.map { (p, ms) -> TimelineFile(p, ms.size, ms.last().ts) }
        .sortedByDescending { it.lastTs }
}

/** 某文件的版本序列（保持 manifest 时间正序）。 */
internal fun versionsOf(
    metas: List<FileSnapshot.Meta>,
    rel: String
): List<FileSnapshot.Meta> {
    val want = normalizeRel(rel)
    return metas.filter { normalizeRel(it.path) == want }
}

/** 版本选择态：点击切换，最多留两个（先进先出），返回最新选择序。 */
internal fun toggleSelect(sel: List<String>, callId: String): List<String> =
    if (callId in sel) sel - callId
    else (sel + callId).takeLast(2)
