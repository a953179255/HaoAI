package com.haoai.pc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Token 统计这一页**接上了没有**：读产品源码本身，不读 README 的转述。
 *
 * 为什么需要这一层（而不是只靠 [UsageSliceTest] 与像素剧本）：这一轮的四个改动
 * 每一个都能"逻辑对、线没接"而全绿 ——
 * - 账本记了 `expert`，但引擎那四个落账点少传一个参数 ⇒ 表里永远只有"未挂专家"；
 * - 导出算好了五张表，但 `/api/usage/export` 没进路由表 ⇒ 点按钮只会 404；
 * - 页面模块写完了，但导航行没有 `data-view="tokens"` 或 JS 里 `querySelector('#tkX')`
 *   拼错一个字母 ⇒ 页面上是一片空白，而单测一条都不会红。
 * 这类"参数收了没用 / 元素挂了不存在"的坑，本项目已经真吃过两次
 * （移动端 `PcLinkScreen` 收了 wallpaper 不用、`#111` 发了 SSE 而 DOM 里没有）。
 */
class TokenPageTest {

    private val ui = File("src/main/resources/ui/index.html")
    private val engine = File("src/main/kotlin/com/haoai/pc/Engine.kt")
    private val server = File("src/main/kotlin/com/haoai/pc/Server.kt")
    private val usage = File("src/main/kotlin/com/haoai/pc/UsageLedger.kt")

    private fun read(f: File): String {
        // 读不到就等于这条测试什么都没做 —— 宁可在这里红，别悄悄绿
        assertTrue("找不到 ${f.path}（测试要在 pc/ 目录下跑）", f.isFile)
        return f.readText(Charsets.UTF_8)
    }

    /** Tokens 模块的整段源码：只在这一段里判"引用了什么、画了什么"。 */
    private fun tokensJs(): String {
        val src = read(ui)
        val from = src.indexOf("const Tokens=(()=>")
        assertTrue("界面里没有 Tokens 模块（整页统计没接上）", from > 0)
        val to = src.indexOf("return {open,load,paint,qs,setWin,table};", from)
        assertTrue("Tokens 模块结尾找不到（内部结构变了，这一页的判据要跟着重写）", to > from)
        return src.substring(from, to)
    }

