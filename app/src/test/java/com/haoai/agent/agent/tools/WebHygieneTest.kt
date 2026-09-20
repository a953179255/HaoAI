package com.haoai.agent.agent.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 检索卫生的两道修正：正文优先取 <article>/<main>，以及"换词但结果集没变"的签名判定。
 *
 * 都来自 2026-09-20 的一次真实调研：18 个不同 query 只拿到 5 种结果、5 个不同的 IBM 页面
 * 返回文本开头全是同一坨站点导航 —— 模型看不到正文就再抓一轮，每轮重付整段上下文，
 * 15 轮烧掉 282k tokens 后撞成本熔断。
 */
class WebHygieneTest {

    // 三段文本刻意互不相同：断言"留下了哪一段"才能说明选中的是哪个容器
    private val nav = "站点导航与推荐报告列表。".repeat(120)
    private val article = "正文：2026 年多智能体编排成为企业默认形态。".repeat(120)
    private val divText = "普通容器里的文章正文。".repeat(120)
    private val widget = "登录后可以发表评论。".repeat(10)   // < MIN_MAIN_HTML，应当被当成小部件

    private fun page(article: String?, div: String? = divText) = buildString {
        append("<html><head><title>t</title></head><body>")
        append("<nav><ul>").append(nav).append("</ul></nav>")
        if (article != null) append("<article>").append(article).append("</article>")
        if (div != null) append("<div class=\"content\">").append(div).append("</div>")
        append("<footer>版权与备案 ").append(nav).append("</footer>")
        append("</body></html>")
    }

    @Test
    fun articleWinsOverPageChrome() {
        val text = HtmlText.convert(page(article = article))
        assertTrue("正文要留下", text.contains("多智能体编排成为企业默认形态"))
        assertFalse("站点导航不该再占住开头：${text.take(80)}", text.contains("站点导航与推荐报告列表"))
    }

    @Test
    fun tinyArticleIsTreatedAsWidgetNotBody() {
        // 评论框/推荐卡也常挂 <article>，只有一两百字符，不能被选成正文
        val text = HtmlText.convert(page(article = widget))
        assertTrue("该退回整页去外壳，把普通容器里的正文留下", text.contains("普通容器里的文章正文"))
    }

    @Test
    fun pageWithoutArticleStillLosesNavAndFooter() {
        val text = HtmlText.convert(page(article = null))
        assertTrue(text.contains("普通容器里的文章正文"))
        assertFalse("无 article 时也要剥掉 nav/footer：${text.take(80)}", text.contains("站点导航与推荐报告列表"))
    }

    @Test
    fun mainElementIsUsedWhenNoArticleExists() {
        val html = "<html><body><nav>$nav</nav><main>$article</main></body></html>"
        val text = HtmlText.convert(html)
        assertTrue(text.contains("多智能体编排成为企业默认形态"))
        assertFalse(text.contains("站点导航与推荐报告列表"))
    }

    private fun sigOf(vararg urls: String): String = with(WebSearchTool()) {
        urls.map { WebSearchTool.Hit("t", it, "s") }.resultSignature()
    }

    @Test
    fun sameLinkSetIsDetectedAcrossQueryRewrites() {
        // 换词后摘要措辞会变、utm 参数会变、大小写与协议会变——但链接集合相同就是没带来新页面
        assertEquals(
            sigOf("https://a.com/x?utm=1", "https://www.b.com/Y/"),
            sigOf("http://A.com/x#frag", "https://b.com/y")
        )
    }

    @Test
    fun genuinelyDifferentLinkSetsAreNotFlagged() {
        assertNotEqualsSig(sigOf("https://a.com/x"), sigOf("https://a.com/x", "https://c.com/z"))
    }

    /** Bing 的结果链接是 ck/a 跳转链：不还原的话模型拿去 web_fetch 必 403，签名也认不出重复。 */
    @Test
    fun bingRedirectLinkIsUnwrapped() = with(WebSearchTool()) {
        assertEquals(
            "https://example.com/a/b",
            unwrapBingLink("https://www.bing.com/ck/a?!&&p=acd01b39&ptn=3&ver=2&u=a1aHR0cHM6Ly9leGFtcGxlLmNvbS9hL2I&form=HDRSC2")
        )
    }

    @Test
    fun plainLinksAndUndecodableRedirectsAreLeftAlone() = with(WebSearchTool()) {
        assertEquals("https://a.com/x?y=1", unwrapBingLink("https://a.com/x?y=1"))
        // 解不动就原样返回：宁可给一条能点的跳转链，也不静默把结果丢掉
        val broken = "https://www.bing.com/ck/a?u=a1!!!!notbase64!!!!"
        assertEquals(broken, unwrapBingLink(broken))
    }

