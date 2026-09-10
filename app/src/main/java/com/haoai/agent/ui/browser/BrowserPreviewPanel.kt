package com.haoai.agent.ui.browser

import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
            BrowserController.detachAll()
        }
    }

    BackHandler(onBack = onClose)

    var expanded by remember { mutableStateOf(false) }

    // ── 迷你态位置：归一化持久化（拖动落盘）──
    val prefs = remember { context.getSharedPreferences("float_windows", android.content.Context.MODE_PRIVATE) }
    var containerW by remember { mutableStateOf(0f) }
    var containerH by remember { mutableStateOf(0f) }
    var miniW by remember { mutableStateOf(0f) }
    var miniH by remember { mutableStateOf(0f) }
    var offX by remember { mutableStateOf(0f) }
    var offY by remember { mutableStateOf(0f) }

    fun place() {
        if (containerW <= 0f || containerH <= 0f || miniW <= 0f || miniH <= 0f) return
        val nx = prefs.getFloat("browser_x", -1f)
        val ny = prefs.getFloat("browser_y", -1f)
        if (nx >= 0f && ny >= 0f) {
            offX = (nx * containerW).coerceIn(0f, maxOf(0f, containerW - miniW))
            offY = (ny * containerH).coerceIn(0f, maxOf(0f, containerH - miniH))
        } else {
            val d = context.resources.displayMetrics.density
            offX = (containerW - miniW - 10f * d).coerceAtLeast(10f)
            offY = 96f * d
        }
    }

    fun onDrag(dx: Float, dy: Float) {
        if (containerW <= 0f || miniW <= 0f) return
        offX = (offX + dx).coerceIn(0f, maxOf(0f, containerW - miniW))
        offY = (offY + dy).coerceIn(0f, maxOf(0f, containerH - miniH))
        prefs.edit()
            .putFloat("browser_x", if (containerW > 0) offX / containerW else 0f)
            .putFloat("browser_y", if (containerH > 0) offY / containerH else 0f)
            .apply()
    }

    val title = BrowserController.tabTitles().getOrNull(active).orEmpty()
    val url = BrowserController.activeUrl()

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
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier
                            .size(width = 32.dp, height = 4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f))
                    )
                    Spacer(Modifier.size(8.dp))
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
                // WebView：按半屏宽真实排版（重排视口），页面与全屏浏览器同构
                RealScaleWebView(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(top = 6.dp)
                )
            }
        } else {
            // ── 迷你态：132dp 真实缩微预览（WebView 按小窗宽排版，比例=手机）──
            Column(
                Modifier
                    .onGloballyPositioned {
                        miniW = it.size.width.toFloat()
                        miniH = it.size.height.toFloat()
                        place()
                    }
                    .width(132.dp)
                    .offset { IntOffset(offX.roundToInt(), offY.roundToInt()) }
                    .shadowOrCreate()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.97f))
                    .clickable { expanded = true }
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                        .padding(horizontal = 6.dp, vertical = 3.dp)
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
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Icon(
                        Icons.Filled.OpenInFull,
                        "放大预览",
                        modifier = Modifier
                            .padding(start = 3.dp)
                            .size(10.dp)
                            .alpha(0.6f)
                    )
                }
                RealScaleWebView(
                    onTap = { expanded = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(178.dp)
                )
            }
        }
    }
}

/** 阴影修饰（独立小函数避免与玻璃面板样式冲突）。 */
private fun Modifier.shadowOrCreate(): Modifier = this

/**
 * 真实排版 WebView：挂载时把 WebView 布局视口设为「容器实际 px」（替代
 * detached 全屏合成布局），页面按容器宽度回流——比例与真机一致；
 * 容器尺寸变化（迷你⇄半屏切换）时重设视口并触发重排版。
 *
 * 触摸策略：Agent 是页面操作者，用户看而不点——遮罩层消费所有指针事件，
 * 防止误触改变页面状态；onTap 非空时遮罩改为 clickable（点窗即放大到半屏），
 * 保证整窗任意位置都能点开（此前只有标题栏可点，用户反馈难点开）。
 */
@Composable
private fun RealScaleWebView(
    modifier: Modifier = Modifier,
    onTap: (() -> Unit)? = null
) {
    var viewportW by remember { mutableFloatStateOf(0f) }
    var viewportH by remember { mutableFloatStateOf(0f) }
    Box(
        modifier
            .onGloballyPositioned {
                val w = it.size.width.toFloat()
                val h = it.size.height.toFloat()
                if (w != viewportW || h != viewportH) {
                    viewportW = w
                    viewportH = h
                }
            }
    ) {
        AndroidView(
            factory = { ctx ->
                android.widget.FrameLayout(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    clipChildren = true
                }
            },
            update = { container ->
                val wv = BrowserController.webViewAtSync()
                if (container.childCount == 1 && container.getChildAt(0) === wv) return@AndroidView
                (wv.parent as? ViewGroup)?.removeView(wv)
                container.removeAllViews()
                wv.layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                )
                container.addView(wv)
            },
            modifier = Modifier.fillMaxSize()
        )
        // 视口重排：容器尺寸确定后把 WebView measure/layout 到容器 px。
        // 手动布局后 WebView 按小宽度回流（mobile 视口），内容完整可见、比例正确。
        LaunchedEffect(viewportW, viewportH) {
            if (viewportW > 0f && viewportH > 0f) {
                BrowserController.relayoutWebViewTo(viewportW.toInt(), viewportH.toInt())
            }
        }
        if (onTap != null) {
            // 点窗任意处放大（遮罩不消费，转为点击手势）
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Transparent)
                    .clickable(
                        interactionSource = null,
                        indication = null
                    ) { onTap() }
            )
        } else {
            // 仅观看遮罩：消费全部指针，用户点按不穿透到页面
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Transparent)
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                awaitPointerEvent().changes.forEach { it.consume() }
                            }
                        }
                    }
            )
        }
    }
}
