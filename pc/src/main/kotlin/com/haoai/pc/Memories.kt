package com.haoai.pc

import java.io.File
import kotlin.math.exp

/**
 * 条目化的长期记忆 —— 与手机端**同一个文件、同一种行格式**。
 *
 * 为什么不做成"整文件编辑"（PC 原先那样）：整文件的 MEMORY 有两个治不好的病 ——
 * ① 一粘贴就把已有内容盖掉；② 没法"只把相关的几条塞进提示"，只能整段贴进去，
 * 于是记忆越多越贵、越贵越不敢加。条目化之后才有搜索、去重、按重要度与新鲜度挑 k 条注入。
 *
 * **对端是 `app/src/main/java/com/haoai/agent/agent/memory/MemoryBank.kt`**（那边没有单测钉住格式，
 * 所以那份文件就是唯一规格）。一行一条：
 *
 *     - [c4a9e667 · 重要度4] 内容压成一行 <!-- id:c4a9e667 imp:4 type:event created:1… lastUsed:0 uses:0 -->
 *
 * 分节标题两边都得认：`## 偏好（n）` / `## 决定（n）` / `## 事件（n）` / `## 事实（n）` /
 * `## 已归档（n）` / `## 其他`。解析不了的行**进 [Doc.extras] 原样写回去**，绝不静默丢 ——
 * 这条是手机端 `MemoryState.extras` 的同一口径：一个工具不该把人写的东西弄没。
 *
 * 文件位置是**工作区的 `MEMORY.md`**（手机端就写在那儿），与 `HAOAI_HOME/MEMORY.md`
 * 那份"跨项目自由文本"是两回事，别混：后者由 [Memory] 整文件读，前者由这里按条目读写。
 */
object Memories {

    const val TYPE_FACT = "fact"
    const val TYPE_PREF = "preference"
    const val TYPE_DECISION = "decision"
    const val TYPE_EVENT = "event"
    const val MAX_ITEMS = 200
    const val MAX_CHARS = 500
    const val INJECT_K = 8
    const val INJECT_CAP = 1200
    const val PER_ITEM_CAP = 180
    const val ARCHIVED = "archived"
    /** 与手机端同名（`MemoryBank.DORMANT`）：自动降级的标记，两边都不注入、但文件里还看得到。 */
    const val DORMANT = "dormant"
    const val STALE_DAYS = 30L
    const val DUP_SIMILARITY = 0.85
    /** 一条记忆每小时最多记一次"用过"，与手机端同一口径：常驻注入不该自我强化转正。 */
    const val USE_HOURS_MS = 3_600_000L

    private val TYPES = listOf(TYPE_PREF, TYPE_DECISION, TYPE_EVENT, TYPE_FACT)
    private val HEAD = linkedMapOf(
        TYPE_PREF to "偏好", TYPE_DECISION to "决定", TYPE_EVENT to "事件", TYPE_FACT to "事实"
    )
    private val LINE = Regex("""^-\s*\[([0-9a-fA-F]{1,8})\s*·\s*重要度(\d)]\s*(.*)$""")
    private val META = Regex("""([A-Za-z]+):(\S+)""")
    private val VIS_TAGS = Regex("""\s*\[([^\]]{1,60})]\s*$""")
    /** [parse] 会落成字段的元数据键；其余的进 [Item.rawMeta] 原样带回去。 */
    private val KNOWN_KEYS = setOf("id", "imp", "type", "created", "lastused", "uses",
        "uq", "src", "org", "upd", "sup", "tags")

