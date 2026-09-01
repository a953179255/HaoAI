package com.haoai.agent.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Construction
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Tune
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import com.haoai.agent.agent.policy.PermissionMode
import com.haoai.agent.data.AppSettings
import com.haoai.agent.platform.KeepAliveService
import com.haoai.agent.platform.llama.LlamaState
import com.haoai.agent.ui.SettingsViewModel
import com.haoai.agent.ui.common.GlassCard
import com.haoai.agent.ui.common.GlassPageBar
import com.haoai.agent.ui.common.GlassPanel
import com.haoai.agent.ui.common.GlassTextButton
import com.haoai.agent.ui.common.glassFieldColors
import kotlinx.coroutines.launch

/**
 * 设置主页（上游 移动端式分组导航）：
 * 根页面只列分类入口，具体设置项进各子页，避免功能增多后平铺混乱。
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun SettingsScreen(
    vm: SettingsViewModel,
    backdrop: com.kyant.backdrop.backdrops.LayerBackdrop,
    onBack: () -> Unit,
    initialSection: String = "",
    onSectionChange: (String) -> Unit = {},
    onOpenMemories: () -> Unit = {},
    onOpenSchedules: () -> Unit = {},
    onOpenSkills: () -> Unit = {},
    onOpenMcp: () -> Unit = {},
    onOpenWorkflows: () -> Unit = {}
) {
    val context = LocalContext.current
    val settings by vm.settings.collectAsState()
    val draft = vm.draft
    val scope = rememberCoroutineScope()

    // 玻璃弹窗状态
    var showScan by androidx.compose.runtime.remember { mutableStateOf(false) }
    // 待删除的供应商 id：点击删除先弹确认（防止误触直接删库）
    var pendingDelete by androidx.compose.runtime.remember { mutableStateOf<String?>(null) }
    // 记忆专用端侧模型选择弹窗
    var showDreamPicker by androidx.compose.runtime.remember { mutableStateOf(false) }
    var wpVersion by androidx.compose.runtime.remember { mutableStateOf(vm.wallpaperSet(context)) }
    var confirmWpClear by androidx.compose.runtime.remember { mutableStateOf(false) }

    // section 状态由 MainActivity 提升（从记忆库等管理页返回时恢复原子页）
    var section by rememberSaveable { mutableStateOf(initialSection) }
    androidx.compose.runtime.LaunchedEffect(section) { if (section != initialSection) onSectionChange(section) }
    // 用量页「清空账本」确认弹窗：状态提在根级（弹窗不能渲染在 LazyColumn item 内——
    // fillMaxSize 遮罩会受 item 高度约束，实测只盖住下半屏）
    var confirmClearLedger by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    // 「模型大脑 → 内部任务模型」选择弹窗：同样必须在根级渲染
    var purposePicker by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    // 设置根页列表滚动状态：必须在 if(section) 分支之外 remember，否则进二级页返回后回到顶部
    val rootListState = androidx.compose.foundation.lazy.rememberLazyListState()
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
        if (section.isNotEmpty()) section = "" else onBack()
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

    if (section.isEmpty()) {
        Box(
            Modifier
                .fillMaxSize()
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
            ) {
                Spacer(Modifier.height(56.dp))
                LazyColumn(
                    state = rootListState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                item {
                    MenuCard(
                        backdrop = backdrop,
                        icon = Icons.Filled.SmartToy,
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
                        tint = Color(0xFF5B8DEF),
                        onClick = { section = "brain" }
                    )
                }
                item {
                    MenuCard(
                        backdrop = backdrop,
                        icon = Icons.Filled.Security,
                        title = "权限与自动化",
                        subtitle = permissionLabel(settings.permissionMode) +
                            if (a11yOn) " · 无障碍已启用" else " · 无障碍未启用",
                        tint = Color(0xFFEF7D54),
                        onClick = { section = "privacy" }
                    )
                }
                item {
                    MenuCard(
                        backdrop = backdrop,
                        icon = Icons.Filled.AutoFixHigh,
                        title = "记忆与梦境",
                        subtitle = "${vm.homeCounts.first} 条长期记忆" +
                            if (settings.deepDream) " · 深度梦境开" else "",
                        tint = Color(0xFF3FA37A),
                        onClick = { section = "memory" }
                    )
                }
                item {
                    MenuCard(
                        backdrop = backdrop,
                        icon = Icons.Filled.Construction,
                        title = "技能库",
                        subtitle = "${vm.homeCounts.third} 个沉淀技能",
                        tint = Color(0xFFD9539E),
                        onClick = onOpenSkills
                    )
                }
                item {
                    MenuCard(
                        backdrop = backdrop,
                        icon = Icons.Filled.Schedule,
                        title = "定时任务",
                        subtitle = "到点自动执行并通知",
                        tint = Color(0xFFD9913F),
                        onClick = onOpenSchedules
                    )
                }
                item {
                    val mcpServers = com.haoai.agent.agent.mcp.McpManager.listServers()
                        val mcpLabel = if (mcpServers.isEmpty()) "未接入"
                        else "${mcpServers.count { it.enabled }}/${mcpServers.size} 个服务器已启用"
                    MenuCard(
                        backdrop = backdrop,
                        icon = Icons.Filled.Extension,
                        title = "MCP 服务器",
                        subtitle = "外部工具扩展 · $mcpLabel",
                        tint = Color(0xFF7C6BE8),
                        onClick = onOpenMcp
                    )
                }
                item {
                    val linuxLabel = run {
                        val st = linuxState.statuses
                        if (linuxState.installingId != null) "正在安装…"
                        else if (st.isEmpty()) "未初始化 · 点击查看"
                        else {
                            val ready = st.count { it.state == com.haoai.agent.platform.sandbox.DistroManager.State.READY }
                            if (ready > 0) "${st.first { it.state == com.haoai.agent.platform.sandbox.DistroManager.State.READY }.distro.name} · 已就绪"
                            else if (st.any { it.state == com.haoai.agent.platform.sandbox.DistroManager.State.DAMAGED }) "rootfs 已损坏 · 建议重装"
                            else "未安装 · 点击安装"
                        }
                    }
                    MenuCard(
                        backdrop = backdrop,
                        icon = Icons.Filled.Terminal,
                        title = "Linux 环境",
                        subtitle = "沙箱发行版 · $linuxLabel",
                        tint = Color(0xFF4A6FA5),
                        onClick = { section = "linux" }
                    )
                }
                item {
                    MenuCard(
                        backdrop = backdrop,
                        icon = Icons.Filled.Folder,
                        title = "工作空间",
                        subtitle = vm.workspaceName(),
                        tint = Color(0xFF38A3C7),
                        onClick = { section = "workspace" }
                    )
                }
                item {
                    MenuCard(
                        backdrop = backdrop,
                        icon = Icons.Filled.Tune,
                        title = "通用",
                        subtitle = "后台保活 · 自定义指令 · 身份",
                        tint = Color(0xFF64748B),
                        onClick = { section = "general" }
                    )
                }
                item {
                    MenuCard(
                        backdrop = backdrop,
                        icon = Icons.Filled.Bolt,
                        title = "工作流",
                        subtitle = "多步自动化 · 定时/开机/通知触发",
                        tint = Color(0xFFD9913F),
                        onClick = onOpenWorkflows
                    )
                }
                item {
                    MenuCard(
                        backdrop = backdrop,
                        icon = Icons.Filled.Equalizer,
                        title = "用量",
                        subtitle = "Token 用量统计 · 内部调用账本",
                        tint = Color(0xFF3FA37A),
                        onClick = { section = "usage" }
                    )
                }
                item {
                    MenuCard(
                        backdrop = backdrop,
                        icon = Icons.Filled.Info,
                        title = "关于",
                        subtitle = "版本信息",
                        tint = Color(0xFF94A3B8),
                        onClick = { section = "about" }
                    )
                }
            }
            }
            GlassPageBar(
                backdrop = backdrop,
                title = "设置",
                onBack = onBack,
                modifier = Modifier
                    .align(Alignment.TopCenter)
            )
        }
    } else {
        Box(
            Modifier
                .fillMaxSize()
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
            ) {
                Spacer(Modifier.height(56.dp))
                LazyColumn(
                    state = rootListState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    when (section) {
                        "brain" -> {
                            brainItems(vm, settings, backdrop, onDeleteRequest = { pendingDelete = it }, onPickPurposeModel = { purposePicker = it })
                            localItems(vm, backdrop, onOpenScan = { showScan = true })
                        }
                        "privacy" -> privacyItems(vm, settings, context, a11yOn, backdrop)
                        "memory" -> memoryItems(vm, settings, onOpenMemories, backdrop, onPickDreamModel = { showDreamPicker = true })
                        "workspace" -> workspaceItems(vm, backdrop) { treePicker.launch(null) }
                        "linux" -> linuxItems(vm, backdrop, linuxState)
                        "general" -> generalItems(
                            vm, settings, context, backdrop,
                            wpVersion, onWpVersionChange = { wpVersion = it },
                            onRequestClearWallpaper = { confirmWpClear = true }
                        )
                        "about" -> aboutItems(vm, settings, backdrop)
                        "usage" -> usageItems(vm, settings, backdrop, onRequestClearLedger = { confirmClearLedger = true })
                    }
                }
            }
            GlassPageBar(
                backdrop = backdrop,
                title = sectionTitle(section),
                onBack = { section = "" },
                modifier = Modifier
                    .align(Alignment.TopCenter)
            )
        }
    }

    draft?.let { d ->
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

    // 删除供应商确认：红色确认键，展示供应商名与模型
    pendingDelete?.let { pid ->
        val p = vm.providers().find { it.id == pid }
        com.haoai.agent.ui.common.GlassAlertDialog(
            backdrop = backdrop,
            title = "删除模型服务",
            onDismiss = { pendingDelete = null },
            confirmLabel = "删除",
            onConfirm = {
                vm.deleteProvider(pid)
                pendingDelete = null
            },
            dismissLabel = "取消",
            danger = true
        ) {
            Text(
                "确定删除「${p?.name ?: "该服务"}」（${p?.model ?: ""}）吗？删除后不可恢复。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f)
            )
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

    // 「模型大脑 → 内部任务模型」选择窗（根级渲染，点行弹窗选择）
    if (purposePicker != null) {
        val purpose = purposePicker!!
        val current = when (purpose) {
            "title" -> settings.titleProviderId
            "memory" -> settings.memoryExtractProviderId
            else -> settings.summarizeProviderId
        }
        val options = listOf("" to "主模型") +
            settings.providers
                .filter { it.id != com.haoai.agent.platform.llama.LlamaServerController.LOCAL_PROVIDER_ID }
                .map { it.id to it.name } +
            listOf("local" to "端侧（llama.cpp）")
        com.haoai.agent.ui.common.GlassAlertDialog(
            backdrop = backdrop,
            title = "选择模型",
            confirmLabel = "关闭",
            onConfirm = { purposePicker = null },
            onDismiss = { purposePicker = null }
        ) {
            Column {
                options.forEach { (id, name) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                vm.setPurposeModel(purpose, id)
                                purposePicker = null
                            }
                            .padding(horizontal = 8.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (id == current) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onBackground,
                            fontWeight = if (id == current) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.weight(1f)
                        )
                        if (id == current) Text("✓", color = MaterialTheme.colorScheme.primary)
                    }
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

@Composable
private fun MenuCard(
    backdrop: com.kyant.backdrop.backdrops.LayerBackdrop,
    icon: ImageVector,
    title: String,
    subtitle: String,
    tint: Color = MaterialTheme.colorScheme.primary,
    onClick: () -> Unit
) {
    GlassCard(
        onClick = onClick,
        backdrop = backdrop,
        shape = RoundedCornerShape(20.dp),
        surfaceAlpha = 0.26f,
        tint = tint.copy(alpha = 0.10f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(44.dp)
                    .background(
                        androidx.compose.ui.graphics.Brush.verticalGradient(
                            listOf(tint.copy(alpha = 0.16f), tint.copy(alpha = 0.34f))
                        ),
                        RoundedCornerShape(14.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.size(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
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
}

private fun permissionLabel(mode: PermissionMode): String = when (mode) {
    PermissionMode.ALWAYS_ASK -> "全部询问"
    PermissionMode.ASK_WRITES -> "写入时询问"
    PermissionMode.YOLO -> "全自动"
}

private fun sectionTitle(section: String): String = when (section) {
    "brain" -> "模型大脑"
    "privacy" -> "权限与自动化"
    "memory" -> "记忆与梦境"
    "workspace" -> "工作空间"
    "linux" -> "Linux 环境"
    "general" -> "通用"
    "about" -> "关于"
    "usage" -> "用量"
    else -> ""
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
    onPickPurposeModel: (String) -> Unit = {}
) {
    item { SectionTitle("云端模型服务") }
    item {
        val ps = settings.providers.filter { it.id != com.haoai.agent.platform.llama.LlamaServerController.LOCAL_PROVIDER_ID }
        val activeId = settings.activeProviderId
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            GlassGroup(backdrop) {
                Column(Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) {
                    ps.forEachIndexed { idx, p ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    if (activeId != p.id) vm.setActiveProvider(p.id)
                                }
                                .padding(start = 8.dp, end = 2.dp, top = 10.dp, bottom = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = p.id == activeId,
                                onClick = { vm.setActiveProvider(p.id) }
                            )
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(p.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                    if (p.id == activeId) {
                                        Spacer(Modifier.size(8.dp))
                                        Text(
                                            "使用中",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                                Text(
                                    "${p.model} · ctx ${p.effectiveContextLength() / 1024}K" +
                                        (p.maxTokens.takeIf { it > 0 }?.let { " · max $it" } ?: ""),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    p.baseUrl,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                    maxLines = 1
                                )
                            }
                            IconButton(onClick = { vm.editProvider(p) }) {
                                Icon(Icons.Filled.Edit, contentDescription = "编辑", modifier = Modifier.size(19.dp))
                            }
                            if (p.id != com.haoai.agent.platform.llama.LlamaServerController.LOCAL_PROVIDER_ID) {
                                IconButton(onClick = { onDeleteRequest(p.id) }) {
                                    Icon(Icons.Filled.Delete, contentDescription = "删除", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(19.dp))
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
                }
            }
            LiquidPillButton(
                backdrop = backdrop,
                text = "添加模型服务",
                modifier = Modifier.fillMaxWidth(),
                emphasized = true
            ) { vm.startNewDraft() }
            Text(
                "点击卡片切换当前使用的服务；支持 DeepSeek/OpenRouter/Kimi/智谱/Ollama 预设、能力自动检测、测试连接与在线拉取模型列表。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    item { SectionTitle("内部任务模型") }
    item {
        // purpose 配置行：点击弹根级选择窗（选择窗状态在 SettingsScreen 根部）
        Column(Modifier.padding(horizontal = 16.dp)) {
            GlassGroup(backdrop) {
                Column(Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) {
                    purposeRow("会话标题", "title", settings.titleProviderId, settings, vm, backdrop) { onPickPurposeModel(it) }
                    purposeRow("记忆提取", "memory", settings.memoryExtractProviderId, settings, vm, backdrop) { onPickPurposeModel(it) }
                    purposeRow("上下文压缩", "summarize", settings.summarizeProviderId, settings, vm, backdrop) { onPickPurposeModel(it) }
                }
            }
            Text(
                "为内部辅助任务指定独立（更廉价的）模型；「主模型」= 跟随当前云端服务，「端侧」= 本机 llama.cpp 小模型。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp)
            )
        }
    }
}

private fun LazyListScope.localItems(vm: SettingsViewModel, backdrop: com.kyant.backdrop.backdrops.LayerBackdrop, onOpenScan: () -> Unit) {
    item { SectionTitle("端侧推理（llama.cpp 本地运行）") }
    item {
        // Phase 7 阶段2：SoC 后端检测结果
        val detected = remember { com.haoai.agent.platform.llama.LlamaServerController.detectHexagonArch() }
        Text(
            "推理后端：三后端单二进制（CPU/GPU/NPU-Hexagon）· " +
                (detected?.let { "本机骁龙 → Hexagon $it（arm64 启动时启用，失败自动 CPU 兜底）" } ?: "本机走 CPU/GPU 兜底") +
                " · 当前生效：${vm.backendLabel()}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp)
        )
    }
    item {
        val llama by vm.llamaState.collectAsState()
        val dl by vm.llamaDownload.collectAsState()
        var dlUrl by androidx.compose.runtime.remember {
            mutableStateOf(com.haoai.agent.platform.llama.LlamaServerController.DEFAULT_MODEL_URL)
        }
        Column(Modifier.padding(16.dp)) {
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
                            .padding(top = 8.dp)
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
            Text(
                "提示：优先用「扫描」或「加载模型文件」直读手机上已有的 GGUF（不复制、不占双份空间）；" +
                    "视觉投影文件与模型同名放置即可自动启用图像识别。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
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
        GlassGroup(backdrop, modifier = Modifier.padding(horizontal = 16.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    PermissionMode.ALWAYS_ASK to "全部询问",
                    PermissionMode.ASK_WRITES to "写入时询问",
                    PermissionMode.YOLO to "全自动"
                ).forEach { (m, label) ->
                    val selected = mode == m
                    com.haoai.agent.ui.common.LiquidGlassButton(
                        onClick = { vm.setPermissionMode(m) },
                        backdrop = backdrop,
                        shape = RoundedCornerShape(percent = 50),
                        modifier = Modifier.weight(1f),
                        surfaceColor = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
                        else Color.White.copy(alpha = 0.10f)
                    ) {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (selected) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.78f),
                            maxLines = 1,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
                        )
                    }
                }
            }
        }
    }
    item { SectionTitle("无障碍自动化") }
    item {
        GlassGroup(backdrop, modifier = Modifier.padding(horizontal = 16.dp)) {
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
                    runCatching {
                        context.startActivity(
                            android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                }
            }
        }
    }
    item { SectionTitle("后台自动化（虚拟屏）") }
    item {
        val vscreenSupported = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R
        GlassGroup(backdrop, modifier = Modifier.padding(horizontal = 16.dp)) {
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
            Text(
                "说明：操作走无障碍节点（点击/输入/滚动），无需触摸注入；精确手势（拖动滑块）本版本未启用，" +
                    "此类操作会明确报受限并引导节点方案。熄屏场景在部分 ROM 受限。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
            )
        }
    }
    item { SectionTitle("系统权限") }
    item {
        androidx.compose.runtime.LaunchedEffect(Unit) { vm.refreshPermissions(context) }
    }
    item {
        GlassGroup(backdrop, modifier = Modifier.padding(horizontal = 16.dp)) {
            com.haoai.agent.platform.PermissionCenter.ALL.forEach { spec ->
                val granted = vm.permissionStates[spec.key]
                    ?: com.haoai.agent.platform.PermissionCenter.granted(context, spec)
                Row(
                    Modifier
                        .fillMaxWidth()
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
                        Text("✓", color = Color(0xFF3E9B5F), fontWeight = FontWeight.Bold)
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
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            GlassStatTile(
                backdrop = backdrop,
                number = "${vm.homeCounts.first}",
                label = "长期记忆",
                tint = Color(0xFF3FA37A),
                modifier = Modifier.weight(1f)
            )
            GlassStatTile(
                backdrop = backdrop,
                number = "${vm.homeCounts.second}",
                label = "今日日志",
                tint = Color(0xFF5B8DEF),
                modifier = Modifier.weight(1f)
            )
            GlassStatTile(
                backdrop = backdrop,
                number = if (settings.deepDream) "开" else "关",
                label = "梦境整理",
                tint = Color(0xFFD9913F),
                modifier = Modifier.weight(1f)
            )
        }
    }
    item {
        Column(Modifier.padding(horizontal = 16.dp)) {
            com.haoai.agent.ui.common.GlassCard(
                onClick = onOpenMemories,
                backdrop = backdrop,
                shape = RoundedCornerShape(18.dp),
                surfaceAlpha = 0.24f,
                lensRadius = 14.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
            ) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Memory,
                        contentDescription = null,
                        tint = Color(0xFF3FA37A),
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
            Text(
                "固化规则：每日日志中重要性 ≥4 的条目夜间自动晋升为长期记忆；日志保留 7 天后清理。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, start = 4.dp)
            )
        }
    }
    item { SectionTitle("记录策略") }
    item {
        GlassGroup(backdrop, modifier = Modifier.padding(horizontal = 16.dp)) {
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
        GlassGroup(backdrop, modifier = Modifier.padding(horizontal = 16.dp)) {
            ToggleRow(
                title = "闲置时自动整理记忆",
                subtitle = "充电且灭屏持续所选时间后执行，仅 00:00–7:00 夜间时段生效；亮屏或断电即取消。固化历史写入 DREAMS.md。",
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
        GlassGroup(backdrop, modifier = Modifier.padding(horizontal = 16.dp)) {
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
        com.haoai.agent.ui.common.LiquidToggle(
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
        Column(Modifier.padding(16.dp)) {
            // 顶部说明卡片：工作空间是什么、两种目录的差异
            GlassGroup(backdrop) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Folder,
                            contentDescription = null,
                            tint = Color(0xFF38A3C7),
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
                    emphasized = false
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
    onRequestClearWallpaper: () -> Unit
) {
    item { SectionTitle("外观") }
    item {
        GlassGroup(backdrop, modifier = Modifier.padding(horizontal = 16.dp)) {
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
            // —— 主题色 / 动态颜色 / AMOLED / 气泡不透明度 ——
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
                    Row(
                        Modifier.padding(top = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(9.dp)
                    ) {
                        com.haoai.agent.ui.theme.THEME_SEEDS.forEachIndexed { i, seed ->
                            val color = if (darkNow) seed.darkPrimary else seed.lightPrimary
                            val selected = settings.themeSeed == i
                            Box(
                                Modifier
                                    .size(28.dp)
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val bubbleValue = settings.bubbleOpacity.coerceIn(0.3f, 1f)
                    val pct = bubbleValue.times(100).toInt()
                    Text("气泡 / 卡片不透明度", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.weight(1f))
                    Text(
                        "$pct%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                androidx.compose.material3.Slider(
                    value = settings.bubbleOpacity.coerceIn(0.3f, 1f),
                    onValueChange = { vm.setBubbleOpacity(it) },
                    valueRange = 0.3f..1f,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            HorizontalDivider(
                Modifier.padding(horizontal = 14.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f)
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("聊天背景壁纸", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (wpVersion) "已设置 · 玻璃效果将以壁纸为折射背景" else "未设置（使用默认深色渐变）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                val wpPicker = rememberLauncherForActivityResult(
                    ActivityResultContracts.GetContent()
                ) { uri ->
                    uri?.let {
                        vm.setWallpaper(context, it.toString())
                        onWpVersionChange(vm.wallpaperSet(context))
                    }
                }
                TextButton(onClick = { wpPicker.launch("image/*") }) { Text("选择图片") }
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
    item { SectionTitle("模型行为") }
    item {
        GlassGroup(backdrop, modifier = Modifier.padding(horizontal = 16.dp)) {
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
    item { SectionTitle("后台") }
    item {
        GlassGroup(backdrop, modifier = Modifier.padding(horizontal = 16.dp)) {
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
        }
    }
    item { SectionTitle("自定义指令") }
    item {
        OutlinedTextField(
            value = settings.customPrompt,
            onValueChange = { vm.setCustomPrompt(it) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            placeholder = { Text("附加到系统提示末尾的个人偏好…") },
            minLines = 2,
            maxLines = 6,
            colors = com.haoai.agent.ui.common.glassFieldColors()
        )
    }
}

/** 「用量」页（5.4）：今日/本周/本月汇总 + 按模型/用途分布条形（Canvas 自绘）+ 会话 Top5 + 清空账本。 */
private fun LazyListScope.usageItems(
    vm: SettingsViewModel,
    settings: AppSettings,
    backdrop: com.kyant.backdrop.backdrops.LayerBackdrop,
    onRequestClearLedger: () -> Unit = {}
) {
    item {
        // 读文件较重：进入页面时计算一次（清空后重进更新）
        val summary = androidx.compose.runtime.remember {
            com.haoai.agent.data.UsageLedger.summarize()
        }

        Column {
            SectionTitle("Token 汇总")
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                GlassStatTile(
                    backdrop = backdrop,
                    number = formatTokens(summary.todayIn + summary.todayOut),
                    label = "今日合计",
                    tint = Color(0xFF5B8DEF),
                    modifier = Modifier.weight(1f)
                )
                GlassStatTile(
                    backdrop = backdrop,
                    number = formatTokens(summary.weekIn + summary.weekOut),
                    label = "本周",
                    tint = Color(0xFF3FA37A),
                    modifier = Modifier.weight(1f)
                )
                GlassStatTile(
                    backdrop = backdrop,
                    number = formatTokens(summary.monthIn + summary.monthOut),
                    label = "本月",
                    tint = Color(0xFF8B7BEF),
                    modifier = Modifier.weight(1f)
                )
            }
            Text(
                "共 ${summary.entries} 条记录 · 输入 ${formatTokens(summary.monthIn)} / 输出 ${formatTokens(summary.monthOut)}（本月）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
            )

            if (summary.byModel.isNotEmpty()) {
                SectionTitle("按模型")
                Column(Modifier.padding(horizontal = 16.dp)) {
                    summary.byModel.take(6).forEach { m ->
                        UsageBar(
                            label = m.model,
                            value = m.promptTokens + m.completionTokens,
                            maxValue = summary.byModel.maxOf { it.promptTokens + it.completionTokens },
                            detail = "${formatTokens(m.promptTokens + m.completionTokens)} · ${m.calls} 次",
                            tint = Color(0xFF5B8DEF)
                        )
                    }
                }
            }

            if (summary.byPurpose.isNotEmpty()) {
                SectionTitle("按用途")
                Column(Modifier.padding(horizontal = 16.dp)) {
                    summary.byPurpose.take(6).forEach { p ->
                        UsageBar(
                            label = purposeLabel(p.purpose),
                            value = p.promptTokens + p.completionTokens,
                            maxValue = summary.byPurpose.maxOf { it.promptTokens + it.completionTokens },
                            detail = "${formatTokens(p.promptTokens + p.completionTokens)} · ${p.calls} 次",
                            tint = Color(0xFF3FA37A)
                        )
                    }
                }
            }

            if (summary.bySession.size > 1) {
                SectionTitle("会话排行")
                Column(Modifier.padding(horizontal = 16.dp)) {
                    summary.bySession.take(5).forEach { s ->
                        val title = runCatching {
                            vm.sessionTitleOf(s.sessionId)
                        }.getOrNull().orEmpty().ifBlank { "会话 ${s.sessionId.take(8)}" }
                        UsageBar(
                            label = title,
                            value = s.promptTokens + s.completionTokens,
                            maxValue = summary.bySession.maxOf { it.promptTokens + it.completionTokens },
                            detail = "${formatTokens(s.promptTokens + s.completionTokens)} · ${s.calls} 次调用",
                            tint = Color(0xFFD9913F)
                        )
                    }
                }
            }

            SectionTitle("每日预算（5.1）")
            Column(Modifier.padding(horizontal = 16.dp)) {
                GlassGroup(backdrop) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        Text(
                            "超 70% 提醒精简；达 100% 后定时任务与云端梦境固化自动跳过（手动对话不中断）。0 = 不限。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            var budgetText by androidx.compose.runtime.remember(settings.dailyTokenBudgetK) {
                                androidx.compose.runtime.mutableStateOf(settings.dailyTokenBudgetK.let { if (it == 0) "" else it.toString() })
                            }
                            OutlinedTextField(
                                value = budgetText,
                                onValueChange = { t ->
                                    budgetText = t.filter { ch -> ch.isDigit() }.take(5)
                                    vm.setDailyTokenBudgetK(budgetText.toIntOrNull() ?: 0)
                                },
                                modifier = Modifier.width(140.dp),
                                singleLine = true,
                                placeholder = { Text("不限") },
                                trailingIcon = { Text("K tok/日", style = MaterialTheme.typography.labelSmall) },
                                colors = com.haoai.agent.ui.common.glassFieldColors()
                            )
                        }
                    }
                }
            }

            SectionTitle("维护")
            Column(Modifier.padding(horizontal = 16.dp)) {
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

@Composable
private fun UsageBar(label: String, value: Long, maxValue: Long, detail: String, tint: Color) {
    Column(Modifier.padding(vertical = 5.dp)) {
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
    item { SectionTitle("Token 概览") }
    item {
        Text(
            "详细统计（今日/本周/本月、按模型与用途分布、会话排行）已移至「用量」页。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
        )
    }
    item { SectionTitle("关于本机大脑") }
    item {
        val ctx = androidx.compose.ui.platform.LocalContext.current
        val ver = runCatching {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
        }.getOrNull() ?: "dev"
        Column(Modifier.padding(horizontal = 16.dp)) {
            GlassGroup(backdrop) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Text("HaoAI v$ver", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "云端大脑 + 端侧肌肉\n三层记忆 · 技能自进化 · 手机自动化 · 定时任务",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
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
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(start = 20.dp, top = 24.dp, bottom = 10.dp)
    ) {
        Box(
            Modifier
                .size(width = 4.dp, height = 14.dp)
                .background(
                    MaterialTheme.colorScheme.primary,
                    RoundedCornerShape(2.dp)
                )
        )
        Spacer(Modifier.size(8.dp))
        Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    }
}

/** 二级页通用玻璃分组容器：把一组相关设置项装进同一块液态玻璃。 */
@Composable
private fun GlassGroup(
    backdrop: com.kyant.backdrop.backdrops.LayerBackdrop,
    modifier: Modifier = Modifier,
    refract: Boolean? = null,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    com.haoai.agent.ui.common.GlassPanel(
        backdrop = backdrop,
        modifier = modifier.fillMaxWidth(),
        radius = 18.dp,
        surfaceAlpha = 0.30f,
        refract = refract
    ) {
        Column(Modifier.padding(vertical = 6.dp), content = content)
    }
}

/** 液态玻璃胶囊按钮（主操作），替代普通 Material Button。 */
@Composable
private fun LiquidPillButton(
    backdrop: com.kyant.backdrop.backdrops.LayerBackdrop,
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    emphasized: Boolean = true,
    refract: Boolean? = null,
    onClick: () -> Unit
) {
    com.haoai.agent.ui.common.LiquidGlassButton(
        onClick = onClick,
        backdrop = backdrop,
        shape = RoundedCornerShape(percent = 50),
        enabled = enabled,
        refract = refract,
        surfaceColor = MaterialTheme.colorScheme.primary.copy(
            alpha = if (emphasized && enabled) 0.85f else 0.25f
        ),
        modifier = modifier
    ) {
        Text(
            text,
            color = if (emphasized && enabled) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f),
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
        surfaceAlpha = 0.20f,
        tint = tint.copy(alpha = 0.10f),
        lensRadius = 12.dp,
        refract = refract,
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
                .padding(horizontal = 20.dp),
            radius = 28.dp,
            surfaceAlpha = 0.92f,
            blurRadius = 28.dp,
            chromaticAberration = true
        ) {
            Column(
                Modifier
                    .clickable(interactionSource = null, indication = null) {}
                    .padding(20.dp)
            ) {
                Text(
                    if (draft.id == null) "添加模型服务" else "编辑模型服务",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Column(
                    Modifier
                        .padding(top = 12.dp)
                        .heightIn(max = 460.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        com.haoai.agent.ui.ProviderPresets.all.take(3).forEach { p ->
                            AssistChip(
                                onClick = { onPreset(p) },
                                label = {
                                    Text(
                                        p.label,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f)
                                    )
                                },
                                colors = AssistChipDefaults.assistChipColors(
                                    containerColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.06f)
                                )
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        com.haoai.agent.ui.ProviderPresets.all.drop(3).forEach { p ->
                            AssistChip(
                                onClick = { onPreset(p) },
                                label = {
                                    Text(
                                        p.label,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f)
                                    )
                                },
                                colors = AssistChipDefaults.assistChipColors(
                                    containerColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.06f)
                                )
                            )
                        }
                    }
                    OutlinedTextField(
                        value = draft.name,
                        onValueChange = { v -> onChange(draft.copy(name = v)) },
                        label = { Text("名称（可留空自动命名）") },
                        singleLine = true,
                        colors = glassFieldColors()
                    )
                    // 协议选择（2.3）：anthropic 原生 Messages / openai_compat 默认
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "协议",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        FilterChip(
                            selected = draft.protocol == "openai_compat",
                            onClick = { onChange(draft.copy(protocol = "openai_compat")) },
                            label = { Text("OpenAI 兼容") }
                        )
                        FilterChip(
                            selected = draft.protocol == "anthropic",
                            onClick = { onChange(draft.copy(protocol = "anthropic")) },
                            label = { Text("Anthropic 原生") }
                        )
                    }
                    OutlinedTextField(
                        value = draft.baseUrl,
                        onValueChange = { v -> onChange(draft.copy(baseUrl = v)) },
                        label = {
                            Text(
                                if (draft.protocol == "anthropic") "Base URL（Anthropic Messages）"
                                else "Base URL（OpenAI 兼容）"
                            )
                        },
                        placeholder = {
                            Text(
                                if (draft.protocol == "anthropic") "https://api.anthropic.com"
                                else "https://openrouter.ai/api/v1"
                            )
                        },
                        singleLine = true,
                        colors = glassFieldColors()
                    )
                    OutlinedTextField(
                        value = draft.model,
                        onValueChange = { v -> onChange(draft.copy(model = v)) },
                        label = { Text("模型 ID") },
                        placeholder = { Text("如 deepseek-chat / stealth/ox-alpha") },
                        singleLine = true,
                        colors = glassFieldColors()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        GlassTextButton(
                            text = if (fetchingModels) "拉取中…" else "拉取模型列表",
                            onClick = onFetchModels,
                            enabled = !fetchingModels && draft.baseUrl.isNotBlank(),
                            backdrop = backdrop
                        )
                        GlassTextButton(
                            text = if (detectingCaps) "检测中…" else "自动检测能力",
                            onClick = onDetectCaps,
                            enabled = !detectingCaps && draft.model.isNotBlank(),
                            backdrop = backdrop
                        )
                    }
                    detectResult?.let { (ok, msg) ->
                        Text(
                            msg,
                            color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    modelChoices?.let { list ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .background(
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                    RoundedCornerShape(10.dp)
                                )
                                .padding(vertical = 4.dp)
                        ) {
                            Text(
                                "点击选择（共 ${list.size} 个）：",
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                            )
                            Column(Modifier.heightIn(max = 180.dp)) {
                                list.forEach { id ->
                                    Text(
                                        id,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace,
                                        maxLines = 1,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { onPickModel(id) }
                                            .padding(horizontal = 12.dp, vertical = 6.dp)
                                    )
                                }
                            }
                        }
                    }
                    // API Key 默认掩码显示：防止旁人瞥见或截屏泄露；可切换明文核对
                    var showKey by remember { mutableStateOf(false) }
                    OutlinedTextField(
                        value = draft.apiKeyPlain,
                        onValueChange = { v -> onChange(draft.copy(apiKeyPlain = v)) },
                        label = { Text(if (draft.id == null) "API Key" else "API Key（留空保持不变）") },
                        singleLine = true,
                        visualTransformation =
                            if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            TextButton(onClick = { showKey = !showKey }) {
                                Text(
                                    if (showKey) "隐藏" else "显示",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        },
                        colors = glassFieldColors()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = draft.contextLength,
                            onValueChange = { v -> onChange(draft.copy(contextLength = v.filter { it.isDigit() }.take(8))) },
                            label = { Text("上下文窗口") },
                            placeholder = { Text("自动") },
                            supportingText = { Text("0/留空=按模型推测") },
                            singleLine = true,
                            colors = glassFieldColors(),
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = draft.maxTokens,
                            onValueChange = { v -> onChange(draft.copy(maxTokens = v.filter { it.isDigit() }.take(7))) },
                            label = { Text("回复上限") },
                            placeholder = { Text("默认") },
                            supportingText = { Text("建议 8192+") },
                            singleLine = true,
                            colors = glassFieldColors(),
                            modifier = Modifier.weight(1f)
                        )
                    }
                    if (draftError != null) {
                        Text(
                            draftError,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    testResult?.let { (ok, msg) ->
                        Text(
                            msg,
                            color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    GlassTextButton(
                        text = if (testing) "测试中…" else "测试连接",
                        onClick = onTest,
                        enabled = !testing,
                        backdrop = backdrop
                    )
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    GlassTextButton(text = "取消", onClick = onDismiss, backdrop = backdrop)
                    LiquidPillButton(
                        backdrop = backdrop,
                        text = "保存",
                        emphasized = true
                    ) { onSave() }
                }
            }
        }
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
                                        emphasized = false,
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
                                        emphasized = false,
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
                            strokeCap = androidx.compose.ui.graphics.StrokeCap.Round
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
                    else -> settings.providers.find { it.id == configId }?.name ?: "已失效服务"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text("更换 ›", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
    }
}
