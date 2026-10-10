package com.haoai.agent.agent.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * 工具卡一句话简报：引擎（ToolUpdate.brief）与 ChatViewModel（历史消息卡）共用，
 * 原先两处各维护一份 ~50 行 when，已开始漂移——收口于此。
 * v2：主简报改为「中文动词 + 可读对象」（打开·开发者选项），原始参数下沉到
 * rawOf() 供展开态显示；未知工具（mcp_* 等）回退显示参数原文头部。
 */
object ToolBrief {

    /** 主简报：中文动词 · 对象。给「一眼看懂」用。 */
    fun of(toolName: String, argsJson: String): String {
        val args = runCatching {
            com.haoai.agent.data.HaoJson.json.parseToJsonElement(argsJson.ifBlank { "{}" })
        }.getOrNull() as? JsonObject ?: return "执行 $toolName"
        return when (toolName) {
            "bash" -> "执行命令 · " + (args.optString("command").lineSequence().firstOrNull()?.take(48) ?: "…")
            "read" -> "读取文件 · " + fileName(args.optString("path"))
            "write" -> "写入文件 · " + fileName(args.optString("path"))
            "edit" -> "编辑文件 · " + fileName(args.optString("path"))
            "grep" -> "搜索内容 · " + args.optString("pattern").take(30)
            "glob" -> "查找文件 · " + args.optString("pattern").take(30)
            "web_fetch" -> "抓取网页 · " + args.optString("url").take(40)
            "web_search" -> "搜索网络 · 「" + args.optString("query").take(24) + "」"
            "todo" -> {
                // TodoTool 的真实 schema 只有 todos（传=全量替换，不传=查看当前清单）。
                // 2026-10-10 Nit：原先还读 args["action"]/args["text"] 走一套 view/add/...
                // 的旧文法，那两个参数在 schema 里根本不存在、恒为默认值，是死分支，删。
                val todos = args["todos"] as? JsonArray
                if (todos != null) "更新任务清单（${todos.size} 项）" else "查看任务清单"
            }
            "memory" -> when (args.optString("action", "list")) {
                "list" -> "查看记忆"
                "save", "write" -> "记住 · " + args.optString("content").take(24)
                else -> "查找记忆 · " + args.optString("query").take(24)
            }
            "screen" -> "读取屏幕"
            "tap" -> "点击 · " + tapTarget(args)
            "swipe" -> "滑动 · (${args.optInt("x1") ?: "?"},${args.optInt("y1") ?: "?"})→(${args.optInt("x2") ?: "?"},${args.optInt("y2") ?: "?"})"
            "scroll" -> "滚动" + when (args.optString("direction", "down")) {
                "up" -> " ↑"
                "left" -> " ←"
                "right" -> " →"
                else -> " ↓"
            } + (args.optDouble("amount")?.takeIf { it != 0.5 }?.let { " · 幅度$it" } ?: "")
            "find" -> "查找控件 · 「" + args.optString("text").take(20) + "」"
            "wait" -> when (args.optString("mode", "text")) {
                "idle" -> "等待页面稳定"
                else -> "等待「" + args.optString("text").take(16) + "」出现"
            }
            "type_text" -> "输入文字 · " + args.optString("text").take(20)
            "key" -> "按键 · " + args.optString("action")
            "launch_app" -> "打开应用 · " + args.optString("package").substringAfterLast('.').ifBlank { "…" }
            "open_uri" -> "打开页面 · " + uriLabel(args.optString("uri"))
            "list_apps" -> "列出已装应用"
            "browser_search" -> "浏览器搜索 · 「" + args.optString("query").take(24) + "」"
            "browser_open", "browser_navigate" -> "打开网页 · " + uriLabel(args.optString("url"))
            "browser_read" -> "读取网页内容"
            "browser_click" -> "点击网页元素 · [${args.optInt("index")}]"
            "browser_input" -> "网页输入 · " + args.optString("text").take(20)
            "browser_scroll" -> "网页滚动 · " + args.optString("direction", "down")
            "browser_find" -> "网页查找 · 「" + args.optString("text").take(20) + "」"
            "browser_back" -> "网页后退"
            "browser_screenshot" -> "网页截图"
            "schedule" -> when (args.optString("action", "list")) {
                "list" -> "查看定时任务"
                else -> "定时任务 · " + args.optString("name").take(20)
            }
            "spawn_agent" -> "派出子代理 · " + args.optString("task").take(24)
            "spawn_agents" -> "并行子代理 · ${(args["tasks"] as? JsonArray)?.size ?: 0} 路"
            "app_status" -> "读取运行状态"
            "config_get" -> "读取配置"
            "config_set" -> "修改配置"
            "ask_user" -> "向你提问 · " + args.optString("question").take(30)
            "ask_user_batch" -> "向你答题 · " + args.optString("title").ifBlank {
                "共 ${(args["questions"] as? JsonArray)?.size ?: 0} 题"
            }.take(24)
            "camera" -> "拍照"
            "location" -> "获取位置"
            "job_output" -> "查看后台任务日志"
            "tools_enable" -> "启用工具组 · " + args.optString("group").ifBlank { "…" }
            else -> "执行 $toolName · " + argsJson.take(30)
        }
    }

