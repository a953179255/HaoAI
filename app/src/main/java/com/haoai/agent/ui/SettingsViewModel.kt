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
import com.haoai.agent.data.CapabilityResolver
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
    /** 编辑已有服务时该服务是否已保存过 Key（决定 API Key 占位符提示文案） */
    val hasSavedKey: Boolean = false,
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
    val model: String,
    /** 卡片副注：写「能拿到什么模型」而非「已预填」 */
    val sub: String = ""
)

object ProviderPresets {
    val all = listOf(
        ProviderPreset("DeepSeek", "DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat", "deepseek 官方模型"),
        ProviderPreset("Kimi", "Moonshot Kimi", "https://api.moonshot.cn/v1", "kimi-k2-0905-preview", "kimi 系列模型"),
        ProviderPreset("智谱", "智谱 GLM", "https://open.bigmodel.cn/api/paas/v4", "glm-4-plus", "GLM 系列模型"),
        ProviderPreset("OpenRouter", "OpenRouter", "https://openrouter.ai/api/v1", "", "聚合 400+ 模型"),
        ProviderPreset("Ollama", "Ollama (本机)", "http://127.0.0.1:11434/v1", "", "本机模型服务"),
        ProviderPreset("自定义", "", "", "", "任意兼容端点")
    )
}

