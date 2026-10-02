package com.haoai.pc

import com.sun.net.httpserver.HttpExchange
import com.haoai.pc.WebServer.Body
import java.io.File

/**
 * 知识库（KB）的 Web 面：`/api/kb`。
 *
 * 定位：工作区里的 <b>`<ws>/.haoai-kb/`</b> 就是语料本身 —— 不另建数据库、不另建目录树。
 * 好处有三条：语义索引（[SemanticIndex]）本来就按工作区走，目录里的 md/txt 自动被增量索引，
 * 一行索引代码都不用加；跟着会话的工作区走，换项目就是换语料；备份/迁移工作区时语料跟着走。
 *
 * 检索进对话的路有两条，都是现成的：grep 0 命中回退语义近邻（search 工具）；
 * 引擎每回合的系统提示里会列出这份语料的存在（见 Engine 的 kbNote），模型知道去读。
 */

/** KB 收的扩展名：都是纯文本。PDF/DOCX 要解析器，v1 不做 —— 收进来读不了等于骗用户。 */
private val KB_EXTS = setOf("md", "txt", "csv", "json", "yml", "yaml", "log", "html", "xml")

/** 与 SemanticIndex.MAX_BYTES 同一口径：再大的文件索引阶段就会被丢，不如收的时候就拒。 */
private const val KB_MAX_BYTES = 200_000

private fun kbDir(ws: File): File = File(ws, ".haoai-kb")

/** 名字只留文件名本体：路径分隔符、`..`、开头点号一律挡掉 —— 语料名是给用户看的，不是寻址用的。 */
internal fun kbSafeName(raw: String): String? {
    val name = raw.trim().replace('\\', '/').substringAfterLast('/')
    if (name.isEmpty() || name.startsWith(".") || name.contains("..") || name.length > 120) return null
    val ext = name.substringAfterLast('.', "").lowercase()
    return if (ext in KB_EXTS) name else null
}

/**
 * `GET /api/kb?sid=` —— 语料清单 + 索引可用性。
 * `POST {op:'import'|'del'|'test', sid?, name?, text?, q?}`
 */
internal fun WebServer.kb(ex: HttpExchange) {
    val b = Body(ex)
    val sid = if (ex.requestMethod == "GET") querySid(ex) else b.str("sid")
    val ws = (sessions[sid]?.engine?.session?.workspace ?: settings.workspaceFile()).canonicalFile

    if (ex.requestMethod != "GET") {
        when (b.str("op")) {
            "import" -> {
                val name = kbSafeName(b.str("name"))
                    ?: run { send(ex, 200, """{"ok":false,"error":${quote("文件名不收：要 " + KB_EXTS.sorted().joinToString("/") + " 且不带路径")}}""", "application/json; charset=utf-8"); return }
                val text = b.str("text")
                if (text.isEmpty()) {
                    send(ex, 200, """{"ok":false,"error":"内容是空的"}""", "application/json; charset=utf-8"); return
                }
                if (text.toByteArray(Charsets.UTF_8).size > KB_MAX_BYTES) {
                    send(ex, 200, """{"ok":false,"error":${quote("超过 ${KB_MAX_BYTES / 1000}KB —— 先拆再导，太大的一篇检索出来也用不了")}}""",
                        "application/json; charset=utf-8"); return
                }
                val dir = kbDir(ws)
                runCatching { dir.mkdirs() }.getOrElse {
                    send(ex, 200, """{"ok":false,"error":"建不了 .haoai-kb 目录（工作区只读？）"}""",
                        "application/json; charset=utf-8"); return
                }
                // 同名覆盖是**有意的**：语料改名重导是最顺的更新路，弹"要不要覆盖"纯属多一步
                File(dir, name).writeText(text)
            }
            "del" -> {
                val name = kbSafeName(b.str("name"))
                if (name != null) {
                    val f = File(kbDir(ws), name)
                    if (f.isFile) f.delete()
                    // 语料删了，向量缓存里的旧块还挂着：下次查询会带着它说话。索引缓存按工作区哈希存，
                    // 直接删那份缓存文件，下一次查询自然全量重建（语料就几份，重建不贵）。
                    SemanticIndex.dropCache(ws)
                }
            }
            "test" -> {
                val q = b.str("q").trim()
                if (q.isEmpty()) {
                    send(ex, 200, """{"ok":false,"error":"先写一句要查的"}""", "application/json; charset=utf-8"); return
                }
                val r = SemanticIndex.query(ws, q, settings.embedUrl, top = 5)
                val hits = r.hits.joinToString(",", "[", "]") { h ->
                    """{"path":${quote(h.path)},"score":${"%.3f".format(h.score)},"snippet":${quote(h.snippet)}}"""
                }
                send(ex, 200, """{"ok":true,"hits":$hits,"note":${quote(r.note ?: "")}}""",
                    "application/json; charset=utf-8")
                return
            }
        }
    }
    val dir = kbDir(ws)
    val files = runCatching {
        dir.listFiles()?.filter { it.isFile }
            ?.sortedBy { it.name.lowercase() }
            ?.map { f -> """{"name":${quote(f.name)},"size":${f.length()},"updated":${f.lastModified()}}""" }
            ?: emptyList()
    }.getOrDefault(emptyList())
    val hasEmbed = settings.embedUrl.isNotBlank()
    send(ex, 200,
        """{"ok":true,"ws":${quote(ws.absolutePath)},"dir":".haoai-kb","embed":$hasEmbed,""" +
        """"exts":${KB_EXTS.sorted().joinToString(",", "[", "]") { quote(it) }},"items":[${files.joinToString(",")}]}""",
        "application/json; charset=utf-8")
}
