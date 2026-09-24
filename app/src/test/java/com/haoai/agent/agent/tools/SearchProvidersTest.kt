package com.haoai.agent.agent.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 四家搜索后端的响应解析。
 *
 * 全部用假 JSON 打：手上没有 key，但适配器的主要风险恰恰是"字段名猜错"，
 * 所以按各家真实响应形状（照 RikkaHub 的实测实现取字段：智谱 search_result[].title/link、
 * 博查 data.webPages.value[].name/url、SearXNG results[]、自定义按路径下钻）逐条钉住。
 * 网络请求本身不在单测范围内（那是设置页「测试搜索」的职责）。
 */
class SearchProvidersTest {

    // ── 智谱 ────────────────────────────────────────────────────────

    @Test
    fun zhipuReadsTitleLinkContentTriple() {
        val hits = SearchProviders.parseZhipu(
            """{"error_msg":"","request_id":"r1","search_result":[
               |{"title":"2026 年多智能体编排成为企业默认形态","content":"摘要一","link":"https://a.com/x","date":"2026-01-02"},
               |{"title":"第二条","content":"摘要二","link":"https://b.com/y"}]}""".trimMargin()
        )
        assertEquals(2, hits.size)
        assertEquals("2026 年多智能体编排成为企业默认形态", hits[0].title)
        // link 而不是 url：智谱给的是 link 字段，取错就是每条结果都没链接
        assertEquals("https://a.com/x", hits[0].url)
        assertEquals("摘要二", hits[1].snippet)
    }

    @Test
    fun zhipuEmptyResultIsNotAnError() {
        assertTrue(SearchProviders.parseZhipu("""{"search_result":[]}""").isEmpty())
        assertTrue(SearchProviders.parseZhipu("""{}""").isEmpty())
    }

    // ── 博查 ────────────────────────────────────────────────────────

    @Test
    fun bochaDrillsIntoWebPagesValueAndPrefersName() {
        val hits = SearchProviders.parseBocha(
            """{"code":200,"data":{"webPages":{"value":[
               |{"name":"博查给的标题","url":"https://c.com/z","summary":"摘要","snippet":"备选"}]}},
             |"msg":"success"}""".trimMargin()
        )
        assertEquals(1, hits.size)
        assertEquals("博查给的标题", hits[0].title)
        assertEquals("https://c.com/z", hits[0].url)
        assertEquals("摘要", hits[0].snippet)
    }

    /** 博查 HTTP 200 也可能带业务错误码——不查就会把"鉴权失败"当成"没有相关结果"。 */
    @Test
    fun bochaBusinessErrorCodeThrows() {
        var thrown = ""
        runCatching {
            SearchProviders.parseBocha("""{"code":401,"msg":"invalid api key"}""")
        }.onFailure { thrown = it.message.orEmpty() }
        assertTrue(thrown.contains("401"))
        assertTrue(thrown.contains("invalid api key"))
    }

    // ── SearXNG ─────────────────────────────────────────────────────

    @Test
    fun searxngReadsResultsArray() {
        val hits = SearchProviders.parseSearxng(
            """{"query":"q","results":[{"title":"实例标题","url":"https://d.com","content":"正文片段"}]}"""
        )
        assertEquals(1, hits.size)
        assertEquals("实例标题", hits[0].title)
        assertEquals("https://d.com", hits[0].url)
    }

    /** 实例没开 json 输出时回的是 HTML：解析器不该抛，判 0 结果让引擎链继续往下走。 */
    @Test
    fun searxngHtmlResponseDegradesToEmpty() {
        assertTrue(SearchProviders.parseSearxng("<html><body>searxng</body></html>").isEmpty())
    }

    // ── 自定义端点 ──────────────────────────────────────────────────

    @Test
    fun customUsesGivenPathWhenProvided() {
        val body = """{"data":{"items":[{"name":"甲","url":"https://e"}]},"results":[{"title":"乙","url":"https://f"}]}"""
        assertEquals("甲", SearchProviders.parseCustom(body, "data.items").first().title)
        // 没给路径时按常见形状探测：results 先命中
        assertEquals("乙", SearchProviders.parseCustom(body, "").first().title)
    }

    @Test
    fun customSkipsItemsWithNeitherTitleNorUrl() {
        val hits = SearchProviders.parseCustom(
            """{"results":[{"title":"","url":""},{"title":"有名字","url":""}]}""", ""
        )
        assertEquals(1, hits.size)
        assertEquals("有名字", hits[0].title)
    }

