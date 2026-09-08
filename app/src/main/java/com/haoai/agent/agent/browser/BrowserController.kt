package com.haoai.agent.agent.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.math.min

/**
 * 4.2 内置 WebView 浏览器控制器（App 级单例，AppContainer.init 里装配）：
 * - 最多 3 个标签的 WebView 池；工具（可无头运行）与 BrowserScreen（可视界面）
 *   共用同一批实例——用户随时切进浏览器界面手动干预，工具下一次 read 拿到的就是
 *   干预后的状态（4.2 验收标准之三）。
 * - WebView 只能在主线程操作：所有 suspend 方法内部 withContext(Main)。
 * - 页面结构读取：注入 __haoaiMark JS，给可交互元素编号 data-haoai-index 并返回
 *   结构 JSON；click/input 按 number 定位（与 a11y 的 index 体系同思路）。
 * - Cookie 说明：Android WebView 的 cookie jar 天然按应用进程隔离，用户日常
 *   浏览器（Chrome 等）的登录态不在本应用 jar 里，无污染路径；应用内 Agent 与
 *   浏览器界面共用本应用 jar（多 Profile 隔离需 API 35 ProfileStore，暂不做）。
 */
object BrowserController {

    const val MAX_TABS = 3
    const val LOAD_TIMEOUT_MS = 20_000L
    const val RENDER_MARGIN_MS = 800L
    const val IDLE_RECYCLE_MS = 5 * 60_000L
    const val MAX_ELEMENTS = 80

    @Volatile private var appContext: Context? = null
    @Volatile private var scope: CoroutineScope? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 托管模式（2026-08-31 去泊车重构）：WebView 用应用上下文
     * 创建后保持 detached（parent == null，不挂任何窗口），**没有隐藏宿主**。
     * 旧方案（全屏 VISIBLE 宿主垫在 content 底层被 Compose 盖住泊车）在魅族
     * 20 Pro/Android 16 上出现「导航停摆」：可见容器里 loadUrl 后 onPageFinished
     * 永不回调（fa8ab01 原版同样复现、重启手机/清 app_webview 均无效，而同机
     * 系统浏览器正常）——泊车态 Chromium 合成器进入抑制态后换挂
     * 可见容器也无法恢复。三方同类项目调研证实无项目采用
     * 「已挂载但被遮挡」的泊车模式：
     * - 方案A：全程 detached，每次 loadUrl 前手动 measure(EXACTLY)+layout
     *   合成布局，导航/JS/截图全通（同机实证可用）；活跃性靠进程级 FGS。
     * - 方案B：WindowManager 1x1 悬浮窗（SYSTEM_ALERT_WINDOW）+ 伪全屏 measure。
     * - 方案C：VirtualDisplay + ImageReader。
     * 无头导航的活跃性由进程承担（本应用 KeepAliveService 前台服务已有），
     * 视图树层面只保证「可见容器内导航」（预览面板自动弹出即为此设计）。
     */

    class Tab internal constructor(val id: Long, internal var webView: WebView) {
        var title: String = ""
        var url: String = ""
        internal var loadSignal: CompletableDeferred<Unit>? = null
    }

    private val tabs = mutableListOf<Tab>()
    private var nextTabId = 1L

    /** 活动标签在 tabs 中的下标（工具与 UI 共用）。 */
    val activeIndex = MutableStateFlow(0)

    /** 界面可见性：可见时暂停 5 分钟空闲回收（用户正在看）。 */
    @Volatile var uiVisible = false

    /**
     * 界面拉起器（MainActivity 注册）：无头 navigate 时自动弹出底部预览面板
     * （两段式呼出第一段，用户全程看得到浏览过程；点面板 🌐 换挂全屏浏览器
     * 完整操作）。唤起失败不阻断——navigate 会降级为 detached 导航。
     */
    var uiOpener: (() -> Unit)? = null

    /**
     * 底部预览面板开关（MainActivity 收集渲染 BrowserPreviewPanel）。面板与
     * 全屏 BrowserScreen 共用 WebView 池、一次只显示其一；关闭面板时 WebView
     * 摘回 detached 态，下次 navigate 再自动弹出。
     */
    val previewOpen = MutableStateFlow(false)

    fun openPreview() { previewOpen.value = true }

