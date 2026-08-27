package com.haoai.agent.ui.sessions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.haoai.agent.ui.ChatViewModel
import com.haoai.agent.ui.common.GlassCard
import com.haoai.agent.ui.common.GlassPageBar
import com.haoai.agent.ui.common.glassFieldColors
import com.kyant.backdrop.backdrops.LayerBackdrop
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 独立「全部会话」页：搜索过滤 + 完整会话列表 + 回收站（恢复/彻底删除）。 */
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

    // 系统返回手势直接回聊天页
    androidx.activity.compose.BackHandler { onBack() }

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

        if (!showTrash) {
            // 搜索框：按标题过滤
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
        }

        // 视图切换：活跃会话 / 回收站
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = !showTrash,
                onClick = { showTrash = false },
                label = {
                    Text(
                        "会话 ${sessions.size}",
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
            )
            FilterChip(
                selected = showTrash,
                onClick = { showTrash = true },
                label = {
                    Text(
                        "回收站 ${deletedSessions.size}",
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
            )
        }

        val list = if (showTrash) deletedSessions
        else sessions.filter { query.isBlank() || it.title.contains(query.trim(), ignoreCase = true) }

        if (list.isEmpty()) {
            Text(
                if (showTrash) "回收站是空的。删除的会话会保留 7 天，之后自动清理。"
                else if (query.isBlank()) "还没有历史会话"
                else "没有匹配的会话",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
            )
        }

        LazyColumn(
            Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp)
        ) {
            if (showTrash) {
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
                            TextButton(onClick = { vm.deleteSessionForever(s.id) }) {
                                Text("彻底删除", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            } else {
                items(list, key = { it.id }) { s ->
                    val active = s.id == activeId
                    GlassCard(
                        onClick = {
                            vm.selectSession(s.id)
                            onBack()
                        },
                        backdrop = backdrop,
                        shape = RoundedCornerShape(14.dp),
                        surfaceAlpha = if (active) 0.30f else 0.16f,
                        tint = if (active) MaterialTheme.colorScheme.secondary.copy(alpha = 0.25f) else null,
                        lensRadius = 14.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 2.dp)
                    ) {
                        Row(
                            Modifier.padding(start = 14.dp, end = 4.dp, top = 9.dp, bottom = 9.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    s.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                    color = if (active) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onBackground,
                                    maxLines = 1
                                )
                                Text(
                                    "${fmt.format(Date(s.updatedAt))} · ${s.messages.size} 条",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f)
                                )
                            }
                            IconButton(onClick = { vm.deleteSession(s.id) }, modifier = Modifier.size(34.dp)) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = "删除",
                                    tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
                                    modifier = Modifier.size(17.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