    // ── 元数据 ──────────────────────────────────────────────────────

    // ── 豆包（两种模式响应形状不同）──────────────────────────────────

    @Test
    fun doubaoWebModeReadsWebResultsAndPrefersSummary() {
        val hits = SearchProviders.parseDoubao(
            """{"ResponseMetadata":{"Error":null},"Result":{"WebResults":[
               |{"Title":"多智能体编排成为默认","Url":"https://a.com/x","Summary":"长摘要","Snippet":"短"},
               |{"Title":"只有 Snippet 的一条","Url":"https://a.com/y","Snippet":"就它"}]}}""".trimMargin()
        )
        assertEquals(2, hits.size)
        assertEquals("https://a.com/x", hits[0].url)
        assertEquals("长摘要", hits[0].snippet)
        assertEquals("就它", hits[1].snippet)
    }

    @Test
    fun doubaoGlobalModeJoinsSnippetArray() {
        val hits = SearchProviders.parseDoubao(
            """{"Result":{"ErrorCode":0,"Documents":[
               |{"Url":"https://g.com/1","Title":"全球结果","Snippet":[{"Text":"第一段"},{"Text":"第二段"}]}]}}""".trimMargin()
        )
        assertEquals(1, hits.size)
        // Snippet 是数组，不 join 就会把正文丢掉
        assertEquals("第一段\n第二段", hits[0].snippet)
    }

    /** 豆包的错误分两层：网关在 ResponseMetadata.Error，业务在 Result.ErrorCode。两层都得报出来。 */
    @Test
    fun doubaoReportsBothErrorLayers() {
        val gateway = runCatching {
            SearchProviders.parseDoubao("""{"ResponseMetadata":{"Error":{"Code":"InvalidParameter","Message":"key invalid"}}}""")
        }.exceptionOrNull()?.message.orEmpty()
        assertTrue(gateway.contains("InvalidParameter"))
        assertTrue(gateway.contains("key invalid"))

        val business = runCatching {
            SearchProviders.parseDoubao("""{"Result":{"ErrorCode":429,"ErrorMsg":"quota exceeded","Documents":[]}}""")
        }.exceptionOrNull()?.message.orEmpty()
        assertTrue(business.contains("429"))
        assertTrue(business.contains("quota exceeded"))
    }

    @Test
    fun doubaoMissingResultIsZeroHitsNotACrash() {
        assertTrue(SearchProviders.parseDoubao("""{"ResponseMetadata":{}}""").isEmpty())
        assertTrue(SearchProviders.parseDoubao("<html>网关返回页</html>").isEmpty())
    }

    // ── 秘塔 ─────────────────────────────────────────────────────────

    @Test
    fun metasoReadsWebpagesWithLinkAsUrl() {
        val hits = SearchProviders.parseMetaso(
            """{"credits":3,"webpages":[
               |{"title":"秘塔结果","link":"https://m.com/1","snippet":"摘要片段","score":"0.8"}]}""".trimMargin()
        )
        assertEquals(1, hits.size)
        // link 而不是 url：字段取错就是每条结果都没链接
        assertEquals("https://m.com/1", hits[0].url)
        assertEquals("摘要片段", hits[0].snippet)
    }

    @Test
    fun metasoFallsBackToSummaryWhenSnippetAbsent() {
        val hits = SearchProviders.parseMetaso(
            """{"webpages":[{"title":"只有 summary","link":"https://m.com/2","summary":"正文概要"}]}"""
        )
        assertEquals("正文概要", hits[0].snippet)
    }

    @Test
    fun metasoEmptyWebpagesIsZeroHits() {
        assertTrue(SearchProviders.parseMetaso("""{"credits":0,"webpages":[]}""").isEmpty())
    }

    // ── 目录元数据 ──────────────────────────────────────────────────

    /** 目录表自洽：id 唯一、短名/一句话都填了、needsKey 与"要填什么"对得上。 */
    @Test
    fun catalogIsSelfConsistent() {
        val ids = SearchProviders.CATALOG.map { it.id }
        assertEquals(ids.size, ids.distinct().size)
        SearchProviders.CATALOG.forEach {
            assertTrue(it.id, it.name.isNotBlank() && it.short.isNotBlank() && it.one.isNotBlank())
            assertTrue(it.id, it.hint.isNotBlank())
            assertTrue(it.id, it.reach.isNotBlank())
            assertEquals(it.id, SearchProviders.needsKey(it.id), it.need == "API Key")
            assertTrue(it.id, SearchProviders.isKnown(it.id))
            // 内置链没有官网可去
            if (it.id == SearchProviders.BUILTIN) assertTrue(it.id, it.site.isBlank())
        }
    }

