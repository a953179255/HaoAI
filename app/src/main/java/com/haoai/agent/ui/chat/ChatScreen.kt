package com.haoai.agent.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.positionChangeConsumed
import androidx.compose.ui.layout.onSizeChanged
import android.widget.Toast
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.unit.IntOffset
import kotlin.math.abs
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBars
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Construction
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.animation.animateContentSize
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Web
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.haoai.agent.agent.engine.ToolRunState
import com.haoai.agent.data.StoredSession
import com.haoai.agent.ui.ChatRow
import com.haoai.agent.ui.ChatViewModel
import com.haoai.agent.ui.common.GlassCard
import com.haoai.agent.ui.common.GlassPanel
import com.haoai.agent.ui.common.LiquidGlassButton
import com.haoai.agent.ui.common.MarkdownText
import com.haoai.agent.ui.common.SwipeRevealCard
import com.haoai.agent.ui.common.appLayer
import com.haoai.agent.ui.theme.wallpaperAdaptiveGray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * 轻量抽屉控制器：0..1 fraction 驱动布局期平移（Modifier.offset，不创建离屏层）。
 * 不用 ModalNavigationDrawer——其 sheet 的 graphicsLayer 平移动画会被玻璃 backdrop
 * 采样滞后回画，关抽屉时卡片四角闪直角残影；且动画期间禁折射又会造成暗→亮跳变。
 *
 * 支持跟手拖动（dragTo/snapTo）与松手速度判定（settle）——上游/上游 式丝滑：
 * 手势期间每帧 snapTo 跟手，松手按当前位置+速度决定开或关，spring 带轻微回弹。
 */
class DrawerController {
    var targetOpen by androidx.compose.runtime.mutableStateOf(false)
    val fraction = androidx.compose.animation.core.Animatable(0f)
    val isOpen: Boolean get() = targetOpen

    /**
     * 转场冻结标记：true 时 ChatScreen 绘制层改为输出最近记录的 GraphicsLayer
     * 快照（组合照跑、绘制被静态图像替代）。聊天页是全 App 最重的页面
     * （抽屉+遮罩+壁纸+LazyColumn），push 到设置时首帧重组慢曾被看穿成
     * 「残留/不同步」；快照让转场期滑动的只是一张图，重组延迟被彻底隔离。
     * 退出转场后必须置回 false，否则聊天页永远显示旧画面。
     */
    var frozen by androidx.compose.runtime.mutableStateOf(false)

    /** 打开态弹性参数：中低刚度+轻微回弹（damping 0.85），有「果冻到位」感而不狂振荡 */
    private fun settleSpec() = androidx.compose.animation.core.spring(
        dampingRatio = 0.85f,
        stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow,
        visibilityThreshold = 0.001f
    )

    suspend fun open() {
        targetOpen = true
        fraction.animateTo(1f, settleSpec(), fraction.velocity)
    }

    /** 无动画直接以展开态出现：设置返回聊天并恢复侧边栏时用，避免与页面过渡叠加成两段动画 */
    suspend fun snapOpen() {
        targetOpen = true
        fraction.snapTo(1f)
    }

    suspend fun close() {
        targetOpen = false
        fraction.animateTo(0f, settleSpec(), fraction.velocity)
    }

    /** 跟手：直接把 fraction 钉到 v（0..1），无动画。 */
    suspend fun dragTo(v: Float) {
        targetOpen = v > 0.5f
        fraction.snapTo(v.coerceIn(0f, 1f))
    }

    /**
     * 松手结算：按当前位置与速度决定终点——
     * 速度超过 0.35/s 直接顺着方向；否则看位置过半没。返回实际执行的动画是否为打开。
     */
    suspend fun settle(velocityFraction: Float): Boolean {
        val shouldOpen = when {
            velocityFraction > 0.35f -> true
            velocityFraction < -0.35f -> false
            else -> fraction.value > 0.5f
        }
        if (shouldOpen) open() else close()
        return shouldOpen
    }
}

