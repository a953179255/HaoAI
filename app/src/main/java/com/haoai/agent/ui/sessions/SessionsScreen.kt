package com.haoai.agent.ui.sessions

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
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
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 独立「全部会话」页：搜索过滤（会话/回收站共用）+ 左右滑动切换 + 置顶/重命名/回收站管理。 */
@Composable
fun SessionsScreen(
    vm: ChatViewModel,
    backdrop: LayerBackdrop,
    onBack: () -> Unit
) {
    val sessions by vm.sessions.collectAsState()
    val deletedSessions by vm.deletedSessions.collectAsState()
    val activeId = vm.session.collectAsState().value?.id

    var query by rememberSaveable { mutableStateOf("") }
    var showTrash by rememberSaveable { mutableStateOf(false) }
    val fmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
    val scope = rememberCoroutineScope()
    // 待彻底删除的会话：误触不可逆操作前先确认
    var pendingPurge by remember { mutableStateOf<StoredSession?>(null) }
    // 重命名目标；清空回收站确认
    var renameTarget by remember { mutableStateOf<StoredSession?>(null) }
    var confirmEmptyTrash by remember { mutableStateOf(false) }
    // 当前滑出操作按钮的会话（同时只允许一张）
    var openCardId by remember { mutableStateOf<String?>(null) }

    // 左「会话」右「回收站」：从右往左滑直接翻到回收站
    val pagerState = rememberPagerState(initialPage = if (showTrash) 1 else 0, pageCount = { 2 })
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { page ->
            showTrash = page == 1
            // 翻页时收起展开的操作按钮，避免另一页残留展开态
            openCardId = null
        }
    }

    // 系统返回手势直接回聊天页
    androidx.activity.compose.BackHandler { onBack() }

    fun matches(s: StoredSession): Boolean =
        query.isBlank() || s.title.contains(query.trim(), ignoreCase = true)

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(top = 6.dp)
    ) {
        GlassPageBar(
            backdrop = backdrop,
            title = if (showTrash) "回收站" else "全部会话",
            onBack = onBack,
            modifier = Modifier.padding(horizontal = 12.dp)
        )

        // 搜索框常驻：两个视图共用（回收站内可搜索后精准彻底删除）
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = {
                Text(
                    "搜索会话标题",
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
                if (query.isNotEmpty()) {
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

        // 视图切换（与滑动双向同步）+ 回收站清空入口
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChip(
                selected = !showTrash,
                onClick = {
                    openCardId = null
                    scope.launch { pagerState.animateScrollToPage(0) }
                },
                label = {
                    Text(
                        "会话 ${sessions.size}",
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
            )
            FilterChip(
                selected = showTrash,
                onClick = {
                    openCardId = null
                    scope.launch { pagerState.animateScrollToPage(1) }
                },
                label = {
                    Text(
                        "回收站 ${deletedSessions.size}",
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
            )
            Spacer(Modifier.weight(1f))
            if (showTrash && deletedSessions.isNotEmpty()) {
                TextButton(onClick = { confirmEmptyTrash = true }) {
                    Text("清空", color = MaterialTheme.colorScheme.error)
                }
            }
        }

        HorizontalPager(state = pagerState, modifier = Modifier.weight(1f)) { page ->
            val sessionsPage = page == 0
            val list = (if (sessionsPage) sessions else deletedSessions).filter { matches(it) }
            // 页面根容器必须铺满：Pager 会把 wrap 高度的内容垂直居中（列表越短顶部空白越大）
            // 点空白处只收回展开的操作按钮（卡片/按钮自己会消费点击，不受影响）
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) { detectTapGestures { openCardId = null } }
            ) {
                if (list.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            when {
                                !sessionsPage && query.isBlank() -> "回收站是空的。删除的会话会保留 7 天，之后自动清理。"
                                query.isBlank() -> "还没有历史会话"
                                else -> "没有匹配的会话"
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
                                openWidth = 156.dp,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
                                actions = {
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
                                        surfaceAlpha = if (active) 0.30f else 0.16f,
                                        tint = if (active) MaterialTheme.colorScheme.secondary.copy(alpha = 0.25f) else null,
                                        lensRadius = 14.dp,
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
                                                        s.title,
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
                                surfaceAlpha = 0.16f,
                                lensRadius = 14.dp,
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
                                            s.title,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onBackground,
                                            maxLines = 1
                                        )
                                        Text(
                                            "${fmt.format(Date(s.deletedAt))} 删除 · 剩 $daysLeft 天自动清理 · ${s.messages.size} 条",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f)
                                        )
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

    pendingPurge?.let { target ->
        GlassAlertDialog(
            backdrop = backdrop,
            title = "彻底删除会话",
            onDismiss = { pendingPurge = null },
            confirmLabel = "彻底删除",
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
