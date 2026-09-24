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
    val KEYPED = setOf("zhipu", "bocha")

    /** 后端是否需要我们这边存 key（决定 UI 上要不要显示输入框）。 */
    fun needsKey(backend: String): Boolean = backend in KEYPED

    /** 一行说明，直接给设置页与结果标注用。 */
    fun label(backend: String): String = when (backend) {
        "zhipu" -> "智谱 Web Search"
        "bocha" -> "博查 BoCha"
        "searxng" -> "自建 SearXNG"
        "custom" -> "自定义端点"
        else -> "内置免 key 引擎"
    }

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

    /** 供设置页判断"这家能不能不填 key 就用"。 */
    fun isKnown(backend: String): Boolean =
        backend == BUILTIN || backend in setOf("zhipu", "bocha", "searxng", "custom")
}
