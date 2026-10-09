package com.haoai.agent.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.AnnotatedString
import com.haoai.agent.ui.common.CodeHighlight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自研高亮 v3 的判定口径钉死测试。
 *
 * 起因：v2 探针实测（2026-10-09）抓到 8 类缺陷，全部源于「注释/引号规则不分语言全局生效」
 * + 块注释不认 + 键值同色 + 大写关键字漏染——而这个 object 此前**零测试**，缺陷攒了半年没人发现。
 * v3 把规则语言档案化之后，每条修复都对应下面一组断言；动正则/词表先过这里。
 */
class CodeHighlightTest {

    private val light = CodeHighlight.lightColors()

    /** token 首次出现位置的样式色（逐 token 独立 push/pop，覆盖该点的最后一段即它的颜色）。 */
    private fun colorOf(hl: AnnotatedString, token: String): Color {
        val i = hl.text.indexOf(token)
        assertTrue("token 不存在：$token\n实际=${hl.text}", i >= 0)
        val hit = hl.spanStyles.lastOrNull { it.start <= i && it.end > i }
        assertTrue("($token) 处无样式段", hit != null)
        return (hit!!.item as SpanStyle).color
    }

    private fun hl(code: String, lang: String) = CodeHighlight.highlight(code, lang, light)

    // ══════════ 修复① 真降级：未收录语言 = 100% 纯文本 ══════════

    @Test
    fun `unknown language keeps every token plain including hash slash quote`() {
        val code = "把色值 #4ADE80 改成渐变，详见 https://haoai.app，It's fine，-- 破折号"
        val h = hl(code, "text")
        val empty = hl(code, "")
        for (one in listOf(h, empty)) {
            assertEquals("降级语言整块只该有一个 plain 段", 1, one.spanStyles.size)
            assertEquals(light.plain, (one.spanStyles[0].item as SpanStyle).color)
        }
    }

    @Test
    fun `diff and http blocks contain no colored runs`() {
        for (lang in listOf("diff", "http", "makefile", "protobuf")) {
            val h = hl("# c // x -- y 'z'", lang)
            // 降级块只有一个全铺 plain 段（withStyle 包整块），不产生多段上色
            assertTrue("$lang 应整体纯文本", h.spanStyles.all {
                (it.item as SpanStyle).color == light.plain
            })
            assertEquals(1, h.spanStyles.size)
        }
    }

    // ══════════ 修复② 注释规则按语言开关 ══════════

    @Test
    fun `hash is comment only in hash-comment languages`() {
        assertEquals(light.comment, colorOf(hl("run # 注释", "bash"), "# 注释"))
        assertEquals(light.comment, colorOf(hl("x = 1  # 注释", "py"), "# 注释"))
        assertEquals(light.comment, colorOf(hl("a: 1  # 注释", "yaml"), "# 注释"))
        // kotlin/js 行注释是 //，裸 # 不是注释（落 plain）
        assertNotEquals(light.comment, colorOf(hl("val a = # 1", "kotlin"), "# 1"))
    }

    @Test
    fun `url double slash never swallowed as comment`() {
        val code = "see https://haoai.app/docs for details"
        val slashIdx = code.indexOf("//haoai")
        for (lang in listOf("bash", "kotlin", "js", "rust", "yaml")) {
            val h = hl(code, lang)
            // 强判据：不存在任何"起点在 URL 的 // 处、且染 comment 色"的段
            h.spanStyles.forEach { span ->
                val seg = code.substring(span.start, span.end)
                if ((span.item as SpanStyle).color == light.comment) {
                    assertFalse("$lang 把 URL 当注释: $seg",
                        span.start >= slashIdx && span.start < slashIdx + 2)
                }
            }
        }
    }

    @Test
    fun `sql double dash comment needs space and language`() {
        assertEquals(light.comment, colorOf(hl("select 1 -- 尾注", "sql"), "-- 尾注"))
        // i-- 自减不是注释（旧行为保持）：整体无 comment 段
        val h = hl("x--", "sql")
        assertTrue(h.spanStyles.none { (it.item as SpanStyle).color == light.comment })
    }

    // ══════════ 修复③ 块注释 ══════════

