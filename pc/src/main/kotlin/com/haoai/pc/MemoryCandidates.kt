package com.haoai.pc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * 记忆漏斗（B6，对标 Octop 的 Candidate 层的**简化版**）：
 *
 * `对话 →（LLM 抽取，引擎回合结束自动跑）→ 候选（pending）→（人在记忆页签
 * 晋升/拒绝）→ Memories 条目（进现有的注入与 tidy 语义）`。
 *
 * 为什么不照抄四层：Octop 的 RawEvent/Atom/EntityPage/Journal 每层都有独立存储与
 * GC —— 单机文件存储上那是四个能各自坏掉的东西。这里**候选是唯一新增层**：
 * 晋升后走 [Memories.add]（天然去重、重要度可升），拒绝留痕在候选列表里（status 可审）。
 *
 * 自动抽取的节流：回合结束 [MIN_GAP_MS] 内只抽一次 + 失败静默 ——
 * 抽取是额外的模型调用，卡着聊卡着抽等于每句话都多花一笔。
 */
data class MemCandidate(
    val id: String,
    /** fact | preference | decision | task —— 晋升时映射到 [Memories] 的类型。 */
    val type: String,
    val title: String,
    val assertion: String,
    val quote: String,
    val status: String = "pending",     // pending | promoted | rejected
    /** 产生它的会话工作区：晋升要写进那条工作区的记忆文件。 */
    val ws: String = "",
    val created: Long = System.currentTimeMillis()
)

object MemoryCandidates {
    /** 自动抽取的最小间隔：聊得再勤也最多 10 分钟一次。 */
    const val MIN_GAP_MS = 10 * 60_000L

    fun file(): File = File(Env.home, "memory-candidates.json")

    private class Store(var lastExtract: Long = 0, val items: MutableList<MemCandidate> = mutableListOf())

