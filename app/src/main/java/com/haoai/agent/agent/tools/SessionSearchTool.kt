package com.haoai.agent.agent.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * B4 会话内容搜索（READ）：跨历史会话按标题/正文检索，供模型回答"之前聊过…"类问题。
 * 查询语义与 UI 搜索共用 data 层的线性扫描（标题优先 + 首条命中摘要），
 * 不引入 FTS 依赖；结果条数与摘要长度刻意收紧以免撑爆上下文。
 */
class SessionSearchTool(
    private val search: (String) -> List<Pair<String, String>>
) : Tool {

    override val name = "session_search"
    override val description =
        "搜索过往聊天会话（标题与消息正文）。query 为关键词；返回标题命中优先的会话列表（id + 标题 + 摘要）。" +
            "用于回忆用户此前的约定、项目背景或说过的话；不要用它代替 memory（长期记忆）。"
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("query") {
                put("type", "string")
                put("description", "搜索关键词（与用户问题中的专名/主题一致）")
            }
        }
    }

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val q = args.optString("query").trim()
        if (q.isEmpty()) return ToolResult("session_search 需要 query", true)
        val hits = search(q)
        if (hits.isEmpty()) return ToolResult("没有匹配的会话")
        return ToolResult(
            hits.take(8).joinToString("\n") { (title, snip) ->
                "- $title：$snip"
            }
        )
    }
}
