package com.haoai.agent.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 批2c（路径芯片定位）的口径钉死测试。
 *
 * 芯片点开是只读查看器，直接读盘上的文件——安全边界与批2a 产物卡同源
 * （locateHtmlFile 现在就是薄封装在它上面），但判定口径不同：
 * ①不筛扩展名（任何文本文件都有查看语义）；
 * ②../ 越界一律拒绝（芯片能开查看器 = 可读面，越界即扩权）；
 * ③SAF 后端（root=null）降级不出芯片。
 */
class WorkspaceFileLocateTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ════════ 无扩展名筛选 ════════

    @Test
    fun `任意工作区文件都出芯片`() {
        for (p in listOf("hello.txt", "src/Main.kt", "a/b/c.md", "noext")) {
            val r = locateWorkspaceFile("""{"path":"$p"}""", tmp.root)
            assertNotNull("应识别 $p", r)
            assertEquals(p, r!!.relPath)
        }
    }

    @Test
    fun `空参与坏JSON不出芯片`() {
        assertNull(locateWorkspaceFile("", tmp.root))
        assertNull(locateWorkspaceFile("not json", tmp.root))
        assertNull(locateWorkspaceFile("{}", tmp.root))
        assertNull(locateWorkspaceFile("""{"path":""}""", tmp.root))
        assertNull(locateWorkspaceFile("""{"file":"a.txt"}""", tmp.root))
        // root=null（SAF 后端）：即便参数齐全也不出芯片
        assertNull(locateWorkspaceFile("""{"path":"a.txt"}""", null))
    }

    // ════════ 越界防线 ════════

    @Test
    fun `上级目录逃逸被拒绝`() {
        for (p in listOf("../secret.txt", "out/../../etc/passwd", "..\\evil.txt")) {
            assertNull("应拒绝 $p", locateWorkspaceFile("""{"path":"$p"}""", tmp.root))
        }
    }

    @Test
    fun `绝对路径输入收编到根下而非原样放行`() {
        val r = locateWorkspaceFile("""{"path":"/etc/passwd"}""", tmp.root)
        assertNotNull(r)
        assertTrue(r!!.absPath.startsWith(tmp.root.canonicalPath))
    }

    @Test
    fun `嵌套路径落在根内`() {
        val r = locateWorkspaceFile("""{"path":"site/out/page.txt"}""", tmp.root)
        assertNotNull(r)
        // 单测可能跑在 Windows：canonical 用反斜杠，断言前统一成 /
        val abs = r!!.absPath.replace('\\', '/')
        assertTrue(abs, abs.startsWith(tmp.root.canonicalPath.replace('\\', '/')))
        assertTrue(abs, abs.endsWith("site/out/page.txt"))
    }

    // ════════ 批2a 口径不回归：locateHtmlFile 复用同一防线 ════════

    @Test
    fun `html 筛选口径保持`() {
        assertNotNull(locateHtmlFile("""{"path":"a/index.HTML"}""", tmp.root))
        assertNull(locateHtmlFile("""{"path":"a/index.txt"}""", tmp.root))
        assertNull(locateHtmlFile("""{"path":"../a/index.html"}""", tmp.root))
    }
}
