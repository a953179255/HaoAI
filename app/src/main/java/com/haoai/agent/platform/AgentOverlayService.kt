package com.haoai.agent.platform

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 任务悬浮窗（用户点名的编排层缺口：切后台看不到 Agent 在干嘛）。
 *
 * 形态与交互抄自三家成熟实现：上游 OverlayService（前台载体 + 胶囊实时文案 +
 * 内置停止键）、上游 ToolOverlayController（仅后台显示 + 完成态停留）、
 * 上游 FloatingWindowManager（位置持久化）。
 *
 * 刻意用传统 View 而非 ComposeView：服务窗口挂 Compose 需要注入
 * LifecycleOwner/SavedStateRegistry 三件套（上游 做法），胶囊这点内容不值得
 * 引入那份复杂度和崩溃面。
 *
 * 生命周期：send() 时尝试启动；RunObserver.state 转非活动 → 显示完成态停留
 * 3.5s → 自摘窗口并 stopSelf。应用回到前台时隐藏（聊天页内已有完整进度展示），
 * 切后台自动恢复显示。
 */
class AgentOverlayService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val main = Handler(Looper.getMainLooper())
    private var wm: WindowManager? = null

    private var pillView: LinearLayout? = null
    private var cardView: LinearLayout? = null
    private var expanded = false

    private lateinit var pillDot: View
    private lateinit var pillLabel: TextView
    private lateinit var cardGoal: TextView
    private lateinit var cardStep: TextView
    private lateinit var cardTodo: TextView
    private lateinit var cardElapsed: TextView

    private var params: WindowManager.LayoutParams? = null
    private var showing = false
    private var stopAt: Long = 0 // 完成态开始时间（停留窗口）

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (wm == null) wm = getSystemService(WINDOW_SERVICE) as WindowManager
        scope.launch { observe() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        runCatching { removeViews() }
        scope.cancel()
        super.onDestroy()
    }

    /** 状态机：活动→显示/更新；非活动→完成态停留后自退。 */
    private suspend fun observe() {
        var wasActive = false
        while (scope.isActive) {
            val st = RunObserver.state.value
            val enabled = (application as? com.haoai.agent.HaoApplication)
                ?.container?.settingsFlow?.value?.runOverlay ?: true
            if (st.active) {
                wasActive = true
                stopAt = 0
                if (!enabled || RunObserver.appForeground) {
                    removeViews() // 前台时聊天页有完整进度，胶囊会挡输入区
                } else if (canDraw()) {
                    ensureViews()
                    render(st)
                }
            } else if (wasActive) {
                // 完成态停留（上游 linger）：已停止/已完成 可见几秒再消失
                if (stopAt == 0L) stopAt = System.currentTimeMillis()
                if (stopAt > 0 && System.currentTimeMillis() - stopAt > 3500) {
                    removeViews()
                    stopSelf()
                    return
                }
                if (!RunObserver.appForeground && canDraw() && showing) {
                    pillDot.background = circle(COLOR_DONE)
                    pillLabel.text = "任务已结束"
                    if (expanded) toggleExpand()
                }
            }
            delay(300)
        }
    }

    private fun canDraw(): Boolean = Settings.canDrawOverlays(this)

    @SuppressLint("ClickableViewAccessibility", "InflateParams")
    private fun ensureViews() {
        if (showing) return
        val dm = resources.displayMetrics
        val startX = prefs.getInt(KEY_X, dm.widthPixels - 360).coerceAtLeast(12)
        val startY = prefs.getInt(KEY_Y, 140).coerceAtLeast(40)

        // —— 胶囊 ——
        pillDot = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(9), dp(9)).apply {
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            background = circle(COLOR_RUN)
        }
        pillLabel = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(8), 0, dp(4), 0)
        }
        val pillStop = TextView(this).apply {
            text = "⏹"
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(dp(6), 0, dp(2), 0)
            setOnClickListener {
                RunObserver.requestStop()
                android.widget.Toast.makeText(this@AgentOverlayService, "已请求停止", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        pillView = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = panel(22f)
            setPadding(dp(12), dp(8), dp(10), dp(8))
            addView(pillDot)
            addView(pillLabel)
            addView(pillStop)
            setOnTouchListener(dragOrTap { toggleExpand() })
        }

        // —— 展开卡 ——
        cardGoal = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        cardStep = TextView(this).apply {
            setTextColor(0xE6FFFFFF.toInt())
            textSize = 13f
            maxLines = 3
            setPadding(0, dp(6), 0, 0)
        }
        cardTodo = TextView(this).apply {
            setTextColor(0x99FFFFFF.toInt())
            textSize = 12f
            maxLines = 2
            setPadding(0, dp(4), 0, 0)
        }
        cardElapsed = TextView(this).apply {
            setTextColor(0x80FFFFFF.toInt())
            textSize = 11f
            setPadding(0, dp(4), 0, 0)
        }
        fun action(label: String, action: () -> Unit): TextView = TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(dp(10), dp(5), dp(10), dp(5))
            background = panel(14f, fill = 0x33FFFFFF)
            setOnClickListener { action() }
        }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(10), 0, 0)
            addView(action("去处理") { goChat() })
            addView(View(this@AgentOverlayService).apply { layoutParams = LinearLayout.LayoutParams(dp(8), 1) })
            addView(action("收起") { toggleExpand() })
            addView(View(this@AgentOverlayService).apply { layoutParams = LinearLayout.LayoutParams(dp(8), 1) })
            addView(action("停止") {
                RunObserver.requestStop()
                android.widget.Toast.makeText(this@AgentOverlayService, "已请求停止", android.widget.Toast.LENGTH_SHORT).show()
            })
        }
        cardView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = panel(22f)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            addView(cardGoal)
            addView(cardStep)
            addView(cardTodo)
            addView(cardElapsed)
            addView(buttons)
            setOnTouchListener(dragOrTap { toggleExpand() })
        }

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = startX
            y = startY
        }
        show(if (expanded) cardView!! else pillView!!)
        showing = true
    }

    private fun show(v: View) {
        val p = params ?: return
        runCatching {
            if (v.parent == null) wm?.addView(v, p) else wm?.updateViewLayout(v, p)
        }
    }

    private fun removeViews() {
        runCatching { pillView?.let { if (it.parent != null) wm?.removeView(it) } }
        runCatching { cardView?.let { if (it.parent != null) wm?.removeView(it) } }
        showing = false
    }

    private fun toggleExpand() {
        expanded = !expanded
        removeViews()
        showing = false
        ensureViews()
        render(RunObserver.state.value)
    }

    /** 拖动 + 点按判定（slop 内算点按）。 */
    @SuppressLint("ClickableViewAccessibility")
    private fun dragOrTap(onTap: () -> Unit): View.OnTouchListener {
        val slop = android.view.ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        return View.OnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX; downY = ev.rawY
                    startX = params?.x ?: 0; startY = params?.y ?: 0
                    moved = false
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (ev.rawX - downX).toInt()
                    val dy = (ev.rawY - downY).toInt()
                    if (!moved && (kotlin.math.abs(dx) > slop || kotlin.math.abs(dy) > slop)) moved = true
                    if (moved) {
                        params?.let {
                            it.x = startX + dx
                            it.y = startY + dy
                            runCatching { wm?.updateViewLayout(v, it) }
                        }
                    }
                    moved
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        prefs.edit().putInt(KEY_X, params?.x ?: 0).putInt(KEY_Y, params?.y ?: 0).apply()
                    } else {
                        onTap()
                    }
                    moved
                }
                else -> false
            }
        }
    }

    private fun render(st: RunObserver.RunState) {
        if (!showing) return
        val last = st.steps.firstOrNull()
        val stepLabel = when {
            st.approvalTitle != null -> "等待确认：${st.approvalTitle}"
            last?.state == "running" -> "正在 ${last.brief.ifBlank { last.name }}"
            last != null -> "刚完成：${last.brief.ifBlank { last.name }}"
            st.streamTail.isNotBlank() -> st.streamTail.lines().lastOrNull { it.isNotBlank() } ?: "正在回答…"
            else -> "正在思考…"
        }
        pillDot.background = circle(if (st.approvalTitle != null) COLOR_APPROVAL else COLOR_RUN)
        pillLabel.text = stepLabel
        cardGoal.text = st.goal.ifBlank { "任务执行中" }
        cardStep.text = stepLabel
        val done = st.todos.count { it.second == "completed" }
        cardTodo.text = if (st.todos.isNotEmpty()) {
            val cur = st.todos.firstOrNull { it.second == "in_progress" }?.first
            "任务清单 $done/${st.todos.size}" + (cur?.let { " · $it" } ?: "")
        } else ""
        cardTodo.visibility = if (st.todos.isEmpty()) View.GONE else View.VISIBLE
        val sec = (System.currentTimeMillis() - st.startedAt) / 1000
        cardElapsed.text = "已运行 ${sec / 60}分${sec % 60}秒"
        val v = if (expanded) cardView else pillView
        if (v?.parent == null) show(v!!)
    }

    private fun goChat() {
        val intent = Intent(this, com.haoai.agent.MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = android.net.Uri.parse("haoai://debug/chat")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(intent)
    }

    private val prefs by lazy {
        getSharedPreferences("run_overlay", Context.MODE_PRIVATE)
    }

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).toInt()

    private fun dp(v: Int): Int = dp(v.toFloat())

    private fun panel(radiusDp: Float, fill: Int = 0xD9161A20.toInt()): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(radiusDp).toFloat()
        }

    private fun circle(color: Int): GradientDrawable =
        GradientDrawable().apply { setColor(color); shape = GradientDrawable.OVAL }

    companion object {
        private const val KEY_X = "x"
        private const val KEY_Y = "y"
        private const val COLOR_RUN = 0xFF4CAF50.toInt()
        private const val COLOR_APPROVAL = 0xFFFFA726.toInt()
        private const val COLOR_DONE = 0xFF90A4AE.toInt()

        fun start(context: Context) {
            if (!Settings.canDrawOverlays(context)) return
            runCatching {
                context.startService(Intent(context, AgentOverlayService::class.java))
            }
        }
    }
}
