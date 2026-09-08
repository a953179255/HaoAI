package com.haoai.agent.agent.memory

import com.haoai.agent.data.HaoJson
import kotlinx.serialization.Serializable
import java.io.File
import java.util.UUID

@Serializable
data class Memory(
    val id: String,
    var content: String,
    val tags: List<String> = emptyList(),
    val type: String = "fact",
    var importance: Int = 3,
    val createdAt: Long = System.currentTimeMillis(),
    var lastUsedAt: Long = 0,
    var useCount: Int = 0,
    /** 来源：model(模型主动)/auto(每轮自动提取)/manual(手动)/consolidation(固化晋升)。 */
    val source: String? = null,
    var updatedAt: Long = 0,
    /** 软失效（Graphiti 式"失效不删除"）：被更新的记忆 id，保留 30 天供溯源后由 tidy 清理。 */
    var supersededBy: String? = null
)

@Serializable
data class MemoryState(
    val items: MutableList<Memory> = mutableListOf(),
    /** markdown 中无法解析的原样行（手写内容），重写时放回「其他」节，绝不丢弃。 */
    var extras: List<String> = emptyList()
)

/**
 * 长期记忆库（"文件即记忆"）：
 * 真源 = 工作区 MEMORY.md（markdown），内存态为工作副本；旧版 memories.json 首启自动迁移。
 * 行格式（人读部分与镜像一致，行尾 HTML 注释承载无损元数据）：
 *   `- [c4a9e667 · 重要度4] 内容 [tags] <!-- id:.. imp:4 type:event created:.. lastUsed:.. uses:0 src:.. -->`
 * - 无注释的手写行按可见内容推导（id=内容哈希、type=所属 `## 节`、重要度默认 3）
 * - 软失效条目在「已归档」节（sup:xx 或手工移入即视为归档）；解析失败的行原样保留在「其他」节，绝不丢弃
 * - 每次写盘前先把当前文件复制为 MEMORY.md.bak（单代 preimage，防手改/解析损坏无路可退）
 * - 每次访问比对 mtime：外部手改文件后下一轮访问自动重载（手改实时生效）
 */
