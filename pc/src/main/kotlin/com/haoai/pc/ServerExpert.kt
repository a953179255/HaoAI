package com.haoai.pc

import com.sun.net.httpserver.HttpExchange
import com.haoai.pc.WebServer.Body
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.io.File

/**
 * 专家与团队的 Web 面：`/api/teams`（编制的读与增删改）与 `/api/experts/library`（随包内置专家库）。
 *
 * 为什么团队只存编制不存人设：成员是人，人设跟着角色卡走 —— 改了卡，下一个团队会话就跟着变。
 * 主持人的完整人设在开会话那一刻由成员名单现拼（[teamCoordinatorPersona]），没有第二真源。
 */

/**
 * `GET/POST /api/teams` —— 团队的读与增删改。
 * `POST {op:'save'|'del', id?, name?, desc?, icon?, color?, members?:[presetId,…]}`
 *
 * GET 返回的每条成员都带着**解析后的角色卡摘要**：前端卡片要画头像叠、
 * 还要能看出"某个成员的卡已经被删了"（missing=true），而不是开团那一刻才炸。
 */
internal fun WebServer.teams(ex: HttpExchange) {
    val b = Body(ex)
    if (ex.requestMethod != "GET") {
        when (b.str("op")) {
            "del" -> Teams.remove(b.str("id"))
            else -> {
                val name = b.str("name").trim()
                val members = b.list("members").map { it.trim() }.filter { it.isNotEmpty() }.distinct()
                if (name.isBlank()) {
                    send(ex, 200, """{"ok":false,"error":"团队得有个名字"}""",
                        "application/json; charset=utf-8"); return
                }
                if (members.size < Teams.MIN_MEMBERS) {
                    send(ex, 200, """{"ok":false,"error":${quote("一个队至少 ${Teams.MIN_MEMBERS} 名成员（不含主持人）——一个人的团队就是一条普通角色会话")}}""",
                        "application/json; charset=utf-8"); return
                }
                // 成员必须是真角色卡：手改请求塞进不存在的 id，开团那一刻才炸不如现在就拒
                val unknown = members.filter { Presets.find(it) == null }
                if (unknown.isNotEmpty()) {
                    send(ex, 200, """{"ok":false,"error":${quote("这些成员不是有效的角色卡：" + unknown.joinToString("、"))}}""",
                        "application/json; charset=utf-8"); return
                }
                val id = b.str("id").ifBlank { "tm" + System.nanoTime().toString(16).take(8) }
                val old = Teams.find(id)
                if (old == null && Teams.load().size >= Teams.MAX) {
                    send(ex, 200, """{"ok":false,"error":${quote("团队最多 " + Teams.MAX + " 支，先删一支")}}""",
                        "application/json; charset=utf-8"); return
                }
                Teams.update(
                    Team(
                        id = id,
                        name = name,
                        desc = b.str("desc").trim(),
                        icon = b.str("icon").trim().take(4),
                        color = b.str("color").trim().take(9),
                        members = members,
                        created = old?.created ?: System.currentTimeMillis()
                    )
                )
            }
        }
    }
    send(ex, 200, teamsJson(), "application/json; charset=utf-8")
}

/** 团队清单：成员展开成摘要（名字/头像/档位/是否还在），前端不用再发第二发请求去对。 */
internal fun WebServer.teamsJson(): String {
    val items = Teams.load().joinToString(",") { t ->
        val ms = t.members.joinToString(",", "[", "]") { mid ->
            val p = Presets.find(mid)
            if (p == null) """{"id":${quote(mid)},"missing":true}"""
            else """{"id":${quote(p.id)},"name":${quote(p.name)},"icon":${quote(p.avatarChar())},""" +
                """"color":${quote(p.color)},"mbti":${quote(p.mbti)},"model":${quote(p.model)}}"""
        }
        """{"id":${quote(t.id)},"name":${quote(t.name)},"desc":${quote(t.desc)},""" +
            """"icon":${quote(t.icon)},"color":${quote(t.color)},"members":$ms}"""
    }
    return """{"ok":true,"items":[$items],"max":${Teams.MAX},"minMembers":${Teams.MIN_MEMBERS}}"""
}

/**
 * `GET /api/experts/library` —— 随包内置专家库（只读）。
 *
 * 为什么随包不走远端：市场要账号/评分/下架，是双边生意（路线图 §5.3 的裁定）；
 * 内置库是"开箱就有几张能用的卡"，与裁定不冲突。启用 = 前端把它当普通表单
 * POST 给 /api/presets，从此就是用户自己的卡，想改想删都行。
 */
