package com.haoai.agent.agent.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.net.URLEncoder

/**
 * 4.1 a11y 浏览器工具组：拉起系统浏览器到搜索结果页 / 目标网址。
 * 本组只负责「打开」；打开后的读取与操作复用既有 screen/tap/wait 组合
 * （推荐流程写进 SystemPrompt，不另造包装工具）。
 * 纯 Intent 实现，不依赖无障碍服务实例——无障碍未开启时打开也能成功，
 * 只是后续读不到屏幕（工具结果里提示模型）。
 */
object BrowserIntents {

    /** 搜索引擎白名单：bing/baidu 国内可直连，google/duckduckgo 需代理，默认 bing。 */
    val ENGINES = mapOf(
        "bing" to "https://www.bing.com/search?q=",
        "baidu" to "https://www.baidu.com/s?wd=",
        "google" to "https://www.google.com/search?q=",
        "duckduckgo" to "https://duckduckgo.com/?q="
    )

    fun launch(context: Context, url: String): String {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            context.startActivity(intent)
            "已在浏览器打开：$url"
        }.getOrElse {
            "打开浏览器失败：${it.message ?: "未找到可用的浏览器应用"}"
        }
    }
}

class BrowserSearchTool : Tool {

    override val name = "browser_search"
    override val description =
        "在真实浏览器中搜索关键词（EXEC 级审批）：拉起默认浏览器打开搜索结果页。" +
            "打开后用 wait(mode=idle) 等页面加载，再 screen 读编号、tap(index) 点击结果。" +
            "纯文本资料优先用 web_fetch/web_search（省步骤）；需要真实浏览器环境时用本工具。"
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("query") {
                put("type", "string")
                put("description", "搜索关键词")
            }
            putJsonObject("engine") {
                put("type", "string")
                put("description", "搜索引擎：bing（默认）/ baidu / google / duckduckgo")
            }
        }
    }

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val app = ctx.appContext ?: return ToolResult("应用上下文不可用", true)
        val query = args.optString("query")
        if (query.isBlank()) return ToolResult("缺少 query", true)
        val engine = args.optString("engine").ifBlank { "bing" }.lowercase()
        val base = BrowserIntents.ENGINES[engine]
            ?: return ToolResult("未知搜索引擎：$engine（支持 ${BrowserIntents.ENGINES.keys.joinToString("/")}）", true)
        return ToolResult(BrowserIntents.launch(app, base + URLEncoder.encode(query, "UTF-8")))
    }
}

class BrowserOpenTool : Tool {

    override val name = "browser_open"
    override val description =
        "在真实浏览器中打开网址（EXEC 级审批）：拉起默认浏览器打开页面，用户可见。" +
            "打开后用 wait(mode=idle) 等页面加载，再 screen 读编号、tap(index) 操作。" +
            "url 缺协议时自动补 https://。"
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("url") {
                put("type", "string")
                put("description", "目标网址，如 https://news.example.com 或 news.example.com")
            }
        }
    }

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val app = ctx.appContext ?: return ToolResult("应用上下文不可用", true)
        var url = args.optString("url").trim()
        if (url.isBlank()) return ToolResult("缺少 url", true)
        if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://$url"
        return ToolResult(BrowserIntents.launch(app, url))
    }
}
