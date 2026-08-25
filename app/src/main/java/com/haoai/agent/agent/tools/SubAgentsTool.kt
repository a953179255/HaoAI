package com.haoai.agent.agent.tools

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

class SubAgentsTool(private val runner: SubAgentRunner) : Tool {

    override val name = "spawn_agents"
    override val description =
        "并行派出多个只读研究子代理（每个可用 read/grep/glob/web_fetch/memory），全部完成后汇总返回。tasks 为任务字符串数组（2~4 个，每个任务描述必须自包含）。适合互不依赖的多路调研；单个任务用 spawn_agent。"
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("tasks") {
                put("type", "array")
                putJsonObject("items") { put("type", "string") }
            }
        }
    }

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        if (ctx.depth > 0) return ToolResult("子代理不可再嵌套派出子代理", true)
        val tasks = (args["tasks"] as? JsonArray)
            ?.mapNotNull { it.jsonPrimitive.contentOrNull?.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
        if (tasks.isEmpty()) return ToolResult("缺少 tasks 数组", true)
        if (tasks.size > MAX_PARALLEL) return ToolResult("一次最多 $MAX_PARALLEL 个子代理", true)

        val results = coroutineScope {
            tasks.map { task -> async { task to runOne(task, ctx) } }.awaitAll()
        }
        return ToolResult(
            results.mapIndexed { i, (task, r) ->
                "【子代理 ${i + 1}/${tasks.size}】任务：${task.take(100)}\n$r"
            }.joinToString("\n\n────────\n\n")
        )
    }

    private suspend fun runOne(task: String, ctx: ToolContext): String = try {
        runner.run(task, ctx)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        "子代理执行失败：${e.message ?: e.javaClass.simpleName}"
    }

    companion object {
        const val MAX_PARALLEL = 4
    }
}
