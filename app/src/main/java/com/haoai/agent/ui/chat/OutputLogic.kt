package com.haoai.agent.ui.chat

import com.haoai.agent.ui.ChainStep
import com.haoai.agent.ui.ChatRow

/**
 * 批3b：产出汇总的纯逻辑层。
 *
 * 口径：只认**成功完成**的 write/edit 步（state 成功且有 diff 摘要）——read 是消费
 * 不是产出，失败的写不算产出。同一文件被多轮改过合并成一条，变更数取末次写入
 * （链上后出现的步骤覆盖先出现的）。
 *
 * 快照淘汰的补集：manifest 每会话 200 条上限，早期 write 的 diff 可能已不可用；
 * 这类步没有 UiDiff，天然不进清单——面板底部对差异计数为 0 的产物如实标注口径，
 * 不假装统计完整。
 */

/** 一条产物（会话级合并后的文件清单行）。 */
data class OutputEntry(
    val relPath: String,
    /** 产出该文件的工具名（合并后取末次）：write 新建 / edit 修改。 */
    val toolName: String,
    /** 末次写入的 callId——「查看变更」复用 1.3 快照 diff 通道的钥匙。 */
    val callId: String,
    val added: Int,
    val removed: Int,
    /** 末次写入时是新建文件（此前不存在）。 */
    val isNewFile: Boolean,
    /** 链上出现的顺序位（越大越新），排序用。 */
    val seq: Int
)

/**
 * 从行的链步骤里按出现顺序取 write/edit 步。
 * 只认 [com.haoai.agent.agent.engine.ToolRunState.DONE]：ERROR 步的 diff 是"参数预测
 * 变更"（快照在写前就拍好了），写失败时文件其实没动，进汇总就是虚账。
 * rel 优先取 fileRef（批2c 定位），SAF 工作区没有文件系统根 → fileRef 为 null，
 * 回落快照 manifest 里的 path——汇总对 SAF 会话照样列账，只是没有"打开查看"出路。
 */
private fun writeEditToolsOf(row: ChatRow): List<Pair<com.haoai.agent.ui.UiTool, String>> =
    (row.chainSteps.filterIsInstance<ChainStep.Tool>().map { it.tool } + row.tools)
        .filter {
            (it.name == "write" || it.name == "edit") &&
                it.diff != null && it.state == com.haoai.agent.agent.engine.ToolRunState.DONE
        }
        .map { t ->
            t to (t.fileRef?.relPath ?: normalizeRel(t.diff!!.path))
        }

/** 整会话产物合并表：跨行同文件去重（后写覆盖），seq 全局递增保时序。 */
internal fun buildOutputEntries(rows: List<ChatRow>): List<OutputEntry> {
    var seq = 0
    val byPath = LinkedHashMap<String, OutputEntry>()
    for (row in rows) {
        for ((t, rawRel) in writeEditToolsOf(row)) {
            val d = t.diff!!
            val rel = normalizeRel(rawRel)
            byPath[rel] = OutputEntry(
                relPath = rel,
                toolName = t.name,
                callId = t.callId,
                added = d.added,
                removed = d.removed,
                isNewFile = d.isNewFile,
                seq = seq++
            )
        }
    }
    return byPath.values.sortedByDescending { it.seq }
}
