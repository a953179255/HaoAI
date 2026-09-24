package com.haoai.agent.agent.tools

import com.haoai.agent.data.HaoJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder

/**
 * 用户在「设置 → 搜索服务」里选定的主后端配置。
 *
 * 为什么由引擎算好后传进来而不是让工具去读 AppContainer：工具层拿不到容器（也不该拿），
 * 而 API Key 必须经过 Keystore 解密这一步——解密留在有 cipher 的地方，工具只接明文。
 */
data class SearchProviderConfig(
    val backend: String,
    /** 已解密的明文 Key；searxng/custom 可以空。 */
    val apiKey: String = "",
    val count: Int = 5,
    /** 主后端失败/结果不合格时是否允许退回内置免 key 链。 */
    val fallback: Boolean = true,
    /** 非密钥项，键名形如 `searxng.url`、`custom.template`。 */
    val options: Map<String, String> = emptyMap()
) {
    fun opt(key: String): String = (options["$backend.$key"] ?: "").trim()
    /** 只有配了主后端且不是 builtin 才需要走外部服务。 */
    val external: Boolean get() = backend.isNotBlank() && backend != SearchProviders.BUILTIN
}

/** 给设置页「测试搜索」用的公开结果类型（WebSearchTool.Hit 是 internal，不外泄）。 */
data class SearchHit(val title: String, val url: String, val snippet: String)

/** 一次测试的结果：实际给出结果的引擎名 + 命中 + 沿途各家的失败原因。 */
data class SearchTestOutcome(
    val engine: String,
    val hits: List<SearchHit>,
    val notes: List<String> = emptyList()
)

/**
 * 可选的搜索后端适配器（智谱 / 博查 / 自建 SearXNG / 自定义 URL 模板）。
 *
 * 请求体与响应字段是照 RikkaHub 的实测实现来的（`refs/rikkahub/search/.../ZhipuSearchService.kt`、
 * `BochaSearchService.kt`、`SearXNGService.kt`），不是照文档猜的——省掉一轮真机试错。
 * 解析函数全部做成纯函数：没有 key 也能用假 JSON 把每条路径测到。
 */
object SearchProviders {

    const val BUILTIN = "builtin"
    val KEYPED = setOf("zhipu", "bocha", "doubao", "metaso")

    /** 目录页分组：开箱可用 / 国内可直连 / 自建与通用。 */
    enum class Group { Free, Cn, Self }

    /**
     * 一家后端的展示元数据。设置页与目录页共用这份表——两处各写一套文案，
     * 迟早会出现"目录页说免费、配置页说按次计费"这种自相矛盾。
     *
     * @param reach 国内直连可达性的**实测状态**。没有 Key 就没法测，不能拿"官方说国内可用"
     *              当"我测过"，所以默认值是「未实测」，界面上照实显示。
     */
    data class Backend(
        val id: String,
        val name: String,
        val short: String,
        val group: Group,
        val need: String,
        val price: String,
        val one: String,
        val hint: String,
        val site: String = "",
        val siteNote: String = "",
        val reach: String = "未实测 · 填好 Key 后用下方「测试」确认"
    )