internal fun WebServer.expertLibrary(ex: HttpExchange) {
    val bytes = javaClass.classLoader.getResourceAsStream("expert-library.json")?.readBytes()
    if (bytes == null) {
        send(ex, 200, """{"ok":true,"items":[]}""", "application/json; charset=utf-8"); return
    }
    send(ex, 200, String(bytes, Charsets.UTF_8), "application/json; charset=utf-8")
}

/**
 * `GET/POST /api/agent-config` —— 每专家配置（个性化中枢的数据底座，[AgentConfigs]）。
 * `GET ?preset=<id>` 单查（缺省给默认配置，调用方不用判 null）；不带 preset 则回全部。
 * `POST {preset, providerName?, baseUrl?, toolsOff?, skillsOff?, subagents?, personaMbti?, personaExtra?, knowledgeIds?}`
 * —— 整份覆盖写（前端表单本就整份取值；部分更新的增量语义留给以后真需要时再加）。
 */
internal fun WebServer.agentConfig(ex: HttpExchange) {
    val b = Body(ex)
    if (ex.requestMethod != "GET") {
        val pid = b.str("preset").trim()
        if (pid.isBlank()) {
            send(ex, 200, """{"ok":false,"error":"缺 preset 字段（这是哪张专家卡的配置）"}""",
                "application/json; charset=utf-8"); return
        }
        AgentConfigs.save(AgentConfig(
            presetId = pid,
            providerName = b.str("providerName").trim(),
            baseUrl = b.str("baseUrl").trim(),
            toolsOff = b.list("toolsOff"),
            skillsOff = b.list("skillsOff"),
            subagents = b.list("subagents"),
            personaMbti = b.str("personaMbti").trim().uppercase(),
            personaExtra = b.str("personaExtra"),
            knowledgeIds = b.list("knowledgeIds"),
        ))
        send(ex, 200, """{"ok":true,"item":${AgentConfigs.one(AgentConfigs.of(pid))}}""",
            "application/json; charset=utf-8")
        return
    }
    val pid = queryOf(ex, "preset").trim()
    if (pid.isNotBlank()) {
        send(ex, 200, """{"ok":true,"item":${AgentConfigs.one(AgentConfigs.of(pid))}}""",
            "application/json; charset=utf-8"); return
    }
    val items = AgentConfigs.load().values.joinToString(",") { AgentConfigs.one(it) }
    send(ex, 200, """{"ok":true,"items":[$items]}""", "application/json; charset=utf-8")
}

/**
 * `GET/POST /api/mbti` —— MBTI 人格（数据在 [Mbti]，生效走 [AgentConfigs.personaMbti]）。
 * `GET ?what=types` 16 型全量；`?what=questions` 28 题；`?what=current&preset=<id>` 当前选型。
 * `POST {op:'test', answers:{题id:0|1}}` 判型（不足 20 题报错）；
 * `POST {op:'apply', preset, code}` 把选型写进该专家的配置（不自动应用——测完看一眼再点头）。
 */
