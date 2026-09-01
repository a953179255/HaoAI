package com.haoai.agent.ui.settings

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.haoai.agent.agent.mcp.McpConnState
import com.haoai.agent.agent.mcp.McpManager
import com.haoai.agent.agent.mcp.McpServerConfig
import com.haoai.agent.ui.common.GlassAlertDialog
import com.haoai.agent.ui.common.GlassCard
import com.haoai.agent.ui.common.GlassPageBar
import com.haoai.agent.ui.common.LiquidGlassButton
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * MCP 服务器管理页（2.1）：添加/编辑远程 MCP 服务器、测试连接、查看工具清单、
 * 按 server 启停与审批级别设置。视觉沿用 Glass 组件库。
 */
@Composable
fun McpSettingsScreen(backdrop: com.kyant.backdrop.backdrops.LayerBackdrop, onBack: () -> Unit) {
    var servers by remember { mutableStateOf(McpManager.listServers()) }
    val states by McpManager.states.collectAsState()
    val scope = rememberCoroutineScope()

    // null = 列表视图；"new" = 新建；其他 = 编辑对应 id
    var editing by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<McpServerConfig?>(null) }

    fun refresh() { servers = McpManager.listServers() }

    BackHandler(enabled = editing != null) { editing = null }
    BackHandler(enabled = editing == null) { onBack() }

    if (editing != null) {
        val existing = servers.find { it.id == editing }
        McpEditView(
            backdrop = backdrop,
            initial = existing,
            onBack = {
                editing = null
                refresh()
            },
            onSave = { cfg ->
                scope.launch {
                    if (existing == null) McpManager.addServer(cfg) else McpManager.updateServer(cfg)
                    editing = null
                    refresh()
                }
            },
            onDelete = existing?.let { srv ->
                {
                    scope.launch {
                        McpManager.removeServer(srv.id)
                        editing = null
                        refresh()
                    }
                }
            }
        )
    } else {
        Box(Modifier.fillMaxSize().statusBarsPadding()) {
            Column(Modifier.fillMaxSize()) {
                Spacer(Modifier.height(64.dp))
                Text(
                    "接入 Model Context Protocol 服务器，为代理扩展外部工具。工具默认执行前询问；添加后冷启动会自动连接。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
                )
                Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    LiquidGlassButton(
                        onClick = { editing = "new" },
                        backdrop = backdrop,
                        shape = RoundedCornerShape(percent = 50),
                        surfaceColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 9.dp)
                        ) {
                            Text("＋", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "添加服务器",
                                color = MaterialTheme.colorScheme.onPrimary,
                                fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
                if (servers.isEmpty()) {
                    Text(
                        "暂无 MCP 服务器。可以添加任何支持 Streamable HTTP 的 MCP 服务器（例如文档查询类只读服务）。",
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
                    items(servers, key = { it.id }) { srv ->
                        val st = states[srv.id] ?: McpConnState.Disconnected
                        GlassCard(
                            onClick = { editing = srv.id },
                            backdrop = backdrop,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.fillMaxWidth().padding(14.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    StatusDot(st)
                                    Spacer(Modifier.width(8.dp))
                                    Column(Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(srv.name, style = MaterialTheme.typography.titleSmall)
                                            if (srv.kind == "stdio") {
                                                Spacer(Modifier.width(6.dp))
                                                Text(
                                                    "本地",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                        }
                                        Text(
                                            if (srv.kind == "stdio") srv.command else srv.url,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontFamily = FontFamily.Monospace,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1
                                        )
                                    }
                                    com.haoai.agent.ui.common.LiquidToggle(
                                        checked = srv.enabled,
                                        onCheckedChange = { on ->
                                            scope.launch {
                                                McpManager.setEnabled(srv.id, on)
                                                refresh()
                                            }
                                        },
                                        backdrop = backdrop
                                    )
                                }
                                val (label, color) = stateLabel(st)
                                if (label != null) {
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        label,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = color ?: MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2
                                    )
                                }
                                if (srv.toolCache.isNotEmpty()) {
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        "${srv.toolCache.size} 把工具 · 审批：" +
                                            if (srv.approvalLevel == "read") "免审批" else "每次询问",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        // 顶栏悬浮（与其他管理页一致：TopCenter + 12/6 边距）
        GlassPageBar(
            backdrop = backdrop,
            title = "MCP 服务器",
            onBack = onBack,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(horizontal = 12.dp, vertical = 6.dp)
        )
        }
        pendingDelete?.let { srv ->
            GlassAlertDialog(
                backdrop = backdrop,
                title = "删除服务器",
                onDismiss = { pendingDelete = null },
                confirmLabel = "删除",
                dismissLabel = "取消",
                danger = true,
                onConfirm = {
                    scope.launch {
                        McpManager.removeServer(srv.id)
                        pendingDelete = null
                        refresh()
                    }
                },
                content = {
                    Text("将移除「${srv.name}」及其全部工具注册。确定删除？")
                }
            )
        }
    }
}

@Composable
private fun StatusDot(st: McpConnState) {
    val color = when (st) {
        is McpConnState.Ready -> Color(0xFF3FA37A)
        is McpConnState.Connecting -> Color(0xFF5B8DEF)
        is McpConnState.Error -> Color(0xFFE25555)
        McpConnState.PendingReady -> Color(0xFFD9913F)
        McpConnState.Disconnected -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
    }
    Box(
        Modifier
            .size(10.dp)
            .background(color, CircleShape)
    )
}

private fun stateLabel(st: McpConnState): Pair<String?, Color?> = when (st) {
    is McpConnState.Ready -> "已连接（${st.toolCount} 把工具）" to null
    is McpConnState.Connecting -> "连接中…" to null
    is McpConnState.Error -> "连接失败：${st.message}" to Color(0xFFE25555)
    McpConnState.PendingReady ->
        "待就绪：需要 Linux 环境（设置 → Linux 环境 安装发行版后自动连接）" to Color(0xFFD9913F)
    McpConnState.Disconnected -> null to null
}

// ---------- 编辑视图 ----------

@Composable
private fun McpEditView(
    backdrop: com.kyant.backdrop.backdrops.LayerBackdrop,
    initial: McpServerConfig?,
    onBack: () -> Unit,
    onSave: (McpServerConfig) -> Unit,
    onDelete: (() -> Unit)?
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var kind by remember { mutableStateOf(initial?.kind ?: "http") }
    var url by remember { mutableStateOf(initial?.url?.ifBlank { "https://" } ?: "https://") }
    var command by remember { mutableStateOf(initial?.command ?: "") }
    var bearer by remember {
        mutableStateOf(
            initial?.headers?.entries?.firstOrNull {
                it.key.equals("Authorization", true) && it.value.startsWith("Bearer ")
            }?.value?.removePrefix("Bearer ") ?: ""
        )
    }
    // 自定义 headers（不含 Authorization）：键/值平行列表按行编辑
    var headerKeys by remember {
        mutableStateOf(
            initial?.headers?.filterKeys { !it.equals("Authorization", true) }?.keys?.toList() ?: emptyList()
        )
    }
    var headerValues by remember {
        mutableStateOf(
            initial?.headers?.filterKeys { !it.equals("Authorization", true) }?.values?.toList() ?: emptyList()
        )
    }
    var approvalLevel by remember { mutableStateOf(initial?.approvalLevel ?: "write") }
    var allowPlaintext by remember { mutableStateOf(initial?.allowPlaintext ?: false) }
    var testResult by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var testing by remember { mutableStateOf(false) }
    var errText by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    fun buildConfig(): McpServerConfig? {
        val n = name.trim()
        if (n.isEmpty()) { errText = "名称不能为空"; return null }
        if (kind == "stdio") {
            val cmd = command.trim()
            if (cmd.isEmpty()) { errText = "启动命令不能为空"; return null }
            return McpServerConfig(
                id = initial?.id ?: UUID.randomUUID().toString(),
                name = n, url = "", kind = "stdio", command = cmd,
                headers = emptyMap(),
                enabled = initial?.enabled ?: true,
                approvalLevel = approvalLevel,
                allowPlaintext = false,
                toolCache = initial?.toolCache ?: emptyList()
            )
        }
        val u = url.trim()
        if (!u.startsWith("https://") && !u.startsWith("http://")) {
            errText = "URL 需以 http(s):// 开头"; return null
        }
        val headers = buildMap {
            headerKeys.zip(headerValues).forEach { (k, v) ->
                val key = k.trim(); val value = v.trim()
                if (key.isNotEmpty() && !key.equals("Authorization", true)) put(key, value)
            }
            if (bearer.isNotBlank()) put("Authorization", "Bearer ${bearer.trim()}")
        }
        return McpServerConfig(
            id = initial?.id ?: UUID.randomUUID().toString(),
            name = n, url = u, kind = "http", command = "",
            headers = headers,
            enabled = initial?.enabled ?: true,
            approvalLevel = approvalLevel,
            allowPlaintext = allowPlaintext,
            toolCache = initial?.toolCache ?: emptyList()
        )
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        GlassPageBar(
            backdrop = backdrop,
            title = if (initial == null) "添加 MCP 服务器" else "编辑服务器",
            onBack = onBack
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp, 8.dp, 16.dp, 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                GlassCard(onClick = {}, backdrop = backdrop, shape = RoundedCornerShape(16.dp), pressScale = false) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Column {
                            Text(
                                "服务器类型",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(
                                    selected = kind == "http",
                                    onClick = { kind = "http" },
                                    label = { Text("远程 HTTP") }
                                )
                                FilterChip(
                                    selected = kind == "stdio",
                                    onClick = { kind = "stdio" },
                                    label = { Text("本地 stdio") }
                                )
                            }
                            Text(
                                if (kind == "stdio")
                                    "在 Linux 沙箱内拉起本地 MCP 服务器进程（3.5）。需先安装 Linux 环境。"
                                else "连接远程 Streamable HTTP MCP endpoint。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        LabeledField("名称", name, { name = it }, placeholder = "如 本地文件检索")
                        if (kind == "stdio") {
                            LabeledField(
                                "沙箱内启动命令", command, { command = it },
                                placeholder = "如 node /workspace/mcp/server.js", mono = true
                            )
                            val sandboxOk = remember { McpManager.sandboxReady() }
                            Text(
                                if (sandboxOk) "✓ Linux 沙箱已就绪，保存后自动连接"
                                else "⚠ Linux 环境未安装：请先到 设置 → Linux 环境 安装发行版（安装后本服务器会自动连接）",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (sandboxOk) Color(0xFF3FA37A) else Color(0xFFD9913F)
                            )
                        } else {
                            LabeledField(
                                "服务器 URL（Streamable HTTP endpoint）", url, { url = it },
                                placeholder = "https://example.com/mcp", mono = true
                            )
                            LabeledField(
                                "Bearer Token（可选）", bearer, { bearer = it },
                                placeholder = "留空表示无需鉴权",
                                visual = PasswordVisualTransformation()
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    "自定义请求头（可选）",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(onClick = {
                                    headerKeys = headerKeys + ""
                                    headerValues = headerValues + ""
                                }) {
                                    Icon(Icons.Filled.Add, contentDescription = "添加请求头")
                                }
                            }
                            headerKeys.indices.forEach { i ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    androidx.compose.material3.OutlinedTextField(
                                        value = headerKeys[i],
                                        onValueChange = { v ->
                                            headerKeys = headerKeys.toMutableList().also { it[i] = v }
                                        },
                                        modifier = Modifier.weight(1f),
                                        singleLine = true,
                                        placeholder = { Text("Header", style = MaterialTheme.typography.bodySmall) },
                                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                                    )
                                    Text(":", style = MaterialTheme.typography.titleMedium)
                                    androidx.compose.material3.OutlinedTextField(
                                        value = headerValues.getOrElse(i) { "" },
                                        onValueChange = { v ->
                                            headerValues = headerValues.toMutableList().also {
                                                if (i < it.size) it[i] = v else it.add(v)
                                            }
                                        },
                                        modifier = Modifier.weight(1.4f),
                                        singleLine = true,
                                        placeholder = { Text("值", style = MaterialTheme.typography.bodySmall) },
                                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                                    )
                                    IconButton(onClick = {
                                        headerKeys = headerKeys.toMutableList().also { if (i < it.size) it.removeAt(i) }
                                        headerValues = headerValues.toMutableList().also { if (i < it.size) it.removeAt(i) }
                                    }) {
                                        Icon(
                                            Icons.Filled.Delete,
                                            contentDescription = "删除请求头",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            item {
                GlassCard(onClick = {}, backdrop = backdrop, shape = RoundedCornerShape(16.dp), pressScale = false) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            "审批级别",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = approvalLevel == "write",
                                onClick = { approvalLevel = "write" },
                                label = { Text("每次询问（推荐）") }
                            )
                            FilterChip(
                                selected = approvalLevel == "read",
                                onClick = { approvalLevel = "read" },
                                label = { Text("免审批") }
                            )
                        }
                        Text(
                            if (approvalLevel == "read")
                                "⚠ 免审批模式下该服务器的所有工具将直接执行、不再弹窗确认——仅建议对确信只读的服务开启。"
                            else "该服务器工具在代理调用前会弹出确认。",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (approvalLevel == "read") Color(0xFFD9913F)
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (kind == "http") {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.weight(1f)) {
                                    Text("允许明文 http", style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        "仅为你自建的本地/内网 http 服务器开启；公网地址请使用 https。",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                com.haoai.agent.ui.common.LiquidToggle(
                                    checked = allowPlaintext,
                                    onCheckedChange = { allowPlaintext = it },
                                    backdrop = backdrop
                                )
                            }
                        }
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    LiquidGlassButton(
                        onClick = {
                            val cfg = buildConfig() ?: return@LiquidGlassButton
                            testing = true
                            testResult = null
                            scope.launch {
                                val r = McpManager.testConnection(cfg)
                                testing = false
                                testResult = r.fold(
                                    { n -> true to "连接成功，发现 $n 把工具" },
                                    { e -> false to (e.message ?: "连接失败") }
                                )
                            }
                        },
                        backdrop = backdrop,
                        shape = RoundedCornerShape(percent = 50),
                        enabled = !testing,
                        surfaceColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.85f)
                    ) {
                        Text(
                            if (testing) "测试中…" else "测试连接",
                            color = MaterialTheme.colorScheme.onSecondary,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                        )
                    }
                    LiquidGlassButton(
                        onClick = {
                            val cfg = buildConfig() ?: return@LiquidGlassButton
                            onSave(cfg)
                        },
                        backdrop = backdrop,
                        shape = RoundedCornerShape(percent = 50),
                        surfaceColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
                    ) {
                        Text(
                            "保存",
                            color = MaterialTheme.colorScheme.onPrimary,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 22.dp, vertical = 10.dp)
                        )
                    }
                }
            }
            testResult?.let { (ok, msg) ->
                item {
                    Text(
                        (if (ok) "✓ " else "✗ ") + msg,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (ok) Color(0xFF3FA37A) else Color(0xFFE25555)
                    )
                }
            }
            if (initial != null && initial.toolCache.isNotEmpty()) {
                item {
                    GlassCard(onClick = {}, backdrop = backdrop, shape = RoundedCornerShape(16.dp), pressScale = false) {
                        Column(Modifier.fillMaxWidth().padding(14.dp)) {
                            Text(
                                "工具清单（${initial.toolCache.size}）",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(6.dp))
                            initial.toolCache.forEach { t ->
                                Text(
                                    "• ${t.name}",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace
                                )
                                if (t.description.isNotBlank()) {
                                    Text(
                                        "  ${t.description.take(80)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (onDelete != null) {
                item {
                    LiquidGlassButton(
                        onClick = { onDelete() },
                        backdrop = backdrop,
                        shape = RoundedCornerShape(percent = 50),
                        surfaceColor = Color(0xFFE25555).copy(alpha = 0.85f)
                    ) {
                        Text(
                            "删除此服务器",
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LabeledField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    placeholder: String = "",
    mono: Boolean = false,
    visual: androidx.compose.ui.text.input.VisualTransformation = androidx.compose.ui.text.input.VisualTransformation.None
) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        androidx.compose.material3.OutlinedTextField(
            value = value,
            onValueChange = onChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text(placeholder, style = MaterialTheme.typography.bodySmall) },
            textStyle = if (mono) MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
            else MaterialTheme.typography.bodyMedium,
            visualTransformation = visual
        )
    }
}
