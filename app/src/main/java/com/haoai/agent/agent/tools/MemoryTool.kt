package com.haoai.agent.agent.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.putJsonArray

class MemoryTool : Tool {

    override val name = "memory"
    override val description =
        "记忆系统（两层）。action: save / journal / search(query) / list / forget(id 或关键词)。" +
            "save=长期记忆库（容量有限，宁缺毋滥）：content 一句话、type 取 preference(用户偏好)/fact(稳定事实)/decision(重要决定)/event(带日期事件)，importance 1-5。" +
            "journal=每日日志（当天发生的事，7 天后过期）：content 一句话、importance 1-5；夜间重要日志(importance>=4)会自动晋升为长期记忆。" +
            "save 只记：用户明确说出的偏好与习惯、纠正你的教训、重要约定与决定、项目长期背景。" +
            "journal 记：今天完成的重要进展、遇到的问题与解决方式、用户交代的事项。" +
            "两者都不要记：闲聊内容、能随时查到的信息、当前会话的临时状态、与已有记录重复的内容。"
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("action") {
                put("type", "string")
                putJsonArray("enum") {
                    add(JsonPrimitive("save"))
                    add(JsonPrimitive("journal"))
                    add(JsonPrimitive("search"))
                    add(JsonPrimitive("list"))
                    add(JsonPrimitive("forget"))
                }
            }
            putJsonObject("content") { put("type", "string") }
            putJsonObject("tags") { put("type", "string") }
            putJsonObject("query") { put("type", "string") }
            putJsonObject("type") { put("type", "string") }
            putJsonObject("importance") { put("type", "integer") }
        }
    }

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val bank = ctx.memoryBank
            ?: return ToolResult("记忆功能未启用（可在设置中开启）", true)
        return when (args.optString("action", "list").lowercase()) {
            "save" -> {
                val content = args.optString("content").trim()
                if (content.isEmpty()) return ToolResult("save 需要 content 参数", true)
                val tags = args.optString("tags").split(',', '，')
                    .map { it.trim() }.filter { it.isNotEmpty() }
                val type = args.optString("type", "fact")
                val importance = args.optInt("importance") ?: 3
                val m = bank.remember(content, tags, type, importance, source = "model")
                if (m == null) {
                    ToolResult(
                        "记忆库已满（${bank.count()}/${bank.capacity()} 条）且没有低价值条目可自动清理。" +
                            "请先调用 forget 删除过时/低价值条目，或用更精炼的表述合并相似记忆后重试。",
                        true
                    )
                } else {
                    // 近冲突提示：词面相近但表述不同的既有记忆，交还模型决定是否要 forget 旧条目
                    val conflicts = bank.nearConflicts(content).filter { it.id != m.id }
                    val hint = if (conflicts.isEmpty()) "" else
                        "\n注意：已有相近记忆 " + conflicts.joinToString("；") {
                            "(id=${it.id}) ${it.content.take(60)}"
                        } + "。若是新信息取代旧情况，请 forget 旧条目；若不冲突请忽略本提示。"
                    ToolResult("已记住（id=${m.id}，共 ${bank.activeCount()} 条记忆）。注意：只沉淀长期有效的信息，勿记琐碎内容。$hint")
                }
            }

            "journal" -> {
                val j = ctx.journal
                    ?: return ToolResult("每日日志未启用", true)
                val content = args.optString("content").trim()
                if (content.isEmpty()) return ToolResult("journal 需要 content 参数", true)
                val importance = args.optInt("importance") ?: 3
                val e = j.append(content, importance, "model")
                if (e == null) ToolResult("该内容今天已记录过")
                else ToolResult(
                    "已记入今日日志（${j.count()} 条）。重要事件请标 importance>=4，夜间固化时会晋升为长期记忆。"
                )
            }

            "search" -> {
                val hits = bank.search(args.optString("query"), 5)
                if (hits.isEmpty()) ToolResult("没有匹配的记忆")
                else ToolResult(hits.joinToString("\n") { "- (${it.id}) ${it.content}" })
            }

            "list" -> {
                val items = bank.all()
                if (items.isEmpty()) ToolResult("记忆为空")
                else ToolResult(
                    "共 ${items.size} 条：\n" + items.take(30)
                        .joinToString("\n") { "- (${it.id}) ${it.content.take(120)}" }
                )
            }

            "forget" -> {
                val n = bank.forget(args.optString("query").ifBlank { args.optString("content") })
                if (n == 0) ToolResult("未找到匹配的记忆", true)
                else ToolResult("已删除 $n 条记忆")
            }

            else -> ToolResult("未知 action", true)
        }
    }
}
