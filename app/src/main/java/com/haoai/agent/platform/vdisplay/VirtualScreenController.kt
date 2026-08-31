package com.haoai.agent.platform.vdisplay

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.Display
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicReference

/**
 * 4.3 虚拟屏后台自动化：DisplayManager 创建与主屏同规格的公共虚拟屏，
 * 目标 App 经 ActivityOptions.setLaunchDisplayId 启动到屏上（本 App 自己
 * 创建的虚拟屏，caller 即 owner，普通权限可启动）；ImageReader surface
 * 收帧供截图（上游 逆向确认的帧管线模式）。操作主轴走无障碍节点
 * （API 30+ getWindowsOnAllDisplays，见 HaoAccessibilityService），本类
 * 只管屏的生命周期与画面。
 *
 * 生命周期：会话级——首次 vscreen_launch 建屏，vscreen_close 或 5 分钟
 * 无调用自动销毁省电。截图取"最近一帧"缓存（虚拟屏只在内容变化时出帧）。
 */
object VirtualScreenController {

    /** 多显示器无障碍（getWindowsOnAllDisplays）自 API 30 才有公开通道。 */
    val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    private const val IDLE_DESTROY_MS = 5 * 60 * 1000L

    private val lock = Any()
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private val latestFrame = AtomicReference<Bitmap?>(null)
    @Volatile private var lastActivityAt = 0L
    /** 上一步动作的目标控件矩形（虚拟屏坐标，与截图 1:1），供点击标记绘制。 */
    @Volatile var lastMarker: Pair<Rect, String>? = null
        private set

    /** 预览面板开合；vscreen_launch 成功自动弹出（两段式 uiOpener 模式）。 */
    val previewOpen = MutableStateFlow(false)
    /** 面板实时帧（降采样，软件位图随 GC 回收不显式 recycle——面板可能仍持引用绘制）。 */
    val previewFrame = MutableStateFlow<Bitmap?>(null)

    fun openPreview() {
        previewOpen.value = true
        // 面板打开时先发当前帧，避免要等内容更新才见画面
        latestFrame.get()?.let { publishPreview(it) }
    }
    fun closePreview() { previewOpen.value = false }

    private fun publishPreview(bmp: Bitmap) {
        if (!previewOpen.value) return
        val scale = minOf(1f, 720f / maxOf(bmp.width, bmp.height))
        // 始终发布独立副本：latestFrame 会被 recycle，面板不能共享同一实例
        previewFrame.value = if (scale < 1f) {
            Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
        } else bmp.copy(Bitmap.Config.ARGB_8888, false)
    }

    val displayId: Int? get() = display?.display?.displayId

    /** displayId 的可观察形态（聊天顶栏入口按钮据此显隐）。 */
    val displayIdFlow = MutableStateFlow<Int?>(null)

    fun touch() { lastActivityAt = System.currentTimeMillis() }

    /** 空闲超 5 分钟自动销毁；每次工具调用前检查。返回 true 表示屏刚被回收。 */
    fun reapIfIdle(): Boolean {
        if (display == null) return false
        if (System.currentTimeMillis() - lastActivityAt < IDLE_DESTROY_MS) return false
        destroy()
        return true
    }

