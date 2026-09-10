package com.haoai.agent.ui.browser

import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.haoai.agent.agent.browser.BrowserController
import com.haoai.agent.ui.common.GlassPanel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import kotlin.math.roundToInt

/**
 * 内置浏览器悬浮预览（用户定稿 2026-09-10 方案一落地版）：两态、全应用内。
 *
 * ── 迷你态（Agent 浏览时默认）──
 * 右上 132dp 宽「真实缩微预览窗」：WebView 以小窗自身尺寸真实排版
 * （布局视口=容器 px，不再 1080px 全屏树缩放），页面按手机布局回流，
 * 比例天然正确，用户能直接看清 Agent 正在看的页面。可拖拽+位置记忆；
 * 点击放大到半屏。
 *
 * ── 半屏态 ──
 * 90% 高 Bottom Sheet（OpenMinis 结构）：标签标题行 + URL 行（复制）+
 * WebView（此时按半屏容器宽度重排版，真实可滑动观看）+ 链接条 +
 * 底部导航（后退/刷新/收起/全屏接管）。收起回迷你态。
 *
 * WebView 硬约束（BrowserController 文档）：factory 只建空容器，update 幂等
 * swap；两态容器互斥组合，WebView 一次只挂一处。
 * 关键机制：挂载时把 WebView 的布局视口设为容器实际 px（替代 detached
 * 全屏合成布局），页面按小屏回流——这是「比例一致」的根本解法；
 * 切换形态/离开面板时由 ensureLaidOut 恢复全屏合成布局供工具继续无头工作。
 */
