package com.haoai.pc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

/**
 * 那个**手搓的 .xlsx 写入器**。
 *
 * 为什么值得单独钉：不引 POI 是刻意的（离线构建 + 只为几个数字拖 8MB 依赖不值），
 * 代价是 OOXML 那份"少写一个部件就打不开"的规矩得自己兜。
 * 判据全部走 JDK 自带的 ZipFile 把字节**读回来**逐部件断言 ——
 * 断"字节数对不对"毫无意义，断"能不能解开、解开的 XML 里有没有那些数"才是导出功能的验收。
 */
class XlsxTest {

    private fun file(bytes: ByteArray): File =
        File.createTempFile("haoai-xlsx", ".xlsx").apply { writeBytes(bytes) }

    private fun part(bytes: ByteArray, name: String): String {
        val z = ZipFile(file(bytes))
        val e = z.getEntry(name) ?: throw AssertionError("导出里没有 $name")
        return z.getInputStream(e).readBytes().toString(Charsets.UTF_8)
    }

    private val sample = listOf(
        Xlsx.Sheet("汇总", listOf(listOf("指标", "值"), listOf("总 TOKENS", 73640))),
        Xlsx.Sheet("按天", listOf(listOf("日期", "总"), listOf("2026-10-03", 120)))
    )

    @Test
    fun `the parts a reader needs are all there`() {
        val names = ZipFile(file(Xlsx.write(sample))).entries().toList().map { it.name }.toSet()
        for (need in listOf("[Content_Types].xml", "_rels/.rels", "xl/workbook.xml",
                "xl/_rels/workbook.xml.rels", "xl/styles.xml",
                "xl/worksheets/sheet1.xml", "xl/worksheets/sheet2.xml")) {
            assertTrue("少了 " + need + " —— Excel 会直接报「文件已损坏」", need in names)
        }
        assertEquals("两个表就要有两个 worksheet 部件", 2, names.count { it.startsWith("xl/worksheets/") })
    }

    /** 表名与关系 id 必须一一对上：错一个 Excel 就把第二张表显示成第一张的内容。 */
    @Test
    fun `workbook lists every sheet and the rels point at the right files`() {
        val bytes = Xlsx.write(sample)
        val wb = part(bytes, "xl/workbook.xml")
        assertTrue(wb.contains("""<sheet name="汇总" sheetId="1" r:id="rId1"/>"""))
        assertTrue(wb.contains("""<sheet name="按天" sheetId="2" r:id="rId2"/>"""))
        val rels = part(bytes, "xl/_rels/workbook.xml.rels")
        assertTrue(rels.contains("""Target="worksheets/sheet2.xml""""))
        assertTrue("样式表也要挂进 rels", rels.contains("""Target="styles.xml""""))
    }

    /** 这条是"导出到底有没有用"的分水岭：数字必须是数字，否则在 Excel 里求不了和。 */
    @Test
    fun `numbers are numbers and text is text`() {
        val s1 = part(Xlsx.write(sample), "xl/worksheets/sheet1.xml")
        assertTrue("数字格不许带 inlineStr", s1.contains("""<c r="B2"><v>73640</v></c>"""))
        assertTrue(s1.contains("总 TOKENS</t></is></c>"))
        assertTrue("表头那行要用加粗样式", s1.contains("""<c r="A1" s="1""""))
        assertTrue("数据行不许带表头样式", !s1.contains("""<c r="A2" s="1""""))
    }

    /** 模型名是一整条 Windows 路径，还可能夹着尖括号与 &：转义漏一处整份文件作废。 */
    @Test
    fun `xml metacharacters in a cell get escaped`() {
        val nasty = "G:" + File.separator + "AI" + File.separator + "<a>&b.gguf"
        val bytes = Xlsx.write(listOf(Xlsx.Sheet("怪", listOf(listOf("模型"), listOf(nasty)))))
        val xml = part(bytes, "xl/worksheets/sheet1.xml")
        assertTrue("反斜杠原样留着", xml.contains("G:" + File.separator + "AI"))
        assertTrue("< 要转义", xml.contains("&lt;a&gt;"))
        assertTrue("& 要转义", xml.contains("&amp;b"))
        assertTrue("裸的 <a> 不该出现在 XML 里", !xml.contains("<a>"))
    }

    /** 表头冻结 + 筛选：明细几千行，没有 autoFilter 的表等于让人手抄。 */
    @Test
    fun `the header row is frozen so a long detail sheet stays readable`() {
        val xml = part(Xlsx.write(sample), "xl/worksheets/sheet2.xml")
        assertTrue("第一行要冻住", xml.contains("""state="frozen""""))
        assertTrue("每张表都要有 auto-filter 区", xml.contains("<autoFilter ref=\"A1:B2\""))
    }

    @Test
    fun `column letters roll past Z and sheet names stay legal`() {
        assertEquals("A", Xlsx.col(0))
        assertEquals("Z", Xlsx.col(25))
        assertEquals("AA", Xlsx.col(26))
        assertEquals("AZ", Xlsx.col(51))
        assertEquals("BA", Xlsx.col(52))
        assertEquals("OOXML 不许的那几个字符要剔掉", "月度报表", Xlsx.safeName("月度/报表*"))
        assertEquals("表名上限 31 个字符", 31, Xlsx.safeName("名".repeat(50)).length)
        val two = listOf(Xlsx.Sheet("同名", listOf(listOf(1))), Xlsx.Sheet("同名", listOf(listOf(2))))
        val wb = part(Xlsx.write(two), "xl/workbook.xml")
        assertEquals("重名要自动错开，否则 Excel 直接拒开", 1, Regex("name=\"同名\\(2\\)\"").findAll(wb).count())
    }

    /** 空表名/空行不能产出非法 XML（导出的是"这一段时间没账"，不是崩）。 */
    @Test
    fun `an empty sheet is still a valid workbook`() {
        val bytes = Xlsx.write(listOf(Xlsx.Sheet("", emptyList())))
        assertTrue(bytes.size > 200)
        assertEquals(1, ZipFile(file(bytes)).entries().toList().count { it.name.startsWith("xl/worksheets/") })
        assertTrue(part(bytes, "xl/worksheets/sheet1.xml").contains("<sheetData></sheetData>"))
    }
}