@Composable
fun ChatScreen(
    vm: ChatViewModel,
    backdrop: com.kyant.backdrop.backdrops.LayerBackdrop,
    drawer: DrawerController = remember { DrawerController() },
    listState: androidx.compose.foundation.lazy.LazyListState =
        androidx.compose.foundation.lazy.rememberLazyListState(),
    onOpenSettings: () -> Unit,
    onOpenSessions: () -> Unit = {},
    onOpenBrowser: () -> Unit = {},
    onOpenVscreen: () -> Unit = {}
) {
    val context = LocalContext.current

    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    fun copyText(text: String) {
        clipboard.setText(AnnotatedString(text))
        Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
    }

    val rows by vm.rows.collectAsState()
    val streaming by vm.streamingText.collectAsState()
    val streamingReasoning by vm.streamingReasoning.collectAsState()
    val thinkingMs by vm.thinkingMs.collectAsState()
    val running by vm.running.collectAsState()
    val liveToolsSnapshot by vm.liveToolsSnapshotFlow.collectAsState()
    val error by vm.error.collectAsState()
    val sessions by vm.sessions.collectAsState()
    val deletedSessions by vm.deletedSessions.collectAsState()
    val settings by vm.settings.collectAsState()

    // ⑧ 回到底部浮钮状态：粘滞标志提升到 ChatScreen 级（MessageList 内维护、
    // 浮钮画在 appLayer 采样层之外——Box 包裹会破坏 drawBackdrop 采样链，
    // 与「玻璃顶栏不能进 appLayer 子树」同理）。用 MutableState 对象（非 by 委托）
    // 以便同时传给 MessageList 读写与浮钮读取
    val userScrolledAway = remember { mutableStateOf(false) }
    // 程序化滚动标志（MessageList 的跟随/补滚与浮钮回底共用）
    val scrollGuard = remember { mutableStateOf(false) }
    // v4-4 回底徽标：断开瞬间拍 baseline 快照（行数+流式长度），
    // 只统计之后的真增量——上滑动作本身不再被误计为「1 条新动态」。
    val newContentTicker = remember { mutableStateOf(0) }
    var contentBaseline by remember { mutableStateOf(0L) }
    // ① 断开/恢复跟随时重拍基线
    LaunchedEffect(userScrolledAway.value) {
        if (userScrolledAway.value) {
            contentBaseline = rows.size.toLong() * 1_000_000L + (streaming?.length ?: 0)
            newContentTicker.value = 0
        }
    }
    // ② 断开期间内容变化：与基线求差（行数变化记 1 条，流式增长 400 字记 1 条）
    LaunchedEffect(rows.size, streaming?.length) {
        if (!userScrolledAway.value) return@LaunchedEffect
        val now = rows.size.toLong() * 1_000_000L + (streaming?.length ?: 0)
        val deltaRows = rows.size - (contentBaseline / 1_000_000L).toInt()
        if (deltaRows > 0) {
            newContentTicker.value = deltaRows
        } else {
            // 行数没变但流式在长：按 400 字一条粗算
            val deltaChars = now - contentBaseline
            if (deltaChars > 0) newContentTicker.value = (deltaChars / 400).toInt().coerceAtLeast(1)
        }
    }
    val approval by vm.approval.collectAsState()
    val todoItems by vm.todoItems.collectAsState()
    val planMode by vm.planMode.collectAsState()
    val planProposal by vm.planProposal.collectAsState()

    var input by rememberSaveable { mutableStateOf("") }
    // 大体积 base64 / 文档正文绝不能进 rememberSaveable：会被写入 savedInstanceState
    // Bundle，超过 ~1MB 直接 TransactionTooLargeException 崩溃；进程重建丢失待发附件可接受
    var pendingImage by remember { mutableStateOf<String?>(null) }
    // 音频/视频附件本机路径（应用私有 attachments 目录）——base64 不进会话 JSON 防膨胀；
    // 引擎按当前模型能力决定直发（audio-in）或注记绕行（ffmpeg/ASR）
    var pendingAudioPath by remember { mutableStateOf<String?>(null) }
    var pendingAudioName by remember { mutableStateOf<String?>(null) }
    var pendingVideoPath by remember { mutableStateOf<String?>(null) }
    var pendingVideoName by remember { mutableStateOf<String?>(null) }
    // 相机临时文件：filesDir 下（file_paths.xml 的 internal_files 已覆盖）
    val cameraShotFile = java.io.File(context.filesDir, "camera_shot.jpg")
    var pendingDocumentName by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingDocumentContent by remember { mutableStateOf<String?>(null) }
    // 消息长按操作组（1.2）：目标消息 / 编辑重发草稿 / 删除确认
    var msgAction by remember { mutableStateOf<ChatRow?>(null) }
    // 网页渲染预览目标（⋮ 菜单进入）
    var previewTarget by remember { mutableStateOf<ChatRow?>(null) }
    var editTarget by remember { mutableStateOf<ChatRow?>(null) }
    var deleteTarget by remember { mutableStateOf<ChatRow?>(null) }
    // 工具卡"查看变更"（1.3）：从写前快照现算 diff
    var diffViewer by remember { mutableStateOf<Pair<String, List<com.haoai.agent.ui.common.DiffLine>>?>(null) }
    val snapshotScope = rememberCoroutineScope()

    val scope = rememberCoroutineScope()
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val view = androidx.compose.ui.platform.LocalView.current

    // 软键盘悬浮在 adjustNothing 窗口上会直接盖住抽屉下半部，键盘底色被玻璃 backdrop 采进去
    // （表现为「侧边栏只有下半部分是灰色不透明」）——开抽屉前先收起 IME
    fun hideIme() {
        runCatching {
            (context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as?
                android.view.inputmethod.InputMethodManager)
                ?.hideSoftInputFromWindow(view.windowToken, 0)
        }
        focusManager.clearFocus()
    }
    fun openDrawer() = scope.launch { hideIme(); drawer.open() }
    val density = LocalDensity.current
    // 抽屉面板宽度（与 sheet 的 fillMaxWidth(0.72f) 一致）：跟手拖动时把 px 位移归一化为 fraction
    val drawerPanelWidthDp = (androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp * 0.72f).dp
    val imeHeightPx = WindowInsets.ime.getBottom(density)
    // 底部列自带 navigationBarsPadding，若按完整 ime 高度上移会多抬一个导航栏高度，形成键盘空隙
    val navBarPx = WindowInsets.navigationBars.getBottom(density)
    val keyboardLiftPx = (imeHeightPx - navBarPx).coerceAtLeast(0)
    // 消息列表顶部留白 = 状态栏 + 顶栏高度：列表物理延伸到玻璃顶栏下方（消息可滚入玻璃
    // 被磨砂遮住，主流聊天观感），仅用 contentPadding 保证初始首条消息停在顶栏下沿
    val topBarHeightDp = with(density) {
        WindowInsets.statusBars.getTop(density).toDp() + 60.dp
    }
    // 玻璃 effects 在「绘制期」读取这个 State 注册快照订阅：键盘动画每帧变更 →
    // backdrop 节点失效重绘 → 采样 offset 用最新布局坐标重算。effects 里读普通
    // Int（keyboardLiftPx 参数）不会注册订阅——这正是输入栏透出抬升前旧背景的根因
    val keyboardLiftState = remember { androidx.compose.runtime.mutableIntStateOf(0) }
    keyboardLiftState.intValue = keyboardLiftPx
    // 底部悬浮列（TaskPanel/斜杠弹层/输入框）实测高度，驱动消息列表动态底部留白
    var bottomBarHeightPx by remember { mutableStateOf(0) }

    // 斜杠命令状态
    var slashFilterQuery by remember { mutableStateOf("") }
    var slashMenuVisible by remember { mutableStateOf(false) }
    var showSlashHelp by remember { mutableStateOf(false) }
    var showStatusPopup by remember { mutableStateOf(false) }
    var showModelPicker by remember { mutableStateOf(false) }
    var showProfileEdit by remember { mutableStateOf(false) }
    // 顶栏会话名点击重命名
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf("") }
    // /task 强制展开任务面板（即使清单为空或已全部完成）
    var taskPanelExpanded by remember { mutableStateOf(false) }
    // /task 命令强制展开标记（无任务时也能看空态）
    var taskPanelForcedVisible by remember { mutableStateOf(false) }

    // 新任务清单到达（首条 id 变化）时自动展开一次顶栏任务面板
    LaunchedEffect(todoItems.firstOrNull()?.id) {
        if (todoItems.isNotEmpty()) taskPanelExpanded = true
    }

    // 系统返回手势：弹层优先关闭，其次侧边栏，避免把应用最小化
    androidx.activity.compose.BackHandler(enabled = showProfileEdit) { showProfileEdit = false }
    androidx.activity.compose.BackHandler(enabled = showRenameDialog) { showRenameDialog = false }
    androidx.activity.compose.BackHandler(enabled = showModelPicker) { showModelPicker = false }
    androidx.activity.compose.BackHandler(enabled = showSlashHelp) { showSlashHelp = false }
    androidx.activity.compose.BackHandler(enabled = showStatusPopup) { showStatusPopup = false }
    androidx.activity.compose.BackHandler(enabled = drawer.isOpen) {
        scope.launch { drawer.close() }
    }

    // ACTION_PICK 分发给默认图库（Flyme 图库等），结果 URI 自带临时读权限；
    // 原 GetContent() 走 SAF 文档选择器，界面是文件管理器而非图库（用户反馈）
    val imagePicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.data
        if (uri != null) {
            runCatching {
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

    // 系统分享接入（R2.2）：文本预填输入框；图片 Uri 走与相册选择相同的解码管线
    androidx.compose.runtime.LaunchedEffect(vm) {
        vm.shareText.collect { t ->
            if (t != null) {
                input = if (input.isBlank()) t else "$input\n$t"
                vm.shareText.value = null
            }
        }
    }
    androidx.compose.runtime.LaunchedEffect(vm) {
        vm.shareImageUri.collect { uriStr ->
            if (uriStr != null) {
                vm.shareImageUri.value = null
                runCatching {
                    val uri = android.net.Uri.parse(uriStr)
                    val resolver = context.contentResolver
                    resolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                runCatching {
                    val resolver = context.contentResolver
                    val uri = android.net.Uri.parse(uriStr)
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
    }

    // 拍照：动态权限 → TakePicture 到 FileProvider Uri → 采样解码压缩为 base64 附件
    val cameraLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.TakePicture()
    ) { ok ->
        if (ok) {
            runCatching {
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                java.io.FileInputStream(cameraShotFile).use {
                    android.graphics.BitmapFactory.decodeFileDescriptor(it.fd, null, bounds)
                }
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1024) sample *= 2
                val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
                val bmp = android.graphics.BitmapFactory.decodeFile(cameraShotFile.absolutePath, opts)
                if (bmp != null) {
                    val bos = java.io.ByteArrayOutputStream()
                    bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, bos)
                    bmp.recycle()
                    pendingImage = "data:image/jpeg;base64," +
                        android.util.Base64.encodeToString(bos.toByteArray(), android.util.Base64.NO_WRAP)
                }
                cameraShotFile.delete()
            }
        }
    }
    val launchCamera: () -> Unit = {
        runCatching {
            cameraShotFile.outputStream().close()
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context, context.packageName + ".fileprovider", cameraShotFile
            )
            cameraLauncher.launch(uri)
        }
    }
    val cameraPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) launchCamera() }
    val documentPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                val resolver = context.contentResolver
                val fileName = resolver.query(uri, null, null, null, null)?.use { c ->
                    val nameIndex = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    c.moveToFirst()
                    if (nameIndex >= 0) c.getString(nameIndex) else "document"
                } ?: "document"
                val mimeType = resolver.getType(uri) ?: "application/octet-stream"
                val isTextLike = mimeType.startsWith("text/") ||
                    mimeType == "application/json" ||
                    mimeType == "application/xml" ||
                    fileName.endsWith(".kt", true) ||
                    fileName.endsWith(".java", true) ||
                    fileName.endsWith(".py", true) ||
                    fileName.endsWith(".js", true) ||
                    fileName.endsWith(".ts", true) ||
                    fileName.endsWith(".html", true) ||
                    fileName.endsWith(".css", true) ||
                    fileName.endsWith(".json", true) ||
                    fileName.endsWith(".xml", true) ||
                    fileName.endsWith(".md", true) ||
                    fileName.endsWith(".txt", true) ||
                    fileName.endsWith(".csv", true) ||
                    fileName.endsWith(".log", true) ||
                    fileName.endsWith(".gradle", true) ||
                    fileName.endsWith(".yaml", true) ||
                    fileName.endsWith(".yml", true) ||
                    fileName.endsWith(".toml", true) ||
                    fileName.endsWith(".properties", true)
                if (isTextLike) {
                    val content = resolver.openInputStream(uri)?.use { it.bufferedReader().readText() }
                    if (content != null) {
                        pendingDocumentName = fileName
                        pendingDocumentContent = content
                    }
                } else {
                    pendingDocumentName = fileName
                    pendingDocumentContent = "[文件附件: $fileName ($mimeType)]"
                }
            }
        }
    }

    // 音频附件：复制到应用私有目录存本机路径（base64 不进会话 JSON 防膨胀）；
    // 模型有 audio-in 时引擎读取转 input_audio 直发，无则注记让 Agent 走 ASR/转写绕行
    val audioPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                val resolver = context.contentResolver
                val fileName = resolver.query(uri, null, null, null, null)?.use { c ->
                    val nameIndex = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    c.moveToFirst()
                    if (nameIndex >= 0) c.getString(nameIndex) else "audio.mp3"
                } ?: "audio.mp3"
                val dir = java.io.File(context.filesDir, "attachments").apply { mkdirs() }
                val dest = java.io.File(dir, System.currentTimeMillis().toString() + "_" + fileName.replace(Regex("[^A-Za-z0-9._-]"), "_"))
                resolver.openInputStream(uri)?.use { ins -> dest.outputStream().use { ins.copyTo(it) } }
                if (dest.exists() && dest.length() <= 8L * 1024 * 1024) {
                    pendingAudioPath = dest.absolutePath
                    pendingAudioName = fileName
                } else {
                    dest.delete()
                    vm.showError("音频过大（>8MB），请先用 shell 工具压缩或截取片段")
                }
            }.onFailure { vm.showError("读取音频失败：${it.message}") }
        }
    }

    // 视频附件：复制到应用私有目录存本机路径——chat 模型几乎不原生吃视频，
    // 引擎把路径注记给 Agent，由 shell ffmpeg 抽关键帧/抽音轨绕行（能力声明已告知）
    val videoPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                val resolver = context.contentResolver
                val fileName = resolver.query(uri, null, null, null, null)?.use { c ->
                    val nameIndex = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    c.moveToFirst()
                    if (nameIndex >= 0) c.getString(nameIndex) else "video.mp4"
                } ?: "video.mp4"
                val dir = java.io.File(context.filesDir, "attachments").apply { mkdirs() }
                val dest = java.io.File(dir, System.currentTimeMillis().toString() + "_" + fileName.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_"))
                resolver.openInputStream(uri)?.use { ins -> dest.outputStream().use { ins.copyTo(it) } }
                if (dest.exists()) {
                    pendingVideoPath = dest.absolutePath
                    pendingVideoName = fileName
                }
            }.onFailure { vm.showError("读取视频失败：${it.message}") }
        }
    }

    // 上下文使用量（来自 ViewModel 实时估算）
    val contextUsage by vm.contextUsage.collectAsState()
    // 上下文详情面板开关：状态提升到此处——面板在顶栏 Column 内展开（与 E1 横幅同列），
    // 触发点在 TopBar 的环形指示器，点消息区也要能关，故不能留在 TopBar 内部
    var showContextDetail by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
    val drawerFraction = drawer.fraction.value
    // 转场快照层：常态每帧把整页内容记录进 GraphicsLayer（record 只在有
    // 重绘时发生，无额外开销）；drawer.frozen=true（push 到设置转场中）时
    // 改为绘制上次记录的静态图像——重组照跑但绘制被快照替代，重页面
    // 组合延迟不再被看穿。转场结束 frozen 置回 false 恢复实时绘制
    val snapshotLayer = rememberGraphicsLayer()
    Box(
        Modifier
            .fillMaxSize()
            .drawWithContent {
                if (drawer.frozen) {
                    drawLayer(snapshotLayer)
                } else {
                    drawContent()
                    snapshotLayer.record { this@drawWithContent.drawContent() }
                }
            }
    ) {
        // 采样层根保持静止（无 offset/graphicsLayer 外层变换）：字节码确认采样 offset
        // = layerCoordinates.localPositionOf(glass, Zero)，任何外层平移都会整体错位。
        // 键盘抬升不再平移列表，改为增大 MessageList 底部留白（见 bottomPadding），
        // 顶部占位 Spacer 固定——消息永远从顶栏下方开始，不再顶入玻璃
        Column(
            Modifier
                .fillMaxSize()
                .appLayer(backdrop)
                .pointerInput(Unit) {
                    detectTapGestures {
                        focusManager.clearFocus()
                        // 点消息区收起上下文详情面板（原 Popup 的 outside-dismiss 语义）
                        showContextDetail = false
                    }
                }
                .pointerInput(Unit) {
                    // 左缘右滑开抽屉：跟手拖动（上游/上游 式）——越过 slop 且方向为右后
                    // 每帧把 fraction 钉到 位移/面板宽度，松手按位置+速度结算开或关
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        if (down.position.x < with(this) { 60.dp.toPx() }) {
                            var tx = 0f; var ty = 0f
                            var tracking = false
                            var startTime = 0L
                            while (true) {
                                val e = awaitPointerEvent()
                                val c = e.changes.firstOrNull { it.id == down.id } ?: break
                                tx += c.positionChange().x; ty += c.positionChange().y
                            if (!tracking) {
                                if (abs(tx) > viewConfiguration.touchSlop || abs(ty) > viewConfiguration.touchSlop) {
                                    if (abs(tx) > abs(ty) && tx > 0f) {
                                        tracking = true
                                        startTime = System.currentTimeMillis()
                                        hideIme()
                                        c.consume()
                                    } else break
                                }
                            } else {
                                // 位移按面板宽度归一化：用累计位移 tx（含 slop 前的量），
                                // 面板从 0 开始跟手；异步 launch 合并同帧多次调用
                                val panelPx = with(this) { drawerPanelWidthDp.toPx() }
                                val target = (tx / panelPx).coerceIn(0f, 1f)
                                scope.launch { drawer.dragTo(target) }
                                c.consume()
                            }
                                if (!c.pressed) {
                                    if (tracking) {
                                        val dt = ((System.currentTimeMillis() - startTime).coerceAtLeast(1)) / 1000f
                                        // 近似速度：总位移 / 总时长（touch 采样下的稳定估计）
                                        val v = (tx / (with(this) { drawerPanelWidthDp.toPx() })) / dt
                                        scope.launch { drawer.settle(v) }
                                    }
                                    break
                                }
                            }
                        }
                    }
                }
        ) {
            // 顶部不再占位：列表物理延伸到玻璃顶栏下方（含状态栏区域），
            // 初始首条消息位置由 MessageList 的 topPadding 保证
            MessageList(
                rows = rows,
                onOpenMenu = { msgAction = it },
                onCopyRow = { copyText(it.text) },
                onQuickRegenerate = { vm.regenerateFrom(it.id) },
                onQuickEdit = { editTarget = it },
                onToolViewDiff = { callId ->
                    snapshotScope.launch { diffViewer = vm.snapshotDiff(callId) }
                },
                onToolRollback = { callId ->
                    snapshotScope.launch {
                        val msg = vm.rollbackChange(callId)
                        if (msg != null) vm.showError(msg)
                    }
                },
                onStopRun = { vm.stop() },
                streamingText = streaming,
                streamingReasoning = streamingReasoning,
                running = running,
                thinkingMs = thinkingMs,
                thinkingHint = if (running && vm.isLocalProviderActive())
                    "端侧推理 · 正在理解上下文（需预处理全部提示词，可能数十秒）" else null,
                liveToolsSnapshot = liveToolsSnapshot,
                listState = listState,
                userScrolledAway = userScrolledAway,
                scrollGuard = scrollGuard,
                sessionId = vm.session.collectAsState().value?.id,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                topPadding = topBarHeightDp + 8.dp,
                bottomPadding = with(density) {
                    maxOf(132.dp, bottomBarHeightPx.toDp() + 8.dp) + keyboardLiftPx.toDp()
                }
            )
        }

        // 玻璃顶栏不能进入上面的 appLayer 子树——drawBackdrop 采样自层会递归崩溃
        val activeSession = vm.session.collectAsState().value
        val hasActiveTask = todoItems.any { it.status != "completed" && it.status != "cancelled" }
        Box(Modifier.align(Alignment.TopCenter)) {
            Column {
                TopBar(
                    title = vm.agentName(),
                    subtitle = activeSession?.title?.takeIf { it.isNotBlank() } ?: "新对话",
                    contextUsage = contextUsage,
                    backdrop = backdrop,
                    onDrawer = { openDrawer() },
                    onNewChat = {
                        vm.newSession()
                        scope.launch { drawer.close() }
                    },
                    onRenameSubtitle = {
                        renameText = activeSession?.title ?: ""
                        showRenameDialog = true
                    },
                    onOpenBrowser = onOpenBrowser,
                    onOpenVscreen = onOpenVscreen,
                    planMode = planMode,
                    todoItems = todoItems,
                    taskExpanded = taskPanelExpanded,
                    onToggleTask = { taskPanelExpanded = !taskPanelExpanded },
                    contextDetailExpanded = showContextDetail,
                    onToggleContextDetail = { showContextDetail = !showContextDetail },
                    modifier = Modifier
                )
                // 上下文用量详情：从顶栏玻璃下沿向下展开、水平居中。
                // 顶栏展开面板层级（自上而下）：任务面板（与玻璃一体生长，常驻工作状态）
                // > 上下文详情（用户点环形指示器主动弹出的瞬时信息，贴近触发点）
                // > E1 断点恢复横幅（系统被动通知，优先级最低）。
                // 三者同在布局流里堆叠，互不遮挡。
                androidx.compose.animation.AnimatedVisibility(
                    visible = showContextDetail,
                    enter = androidx.compose.animation.expandVertically() +
                        androidx.compose.animation.fadeIn(),
                    exit = androidx.compose.animation.shrinkVertically() +
                        androidx.compose.animation.fadeOut()
                ) {
                    Box(
                        Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.TopCenter
                    ) {
                        ContextUsagePanel(
                            usage = contextUsage,
                            backdrop = backdrop,
                            modifier = Modifier.padding(top = 6.dp, bottom = 8.dp)
                        )
                    }
                }
                // E1 断点恢复横幅：从顶栏下沿延展出现（running 标记由 selectSession 死亡检测置入）
                val runStateNow = activeSession?.runState
                val runGoalNow = activeSession?.runGoal
                androidx.compose.animation.AnimatedVisibility(
                    visible = com.haoai.agent.data.StoredSession.resumable(runStateNow) && runGoalNow != null,
                    enter = androidx.compose.animation.fadeIn() +
                        androidx.compose.animation.expandVertically(),
                    exit = androidx.compose.animation.fadeOut() +
                        androidx.compose.animation.shrinkVertically()
                ) {
                    com.haoai.agent.ui.common.GlassCard(
                        onClick = {},
                        backdrop = backdrop,
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(
                            topStart = 0.dp, topEnd = 0.dp, bottomStart = 14.dp, bottomEnd = 14.dp
                        ),
                        surfaceAlpha = 0.60f,
                        contentAlignment = androidx.compose.ui.Alignment.CenterStart,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                    ) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "上次任务中断：${runGoalNow?.take(60) ?: ""}",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 2
                                )
                                Text(
                                    when (runStateNow) {
                                        com.haoai.agent.data.StoredSession.RUN_TURNCAPPED -> "达到轮数上限，可继续执行剩余步骤"
                                        com.haoai.agent.data.StoredSession.RUN_FAILED -> "执行出错，可继续尝试"
                                        else -> "进程中断，可继续执行"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            androidx.compose.material3.TextButton(onClick = { vm.resumeRun() }) {
                                Text("继续", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                            }
                            androidx.compose.material3.TextButton(onClick = { vm.dismissResume() }) {
                                Text("忽略", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            // 右下角耳片：面板收起时凸起一小块玻璃，点开任务面板（与顶栏连体贴合）
            androidx.compose.animation.AnimatedVisibility(
                visible = !taskPanelExpanded && (hasActiveTask || taskPanelForcedVisible),
                enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.expandVertically(),
                exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.shrinkVertically(),
                modifier = Modifier.align(Alignment.BottomEnd)
            ) {
                val infinite = rememberInfiniteTransition(label = "ear")
                val breath by infinite.animateFloat(
                    0.35f, 1f,
                    androidx.compose.animation.core.infiniteRepeatable(
                        androidx.compose.animation.core.tween(900),
                        androidx.compose.animation.core.RepeatMode.Reverse
                    ), label = "earA"
                )
                GlassPanel(
                    backdrop = backdrop,
                    radius = 0.dp,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(
                        bottomStart = 16.dp, bottomEnd = 16.dp
                    ),
                    lensRadius = 0.dp,
                    surfaceAlpha = 0.20f,
                    border = false,
                    modifier = Modifier.size(width = 116.dp, height = 46.dp)
                ) {
                    Row(
                        Modifier
                            .fillMaxSize()
                            .clickable { taskPanelExpanded = true },
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (hasActiveTask) {
                            Box(
                                Modifier
                                    .size(8.dp)
                                    .clip(androidx.compose.foundation.shape.CircleShape)
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = breath))
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Icon(
                            Icons.Filled.ExpandMore,
                            contentDescription = "展开任务",
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        }

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                // 同上：offset 布局期平移，保证 TaskPanel/斜杠弹层/输入框玻璃采样随键盘抬升
                .offset { androidx.compose.ui.unit.IntOffset(0, -keyboardLiftPx) }
                .onSizeChanged { bottomBarHeightPx = it.height }
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            // E8 插话排队提示（生成期间用户发送的消息在队列中等待间隙注入）
            val interjectCount by vm.interjectCount.collectAsState()
            if (interjectCount > 0) {
                Row(
                    Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(bottom = 6.dp)
                        .clickable { vm.withdrawInterjection() },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "$interjectCount 条插话已排队 · 当前步骤结束后送达（点此撤回）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
                    )
                }
            }
            ComposerBar(
                backdrop = backdrop,
                text = input,
                onTextChange = { newVal ->
                    input = newVal
                    if (newVal.startsWith("/")) {
                        slashFilterQuery = newVal
                        slashMenuVisible = true
                    } else {
                        slashMenuVisible = false
                    }
                },
                running = running,
                pendingImage = pendingImage,
                onPickImage = {
                    imagePicker.launch(
                        android.content.Intent(
                            android.content.Intent.ACTION_PICK,
                            android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                        )
                    )
                },
                onTakePhoto = {
                    if (androidx.core.content.ContextCompat.checkSelfPermission(
                            context, android.Manifest.permission.CAMERA
                        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    ) launchCamera() else
                        cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
                },
                onPickDocument = { documentPicker.launch(arrayOf("text/*", "application/pdf", "application/json", "application/xml")) },
                onClearImage = { pendingImage = null },
                pendingAudio = pendingAudioPath,
                pendingVideoName = pendingVideoName,
                onPickAudio = { audioPicker.launch(arrayOf("audio/*")) },
                onPickVideo = { videoPicker.launch(arrayOf("video/*")) },
                onClearAudio = { pendingAudioPath = null; pendingAudioName = null },
                onClearVideo = { pendingVideoPath = null; pendingVideoName = null },
                onSlashCommand = {
                    slashFilterQuery = ""
                    slashMenuVisible = !slashMenuVisible
                },
                slashVisible = slashMenuVisible,
                slashQuery = slashFilterQuery,
                onSlashSelect = { cmd ->
                    if (cmd.takesText) {
                        input = "/${cmd.name} "
                    } else {
                        scope.launch {
                            val handled = vm.handleSlashCommand(cmd, "") {
                                showModelPicker = true
                            }
                            if (handled) {
                                when (cmd.name) {
                                    "help" -> showSlashHelp = true
                                    "status" -> showStatusPopup = true
                                    "task" -> {
                                        taskPanelExpanded = !taskPanelExpanded
                                        taskPanelForcedVisible = !taskPanelForcedVisible
                                    }
                                }
                            }
                        }
                        input = ""
                    }
                    slashMenuVisible = false
                },
                onSlashDismiss = { slashMenuVisible = false },
                onSend = {
                    val trimmed = input.trim()
                    val slashResult = SlashCommands.parse(trimmed)
                    if (slashResult != null) {
                        val (cmd, arg) = slashResult
                        scope.launch {
                            val handled = vm.handleSlashCommand(cmd, arg) {
                                showModelPicker = true
                            }
                            if (handled) {
                                when (cmd.name) {
                                    "help" -> showSlashHelp = true
                                    "status" -> showStatusPopup = true
                                    "task" -> {
                                        taskPanelExpanded = !taskPanelExpanded
                                        taskPanelForcedVisible = !taskPanelForcedVisible
                                    }
                                }
                            }
                        }
                        input = ""
                        slashMenuVisible = false
                        return@ComposerBar
                    }
                    val docPrefix = if (pendingDocumentContent != null && pendingDocumentName != null) {
                        "[附件: $pendingDocumentName]\n```\n$pendingDocumentContent\n```\n"
                    } else ""
                    val fullText = docPrefix + trimmed
                    if (fullText.isNotBlank() || pendingImage != null || pendingAudioPath != null || pendingVideoPath != null) {
                        vm.send(fullText, pendingImage, pendingAudioPath, pendingVideoPath)
                        input = ""
                        pendingImage = null
                        pendingAudioPath = null
                        pendingAudioName = null
                        pendingVideoPath = null
                        pendingVideoName = null
                        pendingDocumentName = null
                        pendingDocumentContent = null
                    }
                },
                onStop = { vm.stop() },
                placeholder = "给 ${vm.agentName()} 派个活…",
                redrawKey = { keyboardLiftState.intValue }
            )
        }

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

        // ⑧ 回到底部浮钮：用户上滑断开粘滞后浮现，点击回底并恢复跟随。
        // 必须画在 appLayer 采样层之外（与玻璃顶栏同级）——放进 MessageList 的
        // Box 包裹会切断 drawBackdrop 采样链，整屏透出白色遮罩（实测踩坑）
        // v2：加新动态计数徽标（ChatGPT/Claude 式「↓ N 条新回复」）
        androidx.compose.animation.AnimatedVisibility(
            visible = userScrolledAway.value && rows.isNotEmpty() && drawerFraction < 0.01f,
            enter = androidx.compose.animation.fadeIn(tween(180)) +
                androidx.compose.animation.expandVertically(tween(180)),
            exit = androidx.compose.animation.fadeOut(tween(140)),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = with(density) {
                    maxOf(132.dp, bottomBarHeightPx.toDp() + 8.dp) + keyboardLiftPx.toDp() + 10.dp
                })
        ) {
            Surface(
                onClick = {
                    userScrolledAway.value = false
                    newContentTicker.value = 0
                    scope.launch { listState.scrollToEnd(guard = scrollGuard) }
                },
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                shape = CircleShape,
                tonalElevation = 3.dp,
                border = androidx.compose.foundation.BorderStroke(
                    1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                ),
                modifier = Modifier.size(38.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.KeyboardArrowDown,
                            contentDescription = "回到底部",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        val n = newContentTicker.value
                        if (n > 0) {
                            Spacer(Modifier.size(1.dp))
                            Text(
                                "$n",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
        // 抽屉 scrim：透明度随 fraction，点击收起
        if (drawerFraction > 0.01f) {
            Box(
                Modifier
                    .matchParentSize()
                    .background(Color.Black.copy(alpha = 0.30f * drawerFraction))
                    .clickable(interactionSource = null, indication = null) {
                        scope.launch { drawer.close() }
                    }
            )
        }
        // 抽屉 sheet：布局期平移（Modifier.offset 无离屏层）——ModalNavigationDrawer 的
        // graphicsLayer 平移动画会被玻璃 backdrop 采样滞后回画，关抽屉时卡片四角闪直角残影
        var sheetW by remember { mutableIntStateOf(0) }
        Box(
            Modifier
                .fillMaxHeight()
                // 外层宽度=可见面板宽度（0.72）：此前外层 0.85 内层再 0.85，
                // 吃点击的 clickable 覆盖到 0.85，面板右缘与外层之间 13% 死区点不动
                .fillMaxWidth(0.72f)
                .onSizeChanged { sheetW = it.width }
                .offset { IntOffset((-(1f - drawerFraction) * sheetW).toInt(), 0) }
                // 左滑收起抽屉：跟手拖动，松手按位置+速度结算（与左缘呼出对称）
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var tx = 0f; var ty = 0f
                        var tracking = false
                        var startTime = 0L
                        while (true) {
                            val e = awaitPointerEvent()
                            val c = e.changes.firstOrNull { it.id == down.id } ?: break
                            tx += c.positionChange().x; ty += c.positionChange().y
                            if (!tracking) {
                                if (abs(tx) > viewConfiguration.touchSlop || abs(ty) > viewConfiguration.touchSlop) {
                                    // 卡片右滑呼出操作会消费水平拖拽，此处只认左滑
                                    if (abs(tx) > abs(ty) && tx < 0f) {
                                        tracking = true
                                        startTime = System.currentTimeMillis()
                                        c.consume()
                                    } else break
                                }
                            } else {
                                // 从开态 1.0 起跟手左移：累计位移（负值）归一化
                                val panelPx = with(this) { drawerPanelWidthDp.toPx() }
                                val target = (1f + tx / panelPx).coerceIn(0f, 1f)
                                scope.launch { drawer.dragTo(target) }
                                c.consume()
                            }
                            if (!c.pressed) {
                                if (tracking) {
                                    val dt = ((System.currentTimeMillis() - startTime).coerceAtLeast(1)) / 1000f
                                    val v = (tx / (with(this) { drawerPanelWidthDp.toPx() })) / dt
                                    scope.launch { drawer.settle(v) }
                                }
                                break
                            }
                        }
                    }
                }
                // 吃掉 sheet 空白区的点击：否则会穿透到下层 scrim 误关抽屉
                .clickable(interactionSource = null, indication = null) { }
        ) {
        Box(Modifier.fillMaxSize()) {
                    GlassPanel(
                        backdrop = backdrop,
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(1f),
                        surfaceAlpha = 0.28f,
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
                            subtitleLine = settings.bio,
                            avatarEmoji = settings.avatarEmoji,
                            avatarGradient = settings.avatarGradient,
                            avatarImagePath = settings.avatarImagePath,
                            sessions = sessions,
                            activeId = vm.session.collectAsState().value?.id,
                            backdrop = backdrop,
                            drawerOpen = drawer.isOpen,
                            onOpenSessions = {
                                onOpenSessions()
                            },
                            onSelectSession = { id ->
                                vm.selectSession(id)
                            },
                            onPinSession = { id -> vm.pinSession(id) },
                            onRenameSession = { id, title -> vm.renameSession(id, title) },
                            onDeleteSession = { id -> vm.deleteSession(id) },
                            onEditProfile = { showProfileEdit = true },
                            onSettings = {
                                onOpenSettings()
                            }
                        )
                    }
        }
        }

    // 工具卡"查看变更"弹层（1.3）：diff 从写前快照现算
    diffViewer?.let { (path, diffLines) ->
        androidx.compose.ui.window.Popup(
            onDismissRequest = { diffViewer = null },
            properties = androidx.compose.ui.window.PopupProperties(focusable = true)
        ) {
            DiffReviewView(
                path = path,
                isNewFile = false,
                diff = diffLines,
                modifier = Modifier.padding(16.dp)
            )
        }
    }

    // ===== 消息操作组（1.2，入口已改消息下方 ⋮ 与快捷行）=====
    msgAction?.let { target ->
        androidx.compose.animation.AnimatedVisibility(
            visible = true,
            enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(140)) +
                androidx.compose.animation.slideInVertically(
                    initialOffsetY = { it / 3 },
                    animationSpec = androidx.compose.animation.core.tween(200, easing = androidx.compose.animation.core.FastOutSlowInEasing)
                )
        ) {
        MessageActionPanel(
            backdrop = backdrop,
            row = target,
            running = running,
            onDismiss = { msgAction = null },
            onCopy = { text ->
                copyText(text)
                msgAction = null
            },
            onRegenerate = {
                vm.regenerateFrom(target.id)
                msgAction = null
            },
            onEdit = {
                editTarget = target
                msgAction = null
            },
            onDelete = {
                deleteTarget = target
                msgAction = null
            },
            onQuote = {
                input = "> " + target.text.take(200).replace("\n", "\n> ") + "\n\n"
                msgAction = null
            },
            onPreview = {
                previewTarget = target
                msgAction = null
            }
        )
        }
    }
    previewTarget?.let { target ->
        HtmlPreviewModal(row = target, onDismiss = { previewTarget = null })
    }
    editTarget?.let { target ->
        var editText by remember(target.id) { mutableStateOf(target.text) }
        com.haoai.agent.ui.common.GlassAlertDialog(
            backdrop = backdrop,
            title = "编辑并重发",
            onDismiss = { editTarget = null },
            confirmLabel = "重发",
            onConfirm = {
                vm.editResend(target.id, editText)
                editTarget = null
            },
            confirmEnabled = editText.isNotBlank()
        ) {
            androidx.compose.material3.OutlinedTextField(
                value = editText,
                onValueChange = { editText = it },
                colors = com.haoai.agent.ui.common.glassFieldColors(),
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                "发送后将替换这条消息并重新生成回复",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
    deleteTarget?.let { target ->
        com.haoai.agent.ui.common.GlassAlertDialog(
            backdrop = backdrop,
            title = "删除该消息？",
            onDismiss = { deleteTarget = null },
            confirmLabel = "删除",
            danger = true,
            onConfirm = {
                vm.deleteMessage(target.id)
                deleteTarget = null
            }
        ) {
            Text(
                "仅删除这一条消息，其他内容不受影响。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    // /help 斜杠命令帮助
    if (showSlashHelp) {
        com.haoai.agent.ui.common.GlassAlertDialog(
            backdrop = backdrop,
            title = "斜杠命令",
            onDismiss = { showSlashHelp = false },
            dismissLabel = "关闭"
        ) {
            val groups = SlashCommands.all.groupBy { it.group }
            groups.entries.forEachIndexed { gi, (group, cmds) ->
                Text(
                    group,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = if (gi == 0) 2.dp else 14.dp, bottom = 2.dp)
                )
                cmds.forEach { cmd ->
                    Row(
                        Modifier.padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            cmd.icon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            "/${cmd.name}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(start = 10.dp)
                        )
                        Text(
                            cmd.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.65f),
                            modifier = Modifier.padding(start = 10.dp)
                        )
                    }
                }
            }
        }
    }

    // /model 模型服务切换
    if (showModelPicker) {
        val providersNow = vm.providers()
        val activeIdNow = vm.activeProviderId()
        com.haoai.agent.ui.common.GlassAlertDialog(
            backdrop = backdrop,
            title = "切换模型",
            onDismiss = { showModelPicker = false },
            dismissLabel = "关闭"
        ) {
            if (providersNow.isEmpty()) {
                Text(
                    "还没有配置模型服务，请到「设置 → 模型服务」添加。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                )
            }
            providersNow.forEach { p ->
                val active = p.id == activeIdNow
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            vm.selectProvider(p.id)
                            showModelPicker = false
                        }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            p.name,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal
                        )
                        Text(
                            "${p.model} · ${p.baseUrl}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }
                    if (active) {
                        Text(
                            "使用中",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                // 点1（借鉴 上游/上游）：供应商下的模型直接点选切换，无需进设置
                if (p.models.isNotEmpty()) {
                    Row(
                        Modifier
                            .padding(bottom = 8.dp)
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        p.modelIds().forEach { mid ->
                            androidx.compose.material3.FilterChip(
                                selected = active && mid == p.model,
                                onClick = {
                                    vm.selectModel(p.id, mid)
                                    showModelPicker = false
                                },
                                label = { Text(mid, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                }
            }
        }
    }

    // /status 会话状态
    if (showStatusPopup) {
        val sess by vm.session.collectAsState()
        val rowsNow by vm.rows.collectAsState()
        val runningNow by vm.running.collectAsState()
        val todosNow by vm.todoItems.collectAsState()
        val providerLabel = vm.activeProviderLabel() ?: "端侧 llama.cpp"
        val cu = contextUsage
        com.haoai.agent.ui.common.GlassAlertDialog(
            backdrop = backdrop,
            title = "会话状态",
            onDismiss = { showStatusPopup = false },
            dismissLabel = "关闭"
        ) {
            StatusRow("Agent", vm.agentName())
            StatusRow("模型", providerLabel)
            StatusRow("会话", (sess?.title ?: "新会话") + " · " + rowsNow.size + " 条消息")
            StatusRow("上下文", "${cu.usedTokens} / ${cu.totalTokens} tokens（${(cu.percentage * 100).toInt()}%）")
            val openCount = todosNow.count { it.status != "completed" && it.status != "cancelled" }
            StatusRow("任务", if (todosNow.isEmpty()) "暂无" else "${todosNow.size} 项 · 待完成 $openCount")
            StatusRow("状态", if (runningNow) "运行中" else "空闲")
        }
    }

    // 会话重命名弹窗（顶栏点击会话名触发）
    if (showRenameDialog) {
        val renameTarget = vm.session.collectAsState().value
        com.haoai.agent.ui.common.GlassAlertDialog(
            backdrop = backdrop,
            title = "重命名会话",
            onDismiss = { showRenameDialog = false },
            confirmLabel = "保存",
            onConfirm = {
                renameTarget?.let { vm.renameSession(it.id, renameText) }
                showRenameDialog = false
            },
            dismissLabel = "取消"
        ) {
            OutlinedTextField(
                value = renameText,
                onValueChange = { renameText = it.take(50) },
                label = { Text("会话名") },
                singleLine = true,
                colors = com.haoai.agent.ui.common.glassFieldColors(),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }

    // 档案编辑弹窗：名字 / emoji 头像 / 渐变底色 / 签名（放在抽屉之外，避免被抽屉层盖住）
    if (showProfileEdit) {
        var editName by remember { mutableStateOf(settings.agentName.ifBlank { "HaoAI" }) }
        var editEmoji by remember { mutableStateOf(settings.avatarEmoji) }
        var editGradient by remember { mutableStateOf(settings.avatarGradient) }
        var editBio by remember { mutableStateOf(settings.bio) }
        var editImagePath by remember { mutableStateOf(settings.avatarImagePath) }
        val emojiChoices = remember {
            listOf("😀", "😊", "😎", "🤖", "🐱", "🐶", "🦊", "🐰", "🌸", "🌟", "🔥", "🌙", "⚡", "🍀", "🎧", "🚀")
        }
        val avatarPicker = androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.GetContent()
        ) { uri ->
            if (uri != null) {
                vm.importAvatarImage(uri) { path ->
                    if (path != null) {
                        editImagePath = path
                        editEmoji = ""
                    }
                }
            }
        }
        com.haoai.agent.ui.common.GlassAlertDialog(
            backdrop = backdrop,
            title = "编辑档案",
            onDismiss = { showProfileEdit = false },
            confirmLabel = "保存",
            onConfirm = {
                vm.updateProfile(editName, editEmoji, editGradient, editBio, editImagePath)
                showProfileEdit = false
            },
            dismissLabel = "取消"
        ) {
            OutlinedTextField(
                value = editName,
                onValueChange = { editName = it.take(20) },
                label = { Text("名字") },
                singleLine = true,
                colors = com.haoai.agent.ui.common.glassFieldColors(),
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                "头像",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 10.dp)
            )
            Row(
                Modifier.padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ProfileAvatar(
                    emoji = editEmoji,
                    gradientIndex = editGradient,
                    fallback = editName,
                    size = 52.dp,
                    imagePath = editImagePath
                )
                Spacer(Modifier.size(14.dp))
                com.haoai.agent.ui.common.LiquidGlassButton(
                    onClick = { avatarPicker.launch("image/*") },
                    backdrop = backdrop,
                    shape = RoundedCornerShape(percent = 50),
                    // 弹窗内按钮采样不到弹窗遮罩，透明度低了壁纸直接透过（用户反馈点）
                    surfaceColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)
                ) {
                    Text(
                        "从相册选择",
                        color = MaterialTheme.colorScheme.onBackground,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                    )
                }
                if (editImagePath != null) {
                    com.haoai.agent.ui.common.GlassTextButton(
                        text = "移除",
                        onClick = { editImagePath = null }
                    )
                }
            }
            emojiChoices.chunked(6).forEach { rowEmojis ->
                Row(
                    Modifier.padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    rowEmojis.forEach { e ->
                        val selected = e == editEmoji
                        Box(
                            Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
                                    else Color.Transparent
                                )
                                .then(
                                    if (selected) Modifier.border(
                                        2.dp,
                                        MaterialTheme.colorScheme.primary,
                                        RoundedCornerShape(10.dp)
                                    ) else Modifier
                                )
                                .clickable {
                                    editEmoji = if (selected) "" else e
                                    // 选 emoji 即回到 emoji 头像，图片模式退出
                                    editImagePath = null
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(e, fontSize = 20.sp)
                        }
                    }
                }
            }
            Text(
                "底色",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 10.dp)
            )
            Row(
                Modifier.padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AVATAR_GRADIENTS.forEachIndexed { i, colors ->
                    val selected = i == editGradient
                    Box(
                        Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(Brush.linearGradient(colors))
                            .then(
                                if (selected) Modifier.border(
                                    2.dp,
                                    MaterialTheme.colorScheme.primary,
                                    CircleShape
                                ) else Modifier
                            )
                            .clickable { editGradient = i }
                    )
                }
            }
            OutlinedTextField(
                value = editBio,
                onValueChange = { editBio = it.take(60) },
                label = { Text("签名（一句话介绍）") },
                singleLine = true,
                colors = com.haoai.agent.ui.common.glassFieldColors(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
            )
        }
    }

    // 5.6 计划确认卡：Plan 模式回合结束且拦截过工具时弹出
    planProposal?.let { plan ->
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.18f))
        ) {
            GlassPanel(
                backdrop = backdrop,
                radius = 22.dp,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp)
                    .fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "执行计划",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Plan 模式下产出的计划。批准后退出计划模式并开始执行（执行仍走正常审批）。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
                    )
                    Text(
                        plan.take(4000),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 14,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                            .padding(10.dp)
                    )
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)
                    ) {
                        Text(
                            "继续讨论",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { vm.dismissPlan() }
                                .padding(horizontal = 14.dp, vertical = 8.dp)
                        )
                        Text(
                            "批准并执行",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { vm.approvePlan() }
                                .padding(horizontal = 14.dp, vertical = 8.dp)
                        )
                    }
                }
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
                    // 1.3 Diff 审查：写文件审批内嵌改动预览，拒绝 = 文件不变
                    (req as? com.haoai.agent.agent.policy.ApprovalRequest.WriteOp)?.let { wop ->
                        if (wop.isNewFile || wop.diff.isNotEmpty()) {
                            DiffReviewView(
                                path = wop.path,
                                isNewFile = wop.isNewFile,
                                diff = wop.diff,
                                modifier = Modifier.padding(top = 12.dp)
                            )
                        }
                    }
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
    onDrawer: () -> Unit,
    onNewChat: () -> Unit,
    // 点击标题区（会话名副标题）→ 重命名当前会话
    onRenameSubtitle: () -> Unit,
    onOpenBrowser: () -> Unit = {},
    onOpenVscreen: () -> Unit = {},
    planMode: Boolean = false,
    // 一体任务面板：todoItems 驱动内容；expanded 由外部控制（任务按钮/右下耳片//task 命令）
    todoItems: List<com.haoai.agent.agent.tools.TodoItem> = emptyList(),
    taskExpanded: Boolean = false,
    onToggleTask: () -> Unit = {},
    // 上下文详情面板：开关状态提升到 ChatScreen（面板在顶栏下方布局流里展开），
    // TopBar 只留触发点
    contextDetailExpanded: Boolean = false,
    onToggleContextDetail: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val hasActiveTask = todoItems.any { it.status != "completed" && it.status != "cancelled" }
    val vscreenId by com.haoai.agent.platform.vdisplay.VirtualScreenController.displayIdFlow.collectAsState()
    val density = LocalDensity.current
    val statusBarPx = WindowInsets.statusBars.getTop(density).toFloat()
    val scrimColor = MaterialTheme.colorScheme.background
    // 通栏方角顶栏（上游 式）：无左右边距、无圆角、无四周描边（底缘发丝线由内容
    // Row 下方的 Box 画出）；任务面板在同一块玻璃内向下一体生长（animateContentSize）
    GlassPanel(
        backdrop = backdrop,
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(),
        radius = 0.dp,
        lensRadius = 0.dp,
        // blurRadius 默认=radius/3，方角顶栏 radius=0 会得到 blur(0)——显式给模糊量
        blurRadius = 12.dp,
        surfaceAlpha = 0.30f,
        border = false,
        // backdrop blur 在玻璃顶缘采样衰减，高对比文字滚入状态栏带会透出：
        // 顶部叠一条背景色渐变补强，到状态栏底 +20dp（标题行上缘）淡出
        surfaceOverlay = {
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(scrimColor.copy(alpha = 0.55f), scrimColor.copy(alpha = 0f)),
                    startY = 0f,
                    endY = statusBarPx + with(density) { 20.dp.toPx() }
                )
            )
        }
    ) {
        // GlassPanel 内容是 Box：顶栏行与任务区必须包在同一 Column 里，否则叠放
        Column(
            Modifier
                .fillMaxWidth()
                // 玻璃面吃掉空白区点按：消息现在会滚入顶栏下方，玻璃后的模糊消息
                // 不应再响应点按/长按（内部按钮与标题列的点击不受影响）
                .clickable(interactionSource = null, indication = null) {}
        ) {
        // 状态栏高度并入顶栏玻璃：玻璃从屏幕最顶端铺下来（不再 statusBarsPadding 外扩）
        Spacer(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(0.dp)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onDrawer) {
                Icon(Icons.Filled.Menu, contentDescription = "会话列表", tint = MaterialTheme.colorScheme.onBackground)
            }
            Spacer(Modifier.size(2.dp))
            Column(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = onRenameSubtitle)
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            ) {
                if (planMode) {
                    Surface(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.padding(bottom = 2.dp)
                    ) {
                        Text(
                            "计划中 · /plan 退出",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                        )
                    }
                }
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
            Spacer(Modifier.size(2.dp))
            IconButton(onClick = onOpenBrowser) {
                Icon(
                    Icons.Filled.Public,
                    contentDescription = "内置浏览器",
                    modifier = Modifier.size(22.dp),
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
            // 4.3 增强：虚拟屏活跃时显示入口，点开实时预览面板
            if (vscreenId != null) {
                IconButton(onClick = onOpenVscreen) {
                    Icon(
                        Icons.Filled.SmartDisplay,
                        contentDescription = "虚拟屏预览",
                        modifier = Modifier.size(22.dp),
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }
            }
            IconButton(onClick = onNewChat) {
                Icon(Icons.Filled.Add, contentDescription = "新会话", tint = MaterialTheme.colorScheme.onBackground)
            }
            CircularContextIndicator(
                usage = contextUsage,
                onClick = onToggleContextDetail,
                expanded = contextDetailExpanded
            )
        }
        // 任务面板：同一块玻璃向下一体生长（顶栏加宽效果），无独立卡片、无关闭钮
        androidx.compose.animation.AnimatedVisibility(
            visible = taskExpanded,
            enter = androidx.compose.animation.expandVertically() + androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.shrinkVertically() + androidx.compose.animation.fadeOut()
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 10.dp)
            ) {
                // 与顶栏行的细分隔线
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                        .height(1.dp)
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f))
                )
                TopTaskSection(items = todoItems)
                // 右下角收起圆钮（向上箭头）
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End
                ) {
                    androidx.compose.material3.Surface(
                        onClick = onToggleTask,
                        shape = androidx.compose.foundation.shape.CircleShape,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                        modifier = Modifier.size(38.dp)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            Icon(
                                Icons.Filled.ExpandLess,
                                contentDescription = "收起任务",
                                tint = MaterialTheme.colorScheme.onSurface
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
private fun MessageList(
    rows: List<ChatRow>,
    onOpenMenu: (ChatRow) -> Unit,
    onCopyRow: (ChatRow) -> Unit,
    onQuickRegenerate: (ChatRow) -> Unit,
    onQuickEdit: (ChatRow) -> Unit,
    onToolViewDiff: (String) -> Unit,
    onToolRollback: (String) -> Unit = {},
    onStopRun: () -> Unit = {},
    streamingText: String?,
    streamingReasoning: String?,
    running: Boolean,
    thinkingHint: String? = null,
    thinkingMs: Long? = null,
    liveToolsSnapshot: List<com.haoai.agent.ui.UiTool> = emptyList(),
    listState: androidx.compose.foundation.lazy.LazyListState,
    userScrolledAway: androidx.compose.runtime.MutableState<Boolean>,
    scrollGuard: androidx.compose.runtime.MutableState<Boolean>,
    sessionId: String? = null,
    modifier: Modifier = Modifier,
    topPadding: androidx.compose.ui.unit.Dp = 0.dp,
    bottomPadding: androidx.compose.ui.unit.Dp
) {
    val showStreaming = streamingText != null || running
    val totalItems = rows.size + (if (showStreaming) 1 else 0)

    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    // 程序化滚动标志：跟随/补滚期间为 true。isScrollInProgress 不区分滚动来源，
    // 不加这层守卫，流式跟随/键盘抬升补滚会被当成"用户滑动"而 clearFocus 收起输入法
    // （表现为点输入框键盘刚弹出就被关），也会误断粘滞
    // 用户滑动列表时收起输入法（程序化滚动不触发）
    LaunchedEffect(listState) {
        androidx.compose.runtime.snapshotFlow { listState.isScrollInProgress }
            .collect { if (it && !scrollGuard.value) focusManager.clearFocus() }
    }

    // 粘滞标志：只有用户亲手把列表拖离底部才断开跟随；键盘抬升/内容增高/补滚动画不算
    // v2 判定：用户手势期间**任一帧出现向上滚动趋势即断开**（首帧断开，业界标准）——
    // 旧版要拖离底部 >200px 才算"离开"，流式 delta 每 40ms 重触发 scrollToEnd
    // 瞬时跳底与用户上拖抢滚动，表现为"自动往下弹、看不了历史"。
    // 上向趋势 = 本帧位置比手势起点更靠上（firstVisibleItemIndex 或 scrollOffset 减小）。
    var gestureAnchor by remember(listState) { mutableStateOf<Pair<Int, Int>?>(null) }
    LaunchedEffect(listState) {
        androidx.compose.runtime.snapshotFlow {
            val inProgress = listState.isScrollInProgress
            val info = listState.layoutInfo
            val first = info.visibleItemsInfo.firstOrNull()
            Triple(inProgress, first?.index ?: 0, first?.offset ?: 0)
        }
            .distinctUntilChanged().collect { (inProgress, idx, off) ->
                if (scrollGuard.value) return@collect
                if (inProgress) {
                    val anchor = gestureAnchor
                    if (anchor == null) {
                        gestureAnchor = idx to off
                    } else if (idx < anchor.first || (idx == anchor.first && off < anchor.second)) {
                        // 首帧向上 → 立即断开跟随
                        userScrolledAway.value = true
                    }
                } else {
                    gestureAnchor = null
                    // 手势结束后若已停在近底，恢复跟随（温和的自动恢复）
                    val lv = listState.layoutInfo.visibleItemsInfo.lastOrNull()
                    val contentEnd = listState.layoutInfo.viewportEndOffset - listState.layoutInfo.afterContentPadding
                    val nearBottom = lv != null &&
                        lv.index == listState.layoutInfo.totalItemsCount - 1 &&
                        lv.offset + lv.size - contentEnd < 120
                    if (nearBottom) userScrolledAway.value = false
                }
            }
    }

    // 自动滚底：用户发送新消息时无条件滚底；流式内容仅在粘滞（用户未主动滑走）时跟随。
    // running/durationMs/bottomPadding/footerRevealTick 也入 key：流式结束最终行增高
    // （usage 常晚于正文落值）、footer 生长动画启动、键盘抬升改 padding 时都要补沉降。
    // 一律瞬时滚动：animateScrollBy 会被下一帧 delta 取消，快 token 率下进度被饿死
    // 发送新消息收起键盘：旧版靠强制滚动的 clearFocus 副作用实现，加滚动守卫后需显式收。
    // 按末条 user 消息 key 去重——流式期间最后一条仍是 user，不能每次都收，
    // 否则用户流式中打开键盘想插话会被下一个 delta 误关
    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    var lastSentUserKey by rememberSaveable { mutableStateOf<String?>(null) }
    // footer 展开动画启动的通知：settle 循环若在动画开始前退出（隐藏态本就"贴合"），
    // 动画增高的 ~45dp 将无人跟进，静止位比真底部高两行（可上拖）。收到通知重启一次
    var footerRevealTick by remember { mutableStateOf(0) }

    LaunchedEffect(totalItems, rows.lastOrNull()?.text?.length, streamingText?.length, streamingReasoning?.length, running, rows.lastOrNull()?.durationMs, bottomPadding, footerRevealTick) {
        if (totalItems <= 0) return@LaunchedEffect
        // 用户发送新消息（最后一条是 user）→ 强制滚底并复位粘滞
        val isUserMessage = rows.lastOrNull()?.role == "user"
        if (isUserMessage) {
            userScrolledAway.value = false
            val k = rows.last().key
            if (k != lastSentUserKey) {
                lastSentUserKey = k
                keyboardController?.hide()
                focusManager.clearFocus()
            }
        }
        if (isUserMessage || !userScrolledAway.value) {
            listState.scrollToEnd(guard = scrollGuard)
        }
    }

    // 会话切换：无条件跳到最新一条（切换后通常停在旧位置，且最后一条未必是 user 消息，
    // 上面的跟随逻辑不会触发）。新会话无消息时不滚动。
    LaunchedEffect(sessionId) {
        if (sessionId != null && totalItems > 0) listState.scrollToEnd(guard = scrollGuard)
    }

    // 流式刚结束的那次重组（running true→false 与最终行入列同帧发生）：最终行 footer
    // （操作按钮+统计行）先隐藏、下一帧起 200ms 生长动画，把 +46dp 硬跳吸收成动画，
    // 滚动跟随（settle 循环）追的是连续生长而非跳变。历史消息不满足条件、静态直出
    val prevRunning = remember { mutableStateOf(running) }
    val justFinished = prevRunning.value && !running
    prevRunning.value = running

    LazyColumn(state = listState, modifier = modifier, contentPadding = androidx.compose.foundation.layout.PaddingValues(top = topPadding, bottom = bottomPadding)) {
        // 快捷操作按钮只挂回合最终回复：usage 字段只在整轮最终消息落值；
        // 兜底 = 非运行态的最后一条（覆盖无 usage 的错误收尾行），运行中不显示
        val finalRowKey = if (!running) rows.lastOrNull()?.key else null
        val growInKey = if (justFinished) finalRowKey else null
        items(rows, key = { it.key }) { row ->
            RowItem(
                row,
                onOpenMenu = onOpenMenu,
                onCopyRow = onCopyRow,
                onQuickRegenerate = onQuickRegenerate,
                onQuickEdit = onQuickEdit,
                running = running,
                onViewDiff = onToolViewDiff,
                onRollback = onToolRollback,
                onStopRun = onStopRun,
                showActions = row.completionTokens != null || row.durationMs != null || row.key == finalRowKey,
                growIn = row.key == growInKey,
                onFooterReveal = { footerRevealTick++ }
            )
        }
        if (showStreaming) {
            item(key = "streaming") {
                StreamingItem(
                    streamingText,
                    streamingReasoning,
                    thinkingHint,
                    thinkingMs,
                    liveTools = liveToolsSnapshot,
                    running = running,
                    onStopRun = onStopRun,
                    onViewDiff = onToolViewDiff
                )
            }
        }
    }
}

/**
 * 滚到列表末端：末项底边对齐内容末端（视口末端-后 padding），上游 animateScrollToEnd 同思路。
 * animateScrollToItem(last) 是顶边对齐——流式条目长过一屏后，最新内容留在视口下方外，
 * 近底判定也随之失真，表现为"流式快结束时停住、结束不滚到底"。
 * 末项增高（操作按钮/统计行出现、Markdown 异步布局）晚于本次 layoutInfo 快照，
 * 故循环若干帧重读布局微调直至贴合。
 */
private suspend fun androidx.compose.foundation.lazy.LazyListState.scrollToEnd(
    guard: androidx.compose.runtime.MutableState<Boolean>
) {
    guard.value = true
    try {
    // 首轮不先等帧：流式 delta 约每帧一次，先 withFrameNanos 会让 effect 反复在等待中
    // 被下一个 delta 取消，滚动永远执行不到、列表越落越远（isNearBottom 随之失效断跟随）。
    // 但 delta≈0 也不能提前返回：流式结束那刻最终行增高（footer 生长动画 200ms≈12 帧、
    // 长文 Markdown 异步布局）要到之后若干帧才可见，故循环重读布局微调，
    // 连续两帧贴合才收工（上限 18 帧，覆盖整个 footer 动画期）。
    var settled = 0
    var attempt = 0
    // 至少 8 帧才允许按 settled 退出：footer expand 动画的 tween 缓入段头几帧增高 <1px，
    // 会被误判"贴合"连续计数提前收工，剩余生长无人追踪（实测静止位比底部差 ~150px 可上拖）
    while (attempt < 18 && !(attempt >= 8 && settled >= 2)) {
        // 用户手势插入（首帧断开场景）：立即让位，不与手指抢滚动
        if (isScrollInProgress && !guard.value) break
        attempt++
        val info = layoutInfo
        val lastIndex = info.totalItemsCount - 1
        if (lastIndex < 0) return
        val lastItem = info.visibleItemsInfo.lastOrNull { it.index == lastIndex }
        if (lastItem == null) {
            // 末项尚未组合（如切会话停在旧位置）：先跳过去，下一帧再微调
            scrollToItem(lastIndex)
            settled = 0
        } else {
            val contentEnd = info.viewportEndOffset - info.afterContentPadding
            val delta = lastItem.offset + lastItem.size - contentEnd
            if (kotlin.math.abs(delta) > 1) {
                // 一律瞬时滚动：生长动画本身平滑，逐帧贴住即连续；animateScrollBy
                // 的动画挂起会让循环落后于生长、且小 delta 被缓入帧骗退
                scrollBy(delta.toFloat())
                settled = 0
            } else {
                settled++
            }
        }
        withFrameNanos { }
    }
        // 多等一帧再复位守卫：scrollBy 触发的 isScrollInProgress 快照可能晚一帧送达，
        // 提前复位会让这帧被误判成用户滑动而收起输入法
        withFrameNanos { }
    } finally {
        guard.value = false
    }
}

@Composable
private fun RowItem(
    row: ChatRow,
    onOpenMenu: (ChatRow) -> Unit,
    onCopyRow: (ChatRow) -> Unit,
    onQuickRegenerate: (ChatRow) -> Unit,
    onQuickEdit: (ChatRow) -> Unit,
    running: Boolean,
    onViewDiff: (String) -> Unit,
    onRollback: (String) -> Unit = {},
    onStopRun: () -> Unit = {},
    showActions: Boolean = true,
    growIn: Boolean = false,
    onFooterReveal: () -> Unit = {}
) {
    // 引擎注入的系统事件（handoff 催办 / 压缩结果）不冒充聊天气泡，渲染为居中事件条
    if (row.role == "user" && row.text.startsWith("[系统提示]") ||
        row.role != "user" && row.text.startsWith("[系统]")
    ) {
        SystemEventBar(row.text)
        return
    }
    when (row.role) {
        "user" -> UserBubble(row, onOpenMenu, onCopyRow, onQuickEdit, running)
        else -> AssistantBlock(row, onOpenMenu, onCopyRow, onQuickRegenerate, running, onViewDiff, onRollback, onStopRun, showActions, growIn, onFooterReveal)
    }
}

/**
 * 系统事件条（居中小字胶囊，上游/同类工具 式）：压缩、交接等引擎级事件
 * 与对话内容在视觉上分层，透明度跟随设置的「气泡 / 卡片不透明度」。
 */
@Composable
private fun SystemEventBar(text: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.Center
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = chatBubbleAlphas().second * 0.6f),
            shape = RoundedCornerShape(999.dp)
        ) {
            Text(
                text.removePrefix("[系统提示]").removePrefix("[系统]").trim(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
            )
        }
    }
}

/** 轻量等待行（等首 token / prefill）：无边框无底色的细行，替代旧空"正在思考"气泡。 */
@Composable
private fun ThinkingLine(hint: String? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ThinkingIndicator(hint)
    }
}

/**
 * ⑤ 等待状态行（上游 waiting line 同思路）：连接点 + 短语轮播（shimmer 渐变）+
 * 已用时长计时。prefill 慢（端侧模型数十秒）时给用户持续"活着"的信号。
 */
@Composable
private fun ThinkingIndicator(hint: String? = null) {
    val phrases = hint?.let { listOf(it) } ?: listOf(
        "正在连接模型…", "正在理解上下文…", "预热推理中…", "组织回答…"
    )
    var idx by remember { mutableIntStateOf(0) }
    var elapsedMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(phrases) {
        while (true) {
            kotlinx.coroutines.delay(2600)
            idx = (idx + 1) % phrases.size
        }
    }
    LaunchedEffect(Unit) {
        val t0 = System.currentTimeMillis()
        while (true) {
            elapsedMs = System.currentTimeMillis() - t0
            kotlinx.coroutines.delay(100)
        }
    }
    // shimmer：渐变高光横扫文字（animateFloat 驱动 Brush 偏移）
    val shimmer by androidx.compose.animation.core.rememberInfiniteTransition(label = "shim")
        .animateFloat(
            initialValue = 0f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing)),
            label = "shimmer"
        )
    val base = MaterialTheme.colorScheme.onSurfaceVariant
    val hi = MaterialTheme.colorScheme.primary
    Row(verticalAlignment = Alignment.CenterVertically) {
        // 连接状态点（呼吸）
        val breathe by androidx.compose.animation.core.rememberInfiniteTransition(label = "conn")
            .animateFloat(0.4f, 1f, infiniteRepeatable(tween(900)), label = "breathe")
        Box(
            Modifier
                .size(7.dp)
                .graphicsLayer { alpha = breathe }
                .background(MaterialTheme.colorScheme.primary, CircleShape)
        )
        Spacer(Modifier.size(9.dp))
        Text(
            text = phrases[idx % phrases.size],
            style = MaterialTheme.typography.bodyMedium.copy(
                brush = androidx.compose.ui.graphics.Brush.linearGradient(
                    colors = listOf(base, hi, base),
                    start = androidx.compose.ui.geometry.Offset((shimmer * 2f - 0.5f) * 240f, 0f),
                    end = androidx.compose.ui.geometry.Offset((shimmer * 2f + 0.5f) * 240f, 0f)
                )
            )
        )
        Spacer(Modifier.size(8.dp))
        Text(
            String.format(Locale.US, "%.1fs", elapsedMs / 1000.0),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
        )
    }
}

/** ① 流式光标：2dp 细线柔和呼吸（0.15↔0.7 缓动），v4-3 不再硬闪抢戏。 */
@Composable
private fun BlinkCursor() {
    val transition = rememberInfiniteTransition(label = "cursor")
    val phase by transition.animateFloat(
        initialValue = 0.15f, targetValue = 0.7f,
        animationSpec = infiniteRepeatable(tween(1060, easing = LinearEasing)),
        label = "phase"
    )
    Box(
        Modifier
            .width(2.dp)
            .height(14.dp)
            .graphicsLayer { alpha = phase }
            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(1.dp))
    )
}

/**
 * ④ 思考过程面板（上游 ChainOfThought 同思路）：
 * - live（正文未出）：标题 shimmer「正在思考」，内容只显示约 66dp 的底部渐隐预览，
 *   不再全展开把正文顶出屏幕；
 * - 正文开始（autoCollapse）后自动收起为「💭 已思考 N 秒 ▸」，点击可展开看全文；
 * - 历史消息默认收起，点击展开。
 */
@Composable
private fun ReasoningPanel(
    text: String,
    live: Boolean,
    autoCollapse: Boolean = false,
    thinkingMs: Long? = null
) {
    var userToggled by rememberSaveable { mutableStateOf(false) }
    var expanded by rememberSaveable { mutableStateOf(live) }
    // 正文开始输出时自动收起（除非用户手动展开过）
    LaunchedEffect(autoCollapse, live) {
        if (autoCollapse && !userToggled) expanded = false
    }
    // live 且未展开全文时：受限高度 + 底部渐隐预览
    val previewMode = live && !userToggled
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = chatBubbleAlphas().second),
        shape = RoundedCornerShape(13.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(13.dp))
            .clickable { userToggled = true; expanded = !expanded }
    ) {
        Column(Modifier.padding(vertical = 8.dp)) {
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
                if (live) {
                    // 标题 shimmer：渐变高光横扫
                    val shim by androidx.compose.animation.core.rememberInfiniteTransition(label = "rshim")
                        .animateFloat(0f, 1f, infiniteRepeatable(tween(1700, easing = LinearEasing)), label = "rp")
                    Text(
                        "正在思考",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            brush = androidx.compose.ui.graphics.Brush.linearGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.onSurfaceVariant,
                                    MaterialTheme.colorScheme.primary,
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                ),
                                start = androidx.compose.ui.geometry.Offset((shim * 2f - 0.5f) * 160f, 0f),
                                end = androidx.compose.ui.geometry.Offset((shim * 2f + 0.5f) * 160f, 0f)
                            )
                        )
                    )
                } else {
                    // 收起态：显示思考用时（有值时），否则「思考过程」
                    Text(
                        thinkingMs?.let { "已思考 ${String.format(Locale.US, "%.1f", it / 1000.0)} 秒" }
                            ?: "思考过程",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.weight(1f))
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.size(12.dp))
            }
            androidx.compose.animation.AnimatedVisibility(expanded) {
                if (previewMode) {
                    // live 预览：限高 + 底部渐隐遮罩，内容自动滚到底跟随最新推理
                    val previewScroll = rememberScrollState()
                    LaunchedEffect(text) { previewScroll.scrollTo(previewScroll.maxValue) }
                    Box(Modifier.padding(horizontal = 12.dp).padding(top = 5.dp)) {
                        Text(
                            text,
                            style = MaterialTheme.typography.bodySmall,
                            lineHeight = 17.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.92f),
                            modifier = Modifier
                                .heightIn(max = 66.dp)
                                .verticalScroll(previewScroll)
                        )
                        // 底部渐隐（与面板底色一致）
                        Box(
                            Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .height(28.dp)
                                .background(
                                    androidx.compose.ui.graphics.Brush.verticalGradient(
                                        colors = listOf(
                                            Color.Transparent,
                                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = chatBubbleAlphas().second)
                                        )
                                    )
                                )
                        )
                    }
                } else {
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
private fun StreamingItem(
    streamingText: String?,
    streamingReasoning: String?,
    thinkingHint: String? = null,
    thinkingMs: Long? = null,
    liveTools: List<com.haoai.agent.ui.UiTool> = emptyList(),
    running: Boolean = false,
    onStopRun: () -> Unit = {},
    onViewDiff: (String) -> Unit = {}
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 5.dp)
    ) {
        val hasContent = !streamingText.isNullOrBlank()
        // 方案 C 时间轴聚合卡：思考/工具按真实时间序进同一张卡，卡内滚动，
        // 外层列表只随正文气泡生长——顺序错乱与自动弹底从机制上消除。
        // 无任何活动且无正文时（prefill/等首 token）显示轻量等待行，不再渲染空泡。
        val hasActivity = streamingReasoning != null || liveTools.isNotEmpty()
        if (hasActivity) {
            ActivityTimelineCard(
                reasoning = streamingReasoning,
                tools = liveTools,
                streaming = hasContent,
                thinkingMs = thinkingMs,
                onStopRun = onStopRun,
                onViewDiff = onViewDiff
            )
        } else if (!hasContent) {
            ThinkingLine(thinkingHint)
        }
        if (hasContent) {
            if (hasActivity) Spacer(Modifier.size(5.dp))
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = chatBubbleAlphas().second),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                // ① 光标不再拼进 markdown 源文本（避免污染行内正则/解析）；
                // v4-3 起光标经 InlineTextContent 内联在最后一个字符后（showCursor），
                // ③ streaming=true 启用冻结前缀增量解析
                MarkdownText(
                    streamingText.orEmpty(),
                    streaming = true,
                    showCursor = true,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                )
            }
        }
    }
}

/**
 * 方案 A · 执行流聚合卡（骨架）：整回合思考+工具收进一张玻璃卡，紧凑行动作流。
 * - 新活动永远追加在最底，顺序确定（根治"工具跑到思考上面"）；
 * - 卡限高 ~40% 屏，超出在卡内滚动跟随；外层列表只随正文气泡生长（根治弹底）；
 * - 正文开始后自动收成摘要行；结束后收成「已完成 N 个动作」，点击回看。
 */
@Composable
private fun ActivityTimelineCard(
    reasoning: String?,
    tools: List<com.haoai.agent.ui.UiTool>,
    streaming: Boolean,
    thinkingMs: Long? = null,
    onStopRun: () -> Unit = {},
    onViewDiff: (String) -> Unit = {}
) {
    val doneCount = tools.count { it.state != ToolRunState.RUNNING }
    val runningCount = tools.count { it.state == ToolRunState.RUNNING }
    val live = streaming == false && (runningCount > 0 || reasoning != null)
    val actionCount = doneCount + runningCount

    // 展开态：流式中默认展开（活动是主角），历史消息/正文已出默认收起成摘要头
    var expanded by rememberSaveable { mutableStateOf(streaming == false && live) }
    var userToggled by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(streaming) {
        if (streaming && !userToggled) expanded = false
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = chatBubbleAlphas().second),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable {
                userToggled = true
                expanded = !expanded
            }
    ) {
        Column(Modifier.padding(vertical = 6.dp)) {
            // ── 头部：状态 + 动作计数 ──
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (live) {
                    val pulse by androidx.compose.animation.core.rememberInfiniteTransition(label = "pulse")
                        .animateFloat(0.55f, 1f, infiniteRepeatable(tween(900)), label = "p")
                    Box(
                        Modifier
                            .size(8.dp)
                            .graphicsLayer { scaleX = pulse; scaleY = pulse; alpha = pulse }
                            .background(MaterialTheme.colorScheme.primary, CircleShape)
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(
                        "正在执行",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    Icon(
                        Icons.Filled.Checklist,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(
                        "已完成 $actionCount 个动作",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (actionCount > 0) {
                    Spacer(Modifier.size(8.dp))
                    Text(
                        "$actionCount",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                    )
                }
                Spacer(Modifier.weight(1f))
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
            // 收起态摘要：最近一条活动的单行（思考 ticker / 工具行）
            if (!expanded) {
                val lastTool = tools.lastOrNull()
                Row(
                    Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (reasoning != null && lastTool == null) {
                        // 思考中：右侧滚动跟随（收起态也跟随）
                        ReasoningTickerInline(text = reasoning, thinkingMs = thinkingMs)
                    } else if (lastTool != null) {
                        TimelineToolRowCompact(lastTool)
                    }
                }
            } else {
                Spacer(Modifier.size(4.dp))
                TimelineBody(
                    reasoning = reasoning,
                    tools = tools,
                    thinkingMs = thinkingMs,
                    onStopRun = onStopRun,
                    onViewDiff = onViewDiff
                )
            }
        }
    }
}

/** 收起态单行工具摘要（状态点 + 图标 + 中文动词加粗）。须在 RowScope 内调用。 */
@Composable
private fun androidx.compose.foundation.layout.RowScope.TimelineToolRowCompact(tool: com.haoai.agent.ui.UiTool) {
    if (tool.state == ToolRunState.RUNNING) {
        CircularProgressIndicator(
            modifier = Modifier.size(11.dp),
            strokeWidth = 1.5.dp,
            color = MaterialTheme.colorScheme.primary
        )
    } else {
        Box(
            Modifier
                .size(8.dp)
                .background(
                    if (tool.state == ToolRunState.ERROR) MaterialTheme.colorScheme.error else Color(0xFF7BD88F),
                    CircleShape
                )
        )
    }
    Spacer(Modifier.size(7.dp))
    Text(toolIcon(tool.name), fontSize = 12.sp)
    Spacer(Modifier.size(7.dp))
    Text(
        toolVerb(tool),
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurface,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f)
    )
}

/** 工具中文动词行文本（简报已是中文动词层，直接用；空则回退原名）。 */
private fun toolVerb(tool: com.haoai.agent.ui.UiTool): String =
    tool.brief.ifBlank { tool.name }

/** 工具类别图标：ToolBrief.iconOf 的 UI 侧兜底。 */
private fun toolIcon(name: String): String =
    runCatching { com.haoai.agent.agent.tools.ToolBrief.iconOf(name) }.getOrDefault("🔧")

/** 展开态主体：紧凑行动作流（无竖线无节点），思考/工具按到达序排列。 */
@Composable
private fun TimelineBody(
    reasoning: String?,
    tools: List<com.haoai.agent.ui.UiTool>,
    thinkingMs: Long? = null,
    onStopRun: () -> Unit = {},
    onViewDiff: (String) -> Unit = {}
) {
    val scroll = rememberScrollState()
    // 新活动到达自动滚到卡内底部（跟随最新动作）
    LaunchedEffect(reasoning?.length, tools.size, tools.lastOrNull()?.state) {
        scroll.animateScrollTo(scroll.maxValue)
    }
    Column(
        Modifier
            .padding(horizontal = 6.dp)
            .heightIn(max = 340.dp)
            .verticalScroll(scroll)
    ) {
        // 思考行（B 式玻璃行；流式思考是连续一段，放最前；工具执行中的 reasoning 为空）
        if (!reasoning.isNullOrBlank()) {
            ReasoningRow(reasoning, thinkingMs)
        }
        // 工具行按到达序排列
        tools.forEach { tool ->
            ToolStreamRow(tool, onViewDiff = onViewDiff, onStopRun = onStopRun)
        }
    }
}

/**
 * B 式思考行：无底色（v4-1 去黑块）——spinner/shimmer「正在思考」+ 实时计时 +
 * 右侧单行滚动跟随最新推理（中央渐亮两侧透明渐隐），点击展开全文。
 * 2 秒无新 token 视为"已思考"。
 */
@Composable
private fun ReasoningRow(
    text: String,
    thinkingMs: Long? = null
) {
    var open by rememberSaveable { mutableStateOf(false) }
    // 2s 无新内容 → live 语气转"已思考"（正文未出时的自然停顿）
    var recentUpdate by remember { mutableStateOf(true) }
    LaunchedEffect(text) {
        recentUpdate = true
        kotlinx.coroutines.delay(2000)
        recentUpdate = false
    }
    val live = recentUpdate
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable { open = !open }
            .padding(horizontal = 10.dp, vertical = 7.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ReasoningTickerInline(text = text, thinkingMs = thinkingMs, live = live)
        }
        AnimatedVisibility(open) {
            Column {
                Spacer(Modifier.size(4.dp))
                Text(
                    text,
                    style = MaterialTheme.typography.bodySmall,
                    lineHeight = 17.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.92f),
                    modifier = Modifier
                        .heightIn(max = 220.dp)
                        .verticalScroll(rememberScrollState())
                )
            }
        }
    }
    Spacer(Modifier.size(2.dp))
}

/**
 * 思考 ticker：固定「正在思考」标签（shimmer 高光横扫）+ 右侧单行滚动跟随
 * 最新推理文本（两端渐隐）+ 实时计时。须在 RowScope 内调用。
 */
@Composable
private fun androidx.compose.foundation.layout.RowScope.ReasoningTickerInline(
    text: String,
    thinkingMs: Long? = null,
    live: Boolean = true
) {
    // ticker 视口只显示行尾 ~160 字符，文本超长时滚动到尾部
    val tail = text.takeLast(160)
    // 实时计时（live 时每 100ms 刷新；结束态用定格的 thinkingMs）
    var elapsed by remember { mutableLongStateOf(0L) }
    if (live) {
        LaunchedEffect(Unit) {
            val t0 = System.currentTimeMillis()
            while (true) {
                elapsed = System.currentTimeMillis() - t0
                kotlinx.coroutines.delay(100)
            }
        }
    }
    // shimmer：渐变高光横扫标题（B 式）
    val shim by androidx.compose.animation.core.rememberInfiniteTransition(label = "rshim")
        .animateFloat(0f, 1f, infiniteRepeatable(tween(1700, easing = LinearEasing)), label = "rp")
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
        if (live) {
            CircularProgressIndicator(
                modifier = Modifier.size(11.dp),
                strokeWidth = 1.5.dp,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.size(6.dp))
        }
        if (live) {
            Text(
                "正在思考",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.SemiBold,
                    brush = Brush.linearGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.onSurfaceVariant,
                            MaterialTheme.colorScheme.primary,
                            MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        start = androidx.compose.ui.geometry.Offset((shim * 2f - 0.5f) * 160f, 0f),
                        end = androidx.compose.ui.geometry.Offset((shim * 2f + 0.5f) * 160f, 0f)
                    )
                )
            )
            Spacer(Modifier.size(5.dp))
            Text(
                String.format(Locale.US, "%.1fs", elapsed / 1000.0),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
            )
        } else {
            Text(
                "已思考",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (thinkingMs != null) {
                Spacer(Modifier.size(4.dp))
                Text(
                    String.format(Locale.US, "%.1fs", thinkingMs / 1000.0),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }
        }
        Spacer(Modifier.size(8.dp))
        // 滚动跟随视口：中央全亮、向两侧渐隐到透明的对称蒙版（v4-1，浮出感）
        val hscroll = rememberScrollState()
        LaunchedEffect(tail) { hscroll.scrollTo(hscroll.maxValue) }
        Box(
            Modifier
                .weight(1f)
                .height(16.dp)
                .drawWithContent {
                    drawContent()
                    // 七段对称渐变：0 → .18 → .62 → 1（中央）→ .62 → .18 → 0
                    drawRect(
                        brush = Brush.horizontalGradient(
                            0f to Color.Transparent,
                            0.16f to Color.Black.copy(alpha = 0.18f),
                            0.34f to Color.Black.copy(alpha = 0.62f),
                            0.5f to Color.Black,
                            0.66f to Color.Black.copy(alpha = 0.62f),
                            0.84f to Color.Black.copy(alpha = 0.18f),
                            1f to Color.Transparent
                        ),
                        blendMode = androidx.compose.ui.graphics.BlendMode.DstIn
                    )
                }
        ) {
            Text(
                tail,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                maxLines = 1,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                modifier = Modifier.horizontalScroll(hscroll)
            )
        }
    }
}

/** A 式工具行：图标盒 + 停止块/状态点 + 中文动词加粗 + 对象灰字，点击展开参数。 */
@Composable
private fun ToolStreamRow(
    tool: com.haoai.agent.ui.UiTool,
    onViewDiff: (String) -> Unit = {},
    onStopRun: () -> Unit = {}
) {
    var expanded by rememberSaveable(tool.callId) { mutableStateOf(false) }
    val canReview = (tool.name == "write" || tool.name == "edit") && tool.state == ToolRunState.DONE
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable { expanded = !expanded }
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            when (tool.state) {
                ToolRunState.RUNNING -> Box(
                    Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.error.copy(alpha = 0.14f))
                        .clickable { onStopRun() },
                    contentAlignment = Alignment.Center
                ) {
                    val pulse by androidx.compose.animation.core.rememberInfiniteTransition(label = "stop")
                        .animateFloat(0.55f, 1f, infiniteRepeatable(tween(1100)), label = "sp")
                    Box(
                        Modifier
                            .size(8.dp)
                            .graphicsLayer { alpha = pulse }
                            .background(MaterialTheme.colorScheme.error, RoundedCornerShape(2.dp))
                    )
                }
                else -> Box(
                    Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(
                            (if (tool.state == ToolRunState.ERROR) MaterialTheme.colorScheme.error else Color(0xFF7BD88F))
                                .copy(alpha = 0.14f)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        Modifier
                            .size(7.dp)
                            .background(
                                if (tool.state == ToolRunState.ERROR) MaterialTheme.colorScheme.error else Color(0xFF7BD88F),
                                CircleShape
                            )
                    )
                }
            }
            Spacer(Modifier.size(8.dp))
            Text(toolIcon(tool.name), fontSize = 12.sp)
            Spacer(Modifier.size(7.dp))
            // 中文动词加粗主位，对象灰字跟随（A 卡内行布局：动词不动、对象截断）
            Text(
                toolVerb(tool),
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            Spacer(Modifier.size(6.dp))
            if (canReview) {
                Text(
                    "查看变更",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onViewDiff(tool.callId) }
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
                Spacer(Modifier.size(2.dp))
            }
            Icon(
                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
        }
        AnimatedVisibility(expanded) {
            Column(Modifier.padding(start = 38.dp, top = 2.dp, bottom = 4.dp)) {
                if (tool.subagents.isNotEmpty()) {
                    tool.subagents.forEach { sub ->
                        val subColor = when (sub.state) {
                            "RUNNING" -> MaterialTheme.colorScheme.primary
                            "DONE" -> Color(0xFF7BD88F)
                            else -> MaterialTheme.colorScheme.error
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp)
                        ) {
                            if (sub.state == "RUNNING") {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(10.dp),
                                    strokeWidth = 1.5.dp,
                                    color = subColor
                                )
                            } else {
                                Box(
                                    Modifier
                                        .size(7.dp)
                                        .background(subColor, CircleShape)
                                )
                            }
                            Spacer(Modifier.size(6.dp))
                            Text(
                                "子代理 ${sub.index}/${sub.total}",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(Modifier.size(5.dp))
                            Text(
                                sub.brief,
                                fontSize = 10.5.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    Spacer(Modifier.size(2.dp))
                }
                Text(
                    tool.preview ?: toolVerb(tool),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
    Spacer(Modifier.size(2.dp))
}

/** 气泡不透明度（设置 30%-100%）映射为 (用户气泡 alpha, 助手气泡 alpha)；100% 时几乎不透明。 */
@Composable
private fun chatBubbleAlphas(): Pair<Float, Float> {
    val app = LocalContext.current.applicationContext as? com.haoai.agent.HaoApplication ?: return 0.20f to 0.62f
    val settings by app.container.settingsFlow.collectAsState()
    val t = (settings.bubbleOpacity.coerceIn(0.3f, 1f) - 0.3f) / 0.7f
    return (0.14f + 0.79f * t) to (0.45f + 0.52f * t)
}

@Composable
private fun UserBubble(
    row: ChatRow,
    onOpenMenu: (ChatRow) -> Unit,
    onCopyRow: (ChatRow) -> Unit,
    onQuickEdit: (ChatRow) -> Unit,
    running: Boolean
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 5.dp),
        horizontalAlignment = Alignment.End
    ) {
        Surface(
            color = MaterialTheme.colorScheme.primary.copy(alpha = chatBubbleAlphas().first),
            shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 5.dp),
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            // 长按正文 = 系统文本选择（上游 交互），不再弹操作菜单
            SelectionContainer {
                Text(
                    row.text,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp)
                )
            }
        }
        // 快捷操作行（user：复制 / 编辑重发 / 更多）
        Row(Modifier.padding(top = 1.dp, end = 2.dp)) {
            QuickActionButton(Icons.Filled.ContentCopy, "复制") { onCopyRow(row) }
            QuickActionButton(Icons.Filled.Edit, "编辑重发", enabled = !running) { onQuickEdit(row) }
            QuickActionButton(Icons.Filled.MoreVert, "更多") { onOpenMenu(row) }
        }
    }
}

@Composable
private fun AssistantBlock(
    row: ChatRow,
    onOpenMenu: (ChatRow) -> Unit,
    onCopyRow: (ChatRow) -> Unit,
    onQuickRegenerate: (ChatRow) -> Unit,
    running: Boolean,
    onViewDiff: (String) -> Unit,
    onRollback: (String) -> Unit = {},
    onStopRun: () -> Unit = {},
    showActions: Boolean = true,
    growIn: Boolean = false,
    onFooterReveal: () -> Unit = {}
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 5.dp)
    ) {
        // v4-2 聚合卡持久化：reasoning+tools 用流式期同一张活动卡渲染（收起态），
        // 流式→历史只换头部文案（正在执行→已完成 N 个动作），不再散架成
        // 「思考过程面板 + N 个散装工具条」。纯思考消息（无工具）保留旧 ReasoningPanel。
        val hasTools = row.tools.isNotEmpty()
        val hasReasoning = row.reasoning?.takeIf { it.isNotBlank() } != null
        if (hasTools) {
            ActivityTimelineCard(
                reasoning = row.reasoning,
                tools = row.tools,
                streaming = false,
                thinkingMs = null,
                onStopRun = onStopRun,
                onViewDiff = onViewDiff
            )
            Spacer(Modifier.size(5.dp))
        } else if (hasReasoning) {
            ReasoningPanel(text = row.reasoning.orEmpty(), live = false)
            Spacer(Modifier.size(5.dp))
        }
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
                    color = MaterialTheme.colorScheme.surface.copy(alpha = chatBubbleAlphas().second),
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // 长按正文 = 系统文本选择（上游 交互）；代码块内已嵌套
                    // SelectionContainer（内层优先），复制按钮用 disableSelection 隔离
                    SelectionContainer {
                        MarkdownText(
                            row.text,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                        )
                    }
                }
            }
        }
        // 快捷操作行（assistant：复制 / 重新生成 / 更多）——仅回合最终回复显示，
        // 工具循环的中间叙述（"马上帮你查"等）不渲染，避免每条都挂一排按钮。
        // growIn（流式刚结束的那一行）：footer 首帧隐藏、下一帧起 expand+fade 200ms，
        // 把操作按钮+统计行的 +46dp 硬跳吸收成生长动画；历史消息 footerShown 初值 true，
        // AnimatedVisibility 初始即可见、不播动画
        var footerShown by remember(row.key) { mutableStateOf(!growIn) }
        LaunchedEffect(row.key) {
            if (!footerShown) {
                withFrameNanos { }
                footerShown = true
                // 通知列表：footer 展开动画开始，重启 settle 追踪生长高度
                onFooterReveal()
            }
        }
        AnimatedVisibility(
            visible = footerShown && (showActions || row.completionTokens != null || row.durationMs != null),
            enter = expandVertically(tween(200)) + fadeIn(tween(200))
        ) {
            Column {
                if (showActions) {
                    Row(Modifier.fillMaxWidth().padding(start = 2.dp, end = 2.dp, top = 1.dp)) {
                        QuickActionButton(Icons.Filled.ContentCopy, "复制") { onCopyRow(row) }
                        QuickActionButton(Icons.Filled.Refresh, "重新生成", enabled = !running) { onQuickRegenerate(row) }
                        Spacer(Modifier.weight(1f))
                        QuickActionButton(Icons.Filled.MoreVert, "更多") { onOpenMenu(row) }
                    }
                }
                NerdLine(row)
            }
        }
    }
}

