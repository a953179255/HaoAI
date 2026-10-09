package com.haoai.agent.ui

import com.haoai.agent.agent.engine.inlineBodyOf
import com.haoai.agent.ui.chat.formatElapsed
import com.haoai.agent.ui.chat.parseToolBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 批1e（工具结果正文内联）的判定口径钉死测试。
 *
 * 这一批的实质是「把已经在算的数据送上屏」，但两处口径错了就白做：
 * ①选错工具（web_fetch 几万字符正文塞进消息流会把对话冲垮）；
 * ②bash 的 `exit=N\n---\n` 头剥不干净，正文里会和头部徽标重复一遍。
 */
class ToolBodyCardTest {

    // ══════════ 选哪些工具内联 ══════════

    @Test
    fun `编码向六件套与 job_output 内联正文`() {
        for (name in listOf("bash", "job_output", "read", "write", "edit", "glob", "grep")) {
            assertTrue("$name 应内联正文", inlineBodyOf(name, "some output") != null)
        }
    }

    @Test
    fun `网页抓取与浏览器类不内联正文`() {
        // 正文动辄几万字符，进消息流会冲垮对话——这类维持 preview 一行
        for (name in listOf("web_fetch", "web_search", "browser_read", "browser_screenshot")) {
            assertNull("$name 不应内联正文", inlineBodyOf(name, "x".repeat(50_000)))
        }
    }

    @Test
    fun `空结果不出卡`() {
        for (blank in listOf("", "   ", "\n\n")) {
            assertNull("空白结果不应出卡", inlineBodyOf("bash", blank))
        }
    }

    @Test
    fun `超长正文中间截断且头尾都在`() {
        val body = "HEAD" + "x".repeat(9000) + "TAIL"
        val capped = inlineBodyOf("bash", body)!!
        assertTrue("应截断到 2400 附近，实际 ${capped.length}", capped.length <= 2400)
        assertTrue("头须保留", capped.startsWith("HEAD"))
        assertTrue("尾须保留", capped.endsWith("TAIL"))
    }

    // ══════════ bash 的 exit 头剥离 ══════════

    @Test
    fun `bash 结果剥出退出码与分隔线`() {
        val parsed = parseToolBody("bash", "exit=0\n---\n> Task :app:compileDebugKotlin\nBUILD SUCCESSFUL")
        assertEquals(0, parsed.exitCode)
        assertEquals("> Task :app:compileDebugKotlin\nBUILD SUCCESSFUL", parsed.text)
    }

    @Test
    fun `失败退出码照剥不误`() {
        val parsed = parseToolBody("bash", "exit=127\n---\nsh: gradlew: not found")
        assertEquals(127, parsed.exitCode)
        assertEquals("sh: gradlew: not found", parsed.text)
    }

    @Test
    fun `退出码带后端标注也能剥`() {
        // BashTool.kt:152 的 note 拼在 exit= 后面（linux 沙箱 / ssh），不能因此整头丢掉
        val parsed = parseToolBody("bash", "exit=0（linux 沙箱）\n---\nstarted")
        assertEquals(0, parsed.exitCode)
        assertEquals("started", parsed.text)
    }

    @Test
    fun `非 bash 工具不剥不改原样`() {
        for (name in listOf("read", "grep", "write")) {
            val raw = "1: package com.haoai\n2: "
            val parsed = parseToolBody(name, raw)
            assertNull("$name 不该有退出码", parsed.exitCode)
            assertEquals("$name 正文须原样", raw, parsed.text)
        }
    }

    @Test
    fun `无分隔线的异常格式保守保留全文`() {
        // 宁可多显示一行，也不要因为猜错格式把内容吃掉
        val parsed = parseToolBody("bash", "exit=0\nBUILD SUCCESSFUL")
        assertEquals(0, parsed.exitCode)
        assertTrue("异常格式须保留原文", parsed.text.contains("BUILD SUCCESSFUL"))
    }

    // ══════════ 耗时格式化 ══════════

    @Test
    fun `耗时按量级切换单位`() {
        assertEquals("0ms", formatElapsed(0))
        assertEquals("320ms", formatElapsed(320))
        assertEquals("1.8s", formatElapsed(1800))
        assertEquals("59.9s", formatElapsed(59_900))
        assertEquals("1m5s", formatElapsed(65_000))
        assertEquals("12m3s", formatElapsed(723_000))
    }
}