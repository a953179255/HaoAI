package com.haoai.agent.ui.chat

import com.haoai.agent.ui.common.parseOutline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 查看器大纲解析（批2c）：轻量正则抽声明，供大纲抽屉跳行。
 * 与 parseOutline 是同一跨层契约——改查看器高亮文案不影响这里，
 * 但改大纲正则必须跑全量（kt/json/md 三族各有行为锚）。
 */
class OutlineParseTest {

    private val kt = listOf(
        "package com.haoai.agent.ui.chat",
        "",
        "class Viewer {",
        "    private val cache = mutableMapOf<String, Int>()",
        "    fun load(path: String): String? {",
        "        return null",
        "    }",
        "    private suspend fun parse(text: String) {",
        "    }",
        "}",
        "data class Entry(val id: Int)"
    )

    @Test
    fun `kt 抽 fun与class与val`() {
        val o = parseOutline("kt", kt)
        val kinds = o.map { it.kind }
        assertTrue(kinds.containsAll(listOf("CLS", "FUN", "VAL")))
        assertEquals("Viewer", o.first { it.kind == "CLS" }.label)
        assertEquals("load", o.first { it.label == "load" }.label)
        assertEquals(4, o.first { it.label == "load" }.line)      // 0 基
        assertEquals(7, o.first { it.label == "parse" }.line)     // suspend/private 前缀跳过
    }

    @Test
    fun `json 抽键`() {
        val json = listOf(
            "{",
            "  \"settings\": {",
            "    \"providers\": []",
            "  }",
            "}"
        )
        val o = parseOutline("json", json)
        assertEquals(listOf("settings", "providers"), o.map { it.label })
        assertEquals(listOf("KEY", "KEY"), o.map { it.kind })
    }

    @Test
    fun `md 抽标题带级别`() {
        val md = listOf("# 大标题", "正文", "## 小节", "### 更小")
        val o = parseOutline("md", md)
        assertEquals(listOf("H1", "H2", "H3"), o.map { it.kind })
        assertEquals(listOf("大标题", "小节", "更小"), o.map { it.label })
    }

    @Test
    fun `不支持扩展名返回空`() {
        assertTrue(parseOutline("csv", kt).isEmpty())
        assertTrue(parseOutline("", kt).isEmpty())
    }

    @Test
    fun `kt 顶层val与类内val都收`() {
        val src = listOf("val top = 1", "class C {", "    val inner = 2", "}")
        val o = parseOutline("kt", src)
        assertEquals(listOf("top", "C", "inner"), o.map { it.label })
    }
}
