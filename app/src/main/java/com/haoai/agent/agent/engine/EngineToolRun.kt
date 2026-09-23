package com.haoai.agent.agent.engine

import com.haoai.agent.agent.model.ChatMessage
import com.haoai.agent.agent.model.ToolCallData
import com.haoai.agent.agent.policy.ApprovalRequest
import com.haoai.agent.agent.tools.TextCap
import com.haoai.agent.agent.tools.optBool
import com.haoai.agent.agent.tools.Tool
import com.haoai.agent.agent.tools.ToolContext
import com.haoai.agent.agent.tools.ToolRegistry
import com.haoai.agent.agent.tools.ToolResult
import com.haoai.agent.agent.tools.optInt
import com.haoai.agent.agent.tools.optString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 工具调用的一次执行：审批 → 前置 hook → 执行 → 后置 hook → 落库 + 事件。
 *
 * 从引擎里搬出来的判据同 EngineContext：这一组只在"模型已经点完工具"之后工作，
 * 不推进回合循环本身（轮数、熔断、续跑都在 runTurn 里）。串行与并行两条入口共用
 * finishCall 收尾，正是为了不让两套路径各写一份账本/熔断/落库。
 */
internal suspend fun AgentEngine.executeCall(
    call: ToolCallData,
    tools: List<Tool>,
    ctx: ToolContext,
    onEvent: (TurnEvent) -> Unit
) {
    // P2-5 重放保护：LLM 流瞬态重试（网络波动/看门狗）会把同一轮已执行的工具
    // 序列重新发送。"非 READ 工具 + 同名同参 + 紧邻上一次执行"→ 判定为重放，
    // 返回上次结果不再执行（写类重复可能产生真实副作用）；READ 类放行（连续
    // 滚动/重复读页是合法操作）。
    val replaySig = call.name + "|" + call.argumentsJson.trim()
    val isReplay = replaySig == lastExecutedSig &&
        lastExecutedResult != null &&
        policy.riskOf(call.name) != com.haoai.agent.agent.policy.RiskLevel.READ
    if (isReplay) {
        val prev = lastExecutedResult!!
        onEvent(ToolChanged(ToolUpdate(call.id, ToolRunState.DONE, briefOf(call), "重放跳过：与上一次调用相同，返回已有结果")))
        val storedP = TextCap.middle(prev.content, STORED_CAP)
        appendAndNotify(
            ChatMessage(role = ChatMessage.ROLE_TOOL, content = storedP, toolCallId = call.id, toolName = call.name),
            onEvent
        )
        return
    }

    onEvent(ToolChanged(ToolUpdate(call.id, ToolRunState.RUNNING, briefOf(call))))

    val tool = tools.firstOrNull { it.name == call.name }
    val args = parseArgs(call.argumentsJson)
    // 畸形参数防护（OpenClaw #142176 精神）：解析失败不静默当空参跑——那会把
    // memory save 误变成默认的 list 之类的动作且模型毫不知情。此刻没有任何动作
    // 执行过（replay-safe），给显性错误让模型重新完整调用。走 finishCall 落历史，
    // 模型下一轮就能看到错误并自愈。
    if (args == null) {
        val err = ToolResult(
            "工具参数 JSON 损坏（模型输出被截断或编码错误）：${call.argumentsJson.take(160)}。" +
                "请完整重新调用 ${call.name}，参数必须是合法 JSON。",
            true
        )
        onEvent(ToolChanged(ToolUpdate(call.id, ToolRunState.ERROR, briefOf(call), TextCap.middle(err.content, 80))))
        finishCall(call, buildJsonObject {}, ctx, err, ToolRunState.ERROR, 0L, "direct", onEvent)
        return
    }
    // E7a：按调用注入 callId 与子代理进度上报桥（子代理工具经 parentCtx 回调 → SubagentUpdate 事件）
    val callCtx = subagentBridge(ctx, call, onEvent)

    var result: ToolResult = ToolResult("")
    var finalState: ToolRunState = ToolRunState.DONE
    var toolStartMs = 0L
    var decision: String? = null
    var handledByHook = false

    // 5.6 Plan 模式：READ 之外的工具一律不执行，引导模型产出计划文本
    if (planGate() && tool != null && policy.riskOf(call.name) != com.haoai.agent.agent.policy.RiskLevel.READ) {
        this.planIntercepted = true
        result = ToolResult(
            "[Plan 模式] 工具 ${call.name} 已被拦截（计划阶段不执行改动）。" +
                "请基于已有信息给出完整执行计划：步骤、涉及文件、预期结果，等待用户批准后再执行。",
            false
        )
        finalState = ToolRunState.DONE
        val storedP = TextCap.middle(result.content, STORED_CAP)
        appendAndNotify(
            ChatMessage(role = ChatMessage.ROLE_TOOL, content = storedP, toolCallId = call.id, toolName = call.name),
            onEvent
        )
        onEvent(ToolChanged(ToolUpdate(call.id, ToolRunState.DONE, briefOf(call), "已拦截（Plan 模式）")))
        return
    }

    // E7b before hooks：Plan 门与审批之后、工具执行之前（快照在此拍）。返回 Handled 时直接落库
    var hookFailure: String? = null
    try {
        for (h in hooks) {
            if (h.names.isNotEmpty() && call.name !in h.names) continue
            val d = h.before(call, args, callCtx)
            if (d is ToolHook.HookDecision.Handled) {
                result = d.result
                finalState = if (d.result.isError) ToolRunState.ERROR else ToolRunState.DONE
                decision = "hook"
                handledByHook = true
                break
            }
        }
    } catch (ce: kotlin.coroutines.cancellation.CancellationException) {
        throw ce
    } catch (e: Exception) {
        // fail-closed：before hook（写前快照）没做成就不执行改动。旧行为是打一行 logcat 照常写，
        // 用户以为改错了能 /undo，其实底片没拍到；而真机 Flyme 上 logcat 被全量抑制，那行日志谁也看不见。
        // 快照是回滚的唯一依据，它失败时"停下报错"比"悄悄失去回滚能力"更可接受。
        hookFailure = e.message ?: e.javaClass.simpleName
        android.util.Log.w("HaoEngine", "hook before failed, change blocked: $hookFailure")
    }

    // bash 拦截检查（正则扫描+递归切分，非廉价）：结果存局部变量避免同一命令查两次。
    // 注意必须放在 when 之外：曾经的写法 `call.name == "bash" -> checkShellBlocked(...)?.let{}`
    // 让「未拦截」也命中该分支并使分支体空转 → 工具从不执行、结果恒为空串且 error=false，
    // 表现为所有 bash 调用「无回显」（2026-09-09 6de47f3 引入，2026-09-16 修复），
    // 同时导致拦截规则形同虚设、bash 审批被跳过。
    val shellBlocked: String? =
        if (call.name == "bash") policy.checkShellBlocked(args.optString("command")) else null

    when {
        // fail-closed：before hook 没做成（如写前快照落不下去）就不执行改动，
        // 免得留下"以为能回滚其实不能"的改动。
        hookFailure != null -> {
            result = ToolResult(
                "已取消本次 ${call.name}：执行前检查（写前快照）失败（$hookFailure），" +
                    "继续改动将无法回滚。请清理应用存储空间或检查权限后重试。",
                true
            )
            finalState = ToolRunState.ERROR
            decision = "hook-error"
        }

        // hook 已处理（如 Plan 拦截的等价 case），跳过工具执行
        handledByHook -> Unit

        tool == null -> {
            // E4b：模型可能凭系统提示里的分组描述臆测调用未注入的工具 —— 给出 tools_enable 引导
            result = unknownToolResult(call.name)
            finalState = ToolRunState.ERROR
            decision = "unknown"
        }

        shellBlocked != null -> {
            result = ToolResult(shellBlocked, true)
            finalState = ToolRunState.DENIED
            decision = "blocked"
        }

        // C6/C1 config_set 恒审批：预检（补丁非法 → 免审批直接拒绝）→ 语义 diff 审批 → 同步 apply。
        // 无论权限模式如何都必须经用户批准（配置写不变量，含 permission_mode 自提权场景）。
        call.name == "config_set" -> {
            val preview = configPreview(args)
            if (preview.err != null) {
                result = ToolResult(
                    "配置被拒绝：${preview.err}（未生效；修正参数后重新调用 config_set 即可）",
                    true
                )
                finalState = ToolRunState.ERROR
                decision = "invalid"
            } else {
                val granted = approve(ApprovalRequest.ConfigChange(preview.diff.ifBlank { "（无字段变化）" }))
                if (!granted) {
                    result = ToolResult("用户拒绝了配置修改。", true)
                    finalState = ToolRunState.DENIED
                    decision = "denied"
                } else {
                    decision = "approved"
                    toolStartMs = System.currentTimeMillis()
                    result = invokeTool(tool, args, callCtx)
                    if (result.isError) finalState = ToolRunState.ERROR
                }
            }
        }

        policy.requiresApproval(call.name) -> {
            val request = buildApprovalRequest(call, args)
            val granted = approve(request)
            if (!granted) {
                result = ToolResult("用户拒绝了本次操作。", true)
                finalState = ToolRunState.DENIED
                decision = "denied"
            } else {
                decision = "approved"
                toolStartMs = System.currentTimeMillis()
                result = invokeTool(tool, args, callCtx)
                if (result.isError) finalState = ToolRunState.ERROR
            }
        }

        else -> {
            // YOLO 等免审批模式同样要拍快照（5.5 回滚依赖），否则全自动下写入无档可回
            toolStartMs = System.currentTimeMillis()
            result = invokeTool(tool, args, callCtx)
            if (result.isError) finalState = ToolRunState.ERROR
        }
    }
    // 记录签名供重放保护比对（仅非 READ 工具参与判定）。
    // fail-closed 拦下的那次不算"执行过"：否则用户清出空间后原样重试会被判成重放，
    // 直接返回那条陈旧错误、永远没机会真正写。
    if (hookFailure == null &&
        policy.riskOf(call.name) != com.haoai.agent.agent.policy.RiskLevel.READ
    ) {
        lastExecutedSig = replaySig
        lastExecutedResult = result
    }
    finishCall(call, args, callCtx, result, finalState, toolStartMs, decision, onEvent)
}

