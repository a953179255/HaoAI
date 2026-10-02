package com.haoai.pc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File

/**
 * 团队：一份成员编制 + 一句团队描述，开一条"主持人会话"用。
 *
 * 为什么不做成独立实体树：参照 Octop 的 AgentTeams 拍板过的那条路 ——
 * **成员仍是普通角色卡（Preset），可同时加入多个团队**；团队自己不存人设、
 * 不存模型，它只回答"这个队里有谁"。主持人的人设是开会话那一刻
 * 由成员名单现拼的（[teamCoordinatorPersona]），改了成员的卡，下一个团队会话就跟着变，
 * 不存在"团队里的人设和卡上不一致"这种第二真源。
 *
 * 深度只一层：成员是角色卡，不是别的团队 —— 团队套团队是编排器的活，不是存储的活。
 */
data class Team(
    val id: String,
    val name: String,
    /** 一句话说明这个队干什么（卡片第二行）。 */
    val desc: String = "",
    /** 头像字符与底色，同 [Preset] 的约定。 */
    val icon: String = "",
    val color: String = "",
    /** 成员编制：Preset id 列表（至少 2 个，不含主持人）。 */
    val members: List<String> = emptyList(),
    val created: Long = System.currentTimeMillis()
)

object Teams {
    /** 团队上限：真在跑的协作就那几组，列表长了反而找不到要开的那条。 */
    const val MAX = 8

    /** 一个队最少的成员数：一个人的"团队"就是一条普通角色会话，别让它伪装成协作。 */
    const val MIN_MEMBERS = 2

    fun file(): File = File(Env.home, "teams.json")

    fun load(): MutableList<Team> = runCatching {
        val root = Json.parseToJsonElement(file().readText())
        val arr = if (root is JsonArray) root else root.jsonObject["items"]?.jsonArray ?: JsonArray(emptyList())
        arr.mapNotNull { el ->
            val o = runCatching { el.jsonObject }.getOrNull() ?: return@mapNotNull null
            val id = o["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            Team(
                id = id,
                name = o["name"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { id },
                desc = o["desc"]?.jsonPrimitive?.contentOrNull ?: "",
                icon = o["icon"]?.jsonPrimitive?.contentOrNull ?: "",
                color = o["color"]?.jsonPrimitive?.contentOrNull ?: "",
                members = o["members"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList(),
                created = o["created"]?.jsonPrimitive?.longOrNull ?: System.currentTimeMillis()
            )
        }.toMutableList()
    }.getOrDefault(mutableListOf())

    fun save(list: List<Team>) {
        val body = list.joinToString(",", "[", "]") { t ->
            """{"id":${js(t.id)},"name":${js(t.name)},"desc":${js(t.desc)},""" +
                """"icon":${js(t.icon)},"color":${js(t.color)},""" +
                """"members":${t.members.joinToString(",", "[", "]") { js(it) }},"created":${t.created}}"""
        }
        runCatching {
            file().parentFile?.mkdirs()
            file().writeText(body)
        }
    }

    fun update(item: Team): MutableList<Team> {
        val list = load()
        val i = list.indexOfFirst { it.id == item.id }
        if (i >= 0) list[i] = item else { if (list.size >= MAX) return list; list += item }
        save(list)
        return list
    }

    fun remove(id: String): Boolean {
        val list = load()
        val kept = list.filterNot { it.id == id }
        if (kept.size == list.size) return false
        save(kept)
        return true
    }

    fun find(id: String): Team? = load().firstOrNull { it.id == id }

    private fun js(s: String): String = "\"" +
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "") + "\""
}

/**
 * 团队主持人的系统提示人设：成员名单（各自专长与**卡 id**）+ 调度规矩。
 *
 * 成员**派工时按卡 id 取自己的人设**（task 的 `preset` 参数，见 [Engine.spawn]）——
 * 主持人提示里只放摘要（专长一句 + 人设前 160 字）：整段人设 × N 个成员
 * 全文塞进主持人的每一轮，是把窗口当仓库用。
 *
 * 上一版这里写的是"persona 整段抄该成员的人设"，那是个真缺陷而不只是啰嗦：
 * 抄出来的那一份**没有任何一处会校验**，主持人少抄一段、把 A 成员的规矩抄给 B 成员，
 * 成员照样跑、界面照样出卡，只是跑出来的不像那个人 —— 而 Token 统计也认不出是谁花的。
 * 现在按 id 取，取不到就明确报错。
 */
internal fun teamCoordinatorPersona(team: Team, members: List<Preset>): String {
    val roster = members.joinToString("\n") { m ->
        buildString {
            append("· ").append(m.name)
            if (m.mbti.isNotBlank()) append("（").append(m.mbti).append("）")
            append("：").append(m.desc.ifBlank { "（没写专长）" })
            append(" ｜派它时 task 的 preset 填 ").append(m.id)
            if (m.model.isNotBlank()) append("（模型 ").append(m.model).append("）")
            append("\n  人设摘要：").append(m.persona.trim().take(160).ifBlank { "（没写人设）" })
        }
    }
    return buildString {
        appendLine("你是团队「${team.name}」的主持人，只做调度与汇总，不亲自干专业活。成员编制：")
        appendLine(roster)
        appendLine("调度规矩：")
        appendLine("1) 用户的话先过你。寒暄、澄清、给结论这类你自己答；专业工作用 task 工具派给成员。")
        appendLine("2) 派工时 task 的 label 必须写成员名（界面按它标牌），preset 填上面那个卡 id" +
            "（人设与模型系统会按卡取，**不要**自己抄 persona），" +
            "prompt 把任务、上下文、要产出什么一次写清。同一回合可以并行派多个成员。")
        appendLine("3) 成员只回结论。你消化、核对、拼装后再向用户汇报，不要把成员原文整段贴回来。")
        appendLine("4) 成员结论互相矛盾时你裁决，并说明裁决理由。")
        appendLine("5) 成员两次都没干成的事，如实告诉用户卡在哪，不要自己默默换路子重做。")
    }
}