    /** 已写适配器的后端。加一家 = 这里一行 + `search()` 里一个分支 + 一个纯解析函数。 */
    val CATALOG: List<Backend> = listOf(
        Backend(
            id = BUILTIN, name = "内置免 key 引擎", short = "内置链", group = Group.Free,
            need = "无需配置", price = "免费",
            one = "Bing → DuckDuckGo → 搜狗 依次尝试",
            hint = "依次试 Bing、DuckDuckGo、搜狗：前一家失败或结果不合格才退到下一家；" +
                "单次搜索用 6s/9s 短超时，本轮失败过的引擎不再重试。什么都不用填，" +
                "它同时也是其它家的兜底。",
            reach = "已实测：2026-09-24 模拟器国内直连 4.9s / 5 条 / 来源 duckduckgo"
        ),
        Backend(
            id = "zhipu", name = "智谱 Web Search", short = "智谱", group = Group.Cn,
            need = "API Key", price = "0.01 元/次",
            one = "GLM 官方检索接口",
            hint = "标准档 0.01 元/次、高级档 0.03 元/次。返回已是干净的结果 JSON，" +
                "不必再跟 Bing 的日历页搏斗。",
            site = "https://open.bigmodel.cn/usercenter/apikeys",
            siteNote = "注册后在控制台「API Keys」页新建"
        ),
        Backend(
            id = "bocha", name = "博查 BoCha", short = "博查", group = Group.Cn,
            need = "API Key", price = "按次计费",
            one = "中文网页覆盖较好",
            hint = "国内可直连的第三方检索。价格首页不公示，以控制台显示为准。" +
                "「返回摘要段」打开后每条结果带更长正文，多花 token 但常省掉一次 web_fetch。",
            site = "https://open.bochaai.com/",
            siteNote = "注册后在控制台创建 API Key"
        ),
        Backend(
            id = "doubao", name = "豆包 · 火山搜索", short = "豆包", group = Group.Cn,
            need = "API Key", price = "有免费额度",
            one = "方舟系联网检索 API",
            hint = "走 feedcoop 的检索端点。「网页搜索」返回站内索引结果，" +
                "「全球搜索」覆盖海外引擎但更慢、更贵。两种模式的响应形状不同，代码里分别解析。",
            site = "https://console.volcengine.com/search-infinity/api-key",
            siteNote = "开通「联网内容插件」后创建 API Key"
        ),
        Backend(
            id = "metaso", name = "秘塔 Metaso", short = "秘塔", group = Group.Cn,
            need = "API Key", price = "按次计费",
            one = "中文 AI 检索，返回带摘要",
            hint = "秘塔开放接口，按 q/scope/size 请求，返回 webpages 列表。" +
                "每次消耗额度（响应里的 credits 字段），所以条数别开太大。",
            site = "https://metaso.cn/",
            siteNote = "注册后在开放平台创建 API Key"
        ),
        Backend(
            id = "searxng", name = "自建 SearXNG", short = "SearXNG", group = Group.Self,
            need = "实例 URL", price = "免费 · 需自建",
            one = "元搜索引擎，聚合哪家由实例定",
            hint = "实例必须在 settings.yml 的 search.formats 里加上 json，" +
                "否则接口回的是网页而不是结果——这时测试会显示 0 条而不是报错。",
            site = "https://docs.searxng.org/",
            siteNote = "重点看 settings.yml 的 search.formats",
            reach = "取决于你的实例在不在国内"
        ),
        Backend(
            id = "custom", name = "自定义端点", short = "自定义", group = Group.Self,
            need = "URL 模板", price = "看你接谁",
            one = "任何 GET 返回 JSON 的接口",
            hint = "把 {query} 换成检索词、{count} 换成条数后 GET 出去，再按给定路径从响应里取结果数组。" +
                "不给路径就自动试 results / items / data.webPages.value。" +
                "POST-only 或要 AK/SK 签名的接口接不了，那种得写适配器。",
            reach = "取决于你接的端点"
        ),
    )

    fun backend(id: String): Backend = CATALOG.firstOrNull { it.id == id } ?: CATALOG.first()

    /** 后端是否需要我们这边存 key（决定 UI 上要不要显示输入框）。 */
    fun needsKey(backend: String): Boolean = backend in KEYPED

    /** 一行说明，直接给设置页与结果标注用。 */
    fun label(backend: String): String = backend(backend).name

