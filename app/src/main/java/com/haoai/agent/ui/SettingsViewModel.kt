package com.haoai.agent.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.haoai.agent.agent.policy.PermissionMode
import com.haoai.agent.agent.skills.SkillStore
import com.haoai.agent.data.AppContainer
import com.haoai.agent.data.AppSettings
import com.haoai.agent.data.ProviderConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonObject
import java.util.UUID

data class ProviderDraft(
    val id: String? = null,
    val name: String = "",
    val baseUrl: String = "",
    val model: String = "",
    val apiKeyPlain: String = "",
    /** 上下文窗口 tokens；空串=自动（按模型名推测） */
    val contextLength: String = "",
    /** 单次回复上限 max_tokens；空串=云端供应商默认 / 本地 4096 */
    val maxTokens: String = "",
    /** 协议：openai_compat（默认）| anthropic（原生 Messages API） */
    val protocol: String = "openai_compat",
    // ── 点1：同供应商多模型（不含当前 model；当前 model 的能力编辑写回对应条目）──
    val models: List<com.haoai.agent.data.ModelEntry> = emptyList(),
    // ── 点2：采样参数发送开关（值用字符串承载输入框内容）──
    val sendTemperature: Boolean = false,
    val temperature: String = "1.0",
    val sendTopP: Boolean = false,
    val topP: String = "1.0",
    val sendPresencePenalty: Boolean = false,
    val presencePenalty: String = "0",
    val sendFrequencyPenalty: Boolean = false,
    val frequencyPenalty: String = "0",
    // ── 点5：Key 池——existing=已存密文（原样保留/删除），addPlain=本次新增明文 ──
    val poolCiphers: List<String> = emptyList(),
    val poolAddPlain: List<String> = emptyList(),
    val keyRotation: String = "ROUND_ROBIN",
    // ── 点3：余额查询 ──
    val balanceEnabled: Boolean = false,
    val balanceApiPath: String = "/credits",
    val balanceJsonPath: String = "data.total_usage"
)

data class ProviderPreset(
    val label: String,
    val name: String,
    val baseUrl: String,
    val model: String
)

object ProviderPresets {
    val all = listOf(
        ProviderPreset("DeepSeek", "DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat"),
        ProviderPreset("OpenRouter", "OpenRouter", "https://openrouter.ai/api/v1", ""),
        ProviderPreset("Kimi", "Moonshot Kimi", "https://api.moonshot.cn/v1", "kimi-k2-0905-preview"),
        ProviderPreset("智谱", "智谱 GLM", "https://open.bigmodel.cn/api/paas/v4", "glm-4-plus"),
        ProviderPreset("Ollama", "Ollama (本机)", "http://127.0.0.1:11434/v1", "")
    )
}

class SettingsViewModel(private val c: AppContainer) : ViewModel() {

    /** Phase 7：端侧当前生效后端（设置页状态行）。 */
    fun backendLabel(): String = runCatching { c.llama.activeBackend }.getOrDefault("cpu")

    /** 配置文件桥状态行：最近一次应用/拒绝记录（读状态目录 config-bridge.log 尾部）。 */
    fun configFileStatus(): String {
        val f = java.io.File(c.configFile.parentFile, "config-bridge.log")
        if (!f.exists()) return "未配置"
        return runCatching {
            val lines = f.readLines().filter { it.isNotBlank() }
            lines.lastOrNull()?.take(120) ?: "未配置"
        }.getOrDefault("未配置")
    }

    /** 配置文件绝对路径（供 UI 展示；C6 起位于状态目录，agent 经 config 工具读写）。 */
    fun configFilePath(): String = c.configFile.absolutePath

    /** C2：待清理的 REJECTED 修正副本数（拒绝时脱敏存档，供用户检查/清理）。 */
    fun rejectedConfigCopies(): Int =
        c.configFile.parentFile?.listFiles { f -> f.name.contains(".REJECTED.") }?.size ?: 0

    /** C2：一键清理 REJECTED 修正副本。 */
    fun clearRejectedConfigCopies(): Int {
        val files = c.configFile.parentFile?.listFiles { f -> f.name.contains(".REJECTED.") } ?: return 0
        var n = 0
        files.forEach { if (it.delete()) n++ }
        return n
    }

    /** 回滚兜底：最近一次配置快照时间（null=无快照可回退）。 */
    fun latestConfigSnapshot(): String? = c.configBridge.listSnapshots().firstOrNull()

