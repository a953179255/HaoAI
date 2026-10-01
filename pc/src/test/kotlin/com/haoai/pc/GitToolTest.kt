package com.haoai.pc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * git 工具的回归。
 *
 * 值得单独测的两件事：一是**参数切分**（`commit -m "fix: 两个词"` 不能被切成五段），
 * 二是**权限**：只读子命令不该问人，改仓库的必须走同一张规则表 ——
 * 否则"绕过 shell 直接调 git"就成了权限规则的侧门。
 */
class GitToolTest {

    private class SpyGate : Gate {
        val asked = mutableListOf<String>()
        var allow = true
        override fun approve(title: String, detail: String, kind: String): Boolean {
            asked += title
            return allow
        }

        override fun ask(question: String, options: List<String>) = ""
    }

    private fun args(json: String) = Json.parseToJsonElement(json).jsonObject

    private fun repo(): File {
        val dir = Files.createTempDirectory("haoai-git").toFile().apply { mkdirs() }
        assertEquals(0, GitCli.exit(dir, listOf("init", "-q")))
        File(dir, "a.txt").writeText("one\n")
        GitCli.exit(dir, listOf("add", "."))
        GitCli.exit(dir, listOf("-c", "user.email=t@t", "-c", "user.name=t", "commit", "-qm", "first"))
        return dir
    }

    @Test
    fun `git is available on this machine`() {
        assumeTrue("这台机器上没有 git", GitCli.available() != null)
    }

    @Test
    fun `quoted arguments survive the split`() {
        assertEquals(
            listOf("commit", "-m", "fix: 修好两件事"),
            listOf("commit") + GitTool.splitArgs("-m \"fix: 修好两件事\"")
        )
        assertEquals(listOf("log", "--oneline", "-5"), listOf("log") + GitTool.splitArgs("--oneline -5"))
        assertTrue(GitTool.splitArgs("   ").isEmpty())
    }

    @Test
    fun `read-only subcommands run without asking`() {
        assumeTrue("这台机器上没有 git", GitCli.available() != null)
        val r = repo()
        val gate = SpyGate()
        val ctx = ToolCtx(r, PcSettings(), "ask", gate)
        File(r, "a.txt").appendText("two\n")

        val st = GitTool().runB(args("""{"sub":"status","args":"--porcelain"}"""), ctx)
        assertFalse(st.content, st.error)
        assertTrue("status 没看到改动：${st.content}", st.content.contains("a.txt"))
        val df = GitTool().runB(args("""{"sub":"diff","args":"-- a.txt"}"""), ctx)
        assertTrue("diff 里没有 +two：${df.content}", df.content.contains("+two"))
        assertEquals("只读子命令不该问人", 0, gate.asked.size)
    }

    @Test
    fun `add and commit actually land in the repo`() {
        assumeTrue("这台机器上没有 git", GitCli.available() != null)
        val r = repo()
        val ctx = ToolCtx(r, PcSettings(), "auto", SpyGate())
        File(r, "b.txt").writeText("hello\n")
        val added = GitTool().runB(args("""{"sub":"add","args":"b.txt"}"""), ctx)
        assertFalse(added.content, added.error)
        val committed = GitTool().runB(
            args("""{"sub":"commit","args":"-m \"add b\""}"""), ctx
        )
        assertFalse(committed.content, committed.error)
        val log = GitTool().runB(args("""{"sub":"log","args":"--oneline"}"""), ctx)
        assertTrue("log 里没有新提交：${log.content}", log.content.contains("add b"))
        assertEquals("应该有两个提交", 2, GitCli.out(r, listOf("rev-list", "--count", "HEAD")).trim().toInt())
    }

    @Test
    fun `a deny rule on git commit blocks the commit`() {
        assumeTrue("这台机器上没有 git", GitCli.available() != null)
        val r = repo()
        val rules = File.createTempFile("haoai-git-rules-", ".json")
        Policies.reset(rules)
        Policies.get().add(r, Rule("shell", "git commit*", Decision.DENY))
        val ctx = ToolCtx(r, PcSettings(), "auto", SpyGate())
        File(r, "c.txt").writeText("x\n")
        GitTool().runB(args("""{"sub":"add","args":"c.txt"}"""), ctx)
        val blocked = GitTool().runB(args("""{"sub":"commit","args":"-m nope"}"""), ctx)
        assertTrue("规则没拦住 commit：${blocked.content}", blocked.error)
        assertTrue(blocked.content.contains("规则拒绝"))
        assertEquals("commit 居然真进去了", 1, GitCli.out(r, listOf("rev-list", "--count", "HEAD")).trim().toInt())
        Policies.reset(null)
    }

    @Test
    fun `outside a repo the tool says so instead of crashing`() {
        val dir = Files.createTempDirectory("haoai-nogit").toFile().apply { mkdirs() }
        val ctx = ToolCtx(dir, PcSettings(), "auto", SpyGate())
        val r = GitTool().runB(args("""{"sub":"status"}"""), ctx)
        assertTrue("不在仓库里却成功了", r.error)
        assertTrue("没给出下一步：${r.content}", r.content.contains("git init") || r.content.contains("不在 git 仓库"))
    }

    @Test
    fun `unsupported subcommand lists what is allowed`() {
        assumeTrue("这台机器上没有 git", GitCli.available() != null)
        val r = repo()
        val ctx = ToolCtx(r, PcSettings(), "auto", SpyGate())
        val bad = GitTool().runB(args("""{"sub":"filter-branch"}"""), ctx)
        assertTrue(bad.error)
        assertTrue("没提示可用子命令：${bad.content}", bad.content.contains("status"))
    }
}
