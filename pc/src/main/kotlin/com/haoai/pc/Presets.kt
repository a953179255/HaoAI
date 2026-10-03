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
 * 预设 agent（角色卡）：一套"人设 + 模型 + 工作区 + 档位"存起来，新会话一键带上。
 *
 * 为什么要：这台机器上的活天生是几种不同的角色 —— 直播时口述要快（auto 档、便宜模型），
 * 剪视频要的是媒体工具与那个装素材的目录，写安卓代码要的是 HaoAI 仓库加"问一句再动手"。
 * 每次开会话都重挑一遍模型、重换一次工作区、重设一遍档位，三件事里总会漏一件，
 * 而漏的那一件通常要到跑错了才发现（在错的目录里写文件最贵）。
 *
 * 刻意**不**存"关掉的工具"：会话级工具开关（v0.40）已经在那条会话上了，
 * 角色卡再叠一层白名单，出问题时得同时看两处才知道是谁关的 —— 收益不值这个账。
 *
 * 专家卡字段（desc/icon/color/mbti/quick）是角色卡的展示层升级：原来列表里只有名字，
 * 人设全文塞 title 悬停才看得见 —— "专家"要的是一张能扫一眼就知道谁擅长什么的卡片。
 * 全部可缺省：老 presets.json 原样读入，一个字段都不补。
 */
data class Preset(
    val id: String,
    val name: String,
    /** 塞进系统提示的一段话：这个角色是谁、要产出什么、有什么规矩。 */
    val persona: String,
    /** 留空 = 跟全局默认模型。 */
    val model: String,
    /** 留空 = 跟全局工作区。 */
    val workspace: String,
    /** plan / ask / auto；留空或非法 = 跟全局默认档位。 */
    val mode: String,
    /** 一句专长描述（卡片第二行）。 */
    val desc: String = "",
    /** 头像字符：emoji 或单字；留空取名字首字。 */
    val icon: String = "",
    /** 头像底色 #RRGGBB；留空用哈希色。 */
    val color: String = "",
    /** 四字母 MBTI，纯展示徽标。 */
    val mbti: String = "",
    /** 快捷提问：专家卡上点一下就带着这套配置发出去。 */
    val quick: List<String> = emptyList(),
    /**
     * 绑定的知识库 id（对标 Octop 的 `agents.knowledge_base_ids`）。
     *
     * 为什么绑在卡上而不是全局：知识库的价值是"这个角色说话有依据"。
     * 全局挂十个库，每一轮都给模型看一份目录，是把窗口当仓库用；
     * 绑在卡上，剪辑专家只看剪辑规范，法务卡只看制度文件。
     * 空 = 只带"每回合自动带上"的那些库（[Knowledge.defaultOpenIds]）。
     */
    val kbs: List<String> = emptyList(),
    val created: Long = System.currentTimeMillis()
) {
    /** 头像字符的统一出口：没设 icon 就拿名字第一个字。 */
    fun avatarChar(): String = icon.ifBlank { name.take(1) }
}

object Presets {
    /**
     * 上限从 12 提到 24：内置专家库上线后"启用一个"是一句话的事，
     * 12 张很容易撞顶，而撞顶的报错出现在用户点了"启用"之后，最扫兴。
     */
    const val MAX = 24

    val MODES = listOf("plan", "ask", "auto")

    fun file(): File = File(Env.home, "presets.json")

    fun load(): MutableList<Preset> = runCatching {
        val root = Json.parseToJsonElement(file().readText())
        val arr = if (root is JsonArray) root else root.jsonObject["items"]?.jsonArray ?: JsonArray(emptyList())
        arr.mapNotNull { el ->
            val o = runCatching { el.jsonObject }.getOrNull() ?: return@mapNotNull null
            val id = o["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            Preset(
                id = id,
                name = o["name"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { id },
                persona = o["persona"]?.jsonPrimitive?.contentOrNull ?: "",
                model = o["model"]?.jsonPrimitive?.contentOrNull ?: "",
                workspace = o["workspace"]?.jsonPrimitive?.contentOrNull ?: "",
                mode = (o["mode"]?.jsonPrimitive?.contentOrNull ?: "").takeIf { it in MODES } ?: "",
                desc = o["desc"]?.jsonPrimitive?.contentOrNull ?: "",
                icon = o["icon"]?.jsonPrimitive?.contentOrNull ?: "",
                color = o["color"]?.jsonPrimitive?.contentOrNull ?: "",
                mbti = (o["mbti"]?.jsonPrimitive?.contentOrNull ?: "").uppercase(),
                quick = o["quick"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList(),
                kbs = o["kbs"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList(),
                created = o["created"]?.jsonPrimitive?.longOrNull ?: System.currentTimeMillis()
            )
        }.toMutableList()
    }.getOrDefault(mutableListOf())

    fun save(list: List<Preset>) {
        val body = list.joinToString(",", "[", "]") { p ->
            """{"id":${js(p.id)},"name":${js(p.name)},"persona":${js(p.persona)},""" +
                """"model":${js(p.model)},"workspace":${js(p.workspace)},""" +
                """"mode":${js(p.mode)},"desc":${js(p.desc)},"icon":${js(p.icon)},""" +
                """"color":${js(p.color)},"mbti":${js(p.mbti)},""" +
                """"quick":${p.quick.joinToString(",", "[", "]") { js(it) }},""" +
                """"kbs":${p.kbs.joinToString(",", "[", "]") { js(it) }},"created":${p.created}}"""
        }
        runCatching {
            file().parentFile?.mkdirs()
            file().writeText(body)
        }
    }

    fun update(item: Preset): MutableList<Preset> {
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

    fun find(id: String): Preset? = load().firstOrNull { it.id == id }

    /** 预设里那个目录现在还在不在。不在就宁可报错，也不悄悄把人放进他没选的仓库。 */
    fun workspaceOf(p: Preset): File? =
        p.workspace.takeIf { it.isNotBlank() }?.let { runCatching { File(it).canonicalFile }.getOrNull() }
            ?.takeIf { it.isDirectory }

    /** 档位只认这三个值，别的（手改过的 json）一律回落到全局默认。 */
    fun modeOf(p: Preset, fallback: String): String = p.mode.takeIf { it in MODES } ?: fallback

    fun json(): String {
        val items = load().joinToString(",") { p ->
            """{"id":${js(p.id)},"name":${js(p.name)},"persona":${js(p.persona)},""" +
                """"model":${js(p.model)},"workspace":${js(p.workspace)},"mode":${js(p.mode)},""" +
                """"desc":${js(p.desc)},"icon":${js(p.icon)},"color":${js(p.color)},""" +
                """"mbti":${js(p.mbti)},"quick":${p.quick.joinToString(",", "[", "]") { js(it) }},""" +
                """"kbs":${p.kbs.joinToString(",", "[", "]") { js(it) }}}"""
        }
        return """{"ok":true,"items":[$items],"max":$MAX,"modes":${MODES.joinToString(",", "[", "]") { js(it) }}}"""
    }

    private fun js(s: String): String = "\"" +
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "") + "\""
}
