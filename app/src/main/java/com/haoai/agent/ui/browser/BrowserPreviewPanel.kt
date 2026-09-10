package com.haoai.agent.ui.browser

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.OpenInFull
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.haoai.agent.agent.browser.BrowserController
import kotlinx.coroutines.delay
import org.json.JSONArray
import kotlin.math.roundToInt

/**
 * 内置浏览器悬浮预览（用户定稿 2026-09-11 晚）：只有「迷你窗」一种形态。
 *
 * - 画面：位图快照（每 ~0.7s 一帧，按 2:3 浏览器可视区比例裁剪），
 *   与半屏/全屏里看到的页面一致，不留整屏缩略的多余空白。
 *   （小窗内放 interop WebView 收不到触摸——实测；位图是纯 Compose 内容，
 *   拖动/捏合手势才稳定。）
 * - 手势：单指拖动 = 移动窗口；双指捏合 = 缩放窗口（0.7~2.4，持久化）；
 *   位置夹在安全区内（不压顶栏、不压输入框）。
 * - 按钮（用户定稿）：↗ 全屏（可操作的那种，直接进全屏浏览器，半屏 Sheet
 *   已删除），放在最小化左侧一位；▼ 最小化 = 收起悬浮窗（顶栏 🌐 单击随时唤回）。
 */
@Composable
fun BrowserPreviewPanel(
    backdrop: com.kyant.backdrop.backdrops.LayerBackdrop,
    onClose: () -> Unit,
    onFullscreen: () -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
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
            // 复位缩放（保险）：避免全屏浏览器/无头工具继承缩小画面
            BrowserController.resetPreviewScale()
            BrowserController.detachAll()
        }
    }

    BackHandler(onBack = onClose)

    // 屏幕宽高比：抓帧只取整屏顶部与窗口同比例的一段
    val screenAspect = remember {
        val (sw, sh) = BrowserController.screenSizePx()
        if (sw > 0 && sh > 0) sw.toFloat() / sh.toFloat() else 1080f / 2400f
    }
    // 小窗画面比例（宽/高）= 2:3，对齐半屏/全屏里「网页可视区」那一块（方案 A）
    val previewTopFraction = remember { (screenAspect / PREVIEW_ASPECT).coerceIn(0.2f, 1f) }

    val density = context.resources.displayMetrics.density
    // 安全区：顶部让开顶栏、底部让开输入框（窗口不得压在这两块上）
    val topInset = 96f * density
    val bottomInset = 104f * density

    // ── 位置/大小：位置归一化持久化，双指捏合改缩放 ──
    val prefs = remember { context.getSharedPreferences("float_windows", android.content.Context.MODE_PRIVATE) }
    var containerW by remember { mutableStateOf(0f) }
    var containerH by remember { mutableStateOf(0f) }
    var miniW by remember { mutableStateOf(0f) }
    var miniH by remember { mutableStateOf(0f) }
    var offX by remember { mutableStateOf(0f) }
    var offY by remember { mutableStateOf(0f) }
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

    /** 双指捏合：factor>1 放大窗口。 */
    fun onZoom(factor: Float) {
        if (factor <= 0f) return
        zoom = (zoom * factor).coerceIn(MIN_ZOOM, MAX_ZOOM)
        prefs.edit().putFloat("browser_zoom", zoom).apply()
    }

    val title = BrowserController.tabTitles().getOrNull(active).orEmpty()
    val url = BrowserController.activeUrl()

    // 缩略位图：预览打开期间按帧刷新（约 0.7s 一帧）
    var snapshot by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    LaunchedEffect(zoom) {
        // 缩放/移动结束后立刻补一帧清晰位图（手势中位图被拉伸会发虚）
        delay(220)
        snapshot = BrowserController.captureSnapshot(0.42f, previewTopFraction)?.asImageBitmap()
    }
    LaunchedEffect(Unit) {
        while (true) {
            // 位图态 WebView 未被挂载：保持全屏合成布局（Agent 导航/读取依赖）再抓帧
            val wv = BrowserController.webViewAtSync()
            BrowserController.ensureLaidOutForPreview(wv)
            snapshot = BrowserController.captureSnapshot(0.42f, previewTopFraction)?.asImageBitmap()
            delay(700)
        }
    }

    // 链接条：加载完成后延时提取（点按复制去向）
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

    // 捏合后窗口尺寸变化 → 夹取位置
    LaunchedEffect(zoom) {
        if (placed) {
            delay(50)
            clampOffsets()
        }
    }

    val miniWidthDp = (MINI_BASE_DP * zoom).coerceAtMost(
        if (containerW > 0f) (containerW / density) - 16f else MINI_BASE_DP * MAX_ZOOM
    )

    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned {
                containerW = it.size.width.toFloat()
                containerH = it.size.height.toFloat()
                if (!placed) place() else clampOffsets()
            }
    ) {
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
            // 标题栏：拖动移动窗口；↗ 全屏（最小化左侧一位）、▼ 最小化（最右）
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
                    modifier = Modifier
                        .weight(1f)
                        .clickable(
                            interactionSource = null,
                            indication = null
                        ) { copyAndToast(url, "当前网址") }
                )
                IconButton(
                    onClick = onFullscreen,
                    modifier = Modifier.size(30.dp)
                ) {
                    Icon(
                        Icons.Filled.OpenInFull,
                        "全屏浏览器（可操作）",
                        modifier = Modifier.size(15.dp)
                    )
                }
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.size(30.dp)
                ) {
                    Icon(
                        Icons.Filled.KeyboardArrowDown,
                        "最小化悬浮窗",
                        modifier = Modifier
                            .size(17.dp)
                            .alpha(0.85f)
                    )
                }
            }
            // 位图缩略画面（纯 Compose）：单指拖动移动、双指捏合缩放；点按不穿透
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(PREVIEW_ASPECT)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoomChange, _ ->
                            // 双指期间只缩放不平移：否则窗口跟着手指乱飘、手感生硬
                            if (zoomChange == 1f) {
                                onDrag(pan.x, pan.y)
                            } else {
                                onZoom(zoomChange)
                            }
                        }
                    }
            ) {
                snapshot?.let { bmp ->
                    Image(
                        bitmap = bmp,
                        contentDescription = "网页缩略预览",
                        contentScale = ContentScale.FillBounds,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                // 底部链接条：点按复制去向（不遮挡画面主体）
                if (links.isNotEmpty()) {
                    Row(
                        Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.75f))
                            .padding(horizontal = 5.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        links.take(3).forEach { (text, href) ->
                            Text(
                                text = "🔗 " + text.ifBlank { href.substringAfter("//").substringBefore('/') },
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
                                    .clickable { copyAndToast(href, "链接") }
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 迷你窗基础宽度与缩放范围（双指捏合）。 */
private const val MINI_BASE_DP = 132f
private const val MIN_ZOOM = 0.7f
private const val MAX_ZOOM = 2.4f

/** 小窗画面比例（宽/高）= 2:3，对齐半屏/全屏里「网页可视区」那一块（方案 A）。 */
private const val PREVIEW_ASPECT = 2f / 3f

/** 阴影占位（真实阴影由窗口 surface 与背景对比承担）。 */
private fun Modifier.shadowOrCreate(): Modifier = this
