package com.haoai.agent

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import com.haoai.agent.platform.WebViewWarmer
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.animation.togetherWith
import androidx.compose.ui.zIndex
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.haoai.agent.platform.KeepAliveService
import com.haoai.agent.ui.ChatViewModel
import com.haoai.agent.ui.SettingsViewModel
import com.haoai.agent.ui.chat.ChatScreen
import com.haoai.agent.ui.common.GlassPanel
import com.haoai.agent.ui.common.LiquidGlassButton
import com.haoai.agent.ui.settings.SettingsScreen
import com.haoai.agent.ui.theme.HaoTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val notifPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private var pendingRuntimeCb: ((Map<String, Boolean>) -> Unit)? = null
    private var pendingResultCb: ((android.content.Intent?) -> Unit)? = null

    private val runtimePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            pendingRuntimeCb?.invoke(result)
            pendingRuntimeCb = null
        }

    private val activityLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            pendingResultCb?.invoke(if (r.resultCode == RESULT_OK) r.data else null)
            pendingResultCb = null
        }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        // 深链/第三方 startActivity 重投递时框架不自动更新 getIntent()，须手动 setIntent
        setIntent(intent)
        // setIntent 不触发重组：已运行实例的深链改走 flow 投递（单靠 LaunchedEffect(intent.data) 会漏）
        deepLinkFlow.value = intent.data
        handleSendIntent(intent)
    }

    /** onNewIntent 投递的深链（含冷启动 intent 之外的所有重投递）。 */
    internal val deepLinkFlow = kotlinx.coroutines.flow.MutableStateFlow<android.net.Uri?>(null)

    /** 系统分享接入（R2.2）：(文本, 图片Uri)，ChatScreen 导航层消费。 */
    internal val shareFlow = kotlinx.coroutines.flow.MutableStateFlow<Pair<String?, android.net.Uri?>?>(null)

    private fun handleSendIntent(intent: android.content.Intent?) {
        if (intent?.action != android.content.Intent.ACTION_SEND) return
        val text = intent.getStringExtra(android.content.Intent.EXTRA_TEXT)
        @Suppress("DEPRECATION")
        val stream = intent.getParcelableExtra<android.net.Uri>(android.content.Intent.EXTRA_STREAM)
        if (text != null || stream != null) shareFlow.value = text to stream
    }

    override fun onResume() {
        super.onResume()
        // 悬浮窗仅后台显示的判定源：回到前台即隐藏（聊天页内有完整进度）
        com.haoai.agent.platform.RunObserver.appForeground = true
    }

    override fun onPause() {
        super.onPause()
        com.haoai.agent.platform.RunObserver.appForeground = false
    }

    override fun onDestroy() {
        // 静态桥持有 Activity 闭包：不清理会在主题/配置变更后泄漏整棵 View
        if (com.haoai.agent.platform.PermissionBridge.requestRuntime != null &&
            pendingRuntimeCb != null
        ) {
            // 回调已挂起的协程交给超时兜底，避免永远等不到系统结果
            pendingRuntimeCb?.invoke(emptyMap())
        }
        pendingRuntimeCb = null
        pendingResultCb = null
        com.haoai.agent.platform.PermissionBridge.requestRuntime = null
        com.haoai.agent.platform.PermissionBridge.startForResult = null
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationIfNeeded()
        handleSendIntent(intent)
        com.haoai.agent.platform.PermissionBridge.requestRuntime = { perms, cb ->
            runOnUiThread {
                pendingRuntimeCb = cb
                runtimePermissionLauncher.launch(perms.toTypedArray())
            }
        }
        com.haoai.agent.platform.PermissionBridge.startForResult = { intent, cb ->
            runOnUiThread {
                pendingResultCb = cb
                activityLauncher.launch(intent)
            }
        }
        // WebView 预热：网页渲染预览首次打开若现场拉起渲染进程会黑屏+卡 ~1s。
        // 首帧稳定后创建 1px 壳 WebView 加载空白页，让进程/Provider 提前就绪
        // （app 级 context 持有，不随 Activity 销毁；进程存活即预热有效）
        window.decorView.postDelayed({
            runCatching {
                WebViewWarmer.warm(applicationContext)
            }
        }, 2500)
        setContent {
            val app = applicationContext as HaoApplication
            val settings by app.container.settingsFlow.collectAsState()
            val dark = when (settings.themeMode) {
                "dark" -> true
                "light" -> false
                else -> androidx.compose.foundation.isSystemInDarkTheme()
            }
            // 壁纸提升到主题层：开动态颜色时直接从聊天壁纸取色（换壁纸配色即变）
            val wpVersion by com.haoai.agent.platform.WallpaperStore.changes.collectAsState()
            val dm = androidx.compose.ui.platform.LocalContext.current.resources.displayMetrics
            val wallpaper = androidx.compose.runtime.remember(wpVersion, dm.widthPixels, dm.heightPixels) {
                // 预缩放到屏幕 cover 尺寸：backdrop 画布每帧 1:1 贴图，
                // 消除每帧现算缩放（实测转场期 Slow bitmap uploads 71+ 次/10 轮）
                com.haoai.agent.platform.WallpaperStore.loadBitmapCover(
                    applicationContext, dm.widthPixels, dm.heightPixels
                )
            }
            HaoTheme(
                darkTheme = dark,
                dynamicColor = settings.dynamicColor,
                seedIndex = settings.themeSeed,
                customSeed = settings.customSeedActive,
                amoled = settings.amoledMode,
                wallpaper = wallpaper
            ) {
                // 壁纸模式开关：设置/管理页的玻璃卡据此把不透明度抬到 0.82
                // （白 58% 压在花壁纸上会把图案透出来，卡片/分隔线/徽标全糊在一起）
                androidx.compose.runtime.CompositionLocalProvider(
                    com.haoai.agent.ui.theme.LocalOnWallpaper
                        provides (settings.wallpaperGlobal && wallpaper != null)
                ) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        RootApp(wallpaper)
                    }
                }
            }
        }
    }

    private fun requestNotificationIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

// 页面导航深度：转场方向判定用（push = 进入更深一层）。
// v0.18.2 修复：旧 screenLevel 把记忆库(2)与记忆与梦境(11)粗归同一层，
// 两个方向都命中「to>=from」→ 进入和返回播同一个动画（用户反馈）。
// 记忆库是记忆与梦境的下一级（唯一入口在其中，onBack 回 11），深度必须更高。
private fun screenDepth(s: Int) = when (s) {
    0 -> 0          // 聊天（根）
    1, 4, 7 -> 1    // 设置根 / 会话列表 / 浏览器
    2 -> 3          // 记忆库（记忆与梦境的下级）
    in 9..18 -> 2   // 设置 section 子页（记忆与梦境/模型大脑/搜索服务/搜索目录/…）
    else -> 2       // 定时 / 技能 / MCP / 工作流（设置根直接进入）
}

