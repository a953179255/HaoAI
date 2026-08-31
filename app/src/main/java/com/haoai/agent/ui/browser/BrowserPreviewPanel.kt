package com.haoai.agent.ui.browser

import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.haoai.agent.agent.browser.BrowserController
import com.haoai.agent.ui.common.GlassPanel
import kotlinx.coroutines.delay
import org.json.JSONArray

/**
 * 4.2 呼出优化：底部非全屏预览面板（上游 两段式第一段）。Agent 无头浏览时
 * 经 uiOpener 自动弹出，聊天区保持可见可继续对话；预览区触摸屏蔽（仅观看，用户
 * 选定交互方式），链接条点击直接复制网址，点 🌐 换挂全屏 BrowserScreen 完整操作。
 *
 * WebView 承载复刻 BrowserScreen 的硬约束模式：factory 只建空容器，update 里把
 * 活动标签 WebView swap 进来（幂等）——绝不在 factory 直接返回宿主里的 WebView
 * （转移挂载破坏 Compose 绘制 → 整页白屏，BrowserController 文档记载的教训）。
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

    fun copyAndToast(text: String) {
        if (text.isBlank()) return
        clipboard.setText(AnnotatedString(text))
        Toast.makeText(context, "已复制：${text.take(48)}", Toast.LENGTH_SHORT).show()
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

    // 入场：自底部滑入（面板只在 previewOpen=true 时组合，离场即卸载无需动画）
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val slide by animateFloatAsState(if (shown) 0f else 1f, tween(260), label = "previewSlide")

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

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .navigationBarsPadding()
                .padding(bottom = 8.dp)
                .fillMaxHeight(0.46f)
                .graphicsLayer { translationY = slide * size.height }
        ) {
            GlassPanel(
                backdrop = backdrop,
                modifier = Modifier.fillMaxWidth(),
                radius = 20.dp,
                surfaceAlpha = 0.72f
            ) {
                Column(Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) {
                    // 顶栏：关闭 × ｜ 标题 + URL（点按复制当前页网址）｜ 🌐 全屏
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onClose, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Filled.Close, "关闭预览", modifier = Modifier.size(18.dp))
                        }
                        Column(
                            Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { copyAndToast(url) }
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                        ) {
                            Text(
                                title.ifBlank { "网页预览" },
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                url.ifBlank { "点按复制当前网址" },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        IconButton(onClick = onFullscreen, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Filled.Public, "全屏浏览器", modifier = Modifier.size(18.dp))
                        }
                    }
                    // 链接条：当前页可见链接，点击直接复制（上游 CopyableURLCapsule 同思路）
                    if (links.isNotEmpty()) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(top = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            links.forEach { (text, href) ->
                                Text(
                                    text = "🔗 " + text.ifBlank { href.substringAfter("//").substringBefore('/') },
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))
                                        .clickable { copyAndToast(href) }
                                        .padding(horizontal = 10.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                }
            }
            // 预览区：活动标签 WebView（与工具/全屏共用实例）+ 触摸屏蔽层
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(top = 6.dp)
                    .clip(RoundedCornerShape(20.dp))
            ) {
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
                // 仅观看：消费全部触摸（用户选定）；操作引导进全屏
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.12f))
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    awaitPointerEvent().changes.forEach { it.consume() }
                                }
                            }
                        },
                    contentAlignment = Alignment.BottomCenter
                ) {
                    Text(
                        "预览模式 · 点按已屏蔽，点 🌐 全屏操作",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        maxLines = 1,
                        modifier = Modifier
                            .padding(bottom = 8.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(Color.Black.copy(alpha = 0.45f))
                            .padding(horizontal = 12.dp, vertical = 5.dp)
                    )
                }
            }
        }
    }
}
