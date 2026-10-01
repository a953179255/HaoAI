package com.haoai.pc

import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 审批卡上的逐块接受 / 拒绝。
 *
 * 原来一次 `write` 改了三处，人只能整条批或整条拒 —— 两条都是亏的：忍着放行就得回头自己把那一处改回来，
 * 全拒则连对的两处也要让模型重跑一遍。这一批把改动切成块，勾掉的那块保持原样。
 *
 * 三件事是这套东西的安全底线，每条都钉着：
 * ① **全勾 == 整条放行**（写进去的内容与模型给的逐字节相同，连结尾换行都不差）；
 * ② 退掉的块**一个字节都不写**，其余块的位置不因为退了一块而漂移；
 * ③ 卡片挂着等人看的几分钟里文件被改过，就**整条不写**并说清为什么 ——
 *    按旧基线合块等于盖掉别人的改动，而那类写坏的现场是回不来的。
 */
class HunkTest {

    /**
     * 参数直接用 JsonObject 构造，不拼 JSON 字符串。
     *
     * 上一批刚栽过：手搓的字符串多一个括号，产品坏而测试全绿。这里的 content 里全是换行与中文，
     * 再拿字符串拼一遍就等于给下一个同类缺陷留位置。
     */
    private fun writeArgs(path: String, content: String) =
        buildJsonObject { put("path", path); put("content", content) }

    private fun editArgs(path: String, old: String, new: String, all: Boolean = false) =
        buildJsonObject {
            put("path", path); put("old_string", old); put("new_string", new); put("all", all)
        }

    /** 20 行的基线文件，行号写在内容里 —— 断言时一眼能看出「第几行现在是什么」。 */
    private fun base(n: Int = 20) = (1..n).joinToString("\n", "", "\n") { "L%02d".format(it) }

    /**
     * 把第 `rows`（1 基）几行换掉，其余原样。
     *
     * 这里刻意**不用** `String.lines()`：它把结尾那个换行拆成一个空尾项，
     * 再 joinToString 回去就凭空多出一行空行 —— 夹具自己错的时候，产品对了也测不出来
     * （本批就是先被这个假差异测出"2 块 / 14 块"，回头才发现是夹具在改文件尾巴）。
     */
    private fun rowsOf(s: String): List<String> =
        if (s.isEmpty()) emptyList() else s.split("\n").let { if (it.last() == "") it.dropLast(1) else it }

    private fun edited(from: String, vararg rows: Pair<Int, String>): String {
        val l = rowsOf(from).toMutableList()
        rows.forEach { (no, to) -> l[no - 1] = to }
        return l.joinToString("\n", "", "\n")
    }

    @Test
    fun `three separate edits become three hunks`() {
        val b = base()
        val w = edited(b, 2 to "X02", 9 to "X09", 16 to "X16")
        val hs = Hunks.of(b, w)
        assertEquals("三处相隔够远就该切成三块：" + hs.map { it.oldAt }, 3, hs.size)
        assertEquals(listOf(1, 8, 15), hs.map { it.oldAt })
        assertEquals(listOf("X02", "X09", "X16"), hs.map { it.added.first() })
        assertEquals("块号从 1 起，界面上那句「第几处」靠它", listOf(1, 2, 3), hs.map { it.no })
        assertEquals("第 2 行之前只有 1 行可当上文", listOf("L01"), hs[0].before)
        assertEquals("下文带满 3 行", listOf("L03", "L04", "L05"), hs[0].after)
        assertTrue("卡片上第一行得说清是哪一块：" + hs[0].text(), hs[0].text().startsWith("@@ 第 2 行起 @@"))
    }

    @Test
    fun `changes close together merge into one block`() {
        val b = base()
        // 第 2 行与第 4 行之间只隔一行没动的：人的眼睛看的是「这一段」，不是两处
        val w = edited(b, 2 to "X02", 4 to "X04")
        val hs = Hunks.of(b, w)
        assertEquals("间隔小于一份上下文的改动要并块：" + hs.size, 1, hs.size)
        assertNull("只有一块时不摆逐块勾选（这是 plan 的门槛，不是切块的能力）", Hunks.plan(b, w))
        assertEquals("并块之后 added 里要把中间那行原样带上，否则合回去会丢行", "L03", hs[0].added[1])
    }

    @Test
    fun `accepting every block writes exactly what the model asked for`() {
        val b = base()
        val w = edited(b, 2 to "X02", 9 to "X09", 16 to "X16")
        val p = Hunks.plan(b, w)
        assertNotNull("三块该给逐块：" + Hunks.of(b, w).size, p)
        p!!.keep = listOf(true, true, true)
        assertEquals("全勾必须与整条放行逐字节相同", w, p.content())
        assertFalse("全勾不算「部分接受」，别在模型面前演一场没发生过的挑选", p.partial)
    }

