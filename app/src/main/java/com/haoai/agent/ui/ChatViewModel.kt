package com.haoai.agent.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.haoai.agent.agent.engine.AgentEngine
import com.haoai.agent.agent.tools.TodoItem
import com.haoai.agent.agent.tools.TodoStore
import com.haoai.agent.ui.chat.ContextUsage
import com.haoai.agent.agent.engine.Finished
import com.haoai.agent.agent.engine.MessageAdded
import com.haoai.agent.agent.engine.ToolChanged
import com.haoai.agent.agent.engine.ToolRunState
import com.haoai.agent.agent.engine.TurnEvent
import com.haoai.agent.agent.model.ChatMessage
import com.haoai.agent.agent.policy.ApprovalRequest
import com.haoai.agent.agent.policy.PolicyEngine
import com.haoai.agent.data.AppContainer
import com.haoai.agent.data.StoredSession
import com.haoai.agent.data.toModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.contentOrNull

data class UiTool(
    val callId: String,
    val name: String,
    val brief: String,
    val state: ToolRunState = ToolRunState.RUNNING,
    val preview: String? = null
)

data class ChatRow(
    val key: String,
    val role: String,
    val text: String,
    val error: Boolean = false,
    val tools: List<UiTool> = emptyList(),
    val reasoning: String? = null
)

class ChatViewModel(private val c: AppContainer) : ViewModel() {

    private var currentSession: StoredSession? = null
    private var job: Job? = null
    private val liveTools = mutableMapOf<String, UiTool>()

    private val _session = MutableStateFlow<StoredSession?>(null)
    val session = _session.asStateFlow()

    private val _rows = MutableStateFlow<List<ChatRow>>(emptyList())
    val rows = _rows.asStateFlow()

    private val _streamingText = MutableStateFlow<String?>(null)
    val streamingText = _streamingText.asStateFlow()

    /** 流式思考过程（reasoning_content / <think>），与 streamingText 同生命周期。 */
    private val _streamingReasoning = MutableStateFlow<String?>(null)
    val streamingReasoning = _streamingReasoning.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running = _running.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    private val _approval =
        MutableStateFlow<Pair<ApprovalRequest, CompletableDeferred<Boolean>>?>(null)
    val approval = _approval.asStateFlow()

    private val _sessions = MutableStateFlow<List<StoredSession>>(emptyList())
    val sessions = _sessions.asStateFlow()

    private val _deletedSessions = MutableStateFlow<List<StoredSession>>(emptyList())
    val deletedSessions = _deletedSessions.asStateFlow()

    private val _contextUsage = MutableStateFlow(
        ContextUsage(usedTokens = 0, totalTokens = 32768, systemTokens = 0, toolsTokens = 0, historyTokens = 0)
    )
    val contextUsage = _contextUsage.asStateFlow()

    private val _todoItems = MutableStateFlow<List<TodoItem>>(emptyList())
    val todoItems = _todoItems.asStateFlow()

    private val todoStore = TodoStore(c.appFilesDir)

    init {
        c.sessionStore.purgeExpiredTrash()
        refreshSessions()
        refreshDeletedSessions()
        refreshTodos()
        if (_sessions.value.isNotEmpty()) {
            selectSession(_sessions.value.first().id)
        } else {
            newSession()
        }
    }

    fun workspaceName(): String =
        c.workspace.current?.displayName?.substringAfterLast('/')?.ifBlank { "根目录" }
            ?: "未绑定工作空间"

    fun refreshSessions() {
        _sessions.value = c.sessionStore.list()
    }

    fun refreshDeletedSessions() {
        _deletedSessions.value = c.sessionStore.listDeleted()
    }

    fun restoreSession(id: String) {
        c.sessionStore.restore(id)
        refreshSessions()
        refreshDeletedSessions()
    }

    fun deleteSessionForever(id: String) {
        c.sessionStore.deleteForever(id)
        refreshDeletedSessions()
    }

    fun newSession() {
        stopInternal()
        val s = StoredSession.create(c.workspace.workspaceUriForSession)
        currentSession = s
        c.sessionStore.save(s)
        liveTools.clear()
        sessionIn = 0
        sessionOut = 0
        _session.value = s
        rebuildRows()
        refreshSessions()
        refreshTodos()
    }