    /** UI 观察用：任一标签加载状态变化时自增，驱动 BrowserScreen 刷新地址栏/标题。 */
    val revision = MutableStateFlow(0)

    @Volatile private var lastUsedAt = System.currentTimeMillis()
    private var recycler: Job? = null

    val ready: Boolean get() = appContext != null

    fun init(context: Context, backgroundScope: CoroutineScope) {
        if (appContext != null) return
        appContext = context.applicationContext
        scope = backgroundScope
        recycler = backgroundScope.launch {
            while (true) {
                delay(60_000)
                val idle = System.currentTimeMillis() - lastUsedAt > IDLE_RECYCLE_MS
                if (idle && !uiVisible && tabs.isNotEmpty()) closeAll()
            }
        }
    }

    /** Activity 绑定（MainActivity 进程内调用一次）：仅更新屏幕尺寸缓存。 */
    fun bindActivity(activity: android.app.Activity) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { bindActivity(activity) }
            return
        }
        screenW = activity.resources.displayMetrics.widthPixels
        screenH = activity.resources.displayMetrics.heightPixels
    }

    /** 池内所有 WebView 从任何父容器摘下（回到 detached 态）：预览面板/浏览器
     *  界面关闭时调用。异步延迟一拍执行并带 uiVisible 守卫——面板→全屏切换时
     *  全屏容器会先挂上 WebView，面板的 onDispose 后到，若同步摘除会把刚挂好
     *  的实例又拽下来（全屏白屏）；下一拍 uiVisible 已被新界面置 true，跳过。 */
    internal fun detachAll() {
        mainHandler.post {
            if (uiVisible) return@post
            for (tab in tabs) {
                (tab.webView.parent as? ViewGroup)?.removeView(tab.webView)
                ensureLaidOut(tab.webView)
            }
        }
    }

    /**
     * detached WebView 的合成布局：
     * WebView 不挂任何窗口时永远不会自动 layout，viewport 为 0、页面不排版。
     * 手动按屏幕尺寸 EXACTLY measure+layout 后，JS 布局数据与导航均可推进。
     */
    @Volatile private var screenW = 0
    @Volatile private var screenH = 0

    internal fun ensureLaidOut(wv: WebView) {
        // UI 可见时 WebView 在可见容器里有真实布局，交给容器（手动改成全屏会撑变形）
        if (uiVisible) return
        val ctx = appContext ?: return
        val dm = ctx.resources.displayMetrics
        val w = if (screenW > 0) screenW else dm.widthPixels
        val h = if (screenH > 0) screenH else dm.heightPixels
        if (w <= 0 || h <= 0) return
        if (wv.width == w && wv.height == h && wv.isLaidOut) return
        val specW = android.view.View.MeasureSpec.makeMeasureSpec(w, android.view.View.MeasureSpec.EXACTLY)
        val specH = android.view.View.MeasureSpec.makeMeasureSpec(h, android.view.View.MeasureSpec.EXACTLY)
        wv.measure(specW, specH)
        wv.layout(0, 0, w, h)
    }

    // ---------- 标签管理 ----------

    private fun assertMain() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "WebView 只能在主线程操作" }
    }

    /** 取下标处的 WebView（不存在则创建并合成布局）；返回恒非空。 */
    suspend fun webViewAt(index: Int? = null): WebView = withContext(Dispatchers.Main) {
        touch()
        val tab = (index?.let { tabs.getOrNull(it) } ?: getOrCreateTab())
        ensureLaidOut(tab.webView)
        tab.webView
    }

    /** 主线程同步取活动标签的 WebView（UI 挂载用）；不存在则建首个标签并合成布局。 */
    fun webViewAtSync(index: Int? = null): WebView {
        assertMain()
        touch()
        val tab = (index?.let { tabs.getOrNull(it) } ?: getOrCreateTab())
        ensureLaidOut(tab.webView)
        return tab.webView
    }

    /** 无参同步取活动标签（UI 进入时）；tabs 为空则建首个标签并入列。
     *  （2026-08-31 修复：旧实现调 newTabLocked() 后不入列——tabs 恒空，
     *  面板挂载/navigate/read 各造各的孤儿 WebView，onPageFinished 永远
     *  找不到 tab → loadSignal 永不完成 → 一律 20s 超时假加载。） */
    private fun getOrCreateTab(): Tab {
        tabs.firstOrNull()?.let { return it }
        val tab = newTabLocked()
        tabs.add(tab)
        return tab
    }

    /** 仅创建不入列表（入列由调用方负责，避免同一实例双重入列→UI 双标签）。 */
    private fun newTabLocked(): Tab {
        val ctx = appContext ?: error("BrowserController 未初始化")
        val wv = createWebView(ctx)
        val tab = Tab(nextTabId++, wv)
        // detached 创建：不挂任何宿主；无头导航前由
        // ensureLaidOut 合成布局，UI 打开时由可见容器换挂
        ensureLaidOut(wv)
        return tab
    }

    /** 新建标签并设为活动；超上限时挤掉最旧的非活动标签。 */
    suspend fun newTab(url: String? = null): Int = withContext(Dispatchers.Main) {
        touch()
        if (tabs.size >= MAX_TABS) {
            val victim = tabs.indices.filter { it != activeIndex.value }.minByOrNull { tabs[it].id }
            victim?.let { closeTabInternal(it) }
        }
        tabs.add(newTabLocked())
        activeIndex.value = tabs.size - 1
        bump()
        if (url != null) navigate(url)
        activeIndex.value
    }

    suspend fun closeTab(index: Int): Unit = withContext(Dispatchers.Main) {
        if (tabs.isEmpty()) return@withContext // 空池（上次已就地销毁最后一个标签）时 coerceIn 会崩
        closeTabInternal(index.coerceIn(0, tabs.size - 1))
    }

    private fun closeTabInternal(index: Int) {
        if (tabs.size <= 1) {
            // 最后一个标签：就地销毁（不能调 suspend closeAll），空池由下次访问重建
            for (tab in tabs) {
                (tab.webView.parent as? android.view.ViewGroup)?.removeView(tab.webView)
                tab.webView.destroy()
            }
            tabs.clear()
            activeIndex.value = 0
            bump()
            return
        }
        val tab = tabs.removeAt(index)
        (tab.webView.parent as? android.view.ViewGroup)?.removeView(tab.webView)
        tab.webView.destroy()
        if (activeIndex.value >= tabs.size) activeIndex.value = tabs.size - 1
        bump()
    }

    suspend fun closeAll(): Unit = withContext(Dispatchers.Main) {
        for (tab in tabs) {
            (tab.webView.parent as? android.view.ViewGroup)?.removeView(tab.webView)
            tab.webView.destroy()
        }
        tabs.clear()
        activeIndex.value = 0
        bump()
    }

    suspend fun switchTab(index: Int): Unit = withContext(Dispatchers.Main) {
        if (index in tabs.indices) {
            activeIndex.value = index
            touch()
            bump()
        }
    }

    fun tabSummaries(): List<String> = tabs.mapIndexed { i, t -> "[$i] ${t.title.ifBlank { "未加载" }} ${t.url.take(60)}" }

    /** UI 标签条：各标签标题（须主线程；供 Compose composition 读取）。 */
    fun tabTitles(): List<String> = tabs.map { it.title.orEmpty().ifBlank { it.url.ifBlank { "未加载" } } }

    /** UI 地址栏：活动标签当前 URL（主线程读；不创建标签）。 */
    fun activeUrl(): String = tabs.getOrNull(activeIndex.value)?.url.orEmpty()

    /** 系统返回键语义：能否网页后退（主线程读）。 */
    fun canGoBack(): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper()) return false
        return tabs.getOrNull(activeIndex.value)?.webView?.canGoBack() ?: false
    }

    /** 地址栏输入：含 . 无空格（或带协议）按 URL，否则当搜索词走 Bing。 */
    suspend fun navigateOrSearch(text: String): String {
        val isUrl = text.startsWith("http") || (text.contains(".") && !text.contains(" "))
        return if (isUrl) navigate(text)
        else navigate("https://www.bing.com/search?q=" + android.net.Uri.encode(text))
    }

    /**
     * 补协议：有 scheme 原样；host 是私有段/localhost 时补 http://（内网多为明文
     * 服务，强补 https 会撞 SSL 协议错）；其余补 https://。
     */
    private fun withScheme(url: String): String {
        if (url.startsWith("http://") || url.startsWith("https://")) return url
        val host = url.substringBefore('/').substringBefore(':')
        return if (com.haoai.agent.platform.NetGuard.isPrivateHost(host)) "http://$url" else "https://$url"
    }

    /** 刷新当前页：重新加载并等待完成。 */
    suspend fun reload(): String = withContext(Dispatchers.Main) {
        touch()
        val tab = getOrCreateTab()
        ensureLaidOut(tab.webView)
        val current = tab.url.ifBlank { "about:blank" }
        if (current == "about:blank") return@withContext "当前页未加载，无内容可刷新"
        tab.loadSignal = CompletableDeferred()
        tab.webView.reload()
        withTimeoutOrNull(LOAD_TIMEOUT_MS) { tab.loadSignal?.await() }
        delay(RENDER_MARGIN_MS)
        bump()
        "已刷新：${tab.title.ifBlank { current }}"
    }

    private fun touch() { lastUsedAt = System.currentTimeMillis() }
    private fun bump() { revision.value++ }

    // ---------- WebView 创建 ----------

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(ctx: Context): WebView {
        WebView.enableSlowWholeDocumentDraw()
        // Android 16 实测坑：多进程 WebView 的 webview_service 是独立缓存进程，
        // WebView 不可见 ~10s 即被 cached-app freezer 冻结 → loadUrl 停在
        // onPageStarted、网络请求永不发出（日志与 dumpsys 进程状态实锤）。
        return WebView(ctx).apply {
            // 实战加固：OEM GPU 合成在弹层/换挂场景下白屏，强制硬件层
            setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // target=_blank 就地打开：自动化里 onCreateWindow 是死路
            settings.setSupportMultipleWindows(false)
            settings.mediaPlaybackRequiresUserGesture = false
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            // 无头保命开关：WebView 不可见（detached 态）时继续光栅化/渲染，
            // 否则 Android 16 的 freezer 会在 ~10s 后冻结 webview_service，
            // loadUrl 变成假加载（onPageFinished 永不回调、无网络请求）
            settings.offscreenPreRaster = true
            // 不用 addJavascriptInterface：页面 JS 是不可信输入，桥会扩攻击面；
            // 结构读取统一走 evaluateJavascript 主通道（方向相反，无暴露）
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) {
                    val tab = tabs.firstOrNull { it.webView === view } ?: return
                    tab.title = view.title ?: ""
                    tab.url = url ?: view.url ?: ""
                    tab.loadSignal?.complete(Unit)
                    tab.loadSignal = null
                    bump()
                }
                // 加固：主帧加载失败也完成 loadSignal——否则错误页
                // 永不触发 onPageFinished，navigate 白白等满 20s 超时
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
                    if (!request.isForMainFrame) return
                    val tab = tabs.firstOrNull { it.webView === view } ?: return
                    tab.title = view.title ?: ""
                    tab.url = request.url.toString()
                    tab.loadSignal?.complete(Unit)
                    tab.loadSignal = null
                    bump()
                }
                // 自愈：渲染进程崩死后该实例永久假加载，
                // 原地销毁重建一个新标签替换，导航管线恢复
                override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                    val idx = tabs.indexOfFirst { it.webView === view }
                    if (idx >= 0) {
                        val dead = tabs.removeAt(idx)
                        (dead.webView.parent as? ViewGroup)?.removeView(dead.webView)
                        runCatching { dead.webView.destroy() }
                        if (activeIndex.value >= tabs.size) activeIndex.value = (tabs.size - 1).coerceAtLeast(0)
                    }
                    bump()
                    return true
                }
                // http(s)/相对地址就地加载；intent:/mailto:/tel: 等拦截掉（WebView 加载会变错误页）
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val scheme = request.url.scheme?.lowercase()
                    return !(scheme == "http" || scheme == "https" || scheme.isNullOrBlank())
                }
            }
            webChromeClient = object : WebChromeClient() {
                // 抑制 JS 对话框：无头场景弹窗依赖 Activity 令牌会崩，自动化也不应被 alert 卡死
                override fun onJsAlert(view: WebView, url: String?, message: String?, result: android.webkit.JsResult): Boolean {
                    result.confirm(); return true
                }
                override fun onJsConfirm(view: WebView, url: String?, message: String?, result: android.webkit.JsResult): Boolean {
                    result.cancel(); return true
                }
                override fun onJsPrompt(view: WebView, url: String?, message: String?, defaultValue: String?, result: android.webkit.JsPromptResult): Boolean {
                    result.cancel(); return true
                }
                override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean = true
            }
        }
    }

    // ---------- 核心动作 ----------

    /** 无头保护（Android 16 平台约束）：界面未开时被遮挡的 WebView 无法提供
     *  可靠的页面状态——read/click 等一律引导先打开内置浏览器界面。 */
    private fun headlessGuard(): String? =
        if (!uiVisible) "内置浏览器界面未打开：后台模式读不到页面（系统限制）。" +
            "请改用 web_fetch；需要完整浏览/交互时请用户打开内置浏览器界面（聊天页顶栏 🌐）。"
        else null


    /** 加载 url：等 onPageFinished（超时 20s）+ 固定渲染余量，返回最终 title/url。 */
    suspend fun navigate(url: String, index: Int? = null): String {
        // 平台说明（2026-08-31 去泊车重构定案）：可见容器内导航（预览面板/全屏）
        // 为主路径；面板唤起失败时降级为 detached + 合成布局导航（
        // 同机实证可推进），不再直接报错。
        if (!uiVisible) {
            // 自动弹出底部预览面板让用户看到浏览过程
            uiOpener?.invoke()
            withTimeoutOrNull(2_500L) {
                while (!uiVisible) kotlinx.coroutines.delay(100)
                true
            }
        }
        return withContext(Dispatchers.Main) {
            touch()
            val tab = (index?.let { tabs.getOrNull(it) } ?: getOrCreateTab())
            // 关键（真机/模拟器日志实证）：面板 uiVisible 置位后，AndroidView 的
            // update 换挂要等下一帧才执行。若此刻就 loadUrl，WebView 仍 detached
            // （attached=false size=0x0），Chromium 启动的导航停在 onPageStarted
            // 永不 Finished——必须先等换挂完成（attached 且有尺寸）再导航。
            val deadline = System.currentTimeMillis() + 3_000L
            while ((tab.webView.parent == null || tab.webView.width <= 0 || tab.webView.height <= 0) &&
                System.currentTimeMillis() < deadline
            ) {
                if (tab.webView.parent == null) ensureLaidOut(tab.webView)
                delay(50)
            }
            val target = withScheme(url)
            tab.loadSignal = CompletableDeferred()
            tab.webView.loadUrl(target)
            // 保活：webview_service（Android 16 多进程 WebView 的 browser 侧进程）
            // 在无交互 ~10s 后被 cached-app freezer 冻结——冻结期间导航的后续步骤
            // （网络请求/onPageFinished）全部停摆。对 WebView 注入成对 ACTION_DOWN/UP
            // （位移 0，对页面无副作用）走输入管线保持活跃，至 onPageFinished 为止。
            val heartbeat = launch {
                var flip = false
                while (tab.loadSignal != null) {
                    delay(4_000)
                    if (tab.loadSignal == null) break
                    runCatching {
                        flip = !flip
                        val x = (tab.webView.width / 2) + if (flip) 1 else 0
                        val y = (tab.webView.height / 2) + if (flip) 1 else 0
                        val now = android.os.SystemClock.uptimeMillis()
                        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x.toFloat(), y.toFloat(), 0)
                        tab.webView.dispatchTouchEvent(down)
                        down.recycle()
                        val up = MotionEvent.obtain(now, now + 40, MotionEvent.ACTION_UP, x.toFloat(), y.toFloat(), 0)
                        tab.webView.dispatchTouchEvent(up)
                        up.recycle()
                    }
                }
            }
            val completed = withTimeoutOrNull(LOAD_TIMEOUT_MS) { tab.loadSignal?.await() } != null
            heartbeat.cancel()
            delay(RENDER_MARGIN_MS)
            bump()
            if (!completed) {
                "页面加载超时（20s）：网络不可达或站点过慢，可稍后重试或换 URL"
            } else {
                "已加载：${tab.title.ifBlank { target }}\nURL：${tab.url.ifBlank { target }}"
            }
        }
    }

    private suspend fun evalJs(wv: WebView, script: String): String = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            wv.evaluateJavascript(script) { cont.resume(it ?: "null") }
        }
    }

    /**
     * 页面结构读取：标记可交互元素编号并返回结构 JSON（title/url/滚动位置/elements）。
     * index 为 null 读活动标签。
     */
    suspend fun readStructure(max: Int = MAX_ELEMENTS, index: Int? = null): String {
        headlessGuard()?.let { return it }
        val wv = webViewAt(index)
        val result = evalJs(wv, "(function(){$MARK_FUNC_JS;return window.__haoaiMark($max);})()")
        return unwrapJsString(result)
    }

    /**
     * 当前页可见链接（预览面板链接条用）：独立 __haoaiLinks JS，只读、不给元素
     * 打 data-haoai-index 编号（避免与模型进行中的 read→click 编号序列竞争），
     * 可见优先排序 + href 去重，返回 JSON 数组 [{"text","href"}]。
     */
    suspend fun collectLinks(max: Int = 10): String {
        if (tabs.isEmpty()) return "[]"
        val wv = webViewAt()
        return unwrapJsString(evalJs(wv, "(function(){$LINKS_FUNC_JS;return window.__haoaiLinks($max);})()"))
    }

    /** 点击编号元素：滚动到可视区 → focus → 完整鼠标事件序列（React 等框架兼容）。 */
    suspend fun clickElement(index: Int, tabIndex: Int? = null): String {
        headlessGuard()?.let { return it }
        val wv = webViewAt(tabIndex)
        val script = """
            (function(){
              var el=document.querySelector('[data-haoai-index="$index"]');
              if(!el)return JSON.stringify({ok:false,reason:'元素不存在，页面可能已变化，请重新 browser_read'});
              el.scrollIntoView({block:'center'});
              el.focus && el.focus();
              function fire(t){var e=new MouseEvent(t,{bubbles:true,cancelable:true,view:window});el.dispatchEvent(e);}
              fire('mousedown');fire('mouseup');el.click();
              return JSON.stringify({ok:true,tag:el.tagName.toLowerCase(),text:(el.innerText||el.value||'').trim().slice(0,60)});
            })()
        """.trimIndent()
        return unwrapJsString(evalJs(wv, script))
    }

    /** 向编号元素输入文本：native setter 兼容 React 受控组件，submit 时提交表单或派发回车。 */
    suspend fun inputText(index: Int, text: String, submit: Boolean, tabIndex: Int? = null): String {
        headlessGuard()?.let { return it }
        val wv = webViewAt(tabIndex)
        val quoted = org.json.JSONObject.quote(text)
        val script = """
            (function(){
              var el=document.querySelector('[data-haoai-index="$index"]');
              if(!el)return JSON.stringify({ok:false,reason:'元素不存在，页面可能已变化，请重新 browser_read'});
              if(!(el.tagName==='INPUT'||el.tagName==='TEXTAREA'||el.isContentEditable))
                return JSON.stringify({ok:false,reason:'元素 '+el.tagName+' 不是输入框，请重新 browser_read 选择'});
              el.scrollIntoView({block:'center'});el.focus();
              var v=$quoted;
              var proto=el.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;
              var d=Object.getOwnPropertyDescriptor(proto,'value');
              if(d&&d.set)d.set.call(el,v);else el.value=v;
              el.dispatchEvent(new Event('input',{bubbles:true}));
              el.dispatchEvent(new Event('change',{bubbles:true}));
              var submitted=false;
              if($submit){
                var f=el.closest&&el.closest('form');
                if(f){ if(f.requestSubmit)f.requestSubmit(); else f.submit(); submitted=true; }
                else{
                  var k={key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true,cancelable:true};
                  el.dispatchEvent(new KeyboardEvent('keydown',k));
                  el.dispatchEvent(new KeyboardEvent('keyup',k));
                  submitted=true;
                }
              }
              return JSON.stringify({ok:true,submitted:submitted,value:(el.value||v).slice(0,40)});
            })()
        """.trimIndent()
        return unwrapJsString(evalJs(wv, script))
    }

    /** 滚动页面：direction=up/down/left/right，amount 为视口比例。 */
    suspend fun scroll(direction: String, amount: Double, tabIndex: Int? = null): String {
        headlessGuard()?.let { return it }
        val wv = webViewAt(tabIndex)
        val a = amount.coerceIn(0.1, 0.9)
        val script = """
            (function(){
              var d='$direction',f=$a,h=window.innerHeight,w=window.innerWidth,before=window.scrollY;
              if(d==='up')window.scrollBy(0,-h*f);
              else if(d==='down')window.scrollBy(0,h*f);
              else if(d==='left')window.scrollBy(w*f,0);
              else if(d==='right')window.scrollBy(-w*f,0);
              else return JSON.stringify({ok:false,reason:'未知方向'});
              return JSON.stringify({ok:true,scrollY:Math.round(window.scrollY),
                maxScroll:Math.round(document.documentElement.scrollHeight-window.innerHeight)});
            })()
        """.trimIndent()
        return unwrapJsString(evalJs(wv, script))
    }

    /**
     * 文本查找：全文档候选节点匹配 → 首个命中滚到屏幕中央 → 重新编号，
     * 返回命中列表（含最邻近可交互元素编号）+ 最新结构，模型直接接着 click。
     */
    suspend fun findText(query: String, max: Int = MAX_ELEMENTS, tabIndex: Int? = null): String {
        headlessGuard()?.let { return it }
        val wv = webViewAt(tabIndex)
        val q = org.json.JSONObject.quote(query)
        val script = """
            (function(){
              $MARK_FUNC_JS
              window.__haoaiMark($max);
              var q=$q.toLowerCase();
              var cands=document.querySelectorAll('a,button,input,textarea,select,summary,h1,h2,h3,h4,h5,h6,p,li,span,label,div');
              var matches=[];
              for(var i=0;i<cands.length&&matches.length<10;i++){
                var el=cands[i];
                var t=(el.innerText||el.value||el.placeholder||'').trim();
                if(!t||t.length>400)continue;
                if(t.toLowerCase().indexOf(q)===-1)continue;
                var r=el.getBoundingClientRect();
                if(r.width===0&&r.height===0)continue;
                var anchor=el.closest?el.closest('[data-haoai-index]'):el;
                var idx=anchor&&anchor.getAttribute?anchor.getAttribute('data-haoai-index'):null;
                matches.push({index:idx===null?null:parseInt(idx),text:t.slice(0,80)});
              }
              if(matches.length&&matches[0].index!==null){
                var tg=document.querySelector('[data-haoai-index="'+matches[0].index+'"]');
                if(tg)tg.scrollIntoView({block:'center'});
              }
              var structure=JSON.parse(window.__haoaiMark($max));
              return JSON.stringify({query:q,found:matches.length,matches:matches,structure:structure});
            })()
        """.trimIndent()
        return unwrapJsString(evalJs(wv, script))
    }

    /** 后退：canGoBack 才退，退后等待加载 + 余量。 */
    suspend fun goBack(tabIndex: Int? = null): String = withContext(Dispatchers.Main) {
        touch()
        val tab = (tabIndex?.let { tabs.getOrNull(it) } ?: getOrCreateTab())
        ensureLaidOut(tab.webView)
        if (!tab.webView.canGoBack()) return@withContext "已在历史最早一页，无法后退"
        tab.loadSignal = CompletableDeferred()
        tab.webView.goBack()
        withTimeoutOrNull(LOAD_TIMEOUT_MS) { tab.loadSignal?.await() }
        delay(RENDER_MARGIN_MS)
        bump()
        "已后退：${tab.title.ifBlank { tab.url }}"
    }

    /**
     * 截图当前页（视口）：软件画布 draw → 压缩 JPEG → data URL。
     * 返回 null 表示无标签或页面尺寸异常。
     */
    suspend fun screenshotDataUrl(maxLongSide: Int = 1024, quality: Int = 72): String? =
        withContext(Dispatchers.Main) {
            headlessGuard()?.let { return@withContext null }
            touch()
            val wv = (activeIndex.value.takeIf { tabs.isNotEmpty() }?.let { tabs.getOrNull(it) } ?: getOrCreateTab()).webView
            ensureLaidOut(wv)
            val w = wv.width
            val h = wv.height
            if (w <= 0 || h <= 0) return@withContext null
            val src = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            wv.draw(Canvas(src))
            val scale = min(1f, maxLongSide.toFloat() / maxOf(w, h))
            val bmp = if (scale < 1f) {
                val out = Bitmap.createScaledBitmap(src, (w * scale).toInt(), (h * scale).toInt(), true)
                if (out !== src) src.recycle()
                out
            } else src
            val bos = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, quality, bos)
            bmp.recycle()
            val b64 = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
            "data:image/jpeg;base64,$b64"
        }

    // ---------- JS ----------

    /**
     * 页面标记函数（每次调用重新编号，幂等）：返回结构 JSON 字符串。
     * 独立成 const 供 read/find 两处拼装；Kotlin raw string 内禁用 JS 模板字符串
     * （${ 会被 Kotlin 插值），一律用 + 拼接。
     */
    private const val MARK_FUNC_JS = """
      window.__haoaiMark=function(max){
        function vis(r){return r.bottom>0&&r.top<window.innerHeight&&r.right>0&&r.left<window.innerWidth&&r.width>1&&r.height>1;}
        var sel='a[href],button,input,textarea,select,[role=button],[role=link],[onclick],[contenteditable="true"],summary';
        var nodes=document.querySelectorAll(sel);
        var items=[];
        for(var i=0;i<nodes.length;i++){
          var el=nodes[i],r=el.getBoundingClientRect(),st=window.getComputedStyle(el);
          if(st.display==='none'||st.visibility==='hidden')continue;
          if(parseFloat(st.opacity||'1')===0)continue;
          if(r.width===0&&r.height===0)continue;
          items.push({el:el,r:r,vis:vis(r)});
        }
        items.sort(function(a,b){
          if(a.vis!==b.vis)return a.vis?-1:1;
          if(a.vis&&b.vis)return (a.r.top-b.r.top)||(a.r.left-b.r.left);
          return 0;
        });
        var els=[],n=Math.min(max,items.length);
        for(var j=0;j<n;j++){
          var it=items[j],e=it.el;
          e.setAttribute('data-haoai-index',j);
          var txt=(e.innerText||e.value||e.placeholder||e.getAttribute('aria-label')||e.title||'').trim().replace(/\s+/g,' ');
          if(txt.length>60)txt=txt.slice(0,60);
          var href=e.getAttribute&&e.getAttribute('href');
          var role=e.getAttribute&&e.getAttribute('role')||'';
          els.push({index:j,tag:e.tagName.toLowerCase(),role:role,text:txt,
            href:((e.tagName==='A'&&href)?href.slice(0,120):''),
            rect:[Math.round(it.r.left),Math.round(it.r.top),Math.round(it.r.width),Math.round(it.r.height)]});
        }
        return JSON.stringify({title:document.title,url:location.href,
          scrollY:Math.round(window.scrollY),
          pageHeight:Math.round(document.documentElement.scrollHeight),
          viewport:[window.innerWidth,window.innerHeight],
          totalInteractive:items.length,elements:els});
      };
    """

    /**
     * 链接提取（预览面板链接条）：与 __haoaiMark 分离——只读不编号，页面绝对
     * http(s) 链接，可见优先排序 + href 去重；Kotlin raw string 内 JS 用 + 拼接。
     */
    private const val LINKS_FUNC_JS = """
      window.__haoaiLinks=function(max){
        var nodes=document.querySelectorAll('a[href]'),seen={},out=[];
        var items=[];
        for(var i=0;i<nodes.length;i++){
          var el=nodes[i],r=el.getBoundingClientRect(),st=window.getComputedStyle(el);
          if(st.display==='none'||st.visibility==='hidden')continue;
          if(r.width===0&&r.height===0)continue;
          var h=el.href||'';   // property 取绝对 URL（相对 href 由浏览器解析）；attribute 会拿到相对路径
          if(h.indexOf('http')!==0)continue;
          var txt=(el.innerText||el.getAttribute('aria-label')||el.title||'').trim().replace(/\s+/g,' ');
          if(!txt)txt=h.replace(/^https?:\/\//,'').slice(0,30);
          if(txt.length>24)txt=txt.slice(0,24);
          items.push({el:el,txt:txt,h:h,vis:r.bottom>0&&r.top<window.innerHeight});
        }
        items.sort(function(a,b){
          if(a.vis!==b.vis)return a.vis?-1:1;
          return 0;
        });
        for(var j=0;j<items.length&&out.length<max;j++){
          if(seen[items[j].h])continue;
          seen[items[j].h]=1;
          out.push({text:items[j].txt,href:items[j].h});
        }
        return JSON.stringify(out);
      };
    """

    /** evaluateJavascript 的回调把 JS 返回值包成 JSON 字符串字面量，这里剥壳。 */
    private fun unwrapJsString(raw: String): String =
        runCatching {
            val s = org.json.JSONTokener(raw).nextValue()
            if (s is String) s else raw
        }.getOrDefault(raw)
}
