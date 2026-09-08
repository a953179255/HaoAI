package com.haoai.agent.platform

import android.annotation.SuppressLint
import android.webkit.WebView

/**
 * WebView 渲染进程预热：首次创建 WebView 会同步拉起渲染服务（模拟器实测 ~1s 黑屏）。
 * 应用启动后后台建一个壳 WebView（加载 about:blank）触发进程初始化；之后
 * HtmlPreviewModal 再建 WebView 直接复用已就绪的渲染管线，打开即渲染。
 * 壳实例 app 级常驻（几十 KB 内存），不销毁——销毁再建会失去预热效果。
 */
object WebViewWarmer {
    @SuppressLint("SetJavaScriptEnabled")
    private var shell: WebView? = null

    @Synchronized
    fun warm(appContext: android.content.Context) {
        if (shell != null) return
        runCatching {
            shell = WebView(appContext).apply {
                settings.javaScriptEnabled = true
                loadDataWithBaseURL(null, "", "text/html", "utf-8", null)
            }
        }.onFailure { android.util.Log.w("WebViewWarmer", "warm failed: ${it.message}") }
    }
}
