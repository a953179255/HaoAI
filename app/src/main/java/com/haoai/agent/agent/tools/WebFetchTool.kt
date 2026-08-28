package com.haoai.agent.agent.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class WebFetchTool : Tool {

    override val name = "web_fetch"
    override val description =
        "抓取网页并转为可读文本（自动去除 HTML 标签）。仅支持 http(s) 链接。"
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("url") { put("type", "string") }
            putJsonObject("max_chars") { put("type", "integer") }
        }
        putJsonArray("required") { add(kotlinx.serialization.json.JsonPrimitive("url")) }
    }

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .addInterceptor(com.haoai.agent.platform.NetGuard.interceptor())
            .build()
    }

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            val url = args.reqString("url")
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                return@withContext ToolResult("仅支持 http(s) 链接", true)
            }
            val maxChars = (args.optInt("max_chars") ?: 8000).coerceIn(200, 30_000)
            runCatching {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) HaoAI/0.1")
                    .build()
                http.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext ToolResult("HTTP ${resp.code}", true)
                    val contentType = resp.header("Content-Type") ?: ""
                    // 上限 2MB：读全量进内存会 OOM
                    val bytes = readCapped(resp, MAX_DOWNLOAD_BYTES)
                    // 按 HTTP 头 / HTML meta 声明的字符集解码：国内大量站点仍是 GBK，硬解 UTF-8 全是乱码
                    val body = String(bytes, charsetOf(contentType, bytes))
                    if (contentType.contains("html", ignoreCase = true) || body.trimStart().startsWith("<")) {
                        val text = HtmlText.convert(body)
                        ToolResult("$url（HTTP ${resp.code}）\n\n${TextCap.middle(text, maxChars)}")
                    } else {
                        ToolResult("$url（HTTP ${resp.code}）\n\n${TextCap.middle(body, maxChars)}")
                    }
                }
            }.getOrElse { ToolResult("抓取失败：${it.message}", true) }
        }

    /** 最多读 limit 字节即停止，防止超大响应撑爆内存。 */
    private fun readCapped(resp: okhttp3.Response, limit: Int): ByteArray {
        val src = resp.body?.source() ?: return ByteArray(0)
        val out = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(16 * 1024)
        var total = 0
        src.inputStream().use { ins ->
            while (total < limit) {
                val n = ins.read(chunk, 0, minOf(chunk.size, limit - total))
                if (n < 0) break
                out.write(chunk, 0, n)
                total += n
            }
        }
        return out.toByteArray()
    }

    /** 字符集解析：HTTP 头优先，其次 HTML 头部 meta 声明，默认 UTF-8。 */
    private fun charsetOf(contentType: String, bytes: ByteArray): java.nio.charset.Charset {
        Regex("(?i)charset=\\s*[\"']?([\\w-]+)").find(contentType)?.groupValues?.get(1)?.let { name ->
            runCatching { return java.nio.charset.Charset.forName(name) }
        }
        if (bytes.isNotEmpty()) {
            // meta 声明在文档前部；用 ISO-8859-1 无损读字节再找 charset 声明
            val head = String(bytes, 0, minOf(bytes.size, 2048), Charsets.ISO_8859_1)
            Regex("(?i)charset\\s*=\\s*[\"']?([\\w-]+)").find(head)?.groupValues?.get(1)?.let { name ->
                runCatching { return java.nio.charset.Charset.forName(name) }
            }
        }
        return Charsets.UTF_8
    }

    private companion object {
        const val MAX_DOWNLOAD_BYTES = 2 * 1024 * 1024
    }
}

object HtmlText {

    private val scriptBlock = Regex("(?is)<(script|style|head|noscript|svg)[^>]*>.*?</\\1>")
    private val comments = Regex("(?s)<!--.*?-->")
    private val blockTags = Regex("(?i)</?(p|div|br|h[1-6]|li|tr|td|th|table|ul|ol|blockquote|section|article|header|footer|nav|pre)(\\s[^>]*)?/?>")
    private val anyTag = Regex("<[^>]+>")
    private val entities = mapOf(
        "&amp;" to "&", "&lt;" to "<", "&gt;" to ">", "&quot;" to "\"",
        "&#39;" to "'", "&apos;" to "'", "&nbsp;" to " ", "&copy;" to "©",
        "&mdash;" to "—", "&hellip;" to "…", "&ndash;" to "–",
        "&lsquo;" to "‘", "&rsquo;" to "’", "&ldquo;" to "“", "&rdquo;" to "”",
        "&middot;" to "·", "&times;" to "×", "&divide;" to "÷", "&reg;" to "®"
    )
    private val namedEntity = Regex("&([a-zA-Z][a-zA-Z0-9]{1,10});")
    private val numericEntity = Regex("&#(\\d+);")
    private val hexEntity = Regex("&#x([0-9a-fA-F]+);")
    private val blankLines = Regex("\n{3,}")

    fun convert(html: String): String {
        var t = html
        t = scriptBlock.replace(t, " ")
        t = comments.replace(t, " ")
        t = blockTags.replace(t, "\n")
        t = anyTag.replace(t, "")
        for ((k, v) in entities) t = t.replace(k, v)
        // 十六进制与十进制数字实体：toChar() 只取低 16 位，增补平面字符必须走 toChars
        t = hexEntity.replace(t) { m ->
            decodeCodePoint(m.groupValues[1], hex = true)
        }
        t = numericEntity.replace(t) { m ->
            decodeCodePoint(m.groupValues[1], hex = false)
        }
        // 未收录的命名实体原样删掉（&unknownx; 之类），避免混进正文
        t = namedEntity.replace(t, "")
        t = t.replace("\r", "").replace("\t", " ")
        t = blankLines.replace(t, "\n\n")
        return t.split('\n').joinToString("\n") { it.trim().ifEmpty { "" } }
            .replace(blankLines, "\n\n")
            .trim()
    }

    private fun decodeCodePoint(spec: String, hex: Boolean): String = runCatching {
        val cp = if (hex) spec.toInt(16) else spec.toInt()
        if (cp <= 0 || cp > Character.MAX_CODE_POINT || (cp in 0xD800..0xDFFF)) return ""
        String(Character.toChars(cp))
    }.getOrDefault("")
}
