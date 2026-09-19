package com.haoai.agent.ui.common

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.BackgroundColorSpan
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.view.View
import android.widget.TextView
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.text.Selection
import android.text.Editable
import java.text.BreakIterator

/**
 * 段落级原生 TextView 选择渲染（2026-09-19）：
 * Compose SelectionContainer 的长按拖选在 CJK 上有词边界吸附缺陷（选区反向延伸/
 * 跳行，纯净 demo 复现，1.11-1.13alpha 同病，upstream 未修），且取消选择时系统
 * 浮层有"全选"闪现竞态——两者都在 Compose 选择系统内部（internal，无开关）。
 *
 * 架构：TextView 只负责渲染 Spannable 与显示选区/把手/ActionMode；
 * **触摸完全由外层 Compose 手势驱动**：
 * - TextView 的 onTouchEvent 一律返回 false → 手指落在段落上滑动时列表照常滚动
 *   （textIsSelectable 的 TextView 默认吞掉整个手势序列，列表会滚不动——实测坑）；
 * - 外层 detectDragGesturesAfterLongPress 检测长按 → 程序化选词（平台 BreakIterator）
 *   → 拖动逐字符扩展选区（无词吸附，中文跟手）→ 松手 performLongClick 弹系统浮层；
 * - 链接点击走外层 tap → 命中 offset 查 ClickableSpan → openLink；
 * - 把手拖动是 TextView Popup 内的独立触摸序列，原生工作不受影响。
 */

