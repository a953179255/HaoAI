package com.haoai.agent.agent.tools

import com.haoai.agent.agent.skills.SkillStore
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * 技能工具（自进化）：把走通的多步流程、踩坑经验沉淀为可复用技能。
 * list=索引 / view=全文 / save=创建或更新 / delete=删除。
 */
class SkillTool(private val store: SkillStore) : Tool {

    override val name = "skill"
    override val description =
        "技能库：沉淀与复用做事方法。action: save(name, description, content) 把值得复用的多步流程/踩坑经验沉淀为技能；" +
            "list 列出全部技能索引；view(name) 读全文；delete(name) 删除。" +
            "何时 save：走通了值得复用的多步流程、踩坑后找到可行路径、被用户纠正了做法。" +
            "content 建议结构：When to Use（适用场景）/ Procedure（步骤）/ Pitfalls（坑与注意）。" +
            "沉淀纪律：同一教训只存一条——先 view 检查已有技能，强化旧条目而不是追加复制品；" +
            "环境故障要记『修复方法』而不是『该工具不可用』（环境会变，永久负面断言会误导后续所有任务）；未解决的失败不要存。"
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("action") { put("type", "string") }
            putJsonObject("name") { put("type", "string") }
            putJsonObject("description") { put("type", "string") }
            putJsonObject("content") { put("type", "string") }
        }
    }

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        return when (args.optString("action", "list").lowercase()) {
            "save" -> {
                val name = args.optString("name").trim()
                if (name.isEmpty()) return ToolResult("save 需要 name", true)
                val desc = args.optString("description").trim()
                if (desc.isEmpty()) return ToolResult("save 需要 description（一句话说明该技能何时有用）", true)
                val content = args.optString("content").trim()
                if (content.isEmpty()) return ToolResult("save 需要 content（技能正文）", true)
                store.save(name, desc, content, source = "agent")
                // C 只读技能免确认：晋升结果按正文工具面即时反馈，模型下一步即可复用
                val auto = com.haoai.agent.agent.skills.SkillStore.isReadOnlyBody(content)
                val msg = if (auto) "技能「$name」已保存并生效（只读技能自动启用）。下次遇到同类任务先 view 复用。"
                else "技能「$name」已保存（候选态：含写入/执行/设备操作，待用户在技能库页验证启用）。"
                ToolResult(msg)
            }
            "view" -> {
                val name = args.optString("name").trim()
                if (name.isEmpty()) return ToolResult("view 需要 name", true)
                val text = store.view(name)
                    ?: return ToolResult("技能「$name」不存在；用 list 查看全部", true)
                ToolResult(text)
            }
            "delete" -> {
                val name = args.optString("name").trim()
                if (name.isEmpty()) return ToolResult("delete 需要 name", true)
                if (store.delete(name)) ToolResult("已删除技能「$name」")
                else ToolResult("技能「$name」不存在", true)
            }
            "list" -> {
                val all = store.list()
                if (all.isEmpty()) ToolResult("技能库为空。完成有价值的多步流程后可用 save 沉淀。")
                else ToolResult("共 ${all.size} 个技能：\n" + all.joinToString("\n") { "- ${it.name}：${it.description.take(80)}" })
            }
            else -> ToolResult("未知 action（支持 save/list/view/delete）", true)
        }
    }
}
