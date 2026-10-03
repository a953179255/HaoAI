package com.haoai.pc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * 每专家配置（个性化枢纽的数据底座）——Octop `config_json` 的单文件同构物。
 *
 * 为什么单独一层：角色卡（[Preset]）是"这是谁"（身份与静态四件套），本文件是
 * "这个专家开哪些能力"（启停状态）。两者分开才对得上两边的改动频率——
 * 改人设是天天发生的事，而开关类配置一旦定型很少动；混成一张卡会让
 * 每次改人设都把开关字段一起重写（写放大，参考 updateSettings 的老账）。
 *
 * 缺省即默认：没有记录 = 什么都没关、什么都没挂、跟全局 —— 老用户零迁移。
 * 状态全在 HAOAI_HOME（与 presets/teams 同族），会话创建时快照进 Session。
 */
data class AgentConfig(
    /** 对应 [Preset.id]；会话创建时按 preset 读取。 */
    val presetId: String = "",
    /** 专家自带网关：providerName/baseUrl 留空 = 跟全局（Octop 专家独立 providers 的单用户简化）。 */
    val providerName: String = "",
    val baseUrl: String = "",
    /** 该专家关掉的内置工具（critical 的在写入时被剔除，见 [AgentConfigs.save]）。 */
    val toolsOff: List<String> = emptyList(),
    /** 该专家停用的技能 slug（提示注入剔除 + 读其路径被拦）。 */
    val skillsOff: List<String> = emptyList(),
    /**
     * 可派发的子智能体 slug。**空 = 全部已安装可用**（零配置即能用是默认态）；
     * 非空 = 只这几个（给"只许派调研、不许碰代码"这类专家用）。
     */
    val subagents: List<String> = emptyList(),
    /** 人格：MBTI 四字母（空=未设）+ 补充段（渲染在系统提示人格节尾部）。 */
    val personaMbti: String = "",
    val personaExtra: String = "",
    /** 默认挂接的知识库（.haoai-kb/ 下的子目录名；空 = 整个 .haoai-kb/）。 */
    val knowledgeIds: List<String> = emptyList(),
)

object AgentConfigs {
    /** 这些工具关掉等于砍掉引擎（Octop CRITICAL_TOOLS 的口径，按"砍掉后失能"从严标记）。 */
    val CRITICAL = setOf("read", "write", "edit", "glob", "grep", "task", "todo", "ask_user")

    private val ORDER = listOf(
        "presetId", "providerName", "baseUrl", "toolsOff", "skillsOff",
        "subagents", "personaMbti", "personaExtra", "knowledgeIds"
    )

    fun file(): File = File(Env.home, "agent-configs.json")

    fun load(): Map<String, AgentConfig> = runCatching {
        val root = Json.parseToJsonElement(file().readText())
        val arr = if (root is JsonArray) root else root.jsonObject["items"]?.jsonArray ?: JsonArray(emptyList())
        arr.mapNotNull { el ->
            val o = runCatching { el.jsonObject }.getOrNull() ?: return@mapNotNull null
            val id = o["presetId"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            id to AgentConfig(
                presetId = id,
                providerName = o["providerName"]?.jsonPrimitive?.contentOrNull ?: "",
                baseUrl = o["baseUrl"]?.jsonPrimitive?.contentOrNull ?: "",
                toolsOff = o["toolsOff"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList(),
                skillsOff = o["skillsOff"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList(),
                subagents = o["subagents"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList(),
                personaMbti = o["personaMbti"]?.jsonPrimitive?.contentOrNull ?: "",
                personaExtra = o["personaExtra"]?.jsonPrimitive?.contentOrNull ?: "",
                knowledgeIds = o["knowledgeIds"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList(),
            )
        }.toMap()
    }.getOrDefault(emptyMap())

    /** 没有记录时给一份默认（读取永远走这里，调用方不判 null）。 */
    fun of(presetId: String): AgentConfig = load()[presetId] ?: AgentConfig(presetId = presetId)

    fun save(c: AgentConfig) {
        // critical 在写入口剔除：手改 json/旧数据混进来的都挡在这一步，
        // 引擎侧（schemas）还要再兜一次——两处防线缺一处都能把引擎关死。
        // mbti 归一也放这里而不是端点：所有写入路径（端点、将来的导入）都得过这一道。
        val safe = c.copy(
            toolsOff = c.toolsOff.filterNot { it in CRITICAL },
            personaMbti = c.personaMbti.trim().uppercase()
        )
        val all = load().toMutableMap()
        all[safe.presetId] = safe
        writeAll(all)
    }

    /** 角色卡被删时它的开关记录也该走：留着不碍事，但"配置里有个没有的专家"是脏数据。 */
    fun remove(presetId: String) {
        val all = load().toMutableMap()
        if (all.remove(presetId) != null) writeAll(all)
    }

    private fun writeAll(all: Map<String, AgentConfig>) {
        val body = all.values.joinToString(",", "[", "]") { c ->
            """{"presetId":${js(c.presetId)},"providerName":${js(c.providerName)},"baseUrl":${js(c.baseUrl)},""" +
                """"toolsOff":${arr(c.toolsOff)},"skillsOff":${arr(c.skillsOff)},"subagents":${arr(c.subagents)},""" +
                """"personaMbti":${js(c.personaMbti)},"personaExtra":${js(c.personaExtra)},"knowledgeIds":${arr(c.knowledgeIds)}}"""
        }
        runCatching {
            file().parentFile?.mkdirs()
            file().writeText(body)
        }
    }

    /** 会话侧的生效口径：全局 toolsOff ∪ 专家 toolsOff，critical 永远不许进结果。 */
    fun effectiveToolsOff(session: Session, settings: PcSettings): Set<String> =
        ((session.agentConfig.toolsOff) + (settings.toolsOff))
            .filterNot { it in CRITICAL }.toSet()

    // ORDER 留作字段清单的文档性存在（写入顺序固定在 writeAll；显式声明避免后加字段时漏登记）
    @Suppress("unused")
    private fun orderDoc(): List<String> = ORDER

    private fun arr(xs: List<String>): String = xs.joinToString(",", "[", "]") { js(it) }
    private fun js(s: String): String = "\"" +
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "") + "\""

    /** 单条配置的 JSON（接口返回与落盘共用同一份序列化，两边不会写出两种形状）。 */
    fun one(c: AgentConfig): String =
        """{"presetId":${js(c.presetId)},"providerName":${js(c.providerName)},"baseUrl":${js(c.baseUrl)},""" +
            """"toolsOff":${arr(c.toolsOff)},"skillsOff":${arr(c.skillsOff)},"subagents":${arr(c.subagents)},""" +
            """"personaMbti":${js(c.personaMbti)},"personaExtra":${js(c.personaExtra)},"knowledgeIds":${arr(c.knowledgeIds)}}"""
}
