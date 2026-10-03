package com.haoai.pc

import com.sun.net.httpserver.HttpExchange
import com.haoai.pc.WebServer.Body
import java.io.File

/**
 * 知识库的 Web 面：`/api/kbs`（库与文档的增删改查、重新索引、预览、试检索）
 * 与 `/api/kbfile`（下载原文）。
 *
 * 与老口 `/api/kb`（工作区 `.haoai-kb/` 语料）并存：那一份是"这个项目的参考文档"，
 * 这一份是"这个人的资料库"（跨项目、能绑专家）。两者的检索最终都落到 [SemanticIndex]，
 * 所以不会有两套索引逻辑互相漂移。
 */

/** 查询串参数（同一个正则套路，见 ServerUsage）。 */
private fun HttpExchange.q(name: String): String =
    Regex("""(?:^|&)$name=([^&]*)""").find(requestURI.rawQuery ?: "")
        ?.groupValues?.get(1)
        ?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault("") } ?: ""

internal fun WebServer.kbs(ex: HttpExchange) {
    val b = Body(ex)
    val kb = b.str("kb").ifBlank { ex.q("kb") }
    val name = b.str("name").ifBlank { ex.q("name") }
    if (ex.requestMethod != "GET") {
        when (b.str("op")) {
            "create" -> {
                val (base, err) = Knowledge.create(b.str("title"), b.str("desc"))
                if (err != null) {
                    send(ex, 200, """{"ok":false,"error":${quote(err)}}""",
                        "application/json; charset=utf-8"); return
                }
                publish("kbs", Knowledge.json(settings.embedUrl.isNotBlank()))
                base?.let { }
            }
            "del" -> if (!Knowledge.remove(kb)) {
                send(ex, 200, """{"ok":false,"error":"没有这个知识库"}""",
                    "application/json; charset=utf-8"); return
            } else publish("kbs", Knowledge.json(settings.embedUrl.isNotBlank()))
            "rename", "toggle" -> {
                val nb = Knowledge.rename(
                    kb,
                    name = b.str("title").ifBlank { null },
                    desc = if (b.str("op") == "rename") b.str("desc") else null,
                    defaultOpen = if (b.str("op") == "toggle") b.str("on") != "false" else null
                )
                if (nb == null) {
                    send(ex, 200, """{"ok":false,"error":"没有这个知识库"}""",
                        "application/json; charset=utf-8"); return
                }
                publish("kbs", Knowledge.json(settings.embedUrl.isNotBlank()))
            }
            "add" -> {
                val text = b.str("text")
                if (text.isBlank()) {
                    send(ex, 200, """{"ok":false,"error":"内容是空的"}""",
                        "application/json; charset=utf-8"); return
                }
                val (doc, err) = Knowledge.addDoc(kb, name.ifBlank { "pasted.md" },
                    text.toByteArray(Charsets.UTF_8))
                if (doc == null) {
                    send(ex, 200, """{"ok":false,"error":${quote(err ?: "没收进来")}}""",
                        "application/json; charset=utf-8"); return
                }
                // 解析失败也要 200 + 那一条 failed 记录：列表里必须看得见"这个没吃进去"
                publish("kbs", Knowledge.json(settings.embedUrl.isNotBlank()))
                send(ex, 200, """{"ok":${doc.status == Knowledge.READY},"status":${quote(doc.status)},""" +
                    """"error":${quote(doc.error)},"chunks":${doc.chunks},"chars":${doc.chars}}""",
                    "application/json; charset=utf-8")
                return
            }
            // 二进制（docx/pptx/xlsx/pdf）：浏览器读成 base64 再 POST，服务端解包抽正文
            "addb64" -> {
                val raw = runCatching {
                    java.util.Base64.getMimeDecoder().decode(b.str("data"))
                }.getOrNull()
                if (raw == null || raw.isEmpty()) {
                    send(ex, 200, """{"ok":false,"error":"文件内容没传上来"}""",
                        "application/json; charset=utf-8"); return
                }
                val (doc, err) = Knowledge.addDoc(kb, name, raw)
                if (doc == null) {
                    send(ex, 200, """{"ok":false,"error":${quote(err ?: "没收进来")}}""",
                        "application/json; charset=utf-8"); return
                }
                publish("kbs", Knowledge.json(settings.embedUrl.isNotBlank()))
                send(ex, 200, """{"ok":${doc.status == Knowledge.READY},"status":${quote(doc.status)},""" +
                    """"error":${quote(doc.error)},"chunks":${doc.chunks},"chars":${doc.chars}}""",
                    "application/json; charset=utf-8")
                return
            }
            "delDoc" -> {
                if (!Knowledge.dropDoc(kb, name)) {
                    send(ex, 200, """{"ok":false,"error":"没有这份文档"}""",
                        "application/json; charset=utf-8"); return
                }
                publish("kbs", Knowledge.json(settings.embedUrl.isNotBlank()))
            }
            "reindex" -> {
                val (doc, err) = Knowledge.reindex(kb, name)
                if (doc == null) {
                    send(ex, 200, """{"ok":false,"error":${quote(err ?: "重新索引失败")}}""",
                        "application/json; charset=utf-8"); return
                }
                send(ex, 200, """{"ok":${doc.status == Knowledge.READY},"status":${quote(doc.status)},""" +
                    """"error":${quote(doc.error)}}""", "application/json; charset=utf-8")
                return
            }
            "reindexAll" -> {
                val n = Knowledge.reindexAll(kb)
                send(ex, 200, """{"ok":true,"ready":$n}""", "application/json; charset=utf-8"); return
            }
            "search" -> {
                val ids = if (kb.isBlank()) Knowledge.load().map { it.id } else listOf(kb)
                val (hits, note) = Knowledge.search(ids, b.str("q"), settings.embedUrl, top = 6)
                val hs = hits.joinToString(",", "[", "]") { h ->
                    """{"kb":${quote(h.kbName)},"doc":${quote(h.doc)},"how":${quote(h.how)},""" +
                        """"score":${"%.3f".format(h.score)},"snippet":${quote(h.snippet)}}"""
                }
                send(ex, 200, """{"ok":true,"hits":$hs,"note":${quote(note ?: "")},""" +
                    """"embed":${settings.embedUrl.isNotBlank()}}""", "application/json; charset=utf-8")
                return
            }
            "preview" -> {
                val t = Knowledge.textOf(kb, name)
                if (t == null) {
                    send(ex, 200, """{"ok":false,"error":"没有抽出来的正文（解析失败或原文缺失）"}""",
                        "application/json; charset=utf-8"); return
                }
                send(ex, 200, """{"ok":true,"text":${quote(t.take(20000))},"chars":${t.length}}""",
                    "application/json; charset=utf-8")
                return
            }
        }
    }
    send(ex, 200, Knowledge.json(settings.embedUrl.isNotBlank()), "application/json; charset=utf-8")
}

/**
 * `GET /api/kbfile?kb=&name=` —— 下载原文（不是抽出来的正文）。
 *
 * 路径必须两头都验：`kb` 要是真存在的库 id，`name` 要过 [Knowledge.safeName] 同款的
 * "只留文件名本体" —— 否则 `?name=../../settings.json` 就把状态根里的别的文件递出去了。
 */
internal fun WebServer.kbFile(ex: HttpExchange) {
    val kb = ex.q("kb")
    val name = ex.q("name")
    val f: File? = if (Knowledge.find(kb) == null) null else Knowledge.srcFile(kb, name)
    if (f == null) {
        send(ex, 404, "没有这份原文（库不存在、文件名不收，或者它是在这次改动之前导入的）",
            "text/plain; charset=utf-8"); return
    }
    val bytes = f.readBytes()
    ex.responseHeaders.add("Content-Type", "application/octet-stream")
    ex.responseHeaders.add("Content-Disposition", """attachment; filename="${f.name.replace("\"", "")}"""")
    ex.responseHeaders.add("Cache-Control", "no-store")
    ex.sendResponseHeaders(200, bytes.size.toLong())
    ex.responseBody.use { it.write(bytes) }
}
