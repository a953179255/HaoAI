package com.haoai.agent.ui.chat

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Web
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.haoai.agent.ui.ChatRow

/**
 * 网页渲染预览（上游 render_with_webview 同款交互）：
 * - 消息含 ```html 代码块 → 直接渲染代码块内容（可运行的 artifact 预览）
 * - 无代码块 → markdown → HTML（fork HtmlGenerator，结构正确：嵌套列表/表格/
 *   标题层级）+ KaTeX 公式渲染（assets/katex 本地）+ highlight.js 代码高亮
 *   （assets/hljs 本地，MIT）。资产经 file:///android_asset baseURL 相对引用。
 * WebView 开 JS（渲染需要）、禁文件访问；离开即销毁防泄漏。
 */
@Composable
fun HtmlPreviewModal(
    row: ChatRow,
    onDismiss: () -> Unit
) {
    val dark = isSystemInDarkTheme()
    androidx.activity.compose.BackHandler(onBack = onDismiss)
    val context = LocalContext.current
    // 内容只与消息文本有关，recomposition 不重建/不重载 WebView
    val html = remember(row.text, dark) { buildPreviewHtml(row.text, dark) }
    val webView = remember {
        WebView(context).apply {
            @SuppressLint("SetJavaScriptEnabled")
            settings.javaScriptEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.domStorageEnabled = true
            // 上游 WebViewPage 同款：标准浏览器视口行为（无 viewport meta 的
            // artifact 按宽布局缩放适配，避免 ICB 失常导致的裁切/白屏）
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            // 网页 console 打进 logcat（上游 同款），排查渲染问题不再盲猜
            webChromeClient = object : android.webkit.WebChromeClient() {
                override fun onConsoleMessage(message: android.webkit.ConsoleMessage): Boolean {
                    android.util.Log.d(
                        "HtmlPreview",
                        "console: ${message.message()} @${message.sourceId()}:${message.lineNumber()}"
                    )
                    return true
                }
            }
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    android.util.Log.d("HtmlPreview", "onPageFinished url=$url")
                }
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose { webView.destroy() }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.Web,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(
                        "网页渲染预览",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "关闭",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .clickable(onClick = onDismiss)
                    )
                }
            }
            AndroidView(
                factory = { webView },
                update = { wv ->
                    // 上游 同款加载方式：明文 data + 虚拟 https 域名提供 origin
                    // （loadDataWithBaseURL 不做任何解码，此前白屏真因是模拟器 vh 固化
                    // bug，已由 fixVhUnits 解决，与加载方式无关）。
                    // update 先于布局执行，vh shim 依赖 innerHeight，故推迟到首次布局
                    // 完成后加载；tag 记录已加载内容避免重组重复加载（对应 上游 的
                    // lastLoadedData 守卫）
                    if (wv.tag != html) {
                        wv.tag = html
                        androidx.core.view.OneShotPreDrawListener.add(wv) {
                            // baseURL 指向 assets：模板相对引用 katex/hljs 本地资产
                            wv.loadDataWithBaseURL(
                                "file:///android_asset/", html, "text/html", "utf-8", null
                            )
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )
        }
    }
}

private fun buildPreviewHtml(text: String, dark: Boolean): String {
    val blocks = Regex("```\\s*html[ \\t]*\\r?\\n([\\s\\S]*?)```", RegexOption.IGNORE_CASE)
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
    val flavour = org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor(useSafeLinks = false)
    val tree = org.intellij.markdown.parser.MarkdownParser(flavour).buildMarkdownTreeFromString(md)
    org.intellij.markdown.html.HtmlGenerator(md, tree, flavour).generateHtml()
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
    val patched = html.replace(Regex("([0-9.]+)vh"), "calc($1 * var(--haoai-vh))")
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
    val head = Regex("(?i)<head[^>]*>").find(patched)
    return when {
        head != null -> patched.insert(head.range.last + 1, shim)
        else -> {
            val htmlTag = Regex("(?i)<html[^>]*>").find(patched)
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
        val fence = Regex("^```\\w*[ \\t]*$").matches(raw.trim())
        if (fence) {
            if (inCode) { out.append("</code></pre>\n"); inCode = false } else { closeList(); out.append("<pre><code>"); inCode = true }
            continue
        }
        if (inCode) { out.append(raw).append('\n'); continue }
        val line = raw.trimEnd()
        val heading = Regex("^(#{1,6})\\s+(.*)$").find(line)
        val ul = Regex("^[-*+]\\s+(.*)$").find(line)
        val ol = Regex("^\\d+[.)]\\s+(.*)$").find(line)
        val quote = Regex("^&gt;\\s?(.*)$").find(line)
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
    t = Regex("`([^`]+)`").replace(t) { "<code>" + it.groupValues[1] + "</code>" }
    t = Regex("\\*\\*([^*]+)\\*\\*").replace(t) { "<b>" + it.groupValues[1] + "</b>" }
    t = Regex("(?<!\\*)\\*([^*]+)\\*(?!\\*)").replace(t) { "<i>" + it.groupValues[1] + "</i>" }
    t = Regex("\\[([^\\]]+)]\\(([^)]+)\\)").replace(t) { "<a href=\"" + it.groupValues[2] + "\">" + it.groupValues[1] + "</a>" }
    return t
}
