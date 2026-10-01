package com.haoai.agent.agent.engine

import com.haoai.agent.agent.model.ToolCallData
import com.haoai.agent.agent.provider.ApiMessage
import com.haoai.agent.agent.provider.SseEvent
import com.haoai.agent.agent.tools.TextCap
import com.haoai.agent.agent.tools.ToolContext
import com.haoai.agent.agent.tools.ToolRegistry
import com.haoai.agent.agent.tools.ToolResult
import com.haoai.agent.agent.tools.toApi
import com.haoai.agent.agent.tools.toolCallToApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import java.io.File

/**
 * 子代理运行时：主代理派出的独立小循环（research 只读 / work 可写）。
 * 与主循环共享工具与 hook，但不共享会话历史——它只带回一段结论文本，
 * 这是"上下文隔离"的全部机制，也是它比再开一个模型请求贵的地方（每轮重建上下文）。
 */
/** 运行单个子代理（E7a + P1/P2/P3）：独立工具集+轮数上限，逐工具进度上报、
 *  失败/终止回收部分结果、转录落盘（.haoai-jobs/subagent_<id>.md），可被 stop/steer 干预。
 *  P3 mode=work：主代理工具面（继承权限，写盘快照+审批闸同主循环），research 维持只读。 */
internal suspend fun AgentEngine.runSubAgent(
    task: String,
    parentCtx: ToolContext,
    index: Int = 1,
    total: Int = 1,
    mode: String = "research",
    id: String? = null
): String {
    val fid = id ?: "sa_${subagentSeq.incrementAndGet()}"
    // 计划模式：work 子代理会执行改动，与 Plan 语义冲突，直接拒绝
    if (mode == "work" && planGate()) {
        return "计划模式下不派出 work 子代理（会执行改动）；请先结束计划阶段再派。"
    }
    val handle = SubagentHandle(
        fid, parentCtx.currentCallId, index, total, task,
        currentCoroutineContext()[kotlinx.coroutines.Job] ?: kotlinx.coroutines.Job()
    )
    activeSubagents[fid] = handle
    var subPrompt = 0L
    var subCompletion = 0L
    val subStart = System.currentTimeMillis()
    fun report(state: String, tokens: Long, brief: String) {
        try {
            parentCtx.onSubagentEvent?.invoke(
                com.haoai.agent.agent.engine.SubagentReport(fid, index, total, state, tokens, brief)
            )
        } catch (_: Exception) {
        }
    }
    // P1.3 转录落盘：过程写到工作区 .haoai-jobs/subagent_<id>.md（agent 可用 read 复查；
    //  shellDir 为空=SAF 目录时静默跳过）。返回结果尾注。
    fun transcriptTail(status: String): String {
        val dir = parentCtx.shellDir?.let { java.io.File(it, ".haoai-jobs") } ?: return ""
        return runCatching {
            dir.mkdirs()
            val f = java.io.File(dir, "subagent_$fid.md")
            f.writeText(
                buildString {
                    appendLine("# 子代理 $id（$status）")
                    appendLine("- 任务：$task")
                    appendLine("- 时间：${System.currentTimeMillis()} · index $index/$total")
                    if (handle.steps.isNotEmpty()) {
                        appendLine()
                        appendLine("## 已完成步骤")
                        handle.steps.forEach { appendLine("- $it") }
                    }
                    if (handle.lastAssistant.isNotBlank()) {
                        appendLine()
                        appendLine("## 最近中间结论")
                        appendLine(handle.lastAssistant.take(2000))
                    }
                }
            )
            "\n\n（过程日志：.haoai-jobs/subagent_$fid.md）"
        }.getOrDefault("")
    }
    // P3：childCtx 用 copy 全量继承（work 模式的 config 通道/webCache 复用）；depth+1 且去嵌套上报
    val childCtx = parentCtx.copy(depth = parentCtx.depth + 1, onSubagentEvent = null)
    // E7a 进度上报（经 parentCtx 回调；失败也上报 ERROR，不拖垮整卡）
    report("RUNNING", 0, task.take(80))
    // P3-A：work 模式=主代理工具面（depth+1 已天然排除 spawn 防嵌套；再减配置/清单纪律项），
    // research 维持只读清单
    val tools = if (mode == "work") {
        ToolRegistry.build(childCtx, null, session.activeGroups?.toSet(), null)
            .filter { it.name !in setOf("config_set", "tools_enable", "todo") }
    } else ToolRegistry.readOnly(childCtx)
    val apiTools = gateTools(tools.map { it.toApi() })
    // 注意：不更新引擎级 _toolsTokenCache——那是主循环工具清单的开销估算，
    // 子代理只读工具集远小于主清单，覆写会让压缩/催办判断在本轮剩余时间持续低估

    val msgs = mutableListOf(
        ApiMessage(role = "system", content = if (mode == "work") SUBAGENT_WORK_SYSTEM else SUBAGENT_SYSTEM),
        ApiMessage(role = "user", content = task)
    )
    var finalText = ""
    var turns = 0
    var hitTurnCap = false
    var subOk = true
    // P3-B 检索硬预算：research 子代理强制计数；work 模式不夹（它可能确实要反复抓取）
    val retrievalBudget = RetrievalBudget(if (mode == "research") SUB_RETRIEVAL_CAP else Int.MAX_VALUE)
    // 子代理这段独立会话内的 tool_call id 台账：撞号会让步骤显示与配对判定跨轮串名
    val usedCallIds = HashSet<String>()
    while (turns++ < SUB_MAX_TURNS) {
        currentCoroutineContext().ensureActive()
        // P2 steer：主代理的纠偏指令在子代理下一轮开始前注入（不打断当前执行）
        handle.steering.poll()?.let { m ->
            msgs.add(ApiMessage(role = "user", content = "[主代理插话] $m"))
        }
        val subTranscript = TurnTranscript()
        var calls: List<ToolCallData> = emptyList()
        // 子代理的模型请求单独走一次，失败不再直接判 ERROR：
        // 主循环有 5/12/25s 瞬态退避，子代理此前一次网关抖动/429 就废掉整路调研
        //（spawn_agents 并行时表现为兄弟路正常、这一路凭空 ERROR）
        suspend fun attemptOnce() {
            subTranscript.reset()
            calls = emptyList()
            httpClient.chatStream(provider, apiKey, msgs, apiTools, effectiveEffort()).collect { ev ->
                when (ev) {
                    is SseEvent.Delta -> subTranscript.delta(ev.text)
                    is SseEvent.Reasoning -> Unit
                    is SseEvent.Completed -> calls = uniquifyCallIds(ev.toolCalls, usedCallIds)
                    is SseEvent.Usage -> { subPrompt += ev.promptTokens; subCompletion += ev.completionTokens }
                }
            }
        }
        try {
            var backoff = 0
            while (true) {
                try {
                    attemptOnce()
                    break
                } catch (e: Exception) {
                    if (!isTransientHttpError(e) || backoff >= SUB_BACKOFFS_SEC.size) throw e
                    if (backoff == 0) report("RUNNING", subPrompt + subCompletion, "网络波动，退避重试中")
                    delay(SUB_BACKOFFS_SEC[backoff] * 1000L)
                    backoff++
                    currentCoroutineContext().ensureActive()
                }
            }
        } catch (ce: CancellationException) {
            val ourStop = handle.state == "STOPPING"
            if (!ourStop) throw ce
            // stop_agent 的终止：部分结果挂在句柄上，spawn 层回收后返回主代理
            handle.state = "STOPPED"
            subOk = false
            ledgerLlm("subagent", subPrompt, subCompletion, System.currentTimeMillis() - subStart, ok = false)
            report("STOPPED", subPrompt + subCompletion, "已终止（${handle.steps.size} 步已回收）")
            handle.finalResult = "子代理 ${handle.id} 被终止。\n\n${handle.partialSummary()}"
            transcriptTail("被终止")
            throw ce
        } catch (e: Exception) {
            subOk = false
            handle.state = "ERROR"
            ledgerLlm("subagent", subPrompt, subCompletion, System.currentTimeMillis() - subStart, ok = false)
            report("ERROR", subPrompt + subCompletion, "失败：${e.message ?: e.javaClass.simpleName}")
            transcriptTail("失败：${e.message ?: e.javaClass.simpleName}")
            // P1.2 失败不白干：回收已完成步骤与中间结论（而非裸错误）
            val failOut = "子代理执行失败：${e.message ?: e.javaClass.simpleName}\n\n${handle.partialSummary()}"
            handle.finalResult = failOut
            return failOut
        }
        finalText = subTranscript.textString()
        handle.lastAssistant = finalText
        if (calls.isEmpty()) break
        msgs.add(
            ApiMessage(
                role = "assistant",
                content = finalText.ifBlank { null },
                toolCalls = calls.map { toolCallToApi(it.id, it.name, it.argumentsJson) }
            )
        )
        for (call in calls) {
            // 协作停止检查点（job.cancel 也会在下个挂起点生效，双保险）
            if (handle.state == "STOPPING") break
            currentCoroutineContext().ensureActive()
            // P1.1 逐工具进度：子代理此刻在干什么直接上任务卡
            handle.currentTool = briefOf(call)
            report("RUNNING", subPrompt + subCompletion, "工具 ${call.name} · ${TextCap.middle(call.argumentsJson, 60)}")
            val tool = tools.firstOrNull { it.name == call.name }
            val childArgs = parseArgs(call.argumentsJson)
            // P3-A work 模式：before hooks（写盘快照，回滚依赖）与主循环同源
            var hookHandled: ToolResult? = null
            if (mode == "work" && tool != null && childArgs != null) {
                runCatching {
                    for (h in hooks) {
                        if (h.names.isNotEmpty() && call.name !in h.names) continue
                        val d = h.before(call, childArgs, childCtx)
                        if (d is ToolHook.HookDecision.Handled) {
                            hookHandled = d.result
                            break
                        }
                    }
                }
            }
            // P3-B 预算用完：不执行、不当失败（避免污染 E5 连续失败熔断），只回一条收敛指令
            val overBudget = retrievalBudget.admit(call.name)
            val result = try {
                withTimeout(TOOL_TIMEOUT_MS) {
                    when {
                        overBudget != null -> ToolResult(overBudget)
                        tool == null -> ToolResult("未知工具：${call.name}", true)
                        // 子代理同样不得拿损坏参数当空参跑（畸形参数显性报错）
                        childArgs == null -> ToolResult(
                            "工具参数 JSON 损坏（截断/编码错误）：${call.argumentsJson.take(120)}。请完整重新调用 ${call.name}。",
                            true
                        )
                        hookHandled != null -> hookHandled
                        // P3-A work 模式审批闸：与主循环同策略（ASK_WRITES/ALWAYS_ASK 下逐次审批）
                        mode == "work" && policy.requiresApproval(call.name) -> {
                            val granted = approve(buildApprovalRequest(call, childArgs))
                            if (!granted) ToolResult("用户拒绝了本次操作。", true)
                            else tool.run(childArgs, childCtx)
                        }
                        else -> tool.run(childArgs, childCtx)
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                ToolResult("失败：${e.message ?: e.javaClass.simpleName}", true)
            }
            handle.currentTool = null
            // P1.2 步骤留痕：失败/终止时的部分结果由此构成
            handle.steps.add(
                "${call.name}(${TextCap.middle(call.argumentsJson, 60)}) → ${TextCap.middle(result.content, 90)}" +
                    if (result.error) " [错误]" else ""
            )
            report("RUNNING", subPrompt + subCompletion, "已完成 ${call.name}（第 ${handle.steps.size} 步）")
            msgs.add(
                ApiMessage(
                    role = "tool",
                    content = TextCap.middle(result.content, 6000),
                    toolCallId = call.id,
                    name = call.name
                )
            )
        }
        if (handle.state == "STOPPING") break
        if (turns >= SUB_MAX_TURNS && calls.isNotEmpty()) hitTurnCap = true
    }
    handle.state = if (subOk) "DONE" else "ERROR"
    ledgerLlm("subagent", subPrompt, subCompletion, System.currentTimeMillis() - subStart, ok = subOk)
    report("DONE", subPrompt + subCompletion, finalText.ifBlank { "（未给出结论）" }.take(120))
    val logNote = transcriptTail("完成")
    val capNote = if (hitTurnCap) {
        "\n\n（注意：达到 $SUB_MAX_TURNS 轮上限被截断，以上为部分结论。已完成步骤：\n" +
            handle.steps.joinToString("\n- ", prefix = "- ") + "）"
    } else ""
    val out = finalText.ifBlank { "子代理未给出结论" } + capNote + logNote
    handle.finalResult = out
    return out
}

/** 任务卡终止按钮通路：终止单个运行中的子代理（部分结果随 spawn 调用回收）。 */
internal fun AgentEngine.stopSubagent(id: String): Boolean = subagentControl.stop(id)
