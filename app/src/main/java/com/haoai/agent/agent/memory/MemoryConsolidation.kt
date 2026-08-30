package com.haoai.agent.agent.memory

import com.haoai.agent.agent.provider.ApiMessage
import com.haoai.agent.agent.provider.SseEvent
import com.haoai.agent.data.HaoJson
import com.haoai.agent.data.ProviderConfig
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 记忆固化（上游 "dreaming" 的本地安全版）：
 * 规则层（零成本，始终执行）：
 * 1) 每日日志中 importance>=4 的条目晋升为长期 event 记忆
 * 2) 过期日志清理（默认保留 7 天）
 * 3) 长期库去重整理
 * 深度梦境层（可选，端侧小模型执行，零云端 token）：
 * 4) 用本地模型对长期记忆做语义去重与合并，解决"不同措辞的近重复"问题；
 *    模型不可用或输出不合法时自动回退纯规则，绝不因 AI 输出损坏记忆库。
 */
object MemoryConsolidation {

    data class Report(
        val promoted: Int,
        val expired: Int,
        val tidied: Int,
        val deepMerged: Int = 0,
        val promotedByUse: Int = 0,
        val conflictResolved: Int = 0
    ) {
        fun describe(): String = when {
            promoted == 0 && expired == 0 && tidied == 0 && deepMerged == 0 &&
                promotedByUse == 0 && conflictResolved == 0 -> "没有需要固化的内容"
            else -> buildString {
                append("已固化：$promoted 条重要动态晋升长期记忆")
                if (promotedByUse > 0) append("，$promotedByUse 条高频记忆升级重要度")
                if (conflictResolved > 0) append("，$conflictResolved 条冲突记忆更新")
                if (deepMerged > 0) append("，深度梦境合并 $deepMerged 条冗余")
                if (tidied > 0) append("，规则整理 $tidied 条")
                append("，清理过期日志 $expired 条")
            }
        }
    }

    const val PROMOTE_THRESHOLD = 4
    const val KEEP_DAYS = 7

    /** 使用次数晋升阈值：被反复检索/注入的高频 imp=3 记忆也能升级（痛点：imp=3 永不晋升）。 */
    const val PROMOTE_USE_COUNT = 20

    /** 规则固化（快速、零成本）。 */
    fun run(bank: MemoryBank, journal: DailyJournal): Report {
        val promoted = promoteFromJournal(bank, journal)
        val promotedByUse = promoteByUseCount(bank)
        val expired = journal.expire(KEEP_DAYS)
        val tidied = bank.tidy()
        return Report(promoted, expired, tidied, promotedByUse = promotedByUse)
    }

    /** 深度梦境：规则固化 + 端侧 LLM 语义去重合并 + 近冲突裁决。LLM 失败时静默回退规则结果。 */
    suspend fun runDeep(
        bank: MemoryBank,
        journal: DailyJournal,
        client: com.haoai.agent.agent.provider.ProviderClient,
        provider: ProviderConfig,
        apiKey: String
    ): Report {
        val promoted = promoteFromJournal(bank, journal)
        val promotedByUse = promoteByUseCount(bank)
        val expired = journal.expire(KEEP_DAYS)
        var deepMerged = 0
        runCatching { llmDedup(bank, client, provider, apiKey) }
            .onSuccess { deepMerged = it }
        var conflictResolved = 0
        runCatching { llmConflicts(bank, client, provider, apiKey) }
            .onSuccess { conflictResolved = it }
        val tidied = bank.tidy()
        return Report(promoted, expired, tidied, deepMerged, promotedByUse, conflictResolved)
    }

    private fun promoteFromJournal(bank: MemoryBank, journal: DailyJournal): Int {
        val existing = bank.all().mapTo(HashSet()) { it.content }
        var promoted = 0
        for (day in journal.allDays()) {
            for (e in day.items) {
                if (e.importance < PROMOTE_THRESHOLD) continue
                if (!existing.add(e.content)) continue
                bank.remember(e.content, listOf(day.date), type = "event", importance = e.importance, source = "consolidation")
                promoted++
            }
        }
        return promoted
    }