@Composable
private fun RootApp(wallpaper: android.graphics.Bitmap?) {
    val app = LocalContext.current.applicationContext as HaoApplication
    val container = app.container
    val context = LocalContext.current

    val chatVm: ChatViewModel = viewModel(factory = viewModelFactory {
        initializer { ChatViewModel(container) }
    })
    val settingsVm: SettingsViewModel = viewModel(factory = viewModelFactory {
        initializer { SettingsViewModel(container) }
    })

    LaunchedEffect(Unit) {
        if (container.settingsFlow.value.keepAlive) {
            KeepAliveService.start(context)
        }
        com.haoai.agent.platform.DreamTriggerMonitor.init(context)
        // 4.2 内置浏览器：建 INVISIBLE 宿主（detached WebView 不执行 loadUrl，
        // 必须挂 window 才能加载；UI 打开时 swap 到可见容器）
        (context as? android.app.Activity)?.let {
            com.haoai.agent.agent.browser.BrowserController.bindActivity(it)
        }
    }

    // 全局共享：液态玻璃采样层。壁纸默认只用于聊天界面（screen==0），设置里可切换全局应用
    val wpVersion by com.haoai.agent.platform.WallpaperStore.changes.collectAsState()
    androidx.compose.runtime.remember(wpVersion) { wallpaper }
    var screen by rememberSaveable { mutableIntStateOf(0) }
    // 虚拟屏全屏查看页展开态（迷你窗 ⤢ 展开 / 全屏页 ↩ 收起），供两个槽位共享
    var vscreenFullOpen by rememberSaveable { mutableStateOf(false) }

    // debug deep link 路由：adb shell am start -a android.intent.action.VIEW -d "haoai://debug/<target>"
    // 验证直达（跳过导航点击）。仅响应 host=debug（Manifest intent-filter 限定）
    // 来源有二：冷启动 getIntent（LaunchedEffect 初值）+ 运行中 onNewIntent（deepLinkFlow）
    val rootScope = androidx.compose.runtime.rememberCoroutineScope()
    val act = context as? android.app.Activity
    // 抽屉状态提升：设置页返回时可恢复「侧边栏呼出」的来源状态
    // （v0.18.1：声明提前到深链路由之前，供 leaveChat/enterChat 统一冻结入口使用）
    val drawer = remember { com.haoai.agent.ui.chat.DrawerController() }
    // v0.18.1 转场冻结统一入口：凡「离开聊天页」的切页，先把聊天页 frozen=true
    // （退出层变静态快照纹理，动画期间不再每帧实时重绘整页），再改 screen。
    // parallax=true 仅侧边栏→设置：退出层走 1/3 视差+缩放+淡出；其余保持全宽直线。
    // 返回聊天（screen→0）时由各自逻辑解除冻结。
    fun leaveChat(parallax: Boolean = false) {
        if (screen == 0) {
            drawer.frozen = true
            drawer.frozenParallax = parallax
        }
    }
    fun enterChat() {
        drawer.frozen = false
        drawer.frozenParallax = false
    }
    suspend fun consumeDeepLink(uri: android.net.Uri) {
        if (uri.host != "debug") return
        // 调试路由（bash/ask/vscreen 等）仅 debug 构建生效：release 下任意
        // app/网页投递的 haoai://debug/* 一律忽略，防跨应用注入与特权命令。
        val debuggable =
            (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!debuggable) return
        val target = uri.lastPathSegment ?: ""
        when (target) {
            "chat" -> { enterChat(); screen = 0 }
            "settings" -> { leaveChat(); screen = 1 }
            "memory" -> { leaveChat(); screen = 2 }
            "schedules" -> { leaveChat(); screen = 3 }
            "sessions" -> { leaveChat(); screen = 4 }
            "skills" -> { leaveChat(); screen = 5 }
            "mcp" -> { leaveChat(); screen = 6 }
            "browser" -> { leaveChat(); screen = 7 }
            "workflows" -> { leaveChat(); screen = 8 }
            "search" -> { leaveChat(); screen = 17 }
            "searchcat" -> { leaveChat(); screen = 18 }
            "vscreen" -> {
                enterChat()
                screen = 0
                rootScope.launch(kotlinx.coroutines.Dispatchers.Default) {
                    val appCtx = container.appContext
                    val pkg = uri.getQueryParameter("pkg") ?: "com.android.settings"
                    // 包名白名单：禁止把 query 原样拼进 shizuku/root 命令（命令注入）
                    if (!Regex("^[A-Za-z0-9._]+$").matches(pkg)) {
                        com.haoai.agent.platform.vdisplay.VirtualScreenController.debugLog(
                            "route: rejected invalid pkg"
                        )
                        return@launch
                    }
                    // 观察循环：重投递则强退设置重投；无帧自愈后重试；最终长观察帧管线
                    var attempt = 0
                    var launchedOk = false
                    while (attempt < 4 && !launchedOk) {
                        attempt++
                        com.haoai.agent.platform.vdisplay.VirtualScreenController.ensureDisplay(appCtx)
                            ?.let { e ->
                                com.haoai.agent.platform.vdisplay.VirtualScreenController.debugLog("route attempt $attempt: $e")
                                return@launch
                            }
                        val err = com.haoai.agent.platform.vdisplay.VirtualScreenController.launch(appCtx, pkg)
                        if (err == null) {
                            com.haoai.agent.platform.vdisplay.VirtualScreenController.debugLog("route: launched OK (attempt $attempt, frame=${com.haoai.agent.platform.vdisplay.VirtualScreenController.hasFrame()})")
                            launchedOk = true
                        } else {
                            com.haoai.agent.platform.vdisplay.VirtualScreenController.debugLog("route attempt $attempt: $err")
                            if (err.contains("正在主屏运行")) {
                                com.haoai.agent.platform.vdisplay.PrivilegedShell.refresh(appCtx)
                                val kill = if (com.haoai.agent.platform.vdisplay.PrivilegedShell.shizukuUsable())
                                    com.haoai.agent.platform.vdisplay.PrivilegedShell.shizukuExec("am force-stop $pkg")
                                else com.haoai.agent.platform.vdisplay.PrivilegedShell.rootExec("am force-stop $pkg")
                                com.haoai.agent.platform.vdisplay.VirtualScreenController.debugLog("route force-stopped $pkg: ${kill.ok}")
                            }
                            kotlinx.coroutines.delay(2500)
                        }
                    }
                    repeat(6) { i ->
                        kotlinx.coroutines.delay(2500)
                        val shot = com.haoai.agent.platform.vdisplay.VirtualScreenController.capture(960, 70)
                        com.haoai.agent.platform.vdisplay.VirtualScreenController.debugLog(
                            "route observe $i frame=${com.haoai.agent.platform.vdisplay.VirtualScreenController.hasFrame()} shot=${shot?.length ?: "null"}"
                        )
                    }
                }
            }
            "new" -> {
                enterChat()
                screen = 0
                chatVm.newSession()
            }
            "ask" -> {
                // 调试直达：haoai://debug/ask?text=...（URL 编码）——绕过 IME 注入直接派任务
                enterChat()
                screen = 0
                uri.getQueryParameter("text")?.takeIf { it.isNotBlank() }?.let { chatVm.send(it) }
            }
            "stop" -> {
                // 调试直达：haoai://debug/stop —— 取消当前会话正在跑的回合。
                // 存在的意义：停止后的收尾（任务视图恢复、runState 落盘、token 结账）
                // 是必须能自动化验证的路径，而 Compose 的停止键不暴露给 a11y 树、坐标点按不稳。
                chatVm.stop()
            }
            "resume" -> {
                // 调试直达：haoai://debug/resume —— 点恢复横幅上的「继续」。
                // 同 debug/stop：横幅按钮是 Compose 节点，坐标点按不可靠，而"被杀后续跑"
                // 这条路径（遗留 running 判中断 → 补未确认工具结果 → 续跑）必须能自动验证。
                chatVm.resumeRun()
            }
            "compact" -> {
                // 调试直达：haoai://debug/compact —— 手动压缩当前会话上下文。
                // 自动压缩要攒到半窗 token 才触发，设备上没法自然复现，而压缩的水位与
                // 计量字段（compactedTokensBefore、锚点作废）是必须能验证的路径。
                enterChat()
                screen = 0
                com.haoai.agent.ui.chat.SlashCommands.parse("/compact")?.let { (cmd, arg) ->
                    rootScope.launch { chatVm.handleSlashCommand(cmd, arg) {} }
                }
            }
            "followdump" -> {
                // 导出跟随判定的**内存**轨迹（滚动期不落盘，避免主线程 IO 卡顿）：
                //   adb shell am start -d "haoai://debug/followdump" com.haoai.agent
                //   adb shell run-as com.haoai.agent cat files/follow-probe.txt
                rootScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    val txt = com.haoai.agent.ui.chat.FollowTrace.dump()
                    runCatching {
                        java.io.File(container.appContext.filesDir, "follow-probe.txt")
                            .writeText(txt)
                    }
                }
            }
            "fakestream" -> {
                // 调试直达：本地合成流式输出（不走模型），用于可复现地调流式渲染与滚动跟随：
                //   adb shell am start -d "haoai://debug/fakestream?sec=22"
                enterChat()
                screen = 0
                chatVm.debugFakeStream(uri.getQueryParameter("sec")?.toIntOrNull() ?: 22)
            }
            "vsclose" -> {
                enterChat()
                screen = 0
                rootScope.launch(kotlinx.coroutines.Dispatchers.Default) {
                    com.haoai.agent.platform.vdisplay.VirtualScreenController.destroy()
                    com.haoai.agent.platform.vdisplay.VirtualScreenController.debugLog("route: vsclose done")
                }
            }
            "bashtool" -> {
                // BashTool 全路径自检（复现 Agent 的工具调用，非直连 ProotBackend）：
                //   adb shell am start -d "haoai://debug/bashtool?cmd=<urlencoded>&backend=auto|linux"
                // 结果落 files/bashtool-probe.txt
                enterChat()
                screen = 0
                rootScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    val ctx = container.appContext
                    val out = StringBuilder()
                    fun log(s: String) { out.append(s).append('\n') }
                    try {
                        val cmd = uri.getQueryParameter("cmd") ?: "uname -a; echo TOOL_OK"
                        val backendArg = uri.getQueryParameter("backend") ?: "auto"
                        val ws = container.workspace.current?.shellWorkdir()
                            ?: java.io.File(ctx.filesDir, "shell-home").apply { mkdirs() }
                        val tctx = com.haoai.agent.agent.tools.ToolContext(
                            backend = container.workspace.current,
                            shellDir = ws,
                            todoStore = com.haoai.agent.agent.tools.TodoStore(ctx.filesDir),
                            appFilesDir = ctx.filesDir,
                            appContext = ctx
                        )
                        val args = kotlinx.serialization.json.buildJsonObject {
                            put("command", kotlinx.serialization.json.JsonPrimitive(cmd))
                            put("timeout_ms", kotlinx.serialization.json.JsonPrimitive(120_000))
                            if (backendArg != "auto") put("backend", kotlinx.serialization.json.JsonPrimitive(backendArg))
                        }
                        log("[bt] shellDir=${ws.absolutePath} backend=$backendArg")
                        log("[bt] cmd=$cmd")
                        val r = com.haoai.agent.agent.tools.BashTool().run(args, tctx)
                        log("[bt] isError=${r.isError}")
                        log("[bt] output >>>")
                        log(r.content.take(3000))
                        log("[bt] <<< output")
                    } catch (e: Throwable) {
                        log("[bt] EXC ${e.javaClass.name}: ${e.message}")
                    }
                    java.io.File(ctx.filesDir, "bashtool-probe.txt").writeText(out.toString())
                    android.util.Log.w("BashToolProbe", out.toString())
                }
            }
            "sbox" -> {
                // 沙箱链路自检（app 进程内真实执行，结果落 files/sbox-probe.txt 供 adb 取回）：
                //   adb shell am start -a android.intent.action.VIEW -d "haoai://debug/sbox"
                enterChat()
                screen = 0
                rootScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    val ctx = container.appContext
                    val out = StringBuilder()
                    fun log(s: String) { out.append(s).append('\n') }
                    try {
                        val nativeDir = ctx.applicationInfo?.nativeLibraryDir
                        val ws = container.workspace.current?.shellWorkdir()
                            ?: java.io.File(ctx.filesDir, "shell-home").apply { mkdirs() }
                        log("[sbox] filesDir=${ctx.filesDir}")
                        log("[sbox] nativeDir=$nativeDir")
                        log("[sbox] workspace=${ws.absolutePath} exists=${ws.exists()} canRead=${ws.canRead()}")
                        val install = com.haoai.agent.platform.sandbox.Proot.ensureReady(ctx.filesDir, nativeDir)
                        log("[sbox] Proot.ensureReady=${if (install == null) "null（ABI 无打包二进制或 sha256 不符）" else "${install.abi} bin=${install.binary.absolutePath}"}")
                        val sb = run {
                            val want = uri.getQueryParameter("distro")
                            if (want.isNullOrBlank()) {
                                com.haoai.agent.platform.sandbox.SandboxEnv.resolve(ctx.filesDir, nativeDir, ws)
                            } else if (install == null) null else {
                                val root = java.io.File(ctx.filesDir, "proot/distros/$want/rootfs")
                                if (root.exists()) com.haoai.agent.platform.sandbox.SandboxEnv.Sandbox(install, root, want, ws) else null
                            }
                        }
                        if (sb == null) {
                            log("[sbox] SandboxEnv.resolve=null（沙箱不可用）")
                        } else {
                            log("[sbox] resolve ok: distro=${sb.distroId} rootfs=${sb.rootfs.absolutePath}")
                            val argv = sb.buildArgs("echo SBOX_OK; uname -a; pwd; id")
                            log("[sbox] argv=${argv.joinToString(" ")}")
                            val env = com.haoai.agent.platform.sandbox.Proot.environmentOf(sb.install)
                                .plus(mapOf("PATH" to "/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin", "HOME" to "/root", "TMPDIR" to "/tmp"))
                            log("[sbox] env=$env")
                            val t0 = System.currentTimeMillis()
                            // 带 -v 详细跟踪：保留尾部 12000 字符（proot 每 syscall 一行，前段会刷屏）
                            val vArgv = sb.buildArgs("-v").toMutableList()
                            // buildArgs 末尾是 [haoai-env, /bin/sh, -c, cmd]，把 -v 插到 proot 选项区（index 1 之后）
                            vArgv.removeAt(vArgv.size - 1)
                            vArgv.add("apt-get --version | head -1; dpkg --version | head -1; uname -m; echo PROD_OK")
                            vArgv.add(1, "3")
                            vArgv.add(1, "-v")
                            val pb = ProcessBuilder(vArgv).redirectErrorStream(true)
                            pb.directory(sb.install.libDir)
                            pb.environment().apply { clear(); for ((k, v) in env) put(k, v) }
                            val p = pb.start()
                            val done = java.util.concurrent.CountDownLatch(1)
                            val rawOut = StringBuilder()
                            Thread {
                                runCatching {
                                    p.inputStream.bufferedReader().forEachLine {
                                        rawOut.appendLine(it)
                                        // 尾部缓冲：超 12000 就从头部砍 4000，保证看到最后发生的 syscall
                                        if (rawOut.length > 12000) rawOut.delete(0, 4000)
                                    }
                                }
                                done.countDown()
                            }.apply { isDaemon = true }.start()
                            val fin = done.await(60, java.util.concurrent.TimeUnit.SECONDS)
                            val alive = p.isAlive
                            if (alive) { p.destroy(); if (!p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly() }
                            log("[sbox] raw exec: finished=$fin stillAlive=$alive exit=${runCatching { p.exitValue() }.getOrNull()} dur=${System.currentTimeMillis() - t0}ms")
                            log("[sbox] raw output >>>")
                            log(rawOut.toString().ifBlank { "(空)" })
                            log("[sbox] <<< raw output end")

                            // 生产路径验证：ProotBackend.exec（含一次性进程模型 + 异步读）
                            runCatching {
                                val be = com.haoai.agent.agent.tools.shell.ProotBackend.forSandbox(sb)
                                val r = be.exec("uname -m; echo PROD_OK", 90_000)
                                log("[sbox] ProotBackend.exec exit=${r.exitCode} dur=${r.durationMs}ms out=${r.output.trim().replace("\n", " | ")}")
                            }.onFailure { log("[sbox] ProotBackend 异常 ${it.javaClass.simpleName}: ${it.message}") }

                            // apt 端到端（用户最关心的链路）：二进制可用 → 索引 → 装包 → 运行
                            if (uri.getQueryParameter("apt") != null) {
                                runCatching {
                                    val be = com.haoai.agent.agent.tools.shell.ProotBackend.forSandbox(sb)
                                    val r0 = be.exec("dpkg --configure -a >/dev/null 2>&1; apt-get --version | head -1; dpkg --version | head -1", 180_000)
                                    log("[apt] bin exit=${r0.exitCode} dur=${r0.durationMs}ms out=${r0.output.trim().replace("\n", " | ")}")
                                    val r1 = be.exec("DEBIAN_FRONTEND=noninteractive apt-get update -qq 2>&1 | tail -2; echo UPDATE_DONE", 600_000)
                                    log("[apt] update exit=${r1.exitCode} dur=${r1.durationMs}ms out=${r1.output.trim().take(600)}")
                                    val r2 = be.exec("DEBIAN_FRONTEND=noninteractive apt-get install -y -qq hello 2>&1 | tail -3; echo INSTALL_DONE; hello", 600_000)
                                    log("[apt] install exit=${r2.exitCode} dur=${r2.durationMs}ms")
                                    log("[apt] install out >>>"); log(r2.output.trim().take(1500)); log("[apt] <<< install out")
                                }.onFailure { log("[apt] 异常 ${it.javaClass.simpleName}: ${it.message}") }
                            }

                            // —— 配置矩阵：定位 fork ENOSYS 由哪个开关导致 ——
                            // 每个配置跑同一组命令：内建(echo) + 需 fork 的外部程序(uname)
                            data class Cfg(val name: String, val fakeRoot: Boolean, val noSeccomp: Boolean, val extra: List<String>)
                            val cfgs = listOf(
                                Cfg("base(-0,NoSec)", true, true, emptyList()),
                                Cfg("noNoSeccomp", true, false, emptyList()),
                                Cfg("noFakeRoot", false, true, emptyList()),
                                Cfg("noFakeRoot+noNoSeccomp", false, false, emptyList()),
                                Cfg("sysvipc", true, true, listOf("--sysvipc")),
                                Cfg("nf+ns+sysvipc", false, false, listOf("--sysvipc")),
                                // proot-distro 实际用法：不用 -0，改用真实 uid 映射 --change-id=0:0
                                Cfg("change-id=0:0", false, false, listOf("--change-id=0:0")),
                                Cfg("change-id=0:0+sysvipc", false, false, listOf("--change-id=0:0", "--sysvipc"))
                            )
                            for (cfg in cfgs) {
                                try {
                                    val argv = com.haoai.agent.platform.sandbox.Proot.buildCommand(
                                        sb.install, sb.rootfs, "echo BUILTIN_OK; uname -a; ls /; echo DONE",
                                        binds = listOf(
                                            "/storage/emulated/0/Android/data/com.haoai.agent/files/workspace" to "/workspace",
                                            "/dev" to "/dev", "/proc" to "/proc", "/sys" to "/sys"
                                        ),
                                        workdir = "/workspace",
                                        envWrapper = true,
                                        fakeRoot = cfg.fakeRoot
                                    ).toMutableList()
                                    if (cfg.extra.isNotEmpty()) argv.addAll(2, cfg.extra)
                                    val env = com.haoai.agent.platform.sandbox.Proot.environmentOf(sb.install).toMutableMap()
                                    env["PATH"] = "/usr/local/bin:/usr/bin:/bin:/usr/sbin:/usr/bin"
                                    env["HOME"] = "/root"
                                    env["TMPDIR"] = "/tmp"
                                    if (!cfg.noSeccomp) env.remove("PROOT_NO_SECCOMP")
                                    val pb = ProcessBuilder(argv).redirectErrorStream(true)
                                    pb.directory(sb.install.libDir)
                                    pb.environment().apply { clear(); for ((k, v) in env) put(k, v) }
                                    val p = pb.start()
                                    val dl = java.util.concurrent.CountDownLatch(1)
                                    val o = StringBuilder()
                                    Thread {
                                        runCatching { p.inputStream.bufferedReader().forEachLine { if (o.length < 4000) o.appendLine(it) } }
                                        dl.countDown()
                                    }.apply { isDaemon = true }.start()
                                    val fin = dl.await(45, java.util.concurrent.TimeUnit.SECONDS)
                                    if (p.isAlive) { p.destroy(); if (!p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly() }
                                    log("[MX:${cfg.name}] finished=$fin exit=${runCatching { p.exitValue() }.getOrNull()} noSeccomp=${cfg.noSeccomp} fakeRoot=${cfg.fakeRoot} extra=${cfg.extra}")
                                    o.toString().trim().lines().take(6).forEach { log("    | $it") }
                                } catch (e: Throwable) {
                                    log("[MX:${cfg.name}] EXC ${e.javaClass.simpleName}: ${e.message}")
                                }
                            }
                        }
                    } catch (e: Throwable) {
                        log("[sbox] EXCEPTION ${e.javaClass.name}: ${e.message}")
                        log(e.stackTraceToString().take(3000))
                    }
                    java.io.File(ctx.filesDir, "sbox-probe.txt").writeText(out.toString())
                    android.util.Log.w("SboxProbe", out.toString())
                }
            }
        }
    }
    LaunchedEffect(Unit) {
        // 冷启动：intent 已带 data
        (act?.intent)?.data?.let { consumeDeepLink(it) }
        act?.intent = null
        // 运行中重投递：onNewIntent → flow
        (act as? MainActivity)?.deepLinkFlow?.collect { uri ->
            if (uri != null) {
                consumeDeepLink(uri)
                act.deepLinkFlow.value = null
            }
        }
    }
    // 系统分享接入（R2.2）：切到聊天页并转存到 ChatViewModel，ChatScreen 负责预填
    LaunchedEffect(Unit) {
        (act as? MainActivity)?.shareFlow?.collect { s ->
            if (s != null) {
                enterChat()
                screen = 0
                s.first?.let { chatVm.shareText.value = it }
                s.second?.let { chatVm.shareImageUri.value = it.toString() }
                act.shareFlow.value = null
            }
        }
    }
    // 运行时任务视图隐藏兜底恢复：进程上次被强杀时任务可能残留隐藏态，
    // 应用重新回到前台且 Agent 未在运行 → 恢复最近任务可见（防「任务藏起来了找不到」）
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            com.haoai.agent.platform.TaskVisibility.restoreIfIdle(
                context.applicationContext, chatVm.running.value
            )
        }
    }
    val settings by container.settingsFlow.collectAsState()
    // 无壁纸时的默认渐变底色随主题切换（暗色/AMOLED 下不再露浅绿）
    val darkBackdrop = when (settings.themeMode) {
        "dark" -> true
        "light" -> false
        else -> androidx.compose.foundation.isSystemInDarkTheme()
    }
    // 壁纸可见性：聊天页(0)或全局壁纸。注意转场期两层要分开处理——
    // 1) 壁纸 Image 层：从聊天页切走时延迟 400ms 释放（退出中的半透明玻璃
    //    聊天页背后需要壁纸，否则底色突变出现断裂竖条）
    // 2) backdrop 采样源：立即跟随 screen——进入页（设置等）的玻璃卡片
    //    要采样素底 backdrop；若采样壁纸 backdrop，滑入过程会透出清晰壁纸
    //    且 400ms 后 backdrop 切换时折射内容跳变（闪现壁纸帧，用户截图确认）
    val wallpaperOnScreenBase = settings.wallpaperGlobal || screen == 0
    var wallpaperImageVisible by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(wallpaperOnScreenBase)
    }
    androidx.compose.runtime.LaunchedEffect(wallpaperOnScreenBase) {
        if (wallpaperOnScreenBase) {
            wallpaperImageVisible = true
        } else {
            kotlinx.coroutines.delay(400)
            wallpaperImageVisible = false
        }
    }
    // 素底玻璃的底色跟随主题（动态取色时随 Material You 变化）：
    // 上浅下深两级 surface，玻璃 vibrancy 透出的即主题背景色
    val scheme = androidx.compose.material3.MaterialTheme.colorScheme
    // 转场纱幕：旧页淡出溶进的是「净色实底」（其根布局
    // background(colorScheme.background)），HaoAI 淡出溶进的是花壁纸
    // ——彩色图案残影。push 期间在壁纸上盖一层主题净色「纱」
    // （alpha 0.9 近实底，转场完缓退），转场瞬间借用同样的净底。
    // 只在 push（离开聊天/设置根）时升起：pop 是「揭开」语义，聊天页
    // 滑回时壁纸应同步回归——纱幕若在，会把落位的聊天页罩灰（实测确认）
    var lastScreen by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(screen)
    }
    var scrimLevel by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(0f)
    }
    // 转场落位标记：screen 变化后等 spring 收尾才更新。状态栏亮度采样
    // （PixelCopy GPU 回读）只挂这个键——转场起点不再被回读抢帧，
    // 落位后补一次采样即可
    var screenSettled by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(screen)
    }
    androidx.compose.runtime.LaunchedEffect(screen) {
        // 转场纱幕只为「有淡出参与」的转场服务（非聊天页之间的纵深转场，
        // 旧页 fadeOut 需要净色接住）。方向二后聊天页参与的转场是纯滑动、
        // 无任何淡出——若升纱幕，转场结束后的 450ms 缓退会在已落位的
        // 聊天页上呈现为「背景缓慢淡出」（用户反馈确认），故直接跳过
        val chatInvolved = screen == 0 || lastScreen == 0
        lastScreen = screen
        if (!chatInvolved) {
            scrimLevel = 0.9f
            try {
                kotlinx.coroutines.delay(420)
            } finally {
                // 取消兜底：快速连点返回（如 模型大脑→设置→聊天 两跳间隔 <420ms）时，
                // 本协程在 delay(420) 中被下一个 screen 转场取消，原「delay 后置 0」
                // 永远执行不到；而下一个转场若聊天参与会整体跳过纱幕逻辑，scrimLevel
                // 就永久停在 0.9——净色纱幕罩住聊天页（白遮罩 bug）。finally 保证
                // 任何取消路径都把纱幕落下
                scrimLevel = 0f
            }
        }
        kotlinx.coroutines.delay(100)   // 转场 spring 收尾
        screenSettled = screen
    }
    // 遗留3 修复（2026-09-22）：净色底玻璃"透过"的颜色必须与页面实际底色一致
    // （采样=所见）。此前用 surface/surfaceVariant 渐变，与壳的 .background(background) 不符
    val plainTop = scheme.background
    val plainBottom = scheme.background
    val wpBackdrop = com.haoai.agent.ui.common.rememberAppBackdrop(
        wallpaper, dark = darkBackdrop, baseTop = plainTop, baseBottom = plainBottom
    )
    // 设置系页面专用 backdrop：与 wpBackdrop 同为纯壁纸画布，但聊天页的
    // appLayer 挂在 wpBackdrop 上——其全部内容（含打开抽屉的磨砂面板）会
    // 实时画进该画布；设置页玻璃卡采样它时，抽屉停留过的左带区域呈
    // 「磨砂叠磨砂」双重模糊，转场后突然变浅（用户截图框选）。设置系
    // 页面用本实例采样，画布永远干净
    val settingsBackdrop = com.haoai.agent.ui.common.rememberAppBackdrop(
        wallpaper, dark = darkBackdrop, baseTop = plainTop, baseBottom = plainBottom
    )
    val plainBackdrop = com.haoai.agent.ui.common.rememberAppBackdrop(
        null, dark = darkBackdrop, baseTop = plainTop, baseBottom = plainBottom
    )
    // backdrop 分配（与各页实际背景严格对齐——采样=所见是玻璃不出错的铁律）：
    // - 聊天页(0)：壁纸层在它背后实时对位 → wpBackdrop
    // - 设置等二级页 + 全局壁纸开：页面根底自带来对齐的壁纸 Image（SettingsScreen
    //   内部绘制）→ settingsBackdrop（纯壁纸画布，聊天页 appLayer 不挂其上，
    //   转场期退出内容不会污染采样——samplingFrozen 降级机制已因此移除）
    // - 其余（全局关时的二级页）：净色底 → plainBackdrop
    val backdrop = if (wallpaper != null &&
        (screen == 0 || settings.wallpaperGlobal)
    ) {
        if (screen == 0) wpBackdrop else settingsBackdrop
    } else {
        plainBackdrop
    }
    // v0.18.1 修复：聊天页专用画布。转场期退出层聊天页的 appLayer 会把它收到的
    // backdrop 画布整页写入（frozen 快照首帧 record / 实时绘制都是），若此时它
    // 收到的是按 screen 计算的共享画布（settingsBackdrop/plainBackdrop），聊天
    // 消息与抽屉就会残留进设置系页面的采样源——设置页玻璃卡「透出聊天画面」
    // （用户截图确认）。聊天页无论进出转场一律只写自己的画布：壁纸在 → wpBackdrop；
    // 无壁纸 → 专用 chatPlainBackdrop（与二级页 plainBackdrop 内容相同但物理隔离）。
    val chatPlainBackdrop = com.haoai.agent.ui.common.rememberAppBackdrop(
        null, dark = darkBackdrop, baseTop = plainTop, baseBottom = plainBottom
    )
    val chatBackdrop = if (wallpaper != null) wpBackdrop else chatPlainBackdrop

    // 状态栏图标随顶部实际亮度自适应（修复：系统深色 + App 浅色时白图标看不见）
    // 采样键用 screenSettled 而非 screen：PixelCopy 是 GPU 回读，压在转场
    // 首帧上会抢帧；等页面落位后再补采样（AdaptiveStatusBarIcons 内部
    // 先按主题给初值，转场期间图标不会错色）
    com.haoai.agent.ui.common.AdaptiveStatusBarIcons(
        dark = darkBackdrop,
        sampleKey = "${darkBackdrop}|${settings.themeSeed}|${settings.dynamicColor}|" +
            "${settings.amoledMode}|$screenSettled|$wpVersion|${settings.wallpaperGlobal}"
    )

    // 4.2 呼出优化：无头工具触发浏览时自动弹出底部预览面板（两段式第一段，
    // 聊天不打断）；面板 🌐 才进全屏浏览器。可见容器才真实加载（平台约束）
    androidx.compose.runtime.LaunchedEffect(Unit) {
        com.haoai.agent.agent.browser.BrowserController.uiOpener = {
            com.haoai.agent.agent.browser.BrowserController.openPreview()
        }
    }
    // 聊天滚动状态提升到 RootApp（不随 screen 切换销毁），进设置再返回时保持位置。
    // B′（2026-09-19）：聊天列表已由 LazyColumn 改为 Column + verticalScroll，
    // 状态类型随之从 LazyListState 换成 ScrollState（Saver 同步换）。
    val chatListState = androidx.compose.runtime.saveable.rememberSaveable(
        saver = androidx.compose.foundation.ScrollState.Saver
    ) { androidx.compose.foundation.ScrollState(initial = 0) }

    // 设置主页滚动状态提升到 RootApp：进子页（独立 screen）会销毁重建设置
    // 组件，rememberSaveable 在 AnimatedContent 销毁分支不恢复（实测），
    // 提升到不随 screen 销毁的层级才能真正记住位置
    val settingsRootListState = androidx.compose.runtime.saveable.rememberSaveable(
        saver = androidx.compose.foundation.lazy.LazyListState.Saver
    ) { androidx.compose.foundation.lazy.LazyListState() }

    // 设置二级页状态提升：从设置子页进入管理页（记忆库等）后，返回时回到原子页而非设置根
    var settingsSection by rememberSaveable { mutableStateOf("") }

    Box(Modifier.fillMaxSize()) {
        // 壁纸 Image 层：延迟释放（转场期退出中的玻璃页需要它垫底）
        if (wallpaper != null && wallpaperImageVisible) {
            // v0.18.1：包装 remember 化——裸调每次重组分配新 ImageBitmap 触发整屏重绘
            val wpImage = remember(wallpaper) { wallpaper.asImageBitmap() }
            Image(
                bitmap = wpImage,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize()
            )
            // 转场纱幕：净色盖在壁纸上（页面层之下），旧页淡出时溶进净色
            // 而非花壁纸——丝滑的关键（底就是净色实底）
            // v0.18.1：动画收进子组合——旧实现 animateFloatAsState().value 在
            // RootApp 顶层解包，450ms 淡出期间每帧重组整个 RootApp（含 AnimatedContent
            // 全部页面）；现在只传离散 scrimLevel，逐帧 alpha 只重组这个 10 行的小组件
            TransitionScrim(
                level = scrimLevel,
                color = scheme.background,
                modifier = Modifier.matchParentSize()
            )
        }
        if (!settings.onboarded) {
            // 首启引导也走横屏内容列（s=-1：不命中浏览器豁免），5 步卡片不会被 873dp 拉开
            LandscapeWrap(s = -1) {
                OnboardingGlass(
                    backdrop = backdrop,
                    settingsVm = settingsVm,
                    onSave = { name, soul, perm -> chatVm.completeOnboarding(name, soul, perm) }
                )
            }
        } else {
            // 页面切换过渡（slide + scale + fade，有纵深感）：
            // push——新页全屏滑入；旧页缩小(1→0.92)+变暗淡出沉底（不是全 0.7，
            // HaoAI 旧页带着展开抽屉，缩太多会露出边缘）。pop 反向——旧页全速
            // 滑出，下层页从 0.92 迎上来放大回位。双向运动 = 流畅感来源。
            // zIndex：AnimatedContent 默认 target 在顶，pop 时必须显式把进入的
            // 聊天页压到 -1，否则聊天页（含抽屉 scrim）盖在设置页上洗灰。
            fun levelOf(s: Int) = screenDepth(s)
            val ease = androidx.compose.animation.core.FastOutSlowInEasing
            // spring 而非固定 tween：先快后缓的自然减速比匀速机械感更「丝滑」。
            // 刚度用 Medium（原 MediumLow）：全宽 1080px 位移下 MediumLow 收敛
            // 尾巴过长，后段每帧位移只剩亚像素级「蠕行」被读作不跟手/拖沓
            val slideSpec: androidx.compose.animation.core.FiniteAnimationSpec<androidx.compose.ui.unit.IntOffset> =
                androidx.compose.animation.core.spring(
                    dampingRatio = 0.9f,
                    stiffness = androidx.compose.animation.core.Spring.StiffnessMedium,
                    visibilityThreshold = androidx.compose.ui.unit.IntOffset(1, 1)
                )
            val fadeSpec: androidx.compose.animation.core.FiniteAnimationSpec<Float> =
                androidx.compose.animation.core.tween(durationMillis = 300, easing = ease)
            val scaleSpec: androidx.compose.animation.core.FiniteAnimationSpec<Float> =
                androidx.compose.animation.core.spring(
                    dampingRatio = 0.9f,
                    stiffness = androidx.compose.animation.core.Spring.StiffnessMedium,
                    visibilityThreshold = 0.001f
                )
            androidx.compose.animation.AnimatedContent(
                targetState = screen,
                transitionSpec = {
                    val from = levelOf(initialState)
                    val to = levelOf(targetState)
                    // 方向判定（v0.18.2）：深度更高 = push，更低 = pop；
                    // 同深（理论不存在）按编号兜底，保证任何一对页面方向确定。
                    // 修复：记忆库(2,深度3)↔记忆与梦境(11,深度2) 旧版同层双向同动画
                    val isPush = to > from || (to == from && targetState > initialState)
                    // 方向二：聊天页(0)参与的转场用纯滑动——聊天页不缩放不淡出
                    // （重页面最简单的运动最不容易露馅：缩放+淡出会放大重组延迟
                    // 的可见性，直线滑动则完全掩盖）。仅非聊天页之间保留
                    // slide+scale+fade 纵深（用户确认设置↔技能库观感好）
                    val chatInvolved = initialState == 0 || targetState == 0
                    when {
                        // push（聊天→设置等）：设置页全速滑入盖上来；
                        // 聊天页退出（v0.18.1：所有离开聊天页路径统一 frozen 快照，
                        // 退出层是静态纹理，动画成本≈0）：
                        // - 侧边栏→设置（frozenParallax）：纵深视差——1/3 滑距+缩小+
                        //   淡出，静态纹理上做这些是纯 GPU 合成
                        // - 其他路径：保持全宽直线滑出（与旧版实时滑出观感一致）
                        isPush -> {
                            (androidx.compose.animation.slideInHorizontally(slideSpec) { it })
                                .togetherWith(
                                if (chatInvolved) {
                                    if (drawer.frozenParallax) {
                                        androidx.compose.animation.slideOutHorizontally(slideSpec) { -it / 3 } +
                                            androidx.compose.animation.scaleOut(
                                                targetScale = 0.92f, animationSpec = scaleSpec
                                            ) +
                                            androidx.compose.animation.fadeOut(fadeSpec)
                                    } else {
                                        androidx.compose.animation.slideOutHorizontally(slideSpec) { -it }
                                    }
                                } else {
                                    androidx.compose.animation.slideOutHorizontally(slideSpec) { -it / 3 } +
                                        androidx.compose.animation.scaleOut(
                                            targetScale = 0.92f, animationSpec = scaleSpec
                                        ) +
                                        androidx.compose.animation.fadeOut(fadeSpec)
                                }
                            ).apply { targetContentZIndex = 1f }
                        }
                        // pop（返回聊天 / 返回设置）：上层页全速右滑出；
                        // 非聊天下层页从 0.92 迎上来放大回位（双向运动）；
                        // 聊天页则直线滑回（重页面不做缩放，快照+直线最稳）
                        else -> {
                            (androidx.compose.animation.slideInHorizontally(slideSpec) { if (chatInvolved) -it / 4 else -it / 5 } +
                                if (chatInvolved) {
                                    // 聊天页直线滑回：不加 scale/fade（重页面纯滑动最稳）
                                    androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(1))
                                } else {
                                    androidx.compose.animation.scaleIn(
                                        initialScale = 0.92f, animationSpec = scaleSpec
                                    ) +
                                    androidx.compose.animation.fadeIn(fadeSpec)
                                })
                                .togetherWith(
                                androidx.compose.animation.slideOutHorizontally(slideSpec) { it }
                            ).apply { targetContentZIndex = -1f }
                        }
                    }
                },
                label = "screenSwitch"
            ) { s ->
            // zIndex 已由 transitionSpec 的 targetContentZIndex 声明（内容重组层
            // 的 Modifier.zIndex 不参与 AnimatedContent 的 z 排序，是死代码），
            // 页面直接平铺
            // 横屏：整页限宽居中（一处覆盖 0~18 全部屏；浏览器/抽屉可见时豁免）
            LandscapeWrap(s = s, drawerOpen = s == 0 && (drawer.drawerVisible || drawer.frozen)) {
            when (s) {
                1 -> SettingsScreen(
                    vm = settingsVm,
                    backdrop = backdrop,
                    // 全局壁纸开时设置页自带对齐的壁纸底（页面自己的 Image 层，
                    // 随页面整体滑动）——「壁纸应用于所有页面」真正生效
                    wallpaper = if (settings.wallpaperGlobal) wallpaper else null,
                    rootListState = settingsRootListState,
                    initialSection = settingsSection,
                    onSectionChange = { settingsSection = it },
                    // 从设置返回聊天：恢复抽屉展开态（与预览方案一致——返回后侧边栏在）。
                    // snapOpen 无动画瞬位，与 pop 过渡同帧；聊天页带展开抽屉一起视差回来。
                    // 同时解除冻结：返回后聊天页恢复实时绘制
                    onBack = {
                        enterChat()
                        screen = 0
                        // 复位快照标志：返回后抽屉开着（drawerVisible），若沿用设置期
                        // 录的旧快照会缺顶栏/输入框；复位强制重录当前真实画面
                        drawer.snapshotFresh = false
                        rootScope.launch { drawer.snapOpen() }
                    },
                    onOpenMemories = { screen = 2 },
                    onOpenSchedules = { screen = 3 },
                    onOpenSkills = { screen = 5 },
                    onOpenMcp = { screen = 6 },
                    onOpenWorkflows = { screen = 8 },
                    // 独立 screen 化的 section 子页：与技能库/MCP/工作流同机制
                    // （转场动画 + 全局壁纸），消除「瞬切无动画」的不一致
                    onOpenSection = { settingsSection = ""; screen = it }
                )
                // ---- 设置 section 子页独立 screen（9-16）：共用 SettingsScreen
                // lockedSection 渲染，转场/壁纸/返回与技能库等完全一致 ----
                9 -> com.haoai.agent.ui.settings.SettingsScreen(
                    vm = settingsVm, backdrop = backdrop,
                    wallpaper = if (settings.wallpaperGlobal) wallpaper else null,
                    onBack = { screen = 1 },
                    lockedSection = "brain", onSectionBack = { screen = 1 }
                )
                10 -> com.haoai.agent.ui.settings.SettingsScreen(
                    vm = settingsVm, backdrop = backdrop,
                    wallpaper = if (settings.wallpaperGlobal) wallpaper else null,
                    onBack = { screen = 1 },
                    lockedSection = "privacy", onSectionBack = { screen = 1 }
                )
                11 -> com.haoai.agent.ui.settings.SettingsScreen(
                    vm = settingsVm, backdrop = backdrop,
                    wallpaper = if (settings.wallpaperGlobal) wallpaper else null,
                    onBack = { screen = 1 },
                    lockedSection = "memory", onSectionBack = { screen = 1 },
                    onOpenMemories = { screen = 2 }
                )
                12 -> com.haoai.agent.ui.settings.SettingsScreen(
                    vm = settingsVm, backdrop = backdrop,
                    wallpaper = if (settings.wallpaperGlobal) wallpaper else null,
                    onBack = { screen = 1 },
                    lockedSection = "linux", onSectionBack = { screen = 1 }
                )
                13 -> com.haoai.agent.ui.settings.SettingsScreen(
                    vm = settingsVm, backdrop = backdrop,
                    wallpaper = if (settings.wallpaperGlobal) wallpaper else null,
                    onBack = { screen = 1 },
                    lockedSection = "workspace", onSectionBack = { screen = 1 }
                )
                14 -> com.haoai.agent.ui.settings.SettingsScreen(
                    vm = settingsVm, backdrop = backdrop,
                    wallpaper = if (settings.wallpaperGlobal) wallpaper else null,
                    onBack = { screen = 1 },
                    lockedSection = "general", onSectionBack = { screen = 1 }
                )
                15 -> com.haoai.agent.ui.settings.SettingsScreen(
                    vm = settingsVm, backdrop = backdrop,
                    wallpaper = if (settings.wallpaperGlobal) wallpaper else null,
                    onBack = { screen = 1 },
                    lockedSection = "about", onSectionBack = { screen = 1 }
                )
                16 -> com.haoai.agent.ui.settings.SettingsScreen(
                    vm = settingsVm, backdrop = backdrop,
                    wallpaper = if (settings.wallpaperGlobal) wallpaper else null,
                    onBack = { screen = 1 },
                    lockedSection = "usage", onSectionBack = { screen = 1 }
                )
                17 -> com.haoai.agent.ui.settings.SettingsScreen(
                    vm = settingsVm, backdrop = backdrop,
                    wallpaper = if (settings.wallpaperGlobal) wallpaper else null,
                    onBack = { screen = 1 },
                    lockedSection = "search", onSectionBack = { screen = 1 },
                    onOpenSearchCatalog = { screen = 18 }
                )
                // 搜索服务目录页：从「搜索服务」进来，返回要回它而不是设置主页
                18 -> com.haoai.agent.ui.settings.SettingsScreen(
                    vm = settingsVm, backdrop = backdrop,
                    wallpaper = if (settings.wallpaperGlobal) wallpaper else null,
                    onBack = { screen = 17 },
                    lockedSection = "searchcat", onSectionBack = { screen = 17 }
                )
                2 -> com.haoai.agent.ui.manage.MemoryScreen(
                    backdrop = backdrop,
                    // 记忆库唯一入口是「记忆与梦境」页，返回上一级=回该页
                    // （此前回设置主页，用户反馈「不是返回上一级」）
                    onBack = { screen = 11 },
                    wallpaper = if (settings.wallpaperGlobal) wallpaper else null
                )
                3 -> com.haoai.agent.ui.manage.ScheduleScreen(
                    backdrop = backdrop,
                    onBack = { screen = 1 },
                    wallpaper = if (settings.wallpaperGlobal) wallpaper else null
                )
                5 -> com.haoai.agent.ui.manage.SkillsScreen(
                    backdrop = backdrop,
                    onBack = { screen = 1 },
                    wallpaper = if (settings.wallpaperGlobal) wallpaper else null,
                    onLearnFromHistory = {
                        // 回到聊天开复盘任务：过程在会话里可见（可插话/停止），产出走技能候选态
                        chatVm.learnFromHistory()
                        enterChat()
                        screen = 0
                    }
                )
                6 -> com.haoai.agent.ui.settings.McpSettingsScreen(
                    backdrop = backdrop,
                    onBack = { screen = 1 },
                    wallpaper = if (settings.wallpaperGlobal) wallpaper else null
                )
                8 -> com.haoai.agent.ui.manage.WorkflowScreen(
                    container = container,
                    backdrop = backdrop,
                    onBack = { screen = 1 },
                    wallpaper = if (settings.wallpaperGlobal) wallpaper else null
                )
                4 -> com.haoai.agent.ui.sessions.SessionsScreen(
                    vm = chatVm,
                    backdrop = backdrop,
                    wallpaper = if (settings.wallpaperGlobal) wallpaper else null,
                    // 全部会话只能从侧边栏进入：返回（箭头/系统手势）回到聊天并重新展开侧边栏
                    onBack = {
                        enterChat()
                        screen = 0
                        rootScope.launch { drawer.open() }
                    }
                )
                // 4.2 内置浏览器：与工具共用 BrowserController WebView 池
                7 -> com.haoai.agent.ui.browser.BrowserScreen(
                    backdrop = backdrop,
                    onBack = { enterChat(); screen = 0 }
                )
                else -> ChatScreen(
                    vm = chatVm,
                    // v0.18.1：聊天页固定采样自己的画布（chatBackdrop），转场退出层
                    // 的 appLayer 写入不再触及设置系页面共享的 settingsBackdrop/
                    // plainBackdrop——修「设置页玻璃透出聊天画面」
                    backdrop = chatBackdrop,
                    drawer = drawer,
                    scrollState = chatListState,
                    // 侧边栏点设置：push 转场开始——先冻结聊天页（后续帧绘制
                    // 静态快照，重页面组合延迟不再被看穿），切页触发转场。
                    // parallax=true：此路径退出层保留 1/3 视差+缩放+淡出纵深
                    onOpenSettings = {
                        leaveChat(parallax = true)
                        screen = 1
                    },
                    // 先收起抽屉再切页：否则返回时 drawerState 仍是 Open，抽屉会原样展开。
                    // 此路径不冻结——退出动画期间抽屉正在关闭，冻结会把抽屉
                    // 「定格在展开态」滑出（观感改变）；且该入口低频，保持实时绘制
                    onOpenSessions = {
                        rootScope.launch { drawer.close() }
                        screen = 4
                    },
                    // 顶栏 🌐 单击：唤出悬浮预览窗（方案 A）；池里没页面时退回全屏
                    onOpenBrowser = {
                        val hasPage = com.haoai.agent.agent.browser.BrowserController.tabCount() > 0
                        if (hasPage) {
                            com.haoai.agent.agent.browser.BrowserController.previewOpen.value = true
                        } else {
                            leaveChat()
                            com.haoai.agent.agent.browser.BrowserController.previewOpen.value = false
                            screen = 7
                        }
                    },
                    // 顶栏 🌐 长按：直接进全屏浏览器
                    onOpenBrowserFullscreen = {
                        leaveChat()
                        com.haoai.agent.agent.browser.BrowserController.previewOpen.value = false
                        screen = 7
                    },
                    // 虚拟屏：迷你窗画在采样层内（抽屉可透视）；全屏查看页画在顶栏之上
                    vscreenMini = {
                        val vscreenOpen by com.haoai.agent.platform.vdisplay.VirtualScreenController.previewOpen.collectAsState()
                        if (vscreenOpen) {
                            com.haoai.agent.ui.browser.VScreenMiniPanel(
                                onExpand = { vscreenFullOpen = true },
                                onClose = { com.haoai.agent.platform.vdisplay.VirtualScreenController.closePreview() }
                            )
                        }
                    },
                    vscreenFull = {
                        val vscreenOpen by com.haoai.agent.platform.vdisplay.VirtualScreenController.previewOpen.collectAsState()
                        if (vscreenOpen && vscreenFullOpen) {
                            com.haoai.agent.ui.browser.VScreenFullPanel(
                                onCollapse = { vscreenFullOpen = false }
                            )
                        }
                    },
                    browserPreview = {
                        val previewOpen by com.haoai.agent.agent.browser.BrowserController.previewOpen.collectAsState()
                        if (previewOpen) {
                            com.haoai.agent.ui.browser.BrowserPreviewPanel(
                                backdrop = chatBackdrop,
                                onClose = { com.haoai.agent.agent.browser.BrowserController.previewOpen.value = false },
                                onFullscreen = {
                                    com.haoai.agent.agent.browser.BrowserController.previewOpen.value = false
                                    screen = 7
                                }
                            )
                        }
                    },
                    onOpenVscreen = {
                        com.haoai.agent.platform.vdisplay.VirtualScreenController.openPreview()
                    }
                )
            }
            }
            } // AnimatedContent content lambda
            // 4.2 预览浮层已移入 ChatScreen（browserPreview 槽位）：保证它画在
            // 侧边栏抽屉之下（抽屉打开时盖住它），且只在聊天页存在。
            // 4.3 虚拟屏预览浮层已移入 ChatScreen（vscreenPreview 槽位）：与浏览器
            // 同样画在抽屉之下、仅聊天页存在。
        }
    }
}


