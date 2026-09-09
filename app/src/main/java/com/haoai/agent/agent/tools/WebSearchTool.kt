package com.haoai.agent.agent.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * 网页搜索（gugu 式调研入口）：DuckDuckGo HTML 端点，免 API key。
 * 返回标题+链接+摘要列表；需要全文时再接 web_fetch。
 */
class WebSearchTool : Tool {

    override val name = "web_search"
    override val description =
        "联网搜索。返回与 query 相关的网页列表（标题/链接/摘要）。" +
            "适合查新闻、资料、文档、实时信息；需要完整正文时对结果里的 url 调用 web_fetch。"
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("query") { put("type", "string") }
            putJsonObject("count") { put("type", "integer") }
        }
        putJsonArrayRequired()
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putJsonArrayRequired() {
        put("required", kotlinx.serialization.json.buildJsonArray {
            add(kotlinx.serialization.json.JsonPrimitive("query"))
        })
    }

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val client = ctx.httpClient
            ?: return ToolResult("搜索功能不可用", true)
        val query = args.optString("query").trim()
        if (query.isEmpty()) return ToolResult("需要 query 参数", true)
        val count = (args.optInt("count") ?: 6).coerceIn(1, 10)
        // D 检索卫生：同一任务内同关键词命中 TTL 缓存直接返回（防重复搜索烧轮次）
        val cacheKey = "search:${query.lowercase()}"
        ctx.webCacheGet(cacheKey)?.let { return ToolResult("$it\n\n（缓存）") }

        // Bing 优先（国内可达），DuckDuckGo 兜底
        val errors = mutableListOf<String>()
        var results: List<Hit>? = null
        runCatching { tryBing(client, query) }
            .onSuccess { results = it }
            .onFailure { errors.add("bing: ${it.message}") }
        if (results == null) {
            runCatching { tryDdg(client, query) }
                .onSuccess { results = it }
                .onFailure { errors.add("ddg: ${it.message}") }
        }
        val hits = results ?: return ToolResult("搜索失败（${errors.joinToString("; ")}）", true)
        return when {
            hits.isEmpty() -> ToolResult("没有搜到相关结果")
            else -> {
                val out = hits.take(count)
                    .mapIndexed { i, h ->
                        "${i + 1}. ${h.title}\n${h.url}\n${h.snippet.take(220)}"
                    }
                    .joinToString("\n\n")
                ctx.webCachePut(cacheKey, out)
                ToolResult(out)
            }
        }
    }

    internal data class Hit(val title: String, val url: String, val snippet: String)

    private suspend fun fetch(client: okhttp3.OkHttpClient, url: String, postBody: String?): String =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val builder = okhttp3.Request.Builder()
                .url(url)
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
                )
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            if (postBody != null) {
                builder.post(
                    okhttp3.FormBody.Builder().add("q", postBody).build()
                )
            }
            client.newCall(builder.build()).execute().use { resp ->
                if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
                resp.body?.string() ?: throw IllegalStateException("空响应")
            }
        }

    /** Bing 国内可达：解析 b_algo 结果块。 */
    private suspend fun tryBing(client: okhttp3.OkHttpClient, query: String): List<Hit> =
        runCatching {
            val html = fetch(client, "https://cn.bing.com/search?q=" +
                java.net.URLEncoder.encode(query, "UTF-8"), null)
            val blockRe = Regex("<li class=\"b_algo\"[^>]*>(.*?)</li>", RegexOption.DOT_MATCHES_ALL)
            // Bing 结构：<a href="..."><h2>标题</h2></a>（a 在外层）；兼容旧结构
            val linkRe = Regex(
                "<a[^>]*href=\"(https?://[^\"]+)\"[^>]*>\\s*<h2[^>]*>(.*?)</h2>|<h2[^>]*><a[^>]*href=\"(https?://[^\"]+)\"[^>]*>(.*?)</a>",
                RegexOption.DOT_MATCHES_ALL
            )
            val snipRe = Regex("<p[^>]*>(.*?)</p>", RegexOption.DOT_MATCHES_ALL)
            blockRe.findAll(html).mapNotNull { m ->
                val block = m.groupValues[1]
                val link = linkRe.find(block) ?: return@mapNotNull null
                val url = (link.groupValues[1].ifEmpty { link.groupValues[3] })
                val title = stripTags(
                    (link.groupValues[2].ifEmpty { link.groupValues[4] })
                )
                val snippet = snipRe.find(block)?.let { stripTags(it.groupValues[1]) }.orEmpty()
                if (title.isBlank() || !url.startsWith("http")) null
                else Hit(title, url, snippet)
            }.toList()
        }.getOrElse { e ->
            if (e is kotlinx.coroutines.CancellationException) throw e
            throw IllegalStateException(e.message ?: "bing error")
        }

    private suspend fun tryDdg(client: okhttp3.OkHttpClient, query: String): List<Hit> =
        runCatching {
            val html = fetch(client, "https://html.duckduckgo.com/html/", query)
            parseResults(html)
        }.getOrElse { e ->
            if (e is kotlinx.coroutines.CancellationException) throw e
            throw IllegalStateException(e.message ?: "ddg error")
        }

    private fun parseResults(html: String): List<Hit> {
        val linkRe = Regex(
            "<a[^>]*class=\"result__a\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>",
            RegexOption.DOT_MATCHES_ALL
        )
        val snippetRe = Regex(
            "<a[^>]*class=\"result__snippet\"[^>]*>(.*?)</a>",
            RegexOption.DOT_MATCHES_ALL
        )
        val titles = linkRe.findAll(html).toList()
        val snippets = snippetRe.findAll(html).map { stripTags(it.groupValues[1]) }.toList()
        return titles.mapIndexedNotNull { i, m ->
            var href = m.groupValues[1]
            // DDG 跳转链：//duckduckgo.com/l/?uddg=<encoded>&...
            if (href.contains("uddg=")) {
                href = runCatching {
                    java.net.URLDecoder.decode(
                        href.substringAfter("uddg=").substringBefore("&"), "UTF-8"
                    )
                }.getOrDefault(href)
            }
            if (!href.startsWith("http")) href = "https:$href"
            val title = stripTags(m.groupValues[2])
            if (title.isBlank() || !href.startsWith("http")) return@mapIndexedNotNull null
            Hit(title.trim(), href, snippets.getOrNull(i)?.trim().orEmpty())
        }
    }

    private fun stripTags(s: String): String =
        s.replace(Regex("<[^>]+>"), "")
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#x27;", "'").replace("&nbsp;", " ")
            .replace(Regex("\\s+"), " ").trim()
}