    data class Item(
        val id: String,
        var content: String,
        var importance: Int = 3,
        var type: String = TYPE_FACT,
        var createdAt: Long = System.currentTimeMillis(),
        var lastUsedAt: Long = 0,
        var useCount: Int = 0,
        var uniqQueries: Int = 0,
        var source: String? = null,
        var origin: String = "agent",
        var updatedAt: Long = 0,
        var supersededBy: String? = null,
        var tags: List<String> = emptyList(),
        /**
         * 这边**不认识**的元数据键，按原样带着走。
         *
         * 两端共用一个文件，谁都没有"我读不懂的字段可以扔"这个权力：手机端会写 `rc:1`
         * （被注入过，防 autoExtract 把老事实当新记忆再提取一遍），PC 早期版本不认识它，
         * 一保存就把手机端的防召回环标记擦掉了。所以未知键原样保留、原样写回。
         */
        var rawMeta: List<Pair<String, String>> = emptyList()
    ) {
        /** 与手机端同一口径：被取代的、以及非 agent/用户来源的，都不进提示。 */
        val live: Boolean get() = supersededBy == null && origin != "untrusted" && origin != "system"
    }

    class Doc(
        val items: MutableList<Item> = mutableListOf(),
        val extras: MutableList<String> = mutableListOf(),
        /** 这份文件是哪一个工作区的（绝对路径），只当作查询指纹的命名空间用。 */
        val key: String = ""
    ) {
        val active: List<Item> get() = items.filter { it.live }
    }

    fun fileFor(workspace: File): File = File(workspace, "MEMORY.md")

    fun load(f: File): Doc =
        if (!f.isFile) Doc(key = f.absolutePath)
        else parse(runCatching { f.readText(Charsets.UTF_8) }.getOrDefault(""), f.absolutePath)

