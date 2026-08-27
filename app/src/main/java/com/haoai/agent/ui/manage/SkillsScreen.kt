package com.haoai.agent.ui.manage

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
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
import com.haoai.agent.agent.skills.SkillStore
import com.haoai.agent.ui.common.GlassCard
import com.haoai.agent.ui.common.GlassPageBar
import com.haoai.agent.ui.common.appLayer

/**
 * 技能库管理：查看/手动添加/删除 SKILL.md（skill 工具的图形入口）。
 */
@Composable
fun SkillsScreen(backdrop: com.kyant.backdrop.backdrops.LayerBackdrop, onBack: () -> Unit) {
    val store = SkillStore
    var skills by remember { mutableStateOf(store.list()) }
    var viewBody by remember { mutableStateOf<Pair<String, String>?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    val fmt = remember { java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA) }

    // 系统返回手势：回到设置根页，而不是把应用最小化
    androidx.activity.compose.BackHandler { onBack() }

    fun refresh() {
        skills = store.list()
    }

    Box(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        androidx.compose.runtime.CompositionLocalProvider(
            com.haoai.agent.ui.common.LocalGlassRefract provides false
        ) {
        Column(
            Modifier
                .fillMaxSize()
                .appLayer(backdrop)
        ) {
            Spacer(Modifier.height(64.dp))
        Text(
            "代理在完成任务时用 skill 工具沉淀的可复用经验；系统提示词只带索引，正文按需加载。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp)
        )
        androidx.compose.foundation.layout.Row(
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
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
                            Text(
                                "使用 ${s.useCount} 次 · 最近 ${if (s.lastUsedAt > 0) fmt.format(java.util.Date(s.lastUsedAt)) else "从未"}" +
                                    " · 来源 ${if (s.source == "agent") "自进化" else "手动"}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(onClick = {
                            store.setPinned(s.name, !s.pinned)
                            refresh()
                        }) { Text(if (s.pinned) "取消置顶" else "置顶") }
                        TextButton(onClick = {
                            viewBody = s.name to (store.view(s.name) ?: "")
                        }) { Text("查看") }
                        IconButton(onClick = {
                            store.delete(s.name)
                            refresh()
                        }) {
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
                .padding(horizontal = 12.dp, vertical = 6.dp)
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
}
