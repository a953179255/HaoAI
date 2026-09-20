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

    private fun assertNotEqualsSig(a: String, b: String) = assertTrue("签名不该把不同结果集判成同一批", a != b)
}
