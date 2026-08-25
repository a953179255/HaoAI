package com.haoai.agent.agent.tools

import com.haoai.agent.agent.schedule.ScheduleTask
import com.haoai.agent.agent.schedule.Scheduler
import com.haoai.agent.data.HaoJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ScheduleTool(private val appFilesDir: java.io.File) : Tool {

    override val name = "schedule"
    override val description =
        "定时任务。action: create(name,spec,prompt) / list / remove(id) / toggle(id)。spec 格式：every:30m（每30分钟）、every:2h、every:1d、daily:09:30（每天定点）、hourly。prompt 为到点后交给代理执行的指令。"
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("action") { put("type", "string") }
            putJsonObject("name") { put("type", "string") }
            putJsonObject("spec") { put("type", "string") }
            putJsonObject("prompt") { put("type", "string") }
            putJsonObject("id") { put("type", "string") }
        }
    }

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val store = com.haoai.agent.agent.schedule.ScheduleStore(appFilesDir)
        return when (args.optString("action", "list").lowercase()) {
            "create" -> {
                val name = args.optString("name").trim()
                val spec = Scheduler.validateSpec(args.optString("spec"))
                    ?: return ToolResult(
                        "spec 无效。可用格式：every:30m / every:2h / every:1d / daily:09:30 / hourly", true
                    )
                val prompt = args.optString("prompt").trim()
                if (name.isEmpty() || prompt.isEmpty()) return ToolResult("create 需要 name 和 prompt", true)
                val task = ScheduleTask(name = name.take(40), spec = spec, prompt = prompt.take(2000))
                val state = store.load()
                state.items.add(task)
                store.save(state)
                Scheduler.enqueueNext(task)
                ToolResult("已创建定时任务「${task.name}」（id=${task.id}，${Scheduler.specLabel(spec)}），到点自动执行。")
            }

            "list" -> {
                val items = store.load().items
                if (items.isEmpty()) return ToolResult("暂无定时任务")
                val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
                ToolResult(
                    items.joinToString("\n") { t ->
                        val lastRun = if (t.lastRunAt > 0) " · 上次 ${fmt.format(Date(t.lastRunAt))}" else ""
                        "- (${t.id}) ${t.name} · ${Scheduler.specLabel(t.spec)} · " +
                            (if (t.enabled) "启用中" else "已停用") + lastRun
                    } + items.filter { it.lastResult.isNotBlank() }
                        .take(5)
                        .joinToString("\n") { "\n  ↳ ${it.name} 结果：${it.lastResult.take(100)}" }
                )
            }

            "remove" -> {
                val id = args.optString("id")
                val state = store.load()
                val target = state.items.firstOrNull { it.id.equals(id, true) || it.name == id }
                    ?: return ToolResult("未找到任务：$id", true)
                state.items.remove(target)
                store.save(state)
                Scheduler.cancel(target.id)
                ToolResult("已删除「${target.name}」")
            }

            "toggle" -> {
                val id = args.optString("id")
                val state = store.load()
                val target = state.items.firstOrNull { it.id.equals(id, true) || it.name == id }
                    ?: return ToolResult("未找到任务：$id", true)
                target.enabled = !target.enabled
                store.save(state)
                if (target.enabled) Scheduler.enqueueNext(target) else Scheduler.cancel(target.id)
                ToolResult("「${target.name}」已${if (target.enabled) "启用" else "停用"}")
            }

            else -> ToolResult("未知 action", true)
        }
    }
}
