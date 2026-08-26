package com.haoai.agent.ui.manage

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
import androidx.compose.ui.unit.dp
import com.haoai.agent.agent.skills.SkillStore
import com.haoai.agent.ui.common.GlassCard
import com.haoai.agent.ui.common.GlassPageBar

/**
 * 技能库管理：查看/删除代理自沉淀的 SKILL.md（skill 工具的图形入口）。
 */
@Composable
fun SkillsScreen(backdrop: com.kyant.backdrop.backdrops.LayerBackdrop, onBack: () -> Unit) {
    val store = SkillStore
    var skills by remember { mutableStateOf(store.list()) }
    var viewBody by remember { mutableStateOf<Pair<String, String>?>(null) }
    val fmt = remember { java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA) }

    fun refresh() {
        skills = store.list()
    }

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
        Text(
            "代理在完成任务时用 skill 工具沉淀的可复用经验；系统提示词只带索引，正文按需加载。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp)
        )
        if (skills.isEmpty()) {
            Text(
                "暂无技能——当代理踩坑并总结出可复用步骤时会自动沉淀到这里。",
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
                    surfaceAlpha = 0.22f,
                    lensRadius = 16.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(Modifier.padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 4.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(s.name, style = MaterialTheme.typography.titleSmall)
                            Text(
                                s.description.ifBlank { "（无描述）" },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2
                            )
                            Text(
                                fmt.format(java.util.Date(s.updatedAt)),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
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
        AlertDialog(
            onDismissRequest = { viewBody = null },
            title = { Text(name) },
            text = {
                Text(
                    body,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.verticalScroll(rememberScrollState())
                )
            },
            confirmButton = {
                TextButton(onClick = { viewBody = null }) { Text("关闭") }
            }
        )
    }
}
