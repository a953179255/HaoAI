package com.haoai.agent.platform.a11y

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

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
    }

    override fun onInterrupt() = Unit

    /** 界面是否已稳定（[quietMs] 内无无障碍事件）。 */
    fun isIdle(quietMs: Long = 800L): Boolean =
        System.currentTimeMillis() - lastEventAt >= quietMs

    /**
     * 索引化屏幕 dump：只列可交互/有文本的控件，每行带 [index]，
     * 同时缓存到 lastDump 供 tap(index=…) 使用。
     */
    fun dumpIndexed(maxNodes: Int = 80): String {
        val root = instance?.rootInActiveWindow
            ?: return "无法获取当前窗口内容（服务未连接或目标窗口不可读取）"
        val out = StringBuilder()
        val cache = mutableListOf<IndexedNode>()
        var idx = 0
        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null || cache.size >= maxNodes) return
            if (!node.isVisibleToUser) return
            val rect = Rect().also { node.getBoundsInScreen(it) }
            val label = listOfNotNull(
                node.text?.toString()?.takeIf { it.isNotBlank() },
                node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
            ).joinToString(" / ")
            val clickable = node.isClickable
            val editable = node.isEditable
            val scrollable = node.isScrollable
            val interesting = clickable || editable || scrollable || label.isNotBlank()
            if (interesting && rect.width() > 0) {
                val cls = node.className?.toString()?.substringAfterLast('.') ?: "View"
                val id = node.viewIdResourceName?.substringAfter('/')
                val flags = buildList {
                    if (clickable) add("点击")
                    if (editable) add("输入")
                    if (scrollable) add("滚动")
                }
                out.append('[').append(idx).append("] ")
                    .append(cls).append(" \"").append(label.ifBlank { "«空»" }).append('"')
                id?.let { out.append(" id=").append(it) }
                out.append(" (").append(rect.centerX()).append(',').append(rect.centerY()).append(')')
                if (flags.isNotEmpty()) out.append(" <").append(flags.joinToString(",")).append('>')
                out.append('\n')
                cache.add(IndexedNode(idx, cls, label, id, rect.centerX(), rect.centerY(), clickable, editable, scrollable))
                idx++
            }
            for (i in 0 until node.childCount) walk(node.getChild(i))
        }
        walk(root)
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
            return "已点击「$text」"
        }
        return if (longPress && longPress(rect.centerX(), rect.centerY()))
            "已长按「$text」"
        else if (tapScreen(rect.centerX(), rect.centerY()))
            "已坐标点击「$text」"
        else "点击失败：控件不可点击且手势注入失败"
    }

    fun clickById(viewId: String): String {
        val node = findNode(viewId = viewId) ?: return "未找到 id=$viewId 的控件"
        if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return "已点击 id=$viewId"
        val rect = Rect().also { node.getBoundsInScreen(it) }
        return if (tapScreen(rect.centerX(), rect.centerY())) "已坐标点击 id=$viewId" else "点击失败"
    }

    fun tapScreen(x: Int, y: Int): Boolean {
        val svc = instance ?: return false
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        return svc.dispatchGesture(gesture, null, null)
    }

    fun longPress(x: Int, y: Int, durationMs: Int = 600): Boolean {
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
        val target = findByDfs(svc.rootInActiveWindow ?: return enableHint()) { it.isEditable }
            ?: return "当前屏幕没有可输入的焦点框"
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
}
