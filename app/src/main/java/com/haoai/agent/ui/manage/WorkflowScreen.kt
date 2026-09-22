package com.haoai.agent.ui.manage

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.statusBarsPadding
import com.haoai.agent.agent.workflow.WorkflowRunner
import com.haoai.agent.agent.workflow.WorkflowStore
import com.haoai.agent.data.HaoJson
import com.haoai.agent.ui.common.GlassAlertDialog
import com.haoai.agent.ui.common.GlassCard
import com.haoai.agent.ui.common.LiquidTabRow
import com.haoai.agent.ui.common.LiquidToggle
import com.kyant.backdrop.backdrops.LayerBackdrop
import kotlinx.coroutines.launch
import com.haoai.agent.ui.common.appLayer
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.luminance

private val TRIGGER_TYPES = listOf("manual", "schedule", "boot", "notification")

/**
 * 工作流管理页：列表 + 可视化步骤编辑器（JSON 高级模式可切换）+ 启停 + 待确认徽标
 * + 手动运行 + 运行历史（最近 20 条）+ 外部触发令牌。Agent 起草的（pendingConfirm）只有这里能确认启用。
 */
@Composable
fun WorkflowScreen(
    container: com.haoai.agent.data.AppContainer,
    backdrop: LayerBackdrop,
    onBack: () -> Unit,
    /** 全局壁纸开时传入：页面自带对齐的壁纸底 */
    wallpaper: android.graphics.Bitmap? = null
) {
    var workflows by remember { mutableStateOf(WorkflowStore.list()) }
    var editTarget by remember { mutableStateOf<WorkflowStore.WorkflowDef?>(null) }
    var newDraft by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<WorkflowStore.WorkflowDef?>(null) }
    var historyFor by remember { mutableStateOf<WorkflowStore.WorkflowDef?>(null) }
    var extFor by remember { mutableStateOf<WorkflowStore.WorkflowDef?>(null) }
    val scope = rememberCoroutineScope()

    fun refresh() { workflows = WorkflowStore.list() }

    // 系统返回手势：回到设置根页，而不是把应用最小化
    androidx.activity.compose.BackHandler { onBack() }

    androidx.compose.foundation.layout.Box(
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
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 56.dp, bottom = 40.dp)
        ) {
            item {
                Text(
                    "工作流 · ${workflows.size}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                )
            }
            if (workflows.isEmpty()) {
                item {
                    com.haoai.agent.ui.common.HaoEmptyState(
                        backdrop = localBackdrop,
                        icon = Icons.Filled.Bolt,
                        title = "暂无工作流",
                        hint = "工作流 = 多步自动化，可按定时 / 开机 / 通知触发。也可以直接让代理帮你建一条。",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                }
            }
            items(workflows, key = { it.id }) { w ->
                GlassCard(
                    onClick = { editTarget = w },
                    backdrop = localBackdrop,
                    shape = RoundedCornerShape(14.dp),
                    surfaceAlpha = com.haoai.agent.ui.theme.haoCardSurfaceAlpha(),
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
                            LiquidToggle(
                                checked = w.enabled && !w.pendingConfirm,
                                onCheckedChange = { on ->
                                    val next = w.copy(enabled = on && !w.pendingConfirm, pendingConfirm = false)
                                    WorkflowStore.save(next)
                                    refresh()
                                    // schedule 触发：启停同步 WorkManager
                                    if (next.enabled && next.trigger.type == "schedule") {
                                        WorkflowRunner.enqueueSchedule(container.appContext, next.id, next.trigger.config)
                                    } else WorkflowRunner.cancelSchedule(container.appContext, next.id)
                                },
                                backdrop = backdrop
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
                                (if (run.ok) "✓ " else "✗ ") + fmtTs(run.ts) + " · " + run.summary.take(80),
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
                                            WorkflowStore.get(w.id)?.let { def ->
                                                WorkflowRunner.run(container, def)
                                            }
                                            refresh()
                                        }
                                    }
                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                            if (w.history.isNotEmpty()) {
                                Text(
                                    "历史 ${w.history.size}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { historyFor = w }
                                        .padding(horizontal = 10.dp, vertical = 4.dp)
                                )
                            }
                            Text(
                                "外部触发",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { extFor = w }
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
                        backdrop = localBackdrop,
                        shape = RoundedCornerShape(14.dp),
                        surfaceAlpha = com.haoai.agent.ui.theme.haoCardSurfaceAlpha()
                    ) {
                        Row(
                            Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.size(6.dp))
                            Text("新建工作流", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }

        com.haoai.agent.ui.common.GlassPageBar(
            backdrop = localBackdrop,
            title = "工作流",
            onBack = onBack,
            modifier = Modifier
                .align(Alignment.TopCenter)
        )
    }

    // 编辑弹窗（新建/编辑通用；可视化步骤卡片 + JSON 高级模式可切换）
    if (editTarget != null || newDraft) {
        EditWorkflowDialog(
            editing = editTarget,
            container = container,
            backdrop = backdrop,
            onDismiss = { editTarget = null; newDraft = false },
            onSaved = {
                editTarget = null
                newDraft = false
                refresh()
            }
        )
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

    historyFor?.let { w ->
        GlassAlertDialog(
            backdrop = backdrop,
            title = "运行历史 · ${w.name}",
            confirmLabel = "关闭",
            fullWidthConfirm = true,
            onConfirm = { historyFor = null },
            onDismiss = { historyFor = null }
        ) {
            Column(
                Modifier
                    .heightIn(max = 380.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                if (w.history.isEmpty()) {
                    Text("暂无运行记录", style = MaterialTheme.typography.bodySmall)
                }
                w.history.reversed().forEach { r ->
                    Column(Modifier.padding(vertical = 5.dp)) {
                        Text(
                            (if (r.ok) "✓" else "✗") + " ${fmtTs(r.ts)} · ${sourceLabel(r.trigger)} · ${fmtDur(r.durationMs)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (r.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                        )
                        Text(
                            r.summary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }

    extFor?.let { w ->
        val token = remember(w.id) {
            WorkflowStore.get(w.id)?.let { WorkflowStore.externalToken(it) } ?: ""
        }
        val cmd =
            "adb shell am broadcast -a com.haoai.agent.WORKFLOW_RUN -n com.haoai.agent/.platform.WorkflowTriggerReceiver --es id ${w.id} --es token $token"
        val clipboard = LocalClipboardManager.current
        GlassAlertDialog(
            backdrop = backdrop,
            title = "外部触发 · ${w.name}",
            confirmLabel = "复制命令",
            dismissLabel = "关闭",
            onConfirm = {
                clipboard.setText(AnnotatedString(cmd))
                extFor = null
            },
            onDismiss = { extFor = null }
        ) {
            Column {
                Text(
                    "令牌（每工作流唯一，更换即作废旧令牌）：",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(token, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.size(8.dp))
                Text(
                    "Tasker/MacroDroid：发送广播，动作 com.haoai.agent.WORKFLOW_RUN，目标组件 com.haoai.agent/.platform.WorkflowTriggerReceiver（须显式组件，隐式广播会被系统拦截），字符串 extras：id=${w.id}（或 name=工作流名）、token=$token。仅已启用的流程可被拉起。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    cmd,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun EditWorkflowDialog(
    editing: WorkflowStore.WorkflowDef?,
    container: com.haoai.agent.data.AppContainer,
    backdrop: LayerBackdrop,
    onDismiss: () -> Unit,
    onSaved: () -> Unit
) {
    var advanced by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf(editing?.name ?: "新工作流") }
    var triggerType by remember {
        mutableStateOf(TRIGGER_TYPES.indexOf(editing?.trigger?.type ?: "schedule").coerceAtLeast(0))
    }
    var triggerConfig by remember { mutableStateOf(editing?.trigger?.config?.ifBlank { "every:30m" } ?: "every:30m") }
    var steps by remember {
        mutableStateOf(editing?.steps?.ifEmpty { listOf(WorkflowStore.Step(type = "prompt", text = "")) }
            ?: listOf(WorkflowStore.Step(type = "prompt", text = "")))
    }
    var json by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }

    fun toDef(): WorkflowStore.WorkflowDef = WorkflowStore.WorkflowDef(
        id = editing?.id ?: WorkflowStore.newId(),
        name = name.trim().take(40).ifBlank { "未命名工作流" },
        enabled = editing?.enabled ?: false,
        pendingConfirm = editing?.pendingConfirm ?: false,
        trigger = WorkflowStore.Trigger(TRIGGER_TYPES[triggerType], triggerConfig.trim()),
        steps = steps,
        createdAt = editing?.createdAt ?: System.currentTimeMillis(),
        lastRun = editing?.lastRun,
        history = editing?.history ?: emptyList(),
        externalToken = editing?.externalToken
    )

    fun save(d: WorkflowStore.WorkflowDef) {
        WorkflowStore.save(d)
        if (d.enabled && d.trigger.type == "schedule") {
            WorkflowRunner.enqueueSchedule(container.appContext, d.id, d.trigger.config)
        } else WorkflowRunner.cancelSchedule(container.appContext, d.id)
        onSaved()
    }

    GlassAlertDialog(
        backdrop = backdrop,
        title = if (editing == null) "新建工作流" else "编辑工作流",
        confirmLabel = "保存",
        dismissLabel = "取消",
        contentMaxHeight = 480.dp,
        onConfirm = {
            if (advanced) {
                runCatching {
                    HaoJson.json.decodeFromString(WorkflowStore.WorkflowDef.serializer(), json)
                }.onSuccess { err = null; save(it) }
                    .onFailure { err = "JSON 无效：${it.message}" }
            } else {
                val d = toDef()
                if (d.trigger.type == "schedule" && !WorkflowStore.validScheduleSpec(d.trigger.config)) {
                    err = "定时规格无效。可用：every:30m / every:2h / every:1d / daily:09:30 / hourly"
                } else if (d.steps.none { it.type == "prompt" && it.text.isNotBlank() || it.type == "tool" && it.tool.isNotBlank() }) {
                    err = "至少需要一个非空步骤（指令或工具）"
                } else {
                    err = null; save(d)
                }
            }
        },
        onDismiss = onDismiss
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            Text(
                if (advanced) "← 可视化编辑" else "高级 JSON 模式",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable {
                        if (advanced) {
                            runCatching {
                                HaoJson.json.decodeFromString(WorkflowStore.WorkflowDef.serializer(), json)
                            }.onSuccess { d ->
                                name = d.name
                                triggerType = TRIGGER_TYPES.indexOf(d.trigger.type).coerceAtLeast(0)
                                triggerConfig = d.trigger.config
                                steps = d.steps.ifEmpty { listOf(WorkflowStore.Step()) }
                                advanced = false
                                err = null
                            }.onFailure { err = "JSON 无效：${it.message}" }
                        } else {
                            json = HaoJson.json.encodeToString(WorkflowStore.WorkflowDef.serializer(), toDef())
                            advanced = true
                            err = null
                        }
                    }
                    .padding(horizontal = 10.dp, vertical = 2.dp)
            )
        }
        if (advanced) {
            OutlinedTextField(
                value = json,
                onValueChange = { json = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp),
                textStyle = MaterialTheme.typography.labelSmall,
                isError = err != null,
                supportingText = { Text(err ?: "完整 JSON 定义；pendingConfirm=false 且 enabled=true 即生效") }
            )
        } else {
            Column(
                Modifier
                    .heightIn(max = 380.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    "触发方式",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                LiquidTabRow(
                    tabs = listOf("手动", "定时", "开机", "通知"),
                    selectedIndex = triggerType,
                    onSelected = { triggerType = it },
                    backdrop = backdrop
                )
                if (TRIGGER_TYPES[triggerType] == "schedule" || TRIGGER_TYPES[triggerType] == "notification") {
                    OutlinedTextField(
                        value = triggerConfig,
                        onValueChange = { triggerConfig = it },
                        label = { Text(if (TRIGGER_TYPES[triggerType] == "schedule") "定时规格" else "通知关键词") },
                        singleLine = true,
                        supportingText = {
                            Text(
                                if (TRIGGER_TYPES[triggerType] == "schedule")
                                    "every:30m / every:2h / every:1d / daily:09:30 / hourly"
                                else "捕获到含关键词的通知时触发（需通知使用权）",
                                style = MaterialTheme.typography.labelSmall
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Spacer(Modifier.size(10.dp))
                steps.forEachIndexed { i, s ->
                    StepEditorCard(
                        index = i,
                        total = steps.size,
                        step = s,
                        backdrop = backdrop,
                        onChange = { ns -> steps = steps.toMutableList().also { it[i] = ns } },
                        onRemove = { steps = steps.toMutableList().also { it.removeAt(i) } },
                        onMove = { delta ->
                            val j = i + delta
                            if (j in steps.indices) {
                                steps = steps.toMutableList().also { m -> m[i] = m[j].also { m[j] = m[i] } }
                            }
                        }
                    )
                    if (i < steps.size - 1) {
                        Text(
                            "↓ 输出可经 {{prev}} / {{step${i + 1}}} 传入后续步骤",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 12.dp, top = 1.dp, bottom = 1.dp)
                        )
                    }
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        "+ 添加步骤",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { steps = steps + WorkflowStore.Step() }
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                }
                err?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
        }
    }
}

/** 单步骤卡片：类型切换 + 指令/工具参数 + 条件 + 失败中止 + 排序/删除。 */
@Composable
private fun StepEditorCard(
    index: Int,
    total: Int,
    step: WorkflowStore.Step,
    backdrop: LayerBackdrop,
    onChange: (WorkflowStore.Step) -> Unit,
    onRemove: () -> Unit,
    onMove: (Int) -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(horizontal = 6.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "步骤 ${index + 1}",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            Icon(
                Icons.Filled.KeyboardArrowUp,
                contentDescription = "上移",
                tint = if (index > 0) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier
                    .size(18.dp)
                    .clickable(enabled = index > 0) { onMove(-1) }
            )
            Spacer(Modifier.size(6.dp))
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = "下移",
                tint = if (index < total - 1) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier
                    .size(18.dp)
                    .clickable(enabled = index < total - 1) { onMove(1) }
            )
            Spacer(Modifier.size(6.dp))
            Icon(
                Icons.Filled.Delete,
                contentDescription = "删除步骤",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(16.dp)
                    .clickable { onRemove() }
            )
        }
        LiquidTabRow(
            tabs = listOf("指令（Agent）", "工具直调"),
            selectedIndex = if (step.type == "tool") 1 else 0,
            onSelected = { onChange(step.copy(type = if (it == 1) "tool" else "prompt")) },
            backdrop = backdrop
        )
        if (step.type == "tool") {
            OutlinedTextField(
                value = step.tool,
                onValueChange = { onChange(step.copy(tool = it.trim().take(60))) },
                label = { Text("工具名") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = step.args,
                onValueChange = { onChange(step.copy(args = it.take(4000))) },
                label = { Text("参数 JSON") },
                textStyle = MaterialTheme.typography.labelSmall,
                supportingText = { Text("支持 {{prev}}/{{stepN}}，注入时自动 JSON 转义", style = MaterialTheme.typography.labelSmall) },
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            OutlinedTextField(
                value = step.text,
                onValueChange = { onChange(step.copy(text = it.take(2000))) },
                label = { Text("交给代理的指令") },
                textStyle = MaterialTheme.typography.bodySmall,
                minLines = 2,
                maxLines = 6,
                supportingText = { Text("支持 {{prev}}=上一步输出、{{step1}}=第 1 步输出", style = MaterialTheme.typography.labelSmall) },
                modifier = Modifier.fillMaxWidth()
            )
        }
        OutlinedTextField(
            value = step.condition,
            onValueChange = { onChange(step.copy(condition = it.take(200))) },
            label = { Text("条件（可选）") },
            singleLine = true,
            placeholder = { Text("prev_contains:完成") },
            supportingText = { Text("prev_contains:X / prev_not_contains:X，不满足则跳过此步", style = MaterialTheme.typography.labelSmall) },
            modifier = Modifier.fillMaxWidth()
        )
        Row(
            Modifier.padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LiquidToggle(
                checked = step.stopOnError,
                onCheckedChange = { onChange(step.copy(stopOnError = it)) },
                backdrop = backdrop
            )
            Spacer(Modifier.size(8.dp))
            Text(
                "失败中止",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun fmtTs(ts: Long): String =
    java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(ts))

private fun fmtDur(ms: Long): String =
    if (ms <= 0) "" else if (ms < 60_000) "${ms / 1000}s" else "${ms / 60_000}m${(ms % 60_000) / 1000}s"

private fun sourceLabel(t: String): String = when (t) {
    "manual" -> "手动"
    "schedule" -> "定时"
    "boot" -> "开机"
    "notification" -> "通知"
    "external" -> "外部"
    else -> t
}

private fun triggerLabel(w: WorkflowStore.WorkflowDef): String = when (w.trigger.type) {
    "manual" -> "手动"
    "schedule" -> "定时"
    "boot" -> "开机"
    "notification" -> "通知关键词"
    else -> w.trigger.type
}