    /** 读：分节标题决定默认 type，注释里的元数据是权威（可见文字只是给人看的）。 */
    fun parse(text: String, key: String = ""): Doc {
        val doc = Doc(key = key)
        var type = TYPE_FACT
        var archived = false
        var other = false
        val bodies = mutableListOf<String>()
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.startsWith("## ")) {
                val h = line.substring(3).substringBefore('（').substringBefore('(').trim()
                archived = h == "已归档"
                other = h == "其他"
                HEAD.entries.firstOrNull { it.value == h }?.let { type = it.key }
                continue
            }
            if (line.isEmpty() || (line.startsWith("#") && !line.startsWith("- "))) continue
            if (!line.startsWith("- ")) { if (other) doc.extras += line; continue }
            val m = LINE.find(line)
            if (m == null) { doc.extras += line; continue }
            val body = m.groupValues[3]
            bodies += line
            val (vis, meta) = if (body.contains("<!--"))
                body.substringBefore("<!--").trim() to META.findAll(body.substringAfter("<!--")).toList()
            else body to emptyList()
            val mm = meta.associate { it.groupValues[1].lowercase() to it.groupValues[2] }
            val tags = mm["tags"]?.split(',', '，')?.map { it.trim() }?.filter { it.isNotEmpty() }
                ?: VIS_TAGS.find(vis)?.groupValues?.get(1)?.split(',', '，')
                    ?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
            val content = VIS_TAGS.find(vis)?.let { m2 ->
                vis.removeRange(m2.range).trim()
            } ?: vis.trim()
            val id = mm["id"]?.takeIf { it.matches(Regex("[0-9a-fA-F]{1,8}")) } ?: m.groupValues[1]
            doc.items += Item(
                id = newId(id),
                content = content.take(MAX_CHARS),
                importance = (mm["imp"]?.toIntOrNull() ?: m.groupValues[2].toIntOrNull() ?: 3).coerceIn(1, 5),
                type = mm["type"]?.takeIf { it in TYPES } ?: type,
                createdAt = mm["created"]?.toLongOrNull() ?: System.currentTimeMillis(),
                lastUsedAt = mm["lastused"]?.toLongOrNull() ?: 0L,
                useCount = mm["uses"]?.toIntOrNull() ?: 0,
                uniqQueries = mm["uq"]?.toIntOrNull() ?: 0,
                source = mm["src"]?.takeIf { it != "null" },
                origin = mm["org"]?.takeIf { it.isNotBlank() } ?: "agent",
                updatedAt = mm["upd"]?.toLongOrNull() ?: 0L,
                supersededBy = (if (archived) mm["sup"] ?: ARCHIVED else mm["sup"]?.takeIf { it.isNotBlank() }),
                tags = tags.take(6),
                rawMeta = meta.map { p -> p.groupValues[1].lowercase() to p.groupValues[2] }
                    .filter { !KNOWN_KEYS.contains(it.first) }.distinct()
            )
        }
        return doc
    }

    /** 写：手机端能原样读回来的形状。 */
    fun render(doc: Doc): String = buildString {
        appendLine("# 长期记忆")
        appendLine()
        appendLine("<!-- 一行一条。手机端 HaoAI 与电脑端 HaoAI 读写同一份，格式两边共用。 -->")
        for ((t, head) in HEAD) {
            val list = doc.items.filter { it.type == t && it.supersededBy == null }
            if (list.isEmpty()) continue
            appendLine()
            appendLine("## $head（${list.size}）")
            list.sortedByDescending { it.createdAt }.forEach { appendLine(lineOf(it)) }
        }
        val gone = doc.items.filter { it.supersededBy != null }
        if (gone.isNotEmpty()) {
            appendLine()
            appendLine("## 已归档（${gone.size}）")
            gone.forEach { appendLine(lineOf(it)) }
        }
        if (doc.extras.isNotEmpty()) {
            appendLine()
            appendLine("## 其他")
            doc.extras.forEach { appendLine(it) }
        }
    }.trimEnd() + "\n"

    private fun lineOf(it: Item): String {
        val meta = mutableListOf("id:${it.id}", "imp:${it.importance}", "type:${it.type}",
            "created:${it.createdAt}", "lastUsed:${it.lastUsedAt}", "uses:${it.useCount}")
        if (it.uniqQueries > 0) meta += "uq:${it.uniqQueries}"
        it.source?.takeIf { s -> s.isNotBlank() }?.let { s -> meta += "src:$s" }
        if (it.origin != "agent") meta += "org:${it.origin}"
        if (it.updatedAt > 0) meta += "upd:${it.updatedAt}"
        it.supersededBy?.let { s -> meta += "sup:$s" }
        if (it.tags.isNotEmpty()) meta += "tags:" + it.tags.joinToString(",")
        it.rawMeta.forEach { (k, v) -> meta += "$k:$v" }
        return "- [${it.id} · 重要度${it.importance}] ${flat(it.content)} <!-- ${meta.joinToString(" ")} -->"
    }

    fun save(f: File, doc: Doc): Boolean = runCatching {
        f.parentFile?.mkdirs()
        Env.atomicWrite(f, render(doc))
    }.isSuccess

    /** 加一条。返回 `true` = 新加，`false` = 与已有的一条归一化后完全相同（只抬重要度，不重复写）。 */
    fun add(doc: Doc, content: String, type: String = TYPE_FACT, importance: Int = 3,
            tags: List<String> = emptyList(), source: String? = null): Pair<Item, Boolean> {
        val text = flat(content).take(MAX_CHARS)
        val key = normalize(text)
        val hit = doc.items.firstOrNull { it.supersededBy == null && normalize(it.content) == key }
        if (hit != null) {
            if (importance > hit.importance) hit.importance = importance.coerceIn(1, 5)
            hit.lastUsedAt = System.currentTimeMillis()
            return hit to false
        }
        val it = Item(
            id = newId(null), content = text, importance = importance.coerceIn(1, 5),
            type = if (type in TYPES) type else TYPE_FACT,
            tags = tags.map { t -> t.trim() }.filter { t -> t.isNotEmpty() }.take(6),
            source = source?.take(20)
        )
        doc.items += it
        return it to true
    }

    fun update(doc: Doc, id: String, content: String, importance: Int? = null,
               type: String? = null): Boolean {
        val it = doc.items.firstOrNull { x -> x.id == id } ?: return false
        it.content = flat(content).take(MAX_CHARS)
        importance?.let { v -> it.importance = v.coerceIn(1, 5) }
        type?.takeIf { v -> v in TYPES }?.let { v -> it.type = v }
        it.updatedAt = System.currentTimeMillis()
        return true
    }

    /** 删：手机端是软取代（supersededBy），这样"忘掉"这件事本身还留痕，同步时也不会复活。 */
    fun forget(doc: Doc, id: String): Boolean {
        val it = doc.items.firstOrNull { x -> x.id == id && x.supersededBy == null } ?: return false
        it.supersededBy = ARCHIVED
        it.updatedAt = System.currentTimeMillis()
        return true
    }

    /**
     * 搜索：**搜不到就是空**。
     *
     * 第一版把注入那套"没有重合也按重要度给几条"的兜底搬了过来，结果界面上搜"gradle"
     * 会把三条毫不相干的记忆一起列出来 —— 对搜索框来说那不是"相关"，那是"没在听我说话"。
     * 注入（[inject]）没有查询词可比，按重要度与新鲜度兜底才是对的，两边不是一条路。
     */
    fun search(doc: Doc, query: String, k: Int = 20): List<Item> {
        val q = tokens(query)
        if (q.isEmpty()) return doc.items.sortedByDescending { it.createdAt }.take(k)
        val now = System.currentTimeMillis()
        return doc.active.map { it to overlapOf(it, q) }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<Item, Int>> { it.second }
                .thenByDescending { it.first.importance }
                .thenByDescending { it.first.createdAt })
            .take(k).map { it.first }
    }

    private fun overlapOf(it: Item, q: Set<String>): Int =
        tokens(it.content + " " + it.tags.joinToString(" ")).count { t -> q.contains(t) }

    /**
     * 注入提示的那一段：按"重合度 + 重要度 + 新鲜度 + 用过几次"挑 k 条，
     * 并且**整条丢掉而不是截半条**（总字数超了就停）—— 半句话比没有这句话更容易被模型当成事实。
     * 与手机端同一组常数（k=8 / 单条 180 / 总 1200），这样两端看到的记忆量是同一个量级。
     */
    fun inject(doc: Doc, query: String?, k: Int = INJECT_K): String = injectIds(doc, query, k).first

    /**
     * 注入的那一段，外加**这次命中了哪几条**。
     *
     * 为什么要把 id 返回出来：使用反馈只能在"真的发给模型了"之后记（手机端同一口径：
     * 只估算上下文占用的调用不回写）。没有这个回环，打分里的 `min(uses,5)*0.2` 永远是 0，
     * 常用的一条永远升不上去、久不用的一条也永远降不下来 —— 记忆库会退化成"按写入顺序取前 8 条"。
     */
    fun injectIds(doc: Doc, query: String?, k: Int = INJECT_K): Pair<String, List<String>> {
        val pool = doc.active
        if (pool.isEmpty()) return "" to emptyList()
        val q = tokens(query ?: "")
        val now = System.currentTimeMillis()
        val picked = mutableListOf<Item>()
        var used = 0
        for ((it, _) in pool.map { i -> i to score(i, q, now) }
            .sortedWith(compareByDescending<Pair<Item, Double>> { it.second }.thenByDescending { it.first.createdAt })) {
            if (picked.size >= k) break
            if (picked.any { p -> jaccard(tokens(p.content), tokens(it.content)) > 0.6 } && q.isNotEmpty()) continue
            val body = it.content.take(PER_ITEM_CAP)
            if (used + body.length > INJECT_CAP && picked.isNotEmpty()) break
            picked += it
            used += body.length
        }
        if (picked.isEmpty()) return "" to emptyList()
        return picked.joinToString("\n") {
            val age = (now - (if (it.lastUsedAt > 0) it.lastUsedAt else it.createdAt)) / 86_400_000.0
            val stale = if (age > 60) "（${(age / 30).toInt()} 个月前记录，可能已过时）" else ""
            "- [重要度${it.importance}] ${it.content.take(PER_ITEM_CAP)}$stale"
        } to picked.map { it.id }
    }

    /**
     * 记一次"这几条被用过了"。返回 `true` = 有计数变化（才值得写文件）。
     *
     * 三条与手机端对齐的口径，少一条都会让计数变成噪音：
     * ① **每小时最多 +1**：注入是每轮都做的事，不限流就等于"谁常驻谁转正"；
     * ② `uq` 只数**没见过的查询指纹**（同一句话反复问 100 次算 1 次），指纹集合每条目限 32 个；
     * ③ 打上 `rc:1`（手机端靠它防"把召回来的老事实再提取一遍成新记忆"）。
     */
    fun markUsed(doc: Doc, ids: Collection<String>, query: String?,
                 now: Long = System.currentTimeMillis()): Boolean {
        if (ids.isEmpty()) return false
        val fp = fingerprint(query)
        var changed = false
        for (id in ids) {
            val it = doc.items.firstOrNull { x -> x.id == id && x.live } ?: continue
            if (fp != null) {
                val seen = seenQueries.getOrPut(doc.key + "#" + id) { LinkedHashSet() }
                if (seen.add(fp)) {
                    if (seen.size > 32) seen.remove(seen.first())
                    it.uniqQueries += 1
                    changed = true
                }
            }
            if (now - it.lastUsedAt > USE_HOURS_MS) {
                it.lastUsedAt = now
                it.useCount += 1
                changed = true
            }
            if (it.rawMeta.none { p -> p.first == "rc" }) {
                it.rawMeta = it.rawMeta + ("rc" to "1")
                changed = true
            }
        }
        return changed
    }

    /**
     * 真发出去一次之后的回写：**重新读一遍文件**，只在最新那份上记使用计数。
     *
     * 不拿调用方内存里那份盖回去 —— 两端共用一个文件，手机上刚刚新增或改掉的一条
     * 会被这一写抹掉，而"记忆自己会丢"是比"少记一次使用"严重得多的故障。
     * 重读之后按 id 找，找不到就跳过（那一条可能已被手机端合并或忘掉）。
     */
    fun bumpUsage(f: File, ids: Collection<String>, query: String?,
                  now: Long = System.currentTimeMillis()): Boolean {
        val doc = load(f)
        return markUsed(doc, ids, query, now) && save(f, doc)
    }

    /** 整理一次的报告：三堆**都是将要动谁**，不是已经动了谁（预览用同一份）。 */
    class Tidy(
        val degraded: List<Item> = emptyList(),
        val merged: List<Item> = emptyList(),
        val purged: List<Item> = emptyList()
    ) {
        val changed: Int get() = degraded.size + merged.size + purged.size
    }

    /**
     * 整理：降级 / 合重 / 清掉过期失效。与手机端 `MemoryBank.tidy()` 同一组判据
     * （重要度<=2 且 30 天没用 → `dormant`；归一化相同或词面 Jaccard>0.85 → 合到一条；
     * 失效满 30 天物理删除），**一处刻意不同**：
     *
     * 手机端合并是直接从文件里删掉，这边改成"指到保留那条的 id 上"（`sup:<keeper>`）。
     * 手机读这种行完全没问题（本来就是这个语义），而误合的那条还能回看、能改回来 ——
     * 自动整理跑在共享文件上，可逆性本身是判据。
     *
     * 因此这里是**手动一次**而不是每轮自动：两端都开自动整理，会把对方的整理当成新内容再整理一遍。
     */
    fun tidy(doc: Doc, now: Long = System.currentTimeMillis()): Tidy {
        val day = 86_400_000.0
        val degraded = mutableListOf<Item>()
        for (it in doc.items) {
            if (it.supersededBy != null || it.importance > 2) continue
            val ref = if (it.lastUsedAt > 0) it.lastUsedAt else it.createdAt
            if ((now - ref) / day > STALE_DAYS) {
                it.supersededBy = DORMANT
                it.updatedAt = now
                degraded += it
            }
        }
        val merged = mutableListOf<Item>()
        val kept = mutableListOf<Item>()
        for (it in doc.items.filter { x -> x.supersededBy == null }
            .sortedWith(compareByDescending<Item> { it.importance }.thenByDescending { it.createdAt })) {
            val norm = normalize(it.content)
            val toks = tokens(it.content)
            val hit = kept.firstOrNull { k ->
                normalize(k.content) == norm ||
                    (norm.isNotEmpty() && jaccard(toks, tokens(k.content)) > DUP_SIMILARITY)
            }
            if (hit == null) kept += it else {
                it.supersededBy = hit.id
                it.updatedAt = now
                merged += it
            }
        }
        val purged = doc.items.filter {
            val sup = it.supersededBy ?: return@filter false
            sup != it.id && (now - (if (it.updatedAt > 0) it.updatedAt else it.createdAt)) / day > STALE_DAYS
        }
        doc.items.removeAll(purged)
        return Tidy(degraded, merged, purged)
    }

    /** 查询指纹：与手机端 `MemoryBank.fingerprint` 同一个算法，否则两边的 `uq` 不可比。 */
    private fun fingerprint(q: String?): String? {
        val s = q?.trim()?.lowercase() ?: return null
        if (s.isEmpty()) return null
        return Integer.toHexString(s.take(160).hashCode())
    }

    private val seenQueries = java.util.concurrent.ConcurrentHashMap<String, LinkedHashSet<String>>()

    fun json(doc: Doc, query: String = ""): String {
        val list = if (query.isBlank()) doc.items.sortedByDescending { it.createdAt }
        else search(doc, query, 60)
        return list.joinToString(",", "[", "]") { x ->
            val sup = x.supersededBy
            """{"id":${quote(x.id)},"content":${quote(x.content)},"imp":${x.importance},""" +
                """"type":${quote(x.type)},"created":${x.createdAt},"uses":${x.useCount},""" +
                """"tags":${quote(x.tags.joinToString(","))},""" +
                """${if (sup == null) "" else """"archived":${quote(sup)},"""}""" +
                """"active":${x.live}}"""
        }
    }

    private fun score(it: Item, q: Set<String>, now: Long): Double {
        val overlap = if (q.isEmpty()) 0.0 else tokens(it.content + " " + it.tags.joinToString(" "))
            .count { t -> q.contains(t) }.toDouble()
        val ageDays = (now - (if (it.lastUsedAt > 0) it.lastUsedAt else it.createdAt)) / 86_400_000.0
        return overlap * 2.5 + it.importance * 3.0 + 2.0 * exp(-ageDays / 14.0) +
            minOf(it.useCount, 5) * 0.2
    }

    /** 一行化：条目是"一行一条"的格式，换行会把一条拆成两条。 */
    private fun flat(s: String): String = s.replace(Regex("\\s+"), " ").trim()

    private fun normalize(s: String): String =
        s.lowercase().replace(Regex("""[\s，。、；：！？,.:;!?\u201c\u201d"'\[\]()（）]"""), "")

    private fun tokens(s: String): Set<String> {
        val out = mutableSetOf<String>()
        for (w in s.lowercase().split(Regex("""[^a-z0-9一-鿿]+"""))) {
            if (w.length < 2) continue
            if (w.length <= 24) out += w
            for (i in 0 until w.length - 1) {
                val a = w[i]; val b = w[i + 1]
                if (a in '一'..'鿿' && b in '一'..'鿿') out += w.substring(i, i + 2)
            }
        }
        return out
    }

    private fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val inter = a.count { b.contains(it) }.toDouble()
        return inter / (a.size + b.size - inter)
    }

    private fun newId(preferred: String?): String {
        if (preferred != null) return preferred
        var s: String
        do { s = java.util.UUID.randomUUID().toString().replace("-", "").take(8) } while (s.length < 8)
        return s
    }

    private fun quote(s: String): String = "\"" + s.replace("\\", "\\\\")
        .replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "").replace("\t", " ") + "\""
}