class MemoryBank(
    private val appFilesDir: File,
    private val maxItems: Int = 200,
    storageFile: File? = null
) {

    /** 真源文件：工作区 MEMORY.md；SAF 工作区无文件路径时回退应用私有目录（与日志层策略一致）。 */
    private val file: File = storageFile ?: File(appFilesDir, "MEMORY.md")

    fun storageFile(): File = file

    // 内存态为工作副本：promptSnippet 每轮注入系统提示，不能每次都解析文件（会卡主线程）
    @Volatile
    private var state: MemoryState? = null

    // 上次已知的文件 mtime：用于外部修改检测；自身写盘后同步更新，不会误触发重载
    @Volatile
    private var lastKnownMtime: Long = 0L

    init {
        // 旧版镜像特征：旧 WorkspaceDocs 渲染的只读视图（无元数据注释，头部带"镜像视图"字样）。
        // 它不是真源——此时若存在旧 memories.json，应从字段更全的 JSON 迁移，而不是采纳镜像。
        val content = if (file.exists()) runCatching { file.readText() }.getOrNull().orEmpty() else ""
        val isLegacyMirror = file.exists() && !content.contains("<!--") && content.contains("镜像视图")
        val legacy = File(appFilesDir, "memories.json")
        val legacyState = if (!file.exists() || isLegacyMirror) {
            if (legacy.exists()) {
                runCatching {
                    HaoJson.readJsonSafe(legacy, MemoryState.serializer())?.also {
                        runCatching { legacy.renameTo(File(appFilesDir, "memories.json.migrated.bak")) }
                    }
                }.getOrNull()
            } else null
        } else null

        when {
            // 真源缺失或为旧镜像：用迁移数据（或空库）建立新格式真源
            !file.exists() || isLegacyMirror -> {
                state = legacyState ?: MemoryState()
                persistToDisk(state!!)
            }
            // 已是真源格式：直接解析
            else -> {
                state = parseMarkdown(content)
                lastKnownMtime = file.lastModified()
            }
        }
    }

    private fun stateOrNull(): MemoryState {
        state?.let {
            maybeReload()
            return state!!
        }
        val loaded = parseMarkdown(runCatching { file.readText() }.getOrDefault(""))
        state = loaded
        lastKnownMtime = if (file.exists()) file.lastModified() else 0L
        return loaded
    }

    /** 外部（模型/用户）手改真源文件后自动重载；自身写盘会同步 lastKnownMtime，不会误触发。 */
    private fun maybeReload() {
        val m = if (file.exists()) file.lastModified() else 0L
        if (m != lastKnownMtime) {
            state = parseMarkdown(runCatching { file.readText() }.getOrDefault(""))
            lastKnownMtime = m
        }
    }

    @Synchronized
    private fun load(): MemoryState = stateOrNull()

    @Synchronized
    private fun save(state: MemoryState) {
        this.state = state
        persistToDisk(state)
    }

    /** 渲染 markdown 并原子写盘；写前保留单代 preimage（.bak）。 */
    private fun persistToDisk(state: MemoryState) {
        runCatching {
            file.parentFile?.mkdirs()
            if (file.exists()) {
                runCatching { file.copyTo(File(file.parentFile, file.name + ".bak"), overwrite = true) }
            }
            HaoJson.writeAtomic(file, renderMarkdown(state))
            lastKnownMtime = file.lastModified()
        }
    }

    /**
     * 写入长期记忆。返回写入/命中的条目；返回 null 表示记忆库已满且无低价值条目可驱逐
     * （把整理责任交还模型，由其 forget/合并后再写）。
     */
    @Synchronized
    fun remember(
        content: String,
        tags: List<String> = emptyList(),
        type: String = "fact",
        importance: Int = 3,
        source: String? = null
    ): Memory? {
        val text = content.trim()
        val state = load()
        // 精确 + 归一化双重去重：自动提取的换述版本（标点/空白/大小写差异）不再重复入库；
        // 软失效条目视为已删除，不参与命中（否则被覆盖的记忆会因重复保存而"复活失败"）
        val norm = normalize(text)
        val existed = state.items.filter { it.supersededBy == null }.let { live ->
            live.firstOrNull { it.content == text }
                ?: live.firstOrNull { normalize(it.content) == norm && norm.isNotEmpty() }
        }
        if (existed != null) {
            existed.lastUsedAt = System.currentTimeMillis()
            if (importance > existed.importance) {
                existed.importance = importance.coerceIn(1, 5)
                existed.updatedAt = System.currentTimeMillis()
            }
            save(state)
            return existed
        }
        // 预算控制：优先驱逐已软失效的条目，其次「低价值且长期未用」，否则拒绝写入并提示整理
        while (state.items.size >= maxItems) {
            val now = System.currentTimeMillis()
            val victim = state.items.filter { it.supersededBy != null }
                .minByOrNull { if (it.updatedAt > 0) it.updatedAt else it.createdAt }
                ?: state.items
                    .filter {
                        it.importance <= 2 &&
                            (now - (if (it.lastUsedAt > 0) it.lastUsedAt else it.createdAt)) > 60L * 86_400_000
                    }
                    .minByOrNull { it.importance }
                ?: break
            state.items.remove(victim)
        }
        if (state.items.size >= maxItems) return null
        val m = Memory(
            id = UUID.randomUUID().toString().take(8),
            content = text.take(500),
            tags = tags.map { it.trim() }.filter { it.isNotEmpty() }.take(6),
            type = type.take(20),
            importance = importance.coerceIn(1, 5),
            source = source?.take(20)
        )
        state.items.add(m)
        save(state)
        return m
    }

    fun capacity(): Int = maxItems

    @Synchronized
    fun activeCount(): Int = load().items.count { it.supersededBy == null }

    /** 近冲突检测（Mem0 裁决的规则前置）：与输入词面相近但不等价（Jaccard 0.45-0.85）的既有条目。 */
    fun nearConflicts(content: String, k: Int = 3): List<Memory> {
        val toks = tokenize(content)
        if (toks.isEmpty()) return emptyList()
        val norm = normalize(content.trim())
        return load().items.mapNotNull { m ->
            if (m.supersededBy != null) return@mapNotNull null
            val mt = tokenize(m.content)
            val union = mt.union(toks).size
            if (union == 0) return@mapNotNull null
            val j = mt.intersect(toks).size.toDouble() / union
            if (j >= CONFLICT_LOW && j <= CONFLICT_HIGH && normalize(m.content) != norm) m to j else null
        }.sortedByDescending { it.second }.take(k).map { it.first }
    }

    /** 软失效：id 被 by 覆盖，不再参与检索/注入，保留 30 天供溯源。 */
    @Synchronized
    fun supersede(id: String, by: String): Boolean {
        val state = load()
        val m = state.items.firstOrNull { it.id == id } ?: return false
        m.supersededBy = by
        m.updatedAt = System.currentTimeMillis()
        save(state)
        return true
    }

    /** 原地更新内容（Mem0 UPDATE 决策），保留 id/创建时间/来源。 */
    @Synchronized
    fun updateContent(id: String, content: String, importance: Int? = null): Boolean {
        val text = content.trim()
        if (text.isEmpty()) return false
        val state = load()
        val m = state.items.firstOrNull { it.id == id } ?: return false
        m.content = text.take(500)
        if (importance != null) m.importance = importance.coerceIn(1, 5)
        m.updatedAt = System.currentTimeMillis()
        save(state)
        return true
    }

    /** 注入命中标记：每条至多每小时 +1 次 useCount 并刷新 lastUsedAt；token 估算等只读调用不计数。 */
    @Synchronized
    fun markInjected(ids: Collection<String>) {
        if (ids.isEmpty()) return
        val now = System.currentTimeMillis()
        val state = load()
        var changed = false
        ids.forEach { id ->
            state.items.firstOrNull { it.id == id }?.let { m ->
                val last = if (m.lastUsedAt > 0) m.lastUsedAt else 0L
                if (now - last > 3_600_000L) {
                    m.lastUsedAt = now
                    m.useCount += 1
                    changed = true
                }
            }
        }
        if (changed) save(state)
    }

    @Synchronized
    fun search(query: String, k: Int = 5): List<Memory> {
        val qTokens = tokenize(query)
        if (qTokens.isEmpty()) return emptyList()
        val now = System.currentTimeMillis()
        val scored = load().items.filter { it.supersededBy == null }.mapNotNull { m ->
            val mTokens = tokenize(m.content + " " + m.tags.joinToString(" "))
            val overlap = qTokens.intersect(mTokens).size
            if (overlap == 0) return@mapNotNull null
            val ageDays = (now - (if (m.lastUsedAt > 0) m.lastUsedAt else m.createdAt)) / 86_400_000.0
            val score = overlap * 2.0 + 3.0 * Math.exp(-ageDays / 14.0) + Math.min(m.useCount, 5) * 0.1
            m to score
        }.sortedByDescending { it.second }.take(k)
        if (scored.isNotEmpty()) {
            val state = load()
            scored.forEach { (m, _) ->
                state.items.find { it.id == m.id }?.let {
                    it.lastUsedAt = now
                    it.useCount += 1
                }
            }
            save(state)
        }
        return scored.map { it.first }
    }

    @Synchronized
    fun all(): List<Memory> = load().items.filter { it.supersededBy == null }.sortedByDescending { it.createdAt }

    @Synchronized
    fun forget(idOrQuery: String): Int {
        val state = load()
        val q = idOrQuery.trim()
        // 空 query 会匹配到所有条目（"".contains("")==true），必须直接拒绝，防止误清空整个记忆库
        if (q.isEmpty()) return 0
        val targets = state.items.filter {
            it.id.equals(q, ignoreCase = true) ||
                (q.length >= 3 && it.id.startsWith(q, ignoreCase = true)) ||
                (q.length >= 2 && it.content.contains(q))
        }
        if (targets.isEmpty()) return 0
        state.items.removeAll(targets.toSet())
        save(state)
        return targets.size
    }

    /** 按 id 精确删除（深度梦境合并/清理专用），返回删除条数。 */
    @Synchronized
    fun removeIds(ids: Collection<String>): Int {
        if (ids.isEmpty()) return 0
        val state = load()
        val idSet = ids.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val before = state.items.size
        state.items.removeAll { it.id in idSet }
        val n = before - state.items.size
        if (n > 0) save(state)
        return n
    }

    @Synchronized
    fun count(): Int = load().items.size

    /**
     * 记忆整理（防滥记兜底，纯本地无 LLM）：
     * 1) 低价值遗忘改为降级（同类 式）：importance<=2 且 30 天未用 → 标记 dormant，
     *    移入「已归档」节，不再参与注入/检索但保留可见可捞回
     * 2) 软失效（sup/dormant/archived）超 30 天才物理清理
     * 3) 归一化去重（忽略空白/大小写/标点差异）
     * 4) 高相似合并（同 token 重叠率 > 0.85 保留重要性高/更新的）
     * @return 删除+降级的条数
     */
    @Synchronized
    fun tidy(): Int {
        val state = load()
        val now = System.currentTimeMillis()
        val kept = mutableListOf<Memory>()
        var removed = 0
        // 低价值记忆降级为 dormant（30 天未用且重要度<=2），不再直接删除
        var degraded = 0
        state.items.forEach { m ->
            if (m.supersededBy == null && m.importance <= 2) {
                val ref = if (m.lastUsedAt > 0) m.lastUsedAt else m.createdAt
                if ((now - ref) / 86_400_000.0 > 30) {
                    m.supersededBy = DORMANT
                    m.updatedAt = now
                    degraded++
                }
            }
        }
        // 软失效超 30 天物理清理（dormant/sup/archived 同规则）
        val beforePurge = state.items.size
        state.items.removeAll {
            if (it.supersededBy == null) return@removeAll false
            val ref = if (it.updatedAt > 0) it.updatedAt else it.createdAt
            (now - ref) / 86_400_000.0 > 30
        }
        removed += beforePurge - state.items.size
        for (m in state.items.sortedWith(compareByDescending<Memory> { it.importance }.thenByDescending { it.createdAt })) {
            val norm = normalize(m.content)
            val toks = tokenize(m.content)
            val dup = kept.any { k ->
                normalize(k.content) == norm || run {
                    val kt = tokenize(k.content)
                    val inter = kt.intersect(toks).size
                    val union = kt.union(toks).size
                    union > 0 && inter.toDouble() / union > 0.85
                }
            }
            if (dup) removed++ else kept.add(m)
        }
        if (removed > 0 || degraded > 0) save(MemoryState(kept.toMutableList(), state.extras))
        return removed + degraded
    }

    private fun normalize(s: String): String =
        s.lowercase().replace(Regex("[\\s，。、；：！？,.:;!?()（）\\[\\]\"']"), "")

    @Synchronized
    fun clear() {
        save(MemoryState())
    }

    fun promptSnippet(query: String? = null, k: Int = 8, capPerItem: Int = 180): String =
        promptSnippetIds(query, k, capPerItem).first

    /**
     * 注入片段（触发式注入）：query 非空时打分 =
     * 词面重叠*2.5 + 全局重要性/新鲜度/useCount；零重叠时自然退化为原全局排序。
     * 同时返回命中的 id 列表，供真实请求路径回写 lastUsedAt/useCount（估算调用不回写）。
     */
    fun promptSnippetIds(query: String? = null, k: Int = 8, capPerItem: Int = 180): Pair<String, List<String>> {
        val now = System.currentTimeMillis()
        val qTokens = query?.let { tokenize(it) } ?: emptySet()
        val items = all()
            .sortedByDescending { m ->
                val ageDays = (now - (if (m.lastUsedAt > 0) m.lastUsedAt else m.createdAt)) / 86_400_000.0
                val overlap = if (qTokens.isEmpty()) 0
                else qTokens.intersect(tokenize(m.content + " " + m.tags.joinToString(" "))).size
                overlap * 2.5 + m.importance * 3.0 + 2.0 * Math.exp(-ageDays / 14.0) +
                    Math.min(m.useCount, 5) * 0.2
            }
            .take(k)
        if (items.isEmpty()) return "" to emptyList()
        val text = buildString {
            appendLine("## 长期记忆（过往沉淀，回答时主动参考）")
            items.forEach { m ->
                val tag = if (m.tags.isEmpty()) "" else " [${m.tags.joinToString(",")}]"
                val type = if (m.type == "fact") "" else "(${m.type}) "
                appendLine("- $type${m.content.take(capPerItem)}$tag")
            }
        }.trimEnd()
        return text to items.map { it.id }
    }

    // ---------- markdown 持久化（真源格式） ----------

    private fun renderMarkdown(state: MemoryState): String = buildString {
        appendLine("# 长期记忆（MEMORY.md）")
        appendLine()
        appendLine("> 长期记忆真源：模型与用户都可直接查看编辑。行尾 <!-- --> 是元数据，修改内容时请整行保留；")
        appendLine("> 去重/上限/冲突检测等结构性修改建议仍用 memory 工具。每次写盘前旧版保留为同目录 .bak。")
        appendLine()
        listOf("preference" to "偏好", "decision" to "决定", "event" to "事件", "fact" to "事实").forEach { (type, label) ->
            val group = state.items.filter { it.supersededBy == null && it.type == type }
                .sortedWith(compareByDescending<Memory> { it.importance }.thenByDescending { it.createdAt })
            if (group.isEmpty()) return@forEach
            appendLine("## $label（${group.size}）")
            group.forEach { appendLine(formatMemory(it)) }
            appendLine()
        }
        val archived = state.items.filter { it.supersededBy != null }
            .sortedByDescending { if (it.updatedAt > 0) it.updatedAt else it.createdAt }
        if (archived.isNotEmpty()) {
            appendLine("## 已归档（${archived.size}）")
            archived.forEach { appendLine(formatMemory(it)) }
            appendLine()
        }
        if (state.extras.isNotEmpty()) {
            appendLine("## 其他")
            state.extras.forEach { appendLine(it) }
            appendLine()
        }
    }.trimEnd() + "\n"

    private fun formatMemory(m: Memory): String = buildString {
        val oneLine = m.content.replace(Regex("\\s+"), " ").trim()
        // tags 只写进行尾元数据注释，不渲染可见方括号——否则解析时（元数据优先）会污染 content
        append("- [${m.id} · 重要度${m.importance}] $oneLine")
        append(" <!-- id:${m.id} imp:${m.importance} type:${m.type} created:${m.createdAt}")
        append(" lastUsed:${m.lastUsedAt} uses:${m.useCount}")
        if (!m.source.isNullOrBlank()) append(" src:${m.source}")
        if (m.updatedAt > 0) append(" upd:${m.updatedAt}")
        if (!m.supersededBy.isNullOrBlank()) append(" sup:${m.supersededBy}")
        if (m.tags.isNotEmpty()) append(" tags:${m.tags.joinToString(",")}")
        append(" -->")
    }

    private fun parseMarkdown(text: String): MemoryState {
        val items = mutableListOf<Memory>()
        val extras = mutableListOf<String>()
        var sectionType: String? = null
        var archived = false
        val now = System.currentTimeMillis()
        val usedIds = HashSet<String>()
        for (raw in text.lines()) {
            val line = raw.trim()
            when {
                line.isEmpty() || line.startsWith(">") -> {}
                line.startsWith("## ") -> {
                    val title = line.removePrefix("## ").trim()
                    sectionType = when {
                        title.startsWith("偏好") -> "preference"
                        title.startsWith("决定") -> "decision"
                        title.startsWith("事件") -> "event"
                        title.startsWith("事实") -> "fact"
                        else -> null
                    }
                    archived = title.startsWith("已归档")
                }
                line.startsWith("# ") -> {} // 主标题为模板再生，不进数据
                line.startsWith("- ") -> {
                    parseMemoryLine(line, sectionType, archived, now, usedIds)?.let { items.add(it) }
                        ?: extras.add(line)
                }
                else -> extras.add(line)
            }
        }
        return MemoryState(items, extras)
    }

    /** 解析一条记忆行；返回 null 表示无法解析（调用方把该行原样保留进「其他」，绝不丢弃）。 */
    private fun parseMemoryLine(
        line: String,
        sectionType: String?,
        archived: Boolean,
        now: Long,
        usedIds: MutableSet<String>
    ): Memory? {
        val withoutComment: String
        val meta: Map<String, String>
        val cStart = line.indexOf("<!--")
        if (cStart >= 0) {
            val cEnd = line.lastIndexOf("-->")
            if (cEnd <= cStart) return null
            withoutComment = line.substring(0, cStart).trim()
            meta = Regex("([A-Za-z]+):([^\\s]+)").findAll(line.substring(cStart + 4, cEnd))
                .associate { it.groupValues[1].lowercase() to it.groupValues[2] }
        } else {
            withoutComment = line
            meta = emptyMap()
        }
        if (!withoutComment.startsWith("-")) return null
        var rest = withoutComment.removePrefix("-").trim()
        // 可见头 `- [id · 重要度N] `
        val head = Regex("^\\[([0-9a-fA-F]{1,8})\\s*·\\s*重要度(\\d)]\\s*(.*)$").find(rest)
        var visibleId: String? = null
        if (head != null) {
            visibleId = head.groupValues[1]
            rest = head.groupValues[3].trim()
        }
        val importance = meta["imp"]?.toIntOrNull()?.coerceIn(1, 5)
            ?: head?.groupValues?.get(2)?.toIntOrNull()?.coerceIn(1, 5)
            ?: 3
        var content = rest
        // 手写行的行尾 [tags]：仅当行内没有元数据注释时才按可见方括号推导（自身渲染的行 tags 一律在注释里）
        val tags = meta["tags"]?.split(",", "，")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: run {
            val m = Regex("\\[([^\\[\\]]+)]\\s*$").find(content)
            if (m != null && meta.isEmpty()) {
                content = content.substring(0, m.range.first).trim()
                m.groupValues[1].split(",", "，").map { it.trim() }.filter { it.isNotEmpty() }
            } else emptyList()
        }
        // 自愈：旧版渲染器曾把 tags 同时写在可见尾部和元数据里（重复保存会层层叠加），
        // 元数据 tags 存在时把尾部成串的相同方括号全部剥掉，防污染 content
        val metaTags = meta["tags"]
        if (!metaTags.isNullOrEmpty()) {
            val suffix = "[$metaTags]"
            while (content.endsWith(suffix)) content = content.removeSuffix(suffix).trim()
        }
        if (content.isEmpty()) return null
        var id = (meta["id"] ?: visibleId)?.take(8)?.lowercase().orEmpty()
        if (id.isEmpty() || id in usedIds) id = UUID.randomUUID().toString().take(8)
        usedIds.add(id)
        // 归档位置即权威：在「已归档」节 = 失效（sup 元数据或默认 archived）；
        // 用户把行移回正常节 = 手动捞回，忽略 sup 标记恢复有效
        val supersededBy = if (archived) (meta["sup"]?.take(8) ?: "archived") else null
        return Memory(
            id = id,
            content = content,
            tags = tags,
            type = (meta["type"] ?: sectionType ?: "fact").take(20),
            importance = importance,
            createdAt = meta["created"]?.toLongOrNull() ?: now,
            lastUsedAt = meta["lastused"]?.toLongOrNull() ?: 0L,
            useCount = (meta["uses"]?.toIntOrNull() ?: 0).coerceAtLeast(0),
            source = meta["src"]?.take(20),
            updatedAt = meta["upd"]?.toLongOrNull() ?: 0L,
            supersededBy = supersededBy
        )
    }

    companion object {
        /** 近冲突词面相似度区间：低于下限视为无关，高于上限视为去重范畴（tidy 合并）。 */
        const val CONFLICT_LOW = 0.45
        const val CONFLICT_HIGH = 0.85

        /** 降级遗忘标记（同类 式 subconscious）：不参与注入/检索，保留 30 天后物理清理。 */
        const val DORMANT = "dormant"

        fun tokenize(text: String): Set<String> {
            val out = HashSet<String>()
            val latin = text.lowercase().split(Regex("[^a-z0-9\\u4e00-\\u9fff]+"))
                .filter { it.length in 2..24 }
            out.addAll(latin)
            val cjk = text.filter { it.code in 0x4e00..0x9fff }
            for (i in 0 until cjk.length - 1) out.add(cjk.substring(i, i + 2))
            return out
        }
    }
}
