package com.haoai.agent.platform

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 电脑联动请求体构造的转义守卫（M7 修复时新增）。
 *
 * ## 为什么要有这个测试
 *
 * `PcLink` 原先手拼 JSON 字符串，转义helper 只处理 `\` 与 `"`：
 * ```kotlin
 * private fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
 * ```
 * 但 JSON 字符串**不允许裸 `\n` / `\r` / `\t` / <0x20> 控制字符**。
 * 而 `answer(id, text)` 的 text 是用户对电脑上 `ask_user` 提问的自由回答，
 * **换行极其常见**——一旦命中，整个请求体变成非法 JSON。
 *
 * 症状极其隐蔽：发送侧不报错，电脑端解析失败只 `Log.w` 一句，
 * 用户视角就是"点了没反应"。这类问题纯靠代码审查几乎不可能发现第二次。
 *
 * ## 注意：断言绝不能用"解析失败"当判据
 *
 * 原本想用 `assertTrue(runCatching { Json.parse(x) }.isFailure)` 来证明
 * "旧 esc() 产出的是非法 JSON"，结果**两次都判错**：
 * ① 先以为 `isLenient = true` 容忍裸换行；② 改成 `isLenient = false`
 * 后**照样解析成功** —— kotlinx.serialization 对字符串内的裸控制字符
 * 一律宽容，跟 lenient 开关无关。
 *
 * 结论：这个判据在 kotlinx 上恒为假绿，**比没有断言更危险**。
 * 唯一可靠的判据是**规范层面**的硬事实 —— 直接数产出字符串里的裸控制
 * 字符个数：新实现必须 = 0，旧实现必须 > 0。这个不依赖任何解析器行为。
 */
class PcLinkJsonTest {

    /** 用于往返校验（确认内容原样保留），不承担"合法性"判据职责。 */
    private val strict = Json { isLenient = false }

    /** 字符串体（不含引号）里的裸控制字符个数。 */
    private fun rawControlCharCount(json: String): Int =
        json.count { it == '\n' || it == '\r' || it == '\t' || (it < ' ' && it != ' ') }

    @Test
    fun `multi-line answer produces json without raw control chars`() {
        // 用户在电脑上被问"要不要继续"，回答里带换行 —— 最典型的触发场景
        val text = "继续\n第二行\n\n第三行"
        val body = jsonObj("id" to "req-1", "answer" to text)

        assertEquals(
            "新实现不得产出裸控制字符（裸换行会让请求体变成非法 JSON）",
            0,
            rawControlCharCount(body)
        )
        val parsed = strict.parseToJsonElement(body) as JsonObject
        assertEquals(
            "换行必须原样保留（不能被吞掉）",
            text,
            (parsed["answer"] as JsonPrimitive).content
        )
    }

    @Test
    fun `control characters are escaped and survive strict parse`() {
        val nasty = "带\t制表符和控制符的\"引号\"与\\反斜杠"
        val body = jsonObj("text" to nasty)
        assertEquals("控制字符必须被转义", 0, rawControlCharCount(body))
        val parsed = strict.parseToJsonElement(body) as JsonObject
        assertEquals(nasty, (parsed["text"] as JsonPrimitive).content)
    }

    @Test
    fun `old hand-built escaper is proven broken by raw control char check`() {
        // 回归证据：证明旧实现确实会坏，而不是我们多虑了。
        //
        // 判据只用**字符串层面**的裸控制字符，绝不用"解析失败"当判据：
        // kotlinx.serialization 在isLenient=true 和 isLenient=false 下
        // **都会**收下字符串里的裸换行（2026-10-10 实测两次都判错），
        // 所以"跑一遍解析看会不会失败"这种写法在 kotlinx 上恒为假绿 ——
        // 比没有断言更危险。反过来，"产出里有没有裸控制字符"是规范层面
        // 的硬事实，不依赖任何解析器实现，是唯一可靠的判据。
        fun oldEsc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
        val broken = """{"answer":"${oldEsc("第一行\n第二行")}"}"""

        assertTrue(
            "旧 esc() 未处理控制字符，产出里应含裸换行（JSON 规范明令禁止）",
            broken.contains("第一行\n第二行")
        )
        // 反向对照：同样内容经真源构造后裸控制字符归零，证明差异确实来自 esc()
        val fixed = jsonObj("answer" to "第一行\n第二行")
        assertEquals(
            "同一段内容，真源构造的请求体不该有裸控制字符",
            0,
            rawControlCharCount(fixed)
        )
    }
}