package com.haoai.agent.agent.tools

import com.haoai.agent.agent.engine.SubagentControl
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

fun interface SubAgentRunner {
    /** E7a：index/total 供多路并行时逐路上报进度（单路 1/1）。 */
    suspend fun run(task: String, parentCtx: ToolContext, index: Int, total: Int): String
}

class SubAgentTool(
    private val runner: SubAgentRunner,
    /** P2：终止/纠偏句柄查找（spawn 被终止时回收部分结果）。 */
    private val controls: SubagentControl? = null
) : Tool {

    override val name = "spawn_agent"
    override val description =
        "派出一个只读研究子代理独立完成调研任务（可用 read/grep/glob/web_fetch/memory），完成后返回结论文本。适合需要大量检索但不想污染主对话的场景，如「spawn_agent: 调查 src 目录里所有网络请求的写法」。不可嵌套。若需中途干预可用 steer_agent/stop_agent（id 见返回结果）。"
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
            // P2：被 stop_agent 终止 → 回收部分结果返回主代理；整轮取消（用户停止）则原样传播
            val h = controls?.find(ctx.currentCallId, 1)
            if (h?.state == "STOPPED") ToolResult("子代理 ${h.id} 被终止。\n\n${h.partialSummary()}", true)
            else throw e
        } catch (e: Exception) {
            ToolResult("子代理执行失败：${e.message ?: e.javaClass.simpleName}", true)
        }
    }
}

/** P2：终止一个运行中的子代理——部分结果（已完成步骤+中间结论）由 spawn 调用回收返回主代理。 */
class StopAgentTool(private val controls: SubagentControl) : Tool {

    override val name = "stop_agent"
    override val description =
        "终止一个运行中的子代理（id 见 spawn 结果各路标注）。被终止的子代理会把已完成步骤与中间结论回收到本次 spawn 的返回里，主代理可据此继续。"
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("id") { put("type", "string") }
        }
    }

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val id = args.optString("id").trim()
        if (id.isEmpty()) return ToolResult("缺少 id（见 spawn 结果的 sa_N 标注）", true)
        return if (controls.stop(id)) ToolResult("已终止 $id：部分结果将随本次 spawn 调用返回。")
        else ToolResult("子代理 $id 不存在或已结束", true)
    }
}

/** P2：向运行中的子代理注入纠偏指令——不打断当前执行，下一轮开始前生效。 */
class SteerAgentTool(private val controls: SubagentControl) : Tool {

    override val name = "steer_agent"
    override val description =
        "向运行中的子代理追加指令（不打断当前执行，下一轮前生效）。用于纠偏、收窄范围或补充线索。id 见 spawn 结果标注。"
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("id") { put("type", "string") }
            putJsonObject("message") { put("type", "string") }
        }
    }

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val id = args.optString("id").trim()
        val message = args.optString("message").trim()
        if (id.isEmpty() || message.isEmpty()) return ToolResult("需要 id 与 message", true)
        return if (controls.steer(id, message)) ToolResult("已向 $id 注入指令，下一轮生效。")
        else ToolResult("子代理 $id 不存在或已结束", true)
    }
}
