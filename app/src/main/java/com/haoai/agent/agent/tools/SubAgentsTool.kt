package com.haoai.agent.agent.tools

import com.haoai.agent.agent.engine.SubagentControl
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.supervisorScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

class SubAgentsTool(
    private val runner: SubAgentRunner,
    /** P2：终止/纠偏句柄查找（单路被 stop_agent 终止时回收部分结果，兄弟路不受牵连）。 */
    private val controls: SubagentControl? = null
) : Tool {

    override val name = "spawn_agents"
    override val description =
        "并行派出多个子代理（mode=research 只读调研 / work 读写执行，权限随主代理），全部完成后汇总返回。tasks 为任务字符串数组（2~4 个，每个任务描述必须自包含）。适合互不依赖的多路任务；单任务或后台模式用 spawn_agent。运行中可用 steer_agent 纠偏、stop_agent 终止单路（id 见各路标注，被终止的路会回收已完成步骤）。"
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("tasks") {
                put("type", "array")
                putJsonObject("items") { put("type", "string") }
            }
            putJsonObject("mode") { put("type", "string") }
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
        if ((args["background"] as? kotlinx.serialization.json.JsonPrimitive)?.booleanOrNull == true) {
            return ToolResult("spawn_agents 不支持 background：后台模式请逐个用 spawn_agent(background=true)", true)
        }
        val mode = if (args.optString("mode").lowercase() == "work") "work" else "research"

        // P2：supervisorScope——单路被 stop_agent 取消不再连坐整批；await 侧按句柄状态区分
        // 「被终止（回收部分结果）」与「整轮取消（原样传播）」。
        val results = supervisorScope {
            tasks.mapIndexed { i, task ->
                async {
                    try {
                        task to runOne(task, i + 1, tasks.size, ctx, mode)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        val h = controls?.find(ctx.currentCallId, i + 1)
                        if (h?.state == "STOPPED") {
                            task to "子代理 ${h.id} 被终止。\n\n${h.partialSummary()}"
                        } else throw e
                    }
                }
            }.awaitAll()
        }
        return ToolResult(
            results.mapIndexed { i, (task, r) ->
                val hid = controls?.find(ctx.currentCallId, i + 1)?.id ?: "?"
                "【子代理 ${i + 1}/${tasks.size} · $hid】任务：${task.take(100)}\n$r"
            }.joinToString("\n\n────────\n\n")
        )
    }

    private suspend fun runOne(task: String, index: Int, total: Int, ctx: ToolContext, mode: String): String = try {
        runner.run(task, ctx, index, total, mode, controls?.newId() ?: "?")
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        "子代理执行失败：${e.message ?: e.javaClass.simpleName}"
    }

    companion object {
        const val MAX_PARALLEL = 4
    }
}
