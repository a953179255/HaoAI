package com.haoai.agent.ui.sessions

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.haoai.agent.data.StoredSession
import com.haoai.agent.ui.ChatViewModel
import com.haoai.agent.ui.common.GlassAlertDialog
import com.haoai.agent.ui.common.GlassCard
import com.haoai.agent.ui.common.GlassPageBar
import com.haoai.agent.ui.common.SwipeRevealCard
import com.haoai.agent.ui.common.glassFieldColors
import com.kyant.backdrop.backdrops.LayerBackdrop
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import kotlin.math.abs
import java.util.Date
import java.util.Locale
import com.haoai.agent.ui.common.appLayer

/** 独立「全部会话」页：搜索过滤（会话/回收站共用）+ 左右滑动切换 + 置顶/重命名/回收站管理。 */
@Composable
fun SessionsScreen(
    vm: ChatViewModel,
    backdrop: LayerBackdrop,
    onBack: () -> Unit,
    /** 全局壁纸开时传入：页面自带对齐的壁纸底（与其他二级页一致） */
    wallpaper: android.graphics.Bitmap? = null
) {
    val sessions by vm.sessions.collectAsState()
    val deletedSessions by vm.deletedSessions.collectAsState()
    val activeId = vm.session.collectAsState().value?.id

    var query by rememberSaveable { mutableStateOf("") }
    var showTrash by rememberSaveable { mutableStateOf(false) }
    val fmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
    // 待彻底删除的会话：误触不可逆操作前先确认
    var pendingPurge by remember { mutableStateOf<StoredSession?>(null) }
    // 重命名目标；清空回收站确认
    var renameTarget by remember { mutableStateOf<StoredSession?>(null) }
    var confirmEmptyTrash by remember { mutableStateOf(false) }
    // 当前滑出操作按钮的会话（同时只允许一张）
    var openCardId by remember { mutableStateOf<String?>(null) }
    // 5.4 本会话用量弹窗目标
    var usageTarget by remember { mutableStateOf<StoredSession?>(null) }

    // 系统返回手势直接回聊天页
    androidx.activity.compose.BackHandler { onBack() }

    // 300ms 防抖：输入中不重算结果；query 变化会重启协程，旧 delay 自动作废
    var debouncedQuery by remember { mutableStateOf("") }
    LaunchedEffect(query) {
        if (query.isBlank()) {
            debouncedQuery = ""
        } else {
            delay(300)
            debouncedQuery = query.trim()
        }
    }
    val searching = query.isNotBlank() && query.trim() != debouncedQuery

    Box(
        Modifier
            .fillMaxSize()
            // 平移转场页面必须有实底：否则转场中本页滑入时透出下层聊天页
            // （壁纸/抽屉）；全局壁纸开时铺对齐壁纸（与 backdrop 采样同源同位）
            .background(MaterialTheme.colorScheme.background)
    ) {
        // ★ 采样宿主（2026-09-22 挂载铁律）：只录背景层，玻璃元素在宿主外。
        // 宿主子树内含玻璃 = RenderNode 成环 = SIGSEGV（详见 SettingsScreen 同款注释）
        Box(Modifier.matchParentSize().appLayer(backdrop)) {
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
            .navigationBarsPadding()
            .padding(top = 6.dp)
            .clickable(interactionSource = null, indication = null) {
                // 点页面空白：呼出中只收起呼出
                if (openCardId != null) openCardId = null
            }
    ) {
        GlassPageBar(
            backdrop = backdrop,
            title = if (showTrash) "回收站" else "全部会话",
            onBack = {
                // 呼出操作按钮期间点返回：只收起呼出，不离开页面
                if (openCardId != null) openCardId = null else onBack()
            },
            modifier = Modifier
        )

        // 搜索框常驻：两个视图共用（回收站内可搜索后精准彻底删除）
        Box {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = {
                Text(
                    "搜索标题与消息内容",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f)
                )
            },
            leadingIcon = {
                Icon(
                    Icons.Filled.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                )
            },
            trailingIcon = {
                if (searching) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )
                } else if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }, modifier = Modifier.size(34.dp)) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "清空",
                            tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                            modifier = Modifier.size(17.dp)
                        )
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            colors = glassFieldColors(),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        )
        if (openCardId != null) {
            Box(
                Modifier
                    .matchParentSize()
                    .clickable(interactionSource = null, indication = null) { openCardId = null }
            )
        }
        }

        // 视图切换（与滑动双向同步）+ 回收站清空入口
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            com.haoai.agent.ui.common.LiquidTabRow(
                tabs = listOf("会话 ${sessions.size}", "回收站 ${deletedSessions.size}"),
                selectedIndex = if (showTrash) 1 else 0,
                onSelected = { i ->
                    // 呼出中只收起呼出，不切页
                    if (openCardId != null) openCardId = null
                    showTrash = i == 1
                },
                backdrop = backdrop,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.weight(1f))
            // 常驻占位：若按条件增删，Row 权重空间重分配会让 LiquidTabRow 宽度
            // 跳变（切回收站胶囊缩小、切回又放大）。不可见时 alpha(0)+禁用，宽度恒定
            val trashActionVisible = showTrash && deletedSessions.isNotEmpty()
            TextButton(
                onClick = { confirmEmptyTrash = true },
                enabled = trashActionVisible,
                modifier = Modifier.alpha(if (trashActionVisible) 1f else 0f)
            ) {
                Text("清空", color = MaterialTheme.colorScheme.error)
            }
        }

        // 会话/回收站两页条件渲染 + 左右滑切页：不用 HorizontalPager——其页面
        // graphicsLayer 平移会被玻璃卡片 backdrop 采样，在卡片四角回画成矩形阴影
        // （与抽屉弃用 ModalNavigationDrawer、SwipeRevealCard 弃 graphicsLayer 同因）
        val sessionsPage = !showTrash
        val baseList = if (sessionsPage) sessions else deletedSessions
        // 双模式搜索：标题命中 + 内容命中（带摘要）。内存过滤，量级为个人会话数，
        // remember 缓存避免重组重算
        val searchResult = remember(debouncedQuery, baseList) {
            searchSessions(baseList, debouncedQuery)
        }
        val contentSnippets = remember(searchResult) {
            searchResult.contentHits.associate { it.session.id to it.snippet }
        }
        val list = if (debouncedQuery.isBlank()) baseList
        else searchResult.titleHits + searchResult.contentHits.map { it.session }
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .pointerInput(Unit) { detectTapGestures { openCardId = null } }
                .pointerInput(Unit) {
                    // 左滑→回收站，右滑→会话；卡片消费掉的水平拖拽不会到达此处
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var tx = 0f; var ty = 0f
                        while (true) {
                            val e = awaitPointerEvent()
                            val c = e.changes.firstOrNull { it.id == down.id } ?: break
                            if (c.changedToUp()) break
                            tx += c.positionChange().x; ty += c.positionChange().y
                            if (abs(tx) > viewConfiguration.touchSlop || abs(ty) > viewConfiguration.touchSlop) {
                                if (abs(tx) > abs(ty)) {
                                    if (tx < 0f && !showTrash) showTrash = true
                                    else if (tx > 0f && showTrash) showTrash = false
                                    // 切页时收起展开的操作按钮
                                    openCardId = null
                                }
                                break
                            }
                        }
                    }
                }
        ) {
                if (list.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            when {
                                !sessionsPage && query.isBlank() -> "回收站是空的。删除的会话会保留 7 天，之后自动清理。"
                                query.isBlank() -> "还没有历史会话"
                                else -> "没有匹配的标题或内容"
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 24.dp)
                        )
                    }
                } else if (sessionsPage) {
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp)
                    ) {
                        items(list, key = { it.id }) { s ->
                            val active = s.id == activeId
                            SwipeRevealCard(
                                isOpen = openCardId == s.id,
                                anyOpen = openCardId != null,
                                onClick = {
                                    vm.selectSession(s.id)
                                    onBack()
                                },
                                onOpenChange = { open -> openCardId = if (open) s.id else null },
                                openWidth = 200.dp,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
                                actions = {
                                    FilledIconButton(
                                        onClick = { usageTarget = s; openCardId = null },
                                        modifier = Modifier.size(44.dp),
                                        colors = IconButtonDefaults.filledIconButtonColors(
                                            containerColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.10f),
                                            contentColor = MaterialTheme.colorScheme.onBackground
                                        )
                                    ) {
                                        Icon(
                                            Icons.Filled.BarChart,
                                            contentDescription = "本会话用量",
                                            modifier = Modifier.size(19.dp)
                                        )
                                    }
                                    FilledIconButton(
                                        onClick = {
                                            vm.pinSession(s.id)
                                            openCardId = null
                                        },
                                        modifier = Modifier.size(44.dp),
                                        colors = IconButtonDefaults.filledIconButtonColors(
                                            // 未置顶：灰（同重命名）；已置顶：绿（提示当前处于置顶态）
                                            containerColor = if (s.pinned) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                                            else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.10f),
                                            contentColor = if (s.pinned) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onBackground
                                        )
                                    ) {
                                        Icon(
                                            Icons.Filled.PushPin,
                                            contentDescription = if (s.pinned) "取消置顶" else "置顶",
                                            modifier = Modifier.size(19.dp)
                                        )
                                    }
                                    FilledIconButton(
                                        onClick = { renameTarget = s; openCardId = null },
                                        modifier = Modifier.size(44.dp),
                                        colors = IconButtonDefaults.filledIconButtonColors(
                                            containerColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.10f),
                                            contentColor = MaterialTheme.colorScheme.onBackground
                                        )
                                    ) {
                                        Icon(Icons.Filled.Edit, contentDescription = "重命名", modifier = Modifier.size(19.dp))
                                    }
                                    FilledIconButton(
                                        onClick = {
                                            vm.deleteSession(s.id)
                                            openCardId = null
                                        },
                                        modifier = Modifier.size(44.dp),
                                        colors = IconButtonDefaults.filledIconButtonColors(
                                            containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.16f),
                                            contentColor = MaterialTheme.colorScheme.error
                                        )
                                    ) {
                                        Icon(Icons.Filled.Delete, contentDescription = "删除", modifier = Modifier.size(20.dp))
                                    }
                                },
                                content = { cardClick ->
                                    GlassCard(
                                        onClick = cardClick,
                                        backdrop = backdrop,
                                        shape = RoundedCornerShape(14.dp),
                                        // 与全项目内容卡同刻度（壁纸模式自动 0.82）；
                                        // 当前会话用品牌色 tint 区分，不再用"更透"区分
                                        surfaceAlpha = com.haoai.agent.ui.theme.haoCardSurfaceAlpha(),
                                        tint = if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.22f) else null,
                                        lensRadius = 14.dp,
                                        pressScale = true,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            Modifier.padding(start = 14.dp, end = 14.dp, top = 9.dp, bottom = 9.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(Modifier.weight(1f)) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    if (s.pinned) {
                                                        Icon(
                                                            Icons.Filled.PushPin,
                                                            contentDescription = "已置顶",
                                                            tint = MaterialTheme.colorScheme.primary,
                                                            modifier = Modifier
                                                                .size(12.dp)
                                                                .padding(end = 2.dp)
                                                        )
                                                        Spacer(Modifier.size(3.dp))
                                                    }
                                                    Text(
                                                        highlightedAnnotatedString(s.title, debouncedQuery),
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                                        color = if (active) MaterialTheme.colorScheme.primary
                                                        else MaterialTheme.colorScheme.onBackground,
                                                        maxLines = 1
                                                    )
                                                }
                                                Text(
                                                    "${fmt.format(Date(s.updatedAt))} · ${s.messages.size} 条",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f)
                                                )
                                                // 内容命中摘要：±50 字窗口，命中词高亮
                                                contentSnippets[s.id]?.let { snippet ->
                                                    Text(
                                                        highlightedAnnotatedString(snippet, debouncedQuery),
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                                                        maxLines = 2,
                                                        modifier = Modifier.padding(top = 2.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp)
                    ) {
                        items(list, key = { it.id }) { s ->
                            val daysLeft = 7 - ((System.currentTimeMillis() - s.deletedAt) / (24 * 60 * 60 * 1000L))
                            GlassCard(
                                onClick = {},
                                backdrop = backdrop,
                                shape = RoundedCornerShape(14.dp),
                                surfaceAlpha = com.haoai.agent.ui.theme.haoCardSurfaceAlpha(),
                                lensRadius = 14.dp,
                                pressScale = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 2.dp)
                            ) {
                                Row(
                                    Modifier.padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            highlightedAnnotatedString(s.title, debouncedQuery),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onBackground,
                                            maxLines = 1
                                        )
                                        Text(
                                            "${fmt.format(Date(s.deletedAt))} 删除 · 剩 $daysLeft 天自动清理 · ${s.messages.size} 条",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f)
                                        )
                                        contentSnippets[s.id]?.let { snippet ->
                                            Text(
                                                highlightedAnnotatedString(snippet, debouncedQuery),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                                                maxLines = 2,
                                                modifier = Modifier.padding(top = 2.dp)
                                            )
                                        }
                                    }
                                    TextButton(onClick = { vm.restoreSession(s.id) }) {
                                        Text("恢复", color = MaterialTheme.colorScheme.primary)
                                    }
                                    TextButton(onClick = { pendingPurge = s }) {
                                        Text("彻底删除", color = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                    }
                }
            }
    }

    }

    // 5.4 本会话用量弹窗
    usageTarget?.let { target ->
        val su = remember(target.id) { com.haoai.agent.data.UsageLedger.summarize(target.id) }
        com.haoai.agent.ui.common.GlassAlertDialog(
            backdrop = backdrop,
            title = "本会话用量",
            confirmLabel = "好",
            onConfirm = { usageTarget = null },
            onDismiss = { usageTarget = null }
        ) {
            Column {
                Text("「${target.title}」", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Token 合计 ${su.monthIn + su.monthOut}（全量口径）\n输入 ${su.monthIn} · 输出 ${su.monthOut}\n调用记录 ${su.entries} 条",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    pendingPurge?.let { target ->
        GlassAlertDialog(
            backdrop = backdrop,
            title = "彻底删除会话",
            onDismiss = { pendingPurge = null },
            confirmLabel = "彻底删除",
            dismissLabel = "取消",
            danger = true,
            onConfirm = {
                vm.deleteSessionForever(target.id)
                pendingPurge = null
            }
        ) {
            Text(
                "「${target.title}」（${target.messages.size} 条消息）将被永久删除，无法恢复。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
        }
    }

    renameTarget?.let { target ->
        var renameValue by remember(target.id) { mutableStateOf(target.title) }
        GlassAlertDialog(
            backdrop = backdrop,
            title = "重命名会话",
            onDismiss = { renameTarget = null },
            confirmLabel = "保存",
            dismissLabel = "取消",
            onConfirm = {
                vm.renameSession(target.id, renameValue)
                renameTarget = null
            }
        ) {
            OutlinedTextField(
                value = renameValue,
                onValueChange = { renameValue = it.take(50) },
                label = { Text("会话名") },
                singleLine = true,
                colors = glassFieldColors(),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }

    if (confirmEmptyTrash) {
        GlassAlertDialog(
            backdrop = backdrop,
            title = "清空回收站",
            onDismiss = { confirmEmptyTrash = false },
            confirmLabel = "全部彻底删除",
            dismissLabel = "取消",
            danger = true,
            onConfirm = {
                vm.emptyTrash()
                confirmEmptyTrash = false
            }
        ) {
            Text(
                "回收站内 ${deletedSessions.size} 个会话将被永久删除，无法恢复。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
        }
    }
}
