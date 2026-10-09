package com.haoai.agent.ui.chat

/**
 * 批3a：查看器 → 文件树的定位通道（ChatScreen 顶层 provide：开树 + 传 rel）。
 * 与 LocalOpenToolSheet 同一模式：深层组件上抛"要开哪个全屏壳"，宿主统一挂载。
 */
val LocalLocateInTree = androidx.compose.runtime.staticCompositionLocalOf<(String) -> Unit> { {} }

/**
 * 批3d：查看器 → 版本时间轴的直达通道（ChatScreen 顶层 provide：开时间轴 + 预选文件）。
 */
val LocalOpenTimeline = androidx.compose.runtime.staticCompositionLocalOf<(String) -> Unit> { {} }

/**
 * 批3a：工作区文件树的纯逻辑（与 Compose/后端解耦，进 JVM 单测）。
 *
 * 树的懒加载模型：children 是「目录 rel → listDir 结果」的已加载缓存，
 * 目录名以 "/" 结尾（FileBackend.listDir 的口径，Raw/SAF 两后端一致且都按
 * 目录在前排序）；expanded 是展开的目录 rel 集合。flatten 从根深度优先
 * 展开成扁平行表喂 LazyColumn。
 */

/** 树的一行：文件展开后的扁平表示。 */
data class TreeRow(
    /** 工作区相对路径（目录不带尾斜杠）。 */
    val path: String,
    /** 显示名（basename）。 */
    val name: String,
    val isDir: Boolean,
    val depth: Int
)

/** 深度优先展开：children 里没加载的目录按未展开处理（只出行不出内容）。 */
internal fun flattenTree(
    children: Map<String, List<String>>,
    expanded: Set<String>
): List<TreeRow> {
    val out = ArrayList<TreeRow>()
    fun visit(dir: String, depth: Int) {
        for (entry in children[dir].orEmpty()) {
            val isDir = entry.endsWith("/")
            val name = if (isDir) entry.dropLast(1) else entry
            val path = if (dir.isEmpty()) name else "$dir/$name"
            out.add(TreeRow(path, name, isDir, depth))
            if (isDir && path in expanded) visit(path, depth + 1)
        }
    }
    visit("", 0)
    return out
}

/** "a/b/c.txt" → ["", "a", "a/b"]：定位时要先加载并展开的祖先目录链。 */
internal fun ancestorsOf(rel: String): List<String> {
    val parts = rel.split('/').filter { it.isNotBlank() }
    return List(parts.size) { i -> parts.take(i).joinToString("/") }
}

/** 芯片/参数的原始 path → 树行路径口径（去首斜杠、反斜杠、重复斜杠；.. 段由定位层早已拒绝）。 */
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