/** E7a：按调用注入 callId 与子代理进度上报桥（串行/并行两路共用）。 */
internal fun AgentEngine.subagentBridge(ctx: ToolContext, call: ToolCallData, onEvent: (TurnEvent) -> Unit): ToolContext =
    ctx.copy(
        currentCallId = call.id,
        onSubagentEvent = { report ->
            onEvent(
                SubagentUpdate(
                    call.id, report.id, report.index, report.total,
                    report.state, report.tokensUsed, report.brief
                )
            )
        }
    )

/** E4b：模型可能凭系统提示里的分组描述臆测调用未注入的工具 —— 给出 tools_enable 引导。 */
internal fun AgentEngine.unknownToolResult(name: String): ToolResult {
    val g = ToolRegistry.groupOf(name)
    val groups = session.activeGroups?.toSet()
    return if (groups != null && g != ToolRegistry.GROUP_CORE && g !in groups) {
        ToolResult(
            "工具 $name 属于未启用的「$g」工具组。请先调用 tools_enable（group=\"$g\"）启用后再使用。",
            true
        )
    } else {
        ToolResult("未知工具：$name", true)
    }
}

/**
 * 执行收尾：账本 / E5 连续失败熔断 / E7b after hooks / 结果落库 / 状态事件。
 * 串行路径（executeCall）与并行段（executeParallelCalls）共用；
 * 会在主协程按序执行，禁止放进并发块（session.messages 与事件回调非线程安全）。
 */
