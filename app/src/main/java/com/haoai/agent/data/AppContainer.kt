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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class AppContainer(app: Application) {

    val appFilesDir: java.io.File = app.filesDir

    val appContext: android.content.Context = app

    init {
        // models.dev 目录三级缓存需要 Context（磁盘缓存 + 内置 asset 兜底）
        ModelCatalog.init(app)
    }

    val cipher = KeystoreCipher()
    val settingsStore = SettingsStore(app)
    val sessionStore = SessionStore(app)
    // M18（2026-10-10）：applicationScope 声明上移到 init 之前——冷启动清扫要往里投协程。
    // （原声明在 init 块之后；Kotlin init 按声明顺序执行，提前引用会 NPE。）
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * P4 后台子代理完成协调器：后台子代理跨回合存活，完成后由它批合并通知、
     * 优先走前台 VM 通路注入、VM 不在时起无头唤醒回合。lazy=首次派后台子代理才实例化。
     */
    val backgroundSubagents by lazy { com.haoai.agent.platform.BackgroundSubagentCoordinator(this) }

    init {
        // 冷启动清扫：引擎只活在本进程，上个进程留下的 running 一律判成中断，
        // 否则那些会话既没有恢复入口也没有停止键，会永久卡住（放在 sessionStore
        // 之后声明，Kotlin 的 init 块按声明顺序执行）
        //
        // M18：改异步执行。原实现同步跑在主线程（AppContainer 在 Application 构造期
        // 建出来），粗筛要对每个会话文件整份 readText 一遍——会话库一大，冷启动
        // 白屏时间线性上涨。挪到后台后，清扫完成的先后只影响"恢复横幅晚一拍出现"
        // （清扫把 running→interrupted 落盘并更新磁盘缓存，下一次列表刷新即正确），
        // 用户在清扫完成的 1 秒内恰好点进那条会话的概率可以忽略。
        applicationScope.launch { runCatching { sessionStore.markStaleRunsInterrupted() } }
    }
    val workspace = WorkspaceManager(app)

    /**
     * 脑根（v2 架构解耦 2026-10-24）：内部私有固定目录，永不随工作区档位移动。
     * 脑 = Agent 的身份/记忆/技能/待办（MEMORY.md、USER.md、skills/、todos/…）；
     * 工作区 = Agent 生成的材料（三档可换、切档零搬运）。
     * 旧版脑混在工作区根随档位被整树复制（7 万文件迁移事故根源），本目录将其分离。
     */
    val brainDir: java.io.File = java.io.File(app.filesDir, "brain").apply { mkdirs() }

    init {
        // 一次性脑分家（幂等）：旧版脑文件散在各旧位置，搬进 brainDir。
        // 复制→字节校验→删源；目标赢不覆盖；失败不写 marker 下次重试。
        // 三个源缺一不可（2026-10-24 实测教训）：①当前工作区根（引擎在用的那份）
        // ②旧默认工作区（应用专属）——升级用户若被新装默认切走过，脑还留在这
        // ③内部 filesDir 根（SAF 档 legacy 回退位 + todos 旧位）。
        // 同步执行：必须赶在 memoryBank 首次读取之前（分家仅几十个小文件，一次性成本）。
        val split = com.haoai.agent.platform.BrainMigrator.splitIfNeeded(
            brainDir,
            (workspace.current as? com.haoai.agent.platform.RawFileBackend)?.shellWorkdir(),
            workspace.defaultWorkspaceRoot(),
            appFilesDir
        )
        if (!split.ok) {
            android.util.Log.w("HaoAI", "脑分家未完成(moved=${split.moved},failed=${split.failed})，下次启动重试")
        }
    }

    // 长期记忆真源 = 脑根 MEMORY.md（"文件即记忆"）；v2 起固定内部私有，不随工作区移动
    val memoryBank = MemoryBank(
        appFilesDir,
        storageFile = java.io.File(brainDir, "MEMORY.md")
    )
    val journal = DailyJournal(
        appFilesDir,
        storageDir = java.io.File(brainDir, "memory").apply { mkdirs() }
    )

    /** Linux 沙箱 proot 安装（3.1）：null = 当前设备不可用（ABI 无包/sha 不符），3.3 后端据此回落 toybox。 */
    // M18（2026-10-10）：proot 安装从构造期同步求值改为 lazy。
    // ensureReady 里要对 libproot.so（数 MB）算 sha256，原来每次冷启动都在主线程算一遍；
    // lazy 后首次真正用到沙箱时才算（工具执行/设置页检查都在后台），冷启动零开销。
    // 配合 Proot.ensureReady 内部的 sha 结果磁盘缓存，热路径连哈希都不用重算。
    val proot: com.haoai.agent.platform.sandbox.Proot.Install? by lazy {
        runCatching {
            com.haoai.agent.platform.sandbox.Proot.ensureReady(appFilesDir, app.applicationInfo.nativeLibraryDir)
        }.getOrNull()
    }

    val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        // 120s：流式期间静默死亡的网络（如飞行模式静默断连）最长拖 2 分钟就报错，
        // 300s 会让用户误判卡死（e2e P2-4）。推理思考的静默间隙远小于此（reasoning 持续出 delta）
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .addInterceptor(com.haoai.agent.platform.NetGuard.interceptor())
        .build()

    val client = OpenAiCompatClient(okHttpClient)
    val anthropicClient = com.haoai.agent.agent.provider.AnthropicClient(okHttpClient)

    /** Linux 发行版管理（3.2）：下载/校验/解压/初始化 rootfs，供 3.3 ProotBackend 使用。 */
    val distros = com.haoai.agent.platform.sandbox.DistroManager(appFilesDir, okHttpClient)

    /** 按供应商配置的协议选客户端（2.3）：anthropic 原生 / openai_compat（默认）。
     *  E2 请求级重试（ResilientCall）永远包裹单次请求：网络错误/429/5xx/空流
     *  指数退避重试（1s/2s/4s，最多 3 次），已产出 delta 不重放。
     *  5.2 降级链（FallbackClient）仅当 provider 是链头且链非空时最外层包装：
     *  单 provider 重试耗尽后才切备用。 */
    fun clientFor(provider: ProviderConfig?): com.haoai.agent.agent.provider.ProviderClient {
        if (provider == null) return client
        fun resolve(p: ProviderConfig): com.haoai.agent.agent.provider.ProviderClient {
            val c = if (p.protocol == "anthropic") anthropicClient else client
            return com.haoai.agent.agent.provider.ResilientCall(resolve = { _ -> c })
        }
        val st = settingsFlow.value
        val chain = st.fallbackChain
        if (chain.isEmpty() || provider.id != st.activeProviderId) return resolve(provider)
        return com.haoai.agent.agent.provider.FallbackClient(
            resolve = { p -> resolve(p) },
            lookup = { id -> st.providers.find { it.id == id } },
            decrypt = { p -> resolveApiKey(p) },
            fallbackIds = chain,
            onFallback = { name -> lastFallbackNotice = name }
        )
    }

    /** 5.2 最近一次降级通知（UI 轮询显示「已降级到 X」）；null=无。 */
    @Volatile var lastFallbackNotice: String? = null

    // ── API Key 池：主 Key + 备用池按策略逐请求轮换，分摊单 Key 限流 ──
    private val keyCursor = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** 解密供应商当前应使用的 API Key（池内轮换；解密失败回退空串）。 */
    fun resolveApiKey(p: ProviderConfig): String {
        val pool = (listOf(p.apiKeyCipher) + p.apiKeyPoolCiphers).filter { it.isNotBlank() }
        if (pool.isEmpty()) return ""
        val idx = when {
            pool.size == 1 -> 0
            p.keyRotation == "RANDOM" -> (pool.indices).random()
            else -> {
                val cur = keyCursor[p.id] ?: 0
                keyCursor[p.id] = (cur + 1) % pool.size
                cur % pool.size
            }
        }
        return runCatching { cipher.decrypt(pool[idx]) }.getOrDefault("")
    }

    /**
     * 该供应商地址是否必须配置 API Key：回环/私有网段/本机 hostname（本机或局域网
     * 推理服务器，如 Ollama、LM Studio）免 key；公网地址空 key 会在发送前被拦截，
     * 而不是无 Authorization 照发等供应商 401。
     */
    fun needsApiKey(baseUrl: String): Boolean {
        // 端侧 llama（local://llama，URI 无 host）永不校验 key
        if (baseUrl.startsWith("local")) return false
        val host = runCatching { java.net.URI(baseUrl.trim()).host }.getOrNull() ?: return true
        if (host.isEmpty()) return true
        if (host == "localhost" || host == "::1" || host == "10.0.2.2") return false
        if (host.startsWith("127.") || host.endsWith(".local") || host.endsWith(".lan")) return false
        val parts = host.split(".").mapNotNull { it.toIntOrNull() }
        if (parts.size == 4) {
            val (a, b) = parts
            if (a == 10 || (a == 192 && b == 168) || (a == 172 && b in 16..31)) return false
        }
        return true
    }

    val llama = LlamaServerController(app, okHttpClient)

    /** 设置读改写互斥：UI 主线程 / flushUsage(IO) / ConfigFileBridge(IO) 并发写会丢更新。
     *  A1：声明上移到 settingsFlow 之前——异步灌入与 updateSettings 要共用这把锁。 */
    private val settingsLock = Any()

    /**
     * A1（2026-10-15）：设置改异步读取。
     * 旧实现在 Application 构造期主线程同步 settingsStore.load()——settings.json 全量
     * 反序列化（实测 ANR trace：HaoApplication.onCreate → AppContainer.<init> →
     * SettingsStore.load → AppSettings$$serializer.deserialize 占住主线程）直接触发
     * 「HaoAI 没有响应」，且 ANR 对话框期间系统会重投递 intent 造成任务双跑。
     * 现在初值给默认 AppSettings()，真值在 applicationScope（IO）读完后一次性灌入并置
     * settingsReady；消费方约定：
     * - UI 首帧（MainActivity.RootApp）以 settingsReady 为门，未就绪不判 onboarding/聊天；
     * - 深链投递 await settingsReady；
     * - ready 前触发 updateSettings 的按调用方门禁（UI/深链都已挡）计风险≈0。
     */
    val settingsFlow = MutableStateFlow(AppSettings())

    /** 设置真值已从磁盘灌入（就绪门，见 settingsFlow 注释）。
     *  StateFlow 形态供 Compose 首帧订阅（避免默认 onboarded=false 闪一下引导页）；
     *  @Volatile 影子供 awaitSettingsReady 忙读。 */
    val settingsReadyFlow = MutableStateFlow(false)
    @Volatile private var settingsReady = false

    /** 挂起等待设置就绪（已就绪立即返回）；超时也放行让调用方按当前值走。 */
    suspend fun awaitSettingsReady(timeoutMs: Long = 8_000): Boolean {
        if (settingsReady) return true
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!settingsReady && System.currentTimeMillis() < deadline) {
            kotlinx.coroutines.delay(40)
        }
        return settingsReady
    }

    /**
     * C6 配置源迁状态目录（app 私有，config 归状态目录）：
     * 工作区不再出现 haoai.config.json，agent 的 read/write/edit 摸不到它——
     * agent 改配置唯一入口是 config_get/config_set 工具（恒审批）。
     * state/agents/ 为多 Agent 预留骨架（将来每个 Agent 一个子目录：config + sessions）。
     */
    val configFile: java.io.File =
        java.io.File(appFilesDir, "state/haoai.config.json").apply {
            parentFile?.mkdirs()
            java.io.File(parentFile, "agents/main").mkdirs()
        }
    val configBridge = ConfigFileBridge(
        file = configFile,
        cipher = cipher,
        updateSettings = { edit -> updateSettings(edit) },
        readSettings = { settingsFlow.value },
        // MCP/SSH 分区当前值（config_set 校验掩码沿用 + 审批 diff 对比）
        readMcp = { com.haoai.agent.agent.mcp.McpManager.listServers() },
        readSsh = { com.haoai.agent.agent.tools.shell.SshBackend.loadTargets(appFilesDir) },
        // 分区同步：apply（config_set 与轮询兜底两条路径）成功后落各自存储+重连，备注拼进结果
        // MCP 部分含 suspend 重连（delay/HTTP），不得在主线程 runBlocking（冷启动迁移会 ANR）——
        // 统一丢 applicationScope；SSH 仅本地读写，保持同步返回备注。
        onExtChanged = { p ->
            val notes = mutableListOf<String>()
            p.mcpServers?.let { new ->
                applicationScope.launch {
                    runCatching {
                        val cur = com.haoai.agent.agent.mcp.McpManager.listServers()
                        if (p.mcpRemoved) {
                            cur.filter { s -> new.none { it.id == s.id } }.forEach {
                                com.haoai.agent.agent.mcp.McpManager.removeServer(it.id)
                            }
                        }
                        new.forEach { cfg ->
                            val old = cur.find { it.id == cfg.id }
                            // 内容不变跳过（轮询路径每次 apply 都带全量分区，避免无谓重连）
                            if (old == cfg) return@forEach
                            when {
                                old == null -> com.haoai.agent.agent.mcp.McpManager.addServer(cfg)
                                else -> com.haoai.agent.agent.mcp.McpManager.updateServer(cfg)
                            }
                        }
                    }
                }
                notes += "MCP 配置已提交同步（连接稍后生效）"
            }
            p.sshTargets?.let { new ->
                val cur = com.haoai.agent.agent.tools.shell.SshBackend.loadTargets(appFilesDir)
                val merged = if (p.sshRemoved) new else cur.filter { t -> new.none { it.id == t.id } } + new
                com.haoai.agent.agent.tools.shell.SshBackend.saveTargets(appFilesDir, merged)
                notes += "SSH 目标已同步（共 ${merged.size} 个）"
            }
            if (notes.isEmpty()) "" else "；" + notes.joinToString("；")
        }
    )

    init {
        // 存储单例先初始化（WorkspaceDocs 异步任务会用到）
        com.haoai.agent.agent.schedule.ScheduleStore.init(appFilesDir)
        // v2：技能根固定脑目录 brain/skills（不再随工作区档位移动）；
        // filesDir/skills 与工作区 skills 的存量由 BrainMigrator 一次性分家收编
        com.haoai.agent.agent.skills.SkillStore.init(brainDir)
        // C 出厂预置联网调研技能（迁移落定后播种，用户删除后凭标记不复活）
        com.haoai.agent.agent.skills.SkillStore.seedBundledResearchSkill(
            java.io.File(appFilesDir, ".bundled_skills")
        )
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
        // A1：llama.preferredModel/contextSize 的设置灌入挪进 settings 异步读完成处
        syncWorkspaceDocs()
        // C6：工作区旧 haoai.config.json 一次性迁移挪进 settings 异步读完成处（A1-1）——
        // 构造期直接跑会和尚未灌入的 settingsFlow 初值抢写（apply→updateSettings
        // 基于"默认设置"改写、随后异步 load 用磁盘值覆盖 = 用户改动丢失）。
        // 配置文件桥：启动渲染镜像 + 监听外部改动（兜底覆盖 adb/文件管理器手改状态目录文件）
        configBridge.startWatching(applicationScope)
    }

    init {
        // A1 修复版（2026-10-15）：settings 异步读取。
        // ⚠ 此 init 块必须位于其闭包引用的全部字段声明之后（settingsStore/settingsFlow/
        //   settingsLock/llama/workspace/configBridge）——协程在 IO 线程与构造函数并发跑，
        //   引用尚未初始化的字段会 NPE（初版放在 configBridge 之前，实测每次冷启动
        //   "卡启动屏数秒→进程被杀"，即此竞态）。
        // ⚠ 整体 try/finally 兜底：任何失败都置 ready（按默认设置继续），就绪门绝不
        //   永久卡死 UI；失败详情进日志。
        applicationScope.launch {
            try {
                val loaded = settingsStore.load()
                synchronized(settingsLock) { settingsFlow.value = loaded }
                // 玻璃质感：持久化参数灌入运行态镜像（GlassTuning 单例，玻璃组件逐帧读）
                com.haoai.agent.ui.theme.GlassTuning.loadFrom(loaded.glass)
                // llama 参数补灌：原在构造期直读 settingsFlow.value（那时已是真值，异步后挪进来）
                llama.preferredModel = loaded.localModelFile
                llama.contextSize = loaded.localContextLength
                llama.tuning = LlamaServerController.LlamaTuning(
                    threads = loaded.localThreads,
                    backend = loaded.localBackend,
                    flashAttn = loaded.localFlashAttn,
                    kvCache = loaded.localKvCache,
                    mmap = loaded.localMmap,
                    seed = loaded.localSeed
                )
                // C6 迁移：legacy config apply 走 updateSettings，必须在 settingsFlow 真值就位后执行
                runCatching {
                    (workspace.current as? com.haoai.agent.platform.RawFileBackend)?.shellWorkdir()?.let { ws ->
                        val legacy = java.io.File(ws, "haoai.config.json")
                        if (legacy.exists()) {
                            runCatching { legacy.readText() }.getOrNull()?.let { configBridge.apply(it) }
                            runCatching { legacy.delete() }
                        }
                    }
                }
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (t: Throwable) {
                android.util.Log.e("HaoAppContainer", "settings 异步加载失败，按默认设置继续", t)
            } finally {
                settingsReady = true
                settingsReadyFlow.value = true
            }
        }
    }

    fun updateSettings(edit: (AppSettings) -> AppSettings) {
        synchronized(settingsLock) {
            val next = edit(settingsFlow.value)
            settingsStore.save(next)
            settingsFlow.value = next
        }
        // 镜像配置保持与真源一致（apiKey 掩码化回写）
        runCatching { configBridge.onSettingsChanged() }
    }

    /**
     * 挂起等到 settings 异步加载完成（成功或失败兜底都会置位），再返回真值。
     *
     * ⚠ 事故背景（2026-10-24 真机实测）：init 第一个块里就调 syncWorkspaceDocs()，
     *   那时 settingsFlow 还是默认空值，renderSoul/renderIdentity 拿到空 soul / 空
     *   agentName，于是把 BrainMigrator 刚搬进脑目录的用户人设（柠瑶🍋、元气甜系）
     *   覆盖成「用户未特别设定」默认模板——镜像文件直接把用户设定冲掉了。
     * 规矩：任何异步任务要读设置，一律走这里，禁止直接取 settingsFlow.value。
     */
    suspend fun awaitSettings(): AppSettings {
        settingsReadyFlow.first { it }
        return settingsFlow.value
    }

    /** 把记忆/身份等渲染为工作区 Markdown（文件层），异步执行。 */
    fun syncWorkspaceDocs() {
        applicationScope.launch { runCatching { WorkspaceDocs.syncAll(this@AppContainer) } }
    }

    /**
     * 从相册导入档案头像：降采样压缩后拷入应用私有目录，返回新文件路径（失败 null）。
     * 可在任意协程上下文调用（内部自行切 IO）。[mine]=true 导入「我的档案」头像，
     * 旧文件清理各槽独立（聊天社交化 2026-10-07）。
     */
    suspend fun importAvatarImage(uri: android.net.Uri, mine: Boolean): String? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val resolver = appContext.contentResolver
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                resolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 512) sample *= 2
                val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
                val bmp = resolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, opts) }
                    ?: return@runCatching null
                val dir = java.io.File(appFilesDir, "avatar").apply { mkdirs() }
                // 旧头像文件一并删除，避免私有目录堆积
                val old = if (mine) settingsFlow.value.myAvatarPath else settingsFlow.value.avatarImagePath
                old?.let { f -> if (f.startsWith(dir.absolutePath)) java.io.File(f).delete() }
                val prefix = if (mine) "myavatar_" else "avatar_"
                val out = java.io.File(dir, prefix + System.currentTimeMillis() + ".jpg")
                out.outputStream().use { fos -> bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, fos) }
                bmp.recycle()
                out.absolutePath
            }.getOrNull()
        }

    /**
     * 工作区切换后同步引擎文档（v2：只同步脑根文档，技能/记忆/待办均在 brainDir 固定不动）。
     * 切档 = 纯换指针零搬运，这里不再有任何技能落点迁移。
     */
    fun onWorkspaceSwitched() {
        syncWorkspaceDocs()
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
            DreamTarget(p, resolveApiKey(p), false)
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
            // "providerId|modelId"：同供应商多模型精确路由——把目标模型提为该副本的 model，
            // 原默认退入备选列表，能力条目跟随模型 ID 不丢
            val (pid, modelOverride) = if (id.contains('|')) {
                id.split('|', limit = 2).let { it[0] to it[1] }
            } else id to null
            val p = st.providers.find { it.id == pid } ?: return null
            if (p.baseUrl.startsWith("local")) return null
            val effective = if (modelOverride != null && modelOverride != p.model &&
                (modelOverride == p.model || p.models.any { it.id == modelOverride })
            ) {
                val oldEntry = p.models.find { it.id == p.model }
                    ?: p.model.takeIf { it.isNotBlank() }?.let { ModelEntry(it) }
                p.copy(
                    model = modelOverride,
                    models = p.models.filterNot { it.id == modelOverride } + listOfNotNull(oldEntry)
                )
            } else p
            DreamTarget(effective, resolveApiKey(p), false)
        }
    }

    /**
     * 用途模型链解析：主目标 + 备用链按序去重，返回全部可用目标。
     * 调用方按序尝试、失败降级；主目标为空配置时返回空列表（调用方回落主模型）。
     */
    suspend fun resolvePurposeTargets(primaryId: String, fallbackIds: List<String>): List<DreamTarget> {
        val ids = (listOf(primaryId.trim()) + fallbackIds.map { it.trim() })
            .filter { it.isNotEmpty() }.distinct()
        return ids.mapNotNull { resolvePurposeTarget(it) }
    }

    fun activeProvider(): ProviderConfig? {
        val s = settingsFlow.value
        return s.providers.find { it.id == s.activeProviderId } ?: s.providers.firstOrNull()
    }

    /**
     * 「设置 → 搜索服务」的主后端配置。key 只在这里解密（Keystore 密文存 settings.json，
     * 明文不落盘），所以每次搜索现取——用户刚填完 key 不必重启引擎就能生效。
     *
     * 选「内置免 key」时同样返回配置（backend=builtin，工具侧 `external=false` 就走原引擎链）：
     * 「结果条数」是用户对检索的统一偏好，内置路径也要能读到，否则设置项只对三家外部后端生效。
     */
    fun searchProviderConfig(): com.haoai.agent.agent.tools.SearchProviderConfig {
        val s = settingsFlow.value
        val backend = if (com.haoai.agent.agent.tools.SearchProviders.isKnown(s.searchBackend)) {
            s.searchBackend
        } else {
            com.haoai.agent.agent.tools.SearchProviders.BUILTIN
        }
        return com.haoai.agent.agent.tools.SearchProviderConfig(
            backend = backend,
            apiKey = cipher.decrypt(s.searchApiKeyCiphers[backend]),
            count = s.searchCount,
            fallback = s.searchFallback,
            options = s.searchOptions
        )
    }

    /**
     * 聊天会话生效模型：设置里「模型切换 → 聊天会话」配置了就按配置解析
     * （"pid" 或 "pid|modelId"，支持同供应商精确到模型），否则回落当前激活供应商。
     * local 配置不在此处理——聊天入口的 resolveProvider 已有端侧拉起逻辑，
     * 这里返回 LOCAL_PROVIDER_ID 的原始条目让其照走原路径。
     */
    fun activeChatProvider(): ProviderConfig? {
        val s = settingsFlow.value
        val cfg = s.chatPurposeId.trim()
        if (cfg.isEmpty() || cfg == "local") return activeProvider()
        val (pid, modelOverride) = if (cfg.contains('|')) {
            cfg.split('|', limit = 2).let { it[0] to it[1] }
        } else cfg to null
        val p = s.providers.find { it.id == pid } ?: return activeProvider()
        if (modelOverride == null || modelOverride == p.model) return p
        // 目标模型必须在该供应商的已知集合里（默认或备选），否则配置已失效→回落
        val known = modelOverride == p.model || p.models.any { it.id == modelOverride }
        if (!known) return activeProvider()
        val oldEntry = p.models.find { it.id == p.model }
            ?: p.model.takeIf { it.isNotBlank() }?.let { ModelEntry(it) }
        return p.copy(
            model = modelOverride,
            models = (p.models.filterNot { it.id == modelOverride } + listOfNotNull(oldEntry))
                .distinctBy { it.id }
        )
    }
}