    @Test
    fun `block comments color as comment in c-like languages`() {
        assertEquals(light.comment, colorOf(hl("/* 缓存未命中回源 */\nfun load(): Int = 1", "kotlin"),
            "/* 缓存未命中回源 */"))
        assertEquals(light.comment, colorOf(hl("/*\n * 多行\n */", "js"), "/*\n * 多行\n */"))
        // 语言没开块注释（json 之类）不许出现 comment 色
        val j = hl("/* 不是注释 */ {}", "json")
        assertTrue(j.spanStyles.none { (it.item as SpanStyle).color == light.comment })
    }

    // ══════════ 修复④ 撇号不是字符串 ══════════

    @Test
    fun `apostrophes in prose do not open strings`() {
        val h = hl("echo It's Bob's fine", "bash")
        h.spanStyles.forEach { span ->
            assertNotEquals("撇号劈成字符串", light.string, (span.item as SpanStyle).color)
        }
        // 真单引号串仍染绿
        assertEquals(light.string, colorOf(hl("echo 'hello world'", "bash"), "'hello world'"))
        // python f 前缀串放行
        assertEquals(light.string, colorOf(hl("s = f'{x} y'", "python"), "'{x} y'"))
    }

    @Test
    fun `rust lifetime ticks never pair into fake strings`() {
        val h = hl("fn tick<'a>(&'a mut self, other: &'a u8)", "rust")
        h.spanStyles.filter { (it.item as SpanStyle).color == light.string }.forEach { r ->
            val seg = h.text.substring(r.start, r.end)
            assertTrue("生命周期被配成字符串: $seg", !seg.contains(">") && !seg.contains(","))
        }
    }

    // ══════════ 修复⑤ JSON/YAML/CSS 键位 ══════════

    @Test
    fun `json keys are function-blue and values stay green`() {
        val j = hl("{\"model\": \"doubao\", \"stream\": true}", "json")
        assertEquals(light.function, colorOf(j, "\"model\""))
        assertEquals(light.string, colorOf(j, "\"doubao\""))
        assertEquals(light.literal, colorOf(j, "true"))
    }

    @Test
    fun `yaml bare keys are function-blue`() {
        val y = hl("server:\n  port: 8080  # 注释", "yaml")
        assertEquals(light.function, colorOf(y, "server"))
        assertEquals(light.function, colorOf(y, "port"))
        assertEquals(light.comment, colorOf(y, "# 注释"))
    }

    @Test
    fun `css hex colors and property keys get distinct roles`() {
        val c = hl(".hero { color: #2E7D5B; width: 42px; } /* 注 */", "css")
        assertEquals(light.number, colorOf(c, "#2E7D5B"))
        assertNotEquals("CSS 色值被当注释", light.comment, colorOf(c, "#2E7D5B"))
        assertEquals(light.function, colorOf(c, "color"))
        assertEquals(light.number, colorOf(c, "42px"))
        assertEquals(light.comment, colorOf(c, "/* 注 */"))
    }

    @Test
    fun `css at-rule is annotation blue`() {
        assertEquals(light.annotation, colorOf(hl("@media screen { color: red }", "css"), "@media"))
    }

    // ══════════ 修复⑥ 属性宏 / 预处理 / 装饰器 ══════════

    @Test
    fun `rust hash attr is annotation not comment`() {
        val r = hl("#[derive(Debug, Clone)]\nfn main() {}", "rust")
        assertEquals(light.annotation, colorOf(r, "#[derive(Debug, Clone)]"))
        assertEquals(light.keyword, colorOf(r, "fn"))
    }

    @Test
    fun `cpp include is annotation not comment`() {
        val h = hl("#include <stdio.h>\nint main(){return 0;}", "cpp")
        assertNotEquals(light.comment, colorOf(h, "#include"))
        // 指令 token 吃到行尾（整段蓝）
        val run = h.spanStyles.first { (it.item as SpanStyle).color == light.annotation }
        assertEquals(h.text.indexOf("#include"), run.start)
        assertTrue(run.end >= h.text.indexOf("<stdio.h>") + 9)
    }

    @Test
    fun `kotlin annotation stays annotation`() {
        assertEquals(light.annotation, colorOf(hl("@Composable\nfun Screen() = Unit", "kotlin"), "@Composable"))
    }

    // ══════════ 修复⑦ 大写关键字 ══════════

