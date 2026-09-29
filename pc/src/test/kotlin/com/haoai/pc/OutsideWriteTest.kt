package com.haoai.pc

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * 沙箱第一层：工作区边界。
 *
 * `outside()` 早就在参与风险打分了（v0.59 那批），所以这一层不是"从零加限制"，
 * 而是把 **auto 档**下那条路从"弹卡等人点"改成"当场拒并说清为什么"：
 * 定时任务与任务链都以 auto 档在夜里跑，弹卡等于白等 300 秒然后自动拒，
 * 而模型收到的那句"超时未答，按拒绝处理"分不清是边界问题还是网关问题，于是它会换个写法再试。
 *
 * ask 档刻意**不动**：人在电脑前，弹卡让他点是正确的，凭什么替他决定。
 * 这条也在测试里钉住 —— 不然下一次"顺手收紧"会把人那一条路一起堵掉。
 */
class OutsideWriteTest {

    private class Spy : Gate {
        val asked = mutableListOf<String>()
        val levels = mutableListOf<Risk>()
        var allow = true
        override fun approve(title: String, detail: String, kind: String): Boolean {
            asked += title + "|" + detail
            return allow
        }
        override fun approveRule(
            title: String, detail: String, kind: String, tool: String, pattern: String, risk: RiskOf.Verdict?
        ): Boolean {
            risk?.let { levels += it.level }
            return approve(title, detail, kind)
        }
        override fun ask(question: String, options: List<String>) = options.firstOrNull() ?: ""
    }

    private lateinit var ws: File
    private lateinit var outside: File

    companion object {
        @JvmStatic
        @BeforeClass
        fun setUp() {
            val home = Files.createTempDirectory("haoai-outside-home").toFile()
            System.setProperty("haoai.home", File(home, "home").absolutePath)
            File(home, "home").mkdirs()
        }
    }

    private fun dirs() {
        ws = Files.createTempDirectory("haoai-outside-ws").toFile().apply { mkdirs() }
        // 工作区同级的那个文件就是"外面"：`outside()` 比的是 canonicalPath 前缀
        outside = File(ws.parentFile, "not-in-workspace-${System.nanoTime()}.txt")
    }

    private fun ctx(mode: String, flags: Map<String, Boolean> = emptyMap(), gate: Gate = Spy()) =
        ToolCtx(ws, PcSettings(permissionMode = mode, flags = flags), mode, gate)

    private fun guardWrite(c: ToolCtx, path: String) =
        c.guard("write", path, "写 $path", { "新建或覆盖 $path" })

    @Test
    fun `auto refuses an outside write on the spot without asking anyone`() {
        dirs()
        val gate = Spy()
        val msg = guardWrite(ctx("auto", gate = gate), outside.absolutePath)
        assertTrue("auto 档该拒掉：" + msg, msg != null && msg.contains("工作区之外"))
        assertTrue("要给出下一步（改路径或显式开开关），而且得排在前面——工具结果一行只看得见前九十个字符：" + msg,
            msg!!.indexOf("outside_write") < 60 && msg.contains("改到工作区里"))
        assertTrue("当场拒就不该再弹卡等人（等 = 白占 300 秒）：" + gate.asked, gate.asked.isEmpty())
        assertFalse("拒绝就意味着文件没被写出来", outside.exists())
    }

    @Test
    fun `the switch really lets it through when the user turns it on`() {
        dirs()
        val gate = Spy()
        val msg = guardWrite(
            ctx("auto", mapOf(HaoFlag.OUTSIDE_WRITE.key to true), gate), outside.absolutePath
        )
        assertEquals("开了开关还拦，那个人为的口子就是假的：" + msg, null, msg)
        assertTrue(gate.asked.isEmpty())
    }

