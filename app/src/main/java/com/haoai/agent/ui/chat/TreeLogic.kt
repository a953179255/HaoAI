package com.haoai.agent.ui.chat

/**
 * 批4b（2026-10-23 文件管理瘦身）：树模型退役（flattenTree/ancestorsOf/版本时间轴
 * 通道随三 Tab 壳删除），本文件只留两样还活着的：
 * - LocalLocateInTree：查看器 → 浏览单页的「在目录中定位」上抛通道（ChatScreen 顶层 provide）；
 * - normalizeRel / searchPaths：查看器与浏览页共用的路径口径与搜索过滤。
 */
val LocalLocateInTree = androidx.compose.runtime.staticCompositionLocalOf<(String) -> Unit> { {} }

/** 芯片/参数的原始 path → 行路径口径（去首斜杠、反斜杠、重复斜杠；.. 段由定位层早已拒绝）。 */
internal fun normalizeRel(raw: String): String =
    raw.replace('\\', '/').split('/').filter { it.isNotBlank() && it != "." }.joinToString("/")

/**
 * 搜索：walk 结果按相对路径子串过滤（大小写不敏感），按路径排序，封顶 limit。
 * 返回展示用的完整相对路径列表（搜索态下 basename 区分不了同名文件）。
 */
internal fun searchPaths(
    entries: List<com.haoai.agent.platform.FileEntry>,
    query: String,
    limit: Int = 200
): List<String> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return emptyList()
    return entries.asSequence()
        .map { it.path.replace('\\', '/') }
        .filter { it.lowercase().contains(q) }
        .sorted()
        .take(limit)
        .toList()
}
