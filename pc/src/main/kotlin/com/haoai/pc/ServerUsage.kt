package com.haoai.pc

import com.sun.net.httpserver.HttpExchange
import java.nio.charset.StandardCharsets

/**
 * Token 统计的 Web 面：`/api/usage`（带区间与筛选的报表）与 `/api/usage/export`（下载 .xlsx）。
 *
 * 为什么导出走服务端而不是前端拼：前端手上只有**当前页签**那一张表，
 * 而导出要给五张（汇总 / 按天 / 按专家 / 按模型 / 明细）—— 明细那份要逐行读账本，
 * 把整个 jsonl 发给浏览器再拼 xlsx，等于把一台机器的账本搬到另一台上去算，
 * 算完还对不上（浏览器没有本地时区以外的信息，缓存 token 也没落进页面数据）。
 */

/** 查询串里的长整数：缺省 0（= 让 [UsageLedger.span] 用它自己的默认窗口）。 */
private fun HttpExchange.num(name: String): Long =
    Regex("""(?:^|&)$name=(-?\d+)""").find(requestURI.rawQuery ?: "")
        ?.groupValues?.get(1)?.toLongOrNull() ?: 0L

/** 查询串里的文本参数（URL 解码；专家名是中文，不解码就永远匹配不上）。 */
private fun HttpExchange.text(name: String): String =
    Regex("""(?:^|&)$name=([^&]*)""").find(requestURI.rawQuery ?: "")
        ?.groupValues?.get(1)
        ?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault("") } ?: ""

/**
 * `GET /api/usage?from=&to=&expert=&kind=` —— 区间报表。
 *
 * 不带参数时返回的形状**与改造前一致**（`today`/`week`/`all`/`models`/`days`/`okRate`/`tps`），
 * 右栏那个累计看板与既有像素剧本读的都是那几个键；新键（`range`/`summary`/`byDay`/
 * `byExpert`/`byModel`/`experts`）是整页 Token 统计用的。
 */
internal fun WebServer.usageReport(ex: HttpExchange) {
    send(ex, 200, UsageLedger.report(
        from = ex.num("from"), to = ex.num("to"),
        expert = ex.text("expert"), kind = ex.text("kind")
    ), "application/json; charset=utf-8")
}

/**
 * `GET /api/usage/export?from=&to=&expert=&kind=` —— 直接下一份 .xlsx。
 *
 * 三件事必须做对，否则"导出"是个假功能：
 * 1. **Content-Disposition 带文件名**，浏览器才会存成文件而不是开一个空白页；
 * 2. 文件名里不能出现 HTTP 头不许的字符（专家名是中文，直接拼进头里就是 500），
 *    所以按 RFC 5987 再给一份 `filename*=UTF-8''…`；
 * 3. 字节流走 `sendResponseHeaders(len)` + 原样写，**不能过 `send(String)`** ——
 *    那份 helper 会按 UTF-8 编码字符串，zip 里的 0x00 与高位字节会被改坏。
 */
internal fun WebServer.usageExportXlsx(ex: HttpExchange) {
    val from = ex.num("from")
    val to = ex.num("to")
    val expert = ex.text("expert")
    val kind = ex.text("kind")
    val name = UsageExport.filename(from, to, expert)
    val bytes = runCatching { Xlsx.write(UsageExport.sheets(from, to, expert, kind)) }.getOrElse {
        send(ex, 500, "导不出来：${it.message ?: it.javaClass.simpleName}",
            "text/plain; charset=utf-8"); return
    }
    ex.responseHeaders.add("Content-Type",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    ex.responseHeaders.add("Content-Disposition",
        """attachment; filename="$name"; filename*=UTF-8''${uriName(name)}""")
    ex.responseHeaders.add("Cache-Control", "no-store")
    ex.sendResponseHeaders(200, bytes.size.toLong())
    ex.responseBody.use { it.write(bytes) }
    Env.log("usage", "导出 ${name}：${bytes.size} 字节")
}

/** 中文文件名的百分号编码（`filename*` 那份）：`URLEncoder` 把空格编成 `+`，这里要的是 `%20`。 */
private fun uriName(s: String): String =
    java.net.URLEncoder.encode(s, StandardCharsets.UTF_8.name()).replace("+", "%20")
