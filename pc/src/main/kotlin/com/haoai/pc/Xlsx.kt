package com.haoai.pc

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 一个**自带的最小 .xlsx 写入器** —— 不引第三方库。
 *
 * 为什么不引 POI：`pc/` 是离线构建（Gradle 一直带 `--offline` 跑，工程红线），
 * 而 xlsx 本质上就是**一个 zip 里装几份 XML**。五份部件写全了，Excel / WPS / LibreOffice
 * 都认；为了几个数字表拖 8MB 依赖进来，代价与收益不成比例。
 *
 * 刻意只用最保守的那一套 OOXML 形状：
 * - 字符串走 `inlineStr`（不建 `sharedStrings.xml`）：省一份部件，也省掉"共享表索引算错"
 *   这一整类只在导出时才炸的 bug。
 * - 数字走 `<v>` 不带 `t`：这样在 Excel 里是**真数字**，能求和、能做图。
 *   导出的意义就是拿进表格里再算一遍，全列文本的"Excel"等于没导。
 * - 样式只两份（正文 / 加粗表头）：`styles.xml` 是 OOXML 里最容易写错的一份，越少越好。
 *
 * 测试怎么验它（[XlsxTest]）：用 JDK 自带的 `ZipFile` 把字节读回来逐部件断言 ——
 * 判据落在"能不能解开、解开的 XML 里有没有那些数"，不是"字节数对不对"。
 */
object Xlsx {

    /** 一张表：名字 + 若干行（第一行按表头加粗）。`null` = 这一格留空。 */
    data class Sheet(val name: String, val rows: List<List<Any?>>)

    /** OOXML 不许出现在表名里的字符，还有 31 个字符的长度上限。 */
    internal fun safeName(raw: String): String {
        val cleaned = raw.filterNot { it in "[]:*?/\\" }.trim().ifBlank { "Sheet" }
        return cleaned.take(31)
    }

    /** 第 n 列的字母：0→A，25→Z，26→AA。 */
    internal fun col(n: Int): String {
        var i = n
        val sb = StringBuilder()
        while (true) {
            sb.insert(0, ('A' + i % 26))
            i = i / 26 - 1
            if (i < 0) break
        }
        return sb.toString()
    }

    /**
     * XML 1.0 里**根本不许出现**的控制字符（除了 tab/换行/回车）：模型名、报错文本里
     * 都可能夹一个，写进去就是一份打不开的 .xlsx。所以转义之前先滤一遍。
     */
    internal fun xml(s: String): String = buildString {
        for (c in s) {
            when {
                c == '&' -> append("&amp;")
                c == '<' -> append("&lt;")
                c == '>' -> append("&gt;")
                c == '"' -> append("&quot;")
                c == '\t' || c == '\n' || c == '\r' -> append(c)
                c < ' ' -> { /* 丢掉：控制字符写进 XML 会让整个文件无效 */ }
                else -> append(c)
            }
        }
    }

    private fun cell(ref: String, v: Any?, bold: Boolean): String {
        val s = if (bold) """ s="1"""" else ""
        return when (v) {
            null -> ""
            is Int -> """<c r="$ref"$s><v>$v</v></c>"""
            is Long -> """<c r="$ref"$s><v>$v</v></c>"""
            is Double -> """<c r="$ref"$s><v>${v.toString()}</v></c>"""
            is Boolean -> """<c r="$ref"$s><v>${if (v) 1 else 0}</v></c>"""
            else -> """<c r="$ref"$s t="inlineStr"><is><t xml:space="preserve">${xml(v.toString())}</t></is></c>"""
        }
    }

    private fun sheetXml(sh: Sheet): String {
        val body = sh.rows.mapIndexed { ri, row ->
            val cells = row.mapIndexedNotNull { ci, v -> cell("${col(ci)}${ri + 1}", v, ri == 0) }
                .joinToString("")
            // 表头那行用样式 1（加粗），并给一个行高
            val p = if (ri == 0) """ ht="20" customHeight="1"""" else ""
            """<row r="${ri + 1}"$p>$cells</row>"""
        }.joinToString("")
        /*
         * `autoFilter`：明细动辄几千行，没有筛选区的表等于让人手抄。
         * 参照的是 Octop 那份导出的形状（表头加粗 + 冻结 + 自动筛选）。
         */
        val lastRow = sh.rows.size
        val lastCol = if (sh.rows.isEmpty()) 0 else (sh.rows.maxOf { it.size } - 1).coerceAtLeast(0)
        val filter = if (lastRow >= 1 && lastCol >= 0)
            """<autoFilter ref="A1:${col(lastCol)}$lastRow"/>""" else ""
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            """<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""" +
            """<sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" """ +
            """activePane="bottomLeft" state="frozen"/></sheetView></sheetViews>""" +
            "<sheetFormatPr defaultRowHeight=\"15\"/>" +
            cols(sh) +
            "<sheetData>$body</sheetData>$filter</worksheet>"
    }