    @Test
    fun `uppercase sql keywords are colored`() {
        val s = hl("SELECT name FROM users WHERE age > 18", "sql")
        assertEquals(light.keyword, colorOf(s, "SELECT"))
        assertEquals(light.keyword, colorOf(s, "FROM"))
        assertEquals(light.keyword, colorOf(s, "WHERE"))
        assertEquals(light.number, colorOf(s, "18"))
        assertEquals(light.keyword, colorOf(hl("select 1", "sql"), "select"))
    }

    @Test
    fun `dockerfile uppercase instructions are keywords`() {
        val d = hl("FROM node:18\nRUN npm ci\n# build image", "dockerfile")
        assertEquals(light.keyword, colorOf(d, "FROM"))
        assertEquals(light.keyword, colorOf(d, "RUN"))
        assertEquals(light.comment, colorOf(d, "# build image"))
    }

    // ══════════ 旧能力不回归 ══════════

    @Test
    fun `classic kotlin python js behaviors survive`() {
        val kt = hl("fun load(name: String): Bitmap? {\n  val x = listOf(1)\n}", "kotlin")
        assertEquals(light.keyword, colorOf(kt, "fun"))
        assertEquals(light.keyword, colorOf(kt, "val"))
        assertEquals(light.function, colorOf(kt, "load"))
        assertEquals(light.builtin, colorOf(kt, "String"))
        assertEquals(light.builtin, colorOf(kt, "listOf"))
        assertEquals(light.number, colorOf(kt, "1"))
        val py = hl("def fib(n):\n    return fib(n-1)  # 递归", "python")
        assertEquals(light.keyword, colorOf(py, "def"))
        assertEquals(light.function, colorOf(py, "fib"))
        assertEquals(light.comment, colorOf(py, "# 递归"))
        val js = hl("const p = new Promise(res => res()); fetch(url);", "js")
        assertEquals(light.keyword, colorOf(js, "const"))
        assertEquals(light.function, colorOf(js, "fetch"))
        assertEquals(light.builtin, colorOf(js, "Promise"))
        assertEquals(light.string,
            colorOf(hl("s = \"\"\"\n多行\n\"\"\"", "python"), "\"\"\"\n多行\n\"\"\""))
    }

    @Test
    fun `xml attributes are strings`() {
        val x = hl("<a href=\"/x\" class=\"b\">text</a>", "xml")
        assertEquals(light.string, colorOf(x, "\"/x\""))
        assertEquals(light.string, colorOf(x, "\"b\""))
    }

    @Test
    fun `js multiline template string closes across lines`() {
        val h = hl("const t = `<i>a\nb</i>`; const c = 1;", "js")
        assertEquals(light.string, colorOf(h, "`<i>a\nb</i>`"))
        assertEquals(light.keyword, colorOf(h, "const"))
    }

    @Test
    fun `dotted access stays word-split`() {
        val h = hl("Math.PI and app.name", "js")
        assertEquals(light.builtin, colorOf(h, "Math"))
        assertEquals(light.plain, colorOf(h, "PI"))
    }

    // ══════════ 别名与缓存一致性 ══════════

    @Test
    fun `aliases map to same spec as canonical names`() {
        val h1 = hl("run # x", "bash"); val h2 = hl("run # x", "sh")
        assertEquals(h1.text, h2.text)
        assertEquals((h1.spanStyles[0].item as SpanStyle).color, (h2.spanStyles[0].item as SpanStyle).color)
        // html 归一到 xml：属性值染绿、# 不再是注释
        val h = hl("<p data-x=\"#1\">", "html")
        assertEquals(light.string, colorOf(h, "\"#1\""))
    }

    @Test
    fun `highlight cached equals uncached`() {
        val code = "SELECT 1 -- 注\nx"
        val a = hl(code, "sql")
        val b = CodeHighlight.highlightCached(code, "sql", light)
        assertEquals(a.text, b.text)
        assertEquals(a.spanStyles.size, b.spanStyles.size)
    }

    @Test
    fun `mixed chinese prose stays plain with only real tokens colored`() {
        val h = hl("函数 main 里 x = 1 调用", "kotlin")
        assertEquals("函数 main 里 x = 1 调用", h.text)
        // 只染 main？main 前无 fun → plain；整句只可能命中 = 之外的词，断言无关键字色
        h.spanStyles.forEach { assertNotEquals(light.keyword, (it.item as SpanStyle).color) }
    }
}
