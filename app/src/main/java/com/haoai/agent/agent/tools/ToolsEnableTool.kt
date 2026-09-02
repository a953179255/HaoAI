package com.haoai.agent.agent.tools

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * E4b 工具分层注入的元工具：启用一个工具组（本会话保持）。
 * 引擎在回调里把组并入 session.activeGroups、持久化并重建本轮工具清单（下一轮 LLM 请求生效）。
 * READ 级（免审批、不占快照）；不在 PARALLEL_SAFE 白名单，恒串行执行，避免并发重建工具清单。
 */
class ToolsEnableTool(private val enable: (String) -> String) : Tool {

    override val name = "tools_enable"
    override val description =
        "启用一个工具组，启用后该组工具注入工具清单且本会话保持。可用组：extended（无障碍操作/内置浏览器/虚拟屏/相机/定位/设备工具包/工作流等）、mcp（MCP 外部服务器工具）。core 组常驻无需启用。仅当系统提示标注某组未加载、且当前任务确需该组工具时才调用。"
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("group") {
                put("type", "string")
                put("description", "要启用的工具组：extended 或 mcp")
            }
        }
        putJsonArray("required") { add(JsonPrimitive("group")) }
    }

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val group = args.optString("group").trim()
        if (group.isEmpty()) return ToolResult("缺少 group 参数", true)
        return ToolResult(enable(group))
    }
}
