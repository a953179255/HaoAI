package com.haoai.agent.ui.chat

import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.drawBehind
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
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
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
import androidx.compose.material.icons.filled.Check
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.haoai.agent.agent.engine.ToolRunState
import com.haoai.agent.data.StoredSession
import com.haoai.agent.ui.ChatRow
import com.haoai.agent.ui.ChatViewModel
import com.haoai.agent.ui.common.GlassCard
import com.haoai.agent.ui.common.GlassPanel
import com.haoai.agent.ui.common.GlassBottomSheet
import com.haoai.agent.ui.common.LiquidGlassButton
import com.haoai.agent.ui.common.MarkdownText
import com.haoai.agent.ui.common.SwipeRevealCard
import com.haoai.agent.ui.common.appLayer
import com.haoai.agent.ui.theme.wallpaperAdaptiveGray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch
import com.haoai.agent.ui.common.CompactGlassField
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.focus.onFocusChanged

/**
 * 轻量抽屉控制器：0..1 fraction 驱动布局期平移（Modifier.offset，不创建离屏层）。
 * 不用 ModalNavigationDrawer——其 sheet 的 graphicsLayer 平移动画会被玻璃 backdrop
 * 采样滞后回画，关抽屉时卡片四角闪直角残影；且动画期间禁折射又会造成暗→亮跳变。
 *
 * 支持跟手拖动（dragTo/snapTo）与松手速度判定（settle）的丝滑手感：
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

    /**
     * 快照有效性标志（仅绘制期读写，故用普通 var 不走快照订阅）：frozen 置 true 后
     * 首帧补录一次（true），期间各帧只 drawLayer；解除 frozen 时复位（false）。
     */
    var snapshotFresh = false

    /** 抽屉可见（绘制期判定，不订阅重组）：fraction>0 即冻结主界面为整页快照，
     *  抽屉玻璃磨砂"所见即所得"的最终画面（含顶栏/输入框玻璃与其文字）——
     *  exportedBackdrop 只导出玻璃表面不含内容，文字会清晰透出（用户实锤），快照才是真透明。 */
    val drawerVisible: Boolean get() = fraction.value > 0f

    /**
     * 纵深视差动画标志（v0.18.1 与 frozen 解耦）：仅「侧边栏→设置」保留
     * 1/3 滑距+缩放+淡出的纵深退出；其他离开聊天页的路径同样冻结快照
     * （退出层变静态纹理），但动画保持全宽直线滑出——观感与旧版逐路径一致。
     */
    var frozenParallax = false

    /**
     * 上一次处理过的分栏模式（横屏=两栏）。**注意它记的是"方向"而不是"抽屉开合"**。
     *
     * 为什么要有这个：聊天页那个"横屏自动展开侧栏 / 竖屏收起"的效果键在方向上，
     * 可 `LaunchedEffect` 在**每次进入聊天页**时都会跑一遍（从设置返回就是重新进入），
     * 而返回聊天时 MainActivity 刻意把侧栏恢复成展开（[snapOpen]）——
     * 于是竖屏那条分支立刻把刚恢复的侧栏又关掉了，用户看到"从设置回来，侧边栏自己收了"
     * （2026-09-30 报的缺陷）。方向没变就不该动抽屉。
     */
    private var lastTwoPane: Boolean? = null

    /**
     * 报告当前分栏模式，返回**这次是不是真的换了方向**（首次进入也算换，行为与旧版一致）。
     * 只有返回 true 时调用方才该去开/收侧栏。
     */
    fun onPaneMode(twoPane: Boolean): Boolean {
        val changed = lastTwoPane != twoPane
        lastTwoPane = twoPane
        return changed
    }

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
    scrollState: androidx.compose.foundation.ScrollState =
        androidx.compose.foundation.rememberScrollState(),
    onOpenSettings: () -> Unit,
    onOpenSessions: () -> Unit = {},
    onOpenBrowser: () -> Unit = {},
    /** 浏览器悬浮预览浮层（画在抽屉之下：抽屉打开时盖住它）。 */
    browserPreview: @Composable () -> Unit = {},
    /** 虚拟屏悬浮预览浮层（同上，纯观看）。 */
    /** 虚拟屏迷你窗（画在采样层内：抽屉玻璃可透出它的模糊实时画面）。 */
    vscreenMini: @Composable () -> Unit = {},
    /** 虚拟屏全屏查看页（画在顶栏之上：展开时盖住顶栏）。 */
    vscreenFull: @Composable () -> Unit = {},
    /** 顶栏 🌐 长按：直接进全屏浏览器。 */
    onOpenBrowserFullscreen: () -> Unit = {},
    onOpenVscreen: () -> Unit = {}
) {
    // 顶栏/输入框的导出层：它们 drawBackdrop 时把最终玻璃表面 record 进来，
    // 抽屉经 CombinedBackdrop 合成采样 —— 解决"玻璃磨砂不到玻璃"（宿主内含玻璃会成环
    // 崩溃，导出层是唯一通路）。作用域在 ChatScreen 级（ComposerBar/TopBar/抽屉三处共用）
    val topBarExportBackdrop = com.kyant.backdrop.backdrops.rememberLayerBackdrop()
    val composerExportBackdrop = com.kyant.backdrop.backdrops.rememberLayerBackdrop()
    // 顶栏/输入框的**内容导出层**（只含文字/图标，不含玻璃磨砂底）：
    // 抽屉采样 = 底层画面 + 两张内容层 → 只磨一层（根治"抽屉磨砂与顶栏/输入框
    // 自身磨砂重叠导致磨砂变重"）；文字仍被抽屉磨砂，不会清晰透出。
    val topBarContentLayer = rememberGraphicsLayer()
    val composerContentLayer = rememberGraphicsLayer()
    val topBarContentCoords = remember {
        java.util.concurrent.atomic.AtomicReference<androidx.compose.ui.layout.LayoutCoordinates?>(null)
    }
    val composerContentCoords = remember {
        java.util.concurrent.atomic.AtomicReference<androidx.compose.ui.layout.LayoutCoordinates?>(null)
    }
    // 内容层 → Backdrop 包装（坐标平移对齐，绘制期读，不订阅重组）
    fun contentLayerBackdrop(
        layer: androidx.compose.ui.graphics.layer.GraphicsLayer,
        coords: java.util.concurrent.atomic.AtomicReference<androidx.compose.ui.layout.LayoutCoordinates?>
    ): com.kyant.backdrop.Backdrop = object : com.kyant.backdrop.Backdrop {
        override val isCoordinatesDependent = true
        override fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBackdrop(
            density: androidx.compose.ui.unit.Density,
            coordinates: androidx.compose.ui.layout.LayoutCoordinates?,
            layerBlock: (androidx.compose.ui.graphics.GraphicsLayerScope.() -> Unit)?
        ) {
            val root = coords.get() ?: return
            val self = coordinates ?: return
            val offset = root.localPositionOf(self)
            withTransform({
                translate(-offset.x, -offset.y)
            }) {
                drawLayer(layer)
            }
        }
    }
    val topBarContentBackdrop = remember(topBarContentLayer) {
        contentLayerBackdrop(topBarContentLayer, topBarContentCoords)
    }
    val composerContentBackdrop = remember(composerContentLayer) {
        contentLayerBackdrop(composerContentLayer, composerContentCoords)
    }
    val context = LocalContext.current

    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    fun copyText(text: String) {
        clipboard.setText(AnnotatedString(text))
        Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
    }

    val rows by vm.rows.collectAsState()
    // 会话门控版流式展示：切到其他会话时为 null，不渲染别的会话正在生成的回答
    val streaming by vm.visibleStreamingText.collectAsState()
    val streamingReasoning by vm.visibleStreamingReasoning.collectAsState()
    // ask_user 提问卡（会话门控）：模型发起"等你拍板"时渲染在流式区下方
    val pendingAskNow by vm.visiblePendingAsk.collectAsState()
    // ask_user_batch 题组卡（会话门控）：整批问卷本地循环出题
    val pendingQuizNow by vm.visiblePendingQuiz.collectAsState()
    val thinkingMs by vm.thinkingMs.collectAsState()
    /**
     * 本轮起点时刻：界面上那根"已等 N 秒"的秒表**必须从这里取**。
     * 在组合里自己 `val t0 = now()` 起表 = 换一次屏（进设置再回聊天）就从 0 重跳，
     * 因为移动端换屏是整棵树重建（见 [ElapsedClock] 那段）。
     */
    val turnStartAt by vm.turnStartAt.collectAsState()
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
    // 相机临时文件：filesDir/camera/（file_paths 仅暴露该子目录，防 FileProvider 覆盖整个 filesDir）
    val cameraShotFile = java.io.File(java.io.File(context.filesDir, "camera"), "camera_shot.jpg")
        .apply { parentFile?.mkdirs() }
    var pendingDocumentName by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingDocumentContent by remember { mutableStateOf<String?>(null) }
    // 消息长按操作组（1.2）：目标消息 / 编辑重发草稿 / 删除确认
    var msgAction by remember { mutableStateOf<ChatRow?>(null) }
    // 网页渲染预览目标（⋮ 菜单进入）
    var previewTarget by remember { mutableStateOf<ChatRow?>(null) }
    var editTarget by remember { mutableStateOf<ChatRow?>(null) }
    var deleteTarget by remember { mutableStateOf<ChatRow?>(null) }
    // 工具详情弹层（v8 思维链）：ToolStep 点击后经 LocalOpenToolSheet 上抛，
    // 在 appLayer 外渲染 ToolDetailSheet+GlassPanel 真玻璃（独立窗口采样不到 backdrop）
    var toolSheet by remember { mutableStateOf<com.haoai.agent.ui.UiTool?>(null) }
    // 工具卡"查看变更"（1.3）：从写前快照现算 diff
    var diffViewer by remember { mutableStateOf<Pair<String, List<com.haoai.agent.ui.common.DiffLine>>?>(null) }
    // 代际栅栏（显示侧）：diff 属于打开它的那个会话，切会话即关，防旧会话的变更弹窗盖在别的会话上
    val diffViewerSessionId = vm.session.collectAsState().value?.id
    androidx.compose.runtime.LaunchedEffect(diffViewerSessionId) {
        diffViewer = null
    }
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
    // 汉堡 = 开/关切换。横屏两栏下侧栏常驻，收起它唯一的入口就是这颗钮；
    // 旧实现只 open 不 close，横屏点汉堡没反应（＝"侧边栏关不掉"）。
    fun toggleDrawer() {
        if (drawer.isOpen) scope.launch { hideIme(); drawer.close() } else openDrawer()
    }
    val density = LocalDensity.current
    // 横屏 = 两栏模式：侧栏占 28%（≈340dp），常驻可见；竖屏保持 0.72 抽屉盖层
    val cfgNow = androidx.compose.ui.platform.LocalConfiguration.current
    val landscapeTwoPane = cfgNow.screenWidthDp > cfgNow.screenHeightDp
    val drawerPanelRatio = if (landscapeTwoPane) 0.28f else 0.72f
    // 抽屉面板宽度（与 sheet 的 fillMaxWidth(drawerPanelRatio) 一致）：跟手拖动时把 px 位移归一化为 fraction
    val drawerPanelWidthDp = (cfgNow.screenWidthDp * drawerPanelRatio).dp
    // 横屏进屏自动展开侧栏（两栏）：只在"进入横屏"这一刻跑，之后用户可手动收起，不反复强制；
    // 转回竖屏时收起——竖屏侧栏是盖层，留着会挡住整屏聊天。
    // 横屏 = 两栏模式：侧栏占 28%（≈340dp），常驻可见；竖屏保持 0.72 抽屉盖层
    // 只在"方向真的变了"那一刻开/收侧栏（判据在 DrawerController.onPaneMode）：
    // 这个效果每次进入聊天页都会跑，而"从设置返回聊天"时 MainActivity 刻意把侧栏恢复成展开，
    // 不加这道闸就会立刻把刚恢复的侧栏又关掉（用户 2026-09-30 报的现象）。
    androidx.compose.runtime.LaunchedEffect(landscapeTwoPane) {
        if (!drawer.onPaneMode(landscapeTwoPane)) return@LaunchedEffect
        if (landscapeTwoPane) { if (!drawer.isOpen) drawer.snapOpen() }
        else if (drawer.isOpen) drawer.close()
    }
    // 消息列表顶部留白 = 状态栏 + 顶栏高度：列表物理延伸到玻璃顶栏下方（消息可滚入玻璃
    // 被磨砂遮住，主流聊天观感），仅用 contentPadding 保证初始首条消息停在顶栏下沿
    val topBarHeightDp = with(density) {
        WindowInsets.statusBars.getTop(density).toDp() + 60.dp
    }
    // 底部悬浮列（TaskPanel/斜杠弹层/输入框）实测高度，驱动消息列表动态底部留白
    var bottomBarHeightPx by remember { mutableStateOf(0) }

    // 斜杠命令状态
    var slashFilterQuery by remember { mutableStateOf("") }
    var slashMenuVisible by remember { mutableStateOf(false) }
    var showSlashHelp by remember { mutableStateOf(false) }
    var showStatusPopup by remember { mutableStateOf(false) }
    var showModelPicker by remember { mutableStateOf(false) }
    var showProfileEdit by remember { mutableStateOf(false) }
    // 聊天输入框聚焦态（由 ComposerBar 上报）：键盘避让的唯一判据
    var composerFocused by remember { mutableStateOf(false) }
    // 顶栏会话名点击重命名
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf("") }
    // ── 键盘上移（只服务于"聚焦的聊天输入框"）──
    // 底部列自带 navigationBarsPadding，若按完整 ime 高度上移会多抬一个导航栏高度，形成键盘空隙
    val imeHeightPx = WindowInsets.ime.getBottom(density)
    val navBarPx = WindowInsets.navigationBars.getBottom(density)
    // 弹窗（编辑档案/重命名/模型选择等）里的输入框有自己的键盘避让，
    // **不应带动聊天内容一起上移**（用户实锤：在侧边栏编辑档案里打字，聊天页内容仍在抬）
    val overlayDialogOpen = showProfileEdit || showRenameDialog || showModelPicker ||
        showStatusPopup || showSlashHelp
    // 判据是"聊天输入框自己聚焦"而非"键盘是否存在"：关闭弹窗时键盘要收 ~200ms，
    // 期间若按"无弹窗 + 有键盘"判定，聊天内容会被抬起再落下（用户实锤闪动）。
    // 以聚焦为准则窗口不存在：聊天输入框没聚焦 → 恒不抬。
    val keyboardLiftPx = if (overlayDialogOpen || !composerFocused) 0
        else (imeHeightPx - navBarPx).coerceAtLeast(0)
    // 玻璃 effects 在「绘制期」读取这个 State 注册快照订阅：键盘动画每帧变更 →
    // backdrop 节点失效重绘 → 采样 offset 用最新布局坐标重算。effects 里读普通
    // Int（keyboardLiftPx 参数）不会注册订阅——这正是输入栏透出抬升前旧背景的根因
    val keyboardLiftState = remember { androidx.compose.runtime.mutableIntStateOf(0) }
    keyboardLiftState.intValue = keyboardLiftPx
    // 任务面板的展开态挂在 VM 的 TaskPanelState 上，**不放在这儿的 remember 里**：
    // 去一趟设置页再回来，这棵 Composable 树整个重建，remember 归零、下面那条自动展开
    // 又跑一遍 = 面板"每次回来都重新展开一次"（2026-09-30 用户报的现场缺陷），
    // 他自己刚收起的那一步也会一起丢。
    // v9 方案B 的语义照旧：按会话绑定——切换/新建会话时归位，他处开过的面板不残留。
    val taskSession = vm.session.collectAsState().value
    val taskSessionKey = taskSession?.id
    androidx.compose.runtime.LaunchedEffect(taskSessionKey) {
        vm.taskPanel.onEnter(taskSessionKey)
    }

    // 新任务清单到达（首条 id 变化）时自动展开一次；同一份清单不再重复展开，
    // 所以"收起来之后重进本会话"不会再被拉开。
    LaunchedEffect(todoItems.firstOrNull()?.id) {
        vm.taskPanel.maybeAutoExpand(todoItems.firstOrNull()?.id)
    }

    // 系统返回手势：弹层优先关闭，其次侧边栏，避免把应用最小化
    androidx.activity.compose.BackHandler(enabled = showProfileEdit) { hideIme(); showProfileEdit = false }
    androidx.activity.compose.BackHandler(enabled = showRenameDialog) { hideIme(); showRenameDialog = false }
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
    // 横屏两栏的「让位」修饰符：侧栏是分区不是盖层，所以凡与聊天内容平级、声明在
    // appLayer 之外的浮层（玻璃顶栏、上下文详情、底部输入列）都必须按同一 fraction
    // 让出侧栏宽度。此前只让了消息列：顶栏汉堡被面板压住 → 横屏侧栏关不掉，
    // 输入框左端也整截藏在面板下（2026-09-26 实锤）。
    // fraction 必须先夹回 0..1：抽屉用 spring 收合，阻尼<1 会冲过头到负值，
    // 而 padding 只接受非负——不夹就是「点汉堡关侧栏 → IllegalArgumentException 崩应用」
    //（实测崩溃栈 ChatScreen.kt:730 Padding must be non-negative）。
    // 旧抽屉只把 fraction 用在 alpha/offset 上，负值无害，所以这个坑是新引入的。
    val twoPaneShift =
        if (landscapeTwoPane) Modifier.padding(start = drawerPanelWidthDp * drawerFraction.coerceIn(0f, 1f))
        else Modifier
    // 转场快照层（v0.18.1 优化：按需录制）：常态只 drawContent()——旧实现每帧
    // 额外把整页再 record 进 GraphicsLayer 一遍，等于全页 DisplayList 每帧录两次，
    // 聊天滚动/抽屉/转场三个场景的每帧成本被凭空放大近一倍（实测聊天滚动 jank 82%）。
    // 快照唯一消费点是 frozen（push 转场退出层），改为：进入 frozen 的那一帧补录
    // 一次（该帧成本与旧常态持平），之后各帧只 drawLayer 静态纹理（纯 GPU 合成）。
    // 视觉输出与旧实现逐帧一致：frozen 首帧录的就是转场起点画面。
    val snapshotLayer = rememberGraphicsLayer()

    Box(
        Modifier
            .fillMaxSize()

            .drawWithContent {
                // 转场冻结（push 设置）：单次录制，转场期滑动的只是一张图
                if (drawer.frozen) {
                    if (!drawer.snapshotFresh) {
                        snapshotLayer.record { this@drawWithContent.drawContent() }
                        drawer.snapshotFresh = true
                    }
                    drawLayer(snapshotLayer)
                } else {
                    drawer.snapshotFresh = false
                    drawContent()
                }
            }
    ) {
        // 采样宿主：把消息列表与两个悬浮预览窗一起录进 backdrop，
        // 抽屉玻璃即可透出它们的模糊实时画面（玻璃元素本身留在外面防递归）
        Box(Modifier.fillMaxSize().appLayer(backdrop)) {
        // 采样层根保持静止（无 offset/graphicsLayer 外层变换）：字节码确认采样 offset
        // = layerCoordinates.localPositionOf(glass, Zero)，任何外层平移都会整体错位。
        // 键盘抬升不再平移列表，改为增大 MessageList 底部留白（见 bottomPadding），
        // 顶部占位 Spacer 固定——消息永远从顶栏下方开始，不再顶入玻璃
        Column(
            Modifier
                .fillMaxSize()
                // 两栏：内容整体让出侧栏宽度（fraction 驱动的布局期 padding，
                // 与抽屉打开动画同帧；竖屏为 0，保持原盖层行为）
                .then(twoPaneShift)
                .pointerInput(Unit) {
                    detectTapGestures {
                        focusManager.clearFocus()
                        // 点消息区收起上下文详情面板（原 Popup 的 outside-dismiss 语义）
                        showContextDetail = false
                    }
                }
                .pointerInput(Unit) {
                    // 左缘右滑开抽屉：跟手拖动——越过 slop 且方向为右后
                    // 每帧把 fraction 钉到 位移/面板宽度，松手按位置+速度结算开或关
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        // 判区取 88dp：魅族 20 Pro 实测 Flyme 的手势导航**不认**
                        // systemGestureExclusionRects（冷启动后 x=8 起手仍被系统当成返回、
                        // 整页退回桌面），而起手在 35dp 就能正常呼出抽屉。60dp 只留了一线
                        // 余量，稍靠外的自然起手就漏；88dp ≈ 屏宽 1/5，配合"必须横向为主"
                        // 的方向判定，不会和消息列表的纵向滚动抢手势。
                        if (down.position.x < with(this) { 88.dp.toPx() }) {
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
            // provide 工具弹层回调：深层工具步骤（ChainCard 的 ToolStep）经
            // LocalOpenToolSheet 上抛要开哪个工具，由 ChatScreen 顶层（appLayer 外）
            // 渲染 ToolDetailSheet+GlassPanel 真玻璃——独立 Popup/Dialog 窗口采样不到 backdrop
            androidx.compose.runtime.CompositionLocalProvider(
                com.haoai.agent.ui.chat.LocalOpenToolSheet provides { t -> toolSheet = t }
            ) {
            MessageList(
                rows = rows,
                onOpenMenu = { msgAction = it },
                onCopyRow = { copyText(it.text) },
                onQuickRegenerate = { vm.regenerateFrom(it.id) },
                onQuickEdit = { editTarget = it },
                onToolViewDiff = { callId ->
                    snapshotScope.launch {
                        val sidAtRequest = vm.session.value?.id
                        val diff = vm.snapshotDiff(callId)
                        // 双保险：snapshotDiff 内部已栅栏，这里再校验一次防窗口间隙
                        if (diff != null && sidAtRequest != null && vm.isCurrentSession(sidAtRequest)) {
                            diffViewer = diff
                        }
                    }
                },
                onToolRollback = { callId ->
                    snapshotScope.launch {
                        val msg = vm.rollbackChange(callId)
                        if (msg != null) vm.showError(msg)
                    }
                },
                onStopRun = { vm.stop() },
                onStopSubagent = { vm.stopSubagent(it) },
                streamingText = streaming,
                streamingReasoning = streamingReasoning,
                running = running,
                thinkingMs = thinkingMs,
                thinkingHint = if (running && vm.isLocalProviderActive())
                    "端侧推理 · 正在理解上下文（需预处理全部提示词，可能数十秒）" else null,
                liveToolsSnapshot = liveToolsSnapshot,
                scrollState = scrollState,
                userScrolledAway = userScrolledAway,
                sessionId = vm.session.collectAsState().value?.id,
                planProposal = planProposal
                    ?.takeIf { it.first == vm.session.collectAsState().value?.id }?.second,
                onApprovePlan = { vm.approvePlan() },
                onDismissPlan = { vm.dismissPlan() },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                topPadding = topBarHeightDp + 8.dp,
                bottomPadding = with(density) {
                    maxOf(132.dp, bottomBarHeightPx.toDp() + 8.dp) + keyboardLiftPx.toDp()
                }
            )
            }
        }
            browserPreview()
            vscreenMini()
        }

        // 玻璃顶栏不能进入上面的 appLayer 子树——drawBackdrop 采样自层会递归崩溃
        val activeSession = vm.session.collectAsState().value
        Box(Modifier.align(Alignment.TopCenter).then(twoPaneShift)) {
            Column {
                TopBar(
    exportedBackdrop = topBarExportBackdrop,
    contentExportLayer = topBarContentLayer,
    contentExportCoords = topBarContentCoords,
                    title = vm.agentName(),
                    subtitle = activeSession?.title?.takeIf { it.isNotBlank() } ?: "新对话",
                    contextUsage = contextUsage,
                    backdrop = backdrop,
                    onDrawer = { toggleDrawer() },
                    onNewChat = {
                        vm.newSession()
                        scope.launch { drawer.close() }
                    },
                    onRenameSubtitle = {
                        renameText = activeSession?.title ?: ""
                        showRenameDialog = true
                    },
                    onOpenBrowser = onOpenBrowser,
                    onOpenBrowserFullscreen = onOpenBrowserFullscreen,
                    onOpenVscreen = onOpenVscreen,
                    planMode = planMode,
                    contextDetailExpanded = showContextDetail,
                    onToggleContextDetail = { showContextDetail = !showContextDetail },
                    modifier = Modifier
                )
                // 任务浮层（方案 A · 形状连续形变）：胶囊⇄面板是同一颗玻璃，
                // 宽/圆角/图标旋转/内容交叉由 morph 单值驱动（420ms easeOutQuint），
                // 高度由清单 AnimatedVisibility + animateContentSize 生长。
                // key(背景色)：主题翻转时玻璃缓存层会滞留旧表面（实测深→浅→深后胶囊
                // 滞留浅色玻璃、内容文字却已变深）——以背景色为 key 强制子树整体重建，
                // 玻璃层随主题重生。主题切换是低频事件，重建成本可忽略。
                val themeBgKey = MaterialTheme.colorScheme.background
                androidx.compose.runtime.key(themeBgKey) {
                    val taskFloatVisible = todoItems.isNotEmpty() || vm.taskPanel.forcedVisible
                    androidx.compose.animation.AnimatedVisibility(
                        visible = taskFloatVisible,
                        enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.expandVertically(),
                        exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.shrinkVertically()
                    ) {
                        TaskFloat(
                            items = todoItems,
                            expanded = vm.taskPanel.expanded,
                            onToggle = { vm.taskPanel.toggleExpanded() },
                            backdrop = backdrop
                        )
                    }
                }
                // 上下文用量详情（Z3 最上位，2026-09-10 三次修正）：保留「顶栏下沿从上
                // 往下滑出」动画与位置。关键：遮罩 Box 不得在 Column 流里占无限高度
                // （此前 fillMaxSize 占位把消息区整屏顶走、环钮点不到）。方案：覆盖层
                // 不放布局流，移到外层全屏 Box（顶栏容器同级、声明在后 = Z 最高），
                // 见 ChatScreen 外层「上下文覆盖层」注释处。
                // E1 横幅仍留本布局流（被上下文盖住是可接受瞬时态）。
                // E1 断点恢复横幅：从顶栏下沿延展出现（running 标记由 selectSession 死亡检测置入）
                val runStateNow = activeSession?.runState
                val runGoalNow = activeSession?.runGoal
                // 中断判决文案：判定与措辞都在 StoredSession.interruptionNote 里（纯函数、可单测），
                // UI 只负责显示。按会话+消息数记忆，避免每次重组重扫消息列表。
                val interruptionNote = remember(
                    activeSession?.id, activeSession?.messages?.size, runStateNow
                ) {
                    if (com.haoai.agent.data.StoredSession.resumable(runStateNow))
                        com.haoai.agent.data.StoredSession.interruptionNote(
                            runStateNow, activeSession?.messages ?: emptyList()
                        )
                    else ""
                }
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
                        surfaceAlpha = com.haoai.agent.ui.theme.haoCardSurfaceAlpha(),
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
                                    interruptionNote,
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
        }

        // 上下文详情覆盖层（Z3 最上位）：无遮罩——点 0% 环，面板从顶栏下沿滑出/
        // 收回顶栏下沿（expand/shrinkVertically 均以「顶栏下沿」为固定边：外层容器
        // 必须从顶栏下沿开始向下延展，内层 Box 顶对齐，收起时高度向顶部收拢才不会
        // 视觉上往顶栏上方缩——此前整屏容器顶对齐导致收起方向朝上，用户反馈 2026-09-10）。
        // 点面板外任意处关闭（透明点击层，视觉零打扰）。声明在顶栏容器后 = Z 最高。
        androidx.compose.animation.AnimatedVisibility(
            visible = showContextDetail,
            enter = androidx.compose.animation.fadeIn(tween(120)),
            exit = androidx.compose.animation.fadeOut(tween(120)),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .then(twoPaneShift)
                .fillMaxSize()
        ) {
            // 透明点击捕获层（无背景色）：外部点击关闭面板
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = null,
                        indication = null
                    ) { showContextDetail = false }
            ) {
                Column(
                    // 从顶栏下沿开始向下占满：面板在此容器顶部展开/收起，
                    // 固定边=顶栏下沿，收起动画视觉上「收回顶栏里」
                    Modifier
                        .fillMaxWidth()
                        .fillMaxHeight()
                ) {
                    Spacer(
                        Modifier
                            .statusBarsPadding()
                            .fillMaxWidth()
                            .height(52.dp)
                    )
                    androidx.compose.animation.AnimatedVisibility(
                        visible = showContextDetail,
                        enter = androidx.compose.animation.expandVertically(tween(260)) +
                            androidx.compose.animation.fadeIn(),
                        exit = androidx.compose.animation.shrinkVertically(tween(200)) +
                            androidx.compose.animation.fadeOut()
                    ) {
                        Box(
                            Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.TopCenter
                        ) {
                            ContextUsagePanel(
                                usage = contextUsage,
                                backdrop = backdrop,
                                // bottom 留量同理：本面板外面也是 AnimatedVisibility(clip=true)，
                                // 竖向只有 8dp 余量时下缘外阴影几乎全被裁掉
                                modifier = Modifier.padding(top = 6.dp, bottom = 22.dp)
                            )
                        }
                    }
                }
            }
        }

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .then(twoPaneShift)
                .navigationBarsPadding()
                // 同上：offset 布局期平移，保证 TaskPanel/斜杠弹层/输入框玻璃采样随键盘抬升
                .offset { androidx.compose.ui.unit.IntOffset(0, -keyboardLiftPx) }
                .onSizeChanged { bottomBarHeightPx = it.height }
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            // ask_user 提问卡：钉在输入框上方（不随消息滚动、永远贴底）——P1 复审定稿位置
            pendingAskNow?.let { ask ->
                androidx.compose.runtime.key("pending-ask-${ask.id}") {
                    PendingAskCard(
                        ask = ask,
                        onAnswer = { id, idx -> vm.answerAsk(id, idx) },
                        onAnswerFree = { id, text -> vm.answerAskFree(id, text) }
                    )
                }
            }
            // ask_user_batch 题组卡：同位置；选完自动跳下一题（点选即作答，无确认环节）
            pendingQuizNow?.let { quiz ->
                androidx.compose.runtime.key("pending-quiz-${quiz.id}") {
                    PendingQuizCard(
                        quiz = quiz,
                        onAnswer = { id, qIndex, oIndex -> vm.answerQuiz(id, qIndex, oIndex) },
                        onAnswerFree = { id, qIndex, text -> vm.answerQuizFree(id, qIndex, text) }
                    )
                }
            }
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
                        "已排队 $interjectCount 条 · 当前任务完成后自动执行（点此撤回最新一条）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
                    )
                }
            }
            // v6.1 Agent 实时工作状态行（常驻）：输入框左上方玻璃小胶囊，
            // 从发出消息起显示到回合结束；文案跟随阶段切换
            // （连接模型 → 思考中 → 执行第 N 个工具 → 生成回答）。
            // 随 ComposerBar 一起被键盘/增高上抬；斜杠面板会暂时盖住它（可接受）
            androidx.compose.animation.AnimatedVisibility(
                visible = running,
                enter = androidx.compose.animation.fadeIn(tween(160)) +
                    androidx.compose.animation.expandVertically(tween(200)),
                exit = androidx.compose.animation.fadeOut(tween(140)) +
                    androidx.compose.animation.shrinkVertically(tween(160))
            ) {
                Row(
                    Modifier.padding(start = 2.dp, bottom = 6.dp)
                ) {
                    GlassPanel(
                        backdrop = backdrop,
                        radius = 14.dp,
                        surfaceAlpha = com.haoai.agent.ui.theme.haoCardSurfaceAlpha(),
                        // v7.2：文案长短切换时胶囊宽度平滑过渡（180ms 弹性），不再瞬跳
                        modifier = Modifier.animateContentSize(
                            animationSpec = tween(180, easing = LinearEasing)
                        )
                    ) {
                        Row(
                            Modifier.padding(start = 12.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // 阶段文案（2026-09-16 重做）：**从 liveToolsSnapshot 派生**——与链卡同一
                            // 数据源，因此"屏幕上有几步"和"这里说在第几步"必然一致，不会漏步骤。
                            // 旧实现优先级写反（streaming/reasoning 排在工具之前），而工具在跑时
                            // 通常同时存在推理文本 → "正在执行工具"这档几乎永远轮不到，
                            // 用户实测：「工具在跑，这里却一直显示正在思考」。
                            // 有工具在跑 → 报告具体是哪个（动词 + 对象，带第几步）
                            val runningTool = liveToolsSnapshot.lastOrNull { it.state == ToolRunState.RUNNING }
                            // 引擎把"供应商限流/网络波动"这类瞬态重试也建模成了一个工具步骤
                            // （ToolUpdate("rate-limit", …)，见 AgentEngine 重试分支）。
                            // 它并不是"在执行某工具"，所以状态框要单独措辞——
                            // 否则会说出"正在执行 供应商限流"这种话（真机实测）。
                            val retrying = runningTool?.takeIf { it.callId.startsWith("rate-limit") }
                            val phaseText = when {
                                // ask_user 挂起等用户：优先级最高——工具在 RUNNING，但真正的事件是"等你回答"
                                pendingAskNow != null -> "等你回答"
                                // 题组同理：等的是答题进度，不是单词作答
                                pendingQuizNow != null -> "等你答题"
                                retrying != null ->
                                    (retrying.brief.ifBlank { "网络波动" }) + " · 自动重试中"
                                runningTool != null -> {
                                    val brief = runningTool.brief.ifBlank { runningTool.name }
                                    val verb = brief.substringBefore('·').trim()
                                    val obj = brief.substringAfter('·', "").trim()
                                    val label = if (obj.isNotBlank()) "$verb $obj" else verb
                                    val no = liveToolsSnapshot.indexOf(runningTool) + 1
                                    "正在执行 $label" + if (no > 1) "（第 $no 步）" else ""
                                }
                                // 顺序铁律：先报"此刻在产出什么"，再报"等待"。
                                // 反过来（已完成 N 步 排在 streaming 之前）会让正文流式中
                                // 显示"等待模型"——实测就是这么错的。
                                streaming != null -> "正在生成回答"
                                streamingReasoning != null -> "正在思考"
                                // 无工具在跑、也无流 → 才说"已完成几步、在等模型"
                                liveToolsSnapshot.isNotEmpty() ->
                                    "已完成 ${liveToolsSnapshot.count { it.state == ToolRunState.DONE }} 步 · 等待模型"
                                else -> if (vm.isLocalProviderActive())
                                    "端侧推理 · 正在理解上下文（需预处理全部提示词，可能数十秒）"
                                else "正在连接模型"
                            }
                            ThinkingIndicator(phaseText, turnStartAt)
                        }
                    }
                }
            }
            // 顶栏/输入框的导出层：它们自身 drawBackdrop 时把最终表面 record 进来
            val topBarExportBackdrop = com.kyant.backdrop.backdrops.rememberLayerBackdrop()
            val composerExportBackdrop = com.kyant.backdrop.backdrops.rememberLayerBackdrop()
            ComposerBar(
                backdrop = backdrop,
                exportedBackdrop = composerExportBackdrop,
                onFocusChange = { composerFocused = it },
                contentExportLayer = composerContentLayer,
                contentExportCoords = composerContentCoords,
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
                                    "task" -> vm.taskPanel.toggleForcedVisible()
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
                                    "task" -> vm.taskPanel.toggleForcedVisible()
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
            // 8 秒自动消失：此前只能手动关，断网报错会挂屏很久（e2e P3-6）
            LaunchedEffect(msg) {
                kotlinx.coroutines.delay(8000)
                vm.dismissError()
            }
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
            // rows 为空但正文正在流式时也要能出现（首条回复就上滑看历史的场景）——
            // 否则流式中途没有任何"回到底部"的入口，只能手动拖回去
            visible = userScrolledAway.value && (rows.isNotEmpty() || streaming != null) &&
                drawerFraction < 0.01f,
            enter = androidx.compose.animation.fadeIn(tween(180)) +
                androidx.compose.animation.expandVertically(tween(180)),
            exit = androidx.compose.animation.fadeOut(tween(140)),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = with(density) {
                    maxOf(132.dp, bottomBarHeightPx.toDp() + 8.dp) + keyboardLiftPx.toDp() + 10.dp
                })
        ) {
            // v5.1：AndroidLiquidGlass 胶囊（GlassCard 全 app 统一玻璃材质）——
            // 「↓ N 条新动态」，无新动态时收窄成「↓ 最新」；按压折射缩放同发送钮
            GlassCard(
                onClick = {
                    userScrolledAway.value = false
                    newContentTicker.value = 0
                    scope.launch { scrollState.pinToBottom() }
                },
                backdrop = backdrop,
                shape = RoundedCornerShape(percent = 50),
                surfaceAlpha = com.haoai.agent.ui.theme.haoCardSurfaceAlpha(),
                lensRadius = 14.dp
            ) {
                Row(
                    Modifier.padding(start = 16.dp, end = 18.dp, top = 9.dp, bottom = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Text(
                        "↓",
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                    val n = newContentTicker.value
                    Text(
                        if (n > 0) "$n 条新动态" else "最新",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                }
            }
        }
    }
        // 虚拟屏全屏查看页：盖住顶栏（展开态），在 scrim 之前
        vscreenFull()
        // 抽屉 scrim：透明度随 fraction，点击收起。
        // 横屏两栏没有 scrim：侧栏是常驻分区而非盖层，压暗会让右边聊天长期发灰，
        // 且点聊天区误关侧栏（用户要的是"看着用"，不是"盖着用"）
        if (drawerFraction > 0.01f && !landscapeTwoPane) {
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
                .fillMaxWidth(drawerPanelRatio)
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
                    val drawerEdge = if (MaterialTheme.colorScheme.background.luminance() < 0.5f)
                        Color.White.copy(alpha = 0.28f)
                    else Color.Black.copy(alpha = 0.20f)
                    // v7.9.2 右缘分界线：形状必须与面板一致（右上/右下 28dp 圆角）——
                    // 上一版画全高矩形条，圆角段超出玻璃边界"悬空"成两截多余线（用户截图实锤）。
                    // 用同形状 Stroke 描边：只露出右缘直线段+两段圆角弧，左缘直段被
                    // 画布裁掉（面板贴屏幕左缘），顶/底水平段因描边中心线在边界上、
                    // 只漏进 0.75dp 内侧，肉眼不可见
                    val drawerShape = RoundedCornerShape(
                        topStart = 0.dp, topEnd = 28.dp, bottomEnd = 28.dp, bottomStart = 0.dp
                    )
                    // 抽屉采样源 = 底层画面(壁纸+消息列表) + 顶栏/输入框**内容层**：
                    // 内容层不含它们的玻璃磨砂，抽屉只磨一层 → 磨砂均匀不叠加；
                    // 文字含在内容层里仍被磨砂（不会清晰透出）
                    val drawerBackdrop = com.kyant.backdrop.backdrops.rememberCombinedBackdrop(
                        backdrop, topBarContentBackdrop, composerContentBackdrop
                    )
                    GlassPanel(
                        backdrop = drawerBackdrop,
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(1f)
                            .clip(drawerShape)
                            .drawBehind {
                                val outline = drawerShape.createOutline(size, LayoutDirection.Ltr, this)
                                val p = when (outline) {
                                    is androidx.compose.ui.graphics.Outline.Rectangle -> {
                                        androidx.compose.ui.graphics.Path().apply { addRect(outline.rect) }
                                    }
                                    is androidx.compose.ui.graphics.Outline.Rounded -> {
                                        androidx.compose.ui.graphics.Path().apply { addRoundRect(outline.roundRect) }
                                    }
                                    is androidx.compose.ui.graphics.Outline.Generic -> outline.path
                                    else -> androidx.compose.ui.graphics.Path()
                                }
                                drawPath(
                                    path = p,
                                    brush = SolidColor(drawerEdge),
                                    style = Stroke(width = 1.5f.dp.toPx())
                                )
                            },
                        // 对齐设置页子菜单玻璃配方（用户：抽屉和子菜单效果差很多）：
                        // blur 16 + 折射环带 16dp（强度 2×=32dp）+ 色差 + 白雾 0.62。
                        // 磨砂重叠已由"内容层采样"根治（抽屉只磨一层），此处按观感取值
                        surfaceAlpha = 0.62f,
                        blurRadius = 16.dp,
                        lensRadius = 16.dp,
                        chromaticAberration = true,
                        // 贴屏幕左缘：左上/左下不做圆角，保证与边缘齐平的折射观感
                        shape = RoundedCornerShape(
                            topStart = 0.dp,
                            topEnd = 28.dp,
                            bottomEnd = 28.dp,
                            bottomStart = 0.dp
                        ),
                        // v7.9.1：关掉整圈发丝描边——左缘贴屏幕边，描边成了贴边白线
                        // （用户指出）；右缘分界线在下方单独画
                        border = false
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
                                // 竖屏选完收起侧栏：72% 盖层留在屏上，刚点开的对话只剩一条边
                                // （「+ 新对话」一直是这么做的，选会话却漏了）。
                                // 横屏两栏侧栏是常驻分区，保持展开不动
                                if (!landscapeTwoPane) scope.launch { drawer.close() }
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

    // 工具详情弹层（v8 思维链）：appLayer 外渲染 → GlassPanel 真玻璃折射消息流
    toolSheet?.let { t ->
        ToolDetailSheet(
            tool = t,
            backdrop = backdrop,
            onDismiss = { toolSheet = null },
            onViewDiff = { callId ->
                snapshotScope.launch {
                    val sidAtRequest = vm.session.value?.id
                    val diff = vm.snapshotDiff(callId)
                    if (diff != null && sidAtRequest != null && vm.isCurrentSession(sidAtRequest)) {
                        diffViewer = diff
                    }
                }
            },
            onStopSubagent = { vm.stopSubagent(it) }
        )
    }

    // 工具卡"查看变更"弹层（1.3）：diff 从写前快照现算
    // v8 审计修复：原 Popup + Surface 是独立窗口+实心底；现用 GlassBottomSheet 通用壳
    // （appLayer 外真玻璃 + 滑入滑出 + 拖横杠收起）
    diffViewer?.let { (path, diffLines) ->
        GlassBottomSheet(
            backdrop = backdrop,
            onDismiss = { diffViewer = null }
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 640.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp)
                    .padding(top = 4.dp, bottom = 24.dp)
            ) {
                DiffReviewView(
                    path = path,
                    isNewFile = false,
                    diff = diffLines,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.size(12.dp))
                Text(
                    "关闭",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .align(Alignment.End)
                        .clip(RoundedCornerShape(999.dp))
                        .clickable { diffViewer = null }
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                )
            }
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
            CompactGlassField(
        value = editText,
        onValueChange = { editText = it },
        label = "",
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
                // 点1：供应商下的模型直接点选切换，无需进设置
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
            onDismiss = { hideIme(); showRenameDialog = false },
            confirmLabel = "保存",
            onConfirm = {
                renameTarget?.let { vm.renameSession(it.id, renameText) }
                showRenameDialog = false
            },
            dismissLabel = "取消"
        ) {
            CompactGlassField(
        value = renameText,
        onValueChange = { renameText = it.take(50) },
        label = "会话名",
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
            onDismiss = { hideIme(); showProfileEdit = false },
            confirmLabel = "保存",
            onConfirm = {
                vm.updateProfile(editName, editEmoji, editGradient, editBio, editImagePath)
                hideIme()
                showProfileEdit = false
            },
            dismissLabel = "取消"
        ) {
            CompactGlassField(
        value = editName,
        onValueChange = { editName = it.take(20) },
        label = "名字",
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
                    // 按钮铁律：实底 + 白字（此前 0.65 主色玻璃，用户反馈配色不对）
                    surfaceColor = com.haoai.agent.ui.theme.haoButtonColors(
                        com.haoai.agent.ui.theme.HaoButtonLevel.Primary
                    ).first
                ) {
                    Text(
                        "从相册选择",
                        color = com.haoai.agent.ui.theme.haoButtonColors(
                            com.haoai.agent.ui.theme.HaoButtonLevel.Primary
                        ).second,
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
            CompactGlassField(
        value = editBio,
        onValueChange = { editBio = it.take(60) },
        label = "签名（一句话介绍）",
        modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
    )
        }
    }

    // 5.6 计划确认卡：P3 起改为行内卡（与 ask_user 提问卡同族交互），经 MessageList 渲染
    // planProposal = (所属会话 id, 计划全文)；仅当前会话匹配时显示，防切走会话后批准串台

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
    /** 顶栏玻璃导出层：抽屉合成采样用（ChatScreen 创建传入） */
    exportedBackdrop: com.kyant.backdrop.backdrops.LayerBackdrop,
    /** 顶栏内容导出层（只含文字/图标，不含玻璃磨砂底）：抽屉采样用，避免磨砂叠加 */
    contentExportLayer: androidx.compose.ui.graphics.layer.GraphicsLayer? = null,
    contentExportCoords: java.util.concurrent.atomic.AtomicReference<androidx.compose.ui.layout.LayoutCoordinates?>? = null,
    onDrawer: () -> Unit,
    onNewChat: () -> Unit,
    // 点击标题区（会话名副标题）→ 重命名当前会话
    onRenameSubtitle: () -> Unit,
    onOpenBrowser: () -> Unit = {},
    onOpenBrowserFullscreen: () -> Unit = {},
    onOpenVscreen: () -> Unit = {},
    planMode: Boolean = false,
    // 上下文详情面板：开关状态提升到 ChatScreen（面板在顶栏下方布局流里展开），
    // TopBar 只留触发点
    contextDetailExpanded: Boolean = false,
    onToggleContextDetail: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val vscreenId by com.haoai.agent.platform.vdisplay.VirtualScreenController.displayIdFlow.collectAsState()
    val density = LocalDensity.current
    val statusBarPx = WindowInsets.statusBars.getTop(density).toFloat()
    val scrimColor = MaterialTheme.colorScheme.background
    // 通栏方角顶栏：无左右边距、无圆角、无四周描边（底缘发丝线由内容
    // Row 下方的 Box 画出）；任务面板在同一块玻璃内向下一体生长（animateContentSize）
    GlassPanel(
        backdrop = backdrop,
        exportedBackdrop = exportedBackdrop,
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(),
        radius = 0.dp,
        lensRadius = com.haoai.agent.ui.theme.haoPageBarLensRadius(),
        // blurRadius 默认=radius/3，方角顶栏 radius=0 会得到 blur(0)——显式给模糊量
        blurRadius = com.haoai.agent.ui.theme.haoPageBarBlurRadius(),
        surfaceAlpha = com.haoai.agent.ui.theme.haoPageBarSurfaceAlpha(),
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
                // 内容导出：本列（文字/图标，不含玻璃底）录进 contentExportLayer
                .onGloballyPositioned { contentExportCoords?.set(it) }
                .drawWithContent {
                    contentExportLayer?.record { this@drawWithContent.drawContent() }
                    drawContent()
                }
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
            // 右侧按钮组统一 4dp 间距（IconButton 自带 12dp 视觉边距，叠加后实际
            // 图标间隙≈16dp，与左侧对称）；此前浏览器钮 2dp/虚拟屏 0dp/新会话 0dp
            // 间距不一致（用户反馈 2026-09-10）
            Spacer(Modifier.size(4.dp))
            // 单击 = 唤出悬浮预览窗（有页面时）/ 长按 = 直接进全屏（用户定稿方案 A）
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { onOpenBrowser() },
                            onLongPress = { onOpenBrowserFullscreen() }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.Public,
                    contentDescription = "内置浏览器（长按全屏）",
                    modifier = Modifier.size(22.dp),
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
            // 4.3 增强：虚拟屏活跃时显示入口，点开实时预览面板
            if (vscreenId != null) {
                Spacer(Modifier.size(4.dp))
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
            Spacer(Modifier.size(4.dp))
            CircularContextIndicator(
                usage = contextUsage,
                onClick = onToggleContextDetail,
                expanded = contextDetailExpanded
            )
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
    /** P2：终止单个运行中的子代理（参数=句柄 id）。 */
    onStopSubagent: (String) -> Unit = {},
    streamingText: String?,
    streamingReasoning: String?,
    running: Boolean,
    thinkingHint: String? = null,
    thinkingMs: Long? = null,
    liveToolsSnapshot: List<com.haoai.agent.ui.UiTool> = emptyList(),
    scrollState: androidx.compose.foundation.ScrollState,
    userScrolledAway: androidx.compose.runtime.MutableState<Boolean>,
    sessionId: String? = null,
    modifier: Modifier = Modifier,
    topPadding: androidx.compose.ui.unit.Dp = 0.dp,
    bottomPadding: androidx.compose.ui.unit.Dp,
    /** 5.6 Plan 模式计划确认卡（已按当前会话门控）；非空时渲染在提问卡之后。 */
    planProposal: String? = null,
    onApprovePlan: () -> Unit = {},
    onDismissPlan: () -> Unit = {}
) {
    val showStreaming = streamingText != null || running
    val totalItems = rows.size + (if (showStreaming) 1 else 0)

    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current

    // ── 跟随判定：位置派生（2026-09-16 第三版定案，照搬 rikkahub）────────────
    // 三段教训：
    //  v1 `isScrollInProgress` 判"用户在滑"——它不区分滚动来源（自己的 scrollBy 也算），
    //     再叠 guard 守卫 → 手势判据被全量吞掉 → 用户上滑仍被拉回底部。
    //  v2 `pointerInput 只看手指` + `userScrolledAway` 粘滞标志——标志一旦被真实手势置真，
    //     只有"用户发消息"才复位："重新生成"不复位 → 整个回复过程都不跟随。
    //  v3（本版）= **跟随只看位置**：不要任何标志参与跟随判定。
    //     rikkahub 没有这类问题的根本原因就在这里——它是无状态的位置判定
    //     （`isAtBottom()` → `requestScrollToItem(末项+10)`），任何一帧都能自愈；
    //     而任何"标志 + 守卫"的写法都引入了一个可能永久卡死的状态。
    //     userScrolledAway 从此只作「回到底部浮钮」的显隐依据，且同样由位置派生。
    val bottomSlackPx = with(androidx.compose.ui.platform.LocalDensity.current) { 48.dp.roundToPx() }
    // 「本轮用户是否主动滚动过」——发消息/新一轮生成/切会话时复位。
    // 唯一目的：让"用户全程没碰屏幕"时**永远允许贴底**，于是任何结构跳变
    // （收尾换位、长行落行）都只造成一帧偏差、下一帧自愈，不会再出现
    // "我根本没打断它，它却停在半路不动"（用户实测）。
    val userScrolledThisRun = remember { mutableStateOf(false) }

    // 发送新消息收起键盘：按末条 user 消息 key 去重——流式期间最后一条仍是 user，
    // 不能每次都收，否则用户流式中打开键盘想插话会被下一个 delta 误关
    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    var lastSentUserKey by rememberSaveable { mutableStateOf<String?>(null) }
    var lastSessionKey by rememberSaveable { mutableStateOf<String?>(null) }
    // footer 展开动画启动的通知（RowItem 生长动画仍会回调，保留供排查）
    var footerRevealTick by remember { mutableStateOf(0) }

    // 发送新消息 / 切会话：无条件贴底并把粘滞复位。
    // 只挂这两个 key——流式期的持续跟随交给下面的逐帧循环，不再靠 text.length 之类的 key。
    LaunchedEffect(rows.lastOrNull()?.key, sessionId) {
        if (totalItems <= 0) return@LaunchedEffect
        // 切会话：无条件贴底并复位粘滞（切换后停在旧位置；末条未必是 user 消息，
        // 逐帧循环又会被"已断开粘滞"拦下 → 必须先复位）
        if (sessionId != null && sessionId != lastSessionKey) {
            lastSessionKey = sessionId
            userScrolledAway.value = false
            userScrolledThisRun.value = false
            scrollState.pinToBottom()
            return@LaunchedEffect
        }
        val isUserMessage = rows.lastOrNull()?.role == "user"
        if (isUserMessage) {
            userScrolledAway.value = false
            userScrolledThisRun.value = false
            val k = rows.last().key
            if (k != lastSentUserKey) {
                lastSentUserKey = k
                keyboardController?.hide()
                focusManager.clearFocus()
            }
            scrollState.pinToBottom()
        } else {
            // 新行入列：是否跟随交给位置订阅判定，这里只在"仍贴着底"时补一次
            if (scrollState.isAtBottom(bottomSlackPx)) scrollState.pinToBottom()
        }
    }

    // ── 跟随判定（2026-09-16 第五版）──────────────────────────────────
    // 允许贴底的条件（三选一）：
    //   ① 本帧贴着底                 —— 正常跟随
    //   ② 上一帧贴着底（单帧宽限）   —— 吸收结构跳变造成的千 px 位移
    //   ③ 本轮用户从未滚动过         —— 用户没打断就不能停，任何"停住"都能自愈
    // 另加：手指/惯性滚动期间一律让位（自己的一次性贴底只占 1 帧，不会误伤跟随）。
    // 三者叠加后，"停住"只可能发生在用户自己滚走之后 —— 那正是期望行为。
    // 为什么不再单纯依赖位置：真机探针实测 delta=2816/197（收尾换位、长行落行）
    // 会让纯位置判定永久停手（位置判定无状态、不会自己回来）；③ 就是那副解药。
    // B′：判定源从"末项底边 vs 内容末端"换成 ScrollState 的 value/maxValue ——
    // 语义等价（value==maxValue 即贴底），但不再依赖 item 级 layoutInfo，判定大幅简化，
    // 也去掉了原版"末项未组合 / 末项索引对不上"那类边界情况。
    // 关键：snapshotFlow 必须**同时**订阅 value 与 maxValue —— 只订阅 value 的话，
    // 流式内容变高（maxValue 变）而滚动位置没动时不会发出，跟随会漏帧。
    LaunchedEffect(scrollState, bottomSlackPx) {
        var prevAtBottom = true
        var busyFrames = 0
        // 【自愈】2026-09-19：把跟随改成逐帧驱动后，这里再包一层 try/catch + 循环重连。
        // 理由：跟随逻辑一旦中途失效，`userScrolledThisRun` 就再也置位不了、跟随判定瘫痪，
        // 而且**没有任何可见报错**（排查时极难定位）。重连保证任何单次异常都不会让它永久失效。
        // 【逐帧循环，不用 snapshotFlow】2026-09-19：改用逐帧后有两个硬好处——
        // ① 每帧必有记录，任何"谁改了 value / 谁在还原位置"都会现形（snapshotFlow 依赖
        //    "读到的状态变了才发"，实测它在拖拽期间一次都不发，把排查带偏过一轮）；
        // ② 不依赖状态变化的采样，就不会出现"effect 看起来死了"的假象。
        while (true) {
        try {
        androidx.compose.runtime.withFrameNanos { }
        run {
                val delta = scrollState.maxValue - scrollState.value
                val atBottom = scrollState.isAtBottom(bottomSlackPx)
                // 粘滞标志：**仅**用于「回到底部」浮钮的显隐。连续两帧离开底部才置位，
                // 恢复贴底立即清除（单帧宽限，否则收尾/大重排时浮钮会闪一下）。
                // 【2026-09-19 合并进来】原先它由一个独立的 snapshotFlow effect 维护，
                // 而实测 snapshotFlow 会长时间一帧都不发 → userScrolledAway 恒为 false
                // → 浮钮永不出现（用户实测反馈 + 探针佐证：滚到顶部时 away 仍为 false）。
                val awayNow = !atBottom
                if (awayNow && !prevAtBottom) userScrolledAway.value = true
                else if (!awayNow) userScrolledAway.value = false
                busyFrames = if (scrollState.isScrollInProgress) busyFrames + 1 else 0
                if (busyFrames >= 2 && !atBottom) userScrolledThisRun.value = true
                val follow = atBottom || prevAtBottom || !userScrolledThisRun.value
                var pinned = false
                if (busyFrames < 1 && follow) pinned = scrollState.pinToBottom()
                // 诊断走**内存**（不落盘：滚动路径上做主线程 IO 会卡顿），
                // 需要时用 haoai://debug/followdump 一次性导出。
                // P0-2：这条字符串每渲染帧拼一条，探针关着时连拼都不拼
                if (FollowTrace.enabled) {
                    FollowTrace.add(
                        "busy=$busyFrames atBottom=$atBottom prev=$prevAtBottom delta=$delta " +
                            "follow=$follow pinned=$pinned scrolled=${userScrolledThisRun.value} " +
                            "pos=${scrollState.value}/${scrollState.maxValue}"
                    )
                }
                prevAtBottom = atBottom
        }
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (t: Throwable) {
            FollowTrace.addSlow(
                "FOLLOW_DIED ${t::class.java.simpleName}: ${t.message}"
            )
            kotlinx.coroutines.delay(500)
        }
        }
    }


    // ── 慢帧探针（2026-09-18）────────────────────────────────────────
    // 目的：真机实测滚动中偶发一帧 400ms（另有 85ms×2），对照实验表明它**不是**某个区域的
    // 稳定属性，而是"某次首次组合"的一次性开销；需要"抓到那一帧"才能定位到具体行。
    // 做法：Choreographer 逐帧回调，帧间隔 >=50ms 时把"可见行区间 + 各行高度"写进内存轨迹
    // （FollowTrace.addSlow，零文件 IO，不在滚动路径上分配）。
    // 判读：卡顿帧的可见区间相对上一帧扩张的那一侧 = 新进入视口的行 = 首要嫌疑。
    // P0-2：此探针是自续 120Hz Choreographer 回调 + 每帧两次 getRuntimeStat，常开即常烧；
    // 只在 followdump 深链打开 FollowTrace.enabled 后才启动（默认零成本）
    if (FollowTrace.enabled) {
    LaunchedEffect(scrollState) {
        val choreographer = android.view.Choreographer.getInstance()
        var prevFrame = 0L
        var prevGcCount = 0L
        var prevGcTime = 0L
        val cb = object : android.view.Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                val prev = prevFrame
                prevFrame = frameTimeNanos
                // B′：Column 下没有 item 索引可记（原 prevFirst/prevLast 用于对照"新进入视口的行"）
                var gcCount = 0L
                var gcTime = 0L
                runCatching {
                    gcCount = android.os.Debug.getRuntimeStat("art.gc.gc-count")?.toLongOrNull() ?: 0L
                    gcTime = android.os.Debug.getRuntimeStat("art.gc.gc-time")?.toLongOrNull() ?: 0L
                }
                val dGc = gcCount - prevGcCount
                val dGcTime = gcTime - prevGcTime
                prevGcCount = gcCount
                prevGcTime = gcTime
                if (prev != 0L) {
                    val ms = (frameTimeNanos - prev) / 1_000_000L
                    if (ms >= 50L) {
                        // B′：Column 下没有 item 级 layoutInfo，改记滚动位置与内容总高
                        FollowTrace.addSlow(
                            "SLOWFRAME ${ms}ms gc=+${dGc}(${dGcTime}ms) " +
                                "pos=${scrollState.value}/${scrollState.maxValue}"
                        )
                    }
                }
                choreographer.postFrameCallback(this)
            }
        }
        choreographer.postFrameCallback(cb)
        try {
            kotlinx.coroutines.awaitCancellation()
        } finally {
            choreographer.removeFrameCallback(cb)
        }
    }
    }

    // ── Phase 3：显示窗口 ────────────────────────────────────────────────
    // B′ 不做虚拟化：窗口内每条消息都会常驻组合（真机/模拟器实测 ≈+4.4MB/条），
    // 进页面还要把窗口内所有行一次组合 + 测量（24 行时 674~911ms）。
    // 这里只把**最近 12 条**放进 Column，向上滚到顶部附近再自动扩窗。
    // 关键：扩窗后把新增内容的高度补进滚动位置，**视口不跳**（否则上方插入会让画面位移）。
    // 不截断任何内容 —— 只是延后组合；切会话重置。
    var windowSize by remember(sessionId) { mutableStateOf(12) }
    // 【必须用 rememberUpdatedState】rows 是**值参数**不是 State：LaunchedEffect 的协程
    // 只会捕获启动那一刻的 List，之后它在循环里读到的永远是旧数量 ——
    // 实测症状：造了 6 条新消息，循环里 rows.size 仍恒为 8，于是"自动扩窗"永不触发
    // （2026-09-19 用临时诊断 WINDOW 定位）。改读 State 才能拿到最新数量。
    val rowsCount by androidx.compose.runtime.rememberUpdatedState(rows.size)
    val windowedRows = remember(rows, windowSize) {
        if (rows.size <= windowSize) rows else rows.takeLast(windowSize)
    }
    val hiddenRowCount = rows.size - windowedRows.size
    // 入场动画用（替代 LazyColumn 的 animateItem，见下方 forEach 内注释）。
    // 初值 = 首帧就存在的 keys ⇒ 首屏/切会话时不会集体淡入；之后 add 成功才是"新行"。
    val seenRowKeys = remember(sessionId) { rows.mapTo(HashSet<String>()) { it.key } }
    LaunchedEffect(sessionId) {
        while (true) {
            kotlinx.coroutines.delay(300)
            if (rowsCount > windowSize && scrollState.value <= 400) {
                val before = scrollState.maxValue
                windowSize = minOf(rowsCount, windowSize + 8)
                // 等新内容测量落地（最多 10 帧），再把它的高度补进位置
                var waited = 0
                while (waited < 10 && scrollState.maxValue == before) {
                    androidx.compose.runtime.withFrameNanos { }
                    waited++
                }
                val grown = scrollState.maxValue - before
                if (grown > 0 && scrollState.maxValue != Int.MAX_VALUE) {
                    // 补偿两步走（2026-09-19 探针实测修正）：
                    // ① 先用 UserInput 优先级的"空滚动"抢占可能仍在进行的惯性滚动（fling）。
                    //    fling 持有 Default 优先级会话：不抢占的话，它接下来会继续把 value 拉回 0
                    //    ——这就是此前 dispatchRawDelta / scrollBy 两种补偿"都没生效"的真因；
                    //    而 scrollBy 自己还会因拿不到互斥直接抛 CancellationException 被
                    //    runCatching 静默吞掉（探针实测补偿后 v 仍停在 0 的直接原因）。
                    //    若当前是手指拖拽（同为 UserInput）抢占会静默失败——无妨，拖拽是
                    //    相对位移，② 的补偿不会被它重置。
                    // ② 再用 dispatchRawDelta 立即补偿（不走滚动互斥，任何时刻都能改 value）。
                    runCatching {
                        scrollState.scroll(androidx.compose.foundation.MutatePriority.UserInput) { }
                    }
                    scrollState.dispatchRawDelta(grown.toFloat())
                }
            }
        }
    }

    // 粘滞标志（userScrolledAway）已并入上面的逐帧跟随循环统一派生 ——
    // 原实现用独立 snapshotFlow，实测会长时间不发导致浮钮永不出现（见循环内注释）。

    // 流式刚结束的那次重组（running true→false 与最终行入列同帧发生）：最终行 footer
    // （操作按钮+统计行）先隐藏、下一帧起 200ms 生长动画，把 +46dp 硬跳吸收成动画。
    // 历史消息不满足条件、静态直出
    val prevRunning = remember { mutableStateOf(running) }
    val justFinished = prevRunning.value && !running
    val justStarted = !prevRunning.value && running
    prevRunning.value = running

    // ── 新一轮开始 → 复位粘滞并贴底（2026-09-16 真机回归修复）──────────────
    // 缺这一步时的实测症状：用户此前只要上滑过一次（userScrolledAway=true），
    // 之后每次「重新生成 / 重试」都**不是用户消息**，旧的复位条件（isUserMessage）不成立
    // → 整个回复过程都不跟随；直到结束那刻流式项被移除、内容骤缩、滚动位置被钳到末端，
    // 才"啪"地跳到底部（用户描述："回复完毕后才自动跳转到最下方"）。
    // 语义：发起一轮新生成（无论来自发送还是重新生成）都应当拉回最新输出。
    LaunchedEffect(justStarted) {
        if (justStarted) {
            userScrolledAway.value = false
            userScrolledThisRun.value = false
            scrollState.pinToBottom()
        }
    }

    // B′（2026-09-19）：LazyColumn -> Column + verticalScroll。
    // 取消"滚动时首次测量巨型 item"这个动作：Column 下所有行在进入组合时一次测完，
    // 滚动只是纯位移，因此不再出现"滚到长消息卡一下"。
    // 代价：失去虚拟化（Phase 3 用显示窗口分页兜）与 animateItem 入场动画（先只保功能正确）。
    androidx.compose.foundation.layout.Column(
        modifier = modifier
            .verticalScroll(scrollState)
            .pointerInput(Unit) {
            // 只做一件事：真正拖动列表（超过 touchSlop）时收起输入法。
            // **不参与跟随判定**——跟随完全由位置决定（见 isAtBottom），
            // 避免"手指标志没复位 → 跟随永久失效"这类不可自愈的状态。
            // clearFocus 必须跳过"已被文本选择层消费的 MOVE"：长按选区出现后
            // 手指继续拖动=拖选扩选，此时 clearFocus 会直接杀死拖选手势
            // （选区瞬间消失，用户只能拖把手；demo 实测 .test-work/sel-demo 2026-09-19）。
            // 普通滚动时没人消费 MOVE，照常收键盘。
            awaitEachGesture {
                val start = awaitFirstDown(requireUnconsumed = false)
                var slopPassed = false
                val slop = 8.dp.toPx()
                while (true) {
                    val ev = awaitPointerEvent()
                    val ch = ev.changes.firstOrNull { it.id == start.id } ?: break
                    if (!slopPassed && (ch.position - start.position).getDistance() > slop) {
                        if (!ch.isConsumed) {
                            slopPassed = true
                            focusManager.clearFocus()
                        }
                    }
                    if (!ch.pressed) break
                }
            }
        }
            .padding(top = topPadding, bottom = bottomPadding)
    ) {
        // 快捷操作按钮只挂回合最终回复：usage 字段只在整轮最终消息落值；
        // 兜底 = 非运行态的最后一条（覆盖无 usage 的错误收尾行），运行中不显示
        val finalRowKey = if (!running) windowedRows.lastOrNull()?.key else null
        val growInKey = if (justFinished) finalRowKey else null
        // 窗口外还有更早的消息时给一行提示（向上滚即自动加载，不截断内容）
        if (hiddenRowCount > 0) {
            androidx.compose.runtime.key("more_history") {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "↑ 更早的消息（还有 $hiddenRowCount 条，向上滑自动加载）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.42f)
                    )
                }
            }
        }
        // B′：逐行直出。LazyColumn 的 animateItem（入场淡入/位移补间）是 lazy 专属能力，
        // 这里先只保功能正确、去掉入场动画；要恢复可用 alpha 动画补。
        windowedRows.forEach { row ->
            androidx.compose.runtime.key(row.key) {
                // 【补回入场动画】LazyColumn 的 Modifier.animateItem 是 lazy 专属能力，
                // B′ 换成 Column 后没有了。这里自己补：只为"首帧之后新出现的行"做 220ms 淡入
                // ——不能无条件给每行套淡入，否则首屏组合时所有行都会被判成新行而集体淡入。
                // 动画结束把 fadingIn 置 false 撤掉 graphicsLayer，不给已定稿的行长期留图层。
                val isNewRow = remember(row.key) { seenRowKeys.add(row.key) }
                var fadingIn by remember(row.key) { mutableStateOf(isNewRow) }
                val appearAlpha = remember(row.key) {
                    androidx.compose.animation.core.Animatable(if (isNewRow) 0f else 1f)
                }
                LaunchedEffect(row.key) {
                    if (isNewRow) {
                        appearAlpha.animateTo(1f, tween(220))
                        fadingIn = false
                    }
                }
                Box(
                    if (fadingIn) Modifier.graphicsLayer { alpha = appearAlpha.value } else Modifier
                ) {
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
            }
        }
        if (showStreaming) {
            androidx.compose.runtime.key("streaming") {
                StreamingItem(
                    streamingText,
                    streamingReasoning,
                    thinkingHint,
                    thinkingMs,
                    liveTools = liveToolsSnapshot,
                    running = running,
                    onStopRun = onStopRun,
                    onViewDiff = onToolViewDiff,
                    onStopSubagent = onStopSubagent
                )
            }
        }
        // ask_user 提问卡：P1 复审起固定钉在输入框上方（ChatScreen 底栏），不再放消息流
        planProposal?.let { plan ->
            androidx.compose.runtime.key("plan-proposal") {
                PlanProposalCard(
                    plan = plan,
                    onApprove = onApprovePlan,
                    onDismiss = onDismissPlan
                )
            }
        }
    }
}

/**
 * 是否贴着底（B′ 版，2026-09-19 由 LazyListState 版等价移植）。
 *
 * 语义与原版一致（原版是"末项底边距内容末端 ≤ slackPx"）——ScrollState 下"贴底"
 * 就是 value == maxValue，所以判定从 item 级几何退化成一次减法，**不再依赖 layoutInfo**。
 * 这既更便宜，也顺带消掉了原版"末项没被组合 / 末项索引对不上"那一类边界情况。
 *
 * 仍然用"位置"而不是任何标志来判定跟随 —— 这是 rikkahub 没有"跟随卡死"类问题的根本原因。
 * 容差 slackPx 的权衡不变：太小 → 流式一帧长高就被判成"离开底部"而停手（位置判定无状态，
 * 会一直停到你手动拖回）；太大 → 用户小幅上滑会被判成"仍在底部"而被拉回。
 * 48dp≈一行半文字，能吸收流式逐帧增长，又小于任何有意的拖动。
 */
private fun androidx.compose.foundation.ScrollState.isAtBottom(slackPx: Int): Boolean {
    // maxValue == Int.MAX_VALUE 表示尚未完成测量 —— 当作贴底（与旧版 totalItemsCount==0 同义）
    if (maxValue == Int.MAX_VALUE) return true
    return maxValue - value <= slackPx
}

/**
 * 贴底：把滚动位置补到 maxValue。**一次性、非挂起**；无位移时直接返回，
 * 因此逐帧调用是安全的（零成本）。语义与原 LazyListState 版一致。
 * 非挂起是硬要求 —— 见函数体内注释（suspend 的 scrollBy 会在用户手势期间挂起并阻塞 collect）。
 *
 * 原版为什么要 scrollBy 补差量而不是 scrollToItem(末项)：那是**顶边对齐**——流式条目
 * 长过一屏后最新内容会留在视口下方（历史踩坑）。ScrollState 下 maxValue 天然就是
 * "底边对齐"的目标位置，这个坑从根上不存在了。
 *
 * @return 是否发生位移（供排查用）
 */
private fun androidx.compose.foundation.ScrollState.pinToBottom(): Boolean {
    if (maxValue == Int.MAX_VALUE) return false
    val delta = maxValue - value
    if (delta == 0) return false
    // 【必须用 dispatchRawDelta，不能用 suspend 的 scrollBy】2026-09-19 模拟器定位：
    // scrollBy 走 MutatePriority 互斥，**用户手势持有滚动会话时它会挂起等待**。而本函数是
    // 从跟随 effect 的 collect 体里逐帧调用的 —— 一旦挂起，collect 就被阻塞，跟随判定
    // 再也不执行（userScrolledThisRun 永远置位不了），等手势结束那次位移才补上，
    // 于是表现为「上滑后 1.5 秒被弹回原位、位置回到完全一致的像素状态」。
    // 实测证据：跟随轨迹末帧停在 +45969ms，而同 composable 的定时采样一直写到 +62983ms；
    // inProgress=true 的采样里 value 却纹丝不动；全程无异常（所以不是被异常打死的）。
    dispatchRawDelta(delta.toFloat())
    return true
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
 * ask_user 提问卡（等待用户回答；交互=已确认的效果图 v3）：
 * 单选/多选/自由输入统一「选择 → 底部确认按钮」两步提交防误触，选中可改/取消；
 * 「其他」行原地变输入框，与选项共用底部确认按钮（回车=确认）。
 * 挂起语义：运行在此暂停（状态框显示"等你回答"），回答后卡片消失、链卡步骤转已答态。
 */
@Composable
private fun PendingAskCard(
    ask: com.haoai.agent.ui.ChatViewModel.PendingAsk,
    onAnswer: (String, Int) -> Unit,
    onAnswerFree: (String, String) -> Unit
) {
    val req = ask.req
    // 快速模式（模型声明 confirm=false）：点选项即提交，运行立刻继续；只保留自由输入的提交钮
    val quick = !req.confirm
    var selected by remember(ask.id) { mutableStateOf(-1) }
    var freeOpen by remember(ask.id) { mutableStateOf(false) }
    var freeText by remember(ask.id) { mutableStateOf("") }
    val primary = MaterialTheme.colorScheme.primary
    // 确认按钮的提交目标：选中项 label 或自由输入文本（互斥）
    val confirmTarget = when {
        selected in req.options.indices -> req.options[selected].label
        freeText.isNotBlank() -> freeText.trim()
        else -> null
    }
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = chatBubbleAlphas().second),
        shape = RoundedCornerShape(18.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, primary.copy(alpha = 0.55f)),
        // 宿主是底部固定栏（自带 horizontal 14dp padding），这里只给竖向间距
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp, bottom = 4.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            // 徽标行：呼吸点 + 「需要你决定」
            var dotOn by remember(ask.id) { mutableStateOf(true) }
            LaunchedEffect(ask.id) {
                while (true) {
                    dotOn = !dotOn
                    kotlinx.coroutines.delay(900)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(7.dp)
                        .graphicsLayer { alpha = if (dotOn) 1f else 0.35f }
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(primary)
                )
                Text(
                    if (quick) "点选即回答" else "需要你决定",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = primary,
                    modifier = Modifier.padding(start = 7.dp)
                )
            }
            Text(
                req.question,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 10.dp)
            )
            req.options.forEachIndexed { i, opt ->
                val isSel = selected == i
                Surface(
                    color = if (isSel) primary.copy(alpha = 0.14f)
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f),
                    shape = RoundedCornerShape(13.dp),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (isSel) primary.copy(alpha = 0.65f)
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .clickable {
                            if (quick) {
                                // 快速模式：点选即回答，无确认环节
                                onAnswer(ask.id, i)
                            } else {
                                selected = if (isSel) -1 else i
                                if (selected >= 0) freeOpen = false
                            }
                        }
                ) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AskRadioDot(isSel, primary)
                            Text(
                                opt.label,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (isSel) primary else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                            if (req.recommend && i == 0 && req.options.size > 1) {
                                Text(
                                    "推荐",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = primary,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier
                                        .padding(start = 8.dp)
                                        .background(primary.copy(alpha = 0.12f), RoundedCornerShape(999.dp))
                                        .padding(horizontal = 7.dp, vertical = 2.dp)
                                )
                            }
                        }
                        if (opt.description.isNotBlank()) {
                            Text(
                                opt.description,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                lineHeight = 16.sp,
                                modifier = Modifier.padding(start = 24.dp, top = 2.dp)
                            )
                        }
                    }
                }
            }
            if (req.allowFreeText) {
                if (!freeOpen) {
                    Surface(
                        color = Color.Transparent,
                        shape = RoundedCornerShape(13.dp),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                            .clip(RoundedCornerShape(13.dp))
                            .clickable { freeOpen = true; selected = -1 }
                    ) {
                        Text(
                            "其他…（自由输入）",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                        )
                    }
                } else {
                    // 「其他」原地变输入框：提交走底部统一确认按钮（回车=确认）
                    Surface(
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f),
                        shape = RoundedCornerShape(13.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, primary.copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    ) {
                        androidx.compose.foundation.text.BasicTextField(
                            value = freeText,
                            onValueChange = { freeText = it },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                color = MaterialTheme.colorScheme.onSurface
                            ),
                            keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                                onDone = { if (freeText.isNotBlank()) onAnswerFree(ask.id, freeText.trim()) }
                            ),
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(primary),
                            decorationBox = { inner ->
                                Box(Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
                                    if (freeText.isEmpty()) {
                                        Text(
                                            "输入你的回答，底部确认提交",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                        )
                                    }
                                    inner()
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
            // 底部统一确认按钮：就绪前禁用态提示"先选择一个选项"。
            // 快速模式且自由输入未展开时不渲染——点选项即已提交，无需确认。
            if (req.confirm || freeOpen) {
                val ready = confirmTarget != null
                Surface(
                    color = if (ready) primary.copy(alpha = 0.92f)
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(enabled = ready) {
                            when {
                                selected in req.options.indices -> onAnswer(ask.id, selected)
                                freeText.isNotBlank() -> onAnswerFree(ask.id, freeText.trim())
                            }
                        }
                ) {
                    Text(
                        if (ready) "确认：" + confirmTarget.orEmpty().take(24) else "先选择一个选项",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = if (ready) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 11.dp)
                    )
                }
            }
        }
    }
}

/**
 * ask_user_batch 题组卡：本地循环出题——点选项即记录并自动跳下一题（不回模型），
 * 答满整卡消失、引擎恢复。头部固定显示题组标题 + 第 n/N 题 + 进度条。
 * stale 防御：作答携带出题时下标，VM 比对当前下标，重组前的重复点击被丢弃。
 */
@Composable
private fun PendingQuizCard(
    quiz: com.haoai.agent.ui.ChatViewModel.PendingQuiz,
    onAnswer: (askId: String, questionIndex: Int, optionIndex: Int) -> Unit,
    onAnswerFree: (askId: String, questionIndex: Int, text: String) -> Unit
) {
    val qIndex = quiz.answered
    val total = quiz.req.questions.size
    // 答满瞬间 _pendingQuiz 已清空，正常不会重组出越界；防御性取值兜底
    val current = quiz.req.questions.getOrNull(qIndex) ?: return
    val primary = MaterialTheme.colorScheme.primary
    var freeOpen by remember(quiz.id, qIndex) { mutableStateOf(false) }
    var freeText by remember(quiz.id, qIndex) { mutableStateOf("") }
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = chatBubbleAlphas().second),
        shape = RoundedCornerShape(18.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, primary.copy(alpha = 0.55f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp, bottom = 4.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            // 头部：题组标题 + 当前进度
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(7.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(primary)
                )
                Text(
                    quiz.req.title,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = primary,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 7.dp).weight(1f)
                )
                Text(
                    "第 ${qIndex + 1} / $total 题",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // 进度条：分段跳变无动画（动画只在答题时长存在，静止零帧）
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .height(3.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(primary.copy(alpha = 0.14f))
            ) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth((qIndex + 1).toFloat() / total.coerceAtLeast(1))
                        .background(primary)
                )
            }
            Text(
                current.question,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 10.dp)
            )
            current.options.forEachIndexed { i, opt ->
                Surface(
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f),
                    shape = RoundedCornerShape(13.dp),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .clickable { onAnswer(quiz.id, qIndex, i) }
                ) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AskRadioDot(false, primary)
                            Text(
                                opt.label,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                        if (opt.description.isNotBlank()) {
                            Text(
                                opt.description,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                lineHeight = 16.sp,
                                modifier = Modifier.padding(start = 24.dp, top = 2.dp)
                            )
                        }
                    }
                }
            }
            // 自由输入：展开后出现提交按钮（选项路点选即答、无按钮，两路提交入口不同）
            if (quiz.req.allowFreeText) {
                if (!freeOpen) {
                    Surface(
                        color = Color.Transparent,
                        shape = RoundedCornerShape(13.dp),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                            .clip(RoundedCornerShape(13.dp))
                            .clickable { freeOpen = true }
                    ) {
                        Text(
                            "其他…（自由输入）",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                        )
                    }
                } else {
                    Surface(
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f),
                        shape = RoundedCornerShape(13.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, primary.copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    ) {
                        androidx.compose.foundation.text.BasicTextField(
                            value = freeText,
                            onValueChange = { freeText = it },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                color = MaterialTheme.colorScheme.onSurface
                            ),
                            keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                                onDone = {
                                    if (freeText.isNotBlank())
                                        onAnswerFree(quiz.id, qIndex, freeText.trim())
                                }
                            ),
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(primary),
                            decorationBox = { inner ->
                                Box(Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
                                    if (freeText.isEmpty()) {
                                        Text(
                                            "输入你的回答，回车或点下方提交",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                        )
                                    }
                                    inner()
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    val ready = freeText.isNotBlank()
                    Surface(
                        color = if (ready) primary.copy(alpha = 0.92f)
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(enabled = ready) {
                                onAnswerFree(quiz.id, qIndex, freeText.trim())
                            }
                    ) {
                        Text(
                            if (ready) "提交回答" else "先输入你的回答",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = if (ready) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 11.dp)
                        )
                    }
                }
            }
        }
    }
}

