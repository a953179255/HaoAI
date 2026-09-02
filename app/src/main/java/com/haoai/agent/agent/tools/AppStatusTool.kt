package com.haoai.agent.agent.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * 自我状态查询（上游 session_status 式）：按需读取，不占每轮系统提示 token。
 * 数据由 ChatViewModel 注入的 statusProvider 动态渲染。
 */
class AppStatusTool(private val statusProvider: (() -> String)?) : Tool {

    override val name = "app_status"
    override val description =
        "查询你自身（HaoAI 应用）的运行状态。返回：当前模型与供应商、上下文窗口、单次回复上限、" +
            "本会话/今日/历史累计 token 消耗、长期记忆与技能数量、应用版本等。" +
            "当用户问「你是什么模型」「今天用了多少 token」「你的上下文多大」「你还记得多少东西」这类" +
            "关于你自身的问题时，必须调用本工具读取真实数据后回答，严禁凭空编造数字。"
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {}
    }

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val text = statusProvider?.invoke()
        return if (text.isNullOrBlank()) ToolResult("运行状态暂不可用", true)
        else ToolResult(text)
    }
}
