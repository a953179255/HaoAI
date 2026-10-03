package com.haoai.pc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * 子智能体库（B5，对标 Octop 工作区里 agents 目录下的 md 定义）。
 *
 * 形状：**一个 md 一份定义** —— YAML 头（name/description/color/emoji）+ 正文即 system prompt。
 * 与技能同族但语义不同：技能是"做法的文档"（模型自己照着做），子智能体是"人"（整体派出去，
 * 中间过程不进主上下文，只回结论）。description 是**主 agent 选人的唯一依据**（Octop 同款硬约束：
 * 缺 description 的文件直接跳过）。
 *
 * 分两层：`HAOAI_HOME/subagents/<slug>.md` 是用户装的/建的（真源）；
 * 随包库在 classpath `builtin-subagents/<division>/<slug>.md`（只读），
 * 首启经 [installBuiltin] 拷进真源 —— 老规矩：jar 里列不了目录，靠 `index.json` 清单。
 * 归属专家的可用集在 [AgentConfig.subagents]（空 = 全部可用）。
 */
object Subagents {

    data class Def(
        val slug: String,
        val name: String,
        val description: String,
        val color: String = "",
        val emoji: String = "",
        val body: String = ""
    )

    fun dir(): File = File(Env.home, "subagents").apply { mkdirs() }

    fun fileOf(slug: String): File = File(dir(), "$slug.md")

    /**
     * 解析一份定义。frontmatter 只认 name/description/color/emoji 四个键；
     * **description 空 = 这份文件不算数**（没有它，主 agent 没法选人）。
     */
    fun parse(slug: String, text: String): Def? {
        val t = text.replace("\r\n", "\n").replace("\r", "\n")
        val lines = t.split('\n')
        if (lines.firstOrNull()?.trim() != "---") return null
        var name = ""; var desc = ""; var color = ""; var emoji = ""
        var i = 1
        while (i < lines.size && lines[i].trim() != "---") {
            val l = lines[i]
            when (l.substringBefore(':', "").trim().lowercase()) {
                "name" -> name = l.substringAfter(':', "").trim().removeSurrounding("\"")
                "description" -> desc = l.substringAfter(':', "").trim().removeSurrounding("\"")
                "color" -> color = l.substringAfter(':', "").trim().removeSurrounding("\"")
                "emoji" -> emoji = l.substringAfter(':', "").trim().removeSurrounding("\"")
            }
            i++
        }
        if (desc.isEmpty()) return null
        val body = lines.drop(minOf(i + 1, lines.size)).joinToString("\n").trim()
        return Def(slug, name.ifBlank { slug }, desc, color, emoji, body)
    }

    fun list(): List<Def> = runCatching {
        dir().listFiles { f -> f.isFile && f.name.endsWith(".md") }
            ?.sortedBy { it.name }
            ?.mapNotNull { f -> parse(f.name.removeSuffix(".md"), runCatching { f.readText() }.getOrDefault("")) }
            ?: emptyList()
    }.getOrDefault(emptyList())

    fun find(slug: String): Def? = list().firstOrNull { it.slug == slug }

    /** 按专家可用集过滤（空集 = 全部可用）。 */
    fun availableFor(ac: AgentConfig): List<Def> {
        val all = list()
        return if (ac.subagents.isEmpty()) all else all.filter { it.slug in ac.subagents }
    }

    /**
     * 首启把随包库拷进真源。幂等：装过的跳过，只补缺的。
     * 返回本次新装数量（界面显示"已内置 40 个"那类反馈用）。
     */
    fun installBuiltin(): Int = runCatching {
        val idx = javaClass.classLoader.getResourceAsStream("builtin-subagents/index.json")?.readBytes()?.toString(Charsets.UTF_8) ?: return 0
        val items = Json.parseToJsonElement(idx).jsonObject["items"]?.jsonArray ?: JsonArrayEmpty
        var n = 0
        for (el in items) {
            val o = runCatching { el.jsonObject }.getOrNull() ?: continue
            val slug = o["slug"]?.jsonPrimitive?.contentOrNull ?: continue
            val div = o["division"]?.jsonPrimitive?.contentOrNull ?: continue
            val dst = fileOf(slug)
            if (dst.isFile) continue
            val text = javaClass.classLoader
                .getResourceAsStream("builtin-subagents/$div/$slug.md")?.readBytes()?.toString(Charsets.UTF_8) ?: continue
            dst.writeText(text)
            n++
        }
        n
    }.getOrDefault(0)

    /** 新建/覆盖一份定义（枢纽的表单出口）。slug 只许 [slugify] 过的名字。 */
    fun save(slug: String, name: String, description: String, color: String, emoji: String, body: String): Boolean {
        if (!Regex("^[a-z0-9][a-z0-9-]{1,48}$").matches(slug)) return false
        if (description.isBlank() || body.isBlank()) return false
        val md = "---\nname: ${name.replace('\n', ' ')}\ndescription: ${description.replace('\n', ' ')}\n" +
            "color: $color\nemoji: $emoji\n---\n\n${body.trim()}\n"
        return runCatching { fileOf(slug).writeText(md) }.isSuccess
    }

    fun remove(slug: String): Boolean = runCatching { fileOf(slug).delete() }.getOrDefault(false)

    /** 随包库的分类标签（界面分组头用）：{division: {label, emoji, color}}。 */
    fun divisions(): Map<String, Map<String, String>> = runCatching {
        val text = javaClass.classLoader.getResourceAsStream("builtin-subagents/divisions.json")?.readBytes()?.toString(Charsets.UTF_8) ?: return emptyMap()
        val arr = Json.parseToJsonElement(text).jsonObject["divisions"]?.jsonArray ?: return emptyMap()
        arr.mapNotNull { el ->
            val o = runCatching { el.jsonObject }.getOrNull() ?: return@mapNotNull null
            val id = o["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            id to mapOf(
                "label" to (o["label"]?.jsonPrimitive?.contentOrNull ?: id),
                "emoji" to (o["emoji"]?.jsonPrimitive?.contentOrNull ?: ""),
                "color" to (o["color"]?.jsonPrimitive?.contentOrNull ?: "")
            )
        }.toMap()
    }.getOrDefault(emptyMap())

    private val JsonArrayEmpty = kotlinx.serialization.json.JsonArray(emptyList())
}
