package com.haoai.agent.agent.memory

import com.haoai.agent.data.HaoJson
import kotlinx.serialization.Serializable
import java.io.File
import java.util.UUID

@Serializable
data class Memory(
    val id: String,
    val content: String,
    val tags: List<String> = emptyList(),
    val type: String = "fact",
    var importance: Int = 3,
    val createdAt: Long = System.currentTimeMillis(),
    var lastUsedAt: Long = 0,
    var useCount: Int = 0
)

@Serializable
data class MemoryState(val items: MutableList<Memory> = mutableListOf())

class MemoryBank(private val appFilesDir: File, private val maxItems: Int = 200) {

    private val file = File(appFilesDir, "memories.json")

    @Synchronized
    private fun load(): MemoryState = runCatching {
        if (!file.exists()) return MemoryState()
        HaoJson.json.decodeFromString(MemoryState.serializer(), file.readText())
    }.getOrDefault(MemoryState())

    @Synchronized
    private fun save(state: MemoryState) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(HaoJson.json.encodeToString(MemoryState.serializer(), state))
        }
    }

    @Synchronized
    fun remember(
        content: String,
        tags: List<String> = emptyList(),
        type: String = "fact",
        importance: Int = 3
    ): Memory {
        val text = content.trim()
        val state = load()
        val existed = state.items.firstOrNull { it.content == text }
        if (existed != null) {
            existed.lastUsedAt = System.currentTimeMillis()
            if (importance > existed.importance) existed.importance = importance.coerceIn(1, 5)
            save(state)
            return existed
        }
        val m = Memory(
            id = UUID.randomUUID().toString().take(8),
            content = text.take(500),
            tags = tags.map { it.trim() }.filter { it.isNotEmpty() }.take(6),
            type = type.take(20),
            importance = importance.coerceIn(1, 5)
        )
        state.items.add(m)
        while (state.items.size > maxItems) {
            state.items.minByOrNull { it.importance * 10_000L + (if (it.lastUsedAt > 0) it.lastUsedAt else it.createdAt) }
                ?.let { state.items.remove(it) } ?: break
        }
        save(state)
        return m
    }

    @Synchronized
    fun search(query: String, k: Int = 5): List<Memory> {
        val qTokens = tokenize(query)
        if (qTokens.isEmpty()) return emptyList()
        val now = System.currentTimeMillis()
        val scored = load().items.mapNotNull { m ->
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
    fun all(): List<Memory> = load().items.sortedByDescending { it.createdAt }

    @Synchronized
    fun forget(idOrQuery: String): Int {
        val state = load()
        val q = idOrQuery.trim()
        val targets = state.items.filter {
            it.id.equals(q, ignoreCase = true) || it.id.startsWith(q, ignoreCase = true) ||
                it.content.contains(q)
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
     * 1) 归一化去重（忽略空白/大小写/标点差异）
     * 2) 高相似合并（同 token 重叠率 > 0.85 保留重要性高/更新的）
     * 3) 清理低价值：importance<=2 且 30 天未用的
     * @return 删除条数
     */
    @Synchronized
    fun tidy(): Int {
        val state = load()
        val now = System.currentTimeMillis()
        val kept = mutableListOf<Memory>()
        var removed = 0
        // 先清低价值
        state.items.removeAll {
            val ageDays = (now - (if (it.lastUsedAt > 0) it.lastUsedAt else it.createdAt)) / 86_400_000.0
            it.importance <= 2 && ageDays > 30
        }
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
        if (removed > 0) save(MemoryState(kept.toMutableList()))
        return removed
    }

    private fun normalize(s: String): String =
        s.lowercase().replace(Regex("[\\s，。、；：！？,.:;!?()（）\\[\\]\"']"), "")

    @Synchronized
    fun clear() {
        save(MemoryState())
    }

    fun promptSnippet(k: Int = 8, capPerItem: Int = 180): String {
        val now = System.currentTimeMillis()
        val items = all()
            .sortedByDescending {
                val ageDays = (now - (if (it.lastUsedAt > 0) it.lastUsedAt else it.createdAt)) / 86_400_000.0
                it.importance * 3.0 + 2.0 * Math.exp(-ageDays / 14.0) + Math.min(it.useCount, 5) * 0.2
            }
            .take(k)
        if (items.isEmpty()) return ""
        return buildString {
            appendLine("## 长期记忆（按重要性挑选的过往沉淀，回答时主动参考）")
            items.forEach { m ->
                val tag = if (m.tags.isEmpty()) "" else " [${m.tags.joinToString(",")}]"
                val type = if (m.type == "fact") "" else "(${m.type}) "
                appendLine("- $type${m.content.take(capPerItem)}$tag")
            }
        }.trimEnd()
    }

    companion object {
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
