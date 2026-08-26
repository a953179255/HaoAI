package com.haoai.agent.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.haoai.agent.agent.engine.ToolRunState
import com.haoai.agent.data.StoredSession
import com.haoai.agent.ui.ChatRow
import com.haoai.agent.ui.ChatViewModel
import com.haoai.agent.ui.common.GlassCard
import com.haoai.agent.ui.common.GlassPanel
import com.haoai.agent.ui.common.LiquidGlassButton
import com.haoai.agent.ui.common.MarkdownText
import com.haoai.agent.ui.common.appLayer
import com.haoai.agent.ui.common.rememberAppBackdrop
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

@Composable
fun ChatScreen(
    vm: ChatViewModel,
    backdrop: com.kyant.backdrop.backdrops.LayerBackdrop,
    drawerState: androidx.compose.material3.DrawerState =
        androidx.compose.material3.rememberDrawerState(androidx.compose.material3.DrawerValue.Closed),
    onOpenSettings: (fromDrawer: Boolean) -> Unit
) {
    val context = LocalContext.current

    val rows by vm.rows.collectAsState()
    val streaming by vm.streamingText.collectAsState()
    val running by vm.running.collectAsState()
    val error by vm.error.collectAsState()
    val sessions by vm.sessions.collectAsState()
    val approval by vm.approval.collectAsState()

    var input by rememberSaveable { mutableStateOf("") }
    var pendingImage by rememberSaveable { mutableStateOf<String?>(null) }

    val scope = rememberCoroutineScope()
    fun openDrawer() = scope.launch { drawerState.open() }

    val imagePicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            runCatching {
                // 降采样到最长边 1024px 再编码，控制视觉 token 与请求体积
                val resolver = context.contentResolver
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                resolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1024) sample *= 2
                val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
                val bmp = resolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, opts) }
                if (bmp != null) {
                    val bos = java.io.ByteArrayOutputStream()
                    bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, bos)
                    bmp.recycle()
                    pendingImage = "data:image/jpeg;base64," +
                        android.util.Base64.encodeToString(bos.toByteArray(), android.util.Base64.NO_WRAP)
                }
            }
        }
    }

    // 上下文用量粗估（中英混合约 2 字符/token），随消息增长即时更新
    val contextTokens = remember(rows) {
        val chars = rows.sumOf { it.text.length } + 3000
        chars / 2
    }

    Box(Modifier.fillMaxSize()) {
    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            Box(Modifier.fillMaxSize()) {
                GlassPanel(
                    backdrop = backdrop,
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(0.85f),
                    surfaceAlpha = 0.18f,
                    // 贴屏幕左缘：左上/左下不做圆角，保证与边缘齐平的折射观感
                    shape = RoundedCornerShape(
                        topStart = 0.dp,
                        topEnd = 28.dp,
                        bottomEnd = 28.dp,
                        bottomStart = 0.dp
                    ),
                    // 关闭 lens 折射：方角处其采样内边距会产生弧形高光，形成"伪圆角"
                    lensRadius = 0.dp
                ) {
                    SessionsDrawer(
                    agentName = vm.agentName(),
                    workspaceName = vm.workspaceName(),
                    backdrop = backdrop,
                    sessions = sessions,
                    activeId = vm.session.collectAsState().value?.id,
                    onSelect = {
                        vm.selectSession(it.id)
                        scope.launch { drawerState.close() }
                    },
                    onDelete = { vm.deleteSession(it.id) },
                    onNewChat = {
                        vm.newSession()
                        scope.launch { drawerState.close() }
                    },
                    onSettings = {
                        scope.launch { drawerState.close() }
                        onOpenSettings(true)
                    }
                )
                }
            }
        }
    ) {
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .appLayer(backdrop)
        ) {
            Spacer(
                Modifier
                    .statusBarsPadding()
                    .height(78.dp)
            )
            MessageList(
                rows = rows,
                streamingText = streaming,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                bottomPadding = 132.dp
            )
        }

        // 玻璃顶栏必须放在 appLayer 子树之外，否则层采样自引用会导致渲染循环崩溃
        val activeId = vm.session.collectAsState().value?.id
        TopBar(
            title = vm.agentName(),
            subtitle = vm.workspaceName(),
            contextTokens = contextTokens,
            backdrop = backdrop,
            sessions = sessions,
            activeId = activeId,
            onDrawer = { openDrawer() },
            onNewChat = {
                vm.newSession()
                scope.launch { drawerState.close() }
            },
            onSelectSession = { s ->
                vm.selectSession(s.id)
                scope.launch { drawerState.close() }
            },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 6.dp)
        )

        ComposerBar(
            backdrop = backdrop,
            text = input,
            onTextChange = { input = it },
            running = running,
            pendingImage = pendingImage,
            onPickImage = { imagePicker.launch("image/*") },
            onClearImage = { pendingImage = null },
            onSend = {
                vm.send(input, pendingImage)
                input = ""
                pendingImage = null
            },
            onStop = { vm.stop() },
            placeholder = "给 ${vm.agentName()} 派个活…",
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 14.dp, vertical = 10.dp)
        )

        error?.let { msg ->
            Snackbar(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(12.dp),
                action = {
                    TextButton(onClick = { vm.dismissError() }) { Text("关闭") }
                }
            ) { Text(msg, maxLines = 4) }
        }
    }
    }

    approval?.let { (req, _) ->
        // 审批弹窗改为液态玻璃卡片，叠在主窗口内（独立 Dialog 窗口无法采样 LayerBackdrop）
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.32f))
                .clickable(interactionSource = null, indication = null) { vm.onDeny() },
            contentAlignment = Alignment.Center
        ) {
            GlassPanel(
                backdrop = backdrop,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                radius = 28.dp,
                surfaceAlpha = 0.34f
            ) {
                Column(
                    Modifier
                        .clickable(interactionSource = null, indication = null) {}
                        .padding(20.dp)
                ) {
                    Text(
                        req.title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        req.detail,
                        fontFamily = if (req.mono) FontFamily.Monospace else null,
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.82f),
                        modifier = Modifier
                            .padding(top = 10.dp)
                            .heightIn(max = 340.dp)
                            .verticalScroll(rememberScrollState())
                    )
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 18.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = { vm.onDeny() }) { Text("拒绝") }
                        LiquidGlassButton(
                            onClick = { vm.onApprove() },
                            backdrop = backdrop,
                            shape = RoundedCornerShape(percent = 50),
                            surfaceColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
                        ) {
                            Text(
                                "允许",
                                color = MaterialTheme.colorScheme.onPrimary,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
            }
        }
    }
    }
}