internal fun WebServer.mbti(ex: HttpExchange) {
    val b = Body(ex)
    if (ex.requestMethod != "GET") {
        when (b.str("op")) {
            "test" -> {
                val answers = mutableMapOf<String, Int>()
                val raw = b.str("answers")
                // 答案在请求体里是 {题id: 0|1} 的扁平对象：手 parse 一次，避免为测试
                // 单独定义序列化 DTO（答案是键值对，不是数组，jsonObject 遍历最省）
                runCatching {
                    val o = Json.parseToJsonElement(raw).jsonObject
                    o.forEach { (k, v) -> (v as? JsonPrimitive)?.contentOrNull
                        ?.toIntOrNull()?.let { answers[k] = it } }
                }
                if (answers.size < 20) {
                    send(ex, 200, """{"ok":false,"error":${quote("至少答 20 题才能判型（已答 ${answers.size} 题）")}}""",
                        "application/json; charset=utf-8"); return
                }
                val code = Mbti.score(answers)
                if (code == null) {
                    send(ex, 200, """{"ok":false,"error":"判不出型——题答得太少或格式不对"}""",
                        "application/json; charset=utf-8"); return
                }
                send(ex, 200, """{"ok":true,"code":${quote(code)},"item":${profileJson(code)}}""",
                    "application/json; charset=utf-8")
            }
            "apply" -> {
                val pid = b.str("preset").trim()
                val code = b.str("code").trim().uppercase()
                if (pid.isBlank() || Mbti.profile(code) == null) {
                    send(ex, 200, """{"ok":false,"error":"缺 preset 或人格 code 不存在"}""",
                        "application/json; charset=utf-8"); return
                }
                // 只动 personaMbti 一个字段：先读现配置再整份写回（[AgentConfigs] 的覆盖语义）
                val cur = AgentConfigs.of(pid)
                AgentConfigs.save(cur.copy(personaMbti = code))
                send(ex, 200, """{"ok":true,"item":${AgentConfigs.one(AgentConfigs.of(pid))}}""",
                    "application/json; charset=utf-8")
            }
        }
        return
    }
    when (queryOf(ex, "what").ifBlank { "types" }) {
        "questions" -> send(ex, 200,
            """{"ok":true,"items":[${Mbti.QUESTIONS.joinToString(",") {
                """{"id":${quote(it.id)},"dim":${quote(it.dim)},"ap":${quote(it.ap.toString())},"bp":${quote(it.bp.toString())},"q":${quote(it.q)},"a":${quote(it.a)},"b":${quote(it.b)}}"""
            }}]}""", "application/json; charset=utf-8")
        "current" -> {
            val pid = queryOf(ex, "preset").trim()
            val code = if (pid.isBlank()) "" else AgentConfigs.of(pid).personaMbti
            send(ex, 200, """{"ok":true,"code":${quote(code)},"item":${profileJson(code)}}""",
                "application/json; charset=utf-8")
        }
        else -> send(ex, 200,
            """{"ok":true,"items":[${Mbti.PROFILES.joinToString(",") { profileJson(it.code) }}]}""",
            "application/json; charset=utf-8")
    }
}

/** 单个人格的接口形状（找不到 code 时回 null item，前端按"尚未选择"画）。 */
internal fun WebServer.profileJson(code: String): String {
    val p = Mbti.profile(code) ?: return "null"
    val behaviors = p.behaviors.entries.joinToString(",") { (k, v) -> "${quote(k)}:${quote(v)}" }
    return """{"code":${quote(p.code)},"name":${quote(p.name)},"nickname":${quote(p.nickname)},""" +
        """"summary":${quote(p.summary)},"color":${quote(p.color)},""" +
        """"ei":"${p.ei}","eiPct":${p.eiPct},"sn":"${p.sn}","snPct":${p.snPct},""" +
        """"tf":"${p.tf}","tfPct":${p.tfPct},"jp":"${p.jp}","jpPct":${p.jpPct},""" +
        """"behaviors":{$behaviors}}"""
}

/**
 * 开一条团队会话：主持人人设现拼、role 写团队名。
 *
 * 从 /api/new 拆出来是因为那里已经三件事套着了；团队的"拼人设+落盘"自成一段，
 * 失败（成员卡被删光）要在这里就挡住并说清是谁不见了。
 */
internal fun WebServer.newTeamSession(ex: HttpExchange, team: Team, at: File?): Boolean {
    val members = team.members.mapNotNull { Presets.find(it) }
    if (members.size < Teams.MIN_MEMBERS) {
        send(ex, 200, """{"ok":false,"error":${quote(
            "这支团队能用的成员不足 ${Teams.MIN_MEMBERS} 名（角色卡可能被删了），先去「专家与团队」补齐编制")}}""",
            "application/json; charset=utf-8")
        return false
    }
    val id = newSessionId(at, preset = null)
    val eng = sessions[id]?.engine ?: run {
        send(ex, 200, """{"ok":false,"error":"会话引擎没起来"}""", "application/json; charset=utf-8")
        return false
    }
    eng.session.persona = teamCoordinatorPersona(team, members)
    eng.session.role = "团队 · ${team.name}"
    eng.persistNow()
    val md = eng.session.mode
    val role = quote(eng.session.role)
    send(ex, 200, """{"ok":true,"id":${quote(id)},"mode":${quote(md)},"role":$role,"reused":false}""",
        "application/json; charset=utf-8")
    publish("opened", """{"id":${quote(id)},"title":"新会话","mode":${quote(md)},"role":$role}""", id)
    publish("sessions", "{}", id)
    return true
}