/** 预设对应的 API 协议：Anthropic 官方端点用原生 Messages，其余走 OpenAI 兼容。 */
val ProviderPreset.protocol: String
    get() = if (baseUrl.startsWith("https://api.anthropic.com")) "anthropic" else "openai_compat"

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

    // ── 数据与备份（导出侧）───────────────────────────────────────────

    /** 勾选状态。设置里没存过（空列表）按"全五个域"处理，所以首次改动要把默认展开成实列表。 */
    fun backupSelected(scope: com.haoai.agent.platform.BackupScope): Boolean {
        val sel = settings.value.backupScopes
        return sel.isEmpty() || scope.name in sel
    }

    fun toggleBackupScope(scope: com.haoai.agent.platform.BackupScope) {
        val cur = com.haoai.agent.platform.BackupScope.entries.filter { backupSelected(it) }.toMutableList()
        if (!cur.remove(scope)) cur += scope
        // 一项都不勾等于导出一个只有 manifest 的空包，没有意义：拒绝并说明
        if (cur.isEmpty()) return
        c.updateSettings { it.copy(backupScopes = cur.map { s -> s.name }) }
    }

    fun backupIncludeKeys(): Boolean = settings.value.backupIncludeKeys
    fun setBackupIncludeKeys(v: Boolean) = c.updateSettings { it.copy(backupIncludeKeys = v) }
    fun backupSnapshots(): Boolean = settings.value.backupSnapshots
    fun setBackupSnapshots(v: Boolean) = c.updateSettings { it.copy(backupSnapshots = v) }

    /** 单个域的体积（字节）。目录遍历是磁盘活，UI 只在进页面/改动后取一次。 */
    fun scopeBytes(scope: com.haoai.agent.platform.BackupScope): Long =
        com.haoai.agent.platform.DataBackupManager.scopeSize(c, scope)

    fun selectedBytes(): Long = com.haoai.agent.platform.BackupScope.entries
        .filter { backupSelected(it) }.sumOf { scopeBytes(it) }

    /** 备份体积标签。阈值刻意错开一档：用 MB 表达 300 KB 只会显示成"0.0 MB"。 */
    // ---- 「关于」页用的只读快照 ----

    /** 本机会话数（会话列表长度）。 */
    fun sessionCount(): Int = runCatching { c.sessionStore.list().size }.getOrDefault(0)

    /** 应用数据目录绝对路径（关于页可点复制）。 */
    fun dataDirPath(): String = runCatching { c.appFilesDir.absolutePath }.getOrDefault("")

    fun sizeLabel(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes / 1024.0 / 1024 / 1024)
        bytes >= 10L * 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024)
        bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }

    /** "上次导出"状态行：0 = 从未导出过（换机/重装会全部丢失，这行就是提醒这件事的）。 */
    fun lastExportLabel(): String {
        val at = settings.value.lastDataExportAt
        if (at == 0L) return "从未导出到设备外 —— 卸载重装、换机、清除数据都会全部丢失"
        val d = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA).format(java.util.Date(at))
        val days = (System.currentTimeMillis() - at) / 86_400_000L
        return if (days > 30) "$d 导出 · 已经 ${days} 天没有更新" else "$d 导出"
    }

    fun hasExported(): Boolean = settings.value.lastDataExportAt > 0L

    /** 本机快照摘要（破坏性操作前自动留的那批）。 */
    fun snapshotLabel(): String {
        val list = com.haoai.agent.platform.DataBackupManager.snapshots(c)
        if (list.isEmpty()) return "暂无 —— 压缩或彻底删除会话前会自动留一份"
        val latest = list.first()
        val reason = if (latest.reason == "purge") "删除" else "压缩"
        val t = latest.at
        val when0 = "${t.substring(4, 6)}-${t.substring(6, 8)} ${t.substring(9, 11)}:${t.substring(11, 13)}"
        return "${list.size} 份 · 最近一次（${reason}前）$when0"
    }

    var backupBusy by mutableStateOf(false)
        private set

    /** 导出到 SAF 选好的位置。IO 线程跑（打包要读全部会话文件），结果字符串给 UI 弹 Toast。 */
    fun exportData(uri: android.net.Uri, onResult: (Result<String>) -> Unit) {
        if (backupBusy) return
        backupBusy = true
        viewModelScope.launch(Dispatchers.IO) {
            val scopes = com.haoai.agent.platform.BackupScope.entries.filter { backupSelected(it) }.toSet()
            val r = com.haoai.agent.platform.DataBackupManager
                .exportZip(c, uri, scopes, backupIncludeKeys())
            withContext(Dispatchers.Main) {
                backupBusy = false
                onResult(
                    r.map { s ->
                        "已备份 ${s.entries} 项 · ${sizeLabel(s.bytes)}" +
                            if (s.keysIncluded) "（含明文 Key）" else "（不含 Key）"
                    }
                )
            }
        }
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

    /** 云端模型能力自动检测（models.dev 目录：上下文/输出上限/输入输出模态/工具/思考等级）。 */
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
                    // 目录能力写回当前模型条目（模态/工具/推理显式标记，请求侧据此门控）。
                    // 已是手动覆盖（manual）的条目不被自动检测覆盖——恢复自动检测后才可刷新。
                    val curId = d.model.trim()
                    val old = d.models.find { it.id == curId }
                    if (old?.capsSource == "manual") {
                        detectResult = true to "✓ ${caps.describe()}\n（当前模型为手动设置，未覆盖；在能力编辑页「恢复自动检测」后生效）"
                    } else {
                        val entry = (old ?: com.haoai.agent.data.ModelEntry(curId)).copy(
                            inputModalities = caps.inputModalities,
                            outputModalities = caps.outputModalities,
                            reasoning = caps.reasoning,
                            tools = caps.toolCall ?: old?.tools,
                            effortValues = caps.effortValues,
                            capsSource = "models.dev",
                            contextLength = if (caps.contextWindow > 0) caps.contextWindow.toInt() else (old?.contextLength ?: 0),
                            maxTokens = if (caps.maxOutput > 0) caps.maxOutput.toInt().coerceAtMost(1_000_000) else (old?.maxTokens ?: 0)
                        )
                        draft = draft?.copy(
                            contextLength = if (caps.contextWindow > 0) caps.contextWindow.toString() else draft?.contextLength ?: "",
                            maxTokens = if (caps.maxOutput > 0) caps.maxOutput.coerceAtMost(1_000_000).toString() else draft?.maxTokens ?: "",
                            models = draft?.models?.filterNot { it.id == entry.id }?.plus(entry) ?: listOf(entry)
                        )
                        detectResult = true to "✓ ${caps.describe()}"
                    }
                },
                onFailure = { detectResult = false to "✗ ${it.message ?: "检测失败"}（可手动勾选模态）" }
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
        // 保序切换（用户反馈：切默认后列表顺序乱跳）：只改 model 指向；models 列表
        // 不搬动既有条目，仅为缺失条目补位（新模型、旧默认——补在尾部，原本也不在展示列表里）
        var models = d.models
        if (models.none { it.id == newId }) models = models + com.haoai.agent.data.ModelEntry(newId)
        val oldId = d.model.trim()
        if (oldId.isNotBlank() && models.none { it.id == oldId }) {
            models = models + com.haoai.agent.data.ModelEntry(oldId)
        }
        draft = d.copy(model = newId, models = models)
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

    /** 点3：GET baseUrl+path，按点分 JSON 路径取余额展示。 */
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
            hasSavedKey = p.apiKeyCipher.isNotBlank(),
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
            // 预设连带协议：Anthropic 官方端点切原生 Messages，
            // 其余切回 OpenAI 兼容——原先只填 URL 不动协议，
            // 选了 Anthropic 预设还得手动去找协议切换行
            protocol = p.protocol,
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
                        name = d.name.ifBlank { "模型供应商" },
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

    /**
     * 设置供应商的默认模型（同供应商多模型切换入口）。
     * 保序（用户反馈）：只改 model 指向，models 列表原样保留——展开区渲染时
     * 会排除当前默认行，条目无需搬动，切换后顺序不再变化。
     */
    fun setProviderDefaultModel(providerId: String, modelId: String) {
        c.updateSettings { s ->
            val p = s.providers.find { it.id == providerId } ?: return@updateSettings s
            if (modelId == p.model || p.models.none { it.id == modelId }) return@updateSettings s
            // 旧默认若无条目（老数据）补插到尾部保能力数据；已有则列表原样不动（保序）
            val models = if (p.models.any { it.id == p.model }) p.models
            else p.models + com.haoai.agent.data.ModelEntry(p.model)
            val updated = p.copy(model = modelId, models = models)
            s.copy(providers = s.providers.map { if (it.id == providerId) updated else it })
        }
    }

    /** 删除供应商的某个备选模型（不影响当前默认模型）。 */
    fun removeProviderModel(providerId: String, modelId: String) {
        c.updateSettings { s ->
            val p = s.providers.find { it.id == providerId } ?: return@updateSettings s
            if (modelId == p.model) return@updateSettings s
            val updated = p.copy(models = p.models.filterNot { it.id == modelId })
            s.copy(providers = s.providers.map { if (it.id == providerId) updated else it })
        }
    }

    // ── 模型能力编辑页：手动覆盖模态 / 恢复自动检测（直接写回 settings，非草稿） ──

    /** 更新某供应商下某模型的能力条目（原位替换保序；不存在则追加尾部）。 */
    fun updateModelEntry(providerId: String, entry: com.haoai.agent.data.ModelEntry) {
        c.updateSettings { s ->
            val p = s.providers.find { it.id == providerId } ?: return@updateSettings s
            val idx = p.models.indexOfFirst { it.id == entry.id }
            val models = if (idx >= 0) p.models.toMutableList().also { l -> l[idx] = entry }
            else p.models + entry
            val updated = p.copy(models = models)
            s.copy(providers = s.providers.map { if (it.id == providerId) updated else it })
        }
    }

    /** 切换单个输入模态（image/audio/video/pdf）：写回即标记 manual，脱离自动检测。 */
    fun toggleInputModality(providerId: String, modelId: String, mod: String) {
        val p = providers().find { it.id == providerId } ?: return
        val entry = p.models.find { it.id == modelId }
            ?: com.haoai.agent.data.ModelEntry(modelId)
        val cur = CapabilityResolver.resolve(entry, modelId)
        val next = if (mod in cur.inputs) cur.inputs - mod else cur.inputs + mod
        updateModelEntry(
            providerId,
            entry.copy(
                inputModalities = next.distinct(),
                outputModalities = cur.outputs,
                capsSource = "manual"
            )
        )
    }

    /** 切换单个输出模态（image/audio）。 */
    fun toggleOutputModality(providerId: String, modelId: String, mod: String) {
        val p = providers().find { it.id == providerId } ?: return
        val entry = p.models.find { it.id == modelId }
            ?: com.haoai.agent.data.ModelEntry(modelId)
        val cur = CapabilityResolver.resolve(entry, modelId)
        val next = if (mod in cur.outputs) cur.outputs - mod else cur.outputs + mod
        updateModelEntry(
            providerId,
            entry.copy(
                inputModalities = cur.inputs,
                outputModalities = next.distinct(),
                capsSource = "manual"
            )
        )
    }

    /** 恢复自动检测：清掉手动标记与模态覆盖，下次「检测能力」重新填充。 */
    fun resetModelCaps(providerId: String, modelId: String) {
        val p = providers().find { it.id == providerId } ?: return
        val entry = p.models.find { it.id == modelId } ?: return
        updateModelEntry(
            providerId,
            entry.copy(inputModalities = null, outputModalities = null, capsSource = null, effortValues = null)
        )
    }

    /**
     * 按模型思考等级覆盖：""=跟随全局，
     * "off"=显式关闭，low/medium/high=覆盖全局。目录 effortValues 是可选项参考。
     */
    fun setModelEffort(providerId: String, modelId: String, effort: String) {
        val p = providers().find { it.id == providerId } ?: return
        val entry = p.models.find { it.id == modelId }
            ?: com.haoai.agent.data.ModelEntry(modelId)
        updateModelEntry(providerId, entry.copy(reasoningEffortOverride = effort))
    }

    /** 批量检测该供应商全部模型的能力（详情页「检测全部能力」）。 */
    fun detectAllCapabilities(providerId: String) {
        val p = providers().find { it.id == providerId } ?: return
        if (p.baseUrl.isBlank()) return
        detectingCaps = true
        detectResult = null
        viewModelScope.launch {
            val ids = p.modelIds()
            var ok = 0
            val failed = mutableListOf<String>()
            for (id in ids) {
                val old = p.models.find { it.id == id }
                if (old?.capsSource == "manual") { ok++; continue } // 手动的不覆盖，计入成功
                val r = com.haoai.agent.data.ModelCatalog.lookup(c.okHttpClient, p.baseUrl, id)
                r.fold(
                    onSuccess = { caps ->
                        val entry = (old ?: com.haoai.agent.data.ModelEntry(id)).copy(
                            inputModalities = caps.inputModalities,
                            outputModalities = caps.outputModalities,
                            reasoning = caps.reasoning,
                            tools = caps.toolCall ?: old?.tools,
                            effortValues = caps.effortValues,
                            capsSource = "models.dev",
                            contextLength = if (caps.contextWindow > 0) caps.contextWindow.toInt() else (old?.contextLength ?: 0),
                            maxTokens = if (caps.maxOutput > 0) caps.maxOutput.toInt().coerceAtMost(1_000_000) else (old?.maxTokens ?: 0)
                        )
                        c.updateSettings { s ->
                            val pp = s.providers.find { it.id == providerId } ?: return@updateSettings s
                            pp.let {
                                // 保序写入：原位替换该模型条目（2026-09-11 用户反馈：
                                // 旧版 filterNot + 尾部追加，检测能力一次全列表重排）
                                val idx = it.models.indexOfFirst { m -> m.id == entry.id }
                                val models = if (idx >= 0)
                                    it.models.toMutableList().also { l -> l[idx] = entry }
                                else it.models + entry
                                val updated = it.copy(models = models)
                                s.copy(providers = s.providers.map { x -> if (x.id == providerId) updated else x })
                            }
                        }
                        ok++
                    },
                    onFailure = { failed += id }
                )
            }
            detectingCaps = false
            detectResult = if (failed.isEmpty()) true to "✓ 已检测 $ok 个模型"
            else false to "✓ $ok 个 · 未收录：${failed.joinToString("、").take(60)}（可手动勾选模态）"
        }
    }

    /** 当前供应商某模型的能力解析视图（供能力编辑页渲染）。 */
    fun capsOf(providerId: String, modelId: String): CapabilityResolver.Caps =
        CapabilityResolver.resolve(
            providers().find { it.id == providerId }?.models?.find { it.id == modelId }, modelId
        )

    /** 单模型能力检测（能力编辑页「自动检测」按钮；不依赖草稿通道）。 */
    fun detectSingleCaps(providerId: String, modelId: String) {
        val p = providers().find { it.id == providerId } ?: return
        if (p.baseUrl.isBlank()) {
            detectResult = false to "供应商缺少 Base URL，无法检测"
            return
        }
        detectingCaps = true
        detectResult = null
        viewModelScope.launch {
            val old = p.models.find { it.id == modelId }
            val r = com.haoai.agent.data.ModelCatalog.lookup(c.okHttpClient, p.baseUrl, modelId)
            detectingCaps = false
            r.fold(
                onSuccess = { caps ->
                    val entry = (old ?: com.haoai.agent.data.ModelEntry(modelId)).copy(
                        inputModalities = caps.inputModalities,
                        outputModalities = caps.outputModalities,
                        reasoning = caps.reasoning,
                        tools = caps.toolCall ?: old?.tools,
                        effortValues = caps.effortValues,
                        capsSource = "models.dev",
                        contextLength = if (caps.contextWindow > 0) caps.contextWindow.toInt() else (old?.contextLength ?: 0),
                        maxTokens = if (caps.maxOutput > 0) caps.maxOutput.toInt().coerceAtMost(1_000_000) else (old?.maxTokens ?: 0)
                    )
                    updateModelEntry(providerId, entry)
                    detectResult = true to "✓ ${caps.describe()}"
                },
                onFailure = { detectResult = false to "✗ ${it.message ?: "检测失败"}（可手动勾选模态）" }
            )
        }
    }

    fun setPermissionMode(mode: PermissionMode) {
        c.updateSettings { it.copy(permissionMode = mode) }
    }

    fun setKeepAlive(enabled: Boolean) {
        c.updateSettings { it.copy(keepAlive = enabled) }
    }

    /** 任务悬浮窗开关。 */
    fun setRunOverlay(enabled: Boolean) {
        c.updateSettings { it.copy(runOverlay = enabled) }
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

    /** E5b 成本熔断总开关：关闭后交互聊天与无人值守任务均不再自动收尾。 */
    fun setCostBreakerEnabled(on: Boolean) {
        c.updateSettings { it.copy(costBreakerEnabled = on) }
    }

    /** E5b 单轮 token 熔断上限（交互聊天）；0=不限。无人值守固定 15 万硬限不受此影响。 */
    fun setTurnTokenCap(v: Int) {
        c.updateSettings { it.copy(turnTokenCap = v.coerceIn(0, 10_000_000)) }
    }

    /** E5b 圈数熔断上限（单轮工具调用次数）；0=不限。 */
    fun setToolCallCap(v: Int) {
        c.updateSettings { it.copy(toolCallCap = v.coerceIn(0, 100_000)) }
    }

    /** E5b 软提醒开关（70% 预警）。 */
    fun setSoftBudgetWarn(on: Boolean) {
        c.updateSettings { it.copy(softBudgetWarn = on) }
    }

    /** 5.3 内部任务模型路由设置（purpose: memory/title/summarize/chat；""=主模型，"local"=端侧，"pid|model"=同供应商指定模型）。 */
    fun setPurposeModel(purpose: String, id: String) {
        c.updateSettings { st ->
            when (purpose) {
                "memory" -> st.copy(memoryExtractProviderId = id)
                "title" -> st.copy(titleProviderId = id)
                "chat" -> st.copy(chatPurposeId = id)
                // P2 能力委派：主模型缺模态时的代看/代听模型
                "vision" -> st.copy(visionProviderId = id)
                "asr" -> st.copy(asrProviderId = id)
                else -> st.copy(summarizeProviderId = id)
            }
        }
    }

    /** 点6：用途模型备用链——主目标失败按序降级。 */
    fun setPurposeFallback(purpose: String, ids: List<String>) {
        c.updateSettings { st ->
            when (purpose) {
                "memory" -> st.copy(memoryExtractFallbackIds = ids)
                "title" -> st.copy(titleFallbackIds = ids)
                "chat" -> st.copy(chatFallbackIds = ids)
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
        // 选预设色即退出自定义色模式（两者互斥：customSeedActive 空=预设生效）
        c.updateSettings { it.copy(themeSeed = index.coerceIn(0, 100), customSeedActive = "") }
    }

    /** 应用一个自定义主题色（HEX，须已存在于槽位或正准备保存）。 */
    fun applyCustomSeed(hex: String) {
        c.updateSettings { it.copy(customSeedActive = hex) }
    }

    /** 保存自定义色到槽位（0-2）并立即应用；已占用槽位覆盖。 */
    fun saveCustomSeed(slot: Int, hex: String) {
        if (slot !in 0..2) return
        c.updateSettings { s ->
            val list = s.customSeedColors.toMutableList()
            while (list.size < 3) list.add("")
            list[slot] = hex
            s.copy(customSeedColors = list, customSeedActive = hex)
        }
    }

    /** 清除槽位颜色；若该色正在生效则回退到预设种子。 */
    fun clearCustomSeed(slot: Int) {
        if (slot !in 0..2) return
        c.updateSettings { s ->
            val list = s.customSeedColors.toMutableList()
            while (list.size < 3) list.add("")
            val removed = list[slot]
            list[slot] = ""
            val stillActive = if (s.customSeedActive == removed) "" else s.customSeedActive
            s.copy(customSeedColors = list, customSeedActive = stillActive)
        }
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
     * 加载模型文件：仅直读 SAF 解析出的本机真实路径（不复制、不占双份空间）。
     * 解析不到真实路径（第三方/云文档提供器）时不再复制兜底，直接提示改用「扫描」或把模型放到本机存储。
     */
    fun loadModelFile(context: android.content.Context, uriString: String) {
        val resolved = resolveDocumentPath(context, uriString)
        val file = resolved?.let { java.io.File(it) }
        val msg = when {
            resolved == null || !resolved.endsWith(".gguf", ignoreCase = true) || file?.exists() != true ->
                "无法直读该位置（非本机存储路径）。请把模型放到手机存储（如 Download），再用「扫描手机已有模型」选择"
            !file.canRead() ->
                "无读取权限：请在设置里给 HaoAI 开启「所有文件访问」，或把模型移到 HaoAI 的 models 目录"
            else -> {
                useDeviceModel(resolved)
                "已加载 ${file.name}（原路径直读）"
            }
        }
        importingModel = msg
        if (msg.startsWith("已加载")) {
            viewModelScope.launch { delay(4000); if (importingModel == msg) importingModel = null }
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
