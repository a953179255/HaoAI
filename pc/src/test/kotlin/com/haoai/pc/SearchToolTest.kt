package com.haoai.pc

import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files

/**
 * `web_search` 这条线。
 *
 * 为什么单独测：搜索是这台机器上**唯一一条依赖别人页面结构**的能力 —— DuckDuckGo 改版
 * 就会一条都解析不出来，而那时最坏的情况不是"报错"，是"返回空结果，模型于是开始编"。
 * 所以判据分两层：解析用离线 fixture（改版时这条先红），
 * 端到端用本地假搜索服务（不碰网络，但真的走完发请求 → 解析 → 排版这条路）。
 */
class SearchToolTest {

    private class AllowAll : Gate {
        override fun approve(title: String, detail: String, kind: String) = true
        override fun ask(question: String, options: List<String>) = options.firstOrNull() ?: ""
    }

    private fun args(j: String) = Json.parseToJsonElement(j).jsonObject

    private fun ctx() = ToolCtx(
        Files.createTempDirectory("haoai-search-ws").toFile().apply { mkdirs() },
        PcSettings(permissionMode = "auto", searchProvider = "duckduckgo"), "auto", AllowAll()
    )

    companion object {
        /** 手写的 DDG 结果页片段：结构与线上一致（result__a / result__snippet / l/?uddg= 跳转壳）。 */
        private const val DDG_HTML = """
<html><body>
<div class="result results_links">
  <div class="links_main links_deep result__body">
    <h2 class="result__title">
      <a rel="nofollow" class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fkotlinlang.org%2Fdocs%2Fcoroutines.html&amp;rut=abc">Kotlin Coroutines 概览</a>
    </h2>
    <a class="result__snippet" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fkotlinlang.org%2Fdocs%2Fcoroutines.html">挂起函数与<em>协程</em>的官方说明&nbsp;文档</a>
  </div>
</div>
<div class="result results_links">
  <div class="result__body">
    <a class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fa">第二个结果 &amp; 实体</a>
    <a class="result__snippet" href="#">摘要二</a>
  </div>
</div>
<div class="result results_links">
  <div class="result__body"><a class="result__a" href="">坏链接</a></div>
</div>
</body></html>
"""

        private lateinit var fake: HttpServer
        private var base = ""

        @BeforeClass
        @JvmStatic
        fun setUp() {
            val home = Files.createTempDirectory("haoai-search-home").toFile()
            System.setProperty("haoai.home", File(home, "home").absolutePath)
            fake = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            fake.createContext("/html") { ex ->
                val body = DDG_HTML.toByteArray()
                ex.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
                ex.sendResponseHeaders(200, body.size.toLong())
                ex.responseBody.use { it.write(body) }
                ex.close()
            }
            fake.createContext("/empty") { ex ->
                val body = "<html><body>nothing here</body></html>".toByteArray()
                ex.sendResponseHeaders(200, body.size.toLong())
                ex.responseBody.use { it.write(body) }
                ex.close()
            }
            fake.start()
            base = "http://127.0.0.1:${fake.address.port}"
        }

        @AfterClass
        @JvmStatic
        fun tearDown() {
            runCatching { fake.stop(0) }
        }
    }

    @Test
    fun `the duckduckgo parser pulls title url and snippet out of the html`() {
        val hits = Search.parseDuckDuckGo(DDG_HTML)
        assertEquals("三条里有一条链接是空的，应当只剩两条：" + hits, 2, hits.size)
        assertEquals("Kotlin Coroutines 概览", hits[0].title)
        assertEquals("跳转壳要解成真实地址", "https://kotlinlang.org/docs/coroutines.html", hits[0].url)
        assertTrue("摘要里的标签要清掉、实体要解：" + hits[0].snippet,
            hits[0].snippet.contains("协程") && hits[0].snippet.contains("文档"))
        assertEquals("https://example.com/a", hits[1].url)
        assertEquals("标题里的 HTML 实体也要解", "第二个结果 & 实体", hits[1].title)
    }

    @Test
    fun `an empty or restructured page parses to nothing instead of throwing`() {
        assertEquals(0, Search.parseDuckDuckGo("").size)
        assertEquals(0, Search.parseDuckDuckGo("<html><body>改版了</body></html>").size)
        assertEquals(0, Search.parseDuckDuckGo(DDG_HTML, limit = 0).size)
    }

    @Test
    fun `provider selection prefers the keyed one only when a key exists`() {
        assertEquals("duckduckgo", Search.provider("duckduckgo", "k"))
        assertEquals("bocha", Search.provider("bocha", ""))          // 显式选了就去，跑不通会报错
        assertEquals("duckduckgo", Search.provider("auto", ""))       // 没 key 走免 key 的那家
        assertEquals("bocha", Search.provider("auto", "kk"))
        assertEquals("duckduckgo", Search.provider("", ""))
    }

    @Test
    fun `the tool really walks request to formatted result against a local search box`() {
        val old = Search.ddgBase
        Search.ddgBase = "$base/html"
        try {
            val r = WebSearchTool().runB(args("""{"query":"kotlin 协程"}"""), ctx())
            assertFalse("不该报错：" + r.content, r.error)
            assertTrue("要带上提供方与关键词：" + r.content.take(60),
                r.content.contains("duckduckgo") && r.content.contains("kotlin 协程"))
            assertTrue("结果要带可点的 url", r.content.contains("https://kotlinlang.org/docs/coroutines.html"))
            assertTrue("要提醒它接着用 web_fetch 读全文", r.content.contains("web_fetch"))
        } finally {
            Search.ddgBase = old
        }
    }

    /** 空结果必须说人话：模型拿到"一条都没有"时最容易开始凭记忆编。 */
    @Test
    fun `no hits is reported as a problem, not as an empty success`() {
        val old = Search.ddgBase
        Search.ddgBase = "$base/empty"
        try {
            val r = WebSearchTool().runB(args("""{"query":"随便什么"}"""), ctx())
            assertTrue(r.error)
            assertTrue("要给出下一步怎么办：" + r.content,
                r.content.contains("换个说法") && r.content.contains("web_fetch"))
        } finally {
            Search.ddgBase = old
        }
    }

    @Test
    fun `an empty query is refused before spending a request`() {
        val r = WebSearchTool().runB(args("""{"query":"   "}"""), ctx())
        assertTrue(r.error)
        assertTrue(r.content.contains("query"))
    }

    @Test
    fun `bocha json parses and a missing key yields nothing rather than a bad request`() {
        val hits = Search.parseBocha(
            """{"data":{"webPages":{"value":[{"name":"博查标题","url":"https://a.example/x",""" +
                """"summary":"一段摘要"},{"nope":1}]}}}"""
        )
        assertEquals(1, hits.size)
        assertEquals("博查标题", hits[0].title)
        assertEquals("https://a.example/x", hits[0].url)
        assertEquals("一段摘要", hits[0].snippet)
        assertEquals("不是 JSON 就当没有", 0, Search.parseBocha("<html>").size)
        assertEquals("没有 key 就不该发请求", 0, Search.bocha(java.net.http.HttpClient.newHttpClient(), "", "q", 5).size)
    }
}
