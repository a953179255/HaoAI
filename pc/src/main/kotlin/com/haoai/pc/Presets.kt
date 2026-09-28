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
    val created: Long = System.currentTimeMillis()
)

object Presets {
    /** 角色卡数量上限：真正常用的就那几个，列表长了就没人看。 */
    const val MAX = 12

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
                created = o["created"]?.jsonPrimitive?.longOrNull ?: System.currentTimeMillis()
            )
        }.toMutableList()
    }.getOrDefault(mutableListOf())

    fun save(list: List<Preset>) {
        val body = list.joinToString(",", "[", "]") { p ->
            """{"id":${js(p.id)},"name":${js(p.name)},"persona":${js(p.persona)},""" +
                """"model":${js(p.model)},"workspace":${js(p.workspace)},""" +
                """"mode":${js(p.mode)},"created":${p.created}}"""
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
                """"model":${js(p.model)},"workspace":${js(p.workspace)},"mode":${js(p.mode)}}"""
        }
        return """{"ok":true,"items":[$items],"max":$MAX,"modes":${MODES.joinToString(",", "[", "]") { js(it) }}}"""
    }

    private fun js(s: String): String = "\"" +
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "") + "\""
}
