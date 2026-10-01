package com.haoai.agent.agent.tools

import com.haoai.core.takeSafe

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * ask_user_batch：整批问卷/测试题一次提交，等待界面本地循环答完。
 *
 * 与 ask_user 的区别（连续多题场景的专用通道）：
 * - ask_user 是一题一挂起，每题答案作为 tool 结果回模型开一轮——200 题 = 200 次往返；
 * - ask_user_batch 把题库整个塞进一次调用，UI 本地循环出题（选完自动下一题、
 *   不回模型），答完所有题才一次性回传——同样的题量只占 1 次模型往返。
 *
 * 结果文本（"用户已按顺序回答 N 题：" + 逐题 "i. 作答"）是历史回显的数据源，
 * 改格式必须同步 ChatViewModel.askDataOfBatch 的解析与 ChainOfThought 的卡片渲染。
 */
class AskUserBatchTool : Tool {

    override val name = "ask_user_batch"
    override val desc =
        "整批问卷/测试题一次提交并挂起等待：界面本地循环出题，用户选完自动跳下一题、" +
            "全程不回模型，答完所有题一次性把全部答案返回给你（N 题只占 1 次往返）。" +
            "连续多题的同型问法用本工具：性格测试、满意度调查、知识测验、偏好批量收集等 ≥5 题的题组。" +
            "题组较长时分批提交（每次 30~50 题，答完一批再提交下一批）。" +
            "单个决策分叉/一次性提问仍用 ask_user。" +
            "题目与选项描述是写给用户看的：不要夹带内部记分、题号编排等元信息。"

    override val params = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("title") {
                put("type", "string")
                put("description", "题组标题，显示在卡片头部（如「MBTI 性格测试」）")
            }
            putJsonObject("questions") {
                put("type", "array")
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("question") {
                            put("type", "string")
                            put("description", "题干，一句话，以问号或句号结尾")
                        }
                        putJsonObject("options") {
                            put("type", "array")
                            putJsonObject("items") {
                                put("type", "object")
                                putJsonObject("properties") {
                                    putJsonObject("label") {
                                        put("type", "string")
                                        put("description", "选项短标签（≤12 字）")
                                    }
                                    putJsonObject("description") {
                                        put("type", "string")
                                        put("description", "该选项含义的一行补充，可省略")
                                    }
                                }
                                put(
                                    "required",
                                    kotlinx.serialization.json.JsonArray(
                                        listOf(kotlinx.serialization.json.JsonPrimitive("label"))
                                    )
                                )
                            }
                            put("description", "2~6 个互斥选项，各题可不等长")
                        }
                    }
                    put(
                        "required",
                        kotlinx.serialization.json.JsonArray(
                            listOf(
                                kotlinx.serialization.json.JsonPrimitive("question"),
                                kotlinx.serialization.json.JsonPrimitive("options")
                            )
                        )
                    )
                }
                put("description", "1~100 道题，按出题顺序排列")
            }
            putJsonObject("allow_free_text") {
                put("type", "boolean")
                put("description", "每题是否提供「其他…（自由输入）」出口，默认 false")
            }
        }
        put(
            "required",
            kotlinx.serialization.json.JsonArray(
                listOf(
                    kotlinx.serialization.json.JsonPrimitive("title"),
                    kotlinx.serialization.json.JsonPrimitive("questions")
                )
            )
        )
    }

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val title = args.reqString("title").takeSafe(40)
        val allowFree = args.optBool("allow_free_text", false)
        val rawQuestions = (args["questions"] as? JsonArray).orEmpty()
        if (rawQuestions.isEmpty() || rawQuestions.size > 100) {
            return ToolResult(
                "questions 需要 1~100 道题（收到 ${rawQuestions.size} 道），请修正后重试",
                true
            )
        }
        val questions = rawQuestions.mapIndexed { qi, q ->
            val obj = q as? JsonObject
                ?: return ToolResult("questions[$qi] 不是对象，无法解析", true)
            val text = obj.primitive("question")?.contentOrNull?.trim().orEmpty()
            if (text.isEmpty()) return ToolResult("questions[$qi].question 不能为空", true)
            val opts = (obj["options"] as? JsonArray)
                ?.mapNotNull { o ->
                    (o as? JsonObject)?.let {
                        val label = it.primitive("label")?.contentOrNull?.trim() ?: return@let null
                        if (label.isEmpty()) return@let null
                        AskUserOption(label, it.optString("description").trim())
                    }
                }
                .orEmpty()
            if (opts.size < 2 || opts.size > 6) {
                return ToolResult(
                    "questions[$qi]（${text.takeSafe(16)}…）需要 2~6 个互斥选项，收到 ${opts.size} 个",
                    true
                )
            }
            AskUserBatchQuestion(text, opts)
        }
        val gate = ctx.askUserBatch ?: return ToolResult(
            "当前运行环境无法向用户答题（后台/定时/工作流任务）。" +
                "请按上下文选择最稳妥的默认方案继续执行，并在最终回复中明确说明你做了该假设。"
        )
        val answers = gate(AskUserBatchRequest(title, questions, allowFree))
        // UI 保证答满才完成 gate；这里仍做对齐兜底（每题必须有作答）
        val filled = questions.mapIndexed { i, q ->
            answers.getOrNull(i)?.takeIf { it.isNotBlank() } ?: "未作答"
        }
        return ToolResult(
            "用户已按顺序回答 ${filled.size} 题：" + filled.mapIndexed { i, a ->
                "\n${i + 1}. ${a.takeSafe(200)}"
            }.joinToString("")
        )
    }
}
