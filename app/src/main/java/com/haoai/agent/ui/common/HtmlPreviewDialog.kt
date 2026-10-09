package com.haoai.agent.ui.common

import android.annotation.SuppressLint
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * 批2a：统一 HTML 预览壳的内容源。此前三条链路各写各的 WebView（效果稿
 * FullscreenHtmlDialog / 更多面板 HtmlPreviewModal / 工作区文件无入口），
 * 收编后只有两种加载方式之差。
 */
sealed interface HtmlPreviewSource {
    /**
     * 明文 HTML + 任意 baseUrl：消息正文效果稿（baseUrl=null）、mermaid
     * （file:///android_asset/mermaid/）、更多面板模板预览（file:///android_asset/）。
     * [dedupKey] 变了才重载（沿用旧壳 tag 守卫语义）。
     */
    data class Content(val html: String, val baseUrl: String?, val dedupKey: String) : HtmlPreviewSource

    /**
     * 工作区文件：loadUrl(file://…) 直载。相对资源/页内链接由 WebView 按
     * 页面 URL 自动解析到同目录，不需要额外 baseUrl——这正是旧行为里
     * 「工作区 html 打不开」缺的那条腿。
     */
    data class FilePage(val fileUrl: String) : HtmlPreviewSource
}

/**
 * 统一全屏 HTML 预览壳（批2a 定稿）：Dialog 窗口 + WebView 池 + 加载进度条
 * + 状态栏 inset。三个入口共用：
 * - 效果稿卡片/mermaid 全屏（Markdown.kt 的 FullscreenHtmlDialog 薄封装）
 * - 更多 → 网页预览（HtmlPreviewModal 薄封装）
 * - 产物卡 [预览]（ChainOfThought 的 HtmlFileCard）
 *
 * 底部换乘钮（onOpenInBrowser 非空才出现）：预览起手、要交互测试时一键升级
 * 到内置浏览器，不用回聊天翻产物卡——效果图定稿的「预览 ↔ 浏览器」互通。
 */
@Composable
fun HtmlPreviewDialog(
    title: String,
    source: HtmlPreviewSource,
    onDismiss: () -> Unit,
    /** 底部「在浏览器打开 ↗」换乘钮；null = 不是文件产物，浏览器没有用武之地。 */
    onOpenInBrowser: (() -> Unit)? = null,
    /** WebView 底色：深色模板传深底防首绘白闪；默认白底（效果稿/产物假定浅色页）。 */
    pageBackgroundColor: Int = android.graphics.Color.WHITE,
) {
    // 进度（0f..1f）：有反馈就不像卡死（沿用旧 HtmlPreviewModal 的做法）
    val progress = remember { mutableFloatStateOf(0f) }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .background(Color(0xFF10151A))
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFE8EEEA),
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "关闭",
                    fontSize = 12.sp,
                    color = Color(0xFF9AA8A0),
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .clickable { onDismiss() }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
            if (progress.floatValue in 0.01f..0.99f) {
                LinearProgressIndicator(
                    progress = { progress.floatValue },
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp)
                )
            }
            AndroidView(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                factory = { ctx ->
                    @SuppressLint("SetJavaScriptEnabled")
                    WebViewPool.acquire(ctx).apply {
                        setBackgroundColor(pageBackgroundColor)
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                progress.floatValue = newProgress / 100f
                            }
                        }
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView, url: String?) {
                                progress.floatValue = 0f
                            }
                        }
                    }
                },
                update = { wv ->
                    val key = when (source) {
                        is HtmlPreviewSource.Content -> "c:" + source.dedupKey
                        is HtmlPreviewSource.FilePage -> {
                            // 带 mtime：池 WebView 的 tag 跨对话残留，同 URL 不带 mtime 会
                            // 跳过重载，把上一次打开的旧画面当新文件显示（真机复现实锤）
                            val p = android.net.Uri.parse(source.fileUrl).path
                            "f:" + source.fileUrl + "@" +
                                (if (p != null) java.io.File(p).lastModified() else 0L)
                        }
                    }
                    if (wv.tag != key) {
                        wv.tag = key
                        // 首绘前加载：模板里的 vh shim 读 innerHeight，update 先于布局执行，
                        // 立即加载会固化 0 值白屏（旧 HtmlPreviewModal 的实测坑）——推迟到
                        // 首次 pre-draw。file:// 同样延后无害。
                        androidx.core.view.OneShotPreDrawListener.add(wv) {
                            when (source) {
                                is HtmlPreviewSource.Content ->
                                    wv.loadDataWithBaseURL(source.baseUrl, source.html, "text/html", "utf-8", null)
                                is HtmlPreviewSource.FilePage ->
                                    wv.loadUrl(source.fileUrl)
                            }
                        }
                    }
                },
                onRelease = { wv ->
                    // 归还前摘掉带组合捕获的回调、恢复透明底，避免污染池内后续用途
                    wv.webChromeClient = null
                    wv.webViewClient = WebViewClient()
                    wv.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    WebViewPool.release(wv)
                }
            )
            if (onOpenInBrowser != null) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End
                ) {
                    Text(
                        "在浏览器打开 ↗",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(9.dp))
                            .clickable { onOpenInBrowser() }
                            .padding(horizontal = 13.dp, vertical = 7.dp)
                    )
                }
            }
        }
    }
}
