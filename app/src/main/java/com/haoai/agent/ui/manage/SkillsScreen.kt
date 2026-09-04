package com.haoai.agent.ui.manage

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.haoai.agent.agent.skills.SkillImporter
import com.haoai.agent.agent.skills.SkillStore
import com.haoai.agent.ui.common.GlassCard
import com.haoai.agent.ui.common.GlassPageBar
import kotlinx.coroutines.launch

/**
 * 技能库管理：查看/手动添加/删除 SKILL.md（skill 工具的图形入口）。
 */
@Composable
fun SkillsScreen(backdrop: com.kyant.backdrop.backdrops.LayerBackdrop, onBack: () -> Unit) {
    val store = SkillStore
    val context = androidx.compose.ui.platform.LocalContext.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var skills by remember { mutableStateOf(store.list()) }
    var viewBody by remember { mutableStateOf<Pair<String, String>?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    // 待删除技能名：删除不可逆，先确认
    var pendingDelete by remember { mutableStateOf<String?>(null) }
    val fmt = remember { java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA) }

    // 2.2 导入：通道菜单 / URL 输入 / 剪贴板粘贴 / 批量结果
    var showImportMenu by remember { mutableStateOf(false) }
    var showUrlImport by remember { mutableStateOf(false) }
    var showPasteImport by remember { mutableStateOf(false) }
    var importResult by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }
    // 导出：待写出的技能 (名, 全文)，由 CreateDocument launcher 接收目标 uri
    var pendingExport by remember { mutableStateOf<Pair<String, String>?>(null) }
    // 导入下载用客户端（复用 NetGuard 明文拦截）
    val importHttp = remember {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .addInterceptor(com.haoai.agent.platform.NetGuard.interceptor())
            .build()
    }

    val treePicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            scope.launch {
                importing = true
                val docs = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    SkillImporter.scanSafTree(context, uri)
                }
                importing = false
                if (docs == null) {
                    importResult = "无法读取所选目录"
                } else if (docs.isEmpty()) {
                    importResult = "所选目录（含一级子目录）未发现 SKILL.md"
                } else {
                    importResult = SkillImporter.importBatch(docs, "import_local").summary()
                }
                skills = store.list()
            }
        }
    }

    val exportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/markdown")
    ) { uri ->
        val (name, text) = pendingExport ?: return@rememberLauncherForActivityResult
        if (uri != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { os ->
                    os.write(text.toByteArray(Charsets.UTF_8))
                }
                android.widget.Toast.makeText(context, "已导出 $name", android.widget.Toast.LENGTH_SHORT).show()
            }.onFailure {
                android.widget.Toast.makeText(context, "导出失败：${it.message}", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        pendingExport = null
    }

    // 系统返回手势：回到设置根页，而不是把应用最小化
    androidx.activity.compose.BackHandler { onBack() }

    fun refresh() {
        skills = store.list()
    }

    Box(
        Modifier
            .fillMaxSize()
            // 平移转场页面必须有实底：否则转场中卡片缝隙透空黑（同设置页修复）
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            Spacer(Modifier.height(56.dp))
            Text(
                "代理在完成任务时用 skill 工具沉淀的可复用经验；系统提示词只带索引，正文按需加载。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp)
            )
            androidx.compose.foundation.layout.Row(
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)
            ) {
                com.haoai.agent.ui.common.LiquidGlassButton(
                    onClick = { showAdd = true },
                    backdrop = backdrop,
                    shape = RoundedCornerShape(percent = 50),
                    surfaceColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 9.dp)
                    ) {
                        Text(
                            "＋",
                            color = MaterialTheme.colorScheme.onPrimary,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.padding(2.dp))
                        Text(
                            "手动添加技能",
                            color = MaterialTheme.colorScheme.onPrimary,
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
                com.haoai.agent.ui.common.LiquidGlassButton(
                    onClick = { showImportMenu = true },
                    backdrop = backdrop,
                    shape = RoundedCornerShape(percent = 50),
                    enabled = !importing,
                    surfaceColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.85f)
                ) {
                    Text(
                        if (importing) "导入中…" else "导入技能",
                        color = MaterialTheme.colorScheme.onTertiary,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 9.dp)
                    )
                }
            }
            if (skills.isEmpty()) {
                Text(
                    "暂无技能——当代理踩坑并总结出可复用步骤时会自动沉淀到这里，也可以手动添加。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp)
                )
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(skills, key = { it.name }) { s ->
                    GlassCard(
                        onClick = {},
                        backdrop = backdrop,
                        shape = RoundedCornerShape(16.dp),
                        surfaceAlpha = if (s.archived) 0.10f else 0.22f,
                        lensRadius = 16.dp,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(Modifier.padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 4.dp)) {
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(s.name, style = MaterialTheme.typography.titleSmall)
                                    Spacer(Modifier.padding(2.dp))
                                    if (s.archived) {
                                        Text(
                                            "已归档 · 闲置90天",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier
                                                .padding(start = 6.dp)
                                                .background(
                                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.14f),
                                                    RoundedCornerShape(6.dp)
                                                )
                                                .padding(horizontal = 6.dp, vertical = 1.dp)
                                        )
                                    }
                                    if (s.pinned) {
                                        Text(
                                            "置顶",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.padding(start = 6.dp)
                                        )
                                    }
                                }
                                Text(
                                    s.description.ifBlank { "（无描述）" },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2
                                )
                                if (!s.lastUsedResult.isNullOrBlank()) {
                                    val failed = s.lastUsedResult.startsWith("failed")
                                    Text(
                                        if (failed) "⚠ 上次使用失败" else "✓ 上次使用成功",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(top = 2.dp)
                                    )
                                }
                                Text(
                                    "使用 ${s.useCount} 次 · 最近 ${if (s.lastUsedAt > 0) fmt.format(java.util.Date(s.lastUsedAt)) else "从未"}" +
                                        " · 来源 " + when {
                                        s.source == "agent" -> "自进化"
                                        s.source == "import_local" -> "导入（本地文件夹）"
                                        s.source == "import_url" -> "导入（URL）"
                                        s.source == "import_clipboard" -> "导入（剪贴板）"
                                        else -> "手动"
                                    } + if (s.importedAt > 0) " · ${fmt.format(java.util.Date(s.importedAt))}" else "",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Column {
                                TextButton(onClick = {
                                    store.setPinned(s.name, !s.pinned)
                                    refresh()
                                }) { Text(if (s.pinned) "取消置顶" else "置顶") }
                                TextButton(onClick = {
                                    viewBody = s.name to (store.view(s.name) ?: "")
                                }) { Text("查看") }
                                TextButton(onClick = {
                                    val text = runCatching {
                                        val d = java.io.File(
                                            context.filesDir, "skills/${s.name}/SKILL.md"
                                        )
                                        d.readText()
                                    }.getOrDefault("")
                                    pendingExport = s.name to text
                                    exportLauncher.launch("${s.name}.md")
                                }) { Text("导出") }
                                IconButton(onClick = { pendingDelete = s.name }) {
                                    Icon(
                                        Icons.Filled.Delete,
                                        contentDescription = "删除",
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        GlassPageBar(
            backdrop = backdrop,
            title = "技能库（${skills.size}）",
            onBack = onBack,
            modifier = Modifier
                .align(Alignment.TopCenter)
        )
    }

    viewBody?.let { (name, body) ->
        com.haoai.agent.ui.common.GlassAlertDialog(
            backdrop = backdrop,
            title = name,
            onDismiss = { viewBody = null },
            confirmLabel = "关闭",
            onConfirm = { viewBody = null }
        ) {
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace
            )
        }
    }

    if (showAdd) {
        var name by remember { mutableStateOf("") }
        var desc by remember { mutableStateOf("") }
        var body by remember { mutableStateOf("") }
        val valid = name.isNotBlank() && desc.isNotBlank() && body.isNotBlank()
        com.haoai.agent.ui.common.GlassAlertDialog(
            backdrop = backdrop,
            title = "添加技能",
            onDismiss = { showAdd = false },
            confirmLabel = "保存",
            onConfirm = {
                store.save(name, desc, body)
                refresh()
                showAdd = false
            },
            confirmEnabled = valid,
            dismissLabel = "取消"
        ) {
            androidx.compose.foundation.layout.Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                androidx.compose.material3.OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称（如 deepseek-api）") },
                    singleLine = true,
                    colors = com.haoai.agent.ui.common.glassFieldColors()
                )
                androidx.compose.material3.OutlinedTextField(
                    value = desc,
                    onValueChange = { desc = it },
                    label = { Text("一句话描述（注入系统提示索引）") },
                    singleLine = true,
                    colors = com.haoai.agent.ui.common.glassFieldColors()
                )
                androidx.compose.material3.OutlinedTextField(
                    value = body,
                    onValueChange = { body = it },
                    label = { Text("正文：步骤/要点（Markdown，按需加载）") },
                    minLines = 4,
                    maxLines = 8,
                    colors = com.haoai.agent.ui.common.glassFieldColors()
                )
            }
        }
    }

    pendingDelete?.let { name ->
        com.haoai.agent.ui.common.GlassAlertDialog(
            backdrop = backdrop,
            title = "删除技能",
            onDismiss = { pendingDelete = null },
            confirmLabel = "删除",
            danger = true,
            onConfirm = {
                store.delete(name)
                refresh()
                pendingDelete = null
            }
        ) {
            Text(
                "技能「$name」将被删除，此操作不可恢复。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
        }
    }

    // ---------- 2.2 导入弹窗组 ----------

    if (showImportMenu) {
        com.haoai.agent.ui.common.GlassAlertDialog(
            backdrop = backdrop,
            title = "导入技能",
            onDismiss = { showImportMenu = false },
            dismissLabel = "取消",
            contentMaxHeight = 360.dp
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "兼容 agentskills.io 规范的 SKILL.md（frontmatter 的 name/description 字段；其他字段忽略）。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                ImportOptionRow("从本地文件夹", "扫描所选目录及一级子目录中的 SKILL.md") {
                    showImportMenu = false
                    treePicker.launch(null)
                }
                ImportOptionRow("从 URL 下载", ".md 或 .zip 直链（≤50MB，走安全拦截）") {
                    showImportMenu = false
                    showUrlImport = true
                }
                ImportOptionRow("从剪贴板粘贴", "粘贴 SKILL.md 全文") {
                    showImportMenu = false
                    showPasteImport = true
                }
            }
        }
    }

    if (showUrlImport) {
        var url by remember { mutableStateOf("") }
        com.haoai.agent.ui.common.GlassAlertDialog(
            backdrop = backdrop,
            title = "从 URL 导入",
            onDismiss = { showUrlImport = false },
            confirmLabel = if (importing) "下载中…" else "下载并导入",
            confirmEnabled = url.isNotBlank() && !importing,
            dismissLabel = "取消",
            onConfirm = {
                scope.launch {
                    importing = true
                    val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        SkillImporter.downloadAndScan(importHttp, url).fold(
                            { docs ->
                                if (docs.isEmpty()) "下载内容中没有 SKILL.md"
                                else SkillImporter.importBatch(docs, "import_url").summary()
                            },
                            { "下载失败：${it.message}" }
                        )
                    }
                    importing = false
                    showUrlImport = false
                    importResult = r
                    skills = store.list()
                }
            }
        ) {
            androidx.compose.material3.OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("https://…/SKILL.md 或 .zip") },
                colors = com.haoai.agent.ui.common.glassFieldColors()
            )
        }
    }

    if (showPasteImport) {
        var text by remember { mutableStateOf(clipboard.getText()?.text ?: "") }
        var overwrite by remember { mutableStateOf(false) }
        val parsed = remember(text) { SkillStore.parseDoc(text) }
        val conflict = remember(text) {
            val n = SkillStore.sanitizeName(parsed.name ?: "")
            parsed.name != null && SkillStore.exists(n)
        }
        com.haoai.agent.ui.common.GlassAlertDialog(
            backdrop = backdrop,
            title = "从剪贴板导入",
            onDismiss = { showPasteImport = false },
            confirmLabel = "导入",
            confirmEnabled = text.isNotBlank(),
            dismissLabel = "取消",
            onConfirm = {
                val finalName = SkillStore.sanitizeName(parsed.name ?: (clipboard.getText()?.text?.lineSequence()?.firstOrNull() ?: "imported"))
                if (SkillStore.exists(finalName) && !overwrite) {
                    // 冲突：首次点击时提示勾选覆盖（保持弹窗打开）
                    overwrite = true
                } else {
                    when (val r = SkillStore.importDoc(text, "import_clipboard", overwrite = overwrite)) {
                        is SkillStore.ImportOutcome.Done -> {
                            importResult = "已导入技能「${r.name}」" + if (overwrite && conflict) "（覆盖原技能）" else ""
                            showPasteImport = false
                            skills = store.list()
                        }
                        is SkillStore.ImportOutcome.Conflict -> overwrite = true
                        is SkillStore.ImportOutcome.Failed -> importResult = "导入失败：${r.message}".also { showPasteImport = false }
                    }
                }
            }
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (conflict && overwrite) {
                    Text(
                        "技能「${SkillStore.sanitizeName(parsed.name ?: "")}」已存在，再次点击将覆盖它。",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                androidx.compose.material3.OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 6,
                    maxLines = 12,
                    label = { Text("SKILL.md 全文") },
                    colors = com.haoai.agent.ui.common.glassFieldColors()
                )
                if (text.isNotBlank()) {
                    Text(
                        when {
                            parsed.error != null -> "⚠ ${parsed.error}"
                            parsed.name != null -> "识别为技能「${parsed.name}」"
                            else -> "⚠ 未识别到 frontmatter 的 name，将以正文首行或默认名导入"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (parsed.error != null) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    importResult?.let { msg ->
        com.haoai.agent.ui.common.GlassAlertDialog(
            backdrop = backdrop,
            title = "导入结果",
            onDismiss = { importResult = null },
            confirmLabel = "好的",
            onConfirm = { importResult = null }
        ) {
            Text(msg, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ImportOptionRow(title: String, subtitle: String, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                RoundedCornerShape(12.dp)
            )
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
