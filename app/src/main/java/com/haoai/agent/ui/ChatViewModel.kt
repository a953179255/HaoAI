package com.haoai.agent.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.haoai.agent.agent.engine.AgentEngine
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

    init {
        c.sessionStore.purgeExpiredTrash()
        refreshSessions()
        refreshDeletedSessions()
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
            }
        }
    }

    private suspend fun resolveProvider(
        p: com.haoai.agent.data.ProviderConfig
    ): com.haoai.agent.data.ProviderConfig? {
        if (!p.baseUrl.startsWith("local")) return p
        val ok = c.llama.ensureStarted()
        if (!ok) return null
        return p.copy(baseUrl = com.haoai.agent.platform.llama.LlamaServerController.LOCAL_BASE_URL)
    }

    fun stop() {
        job?.cancel()
        job = null
        // 停止时若审批弹层还挂着，必须清掉，否则全屏遮罩会永久吃掉触摸事件
        _approval.value = null
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
            "todo" -> opt("action").ifBlank { "view" }
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
            configMutator = { applyConfigPatch(it) }
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

    fun agentName(): String =
        c.settingsFlow.value.agentName.ifBlank { "HaoAI" }

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
