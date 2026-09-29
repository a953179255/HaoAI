package com.haoai.pc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 本地 review（#8 的"可做"那一半）。
 *
 * 判据的重点不是"能不能跑 git"，而是**规则的方向对不对**：密钥要报高、
 * 大段删除与发布面要报出来、源码动了测试没动要提醒、干净的改动不许无中生有
 * （报告一惊一乍的话，第二天就没人看了）。外发那半没有实现，也就没有可测的行为。
 */
class ReviewTest {

    private fun numstat(vararg rows: String) = rows.joinToString("\n")

    private fun patch(path: String, vararg added: String) = buildString {
        appendLine("diff --git a/$path b/$path")
        appendLine("index 111..222 100644")
        appendLine("--- a/$path")
        appendLine("+++ b/$path")
        appendLine("@@ -1,0 +1,${added.size} @@")
        added.forEach { appendLine("+$it") }
    }

    private fun report(base: String = "HEAD", numstat: String, patch: String) =
        Review.analyze(base, numstat, patch)

    @Test
    fun `新增行里的密钥是高危且只归到那个文件`() {
        val ns = numstat("3\t1\tconfig/App.kt", "10\t0\tsrc/Clean.kt")
        val pt = patch("config/App.kt", "val token = \"sk-abcdefghijklmno1234\"") +
            patch("src/Clean.kt", "fun ok() = 1")
        val r = report(numstat = ns, patch = pt)
        val highs = r.findings.filter { it.level == "高" }
        assertEquals("密钥只该报一处：" + highs, 1, highs.size)
        assertEquals("config/App.kt", highs[0].where)
        assertTrue(r.high >= 1)
        assertTrue(r.suggestions.any { it.contains("轮换") })
    }

    @Test
    fun `两百行以上的大段删除要报中危`() {
        val r = report(numstat = numstat("250\t250\tlegacy/Old.kt"), patch = "diff --git a/legacy/Old.kt b/legacy/Old.kt\n+fun a() = 1")
        assertTrue(r.findings.any { it.level == "中" && it.where == "legacy/Old.kt" && it.why.contains("250") })
    }

    @Test
    fun `发布面文件改了要报出来`() {
        val r = report(
            numstat = numstat("2\t1\t.github/workflows/ci.yml", "1\t1\tgradle.properties", "5\t0\tsrc/Main.kt"),
            patch = patch(".github/workflows/ci.yml", "uses: evil/action@v1")
        )
        val mids = r.findings.filter { it.level == "中" }.map { it.where }
        assertTrue("workflow 要报：" + mids, mids.contains(".github/workflows/ci.yml"))
        assertTrue("gradle.properties 要报：" + mids, mids.contains("gradle.properties"))
        // src/Main.kt 没动发布面，不许跟着中枪
        assertFalse(mids.contains("src/Main.kt"))
    }

    @Test
    fun `测试文件被整份删掉要报并给建议`() {
        val r = report(
            numstat = numstat("0\t80\tpc/src/test/kotlin/com/haoai/pc/OldTest.kt", "80\t0\tpc/src/Main.kt"),
            patch = patch("pc/src/Main.kt", "fun main() {}")
        )
        assertTrue(r.findings.any { it.level == "中" && it.where.endsWith("OldTest.kt") })
        assertTrue(r.suggestions.any { it.contains("测试变少") })
    }

    @Test
    fun `源码动了而测试没动要提醒补测试`() {
        val r = report(numstat = numstat("10\t2\tpc/src/Engine.kt"), patch = patch("pc/src/Engine.kt", "fun f() = 2"))
        assertTrue(r.suggestions.any { it.contains("配套测试") })
        assertEquals("干净改动不许凭空报风险", 0, r.findings.size)
    }

    @Test
    fun `带测试的正常改动只有恒有的那条建议`() {
        val r = report(
            numstat = numstat("10\t2\tpc/src/Engine.kt", "5\t0\tpc/src/test/kotlin/com/haoai/pc/EngineTest.kt"),
            patch = patch("pc/src/Engine.kt", "fun f() = 2")
        )
        assertEquals(0, r.findings.size)
        assertEquals("只该剩收尾那一条：" + r.suggestions, 1, r.suggestions.size)
        assertTrue(r.suggestions[0].contains("gradle test"))
    }