@Composable
fun BrowserPreviewPanel(
    backdrop: com.kyant.backdrop.backdrops.LayerBackdrop,
    onClose: () -> Unit,
    onFullscreen: () -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val revision by BrowserController.revision.collectAsState()
    val active by BrowserController.activeIndex.collectAsState()

    fun copyAndToast(text: String, what: String) {
        if (text.isBlank()) return
        clipboard.setText(AnnotatedString(text))
        Toast.makeText(context, "已复制$what：${text.take(48)}", Toast.LENGTH_SHORT).show()
    }

    LaunchedEffect(Unit) { BrowserController.uiVisible = true }
    DisposableEffect(Unit) {
        onDispose {
            BrowserController.uiVisible = false
            // 先复位缩放，否则全屏浏览器/无头工具会继承缩小的画面
            BrowserController.resetPreviewScale()
            BrowserController.detachAll()
        }
    }

    BackHandler(onBack = onClose)

    var expanded by remember { mutableStateOf(false) }
    // 屏幕宽高比：迷你窗按整屏比例裁切，画面 = 整屏缩微（不回流、不变形）
    val screenAspect = remember {
        val (sw, sh) = BrowserController.screenSizePx()
        if (sw > 0 && sh > 0) sw.toFloat() / sh.toFloat() else 1080f / 2400f
    }

    val density = context.resources.displayMetrics.density
    // 安全区：顶部让开顶栏、底部让开输入框（用户要求窗口不得压在这两块上）
    val topInset = 96f * density
    val bottomInset = 104f * density

    // ── 迷你态位置/大小：位置归一化持久化，双指捏合改缩放 ──
    val prefs = remember { context.getSharedPreferences("float_windows", android.content.Context.MODE_PRIVATE) }
    var containerW by remember { mutableStateOf(0f) }
    var containerH by remember { mutableStateOf(0f) }
    var miniW by remember { mutableStateOf(0f) }
    var miniH by remember { mutableStateOf(0f) }
    var offX by remember { mutableStateOf(0f) }
    var offY by remember { mutableStateOf(0f) }
    // 双指捏合缩放（窗口尺寸等比变化，内容随之缩放）
    var zoom by remember { mutableFloatStateOf(prefs.getFloat("browser_zoom", 1f).coerceIn(MIN_ZOOM, MAX_ZOOM)) }
    var placed by remember { mutableStateOf(false) }

    /** 把当前位置夹在安全区内（容器尺寸/窗口尺寸变化后调用）。 */
    fun clampOffsets() {
        if (containerW <= 0f || containerH <= 0f || miniW <= 0f || miniH <= 0f) return
        val maxX = maxOf(0f, containerW - miniW)
        val maxY = maxOf(0f, containerH - bottomInset - miniH)
        offX = offX.coerceIn(0f, maxX)
        offY = offY.coerceIn(topInset.coerceAtMost(maxOf(0f, containerH - miniH)), maxY.coerceAtLeast(topInset))
    }

    fun place() {
        if (placed || containerW <= 0f || containerH <= 0f || miniW <= 0f || miniH <= 0f) return
        placed = true
        val nx = prefs.getFloat("browser_x", -1f)
        val ny = prefs.getFloat("browser_y", -1f)
        if (nx >= 0f && ny >= 0f) {
            offX = nx * containerW
            offY = ny * containerH
            clampOffsets()
        } else {
            offX = (containerW - miniW - 10f * density).coerceAtLeast(10f)
            offY = topInset
        }
    }

    fun onDrag(dx: Float, dy: Float) {
        if (containerW <= 0f || miniW <= 0f) return
        offX += dx
        offY += dy
        clampOffsets()
        prefs.edit()
            .putFloat("browser_x", if (containerW > 0) offX / containerW else 0f)
            .putFloat("browser_y", if (containerH > 0) offY / containerH else 0f)
            .apply()
    }

    /** 双指捏合：factor>1 放大窗口。窗口变大后重新夹取位置。 */
    fun onZoom(factor: Float) {
        if (factor <= 0f) return
        zoom = (zoom * factor).coerceIn(MIN_ZOOM, MAX_ZOOM)
        prefs.edit().putFloat("browser_zoom", zoom).apply()
    }

    val title = BrowserController.tabTitles().getOrNull(active).orEmpty()
    val url = BrowserController.activeUrl()

    // 缩略位图：预览打开期间按帧刷新（约 1s 一次，够看过程）
    var snapshot by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    LaunchedEffect(zoom) {
        // 缩放/移动结束后立刻补一帧清晰位图（拖动中位图被拉伸会发虚，补帧后恢复清晰）
        delay(220)
        if (!expanded) {
            snapshot = BrowserController.captureSnapshot(0.42f)?.asImageBitmap()
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            if (!expanded) {
                // 迷你态 WebView 未被挂载：保持全屏合成布局（Agent 侧导航/读取依赖），
                // 再抓一帧位图显示
                val wv = BrowserController.webViewAtSync()
                BrowserController.ensureLaidOutForPreview(wv)
                snapshot = BrowserController.captureSnapshot(0.42f)?.asImageBitmap()
            }
            delay(700)
        }
    }

    // 链接条：加载完成（revision 变化）后延时提取，给页面渲染留余量
    var linksJson by remember { mutableStateOf("[]") }
    LaunchedEffect(revision, active) {
        val u = BrowserController.activeUrl()
        if (u.isBlank() || u == "about:blank") {
            linksJson = "[]"
        } else {
            delay(900)
            runCatching { linksJson = BrowserController.collectLinks(10) }
        }
    }
    val links = remember(linksJson) {
        runCatching {
            val arr = JSONArray(linksJson)
            (0 until arr.length()).map { i ->
                arr.getJSONObject(i).let { it.optString("text") to it.optString("href") }
            }.filter { it.second.isNotBlank() }
        }.getOrDefault(emptyList())
    }

    // 半屏态入场缩放
    val sheetAnim by animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        animationSpec = tween(220),
        label = "sheetIn"
    )

    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned {
                containerW = it.size.width.toFloat()
                containerH = it.size.height.toFloat()
                if (!expanded) place()
            }
    ) {
        if (expanded) {
            // ── 半屏态：OpenMinis 式 90% Sheet，WebView 按半屏宽真实排版 ──
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .fillMaxHeight(0.9f)
                    .graphicsLayer {
                        alpha = sheetAnim
                        translationY = (1f - sheetAnim) * size.height * 0.2f
                    }
                    .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.97f))
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .imePadding()
            ) {
                // 标签标题行：拖拽区视觉 + 标题 ｜ 收起 ▼
                // 标准居中拖拽条（顶部分隔提示）
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp, bottom = 2.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        Modifier
                            .size(width = 34.dp, height = 4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f))
                    )
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        title.ifBlank { "内置浏览器" },
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { expanded = false }, modifier = Modifier.size(34.dp)) {
                        Icon(
                            Icons.Filled.KeyboardArrowDown,
                            "收起为迷你预览",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                // URL 行：地址（点按复制）｜ 后退 ⟳ ｜ 🌐 全屏接管 ｜ × 关闭
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                            .clickable { copyAndToast(url, "当前网址") }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            url.ifBlank { "about:blank" },
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                        )
                        Icon(
                            Icons.Filled.ContentCopy,
                            "复制网址",
                            modifier = Modifier
                                .padding(start = 5.dp)
                                .size(11.dp)
                                .alpha(0.5f)
                        )
                    }
                    IconButton(
                        onClick = { scope.launch { BrowserController.goBack() } },
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "后退", modifier = Modifier.size(18.dp))
                    }
                    IconButton(
                        onClick = { scope.launch { BrowserController.reload() } },
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(Icons.Filled.Refresh, "刷新", modifier = Modifier.size(17.dp))
                    }
                    IconButton(onClick = onFullscreen, modifier = Modifier.size(34.dp)) {
                        Icon(Icons.Filled.Public, "全屏接管", modifier = Modifier.size(17.dp))
                    }
                    IconButton(onClick = onClose, modifier = Modifier.size(34.dp)) {
                        Icon(Icons.Filled.Close, "关闭预览", modifier = Modifier.size(17.dp))
                    }
                }
                // 链接条：当前页可见链接（点击复制去向）
                if (links.isNotEmpty()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        links.forEach { (text, href) ->
                            Text(
                                text = "🔗 " + text.ifBlank { href.substringAfter("//").substringBefore('/') },
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                                    .clickable { copyAndToast(href, "链接") }
                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
                // WebView：整屏排版 + 等比收缩绘制（半屏时容器=屏宽 → 比例 1:1，
                // 与全屏浏览器画面完全一致；下方超出部分自然裁切）
                PreviewWebView(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(top = 6.dp)
                )
            }
        } else {
            // ── 迷你态：整屏缩微预览窗。交互（用户定稿 2026-09-11）：
            //    · 只有右上角「放大」按钮能展开半屏（点别处不再展开）
            //    · 窗口任意处可拖动移动位置（拖动由 interop 触摸层转发）
            //    · 双指捏合缩放窗口大小；位置/缩放均持久化
            //    · 位置夹在安全区内（不压顶栏、不压输入框）──
            val miniWidthDp = (MINI_BASE_DP * zoom).coerceAtMost(
                if (containerW > 0f) (containerW / density) - 16f else MINI_BASE_DP * MAX_ZOOM
            )
            Column(
                Modifier
                    .onGloballyPositioned {
                        miniW = it.size.width.toFloat()
                        miniH = it.size.height.toFloat()
                        if (!placed) place() else clampOffsets()
                    }
                    .width(miniWidthDp.dp)
                    .offset { IntOffset(offX.roundToInt(), offY.roundToInt()) }
                    .shadowOrCreate()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.97f))
            ) {
                // 标题栏：拖动移动窗口 + 右侧「放大」按钮（唯一展开入口）
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                        .padding(start = 8.dp, end = 2.dp, top = 2.dp, bottom = 2.dp)
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { },
                                onDrag = { change, drag ->
                                    change.consume()
                                    onDrag(drag.x, drag.y)
                                }
                            )
                        },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        title.ifBlank { url.substringAfter("//").take(14).ifBlank { "网页" } },
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = { expanded = true },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            Icons.Filled.OpenInFull,
                            "放大预览",
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }
                // 位图缩略画面（纯 Compose）：手势可靠——单指拖动移动窗口、
                // 双指捏合缩放窗口；点按不触发放大（只有标题栏按钮能放大）
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(screenAspect)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoomChange, _ ->
                                // 双指捏合期间不叠加平移：否则缩放时窗口会跟着手指乱飘，
                                // 缩放手感很"生硬"（用户反馈）。单指时才移动窗口。
                                if (zoomChange == 1f) {
                                    onDrag(pan.x, pan.y)
                                } else {
                                    onZoom(zoomChange)
                                }
                            }
                        }
                ) {
                    snapshot?.let { bmp ->
                        androidx.compose.foundation.Image(
                            bitmap = bmp,
                            contentDescription = "网页缩略预览",
                            contentScale = androidx.compose.ui.layout.ContentScale.FillBounds,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }
    }
}

