package com.haoai.pc

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「入口承诺 = 落点」的机械核查（fcd6091 那次"点技能弹设置"事故的举一反三）。
 *
 * 这一族 bug 的共同形状：某个入口（导航行/命令面板/帮助文案）承诺去 A，
 * 实际落 B 或落空——每一侧单独看都语法正确、冒烟也不会红（入口照样有反应），
 * 只有把"承诺"和"落点"摆在一起对才知道歪没歪。所以这里把四条对照关系钉死：
 *  ① 右栏页签钮 ↔ tabp 面板 1:1（钮没有面板=点了没反应，面板没有钮=内容永远看不见）
 *  ② 命令面板 PALSET 的 # 选择器每个都要在 HTML 里真实存在
 *  ③ 面板里"设置：X"系列的锚点词必须出现在设置弹窗的模板文案里
 *     （锚点靠 indexOf 命中，弹窗改了文案而面板没跟，就会报"设置里没找到"）
 *  ④ 程序化点右栏页签之前必须先 setView('chat')（页面态下页签是 display:none）
 */
class EntryLandingTest {

    private val ui = File("src/main/resources/ui/index.html")

    private fun read(): String {
        assertTrue("找不到 ${ui.path}（测试要在 pc/ 目录下跑）", ui.isFile)
        return ui.readText(Charsets.UTF_8)
    }