    /** 建屏（幂等）。返回 null=成功，否则错误信息。 */
    fun ensureDisplay(context: Context): String? = synchronized(lock) {
        if (display != null) return null
        if (!supported) return "虚拟屏需要 Android 11（API 30）及以上"
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val main = dm.getDisplay(Display.DEFAULT_DISPLAY)
            ?: return "找不到主屏，无法确定虚拟屏规格"
        val metrics = android.util.DisplayMetrics().also { main.getRealMetrics(it) }
        val w = metrics.widthPixels
        val h = metrics.heightPixels
        if (w <= 0 || h <= 0) return "主屏尺寸异常（${w}x$h）"
        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        r.setOnImageAvailableListener({ rd ->
            val img = runCatching { rd.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
            val bmp = runCatching { imageToBitmap(img) }.getOrNull()
            img.close()
            if (bmp != null) {
                latestFrame.getAndSet(bmp)?.takeIf { it !== bmp }?.recycle()
                publishPreview(bmp)
            }
        }, Handler(Looper.getMainLooper()))
        val d = runCatching {
            dm.createVirtualDisplay(
                "haoai-agent", w, h, metrics.densityDpi, r.surface,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
            )
        }.getOrNull()
        if (d == null) {
            runCatching { r.close() }
            return "虚拟屏创建失败（本 ROM 可能限制了公共虚拟屏）"
        }
        display = d
        reader = r
        displayIdFlow.value = d.display?.displayId
        touch()
        null
    }

    /**
     * 把目标 App/页面启动到虚拟屏。target=包名 或 http(s) URL。
     * 启动失败（App 拒绝多屏/系统限制）返回错误信息，由工具层引导降级前台。
     */
    /**
     * 三级通道启动（Shizuku shell uid → root → App 直启）。shell 通道等价
     * `adb shell am start --display`，豁免 untrusted 屏启动检查（系统 App/
     * 严格 ROM 也能上屏）；直启失败沿用原报错降级文案。返回 null=成功。
     */
    suspend fun launch(context: Context, target: String): String? {
        ensureDisplay(context)?.let { return it }
        val id = displayId ?: return "虚拟屏不可用"
        val pm = context.packageManager
        val isUrl = target.startsWith("http://") || target.startsWith("https://")
        // shell 通道拼命令行；直启通道拼 intent
        val shellCmd: String
        val launchIntent: Intent
        if (isUrl) {
            shellCmd = "am start --display $id -a android.intent.action.VIEW -d '$target'"
            launchIntent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(target))
        } else {
            val act = runCatching { pm.getLaunchIntentForPackage(target)?.component }.getOrNull()
                ?: return "未安装应用：$target（可先用 list_apps 查包名）"
            shellCmd = "am start --display $id -n ${act.flattenToString()}"
            launchIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                component = act
            }
        }
        PrivilegedShell.refresh(context)
        if (PrivilegedShell.shizukuUsable()) {
            val r = PrivilegedShell.shizukuExec(shellCmd)
            if (r.ok) { onLaunched(); return null }
            // shell 错误（Activity 不存在等）直接透传；Permission Denial 才回退直启
            if (!r.output.contains("Permission Denial")) return "Shizuku 启动失败：${r.output.take(220)}"
        }
        if (PrivilegedShell.hasRoot()) {
            val r = PrivilegedShell.rootExec(shellCmd)
            if (r.ok) { onLaunched(); return null }
            if (!r.output.contains("Permission Denial")) return "root 启动失败：${r.output.take(220)}"
        }
        val directError = synchronized(lock) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val options = ActivityOptions.makeBasic().apply { launchDisplayId = id }
            runCatching { context.startActivity(launchIntent, options.toBundle()) }
                .fold(
                    onSuccess = { null },
                    onFailure = { "启动到虚拟屏失败：${it.message}（该 App 可能拒绝多屏；安装并授权 Shizuku 可解锁）" }
                )
        }
        if (directError != null) return directError
        onLaunched()
        return null
    }

    private fun onLaunched() {
        lastMarker = null
        touch()
        previewOpen.value = true
    }

    /** 回到虚拟屏桌面：home intent 定向到虚拟屏（等效于把屏上应用退到后台）。 */
    fun goHome(context: Context): String? = synchronized(lock) {
        val id = displayId ?: return "虚拟屏未启动"
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val options = ActivityOptions.makeBasic().apply { launchDisplayId = id }
        runCatching { context.startActivity(intent, options.toBundle()) }
            .onFailure { return "回到虚拟屏桌面失败：${it.message}" }
        touch()
        null
    }

    fun markAction(rect: Rect, label: String) {
        lastMarker = Rect(rect) to label
        touch()
    }

    /** 最近一帧 + 点击标记 → JPEG data URL（复用 browser_screenshot 图像通路）。 */
    fun capture(maxLongSide: Int = 1280, quality: Int = 70): String? {
        val frame = latestFrame.get() ?: return null
        val src = Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(src)
        canvas.drawBitmap(frame, 0f, 0f, null)
        lastMarker?.let { (rect, label) -> drawMarker(canvas, rect, label) }
        val scale = minOf(1f, maxLongSide.toFloat() / maxOf(src.width, src.height))
        val bmp = if (scale < 1f) {
            val out = Bitmap.createScaledBitmap(src, (src.width * scale).toInt(), (src.height * scale).toInt(), true)
            src.recycle()
            out
        } else src
        val bos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, quality, bos)
        bmp.recycle()
        val b64 = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
        return "data:image/jpeg;base64,$b64"
    }

    /** 等第一帧出现（launch 后帧管线需要内容更新才出帧），最多 [timeoutMs]。 */
    suspend fun awaitFrame(timeoutMs: Long = 2500L): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (latestFrame.get() == null) {
            if (System.currentTimeMillis() > deadline) return false
            kotlinx.coroutines.delay(150)
        }
        return true
    }

    fun destroy() = synchronized(lock) {
        runCatching { display?.release() }
        runCatching { reader?.close() }
        display = null
        reader = null
        displayIdFlow.value = null
        latestFrame.getAndSet(null)?.recycle()
        previewFrame.value = null
        previewOpen.value = false
        lastMarker = null
    }

    private fun drawMarker(canvas: Canvas, rect: Rect, label: String) {
        val stroke = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.STROKE
            strokeWidth = 6f
            color = Color.RED
        }
        val fill = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.FILL
            color = Color.RED
        }
        val text = Paint().apply {
            isAntiAlias = true
            color = Color.WHITE
            textSize = 36f
        }
        canvas.drawRect(rect, stroke)
        // 左上角标签底条，保证任意背景下可读
        val tag = "⬅ $label"
        val ty = (rect.top - 48f).coerceAtLeast(0f)
        canvas.drawRect(rect.left.toFloat(), ty, rect.left + 24f + text.measureText(tag), ty + 44f, fill)
        canvas.drawText(tag, rect.left + 12f, ty + 34f, text)
    }

    /** Image(RGBA_8888 单平面) → Bitmap：逐行拷贝，规避 rowStride 行距填充与 ByteBuffer 重载的类型解析问题。 */
    private fun imageToBitmap(img: android.media.Image): Bitmap? {
        val plane = img.planes.firstOrNull() ?: return null
        val buf = plane.buffer ?: return null
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        if (pixelStride != 4) return null
        val w = img.width
        val h = img.height
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val row = IntArray(w)
        for (y in 0 until h) {
            buf.position(y * rowStride)
            for (x in 0 until w) {
                val b = buf.get(x * pixelStride).toInt() and 0xFF
                val g = buf.get(x * pixelStride + 1).toInt() and 0xFF
                val r = buf.get(x * pixelStride + 2).toInt() and 0xFF
                val a = buf.get(x * pixelStride + 3).toInt() and 0xFF
                row[x] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
            bmp.setPixels(row, 0, w, 0, y, w, 1)
        }
        return bmp
    }
}
