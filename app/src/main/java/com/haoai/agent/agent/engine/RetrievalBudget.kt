package com.haoai.agent.agent.engine

/**
 * P3-B 检索硬预算：一次子代理运行内 web_search / web_fetch 的调用次数上限。
 *
 * 为什么要做成引擎强制而不是提示词：实测（模拟器 + 商汤 glm-5.2）一个 research 子代理
 * 为"统计主流 AI Agent 框架"发了 20 次 web_search（且关键词各不相同，缓存挡不住 widening）、
 * 累计 65,708 prompt tokens / 77.7 秒，而同一条"最多 3-4 次搜索即须收敛"的提示就写在它的
 * 系统提示里——措辞对检索广度没有约束力，只能计数强制。
 */
class RetrievalBudget(
    private val cap: Int,
    private val counted: Set<String> = setOf("web_search", "web_fetch")
) {
    private var used = 0

    /** 放行返回 null；超预算返回该次调用的替代结果文本（不执行、不计为失败）。 */
    fun admit(toolName: String): String? {
        if (toolName !in counted) return null
        if (used >= cap) {
            return "[检索预算已用完（$cap 次）] 停止扩大搜索面。基于已拿到的证据直接给出结论，" +
                "并在结论里明说哪些点没查到；不要再发新的检索。"
        }
        used++
        return null
    }

    val usedCount: Int get() = used
}
