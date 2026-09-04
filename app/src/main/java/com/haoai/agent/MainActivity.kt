package com.haoai.agent

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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
    }

    /** onNewIntent 投递的深链（含冷启动 intent 之外的所有重投递）。 */
    internal val deepLinkFlow = kotlinx.coroutines.flow.MutableStateFlow<android.net.Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationIfNeeded()
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
            val wallpaper = androidx.compose.runtime.remember(wpVersion) {
                com.haoai.agent.platform.WallpaperStore.loadBitmap(applicationContext)
            }
            HaoTheme(
                darkTheme = dark,
                dynamicColor = settings.dynamicColor,
                seedIndex = settings.themeSeed,
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

// 页面层级：转场方向判定与纱幕升降共用（push = 层级升高）
private fun screenLevel(s: Int) = when (s) {
    0 -> 0          // 聊天（根）
    1, 4, 7 -> 1    // 设置 / 会话列表 / 浏览器
    else -> 2       // 记忆 / 定时 / 技能 / MCP / 工作流
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

    // debug deep link 路由：adb shell am start -a android.intent.action.VIEW -d "haoai://debug/<target>"
    // 验证直达（跳过导航点击）。仅响应 host=debug（Manifest intent-filter 限定）
    // 来源有二：冷启动 getIntent（LaunchedEffect 初值）+ 运行中 onNewIntent（deepLinkFlow）
    val rootScope = androidx.compose.runtime.rememberCoroutineScope()
    val act = context as? android.app.Activity
    suspend fun consumeDeepLink(uri: android.net.Uri) {
        if (uri.host != "debug") return
        val target = uri.lastPathSegment ?: ""
        when (target) {
            "chat" -> screen = 0
            "settings" -> screen = 1
            "memory" -> screen = 2
            "schedules" -> screen = 3
            "sessions" -> screen = 4
            "skills" -> screen = 5
            "mcp" -> screen = 6
            "browser" -> screen = 7
            "workflows" -> screen = 8
            "vscreen" -> {
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
                screen = 0
                chatVm.newSession()
            }
            "ask" -> {
                // 调试直达：haoai://debug/ask?text=...（URL 编码）——绕过 IME 注入直接派任务
                screen = 0
                uri.getQueryParameter("text")?.takeIf { it.isNotBlank() }?.let { chatVm.send(it) }
            }
            "vsclose" -> {
                screen = 0
                rootScope.launch(kotlinx.coroutines.Dispatchers.Default) {
                    com.haoai.agent.platform.vdisplay.VirtualScreenController.destroy()
                    com.haoai.agent.platform.vdisplay.VirtualScreenController.debugLog("route: vsclose done")
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
    // 转场纱幕：上游 旧页淡出溶进的是「净色实底」（其根布局
    // background(colorScheme.background)），HaoAI 淡出溶进的是花壁纸
    // ——彩色图案残影。push 期间在壁纸上盖一层主题净色「纱」
    // （alpha 0.9 近实底，转场完缓退），转场瞬间借用 上游 的净底。
    // 只在 push（离开聊天/设置根）时升起：pop 是「揭开」语义，聊天页
    // 滑回时壁纸应同步回归——纱幕若在，会把落位的聊天页罩灰（实测确认）
    var lastScreen by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(screen)
    }
    var scrimLevel by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(0f)
    }
    androidx.compose.runtime.LaunchedEffect(screen) {
        // 转场纱幕时序：push 升起（旧页淡出溶进净色）→ 与转场同步稍长后
        // 缓退（露出新页自己的底：聊天页=壁纸 / 设置页=自带壁纸或净色）。
        // pop 回聊天也短暂升起：退出中的设置页淡出同样需要净色接住
        scrimLevel = 0.9f
        kotlinx.coroutines.delay(420)
        scrimLevel = 0f
    }
    val scrimAlpha = androidx.compose.animation.core.animateFloatAsState(
        targetValue = scrimLevel,
        animationSpec = androidx.compose.animation.core.tween(
            durationMillis = if (scrimLevel > 0f) 0 else 450,
            easing = androidx.compose.animation.core.FastOutSlowInEasing
        ),
        label = "transitionScrim"
    ).value
    val plainTop = scheme.surface
    val plainBottom = scheme.surfaceVariant
    val wpBackdrop = com.haoai.agent.ui.common.rememberAppBackdrop(
        wallpaper, dark = darkBackdrop, baseTop = plainTop, baseBottom = plainBottom
    )
    val plainBackdrop = com.haoai.agent.ui.common.rememberAppBackdrop(
        null, dark = darkBackdrop, baseTop = plainTop, baseBottom = plainBottom
    )
    // backdrop 分配（与各页实际背景严格对齐——采样=所见是玻璃不出错的铁律）：
    // - 聊天页(0)：壁纸层在它背后实时对位 → wpBackdrop
    // - 设置等二级页 + 全局壁纸开：页面根底自带来对齐的壁纸 Image（SettingsScreen
    //   内部绘制）→ wpBackdrop。上一版强制素底导致「全局壁纸失效显示白底」
    // - 其余（全局关时的二级页）：净色底 → plainBackdrop
    // 转场期纱幕（页面层之下）负责接住退出页的淡出，采样无需再降级
    val backdrop = if (wallpaper != null &&
        (screen == 0 || settings.wallpaperGlobal)
    ) {
        wpBackdrop
    } else {
        plainBackdrop
    }

    // 状态栏图标随顶部实际亮度自适应（修复：系统深色 + App 浅色时白图标看不见）
    com.haoai.agent.ui.common.AdaptiveStatusBarIcons(
        dark = darkBackdrop,
        sampleKey = "${darkBackdrop}|${settings.themeSeed}|${settings.dynamicColor}|" +
            "${settings.amoledMode}|$screen|$wpVersion|${settings.wallpaperGlobal}"
    )

    // 4.2 呼出优化：无头工具触发浏览时自动弹出底部预览面板（两段式第一段，
    // 聊天不打断）；面板 🌐 才进全屏浏览器。可见容器才真实加载（平台约束）
    androidx.compose.runtime.LaunchedEffect(Unit) {
        com.haoai.agent.agent.browser.BrowserController.uiOpener = {
            com.haoai.agent.agent.browser.BrowserController.openPreview()
        }
    }
    // 抽屉状态提升：设置页返回时可恢复「侧边栏呼出」的来源状态
    val drawer = remember { com.haoai.agent.ui.chat.DrawerController() }
    // 聊天滚动状态提升到 RootApp（不随 screen 切换销毁），进设置再返回时保持位置
    val chatListState = androidx.compose.runtime.saveable.rememberSaveable(
        saver = androidx.compose.foundation.lazy.LazyListState.Saver
    ) { androidx.compose.foundation.lazy.LazyListState() }

    // 设置二级页状态提升：从设置子页进入管理页（记忆库等）后，返回时回到原子页而非设置根
    var settingsSection by rememberSaveable { mutableStateOf("") }

    Box(Modifier.fillMaxSize()) {
        // 壁纸 Image 层：延迟释放（转场期退出中的玻璃页需要它垫底）
        if (wallpaper != null && wallpaperImageVisible) {
            Image(
                bitmap = wallpaper.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize()
            )
            // 转场纱幕：净色盖在壁纸上（页面层之下），旧页淡出时溶进净色
            // 而非花壁纸——上游 丝滑的关键（其底就是净色实底）
            if (scrimAlpha > 0.01f) {
                Box(
                    Modifier
                        .matchParentSize()
                        .background(scheme.background.copy(alpha = scrimAlpha))
                )
            }
        }
        if (!settings.onboarded) {
            OnboardingGlass(
                backdrop = backdrop,
                onSave = { name, soul -> chatVm.completeOnboarding(name, soul) }
            )
        } else {
            // 页面切换过渡（上游 同款：slide + scale + fade，有纵深感）：
            // push——新页全屏滑入；旧页缩小(1→0.92)+变暗淡出沉底（不是全 0.7，
            // HaoAI 旧页带着展开抽屉，缩太多会露出边缘）。pop 反向——旧页全速
            // 滑出，下层页从 0.92 迎上来放大回位。双向运动 = 上游 流畅感来源。
            // zIndex：AnimatedContent 默认 target 在顶，pop 时必须显式把进入的
            // 聊天页压到 -1，否则聊天页（含抽屉 scrim）盖在设置页上洗灰。
            fun levelOf(s: Int) = screenLevel(s)
            val ease = androidx.compose.animation.core.FastOutSlowInEasing
            // 上游 未指定 tween：navigation3/AnimatedContent 默认 spring
            // （stiffness MediumLow 附近）——先快后缓的自然减速，比固定
            // 380ms tween 的匀速机械感更「丝滑」。visibilityThreshold 保证
            // 像素级收敛不抖动
            val slideSpec: androidx.compose.animation.core.FiniteAnimationSpec<androidx.compose.ui.unit.IntOffset> =
                androidx.compose.animation.core.spring(
                    dampingRatio = 0.9f,
                    stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow,
                    visibilityThreshold = androidx.compose.ui.unit.IntOffset(1, 1)
                )
            val fadeSpec: androidx.compose.animation.core.FiniteAnimationSpec<Float> =
                androidx.compose.animation.core.tween(durationMillis = 300, easing = ease)
            val scaleSpec: androidx.compose.animation.core.FiniteAnimationSpec<Float> =
                androidx.compose.animation.core.spring(
                    dampingRatio = 0.9f,
                    stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow,
                    visibilityThreshold = 0.001f
                )
            androidx.compose.animation.AnimatedContent(
                targetState = screen,
                transitionSpec = {
                    val from = levelOf(initialState)
                    val to = levelOf(targetState)
                    // 方向二：聊天页(0)参与的转场用纯滑动——聊天页不缩放不淡出
                    // （重页面最简单的运动最不容易露馅：缩放+淡出会放大重组延迟
                    // 的可见性，直线滑动则完全掩盖）。仅非聊天页之间保留
                    // slide+scale+fade 纵深（用户确认设置↔技能库观感好）
                    val chatInvolved = initialState == 0 || targetState == 0
                    when {
                        // push（聊天→设置等）：设置页全速滑入盖上来；
                        // 聊天页（含抽屉）直线左滑出，无缩放无变暗
                        to >= from && initialState != 0 || (from == 0 && to == 1) -> {
                            (androidx.compose.animation.slideInHorizontally(slideSpec) { it })
                                .togetherWith(
                                if (chatInvolved) {
                                    androidx.compose.animation.slideOutHorizontally(slideSpec) { -it }
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
                    initialSection = settingsSection,
                    onSectionChange = { settingsSection = it },
                    // 从设置返回聊天：恢复抽屉展开态（与预览方案一致——返回后侧边栏在）。
                    // snapOpen 无动画瞬位，与 pop 过渡同帧；聊天页带展开抽屉一起视差回来。
                    // 同时解除冻结：返回后聊天页恢复实时绘制
                    onBack = {
                        screen = 0
                        drawer.frozen = false
                        rootScope.launch { drawer.snapOpen() }
                    },
                    onOpenMemories = { screen = 2 },
                    onOpenSchedules = { screen = 3 },
                    onOpenSkills = { screen = 5 },
                    onOpenMcp = { screen = 6 },
                    onOpenWorkflows = { screen = 8 }
                )                2 -> com.haoai.agent.ui.manage.MemoryScreen(
                    backdrop = backdrop,
                    // 记忆/任务/技能入口已收进设置页，返回回设置
                    onBack = { screen = 1 }
                )
                3 -> com.haoai.agent.ui.manage.ScheduleScreen(
                    backdrop = backdrop,
                    onBack = { screen = 1 }
                )
                5 -> com.haoai.agent.ui.manage.SkillsScreen(
                    backdrop = backdrop,
                    onBack = { screen = 1 }
                )
                6 -> com.haoai.agent.ui.settings.McpSettingsScreen(
                    backdrop = backdrop,
                    onBack = { screen = 1 }
                )
                8 -> com.haoai.agent.ui.manage.WorkflowScreen(
                    container = container,
                    backdrop = backdrop,
                    onBack = { screen = 1 }
                )
                4 -> com.haoai.agent.ui.sessions.SessionsScreen(
                    vm = chatVm,
                    backdrop = backdrop,
                    // 全部会话只能从侧边栏进入：返回（箭头/系统手势）回到聊天并重新展开侧边栏
                    onBack = {
                        screen = 0
                        rootScope.launch { drawer.open() }
                    }
                )
                // 4.2 内置浏览器：与工具共用 BrowserController WebView 池
                7 -> com.haoai.agent.ui.browser.BrowserScreen(
                    backdrop = backdrop,
                    onBack = { screen = 0 }
                )
                else -> ChatScreen(
                    vm = chatVm,
                    backdrop = backdrop,
                    drawer = drawer,
                    listState = chatListState,
                    // 侧边栏点设置：push 转场开始——先冻结聊天页（后续帧绘制
                    // 静态快照，重页面组合延迟不再被看穿），切页触发转场
                    onOpenSettings = {
                        drawer.frozen = true
                        screen = 1
                    },
                    // 先收起抽屉再切页：否则返回时 drawerState 仍是 Open，抽屉会原样展开
                    onOpenSessions = {
                        rootScope.launch { drawer.close() }
                        screen = 4
                    },
                    onOpenBrowser = {
                        // 顶栏 🌐 直达全屏：先收预览面板，避免浮层叠在全屏浏览器上
                        com.haoai.agent.agent.browser.BrowserController.previewOpen.value = false
                        screen = 7
                    },
                    onOpenVscreen = {
                        com.haoai.agent.platform.vdisplay.VirtualScreenController.openPreview()
                    }
                )
            }
            } // AnimatedContent content lambda
            // 4.2 呼出优化：底部预览浮层（叠在任意 screen 之上，工具无头浏览自动弹出）
            val previewOpen by com.haoai.agent.agent.browser.BrowserController.previewOpen.collectAsState()
            if (previewOpen) {
                com.haoai.agent.ui.browser.BrowserPreviewPanel(
                    backdrop = backdrop,
                    onClose = { com.haoai.agent.agent.browser.BrowserController.previewOpen.value = false },
                    onFullscreen = {
                        com.haoai.agent.agent.browser.BrowserController.previewOpen.value = false
                        screen = 7
                    }
                )
            }
            // 4.3 增强：虚拟屏实时预览浮层（vscreen_launch 成功自动弹出）
            val vscreenOpen by com.haoai.agent.platform.vdisplay.VirtualScreenController.previewOpen.collectAsState()
            if (vscreenOpen) {
                com.haoai.agent.ui.browser.VScreenPreviewPanel(
                    backdrop = backdrop,
                    onClose = { com.haoai.agent.platform.vdisplay.VirtualScreenController.closePreview() }
                )
            }
        }
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
        // 首启引导：液态玻璃卡片悬浮在壁纸之上（上游 式"出生仪式"）
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
