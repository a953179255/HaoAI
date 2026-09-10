package com.haoai.agent.ui.browser

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.haoai.agent.agent.browser.BrowserController
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * 内置浏览器悬浮预览（用户定稿 2026-09-11 晚）：只有「迷你窗」一种形态。
 *
 * - 画面：**实时 WebView 挂载**（方案 A，2026-09-11 定稿）——WebView 以全屏虚拟尺寸
 *   （screenW×screenH EXACTLY）挂进小窗容器，graphicsLayer 等比缩放适配；
 *   页面排版与全屏完全一致（Agent 的 a11y 树/坐标体系不受小窗影响），动画满帧流畅。
 *   （旧位图快照方案每 0.7s 一帧、动画卡顿，已废弃。）
 * - 触摸：观看模式——小窗不与页面交互。顶层 Compose 手势层拦截全部触摸：
 *   单指拖动 = 移动窗口；双指捏合 = 缩放窗口（0.7~2.4，持久化）；页面触摸被吃掉，
 *   防误触干扰 Agent 正在进行的操作。
 * - 按钮（用户定稿）：↗ 全屏（可操作的那种，直接进全屏浏览器，半屏 Sheet
 *   已删除），放在最小化左侧一位；▼ 最小化 = 收起悬浮窗（顶栏 🌐 单击随时唤回）。
 *   关闭/最小化后 WebView 回到 detached 无头态，Agent 工具继续可用。
 */
@Composable
fun BrowserPreviewPanel(
    backdrop: com.kyant.backdrop.backdrops.LayerBackdrop,
    onClose: () -> Unit,
    onFullscreen: () -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
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

    // WebView 的虚拟布局尺寸：保持全屏尺寸 EXACTLY，页面排版与全屏一致
    // （Agent 点击坐标/a11y 节点几何不因小窗改变）；显示适配靠 graphicsLayer 等比缩放
    val screen = remember { BrowserController.screenSizePx() }
    val screenW = screen.first.coerceAtLeast(1)
    val screenH = screen.second.coerceAtLeast(1)

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

    // 重新打开时从保存的位置原地淡入：首帧布局回调（读取保存位置）生效前
    // 不显示——否则窗口会先画在 (0,0) 左上角再跳到原位（用户反馈的过渡 bug）
    val winAlpha by animateFloatAsState(
        targetValue = if (placed) 1f else 0f,
        animationSpec = tween(150),
        label = "winIn"
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
                .alpha(winAlpha)
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
            // 实时画面：WebView 以全屏虚拟尺寸挂载、graphicsLayer 等比缩放进小窗
            // （观看模式）。顶层手势 overlay 吃掉全部触摸：单指拖动移动窗口、双指捏合
            // 缩放窗口，页面触摸被拦截（防误触干扰 Agent 操作）。
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(PREVIEW_ASPECT)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            ) {
                androidx.compose.ui.viewinterop.AndroidView(
                    factory = { ctx ->
                        android.widget.FrameLayout(ctx).apply {
                            // 超出小窗的部分由外层 clip 裁掉（WebView 是全屏尺寸）
                            clipChildren = false
                        }
                    },
                    update = { container ->
                        val wv = BrowserController.webViewAtSync()
                        if (container.childCount == 1 && container.getChildAt(0) === wv) return@AndroidView
                        (wv.parent as? android.view.ViewGroup)?.removeView(wv)
                        container.removeAllViews()
                        // EXACT 全屏虚拟尺寸：页面排版与全屏一致，Agent 坐标/节点几何不变
                        wv.layoutParams = android.view.ViewGroup.LayoutParams(screenW, screenH)
                        container.addView(wv)
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            // 等比缩放：宽度铺满小窗，顶部对齐（pivot 左上）——可见区域与
                            // 旧位图方案取的「整屏顶部 2:3 段」一致
                            val s = if (screenW > 0) size.width / screenW else 1f
                            scaleX = s
                            scaleY = s
                            transformOrigin = TransformOrigin(0f, 0f)
                        }
                )
                // 手势拦截层：盖在 WebView 之上，单指拖动/双指捏合操作窗口，触摸不下发页面
                Box(
                    Modifier
                        .matchParentSize()
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
                )
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
