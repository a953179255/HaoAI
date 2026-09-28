package com.haoai.pc

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 技能目录（SKILL.md）与导入的判据。
 *
 * 导入的输入全是**外部来的**（粘的文本、下的包、别人仓库里的条目名），
 * 所以这里最要紧的一条不是"能不能装进来"，而是"条目名能不能跑出技能目录"。
 */
class SkillDocsTest {

    companion object {
        private lateinit var home: File

        @BeforeClass
        @JvmStatic
        fun setUpClass() {
            home = Files.createTempDirectory("haoai-skills-home").toFile()
            System.setProperty("haoai.home", File(home, "home").absolutePath)
            File(home, "home").mkdirs()
        }
    }

    @Before
    fun clearDocs() {
        SkillDocs.list().forEach { SkillDocs.remove(it.slug) }
    }

    private fun doc(name: String, desc: String, body: String) =
        "---\nname: $name\ndescription: $desc\n---\n$body"

    private fun zipOf(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            entries.forEach { (n, t) ->
                z.putNextEntry(ZipEntry(n)); z.write(t.toByteArray()); z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    // ---- 形状 ----

    @Test
    fun `frontmatter gives name and description, the rest stays the body`() {
        val d = SkillDocs.saveDoc("", doc("剪片流程", "把录屏剪成 30 秒竖版", "第一步：\n第二步：\n"))!!
        assertEquals("剪片流程", d.name)
        assertEquals("把录屏剪成 30 秒竖版", d.desc)
        assertEquals("正文原样留着（含换行）", "第一步：\n第二步：", d.body.trim())
        assertTrue("落在 skills/<slug>/SKILL.md", d.file.isFile && d.file.name == "SKILL.md")
    }

    @Test
    fun `a doc without frontmatter still imports`() {
        val d = SkillDocs.saveDoc("随手技能", "# 标题\n正文一段")!!
        assertEquals("没头就用给的名字", "随手技能", d.name)
        assertTrue(d.body.contains("正文一段"))
    }

    @Test
    fun `a chinese skill name keeps a readable directory`() {
        val s = SkillDocs.sanitize("剪片子 v2")
        assertTrue("中文名别折成一串横线：" + s, s.contains("剪片子"))
        assertEquals("目录名里不许有路径分隔", -1, s.indexOf('/'))
    }

    // ---- 安全 ----

    @Test
    fun `a zip entry trying to escape is refused and reported`() {
        val bytes = zipOf(
            "../SKILL.md" to doc("越界", "往上跑", "x"),
            "a/../../evil/SKILL.md" to doc("越界二", "往上跑", "x"),
            "good/SKILL.md" to doc("正常", "该进的", "y")
        )
        val r = SkillDocs.importZip(bytes)
        assertTrue("正常那份该进：" + r.added, r.added.size == 1)
        assertTrue("越界条目要出现在 skipped 里：" + r.skipped, r.skipped.size == 2)
        assertFalse("状态根里不该冒出 SKILL.md", File(Env.home, "SKILL.md").isFile)
        assertFalse("上一层更不该有", File(Env.home.parentFile, "SKILL.md").isFile)
        assertTrue("所有技能都还在目录内",
            SkillDocs.list().all { it.file.canonicalPath.startsWith(SkillDocs.dir().canonicalPath) })
    }

    @Test
    fun `the sanitizer cannot leave the skills dir`() {
        for (raw in listOf("../../etc", "..\\..\\windows", "/absolute", "a/b/../../c", "....//", "%2e%2e")) {
            val s = SkillDocs.sanitize(raw)
            assertFalse("$raw 里不该还有点号或斜杠：" + s, s.contains("/") || s.contains("\\"))
            assertFalse("$raw 不该只剩点：" + s, s.all { it == '.' })
        }
    }

    @Test
    fun `an oversized entry is skipped instead of filling the disk`() {
        val big = "a".repeat(SkillDocs.MAX_BYTES + 10)
        val r = SkillDocs.importZip(zipOf("big/SKILL.md" to big))
        assertTrue("该跳过并说明：" + r.skipped, r.skipped.any { it.contains("超过") })
        assertTrue("不该留下半个技能", SkillDocs.list().isEmpty())
    }

    // ---- 导入路 ----

    @Test
    fun `a zip with two skills imports both`() {
        val bytes = zipOf(
            "alpha/SKILL.md" to doc("甲技能", "第一个", "做甲"),
            "beta/SKILL.md" to doc("乙技能", "第二个", "做乙")
        )
        val r = SkillDocs.importZip(bytes)
        assertEquals(2, r.added.size)
        assertEquals(setOf("甲技能", "乙技能"), SkillDocs.list().map { it.name }.toSet())
    }

    @Test
    fun `importing the same skill twice does not overwrite the first`() {
        SkillDocs.importText(doc("重复", "第一版", "旧内容"))
        val r = SkillDocs.importText(doc("重复", "第二版", "新内容"))
        assertTrue("第二次也该成功（另存一个名字）：" + r.error, r.ok)
        val all = SkillDocs.list().filter { it.name == "重复" }
        assertEquals("两份都该在", 2, all.size)
        assertTrue("内容各是各的", all.map { it.body }.toSet().size == 2)
    }

    @Test
    fun `url import takes markdown over http and refuses anything else`() {
        val gw = com.sun.net.httpserver.HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        gw.createContext("/skill.md") { ex ->
            val b = doc("网址技能", "从本地假网关来的", "正文一段").toByteArray()
            ex.responseHeaders.add("Content-Type", "text/markdown; charset=utf-8")
            ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) }; ex.close()
        }
        gw.createContext("/pkg.zip") { ex ->
            val b = zipOf("zipped/SKILL.md" to doc("包里技能", "zip 走的", "包内正文"))
            ex.responseHeaders.add("Content-Type", "application/zip")
            ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) }; ex.close()
        }
        gw.start()
        try {
            val base = "http://127.0.0.1:${gw.address.port}"
            val md = SkillDocs.importUrl("$base/skill.md")
            assertTrue("md 该进：" + md.error, md.added.size == 1)
            val zp = SkillDocs.importUrl("$base/pkg.zip")
            assertTrue("zip 该进：" + zp.error, zp.added.size == 1)
            assertEquals("列表里两份都在", 2, SkillDocs.list().size)
            val bad = SkillDocs.importUrl("file:///C:/Windows/win.ini")
            assertFalse("file: 这种地址绝不能碰", bad.ok)
            assertTrue("要说清只支持什么：" + bad.error, bad.error.contains("http"))
        } finally { gw.stop(0) }
    }

    @Test
    fun `removing a doc deletes its directory`() {
        val d = SkillDocs.importText(doc("要删的", "临时", "x")).added.first()
        assertTrue(SkillDocs.remove(d))
        assertFalse("目录该整个不见", File(SkillDocs.dir(), d).exists())
        assertFalse("再删一次要说没有", SkillDocs.remove(d))
    }
}
