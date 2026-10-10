package com.haoai.agent.ui.chat

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.haoai.agent.ui.ChatRow

// 解析正则（2026-10-10 归并：原散在 buildPreviewHtml/fixVhUnits/markdownToSimpleHtml/
// inline 里每次调用 new Regex；Regex 无状态、线程安全，提为顶层常量）
private val HTML_FENCE =
    Regex("```\\s*html[ \\t]*\\r?\\n([\\s\\S]*?)```", RegexOption.IGNORE_CASE)
private val VH_UNIT = Regex("([0-9.]+)vh")
private val HEAD_OPEN = Regex("(?i)<head[^>]*>")
private val HTML_OPEN = Regex("(?i)<html[^>]*>")
private val FENCE_LINE = Regex("^```\\w*[ \\t]*$")
private val HEADING = Regex("^(#{1,6})\\s+(.*)$")
private val UL_ITEM = Regex("^[-*+]\\s+(.*)$")
private val OL_ITEM = Regex("^\\d+[.)]\\s+(.*)$")
private val QUOTE_LINE = Regex("^&gt;\\s?(.*)$")
private val CODE_SPAN = Regex("`([^`]+)`")
private val BOLD_SPAN = Regex("\\*\\*([^*]+)\\*\\*")
private val ITALIC_SPAN = Regex("(?<!\\*)\\*([^*]+)\\*(?!\\*)")
private val LINK_SPAN = Regex("\\[([^\\]]+)]\\(([^)]+)\\)")

// markdown 解析器单例（2026-10-10 归并：原 markdownToHtml 每次新建 flavour+parser，
// 与同项目 ui/common/MdAst.kt 的 by lazy 单例解决的是同一问题，向它对齐）
private val gfmFlavour by lazy { org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor(useSafeLinks = false) }
private val gfmParser by lazy { org.intellij.markdown.parser.MarkdownParser(gfmFlavour) }

/**
 * 网页渲染预览（更多 → 网页预览）：
 * - 消息含 ```html 代码块 → 直接渲染代码块内容（可运行的 artifact 预览）
 * - 无代码块 → markdown → HTML（fork HtmlGenerator，结构正确：嵌套列表/表格/
 *   标题层级）+ KaTeX 公式渲染（assets/katex 本地）+ highlight.js 代码高亮
 *   （assets/hljs 本地，MIT）。资产经 file:///android_asset baseURL 相对引用。
 * 批2a 起 UI 收编进统一壳 HtmlPreviewDialog（与效果稿全屏/产物卡预览同源），
 * 本文件只负责「消息文本 → 可预览 HTML」的内容构建；自建 WebView 已删。
 */
@Composable
fun HtmlPreviewModal(
    row: ChatRow,
    onDismiss: () -> Unit
) {
    val dark = isSystemInDarkTheme()
    // 内容只与消息文本有关，recomposition 不重建/不重载 WebView
    val html = remember(row.text, dark) { buildPreviewHtml(row.text, dark) }
    com.haoai.agent.ui.common.HtmlPreviewDialog(
        title = "网页渲染预览",
        source = com.haoai.agent.ui.common.HtmlPreviewSource.Content(
            html = html,
            baseUrl = "file:///android_asset/",
            dedupKey = html
        ),
        // 深色模板底色传给壳，首绘不白闪
        pageBackgroundColor = if (dark) 0xFF0D1117.toInt() else 0xFFFFFFFF.toInt(),
        onDismiss = onDismiss
    )
}

private fun buildPreviewHtml(text: String, dark: Boolean): String {
    val blocks = HTML_FENCE
        .findAll(text)
        .map { it.groupValues[1] }
        .toList()
    // artifact 代码块本身是完整文档 → 直接整页加载（包模板会因嵌套 html 解析异常白屏）
    val html = if (blocks.isNotEmpty()) {
        val full = blocks.firstOrNull {
            it.contains("<!DOCTYPE", ignoreCase = true) || it.contains("<html", ignoreCase = true)
        }
        if (full != null) full
        else wrapInTemplate(blocks.joinToString("\n<hr style=\"opacity:.25\">\n"), dark, fullDoc = false)
    } else {
        wrapInTemplate(markdownToHtml(text), dark, fullDoc = true)
    }
    return fixVhUnits(html)
}

/**
 * markdown → HTML（fork HtmlGenerator，与聊天内渲染同源）：
 * 嵌套列表/表格/标题层级天然正确；代码块输出 <pre><code class="language-xxx">
 * （highlight.js 约定）；数学输出 <span class="math" inline="...">（KaTeX 处理）。
 */
private fun markdownToHtml(md: String): String = runCatching {
    val tree = gfmParser.buildMarkdownTreeFromString(md)
    org.intellij.markdown.html.HtmlGenerator(md, tree, gfmFlavour).generateHtml()
}.getOrElse {
    // 兜底：解析失败退回极简转换，绝不空白
    markdownToSimpleHtml(md)
}

