package com.haoai.pc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Git 面板的回归。
 *
 * 这一层是给**人**按的，所以判据看"点完之后仓库真变成什么样"：
 * 勾了 → 暂存区里有了；提交 → `git log` 里有了、工作区干净了；而不是"接口回了 200"。
 * 另外三条边界各一条：路径白名单（不能变成 git 选项）、空提交说明、没暂存内容。
 */
class GitPanelTest {

    /** 换行写成字符而不是转义：补丁工具会把反斜杠吃掉，这类文件已经栽过两次。带尾换行，appendText 才不会接在最后一行后面。 */
    private fun txt(vararg l: String) = l.joinToString(10.toChar().toString()) + 10.toChar()

    private fun repo(): File {
        val dir = Files.createTempDirectory("haoai-gitpanel").toFile().apply { mkdirs() }
        assertEquals(0, GitCli.exit(dir, listOf("init", "-q")))
        File(dir, "a.txt").writeText(txt("one", "two"))
        File(dir, "中文.txt").writeText(txt("第一行"))
        assertEquals(0, GitCli.exit(dir, listOf("add", ".")))
        assertEquals(
            0, GitCli.exit(
                dir, listOf("-c", "user.email=t@t", "-c", "user.name=t", "commit", "-qm", "first")
            )
        )
        return dir
    }

    private fun json(s: String) = Json.parseToJsonElement(s).jsonObject

    private fun entries(s: String): Map<String, Boolean> =
        json(s)["entries"]!!.jsonArray.associate {
            val o = it.jsonObject
            o["p"]!!.jsonPrimitive.content to (o["staged"]!!.jsonPrimitive.content == "true")
        }

    private fun ok(s: String) = json(s)["ok"]!!.jsonPrimitive.content == "true"

    @Test
    fun `status separates modified staged and untracked`() {
        assumeTrue("这台机器上没有 git", GitCli.available() != null)
        val r = repo()
        File(r, "a.txt").writeText(txt("one", "two changed"))
        File(r, "新文件.txt").writeText(txt("x"))
        File(r, "中文.txt").writeText(txt("第一行", "第二行"))
        val st = GitPanel.statusJson(r)
        val es = entries(st)
        assertEquals("改动该被判为未暂存：" + st, false, es["a.txt"])
        assertTrue("未跟踪文件要出现：" + st, es.containsKey("新文件.txt"))
        // 这条是 -z 的全部理由：默认输出会把中文转义成 "...\346..." 那样，前端就配不上路径
        assertTrue("中文文件名要原样出现：" + st, es.containsKey("中文.txt"))
        // 分支名不写死 master：有人把 init.defaultBranch 设成了 main
        val br = json(st)["branch"]!!.jsonPrimitive.content
        assertTrue("分支名该是真名，不是占位：" + br, br.isNotBlank() && !br.startsWith("("))

        assertEquals(0, GitCli.exit(r, listOf("add", "a.txt")))
        val after = GitPanel.statusJson(r)
        assertEquals("暂存后 staged 应为 true", true, entries(after)["a.txt"])
        assertEquals("计数要说清已暂存几项", "1", json(after)["stagedCount"]!!.jsonPrimitive.content)
    }

    @Test
    fun `stage and unstage round-trip`() {
        assumeTrue("这台机器上没有 git", GitCli.available() != null)
        val r = repo()
        File(r, "a.txt").appendText(txt("three"))
        assertFalse(GitPanel.status(r).first { it.path == "a.txt" }.staged)
        val s = GitPanel.stageJson(r, listOf("a.txt"), false)
        assertTrue(s, ok(s))
        assertTrue(GitPanel.status(r).first { it.path == "a.txt" }.staged)
        val u = GitPanel.stageJson(r, listOf("a.txt"), true)
        assertTrue(u, ok(u))
        assertFalse(GitPanel.status(r).first { it.path == "a.txt" }.staged)
    }

    @Test
    fun `commit writes history and leaves the tree clean`() {
        assumeTrue("这台机器上没有 git", GitCli.available() != null)
        val r = repo()
        File(r, "a.txt").appendText(txt("three"))
        val c = GitPanel.commitJson(r, "加第三行", listOf("a.txt"))
        assertTrue(c, ok(c))
        assertEquals("加第三行", GitCli.out(r, listOf("log", "-1", "--format=%s")))
        assertTrue("提交后这条文件该从 status 里消失", GitPanel.status(r).none { it.path == "a.txt" })
        assertTrue("界面上要报出新提交：" + c, json(c)["committed"]!!.jsonPrimitive.content.contains("加第三行"))
    }

