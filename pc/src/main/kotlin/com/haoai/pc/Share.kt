package com.haoai.pc

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date

/**
 * 会话分享快照：把一条会话导出成**一个自包含的只读 HTML**。
 *
 * 为什么不是那份 markdown：markdown 是"给机器与编辑器"的形态，
 * 发给别人要先有个能渲染的人。OpenCode 的 `/share` 给的是"打开就能看"的成品，
 * 那才是"我这次跑出来的东西给你看一眼"该有的样子。
 *
 * 三条规矩：
 * 1. **没有一行 JS**。只读快照里出现脚本＝把"给人看"变成"在别人机器上跑"。
 * 2. **不引外部资源**（字体、CDN、图片链接一概不写），断网也能看，也不会泄露访问记录。
 * 3. 落在 `HAOAI_HOME/share/`，读回来时只认一个干净的文件名 ——
 *    这个入口的 name 参数来自 URL，`../` 必须挡死。
 */
object Share {

    fun dir(): File = File(Env.home, "share")

    /** 快照文件名：`pc-<sid>-<时间>.html`，只留安全字符。 */
    fun nameFor(sid: String): String =
        "pc-" + sanitize(sid) + "-" + SimpleDateFormat("yyyyMMdd-HHmmss").format(Date()) + ".html"

    fun sanitize(raw: String): String = raw.replace(Regex("""[^A-Za-z0-9._\-]"""), "_")

    /**
     * URL 传进来的名字：只允许"我们自己写出去的那种文件名"。
     * 返回 null = 拒绝。`../`、绝对路径、`.html` 之外的后缀全都进不来。
     */
    fun safeName(raw: String): String? {
        val n = raw.substringAfterLast('/').substringAfterLast('\\').trim()
        if (!n.endsWith(".html")) return null
        if (n != sanitize(n)) return null
        if (n.contains("..")) return null
        return n.takeIf { it.length in 6..160 }
    }

    private fun esc(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;")

    /**
     * 一小撮 markdown → HTML。**先转义再套标签**，所以正文里的 `<script>`
     * 只会变成文本，不可能变成标签。
     *
     * 为什么只做一撮：快照是"发给别人打开就能看"的成品，助手回答里全是
     * 代码块、小标题和项目符号，原样贴出来就像贴了源码 —— 但把 md.js 整套搬过来
     * 要么引外部脚本（这条页不许有），要么在 Kotlin 里重写一个渲染器（不值）。
     * 所以只认这几样：围栏代码块、行内代码、`#` 小标题、`-`/`*` 列表、`**粗**`。
     */
    fun mdToHtml(src: String): String {
        val out = StringBuilder()
        val lines = src.replace("\r\n", "\n").split("\n")
        var i = 0
        var inList = false
        val closeList = { if (inList) { out.append("</ul>"); inList = false } }
        while (i < lines.size) {
            val l = lines[i]
            if (l.trim().startsWith("```")) {
                closeList()
                val lang = l.trim().removePrefix("```").trim()
                val body = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trim().startsWith("```")) {
                    body.append(lines[i]).append('\n'); i++
                }
                i++   // 跳过收尾的 ```（没有收尾也照样结束，别死循环）
                out.append("<pre class=\"code\" data-lang=\"").append(esc(lang))
                    .append("\">").append(esc(body.toString().trimEnd())).append("</pre>")
                continue
            }
            val h = HEAD.find(l)
            if (h != null) {
                closeList()
                out.append("<h4>").append(inline(h.groupValues[2])).append("</h4>")
                i++
                continue
            }
            if (BULLET.containsMatchIn(l)) {
                if (!inList) { out.append("<ul>"); inList = true }
                val item = BULLET.find(l)?.let { l.removeRange(it.range) } ?: l
                out.append("<li>").append(inline(item)).append("</li>")
                i++
                continue
            }
            closeList()
            if (l.isBlank()) i++ else {
                out.append("<p>").append(inline(l)).append("</p>"); i++
            }
        }
        closeList()
        return out.toString()
    }

    private fun inline(s: String): String {
        val e = esc(s)
        return Regex("`([^`]+)`").replace(e) { "<code>${it.groupValues[1]}</code>" }
            .let { Regex("""\*\*([^*]+)\*\*""").replace(it) { "<b>${it.groupValues[1]}</b>" } }
    }

    private val BULLET = Regex("""^\s*[-*]\s+""")
    private val HEAD = Regex("^(#{1,6})\\s+(.*)$")