    /** 回滚兜底：回退到最近一次快照（apply 前自动存的 last-known-good）。 */
    fun restoreLatestConfigSnapshot(): Boolean {
        val name = c.configBridge.listSnapshots().firstOrNull() ?: return false
        return c.configBridge.restoreSnapshot(name)
    }

    /** 用量页会话排行显示标题（5.4）；读取失败回退空串。 */
    fun sessionTitleOf(sessionId: String): String =
        runCatching { c.sessionStore.load(sessionId)?.title.orEmpty() }.getOrDefault("")

    val settings: kotlinx.coroutines.flow.StateFlow<AppSettings> =
        c.settingsFlow

    /** Linux 发行版管理器（3.2，供设置页 Linux 环境区块使用）。 */
    val distros get() = c.distros

    var draft by mutableStateOf<ProviderDraft?>(null)
        private set

    var draftError by mutableStateOf<String?>(null)
        private set

    var testing by mutableStateOf(false)
        private set

    var testResult by mutableStateOf<Pair<Boolean, String>?>(null)
        private set

    var fetchingModels by mutableStateOf(false)
        private set

    var modelChoices by mutableStateOf<List<String>?>(null)
        private set

    var detectingCaps by mutableStateOf(false)
        private set

    var detectResult by mutableStateOf<Pair<Boolean, String>?>(null)
        private set

    /** 云端模型能力自动检测（上游 同款目录数据源，含上下文/输出上限/多模态）。 */
    fun detectCapabilities() {
        val d = draft ?: return
        if (d.baseUrl.isBlank() || d.model.isBlank()) {
            draftError = "请先填写 Base URL 和模型 ID 再检测"
            return
        }
        detectingCaps = true
        detectResult = null
        viewModelScope.launch {
            val r = com.haoai.agent.data.ModelCatalog.lookup(c.okHttpClient, d.baseUrl.trim(), d.model.trim())
            detectingCaps = false
            r.fold(
                onSuccess = { caps ->
                    // 点4：目录能力写回当前模型条目（vision/reasoning 显式标记，请求侧据此门控）
                    val entry = com.haoai.agent.data.ModelEntry(
                        id = d.model.trim(),
                        vision = caps.inputModalities.any { it == "image" || it == "video" },
                        reasoning = caps.reasoning,
                        contextLength = if (caps.contextWindow > 0) caps.contextWindow.toInt() else 0,
                        maxTokens = if (caps.maxOutput > 0) caps.maxOutput.toInt().coerceAtMost(1_000_000) else 0
                    )
                    draft = draft?.copy(
                        contextLength = if (caps.contextWindow > 0) caps.contextWindow.toString() else draft?.contextLength ?: "",
                        maxTokens = if (caps.maxOutput > 0) caps.maxOutput.coerceAtMost(1_000_000).toString() else draft?.maxTokens ?: "",
                        models = (draft?.models ?: emptyList()).filterNot { it.id == entry.id } + entry
                    )
                    detectResult = true to "✓ ${caps.describe()}"
                },
                onFailure = { detectResult = false to "✗ ${it.message ?: "检测失败"}（可手动填写）" }
            )
        }
    }

    /** 拉取该供应商的可用模型列表（OpenAI 兼容 GET /models）。 */
    fun fetchModelList() {
        val d = draft ?: return
        if (d.baseUrl.isBlank()) {
            draftError = "请先填写 Base URL 再拉取模型列表"
            return
        }
        val existing = d.id?.let { id -> providers().find { it.id == id } }
        val key = when {
            d.apiKeyPlain.isNotBlank() -> d.apiKeyPlain
            existing != null -> runCatching { c.cipher.decrypt(existing.apiKeyCipher) }.getOrDefault("")
            else -> ""
        }
        if (d.protocol == "anthropic") {
            draftError = "Anthropic 原生协议不支持拉取模型列表，请手动填写模型 ID"
            return
        }
        fetchingModels = true
        viewModelScope.launch {
            val r = c.client.listModels(d.baseUrl.trim(), key)
            fetchingModels = false
            r.fold(
                onSuccess = { list ->
                    modelChoices = if (list.size > 200) list.take(200) else list
                    if (list.isEmpty()) draftError = "供应商返回了空模型列表"
                },
                onFailure = { draftError = "拉取失败：${it.message ?: "未知错误"}" }
            )
        }
    }

