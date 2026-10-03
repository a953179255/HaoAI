package com.haoai.pc

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 页面态的两列让位：主区翻到专家/知识库/日程/Token 统计这些整页时，
 * 会话栏（.sesscol）与右栏（.rail.r）必须跟着聊天三件套一起藏掉——
 * 效果图里它们长在会话视图内部，别的视图根本没有这两列。
 *
 * 为什么钉在源码上（而不是只靠像素剧本）：这套换页的坑全是"线没接"——
 * - CSS 写了 display:none，但 setView 只改 .main[data-page]，两栏纹丝不动
 *   （真实事故：专家页左右各挂一列聊天配件，卡片被挤成窄条）；
 * - 网格藏掉中间列后，自动布位把主区前移进 0/252px 轨道（实测 hideSessOnly main=252、
 *   hideBoth main=198）——所以四列必须有显式 grid-column，这条也顺手修了
 *   Ctrl+B 收栏（no-l）整窗空白的存量 bug；
 * - 两栏藏了，但从专家页用命令面板跳会话/开右栏页签/Ctrl N，点的是 display:none
 *   的元素——"选中态翻了、面板也画了，人什么都看不见"。
 */
class PageModeTest {

    private val ui = File("src/main/resources/ui/index.html")

    private fun read(): String {
        assertTrue("找不到 ${ui.path}（测试要在 pc/ 目录下跑）", ui.isFile)
        return ui.readText(Charsets.UTF_8)
    }

    @Test
    fun `page mode hides the session column and the chat rail`() {
        val src = read()
        assertTrue("CSS 要有 .app.page-mode{--sw:0px;--rw:0px} —— display:none 只摘元素不摘轨道，" +
            "轨道留着就是两条 252/292px 的空缝",
            Regex("""\.app\.page-mode\{--sw:0px;--rw:0px\}""").containsMatchIn(src))
        assertTrue("CSS 要把会话栏与右栏一起藏（.app.page-mode .sesscol,.app.page-mode .rail.r）",
            Regex("""\.app\.page-mode \.sesscol,\.app\.page-mode \.rail\.r\{display:none\}""")
                .containsMatchIn(src))
        assertTrue("setView 要在 .app 上切 page-mode —— 只改 .main[data-page] 两栏不会跟着走",
            Regex("""app\.classList\.toggle\('page-mode',!chat\)""").containsMatchIn(src))
        assertTrue("切进页面态要顺手收掉窄屏浮层（show-l/show-r 盖在页面上比藏掉更迷惑）",
            Regex("""if\(!chat\)app\.classList\.remove\('show-l','show-r'\)""").containsMatchIn(src))
    }

    @Test
    fun `the four shell columns are pinned to explicit grid tracks`() {
        val src = read()
        // 网格自动布位按"可见元素顺序"填格子：display:none 掉中间列，后面的列整体前移
        // （浏览器实测：hideSessOnly main=252、hideBoth main=198）。没有这四条钉位，
        // page-mode 与 Ctrl+B（no-l）都会布局错位。
        assertTrue(""".rail.l{grid-column:1}""", src.contains(".rail.l{grid-column:1}"))
        assertTrue(""".sesscol{grid-column:2}""", src.contains(".sesscol{grid-column:2}"))
        assertTrue(""".main{grid-column:3}""", src.contains(".main{grid-column:3}"))
        assertTrue(""".rail.r{grid-column:4}""", src.contains(".rail.r{grid-column:4}"))
        // 手机宽只剩一条轨道：主区必须挪回第 1 轨，否则掉进隐式轨道
        // （媒体查询块里还有别的 {} 规则，直接按索引先后判，不写跨大括号的正则）
        val mediaAt = src.indexOf("@media (max-width:860px)")
        val mainCol1 = src.indexOf(".main{grid-column:1}")
        assertTrue("≤860 媒体查询里主区要挪回 grid-column:1（找不到媒体块或规则不在其中）",
            mediaAt in 0 until mainCol1 && mainCol1 - mediaAt < 400)
    }

    @Test
    fun `every jump back to a session lands on the chat view`() {
        val src = read()
        // 这些入口在专家/知识库页上都够得着（命令面板 / Ctrl N），而它们点的东西
        // （消息流、输入框、右栏页签）全在会话页里 —— 不先 setView('chat') 就是点了没反应。
        assertTrue("命令面板的会话跳转要先回会话页",
            Regex("""g:'会话',t:m\.title,run:\(\)=>\{\s*setView\('chat'\);""").containsMatchIn(src))
        assertTrue("面板里 #入口 通用分支（右栏页签/顶栏按钮）要先回会话页",
            Regex("""setView\('chat'\);\s*e\.click\(\)""").containsMatchIn(src))
        assertTrue("palTabBtn（整理记忆/手动压缩）要先回会话页",
            Regex("""function palTabBtn\(tab,sel\)\{\s*setView\('chat'\);""").containsMatchIn(src))
        assertTrue("rerunGoal（重跑要填回输入框）要先回会话页",
            Regex("""function rerunGoal\(g\)\{\s*setView\('chat'\);""").containsMatchIn(src))
        assertTrue("newTask 成功后要把新会话切到眼前（Ctrl N 从任何页都能按）",
            Regex("""setView\('chat'\);\s*show\(d\.id\);paintUsage""").containsMatchIn(src))
    }

    @Test
    fun `ctrl k falls back to the palette when the session column is hidden`() {
        val src = read()
        assertTrue("页面态下会话栏是 display:none，Ctrl K 的落点要能落到命令面板，" +
            "别让焦点聚进藏起来的搜索框毫无动静",
            Regex("""getBoundingClientRect\(\)\.width\)q\.focus\(\);else Palette\.toggle\(\)""")
                .containsMatchIn(src))
    }
}