    /** 高频使用晋升：useCount 达标的记忆 importance+1（封顶 5）。 */
    private fun promoteByUseCount(bank: MemoryBank): Int {
        var n = 0
        for (m in bank.all()) {
            if (m.useCount >= PROMOTE_USE_COUNT && m.importance < 5) {
                if (bank.updateContent(m.id, m.content, m.importance + 1)) n++
            }
        }
        return n
    }

    /**
     * 端侧 LLM 去重合并。安全约束：
     * - 只允许操作已存在的 id；删除+合并源不超过总条数的 40%
     * - 合并内容必须自包含且 ≤300 字符
     * - 解析失败/越界一律忽略对应条目
     * @return 被折叠掉的条数
     */
    private suspend fun llmDedup(
        bank: MemoryBank,
        client: com.haoai.agent.agent.provider.ProviderClient,
        provider: ProviderConfig,
        apiKey: String
    ): Int {
        val items = bank.all()
        if (items.size < 4) return 0
        val listing = items.take(80).joinToString("\n") { m ->
            "${m.id}|${m.type}|${m.importance}|${m.content.take(100)}"
        }
        val buf = StringBuilder()
        client.chatStream(
            provider, apiKey,
            listOf(
                ApiMessage(role = "system", content = DEDUP_SYSTEM),
                ApiMessage(role = "user", content = listing)
            ),
            emptyList()
        ).collect { ev -> if (ev is SseEvent.Delta) buf.append(ev.text) }
        currentCoroutineContext().ensureActive()

        val text = buf.toString()
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return 0
        val obj = runCatching {
            HaoJson.json.parseToJsonElement(text.substring(start, end + 1)).jsonObject
        }.getOrNull() ?: return 0

        val validIds = items.map { it.id }.toHashSet()
        val maxRemovals = (items.size * 40 / 100).coerceAtLeast(2)
        val removeIds = linkedSetOf<String>()
        val merges = mutableListOf<Triple<List<String>, String, Pair<String, Int>>>()

        runCatching {
            obj["removes"]?.jsonArray?.forEach { el ->
                val id = el.jsonPrimitive.contentOrNull?.trim() ?: return@forEach
                if (id in validIds) removeIds.add(id)
            }
            obj["merges"]?.jsonArray?.forEach { el ->
                val m = el as? JsonObject ?: return@forEach
                val ids = runCatching {
                    m["ids"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull?.trim() }
                }.getOrNull() ?: return@forEach
                val content = m["content"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                if (ids.isEmpty() || ids.any { it !in validIds } || content.isEmpty() || content.length > 300) {
                    return@forEach
                }
                val type = (m["type"]?.jsonPrimitive?.contentOrNull ?: "fact").take(20)
                val importance = m["importance"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()?.coerceIn(1, 5) ?: 3
                merges.add(Triple(ids, content, type to importance))
            }
        }

        // 应用（限额保护）
        var folded = 0
        for ((ids, content, meta) in merges) {
            val budgetLeft = maxRemovals - removeIds.size - folded
            if (budgetLeft <= 0) break
            if (ids.size - 1 > budgetLeft) continue
            bank.removeIds(ids)
            bank.remember(content, type = meta.first, importance = meta.second)
            folded += ids.size - 1
        }
        if (removeIds.isNotEmpty()) {
            val allowed = removeIds.take((maxRemovals - folded).coerceAtLeast(0))
            folded += bank.removeIds(allowed)
        }
        return folded
    }

    val DEDUP_SYSTEM = """
        [MEMORY-DEDUP] 你是记忆整理器。输入是助理的长期记忆列表，每行格式：id|类型|重要性|内容。
        任务：找出语义重复或高度相似（同一事实的不同措辞）的条目，合并为一条更简洁准确的表述；删除明显过时或无价值的条目。
        只输出一个 JSON 对象，格式：
        {"merges":[{"ids":["id1","id2"],"content":"合并后的一句话","type":"fact","importance":3}],"removes":["id9"]}
        要求：
        - 宁缺毋滥：不确定是否重复就不要动；不得虚构或引入列表之外的新信息
        - type 只能取 fact/preference/decision/event；importance 取 1-5
        - content 必须自包含（脱离上下文也能看懂）
        - 最多 10 个 merge、5 个 remove；没有可整理的内容就输出 {}
    """.trimIndent()

    /**
     * 近冲突裁决（Mem0 ADD/UPDATE/DELETE 思想的固化期落地）：
     * 把词面相近但表述矛盾的条目对交给模型，裁决谁覆盖谁或合并。
     * 安全约束与 llmDedup 一致：只动已存在的 id、操作量限额、失败静默跳过。
     * @return 实际处置的冲突对数
     */
    private suspend fun llmConflicts(
        bank: MemoryBank,
        client: com.haoai.agent.agent.provider.ProviderClient,
        provider: ProviderConfig,
        apiKey: String
    ): Int {
        val items = bank.all()
        val byId = items.associateBy { it.id }
        // 收集近冲突对（去重），最多 8 对送审
        val pairs = linkedSetOf<Pair<String, String>>()
        for (m in items) {
            for (c in bank.nearConflicts(m.content)) {
                if (c.id != m.id) pairs.add(if (m.id < c.id) m.id to c.id else c.id to m.id)
                if (pairs.size >= 8) break
            }
            if (pairs.size >= 8) break
        }
        if (pairs.isEmpty()) return 0
        val listing = pairs.mapNotNull { (a, b) ->
            val ma = byId[a] ?: return@mapNotNull null
            val mb = byId[b] ?: return@mapNotNull null
            "A=${ma.id}|${ma.importance}|${ma.content.take(120)}\nB=${mb.id}|${mb.importance}|${mb.content.take(120)}"
        }.joinToString("\n")
        if (listing.isBlank()) return 0
        val buf = StringBuilder()
        client.chatStream(
            provider, apiKey,
            listOf(
                ApiMessage(role = "system", content = CONFLICT_SYSTEM),
                ApiMessage(role = "user", content = listing)
            ),
            emptyList()
        ).collect { ev -> if (ev is SseEvent.Delta) buf.append(ev.text) }
        currentCoroutineContext().ensureActive()

        val text = buf.toString()
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return 0
        val obj = runCatching {
            HaoJson.json.parseToJsonElement(text.substring(start, end + 1)).jsonObject
        }.getOrNull() ?: return 0

        var resolved = 0
        runCatching {
            obj["decisions"]?.jsonArray?.forEach { el ->
                val d = el as? JsonObject ?: return@forEach
                val a = d["a"]?.jsonPrimitive?.contentOrNull?.trim() ?: return@forEach
                val b = d["b"]?.jsonPrimitive?.contentOrNull?.trim() ?: return@forEach
                val action = d["action"]?.jsonPrimitive?.contentOrNull?.trim() ?: "keep"
                if (a !in byId || b !in byId) return@forEach
                when (action) {
                    "supersede_a" -> if (bank.supersede(a, b)) resolved++
                    "supersede_b" -> if (bank.supersede(b, a)) resolved++
                    "merge" -> {
                        val content = d["content"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                        if (content.isEmpty() || content.length > 300) return@forEach
                        val type = (d["type"]?.jsonPrimitive?.contentOrNull ?: "fact").take(20)
                        val importance = d["importance"]?.jsonPrimitive?.contentOrNull
                            ?.toIntOrNull()?.coerceIn(1, 5) ?: 3
                        bank.removeIds(listOf(a, b))
                        bank.remember(content, type = type, importance = importance, source = "consolidation")
                        resolved++
                    }
                    else -> {} // keep：模型不确定时不动
                }
            }
        }
        return resolved
    }

    val CONFLICT_SYSTEM = """
        [MEMORY-CONFLICT] 你是记忆裁决器。输入是助理长期记忆中若干对"词面相近但可能矛盾"的记忆（A/B 各一行）。
        任务：判断每对是否真的互相矛盾或过时——例如"用 Vim"与"改用 VS Code"，新信息应覆盖旧信息。
        只输出一个 JSON 对象，格式：
        {"decisions":[{"a":"idA","b":"idB","action":"keep|supersede_a|supersede_b|merge","content":"合并后的一句话","type":"fact","importance":3}]}
        动作含义：
        - keep：不矛盾或不确定，什么都不做（宁缺毋滥，默认选这个）
        - supersede_a：B 是更新的情况，A 已过时
        - supersede_b：A 仍然成立，B 是误记或重复
        - merge：两条各有部分正确，用 content 给出合并后的一句话（type 取 fact/preference/decision/event，importance 1-5）
        - 只裁决输入中出现的 id，不得虚构；没有需要处理的就输出 {"decisions":[]}
    """.trimIndent()
}