    internal suspend fun search(
        cfg: SearchProviderConfig,
        client: OkHttpClient,
        query: String
    ): List<WebSearchTool.Hit> = withContext(Dispatchers.IO) {
        when (cfg.backend) {
            "zhipu" -> parseZhipu(post(
                "https://open.bigmodel.cn/api/paas/v4/web_search", cfg.apiKey,
                buildJsonObject {
                    put("search_query", query)
                    put("search_engine", cfg.opt("engine").ifBlank { "search_std" })
                    put("count", cfg.count)
                }, client
            ))

            "bocha" -> parseBocha(post(
                "https://api.bochaai.com/v1/web-search", cfg.apiKey,
                buildJsonObject {
                    put("query", query)
                    put("summary", cfg.opt("summary") == "1")
                    put("count", cfg.count)
                }, client
            ))

            // 豆包两种模式端点与请求体都不同（照 RikkaHub DoubaoSearchService.kt:57-91 取形状）
            "doubao" -> {
                val global = cfg.opt("mode") == "global"
                parseDoubao(post(
                    "https://open.feedcoopapi.com/search_api/" +
                        if (global) "global_search" else "web_search",
                    cfg.apiKey,
                    if (global) buildJsonObject {
                        put("Query", query)
                        put("DocCount", cfg.count)
                        put("MaxSnippetLength", 300)
                    } else buildJsonObject {
                        put("Query", query)
                        put("SearchType", "web")
                        put("Count", cfg.count)
                        put("QueryControl", buildJsonObject { put("QueryRewrite", false) })
                    }, client
                ))
            }

            "metaso" -> parseMetaso(post(
                "https://metaso.cn/api/v1/search", cfg.apiKey,
                buildJsonObject {
                    put("q", query)
                    put("scope", "webpage")
                    put("size", cfg.count)
                    put("includeSummary", false)
                }, client
            ))

            "searxng" -> {
                val base = cfg.opt("url").trimEnd('/')
                require(base.startsWith("http")) { "SearXNG 实例 URL 未配置" }
                val url = buildString {
                    append(base).append("/search?q=").append(URLEncoder.encode(query, "UTF-8"))
                    append("&format=json")
                    if (cfg.opt("engines").isNotBlank())
                        append("&engines=").append(URLEncoder.encode(cfg.opt("engines"), "UTF-8"))
                    if (cfg.opt("language").isNotBlank())
                        append("&language=").append(URLEncoder.encode(cfg.opt("language"), "UTF-8"))
                }
                parseSearxng(get(url, cfg, client))
            }

            "custom" -> {
                val tpl = cfg.opt("template")
                require(tpl.contains("{query}")) { "自定义模板要含 {query} 占位符" }
                val url = tpl.replace("{query}", URLEncoder.encode(query, "UTF-8"))
                    .replace("{count}", cfg.count.toString())
                parseCustom(get(url, cfg, client), cfg.opt("path"))
            }

            else -> emptyList()
        }
    }

    private suspend fun post(url: String, key: String, body: JsonObject, client: OkHttpClient): String {
        val req = Request.Builder().url(url)
            .post(HaoJson.json.encodeToString(JsonObject.serializer(), body)
                .toRequestBody("application/json".toMediaType()))
            .header("Authorization", "Bearer $key")
            .header("Content-Type", "application/json")
            .build()
        return send(req, client)
    }

    private suspend fun get(url: String, cfg: SearchProviderConfig, client: OkHttpClient): String {
        val b = Request.Builder().url(url).get()
        when {
            cfg.backend == "searxng" && cfg.opt("username").isNotBlank() ->
                b.header("Authorization", Credentials.basic(cfg.opt("username"), cfg.opt("password")))
            cfg.apiKey.isNotBlank() -> {
                val header = cfg.opt("header").ifBlank { "Authorization" }
                b.header(header, if (header == "Authorization") "Bearer ${cfg.apiKey}" else cfg.apiKey)
            }
        }
        return send(b.build(), client)
    }

