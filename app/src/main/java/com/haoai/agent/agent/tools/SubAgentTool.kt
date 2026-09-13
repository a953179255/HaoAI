package com.haoai.agent.agent.tools

import com.haoai.agent.agent.engine.SubagentControl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

fun interface SubAgentRunner {
    /** E7a + P3：mode=research(只读)/work(继承权限可写)；id=预分配句柄 id（后台派发需先拿到）。 */
    suspend fun run(task: String, parentCtx: ToolContext, index: Int, total: Int, mode: String, id: String): String
}

class SubAgentTool(
    private val runner: SubAgentRunner,
    /** P2：终止/纠偏/收取句柄（spawn 被终止时回收部分结果；后台派发预分配 id）。 */
    private val controls: SubagentControl? = null
) : Tool {

    override val name = "spawn_agent"
    override val description =
        "派出一个子代理独立完成任务。mode=research(默认)只读调研（read/grep/glob/web_fetch/memory）；" +
            "mode=work 可读写文件/执行命令，写权限随主代理（计划模式下不可用；config_set/tools_enable/todo 不可用，改动可回滚）。" +
            "background=true 时立即返回、子代理后台运行（用 collect_agent 收结果、steer_agent 纠偏、stop_agent 终止；回合结束会一并终止）。" +
            "不可嵌套。"
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("task") { put("type", "string") }
            putJsonObject("mode") {
                put("type", "string")
                putJsonArray("enum") {
                    add(kotlinx.serialization.json.JsonPrimitive("research"))
                    add(kotlinx.serialization.json.JsonPrimitive("work"))
                }
            }
            putJsonObject("background") { put("type", "boolean") }
        }
    }

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        if (ctx.depth > 0) return ToolResult("子代理不可再嵌套派出子代理", true)
        val task = args.optString("task").trim()
        if (task.isEmpty()) return ToolResult("缺少 task", true)
        val mode = if (args.optString("mode").lowercase() == "work") "work" else "research"
        val background = (args["background"] as? kotlinx.serialization.json.JsonPrimitive)?.booleanOrNull == true
        val id = controls?.newId() ?: "sa_local_${System.currentTimeMillis() % 100000}"

        if (background) {
            if (controls == null) return ToolResult("后台模式不可用（缺少句柄接口）", true)
            // P3 后台：挂在当前回合协程树上 launch——不阻塞主代理；回合结束随树取消
            CoroutineScope(kotlinx.coroutines.currentCoroutineContext()).launch {
                try {
                    controls.byId(id)?.finalResult = runner.run(task, ctx, 1, 1, mode, id)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    // 终止/回合取消：句柄状态与部分结果已在 runSubAgent 内落账
                } catch (e: Exception) {
                    controls.byId(id)?.let { h ->
                        h.state = "ERROR"
                        h.finalResult = "子代理执行失败：${e.message ?: e.javaClass.simpleName}\n\n${h.partialSummary()}"
                    }
                }
            }
            return ToolResult(
                "子代理 $id 已在后台启动（mode=$mode，任务：${task.take(80)}）。" +
                    "用 collect_agent(id='$id') 收取进度/结果；steer_agent/stop_agent 可干预。注意：回合结束时后台子代理会一并终止。"
            )
        }

        return try {
            val result = runner.run(task, ctx, 1, 1, mode, id)
            ToolResult(TextCap.middle(result, 6000))
        } catch (e: kotlinx.coroutines.CancellationException) {
            // P2：被 stop_agent 终止 → 回收部分结果返回主代理；整轮取消（用户停止）则原样传播
            val h = controls?.byId(id)
            if (h?.state == "STOPPED") ToolResult("子代理 ${h.id} 被终止。\n\n${h.partialSummary()}", true)
            else throw e
        } catch (e: Exception) {
            ToolResult("子代理执行失败：${e.message ?: e.javaClass.simpleName}", true)
        }
    }
}

/** P3：收取子代理（含后台）的进度或结果。wait_seconds>0 时最多等待该秒数直到完成。 */
class CollectAgentTool(private val controls: SubagentControl) : Tool {

    override val name = "collect_agent"
    override val description =
        "收取子代理的进度或结果（id 见 spawn 结果标注，后台子代理必须用本工具收取）。wait_seconds=0 只看当前快照；>0 最多等待该秒数直到完成，返回状态、已完成步骤与最终结论。"
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("id") { put("type", "string") }
            putJsonObject("wait_seconds") { put("type", "integer") }
        }
    }

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val id = args.optString("id").trim()
        if (id.isEmpty()) return ToolResult("缺少 id（见 spawn 结果的 sa_N 标注）", true)
        val h = controls.byId(id)
            ?: return ToolResult("子代理 $id 不存在（未派出或已超出本回合）", true)
        val wait = (args.optInt("wait_seconds") ?: 0).coerceIn(0, 600)
        val deadline = System.currentTimeMillis() + wait * 1000L
        while (h.state == "RUNNING" && System.currentTimeMillis() < deadline) delay(400)
        return buildString {
            append("子代理 ${h.id}（${h.state}，index ${h.index}/${h.total}）")
            h.currentTool?.let { append(" · 正在：${it.take(80)}") }
            appendLine()
            if (h.steps.isNotEmpty()) {
                appendLine()
                appendLine("已完成步骤：")
                h.steps.forEach { appendLine("- $it") }
            }
            h.finalResult?.let {
                appendLine()
                append(TextCap.middle(it, 3000))
            }
        }.let { ToolResult(it) }
    }
}

/** P2：终止一个运行中的子代理——部分结果（已完成步骤+中间结论）由 spawn 调用回收返回主代理。 */
class StopAgentTool(private val controls: SubagentControl) : Tool {

    override val name = "stop_agent"
    override val description =
        "终止一个运行中的子代理（id 见 spawn 结果各路标注）。被终止的子代理会把已完成步骤与中间结论回收到本次 spawn 的返回里，主代理可据此继续。后台子代理终止后用 collect_agent 取部分结果。"
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("id") { put("type", "string") }
        }
    }

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val id = args.optString("id").trim()
        if (id.isEmpty()) return ToolResult("缺少 id（见 spawn 结果的 sa_N 标注）", true)
        return if (controls.stop(id)) ToolResult("已发送终止指令：$id（部分结果将随本次 spawn 调用返回；后台子代理用 collect_agent 收取）。")
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