/** 消息快捷操作小图标（16dp 图标 + 6dp 内边距，可点区约 28dp）。 */
@Composable
private fun QuickActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Icon(
        imageVector = icon,
        contentDescription = contentDescription,
        tint = wallpaperAdaptiveGray(alpha = if (enabled) 0.8f else 0.3f),
        modifier = Modifier
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(6.dp)
            .size(16.dp)
    )
}

/**
 * 统计行（上游 NerdLine 同款）：细小灰字展示整轮 token 用量 / 速度 / 耗时。
 * 旧消息或无数据（usage 缺省）时不渲染；口径 = 整轮累计（含工具循环全部 LLM 调用）。
 */
@Composable
private fun NerdLine(row: ChatRow) {
    val pt = row.promptTokens
    val ct = row.completionTokens
    val dur = row.durationMs
    if (pt == null && ct == null && dur == null) return
    val tint = wallpaperAdaptiveGray()
    Row(
        Modifier.padding(start = 8.dp, top = 0.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        pt?.takeIf { it > 0 }?.let { NerdStat(Icons.Filled.ArrowUpward, fmtTokens(it), tint) }
        ct?.takeIf { it > 0 }?.let { NerdStat(Icons.Filled.ArrowDownward, fmtTokens(it), tint) }
        if (ct != null && ct > 0 && dur != null && dur > 0) {
            NerdStat(Icons.Filled.Bolt, String.format(Locale.US, "%.1f tok/s", ct / (dur / 1000.0)), tint)
        }
        dur?.takeIf { it > 0 }?.let { NerdStat(Icons.Filled.Schedule, String.format(Locale.US, "%.1fs", it / 1000.0), tint) }
    }
}

@Composable
private fun NerdStat(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    tint: Color
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(11.dp))
        Text(text, style = MaterialTheme.typography.labelSmall, color = tint)
    }
}

