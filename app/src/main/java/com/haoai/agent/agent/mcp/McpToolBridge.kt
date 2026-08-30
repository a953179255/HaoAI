package com.haoai.agent.agent.mcp

import com.haoai.agent.agent.tools.Tool
import com.haoai.agent.agent.tools.ToolContext
import com.haoai.agent.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * 远端 MCP 工具 → 本地 Tool 适配器。
 * 命名 mcp_<server短名>_<工具名>；inputSchema 原样透传（缺失字段防御性补全）；
 * 执行转发给 McpManager（含重连兜底与审批分级）。
 */
class McpTool(
    private val cfg: McpServerConfig,
    private val info: McpToolInfoData,
    private val manager: McpManager
) : Tool {

    override val name: String =
        "mcp_${manager.shortNameOf(cfg)}_${sanitize(info.name)}"

    override val description: String = buildString {
        append("[MCP·${cfg.name}] ")
        append(info.description.ifBlank { info.name })
        append("（远程工具，执行前会请求用户批准）")
    }

    /** 远端 schema 防御性补全：缺 type 时按 object 补全，缺 root 对象用默认空参，避免模型端解析失败。 */
    override val parameters: JsonObject by lazy {
        val raw = runCatching {
            if (info.schemaJson.isBlank()) null
            else com.haoai.agent.data.HaoJson.json.parseToJsonElement(info.schemaJson) as? JsonObject
        }.getOrNull()
        val hasType = raw?.get("type")?.jsonPrimitive?.contentOrNull != null
        when {
            raw == null -> DEFAULT_PARAMS
            !hasType -> buildJsonObject {
                put("type", "object")
                raw["properties"]?.let { put("properties", it) }
                raw["required"]?.let { put("required", it) }
            }
            else -> raw
        }
    }

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult =
        manager.callTool(cfg.id, info.name, args)

    private fun sanitize(raw: String): String =
        raw.map {
            if ((it.isLetterOrDigit() && it.code < 128) || it == '_' || it == '-') it else '_'
        }.joinToString("").ifBlank { "tool" }

    private companion object {
        val DEFAULT_PARAMS: JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") { }
            putJsonArray("required") { }
        }
    }
}
