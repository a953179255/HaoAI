package com.haoai.agent.agent.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * friendlyErrorText / shortTechDetailShared 的回归锁。
 *
 * 2026-10-25 真机实证（用户任务气泡显示「出错了：\」）：Qoder 网关的错误体
 * message 值以转义引号开头，旧正则 [^"] 在反斜杠处停下，真实原因被吃成
 * 一个 "\"。修法：转义感知捕获 + JSON 反转义 + 退化回落。
 */
class FriendlyErrorTextTest {

    /** message 值以转义引号开头：修复前 detail = "\"，修复后应给出完整消息。 */
    @Test
    fun escapedQuoteMessageIsFullyExtracted() {
        val body = """{"error":{"message":"\"模型不存在\"","type":"invalid_request_error"}}"""
        val detail = friendlyErrorText(body)
        assertTrue("应包含反转义后的完整消息，实际：$detail", detail.contains("模型不存在"))
        assertTrue("不得只剩孤立反斜杠", !detail.endsWith("\\") || detail.length > 2)
    }

    /** 普通 message 提取保持原行为（选词避开 timeout/connection 等关键词分支）。 */
    @Test
    fun plainMessageStillExtracted() {
        val body = """{"error":{"message":"upstream exploded","type":"api_error"}}"""
        assertTrue(friendlyErrorText(body).contains("upstream exploded"))
    }

    /** message 含 JSON 转义字符（\n、\\）时正确反转义。 */
    @Test
    fun escapedCharsUnescaped() {
        val body = """{"error":{"message":"line1\nline2 \\ path"}}"""
        val detail = friendlyErrorText(body)
        assertTrue("应包含 line1，实际：$detail", detail.contains("line1"))
        assertTrue("应包含 line2（反转义后），实际：$detail", detail.contains("line2"))
    }

    /** 无 message 字段：回落到清洗原文。 */
    @Test
    fun noMessageFallsBackToCleanedRaw() {
        val detail = friendlyErrorText("""[{"arg":"x"}] unexpected token""")
        assertTrue(detail.contains("unexpected token"))
    }

    /** 两级提取都退化成标点：宁可给原文也不再给孤立符号。 */
    @Test
    fun degenerateDetailFallsBackToRaw() {
        val detail = friendlyErrorText("""{"message":"\\"}""")
        assertTrue("不得只显示孤立反斜杠", detail != "出错了：\\")
    }

    /** 关键词分支不受影响：403 仍走账号验证提示。 */
    @Test
    fun keywordBranchesUntouched() {
        assertTrue(friendlyErrorText("HTTP 403（Qoder）：forbidden").contains("验证失败"))
        assertTrue(friendlyErrorText("HTTP 429 rate limited").contains("限流"))
    }
}