/** 提问卡选项单选圆点：选中填充主色打勾，未选中空心灰圈。 */
@Composable
private fun AskRadioDot(selected: Boolean, primary: Color) {
    Surface(
        color = if (selected) primary else Color.Transparent,
        shape = androidx.compose.foundation.shape.CircleShape,
        border = androidx.compose.foundation.BorderStroke(
            1.5.dp,
            if (selected) primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        ),
        modifier = Modifier.size(16.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxSize()
                .padding(2.dp)
        ) {
            if (selected) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(11.dp)
                )
            }
        }
    }
}

/**
 * Plan 计划确认卡（5.6 → P3 改版）：从居中遮罩弹窗迁到行内卡，与 ask_user 提问卡
 * 同族的"选择→确认"交互——「继续讨论」次级行 + 「批准并执行」主色确认按钮。
 * 计划全文在卡内滚动（heightIn 上限 260dp），不再遮挡整屏。
 */
@Composable
private fun PlanProposalCard(
    plan: String,
    onApprove: () -> Unit,
    onDismiss: () -> Unit
) {
    val primary = MaterialTheme.colorScheme.primary
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = chatBubbleAlphas().second),
        shape = RoundedCornerShape(18.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, primary.copy(alpha = 0.55f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Text(
                "执行计划",
                style = MaterialTheme.typography.titleSmall,
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
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 260.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
                    .verticalScroll(rememberScrollState())
                    .padding(10.dp)
            )
            Surface(
                color = Color.Transparent,
                shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onDismiss() }
            ) {
                Text(
                    "继续讨论",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 10.dp)
                )
            }
            Surface(
                color = primary.copy(alpha = 0.92f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onApprove() }
            ) {
                Text(
                    "批准并执行",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 11.dp)
                )
            }
        }
    }
}

