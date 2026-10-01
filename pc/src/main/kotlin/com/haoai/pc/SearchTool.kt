package com.haoai.pc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * `web_search` —— PC 端以前没有这把工具。
 *
 * 只有 `web_fetch` 等于"知道网址才能读"。而 agent 干活时最常见的一步是
 * "这个库现在的 API 到底叫什么"、"这个错误码是谁定义的" —— 没有搜索，
 * 模型就只能凭训练时的记忆答，答错了界面上一个字都看不出来。
 * 手机端为这件事做了 12 家搜索服务，PC 端一条都没有，这是两端之间最大的一块功能差。
 *
 * 两条提供方，按"装完就能用"优先：
 * - **duckduckgo**（默认，不要 key）：它的 `html` 端点是纯服务端渲染的，
 *   不需要 JS，也不需要浏览器。缺点是对方改版就会解析不出东西 ——
 *   所以解析单独抽成 [parseDuckDuckGo]，用离线 fixture 测，而不是"跑一次看看"。
 * - **bocha**（要 key）：与手机端同源的一家，JSON 接口，改版风险低。
 *   key 从 `HAOAI_SEARCH_KEY` 或 `HAOAI_HOME/searchkey` 读，**不进设置对象**
 *   （那份会被 `GET /api/settings` 整体发回前端）。
 *
 * 选哪条由 `settings.searchProvider` 决定：`auto` = 有 key 走 bocha，没 key 走 duckduckgo。
 */
class WebSearchTool : Tool(
    "web_search",
    "搜网页。query 给关键词（可以带 site:、年份这类限定），返回若干条标题 / 链接 / 摘要。" +
        "要读某条全文，接着用 web_fetch 那条 url。",
    schema("query" to "string", "limit" to "integer", required = arrayOf("query")),
    kind = "net"
) {
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NORMAL).build()

    override suspend fun run(args: JsonObject, ctx: ToolCtx): ToolResult {
        val q = req(args, "query")?.trim() ?: return fail("web_search 缺少 query")
        if (q.isEmpty()) return fail("query 是空的：要搜什么？")
        val limit = int(args, "limit", 6).coerceIn(1, 15)
        val key = Search.key()
        val provider = Search.provider(ctx.settings.searchProvider, key)
        // 显式选了要 key 的一家却没填 key：直接说清去哪儿填，别让它去发一个注定空手的请求
        if (provider == "bocha" && key.isBlank()) {
            return fail("选了博查但没有搜索 key：写进 " + Env.searchKeyFile.absolutePath +
                "（或环境变量 HAOAI_SEARCH_KEY），或者把提供方改回 auto / duckduckgo。")
        }
        return try {
            val hits = when (provider) {
                "bocha" -> Search.bocha(client, key, q, limit)
                else -> Search.duckduckgo(client, q, limit)
            }
            if (hits.isEmpty()) {
                // 空手而归要说清是"没结果"还是"对方结构变了"，否则模型会以为搜不到就开始编
                return fail(
                    "一家结果都没有（provider=$provider）。可能是关键词太偏，" +
                        "也可能是搜索页改版解析不出来 —— 换个说法再试，或者直接用 web_fetch 打你已知的地址。"
                )
            }
            ToolResult(Search.render(q, provider, hits))
        } catch (e: Exception) {
            fail("搜索失败（provider=$provider）：${e.message ?: e.javaClass.simpleName}")
        }
    }
}

internal data class Hit(val title: String, val url: String, val snippet: String)

internal object Search {
    /** 测试要能打本地假搜索服务，所以基址可覆盖（与 Env.home 同一套做法）。 */
    var ddgBase: String =
        System.getProperty("haoai.ddg.base") ?: "https://html.duckduckgo.com/html"
    var bochaBase: String =
        System.getProperty("haoai.bocha.base") ?: "https://api.bochaai.com/v1/web-search"

    fun key(): String = System.getenv("HAOAI_SEARCH_KEY")?.takeIf { it.isNotBlank() }
        ?: runCatching { Env.searchKeyFile.takeIf { f -> f.isFile }?.readText()?.trim() }.getOrNull().orEmpty()

    /** `auto` 的语义：有 key 就用要 key 的那家（结果更稳），没有就用免 key 的。 */
    fun provider(setting: String, key: String): String = when (setting.trim().lowercase()) {
        "duckduckgo", "ddg" -> "duckduckgo"
        "bocha" -> "bocha"
        else -> if (key.isNotBlank()) "bocha" else "duckduckgo"
    }

