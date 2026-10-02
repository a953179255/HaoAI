package com.haoai.pc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 账本的**四个新维度**（专家 / 卡 id / 缓存输入 / 主会话还是子任务）与按区间切片。
 *
 * 为什么单独测而不是往 [UsageLedgerTest] 里加：那份钉的是"按回合落账、按本地日分桶"，
 * 这一份钉的是"能不能按 Octop 那样切"。判据一律读 `report()` 的 JSON，
 * 因为界面与导出读的都是它 —— 内部字段对了而 JSON 少一个键，页面上就是永远"0"。
 */
class UsageSliceTest {

    companion object {
        @BeforeClass
        @JvmStatic
        fun home() {
            val dir = Files.createTempDirectory("haoai-slice-home").toFile()
            System.setProperty("haoai.home", File(dir, "home").absolutePath)
        }
    }

    @Before
    fun clear() {
        UsageLedger.file().delete()
        UsageLedger.file().parentFile?.mkdirs()
    }

    private val day = 86_400_000L
    private fun now() = System.currentTimeMillis()
    private fun json() = Json.parseToJsonElement(UsageLedger.report()).jsonObject

    /** 直接写行：要造"哪一天"的账，只能自己给 t（add() 一律用当前时间）。 */
    private fun row(
        t: Long, model: String = "m1", p: Int = 0, c: Int = 0, cached: Int = 0,
        expert: String = "", preset: String = "", kind: String = "", ms: Long = 0, ok: Boolean = true
    ) {
        val extra = buildString {
            if (cached > 0) append(""","cached":$cached""")
            if (expert.isNotBlank()) append(""","expert":"$expert"""")
            if (preset.isNotBlank()) append(""","preset":"$preset"""")
            if (kind.isNotBlank()) append(""","kind":"$kind"""")
        }
        UsageLedger.file().appendText(
            """{"t":$t,"model":"$model","sid":"s","prompt":$p,"completion":$c,"ms":$ms,"ok":$ok$extra}""" +
                "\n")
    }

    @Test
    fun `the four new columns survive a write and a read`() {
        UsageLedger.add("m1", "s1", 10, 5, 100, true,
            cached = 7, expert = "小通 · 通用助手", preset = "ex1", kind = UsageLedger.SUB)
        val r = UsageLedger.rows().single()
        assertEquals(7, r.cached)
        assertEquals("小通 · 通用助手", r.expert)
        assertEquals("ex1", r.preset)
        assertEquals(UsageLedger.SUB, r.kind)
    }

    /** 没挂专家的**明说**，不许编一个名字：编出来的统计比没统计更害人。 */
    @Test
    fun `a row with no expert is labelled, not guessed`() {
        UsageLedger.add("m1", "s1", 10, 5, 100, true)
        val r = UsageLedger.rows().single()
        assertEquals(UsageLedger.NO_EXPERT, r.expertKey())
        assertEquals(UsageLedger.MAIN, r.kind)
    }

    /** 老格式的一行（只有四个旧键）必须照样读得通 —— 用户机器上已经落了几天账。 */
    @Test
    fun `a line written before this change still parses`() {
        UsageLedger.file().appendText(
            """{"t":${now()},"model":"老模型","sid":"old","prompt":3,"completion":4,"ms":9,"ok":true}""" + "\n")
        val r = UsageLedger.rows().single()
        assertEquals(0, r.cached)
        assertEquals("", r.expert)
        assertEquals("老账本归主会话", UsageLedger.MAIN, r.kind)
        assertEquals(7, json()["summary"]!!.jsonObject["total"]!!.jsonPrimitive.int)
    }

    @Test
    fun `by expert splits the same model between two people`() {
        row(now(), "共用模型", p = 100, c = 10, expert = "甲", preset = "pa")
        row(now(), "共用模型", p = 30, c = 5, expert = "乙", preset = "pb")
        val by = json()["byExpert"]!!.jsonArray
        assertEquals("同一个模型被两个专家用，就该是两行", 2, by.size)
        assertEquals("花得多的排前面", "甲", by[0].jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals(110, by[0].jsonObject["total"]!!.jsonPrimitive.int)
        assertEquals("pa", by[0].jsonObject["preset"]!!.jsonPrimitive.content)
        assertEquals("模型数按人去重", 1, by[0].jsonObject["models"]!!.jsonPrimitive.int)
    }

    @Test
    fun `cached tokens are counted apart from prompt`() {
        row(now(), "m", p = 1000, c = 20, cached = 800)
        val s = json()["summary"]!!.jsonObject
        assertEquals(1000, s["prompt"]!!.jsonPrimitive.int)
        assertEquals("缓存命中是输入的一部分，不是额外的一笔", 800, s["cached"]!!.jsonPrimitive.int)
        assertEquals(1020, s["total"]!!.jsonPrimitive.int)
    }

    @Test
    fun `sub tasks are counted separately from the coordinator`() {
        row(now(), "m", p = 100, c = 10, expert = "团队 · 剪辑组")
        row(now(), "m", p = 700, c = 50, expert = "剪辑", kind = UsageLedger.SUB)
        val e = json()["byExpert"]!!.jsonArray[0].jsonObject
        assertEquals("成员花掉的要能看出来是派工花的", 1, e["sub"]!!.jsonPrimitive.int)
        val only = Json.parseToJsonElement(UsageLedger.report(kind = UsageLedger.SUB))
            .jsonObject["summary"]!!.jsonObject
        assertEquals(1, only["n"]!!.jsonPrimitive.int)
        assertEquals(750, only["total"]!!.jsonPrimitive.int)
    }

    @Test
    fun `the end day is included whole`() {
        val today = UsageLedger.startOfDay(now())
        row(today + 60_000, "m", p = 1, c = 1)                       // 今天 00:01
        row(today + day - 1000, "m", p = 2, c = 2)                   // 今天 23:59:5x
        row(today - 60_000, "m", p = 99, c = 99)                     // 昨天最后一分钟
        val r = Json.parseToJsonElement(
            UsageLedger.report(from = today, to = today)
        ).jsonObject
        assertEquals("选「今天」要含整天，昨天那一笔不能进来", 2, r["summary"]!!.jsonObject["n"]!!.jsonPrimitive.int)
        assertEquals(6, r["summary"]!!.jsonObject["total"]!!.jsonPrimitive.int)
    }

    @Test
    fun `a day without spend still shows up in the range`() {
        val today = UsageLedger.startOfDay(now())
        row(today - 2 * day, "m", p = 5, c = 5)
        val days = Json.parseToJsonElement(
            UsageLedger.report(from = today - 3 * day, to = today)
        ).jsonObject["byDay"]!!.jsonArray
        assertEquals("三天区间要给出四根柱（含空天）", 4, days.size)
        assertEquals("空天要有零值而不是缺项", 0,
            days.first { it.jsonObject["d"]!!.jsonPrimitive.content ==
                UsageLedger.dayOf(today) }.jsonObject["total"]!!.jsonPrimitive.int)
    }

    /**
     * 下拉项的 key 为什么"有卡 id 用 id，没 id 用名字"，这条就是理由：
     * 只记了名字的老行**按 id 是筛不到的**。要是下拉一律给 id，改名之前的那批账
     * 就永远落在筛选结果外面 —— 用户看到的是"这个专家没花过钱"。
     */
    @Test
    fun `the expert filter matches by card id and by display name`() {
        row(now(), "m", p = 10, c = 1, expert = "小通", preset = "ex1")
        row(now(), "m", p = 20, c = 1, expert = "小通", preset = "")      // 老行只有名字
        row(now(), "m", p = 40, c = 1, expert = "别人", preset = "ex2")
        // 最硬的一条：**下拉项的 key 必须能复现表上那一行的数**（同一把钥匙分组同一把钥匙筛）
        val r = json()
        val rows = r["byExpert"]!!.jsonArray.map {
            it.jsonObject["key"]!!.jsonPrimitive.content to it.jsonObject["n"]!!.jsonPrimitive.int
        }
        for ((key, n) in rows) {
            val back = Json.parseToJsonElement(UsageLedger.report(expert = key))
                .jsonObject["summary"]!!.jsonObject["n"]!!.jsonPrimitive.int
            assertEquals("下拉里选「$key」筛出的回合数要和表上那一行一致", n, back)
        }
        val opts = Json.parseToJsonElement(UsageLedger.report())
            .jsonObject["experts"]!!.jsonArray
        assertTrue("下拉项要带得上卡 id 这个字段（drill-down 用）：$opts",
            opts.all { it.jsonObject.containsKey("preset") })
        assertTrue("下拉项的 key 就是分组名：$opts",
            opts.map { it.jsonObject["key"]!!.jsonPrimitive.content }.containsAll(listOf("小通", "别人"))
        )
    }

    /**
     * 一个键只许出现一次。
     *
     * 这条是本轮改造自己踩出来的风险：区间口径与全量口径都要 `okRate`/`tps`，
     * 手搓字符串时很容易在顶层写两遍 —— 而 JSON 允许重复键，**后写的赢**，
     * 于是页面显示的"成功率"永远是全量那个，还看不出哪里错了。
     */
    @Test
    fun `no key is written twice into the report`() {
        row(now(), "m", p = 10, c = 1, expert = "甲", preset = "pa")
        val src = UsageLedger.report()
        // 先把 summary 那一段摘掉：它里面的 okRate/tps 是**区间**口径，与顶层那两个同名不同义
        val top = src.replace(Regex("\"summary\":\\{[^{}]*\\}"), "\"summary\":{}")
        for (k in listOf("summary", "range", "today", "week", "all", "okRate", "tps", "rows",
                "byDay", "byExpert", "byModel", "experts")) {
            val n = Regex("\"$k\":").findAll(top).count()
            assertEquals("顶层键「$k」出现了 $n 遍（重复键＝后写的赢，页面读到哪个全凭解析器心情）", 1, n)
        }
        assertTrue("区间数必须收在 summary 里",
            Regex("\"summary\":\\{[^}]*\"okRate\"").containsMatchIn(src))
    }

    @Test
    fun `the legacy keys keep their whole-ledger meaning`() {
        val today = UsageLedger.startOfDay(now())
        row(today - 40 * day, "m", p = 100, c = 10)          // 40 天前：默认区间之外
        row(today, "m", p = 1, c = 1)
        val r = json()                                        // 不带参数的老调用形状
        assertEquals("默认窗口只含近 30 天", 1, r["summary"]!!.jsonObject["n"]!!.jsonPrimitive.int)
        assertEquals("右栏那个「全部」还是全量，不跟着区间漂", 2, r["all"]!!.jsonObject["n"]!!.jsonPrimitive.int)
        assertEquals(14, r["days"]!!.jsonArray.size)
    }
}
