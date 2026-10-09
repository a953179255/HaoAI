package com.haoai.agent.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Construction
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Tune
import androidx.compose.foundation.layout.imePadding
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import com.haoai.agent.ui.theme.toHex
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.haoai.agent.agent.policy.PermissionMode
import com.haoai.agent.agent.tools.SearchProviders
import com.haoai.agent.agent.tools.SearchTestOutcome
import com.haoai.agent.data.AppSettings
import com.haoai.agent.platform.KeepAliveService
import com.haoai.agent.platform.llama.LlamaState
import com.haoai.agent.ui.SettingsViewModel
import com.haoai.agent.ui.common.GlassCard
import com.haoai.agent.ui.common.GlassPageBar
import com.haoai.agent.ui.common.GlassPanel
import com.kyant.backdrop.backdrops.layerBackdrop
import com.haoai.agent.ui.common.HaoChip
import com.haoai.agent.ui.common.HaoGroup
import com.haoai.agent.ui.common.HaoRow
import com.haoai.agent.ui.theme.HaoDimens
import com.haoai.agent.ui.theme.HaoTone
import com.haoai.agent.ui.theme.haoToneInk
import com.haoai.agent.ui.theme.haoToneMain
import com.haoai.agent.ui.common.GlassTextButton
import com.haoai.agent.ui.common.LiquidSlider
import com.haoai.agent.ui.common.glassFieldColors
import kotlinx.coroutines.launch
import com.haoai.agent.ui.common.appLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import com.haoai.agent.ui.theme.MotionStyle
import com.haoai.agent.ui.theme.MotionTheme
import androidx.compose.ui.graphics.luminance