    @Test
    fun `the page is reachable from the nav and the command palette`() {
        val src = read(ui)
        assertTrue("导航栏要有 Token 统计这一行（data-view=tokens）",
            Regex("""data-view="tokens"[^>]*id="tokensBtn"""").containsMatchIn(src) ||
                Regex("""id="tokensBtn"[^>]*data-view="tokens"""").containsMatchIn(src))
        assertTrue("主区要有 #page-tokens 这个容器", src.contains("""class="pagev" id="page-tokens""""))
        assertTrue("换页机制要认识 tokens 这一页（CSS 里没这条就永远 display:none）",
            Regex("""\.main\[data-page="tokens"\]>#page-tokens""").containsMatchIn(src))
        assertTrue("点导航行要把页面交给 Tokens.open()",
            Regex("""v==='tokens'.*Tokens\.open\(\)""").containsMatchIn(src))
        assertTrue("命令面板里也要有入口（键盘党找不到这页）",
            Regex("""'#tokensBtn'""").containsMatchIn(src))
    }

    /**
     * JS 里引用的每一个 `#tkXxx` 都必须真在标记里。
     *
     * 这条是本轮最值钱的一条：`$id('#tkExpert').onchange=…` 拼错一个字母，
     * 浏览器只会在赋值那一行抛一次异常，而**页面看起来还是画出来了**（前半段已经画完），
     * 于是"筛选点了没反应"这种症状要人肉点一遍才会发现。
     */
    @Test
    fun `every element the module touches exists in the markup`() {
        val js = tokensJs()
        val markup = read(ui).substringBefore("const Tokens=(()=>")
        val refs = Regex("""[#]([A-Za-z][\w-]*)""").findAll(js)
            .map { it.groupValues[1] }.filter { it.startsWith("tk") }.toSet()
        assertTrue("一个 tk* 元素都没引用到（判据本身失效了）", refs.size >= 8)
        val missing = refs.filter { !markup.contains("""id="$it"""") }
        assertTrue("这些元素在标记里不存在：" + missing.joinToString(",") +
            "（JS 挂到空气上，页面画一半就静默失败）", missing.isEmpty())
        // 反方向：页面上的控件没有一个是死的
        val dead = listOf("tkWin", "tkFrom", "tkTo", "tkExpert", "tkKind", "tkOne", "tkExport",
            "tkCards", "tkDonuts", "tkBars", "tkTable", "tkRange", "tktabs")
            .filter { !js.contains(it) }
        assertTrue("这些控件在模块里一次都没被引用（摆了个假界面）：" + dead.joinToString(","),
            dead.isEmpty())
    }

    @Test
    fun `the four tabs and the export button are all wired`() {
        val js = tokensJs()
        for (t in listOf("day", "expert", "model"))
            assertTrue("缺「$t」页签的分支（点了不换表）", js.contains("tab==='$t'"))
        assertTrue("页签按钮要真的换表（点下去只改样式不算）",
            js.contains("tktabs") && js.contains("table()"))
        assertTrue("导出按钮要打到 /api/usage/export", js.contains("/api/usage/export"))
        assertTrue("查询串要带 from/to/expert/kind 四个参数",
            listOf("set('from'", "set('to'", "set('expert'", "set('kind'").all { js.contains(it) })
        assertTrue("结束那天要整天算进去（只传 00:00 会少一整个白天）",
            js.contains("dayStart(t)+DAY-1"))
        assertTrue("环图与柱子要有内容（不然只剩两个空壳）",
            js.contains("donutSvg") && js.contains("tkBars"))
    }

    @Test
    fun `the server routes both the report and the download`() {
        val src = read(server)
        assertTrue("/api/usage 要走带区间的 handler，不能写死 report()",
            src.contains("\"/api/usage\" -> usageReport(ex)"))
        assertTrue("缺 /api/usage/export 路由（点导出会 404）",
            src.contains("\"/api/usage/export\" -> usageExportXlsx(ex)"))
        val h = File("src/main/kotlin/com/haoai/pc/ServerUsage.kt")
        assertTrue("ServerUsage.kt 不存在", h.isFile)
        val hs = h.readText(Charsets.UTF_8)
        assertTrue("下载要带 Content-Disposition，否则浏览器只会开一个空白页",
            hs.contains("Content-Disposition") && hs.contains("attachment"))
        assertTrue("字节流不能过 send(String)（zip 里的 0x00 会被编码改坏）",
            Regex("""sendResponseHeaders\(200, bytes\.size""").containsMatchIn(hs))
        assertTrue("中文文件名要按 RFC 5987 再给一份 filename*", hs.contains("filename*=UTF-8''"))
    }

    /** 落账口只留一个：四个调用点各拼一遍参数，迟早有一个忘了带 expert。 */
    @Test
    fun `the engine records expert cached and kind on every turn`() {
        val src = read(engine)
        val direct = Regex("""UsageLedger\.add\(""").findAll(src).toList()
        assertEquals("引擎里不该再有直接 add（都走 ledger( 那一个口）：" +
            direct.joinToString { it.value }, 1, direct.size)
        assertTrue("ledger() 要把 session.role 与 session.preset 带给账本",
            Regex("""ledger\([^)]*\)[^=]*=[\s\S]{0,200}session\.role, session\.preset""").containsMatchIn(src))
        assertTrue("子任务要落成 SUB，不然团队会话的账全算到主持人头上",
            src.contains("if (depth > 0) UsageLedger.SUB else UsageLedger.MAIN"))
        assertTrue("缓存 token 要落账（网关早就回了 cachedTokens，之前一直被丢掉）",
            Regex("""turn\.usage\.cachedTokens""").containsMatchIn(src))
        assertTrue("角色卡起会话时要记下卡 id（不然按专家筛永远只有名字）",
            read(server).contains("session.preset = preset?.id.orEmpty()"))
    }

    @Test
    fun `the ledger keeps its four new dimensions and the default window`() {
        val src = read(usage)
        for (k in listOf("cached", "expert", "preset", "kind"))
            assertTrue("Row 缺 $k 字段", Regex("""val $k:""").containsMatchIn(src))
        assertTrue("默认窗口是近 30 天（与页面默认一致）",
            Regex("""29 \* DAY_MS""").containsMatchIn(src))
        assertTrue("区间要按本地日切齐、且含结束那天",
            Regex("""startOfDay\(to\) \+ DAY_MS""").containsMatchIn(src))
        assertTrue("小数必须按 Locale.ROOT 格式化（跟着系统语言走会产出非法 JSON）",
            Regex("""String\.format\(Locale\.ROOT""").containsMatchIn(src))
    }
}