internal suspend fun AgentEngine.finishCall(
    call: ToolCallData,
    args: JsonObject,
    callCtx: ToolContext,
    result: ToolResult,
    finalState: ToolRunState,
    toolStartMs: Long,
    decision: String?,
    onEvent: (TurnEvent) -> Unit
) {
    if (toolStartMs > 0) {
        ledgerTool(
            call.name, System.currentTimeMillis() - toolStartMs,
            ok = !result.isError, decision = decision ?: "direct"
        )
    }
    // E7b hooks：E3 失败升级 / 5.7 技能提示 / E7c 写文件校验统一在 after 阶段按列表序执行
    var finalResult = result
    for (h in hooks) {
        if (h.names.isNotEmpty() && call.name !in h.names) continue
        finalResult = h.after(call, args, callCtx, finalResult)
    }
    // B1 空转守卫：观测收尾结果（与 E5 失败熔断并列；在 hooks 之后，保证 finalResult 已定）
    val guardDecision = loopGuard.observe(
        toolName = call.name,
        argsJson = call.argumentsJson,
        resultContent = finalResult.content,
        isError = finalResult.isError,
        isRead = policy.riskOf(call.name) == com.haoai.agent.agent.policy.RiskLevel.READ
    )
    when (guardDecision) {
        is ToolLoopGuard.Decision.None -> Unit
        is ToolLoopGuard.Decision.Warn -> {
            // 引导挂在工具结果尾部：模型下一轮立刻看到，不打断循环
            finalResult = finalResult.copy(
                content = finalResult.content + "\n\n" + guardDecision.message,
                isError = finalResult.isError
            )
            onEvent(
                ToolChanged(
                    ToolUpdate(call.id, finalState, briefOf(call), "空转守卫：检测到重复调用")
                )
            )
        }
        is ToolLoopGuard.Decision.Halt -> {
            finalResult = finalResult.copy(
                content = finalResult.content + "\n\n" + guardDecision.message,
                isError = true
            )
            _loopStallHalt = true
            onEvent(
                ToolChanged(
                    ToolUpdate(call.id, ToolRunState.ERROR, briefOf(call), "空转守卫：强制收尾")
                )
            )
        }
    }

    // E5 连续工具失败熔断：必须排在 hooks 之后判定——conFailCount 的自增发生在
    // EscalationHook.after（EngineHooks.kt:90），先前置读会让阈值 8 拖到第 9 次失败才触发。
    if (toolFailCap > 0 && result.isError && (conFailCount[call.name] ?: 0) >= toolFailCap) {
        _loopFailedCap = true
    }
    var storedContent = TextCap.middle(finalResult.content, STORED_CAP)
    val message = ChatMessage(
        role = ChatMessage.ROLE_TOOL,
        content = storedContent,
        toolCallId = call.id,
        toolName = call.name,
        error = finalResult.isError
    )
    appendAndNotify(message, onEvent)
    // B2：外部内容污点标记（web/search/browser 成功且有正文 → 本回合后续 auto 提取按 untrusted）
    if (!finalResult.isError && call.name in TAINT_TOOLS && finalResult.content.isNotBlank()) {
        turnTainted = true
    }
    // 图像注入通路（4.2 browser_screenshot）：工具结果带图时追加一条 user 图像消息，
    // 复用既有 imageData → image_url 转换，OpenAI/Anthropic 两协议均可消费
    finalResult.imageDataUrl?.let { img ->
        appendAndNotify(
            ChatMessage(
                role = ChatMessage.ROLE_USER,
                content = "[${call.name}] 页面截图（当前视觉状态，供图像分析）",
                imageData = img
            ),
            onEvent
        )
    }
    onEvent(
        ToolChanged(
            ToolUpdate(call.id, finalState, briefOf(call), previewOf(result.content))
        )
    )
}

