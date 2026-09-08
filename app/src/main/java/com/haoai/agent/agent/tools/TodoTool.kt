package com.haoai.agent.agent.tools

import com.haoai.agent.data.HaoJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.util.UUID

@Serializable
data class TodoItem(
    val text: String,
    val status: String = "pending",
    val priority: String = "medium",
    /** 稳定唯一 id：LazyColumn key/精确更新用（旧 JSON 缺省时自动生成，避免重复 key 崩溃）。 */
    val id: String = UUID.randomUUID().toString()
)

class TodoStore(private val appFilesDir: File) {

    private val todosDir = File(appFilesDir, "todos").apply { mkdirs() }

    fun load(sessionId: String): List<TodoItem> = runCatching {
        val file = File(todosDir, "$sessionId.json")
        if (!file.exists()) return@runCatching emptyList()
        val raw = file.readText()
        HaoJson.json.decodeFromString<List<TodoItem>>(raw)
    }.getOrDefault(emptyList())

    fun save(sessionId: String, items: List<TodoItem>) {
        runCatching {
            val file = File(todosDir, "$sessionId.json")
            if (items.isEmpty()) {
                file.delete()
            } else {
                file.writeText(HaoJson.json.encodeToString<List<TodoItem>>(items))
            }
        }
    }
}

class TodoTool : Tool {

    override val name = "todo"
    override val description =
        """创建和维护当前会话的任务清单。传入完整的 todo 列表进行全量替换。
每个条目包含：text(任务描述)、status(pending/in_progress/completed/cancelled)、priority(high/medium/low)。
仅传入 todos 参数即可更新清单；不传则查看当前清单。
更新时机：开始某项前把它标为 in_progress；每完成一项立即调用本工具把它标为 completed，不要攒到最后；放弃的项标为 cancelled。给出最终回答前，清单必须已与实际进度一致。"""
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("todos") {
                put("type", "array")
                put("description", "完整的任务列表（全量替换）")
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("text") { put("type", "string"); put("description", "任务描述") }
                        putJsonObject("status") { put("type", "string"); put("description", "pending/in_progress/completed/cancelled") }
                        putJsonObject("priority") { put("type", "string"); put("description", "high/medium/low") }
                    }
                }
            }
        }
    }

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val todosArray = args["todos"]?.jsonArray

        if (todosArray != null) {
            val items = todosArray.map { el ->
                val obj = el.jsonObject
                TodoItem(
                    text = obj["text"]?.jsonPrimitive?.content ?: "",
                    status = obj["status"]?.jsonPrimitive?.content ?: "pending",
                    priority = obj["priority"]?.jsonPrimitive?.content ?: "medium"
                )
            }
            ctx.todoStore.save(ctx.sessionId, items)
            ctx.onToolChange?.invoke()
            return render(items)
        }

        val items = ctx.todoStore.load(ctx.sessionId)
        return render(items)
    }

    private fun render(items: List<TodoItem>): ToolResult {
        if (items.isEmpty()) return ToolResult("清单为空。")
        val done = items.count { it.status == "completed" }
        val sb = StringBuilder()
        sb.appendLine("任务清单（$done/${items.size} 完成）")
        items.forEach { item ->
            val mark = when (item.status) {
                "completed" -> "[x]"
                "in_progress" -> "[~]"
                "cancelled" -> "[-]"
                else -> "[ ]"
            }
            val pri = when (item.priority) {
                "high" -> " !"
                "low" -> " ·"
                else -> ""
            }
            sb.appendLine("$mark$pri ${item.text}")
        }
        return ToolResult(sb.toString().trimEnd())
    }
}
