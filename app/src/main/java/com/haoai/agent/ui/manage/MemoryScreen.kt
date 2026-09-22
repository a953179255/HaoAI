package com.haoai.agent.ui.manage

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.haoai.agent.HaoApplication
import com.haoai.agent.agent.memory.JournalDay
import com.haoai.agent.agent.memory.Memory
import com.haoai.agent.agent.memory.MemoryConsolidation
import com.haoai.agent.ui.common.GlassCard
import com.haoai.agent.ui.common.GlassPageBar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import com.haoai.agent.ui.theme.HaoDimens
import com.haoai.agent.ui.common.appLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.luminance

@Composable
fun MemoryScreen(
    backdrop: com.kyant.backdrop.backdrops.LayerBackdrop,
    onBack: () -> Unit,
    /** 全局壁纸开时传入：页面自带对齐的壁纸底 */
    wallpaper: android.graphics.Bitmap? = null
) {
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as HaoApplication
    val vm: MemoryViewModel = viewModel(factory = viewModelFactory {
        initializer { MemoryViewModel(app.container) }
    })
    var confirmClear by remember { mutableStateOf(false) }
    var confirmClearJournal by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    var clearCountdown by remember { mutableIntStateOf(0) }
    var pendingDelete by remember { mutableStateOf<Memory?>(null) }

    // 系统返回手势：回到设置根页，而不是把应用最小化
    androidx.activity.compose.BackHandler { onBack() }
    val fmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) vm.exportTo(uri) { msg = it }
    }
    val exportName = remember {
        "haoai-memory-" + SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date()) + ".zip"
    }

    val prefN = vm.items.count { it.type == "preference" }
    val factN = vm.items.count { it.type == "fact" }
    val decisionN = vm.items.count { it.type == "decision" }
    val eventN = vm.items.count { it.type == "event" }

    Box(
        Modifier
            .fillMaxSize()
            // 平移转场页面必须有实底：否则转场中卡片缝隙透空黑；
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
                val wpImage = androidx.compose.runtime.remember(wallpaper) { wallpaper.asImageBitmap() }
                Image(
                    bitmap = wpImage,
                    contentDescription = null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.matchParentSize()
                )
                    // 壁纸压暗层：卡片之外直接压着壁纸的内容靠它恢复对比度
                    com.haoai.agent.ui.common.HaoWallpaperScrim(wallpaper != null, Modifier.matchParentSize())
            }
        }
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            Spacer(Modifier.height(56.dp))
            com.haoai.agent.ui.common.HaoNote(
                backdrop = localBackdrop,
                text = "近期动态（每日日志，7 天后过期，重要条目夜间固化晋升）+ 长期记忆（按重要性注入）。",
                modifier = Modifier.padding(vertical = 4.dp)
            )
            Text(
                "概览：偏好 $prefN · 事实 $factN · 决定 $decisionN · 事件 $eventN · 今日日志 ${vm.days.firstOrNull()?.items?.size ?: 0} 条",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp)
            )
            msg?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp)
                )
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item(key = "dashboard") {
                    MemoryDashboardCard(vm, fmt, backdrop, onDeleteRequest = { pendingDelete = it })
                }
                if (vm.days.isNotEmpty()) {
                    item(key = "journal_header") {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "近期动态 · ${vm.journalCount()} 条",
                                style = MaterialTheme.typography.titleMedium
                            )
                            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                            TextButton(onClick = { confirmClearJournal = true }) {
                                Text("清空", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                    items(vm.days, key = { "j_${it.date}" }) { day ->
                        JournalDayCard(day, backdrop)
                    }
                }
                item(key = "lt_header") {
                    Text(
                        if (vm.items.isEmpty() && vm.days.isEmpty()) "暂无记忆" else "长期记忆",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                items(vm.items, key = { it.id }) { m ->
                    MemoryCard(m, fmt, onDeleteRequest = { pendingDelete = it }, backdrop)
                }
            }
        }
        GlassPageBar(
            backdrop = localBackdrop,
            title = "记忆库（${vm.items.size}）",
            onBack = onBack,
            modifier = Modifier
                .align(Alignment.TopCenter),
            actions = {
                IconButton(onClick = { showAdd = true }) {
                    Icon(Icons.Filled.Add, contentDescription = "手动添加记忆")
                }
                IconButton(onClick = { exportLauncher.launch(exportName) }) {
                    Icon(Icons.Filled.Archive, contentDescription = "导出记忆备份（zip）")
                }
                IconButton(onClick = {
                    if (!vm.consolidating) vm.consolidate { msg = it }
                }) {
                    Icon(Icons.Filled.AutoFixHigh, contentDescription = "固化记忆（AI自动整理重要信息）")
                }
                if (vm.items.isNotEmpty()) {
                    IconButton(onClick = {
                        val n = vm.tidy()
                        msg = if (n > 0) "已整理：合并/清理 $n 条冗余记忆" else "很干净，无需整理"
                    }) {
                        Icon(Icons.Filled.Build, contentDescription = "整理冗余记忆")
                    }
                    IconButton(onClick = { confirmClear = true }) {
                        Icon(Icons.Filled.DeleteSweep, contentDescription = "清空所有长期记忆")
                    }
                }
            }
        )
    }

    if (confirmClear) {
        LaunchedEffect(confirmClear) {
            clearCountdown = 5
            while (clearCountdown > 0) {
                delay(1000)
                clearCountdown--
            }
        }
        com.haoai.agent.ui.common.GlassAlertDialog(
            backdrop = backdrop,
            title = "清空记忆库",
            onDismiss = { confirmClear = false },
            confirmLabel = if (clearCountdown > 0) "${clearCountdown}秒后可清空" else "清空",
            onConfirm = {
                if (clearCountdown <= 0) {
                    vm.clearAll()
                    confirmClear = false
                }
            },
            confirmEnabled = clearCountdown <= 0,
            dismissLabel = "取消"
        ) {
            Text("将删除全部 ${vm.items.size} 条长期记忆，此操作不可恢复。")
        }
    }
    if (confirmClearJournal) {
        var journalCountdown by remember { mutableIntStateOf(0) }
        LaunchedEffect(confirmClearJournal) {
            journalCountdown = 5
            while (journalCountdown > 0) {
                delay(1000)
                journalCountdown--
            }
        }
        com.haoai.agent.ui.common.GlassAlertDialog(
            backdrop = backdrop,
            title = "清空每日日志",
            onDismiss = { confirmClearJournal = false },
            confirmLabel = if (journalCountdown > 0) "${journalCountdown}秒后可清空" else "清空",
            onConfirm = {
                if (journalCountdown <= 0) {
                    vm.clearJournal()
                    confirmClearJournal = false
                }
            },
            confirmEnabled = journalCountdown <= 0,
            dismissLabel = "取消"
        ) {
            Text("将删除全部 ${vm.journalCount()} 条近期动态（不影响长期记忆），此操作不可恢复。")
        }
    }
    if (showAdd) {
        ManualAddDialog(
            backdrop = backdrop,
            onAdd = { content, type, importance ->
                vm.addManual(content, type, importance)
                showAdd = false
                msg = "已手动添加长期记忆"
            },
            onDismiss = { showAdd = false }
        )
    }
    pendingDelete?.let { m ->
        com.haoai.agent.ui.common.GlassAlertDialog(
            backdrop = backdrop,
            title = "删除记忆",
            onDismiss = { pendingDelete = null },
            confirmLabel = "删除",
            onConfirm = {
                vm.delete(m.id)
                pendingDelete = null
            },
            dismissLabel = "取消"
        ) {
            Text("确认删除此条记忆？此操作不可恢复。")
        }
    }
}

