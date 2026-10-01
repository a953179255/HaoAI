package com.haoai.pc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Run/Verify 闭环（#17）的回归。
 *
 * 判据盯三件事：
 * 1. **检测/默认构建表是确定性的**——认得出、且没把握时**跳过而不是猜**（猜错的构建命令比不构建更糟）；
 * 2. **整条链逐段有报告**——死在哪一段要在工具结果里看得见；
 * 3. **失败路径也要停干净**——验证没过时起过的长驻进程必须被收掉（端口不能留到下次冲突）。
 */
class RunVerifyTest {

    private class AllowGate : Gate {
        override fun approve(title: String, detail: String, kind: String) = true
        override fun ask(question: String, options: List<String>) = ""
    }

    /** 全局进程表，用例之间清干净。 */
    @org.junit.Before
    fun cleanSlate() {
        ProcRegistry.closeAll()
    }

    private fun ctx(): ToolCtx =
        ToolCtx(Files.createTempDirectory("haoai-rv").toFile().apply { mkdirs() }, PcSettings(), "ask", AllowGate())

    private fun args(json: String) = Json.parseToJsonElement(json).jsonObject

    private fun tmpDir(marker: String): File {
        val d = Files.createTempDirectory("haoai-rv-detect").toFile().apply { mkdirs() }
        if (marker.isNotBlank()) File(d, marker).writeText("{}")
        return d
    }

    @Test
    fun `detect 按工作区文件认类型，认不出就 null`() {
        assertEquals("gradle", RunVerifyTool.detectProject(tmpDir("gradlew.bat")))
        assertEquals("gradle", RunVerifyTool.detectProject(tmpDir("build.gradle.kts")))
        assertEquals("maven", RunVerifyTool.detectProject(tmpDir("pom.xml")))
        assertEquals("npm", RunVerifyTool.detectProject(tmpDir("package.json")))
        assertEquals("cargo", RunVerifyTool.detectProject(tmpDir("Cargo.toml")))
        assertEquals("python", RunVerifyTool.detectProject(tmpDir("requirements.txt")))
        assertNull("空目录必须是 null（猜一个比不猜更糟）", RunVerifyTool.detectProject(tmpDir("")))
    }

    @Test
    fun `默认构建表：没把握就跳过`() {
        val py = tmpDir("")
        assertNull("python 没有统一构建，必须跳过", RunVerifyTool.defaultBuild("python", py))

        val npmPlain = tmpDir("package.json").also { File(it, "package.json").writeText("""{"name":"x"}""") }
        assertNull("没有 scripts.build 的 npm 项目不许硬跑 npm run build", RunVerifyTool.defaultBuild("npm", npmPlain))

        val npmBuilt = tmpDir("package.json").also {
            File(it, "package.json").writeText("""{"scripts":{"build":"tsc"}}""")
        }
        assertEquals("npm run build", RunVerifyTool.defaultBuild("npm", npmBuilt))

        val pnpm = tmpDir("").also {
            File(it, "package.json").writeText("""{"scripts":{"build":"tsc"}}""")
            File(it, "pnpm-lock.yaml").writeText("")
        }
        assertEquals("pnpm run build", RunVerifyTool.defaultBuild("npm", pnpm))

        val gradleW = tmpDir("gradlew.bat")
        assertEquals("gradlew.bat build", RunVerifyTool.defaultBuild("gradle", gradleW))
    }

    @Test
    fun `链路：构建覆盖 + verify_file 逐段报告`() {
        val c = ctx()
        val r = RunVerifyTool().runB(
            args("""{"build":"echo built>run-verify-marker.txt","verify_file":"run-verify-marker.txt"}"""), c
        )
        assertFalse(r.content, r.error)
        assertTrue("检测段要有交代：" + r.content, r.content.contains("检测："))
        assertTrue("构建段要有 ok：" + r.content, r.content.contains("构建：`echo built>run-verify-marker.txt` …ok"))
        assertTrue("验证段要有 ok：" + r.content, r.content.contains("验证：ok"))
        assertTrue("结论要有：" + r.content, r.content.contains("整条链跑通"))
    }

    @Test
    fun `链路：构建失败就地停住不往下走`() {
        val c = ctx()
        val r = RunVerifyTool().runB(
            args("""{"build":"exit 3","verify_file":"never.txt"}"""), c
        )
        assertTrue("构建失败必须标 error：" + r.content, r.error)
        assertTrue("要说清是构建失败：" + r.content, r.content.contains("构建没过"))
        assertFalse("构建失败不许进验证段：" + r.content, r.content.contains("验证：ok"))
    }

    @Test
    fun `链路：启动 → 验证过 → 停止（Ctrl+C 优先）`() {
        assumeTrue("这台机器上没有 Git Bash", ShellLauncher.persistentForName("bash") != null)
        val c = ctx()
        File(c.workspace, "up.marker").writeText("x")
        val r = RunVerifyTool().runB(
            args("""{"start":"while true; do sleep 1; done","verify_file":"up.marker","timeout_ms":8000}"""), c
        )
        assertFalse(r.content, r.error)
        assertTrue("启动段要有 id：" + r.content, r.content.contains("启动：已起 p"))
        assertTrue("验证段要有 ok：" + r.content, r.content.contains("验证：ok"))
        assertTrue("停止段要有交代：" + r.content, r.content.contains("停止："))
        assertTrue("结论要有：" + r.content, r.content.contains("整条链跑通"))
        assertTrue(
            "跑完不许留僵尸：${ProcRegistry.list().map { it.label }}",
            ProcRegistry.list().none { it.label == "run_verify" }
        )
    }

    @Test
    fun `链路：验证失败也要把起过的进程停干净`() {
        assumeTrue("这台机器上没有 Git Bash", ShellLauncher.persistentForName("bash") != null)
        val c = ctx()
        val r = RunVerifyTool().runB(
            args("""{"start":"while true; do sleep 1; done","verify_url":"http://127.0.0.1:9/nothing","timeout_ms":1500}"""), c
        )
        assertTrue("验证失败必须标 error：" + r.content, r.error)
        assertTrue("要说清失败原因：" + r.content, r.content.contains("验证：失败"))
        assertTrue(
            "失败路径也不许留僵尸（端口占着到下次冲突）：${ProcRegistry.list().map { it.label }}",
            ProcRegistry.list().none { it.label == "run_verify" }
        )
    }
}