    @Test
    fun `empty message and nothing staged are refused`() {
        assumeTrue("这台机器上没有 git", GitCli.available() != null)
        val r = repo()
        val blank = json(GitPanel.commitJson(r, "   ", emptyList()))
        assertFalse(ok(GitPanel.commitJson(r, "   ", emptyList())))
        assertTrue(blank.toString(), blank["error"]!!.jsonPrimitive.content.contains("提交说明"))
        File(r, "b.txt").writeText(txt("没暂存"))
        val nothing = json(GitPanel.commitJson(r, "想提交", emptyList()))
        assertFalse("未跟踪文件没暂存就不该提交得出去", ok(GitPanel.commitJson(r, "想提交", emptyList())))
        assertTrue(nothing.toString(), nothing["error"]!!.jsonPrimitive.content.contains("没有可提交"))
        assertEquals("被拒之后不该凭空多出一条提交", "1", GitCli.out(r, listOf("rev-list", "--count", "HEAD")))
    }

    @Test
    fun `path whitelist blocks option injection and escapes`() {
        assumeTrue("这台机器上没有 git", GitCli.available() != null)
        val r = repo()
        assertNull(GitPanel.safePath(r, ""))
        assertNull(GitPanel.safePath(r, "   "))
        assertNull("以 - 开头会被当成 git 选项", GitPanel.safePath(r, "--help"))
        assertNull(GitPanel.safePath(r, "../外部文件.txt"))
        assertNull(GitPanel.safePath(r, "a/../../b"))
        assertEquals("a.txt", GitPanel.safePath(r, "a.txt"))
        assertEquals("a.txt", GitPanel.safePath(r, "./a.txt"))
        assertEquals("中文.txt", GitPanel.safePath(r, "中文.txt"))
        val bad = GitPanel.stageJson(r, listOf("--no-verify", "a.txt"), false)
        assertFalse("整批里有一个坏路径就一个都不暂存", ok(bad))
        assertTrue(GitPanel.status(r).none { it.staged })
    }

    @Test
    fun `diff shows the change and says so for untracked files`() {
        assumeTrue("这台机器上没有 git", GitCli.available() != null)
        val r = repo()
        File(r, "a.txt").writeText(txt("one", "two", "three"))
        File(r, "brand-new.txt").writeText(txt("全新"))
        val d = json(GitPanel.diffJson(r, "a.txt", false))
        assertTrue(d.toString(), ok(GitPanel.diffJson(r, "a.txt", false)))
        val text = d["text"]!!.jsonPrimitive.content
        assertTrue("diff 里该有新增行：" + text, text.contains("+three"))
        val un = json(GitPanel.diffJson(r, "brand-new.txt", false))
        assertTrue(un["text"]!!.jsonPrimitive.content.contains("未跟踪"))
    }

    @Test
    fun `repoFor only walks up to a real git root`() {
        assumeTrue("这台机器上没有 git", GitCli.available() != null)
        val r = repo()
        val deep = File(r, "x/y/z").apply { mkdirs() }
        assertEquals(File(r.canonicalPath), GitPanel.repoFor(deep)?.let { File(it.canonicalPath) })
        val loose = Files.createTempDirectory("haoai-nogit").toFile().apply { mkdirs() }
        // 临时目录本身若落在某个仓库里，这条就没意义 —— 跳过而不是假装通过
        assumeTrue(GitPanel.repoFor(loose) == null)
        assertNull(GitPanel.repoFor(loose))
    }

    @Test
    fun `status of a clean repo says clean`() {
        assumeTrue("这台机器上没有 git", GitCli.available() != null)
        val r = repo()
        val st = json(GitPanel.statusJson(r))
        assertTrue(st["clean"]!!.jsonPrimitive.content == "true")
        assertTrue(st["count"]!!.jsonPrimitive.content == "0")
        assertFalse(st["entries"]!!.jsonArray.isNotEmpty())
        assertTrue(st["last"]!!.jsonPrimitive.content.contains("first"))
        assertTrue("面板要知道这是仓库：" + st, st["isRepo"]!!.jsonPrimitive.content == "true")
    }
}
