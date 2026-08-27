package com.haoai.agent.agent.tools

import com.haoai.agent.data.HaoJson
import com.haoai.agent.platform.FileBackend
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File

@Serializable
data class TodoItem(val id: Int, var text: String, var status: String = "pending")

@Serializable
data class TodoState(var items: MutableList<TodoItem> = mutableListOf(), var nextId: Int = 1)

class TodoStore(private val appFilesDir: File) {

    companion object {
        const val WS_PATH = ".haoai/todo.json"
    }

    private val fallbackFile = File(appFilesDir, "todo.json")

    suspend fun load(backend: FileBackend?): TodoState = runCatching {
        val text = if (backend != null) backend.readText(WS_PATH)
        else if (fallbackFile.exists()) fallbackFile.readText() else null
        text?.let { HaoJson.json.decodeFromString(TodoState.serializer(), it) } ?: TodoState()
    }.getOrDefault(TodoState())

    suspend fun save(state: TodoState, backend: FileBackend?) {
        runCatching {
            val text = HaoJson.json.encodeToString(TodoState.serializer(), state)
            if (backend != null) backend.writeText(WS_PATH, text) else {
                fallbackFile.parentFile?.mkdirs()
                fallbackFile.writeText(text)
            }
        }
    }
}

class TodoTool : Tool {

    override val name = "todo"
    override val description =
        "多步任务清单。action: view(默认)/add/start/done/drop/clear；index 为 view 列表中的序号（1 起）；add 时用 text 提供内容。"
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("action") { put("type", "string") }
            putJsonObject("text") { put("type", "string") }
            putJsonObject("index") { put("type", "integer") }
        }
    }

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val store = ctx.todoStore
        val state = store.load(ctx.backend)
        val action = args.optString("action", "view").lowercase()

        return when (action) {
            "view" -> render(state)

            "add" -> {
                val text = args.optString("text").trim()
                if (text.isEmpty()) return ToolResult("add 需要 text 参数", true)
                state.items.add(TodoItem(state.nextId, text))
                state.nextId += 1
                store.save(state, ctx.backend)
                ctx.onToolChange?.invoke()
                ToolResult("已添加。\n" + render(state).content)
            }

            "start", "done", "drop" -> {
                val index = args.optInt("index")
                    ?: return ToolResult("$action 需要 index 参数（见 view 列表序号）", true)
                val item = state.items.getOrNull(index - 1)
                    ?: return ToolResult("序号越界：$index（共 ${state.items.size} 项）", true)
                when (action) {
                    "start" -> item.status = "doing"
                    "done" -> item.status = "done"
                    else -> state.items.remove(item)
                }
                store.save(state, ctx.backend)
                ctx.onToolChange?.invoke()
                render(state)
            }

            "clear" -> {
                state.items.clear()
                store.save(state, ctx.backend)
                ctx.onToolChange?.invoke()
                ToolResult("清单已清空")
            }

            else -> ToolResult("未知 action：$action", true)
        }
    }

    private fun render(state: TodoState): ToolResult {
        if (state.items.isEmpty()) return ToolResult("清单为空。可用 add 添加任务。")
        val doneCount = state.items.count { it.status == "done" }
        val sb = StringBuilder()
        sb.appendLine("TODO 清单（$doneCount/${state.items.size} 完成）")
        state.items.forEachIndexed { i, item ->
            val mark = when (item.status) {
                "done" -> "[x]"
                "doing" -> "[~]"
                else -> "[ ]"
            }
            sb.appendLine("$mark ${i + 1}. ${item.text}")
        }
        return ToolResult(sb.toString().trimEnd())
    }
}
