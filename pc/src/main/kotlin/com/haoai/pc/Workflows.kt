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
 * 任务链（工作流）：几句按顺序跑的话，跑在同一条会话里。
 *
 * 为什么值得单独有：视频那类活天生是多步的 —— "把今天的录屏剪成 30 秒"→"从里面抽一张封面"→
 * "照这条片子写一句标题"。一步一步手动发，人就得守在旁边等每一轮结束；
 * 而排到定时任务里又只能排一句话。
 *
 * 实现上**不引入新的执行机制**：第一步正常起一轮，剩下的句子塞进那条会话已有的排队队列，
 * 由每轮结束时的接力自动往下发（见 `Server` 里 trigger=排队 那段）。
 * 所以每一步都看得见上一步的结果（同一段历史），且按停止会像平时一样把队列清掉。
 */
data class Workflow(
    val id: String,
    val name: String,
    val steps: List<String>,
    val created: Long = System.currentTimeMillis()
)

object Workflows {
    /** 一条链最多几步：再多就该拆成两条链，或者写成一段提示词。 */
    const val MAX_STEPS = 8

    fun file(): File = File(Env.home, "workflows.json")

    fun load(): MutableList<Workflow> = runCatching {
        val root = Json.parseToJsonElement(file().readText())
        val arr = if (root is JsonArray) root else root.jsonObject["items"]?.jsonArray ?: JsonArray(emptyList())
        arr.mapNotNull { el ->
            val o = runCatching { el.jsonObject }.getOrNull() ?: return@mapNotNull null
            val id = o["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val steps = (o["steps"]?.jsonArray ?: JsonArray(emptyList())).mapNotNull {
                runCatching { it.jsonPrimitive.contentOrNull }.getOrNull()
            }.map { it.trim() }.filter { it.isNotEmpty() }
            Workflow(
                id = id,
                name = o["name"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { id },
                steps = steps,
                created = o["created"]?.jsonPrimitive?.longOrNull ?: System.currentTimeMillis()
            )
        }.toMutableList()
    }.getOrDefault(mutableListOf())

    fun save(list: List<Workflow>) {
        val body = list.joinToString(",", "[", "]") { w ->
            """{"id":${js(w.id)},"name":${js(w.name)},"created":${w.created},"steps":""" +
                w.steps.joinToString(",", "[", "]") { js(it) } + "}"
        }
        runCatching {
            file().parentFile?.mkdirs()
            file().writeText(body)
        }
    }

    fun update(item: Workflow): MutableList<Workflow> {
        val list = load()
        val i = list.indexOfFirst { it.id == item.id }
        if (i >= 0) list[i] = item else list += item
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

    fun find(id: String): Workflow? = load().firstOrNull { it.id == id }

    /** 把"一行一步"的文本切成步骤：空行丢掉，超出上限就截断（界面上会说清截到几步）。 */
    fun parseSteps(raw: String): List<String> =
        raw.split('\n').map { it.trim() }.filter { it.isNotEmpty() }.take(MAX_STEPS)

    fun json(): String {
        val items = load().joinToString(",") { w ->
            """{"id":${js(w.id)},"name":${js(w.name)},"created":${w.created},""" +
                """"steps":${w.steps.joinToString(",", "[", "]") { js(it) }},""" +
                """"count":${w.steps.size}}"""
        }
        return """{"ok":true,"items":[$items],"maxSteps":$MAX_STEPS}"""
    }

    private fun js(s: String): String = "\"" +
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "") + "\""
}
