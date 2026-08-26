package com.haoai.agent.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Construction
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import com.haoai.agent.agent.policy.PermissionMode
import com.haoai.agent.data.AppSettings
import com.haoai.agent.platform.KeepAliveService
import com.haoai.agent.platform.llama.LlamaState
import com.haoai.agent.ui.SettingsViewModel
import com.haoai.agent.ui.common.GlassCard
import com.haoai.agent.ui.common.GlassPageBar
import kotlinx.coroutines.launch

/**
 * 设置主页（上游 移动端式分组导航）：
 * 根页面只列分类入口，具体设置项进各子页，避免功能增多后平铺混乱。
 */
@Composable
fun SettingsScreen(
    vm: SettingsViewModel,
    backdrop: com.kyant.backdrop.backdrops.LayerBackdrop,
    onBack: () -> Unit,
    onOpenMemories: () -> Unit = {},
    onOpenSchedules: () -> Unit = {},
    onOpenSkills: () -> Unit = {}
) {
    val context = LocalContext.current
    val settings by vm.settings.collectAsState()
    val draft = vm.draft
    val scope = rememberCoroutineScope()

    var section by rememberSaveable { mutableStateOf("") }

    androidx.activity.compose.BackHandler(enabled = draft == null) {
        if (section.isNotEmpty()) section = "" else onBack()
    }

    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            scope.launch { vm.useSafWorkspace(uri.toString()) }
        }
    }

    val a11yOn = com.haoai.agent.platform.a11y.HaoAccessibilityService.connected()

    if (section.isEmpty()) {
        Box(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            Column(
                Modifier
                    .fillMaxSize()
            ) {
                Spacer(Modifier.height(64.dp))
                LazyColumn(
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
                        subtitle = "${vm.memoryCount()} 条长期记忆" +
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
                        subtitle = "${vm.skillCount()} 个沉淀技能 · 点击管理",
                        tint = Color(0xFFD9539E),
                        onClick = onOpenSkills
                    )
                }
                item {
                    MenuCard(
                        backdrop = backdrop,
                        icon = Icons.Filled.Schedule,
                        title = "定时任务",
                        subtitle = "到点自动执行并通知 · 点击管理",
                        tint = Color(0xFFD9913F),
                        onClick = onOpenSchedules
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
                        icon = Icons.Filled.Info,
                        title = "关于",
                        subtitle = "Token 用量统计 · 版本信息",
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
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
    } else {
        Box(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            Column(
                Modifier
                    .fillMaxSize()
            ) {
                Spacer(Modifier.height(64.dp))
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 40.dp)
                ) {
                    when (section) {
                        "brain" -> {
                            brainItems(vm, settings)
                            localItems(vm)
                        }
                        "privacy" -> privacyItems(vm, settings, context, a11yOn)
                        "memory" -> memoryItems(vm, settings, onOpenMemories, backdrop)
                        "workspace" -> workspaceItems(vm) { treePicker.launch(null) }
                        "general" -> generalItems(vm, settings, context, backdrop)
                        "about" -> aboutItems(vm, settings)
                    }
                }
            }
            GlassPageBar(
                backdrop = backdrop,
                title = sectionTitle(section),
                onBack = { section = "" },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
    }

    draft?.let { d ->
        ProviderDialog(
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
            onSave = { vm.saveDraft() },
            onTest = { vm.testDraftConnection() },
            onDismiss = { vm.cancelDraft() }
        )
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
    "general" -> "通用"
    "about" -> "关于"
    else -> ""
}

// ---------- 各子页 ----------

private fun LazyListScope.brainItems(vm: SettingsViewModel, settings: AppSettings) {
    item { SectionTitle("云端模型服务") }
    item {
        val ps = settings.providers
        val activeId = settings.activeProviderId
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ps.forEach { p ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (activeId != p.id) vm.setActiveProvider(p.id)
                        }
                ) {
                    Row(
                        Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = p.id == activeId,
                            onClick = { vm.setActiveProvider(p.id) }
                        )
                        Column(Modifier.weight(1f)) {
                            Text(p.name, style = MaterialTheme.typography.titleSmall)
                            Text(
                                "${p.model} · ctx ${p.effectiveContextLength() / 1024}K" +
                                    (p.maxTokens.takeIf { it > 0 }?.let { " · max $it" } ?: "") +
                                    "\n${p.baseUrl}",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 3
                            )
                        }
                        IconButton(onClick = { vm.editProvider(p) }) {
                            Icon(Icons.Filled.Edit, contentDescription = "编辑")
                        }
                        IconButton(onClick = { vm.deleteProvider(p.id) }) {
                            Icon(Icons.Filled.Delete, contentDescription = "删除", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
            Button(onClick = { vm.startNewDraft() }, modifier = Modifier.fillMaxWidth()) {
                Text("添加模型服务")
            }
            Text(
                "点击卡片切换当前使用的服务；支持 DeepSeek/OpenRouter/Kimi/智谱/Ollama 预设、测试连接与在线拉取模型列表。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun LazyListScope.localItems(vm: SettingsViewModel) {
    item { SectionTitle("端侧推理（llama.cpp 本地运行）") }
    item {
        val llama by vm.llamaState.collectAsState()
        val dl by vm.llamaDownload.collectAsState()
        var dlUrl by androidx.compose.runtime.remember {
            mutableStateOf(com.haoai.agent.platform.llama.LlamaServerController.DEFAULT_MODEL_URL)
        }
        Column(Modifier.padding(16.dp)) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("llama.cpp 本地推理", style = MaterialTheme.typography.titleSmall)
                            Text(
                                when (val s = llama) {
                                    is LlamaState.Running -> "运行中 · ${s.modelFile}"
                                    is LlamaState.Starting -> s.detail
                                    is LlamaState.Failed -> "失败：${s.message}"
                                    else -> "已停止 · 模型：${vm.llamaModelFile() ?: "未下载"}"
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
                                Button(onClick = { vm.stopLlama() }) { Text("停止服务") }
                            }
                            else -> {
                                Button(onClick = { vm.startLlama { } }, enabled = vm.llamaModelFile() != null) {
                                    Text("启动服务")
                                }
                            }
                        }
                        TextButton(onClick = { vm.useLocalModel() }) { Text("使用端侧模型") }
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
                    textStyle = MaterialTheme.typography.labelSmall
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
                    Button(
                        onClick = { vm.downloadLlamaModel(dlUrl) { } },
                        modifier = Modifier.padding(top = 8.dp)
                    ) { Text("下载模型（Qwen2.5-0.5B，约 470MB）") }
                }
            }
            val ctxLocal = androidx.compose.ui.platform.LocalContext.current
            val importLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument()
            ) { uri -> uri?.let { vm.importModel(ctxLocal, it.toString()) } }
            Button(
                onClick = { importLauncher.launch(arrayOf("*/*")) },
                enabled = vm.importingModel == null,
                modifier = Modifier.padding(top = 8.dp)
            ) { Text("从手机选择模型文件（GGUF / mmproj）") }
            vm.importingModel?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            Text(
                "提示：把视觉投影文件命名为与模型同名（如 qwen-vl.gguf → qwen-vl.mmproj）放入模型目录即可开启图像识别。",
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
            val models = vm.localModels()
            if (models.size > 1) {
                Text(
                    "选择端侧模型（切换后自动重启服务生效）",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 12.dp)
                )
                val selected = vm.selectedLocalModel()
                models.forEach { name ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { vm.selectLocalModel(name) },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = name == selected, onClick = { vm.selectLocalModel(name) })
                        Text(
                            name,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1
                        )
                    }
                }
            }
            Text(
                "上下文窗口（Agent 工具流建议 ≥64K；过大增加内存占用，切换后下次启动生效）",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 12.dp, bottom = 6.dp)
            )
            val ctxOptions = listOf(16384, 32768, 65536, 131072, 262144)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                ctxOptions.forEachIndexed { i, n ->
                    SegmentedButton(
                        selected = vm.localContextLength() == n,
                        onClick = { vm.setLocalContextLength(n) },
                        shape = SegmentedButtonDefaults.itemShape(index = i, count = ctxOptions.size)
                    ) {
                        Text(if (n >= 1024) "${n / 1024}K" else "$n", maxLines = 1)
                    }
                }
            }
        }
    }
}