    /** 一条消息 → 一段 HTML。工具行只留第一行并截断：快照是给人看结论的，不是日志转储。 */
    private fun block(role: String, name: String, text: String): String {
        if (text.isBlank() && role != "tool") return ""
        val who = when (role) {
            "user" -> "你"
            "assistant" -> "助手"
            else -> "工具" + if (name.isBlank()) "" else " · $name"
        }
        val body = if (role == "tool") text.lineSequence().first().take(400) else mdToHtml(text)
        return "<section class=\"${esc(role)}\"><h3>${esc(who)}</h3>" +
            (if (role == "tool") "<p>${esc(body)}</p>" else body) + "</section>\n"
    }

    /**
     * 渲染整页。`msgs` 是三元组（role, toolName, content），
     * 故意不依赖 Msg 类型：这样 Server 与测试都能直接喂。
     */
    fun render(
        title: String, workspace: String, model: String, mode: String,
        madeAt: Long, msgs: List<Triple<String, String, String>>
    ): String = buildString {
        append("<!doctype html><meta charset=\"utf-8\">")
        append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
        append("<title>").append(esc(title)).append(" · HaoAI 会话快照</title>")
        append("<style>")
        append(":root{color-scheme:dark}")
        append("body{margin:0;background:#0f1512;color:#e8f0ea;")
        append("font:15px/1.65 ui-sans-serif,system-ui,\"Segoe UI\",sans-serif}")
        append("main{max-width:820px;margin:0 auto;padding:28px 20px 64px}")
        append("h1{font-size:22px;margin:0 0 6px}")
        append(".meta{color:#8fa39a;font-size:12.5px;margin-bottom:22px;word-break:break-all}")
        append("section{border:1px solid #22322b;border-radius:12px;padding:12px 14px;margin:0 0 12px;background:#141d19}")
        append("section.user{border-color:#2c4a3e}")
        append("section.tool{background:#111815;color:#a9bcb2;font-size:13px}")
        append("h3{margin:0 0 6px;font-size:12px;letter-spacing:.06em;text-transform:uppercase;color:#78907f}")
        append("p{margin:0 0 6px;white-space:pre-wrap;word-break:break-word}")
        append("p:last-child{margin:0}")
        append("h4{font-size:15px;margin:10px 0 4px}")
        append("ul{margin:4px 0;padding-left:22px}li{margin:0 0 3px}")
        append("pre.code{background:#0b100e;border:1px solid #22322b;border-radius:8px;padding:10px 12px;")
        append("overflow:auto;font:13px/1.6 ui-monospace,Consolas,monospace;white-space:pre-wrap}")
        append("code{background:#0b100e;border-radius:4px;padding:1px 5px;font:13px ui-monospace,Consolas,monospace}")
        append("pre.code code{background:none;padding:0;border:0}")
        append("footer{color:#6f8479;font-size:12px;margin-top:26px}")
        append("</style>")
        append("<main><h1>").append(esc(title)).append("</h1>")
        append("<div class=\"meta\">")
        append("模型 ").append(esc(model.ifBlank { "—" })).append(" · 档位 ")
            .append(esc(mode)).append(" · 工作区 ").append(esc(workspace.ifBlank { "—" })).append(" · ")
            .append(esc(SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date(madeAt))))
            .append("</div>")
        val body = msgs.joinToString("") { block(it.first, it.second, it.third) }
        append(if (body.isBlank()) "<section><h3>空</h3><p>这条会话还没有说过话。</p></section>" else body)
        append("<footer>由 HaoAI PC 端导出的只读快照 · 页面里没有脚本，也不会连任何外部地址</footer>")
        append("</main>")
    }

    /** 落盘。写不进去就返回 null（调用方要如实报错，别回一个"成功"加空路径）。 */
    fun write(name: String, html: String): File? = runCatching {
        dir().mkdirs()
        val f = File(dir(), sanitize(name))
        f.writeText(html, Charsets.UTF_8)
        if (f.isFile && f.canonicalFile.parentFile == dir().canonicalFile) f else null
    }.getOrNull()

    fun read(name: String): File? = safeName(name)?.let { n ->
        runCatching { File(dir(), n).canonicalFile }
            .getOrNull()?.takeIf { it.isFile && it.parentFile == runCatching { dir().canonicalFile }.getOrNull() }
    }
}
