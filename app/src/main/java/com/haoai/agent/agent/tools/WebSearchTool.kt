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
            "凡产出物会含具体事实、数据、时效性说法或具名对象（文章/报告/文案/评测/对比/推荐/答疑），先搜索再动笔，不要凭模型记忆直接成文；" +
            "需要完整正文时对结果里的 url 调用 web_fetch。"
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
        // 结果条数：默认 5、上限 8。原为默认 6/上限 10 —— 实测一次调研 20 次检索 × 10 条
        // ≈ 5.6 万字符塞进上下文，是 prompt token 膨胀的主要来源，收紧一档。
        val count = (args.optInt("count") ?: 5).coerceIn(1, 8)
        // D 检索卫生：同一任务内同关键词命中 TTL 缓存直接返回（防重复搜索烧轮次）
        val cacheKey = "search:${query.lowercase()}"
        ctx.webCacheGet(cacheKey)?.let { return ToolResult("$it\n\n（缓存）") }

        // 引擎链：前一个**请求失败或结果低质**才退到下一个。
        // 只判失败不够——Bing 对爬虫会正常返回 200 和满屏结果块，内容却是日历/导航/词条类
        // 聚合页（2026-09 实测：同一批 query 拿到"2026年大事要事一览表""AI工具集导航大全"
        // "year（英文单词）_百度百科"），模型拿着它继续写，用户看到的就是"搜了 18 次没料"。
        val errors = mutableListOf<String>()
        val candidates = ArrayList<Pair<String, List<Hit>>>()
        for ((engine, fetch) in ENGINE_CHAIN) {
            runCatching { fetch(client, query) }.onSuccess { hits ->
                if (hits.isEmpty()) errors.add("$engine: 0 结果")
                else {
                    candidates += engine to hits
                    if (qualityIssue(hits, query) == null) break   // 够好就不再试后面的引擎
                }
            }.onFailure { errors.add("$engine: ${it.message ?: it.javaClass.simpleName}") }
        }
        // 没有一家通过质量门时，取"低质条数最少"的那份，并如实告诉模型它不合格
        val pick = candidates.firstOrNull { qualityIssue(it.second, query) == null }
            ?: candidates.minByOrNull { junkCount(it.second) }
            ?: return ToolResult("搜索失败（${errors.joinToString("; ")}）", true)
        val (engine, results) = pick
        val hits = results
        return when {
            hits.isEmpty() -> ToolResult("没有搜到相关结果")
            else -> {
                val picked = hits.take(count)
                // 换词但拿到的还是同一批链接：直说，别让模型继续空转。
                // 实测一次调研 18 个不同 query 只出 5 种结果，多出来的 13 次每次都要把整段
                // 上下文重付一遍（≈19k tokens），是成本见顶的主因。
                val sig = picked.resultSignature()
                ctx.searchSigs.putIfAbsent(sig, ctx.searchSigs.size + 1)?.let { earlier ->
                    return ToolResult(
                        "这批链接与前面第 $earlier 次搜索完全相同——换关键词没有带来新页面，别再换词重试。\n" +
                            "接下来只有三条路：对上面已有的链接调 web_fetch 取正文；" +
                            "换一个明显不同的检索角度（具体机构名/报告名/产品名 + 时间限定）；" +
                            "或者在回答里直接说明这个信息拿不到。"
                    )
                }
                val out = picked
                    .mapIndexed { i, h ->
                        "${i + 1}. ${h.title}\n${h.url}\n${h.snippet.take(220)}"
                    }
                    .joinToString("\n\n")
                // 标来源引擎：出问题时能一眼看出是哪家引擎给的东西，模型也会据此降低对结果的信任
                val body = "$out\n\n（来源：$engine）" +
                    (qualityIssue(hits, query)?.let {
                        "\n⚠ 这批结果相关性低（$it），两家引擎都试过了。" +
                            "别据此硬写：换一个具体得多的角度（机构名/报告名/产品名 + 年份）再试一次，" +
                            "或者在回答里说明这个信息拿不到。"
                    }.orEmpty())
                ctx.webCachePut(cacheKey, body)
                ToolResult(body)
            }
        }
    }

    /**
     * 引擎尝试顺序。Bing 放前面是**国内直连可达性**的保守选择（DuckDuckGo 在部分网络下不通），
     * 差别由上面的质量门兜住：Bing 给的东西不合格就继续往下试，不会停在坏结果上。
     */
    private val ENGINE_CHAIN: List<Pair<String, suspend (okhttp3.OkHttpClient, String) -> List<Hit>>> by lazy {
        listOf("bing" to ::tryBing, "duckduckgo" to ::tryDdg)
    }

    /** 明显不是调研目标的聚合页特征。判据刻意保守——只在**过半**结果命中时才判低质。 */
    private val JUNK_URL = listOf(
        "calendar", "timeanddate", "wannianrili", "jie假", "hao123", "2345.com",
        "ai-bot.cn", "baike.", "/item/", "dict.", "douyin", "short-video"
    )
    private val JUNK_TITLE = listOf(
        "日历", "放假安排", "一览表", "节日", "导航", "百科", "词条", "面试", "官网", "下载", "在线观看"
    )

    internal fun junkCount(hits: List<Hit>): Int = hits.count { h ->
        val u = h.url.lowercase()
        JUNK_URL.any { u.contains(it) } || JUNK_TITLE.any { t -> h.title.contains(t) }
    }

    /** 返回 null = 结果可用；非 null 是一句能直接给模型看的原因。 */
    internal fun qualityIssue(hits: List<Hit>, query: String): String? {
        if (hits.isEmpty()) return "没有结果"
        val junk = junkCount(hits)
        if (junk * 2 >= hits.size) return "${hits.size} 条里有 $junk 条是日历/导航/词条/面试类聚合页"
        val keys = query.lowercase().split(Regex("[\\s,，、/]+")).filter { it.length >= 2 }.take(5)
        if (keys.size >= 2) {
            val touched = hits.count { h ->
                val s = (h.title + " " + h.snippet).lowercase()
                keys.any { k -> s.contains(k) }
            }
            if (touched == 0) return "没有一条结果包含查询里的词"
        }
        return null
    }

    /**
     * 结果集签名：只取链接的「host+path」并丢掉 utm/追踪参数与大小写差异。
     * 为什么不用整段文本：同一批结果在不同 query 下摘要措辞会略变，签名会假性不同。
     */
    internal fun List<Hit>.resultSignature(): String =
        map { it.url.normalizeForSig() }.sorted().joinToString(";")

    internal fun String.normalizeForSig(): String = lowercase()
        .removePrefix("https://").removePrefix("http://").removePrefix("www.")
        .substringBefore('#').substringBefore('?').trimEnd('/')

    /**
     * 还原 Bing 的跳转链 `.../ck/a?...&u=a1<base64url>` → 真实地址。
     *
     * 不还原的代价是实打实的：交给模型的是 `bing.com/ck/a` 地址，模型拿去 web_fetch
     * 就是 403/404（2026-09-20 设备会话里那几次"抓取失败"有一部分是这个），
     * 而且结果签名也会因为跳转参数每次都变而**认不出重复结果集**。
     * 解码失败一律原样返回——宁可给个能点的跳转链，也不静默丢结果。
     */
    internal fun unwrapBingLink(href: String): String {
        if (!href.contains("/ck/a")) return href
        val raw = href.replace("&amp;", "&")
        val u = Regex("[?&]u=([^&]+)").find(raw)?.groupValues?.get(1) ?: return href
        val decoded = runCatching { java.net.URLDecoder.decode(u, "UTF-8") }.getOrDefault(u)
        val body = if (decoded.startsWith("a1")) decoded.substring(2) else decoded
        if (body.isEmpty()) return href
        return runCatching {
            val padded = body.padEnd(body.length + (4 - body.length % 4) % 4, '=')
            String(java.util.Base64.getUrlDecoder().decode(padded), Charsets.UTF_8)
        }.getOrDefault(href)
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
                val url = unwrapBingLink(link.groupValues[1].ifEmpty { link.groupValues[3] })
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
