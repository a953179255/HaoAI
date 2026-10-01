package com.haoai.pc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * `ask_user` 的契约锁（B22：与手机端同一把尺子）。
 *
 * 量的是三件事：
 * 1. **参数面**——对象选项、2~4 校验（字符串载荷按 0 个拒）、三个开关的缺省 true；
 * 2. **闸口载荷**——desc/开关原样到 [Gate.ask]（界面按它们渲染徽标与确认步）；
 * 3. **结果前缀**——「用户选择了：/用户回答：/用户未给出有效回答」三支，
 *    界面回显已答卡、模型续跑的语气都吃这个前缀，改一个字都是契约漂移。
 */
class AskUserToolTest {

    private class ProbeGate : Gate {
        var seen: AskReq? = null
        var answer: String = ""
        override fun approve(title: String, detail: String, kind: String) = true
        override fun ask(question: String, options: List<String>) = answer
        override fun ask(req: AskReq): String {
            seen = req
            return answer
        }
    }

    private fun args(j: String) = Json.parseToJsonElement(j).jsonObject

    private fun ctx(gate: Gate) = ToolCtx(
        Files.createTempDirectory("haoai-ask-ws").toFile().apply { mkdirs() },
        PcSettings(permissionMode = "auto"), "auto", gate
    )

    private fun run(j: String, gate: ProbeGate): ToolResult =
        AskUserTool().runB(args(j), ctx(gate))

    @Test
    fun `options reach the gate as objects with descriptions and default flags`() {
        val g = ProbeGate().apply { answer = "绿色" }
        val r = run(
            """{"question":"要哪种主题色？","options":[{"label":"绿色","description":"护眼"},{"label":"蓝色","description":"冷静"}]}""",
            g
        )
        assertFalse("正常提问不该报错", r.error)
        assertEquals("选中选项走前缀分支", "用户选择了：绿色", r.content)
        val req = g.seen ?: error("闸口没收到请求")
        assertEquals("要哪种主题色？", req.question)
        assertEquals(listOf(AskOpt("绿色", "护眼"), AskOpt("蓝色", "冷静")), req.options)
        assertEquals("缺省三开关都是 true", true, req.allowFree)
        assertEquals("缺省三开关都是 true", true, req.confirm)
        assertEquals("缺省三开关都是 true", true, req.recommend)
    }

    @Test
    fun `confirm and recommend false ride through`() {
        val g = ProbeGate().apply { answer = "蓝色" }
        run(
            """{"question":"哪张图好看？","options":[{"label":"绿色"},{"label":"蓝色"}],"confirm":false,"recommend":false,"allow_free_text":false}""",
            g
        )
        val req = g.seen ?: error("闸口没收到请求")
        assertEquals(false, req.confirm)
        assertEquals(false, req.recommend)
        assertEquals(false, req.allowFree)
    }

    @Test
    fun `free text answer takes the other branch`() {
        val g = ProbeGate().apply { answer = "两个都行，你定" }
        val r = run(
            """{"question":"选哪个？","options":[{"label":"甲"},{"label":"乙"}]}""",
            g
        )
        assertEquals("用户回答：两个都行，你定", r.content)
    }

    @Test
    fun `blank answer falls back to the default-assumption branch`() {
        val g = ProbeGate().apply { answer = "" }
        val r = run(
            """{"question":"选哪个？","options":[{"label":"甲"},{"label":"乙"}]}""",
            g
        )
        assertEquals("用户未给出有效回答；请按默认假设继续并在回复中说明", r.content)
        assertTrue("空答案要标 error=false：这不是工具故障", !r.error)
    }

    @Test
    fun `string options are rejected with the same 2-4 count error as mobile`() {
        val g = ProbeGate()
        val r = run("""{"question":"选哪个？","options":["绿色","蓝色"]}""", g)
        assertTrue("字符串载荷要报错", r.error)
        assertEquals("options 需要 2~4 个互斥选项（收到 0 个），请修正后重试", r.content)
        assertEquals("报错时不许惊动闸口", null, g.seen)
    }

    @Test
    fun `option count outside 2-4 is rejected`() {
        val g = ProbeGate()
        val one = run("""{"question":"选？","options":[{"label":"唯一"}]}""", g)
        assertTrue(one.error)
        assertTrue("错误要说清收到几个：" + one.content, one.content.contains("收到 1 个"))
        val five = run(
            """{"question":"选？","options":[{"label":"a"},{"label":"b"},{"label":"c"},{"label":"d"},{"label":"e"}]}""",
            g
        )
        assertTrue(five.error)
        assertTrue(five.content.contains("收到 5 个"))
        assertEquals("报错时不许惊动闸口", null, g.seen)
    }

    @Test
    fun `blank labels are dropped before the count check`() {
        val g = ProbeGate()
        val r = run(
            """{"question":"选？","options":[{"label":"甲"},{"label":"  "},{"label":""},{"label":"乙"}]}""",
            g
        )
        // 4 个里两个空白 → 剩 2 个合法，能问
        assertFalse(r.error)
        assertEquals(2, g.seen?.options?.size)
    }
}
