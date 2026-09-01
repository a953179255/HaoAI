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
    /** 消息唯一 id（StoredMessage.id），长按操作/截断/搜索定位用。 */
    val id: String = "",
    val role: String,
    val text: String,
    val error: Boolean = false,
    val tools: List<UiTool> = emptyList(),
    val reasoning: String? = null,
    /** 消息时间戳（操作面板元信息行）。 */
    val ts: Long = 0L,
    /** 整轮用量统计（assistant 最终回复才有；旧消息为 null → 统计行不显示）。 */
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val durationMs: Long? = null,
    val model: String? = null
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

    /** 工具卡"查看变更"（1.3）：callId → (路径, diff 行)；从写前快照现算。 */
    suspend fun snapshotDiff(callId: String): Pair<String, List<com.haoai.agent.ui.common.DiffLine>>? {
        val sid = _session.value?.id ?: return null
        val snap = c.sessionStore.let {
            com.haoai.agent.agent.tools.snapshot.FileSnapshot.read(c.appFilesDir, sid, callId)
        } ?: return null
        val (meta, before, after) = snap
        return meta.path to com.haoai.agent.ui.common.TextDiff.diffText(before ?: "", after ?: "").lines
    }

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
        // 置顶优先，其余按最近使用倒序
        _sessions.value = c.sessionStore.list()
            .sortedWith(compareByDescending<StoredSession> { it.pinned }.thenByDescending { it.updatedAt })
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

    /** 置顶/取消置顶（切换）。 */
    fun pinSession(id: String) {
        val s = _sessions.value.firstOrNull { it.id == id } ?: return
        c.sessionStore.setPinned(id, !s.pinned)
        refreshSessions()
    }

    /** 手动重命名：titleAuto 置位，防止 AI 标题生成覆盖用户起的名字。 */
    fun renameSession(id: String, title: String) {
        val t = title.trim().take(50)
        if (t.isEmpty()) return
        val s = c.sessionStore.load(id) ?: return
        s.title = t
        s.titleAuto = true
        c.sessionStore.save(s, touch = false)
        refreshSessions()
        if (currentSession?.id == id) {
            // 同一实例上的字段变更不会触发 StateFlow 重发射，copy 一份让顶栏立即刷新
            // （copy 共享同一个 messages 列表引用，引擎追加消息不受影响）
            val view = s.copy()
            currentSession = view
            _session.value = view
        }
    }

    /** 清空回收站：所有回收站会话彻底删除。 */
    fun emptyTrash() {
        _deletedSessions.value.forEach { c.sessionStore.deleteForever(it.id) }
        refreshDeletedSessions()
    }

    // ===== 消息长按操作组（1.2）：删除 / 重新生成 / 编辑重发 =====

    private fun refreshSessionView(s: StoredSession) {
        if (_session.value?.id == s.id) {
            // copy() 让 StateFlow 立即重发射（同引用修改不会触发更新）
            val view = s.copy()
            currentSession = view
            _session.value = view
        }
        rebuildRows()
        refreshSessions()
    }

    /** 长按：删除单条消息。 */
    fun deleteMessage(messageId: String) {
        if (_running.value) return
        val s = currentSession ?: return
        if (!c.sessionStore.deleteMessage(s.id, messageId)) return
        // 同步内存实例（引擎可能持有同引用追加消息）
        s.messages.removeAll { it.id == messageId }
        refreshSessionView(s)
    }

    /**
     * 长按：重新生成——删除 [messageId]（assistant）及其后全部消息，
     * 以它之前最近的用户消息重跑（用户消息一并截掉，由 send 重新落库，内容等价）。
     */
    fun regenerateFrom(messageId: String) {
        if (_running.value) return
        val s = currentSession ?: return
        val idx = s.messages.indexOfFirst { it.id == messageId }
        if (idx < 0) return
        var userIdx = -1
        for (i in idx - 1 downTo 0) {
            val m = s.messages[i]
            if (m.role == ChatMessage.ROLE_USER && m.content.isNotBlank() && m.toolCallId == null) {
                userIdx = i
                break
            }
        }
        if (userIdx < 0) return
        val userMsg = s.messages[userIdx]
        while (s.messages.size > userIdx) s.messages.removeAt(s.messages.size - 1)
        c.sessionStore.save(s)
        rebuildRows()
        send(userMsg.content, userMsg.imageData)
    }

    /** 长按：编辑重发（仅用户消息）——截掉该条及其后，以编辑后文本重新发送。 */
    fun editResend(messageId: String, newText: String) {
        if (_running.value) return
        val s = currentSession ?: return
        val idx = s.messages.indexOfFirst { it.id == messageId }
        if (idx < 0) return
        val m = s.messages[idx]
        if (m.role != ChatMessage.ROLE_USER) return
        val text = newText.trim()
        if (text.isEmpty()) return
        val imageData = m.imageData
        while (s.messages.size > idx) s.messages.removeAt(s.messages.size - 1)
        c.sessionStore.save(s)
        rebuildRows()
        send(text, imageData)
    }

    fun send(rawText: String, imageData: String? = null) {
        val text = rawText.trim()
        // 纯图片发送（无文字）也允许：否则 UI 已清掉 pendingImage，图片会静默丢失
        if ((text.isEmpty() && imageData == null) || _running.value) return
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
            var turnEngine: AgentEngine? = null
            try {
                val provider = resolveProvider(provider0)
                if (provider == null) {
                    _error.value = c.llama.state.value.let {
                        (it as? com.haoai.agent.platform.llama.LlamaState.Failed)?.message
                            ?: "端侧模型启动失败"
                    }
                    return@launch
                }
                val engine = buildEngine(s, provider).also { turnEngine = it }
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
                // 5.6：Plan 模式下拦截过工具 → 本轮输出即计划，弹确认卡
                if (_planMode.value && turnEngine?.planIntercepted == true) {
                    val plan = s.messages.lastOrNull {
                        it.role == ChatMessage.ROLE_ASSISTANT && it.content.isNotBlank()
                    }?.content.orEmpty()
                    if (plan.isNotBlank()) _planProposal.value = plan
                }
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
        // 端侧模型不做 LLM 标题总结：辅助请求会冲掉 llama-server 单 slot 的前缀缓存，
        // 让下一轮主对话全量重算 prefill（手机上多花几十秒）；直接取用户首条消息截断
        // （仅未配置专用标题模型、且主模型本身是端侧时；显式路由到端侧小模型由用户选择，照常生成）
        val titleCfgId = c.settingsFlow.value.titleProviderId.trim()
        val mainProvider = c.activeProvider() ?: return
        val implicitLocal = titleCfgId.isEmpty() && (
            mainProvider.id == com.haoai.agent.platform.llama.LlamaServerController.LOCAL_PROVIDER_ID ||
                mainProvider.baseUrl.contains("127.0.0.1")
            )
        if (implicitLocal) {
            val fallback = s.messages.firstOrNull {
                it.role == com.haoai.agent.agent.model.ChatMessage.ROLE_USER && it.content.isNotBlank()
            }?.content?.lineSequence()?.firstOrNull()?.trim()?.take(12)
            if (!fallback.isNullOrBlank()) {
                s.titleAuto = true
                s.title = fallback
                c.sessionStore.save(s)
                refreshSessions()
                if (_session.value?.id == s.id) _session.value = s.copy()
            }
            return
        }
        // 先落标记再请求：失败也不重试，避免每轮都烧 token
        s.titleAuto = true
        c.sessionStore.save(s)
        viewModelScope.launch {
            try {
                val provider = if (titleCfgId.isEmpty()) {
                    resolveProvider(mainProvider) ?: return@launch
                } else {
                    // 5.3 专用标题模型（含端侧小模型：resolvePurposeTarget 会拉起 llama）
                    val t = runCatching { c.resolvePurposeTarget(titleCfgId) }.getOrNull() ?: return@launch
                    resolveProvider(t.provider) ?: return@launch
                }
                val engine = buildEngine(s, provider)
                val sb = StringBuilder()
                engine.runBtw(
                    question = "用不超过12个字总结这段对话的主题，作为会话标题。只输出标题本身，不要引号、句号或任何解释。",
                    onDelta = { frag -> if (sb.length < 80) sb.append(frag) },
                    onReasoning = { },
                    purpose = "title"
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
                val provider0 = c.activeProvider() ?: return false
                // 端侧供应商需要拉起/切回本地 server：直接用原始 provider 压缩会请求 local://llama 失败
                val provider = resolveProvider(provider0) ?: run {
                    _error.value = c.llama.state.value.let {
                        (it as? com.haoai.agent.platform.llama.LlamaState.Failed)?.message
                            ?: "端侧模型启动失败，无法压缩"
                    }
                    return true
                }
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
            "undo" -> {
                val msg = undoLastChange()
                if (msg != null) _error.value = msg
                true
            }
            "plan" -> {
                _planMode.value = !_planMode.value
                _error.value = if (_planMode.value) "已进入计划模式：WRITE/EXEC 工具被禁用，模型只产出计划" else "已退出计划模式"
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

    /**
     * 5.5 /undo：回滚本会话最近一次文件变更。redo 往返自然成立——回滚会把
     * 「当前内容」存为 redo 快照（时间最新），下一次 /undo 命中的就是它。
     * 走 WRITE 审批弹窗（复用引擎审批管线）。
     */
    suspend fun undoLastChange(): String? {
        val s = _session.value ?: return "会话未打开"
        val metas = com.haoai.agent.agent.tools.snapshot.FileSnapshot.listSession(c.appFilesDir, s.id)
        if (metas.isEmpty()) return "本会话没有可回滚的文件变更"
        val target = metas.last()
        val snap = com.haoai.agent.agent.tools.snapshot.FileSnapshot.read(c.appFilesDir, s.id, target.callId)
            ?: return "快照文件缺失（可能已被清理）"
        val ok = requestApproval(com.haoai.agent.agent.policy.ApprovalRequest.Generic("undo", "回滚最近文件变更：${target.path}"))
        if (!ok) return null
        return performRollback(s.id, c.workspace.current, snap.first, snap.second)
    }

    /** 5.5 回滚指定变更（工具卡「回滚」按钮）。 */
    suspend fun rollbackChange(callId: String): String? {
        val s = _session.value ?: return "会话未打开"
        val snap = com.haoai.agent.agent.tools.snapshot.FileSnapshot.read(c.appFilesDir, s.id, callId)
            ?: return "快照不存在或已被清理"
        val ok = requestApproval(com.haoai.agent.agent.policy.ApprovalRequest.Generic("undo", "回滚文件变更：${snap.first.path}"))
        if (!ok) return null
        return performRollback(s.id, c.workspace.current, snap.first, snap.second)
    }

    private suspend fun performRollback(
        sessionId: String,
        backend: com.haoai.agent.platform.FileBackend?,
        meta: com.haoai.agent.agent.tools.snapshot.FileSnapshot.Meta,
        before: String?
    ): String? {
        val current = runCatching { backend?.readText(meta.path) }.getOrNull()
        val redoCall = "undo-${System.currentTimeMillis()}"
        runCatching {
            com.haoai.agent.agent.tools.snapshot.FileSnapshot.rollback(c.appFilesDir, sessionId, meta.callId, current, redoCall)
        }.onFailure { return "回滚失败：${it.message}" }
        try {
            if (before == null) backend?.delete(meta.path) else backend?.writeText(meta.path, before)
        } catch (e: Exception) {
            return "回滚失败：${e.message}"
        }
        // 行数口径：被撤销的那次改动（before→current）的 +/−
        val diff = com.haoai.agent.ui.common.TextDiff.diffText(before ?: "", current ?: "").lines
        val added = diff.count { it.type == com.haoai.agent.ui.common.DiffType.ADDED }
        val removed = diff.count { it.type == com.haoai.agent.ui.common.DiffType.REMOVED }
        return "已回滚 ${meta.path}（撤销 +$added −$removed 的改动）；再输 /undo 可恢复"
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

    /** 5.5 工具卡回滚结果的 SnackBar 展示入口。 */
    fun showError(msg: String) { _error.value = msg }

    // ── 5.6 Plan / Execute 模式 ─────────────────────────────────────

    private val _planMode = kotlinx.coroutines.flow.MutableStateFlow(false)
    val planMode = _planMode

    /** 回合结束时计划待确认（引擎本轮产出过计划文本 → 弹确认卡）。 */
    private val _planProposal = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val planProposal = _planProposal

    /** 是否在本回合拦截过 WRITE/EXEC 工具（判定"模型给出的是计划"）。 */
    @Volatile private var planIntercepted = false

    /** 批准计划：退出 Plan 模式并自动触发执行（仍走正常审批）。 */
    fun approvePlan() {
        _planProposal.value = null
        _planMode.value = false
        send("请按上述计划执行。")
    }

    fun dismissPlan() {
        _planProposal.value = null
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
                // 5.2 降级提示：回合结束消费（SnackBar 显示「已降级到 X」）
                c.lastFallbackNotice?.let { name ->
                    c.lastFallbackNotice = null
                    _error.value = "已降级到 $name（主服务请求失败）"
                }
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
                    rows.add(
                        ChatRow(
                            "m$i", m.id, m.role, m.content, m.error, tools, m.reasoning,
                            ts = m.ts,
                            promptTokens = m.promptTokens,
                            completionTokens = m.completionTokens,
                            durationMs = m.durationMs,
                            model = m.model
                        )
                    )
                }
                ChatMessage.ROLE_USER ->
                    rows.add(ChatRow("m$i", m.id, m.role, m.content, ts = m.ts))
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
            "browser_search" -> "搜索「${opt("query")}」"
            "browser_open" -> opt("url")
            "browser_navigate" -> opt("url")
            "browser_read" -> "读取页面结构"
            "browser_click" -> opt("index")?.let { "[$it]" } ?: ""
            "browser_input" -> "[${opt("index")}] 输入：${opt("text").take(30)}"
            "browser_scroll" -> "滚动 " + opt("direction").ifBlank { "down" }
            "browser_find" -> "查找「${opt("text")}」"
            "browser_back" -> "后退"
            "browser_screenshot" -> "页面截图"
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
            httpClient = c.clientFor(provider),
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
            onToolChange = { refreshTodos() },
            vscreenEnabled = st.vscreenEnabled &&
                android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R,
            budgetHint = { com.haoai.agent.data.UsageLedger.budgetHint(st.dailyTokenBudgetK) },
            // E5 单轮熔断：token 上限 + 连续工具失败阈值（设置-通用-模型行为）
            turnTokenCap = st.turnTokenCap,
            toolFailCap = st.consecutiveToolFailCap,
            memoryTarget = {
                c.resolvePurposeTarget(st.memoryExtractProviderId)?.let { it.provider to it.apiKey }
            },
            summarizeTarget = {
                c.resolvePurposeTarget(st.summarizeProviderId)?.let { it.provider to it.apiKey }
            },
            auxClientFor = { p -> c.clientFor(p) },
            planGate = { _planMode.value }
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
        // 与引擎压缩判断同一口径：真实系统提示（记忆/日志/技能注入）而非固定底数，
        // 否则指示器长期低估、压缩偏晚
        val engine = buildEngine(s, c.activeProvider() ?: com.haoai.agent.data.ProviderConfig(
            id = "estimate", name = "估算", baseUrl = "https://estimate.invalid", model = "-"
        ))
        val (sysTok, toolsTok) = engine.estimateOverheadTokens()
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

    /** 更新档案：名字、emoji 头像、渐变底色、签名、图片头像。 */
    fun updateProfile(
        name: String,
        avatarEmoji: String,
        avatarGradient: Int,
        bio: String,
        avatarImagePath: String?
    ) {
        c.updateSettings {
            it.copy(
                agentName = name.trim().take(20),
                avatarEmoji = avatarEmoji,
                avatarGradient = avatarGradient.coerceIn(0, 5),
                bio = bio.trim().take(60),
                avatarImagePath = avatarImagePath
            )
        }
    }

    /** 从相册导入头像：压缩后拷入应用私有目录，完成后在主线程回传新文件路径（失败为 null）。 */
    fun importAvatarImage(uri: android.net.Uri, onDone: (String?) -> Unit) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val path = runCatching {
                val resolver = c.appContext.contentResolver
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                resolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 512) sample *= 2
                val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
                val bmp = resolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, opts) }
                    ?: return@runCatching null
                val dir = java.io.File(c.appFilesDir, "avatar").apply { mkdirs() }
                // 旧头像文件一并删除，避免私有目录堆积
                c.settingsFlow.value.avatarImagePath?.let { old ->
                    if (old.startsWith(dir.absolutePath)) java.io.File(old).delete()
                }
                val out = java.io.File(dir, "avatar_${System.currentTimeMillis()}.jpg")
                out.outputStream().use { fos -> bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, fos) }
                bmp.recycle()
                out.absolutePath
            }.getOrNull()
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { onDone(path) }
        }
    }

    /** 当前生效供应商标签（/status 展示用）。 */
    fun activeProviderLabel(): String? =
        c.activeProvider()?.let { "${it.name} · ${it.model}" }

    /** /model 弹窗：可切换的模型服务列表。 */
    fun providers() = c.settingsFlow.value.providers

    /** 当前激活供应商是否为端侧模型（供 UI 提示端侧 prefill 特性）。 */
    fun isLocalProviderActive(): Boolean {
        val st = c.settingsFlow.value
        return st.providers.find { it.id == st.activeProviderId }
            ?.let {
                it.id == com.haoai.agent.platform.llama.LlamaServerController.LOCAL_PROVIDER_ID ||
                    it.baseUrl.contains("127.0.0.1")
            } == true
    }

    fun activeProviderId(): String? = c.settingsFlow.value.activeProviderId

    fun selectProvider(id: String) {
        c.updateSettings { it.copy(activeProviderId = id) }
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
