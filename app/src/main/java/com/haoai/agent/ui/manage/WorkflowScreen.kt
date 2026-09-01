package com.haoai.agent.ui.manage

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.haoai.agent.agent.workflow.WorkflowRunner
import com.haoai.agent.agent.workflow.WorkflowStore
import com.haoai.agent.data.HaoJson
import com.haoai.agent.ui.common.GlassAlertDialog
import com.haoai.agent.ui.common.GlassCard
import com.kyant.backdrop.backdrops.LayerBackdrop
import kotlinx.coroutines.launch

/**
 * Phase 6 工作流管理页：列表 + 手动创建/编辑（JSON 高级模式）+ 启停 + 待确认徽标
 * + 手动运行 + lastRun 结果展开。Agent 起草的（pendingConfirm）只有这里能确认启用。
 */
@Composable
fun WorkflowScreen(
    container: com.haoai.agent.data.AppContainer,
    backdrop: LayerBackdrop,
    onBack: () -> Unit
) {
    var workflows by remember { mutableStateOf(WorkflowStore.list()) }
    var editTarget by remember { mutableStateOf<WorkflowStore.WorkflowDef?>(null) }
    var newDraft by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<WorkflowStore.WorkflowDef?>(null) }
    val scope = rememberCoroutineScope()

    fun refresh() { workflows = WorkflowStore.list() }

    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 84.dp, bottom = 40.dp)
        ) {
            item {
                Text(
                    "工作流 · ${workflows.size}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                )
            }
            items(workflows, key = { it.id }) { w ->
                GlassCard(
                    onClick = { editTarget = w },
                    backdrop = backdrop,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.Bolt,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.size(8.dp))
                            Text(w.name, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            if (w.pendingConfirm) {
                                Text(
                                    "待确认",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                                Spacer(Modifier.size(6.dp))
                            }
                            Switch(
                                checked = w.enabled && !w.pendingConfirm,
                                onCheckedChange = { on ->
                                    val next = w.copy(enabled = on && !w.pendingConfirm, pendingConfirm = false)
                                    WorkflowStore.save(next)
                                    refresh()
                                    // schedule 触发：启停同步 WorkManager
                                    if (next.enabled && next.trigger.type == "schedule") {
                                        WorkflowRunner.enqueueSchedule(container.appContext, next.id, next.trigger.config)
                                    } else WorkflowRunner.cancelSchedule(container.appContext, next.id)
                                }
                            )
                        }
                        Text(
                            "${w.steps.size} 步 · 触发 ${triggerLabel(w)}" +
                                (w.trigger.config.takeIf { it.isNotBlank() }?.let { "($it)" } ?: ""),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        w.lastRun?.let { run ->
                            Text(
                                (if (run.ok) "✓ " else "✗ ") + java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(run.ts)) +
                                    " · " + run.summary.take(80),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (run.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(top = 3.dp)
                            )
                        }
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(top = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                        ) {
                            Text(
                                "运行",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        scope.launch {
                                            val def = WorkflowStore.get(w.id) ?: return@launch
                                            WorkflowRunner.run(container, def.copy(lastRun = null))
                                            refresh()
                                        }
                                    }
                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = "删除",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .size(18.dp)
                                    .clickable { deleteTarget = w }
                            )
                        }
                    }
                }
            }
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    GlassCard(
                        onClick = { newDraft = true },
                        backdrop = backdrop,
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Row(
                            Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.size(6.dp))
                            Text("新建工作流（JSON）", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }

        com.haoai.agent.ui.common.GlassPageBar(
            backdrop = backdrop,
            title = "工作流",
            onBack = onBack,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }

    // JSON 编辑弹窗（新建/编辑通用）
    if (editTarget != null || newDraft) {
        val editing = editTarget
        var json by remember(editing?.id ?: "new") {
            mutableStateOf(
                editing?.let { HaoJson.json.encodeToString(WorkflowStore.WorkflowDef.serializer(), it) }
                    ?: WorkflowStore.WorkflowDef(
                        id = WorkflowStore.newId(), name = "新工作流",
                        trigger = WorkflowStore.Trigger("schedule", "every:30m"),
                        steps = listOf(WorkflowStore.Step(type = "prompt", text = "报个时间")),
                        createdAt = System.currentTimeMillis()
                    ).let { HaoJson.json.encodeToString(WorkflowStore.WorkflowDef.serializer(), it) }
            )
        }
        var err by remember { mutableStateOf<String?>(null) }
        GlassAlertDialog(
            backdrop = backdrop,
            title = if (editing == null) "新建工作流" else "编辑工作流",
            confirmLabel = "保存",
            dismissLabel = "取消",
            contentMaxHeight = 460.dp,
            onConfirm = {
                runCatching {
                    val def = HaoJson.json.decodeFromString(WorkflowStore.WorkflowDef.serializer(), json)
                    WorkflowStore.save(def)
                    if (def.enabled && def.trigger.type == "schedule") {
                        WorkflowRunner.enqueueSchedule(container.appContext, def.id, def.trigger.config)
                    } else WorkflowRunner.cancelSchedule(container.appContext, def.id)
                    err = null
                    editTarget = null
                    newDraft = false
                    refresh()
                }.onFailure { err = it.message ?: "JSON 解析失败" }
            },
            onDismiss = { editTarget = null; newDraft = false; err = null }
        ) {
            androidx.compose.material3.OutlinedTextField(
                value = json,
                onValueChange = { json = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(320.dp),
                textStyle = MaterialTheme.typography.labelSmall,
                isError = err != null,
                supportingText = { Text(err ?: "完整 JSON 定义；pendingConfirm=false 且 enabled=true 即生效") }
            )
        }
    }

    deleteTarget?.let { w ->
        GlassAlertDialog(
            backdrop = backdrop,
            title = "删除工作流",
            confirmLabel = "删除",
            dismissLabel = "取消",
            danger = true,
            onConfirm = {
                WorkflowRunner.cancelSchedule(container.appContext, w.id)
                WorkflowStore.delete(w.id)
                deleteTarget = null
                refresh()
            },
            onDismiss = { deleteTarget = null }
        ) {
            Text("「${w.name}」将被删除，无法恢复。", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private fun triggerLabel(w: WorkflowStore.WorkflowDef): String = when (w.trigger.type) {
    "manual" -> "手动"
    "schedule" -> "定时"
    "boot" -> "开机"
    "notification" -> "通知关键词"
    else -> w.trigger.type
}
