package com.haoai.agent.agent.engine.compaction

object CompactionPrompts {

    /** 初始压缩：从头到尾的对话生成结构化摘要。 */
    fun initialSummaryPrompt(context: String, budget: Int): String = """
        你是一个对话摘要助手。请将以下对话压缩为结构化摘要，严格控制在 $budget tokens 以内。

        输出格式（必须严格遵守）：
        ## 目标
        用户的核心目标和意图（一句话）

        ## 进展
        已完成的关键步骤和当前状态（要点列表）

        ## 决策
        做出的重要选择和原因（要点列表）

        ## 下一步
        待执行的下一步操作（要点列表）

        ## 关键上下文
        必须保留的关键信息：文件路径、变量名、配置值、用户偏好等（要点列表）

        要求：
        - 只保留事实和决策，删除寒暄和冗余
        - 代码/命令只保留关键片段
        - 精确字符串原样保留：文件路径、命令、报错原文、URL、变量与配置标识符不得改写或转述
        - 保留用户的明确偏好和要求
        - 凡是没有写进摘要的信息等于丢失——拿不准是否该删时，保留
        - 不要添加你的分析或评论

        对话内容：
        $context
    """.trimIndent()

    /** 增量压缩：将新消息合并到现有摘要中。 */
    fun incrementalSummaryPrompt(existingSummary: String, newMessages: String, budget: Int): String = """
        你是一个对话摘要助手。请将新消息合并到现有摘要中，严格控制在 $budget tokens 以内。

        现有摘要：
        $existingSummary

        新消息：
        $newMessages

        输出格式（必须严格遵守）：
        ## 目标
        用户的核心目标和意图（一句话）

        ## 进展
        已完成的关键步骤和当前状态（要点列表）

        ## 决策
        做出的重要选择和原因（要点列表）

        ## 下一步
        待执行的下一步操作（要点列表）

        ## 关键上下文
        必须保留的关键信息：文件路径、变量名、配置值、用户偏好等（要点列表）

        要求：
        - 将新信息整合进现有摘要，而不是重写
        - 删除已过时的信息
        - 新消息与旧摘要冲突时，以新消息为准
        - 凡是没带进新摘要的内容即视为丢失——重要细节宁可保留不要省略
        - 精确字符串原样保留：文件路径、命令、报错原文、URL、变量与配置标识符不得改写
        - 保留用户的明确偏好和要求
        - 不要添加你的分析或评论
    """.trimIndent()

    /** Fallback：LLM 调用失败时的简单截断摘要。 */
    fun fallbackTruncate(messages: List<String>, maxChars: Int = 4000): String {
        val sb = StringBuilder()
        var total = 0
        for (msg in messages.asReversed()) {
            if (total + msg.length > maxChars) break
            sb.insert(0, msg)
            total += msg.length
        }
        return sb.toString()
    }
}