    @Test
    fun `accepting every block follows the model on the trailing newline`() {
        // 旧文件结尾有换行、模型给的那份没有 —— 这是逐块合并最容易悄悄改错的一位，
        // 所以全勾时根本不走 merge，直接把 wanted 交出去。
        val b = base()
        val w = edited(b, 2 to "X02", 9 to "X09").trimEnd('\n')
        val p = Hunks.plan(b, w) ?: error("这条该切得出两块：" + Hunks.of(b, w).size)
        p.keep = listOf(true, true)
        assertEquals(w, p.content())
        p.keep = listOf(true, false)
        assertTrue("部分接受时尾部没动的行跟着旧文件的习惯：" + p.content().takeLast(8),
            p.content().endsWith("\n"))
    }

    @Test
    fun `a rejected block keeps its original lines and the others do not move`() {
        val b = base()
        val w = edited(b, 2 to "X02", 9 to "X09", 16 to "X16")
        val p = Hunks.plan(b, w)!!
        p.keep = listOf(true, false, true)
        val out = p.content()
        // 整串比一遍最狠：行数漂一位、上下文被当成内容写进去、行尾丢掉，都会在这里红
        assertEquals("只该动被接受的那两块，其余连行尾一起保持原样：" + out,
            edited(b, 2 to "X02", 16 to "X16"), out)
        assertEquals("退一块不该让行数跟着漂", Hunks.rows(b), Hunks.rows(out))
        assertTrue("结尾换行要保住", out.endsWith("\n"))
        assertTrue("部分接受要说得出来（模型据此才知道世界不是它想的那个）", p.partial)
        assertTrue("被退的是第 2 块：" + p.outcome("notes.md", out), p.outcome("notes.md", out).contains("第 2 块"))
    }

    @Test
    fun `a blank line added at the end is a change of its own`() {
        // 上面那条夹具注释说的就是这件事：结尾换行的**有无**是内容差异，不是表示层的自由。
        // 产品要把它认成一处改动（并在"只有一块"时按整条审批），合回去也必须逐字节还原。
        val b = base()
        val w = b + "\n"
        val hs = Hunks.of(b, w)
        assertEquals("尾巴上多一行空行也得成一块：" + hs.map { it.oldAt }, 1, hs.size)
        assertEquals(20, hs[0].oldAt)
        assertNull("只有一块，不摆勾选框", Hunks.plan(b, w))
        assertEquals(w, Hunks.merge(b, hs, listOf(true), w))
        assertEquals(b, Hunks.merge(b, hs, listOf(false), w))
    }

    @Test
    fun `a whole new file has nothing to pick`() {
        assertNull("新建文件就是一整块，勾选框只会让人多点一下", Hunks.plan("", base(6)))
        assertEquals(1, Hunks.of("", base(6)).size)
    }

    @Test
    fun `too many blocks falls back to one whole approval`() {
        val b = base(120)
        val rows = (0 until 13).map { (it * 9 + 1) to "N%02d".format(it) }
        val w = edited(b, *rows.toTypedArray())
        assertEquals("先确认确实切出了 13 块", 13, Hunks.of(b, w).size)
        assertNull("块多到一屏放不下时逐块反而更难读，退回整条审批", Hunks.plan(b, w))
    }

    @Test
    fun `a huge diff costs one block instead of seconds`() {
        // 中段 1100×1100 > 1e6 格：动态规划要跑得起，也要让人等得起 —— 超预算就退回整段一块
        val a = (1..1100).joinToString("\n", "", "\n") { "old %04d".format(it) }
        val c = (1..1100).joinToString("\n", "", "\n") { "new %04d".format(it) }
        val t0 = System.currentTimeMillis()
        val hs = Hunks.of(a, c)
        val ms = System.currentTimeMillis() - t0
        assertTrue("切块该是毫秒级，不是「人盯着卡片等十几秒」：$ms ms", ms < 8_000)
        assertEquals("超预算时退成一整块：" + hs.size, 1, hs.size)
        assertNull(Hunks.plan(a, c))
    }

