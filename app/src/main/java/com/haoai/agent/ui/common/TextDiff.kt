package com.haoai.agent.ui.common

/** diff 行类型。 */
enum class DiffType { SAME, ADDED, REMOVED }

data class DiffLine(val type: DiffType, val text: String)

/**
 * 自研行级 LCS diff（1.3）：纯 Kotlin，无依赖。
 * 大文件（>2000 行）只对尾部 2000 行做 diff，头部折叠为"…未变更"标记，
 * 覆盖审批弹窗的阅读场景（审批关心的是这次改动，不是整个文件）。
 */
object TextDiff {

    data class Result(val lines: List<DiffLine>, val truncatedHead: Int, val added: Int, val removed: Int)

    fun diffText(oldText: String, newText: String, tailLimit: Int = 2000): Result {
        val old = oldText.split('\n')
        val new = newText.split('\n')
        var oldView = old
        var truncatedHead = 0
        if (old.size > tailLimit) {
            truncatedHead = old.size - tailLimit
            oldView = old.subList(truncatedHead, old.size)
        }
        val lines = lcs(oldView, new)
        val added = lines.count { it.type == DiffType.ADDED }
        val removed = lines.count { it.type == DiffType.REMOVED }
        return Result(lines, truncatedHead, added, removed)
    }

    /** 经典 LCS 回溯；行数过大时退化为简化输出（全标替换），防内存失控。 */
    private fun lcs(a: List<String>, b: List<String>): List<DiffLine> {
        if (a.size * b.size > 4_000_000) {
            return buildList {
                addAll(a.map { DiffLine(DiffType.REMOVED, it) })
                addAll(b.map { DiffLine(DiffType.ADDED, it) })
            }
        }
        val n = a.size
        val m = b.size
        // dp[i][j] = a[i..] 与 b[j..] 的公共子序列长度
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                dp[i][j] = if (a[i] == b[j]) dp[i + 1][j + 1] + 1
                else maxOf(dp[i + 1][j], dp[i][j + 1])
            }
        }
        val out = mutableListOf<DiffLine>()
        // 相邻的 ADD/REMOVE 合并顺序：先删后增（与常见 diff 观感一致）
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                a[i] == b[j] -> {
                    out.add(DiffLine(DiffType.SAME, a[i])); i++; j++
                }
                dp[i + 1][j] >= dp[i][j + 1] -> {
                    out.add(DiffLine(DiffType.REMOVED, a[i])); i++
                }
                else -> {
                    out.add(DiffLine(DiffType.ADDED, b[j])); j++
                }
            }
        }
        while (i < n) { out.add(DiffLine(DiffType.REMOVED, a[i])); i++ }
        while (j < m) { out.add(DiffLine(DiffType.ADDED, b[j])); j++ }
        return out
    }
}

/** DiffView 用的折叠视图参数：超 300 行折叠为"展开查看完整 diff"。 */
private const val DIFF_COLLAPSE_LINES = 300
