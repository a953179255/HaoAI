package com.haoai.pc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 知识库（对标 Octop 第 5 条）。
 *
 * 判据重点放在两件最容易糊的地方：
 * - **二进制到底有没有真抽出正文**（docx/pptx/xlsx 都是 zip+XML，测试里现造一份真的 zip，
 *   不是喂一段假字节说"解析成功"）；
 * - **解析失败必须看得见**（列表里凭空少一项 = 用户以为导入成功了）。
 */
class KnowledgeTest {

    companion object {
        @BeforeClass
        @JvmStatic
        fun home() {
            val dir = Files.createTempDirectory("haoai-kb-home").toFile()
            System.setProperty("haoai.home", File(dir, "home").absolutePath)
        }
    }

    @Before
    fun clear() {
        Knowledge.file().delete()
        runCatching { Knowledge.root().deleteRecursively() }
        Presets.file().delete()
    }

    private fun zip(parts: Map<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            parts.forEach { (n, body) ->
                z.putNextEntry(ZipEntry(n)); z.write(body.toByteArray(Charsets.UTF_8)); z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun docx(vararg paras: String) = zip(mapOf(
        "word/document.xml" to ("<w:document><w:body>" +
            paras.joinToString("") { "<w:p><w:r><w:t>$it</w:t></w:r></w:p>" } +
            "</w:body></w:document>")
    ))

    // ---- 库 ----

    @Test
    fun `a base round-trips and empty or duplicate names are refused`() {
        val (b, err) = Knowledge.create("剪辑规范", "公司内部的剪辑口径")
        assertNull(err)
        val back = Knowledge.load().single()
        assertEquals("剪辑规范", back.name)
        assertEquals("公司内部的剪辑口径", back.desc)
        assertEquals(false, back.defaultOpen)
        assertTrue(Knowledge.create("", "").second!!.contains("名字"))
        assertTrue(Knowledge.create("剪辑规范").second!!.contains("同名"))
    }

    @Test
    fun `toggling default-open and renaming keep one source of truth`() {
        val b = Knowledge.create("制度")!!.first!!
        Knowledge.rename(b.id, defaultOpen = true)
        assertTrue(Knowledge.find(b.id)!!.defaultOpen)
        Knowledge.rename(b.id, name = "公司制度", desc = "改过了")
        assertEquals("公司制度", Knowledge.find(b.id)!!.name)
        assertEquals("改过了", Knowledge.find(b.id)!!.desc)
        assertEquals("改名不能变成第二个库", 1, Knowledge.load().size)
    }

    @Test
    fun `deleting a base takes its files with it`() {
        val b = Knowledge.create("临时")!!.first!!
        Knowledge.addDoc(b.id, "a.md", "内容".toByteArray())
        assertTrue(Knowledge.dir(b.id).isDirectory)
        Knowledge.remove(b.id)
        assertTrue("目录没跟着删，盘上会堆一堆孤儿语料", !Knowledge.dir(b.id).exists())
        assertNull(Knowledge.find(b.id))
    }

    // ---- 文档与解析 ----

    @Test
    fun `a text doc lands as ready with chunks and both files`() {
        val b = Knowledge.create("手册")!!.first!!
        val text = (1..40).joinToString("\n") { "第 $it 行，讲一个要点，够长才会被切成好几块。" }
        val (d, err) = Knowledge.addDoc(b.id, "guide.md", text.toByteArray())
        assertNull(err)
        assertEquals(Knowledge.READY, d!!.status)
        assertTrue("正文要真落一份给索引器读", Knowledge.txtFile(b.id, "guide.md").isFile)
        assertTrue("原文也要在（下载与重新索引都靠它）", Knowledge.srcFile(b.id, "guide.md") != null)
        assertTrue("块数要大于 1（几十行才看得出切没切）", d.chunks > 1)
        assertEquals(text.length, d.chars)
    }

    /** docx 是 zip 里装 XML：不引 POI 也能读，这条就是钉这个的。 */
    @Test
    fun `docx text is extracted with paragraph breaks`() {
        val b = Knowledge.create("办公")!!.first!!
        val (d, _) = Knowledge.addDoc(b.id, "说明.docx", docx("第一段 关于导出", "第二段 关于索引"))
        assertEquals(Knowledge.READY, d!!.status)
        val t = Knowledge.textOf(b.id, "说明.docx")!!
        assertTrue("要抽出正文：" + t, t.contains("第一段 关于导出"))
        assertTrue("段落之间要换行（SemanticIndex 按行攒块，挤成一行等于粒度全丢）",
            t.contains("\n"))
        assertTrue("不该残留 XML 标签", !t.contains("<w:t>"))
    }

    @Test
    fun `xlsx shared strings and pptx slides come through in slide order`() {
        val b = Knowledge.create("办公2")!!.first!!
        Knowledge.addDoc(b.id, "表.xlsx", zip(mapOf(
            "xl/sharedStrings.xml" to "<sst><si><t>季度</t></si><si><t>营收</t></si></sst>")))
        assertTrue(Knowledge.textOf(b.id, "表.xlsx")!!.contains("营收"))
        Knowledge.addDoc(b.id, "片子.pptx", zip(mapOf(
            "ppt/slides/slide1.xml" to "<p:sld><a:t>第一页</a:t></p:sld>",
            "ppt/slides/slide10.xml" to "<p:sld><a:t>第十页</a:t></p:sld>",
            "ppt/slides/slide2.xml" to "<p:sld><a:t>第二页</a:t></p:sld>")))
        val t = Knowledge.textOf(b.id, "片子.pptx")!!
        assertTrue("slide1/2/10 要按数字顺序读，不然章节会乱：" + t,
            t.indexOf("第一页") in 0..t.indexOf("第二页") && t.indexOf("第二页") < t.indexOf("第十页"))
    }

    @Test
    fun `html loses its tags and scripts`() {
        val b = Knowledge.create("网页")!!.first!!
        Knowledge.addDoc(b.id, "page.html",
            "<html><head><script>var a=1</script></head><body><h1>标题</h1><p>正文一段</p></body></html>"
                .toByteArray())
        val t = Knowledge.textOf(b.id, "page.html")!!
        assertTrue(t.contains("正文一段"))
        assertTrue("脚本内容不该进语料（会污染检索）", !t.contains("var a=1"))
    }

    /** 解析失败也要看得见：列表里凭空少一项是最坏的失败。 */
    @Test
    fun `an unreadable doc is kept as failed with a reason`() {
        val b = Knowledge.create("坏文件")!!.first!!
        val (d, _) = Knowledge.addDoc(b.id, "假.docx", ByteArray(64))
        assertEquals(Knowledge.FAILED, d!!.status)
        assertTrue("要说清为什么：" + d.error, d.error.isNotBlank())
        assertEquals("failed 的条目仍要在列表里", 1, Knowledge.find(b.id)!!.docs.size)
        assertTrue("PDF 没工具时也要给出路（告诉你转成什么再来）",
            d.error.contains("docx") || d.error.contains("zip") || d.error.contains("正文") ||
                d.error.contains("pdftotext"))
    }

    /**
     * 文件名的保证是"**削成一个干净的本体**"，不是"带路径就整条拒收"：
     * `../../docs/秘密.md` → `秘密.md` 落在库目录里，走不了别处。
     * （原来我在这儿写了两个 assertNull，那是把"消毒"误解成"拒绝"—— 代码是对的，判据错了）
     */
    @Test
    fun `unknown extensions are refused and paths get flattened to one name`() {
        val b = Knowledge.create("门禁")!!.first!!
        assertNull(Knowledge.safeName("virus.exe"))
        assertNull(Knowledge.safeName(".hidden.md"))
        assertNull(Knowledge.safeName("没有扩展名"))
        assertEquals("秘密.md", Knowledge.safeName("../秘密.md"))
        assertEquals("秘密.md", Knowledge.safeName("../../docs/秘密.md"))
        assertEquals("b.md", Knowledge.safeName("a\\..\\b.md"))
        assertTrue("削完不许还剩分隔符或 ..",
            listOf(Knowledge.safeName("a\\..\\b.md"), Knowledge.safeName("../../x.md"))
                .all { it != null && !it.contains("..") && !it.contains('/') && !it.contains('\\') })
        assertTrue(Knowledge.addDoc(b.id, "x.exe", ByteArray(4)).second!!.contains("不收"))
        assertEquals("被拒的不该在列表里留痕", 0, Knowledge.find(b.id)!!.docs.size)
    }

    @Test
    fun `reimporting the same name replaces it and keeps the original add time`() {
        val b = Knowledge.create("更新")!!.first!!
        val first = Knowledge.addDoc(b.id, "a.md", "旧内容".toByteArray()).first!!
        Thread.sleep(5)
        val second = Knowledge.addDoc(b.id, "a.md", "新内容更长一点".toByteArray()).first!!
        assertEquals("同名要是一条而不是两条", 1, Knowledge.find(b.id)!!.docs.size)
        assertEquals(first.added, second.added)
        assertTrue("正文要真的换掉", Knowledge.textOf(b.id, "a.md")!!.contains("新内容"))
    }

    @Test
    fun `reindex re-extracts from the stored original`() {
        val b = Knowledge.create("重建")!!.first!!
        Knowledge.addDoc(b.id, "说明.docx", docx("原始段落"))
        // 手工把正文那份弄坏，模拟"解析器升级了/文件被误删"
        Knowledge.txtFile(b.id, "说明.docx").writeText("")
        assertTrue(Knowledge.reindex(b.id, "说明.docx").first!!.status == Knowledge.READY)
        assertTrue(Knowledge.textOf(b.id, "说明.docx")!!.contains("原始段落"))
        assertTrue("原文丢了就没法重建，要说清",
            Knowledge.reindex(b.id, "没这个.docx").second!!.contains("原文"))
    }

    @Test
    fun `the per-base doc cap is enforced by name not by luck`() {
        val b = Knowledge.create("满了")!!.first!!
        repeat(Knowledge.MAX_DOCS) {
            assertNotNull(Knowledge.addDoc(b.id, "d$it.md", "内容".toByteArray()).first)
        }
        val (d, err) = Knowledge.addDoc(b.id, "多一个.md", "内容".toByteArray())
        assertNull(d)
        assertTrue("满了要明说：" + err, err!!.contains("最多 100"))
        // 同名重导是更新，不算新增，不能被上限挡住
        assertNotNull(Knowledge.addDoc(b.id, "d0.md", "改一下".toByteArray()).first)
    }

    // ---- 检索与绑定 ----

    @Test
    fun `search falls back to literal matching when embeddings are not configured`() {
        val b = Knowledge.create("术语表")!!.first!!
        Knowledge.addDoc(b.id, "gloss.md",
            "# 术语\n\n液态玻璃：一种把背景模糊后叠上来的做法。\n别的条目：无关内容。".toByteArray())
        val (hits, _) = Knowledge.search(listOf(b.id), "液态玻璃", embedUrl = "", top = 3)
        assertTrue("没配 embedUrl 也该查得到字面命中", hits.isNotEmpty())
        val h = hits.first()
        assertEquals("术语表", h.kbName)
        assertEquals("gloss.md", h.doc)
        assertEquals("字面", h.how)
        assertTrue(h.snippet.contains("液态玻璃"))
    }

    @Test
    fun `search says so when nothing matches instead of inventing one`() {
        val b = Knowledge.create("空查")!!.first!!
        Knowledge.addDoc(b.id, "a.md", "只有一句无关的话".toByteArray())
        val (hits, _) = Knowledge.search(listOf(b.id), "火星基地编号", embedUrl = "")
        assertTrue(hits.isEmpty())
    }

    @Test
    fun `a preset binds bases and falls back to the default-open ones`() {
        val x = Knowledge.create("X")!!.first!!
        val y = Knowledge.create("Y")!!.first!!
        Knowledge.rename(y.id, defaultOpen = true)
        Knowledge.addDoc(x.id, "口径.md", "导出要留原片".toByteArray())
        Presets.update(Preset(id = "p1", name = "甲", persona = "", model = "", workspace = "",
            mode = "", kbs = listOf(x.id)))
        assertEquals(listOf(x.id), Knowledge.boundTo("p1"))
        assertEquals("没挂卡的会话只带自动打开的那些", listOf(y.id), Knowledge.boundTo(""))
        assertEquals("卡上没绑 → 同样回落到自动打开", listOf(y.id), Knowledge.boundTo("p2"))
        assertTrue("目录要给模型看库名与文件名：" + Knowledge.catalog(listOf(x.id, y.id)),
            Knowledge.catalog(listOf(x.id, y.id)).contains("口径.md"))
        assertEquals("一个库都没有时目录是空串（不占提示的 token）", "", Knowledge.catalog(listOf("没有")))
    }

    @Test
    fun `the json tells the interface what it can accept`() {
        Knowledge.create("读数")
        val j = Json.parseToJsonElement(Knowledge.json(false)).jsonObject
        assertEquals(20, j["maxBases"]!!.jsonPrimitive.int)
        assertEquals(100, j["maxDocs"]!!.jsonPrimitive.int)
        assertEquals(false, j["embed"]!!.jsonPrimitive.boolean)
        assertTrue(j["items"]!!.jsonArray.size == 1)
        val exts = j["exts"]!!.jsonArray.map { it.jsonPrimitive.content }
        for (need in listOf("md", "docx", "pptx", "xlsx", "pdf"))
            assertTrue("该收的没列出来：$need", need in exts)
        val one = j["items"]!!.jsonArray[0].jsonObject
        assertTrue(one.containsKey("defaultOpen"))
        assertTrue(one["docs"]!!.jsonArray.isEmpty())
    }

    // ---- 接线 ----

    @Test
    fun `the tool the prompt tells the model to use is really wired`() {
        val tools = File("src/main/kotlin/com/haoai/pc/Tools.kt").readText(Charsets.UTF_8)
        assertTrue("search_knowledge 要注册进内置表",
            tools.contains("SearchKnowledgeTool()"))
        assertTrue("工具要知道自己是哪个专家（决定查哪些库）",
            tools.contains("Knowledge.boundTo(ctx.presetId)"))
        val eng = File("src/main/kotlin/com/haoai/pc/Engine.kt").readText(Charsets.UTF_8)
        assertTrue("ctx.presetId 要接上会话的卡", eng.contains("ctx.presetId = session.preset"))
        assertTrue("系统提示里要有知识库目录（不然模型不知道有这回事）",
            eng.contains("Knowledge.catalog(Knowledge.boundTo(session.preset))"))
        val srv = File("src/main/kotlin/com/haoai/pc/Server.kt").readText(Charsets.UTF_8)
        assertTrue("两条新路由要在", srv.contains("\"/api/kbs\" -> kbs(ex)") &&
            srv.contains("\"/api/kbfile\" -> kbFile(ex)"))
        assertTrue("存卡时要把不存在的库 id 滤掉", srv.contains("Knowledge.find(it) != null"))
    }
}
