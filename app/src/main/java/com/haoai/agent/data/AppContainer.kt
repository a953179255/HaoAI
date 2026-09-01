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
    // 长期记忆真源 = 工作区 MEMORY.md（上游 式"文件即记忆"）；SAF 工作区时回退内部目录
    val memoryBank = MemoryBank(
        appFilesDir,
        storageFile = (workspace.current as? com.haoai.agent.platform.RawFileBackend)
            ?.shellWorkdir()?.let { java.io.File(it, "MEMORY.md") }
    )
    val journal = DailyJournal(
        appFilesDir,
        storageDir = (workspace.current as? com.haoai.agent.platform.RawFileBackend)
            ?.shellWorkdir()?.let { java.io.File(it, "memory").apply { mkdirs() } }
    )

    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Linux 沙箱 proot 安装（3.1）：null = 当前设备不可用（ABI 无包/sha 不符），3.3 后端据此回落 toybox。 */
    val proot: com.haoai.agent.platform.sandbox.Proot.Install? = runCatching {
        com.haoai.agent.platform.sandbox.Proot.ensureReady(appFilesDir, app.applicationInfo.nativeLibraryDir)
    }.getOrNull()

    val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .addInterceptor(com.haoai.agent.platform.NetGuard.interceptor())
        .build()

    val client = OpenAiCompatClient(okHttpClient)
    val anthropicClient = com.haoai.agent.agent.provider.AnthropicClient(okHttpClient)

    /** Linux 发行版管理（3.2）：下载/校验/解压/初始化 rootfs，供 3.3 ProotBackend 使用。 */
    val distros = com.haoai.agent.platform.sandbox.DistroManager(appFilesDir, okHttpClient)

    /** 按供应商配置的协议选客户端（2.3）：anthropic 原生 / openai_compat（默认）。 */
    fun clientFor(provider: ProviderConfig?): com.haoai.agent.agent.provider.ProviderClient {
        val base = if (provider?.protocol == "anthropic") anthropicClient else client
        // 5.2 降级链：仅当 provider 是链头（主 Provider）且链非空时包装
        val st = settingsFlow.value
        val chain = st.fallbackChain
        if (provider == null || chain.isEmpty() || provider.id != st.activeProviderId) return base
        return com.haoai.agent.agent.provider.FallbackClient(
            resolve = { p -> if (p.protocol == "anthropic") anthropicClient else client },
            lookup = { id -> st.providers.find { it.id == id } },
            decrypt = { p -> runCatching { cipher.decrypt(p.apiKeyCipher) }.getOrDefault("") },
            fallbackIds = chain,
            onFallback = { name -> lastFallbackNotice = name }
        )
    }

    /** 5.2 最近一次降级通知（UI 轮询显示「已降级到 X」）；null=无。 */
    @Volatile var lastFallbackNotice: String? = null

    val llama = LlamaServerController(app, okHttpClient)

    val settingsFlow = MutableStateFlow(settingsStore.load())

    /** 工作区配置文件桥（haoai.config.json）：agent 可直接改的 上游 式镜像配置。 */
    val configFile: java.io.File =
        (workspace.current as? com.haoai.agent.platform.RawFileBackend)?.shellWorkdir()
            ?.let { java.io.File(it, "haoai.config.json") }
            ?: java.io.File(appFilesDir, "haoai.config.json")
    val configBridge = ConfigFileBridge(
        file = configFile,
        cipher = cipher,
        updateSettings = { edit -> updateSettings(edit) },
        readSettings = { settingsFlow.value }
    )

    init {
        // 存储单例先初始化（WorkspaceDocs 异步任务会用到）
        com.haoai.agent.agent.schedule.ScheduleStore.init(appFilesDir)
        com.haoai.agent.agent.skills.SkillStore.init(appFilesDir)
        // 5.4 运行账本（LLM/工具调用 JSONL，按月分文件 + 90 天清理）
        UsageLedger.init(appFilesDir)
        // Phase 6 工作流存储 + schedule 触发重入队（冷启/进程重建后恢复调度链）
        com.haoai.agent.agent.workflow.WorkflowStore.init(appFilesDir)
        applicationScope.launch {
            runCatching {
                com.haoai.agent.agent.workflow.WorkflowStore.list()
                    .filter { it.enabled && !it.pendingConfirm && it.trigger.type == "schedule" }
                    .forEach { wf ->
                        com.haoai.agent.agent.workflow.WorkflowRunner.enqueueSchedule(
                            app, wf.id, wf.trigger.config
                        )
                    }
            }
        }
        // 4.2 内置浏览器控制器：WebView 池（工具无头运行 + BrowserScreen 可视共用）
        com.haoai.agent.agent.browser.BrowserController.init(app, applicationScope)
        // MCP：加载服务器配置 → 注册风险覆盖/白名单 → enabled 服务器异步握手（不阻塞启动）
        // sandboxProvider 供 stdio 类型（3.5）现场解析沙箱：发行版装/删即时生效
        com.haoai.agent.agent.mcp.McpManager.init(appFilesDir, okHttpClient, sandboxProvider = {
            val wsDir = (workspace.current as? com.haoai.agent.platform.RawFileBackend)?.shellWorkdir()
                ?: java.io.File(appFilesDir, "shell-home")
            com.haoai.agent.platform.sandbox.SandboxEnv.resolve(
                appFilesDir, app.applicationInfo.nativeLibraryDir, wsDir
            )
        })
        com.haoai.agent.agent.mcp.McpManager.connectAll(applicationScope)
        llama.preferredModel = settingsFlow.value.localModelFile
        llama.contextSize = settingsFlow.value.localContextLength
        syncWorkspaceDocs()
        // 配置文件桥：启动渲染镜像 + 监听外部改动（agent 写 haoai.config.json → 自动合并入库）
        configBridge.startWatching(applicationScope)
    }

    fun updateSettings(edit: (AppSettings) -> AppSettings) {
        val next = edit(settingsFlow.value)
        settingsStore.save(next)
        settingsFlow.value = next
        // 镜像配置保持与真源一致（apiKey 掩码化回写）
        runCatching { configBridge.onSettingsChanged() }
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

    /**
     * 5.3 内部任务模型路由：按用途解析专用 provider（memoryExtract/title/summarize）。
     * 空配置回落主模型；"local"=端侧聊天模型（拉起 llama.cpp）；目标不可用返回 null（调用方回落主模型）。
     */
    suspend fun resolvePurposeTarget(purposeProviderId: String): DreamTarget? {
        val st = settingsFlow.value
        val id = purposeProviderId.trim()
        if (id.isEmpty()) return null
        return if (id == "local") {
            val up = runCatching { llama.ensureStarted(st.localModelFile?.takeIf { it.isNotBlank() }) }.getOrDefault(false)
            if (!up) null
            else DreamTarget(
                ProviderConfig(
                    id = "purpose-local", name = "local",
                    baseUrl = com.haoai.agent.platform.llama.LlamaServerController.LOCAL_BASE_URL,
                    model = "local"
                ),
                apiKey = "",
                isLocal = true
            )
        } else {
            val p = st.providers.find { it.id == id } ?: return null
            if (p.baseUrl.startsWith("local")) return null
            DreamTarget(p, runCatching { cipher.decrypt(p.apiKeyCipher) }.getOrDefault(""), false)
        }
    }

    fun activeProvider(): ProviderConfig? {
        val s = settingsFlow.value
        return s.providers.find { it.id == s.activeProviderId } ?: s.providers.firstOrNull()
    }
}