    /** 第一列留宽一点：里面装的是"小通 · 通用助手"这种中文名和 gguf 的整条路径。 */
    private fun cols(sh: Sheet): String {
        val n = sh.rows.maxOfOrNull { it.size } ?: 0
        if (n == 0) return ""
        val w = (1..n).joinToString("") { i ->
            """<col min="$i" max="$i" width="${if (i == 1) 26.0 else 14.0}" customWidth="1"/>"""
        }
        return "<cols>$w</cols>"
    }

    private const val NS_REL = "http://schemas.openxmlformats.org/package/2006/relationships"
    private const val T_SHEET =
        "http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet"
    private const val T_STYLES =
        "http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles"

    /** 真打一个 .xlsx。空表名会撞车（Excel 要求唯一），所以重名的自动加序号。 */
    fun write(sheets: List<Sheet>): ByteArray {
        val names = ArrayList<String>()
        for (sh in sheets) {
            var n = safeName(sh.name)
            var k = 2
            while (n in names) { n = safeName(sh.name).take(28) + "(" + k + ")"; k++ }
            names += n
        }
        val list = sheets.mapIndexed { i, sh -> Sheet(names[i], sh.rows) }

        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            fun put(name: String, body: String) {
                z.putNextEntry(ZipEntry(name))
                z.write(body.toByteArray(Charsets.UTF_8))
                z.closeEntry()
            }
            val overrides = list.indices.joinToString("") { i ->
                """<Override PartName="/xl/worksheets/sheet${i + 1}.xml" ContentType=""" +
                    """"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>"""
            }
            put(
                "[Content_Types].xml",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
                    """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""" +
                    """<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""" +
                    """<Default Extension="xml" ContentType="application/xml"/>""" +
                    """<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>""" +
                    overrides +
                    """<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>""" +
                    "</Types>"
            )
            put(
                "_rels/.rels",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
                    """<Relationships xmlns="$NS_REL">""" +
                    """<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>""" +
                    "</Relationships>"
            )
            val entries = list.mapIndexed { i, sh ->
                """<sheet name="${xml(sh.name)}" sheetId="${i + 1}" r:id="rId${i + 1}"/>"""
            }.joinToString("")
            put(
                "xl/workbook.xml",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
                    """<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" """ +
                    """xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">""" +
                    "<sheets>$entries</sheets></workbook>"
            )
            val rels = list.indices.joinToString("") { i ->
                """<Relationship Id="rId${i + 1}" Type="$T_SHEET" Target="worksheets/sheet${i + 1}.xml"/>"""
            }
            put(
                "xl/_rels/workbook.xml.rels",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
                    """<Relationships xmlns="$NS_REL">$rels""" +
                    """<Relationship Id="rId${list.size + 1}" Type="$T_STYLES" Target="styles.xml"/>""" +
                    "</Relationships>"
            )
            // 两份字体（正文 / 加粗）、一份默认样式：cellXfs 的第 1 项就是表头用的 s="1"
            put(
                "xl/styles.xml",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
                    """<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""" +
                    """<fonts count="2"><font><sz val="11"/><name val="Calibri"/></font>""" +
                    """<font><b/><sz val="11"/><color rgb="FF44546A"/><name val="Calibri"/></font></fonts>""" +
                    """<fills count="2"><fill><patternFill patternType="none"/></fill>""" +
                    """<fill><patternFill patternType="gray125"/></fill></fills>""" +
                    """<borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>""" +
                    """<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>""" +
                    """<cellXfs count="2"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>""" +
                    """<xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1"/></cellXfs>""" +
                    """<cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>""" +
                    "</styleSheet>"
            )
            list.forEachIndexed { i, sh -> put("xl/worksheets/sheet${i + 1}.xml", sheetXml(sh)) }
        }
        return out.toByteArray()
    }
}
