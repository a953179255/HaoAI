package com.haoai.pc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * `ask_user_batch` 的契约锁（T2：与手机端同一把尺子）。
 *
 * 结果文本（"用户已按顺序回答 N 题：" + 逐题 "i. 作答"）是历史回显的数据源，
 * 两端各自钉同一条前缀；缺答补位、1~100/2~6 两级校验的错误文案也逐字节同款。
 * 另锁 [Gate.askBatch] 的默认桥：没人覆写时必须逐题落到单题 ask —— CLI 与
 * 测试替身的"零改动跟上"就靠这条。
 */
class AskUserBatchToolTest {

    private class ProbeGate : Gate {
        var seenBatch: Triple<String, List<AskReq>, Boolean>? = null
        var answers: List<String> = emptyList()
        var singleAsked = mutableListOf<String>()
        override fun approve(title: String, detail: String, kind: String) = true
        override fun ask(question: String, options: List<String>): String {
            singleAsked += question
            return options.firstOrNull() ?: ""
        }

        override fun askBatch(title: String, questions: List<AskReq>, allowFree: Boolean): List<String> {
            seenBatch = Triple(title, questions, allowFree)
            return answers
        }
    }

    /** 只覆写单题的老闸：默认桥必须把整批拆给它（这条锁的就是"没被忘掉的退化"）。 */
    private class SingleOnlyGate : Gate {
        val asked = mutableListOf<String>()
        override fun approve(title: String, detail: String, kind: String) = true
        override fun ask(question: String, options: List<String>): String {
            asked += question
            return options.firstOrNull() ?: ""
        }
    }

    private fun args(j: String) = Json.parseToJsonElement(j).jsonObject

    private fun ctx(gate: Gate) = ToolCtx(
        Files.createTempDirectory("haoai-batch-ws").toFile().apply { mkdirs() },
        PcSettings(permissionMode = "auto"), "auto", gate
    )

    private fun run(j: String, gate: Gate) = AskUserBatchTool().runB(args(j), ctx(gate))

    private fun argsOf(n: Int, allowFree: Boolean = false) = buildJsonObject {
        put("title", "性格测试")
        put("allow_free_text", allowFree)
        putJsonArray("questions") {
            repeat(n) { qi ->
                addJsonObject {
                    put("question", "第 ${qi + 1} 题？")
                    putJsonArray("options") {
                        addJsonObject { put("label", "选项A") }
                        addJsonObject { put("label", "选项B") }
                    }
                }
            }
        }
    }.toString()

    @Test
    fun `happy path - result prefix and per-question lines`() {
        val g = ProbeGate().apply { answers = listOf("选项A", "选项B", "选项A") }
        val r = run(argsOf(3), g)
        assertFalse(r.error)
        assertTrue("前缀必须是历史解析的锚点，实际=${r.content}", r.content.startsWith("用户已按顺序回答 3 题："))
        assertTrue(r.content.contains("\n1. 选项A"))
        assertTrue(r.content.contains("\n2. 选项B"))
        assertTrue(r.content.contains("\n3. 选项A"))
        // 题库原样到闸口：标题、题干、desc、开关
        val (title, qs, free) = g.seenBatch ?: error("闸口没收到批量请求")
        assertEquals("性格测试", title)
        assertEquals(3, qs.size)
        assertEquals("第 1 题？", qs[0].question)
        assertEquals(AskOpt("选项A", ""), qs[0].options[0])
        assertEquals(false, free)
    }

    @Test
    fun `gate returns fewer answers - pads with 未作答 so parser always finds every index`() {
        val g = ProbeGate().apply { answers = listOf("选项A") }
        val r = run(argsOf(3), g)
        assertTrue(r.content.contains("\n2. 未作答"))
        assertTrue(r.content.contains("\n3. 未作答"))
    }

    @Test
    fun `question count out of range - error without suspending`() {
        val g = ProbeGate()
        val zero = run(argsOf(0), g)
        assertTrue(zero.error)
        val tooMany = run(argsOf(101), g)
        assertTrue(tooMany.error)
        assertTrue(tooMany.content.contains("1~100"))
        assertEquals("报错时不许惊动闸口", null, g.seenBatch)
    }

    @Test
    fun `single option question - error names the question`() {
        val j = """{"title":"t","questions":[{"question":"坏题？","options":[{"label":"只有它"}]}]}"""
        val r = run(j, ProbeGate())
        assertTrue(r.error)
        assertTrue(r.content.contains("2~6"))
        assertTrue(r.content.contains("坏题"))
    }

    @Test
    fun `default bridge splits the batch into per-question asks`() {
        val g = SingleOnlyGate()
        val r = run(argsOf(2), g)
        assertFalse(r.error)
        assertEquals("默认桥要逐题落到单题 ask（CLI/测试替身零改动）", listOf("第 1 题？", "第 2 题？"), g.asked)
        // 单题闸答的是选项 label → 结果按序拼回整批
        assertTrue(r.content.startsWith("用户已按顺序回答 2 题："))
        assertTrue(r.content.contains("\n1. 选项A"))
    }

    @Test
    fun `batch answers json parses - and garbage falls back to all-unanswered`() {
        assertEquals(listOf("春", "自己写的"), ApprovalBroker.parseBatchAnswers("""["春","自己写的"]"""))
        assertEquals("超时/中止的空串回空表（全部未作答）", emptyList<String>(), ApprovalBroker.parseBatchAnswers(""))
        assertEquals("坏载荷同样回空表，不抛给引擎", emptyList<String>(), ApprovalBroker.parseBatchAnswers("{不是数组"))
        assertEquals(listOf(""), ApprovalBroker.parseBatchAnswers("[\"\"]"))
    }
}
