package com.haoai.agent.ui.browser

import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.haoai.agent.agent.browser.BrowserController
import com.haoai.agent.ui.common.GlassPanel
import kotlinx.coroutines.launch

/**
 * 4.2 内置浏览器界面：WebView 容器（多标签条 + 地址栏 + 后退/刷新）。
 * 与工具共用 BrowserController 的 WebView 池——Agent 在后台操作时用户切进本界面
 * 看到的是同一批页面；用户手动干预后工具下一次 browser_read 即拿到干预后状态
 * （4.2 验收标准之三）。key(active) 重挂 AndroidView：切标签时旧容器销毁
 * （Compose 自动从父层移除 WebView，实例留池不销毁），新容器 factory 挂新 WebView。
 */
@Composable
fun BrowserScreen(
    backdrop: com.kyant.backdrop.backdrops.LayerBackdrop,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val revision by BrowserController.revision.collectAsState()
    val active by BrowserController.activeIndex.collectAsState()

    // 地址栏文本：非编辑态跟随当前标签 URL（revision 驱动刷新），编辑中不被覆盖
    var urlText by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf(false) }
    LaunchedEffect(revision, active) {
        if (!editing) urlText = BrowserController.activeUrl().let { if (it == "about:blank") "" else it }
    }

    // 系统返回优先"网页后退"，无历史才退出界面
    androidx.activity.compose.BackHandler {
        scope.launch {
            if (BrowserController.canGoBack()) BrowserController.goBack() else onBack()
        }
    }

    // 界面可见期间挂起 5 分钟空闲回收（正在看，别销毁）
    LaunchedEffect(Unit) { BrowserController.uiVisible = true }
    DisposableEffect(Unit) {
        onDispose { BrowserController.uiVisible = false }
    }

    Column(Modifier.fillMaxSize()) {
        GlassPanel(
            backdrop = backdrop,
            modifier = Modifier
                .fillMaxWidth()
                // 用户实测反馈：不垫状态栏高度时标签条与系统状态栏重叠
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            radius = 22.dp,
            surfaceAlpha = 0.2f
        ) {
            Column(Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) {
                // 标签条：标题 chip + 关闭 ×，尾部 + 新建
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    BrowserController.tabTitles().forEachIndexed { i, title ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    if (i == active) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                                    else MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)
                                )
                                .clickable { scope.launch { BrowserController.switchTab(i) } }
                                .padding(start = 10.dp, end = 2.dp, top = 2.dp, bottom = 2.dp)
                        ) {
                            Text(
                                title.ifBlank { "未加载" },
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                modifier = Modifier.padding(vertical = 6.dp)
                            )
                            IconButton(
                                onClick = { scope.launch { BrowserController.closeTab(i) } },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(Icons.Filled.Close, "关闭标签", modifier = Modifier.size(12.dp))
                            }
                        }
                    }
                    IconButton(
                        onClick = { scope.launch { BrowserController.newTab() } },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Filled.Add, "新标签", modifier = Modifier.size(18.dp))
                    }
                }
                // 地址栏 + 后退/刷新/关闭
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = urlText,
                        onValueChange = { urlText = it; editing = true },
                        singleLine = true,
                        placeholder = {
                            Text("网址或搜索词", style = MaterialTheme.typography.bodySmall)
                        },
                        leadingIcon = { Icon(Icons.Filled.Search, null, modifier = Modifier.size(16.dp)) },
                        textStyle = MaterialTheme.typography.bodySmall,
                        shape = RoundedCornerShape(18.dp),
                        colors = OutlinedTextFieldDefaults.colors(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = {
                            val text = urlText.trim()
                            if (text.isNotEmpty()) {
                                editing = false
                                scope.launch { BrowserController.navigateOrSearch(text) }
                            }
                        }),
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 6.dp)
                    )
                    IconButton(onClick = { scope.launch { BrowserController.goBack() } }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "后退", modifier = Modifier.size(20.dp))
                    }
                    IconButton(onClick = { scope.launch { BrowserController.reload() } }) {
                        Icon(Icons.Filled.Refresh, "刷新", modifier = Modifier.size(20.dp))
                    }
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.Close, "关闭浏览器", modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
        // 上游 BrowserWebView 同款：factory 只建空容器（factory 不会重跑），
        // update 里把活动标签的 detached WebView swap 进来（幂等）；离开界面时
        // Compose 移除容器，WebView 随之回到 detached 池，工具继续无头可用
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
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        )
    }
}