    /** 设备实测到的那批低质结果必须过不了质量门（日历表 / 工具导航站 / 英文单词词条）。 */
    @Test
    fun calendarAndNavJunkFailsTheGate() = with(WebSearchTool()) {
        val junk = listOf(
            WebSearchTool.Hit("2026年大事、要事、重要节日一览表（附放假安排）", "https://news.qq.com/rain/a/1", "x"),
            WebSearchTool.Hit("AI工具集官网 | 1000+ AI工具集合，导航大全", "https://ai-bot.cn/", "x"),
            WebSearchTool.Hit("year（英文单词）_百度百科", "https://baike.baidu.com/item/year", "x"),
            WebSearchTool.Hit("2026 Calendar - United States", "https://www.timeanddate.com/calendar/", "x")
        )
        assertTrue("低质结果集要被判出来：" + qualityIssue(junk, "2026 AI agent trends predictions").orEmpty(),
            qualityIssue(junk, "2026 AI agent trends predictions") != null)
    }

    @Test
    fun relevantResultsPassTheGate() = with(WebSearchTool()) {
        val good = listOf(
            WebSearchTool.Hit("Multi-Agent AI Orchestration: Complete 2026 Guide", "https://claritywithai.org/multi-agent-2026", "multi-agent orchestration in 2026"),
            WebSearchTool.Hit("7 Agentic AI Trends to Watch in 2026", "https://machinelearningmastery.com/agentic-ai-trends/", "agentic AI trends"),
            WebSearchTool.Hit("Agentic AI trends 2026: how multiagent systems redefine work", "https://www.druid.ai/blog/trends", "multiagent systems"),
            WebSearchTool.Hit("2026年AI Agent技术最新进展：从工具调用到自主决策", "https://blog.csdn.net/x", "AI Agent 新变化")
        )
        val q = "2026 AI agent trends multi-agent orchestration"
        assertTrue("正常结果不该被判低质：" + qualityIssue(good, q).orEmpty(), qualityIssue(good, q) == null)
    }

    /** 单词查询（切不出两个关键词）不该触发"没有一条包含查询词"这条判据。 */
    @Test
    fun singleKeywordQuerySkipsTheOverlapRule() = with(WebSearchTool()) {
        val hits = listOf(
            WebSearchTool.Hit("深度解读：智能体的今年怎么过", "https://some.site/a", "讲编排与协作"),
            WebSearchTool.Hit("Agent 编排实践", "https://other.site/b", "多路并发")
        )
        assertTrue(qualityIssue(hits, "agent") == null)
    }

    /** 反爬页是 HTTP 200 + 一小段带"验证码/antispider"的壳，必须判失败而不是"没搜到结果"。 */
    @Test
    fun antiBotPagesAreDetected() = with(WebSearchTool()) {
        assertTrue(looksLikeAntiBot("<html><body>搜狗搜索 请输入验证码 antispider</body></html>"))
        assertTrue(looksLikeAntiBot("<html>安全检验 异常流量 " + "x".repeat(200) + "</html>"))
        assertFalse(looksLikeAntiBot("<html><body>" + "正常结果块 ".repeat(5000) + "</body></html>"))
    }

    /** 全部引擎本轮不可用时，要把原因摊开并明确让模型停止空转。 */
    @Test
    fun allEnginesDeadTellsTheModelToStopSearching() = with(WebSearchTool()) {
        val msg = allDeadMessage(
            mapOf("duckduckgo" to "connect timed out", "sogou" to "反爬验证页"), emptyList()
        )
        assertTrue(msg.contains("duckduckgo: connect timed out"))
        assertTrue(msg.contains("sogou: 反爬验证页"))
        assertTrue(msg.contains("不要反复换关键词空转"))
    }

    /**
     * 引擎顺序锁：bing 必须在最前。
     * 国内直连实测（2026-09-21 广州移动）：www.bing.com 0.6s 出结果，
     * html.duckduckgo.com 挂 16s 超时、brave/mojeek 连不上、百度弹验证页。
     * 把 ddg 提到前面（海外出口下看着更好）会让每次搜索白等十几秒。
     */
    @Test
    fun bingStaysFirstBecauseOfDomesticReachability() {
        assertEquals(
            listOf("bing", "duckduckgo", "sogou"),
            WebSearchTool().ENGINE_CHAIN.map { it.first }
        )
    }

    private fun assertNotEqualsSig(a: String, b: String) = assertTrue("签名不该把不同结果集判成同一批", a != b)
}
