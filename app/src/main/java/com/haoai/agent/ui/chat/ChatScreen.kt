package com.haoai.agent.ui.chat

import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.drawBehind
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.ui.draw.rotate
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
import kotlin.math.roundToInt
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
import kotlinx.coroutines.flow.collectLatest
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
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Construction
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.WarningAmber
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
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
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
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
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
import com.haoai.agent.ui.common.HaoIcons
import com.haoai.agent.ui.common.GlassBottomSheet
import com.haoai.agent.ui.common.LiquidGlassButton
import com.haoai.agent.ui.common.MarkdownText
import com.haoai.agent.ui.common.MdAstCache
import com.haoai.agent.ui.common.PickerHueColors
import com.haoai.agent.ui.common.SwipeRevealCard
import com.haoai.agent.ui.common.appLayer
import com.haoai.agent.ui.common.SlowSpinner
import com.haoai.agent.ui.theme.wallpaperAdaptiveGray
import java.text.SimpleDateFormat
import androidx.compose.ui.text.style.TextOverflow
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch
import com.haoai.agent.ui.common.CompactGlassField
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.focus.onFocusChanged

/**
 * 重新生成版本翻页回调（宿主 user 消息 id, 目标版本索引；-1=最新）。
 * ChatScreen 顶层 provide vm::switchVersion，深层最终回复行经此上抛——
 * 与 LocalOpenToolSheet 同一模式，省掉 RowItem/MessageList 多层签名透传。
 */
val LocalSwitchVersion = androidx.compose.runtime.staticCompositionLocalOf<(String, Int) -> Unit> { { _, _ -> } }

/** 聊天社交化（2026-10-07 效果图 C 定稿）：消息区名字行用的双方档案快照。 */
data class ChatProfiles(
    val agentName: String,
    val agentEmoji: String,
    val agentGradient: Int,
    val agentImage: String?,
    val myName: String,
    val myEmoji: String,
    val myGradient: Int,
    /** 我的头像自定义底色（HEX）；空 = 用 myGradient 预设渐变。 */
    val myGradientCustom: String = "",
    val myImage: String?
)

/** 头像进名字行后深层组件（AssistantBlock/UserBubble/StreamingItem）取档案，免逐层透传。 */
val LocalChatProfiles = androidx.compose.runtime.staticCompositionLocalOf<ChatProfiles?> { null }

/**
 * D17 批3：排队气泡的动作回调（撤回/编辑退回输入框/立即发送/插话）。
 * 走 CompositionLocal 而不是逐层透传——这条链要穿 MessageList→itemsIndexed→MessageRow，
 * 加几个参数会传染一大片；语义与 LocalChatProfiles 同款（渲染真源在 ChatScreen，VM 只出动作）。
 */
data class QueueActions(
    val withdraw: (String) -> Unit,
    val edit: (String) -> Unit,
    val promote: (String) -> Unit,
    /** A2：标记为插话——消息已在历史里，引擎下一轮组装请求天然带上；旗标只驱动销账。 */
    val interject: (String) -> Unit
)
val LocalQueueActions = androidx.compose.runtime.staticCompositionLocalOf<QueueActions?> { null }

