package com.haoai.pc

import com.sun.net.httpserver.HttpExchange
import com.haoai.pc.WebServer.Body
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * 记忆漏斗 + 画像 + 日记 + 主动关心的 Web 面（B6/B8）：`/api/memory-funnel`。
 *
 * `GET ?what=candidates|profile|episodes|care`
 * `POST {op:'promote'|'reject', id}` / `{op:'extract', sid?}` / `{op:'regenProfile', sid}` /
 * `{op:'care', enabled:0|1}`
 */
internal fun WebServer.memoryFunnel(ex: HttpExchange) {
    val b = Body(ex)
    if (ex.requestMethod == "GET") {
        when (queryOf(ex, "what").ifBlank { "candidates" }) {
            "profile" -> {
                val text = runCatching { MemoryProfile.read() }.getOrDefault("")
                send(ex, 200, """{"ok":true,"content":${quote(text)}}""", "application/json; charset=utf-8")
            }
            "episodes" -> {
                val items = Episodes.list(30).joinToString(",") { line ->
                    // jsonl 每行已是合法 json，原样回传（行里的引号已转义过，不再二次 quote）
                    runCatching { Json.parseToJsonElement(line); line }.getOrDefault("{}")
                }
                send(ex, 200, """{"ok":true,"items":[$items]}""", "application/json; charset=utf-8")
            }
            "care" -> {
                val on = PcSettings.load().flags["proactiveCare"] == true
                val journal = runCatching {
                    val f = java.io.File(Env.home, "memory-care.jsonl")
                    if (f.isFile) f.readLines().filter { it.isNotBlank() }.takeLast(5).reversed() else emptyList<String>()
                }.getOrDefault(emptyList())
                send(ex, 200, """{"ok":true,"enabled":${on},"journal":[${journal.joinToString(",")}]}""",
                    "application/json; charset=utf-8")
            }
            else -> {
                val all = MemoryCandidates.all()
                val cands = all.joinToString(",") { c ->
                    """{"id":${quote(c.id)},"type":${quote(c.type)},"title":${quote(c.title)},""" +
                        """"assertion":${quote(c.assertion)},"quote":${quote(c.quote)},"status":${quote(c.status)},"created":${c.created}}"""
                }
                send(ex, 200,
                    """{"ok":true,"items":[$cands],"pending":${all.count { it.status == "pending" }}}""",
                    "application/json; charset=utf-8")
            }
        }
        return
    }
    when (b.str("op")) {
        "promote" -> send(ex, 200, """{"ok":${MemoryCandidates.promote(b.str("id"))}}""",
            "application/json; charset=utf-8")
        "reject" -> send(ex, 200, """{"ok":${MemoryCandidates.reject(b.str("id"))}}""",
            "application/json; charset=utf-8")
        "extract" -> {
            val sid = b.str("sid").ifBlank { pick("") }
            val e = sessions[sid]?.engine
            if (e == null) {
                send(ex, 200, """{"ok":false,"error":"这条会话的引擎不在（先点开它）"}""",
                    "application/json; charset=utf-8"); return
            }
            e.extractMemoryAsync(force = true)
            send(ex, 200, """{"ok":true,"note":"已开始提炼，几秒后回来刷新"}""",
                "application/json; charset=utf-8")
        }
        "regenProfile" -> {
            val sid = b.str("sid").ifBlank { pick("") }
            val e = sessions[sid]?.engine
            if (e == null) {
                send(ex, 200, """{"ok":false,"error":"这条会话的引擎不在（先点开它）"}""",
                    "application/json; charset=utf-8"); return
            }
            val ok = e.regenProfile()
            send(ex, 200, """{"ok":${ok},"error":${if (ok) "\"\"" else quote("生成失败：模型没回出可画像的内容")}}""",
                "application/json; charset=utf-8")
        }
        "care" -> {
            val s = PcSettings.load()
            val on = b.str("enabled") == "1"
            PcSettings.save(s.copy(flags = s.flags + ("proactiveCare" to on)))
            send(ex, 200, """{"ok":true,"enabled":${on}}""", "application/json; charset=utf-8")
        }
    }
}