    fun selectSession(id: String) {
        if (_session.value?.id == id) return
        stopInternal()
        val s = c.sessionStore.load(id) ?: return
        currentSession = s
        liveTools.clear()
        sessionIn = 0
        sessionOut = 0
        _session.value = s
        rebuildRows()
        refreshTodos()
    }

    fun deleteSession(id: String) {
        c.sessionStore.delete(id)
        refreshSessions()
        refreshDeletedSessions()
        if (_session.value?.id == id) newSession()
    }

    fun send(rawText: String, imageData: String? = null) {
        val text = rawText.trim()
        if (text.isEmpty() || _running.value) return
        val provider0 = c.activeProvider()
        if (provider0 == null) {
            _error.value = "请先在「设置」里配置模型服务（Base URL / 模型 ID / API Key）"
            return
        }
        val s = currentSession ?: return
        _running.value = true
        _streamingText.value = null
        _streamingReasoning.value = null
        job = viewModelScope.launch {
            try {
                val provider = resolveProvider(provider0)
                if (provider == null) {
                    _error.value = c.llama.state.value.let {
                        (it as? com.haoai.agent.platform.llama.LlamaState.Failed)?.message
                            ?: "端侧模型启动失败"
                    }
                    return@launch
                }
                val engine = buildEngine(s, provider)
                engine.runTurn(
                    userText = text,
                    onDelta = { frag -> _streamingText.value = (_streamingText.value ?: "") + frag },
                    onEvent = ::handleEvent,
                    imageData = imageData,
                    onReasoning = { frag ->
                        _streamingReasoning.value = (_streamingReasoning.value ?: "") + frag
                    }
                )
            } finally {
                _running.value = false
                _streamingText.value = null
                _streamingReasoning.value = null
                refreshSessions()
                c.syncWorkspaceDocs()
                maybeGenerateTitle(s)
            }
        }
    }

    private suspend fun resolveProvider(
        p: com.haoai.agent.data.ProviderConfig
    ): com.haoai.agent.data.ProviderConfig? {
        if (!p.baseUrl.startsWith("local")) return p
        // 期望聊天模型：记忆固化可能把服务切到更小的记忆专用模型，这里按需切回
        val ok = c.llama.ensureStarted(c.llama.findModel()?.absolutePath)
        if (!ok) return null
        return p.copy(baseUrl = com.haoai.agent.platform.llama.LlamaServerController.LOCAL_BASE_URL)
    }