/** MdInline 行内树 → SpannableStringBuilder（样式对齐 Compose 版 buildInlineTyped）。 */
@Suppress("unused")
internal fun buildInlineSpannable(
    inlines: List<MdInline>,
    context: android.content.Context,
    textColor: Int,
    primaryColor: Int,
    onSurface: Int,
    inlineCodeColor: Int,
    openLink: (String) -> Unit
): SpannableStringBuilder {
    val sb = SpannableStringBuilder()

    fun emit(list: List<MdInline>) {
        for (node in list) {
            when (node) {
                is MdInline.Run -> if (node.text.isNotEmpty()) sb.append(node.text)
                is MdInline.Strong -> {
                    val start = sb.length
                    emit(node.children)
                    sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                is MdInline.Emph -> {
                    val start = sb.length
                    emit(node.children)
                    sb.setSpan(StyleSpan(Typeface.ITALIC), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                is MdInline.Del -> {
                    val start = sb.length
                    emit(node.children)
                    sb.setSpan(StrikethroughSpan(), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                is MdInline.CodeSpan -> {
                    val start = sb.length
                    sb.append(node.code)
                    // 行内代码：等宽 + 蓝字 + 浅底（与 Compose 版视觉对齐；Background 无圆角为已知小差异）
                    sb.setSpan(TypefaceSpan("monospace"), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(RelativeSizeSpan(0.86f), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(ForegroundColorSpan(inlineCodeColor), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(BackgroundColorSpan(onSurface), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                is MdInline.MathSpan -> {
                    // 选择文本块内公式降级为衬线斜体源码（与 Compose 侧降级路径一致）
                    val start = sb.length
                    sb.append(node.latex)
                    sb.setSpan(TypefaceSpan("serif"), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(StyleSpan(Typeface.ITALIC), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                is MdInline.Link -> {
                    val start = sb.length
                    emit(node.children)
                    if (sb.length > start) {
                        val url = node.url
                        sb.setSpan(
                            object : ClickableSpan() {
                                override fun onClick(widget: View) {
                                    if (url.isNotEmpty()) openLink(url)
                                }

                                override fun updateDrawState(ds: TextPaint) {
                                    ds.color = primaryColor
                                    ds.isUnderlineText = true
                                }
                            },
                            start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                    }
                }
                is MdInline.Image -> {
                    val start = sb.length
                    sb.append("🖼 ").append(node.alt.ifBlank { "图片" })
                    sb.setSpan(ForegroundColorSpan(primaryColor), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(StyleSpan(Typeface.ITALIC), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
        }
    }

    emit(inlines)
    return sb
}

/** 标题层级 → 相对正文的字号倍率（对齐 Material3 typography 的视觉层级）。 */
internal fun headingSizeFactor(heading: Int): Float = when (heading) {
    1 -> 1.55f
    2 -> 1.4f
    3 -> 1.15f
    else -> 1f
}

/**
 * 手势驱动的可选择 TextView：
 * - onTouchEvent 一律返回 false：段落永不吞列表滚动手势；
 * - 选择由外层 Compose 手势驱动（见 [selectWordAt]/[extendSelectionTo]）；
 * - 把手拖动是 Popup 内独立触摸序列，原生工作。
 */
internal class GestureDrivenTextView(context: android.content.Context) : TextView(context) {

    /** 当前选区浮层（tap 已选文本时关闭）。 */
    var activeActionMode: android.view.ActionMode? = null

    init {
        setTextIsSelectable(true) // 选区/把手/ActionMode 渲染管线
        // 不挂 movementMethod：链接点击由外层 tap 查 ClickableSpan（DOWN 放行
        // 策略下 movement 的触摸路径本就不可达）
        highlightColor = 0x4034D399.toInt()
        gravity = android.view.Gravity.START
        setPadding(0, 0, 0, 0)
        includeFontPadding = false
        // textIsSelectable 会置 clickable 使 super.onTouchEvent 消费 DOWN——
        // 显式降回 clickable（配合 override 的恒 false 放行）；longClickable 保留：
        // performLongClick 启动选择 ActionMode 依赖它（长按检测由外层手势负责）
        isClickable = false
        isLongClickable = true
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun onTouchEvent(event: android.view.MotionEvent): Boolean = false

    /** 选区写入：text 为 EDITABLE buffer（见 update 的 setText），走 Selection 静态 API。 */
    private fun setSelectionRange(start: Int, end: Int) {
        (text as? Editable)?.let { Selection.setSelection(it, start.coerceAtMost(end), end.coerceAtLeast(start)) }
    }

    /** 长按起点：选中 offset 所在词（平台 BreakIterator 词边界，与原生选词一致）。 */
    fun selectWordAt(x: Float, y: Float): Pair<Int, Int> {
        val len = text?.length ?: 0
        if (len == 0) return 0 to 0
        val offset = runCatching { getOffsetForPosition(x, y) }.getOrNull()?.coerceIn(0, len) ?: 0
        val (start, end) = wordRangeAt(text.toString(), offset)
        if (start < end) setSelectionRange(start, end)
        return start to end
    }

    /** 拖动扩选：字符级跟随手指（无词吸附，CJK 跟手）。锚点在长按选词时固定。 */
    fun extendSelectionTo(x: Float, y: Float, anchorStart: Int, anchorEnd: Int) {
        val len = text?.length ?: 0
        if (len == 0) return
        val offset = runCatching { getOffsetForPosition(x, y) }.getOrNull()?.coerceIn(0, len) ?: return
        when {
            offset >= anchorEnd -> setSelectionRange(anchorStart, offset)
            offset <= anchorStart -> setSelectionRange(offset, anchorEnd)
            else -> setSelectionRange(anchorStart, anchorEnd)
        }
    }

    /**
     * 选区浮层：自启 floating ActionMode（复制/全选）。不走 performLongClick——
     * 外部驱动选区时其内部定位链路（依赖原生长按状态）在 Compose 窗口下浮层不可见。
     */
    fun showSelectionToolbar() {
        activeActionMode?.finish()
        val callback = object : android.view.ActionMode.Callback2() {
            override fun onCreateActionMode(mode: android.view.ActionMode, menu: android.view.Menu): Boolean {
                menu.add(0, 1, 0, "复制")
                menu.add(0, 2, 1, "全选")
                return true
            }

            override fun onPrepareActionMode(mode: android.view.ActionMode, menu: android.view.Menu): Boolean = false

            override fun onActionItemClicked(mode: android.view.ActionMode, item: android.view.MenuItem): Boolean {
                val sel = text?.substring(selectionStart.coerceAtLeast(0), selectionEnd.coerceAtMost(text?.length ?: 0)).orEmpty()
                when (item.itemId) {
                    1 -> {
                        val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                            as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("text", sel))
                        android.widget.Toast.makeText(context, "已复制所选文本", android.widget.Toast.LENGTH_SHORT).show()
                        mode.finish()
                    }
                    2 -> {
                        val len = text?.length ?: 0
                        setSelectionRange(0, len)
                        // 保持浮层开启供继续操作
                    }
                }
                return true
            }

            override fun onDestroyActionMode(mode: android.view.ActionMode) {
                if (activeActionMode === mode) activeActionMode = null
            }

            // 浮层定位到选区中点上方（Callback2 的坐标系是 View 本地）
            override fun onGetContentRect(mode: android.view.ActionMode, view: View, outRect: android.graphics.Rect) {
                val s = selectionStart.coerceAtLeast(0)
                val e = selectionEnd.coerceAtMost(text?.length ?: 0)
                if (s < e && layout != null) {
                    val l = layout.getLineForOffset(s)
                    outRect.set(
                        layout.getPrimaryHorizontal(s).toInt(),
                        layout.getLineTop(l),
                        layout.getPrimaryHorizontal((e - 1).coerceAtLeast(s)).toInt(),
                        layout.getLineBottom(l)
                    )
                } else outRect.set(0, 0, width, height)
            }
        }
        startActionMode(callback, android.view.ActionMode.TYPE_FLOATING)
    }

    /** 取消选择（选区归零 + 失焦），由外层 tap 已选段落时调用。 */
    fun clearTextSelection() {
        val len = text?.length ?: 0
        if (hasSelection() && text is Editable) setSelectionRange(len, len)
        clearFocus()
    }

    /** 返回 offset 所在词的 [start, end)；空隙/标点保底 [offset, offset+1)。 */
    private fun wordRangeAt(text: String, offset: Int): Pair<Int, Int> {
        val iter = BreakIterator.getWordInstance(java.util.Locale.getDefault())
        iter.setText(text)
        val safe = offset.coerceIn(0, text.length)
        var start = if (iter.isBoundary(safe)) safe else iter.preceding(safe)
        if (start == BreakIterator.DONE) start = 0
        var end = iter.following(safe.coerceAtLeast(start))
        if (end == BreakIterator.DONE || end <= start) end = text.length
        if (end <= start) {
            start = safe
            end = (safe + 1).coerceAtMost(text.length)
        }
        return start to end
    }
}

/**
 * 原生可选择文本块（段落/标题/列表项/引用正文）。
 * 手势协议见 [GestureDrivenTextView]；[textSpannable] 变化时原位 setText。
 */
@androidx.compose.runtime.Composable
internal fun SelectableTextBlock(
    textSpannable: SpannableStringBuilder,
    textColor: Int,
    sizeFactor: Float,
    bold: Boolean,
    modifier: Modifier = Modifier,
    lineHeightFactor: Float = 1.35f,
    highlightColor: Int = 0x4034D399.toInt(),
    openLink: (String) -> Unit = {}
) {
    var textView by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<GestureDrivenTextView?>(null)
    }
    // 拖选锚点：长按选词的固定范围（扩选期间 start/end 的吸附基准）
    var anchorStart by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
    var anchorEnd by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }

    AndroidView(
        modifier = modifier
            .pointerInput(openLink) {
                // 点击：命中链接 span 则打开（movementMethod 在 DOWN 放行策略下不可达）
                detectTapGestures(onTap = { pos ->
                    val tv = textView ?: return@detectTapGestures
                    // tap 已有选区的段落 = 取消选择（含浮层）；tap 链接 = 打开
                    if (tv.hasSelection()) {
                        tv.activeActionMode?.finish()
                        tv.clearTextSelection()
                        return@detectTapGestures
                    }
                    val text = tv.text as? SpannableStringBuilder ?: return@detectTapGestures
                    val offset = runCatching { tv.getOffsetForPosition(pos.x, pos.y) }.getOrNull()
                        ?: return@detectTapGestures
                    if (offset !in 0 until text.length) return@detectTapGestures
                    text.getSpans(offset, offset, ClickableSpan::class.java).firstOrNull()?.onClick(tv)
                })
            }
            .pointerInput(textSpannable) {
                // 长按直接拖动扩选：与列表 scrollable 同在 Compose 管线——手指移动
                // 时 scrollable 的 slop 先过（长按取消、列表滚动），按住不动时长按胜出
                var lastPosition = androidx.compose.ui.geometry.Offset.Zero
                detectDragGesturesAfterLongPress(
                    onDragStart = { pos ->
                        val tv = textView ?: return@detectDragGesturesAfterLongPress
                        // 选区高亮/把手渲染依赖 focus——程序化 setSelection 前必须先拿焦点
                        tv.requestFocus()
                        val (s, e) = tv.selectWordAt(pos.x, pos.y)
                        anchorStart = s
                        anchorEnd = e
                        lastPosition = pos
                        tv.parent?.requestDisallowInterceptTouchEvent(true)
                    },
                    onDrag = { change, dragAmount ->
                        val tv = textView ?: return@detectDragGesturesAfterLongPress
                        lastPosition += dragAmount
                        tv.extendSelectionTo(lastPosition.x, lastPosition.y, anchorStart, anchorEnd)
                        change.consume()
                    },
                    onDragEnd = {
                        // 选区非空时弹系统浮层（复制/全选）；空选区静默收场
                        val tv = textView ?: return@detectDragGesturesAfterLongPress
                        if ((tv.selectionEnd - tv.selectionStart) > 0) {
                            tv.requestFocus()
                            tv.showSelectionToolbar()
                        }
                    },
                    onDragCancel = {}
                )
            },
        factory = { ctx ->
            GestureDrivenTextView(ctx).apply {
                setTextColor(textColor)
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f * sizeFactor)
                setLineSpacing(0f, lineHeightFactor)
                if (bold) setTypeface(typeface, Typeface.BOLD)
                this.highlightColor = highlightColor
            }.also {
                it.setText(textSpannable, android.widget.TextView.BufferType.EDITABLE)
                textView = it
            }
        },
        update = { tv ->
            tv.setTextColor(textColor)
            tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f * sizeFactor)
            if (tv.text.toString() != textSpannable.toString()) tv.setText(textSpannable, android.widget.TextView.BufferType.EDITABLE)
        }
    )
}