    @Test
    fun `carriage returns survive a partial write`() {
        val b = (1..20).joinToString("\r\n", "", "\r\n") { "L%02d".format(it) }
        val w = b.replace("L02", "X02").replace("L09", "X09").replace("L16", "X16")
        val p = Hunks.plan(b, w)
        assertNotNull("CRLF 文件也该切成三块：" + Hunks.of(b, w).size, p)
        p!!.keep = listOf(true, false, true)
        val out = p.content()
        assertTrue("行尾的 CR 不能被合并弄丢：" + out.take(40).replace("\r", "<CR>"), out.contains("X02\r\n"))
        assertTrue(out.contains("L09\r\n"))
        assertTrue("结尾仍然是 CRLF", out.endsWith("\r\n"))
        p.keep = listOf(true, true, true)
        assertEquals("全勾仍然逐字节等于模型给的那一份", w, p.content())
    }

    // ---- 工具那一侧 ----

    /** 会往 plan 里写勾选的闸口，模仿网页壳。 */
    private inner class BlockGate(var keep: List<Boolean>? = null) : Gate {
        val asks = mutableListOf<String>()
        var sawPlan = 0
        var whileWaiting: () -> Unit = {}
        override fun approve(title: String, detail: String, kind: String) = true
        override fun ask(question: String, options: List<String>) = ""
        override fun approveRule(
            title: String, detail: String, kind: String, tool: String, pattern: String,
            risk: RiskOf.Verdict?, plan: HunkPlan?
        ): Boolean {
            asks += title + "|" + detail
            if (plan != null) { sawPlan++; plan.keep = keep }
            whileWaiting()
            return true
        }
    }

    /** 只会答 true/false 的老式闸口（CLI 与其余测试假闸口都是这个样子）。 */
    private class PlainGate : Gate {
        var n = 0
        override fun approve(title: String, detail: String, kind: String): Boolean { n++; return true }
        override fun ask(question: String, options: List<String>) = ""
    }

    private fun ws(): File = Files.createTempDirectory("haoai-hunk-ws").toFile().apply { mkdirs() }

    private fun ctx(w: File, mode: String, gate: Gate) =
        ToolCtx(w, PcSettings(permissionMode = mode), mode, gate)

    @Test
    fun `write puts only the accepted blocks on disk`() {
        val w = ws()
        val f = File(w, "notes.md")
        val b = base()
        f.writeText(b)
        val wanted = edited(b, 2 to "X02", 9 to "X09", 16 to "X16")
        val gate = BlockGate(keep = listOf(true, false, true))
        val r = WriteTool().runB(writeArgs("notes.md", wanted), ctx(w, "ask", gate))
        assertEquals("该弹一张带勾选的卡：" + gate.sawPlan, 1, gate.sawPlan)
        assertTrue("卡上要说清分了几处：" + gate.asks, gate.asks[0].contains("分成 3 处"))
        assertEquals("被退的那块保持原样，其余两块落盘",
            edited(b, 2 to "X02", 16 to "X16"), f.readText())
        assertTrue("模型必须看见「部分接受」，否则它会以为整条都过了：" + r.content,
            r.content.contains("部分写入") && r.content.contains("退了 1 块"))
        assertTrue("要指出是第几块，并教它下一步：" + r.content,
            r.content.contains("第 2 块") && r.content.contains("先 read"))
    }

    @Test
    fun `a gate that cannot pick blocks writes the whole thing`() {
        val w = ws()
        val b = base()
        File(w, "notes.md").writeText(b)
        val wanted = edited(b, 2 to "X02", 9 to "X09", 16 to "X16")
        val gate = PlainGate()
        val r = WriteTool().runB(writeArgs("notes.md", wanted), ctx(w, "ask", gate))
        assertEquals("CLI 那类闸口只答 true/false，就该整条写下去", wanted, File(w, "notes.md").readText())
        assertFalse("没人挑过就不许说「部分写入」：" + r.content, r.content.contains("部分写入"))
        assertEquals("仍然只弹一次卡：" + gate.n, 1, gate.n)
    }

    @Test
    fun `a file changed while the card was open is not written at all`() {
        val w = ws()
        val f = File(w, "notes.md")
        val b = base()
        f.writeText(b)
        val wanted = edited(b, 2 to "X02", 9 to "X09", 16 to "X16")
        val gate = BlockGate(keep = listOf(true, false, true))
        // 人在读卡片的那几分钟里，别的窗口把这一行改了 —— 逐块合并的基线已经不成立
        gate.whileWaiting = { f.writeText(b.replace("L09", "别人刚改的")) }
        val r = WriteTool().runB(writeArgs("notes.md", wanted), ctx(w, "ask", gate))
        assertTrue("基线变了要拒绝写入并说清原因：" + r.content, r.error && r.content.contains("文件内容变了"))
        val l = f.readText().lines()
        assertEquals("一个字都不许多写（别人的改动不能被盖掉）", "别人刚改的", l[8])
        assertEquals("也不该把挑中的那两块偷偷写进去", "L02", l[1])
        assertTrue("得教模型下一步做什么：" + r.content, r.content.contains("重新 read"))
    }