    private fun load(): Store = runCatching {
        val root = Json.parseToJsonElement(file().readText()).jsonObject
        val s = Store(root["lastExtract"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L)
        for (el in root["items"]?.jsonArray ?: JsonArray(emptyList())) {
            val o = runCatching { el.jsonObject }.getOrNull() ?: continue
            val id = o["id"]?.jsonPrimitive?.contentOrNull ?: continue
            s.items.add(
                MemCandidate(
                    id = id,
                    type = o["type"]?.jsonPrimitive?.contentOrNull ?: "fact",
                    title = o["title"]?.jsonPrimitive?.contentOrNull ?: "",
                    assertion = o["assertion"]?.jsonPrimitive?.contentOrNull ?: "",
                    quote = o["quote"]?.jsonPrimitive?.contentOrNull ?: "",
                    status = o["status"]?.jsonPrimitive?.contentOrNull ?: "pending",
                    ws = o["ws"]?.jsonPrimitive?.contentOrNull ?: "",
                    created = o["created"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: System.currentTimeMillis()
                )
            )
        }
        s
    }.getOrDefault(Store())

    private fun save(s: Store) {
        val body = buildString {
            append("""{"lastExtract":${s.lastExtract},"items":[""")
            s.items.forEachIndexed { i, c ->
                if (i > 0) append(',')
                append("""{"id":${q(c.id)},"type":${q(c.type)},"title":${q(c.title)},""" +
                    """"assertion":${q(c.assertion)},"quote":${q(c.quote)},"status":${q(c.status)},""" +
                    """"ws":${q(c.ws)},"created":${c.created}}""")
            }
            append("]}")
        }
        runCatching { file().parentFile?.mkdirs(); file().writeText(body) }
    }

    fun pending(): List<MemCandidate> = load().items.filter { it.status == "pending" }.sortedByDescending { it.created }

    fun all(limit: Int = 80): List<MemCandidate> = load().items.sortedByDescending { it.created }.take(limit)

    fun lastExtractAt(): Long = load().lastExtract

    /** 抽取写入：标题重复（normalize 后同文）的跳过 —— 每回合都抽到"用户在做 MBTI 测试"是刷屏不是记忆。 */
    fun addExtracted(items: List<MemCandidate>, episode: Pair<Int, String>?, sid: String): Int {
        val s = load()
        s.lastExtract = System.currentTimeMillis()
        var n = 0
        for (c in items) {
            val key = c.assertion.trim().lowercase()
            if (key.isEmpty()) continue
            if (s.items.any { it.assertion.trim().lowercase() == key && it.status != "rejected" }) continue
            s.items.add(c.copy(id = "mc" + System.nanoTime().toString(16).take(8), ws = c.ws.ifBlank { sid }))
            n++
        }
        save(s)
        episode?.let { Episodes.append(it.first, it.second, sid) }
        return n
    }

    /**
     * 晋升：写进候选产生时的那个工作区的长期记忆。返回 true=进了记忆。
     * 类型映射保守：Memories 的类型表没有 task，任务型落 fact（宁可归宽也别丢）。
     */
    fun promote(id: String): Boolean {
        val s = load()
        val c = s.items.firstOrNull { it.id == id && it.status == "pending" } ?: return false
        val ws = File(c.ws).takeIf { it.isDirectory } ?: return false
        val type = when (c.type) {
            "preference" -> Memories.TYPE_PREF
            "decision" -> Memories.TYPE_DECISION
            else -> Memories.TYPE_FACT
        }
        runCatching {
            val f = Memories.fileFor(ws)
            val doc = Memories.load(f)
            Memories.add(doc, c.assertion, type = type, importance = 4, source = "candidate")
            // add 只改内存里的 doc —— 不 save 就是"晋升成功了但重启又没了"
            Memories.save(f, doc)
        }.getOrElse { return false }
        s.items[s.items.indexOfFirst { it.id == id }] = c.copy(status = "promoted")
        save(s)
        return true
    }

    fun reject(id: String): Boolean {
        val s = load()
        val i = s.items.indexOfFirst { it.id == id && it.status == "pending" }
        if (i < 0) return false
        s.items[i] = s.items[i].copy(status = "rejected")
        save(s)
        return true
    }

    /** 抽取提示：只要 JSON，没得抽就空数组——明确告诉模型"空着是合法答案"。 */
    fun extractPrompt(userMsgs: List<String>): String = buildString {
        appendLine("你是记忆整理员。读下面最近的用户发言，挑出**值得长期记住**的（事实/偏好/决定），")
        appendLine("没有就输出空数组。规矩：")
        appendLine("1) 只记用户明确表达的，不猜、不外推；")
        appendLine("2) 每条 assertion 用一句完整的话（谁/什么/何时），可独立成句；")
        appendLine("3) quote 抄原文里最能支撑它的那半句（≤60字）；")
        appendLine("4) type 只认 fact/preference/decision/task 四种；title 是 12 字内的标签；")
        appendLine("5) 可选 episode：如果这轮发言带明显情绪/事件，给 {\"mood\":1-5,\"summary\":\"≤60字第三人称\"}。")
        appendLine("只输出 JSON，形状：{\"candidates\":[{type,title,assertion,quote}],\"episode\":{mood,summary}|null}")
        appendLine("---- 用户发言 ----")
        userMsgs.forEach { appendLine(it.take(600)) }
    }

    /** 宽松解析：模型爱加围栏/前缀，取第一对大括号包住的对象再拆。 */
    fun parseExtract(raw: String): Pair<List<MemCandidate>, Pair<Int, String>?> = runCatching {
        val start = raw.indexOf('{'); val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return emptyList<MemCandidate>() to null
        val o = Json.parseToJsonElement(raw.substring(start, end + 1)).jsonObject
        val cands = o["candidates"]?.jsonArray?.mapNotNull { el ->
            val c = runCatching { el.jsonObject }.getOrNull() ?: return@mapNotNull null
            val assertion = c["assertion"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            if (assertion.isBlank()) return@mapNotNull null
            MemCandidate(
                id = "",
                type = (c["type"]?.jsonPrimitive?.contentOrNull ?: "fact").lowercase(),
                title = c["title"]?.jsonPrimitive?.contentOrNull ?: assertion.take(12),
                assertion = assertion.take(400),
                quote = (c["quote"]?.jsonPrimitive?.contentOrNull ?: "").take(120)
            )
        } ?: emptyList()
        val ep = o["episode"]?.let { e ->
            val eo = runCatching { e.jsonObject }.getOrNull() ?: return@let null
            val mood = (eo["mood"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()?.coerceIn(1, 5) ?: return@let null
            val summary = eo["summary"]?.jsonPrimitive?.contentOrNull?.take(160) ?: return@let null
            mood to summary
        }
        cands to ep
    }.getOrDefault(emptyList<MemCandidate>() to null)

    private fun q(s: String): String = "\"" +
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "") + "\""
}

/**
 * 情绪日记（B8，Octop Episode 的简化版）：一行一段，jsonl 追加——
 * 日记天然是 append-only，拆表反而要为"改"发明不存在的需求。
 */
object Episodes {
    fun file(): File = File(Env.home, "memory-episodes.jsonl")

    fun append(mood: Int, summary: String, sid: String) {
        runCatching {
            file().parentFile?.mkdirs()
            file().appendText(
                """{"ts":${System.currentTimeMillis()},"mood":${mood.coerceIn(1, 5)},""" +
                    """"summary":${q(summary)},"sid":${q(sid)}}""" + "\n"
            )
        }
    }

    fun list(limit: Int = 30): List<String> = runCatching {
        file().readLines().filter { it.isNotBlank() }.takeLast(limit).reversed()
    }.getOrDefault(emptyList())

    /** 近几天的日记（主动关心挑素材用）。 */
    fun recent(days: Int): List<String> = runCatching {
        val cut = System.currentTimeMillis() - days * 86_400_000L
        file().readLines().mapNotNull { line ->
            val ts = Regex(""""ts":(\d+)""").find(line)?.groupValues?.get(1)?.toLongOrNull()
            if (ts != null && ts >= cut) line else null
        }
    }.getOrDefault(emptyList())

    private fun q(s: String): String = "\"" +
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "") + "\""
}

/**
 * 用户画像（B8 简化版）：`HAOAI_HOME/memory-profile.md`，晋升攒够后由 LLM 重渲染。
 * `## My Notes` 段**保留用户手写**：渲染只替换段外的生成区（Octop 同款约定）。
 * 注入走系统提示的独立节，与记忆注入分开 —— 画像常驻、记忆按轮次挑。
 */
object MemoryProfile {
    fun file(): File = File(Env.home, "memory-profile.md")

    fun read(): String = runCatching { if (file().isFile) file().readText() else "" }.getOrDefault("")

    fun write(text: String) {
        runCatching { file().parentFile?.mkdirs(); file().writeText(text.trim() + "\n") }
    }

    /** 用户手写段：没有就造一个空的，渲染时原样拼回。 */
    fun userNotes(): String {
        val t = read()
        val i = t.indexOf("## My Notes")
        return if (i >= 0) t.substring(i) else "## My Notes\n（这里可以自己手写补充，重生成画像时这段原样保留）"
    }

    /** [ws] = 画像所属的会话工作区（长期记忆按工作区分文件，画像取它重渲染）。 */
    fun renderPrompt(ws: File): String = buildString {
        appendLine("根据下面的长期记忆条目，生成一份用户画像（markdown，400 字内）。要求：")
        appendLine("分三节：## 我是谁（用户的身份/环境，没有就省略）、## 偏好、## 近期关注；")
        appendLine("只写条目里有的，不脑补；条目稀少就如实写少。最后另起一行放 `## My Notes` 并保留：")
        appendLine(userNotes())
        appendLine("---- 记忆条目 ----")
        val doc = runCatching { Memories.load(Memories.fileFor(ws)) }.getOrNull()
        val items = doc?.items ?: emptyList()
        items.sortedByDescending { it.importance }.take(80).forEach {
            appendLine("- [${it.type}] ${it.content}")
        }
        if (items.isEmpty()) appendLine("（还没有条目）")
    }
}
