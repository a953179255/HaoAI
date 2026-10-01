package com.haoai.agent.agent.tools

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * ask_user_batch 结果格式契约：返回文本是历史回显的唯一数据源
 * （ChatViewModel.askDataOfBatch 按 "用户已按顺序回答 N 题：" + 逐题 "i. 作答" 解析），
 * 改格式必须两边同步——这里把前缀与逐题行锁死。
 */
class AskUserBatchToolTest {

    @get:Rule
    val tmp = org.junit.rules.TemporaryFolder()

    private fun ctx(gate: (suspend (AskUserBatchRequest) -> List<String>)?): ToolContext =
        ToolContext(null, null, com.haoai.agent.agent.tools.TodoStore(tmp.root), tmp.root, askUserBatch = gate)

    private fun argsOf(questions: Int, allowFree: Boolean = false) = buildJsonObject {
        put("title", "性格测试")
        put("allow_free_text", allowFree)
        putJsonArray("questions") {
            repeat(questions) { qi ->
                addJsonObject {
                    put("question", "第 ${qi + 1} 题？")
                    putJsonArray("options") {
                        addJsonObject { put("label", "选项A") }
                        addJsonObject { put("label", "选项B") }
                    }
                }
            }
        }
    }

    private suspend fun run(
        args: kotlinx.serialization.json.JsonObject,
        gate: (suspend (AskUserBatchRequest) -> List<String>)?
    ) = AskUserBatchTool().run(args, ctx(gate))

    @Test
    fun `happy path - result prefix and per-question lines`() = kotlinx.coroutines.runBlocking {
        val res = run(argsOf(3)) { listOf("选项A", "选项B", "选项A") }
        assertFalse(res.error)
        assertTrue(
            "前缀必须是历史解析的锚点，实际=$res",
            res.content.startsWith("用户已按顺序回答 3 题：")
        )
        assertTrue(res.content.contains("\n1. 选项A"))
        assertTrue(res.content.contains("\n2. 选项B"))
        assertTrue(res.content.contains("\n3. 选项A"))
    }

    @Test
    fun `gate returns fewer answers - pads with 未作答 so parser always finds every index`() =
        kotlinx.coroutines.runBlocking {
            val res = run(argsOf(3)) { listOf("选项A") }
            assertTrue(res.content.contains("\n2. 未作答"))
            assertTrue(res.content.contains("\n3. 未作答"))
        }

    @Test
    fun `question count out of range - error without suspending`() = kotlinx.coroutines.runBlocking {
        val zero = run(argsOf(0)) { emptyList() }
        assertTrue(zero.error)
        val tooMany = run(argsOf(101)) { emptyList() }
        assertTrue(tooMany.error)
        assertTrue(tooMany.content.contains("1~100"))
    }

    @Test
    fun `single option question - error names the question`() = kotlinx.coroutines.runBlocking {
        val args = buildJsonObject {
            put("title", "t")
            putJsonArray("questions") {
                addJsonObject {
                    put("question", "坏题？")
                    putJsonArray("options") { addJsonObject { put("label", "只有它") } }
                }
            }
        }
        val res = run(args) { emptyList() }
        assertTrue(res.error)
        assertTrue(res.content.contains("2~6"))
    }

    @Test
    fun `no gate - unattended fallback tells model to assume defaults`() =
        kotlinx.coroutines.runBlocking {
            val res = run(argsOf(2), null)
            // 与单题 ask_user 同约定：兜底是指引不是错误（error=false），模型读文本继续
            assertFalse(res.error)
            assertTrue(res.content.contains("无法向用户答题"))
        }
}