    /**
     * 智能会话标题：首轮对话完成后用当前模型总结 ≤12 字标题。
     * 只生成一次（titleAuto 标记），异步执行、静默失败，不阻塞下一次对话。
     */
    private fun maybeGenerateTitle(s: StoredSession) {
        if (s.titleAuto) return
        val hasUser = s.messages.any {
            it.role == com.haoai.agent.agent.model.ChatMessage.ROLE_USER && it.content.isNotBlank()
        }
        val hasReply = s.messages.any {
            it.role == com.haoai.agent.agent.model.ChatMessage.ROLE_ASSISTANT &&
                it.content.isNotBlank() && !it.error
        }
        if (!hasUser || !hasReply) return
        val provider0 = c.activeProvider() ?: return
        // 先落标记再请求：失败也不重试，避免每轮都烧 token
        s.titleAuto = true
        c.sessionStore.save(s)
        viewModelScope.launch {
            try {
                val provider = resolveProvider(provider0) ?: return@launch
                val engine = buildEngine(s, provider)
                val sb = StringBuilder()
                engine.runBtw(
                    question = "用不超过12个字总结这段对话的主题，作为会话标题。只输出标题本身，不要引号、句号或任何解释。",
                    onDelta = { frag -> if (sb.length < 80) sb.append(frag) },
                    onReasoning = { }
                )
                val t = sb.toString().trim()
                    .trim('"', '“', '”', '\'', '「', '」', '。', '.', '！', '!', '？', '?')
                    .replace('\n', ' ')
                    .take(24)
                if (t.isNotBlank()) {
                    s.title = t
                    c.sessionStore.save(s)
                    refreshSessions()
                    // copy() 强制 StateFlow 发射（同引用修改不会触发更新）
                    if (_session.value?.id == s.id) _session.value = s.copy()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("HaoTitle", "会话标题生成失败: ${e.message}")
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        _approval.value = null
    }

    fun refreshTodos() {
        val s = currentSession ?: run {
            _todoItems.value = emptyList()
            return
        }
        _todoItems.value = todoStore.load(s.id)
    }

    /**
     * 处理斜杠命令。返回 true 表示命令已消费，UI 不应再发送为普通消息。
     */
    suspend fun handleSlashCommand(
        command: com.haoai.agent.ui.chat.SlashCommand,
        arg: String,
        onModelPicker: () -> Unit
    ): Boolean {
        return when (command.name) {
            "compact" -> {
                val s = currentSession ?: return false
                val provider = c.activeProvider() ?: return false
                val engine = buildEngine(s, provider)
                _running.value = true
                try {
                    val summary = engine.compactNow()
                    if (summary != null) {
                        _streamingText.value = null
                        rebuildRows()
                    }
                } finally {
                    _running.value = false
                    _streamingText.value = null
                    refreshSessions()
                }
                true
            }
            "clear" -> {
                newSession()
                true
            }
            "stop" -> {
                stop()
                true
            }
            "model" -> {
                onModelPicker()
                true
            }
            "btw", "side" -> {
                if (arg.isBlank()) {
                    _error.value = "用法：/btw <你的问题>"
                    return true
                }
                sendBtw(arg)
                true
            }
            "help" -> {
                true
            }
            "status" -> {
                true
            }
            "task", "todo", "tasks" -> {
                refreshTodos()
                true
            }
            else -> false
        }
    }

    /**
     * /btw 附带问题：临时借用当前上下文回答，不写入对话历史。
     */
    private fun sendBtw(question: String) {
        val provider0 = c.activeProvider()
        if (provider0 == null) {
            _error.value = "请先配置模型服务"
            return
        }
        val s = currentSession ?: return
        _running.value = true
        _streamingText.value = null
        job = viewModelScope.launch {
            try {
                val provider = resolveProvider(provider0) ?: run {
                    _error.value = "端侧模型启动失败"
                    return@launch
                }
                val engine = buildEngine(s, provider)
                engine.runBtw(
                    question = question,
                    onDelta = { frag -> _streamingText.value = (_streamingText.value ?: "") + frag },
                    onReasoning = { frag -> _streamingReasoning.value = (_streamingReasoning.value ?: "") + frag }
                )
            } finally {
                _running.value = false
                _streamingText.value = null
                _streamingReasoning.value = null
            }
        }
    }

    fun dismissError() {
        _error.value = null
    }

    fun onApprove() {
        _approval.value?.second?.complete(true)
    }

    fun onDeny() {
        _approval.value?.second?.complete(false)
    }

    private suspend fun requestApproval(req: ApprovalRequest): Boolean {
        val gate = CompletableDeferred<Boolean>()
        _approval.value = req to gate
        // try/finally：协程被取消（如用户按停止）时也要清掉弹层状态
        try {
            return gate.await()
        } finally {
            _approval.value = null
        }
    }

    private fun handleEvent(ev: TurnEvent) {
        when (ev) {
            is MessageAdded -> {
                _streamingText.value = null
                ev.message.toolCalls.forEach { call ->
                    liveTools[call.id] = UiTool(call.id, call.name, briefFor(call.name, call.argumentsJson))
                }
                rebuildRows()
            }
            is ToolChanged -> {
                liveTools[ev.update.callId] = UiTool(
                    ev.update.callId,
                    liveTools[ev.update.callId]?.name ?: "",
                    ev.update.brief ?: "",
                    ev.update.state,
                    ev.update.preview
                )
                rebuildRows()
            }
            is Finished -> {
                if (ev.error != null && ev.error != "已停止") _error.value = ev.error
            }
        }
    }

    private fun rebuildRows() {
        val s = _session.value ?: return
        val resultByCall = HashMap<String, Pair<String, Boolean>>()
        s.messages.forEach { m ->
            if (m.role == ChatMessage.ROLE_TOOL && m.toolCallId != null) {
                resultByCall[m.toolCallId!!] = m.content to m.error
            }
        }

        val rows = ArrayList<ChatRow>()
        s.messages.forEachIndexed { i, m ->
            when (m.role) {
                ChatMessage.ROLE_ASSISTANT -> {
                    val tools = m.toolCalls.map { call ->
                        val live = liveTools[call.id]
                        val stored = resultByCall[call.id]
                        val base = UiTool(call.id, call.name, briefFor(call.name, call.argumentsJson))
                        when {
                            live != null && live.state == ToolRunState.RUNNING -> live
                            stored != null -> base.copy(
                                state = if (stored.second) ToolRunState.ERROR else ToolRunState.DONE,
                                preview = previewLine(stored.first)
                            )
                            live != null -> live
                            else -> base
                        }
                    }
                    rows.add(ChatRow("m$i", m.role, m.content, m.error, tools, m.reasoning))
                }
                ChatMessage.ROLE_USER ->
                    rows.add(ChatRow("m$i", m.role, m.content))
                else -> Unit
            }
        }
        _rows.value = rows
        recalcContextUsage()
    }

    private fun previewLine(content: String): String =
        content.lineSequence().firstOrNull()?.take(160) ?: ""

    private fun briefFor(toolName: String, argsJson: String): String {
        val args = runCatching {
            com.haoai.agent.data.HaoJson.json.parseToJsonElement(argsJson.ifBlank { "{}" })
        }.getOrNull() as? kotlinx.serialization.json.JsonObject
            ?: return ""
        fun opt(k: String): String =
            (args[k] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull ?: ""
        return when (toolName) {
            "bash" -> opt("command").lineSequence().firstOrNull()?.take(90) ?: ""
            "read", "write", "edit" -> opt("path")
            "grep" -> "/${opt("pattern")}/"
            "glob" -> opt("pattern")
            "web_fetch" -> opt("url")
            "todo" -> {
                val todos = args["todos"] as? kotlinx.serialization.json.JsonArray
                if (todos != null) "更新清单(${todos.size}项)"
                else "查看清单"
            }
            "memory" -> opt("action").ifBlank { "list" } +
                (opt("content").ifBlank { opt("query") }).takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
            "screen" -> "读取屏幕"
            "tap" -> opt("index")?.let { "[$it]" }
                ?: opt("text").ifBlank { opt("view_id") }
                .ifBlank { "(${opt("x")},${opt("y")})" }
            "swipe" -> "滑动"
            "scroll" -> "滚动 " + opt("direction").ifBlank { "down" }
            "find" -> "查找「${opt("text")}」"
            "wait" -> "等待 " + opt("mode").ifBlank { "text" } + opt("text").takeIf { it.isNotBlank() }?.let { "「$it」" }.orEmpty()
            "type_text" -> "输入：${opt("text").take(40)}"
            "key" -> opt("action")
            "launch_app" -> opt("package")
            "list_apps" -> "列出应用"
            "schedule" -> opt("action").ifBlank { "list" } +
                opt("name").takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
            "spawn_agent" -> opt("task").take(60)
            "spawn_agents" -> "${(args["tasks"] as? kotlinx.serialization.json.JsonArray)?.size ?: 0} 个并行子任务"
            "app_status" -> "读取运行状态"
            "update_settings" -> "修改设置"
            else -> argsJson.take(60)
        }
    }

    private fun buildEngine(s: StoredSession, provider: com.haoai.agent.data.ProviderConfig): AgentEngine {
        val st = c.settingsFlow.value
        val identity = buildString {
            if (st.agentName.isNotBlank()) {
                append("## 你的身份\n")
                append("- 你的名字：${st.agentName}（用户给你起的，请以此名字自称）\n")
                if (st.soul.isNotBlank()) append("- 你的性格：${st.soul}\n")
            }
        }
        return AgentEngine(
            httpClient = c.client,
            provider = provider,
            apiKey = c.cipher.decrypt(provider.apiKeyCipher),
            customPrompt = st.customPrompt,
            policy = PolicyEngine(st.permissionMode),
            approve = { req -> requestApproval(req) },
            session = s,
            persist = { c.sessionStore.save(s) },
            backend = c.workspace.current,
            appFilesDir = c.appFilesDir,
            workspaceLabel = workspaceName(),
            memoryBank = c.memoryBank,
            memoryEnabled = st.memoryEnabled,
            journal = c.journal,
            autoLearn = st.autoLearn,
            reasoningEffort = st.reasoningEffort,
            okHttpClient = c.okHttpClient,
            appContext = c.appContext,
            identity = identity,
            onUsage = { pin, pout -> addUsage(pin, pout) },
            backgroundScope = c.applicationScope,
            statusProvider = { buildStatusText() },
            configMutator = { applyConfigPatch(it) },
            onToolChange = { refreshTodos() }
        )
    }

    /**
     * 自身运行状态全文（上游 session_status 式）：由 app_status 工具按需读取，
     * 不再每轮注入系统提示——省 token，数据仍然实时。
     */
    private fun buildStatusText(): String {
        val st = c.settingsFlow.value
        val p = st.providers.find { it.id == st.activeProviderId } ?: st.providers.firstOrNull()
        val isLocal = p?.id == com.haoai.agent.platform.llama.LlamaServerController.LOCAL_PROVIDER_ID ||
            p?.baseUrl?.contains("127.0.0.1") == true
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA).format(java.util.Date())
        val ver = runCatching {
            c.appContext.packageManager.getPackageInfo(c.appContext.packageName, 0).versionName
        }.getOrNull() ?: "dev"
        return buildString {
            appendLine("应用：HaoAI v$ver（Android ${android.os.Build.VERSION.RELEASE}）")
            if (p == null) {
                appendLine("当前模型：未配置")
            } else {
                appendLine("当前模型：${p.model}（${p.name}${if (isLocal) " · 端侧本地推理" else " · 云端"}）")
                appendLine("上下文窗口：约 ${if (isLocal) st.localContextLength else p.effectiveContextLength()} tokens")
                val maxTok = if (isLocal) 4096 else p.effectiveMaxTokens()
                appendLine("单次回复上限：${if (maxTok > 0) "$maxTok tokens" else "供应商默认"}")
            }
            appendLine("本会话 token：输入 $sessionIn + 输出 $sessionOut")
            appendLine(
                if (st.tokenDay == today) "今日 token（$today）：输入 ${st.tokenInToday} + 输出 ${st.tokenOutToday}"
                else "今日 token：今天还没有消耗（统计日期 ${st.tokenDay.ifBlank { "无" }}）"
            )
            appendLine("历史累计 token：输入 ${st.tokenInTotal} + 输出 ${st.tokenOutTotal}")
            appendLine("长期记忆：${c.memoryBank.count()} 条 · 今日日志：${c.journal.count()} 条 · 技能：${com.haoai.agent.agent.skills.SkillStore.list().size} 个")
            val llamaState = c.llama.state.value
            appendLine(
                "端侧推理：" + when (llamaState) {
                    is com.haoai.agent.platform.llama.LlamaState.Running -> "运行中（${llamaState.modelFile}）"
                    is com.haoai.agent.platform.llama.LlamaState.Starting -> "启动中"
                    is com.haoai.agent.platform.llama.LlamaState.Failed -> "上次失败"
                    else -> "已停止"
                }
            )
        }.trimEnd()
    }

    /** update_settings 白名单落地；返回给模型的结果文本。 */
    private fun applyConfigPatch(args: kotlinx.serialization.json.JsonObject): String {
        fun intArg(key: String): Int? =
            (args[key] as? kotlinx.serialization.json.JsonPrimitive)?.content?.trim()?.toIntOrNull()
        fun boolArg(key: String): Boolean? =
            (args[key] as? kotlinx.serialization.json.JsonPrimitive)?.content?.trim()?.toBooleanStrictOrNull()

        val applied = mutableListOf<String>()
        var localCtx: Int? = null
        c.updateSettings { s ->
            var next = s
            val pid = s.activeProviderId ?: s.providers.firstOrNull()?.id
            intArg("reply_max_tokens")?.let { v ->
                next = next.copy(providers = next.providers.map {
                    if (it.id == pid && it.id != com.haoai.agent.platform.llama.LlamaServerController.LOCAL_PROVIDER_ID)
                        it.copy(maxTokens = v.coerceIn(256, 1_000_000)) else it
                })
                applied.add("单次回复上限=${v.coerceIn(256, 1_000_000)}")
            }
            intArg("context_length")?.let { v ->
                next = next.copy(providers = next.providers.map {
                    if (it.id == pid && it.id != com.haoai.agent.platform.llama.LlamaServerController.LOCAL_PROVIDER_ID)
                        it.copy(contextLength = v.coerceIn(1024, 10_000_000)) else it
                })
                applied.add("云端上下文窗口=${v.coerceIn(1024, 10_000_000)}")
            }
            intArg("local_context_length")?.let { v ->
                val n = v.coerceIn(2048, 262_144)
                next = next.copy(localContextLength = n)
                localCtx = n
                applied.add("端侧上下文窗口=$n（重启端侧服务生效）")
            }
            boolArg("memory_enabled")?.let { v ->
                next = next.copy(memoryEnabled = v)
                applied.add("记忆系统=${if (v) "开" else "关"}")
            }
            boolArg("auto_learn")?.let { v ->
                next = next.copy(autoLearn = v)
                applied.add("自动学习=${if (v) "开" else "关"}")
            }
            boolArg("deep_dream")?.let { v ->
                next = next.copy(deepDream = v)
                applied.add("闲置整理记忆=${if (v) "开" else "关"}")
            }
            next
        }
        localCtx?.let { c.llama.contextSize = it }
        return if (applied.isEmpty())
            "没有任何字段被修改。支持的字段：reply_max_tokens / context_length / local_context_length / memory_enabled / auto_learn / deep_dream"
        else
            "已生效（用户已批准）：${applied.joinToString("；")}。可用 app_status 验证。"
    }

    private val _usage = kotlinx.coroutines.flow.MutableStateFlow(
        (c.settingsFlow.value.tokenInTotal.toLong() to c.settingsFlow.value.tokenOutTotal.toLong())
    )
    val usage: kotlinx.coroutines.flow.StateFlow<Pair<Long, Long>> = _usage

    private var sessionIn = 0L
    private var sessionOut = 0L

    private fun addUsage(pin: Long, pout: Long) {
        val cur = _usage.value
        val next = (cur.first + pin) to (cur.second + pout)
        _usage.value = next
        sessionIn += pin
        sessionOut += pout
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA).format(java.util.Date())
        c.updateSettings { s ->
            val sameDay = s.tokenDay == today
            s.copy(
                tokenInTotal = next.first,
                tokenOutTotal = next.second,
                tokenDay = today,
                tokenInToday = (if (sameDay) s.tokenInToday else 0L) + pin,
                tokenOutToday = (if (sameDay) s.tokenOutToday else 0L) + pout
            )
        }
    }

    /** 重新估算上下文使用量（UI 实时显示）。 */
    private fun recalcContextUsage() {
        val s = _session.value ?: return
        val st = c.settingsFlow.value
        val isLocal = st.providers.find { it.id == st.activeProviderId }
            ?.let { it.id == com.haoai.agent.platform.llama.LlamaServerController.LOCAL_PROVIDER_ID || it.baseUrl.contains("127.0.0.1") } == true
        val contextWindow = if (isLocal) st.localContextLength
            else st.providers.find { it.id == st.activeProviderId }?.effectiveContextLength() ?: 32768
        val chatMsgs = s.messages.map { it.toModel() }
        val sysTok = com.haoai.agent.ui.chat.ContextUsage.estimateSystemTokens("")
        val toolsTok = 2000
        val histTok = chatMsgs.sumOf { com.haoai.agent.ui.chat.ContextUsage.estimateMessageTokens(it) }
        _contextUsage.value = com.haoai.agent.ui.chat.ContextUsage(
            usedTokens = sysTok + toolsTok + histTok,
            totalTokens = contextWindow,
            systemTokens = sysTok,
            toolsTokens = toolsTok,
            historyTokens = histTok
        )
    }

    fun agentName(): String =
        c.settingsFlow.value.agentName.ifBlank { "HaoAI" }

    /** 档案状态流：头像/签名等 UI 直接订阅。 */
    val settings get() = c.settingsFlow

    /** 更新档案：名字、emoji 头像、渐变底色、签名。 */
    fun updateProfile(name: String, avatarEmoji: String, avatarGradient: Int, bio: String) {
        c.updateSettings {
            it.copy(
                agentName = name.trim().take(20),
                avatarEmoji = avatarEmoji,
                avatarGradient = avatarGradient.coerceIn(0, 5),
                bio = bio.trim().take(60)
            )
        }
    }

    fun needsOnboarding(): Boolean = !c.settingsFlow.value.onboarded

    fun completeOnboarding(name: String, soul: String) {
        c.updateSettings {
            it.copy(
                agentName = name.trim().take(20),
                soul = soul.trim().take(120),
                onboarded = true
            )
        }
        c.syncWorkspaceDocs()
    }

    private fun stopInternal() {
        job?.cancel()
        job = null
        _running.value = false
        _streamingText.value = null
        _streamingReasoning.value = null
    }
}