/**
 * 模拟器 WebView 的 vh 固化 bug：页面加载后 CSS vh 恒为 0（实测 window.innerHeight=840
 * 正确、20vh 却解析为 0，且不随视口重算），致 100vh 背景/整页布局白屏。
 * 绕过：把 Nvh 重写为 calc(N * var(--haoai-vh))，并注入脚本用 innerHeight（实测为活值）
 * 实时维护该变量；resize 时同步刷新。
 */
private fun fixVhUnits(html: String): String {
    if (!html.contains("vh")) return html
    val patched = html.replace(VH_UNIT, "calc($1 * var(--haoai-vh))")
    val shim = "<style>:root{--haoai-vh:1vh}</style>" +
        "<script>(function(){var d=document.documentElement,f=function(){" +
        "d.style.setProperty('--haoai-vh',(window.innerHeight/100)+'px')};f();" +
        "window.addEventListener('resize',f);window.addEventListener('load',f);" +
        // 模拟器 WebView 的 canvas 层渐变不绘制（div 级渐变/纯色正常，真机无此问题）：
        // 把 body 计算后的背景镜像到固定背景 div，走 div 绘制路径兜底
        "function m(){try{var b=getComputedStyle(document.body);if(!b)return;" +
        "var s=getComputedStyle(document.documentElement);" +
        "if(b.backgroundImage==='none'&&b.backgroundColor==='rgba(0, 0, 0, 0)')return;" +
        "var m=document.getElementById('haoai-bg-mirror');" +
        "if(!m){m=document.createElement('div');m.id='haoai-bg-mirror';d.appendChild(m)}" +
        "m.style.cssText='position:fixed;inset:0;z-index:-1;pointer-events:none;background:'+b.background" +
        "}catch(e){}}window.addEventListener('load',m);setTimeout(m,0)})();</script>"
    // 注入点：<head> 内最稳（vh shim 无 body 依赖）；无 head 则 <html> 之后；纯片段前插
    val head = HEAD_OPEN.find(patched)
    return when {
        head != null -> patched.insert(head.range.last + 1, shim)
        else -> {
            val htmlTag = HTML_OPEN.find(patched)
            if (htmlTag != null) patched.insert(htmlTag.range.last + 1, shim)
            else shim + patched
        }
    }
}

private fun String.insert(index: Int, text: String): String =
    substring(0, index) + text + substring(index)

private fun wrapInTemplate(bodyHtml: String, dark: Boolean, fullDoc: Boolean): String {
    val bg = if (dark) "#121318" else "#ffffff"
    val fg = if (dark) "#e4e4e9" else "#1b1b1f"
    val codeBg = if (dark) "#1e1f26" else "#f4f4f6"
    val inlineCodeBg = if (dark) "#26272f" else "#eeeef2"
    val border = if (dark) "#3a3b44" else "#ccc"
    val link = if (dark) "#8ab4f8" else "#1a73e8"
    val quote = if (dark) "#555" else "#bbb"
    val hlBg = if (dark) "#1e1f26" else "#f6f7f9"
    // 渲染脚本（仅 markdown 全文档路径注入；artifact html 块保持纯净）：
    // KaTeX 渲染 fork HtmlGenerator 输出的 <span class="math" inline="..">，
    // highlight.js 高亮 <pre><code class="language-xxx">——两者均为本地 assets。
    // 脚本置于 body 末尾（body 已解析），且 load+setTimeout 双触发幂等渲染
    val scripts = if (fullDoc) {
        "<link rel=\"stylesheet\" href=\"katex/katex.min.css\">" +
            "<script src=\"katex/katex.min.js\"></script>" +
            "<script src=\"hljs/highlight.min.js\"></script>" +
            "<style>pre code.hljs{background:transparent;padding:0}" +
            "code.hljs{background:$hlBg;border-radius:6px}" +
            // AtomOne Light（与聊天内 CodeHighlight 配色同体系）
            ".hljs{color:#383a42}" +
            ".hljs-keyword{color:#a626a4}" +
            ".hljs-string{color:#50a14f}" +
            ".hljs-comment{color:#a0a1a7;font-style:italic}" +
            ".hljs-number,.hljs-literal{color:#986801}" +
            ".hljs-title,.hljs-function .hljs-title,.hljs-built_in,.hljs-name{color:#4078f2}" +
            ".hljs-attr,.hljs-attribute{color:#986801}" +
            ".hljs-symbol,.hljs-bullet{color:#0184bc}" +
            ".hljs-section{color:#4078f2;font-weight:bold}" +
            ".hljs-meta{color:#0184bc}</style>" +
            "<script>function haoaiRender(){try{hljs.highlightAll()}catch(e){console.log('hljs: '+e)}" +
            "try{if(typeof katex==='undefined'){console.log('katex missing');return}" +
            "var els=document.querySelectorAll('span.math');" +
            "console.log('math spans='+els.length);" +
            "for(var i=0;i<els.length;i++){var el=els[i];if(el.dataset.done)continue;el.dataset.done=1;" +
            "var tex=el.textContent;var disp=el.getAttribute('inline')==='false';" +
            "try{katex.render(tex,el,{displayMode:disp,throwOnError:false})}" +
            "catch(e){console.log('katex: '+e);el.textContent=tex}}}catch(e){console.log('loop: '+e)}}" +
            "haoaiRender();window.addEventListener('load',haoaiRender);" +
            "setTimeout(haoaiRender,400);</script>"
    } else ""
    return """<!DOCTYPE html>
<html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<link rel="stylesheet" href="katex/katex.min.css">
<style>
body{font-family:system-ui,-apple-system,sans-serif;margin:12px;color:$fg;background:$bg;
font-size:15px;line-height:1.55;word-break:break-word}
h1,h2,h3,h4{margin:.6em 0 .3em}
pre{background:$codeBg;padding:10px;border-radius:8px;overflow:auto}
code{font-family:monospace;font-size:.92em}
:not(pre)>code{background:$inlineCodeBg;padding:1px 5px;border-radius:4px}
ul,ol{margin:.3em 0;padding-left:1.6em}
li{margin:.15em 0}
li>ul,li>ol{margin:.1em 0}
/* 任务清单：自绘勾选框对齐聊天内绿色（disabled 原生控件在部分 WebView 忽略 accent-color），去列表圆点 */
li:has(>input[type=checkbox]){list-style:none;margin-left:-1.35em}
input[type=checkbox]{-webkit-appearance:none;appearance:none;width:15px;height:15px;vertical-align:-2px;margin-right:5px;border:1.5px solid rgba(128,128,128,.55);border-radius:3px;background:transparent}
input[type=checkbox]:checked{background:#7BD88F;border-color:#7BD88F;position:relative}
input[type=checkbox]:checked::after{content:"";position:absolute;left:4.5px;top:1.5px;width:4px;height:8px;border:solid #fff;border-width:0 2px 2px 0;transform:rotate(45deg)}
table{border-collapse:collapse;margin:.5em 0}
td,th{border:1px solid $border;padding:5px 10px}
a{color:$link}
blockquote{border-left:3px solid $quote;margin:.4em 0;padding:.1em .8em;opacity:.85}
span.math{white-space:normal}
</style></head><body>$bodyHtml""" + scripts + "</body></html>"
}

