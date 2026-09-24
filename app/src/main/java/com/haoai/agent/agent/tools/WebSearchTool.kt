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
        // 结果条数：模型不指定时用用户在「设置 → 搜索服务」里配的条数（内置链也读得到），
        // 上限 8 —— 原为默认 6/上限 10，实测一次调研 20 次检索 × 10 条 ≈ 5.6 万字符塞进上下文，
        // 是 prompt token 膨胀的主要来源，收紧一档（设置页滑杆区间与此对齐）。
        val count = (args.optInt("count")
            ?: ctx.searchProvider?.count?.takeIf { it > 0 }
            ?: 5).coerceIn(1, 8)
        // D 检索卫生：同一任务内同关键词命中 TTL 缓存直接返回（防重复搜索烧轮次）
        val cacheKey = "search:${query.lowercase()}"
        ctx.webCacheGet(cacheKey)?.let { return ToolResult("$it\n\n（缓存）") }

        // 引擎链：前一个**请求失败或结果低质**才退到下一个。
        // 只判失败不够——Bing 对爬虫会正常返回 200 和满屏结果块，内容却是日历/导航/词条类
        // 聚合页（2026-09-21 国内直连实测：`2026年 AI Agent 新变化` 前 4 条是
        // "2026年日历全年完整图""国务院2026年节假日安排通知"），模型拿着它继续写，
        // 用户看到的就是"搜了 18 次没料"。
        //
        // 两条国内路径上必须有的保护（都是实测出来的）：
        // - **短超时**：DuckDuckGo 在国内不是"连不上立刻报错"，而是**挂 16 秒**才超时。
        //   共享的 OkHttp readTimeout 是 120s，一次坏检索能把一个回合拖死。
        // - **本轮失败记忆**：一个引擎这轮已经失败（超时/反爬页），后面就不再去试它。
        //   否则每次搜索都要为同一家不可达的引擎再等一遍。
        val errors = mutableListOf<String>()
        val candidates = ArrayList<Pair<String, List<Hit>>>()
        val fast = shortLived(client)

        // 配了主后端就先问它。它成功且结果合格就不再往下消耗免 key 链的请求。
        val sp = ctx.searchProvider?.takeIf { it.external }?.copy(count = count)
        val providerUsable = sp != null && !(SearchProviders.needsKey(sp.backend) && sp.apiKey.isBlank())
        if (sp != null && !providerUsable) {
            errors.add("${SearchProviders.label(sp.backend)}: 未配置 API Key")
        }
        if (sp != null && providerUsable) {
            runCatching { SearchProviders.search(sp, fast, query) }.onSuccess { hits ->
                if (hits.isEmpty()) errors.add("${SearchProviders.label(sp.backend)}: 0 结果")
                else candidates += SearchProviders.label(sp.backend) to hits
            }.onFailure { errors.add("${SearchProviders.label(sp.backend)}: ${it.message ?: "失败"}") }
        }

        // 内置免 key 引擎链什么时候走：没配主后端、配了但没填 key（等于"留空继续用免 key"）、
        // 或主后端结果不合格且用户允许降级。主后端结果已经够好就不再重复请求。
        val primaryGood = candidates.any { qualityIssue(it.second, query) == null }
        val runChain = sp == null || !providerUsable || (!primaryGood && sp.fallback)
        if (runChain) {
            for ((engine, fetch) in ENGINE_CHAIN) {
                ctx.deadEngines[engine]?.let {
                    errors.add("$engine: 本轮已判不可用（$it）")
                    continue
                }
                runCatching { fetch(fast, query) }.onSuccess { hits ->
                    if (hits.isEmpty()) errors.add("$engine: 0 结果")
                    else {
                        candidates += engine to hits
                        if (qualityIssue(hits, query) == null) break   // 够好就不再试后面的引擎
                    }
                }.onFailure {
                    val why = it.message ?: it.javaClass.simpleName
                    ctx.deadEngines[engine] = why
                    errors.add("$engine: $why")
                }
            }
        }
        // 没有一家通过质量门时，取"低质条数最少"的那份，并如实告诉模型它不合格
        val pick = candidates.firstOrNull { qualityIssue(it.second, query) == null }
            ?: candidates.minByOrNull { junkCount(it.second) }
            ?: return ToolResult(
                if (candidates.isEmpty()) allDeadMessage(ctx.deadEngines, errors)
                else "搜索失败（${errors.joinToString("; ")}）", true
            )
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
     * 引擎尝试顺序。Bing 放前面是**国内直连可达性**的保守选择：2026-09-21 广州移动网络实测
     * www.bing.com 0.6s 出结果，而 html.duckduckgo.com 挂 16s 超时、brave/mojeek 同样连不上、
     * 百度直接弹安全验证页。顺序错了不会变好，只会让每次搜索多等十几秒。
     * 差别由质量门兜住：Bing 给的东西不合格就继续往下试。
     */
    internal val ENGINE_CHAIN: List<Pair<String, suspend (okhttp3.OkHttpClient, String) -> List<Hit>>> by lazy {
        listOf("bing" to ::tryBing, "duckduckgo" to ::tryDdg, "sogou" to ::trySogou)
    }

    /**
     * 设置页「测试搜索」跑内置链用：按线上同序、同短超时、同质量门走一遍，
     * 返回（实际采用的引擎, 结果, 各家失败原因）。
     *
     * 与 [run] 的区别只有两点：不带本轮的 deadEngines 拉黑与签名去重（那两套状态属于任务内
     * 检索，一次诊断不该写进去），以及不过模型。这样"测试通过"才真的等于"Agent 能用"。
     * [count] 与工具侧同源：测试卡上显示的条数必须等于 Agent 实际会拿到的条数，
     * 否则设置里写 5 条、测试却报 10 条，用户读到的是两套口径。
     */
    internal suspend fun probeChain(
        client: okhttp3.OkHttpClient,
        query: String,
        count: Int = 5
    ): Triple<String?, List<Hit>, List<String>> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val fast = shortLived(client)
            val errors = mutableListOf<String>()
            val candidates = ArrayList<Pair<String, List<Hit>>>()
            for ((engine, fetch) in ENGINE_CHAIN) {
                runCatching { fetch(fast, query) }.onSuccess { hits ->
                    if (hits.isEmpty()) errors.add("$engine: 0 结果")
                    else {
                        candidates += engine to hits
                        if (qualityIssue(hits, query) == null) break
                    }
                }.onFailure { errors.add("$engine: ${it.message ?: it.javaClass.simpleName}") }
            }
            val pick = candidates.firstOrNull { qualityIssue(it.second, query) == null }
                ?: candidates.minByOrNull { junkCount(it.second) }
            Triple(pick?.first, pick?.second?.take(count.coerceIn(1, 8)).orEmpty(), errors)
        }

    /**
     * 搜索专用的短超时客户端。共享的 OkHttp 客户端 readTimeout 是 120s（为模型流式长响应设的），
     * 用在搜索上一次坏引擎能把一个回合拖死；newBuilder 复用连接池与线程池，只换超时。
     */
    private fun shortLived(client: okhttp3.OkHttpClient): okhttp3.OkHttpClient =
        client.newBuilder()
            .connectTimeout(6, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(9, java.util.concurrent.TimeUnit.SECONDS)
            .build()

    /** 全部引擎本轮都不可用时给模型的说明：让它别再搜了，用手上的材料收尾。 */
    internal fun allDeadMessage(dead: Map<String, String>, errors: List<String>): String =
        "联网搜索当前不可用（" +
            (if (dead.isEmpty()) errors.joinToString("; ")
             else dead.entries.joinToString("; ") { "${it.key}: ${it.value}" }) +
            "）。这些引擎本轮不再重试。请二选一：用你已经拿到的材料完成回答并说明信息来源受限；" +
            "或告诉用户搜索暂时不可用、需要他检查网络/代理后重试。不要反复换关键词空转。"

    /** 反爬/验证码页：HTTP 200 但一个结果都没有，必须判失败而不是"没有搜到相关结果"。 */
    internal fun looksLikeAntiBot(html: String): Boolean =
        html.length < 20_000 && listOf("antispider", "验证码", "安全检验", "输入验证码", "异常流量")
            .any { html.contains(it, ignoreCase = true) }

    /** 搜狗：国内可达（0.6–0.8s），但会周期性弹反爬页——所以只排第三，且失败即被本轮拉黑。 */
    private suspend fun trySogou(client: okhttp3.OkHttpClient, query: String): List<Hit> =
        runCatching {
            val html = fetch(
                client, "https://www.sogou.com/web?query=" +
                    java.net.URLEncoder.encode(query, "UTF-8"), null
            )
            if (looksLikeAntiBot(html)) throw IllegalStateException("反爬验证页")
            Regex("<h3[^>]*>\\s*<a[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL)
                .findAll(html).mapNotNull { m ->
                    val title = stripTags(m.groupValues[2])
                    var href = m.groupValues[1].replace("&amp;", "&")
                    // 搜狗也是 /link?url=... 跳转链，解不出就原样（至少同源可点）
                    if (href.startsWith("/link")) href = "https://www.sogou.com" + href
                    if (title.isBlank()) null else Hit(title, href, "")
                }.toList()
        }.getOrElse { e ->
            if (e is kotlinx.coroutines.CancellationException) throw e
            throw IllegalStateException(e.message ?: "sogou error")
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
                // 桌面 UA：搜索引擎给移动 UA 与桌面 UA 的结果块结构不同，2026-09-21 国内直连
                // 实测是按桌面 UA 验证的（www.bing.com 出 10 条 b_algo，移动 UA 的 cn.bing.com
                // 只解析出 5 条），换 UA 会连带改掉解析命中率。
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
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
            val html = fetch(client, "https://www.bing.com/search?q=" +
                java.net.URLEncoder.encode(query, "UTF-8"), null)
            if (looksLikeAntiBot(html)) throw IllegalStateException("反爬验证页")
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
