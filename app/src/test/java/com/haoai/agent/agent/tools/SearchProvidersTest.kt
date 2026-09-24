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

    @Test
    fun onlyZhipuAndBochaNeedAKeyFromTheUser() {
        assertTrue(SearchProviders.needsKey("zhipu"))
        assertTrue(SearchProviders.needsKey("bocha"))
        assertFalse(SearchProviders.needsKey("searxng"))
        assertFalse(SearchProviders.needsKey("custom"))
        assertFalse(SearchProviders.needsKey("builtin"))
    }

    /** 设置页存来的后端名必须认得；不认得时容器会回落内置，不能让一次写错的配置把联网能力判死。 */
    @Test
    fun knownBackendsCoverTheFiveOffered() {
        listOf("builtin", "zhipu", "bocha", "searxng", "custom").forEach {
            assertTrue(it, SearchProviders.isKnown(it))
        }
        assertFalse(SearchProviders.isKnown("tavily"))
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