    /** 回落语义：settings.json 里躺着已下架/写错的后端名时，必须回落内置而不是整条联网能力判死。 */
    @Test
    fun unknownBackendFallsBackToBuiltinRow() {
        assertEquals(SearchProviders.BUILTIN, SearchProviders.backend("tavily").id)
        assertFalse(SearchProviders.isKnown("tavily"))
    }

    @Test
    fun knownBackendsCoverWhatWeShip() {
        listOf("builtin", "zhipu", "bocha", "doubao", "metaso", "searxng", "custom").forEach {
            assertTrue(it, SearchProviders.isKnown(it))
        }
    }

    // ── 「已添加」推导与必填项判据 ──────────────────────────────────

    /** 必填项配齐才算"配过这家"；可选值不该把一家带进主页。 */
    @Test
    fun optionalValuesAloneDoNotMarkABackendConfigured() {
        // 只点了档位（zhipu.engine），没填 Key —— 真机上就是这么冒出来一家的
        val onlyEngine = mapOf("zhipu.engine" to "search_std")
        assertFalse(SearchProviders.isConfigured("zhipu", hasKey = false, options = onlyEngine))
        assertTrue(SearchProviders.isConfigured("zhipu", hasKey = true, options = onlyEngine))
        // SearXNG 认 URL，自定义认含 {query} 的模板
        assertFalse(SearchProviders.isConfigured("searxng", false, mapOf("searxng.engines" to "google")))
        assertTrue(SearchProviders.isConfigured("searxng", false, mapOf("searxng.url" to "https://s.example")))
        assertFalse(SearchProviders.isConfigured("custom", false, mapOf("custom.template" to "https://x/y")))
        assertTrue(SearchProviders.isConfigured("custom", false, mapOf("custom.template" to "https://x/q={query}")))
        // 内置链恒为已配
        assertTrue(SearchProviders.isConfigured("builtin", false, emptyMap()))
    }

    @Test
    fun addedBackendsAreDerivedFromWhatWasFilled() {
        val added = SearchProviders.addedBackends(
            active = "zhipu",
            configured = setOf("zhipu", "bocha", "searxng")
        )
        // 内置链恒在（它是兜底）；其余按 CATALOG 顺序，不按 Set 迭代顺序，否则列表会抖
        assertEquals(listOf("builtin", "zhipu", "bocha", "searxng"), added)
        // 当前主后端即使没填完也留在表里（刚从目录页选进来，得看得见它待填）
        assertEquals(
            listOf("builtin", "custom"),
            SearchProviders.addedBackends("custom", emptySet())
        )
        // 没配过的家不冒出来
        assertEquals(listOf("builtin"), SearchProviders.addedBackends("builtin", emptySet()))
    }

    /** 残留/异常名字不该把家带进表。 */
    @Test
    fun staleOrBlankNamesDoNotCountAsAdded() {
        assertEquals(
            listOf("builtin"),
            SearchProviders.addedBackends("builtin", setOf("", "tavily", "exa"))
        )
    }

    @Test
    fun onlyKeyedProvidersNeedAKeyFromTheUser() {
        listOf("zhipu", "bocha", "doubao", "metaso").forEach {
            assertTrue(it, SearchProviders.needsKey(it))
        }
        assertFalse(SearchProviders.needsKey("searxng"))
        assertFalse(SearchProviders.needsKey("custom"))
        assertFalse(SearchProviders.needsKey("builtin"))
    }


    @Test
    fun optionsAreNamespacedByBackend() {
        val cfg = SearchProviderConfig(
            backend = "searxng",
            options = mapOf("searxng.url" to " https://s.example ", "zhipu.url" to "https://wrong")
        )
        // 取错命名空间 = 用了另一家填的值
        assertEquals("https://s.example", cfg.opt("url"))
        // builtin 才是"不外呼"；自建实例虽然是自己的服务器，也属于外部后端
        assertFalse(SearchProviderConfig(backend = "builtin").external)
        assertTrue(SearchProviderConfig(backend = "searxng").external)
    }

    @Test
    fun jsonPathStopsQuietlyWhenShapeDoesntMatch() {
        val root = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"a":{"b":1}}"""
        )
        assertNull(SearchProviders.jsonPath(root, "a.nope"))
        // 中间段是数字不是对象：同样返回 null，不抛
        assertNull(SearchProviders.jsonPath(root, "a.b.c"))
    }
}
