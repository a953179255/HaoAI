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
    val settings by container.settingsFlow.collectAsState()
    // 无壁纸时的默认渐变底色随主题切换（暗色/AMOLED 下不再露浅绿）
    val darkBackdrop = when (settings.themeMode) {
        "dark" -> true
        "light" -> false
        else -> androidx.compose.foundation.isSystemInDarkTheme()
    }
    val wallpaperOnScreen = settings.wallpaperGlobal || screen == 0
    val wpBackdrop = com.haoai.agent.ui.common.rememberAppBackdrop(wallpaper, dark = darkBackdrop)
    val plainBackdrop = com.haoai.agent.ui.common.rememberAppBackdrop(null, dark = darkBackdrop)
    val backdrop = if (wallpaper != null && wallpaperOnScreen) wpBackdrop else plainBackdrop

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
    val rootScope = androidx.compose.runtime.rememberCoroutineScope()
    var cameFromDrawer by rememberSaveable { mutableStateOf(false) }

    // 设置二级页状态提升：从设置子页进入管理页（记忆库等）后，返回时回到原子页而非设置根
    var settingsSection by rememberSaveable { mutableStateOf("") }

    Box(Modifier.fillMaxSize()) {
        if (wallpaper != null && wallpaperOnScreen) {
            Image(
                bitmap = wallpaper.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize()
            )
        }
        if (!settings.onboarded) {
            OnboardingGlass(
                backdrop = backdrop,
                onSave = { name, soul -> chatVm.completeOnboarding(name, soul) }
            )
        } else {
            // 页面切换过渡：淡入 + 3% 轻微上移 + 98%→100% 缩放（上游 式克制动效）。
            // 不用滑动方向感（左右 slide）：screen 编号与层级无关（0=聊天 1=设置 8=工作流），
            // 方向滑动会显得随机；快速 fade+微位移既有过渡又不拖沓。
            androidx.compose.animation.AnimatedContent(
                targetState = screen,
                transitionSpec = {
                    (androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(180)) +
                        androidx.compose.animation.scaleIn(
                            initialScale = 0.985f,
                            animationSpec = androidx.compose.animation.core.tween(200)
                        )).togetherWith(
                        androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(120))
                    )
                },
                label = "screenSwitch"
            ) { s ->
            when (s) {
                1 -> SettingsScreen(
                    vm = settingsVm,
                    backdrop = backdrop,
                    initialSection = settingsSection,
                    onSectionChange = { settingsSection = it },
                    onBack = {
                        screen = 0
                        // 从侧边栏进入设置的：返回时侧边栏直接以展开态出现（snapOpen），
                        // 不重播滑出动画——与页面淡入叠加会显得两段式、不丝滑
                        if (cameFromDrawer) {
                            cameFromDrawer = false
                            rootScope.launch { drawer.snapOpen() }
                        }
                    },
                    onOpenMemories = { screen = 2 },
                    onOpenSchedules = { screen = 3 },
                    onOpenSkills = { screen = 5 },
                    onOpenMcp = { screen = 6 },
                    onOpenWorkflows = { screen = 8 }
                )
                2 -> com.haoai.agent.ui.manage.MemoryScreen(
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
                    onOpenSettings = { fromDrawer ->
                        cameFromDrawer = fromDrawer
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
