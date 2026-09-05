package com.haoai.agent.platform.a11y

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

class HaoAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: HaoAccessibilityService? = null
            private set

        fun connected(): Boolean = instance != null

        fun enableHint(): String =
            "无障碍服务未启用。请到系统「设置 → 无障碍 → HaoAI」开启后重试。"

        /** 最近一次 dump 的索引化控件缓存，供按 index 点击。 */
        @Volatile
        var lastDump: List<IndexedNode> = emptyList()

        @Volatile
        var lastEventAt: Long = 0L
    }

    // ---------- 4.3 无障碍适配模式（TalkBack 等环境下自动钳制行为） ----------

    private var talkBackCheckedAt = 0L
    private var talkBackActiveNow = false

    /** 适配模式生效条件：设置页开关 ∧ TalkBack 等朗读无障碍服务确在运行（5s 缓存）。 */
    fun adaptiveModeOn(): Boolean {
        val enabled = runCatching {
            (application as? com.haoai.agent.HaoApplication)?.container?.settingsFlow?.value?.a11yAdaptiveMode
                ?: false
        }.getOrDefault(false)
        if (!enabled) return false
        val now = System.currentTimeMillis()
        if (now - talkBackCheckedAt > 5000) {
            talkBackCheckedAt = now
            talkBackActiveNow = runCatching {
                android.provider.Settings.Secure.getString(
                    contentResolver, "enabled_accessibility_services"
                )?.contains("talkback", ignoreCase = true) ?: false
            }.getOrDefault(false)
        }
        return talkBackActiveNow
    }

    /** 朗读步骤（TalkBack 自动朗读 toast）；适配模式下的操作可感知性。 */
    private fun announce(msg: String) {
        runCatching { android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show() }
    }

    data class IndexedNode(
        val index: Int,
        val cls: String,
        val label: String,
        val viewId: String?,
        val cx: Int,
        val cy: Int,
        val clickable: Boolean,
        val editable: Boolean,
        val scrollable: Boolean
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event != null) lastEventAt = System.currentTimeMillis()
        // 窗口切换后旧 dump 的坐标已失效，清空缓存防止 tap(index) 点到旧界面
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            lastDump = emptyList()
        }
    }

    override fun onInterrupt() = Unit

    /** 界面是否已稳定（[quietMs] 内无无障碍事件）。 */
    fun isIdle(quietMs: Long = 800L): Boolean =
        System.currentTimeMillis() - lastEventAt >= quietMs

    /**
     * 索引化遍历（dumpIndexed 与虚拟屏 dump 共用编号规则：DFS、可见、
     * 可交互或有文本、maxNodes 截断），保证同一界面两次遍历编号一致。
     */
    private fun numberedWalk(
        root: AccessibilityNodeInfo?,
        maxNodes: Int,
        visit: (AccessibilityNodeInfo, Rect, Int, String) -> Unit
    ) {
        var count = 0
        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null || count >= maxNodes) return
            if (!node.isVisibleToUser) return
            val rect = Rect().also { node.getBoundsInScreen(it) }
            val label = listOfNotNull(
                node.text?.toString()?.takeIf { it.isNotBlank() },
                node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
            ).joinToString(" / ")
            val interesting = node.isClickable || node.isEditable || node.isScrollable || label.isNotBlank()
            if (interesting && rect.width() > 0) {
                visit(node, rect, count, label)
                count++
            }
            for (i in 0 until node.childCount) walk(node.getChild(i))
        }
        walk(root)
    }

    /**
     * 索引化屏幕 dump：只列可交互/有文本的控件，每行带 [index]，
     * 同时缓存到 lastDump 供 tap(index=…) 使用。
     */
    fun dumpIndexed(maxNodes: Int = 80): String {
        val root = instance?.rootInActiveWindow
            ?: return "无法获取当前窗口内容（服务未连接或目标窗口不可读取）"
        val out = StringBuilder()
        val cache = mutableListOf<IndexedNode>()
        numberedWalk(root, maxNodes) { node, rect, idx, label ->
            val cls = node.className?.toString()?.substringAfterLast('.') ?: "View"
            val id = node.viewIdResourceName?.substringAfter('/')
            val flags = buildList {
                if (node.isClickable) add("点击")
                if (node.isEditable) add("输入")
                if (node.isScrollable) add("滚动")
            }
            out.append('[').append(idx).append("] ")
                .append(cls).append(" \"").append(label.ifBlank { "«空»" }).append('"')
            id?.let { out.append(" id=").append(it) }
            out.append(" (").append(rect.centerX()).append(',').append(rect.centerY()).append(')')
            if (flags.isNotEmpty()) out.append(" <").append(flags.joinToString(",")).append('>')
            out.append('\n')
            cache.add(IndexedNode(idx, cls, label, id, rect.centerX(), rect.centerY(), node.isClickable, node.isEditable, node.isScrollable))
        }
        lastDump = cache
        return if (cache.isEmpty()) "当前窗口无可交互控件" else
            "共 ${cache.size} 个控件（用 tap 的 index 参数直接点击）：\n" + out.toString().trimEnd()
    }

    fun nodeByIndex(index: Int): IndexedNode? = lastDump.find { it.index == index }

    fun dumpScreen(maxNodes: Int = 120): String {
        val root = instance?.rootInActiveWindow
            ?: return "无法获取当前窗口内容（服务未连接或目标窗口不可读取）"
        val sb = StringBuilder()
        var count = 0
        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || count >= maxNodes || depth > 14) return
            if (!node.isVisibleToUser) return
            val rect = Rect().also { node.getBoundsInScreen(it) }
            val label = listOfNotNull(
                node.text?.toString()?.takeIf { it.isNotBlank() },
                node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
            ).joinToString(" / ")
            val cls = node.className?.toString()?.substringAfterLast('.') ?: "View"
            val flags = buildString {
                if (node.isClickable) append(" clickable")
                if (node.isEditable) append(" editable")
                if (node.isScrollable) append(" scrollable")
            }
            val id = node.viewIdResourceName?.substringAfter('/')?.let { " id=$it" } ?: ""
            sb.append("  ".repeat(depth))
                .append("[$cls] ")
                .append(if (label.isNotEmpty()) "\"$label\"" else "«空»")
                .append(id)
                .append(" (${rect.centerX()},${rect.centerY()})")
                .append(flags)
                .append('\n')
            count++
            for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)
        }
        walk(root, 0)
        return if (sb.isEmpty()) "当前窗口无可读节点" else sb.toString().trimEnd()
    }

    fun findNode(text: String? = null, viewId: String? = null): AccessibilityNodeInfo? {
        val root = instance?.rootInActiveWindow ?: return null
        if (!text.isNullOrBlank()) {
            val exact = root.findAccessibilityNodeInfosByText(text)
                ?.firstOrNull { it.text?.toString()?.contains(text) == true }
            if (exact != null) return exact
            return findByDfs(root) { n ->
                n.text?.toString()?.contains(text) == true ||
                    n.contentDescription?.toString()?.contains(text) == true
            }
        }
        if (!viewId.isNullOrBlank()) {
            val pkg = root.packageName?.toString() ?: return null
            val full = if (viewId.contains('/')) viewId else "$pkg:id/$viewId"
            root.findAccessibilityNodeInfosByViewId(full)?.firstOrNull()?.let { return it }
            return findByDfs(root) { n -> n.viewIdResourceName == full }
        }
        return null
    }

    private fun findByDfs(
        node: AccessibilityNodeInfo,
        pred: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        if (pred(node)) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val hit = findByDfs(child, pred)
            if (hit != null) return hit
        }
        return null
    }

    fun clickByText(text: String, longPress: Boolean = false): String {
        val node = findNode(text = text) ?: return "未找到包含「$text」的控件"
        var target: AccessibilityNodeInfo = node
        var depth = 0
        while (!target.isClickable && target.parent != null && depth < 6) {
            target = target.parent
            depth++
        }
        val rect = Rect().also { node.getBoundsInScreen(it) }
        if (!longPress && target.isClickable && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            if (adaptiveModeOn()) announce("已点击「$text」")
            return "已点击「$text」"
        }
        return if (longPress && longPress(rect.centerX(), rect.centerY()))
            "已长按「$text」"
        else if (tapScreen(rect.centerX(), rect.centerY()))
            "已坐标点击「$text」"
        else if (adaptiveModeOn())
            "点击失败：无障碍适配模式已启用，控件不可点击时跳过手势注入（避免与 TalkBack 冲突）"
        else "点击失败：控件不可点击且手势注入失败"
    }

    fun clickById(viewId: String): String {
        val node = findNode(viewId = viewId) ?: return "未找到 id=$viewId 的控件"
        if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            if (adaptiveModeOn()) announce("已点击 id=$viewId")
            return "已点击 id=$viewId"
        }
        val rect = Rect().also { node.getBoundsInScreen(it) }
        return if (tapScreen(rect.centerX(), rect.centerY())) "已坐标点击 id=$viewId"
        else if (adaptiveModeOn()) "点击失败：无障碍适配模式已启用，该控件无点击语义，已跳过手势注入"
        else "点击失败"
    }

    fun tapScreen(x: Int, y: Int): Boolean {
        // 适配模式：手势会被 TalkBack 接管（滑动手势触发焦点导航），一律走节点语义
        if (adaptiveModeOn()) return false
        val svc = instance ?: return false
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        return svc.dispatchGesture(gesture, null, null)
    }

    fun longPress(x: Int, y: Int, durationMs: Int = 600): Boolean {
        if (adaptiveModeOn()) return false
        val svc = instance ?: return false
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs.toLong().coerceIn(300, 3000)))
            .build()
        return svc.dispatchGesture(gesture, null, null)
    }

    fun screenWidth(): Int = resources.displayMetrics.widthPixels

    fun screenHeight(): Int = resources.displayMetrics.heightPixels

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): Boolean {
        if (adaptiveModeOn()) return false
        val svc = instance ?: return false
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs.toLong().coerceIn(80, 3000)))
            .build()
        return svc.dispatchGesture(gesture, null, null)
    }

    fun typeText(text: String): String {
        val svc = instance ?: return enableHint()
        val root = svc.rootInActiveWindow ?: return enableHint()
        // 优先当前输入焦点，其次带焦点的输入框；DFS 第一个 editable 会把密码填进账号框
        val target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: findByDfs(root) { it.isEditable && it.isFocused }
            ?: findByDfs(root) { it.isEditable }
            ?: return "当前屏幕没有可输入的焦点框"
        target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return if (target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args))
            "已在输入框填入文本"
        else "填入文本失败"
    }

    fun pressKey(action: String): String {
        val svc = instance ?: return enableHint()
        val global = when (action.lowercase()) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS
            else -> return "未知按键：$action（支持 back/home/recents/notifications/quick_settings）"
        }
        return if (svc.performGlobalAction(global)) "已执行 $action" else "执行 $action 失败"
    }

    fun launchApp(packageName: String): String {
        val pm = packageManager
        val intent = pm.getLaunchIntentForPackage(packageName)
            ?: return "未安装应用：$packageName"
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            startActivity(intent)
            "已启动 $packageName"
        }.getOrElse { "启动失败：${it.message}" }
    }

    /** launch_app 扩展：指定应用内页面 + 字符串键值 extras；activity 为空回退首页。 */
    fun launchComponent(packageName: String, activity: String?, extras: android.os.Bundle?): String {
        val intent = if (activity.isNullOrBlank()) {
            packageManager.getLaunchIntentForPackage(packageName)
                ?: return "未安装应用：$packageName"
        } else {
            val cls = if (activity.startsWith(".")) packageName + activity else activity
            Intent().setClassName(packageName, cls)
        }
        extras?.let { intent.putExtras(it) }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            startActivity(intent)
            "已启动 $packageName${activity?.let { "/$it" } ?: ""}"
        }.getOrElse { "启动失败：${it.message}" }
    }

    /**
     * open_uri（直达导航）：把三种形态归一为 Intent 以前台豁免启动。
     * 无障碍服务持有后台启动 Activity 的能力，可在本 App 不在前台时拉起目标页——
     * 「打开无线调试/蓝牙设置/某网页」类任务一步直达，省掉 screen→tap 视觉循环。
     * 形态约定：
     *  - 含 ':' → 有协议，按数据 URI 走 ACTION_VIEW（https/geo/market/tel 等）；
     *    intent: 开头用系统解析器展开成目标 Intent
     *  - 无 ':' 含 '/' → "包名/类名" 指名组件（相对类名自动补包名前缀）
     *  - 两者皆无 → 视为 Android action（android.settings.* / android.intent.action.*）
     * 失败时回显系统异常描述（如 ActivityNotFound），模型可据此换路径。
     */
    fun openUri(uri: String): String {
        val s = uri.trim()
        if (s.isEmpty()) return "缺少 uri"
        val intent = runCatching {
            when {
                s.startsWith("intent:") ->
                    Intent.parseUri(s, Intent.URI_INTENT_SCHEME)
                s.contains(':') ->
                    Intent(Intent.ACTION_VIEW, Uri.parse(s))
                s.contains('/') -> {
                    val pkg = s.substringBefore('/')
                    val cls = s.substringAfter('/').let { if (it.startsWith(".")) pkg + it else it }
                    Intent().setClassName(pkg, cls)
                }
                else ->
                    Intent(s)
            }
        }.getOrElse { return "无法解析 uri：$s（${it.message}）" }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.action.isNullOrEmpty() && intent.component == null) intent.action = Intent.ACTION_VIEW
        return runCatching {
            android.util.Log.i("OpenUri", "startActivity act=${intent.action} cmp=${intent.component} data=${intent.data}")
            startActivity(intent)
            "已打开 $s"
        }.getOrElse {
            android.util.Log.w("OpenUri", "startActivity failed for $s", it)
            "打开失败：${it.javaClass.simpleName}${it.message?.let { m -> "：$m" } ?: ""}"
        }
    }

    fun listApps(limit: Int = 60): String {
        val pm = packageManager
        val apps = pm.getInstalledApplications(0)
            .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
            .mapNotNull { app ->
                pm.getApplicationLabel(app)?.toString()?.let { "$it (${app.packageName})" }
            }
            .sorted()
        return "共 ${apps.size} 个应用：\n" + apps.take(limit).joinToString("\n") { "- $it" }
    }

    // ---------- 4.3 虚拟屏（API 30+ 多显示器无障碍，节点操作不依赖触摸注入） ----------

    /** 多显示器无障碍通道是否可用（getWindowsOnAllDisplays 自 API 30 引入）。 */
    fun displaySupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    /** 指定屏上最佳应用窗口的根节点（活跃 > 聚焦 > 第一个）。 */
    private fun displayRoot(displayId: Int): AccessibilityNodeInfo? {
        if (!displaySupported()) return null
        val svc = instance ?: return null
        // getWindowsOnAllDisplays 返回按 displayId 分组的 SparseArray<List<...>>
        val byDisplay = runCatching { svc.windowsOnAllDisplays }.getOrNull() ?: return null
        val windows: List<AccessibilityWindowInfo> = byDisplay[displayId] ?: return null
        val onDisplay = windows
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        val win = onDisplay.firstOrNull { it.isActive }
            ?: onDisplay.firstOrNull { it.isFocused }
            ?: onDisplay.firstOrNull()
        return win?.root
    }

    /** 指定屏上是否存在目标应用的应用窗口（诊断虚拟屏重挂载：部分 ROM 把窗口挂回主屏）。 */
    fun targetAppWindowOnDisplay(displayId: Int, targetPkg: String): Boolean {
        if (!displaySupported()) return false
        val svc = instance ?: return false
        val byDisplay = runCatching { svc.windowsOnAllDisplays }.getOrNull() ?: return false
        val windows = byDisplay[displayId]?.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            ?: return false
        if (targetPkg.isNotBlank()) {
            val hit = windows.any { runCatching { it.root?.packageName?.toString() == targetPkg }.getOrDefault(false) }
            if (!hit) {
                // 误报探针：列出系统真正注册的窗口（displayId/包名/类型），供 miss 时对照。
                // 写文件日志（本 ROM logcat 被抑制，android.util.Log 不可见）
                val sb = StringBuilder()
                for (i in 0 until byDisplay.size()) {
                    sb.append(byDisplay.keyAt(i)).append("=[")
                    sb.append(byDisplay.valueAt(i).joinToString(",") { w ->
                        "${runCatching { w.root?.packageName?.toString() }.getOrNull() ?: "?"}:${w.type}"
                    })
                    sb.append("];")
                }
                com.haoai.agent.platform.vdisplay.VirtualScreenController
                    .debugLog("a11y window miss d=$displayId target=$targetPkg actual: $sb")
            }
            return hit
        }
        // URL 目标：排除系统 launcher 后任意应用窗口即可（浏览器在虚拟屏上）
        val launcherPkg = runCatching {
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            packageManager.queryIntentActivities(home, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
                .firstOrNull()?.activityInfo?.packageName
        }.getOrNull() ?: ""
        return windows.any { it.root?.packageName?.toString()?.takeIf { p -> p != launcherPkg } != null }
    }

    /** 虚拟屏索引化 dump：编号规则与 dumpIndexed 完全一致（共用 numberedWalk）。 */
    fun dumpIndexedOnDisplay(displayId: Int, maxNodes: Int = 80): String? {
        if (!displaySupported()) return null
        val root = displayRoot(displayId) ?: return null
        val out = StringBuilder()
        var count = 0
        numberedWalk(root, maxNodes) { node, rect, idx, label ->
            val cls = node.className?.toString()?.substringAfterLast('.') ?: "View"
            val id = node.viewIdResourceName?.substringAfter('/')
            val flags = buildList {
                if (node.isClickable) add("点击")
                if (node.isEditable) add("输入")
                if (node.isScrollable) add("滚动")
            }
            out.append('[').append(idx).append("] ")
                .append(cls).append(" \"").append(label.ifBlank { "«空»" }).append('"')
            id?.let { out.append(" id=").append(it) }
            out.append(" (").append(rect.centerX()).append(',').append(rect.centerY()).append(')')
            if (flags.isNotEmpty()) out.append(" <").append(flags.joinToString(",")).append('>')
            out.append('\n')
            count++
        }
        return if (count == 0) "虚拟屏窗口无可交互控件" else
            "共 $count 个控件（用 vscreen_tap 的 index 参数直接点击）：\n" + out.toString().trimEnd()
    }

    /** 按编号重遍历取活节点：编号确定性来自共用遍历，界面变了需先重新 dump。 */
    private fun displayNodeAt(displayId: Int, index: Int): AccessibilityNodeInfo? {
        val root = displayRoot(displayId) ?: return null
        var hit: AccessibilityNodeInfo? = null
        numberedWalk(root, 200) { node, _, idx, _ ->
            if (hit == null && idx == index) hit = node
        }
        return hit
    }

    /** 点击（不可点节点向上找 6 层可点祖先，与主屏 clickByText 同策略）。返回成功与目标矩形（虚拟屏坐标）。 */
    fun clickOnDisplay(displayId: Int, index: Int): Pair<Boolean, Rect?> {
        val node = displayNodeAt(displayId, index) ?: return false to null
        var target: AccessibilityNodeInfo = node
        var depth = 0
        while (!target.isClickable && target.parent != null && depth < 6) {
            target = target.parent
            depth++
        }
        val rect = Rect().also { node.getBoundsInScreen(it) }
        val ok = target.isClickable && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (ok && adaptiveModeOn()) announce("已点击虚拟屏控件 [$index]")
        return ok to rect
    }

    fun textOnDisplay(displayId: Int, index: Int, text: String): Pair<Boolean, Rect?> {
        val node = displayNodeAt(displayId, index) ?: return false to null
        if (!node.isEditable) node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        return ok to Rect().also { node.getBoundsInScreen(it) }
    }

    /** 滚动：index 给定滚该节点，否则找屏上第一个可滚容器（节点滚动无需手势注入）。 */
    fun scrollOnDisplay(displayId: Int, index: Int?, forward: Boolean): String {
        val node = if (index != null) displayNodeAt(displayId, index) else firstScrollableOnDisplay(displayId)
        if (node == null) {
            return if (index != null) "index=$index 不存在，请重新 vscreen_screen 获取编号"
            else "虚拟屏未找到可滚动容器（精确手势注入未启用）"
        }
        val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        return if (node.performAction(action)) "已滚动"
        else "该控件不支持节点滚动（精确手势注入未启用）"
    }

    private fun firstScrollableOnDisplay(displayId: Int): AccessibilityNodeInfo? {
        val root = displayRoot(displayId) ?: return null
        fun walk(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.isScrollable && node.isVisibleToUser) return node
            for (i in 0 until node.childCount) {
                walk(node.getChild(i))?.let { return it }
            }
            return null
        }
        return walk(root)
    }

    /**
     * 虚拟屏返回：performGlobalAction 无屏幕维度（会动到用户主屏），
     * 因此只点界面内的返回/导航控件，找不到就明确报受限。
     */
    fun backOnDisplay(displayId: Int): String {
        val root = displayRoot(displayId) ?: return "读不到虚拟屏窗口"
        val patterns = listOf("返回", "back", "navigate up", "转到上", "上一层级", "arrow_back")
        var hit: AccessibilityNodeInfo? = null
        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null || hit != null) return
            val label = listOfNotNull(
                node.text?.toString(),
                node.contentDescription?.toString(),
                node.viewIdResourceName?.toString()
            ).joinToString(" ").lowercase()
            if (node.isVisibleToUser && node.isClickable && patterns.any { label.contains(it) }) {
                hit = node
                return
            }
            for (i in 0 until node.childCount) walk(node.getChild(i))
        }
        walk(root)
        val n = hit ?: return "虚拟屏未找到返回控件（全局返回键无屏幕维度，不会代按以免打断用户主屏）；请用 vscreen_screen 找界面上的返回/关闭按钮 tap 它"
        return if (n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) "已点击界面内返回控件" else "返回控件点击失败"
    }
}