    @Test
    fun `rail tab buttons and tab panels match one to one`() {
        val src = read()
        val tabsAt = assertTrue2("右栏页签行 <div class=\"tabs\" id=\"tabs\"> 不见了——页签结构被挪走，先修这条测试", src, "<div class=\"tabs\" id=\"tabs\">")
        // 页签行到第一个面板开头之间就是整排按钮；面板 id 全文收集
        val rowEnd = src.indexOf("<div class=\"tabp\"", tabsAt)
        val row = src.substring(tabsAt, rowEnd)
        val buttons = Regex("""<button data-t="(\w+)"""").findAll(row).map { it.groupValues[1] }.toList()
        val panels = Regex("""<div class="tabp[^"]*" id="tab-(\w+)">""").findAll(src).map { it.groupValues[1] }.toList()
        assertTrue("右栏页签钮一个都没有（>=12 个才对：任务/用量/产出/Git/终端/预览/记忆/技能/定时/自动化/工具/知识）", buttons.size >= 12)
        assertTrue("tabp 面板一个都没有（页签钮数量应与面板一致）", panels.size >= 12)
        val orphanBtn = buttons.filter { it !in panels }
        assertTrue("页签钮没有对应面板（点了没反应）: $orphanBtn", orphanBtn.isEmpty())
        val orphanPanel = panels.filter { it !in buttons }
        assertTrue("面板没有页签钮（内容永远打不开）: $orphanPanel", orphanPanel.isEmpty())
    }

    @Test
    fun `every palette hash target exists in the page`() {
        val src = read()
        val palAt = assertTrue2("PALSET 不见了——命令面板的落点表被挪走，先修这条测试", src, "const PALSET=[")
        val pal = src.substring(palAt, src.indexOf("\n];", palAt))
        // '#xxx' 或 '#tabs button[data-t=xxx]' 两种形状
        val ids = Regex("""'#(\w+)'""").findAll(pal).map { it.groupValues[1] }.toList()
        val tabs = Regex("""'#tabs button\[data-t=(\w+)\]'""").findAll(pal).map { it.groupValues[1] }.toList()
        val missIds = ids.filter { !Regex("""id="$it"""").containsMatchIn(src) }
        assertTrue("命令面板 # 落点在页面里不存在（点了 toast 或没反应）: $missIds", missIds.isEmpty())
        val missTabs = tabs.filter { !Regex("""<button data-t="$it"""").containsMatchIn(src) }
        assertTrue("命令面板指向的右栏页签不存在: $missTabs", missTabs.isEmpty())
        assertTrue("面板里该有「右栏：技能」入口（fcd6091 起技能是右栏页签）", pal.contains("data-t=skills"))
    }

    @Test
    fun `every settings anchor in the palette exists in the settings dialog`() {
        val src = read()
        val dlgAt = src.indexOf("dg.innerHTML=`<h3>设置</h3>")
        assertTrue("设置弹窗模板不见了（Cfg.open 的 innerHTML 被挪走），先修这条测试", dlgAt >= 0)
        val dlg = src.substring(dlgAt, src.indexOf("`;", dlgAt))
        val palAt = src.indexOf("const PALSET=[")
        val pal = src.substring(palAt, src.indexOf("\n];", palAt))
        // 不以 # 开头的那条是"设置里的某一节"（palJumpCfg 按 indexOf 找 grp/label 文案）。
        // 第二列必须是引号字符串：函数型条目（()=>palTabBtn(…)）在源码里也不以 # 开头，
        // 但那是"动作"不是锚点，抓进来必误报。
        val needles = Regex("""\['([^']+)',\s*'([^'#][^']*)'\s*\]""").findAll(pal).map { it.groupValues[2] }.toList()
        assertTrue("从 PALSET 里没解析出设置锚点（条目形状变了）", needles.size >= 6)
        val miss = needles.filter { it !in dlg }
        assertTrue("面板承诺的设置章节在弹窗文案里找不到（会报「设置里没找到」）: $miss", miss.isEmpty())
    }

    @Test
    fun `every programmatic rail tab click is preceded by setView chat`() {
        val src = read()
        val needle = ".click()"
        var from = 0
        var hits = 0
        while (true) {
            val at = src.indexOf("document.querySelector('#tabs button[data-t=", from)
            if (at < 0) break
            val click = src.indexOf(needle, at)
            val lineEnd = src.indexOf('\n', at)
            // 只认"同一句里直接 .click()"的那种跳页签写法（palTabBtn 等另算，已有 pin）
            if (click in 0 until lineEnd) {
                hits++
                val before = src.substring(maxOf(0, at - 160), at)
                assertTrue("程序化点右栏页签前没有 setView('chat')——页面态下那是 display:none 的元素：…${before.takeLast(80)}",
                    before.contains("setView('chat')"))
            }
            from = at + 10
        }
        assertTrue("一条程序化跳页签都没找到（接线写法变了，先修这条测试）", hits >= 5)
    }

    @Test
    fun `help dialog shortcuts match the registered keydown handlers`() {
        val src = read()
        // 帮助文案这一侧
        assertTrue("帮助里要有 Ctrl Shift P 命令面板（无条件开面板的键位只此一个，漏写=没人知道）",
            Regex("""<kbd>Ctrl Shift P</kbd> 命令面板""").containsMatchIn(src))
        assertTrue("帮助里 Ctrl K 要写成「搜会话」——它实际是聚焦会话搜索框，藏着时才兜底开面板；" +
            "写成「命令面板」就是把兜底当成主要行为",
            Regex("""<kbd>Ctrl K</kbd> 搜会话""").containsMatchIn(src))
        // keydown 注册这一侧：文案承诺的每个键都要有 handler
        val keyAt = src.indexOf("document.addEventListener('keydown'")
        assertTrue("全局 keydown 注册不见了", keyAt >= 0)
        val key = src.substring(keyAt, src.indexOf("});", keyAt))
        for (frag in listOf("'Escape'", "'n'", "'p'", "'k'", "'b'", "['1','2','3']")) {
            assertTrue("keydown 里没有 $frag 的注册——帮助文案承诺的键位落空了", key.contains(frag))
        }
    }

    /** assertTrue(消息, 条件) 的变体：条件里已经带上下文时复用消息文本。 */
    private fun assertTrue2(msg: String, src: String, needle: String): Int {
        val at = src.indexOf(needle)
        assertTrue(msg, at >= 0)
        return at
    }
}