/** token 数缩写：1234 → 1.2k（超过 1 万才缩，小数字原样）。 */
private fun fmtTokens(n: Int): String =
    if (n >= 10000) String.format(Locale.US, "%.1fk", n / 1000.0) else n.toString()

@Composable
private fun ToolChip(
    tool: com.haoai.agent.ui.UiTool,
    onViewDiff: (String) -> Unit,
    onRollback: (String) -> Unit = {},
    onStopRun: () -> Unit = {}
) {
    var expanded by rememberSaveable(tool.callId) { mutableStateOf(false) }
    val canReview = (tool.name == "write" || tool.name == "edit") && tool.state == ToolRunState.DONE
    val stateColor = when (tool.state) {
        ToolRunState.RUNNING -> MaterialTheme.colorScheme.error
        ToolRunState.DONE -> Color(0xFF7BD88F)
        ToolRunState.ERROR -> MaterialTheme.colorScheme.error
        ToolRunState.DENIED -> Color(0xFFFFC46B)
    }
    // E7a 子代理卡片：任一路在跑即自动展开逐行状态；全部结束后可手动收起
    val anyRunning = tool.subagents.any { it.state == "RUNNING" }
    LaunchedEffect(anyRunning) {
        if (anyRunning) expanded = true
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = chatBubbleAlphas().second),
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
                    // ⑥ 运行中：红色停止方块（上游 ToolCallPill 同思路），点按即中断本轮
                    ToolRunState.RUNNING -> Box(
                        Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .clickable { onStopRun() },
                        contentAlignment = Alignment.Center
                    ) {
                        val pulse by androidx.compose.animation.core.rememberInfiniteTransition(label = "stop")
                            .animateFloat(0.55f, 1f, infiniteRepeatable(tween(1100)), label = "sp")
                        Box(
                            Modifier
                                .size(9.dp)
                                .graphicsLayer { alpha = pulse }
                                .background(MaterialTheme.colorScheme.error, RoundedCornerShape(2.dp))
                        )
                    }
                    else -> Box(
                        Modifier
                            .size(9.dp)
                            .background(stateColor, CircleShape)
                    )
                }
                Spacer(Modifier.size(8.dp))
                // v2 中文动词优先：主位显示中文简报（打开·开发者选项），原名+参数收进展开态
                Text(
                    toolVerb(tool),
                    fontSize = 12.5.sp,
                    lineHeight = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    runCatching {
                        com.haoai.agent.agent.tools.ToolBrief.rawOf(
                            tool.name,
                            tool.preview ?: ""
                        )
                    }.getOrNull().orEmpty().ifBlank { tool.name },
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 18.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (canReview) {
                    Text(
                        "查看变更",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onViewDiff(tool.callId) }
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                    Text(
                        "回滚",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onRollback(tool.callId) }
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                    Spacer(Modifier.size(6.dp))
                }
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
            AnimatedVisibility(expanded) {
                Column(Modifier.padding(top = 6.dp)) {
                    // E7a：spawn 工具展开时逐行显示每路子代理状态（RUNNING/DONE/ERROR + 用量）
                    if (tool.subagents.isNotEmpty()) {
                        tool.subagents.forEach { sub ->
                            val subColor = when (sub.state) {
                                "RUNNING" -> MaterialTheme.colorScheme.primary
                                "DONE" -> Color(0xFF7BD88F)
                                else -> MaterialTheme.colorScheme.error
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp)
                            ) {
                                if (sub.state == "RUNNING") {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(11.dp),
                                        strokeWidth = 1.5.dp,
                                        color = subColor
                                    )
                                } else {
                                    Box(
                                        Modifier
                                            .size(8.dp)
                                            .background(subColor, CircleShape)
                                    )
                                }
                                Spacer(Modifier.size(7.dp))
                                Text(
                                    "子代理 ${sub.index}/${sub.total}",
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(Modifier.size(6.dp))
                                Text(
                                    sub.brief,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                if (sub.tokensUsed > 0) {
                                    Spacer(Modifier.size(6.dp))
                                    Text(
                                        fmtTokens(sub.tokensUsed.toInt()) + " tok",
                                        fontSize = 10.5.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.size(2.dp))
                    }
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
    onTakePhoto: () -> Unit = {},
    onPickDocument: () -> Unit,
    onClearImage: () -> Unit,
    pendingAudio: String? = null,
    pendingVideoName: String? = null,
    onPickAudio: () -> Unit = {},
    onPickVideo: () -> Unit = {},
    onClearAudio: () -> Unit = {},
    onClearVideo: () -> Unit = {},
    onSlashCommand: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    placeholder: String = "给 HaoAI 派个活…",
    modifier: Modifier = Modifier,
    redrawKey: (() -> Any?)? = null,
    // 斜杠命令面板：渲染在本玻璃内、输入行之上——从输入栏向上生长/收回，一体感
    slashVisible: Boolean = false,
    slashQuery: String = "",
    onSlashSelect: (SlashCommand) -> Unit = {},
    onSlashDismiss: () -> Unit = {}
) {
    var toolbarExpanded by remember { mutableStateOf(false) }
    val slashCommands = SlashCommands.filter(slashQuery)

    GlassPanel(
        backdrop = backdrop,
        radius = 26.dp,
        surfaceAlpha = 0.22f,
        // 键盘抬升值作重绘键：位置变化后强制重绘折射，采样对齐新布局位置，
        // 保持完整液态效果且背景正确（matte 方案观感差已弃）
        redrawKey = redrawKey,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(top = 4.dp)) {
            androidx.compose.animation.AnimatedVisibility(
                visible = slashVisible && slashCommands.isNotEmpty(),
                enter = androidx.compose.animation.expandVertically(
                    expandFrom = Alignment.Bottom
                ) + androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.shrinkVertically(
                    shrinkTowards = Alignment.Bottom
                ) + androidx.compose.animation.fadeOut()
            ) {
                SlashCommandList(
                    commands = slashCommands,
                    onSelect = onSlashSelect,
                    onDismiss = onSlashDismiss,
                    modifier = Modifier.padding(horizontal = 6.dp)
                )
            }
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
                        "已附加图片",
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
            if (pendingAudio != null) {
                Row(
                    Modifier.padding(horizontal = 18.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.Mic,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        "已附加音频",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 6.dp),
                        maxLines = 1
                    )
                    IconButton(onClick = onClearAudio, modifier = Modifier.size(30.dp)) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "移除音频",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
            if (pendingVideoName != null) {
                Row(
                    Modifier.padding(horizontal = 18.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.Movie,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        "已附加视频 · $pendingVideoName",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 6.dp),
                        maxLines = 1
                    )
                    IconButton(onClick = onClearVideo, modifier = Modifier.size(30.dp)) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "移除视频",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
            Row(
                Modifier.padding(start = 6.dp, end = 6.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { toolbarExpanded = !toolbarExpanded }, enabled = !running) {
                    Icon(
                        if (toolbarExpanded) Icons.Filled.ExpandMore else Icons.Filled.Add,
                        contentDescription = "展开工具栏",
                        tint = if (toolbarExpanded) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
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
                val actionable = running || text.isNotBlank() || pendingImage != null || pendingAudio != null || pendingVideoName != null
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
            AnimatedVisibility(visible = toolbarExpanded) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    ToolbarButton(icon = Icons.Filled.PhotoCamera, label = "拍照", onClick = {
                        toolbarExpanded = false
                        onTakePhoto()
                    })
                    ToolbarButton(icon = Icons.Filled.AddPhotoAlternate, label = "图片", onClick = {
                        toolbarExpanded = false
                        onPickImage()
                    })
                    ToolbarButton(icon = Icons.Filled.Mic, label = "音频", onClick = {
                        toolbarExpanded = false
                        onPickAudio()
                    })
                    ToolbarButton(icon = Icons.Filled.Movie, label = "视频", onClick = {
                        toolbarExpanded = false
                        onPickVideo()
                    })
                    ToolbarButton(icon = Icons.Filled.Description, label = "文档", onClick = {
                        toolbarExpanded = false
                        onPickDocument()
                    })
                    SlashToolbarButton(onClick = {
                        toolbarExpanded = false
                        onSlashCommand()
                    })
                }
            }
        }
    }
}

@Composable
private fun ToolbarButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        modifier = Modifier.height(34.dp)
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                contentDescription = label,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.size(4.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SlashToolbarButton(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        modifier = Modifier.height(34.dp)
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "/",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.size(4.dp))
            Text(
                "命令",
                style = MaterialTheme.typography.labelSmall,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SessionsDrawer(
    agentName: String,
    subtitleLine: String,
    avatarEmoji: String,
    avatarGradient: Int,
    avatarImagePath: String?,
    sessions: List<StoredSession>,
    activeId: String?,
    backdrop: com.kyant.backdrop.backdrops.LayerBackdrop,
    drawerOpen: Boolean,
    onOpenSessions: () -> Unit,
    onSelectSession: (String) -> Unit,
    onPinSession: (String) -> Unit,
    onRenameSession: (String, String) -> Unit,
    onDeleteSession: (String) -> Unit,
    onEditProfile: () -> Unit,
    onSettings: () -> Unit
) {
    // 上游 式三段结构：档案头部 → 最近会话列表（主体，点击即切换）→ 底部导航
    val fmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
    // 当前滑出操作按钮的会话（同时只允许一张）+ 重命名目标
    var openCardId by remember { mutableStateOf<String?>(null) }
    var renameTarget by remember { mutableStateOf<StoredSession?>(null) }
    // 抽屉收起时自动收回展开的按钮
    LaunchedEffect(drawerOpen) { if (!drawerOpen) openCardId = null }

    Box(Modifier.fillMaxSize()) {
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(top = 8.dp)
            .clickable(interactionSource = null, indication = null) {
                // 点抽屉空白：呼出中只收起呼出；非呼出态吞掉点击（避免误关抽屉）
                if (openCardId != null) openCardId = null
            }
    ) {
        // 档案头部：点击头像或名字直接进入编辑
        Row(
            Modifier
                .fillMaxWidth()
                .clickable {
                    // 呼出操作按钮期间点头部：只收起呼出，不打开编辑档案
                    if (openCardId != null) openCardId = null else onEditProfile()
                }
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ProfileAvatar(
                emoji = avatarEmoji,
                gradientIndex = avatarGradient,
                fallback = agentName,
                size = 44.dp,
                imagePath = avatarImagePath
            )
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(agentName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                if (subtitleLine.isNotBlank()) {
                    Text(
                        subtitleLine,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }
        }
        HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 10.dp))

        // 最近会话列表：置顶优先（VM 排序），点击即切换并收起抽屉
        LazyColumn(
            Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 6.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            if (sessions.isEmpty()) {
                item {
                    Text(
                        "暂无历史会话",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 22.dp, vertical = 10.dp)
                    )
                }
            }
            // 不设上限：LazyColumn 惰性组合，几百条也只组合可见项
            itemsIndexed(sessions, key = { _, s -> s.id }) { _, s ->
                val active = s.id == activeId
                // 右划呼出 置顶/重命名；划入删除带松手只「上膛」（红色高亮），再拖一次/点垃圾桶才删除（进回收站，7 天可恢复）
                SwipeRevealCard(
                    isOpen = openCardId == s.id,
                    anyOpen = openCardId != null,
                    onClick = { onSelectSession(s.id) },
                    onOpenChange = { open -> openCardId = if (open) s.id else null },
                    openWidth = 104.dp,
                    deleteWidth = 200.dp,
                    onDeleteSwipe = { onDeleteSession(s.id) },
                    modifier = Modifier.padding(horizontal = 12.dp),
                    actions = {
                        FilledIconButton(
                            onClick = {
                                onPinSession(s.id)
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
                            onClick = {
                                renameTarget = s
                                openCardId = null
                            },
                            modifier = Modifier.size(44.dp),
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.10f),
                                contentColor = MaterialTheme.colorScheme.onBackground
                            )
                        ) {
                            Icon(Icons.Filled.Edit, contentDescription = "重命名", modifier = Modifier.size(19.dp))
                        }
                    },
                    content = { cardClick ->
                        // 卡面：真折射玻璃（v0.17.0 容器化时误降级为普通色块，用户要求恢复
                        // AndroidLiquidGlass 折射质感）；active 带主题色浸染，不按压缩放（避免
                        // 按压层变换被 backdrop 采样回画成四角残影）
                        com.haoai.agent.ui.common.GlassCard(
                            onClick = cardClick,
                            backdrop = backdrop,
                            shape = RoundedCornerShape(14.dp),
                            // 恢复 v0.14 薄透折射玻璃质感：面板保持磨砂（lensRadius=0）不叠
                            // 动态玻璃故文字不糊；折射渲染已被常驻隔离层兜底，四角无残影。
                            // active 用 secondary 浸染（与旧版一致），按压缩放恢复（隔离层已根治残影）
                            refract = true,
                            surfaceAlpha = if (active) 0.30f else 0.16f,
                            lensRadius = 14.dp,
                            tint = if (active) MaterialTheme.colorScheme.secondary.copy(alpha = 0.25f) else null,
                            pressScale = true,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (s.pinned) {
                                Icon(
                                    Icons.Filled.PushPin,
                                    contentDescription = "置顶",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(13.dp)
                                )
                                Spacer(Modifier.size(7.dp))
                            }
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
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        }
                    }
                )
            }
        }

        HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        DrawerEntry(Icons.AutoMirrored.Filled.Chat, "全部会话", badge = sessions.size, onClick = {
            if (openCardId != null) openCardId = null else onOpenSessions()
        })
        DrawerEntry(Icons.Filled.Settings, "设置", onClick = {
            if (openCardId != null) openCardId = null else onSettings()
        })
        Spacer(Modifier.navigationBarsPadding())
    }

    }

    renameTarget?.let { target ->
        var renameValue by remember(target.id) { mutableStateOf(target.title) }
        com.haoai.agent.ui.common.GlassAlertDialog(
            backdrop = backdrop,
            title = "重命名会话",
            onDismiss = { renameTarget = null },
            confirmLabel = "保存",
            dismissLabel = "取消",
            onConfirm = {
                onRenameSession(target.id, renameValue)
                renameTarget = null
            }
        ) {
            OutlinedTextField(
                value = renameValue,
                onValueChange = { renameValue = it.take(50) },
                label = { Text("会话名") },
                singleLine = true,
                colors = com.haoai.agent.ui.common.glassFieldColors(),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/** 侧边栏导航行：图标 + 文案 + 可选计数徽标。 */
@Composable
private fun DrawerEntry(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    badge: Int = 0,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(21.dp)
        )
        Spacer(Modifier.size(14.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (badge > 0) {
            Text(
                "$badge",
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
}

/** /status 弹窗里的键值行。 */
@Composable
private fun StatusRow(label: String, value: String) {
    Row(Modifier.padding(top = 10.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
            modifier = Modifier.width(64.dp)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f)
        )
    }
}

// 档案头像 6 色渐变表（索引对应 SettingsStore.avatarGradient）
private val AVATAR_GRADIENTS = listOf(
    listOf(Color(0xFF7BC6A5), Color(0xFF4E9F7D)),
    listOf(Color(0xFF8FB8F0), Color(0xFF5B8DEF)),
    listOf(Color(0xFFF0B37E), Color(0xFFE88D5B)),
    listOf(Color(0xFFD79BE8), Color(0xFFB06BD4)),
    listOf(Color(0xFFF09BB0), Color(0xFFE56B8F)),
    listOf(Color(0xFF9CD8C8), Color(0xFF5FB0C9))
)

/** 档案头像：图片 > emoji > 名字首字，渐变底色兜底。 */
@Composable
private fun ProfileAvatar(
    emoji: String,
    gradientIndex: Int,
    fallback: String,
    size: androidx.compose.ui.unit.Dp = 40.dp,
    imagePath: String? = null
) {
    val colors = AVATAR_GRADIENTS[gradientIndex.coerceIn(0, AVATAR_GRADIENTS.lastIndex)]
    val bmp = remember(imagePath) {
        imagePath?.let { p ->
            runCatching {
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(p, bounds)
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 512) sample *= 2
                val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
                android.graphics.BitmapFactory.decodeFile(p, opts)?.asImageBitmap()
            }.getOrNull()
        }
    }
    Box(
        Modifier
            .size(size)
            .background(Brush.linearGradient(colors), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        if (bmp != null) {
            androidx.compose.foundation.Image(
                bitmap = bmp,
                contentDescription = "头像",
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
            )
        } else if (emoji.isNotEmpty()) {
            Text(emoji, fontSize = (size.value * 0.45f).sp)
        } else {
            Text(
                fallback.take(1).ifEmpty { "AI" },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }
    }
}


/**
 * 消息长按操作面板（1.2）：Glass 底部弹层。操作项按消息角色动态出现，
 * 生成中（running）时破坏性操作置灰，仅复制可用。
 */
@Composable
private fun MessageActionPanel(
    backdrop: com.kyant.backdrop.backdrops.LayerBackdrop,
    row: ChatRow,
    running: Boolean,
    onDismiss: () -> Unit,
    onCopy: (String) -> Unit,
    onRegenerate: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onQuote: () -> Unit,
    onPreview: () -> Unit
) {
    androidx.activity.compose.BackHandler(onBack = onDismiss)
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.30f))
            .clickable(interactionSource = null, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter
    ) {
        com.haoai.agent.ui.common.GlassPanel(
            backdrop = backdrop,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp)
                .padding(bottom = 24.dp)
                .clickable(interactionSource = null, indication = null) {},
            radius = 24.dp,
            surfaceAlpha = 0.92f,
            blurRadius = 24.dp
        ) {
            Column(Modifier.padding(horizontal = 8.dp, vertical = 10.dp)) {
                Text(
                    "消息操作",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
                    modifier = Modifier.padding(start = 14.dp, bottom = 4.dp)
                )
                MessageActionItem(
                    icon = Icons.Filled.ContentCopy,
                    label = "复制全文",
                    enabled = true,
                    onClick = { onCopy(row.text) }
                )
                // 含代码块时追加逐块复制（Markdown 源码里的围栏）
                val blocks = codeBlocks(row.text)
                blocks.forEachIndexed { i, (lang, code) ->
                    val suffix = if (lang.isNotBlank()) "（" + lang + "）" else ""
                    val label = if (blocks.size > 1) "复制代码 " + (i + 1) + suffix else "复制代码" + suffix
                    MessageActionItem(
                        icon = Icons.Filled.Code,
                        label = label,
                        enabled = true,
                        onClick = { onCopy(code) }
                    )
                }
                // 网页渲染预览（上游 同款）：有文本即可用
                if (row.text.isNotBlank()) {
                    MessageActionItem(
                        icon = Icons.Filled.Web,
                        label = "网页渲染预览",
                        enabled = true,
                        onClick = onPreview
                    )
                }
                if (row.role != "user") {
                    MessageActionItem(
                        icon = Icons.Filled.Refresh,
                        label = "重新生成",
                        enabled = !running,
                        onClick = onRegenerate
                    )
                } else {
                    MessageActionItem(
                        icon = Icons.Filled.Edit,
                        label = "编辑重发",
                        enabled = !running,
                        onClick = onEdit
                    )
                }
                MessageActionItem(
                    icon = Icons.Filled.Delete,
                    label = "删除该消息",
                    enabled = !running,
                    danger = true,
                    onClick = onDelete
                )
                MessageActionItem(
                    icon = Icons.Filled.FormatQuote,
                    label = "引用到输入框",
                    enabled = !running,
                    onClick = onQuote
                )
                // 元信息行（上游 同款）：时间 + 模型名
                val meta = buildString {
                    append(SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(row.ts)))
                    row.model?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
                }
                Text(
                    meta,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f),
                    modifier = Modifier.padding(start = 14.dp, top = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun MessageActionItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean,
    danger: Boolean = false,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = (if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                .copy(alpha = if (enabled) 1f else 0.35f),
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.size(14.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (danger) FontWeight.SemiBold else FontWeight.Normal,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = if (enabled) 0.88f else 0.35f),
            modifier = Modifier.weight(1f)
        )
    }
}

/** 提取 Markdown 源码中的围栏代码块（语言, 代码）。 */
private fun codeBlocks(text: String): List<Pair<String, String>> {
    val re = Regex("```(\\w*)[ \\t]*\\n?([\\s\\S]*?)(?:```|$)")
    return re.findAll(text)
        .map { it.groupValues[1] to it.groupValues[2].removeSuffix("\n") }
        .filter { it.second.isNotBlank() }
        .toList()
}



/** Diff 审查视图（1.3）：统计 + 行级变更列表（绿增/红删/灰同），超 300 行折叠。 */
@Composable
private fun DiffReviewView(
    path: String,
    isNewFile: Boolean,
    diff: List<com.haoai.agent.ui.common.DiffLine>,
    modifier: Modifier = Modifier
) {
    var expanded by remember(path) { mutableStateOf(false) }
    val added = diff.count { it.type == com.haoai.agent.ui.common.DiffType.ADDED }
    val removed = diff.count { it.type == com.haoai.agent.ui.common.DiffType.REMOVED }
    val collapsed = !expanded && diff.size > 300
    val shown = if (collapsed) diff.take(300) else diff

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = chatBubbleAlphas().second),
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (isNewFile) "新建文件 · $added 行" else "+$added −$removed",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.weight(1f))
                Text(
                    path.substringAfterLast('/'),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (isNewFile) {
                Text(
                    "新文件不展示全文 diff，批准后写入。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            } else {
                androidx.compose.foundation.lazy.LazyColumn(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 300.dp)
                        .padding(top = 6.dp)
                ) {
                    items(shown.size) { i ->
                        val l = shown[i]
                        val (bg, fg, prefix) = when (l.type) {
                            com.haoai.agent.ui.common.DiffType.ADDED ->
                                Triple(Color(0x2E3F9E5C), Color(0xFF9FD8AE), "+")
                            com.haoai.agent.ui.common.DiffType.REMOVED ->
                                Triple(Color(0x33C25549), Color(0xFFF3B3AC), "−")
                            else -> Triple(Color.Transparent, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), " ")
                        }
                        Text(
                            text = prefix + l.text,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                            color = fg,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(bg)
                                .padding(horizontal = 6.dp, vertical = 1.dp)
                        )
                    }
                }
                if (collapsed) {
                    Text(
                        "展开查看完整 diff（共 ${diff.size} 行）",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { expanded = true }
                            .padding(top = 6.dp)
                    )
                }
            }
        }
    }
}