/**
 * 系统事件条（居中小字胶囊）：压缩、交接等引擎级事件
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

/**
 * ⑤ 等待状态行（v6 移到输入框左上方玻璃胶囊内）：连接点 + 短语轮播
 * （shimmer 渐变）+ 已用时长计时。prefill 慢（端侧模型数十秒）时给用户持续"活着"的信号。
 */
@Composable
private fun ThinkingIndicator(hint: String? = null, turnStartAt: Long = 0L) {
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
    /*
     * 秒表：起点取**回合起点**，不取"本次组合开始"。
     * 原来这里是 `LaunchedEffect(Unit) { val t0 = System.currentTimeMillis(); ... }` ——
     * 换一次屏（进设置再回聊天）整棵组合树重建，t0 变成"现在"，屏幕上"已等 N 秒"
     * 从 0 重跳，看着像任务被重启。localT0 只留作"还没进过任何一轮"时的兜底。
     * key 用 turnStartAt：开新一轮时秒表要跟着重新起（不然会拿着旧起点继续加）。
     */
    val localT0 = remember(turnStartAt) { System.currentTimeMillis() }
    LaunchedEffect(turnStartAt) {
        while (true) {
            elapsedMs = ElapsedClock.ms(turnStartAt, localT0, System.currentTimeMillis())
            kotlinx.coroutines.delay(100)
        }
    }
    // shimmer：渐变高光横扫文字（P0-3：30fps 装饰驱动，不再 120Hz 全速）
    val shimmer by com.haoai.agent.ui.common.rememberPulse(0f, 1f, 1600)
    val base = MaterialTheme.colorScheme.onSurfaceVariant
    val hi = MaterialTheme.colorScheme.primary
    Row(verticalAlignment = Alignment.CenterVertically) {
        // 连接状态点（呼吸）
        val breathe by com.haoai.agent.ui.common.rememberPulse(0.4f, 1f, 900)
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
            ),
            // 阶段文案现在会带工具名/对象，可能很长：限宽 + 单行截断，
            // 免得玻璃胶囊被撑成多行把输入栏顶走
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 210.dp)
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

