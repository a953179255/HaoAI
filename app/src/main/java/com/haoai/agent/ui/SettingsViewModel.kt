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
import java.util.UUID

data class ProviderDraft(
    val id: String? = null,
    val name: String = "",
    val baseUrl: String = "",
    val model: String = "",
    val apiKeyPlain: String = ""
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

    val settings: kotlinx.coroutines.flow.StateFlow<AppSettings> =
        c.settingsFlow

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
        draft = d.copy(model = id)
        modelChoices = null
    }

    fun providers(): List<ProviderConfig> = c.settingsFlow.value.providers
    fun activeProviderId(): String? = c.settingsFlow.value.activeProviderId

    fun startNewDraft() {
        draft = ProviderDraft()
        draftError = null
        testResult = null
        modelChoices = null
    }

    fun editProvider(p: ProviderConfig) {
        draft = ProviderDraft(
            id = p.id,
            name = p.name,
            baseUrl = p.baseUrl,
            model = p.model,
            apiKeyPlain = ""
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
        draft = next
        draftError = null
        modelChoices = null
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
                c.updateSettings { s ->
                    val pid = d.id ?: UUID.randomUUID().toString()
                    val existing = s.providers.find { it.id == pid }
                    val keyCipher = newCipher ?: existing?.apiKeyCipher ?: ""
                    val list = s.providers.filterNot { it.id == pid } +
                        ProviderConfig(pid, d.name.ifBlank { "模型服务" }, url, d.model.trim(), keyCipher)
                    s.copy(
                        providers = list,
                        activeProviderId = s.activeProviderId?.takeIf { aid -> list.any { it.id == aid } } ?: pid
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
        val probe = ProviderConfig("probe", d.name.ifBlank { "测试" }, d.baseUrl.trim(), d.model.trim())
        testing = true
        testResult = null
        viewModelScope.launch {
            val r = c.client.testConnection(probe, key)
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
            s.copy(
                providers = list,
                activeProviderId = s.activeProviderId?.takeIf { aid -> list.any { it.id == aid } }
                    ?: list.firstOrNull()?.id
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

    fun setDeepDream(enabled: Boolean) {
        c.updateSettings { it.copy(deepDream = enabled) }
    }

    fun setAutoLearn(enabled: Boolean) {
        c.updateSettings { it.copy(autoLearn = enabled) }
    }

    fun setDreamProvider(id: String) {
        c.updateSettings { it.copy(dreamProviderId = id) }
    }

    fun setDreamIdleMinutes(minutes: Int) {
        c.updateSettings { it.copy(dreamIdleMinutes = minutes.coerceIn(1, 240)) }
    }

    fun setThemeMode(mode: String) {
        c.updateSettings { it.copy(themeMode = mode) }
    }

    fun setReasoningEffort(level: String) {
        c.updateSettings { it.copy(reasoningEffort = level) }
    }

    fun memoryCount(): Int = c.memoryBank.count()
    fun journalCount(): Int = c.journal.count()

    fun skillCount(): Int = SkillStore.list().size

    // ---- 端侧模型文件导入（SAF 选择后拷入应用模型目录）----

    var importingModel by mutableStateOf<String?>(null)
        private set

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
            onResult(c.llama.ensureStarted())
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

    fun setCustomPrompt(text: String) {
        c.updateSettings { it.copy(customPrompt = text) }
    }

    fun useDefaultWorkspace() {
        c.workspace.useDefault()
    }

    fun useSafWorkspace(uriString: String): Boolean = c.workspace.useSaf(uriString)

    fun workspaceName(): String = c.workspace.current?.displayName ?: "未绑定"
}
