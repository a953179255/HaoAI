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

// ── 4.2 内置浏览器工具组：App 内嵌 WebView 自动化，用户不可见除非切到浏览器界面 ──
// 与上面 4.1 组的分工：内置组能读 JS 渲染后结构、可编程操作、适合批量抓取；
// 真实浏览器组适合登录态页面与"让用户亲眼看到"的场景。

private fun noBrowser() = ToolResult("内置浏览器不可用（控制器未初始化）", true)

class BrowserNavigateTool : Tool {

    override val name = "browser_navigate"
    override val description =
        "在内置浏览器（App 内 WebView，EXEC 级审批）中加载网页：自动等页面加载完成 + 渲染余量，" +
            "返回最终标题与 URL。之后用 browser_read 取编号结构再操作。" +
            "需要 JS 渲染页面/自动化/批量读取时用本组；要给用户看或用登录态时才用 browser_open。"
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("url") {
                put("type", "string")
                put("description", "目标网址，缺协议自动补 https://")
            }
        }
    }

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        if (!com.haoai.agent.agent.browser.BrowserController.ready) return noBrowser()
        val url = args.optString("url").trim()
        if (url.isBlank()) return ToolResult("缺少 url", true)
        return ToolResult(com.haoai.agent.agent.browser.BrowserController.navigate(url))
    }
}

class BrowserReadTool : Tool {

    override val name = "browser_read"
    override val description =
        "读取内置浏览器当前页结构（READ）：标题/URL/滚动位置 + 可交互元素编号列表 " +
            "[index] 标签 \"文本\" href 坐标（可见元素优先，上限 80）。" +
            "编号每次 read 刷新，页面变了必须重读；点击/输入前先 read。"
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("max_nodes") {
                put("type", "integer")
                put("description", "返回元素上限，默认 80")
            }
        }
    }

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        if (!com.haoai.agent.agent.browser.BrowserController.ready) return noBrowser()
        val max = (args.optInt("max_nodes") ?: com.haoai.agent.agent.browser.BrowserController.MAX_ELEMENTS)
            .coerceIn(10, 200)
        return ToolResult(
            com.haoai.agent.agent.browser.BrowserController.readStructure(max).let { TextCap.middle(it, 8000) }
        )
    }
}

class BrowserClickTool : Tool {

    override val name = "browser_click"
    override val description =
        "点击内置浏览器页面上编号对应的元素（WRITE，编号来自 browser_read）。" +
            "自动滚到元素中央并派发完整鼠标事件序列；页面跳转后用 browser_read 确认结果。"
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("index") {
                put("type", "integer")
                put("description", "browser_read 输出的元素编号")
            }
        }
    }

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        if (!com.haoai.agent.agent.browser.BrowserController.ready) return noBrowser()
        val idx = args.optInt("index") ?: return ToolResult("缺少 index", true)
        return ToolResult(com.haoai.agent.agent.browser.BrowserController.clickElement(idx))
    }
}

class BrowserInputTool : Tool {

    override val name = "browser_input"
    override val description =
        "向内置浏览器页面编号对应的输入框填文本（WRITE，编号来自 browser_read）。" +
            "submit=true 时提交表单（无表单则派发回车）。兼容 React/Vue 受控输入。"
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("index") {
                put("type", "integer")
                put("description", "browser_read 输出的输入框编号")
            }
            putJsonObject("text") { put("type", "string") }
            putJsonObject("submit") {
                put("type", "boolean")
                put("description", "填完是否提交，默认 false")
            }
        }
    }

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        if (!com.haoai.agent.agent.browser.BrowserController.ready) return noBrowser()
        val idx = args.optInt("index") ?: return ToolResult("缺少 index", true)
        val text = args.optString("text")
        if (text.isEmpty()) return ToolResult("缺少 text", true)
        return ToolResult(
            com.haoai.agent.agent.browser.BrowserController.inputText(idx, text, args.optBool("submit"))
        )
    }
}

class BrowserPageScrollTool : Tool {

    override val name = "browser_scroll"
    override val description =
        "滚动内置浏览器页面（WRITE）：direction=up/down/left/right，amount=视口比例 0.1-0.9 默认 0.7。"
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("direction") {
                put("type", "string")
                put("description", "up=内容上移看下方，默认 down")
            }
            putJsonObject("amount") { put("type", "number") }
        }
    }

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        if (!com.haoai.agent.agent.browser.BrowserController.ready) return noBrowser()
        val dir = args.optString("direction").ifBlank { "down" }
        return ToolResult(
            com.haoai.agent.agent.browser.BrowserController.scroll(dir, args.optDouble("amount") ?: 0.7)
        )
    }
}

class BrowserFindTool : Tool {

    override val name = "browser_find"
    override val description =
        "在内置浏览器页面查找文本（READ）：滚动到首个命中处并重新编号，返回命中列表" +
            "（含可交互元素编号）+ 最新结构，可直接 browser_click 命中编号。"
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("text") { put("type", "string") }
        }
    }

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        if (!com.haoai.agent.agent.browser.BrowserController.ready) return noBrowser()
        val text = args.optString("text")
        if (text.isBlank()) return ToolResult("缺少 text", true)
        return ToolResult(
            com.haoai.agent.agent.browser.BrowserController.findText(text).let { TextCap.middle(it, 8000) }
        )
    }
}

class BrowserBackTool : Tool {

    override val name = "browser_back"
    override val description = "内置浏览器后退一步（WRITE）。"
    override val parameters = buildJsonObject { put("type", "object") }

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        if (!com.haoai.agent.agent.browser.BrowserController.ready) return noBrowser()
        return ToolResult(com.haoai.agent.agent.browser.BrowserController.goBack())
    }
}

class BrowserScreenshotTool : Tool {

    override val name = "browser_screenshot"
    override val description =
        "截取内置浏览器当前视口（READ）：图像会注入对话，视觉模型可直接描述页面内容。" +
            "需要视觉判断（验证码/布局/图表）时用；常规结构信息用 browser_read 更省。"
    override val parameters = buildJsonObject { put("type", "object") }

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        if (!com.haoai.agent.agent.browser.BrowserController.ready) return noBrowser()
        val dataUrl = com.haoai.agent.agent.browser.BrowserController.screenshotDataUrl()
            ?: return ToolResult("截图失败：页面未加载或尺寸异常", true)
        return ToolResult("已截图，图像注入对话", imageDataUrl = dataUrl)
    }
}
