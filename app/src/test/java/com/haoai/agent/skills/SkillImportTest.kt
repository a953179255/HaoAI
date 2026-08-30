package com.haoai.agent.skills

import com.haoai.agent.agent.skills.SkillImporter
import com.haoai.agent.agent.skills.SkillStore
import com.haoai.agent.agent.skills.SkillStore.ImportOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 2.2 技能导入纯逻辑单测：agentskills.io frontmatter 兼容解析、导入冲突/重命名、
 * 批量导入汇总。文件 IO 走 @TempDir 临时目录，不碰 Android 框架。
 */
class SkillImportTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun initStore() {
        SkillStore.init(tmp.newFolder("skills").parentFile)
    }

    // ---------- parseDoc ----------

    @Test
    fun `标准 agentskills frontmatter 解析`() {
        val text = """
            ---
            name: pdf
            description: Work with PDF files.
            license: Proprietary. LICENSE.txt has complete terms
            metadata:
              author: anthropic
            ---
            # PDF Guide
            Body content here.
        """.trimIndent()
        val doc = SkillStore.parseDoc(text)
        assertEquals("pdf", doc.name)
        assertEquals("Work with PDF files.", doc.description)
        assertTrue(doc.body.startsWith("# PDF Guide"))
        assertEquals(null, doc.error)
    }

    @Test
    fun `带引号与转义的多行 description 清理`() {
        val text = """
            ---
            name: xlsx
            description: "Use this skill any time a spreadsheet file is the primary input or output (e.g., \"the xlsx in my downloads\")."
            ---
            Body.
        """.trimIndent()
        val doc = SkillStore.parseDoc(text)
        assertEquals("xlsx", doc.name)
        assertFalse(doc.description.startsWith("\""))
        assertFalse(doc.description.contains("\\\""))
        assertTrue(doc.description.startsWith("Use this skill"))
    }

    @Test
    fun `缺 name 与缺 description 兜底`() {
        val text = """
            ---
            description: Only description here.
            ---
            First body line is ignored for description because description exists.
        """.trimIndent()
        val doc = SkillStore.parseDoc(text)
        assertEquals(null, doc.name) // 由调用方以文件名兜底
        assertEquals("Only description here.", doc.description)

        val noDesc = """
            ---
            name: bare
            ---
            正文首段作为描述兜底。
            第二段。
        """.trimIndent()
        val doc2 = SkillStore.parseDoc(noDesc)
        assertEquals("bare", doc2.name)
        assertEquals("正文首段作为描述兜底。", doc2.description)
    }

    @Test
    fun `无 frontmatter 时全文视为正文`() {
        val doc = SkillStore.parseDoc("# Title\n\n正文内容。")
        assertEquals(null, doc.name)
        assertEquals("正文内容。", doc.description)
        assertTrue(doc.body.startsWith("# Title"))
    }

    @Test
    fun `frontmatter 未闭合报错且不产生半成品`() {
        val broken = "---\nname: broken\n没有结束线"
        assertNotNull(SkillStore.parseDoc(broken).error)
        initStore()
        val r = SkillStore.importDoc(broken, "import_url")
        assertTrue(r is ImportOutcome.Failed)
        assertFalse(SkillStore.exists("broken")) // 不留半成品
    }

    // ---------- importDoc / importBatch ----------

    @Test
    fun `导入成功且重名返回 Conflict`() {
        initStore()
        val text = "---\nname: demo\ndescription: d1\n---\nbody"
        val r1 = SkillStore.importDoc(text, "import_url")
        assertTrue(r1 is ImportOutcome.Done)
        assertEquals("demo", (r1 as ImportOutcome.Done).name)
        val r2 = SkillStore.importDoc(text.replace("d1", "d2"), "import_url")
        assertTrue(r2 is ImportOutcome.Conflict)
        // 覆盖模式允许
        val r3 = SkillStore.importDoc(text.replace("d1", "d3"), "import_url", overwrite = true)
        assertTrue(r3 is ImportOutcome.Done)
        assertEquals(1, SkillStore.list().size)
    }

    @Test
    fun `批量导入重名自动加序号后缀`() {
        initStore()
        val docs = listOf(
            "folder" to "---\nname: sheet\ndescription: a\n---\nb1",
            "folder2" to "---\nname: sheet\ndescription: b\n---\nb2",
            "folder3" to "---\nname: sheet\ndescription: c\n---\nb3"
        )
        val result = SkillImporter.importBatch(docs, "import_local")
        assertEquals(listOf("sheet", "sheet-2", "sheet-3"), result.imported)
        assertEquals(2, result.renamed.size)
        assertEquals(0, result.failed.size)
        assertTrue(result.summary().contains("重名自动改名 2 个"))
    }

    @Test
    fun `批量导入收集失败项`() {
        initStore()
        val docs = listOf(
            "ok" to "---\nname: good\ndescription: x\n---\nbody",
            "bad" to "---\nname: broken\n没有结束线"
        )
        val result = SkillImporter.importBatch(docs, "import_clipboard")
        assertEquals(listOf("good"), result.imported)
        assertEquals(1, result.failed.size)
        assertEquals("bad", result.failed[0].first)
        assertTrue(result.failed[0].second.contains("frontmatter") || result.failed[0].second.contains("未闭合"))
    }

    // ---------- sanitizeName / exists ----------

    @Test
    fun `技能名净化与存在性检查`() {
        initStore()
        assertEquals("my-skill", SkillStore.sanitizeName("my skill!"))
        assertFalse(SkillStore.exists("nope"))
        SkillStore.save("exist-test", "d", "b")
        assertTrue(SkillStore.exists("exist-test"))
        assertTrue(SkillStore.exists("exist-test ")) // 带空白也应命中
    }
}
