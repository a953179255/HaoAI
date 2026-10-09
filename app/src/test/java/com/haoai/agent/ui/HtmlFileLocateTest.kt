package com.haoai.agent.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 批2a（HTML 产物卡定位）的判定口径钉死测试。
 *
 * 产物卡能把 file:// 送进预览壳和内置浏览器——定位错了不只是不出卡，而是
 * 把「可点开面」扩到工作区之外。三条口径必须钉死：
 * ①只有 .html/.htm 出卡（别的文件类型没有 WebView 语义）；
 * ②../ 越界与绝对路径输入一律拒绝（PathSafety.normalize 抛错 → null）；
 * ③SAF 后端（root=null）降级不出卡。
 */
class HtmlFileLocateTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun root() = tmp.root

    // ════════ 类型筛选 ════════

    @Test
    fun `html 与 htm 出卡且大小写不敏感`() {
        for (p in listOf("index.html", "out/landing.HTML", "a/b/c.Htm")) {
            val r = locateHtmlFile("""{"path":"$p"}""", root())
            assertNotNull("应识别 $p", r)
            assertEquals(p, r!!.relPath)
        }
    }

    @Test
    fun `非 html 文件不出卡`() {
        for (p in listOf("a.kt", "b.txt", "c.html.bak", "d.png")) {
            assertNull("不应识别 $p", locateHtmlFile("""{"path":"$p"}""", root()))
        }
    }

    @Test
    fun `空参与坏 JSON 不出卡`() {
        assertNull(locateHtmlFile("", root()))
        assertNull(locateHtmlFile("not json", root()))
        assertNull(locateHtmlFile("{}", root()))
        // root=null（SAF 后端）：即便参数齐全也不出卡
        assertNull(locateHtmlFile("""{"path":"x.html"}""", null))
    }

    // ════════ 越界防线（产物卡 = file:// 入口，这层是安全边界） ════════

    @Test
    fun `上级目录逃逸被拒绝`() {
        for (p in listOf("../secret.html", "out/../../etc/passwd.html", "..\\evil.html")) {
            assertNull("应拒绝 $p", locateHtmlFile("""{"path":"$p"}""", root()))
        }
    }

    @Test
    fun `绝对路径输入收编到根下而非原样放行`() {
        val r = locateHtmlFile("""{"path":"/etc/passwd.html"}""", root())
        // trimStart('/') 后变相对路径 → 落在根内，绝不指向真实 /etc
        assertNotNull(r)
        assertTrue(r!!.absPath.startsWith(root().canonicalPath))
    }

    @Test
    fun `嵌套路径正确拼接且落在根内`() {
        val r = locateHtmlFile("""{"path":"site/out/page.html"}""", root())
        assertNotNull(r)
        // 单测可能跑在 Windows：canonical 用反斜杠，断言前统一成 /
        val abs = r!!.absPath.replace('\\', '/')
        assertTrue(abs, abs.startsWith(root().canonicalPath.replace('\\', '/')))
        assertTrue(abs, abs.endsWith("site/out/page.html"))
    }
}
