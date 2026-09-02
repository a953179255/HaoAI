package com.haoai.agent.agent.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

fun interface SubAgentRunner {
    /** E7a：index/total 供多路并行时逐路上报进度（单路 1/1）。 */
    suspend fun run(task: String, parentCtx: ToolContext, index: Int, total: Int): String
}

class SubAgentTool(private val runner: SubAgentRunner) : Tool {

    override val name = "spawn_agent"
    override val description =
        "派出一个只读研究子代理独立完成调研任务（可用 read/grep/glob/web_fetch/memory），完成后返回结论文本。适合需要大量检索但不想污染主对话的场景，如「spawn_agent: 调查 src 目录里所有网络请求的写法」。不可嵌套。"
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("task") {
                put("type", "string")
            }
        }
    }

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        if (ctx.depth > 0) return ToolResult("子代理不可再嵌套派出子代理", true)
        val task = args.optString("task").trim()
        if (task.isEmpty()) return ToolResult("缺少 task", true)
        return try {
            val result = runner.run(task, ctx, 1, 1)
            ToolResult(TextCap.middle(result, 6000))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolResult("子代理执行失败：${e.message ?: e.javaClass.simpleName}", true)
        }
    }
}