    @Test
    fun `edit with all=true offers one block per occurrence`() {
        val w = ws()
        val f = File(w, "log.txt")
        f.writeText(base().replace("L02", "DUP").replace("L16", "DUP"))
        val gate = BlockGate(keep = listOf(true, false))
        val r = EditTool().runB(editArgs("log.txt", "DUP", "OK", all = true), ctx(w, "ask", gate))
        assertEquals("一次调用改两处就该给两块：" + gate.sawPlan, 1, gate.sawPlan)
        assertTrue("卡上该说分成 2 处：" + gate.asks, gate.asks[0].contains("分成 2 处"))
        val l = f.readText().lines()
        assertEquals("第一处接受", "OK", l[1])
        assertEquals("第二处被退，保持原样", "DUP", l[15])
        assertTrue("edit 也要说得清是部分接受：" + r.content, r.content.contains("部分写入"))
    }

    @Test
    fun `the match check happens before asking, so a doomed edit costs no card`() {
        val w = ws()
        File(w, "a.txt").writeText("第一行\n")
        val gate = PlainGate()
        val r = EditTool().runB(editArgs("a.txt", "根本没这一句", "x"), ctx(w, "ask", gate))
        assertTrue("该直接说不匹配：" + r.content, r.content.contains("没找到"))
        assertEquals("匹配都不过就不该弹卡等人：" + gate.n, 0, gate.n)
    }

    @Test
    fun `other tools are untouched by this`() {
        val w = ws()
        File(w, "a.txt").writeText("one\n")
        val gate = PlainGate()
        // 逐块只属于 write/edit 这两个「按模型给的路径落文件」的动作；
        // shell 之类走的是 exec，plan 永远是 null，闸口一行都不用改。
        val msg = ctx(w, "ask", gate).guard("shell", "echo hi", "跑一条命令", { "echo hi" }, subjectIsPath = false)
        assertNull(msg)
        assertEquals(1, gate.n)
    }

    // ---- 协议那一侧 ----

    @Test
    fun `the diff card counts only the lines that really changed`() {
        val b = base()
        val w = edited(b, 2 to "X02", 16 to "X16")
        val d = Diff.unified("notes.md", b, w)
        assertTrue("两处各一行，统计就该是 −2 / +2（原来报 −15 / +15，中间 13 行根本没动）：" +
            d.lineSequence().first(), d.startsWith("−2 行 / +2 行"))
        assertTrue("要写明这是几处：" + d.lineSequence().first(), d.contains("（2 处）"))
        assertEquals("两块各带自己的 @@ 头", 2, Regex("@@ 第 \\d+ 行起 @@").findAll(d).count())
        assertFalse("没动的行不许算成删除：" + d, d.contains("-L09"))
        assertTrue("上下文还是要给：" + d, d.contains(" L01"))
    }

    @Test
    fun `a single block keeps the old one-blob rendering`() {
        // 只有一处改动时不摆 @@：那正是 `Diff.unified` 本来的样子，别为了统一把老形状改掉
        val d = Diff.unified("a.txt", "one\ntwo\n", "one\nTWO\n")
        assertTrue("统计行照旧：" + d.lineSequence().first(), d.startsWith("−1 行 / +1 行"))
        assertFalse("一块时不带 @@：" + d, d.contains("@@"))
    }