@Composable
private fun MemoryDashboardCard(
    vm: MemoryViewModel,
    fmt: SimpleDateFormat,
    backdrop: com.kyant.backdrop.backdrops.LayerBackdrop,
    onDeleteRequest: (Memory) -> Unit
) {
    GlassCard(
        onClick = {},
        backdrop = backdrop,
        shape = RoundedCornerShape(16.dp),
        surfaceAlpha = com.haoai.agent.ui.theme.haoCardSurfaceAlpha(),
        lensRadius = 16.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("记忆健康度", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.weight(1f))
                Text(
                    "${vm.items.size}/${vm.capacity} 条",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            LinearProgressIndicator(
                progress = {
                    if (vm.capacity > 0) (vm.items.size.toFloat() / vm.capacity).coerceIn(0f, 1f) else 0f
                },
                modifier = Modifier.fillMaxWidth(),
                // M3 1.4 默认轨道末端 stopIndicator 小圆，与 Linux 下载条一并关闭
                gapSize = 0.dp,
                drawStopIndicator = {}
            )
            com.haoai.agent.ui.common.HaoNote(
                backdrop = backdrop,
                text = "上次固化：" + if (vm.lastConsolidationAt > 0) {
                    fmt.format(Date(vm.lastConsolidationAt)) +
                    (vm.lastConsolidationReport.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
                    } else "尚未固化（可点顶栏 ✨ 手动触发，或夜间充电灭屏自动执行）",
                modifier = Modifier.padding(vertical = 4.dp)
            )
            com.haoai.agent.ui.common.HaoNote(
                backdrop = backdrop,
                text = "上次备份：" + if (vm.lastBackupAt > 0) fmt.format(Date(vm.lastBackupAt)) + "（自动，内部 backups/）"
                    else "尚无（固化后自动创建；顶栏 ⬇ 可手动导出 zip）",
                modifier = Modifier.padding(vertical = 4.dp)
            )
            if (vm.promotionCandidates > 0) {
                Text(
                    "待固化：${vm.promotionCandidates} 条重要动态（★）下次固化时晋升长期记忆",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            val oldest = vm.oldestUnused()
            if (oldest.isNotEmpty()) {
                Text(
                    "最久未使用（可清理）：",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                oldest.forEach { m ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "(${m.id}) ${m.content.take(40)}" +
                                " · " + (if (m.lastUsedAt > 0) "上次使用 " else "记录于 ") +
                                SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
                                    .format(Date(if (m.lastUsedAt > 0) m.lastUsedAt else m.createdAt)),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { onDeleteRequest(m) }) {
                            Icon(
                                Icons.Filled.DeleteOutline,
                                contentDescription = "删除该记忆",
                                modifier = Modifier.height(16.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ManualAddDialog(
    backdrop: com.kyant.backdrop.backdrops.LayerBackdrop,
    onAdd: (String, String, Int) -> Unit,
    onDismiss: () -> Unit
) {
    var content by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("fact") }
    var importance by remember { mutableStateOf(3) }
    val types = listOf("preference" to "偏好", "fact" to "事实", "decision" to "决定", "event" to "事件")
    com.haoai.agent.ui.common.GlassAlertDialog(
        backdrop = backdrop,
        title = "手动添加长期记忆",
        onDismiss = onDismiss,
        confirmLabel = "添加",
        onConfirm = { onAdd(content, type, importance) },
        confirmEnabled = content.isNotBlank(),
        dismissLabel = "取消"
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            androidx.compose.material3.OutlinedTextField(
                value = content,
                onValueChange = { content = it },
                label = { Text("内容（一句话）") },
                minLines = 2,
                colors = com.haoai.agent.ui.common.glassFieldColors(),
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                types.forEach { (t, label) ->
                    val selected = type == t
                    androidx.compose.material3.FilterChip(
                        selected = selected,
                        onClick = { type = t },
                        label = {
                            Text(
                                label,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f)
                            )
                        },
                        colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
                            containerColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.06f),
                            selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
                            selectedLabelColor = MaterialTheme.colorScheme.primary
                        )
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("重要度：$importance", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { if (importance > 1) importance-- }) {
                    Icon(Icons.Filled.Remove, contentDescription = "降低")
                }
                IconButton(onClick = { if (importance < 5) importance++ }) {
                    Icon(Icons.Filled.Add, contentDescription = "提高")
                }
            }
        }
    }
}

@Composable
private fun JournalDayCard(day: JournalDay, backdrop: com.kyant.backdrop.backdrops.LayerBackdrop) {
    GlassCard(
        onClick = {},
        backdrop = backdrop,
        shape = RoundedCornerShape(16.dp),
        surfaceAlpha = com.haoai.agent.ui.theme.haoCardSurfaceAlpha(),
        lensRadius = 16.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(day.date, style = MaterialTheme.typography.labelLarge)
            day.items.asReversed().forEach { e ->
                Column(Modifier.padding(top = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (e.importance >= MemoryConsolidation.PROMOTE_THRESHOLD) {
                            Text(
                                "★ ",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Text(e.content, style = MaterialTheme.typography.bodySmall)
                    }
                    Text(
                        "${e.source} · ${SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(e.createdAt))}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 2.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun MemoryCard(m: Memory, fmt: SimpleDateFormat, onDeleteRequest: (Memory) -> Unit, backdrop: com.kyant.backdrop.backdrops.LayerBackdrop) {
    GlassCard(
        onClick = {},
        backdrop = backdrop,
        shape = RoundedCornerShape(16.dp),
        surfaceAlpha = com.haoai.agent.ui.theme.haoCardSurfaceAlpha(),
        lensRadius = 16.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 4.dp)) {
            Column(Modifier.weight(1f)) {
                Text(m.content, style = MaterialTheme.typography.bodyMedium)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 6.dp)
                ) {
                    Text(
                        "#${m.id}",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary
                    )
                    m.tags.forEach { tag ->
                        Text(
                            tag,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        fmt.format(Date(m.createdAt)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            IconButton(onClick = { onDeleteRequest(m) }) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "删除",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