    fun pickModel(id: String) {
        val d = draft ?: return
        val newId = id.trim()
        if (newId.isBlank() || newId == d.model.trim()) return
        // 旧模型保留为备选（同供应商多模型），新模型从备选里出列。
        // 此前只在旧模型「已有能力条目」时才保留，首次点选会把上一个模型直接丢掉
        val oldId = d.model.trim()
        val oldEntry = d.models.find { it.id == oldId }
            ?: oldId.takeIf { it.isNotBlank() }?.let { com.haoai.agent.data.ModelEntry(it) }
        val models = d.models.filterNot { it.id == newId } + listOfNotNull(oldEntry)
        draft = d.copy(model = newId, models = models.distinctBy { it.id })
        draftError = null
        // 列表保持展开：可以连着点选好几个模型，选完一个不用重新拉一次
    }

    /** 点1：聊天/设置共用——把某供应商的当前模型切到指定 modelId（同供应商多模型）。 */
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

    /** 点3：余额查询结果缓存（providerId → 展示文本；"…"=进行中）。 */
    var balanceResults by mutableStateOf<Map<String, String>>(emptyMap())
        private set

    /** 点3（借鉴 上游）：GET baseUrl+path，按点分 JSON 路径取余额展示。 */
    fun checkBalance(p: ProviderConfig) {
        balanceResults = balanceResults + (p.id to "查询中…")
        viewModelScope.launch {
            val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    val key = c.resolveApiKey(p)
                    val url = p.baseUrl.trimEnd('/') +
                        (if (p.balanceApiPath.startsWith("/")) p.balanceApiPath else "/" + p.balanceApiPath)
                    val req = okhttp3.Request.Builder().url(url)
                        .apply { if (key.isNotBlank()) header("Authorization", "Bearer $key") }
                        .build()
                    c.okHttpClient.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) error("HTTP ${resp.code}")
                        val body = resp.body?.string() ?: error("空响应")
                        var node: kotlinx.serialization.json.JsonElement =
                            kotlinx.serialization.json.Json.parseToJsonElement(body)
                        for (seg in p.balanceJsonPath.split('.').filter { it.isNotBlank() }) {
                            node = node.jsonObject[seg] ?: error("路径缺字段 $seg")
                        }
                        (node as? kotlinx.serialization.json.JsonPrimitive)?.content
                            ?: node.toString().trim('"')
                    }
                }
            }
            balanceResults = balanceResults + (p.id to (r.getOrNull() ?: "查询失败：${r.exceptionOrNull()?.message?.take(40) ?: "未知"}"))
        }
    }

    fun providers(): List<ProviderConfig> = c.settingsFlow.value.providers
    fun activeProviderId(): String? = c.settingsFlow.value.activeProviderId

    fun startNewDraft() {
        draft = ProviderDraft()
        draftError = null
        testResult = null
        modelChoices = null
        detectResult = null
    }

    fun editProvider(p: ProviderConfig) {
        draft = ProviderDraft(
            id = p.id,
            name = p.name,
            baseUrl = p.baseUrl,
            model = p.model,
            apiKeyPlain = "",
            contextLength = if (p.contextLength > 0) p.contextLength.toString() else "",
            maxTokens = if (p.maxTokens > 0) p.maxTokens.toString() else "",
            protocol = p.protocol,
            models = p.models,
            sendTemperature = p.sendTemperature,
            temperature = p.temperature.toString(),
            sendTopP = p.sendTopP,
            topP = p.topP.toString(),
            sendPresencePenalty = p.sendPresencePenalty,
            presencePenalty = p.presencePenalty.toString(),
            sendFrequencyPenalty = p.sendFrequencyPenalty,
            frequencyPenalty = p.frequencyPenalty.toString(),
            poolCiphers = p.apiKeyPoolCiphers,
            keyRotation = p.keyRotation,
            balanceEnabled = p.balanceEnabled,
            balanceApiPath = p.balanceApiPath,
            balanceJsonPath = p.balanceJsonPath
        )
        draftError = null
        testResult = null
        modelChoices = null
    }

    fun applyPreset(p: ProviderPreset) {
        val d = draft ?: return
        draft = d.copy(
            name = p.name,
            baseUrl = p.baseUrl,
            model = if (p.model.isNotBlank()) p.model else d.model
        )
        draftError = null
        testResult = null
        modelChoices = null
    }

    fun updateDraft(next: ProviderDraft) {
        val prev = draft
        draft = next
        draftError = null
        // 只有会影响拉取结果的字段变了才丢弃模型列表。此前无条件清空，
        // 改个服务名都会把刚拉下来的上百条模型 ID 清掉、得重新拉一次
        if (prev == null || prev.baseUrl != next.baseUrl || prev.protocol != next.protocol) {
            modelChoices = null
        }
    }

    fun cancelDraft() {
        draft = null
        draftError = null
        testing = false
        testResult = null
        modelChoices = null
    }

    fun saveDraft() {
        val d = draft ?: return
        val url = d.baseUrl.trim()
        when {
            !url.startsWith("http://") && !url.startsWith("https://") ->
                draftError = "Base URL 必须以 http(s):// 开头，例如 https://openrouter.ai/api/v1"
            d.model.isBlank() ->
                draftError = "模型 ID 不能为空（如 deepseek-chat、stealth/ox-alpha）"
            d.id == null && d.apiKeyPlain.isBlank() && needsApiKey(url) ->
                draftError = "该服务需要填写 API Key"
            else -> {
                draftError = null
                // 先在 lambda 外完成加密：失败（null）则整体取消，绝不把空串当密文存进去
                val newCipher = if (d.apiKeyPlain.isNotBlank()) {
                    c.cipher.encrypt(d.apiKeyPlain) ?: run {
                        draftError = "密钥加密失败（AndroidKeyStore 不可用），本次未保存"
                        return
                    }
                } else null
                val ctxLen = d.contextLength.trim().toIntOrNull()?.coerceIn(0, 10_000_000) ?: 0
                val maxTok = d.maxTokens.trim().toIntOrNull()?.coerceIn(0, 1_000_000) ?: 0
                // 点5：Key 池新增明文逐条加密；任一失败整体取消（与主 Key 同策略）
                val poolAdd = d.poolAddPlain.map { it.trim() }.filter { it.isNotBlank() }
                val poolAddCiphers = poolAdd.map { k ->
                    c.cipher.encrypt(k) ?: run {
                        draftError = "备用 Key 加密失败（AndroidKeyStore 不可用），本次未保存"
                        return
                    }
                }
                c.updateSettings { s ->
                    val pid = d.id ?: UUID.randomUUID().toString()
                    val existing = s.providers.find { it.id == pid }
                    val keyCipher = newCipher ?: existing?.apiKeyCipher ?: ""
                    val list = s.providers.filterNot { it.id == pid } + ProviderConfig(
                        id = pid,
                        name = d.name.ifBlank { "模型服务" },
                        baseUrl = url,
                        model = d.model.trim(),
                        apiKeyCipher = keyCipher,
                        protocol = d.protocol,
                        contextLength = ctxLen,
                        maxTokens = maxTok,
                        models = d.models,  // 含当前模型条目（能力/窗口覆盖挂在其上）
                        sendTemperature = d.sendTemperature,
                        temperature = d.temperature.toFloatOrNull()?.coerceIn(0f, 2f) ?: 1f,
                        sendTopP = d.sendTopP,
                        topP = d.topP.toFloatOrNull()?.coerceIn(0f, 1f) ?: 1f,
                        sendPresencePenalty = d.sendPresencePenalty,
                        presencePenalty = d.presencePenalty.toFloatOrNull()?.coerceIn(-2f, 2f) ?: 0f,
                        sendFrequencyPenalty = d.sendFrequencyPenalty,
                        frequencyPenalty = d.frequencyPenalty.toFloatOrNull()?.coerceIn(-2f, 2f) ?: 0f,
                        apiKeyPoolCiphers = d.poolCiphers + poolAddCiphers,
                        keyRotation = if (d.keyRotation == "RANDOM") "RANDOM" else "ROUND_ROBIN",
                        balanceEnabled = d.balanceEnabled,
                        balanceApiPath = d.balanceApiPath.ifBlank { "/credits" },
                        balanceJsonPath = d.balanceJsonPath.ifBlank { "data.total_usage" }
                    )
                    s.copy(
                        providers = list,
                        // 新建供应商：保存后自动选中；编辑：保持原选中（被删才回退）
                        activeProviderId = if (d.id == null) pid
                        else s.activeProviderId?.takeIf { aid -> list.any { it.id == aid } } ?: pid
                    )
                }
                draft = null
                testResult = null
            }
        }
    }

    private fun needsApiKey(url: String): Boolean =
        !(url.contains("127.0.0.1") || url.contains("localhost"))

    /** 用草稿里的配置做一次真实连通性测试。 */
    fun testDraftConnection() {
        val d = draft ?: return
        if (d.baseUrl.isBlank() || d.model.isBlank()) {
            draftError = "请先填写 Base URL 和模型 ID 再测试"
            return
        }
        val existing = d.id?.let { id -> providers().find { it.id == id } }
        val key = when {
            d.apiKeyPlain.isNotBlank() -> d.apiKeyPlain
            existing != null -> runCatching { c.cipher.decrypt(existing.apiKeyCipher) }.getOrDefault("")
            else -> ""
        }
        val probe = ProviderConfig("probe", d.name.ifBlank { "测试" }, d.baseUrl.trim(), d.model.trim(), protocol = d.protocol)
        testing = true
        testResult = null
        viewModelScope.launch {
            val r = c.clientFor(probe).testConnection(probe, key)
            testing = false
            testResult = r.fold(
                onSuccess = { true to "✓ 连接成功 · $it" },
                onFailure = { false to "✗ ${it.message ?: "连接失败"}" }
            )
        }
    }

    fun deleteProvider(id: String) {
        c.updateSettings { s ->
            val list = s.providers.filterNot { it.id == id }
            // 回退优先选云端服务：端侧模型可能还没下载，直接落到 local 会报「未找到模型文件」
            val fallback = list.firstOrNull {
                it.id != com.haoai.agent.platform.llama.LlamaServerController.LOCAL_PROVIDER_ID
            }?.id ?: list.firstOrNull()?.id
            s.copy(
                providers = list,
                activeProviderId = s.activeProviderId?.takeIf { aid -> list.any { it.id == aid } }
                    ?: fallback
            )
        }
    }

    fun setActiveProvider(id: String) {
        c.updateSettings { it.copy(activeProviderId = id) }
    }

    fun setPermissionMode(mode: PermissionMode) {
        c.updateSettings { it.copy(permissionMode = mode) }
    }

    fun setKeepAlive(enabled: Boolean) {
        c.updateSettings { it.copy(keepAlive = enabled) }
    }

    fun setMemoryEnabled(enabled: Boolean) {
        c.updateSettings { it.copy(memoryEnabled = enabled) }
    }

    fun setVscreenEnabled(enabled: Boolean) {
        c.updateSettings { it.copy(vscreenEnabled = enabled) }
    }

    /** 4.3 虚拟屏画面码率档位（kbps：1500/3000/5000/10000/20000）。 */
    fun setVscreenBitrate(kbps: Int) {
        c.updateSettings { it.copy(vscreenBitrateKbps = kbps.coerceIn(1500, 20000)) }
    }

    /** 4.3 运行时任务视图隐藏开关。 */
    fun setVscreenHideTask(enabled: Boolean) {
        c.updateSettings { it.copy(vscreenHideTask = enabled) }
    }

    /** 4.3 无障碍适配模式开关（TalkBack 等环境下节点优先、禁手势、朗读步骤）。 */
    fun setA11yAdaptiveMode(enabled: Boolean) {
        c.updateSettings { it.copy(a11yAdaptiveMode = enabled) }
    }

    fun setDeepDream(enabled: Boolean) {
        c.updateSettings { it.copy(deepDream = enabled) }
    }

    /** 5.1 每日 token 预算（千 token 单位）；0=不限。 */
    fun setDailyTokenBudgetK(k: Int) {
        c.updateSettings { it.copy(dailyTokenBudgetK = k.coerceIn(0, 10_000)) }
    }

    /** 5.3 内部任务模型路由设置（purpose: memory/title/summarize；""=主模型，"local"=端侧）。 */
    fun setPurposeModel(purpose: String, id: String) {
        c.updateSettings { st ->
            when (purpose) {
                "memory" -> st.copy(memoryExtractProviderId = id)
                "title" -> st.copy(titleProviderId = id)
                else -> st.copy(summarizeProviderId = id)
            }
        }
    }

    /** 点6（借鉴 上游 模型组）：用途模型备用链——主目标失败按序降级。 */
    fun setPurposeFallback(purpose: String, ids: List<String>) {
        c.updateSettings { st ->
            when (purpose) {
                "memory" -> st.copy(memoryExtractFallbackIds = ids)
                "title" -> st.copy(titleFallbackIds = ids)
                else -> st.copy(summarizeFallbackIds = ids)
            }
        }
    }

    /** 5.2 降级链：勾选/取消备用 Provider（链顺序 = provider 列表顺序）。 */
    fun toggleFallback(id: String) {
        c.updateSettings { st ->
            val next = if (id in st.fallbackChain) st.fallbackChain - id else st.fallbackChain + id
            st.copy(fallbackChain = next)
        }
    }

    fun setAutoLearn(enabled: Boolean) {
        c.updateSettings { it.copy(autoLearn = enabled) }
    }

    fun setDreamProvider(id: String) {
        c.updateSettings { it.copy(dreamProviderId = id) }
    }

    /** 记忆整理专用端侧模型；null = 跟随端侧聊天模型。 */
    fun setDreamLocalModelFile(path: String?) {
        c.updateSettings { it.copy(dreamLocalModelFile = path?.takeIf { p -> p.isNotBlank() }) }
    }

    /** 应用目录内可用的端侧模型（绝对路径）。 */
    fun llamaModelPaths(): List<String> = c.llama.listModels().map { it.absolutePath }

    fun setDreamIdleMinutes(minutes: Int) {
        c.updateSettings { it.copy(dreamIdleMinutes = minutes.coerceIn(1, 240)) }
    }

    fun setThemeMode(mode: String) {
        c.updateSettings { it.copy(themeMode = mode) }
    }

    fun setDynamicColor(enabled: Boolean) {
        c.updateSettings { it.copy(dynamicColor = enabled) }
    }

    fun setThemeSeed(index: Int) {
        c.updateSettings { it.copy(themeSeed = index.coerceIn(0, 100)) }
    }

    fun setAmoledMode(enabled: Boolean) {
        c.updateSettings { it.copy(amoledMode = enabled) }
    }

    fun setBubbleOpacity(value: Float) {
        c.updateSettings { it.copy(bubbleOpacity = value.coerceIn(0.3f, 1f)) }
    }

    fun setWallpaperGlobal(enabled: Boolean) {
        c.updateSettings { it.copy(wallpaperGlobal = enabled) }
    }

    fun setReasoningEffort(level: String) {
        c.updateSettings { it.copy(reasoningEffort = level) }
    }

    fun memoryCount(): Int = c.memoryBank.count()
    fun journalCount(): Int = c.journal.count()

    fun skillCount(): Int = SkillStore.list().size

    /** 主页三项计数（长期记忆/今日日志/技能数）：IO 收敛到后台，组合期只读内存。 */
    var homeCounts by mutableStateOf(Triple(0, 0, 0))
        private set

    fun refreshHomeCounts() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                homeCounts = Triple(c.memoryBank.count(), c.journal.count(), SkillStore.list().size)
            }
        }
    }

    // ---- 端侧模型文件：加载（直读原路径，不复制）----

    var importingModel by mutableStateOf<String?>(null)
        private set

    /**
     * 加载模型文件：优先把 SAF content:// 解析成真实路径直读（不复制、不占双份空间）；
     * 解析不了（第三方文档提供器）才回退为复制进应用目录。
     */
    fun loadModelFile(context: android.content.Context, uriString: String) {
        val resolved = resolveDocumentPath(context, uriString)
        if (resolved != null && resolved.endsWith(".gguf", ignoreCase = true) && java.io.File(resolved).exists()) {
            useDeviceModel(resolved)
            importingModel = "已加载 ${java.io.File(resolved).name}（原路径直读）"
            viewModelScope.launch { delay(3500); if (importingModel?.startsWith("已加载") == true) importingModel = null }
        } else {
            importModel(context, uriString)
        }
    }

    /** externalstorage 文档 URI → /storage/emulated/0/... 真实路径；其余返回 null。 */
    private fun resolveDocumentPath(context: android.content.Context, uriString: String): String? = runCatching {
        val uri = android.net.Uri.parse(uriString)
        if (uri.authority != "com.android.externalstorage.documents") return@runCatching null
        val seg = uri.path?.split("/document/")?.getOrNull(1) ?: return@runCatching null
        when {
            seg.startsWith("primary:") -> "/storage/emulated/0/" + seg.removePrefix("primary:")
            else -> null
        }
    }.getOrNull()

    /** 兜底：复制进应用模型目录（第三方提供器无法拿到真实路径时）。 */
    fun importModel(context: android.content.Context, uriString: String) {
        if (importingModel != null) return
        viewModelScope.launch(Dispatchers.IO) {
            val finish: (String?) -> Unit = { msg ->
                importingModel = msg
            }
            runCatching {
                val resolver = context.contentResolver
                val uri = android.net.Uri.parse(uriString)
                var name = runCatching {
                    androidx.documentfile.provider.DocumentFile.fromSingleUri(context, uri)?.name
                }.getOrNull()
                if (name.isNullOrBlank()) name = "imported-${System.currentTimeMillis()}.gguf"
                if (!name.endsWith(".gguf")) name += ".gguf"
                withContext(Dispatchers.Main) { finish("导入 $name…") }
                val out = java.io.File(c.llama.modelsDir(), name)
                resolver.openInputStream(uri)?.use { ins ->
                    java.io.FileOutputStream(out).use { fos ->
                        val buf = ByteArray(1 shl 20)
                        var total = 0L
                        var lastMb = -1L
                        while (true) {
                            val n = ins.read(buf)
                            if (n < 0) break
                            fos.write(buf, 0, n)
                            total += n
                            val mb = total / (1024L * 1024L)
                            if (mb != lastMb) {
                                lastMb = mb
                                withContext(Dispatchers.Main) {
                                    finish("导入 $name · ${mb} MB")
                                }
                            }
                        }
                    }
                } ?: throw IllegalStateException("无法读取所选文件")
                if (out.length() < 1_000_000) throw IllegalStateException("文件过小，可能不是有效 GGUF")
                if (c.llama.isMmprojFile(out)) {
                    withContext(Dispatchers.Main) { finish("已导入视觉投影 $name（与模型同名即可自动启用图像识别）") }
                } else {
                    c.updateSettings { it.copy(localModelFile = name) }
                    c.llama.preferredModel = name
                    withContext(Dispatchers.Main) { finish("已导入 $name（${out.length() / (1024 * 1024)} MB）并设为当前端侧模型") }
                }
            }.onFailure {
                withContext(Dispatchers.Main) { finish("导入失败：${it.message ?: it.javaClass.simpleName}") }
            }
            delay(4000)
            withContext(Dispatchers.Main) { if (importingModel?.startsWith("已导入") == true || importingModel?.startsWith("导入失败") == true) finish(null) }
        }
    }

    // ---- 端侧模型：免拷贝直读手机上已有的 GGUF ----

    var scannedDeviceModels by mutableStateOf<List<java.io.File>?>(null)
        private set

    var scanningModels by mutableStateOf(false)
        private set

    fun scanDeviceModels() {
        if (scanningModels) return
        scanningModels = true
        viewModelScope.launch(Dispatchers.IO) {
            val r = runCatching { c.llama.scanDeviceModels() }
            scanningModels = false
            scannedDeviceModels = r.getOrDefault(emptyList())
        }
    }

    fun clearDeviceScan() {
        scannedDeviceModels = null
    }

    /** 直接引用手机上的模型文件（不复制），下次启动端侧服务生效。 */
    fun useDeviceModel(path: String) {
        viewModelScope.launch {
            c.updateSettings { it.copy(localModelFile = path) }
            c.llama.preferredModel = path
            val display = java.io.File(path).name
            c.updateSettings { s ->
                s.copy(providers = s.providers.map {
                    if (it.id == com.haoai.agent.platform.llama.LlamaServerController.LOCAL_PROVIDER_ID)
                        it.copy(model = display) else it
                })
            }
            if (c.llama.state.value is com.haoai.agent.platform.llama.LlamaState.Running) c.llama.stop()
        }
    }

    /** 当前选中的本地模型显示名（兼容应用目录内文件名与绝对路径两种形式）。 */
    fun currentLocalModelLabel(): String? {
        val pref = c.settingsFlow.value.localModelFile ?: return c.llama.findModel()?.name
        return if (pref.startsWith("/")) java.io.File(pref).name + "（原路径直读）" else pref
    }

    // ---- 聊天背景壁纸 ----

    fun wallpaperSet(context: android.content.Context): Boolean =
        com.haoai.agent.platform.WallpaperStore.has(context)

    fun setWallpaper(context: android.content.Context, uriString: String) {
        runCatching {
            context.contentResolver.openInputStream(android.net.Uri.parse(uriString))?.use { ins ->
                com.haoai.agent.platform.WallpaperStore.set(context, ins.readBytes())
            }
        }
    }

    fun clearWallpaper(context: android.content.Context) {
        com.haoai.agent.platform.WallpaperStore.clear(context)
    }

    // ---- 系统权限状态与授权引导 ----

    var permissionStates by mutableStateOf<Map<String, Boolean>>(emptyMap())
        private set

    fun refreshPermissions(context: android.content.Context) {
        permissionStates = com.haoai.agent.platform.PermissionCenter.ALL.associate {
            it.key to com.haoai.agent.platform.PermissionCenter.granted(context, it)
        }
    }

    /** 授权：运行时权限弹系统对话框；特殊权限跳系统设置并等待返回。 */
    fun requestPermission(spec: com.haoai.agent.platform.PermSpec, context: android.content.Context) {
        viewModelScope.launch {
            com.haoai.agent.platform.PermissionCenter.ensure(context, spec, 150_000)
            refreshPermissions(context)
        }
    }

    val llamaState = c.llama.state
    val llamaDownload = c.llama.downloadProgress

    fun llamaModelFile(): String? = c.llama.findModel()?.name
    fun llamaModelSizeMb(): Long =
        c.llama.findModel()?.let { it.length() / (1024 * 1024) } ?: 0

    fun startLlama(onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            // 期望聊天模型：若服务正被记忆专用模型占用则切回
            onResult(c.llama.ensureStarted(c.llama.findModel()?.absolutePath))
        }
    }

    fun stopLlama() = c.llama.stop()

    fun downloadLlamaModel(url: String, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            val r = c.llama.downloadModel(url)
            onDone(r.isSuccess)
        }
    }

    fun useLocalModel() {
        c.updateSettings { s ->
            val has = s.providers.any { it.id == com.haoai.agent.platform.llama.LlamaServerController.LOCAL_PROVIDER_ID }
            val list = if (has) s.providers else s.providers + com.haoai.agent.data.ProviderConfig(
                id = com.haoai.agent.platform.llama.LlamaServerController.LOCAL_PROVIDER_ID,
                name = "端侧模型 (llama.cpp)",
                baseUrl = com.haoai.agent.platform.llama.LlamaServerController.LOCAL_PROVIDER_BASE,
                model = c.llama.findModel()?.name ?: "未下载模型",
                apiKeyCipher = ""
            )
            s.copy(providers = list, activeProviderId = com.haoai.agent.platform.llama.LlamaServerController.LOCAL_PROVIDER_ID)
        }
    }

    fun isLocalActive(): Boolean =
        c.settingsFlow.value.activeProviderId == com.haoai.agent.platform.llama.LlamaServerController.LOCAL_PROVIDER_ID

    fun localModels(): List<String> =
        c.llama.listModels().map { it.name }

    fun selectedLocalModel(): String? {
        val pref = c.settingsFlow.value.localModelFile
        val names = localModels()
        return (pref?.takeIf { it in names } ?: names.firstOrNull())
    }

    /** 手动切换端侧模型：持久化选择并停掉在跑的 server，下次启动生效。 */
    fun selectLocalModel(name: String) {
        c.updateSettings { it.copy(localModelFile = name) }
        c.llama.preferredModel = name
        c.updateSettings { s ->
            s.copy(providers = s.providers.map {
                if (it.id == com.haoai.agent.platform.llama.LlamaServerController.LOCAL_PROVIDER_ID)
                    it.copy(model = name) else it
            })
        }
        if (c.llama.state.value is com.haoai.agent.platform.llama.LlamaState.Running) c.llama.stop()
    }

    /** 端侧上下文窗口：过大在小内存设备会 OOM/预填充极慢，切换后下次启动生效。 */
    fun setLocalContextLength(size: Int) {
        val v = size.coerceIn(2048, 262_144)
        c.updateSettings { it.copy(localContextLength = v) }
        c.llama.contextSize = v
    }

    fun localContextLength(): Int = c.settingsFlow.value.localContextLength

    fun setCustomPrompt(text: String) {
        c.updateSettings { it.copy(customPrompt = text) }
    }

    fun useDefaultWorkspace() {
        c.workspace.useDefault()
        c.onWorkspaceSwitched()
    }

    fun useSafWorkspace(uriString: String): Boolean {
        val ok = c.workspace.useSaf(uriString)
        if (ok) c.onWorkspaceSwitched()
        return ok
    }

    fun workspaceName(): String = c.workspace.current?.displayName ?: "未绑定"
}
