package com.haoai.agent.agent.engine.compaction

import kotlinx.serialization.Serializable

@Serializable
data class CompactionSettings(
    /** 触发压缩的阈值（占 contextWindow 的比例，0.0-1.0）。默认 0.5 = 50%。 */
    val triggerThreshold: Float = 0.5f,
    /** 压缩后保留的最近 token 数（用于保持近期上下文完整）。 */
    val keepRecentTokens: Int = 18000,
    /** 摘要占 contextWindow 的比例上限（0.0-1.0）。 */
    val summaryBudgetRatio: Float = 0.20f,
    /** 摘要 token 数下限。 */
    val summaryBudgetMin: Int = 2000,
    /** 摘要 token 数上限。 */
    val summaryBudgetMax: Int = 8000,
    /** 压缩冷却时间（毫秒）。防止压缩失败后立即重试。 */
    val cooldownMs: Long = 30_000L,
    /** 工具输出预修剪：保留每个工具结果的前 N 个字符。0 = 不修剪。 */
    val toolOutputKeepChars: Int = 500
) {
    fun summaryBudget(contextWindow: Int): Int =
        (contextWindow * summaryBudgetRatio).toInt().coerceIn(summaryBudgetMin, summaryBudgetMax)
}
