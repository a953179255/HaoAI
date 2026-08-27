package com.haoai.agent.data

import android.app.Application
import com.haoai.agent.agent.memory.DailyJournal
import com.haoai.agent.agent.memory.MemoryBank
import com.haoai.agent.agent.provider.OpenAiCompatClient
import com.haoai.agent.platform.WorkspaceDocs
import com.haoai.agent.platform.WorkspaceManager
import com.haoai.agent.platform.llama.LlamaServerController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class AppContainer(app: Application) {

    val appFilesDir: java.io.File = app.filesDir

    val appContext: android.content.Context = app

    val cipher = KeystoreCipher()
    val settingsStore = SettingsStore(app)
    val sessionStore = SessionStore(app)
    val workspace = WorkspaceManager(app)
    val memoryBank = MemoryBank(appFilesDir)
    val journal = DailyJournal(
        appFilesDir,
        storageDir = (workspace.current as? com.haoai.agent.platform.RawFileBackend)
            ?.shellWorkdir()?.let { java.io.File(it, "memory").apply { mkdirs() } }
    )

    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .addInterceptor(com.haoai.agent.platform.NetGuard.interceptor())
        .build()

    val client = OpenAiCompatClient(okHttpClient)

    val llama = LlamaServerController(app, okHttpClient)

    val settingsFlow = MutableStateFlow(settingsStore.load())

    init {
        // 存储单例先初始化（WorkspaceDocs 异步任务会用到）
        com.haoai.agent.agent.schedule.ScheduleStore.init(appFilesDir)
        com.haoai.agent.agent.skills.SkillStore.init(appFilesDir)
        llama.preferredModel = settingsFlow.value.localModelFile
        llama.contextSize = settingsFlow.value.localContextLength
        syncWorkspaceDocs()
    }

    fun updateSettings(edit: (AppSettings) -> AppSettings) {
        val next = edit(settingsFlow.value)
        settingsStore.save(next)
        settingsFlow.value = next
    }

    /** 把记忆/身份等渲染为工作区 Markdown（上游 式文件层），异步执行。 */
    fun syncWorkspaceDocs() {
        applicationScope.launch { runCatching { WorkspaceDocs.syncAll(this@AppContainer) } }
    }

    fun activeProviderById(id: String?): ProviderConfig? =
        id?.let { pid -> settingsFlow.value.providers.find { it.id == pid } }
            ?: activeProvider()

    data class DreamTarget(val provider: ProviderConfig, val apiKey: String, val isLocal: Boolean)

    /**
     * 解析「管理记忆的模型」目标：local=端侧小模型（会拉起 llama.cpp），
     * 其他=对应云端服务 id；不可用时返回 null（调用方回退纯规则）。
     */
    suspend fun resolveDreamTarget(): DreamTarget? {
        val st = settingsFlow.value
        return if (st.dreamProviderId == "local") {
            val up = runCatching {
                // 记忆专用端侧小模型：指定了其他 GGUF 时 ensureStarted 会自动重启切换
                llama.ensureStarted(st.dreamLocalModelFile?.takeIf { it.isNotBlank() })
            }.getOrDefault(false)
            if (!up) null
            else DreamTarget(
                ProviderConfig(
                    id = "dream", name = "local-dream",
                    baseUrl = com.haoai.agent.platform.llama.LlamaServerController.LOCAL_BASE_URL,
                    model = "local"
                ),
                apiKey = "",
                isLocal = true
            )
        } else {
            val p = activeProviderById(st.dreamProviderId) ?: return null
            if (p.baseUrl.startsWith("local")) return null
            DreamTarget(p, runCatching { cipher.decrypt(p.apiKeyCipher) }.getOrDefault(""), false)
        }
    }

    fun activeProvider(): ProviderConfig? {
        val s = settingsFlow.value
        return s.providers.find { it.id == s.activeProviderId } ?: s.providers.firstOrNull()
    }
}