/** B2：成功返回网络/页面正文的工具名（污染本回合记忆提取的溯源）。 */
private val TAINT_TOOLS = setOf(
    "web_fetch", "web_search",
    "browser_read", "browser_navigate", "browser_find", "browser_screenshot"
)

/**
 * E6 并行执行：READ 白名单调用在 IO 协程并发跑工具体（信号量限 4），
 * 结果按原 calls 顺序在主协程经 finishCall 串行收尾（顺序可回放，零数据竞争）。
 * 单调用退化为完整 executeCall（保留 Plan 门等特判语义）。
 */
/** E6 并行段单次调用的准备态：参数解析在主协程完成，工具体并发执行。 */
internal class PreparedCall(
    val call: ToolCallData,
    val tool: Tool?,
    /** null = 参数 JSON 损坏，执行点必须出显性错误（不得当空参运行）。 */
    val args: JsonObject?,
    val ctx: ToolContext
)

internal suspend fun AgentEngine.executeParallelCalls(
    calls: List<ToolCallData>,
    tools: List<Tool>,
    ctx: ToolContext,
    onEvent: (TurnEvent) -> Unit
) {
    if (calls.isEmpty()) return
    if (calls.size == 1) {
        executeCall(calls.first(), tools, ctx, onEvent)
        return
    }
    val prepared = calls.map { call ->
        PreparedCall(call, tools.firstOrNull { it.name == call.name }, parseArgs(call.argumentsJson), ctx)
    }
    prepared.forEach { p ->
        onEvent(ToolChanged(ToolUpdate(p.call.id, ToolRunState.RUNNING, briefOf(p.call))))
    }
    val gate = Semaphore(PARALLEL_MAX_CONCURRENCY)
    coroutineScope {
        val bodies = prepared.map { p ->
            async(Dispatchers.IO) {
                gate.withPermit { runParallelBody(p) }
            }
        }
        prepared.zip(bodies).forEach { (p, body) ->
            val (result, state, elapsed) = body.await()
            // 参数损坏时 runParallelBody 已产出错误结果；args 传空对象仅为完成历史记录
            finishCall(p.call, p.args ?: buildJsonObject {}, p.ctx, result, state, elapsed, "direct", onEvent)
        }
    }
}

