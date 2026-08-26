package com.haoai.agent.agent.engine

import com.haoai.agent.agent.memory.DailyJournal
import com.haoai.agent.agent.memory.MemoryBank
import com.haoai.agent.agent.model.ChatMessage
import com.haoai.agent.agent.model.ToolCallData
import com.haoai.agent.agent.policy.ApprovalRequest
import com.haoai.agent.agent.policy.PolicyEngine
import com.haoai.agent.agent.provider.ApiMessage
import com.haoai.agent.agent.provider.OpenAiCompatClient
import com.haoai.agent.agent.provider.SseEvent
import com.haoai.agent.agent.tools.SubAgentRunner
import com.haoai.agent.agent.tools.TodoStore
import com.haoai.agent.agent.tools.TextCap
import com.haoai.agent.agent.tools.Tool
import com.haoai.agent.agent.tools.ToolContext
import com.haoai.agent.agent.tools.ToolRegistry
import com.haoai.agent.agent.tools.ToolResult
import com.haoai.agent.agent.tools.optDouble
import com.haoai.agent.agent.tools.optInt
import com.haoai.agent.agent.tools.optString
import com.haoai.agent.agent.tools.toApi
import com.haoai.agent.agent.tools.toolCallToApi
import com.haoai.agent.data.HaoJson
import com.haoai.agent.data.ProviderConfig
import com.haoai.agent.data.StoredSession
import com.haoai.agent.data.toModel
import com.haoai.agent.data.toStored
import com.haoai.agent.platform.FileBackend
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AgentEngine(
    private val httpClient: OpenAiCompatClient,
    private val provider: ProviderConfig,
    private val apiKey: String,
    private val customPrompt: String,
    private val policy: PolicyEngine,
    private val approve: suspend (ApprovalRequest) -> Boolean,
    private val session: StoredSession,
    private val persist: () -> Unit,
    private val backend: FileBackend?,
    private val appFilesDir: File,
    private val workspaceLabel: String,
    private val memoryBank: MemoryBank? = null,
    private val memoryEnabled: Boolean = true,
    private val journal: DailyJournal? = null,
    private val autoLearn: Boolean = true,
    private val reasoningEffort: String = "",
    private val okHttpClient: okhttp3.OkHttpClient? = null,
    private val appContext: android.content.Context? = null,
    private val identity: String = "",
    private val onUsage: (suspend (Long, Long) -> Unit)? = null,
    private val depth: Int = 0,
    private val backgroundScope: CoroutineScope? = null,
    /** 动态渲染自身运行状态（app_status 工具按需读取，不注入系统提示）。 */
    private val statusProvider: () -> String = { "" },
    /** 白名单设置修改（update_settings 工具，走用户审批）。 */
    private val configMutator: (JsonObject) -> String = { "设置修改不可用" }
) {

    private val todoStore = TodoStore(appFilesDir)

    suspend fun runTurn(
        userText: String,
        onDelta: (String) -> Unit,
        onEvent: (TurnEvent) -> Unit,
        imageData: String? = null,
        onReasoning: (String) -> Unit = {}
    ) {
        appendAndNotify(
            ChatMessage(
                role = ChatMessage.ROLE_USER,
                content = if (imageData != null) "[图片]\n$userText".trim() else userText,
                imageData = imageData
            ),
            onEvent
        )

        val shellDir = backend?.shellWorkdir()
            ?: File(appFilesDir, "shell-home").apply { mkdirs() }
        val ctx = ToolContext(
            backend, shellDir, todoStore, appFilesDir,
            memoryBank, journal, depth, okHttpClient, appContext,
            statusProvider = statusProvider,
            configMutator = configMutator
        )
        val subAgentRunner: SubAgentRunner? =
            if (depth == 0) SubAgentRunner { task, parentCtx -> runSubAgent(task, parentCtx) } else null
        val tools = ToolRegistry.build(ctx, subAgentRunner) + com.haoai.agent.agent.tools.HandoffTool { summary ->
            runCatching {
                journal?.append(handoffEvent(summary), importance = 3, source = "handoff")
            }
            compactHistory(summary)
        }
        val apiTools = tools.map { it.toApi() }

        val streamBuf = StringBuilder()
        try {
            var turns = 0
            while (true) {
                currentCoroutineContext().ensureActive()
                if (++turns > MAX_TURNS) {
                    appendAndNotify(
                        ChatMessage(
                            role = ChatMessage.ROLE_ASSISTANT,
                            content = "已达单次任务轮数上限（$MAX_TURNS）。请拆分任务或开新会话继续。"
                        ),
                        onEvent
                    )
                    break
                }

                streamBuf.setLength(0)
                val reasoningBuf = StringBuilder()
                var calls: List<ToolCallData> = emptyList()

                maybeNudgeHandoff(onEvent)

                httpClient.chatStream(provider, apiKey, buildApiMessages(), apiTools, reasoningEffort.ifBlank { null })
                    .collect { ev ->
                        when (ev) {
                            is SseEvent.Delta -> {
                                streamBuf.append(ev.text)
                                onDelta(ev.text)
                            }
                            is SseEvent.Reasoning -> {
                                reasoningBuf.append(ev.text)
                                onReasoning(ev.text)
                            }
                            is SseEvent.Completed -> calls = ev.toolCalls
                            is SseEvent.Usage -> onUsage?.invoke(ev.promptTokens.toLong(), ev.completionTokens.toLong())
                        }
                    }

                if (streamBuf.isNotBlank() || calls.isNotEmpty()) {
                    appendAndNotify(
                        ChatMessage(
                            role = ChatMessage.ROLE_ASSISTANT,
                            content = streamBuf.toString(),
                            toolCalls = calls,
                            reasoning = reasoningBuf.toString().ifBlank { null }
                        ),
                        onEvent
                    )
                }
                currentCoroutineContext().ensureActive()

                if (calls.isEmpty()) break

                for (call in calls) {
                    currentCoroutineContext().ensureActive()
                    executeCall(call, tools, ctx, onEvent)
                }
            }
            onEvent(Finished(null))
            maybeExtractMemory()
        } catch (ce: CancellationException) {
            if (streamBuf.isNotBlank()) {
                appendAndNotify(
                    ChatMessage(
                        role = ChatMessage.ROLE_ASSISTANT,
                        content = streamBuf.toString() + "\n\n*[已停止]*"
                    ),
                    onEvent
                )
            }
            onEvent(Finished("已停止"))
            throw ce
        } catch (e: Exception) {
            val msg = ChatMessage(
                role = ChatMessage.ROLE_ASSISTANT,
                content = "出错了：${e.message ?: e.javaClass.simpleName}",
                error = true
            )
            appendAndNotify(msg, onEvent)
            onEvent(Finished(e.message ?: "未知错误"))
        }
    }

    private suspend fun executeCall(
        call: ToolCallData,
        tools: List<Tool>,
        ctx: ToolContext,
        onEvent: (TurnEvent) -> Unit
    ) {
        onEvent(ToolChanged(ToolUpdate(call.id, ToolRunState.RUNNING, briefOf(call))))

        val tool = tools.firstOrNull { it.name == call.name }
        val args = parseArgs(call.argumentsJson)

        var result: Pair<String, Boolean>
        var finalState: ToolRunState = ToolRunState.DONE

        when {
            tool == null -> {
                result = "未知工具：${call.name}" to true
                finalState = ToolRunState.ERROR
            }

            call.name == "bash" && policy.checkShellBlocked(args.optString("command")) != null -> {
                result = (policy.checkShellBlocked(args.optString("command")) ?: "被拦截") to true
                finalState = ToolRunState.DENIED
            }

            // update_settings 属于配置写操作：无论权限模式如何都必须经用户批准（上游 提案式）
            policy.requiresApproval(call.name) || call.name == "update_settings" -> {
                val request = buildApprovalRequest(call, args)
                val granted = approve(request)
                if (!granted) {
                    result = "用户拒绝了本次操作。" to true
                    finalState = ToolRunState.DENIED
                } else {
                    result = invokeTool(tool, args, ctx)
                    if (result.second) finalState = ToolRunState.ERROR
                }
            }

            else -> {
                result = invokeTool(tool, args, ctx)
                if (result.second) finalState = ToolRunState.ERROR
            }
        }

        val storedContent = TextCap.middle(result.first, STORED_CAP)
        val message = ChatMessage(
            role = ChatMessage.ROLE_TOOL,
            content = storedContent,
            toolCallId = call.id,
            toolName = call.name,
            error = result.second
        )
        appendAndNotify(message, onEvent)
        onEvent(
            ToolChanged(
                ToolUpdate(call.id, finalState, briefOf(call), previewOf(result.first))
            )
        )
    }

    private suspend fun invokeTool(tool: Tool, args: JsonObject, ctx: ToolContext): Pair<String, Boolean> =
        try {
            // 工具实现普遍含文件/网络 IO：统一切到 IO 线程，避免卡主线程
            withTimeout(TOOL_TIMEOUT_MS) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    tool.run(args, ctx)
                }
            }.let { it.content to it.isError }
        } catch (ce: kotlin.coroutines.cancellation.CancellationException) {
            // 区分「工具超时」与「用户停止」：超时只作废本次调用，不能静默杀掉整轮任务
            if (ce is kotlinx.coroutines.TimeoutCancellationException) {
                ("工具执行超时（${TOOL_TIMEOUT_MS / 1000}s），请拆小任务或加大 timeout 重试") to true
            } else {
                throw ce
            }
        } catch (e: Exception) {
            ("工具执行失败：${e.message ?: e.javaClass.simpleName}") to true
        }

    private suspend fun runSubAgent(task: String, parentCtx: ToolContext): String {
        val childCtx = ToolContext(
            parentCtx.backend, parentCtx.shellDir, parentCtx.todoStore,
            parentCtx.appFilesDir, parentCtx.memoryBank, parentCtx.journal, parentCtx.depth + 1,
            parentCtx.httpClient, parentCtx.appContext,
            statusProvider = parentCtx.statusProvider
        )  // httpClient/appContext 随 parentCtx 透传；子代理只读工具集，不注册相机定位与设置修改
        val tools = ToolRegistry.readOnly(childCtx)
        val apiTools = tools.map { it.toApi() }

        val msgs = mutableListOf(
            ApiMessage(role = "system", content = SUBAGENT_SYSTEM),
            ApiMessage(role = "user", content = task)
        )
        var finalText = ""
        var turns = 0
        while (turns++ < SUB_MAX_TURNS) {
            currentCoroutineContext().ensureActive()
            val buf = StringBuilder()
            var calls: List<ToolCallData> = emptyList()
            httpClient.chatStream(provider, apiKey, msgs, apiTools, reasoningEffort.ifBlank { null }).collect { ev ->
                when (ev) {
                    is SseEvent.Delta -> buf.append(ev.text)
                    is SseEvent.Reasoning -> Unit
                    is SseEvent.Completed -> calls = ev.toolCalls
                    is SseEvent.Usage -> Unit
                }
            }
            finalText = buf.toString()
            if (calls.isEmpty()) break
            msgs.add(
                ApiMessage(
                    role = "assistant",
                    content = finalText.ifBlank { null },
                    toolCalls = calls.map { toolCallToApi(it.id, it.name, it.argumentsJson) }
                )
            )
            for (call in calls) {
                currentCoroutineContext().ensureActive()
                val tool = tools.firstOrNull { it.name == call.name }
                val result = try {
                    withTimeout(TOOL_TIMEOUT_MS) {
                        tool?.run(parseArgs(call.argumentsJson), childCtx)
                    } ?: ToolResult("未知工具：${call.name}", true)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    ToolResult("失败：${e.message ?: e.javaClass.simpleName}", true)
                }
                msgs.add(
                    ApiMessage(
                        role = "tool",
                        content = TextCap.middle(result.content, 6000),
                        toolCallId = call.id,
                        name = call.name
                    )
                )
            }
        }
        return finalText.ifBlank { "子代理未给出结论" }
    }

    private fun memorySnippet(): String =
        if (memoryEnabled && depth == 0) memoryBank?.promptSnippet().orEmpty() else ""

    private fun journalSnippet(): String =
        if (memoryEnabled && depth == 0) journal?.promptSnippet().orEmpty() else ""

    /** 从五段式交接文档提取「已完成/下一步」要点，作为当日事件落盘。 */
    private fun handoffEvent(summary: String): String {
        val picked = mutableListOf<String>()
        var section = ""
        for (raw in summary.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("#") -> section = line.trimStart('#').trim()
                line.startsWith("-") && (section.contains("已完成") || section.contains("下一步")) ->
                    picked.add(line.drop(1).trim())
            }
        }
        val body = picked.joinToString("；").take(200).ifBlank {
            summary.replace(Regex("[#*`>]"), "").lineSequence()
                .filter { it.isNotBlank() }.joinToString("；").take(180)
        }
        return "任务进展：$body"
    }

    private fun maybeExtractMemory() {
        val bank = memoryBank ?: return
        val scope = backgroundScope ?: return
        if (!memoryEnabled || !autoLearn || depth != 0) return
        val recent = session.messages
            .filter {
                (it.role == ChatMessage.ROLE_USER || it.role == ChatMessage.ROLE_ASSISTANT) &&
                    it.content.isNotBlank()
            }
            .takeLast(8)
        // 门槛：没有实质用户输入（纯指令/太短）就不值得沉淀，直接跳过
        val userText = recent.lastOrNull { it.role == ChatMessage.ROLE_USER }?.content.orEmpty()
        if (userText.length < 12) return
        val transcript = recent
            .joinToString("\n") { m ->
                val who = if (m.role == ChatMessage.ROLE_USER) "用户" else "助手"
                "$who：${m.content.take(500)}"
            }
            .trim()
        if (transcript.length < 80) return
        scope.launch {
            runCatching {
                val buf = StringBuilder()
                httpClient.chatStream(
                    provider, apiKey,
                    listOf(
                        ApiMessage(role = "system", content = EXTRACT_SYSTEM),
                        ApiMessage(role = "user", content = TextCap.middle(transcript, 4000))
                    ),
                    emptyList()
                ).collect { ev -> if (ev is SseEvent.Delta) buf.append(ev.text) }
                parseMemories(buf.toString()).take(2).forEach { (content, tags) ->
                    bank.remember(content, tags, importance = 2)
                }
            }
        }
    }

    private fun parseMemories(text: String): List<Pair<String, List<String>>> {
        val start = text.indexOf('[')
        val end = text.lastIndexOf(']')
        if (start < 0 || end <= start) return emptyList()
        return runCatching {
            val arr = HaoJson.json.parseToJsonElement(text.substring(start, end + 1)).jsonArray
            arr.mapNotNull { el ->
                val obj = el as? JsonObject ?: return@mapNotNull null
                val content = obj["content"]?.jsonPrimitive?.contentOrNull?.trim() ?: return@mapNotNull null
                if (content.isEmpty()) return@mapNotNull null
                val tags = runCatching {
                    obj["tags"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
                }.getOrDefault(emptyList())
                content.take(300) to tags
            }
        }.getOrDefault(emptyList())
    }

    private fun buildApiMessages(): List<ApiMessage> {
        val shellAvailable = backend?.shellWorkdir() != null
        val dateText = SimpleDateFormat("yyyy-MM-dd EEEE", Locale.CHINA).format(Date())
        val systemText = SystemPrompt.PREFIX +
            SystemPrompt.buildSuffix(
                workspaceLabel, shellAvailable, dateText, customPrompt, memorySnippet(),
                a11yAvailable = com.haoai.agent.platform.a11y.HaoAccessibilityService.connected(),
                identity = identity,
                skillIndex = com.haoai.agent.agent.skills.SkillStore.promptIndex(),
                journalBlock = journalSnippet()
            )

        // 配对感知裁剪：窗口切割可能把 assistant(tool_calls) 切掉却留下它的 tool 结果，
        // OpenAI 兼容端会以 400 拒绝孤儿 tool 消息（且重试复现，会话就此卡死）。
        val history = session.messages.asReversed()
            .take(MAX_HISTORY)
            .asReversed()
            .pairSanitized()
            .mapNotNull { m ->
                when (m.role) {
                    ChatMessage.ROLE_USER ->
                        if (!m.imageData.isNullOrBlank()) ApiMessage(
                            role = "user",
                            content = null,
                            parts = listOf(
                                com.haoai.agent.agent.provider.ApiContentPart(type = "text", text = m.content),
                                com.haoai.agent.agent.provider.ApiContentPart(
                                    type = "image_url",
                                    imageUrl = com.haoai.agent.agent.provider.ApiImageUrl(url = m.imageData)
                                )
                            )
                        )
                        else ApiMessage(role = "user", content = m.content)
                    ChatMessage.ROLE_ASSISTANT -> {
                        if (m.content.isBlank() && m.toolCalls.isEmpty()) null
                        else ApiMessage(
                            role = "assistant",
                            content = m.content.ifBlank { null },
                            toolCalls = m.toolCalls.map {
                                toolCallToApi(it.id, it.name, it.argumentsJson)
                            }.ifEmpty { null }
                        )
                    }
                    ChatMessage.ROLE_TOOL -> ApiMessage(
                        role = "tool",
                        content = TextCap.middle(m.content, REQ_CAP),
                        toolCallId = m.toolCallId,
                        name = m.toolName
                    )
                    else -> null
                }
            }
        return listOf(ApiMessage(role = "system", content = systemText)) + history
    }

    private fun appendAndNotify(msg: ChatMessage, onEvent: (TurnEvent) -> Unit) {
        session.messages.add(msg.toStored())
        persist()
        onEvent(MessageAdded(session.messages.last().toModel()))
    }

    /**
     * 历史接近上限时，注入一次性提示让模型主动调用 handoff 压缩上下文。
     * 以会话中是否已存在交接文档作为「只提示一次」的依据。
     */
    private fun maybeNudgeHandoff(onEvent: (TurnEvent) -> Unit) {
        val msgs = session.messages
        if (msgs.size < HANDOFF_NUDGE_AT) return
        val recentHasDoc = msgs.takeLast(HANDOFF_NUDGE_AT / 2)
            .any { it.role == ChatMessage.ROLE_USER && it.content.startsWith(HANDOFF_MARKER) }
        val recentNudged = msgs.takeLast(6).any {
            it.role == ChatMessage.ROLE_USER && it.content.contains("handoff")
        }
        if (recentHasDoc || recentNudged) return
        appendAndNotify(
            ChatMessage(
                role = ChatMessage.ROLE_USER,
                content = "[系统提示] 对话历史即将超出上下文窗口。请立即调用 handoff 工具，" +
                    "用五段式（目标/约束/已完成/关键决定/下一步）总结当前任务，然后继续执行。"
            ),
            onEvent
        )
    }

    /** 用交接文档替换早期历史：保留文档 + 最近几条消息。 */
    private fun compactHistory(summary: String) {
        val msgs = session.messages
        if (msgs.size <= HANDOFF_KEEP) return
        val doc = ChatMessage(
            role = ChatMessage.ROLE_USER,
            content = "$HANDOFF_MARKER\n$summary\n\n（以上为此前对话的压缩交接，请基于它继续当前任务）"
        )
        val kept = msgs.takeLast(HANDOFF_KEEP).pairSanitized()
        msgs.clear()
        msgs.add(doc.toStored())
        msgs.addAll(kept)
        persist()
    }

    /**
     * 剔除因裁剪/压缩而失去配对的消息：
     * 1) 没有对应 assistant(tool_calls) 的孤儿 tool 结果；
     * 2) 其全部调用都缺少结果的 assistant(tool_calls)。
     * 保证发给供应商的历史始终满足严格的调用配对约束。
     */
    private fun List<com.haoai.agent.data.StoredMessage>.pairSanitized(): List<com.haoai.agent.data.StoredMessage> {
        val calledIds = flatMap { if (it.role == ChatMessage.ROLE_ASSISTANT) it.toolCalls.map { c -> c.id } else emptyList() }
            .toSet()
        val step1 = filterNot { it.role == ChatMessage.ROLE_TOOL && (it.toolCallId == null || it.toolCallId !in calledIds) }
        val answeredIds = step1.filter { it.role == ChatMessage.ROLE_TOOL }.mapNotNull { it.toolCallId }.toSet()
        return step1.filterNot { m ->
            m.role == ChatMessage.ROLE_ASSISTANT && m.toolCalls.isNotEmpty() &&
                m.toolCalls.all { it.id !in answeredIds }
        }
    }

    private fun parseArgs(json: String): JsonObject =
        runCatching {
            HaoJson.json.parseToJsonElement(json.ifBlank { "{}" })
        }.getOrNull() as? JsonObject ?: buildJsonObject {}

    private fun buildApprovalRequest(call: ToolCallData, args: JsonObject): ApprovalRequest =
        when (call.name) {
            "bash" -> ApprovalRequest.ExecOp(args.optString("command"))
            "write" -> ApprovalRequest.WriteOp(
                "write",
                args.optString("path"),
                "${args.optString("content").toByteArray(Charsets.UTF_8).size} 字节内容"
            )
            "edit" -> ApprovalRequest.WriteOp(
                "edit",
                args.optString("path"),
                "- ${args.optString("old_string").take(300)}\n+ ${args.optString("new_string").take(300)}"
            )
            "update_settings" -> ApprovalRequest.Generic(
                "update_settings",
                settingsChangeSummary(args)
            )
            else -> ApprovalRequest.Generic(call.name, args.toString().take(400))
        }

    /** 把 update_settings 的参数翻译成人可读的变更清单，供审批弹窗展示。 */
    private fun settingsChangeSummary(args: JsonObject): String {
        val labels = mapOf(
            "reply_max_tokens" to "单次回复上限",
            "context_length" to "云端上下文窗口",
            "local_context_length" to "端侧上下文窗口",
            "memory_enabled" to "记忆系统",
            "auto_learn" to "自动学习",
            "deep_dream" to "闲置整理记忆"
        )
        return args.entries.joinToString("\n") { (k, v) ->
            val label = labels[k] ?: k
            when (v) {
                is kotlinx.serialization.json.JsonPrimitive -> "$label → ${v.content}"
                else -> "$label → $v"
            }
        }.ifBlank { "无变更" }.take(400)
    }

    private fun briefOf(call: ToolCallData): String {
        val args = parseArgs(call.argumentsJson)
        return when (call.name) {
            "bash" -> args.optString("command").lineSequence().firstOrNull()?.take(90) ?: ""
            "read", "write", "edit" -> args.optString("path")
            "grep" -> "/${args.optString("pattern")}/"
            "glob" -> args.optString("pattern")
            "web_fetch" -> args.optString("url")
            "web_search" -> args.optString("query")
            "todo" -> args.optString("action", "view") +
                args.optString("text").takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
            "memory" -> args.optString("action", "list") +
                (args.optString("content").ifBlank { args.optString("query") })
                .takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
            "screen" -> "读取屏幕"
            "tap" -> args.optInt("index")?.let { "[$it]" }
                ?: args.optString("text").ifBlank { args.optString("view_id") }
                .ifBlank { "(${args.optString("x")},${args.optString("y")})" }
            "swipe" -> "滑动"
            "scroll" -> "滚动 ${args.optString("direction", "down")}"
            "find" -> "查找「${args.optString("text")}」"
            "wait" -> "等待 ${args.optString("mode", "text")}" +
                args.optString("text").takeIf { it.isNotBlank() }?.let { "「$it」" }.orEmpty()
            "type_text" -> "输入：${args.optString("text").take(40)}"
            "key" -> args.optString("action")
            "launch_app" -> args.optString("package")
            "list_apps" -> "列出应用"
            "schedule" -> args.optString("action", "list") +
                args.optString("name").takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
            "spawn_agent" -> args.optString("task").take(60)
            "spawn_agents" -> "${(args["tasks"] as? kotlinx.serialization.json.JsonArray)?.size ?: 0} 个并行子任务"
            "app_status" -> "读取运行状态"
            "update_settings" -> settingsChangeSummary(args).lineSequence().firstOrNull() ?: "修改设置"
            "camera" -> "拍照"
            "location" -> "获取当前位置"
            else -> ""
        }
    }

    private fun previewOf(content: String): String =
        content.lineSequence().firstOrNull()?.take(160) ?: ""

    companion object {
        const val MAX_TURNS = 60
        const val MAX_HISTORY = 80
        const val TOOL_TIMEOUT_MS = 180_000L
        const val STORED_CAP = 16_000
        const val REQ_CAP = 4_000
        const val SUB_MAX_TURNS = 10
        const val HANDOFF_NUDGE_AT = 56
        const val HANDOFF_KEEP = 6
        const val HANDOFF_MARKER = "## 任务交接文档"

        val EXTRACT_SYSTEM = """
            [MEMORY-EXTRACT] 你是记忆守门员。只提取满足全部条件的稳定信息：
            1) 用户明确表达的长期偏好、身份信息（名字/职业/城市）、长期项目背景、重要约定或纠正你的教训；
            2) 半年后仍然有效；
            3) 不查资料就能复述价值。
            严禁提取：本次任务的执行细节、代码/命令、临时状态、寒暄、你自己的回答内容、常识。
            只输出 JSON 数组，每项 {"content":"...","tags":["..."]}，最多 2 条，每条一句话且自包含；
            没有符合条件的内容必须输出 []。拿不准就不记——漏记一条无关紧要，记错会永久污染记忆库。
        """.trimIndent()

        val SUBAGENT_SYSTEM = """
            [SUBAGENT] 你是被主代理派出的只读研究子代理。只做调研与只读操作（read/grep/glob/web_fetch/memory），
            禁止写入或执行命令。高效检索，最后输出简明、结构化的结论（要点 + 证据路径）。
        """.trimIndent()
    }
}
