package com.haoai.agent.agent.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * 上下文交接压缩（gugu 风格五段式）：长任务中由模型主动总结，
 * 引擎用交接文档替换早期历史，任务无缝继续。
 */
class HandoffTool(private val onHandoff: (String, Int) -> Unit) : Tool {

    override val name = "handoff"
    override val description =
        "上下文压缩：当系统提示「上下文即将超限」时必须调用。用 summary 传五段式交接文档：" +
            "【目标】本次任务要达成什么 /【约束】限制与要求 /【已完成】关键进展与产物路径 /【关键决定】已确定的做法 /【下一步】接下来做什么。" +
            "importance 传本次任务的重要度 2-4（日常任务 2，重要里程碑 3，关键节点 4），用于记忆日志分级。" +
            "调用后早期对话会被压缩为该文档，当前任务无缝继续。"
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("summary") {
                put("type", "string")
                put("description", "五段式交接文档，每段一行：目标/约束/已完成/关键决定/下一步")
            }
            putJsonObject("importance") {
                put("type", "integer")
                put("description", "任务重要度 2-4，默认 3")
            }
        }
        put(
            "required",
            kotlinx.serialization.json.JsonArray(
                listOf(kotlinx.serialization.json.JsonPrimitive("summary"))
            )
        )
    }

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val summary = args.optString("summary").trim()
        if (summary.length < 30) return ToolResult("summary 太短，请按五段式完整总结后再调用", true)
        val importance = (args.optInt("importance") ?: 3).coerceIn(2, 4)
        onHandoff(summary, importance)
        return ToolResult("历史已压缩，交接文档已注入上下文。请基于交接文档继续任务，无需重复已完成的工作。")
    }
}