/** 时间戳三档：今天=HH:mm；昨天=昨天 HH:mm；更早=MM月d日 HH:mm。 */
internal fun fmtMsgTs(ts: Long): String {
    if (ts <= 0L) return ""
    val d = Date(ts)
    val now = java.util.Calendar.getInstance()
    val then = java.util.Calendar.getInstance().apply { timeInMillis = ts }
    val sameDay = now.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR) &&
        now.get(java.util.Calendar.DAY_OF_YEAR) == then.get(java.util.Calendar.DAY_OF_YEAR)
    val yest = now.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR) &&
        now.get(java.util.Calendar.DAY_OF_YEAR) - then.get(java.util.Calendar.DAY_OF_YEAR) == 1
    return when {
        sameDay -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(d)
        yest -> "昨天 " + SimpleDateFormat("HH:mm", Locale.getDefault()).format(d)
        else -> SimpleDateFormat("MM月d日 HH:mm", Locale.getDefault()).format(d)
    }
}

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
    // 2026-10-28：聊天列表虚拟化（LazyColumn），滚动状态从 ScrollState 换回
    // LazyListState（跨设置导航的位置恢复由 MainActivity 提升的实例承担，此默认值
    // 只兜底独立使用场景）。
    listState: androidx.compose.foundation.lazy.LazyListState =
        androidx.compose.foundation.lazy.rememberLazyListState(),
    // 跟随状态机跨页状态（RootApp 提升实例；独立使用时用本地默认）：
    // listSessionId=listState 当前所属会话（切会话贴底判据，设置往返不触发）；
    // followParked=用户接管画面（停车）。两者放 ChatScreen 内会随 AnimatedContent
    // 销毁分支丢失（rememberSaveable 在该分支不恢复，实测），导致返回聊天被误判
    // 成切会话/未停车而跳底——提升后位置保持才真正成立。详见 MainActivity 注释。
    listSessionId: androidx.compose.runtime.MutableState<String?> =
        androidx.compose.runtime.remember {
            androidx.compose.runtime.mutableStateOf<String?>(null)
        },
    followParked: androidx.compose.runtime.MutableState<Boolean> =
        androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) },
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
    onOpenVscreen: () -> Unit = {},
    /** 工作区壳自带画布垫底（全局壁纸开时铺对齐壁纸，与设置/会话页同源同位）。 */
    wallpaper: android.graphics.Bitmap? = null
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
    // ❶ 晋升行：从流式区落成行的 key——跳过入场淡入（内容同帧已在上屏，再淡入=闪一下）
    val promotedRowKeys by vm.promotedRowKeysFlow.collectAsState()
    // 本轮是否产出过内容：收尾空档的胶囊文案分支（「整理回答」vs「连接模型」）
    val turnHadOutput by vm.turnHadOutput.collectAsState()
    val error by vm.error.collectAsState()
    val sessions by vm.sessions.collectAsState()
    val deletedSessions by vm.deletedSessions.collectAsState()
    val settings by vm.settings.collectAsState()

    // ⑧ 回到底部浮钮状态：粘滞标志提升到 ChatScreen 级（MessageList 内维护、
    // 浮钮画在 appLayer 采样层之外——Box 包裹会破坏 drawBackdrop 采样链，
    // 与「玻璃顶栏不能进 appLayer 子树」同理）。用 MutableState 对象（非 by 委托）
    // 以便同时传给 MessageList 读写与浮钮读取
    val userScrolledAway = remember { mutableStateOf(false) }
    // 粘底订阅状态（2026-10-12 三修，替代「松手边沿停车」）：true=用户已接管画面
    // （本帧位移被非钉底的外力挪动过），跟随循环不钉底；false=贴底订阅中。
    // 撤停=滚回真底（delta≤8px，rikkahub 同款严格触底）或显式动作（回底钮/发消息/
    // 新一轮/切会话）。虚拟化版（2026-10-28）提升到 RootApp（followParked 参数）：
    // 本组件在 AnimatedContent 销毁分支内，进设置再返回会整树重建——停车状态留在
    // 本地会让返回后的跟随循环把恢复出的中段位置当成「该跟随」钉回底部，位置保持
    // 失效（实测）。演进：二修用「会话结束边沿+落点>63px」判停车，但拖把手路径多、
    // 把手向下拖时画面朝下走（dV>0）方向判定也不成立 → 用户实测弹跳区间缩小但没消失；
    // 三修改为逐帧位移归属（dV≠0 且非我们钉的=接管），一次覆盖全部路径。
    val parkedFollow = followParked
    // 展开锚点豁免窗口（2026-10-13）：点展开时置 now+750ms，跟随循环在窗口内跳过吸底，
    // 使视口锚在被点条上、内容向下掀（详见 ChainOfThought.LocalParkExpand）。
    // 独立于 parkedFollow：不背滚动归因，且躲开「delta≤8 撤停」在同帧抹掉。
    val expandParkUntil = remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    // ⑧ 回底浮钮 v3（2026-10-12 方向感知定稿，用户效果图拍板）：不再计数
    // （旧「↓ N 条新回复」徽标整条拆除：ticker 会把"上滑动作"以外的一切内容
    // 变化都喊成动态，而且宽胶囊悬在流式落字区正中挡正文）。新语义 =
    // 「你下面还有内容，且你正在找它」：向下翻且离底 >150dp 才浮现、向上翻
    // （阅读意图）立即隐藏、停手 2.5 秒淡出、贴底不出。显隐与闲置淡出全部由
    // MessageList 的逐帧跟随循环驱动（那里才有逐帧 dV；闲置计时用循环内本地
    // 时间戳，**不逐帧写 State**——每帧写会让读取方作用域每帧重组，主题页
    // 17.34% jank 就是同款教训），本层只持有显隐状态。
    val jumperVisible = remember { mutableStateOf(false) }
    // v6.1 补档（2026-10-12 用户定稿）：running 真→假边沿把本轮耗时定格，
    // 状态行显示「✓ 回复完毕 · N秒」4 秒后消失——旧版跑完直接消失，用户
    // 在上方看消息时完全不知道下面已经出结果。门：本轮产出过内容才提示
    //（空回合/秒失败不值得打扰；手动停止同样算"这轮结束了"，语义一致）。
    // 2026-10-16 纠偏：结束有错（异常中断/失败）时文案改「⚠ 意外中断」——
    // 原先失败也闪"回复完毕"属谎报（用户实测点名）。
    var lastRunningForDone by remember { mutableStateOf(false) }
    var turnDoneMs by remember { mutableStateOf<Long?>(null) }
    var turnDoneError by remember { mutableStateOf(false) }
    // 2026-10-16 补：熔断收尾（成本/圈数上限）与「回复完毕」分流。引擎撞上限时是
    // **正常收尾**（error=null），只剩半截进度，胶囊却闪"回复完毕" = 谎报完成，
    // 用户无从判断要不要点继续。这里单独记一档，显示「⚠ 提前收尾」。
    var turnDoneCapped by remember { mutableStateOf(false) }
    val lastTurnError by vm.lastTurnError.collectAsState()
    LaunchedEffect(running) {
        if (running) {
            turnDoneMs = null
        } else if (lastRunningForDone && turnHadOutput) {
            turnDoneError = vm.lastTurnError.value != null
            turnDoneCapped = !turnDoneError &&
                vm.lastTurnEndState.value == com.haoai.agent.data.StoredSession.RUN_TURNCAPPED
            turnDoneMs = (System.currentTimeMillis() - turnStartAt).coerceAtLeast(0L)
        }
        lastRunningForDone = running
    }
    LaunchedEffect(turnDoneMs) {
        if (turnDoneMs != null) {
            kotlinx.coroutines.delay(4000)
            turnDoneMs = null
        }
    }
    val approval by vm.approval.collectAsState()
    val todoItems by vm.todoItems.collectAsState()
    val planMode by vm.planMode.collectAsState()
    val planProposal by vm.planProposal.collectAsState()

    var input by rememberSaveable { mutableStateOf("") }
    // D17 批3：排队气泡点 ✎ → 文本退回输入框（消费一次即清，防重放）
    androidx.compose.runtime.LaunchedEffect(Unit) {
        vm.composerPrefill.collect { pre ->
            if (pre != null) {
                input = pre
                vm.consumeComposerPrefill()
            }
        }
    }
    // 大体积 base64 / 文档正文绝不能进 rememberSaveable：会被写入 savedInstanceState
    // Bundle，超过 ~1MB 直接 TransactionTooLargeException 崩溃；进程重建丢失待发附件可接受
    // 多图时代（2026-10-14）：待发图片是列表，上限 9 张（再多 base64 撑内存、会话 JSON 也膨胀）
    var pendingImages by remember { mutableStateOf<List<String>>(emptyList()) }
    val addPendingImage: (String) -> Unit = { url ->
        if (pendingImages.size < 9) pendingImages = pendingImages + url
        else android.widget.Toast.makeText(context, "一次最多附 9 张图片", android.widget.Toast.LENGTH_SHORT).show()
    }
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
    // 引用片段卡（效果图定稿 2026-10-07）：不再把整段灌进输入框，改挂输入框上方，
    // 发送时才拼进正文——输入框保持干净，✕ 一键移除
    var quoteDraft by remember { mutableStateOf<ChatRow?>(null) }
    // 网页渲染预览目标（⋮ 菜单进入）
    var previewTarget by remember { mutableStateOf<ChatRow?>(null) }
    // 图片全屏查看器宿主（批1d）：Markdown 图片块与用户附件缩略图点击上抛图集；
    // 挂在本级（而非各 ImageBlockView 自挂）——消息滚出屏再滚回，查看器不被销毁，
    // 且图集来自整条消息可左右横滑
    var imageLightbox by remember {
        mutableStateOf<Pair<List<Pair<String, String>>, String>?>(null)
    }
    var editTarget by remember { mutableStateOf<ChatRow?>(null) }
    var deleteTarget by remember { mutableStateOf<ChatRow?>(null) }
    // 工作区浏览单页（批4b：三 Tab 壳退役→扁平列表+面包屑）。
    // fileTreeLocate=查看器「在目录中定位」跳入请求（落到该文件父目录并短暂高亮）。
    var workspaceOpen by remember { mutableStateOf(false) }
    var fileTreeLocate by remember { mutableStateOf<String?>(null) }
    // 批3e：内联运行能力（shell 恒可、python/js 需沙箱；装完发行版换会话/重进即重探）
    var runEnv by remember { mutableStateOf(com.haoai.agent.ui.common.RunEnv()) }
    val runEnvSessionId = vm.session.collectAsState().value?.id
    androidx.compose.runtime.LaunchedEffect(runEnvSessionId) { runEnv = vm.runEnv() }
    // 工具详情弹层（v8 思维链）：ToolStep 点击后经 LocalOpenToolSheet 上抛，
    // 在 appLayer 外渲染 ToolDetailSheet+GlassPanel 真玻璃（独立窗口采样不到 backdrop）
    // P4：Pair 第二位 = pill 点击带入的初始选中子代理 id（null=自动选）
    var toolSheet by remember { mutableStateOf<Pair<com.haoai.agent.ui.UiTool, String?>?>(null) }
    // P4 右上角浮标：详情弹层（面板行/「查看完整过程」点击带入单路 id）。
    // badgeDetail=打开时快照；此后 live（1s ticker 驱动重组）持续跟进，轨迹被清时保快照不闪退
    var badgeDetailId by remember { mutableStateOf<String?>(null) }
    var badgeDetail by remember { mutableStateOf<com.haoai.agent.ui.UiSubagentDetail?>(null) }
    // 工具卡"查看变更"（1.3）：从写前快照现算 diff
    var diffViewer by remember { mutableStateOf<Pair<String, List<com.haoai.agent.ui.common.DiffLine>>?>(null) }
    // 代际栅栏（显示侧）：diff 属于打开它的那个会话，切会话即关，防旧会话的变更弹窗盖在别的会话上
    val diffViewerSessionId = vm.session.collectAsState().value?.id
    androidx.compose.runtime.LaunchedEffect(diffViewerSessionId) {
        diffViewer = null
        // P4：浮标详情弹层同理——它是某一会话的子代理轨迹，切会话即关防旧内容盖新会话
        badgeDetailId = null
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
    // 顶栏专用采样源（出血玻璃）：顶栏玻璃节点向屏外扩 bleed，牺牲区垫屏幕对齐壁纸——
    // 根治状态栏带磨砂失效（见 BledTopBarBackdrop 注释）。壁纸变换/主题切换经 remember key 重建。
    val topBarScreenW = with(density) { cfgNow.screenWidthDp.dp.toPx() }
    val topBarScreenH = with(density) { cfgNow.screenHeightDp.dp.toPx() }
    val topBarBleedPx = with(density) { TopBarBleed.toPx() }
    val topBarBase = MaterialTheme.colorScheme.background
    val topBarWallpaperImage = remember(wallpaper) { wallpaper?.asImageBitmap() }
    val topBarBackdrop = remember(
        topBarWallpaperImage, backdrop, topBarBleedPx, topBarScreenW, topBarScreenH,
        topBarBase
    ) {
        BledTopBarBackdrop(
            wallpaperImage = topBarWallpaperImage,
            main = backdrop,
            ringPx = topBarBleedPx,
            screenW = topBarScreenW,
            screenH = topBarScreenH,
            baseColor = topBarBase
        )
    }
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
    // ask_user/题组卡自由输入框的聚焦态：键盘避让同样要服务它（2026-10-15 用户报：
    // 点提问卡"自定义输入"时卡片不随键盘抬起，键盘盖住选项——旧逻辑只认主输入栏焦点）
    var askCardInputFocused by remember { mutableStateOf(false) }
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
    // 抬升服务任何"正在输入的框"：主输入栏 或 ask_user/题组卡的自定义输入框
    val keyboardLiftPx = if (overlayDialogOpen || (!composerFocused && !askCardInputFocused)) 0
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

    // 单张 Uri → data URL 解码管线（相册多选、系统分享、拍照共用；长边 1024 采样 + JPEG 85）
    val decodeUriToDataUrl: (android.net.Uri) -> String? = dec@{ uri ->
        runCatching {
            val resolver = context.contentResolver
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1024) sample *= 2
            val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = resolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, opts) }
                ?: return@runCatching null
            val bos = java.io.ByteArrayOutputStream()
            bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, bos)
            bmp.recycle()
            "data:image/jpeg;base64," + android.util.Base64.encodeToString(bos.toByteArray(), android.util.Base64.NO_WRAP)
        }.getOrNull()
    }

    // 多图时代（2026-10-14）：Android 13+ 走系统 PhotoPicker（多选、隐私友好、无需存储权限），
    // 旧系统回退 ACTION_PICK_MULTIPLE 分发给默认图库（Flyme 图库等，结果 URI 自带临时读权限）。
    val pickMultipleImages = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia(9)
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        // 解码在 IO 线程跑（多张 12MP 全流程可达秒级，主线程逐张解码就是"选完图冻一下"）
        scope.launch(Dispatchers.IO) {
            val urls = uris.mapNotNull { decodeUriToDataUrl(it) }
            if (urls.isNotEmpty()) withContext(Dispatchers.Main.immediate) {
                pendingImages = (pendingImages + urls).take(9)
            }
        }
    }
    val legacyMultiPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        // 旧系统单图通路：ClipData 多选 + data 单选的兜底都收进同一个列表口径
        val uris = mutableListOf<android.net.Uri>()
        result.data?.clipData?.let { cd -> for (i in 0 until cd.itemCount) cd.getItemAt(i).uri?.let(uris::add) }
        result.data?.data?.let(uris::add)
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            val urls = uris.mapNotNull { decodeUriToDataUrl(it) }
            if (urls.isNotEmpty()) withContext(Dispatchers.Main.immediate) {
                pendingImages = (pendingImages + urls).take(9)
            }
        }
    }
    val launchImagePicker: () -> Unit = {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            pickMultipleImages.launch(
                androidx.activity.result.PickVisualMediaRequest(
                    androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly
                )
            )
        } else {
            // API<33：PhotoPicker 不可用，回退默认图库（Flyme 图库等）单图通路；
            // 部分图库认 EXTRA_ALLOW_MULTIPLE，带上一并尽力多选
            runCatching {
                legacyMultiPicker.launch(
                    android.content.Intent(android.content.Intent.ACTION_PICK,
                        android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI).apply {
                        type = "image/*"
                        putExtra("android.intent.extra.ALLOW_MULTIPLE", true)
                    }
                )
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
                    val uri = android.net.Uri.parse(uriStr)
                    val url = decodeUriToDataUrl(uri)
                    if (url != null) addPendingImage(url)
                }
            }
        }
    }

    // 拍照：动态权限 → TakePicture 到 FileProvider Uri → 采样解码压缩为 base64 附件
    val cameraLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.TakePicture()
    ) { ok ->
        if (ok) {
            // 主线程 IO 修复：采样解码 + JPEG 压缩 + base64 都是 CPU 重活
            //（一张 12MP 照片全流程 200-500ms），拍照回来看着界面冻一下就是这里。
            scope.launch(Dispatchers.IO) {
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
                        val dataUrl = "data:image/jpeg;base64," +
                            android.util.Base64.encodeToString(bos.toByteArray(), android.util.Base64.NO_WRAP)
                        withContext(Dispatchers.Main.immediate) { addPendingImage(dataUrl) }
                    }
                    cameraShotFile.delete()
                }
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
            // 主线程 IO 修复（2026-10-10）：picker 回调在主线程，原先 query/openInputStream/readText
            // 全在主线程跑——大文档（几 MB 的 log/csv）会把输入阻塞几百毫秒。挪到 IO 线程，
            // 状态写回主线程（Compose state 本身线程安全，但保持一致的写线程便于排查）。
            scope.launch(Dispatchers.IO) {
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
                            withContext(Dispatchers.Main.immediate) {
                                pendingDocumentName = fileName
                                pendingDocumentContent = content
                            }
                        }
                    } else {
                        withContext(Dispatchers.Main.immediate) {
                            pendingDocumentName = fileName
                            pendingDocumentContent = "[文件附件: $fileName ($mimeType)]"
                        }
                    }
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
            // 主线程 IO 修复：音频文件可能 8MB，复制挪到 IO 线程
            scope.launch(Dispatchers.IO) {
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
                    withContext(Dispatchers.Main.immediate) {
                        if (dest.exists() && dest.length() <= 8L * 1024 * 1024) {
                            pendingAudioPath = dest.absolutePath
                            pendingAudioName = fileName
                        } else {
                            dest.delete()
                            vm.showError("音频过大（>8MB），请先用 shell 工具压缩或截取片段")
                        }
                    }
                }.onFailure {
                    withContext(Dispatchers.Main.immediate) { vm.showError("读取音频失败：${it.message}") }
                }
            }
        }
    }

    // 视频附件：复制到应用私有目录存本机路径——chat 模型几乎不原生吃视频，
    // 引擎把路径注记给 Agent，由 shell ffmpeg 抽关键帧/抽音轨绕行（能力声明已告知）
    val videoPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            // 主线程 IO 修复：视频是三类附件里最大的（几百 MB 级），在主线程整文件复制
            // 会把界面冻住好几秒甚至触发 ANR。复制挪到 IO 线程，结果回主线程。
            scope.launch(Dispatchers.IO) {
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
                        withContext(Dispatchers.Main.immediate) {
                            pendingVideoPath = dest.absolutePath
                            pendingVideoName = fileName
                        }
                    }
                }.onFailure {
                    withContext(Dispatchers.Main.immediate) { vm.showError("读取视频失败：${it.message}") }
                }
            }
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
                com.haoai.agent.ui.chat.LocalOpenToolSheet provides { t, sub -> toolSheet = t to sub },
                // 批2a：产物卡 [在浏览器打开]——navigate 自带 uiOpener（未开界面时
                // 自动弹内置浏览器预览面板），这里只负责起协程
                com.haoai.agent.ui.chat.LocalOpenHtmlFile provides { url ->
                    scope.launch { com.haoai.agent.agent.browser.BrowserController.navigate(url) }
                },
                // 工作区浏览页：查看器「在目录中定位」上抛——开单页并落到该文件所在目录。
                // 批4b：「版本历史」通道随历史 Tab 退役（回滚走聊天 /undo，快照引擎保留）。
                LocalLocateInTree provides { rel ->
                    fileTreeLocate = rel
                    workspaceOpen = true
                },
                // 批3e：代码块运行通道 + 能力快照（CodeBlock 据此决定出不出运行钮）
                com.haoai.agent.ui.common.LocalRunEnv provides runEnv,
                com.haoai.agent.ui.common.LocalRunCode provides { lang, code ->
                    vm.runCode(lang, code)
                },
                com.haoai.agent.ui.common.LocalImageLightboxLauncher provides { list, url ->
                    imageLightbox = list to url
                },
                LocalSwitchVersion provides { uid, idx -> vm.switchVersion(uid, idx) },
                // 展开锚点（2026-10-13）：点展开=置停车（不吸底，视口锚在被点条上、内容向下掀）
                // + 起 750ms 豁免窗（窗口内「贴底自动撤停」不抹停车——展开首帧内容还没长高、
                // delta 仍≤8，不保护会被当场撤掉）。窗口后停车持续，直到用户自己滚回底部
                // （delta≤8 撤停）或显式动作（发消息/新一轮/回底钮，各 effect 清）。详见
                // ChainOfThought.LocalParkExpand 注释。
                LocalParkExpand provides {
                    parkedFollow.value = true
                    expandParkUntil.value = System.currentTimeMillis() + 750L
                },
                LocalChatProfiles provides ChatProfiles(
                    agentName = vm.agentName(),
                    agentEmoji = settings.avatarEmoji,
                    agentGradient = settings.avatarGradient,
                    agentImage = settings.avatarImagePath,
                    myName = vm.myName(),
                    myEmoji = settings.myEmoji,
                    myGradient = settings.myGradient,
                    myGradientCustom = settings.myGradientCustom,
                    myImage = settings.myAvatarPath
                ),
                LocalQueueActions provides QueueActions(
                    withdraw = { vm.withdrawQueued(it) },
                    edit = { vm.editQueued(it) },
                    promote = { vm.promoteQueued(it) },
                    interject = { vm.interjectQueued(it) }
                )
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
                turnStartAt = turnStartAt,
                thinkingMs = thinkingMs,
                thinkingHint = if (running && vm.isLocalProviderActive())
                    "端侧推理 · 正在理解上下文（需预处理全部提示词，可能数十秒）" else null,
                liveToolsSnapshot = liveToolsSnapshot,
                promotedRowKeys = promotedRowKeys,
                listState = listState,
                listSessionId = listSessionId,
                userScrolledAway = userScrolledAway,
                parkedFollow = parkedFollow,
                expandParkUntil = expandParkUntil,
                jumperVisible = jumperVisible,
                keyboardLiftPxState = keyboardLiftState,
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
                    // 社交化定稿（效果图 C 默认态）：顶栏只显会话标题，Agent 名字已在
                    // 每组消息的名字行里，顶栏不再重复
                    title = activeSession?.title?.takeIf { it.isNotBlank() } ?: "新对话",
                    subtitle = "",
                    contextUsage = contextUsage,
                    backdrop = topBarBackdrop,
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
                // P4 子代理浮标（效果图场景四）：挂 TaskFloat 之后——有任务面板时纵向
                // 错开 8dp，没有时自动上移到顶栏下第一行。主回合运行时 vm 已让位 null。
                // chip/面板无 pointerInput 的区域触摸穿透（Box 不吃事件），不挡下方消息列表。
                val bgBadgeNow = vm.bgBadge.collectAsState().value
                androidx.compose.animation.AnimatedVisibility(
                    visible = bgBadgeNow != null,
                    enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.expandVertically(),
                    exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.shrinkVertically()
                ) {
                    bgBadgeNow?.let { b ->
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp, end = 12.dp),
                            contentAlignment = Alignment.TopEnd
                        ) {
                            SubagentBadge(
                                badge = b,
                                onOpenDetail = { id ->
                                    badgeDetailId = id
                                    badgeDetail = vm.subagentDetailById(id)
                                }
                            )
                        }
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
                    // 断点恢复横幅（2026-10-14 治理，用户拍板「磨砂胶囊+单继续」）：
                    // 从全宽双行大卡瘦身成一行磨砂玻璃胶囊——左侧橙圆播放徽标、
                    // 主行「任务在第 N 轮中断」(结构信息，不再把 runGoal 截断塞标题)、
                    // 副行断点形态一句；右侧单个橙色「继续」胶囊 + 一个 × 收起。
                    // 「忽略」按钮退役：它是语义陷阱（清了状态后这个中断任务在会话里
                    // 再无专门入口）。× 只是收起提示——用户发新消息时引擎照常带持久化
                    // 历史续跑（Aether 同思路），不需要专门恢复入口。
                    com.haoai.agent.ui.common.GlassCard(
                        onClick = {},
                        backdrop = backdrop,
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(999.dp),
                        surfaceAlpha = com.haoai.agent.ui.theme.haoCardSurfaceAlpha(),
                        pressScale = false,
                        contentAlignment = androidx.compose.ui.Alignment.CenterStart,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 2.dp)
                    ) {
                        // 中断横幅 2026-10-22 重设计（用户拍板效果图 interrupt_redesign_mockup）：
                        // ① 橙▶徽标 → 琥珀⏸（警示语义归状态，动作绿归按钮——旧橙色与语义色系统无关）
                        // ② 「第 N 轮」→「第 N 步」（去引擎黑话，headline 在 SessionStore 同步改）
                        // ③ 「继续」小橙胶囊 → 绿实心「恢复执行」32dp 大按钮
                        // ④ × 删除——注释曾写"仅收起"，实现却调 dismissResume 清库里的断点状态，
                        //    手滑一次任务永久失去恢复入口（语义陷阱）。横幅消失只靠：点恢复、
                        //    或发新消息开新轮（startRun 改写 runState，gate 自然灭）。
                        val amberDeep = androidx.compose.ui.graphics.Color(0xFFB45309)
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(start = 8.dp, end = 6.dp, top = 5.dp, bottom = 5.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                        ) {
                            // 琥珀暂停徽标：状态警示
                            Box(
                                Modifier
                                    .size(28.dp)
                                    .clip(androidx.compose.foundation.shape.CircleShape)
                                    .background(androidx.compose.ui.graphics.Color(0xFFFDF3E0)),
                                contentAlignment = androidx.compose.ui.Alignment.Center
                            ) {
                                Icon(
                                    HaoIcons.Pause, contentDescription = null,
                                    tint = amberDeep,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                            Column(
                                Modifier
                                    .weight(1f)
                                    .padding(start = 9.dp, end = 6.dp)
                            ) {
                                Text(
                                    com.haoai.agent.data.StoredSession.interruptionHeadline(
                                        activeSession?.runTurnsUsed ?: 0
                                    ),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1
                                )
                                Text(
                                    interruptionNote,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            // 绿实心「恢复执行」（动作=绿；padding 上下 9dp → 总高 ~32dp 点击目标）
                            Text(
                                "恢复执行",
                                style = androidx.compose.ui.text.TextStyle(
                                    fontSize = 12.5.sp, lineHeight = 17.sp,
                                    fontWeight = FontWeight.Bold,
                                    platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false)
                                ),
                                color = androidx.compose.ui.graphics.Color.White,
                                modifier = Modifier
                                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(999.dp))
                                    .background(MaterialTheme.colorScheme.primary)
                                    .clickable { vm.resumeRun() }
                                    .padding(horizontal = 16.dp, vertical = 9.dp)
                            )
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
                        onAnswerFree = { id, text -> vm.answerAskFree(id, text) },
                        onFreeInputFocus = { askCardInputFocused = it }
                    )
                }
            }
            // ask_user_batch 题组卡：同位置；选完自动跳下一题（点选即作答，无确认环节）
            pendingQuizNow?.let { quiz ->
                androidx.compose.runtime.key("pending-quiz-${quiz.id}") {
                    PendingQuizCard(
                        quiz = quiz,
                        onAnswer = { id, qIndex, oIndex -> vm.answerQuiz(id, qIndex, oIndex) },
                        onAnswerFree = { id, qIndex, text -> vm.answerQuizFree(id, qIndex, text) },
                        onFreeInputFocus = { askCardInputFocused = it }
                    )
                }
            }
            // D17 批2：已暂停队列的恢复入口（ZCode 式——停止 ≠ 清队列，点继续自动接上）
            val queuePausedSid by vm.queuePaused.collectAsState()
            if (queuePausedSid != null) {
                Row(
                    Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(bottom = 6.dp)
                        .background(
                            MaterialTheme.colorScheme.tertiary.copy(alpha = 0.10f),
                            CircleShape
                        )
                        .border(
                            1.dp,
                            MaterialTheme.colorScheme.tertiary.copy(alpha = 0.30f),
                            CircleShape
                        )
                        .clip(CircleShape)
                        .clickable { vm.resumeQueue() }
                        .padding(horizontal = 13.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        HaoIcons.Play,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.size(5.dp))
                    Text(
                        "队列已暂停 · 继续自动发送",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.tertiary
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
                        lensRadius = com.haoai.agent.ui.theme.GlassTuning.lensHeight.dp,
                        lensAmount = com.haoai.agent.ui.theme.GlassTuning.lensAmount.dp,
                        depthEffect = com.haoai.agent.ui.theme.GlassTuning.depthEffect,
                        surfaceAlpha = com.haoai.agent.ui.theme.haoCardSurfaceAlpha(),
                        // v7.2：文案长短切换时胶囊宽度平滑过渡（180ms 弹性），不再瞬跳
                        modifier = Modifier.animateContentSize(
                            animationSpec = tween(180, easing = LinearEasing)
                        ),
                        chromaticAberration = com.haoai.agent.ui.theme.GlassTuning.ca
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
                                // 收尾空档：最终消息已落行、流式已清、工具已结算，running 还没翻 false。
                                // 本轮产出过内容 → 是"在整理回答"（真机实拍：这里曾闪"正在连接模型"）；
                                // 本轮还没产出过 → 才是真的"正在连接模型"（回合刚起跑）
                                else -> when {
                                    turnHadOutput -> "正在整理回答"
                                    vm.isLocalProviderActive() ->
                                        "端侧推理 · 正在理解上下文（需预处理全部提示词，可能数十秒）"
                                    else -> "正在连接模型"
                                }
                            }
                            ThinkingIndicator(phaseText, turnStartAt)
                        }
                    }
                }
            }
            // v6.1 补档（2026-10-12 用户定稿）：running 真→假边沿定格本轮耗时后，
            // 在与运行胶囊**同一位置**显示「✓ 回复完毕 · N秒」4 秒（由上方 effect 归零）。
            // 旧版跑完状态行直接消失——用户在上方翻历史时完全不知道下面已经出结果。
            // 与运行胶囊天然互斥（turnDoneMs 仅在 running=false 时非空），无需额外门。
            androidx.compose.animation.AnimatedVisibility(
                visible = turnDoneMs != null,
                enter = androidx.compose.animation.fadeIn(tween(160)) +
                    androidx.compose.animation.expandVertically(tween(200)),
                exit = androidx.compose.animation.fadeOut(tween(140)) +
                    androidx.compose.animation.shrinkVertically(tween(160))
            ) {
                Row(Modifier.padding(start = 2.dp, bottom = 6.dp)) {
                    GlassPanel(
                        backdrop = backdrop,
                        radius = 14.dp,
                        lensRadius = com.haoai.agent.ui.theme.GlassTuning.lensHeight.dp,
                        lensAmount = com.haoai.agent.ui.theme.GlassTuning.lensAmount.dp,
                        depthEffect = com.haoai.agent.ui.theme.GlassTuning.depthEffect,
                        surfaceAlpha = com.haoai.agent.ui.theme.haoCardSurfaceAlpha(),
                        chromaticAberration = com.haoai.agent.ui.theme.GlassTuning.ca
                    ) {
                        Row(
                            Modifier.padding(start = 12.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // 三档：异常中断（红）／撞上限提前收尾（琥珀）／正常完毕（主色）
                            val doneIcon = when {
                                turnDoneError -> Icons.Filled.WarningAmber
                                turnDoneCapped -> Icons.Filled.WarningAmber
                                else -> Icons.Filled.Check
                            }
                            val doneTint = when {
                                turnDoneError -> MaterialTheme.colorScheme.error
                                turnDoneCapped -> com.haoai.agent.ui.theme.haoToneMain(
                                    com.haoai.agent.ui.theme.HaoTone.Warn
                                )
                                else -> MaterialTheme.colorScheme.primary
                            }
                            Icon(
                                imageVector = doneIcon,
                                contentDescription = null,
                                tint = doneTint,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.size(7.dp))
                            Text(
                                when {
                                    turnDoneError -> "意外中断"
                                    turnDoneCapped -> "达上限提前收尾"
                                    else -> "回复完毕"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = doneTint,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1
                            )
                            Spacer(Modifier.size(8.dp))
                            Text(
                                String.format(
                                    Locale.US, "%.1fs",
                                    (turnDoneMs ?: 0L) / 1000.0
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                            )
                        }
                    }
                }
            }
            // 顶栏/输入框的导出层：它们自身 drawBackdrop 时把最终表面 record 进来
            val topBarExportBackdrop = com.kyant.backdrop.backdrops.rememberLayerBackdrop()
            val composerExportBackdrop = com.kyant.backdrop.backdrops.rememberLayerBackdrop()
            // 引用片段卡（微信同款）：左绿线+来源+两行摘要+✕，挂在输入框上方
            androidx.compose.animation.AnimatedVisibility(
                visible = quoteDraft != null,
                enter = androidx.compose.animation.expandVertically(tween(160)) +
                    androidx.compose.animation.fadeIn(tween(160)),
                exit = androidx.compose.animation.shrinkVertically(tween(140)) +
                    androidx.compose.animation.fadeOut(tween(120)),
                modifier = Modifier.padding(horizontal = 14.dp)
            ) {
                quoteDraft?.let { q ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f))
                            .padding(10.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        // 左绿线
                        Box(
                            Modifier
                                .width(3.dp)
                                .height(34.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(MaterialTheme.colorScheme.primary)
                        )
                        Spacer(Modifier.width(9.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                (if (q.role == "user") "引用你的消息" else "引用回复") +
                                    " · " + fmtMsgTs(q.ts),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                q.text,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Icon(
                            HaoIcons.Close, "移除引用",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier
                                .size(18.dp)
                                .clip(CircleShape)
                                .clickable { quoteDraft = null }
                        )
                    }
                }
            }
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
                pendingImages = pendingImages,
                onPickImage = { launchImagePicker() },
                onTakePhoto = {
                    if (androidx.core.content.ContextCompat.checkSelfPermission(
                            context, android.Manifest.permission.CAMERA
                        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    ) launchCamera() else
                        cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
                },
                onPickDocument = { documentPicker.launch(arrayOf("text/*", "application/pdf", "application/json", "application/xml")) },
                onRemoveImage = { idx -> pendingImages = pendingImages.filterIndexed { i, _ -> i != idx } },
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
                    // 引用片段卡：发送时才拼成 Markdown 引用块进正文（输入框期间保持干净）
                    val quotePrefix = quoteDraft?.let { q ->
                        "> " + q.text.trim().replace("\n", "\n> ") + "\n\n"
                    } ?: ""
                    val fullText = quotePrefix + docPrefix + trimmed
                    if (fullText.isNotBlank() || pendingImages.isNotEmpty() || pendingAudioPath != null || pendingVideoPath != null) {
                        vm.send(fullText, pendingImages, pendingAudioPath, pendingVideoPath)
                        input = ""
                        quoteDraft = null
                        pendingImages = emptyList()
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
            // 顶部通知条玻璃化（2026-10-14 用户拍板）：Material 默认深灰 Snackbar
            // 是全 App 最"一眼不是 HaoAI"的件。换同款磨砂玻璃胶囊 + 场景图标
            // （余额/密钥/限流/断网各有专属图标，图标比"充值"二字先传达语义）；
            // 位置下移到顶栏之下，不再遮挡 App 顶栏。判定文案（friendlyErrorText）不动。
            val (errIcon, errTint) = errorScene(msg)
            com.haoai.agent.ui.common.GlassCard(
                onClick = {},
                backdrop = backdrop,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                surfaceAlpha = com.haoai.agent.ui.theme.haoCardSurfaceAlpha(),
                pressScale = false,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = topBarHeightDp + 14.dp, start = 12.dp, end = 12.dp)
                    .fillMaxWidth()
            ) {
                Row(
                    Modifier.padding(start = 10.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.Top
                ) {
                    Box(
                        Modifier
                            .size(22.dp)
                            .padding(top = 1.dp)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .background(errTint.copy(alpha = 0.16f)),
                        contentAlignment = androidx.compose.ui.Alignment.Center
                    ) {
                        Icon(
                            errIcon, contentDescription = null,
                            tint = errTint, modifier = Modifier.size(13.dp)
                        )
                    }
                    Text(
                        msg.removePrefix("⚠️ ").removePrefix("⏳ "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 4,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 8.dp)
                    )
                    Box(
                        Modifier
                            .padding(start = 4.dp)
                            .size(24.dp)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .clickable { vm.dismissError() },
                        contentAlignment = androidx.compose.ui.Alignment.Center
                    ) {
                        Icon(
                            HaoIcons.Close, contentDescription = "关闭",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(13.dp)
                        )
                    }
                }
            }
        }

        // ⑧ 回底浮钮 v3（方向感知，用户效果图定稿）：显隐由 MessageList 逐帧
        // 循环按滚动方向驱动（向下翻+离底>150dp 浮现 / 向上翻即隐 / 停手 2.5s 淡出），
        // 这里只负责画一个 40dp 居中小圆 + 点击回底。不再计数（正文居中，
        // 旧宽胶囊横腰截断流式落字区=用户最初报怨的遮挡）。
        // 必须画在 appLayer 采样层之外（与玻璃顶栏同级）——放进 MessageList 的
        // Box 包裹会切断 drawBackdrop 采样链，整屏透出白色遮罩（实测踩坑）
        androidx.compose.animation.AnimatedVisibility(
            visible = jumperVisible.value && drawerFraction < 0.01f,
            enter = androidx.compose.animation.fadeIn(tween(180)) +
                androidx.compose.animation.scaleIn(tween(180), initialScale = 0.8f),
            exit = androidx.compose.animation.fadeOut(tween(140)) +
                androidx.compose.animation.scaleOut(tween(140), targetScale = 0.8f),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = with(density) {
                    maxOf(132.dp, bottomBarHeightPx.toDp() + 8.dp) + keyboardLiftPx.toDp() + 10.dp
                })
        ) {
            // 40dp 玻璃小圆（GlassCard 全 app 统一材质，按压折射缩放同发送钮）；
            // Agent 正在产出且被钮浮现时，右上角挂一枚绿色呼吸点——只说
            // 「下面有动静」，不计数。
            Box {
                GlassCard(
                    onClick = {
                        userScrolledAway.value = false
                        // 用户主动回底=重新接管，撤停车（配合循环里 delta==0 安全网双保险）
                        parkedFollow.value = false
                        jumperVisible.value = false
                        // requestScrollToItem 会一并取消进行中的滚动会话（惯性甩动被接管，
                        // 与旧行为的差异：旧 dispatchRawDelta 不取消会话、会被甩动反超）
                        listState.requestPinToBottom()
                    },
                    backdrop = backdrop,
                    shape = CircleShape,
                    surfaceAlpha = com.haoai.agent.ui.theme.haoCardSurfaceAlpha(),
                    lensRadius = 14.dp,
                    modifier = Modifier.size(40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "↓",
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                }
                if (running || streaming != null) {
                    val pulse by com.haoai.agent.ui.common.rememberPulse(0.35f, 1f, 1200)
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .offset(2.dp, (-2).dp)
                            .size(11.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surface)
                    ) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .padding(2.dp)
                                .clip(CircleShape)
                                .background(
                                    MaterialTheme.colorScheme.primary.copy(alpha = pulse)
                                )
                        )
                    }
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
                        // 统一玻璃配方（2026-10-08 用户裁决：折射/模糊全局一致）：
                        // 原 blur16+lens16×2+色差写死，现折射/模糊/色差走调参，
                        // 白雾 0.62 保留（抽屉常年压在正文上，比卡片更实一档是刻意的）
                        surfaceAlpha = 0.62f,
                        blurRadius = com.haoai.agent.ui.theme.GlassTuning.blur.dp,
                        lensRadius = com.haoai.agent.ui.theme.GlassTuning.lensHeight.dp,
                        lensAmount = com.haoai.agent.ui.theme.GlassTuning.lensAmount.dp,
                        depthEffect = com.haoai.agent.ui.theme.GlassTuning.depthEffect,
                        chromaticAberration = com.haoai.agent.ui.theme.GlassTuning.ca,
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
                            runningIds = vm.runningIds.collectAsState().value,
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
                            onDeleteSession = { id ->
                                val wasActive = vm.session.value?.id == id
                                vm.deleteSession(id)
                                // 删除的正是正在看的会话：VM 内部已自动 newSession() 跳转，
                                // 但 72% 盖层抽屉不收起来的话，用户看不见跳转落地、以为
                                // 「当前会话删不掉」（2026-10-13 真机反馈）。收起后直接
                                // 露出新会话空页；非当前会话删除仍留在抽屉里方便连删。
                                if (wasActive && !landscapeTwoPane) scope.launch { drawer.close() }
                            },
                            // 工作区共享壳（方案 B）：抽屉只留一行「工作区」，三视图进壳内 Tab。
                            // 抽屉不收：全屏实底壳盖在上面，返回即原样露出——首版"进壳收抽屉、
                            // 退出再 drawer.open()"被用户点名（退出会重播一次开合动画）
                            onOpenWorkspace = { workspaceOpen = true },
                            onEditProfile = { showProfileEdit = true },
                            onSettings = {
                                onOpenSettings()
                            }
                        )
                    }
        }
        }

    // 工作区浏览单页（批4b 取代三 Tab 壳）：抽屉「工作区」呼出；查看器「在目录中
    // 定位」也开它并落到目标文件所在目录（locateRel）。架构沿用壳定稿：实底+本页
    // 自带采样画布，玻璃只采本页底；返回=只关页，抽屉原样露出。
    if (workspaceOpen) {
        WorkspaceBrowserDialog(
            wallpaper = wallpaper,
            workspaceName = vm.workspaceName(),
            backendReady = vm.treeBackend() != null,
            listDir = { vm.treeListDir(it) },
            walk = { vm.treeWalk() },
            resolve = { vm.fileRefOfRel(it) },
            deleteFile = { vm.deleteWorkspaceFile(it) },
            locateRel = fileTreeLocate,
            onLocateConsumed = { fileTreeLocate = null },
            onDismiss = { workspaceOpen = false }
        )
    }

    // 工具详情弹层（v8 思维链）：appLayer 外渲染 → GlassPanel 真玻璃折射消息流
    toolSheet?.let { (t, subId) ->
        ToolDetailSheet(
            tool = t,
            backdrop = backdrop,
            onDismiss = { toolSheet = null },
            initialSubagentId = subId,
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

    // P4 浮标详情弹层：面板行点击带入 id 并抓快照；此后**弹层自轮询**（1s）跟进——
    // 不能靠 bgBadge 发射驱动根级重组：唤醒回合一起跑 badge 置 null、StateFlow 去重
    // 不再发射，弹层会冻死在旧快照（实测「仍在工作中」永不翻面）。轨迹被清时保快照不闪退。
    LaunchedEffect(badgeDetailId) {
        while (badgeDetailId != null) {
            vm.subagentDetailById(badgeDetailId!!)?.let { badgeDetail = it }
            kotlinx.coroutines.delay(1000)
        }
    }
    badgeDetail?.takeIf { badgeDetailId != null }?.let { d ->
        SubagentBadgeDetailSheet(
            detail = d,
            backdrop = backdrop,
            onDismiss = { badgeDetailId = null },
            onStop = { vm.stopSubagent(it) }
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
                quoteDraft = target
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
    // 图片全屏查看器宿主（批1d）：图集由 LocalImageLightboxLauncher 上抛
    imageLightbox?.let { (list, url) ->
        com.haoai.agent.ui.common.ImageLightbox(
            images = list, currentUrl = url, onDismiss = { imageLightbox = null }
        )
    }
    editTarget?.let { target ->
        var editText by remember(target.id) { mutableStateOf(target.text) }
        // D17 批2：队列已暂停又有新消息 → 选择（ZCode heldQueueInputRequiresChoice）。
        // 两个动作都放内容区（不放 footer dismiss 位）：onDismiss 同时挂在遮罩点击上，
        // "清空队列"这种破坏性动作绝不能被误点遮罩触发——点外面只关窗、不选。
        val queueHold by vm.queueHold.collectAsState()
        queueHold?.let { h ->
            com.haoai.agent.ui.common.GlassAlertDialog(
                backdrop = backdrop,
                title = "队列已暂停",
                onDismiss = { vm.dismissQueueHold() },
                confirmLabel = "取消",
                onConfirm = { vm.dismissQueueHold() }
            ) {
                Text(
                    "这条会话还排着 " + h.pendingCount + " 条消息（已暂停，不会自动发）。" +
                        "这条新消息怎么处理？",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
                Spacer(Modifier.size(10.dp))
                // 主操作：保留队列立即发送（绿实底）
                com.haoai.agent.ui.common.LiquidGlassButton(
                    onClick = { vm.confirmQueueHoldKeep() },
                    backdrop = backdrop,
                    shape = RoundedCornerShape(percent = 50),
                    modifier = Modifier.fillMaxWidth(),
                    surfaceColor = MaterialTheme.colorScheme.primary.copy(alpha = 1f)
                ) {
                    Text(
                        "保留队列，立即发送这条",
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                    )
                }
                Spacer(Modifier.size(8.dp))
                // 破坏性：清空队列后发送（红底=破坏动作，语义色相铁律）
                com.haoai.agent.ui.common.LiquidGlassButton(
                    onClick = { vm.confirmQueueHoldClear() },
                    backdrop = backdrop,
                    shape = RoundedCornerShape(percent = 50),
                    modifier = Modifier.fillMaxWidth(),
                    surfaceColor = MaterialTheme.colorScheme.error.copy(alpha = 1f)
                ) {
                    Text(
                        "清空队列，只发这条",
                        color = MaterialTheme.colorScheme.onError,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                    )
                }
            }
        }

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
            // 多行文本域（真机反馈：旧 CompactGlassField 是 40dp 单行框，多行文字压边框）。
            // 自适应高度、限高内滚动、聚焦绿框、右下角实时字数
            var focused by remember { mutableStateOf(false) }
            val shape = RoundedCornerShape(11.dp)
            androidx.compose.foundation.text.BasicTextField(
                value = editText,
                onValueChange = { editText = it },
                maxLines = 8,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onBackground, lineHeight = 22.sp
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 88.dp, max = 220.dp)
                    .clip(shape)
                    .background(MaterialTheme.colorScheme.onBackground.copy(alpha = if (focused) 0.08f else 0.05f), shape)
                    .border(if (focused) 1.5.dp else 1.dp,
                        if (focused) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.30f), shape)
                    .padding(12.dp)
                    .verticalScroll(rememberScrollState())
                    .onFocusChanged { focused = it.isFocused },
                decorationBox = { inner ->
                    Box {
                        if (editText.isEmpty()) {
                            Text("编辑消息内容…", style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.35f))
                        }
                        inner()
                    }
                }
            )
            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "发送后生成新版本，旧版可用 ‹ › 回看",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "${editText.length} 字",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
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
    // 字段区抽成 ProfileEditFields 与设置「我的档案」共用（聊天社交化 2026-10-07）
    if (showProfileEdit) {
        var editName by remember { mutableStateOf(settings.agentName.ifBlank { "HaoAI" }) }
        var editEmoji by remember { mutableStateOf(settings.avatarEmoji) }
        var editGradient by remember { mutableStateOf(settings.avatarGradient) }
        var editBio by remember { mutableStateOf(settings.bio) }
        var editImagePath by remember { mutableStateOf(settings.avatarImagePath) }
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
            ProfileEditFields(
                backdrop = backdrop,
                name = editName, onName = { editName = it },
                emoji = editEmoji, onEmoji = { editEmoji = it },
                gradient = editGradient, onGradient = { editGradient = it },
                bio = editBio, onBio = { editBio = it },
                imagePath = editImagePath, onRemoveImage = { editImagePath = null },
                onPickImage = { avatarPicker.launch("image/*") }
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
                // 统一玻璃配方（2026-10-08）：原不传 lens → 继承 radius28×默认2=位移56
                // 全 App 最重；折射并入全局，色差走开关
                lensRadius = com.haoai.agent.ui.theme.GlassTuning.lensHeight.dp,
                lensAmount = com.haoai.agent.ui.theme.GlassTuning.lensAmount.dp,
                depthEffect = com.haoai.agent.ui.theme.GlassTuning.depthEffect,
                chromaticAberration = com.haoai.agent.ui.theme.GlassTuning.ca
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

/** 顶栏采样环带（GlassPanel.edgeBleedPx）：须 ≥ blur(15dp)+lens 折射高(16dp)，余量见 Glass.kt 注释。 */
private val TopBarBleed = 32.dp

/**
 * 顶栏专用采样源：壁纸本体 + 环带向外拉伸垫底，再叠主采样层（2026-10-27 状态栏带根治）。
 *
 * 病根（库源码实证）：blur() 在效果链已有 renderEffect（vibrancy 在前）时把记录画布
 * 外扩 padding=模糊半径，但内容只平移 +padding 绘制——**外扩环无人填色 = 透明环**；
 * 玻璃贴屏幕边时 blur 在边缘混入透明环 → 贴边一圈磨砂失效、锐利正文从状态栏带透出
 * （遮罩全关实测幽灵文字，v5/v6 遮罩属治标被否）。GlassPanel.edgeBleedPx 让环带存在，
 * 本类把环带垫上壁纸延伸：顶行/左右列向外拉伸（接缝 = 壁纸第 0 行/列，C0 连续），
 * 环外越界由 blur 的 TileMode.Clamp 收口到环边像素。环带被 shape 裁剪不上屏。
 * 主采样层几何不变（坐标自对齐），壁纸 cover-fit 与 rememberAppBackdrop 逐字同几何。
 */
private class BledTopBarBackdrop(
    private val wallpaperImage: androidx.compose.ui.graphics.ImageBitmap?,
    private val main: com.kyant.backdrop.Backdrop,
    private val ringPx: Float,
    private val screenW: Float,
    private val screenH: Float,
    private val baseColor: Color
) : com.kyant.backdrop.Backdrop {
    override val isCoordinatesDependent = true

    override fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBackdrop(
        density: androidx.compose.ui.unit.Density,
        coordinates: androidx.compose.ui.layout.LayoutCoordinates?,
        layerBlock: (androidx.compose.ui.graphics.GraphicsLayerScope.() -> Unit)?
    ) {
        // 画布原点 = 节点左上角 = 屏幕 (0,0)；负坐标即记录画布的外扩环带。
        wallpaperImage?.let { wp ->
            val s = maxOf(screenW / wp.width, screenH / wp.height)
            val dw = wp.width * s
            val dh = wp.height * s
            val dx = (screenW - dw) / 2f
            val dy = (screenH - dh) / 2f
            // 壁纸本体（屏幕对齐，cover-fit）
            drawImage(
                wp,
                dstOffset = androidx.compose.ui.unit.IntOffset(dx.roundToInt(), dy.roundToInt()),
                dstSize = androidx.compose.ui.unit.IntSize(dw.roundToInt(), dh.roundToInt())
            )
            // 顶行向上拉伸盖顶环（横向多铺一个环宽，顺带盖住上两角）
            drawImage(
                wp,
                srcOffset = androidx.compose.ui.unit.IntOffset(0, 0),
                srcSize = androidx.compose.ui.unit.IntSize(wp.width, 1),
                dstOffset = androidx.compose.ui.unit.IntOffset(
                    (dx - ringPx).roundToInt(),
                    (dy - ringPx).roundToInt()
                ),
                dstSize = androidx.compose.ui.unit.IntSize(
                    (dw + ringPx * 2).roundToInt(),
                    (ringPx + (if (dy < 0f) -dy else 0f)).roundToInt().coerceAtLeast(1)
                )
            )
            // 左列/右列向左右拉伸盖侧环（纵向覆盖 dy..dy+dh ⊇ 玻璃全高 + 底环）
            drawImage(
                wp,
                srcOffset = androidx.compose.ui.unit.IntOffset(0, 0),
                srcSize = androidx.compose.ui.unit.IntSize(1, wp.height),
                dstOffset = androidx.compose.ui.unit.IntOffset((dx - ringPx).roundToInt(), dy.roundToInt()),
                dstSize = androidx.compose.ui.unit.IntSize(ringPx.roundToInt(), dh.roundToInt())
            )
            drawImage(
                wp,
                srcOffset = androidx.compose.ui.unit.IntOffset(wp.width - 1, 0),
                srcSize = androidx.compose.ui.unit.IntSize(1, wp.height),
                dstOffset = androidx.compose.ui.unit.IntOffset((dx + dw).roundToInt(), dy.roundToInt()),
                dstSize = androidx.compose.ui.unit.IntSize(ringPx.roundToInt(), dh.roundToInt())
            )
        } ?: drawRect(
            // 素色底：渐变矩形直接外扩一个环宽（渐变越界端钳在端色）
            Brush.verticalGradient(listOf(baseColor, baseColor), startY = 0f, endY = screenH),
            topLeft = androidx.compose.ui.geometry.Offset(-ringPx, -ringPx),
            size = androidx.compose.ui.geometry.Size(size.width + ringPx * 2, size.height + ringPx * 2)
        )
        with(main) {
            drawBackdrop(density, coordinates, layerBlock)
        }
    }
}

@Composable
private fun TopBar(
    title: String,
    subtitle: String,
    contextUsage: ContextUsage,
    backdrop: com.kyant.backdrop.Backdrop,
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
        lensAmount = com.haoai.agent.ui.theme.haoPageBarLensAmount(),
        depthEffect = com.haoai.agent.ui.theme.GlassTuning.depthEffect,
        // blurRadius 默认=radius/3，方角顶栏 radius=0 会得到 blur(0)——显式给模糊量
        blurRadius = com.haoai.agent.ui.theme.haoPageBarBlurRadius(),
        surfaceAlpha = com.haoai.agent.ui.theme.haoPageBarSurfaceAlpha(),
        border = false,
        // 采样环带：记录画布外扩，BledTopBarBackdrop 垫壁纸延伸——状态栏带磨砂
        // 与中央一致（根治，2026-10-27；机理见 Glass.kt edgeBleedPx 注释）
        edgeBleedPx = with(density) { TopBarBleed.toPx() }
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
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    // 效果图 t1 规范：单行省略——长会话名换行会把顶栏撑高两行
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
                // 社交化定稿：副标题（Agent 名）已移除；空串不渲染避免占一行高度，
                // 重命名入口随标题区整列点击保留
                if (subtitle.isNotBlank()) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
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
                Icon(HaoIcons.Add, contentDescription = "新会话", tint = MaterialTheme.colorScheme.onBackground)
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
    /** 本轮起点（0=没在跑）：页脚硬闸只封本轮新行，历史行统计不受牵连（2026-10-11）。 */
    turnStartAt: Long = 0L,
    thinkingHint: String? = null,
    thinkingMs: Long? = null,
    liveToolsSnapshot: List<com.haoai.agent.ui.UiTool> = emptyList(),
    /** 从流式区晋升落行的 key：这些行跳过入场淡入（同帧内容已在上屏，淡入=闪一下）。 */
    promotedRowKeys: Set<String> = emptySet(),
    /** 虚拟化（2026-10-28）：列表滚动状态 = LazyListState（MainActivity 提升实例）。 */
    listState: androidx.compose.foundation.lazy.LazyListState,
    /** listState 当前所属会话（RootApp 提升实例）：切会话贴底判据。放本组件内会随
     *  AnimatedContent 销毁分支丢失 → 设置往返被误判成切会话而跳底（实测），故提升。 */
    listSessionId: androidx.compose.runtime.MutableState<String?>,
    userScrolledAway: androidx.compose.runtime.MutableState<Boolean>,
    /** 松手停车（2026-10-11）：滚动会话结束帧停在底部上方=用户有意停车，撤贴底跟随。 */
    parkedFollow: androidx.compose.runtime.MutableState<Boolean>,
    /** 展开锚点豁免（2026-10-13）：点展开置 now+窗口，跟随循环窗口内跳过吸底=锚定条不被顶出。 */
    expandParkUntil: androidx.compose.runtime.State<Long>,
    /** 回底浮钮 v3 显隐（2026-10-12 方向感知定稿）：由逐帧循环按位移**方向**驱动——
     *  向下翻且离底超门槛=浮现（dV>0 且非我方钉底帧），向上翻（阅读意图）=即隐；
     *  闲置 2.5 秒淡出的计时也在循环内（本地时间戳，不逐帧写 State）。 */
    jumperVisible: androidx.compose.runtime.MutableState<Boolean>,
    /** 键盘抬升高度（px，ChatScreen keyboardLiftState）：停车态下逐帧补偿滚动用。
     *  必须是 State——逐帧循环每帧读最新值；传普通 Int 参数会捕获快照、永不更新。 */
    keyboardLiftPxState: androidx.compose.runtime.State<Int>,
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
    // 虚拟化版（2026-10-28）判定源从 ScrollState 的 value/maxValue 换回 item 级
    // layoutInfo（末项底边 vs 视口底），B′ 当年弃用它是因为依赖"末项已组合"——
    // 现在「末项不在视口」本身就被 distanceToBottomPx 判为离底很远，语义自洽。
    val bottomSlackPx = with(androidx.compose.ui.platform.LocalDensity.current) { 48.dp.roundToPx() }
    // 回底浮钮 v3 门槛（2026-10-12 方向感知定稿）：离底不足 150dp 时不冒钮——
    // 那点距离手指一划就到，弹出来纯属添乱（用户报怨旧版「没有距离门槛」）。
    val jumperGapPx = with(androidx.compose.ui.platform.LocalDensity.current) { 150.dp.roundToPx() }
    // 「本轮用户是否主动滚动过」userScrolledThisRun 已删除（2026-10-12 三修）：
    // 它是旧「猜意图」方案的状态；粘底状态机只看位移原因（dV/dM），不需要跨帧意图标志。

    // ── 虚拟化（2026-10-28，B′ 手动窗口化退役）────────────────────────────
    // LazyColumn 接管视口裁剪：只组合/测量可见 item，滚动=锚点位移。删除：
    // windowSize/扩窗循环/贴顶补偿 dispatchRawDelta/「更早的消息」提示行/hiddenRowCount
    // ——虚拟化后没有窗口概念，全量 rows 直接交给 itemsIndexed。
    // 轮级折叠 slots 原样保留：每个槽位一个 item，Plain=row.key、Group=headRow.key
    // 作稳定 key（ChatRow.key=消息 id，删除/重发不漂移）。正在进行的轮从首条过程行
    // 起就是 Group（key 稳定），终答落行只改组内容不改 key → 折叠瞬间视口锚点不丢。
    val active = running || showStreaming
    val slots = remember(rows, active) { buildRenderSlots(rows, active) }
    // item 总数（与下方 items 的实际构成同源）：切会话/发消息的贴底请求发生时
    // layoutInfo 还是上一次的测量，必须用本帧组合已知的数量兜底。
    val expectedItemCount = slots.size + (if (showStreaming) 1 else 0) +
        (if (planProposal != null) 1 else 0)

    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current

    // 发送新消息收起键盘：按末条 user 消息 key 去重——流式期间最后一条仍是 user，
    // 不能每次都收，否则用户流式中打开键盘想插话会被下一个 delta 误关
    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    var lastSentUserKey by rememberSaveable { mutableStateOf<String?>(null) }
    // footer 展开动画启动的通知（RowItem 生长动画仍会回调，保留供排查）
    var footerRevealTick by remember { mutableStateOf(0) }

    // 发送新消息 / 切会话：无条件贴底并把粘滞复位。
    // 只挂这两个 key——流式期的持续跟随交给下面的逐帧循环，不再靠 text.length 之类的 key。
    // 切会话判据=listSessionId（RootApp 提升）：本组件随 AnimatedContent 销毁重建，
    // 会话指纹放这里的话，设置往返会被误判成切会话而跳底（旧 rememberSaveable 版
    // 在销毁分支不恢复，实测同病）——提升后只有真换会话/冷启动才贴底。
    LaunchedEffect(rows.lastOrNull()?.key, sessionId) {
        if (totalItems <= 0) return@LaunchedEffect
        // 切会话：无条件贴底并复位粘滞（切换后停在旧位置；末条未必是 user 消息，
        // 逐帧循环又会被"已断开粘滞"拦下 → 必须先复位）
        if (sessionId != null && sessionId != listSessionId.value) {
            listSessionId.value = sessionId
            userScrolledAway.value = false
            parkedFollow.value = false
            jumperVisible.value = false
            // 此时 layoutInfo 还是旧会话的测量（新会话重测在本帧 layout 阶段才发生），
            // distanceToBottomPx 会拿旧数据误判「已贴底」→ 不能走 gap 守卫的 pinToBottom；
            // 用本帧组合已知的 item 数直接发起贴底请求，与新会话首测同帧应用。
            listState.requestPinToBottom(expectedItemCount)
            return@LaunchedEffect
        }
        val isUserMessage = rows.lastOrNull()?.role == "user"
        if (isUserMessage) {
            userScrolledAway.value = false
            parkedFollow.value = false
            jumperVisible.value = false
            val k = rows.last().key
            if (k != lastSentUserKey) {
                lastSentUserKey = k
                keyboardController?.hide()
                focusManager.clearFocus()
            }
            // 新消息同帧贴底：请求在下一次重测（即新行首次测量的那一次）生效，
            // 新行出现帧直接以贴底姿态绘制，无中间帧
            listState.requestPinToBottom(expectedItemCount)
        } else {
            // 新行入列：是否跟随交给位置订阅判定，这里只在"仍贴着底"时补一次；
            // 停车中（用户拖把手刚松手）不补——否则绕过 parkedFollow 又钉一次底
            if (!parkedFollow.value && listState.isAtBottom(bottomSlackPx)) listState.pinToBottom()
        }
    }

    // ── 版本翻页闪帧补偿（LazyColumn 重设计，2026-10-28）────────────────────
    // Column 时代的 draw 阶段 graphicsLayer translationY 方案已删：它靠 onSizeChanged
    // 量测整体 contentHeight——LazyColumn 的列表节点尺寸不变（变的只是内部 item），
    // 那个量测点不存在了。新方案利用 requestScrollToItem「下一次重测生效」的语义：
    // 贴底侧翻页**先**排队贴底请求、再切内容——切版变高触发的那次重测会同时应用
    // 两者，变高帧直接以贴底姿态出现，中间帧不存在（比旧方案还少一帧）。
    // 非贴底侧（用户滚上去回看版本）照旧不动画；手势中让位（isScrollInProgress）。
    val outerSwitch = androidx.compose.runtime.rememberUpdatedState(LocalSwitchVersion.current)
    val versionSwitch: (String, Int) -> Unit = { uid, idx ->
        if (!listState.isScrollInProgress && listState.isAtBottom(bottomSlackPx)) {
            listState.requestPinToBottom()
            if (FollowTrace.enabled) FollowTrace.addSlow("PAGER_ARM")
        }
        outerSwitch.value(uid, idx)
    }

    // 拖选把手守卫（2026-10-11）：长按拖选后把手拖到靠近视口底缘时，文本选择会自动
    // 滚动容器把把手保持可见（内容上移=用户看到的「往上弹」）；跟随循环这时又把
    // ≤48dp 的剩余距离 pin 到底，与拖选边缘滚动逐帧打架=反复弹跳。判据=「手指是否
    // 按在列表上」（原始 down/up，不判消费：把手拖拽被选择层消费、不走滚动会话
    // isScrollInProgress，两条常规让位闸都漏掉它）。按住期间暂停贴底，抬手即恢复。
    val fingerOnList = remember { mutableStateOf(false) }
    // 允许贴底的条件（三选一）：
    //   ① 本帧贴着底                 —— 正常跟随
    //   ② 上一帧贴着底（单帧宽限）   —— 吸收结构跳变造成的千 px 位移
    //   ③ 本轮用户从未滚动过         —— 用户没打断就不能停，任何"停住"都能自愈
    // 另加：手指/惯性滚动期间一律让位（自己的一次性贴底只占 1 帧，不会误伤跟随）。
    // 三者叠加后，"停住"只可能发生在用户自己滚走之后 —— 那正是期望行为。
    // 为什么不再单纯依赖位置：真机探针实测 delta=2816/197（收尾换位、长行落行）
    // 会让纯位置判定永久停手（位置判定无状态、不会自己回来）；③ 就是那副解药。
    // 虚拟化版（2026-10-28）：判定源=distanceToBottomPx()（末项底边 vs 视口底），
    // 位移归因 dV 用 firstVisibleItemIndex/Offset 复合代理（行只在末尾增删、折叠只
    // 发生在末轮，锚点之上的 item 结构不变 → 该代理与旧 ScrollState.value 同样单调）。
    // 关键不变量保持：每帧必有记录、自愈循环（异常重连）、prevPinnedByUs 归因。
    LaunchedEffect(listState, bottomSlackPx) {
        var prevAtBottom = true
        var busyFrames = 0
        var lastEndPos = -1L
        var prevPinnedByUs = false
        // 粘底状态机（2026-10-12 三修）：逐帧比对位移量判「这一帧的位移是谁造成的」。
        // -1=首帧未知。
        var prevFrameV = -1L
        // 停车补偿自认闸（2026-10-29）：dispatchRawDelta 不是滚动会话，它造成的位移
        // 在下一帧以 dV≠0 现形。若不豁免，会被"位移归因"判成用户挪过 → 误亮回底浮钮、
        // 刷闲置计时。语义同 prevPinnedByUs（钉底帧豁免），二者取或。
        var prevLiftedByUs = false
        // 回底浮钮 v3 闲置淡出计时基准（2026-10-12 方向感知定稿）：最近一次用户
        // 归因滚动帧的时刻。是循环内**本地变量**——钮的「停手 2.5 秒淡出」用它
        // 判闲置，绝不每帧写 State（每帧写=读取方每帧重组，主题页 jank 同款教训）。
        var lastUserScrollFrameMs = 0L
        // 键盘升起补偿（2026-10-29 修「停车态呼出键盘内容不抬」）：停车（parked）时
        // 粘底让位，"内容随键盘抬起"没人负责——账本=已代键盘补滚的像素，停车态每帧
        // 向 keyboardLiftPx 追平（多退少补）；跟随态由 pin 代劳，账本对齐 lift。
        var imeCompensatedPx = 0f
        // 【自愈】2026-09-19：把跟随改成逐帧驱动后，这里再包一层 try/catch + 循环重连。
        // 理由：跟随逻辑一旦中途失效，粘底状态机就再也不推进、钉底/停车全部瘫痪，
        // 而且**没有任何可见报错**（排查时极难定位）。重连保证任何单次异常都不会让它永久失效。
        // 【逐帧循环，不用 snapshotFlow】2026-09-19：改用逐帧后有两个硬好处——
        // ① 每帧必有记录，任何"谁改了位置 / 谁在还原位置"都会现形（snapshotFlow 依赖
        //    "读到的状态变了才发"，实测它在拖拽期间一次都不发，把排查带偏过一轮）；
        // ② 不依赖状态变化的采样，就不会出现"effect 看起来死了"的假象。
        while (true) {
        try {
        androidx.compose.runtime.withFrameNanos { }
        run {
                // 外部挪动侦测（2026-10-11 排查「拖统计行上弹」）：上一帧收尾值 ≠ 本帧
                // 开头值，而上一帧既没钉底也没滚动会话 = 别的东西直接改了滚动位置
                // （嫌疑=文本选择边缘自动滚动）。进慢帧通道（低频、只在异常帧拼串）。
                // （MAXCHG 探针随 ScrollState.maxValue 一起退役：LazyColumn 无整体内容高。）
                if (FollowTrace.enabled) {
                    val vNow = listState.scrollPosProxy()
                    if (lastEndPos >= 0 && vNow != lastEndPos && !prevPinnedByUs && busyFrames == 0 &&
                        !listState.isScrollInProgress) {
                        FollowTrace.addSlow("JUMP $lastEndPos→$vNow")
                    }
                    lastEndPos = vNow
                }
                val delta = listState.distanceToBottomPx()
                val atBottom = delta <= bottomSlackPx
                // 粘滞标志：**仅**用于「回到底部」浮钮的显隐。连续两帧离开底部才置位，
                // 恢复贴底立即清除（单帧宽限，否则收尾/大重排时浮钮会闪一下）。
                // 【2026-09-19 合并进来】原先它由一个独立的 snapshotFlow effect 维护，
                // 而实测 snapshotFlow 会长时间一帧都不发 → userScrolledAway 恒为 false
                // → 浮钮永不出现（用户实测反馈 + 探针佐证：滚到顶部时 away 仍为 false）。
                val awayNow = !atBottom
                if (awayNow && !prevAtBottom) userScrolledAway.value = true
                else if (!awayNow) userScrolledAway.value = false
                busyFrames = if (listState.isScrollInProgress) busyFrames + 1 else 0
                // ── 粘底状态机（2026-10-12 三修，语义对齐 rikkahub ChatList）──────
                // 一修（按住让位）、二修（松手边沿停车+落点阈值）都在**猜手势边沿**：
                // 拖把手的路径有好几条（走滚动会话/被选择层消费/边缘自动滚动），落点
                // 方向也不唯一（把手向下=画面朝下走），边沿+方向判定永远漏一条带 =
                // 用户实测「弹跳区间缩小但没消失」。
                // 三修改看**位移归属**（rikkahub 不变量：跟随只对内容增高反应，从不
                // 回拉用户移动过的位置）：
                // · 停车：dV≠0 且上一帧不是我们钉的 = 位置被别人挪过（上/下/甩/
                //   把手边缘滚动，不分方向与手势类型）。我们自己的钉底由
                //   prevPinnedByUs 归因排除；内容变矮的夹回在新世界不再产生 dV
                //   （锚定语义下末项收缩不改变 firstVisible 锚点）。
                // · 撤停：delta ≤ 8px（rikkahub 同款严格触底）或显式动作（回底钮/
                //   发消息/新一轮/切会话，各 effect 清 parked）。不用 48dp 宽限撤停：
                //   把手松手常停离底 ~100px（宽限内），那样即撤停=流式增高帧立刻
                //   钉回=缺陷复活；拖把手落点永远 >8px，不会命中。
                // · 钉底：follow=!parked。间隙由**内容增高**产生（锚点不动）时 parked
                //   不置位 → footer 展开/版本切换/键盘顶起照常保持贴底；间隙由用户
                //   挪出 → parked=恒不钉，流式与否一视同仁（rikkahub 行为：上滑即
                //   停跟随，新内容转入徽标/浮钮，回到底部附近自动重新接管）。
                val vNow2 = listState.scrollPosProxy()
                val dV = if (prevFrameV >= 0) vNow2 - prevFrameV else 0L
                prevFrameV = vNow2
                if (dV != 0L && !prevPinnedByUs && !prevLiftedByUs) parkedFollow.value = true
                // 展开豁免窗口内不撤停：点展开置的 parkedFollow 若被本行「仍贴底」当场抹掉，
                // 窗口一过又吸底=锚定失效。窗口外撤停逻辑照常（用户滚回底部恢复跟随）。
                if (delta <= 8 && expandParkUntil.value <= System.currentTimeMillis())
                    parkedFollow.value = false
                // ── 回底浮钮 v3 方向驱动（2026-10-12 定稿）───────────────────
                // 只看**用户归因**位移（我方钉底帧由 prevPinnedByUs 排除，停车补偿帧
                // 由 prevLiftedByUs 排除——补偿是 IM 动画期逐帧代键盘挪位，不是用户在翻，
                // 误归因会刷闲置计时、令浮钮在键盘起落期闪现，语义同钉底豁免）。
                // dV>0=画面朝下走=用户在向下找新内容 → 若离底
                // 还超过 150dp 门槛就浮现；dV<0=向上翻=阅读意图 → 立即隐藏；
                // 已滚到底附近(delta≤门槛)一并收钮（贴底不需要回底入口）。
                // 闲置淡出：记下最近一次用户归因滚动帧（本地变量，**不逐帧写
                // State**——每帧写会让读取方每帧重组，主题页 17.34% jank 同款
                // 教训），钮亮着且 2.5 秒没有新归因位移即收。
                if (dV != 0L && !prevPinnedByUs && !prevLiftedByUs) {
                    lastUserScrollFrameMs = System.currentTimeMillis()
                    when {
                        delta <= jumperGapPx -> jumperVisible.value = false
                        dV > 0 -> jumperVisible.value = true
                        else /* dV < 0 */ -> jumperVisible.value = false
                    }
                } else if (jumperVisible.value &&
                    lastUserScrollFrameMs > 0 &&
                    System.currentTimeMillis() - lastUserScrollFrameMs > 2500
                ) {
                    jumperVisible.value = false
                }
                val follow = !parkedFollow.value
                // 展开锚点豁免（2026-10-13）：点展开后 750ms 窗口内跳过吸底——
                // 视口锚在被点条上、被点的条纹丝不动、新内容把下方正文/footer 往下推
                // =向下掀。虚拟化下同一语义由 LazyList 锚点天然达成：不钉底则
                // firstVisible 锚点不动、展开内容向下长。窗口外 follow 逻辑完全照常。
                val expandParked = expandParkUntil.value > System.currentTimeMillis()
                var pinned = false
                // fingerOnList/busyFrames 让位闸保留作belt：按住期间钉底会与拖选
                // 边缘自动滚动逐帧打架；此刻的位移下一帧也会被 dV 归因接住。
                if (busyFrames < 1 && follow && !fingerOnList.value && !expandParked)
                    pinned = listState.pinToBottom()
                // ── 停车态键盘补偿（2026-10-29 修「停车态呼出键盘内容不抬」）──────
                // 背景：键盘避让=输入栏 offset 上移 + 列表底部留白(bottomPadding)同步
                // 增大，"内容跟着抬"过去全靠粘底 pin 的副作用；停车（用户上滑回看）时
                // pin 让位，留白却照样变大→视口底被键盘吃掉多少就盖住多少气泡（用户报）。
                // 补法：停车时逐帧把「抬升增量」代为补滚——dispatchRawDelta 同步即时
                // （区别于 requestScrollToItem 的"下次重测生效"），与 IM 动画逐帧同步。
                // 方向：dispatchRawDelta(+d)=画面朝下走=内容相对上移，与 lift 变大同向。
                // 多退少补：键盘收起动画期反向回滚——LazyList 顶部锚定下若不还原，
                // 整屏内容会随留白缩小"往下沉"盖过输入栏，还原才与用户预期一致。
                // 让位闸同 pin：按住/滚动会话/展开豁免窗口不抢方向盘。
                // 边界保护：回滚只允许在"顶部尚有已组合内容可还原"（firstVisible 非
                // (0,0)）时执行，防止单页短会话反向滚穿顶部。
                val lift = keyboardLiftPxState.value
                var liftedByUs = false
                if (busyFrames < 1 && !follow && !fingerOnList.value && !expandParked) {
                    val need = lift - imeCompensatedPx
                    if (abs(need) >= 1f) {
                        // 回滚（need<0，键盘收起期）只允许在"顶部尚有已组合内容可还原"
                        // 时执行——单页短会话从 (0,0) 再回滚没有意义（也会被 LazyList
                        // 钳住），显式跳过少一次无谓重测。
                        val allowBack = need > 0f ||
                            listState.firstVisibleItemIndex > 0 ||
                            listState.firstVisibleItemScrollOffset > 0
                        if (allowBack) {
                            listState.dispatchRawDelta(need)
                            liftedByUs = true
                        }
                    }
                }
                // 账本无条件对齐 lift：跟随态=钉底代劳、让位态=用户手势期间键盘正在
                // 收起（clearFocus→lift→0），都不欠补偿；转停车帧从账本=当前 lift 起步，
                // 不会对用户刚定位的内容补出一记"跳"。
                imeCompensatedPx = lift.toFloat()
                // 诊断走**内存**（不落盘：滚动路径上做主线程 IO 会卡顿），
                // 需要时用 haoai://debug/followdump 一次性导出。
                // P0-2：这条字符串每渲染帧拼一条，探针关着时连拼都不拼
                if (FollowTrace.enabled) {
                    FollowTrace.add(
                        "busy=$busyFrames atBottom=$atBottom prev=$prevAtBottom delta=$delta " +
                            "follow=$follow pinned=$pinned finger=${fingerOnList.value} " +
                            "dV=$dV parked=${parkedFollow.value} " +
                            "pos=${listState.firstVisibleItemIndex}/${listState.firstVisibleItemScrollOffset}"
                    )
                }
                prevPinnedByUs = pinned
                prevLiftedByUs = liftedByUs
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

    // 拖动列表收键盘（2026-10-28 LazyColumn 重定位，替代旧 pointerInput 里的
    // clearFocus）：列表滚动手势开始时 interactionSource 发出 DragInteraction.Start——
    // 与旧「越过 slop 的真实拖拽即收键盘」同一时刻、同一手势来源。移动收键盘的
    // isConsumed 区分判据失效了（滚动现在是**子孙**节点消费，普通滚动与拖选把手
    // 在本观察者眼里都是已消费，无法区分），而 DragInteraction 天然只来自真实
    // 滚动手势：拖选把手的边缘自动滚动不走滚动会话、不发交互（f03bfa3 结论）→
    // 不误杀拖选；程序化贴底（requestScrollToItem）不是手势会话 → 不误收键盘。
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is androidx.compose.foundation.interaction.DragInteraction.Start) {
                focusManager.clearFocus()
            }
        }
    }


    // ── 慢帧探针（2026-09-18）────────────────────────────────────────
    // 目的：真机实测滚动中偶发一帧 400ms（另有 85ms×2），对照实验表明它**不是**某个区域的
    // 稳定属性，而是"某次首次组合"的一次性开销；需要"抓到那一帧"才能定位到具体行。
    // 做法：Choreographer 逐帧回调，帧间隔 >=50ms 时把"可见行区间 + 各行高度"写进内存轨迹
    // （FollowTrace.addSlow，零文件 IO，不在滚动路径上分配）。
    // 判读：卡顿帧的可见区间相对上一帧扩张的那一侧 = 新进入视口的行 = 首要嫌疑。
    // 虚拟化版（2026-10-28）：item 级 layoutInfo 回来了——直接记首末可见索引，
    // "新进入视口的行"判读法恢复（B′ 时代只能记 pos/max 的降级作废）。
    // P0-2：此探针是自续 120Hz Choreographer 回调 + 每帧两次 getRuntimeStat，常开即常烧；
    // 只在 followdump 深链打开 FollowTrace.enabled 后才启动（默认零成本）
    if (FollowTrace.enabled) {
    LaunchedEffect(listState) {
        val choreographer = android.view.Choreographer.getInstance()
        var prevFrame = 0L
        var prevGcCount = 0L
        var prevGcTime = 0L
        val cb = object : android.view.Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                val prev = prevFrame
                prevFrame = frameTimeNanos
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
                        val info = listState.layoutInfo
                        FollowTrace.addSlow(
                            "SLOWFRAME ${ms}ms gc=+${dGc}(${dGcTime}ms) " +
                                "items=[${info.visibleItemsInfo.firstOrNull()?.index}, " +
                                "${info.visibleItemsInfo.lastOrNull()?.index}]/${info.totalItemsCount}"
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

    // 入场动画用：初值 = 首帧就存在的 keys ⇒ 首屏/切会话时不会集体淡入；
    // 之后 add 成功才是"新行"。虚拟化下离屏行的 remember 状态会随 item 销毁，
    // 但 seenRowKeys 持有的是全量 key —— 回看旧行不重播淡入，行为同窗口化时代。
    val seenRowKeys = remember(sessionId) { rows.mapTo(HashSet<String>()) { it.key } }

    // 逐行的「画不画名字行 / 是否增强轮次边界」预计算（全量 rows；折叠隐藏中间行
    // 不改变这个判定——最终回复行前面是同组 assistant，本就不重复画头像）。
    // 全量索引后 rowIdx>0 即「会话里不是第一行」——窗口化时代用 hiddenRowCount>0
    // 给窗口首行补轮次分隔，现在自然由全量下标表达。
    val headFlags = remember(rows) {
        rows.mapIndexed { rowIdx, row ->
            val prevRole = rows.getOrNull(rowIdx - 1)?.role
            val showHead = prevRole != row.role
            val enhanced = showHead && row.role == "user" && rowIdx > 0
            row.key to (showHead to enhanced)
        }.toMap()
    }

    // 槽位→行区间映射（预解析用）：slotRowStarts[i] = 第 i 个槽位首行在 rows 里的下标；
    // 槽位行数 = 下一个槽位起点 - 本槽位起点（末槽到 rows 末尾）。
    val slotRowStarts = remember(slots) {
        IntArray(slots.size).also { arr ->
            var acc = 0
            for (i in slots.indices) {
                arr[i] = acc
                acc += when (val s = slots[i]) {
                    is RenderSlot.Plain -> 1
                    is RenderSlot.Group -> s.group.rows.size + (if (s.tailRow != null) 1 else 0)
                }
            }
        }
    }

    // 预解析（2026-10-28 虚拟化版；前身为 330301d 的「窗口外紧邻 16 行」）：
    // 视口外前后各 12 行提前在 Dispatchers.Default 解析进 MdAstCache——滚动使新行
    // 进入视口时，首次组合的主线程同步解析（缓存未命中路径）被消掉。
    // ChatViewModel（1e1a6f0）已持续预热最近 24 行，覆盖流式/切页返回；这里补
    // 「上下滚动翻历史」两个方向。collectLatest 保证快速甩动时旧的预解析被取消。
    val slotRowStartsState = androidx.compose.runtime.rememberUpdatedState(slotRowStarts)
    val rowsState = androidx.compose.runtime.rememberUpdatedState(rows)
    LaunchedEffect(listState) {
        androidx.compose.runtime.snapshotFlow {
            val info = listState.layoutInfo
            (info.visibleItemsInfo.firstOrNull()?.index ?: -1) to
                (info.visibleItemsInfo.lastOrNull()?.index ?: -1)
        }.collectLatest { (firstSlot, lastSlot) ->
            val starts = slotRowStartsState.value
            if (firstSlot < 0 || starts.isEmpty()) return@collectLatest
            val currentRows = rowsState.value
            val s0 = firstSlot.coerceIn(0, starts.lastIndex)
            val s1 = lastSlot.coerceIn(0, starts.lastIndex)
            val rowFirst = starts[s0]
            val rowLast = (if (s1 + 1 < starts.size) starts[s1 + 1] else currentRows.size) - 1
            for (i in (rowFirst - 12).coerceAtLeast(0) until rowFirst) {
                MdAstCache.ensure(currentRows[i].text)
            }
            for (i in rowLast + 1 until (rowLast + 13).coerceAtMost(currentRows.size)) {
                MdAstCache.ensure(currentRows[i].text)
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
            // 新一轮开始=用户要看新输出：撤停+重新起链（贴底请求在下一次重测生效，
            // 循环里 prevPinnedByUs 在下一帧看到我们钉过即接管）
            parkedFollow.value = false
            jumperVisible.value = false
            listState.requestPinToBottom(expectedItemCount)
        }
    }

    // ── LazyColumn（2026-10-28，B′ Column+verticalScroll 退役）──────────────
    // 滚动时首次测量巨型 item 的问题由虚拟化本身解决——只组合/测量可见 item，
    // 滚动=锚点位移；B′ 的手动窗口化与其全部派生物已删。
    // 入场动画：RowSlotItem 内部的 alpha Animatable 原样保留（首帧已存在的行不淡入、
    // 晋升行直出）——放在 item 内容里而不是用 animateItem，行为与窗口化时代一致。

    // 快捷操作按钮只挂回合最终回复：usage 字段只在整轮最终消息落值；
    // 兜底 = 非运行态的最后一条（覆盖无 usage 的错误收尾行），运行中不显示。
    // 「收尾」判定（2026-10-07 真机修复）只看 running 不够：中间叙述落行后
    // vm.running 可能已翻 false 而链卡/流式正文还在继续，最后落地的行会被
    // 误判成最终回复、挂上复制/重发/更多并一直留存——流式区还有产出时同样不算收尾
    // （这几个值在 LazyColumn 之前的组合体里算：LazyListScope 的 item lambda
    // 不是 @Composable 上下文，remember/rememberParkOnExpand 不能放里面。）
    val finalRowKey = if (!running && !showStreaming) rows.lastOrNull()?.key else null
    val growInKey = if (justFinished) finalRowKey else null
    // 轮级折叠（2026-10-13 定稿）：把全量 rows 预切成「普通行 / 过程组」槽位
    // （buildRenderSlots 见上方；itemsIndexed 逐槽渲染）。过程组 = 连续的过程行
    // （链卡 + 中间叙述）。组内是否收起由 turnExpanded 控制，默认收起；只有
    // 「已完成的一轮」（尾行=最终回复且非运行态）才享受折叠，正在进行的一轮强制
    // 全展开——尾行落库那一帧从「展开」翻成「收起」，AnimatedVisibility 把这一下
    // 高度差插值成收拢动画（=用户要的"终答到达才折叠"）。2026-10-14 修复：折叠只认
    // 终答**落行且回合结束**——曾经的"流式首字提前折叠"（taillessBar）已删，
    // 任务中段说话不再误触收起（用户真机实锤的抽搐）。
    // 轮次状态与会话绑定：remember(sessionId)，item 销毁重建（滚动出视口）不影响
    // 折叠态（map 存活于 MessageList 作用域）。
    val turnExpanded = remember(sessionId) {
        androidx.compose.runtime.mutableStateMapOf<String, Boolean>()
    }
    // 展开锚点（2026-10-13，效果图 fold-anchor-lab）：轮级条/链卡「由收→展」那一下置停车，
    // 视口锚在被点条上、内容向下掀；收起方向不调用（保持吸底=footer 原地）。
    // 虚拟化下同语义由 LazyList 锚点达成：不钉底则 firstVisible 锚点不动。
    val parkOnExpand = rememberParkOnExpand()

    androidx.compose.foundation.lazy.LazyColumn(
        state = listState,
        // contentPadding（2026-10-28 修「消息被挡在玻璃条外」）：留白属滚动**内容**
        // 而非容器边界——消息滚过贴顶/贴底时从玻璃条下方穿过（B′ 既有设计：
        // 「列表物理延伸到玻璃顶栏下方」）。Modifier.padding 会内缩容器边界，
        // 内容被裁在玻璃条外=用户看到的「不透明遮罩」。
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            top = topPadding, bottom = bottomPadding
        ),
        modifier = modifier
            .pointerInput(Unit) {
            // 只做一件事：手指按在列表上（越过 touchSlop 的真实拖动，含拖选把手）
            // 期间暂停贴底钉底。**不参与跟随判定**——跟随完全由位置决定（见 isAtBottom），
            // 避免"手指标志没复位 → 跟随永久失效"这类不可自愈的状态。
            // 虚拟化下的变化（2026-10-28）：滚动手势移到了列表内部（子孙节点），本
            // 观察者收到的 MOVE 恒为已消费——普通滚动被列表消费、拖选被选择层消费，
            // 旧「isConsumed 区分收键盘」的判据失效（祖先层无法区分两种消费源）。
            // 收键盘职责移交上方 interactionSource 的 DragInteraction 监听
            // （真实滚动手势才发交互；拖选边缘自动滚动不走滚动会话不误杀），
            // 此处只留 fingerOnList 守卫。
            awaitEachGesture {
                val start = awaitFirstDown(requireUnconsumed = false)
                val slop = 8.dp.toPx()
                try {
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == start.id } ?: break
                        if ((ch.position - start.position).getDistance() > slop) {
                            // 越过 slop=真实拖拽（普通滚动、拖选把手都算）：按住期间暂停
                            // 贴底钉框。拖选把手的边缘自动滚动不走滚动会话（isScrollInProgress
                            // 全程 false），原先两条让位闸都漏掉它，与 pinToBottom 逐帧打架。
                            fingerOnList.value = true
                        }
                        if (!ch.pressed) break
                    }
                } finally {
                    // 手势取消/事件丢失也必复位：宁可多跟一帧也不永久停手
                    fingerOnList.value = false
                }
            }
        }
    ) {
        itemsIndexed(slots, key = { _, slot -> slotItemKey(slot) }) { _, slot ->
            when (slot) {
                is RenderSlot.Plain -> RowSlotItem(
                    row = slot.row,
                    showHead = headFlags[slot.row.key]?.first ?: true,
                    enhanced = headFlags[slot.row.key]?.second ?: false,
                    finalRowKey = finalRowKey, growInKey = growInKey,
                    running = running, turnStartAt = turnStartAt,
                    seenRowKeys = seenRowKeys, promotedRowKeys = promotedRowKeys,
                    onFooterReveal = { footerRevealTick++ }, versionSwitch = versionSwitch,
                    onOpenMenu = onOpenMenu, onCopyRow = onCopyRow,
                    onQuickRegenerate = onQuickRegenerate, onQuickEdit = onQuickEdit,
                    onToolViewDiff = onToolViewDiff, onToolRollback = onToolRollback, onStopRun = onStopRun,
                )
                is RenderSlot.Group -> {
                    val group = slot.group
                    // 收尾判定：尾行=本轮最终回复（终答落行 + 回合结束才有尾行）。
                    // 未收尾（这一轮还在跑，或停在中间叙述/链尾）→ 组内行全展开、
                    // 不出现汇总条——「运行期间全程展开，任务中段说话也不收」。
                    // 收尾那一帧：同一 wrapper Column 里翻成「汇总条 + （默认）收起」，
                    // AnimatedVisibility 播「先融后收」exit=「最终回答落位后才折叠全过程」。
                    val groupFinished = slot.tailRow != null
                    // 汇总条出现 = 轮已收尾（尾行落行）。缺省折叠：已完成轮默认收成
                    // 汇总条（历史消息打开即"只看最终回复"）；无条的运行组强制展开。
                    val hasBar = groupFinished
                    val expanded = turnExpanded[group.headRow.key] ?: !hasBar
                    val showProcess = !hasBar || expanded
                    // 折叠动画 2026-10-13 方案B 定稿（效果图实验室拍板）：
                    // 旧法=if 瞬删内容 + 外层 animateContentSize 追高度——内容"唰"地消失只剩跳位。
                    // 新法=高度动画与内容渐隐**并行**：收起=fadeOut(180) 同时 shrinkVertically 延 120ms 起
                    // （先融后收）；展开=expandVertically 开门，逐条错峰上浮淡入（相隔 45ms，先展后现）。
                    // 外层 wrapper 不再挂 animateContentSize——高度动画归 AnimatedVisibility 自理，
                    // 双插值会互相追逐产生抖动感。
                    Column {
                        if (hasBar) {
                            TurnGroupBar(
                                group = group,
                                expanded = expanded,
                                onClick = {
                                    if (!expanded) parkOnExpand() // 收→展：锚定不吸底
                                    turnExpanded[group.headRow.key] = !expanded
                                }
                            )
                        }
                        val foldCurve = androidx.compose.animation.core.CubicBezierEasing(0.32f, 0.72f, 0.24f, 1f)
                        androidx.compose.animation.AnimatedVisibility(
                            visible = showProcess,
                            enter = androidx.compose.animation.expandVertically(
                                animationSpec = tween(300, easing = foldCurve)
                            ) + androidx.compose.animation.fadeIn(tween(200, easing = foldCurve)),
                            // 收起脱节修复（2026-10-13 真机反馈）：旧=fadeOut(180) 与 shrink(延120起460)
                            // 时序错开——内容 180ms 就淡没了，高度却要 580ms 才收完，中间 400ms 是
                            // "看不见的空白在慢慢收"，回复气泡在这段时间被顶上去 → 观感"瞬间消失+气泡脱节"。
                            // 改：淡出与收拢同步（去 delay、fade 拉满 shrink 时长），全程"边缩边淡没"一气呵成。
                            exit = androidx.compose.animation.fadeOut(tween(440, easing = foldCurve)) +
                                androidx.compose.animation.shrinkVertically(
                                    animationSpec = tween(440, easing = foldCurve)
                                )
                        ) {
                            // 真机抽搐模糊修复（2026-10-13）：AnimatedVisibility 的内容 lambda
                            // 包在内部 Box 里——多行组直接把 forEach 行当子节点=全部叠画在同一个
                            // 顶部位置（玻璃卡半透明，5 张叠出白雾重影），再叠加逐条错峰的中间
                            // 半透明态与 520ms 开门，观感=抽搐式模糊持续 1~2s。
                            // ① 补 Column 包裹令多行纵向排列（根治重叠）；
                            // ② 删逐条错峰上浮淡入——大内容多行时错峰与开门高度动画互相打架；
                            //    整组统一淡入（alpha 挂在容器一层，不与逐卡采样打架）；
                            // ③ 开门 520→300ms 压回"一气呵成"窗口。
                            Column {
                                group.rows.forEach { grow ->
                                    RowSlotItem(
                                        row = grow,
                                        // 有汇总条时头像由条统一画（修回归②）；组内行不再双画
                                        showHead = !(hasBar && group.headShow) && (headFlags[grow.key]?.first ?: true),
                                        // 展开回看组内行：过程行都是 assistant，showHead 本来就 false；
                                        // enhanced（虚线轮次分隔）只属于「我发言」方向，组内不会出现
                                        enhanced = headFlags[grow.key]?.second ?: false,
                                        finalRowKey = finalRowKey, growInKey = growInKey,
                                        running = running, turnStartAt = turnStartAt,
                                        seenRowKeys = seenRowKeys, promotedRowKeys = promotedRowKeys,
                                        onFooterReveal = { footerRevealTick++ }, versionSwitch = versionSwitch,
                                        onOpenMenu = onOpenMenu, onCopyRow = onCopyRow,
                                        onQuickRegenerate = onQuickRegenerate, onQuickEdit = onQuickEdit,
                                        onToolViewDiff = onToolViewDiff, onToolRollback = onToolRollback, onStopRun = onStopRun,
                                    )
                                }
                            }
                        }
                        // 尾行=最终回复：画在动画容器外（正文与 footer 永不参与折叠动画、直出）；
                        // 「链+文」合并形态（mergedAnswer）收起时只藏它的链卡（hideChain=!expanded），
                        // 链卡由 AssistantBlock 内的同款 AnimatedVisibility 播「先融后收」——
                        // 这是单行合并轮（真实引擎最常见形态）的收拢动画来源。
                        slot.tailRow?.let { tr ->
                            RowSlotItem(
                                row = tr,
                                hideChain = group.mergedAnswer != null && !expanded,
                                showHead = !(hasBar && group.headShow) && (headFlags[tr.key]?.first ?: true),
                                enhanced = headFlags[tr.key]?.second ?: false,
                                finalRowKey = finalRowKey, growInKey = growInKey,
                                running = running, turnStartAt = turnStartAt,
                                seenRowKeys = seenRowKeys, promotedRowKeys = promotedRowKeys,
                                onFooterReveal = { footerRevealTick++ }, versionSwitch = versionSwitch,
                                onOpenMenu = onOpenMenu, onCopyRow = onCopyRow,
                                onQuickRegenerate = onQuickRegenerate, onQuickEdit = onQuickEdit,
                                onToolViewDiff = onToolViewDiff, onToolRollback = onToolRollback, onStopRun = onStopRun,
                            )
                        }
                    }
                }
            }
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
                    onViewDiff = onToolViewDiff,
                    onStopSubagent = onStopSubagent,
                    // 流式气泡=Agent 正在说话：上一条已落行不是 assistant（本回合首条）才画名字行。
                    // 连接中（还没有思考/工具/正文任何产出）先不画——光秃秃的名字行挂在消息流里，
                    // 与底部的状态胶囊两头不着（真机反馈 2026-10-07）；等首段内容出现再一同现身。
                    showHead = rows.lastOrNull()?.role != "assistant" && (
                        !streamingText.isNullOrBlank() ||
                            !streamingReasoning.isNullOrBlank() ||
                            liveToolsSnapshot.isNotEmpty()
                        ),
                    // 流式区=「我发言 → Agent 回复」方向：问答衔接，恢复紧凑 1dp
                    headTopGap = false
                )
            }
        }
        // ask_user 提问卡：P1 复审起固定钉在输入框上方（ChatScreen 底栏），不再放消息流
        planProposal?.let { plan ->
            item(key = "plan-proposal") {
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
 * 距内容底部的像素距离（虚拟化版，2026-10-28 替代 B′ 的 ScrollState 减法）。
 * = 末项底边超出视口底部的量：流式增高 g 则为 g，已贴底/内容不足一屏为 0。
 * 与旧 `maxValue - value` 同义（都是"还有多少内容藏在视口底之下"），容差权衡不变：
 * 太小 → 流式一帧长高就被判成"离开底部"而停手；太大 → 用户小幅上滑被判"仍在底部"。
 *
 * 关键差异：LazyColumn 没有整体 maxValue，测距只能用 layoutInfo 的 item 几何——
 * 末项不在视口里（滚到中途）时几何上测不到真实距离，统一按"离底很远"处理：
 * 停车撤除/浮钮距离门槛都必须只在看得到末项时给出小值。
 * 未测量/空列表返回 0（视作贴底，与旧 `maxValue == Int.MAX_VALUE` 分支同义）。
 */
private fun androidx.compose.foundation.lazy.LazyListState.distanceToBottomPx(): Int {
    val info = layoutInfo
    val last = info.visibleItemsInfo.lastOrNull() ?: return 0
    if (last.index != info.totalItemsCount - 1) return Int.MAX_VALUE / 4
    val viewportContentEnd = info.viewportEndOffset - info.afterContentPadding
    return (last.offset + last.size - viewportContentEnd).coerceAtLeast(0)
}

/**
 * 是否贴着底（位置判定，仍是 rikkahub 语义：跟随只看位置、不用任何标志）。
 * 本列表的上下留白走 Modifier.padding（列表视口之外），afterContentPadding 恒为 0，
 * 判定即「末项底边距视口底 ≤ slack」。
 */
private fun androidx.compose.foundation.lazy.LazyListState.isAtBottom(slackPx: Int): Boolean =
    distanceToBottomPx() <= slackPx

/**
 * 滚动位置的单调代理（逐帧状态机的 dV 归因用）：
 * firstVisibleItemIndex * 1e6 + firstVisibleItemScrollOffset。行只在列表末尾增删、
 * 折叠只发生在末轮、版本切换不改 key → 锚点之上的 item 结构不变，该值与旧
 * ScrollState.value 同样随下滚单调递增（item 高度 < 1e6 px，跨项不回绕）。
 */
private fun androidx.compose.foundation.lazy.LazyListState.scrollPosProxy(): Long =
    firstVisibleItemIndex.toLong() * 1_000_000L + firstVisibleItemScrollOffset

/**
 * 贴底：一次性、非挂起；**无间隙时零成本返回**（不请求、不触发重测——
 * requestScrollToItem 的去重只看位置相等，无条件重复请求会导致每帧重测，
 * 逐帧循环必须走 gap 守卫，这是与 rikkahub「每次发射都请求」的刻意差异）。
 *
 * 实现 = `requestScrollToItem(末项索引, 大偏移)`：非挂起、在下一次重测生效
 * （rikkahub 同款机制）。大偏移是必须的——越界索引会被测量收敛成 (末项, 0)，
 * 末项高于视口时会**顶对齐**而非贴底（LazyListMeasure 的 scroll-back 只在
 * "向前组合不满视口"时触发）；带大偏移的请求经 scroll-back 数学收敛到
 * offset=末项高度-视口高，**严格底边对齐**，与末项高度无关（流式区/长回答
 * 超过一屏时同样成立，这是纯越界索引写法做不到的）。
 *
 * @return 是否发起了请求（供排查归因用）
 */
private fun androidx.compose.foundation.lazy.LazyListState.pinToBottom(): Boolean {
    if (distanceToBottomPx() <= 0) return false
    return requestPinToBottom()
}

/**
 * 无条件贴底请求（不做 gap 检查）：切会话/新消息/新一轮回底时 layoutInfo 还是
 * 上一次的测量，gap 守卫会拿旧数据误判「已贴底」而跳过；这类调用点用本帧组合
 * 已知的 item 数（[itemCountHint]，0=用已测量的 totalItemsCount）直接发起。
 * 贴底请求与同帧的内容变更（新行/切会话）在**同一次重测**里一起应用。
 */
private fun androidx.compose.foundation.lazy.LazyListState.requestPinToBottom(
    itemCountHint: Int = 0
): Boolean {
    val count = if (itemCountHint > 0) itemCountHint else layoutInfo.totalItemsCount
    if (count <= 0) return false
    requestScrollToItem(count - 1, PinBottomOverscrollPx)
    return true
}

/**
 * 贴底请求的越底偏移。测量期 scroll-back 的落点与该值无关（收敛到底边对齐），
 * 只需大于任何可能的前置 padding 与间隙，取 1M px 覆盖全部现实场景。
 */
private const val PinBottomOverscrollPx = 1_000_000

/**
 * LazyColumn 稳定 item key：普通槽=行 key，过程组槽=组首行 key
 * （首行已被组消费，不会同时以普通槽出现 → 全列表 key 唯一）。
 */
private fun slotItemKey(slot: RenderSlot): String = when (slot) {
    is RenderSlot.Plain -> slot.row.key
    is RenderSlot.Group -> slot.group.headRow.key
}

@Composable
private fun RowItem(
    row: ChatRow,
    onOpenMenu: (ChatRow) -> Unit,
    onCopyRow: (ChatRow) -> Unit,
    onQuickRegenerate: (ChatRow) -> Unit,
    onQuickEdit: (ChatRow) -> Unit,
    running: Boolean,
    /** 本轮起点：页脚硬闸只封本轮行（2026-10-11）。 */
    turnStartAt: Long = 0L,
    onViewDiff: (String) -> Unit,
    onRollback: (String) -> Unit = {},
    onStopRun: () -> Unit = {},
    showActions: Boolean = true,
    /** 社交化分组：本行是否画「头像+昵称」名字行（每组第一条=true）。 */
    showHead: Boolean = true,
    /** 名字行顶部的 6dp 轮次呼吸位（会话首条豁免=false）。 */
    headTopGap: Boolean = true,
    growIn: Boolean = false,
    /** 轮级折叠收起态：合并终答行只藏链卡（正文直出），见 AssistantBlock。 */
    hideChain: Boolean = false,
    onFooterReveal: () -> Unit = {}
) {
    // 引擎注入的系统事件（handoff 催办 / 压缩结果）不冒充聊天气泡，渲染为居中事件条
    if (row.role == "user" && row.text.startsWith("[系统提示]") ||
        row.role != "user" && row.text.startsWith("[系统]")
    ) {
        SystemEventBar(row.text)
        return
    }
    // 系统事件芯片（2026-10-22 中断控制重设计，用户拍板效果图 interrupt_redesign_mockup）：
    // 引擎/协调器注入的 [task-notification]/[系统恢复]/[成本熔断]/[圈数熔断]/交接文档
    // 必须按 role=user 落库（LLM 只读 user/assistant），但界面不画绿气泡冒充「我」——
    // 统一渲染成语义色小卡（绿完成/琥珀熔断/蓝恢复/灰信息），点开见原文。
    // 新增注入前缀务必进 classifySystemEvent + 单测，漏一个就是一起冒充事故。
    if (row.role == "user") {
        classifySystemEvent(row.text)?.let {
            SystemEventChip(row.text, it)
            return
        }
    }
    when (row.role) {
        "user" -> UserBubble(row, onOpenMenu, onCopyRow, onQuickEdit, running, showHead, headTopGap)
        else -> AssistantBlock(row, onOpenMenu, onCopyRow, onQuickRegenerate, running, turnStartAt, onViewDiff, onRollback, onStopRun, showActions, showHead, headTopGap, growIn, hideChain, onFooterReveal)
    }
}

/**
 * 轮次分隔虚线（用户定稿方案 B，2026-10-07）：只出现在「Agent 回复完毕 → 我发言」
 * 的边界，问答衔接方向不插。1.5dp 居中虚线（4/4dp 段），左右 24dp 留白，
 * 上下 9/8dp 呼吸位——视觉锚点明确又不抢戏。
 */
@Composable
private fun TurnDivider() {
    val lineColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.16f)
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 24.dp, top = 9.dp, bottom = 8.dp)
            .height(1.5.dp)
            .drawBehind {
                drawLine(
                    color = lineColor,
                    start = Offset(0f, size.height / 2f),
                    end = Offset(size.width, size.height / 2f),
                    strokeWidth = size.height,
                    pathEffect = PathEffect.dashPathEffect(
                        floatArrayOf(4.dp.toPx(), 4.dp.toPx()), 0f
                    )
                )
            }
    )
}

/**
 * 社交化「名字行」（效果图 C 定稿）：20dp 小头像 + 昵称，每组第一条消息上方显示。
 * 头像不再单独成列——气泡通栏，宽度零浪费。用户侧镜像右对齐。
 */
@Composable
private fun SenderHead(
    name: String,
    emoji: String,
    gradient: Int,
    imagePath: String?,
    right: Boolean,
    topGap: Boolean = true,
    customHex: String = ""
) {
    Row(
        // topGap=true：增强方向（Agent 回复完毕 → 我发言）的 6dp 呼吸位，配合 TurnDivider；
        // topGap=false：问答衔接方向/会话首条，恢复改动前的 1dp 紧凑间距
        Modifier.fillMaxWidth().padding(
            start = 2.dp, end = 2.dp,
            top = if (topGap) 6.dp else 1.dp, bottom = 2.dp
        ),
        horizontalArrangement = if (right) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 用户侧镜像：消息靠右，名字行也「名字左·头像右」，与 Agent（头像左）方向相反
        if (right) {
            Text(
                name,
                style = MaterialTheme.typography.labelSmall.copy(
                    platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false)
                ),
                color = wallpaperAdaptiveGray(),
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            Spacer(Modifier.size(6.dp))
            ProfileAvatar(
                emoji = emoji, gradientIndex = gradient, fallback = name, size = 20.dp,
                imagePath = imagePath, customHex = customHex
            )
        } else {
            ProfileAvatar(
                emoji = emoji, gradientIndex = gradient, fallback = name, size = 20.dp,
                imagePath = imagePath, customHex = customHex
            )
            Spacer(Modifier.size(6.dp))
            Text(
                name,
                // includeFontPadding=false：labelSmall 的行盒带传统字体下坠，按盒居中会让
                // 头像视觉上偏下（真机反馈 2026-10-07）；收紧到字形后盒中心≈字形中心
                style = MaterialTheme.typography.labelSmall.copy(
                    platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false)
                ),
                color = wallpaperAdaptiveGray(),
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }
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
    onAnswerFree: (String, String) -> Unit,
    /** 自由输入框聚焦变化：上抛给键盘避让（keyboardLiftPx 判定用）。 */
    onFreeInputFocus: (Boolean) -> Unit = {}
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
                            modifier = Modifier
                                .fillMaxWidth()
                                .onFocusChanged { onFreeInputFocus(it.isFocused) }
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
    onAnswerFree: (askId: String, questionIndex: Int, text: String) -> Unit,
    /** 自由输入框聚焦变化：上抛给键盘避让（keyboardLiftPx 判定用）。 */
    onFreeInputFocus: (Boolean) -> Unit = {}
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
                            modifier = Modifier
                                .fillMaxWidth()
                                .onFocusChanged { onFreeInputFocus(it.isFocused) }
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

/** [task-notification] 首行解析出的唤醒通报摘要（纯函数，单测锁定，勿改文案格式）。 */
internal data class WakeNoticeSummary(val total: Int, val done: Int, val failed: Int)

private val WAKE_NOTICE_HEAD_RE = Regex(
    "\\[task-notification\\] 有 (\\d+) 路后台子代理已结束（完成 (\\d+)、失败 (\\d+)）"
)

/** 从通知全文第一行解析路数/完成/失败；格式对不上返回 null（组件走保守默认）。 */
internal fun parseWakeNotice(text: String): WakeNoticeSummary? {
    val m = WAKE_NOTICE_HEAD_RE.find(text) ?: return null
    return WakeNoticeSummary(
        total = m.groupValues[1].toInt(),
        done = m.groupValues[2].toInt(),
        failed = m.groupValues[3].toInt()
    )
}

/**
 * 系统事件芯片的语义档位（2026-10-22 中断控制重设计，用户拍板效果图
 * interrupt_redesign_mockup）：沿用既有语义色相——绿=完成动作、琥珀=警示代价、
 * 蓝=中性信息。数据层注入文本必须按 role=user 落库（LLM 只读 user/assistant），
 * 界面按前缀识别渲染成芯片，不再画绿气泡冒充用户。
 */
internal enum class SysTone { WAKE, CAP, RESUME, INFO }

/** classifySystemEvent 结果：外观档位 + 收起态一行标题 + 右侧摘要指标（可空）。 */
internal data class SystemEventView(val tone: SysTone, val title: String, val metric: String?)

private val RESUME_STEP_RE = Regex("第\\s*(\\d+)\\s*[步轮]中断")
private val COST_CAP_RE = Regex("计费约\\s*(\\d+)\\s*tokens，达到上限（(\\d+)）")
private val CALL_CAP_RE = Regex("已执行\\s*(\\d+)\\s*次工具调用，达到上限（(\\d+)）")

/** 大数缩写进 metric：275860→"275.9k"，250000→"250k"，<1万原样。 */
internal fun fmtTokensK(raw: String): String {
    val n = raw.toLongOrNull() ?: return raw
    if (n < 10_000) return n.toString()
    val tenthK = (n + 50) / 100          // 0.1k 精度四舍五入
    return if (tenthK % 10 == 0L) "${tenthK / 10}k" else "${tenthK / 10.0}k"
}

/**
 * 引擎/协调器注入的用户角色文本 → 芯片外观（纯函数，单测锁定）。
 * null = 不是系统事件，走正常气泡。新增注入前缀必须同时进这里+单测，
 * 漏一个就是一起「冒充用户」事故（2026-10-22 用户点名家族性复发）。
 */
internal fun classifySystemEvent(text: String): SystemEventView? {
    // ① 后台子代理回传（完成计数沿用 parseWakeNotice，勾/叉由组件判）
    parseWakeNotice(text)?.let {
        return SystemEventView(
            SysTone.WAKE,
            "后台子代理已结束 · ${it.done}/${it.total} 完成" +
                (if (it.failed > 0) " · ${it.failed} 失败" else ""),
            null
        )
    }
    // ② 断点恢复引导（新文案「步」，旧历史「轮」同样识别）
    if (text.startsWith("[系统恢复]")) {
        val step = RESUME_STEP_RE.find(text)?.groupValues?.get(1)
        return SystemEventView(
            SysTone.RESUME,
            if (step != null) "恢复执行 · 继续第 $step 步的任务" else "恢复执行 · 继续中断的任务",
            null
        )
    }
    // ③ 成本熔断（琥珀：有代价发生）
    if (text.startsWith("[成本熔断]")) {
        val m = COST_CAP_RE.find(text)
        return SystemEventView(
            SysTone.CAP,
            "成本熔断 · 已指示 Agent 立即收尾",
            m?.let { "${fmtTokensK(it.groupValues[1])} / ${fmtTokensK(it.groupValues[2])}" }
        )
    }
    // ④ 圈数熔断（同琥珀）
    if (text.startsWith("[圈数熔断]")) {
        val m = CALL_CAP_RE.find(text)
        return SystemEventView(
            SysTone.CAP,
            "圈数熔断 · 工具调用达上限收尾",
            m?.let { "${it.groupValues[1]} / ${it.groupValues[2]} 次" }
        )
    }
    // ⑤ 压缩交接文档（EngineCompaction.compactHistory 落库的 Markdown 大文档）
    if (text.startsWith(com.haoai.agent.agent.engine.HANDOFF_MARKER)) {
        return SystemEventView(SysTone.INFO, "对话历史已压缩 · 生成交接文档", null)
    }
    return null
}

/** 芯片徽标 tint（背景 = tint 13% 透明）。WAKE 的叉态由组件另行走 error 色。 */
@Composable
private fun sysToneTint(tone: SysTone): androidx.compose.ui.graphics.Color = when (tone) {
    SysTone.WAKE -> MaterialTheme.colorScheme.primary
    SysTone.CAP -> androidx.compose.ui.graphics.Color(0xFFB45309)   // 琥珀深（语义色相定稿）
    SysTone.RESUME -> androidx.compose.ui.graphics.Color(0xFF3A6DF0) // 蓝=信息恢复
    SysTone.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
}

/**
 * 系统事件芯片（2026-10-22 中断控制重设计定稿）：把「不得不按 user 角色落库」的
 * 引擎注入文本（子代理回传/系统恢复/成本与圈数熔断/压缩交接）统一渲染成
 * 居中小卡——一行语义标题 + 右侧摘要指标，点开见原文。折叠语言照搬思考阅读区
 * 规格：expandFrom Top 向下掀开 + 文字晚 60ms 后进光 + 箭头旋转；
 * 内滚必挂 panelBoundaryClamp（边界钳制，不带走会话流）。
 */
@Composable
private fun SystemEventChip(text: String, view: SystemEventView) {
    val wakeFailed = view.tone == SysTone.WAKE && view.title.contains("失败")
    var expanded by rememberSaveable(text) { mutableStateOf(false) }
    val easeSpec = androidx.compose.animation.core.CubicBezierEasing(0.3f, 0.6f, 0.35f, 1f)
    val rot by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = androidx.compose.animation.core.tween(240, easing = easeSpec),
        label = "noticeChevron"
    )
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = chatCardAlpha()))
            .border(
                1.dp,
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                RoundedCornerShape(18.dp)
            )
    ) {
        // 头部行：点按开合（clickable 只挂头部，展开后点正文不误收）
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = if (expanded) 0.dp else 18.dp, bottomEnd = if (expanded) 0.dp else 18.dp))
                .clickable { expanded = !expanded }
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val tint = if (wakeFailed) MaterialTheme.colorScheme.error else sysToneTint(view.tone)
            val badgeIcon = when {
                wakeFailed -> Icons.Filled.Close
                view.tone == SysTone.WAKE -> Icons.Filled.Check
                view.tone == SysTone.CAP -> Icons.Filled.WarningAmber
                view.tone == SysTone.RESUME -> Icons.Filled.Refresh
                else -> HaoIcons.Archive
            }
            Box(
                Modifier
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(tint.copy(alpha = 0.13f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    badgeIcon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(11.dp)
                )
            }
            Text(
                view.title,
                style = androidx.compose.ui.text.TextStyle(
                    fontSize = 11.sp, lineHeight = 15.sp,
                    fontWeight = FontWeight.Bold,
                    platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false)
                ),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (view.metric != null) {
                Text(
                    view.metric,
                    style = androidx.compose.ui.text.TextStyle(
                        fontSize = 9.5.sp, lineHeight = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false)
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.62f),
                    maxLines = 1
                )
            }
            Text(
                if (expanded) "收起" else "详情",
                style = androidx.compose.ui.text.TextStyle(
                    fontSize = 9.5.sp, lineHeight = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false)
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
            Icon(
                Icons.Filled.ExpandMore,
                contentDescription = if (expanded) "收起通知" else "展开通知",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier
                    .size(14.dp)
                    .graphicsLayer { rotationX = rot }
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(
                animationSpec = androidx.compose.animation.core.tween(280, easing = easeSpec),
                expandFrom = Alignment.Top
            ),
            exit = shrinkVertically(
                animationSpec = androidx.compose.animation.core.tween(220, easing = easeSpec),
                shrinkTowards = Alignment.Top
            ) + fadeOut(androidx.compose.animation.core.tween(160))
        ) {
            // 文字「后进光」（同思考阅读区 2026-10-13 定稿）：入场首帧 0，
            // 等 60ms 再 200ms 淡入；退场 110ms 快淡。Animatable 而非
            // animateFloatAsState——后者首组合直取目标值播不出入场。
            val reveal = remember { androidx.compose.animation.core.Animatable(1f) }
            LaunchedEffect(expanded) {
                if (expanded) {
                    reveal.snapTo(0f)
                    kotlinx.coroutines.delay(60)
                    reveal.animateTo(1f, androidx.compose.animation.core.tween(200))
                } else {
                    reveal.animateTo(0f, androidx.compose.animation.core.tween(110))
                }
            }
            val scroll = rememberScrollState()
            Text(
                text,
                style = androidx.compose.ui.text.TextStyle(
                    fontSize = 11.sp, lineHeight = 16.sp,
                    platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false)
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 240.dp)
                    .panelBoundaryClamp(scroll)
                    .verticalScroll(scroll)
                    .padding(start = 12.dp, end = 12.dp, bottom = 10.dp)
                    .graphicsLayer { alpha = reveal.value }
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
        // 连接状态指示（2026-10-06 用户定稿）：呼吸灯→转圈。
        // 呼吸灯只有透明度变化，分不清「在等模型」还是「卡住了」；转圈持续传达"进行中"，
        // 且与链卡步骤行的 spinner 视觉语言统一（同为 SlowSpinner，30Hz 节流不新增逼帧源）。
        com.haoai.agent.ui.common.SlowSpinner(
            size = 10.dp,
            strokeWidth = 1.5.dp,
            color = MaterialTheme.colorScheme.primary
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
    onStopSubagent: (String) -> Unit = {},
    /** 社交化分组：流式区是否画「头像+昵称」名字行（本回合首条=true）。 */
    showHead: Boolean = true,
    /** 名字行顶部的 6dp 轮次呼吸位（会话首条豁免=false）。 */
    headTopGap: Boolean = true
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 2.dp)
    ) {
        if (showHead) LocalChatProfiles.current?.let { p ->
            SenderHead(p.agentName, p.agentEmoji, p.agentGradient, p.agentImage, right = false, topGap = headTopGap)
        }
        val hasContent = !streamingText.isNullOrBlank()
        // v8 思维链重构（参考 RikkaHub ChainOfThought，2026-09-14 定稿）：
        // 思考 + 工具混排进一张玻璃链卡（时间轴/流式折叠），正文气泡独立在卡外。
        // B 方案：流式期 liveTools 已由 landedCallIds 过滤掉"已落进合并行"的工具，
        // 这里只画"当前回合尚未落库"的思考+工具，与下方已落行不重复。
        val liveSteps = buildList<com.haoai.agent.ui.ChainStep> {
            // m2：直播段用固定 id —— 流式增长时 key 恒定，remember 不重建
            streamingReasoning?.takeIf { it.isNotBlank() }?.let { add(com.haoai.agent.ui.ChainStep.Think(it, thinkingMs, id = "live_reasoning")) }
            liveTools.forEach { add(com.haoai.agent.ui.ChainStep.Tool(it)) }
        }
        if (liveSteps.isNotEmpty()) {
            ChainCard(
                steps = liveSteps,
                reasoningLive = !hasContent,
                toolsLive = true,
                finished = false
            )
        }
        // 分组节奏：链卡与正文气泡之间 6dp 呼吸
        if (hasContent) {
            if (liveSteps.isNotEmpty()) Spacer(Modifier.size(6.dp))
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

// 批：气泡/卡片不透明度统一映射已抽至 BubbleOpacity.kt（chatBubbleAlphas / chatCardAlpha）。

// ── data URL 图片缩略图（composer 附件预览 / 用户已发气泡共用）──

/** 解码缓存：key=dataUrl 哈希。附件数据 ≤1024px 采样，单张位图 ~2-4MB，容量 6 张够用。 */
private val dataUrlBmpCache = android.util.LruCache<String, ImageBitmap>(6)

@Composable
private fun DataUrlThumb(
    dataUrl: String,
    modifier: Modifier = Modifier,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(10.dp),
    contentScale: ContentScale = ContentScale.Crop,
    onClick: (() -> Unit)? = null
) {
    // LRU key 用 dataUrl 本身（LruCache 接受任意长 String）；hashCode 的 32 位碰撞
    // 会从缓存取到别人的图并直接显示（静默错图），不值得省这点内存
    val key = remember(dataUrl) { dataUrl }
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
    // 角标已退役（2026-10-14 用户拍板「G 无角标」，此为 DataUrlThumb 漏网处，10-15 补齐）
    val clickMod = onClick?.let { m -> Modifier.clip(shape).clickable { m() } } ?: Modifier
    if (bmp != null) {
        Box(modifier.clip(shape)) {
            Image(
                bmp!!,
                contentDescription = if (onClick != null) "图片（点开放大）" else "图片",
                modifier = Modifier.fillMaxSize().then(clickMod),
                contentScale = contentScale
            )
        }
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
    running: Boolean,
    showHead: Boolean = true,
    headTopGap: Boolean = true
) {
    // 多图统一口径：新数据在 images，旧会话只有 imageData → 单元素回落
    val attachImages = row.images.ifEmpty { listOfNotNull(row.imageData?.takeIf { it.isNotBlank() }) }
    // 引擎给带图消息落的正文有"[图片N]"占位前缀（rebuildRows 已剥；此处双保险——
    // 剥空 = 纯图消息，气泡只剩图，露出空文字行会很难看）
    val displayText = row.text.replace(Regex("^(\\[图片\\d*\\]\\n?)+"), "")
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 2.dp),
        horizontalAlignment = Alignment.End
    ) {
        // 社交化名字行（效果图 C）：每组第一条右对齐显示「我的头像+昵称」
        if (showHead) LocalChatProfiles.current?.let { p ->
            SenderHead(p.myName, p.myEmoji, p.myGradient, p.myImage, right = true, topGap = headTopGap, customHex = p.myGradientCustom)
        }
        // ── D17 批3：排队态（v4 效果图定稿）──
        // 同一颗绿泡降存在感 + 白虚线圈示意"暂存未发" + 琥珀旗标 +
        // 图标动作行（✎ 编辑退回输入框 / ✕ 撤回 / ↑ 立即发送＝绿动作抢占）。
        // 判定＝本会话忙 && 该消息在队列：续跑出队的瞬间自动落回普通气泡
        // （"转正"由 rebuildRows 驱动，queued 标记消失），无需额外动画状态。
        val qa = LocalQueueActions.current
        if (running && row.queued != null && qa != null) {
            val q = row.queued
            // 旗标：Warn 语义色小胶囊（唯一一处排队文案，动作行不再重复）
            // A2：插话态旗标换 Info 蓝（"主动送进当前轮"），排队态维持 Warn 琥珀
            val qTone =
                if (q.interject) com.haoai.agent.ui.theme.HaoTone.Info
                else com.haoai.agent.ui.theme.HaoTone.Warn
            Text(
                when {
                    q.interject -> "插话中 · 下一轮生效"
                    row.queuePaused -> "排队中 · 已暂停"
                    q.localModel -> "本地模型 · 排队"
                    else -> "排队中 · 本轮后自动执行"
                },
                style = MaterialTheme.typography.labelSmall,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                color = com.haoai.agent.ui.theme.haoToneInk(qTone),
                modifier = Modifier
                    .padding(end = 2.dp, bottom = 4.dp)
                    .background(
                        com.haoai.agent.ui.theme.haoToneBadgeBg(qTone),
                        CircleShape
                    )
                    .padding(horizontal = 9.dp, vertical = 2.dp)
            )
            Surface(
                color = MaterialTheme.colorScheme.primary.copy(
                    alpha = (chatBubbleAlphas().first * 0.62f).coerceIn(0.25f, 0.9f)
                ),
                shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 5.dp),
                border = androidx.compose.foundation.BorderStroke(
                    1.5.dp, MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.55f)
                ),
                modifier = Modifier.widthIn(max = 320.dp)
            ) {
                Text(
                    displayText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.92f),
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp)
                )
            }
            Row(
                Modifier.padding(top = 3.dp, end = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                QuickActionButton(HaoIcons.Edit, "编辑（退回输入框）") { qa.edit(row.id) }
                QuickActionButton(HaoIcons.Close, "撤回") { qa.withdraw(row.id) }
                // A2：插话开关——只在还没标插话时显示（标了就是"已在送进的路上"，
                // 再点没意义；要撤销插话用撤回）。Send=送进当前轮，与 ↑=抢占起新轮区分。
                if (!q.interject) {
                    QuickActionButton(HaoIcons.Send, "插话（不停当前轮，下一轮生效）") { qa.interject(row.id) }
                }
                Spacer(Modifier.weight(1f))
                Icon(
                    HaoIcons.ArrowUp,
                    contentDescription = "立即发送（停当前轮优先发这条）",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable { qa.promote(row.id) }
                        .padding(6.dp)
                        .size(16.dp)
                )
            }
            return
        }
        // 快捷操作行（与 Agent 同款排版：复制·编辑重发靠左，时间戳在「更多」左）。
        // 操作行宽度=气泡宽度：按钮左缘与气泡左缘平齐（气泡右对齐，操作行跟着右对齐，
        // 不再甩到屏幕最左脱离气泡——真机反馈 2026-10-07）
        BubbleWithActions(
            bubble = {
                // 图文合一（2026-10-14 图片排版改版）：图与文字共用一颗气泡的外框圆角，
                // 一眼是"一条消息"而不是两块漂浮卡片。单图贴合原比例（Crop→Fit 会留灰边，
                // 这里按解码位图比例给高），多图 2 列网格；点击进全屏图集（附件在前 +
                // 正文 markdown 图片在后，可横滑）。
                val lb = com.haoai.agent.ui.common.LocalImageLightboxLauncher.current
                val gallery = buildList {
                    attachImages.forEach { add(it to "我的附件") }
                    Regex("!\\[[^\\]]*\\]\\(([^)\\s]+)\\)").findAll(row.text).forEach { m ->
                        add(m.groupValues[1] to "")
                    }
                }
                Surface(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = chatBubbleAlphas().first),
                    shape = if (attachImages.isEmpty()) RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 5.dp)
                    else if (displayText.isBlank()) RoundedCornerShape(18.dp)
                    else RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 5.dp),
                    modifier = Modifier.widthIn(max = 320.dp)
                ) {
                    Column {
                        if (attachImages.isNotEmpty()) {
                            if (attachImages.size == 1) {
                                com.haoai.agent.ui.common.HaoImageSingle(
                                    key = attachImages.first().hashCode().toString(),
                                    decode = { com.haoai.agent.ui.common.haoDecodeDataUrl(attachImages.first()) },
                                    onClick = { lb?.invoke(gallery, attachImages.first()) },
                                    modifier = Modifier.padding(3.dp)
                                )
                            } else {
                                com.haoai.agent.ui.common.HaoImageGrid(
                                    urls = attachImages,
                                    decodeOf = { url -> { com.haoai.agent.ui.common.haoDecodeDataUrl(url) } },
                                    onOpen = { list, cur -> lb?.invoke(list.map { it to "我的附件" }, cur) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(4.dp),
                                    corner = RoundedCornerShape(12.dp)
                                )
                            }
                        }
                        if (displayText.isNotBlank()) {
                            // 长按正文 = 系统文本选择，不再弹操作菜单
                            SelectionContainer {
                                Text(
                                    displayText,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp)
                                )
                            }
                        }
                    }
                }
            },
            actions = {
                Row(
                    Modifier.padding(top = 1.dp, end = 2.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    QuickActionButton(HaoIcons.Copy, "复制") { onCopyRow(row) }
                    QuickActionButton(HaoIcons.Edit, "编辑重发", enabled = !running) { onQuickEdit(row) }
                    Spacer(Modifier.weight(1f))
                    val tsText = fmtMsgTs(row.ts)
                    if (tsText.isNotEmpty()) {
                        Text(tsText, style = MaterialTheme.typography.labelSmall,
                            color = wallpaperAdaptiveGray(), modifier = Modifier.padding(end = 4.dp))
                    }
                    QuickActionButton(HaoIcons.More, "更多") { onOpenMenu(row) }
                }
            }
        )
    }
}

/**
 * 气泡 + 操作行同宽容器：操作行宽度取「气泡宽度与操作行最小宽度的较大者」，
 * 气泡在容器内右对齐。气泡 ≥ 操作行时按钮左缘与气泡左缘严格平齐；
 * 气泡极窄时操作行可超出气泡左缘（操作行自身内容宽度下限），仍紧贴气泡群组。
 */
@Composable
private fun BubbleWithActions(bubble: @Composable () -> Unit, actions: @Composable () -> Unit) {
    androidx.compose.ui.layout.Layout(content = { bubble(); actions() }) { m, c ->
        val b = m[0].measure(c)
        val aMin = m[1].minIntrinsicWidth(c.maxWidth)
        val w = maxOf(b.width, aMin)
        val a = m[1].measure(androidx.compose.ui.unit.Constraints(minWidth = w, maxWidth = w))
        layout(w, b.height + a.height) {
            b.place(w - b.width, 0)
            a.place(0, b.height)
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
    /** 本轮起点：页脚硬闸只封本轮行，历史行统计不受牵连（2026-10-11）。 */
    turnStartAt: Long = 0L,
    onViewDiff: (String) -> Unit,
    onRollback: (String) -> Unit = {},
    onStopRun: () -> Unit = {},
    showActions: Boolean = true,
    /** 社交化分组：本行是否画「头像+昵称」名字行。 */
    showHead: Boolean = true,
    /** 名字行顶部的 6dp 轮次呼吸位（会话首条豁免=false）。 */
    headTopGap: Boolean = true,
    growIn: Boolean = false,
    /** 轮级折叠收起态（修回归①）：「链+文」合并终答行只藏链卡，正文与 footer 直出。 */
    hideChain: Boolean = false,
    onFooterReveal: () -> Unit = {}
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 2.dp)
    ) {
        // 社交化名字行（效果图 C）：每组第一条左对齐显示「Agent 头像+昵称」
        if (showHead) LocalChatProfiles.current?.let { p ->
            SenderHead(p.agentName, p.agentEmoji, p.agentGradient, p.agentImage, right = false, topGap = headTopGap)
        }
        // v8 思维链重构：历史消息与流式同构——思考 + 工具混排进一张链卡（finished=true
        // → 默认折叠为控制条，点开回看全链，静态数据不回放动画）；正文气泡在卡外。
        // 折叠动画 2026-10-13 方案B：合并终答行的链卡收起不再瞬删——与多行组同一套
        // 「先融后收 / 先展后现」规格（正文与 footer 在Visibility外直出，不受影响）。
        if (row.chainSteps.isNotEmpty()) {
            val foldCurve = androidx.compose.animation.core.CubicBezierEasing(0.32f, 0.72f, 0.24f, 1f)
            AnimatedVisibility(
                visible = !hideChain,
                enter = expandVertically(tween(300, easing = foldCurve)) +
                    fadeIn(tween(200, easing = foldCurve)),
                exit = fadeOut(tween(440, easing = foldCurve)) +
                    shrinkVertically(tween(440, easing = foldCurve))
            ) {
                Column {
                    ChainCard(
                        steps = row.chainSteps,
                        reasoningLive = false,
                        toolsLive = false,
                        finished = true
                    )
                    Spacer(Modifier.size(6.dp))
                }
            }
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
        // FOOTER 诊断（FollowTrace，followdump 深链开）：真机反馈「回复过程中仍出现
        // 操作行」但模拟器复现干净——记录每次页脚转为可见时的 running/统计字段，
        // 拿地面真相定位走的是哪条可见路径
        // 硬闸（用户原话「回复过程中不用显示，只在最终回复下面显示」，2026-10-07）：
        // running=true 期间**本轮产出**的 Agent 页脚（正文落行前的按钮/统计闪现路径）
        // 一律隐藏，回合结束统一回归。
        // 二修（2026-10-11）：首版闸是全局 `!running`，把**历史轮**已定稿行的统计行也
        // 连坐藏掉（用户点名：发新消息后上一条回复的 Token/耗时消失）。行 ts 早于本轮
        // 起点=历史行，不受闸——历史行的页脚本来就该一直显示。
        val isCurrentTurnRow = turnStartAt == 0L || row.ts >= turnStartAt
        val footerVisible = footerShown && (!running || !isCurrentTurnRow) &&
            (showActions || row.completionTokens != null || row.durationMs != null)
        LaunchedEffect(footerVisible) {
            if (footerVisible && FollowTrace.enabled) {
                FollowTrace.addSlow(
                    "FOOTER_SHOW run=$running showA=$showActions " +
                        "pt=${row.promptTokens} ct=${row.completionTokens} dur=${row.durationMs} " +
                        "err=${row.error} tail=${row.text.takeLast(18)}"
                )
            }
        }
        AnimatedVisibility(
            visible = footerVisible,
            enter = expandVertically(tween(200)) + fadeIn(tween(200))
        ) {
            Column {
                if (showActions) {
                    Row(Modifier.fillMaxWidth().padding(start = 2.dp, end = 2.dp, top = 1.dp)) {
                        QuickActionButton(HaoIcons.Copy, "复制") { onCopyRow(row) }
                        QuickActionButton(HaoIcons.Refresh, "重新生成", enabled = !running) { onQuickRegenerate(row) }
                        Spacer(Modifier.weight(1f))
                        // 版本翻页器（效果图定稿）：多版时出现，靠右、永远在「更多」左边
                        VersionPager(row)
                        QuickActionButton(HaoIcons.More, "更多") { onOpenMenu(row) }
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
 * 版本翻页器（重新生成可回看，效果图定稿 2026-10-07）：仅当本行宿主 user 消息有历史版时出现。
 * ‹ 2/3 › —— 左箭头看旧版、右箭头看新版；当前版=regenIndex（-1=最新，显示 total+1/total+1）。
 * 引擎上下文永远用最新版，这里只切气泡显示文字（纯 UI 显示态）。
 */
@Composable
private fun VersionPager(row: ChatRow) {
    if (row.regenVersions.isEmpty()) return
    val switch = LocalSwitchVersion.current
    val total = row.regenVersions.size + 1   // +1：最新版（content 本身）
    val cur = if (row.regenIndex < 0) total else row.regenIndex + 1
    val tint = wallpaperAdaptiveGray(alpha = 0.75f)
    Row(verticalAlignment = Alignment.CenterVertically) {
        QuickActionButton(
            HaoIcons.Back, "上一版",
            enabled = cur > 1
        ) { switch(row.regenUserId, cur - 2) }
        Text(
            "$cur/$total",
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            modifier = Modifier.padding(horizontal = 1.dp)
        )
        QuickActionButton(
            HaoIcons.ChevronRight, "下一版",
            enabled = cur < total
        ) { switch(row.regenUserId, if (cur >= total - 1) -1 else cur) }
    }
}

/**
 * 轮级折叠（2026-10-13 用户拍板效果图定稿）：一轮回答 = 「过程段 + 最终回复」。
 * 过程段 = 用户消息之后、最终回复之前的连续 assistant 行（链卡行 + 中间叙述行）；
 * 折叠时整段收成一行汇总条「本轮过程 · N 步 · +X −Y」，点击原位展开回看；
 * 最终回复行与其 footer（操作行 + 统计行）永远直出、不受影响。
 * 折叠动画 = 容器 animateContentSize 500ms（行群 → 汇总条的高度变化被插值成收拢）。
 */
internal class ProcGroup(
    val headRow: ChatRow,
    val rows: List<ChatRow>,
    var steps: Int,
    var added: Int,
    var removed: Int,
    /** 本轮耗时（最终回复行已落 statistics 时给值；流式中为 null 不显示） */
    var durMs: Long? = null,
    /** 本组是「我发言 → Agent 回」的轮次首组：收起态需在汇总条上方画头像（修回归②）。 */
    val headShow: Boolean = false,
    /**
     * 合并终答行（修回归①）：真实引擎把「链 + 正文」落在同一行（flushChain：模型说话
     * 时把 pending 链随正文一起落行），旧判据要求终答行 chainSteps 为空——真实回合
     * 永不满足 → 不折叠（用户实测反馈 2026-10-13）。现在承认合并形态：收起时该行只
     * 藏链卡部分（hideChain 直通 AssistantBlock），正文照常直出。
     */
    val mergedAnswer: ChatRow? = null,
)

internal sealed interface RenderSlot {
    class Plain(val row: ChatRow) : RenderSlot
    class Group(val group: ProcGroup, val tailRow: ChatRow?) : RenderSlot
}

/**
 * 按「一轮」把行列表切成槽位：user 行/系统事件条=普通槽；
 * 连续 assistant 行（链卡 + 中间叙述）=一个过程组。
 * **仅当该组已收尾（本轮不再活跃，且组尾是一条"无链卡的纯文本行"=最终回答）
 * 才把尾行从过程里拆出来**——组=过程（可折叠），尾行=最终回复（永远直出）。
 * 活跃判定：running || showStreaming 时**窗口最末一组**还没跑完（最终回答还在
 * 流式区/尚未落行），不能拆尾、不能折叠——正是「运行期间全程展开」。
 * 组内 diff 增删数顺带累计（汇总条徽章用）。
 *
 * 折叠时机（2026-10-14 修复「任务中段说话误触发收起」）：只认**终答落行且回合结束**
 * （!active）。曾经的「taillessBar 提前折叠」（streamingText 非空就折）已删除——
 * 引擎把中间叙述与最终回复走同一条 onDelta 流式通道，流式期间 UI 无法区分二者
 * （工具调用在内容之后才到达），导致模型每次任务中段发言都误触收起、说完又展开
 * （用户真机实锤的抽搐）。终答行是唯一可确认的"最终回复"：引擎只给无工具调用的
 * 那一轮挂统计字段（ AssistantStats ），且回合收尾后 running 才翻假。
 */
internal fun buildRenderSlots(
    rows: List<ChatRow>,
    active: Boolean
): List<RenderSlot> {
    val out = ArrayList<RenderSlot>()
    var i = 0
    while (i < rows.size) {
        val r = rows[i]
        val isProc = r.role != "user" && !r.text.startsWith("[系统]") &&
            (r.chainSteps.isNotEmpty() || r.text.isNotBlank())
        if (!isProc) {
            out.add(RenderSlot.Plain(r)); i++
            continue
        }
        val run = ArrayList<ChatRow>()
        val startIdx = i
        while (i < rows.size) {
            val g = rows[i]
            val cont = g.role != "user" && !g.text.startsWith("[系统]") &&
                (g.chainSteps.isNotEmpty() || g.text.isNotBlank())
            if (!cont) break
            run.add(g); i++
        }
        val runAtEnd = i >= rows.size
        // 收尾=「不是活跃的末组」，且组尾一行有正文=最终回答（**允许带链**——
        // 真实引擎的 flushChain 把 pending 链随正文落同一行，旧判据 chainSteps.isEmpty()
        // 对真实回合永不成立 → 用户实测"跑完不折叠"，2026-10-13 修正）。
        // 活跃末组（还在跑的这一轮）不拆尾、不折叠——「运行期间全程展开」。
        val lastIdx = run.size - 1
        val lastIsAnswer = run[lastIdx].text.isNotBlank()
        val finished = lastIsAnswer && !(active && runAtEnd)
        // 轮次首组判据（修回归②）：组前一行是 user = 「我发言→Agent 回」的开头。
        // 折叠后带头像的过程行被藏 → 头像必须在汇总条上方补画，否则整轮丢头像。
        val prevRole = rows.getOrNull(startIdx - 1)?.role
        val headShow = prevRole == "user"
        if (!finished) {
            // 运行中 / 停在无正文的链尾：整组按过程直出、无汇总条（行为同折叠前）
            val group = procGroupOf(run, null, headShow, merged = null)
            out.add(RenderSlot.Group(group, null))
        } else if (run.none { it.chainSteps.isNotEmpty() }) {
            // 纯问答轮（没有任何思考/工具链）：没东西可折，逐行直出，
            // 避免普通一问一答也挂一条「本轮过程 · 0 步」的空汇总条
            run.forEach { out.add(RenderSlot.Plain(it)) }
        } else {
            // 收尾：tail=最终回答行（可能是「链+文」合并形态，mergedAnswer 标记之）。
            // proc=tail 之前的过程行；tail 永远直出正文，收起时合并链卡藏进汇总条。
            val tail = run[lastIdx]
            val proc = run.subList(0, lastIdx).toList()
            val merged = tail.takeIf { it.chainSteps.isNotEmpty() }
            out.add(RenderSlot.Group(procGroupOf(proc, tail, headShow, merged), tail))
        }
    }
    return out
}

private fun procGroupOf(
    proc: List<ChatRow>,
    tail: ChatRow?,
    headShow: Boolean = false,
    merged: ChatRow? = null,
): ProcGroup {
    // 单行合并形态 proc 为空：headRow 兜底用 tail（turnExpanded 的 key）
    val group = ProcGroup(headRow = proc.firstOrNull() ?: tail ?: error("empty group"),
        rows = proc, steps = 0, added = 0, removed = 0,
        headShow = headShow, mergedAnswer = merged)
    // 步数/±徽章：过程行 + 合并终答行自带的链（收起时那条链也进"本轮过程"）
    (proc + listOfNotNull(merged)).forEach { g ->
        group.steps += g.chainSteps.size
        g.chainSteps.forEach { st ->
            if (st is com.haoai.agent.ui.ChainStep.Tool) {
                st.tool.diff?.let { d -> group.added += d.added; group.removed += d.removed }
            }
        }
    }
    tail?.durationMs?.let { group.durMs = it }
    return group
}

/**
 * 渲染单条消息行（原 windowedRows.forEach 循环体原样搬入，行为不变：
 * key/重提供切版回调/入场淡入/晋升豁免/增强轮次边界 + RowItem）。
 */
@Composable
private fun RowSlotItem(
    row: ChatRow,
    showHead: Boolean,
    enhanced: Boolean,
    finalRowKey: String?,
    growInKey: String?,
    /** 收起态且本行是「链+文」合并终答行：只藏链卡、正文与 footer 照常直出（修回归①）。 */
    hideChain: Boolean = false,
    running: Boolean,
    turnStartAt: Long,
    seenRowKeys: MutableSet<String>,
    promotedRowKeys: Set<String>,
    onFooterReveal: () -> Unit,
    versionSwitch: (String, Int) -> Unit,
    onOpenMenu: (ChatRow) -> Unit,
    onCopyRow: (ChatRow) -> Unit,
    onQuickRegenerate: (ChatRow) -> Unit,
    onQuickEdit: (ChatRow) -> Unit,
    onToolViewDiff: (String) -> Unit,
    onToolRollback: (String) -> Unit,
    onStopRun: () -> Unit,
) {
    androidx.compose.runtime.key(row.key) {
        androidx.compose.runtime.CompositionLocalProvider(
            LocalSwitchVersion provides versionSwitch
        ) {
            // 入场动画（替代 LazyColumn animateItem，原注释口径不变）：
            // 只为"首帧之后新出现的行"做 220ms 淡入；晋升行（流式区让位给落行）直接不透明出现。
            val isNewRow = remember(row.key) { seenRowKeys.add(row.key) }
            val promoted = row.key in promotedRowKeys
            var fadingIn by remember(row.key) { mutableStateOf(isNewRow && !promoted) }
            val appearAlpha = remember(row.key) {
                androidx.compose.animation.core.Animatable(if (isNewRow) 0f else 1f)
            }
            LaunchedEffect(row.key) {
                if (isNewRow) {
                    appearAlpha.animateTo(1f, tween(220))
                    fadingIn = false
                }
            }
            Column(
                if (fadingIn) Modifier.graphicsLayer { alpha = appearAlpha.value } else Modifier
            ) {
                if (enhanced) TurnDivider()
                RowItem(
                    row,
                    onOpenMenu = onOpenMenu,
                    onCopyRow = onCopyRow,
                    onQuickRegenerate = onQuickRegenerate,
                    onQuickEdit = onQuickEdit,
                    running = running,
                    turnStartAt = turnStartAt,
                    onViewDiff = onToolViewDiff,
                    onRollback = onToolRollback,
                    onStopRun = onStopRun,
                    showActions = row.completionTokens != null || row.durationMs != null || row.key == finalRowKey,
                    showHead = showHead,
                    headTopGap = enhanced,
                    growIn = row.key == growInKey,
                    hideChain = hideChain,
                    onFooterReveal = onFooterReveal
                )
            }
        }
    }
}

/** 轮级折叠的汇总条：收起态=「本轮过程 · N 步 · +X −Y · 耗时 ⌄」整行可点。 */
@Composable
private fun TurnGroupBar(
    group: ProcGroup,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    // 水平内缩必须与消息流其他内容一致（AssistantBlock/StreamingItem 都是 14dp）——
    // 折叠态曾漏了这条：头像贴屏幕左缘、汇总条通栏，与正文卡片宽度不一致
    // （2026-10-13 用户实测反馈「太靠边、太长、左右间距不一致」）。
    // 14dp 内缩后：头像起点=16dp（与组内头像同线）、汇总条与正文卡同宽。
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 2.dp)) {
    // 轮级折叠收起态整轮丢失头像（修回归②，2026-10-13 用户实测）：带头像的过程行被
    // 藏进汇总条后，角色变化判据（prevRole!=role）找不到可画头像的行。轮次首组
    // （组前一行是 user）在汇总条上方补画一次「头像+昵称」，展开/收起都稳定存在。
    if (group.headShow) LocalChatProfiles.current?.let { p ->
        SenderHead(p.agentName, p.agentEmoji, p.agentGradient, p.agentImage, right = false, topGap = false)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(shape)
            .background(
                MaterialTheme.colorScheme.surface.copy(alpha = com.haoai.agent.ui.chat.chatCardAlpha())
            )
            .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), shape)
            .clickable(interactionSource = null, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 折叠动画 2026-10-13 方案B：箭头同枚旋转 180°（320ms 软曲线），不再「▤/▴」字符瞬切
        val chevRot by animateFloatAsState(
            targetValue = if (expanded) 180f else 0f,
            animationSpec = tween(320, easing = androidx.compose.animation.core.CubicBezierEasing(0.3f, 0.6f, 0.35f, 1f)),
            label = "turnBarChevron"
        )
        Icon(
            HaoIcons.ChevronDown,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .size(16.dp)
                .graphicsLayer { rotationX = chevRot }
        )
        Spacer(Modifier.size(8.dp))
        // 文案交叉淡化（旧字淡出 160ms · 新字淡入 200ms），不再硬切
        AnimatedContent(
            targetState = expanded,
            transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(160)) },
            label = "turnBarLabel"
        ) { exp ->
            Text(
                if (exp) "收起本轮过程" else "本轮过程 · ${group.steps} 步",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.weight(1f))
        // 右侧统计：收起态才亮，交叉淡化进出（展开时腾出的宽度由左侧文案吸收）
        AnimatedContent(
            targetState = expanded,
            transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(160)) },
            label = "turnBarMeta"
        ) { exp ->
            if (!exp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (group.added > 0 || group.removed > 0) {
                        Text(
                            "+${group.added} −${group.removed}",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold,
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        )
                    }
                    group.durMs?.takeIf { it > 0 }?.let { d ->
                        Spacer(Modifier.size(8.dp))
                        Text(
                            if (d >= 60_000) String.format(Locale.US, "%dm%02ds", d / 60_000, d / 1000 % 60)
                            else String.format(Locale.US, "%.1fs", d / 1000.0),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                        )
                    }
                }
            }
        }
    }
    }
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
    val hasStats = pt != null || ct != null || dur != null
    // 时间戳（效果图定稿）：并入统计行最右端；无统计时仍单独渲染这一行放时间戳
    val tsText = fmtMsgTs(row.ts)
    if (!hasStats && tsText.isEmpty()) return
    val tint = wallpaperAdaptiveGray()
    Row(
        Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 0.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        pt?.takeIf { it > 0 }?.let { NerdStat(Icons.Filled.ArrowUpward, fmtTokens(it), tint) }
        ct?.takeIf { it > 0 }?.let { NerdStat(Icons.Filled.ArrowDownward, fmtTokens(it), tint) }
        if (ct != null && ct > 0 && dur != null && dur > 0) {
            NerdStat(Icons.Filled.Bolt, String.format(Locale.US, "%.1f tok/s", ct / (dur / 1000.0)), tint)
        }
        dur?.takeIf { it > 0 }?.let { NerdStat(Icons.Filled.Schedule, String.format(Locale.US, "%.1fs", it / 1000.0), tint) }
        if (tsText.isNotEmpty()) {
            Spacer(Modifier.weight(1f))
            Text(tsText, style = MaterialTheme.typography.labelSmall, color = tint)
        }
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

/**
 * 顶部通知条场景分类（2026-10-14 玻璃化配套）：按 friendlyErrorText 的固定话术关键词
 * 选图标与色调——余额(⚠红)/密钥(线稿紫)/限流(沙漏琥珀)/断网(斜杠蓝)/兜底(⚠红)。
 * 与 friendlyErrorText 同源维护：改那边话术时这里跟着改。
 */
private fun errorScene(msg: String): Pair<androidx.compose.ui.graphics.vector.ImageVector, androidx.compose.ui.graphics.Color> = when {
    "充值" in msg || "话费用完" in msg ->
        HaoIcons.Alert to androidx.compose.ui.graphics.Color(0xFFC24A4A)
    "密钥" in msg || "验证失败" in msg ->
        HaoIcons.Key to androidx.compose.ui.graphics.Color(0xFF7C5CE0)
    "限流" in msg || "太忙" in msg ->
        HaoIcons.Hourglass to androidx.compose.ui.graphics.Color(0xFFE8930C)
    "连不上" in msg || "网络" in msg ->
        HaoIcons.WifiOff to androidx.compose.ui.graphics.Color(0xFF3D7BD9)
    else -> HaoIcons.Alert to androidx.compose.ui.graphics.Color(0xFFC24A4A)
}

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
                    if (expanded) HaoIcons.ChevronUp else HaoIcons.ChevronDown,
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
                                    // 方案A：低频自绘转圈（同 ThinkingHeader 处，防库动画逼帧）
                                    SlowSpinner(
                                        size = 11.dp,
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
                                            HaoIcons.Close,
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
    pendingImages: List<String>,
    onPickImage: () -> Unit,
    onTakePhoto: () -> Unit = {},
    onPickDocument: () -> Unit,
    onRemoveImage: (Int) -> Unit,
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
        // 输入框玻璃接统一调参（2026-10-08 定稿）：历史上不传 lensRadius 继承
        // radius=26dp、倍数默认 2 → 位移 52dp 全 App 最重，滚动内容穿过时被扭曲
        // 出"卡顿感"（用户实测）。现折射环带/倍数/色差与全 App 一致（默认 16×1），
        // 模糊单独一档（常年压在正文上，默认 4）
        lensRadius = com.haoai.agent.ui.theme.GlassTuning.lensHeight.dp,
        lensAmount = com.haoai.agent.ui.theme.GlassTuning.lensAmount.dp,
        depthEffect = com.haoai.agent.ui.theme.GlassTuning.depthEffect,
        blurRadius = com.haoai.agent.ui.theme.GlassTuning.inputBlur.dp,
        chromaticAberration = com.haoai.agent.ui.theme.GlassTuning.ca,
        surfaceAlpha = com.haoai.agent.ui.theme.haoInputSurfaceAlpha(),
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
            if (pendingImages.isNotEmpty()) {
                // 多图附件芯片行（2026-10-14）：横排缩略图，每张右上角单独移除；
                // 尾部「＋」继续添加；不足上限时也可点加号补图。
                Row(
                    Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
                ) {
                    pendingImages.forEachIndexed { idx, url ->
                        Box(Modifier.size(54.dp)) {
                            DataUrlThumb(
                                url,
                                Modifier.size(54.dp),
                                shape = RoundedCornerShape(13.dp)
                            )
                            // 移除角标（用户拍板 X1 磨砂玻璃圆点）：半透白底板+细白描边+
                            // 内高光线，压在图上看得到但不打架；× 用 HaoIcons 细线稿
                            Box(
                                Modifier
                                    .align(Alignment.TopEnd)
                                    .size(24.dp)
                                    .clip(CircleShape)
                                    .clickable { onRemoveImage(idx) },
                                contentAlignment = Alignment.Center
                            ) {
                                Box(
                                    Modifier
                                        .size(19.dp)
                                        .clip(CircleShape)
                                        .background(androidx.compose.ui.graphics.Color(0x8C2B3242))
                                        .border(
                                            1.dp,
                                            androidx.compose.ui.graphics.Color(0x73FFFFFF),
                                            CircleShape
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        com.haoai.agent.ui.common.HaoIcons.Close,
                                        contentDescription = "移除第${idx + 1}张",
                                        tint = androidx.compose.ui.graphics.Color.White,
                                        modifier = Modifier.size(11.dp)
                                    )
                                }
                            }
                        }
                    }
                    if (pendingImages.size < 9) {
                        // 继续添加（打开多选图库）
                        Box(
                            Modifier
                                .size(54.dp)
                                .clip(RoundedCornerShape(13.dp))
                                .background(androidx.compose.ui.graphics.Color(0x14FFFFFF))
                                .border(1.dp, androidx.compose.ui.graphics.Color(0x33FFFFFF), RoundedCornerShape(13.dp))
                                .clickable(onClick = onPickImage),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                HaoIcons.Add,
                                contentDescription = "继续添加图片",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                    Text(
                        "${pendingImages.size}/9",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (pendingAudio != null) {
                Row(
                    Modifier.padding(horizontal = 18.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        HaoIcons.Mic,
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
                            HaoIcons.Close,
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
                        HaoIcons.Video,
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
                            HaoIcons.Close,
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
                        if (toolbarExpanded) HaoIcons.ChevronDown else HaoIcons.Add,
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
                // 方案A（2026-10-16 用户拍板，修 D17 排队的"看得见够不着"）：运行中按钮跟着
                // 输入内容走——有文字/附件=发送键（点击由 VM 落进该会话队列，本轮结束自动
                // 接上；带附件运行中发送会被 VM 明确提示"排队只认纯文本"，不是静默吞）；
                // 输入框全空=停止键（旧行为）。此前运行中恒停止键，打字没有任何发送途径。
                val hasOutbox = text.isNotBlank() || pendingImages.isNotEmpty() || pendingAudio != null || pendingVideoName != null
                val showSend = !running || hasOutbox
                val actionable = hasOutbox || !showSend
                LiquidGlassButton(
                    onClick = { if (showSend) onSend() else onStop() },
                    backdrop = backdrop,
                    modifier = Modifier.size(46.dp),
                    surfaceColor = MaterialTheme.colorScheme.primary.copy(alpha = if (actionable) 0.85f else 0.25f)
                ) {
                    Icon(
                        if (showSend) HaoIcons.Send else HaoIcons.Stop,
                        contentDescription = when {
                            showSend && running -> "发送（排队，本轮结束后自动执行）"
                            showSend -> "发送"
                            else -> "停止"
                        },
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
                    ToolbarButton(icon = HaoIcons.Camera, label = "拍照", onClick = {
                        toolbarExpanded = false
                        onTakePhoto()
                    })
                    ToolbarButton(icon = HaoIcons.AttachImage, label = "图片", onClick = {
                        toolbarExpanded = false
                        onPickImage()
                    })
                    ToolbarButton(icon = HaoIcons.Mic, label = "音频", onClick = {
                        toolbarExpanded = false
                        onPickAudio()
                    })
                    ToolbarButton(icon = HaoIcons.Video, label = "视频", onClick = {
                        toolbarExpanded = false
                        onPickVideo()
                    })
                    ToolbarButton(icon = HaoIcons.Document, label = "文档", onClick = {
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
    runningIds: Set<String> = emptySet(),
    backdrop: com.kyant.backdrop.backdrops.LayerBackdrop,
    drawerOpen: Boolean,
    onOpenSessions: () -> Unit,
    onSelectSession: (String) -> Unit,
    onPinSession: (String) -> Unit,
    onRenameSession: (String, String) -> Unit,
    onDeleteSession: (String) -> Unit,
    onOpenWorkspace: () -> Unit,
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
                                // 标题 + 运行中指示（D17 批5：会话并行跑任务时列表也看得出来）
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        s.title,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                                        color = if (active) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onBackground,
                                        maxLines = 1,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    if (s.id in runningIds) {
                                        Spacer(Modifier.size(6.dp))
                                        val rot = androidx.compose.animation.core.rememberInfiniteTransition(s.id)
                                            .animateFloat(
                                                0f, 360f,
                                                animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                                                    tween(900)
                                                )
                                            ).value
                                        Icon(
                                            HaoIcons.Refresh,
                                            contentDescription = "进行中",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier
                                                .size(12.dp)
                                                .rotate(rot)
                                        )
                                    }
                                }
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
        // 工作区共享壳（方案 B 定稿）：目录/产出/历史三视图收进一个「工作区」入口
        DrawerEntry(Icons.Filled.Folder, "工作区", onClick = {
            if (openCardId != null) openCardId = null else onOpenWorkspace()
        })
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

// 档案头像 6 色渐变表（索引对应 SettingsStore.avatarGradient / myGradient）
internal val AVATAR_GRADIENTS = listOf(
    listOf(Color(0xFF7BC6A5), Color(0xFF4E9F7D)),
    listOf(Color(0xFF8FB8F0), Color(0xFF5B8DEF)),
    listOf(Color(0xFFF0B37E), Color(0xFFE88D5B)),
    listOf(Color(0xFFD79BE8), Color(0xFFB06BD4)),
    listOf(Color(0xFFF09BB0), Color(0xFFE56B8F)),
    listOf(Color(0xFF9CD8C8), Color(0xFF5FB0C9))
)

/** 档案头像：图片 > emoji > 名字首字，渐变底色兜底；[customHex] 非空时用色盘自定义纯色。 */
@Composable
internal fun ProfileAvatar(
    emoji: String,
    gradientIndex: Int,
    fallback: String,
    size: androidx.compose.ui.unit.Dp = 40.dp,
    imagePath: String? = null,
    customHex: String = ""
) {
    val colors = AVATAR_GRADIENTS[gradientIndex.coerceIn(0, AVATAR_GRADIENTS.lastIndex)]
    val custom = if (customHex.isNotBlank()) {
        com.haoai.agent.ui.theme.parseHexColor(customHex)
    } else {
        null
    }
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
            .background(
                custom?.let { androidx.compose.ui.graphics.SolidColor(it) }
                    ?: Brush.linearGradient(colors),
                CircleShape
            ),
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
            // emoji 位图字形约占 0.85em：0.72f → 视觉≈0.6 直径（0.45 真机实测偏小，
            // 2026-10-07）。includeFontPadding=false 必须同样加——否则 emoji 字体的
            // 传统顶边距把位图往下压（实测偏下 4px/52px 圆）
            Text(
                emoji,
                fontSize = (size.value * 0.72f).sp,
                style = androidx.compose.ui.text.TextStyle(
                    platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false)
                )
            )
        } else {
            // 首字兜底「几乎占满」。分档：方块类 CJK 字形宽高都接近 1em，圆内内接方
            // 上限≈0.71 直径；Flyme 字体的字形在行盒里比模拟器字体坐得低约 1px，
            // 0.78f 时「我」底钩已贴圆边（真机实测）→ 收到 0.73f 留出下缘余量。
            // 拉丁字母高度主体是大写帽(~0.7em)且窄，0.9f 恰好接近占满。
            // includeFontPadding=false 收紧行盒，Box 居中=字形居中
            val c = fallback.take(1).ifEmpty { "A" }.first()
            val fill = if (c.code > 0x2E7F) 0.73f else 0.9f   // 0x2E80 起 = CJK 部首/汉字区
            Text(
                c.toString(),
                fontSize = (size.value * fill).sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                // CJK 兜底：includeFontPadding=false 后行盒=[ascent,descent]（下衬比墨心占位
                // 厚），盒居中时「我」字墨心仍低 ~4.8% 直径（2026-10-13 截图逐像素实测
                // +2.33px/52dp 圆）→ 按直径比例上移补偿（0.062 含二测残差 +1.9px）；
                // 拉丁帽高近墨心，不补
                modifier = if (c.code > 0x2E7F) Modifier.offset(y = -size * 0.062f) else Modifier,
                style = androidx.compose.ui.text.TextStyle(
                    platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false)
                )
            )
        }
    }
}


/** 档案 emoji 候选（Agent/我的档案编辑共用，聊天社交化 2026-10-07）。
 * 18 个 = 6 列 3 整行（2026-10-13 用户反馈网格第三行只露 4 个，补 🐯🦄 收满）。 */
internal val PROFILE_EMOJIS = listOf(
    "😀", "😊", "😎", "🤖", "🐱", "🐶", "🦊", "🐰", "🌸", "🌟", "🔥", "🌙", "⚡", "🍀", "🎧", "🚀", "🐯", "🦄"
)

/**
 * 档案编辑共享字段（效果图③定稿）：名字 / 头像（52dp 预览+相册+emoji 网格+底色）/ 签名。
 * Agent「编辑档案」与设置「我的档案」两处弹窗复用，状态全部由调用方持有。
 * [onPickImage] 点「从相册选择」（调用方负责 launcher + 回写 imagePath）。
 * [showCustomColor] 开时底色行尾多一颗色盘入口（自定义纯色，与设置主题色共用轮盘）——
 * 仅「我的档案」有自定义槽；Agent 档案只有 6 预设渐变，不显示。
 * [onOpenColorWheel] 由调用方负责在根级挂弹层（LazyColumn item 内会被约束）。
 */
@Composable
internal fun ProfileEditFields(
    backdrop: com.kyant.backdrop.backdrops.LayerBackdrop,
    name: String, onName: (String) -> Unit,
    emoji: String, onEmoji: (String) -> Unit,
    gradient: Int, onGradient: (Int) -> Unit,
    bio: String, onBio: (String) -> Unit,
    imagePath: String?, onRemoveImage: () -> Unit,
    onPickImage: () -> Unit,
    nameLabel: String = "名字",
    customHex: String = "",
    onCustomHex: (String) -> Unit = {},
    showCustomColor: Boolean = false,
    onOpenColorWheel: (() -> Unit)? = null
) {
    CompactGlassField(
        value = name,
        onValueChange = { onName(it.take(20)) },
        label = nameLabel,
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
            emoji = emoji,
            gradientIndex = gradient,
            fallback = name,
            size = 52.dp,
            imagePath = imagePath
        )
        Spacer(Modifier.size(14.dp))
        com.haoai.agent.ui.common.LiquidGlassButton(
            onClick = onPickImage,
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
        if (imagePath != null) {
            com.haoai.agent.ui.common.GlassTextButton(text = "移除", onClick = onRemoveImage)
        }
    }
    PROFILE_EMOJIS.chunked(6).forEach { rowEmojis ->
        Row(
            Modifier.padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            rowEmojis.forEach { e ->
                val selected = e == emoji
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
                            // 选 emoji 即回到 emoji 头像，图片模式退出（与图片互斥）
                            onEmoji(if (selected) "" else e)
                            onRemoveImage()
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
            val selected = i == gradient && customHex.isBlank()
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
                    .clickable { onGradient(i) }
            )
        }
        // 自定义色（色盘单槽）：已挑=实色+主色描边，未挑=sweep 色轮+中心孔
        if (showCustomColor && onOpenColorWheel != null) {
            val customParsed =
                if (customHex.isNotBlank()) com.haoai.agent.ui.theme.parseHexColor(customHex) else null
            Box(
                Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(
                        customParsed?.let {
                            androidx.compose.ui.graphics.SolidColor(it)
                        } ?: Brush.sweepGradient(PickerHueColors)
                    )
                    .border(
                        if (customParsed != null) 2.dp else 1.dp,
                        if (customParsed != null) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.25f),
                        CircleShape
                    )
                    .clickable { onOpenColorWheel() },
                contentAlignment = Alignment.Center
            ) {
                if (customParsed == null) {
                    Box(
                        Modifier
                            .size(9.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.background)
                    )
                }
            }
        }
    }
    CompactGlassField(
        value = bio,
        onValueChange = { onBio(it.take(60)) },
        label = "签名（一句话介绍）",
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
    )
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
            // 2026-10-08：圆角/白雾/模糊全部接入全局调参（滑杆标着"影响：弹层"，
            // 这块面板一直没接，用户实测发现）。白雾按弹层锚点 0.92 等比映射——
            // 滑杆停默认值时仍是原来的 0.92 不透明（压在消息流上可读性优先），
            // 调低才有穿透变化（下限 0.552 防正文穿字）
            radius = com.haoai.agent.ui.theme.GlassTuning.corner.dp,
            surfaceAlpha = com.haoai.agent.ui.theme.GlassTuning.popupVeil(0.92f),
            blurRadius = com.haoai.agent.ui.theme.GlassTuning.blur.dp,
            // 统一玻璃配方（2026-10-08）：原不传 lens → 继承 radius24×默认倍数2=位移48
            // （比输入框原状还重），折射早已并入全局
            lensRadius = com.haoai.agent.ui.theme.GlassTuning.lensHeight.dp,
            lensAmount = com.haoai.agent.ui.theme.GlassTuning.lensAmount.dp,
            depthEffect = com.haoai.agent.ui.theme.GlassTuning.depthEffect,
            chromaticAberration = com.haoai.agent.ui.theme.GlassTuning.ca
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
                // 标题行：左「消息操作」，右元信息（时间 · 模型）——原面板底部的孤行上移
                Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 2.dp)) {
                    Text(
                        "消息操作",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f)
                    )
                    Spacer(Modifier.weight(1f))
                    val meta = buildString {
                        append(fmtMsgTs(row.ts))
                        row.model?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
                    }
                    Text(
                        meta,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f)
                    )
                }
                Spacer(Modifier.height(10.dp))
                // 宫格（效果图定稿 2026-10-07）：只放快捷行没有的动作——
                // 复制全文/重新生成/编辑重发已固定在气泡下方操作行，不再重复列。
                val cells = buildList {
                    add(Triple(HaoIcons.Quote, "引用", onQuote))
                    if (row.text.isNotBlank()) add(Triple(HaoIcons.Web, "网页预览", onPreview))
                    add(Triple(HaoIcons.Copy, "复制全文", { onCopy(row.text) }))
                    codeBlocks(row.text).firstOrNull()?.let { (lang, code) ->
                        val label = if (lang.isNotBlank()) "复制代码·$lang" else "复制代码"
                        add(Triple(HaoIcons.Code, label, { onCopy(code) }))
                    }
                    add(Triple(HaoIcons.Delete, "删除", onDelete))
                }
                cells.chunked(4).forEach { line ->
                    Row(Modifier.fillMaxWidth().height(74.dp)) {
                        line.forEach { (icon, label, click) ->
                            Box(Modifier.weight(1f).fillMaxHeight().padding(3.dp)) {
                                ActionCell(icon, label, click)
                            }
                        }
                        // 不足 4 格补空权重，保持每格等宽
                        repeat(4 - line.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

/** 面板宫格单元：图标上、文字下，整格可点。 */
@Composable
private fun ActionCell(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Column(
        Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.04f))
            .clickable { onClick() }
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(icon, contentDescription = null,
            tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.height(5.dp))
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
            maxLines = 1, overflow = TextOverflow.Ellipsis)
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