/** 阴影修饰（独立小函数避免与玻璃面板样式冲突）。 */
private fun Modifier.shadowOrCreate(): Modifier = this

/** 迷你窗基础宽度与缩放范围（双指捏合）。 */
private const val MINI_BASE_DP = 132f
private const val MIN_ZOOM = 0.7f
private const val MAX_ZOOM = 2.4f

/** 双指间距（捏合缩放用）。 */
private fun pointerSpan(ev: android.view.MotionEvent): Float {
    if (ev.pointerCount < 2) return 0f
    return kotlin.math.hypot(ev.getX(0) - ev.getX(1), ev.getY(0) - ev.getY(1))
}

/**
 * 预览 WebView（迷你/半屏共用）：**整屏排版 + 等比收缩绘制**。
 *
 * 做法：容器内放一个固定为「屏幕物理尺寸」的 WebView（与全屏浏览器、无头
 * 工具完全同一套布局，页面不做任何回流），再按 `容器宽 ÷ 屏宽` 设置
 * scaleX/scaleY（pivot 左上角）——画面就是 Agent 操作的浏览器整屏画面按
 * 比例缩小，比例天然正确。
 *
 * 触摸：Compose 覆盖层盖在 AndroidView 上收不到触摸（interop 视图会先吃掉
 * 事件），因此**触摸全部交给 interop 层内的透明触摸层**处理：
 * 单指拖动 → onDragBy（移动窗口）；双指捏合 → onZoomBy（缩放窗口）；
 * 未传回调时只消费（半屏态「看而不点」）。点击不在此处理——放大只走标题栏
 * 的按钮（用户定稿），避免误触。
 */
