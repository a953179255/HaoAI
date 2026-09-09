package com.haoai.agent.ui.browser

import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
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
import kotlin.math.roundToInt
import org.json.JSONArray

/**
 * 浏览器悬浮预览（用户选型 2026-09-10 方案 D+）：缩略图 ↔ 浮窗两态 + 拖拽 + 位置记忆。
 * - 缩略态（默认弹出）：右上 116dp 宽实时小窗（标题条 + 缩微 WebView），点击展开；
 * - 浮窗态：约 66% 容器宽（标题/URL 行 + WebView 240dp），按住标题行拖拽，
 *   位置归一化存 SharedPreferences("float_windows")——进其他界面返回/重启都在原位；
 * - 仅观看：WebView 触摸屏蔽（用户选定交互），操作走 🌐 全屏；× 收缩回缩略，
 *   长按缩略 × 退出预览（WebView 摘回 detached 池，下次导航自动再弹）。
 *
 * WebView 硬约束（BrowserController 文档）：factory 只建空容器，update 幂等 swap
 * 活动 WebView；缩略/浮窗容器互斥组合，WebView 一次只挂一处。
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

    // 可见期间即「UI 打开」：工具照常工作、空闲回收挂起；关闭时 WebView 摘回
    // detached 态（无隐藏宿主，下次 navigate 再由面板换挂）
    LaunchedEffect(Unit) { BrowserController.uiVisible = true }
    DisposableEffect(Unit) {
        onDispose {
            BrowserController.uiVisible = false
            BrowserController.detachAll()
        }
    }

    BackHandler(onBack = onClose)

    val density = androidx.compose.ui.platform.LocalDensity.current
    // 两态：false=缩略（默认）true=浮窗；浮窗位置归一化持久化（拖动落盘）
    var expanded by remember { mutableStateOf(false) }
    val prefs = remember { context.getSharedPreferences("float_windows", android.content.Context.MODE_PRIVATE) }
    var containerW by remember { mutableStateOf(0f) }
    var containerH by remember { mutableStateOf(0f) }
    var winW by remember { mutableStateOf(0f) }
    var winH by remember { mutableStateOf(0f) }
    var offX by remember { mutableStateOf(0f) }
    var offY by remember { mutableStateOf(0f) }

    fun place() {
        if (containerW <= 0f || containerH <= 0f || winW <= 0f || winH <= 0f) return
        val nx = prefs.getFloat("browser_x", -1f)
        val ny = prefs.getFloat("browser_y", -1f)
        if (nx >= 0f && ny >= 0f) {
            offX = (nx * containerW).coerceIn(0f, maxOf(0f, containerW - winW))
            offY = (ny * containerH).coerceIn(0f, maxOf(0f, containerH - winH))
        } else {
            // 默认右上：右距 10dp、顶距 96dp（顶栏+任务胶囊之下）
            val d = density.density
            offX = (containerW - winW - 10f * d).coerceAtLeast(10f)
            offY = 96f * d
        }
    }

    fun onDrag(dx: Float, dy: Float) {
        if (containerW <= 0f || winW <= 0f) return
        offX = (offX + dx).coerceIn(0f, maxOf(0f, containerW - winW))
        offY = (offY + dy).coerceIn(0f, maxOf(0f, containerH - winH))
        prefs.edit()
            .putFloat("browser_x", if (containerW > 0) offX / containerW else 0f)
            .putFloat("browser_y", if (containerH > 0) offY / containerH else 0f)
            .apply()
    }

    // 链接条：加载完成（revision 变化）后延时提取，给页面渲染留余量
    var linksJson by remember { mutableStateOf("[]") }
    LaunchedEffect(revision, active) {
        val url = BrowserController.activeUrl()
        if (url.isBlank() || url == "about:blank") {
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

    val title = BrowserController.tabTitles().getOrNull(active).orEmpty()
    val url = BrowserController.activeUrl()

    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned {
                containerW = it.size.width.toFloat()
                containerH = it.size.height.toFloat()
                if (expanded) place()
            }
    ) {
        if (expanded) {
            // ── 浮窗态：标题行拖拽 + WebView 240dp ──
            Column(
                Modifier
                    .onGloballyPositioned {
                        winW = it.size.width.toFloat()
                        winH = it.size.height.toFloat()
                        place()
                    }
                    .width((containerW * 0.66f / density.density).dp.coerceAtLeast(230.dp))
                    .offset { IntOffset(offX.roundToInt(), offY.roundToInt()) }
            ) {
                GlassPanel(
                    backdrop = backdrop,
                    radius = 16.dp,
                    surfaceAlpha = 0.72f,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) {
                        // 拖拽手柄行：标题+URL（点按复制）｜ 收缩 □ ｜ 全屏 🌐 ｜ 关闭 ×
                        Row(
                            Modifier
                                .fillMaxWidth()
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
                            Column(
                                Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { copyAndToast(url, "当前网址") }
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    title.ifBlank { "网页预览" },
                                    style = MaterialTheme.typography.labelLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    url.ifBlank { "点按复制网址" },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            IconButton(onClick = { expanded = false }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Filled.Close, "收缩为缩略图", modifier = Modifier.size(15.dp))
                            }
                            IconButton(onClick = onFullscreen, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Filled.Public, "全屏浏览器", modifier = Modifier.size(15.dp))
                            }
                        }
                        // 链接条（保留原功能）
                        if (links.isNotEmpty()) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState())
                                    .padding(top = 2.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                links.forEach { (text, href) ->
                                    Text(
                                        text = "🔗 " + text.ifBlank { href.substringAfter("//").substringBefore('/') },
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))
                                            .clickable { copyAndToast(href, "链接") }
                                            .padding(horizontal = 10.dp, vertical = 5.dp)
                                    )
                                }
                            }
                        }
                    }
                }
                // WebView 预览区（浮窗态 240dp 看清页面主体）
                PreviewWebView(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .height(240.dp)
                        .clip(RoundedCornerShape(16.dp))
                )
            }
        } else {
            // ── 缩略态：116dp 宽实时小窗，点击展开；长按 × 退出预览 ──
            Column(
                Modifier
                    .onGloballyPositioned {
                        winW = it.size.width.toFloat()
                        winH = it.size.height.toFloat()
                        place()
                    }
                    .width(116.dp)
                    .offset { IntOffset(offX.roundToInt(), offY.roundToInt()) }
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f))
                    .clickable { expanded = true }
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        title.ifBlank { url.ifBlank { "网页" } },
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Icon(
                        Icons.Filled.OpenInFull,
                        "展开浮窗",
                        modifier = Modifier
                            .padding(start = 3.dp)
                            .size(10.dp)
                            .alpha(0.6f)
                    )
                }
                PreviewWebView(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                )
            }
        }
    }
}

/**
 * WebView 挂载容器（缩略/浮窗共用）：factory 空 FrameLayout，update 幂等 swap
 * 活动 WebView + 仅观看触摸遮罩。两个容器互斥组合，WebView 一次只挂一处。
 */
@Composable
private fun PreviewWebView(modifier: Modifier = Modifier) {
    Box(modifier) {
        AndroidView(
            factory = { ctx ->
                android.widget.FrameLayout(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                    )
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
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.10f))
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