    fun render(q: String, provider: String, hits: List<Hit>): String = buildString {
        append("web_search（").append(provider).append("）「").append(q).append("」共 ")
        append(hits.size).append(" 条：\n\n")
        hits.forEachIndexed { i, h ->
            append(i + 1).append(". ").append(h.title).append('\n')
            append("   ").append(h.url).append('\n')
            if (h.snippet.isNotBlank()) append("   ").append(h.snippet.replace("\n", " ")).append('\n')
            append('\n')
        }
        append("要哪一条的正文，用 web_fetch 打它的 url；别只凭摘要下结论。")
    }

    fun duckduckgo(client: HttpClient, q: String, limit: Int): List<Hit> {
        val body = "q=" + URLEncoder.encode(q, "UTF-8") + "&kl=cn-zh"
        val req = HttpRequest.newBuilder(URI(ddgBase))
            .timeout(Duration.ofSeconds(25))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) HaoAI/0.1")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build()
        val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
        return parseDuckDuckGo(resp.body(), limit)
    }

    /**
     * 从 DDG 的 html 结果页里挑出条目。
     *
     * 只认 `result__a` / `result__snippet` 这两个类名 —— 它们比 DOM 层级稳定。
     * 链接是 `//duckduckgo.com/l/?uddg=<编码后的真地址>` 这种跳转壳，必须解出 uddg，
     * 否则模型拿到的是一条打不开的跳转地址。
     */
    fun parseDuckDuckGo(html: String, limit: Int = 8): List<Hit> {
        val out = mutableListOf<Hit>()
        if (limit <= 0) return out   // 要 0 条就是 0 条，不是"先给一条"
        val link = Regex("(?is)<a[^>]*class=\"[^\"]*result__a[^\"]*\"[^>]*href=\"([^\"]*)\"[^>]*>(.*?)</a>")
        val snip = Regex("(?is)<a[^>]*class=\"[^\"]*result__snippet[^\"]*\"[^>]*>(.*?)</a>")
        val blocks = html.split(Regex("(?is)<div[^>]+class=\"[^\"]*result results_links"))
        for (b in blocks.drop(1)) {
            val a = link.find(b) ?: continue
            val url = realUrl(a.groupValues[1]) ?: continue
            val title = text(a.groupValues[2])
            if (title.isBlank() || url.isBlank()) continue
            val s = snip.find(b)?.groupValues?.get(1)?.let { text(it) }.orEmpty()
            out += Hit(title, url, s)
            if (out.size >= limit) break
        }
        return out
    }

    private fun realUrl(href: String): String? {
        val h = href.trim()
        if (h.isEmpty()) return null
        val uddg = Regex("[?&]uddg=([^&]+)").find(h)?.groupValues?.get(1)
        val dec = if (uddg != null) java.net.URLDecoder.decode(uddg, "UTF-8") else h
        return when {
            dec.startsWith("http") -> dec
            dec.startsWith("//") -> "https:" + dec
            else -> null
        }
    }

    private fun text(fragment: String): String =
        htmlToText(fragment).replace('\n', ' ').trim()

    fun bocha(client: HttpClient, key: String, q: String, limit: Int): List<Hit> {
        if (key.isBlank()) return emptyList()
        val body = """{"query":${js(q)},"count":${limit.coerceIn(1, 50)},"summary":true}"""
        val req = HttpRequest.newBuilder(URI(bochaBase))
            .timeout(Duration.ofSeconds(25))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $key")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build()
        val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
        return parseBocha(resp.body())
    }

    /** 博查的返回是 `{data:{webPages:{value:[{name,url,snippet,summary}]}}}`。 */
    fun parseBocha(json: String): List<Hit> = runCatching {
        val root = Json.parseToJsonElement(json).jsonObject
        val pages = root["data"]?.jsonObject?.get("webPages")?.jsonObject?.get("value")?.jsonArray
            ?: root["webPages"]?.jsonObject?.get("value")?.jsonArray ?: return emptyList()
        pages.mapNotNull { el ->
            val o = el.jsonObject
            val url = o["url"]?.jsonPrimitive?.content ?: return@mapNotNull null
            Hit(
                title = o["name"]?.jsonPrimitive?.content.orEmpty(),
                url = url,
                snippet = (o["summary"]?.jsonPrimitive?.content ?: o["snippet"]?.jsonPrimitive?.content).orEmpty()
            )
        }
    }.getOrDefault(emptyList())

    private fun js(s: String): String = "\"" +
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
}