    @Test
    fun `二进制变解析成 binary 并报低危`() {
        val r = report(numstat = numstat("-\t-\tassets/logo.png"), patch = "diff --git a/assets/logo.png b/assets/logo.png\nBinary files differ")
        assertEquals(1, r.files.size)
        assertTrue(r.files[0].binary)
        assertTrue(r.findings.any { it.level == "低" && it.where.endsWith("logo.png") })
    }

    @Test
    fun `numstat 的行数统计进总数`() {
        val r = report(numstat = numstat("12\t3\ta.kt", "4\t9\tb.kt"), patch = "")
        assertEquals(16, r.added)
        assertEquals(12, r.deleted)
        assertEquals(2, r.files.size)
    }

    @Test
    fun `render 三段都在且结论按高危翻转`() {
        val bad = report(
            numstat = numstat("1\t0\tsrc/A.kt"),
            patch = patch("src/A.kt", "api_key = \"verysecretvalue\"")
        )
        val text = Review.render(bad)
        assertTrue(text.contains("改了啥"))
        assertTrue(text.contains("风险（高 1"))
        assertTrue(text.contains("建议"))
        assertTrue(text.contains("结论：有 1 条高危"))
        val good = report(numstat = numstat("1\t0\tsrc/A.kt"), patch = patch("src/A.kt", "val x = 1"))
        assertTrue(Review.render(good).contains("结论：没有高危项"))
    }

    @Test
    fun `json 输出字段齐且能被解析`() {
        val r = report(numstat = numstat("1\t0\tsrc/A.kt"), patch = patch("src/A.kt", "val x = 1"))
        val obj = Json.parseToJsonElement(Review.toJson(r)).jsonObject
        assertEquals("HEAD", obj["base"]!!.jsonPrimitive.content)
        assertEquals(1, obj["files"]!!.jsonPrimitive.int)
        assertEquals(1, obj["added"]!!.jsonPrimitive.int)
        assertTrue(obj["findings"]!!.jsonArray.isEmpty())
        assertTrue(obj["suggestions"]!!.jsonArray.size >= 1)
        assertEquals(0, obj["high"]!!.jsonPrimitive.int)
    }

    @Test
    fun `collect 在没有这个 base 时返回 null 而不是空报告`() {
        assumeTrue("这台机器上没有 git", GitCli.available() != null)
        val dir = Files.createTempDirectory("haoai-review").toFile().apply { mkdirs() }
        assertEquals(0, GitCli.exit(dir, listOf("init", "-q")))
        File(dir, "a.txt").writeText("one\n")
        GitCli.exit(dir, listOf("add", "."))
        GitCli.exit(dir, listOf("-c", "user.email=t@t", "-c", "user.name=t", "commit", "-qm", "first"))
        assertNull(Review.collect(dir, "no-such-base-here"))
        val ok = Review.collect(dir, "HEAD")
        assertNotNull("HEAD 上该拿得到 diff（哪怕是空的）", ok)
        val r = Review.analyze("HEAD", ok!!.first, ok.second)
        assertEquals("base 之后没有变更就该是空报告", 0, r.files.size)
        assertEquals(0, r.high)
    }

    @Test
    fun `未跟踪的新文件也进审查——git diff 看不见的那半`() {
        assumeTrue("这台机器上没有 git", GitCli.available() != null)
        val dir = Files.createTempDirectory("haoai-review2").toFile().apply { mkdirs() }
        assertEquals(0, GitCli.exit(dir, listOf("init", "-q")))
        File(dir, "a.txt").writeText("one\n")
        GitCli.exit(dir, listOf("add", "."))
        GitCli.exit(dir, listOf("-c", "user.email=t@t", "-c", "user.name=t", "commit", "-qm", "first"))
        // 新文件没 git add：git diff HEAD 对它一片空白——最容易藏密钥的正是这种文件
        File(dir, "Fresh.kt").writeText("val k = \"sk-abcdefghijklmno1234\"\nfun f() = 1\n")
        val ok = Review.collect(dir, "HEAD")
        assertNotNull(ok)
        val r = Review.analyze("HEAD", ok!!.first, ok.second)
        assertTrue("新文件要出现在改了啥里：" + r.files.map { it.path },
            r.files.any { it.path == "Fresh.kt" && it.added == 2 })
        assertTrue("新文件里的密钥不许漏：" + r.findings,
            r.findings.any { it.level == "高" && it.where == "Fresh.kt" })
    }
}