@Composable
private fun PreviewWebView(
    modifier: Modifier = Modifier,
    onDragBy: ((Float, Float) -> Unit)? = null,
    onZoomBy: ((Float) -> Unit)? = null
) {
    var cw by remember { mutableFloatStateOf(0f) }
    Box(
        modifier
            .onGloballyPositioned { cw = it.size.width.toFloat() }
    ) {
        AndroidView(
            factory = { ctx ->
                android.widget.FrameLayout(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    // 子视图（整屏尺寸的 WebView）溢出部分裁掉，避免画到窗外
                    clipChildren = true
                    clipToPadding = true
                    // 触摸层：interop 层内的真实 Android View —— 手势（单指拖动移动
                    // 窗口 / 双指捏合缩放）在这一层处理。WebView 收不到触摸（看而不点）。
                    val touchLayer = android.view.View(ctx).apply {
                        layoutParams = android.widget.FrameLayout.LayoutParams(
                            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                            android.widget.FrameLayout.LayoutParams.MATCH_PARENT
                        )
                        isClickable = true
                        setBackgroundColor(android.graphics.Color.TRANSPARENT)
                        var lastX = 0f
                        var lastY = 0f
                        var lastSpan = 0f
                        setOnTouchListener { _, ev ->
                            when (ev.actionMasked) {
                                android.view.MotionEvent.ACTION_DOWN -> {
                                    lastX = ev.rawX
                                    lastY = ev.rawY
                                    lastSpan = 0f
                                }
                                android.view.MotionEvent.ACTION_POINTER_DOWN ->
                                    lastSpan = pointerSpan(ev)
                                android.view.MotionEvent.ACTION_MOVE -> {
                                    if (ev.pointerCount >= 2) {
                                        val span = pointerSpan(ev)
                                        if (lastSpan > 0f && span > 0f) {
                                            onZoomBy?.invoke(span / lastSpan)
                                        }
                                        lastSpan = span
                                    } else {
                                        onDragBy?.invoke(ev.rawX - lastX, ev.rawY - lastY)
                                        lastX = ev.rawX
                                        lastY = ev.rawY
                                    }
                                }
                                android.view.MotionEvent.ACTION_POINTER_UP -> lastSpan = 0f
                            }
                            true
                        }
                    }
                    addView(touchLayer)
                }
            },
            update = { container ->
                val wv = BrowserController.webViewAtSync()
                val (sw, sh) = BrowserController.screenSizePx()
                if (container.childCount != 2 || container.getChildAt(1) !== wv) {
                    (wv.parent as? ViewGroup)?.removeView(wv)
                    while (container.childCount > 1) container.removeViewAt(container.childCount - 1)
                    container.addView(wv)
                }
                // 固定为整屏尺寸：容器更小 → 溢出被裁，靠 scale 缩小显示
                if (sw > 0 && sh > 0) {
                    val lp = wv.layoutParams
                    if (lp == null || lp.width != sw || lp.height != sh) {
                        wv.layoutParams = android.widget.FrameLayout.LayoutParams(sw, sh)
                    }
                }
                // 保证触摸层始终在最上层（后加入的 WebView 会盖住它）
                container.getChildAt(0)?.bringToFront()
            },
            modifier = Modifier.fillMaxSize()
        )
        // 尺寸确定后按「容器宽 ÷ 屏宽」等比收缩（整屏缩微，不是回流）
        LaunchedEffect(cw) {
            if (cw > 0f) {
                val wv = BrowserController.webViewAtSync()
                val (sw, _) = BrowserController.screenSizePx()
                if (sw > 0) BrowserController.applyPreviewScale(wv, cw / sw.toFloat())
            }
        }
    }
}
