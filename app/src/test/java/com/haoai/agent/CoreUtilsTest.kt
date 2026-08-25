package com.haoai.agent

import com.haoai.agent.agent.provider.OpenAiCompatClient
import com.haoai.agent.agent.tools.HtmlText
import com.haoai.agent.agent.tools.TextCap
import com.haoai.agent.agent.tools.globToRegex
import com.haoai.agent.platform.PathSafety
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreUtilsTest {

    @Test
    fun `normalizeUrl appends version and endpoint`() {
        assertEquals(
            "https://api.deepseek.com/v1/chat/completions",
            OpenAiCompatClient.normalizeUrl("https://api.deepseek.com")
        )
        assertEquals(
            "https://api.openai.com/v1/chat/completions",
            OpenAiCompatClient.normalizeUrl("https://api.openai.com/v1/")
        )
        assertEquals(
            "https://example.com/api/chat/completions",
            OpenAiCompatClient.normalizeUrl("https://example.com/api/chat/completions")
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `normalizeUrl rejects non http`() {
        OpenAiCompatClient.normalizeUrl("ftp://bad")
    }

    @Test
    fun `glob patterns match as expected`() {
        val kt = globToRegex("**/*.kt")
        assertTrue(kt.matches("src/main/A.kt"))
        assertTrue(kt.matches("A.kt"))
        assertFalse(kt.matches("A.java"))

        val topMd = globToRegex("*.md")
        assertTrue(topMd.matches("readme.md"))
        assertFalse(topMd.matches("docs/readme.md"))
    }

    @Test
    fun `html to text strips tags and scripts`() {
        val html = "<html><head><style>b{}</style></head><body>" +
            "<p>Hello&nbsp;<b>World</b></p><script>evil()</script><!-- c --></body></html>"
        val text = HtmlText.convert(html)
        assertTrue(text.contains("Hello"))
        assertTrue(text.contains("World"))
        assertFalse(text.contains("evil"))
        assertFalse(text.contains("<p>"))
    }

    @Test
    fun `text cap helpers behave`() {
        assertEquals("abc", TextCap.middle("abc", 10))
        assertTrue(TextCap.middle("x".repeat(100), 10).contains("省略"))
        assertEquals("…789", TextCap.tail("123456789", 3))
        assertTrue(TextCap.head("abcdef", 3).endsWith("…"))
    }

    @Test
    fun `path safety rejects traversal`() {
        assertEquals(listOf("a", "b.txt"), PathSafety.normalize("a/b.txt"))
        assertEquals(listOf("a", "b.txt"), PathSafety.normalize("/a\\./b.txt"))
        try {
            PathSafety.normalize("../secret")
            throw AssertionError("should reject")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("越界"))
        }
    }
}
