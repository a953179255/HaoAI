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

/**
 * 设置修改工具（上游 提案式）：模型只能改白名单字段，且每次修改都要用户在审批弹窗确认。
 */
class UpdateSettingsTool(private val mutator: ((JsonObject) -> String)?) : Tool {

    override val name = "update_settings"
    override val description =
        "修改应用设置（每次都会弹窗请用户批准）。可用字段：" +
            "reply_max_tokens(int)：当前云端服务的单次回复上限（建议 4096-32768，解决回答被截断）；" +
            "context_length(int)：当前云端服务的上下文窗口 tokens；" +
            "local_context_length(int 2048-262144)：端侧推理上下文窗口，重启服务生效；" +
            "memory_enabled(bool)：记忆系统总开关；auto_learn(bool)：对话后自动学习；" +
            "deep_dream(bool)：闲置时自动整理记忆。" +
            "用户说「把回复上限调大」「上下文改成 128K」等时使用；一次调用可同时改多个字段。"
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("reply_max_tokens") { put("type", "integer") }
            putJsonObject("context_length") { put("type", "integer") }
            putJsonObject("local_context_length") { put("type", "integer") }
            putJsonObject("memory_enabled") { put("type", "boolean") }
            putJsonObject("auto_learn") { put("type", "boolean") }
            putJsonObject("deep_dream") { put("type", "boolean") }
        }
    }

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult =
        mutator?.invoke(args)?.let { ToolResult(it) } ?: ToolResult("设置修改不可用", true)
}
