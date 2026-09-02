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
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.File
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
    /** 3 缓冲：2 缓冲下 acquireLatestImage 与生产者写缓冲贴得太近，部分 ROM 会读到过渡垃圾帧（竖条）。 */
    private const val MAX_IMAGES = 3

    private val lock = Any()
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    /** 帧管线独立线程：主线程被聊天/流式渲染占用时逐像素拷贝会被拖慢，导致掉帧与预览迟钝。 */
    private var captureThread: android.os.HandlerThread? = null
    /** shell uid 可信屏的 displayId（trusted 通道下 display 为空）。 */
    private var trustedDisplayId = -1
    private val latestFrame = AtomicReference<Bitmap?>(null)
    @Volatile private var lastActivityAt = 0L
    /** 屏由哪个通道创建：trusted=shell uid 可信屏（留得住 App），local=本进程公共屏。 */
    @Volatile var channel: String = ""
        private set
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

    /** WMS 强制取帧（JPEG）→ 位图，供预览面板在 ROM 冻结 VD 输出时也能看到真实画面。 */
    private fun decodeJpeg(bytes: ByteArray): Bitmap? =
        runCatching { android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()

    /** 预览刷新：WMS 强制合成一帧发面板（独立于 ImageReader 输出面的冻结状态）。 */
    fun refreshPreview() {
        val id = displayId ?: return
        previewScope.launch {
            val bytes = PrivilegedShell.captureDisplayJpeg(id, 720, 70)
            val bmp = bytes?.takeIf { it.isNotEmpty() }?.let { decodeJpeg(it) }
            if (bmp != null) {
                publishPreview(bmp)
            } else {
                latestFrame.get()?.takeIf { !it.isRecycled }?.let { publishPreview(it) }
            }
        }
    }

    private val previewScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob()
    )

    val displayId: Int? get() = display?.display?.displayId ?: trustedDisplayId.takeIf { it > 0 }

    /** displayId 的可观察形态（聊天顶栏入口按钮据此显隐）。 */
    val displayIdFlow = MutableStateFlow<Int?>(null)

    fun touch() { lastActivityAt = System.currentTimeMillis() }

    /** 空闲超 5 分钟自动销毁；每次工具调用前检查。返回 true 表示屏刚被回收。 */
    fun reapIfIdle(): Boolean {
        if (displayId == null) return false
        if (System.currentTimeMillis() - lastActivityAt < IDLE_DESTROY_MS) return false
        destroy()
        return true
    }

    /**
     * 建屏（幂等），双通道：PrivilegedShell 可用 → shell uid 建【可信屏】
     * （TRUSTED flag，App 上屏后不被 ROM 重挂回主屏）；无特权源 → 本进程
     * 公共屏（AOSP 可用，严格 ROM 会被重挂载）。ImageReader 一律在本进程
     * （帧管线/截图/预览不跨进程），surface 传给 shell 侧建屏。
     */
    fun ensureDisplay(context: Context): String? = synchronized(lock) {
        if (displayId != null) return null
        if (!supported) return "虚拟屏需要 Android 11（API 30）及以上"
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val main = dm.getDisplay(Display.DEFAULT_DISPLAY)
            ?: return "找不到主屏，无法确定虚拟屏规格"
        val metrics = android.util.DisplayMetrics().also { main.getRealMetrics(it) }
        val w = metrics.widthPixels
        val h = metrics.heightPixels
        if (w <= 0 || h <= 0) return "主屏尺寸异常（${w}x$h）"
        debugCtx = context
        debugLog("ensureDisplay: ${w}x${h} @ ${metrics.densityDpi}dpi, existing=${displayId}")
        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, MAX_IMAGES)
        val th = android.os.HandlerThread("haovd-capture").apply { start() }
        val captureHandler = Handler(th.looper)
        r.setOnImageAvailableListener({ rd ->
            val img = runCatching { rd.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
            val bmp = runCatching { imageToBitmap(img) }.getOrNull()
            img.close()
            if (bmp != null) {
                latestFrame.getAndSet(bmp)?.takeIf { it !== bmp }?.recycle()
                publishPreview(bmp)
                logFrameStats(bmp)
            } else {
                android.util.Log.w("HaoAIVD", "frame decode failed（可能非 RGBA 格式或行距异常）")
            }
        }, captureHandler)

        PrivilegedShell.refresh(context)
        val privileged = PrivilegedShell.shizukuUsable() || PrivilegedShell.hasRoot()
        if (privileged) {
            val id = runBlocking { PrivilegedShell.createTrustedDisplay(w, h, metrics.densityDpi, r.surface) }
            if (id > 0) {
                reader = r
                captureThread = th
                trustedDisplayId = id
                channel = if (PrivilegedShell.shizukuUsable()) "trusted-shizuku" else "trusted-root"
                displayIdFlow.value = id
                touch()
                debugLog("trusted display created/reused id=$id")
                return null
            }
            debugLog("trusted display create failed (id=$id), fallback to local display")
            android.util.Log.w("HaoAIVD", "trusted display create failed (id=$id), fallback to local display")
            // 可信屏创建失败：继续走本地公共屏回退（不留死 reader）
        }

        val d = runCatching {
            dm.createVirtualDisplay(
                "haoai-agent", w, h, metrics.densityDpi, r.surface,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
            )
        }.getOrNull()
        if (d == null) {
            runCatching { r.close() }
            runCatching { th.quitSafely() }
            return "虚拟屏创建失败（本 ROM 可能限制了公共虚拟屏）"
        }
        display = d
        reader = r
        captureThread = th
        channel = "local"
        displayIdFlow.value = d.display?.displayId
        touch()
        debugLog("local display created id=${d.display?.displayId}")
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
            // 注入防线：target 经特权 shell 单引号拼接，单引号/换行可逃逸成任意命令；
            // 百分号编码语义等价（URI 解码后还原），正常 URL 不受影响
            val shellTarget = target
                .replace("'", "%27")
                .replace("\n", "%0A")
                .replace("\r", "%0D")
            shellCmd = "am start --display $id -a android.intent.action.VIEW -d '$shellTarget'"
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
        // 目标 App 已在主屏运行时，am start --display 会被系统送回主屏实例（"delivered to
        // currently running"），虚拟屏收不到内容 → 用户看到白屏/空帧，须明确报错
        fun redeliveryCheck(out: String): String? = if (out.contains("delivered to currently running") ||
            out.contains("Activity not started")
        ) {
            "目标 App 正在主屏运行：无法投递到虚拟屏（系统会把启动送回主屏实例）。请让用户先关闭主屏上的该 App，再重试；不要擅自 force-stop 打断用户当前操作。"
        } else null
        if (PrivilegedShell.shizukuUsable()) {
            val r = PrivilegedShell.shizukuExec(shellCmd)
            redeliveryCheck(r.output)?.let {
                debugLog("launch redelivered (shizuku): ${target}")
                return it
            }
            if (r.ok) {
                onLaunched()
                debugLog("launched via shizuku: $target (display $id)")
                return firstFrameOrHeal(id, target)
            }
            // shell 错误（Activity 不存在等）直接透传；Permission Denial 才回退直启
            if (!r.output.contains("Permission Denial")) return "Shizuku 启动失败：${r.output.take(220)}"
        }
        if (PrivilegedShell.hasRoot()) {
            val r = PrivilegedShell.rootExec(shellCmd)
            redeliveryCheck(r.output)?.let {
                debugLog("launch redelivered (root): ${target}")
                return it
            }
            if (r.ok) {
                onLaunched()
                debugLog("launched via root: $target (display $id)")
                return firstFrameOrHeal(id, target)
            }
            if (!r.output.contains("Permission Denial")) return "root 启动失败：${r.output.take(220)}"
        }
        // 可信屏归 shell 进程所有，本进程无法直启其上（owner 校验）——无直启回退
        if (channel.startsWith("trusted")) {
            return "特权启动失败：请确认 Shizuku 服务在运行"
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

    /**
     * 首帧自愈：启动成功后 6s 无任何帧 → 屏可能处于僵尸态（跨进程复用/ROM 合成故障），
     * 销毁重建并让调用方重试一次。有帧返回 null。
     */
    private suspend fun firstFrameOrHeal(displayId: Int, target: String): String? {
        if (awaitFrame(6000)) return null
        debugLog("no first frame (display $displayId, target $target) -> destroy & retry")
        destroy()
        return "目标未在虚拟屏出画面（疑似屏幕合成故障，已清理重建）：请重试一次启动「$target」"
    }

    /** 诊断用：当前是否有帧（debug 路由观察）。 */
    fun hasFrame(): Boolean = latestFrame.get() != null

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

    /** 码率档位（kbps）→ 截图分辨率与 JPEG 质量。HaoAI 帧管线是逐帧位图（无 H.264 编码），
     * 码率以「每帧字节」等效映射：档位越高截图越大越清晰，预览与喂给模型的字节也随之增大。 */
    fun presetFor(bitrateKbps: Int): Pair<Int, Int> = when (bitrateKbps) {
        1500 -> 720 to 62
        5000 -> 1280 to 72
        10000 -> 1600 to 80
        20000 -> 1920 to 86
        else -> 960 to 66 // 3000 kbps 默认档
    }

    /**
     * 帧健康诊断（debug 门控：setprop log.tag.HaoAIVD DEBUG 或 debuggable 构建；
     * 部分 ROM 压制 logcat 输出，诊断同时落 filesDir/haovd-debug.log）。
     */
    @Volatile private var debugCtx: Context? = null

    fun debugLog(msg: String) {
        val ctx = debugCtx ?: return
        val on = android.util.Log.isLoggable("HaoAIVD", android.util.Log.DEBUG) ||
            (ctx.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0)
        if (!on) return
        android.util.Log.d("HaoAIVD", msg)
        runCatching {
            val f = File(ctx.filesDir, "haovd-debug.log")
            if (f.length() > 256 * 1024) f.delete()
            f.appendText("${System.currentTimeMillis()}  $msg\n")
        }
    }

    @Volatile private var lastStatsColors = 0

    /** 最近一帧画面健康：颜色数 ≤3 视为纯色/底板（部分 ROM 不把应用内容合入虚拟屏帧缓冲）。 */
    fun frameIsDegenerate(): Boolean = lastStatsColors in 1..3

    /** 影子镜像抓到的帧是真实合成：解码采样更新颜色统计（让"纯色提示"跟随真实画面）。 */
    private fun updateDegenerateFromJpeg(bytes: ByteArray) {
        runCatching {
            val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return
            val colors = HashSet<Int>()
            val step = 64
            var y = 0
            while (y < bmp.height) {
                var x = 0
                while (x < bmp.width) {
                    colors.add(bmp.getPixel(x, y))
                    x += step
                }
                y += step
            }
            lastStatsColors = colors.size
            bmp.recycle()
        }
    }

    private fun logFrameStats(bmp: Bitmap) {
        val w = bmp.width
        val h = bmp.height
        val step = 64
        val colors = HashSet<Int>()
        var rSum = 0L; var gSum = 0L; var bSum = 0L; var white = 0; var n = 0
        val row = IntArray(w)
        var y = 0
        while (y < h) {
            bmp.getPixels(row, 0, w, 0, y, w, 1)
            var x = 0
            while (x < w) {
                val p = row[x]
                colors.add(p)
                if ((p shr 16 and 0xFF) > 240 && (p shr 8 and 0xFF) > 240 && (p and 0xFF) > 240) white++
                rSum += p shr 16 and 0xFF; gSum += p shr 8 and 0xFF; bSum += p and 0xFF
                n++
                x += step
            }
            y += step
        }
        lastStatsColors = colors.size
        debugLog("frame ${w}x${h} colors=${colors.size} avg=(${rSum / n},${gSum / n},${bSum / n}) white=${white * 100 / n}%")
    }

    /** 最近一帧 + 点击标记 → JPEG data URL（复用 browser_screenshot 图像通路）。 */
    fun capture(maxLongSide: Int = 1280, quality: Int = 70): String? {
        // 首选：特权侧影子镜像强制合成取帧（Operator-on-Android/scrcpy 同款——绕过 ROM 对
        // VD 输出面只合成纯色/启动画面的冻结）；失败回退本地 ImageReader 帧
        debugLog("capture($maxLongSide,$quality) channel=$channel tid=${displayId}")
        // 影子镜像对任意 displayId 生效（服务端按 layerStack 强制合成，不依赖谁创建的屏）
        val capId = displayId
        if (capId != null) {
            val bytes = runBlocking { PrivilegedShell.captureDisplayJpeg(capId, maxLongSide, quality) }
            if (bytes != null && bytes.isNotEmpty()) {
                debugLog("capture via shadow-mirror display=$capId ${bytes.size} bytes")
                updateDegenerateFromJpeg(bytes)
                return "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
            }
            debugLog("shadow-mirror capture failed${PrivilegedShell.lastCaptureError?.let { " ($it)" } ?: ""}, fallback local frame")
        }
        val frame = latestFrame.get() ?: return null
        if (frame.isRecycled) return null
        val src = Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(src)
        // 帧监听线程可能恰在回收上一帧：画失败重试一次，仍不行就放弃（不抛成工具错误）
        runCatching { canvas.drawBitmap(frame, 0f, 0f, null) }
            .onFailure { runCatching { canvas.drawBitmap(frame, 0f, 0f, null) }
                .onFailure { src.recycle(); return null } }
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
        debugLog("capture ${src.width}x${src.height} q=$quality -> ${bos.size()} bytes")
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
        val trusted = trustedDisplayId
        if (trusted > 0) {
            runBlocking { PrivilegedShell.releaseTrustedDisplay(trusted) }
            trustedDisplayId = -1
        }
        runCatching { display?.release() }
        runCatching { reader?.close() }
        runCatching { captureThread?.quitSafely() }
        display = null
        reader = null
        captureThread = null
        channel = ""
        displayIdFlow.value = null
        latestFrame.getAndSet(null)?.recycle()
        previewFrame.value = null
        previewOpen.value = false
        lastMarker = null
        debugLog("destroyed")
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