/**
 * 横屏内容列：把整页内容限宽居中，行内元素不再横跨整屏。
 *
 * 横屏基线（2026-09-26，模拟器 1080×2400 转横屏 ≈ 873×393dp）：设置页等列表卡直接铺满
 * ~873dp，图标/标题/状态胶囊/箭头被拉开几百 dp，中间全是空白；而可视高度只剩 393dp，
 * 一屏 5 行，信息密度反而更差。限到 620dp 阅读列宽并居中，两侧用页面同色底铺住——
 * 不能让开了壁纸的页面在两侧露出两条异色带。
 *
 * 豁免浏览器（s=7）：网页该满屏。
 */
@Composable
private fun LandscapeWrap(s: Int, drawerOpen: Boolean = false, content: @Composable () -> Unit) {
    // 浏览器满屏；聊天屏且抽屉可见时不收窄——抽屉是"返回聊天时自动恢复打开"的（snapOpen），
    // 收窄后内容列会压在抽屉上（真机截图实测）。
    // 注意 drawerOpen 必须由调用方限定 s==0：leaveChat() 一离开聊天就把 drawer.frozen 置真，
    // 不限定就会让豁免命中所有屏（实测记忆/会话/MCP 全部退回满宽）
    if (s == 7 || drawerOpen) {
        content()
        return
    }
    // 直接读进来的约束宽高，不读 Configuration.orientation：
    // 实测 dumpsys 的 orientation 字段与画面矛盾过（报竖屏、窗口却 2400 宽），
    // 而"宽 > 高"正是布局真正关心的量
    androidx.compose.foundation.layout.BoxWithConstraints(
        Modifier.fillMaxSize()
    ) {
        if (maxWidth <= maxHeight) {
            content()
        } else {
            // 620dp 阅读列宽：放得下「图标 + 标题 + 两枚状态胶囊 + 箭头」而不显拉胯
            val pad = ((maxWidth.value - 620f) / 2f).coerceIn(16f, 260f).dp
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
            ) {
                // 内层 clipToBounds 是关键：抽屉面板关闭时按**父容器**宽度平移（-panelWidth，
                // ChatScreen.kt:436 fillMaxWidth(0.72f)），列比屏幕窄时它会溢到列外的留白里
                // （真机截图：左缘一条被裁的会话列表）。竖屏列=整屏所以从来没暴露过。
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = pad)
                        .clipToBounds()
                ) { content() }
            }
        }
    }
}