    /** 原始参数简报（旧版 of 的内容）：展开态/通知等需要细节时用。 */
    fun rawOf(toolName: String, argsJson: String): String {
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
            "tap" -> args.optInt("index")?.let { "[$it]" }
                ?: args.optString("text").ifBlank { args.optString("view_id") }
                    .ifBlank { "(${args.optString("x")},${args.optString("y")})" }
            "open_uri" -> args.optString("uri").take(80)
            "type_text" -> args.optString("text").take(60)
            "spawn_agent" -> args.optString("task").take(80)
            "wait" -> args.optString("mode", "text") +
                args.optString("text").takeIf { it.isNotBlank() }?.let { "「$it」" }.orEmpty()
            else -> argsJson.take(60)
        }
    }

    /** 类别图标 emoji：时间轴节点用（UI 层也可替换成自己的 Icon 映射）。 */
    fun iconOf(toolName: String): String = when {
        toolName in setOf("bash", "job_output") -> "⌨️"
        toolName in setOf("read", "write", "edit", "glob", "grep") -> "📄"
        toolName in setOf("web_search", "web_fetch") -> "🌐"
        toolName.startsWith("browser") -> "🌐"
        toolName in setOf("screen", "camera", "browser_screenshot") -> "📷"
        toolName in setOf("tap", "swipe", "scroll", "find", "wait", "key", "type_text", "launch_app", "open_uri", "list_apps") -> "📱"
        toolName in setOf("todo", "schedule") -> "✅"
        toolName in setOf("memory", "config_get", "config_set", "app_status", "tools_enable") -> "⚙️"
        toolName in setOf("ask_user", "ask_user_batch") -> "❓"
        toolName.startsWith("spawn") -> "🤖"
        toolName == "location" -> "📍"
        else -> "🔧"
    }

    // ── 小工具 ──
    private fun fileName(path: String): String =
        path.substringAfterLast('/').substringAfterLast('\\').ifBlank { path.take(30) }.take(30)

    private fun uriLabel(uri: String): String = when {
        uri.isBlank() -> "…"
        uri.startsWith("android.settings") -> when {
            uri.contains("APPLICATION_DEVELOPMENT", true) -> "开发者选项"
            uri.contains("WIRELESS", true) -> "无线设置"
            uri.contains("SETTINGS", true) -> "系统设置"
            else -> "系统设置"
        }
        uri.startsWith("content://") -> "内容页"
        else -> uri.removePrefix("https://").removePrefix("http://").take(36)
    }

    private fun tapTarget(args: JsonObject): String =
        args.optInt("index")?.let { "[$it]" + args.optString("text").takeIf { t -> t.isNotBlank() }?.let { t -> " $t" }.orEmpty() }
            ?: args.optString("text").ifBlank { args.optString("view_id") }
                .ifBlank { "(${args.optString("x")},${args.optString("y")})" }
            .take(24)
}
