package com.haoai.agent.agent.tools
import com.haoai.core.takeSafe
import com.haoai.core.takeLastSafe

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * ask_user：Agent 主动向用户提出带选项的问题并暂停等待回答。
 *
 * 与审批弹窗（PolicyEngine，系统策略触发的 允许/拒绝）不同，这是模型发起的
 * 结构化提问通道：真正的方案分叉、不可逆操作前的偏好确认、缺关键参数时
 * 让用户点选，而不是逼用户打字回复。挂起机制复用审批的 CompletableDeferred
 * 管线（ChatViewModel.pendingAsk）。
 *
 * 结果文本是历史回显的数据源（聊天页按前缀解析渲染已答卡片），改动前缀
 * 必须同步 ChatViewModel.rebuildRows 的解析与 ChainOfThought 的 AskStepCard。
 */
class AskUserTool : Tool {

    override val name = "ask_user"
    override val desc =
        "向用户提出带选项的问题并暂停等待回答（运行挂起，用户点选后继续）。" +
            "遇到分叉、歧义或要替用户做假设时，先调用本工具问清再动手，不要自己猜：" +
            "例——用户说「设个 9 点的闹钟」没说早上还是晚上 → 问；" +
            "「让回答更有创意」可以调温度也可以改提示词 → 问；" +
            "两个方案都可行、删除/覆盖等不可逆操作前 → 问。" +
            "不要用于纯闲聊，也不要连续高频调用。把推荐项放在第一个；用户总可以看到自由输入出口。" +
            "低风险、选错也无代价的问题加 confirm=false 让用户点选即回答，交互更省事；" +
            "问卷/测试等无优劣之分的选择加 recommend=false 隐藏「推荐」徽标；" +
            "连续多题（≥5 题同型问卷/测试）改用 ask_user_batch 整批提交，别一题一调。"

    override val params = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("question") {
                put("type", "string")
                put("description", "完整提问：一句话说清背景与要决定的事，以问号结尾")
            }
            putJsonObject("options") {
                put("type", "array")
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("label") {
                            put("type", "string")
                            put("description", "选项短标签（≤12 字），卡片上直接显示")
                        }
                        putJsonObject("description") {
                            put("type", "string")
                            put("description", "该选项的含义/代价/后果，一行话；可省略")
                        }
                    }
                    put("required", JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive("label"))))
                }
                put("description", "2~4 个互斥选项；推荐项放第一个")
            }
            putJsonObject("allow_free_text") {
                put("type", "boolean")
                put("description", "是否允许用户自由输入其他回答，默认 true")
            }
            putJsonObject("confirm") {
                put("type", "boolean")
                put(
                    "description",
                    "是否需要用户点选后再按确认按钮（防误触）。默认 true。" +
                        "低风险、选错也无代价的事实/偏好选择（如早上还是晚上）设 false：" +
                        "用户点选项即回答、任务立刻继续；" +
                        "删除/覆盖/花钱等不可逆或高代价的分叉必须保持 true"
                )
            }
            putJsonObject("recommend") {
                put("type", "boolean")
                put(
                    "description",
                    "是否给第一个选项标「推荐」徽标，默认 true。" +
                        "仅当你确实倾向该选项时才标；" +
                        "各选项无优劣之分的问题（测试问卷、量表打分、抽签类）设 false，不要标推荐"
                )
            }
        }
        put("required", JsonArray(listOf(
            kotlinx.serialization.json.JsonPrimitive("question"),
            kotlinx.serialization.json.JsonPrimitive("options")
        )))
    }

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val question = args.reqString("question")
        val opts = (args["options"] as? JsonArray)
            ?.mapNotNull { o ->
                (o as? JsonObject)?.let {
                    val label = it.primitive("label")?.contentOrNull?.trim() ?: return@let null
                    if (label.isEmpty()) return@let null
                    AskUserOption(label, it.optString("description").trim())
                }
            }
            .orEmpty()
        if (opts.size < 2 || opts.size > 4) {
            return ToolResult("options 需要 2~4 个互斥选项（收到 ${opts.size} 个），请修正后重试", true)
        }
        val allowFree = args.optBool("allow_free_text", true)
        val confirm = args.optBool("confirm", true)
        val recommend = args.optBool("recommend", true)
        val gate = ctx.askUser ?: return ToolResult(
            "当前运行环境无法向用户提问（后台/定时/工作流任务）。" +
                "请按上下文选择最稳妥的默认方案继续执行，并在最终回复中明确说明你做了该假设。"
        )
        val ans = gate(AskUserRequest(question, opts, allowFree, confirm, recommend))
        return when {
            ans.optionIndex in opts.indices -> ToolResult("用户选择了：${opts[ans.optionIndex].label}")
            ans.freeText.isNotBlank() -> ToolResult("用户回答：${ans.freeText.takeSafe(2000)}")
            else -> ToolResult("用户未给出有效回答；请按默认假设继续并在回复中说明")
        }
    }
}