    @Test
    fun `the approval payload with hunks parses as JSON`() {
        val b = base()
        val plan = Hunks.plan(b, edited(b, 2 to "X02", 9 to "X09", 16 to "X16"))!!
        val json = approvalPayload("a1", "写入 notes.md", "详情\n第二行", "write", "write", "notes.md", null, plan)
        val o = runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull()
        assertNotNull("审批 payload 解析不过，手机待批页就整页空白：" + json.take(240), o)
        assertEquals("三块：" + o!!["hunks"]?.jsonArray?.size, 3, o["hunks"]?.jsonArray?.size)
        val first = o["hunks"]!!.jsonArray[0].jsonObject
        assertEquals("块号", "1", first["no"]?.jsonPrimitive?.contentOrNull)
        assertEquals("给人看的行号是 1 基", "2", first["at"]?.jsonPrimitive?.contentOrNull)
        assertTrue("正文带 −/+ 行：" + first["text"], first["text"]!!.jsonPrimitive.content.contains("-L02"))
        assertTrue("第一行是块头：" + first["text"], first["text"]!!.jsonPrimitive.content.startsWith("@@"))
        assertEquals("detail 里的换行不能被截断", "详情\n第二行", o["detail"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a single block approval carries no hunk list`() {
        val json = approvalPayload("a2", "写入 new.md", "新建", "write", "write", "new.md", null, null)
        assertNull("没有 plan 就不该有 hunks 字段", Json.parseToJsonElement(json).jsonObject["hunks"])
    }

    @Test
    fun `the phone gets the count but not the diff bodies`() {
        val b = base()
        val plan = Hunks.plan(b, edited(b, 2 to "X02", 9 to "X09", 16 to "X16"))!!
        val payload = approvalPayload("a3", "写入 notes.md", "详情", "write", "write", "notes.md", null, plan)
        val row = lanPendingRow("a3", "approval", "s1", payload)
        assertNotNull(row)
        val pl = Json.parseToJsonElement(row!!).jsonObject["payload"]?.jsonObject
        assertEquals("手机要知道自己在替几处改动点头：" + pl, "3", pl?.get("hunkCount")?.jsonPrimitive?.contentOrNull)
        assertNull("几十行 diff 不该进 4 秒一次的轮询：" + payload.length, pl?.get("hunks"))
        assertEquals("标题照旧透传", "写入 notes.md", pl?.get("title")?.jsonPrimitive?.content)
    }

    @Test
    fun `both ends of the wire use the same partial prefix`() {
        // 网页壳发的字符串与服务端解的前缀是同一个约定的两侧：任何一侧改名都会让另一侧
        // 把人勾的结果当成整条放行 —— 那正是「用户明确退掉的块被写进文件」，而界面上不留痕。
        val ui = File("src/main/resources/ui/index.html")
        assertTrue("测试得跑在 pc/ 目录下才读得到网页壳：" + ui.absolutePath, ui.isFile)
        val js = ui.readText()
        assertTrue("界面发的必须是 partial:<位串>：", js.contains("'partial:'+bits()"))
        assertEquals("服务端解的前缀必须一致", "partial:", HUNK_PREFIX)
        val phone = File("src/main/resources/ui/phone.html")
        assertTrue("手机页读的是 payload 里那个数量字段：$HUNK_PREFIX", phone.readText().contains("hunkCount"))
    }

    @Test
    fun `hunkKeep parses what the card sends`() {
        val b = base()
        val plan = Hunks.plan(b, edited(b, 2 to "X02", 9 to "X09", 16 to "X16"))!!
        assertEquals(listOf(true, false, true), hunkKeep("partial:101", plan))
        assertNull("整条放行不带勾选信息", hunkKeep("allow_once", plan))
        assertNull("没有 plan 的卡不认这个前缀", hunkKeep("partial:101", null))
        assertEquals("位数对不上宁可一块都不写：" + hunkKeep("partial:1", plan),
            listOf(false, false, false), hunkKeep("partial:1", plan))
        assertEquals(listOf(false, false, false), hunkKeep("partial:2x1", plan))
    }

    @Test
    fun `none checked means nothing is written and it says so`() {
        val b = base()
        val plan = Hunks.plan(b, edited(b, 2 to "X02", 9 to "X09", 16 to "X16"))!!
        val w = ws()
        val f = File(w, "notes.md")
        f.writeText(b)
        // 界面上这块会把「允许一次」置灰，但手搓一条请求也得站得住：全退回 = 一个字都不写
        plan.keep = hunkKeep("partial:000", plan)
        val out = plan.content()
        assertEquals("全退回时内容就是原样", b, out)
        assertTrue("而且这算部分接受，得说清：" + plan.outcome("notes.md", out), plan.partial)
        assertEquals(b, f.readText())
    }

    @Test
    fun `the note on the phone tells a partial answer apart from a whole one`() {
        assertTrue(decideNote(false, "partial:101").contains("部分"))
        assertEquals("已按你的决定放行：允许一次", decideNote(false, "allow_once"))
        // 点拒绝却回一句"已按你的决定放行：deny"是两处错：动词反了，还把内部码露给人看。
        // 这句是手机上点完按钮唯一的回执，它必须说人话。
        assertFalse("拒绝不许说成放行：" + decideNote(false, "deny"),
            decideNote(false, "deny").contains("放行"))
        assertTrue(decideNote(false, "deny").contains("不会执行"))
        assertEquals("已把回答送回电脑：绿色", decideNote(true, "绿色"))
    }
}