/** E6 并行工具体：白名单调用均为 READ 免审批，直接执行即可。 */
internal suspend fun AgentEngine.runParallelBody(p: PreparedCall): Triple<ToolResult, ToolRunState, Long> {
    if (p.tool == null) return Triple(unknownToolResult(p.call.name), ToolRunState.ERROR, 0L)
    if (p.args == null) {
        return Triple(
            ToolResult(
                "工具参数 JSON 损坏（模型输出被截断或编码错误）：${p.call.argumentsJson.take(160)}。" +
                    "请完整重新调用 ${p.call.name}，参数必须是合法 JSON。",
                true
            ),
            ToolRunState.ERROR, 0L
        )
    }
    val start = System.currentTimeMillis()
    val result = invokeTool(p.tool, p.args, p.ctx)
    return Triple(result, if (result.isError) ToolRunState.ERROR else ToolRunState.DONE, System.currentTimeMillis() - start)
}

internal suspend fun AgentEngine.invokeTool(tool: Tool, args: JsonObject, ctx: ToolContext): ToolResult =
    try {
        // bash（3.3 多后端）允许显式放宽到 600s（长构建），按请求 +20s 余量；其余工具维持 180s
        val budget = if (tool.name == "bash") {
            var t = (args.optInt("timeout_ms") ?: 30_000).coerceIn(1000, 600_000).toLong()
            // 包管理命令与 BashTool 同步保底抬升（引擎先超时会连 dpkg 一起杀，
            // 留 interrupted 锁；toybox/ssh 误抬无害——工具内部自有 exec 超时）
            runCatching {
                val cmd = args["command"]?.jsonPrimitive?.contentOrNull ?: ""
                val floorMs = com.haoai.agent.agent.tools.BashTool.pkgMgmtTimeoutFloorMs(cmd)
                if (floorMs > t) t = floorMs
            }
            t + 20_000L
        } else TOOL_TIMEOUT_MS
        // 工具实现普遍含文件/网络 IO：统一切到 IO 线程，避免卡主线程
        // （browser_* 工具内部自行 withContext(Main) 操作 WebView，嵌套切换安全）
        withTimeout(budget) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                tool.run(args, ctx)
            }
        }
    } catch (ce: kotlin.coroutines.cancellation.CancellationException) {
        // 区分「工具超时」与「用户停止」：超时只作废本次调用，不能静默杀掉整轮任务
        if (ce is kotlinx.coroutines.TimeoutCancellationException) {
            ToolResult("工具执行超时，请拆小任务或加大 timeout 重试", true)
        } else {
            throw ce
        }
    } catch (e: Exception) {
        ToolResult("工具执行失败：${e.message ?: e.javaClass.simpleName}", true)
    }