private fun LazyListScope.privacyItems(
    vm: SettingsViewModel,
    settings: AppSettings,
    context: android.content.Context,
    a11yOn: Boolean
) {
    item { SectionTitle("权限模式") }
    item {
        val mode = settings.permissionMode
        SingleChoiceSegmentedButtonRow(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            SegmentedButton(
                selected = mode == PermissionMode.ALWAYS_ASK,
                onClick = { vm.setPermissionMode(PermissionMode.ALWAYS_ASK) },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 3)
            ) { Text("全部询问", maxLines = 1) }
            SegmentedButton(
                selected = mode == PermissionMode.ASK_WRITES,
                onClick = { vm.setPermissionMode(PermissionMode.ASK_WRITES) },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 3)
            ) { Text("写入时询问", maxLines = 1) }
            SegmentedButton(
                selected = mode == PermissionMode.YOLO,
                onClick = { vm.setPermissionMode(PermissionMode.YOLO) },
                shape = SegmentedButtonDefaults.itemShape(index = 2, count = 3)
            ) { Text("全自动", maxLines = 1) }
        }
    }
    item { SectionTitle("无障碍自动化") }
    item {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
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
            TextButton(onClick = {
                runCatching {
                    context.startActivity(
                        android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }) { Text(if (a11yOn) "管理" else "去开启") }
        }
    }
    item { SectionTitle("系统权限") }
    item {
        androidx.compose.runtime.LaunchedEffect(Unit) { vm.refreshPermissions(context) }
    }
    com.haoai.agent.platform.PermissionCenter.ALL.forEach { spec ->
        item(key = "perm-${spec.key}") {
            val granted = vm.permissionStates[spec.key]
                ?: com.haoai.agent.platform.PermissionCenter.granted(context, spec)
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
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
                    TextButton(onClick = { vm.requestPermission(spec, context) }) {
                        Text(if (spec.special) "去设置" else "授权")
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
    backdrop: com.kyant.backdrop.backdrops.LayerBackdrop
) {
    item { SectionTitle("记忆开关") }
    item {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("记忆系统", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "自动沉淀偏好与背景，回答时注入参考（长期 ${vm.memoryCount()} 条 · 今日日志 ${vm.journalCount()} 条）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            com.haoai.agent.ui.common.LiquidToggle(
                checked = settings.memoryEnabled,
                onCheckedChange = { vm.setMemoryEnabled(it) },
                backdrop = backdrop
            )
        }
    }
    item {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("自动学习", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "每轮对话结束后自动从近期内容提取值得长期记住的信息（gugu 式）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            com.haoai.agent.ui.common.LiquidToggle(
                checked = settings.autoLearn,
                onCheckedChange = { vm.setAutoLearn(it) },
                backdrop = backdrop
            )
        }
    }
    item { SectionTitle("梦境整理") }
    item {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("闲置时自动整理记忆", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "充电且屏幕关闭持续所选时间后自动执行；亮屏或断电即取消。" +
                        "固化历史写入工作区 DREAMS.md，模型不可用时自动回退纯规则。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            com.haoai.agent.ui.common.LiquidToggle(
                checked = settings.deepDream,
                onCheckedChange = { vm.setDeepDream(it) },
                backdrop = backdrop
            )
        }
    }
    item {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(
                "灭屏闲置多久后开始",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp)
            )
            val options = listOf(1 to "1 分钟", 5 to "5 分钟", 15 to "15 分钟", 30 to "30 分钟")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                options.forEachIndexed { i, (m, label) ->
                    SegmentedButton(
                        selected = settings.dreamIdleMinutes == m,
                        onClick = { vm.setDreamIdleMinutes(m) },
                        shape = SegmentedButtonDefaults.itemShape(index = i, count = options.size)
                    ) { Text(label, maxLines = 1) }
                }
            }
        }
    }
    item { SectionTitle("记忆管理模型") }
    item {
        Column(Modifier.padding(horizontal = 20.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { vm.setDreamProvider("local") },
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = settings.dreamProviderId == "local", onClick = { vm.setDreamProvider("local") })
                Column(Modifier.padding(start = 4.dp)) {
                    Text("端侧小模型（推荐，零 token 消耗）", style = MaterialTheme.typography.bodySmall)
                    Text(
                        "llama.cpp 本地运行 · ${vm.llamaModelFile() ?: "未下载"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            settings.providers.filter { it.id != "local" }.forEach { p ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { vm.setDreamProvider(p.id) },
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
    item { SectionTitle("记忆库") }
    item {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenMemories() }
            ) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("近期动态与长期记忆", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "查看 · 整理 · 固化 · 清理",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(
                "固化规则：每日日志中重要性 ≥4 的条目夜间自动晋升为长期记忆；日志保留 7 天后清理。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

private fun LazyListScope.workspaceItems(vm: SettingsViewModel, pickFolder: () -> Unit) {
    item {
        Column(Modifier.padding(16.dp)) {
            Text(
                "当前：${vm.workspaceName()}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.padding(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = pickFolder) { Text("选择文件夹 (SAF)") }
                TextButton(onClick = { vm.useDefaultWorkspace() }) { Text("恢复默认目录") }
            }
            Text(
                "SAF 目录下 bash 不可用；默认目录（应用专属）支持完整文件与 shell 能力。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}

private fun LazyListScope.generalItems(
    vm: SettingsViewModel,
    settings: AppSettings,
    context: android.content.Context,
    backdrop: com.kyant.backdrop.backdrops.LayerBackdrop
) {
    item { SectionTitle("外观") }
    item {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text("主题模式", style = MaterialTheme.typography.bodyMedium)
            SingleChoiceSegmentedButtonRow(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            ) {
                SegmentedButton(
                    selected = settings.themeMode == "system",
                    onClick = { vm.setThemeMode("system") },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 3)
                ) { Text("跟随系统", maxLines = 1) }
                SegmentedButton(
                    selected = settings.themeMode == "light",
                    onClick = { vm.setThemeMode("light") },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 3)
                ) { Text("浅色", maxLines = 1) }
                SegmentedButton(
                    selected = settings.themeMode == "dark",
                    onClick = { vm.setThemeMode("dark") },
                    shape = SegmentedButtonDefaults.itemShape(index = 2, count = 3)
                ) { Text("深色", maxLines = 1) }
            }
            var wpVersion by androidx.compose.runtime.remember {
                mutableStateOf(vm.wallpaperSet(context))
            }
            var confirmWpClear by androidx.compose.runtime.remember { mutableStateOf(false) }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp),
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
                        wpVersion = vm.wallpaperSet(context)
                    }
                }
                TextButton(onClick = { wpPicker.launch("image/*") }) { Text("选择图片") }
                if (wpVersion) {
                    TextButton(onClick = { confirmWpClear = true }) { Text("清除") }
                }
            }
            if (confirmWpClear) {
                AlertDialog(
                    onDismissRequest = { confirmWpClear = false },
                    title = { Text("恢复默认背景") },
                    text = { Text("将清除自定义壁纸并恢复默认渐变背景。") },
                    confirmButton = {
                        TextButton(onClick = {
                            vm.clearWallpaper(context)
                            wpVersion = false
                            confirmWpClear = false
                        }) { Text("清除", color = MaterialTheme.colorScheme.error) }
                    },
                    dismissButton = {
                        TextButton(onClick = { confirmWpClear = false }) { Text("取消") }
                    }
                )
            }
        }
    }
    item { SectionTitle("模型行为") }
    item {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text("思考等级（reasoning effort，仅支持的云服务生效）", style = MaterialTheme.typography.bodyMedium)
            SingleChoiceSegmentedButtonRow(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            ) {
                SegmentedButton(
                    selected = settings.reasoningEffort.isBlank(),
                    onClick = { vm.setReasoningEffort("") },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 4)
                ) { Text("默认", maxLines = 1) }
                SegmentedButton(
                    selected = settings.reasoningEffort == "low",
                    onClick = { vm.setReasoningEffort("low") },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 4)
                ) { Text("低", maxLines = 1) }
                SegmentedButton(
                    selected = settings.reasoningEffort == "medium",
                    onClick = { vm.setReasoningEffort("medium") },
                    shape = SegmentedButtonDefaults.itemShape(index = 2, count = 4)
                ) { Text("中", maxLines = 1) }
                SegmentedButton(
                    selected = settings.reasoningEffort == "high",
                    onClick = { vm.setReasoningEffort("high") },
                    shape = SegmentedButtonDefaults.itemShape(index = 3, count = 4)
                ) { Text("高", maxLines = 1) }
            }
        }
    }
    item { SectionTitle("后台") }
    item {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("后台保活", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "以前台服务保持任务在后台继续运行",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            com.haoai.agent.ui.common.LiquidToggle(
                checked = settings.keepAlive,
                onCheckedChange = { enabled ->
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
            maxLines = 6
        )
    }
}

private fun LazyListScope.aboutItems(vm: SettingsViewModel, settings: AppSettings) {
    item { SectionTitle("用量统计") }
    item {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Token 用量（云端累计）", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "输入 ${settings.tokenInTotal} · 输出 ${settings.tokenOutTotal} tokens",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
    item {
        HorizontalDivider(Modifier.padding(vertical = 18.dp, horizontal = 16.dp))
        val ctx = androidx.compose.ui.platform.LocalContext.current
        val ver = runCatching {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
        }.getOrNull() ?: "dev"
        Text(
            "HaoAI v$ver · 云端大脑 + 端侧肌肉\n三层记忆 · 技能自进化 · 手机自动化 · 定时任务",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp)
        )
    }
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

@Composable
private fun ProviderDialog(
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
    onSave: () -> Unit,
    onTest: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (draft.id == null) "添加模型服务" else "编辑模型服务") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    com.haoai.agent.ui.ProviderPresets.all.take(3).forEach { p ->
                        AssistChip(onClick = { onPreset(p) }, label = { Text(p.label, style = MaterialTheme.typography.labelSmall) })
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    com.haoai.agent.ui.ProviderPresets.all.drop(3).forEach { p ->
                        AssistChip(onClick = { onPreset(p) }, label = { Text(p.label, style = MaterialTheme.typography.labelSmall) })
                    }
                }
                OutlinedTextField(
                    value = draft.name,
                    onValueChange = { v -> onChange(draft.copy(name = v)) },
                    label = { Text("名称（可留空自动命名）") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = draft.baseUrl,
                    onValueChange = { v -> onChange(draft.copy(baseUrl = v)) },
                    label = { Text("Base URL（OpenAI 兼容）") },
                    placeholder = { Text("https://openrouter.ai/api/v1") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = draft.model,
                    onValueChange = { v -> onChange(draft.copy(model = v)) },
                    label = { Text("模型 ID") },
                    placeholder = { Text("如 deepseek-chat / stealth/ox-alpha") },
                    singleLine = true
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    TextButton(onClick = onFetchModels, enabled = !fetchingModels && draft.baseUrl.isNotBlank()) {
                        Text(if (fetchingModels) "拉取中…" else "从服务拉取模型列表")
                    }
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
                OutlinedTextField(
                    value = draft.apiKeyPlain,
                    onValueChange = { v -> onChange(draft.copy(apiKeyPlain = v)) },
                    label = { Text(if (draft.id == null) "API Key" else "API Key（留空保持不变）") },
                    singleLine = true
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = draft.contextLength,
                        onValueChange = { v -> onChange(draft.copy(contextLength = v.filter { it.isDigit() }.take(8))) },
                        label = { Text("上下文窗口") },
                        placeholder = { Text("自动") },
                        supportingText = { Text("0/留空=按模型推测") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = draft.maxTokens,
                        onValueChange = { v -> onChange(draft.copy(maxTokens = v.filter { it.isDigit() }.take(7))) },
                        label = { Text("回复上限") },
                        placeholder = { Text("默认") },
                        supportingText = { Text("建议 8192+") },
                        singleLine = true,
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
                TextButton(onClick = onTest, enabled = !testing) {
                    Text(if (testing) "测试中…" else "测试连接")
                }
            }
        },
        confirmButton = { Button(onClick = onSave) { Text("保存") } },
        dismissButton = { TextButton(onClick = { onDismiss() }) { Text("取消") } }
    )
}
