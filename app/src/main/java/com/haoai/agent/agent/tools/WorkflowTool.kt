package com.haoai.agent.agent.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.contentOrNull
import org.json.JSONObject

/**
 * Phase 6 工作流工具：Agent 起草（save，pendingConfirm 待用户 UI 确认）与列表。
 * Agent 不能直接启用工作流——enabled 只能由用户在 UI 确认（审批门控核心）。
 */
class WorkflowTool(private val appFilesDir: java.io.File) : Tool {

    override val name = "workflow_save"
    override val description =
        "把重复任务沉淀为工作流（需用户在管理页确认后才会启用执行）。action: save(name, triggerType, triggerConfig, steps) / list / delete(id)。" +
            "triggerType: manual|schedule|boot|notification；triggerConfig 为 schedule 时的规格（every:30m / daily:09:30 / hourly）；" +
            "notification 时为触发关键词。" +
            "steps 为 JSON 数组，每步 {\"type\":\"prompt\",\"text\":\"交给代理的指令\",\"condition\":\"可选\"} 或 {\"type\":\"tool\",\"tool\":\"工具名\",\"args\":\"{} 参数JSON\",\"condition\":\"可选\",\"stopOnError\":true}。" +
            "步骤间数据传递：text/args 里可用 {{prev}} 引用上一步输出、{{step1}}/{{step2}} 引用第 N 步输出（tool 的 args 会做 JSON 转义）。" +
            "条件分支：condition 为 prev_contains:关键词 或 prev_not_contains:关键词（对上一步输出判断，不满足则跳过该步）。" +
            "外部触发：启用后管理页提供令牌，Tasker/adb 广播 com.haoai.agent.WORKFLOW_RUN 可拉起。"
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("action") { put("type", "string") }
            putJsonObject("name") { put("type", "string") }
            putJsonObject("triggerType") { put("type", "string") }
            putJsonObject("triggerConfig") { put("type", "string") }
            putJsonObject("steps") { put("type", "string") }
            putJsonObject("id") { put("type", "string") }
        }
    }

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val store = com.haoai.agent.agent.workflow.WorkflowStore
        return when (args.optString("action", "list").lowercase()) {
            "save" -> {
                val name = args.optString("name").trim()
                if (name.isEmpty()) return ToolResult("save 需要 name", true)
                val triggerType = args.optString("triggerType", "manual").lowercase().ifBlank { "manual" }
                if (triggerType !in listOf("manual", "schedule", "boot", "notification"))
                    return ToolResult("triggerType 仅支持 manual/schedule/boot/notification", true)
                val triggerConfig = args.optString("triggerConfig").trim()
                if (triggerType == "schedule" && !store.validScheduleSpec(triggerConfig))
                    return ToolResult("schedule 触发的 config 无效。可用：every:30m / every:2h / every:1d / daily:09:30 / hourly", true)
                val stepsJson = args.optString("steps").trim().ifBlank { "[]" }
                val parsed = runCatching {
                    com.haoai.agent.data.HaoJson.json.parseToJsonElement(stepsJson).jsonArray
                        .map { el ->
                            val o = el.jsonObject
                            val type = o["type"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: "prompt"
                            if (type !in listOf("prompt", "tool")) throw Exception("step.type 仅支持 prompt/tool")
                            val condition = o["condition"]?.jsonPrimitive?.contentOrNull.orEmpty().take(200)
                            if (!com.haoai.agent.agent.workflow.WorkflowStore.validCondition(condition))
                                throw Exception("condition 仅支持 prev_contains:文本 / prev_not_contains:文本")
                            com.haoai.agent.agent.workflow.WorkflowStore.Step(
                                type = type,
                                text = o["text"]?.jsonPrimitive?.contentOrNull.orEmpty().take(2000),
                                tool = o["tool"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                                args = o["args"]?.jsonPrimitive?.contentOrNull.orEmpty().take(4000),
                                stopOnError = o["stopOnError"]?.jsonPrimitive?.contentOrNull != "false",
                                condition = condition
                            )
                        }
                }.getOrElse { return ToolResult("steps JSON 无效：${it.message}", true) }
                if (parsed.isEmpty()) return ToolResult("steps 不能为空", true)
                val def = store.draft(name, com.haoai.agent.agent.workflow.WorkflowStore.Trigger(triggerType, triggerConfig), parsed)
                ToolResult(
                    "工作流「${def.name}」已起草（id=${def.id}，${def.steps.size} 步，触发 ${def.trigger.type}）。\n" +
                        "状态：待确认——需要用户在 设置 → 工作流 页确认后才会启用，你无法自行启用。"
                )
            }
            "delete" -> {
                val id = args.optString("id").trim()
                if (store.delete(id)) ToolResult("已删除工作流 $id")
                else ToolResult("工作流不存在：$id", true)
            }
            else -> {
                val all = store.list()
                if (all.isEmpty()) return ToolResult("尚无工作流。可用 workflow_save 起草（需用户确认后生效）。")
                ToolResult(all.joinToString("\n") { d ->
                    "• ${d.name}（id=${d.id}）触发=${d.trigger.type}" +
                        (d.trigger.config.takeIf { it.isNotBlank() }?.let { "($it)" } ?: "") +
                        "，${d.steps.size} 步，" + (if (d.pendingConfirm) "待确认" else if (d.enabled) "已启用" else "已停用")
                })
            }
        }
    }
}
