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
import com.haoai.agent.agent.engine.SubagentUpdate
import com.haoai.agent.agent.engine.ToolChanged
import com.haoai.agent.agent.engine.ToolRunState
import com.haoai.agent.agent.engine.TurnEvent
import com.haoai.agent.agent.model.ChatMessage
import com.haoai.agent.agent.policy.ApprovalRequest
import com.haoai.agent.agent.policy.PolicyEngine
import com.haoai.agent.agent.tools.takeSafe
import com.haoai.agent.data.AppContainer
import com.haoai.agent.data.StoredSession
import com.haoai.agent.data.toModel
import com.haoai.agent.data.toStored
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.contentOrNull

data class UiTool(
    val callId: String,
    val name: String,
    val brief: String,
    val state: ToolRunState = ToolRunState.RUNNING,
    val preview: String? = null,
    /** E7a spawn_agents/spawn_agent 逐路子代理状态（index 从 1 起；按到达序刷新）。 */
    val subagents: List<SubagentLine> = emptyList()
)

/** E7a 单路子代理状态行（RUNNING/DONE/ERROR + token 用量 + 简报）。 */
data class SubagentLine(
    val index: Int,
    val total: Int,
    val state: String,
    val tokensUsed: Long,
    val brief: String
)

data class ChatRow(
    /** LazyColumn 稳定 key：用消息 id，勿回退为下标（删除/重发后会整体错位）。 */
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
    /** 思考阶段打断（无上屏内容）时置位；turn finally 里落一条"已停止"标记后清零。 */
    private var stopHintPending = false

    /** E8 循环内插话队列（引擎间隙 A 消费；StateFlow 驱动 UI 排队提示）。 */
    private val interjectQueue = java.util.concurrent.ConcurrentLinkedQueue<String>()
    private val _interjectCount = kotlinx.coroutines.flow.MutableStateFlow(0)
    val interjectCount = _interjectCount.asStateFlow()

    /** v7 撤回单条排队消息（最新一条）：出队并从历史移除对应的用户消息。 */
    fun withdrawInterjection() {
        val text = interjectQueue.poll()
        _interjectCount.value = interjectQueue.size
        // 同步移除历史里最后一条内容匹配且未消费的用户消息（排队消息在入队时已可见）
        if (text != null) {
            val s = currentSession ?: return
            for (i in s.messages.indices.reversed()) {
                val m = s.messages[i]
                if (m.role == ChatMessage.ROLE_USER && m.content == text) {
                    s.messages.removeAt(i)
                    runCatching { c.sessionStore.save(s) }
                    rebuildRows()
                    return
                }
            }
        }
    }

    /** 系统分享接入（R2.2）：导航层把 ACTION_SEND 内容转存 here，ChatScreen 消费后清空。 */
    val shareText = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val shareImageUri = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    /** E8 引擎结束（/stop 或 Finished）时清空队列。 */
    private fun clearInterjections() {
        interjectQueue.clear()
        _interjectCount.value = 0
    }

    /** D16：当前会话是否被进程内其他实例的引擎持有（跨实例写保护，防截改运行中的历史）。 */
    private fun busyElsewhere(): Boolean {
        if (_running.value) return false // 自己在跑由各入口的 _running 守门
        val s = currentSession ?: return false
        return com.haoai.agent.platform.AgentRunRegistry.isActive(s.id)
    }

    /**
     * 代际栅栏（OpenClaw isCurrent 模式）：异步回调把结果写入会话 UI 前，
     * 必须确认"发起时的会话仍是当前查看的会话"，否则丢弃——防 diff/快照等
     * 慢回包落到切换后的别的会话界面上（跨会话渗漏的异步形态）。
     */
    fun isCurrentSession(sessionId: String?): Boolean =
        sessionId != null && sessionId == _session.value?.id

    private var job: Job? = null
    private val liveTools = mutableMapOf<String, UiTool>()

    /**
     * 时间轴卡快照：liveTools 普通可变 Map，直接读会有并发/重组一致性问题，
     * 也无法触发 Compose 重组。镜像一份到 StateFlow（ToolChanged 时同步）。
     */
    private val _liveToolsSnapshot =
        MutableStateFlow<List<UiTool>>(emptyList())
    val liveToolsSnapshotFlow = _liveToolsSnapshot.asStateFlow()

    /** 时间轴卡用的工具快照（UI collectAsState）。 */
    fun liveToolsSnapshot(): List<UiTool> = _liveToolsSnapshot.value

    private fun publishLiveTools() {
        // 会话门控：仅当查看的正是本实例 job 的归属会话才透出工具时间轴。
        // 引擎事件在切走后仍持续更新 liveTools（切回时时间轴无缝续上）；
        // 无运行任务（owner=null）时也透空，防止上一轮残留（与 selectSession 的 clear 对齐）。
        val owner = _runSessionId.value
        _liveToolsSnapshot.value =
            if (owner != null && _session.value?.id == owner) liveTools.values.toList() else emptyList()
    }

    private val _session = MutableStateFlow<StoredSession?>(null)
    val session = _session.asStateFlow()

    private val _rows = MutableStateFlow<List<ChatRow>>(emptyList())
    val rows = _rows.asStateFlow()

    private val _streamingText = MutableStateFlow<String?>(null)
    val streamingText = _streamingText.asStateFlow()

    /** 流式思考过程（reasoning_content / <think>），与 streamingText 同生命周期。 */
    private val _streamingReasoning = MutableStateFlow<String?>(null)
    val streamingReasoning = _streamingReasoning.asStateFlow()

    // ── ② token 批处理（60ms 节拍批刷）──
    // 引擎回调线程直接逐 token 写 StateFlow 会让 MarkdownText 每帧重组+重解析；
    // 改为 token 先进缓冲，flusher 每 40ms 合并一次上屏（肉眼仍是连续流，重组降 ~4 倍）。
    private val textBuf = StringBuilder()
    private val reasoningBuf = StringBuilder()
    private var streamFlusher: kotlinx.coroutines.Job? = null

    private fun startStreamFlusher() {
        streamFlusher?.cancel()
        streamFlusher = viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(40)
                flushStreamBuf()
            }
        }
    }

    /** 把缓冲增量并入流式 StateFlow；空缓冲零开销。flusher（Main）与收尾路径共用。 */
    private fun flushStreamBuf() {
        val t = synchronized(textBuf) {
            if (textBuf.isEmpty()) null else { val s = textBuf.toString(); textBuf.setLength(0); s }
        }
        val r = synchronized(reasoningBuf) {
            if (reasoningBuf.isEmpty()) null else { val s = reasoningBuf.toString(); reasoningBuf.setLength(0); s }
        }
        if (t != null) _streamingText.value = (_streamingText.value ?: "") + t
        if (r != null) _streamingReasoning.value = (_streamingReasoning.value ?: "") + r
        // 悬浮窗展开态正文：同一 40ms 节拍顺带镜像尾部（RunObserver 内部再截 400 字）
        if (t != null) com.haoai.agent.platform.RunObserver.setStreamTail(
            (_streamingText.value ?: "").takeLast(400)
        )
    }

    private fun appendDelta(frag: String) {
        // ④ 正文首 token 到达 → 定格思考用时（供「已思考 N 秒」显示）
        if (_thinkingMs.value == null && turnStartAt != 0L) {
            _thinkingMs.value = System.currentTimeMillis() - turnStartAt
        }
        synchronized(textBuf) { textBuf.append(frag); Unit }
    }
    private fun appendReasoning(frag: String) = synchronized(reasoningBuf) { reasoningBuf.append(frag); Unit }

    /** 结束/中断收尾：停 flusher、把残余缓冲一次性落屏。 */
    private fun endStreaming() {
        streamFlusher?.cancel()
        streamFlusher = null
        flushStreamBuf()
        _streamingText.value = null
        _streamingReasoning.value = null
    }

    // ── ④ 思考计时：正文首 token 到达即定格「已思考 N 秒」──
    @Volatile private var turnStartAt = 0L
    private val _thinkingMs = MutableStateFlow<Long?>(null)
    val thinkingMs = _thinkingMs.asStateFlow()

    private val _running = MutableStateFlow(false)

    /** 本实例 job 归属的会话：send/compact/btw 入口记入、finally 清空。
     *  会话渗漏修复（切到别的会话不再显示后台任务的进度）的归属依据。 */
    private val _runSessionId = MutableStateFlow<String?>(null)

    /** D16 跨实例停止句柄：注册表按 identity 条件摘除，须全程同一实例。 */
    private val runStopHandle: () -> Unit = { stop() }

    /**
     * D16 UI 运行态（会话门控版）：仅当「查看的正是本实例 job 的归属会话」，
     * 或「该会话被进程内其他实例的引擎持有」（实例分裂场景）时为真。
     * 停止键在后者仍可用（D16 语义保留）；查看其他会话时进度不再渗漏，
     * 后台任务的停止入口在通知任务卡/悬浮窗（全局面，属设计）。
     */
    val running = kotlinx.coroutines.flow.combine(
        _running, _runSessionId, _session, com.haoai.agent.platform.AgentRunRegistry.activeIds
    ) { own, owner, s, ids ->
        (own && owner != null && s?.id == owner) || (s != null && s.id in ids)
    }.stateIn(
        viewModelScope,
        kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000),
        false
    )

    /** 会话门控的流式展示：切走后为 null（别的会话不再渲染本任务的流式气泡），
     *  切回归属会话时带全量继续（_streamingText 后台照常累积，也是悬浮窗尾部的数据源）。 */
    val visibleStreamingText = kotlinx.coroutines.flow.combine(
        _streamingText, _runSessionId, _session
    ) { t, owner, s -> if (owner != null && s?.id == owner) t else null }
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000), null)

    val visibleStreamingReasoning = kotlinx.coroutines.flow.combine(
        _streamingReasoning, _runSessionId, _session
    ) { r, owner, s -> if (owner != null && s?.id == owner) r else null }
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000), null)

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
        // 代际栅栏：读快照期间切走了会话 → diff 不落地（否则会显示在别的会话界面上）
        if (!isCurrentSession(sid)) return null
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
        detachStreaming() // 新建会话不中断进行中的任务（结果写回原会话），仅摘除流式展示
        val s = StoredSession.create(c.workspace.workspaceUriForSession)
        currentSession = s
        c.sessionStore.save(s)
        // liveTools 保留（运行中任务的归属数据），可见性由 publishLiveTools 的会话门控决定
        publishLiveTools()
        sessionIn = 0
        sessionOut = 0
        _session.value = s
        rebuildRows()
        refreshSessions()
        refreshTodos()
    }

    fun selectSession(id: String) {
        if (_session.value?.id == id) return
        detachStreaming() // 切换会话不中断进行中的任务（结果写回原会话），仅摘除流式展示
        val s = c.sessionStore.load(id) ?: return
        // E1 进程死亡检测：持久化 running 且登记表确认进程内已无引擎持有，才判中断。
        // D16：实例分裂/切走再切回时旧实例的引擎可能仍在跑，误标会弹假「任务中断」横幅
        if (s.runState == "running" && !com.haoai.agent.platform.AgentRunRegistry.isActive(s.id)) {
            s.runState = com.haoai.agent.data.StoredSession.RUN_INTERRUPTED
            runCatching { c.sessionStore.save(s) }
        }
        currentSession = s
        // liveTools 保留（运行中任务的归属数据），可见性由 publishLiveTools 的会话门控决定：
        // 切到其他会话透空，切回运行会话时间轴无缝续上
        publishLiveTools()
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
        if (_running.value || busyElsewhere()) return
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
        if (_running.value || busyElsewhere()) return
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
        if (_running.value || busyElsewhere()) return
        val s = currentSession ?: return
        val idx = s.messages.indexOfFirst { it.id == messageId }
        if (idx < 0) return
        val m = s.messages[idx]
        if (m.role != ChatMessage.ROLE_USER) return
        val text = newText.trim()
        if (text.isEmpty()) return
        val imageData = m.imageData
        val audioPath = m.audioPath
        val videoPath = m.videoPath
        while (s.messages.size > idx) s.messages.removeAt(s.messages.size - 1)
        c.sessionStore.save(s)
        rebuildRows()
        send(text, imageData, audioPath, videoPath)
    }

    fun send(rawText: String, imageData: String? = null, audioPath: String? = null, videoPath: String? = null) {
        val text = rawText.trim()
        // 纯图片发送（无文字）也允许：否则 UI 已清掉 pendingImage，图片会静默丢失
        if (text.isEmpty() && imageData == null && audioPath == null && videoPath == null) return
        // E8/v7 循环内插话+排队：生成期间用户发送 → 入队（引擎间隙 A 注入当轮跟进；
        // 若任务结束仍未消费，作为新任务自动执行）。入队同时落一条可见用户消息，
        // 让用户看到自己的消息已排队（排队态反馈）
        if (_running.value) {
            if (text.isNotEmpty()) {
                interjectQueue.add(text)
                _interjectCount.value = interjectQueue.size
                val s0 = currentSession
                if (s0 != null) {
                    s0.messages.add(ChatMessage(role = ChatMessage.ROLE_USER, content = text).toStored())
                    runCatching { c.sessionStore.save(s0) }
                    rebuildRows()
                }
            }
            return
        }
        val s0 = currentSession
        // D16：同会话在进程内其他实例上仍被引擎持有 → 拒绝双跑（两路引擎并发会交叉写坏历史）
        if (s0 != null && com.haoai.agent.platform.AgentRunRegistry.isActive(s0.id)) {
            _error.value = "该会话的任务仍在后台运行，请先按停止键结束，或稍候再发送"
            return
        }
        // 聊天会话模型路由：设置里「模型切换 → 聊天会话」优先（可精确到同供应商某模型），
        // 未配置回落激活供应商
        val provider0 = c.activeChatProvider() ?: c.activeProvider()
        if (provider0 == null) {
            _error.value = "请先在「设置」里配置模型服务（Base URL / 模型 ID / API Key）"
            // 深链/后台发送的消息此前会被静默丢弃（错误条 8s 即逝），留下痕迹供用户看到（P2-6）
            appendAndNotifyLocal(text)
            return
        }
        // 发送门控：公网供应商未配 key 就地拦截（此前无 Authorization 照发、等供应商 401 才报错）
        if (c.resolveApiKey(provider0).isBlank() && c.needsApiKey(provider0.baseUrl)) {
            _error.value = "模型服务「${provider0.name}」未配置 API Key，请到「设置」填写后再发送"
            appendAndNotifyLocal(text)
            return
        }
        val s = currentSession ?: return
        // 新回合清空上一轮的工具活动态：liveTools 跨轮次保留会把旧工具当成
        // 「刚完成」灌进任务卡/悬浮窗（旧消息的完成态由落库结果兜底渲染，清掉无碍）
        liveTools.clear()
        publishLiveTools()
        _runSessionId.value = s.id
        _running.value = true
        _streamingText.value = null
        _streamingReasoning.value = null
        _thinkingMs.value = null
        turnStartAt = System.currentTimeMillis()
        startStreamFlusher()
        // D16: 登记到进程级注册表——其他实例据此显示停止键、路由停止、豁免「死亡」误判
        com.haoai.agent.platform.AgentRunRegistry.register(s.id, runStopHandle)
        // 恢复注入（[系统恢复] 前缀）不作为新目标：目标已在 resumeRun 里剥壳保留。
        // 否则恢复横幅把上一次的恢复文本当目标，断一次叠一层（真机实测套娃 bug）
        val isResumeInject = text.startsWith("[系统恢复]")
        // 编排层可观测性：进程级任务卡开始采集（通知/悬浮窗的唯一数据源）
        com.haoai.agent.platform.RunObserver.start(
            s.id,
            (if (isResumeInject) s.runGoal else null)?.takeSafe(80) ?: text.takeSafe(80)
        )
        if (c.settingsFlow.value.keepAlive) com.haoai.agent.platform.KeepAliveService.start(c.appContext)
        if (c.settingsFlow.value.runOverlay) {
            com.haoai.agent.platform.AgentOverlayService.start(c.appContext)
        }
        // 运行时任务视图隐藏：Agent 运行期间把本应用任务移出最近任务（防误清）
        com.haoai.agent.platform.TaskVisibility.apply(c.appContext, c.settingsFlow.value.vscreenHideTask)
        // E1: 入口置 running + goal（被杀后据此展示恢复横幅）——立即持久化
        if (!isResumeInject) s.runGoal = text.takeSafe(200)
        s.runTurnsUsed = 0
        s.runState = "running"
        // 落盘前回填 store 里较新的标题状态（上一轮标题协程可能刚写完，副本是旧的）
        syncTitleFromStore(s)
        runCatching { c.sessionStore.save(s) }
        job = viewModelScope.launch {
            var turnEngine: AgentEngine? = null
            try {
                val provider = resolveProvider(provider0)
                if (provider == null) {
                    _error.value = c.llama.state.value.let {
                        (it as? com.haoai.agent.platform.llama.LlamaState.Failed)?.message
                            ?: "端侧模型启动失败"
                    }
                    // 消息保留进历史：此前只弹 8s 错误条，深链/排队发送会看似凭空消失（P2-6）
                    appendAndNotifyLocal(text)
                    return@launch
                }
                val engine = buildEngine(s, provider).also { turnEngine = it }
                engine.runTurn(
                    userText = text,
                    onDelta = ::appendDelta,
                    onEvent = ::handleEvent,
                    imageData = imageData,
                    audioPath = audioPath,
                    videoPath = videoPath,
                    onReasoning = ::appendReasoning
                )
            } finally {
                endStreaming()
                // running 必须先于 flushUsage 翻：flushUsage 挂 IO 会跨帧，期间
                // showStreaming(=running) 仍真 → 最终行下方渲染空"正在思考"占位气泡，
                // 消失时又触发一轮滚动修正，表现为结束瞬间先冲过头再弹回的抖动
                _running.value = false
                _runSessionId.value = null
                publishLiveTools() // 归属清空 → 门控立即透空，防上一轮工具卡残留
                com.haoai.agent.platform.AgentRunRegistry.unregister(s.id, runStopHandle)
                com.haoai.agent.platform.RunObserver.end()
                flushUsage()
                com.haoai.agent.platform.TaskVisibility.apply(c.appContext, false)
                // E1: 按引擎结束状态持久化（null→idle；CancellationException 已置 idle）
                val endState = turnEngine?.runEndState ?: com.haoai.agent.data.StoredSession.RUN_IDLE
                // 思考阶段被打断：历史里补可见的停止标记（P3-1），别让用户消息孤零零挂着
                if (stopHintPending) {
                    stopHintPending = false
                    val lastAssistant = s.messages.lastOrNull { it.role == ChatMessage.ROLE_ASSISTANT }
                    if (lastAssistant == null || lastAssistant.content.isBlank()) {
                        s.messages.add(
                            ChatMessage(role = ChatMessage.ROLE_ASSISTANT, content = "⏹ 已手动停止").toStored()
                        )
                    }
                }
                // 落盘前回填 store 里较新的标题状态：打断/结束时标题协程可能已写 store，
                // 用发送前的旧副本直接 save 会把新标题盖回旧值（e2e P2-1 打断标题回退根因）
                syncTitleFromStore(s)
                s.runState = endState
                runCatching { c.sessionStore.save(s) }
                // 用户可能已切换会话：仅在仍看该会话时回写视图（copy 确保 StateFlow 重发射）
                if (_session.value?.id == s.id) {
                    val view = s.copy()
                    currentSession = view
                    _session.value = view
                }
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
                // v7 排队任务：回合正常结束且队列还有排队消息 → 自动作为新任务执行
                // （运行中可继续派活，队列在任务完成后逐条落地）。
                // 非正常结束（停止/失败）不清队列也不自动续跑——用户按停止即表态中止。
                val autoNext = interjectQueue.poll()
                if (autoNext != null && endState == com.haoai.agent.data.StoredSession.RUN_IDLE) {
                    _interjectCount.value = interjectQueue.size
                    // 落一条系统提示说明排队语义（历史可见，非静默）
                    appendAndNotifyLocal(autoNext)
                    send(autoNext)
                } else if (autoNext != null) {
                    // 停止/失败：放回队列头不丢消息，等用户手动重发
                    val q = java.util.concurrent.ConcurrentLinkedQueue<String>()
                    q.add(autoNext); q.addAll(interjectQueue)
                    interjectQueue.clear(); interjectQueue.addAll(q)
                }
            }
        }
    }

    /** v7 排队续跑：把排队消息落为正式用户消息（入历史），再作为新任务发送。 */
    private fun appendAndNotifyLocal(text: String) {
        val s = currentSession ?: return
        s.messages.add(ChatMessage(role = ChatMessage.ROLE_USER, content = text).toStored())
        runCatching { c.sessionStore.save(s) }
        rebuildRows()
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
    /** 把 store 里较新的标题状态回填进会话副本。标题协程是异步写 store 的，而
     *  send/resume 持有的是发送前捕获的副本——直接 save(旧副本) 会把新生成的标题
     *  盖回旧值，陈旧 titleAuto 还会触发重复生成（打断后标题回退的根因，e2e P2-1）。 */
    private fun syncTitleFromStore(s: StoredSession) {
        if (s.titleAuto) return
        runCatching { c.sessionStore.load(s.id) }.getOrNull()?.let { fresh ->
            if (fresh.titleAuto) {
                s.titleAuto = true
                if (fresh.title.isNotBlank()) s.title = fresh.title
            }
        }
    }

    private fun maybeGenerateTitle(s: StoredSession) {
        syncTitleFromStore(s)
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
            }?.content?.lineSequence()?.firstOrNull()?.trim()?.takeSafe(12)
            if (!fallback.isNullOrBlank()) {
                s.titleAuto = true
                s.title = fallback
                c.sessionStore.save(s)
                refreshSessions()
                if (_session.value?.id == s.id) _session.value = s.copy()
            }
            return
        }
        // 先落标记再请求：失败也不重试，避免每轮都烧 token。
        // 用 store 最新实例落盘：直接 save(旧副本) 会盖掉期间其他写入（P2-1 同源）
        runCatching {
            val latest = c.sessionStore.load(s.id) ?: s
            latest.titleAuto = true
            s.titleAuto = true
            c.sessionStore.save(latest)
        }
        viewModelScope.launch {
            // 点6：标题模型链式降级——主+备用按序尝试，拿到非空标题即止
            val chain = if (titleCfgId.isEmpty()) emptyList()
            else runCatching {
                c.resolvePurposeTargets(titleCfgId, c.settingsFlow.value.titleFallbackIds)
            }.getOrDefault(emptyList()).map { it.provider }
            val targets = (if (chain.isEmpty()) listOf(mainProvider) else chain).distinctBy { it.id }
            for (target in targets) {
                val done = try {
                    val provider = resolveProvider(target) ?: continue
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
                        .takeSafe(24)
                    if (t.isNotBlank()) {
                        // 落盘用 store 最新实例：期间可能有 runState 等其他写入，
                        // 标题/titleAuto 字段以本次为准；副本与 currentSession 同步更新
                        val latest = c.sessionStore.load(s.id) ?: s
                        latest.titleAuto = true
                        latest.title = t
                        s.titleAuto = true
                        s.title = t
                        c.sessionStore.save(latest)
                        refreshSessions()
                        // copy() 强制 StateFlow 发射（同引用修改不会触发更新）
                        if (_session.value?.id == s.id) _session.value = latest.copy()
                        if (currentSession?.id == s.id) currentSession = latest.copy()
                        true
                    } else false
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.w("HaoTitle", "会话标题生成失败（${target.name}）: ${e.message}")
                    false
                }
                if (done) break
            }
        }
    }

    fun stop() {
        // D16：本实例没在跑但注册表里有当前会话的登记（其他实例的引擎）→ 路由过去停。
        // 句柄身份比较：登记方就是自己（宿主 finally 清理窗口内）时不路由，直接走本地取消。
        val s = currentSession
        if (!_running.value && s != null) {
            val owner = com.haoai.agent.platform.AgentRunRegistry.ownerOf(s.id)
            if (owner != null && owner !== runStopHandle) {
                owner.invoke()
                return
            }
        }
        // 思考阶段打断（尚无任何上屏内容）时补一条可见的停止标记——否则用户消息
        // 孤零零挂着，看不出是被打断还是没响应（e2e P3-1）
        val noContentYet = synchronized(textBuf) { textBuf.isBlank() } &&
            _streamingText.value.isNullOrBlank()
        if (_running.value && noContentYet) stopHintPending = true
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
        // todo 清单镜像进任务卡（通知显示 N/M 完成）。
        // 仅当查看的正是运行中的会话时才镜像，防止切走会话后把别的清单灌进任务卡
        val obs = com.haoai.agent.platform.RunObserver.state.value
        if (obs.sessionId == s.id) {
            com.haoai.agent.platform.RunObserver.setTodos(
                _todoItems.value.map { Triple(it.text, it.status, it.priority) }
            )
        }
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
                _runSessionId.value = s.id
                _running.value = true
                // 挂到 job 上：/stop 能取消压缩（否则 UI 显示运行中但停止键无效）
                job = viewModelScope.launch {
                    try {
                        val summary = engine.compactNow()
                        if (summary != null) {
                            _streamingText.value = null
                            rebuildRows()
                        }
                    } finally {
                        _running.value = false
                        _runSessionId.value = null
                        _streamingText.value = null
                        refreshSessions()
                    }
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
        val provider0 = c.activeChatProvider() ?: c.activeProvider()
        if (provider0 == null) {
            _error.value = "请先配置模型服务"
            return
        }
        val s = currentSession ?: return
        _runSessionId.value = s.id
        _running.value = true
        _streamingText.value = null
        _streamingReasoning.value = null
        _thinkingMs.value = null
        turnStartAt = System.currentTimeMillis()
        startStreamFlusher()
        publishLiveTools()
        com.haoai.agent.platform.TaskVisibility.apply(c.appContext, c.settingsFlow.value.vscreenHideTask)
        // D16: /btw 同样登记（与 send 共用同一停止句柄，finally 对称摘除）
        com.haoai.agent.platform.AgentRunRegistry.register(s.id, runStopHandle)
        com.haoai.agent.platform.RunObserver.start(s.id, "附带问题：${question.takeSafe(60)}")
        if (c.settingsFlow.value.runOverlay) {
            com.haoai.agent.platform.AgentOverlayService.start(c.appContext)
        }
        job = viewModelScope.launch {
            try {
                val provider = resolveProvider(provider0) ?: run {
                    _error.value = "端侧模型启动失败"
                    return@launch
                }
                val engine = buildEngine(s, provider)
                engine.runBtw(
                    question = question,
                    onDelta = ::appendDelta,
                    onReasoning = ::appendReasoning
                )
            } finally {
                _running.value = false
                _runSessionId.value = null
                endStreaming()
                com.haoai.agent.platform.AgentRunRegistry.unregister(s.id, runStopHandle)
                com.haoai.agent.platform.RunObserver.end()
                com.haoai.agent.platform.TaskVisibility.apply(c.appContext, false)
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
        // 后台可见性：通知升级高优 + 悬浮窗变橙「等待确认」（不弹窗口，仅状态呈现）
        com.haoai.agent.platform.RunObserver.setApproval(req.title, req.detail)
        // try/finally：协程被取消（如用户按停止）时也要清掉弹层状态
        try {
            return gate.await()
        } finally {
            _approval.value = null
            com.haoai.agent.platform.RunObserver.setApproval(null)
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
                // 先把缓冲残余落屏再摘流式气泡，避免最后 <40ms 的 token 在
                // 最终消息接管显示后又闪回流式区（内容已入 rows，不丢数据，纯观感）
                flushStreamBuf()
                _streamingText.value = null
                ev.message.toolCalls.forEach { call ->
                    liveTools[call.id] = UiTool(call.id, call.name, briefFor(call.name, call.argumentsJson))
                }
                publishLiveTools()
                rebuildRows()
            }
            is ToolChanged -> {
                liveTools[ev.update.callId] = UiTool(
                    ev.update.callId,
                    liveTools[ev.update.callId]?.name ?: "",
                    ev.update.brief ?: "",
                    ev.update.state,
                    ev.update.preview,
                    liveTools[ev.update.callId]?.subagents ?: emptyList()
                )
                publishLiveTools()
                rebuildRows()
                publishSteps()
            }
            is SubagentUpdate -> {
                // E7a：按 callId 聚合各路子代理状态（并发到达，同 index 覆盖旧态）
                val prev = liveTools[ev.callId]
                val lines = prev?.subagents.orEmpty().toMutableList()
                val at = lines.indexOfFirst { it.index == ev.index }
                val line = SubagentLine(ev.index, ev.total, ev.state, ev.tokensUsed, ev.brief)
                if (at >= 0) lines[at] = line else lines.add(line)
                liveTools[ev.callId] = UiTool(
                    ev.callId,
                    prev?.name ?: "",
                    prev?.brief ?: "",
                    prev?.state ?: ToolRunState.RUNNING,
                    prev?.preview,
                    lines.sortedBy { it.index }
                )
                publishLiveTools()
                rebuildRows()
                publishSteps()
            }
            is Finished -> {
                // 运行期错误走会话门控：仅当用户仍看着运行会话才弹 Snackbar——
                // 切到别的会话时不再把 A 的失败提示弹进 B（归属会话的历史里有失败痕迹）
                if (ev.error != null && ev.error != "已停止") runError(ev.error)
                // 5.2 降级提示：回合结束消费（SnackBar 显示「已降级到 X」）
                c.lastFallbackNotice?.let { name ->
                    c.lastFallbackNotice = null
                    runError("已降级到 $name（主服务请求失败）")
                }
            }
        }
    }

    /** 运行期错误的会话门控弹出：查看的正是运行会话（或无运行任务）才弹，否则留给历史/通知卡兜底。 */
    private fun runError(msg: String) {
        val owner = _runSessionId.value
        if (owner == null || owner == _session.value?.id) _error.value = msg
    }

    /** 把当前活动工具状态镜像给 RunObserver（通知/悬浮窗消费）。 */
    private fun publishSteps() {
        val running = liveTools.values.filter { it.state == ToolRunState.RUNNING }
        val steps = if (running.isNotEmpty()) {
            running.map { com.haoai.agent.platform.RunObserver.Step(it.callId, it.name, it.brief, "running") }
        } else {
            // 全部已结算：取最近完成的 1 条作为「刚完成」提示（含错误态）
            liveTools.values.maxByOrNull { it.callId }?.let {
                listOf(com.haoai.agent.platform.RunObserver.Step(it.callId, it.name, it.brief, if (it.state == ToolRunState.ERROR) "error" else "done"))
            } ?: emptyList()
        }
        com.haoai.agent.platform.RunObserver.setSteps(steps)
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
                                preview = previewLine(stored.first),
                                subagents = live?.subagents ?: emptyList()
                            )
                            live != null -> live
                            else -> base
                        }
                    }
                    // key 用消息 id：下标 key 在删除/截断/重新生成后会整体平移，列表状态错乱
                    rows.add(
                        ChatRow(
                            m.id.ifBlank { "m$i" }, m.id, m.role, m.content, m.error, tools, m.reasoning,
                            ts = m.ts,
                            promptTokens = m.promptTokens,
                            completionTokens = m.completionTokens,
                            durationMs = m.durationMs,
                            model = m.model
                        )
                    )
                }
                ChatMessage.ROLE_USER ->
                    rows.add(ChatRow(m.id.ifBlank { "m$i" }, m.id, m.role, m.content, ts = m.ts))
                else -> Unit
            }
        }
        _rows.value = rows
        recalcContextUsage()
    }

    private fun previewLine(content: String): String =
        content.lineSequence().firstOrNull()?.takeSafe(160) ?: ""

    private fun briefFor(toolName: String, argsJson: String): String =
        com.haoai.agent.agent.tools.ToolBrief.of(toolName, argsJson)

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
            apiKey = c.resolveApiKey(provider),
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
            // C6/C1 统一配置入口：config_get 渲染镜像；config_set 合并补丁→桥严格校验→同步入库
            configRender = { c.configBridge.render(c.settingsFlow.value) },
            configPreview = { patch ->
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching { c.configBridge.previewPatch(patch) }.getOrElse {
                        com.haoai.agent.data.ConfigFileBridge.Preview(err = it.message ?: "预检失败")
                    }
                }
            },
            configMutator = { args ->
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching {
                        val (merged, err) = c.configBridge.mergePatch(args)
                        if (err != null) {
                            com.haoai.agent.agent.tools.ToolResult("配置被拒绝：$err（未生效）", true)
                        } else {
                            // applyFull 内部：快照 → 入库 → MCP/SSH 分区同步（含连接状态备注）
                            val p = c.configBridge.applyFull(merged)
                            if (!p.ok) com.haoai.agent.agent.tools.ToolResult(
                                "配置被拒绝：${p.message}（未生效，修正后重新调用 config_set 即可）", true
                            ) else com.haoai.agent.agent.tools.ToolResult("配置已应用：${p.message}")
                        }
                    }.getOrElse {
                        com.haoai.agent.agent.tools.ToolResult("配置修改失败：${it.message}", true)
                    }
                }
            },
            onToolChange = { refreshTodos() },
            vscreenEnabled = st.vscreenEnabled &&
                android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R,
            vscreenBitrateKbps = st.vscreenBitrateKbps,
            budgetHint = { com.haoai.agent.data.UsageLedger.budgetHint(st.dailyTokenBudgetK) },
            // E5 单轮熔断：token 上限 + 圈数上限 + 连续工具失败阈值（设置-模型行为）
            // 交互聊天用设置值（默认 25 万，0=不限）；无人值守（定时/工作流）走引擎默认 15 万硬限；
            // 总开关关闭时交互聊天完全不熔断
            turnTokenCap = if (st.costBreakerEnabled) st.turnTokenCap else 0,
            toolCallCap = if (st.costBreakerEnabled) st.toolCallCap else 0,
            softBudgetWarn = st.costBreakerEnabled && st.softBudgetWarn,
            toolFailCap = st.consecutiveToolFailCap,
            memoryTarget = {
                c.resolvePurposeTargets(st.memoryExtractProviderId, st.memoryExtractFallbackIds)
                    .map { it.provider to it.apiKey }
            },
            summarizeTarget = {
                c.resolvePurposeTargets(st.summarizeProviderId, st.summarizeFallbackIds)
                    .map { it.provider to it.apiKey }
            },
            auxClientFor = { p -> c.clientFor(p) },
            // P2 能力委派：主模型缺图像/音频模态时，delegate_to_vision/transcribe_audio
            // 工具经此解析设置的委派模型（"pid" 或 "pid|modelId"）；空配置→引擎不注册工具
            delegateTarget = { kind ->
                val id = when (kind) {
                    "vision" -> st.visionProviderId.trim()
                    else -> st.asrProviderId.trim()
                }
                if (id.isBlank()) emptyList()
                else c.resolvePurposeTargets(id, emptyList()).map { it.provider to it.apiKey }
            },
            planGate = { _planMode.value },
            // E8 循环内插话队列：生成期间用户新指令入队，引擎在安全间隙合并注入
            interjectQueue = interjectQueue
        )
    }


    /**
     * 自身运行状态全文：由 app_status 工具按需读取，
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

    /** update_settings 白名单落地已废弃（C1/C6：统一走 config_set 工具 + 桥校验）。 */

    private val _usage = kotlinx.coroutines.flow.MutableStateFlow(
        (c.settingsFlow.value.tokenInTotal.toLong() to c.settingsFlow.value.tokenOutTotal.toLong())
    )
    val usage: kotlinx.coroutines.flow.StateFlow<Pair<Long, Long>> = _usage

    private var sessionIn = 0L
    private var sessionOut = 0L

    // ── token 计数落盘防抖 ───────────────────────────────────────
    // 每次 Usage 事件直接 updateSettings 会同步写 settings.json + 重渲染配置镜像
    // （两次磁盘 IO，全在调用线程）。改为累积增量、1s 节拍/回合结束统一落盘。
    private var pendingIn = 0L
    private var pendingOut = 0L
    private var usageFlushJob: Job? = null

    private fun addUsage(pin: Long, pout: Long) {
        val cur = _usage.value
        _usage.value = (cur.first + pin) to (cur.second + pout)
        sessionIn += pin
        sessionOut += pout
        pendingIn += pin
        pendingOut += pout
        if (usageFlushJob?.isActive != true) {
            usageFlushJob = viewModelScope.launch {
                kotlinx.coroutines.delay(1_000)
                flushUsage()
            }
        }
    }

    /** 把累积的 token 增量落进设置（IO 线程写盘）；回合结束/防抖到期时调用。 */
    private suspend fun flushUsage() {
        val pin = pendingIn
        val pout = pendingOut
        if (pin == 0L && pout == 0L) return
        pendingIn = 0
        pendingOut = 0
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA)
                .format(java.util.Date())
            c.updateSettings { s ->
                val sameDay = s.tokenDay == today
                s.copy(
                    tokenInTotal = s.tokenInTotal + pin,
                    tokenOutTotal = s.tokenOutTotal + pout,
                    tokenDay = today,
                    tokenInToday = (if (sameDay) s.tokenInToday else 0L) + pin,
                    tokenOutToday = (if (sameDay) s.tokenOutToday else 0L) + pout
                )
            }
        }
    }

    // ── 上下文用量估算（防抖 + IO）────────────────────────────────
    // 每条 MessageAdded/ToolChanged 事件都会触发；此前在主线程同步做：
    // 新建引擎（含 Keystore 解密）+ 全量系统提示构建（沙箱探测/技能索引/记忆/日志/账本读盘）
    // + 序列化全部工具 schema 估算 token——工具循环期间每个事件都来一遍，是发热主源之一。
    // 改为：主线程只拍快照，IO 线程延迟 300ms 计算（突发事件合并成一次），结果回主线程发射。
    private var contextJob: Job? = null

    /** 重新估算上下文使用量（UI 实时显示）。 */
    private fun recalcContextUsage() {
        val s = _session.value ?: return
        // 快照在主线程取：IO 计算期间引擎仍可能向 messages 追加消息
        val messagesSnapshot = s.messages.toList()
        contextJob?.cancel()
        contextJob = viewModelScope.launch {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                kotlinx.coroutines.delay(300)
                runCatching {
                    val settings = c.settingsFlow.value
                    val isLocal = settings.providers.find { it.id == settings.activeProviderId }
                        ?.let {
                            it.id == com.haoai.agent.platform.llama.LlamaServerController.LOCAL_PROVIDER_ID ||
                                it.baseUrl.contains("127.0.0.1")
                        } == true
                    val contextWindow = if (isLocal) settings.localContextLength
                        else settings.providers.find { it.id == settings.activeProviderId }
                            ?.effectiveContextLength() ?: 32768
                    val provider = c.activeProvider() ?: com.haoai.agent.data.ProviderConfig(
                        id = "estimate", name = "估算", baseUrl = "https://estimate.invalid", model = "-"
                    )
                    val engine = buildEngine(s, provider)
                    // 拆分口径与真实请求（AgentEngine.buildApiMessages）对齐：
                    // 系统提示=基础+注入分列；压缩摘要作为独立 system 消息单独计；
                    // 历史只送最近 MAX_HISTORY 条且 tool 结果截断 REQ_CAP 字符；
                    // 回复上限（max_tokens）占用窗口，从"剩余可用"中扣除
                    val (sysTok, injTok, toolsTok) = engine.estimateOverheadBreakdown()
                    val summaryTok = ContextUsage.estimateStringTokens(s.compactionSummary ?: "")
                    val histTok = messagesSnapshot
                        .takeLast(com.haoai.agent.agent.engine.AgentEngine.MAX_HISTORY)
                        .sumOf {
                            ContextUsage.estimateMessageTokens(
                                it.toModel(),
                                toolContentCap = com.haoai.agent.agent.engine.AgentEngine.REQ_CAP
                            )
                        }
                    val reservedTok = provider.effectiveMaxTokens().coerceAtLeast(0)
                    com.haoai.agent.ui.chat.ContextUsage(
                        usedTokens = sysTok + injTok + toolsTok + summaryTok + histTok,
                        totalTokens = contextWindow,
                        systemTokens = sysTok,
                        toolsTokens = toolsTok,
                        historyTokens = histTok,
                        injectedTokens = injTok,
                        summaryTokens = summaryTok,
                        reservedTokens = reservedTok
                    )
                }.getOrNull()
            }?.let { usage ->
                _contextUsage.value = usage
            }
        }
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
        (c.activeChatProvider() ?: c.activeProvider())?.let { "${it.name} · ${it.model}" }

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

    /** 点1：切换供应商并选中其下指定模型（聊天 /model 切换器）。 */
    fun selectModel(providerId: String, modelId: String) {
        c.updateSettings { s ->
            s.copy(
                providers = s.providers.map {
                    if (it.id == providerId && it.model != modelId) it.copy(model = modelId) else it
                },
                activeProviderId = providerId
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

    /**
     * E1 断点恢复：把恢复引导作为普通用户消息注入并重新 runTurn
     * （不复活内存状态，靠持久化历史 + 压缩摘要 + handoff 接续）。
     */
    fun resumeRun() {
        val s = currentSession ?: return
        if (_running.value) return
        // D16：引擎还在其他实例上跑着同一会话，谈不上「恢复」
        if (com.haoai.agent.platform.AgentRunRegistry.isActive(s.id)) return
        // 剥掉历史恢复包装（点击继续后再次中断会嵌套），还原最初的任务目标
        val goal = (s.runGoal?.let { stripResumeNesting(it) } ?: "未记录目标").take(200)
        val turns = s.runTurnsUsed
        val resumeText = "[系统恢复] 上次任务在第 $turns 轮中断，未完成目标：$goal。请先评估当前进度（可读文件/记忆核实），再继续执行。"
        val view = s.copy(runState = com.haoai.agent.data.StoredSession.RUN_IDLE, runGoal = goal)
        currentSession = view
        c.sessionStore.save(view)
        _session.value = view
        send(resumeText, null)
    }

    /** 恢复文本的包裹层（可嵌套）：剥掉全部恢复包装，还原最初的任务目标。 */
    private val resumeWrapper = Regex(
        "\\[系统恢复\\] 上次任务在第 \\d+ 轮中断，未完成目标：|。?请先评估当前进度（可读文件/记忆核实），再继续执行。"
    )

    private fun stripResumeNesting(t: String): String =
        t.replace(resumeWrapper, "").trim().ifBlank { t }

    /** E1 忽略恢复：清状态不注入（copy 实例使 StateFlow 必然重发射，否则横幅不消失）。 */
    fun dismissResume() {
        val s = currentSession ?: return
        val view = s.copy(runState = com.haoai.agent.data.StoredSession.RUN_IDLE, runGoal = null)
        currentSession = view
        c.sessionStore.save(view)
        _session.value = view
    }

    /**
     * 技能复盘（OpenClaw Skill Workshop「Learn from past conversations」#142909 对标）：
     * 把最近会话的对话节选打包成一个可见的聊天任务——过程可观察、可插话纠偏、可停止；
     * 产出走 skill save，由 SkillGuard 自动分流（只读技能直接启用，写入类进候选态人审）。
     * 手动触发不会打开任何"自动自学习"开关。
     */
    fun learnFromHistory() {
        if (_running.value) {
            _error.value = "当前有任务在跑，请先停止或等它结束再开始复盘"
            return
        }
        val digest = buildHistoryDigest()
        if (digest.isBlank()) {
            _error.value = "还没有可复盘的会话历史"
            return
        }
        newSession()
        currentSession?.title = "技能复盘 · ${java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date())}"
        send(
            buildString {
                appendLine("[技能复盘] 下面附上了最近几个会话的对话节选。请通读后提炼值得沉淀为技能的可复用经验：")
                appendLine("走通的多步流程、踩坑后找到的修复路径、被用户纠正过的做法。")
                appendLine("纪律：先用 skill list/view 查重，强化旧条目而不是追加复制品；")
                appendLine("没有值得沉淀的就明确回答\"无\"，不要硬凑；每条技能正文按 When to Use / Procedure / Pitfalls 结构写。")
                appendLine()
                append(digest)
            },
            null
        )
    }

    /** 近期会话对话节选：最多 6 个会话，每个取尾部 8 条实质消息，全局 8000 字封顶。 */
    private fun buildHistoryDigest(): String {
        val recent = c.sessionStore.list().sortedByDescending { it.updatedAt }.take(6)
        val sb = StringBuilder()
        for (s in recent) {
            val msgs = s.messages.filter {
                (it.role == ChatMessage.ROLE_USER || it.role == ChatMessage.ROLE_ASSISTANT) &&
                    it.content.isNotBlank()
            }
            if (msgs.isEmpty()) continue
            sb.appendLine("── 会话「${s.title}」（共 ${msgs.size} 条）──")
            var budget = 1400
            for (m in msgs.takeLast(8)) {
                if (budget <= 0) break
                val who = if (m.role == ChatMessage.ROLE_USER) "用户" else "助手"
                val text = m.content.replace(Regex("\\s+"), " ").trim().take(260)
                sb.appendLine("$who：$text")
                budget -= text.length
            }
            if (sb.length > 8000) break
        }
        return sb.toString().trim()
    }

    /** 摘除当前会话的流式展示（切换/新建会话时调用；任务继续在后台跑，不中断）。 */
    private fun detachStreaming() {
        _streamingText.value = null
        _streamingReasoning.value = null
    }

    private fun stopInternal() {
        job?.cancel()
        job = null
        _running.value = false
        // 取消是异步的（send/compact/btw 的 finally 会迟到一拍），归属先摘，
        // 防止停止瞬间门控流仍把该会话判为运行中
        _runSessionId.value = null
        publishLiveTools()
        // 停 flusher 并丢弃缓冲残余（任务已取消，未上屏的尾部 token 不再有意义）
        streamFlusher?.cancel()
        streamFlusher = null
        synchronized(textBuf) { textBuf.setLength(0) }
        synchronized(reasoningBuf) { reasoningBuf.setLength(0) }
        _streamingText.value = null
        _streamingReasoning.value = null
        clearInterjections()
    }
}