/**
 * 转场纱幕：盖在壁纸上的主题净色层。level>0 立即升起（tween 0），归零时 450ms
 * 缓退。动画订阅只发生在本组件内——父组合只传离散 level，淡出逐帧不再重组页面树。
 */
@Composable
private fun TransitionScrim(level: Float, color: Color, modifier: Modifier = Modifier) {
    val alpha = androidx.compose.animation.core.animateFloatAsState(
        targetValue = level,
        animationSpec = androidx.compose.animation.core.tween(
            durationMillis = if (level > 0f) 0 else 450,
            easing = androidx.compose.animation.core.FastOutSlowInEasing
        ),
        label = "transitionScrim"
    ).value
    if (alpha > 0.01f) {
        Box(
            modifier
                .background(color.copy(alpha = alpha))
        )
    }
}

@Composable
private fun OnboardingGlass(
    backdrop: com.kyant.backdrop.backdrops.LayerBackdrop,
    settingsVm: SettingsViewModel,
    onSave: (String, String, com.haoai.agent.agent.policy.PermissionMode) -> Unit
) {
    var step by remember { mutableIntStateOf(0) }
    // ── 步 0：起名 ──
    var name by remember { mutableStateOf("") }
    // ── 步 1：性格（预设风格卡 + 自定义/🎲随机，可微调）──
    // 默认选中第一张风格卡（与定稿效果图一致：打开即见预览窗、无自定义输入框）
    var personaKey by remember {
        mutableStateOf<String?>(
            com.haoai.agent.ui.onboarding.PersonaPresets.gridPresets.first().key
        )
    }
    var personaCustom by remember { mutableStateOf(false) }
    var customText by remember { mutableStateOf("") }
    var tunedText by remember { mutableStateOf<String?>(null) }
    var tuning by remember { mutableStateOf(false) }
    // 🎲 随机人格（12 款风格池，不重复上一款；chip 只显风格不显代号）
    var rolledTag by remember { mutableStateOf<String?>(null) }
    var lastRollIdx by remember { mutableIntStateOf(-1) }
    // ── 步 2：权限模式（推荐全自动）──
    var perm by remember { mutableStateOf(com.haoai.agent.agent.policy.PermissionMode.YOLO) }
    // ── 步 3：接大脑（内嵌迷你供应商向导，复用 SettingsViewModel 的 draft 管线）──
    var brainStage by remember { mutableIntStateOf(0) }
    var brainPicked by remember { mutableStateOf<com.haoai.agent.ui.ProviderPreset?>(null) }
    var brainSkipped by remember { mutableStateOf(false) }
    var brainEcho by remember { mutableStateOf("") }
    var manualModel by remember { mutableStateOf("") }
    val draft = settingsVm.draft

    fun soulText(): String {
        if (personaCustom) return customText.trim()
        val p = com.haoai.agent.ui.onboarding.PersonaPresets.all.firstOrNull { it.key == personaKey }
            ?: return ""
        return tunedText ?: p.fullText
    }

    /** 🎲 从 12 款风格池随机抽一款填入自定义区（不重复上一款）。 */
    fun rollPersona() {
        val pool = com.haoai.agent.ui.onboarding.PersonaPresets.all
        var i: Int
        do {
            i = kotlin.random.Random.nextInt(pool.size)
        } while (pool.size > 1 && i == lastRollIdx)
        lastRollIdx = i
        val p = pool[i]
        personaKey = null
        personaCustom = true
        customText = p.fullText
        rolledTag = p.tag
    }

    // 完成清单回显：一律用风格标签，不出现内部代号（名字第一步已定）
    fun soulEcho(): String = when {
        personaCustom -> "自定义" + when {
            rolledTag != null -> " · 抽中 $rolledTag"
            customText.isNotBlank() -> " · " + customText.take(12) + "…"
            else -> ""
        }
        tunedText != null -> com.haoai.agent.ui.onboarding.PersonaPresets.all
            .firstOrNull { it.key == personaKey }?.tag?.let { "$it（已微调）" } ?: "已微调"
        personaKey != null -> com.haoai.agent.ui.onboarding.PersonaPresets.all
            .firstOrNull { it.key == personaKey }?.tag.orEmpty().ifBlank { "跳过了，之后可配" }
        else -> "跳过了，之后可配"
    }

    Box(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .imePadding(),
        // v3③：顶对齐 + 固定边距——进度条/「第 N 步」在任何一步都停在同一像素，
        // 不再随各步内容高度上下浮动（原整体垂直居中是跳动根因）
        contentAlignment = Alignment.TopCenter
    ) {
        // 首启引导 v2：5 步（欢迎 → 性格 → 权限 → 大脑 → 完成），液态玻璃卡悬浮在壁纸之上
        GlassPanel(
            backdrop = backdrop,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp, vertical = 18.dp),
            radius = 30.dp,
            surfaceAlpha = 0.36f
        ) {
            Column(Modifier.padding(horizontal = 22.dp, vertical = 22.dp)) {
                // ── 时间线：纯进度点（无文字标签——起名步的「欢迎」痕迹不再出现，
                //    名字已在步 0 定过，界面只呈现当前步内容）──
                val tlLabels = listOf("欢迎", "性格", "权限", "大脑", "完成")
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    tlLabels.indices.forEach { i ->
                        Box(
                            Modifier
                                .size(22.dp)
                                .clip(androidx.compose.foundation.shape.CircleShape)
                                .background(
                                    when {
                                        i == step -> MaterialTheme.colorScheme.primary
                                        i < step -> MaterialTheme.colorScheme.primary.copy(alpha = 0.30f)
                                        else -> MaterialTheme.colorScheme.onBackground.copy(alpha = 0.10f)
                                    }
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                if (i < step) "✓" else "${i + 1}",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (i <= step) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                            )
                        }
                        if (i < tlLabels.lastIndex) {
                            Box(
                                Modifier
                                    .weight(1f)
                                    .height(2.dp)
                                    .background(
                                        MaterialTheme.colorScheme.onBackground.copy(alpha = 0.10f)
                                    )
                                    .align(Alignment.CenterVertically)
                            )
                        }
                    }
                }
                val stepLabel = tlLabels.getOrElse(step) { tlLabels.last() }
                Text(
                    "第 ${step + 1} 步 · $stepLabel",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.65f),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )

                // 内容滚动列（进度条与底栏固定在外，只有这层滚）
                Column(
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                ) {
                Spacer(Modifier.size(12.dp))
                when (step) {
                    // ═════════ 步 0：见面礼（起名）═════════
                    0 -> {
                        Text(
                            "见面礼：给我起个名字",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            "我是你的手机智能助理——能帮你设闹钟、查资料、操作手机、写效果稿。" +
                                "你想叫我什么？（由你来定，我不给自己起名）",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
                            modifier = Modifier.padding(top = 8.dp)
                        )
                        com.haoai.agent.ui.common.CompactGlassField(
                            value = name,
                            onValueChange = { name = it },
                            label = "名字",
                            placeholder = "如：小七、豆豆、阿澄、团子",
                            modifier = Modifier.padding(top = 14.dp)
                        )
                    }

                    // ═════════ 步 1：性格（预设人设，可微调/自定义）═════════
                    1 -> {
                        Text(
                            "你想让我是什么性格？",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            "挑一个预设（点开能看完整人设并微调），也可以自己写或🎲随机抽一个。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
                            modifier = Modifier.padding(top = 6.dp)
                        )
                        // 卡片标题只写风格+性向（如「元气甜系（女性向）」），不出现内部代号——
                        // 名字步 0 已定，性格步只呈现性格（2026-09-26 用户定稿）
                        val renderCard: @Composable (String?, String, String, Boolean) -> Unit =
                            { key, title, sub, isCustom ->
                                val selected = if (isCustom) personaCustom
                                else personaKey == key && !personaCustom
                                Column(
                                    Modifier
                                        .weight(1f)
                                        // v3⑥：填满行高——行高由 IntrinsicSize.Min 取最高卡，
                                        // 副注行数不同（自定义1行 vs 其他2行）的同排卡等高
                                        .fillMaxHeight()
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(
                                            if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                                            else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.06f)
                                        )
                                        .border(
                                            1.dp,
                                            if (selected) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.14f),
                                            RoundedCornerShape(14.dp)
                                        )
                                        .clickable {
                                            tunedText = null
                                            tuning = false
                                            if (isCustom) {
                                                personaKey = null
                                                personaCustom = true
                                            } else {
                                                personaCustom = false
                                                personaKey = key
                                            }
                                        }
                                        .padding(horizontal = 11.dp, vertical = 9.dp)
                                ) {
                                    Text(
                                        title,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onBackground
                                    )
                                    Text(
                                        sub,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                                        maxLines = 2,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                    )
                                }
                            }
                        // v3①：6 格 2×3——「自定义」并入同一 chunked 网格（第 6 格=极简执行右侧），
                        // 不再单独占一行
                        val cells: List<Triple<String?, String, String>> =
                            com.haoai.agent.ui.onboarding.PersonaPresets.gridPresets
                                .map { Triple(it.key, it.tag, it.tagline) } +
                                Triple(null, "自定义", "自己写或🎲随机抽一个")
                        Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            cells.chunked(2).forEach { rowCards ->
                                // 行高=同排最高卡（内在高度），配合 fillMaxHeight 同排等高
                                Row(
                                    Modifier.height(IntrinsicSize.Min),
                                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                                ) {
                                    rowCards.forEach { (key, title, sub) ->
                                        renderCard(key, title, sub, key == null)
                                    }
                                    if (rowCards.size == 1) Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                        // 预览（选了预设才出现）：全文 + 微调入口
                        if (!personaCustom && personaKey != null) {
                            val p = com.haoai.agent.ui.onboarding.PersonaPresets.all
                                .firstOrNull { it.key == personaKey }
                            if (p != null) {
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(top = 9.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.06f))
                                        .padding(horizontal = 12.dp, vertical = 10.dp)
                                ) {
                                    Text(
                                        tunedText ?: p.fullText,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f),
                                        // v3④：去掉写死的 170dp 上限——随内容自然撑高（短人设
                                        // 不再截断、空余位置全利用），整页占满由外层滚动接管
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    if (tuning) {
                                        androidx.compose.foundation.text.BasicTextField(
                                            value = tunedText ?: p.fullText,
                                            onValueChange = { tunedText = it },
                                            textStyle = MaterialTheme.typography.labelSmall.copy(
                                                color = MaterialTheme.colorScheme.onBackground
                                            ),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .heightIn(min = 100.dp)
                                                .padding(top = 6.dp)
                                        )
                                        Text(
                                            "✓ 用这一版",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(8.dp))
                                                .clickable { tuning = false }
                                                .padding(top = 6.dp)
                                        )
                                    } else {
                                        Text(
                                            if (tunedText != null) "✓ 已微调 · ✏️ 再改改" else "✏️ 在此人设基础上微调",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(8.dp))
                                                .clickable { tuning = true }
                                                .padding(top = 6.dp)
                                        )
                                    }
                                }
                            }
                        }
                        // 自定义区（只在选了自定义卡时出现，与预览窗互斥）：
                        // label 行「自定义」+ 🎲 随机人格；抽中 chip 只显风格（不带代号）；
                        // 再抽 = 再点🎲（不重复上一款），无「再抽一次」按钮
                        if (personaCustom) {
                            Column(Modifier.padding(top = 9.dp)) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "自定义",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f),
                                        modifier = Modifier.weight(1f)
                                    )
                                    Row(
                                        Modifier
                                            .clip(RoundedCornerShape(11.dp))
                                            .background(Color(0xFFFFC46B).copy(alpha = 0.14f))
                                            .border(
                                                1.dp,
                                                Color(0xFFFFC46B).copy(alpha = 0.45f),
                                                RoundedCornerShape(11.dp)
                                            )
                                            .clickable { rollPersona() }
                                            .padding(horizontal = 13.dp, vertical = 7.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            "🎲 随机人格",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.SemiBold,
                                            color = Color(0xFFFFC46B)
                                        )
                                    }
                                }
                                rolledTag?.let { tag ->
                                    Text(
                                        "抽中：$tag",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color(0xFFFFC46B),
                                        modifier = Modifier.padding(top = 8.dp)
                                    )
                                }
                                // 多行输入（随机抽出的完整人设要能看全能改）
                                androidx.compose.foundation.text.BasicTextField(
                                    value = customText,
                                    onValueChange = { customText = it },
                                    textStyle = MaterialTheme.typography.bodySmall.copy(
                                        color = MaterialTheme.colorScheme.onBackground
                                    ),
                                    cursorBrush = androidx.compose.ui.graphics.SolidColor(
                                        MaterialTheme.colorScheme.primary
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 8.dp)
                                        .clip(RoundedCornerShape(13.dp))
                                        .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.06f))
                                        .border(
                                            1.dp,
                                            MaterialTheme.colorScheme.onBackground.copy(alpha = 0.14f),
                                            RoundedCornerShape(13.dp)
                                        )
                                        .padding(horizontal = 12.dp, vertical = 11.dp)
                                        .heightIn(min = 92.dp),
                                    decorationBox = { inner ->
                                        Box(Modifier.fillMaxWidth()) {
                                            if (customText.isBlank()) {
                                                Text(
                                                    "写句你想要的风格；点🎲随机抽一个填进来，可随意改",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f)
                                                )
                                            }
                                            inner()
                                        }
                                    }
                                )
                                Text(
                                    "不满意就再点一次🎲重抽 · 这段内容会直接写进我的人格",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                                    modifier = Modifier.padding(top = 6.dp)
                                )
                            }
                        }
                    }

                    // ═════════ 步 2：权限模式 ═════════
                    2 -> {
                        Text(
                            "权限怎么管？",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            "决定我调用工具时要不要先问你。多数人用下来全自动最顺手——选一个，随时可改。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
                            modifier = Modifier.padding(top = 6.dp)
                        )
                        val permOptions = listOf(
                            Triple(
                                com.haoai.agent.agent.policy.PermissionMode.ALWAYS_ASK,
                                "全部询问", "每个工具调用都弹窗确认 · 最稳但最吵"
                            ),
                            Triple(
                                com.haoai.agent.agent.policy.PermissionMode.ASK_WRITES,
                                "写入时询问", "读不问，写入/执行才问 · 应用默认"
                            ),
                            Triple(
                                com.haoai.agent.agent.policy.PermissionMode.YOLO,
                                "全自动", "直接执行不弹窗，后台跑任务不中断 · 推荐 · 最顺手"
                            )
                        )
                        Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            permOptions.forEach { (mode, label, desc) ->
                                val selected = perm == mode
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(
                                            if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                                            else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.06f)
                                        )
                                        .border(
                                            1.dp,
                                            if (selected) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.14f),
                                            RoundedCornerShape(14.dp)
                                        )
                                        .clickable { perm = mode }
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Box(
                                        Modifier
                                            .size(17.dp)
                                            .clip(androidx.compose.foundation.shape.CircleShape)
                                            .border(
                                                2.dp,
                                                if (selected) MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.35f),
                                                androidx.compose.foundation.shape.CircleShape
                                            )
                                    ) {
                                        if (selected) {
                                            Box(
                                                Modifier
                                                    .fillMaxSize()
                                                    .padding(3.dp)
                                                    .clip(androidx.compose.foundation.shape.CircleShape)
                                                    .background(MaterialTheme.colorScheme.primary)
                                            )
                                        }
                                    }
                                    Column {
                                        Text(
                                            label,
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onBackground
                                        )
                                        Text(
                                            desc,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                                        )
                                    }
                                }
                            }
                        }
                        Text(
                            "随时可在 设置 → 安全与权限 里改 · 闹钟/相机/定位等系统能力仍按需弹系统授权 · " +
                                "写入保留快照可回滚",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }

                    // ═════════ 步 3：接大脑（迷你供应商向导，可跳过）═════════
                    3 -> {
                        LaunchedEffect(Unit) {
                            if (settingsVm.draft == null) settingsVm.startNewDraft()
                        }
                        Text(
                            "接上一个聪明的大脑",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            "选一家你有 API Key 的服务商，贴上 Key 就能开聊。没有也没关系，随时在「设置 → 模型大脑」里补。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
                            modifier = Modifier.padding(top = 6.dp)
                        )
                        when (brainStage) {
                            // 选服务商（只用推荐短列表——引导要短）
                            0 -> {
                                Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                    com.haoai.agent.ui.ProviderPresets.onboardingShortlist.forEach { p ->
                                        Row(
                                            Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(14.dp))
                                                .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.06f))
                                                .border(
                                                    1.dp,
                                                    if (brainPicked?.name == p.name)
                                                        MaterialTheme.colorScheme.primary
                                                    else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.12f),
                                                    RoundedCornerShape(14.dp)
                                                )
                                                .clickable {
                                                    brainPicked = p
                                                    settingsVm.applyPreset(p)
                                                    brainStage = 1
                                                }
                                                .padding(horizontal = 12.dp, vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                                        ) {
                                            com.haoai.agent.ui.settings.ProviderLogoAvatar(p)
                                            Column {
                                                Text(
                                                    // 自定义 preset 的 name 为空串，兜底显示 label
                                                    p.name.ifBlank { p.label },
                                                    style = MaterialTheme.typography.bodySmall,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = MaterialTheme.colorScheme.onBackground
                                                )
                                                Text(
                                                    p.sub,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            // 连接：URL（预填）+ Key + 测试
                            1 -> {
                                if (draft != null) {
                                    com.haoai.agent.ui.common.CompactGlassField(
                                        value = draft.baseUrl,
                                        onValueChange = { v -> settingsVm.updateDraft(draft.copy(baseUrl = v)) },
                                        label = "Base URL",
                                        placeholder = "https://api.deepseek.com/v1",
                                        modifier = Modifier.padding(top = 10.dp)
                                    )
                                    com.haoai.agent.ui.common.CompactGlassField(
                                        value = draft.apiKeyPlain,
                                        onValueChange = { v -> settingsVm.updateDraft(draft.copy(apiKeyPlain = v)) },
                                        label = "API Key",
                                        placeholder = "sk-…",
                                        modifier = Modifier.padding(top = 7.dp)
                                    )
                                    Row(
                                        Modifier.padding(top = 9.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        when {
                                            settingsVm.testing -> Text(
                                                "测试中…",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                                            )
                                            settingsVm.testResult != null -> {
                                                val (ok, msg) = settingsVm.testResult!!
                                                Text(
                                                    (if (ok) "✓ " else "✕ ") + msg.take(18),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = if (ok) Color(0xFF4CD6A2) else MaterialTheme.colorScheme.error
                                                )
                                            }
                                        }
                                        Text(
                                            if (settingsVm.testing) "重新测试" else "测试连接",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.SemiBold,
                                            color = if (settingsVm.testing) MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f)
                                            else MaterialTheme.colorScheme.primary,
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(8.dp))
                                                .clickable(enabled = !settingsVm.testing && draft.model.isNotBlank()) {
                                                    settingsVm.testDraftConnection()
                                                }
                                                .padding(horizontal = 8.dp, vertical = 5.dp)
                                        )
                                    }
                                }
                            }

                            // 选模型：自动拉取 → 单选默认；拉不到手填
                            else -> {
                                LaunchedEffect(Unit) {
                                    if (settingsVm.modelChoices == null && !settingsVm.fetchingModels &&
                                        draft != null && draft.protocol != "anthropic" && draft.baseUrl.isNotBlank()
                                    ) settingsVm.fetchModelList()
                                }
                                if (draft?.protocol == "anthropic") {
                                    Text(
                                        "原生协议不支持拉取列表，直接在下面手填模型 ID",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                                        modifier = Modifier.padding(top = 10.dp)
                                    )
                                } else if (settingsVm.fetchingModels) {
                                    Text(
                                        "⇣ 拉取模型列表…",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                                        modifier = Modifier.padding(top = 10.dp)
                                    )
                                }
                                val choices = settingsVm.modelChoices
                                if (choices != null && choices.isNotEmpty()) {
                                    Column(
                                        Modifier
                                            .padding(top = 10.dp)
                                            .heightIn(max = 220.dp)
                                            .verticalScroll(rememberScrollState()),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        choices.take(12).forEach { id ->
                                            val selected = draft?.model?.trim() == id
                                            Row(
                                                Modifier
                                                    .fillMaxWidth()
                                                    .clip(RoundedCornerShape(12.dp))
                                                    .background(
                                                        if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                                                        else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.05f)
                                                    )
                                                    .clickable { settingsVm.pickModel(id) }
                                                    .padding(horizontal = 12.dp, vertical = 9.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Box(
                                                    Modifier
                                                        .size(16.dp)
                                                        .clip(androidx.compose.foundation.shape.CircleShape)
                                                        .border(
                                                            2.dp,
                                                            if (selected) MaterialTheme.colorScheme.primary
                                                            else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f),
                                                            androidx.compose.foundation.shape.CircleShape
                                                        )
                                                )
                                                Spacer(Modifier.size(9.dp))
                                                Text(
                                                    id,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onBackground,
                                                    maxLines = 1,
                                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                                )
                                            }
                                        }
                                    }
                                }
                                com.haoai.agent.ui.common.CompactGlassField(
                                    value = manualModel,
                                    onValueChange = { manualModel = it },
                                    label = "或手动输入模型 ID",
                                    placeholder = "如 deepseek-chat",
                                    modifier = Modifier.padding(top = 8.dp)
                                )
                            }
                        }
                    }

                    // ═════════ 步 4：完成清单 ═════════
                    else -> {
                        Text(
                            "一切就绪 🎉",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            "这是你刚配好的东西，以后都在设置里可以改：",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
                            modifier = Modifier.padding(top = 6.dp)
                        )
                        Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                            onboardingDoneRow("✓", "名字：${name.ifBlank { "小七" }}", "设置 → 通用 里可改")
                            onboardingDoneRow("✓", "性格：${soulEcho()}", "设置 → 通用 可换人设")
                            onboardingDoneRow(
                                "✓", "权限：" + when (perm) {
                                    com.haoai.agent.agent.policy.PermissionMode.YOLO -> "全自动"
                                    com.haoai.agent.agent.policy.PermissionMode.ASK_WRITES -> "写入时询问"
                                    else -> "全部询问"
                                },
                                "设置 → 安全与权限 可改 · 写入保留快照可回滚"
                            )
                            onboardingDoneRow(
                                if (brainSkipped) "!" else "✓",
                                if (brainSkipped) "大脑：未配置" else "大脑：$brainEcho",
                                if (brainSkipped) "设置 → 模型大脑 随时接入" else "设置 → 模型大脑 可加更多供应商"
                            )
                            onboardingDoneRow("·", "通知保活：跑长任务时会请求一次", "后台执行需要常驻通知，建议允许")
                            onboardingDoneRow("·", "进阶：MCP 工具 / 技能 / 定时任务 / 端侧推理", "不着急，想玩的时候再看")
                        }
                    }
                }

                } // end content scroll Column

                // ── 底部操作栏：上一步 / 主按钮（固定底栏 · 左右居中——v3⑤，含第 5 步「开始使用」）──
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 18.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (step > 0) {
                        Text(
                            "‹ 上一步",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .clickable {
                                    if (step == 3 && brainStage > 0) brainStage--
                                    else if (step == 3) {
                                        settingsVm.cancelDraft()
                                        step = 2
                                    } else step--
                                }
                                .padding(horizontal = 10.dp, vertical = 8.dp)
                        )
                    }
                    when (step) {
                        0 -> onboardingPrimaryButton(
                            backdrop = backdrop,
                            text = "下一步",
                            enabled = name.isNotBlank(),
                            onClick = { if (name.isNotBlank()) step = 1 }
                        )
                        1 -> onboardingPrimaryButton(backdrop, "下一步", true) { step = 2 }
                        2 -> onboardingPrimaryButton(backdrop, "下一步", true) { step = 3 }
                        3 -> {
                            if (brainStage == 0) {
                                Text(
                                    "稍后再配 →",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(999.dp))
                                        .clickable {
                                            brainSkipped = true
                                            settingsVm.cancelDraft()
                                            step = 4
                                        }
                                        .padding(horizontal = 10.dp, vertical = 8.dp)
                                )
                            }
                            if (brainStage == 0) {
                                onboardingPrimaryButton(
                                    backdrop = backdrop,
                                    text = "下一步",
                                    enabled = brainPicked != null,
                                    onClick = { }
                                )
                            } else if (brainStage == 1) {
                                onboardingPrimaryButton(
                                    backdrop = backdrop,
                                    text = "下一步",
                                    enabled = draft != null && draft.baseUrl.isNotBlank(),
                                    onClick = {
                                        brainStage = 2
                                    }
                                )
                            } else {
                                onboardingPrimaryButton(
                                    backdrop = backdrop,
                                    text = "完成",
                                    enabled = draft != null && draft.model.isNotBlank(),
                                    onClick = {
                                        brainEcho = (brainPicked?.name ?: draft?.name ?: "") +
                                            " · " + (draft?.model ?: "")
                                        settingsVm.saveDraft()
                                        step = 4
                                    }
                                )
                            }
                        }
                        else -> onboardingPrimaryButton(
                            backdrop = backdrop,
                            text = "开始使用",
                            enabled = true,
                            onClick = { onSave(name, soulText(), perm) }
                        )
                    }
                }
            }
        }
    }
}

/** 完成清单行。 */
@Composable
private fun onboardingDoneRow(mark: String, title: String, sub: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        Text(
            mark,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = when (mark) {
                "✓" -> Color(0xFF4CD6A2)
                "!" -> Color(0xFFFFC46B)
                else -> MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f)
            }
        )
        Column {
            Text(
                title,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                sub,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f)
            )
        }
    }
}

/** 引导主按钮（液态玻璃胶囊）。 */
@Composable
private fun onboardingPrimaryButton(
    backdrop: com.kyant.backdrop.backdrops.LayerBackdrop,
    text: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    LiquidGlassButton(
        onClick = onClick,
        backdrop = backdrop,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(percent = 50),
        enabled = enabled,
        surfaceColor = MaterialTheme.colorScheme.primary.copy(
            alpha = if (enabled) 0.85f else 0.25f
        )
    ) {
        Text(
            text,
            color = if (enabled) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f),
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp)
        )
    }
}