@Composable
private fun TopBar(
    title: String,
    subtitle: String,
    contextTokens: Int,
    backdrop: com.kyant.backdrop.backdrops.LayerBackdrop,
    sessions: List<StoredSession>,
    activeId: String?,
    onDrawer: () -> Unit,
    onNewChat: () -> Unit,
    onSelectSession: (StoredSession) -> Unit,
    modifier: Modifier = Modifier
) {
    var quickMenu by remember { mutableStateOf(false) }
    val fmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
    GlassPanel(
        backdrop = backdrop,
        modifier = modifier.fillMaxWidth(),
        radius = 24.dp,
        surfaceAlpha = 0.14f
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onDrawer) {
                Icon(Icons.Filled.Menu, contentDescription = "会话列表", tint = MaterialTheme.colorScheme.onBackground)
            }
            Spacer(Modifier.size(2.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "$subtitle · ~$contextTokens tok",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
            IconButton(onClick = onNewChat) {
                Icon(Icons.Filled.Add, contentDescription = "新会话", tint = MaterialTheme.colorScheme.onBackground)
            }
            Box {
                IconButton(onClick = { quickMenu = true }) {
                    Icon(
                        Icons.Filled.ExpandMore,
                        contentDescription = "切换会话",
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }
                DropdownMenu(
                    expanded = quickMenu,
                    onDismissRequest = { quickMenu = false },
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    if (sessions.isEmpty()) {
                        DropdownMenuItem(
                            text = { Text("暂无历史会话", style = MaterialTheme.typography.bodySmall) },
                            onClick = {}
                        )
                    }
                    sessions.take(8).forEach { s ->
                        DropdownMenuItem(
                            text = {
                                Column(Modifier.padding(vertical = 2.dp)) {
                                    Text(
                                        s.title,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (s.id == activeId) FontWeight.Bold else FontWeight.Normal,
                                        color = if (s.id == activeId) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1
                                    )
                                    Text(
                                        "${fmt.format(Date(s.updatedAt))} · ${s.messages.size} 条",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            },
                            onClick = {
                                quickMenu = false
                                onSelectSession(s)
                            }
                        )
                    }
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    DropdownMenuItem(
                        text = { Text("查看全部会话") },
                        leadingIcon = { Icon(Icons.Filled.Menu, contentDescription = null, modifier = Modifier.size(19.dp)) },
                        onClick = {
                            quickMenu = false
                            onDrawer()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageList(
    rows: List<ChatRow>,
    streamingText: String?,
    modifier: Modifier = Modifier,
    bottomPadding: androidx.compose.ui.unit.Dp
) {
    val listState = rememberLazyListState()
    val totalItems = rows.size + (if (streamingText != null) 1 else 0)

    LaunchedEffect(totalItems, rows.lastOrNull()?.text?.length, streamingText?.length) {
        if (totalItems <= 0) return@LaunchedEffect
        val last = totalItems - 1
        val info = listState.layoutInfo
        val lastVisible = info.visibleItemsInfo.lastOrNull() ?: return@LaunchedEffect
        // 用户贴近底部时才跟随新内容（滚动到消息底部而非顶部）；上滑阅读时不打扰
        val following = lastVisible.index >= last - 1 &&
            (lastVisible.offset + lastVisible.size) - info.viewportEndOffset < 400
        if (following) {
            listState.scrollToItem(last, info.viewportEndOffset)
        }
    }

    LazyColumn(state = listState, modifier = modifier, contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = bottomPadding)) {
        items(rows, key = { it.key }) { row -> RowItem(row) }
        if (streamingText != null) {
            item(key = "streaming") {
                StreamingBubble(streamingText)
            }
        }
    }
}

@Composable
private fun RowItem(row: ChatRow) {
    when (row.role) {
        "user" -> UserBubble(row.text)
        else -> AssistantBlock(row)
    }
}

@Composable
private fun UserBubble(text: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.End
    ) {
        Surface(
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.20f),
            shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 5.dp),
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp)
            )
        }
    }
}

@Composable
private fun AssistantBlock(row: ChatRow) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 5.dp)
    ) {
        row.tools.forEach { tool -> ToolChip(tool) }
        if (row.text.isNotBlank()) {
            if (row.error) {
                Surface(
                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.14f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        row.text,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(10.dp)
                    )
                }
            } else {
                // 注意：不能用 GlassPanel（drawBackdrop）——消息在 appLayer 子树内，
                // 层采样自引用会触发 hwui 渲染树循环崩溃；用高透 Surface 模拟磨砂
                Surface(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.62f),
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    MarkdownText(
                        row.text,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun StreamingBubble(text: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 5.dp)
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.62f),
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            MarkdownText(
                text + " ▍",
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
            )
        }
    }
}

@Composable
private fun ToolChip(tool: com.haoai.agent.ui.UiTool) {
    var expanded by rememberSaveable(tool.callId) { mutableStateOf(false) }
    val stateColor = when (tool.state) {
        ToolRunState.RUNNING -> MaterialTheme.colorScheme.primary
        ToolRunState.DONE -> Color(0xFF7BD88F)
        ToolRunState.ERROR -> MaterialTheme.colorScheme.error
        ToolRunState.DENIED -> Color(0xFFFFC46B)
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
        shape = RoundedCornerShape(13.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 5.dp)
            .clickable { expanded = !expanded }
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                when (tool.state) {
                    ToolRunState.RUNNING -> CircularProgressIndicator(
                        modifier = Modifier.size(13.dp),
                        strokeWidth = 1.8.dp,
                        color = stateColor
                    )
                    else -> Box(
                        Modifier
                            .size(9.dp)
                            .background(stateColor, CircleShape)
                    )
                }
                Spacer(Modifier.size(8.dp))
                Text(
                    tool.name,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    tool.brief,
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
            AnimatedVisibility(expanded) {
                Column(Modifier.padding(top = 6.dp)) {
                    Text(
                        tool.preview ?: "(等待结果)",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.5.sp,
                        lineHeight = 16.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun ComposerBar(
    backdrop: com.kyant.backdrop.backdrops.LayerBackdrop,
    text: String,
    onTextChange: (String) -> Unit,
    running: Boolean,
    pendingImage: String?,
    onPickImage: () -> Unit,
    onClearImage: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    placeholder: String = "给 HaoAI 派个活…",
    modifier: Modifier = Modifier
) {
    GlassPanel(
        backdrop = backdrop,
        radius = 26.dp,
        surfaceAlpha = 0.30f,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(top = 4.dp)) {
            if (pendingImage != null) {
                Row(
                    Modifier.padding(horizontal = 18.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.Image,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        "已附加图片（发送后由端侧视觉模型识别）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 6.dp),
                        maxLines = 1
                    )
                    IconButton(onClick = onClearImage, modifier = Modifier.size(30.dp)) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "移除图片",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
            Row(
                Modifier.padding(start = 10.dp, end = 6.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onPickImage, enabled = !running) {
                    Icon(
                        Icons.Filled.AddPhotoAlternate,
                        contentDescription = "添加图片",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                androidx.compose.foundation.text.BasicTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier.weight(1f),
                textStyle = androidx.compose.ui.text.TextStyle(
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 15.sp,
                    lineHeight = 21.sp
                ),
                maxLines = 5,
                cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                decorationBox = { inner ->
                    Box(Modifier.padding(vertical = 12.dp)) {
                        if (text.isEmpty()) {
                            Text(
                                placeholder,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 15.sp
                            )
                        }
                        inner()
                    }
                }
            )
            val actionable = running || text.isNotBlank() || pendingImage != null
            LiquidGlassButton(
                onClick = { if (running) onStop() else onSend() },
                backdrop = backdrop,
                modifier = Modifier.size(46.dp),
                surfaceColor = MaterialTheme.colorScheme.primary.copy(alpha = if (actionable) 0.85f else 0.25f)
            ) {
                Icon(
                    if (running) Icons.Filled.Stop else Icons.AutoMirrored.Filled.Send,
                    contentDescription = if (running) "停止" else "发送",
                    tint = if (actionable) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary
                )
            }
        }
        }
    }
}

@Composable
private fun SessionsDrawer(
    agentName: String,
    workspaceName: String,
    backdrop: com.kyant.backdrop.backdrops.LayerBackdrop,
    sessions: List<StoredSession>,
    activeId: String?,
    onSelect: (StoredSession) -> Unit,
    onDelete: (StoredSession) -> Unit,
    onNewChat: () -> Unit,
    onSettings: () -> Unit
) {
    val fmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
    Column(
        Modifier
            .fillMaxSize()
            .padding(top = 18.dp)
    ) {
        Row(
            Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(40.dp)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    agentName.take(1).ifEmpty { "AI" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(agentName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    workspaceName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
        Button(
            onClick = onNewChat,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(6.dp))
            Text("开启新会话")
        }
        HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
        if (sessions.isEmpty()) {
            Text(
                "还没有历史会话",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
            )
        }
        LazyColumn(Modifier.weight(1f)) {
            items(sessions, key = { it.id }) { s ->
                val active = s.id == activeId
                GlassCard(
                    onClick = { onSelect(s) },
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
                                color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1
                            )
                            Text(
                                "${fmt.format(Date(s.updatedAt))} · ${s.messages.size} 条",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { onDelete(s) }, modifier = Modifier.size(34.dp)) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = "删除",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(17.dp)
                            )
                        }
                    }
                }
            }
        }
        HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onSettings)
                .padding(horizontal = 22.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.Settings,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(21.dp)
            )
            Spacer(Modifier.size(14.dp))
            Text("设置", style = MaterialTheme.typography.bodyLarge)
        }
        Spacer(Modifier.navigationBarsPadding())
    }
}