/**
 * ④ 思考过程面板：
 * - live（正文未出）：标题 shimmer「正在思考」，内容只显示约 66dp 的底部渐隐预览，
 *   不再全展开把正文顶出屏幕；
 * - 正文开始（autoCollapse）后自动收起为「💭 已思考 N 秒 ▸」，点击可展开看全文；
 * - 历史消息默认收起，点击展开。
 * v7.7 统一样式：白底 66% + onSurface 10% 描边（与气泡/胶囊同体系，弃灰蓝 surfaceVariant）。
 * v7.7 展开动画 = 方案 3 揭幕式（用户选型）：animateContentSize 撑开容器 +
 * 文字层 graphicsLayer scaleY 揭幕（内容零位移，遮罩自上而下揭开，Notion/Linear 质感）。
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
        // v7.8.9：底色对齐工具胶囊（surface 94% 实底）——原动态公式
        // 0.66*气泡alpha+10% 在气泡透明度低时只有 ~56%，明显比工具胶囊透
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        shape = RoundedCornerShape(16.dp),
        // v7.7：与气泡/胶囊同体系描边；v7.7.1 统一 8%（原 10% 略深）
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            // v7.8：clickable 移除——只允许标题行响应收起（点按区拆分），
            // 面板正文完全释放给文本选择/复制
    ) {
        Column(
            // v7.8.8：原 vertical 8dp padding 挪进标题行（见下）——点击区=可见胶囊 1:1
            Modifier.animateContentSize(
                animationSpec = androidx.compose.animation.core.tween(300, easing = androidx.compose.animation.core.CubicBezierEasing(0.4f, 0f, 0.2f, 1f))
            )
        ) {
            // v7.8.8 标题行改用与 ToolChip 完全相同的链路：clip(胶囊形) + 普通
            // clickable（默认 ripple）。此前手写高亮（indication=null + drawBehind
            // 自绘矩形 + 90/380ms 调参）两个顽疾：①18% 灰太淡、快点一下肉眼无反馈；
            // ②向外扩 8dp 的补偿画在 clip 之外被裁掉，高亮永远比标题栏小一圈。
            // 改回原生 ripple 后触发时机/颜色/形状与工具胶囊同源天然一致，零调参。
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(
                        if (expanded) RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
                        else RoundedCornerShape(16.dp)
                    )
                    .clickable { userToggled = true; expanded = !expanded }
                    // 上下 8dp 在 clip/clickable 内侧 → ripple 与点击区都覆盖到胶囊
                    // 全缘（含原面板 padding 区），死区随之消失
                    .padding(vertical = 8.dp),
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
                    val shim by com.haoai.agent.ui.common.rememberPulse(0f, 1f, 1700)
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
            if (expanded || previewMode) {
                if (previewMode) {
                    // live 预览：限高 + 底部渐隐遮罩，内容自动滚到底跟随最新推理
                    val previewScroll = rememberScrollState()
                    LaunchedEffect(text) { previewScroll.scrollTo(previewScroll.maxValue) }
                    // 底部 8dp 补回：原由面板 Column 的 vertical padding 提供（v7.8.8 挪进了标题行）
                    Box(Modifier.padding(horizontal = 12.dp).padding(top = 5.dp, bottom = 8.dp)) {
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
                                            MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)
                                        )
                                    )
                                )
                        )
                    }
                } else {
                    // v7.8 方案 3（用户选型）：思考竖线 + 浅底——竖线用 colorScheme.primary
                    // （随设置主题种子色/壁纸取色联动），引文式与正式回复分层
                    val lineColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                    Box(
                        Modifier
                            // bottom 2→10dp：补回原面板 Column 的底部 8dp（v7.8.8 挪进了标题行）
                            .padding(horizontal = 10.dp)
                            .padding(top = 6.dp, bottom = 10.dp)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(topEnd = 8.dp, bottomEnd = 8.dp))
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
                            // v7.8.1：只画左侧竖线（border 无 shape 默认四边框——上一版四边
                            // 全包的 bug）；竖线 = primary 45%，随主题种子/壁纸取色联动
                            .drawBehind {
                                drawRect(
                                    brush = SolidColor(lineColor),
                                    size = Size(2.5.dp.toPx(), size.height)
                                )
                            }
                            .padding(start = 12.dp, end = 10.dp, top = 6.dp, bottom = 6.dp)
                    ) {
                        Text(
                            text,
                            style = MaterialTheme.typography.bodySmall,
                            lineHeight = 17.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.92f)
                        )
                    }
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
    onViewDiff: (String) -> Unit = {},
    /** P2：终止单个运行中的子代理（参数=句柄 id）。 */
    onStopSubagent: (String) -> Unit = {}
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 2.dp)
    ) {
        val hasContent = !streamingText.isNullOrBlank()
        // v8 思维链重构（参考 RikkaHub ChainOfThought，2026-09-14 定稿）：
        // 思考 + 工具混排进一张玻璃链卡（时间轴/流式折叠），正文气泡独立在卡外。
        if (!streamingReasoning.isNullOrBlank() || liveTools.isNotEmpty()) {
            ChainCard(
                reasoning = streamingReasoning,
                thinkingMs = thinkingMs,
                reasoningLive = !hasContent,
                tools = liveTools,
                toolsLive = true,
                finished = false
            )
        }
        // 分组节奏：链卡与正文气泡之间 6dp 呼吸
        if (hasContent) {
            if (liveTools.isNotEmpty() || !streamingReasoning.isNullOrBlank()) Spacer(Modifier.size(6.dp))
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = chatBubbleAlphas().second),
                shape = RoundedCornerShape(18.dp),
                // v7.6.4：与历史气泡/工具胶囊同体系描边
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    // 块级高度动画（2026-09-16）：代码围栏闭合、列表成形、段落重排这些
                    // "块级瞬间长高"用 280ms 缓动吃掉（与链卡同 spec）——
                    // 配合逐帧贴底，上方内容的位移就从台阶变成连续曲线。
                    // 这正是 rikkahub「旧内容平滑上推」的核心机制（animateContentSize）。
                    .animateContentSize(
                        animationSpec = tween(
                            280,
                            easing = androidx.compose.animation.core.CubicBezierEasing(0.4f, 0f, 0.2f, 1f)
                        )
                    )
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


/** 工具中文动词行文本（简报已是中文动词层，直接用；空则回退原名）。 */
private fun toolVerb(tool: com.haoai.agent.ui.UiTool): String =
    tool.brief.ifBlank { tool.name }

/**
 * v7.4.3 胶囊专用动词：只取 brief 第一个「·」前的动词段——旧 toolVerb 返回整个
 * brief（动词+对象全在里），是胶囊里「动词后还拖着一长串对象」导致 ✓ 甩尾的元凶。
 */
private fun toolVerbOnly(tool: com.haoai.agent.ui.UiTool): String {
    val b = tool.brief.ifBlank { tool.name }
    return b.substringBefore('·').trim()
}


/**
 * 思考行（v5 旋钮式 ticker）：spinner/shimmer「正在思考」+ 实时计时 + 旋钮滚动
 * （先从左往右填充，满后持续左滚、中央清晰两侧淡出至透明），点击展开全文。
 * 2 秒无新 token 视为"已思考"。历史消息 live=false 直接静态显示。
 */
@Composable
private fun ReasoningRow(
    text: String,
    thinkingMs: Long? = null,
    /** 回合是否真的在跑（历史消息=false：不转圈、不计时、不滚动）。 */
    live: Boolean = true,
    /** 秒表起点＝回合起点（换屏回来不从 0 重跳；见 [ElapsedClock]）。 */
    turnStartAt: Long = 0L
) {
    var open by rememberSaveable { mutableStateOf(false) }
    // 2s 无新内容 → live 语气转"已思考"（仅运行中有意义；历史直接 false）
    var recentUpdate by remember { mutableStateOf(live) }
    LaunchedEffect(text) {
        if (!live) return@LaunchedEffect
        recentUpdate = true
        kotlinx.coroutines.delay(2000)
        recentUpdate = false
    }
    val isLive = live && recentUpdate
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp)
            // v7.7.1：与工具胶囊同体系底+描边（此前是无框裸文本行，与胶囊/气泡不统一）
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f))
            .border(
                1.dp,
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                RoundedCornerShape(16.dp)
            )
            .clickable { open = !open }
            .padding(horizontal = 10.dp, vertical = 7.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // v6.2 形态 C：思考中滚动；思考完（live 转 false）立即停住定格（仍在旋钮形态内，
            // 不切省略）——只在历史消息（showTicker=false）才走静态省略
            ReasoningTickerInline(
                text = text,
                thinkingMs = thinkingMs,
                live = isLive,
                showTicker = true,
                scrolling = live,
                turnStartAt = turnStartAt
            )
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
 * 思考 ticker v6.2「旋钮形态 C」（用户选型）：
 * - 文本从左侧开始往右显示（不满一行时静止）；
 * - 超过一行后持续向左滚动（最新内容从右侧进入）；
 * - 形态 C：中央最清晰、向两侧对称渐隐（SpanStyle alpha 曲线，无 draw 蒙版零黑块）；
 * - 思考结束（scrolling=false）滚动立即停住定格，保持渐隐形态；历史消息走静态省略。
 * 须在 RowScope 内调用。
 */
@Composable
private fun androidx.compose.foundation.layout.RowScope.ReasoningTickerInline(
    text: String,
    thinkingMs: Long? = null,
    live: Boolean = true,
    /** 是否渲染 ticker（聚合卡收起态/思考行= true；历史消息=false 走纯省略）。 */
    showTicker: Boolean = true,
    /** 滚动跟随开关：思考中 true（跟随尾部）；已思考/历史 false（立即定格）。 */
    scrolling: Boolean = live,
    /** 秒表起点＝回合起点（0 时退回本次组合的起点）。别在组合里自己造起点，见 [ElapsedClock]。 */
    turnStartAt: Long = 0L
) {
    // 实时计时（live 时每 100ms 刷新；结束态用定格的 thinkingMs）
    // 起点同样取回合起点：这一组 composable 现在全仓没有调用点（链卡走 ChainOfThought），
    // 但它里面藏着一根和 ThinkingIndicator 同款的自造秒表——留着不改，哪天接回去就是同一个缺陷重演。
    var elapsed by remember { mutableLongStateOf(0L) }
    if (live) {
        val localT0 = remember(turnStartAt) { System.currentTimeMillis() }
        LaunchedEffect(turnStartAt) {
            while (true) {
                elapsed = ElapsedClock.ms(turnStartAt, localT0, System.currentTimeMillis())
                kotlinx.coroutines.delay(100)
            }
        }
    }
    // shimmer：渐变高光横扫标题（live 时）。by 委托=读取发生在 if(live) 内：
    // live=false 的历史行零订阅零帧；live 时全帧率跟随系统刷新率（用户裁决：
    // 涩感远比发热难受，不牺牲流畅）
    val shim by com.haoai.agent.ui.common.rememberPulse(0f, 1f, 1700)
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
        // v6.2 旋钮形态 C（用户选型）：中央最清晰、向两侧对称渐隐。
        // 实现：文字 SpanStyle alpha 逐字符上色（无 draw 蒙版——DstIn 黑带两轮教训），
        // alpha 曲线中段最实、两端淡出；文字始终跟随尾部（新内容从右进入）。
        // 思考结束（scrolling=false）滚动立即定格：文本不再移动，渐隐形态保留。
        if (showTicker) {
            val hscroll = rememberScrollState()
            // 只在滚动期跟随尾部；定格后不再动（text 变化也不触发）
            LaunchedEffect(text, scrolling) {
                if (scrolling) hscroll.scrollTo(hscroll.maxValue)
            }
            Box(
                Modifier
                    .weight(1f)
                    .height(16.dp)
            ) {
                // 尾部窗口：思考中取 120 字符；定格后取定格瞬间的尾部（text 不再变）
                val tailText = text.takeLast(120)
                val base = MaterialTheme.colorScheme.onSurfaceVariant
                val primaryC = MaterialTheme.colorScheme.primary
                // v9 方案S 同步（用户选型）：ticker 右端新进入的字符也走「主题色墨滴→
                // 翻转正文色」双色两段（与正文流式同一动效语言）。右端 16 字符为动效区，
                // 按字符索引哈希派生随机翻转延迟（重组稳定，不重掷）。
                // 思考结束（scrolling=false）立即定格：不再做动效，直接静态 alpha 曲线。
                var tickerAt by remember { mutableLongStateOf(0L) }
                var tickerLen by remember { mutableStateOf(-1) }
                if (scrolling && tailText.length != tickerLen) {
                    tickerLen = tailText.length
                    tickerAt = System.currentTimeMillis()
                }
                var tickerNow by remember { mutableLongStateOf(0L) }
                if (scrolling) {
                    LaunchedEffect(tailText.length) {
                        val startAt = System.currentTimeMillis()
                        while (true) {
                            tickerNow = System.currentTimeMillis()
                            if (tickerNow - startAt > 480) break
                            kotlinx.coroutines.delay(32)
                        }
                    }
                }
                val ann = buildAnnotatedString {
                    append(tailText)
                    // 形态 C alpha 曲线：左端 28% 淡入（0→0.85），右端 18% 淡出
                    // （0.85→0.12）——右侧是"正在离开"的方向，略保留可见度
                    val n = tailText.length
                    val effectStart = (n - 16).coerceAtLeast(0)
                    val baseAlpha = { i: Int ->
                        val t = if (n <= 1) 1f else i.toFloat() / (n - 1)
                        when {
                            t < 0.28f -> (t / 0.28f) * 0.85f
                            t > 0.82f -> 0.85f - ((t - 0.82f) / 0.18f) * 0.73f
                            else -> 0.85f
                        }.coerceIn(0f, 0.85f)
                    }
                    if (scrolling) {
                        for (i in 0 until n) {
                            if (i < effectStart) {
                                addStyle(SpanStyle(color = base.copy(alpha = baseAlpha(i))), i, i + 1)
                            } else {
                                // 动效区：随机延迟淡入 + 主题色→正文色翻转
                                val seed = i * 2654435761L
                                val d = ((seed ushr 16) % 121).toInt()          // 0..120ms 淡入延迟
                                val f = 140 + ((seed ushr 8) % 121).toInt()      // 140..260ms 翻转延迟
                                val age = tickerNow - tickerAt - d
                                val aIn = if (age <= 0f) 0f else (age / 160f).coerceIn(0f, 1f)
                                val alpha = 0.85f * aIn
                                if (alpha <= 0.02f) {
                                    addStyle(SpanStyle(color = base.copy(alpha = baseAlpha(i) * 0.1f)), i, i + 1)
                                } else {
                                    val flipAge = age - f
                                    val col = if (flipAge <= 0f) primaryC.copy(alpha = 0.8f)
                                    else androidx.compose.ui.graphics.lerp(primaryC, base, (flipAge / 200f).coerceIn(0f, 1f))
                                    addStyle(SpanStyle(color = col.copy(alpha = alpha)), i, i + 1)
                                }
                            }
                        }
                    } else {
                        // 定格/历史：纯 alpha 曲线（v6.2 形态 C 原样）
                        for (i in 0 until n) {
                            addStyle(SpanStyle(color = base.copy(alpha = baseAlpha(i))), i, i + 1)
                        }
                    }
                }
                Text(
                    ann,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.horizontalScroll(hscroll)
                )
            }
        } else {
            // 历史/已思考：静态单行省略（无蒙版无滚动，与卡片同底无色差）
            Text(
                text,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/**
 * v7.5 方案 3：智能缩写对象——按工具类型做「可辨认的缩写」而非硬截断：
 * URL → 只留域名（lpl.qq.com）；命令 → 前段（curl -s --max-time 10）；
 * 查询词/其余 → 首尾保留 16 字符。比一律 take(12) 可读性高一个档次。
 */
private fun smartObj(tool: com.haoai.agent.ui.UiTool): String {
    val raw = toolObjPreview(tool)
    val obj = raw.trim().removeSurrounding("「", "」").ifBlank { raw.trim() }
    return when {
        // URL：协议去掉，路径很长只留域名+首段路径
        obj.startsWith("http://") || obj.startsWith("https://") -> {
            val noProto = obj.substringAfter("://")
            val host = noProto.substringBefore('/')
            val path = noProto.substringAfter('/', "")
            if (path.isBlank()) host
            else host + "/" + path.substringBefore('?').take(14) + if (path.length > 14) "…" else ""
        }
        // 命令：前 24 字符（通常参数头都在前段）
        tool.name == "bash" || tool.name == "shell" ->
            obj.take(24) + if (obj.length > 24) "…" else ""
        // 其余（查询词、文件路径等）：16 字符
        else -> obj.take(16) + if (obj.length > 16) "…" else ""
    }
}

/**
 * v7.3 方案 B（用户选型）：行内工具小胶囊——状态点 + 中文动词加粗 + 对象 + 状态标。
 * v7.5 方案 3：单行放宽到 ~92% 行宽，对象超长时胶囊内**横向滚动**查看全文
 * （点击即滚，不再狠截认不出）；点击展开参数/结果明细改为长按（避免与滚动冲突）。
 * 运行中蓝点呼吸，完成绿点+✓，失败红点+⚠。
 */
@Composable
private fun InlineToolPill(
    tool: com.haoai.agent.ui.UiTool,
    live: Boolean,
    onViewDiff: (String) -> Unit = {},
    onStopRun: () -> Unit = {},
    /** P2：终止单个运行中的子代理（参数=句柄 id）。 */
    onStopSubagent: (String) -> Unit = {}
) {
    var expanded by rememberSaveable(tool.callId) { mutableStateOf(false) }
    val canReview = (tool.name == "write" || tool.name == "edit") && tool.state == ToolRunState.DONE
    val isRunning = tool.state == ToolRunState.RUNNING && live
    val isError = tool.state == ToolRunState.ERROR
    Column(
        Modifier
            .fillMaxWidth()
            // v7.4.1：调用方（AssistantBlock/StreamingItem 的 Column）已提供 14dp 水平
            // padding——这里不能再加（双重 14dp = 胶囊比气泡缩进 14dp 的对齐 bug）。
            // v7.6.1 间隔统一：胶囊 vertical=2dp → 胶囊-胶囊 4dp、气泡-胶囊 5+2=7dp
            // 仍不等——气泡列顶部配 3dp 上间距（见调用处 spacing 修复），全局节奏 4dp。
            .padding(vertical = 2.dp)
    ) {
        // 胶囊本体：宽度上限 92% 行宽（fillMaxWidth 上限比例用 BoxWithConstraints 处理）
        BoxWithConstraints {
            val maxW = maxWidth * 0.92f
            Row(
                Modifier
                    .widthIn(max = maxW)
                    .clip(RoundedCornerShape(16.dp))
                    // 实底surface + 细描边——花壁纸上文字可读
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f))
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                        RoundedCornerShape(16.dp)
                    )
                    .combinedClickable(
                        onClick = { if (!isRunning) expanded = !expanded },
                        onLongClick = { if (!isRunning) expanded = true }
                    )
                    .padding(horizontal = 11.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 状态点：运行中蓝色呼吸 / 完成绿色 / 失败红色
                when {
                    isRunning -> {
                        val pulse by com.haoai.agent.ui.common.rememberPulse(0.35f, 1f, 900)
                        Box(
                            Modifier
                                .size(7.dp)
                                .graphicsLayer { alpha = pulse }
                                .background(MaterialTheme.colorScheme.primary, CircleShape)
                        )
                    }
                    isError -> Box(
                        Modifier
                            .size(7.dp)
                            .background(MaterialTheme.colorScheme.error, CircleShape)
                    )
                    else -> Box(
                        Modifier
                            .size(7.dp)
                            .background(Color(0xFF7BD88F), CircleShape)
                    )
                }
                Spacer(Modifier.size(7.dp))
                // 动词：只取 brief 第一个 · 前的动词段
                Text(
                    toolVerbOnly(tool),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
                )
                // v7.5 方案 3：对象超长时胶囊内横向滚动（scrollTo 跟尾），✓ 固定在右
                val fullObj = smartObj(tool)
                val rawObj = toolObjPreview(tool).trim()
                if (fullObj.isNotBlank()) {
                    Spacer(Modifier.size(5.dp))
                    val hscroll = rememberScrollState()
                    var pillMax by remember { mutableIntStateOf(0) }
                    LaunchedEffect(rawObj) {
                        // 内容变化时滚到尾部（最新内容可见），停留 1.2s 后回头部
                        hscroll.scrollTo(hscroll.maxValue)
                        kotlinx.coroutines.delay(1200)
                        hscroll.animateScrollTo(0)
                    }
                    Text(
                        fullObj,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .horizontalScroll(hscroll)
                    )
                }
                Spacer(Modifier.size(6.dp))
                // 状态标：完成 ✓ / 失败 ⚠；运行中不显示（呼吸点即状态）
                if (!isRunning) {
                    Text(
                        if (isError) "⚠" else "✓",
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isError) MaterialTheme.colorScheme.error
                        else Color(0xFF3FAE5C).copy(alpha = 0.85f)
                    )
                }
            }
        }
        // P1/P2：spawn 工具的逐路子代理状态——运行中常显（含当前工具与终止按钮），
        // 结束后点胶囊展开仍可回看
        val showSubs = tool.subagents.isNotEmpty() && (isRunning || expanded)
        androidx.compose.animation.AnimatedVisibility(showSubs) {
            Column(Modifier.padding(start = 14.dp, top = 2.dp)) {
                tool.subagents.forEach { sub ->
                    val subColor = when (sub.state) {
                        "RUNNING" -> MaterialTheme.colorScheme.primary
                        "DONE" -> Color(0xFF7BD88F)
                        "STOPPED" -> Color(0xFFFFC46B)
                        else -> MaterialTheme.colorScheme.error
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                    ) {
                        if (sub.state == "RUNNING") {
                            Box(
                                Modifier
                                    .size(7.dp)
                                    .graphicsLayer {
                                        alpha = 0.55f + 0.45f * (System.currentTimeMillis() % 900 / 900f)
                                    }
                                    .background(subColor, CircleShape)
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
                            "子代理 ${sub.index}/${sub.total}" + if (sub.id.isNotEmpty()) " · ${sub.id}" else "",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.size(6.dp))
                        Text(
                            sub.brief,
                            fontSize = 10.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        if (sub.tokensUsed > 0) {
                            Spacer(Modifier.size(5.dp))
                            Text(
                                fmtTokens(sub.tokensUsed.toInt()) + " tok",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                        if (sub.state == "RUNNING" && sub.id.isNotEmpty()) {
                            androidx.compose.material3.IconButton(
                                onClick = { onStopSubagent(sub.id) },
                                modifier = Modifier.size(22.dp)
                            ) {
                                androidx.compose.material3.Icon(
                                    androidx.compose.material.icons.Icons.Filled.Close,
                                    contentDescription = "终止子代理 ${sub.id}",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(13.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
        // 展开明细：参数/结果（运行中不展开——数据未定型）
        AnimatedVisibility(expanded && !isRunning) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, top = 4.dp, bottom = 4.dp)
            ) {
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
                    Spacer(Modifier.size(3.dp))
                }
                Text(
                    toolBriefDetail(tool),
                    fontSize = 10.5.sp,
                    lineHeight = 15.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 140.dp)
                        .verticalScroll(rememberScrollState())
                )
            }
        }
    }
}

/** 工具对象预览（胶囊内显示）：取 brief 第一个 · 后、下一个 · 前的第一段对象。 */
private fun toolObjPreview(tool: com.haoai.agent.ui.UiTool): String {
    val brief = tool.brief
    if (!brief.contains("·")) return ""
    val after = brief.substringAfter('·').trim()
    // 多段简报（A · B · C）只取第一段
    return after.substringBefore('·').trim()
}

/** 胶囊展开明细：完整简报 + 状态。 */
private fun toolBriefDetail(tool: com.haoai.agent.ui.UiTool): String {
    val st = when (tool.state) {
        ToolRunState.RUNNING -> "执行中"
        ToolRunState.ERROR -> "失败"
        else -> "已完成"
    }
    return "[${tool.name}] $st\n${tool.brief.ifBlank { "（无详情）" }}"
}


/** 气泡不透明度（设置 30%-100%）映射为 (用户气泡 alpha, 助手气泡 alpha)；100% 时几乎不透明。 */
@Composable
private fun chatBubbleAlphas(): Pair<Float, Float> {
    val app = LocalContext.current.applicationContext as? com.haoai.agent.HaoApplication ?: return 0.20f to 0.62f
    val settings by app.container.settingsFlow.collectAsState()
    val t = (settings.bubbleOpacity.coerceIn(0.3f, 1f) - 0.3f) / 0.7f
    return (0.14f + 0.79f * t) to (0.45f + 0.52f * t)
}

// ── data URL 图片缩略图（composer 附件预览 / 用户已发气泡共用）──

/** 解码缓存：key=dataUrl 哈希。附件数据 ≤1024px 采样，单张位图 ~2-4MB，容量 6 张够用。 */
private val dataUrlBmpCache = android.util.LruCache<String, ImageBitmap>(6)

@Composable
private fun DataUrlThumb(
    dataUrl: String,
    modifier: Modifier = Modifier,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(10.dp),
    contentScale: ContentScale = ContentScale.Crop
) {
    val key = remember(dataUrl) { dataUrl.hashCode().toString() }
    var bmp by remember(dataUrl) { mutableStateOf(dataUrlBmpCache.get(key)) }
    LaunchedEffect(dataUrl) {
        if (bmp != null) return@LaunchedEffect
        val decoded = withContext(Dispatchers.IO) {
            runCatching {
                val bytes = android.util.Base64.decode(
                    dataUrl.substringAfter("base64,", ""), android.util.Base64.NO_WRAP
                )
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }.getOrNull()
        }
        if (decoded != null) {
            dataUrlBmpCache.put(key, decoded)
            bmp = decoded
        }
    }
    if (bmp != null) {
        Image(
            bmp!!,
            contentDescription = "图片",
            modifier = modifier.clip(shape),
            contentScale = contentScale
        )
    } else {
        Box(
            modifier
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.Image,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
    }
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
            .padding(horizontal = 14.dp, vertical = 2.dp),
        horizontalAlignment = Alignment.End
    ) {
        // 用户发的图片附件：气泡上方原比例展示（此前只存不显）
        row.imageData?.let { dataUrl ->
            DataUrlThumb(
                dataUrl,
                Modifier
                    .widthIn(max = 260.dp)
                    .heightIn(max = 280.dp),
                shape = RoundedCornerShape(14.dp),
                contentScale = ContentScale.Fit
            )
        }
        Surface(
            color = MaterialTheme.colorScheme.primary.copy(alpha = chatBubbleAlphas().first),
            shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 5.dp),
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            // 长按正文 = 系统文本选择，不再弹操作菜单
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
            .padding(horizontal = 14.dp, vertical = 2.dp)
    ) {
        // v8 思维链重构：历史消息与流式同构——思考 + 工具混排进一张链卡（finished=true
        // → 默认折叠为控制条，点开回看全链，静态数据不回放动画）；正文气泡在卡外。
        val hasTools = row.tools.isNotEmpty()
        val hasReasoning = row.reasoning?.takeIf { it.isNotBlank() } != null
        if (hasTools || hasReasoning) {
            ChainCard(
                reasoning = row.reasoning?.takeIf { it.isNotBlank() },
                thinkingMs = null,
                reasoningLive = false,
                tools = row.tools,
                toolsLive = false,
                finished = true
            )
            Spacer(Modifier.size(6.dp))
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
                    // v7.6.4：与工具胶囊同体系描边（onSurface 8%）——气泡轮廓在任何
                    // 壁纸/背景下都可辨，与胶囊的视觉语言统一
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // 长按正文 = 系统文本选择；代码块内已嵌套
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
 * 统计行：细小灰字展示整轮 token 用量 / 速度 / 耗时。
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
    onStopRun: () -> Unit = {},
    /** P2：终止单个运行中的子代理（参数=句柄 id）。 */
    onStopSubagent: (String) -> Unit = {}
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
                    // ⑥ 运行中：红色停止方块，点按即中断本轮
                    ToolRunState.RUNNING -> Box(
                        Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .clickable { onStopRun() },
                        contentAlignment = Alignment.Center
                    ) {
                        val pulse by com.haoai.agent.ui.common.rememberPulse(0.55f, 1f, 1100)
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
                                // P2：运行中的子代理可单独终止（部分结果随 spawn 返回）
                                if (sub.state == "RUNNING" && sub.id.isNotEmpty()) {
                                    Spacer(Modifier.size(4.dp))
                                    androidx.compose.material3.IconButton(
                                        onClick = { onStopSubagent(sub.id) },
                                        modifier = Modifier.size(20.dp)
                                    ) {
                                        androidx.compose.material3.Icon(
                                            androidx.compose.material.icons.Icons.Filled.Close,
                                            contentDescription = "终止子代理 ${sub.id}",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(13.dp)
                                        )
                                    }
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
    /** 输入框玻璃的导出层（抽屉合成采样用，见 ChatScreen 抽屉） */
    exportedBackdrop: com.kyant.backdrop.backdrops.LayerBackdrop? = null,
    /** 输入框内容导出层（只含文字/按钮，不含玻璃磨砂底）：抽屉采样用，避免磨砂叠加 */
    contentExportLayer: androidx.compose.ui.graphics.layer.GraphicsLayer? = null,
    contentExportCoords: java.util.concurrent.atomic.AtomicReference<androidx.compose.ui.layout.LayoutCoordinates?>? = null,
    text: String,
    onTextChange: (String) -> Unit,
    /** 聊天输入框聚焦变化：键盘避让只服务于"聚焦的输入框"（见 ChatScreen keyboardLiftPx） */
    onFocusChange: (Boolean) -> Unit = {},
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
        exportedBackdrop = exportedBackdrop,
        radius = 26.dp,
        surfaceAlpha = com.haoai.agent.ui.theme.haoCardSurfaceAlpha(),
        // 键盘抬升值作重绘键：位置变化后强制重绘折射，采样对齐新布局位置，
        // 保持完整液态效果且背景正确（matte 方案观感差已弃）
        redrawKey = redrawKey,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            Modifier
                // 内容导出：本列（输入文字/按钮，不含玻璃底）录进 contentExportLayer
                .onGloballyPositioned { contentExportCoords?.set(it) }
                .drawWithContent {
                    contentExportLayer?.record { this@drawWithContent.drawContent() }
                    drawContent()
                }
                .padding(top = 4.dp)
        ) {
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
                    Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 附件预览缩略图（此前只显示"已附加图片"文字，用户看不到选了什么）
                    DataUrlThumb(
                        pendingImage,
                        Modifier.size(52.dp),
                        shape = RoundedCornerShape(12.dp)
                    )
                    Text(
                        "已附加图片",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 10.dp),
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
                // v7：运行中也允许展开工具栏（排队发消息时可能还要带图/音频等附件）
                IconButton(onClick = { toolbarExpanded = !toolbarExpanded }) {
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
                    modifier = Modifier
                        .weight(1f)
                        .onFocusChanged { onFocusChange(it.isFocused) },
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
    // 三段结构：档案头部 → 最近会话列表（主体，点击即切换）→ 底部导航
    val fmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
    // 当前滑出操作按钮的会话（同时只允许一张）+ 重命名目标
    var openCardId by remember { mutableStateOf<String?>(null) }
    var renameTarget by remember { mutableStateOf<StoredSession?>(null) }
    // 抽屉收起时自动收回展开的按钮
    LaunchedEffect(drawerOpen) { if (!drawerOpen) openCardId = null }

    // 抽屉每次打开 → 会话列表回到顶部。
    // 为什么需要：这个 LazyColumn 此前**没有显式 state**，Compose 内部创建的 state 随抽屉
    // 常驻组合（抽屉只是平移出屏、并未离开组合），于是上次滚动的位置一直残留。
    // 症状：新建会话 B 后它确实排在第 0 位，但列表还停在旧偏移，B 在视口上方看不见，
    // 得手动往下拉才露出来（用户实测）。
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(drawerOpen) {
        if (drawerOpen && sessions.isNotEmpty()) listState.scrollToItem(0)
    }

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
            state = listState,
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
                        // v7.6 方案 2（用户选型）：高透白玻璃卡——86% 白底 + 亮描边 +
                        // 内高光（iOS 控制中心滑块风格），白亮边框把卡从磨砂底上"托"出来。
                        // 旧双层薄玻璃（面板 28% + 卡 16%）叠加后卡与底无对比、标题混壁纸。
                        // 折射关（refract=false 走本地绘制路径）：卡已是高透白，再采样壁纸
                        // 反而搅浑文字层；选中态用绿浸染+绿描边。
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(
                                    // v7.9 主题联动（用户确认稿）：选中浸染=primary 38%
                                    // （原硬编码 #7BDC9C 绿——切主题种子/壁纸取色/自定义色
                                    // 不跟随）；未选中保持白 62% 中性
                                    if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.38f)
                                    else Color(0xFFFFFFFF).copy(alpha = 0.62f)
                                )
                                .border(
                                    1.2.dp,
                                    // v7.9：选中描边=primary 60%（原硬编码 #3FAE5C）；
                                    // 未选中 onSurface 18% 深蓝灰（白底隐形、深底勾边）
                                    if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.60f)
                                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f),
                                    RoundedCornerShape(14.dp)
                                )
                                // 内高光：顶部 40% 高度的白色渐变（玻璃"顶光"）
                                .drawWithContent {
                                    drawContent()
                                    drawRect(
                                        brush = Brush.verticalGradient(
                                            0f to Color.White.copy(alpha = 0.35f),
                                            0.4f to Color.White.copy(alpha = 0.06f),
                                            1f to Color.Transparent
                                        )
                                    )
                                }
                                .clickable { cardClick() }
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
                                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
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
            CompactGlassField(
        value = renameValue,
        onValueChange = { renameValue = it.take(50) },
        label = "会话名",
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
                // 网页渲染预览：有文本即可用
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
                // 元信息行：时间 + 模型名
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