/** 极简 markdown → HTML（预览兜底用，不求完备）：转义 → 围栏代码 → 标题/列表/行内样式。 */
private fun markdownToSimpleHtml(md: String): String {
    val esc = md
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    val out = StringBuilder()
    var inCode = false
    var inList: String? = null // "ul" / "ol"
    fun closeList() {
        if (inList != null) { out.append("</").append(inList).append(">\n"); inList = null }
    }
    for (raw in esc.lines()) {
        val fence = FENCE_LINE.matches(raw.trim())
        if (fence) {
            if (inCode) { out.append("</code></pre>\n"); inCode = false } else { closeList(); out.append("<pre><code>"); inCode = true }
            continue
        }
        if (inCode) { out.append(raw).append('\n'); continue }
        val line = raw.trimEnd()
        val heading = HEADING.find(line)
        val ul = UL_ITEM.find(line)
        val ol = OL_ITEM.find(line)
        val quote = QUOTE_LINE.find(line)
        when {
            line.isBlank() -> { closeList(); out.append("<br>\n") }
            heading != null -> { closeList(); val h = heading.groupValues[1].length; out.append("<h$h>").append(inline(heading.groupValues[2])).append("</h$h>\n") }
            quote != null -> { closeList(); out.append("<blockquote>").append(inline(quote.groupValues[1])).append("</blockquote>\n") }
            ul != null -> {
                if (inList != "ul") { closeList(); out.append("<ul>\n"); inList = "ul" }
                out.append("<li>").append(inline(ul.groupValues[1])).append("</li>\n")
            }
            ol != null -> {
                if (inList != "ol") { closeList(); out.append("<ol>\n"); inList = "ol" }
                out.append("<li>").append(inline(ol.groupValues[1])).append("</li>\n")
            }
            else -> { closeList(); out.append(inline(line)).append("<br>\n") }
        }
    }
    closeList()
    if (inCode) out.append("</code></pre>")
    return out.toString()
}

/** 行内样式：`code`、**粗**、*斜*、[文本](链接)。 */
private fun inline(s: String): String {
    var t = s
    t = CODE_SPAN.replace(t) { "<code>" + it.groupValues[1] + "</code>" }
    t = BOLD_SPAN.replace(t) { "<b>" + it.groupValues[1] + "</b>" }
    t = ITALIC_SPAN.replace(t) { "<i>" + it.groupValues[1] + "</i>" }
    t = LINK_SPAN.replace(t) { "<a href=\"" + it.groupValues[2] + "\">" + it.groupValues[1] + "</a>" }
    return t
}