    private fun send(req: Request, client: OkHttpClient): String =
        client.newCall(req).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) error("HTTP ${r.code}${body.take(120).let { if (it.isBlank()) "" else "：$it" }}")
            body
        }

    // ── 纯解析（无网络，可单测）────────────────────────────────────────

    internal fun parseZhipu(text: String): List<WebSearchTool.Hit> =
        itemsAt(text, "search_result").map {
            WebSearchTool.Hit(pick(it, "title"), pick(it, "link", "url"), pick(it, "content", "snippet"))
        }

    internal fun parseBocha(text: String): List<WebSearchTool.Hit> {
        val root = obj(text) ?: return emptyList()
        // 博查 HTTP 200 也可能带业务错误码，不查就会把空结果当"没有相关结果"
        val code = (root["code"] as? JsonPrimitive)?.intOrNull
        if (code != null && code != 200) error("博查接口返回 ${code}：${pick(root, "msg", "message")}")
        val arr = jsonPath(root, "data.webPages.value") as? JsonArray ?: return emptyList()
        return arr.mapNotNull {
            if (it !is JsonObject) return@mapNotNull null
            WebSearchTool.Hit(pick(it, "name", "title"), pick(it, "url"), pick(it, "summary", "snippet"))
        }
    }

    internal fun parseSearxng(text: String): List<WebSearchTool.Hit> =
        itemsAt(text, "results").map {
            WebSearchTool.Hit(pick(it, "title"), pick(it, "url"), pick(it, "content", "snippet"))
        }

    /**
     * 豆包：两种模式响应形状不同（web_search 给 `Result.WebResults[]`，global_search 给
     * `Result.Documents[]`，后者的 Snippet 还是数组），所以一个函数吃两种，别逼调用方选。
     * 错误也分两层：网关错误在 `ResponseMetadata.Error`，业务错误在 `Result.ErrorCode`。
     */
    internal fun parseDoubao(text: String): List<WebSearchTool.Hit> {
        val root = obj(text) ?: return emptyList()
        (jsonPath(root, "ResponseMetadata.Error") as? JsonObject)?.let {
            error("豆包返回 ${pick(it, "Code", "code")}：${pick(it, "Message", "message")}")
        }
        val result = jsonPath(root, "Result") as? JsonObject ?: return emptyList()
        val code = (result["ErrorCode"] as? JsonPrimitive)?.intOrNull
        if (code != null && code != 0) error("豆包返回 $code：${pick(result, "ErrorMsg")}")
        (result["WebResults"] as? JsonArray)?.filterIsInstance<JsonObject>()?.let { arr ->
            return arr.map {
                // Summary 比 Snippet 长，有就用它（照 RikkaHub CustomResult 的取法）
                WebSearchTool.Hit(
                    pick(it, "Title", "title"), pick(it, "Url", "url"),
                    pick(it, "Summary", "summary").ifBlank { pick(it, "Snippet", "snippet") }
                )
            }
        }
        return (result["Documents"] as? JsonArray)?.filterIsInstance<JsonObject>()?.map { d ->
            val snip = (d["Snippet"] as? JsonArray)?.filterIsInstance<JsonObject>()
                ?.joinToString("\n") { pick(it, "Text", "text") }.orEmpty()
            WebSearchTool.Hit(pick(d, "Title", "title"), pick(d, "Url", "url"), snip)
        }.orEmpty()
    }

    internal fun parseMetaso(text: String): List<WebSearchTool.Hit> {
        val root = obj(text) ?: return emptyList()
        // 秘塔失败时也可能回 200 带 message，不查就把"额度不足"当成"没有相关结果"
        val code = (root["code"] as? JsonPrimitive)?.intOrNull
        if (code != null && code != 0) error("秘塔返回 $code：${pick(root, "message", "msg")}")
        return (root["webpages"] as? JsonArray)?.filterIsInstance<JsonObject>()?.map {
            WebSearchTool.Hit(
                pick(it, "title"), pick(it, "link", "url"),
                pick(it, "snippet", "summary")
            )
        }.orEmpty()
    }

    /** 自定义端点：先按给定路径找数组，没给就挨个试常见形状。 */
    internal fun parseCustom(text: String, path: String): List<WebSearchTool.Hit> {
        val root = obj(text) ?: return emptyList()
        val arr = (if (path.isNotBlank()) jsonPath(root, path) else null) as? JsonArray
            ?: listOf("results", "items", "data.webPages.value", "webPages.value", "data.results")
                .mapNotNull { jsonPath(root, it) }.firstOrNull { it is JsonArray } as? JsonArray
            ?: return emptyList()
        return arr.mapNotNull {
            if (it !is JsonObject) return@mapNotNull null
            WebSearchTool.Hit(
                pick(it, "title", "name"), pick(it, "url", "link"),
                pick(it, "snippet", "summary", "content", "text")
            )
        }.filter { it.title.isNotBlank() || it.url.isNotBlank() }
    }

    private fun itemsAt(text: String, key: String): List<JsonObject> {
        val root = obj(text) ?: return emptyList()
        return ((root[key] as? JsonArray) ?: jsonPath(root, key) as? JsonArray)
            ?.filterIsInstance<JsonObject>().orEmpty()
    }

    /**
     * 响应体 → 对象，吃不下就 null。
     *
     * 必须宽容：自建 SearXNG 忘开 json 输出时回的是整页 HTML，自定义端点也可能被网关拦回一段
     * 文本。这里抛异常，设置页就会显示 "Expected BEGIN_OBJECT but was STRING"——用户看到的是
     * 天书，真实原因（实例没开 json 输出）反而没了。判 0 结果才会继续往下走并说清是哪家空的。
     */
    private fun obj(text: String): JsonObject? = runCatching {
        HaoJson.json.parseToJsonElement(text) as? JsonObject
    }.getOrNull()

    /** 按 `a.b.c` 逐段下钻；中间不是对象就返回 null（不抛，交给调用方决定回退）。 */
    internal fun jsonPath(root: JsonElement, path: String): JsonElement? {
        var cur: JsonElement = root
        for (seg in path.split('.')) {
            cur = (cur as? JsonObject)?.get(seg) ?: return null
        }
        return cur
    }

    private fun pick(o: JsonObject, vararg keys: String): String {
        for (k in keys) {
            val v = (o[k] as? JsonPrimitive)?.contentOrNull
            if (!v.isNullOrBlank()) return v.trim()
        }
        return ""
    }

    /** 供设置页判断"这家能不能不填 key 就用"；也用于把 settings 里写错/已下架的后端名回落内置。 */
    fun isKnown(backend: String): Boolean = CATALOG.any { it.id == backend }

    /**
     * 这家**必填项**齐了没。决定三件事：行上挂「已就绪」还是「待填 X」、「测试」让不让跑、
     * 以及算不算"已添加"。
     *
     * 最后一条是 2026-09-24 真机上看出来的：原先"留过任何配置就算添加"，结果用户在档位上
     * 顺手点了一下（写进 `zhipu.engine=search_std`），一家从没填过 Key 的智谱就冒出来
     * 挂在列表里。可选值不该把一家家带进主页。
     */
    fun isConfigured(backend: String, hasKey: Boolean, options: Map<String, String>): Boolean = when {
        backend == BUILTIN -> true
        needsKey(backend) -> hasKey
        backend == "searxng" -> (options["searxng.url"] ?: "").isNotBlank()
        backend == "custom" -> (options["custom.template"] ?: "").contains("{query}")
        else -> true
    }

    /**
     * 「已添加」= 内置链 + 当前主后端 + 必填项已配齐的家。
     *
     * 做成纯函数、放这一层，是因为设置页与单测都要问同一个问题；留在 ViewModel 里
     * 就得为了测一条列表推导去造整个 AppContainer（含 Keystore）。
     * 输出按 [CATALOG] 顺序，不按 Set 迭代顺序：否则列表会随构建/输入顺序抖。
     * 内置链恒在表里：它是兜底路径，移除它等于把"主后端失败也不让任务断"一起拆了。
     */
    fun addedBackends(active: String, configured: Set<String>): List<String> =
        CATALOG.map { it.id }.filter {
            it == BUILTIN || it == active || (it in configured && it != active)
        }
}
