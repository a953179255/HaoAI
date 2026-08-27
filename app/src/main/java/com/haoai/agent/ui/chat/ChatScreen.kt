package com.haoai.agent.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
    listState: androidx.compose.foundation.lazy.LazyListState =
        androidx.compose.foundation.lazy.rememberLazyListState(),
    onOpenSettings: (fromDrawer: Boolean) -> Unit
) {
    val context = LocalContext.current

    val rows by vm.rows.collectAsState()
    val streaming by vm.streamingText.collectAsState()
    val streamingReasoning by vm.streamingReasoning.collectAsState()
    val running by vm.running.collectAsState()
    val error by vm.error.collectAsState()
    val sessions by vm.sessions.collectAsState()
    val deletedSessions by vm.deletedSessions.collectAsState()
    val approval by vm.approval.collectAsState()

    var input by rememberSaveable { mutableStateOf("") }
    var pendingImage by rememberSaveable { mutableStateOf<String?>(null) }

    val scope = rememberCoroutineScope()
    fun openDrawer() = scope.launch { drawerState.open() }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val density = LocalDensity.current
    val imeHeightPx = WindowInsets.ime.getBottom(density)

    // 系统返回手势：侧边栏展开时先收起侧边栏，而不是把应用最小化
    androidx.activity.compose.BackHandler(enabled = drawerState.currentValue == DrawerValue.Open) {
        scope.launch { drawerState.close() }
    }

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

    // 上下文使用量（来自 ViewModel 实时估算）
    val contextUsage by vm.contextUsage.collectAsState()

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
                    deletedSessions = deletedSessions,
                    activeId = vm.session.collectAsState().value?.id,
                    onSelect = {
                        vm.selectSession(it.id)
                        scope.launch { drawerState.close() }
                    },
                    onDelete = { vm.deleteSession(it.id) },
                    onRestoreSession = { vm.restoreSession(it) },
                    onDeleteForever = { vm.deleteSessionForever(it) },
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
                .graphicsLayer { translationY = -imeHeightPx.toFloat() }
                .appLayer(backdrop)
                .pointerInput(Unit) {
                    detectTapGestures {
                        focusManager.clearFocus()
                    }
                }
        ) {
            Spacer(
                Modifier
                    .statusBarsPadding()
                    .height(78.dp)
            )
            MessageList(
                rows = rows,
                streamingText = streaming,
                streamingReasoning = streamingReasoning,
                running = running,
                listState = listState,
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
            contextUsage = contextUsage,
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
                .graphicsLayer { translationY = -imeHeightPx.toFloat() }
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
                .background(Color.Black.copy(alpha = 0.18f))
                .clickable(interactionSource = null, indication = null) { vm.onDeny() },
            contentAlignment = Alignment.Center
        ) {
            GlassPanel(
                backdrop = backdrop,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                radius = 28.dp,
                surfaceAlpha = 0.82f,
                blurRadius = 20.dp,
                chromaticAberration = true
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
    contextUsage: ContextUsage,
    backdrop: com.kyant.backdrop.backdrops.LayerBackdrop,
    sessions: List<StoredSession>,
    activeId: String?,
    onDrawer: () -> Unit,
    onNewChat: () -> Unit,
    onSelectSession: (StoredSession) -> Unit,
    modifier: Modifier = Modifier
) {
    var quickMenu by remember { mutableStateOf(false) }
    var showContextDetail by remember { mutableStateOf(false) }
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
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
            CircularContextIndicator(
                usage = contextUsage,
                onClick = { showContextDetail = !showContextDetail }
            )
            Spacer(Modifier.size(2.dp))
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
                // 会话快切面板：液态玻璃（DropdownMenu 无法承载玻璃材质）
                if (quickMenu) {
                    androidx.compose.ui.window.Popup(
                        alignment = Alignment.BottomEnd,
                        onDismissRequest = { quickMenu = false },
                        properties = androidx.compose.ui.window.PopupProperties(focusable = true)
                    ) {
                        GlassPanel(
                            backdrop = backdrop,
                            modifier = Modifier
                                .padding(top = 6.dp)
                                .widthIn(min = 240.dp, max = 320.dp),
                            radius = 20.dp,
                            surfaceAlpha = 0.52f
                        ) {
                            Column(
                                Modifier
                                    .heightIn(max = 420.dp)
                                    .verticalScroll(rememberScrollState())
                                    .padding(vertical = 6.dp)
                            ) {
                                if (sessions.isEmpty()) {
                                    Text(
                                        "暂无历史会话",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                                    )
                                }
                                sessions.take(8).forEach { s ->
                                    Column(
                                        Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                quickMenu = false
                                                onSelectSession(s)
                                            }
                                            .padding(horizontal = 16.dp, vertical = 7.dp)
                                    ) {
                                        Text(
                                            s.title,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = if (s.id == activeId) FontWeight.Bold else FontWeight.Normal,
                                            color = if (s.id == activeId) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onBackground,
                                            maxLines = 1
                                        )
                                        Text(
                                            "${fmt.format(Date(s.updatedAt))} · ${s.messages.size} 条",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                HorizontalDivider(
                                    Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.18f)
                                )
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            quickMenu = false
                                            onDrawer()
                                        }
                                        .padding(horizontal = 16.dp, vertical = 9.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Filled.Menu,
                                        contentDescription = null,
                                        modifier = Modifier.size(19.dp),
                                        tint = MaterialTheme.colorScheme.onBackground
                                    )
                                    Spacer(Modifier.size(10.dp))
                                    Text("查看全部会话", style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                }
            }
        }
        // 上下文详情弹窗
        if (showContextDetail) {
            androidx.compose.ui.window.Popup(
                alignment = Alignment.BottomEnd,
                onDismissRequest = { showContextDetail = false },
                properties = androidx.compose.ui.window.PopupProperties(focusable = true)
            ) {
                ContextUsageDetailPopup(
                    usage = contextUsage,
                    backdrop = backdrop,
                    modifier = Modifier.padding(end = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun MessageList(
    rows: List<ChatRow>,
    streamingText: String?,
    streamingReasoning: String?,
    running: Boolean,
    listState: androidx.compose.foundation.lazy.LazyListState,
    modifier: Modifier = Modifier,
    bottomPadding: androidx.compose.ui.unit.Dp
) {
    val showStreaming = streamingText != null || running
    val totalItems = rows.size + (if (showStreaming) 1 else 0)

    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    // 滑动列表时收起输入法
    LaunchedEffect(listState) {
        androidx.compose.runtime.snapshotFlow { listState.isScrollInProgress }
            .collect { if (it) focusManager.clearFocus() }
    }

    // 自动滚底：用户发送新消息时无条件滚底；流式内容仅在用户位于底部附近时跟随
    LaunchedEffect(totalItems, rows.lastOrNull()?.text?.length, streamingText?.length, streamingReasoning?.length) {
        if (totalItems <= 0) return@LaunchedEffect
        val last = totalItems - 1
        val info = listState.layoutInfo
        val lastVisible = info.visibleItemsInfo.lastOrNull() ?: return@LaunchedEffect
        val isNearBottom = lastVisible.index >= last - 1 &&
            (lastVisible.offset + lastVisible.size) - info.viewportEndOffset < 200
        // 用户发送新消息（最后一条是 user）→ 强制滚底；否则仅在底部附近时跟随
        val isUserMessage = rows.lastOrNull()?.role == "user"
        if (isUserMessage || isNearBottom) {
            // animateScrollToItem 会等待 item 布局完成再滚动，避免 layout race
            listState.animateScrollToItem(last)
        }
    }

    LazyColumn(state = listState, modifier = modifier, contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = bottomPadding)) {
        items(rows, key = { it.key }) { row -> RowItem(row) }
        if (showStreaming) {
            item(key = "streaming") {
                StreamingItem(streamingText, streamingReasoning)
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

/** 思考中指示器：模型还在 prefill / 推理、尚未吐出正文时给用户明确反馈。 */
@Composable
private fun ThinkingIndicator(text: String = "正在思考") {
    val transition = rememberInfiniteTransition(label = "think")
    val dotAlpha by transition.animateFloat(
        initialValue = 0.2f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(850, easing = LinearEasing)),
        label = "dots"
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(
            modifier = Modifier.size(14.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.size(9.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.size(3.dp))
        Text(
            "···",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.graphicsLayer { this.alpha = dotAlpha }
        )
    }
}

/**
 * 思考过程面板：流式阶段（live）默认展开实时滚动显示推理内容；
 * 正文开始后自动收起；历史消息里默认收起，点击可展开。
 */
@Composable
private fun ReasoningPanel(text: String, live: Boolean, autoCollapse: Boolean = false) {
    var userToggled by rememberSaveable { mutableStateOf(false) }
    var expanded by rememberSaveable { mutableStateOf(live) }
    // 正文开始输出时自动收起（除非用户手动展开过）
    LaunchedEffect(autoCollapse, live) {
        if (autoCollapse && !userToggled) expanded = false
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.34f),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable { userToggled = true; expanded = !expanded }
    ) {
        Column(Modifier.padding(vertical = 7.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Spacer(Modifier.size(12.dp))
                if (live) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 1.6.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.size(8.dp))
                }
                Text(
                    if (live) "正在思考…" else "思考过程",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.weight(1f))
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(17.dp)
                )
                Spacer(Modifier.size(12.dp))
            }
            androidx.compose.animation.AnimatedVisibility(expanded) {
                SelectionContainer {
                    Text(
                        text,
                        style = MaterialTheme.typography.bodySmall,
                        lineHeight = 17.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.92f),
                        modifier = Modifier.padding(horizontal = 12.dp).padding(top = 5.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun StreamingItem(streamingText: String?, streamingReasoning: String?) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 5.dp)
    ) {
        val hasContent = !streamingText.isNullOrBlank()
        if (!streamingReasoning.isNullOrBlank()) {
            ReasoningPanel(
                text = streamingReasoning,
                live = !hasContent,
                autoCollapse = hasContent
            )
            if (hasContent) Spacer(Modifier.size(5.dp))
        }
        if (hasContent) {
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.62f),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                SelectionContainer {
                    MarkdownText(
                        streamingText + " ▍",
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                    )
                }
            }
        } else if (streamingReasoning.isNullOrBlank()) {
            // 什么都还没有：prefill / 等首 token
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.62f),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                    ThinkingIndicator()
                }
            }
        }
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
            SelectionContainer {
                Text(
                    text,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp)
                )
            }
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
        row.reasoning?.takeIf { it.isNotBlank() }?.let {
            ReasoningPanel(text = it, live = false)
            Spacer(Modifier.size(5.dp))
        }
        row.tools.forEach { tool -> ToolChip(tool) }
        if (row.text.isNotBlank()) {
            if (row.error) {
                Surface(
                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.14f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    SelectionContainer {
                        Text(
                            row.text,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }
            } else {
                // 注意：不能用 GlassPanel（drawBackdrop）——消息在 appLayer 子树内，
                // 层采样自引用会触发 hwui 渲染树循环崩溃；用高透 Surface 模拟磨砂
                Surface(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.62f),
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    SelectionContainer {
                        MarkdownText(
                            row.text,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                        )
                    }
                }
            }
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
            .clip(RoundedCornerShape(13.dp))
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
    deletedSessions: List<StoredSession>,
    activeId: String?,
    onSelect: (StoredSession) -> Unit,
    onDelete: (StoredSession) -> Unit,
    onRestoreSession: (String) -> Unit,
    onDeleteForever: (String) -> Unit,
    onSettings: () -> Unit
) {
    val fmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
    var showTrash by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(top = 8.dp)
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
                .clickable(onClick = { showTrash = true })
                .padding(horizontal = 22.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.DeleteSweep,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(21.dp)
            )
            Spacer(Modifier.size(14.dp))
            Text("回收站", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            if (deletedSessions.isNotEmpty()) {
                Text(
                    "${deletedSessions.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .background(
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.14f),
                            CircleShape
                        )
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
        }
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

    // 回收站：恢复 / 彻底删除，7 天后自动清理
    if (showTrash) {
        com.haoai.agent.ui.common.GlassAlertDialog(
            backdrop = backdrop,
            title = "回收站",
            onDismiss = { showTrash = false },
            dismissLabel = "关闭"
        ) {
            Column {
                if (deletedSessions.isEmpty()) {
                    Text(
                        "回收站是空的。删除的会话会在这里保留 7 天，之后自动清理。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Column(
                        Modifier
                            .heightIn(max = 380.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        deletedSessions.forEach { s ->
                            val daysLeft = 7 - ((System.currentTimeMillis() - s.deletedAt) / (24 * 60 * 60 * 1000L))
                            Column(Modifier.padding(vertical = 6.dp)) {
                                Text(s.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                                Text(
                                    "${fmt.format(Date(s.deletedAt))} 删除 · 剩 $daysLeft 天自动清理 · ${s.messages.size} 条",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(onClick = {
                                        onRestoreSession(s.id)
                                    }) { Text("恢复") }
                                    TextButton(onClick = {
                                        onDeleteForever(s.id)
                                    }) { Text("彻底删除", color = MaterialTheme.colorScheme.error) }
                                }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.10f))
                        }
                    }
                }
            }
        }
    }
}
