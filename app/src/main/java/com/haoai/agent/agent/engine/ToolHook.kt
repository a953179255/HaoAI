package com.haoai.agent.agent.engine

import com.haoai.agent.agent.model.ToolCallData
import com.haoai.agent.agent.tools.ToolContext
import com.haoai.agent.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject

/**
 * E7b 引擎横切 hook：把写前快照 / 技能自改进提示 / E3 失败升级 / E7c 写文件校验
 * 迁出 executeCall，成为可插拔的 aware hooks。
 *
 * 时机（与 executeCall 原位置语义等价）：
 * - before: Plan 门与审批之后、工具执行之前 —— 可返回 Handled（跳过执行直接落库）。
 * - after: 工具执行完成、E3/账本分析之后、结果落库之前 —— 可改写 result（追加提示）。
 *
 * 行为等价是硬约束：迁移不改变任何现有时序（快照仍在审批后/执行前；提示仍在落库前）。
 */
interface ToolHook {

    /** hook 关注哪些工具；空 = 全工具。多个 hook 按列表序执行。 */
    val names: Set<String>
        get() = emptySet()

    /** before 阶段：返回非 null Handled 时直接作为最终结果（跳过工具执行）；null=继续正常执行。 */
    suspend fun before(
        call: ToolCallData,
        args: JsonObject,
        ctx: ToolContext
    ): HookDecision? = null

    /** after 阶段：在结果落库前改写内容。 */
    suspend fun after(
        call: ToolCallData,
        args: JsonObject,
        ctx: ToolContext,
        result: ToolResult
    ): ToolResult = result

    sealed interface HookDecision {
        data class Handled(val result: ToolResult) : HookDecision
    }
}