/**
 * 设置主页（分组导航）：
 * 根页面只列分类入口，具体设置项进各子页，避免功能增多后平铺混乱。
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun SettingsScreen(
    vm: SettingsViewModel,
    backdrop: com.kyant.backdrop.backdrops.LayerBackdrop,
    onBack: () -> Unit,
    /** 全局壁纸开时传入：页面自带对齐的壁纸底（随页面整体滑动，玻璃采样与之一致） */
    wallpaper: android.graphics.Bitmap? = null,
    /**
     * 锁定渲染某个 section 子页（独立 screen 化）：非空时本组件直接渲染该
     * 子页（跳过主页与 section 路由），返回走 onSectionBack。由 MainActivity
     * 为「模型大脑/权限与自动化/记忆与梦境/工作空间/Linux/通用/关于/用量」
     * 各建一个独立 screen 调用——与技能库/MCP/工作流一致地获得转场+壁纸
     */
    lockedSection: String? = null,
    onSectionBack: () -> Unit = {},
    /** 主页列表滚动状态由 RootApp 提升传入：进子页销毁重建也不丢位置
     *  （rememberSaveable 在 AnimatedContent 销毁分支时不恢复，实测无效） */
    rootListState: androidx.compose.foundation.lazy.LazyListState =
        androidx.compose.foundation.lazy.rememberLazyListState(),
    initialSection: String = "",
    onSectionChange: (String) -> Unit = {},
    onOpenMemories: () -> Unit = {},
    onOpenSchedules: () -> Unit = {},
    onOpenSkills: () -> Unit = {},
    onOpenMcp: () -> Unit = {},
    /** 「电脑联动」页：把这台手机配到电脑上那台 HaoAI（B4） */
    onOpenPcLink: () -> Unit = {},
    onOpenWorkflows: () -> Unit = {},
    /** section 子页独立 screen 化：主页菜单点击回调（参数为 section key → screen 号由 MainActivity 映射） */
    onOpenSection: (Int) -> Unit = {},
    /** 搜索服务 → 目录页（全量后端 + 搜索过滤），独立 screen 由 MainActivity 映射 */
    onOpenSearchCatalog: () -> Unit = {}
) {
    val context = LocalContext.current
    val settings by vm.settings.collectAsState()
    val draft = vm.draft
    val scope = rememberCoroutineScope()

    // 玻璃弹窗状态
    var showScan by androidx.compose.runtime.remember { mutableStateOf(false) }
    // 待删除的供应商 id：点击删除先弹确认（防止误触直接删库）
    var pendingDelete by androidx.compose.runtime.remember { mutableStateOf<String?>(null) }
    // 模型能力编辑弹层目标（providerId to modelId）——设计稿 ③：输入/输出模态勾选
    var capsTarget by androidx.compose.runtime.remember { mutableStateOf<Pair<String, String>?>(null) }
    // 记忆专用端侧模型选择弹窗
    var showDreamPicker by androidx.compose.runtime.remember { mutableStateOf(false) }
    var wpVersion by androidx.compose.runtime.remember { mutableStateOf(vm.wallpaperSet(context)) }
    var confirmWpClear by androidx.compose.runtime.remember { mutableStateOf(false) }

    // section 状态由 MainActivity 提升（从记忆库等管理页返回时恢复原子页）。
    // lockedSection 非空（独立 screen 模式）时 section 恒为该值、不可变，
    // 且不得触发 onSectionChange——否则会把 settingsSection 污染成锁定值，
    // 返回设置主页时直接渲染子页而非菜单列表
    var section by rememberSaveable { mutableStateOf(lockedSection ?: initialSection) }
    androidx.compose.runtime.LaunchedEffect(section) {
        if (lockedSection == null && section != initialSection) onSectionChange(section)
    }
    // 用量页「清空账本」确认弹窗：状态提在根级（弹窗不能渲染在 LazyColumn item 内——
    // fillMaxSize 遮罩会受 item 高度约束，实测只盖住下半屏）
    var confirmClearLedger by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    // 「模型大脑 → 内部任务模型」选择弹窗：同样必须在根级渲染
    var purposePicker by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    // Linux 环境（3.2）：发行版状态/安装进度/弹窗路由集中在一处
    val linuxState = androidx.compose.runtime.remember { LinuxEnvState() }
    androidx.compose.runtime.LaunchedEffect(section) {
        if (section == "linux" || section.isEmpty()) linuxState.refresh(vm.distros)
    }
    // 首页计数（记忆/日志/技能）后台加载，避免组合期读盘
    androidx.compose.runtime.LaunchedEffect(section) { vm.refreshHomeCounts() }

    // 从系统设置授权/开启无障碍回到 App：ON_RESUME 重读，界面立即反映最新状态。
    // a11yOn 必须是状态（a11yTick 驱动重读）——普通 val 只在组合时求值一次，授权回来不刷新
    var a11yTick by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val a11yOn = remember(a11yTick) { com.haoai.agent.platform.a11y.HaoAccessibilityService.connected() }
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                vm.refreshPermissions(context)
                a11yTick++
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    androidx.activity.compose.BackHandler(enabled = draft == null) {
        when {
            // 供应商编辑弹窗优先级由下方 enabled=draft!=null 的 handler 处理；
            // 独立 screen 模式：返回键=退出本 screen（回设置主页）
            lockedSection != null -> onSectionBack()
            section.isNotEmpty() -> section = ""
            else -> onBack()
        }
    }
    // 后注册优先级更高：供应商编辑弹窗打开时，返回键关闭弹窗而不是直接退出应用
    androidx.activity.compose.BackHandler(enabled = draft != null) {
        vm.cancelDraft()
    }

    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            scope.launch { vm.useSafWorkspace(uri.toString()) }
        }
    }

    // 独立 screen 模式：只渲染锁定的子页（无主页分支、返回走 onSectionBack）。
    // 关键：不能 early return——供应商编辑/删除确认/模型选择等弹窗渲染在函数
    // 尾部，early return 会让独立模式下所有触发弹窗的按钮「点了没反应」
    if (lockedSection != null) {
        SectionPage(
            section = lockedSection,
            vm = vm, settings = settings, wallpaper = wallpaper,
            context = context, a11yOn = a11yOn, a11yTick = a11yTick,
            wpVersion = wpVersion, linuxState = linuxState,
            showScan = showScan, onShowScan = { showScan = true },
            pendingDelete = pendingDelete, onPendingDelete = { pendingDelete = it },
            purposePicker = purposePicker, onPickPurposeModel = { purposePicker = it },
            showDreamPicker = showDreamPicker, onPickDreamModel = { showDreamPicker = true },
            confirmWpClear = confirmWpClear, onConfirmWpClear = { confirmWpClear = true },
            confirmClearLedger = confirmClearLedger, onConfirmClearLedger = { confirmClearLedger = true },
            treePickerLaunch = { treePicker.launch(null) },
            onOpenMemories = onOpenMemories,
            onOpenSearchCatalog = onOpenSearchCatalog,
            onEditCaps = { pid, mid -> capsTarget = pid to mid },
            onBack = onSectionBack
        )
    } else if (section.isEmpty()) {
        Box(
            Modifier
                .fillMaxSize()
                // 不透明净色兜底：转场期间本页整体平移，页面自带实底垫背。
                // 全局壁纸开时上面再铺对齐的壁纸 Image（与 backdrop 采样画布
                // 同源同位，玻璃卡透出对位的壁纸磨砂——「壁纸应用于所有页面」
                // 真正生效且无错位）
                .background(MaterialTheme.colorScheme.background)
        ) {
        // ★ 采样宿主（2026-09-22 修复）：只录背景层（壁纸+压暗；无壁纸时录 onDraw
        // 渐变底）。宿主子树内**绝不能含玻璃元素**——玻璃采样正在录制自己的层
        // = RenderNode 成环 = SIGSEGV 栈溢出（实测；聊天页同款结论：玻璃留外面防递归）
            // ★ 页面专属采样画布：转场中主页/子页并存若共用共享画布，两壳挂载节点每帧
            // 互相 record 覆盖 → 玻璃采样错乱 = 磨砂消失约 1 秒（转场结束恢复）。
            // 每页自建 rememberAppBackdrop，挂载与采样都在本页，互不干扰
            val localBackdrop = com.haoai.agent.ui.common.rememberAppBackdrop(
                wallpaper,
                dark = MaterialTheme.colorScheme.background.luminance() < 0.5f,
                baseTop = MaterialTheme.colorScheme.background,
                baseBottom = MaterialTheme.colorScheme.background
            )
            // ★ 首帧预热采样层：新页首帧采样层为空（挂载节点 draw 后才 record），
            // 转场动画中玻璃会消失几帧；组合提交时先 record 壁纸打底，首帧即磨砂
            var glassHostSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
        // 预热只做一次：切换/动画期页面每帧重组，若每次都 record 壁纸，会与宿主节点的
        // record 交替覆盖采样层 → 背景壁纸抽搐（用户实锤）
        var glassPreheated by remember { mutableStateOf(false) }
            val glassHostSizeDensity = androidx.compose.ui.platform.LocalDensity.current
            val glassHostSizeLayoutDir = androidx.compose.ui.platform.LocalLayoutDirection.current
            androidx.compose.runtime.SideEffect {
                if (!glassPreheated && wallpaper != null && glassHostSize.width > 0 && glassHostSize.height > 0) {
                    val img = wallpaper.asImageBitmap()
                    glassPreheated = true
                    glassPreheated = true
                localBackdrop.graphicsLayer.record(glassHostSizeDensity, glassHostSizeLayoutDir, glassHostSize) {
                        drawImage(
                            img,
                            dstOffset = androidx.compose.ui.unit.IntOffset.Zero,
                            dstSize = androidx.compose.ui.unit.IntSize(glassHostSize.width, glassHostSize.height)
                        )
                    }
                }
            }
            Box(Modifier.matchParentSize().appLayer(localBackdrop).onSizeChanged { glassHostSize = it }) {
                if (wallpaper != null) {
                    // v0.18.1：包装 remember 化——裸调每次重组分配新 ImageBitmap，触发整屏壁纸重绘
                    val wpImage = androidx.compose.runtime.remember(wallpaper) { wallpaper.asImageBitmap() }
                    Image(
                        bitmap = wpImage,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.matchParentSize()
                    )
                }
            }
            Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
            ) {
                // 横屏两列：内容拆成 A/B 两段本地扩展（竖屏按 A→B 原序渲染，观感不变）
                fun androidx.compose.foundation.lazy.LazyListScope.homePartA() {

                // ── 目标 A：三组玻璃卡 ───────────────────────────────────────────────
                // 规则：底色只表达"是不是异常"——常态入口一律强调色徽标，
                // 只有需要用户处理（无障碍未启用 / rootfs 损坏）才用琥珀；
                // 状态不再靠卡片底色表达，改用行尾胶囊（待处理 / 未接入 / 已就绪）。
                item { com.haoai.agent.ui.common.HaoGroupLabel("智能体能力") }
                item {
                    HaoGroup(backdrop = localBackdrop) {
                        HaoRow(
                            icon = Icons.Filled.SmartToy,
                            tintIndex = 0,
                            title = "模型大脑",
                            subtitle = run {
                                val p = settings.providers.find { it.id == settings.activeProviderId }
                                    ?: settings.providers.firstOrNull()
                                val cloud = when {
                                    p == null -> "未配置云端服务"
                                    else -> "${p.name} · ${p.model}"
                                }
                                val local = vm.llamaModelFile()?.let { " · 端侧:$it" } ?: ""
                                "$cloud$local"
                            },
                            showChevron = true,
                            onClick = { onOpenSection(9) }
                        )
                        HaoRow(
                            icon = Icons.Filled.Security,
                            tintIndex = 3,
                            tone = if (a11yOn) HaoTone.Accent else HaoTone.Warn,
                            title = "权限与自动化",
                            subtitle = permissionLabel(settings.permissionMode) +
                                if (a11yOn) " · 无障碍已启用" else " · 无障碍未启用",
                            trailing = {
                                if (!a11yOn) HaoChip("待处理", HaoTone.Warn)
                            },
                            showChevron = true,
                            divider = true,
                            onClick = { onOpenSection(10) }
                        )
                        HaoRow(
                            icon = Icons.Filled.Search,
                            tintIndex = 1,
                            title = "搜索服务",
                            subtitle = run {
                                // 新用户第一眼必须看到"默认就能联网"，否则会以为要先注册什么才能用
                                val b = settings.searchBackend
                                if (b == "builtin" || b.isBlank()) "内置免 key 引擎 · 无需配置"
                                else com.haoai.agent.agent.tools.SearchProviders.label(b)
                            },
                            trailing = {
                                val b = settings.searchBackend
                                val missing =
                                    com.haoai.agent.agent.tools.SearchProviders.needsKey(b) &&
                                        !vm.hasSearchKey(b)
                                if (missing) HaoChip("待填 Key", HaoTone.Warn)
                            },
                            showChevron = true,
                            divider = true,
                            onClick = { onOpenSection(17) }
                        )
                        HaoRow(
                            icon = Icons.Filled.AutoFixHigh,
                            tintIndex = 2,
                            title = "记忆与梦境",
                            subtitle = "${vm.homeCounts.first} 条长期记忆" +
                                if (settings.deepDream) " · 深度梦境开" else "",
                            showChevron = true,
                            divider = true,
                            onClick = { onOpenSection(11) }
                        )
                        HaoRow(
                            icon = Icons.Filled.Construction,
                            tintIndex = 4,
                            title = "技能库",
                            subtitle = "${vm.homeCounts.third} 个沉淀技能",
                            showChevron = true,
                            divider = true,
                            onClick = onOpenSkills
                        )
                    }
                }

                item { com.haoai.agent.ui.common.HaoGroupLabel("扩展与运行环境") }
                item {
                    HaoGroup(backdrop = localBackdrop) {
                        HaoRow(
                            icon = Icons.Filled.Schedule,
                            tintIndex = 1,
                            title = "定时任务",
                            subtitle = "到点自动执行并通知",
                            showChevron = true,
                            onClick = onOpenSchedules
                        )
                        val mcpServers = com.haoai.agent.agent.mcp.McpManager.listServers()
                        HaoRow(
                            icon = Icons.Filled.Extension,
                            tintIndex = 5,
                            title = "MCP 服务器",
                            subtitle = "外部工具扩展",
                            trailing = {
                                if (mcpServers.isEmpty()) {
                                    HaoChip("未接入", HaoTone.Neutral)
                                } else {
                                    HaoChip(
                                        "${mcpServers.count { it.enabled }}/${mcpServers.size} 已启用",
                                        HaoTone.Accent
                                    )
                                }
                            },
                            showChevron = true,
                            divider = true,
                            onClick = onOpenMcp
                        )
                        val pcWait = com.haoai.agent.platform.PcWatchdog.state.value
                        HaoRow(
                            icon = Icons.Filled.Computer,
                            tintIndex = 3,
                            title = "电脑联动",
                            subtitle = if (pcWait.paired) "已连 ${pcWait.base}" else "配到电脑那台 HaoAI，锁屏外也能替它点批准",
                            trailing = {
                                if (pcWait.waiting > 0) HaoChip("等 ${pcWait.waiting} 条", HaoTone.Warn)
                                else HaoChip(if (pcWait.paired) { if (pcWait.polling) "盯着" else "没在轮询" } else "未配对",
                                    if (pcWait.paired) HaoTone.Accent else HaoTone.Neutral)
                            },
                            showChevron = true,
                            divider = true,
                            onClick = onOpenPcLink
                        )
                        run {
                            // Linux 行：状态"需要你处理"才用琥珀，其余走中性；已就绪用强调色
                            val st = linuxState.statuses
                            val (label, tone) = when {
                                linuxState.installingId != null -> "安装中" to HaoTone.Neutral
                                st.isEmpty() -> "未初始化" to HaoTone.Neutral
                                st.any { it.state == com.haoai.agent.platform.sandbox.DistroManager.State.DAMAGED } ->
                                    "rootfs 已损坏" to HaoTone.Warn
                                st.any { it.state == com.haoai.agent.platform.sandbox.DistroManager.State.READY } ->
                                    "已就绪" to HaoTone.Accent
                                else -> "未安装" to HaoTone.Neutral
                            }
                            val readyName = st.firstOrNull {
                                it.state == com.haoai.agent.platform.sandbox.DistroManager.State.READY
                            }?.distro?.name
                            HaoRow(
                                icon = Icons.Filled.Terminal,
                                tintIndex = 4,
                                tone = if (tone == HaoTone.Warn) HaoTone.Warn else HaoTone.Accent,
                                title = "Linux 环境",
                                subtitle = "沙箱发行版" + if (readyName != null) " · $readyName" else "",
                                trailing = { HaoChip(label, tone) },
                                showChevron = true,
                                divider = true,
                                onClick = { onOpenSection(12) }
                            )
                        }
                    }
                }

                }
                fun androidx.compose.foundation.lazy.LazyListScope.homePartB() {
                item { com.haoai.agent.ui.common.HaoGroupLabel("通用与系统") }
                item {
                    // 第三组也是常态入口 ⇒ 同样用强调色徽标。
                    // （曾试过整组用中性灰做"视觉降级"，用户反馈"图标没有颜色，像坏了"——
                    //  规则是"底色只表达异常"，不是"按分组分层级"，灰色只该出现在
                    //  未接入/未初始化这类中性状态胶囊上。）
                    HaoGroup(backdrop = localBackdrop) {
                        HaoRow(
                            icon = Icons.Filled.Folder,
                            tintIndex = 3,
                            title = "工作空间",
                            subtitle = vm.workspaceName(),
                            showChevron = true,
                            onClick = { onOpenSection(13) }
                        )
                        HaoRow(
                            icon = Icons.Filled.AutoFixHigh,
                            tintIndex = 1,
                            title = "主题外观",
                            subtitle = "配色 · 壁纸 · 玻璃质感 · 动画风格",
                            showChevron = true,
                            divider = true,
                            onClick = { onOpenSection(20) }
                        )
                        HaoRow(
                            icon = Icons.Filled.Tune,
                            tintIndex = 5,
                            title = "通用",
                            subtitle = "后台保活 · 自定义指令 · 身份",
                            showChevron = true,
                            divider = true,
                            onClick = { onOpenSection(14) }
                        )
                        HaoRow(
                            icon = Icons.Filled.Bolt,
                            tintIndex = 2,
                            title = "工作流",
                            subtitle = "多步自动化 · 定时/开机/通知触发",
                            showChevron = true,
                            divider = true,
                            onClick = onOpenWorkflows
                        )
                        HaoRow(
                            icon = Icons.Filled.Equalizer,
                            tintIndex = 1,
                            title = "用量",
                            subtitle = "Token 用量统计 · 内部调用账本",
                            showChevron = true,
                            divider = true,
                            onClick = { onOpenSection(16) }
                        )
                        HaoRow(
                            icon = Icons.Filled.Info,
                            tintIndex = 0,
                            title = "关于",
                            subtitle = "版本信息",
                            showChevron = true,
                            divider = true,
                            onClick = { onOpenSection(15) }
                        )
                    }
                }

                }
                val homeTwoCol = androidx.compose.ui.platform.LocalConfiguration.current.let {
                    it.screenWidthDp > it.screenHeightDp
                }
                if (homeTwoCol) {
                    // 两列：横向高度低，靠并排把"一屏可见条目"翻倍——这才是横屏该有的密度
                    androidx.compose.foundation.layout.Row(Modifier.fillMaxSize()) {
                        LazyColumn(
                            state = rootListState,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                top = 56.dp,
                                start = HaoDimens.pagePaddingH,
                                end = HaoDimens.pagePaddingH,
                                bottom = 24.dp
                            ),
                            verticalArrangement = Arrangement.spacedBy(HaoDimens.groupGap)
                        ) { homePartA() }
                        LazyColumn(
                            state = androidx.compose.foundation.lazy.rememberLazyListState(),
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                top = 56.dp,
                                start = HaoDimens.pagePaddingH,
                                end = HaoDimens.pagePaddingH,
                                bottom = 24.dp
                            ),
                            verticalArrangement = Arrangement.spacedBy(HaoDimens.groupGap)
                        ) { homePartB() }
                    }
                } else {
                    LazyColumn(
                    state = rootListState,
                    modifier = Modifier.fillMaxSize(),
                    // 内容从顶栏**下方穿过**（top padding 占位，而不是 Spacer 把列表顶下去）：
                    // 原先列表被 56dp Spacer 顶到栏下方，玻璃顶栏底下永远只有壁纸，
                    // 看起来像"顶栏下有一层不透明遮罩"（用户实测）——透视要成立，
                    // 前提是先让内容滚到栏底下去
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        top = 56.dp,
                        start = HaoDimens.pagePaddingH,
                        end = HaoDimens.pagePaddingH,
                        bottom = 24.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(HaoDimens.groupGap)
                ) {
                    homePartA()
                    homePartB()
                }
                }
            }
            GlassPageBar(
                backdrop = localBackdrop,
                title = "设置",
                onBack = onBack,
                modifier = Modifier
                    .align(Alignment.TopCenter)
            )
        }
    } else {
        SectionPage(
            section = section,
            vm = vm, settings = settings, wallpaper = wallpaper,
            context = context, a11yOn = a11yOn, a11yTick = a11yTick,
            wpVersion = wpVersion, linuxState = linuxState,
            showScan = showScan, onShowScan = { showScan = true },
            pendingDelete = pendingDelete, onPendingDelete = { pendingDelete = it },
            purposePicker = purposePicker, onPickPurposeModel = { purposePicker = it },
            showDreamPicker = showDreamPicker, onPickDreamModel = { showDreamPicker = true },
            confirmWpClear = confirmWpClear, onConfirmWpClear = { confirmWpClear = true },
            confirmClearLedger = confirmClearLedger, onConfirmClearLedger = { confirmClearLedger = true },
            treePickerLaunch = { treePicker.launch(null) },
            onOpenMemories = onOpenMemories,
            onOpenSearchCatalog = { section = "searchcat" },
            onEditCaps = { pid, mid -> capsTarget = pid to mid },
            onBack = { section = "" }
        )
    }

    draft?.let { d ->
        if (vm.wizardOpen) {
            // 添加走 3 步向导（选服务商 → 连接 → 选模型）；测试/拉取/保存复用同一 draft 管线
            ProviderWizardDialog(
                backdrop = backdrop,
                draft = d,
                draftError = vm.draftError,
                testing = vm.testing,
                testResult = vm.testResult,
                fetchingModels = vm.fetchingModels,
                modelChoices = vm.modelChoices,
                onChange = { vm.updateDraft(it) },
                onPreset = { vm.applyPreset(it) },
                onFetchModels = { vm.fetchModelList() },
                onPickModel = { vm.pickModel(it) },
                onTest = { vm.testDraftConnection() },
                onSave = {
                    vm.saveDraft()
                    vm.closeWizard()
                },
                onDismiss = {
                    vm.cancelDraft()
                    vm.closeWizard()
                }
            )
        } else {
            ProviderDialog(
                backdrop = backdrop,
                draft = d,
                draftError = vm.draftError,
                testing = vm.testing,
                testResult = vm.testResult,
                fetchingModels = vm.fetchingModels,
                modelChoices = vm.modelChoices,
                detectingCaps = vm.detectingCaps,
                detectResult = vm.detectResult,
                onChange = { vm.updateDraft(it) },
                onPreset = { vm.applyPreset(it) },
                onFetchModels = { vm.fetchModelList() },
                onPickModel = { vm.pickModel(it) },
                onDetectCaps = { vm.detectCapabilities() },
                onSave = { vm.saveDraft() },
                onTest = { vm.testDraftConnection() },
                onDismiss = { vm.cancelDraft() }
            )
        }
    }

    // 模型能力编辑弹层（设计稿 ③）：输入/输出模态勾选 + 能力来源 + 恢复自动检测
    capsTarget?.let { (pid, mid) ->
        val p = vm.providers().find { it.id == pid }
        if (p == null) {
            capsTarget = null
        } else {
            ModelCapsDialog(
                backdrop = backdrop,
                vm = vm,
                providerId = pid,
                modelId = mid,
                onDismiss = { capsTarget = null }
            )
        }
    }

    if (showScan) {
        val scanned = vm.scannedDeviceModels
        val canScan = !vm.scanningModels && vm.scannedDeviceModels == null
        com.haoai.agent.ui.common.GlassAlertDialog(
            backdrop = backdrop,
            title = "手机上的模型文件",
            onDismiss = { vm.clearDeviceScan(); showScan = false },
            confirmLabel = if (canScan) "开始扫描" else "关闭",
            onConfirm = { if (canScan) vm.scanDeviceModels() else showScan = false },
            dismissLabel = "取消"
        ) {
            Column {
                if (vm.scanningModels) {
                    Text("扫描中…（Download / Documents / models 等目录）")
                } else if (scanned.isNullOrEmpty()) {
                    Text(
                        "未找到大体积 GGUF 文件。请确认模型位置；若放在应用专属目录之外的受限目录，" +
                            "需先在「权限与自动化 → 系统权限」里授予「文件管理（所有文件访问）」。",
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    Text(
                        "点击直接引用原文件（不复制、不占双份空间）：",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Column(Modifier.heightIn(max = 360.dp).padding(top = 6.dp)) {
                        scanned.forEach { f ->
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        vm.useDeviceModel(f.absolutePath)
                                        showScan = false
                                    }
                                    .padding(vertical = 8.dp)
                            ) {
                                Text(
                                    f.name,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    maxLines = 1
                                )
                                Text(
                                    "${f.length() / (1024 * 1024)} MB · ${f.parent?.removePrefix("/storage/emulated/0/") ?: ""}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // 删除供应商确认：红色确认键，展示供应商名与模型；
    // 删的是当前服务时写明将自动切换到哪个——fallback 是列表里下一个云端服务，用户该知道
    pendingDelete?.let { pid ->
        val p = vm.providers().find { it.id == pid }
        val isCurrent = p?.id == settings.activeProviderId
        val remaining = vm.providers().filterNot { it.id == pid }
            .filter { it.id != com.haoai.agent.platform.llama.LlamaServerController.LOCAL_PROVIDER_ID }
        val nextUp = remaining.firstOrNull()?.name
        com.haoai.agent.ui.common.GlassAlertDialog(
            backdrop = backdrop,
            title = "删除模型供应商",
            onDismiss = { pendingDelete = null },
            confirmLabel = "删除",
            onConfirm = {
                vm.deleteProvider(pid)
                pendingDelete = null
            },
            dismissLabel = "取消",
            danger = true
        ) {
            Column {
                Text(
                    "确定删除「${p?.name ?: "该服务"}」（${p?.model ?: ""}）吗？删除后不可恢复。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f)
                )
                if (isCurrent) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        // 与 SettingsViewModel.deleteProvider 的回退逻辑一致：优先下一个云端服务
                        if (nextUp != null) "「${p?.name}」正在使用中，删除后将自动切换到「$nextUp」。"
                        else "「${p?.name}」正在使用中，删除后没有其他云端服务可用。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }

    // 「用量」清空账本确认：必须渲染在根级（LazyColumn item 内 fillMaxSize 遮罩会被约束）
    if (confirmClearLedger) {
        com.haoai.agent.ui.common.GlassAlertDialog(
            backdrop = backdrop,
            title = "清空用量账本",
            confirmLabel = "清空",
            dismissLabel = "取消",
            danger = true,
            onConfirm = {
                confirmClearLedger = false
                com.haoai.agent.data.UsageLedger.clearAll()
            },
            onDismiss = { confirmClearLedger = false }
        ) {
            Text(
                "全部 Token 用量与工具调用记录将被删除，无法恢复。",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }

    // 「模型大脑 → 模型切换」选择窗（根级渲染，点行弹窗选择）
    if (purposePicker != null) {
        val purpose = purposePicker!!
        val current = when (purpose) {
            "title" -> settings.titleProviderId
            "memory" -> settings.memoryExtractProviderId
            "chat" -> settings.chatPurposeId
            else -> settings.summarizeProviderId
        }
        // 选项构造：供应商级（"pid"）+ 同供应商多模型展开为模型级（"pid|modelId"）。
        // 聊天会话需要精确到模型（glm-5.3-flash 配了多个模型时能选到具体那个）；
        // 辅助任务用供应商级即可（用该供应商的默认模型）
        val cloudProviders = settings.providers
            .filter { it.id != com.haoai.agent.platform.llama.LlamaServerController.LOCAL_PROVIDER_ID }
        val options: List<Pair<String, String>> = listOf("" to "主模型") +
            cloudProviders.flatMap { p ->
                val models = (listOf(p.model).filter { it.isNotBlank() } + p.models.map { it.id }).distinct()
                if (purpose == "chat" && models.size > 1) {
                    // 多模型供应商：展开为模型级选项
                    models.map { m -> "${p.id}|$m" to "${p.name} · $m" }
                } else {
                    listOf(p.id to p.name)
                }
            } + listOf("local" to "端侧（llama.cpp）")
        // 点6：备用链（有序多选）——主目标失败按勾选顺序降级
        val fallback = when (purpose) {
            "title" -> settings.titleFallbackIds
            "memory" -> settings.memoryExtractFallbackIds
            "chat" -> settings.chatFallbackIds
            else -> settings.summarizeFallbackIds
        }
        com.haoai.agent.ui.common.GlassAlertDialog(
            backdrop = backdrop,
            // 标题带任务名：进出弹窗不丢「在配哪个任务」的上下文
            title = when (purpose) {
                "chat" -> "聊天会话 · 使用哪个模型"
                "title" -> "会话标题 · 使用哪个模型"
                "memory" -> "记忆提取 · 使用哪个模型"
                else -> "上下文压缩 · 使用哪个模型"
            },
            confirmLabel = "完成",
            // 单主操作：完成键通栏（设计稿样式）
            fullWidthConfirm = true,
            onConfirm = { purposePicker = null },
            onDismiss = { purposePicker = null }
        ) {
            Column {
                // ── 分区：主目标（设计稿 grpl 分组标题 + 单选圆圈）──
                Text(
                    "主目标",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(2.dp))
                options.forEach { (id, name) ->
                    val selected = id == current
                    val sub = when {
                        id.isBlank() -> "跟随当前供应商默认"
                        id == "local" -> "本机 llama.cpp"
                        id.contains('|') -> "指定模型"
                        else -> settings.providers.find { it.id == id }?.model ?: ""
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 40.dp)
                            .then(
                                if (selected) Modifier.background(
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                                    RoundedCornerShape(11.dp)
                                ) else Modifier
                            )
                            .clickable {
                                vm.setPurposeModel(purpose, id)
                                // 主目标从备用链剔除，避免自我降级；
                                // 剔除了东西才提示，不让用户已勾的链被静默改动
                                if (id in fallback) {
                                    android.widget.Toast.makeText(
                                        context,
                                        "已将「$name」设为主目标，并从备用链移出",
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                }
                                vm.setPurposeFallback(purpose, fallback.filterNot { it == id })
                                purposePicker = null
                            }
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 单选圆圈（设计稿 radio）：选中=主题色实心点
                        Box(
                            Modifier
                                .size(19.dp)
                                .border(
                                    1.5.dp,
                                    if (selected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.30f),
                                    CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            if (selected) {
                                Box(
                                    Modifier
                                        .size(9.dp)
                                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                                )
                            }
                        }
                        Spacer(Modifier.size(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                name,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onBackground,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                            )
                            if (sub.isNotBlank()) {
                                Text(
                                    sub,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        if (selected) {
                            Text(
                                "当前",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                // ── 分区：备用链（数字圆标 + 移出）──
                Text(
                    "备用链 · 按序降级",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(2.dp))
                val candidates = options.filter { it.first.isNotBlank() && it.first != current }
                if (candidates.isEmpty()) {
                    Text(
                        "暂无可用备用（先在上方选一个主目标）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
                candidates.forEach { (id, name) ->
                    val on = id in fallback
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 40.dp)
                            .clickable {
                                vm.setPurposeFallback(
                                    purpose,
                                    if (on) fallback - id else fallback + id
                                )
                            }
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 带数字圆标对齐设计稿：选中=主题色实心圆+白字序号（顺序一眼可读），
                        // 未选=细描边空圈。替代 Checkbox（勾选态读不出「顺序」语义）
                        Box(
                            Modifier
                                .size(22.dp)
                                .background(
                                    if (on) MaterialTheme.colorScheme.primary else Color.Transparent,
                                    CircleShape
                                )
                                .border(
                                    1.5.dp,
                                    if (on) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.30f),
                                    CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            if (on) Text(
                                "${fallback.indexOf(id) + 1}",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                        Spacer(Modifier.size(10.dp))
                        Text(
                            name,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.weight(1f)
                        )
                        if (on) Text(
                            "移出",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (fallback.size >= 2) {
                    Text(
                        "主目标失败时按 ${fallback.map { fallback.indexOf(it) + 1 }.joinToString(" → ")} 顺序尝试",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }

    // 记忆专用端侧模型选择：跟随聊天模型 / 应用目录内任一 GGUF
    if (showDreamPicker) {
        var picked by androidx.compose.runtime.remember { mutableStateOf(settings.dreamLocalModelFile) }
        val dreamModels = androidx.compose.runtime.remember { vm.llamaModelPaths() }
        com.haoai.agent.ui.common.GlassAlertDialog(
            backdrop = backdrop,
            title = "记忆专用端侧模型",
            onDismiss = { showDreamPicker = false },
            confirmLabel = "确定",
            onConfirm = {
                vm.setDreamLocalModelFile(picked)
                showDreamPicker = false
            },
            dismissLabel = "取消"
        ) {
            Column {
                if (dreamModels.isEmpty()) {
                    Text(
                        "应用目录内没有可用模型。请先在「模型大脑」里下载或扫描 GGUF 模型。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                ModelPickRow(
                    selected = picked == null,
                    title = "跟随端侧聊天模型",
                    subtitle = "与对话使用同一个模型",
                    onClick = { picked = null }
                )
                dreamModels.forEach { p ->
                    ModelPickRow(
                        selected = picked == p,
                        title = p.substringAfterLast('/'),
                        subtitle = p,
                        onClick = { picked = p }
                    )
                }
            }
        }
    }

    if (confirmWpClear) {
        com.haoai.agent.ui.common.GlassAlertDialog(
            backdrop = backdrop,
            title = "恢复默认背景",
            onDismiss = { confirmWpClear = false },
            confirmLabel = "清除",
            onConfirm = {
                vm.clearWallpaper(context)
                wpVersion = false
                confirmWpClear = false
            },
            dismissLabel = "取消"
        ) {
            Text("将清除自定义壁纸并恢复默认渐变背景。")
        }
    }

    // Linux 发行版安装：预填官方源，可改为自定义镜像；sha256 始终按官方清单校验
    linuxState.urlEditId?.let { id ->
        val distro = vm.distros.distroById(id)
        com.haoai.agent.ui.common.GlassAlertDialog(
            backdrop = backdrop,
            title = "安装 ${distro?.name ?: "发行版"}",
            onDismiss = { linuxState.urlEditId = null },
            confirmLabel = "开始安装",
            onConfirm = {
                val d = distro ?: return@GlassAlertDialog
                linuxState.urlEditId = null
                linuxState.installingId = id
                linuxState.installError = null
                linuxState.progressRead = 0
                linuxState.progressTotal = 0
                val custom = linuxState.urlDraft
                scope.launch {
                    val r = vm.distros.install(d, onProgress = { read, total ->
                        linuxState.progressRead = read
                        linuxState.progressTotal = total
                    }, customUrl = custom)
                    linuxState.installingId = null
                    r.fold(
                        onSuccess = {
                            com.haoai.agent.agent.tools.shell.ProotBackend.invalidate()
                            com.haoai.agent.agent.tools.shell.SandboxProbe.invalidate()
                            // stdio MCP 服务器（3.5）待就绪状态自动重连
                            com.haoai.agent.agent.mcp.McpManager.onSandboxChanged(scope)
                            linuxState.refresh(vm.distros)
                        },
                        onFailure = { linuxState.installError = it.message ?: "安装失败" }
                    )
                }
            },
            dismissLabel = "取消"
        ) {
            Column {
                Text(
                    "当前架构：${com.haoai.agent.platform.sandbox.Proot.abiOf()}。" +
                        "下载完成后按官方 sha256 清单校验，不匹配即拒绝解压。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                androidx.compose.material3.OutlinedTextField(
                    value = linuxState.urlDraft,
                    onValueChange = { linuxState.urlDraft = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("下载 URL（默认官方源，可换镜像）") },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    colors = com.haoai.agent.ui.common.glassFieldColors()
                )
            }
        }
    }

    // Linux 发行版删除确认：展示占用空间
    linuxState.pendingDeleteId?.let { id ->
        val stat = linuxState.statuses.find { it.distro.id == id }
        com.haoai.agent.ui.common.GlassAlertDialog(
            backdrop = backdrop,
            title = "删除 ${stat?.distro?.name ?: "发行版"}",
            onDismiss = { linuxState.pendingDeleteId = null },
            confirmLabel = "删除",
            onConfirm = {
                linuxState.pendingDeleteId = null
                scope.launch {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        vm.distros.delete(id)
                    }
                    com.haoai.agent.agent.tools.shell.ProotBackend.invalidate()
                    com.haoai.agent.agent.tools.shell.SandboxProbe.invalidate()
                    // 删除发行版后 stdio MCP 服务器会因沙箱缺失回 PendingReady（connectServer 内判定）
                    com.haoai.agent.agent.mcp.McpManager.onSandboxChanged(scope)
                    linuxState.refresh(vm.distros)
                }
            },
            dismissLabel = "取消",
            danger = true
        ) {
            Text(
                "将删除整个 rootfs（占用 ${formatDistroSize(stat?.sizeBytes ?: 0)}），删除后不可恢复。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f)
            )
        }
    }

    // Linux 安装失败提示
    linuxState.installError?.let { err ->
        com.haoai.agent.ui.common.GlassAlertDialog(
            backdrop = backdrop,
            title = "安装失败",
            onDismiss = { linuxState.installError = null },
            confirmLabel = "知道了",
            onConfirm = { linuxState.installError = null }
        ) {
            Text(
                err,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f)
            )
        }
    }
}

private fun permissionLabel(mode: PermissionMode): String = when (mode) {
    PermissionMode.ALWAYS_ASK -> "全部询问"
    PermissionMode.ASK_WRITES -> "写入时询问"
    PermissionMode.YOLO -> "全自动"
}

private fun sectionTitle(section: String): String = when (section) {
    "brain" -> "模型大脑"
    "privacy" -> "权限与自动化"
    "search" -> "搜索服务"
    "searchcat" -> "添加搜索服务"
    "memory" -> "记忆与梦境"
    "workspace" -> "工作空间"
    "linux" -> "Linux 环境"
    "general" -> "通用"
    "theme" -> "主题外观"
    "about" -> "关于"
    "usage" -> "用量"
    else -> ""
}

/**
 * 设置 section 子页渲染（原 SettingsScreen else 分支提取）：
 * 主页内嵌与独立 screen 模式共用同一渲染，保证视觉/行为完全一致。
 */
@Composable
private fun SectionPage(
    section: String,
    vm: SettingsViewModel,
    settings: AppSettings,
    // ⚠️ 2026-10-08 修复：本参数（MainActivity 的 settingsBackdrop/plainBackdrop
    // 共享画布）从未被 appLayer 挂载录制，玻璃采样到空图层 = 磨砂/折射滑杆
    // 对各子页卡片全无效果（实测 blur 0↔16 diff=0；主页正常因为用的本页画布）。
    // 现 SectionPage 内所有玻璃统一用下方自建的 localBackdrop，此参数已移除。
    wallpaper: android.graphics.Bitmap?,
    context: android.content.Context,
    a11yOn: Boolean,
    a11yTick: Int,
    wpVersion: Boolean,
    linuxState: LinuxEnvState,
    showScan: Boolean,
    onShowScan: () -> Unit,
    pendingDelete: String?,
    onPendingDelete: (String?) -> Unit,
    purposePicker: String?,
    onPickPurposeModel: (String?) -> Unit,
    showDreamPicker: Boolean,
    onPickDreamModel: () -> Unit,
    confirmWpClear: Boolean,
    onConfirmWpClear: () -> Unit,
    confirmClearLedger: Boolean,
    onConfirmClearLedger: () -> Unit,
    treePickerLaunch: () -> Unit,
    onOpenMemories: () -> Unit,
    onOpenSearchCatalog: () -> Unit,
    onEditCaps: (String, String) -> Unit = { _, _ -> },
    onBack: () -> Unit
) {
    // 色盘弹窗状态必须提在根级：LazyColumn item 内 fillMaxSize 遮罩会被 item 约束，
    // 渲染成内容流内的一块（弹窗不能渲染在 LazyColumn item 内——同账本确认弹窗教训）
    var pickerSeed by remember { mutableStateOf<String?>(null) }
    var pickerSlot by remember { mutableIntStateOf(-1) }
    var keyExportConfirm by remember { mutableStateOf(false) }
    // 我的档案（聊天社交化 2026-10-07）：编辑弹窗状态提在根级（同色盘教训，
    // LazyColumn item 内 fillMaxSize 遮罩会被约束）
    var showMyProfile by remember { mutableStateOf(false) }
    var myEditName by remember { mutableStateOf("") }
    var myEditEmoji by remember { mutableStateOf("") }
    var myEditGradient by remember { mutableIntStateOf(1) }
    var myEditBio by remember { mutableStateOf("") }
    var myEditImagePath by remember { mutableStateOf<String?>(null) }
    val myAvatarPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) vm.importMyAvatar(uri) { path ->
            if (path != null) { myEditImagePath = path; myEditEmoji = "" }
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            // 独立 screen 化后本页参与平移转场：实底垫背必须；
            // 全局壁纸开时铺对齐壁纸（与 backdrop 采样同源同位）
            .background(MaterialTheme.colorScheme.background)
    ) {
        // ★ 采样宿主（2026-09-22 修复）：只录背景层（壁纸+压暗；无壁纸时录 onDraw
        // 渐变底）。宿主子树内**绝不能含玻璃元素**——玻璃采样正在录制自己的层
        // = RenderNode 成环 = SIGSEGV 栈溢出（实测；聊天页同款结论：玻璃留外面防递归）
        // ★ 页面专属采样画布：转场中主页/子页并存若共用共享画布，两壳挂载节点每帧
        // 互相 record 覆盖 → 玻璃采样错乱 = 磨砂消失约 1 秒（转场结束恢复）。
        // 每页自建 rememberAppBackdrop，挂载与采样都在本页，互不干扰
        val localBackdrop = com.haoai.agent.ui.common.rememberAppBackdrop(
            wallpaper,
            dark = MaterialTheme.colorScheme.background.luminance() < 0.5f,
            baseTop = MaterialTheme.colorScheme.background,
            baseBottom = MaterialTheme.colorScheme.background
        )
        // ★ 首帧预热采样层：新页首帧采样层为空（挂载节点 draw 后才 record），
        // 转场动画中玻璃会消失几帧；组合提交时先 record 壁纸打底，首帧即磨砂
        var glassHostSize2 by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
        // 预热只做一次：切换/动画期页面每帧重组，若每次都 record 壁纸，会与宿主节点的
        // record 交替覆盖采样层 → 背景壁纸抽搐（用户实锤）
        var glassPreheated2 by remember { mutableStateOf(false) }
        val glassHostSize2Density = androidx.compose.ui.platform.LocalDensity.current
        val glassHostSize2LayoutDir = androidx.compose.ui.platform.LocalLayoutDirection.current
        androidx.compose.runtime.SideEffect {
            if (!glassPreheated2 && wallpaper != null && glassHostSize2.width > 0 && glassHostSize2.height > 0) {
                val img = wallpaper.asImageBitmap()
                glassPreheated2 = true
                localBackdrop.graphicsLayer.record(glassHostSize2Density, glassHostSize2LayoutDir, glassHostSize2) {
                    drawImage(
                        img,
                        dstOffset = androidx.compose.ui.unit.IntOffset.Zero,
                        dstSize = androidx.compose.ui.unit.IntSize(glassHostSize2.width, glassHostSize2.height)
                    )
                }
            }
        }
        Box(Modifier.matchParentSize().appLayer(localBackdrop).onSizeChanged { glassHostSize2 = it }) {
            if (wallpaper != null) {
                val wpImage = androidx.compose.runtime.remember(wallpaper) { wallpaper.asImageBitmap() }
                Image(
                    bitmap = wpImage,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize()
                )
            }
        }
        // 标题栏底边（窗口坐标）：主题外观「玻璃质感」预览的吸顶停靠线——
        // 停到栏底 = 无缝不露缝、预览顶不被栏压住（状态栏 43dp + 栏体压到 ~83dp，
        // 固定 54dp 会把预览顶压进栏里，2026-10-08 真机实测）
        var barBottomPx by remember { mutableStateOf(Float.MAX_VALUE) }
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            val listState = androidx.compose.foundation.lazy.rememberLazyListState()
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                // 同首页：top padding 占位让内容从顶栏下穿过（透视成立的前提）
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    top = 56.dp,
                    start = HaoDimens.pagePaddingH,
                    end = HaoDimens.pagePaddingH,
                    bottom = 24.dp
                ),
                verticalArrangement = Arrangement.spacedBy(HaoDimens.groupGap)
            ) {
                when (section) {
                    "brain" -> {
                        brainItems(vm, settings, localBackdrop, onDeleteRequest = { onPendingDelete(it) }, onPickPurposeModel = { onPickPurposeModel(it) }, onEditCaps = onEditCaps)
                        localItems(vm, localBackdrop, onOpenScan = onShowScan)
                    }
                    "privacy" -> privacyItems(vm, settings, context, a11yOn, localBackdrop)
                    "search" -> searchItems(
                        vm, settings, context, localBackdrop,
                        onOpenCatalog = onOpenSearchCatalog
                    )
                    "searchcat" -> item {
                        SearchCatalogPage(
                            vm = vm,
                            settings = settings,
                            backdrop = localBackdrop,
                            onBack = onBack
                        )
                    }
                    "memory" -> memoryItems(vm, settings, onOpenMemories, localBackdrop, onPickDreamModel = onPickDreamModel)
                    "workspace" -> workspaceItems(vm, localBackdrop) { treePickerLaunch() }
                    "linux" -> linuxItems(vm, localBackdrop, linuxState)
                    "general" -> generalItems(
                        vm, settings, context, localBackdrop,
                        wpVersion, onWpVersionChange = { },
                        onRequestClearWallpaper = onConfirmWpClear,
                        onWpChanged = { },
                        onOpenColorPicker = { hex, slot -> pickerSlot = slot; pickerSeed = hex },
                        onRequestKeyExportConfirm = { keyExportConfirm = true },
                        onEditMyProfile = {
                            myEditName = settings.myName.ifBlank { "我" }
                            myEditEmoji = settings.myEmoji
                            myEditGradient = settings.myGradient
                            myEditBio = settings.myBio
                            myEditImagePath = settings.myAvatarPath
                            showMyProfile = true
                        }
                    )
                    "theme" -> themeItems(
                        vm, settings, context, localBackdrop,
                        barBottomPx,
                        wpVersion, onWpVersionChange = { },
                        onRequestClearWallpaper = onConfirmWpClear,
                        onOpenColorPicker = { hex, slot -> pickerSlot = slot; pickerSeed = hex }
                    )
                    "about" -> aboutItems(vm, settings, localBackdrop)
                    "usage" -> usageItems(vm, settings, localBackdrop, onRequestClearLedger = onConfirmClearLedger)
                }
            }
        }
        GlassPageBar(
            backdrop = localBackdrop,
            title = sectionTitle(section),
            onBack = onBack,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .onGloballyPositioned { barBottomPx = it.boundsInWindow().bottom }
        )
        if (pickerSeed != null) {
            ColorPickerDialog(
                backdrop = localBackdrop,
                settings = settings,
                initialHex = pickerSeed.orEmpty(),
                initialSlot = pickerSlot,
                vm = vm,
                onDismiss = { pickerSeed = null }
            )
        }
        if (keyExportConfirm) {
            val named = (settings.providers.filter { it.apiKeyCipher.isNotBlank() }
                .map { it.name.ifBlank { it.model } } +
                // 搜索服务的 key 同样会出包，计数漏了它就是"承诺包含 A 家、实际只给 A-1 家"
                settings.searchApiKeyCiphers.keys
                    .filter { it.isNotBlank() }
                    .map { com.haoai.agent.agent.tools.SearchProviders.label(it) })
                .filter { it.isNotBlank() }
            com.haoai.agent.ui.common.GlassAlertDialog(
                backdrop = localBackdrop,
                title = "把 API Key 一起导出？",
                onDismiss = { keyExportConfirm = false },
                confirmLabel = "确认包含",
                onConfirm = { vm.setBackupIncludeKeys(true); keyExportConfirm = false },
                dismissLabel = "不含 Key"
            ) {
                Column {
                    Text(
                        "包内将以明文写入这 ${named.size} 家的密钥：${named.joinToString("、")}" +
                            "。任何拿到这个 zip 的人都能直接用它消费你的额度。",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "建议只在换机迁移时勾选，迁完把备份包删掉。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        // 我的档案编辑弹窗（聊天社交化 2026-10-07）：字段区与聊天页 Agent 档案共用
        if (showMyProfile) {
            com.haoai.agent.ui.common.GlassAlertDialog(
                backdrop = localBackdrop,
                title = "编辑我的档案",
                onDismiss = { showMyProfile = false },
                confirmLabel = "保存",
                onConfirm = {
                    vm.updateMyProfile(myEditName, myEditEmoji, myEditGradient, myEditBio, myEditImagePath)
                    showMyProfile = false
                },
                dismissLabel = "取消"
            ) {
                com.haoai.agent.ui.chat.ProfileEditFields(
                    backdrop = localBackdrop,
                    name = myEditName, onName = { myEditName = it },
                    emoji = myEditEmoji, onEmoji = { myEditEmoji = it },
                    gradient = myEditGradient, onGradient = { myEditGradient = it },
                    bio = myEditBio, onBio = { myEditBio = it },
                    imagePath = myEditImagePath, onRemoveImage = { myEditImagePath = null },
                    onPickImage = { myAvatarPicker.launch("image/*") },
                    nameLabel = "昵称"
                )
            }
        }
    }
}

/** Linux 环境区块的界面状态（发行版列表/安装进度/弹窗路由），由设置页持有。 */
private class LinuxEnvState {
    var statuses by androidx.compose.runtime.mutableStateOf<
        List<com.haoai.agent.platform.sandbox.DistroManager.Status>>(emptyList())
    var installingId by androidx.compose.runtime.mutableStateOf<String?>(null)
    var progressRead by androidx.compose.runtime.mutableStateOf(0L)
    var progressTotal by androidx.compose.runtime.mutableStateOf(0L)
    var installError by androidx.compose.runtime.mutableStateOf<String?>(null)
    var pendingDeleteId by androidx.compose.runtime.mutableStateOf<String?>(null)
    var urlEditId by androidx.compose.runtime.mutableStateOf<String?>(null)
    var urlDraft by androidx.compose.runtime.mutableStateOf("")

    suspend fun refresh(dm: com.haoai.agent.platform.sandbox.DistroManager) {
        statuses = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { dm.statuses() }
    }
}

private fun formatDistroSize(bytes: Long): String = when {
    bytes >= 1L shl 20 -> "%.1f MB".format(bytes / 1048576.0)
    bytes >= 1L shl 10 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}

// ---------- 各子页 ----------

private fun LazyListScope.brainItems(
    vm: SettingsViewModel,
    settings: AppSettings,
    backdrop: com.kyant.backdrop.backdrops.LayerBackdrop,
    onDeleteRequest: (String) -> Unit = {},
    onPickPurposeModel: (String) -> Unit = {},
    onEditCaps: (String, String) -> Unit = { _, _ -> }
) {
    item { SectionTitle("模型供应商") }
    item {
        val ps = settings.providers.filter { it.id != com.haoai.agent.platform.llama.LlamaServerController.LOCAL_PROVIDER_ID }
        val activeId = settings.activeProviderId
        // 供应商视角（用户反馈）：行=供应商+模型数徽标+能力徽章；点击行展开该供应商的
        // 全部模型清单，点模型即切换默认。行内不再放编辑/删除按钮（视觉噪音、功能重复）——
        // 编辑/删除收进长按菜单与展开区「编辑供应商」文字链（设计稿 ① 屏）。
        var expandedId by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
        Column {
            GlassGroup(backdrop) {
                Column(Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) {
                    ps.forEachIndexed { idx, p ->
                        val expanded = expandedId == p.id
                        val isActive = p.id == activeId
                        var menuOpen by androidx.compose.runtime.remember(p.id) { mutableStateOf(false) }
                        Column {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .then(
                                        if (isActive) Modifier.background(
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                                            RoundedCornerShape(12.dp)
                                        ) else Modifier
                                    )
                                    .combinedClickable(
                                        onClick = { expandedId = if (expanded) null else p.id },
                                        onLongClick = { menuOpen = true }
                                    )
                                    .padding(start = 8.dp, end = 10.dp, top = 9.dp, bottom = 9.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = isActive,
                                    onClick = { vm.setActiveProvider(p.id) }
                                )
                                Column(Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(p.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                        // 去重计数：models 里含与默认同 ID 的能力条目，不能简单 size+1
                                        val modelCount = p.modelIds().distinct().size
                                        if (modelCount > 1) {
                                            Spacer(Modifier.size(8.dp))
                                            Text(
                                                "$modelCount 个模型",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier
                                                    .background(
                                                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.10f),
                                                        RoundedCornerShape(5.dp)
                                                    )
                                                    .padding(horizontal = 6.dp, vertical = 1.dp)
                                            )
                                        }
                                    }
                                    Text(
                                        p.model,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1
                                    )
                                    // 能力徽章外显（设计稿 ①）：图/音/视/工具/思考，划线=明确不支持
                                    ProviderCapBadges(p)
                                }
                                // 展开指示：点击行=展开模型清单（主操作）；编辑/删除走长按菜单
                                Icon(
                                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                    contentDescription = if (expanded) "收起模型列表" else "展开模型列表",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                                // 长按菜单：编辑 / 删除（删除仍走原确认对话框）
                                androidx.compose.material3.DropdownMenu(
                                    expanded = menuOpen,
                                    onDismissRequest = { menuOpen = false }
                                ) {
                                    androidx.compose.material3.DropdownMenuItem(
                                        text = { Text("编辑供应商") },
                                        onClick = { menuOpen = false; vm.editProvider(p) }
                                    )
                                    if (p.id != com.haoai.agent.platform.llama.LlamaServerController.LOCAL_PROVIDER_ID) {
                                        androidx.compose.material3.DropdownMenuItem(
                                            text = { Text("删除供应商", color = MaterialTheme.colorScheme.error) },
                                            onClick = { menuOpen = false; onDeleteRequest(p.id) }
                                        )
                                    }
                                }
                            }
                            // 展开区：该供应商的全部模型按「加入顺序」渲染——默认行原位
                            // 高亮（2026-09-11 用户反馈：旧版把默认行置顶 + filter 重排，
                            // 切一次默认整个列表顺序就乱）。点行=切换默认（只改指针、
                            // 不搬条目，VM 侧 setProviderDefaultModel 已是保序语义）；
                            // ✕=移除备选，默认行的 ✕ 隐藏（先切走才能删）；
                            // 单模型能力微调走长按菜单（见下方 LongPressMenu）。
                            if (expanded) {
                                Column(Modifier.padding(start = 52.dp, end = 8.dp, bottom = 8.dp)) {
                                    // 渲染序列 = p.models 原样保序；默认模型不在 models
                                    // （老数据仅单模型字段）时才补在最前。⚠️ 2026-09-11 修：
                                    // 旧条件把默认条目从渲染列表里排掉了，导致「3 个模型只显示
                                    // 2 个、默认那个只在供应商标题上」——这里必须全量渲染
                                    val orderedModels = buildList {
                                        if (p.model.isNotBlank() && p.models.none { it.id == p.model }) add(p.model)
                                        p.models.forEach { add(it.id) }
                                    }.distinct()
                                    orderedModels.forEach { mId ->
                                        val isDefault = mId == p.model
                                        ModelSwitchRow(
                                            id = mId,
                                            isDefault = isDefault,
                                            enabled = true,
                                            onClick = { vm.setProviderDefaultModel(p.id, mId) },
                                            onRemove = if (isDefault) null
                                                else { { vm.removeProviderModel(p.id, mId) } },
                                            onEditCaps = if (isDefault) null else { { onEditCaps(p.id, mId) } }
                                        )
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                        Text(
                                            "编辑供应商",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(8.dp))
                                                .clickable { vm.editProvider(p) }
                                                .padding(horizontal = 8.dp, vertical = 6.dp)
                                        )
                                        // 批量检测：目录数据源 models.dev，手动覆盖的模型不被覆盖
                                        Text(
                                            if (vm.detectingCaps) "检测中…" else "检测全部能力",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.SemiBold,
                                            color = if (vm.detectingCaps) MaterialTheme.colorScheme.onSurfaceVariant
                                            else MaterialTheme.colorScheme.primary,
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(8.dp))
                                                .clickable(enabled = !vm.detectingCaps) { vm.detectAllCapabilities(p.id) }
                                                .padding(horizontal = 8.dp, vertical = 6.dp)
                                        )
                                    }
                                }
                            }
                        }
                        if (idx != ps.lastIndex) {
                            HorizontalDivider(
                                Modifier.padding(start = 52.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f)
                            )
                        }
                    }
                    // 添加项作为列表末行：与列表同容器，符合设置页惯例
                    // （原先全宽胶囊浮在玻璃组外面，与列表无归属关系）
                    // 2026-09-25：点击改为打开 3 步添加向导（原三段面板保留给编辑）
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { vm.startWizard() }
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.size(10.dp))
                        Text(
                            "添加模型供应商",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
            com.haoai.agent.ui.common.HaoNote(
                backdrop = backdrop,
                text = "点「添加」走 3 步向导（选服务商 → 连接 → 选模型）· 点行展开模型清单并切换默认 · 点「能力」勾选输入/输出模态 · 长按行或展开区「编辑供应商」可测试连接、拉取列表与配置密钥。",
                modifier = Modifier.padding(vertical = 4.dp)
            )
        }
    }
    item { SectionTitle("模型切换") }
    item {
        // purpose 配置行（用户反馈：原「内部任务模型」不够细分）：
        // 新增「聊天会话」行；purpose 值可为 "providerId|modelId" 精确到同供应商的具体模型
        Column {
            GlassGroup(backdrop) {
                Column(Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) {
                    purposeRow("聊天会话", "chat", settings.chatPurposeId, settings, vm, backdrop) { onPickPurposeModel(it) }
                    purposeRow("会话标题", "title", settings.titleProviderId, settings, vm, backdrop) { onPickPurposeModel(it) }
                    purposeRow("记忆提取", "memory", settings.memoryExtractProviderId, settings, vm, backdrop) { onPickPurposeModel(it) }
                    purposeRow("上下文压缩", "summarize", settings.summarizeProviderId, settings, vm, backdrop) { onPickPurposeModel(it) }
                    purposeRow("视觉委派", "vision", settings.visionProviderId, settings, vm, backdrop) { onPickPurposeModel(it) }
                    purposeRow("语音转写", "asr", settings.asrProviderId, settings, vm, backdrop) { onPickPurposeModel(it) }
                }
            }
            com.haoai.agent.ui.common.HaoNote(
                backdrop = backdrop,
                text = "为不同任务指定模型：聊天会话可精确到某供应商的某个模型；辅助任务建议用更廉价的模型。「主模型」= 跟随当前供应商默认，「端侧」= 本机 llama.cpp。\n能力委派：主模型不支持图像/音频时，Agent 会自动调用委派模型代看（delegate_to_vision）或转写（transcribe_audio），任务不用中断。",
                modifier = Modifier.padding(vertical = 4.dp)
            )
        }
    }
}

private fun LazyListScope.localItems(vm: SettingsViewModel, backdrop: com.kyant.backdrop.backdrops.LayerBackdrop, onOpenScan: () -> Unit) {
    item { SectionTitle("端侧推理（llama.cpp 本地运行）") }
    item {
        // Phase 7 阶段2：SoC 后端检测结果
        val detected = remember { com.haoai.agent.platform.llama.LlamaServerController.detectHexagonArch() }
        com.haoai.agent.ui.common.HaoNote(
            backdrop = backdrop,
            text = "推理后端：三后端单二进制（CPU/GPU/NPU-Hexagon）· " +
                (detected?.let { "本机骁龙 → Hexagon $it（arm64 启动时启用，失败自动 CPU 兜底）" } ?: "本机走 CPU/GPU 兜底") +
                " · 当前生效：${vm.backendLabel()}",
            modifier = Modifier.padding(vertical = 4.dp)
        )
    }
    item {
        val llama by vm.llamaState.collectAsState()
        val dl by vm.llamaDownload.collectAsState()
        var dlUrl by androidx.compose.runtime.remember {
            mutableStateOf(com.haoai.agent.platform.llama.LlamaServerController.DEFAULT_MODEL_URL)
        }
        Column {
            GlassGroup(backdrop) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("llama.cpp 本地推理", style = MaterialTheme.typography.titleSmall)
                            Text(
                                when (val s = llama) {
                                    is LlamaState.Running -> "运行中 · ${s.modelFile}"
                                    is LlamaState.Starting -> s.detail
                                    is LlamaState.Failed -> "失败：${s.message}"
                                    else -> "已停止 · 模型：${vm.currentLocalModelLabel() ?: "未选择"}"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2
                            )
                        }
                        if (vm.isLocalActive()) {
                            Text(
                                "当前使用",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    // 端侧启动进度条（百分比由 server 日志里程碑驱动，v0.18.3）
                    val startingProgress = (llama as? LlamaState.Starting)?.progress ?: -1
                    if (startingProgress in 0..99) {
                        androidx.compose.material3.LinearProgressIndicator(
                            progress = { startingProgress / 100f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            gapSize = 0.dp,
                            drawStopIndicator = {}
                        )
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 10.dp)
                    ) {
                        when (llama) {
                            is LlamaState.Running -> {
                                LiquidPillButton(
                                    backdrop = backdrop,
                                    text = "停止服务",
                                    emphasized = true
                                ) { vm.stopLlama() }
                            }
                            else -> {
                                LiquidPillButton(
                                    backdrop = backdrop,
                                    text = "启动服务",
                                    enabled = vm.llamaModelFile() != null,
                                    emphasized = true
                                ) { vm.startLlama { vm.useLocalModel() } }
                            }
                        }
                    }
                }
            }
            if (vm.llamaModelFile() == null) {
                OutlinedTextField(
                    value = dlUrl,
                    onValueChange = { dlUrl = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    label = { Text("模型 GGUF 下载地址") },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.labelSmall,
                    colors = com.haoai.agent.ui.common.glassFieldColors()
                )
                if (dl != null) {
                    androidx.compose.material3.LinearProgressIndicator(
                        progress = { dl ?: 0f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        gapSize = 0.dp,
                        drawStopIndicator = {}
                    )
                    Text(
                        "下载中 ${(100 * (dl ?: 0f)).toInt()}%（约 470MB，请耐心等待）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                } else {
                    LiquidPillButton(
                        backdrop = backdrop,
                        text = "下载模型（Qwen2.5-0.5B，约 470MB）",
                        modifier = Modifier.padding(top = 8.dp),
                        emphasized = true
                    ) { vm.downloadLlamaModel(dlUrl) { } }
                }
            }
            val ctxLocal = androidx.compose.ui.platform.LocalContext.current
            val importLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument()
            ) { uri -> uri?.let { vm.loadModelFile(ctxLocal, it.toString()) } }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 8.dp)
            ) {
                LiquidPillButton(
                    backdrop = backdrop,
                    text = "加载模型文件",
                    enabled = vm.importingModel == null,
                    emphasized = true
                ) { importLauncher.launch(arrayOf("*/*")) }
                LiquidPillButton(
                    backdrop = backdrop,
                    text = "扫描手机已有模型",
                    emphasized = false
                ) { onOpenScan() }
            }
            vm.importingModel?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            com.haoai.agent.ui.common.HaoNote(
                backdrop = backdrop,
                text = "提示：优先用「扫描」或「加载模型文件」直读手机上已有的 GGUF（不复制、不占双份空间）；" +
                    "视觉投影文件与模型同名放置即可自动启用图像识别。",
                modifier = Modifier.padding(vertical = 4.dp)
            )
            Text(
                "完全离线运行，对话不出设备。模型越大越聪明但越慢，可按需切换。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
            // 已添加的模型：当前使用 + 应用目录内已有文件（只读展示，切换走扫描/加载入口）
            Text(
                "已添加的模型",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 12.dp)
            )
            GlassGroup(backdrop, modifier = Modifier.padding(top = 6.dp)) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "当前使用",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.widthIn(min = 64.dp)
                        )
                        Text(
                            vm.currentLocalModelLabel() ?: "未添加",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                    }
                    val appDirModels = vm.localModels()
                    if (appDirModels.isNotEmpty()) {
                        HorizontalDivider(
                            Modifier.padding(vertical = 6.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f)
                        )
                        Text(
                            "应用目录内（${appDirModels.size} 个）：",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        appDirModels.forEach { name ->
                            Text(
                                "· $name",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                                maxLines = 1,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                    }
                }
            }
            Text(
                "本地模型上下文窗口（32K 足够日常与工具流，KV 内存随窗口线性增长；切换后下次启动生效）",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 12.dp, bottom = 6.dp)
            )
            val ctxOptions = listOf(32768, 65536, 131072, 262144)
            com.haoai.agent.ui.common.LiquidTabRow(
                tabs = ctxOptions.map { "${it / 1024}K" },
                selectedIndex = ctxOptions.indexOf(vm.localContextLength()).coerceAtLeast(0),
                onSelected = { i -> vm.setLocalContextLength(ctxOptions[i]) },
                backdrop = backdrop,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

private fun LazyListScope.privacyItems(
    vm: SettingsViewModel,
    settings: AppSettings,
    context: android.content.Context,
    a11yOn: Boolean,
    backdrop: com.kyant.backdrop.backdrops.LayerBackdrop
) {
    item { SectionTitle("权限模式") }
    item {
        val mode = settings.permissionMode
        GlassGroup(backdrop) {
            // AndroidLiquidGlass 选项卡：玻璃胶囊容器 + 弹性滑动液态指示器（同 LiquidTabRow 统一样式）
            val permModes = listOf(
                PermissionMode.ALWAYS_ASK to "全部询问",
                PermissionMode.ASK_WRITES to "写入时询问",
                PermissionMode.YOLO to "全自动"
            )
            com.haoai.agent.ui.common.LiquidTabRow(
                tabs = permModes.map { it.second },
                selectedIndex = permModes.indexOfFirst { it.first == mode },
                onSelected = { i -> vm.setPermissionMode(permModes[i].first) },
                backdrop = backdrop,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)
            )
        }
    }
    item { SectionTitle("无障碍自动化") }
    item {
        GlassGroup(backdrop) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("手机操作能力", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (a11yOn) "已启用：代理可读取屏幕并代你操作"
                        else "未启用：开启后代理可代你操作手机",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                LiquidPillButton(
                    backdrop = backdrop,
                    text = if (a11yOn) "管理" else "去开启",
                    emphasized = !a11yOn
                ) {
                    // 与工具门禁同一套多级兜底：优先直达「无障碍 → HaoAI 自动化」开关页
                    runCatching { com.haoai.agent.platform.a11y.A11yGate.openEnablePage(context) }
                }
            }
            ToggleRow(
                title = "无障碍适配模式",
                subtitle = if (settings.a11yAdaptiveMode) "已开：检测到 TalkBack 时禁用手势注入（会被其接管），操作走节点语义并逐步朗读"
                else "为 TalkBack 等无障碍用户优化 UI 自动化：操作优先节点、谨慎自动点击，并朗读操作步骤",
                checked = settings.a11yAdaptiveMode,
                onChange = { vm.setA11yAdaptiveMode(it) },
                backdrop = backdrop
            )
            // 可选增强状态：WRITE_SECURE_SETTINGS（adb 一次性授予）→ Agent 需要无障碍时静默自启
            val wssGranted = runCatching {
                context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            }.getOrDefault(false)
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("无障碍自动开启（可选增强）", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (wssGranted) "已授权：Agent 需要时会静默开启无障碍，全程不弹页"
                        else "未授权：需要时自动弹出无障碍开关页等待开启；电脑 adb 执行 " +
                            "pm grant com.haoai.agent android.permission.WRITE_SECURE_SETTINGS 后可静默自启",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
    item { SectionTitle("后台自动化（虚拟屏）") }
    item {
        val vscreenSupported = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R
        GlassGroup(backdrop) {
            ToggleRow(
                title = "虚拟屏后台自动化",
                subtitle = if (!vscreenSupported) "需要 Android 11 及以上（当前系统不支持，工具不可用）"
                else "代理把目标 App 启动到后台虚拟屏操作，你的主屏不被占用；关闭后 vscreen_* 工具从清单移除",
                checked = settings.vscreenEnabled && vscreenSupported,
                onChange = { vm.setVscreenEnabled(it) },
                backdrop = backdrop
            )
            if (vscreenSupported) {
                VscreenChannelRow(backdrop)
            }
            if (vscreenSupported) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("虚拟屏画面码率", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "档位 = 截图分辨率与画质，越高越清晰、喂给模型的图也越大（当前 ${settings.vscreenBitrateKbps / 1000f} Mbps · ${com.haoai.agent.platform.vdisplay.VirtualScreenController.presetFor(settings.vscreenBitrateKbps).first}px）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                // 同款 LiquidTabRow 选项卡；20 Mbps 档已移除：映射的 1920px 截图会被视觉模型
                // 内部再降采样，清晰度无收益、单图 token 开销翻倍（存量 20000 配置就近落到 10 Mbps 高亮）
                val bitrateOptions = listOf(1500, 3000, 5000, 10000)
                val selIdx = bitrateOptions.indexOf(settings.vscreenBitrateKbps).takeIf { it >= 0 }
                    ?: bitrateOptions.indexOfFirst { it >= settings.vscreenBitrateKbps }.takeIf { it >= 0 }
                    ?: bitrateOptions.lastIndex
                com.haoai.agent.ui.common.LiquidTabRow(
                    tabs = bitrateOptions.map { if (it % 1000 == 0) "${it / 1000} Mbps" else "1.5 Mbps" },
                    selectedIndex = selIdx,
                    onSelected = { i -> vm.setVscreenBitrate(bitrateOptions[i]) },
                    backdrop = backdrop,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp)
                )
                ToggleRow(
                    title = "运行时任务视图隐藏",
                    subtitle = if (settings.vscreenHideTask) "已开：Agent 运行期间本应用任务从最近任务隐藏，运行结束自动恢复"
                    else "Agent 运行期间本应用任务从系统最近任务中隐藏，避免被误滑关闭中断任务",
                    checked = settings.vscreenHideTask,
                    onChange = { vm.setVscreenHideTask(it) },
                    backdrop = backdrop
                )
            }
            com.haoai.agent.ui.common.HaoNote(
                backdrop = backdrop,
                text = "虚拟屏能力边界：点击/输入/滚动走无障碍节点操作，不占用你的主屏；精确手势（拖动滑块、拖拽排序、双指缩放）虚拟屏不支持，" +
                    "相关操作会明确报受限并引导节点方案。部分 ROM（含 Flyme）虚拟屏可能只渲染纯色/启动画面——截图空白属正常，" +
                    "控件树操作不受影响（工具会提示以控件树为准）；熄屏投屏同样因 ROM 而异。",
                modifier = Modifier.padding(vertical = 4.dp)
            )
        }
    }
    item { SectionTitle("系统权限") }
    item {
        androidx.compose.runtime.LaunchedEffect(Unit) { vm.refreshPermissions(context) }
    }
    item {
        GlassGroup(backdrop) {
            com.haoai.agent.platform.PermissionCenter.ALL.forEach { spec ->
                val granted = vm.permissionStates[spec.key]
                    ?: com.haoai.agent.platform.PermissionCenter.granted(context, spec)
                // 已授权的行也可点击：进入对应系统权限管理页，方便取消授权
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .then(
                            if (granted) Modifier.clickable(
                                interactionSource = null, indication = null
                            ) { com.haoai.agent.platform.PermissionCenter.openManagement(context, spec) }
                            else Modifier
                        )
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(spec.label, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            if (granted) "已授权"
                            else if (spec.special) "${spec.description}（需在系统设置中开启）"
                            else spec.description,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (granted) {
                        Text("✓", color = haoToneInk(HaoTone.Accent), fontWeight = FontWeight.Bold)
                    } else {
                        com.haoai.agent.ui.common.LiquidGlassButton(
                            onClick = { vm.requestPermission(spec, context) },
                            backdrop = backdrop,
                            shape = RoundedCornerShape(percent = 50),
                            surfaceColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.30f)
                        ) {
                            Text(
                                if (spec.special) "去设置" else "授权",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onBackground,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun LazyListScope.memoryItems(
    vm: SettingsViewModel,
    settings: AppSettings,
    onOpenMemories: () -> Unit,
    backdrop: com.kyant.backdrop.backdrops.LayerBackdrop,
    onPickDreamModel: () -> Unit = {}
) {
    item { SectionTitle("记忆库概览") }
    item {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            GlassStatTile(
                backdrop = backdrop,
                number = "${vm.homeCounts.first}",
                label = "长期记忆",
                tint = haoToneMain(HaoTone.Accent),
                modifier = Modifier.weight(1f)
            )
            GlassStatTile(
                backdrop = backdrop,
                number = "${vm.homeCounts.second}",
                label = "今日日志",
                tint = haoToneMain(HaoTone.Info),
                modifier = Modifier.weight(1f)
            )
            GlassStatTile(
                backdrop = backdrop,
                number = if (settings.deepDream) "开" else "关",
                label = "梦境整理",
                tint = haoToneMain(HaoTone.Warn),
                modifier = Modifier.weight(1f)
            )
        }
    }
    item {
        Column {
            com.haoai.agent.ui.common.GlassCard(
                onClick = onOpenMemories,
                backdrop = backdrop,
                shape = RoundedCornerShape(18.dp),
                surfaceAlpha = com.haoai.agent.ui.theme.haoPageCardSurfaceAlpha(),
                lensRadius = 14.dp,
                // 2026-10-09 方案A：页面玻璃卡档（磨砂/白雾独立于聊天卡片）
                pageTier = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
            ) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Memory,
                        contentDescription = null,
                        tint = haoToneMain(HaoTone.Accent),
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(Modifier.size(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("近期动态与长期记忆", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Text(
                            "查看 · 整理 · 固化 · 清理",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(
                        Icons.Filled.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            com.haoai.agent.ui.common.HaoNote(
                backdrop = backdrop,
                text = "固化规则：每日日志中重要性 ≥4 的条目夜间自动晋升为长期记忆；日志保留 7 天后清理。",
                modifier = Modifier.padding(vertical = 4.dp)
            )
        }
    }
    item { SectionTitle("记录策略") }
    item {
        GlassGroup(backdrop) {
            ToggleRow(
                title = "记忆系统",
                subtitle = "沉淀偏好与背景，回答时注入参考",
                checked = settings.memoryEnabled,
                onChange = { vm.setMemoryEnabled(it) },
                backdrop = backdrop
            )
            HorizontalDivider(
                Modifier.padding(start = 16.dp, end = 16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f)
            )
            ToggleRow(
                title = "自动学习",
                subtitle = "每轮对话后自动提取值得长期记住的信息（严格守门，宁缺毋滥）",
                checked = settings.autoLearn,
                onChange = { vm.setAutoLearn(it) },
                backdrop = backdrop
            )
        }
    }
    item { SectionTitle("梦境整理") }
    item {
        GlassGroup(backdrop) {
            ToggleRow(
                title = "闲置时自动整理记忆",
                subtitle = "充电且灭屏持续所选时间后执行，仅 00:00–7:00 夜间时段生效；亮屏或断电即取消。闲置期内 PC 端写过 MEMORY.md 则本轮让位（DREAMS.md 留一行原因）。固化历史写入 DREAMS.md。",
                checked = settings.deepDream,
                onChange = { vm.setDeepDream(it) },
                backdrop = backdrop
            )
            Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
                Text(
                    "灭屏闲置多久后开始",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
                val options = listOf(1 to "1 分钟", 5 to "5 分钟", 15 to "15 分钟", 30 to "30 分钟")
                com.haoai.agent.ui.common.LiquidTabRow(
                    tabs = options.map { it.second },
                    selectedIndex = options.indexOfFirst { it.first == settings.dreamIdleMinutes }.coerceAtLeast(0),
                    onSelected = { i -> vm.setDreamIdleMinutes(options[i].first) },
                    backdrop = backdrop
                )
            }
        }
    }
    item { SectionTitle("记忆管理模型") }
item {
        GlassGroup(backdrop) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { vm.setDreamProvider("local") }
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = settings.dreamProviderId == "local", onClick = { vm.setDreamProvider("local") })
                Column(Modifier.padding(start = 4.dp).weight(1f)) {
                    Text("端侧小模型（推荐，零 token 消耗）", style = MaterialTheme.typography.bodySmall)
                    Text(
                        "llama.cpp 本地运行 · ${vm.llamaModelFile() ?: "未下载"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "记忆专用：${settings.dreamLocalModelFile?.substringAfterLast('/') ?: "跟随端侧聊天模型"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                TextButton(onClick = onPickDreamModel) { Text("选择", style = MaterialTheme.typography.labelMedium) }
            }
            settings.providers.filter { it.id != "local" }.forEach { p ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { vm.setDreamProvider(p.id) }
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = settings.dreamProviderId == p.id, onClick = { vm.setDreamProvider(p.id) })
                    Column(Modifier.padding(start = 4.dp)) {
                        Text("${p.name} · ${p.model}", style = MaterialTheme.typography.bodySmall)
                        Text(
                            "云端服务 · 整理时消耗该服务 token",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/** 选择弹窗里的单选行：标题 + 路径副标题。 */
@Composable
private fun ModelPickRow(
    selected: Boolean,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(Modifier.padding(start = 2.dp)) {
            Text(title, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

/**
 * 4.3 增强：虚拟屏启动通道状态行（Shizuku → root → 直启 三级）。
 * 状态在进入区块时刷新；Shizuku 已装未授权时给「授权」按钮。
 */
@Composable
private fun VscreenChannelRow(backdrop: com.kyant.backdrop.backdrops.LayerBackdrop) {
    val context = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.LaunchedEffect(Unit) {
        com.haoai.agent.platform.vdisplay.PrivilegedShell.refresh(context)
    }
    val status by com.haoai.agent.platform.vdisplay.PrivilegedShell.shizukuStatus.collectAsState()
    val root by com.haoai.agent.platform.vdisplay.PrivilegedShell.rootAvailable.collectAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text("App 上屏通道", style = MaterialTheme.typography.bodyMedium)
            val channelText = when {
                status == com.haoai.agent.platform.vdisplay.PrivilegedShell.Status.GRANTED -> "Shizuku 已授权（任意 App 均可上屏）"
                root -> "root 已就绪（任意 App 均可上屏）"
                status == com.haoai.agent.platform.vdisplay.PrivilegedShell.Status.UNAUTHORIZED -> "Shizuku 待授权（未授权时仅部分 App 能上屏）"
                status == com.haoai.agent.platform.vdisplay.PrivilegedShell.Status.NOT_RUNNING -> "Shizuku 未运行（打开 Shizuku 应用启动服务）"
                else -> "未检测到 Shizuku/root（直启受限：部分 App 会拒绝上屏）"
            }
            Text(
                channelText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        when (status) {
            com.haoai.agent.platform.vdisplay.PrivilegedShell.Status.UNAUTHORIZED -> {
                com.haoai.agent.ui.common.LiquidGlassButton(
                    onClick = {
                        val msg = com.haoai.agent.platform.vdisplay.PrivilegedShell.requestShizukuPermission()
                        if (msg != null) {
                            android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
                        }
                    },
                    backdrop = backdrop,
                    shape = RoundedCornerShape(percent = 50),
                    surfaceColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.30f)
                ) {
                    Text(
                        "授权",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }
            com.haoai.agent.platform.vdisplay.PrivilegedShell.Status.NOT_INSTALLED -> {
                com.haoai.agent.ui.common.LiquidGlassButton(
                    onClick = {
                        runCatching {
                            context.startActivity(
                                android.content.Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse("https://github.com/RikkaApps/Shizuku/releases")
                                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    },
                    backdrop = backdrop,
                    shape = RoundedCornerShape(percent = 50),
                    surfaceColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.30f)
                ) {
                    Text(
                        "获取 Shizuku",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }
            else -> {}
        }
    }
}

/** 设置页通用开关行（液态玻璃开关）。 */
@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    backdrop: com.kyant.backdrop.backdrops.LayerBackdrop
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        com.haoai.agent.ui.common.HaoSwitch(
            checked = checked,
            onCheckedChange = onChange,
            backdrop = backdrop
        )
    }
}

private fun LazyListScope.workspaceItems(
    vm: SettingsViewModel,
    backdrop: com.kyant.backdrop.backdrops.LayerBackdrop,
    pickFolder: () -> Unit
) {
    item {
        Column {
            // 顶部说明卡片：工作空间是什么、两种目录的差异
            GlassGroup(backdrop) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Folder,
                            contentDescription = null,
                            tint = haoToneMain(HaoTone.Info),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.size(10.dp))
                        Text(
                            "什么是工作空间",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        "工作空间是助手读写文件、执行命令的根目录。默认目录（应用专属）支持完整文件与 shell 能力；选择 SAF 目录后 bash 不可用。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                    HorizontalDivider(
                        Modifier.padding(vertical = 8.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f)
                    )
                    Text(
                        "当前：${vm.workspaceName()}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            // 双按钮横排
            Row(
                Modifier.padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                LiquidPillButton(
                    backdrop = backdrop,
                    text = "选择文件夹 (SAF)",
                    modifier = Modifier.weight(1f),
                    emphasized = false
                ) { pickFolder() }
                LiquidPillButton(
                    backdrop = backdrop,
                    text = "恢复默认目录",
                    modifier = Modifier.weight(1f),
                    // 回退性操作：用警告级（和"选择文件夹"这种普通次要操作区分开）
                    level = com.haoai.agent.ui.theme.HaoButtonLevel.Warning
                ) { vm.useDefaultWorkspace() }
            }
        }
    }
}

private fun LazyListScope.generalItems(
    vm: SettingsViewModel,
    settings: AppSettings,
    context: android.content.Context,
    backdrop: com.kyant.backdrop.backdrops.LayerBackdrop,
    wpVersion: Boolean,
    onWpVersionChange: (Boolean) -> Unit,
    onRequestClearWallpaper: () -> Unit,
    onWpChanged: () -> Unit = {},
    onOpenColorPicker: (initialHex: String, slot: Int) -> Unit = { _, _ -> },
    onRequestKeyExportConfirm: () -> Unit = {},
    onEditMyProfile: () -> Unit = {}
) {
    item { SectionTitle("我的档案") }
    item {
        // 聊天社交化（2026-10-07）：与 Agent 档案对称——头像+昵称+签名，点开编辑弹窗
        GlassGroup(backdrop) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { onEditMyProfile() }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                com.haoai.agent.ui.chat.ProfileAvatar(
                    emoji = settings.myEmoji,
                    gradientIndex = settings.myGradient,
                    fallback = settings.myName.ifBlank { "我" },
                    size = 40.dp,
                    imagePath = settings.myAvatarPath
                )
                Spacer(Modifier.size(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        settings.myName.ifBlank { "我" },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
                    )
                    Text(
                        settings.myBio.ifBlank { "昵称 · 头像 · 签名" },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
                Icon(
                    Icons.Filled.ChevronRight, null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }

    item { SectionTitle("数据与备份") }
    item {
        GlassGroup(backdrop) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
                // 体积是磁盘遍历，别在重组里现算：进页面时 IO 线程取一次
                val scopes = com.haoai.agent.platform.BackupScope.entries
                var sizes by remember {
                    mutableStateOf<Map<com.haoai.agent.platform.BackupScope, Long>>(emptyMap())
                }
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    sizes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        scopes.associateWith { vm.scopeBytes(it) }
                    }
                }
                scopes.forEach { sc ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable { vm.toggleBackupScope(sc) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = vm.backupSelected(sc),
                            onCheckedChange = { vm.toggleBackupScope(sc) }
                        )
                        Column(Modifier.weight(1f).padding(end = 8.dp)) {
                            Text(sc.label, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                sc.hint,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                        }
                        Text(
                            vm.sizeLabel(sizes[sc] ?: 0L),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
                HorizontalDivider(
                    Modifier.padding(vertical = 6.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f)
                )
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "沙箱运行环境 / 写前快照",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                        )
                        Text(
                            "proot 环境可重新下载；写前快照只在当前机器用于回滚文件改动，跨机无意义",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    }
                    Text(
                        "不含",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
                Text(
                    "已选 ${scopes.count { vm.backupSelected(it) }} 项 · 约 " +
                        "${vm.sizeLabel(scopes.filter { vm.backupSelected(it) }.sumOf { sizes[it] ?: 0L })}" +
                        " · 明文密钥：${if (vm.backupIncludeKeys()) "包含" else "不含"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
    item {
        val exportLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/zip")
        ) { uri ->
            if (uri != null) {
                vm.exportData(uri) { r ->
                    android.widget.Toast.makeText(
                        context,
                        r.getOrElse { "备份失败：${it.message ?: it.javaClass.simpleName}" },
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
        GlassGroup(backdrop, modifier = Modifier.padding(vertical = 6.dp)) {
            Column {
                ToggleRow(
                    title = "备份包里包含明文 API Key",
                    subtitle = "Key 由本机 Keystore 加密，换机后旧包解不开。不含则恢复后需重贴；" +
                        "含则这个 zip 一旦外泄等于账号外泄",
                    checked = vm.backupIncludeKeys(),
                    onChange = { want ->
                        if (!want) vm.setBackupIncludeKeys(false) else onRequestKeyExportConfirm()
                    },
                    backdrop = backdrop
                )
                HorizontalDivider(
                    Modifier.padding(horizontal = 14.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f)
                )
                ToggleRow(
                    title = "破坏性操作前自动留快照",
                    subtitle = "手动压缩、彻底删除会话前，先把那条会话原样存到本机 files/backups/，" +
                        "保留最近 8 份",
                    checked = vm.backupSnapshots(),
                    onChange = { vm.setBackupSnapshots(it) },
                    backdrop = backdrop
                )
                HorizontalDivider(
                    Modifier.padding(horizontal = 14.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f)
                )
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("导出到手机或云盘", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            vm.lastExportLabel(),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (vm.hasExported())
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            else MaterialTheme.colorScheme.tertiary.copy(alpha = 0.9f)
                        )
                        Text(
                            "本机快照：${vm.snapshotLabel()}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                    if (vm.backupBusy) {
                        CircularProgressIndicator(
                            Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        TextButton(onClick = {
                            exportLauncher.launch(
                                "haoai-backup-" + java.text.SimpleDateFormat(
                                    "yyyyMMdd-HHmm", java.util.Locale.CHINA
                                ).format(java.util.Date()) + ".zip"
                            )
                        }) { Text("开始备份") }
                    }
                }
            }
        }
    }

    item {
        // 恢复：SAF 选包 → inspect 整包校验并展示 → 勾选范围确认 → 落盘 → 自动重启
        // （导出侧只保证包是好的；这里补上"读回来"的另一半，换机/卸载重装才真正闭环）
        var restoreUri by remember { mutableStateOf<android.net.Uri?>(null) }
        val restoreLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri ->
            restoreUri = uri
            if (uri != null) {
                vm.inspectBackup(uri) { r ->
                    android.widget.Toast.makeText(
                        context,
                        r.getOrElse { "无法读取备份包：${it.message ?: it.javaClass.simpleName}" },
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
        GlassGroup(backdrop, modifier = Modifier.padding(vertical = 6.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("从备份包恢复", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "选择之前导出的 haoai-backup-*.zip。恢复会覆盖当前同域数据，" +
                            "建议仅在新装或清空数据后使用",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
                if (vm.restoreBusy) {
                    CircularProgressIndicator(
                        Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    TextButton(onClick = { restoreLauncher.launch(arrayOf("application/zip")) }) {
                        Text("选择备份包")
                    }
                }
            }
        }
        vm.restoreManifest?.let { m ->
            val packScopes = m.scopes.mapNotNull {
                runCatching { com.haoai.agent.platform.BackupScope.valueOf(it) }.getOrNull()
            }
            var selected by remember(m) { mutableStateOf(packScopes.toSet()) }
            val packedAt = remember(m) {
                java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA)
                    .format(java.util.Date(m.createdAt))
            }
            com.haoai.agent.ui.common.GlassAlertDialog(
                backdrop = backdrop,
                title = "确认恢复数据",
                onDismiss = { vm.closeRestoreManifest() },
                confirmLabel = "开始恢复",
                onConfirm = {
                    val u = restoreUri ?: return@GlassAlertDialog
                    vm.restoreBackup(u, selected) { r ->
                        android.widget.Toast.makeText(
                            context,
                            r.getOrElse { "恢复失败：${it.message ?: it.javaClass.simpleName}" },
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                        // 成功即重启：会话/记忆/配置桥的内存缓存不重启不会看到恢复的数据
                        r.onSuccess { vm.restartApp() }
                    }
                },
                dismissLabel = "取消"
            ) {
                Column {
                    Text(
                        "备份自 v${m.appVersionName.ifBlank { "?" }} · $packedAt · ${m.items.size} 项\n" +
                            if (m.keysIncluded)
                                "含明文 Key：恢复时用本机新 Keystore 重新加密入库"
                            else
                                "不含 Key：恢复后需到供应商设置里重贴 API Key",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f)
                    )
                    Spacer(Modifier.height(8.dp))
                    packScopes.forEach { sc ->
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable {
                                    selected = if (sc in selected) selected - sc else selected + sc
                                },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = sc in selected,
                                onCheckedChange = {
                                    selected = if (sc in selected) selected - sc else selected + sc
                                }
                            )
                            Text(sc.label, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "恢复会覆盖所选范围的当前数据；完成后应用将自动重启",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.9f)
                    )
                }
            }
        }
    }

    item {
        // 配置文件桥（从「通用」搬来：它管的就是上面这份 haoai.config.json）
        GlassGroup(backdrop, modifier = Modifier.padding(vertical = 6.dp)) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("配置桥状态", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "最近应用：${vm.configFileStatus()}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            maxLines = 2
                        )
                        Text(
                            "新增模型的 API Key 请让代理经 config_set 即时写入（成功后自动加密掩码）；" +
                                "若配置未通过校验，明文会被自动脱敏为 ****，不会以明文留在状态目录或快照中。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                            maxLines = 3
                        )
                    }
                }
                vm.latestConfigSnapshot()?.let { snap ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "上次配置快照：${snap.removeSuffix(".json").take(4)}-${snap.substring(4, 6)}-" +
                                "${snap.substring(6, 8)} ${snap.substring(9, 11)}:${snap.substring(11, 13)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = {
                            if (vm.restoreLatestConfigSnapshot()) {
                                android.widget.Toast.makeText(
                                    context, "已回退到上一份配置，重启后完全生效",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            }
                        }) { Text("回退") }
                    }
                }
                if (vm.rejectedConfigCopies() > 0) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${vm.rejectedConfigCopies()} 个被拒绝配置的修正副本（已脱敏）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { vm.clearRejectedConfigCopies() }) { Text("清理") }
                    }
                }
                // 路径默认收起：正式包不可调试，这个目录在手机上根本打不开，
                // 只在需要远程排障时展开看一眼
                var showPath by remember { mutableStateOf(false) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (showPath) vm.configFilePath() else "配置文件路径（排障用）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { showPath = !showPath }) {
                        Text(if (showPath) "收起" else "展开")
                    }
                }
            }
        }
    }
    item { SectionTitle("模型行为") }
    item {
        GlassGroup(backdrop) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                Text("思考等级（reasoning effort，仅支持的云服务生效）", style = MaterialTheme.typography.bodyMedium)
                val effortOptions = listOf("" to "默认", "low" to "低", "medium" to "中", "high" to "高")
                com.haoai.agent.ui.common.LiquidTabRow(
                    tabs = effortOptions.map { it.second },
                    selectedIndex = effortOptions.indexOfFirst { it.first == settings.reasoningEffort }.coerceAtLeast(0),
                    onSelected = { i -> vm.setReasoningEffort(effortOptions[i].first) },
                    backdrop = backdrop,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
    // E5b 成本熔断：交互聊天与无人值守分级——聊天用这里设置（默认 25 万），定时任务/工作流固定 15 万硬限
    item {
        GlassGroup(backdrop) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                ToggleRow(
                    title = "成本熔断",
                    subtitle = "达到上限时让 Agent 总结进度并收尾，防止失控任务烧 token。关闭后交互聊天与定时任务/工作流均不再自动收尾。",
                    checked = settings.costBreakerEnabled,
                    onChange = { vm.setCostBreakerEnabled(it) },
                    backdrop = backdrop
                )
                if (settings.costBreakerEnabled) {
                    Spacer(Modifier.height(8.dp))
                    Text("单轮 Token 上限", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "一轮对话累计消耗（含每次工具调用重发的上下文）达到上限即收尾。0 = 不限；定时任务/工作流不受此项影响（固定 15 万）。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    var capText by androidx.compose.runtime.remember(settings.turnTokenCap) {
                        androidx.compose.runtime.mutableStateOf(settings.turnTokenCap.let { if (it == 0) "" else it.toString() })
                    }
                    // 防抖：停止输入 600ms 才落盘（逐键击 updateSettings=全量 JSON+双文件写+整页重组）
                    LaunchedEffect(capText) {
                        if ((capText.toIntOrNull() ?: 0) == settings.turnTokenCap) return@LaunchedEffect
                        kotlinx.coroutines.delay(600)
                        vm.setTurnTokenCap(capText.toIntOrNull() ?: 0)
                    }
                    OutlinedTextField(
                        value = capText,
                        onValueChange = { t ->
                            capText = t.filter { ch -> ch.isDigit() }.take(7)
                        },
                        modifier = Modifier.width(150.dp),
                        singleLine = true,
                        placeholder = { Text("不限") },
                        trailingIcon = { Text("tok/轮", style = MaterialTheme.typography.labelSmall) },
                        colors = com.haoai.agent.ui.common.glassFieldColors()
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text("圈数上限（工具调用次数）", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "一轮对话累计工具调用达到上限即收尾，防失控循环。0 = 不限。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    var callText by androidx.compose.runtime.remember(settings.toolCallCap) {
                        androidx.compose.runtime.mutableStateOf(settings.toolCallCap.let { if (it == 0) "" else it.toString() })
                    }
                    LaunchedEffect(callText) {
                        if ((callText.toIntOrNull() ?: 0) == settings.toolCallCap) return@LaunchedEffect
                        kotlinx.coroutines.delay(600)
                        vm.setToolCallCap(callText.toIntOrNull() ?: 0)
                    }
                    OutlinedTextField(
                        value = callText,
                        onValueChange = { t ->
                            callText = t.filter { ch -> ch.isDigit() }.take(6)
                        },
                        modifier = Modifier.width(150.dp),
                        singleLine = true,
                        placeholder = { Text("不限") },
                        trailingIcon = { Text("次/轮", style = MaterialTheme.typography.labelSmall) },
                        colors = com.haoai.agent.ui.common.glassFieldColors()
                    )
                }
                Spacer(Modifier.height(4.dp))
                ToggleRow(
                    title = "熔断前软提醒",
                    subtitle = "达单轮上限 70% 时先提醒 Agent 精简收尾，到 100% 才强制结束",
                    checked = settings.softBudgetWarn,
                    onChange = { vm.setSoftBudgetWarn(it) },
                    backdrop = backdrop
                )
                Spacer(Modifier.height(4.dp))
                ToggleRow(
                    title = "Smart approval",
                    subtitle = "Aux LLM reviews shell before human prompt: safe auto-approve, dangerous deny, uncertain or failure escalate to you.",
                    checked = settings.smartApproval,
                    onChange = { vm.setSmartApproval(it) },
                    backdrop = backdrop
                )
                Spacer(Modifier.height(4.dp))
                Text("Tool profile", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Trim injected tools per task shape (token / noise). Session = follow tools_enable.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        "" to "Session",
                        "minimal" to "Min",
                        "coding" to "Code",
                        "full" to "Full"
                    ).forEach { (value, label) ->
                        val selected = settings.toolProfile == value
                        com.haoai.agent.ui.common.LiquidGlassButton(
                            onClick = { vm.setToolProfile(value) },
                            backdrop = backdrop,
                            enabled = true
                        ) {
                            Text(
                                label,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
                }
            }
        }
    }
    item { SectionTitle("后台") }
    item {
        GlassGroup(backdrop) {
            ToggleRow(
                title = "后台保活",
                subtitle = "以前台服务保持任务在后台继续运行",
                checked = settings.keepAlive,
                onChange = { enabled ->
                    vm.setKeepAlive(enabled)
                    if (enabled) KeepAliveService.start(context) else KeepAliveService.stop(context)
                },
                backdrop = backdrop
            )
            ToggleRow(
                title = "任务悬浮窗",
                subtitle = if (com.haoai.agent.platform.PermissionCenter.granted(context, com.haoai.agent.platform.PermissionCenter.OVERLAY))
                    "切到其他应用时，悬浮胶囊实时显示 Agent 当前步骤，可展开查看与停止"
                else "需要「悬浮窗」权限（点击去授权）；不授权不影响任务后台运行",
                checked = settings.runOverlay,
                onChange = { enabled ->
                    vm.setRunOverlay(enabled)
                    if (enabled && !com.haoai.agent.platform.PermissionCenter.granted(context, com.haoai.agent.platform.PermissionCenter.OVERLAY)) {
                        com.haoai.agent.platform.PermissionCenter.openManagement(context, com.haoai.agent.platform.PermissionCenter.OVERLAY)
                    }
                },
                backdrop = backdrop
            )
        }
    }
    item { SectionTitle("自定义指令") }
    item {
        // 裸 OutlinedTextField → 玻璃组包裹：与设置页其他区块视觉一致
        Column {
            GlassGroup(backdrop) {
                Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                    var promptLocal by androidx.compose.runtime.remember(settings.customPrompt) {
                        androidx.compose.runtime.mutableStateOf(settings.customPrompt)
                    }
                    LaunchedEffect(promptLocal) {
                        if (promptLocal == settings.customPrompt) return@LaunchedEffect
                        kotlinx.coroutines.delay(600)
                        vm.setCustomPrompt(promptLocal)
                    }
                    OutlinedTextField(
                        value = promptLocal,
                        onValueChange = { promptLocal = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("附加到系统提示末尾的个人偏好…") },
                        minLines = 2,
                        maxLines = 6,
                        colors = com.haoai.agent.ui.common.glassFieldColors()
                    )
                }
            }
        }
    }

    // ── 实验特性（S6）：新能力"先上再关"的总闸 ──────────────────────────────
    // 列的是 HaoFlag.visible()，不是散在各页的临时开关 —— 加新能力只在枚举里加一条，
    // 这里自动出现。已下线（REMOVED）的不显示：显示了也改不动，只会骗人。
    item { SectionTitle("实验特性") }
    item {
        HaoGroup(backdrop = backdrop) {
            com.haoai.agent.agent.flags.HaoFlag.visible().forEach { flag ->
                ToggleRow(
                    title = flag.title,
                    subtitle = flag.what,
                    checked = com.haoai.agent.agent.flags.HaoFlag.enabled(flag, settings.enabledFlags),
                    onChange = { vm.setFlag(flag.key, it) },
                    backdrop = backdrop
                )
            }
        }
    }
}

// ---------- 主题外观（2026-10-08：通用页外观块+动画风格迁入，玻璃质感并入） ----------

/**
 * 「设置 → 主题外观」：主题模式/主题色/AMOLED/气泡/壁纸/动画风格（从通用页迁入），
 * 外加**玻璃质感**实时调参——预览卡压在折射测试图案上，拖滑杆所见即所得
 * （数据源 GlassTuning 单例，全 App 玻璃在绘制期读取；这里松手即落盘。
 * 原「玻璃实验室」调试页 2026-10-08 已删，本页是唯一调参入口）。
 */
private fun LazyListScope.themeItems(
    vm: SettingsViewModel,
    settings: AppSettings,
    context: android.content.Context,
    backdrop: com.kyant.backdrop.backdrops.LayerBackdrop,
    /** 标题栏底边（窗口坐标，未测得时为 MAX_VALUE）：预览吸顶的停靠线 */
    barBottomPx: Float,
    wpVersion: Boolean,
    onWpVersionChange: (Boolean) -> Unit,
    onRequestClearWallpaper: () -> Unit,
    onOpenColorPicker: (initialHex: String, slot: Int) -> Unit = { _, _ -> }
) {
    item { SectionTitle("主题模式与配色") }
    item {
        GlassGroup(backdrop) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                Text("主题模式", style = MaterialTheme.typography.bodyMedium)
                val themeOptions = listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色")
                com.haoai.agent.ui.common.LiquidTabRow(
                    tabs = themeOptions.map { it.second },
                    selectedIndex = themeOptions.indexOfFirst { it.first == settings.themeMode }.coerceAtLeast(0),
                    onSelected = { i -> vm.setThemeMode(themeOptions[i].first) },
                    backdrop = backdrop,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            val darkNow = when (settings.themeMode) {
                "dark" -> true
                "light" -> false
                else -> androidx.compose.foundation.isSystemInDarkTheme()
            }
            val dynamicAvailable = android.os.Build.VERSION.SDK_INT >= 31
            HorizontalDivider(
                Modifier.padding(horizontal = 14.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f)
            )
            ToggleRow(
                title = "动态颜色（Material You）",
                subtitle = if (dynamicAvailable) "跟随聊天壁纸动态生成配色（无壁纸时按系统壁纸取色）"
                else "需要 Android 12 及以上",
                checked = settings.dynamicColor && dynamicAvailable,
                onChange = { if (dynamicAvailable) vm.setDynamicColor(it) },
                backdrop = backdrop
            )
            if (!settings.dynamicColor || !dynamicAvailable) {
                HorizontalDivider(
                    Modifier.padding(horizontal = 14.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f)
                )
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text("主题色", style = MaterialTheme.typography.bodyMedium)
                    val activeCustom = settings.customSeedActive
                    // 色盘初始色：当前生效主题色（自定义色或预设 primary），所见即所选
                    val currentSeedHex = if (activeCustom.isNotBlank()) activeCustom
                    else {
                        val cur = com.haoai.agent.ui.theme.THEME_SEEDS
                            .getOrElse(settings.themeSeed) { com.haoai.agent.ui.theme.THEME_SEEDS[0] }
                        (cur.display ?: (if (darkNow) cur.darkPrimary else cur.lightPrimary)).toHex()
                    }
                    Row(
                        // 内容 9×26dp 在窄屏会超出可用宽，固定 spacedBy 下尾元素溢出被玻璃容器
                        // 采样变形（模拟器 411dp 实测色轮成竖条）——改 SpaceBetween 自适应分布
                        Modifier.fillMaxWidth().padding(top = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 5 颗固定种子：绿/蓝保留原版，橙/黄/粉为年轻活力色
                        com.haoai.agent.ui.theme.THEME_SEEDS.forEachIndexed { i, seed ->
                            val color = seed.display
                                ?: (if (darkNow) seed.darkPrimary else seed.lightPrimary)
                            val selected = activeCustom.isBlank() && settings.themeSeed == i
                            Box(
                                Modifier
                                    .size(26.dp)
                                    .clip(CircleShape)
                                    .background(color, CircleShape)
                                    .border(
                                        if (selected) 2.5.dp else 1.dp,
                                        if (selected) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.25f),
                                        CircleShape
                                    )
                                    .clickable { vm.setThemeSeed(i) }
                            )
                        }
                        // 3 个自定义槽：空=加号圈（点击开色盘），已存=点击应用、长按重挑
                        repeat(3) { slot ->
                            val hex = settings.customSeedColors.getOrNull(slot).orEmpty()
                            val parsed = com.haoai.agent.ui.theme.parseHexColor(hex)
                            if (parsed == null) {
                                Box(
                                    Modifier
                                        .size(26.dp)
                                        .clip(CircleShape)
                                        .border(1.5.dp, MaterialTheme.colorScheme.onBackground.copy(alpha = 0.28f), CircleShape)
                                        .combinedClickable(
                                            onClick = { onOpenColorPicker(currentSeedHex, slot) },
                                            onLongClick = { onOpenColorPicker(currentSeedHex, slot) }
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Filled.Add, null,
                                        Modifier.size(14.dp),
                                        tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.42f)
                                    )
                                }
                            } else {
                                val selected = activeCustom == hex
                                Box(
                                    Modifier
                                        .size(26.dp)
                                        .clip(CircleShape)
                                        .background(parsed, CircleShape)
                                        .border(
                                            if (selected) 2.5.dp else 1.dp,
                                            if (selected) parsed
                                            else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.25f),
                                            CircleShape
                                        )
                                        .combinedClickable(
                                            onClick = { vm.applyCustomSeed(hex) },
                                            onLongClick = { onOpenColorPicker(hex, slot) }
                                        )
                                )
                            }
                        }
                        // 彩色色轮入口：打开色盘挑新颜色
                        Box(
                            Modifier
                                .size(26.dp)
                                .clip(CircleShape)
                                .background(Brush.sweepGradient(PickerHueColors), CircleShape)
                                .border(1.dp, MaterialTheme.colorScheme.onBackground.copy(alpha = 0.25f), CircleShape)
                                .clickable { onOpenColorPicker(currentSeedHex, -1) },
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                Modifier
                                    .size(9.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.background)
                            )
                        }
                    }
                }
            }
            HorizontalDivider(
                Modifier.padding(horizontal = 14.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f)
            )
            ToggleRow(
                title = "AMOLED 纯黑模式",
                subtitle = "深色主题下使用纯黑背景（OLED 省电、息屏边框无光晕）",
                checked = settings.amoledMode,
                onChange = { vm.setAmoledMode(it) },
                backdrop = backdrop
            )
            HorizontalDivider(
                Modifier.padding(horizontal = 14.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f)
            )
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                // 拖动走本地状态（逐帧只重组本区块），松手才落盘——
                // updateSettings 每帧全量 JSON 序列化 + 配置桥镜像双文件写 + 整页重组，是滑块掉帧根因
                var bubbleLocal by remember { mutableStateOf(settings.bubbleOpacity.coerceIn(0.3f, 1f)) }
                LaunchedEffect(settings.bubbleOpacity) {
                    bubbleLocal = settings.bubbleOpacity.coerceIn(0.3f, 1f)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("气泡 / 卡片不透明度", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.weight(1f))
                    Text(
                        "${(bubbleLocal * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                LiquidSlider(
                    value = { bubbleLocal },
                    onValueChange = { v -> bubbleLocal = v },
                    onValueChangeFinished = { vm.setBubbleOpacity(bubbleLocal) },
                    valueRange = 0.3f..1f,
                    visibilityThreshold = 0.01f,
                    backdrop = backdrop,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                )
            }
        }
    }

    item { SectionTitle("聊天背景壁纸") }
    item {
        GlassGroup(backdrop) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("壁纸", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (wpVersion) "已设置 · 玻璃效果将以壁纸为折射背景" else "未设置（使用默认渐变背景）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // ACTION_PICK 分发给默认图库（Flyme 图库等），与聊天「图片」入口一致；
                // 兜底：图库 intent 无人处理时（极少数无图库的设备）回退 SAF 文档选择器
                val wpApply: (android.net.Uri?) -> Unit = { uri ->
                    uri?.let {
                        vm.setWallpaper(context, it.toString())
                        onWpVersionChange(vm.wallpaperSet(context))
                    }
                }
                val wpPick = rememberLauncherForActivityResult(
                    ActivityResultContracts.StartActivityForResult()
                ) { result -> wpApply(result.data?.data) }
                val wpFallback = rememberLauncherForActivityResult(
                    ActivityResultContracts.GetContent()
                ) { uri -> wpApply(uri) }
                TextButton(onClick = {
                    val intent = android.content.Intent(
                        android.content.Intent.ACTION_PICK,
                        android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    )
                    if (intent.resolveActivity(context.packageManager) != null) {
                        wpPick.launch(intent)
                    } else {
                        wpFallback.launch("image/*")
                    }
                }) { Text("选择图片") }
                if (wpVersion) {
                    TextButton(onClick = onRequestClearWallpaper) { Text("清除") }
                }
            }
            HorizontalDivider(
                Modifier.padding(horizontal = 14.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f)
            )
            ToggleRow(
                title = "壁纸应用于所有页面",
                subtitle = "关闭时壁纸只作为聊天界面背景，其他页面用主题底色",
                checked = settings.wallpaperGlobal,
                onChange = { vm.setWallpaperGlobal(it) },
                backdrop = backdrop
            )
        }
    }

    // ── 玻璃质感（方案定稿 2026-10-08 效果图确认）──
    // · 标题与其他段落一致：裸标题（HaoSectionTitle 无背板）随内容滚走，不吸顶；
    // · 吸顶只剩预览卡，卡顶用「动态停靠区」把 54dp 让位做在卡外——静止时高度为 0
    //   （标题紧贴预览、无空隙），上滑逼近标题栏时长到 54dp 把卡"停"在栏下，之后
    //   该区始终藏在 GlassPageBar 后面 = 裸背景，不面板不磨砂；
    // · 预览卡不透明实底（surfaceAlpha=1）——用户确认：透底干扰演示判读。
    item { SectionTitle("玻璃质感") }
    stickyHeader {
        val t = com.haoai.agent.ui.theme.GlassTuning
        var topPx by remember { mutableStateOf(Float.MAX_VALUE) }
        val density = androidx.compose.ui.platform.LocalDensity.current
        Column(
            Modifier.onGloballyPositioned { topPx = it.boundsInWindow().top }
        ) {
            // 停靠区：h = clamp(标题栏底边 - 卡顶现高, 0, 54dp)。卡顶由内容流/吸顶
            // 决定，本区只向下撑开，不反作用于卡顶——无反馈回路。停到栏底 = 无缝、
            // 预览顶不被栏压住；静止时（卡顶远低于栏底）h=0，标题紧贴预览无空隙
            val zoneH = with(density) {
                val maxZone = 54.dp.toPx()
                if (barBottomPx == Float.MAX_VALUE) 0.dp
                else (barBottomPx - topPx).coerceIn(0f, maxZone).toDp()
            }
            Spacer(Modifier.height(zoneH))
            // 预览文案用真实档案（用户反馈：写死"小豪/柠瑶"不对，别的用户名字不同）
            GlassPreviewCard(
                agentName = settings.agentName.ifBlank { "HaoAI" },
                agentEmoji = settings.avatarEmoji,
                backdrop = backdrop
            )
        }
    }

    // ── 调参分四组：裸标题在卡外（与其他段落一致），按档位归类 ──
    item { SectionTitle("全局玻璃") }
    item {
        GlassGroup(backdrop) {
            val t = com.haoai.agent.ui.theme.GlassTuning
            // 观察单例 State：滑杆标签即时回显（玻璃组件在 draw 期读，另路重组）
            t.blur; t.lensHeight; t.lensAmountMul; t.veil; t.corner
            GlassSliderRow("磨砂模糊（聊天卡片/弹层）", dpText(t.blur), "影响：消息卡片、弹层、侧边抽屉、任务面板", t.blur, 0f..16f, backdrop, step = 0.5f, onEnd = { vm.persistGlass() }) { t.blur = it }
            HorizontalDivider(Modifier.padding(horizontal = 14.dp), color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f))
            GlassSliderRow("聊天卡片白雾", "${Math.round(t.veil * 100)}%", "影响：消息卡片、弹层", t.veil, 0f..0.9f, backdrop, step = 0.01f, onEnd = { vm.persistGlass() }) { t.veil = it }
            HorizontalDivider(Modifier.padding(horizontal = 14.dp), color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f))
            GlassSliderRow("折射环带宽度", dpText(t.lensHeight), "影响：所有玻璃表面", t.lensHeight, 0f..40f, backdrop, step = 0.5f, onEnd = { vm.persistGlass() }) { t.lensHeight = it }
            HorizontalDivider(Modifier.padding(horizontal = 14.dp), color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f))
            GlassSliderRow("折射强度倍数", "×${"%.1f".format(t.lensAmountMul)}", "影响：所有玻璃表面（0 = 关折射）", t.lensAmountMul, 0f..4f, backdrop, step = 0.1f, onEnd = { vm.persistGlass() }) { t.lensAmountMul = it }
            HorizontalDivider(Modifier.padding(horizontal = 14.dp), color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f))
            GlassSliderRow("玻璃圆角", dpText(t.corner), "影响：页面卡片、弹层顶角（聊天卡片圆角固定 / 顶栏方角 / 输入框胶囊形固定）", t.corner, 0f..32f, backdrop, step = 0.5f, onEnd = { vm.persistGlass() }) { t.corner = it }
            HorizontalDivider(
                Modifier.padding(horizontal = 14.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f)
            )
            ToggleRow(
                title = "整面折射",
                subtitle = "折射覆盖整个表面（默认只在边缘一圈）",
                checked = t.lensFull,
                onChange = { t.lensFull = it; vm.persistGlass() },
                backdrop = backdrop
            )
            ToggleRow(
                title = "边缘色差",
                subtitle = "玻璃边缘红蓝分离（更真实的厚玻璃感）",
                checked = t.ca,
                onChange = { t.ca = it; vm.persistGlass() },
                backdrop = backdrop
            )
            HorizontalDivider(
                Modifier.padding(horizontal = 14.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f)
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "折射/模糊全局统一生效；白雾分聊天/页面两档",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { vm.resetGlass() }) { Text("还原默认") }
            }
        }
    }
    item { SectionTitle("页面玻璃卡") }
    item {
        GlassGroup(backdrop) {
            val t = com.haoai.agent.ui.theme.GlassTuning
            t.pageBlur; t.pageVeil
            GlassSliderRow("页面卡磨砂", dpText(t.pageBlur), "影响：设置主页、各子页、记忆/技能等管理页卡片", t.pageBlur, 0f..16f, backdrop, step = 0.5f, onEnd = { vm.persistGlass() }) { t.pageBlur = it }
            HorizontalDivider(Modifier.padding(horizontal = 14.dp), color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f))
            GlassSliderRow("页面卡白雾", "${Math.round(t.pageVeil * 100)}%", "影响：同上（独立于聊天卡片；任何壁纸深浅下都生效）", t.pageVeil, 0f..0.9f, backdrop, step = 0.01f, onEnd = { vm.persistGlass() }) { t.pageVeil = it }
        }
    }
    item { SectionTitle("顶栏专属") }
    item {
        GlassGroup(backdrop) {
            val t = com.haoai.agent.ui.theme.GlassTuning
            t.barBlur; t.barVeil
            GlassSliderRow("顶栏模糊", dpText(t.barBlur), "影响：顶栏（仅壁纸模式；素色底走固定规范）", t.barBlur, 0f..24f, backdrop, step = 0.5f, onEnd = { vm.persistGlass() }) { t.barBlur = it }
            HorizontalDivider(Modifier.padding(horizontal = 14.dp), color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f))
            GlassSliderRow("顶栏白雾", "${Math.round(t.barVeil * 100)}%", "影响：顶栏", t.barVeil, 0f..0.9f, backdrop, step = 0.01f, onEnd = { vm.persistGlass() }) { t.barVeil = it }
        }
    }
    item { SectionTitle("输入框专属") }
    item {
        GlassGroup(backdrop) {
            val t = com.haoai.agent.ui.theme.GlassTuning
            t.inputBlur; t.inputVeil
            GlassSliderRow("输入框模糊", dpText(t.inputBlur), "影响：底部输入框（常年压在正文上）", t.inputBlur, 0f..16f, backdrop, step = 0.5f, onEnd = { vm.persistGlass() }) { t.inputBlur = it }
            HorizontalDivider(Modifier.padding(horizontal = 14.dp), color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f))
            GlassSliderRow("输入框白雾", "${Math.round(t.inputVeil * 100)}%", "影响：输入框（独立于卡片）", t.inputVeil, 0f..0.9f, backdrop, step = 0.01f, onEnd = { vm.persistGlass() }) { t.inputVeil = it }
        }
    }

    // ── 动画风格：三套全局动画语言（Motion.kt），真机即切即看 ──
    item { SectionTitle("动画风格") }
    item {
        HaoGroup(backdrop = backdrop) {
            HaoRow(
                title = "液态玻璃",
                subtitle = MotionStyle.Liquid.label + " · spring 回弹，弹层上浮，底部弹窗越界",
                tintIndex = 2,
                onClick = { MotionTheme.style = MotionStyle.Liquid },
                trailing = {
                    if (MotionTheme.style == MotionStyle.Liquid) HaoChip("使用中", HaoTone.Accent)
                },
                showChevron = false
            )
            HaoRow(
                title = "丝滑响应",
                subtitle = MotionStyle.Snappy.label + " · 无回弹，120-170ms，跟手优先",
                tintIndex = 4,
                onClick = { MotionTheme.style = MotionStyle.Snappy },
                trailing = {
                    if (MotionTheme.style == MotionStyle.Snappy) HaoChip("使用中", HaoTone.Accent)
                },
                divider = true,
                showChevron = false
            )
            HaoRow(
                title = "柔和渐显",
                subtitle = MotionStyle.Gentle.label + " · 强缓动淡入，安静不抢戏",
                tintIndex = 5,
                onClick = { MotionTheme.style = MotionStyle.Gentle },
                trailing = {
                    if (MotionTheme.style == MotionStyle.Gentle) HaoChip("使用中", HaoTone.Accent)
                },
                divider = true,
                showChevron = false
            )
        }
    }
}

/** 玻璃调参滑杆行：标题+数值同行，下面一行"影响范围"说明；拖动直改单例（实时预览），松手落盘。
 *  [step] > 0 时按步进吸附（0.5dp 精调 / 0.01=1%），值变化仍逐帧回调、标签即显。 */
@Composable
private fun GlassSliderRow(
    title: String,
    valueText: String,
    affect: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    backdrop: com.kyant.backdrop.backdrops.LayerBackdrop,
    step: Float = 0f,
    onEnd: () -> Unit,
    onChange: (Float) -> Unit
) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.weight(1f))
            Text(
                valueText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            affect,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        )
        LiquidSlider(
            value = { value },
            onValueChange = { v ->
                onChange(if (step > 0f) Math.round(v / step).toFloat() * step else v)
            },
            onValueChangeFinished = onEnd,
            valueRange = range,
            visibilityThreshold = 0.01f,
            backdrop = backdrop,
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp)
        )
    }
}

/** dp 文案：整数不带小数；0.5 步进值带一位（4dp / 4.5dp）。 */
private fun dpText(v: Float): String =
    if (v % 1f == 0f) "${v.toInt()}dp" else "%.1f".format(v) + "dp"

/**
 * 玻璃质感预览卡（方案定稿 2026-10-08 效果图确认）：
 * - **不透明实底**（surfaceAlpha=1：浅色纯白 / 深色板岩，glassSurfaceColor 规范）——
 *   用户确认透底会干扰演示判读；blur/lens 置 0（表面已不透明，采样无从呈现）；
 * - 圆角吃玻璃圆角滑杆；fillMaxWidth 走列表同一内边距 ⇒ 与选项卡**同宽**；
 * - 纹理与三行演示照旧：图案层只画网格线（opaqueBase=false），三行各吃各档，
 *   磨砂压在网格上变化可见（顶栏/卡片/输入框档）。2026-10-09 曾加第四行"页面卡"
 *   把全部行高挤压变形（用户实锤"预览修坏了"），已回滚到定稿版式。
 * 自带局部采样层（不依赖页面 backdrop，避开共享画布成环的坑）。
 */
@Composable
private fun GlassPreviewCard(
    agentName: String,
    agentEmoji: String,
    backdrop: com.kyant.backdrop.backdrops.LayerBackdrop
) {
    val t = com.haoai.agent.ui.theme.GlassTuning
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val darkState = rememberUpdatedState(dark)
    // ⚠️ 测试图案走 CanvasBackdrop（采样时在玻璃坐标系直接执行绘制，库 demo 同款）：
    // 此前用 Box.layerBackdrop 挂载 + 玻璃采样它，实测三行玻璃的 blur/采样整条失效
    //（只画白雾、测试字清晰裸露，连 15dp 档顶栏都不糊）——挂载路径在预览卡不工作。
    // CanvasBackdrop 无挂载依赖，每行玻璃各自得到完整图案 + 居中一行测试字，
    // blur/白雾正常作用（"每栏背景都有一行字"，2026-10-09 用户诉求）。
    // 深浅主题经 darkState（绘制期读 State 注册订阅，翻主题即重画）。
    val patternBackdrop = com.kyant.backdrop.backdrops.rememberCanvasBackdrop {
        com.haoai.agent.ui.common.drawRefractionTestPattern(
            scope = this, dark = darkState.value, opaqueBase = false, gridDp = 20f
        )
    }
    Box(
        Modifier
            .fillMaxWidth()
            .height(140.dp)
    ) {
        // 实底面板（悬浮三件套与选项卡同款分层）；活形状防圆角滑杆同类残影
        com.haoai.agent.ui.common.GlassPanel(
            backdrop = backdrop,
            modifier = Modifier.matchParentSize(),
            radius = t.corner.dp,
            shapeProvider = { androidx.compose.foundation.shape.RoundedCornerShape(t.corner.dp) },
            surfaceAlpha = 1f,
            blurRadius = 0.dp,
            lensRadius = 0.dp,
            lensAmountMul = 0f,
            chromaticAberration = false,
            floating = true
        ) {}
        // 三表面纵向叠放（6dp 间距）：尺寸按"文字一行放得下"定，不追求极限压缩
        Column(
            Modifier.matchParentSize().padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // 顶栏（方角，全宽，绑顶栏档）
            GlassPanel(
                backdrop = patternBackdrop,
                modifier = Modifier.fillMaxWidth().height(30.dp),
                radius = 0.dp,
                lensRadius = t.lensHeight.dp,
                lensAmountMul = t.lensAmountMul,
                blurRadius = t.barBlur.dp,
                chromaticAberration = t.ca,
                lensFull = t.lensFull,
                surfaceAlpha = t.barVeil,
                border = false
            ) {
                Text(
                    "顶栏",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = 8.dp, top = 7.dp)
                )
            }
            // 卡片（圆角可调，绑聊天档 t.blur/t.veil；页面档演示不放这里——
            // 预览卡版式是用户三轮效果图定稿的，2026-10-09 曾加第四行挤压全部尺寸，已恢复）
            GlassPanel(
                backdrop = patternBackdrop,
                modifier = Modifier.fillMaxWidth().height(44.dp),
                radius = t.corner.dp,
                lensRadius = t.lensHeight.dp,
                lensAmountMul = t.lensAmountMul,
                blurRadius = t.blur.dp,
                chromaticAberration = t.ca,
                lensFull = t.lensFull,
                surfaceAlpha = t.veil
            ) {
                Text(
                    "$agentEmoji $agentName · 这就帮你查呀～",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp)
                )
            }
            // 输入框（胶囊形固定，绑输入框档）——placeholder 与聊天页同规则
            GlassPanel(
                backdrop = patternBackdrop,
                modifier = Modifier.fillMaxWidth().height(38.dp),
                radius = 19.dp,
                lensRadius = t.lensHeight.dp,
                lensAmountMul = t.lensAmountMul,
                blurRadius = t.inputBlur.dp,
                chromaticAberration = t.ca,
                lensFull = t.lensFull,
                surfaceAlpha = t.inputVeil
            ) {
                Text(
                    "＋ 给 $agentName 派个活…",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)
                )
            }
        }
    }
}

// ---------- 搜索服务（方案 A：正在使用 + 已添加 + 目录页）----------

/**
 * 「设置 → 搜索服务」。一条主线贯穿全页：**同一时间只生效一家**。
 *
 * 为什么长成这样（2026-09-24 用户反馈）：原先五家平铺成一个列表、每行右侧一枚状态胶囊，
 * 读起来像"五家同时在工作"；实际逻辑是 `WebSearchTool` 只问一家主后端，失败时才按开关
 * 退回内置链（见 WebSearchTool.kt:65/80）。所以顶部先回答"现在是谁在干活"，下面才是
 * "已添加的备选"，没添加的家不出现在这一页——主页长度只跟"你配了几家"有关，
 * 与"一共几家可选"无关，后者在目录页里。
 *
 * 行内不放删除按钮：与「模型大脑」的供应商行同一套规矩（见 brainItems 里那段
 * "行内不再放编辑/删除按钮（视觉噪音、功能重复）"），删除收进长按菜单 + 展开区一条文字链。
 */
private fun LazyListScope.searchItems(
    vm: SettingsViewModel,
    settings: AppSettings,
    context: android.content.Context,
    backdrop: com.kyant.backdrop.backdrops.LayerBackdrop,
    onOpenCatalog: () -> Unit
) {
    val open = makeUrlOpener(context)
    val cur = SearchProviders.backend(settings.searchBackend)
    val added = vm.searchAdded().map { SearchProviders.backend(it) }
    val note = vm.searchTestNote(cur.id)

    item { SectionTitle("正在使用") }
    item {
        GlassGroup(backdrop) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                Text(
                    "当前主后端",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        cur.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    if (!vm.searchReady(cur.id)) HaoChip("待填 ${cur.need}", HaoTone.Warn)
                }
                Text(
                    cur.one + if (cur.id == SearchProviders.BUILTIN) {
                        "。什么都不用填，它同时也是其它家的兜底"
                    } else if (vm.searchFallback()) {
                        "；失败时自动退回内置免 key 链"
                    } else {
                        "；降级已关，这家失败就算检索失败"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 5.dp),
                    lineHeight = 17.sp
                )
                if (note.isNotBlank()) {
                    Spacer(Modifier.height(9.dp))
                    Text(
                        "上次测试 $note",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .background(
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.09f),
                                RoundedCornerShape(7.dp)
                            )
                            .padding(horizontal = 7.dp, vertical = 3.dp)
                    )
                }
            }
            if (cur.id != SearchProviders.BUILTIN) {
                com.haoai.agent.ui.common.HaoDivider()
                ToggleRow(
                    title = "失败时退回内置链",
                    subtitle = "配额用尽 / 网络不通 / 返回空结果，都不让任务直接断",
                    checked = vm.searchFallback(),
                    onChange = { vm.setSearchFallback(it) },
                    backdrop = backdrop
                )
            }
        }
    }

    item { SectionTitle("已添加") }
    item {
        GlassGroup(backdrop) {
            com.haoai.agent.ui.common.HaoHint(
                "点一下即切换，同一时间只生效一家；长按可移除。没填完的家不会被使用，配置留着。"
            )
            added.forEachIndexed { index, b ->
                SearchAddedRow(
                    backend = b,
                    first = index == 0,
                    active = b.id == cur.id,
                    ready = vm.searchReady(b.id),
                    vm = vm,
                    form = if (b.id == cur.id) {
                        {
                            SearchBackendForm(
                                vm = vm, backend = b, backdrop = backdrop,
                                open = open, onRemove = { vm.removeSearchBackend(b.id) }
                            )
                        }
                    } else null
                )
            }
        }
    }

    item {
        GlassGroup(backdrop) {
            HaoRow(
                icon = Icons.Filled.Add,
                title = "添加搜索服务",
                subtitle = "共 ${SearchProviders.CATALOG.size} 家可选 · 添加即设为当前",
                showChevron = true,
                onClick = onOpenCatalog
            )
        }
    }

    item { SectionTitle("检索行为") }
    item {
        var n by remember(settings.searchCount) {
            androidx.compose.runtime.mutableIntStateOf(settings.searchCount)
        }
        GlassGroup(backdrop) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("每次返回条数", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.weight(1f))
                    Text(
                        "$n 条",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                LiquidSlider(
                    value = { n.toFloat() },
                    onValueChange = { v -> n = kotlin.math.round(v).toInt() },
                    onValueChangeFinished = { vm.setSearchCount(n) },
                    valueRange = 3f..8f,
                    visibilityThreshold = 1f,
                    backdrop = backdrop,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                )
                Text(
                    "上限 8 是实测结论：一次调研 20 次检索 × 10 条 ≈ 5.6 万字符进上下文，" +
                        "「成本提醒」就是这么提前触发的。模型自己指定条数时以它为准。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                    lineHeight = 17.sp
                )
            }
            com.haoai.agent.ui.common.HaoDivider()
            com.haoai.agent.ui.common.HaoHint(
                "结果一律标注来源（如「来源：智谱 Web Search」），出问题时能一眼看出是哪家给的——" +
                    "这是无条件行为，所以没有开关。"
            )
        }
    }

    item { SectionTitle("测试") }
    item {
        var query by remember(cur.id) { mutableStateOf("2026年 AI Agent 新变化") }
        var outcome by remember(cur.id) { mutableStateOf<SearchTestOutcome?>(null) }
        var failure by remember(cur.id) { mutableStateOf<String?>(null) }
        val scope = rememberCoroutineScope()
        GlassGroup(backdrop) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                Text("搜索词", style = MaterialTheme.typography.labelMedium)
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = com.haoai.agent.ui.common.glassFieldColors()
                )
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LiquidPillButton(
                        backdrop = backdrop,
                        text = "测试 · ${cur.name}",
                        enabled = !vm.searchTesting,
                        onClick = {
                            outcome = null
                            failure = null
                            scope.launch {
                                vm.searchTest(cur.id, query)
                                    .onSuccess { outcome = it }
                                    .onFailure { failure = it.message ?: "请求失败" }
                            }
                        }
                    )
                    if (vm.searchTesting) {
                        Spacer(Modifier.width(12.dp))
                        CircularProgressIndicator(
                            Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
            outcome?.let { o ->
                com.haoai.agent.ui.common.HaoDivider()
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text(
                        "通过 · ${vm.lastSearchMs} ms · ${o.hits.size} 条 · 来源 ${o.engine}",
                        style = MaterialTheme.typography.labelMedium,
                        color = haoToneMain(HaoTone.Accent)
                    )
                    o.hits.take(3).forEach { h ->
                        Text(
                            h.title.ifBlank { h.url },
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                        Text(
                            h.url,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }
                    if (o.notes.isNotEmpty()) {
                        Text(
                            "沿途失败：" + o.notes.joinToString("；"),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }
            }
            failure?.let { msg ->
                com.haoai.agent.ui.common.HaoDivider()
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text(
                        "失败：$msg",
                        style = MaterialTheme.typography.labelMedium,
                        color = haoToneMain(HaoTone.Danger)
                    )
                    Text(
                        if (cur.id == SearchProviders.BUILTIN)
                            "三家引擎都没出结果：先检查手机的网络/代理。Agent 侧会收到同样的失败说明，" +
                                "然后用手上的材料收尾而不是空转。"
                        else
                            "开着「失败时退回内置链」时任务不会因此失败；关掉则这家不通就是不通。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                        lineHeight = 17.sp
                    )
                }
            }
        }
    }
}

/** 已添加行：单选语义（RadioButton）+ 长按菜单，行内不放删除按钮。 */
@Composable
private fun SearchAddedRow(
    backend: SearchProviders.Backend,
    first: Boolean,
    active: Boolean,
    ready: Boolean,
    vm: SettingsViewModel,
    form: (@Composable () -> Unit)?
) {
    var menu by remember { mutableStateOf(false) }
    val b = backend
    Column(Modifier.fillMaxWidth()) {
        // 分隔线跟着"是不是组内第一行"走，不跟着选中态走：原先只在选中行上方画线，
        // 未选中的家挤在一起没有界限（真机截图里智谱那行直接贴上了上面展开的表单）
        if (!first) com.haoai.agent.ui.common.HaoDivider()
        Row(
            Modifier
                .fillMaxWidth()
                .background(
                    if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.07f)
                    else Color.Transparent
                )
                .combinedClickable(
                    onClick = { vm.setSearchBackend(b.id) },
                    onLongClick = { menu = true }
                )
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            RadioButton(
                selected = active,
                onClick = { vm.setSearchBackend(b.id) },
                modifier = Modifier.size(36.dp)
            )
            Column(Modifier.weight(1f)) {
                Text(
                    b.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium
                )
                Text(
                    b.one,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }
            HaoChip(b.price, if (b.group == SearchProviders.Group.Free) HaoTone.Info else HaoTone.Neutral)
            Spacer(Modifier.width(6.dp))
            when {
                !ready -> HaoChip("待填 ${b.need}", HaoTone.Warn)
                active -> HaoChip("使用中", HaoTone.Accent)
            }
            // 长按菜单：删除收在这里（行内按钮会挤掉副标题的可用宽度）
            androidx.compose.material3.DropdownMenu(
                expanded = menu,
                onDismissRequest = { menu = false }
            ) {
                if (!active) androidx.compose.material3.DropdownMenuItem(
                    text = { Text("设为当前主后端") },
                    onClick = { menu = false; vm.setSearchBackend(b.id) }
                )
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text("测试这家") },
                    onClick = { menu = false; vm.setSearchBackend(b.id) }
                )
                if (b.id != SearchProviders.BUILTIN) androidx.compose.material3.DropdownMenuItem(
                    text = { Text("从已添加中移除", color = MaterialTheme.colorScheme.error) },
                    onClick = { menu = false; vm.removeSearchBackend(b.id) }
                )
            }
        }
        form?.invoke()
    }
}

/** 当前那一家展开着的表单：必填项 → 该家的可选项 → 官网链接 → 移除。 */
@Composable
private fun SearchBackendForm(
    vm: SettingsViewModel,
    backend: SearchProviders.Backend,
    backdrop: com.kyant.backdrop.backdrops.LayerBackdrop,
    open: (String) -> Unit,
    onRemove: () -> Unit
) {
    val b = backend
    Column(Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.03f))) {
        Text(
            b.hint,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 17.sp,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp)
        )
        Text(
            "国内可达性：${b.reach}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)
        )
        if (b.id == SearchProviders.BUILTIN) return@Column
        if (SearchProviders.needsKey(b.id)) {
            SearchKeyField(
                vm = vm, backendId = b.id, site = b.site, siteNote = b.siteNote,
                placeholder = "粘贴 API Key", open = open
            )
        }
        when (b.id) {
            "zhipu" -> SearchTabs(
                label = "引擎档位", backdrop = backdrop,
                tabs = listOf("标准（0.01 元/次）", "高级（0.03 元/次）"),
                selectedIndex = if (vm.searchOption("engine") == "search_pro") 1 else 0,
                onSelected = { vm.setSearchOption("engine", if (it == 1) "search_pro" else "search_std") }
            )

            "bocha" -> ToggleRow(
                title = "返回摘要段",
                subtitle = "每条结果带更长的正文摘要：多花 token，但常常省掉一次 web_fetch",
                checked = vm.searchOption("summary") == "1",
                onChange = { vm.setSearchOption("summary", if (it) "1" else "") },
                backdrop = backdrop
            )

            "doubao" -> SearchTabs(
                label = "检索模式", backdrop = backdrop,
                tabs = listOf("网页搜索（快）", "全球搜索（含海外）"),
                selectedIndex = if (vm.searchOption("mode") == "global") 1 else 0,
                onSelected = { vm.setSearchOption("mode", if (it == 1) "global" else "web") }
            )

            "searxng" -> {
                SearchOptionField(
                    vm, "url", "实例 URL（必填）", "https://searx.example.com",
                    help = "实例要在 settings.yml 的 search.formats 里加 json，否则测试显示 0 条"
                )
                SearchAdvanced {
                    SearchOptionField(vm, "engines", "引擎", "google,bing（逗号分隔）")
                    SearchOptionField(vm, "language", "语言", "zh-CN")
                    SearchOptionField(vm, "username", "用户名", "实例开了 Basic Auth 才填")
                    SearchOptionField(vm, "password", "密码", password = true)
                }
            }

            "custom" -> {
                SearchOptionField(
                    vm, "template", "URL 模板（必填）",
                    "https://api.example.com/search?q={query}&count={count}",
                    help = "{query} 会被 URL 编码后替换进去，缺它这家就用不了"
                )
                SearchAdvanced {
                    SearchOptionField(vm, "path", "结果数组路径", "如 data.items（留空自动探测）")
                    SearchOptionField(vm, "header", "鉴权 Header 名", "Authorization")
                    SearchKeyField(
                        vm = vm, backendId = b.id, site = "", siteNote = "",
                        placeholder = "随鉴权头发过去，可留空", open = open, label = "API Key（可选）"
                    )
                }
            }
        }
        if (b.site.isNotBlank()) {
            com.haoai.agent.ui.common.HaoDivider()
            HaoRow(
                title = "${b.site.substringAfter("//").substringBefore("/")} · 拿 Key / 看文档",
                subtitle = b.siteNote,
                showChevron = true,
                onClick = { open(b.site) }
            )
        }
        com.haoai.agent.ui.common.HaoDivider()
        HaoRow(
            title = "从已添加中移除",
            subtitle = "连同本机保存的 Key 一起删；官网那把 Key 不受影响，随时可再加回来",
            titleColor = MaterialTheme.colorScheme.error,
            onClick = onRemove
        )
    }
}

/** 目录页：全量后端 + 搜索过滤。点一家即添加并设为当前（用户裁定的行为）。 */
@Composable
private fun SearchCatalogPage(
    vm: SettingsViewModel,
    settings: AppSettings,
    backdrop: com.kyant.backdrop.backdrops.LayerBackdrop,
    onBack: () -> Unit
) {
    var q by remember { mutableStateOf("") }
    val added = vm.searchAdded()
    val list = SearchProviders.CATALOG.filter {
        q.isBlank() || it.name.contains(q, true) || it.short.contains(q, true) || it.one.contains(q, true)
    }
    Column {
        GlassGroup(backdrop) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                OutlinedTextField(
                    value = q,
                    onValueChange = { q = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("搜后端名称，如 智谱 / SearXNG") },
                    leadingIcon = {
                        Icon(
                            Icons.Filled.Search, contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    colors = com.haoai.agent.ui.common.glassFieldColors()
                )
                Text(
                    "点一家即添加并设为当前主后端；同一时间只生效一家，其余已添加的只是备选。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 17.sp,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }
        listOf(
            SearchProviders.Group.Free to "开箱可用（免费）",
            SearchProviders.Group.Cn to "国内可直连",
            SearchProviders.Group.Self to "自建与通用"
        ).forEach { (g, title) ->
            val items = list.filter { it.group == g }
            if (items.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                com.haoai.agent.ui.common.HaoSectionTitle(title)
                GlassGroup(backdrop) {
                    items.forEachIndexed { i, b ->
                        if (i > 0) com.haoai.agent.ui.common.HaoDivider()
                        val isAdded = b.id in added
                        HaoRow(
                            title = b.name,
                            subtitle = b.one,
                            onClick = {
                                vm.setSearchBackend(b.id)
                                onBack()
                            },
                            trailing = {
                                HaoChip(b.price, if (g == SearchProviders.Group.Free) HaoTone.Info else HaoTone.Neutral)
                                Spacer(Modifier.width(6.dp))
                                if (isAdded) HaoChip("已添加", HaoTone.Accent)
                                else Text(
                                    "＋ 添加",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        )
                    }
                }
            }
        }
        if (list.isEmpty()) {
            Spacer(Modifier.height(10.dp))
            com.haoai.agent.ui.common.HaoEmptyState(
                backdrop = backdrop,
                icon = Icons.Filled.Search,
                title = "没有匹配「$q」的后端",
                hint = "换个词，或者用「自定义端点」接任何 GET 返回 JSON 的搜索接口"
            )
        }
        Spacer(Modifier.height(10.dp))
        GlassGroup(backdrop) {
            com.haoai.agent.ui.common.HaoHint(
                "没列进来的接口：先用「自定义端点」试（GET 回 JSON 就能接）。" +
                    "POST-only 或要 AK/SK 签名的接口接不了，那种要为它单独写适配器。"
            )
        }
    }
}

/** 两个选项的分段选择器（档位/模式这类二选一，值直接落 searchOptions）。 */
@Composable
private fun SearchTabs(
    label: String,
    backdrop: com.kyant.backdrop.backdrops.LayerBackdrop,
    tabs: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit
) {
    Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        com.haoai.agent.ui.common.LiquidTabRow(
            tabs = tabs,
            selectedIndex = selectedIndex,
            onSelected = onSelected,
            backdrop = backdrop,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}

@Composable
private fun SearchKeyField(
    vm: SettingsViewModel,
    backendId: String,
    site: String,
    siteNote: String,
    placeholder: String,
    open: (String) -> Unit,
    label: String = "API Key"
) {
    val has = vm.hasSearchKey(backendId)
    var draft by remember(backendId) { mutableStateOf("") }
    // 停笔 600ms 才落盘：逐字符 updateSettings = 每字符一次 Keystore 加密 + 配置全量序列化
    LaunchedEffect(draft, backendId) {
        if (draft.isBlank()) return@LaunchedEffect
        kotlinx.coroutines.delay(600)
        vm.setSearchKey(backendId, draft)
    }
    Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it.trim() },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text(if (has) "已保存，粘贴新值即覆盖" else placeholder) },
            trailingIcon = {
                if (has && draft.isBlank()) {
                    TextButton(onClick = { vm.setSearchKey(backendId, "") }) {
                        Text("清除", style = MaterialTheme.typography.labelMedium)
                    }
                }
            },
            visualTransformation = PasswordVisualTransformation(),
            colors = com.haoai.agent.ui.common.glassFieldColors()
        )
        Text(
            if (has) "已保存 ${vm.searchKeyMask(backendId)} · Keystore 加密，备份包默认不含"
            else "未填时这家不可用，会自动退回内置链",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 非密钥配置项：按 `后端.字段` 存，切后端不串值。 */
@Composable
private fun SearchOptionField(
    vm: SettingsViewModel,
    key: String,
    label: String,
    placeholder: String = "",
    help: String? = null,
    password: Boolean = false
) {
    val saved = vm.searchOption(key)
    var draft by remember(key, saved) { mutableStateOf(saved) }
    LaunchedEffect(draft) {
        if (draft == saved) return@LaunchedEffect
        kotlinx.coroutines.delay(600)
        vm.setSearchOption(key, draft)
    }
    Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text(placeholder) },
            visualTransformation = if (password) PasswordVisualTransformation()
            else VisualTransformation.None,
            colors = com.haoai.agent.ui.common.glassFieldColors()
        )
        if (help != null) {
            Text(
                help,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 16.sp
            )
        }
    }
}

/** SearXNG/自定义的高级项：默认收起（全留空也能用），平铺会把必填项挤出视线。 */
@Composable
private fun SearchAdvanced(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    HaoRow(
        title = if (expanded) "收起高级选项" else "高级选项（可全部留空）",
        divider = true,
        onClick = { expanded = !expanded },
        trailing = {
            Icon(
                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    )
    if (expanded) Column { content() }
}
/** 打开外部网页（各家官网/文档）。包一层是因为设置页多处要跳外链。 */
private fun makeUrlOpener(context: android.content.Context): (String) -> Unit = { u ->
    runCatching {
        context.startActivity(
            android.content.Intent(
                android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse(u)
            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}


/**
 * 「用量」页（5.4 · 方案B 时段仪表盘）：
 * 顶部今日/本周/本月胶囊切换联动全页——大数字+环比 → 时段柱状趋势（峰值高亮）→
 * 4 指标卡（调用/成功率/平均耗时/估算费用）→ 每日预算（保留 5.1）→ 分布卡
 * （按模型/按用途/会话 chip 切换）→ 健康度（成功率/工具审批/生成速度）→ 会话排行 → 维护。
 * 全部数据来自 UsageLedger.dashboard()（账本原有 ok/durationMs/tool 字段首次上屏）。
 */
private fun LazyListScope.usageItems(
    vm: SettingsViewModel,
    settings: AppSettings,
    backdrop: com.kyant.backdrop.backdrops.LayerBackdrop,
    onRequestClearLedger: () -> Unit = {}
) {
    item {
        // 读文件较重：进入页面时计算一次（清空后重进更新）
        val periods = androidx.compose.runtime.remember {
            com.haoai.agent.data.UsageLedger.dashboard()
        }
        var range by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(2) }
        var distTab by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
        val stat = periods[range]

        Column {
            // 时段胶囊选择器（全局第一层筛选，联动全页）：
            // LiquidTabRow——通用设置「模型行为」同款底部选项卡效果（玻璃轨道+主题色液态指示器弹性滑动）
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                com.haoai.agent.ui.common.LiquidTabRow(
                    tabs = listOf("今日", "本周", "本月"),
                    selectedIndex = range,
                    onSelected = { range = it },
                    backdrop = backdrop
                )
            }

            // 大数字 + 环比（放在玻璃卡里：整块是"直接压在壁纸上"的，壁纸模式下会与花纹打架）
            GlassGroup(backdrop, modifier = Modifier.padding(horizontal = 16.dp)) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        formatTokens(stat.total),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.size(6.dp))
                    Text(
                        "token",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    Spacer(Modifier.weight(1f))
                    val pct = periodDeltaPct(stat)
                    Text(
                        when {
                            stat.prevTotal <= 0L -> ""
                            pct == null -> "—"
                            else -> {
                                val p = pct.toInt()
                                if (p >= 0) "▲ $p%" else "▼ ${-p}%"
                            }
                        },
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (pct != null && pct < 0.0) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 3.dp)
                    )
                    if (stat.prevTotal > 0L) {
                        Text(
                            "vs ${prevRangeLabel(stat.range)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 3.dp)
                        )
                    }
                }
                // 与"大数字"同处一张卡：拆成两块卡会读成两段互不相干的信息
                Text(
                    "输入 ${formatTokens(stat.inTok)} · 输出 ${formatTokens(stat.outTok)} · " +
                        "LLM ${stat.llmCalls} 次 / 工具 ${stat.toolCalls} 次",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
            }

            // 时段柱状趋势（Canvas 自绘，峰值高亮）
            GlassGroup(backdrop) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                    Text(
                        trendRangeLabel(stat),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    UsageTrendChart(
                        bars = stat.days,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // 4 指标卡：**2×2 网格**（4 个挤在一行时每个只有 ~90dp，
            // "35.2s""¥29.40"这种宽度会被压得贴边，数字也只好缩字号）
            Column(
                Modifier.padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DashboardMetric(backdrop, stat.calls.toString(), "调用", Modifier.weight(1f))
                    DashboardMetric(
                        backdrop,
                        stat.successPct()?.let { "$it%" } ?: "—",
                        "成功率",
                        Modifier.weight(1f),
                        tint = haoToneMain(HaoTone.Accent)
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DashboardMetric(
                        backdrop,
                        stat.avgSeconds()?.let { String.format(java.util.Locale.US, "%.1fs", it) } ?: "—",
                        "平均耗时",
                        Modifier.weight(1f)
                    )
                    DashboardMetric(
                        backdrop,
                        estimatePeriodCost(stat),
                        "估算费用",
                        Modifier.weight(1f),
                        tint = haoToneInk(HaoTone.Warn)
                    )
                }
            }

            // 每日预算（保留 5.1，加进度可视化）
            SectionTitle("每日预算")
            Column {
                GlassGroup(backdrop) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                        val budgetK = settings.dailyTokenBudgetK
                        val usedToday = periods[0].total
                        // 输入框与本行说明并排（原先输入框单独一行、左侧空一大片，重心偏）
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            var budgetText by androidx.compose.runtime.remember(settings.dailyTokenBudgetK) {
                                androidx.compose.runtime.mutableStateOf(
                                    settings.dailyTokenBudgetK.let { if (it == 0) "" else it.toString() }
                                )
                            }
                            LaunchedEffect(budgetText) {
                                if ((budgetText.toIntOrNull() ?: 0) == settings.dailyTokenBudgetK) return@LaunchedEffect
                                kotlinx.coroutines.delay(600)
                                vm.setDailyTokenBudgetK(budgetText.toIntOrNull() ?: 0)
                            }
                            Column(Modifier.weight(1f)) {
                                Text("每日上限", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "超过会先提醒、再自动跳过后台任务",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            OutlinedTextField(
                                value = budgetText,
                                onValueChange = { t ->
                                    budgetText = t.filter { ch -> ch.isDigit() }.take(5)
                                },
                                modifier = Modifier.width(126.dp),
                                singleLine = true,
                                placeholder = { Text("不限") },
                                trailingIcon = { Text("K/日", style = MaterialTheme.typography.labelSmall) },
                                colors = com.haoai.agent.ui.common.glassFieldColors()
                            )
                        }
                        if (budgetK > 0) {
                            Spacer(Modifier.height(10.dp))
                            val budget = budgetK * 1000L
                            val pct = (usedToday * 100 / budget).toInt().coerceAtMost(100)
                            Row(Modifier.fillMaxWidth()) {
                                Text(
                                    "今日已用 ${formatTokens(usedToday)} / ${formatTokens(budget)}",
                                    style = MaterialTheme.typography.labelMedium
                                )
                                Spacer(Modifier.weight(1f))
                                Text(
                                    "$pct%",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = when {
                                        pct >= 100 -> MaterialTheme.colorScheme.error
                                        pct >= 70 -> haoToneInk(HaoTone.Warn)
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            UsageBar(
                                label = "",
                                value = usedToday.coerceAtMost(budget),
                                maxValue = budget,
                                detail = "",
                                tint = when {
                                    pct >= 100 -> MaterialTheme.colorScheme.error
                                    pct >= 70 -> haoToneInk(HaoTone.Warn)
                                    else -> haoToneMain(HaoTone.Warn)
                                },
                                compact = true
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "超 70% 提醒精简；达 100% 后定时任务与云端梦境固化自动跳过（手动对话不中断）。0 = 不限。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // 分布卡：按模型 / 按用途 / 会话 chip 二级切换
            SectionTitle("分布")
            Column {
                GlassGroup(backdrop) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("按模型", "按用途", "会话").forEachIndexed { i, label ->
                                GlassCapChip(selected = distTab == i, label = label, onClick = { distTab = i })
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        when (distTab) {
                            0 -> {
                                if (stat.byModel.isEmpty()) EmptyHint("本时段无 LLM 调用")
                                else stat.byModel.take(6).forEach { m ->
                                    UsageBar(
                                        label = m.model,
                                        value = m.promptTokens + m.completionTokens,
                                        maxValue = stat.byModel.maxOf { it.promptTokens + it.completionTokens },
                                        detail = "${formatTokens(m.promptTokens + m.completionTokens)} · ${m.calls} 次",
                                        tint = haoToneMain(HaoTone.Info)
                                    )
                                }
                            }
                            1 -> {
                                if (stat.byPurpose.isEmpty()) EmptyHint("本时段无记录")
                                else stat.byPurpose.take(6).forEach { p ->
                                    UsageBar(
                                        label = purposeLabel(p.purpose),
                                        value = p.promptTokens + p.completionTokens,
                                        maxValue = stat.byPurpose.maxOf { it.promptTokens + it.completionTokens },
                                        detail = "${formatTokens(p.promptTokens + p.completionTokens)} · ${p.calls} 次",
                                        tint = haoToneMain(HaoTone.Accent)
                                    )
                                }
                            }
                            else -> {
                                val list = stat.bySession.filter { it.promptTokens + it.completionTokens > 0 }.ifEmpty { stat.bySession }
                                if (list.size < 1) EmptyHint("本时段无会话记录")
                                else list.take(5).forEach { s ->
                                    val title = runCatching {
                                        vm.sessionTitleOf(s.sessionId)
                                    }.getOrNull().orEmpty().ifBlank { "会话 ${s.sessionId.take(8)}" }
                                    UsageBar(
                                        label = title,
                                        value = s.promptTokens + s.completionTokens,
                                        maxValue = list.maxOf { it.promptTokens + it.completionTokens },
                                        detail = "${formatTokens(s.promptTokens + s.completionTokens)} · ${s.calls} 次调用",
                                        tint = haoToneMain(HaoTone.Warn)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 健康度：成功率 / 工具审批 / 生成速度（账本 ok/durationMs/policyDecision 首次上屏）
            SectionTitle("健康度")
            Column {
                GlassGroup(backdrop) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        val total = stat.calls
                        val succ = stat.successPct()
                        HealthRow(
                            label = "成功率",
                            value = if (succ == null) "—" else "$succ% · 失败 ${stat.failCalls} 次",
                            ratio = (succ ?: 0) / 100f,
                            tint = haoToneMain(HaoTone.Accent)
                        )
                        Spacer(Modifier.height(9.dp))
                        if (stat.toolCalls > 0) {
                            HealthRow(
                                label = "工具审批",
                                value = "通过 ${stat.approved} · 拒绝 ${stat.denied} · 拦截 ${stat.blocked}",
                                ratio = if (stat.approved + stat.denied + stat.blocked == 0) 0f
                                else stat.approved.toFloat() / (stat.approved + stat.denied + stat.blocked),
                                tint = haoToneMain(HaoTone.Accent)
                            )
                            Spacer(Modifier.height(9.dp))
                        }
                        val speed = stat.tps()
                        HealthRow(
                            label = "平均生成速度",
                            value = speed?.let { String.format(java.util.Locale.US, "%.1f tok/s", it) } ?: "—",
                            ratio = ((speed ?: 0.0) / 40.0).coerceIn(0.0, 1.0).toFloat(),
                            tint = haoToneMain(HaoTone.Info)
                        )
                        if (total == 0) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "本时段无调用记录",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // 工具调用 TopN（tool 字段首次上屏）
            if (stat.byTool.isNotEmpty()) {
                SectionTitle("工具调用")
                Column {
                    GlassGroup(backdrop) {
                        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                            stat.byTool.take(5).forEach { t ->
                                UsageBar(
                                    label = t.tool,
                                    value = t.calls.toLong(),
                                    maxValue = stat.byTool.maxOf { it.calls }.toLong().coerceAtLeast(1),
                                    detail = "${t.calls} 次",
                                    tint = haoToneMain(HaoTone.Info)
                                )
                            }
                        }
                    }
                }
            }

            // 会话排行（独立保留：时段内 Top5）
            SectionTitle("会话排行")
            Column {
                GlassGroup(backdrop) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        val list = stat.bySession
                        if (list.size < 2) {
                            EmptyHint("本时段会话不足")
                        } else {
                            list.take(5).forEach { s ->
                                val title = runCatching {
                                    vm.sessionTitleOf(s.sessionId)
                                }.getOrNull().orEmpty().ifBlank { "会话 ${s.sessionId.take(8)}" }
                                UsageBar(
                                    label = title,
                                    value = s.promptTokens + s.completionTokens,
                                    maxValue = list.maxOf { it.promptTokens + it.completionTokens },
                                    detail = "${formatTokens(s.promptTokens + s.completionTokens)} · ${s.calls} 次调用",
                                    tint = haoToneMain(HaoTone.Warn)
                                )
                            }
                        }
                    }
                }
            }

            SectionTitle("维护")
            Column {
                GlassGroup(backdrop) {
                    // 危险操作行（非开关）：点击弹根级确认窗
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onRequestClearLedger() }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("清空账本", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "删除全部用量记录（不影响对话与记忆）",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            "清空",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
}

/** 环比对比文案（今日→昨天 / 本周→上周 / 本月→上月）。 */
private fun prevRangeLabel(range: Int): String = when (range) {
    0 -> "昨天"; 1 -> "上周"; else -> "上月"
}

/** 环比百分比（null=无法计算）。 */
private fun periodDeltaPct(s: com.haoai.agent.data.UsageLedger.PeriodStats): Double? {
    if (s.prevTotal <= 0L) return null
    return (s.total - s.prevTotal).toDouble() / s.prevTotal * 100.0
}

/**
 * 时段估算费用：费率来自内置 models.dev 目录快照（app/src/main/assets/models-dev-api.json），
 * 仅匹配到目录条目时计费，未收录的模型（mock/私有名）按 0 计——「估算」口径，不承诺精确。
 * 单价单位：USD / 百万 token（目录 spec 原生单位），展示折算 CNY（≈7.2 汇率）。
 */
private fun estimatePeriodCost(s: com.haoai.agent.data.UsageLedger.PeriodStats): String {
    if (s.total <= 0L) return "—"
    var usd = 0.0
    var priced = false
    for (m in s.byModel) {
        val rate = com.haoai.agent.data.ModelCatalog.pricePerMillion(m.model) ?: continue
        priced = true
        usd += m.promptTokens / 1_000_000.0 * rate.first + m.completionTokens / 1_000_000.0 * rate.second
    }
    if (!priced) return "—"
    val cny = usd * 7.2
    return if (cny >= 100) "¥${cny.toInt()}" else String.format(java.util.Locale.US, "¥%.2f", cny)
}

/** 趋势卡标题：按时段描述范围。 */
private fun trendRangeLabel(s: com.haoai.agent.data.UsageLedger.PeriodStats): String = when (s.range) {
    0 -> "今日 · 每 4 小时"
    1 -> "本周 · 按日（周一至今）"
    else -> "本月 · 按日（1 日至今）"
}

/** 4 指标卡单格：液态玻璃质感（GlassCard 折射采样，与全 App 玻璃语言一致）。 */
@Composable
private fun DashboardMetric(
    backdrop: com.kyant.backdrop.backdrops.LayerBackdrop,
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.primary
) {
    com.haoai.agent.ui.common.GlassCard(
        onClick = {},
        backdrop = backdrop,
        shape = RoundedCornerShape(14.dp),
        surfaceAlpha = com.haoai.agent.ui.theme.haoTileSurfaceAlpha(),
        tint = tint.copy(alpha = 0.10f),
        lensRadius = 12.dp,
        // 2026-10-09 方案A：瓦片=页面玻璃卡档
        pageTier = true,
        modifier = modifier
    ) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 9.dp)) {
            Text(
                value,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = tint,
                maxLines = 1
            )
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

/** 健康度单行：左标签右数值 + 底部比例条。 */
@Composable
private fun HealthRow(label: String, value: String, ratio: Float, tint: Color) {
    Column {
        Row(Modifier.fillMaxWidth()) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.weight(1f))
            Text(
                value,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.10f))
        ) {
            Box(
                Modifier
                    .fillMaxWidth(ratio.coerceIn(0f, 1f))
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(tint)
            )
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 6.dp)
    )
}

/**
 * 时段柱状趋势图（Canvas 自绘，对齐 MemoryScreen 健康度条绘制风格）：
 * 今日=6 桶（4 小时）/ 本周=7 桶 / 本月=按日桶；峰值桶用深色强调 + 峰值文案。
 */
@Composable
private fun UsageTrendChart(bars: List<com.haoai.agent.data.UsageLedger.DayBar>, tint: Color) {
    val maxTok = bars.maxOfOrNull { it.tokens } ?: 0L
    val peak = bars.firstOrNull { it.peak }
    Column {
        if (bars.isNotEmpty()) {
            Text(
                if (peak != null && peak.tokens > 0) "峰值 ${peak.label} · ${formatTokens(peak.tokens)}"
                else "本时段暂无数据",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp)
            )
        }
        androidx.compose.foundation.Canvas(
            Modifier
                .fillMaxWidth()
                .height(88.dp)
        ) {
            if (bars.isEmpty() || maxTok <= 0L) return@Canvas
            val n = bars.size
            val gap = 4.dp.toPx()
            val barW = (size.width - gap * (n - 1)) / n
            bars.forEachIndexed { i, b ->
                val h = if (maxTok > 0) (b.tokens.toFloat() / maxTok) * (size.height - 14.dp.toPx()) else 0f
                if (b.tokens > 0L) {
                    drawRoundRect(
                        color = if (b.peak) tint.copy(alpha = 0.75f) else tint.copy(alpha = 0.45f),
                        topLeft = androidx.compose.ui.geometry.Offset(i * (barW + gap), size.height - 12.dp.toPx() - h),
                        size = androidx.compose.ui.geometry.Size(barW, h.coerceAtLeast(3.dp.toPx())),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx())
                    )
                } else {
                    drawRoundRect(
                        color = tint.copy(alpha = 0.10f),
                        topLeft = androidx.compose.ui.geometry.Offset(i * (barW + gap), size.height - 12.dp.toPx() - 3.dp.toPx()),
                        size = androidx.compose.ui.geometry.Size(barW, 3.dp.toPx()),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx())
                    )
                }
            }
        }
        // 桶标签：桶多时隔一个标注，避免重叠
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val step = if (bars.size > 10) ((bars.size + 4) / 5) else 1
            bars.forEachIndexed { i, b ->
                androidx.compose.foundation.layout.BoxWithConstraints(Modifier.weight(1f)) {
                    if (i % step == 0 || i == bars.size - 1) {
                        Text(
                            b.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun UsageBar(
    label: String,
    value: Long,
    maxValue: Long,
    detail: String,
    tint: Color,
    compact: Boolean = false
) {
    Column(Modifier.padding(vertical = 5.dp)) {
        if (!compact || label.isNotEmpty() || detail.isNotEmpty()) {
            Row(Modifier.fillMaxWidth()) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        androidx.compose.foundation.Canvas(
            Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
                .height(6.dp)
        ) {
            val ratio = if (maxValue > 0) (value.toFloat() / maxValue).coerceIn(0f, 1f) else 0f
            val w = size.width * ratio
            drawRoundRect(
                color = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.06f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx())
            )
            if (w > 0f) {
                drawRoundRect(
                    color = tint,
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()),
                    size = androidx.compose.ui.geometry.Size(w, size.height)
                )
            }
        }
    }
}

private fun purposeLabel(p: String): String = when (p) {
    "chat" -> "对话"
    "subagent" -> "子代理"
    "memory" -> "记忆提取"
    "title" -> "会话标题"
    "btw" -> "附带提问"
    "compact" -> "上下文压缩"
    "dream" -> "梦境固化"
    else -> p
}

private fun LazyListScope.aboutItems(
    vm: SettingsViewModel,
    settings: AppSettings,
    backdrop: com.kyant.backdrop.backdrops.LayerBackdrop
) {
    // ── 1. 品牌头 ───────────────────────────────────────────────
    // 注意：本函数是 LazyListScope 扩展（非 @Composable），CompositionLocal 与
    // 可组合调用一律要放进 item { } 里
    item {
        val ctx = androidx.compose.ui.platform.LocalContext.current
        val ver = runCatching {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
        }.getOrNull() ?: "dev"
        GlassGroup(backdrop) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    com.haoai.agent.ui.common.HaoBadge(
                        icon = Icons.Filled.SmartToy,
                        tone = HaoTone.Accent
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "HaoAI",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "v$ver",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "手机上的 AI Agent —— 云端大脑负责思考，端侧能力负责动手：记事、操作手机、定时执行。",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("三层记忆", "技能自进化", "手机自动化").forEach {
                        com.haoai.agent.ui.common.HaoChip(it, HaoTone.Neutral)
                    }
                }
            }
        }
    }

    // ── 2. 能力概览 ─────────────────────────────────────────────
    item { SectionTitle("能力概览") }
    item {
        val counts = vm.homeCounts
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassStatTile(backdrop, "${counts.first}", "长期记忆", haoToneMain(HaoTone.Accent), Modifier.weight(1f))
            GlassStatTile(backdrop, "${counts.third}", "技能", haoToneMain(HaoTone.Accent), Modifier.weight(1f))
            GlassStatTile(backdrop, "${vm.sessionCount()}", "会话", haoToneMain(HaoTone.Info), Modifier.weight(1f))
            GlassStatTile(backdrop, "${counts.second}", "今日日志", haoToneMain(HaoTone.Info), Modifier.weight(1f))
        }
    }

    // ── 3. 运行状态 ─────────────────────────────────────────────
    item { SectionTitle("运行状态") }
    item {
        val provider = settings.providers.find { it.id == settings.activeProviderId }
            ?: settings.providers.firstOrNull()
        val localModel = vm.llamaModelFile()
        // 发行版状态要读盘：进页面时取一次（与 Linux 页同一套 state 判定）
        var distroLabel by androidx.compose.runtime.remember {
            androidx.compose.runtime.mutableStateOf("读取中…")
        }
        androidx.compose.runtime.LaunchedEffect(Unit) {
            distroLabel = runCatching {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val st = vm.distros.statuses()
                    val readyName = st.firstOrNull {
                        it.state == com.haoai.agent.platform.sandbox.DistroManager.State.READY
                    }?.distro?.name
                    when {
                        st.isEmpty() -> "未安装 · 到「Linux 环境」安装"
                        readyName != null -> "$readyName · 已就绪"
                        st.any { it.state == com.haoai.agent.platform.sandbox.DistroManager.State.DAMAGED } ->
                            "rootfs 已损坏 · 建议重装"
                        else -> "未就绪"
                    }
                }
            }.getOrDefault("不可用")
        }
        GlassGroup(backdrop) {
            HaoRow(
                icon = Icons.Filled.SmartToy,
                title = "云端服务",
                subtitle = if (provider == null) "未配置" else "${provider.name} · ${provider.model}",
                trailing = {
                    HaoChip(
                        if (provider == null) "未配置" else "已配置",
                        if (provider == null) HaoTone.Warn else HaoTone.Accent
                    )
                }
            )
            HaoRow(
                icon = Icons.Filled.Memory,
                title = "端侧模型",
                subtitle = localModel ?: "未加载本地 GGUF",
                trailing = {
                    HaoChip(
                        if (localModel == null) "未加载" else "已加载",
                        if (localModel == null) HaoTone.Neutral else HaoTone.Accent
                    )
                },
                divider = true
            )
            HaoRow(
                icon = Icons.Filled.Terminal,
                title = "推理后端",
                subtitle = vm.backendLabel(),
                divider = true
            )
            HaoRow(
                icon = Icons.Filled.Construction,
                title = "Linux 沙箱",
                subtitle = distroLabel,
                divider = true
            )
            HaoRow(
                icon = Icons.Filled.Folder,
                title = "工作空间",
                subtitle = vm.workspaceName(),
                divider = true
            )
            HaoRow(
                icon = Icons.Filled.Security,
                title = "权限模式",
                subtitle = permissionLabel(settings.permissionMode),
                divider = true
            )
        }
    }

    // ── 4. 数据与存储 ───────────────────────────────────────────
    item { SectionTitle("数据与存储") }
    item {
        GlassGroup(backdrop) {
            HaoRow(
                title = "会话记录",
                subtitle = "${vm.sessionCount()} 个 · ${vm.sizeLabel(vm.scopeBytes(com.haoai.agent.platform.BackupScope.SESSIONS))}"
            )
            HaoRow(
                title = "记忆与日志",
                subtitle = "${vm.homeCounts.first} 条记忆 · ${vm.sizeLabel(vm.scopeBytes(com.haoai.agent.platform.BackupScope.MEMORY))}",
                divider = true
            )
            HaoRow(
                title = "技能",
                subtitle = "${vm.homeCounts.third} 个 · ${vm.sizeLabel(vm.scopeBytes(com.haoai.agent.platform.BackupScope.SKILLS))}",
                divider = true
            )
            HaoRow(
                title = "待办与用量账本",
                subtitle = vm.sizeLabel(vm.scopeBytes(com.haoai.agent.platform.BackupScope.TASKS)),
                divider = true
            )
            HaoRow(
                title = "配置快照",
                subtitle = vm.snapshotLabel(),
                divider = true
            )
            HaoRow(
                title = "上次导出到设备外",
                subtitle = vm.lastExportLabel(),
                divider = true
            )
        }
    }

    // ── 5. 运行环境 ─────────────────────────────────────────────
    item { SectionTitle("运行环境") }
    item {
        val ctx = androidx.compose.ui.platform.LocalContext.current
        var copied by androidx.compose.runtime.remember {
            androidx.compose.runtime.mutableStateOf(false)
        }
        GlassGroup(backdrop) {
            HaoRow(
                title = "系统",
                subtitle = "Android ${android.os.Build.VERSION.RELEASE}" +
                    "（API ${android.os.Build.VERSION.SDK_INT}）"
            )
            HaoRow(
                title = "设备",
                subtitle = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}" +
                    " · ${android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "-"}",
                divider = true
            )
            HaoRow(
                title = "数据目录",
                subtitle = if (copied) "已复制到剪贴板" else vm.dataDirPath(),
                divider = true,
                onClick = {
                    runCatching {
                        val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                            as android.content.ClipboardManager
                        cm.setPrimaryClip(
                            android.content.ClipData.newPlainText("HaoAI 数据目录", vm.dataDirPath())
                        )
                    }
                    copied = true
                }
            )
        }
    }

    // ── 6. 关于 ─────────────────────────────────────────────────
    item { SectionTitle("关于") }
    item {
        val ctx = androidx.compose.ui.platform.LocalContext.current
        GlassGroup(backdrop) {
            HaoRow(
                title = "项目主页",
                subtitle = "github.com/a953179255/HaoAI",
                showChevron = true,
                onClick = {
                    runCatching {
                        ctx.startActivity(
                            android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse("https://github.com/a953179255/HaoAI")
                            )
                        )
                    }
                }
            )
            com.haoai.agent.ui.common.HaoHint(
                "基于 Kotlin 与 Jetpack Compose 构建；液态玻璃效果来自开源库 Kyant0/AndroidLiquidGlass。" +
                    "全部数据保存在本机应用目录，除你自己配置的模型服务外不会上传到任何服务器。"
            )
        }
    }
}

private fun formatTokens(n: Long): String = when {
    n >= 1_000_000 -> String.format(java.util.Locale.US, "%.2fM", n / 1_000_000.0)
    n >= 1_000 -> String.format(java.util.Locale.US, "%.1fK", n / 1_000.0)
    else -> "$n"
}

@Composable
private fun SectionTitle(text: String) {
    // 统一到共享段落标题（子页与首页同一套间距/竖条规范）
    com.haoai.agent.ui.common.HaoSectionTitle(text)
}

/**
 * 二级页通用玻璃分组容器。
 *
 * 2026-09-21 统一：委托给共享 [com.haoai.agent.ui.common.HaoGroup]，
 * 与设置首页同一材质（白 58% 玻璃 + 库原生三件套分层）、同一圆角（16dp）。
 * 原先这里是 radius 18dp / surfaceAlpha 0.30f 的另一套参数 —— 同一个 App
 * 两套玻璃语言也是"凌乱"的一部分。
 *
 * 注意：HaoGroup 内部已自带行分隔线能力（`HaoRow(divider = true)`），
 * 老的 `Column(padding(vertical = 6.dp))` 会破坏"行通栏"（按压高亮要贴卡缘），
 * 故这里不再加垂直内边距。
 */
@Composable
private fun GlassGroup(
    backdrop: com.kyant.backdrop.backdrops.LayerBackdrop,
    modifier: Modifier = Modifier,
    refract: Boolean? = null,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    com.haoai.agent.ui.common.HaoGroup(
        backdrop = backdrop,
        modifier = modifier,
        refract = refract,
        content = content
    )
}

/** 液态玻璃胶囊按钮（主操作），替代普通 Material Button。 */
@Composable
private fun LiquidPillButton(
    backdrop: com.kyant.backdrop.backdrops.LayerBackdrop,
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    emphasized: Boolean = true,
    /** 语义档位；null = 按 [emphasized] 推导（兼容老调用）。破坏性操作用 Danger。 */
    level: com.haoai.agent.ui.theme.HaoButtonLevel? = null,
    refract: Boolean? = null,
    onClick: () -> Unit
) {
    val resolved = level ?: if (emphasized)
        com.haoai.agent.ui.theme.HaoButtonLevel.Primary
    else com.haoai.agent.ui.theme.HaoButtonLevel.Secondary
    val (bgColor, fgColor) = com.haoai.agent.ui.theme.haoButtonColors(resolved)
    com.haoai.agent.ui.common.LiquidGlassButton(
        onClick = onClick,
        backdrop = backdrop,
        shape = RoundedCornerShape(percent = 50),
        enabled = enabled,
        refract = refract,
        // 禁用观感由 LiquidGlassButton 的整体 opacity(0.45) 统一处理，
        // 这里不再针对 enabled 降 surfaceColor（双层降透明会糊成一团）
        surfaceColor = bgColor,
        modifier = modifier
    ) {
        Text(
            text,
            color = if (enabled) fgColor else fgColor.copy(alpha = 0.6f),
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 9.dp)
        )
    }
}

/** 统计数字小瓷砖（玻璃质感），用于概览排版。 */
@Composable
private fun GlassStatTile(
    backdrop: com.kyant.backdrop.backdrops.LayerBackdrop,
    number: String,
    label: String,
    tint: Color,
    modifier: Modifier = Modifier,
    refract: Boolean? = null
) {
    com.haoai.agent.ui.common.GlassCard(
        onClick = {},
        backdrop = backdrop,
        shape = RoundedCornerShape(16.dp),
        surfaceAlpha = com.haoai.agent.ui.theme.haoTileSurfaceAlpha(),
        tint = tint.copy(alpha = 0.10f),
        lensRadius = 12.dp,
        refract = refract,
        // 2026-10-09 方案A：瓦片=页面玻璃卡档
        pageTier = true,
        modifier = modifier
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                number,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = tint
            )
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun ProviderDialog(
    backdrop: com.kyant.backdrop.backdrops.LayerBackdrop,
    draft: com.haoai.agent.ui.ProviderDraft,
    draftError: String?,
    testing: Boolean,
    testResult: Pair<Boolean, String>?,
    fetchingModels: Boolean,
    modelChoices: List<String>?,
    detectingCaps: Boolean,
    detectResult: Pair<Boolean, String>?,
    onChange: (com.haoai.agent.ui.ProviderDraft) -> Unit,
    onPreset: (com.haoai.agent.ui.ProviderPreset) -> Unit,
    onFetchModels: () -> Unit,
    onPickModel: (String) -> Unit,
    onDetectCaps: () -> Unit,
    onSave: () -> Unit,
    onTest: () -> Unit,
    onDismiss: () -> Unit
) {
    // 液态玻璃弹层：独立 Dialog 窗口采样不到 LayerBackdrop，用全屏遮罩 + GlassPanel 承载
    // 弹窗内禁用折射：采样到的是弹窗底下的页面而非弹窗自身，按钮会「穿透背板 + 边缘光晕」
    androidx.compose.runtime.CompositionLocalProvider(
        com.haoai.agent.ui.common.LocalGlassRefract provides false
    ) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.30f))
            .imePadding()
            .clickable(interactionSource = null, indication = null) { onDismiss() },
        contentAlignment = Alignment.Center
    ) {
        GlassPanel(
            backdrop = backdrop,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                // 弹窗自身限定在键盘之上的可见区内：内容多时不再整体溢出屏幕，
                // 而是内部滚动区收缩，标题与底部操作栏始终可见
                .heightIn(max = 560.dp),
            radius = 28.dp,
            surfaceAlpha = 0.92f,
            blurRadius = 28.dp,
            chromaticAberration = true
        ) {
            Column(
                Modifier
                    .clickable(interactionSource = null, indication = null) {}
                    .padding(horizontal = 16.dp)
                    .padding(top = 16.dp, bottom = 12.dp)
            ) {
                Text(
                    if (draft.id == null) "添加模型供应商" else "编辑模型供应商",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                // ── 三段分段（方案一定稿 2026-09-11）：连接 / 模型 / 高级 ──
                // 添加默认落「连接」（第一步选服务商）、编辑默认落「模型」（最高频）
                var pane by remember(draft.id) { mutableStateOf(if (draft.id == null) 0 else 1) }
                com.haoai.agent.ui.common.LiquidTabRow(
                    tabs = listOf(
                        "连接",
                        if (draft.id == null) "模型" else "模型 · " + (listOf(draft.model).filter { it.isNotBlank() }.size + draft.models.size),
                        "高级"
                    ),
                    selectedIndex = pane,
                    onSelected = { pane = it },
                    backdrop = backdrop,
                    modifier = Modifier.padding(top = 10.dp)
                )
                Column(
                    Modifier
                        .padding(top = 10.dp)
                        // weight(1f, fill=false)：内容不足时按实际高度收缩（不撑满），
                        // 内容/键盘挤压时最多吃到剩余空间——操作栏永远不会被顶出屏幕
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    // ═════════ 段 0：连接 ═════════
                    if (pane == 0) {
                        // ── 服务商预设（仅添加模式；编辑=改连接参数，不是换供应商）──
                        if (draft.id == null) {
                            Text(
                                "服务商预设 · 点选即预填",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            androidx.compose.foundation.layout.FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                com.haoai.agent.ui.ProviderPresets.all.forEach { p ->
                                    val selected = draft.name == p.name && draft.baseUrl == p.baseUrl
                                    PresetCard(
                                        label = p.label,
                                        sub = p.sub,
                                        selected = selected,
                                        onClick = { onPreset(p) }
                                    )
                                }
                            }
                        }
                        // ── 连接字段（名称 / 协议 / Base URL / API Key）──
                        Text(
                            "连接",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        com.haoai.agent.ui.common.CompactGlassField(
                            value = draft.name,
                            onValueChange = { v -> onChange(draft.copy(name = v)) },
                            label = "名称",
                            placeholder = "留空自动命名"
                        )
                        // 协议选择：独立一行（用户反馈：状态与协议不挤）
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                "协议",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            GlassCapChip(
                                selected = draft.protocol == "openai_compat",
                                label = "OpenAI 兼容",
                                onClick = { onChange(draft.copy(protocol = "openai_compat")) }
                            )
                            GlassCapChip(
                                selected = draft.protocol == "anthropic",
                                label = "Anthropic 原生",
                                onClick = { onChange(draft.copy(protocol = "anthropic")) }
                            )
                        }
                        com.haoai.agent.ui.common.CompactGlassField(
                            value = draft.baseUrl,
                            onValueChange = { v -> onChange(draft.copy(baseUrl = v)) },
                            label = "Base URL",
                            placeholder = if (draft.protocol == "anthropic") "https://api.anthropic.com"
                            else "https://openrouter.ai/api/v1"
                        )
                        // API Key 默认掩码显示；编辑态区分「已存过 Key」与「从未存过」
                        var showKey by remember { mutableStateOf(false) }
                        com.haoai.agent.ui.common.CompactGlassField(
                            value = draft.apiKeyPlain,
                            onValueChange = { v -> onChange(draft.copy(apiKeyPlain = v)) },
                            label = "API Key",
                            placeholder = when {
                                draft.id != null && draft.hasSavedKey -> "已保存 · 留空保持不变"
                                draft.id != null -> "未保存过 · 该服务可能免密"
                                else -> "sk-…"
                            },
                            visualTransformation =
                                if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                            trailing = {
                                TextButton(onClick = { showKey = !showKey }) {
                                    Text(
                                        if (showKey) "隐藏" else "显示",
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            }
                        )
                        // 测试连接：状态 chip + 按钮同行（结果就地显示）
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            when {
                                testing -> GlassCapChip(selected = true, label = "测试中…", onClick = {})
                                testResult != null -> {
                                    val (ok, msg) = testResult
                                    GlassCapChip(
                                        selected = true,
                                        label = (if (ok) "✓ " else "✕ ") + msg.take(14),
                                        onClick = {}
                                    )
                                }
                            }
                            GlassTextButton(
                                text = if (testing) "重新测试" else "测试连接",
                                onClick = onTest,
                                enabled = !testing,
                                backdrop = backdrop
                            )
                        }
                    }

                    // ═════════ 段 1：模型 ═════════
                    if (pane == 1) {
                        if (draft.id == null) {
                            // 添加模式：空态引导
                            Text(
                                "先在「连接」段填好 Base URL / Key，然后 ⇣ 拉取勾选加入，或在下面手动输入模型 ID",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.06f),
                                        RoundedCornerShape(12.dp)
                                    )
                                    .padding(horizontal = 12.dp, vertical = 12.dp)
                            )
                        }
                        // 默认模型 ID（清单点选即提升为默认）
                        com.haoai.agent.ui.common.CompactGlassField(
                            value = draft.model,
                            onValueChange = { v -> onChange(draft.copy(model = v)) },
                            label = "默认模型 ID",
                            placeholder = "如 deepseek-chat"
                        )
                        // 手动快捷录入（chip 列表 + 输入框 +）
                        ModelIdQuickAdd(draft = draft, onChange = onChange)
                        // 拉取入口：显式按钮（此前重构把旧「拉取模型列表」按钮删掉后，
                        // modelChoices 永远为 null → 面板永不出现，用户反馈"看不见这个功能"）
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(percent = 50))
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
                                    .border(
                                        1.dp,
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.30f),
                                        RoundedCornerShape(percent = 50)
                                    )
                                    .clickable(enabled = !fetchingModels && draft.baseUrl.isNotBlank()) { onFetchModels() }
                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    if (fetchingModels) "⇣ 拉取中…" else "⇣ 拉取模型列表",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (fetchingModels || draft.baseUrl.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant
                                    else MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        // 拉取模型列表：可折叠 + 勾选多选（上一轮已实现）
                        modelChoices?.let { list ->
                            if (list.isNotEmpty()) {
                                var open by remember(draft.id) { mutableStateOf(true) }
                                val knownIds = remember(draft.id, list) {
                                    (listOf(draft.model.trim()) + draft.models.map { it.id }.filter { it.isNotBlank() })
                                        .filter { it.isNotBlank() }.toSet()
                                }
                                val selected = remember(draft.id, list) {
                                    mutableStateOf(knownIds.filter { it in list.toSet() })
                                }
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f))
                                ) {
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .clickable { open = !open }
                                            .padding(horizontal = 12.dp, vertical = 9.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            if (fetchingModels) "拉取中…" else "拉取的模型列表",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Spacer(Modifier.size(6.dp))
                                        Text(
                                            "共 ${list.size} 个 · 已选 ${selected.value.size}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Spacer(Modifier.weight(1f))
                                        Text(
                                            if (open) "收起 ▲" else "展开 ▼",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    androidx.compose.animation.AnimatedVisibility(
                                        visible = open,
                                        enter = androidx.compose.animation.expandVertically(
                                            expandFrom = Alignment.Top
                                        ) + androidx.compose.animation.fadeIn(),
                                        exit = androidx.compose.animation.shrinkVertically(
                                            shrinkTowards = Alignment.Top
                                        ) + androidx.compose.animation.fadeOut()
                                    ) {
                                        Column {
                                            Column(Modifier.heightIn(max = 168.dp)) {
                                                list.forEach { id ->
                                                    val checked = id in selected.value
                                                    Row(
                                                        Modifier
                                                            .fillMaxWidth()
                                                            .clickable {
                                                                selected.value =
                                                                    if (checked) selected.value - id else selected.value + id
                                                            }
                                                            .padding(horizontal = 12.dp, vertical = 6.dp),
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Box(
                                                            Modifier
                                                                .size(15.dp)
                                                                .border(
                                                                    1.5.dp,
                                                                    if (checked) MaterialTheme.colorScheme.primary
                                                                    else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.28f),
                                                                    RoundedCornerShape(4.dp)
                                                                ),
                                                            contentAlignment = Alignment.Center
                                                        ) {
                                                            if (checked) Text(
                                                                "✓",
                                                                style = MaterialTheme.typography.labelSmall,
                                                                fontWeight = FontWeight.Black,
                                                                color = MaterialTheme.colorScheme.primary
                                                            )
                                                        }
                                                        Spacer(Modifier.size(9.dp))
                                                        Text(
                                                            id,
                                                            style = MaterialTheme.typography.labelSmall,
                                                            fontFamily = FontFamily.Monospace,
                                                            maxLines = 1
                                                        )
                                                        if (id in knownIds) {
                                                            Spacer(Modifier.weight(1f))
                                                            Text(
                                                                "已加入",
                                                                style = MaterialTheme.typography.labelSmall,
                                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                            Row(
                                                Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 12.dp, vertical = 7.dp),
                                                horizontalArrangement = Arrangement.End
                                            ) {
                                                GlassTextButton(
                                                    text = "加入所选（${selected.value.count { it !in knownIds }}）",
                                                    onClick = {
                                                        val additions = selected.value.filter { it !in knownIds }
                                                        if (additions.isNotEmpty()) {
                                                            val newEntries = additions.map { com.haoai.agent.data.ModelEntry(it) }
                                                            onChange(
                                                                if (draft.model.isBlank()) draft.copy(model = additions.first(), models = draft.models + newEntries.drop(1))
                                                                else draft.copy(models = draft.models + newEntries)
                                                            )
                                                        }
                                                    },
                                                    enabled = selected.value.any { it !in knownIds },
                                                    backdrop = backdrop
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        // ── 能力 + 生效参数：跟随当前默认模型（draft.model）──
                        val curId = draft.model.trim()
                        val curEntry = draft.models.find { it.id == curId }
                        if (curId.isNotBlank()) {
                            val curCaps = com.haoai.agent.data.CapabilityResolver.resolve(curEntry, curId)
                            fun patchEntry(next: com.haoai.agent.data.ModelEntry) {
                                val idx = draft.models.indexOfFirst { it.id == next.id }
                                val models = if (idx >= 0) draft.models.toMutableList().also { l -> l[idx] = next }
                                else draft.models + next
                                onChange(draft.copy(models = models))
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    "能力 · ",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    curId,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                Spacer(Modifier.weight(1f))
                                // 检测按钮：胶囊描边样式（对齐定稿原型；GlassTextButton
                                // 的 percent=50 胶囊在 labelSmall 下高度不齐，这里用
                                // 固定 padding 的胶囊 chip 保持行内紧凑）
                                Box(
                                    Modifier
                                        .clip(RoundedCornerShape(percent = 50))
                                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
                                        .border(
                                            1.dp,
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.30f),
                                            RoundedCornerShape(percent = 50)
                                        )
                                        .clickable(enabled = !detectingCaps) { onDetectCaps() }
                                        .padding(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        if (detectingCaps) "检测中…" else "⚡ 检测该模型",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (detectingCaps) MaterialTheme.colorScheme.onSurfaceVariant
                                        else MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf("image" to "图像", "audio" to "音频", "video" to "视频").forEach { (mod, label) ->
                                    val on = mod in curCaps.inputs
                                    GlassCapChip(
                                        selected = on,
                                        label = label,
                                        onClick = {
                                            val ins = if (on) curCaps.inputs - mod else curCaps.inputs + mod
                                            patchEntry(
                                                (curEntry ?: com.haoai.agent.data.ModelEntry(curId)).copy(
                                                    inputModalities = ins.distinct(),
                                                    outputModalities = curCaps.outputs,
                                                    capsSource = "manual"
                                                )
                                            )
                                        }
                                    )
                                }
                                CapTriChip("工具", curEntry?.tools) { v ->
                                    patchEntry((curEntry ?: com.haoai.agent.data.ModelEntry(curId)).copy(tools = v))
                                }
                                CapTriChip("推理", curEntry?.reasoning) { v ->
                                    patchEntry((curEntry ?: com.haoai.agent.data.ModelEntry(curId)).copy(reasoning = v))
                                }
                            }
                            detectResult?.let { (ok, msg) ->
                                Text(
                                    msg,
                                    color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                            // 生效参数：跟随当前模型（编辑态可编辑，上下文等就地显示）
                            if (draft.id != null) {
                                val ctxK = draft.contextLength.trim().toIntOrNull()
                                    ?.takeIf { it > 0 }?.div(1024)
                                val maxT = draft.maxTokens.trim().toIntOrNull()?.takeIf { it > 0 }
                                Text(
                                    "生效参数 · 跟随「$curId」",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    com.haoai.agent.ui.common.CompactGlassField(
                                        value = draft.contextLength,
                                        onValueChange = { v -> onChange(draft.copy(contextLength = v.filter { it.isDigit() }.take(8))) },
                                        label = "上下文",
                                        placeholder = ctxK?.let { "${it}K（自动推测）" } ?: "自动推测",
                                        modifier = Modifier.weight(1f)
                                    )
                                    com.haoai.agent.ui.common.CompactGlassField(
                                        value = draft.maxTokens,
                                        onValueChange = { v -> onChange(draft.copy(maxTokens = v.filter { it.isDigit() }.take(7))) },
                                        label = "回复上限",
                                        placeholder = maxT?.toString() ?: "默认",
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    }

                    // ═════════ 段 2：高级（采样参数 / Key 池 / 余额）═════════
                    if (pane == 2) {

                            // 点2：采样参数发送开关（关=请求体不带该字段）
                            Text(
                                "采样参数（默认不发送；开启才随请求下发）",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            SwitchParamField("temperature", draft.sendTemperature, draft.temperature,
                                { onChange(draft.copy(sendTemperature = it)) }, { onChange(draft.copy(temperature = it)) }, backdrop)
                            SwitchParamField("top_p", draft.sendTopP, draft.topP,
                                { onChange(draft.copy(sendTopP = it)) }, { onChange(draft.copy(topP = it)) }, backdrop)
                            SwitchParamField("presence_penalty", draft.sendPresencePenalty, draft.presencePenalty,
                                { onChange(draft.copy(sendPresencePenalty = it)) }, { onChange(draft.copy(presencePenalty = it)) }, backdrop)
                            SwitchParamField("frequency_penalty", draft.sendFrequencyPenalty, draft.frequencyPenalty,
                                { onChange(draft.copy(sendFrequencyPenalty = it)) }, { onChange(draft.copy(frequencyPenalty = it)) }, backdrop)
                            HorizontalDivider(Modifier.padding(vertical = 4.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f))
                            // 点5：备用 Key 池
                            Text(
                                "备用 Key 池（逐请求轮换，分摊单 Key 限流）",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            draft.poolCiphers.forEachIndexed { i, _ ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("••••••••••（已保存）", style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                                    // 40dp 热区 + Button role：纯文字 clickable 只有十几 dp
                                    // 且无按钮语义（屏幕阅读器读不出可点）
                                    Box(
                                        Modifier
                                            .size(40.dp)
                                            .clickable(
                                                interactionSource = null,
                                                indication = null,
                                                role = Role.Button
                                            ) {
                                                onChange(draft.copy(poolCiphers = draft.poolCiphers.filterIndexed { j, _ -> j != i }))
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("删除", style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                            draft.poolAddPlain.forEachIndexed { i, k ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(k.take(8) + "…（本次新增）", style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                                    Box(
                                        Modifier
                                            .size(40.dp)
                                            .clickable(
                                                interactionSource = null,
                                                indication = null,
                                                role = Role.Button
                                            ) {
                                                onChange(draft.copy(poolAddPlain = draft.poolAddPlain.filterIndexed { j, _ -> j != i }))
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("删除", style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                            var poolKey by remember(draft.id) { mutableStateOf("") }
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                com.haoai.agent.ui.common.CompactGlassField(
                                    value = poolKey, onValueChange = { poolKey = it },
                                    label = "备用 Key", placeholder = "逐请求轮换",
                                    modifier = Modifier.weight(1f)
                                )
                                GlassTextButton(
                                    text = "添加",
                                    enabled = poolKey.isNotBlank(),
                                    onClick = {
                                        onChange(draft.copy(poolAddPlain = draft.poolAddPlain + poolKey.trim()))
                                        poolKey = ""
                                    },
                                    backdrop = backdrop
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                GlassCapChip(selected = draft.keyRotation == "ROUND_ROBIN",
                                    label = "轮询",
                                    onClick = { onChange(draft.copy(keyRotation = "ROUND_ROBIN")) })
                                GlassCapChip(selected = draft.keyRotation == "RANDOM",
                                    label = "随机",
                                    onClick = { onChange(draft.copy(keyRotation = "RANDOM")) })
                            }
                            HorizontalDivider(Modifier.padding(vertical = 4.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f))
                            // 点3：余额查询
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("余额查询", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                                com.haoai.agent.ui.common.HaoSwitch(
                                    checked = draft.balanceEnabled,
                                    onCheckedChange = { onChange(draft.copy(balanceEnabled = it)) },
                                    backdrop = backdrop
                                )
                            }
                            if (draft.balanceEnabled) {
                                com.haoai.agent.ui.common.CompactGlassField(
                                    value = draft.balanceApiPath,
                                    onValueChange = { onChange(draft.copy(balanceApiPath = it)) },
                                    label = "接口路径",
                                    placeholder = "/credits"
                                )
                                com.haoai.agent.ui.common.CompactGlassField(
                                    value = draft.balanceJsonPath,
                                    onValueChange = { onChange(draft.copy(balanceJsonPath = it)) },
                                    label = "JSON 路径",
                                    placeholder = "data.total_usage（点分）"
                                )
                            }
                        }
                    }

                    if (draftError != null) {
                        Text(
                            draftError,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    // 操作栏：右下角（定稿原型 2026-09-11：取消/保存钉在弹窗底部右侧）
                    // 注意必须在 Column(3504) 内部——放外面会变成 GlassPanel Box 的
                    // 直接子组件，被叠到面板右上角（8e8147a 的教训）
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        GlassTextButton(text = "取消", onClick = onDismiss, backdrop = backdrop)
                        LiquidPillButton(
                            backdrop = backdrop,
                            // 按钮写明副作用：新建保存后会自动设为当前使用的服务
                            text = if (draft.id == null) "保存并启用" else "保存",
                            emphasized = true
                        ) { onSave() }
                    }
                }
            }
        }
    }
}

/**
 * 供应商头像：有 logo 资源（ProviderPreset.logoRes，官方矢量标转换的 VectorDrawable）
 * 画白/深底圆角块 + 单色标；没有的用 label 首字字牌 + 品牌底色。
 * public：首启引导「接大脑」步（MainActivity）与设置页向导共用。
 */
@Composable
fun ProviderLogoAvatar(preset: com.haoai.agent.ui.ProviderPreset, size: androidx.compose.ui.unit.Dp = 38.dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(11.dp))
            .background(androidx.compose.ui.graphics.Color(preset.avatarBg)),
        contentAlignment = Alignment.Center
    ) {
        val res = preset.logoRes
        if (res != null) {
            androidx.compose.foundation.Image(
                painter = androidx.compose.ui.res.painterResource(res),
                contentDescription = null,
                // logoFull = 官方自带圆角底色的位图（智谱黑底白 Z）：铺满头像框；
                // 其余是单色矢量标：内缩居中，底色由 avatarBg 提供
                modifier = if (preset.logoFull) Modifier.fillMaxSize()
                else Modifier.size(size * 0.60f)
            )
        } else {
            Text(
                preset.label.take(1),
                color = androidx.compose.ui.graphics.Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = (size.value * 0.40f).sp
            )
        }
    }
}

/**
 * 添加供应商 3 步向导（2026-09-25 交互改版）：选服务商 → 连接 → 选模型。
 * 一步一屏替代旧「添加=全页三段 Tab」，测试/拉取/保存复用 SettingsViewModel 的
 * draft 管线（testDraftConnection/fetchModelList/saveDraft），无新协议层代码。
 * 编辑已有供应商仍走 ProviderDialog 三段面板。
 */
@Composable
private fun ProviderWizardDialog(
    backdrop: com.kyant.backdrop.backdrops.LayerBackdrop,
    draft: com.haoai.agent.ui.ProviderDraft,
    draftError: String?,
    testing: Boolean,
    testResult: Pair<Boolean, String>?,
    fetchingModels: Boolean,
    modelChoices: List<String>?,
    onChange: (com.haoai.agent.ui.ProviderDraft) -> Unit,
    onPreset: (com.haoai.agent.ui.ProviderPreset) -> Unit,
    onFetchModels: () -> Unit,
    onPickModel: (String) -> Unit,
    onTest: () -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit
) {
    var step by remember { mutableIntStateOf(0) }
    var picked by remember { mutableStateOf<com.haoai.agent.ui.ProviderPreset?>(null) }
    val stepTitles = listOf("选择服务商", "连接", "选模型")

    // 液态玻璃弹层外壳：同 ProviderDialog（独立 Dialog 窗口采样不到 LayerBackdrop）
    androidx.compose.runtime.CompositionLocalProvider(
        com.haoai.agent.ui.common.LocalGlassRefract provides false
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.30f))
                .imePadding()
                .clickable(interactionSource = null, indication = null) { onDismiss() },
            contentAlignment = Alignment.Center
        ) {
            GlassPanel(
                backdrop = backdrop,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .heightIn(max = 560.dp),
                radius = 28.dp,
                surfaceAlpha = 0.92f,
                blurRadius = 28.dp,
                chromaticAberration = true
            ) {
                Column(Modifier.fillMaxWidth()) {
                    // 标题 + 进度点
                    Column(
                        Modifier
                            .clickable(interactionSource = null, indication = null) {}
                            .padding(horizontal = 16.dp)
                            .padding(top = 16.dp, bottom = 10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "添加模型供应商",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onBackground,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                "第 ${step + 1} / 3 步",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            repeat(3) { i ->
                                Box(
                                    Modifier
                                        .size(width = if (i == step) 20.dp else 8.dp, height = 8.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(
                                            if (i <= step) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.22f)
                                        )
                                )
                            }
                            Spacer(Modifier.size(8.dp))
                            Text(
                                stepTitles[step],
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Column(
                        Modifier
                            .padding(top = 4.dp)
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        when (step) {
                            // ═══ 步 0：选择服务商（分类 + 搜索 + 官方 logo）═══
                            0 -> WizardPickProviderStep(
                                onPick = { p ->
                                    picked = p
                                    onPreset(p)
                                    step = 1
                                }
                            )

                            // ═══ 步 1：连接（名称/协议/URL/Key + 测试连接）═══
                            1 -> WizardConnectStep(
                                backdrop = backdrop,
                                draft = draft,
                                picked = picked,
                                testing = testing,
                                testResult = testResult,
                                onChange = onChange,
                                onTest = onTest
                            )

                            // ═══ 步 2：选模型（自动拉取 + 勾选 + 设默认）═══
                            2 -> WizardPickModelStep(
                                draft = draft,
                                fetchingModels = fetchingModels,
                                modelChoices = modelChoices,
                                onChange = onChange,
                                onFetchModels = onFetchModels,
                                onPickModel = onPickModel
                            )
                        }
                        draftError?.let { err ->
                            Text(
                                err,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                        }
                    }

                    // 底部操作栏：上一步 / 下一步·完成
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (step > 0) {
                            com.haoai.agent.ui.common.GlassTextButton(
                                text = "‹ 上一步",
                                onClick = { step-- },
                                backdrop = backdrop
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        when (step) {
                            0 -> {}
                            1 -> {
                                val canNext = draft.baseUrl.isNotBlank()
                                WizardPrimaryButton(
                                    text = "下一步",
                                    enabled = canNext,
                                    backdrop = backdrop,
                                    onClick = { if (canNext) step = 2 }
                                )
                            }
                            else -> {
                                val includedCount = (draft.models.map { it.id } + listOf(draft.model))
                                    .filter { it.isNotBlank() }.distinct().size
                                WizardPrimaryButton(
                                    text = if (includedCount > 0) "完成 · 已选 $includedCount 个模型" else "完成",
                                    enabled = draft.model.isNotBlank(),
                                    backdrop = backdrop,
                                    onClick = { if (draft.model.isNotBlank()) onSave() }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 步 0：服务商目录（分类 chips + 搜索 + logo 行）。 */
@Composable
private fun WizardPickProviderStep(onPick: (com.haoai.agent.ui.ProviderPreset) -> Unit) {
    var query by remember { mutableStateOf("") }
    var catIdx by remember { mutableIntStateOf(0) }

    Text(
        "有 Key 的直接点；点选即预填地址与协议",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp)
    )
    com.haoai.agent.ui.common.CompactGlassField(
        value = query,
        onValueChange = { query = it },
        label = "搜索",
        placeholder = "如：deepseek / kimi",
        modifier = Modifier.padding(horizontal = 16.dp)
    )
    // 分类 chips：搜索时忽略分类
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        com.haoai.agent.ui.ProviderPresets.categories.forEachIndexed { i, c ->
            GlassCapChip(
                selected = if (query.isBlank()) catIdx == i else false,
                label = c,
                onClick = { catIdx = i }
            )
        }
    }
    val list = com.haoai.agent.ui.ProviderPresets.all
        .filter { p ->
            val q = query.trim().lowercase()
            q.isBlank() || p.label.lowercase().contains(q) || p.name.lowercase().contains(q) ||
                p.sub.lowercase().contains(q)
        }
        .filter { if (query.isBlank()) it.category == com.haoai.agent.ui.ProviderPresets.categories[catIdx] else true }
        .distinctBy { it.name }
    Column(
        Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        list.forEach { p ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f))
                    .clickable { onPick(p) }
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(11.dp)
            ) {
                ProviderLogoAvatar(p)
                Column(Modifier.weight(1f)) {
                    Text(
                        p.name.ifBlank { p.label },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        p.sub + " · " + p.baseUrl.ifBlank { "手填端点" },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
            }
        }
        if (list.isEmpty()) {
            Text(
                "没有匹配的服务商——切到「自定义」分类手填任意端点",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        }
    }
}

/** 步 1：连接参数（协议已按预设定好、可改）+ 测试连接。 */
@Composable
private fun WizardConnectStep(
    backdrop: com.kyant.backdrop.backdrops.LayerBackdrop,
    draft: com.haoai.agent.ui.ProviderDraft,
    picked: com.haoai.agent.ui.ProviderPreset?,
    testing: Boolean,
    testResult: Pair<Boolean, String>?,
    onChange: (com.haoai.agent.ui.ProviderDraft) -> Unit,
    onTest: () -> Unit
) {
    var showKey by remember { mutableStateOf(false) }
    Row(
        Modifier.padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        picked?.let { ProviderLogoAvatar(it) }
        Column {
            Text(
                picked?.name?.ifBlank { "自定义端点" } ?: "自定义端点",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                "地址与协议已按预设填好，一般不用改",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    com.haoai.agent.ui.common.CompactGlassField(
        value = draft.name,
        onValueChange = { v -> onChange(draft.copy(name = v)) },
        label = "名称",
        placeholder = "留空自动命名",
        modifier = Modifier.padding(horizontal = 16.dp)
    )
    Row(
        Modifier.padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            "协议",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        GlassCapChip(
            selected = draft.protocol == "openai_compat",
            label = "OpenAI 兼容",
            onClick = { onChange(draft.copy(protocol = "openai_compat")) }
        )
        GlassCapChip(
            selected = draft.protocol == "anthropic",
            label = "Anthropic 原生",
            onClick = { onChange(draft.copy(protocol = "anthropic")) }
        )
    }
    com.haoai.agent.ui.common.CompactGlassField(
        value = draft.baseUrl,
        onValueChange = { v -> onChange(draft.copy(baseUrl = v)) },
        label = "Base URL",
        placeholder = "https://api.deepseek.com/v1",
        modifier = Modifier.padding(horizontal = 16.dp)
    )
    com.haoai.agent.ui.common.CompactGlassField(
        value = draft.apiKeyPlain,
        onValueChange = { v -> onChange(draft.copy(apiKeyPlain = v)) },
        label = "API Key",
        placeholder = "sk-…",
        visualTransformation =
            if (showKey) androidx.compose.ui.text.input.VisualTransformation.None
            else androidx.compose.ui.text.input.PasswordVisualTransformation(),
        trailing = {
            androidx.compose.material3.TextButton(onClick = { showKey = !showKey }) {
                Text(
                    if (showKey) "隐藏" else "显示",
                    style = MaterialTheme.typography.labelSmall
                )
            }
        },
        modifier = Modifier.padding(horizontal = 16.dp)
    )
    // 测试连接：结果就地显示。模型 ID 为空时测试无从发起（testDraftConnection 需要模型），
    // 提示先去下一步拉模型——拉完回来测也一样
    Row(
        Modifier.padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        when {
            testing -> GlassCapChip(selected = true, label = "测试中…", onClick = {})
            testResult != null -> {
                val (ok, msg) = testResult
                GlassCapChip(
                    selected = true,
                    label = (if (ok) "✓ " else "✕ ") + msg.take(14),
                    onClick = {}
                )
            }
        }
        com.haoai.agent.ui.common.GlassTextButton(
            text = if (testing) "重新测试" else "测试连接",
            onClick = onTest,
            enabled = !testing && draft.model.isNotBlank(),
            backdrop = backdrop
        )
    }
    if (draft.model.isBlank()) {
        Text(
            "还没指定模型 ID：点「下一步」自动拉取模型列表，选完默认后可回来补测",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
    }
}

/** 步 2：自动拉取模型列表 → 勾选加入 + 点行设默认；拉不到就手动填 ID。 */
@Composable
private fun WizardPickModelStep(
    draft: com.haoai.agent.ui.ProviderDraft,
    fetchingModels: Boolean,
    modelChoices: List<String>?,
    onChange: (com.haoai.agent.ui.ProviderDraft) -> Unit,
    onFetchModels: () -> Unit,
    onPickModel: (String) -> Unit
) {
    // 进本步自动拉一次（本会话没拉过的话）
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (modelChoices == null && !fetchingModels && draft.baseUrl.isNotBlank() &&
            draft.protocol != "anthropic"
        ) onFetchModels()
    }
    // 列表到手、还没有默认模型 → 首个自动设默认（省一次手填）
    androidx.compose.runtime.LaunchedEffect(modelChoices) {
        if (draft.model.isBlank()) modelChoices?.firstOrNull()?.let { onPickModel(it) }
    }

    if (draft.protocol == "anthropic") {
        Text(
            "Anthropic 原生协议不支持拉取列表，直接在下面手动填模型 ID（如 claude-sonnet-4-5）",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
    } else {
        Row(
            Modifier.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                if (fetchingModels) "⇣ 拉取中…" else "已拉取 ${modelChoices?.size ?: 0} 个模型",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Text(
                "重新拉取",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = if (fetchingModels) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = !fetchingModels) { onFetchModels() }
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            )
        }
    }

    // 展示列表 = 拉取的 ∪ 手动加的（都在 draft.models / draft.model 里）
    val known = (draft.models.map { it.id } + listOf(draft.model)).filter { it.isNotBlank() }.distinct()
    val shown = (modelChoices ?: emptyList()).distinct() + known.filter { it !in (modelChoices ?: emptyList()) }
    Column(
        Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        shown.forEach { id ->
            val isDefault = id == draft.model.trim()
            val included = id in known
            WizardModelRow(
                id = id,
                isDefault = isDefault,
                included = included,
                onToggleInclude = {
                    when {
                        included && isDefault -> {} // 默认模型必须保留在清单里
                        included -> onChange(draft.copy(models = draft.models.filterNot { it.id == id }))
                        else -> onChange(
                            draft.copy(models = draft.models + com.haoai.agent.data.ModelEntry(id))
                        )
                    }
                },
                onSetDefault = { onPickModel(id) }
            )
        }
    }

    var manual by remember { mutableStateOf("") }
    Row(
        Modifier.padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        com.haoai.agent.ui.common.CompactGlassField(
            value = manual,
            onValueChange = { manual = it },
            label = "手动添加",
            placeholder = "如 deepseek-reasoner",
            modifier = Modifier.weight(1f)
        )
        Text(
            "＋ 加入",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(enabled = manual.isNotBlank()) {
                    val id = manual.trim()
                    onChange(draft.copy(models = draft.models + com.haoai.agent.data.ModelEntry(id)))
                    if (draft.model.isBlank()) onPickModel(id)
                    manual = ""
                }
                .padding(horizontal = 8.dp, vertical = 6.dp)
        )
    }
    Text(
        "点行 = 设为默认 · 勾选 = 加入模型清单（设置里随时可换）",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp)
    )
}

/** 单个模型行：勾选加入 + 点行设默认。 */
@Composable
private fun WizardModelRow(
    id: String,
    isDefault: Boolean,
    included: Boolean,
    onToggleInclude: () -> Unit,
    onSetDefault: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f))
            .clickable { onSetDefault() }
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        // 勾选块（与点行「设默认」是两个独立热区）
        Box(
            Modifier
                .size(18.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(
                    if (included) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.14f)
                )
                .border(
                    1.dp,
                    if (included) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                    RoundedCornerShape(6.dp)
                )
                .clickable { onToggleInclude() },
            contentAlignment = Alignment.Center
        ) {
            if (included) {
                Text(
                    "✓",
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        Text(
            id,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (isDefault) FontWeight.SemiBold else FontWeight.Normal,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
        )
        if (isDefault) {
            GlassCapChip(selected = true, label = "默认", onClick = {})
        }
    }
}

/** 向导主操作胶囊（下一步 / 完成）。 */
@Composable
private fun WizardPrimaryButton(
    text: String,
    enabled: Boolean,
    backdrop: com.kyant.backdrop.backdrops.LayerBackdrop,
    onClick: () -> Unit
) {
    Box(
        Modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(
                MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 0.85f else 0.25f)
            )
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 22.dp, vertical = 10.dp)
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (enabled) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f)
        )
    }
}

/**
 * 服务商预设卡片（设计稿 vgrid）：标识 + 一句副注，3 列自适应网格。
 * 选中态=主题色描边+浅色底，未选=中性细描边。比 AssistChip 行多一个
 * 「这是第一步选服务商」的视觉分量。
 */
@Composable
private fun PresetCard(label: String, sub: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    androidx.compose.foundation.layout.Column(
        Modifier
            // 3 列铺满一行（每张约 104dp）：固定宽让 6 张卡片两行对齐，不偏左
            .width(104.dp)
            .clip(shape)
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.05f),
                shape
            )
            .border(
                if (selected) 1.5.dp else 1.dp,
                if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.14f),
                shape
            )
            .clickable(
                interactionSource = null,
                indication = null,
                role = Role.Button,
                onClick = onClick
            )
            .padding(horizontal = 8.dp, vertical = 9.dp),
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onBackground,
            maxLines = 1
        )
        Text(
            if (selected) "已预填" else sub,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.9f),
            maxLines = 1
        )
    }
}

/**
 * 供应商展开区里的单行模型：默认模型行（radio 选中态，不可移除）与
 * 备选模型行（点击升为默认，×移除）。供「点供应商行展开清单切换模型」用。
 */
@Composable
private fun ModelSwitchRow(
    id: String,
    isDefault: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    onRemove: (() -> Unit)?,
    onEditCaps: (() -> Unit)? = null
) {
    if (id.isBlank()) return
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .clip(RoundedCornerShape(10.dp))
            .then(
                if (isDefault) Modifier.background(
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                    RoundedCornerShape(10.dp)
                ) else Modifier
            )
            .combinedClickable(
                // 点行 = 切默认（原位高亮不重排）；长按 = 能力编辑（入口收敛：
                // 行内不再放「能力」文字入口，与「检测全部能力」职责分离）
                onClick = { if (!isDefault) onClick() },
                onLongClick = { if (!isDefault) onEditCaps?.invoke() }
            )
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(18.dp)
                .border(
                    1.5.dp,
                    if (isDefault) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.30f),
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            if (isDefault) {
                Box(
                    Modifier
                        .size(8.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                )
            }
        }
        Spacer(Modifier.size(10.dp))
        Text(
            id,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            modifier = Modifier.weight(1f)
        )
        if (isDefault) {
            // 「默认」pill 标记（2026-09-11 用户反馈：原文字按钮无作用）。
            // 纯标识不可点——切换走点行、能力编辑走长按，职责唯一。
            Text(
                "默认",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .background(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                        RoundedCornerShape(999.dp)
                    )
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            )
        } else {
            Box(
                Modifier
                    .size(36.dp)
                    .clickable(
                        interactionSource = null,
                        indication = null,
                        role = Role.Button,
                        enabled = onRemove != null,
                        onClick = { onRemove?.invoke() }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "移除 $id",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(15.dp)
                )
            }
        }
    }
}

/**
 * 同一供应商下的多模型快捷录入：输入框尾部一个 "+"，敲完一个模型 ID 按一下就进列表，
 * 输入框随即清空、接着敲下一个——配一个供应商要挂好几个模型时，不必反复展开折叠区。
 * 已录入的以 chip 展示：点击即提升为默认使用的模型（原默认退回备选），尾部 × 移除。
 * 默认模型还没填时，首个录入的 ID 直接补位，避免保存时卡在「模型 ID 不能为空」。
 */
@Composable
private fun ModelIdQuickAdd(
    draft: com.haoai.agent.ui.ProviderDraft,
    onChange: (com.haoai.agent.ui.ProviderDraft) -> Unit
) {
    // 输入框是独立暂存值：按 + 之后清空，方便连续录入下一个 ID。
    // 以 draft.id 为 key，切换到别的服务商编辑时自动重置
    var input by remember(draft.id) { mutableStateOf("") }
    val currentId = draft.model.trim()
    val knownIds = (listOf(currentId).filter { it.isNotBlank() } + draft.models.map { it.id }).toSet()
    val candidate = input.trim()
    val canAdd = candidate.isNotBlank() && !knownIds.contains(candidate)

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "同一供应商的多个模型 · 点 chip 设为默认",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (draft.models.isNotEmpty()) {
            // FlowRow 自动换行：模型 ID 长短差很大，横向滚动条会把长 ID 藏到屏幕外
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                draft.models.forEach { m ->
                    ModelIdChip(
                        id = m.id,
                        onPromote = {
                            // 升为默认只改 model 指针，models 顺序原样保留（2026-09-11 保序）
                            onChange(draft.copy(model = m.id))
                        },
                        onRemove = { onChange(draft.copy(models = draft.models.filterNot { it.id == m.id })) }
                    )
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            com.haoai.agent.ui.common.CompactGlassField(
                value = input,
                onValueChange = { input = it },
                label = "其他模型",
                placeholder = "输入后点 + 加入",
                modifier = Modifier.weight(1f),
                trailing = {
                    IconButton(
                        onClick = {
                            if (currentId.isBlank()) {
                                onChange(draft.copy(model = candidate))
                            } else {
                                onChange(draft.copy(models = draft.models + com.haoai.agent.data.ModelEntry(candidate)))
                            }
                            input = ""
                        },
                        enabled = canAdd
                    ) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = "添加模型 ID",
                            tint = if (canAdd) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                        )
                    }
                }
            )
        }
    }
}

/**
 * 单个模型 ID 条目：主体点击=设为默认，尾部 × =移除。
 * 移除用 40dp 图标按钮而非文字上的 clickable——后者热区只有十几 dp，
 * 既点不准也没有按钮语义（屏幕阅读器读不出这是个可点控件）。
 */
@Composable
private fun ModelIdChip(id: String, onPromote: () -> Unit, onRemove: () -> Unit) {
    val shape = RoundedCornerShape(percent = 50)
    Row(
        Modifier
            .height(32.dp)
            .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.07f), shape)
            .border(1.dp, MaterialTheme.colorScheme.onBackground.copy(alpha = 0.14f), shape)
            .clickable(
                interactionSource = null,
                indication = null,
                role = Role.Button,
                onClick = onPromote
            )
            .padding(start = 12.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            id,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            modifier = Modifier.widthIn(max = 168.dp)
        )
        Box(
            Modifier
                .size(40.dp)
                .clickable(
                    interactionSource = null,
                    indication = null,
                    role = Role.Button,
                    onClick = onRemove
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "移除 $id",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(15.dp)
            )
        }
    }
}

/** 能力三态芯片：自动（不门控）→ 支持 → 不支持 循环。 */
@Composable
private fun CapTriChip(label: String, value: Boolean?, onChange: (Boolean?) -> Unit) {
    val text = when (value) {
        null -> "$label·自动"
        true -> "$label·支持"
        false -> "$label·不支持"
    }
    GlassCapChip(
        selected = value != null,
        label = text,
        onClick = { onChange(when (value) { null -> true; true -> false; false -> null }) }
    )
}

/**
 * 玻璃弹层内芯片：替代 Material3 FilterChip——后者的边框+涟漪在玻璃弹窗里按压会
 * 突显一个方框（用户反馈）。改用纯背景色切换 + 按压缩放（GlassTextButton 同款动效）。
 */
@Composable
private fun GlassCapChip(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    // 轻点补闪（同深度思考栏那处：collectIsPressedAsState 会被按帧合批吃掉）
    val pressFb = com.haoai.agent.ui.common.rememberPressFeedback(interaction)
    val scale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (pressFb.pressed) 0.95f else 1f,
        animationSpec = androidx.compose.animation.core.tween(90),
        label = "glassCapChipScale"
    )
    Box(
        modifier
            .scale(scale)
            .clip(RoundedCornerShape(18.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.08f)
            )
            .clickable(interactionSource = interaction, indication = null, onClick = pressFb.wrap(onClick))
            .padding(horizontal = 12.dp, vertical = 7.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 供应商行能力徽章（设计稿 ①）：图/音/视/工具/思考小芯片；划线=明确不支持，不显示=未知。 */@Composable
private fun ProviderCapBadges(p: com.haoai.agent.data.ProviderConfig) {
    val caps = p.caps()
    val items = buildList {
        fun chip(label: String, v: Boolean?) {
            if (v != null) add(label to v)
        }
        chip("图", caps.hasImage)
        chip("音", caps.hasAudio)
        chip("视", caps.hasVideo)
        chip("工具", caps.tools)
        chip("思考", caps.reasoning)
    }
    if (items.isEmpty()) return
    Row(
        Modifier.padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items.forEach { (label, ok) ->
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = if (ok) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                modifier = Modifier
                    .background(
                        if (ok) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.07f),
                        RoundedCornerShape(5.dp)
                    )
                    .padding(horizontal = 5.dp, vertical = 1.dp)
            )
        }
    }
}

/**
 * 模型能力编辑弹层（设计稿 ③）：输入/输出模态勾选芯片 + 工具/思考开关 +
 * 能力来源显示与「恢复自动检测」。勾选即写回 settings（capsSource=manual），
 * 请求门控与系统提示词能力声明实时反映。
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ModelCapsDialog(
    backdrop: com.kyant.backdrop.backdrops.LayerBackdrop,
    vm: SettingsViewModel,
    providerId: String,
    modelId: String,
    onDismiss: () -> Unit
) {
    val p = vm.providers().find { it.id == providerId } ?: return
    val entry = p.models.find { it.id == modelId }
    val caps = vm.capsOf(providerId, modelId)
    androidx.compose.runtime.CompositionLocalProvider(
        com.haoai.agent.ui.common.LocalGlassRefract provides false
    ) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.30f))
            .imePadding()
            .clickable(interactionSource = null, indication = null) { onDismiss() },
        contentAlignment = Alignment.Center
    ) {
        GlassPanel(
            backdrop = backdrop,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .heightIn(max = 560.dp),
            radius = 28.dp,
            surfaceAlpha = 0.92f,
            blurRadius = 28.dp,
            chromaticAberration = true
        ) {
            Column(
                Modifier
                    .clickable(interactionSource = null, indication = null) {}
                    .padding(horizontal = 16.dp)
                    .padding(top = 16.dp, bottom = 12.dp)
            ) {
                Text("模型能力 · $modelId", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                // 能力来源条（设计稿 ③ 顶部）：manual 才给「恢复自动检测」
                val srcLabel = when (caps.source) {
                    "manual" -> "手动设置"
                    "models.dev" -> "models.dev 自动检测"
                    "legacy" -> "旧版检测数据"
                    "guess" -> "按模型名推测"
                    else -> "未检测（默认乐观）"
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.07f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "✨ 能力来源：$srcLabel",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    if (caps.source == "manual") {
                        Text(
                            "恢复自动检测",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { vm.resetModelCaps(providerId, modelId) }
                                .padding(horizontal = 6.dp, vertical = 3.dp)
                        )
                    }
                }
                Column(
                    Modifier
                        .padding(top = 10.dp)
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("输入模态", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("image" to "图像", "audio" to "音频", "video" to "视频", "pdf" to "PDF").forEach { (mod, label) ->
                            val on = mod in caps.inputs
                            GlassCapChip(selected = on, label = label, onClick = { vm.toggleInputModality(providerId, modelId, mod) })
                        }
                    }
                    Text("输出模态", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("image" to "图像", "audio" to "音频").forEach { (mod, label) ->
                            val on = mod in caps.outputs
                            GlassCapChip(selected = on, label = label, onClick = { vm.toggleOutputModality(providerId, modelId, mod) })
                        }
                    }
                    Text("能力", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        CapTriChip("工具", entry?.tools) { v ->
                            vm.updateModelEntry(
                                providerId,
                                (entry ?: com.haoai.agent.data.ModelEntry(modelId)).copy(tools = v)
                            )
                        }
                        CapTriChip("推理", entry?.reasoning) { v ->
                            vm.updateModelEntry(
                                providerId,
                                (entry ?: com.haoai.agent.data.ModelEntry(modelId)).copy(reasoning = v)
                            )
                        }
                    }
                    // 按模型思考等级覆盖：
                    // 跟随全局 / off / low / medium / high；目录给了 effortValues 时优先用目录档位
                    Text(
                        "思考等级（本模型覆盖全局）",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    val dirEfforts = entry?.effortValues.orEmpty()
                    val effortOptions = buildList {
                        add("" to "跟随全局")
                        if (dirEfforts.isEmpty()) {
                            add("off" to "关闭")
                            add("low" to "低")
                            add("medium" to "中")
                            add("high" to "高")
                        } else {
                            // 目录档位白名单（如 zhipuai glm-5.3-flash = low/high/max）
                            add("off" to "关闭")
                            dirEfforts.forEach { add(it to it.replaceFirstChar { c -> c.uppercase() }) }
                        }
                    }
                    val curEffort = entry?.reasoningEffortOverride.orEmpty()
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        effortOptions.forEach { (value, label) ->
                            val selected = curEffort == value
                            GlassCapChip(selected = selected, label = label, onClick = { vm.setModelEffort(providerId, modelId, value) })
                        }
                    }
                    // Agent 感知预览（默认折叠）：这段文字即对话时注入系统提示词的能力声明原文，
                    // 展开可核对当前配置将如何告知模型——平时折叠省空间
                    val frag = com.haoai.agent.data.CapabilityResolver.capabilityPromptFragment(caps)
                    var previewOpen by remember { mutableStateOf(false) }
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                    ) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { previewOpen = !previewOpen }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Agent 感知预览",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                if (previewOpen) "收起 ▲" else "展开 ▼",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        if (previewOpen) {
                            Text(
                                frag ?: "模型能力齐备（全模态输入 + 工具调用），无需注入能力声明。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                lineHeight = 16.sp,
                                modifier = Modifier.padding(start = 10.dp, end = 10.dp, bottom = 10.dp)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                // 检测结果就地处（按钮上方一行小字），操作栏钉底：按钮贴弹层底边等宽
                vm.detectResult?.let { (ok, msg) ->
                    Text(
                        msg,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // 液态玻璃胶囊按钮（与编辑对话框「取消/保存」同款，内容居中）：
                    // GlassTextButton 的裸描边样式与全应用按钮观感不统一（用户反馈）
                    LiquidPillButton(
                        backdrop = backdrop,
                        text = if (vm.detectingCaps) "检测中…" else "自动检测",
                        enabled = !vm.detectingCaps,
                        emphasized = false,
                        onClick = { vm.detectSingleCaps(providerId, modelId) },
                        modifier = Modifier.weight(1f)
                    )
                    LiquidPillButton(
                        backdrop = backdrop,
                        text = "完成",
                        onClick = onDismiss,
                        emphasized = true,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
    }
}

/** 采样参数行：开关 + 数值输入（参数级发送控制）。 */
@Composable
private fun SwitchParamField(    label: String,
    enabled: Boolean,
    value: String,
    onToggle: (Boolean) -> Unit,
    onValue: (String) -> Unit,
    backdrop: com.kyant.backdrop.backdrops.LayerBackdrop
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        com.haoai.agent.ui.common.HaoSwitch(
            checked = enabled,
            onCheckedChange = onToggle,
            backdrop = backdrop
        )
        Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
        if (enabled) {
            OutlinedTextField(
                value = value,
                onValueChange = { v -> onValue(v.filter { !it.isLetter() }.take(8)) },
                singleLine = true,
                modifier = Modifier.width(90.dp),
                textStyle = MaterialTheme.typography.labelSmall
            )
        }
    }
}

/** 玻璃弹层输入框配色复用公共实现（ui/common/Glass.kt）。 */

// ---------- Linux 环境（3.2 发行版管理） ----------

private fun androidx.compose.foundation.lazy.LazyListScope.linuxItems(
    vm: SettingsViewModel,
    backdrop: com.kyant.backdrop.backdrops.LayerBackdrop,
    s: LinuxEnvState
) {
    item { SectionTitle("发行版") }
    item {
        GlassGroup(backdrop = backdrop) {
            val st = s.statuses
            if (st.isEmpty()) {
                Text(
                    "正在读取状态…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(14.dp)
                )
            }
            st.forEach { stat ->
                val d = stat.distro
                val busy = s.installingId != null
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                d.name,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                when (stat.state) {
                                    com.haoai.agent.platform.sandbox.DistroManager.State.READY ->
                                        "已安装 · ${formatDistroSize(stat.sizeBytes)}" +
                                            if (stat.installedAt > 0) " · ${java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA).format(java.util.Date(stat.installedAt))} 装" else ""
                                    com.haoai.agent.platform.sandbox.DistroManager.State.DAMAGED ->
                                        "rootfs 已损坏（缺 /bin/sh）· 建议删除后重装"
                                    com.haoai.agent.platform.sandbox.DistroManager.State.NOT_INSTALLED ->
                                        "未安装 · ${d.desc}"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            when (stat.state) {
                                com.haoai.agent.platform.sandbox.DistroManager.State.NOT_INSTALLED ->
                                    LiquidPillButton(
                                        backdrop = backdrop,
                                        text = "安装",
                                        enabled = !busy
                                    ) {
                                        s.urlDraft = d.sources[com.haoai.agent.platform.sandbox.Proot.abiOf()]?.url ?: ""
                                        s.urlEditId = d.id
                                    }
                                com.haoai.agent.platform.sandbox.DistroManager.State.READY ->
                                    LiquidPillButton(
                                        backdrop = backdrop,
                                        text = "删除",
                                        // 破坏性操作：按"按钮五级"必须用危险级，
                                        // 不能用品牌色（改前是 primary 25% 的浅绿，语义反了）
                                        level = com.haoai.agent.ui.theme.HaoButtonLevel.Danger,
                                        enabled = !busy
                                    ) { s.pendingDeleteId = d.id }
                                com.haoai.agent.platform.sandbox.DistroManager.State.DAMAGED -> {
                                    LiquidPillButton(
                                        backdrop = backdrop,
                                        text = "重装",
                                        enabled = !busy
                                    ) {
                                        s.urlDraft = d.sources[com.haoai.agent.platform.sandbox.Proot.abiOf()]?.url ?: ""
                                        s.urlEditId = d.id
                                    }
                                    LiquidPillButton(
                                        backdrop = backdrop,
                                        text = "删除",
                                        // 破坏性操作：按"按钮五级"必须用危险级，
                                        // 不能用品牌色（改前是 primary 25% 的浅绿，语义反了）
                                        level = com.haoai.agent.ui.theme.HaoButtonLevel.Danger,
                                        enabled = !busy
                                    ) { s.pendingDeleteId = d.id }
                                }
                            }
                        }
                    }
                    if (s.installingId == d.id) {
                        Spacer(Modifier.height(8.dp))
                        val frac = if (s.progressTotal > 0)
                            (s.progressRead.toDouble() / s.progressTotal).toFloat() else 0f
                        androidx.compose.material3.LinearProgressIndicator(
                            progress = { frac },
                            modifier = Modifier.fillMaxWidth().height(4.dp),
                            strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
                            // M3 1.4 默认在轨道末端画 stopIndicator 小圆（Material You 规范），
                            // 用户反馈像"多出一个绿点"——置空关闭
                            gapSize = 0.dp,
                            drawStopIndicator = {}
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "${formatDistroSize(s.progressRead)} / " +
                                (if (s.progressTotal > 0) formatDistroSize(s.progressTotal) else "未知大小") +
                                "（${(frac * 100).toInt()}%）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
    item { SectionTitle("说明") }
    item {
        GlassGroup(backdrop = backdrop) {
            Text(
                "安装 = 下载 → sha256 校验 → 解压 → 初始化（DNS/时区/haoai-env）。" +
                    "无论使用官方源还是自定义镜像，校验都按官方 sha256 清单执行，不匹配即拒绝安装。" +
                    "安装完成后由 3.3 的 Linux Shell 后端在沙箱内执行命令。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
            )
        }
    }
}

/** 5.3 内部任务模型选择行（显示当前生效目标）。 */
@Composable
private fun purposeRow(
    label: String,
    purpose: String,
    configId: String,
    settings: AppSettings,
    vm: SettingsViewModel,
    backdrop: com.kyant.backdrop.backdrops.LayerBackdrop,
    onPick: (String) -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onPick(purpose) }
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                when {
                    configId.isBlank() -> "主模型"
                    configId == "local" -> "端侧（llama.cpp）"
                    // "pid|model"：同供应商指定模型（聊天会话精确路由）
                    configId.contains('|') -> {
                        val (pid, mid) = configId.split('|', limit = 2)
                        val pn = settings.providers.find { it.id == pid }?.name ?: "已失效服务"
                        "$pn · $mid"
                    }
                    else -> settings.providers.find { it.id == configId }?.name ?: "已失效服务"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text("更换 ›", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
    }
}

/** 色相条 / 色轮共用彩虹色序（首尾同色，sweep 与 horizontal 均无缝衔接）。 */
private val PickerHueColors = listOf(
    Color(0xFFFF3B30), Color(0xFFFF9500), Color(0xFFFFCC00), Color(0xFF34C759),
    Color(0xFF00C7BE), Color(0xFF007AFF), Color(0xFF5856D6), Color(0xFFAF52DE),
    Color(0xFFFF2D55), Color(0xFFFF3B30)
)

/**
 * 自定义主题色盘弹层（液态玻璃材质，弹窗内降级磨砂——禁自引用折射防崩溃）。
 * 简化交互（三步）：拖色盘=纯预览（不写任何槽）→ 点槽位=仅选中（零写入）→
 * 「保存」=当前色写入选中槽+应用+关闭。全程无长按、无中途写入、无快照回滚。
 * 选中态用 primary 描边（稳定色，不随预览色变）；保存成功后槽圆点回弹动效确认。
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ColorPickerDialog(
    backdrop: com.kyant.backdrop.backdrops.LayerBackdrop,
    settings: AppSettings,
    initialHex: String,
    initialSlot: Int,
    vm: SettingsViewModel,
    onDismiss: () -> Unit
) {
    val init = com.haoai.agent.ui.theme.parseHexColor(initialHex) ?: Color(0xFFFF7A45)
    val initHsv = FloatArray(3).also { android.graphics.Color.colorToHSV(init.toArgb(), it) }
    var hue by remember { mutableFloatStateOf(initHsv[0]) }
    var sat by remember { mutableFloatStateOf(initHsv[1]) }
    var vel by remember { mutableFloatStateOf(initHsv[2]) }
    var activeSlot by remember { mutableIntStateOf(initialSlot) }
    // 槽位本地镜像：拖动中逐帧改这个（重组仅限弹层），落盘走离散的 saveCustomSeed
    val slotColors = remember {
        androidx.compose.runtime.mutableStateListOf<String>().apply {
            addAll(settings.customSeedColors.take(3))
            while (size < 3) add("")
        }
    }
    val cur = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, vel)))
    val hexText = cur.toHex()
    // 保存成功动效：被保存槽的圆点 1→1.3→1 回弹（index 或 -1）
    var bumpSlot by remember { mutableIntStateOf(-1) }
    val bumpScale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (bumpSlot >= 0) 1.3f else 1f,
        animationSpec = androidx.compose.animation.core.keyframes {
            durationMillis = 300
            0f to 1f
            120 to 1.3f
            300 to 1f
        },
        label = "slotBump"
    )
    androidx.compose.runtime.LaunchedEffect(bumpSlot) {
        if (bumpSlot >= 0) {
            kotlinx.coroutines.delay(320)
            bumpSlot = -1
        }
    }

    androidx.compose.runtime.CompositionLocalProvider(
        com.haoai.agent.ui.common.LocalGlassRefract provides false
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.30f))
                .clickable(interactionSource = null, indication = null) { onDismiss() },
            contentAlignment = Alignment.Center
        ) {
            GlassPanel(
                backdrop = backdrop,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 22.dp),
                radius = 28.dp,
                surfaceAlpha = 0.92f,
                blurRadius = 28.dp,
                chromaticAberration = true
            ) {
                Column(
                    Modifier
                        .clickable(interactionSource = null, indication = null) {}
                        .padding(horizontal = 16.dp)
                        .padding(top = 16.dp, bottom = 14.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "自定义颜色",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            hexText,
                            style = MaterialTheme.typography.labelMedium,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        if (activeSlot < 0) "拖动选色 → 点要保存的槽 → 保存"
                        else "将把 $hexText 保存到槽 ${activeSlot + 1}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp, bottom = 10.dp)
                    )

                    // SV 面板：横向=饱和度，纵向=明度；背景双层渐变（白→纯色 再叠 透明→黑）
                    BoxWithConstraints(
                        Modifier
                            .fillMaxWidth()
                            .height(150.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .pointerInput(Unit) {
                                detectTapGestures { pos ->
                                    sat = (pos.x / size.width).coerceIn(0f, 1f)
                                    vel = (1f - pos.y / size.height).coerceIn(0f, 1f)
                                }
                            }
                            .pointerInput(Unit) {
                                detectDragGestures { change, _ ->
                                    change.consume()
                                    sat = (change.position.x / size.width).coerceIn(0f, 1f)
                                    vel = (1f - change.position.y / size.height).coerceIn(0f, 1f)
                                }
                            }
                    ) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.horizontalGradient(listOf(Color.White, Color.hsv(hue, 1f, 1f)))
                                )
                        )
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
                        )
                        Box(
                            Modifier
                                .offset(x = maxWidth * sat - 9.dp, y = maxHeight * (1f - vel) - 9.dp)
                                .size(18.dp)
                                .clip(CircleShape)
                                .background(cur.copy(alpha = 0.4f))
                        )
                        Box(
                            Modifier
                                .offset(x = maxWidth * sat - 9.dp, y = maxHeight * (1f - vel) - 9.dp)
                                .size(18.dp)
                                .border(2.5.dp, Color.White, CircleShape)
                        )
                    }

                    // 色相条
                    BoxWithConstraints(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp)
                            .height(16.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Brush.horizontalGradient(PickerHueColors))
                            .pointerInput(Unit) {
                                detectTapGestures { pos ->
                                    hue = (pos.x / size.width * 360f).coerceIn(0f, 359.9f)
                                }
                            }
                            .pointerInput(Unit) {
                                detectDragGestures { change, _ ->
                                    change.consume()
                                    hue = (change.position.x / size.width * 360f).coerceIn(0f, 359.9f)
                                }
                            }
                    ) {
                        Box(
                            Modifier
                                .align(Alignment.CenterStart)
                                .offset(x = maxWidth * (hue / 360f) - 10.dp)
                                .size(20.dp)
                                .clip(CircleShape)
                                .background(Color.hsv(hue, 1f, 1f))
                                .border(2.5.dp, Color.White, CircleShape)
                        )
                    }

                    // 预览 + 槽位选择：点槽位=当前色直接存入并应用；再点已选槽=取消选中
                    Row(
                        Modifier.padding(top = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(cur)
                                .border(1.5.dp, MaterialTheme.colorScheme.background, CircleShape)
                        )
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                "保存到",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(
                                Modifier.padding(top = 5.dp),
                                horizontalArrangement = Arrangement.spacedBy(7.dp)
                            ) {
                                repeat(3) { i ->
                                    val savedColor = com.haoai.agent.ui.theme.parseHexColor(slotColors[i])
                                    val selected = activeSlot == i
                                    Row(
                                        Modifier
                                            .clip(RoundedCornerShape(percent = 50))
                                            .background(
                                                if (selected) MaterialTheme.colorScheme.primaryContainer
                                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                                            )
                                            .border(
                                                // 选中描边用 primary（稳定色）——不随预览色变，预览色做
                                                // UI 状态色会遇浅色不可见/深色过重，且与主题色行语义冲突
                                                if (selected) 1.5.dp else 1.dp,
                                                if (selected) MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                                                RoundedCornerShape(percent = 50)
                                            )
                                            // 单击=仅切换选中（零写入）；写入只发生在「保存」
                                            .clickable { activeSlot = if (activeSlot == i) -1 else i }
                                            .padding(horizontal = 10.dp, vertical = 5.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                                    ) {
                                        if (savedColor != null) {
                                            Box(
                                                Modifier
                                                    .size(12.dp)
                                                    .scale(if (bumpSlot == i) bumpScale else 1f)
                                                    .clip(CircleShape)
                                                    .background(savedColor)
                                            )
                                        } else {
                                            Box(
                                                Modifier
                                                    .size(12.dp)
                                                    .clip(CircleShape)
                                                    .border(
                                                        1.2.dp,
                                                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                                        CircleShape
                                                    )
                                            )
                                        }
                                        Text(
                                            "槽 ${i + 1}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // 取消=直接关闭（本次未写入任何内容，无需回滚）
                        LiquidPillButton(
                            backdrop,
                            "取消",
                            Modifier.weight(1f),
                            emphasized = false
                        ) { onDismiss() }
                        // 保存=唯一写入点：当前色写入选中槽+应用；未选槽禁用
                        LiquidPillButton(
                            backdrop = backdrop,
                            text = "保存",
                            modifier = Modifier.weight(1f),
                            enabled = activeSlot >= 0
                        ) {
                            if (activeSlot >= 0) {
                                vm.saveCustomSeed(activeSlot, hexText)
                                bumpSlot = activeSlot
                                activeSlot = -1
                                onDismiss()
                            }
                        }
                    }
                }
            }
        }
    }
}
