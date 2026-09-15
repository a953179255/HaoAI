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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    RootApp(wallpaper)
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
    in 9..16 -> 2   // 设置 section 子页（记忆与梦境/模型大脑/…）
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
            "vscreen" -> {
                enterChat()
                screen = 0
                rootScope.launch(kotlinx.coroutines.Dispatchers.Default) {
                    val appCtx = container.appContext
                    val pkg = uri.getQueryParameter("pkg") ?: "com.android.settings"
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
            "vsclose" -> {
                enterChat()
                screen = 0
                rootScope.launch(kotlinx.coroutines.Dispatchers.Default) {
                    com.haoai.agent.platform.vdisplay.VirtualScreenController.destroy()
                    com.haoai.agent.platform.vdisplay.VirtualScreenController.debugLog("route: vsclose done")
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
                        val sb = com.haoai.agent.platform.sandbox.SandboxEnv.resolve(ctx.filesDir, nativeDir, ws)
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
    val plainTop = scheme.surface
    val plainBottom = scheme.surfaceVariant
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
    // 聊天滚动状态提升到 RootApp（不随 screen 切换销毁），进设置再返回时保持位置
    val chatListState = androidx.compose.runtime.saveable.rememberSaveable(
        saver = androidx.compose.foundation.lazy.LazyListState.Saver
    ) { androidx.compose.foundation.lazy.LazyListState() }

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
            OnboardingGlass(
                backdrop = backdrop,
                onSave = { name, soul -> chatVm.completeOnboarding(name, soul) }
            )
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
                    listState = chatListState,
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
            } // AnimatedContent content lambda
            // 4.2 预览浮层已移入 ChatScreen（browserPreview 槽位）：保证它画在
            // 侧边栏抽屉之下（抽屉打开时盖住它），且只在聊天页存在。
            // 4.3 虚拟屏预览浮层已移入 ChatScreen（vscreenPreview 槽位）：与浏览器
            // 同样画在抽屉之下、仅聊天页存在。
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
    onSave: (String, String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var soul by remember { mutableStateOf("") }
    var step by remember { mutableIntStateOf(0) }
    val canConfirm = step == 1 || name.isNotBlank()

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        // 首启引导：液态玻璃卡片悬浮在壁纸之上（"出生仪式"）
        GlassPanel(
            backdrop = backdrop,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp),
            radius = 30.dp,
            surfaceAlpha = 0.36f
        ) {
            Column(Modifier.padding(horizontal = 22.dp, vertical = 26.dp)) {
                Text(
                    if (step == 0) "见面礼：给我起个名字" else "你想让我是什么性格？",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    if (step == 0)
                        "我是你的手机智能助理。你想叫我什么？（由你来定，我不给自己起名）"
                    else
                        "用一句话形容你希望我的做事风格（可跳过）。之后可在设置里修改。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
                    modifier = Modifier.padding(top = 8.dp)
                )
                OutlinedTextField(
                    value = if (step == 0) name else soul,
                    onValueChange = { if (step == 0) name = it else soul = it },
                    label = {
                        Text(if (step == 0) "名字" else "性格 / 风格，如：简洁高效，少废话")
                    },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = MaterialTheme.colorScheme.onBackground,
                        unfocusedTextColor = MaterialTheme.colorScheme.onBackground,
                        cursorColor = MaterialTheme.colorScheme.primary,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.35f),
                        focusedContainerColor = Color.White.copy(alpha = 0.32f),
                        unfocusedContainerColor = Color.White.copy(alpha = 0.2f),
                        focusedLabelColor = MaterialTheme.colorScheme.primary,
                        unfocusedLabelColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp)
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (step == 1) {
                        TextButton(onClick = { onSave(name, "") }) {
                            Text("跳过", color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f))
                        }
                    }
                    LiquidGlassButton(
                        onClick = {
                            if (step == 0) {
                                if (name.isNotBlank()) step = 1
                            } else {
                                onSave(name, soul)
                            }
                        },
                        backdrop = backdrop,
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(percent = 50),
                        enabled = canConfirm,
                        surfaceColor = MaterialTheme.colorScheme.primary.copy(
                            alpha = if (canConfirm) 0.85f else 0.25f
                        )
                    ) {
                        Text(
                            if (step == 0) "下一步" else "开始使用",
                            color = if (canConfirm) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f),
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp)
                        )
                    }
                }
            }
        }
    }
}