    @Test
    fun `ask mode still hands the decision to the human`() {
        dirs()
        val gate = Spy()
        val msg = guardWrite(ctx("ask", gate = gate), outside.absolutePath)
        assertEquals("问人那一档该弹卡（这条不许被顺手改成自动拒）：" + msg, null, msg)
        assertEquals("只该弹一次：" + gate.asked, 1, gate.asked.size)
        assertTrue("卡上要写明在工作区之外：" + gate.asked[0], gate.asked[0].contains("工作区之外"))
        assertEquals("这条仍然是高危（分级是 v0.59 那批的既有行为，别被这批改坏）：" + gate.levels,
            listOf(Risk.HIGH), gate.levels)
    }

    @Test
    fun `ask mode refuses when the human says no`() {
        dirs()
        val gate = Spy().apply { allow = false }
        val msg = guardWrite(ctx("ask", gate = gate), outside.absolutePath)
        assertTrue("人拒了就要回一句拒：" + msg, msg != null && msg.contains("拒绝"))
    }

    @Test
    fun `the switch waives only the outside part, not the whole approval`() {
        dirs()
        // 开关的名字只承诺"允许写到外面"，所以它就只能摘掉"在外面"这一项。
        // 这条挑的是一个**同时落在敏感位置**的路径（RiskOf 里 `.ssh` 那条规则）：
        // 开了开关仍然要问人 —— 不然这个开关就成了"关掉整个审批"的别名。
        val gate = Spy()
        val c = ctx("auto", mapOf(HaoFlag.OUTSIDE_WRITE.key to true), gate)
        val msg = c.guard("write", "C:\\Users\\someone\\.ssh\\id_rsa", "写密钥",
            { "覆盖 id_rsa" })
        assertEquals("敏感位置那条高危不该被开关一起放过：" + msg, null, msg)
        assertEquals("开了开关也要弹一次卡：" + gate.asked, 1, gate.asked.size)
        assertEquals(listOf(Risk.HIGH), gate.levels)
    }

    @Test
    fun `writes inside the workspace are untouched`() {
        dirs()
        val gate = Spy()
        assertEquals("工作区内的普通写不该被这条挡住", null, guardWrite(ctx("auto", gate = gate), "notes/a.txt"))
        assertEquals(null, guardWrite(ctx("ask", gate = gate), "notes/b.txt"))
    }

    @Test
    fun `this layer only covers the write tools — say so instead of pretending`() {
        dirs()
        // 诚实的边界：`shell` 走的是 kind="exec"，这条限制**管不到它**（`echo x > ..\f.txt` 照样能写外面）。
        // 真要那一层得是进程级隔离，本批不做，也别在文案里吹成"已隔离"。
        val c = ctx("auto")
        val msg = c.guard("shell", "echo hi", "跑一条命令", { "echo hi" }, subjectIsPath = false)
        assertEquals("命令这条路口前不该被工作区边界改动：" + msg, null, msg)
    }

    @Test
    fun `media exports to a path outside the workspace are not blocked by this rule`() {
        dirs()
        /*
         * 这条是被全量测试逼出来的：第一版按 `kind == "write"` 判，把 media/record
         * "把产物导到用户指定的输出路径"也一起挡了 —— 而素材库与成片目录在 D:\ 是常态，
         * 视频自动化第一条就撞墙（MediaTest 里那条既有测试直接红）。
         * 边界要挡"改坏别人的文件"，不是"生成一个大文件"。
         */
        val gate = Spy()
        val msg = ctx("auto", gate = gate).guard(
            "media", outside.absolutePath, "导出音频", { "产物写到 " + outside.absolutePath })
        assertEquals("media 导到工作区外不该被这条挡：" + msg, null, msg)
        // 弹一次卡是 v0.59 就有的行为（"在工作区之外"算高危，auto 档也不跳高危），
        // 本批没动它；MediaTest 里那条既有测试跑的也是这个假闸口（它答"允许"）。
        assertEquals("仍然走既有的高危弹卡，不是新规则的当场拒：" + gate.asked, 1, gate.asked.size)
    }
}
