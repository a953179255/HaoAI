package com.haoai.pc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile

/**
 * Token 统计的**导出**：五张表、数字是真数字、筛选条件要真的落到文件里。
 *
 * 为什么单独测 [UsageExport] 而不只看 [XlsxTest]：写入器只管"能不能打开"，
 * 这一层管的是"打开之后那些数对不对"。两边最容易各说各话的地方是**口径**：
 * 页面上选了某个专家、导出却给了全部 —— 判据必须把筛选条件一路带到单元格里。
 */
class UsageExportTest {

    companion object {
        @BeforeClass
        @JvmStatic
        fun home() {
            val dir = Files.createTempDirectory("haoai-export-home").toFile()
            System.setProperty("haoai.home", File(dir, "home").absolutePath)
        }
    }

    @Before
    fun clear() {
        UsageLedger.file().delete()
        UsageLedger.file().parentFile?.mkdirs()
    }

    private val day = 86_400_000L
    private fun row(
        t: Long, model: String = "m1", p: Int = 0, c: Int = 0, cached: Int = 0,
        expert: String = "", preset: String = "", kind: String = ""
    ) {
        val extra = buildString {
            if (cached > 0) append(""","cached":$cached""")
            if (expert.isNotBlank()) append(""","expert":"$expert"""")
            if (preset.isNotBlank()) append(""","preset":"$preset"""")
            if (kind.isNotBlank()) append(""","kind":"$kind"""")
        }
        UsageLedger.file().appendText(
            """{"t":$t,"model":"${model.replace("\\", "\\\\").replace("\"", "\\\"")}","sid":"s",""" +
                """"prompt":$p,"completion":$c,"ms":9,"ok":true$extra}""" + "\n")
    }

    private fun byName(what: String): List<List<Any?>> =
        UsageExport.sheets(0, 0, "", "").first { it.name == what }.rows

    @Test
    fun `the workbook has the five sheets we promise`() {
        row(System.currentTimeMillis(), p = 10, c = 2, expert = "甲", preset = "pa")
        val names = UsageExport.sheets(0, 0, "", "").map { it.name }
        assertEquals(listOf("汇总", "按天", "按专家", "按模型", "明细"), names)
    }

    /** 最值钱的一条：聚合表的合计必须等于汇总那格，否则两份表互相对不上。 */
    @Test
    fun `every slice adds up to the same total`() {
        val now = System.currentTimeMillis()
        row(now, "A", p = 100, c = 10, cached = 40, expert = "甲", preset = "pa")
        row(now - day, "B", p = 30, c = 5, expert = "乙", preset = "pb")
        row(now - day, "A", p = 7, c = 1, expert = "甲", preset = "pa", kind = "sub")
        val sum = byName("汇总").first { it[0] == "总 TOKENS" }!![1] as Int
        assertEquals(153, sum)
        val dayTotal = byName("按天").drop(1).sumOf { (it[1] as Int) }
        val exTotal = byName("按专家").drop(1).sumOf { (it[2] as Int) }
        val moTotal = byName("按模型").drop(1).sumOf { (it[1] as Int) }
        val deTotal = byName("明细").drop(1).sumOf { (it[7] as Int) + (it[8] as Int) }
        assertEquals("按天合计要等于汇总", sum, dayTotal)
        assertEquals("按专家合计要等于汇总", sum, exTotal)
        assertEquals("按模型合计要等于汇总", sum, moTotal)
        assertEquals("明细逐行加起来也要等于汇总", sum, deTotal)
    }

    @Test
    fun `the expert column carries the card id next to the display name`() {
        row(System.currentTimeMillis(), p = 5, c = 1, expert = "小通 · 通用助手", preset = "ex7")
        val r = byName("按专家")[1]
        assertEquals("小通 · 通用助手", r[0])
        assertEquals("ex7", r[1])
        assertEquals(6, r[2])
    }

    /** 筛选要一路走到文件里：页面选了谁，导出的明细就只能有谁。 */
    @Test
    fun `filters travel from the query into the workbook`() {
        val now = System.currentTimeMillis()
        row(now, p = 10, c = 1, expert = "甲", preset = "pa")
        row(now, p = 99, c = 9, expert = "乙", preset = "pb")
        val sheets = UsageExport.sheets(0, 0, "pa", "")
        val ex = sheets.first { it.name == "按专家" }.rows
        assertEquals("筛了 pa 就不该看见乙", 2, ex.size)
        assertEquals("甲", ex[1][0])
        val detail = sheets.first { it.name == "明细" }.rows
        assertEquals(2, detail.size)
        val who = sheets.first { it.name == "汇总" }.rows.first { it[0] == "专家筛选" }
        assertEquals("汇总里要写明这份是按谁筛的（不然三份文件分不清）", "pa", who[1])
    }

    @Test
    fun `sub tasks get their own column and their own filter`() {
        val now = System.currentTimeMillis()
        row(now, p = 10, c = 1, expert = "团队 · 剪")
        row(now, p = 70, c = 5, expert = "剪辑", kind = "sub")
        val ex = byName("按专家")
        val host = ex.first { it[0] == "团队 · 剪" }
        assertEquals("主持人这一行要能看出没有派工", 0, host[7])
        val only = UsageExport.sheets(0, 0, "", "sub").first { it.name == "明细" }.rows
        assertEquals(2, only.size)
        assertEquals("子任务", only[1][6])
    }

    /** 空天也要有一行：只看"有账的日子"会让人以为那几天机器开着但没干活。 */
    @Test
    fun `the day sheet covers every day in the range`() {
        val today = UsageLedger.startOfDay(System.currentTimeMillis())
        row(today - 2 * day, p = 1, c = 1)
        val days = UsageExport.sheets(today - 3 * day, today, "", "")
            .first { it.name == "按天" }.rows
        assertEquals("三天区间 = 四行（含表头）", 5, days.size)
        assertEquals(0, days.last()[1])
    }

    @Test
    fun `detail is capped but the aggregates are not`() {
        val now = System.currentTimeMillis()
        val body = StringBuilder()
        repeat(UsageExport.MAX_DETAIL + 5) {
            body.append("""{"t":$now,"model":"m","sid":"s","prompt":1,"completion":1,"ms":1,"ok":true}""" + "\n")
        }
        UsageLedger.file().writeText(body.toString())
        val sheets = UsageExport.sheets(0, 0, "", "")
        assertEquals("明细只留上限那么多行 + 表头",
            UsageExport.MAX_DETAIL + 1, sheets.first { it.name == "明细" }.rows.size)
        assertEquals("聚合不受截断影响：总数还是全量（按天最后一行是今天）",
            (UsageExport.MAX_DETAIL + 5) * 2,
            sheets.first { it.name == "按天" }.rows.last()[1])
        assertTrue("截断了要在汇总里说一声",
            sheets.first { it.name == "汇总" }.rows.any { it[0] == "明细是否截断" && it[1] == "是（只留最近 ${UsageExport.MAX_DETAIL} 笔）" })
    }

    @Test
    fun `the bytes really unzip and the detail sheet holds the rows`() {
        row(System.currentTimeMillis(), "G:\\AI\\x.gguf", p = 3, c = 4, expert = "甲", preset = "pa")
        val bytes = Xlsx.write(UsageExport.sheets(0, 0, "", ""))
        val f = File.createTempFile("haoai-export", ".xlsx").apply { writeBytes(bytes) }
        val z = ZipFile(f)
        val detail = z.getInputStream(z.getEntry("xl/worksheets/sheet5.xml")!!)
            .readBytes().toString(Charsets.UTF_8)
        assertTrue("中文专家名要原样进表", detail.contains("甲</t>"))
        assertTrue("数字格不能带 inlineStr", detail.contains("<v>3</v>"))
        assertTrue("反斜杠路径不能把 XML 弄坏", detail.contains("G:\\AI\\x.gguf"))
    }

    @Test
    fun `the file name carries the range and stays header safe`() {
        val n = UsageExport.filename(0, 0, "")
        assertTrue("文件名要带 haoai-tokens 与日期：$n", Regex("""^haoai-tokens-\d{4}-\d\d-\d\d~\d{4}-\d\d-\d\d\.xlsx$""").matches(n))
        val nasty = UsageExport.filename(0, 0, "甲\"引号\n换行")
        assertTrue("HTTP 头里不许出现引号与换行：$nasty", !nasty.contains('"') && !nasty.contains('\n'))
        assertTrue(nasty.endsWith(".xlsx"))
    }
}
