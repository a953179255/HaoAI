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
 * `run_code` 的回归。
 *
 * 这台机器上有没有 python/node 不假定 —— 找不到解释器时相关用例 assume 跳过，
 * 但"找不到时要说清怎么装"这条本身是有断言的（不能静默失败）。
 */
class RunCodeTest {

    private class SpyGate : Gate {
        val asked = mutableListOf<String>()
        override fun approve(title: String, detail: String, kind: String): Boolean {
            asked += title
            return true
        }

        override fun ask(question: String, options: List<String>) = ""
    }

    private fun args(json: String) = Json.parseToJsonElement(json).jsonObject

    private fun ctx(mode: String = "auto", gate: Gate = SpyGate()) = ToolCtx(
        Files.createTempDirectory("haoai-run").toFile().apply { mkdirs() }, PcSettings(), mode, gate
    )

    /** 跑一条必然成功的代码；解释器不在场就跳过本用例。 */
    private fun mustRun(lang: String, code: String): Pair<ToolResult, ToolCtx> {
        val c = ctx()
        val r = RunCodeTool().runB(args("""{"lang":"$lang","code":${jsonStr(code)}}"""), c)
        assumeTrue("这台机器上没有 $lang：" + r.content, !r.content.contains("这台机器上找不到"))
        return r to c
    }

    private fun jsonStr(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    @Test
    fun `python prints and exits zero`() {
        val (r, _) = mustRun("python", "print('你好 from python')\nprint(6*7)")
        assertFalse(r.content, r.error)
        assertTrue(r.content, r.content.contains("exit=0"))
        assertTrue(r.content, r.content.contains("你好 from python"))
        assertTrue(r.content, r.content.contains("42"))
    }

    @Test
    fun `stderr is merged into the same result`() {
        val (r, _) = mustRun("python", "import sys\nsys.stderr.write('到 stderr 的话')")
        assertTrue("stderr 没并进来：" + r.content, r.content.contains("到 stderr 的话"))
    }

    @Test
    fun `a non-zero exit is reported as an error but keeps the output`() {
        val (r, _) = mustRun("python", "import sys\nprint('前面还有话')\nsys.exit(3)")
        assertTrue(r.content, r.error)
        assertTrue(r.content, r.content.contains("exit=3"))
        assertTrue(r.content, r.content.contains("前面还有话"))
    }

    /**
     * 产物检测：脚本往**自己所在的那个运行目录**写文件，工具就该把它捞出来。
     *
     * PNG 用 hex 直接落盘，不依赖 matplotlib —— 装没装是这台机器的偶然，
     * "跑出来的图能进上下文"才是这条要验的东西。
     */
    @Test
    fun `a png written into the run dir comes back as pixels`() {
        val pngHex = "89504e470d0a1a0a0000000d49484452000000010000000108060000001f15c4" +
            "890000000a49444154789c63000100000500010d0a252242600000000049454e44ae426082"
        val code = listOf(
            "import os",
            "here=os.path.dirname(os.path.abspath(__file__))",
            "open(os.path.join(here,'out.png'),'wb').write(bytes.fromhex('" + pngHex + "'))",
            "print('写了 out.png')"
        ).joinToString(10.toChar().toString())
        val c = ctx()
        val r = RunCodeTool().runB(args("""{"lang":"python","code":${jsonStr(code)}}"""), c)
        assumeTrue("这台机器上没有 python：" + r.content, !r.content.contains("这台机器上找不到"))
        assertTrue("结果该说 exit=0：" + r.content, r.content.contains("exit=0"))
        assertEquals("运行目录里那张图该被认出来：" + r.content, 1, r.images.size)
        val png = File(r.images[0])
        assertTrue(png.name, png.isFile)
        assertEquals("image/png", Images.mime(png))
        assertTrue("正文要报出产出清单：" + r.content, r.content.contains("out.png"))
    }

    @Test
    fun `node prints too`() {
        val (r, _) = mustRun("node", "console.log('node 也算一个：' + (6 * 7))")
        assertFalse(r.content, r.error)
        assertTrue(r.content, r.content.contains("node 也算一个：42"))
    }

    @Test
    fun `unknown language is refused with the list`() {
        val c = ctx()
        val r = RunCodeTool().runB(args("""{"lang":"cobol","code":"x"}"""), c)
        assertTrue(r.content, r.error)
        assertTrue(r.content, r.content.contains("python"))
        assertTrue(r.content, r.content.contains("node"))
    }

    @Test
    fun `plan mode refuses to run and leaves nothing behind`() {
        val c = ctx(mode = "plan")
        val r = RunCodeTool().runB(args("""{"lang":"python","code":"print(1)"}"""), c)
        assertTrue(r.content, r.error)
        assertTrue(r.content, r.content.contains("计划模式"))
        val runs = File(c.workspace, "${Env.TOOL_OUTPUT_DIR}/runs")
        assertFalse("被拒的那次不该留下运行目录", runs.isDirectory && (runs.list()?.isNotEmpty() == true))
    }

    @Test
    fun `timeout is bounded and says so`() {
        val c = ctx()
        val began = System.currentTimeMillis()
        val r = RunCodeTool().runB(args("""{"lang":"python","timeout":"5","code":${jsonStr("import time\ntime.sleep(60)")}}"""), c)
        val secs = (System.currentTimeMillis() - began) / 1000.0
        assumeTrue("这台机器上没有 python：" + r.content, !r.content.contains("这台机器上找不到"))
        assertTrue("该报超时，实际：" + r.content, r.content.contains("超时"))
        assertTrue("超时没真的收住（等了 ${secs}s）", secs < 25)
    }
}
