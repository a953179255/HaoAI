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
    override val desc =
        "抓取网页并转为可读文本（自动去除 HTML 标签）。仅支持 http(s) 链接。"
    override val params = buildJsonObject {
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
            // D 检索卫生：同一任务内同 URL 命中 TTL 缓存直接返回（防模型重复抓同一页烧轮次）
            val cacheKey = "fetch:${url.trimEnd('/')}"
            ctx.webCacheGet(cacheKey)?.let { return@withContext ToolResult("$url（缓存）\n\n$it") }
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
                    var text = if (contentType.contains("html", ignoreCase = true) || body.trimStart().startsWith("<")) {
                        HtmlText.convert(body)
                    } else body
                    // 兜底：本地挑容器挑不出正文时试一次免 key 远端 reader。
                    // 判据有两条——正文过短；或**开头与本轮已抓过的另一页完全一样**
                    // （2026-09 实测：5 个不同 IBM 页面返回文本开头都是同一段站点导航，
                    //  说明挑到的还是模板，不是文章）。远端失败一律静默退回本地结果。
                    val head = text.filterNot { it == '\n' }.take(160)
                    val repeatedTemplate = head.isNotBlank() &&
                        ctx.fetchHeads.putIfAbsent(head, url) != null
                    if ((text.length < MIN_USEFUL_TEXT || repeatedTemplate) &&
                        !ctx.deadEngines.containsKey(READER_ENGINE)
                    ) {
                        val via = readerText(url, ctx)
                        if (via != null && via.length > text.length * 1.2) {
                            text = via.trim() + "\n\n（正文由远端 reader 提取；本地解析" +
                                (if (repeatedTemplate) "拿到的与已抓过的页面模板重复" else "没定位到正文") + "）"
                        }
                    }
                    val out = TextCap.middle(text, maxChars)
                    ctx.webCachePut(cacheKey, out)
                    ToolResult("$url（HTTP ${resp.code}）\n\n$out")
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

    /**
     * 免 key 的远端正文 reader（r.jina.ai/<url>，返回 markdown 纯文本）。
     *
     * 只在本地挑容器失败时才走，且**任何失败都静默**并把 reader 本轮拉黑：
     * 2026-09-21 国内直连实测 r.jina.ai 是"挂 24 秒超时"而不是快速失败，
     * 共享客户端的 readTimeout 又是 120s——不记黑名单的话，每次正文短的抓取都要白等二十多秒。
     */
    private suspend fun readerText(url: String, ctx: ToolContext): String? = withContext(Dispatchers.IO) {
        runCatching {
            val fast = http.newBuilder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(8, TimeUnit.SECONDS)
                .build()
            val req = Request.Builder()
                .url("https://r.jina.ai/$url")
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) HaoAI/0.1")
                .header("Accept", "text/plain")
                .build()
            fast.newCall(req).execute().use { r ->
                if (!r.isSuccessful) error("HTTP ${r.code}")
                r.body?.string()?.take(200_000)
            }
        }.onFailure { ctx.deadEngines[READER_ENGINE] = it.message ?: it.javaClass.simpleName }
            .getOrNull()
    }

    private companion object {
        const val MAX_DOWNLOAD_BYTES = 2 * 1024 * 1024

        /** 低于这个长度基本可以断定没挑到正文（导航 + 版权尾巴通常也就几百字）。 */
        const val MIN_USEFUL_TEXT = 600

        /** 失败即本轮拉黑的兜底引擎名（与 WebSearchTool 共用同一张失败表）。 */
        const val READER_ENGINE = "reader"
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

    /** 站点外壳：导航、侧栏、页眉页脚、表单。整页兜底时先剥掉它们。 */
    private val chromeBlocks = Regex("(?is)<(nav|aside|form|header|footer)[^>]*>.*?</\\1>")
    private val articleBlock = Regex("(?is)<article[^>]*>.*?</article>")
    private val mainBlock = Regex("(?is)<main[^>]*>.*?</main>")

    /** 容器 HTML 少于这么多字符就当它是页面上的小卡片（评论框、推荐卡），不认作正文。 */
    private const val MIN_MAIN_HTML = 1200

    /**
     * 正文优先：<article>（多个取最长的）→ <main> → 整页去外壳。
     *
     * 为什么要挑容器：原先整页一起转文本，`TextCap.middle` 取到的开头永远是站点导航。
     * 实测一次调研抓了 5 个不同的 IBM 页面，返回文本全部以
     * "Cost of a Data Breach Report 2026" 那段导航开头——模型看不到正文，只能再抓一个 URL，
     * 于是"抓了很多页但什么也没拿到"，每轮还要重付整段上下文。
     */
    internal fun pickBody(html: String): String {
        articleBlock.findAll(html).maxByOrNull { it.value.length }?.value?.let {
            if (it.length >= MIN_MAIN_HTML) return chromeBlocks.replace(it, " ")
        }
        mainBlock.find(html)?.value?.let {
            if (it.length >= MIN_MAIN_HTML) return chromeBlocks.replace(it, " ")
        }
        return chromeBlocks.replace(html, " ")
    }

    fun convert(html: String): String {
        var t = pickBody(html)
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