internal suspend fun AgentEngine.buildApprovalRequest(call: ToolCallData, args: JsonObject): ApprovalRequest =
    when (call.name) {
        "bash" -> ApprovalRequest.ExecOp(args.optString("command"))
        "write" -> {
            val path = args.optString("path")
            val newContent = args.optString("content")
            // 审批时文件尚未被改：现读现算 diff（1.3 审查视图数据源）
            val oldText = runCatching { backend?.readText(path) }.getOrNull()
            val diff = if (oldText != null) {
                com.haoai.agent.ui.common.TextDiff.diffText(oldText, newContent).lines
            } else emptyList()
            ApprovalRequest.WriteOp(
                "write",
                path,
                "${newContent.toByteArray(Charsets.UTF_8).size} 字节内容",
                isNewFile = oldText == null,
                diff = diff
            )
        }
        "edit" -> {
            val path = args.optString("path")
            val old = args.optString("old_string")
            val new = args.optString("new_string")
            val replaceAll = args.optBool("replace_all")
            val oldText = runCatching { backend?.readText(path) }.getOrNull()
            val newText = oldText?.let { if (replaceAll) it.replace(old, new) else it.replaceFirst(old, new) }
            val diff = if (oldText != null && newText != null) {
                com.haoai.agent.ui.common.TextDiff.diffText(oldText, newText).lines
            } else emptyList()
            ApprovalRequest.WriteOp(
                "edit",
                path,
                "- ${old.take(300)}\n+ ${new.take(300)}",
                isNewFile = false,
                diff = diff
            )
        }
        // C1：config_set 审批展示语义 diff（preview 失败时不会走到这里——executeCall 先行拦截）
        "config_set" -> ApprovalRequest.ConfigChange(configChangeSummary(args))
        else -> ApprovalRequest.Generic(call.name, args.toString().take(400))
    }

/** C1 语义 diff：合并补丁 → 同源解析 → 与当前配置对比生成人读变更清单（后台执行，失败给可读原因）。 */
internal suspend fun AgentEngine.configChangeSummary(args: JsonObject): String =
    configPreview.invoke(args).let { p ->
        p.err ?: p.diff.ifBlank { "（无字段变化）" }
    }

/** E4b tools_enable 回调：把组并入会话 activeGroups、持久化并标记主循环重建工具清单。 */
internal fun AgentEngine.enableToolGroup(group: String): String {
    val g = group.trim().lowercase()
    if (g == ToolRegistry.GROUP_CORE) {
        return "core 组是常驻工具组（读写/编辑/bash/搜索/记忆/待办等），无需也无法启用。"
    }
    if (g != ToolRegistry.GROUP_EXTENDED && g != ToolRegistry.GROUP_MCP) {
        return "未知工具组「$group」。可用组：extended（无障碍操作/内置浏览器/虚拟屏/相机/定位/设备工具包/工作流等）、mcp（MCP 外部服务器工具）。"
    }
    val current = session.activeGroups?.toSet() ?: ToolRegistry.ALL_GROUPS
    if (g in current) return "工具组 $g 已处于启用状态。"
    session.activeGroups = (current + g).toSortedSet().toList()
    persist()
    _groupsDirty = true
    return "已启用 $g 工具组，相关工具已注入（本会话保持）。现在可以直接使用该组的工具。"
}
