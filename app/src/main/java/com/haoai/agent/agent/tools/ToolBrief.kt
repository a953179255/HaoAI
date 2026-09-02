package com.haoai.agent.agent.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * 工具卡一句话简报：引擎（ToolUpdate.brief）与 ChatViewModel（历史消息卡）共用，
 * 原先两处各维护一份 ~50 行 when，已开始漂移——收口于此。
 * 未知工具（mcp_* 等）回退显示参数原文头部，便于用户看清模型在调什么。
 */
object ToolBrief {

    fun of(toolName: String, argsJson: String): String {
        val args = runCatching {
            com.haoai.agent.data.HaoJson.json.parseToJsonElement(argsJson.ifBlank { "{}" })
        }.getOrNull() as? JsonObject ?: return ""
        return when (toolName) {
            "bash" -> args.optString("command").lineSequence().firstOrNull()?.take(90) ?: ""
            "read", "write", "edit" -> args.optString("path")
            "grep" -> "/${args.optString("pattern")}/"
            "glob" -> args.optString("pattern")
            "web_fetch" -> args.optString("url")
            "web_search" -> args.optString("query")
            "todo" -> {
                val todos = args["todos"] as? JsonArray
                when {
                    todos != null -> "更新清单(${todos.size}项)"
                    else -> {
                        val action = args.optString("action", "view")
                        val text = args.optString("text")
                        when {
                            text.isNotBlank() -> "$action · $text"
                            action == "view" -> "查看清单"
                            else -> action
                        }
                    }
                }
            }
            "memory" -> args.optString("action", "list") +
                (args.optString("content").ifBlank { args.optString("query") })
                    .takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
            "screen" -> "读取屏幕"
            "tap" -> args.optInt("index")?.let { "[$it]" }
                ?: args.optString("text").ifBlank { args.optString("view_id") }
                    .ifBlank { "(${args.optString("x")},${args.optString("y")})" }
            "swipe" -> "滑动"
            "scroll" -> "滚动 ${args.optString("direction", "down")}"
            "find" -> "查找「${args.optString("text")}」"
            "wait" -> "等待 ${args.optString("mode", "text")}" +
                args.optString("text").takeIf { it.isNotBlank() }?.let { "「$it」" }.orEmpty()
            "type_text" -> "输入：${args.optString("text").take(40)}"
            "key" -> args.optString("action")
            "launch_app" -> args.optString("package")
            "list_apps" -> "列出应用"
            "browser_search" -> "搜索「${args.optString("query")}」"
            "browser_open", "browser_navigate" -> args.optString("url")
            "browser_read" -> "读取页面结构"
            "browser_click" -> args.optInt("index")?.let { "[$it]" } ?: ""
            "browser_input" -> "[${args.optInt("index")}] 输入：${args.optString("text").take(30)}"
            "browser_scroll" -> "滚动 ${args.optString("direction", "down")}"
            "browser_find" -> "查找「${args.optString("text")}」"
            "browser_back" -> "后退"
            "browser_screenshot" -> "页面截图"
            "schedule" -> args.optString("action", "list") +
                args.optString("name").takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
            "spawn_agent" -> args.optString("task").take(60)
            "spawn_agents" -> "${(args["tasks"] as? JsonArray)?.size ?: 0} 个并行子任务"
            "app_status" -> "读取运行状态"
            "config_get" -> "读取配置"
            "config_set" -> "修改配置"
            "camera" -> "拍照"
            "location" -> "获取当前位置"
            "job_output" -> args.optString("id").ifBlank { "最近任务日志" }
            "tools_enable" -> "启用工具组 " + args.optString("group").ifBlank { "（未指定）" }
            else -> argsJson.take(60)
        }
    }
}
