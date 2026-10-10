package com.haoai.agent.provider

import com.haoai.agent.agent.provider.AnthropicClient
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M13：`output_config.effort` 的型号门控守卫。
 *
 * ## 为什么这个门控必须存在
 *
 * 修复前`AnthropicClient.chatStream` 接收 `reasoningEffort` 形参后
 * **全函数体无引用** —— 用户在设置里配了思考等级、界面显示已配置、
 * 实际请求里没有，静默丢弃。
 *
 * 但"直接补上字段"是更糟的修法：Anthropic 的字段名是
 * `output_config.effort`，**不是** OpenAI 的 `reasoning_effort`，写错立刻 400；
 * 而且并非所有 Claude 型号都支持（老型号只有 `thinking.budget_tokens` 形态）。
 * 在老型号上发effort = 把"静默丢弃"换成"每次请求都报错"，用户体感更差。
 *
 * 所以判据必须精确到型号。这里锁的每一条都来自官方 Effort 页的
 * 支持/不支持列表，不是推测。
 */
class AnthropicEffortSupportTest {

    private fun supports(model: String) = AnthropicClient.supportsEffort(model)

    // ---------- 官方明确支持的型号 ----------

    @Test
    fun `official supported models pass the gate`() {
        val supported = listOf(
            "claude-opus-5", "claude-opus-5-5",
            "claude-opus-4-8", "claude-opus-4-7", "claude-opus-4-6",
            "claude-opus-4-5",// 唯一支持 effort 的 extended-thinking-only 型号
            "claude-sonnet-5", "claude-sonnet-4-6",
            "claude-fable-5", "claude-mythos-5", "claude-mythos-5-1"
        )
        supported.forEach { assertTrue("$it 应放行", supports(it)) }
    }

    // ---------- 官方明确不支持的型号：发出去就是 400 ----------

    @Test
    fun `legacy models are blocked`() {
        val blocked = listOf(
            "claude-opus-4-1", "claude-opus-4", "claude-opus-3",
            "claude-sonnet-4-5", "claude-sonnet-4", "claude-sonnet-3-7",
            "claude-haiku-4-5", "claude-haiku-3-5",
            "claude-3-5-sonnet-20241022", "claude-3-opus-20240229"
        )
        blocked.forEach { assertFalse("$it 必须拦下（只有 thinking.budget_tokens 形态）", supports(it)) }
    }

    // ---------- 回归：曾把日期当成版本号 ----------

    @Test
    fun `date suffix is not mistaken for version number`() {
        // 最初的判据写成 (family)[-_.]?(\d+)，引擎会跳过 "3-5" 去匹配
        // "sonnet-20241022"，把日期当 major（20241022 >= 5 ⇒ 放行）。
        // 结果是明确不支持的老型号被放行 → 真发出去必400。
        // 单看代码发现不了，靠离线扫 26 个用例才抓到。
        assertFalse("3.5 系日期后缀不得当成 sonnet-5", supports("claude-3-5-sonnet-20241022"))
        assertFalse("3.7 系同理", supports("claude-3-7-sonnet-20250219"))
        assertFalse("opus 4 的日期后缀同理", supports("claude-opus-4-20250514"))
    }

    @Test
    fun `date suffix does not break supported models`() {
        // 反向：剥掉日期不能把支持型号误判成不支持。
        assertTrue(supports("claude-sonnet-4-6-20260101"))
        assertTrue(supports("claude-opus-4-5-20251101"))
        assertTrue(supports("claude-opus-5@20260101"))
        assertTrue(supports("us.anthropic.claude-sonnet-4-6-v1"))
    }

    // ---------- 非 Anthropic / 异常输入：默认保守 ----------

    @Test
    fun `non-anthropic and malformed models are blocked`() {
        listOf("gpt-4o", "gemini-2-5-pro", "", "sonnet", "claude", "my-local-model")
            .forEach { assertFalse("$it 不应放行", supports(it)) }
    }
}