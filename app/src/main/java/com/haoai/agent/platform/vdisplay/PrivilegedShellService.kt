package com.haoai.agent.platform.vdisplay

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Parcel
import android.util.Base64
import android.view.Surface
import java.io.ByteArrayOutputStream

/**
 * Shizuku UserService：由 Shizuku server 经 app_process 以 shell uid 启动。
 * 两组能力：
 * 1. exec——进程内 Runtime.exec 即等价 adb shell（am start --display 等）；
 * 2. 可信虚拟屏——裸 IDisplayManager 反射建 TRUSTED 屏（Flyme 重挂载克星，
 *    Operator-on-Android 同款机制），surface 由应用进程 ImageReader 跨进程传入。
 *
 * Binder 协议手写（两端同一 APK，描述符/事务码自洽；AIDL 在含中文的工程路径下
 * dep 文件解析有编码 bug，故不用）。
 */
class PrivilegedShellService : Binder() {

    companion object {
        const val DESCRIPTOR = "com.haoai.agent.platform.vdisplay.IPrivilegedShellService"
        const val TRANSACTION_EXEC = FIRST_CALL_TRANSACTION
        const val TRANSACTION_EXIT = FIRST_CALL_TRANSACTION + 1
        const val TRANSACTION_CREATE_DISPLAY = FIRST_CALL_TRANSACTION + 2
        const val TRANSACTION_RELEASE_DISPLAY = FIRST_CALL_TRANSACTION + 3
        const val TRANSACTION_CAPTURE_DISPLAY = FIRST_CALL_TRANSACTION + 4
    }

    // ---------- 生命周期入口 ----------

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        return when (code) {
            TRANSACTION_EXEC -> {
                data.enforceInterface(DESCRIPTOR)
                val resp = exec(data.readString().orEmpty())
                reply?.writeNoException()
                reply?.writeString(resp)
                true
            }
            TRANSACTION_EXIT -> {
                exit()
                true
            }
            TRANSACTION_CREATE_DISPLAY -> {
                data.enforceInterface(DESCRIPTOR)
                val w = data.readInt()
                val h = data.readInt()
                val dpi = data.readInt()
                val token = data.readString().orEmpty()
                val surface = Surface.CREATOR.createFromParcel(data)
                val displayId = runCatching { createTrustedDisplay(w, h, dpi, token, surface) }
                    .onFailure { android.util.Log.e("HaoAIVD", "createTrustedDisplay failed", it) }
                    .getOrDefault(-1)
                reply?.writeNoException()
                reply?.writeInt(displayId)
                true
            }
            TRANSACTION_RELEASE_DISPLAY -> {
                data.enforceInterface(DESCRIPTOR)
                val released = runCatching { releaseDisplay(data.readInt()) }
                    .onFailure { android.util.Log.e("HaoAIVD", "releaseDisplay failed", it) }
                    .getOrDefault(false)
                reply?.writeNoException()
                reply?.writeInt(if (released) 1 else 0)
                true
            }
            TRANSACTION_CAPTURE_DISPLAY -> {
                data.enforceInterface(DESCRIPTOR)
                val displayId = data.readInt()
                val maxSide = data.readInt()
                val quality = data.readInt()
                val bytes = runCatching { captureDisplay(displayId, maxSide, quality) }
                    .onFailure { android.util.Log.e("HaoAIVD", "captureDisplay failed", it) }
                    .getOrNull()
                reply?.writeNoException()
                reply?.writeByteArray(bytes ?: ByteArray(0))
                true
            }
            else -> super.onTransact(code, data, reply, flags)
        }
    }

    private fun exit() {
        System.exit(0)
    }

    // ---------- exec ----------

    private fun exec(cmd: String): String {
        val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
        val out = p.inputStream.readBytes().decodeToString()
        val err = p.errorStream.readBytes().decodeToString()
        val rc = p.waitFor()
        // stdout+stderr 合并：am start 的重投递警告走 stderr，只取 stdout 会漏检
        val text = (out + "\n" + err).trim().ifBlank { "(no output)" }
        return "$rc\n$text"
    }

    // ---------- 可信虚拟屏（Operator-on-Android 同款机制） ----------

    private var currentDisplayId = -1
    private var currentToken: IBinder? = null
    private var currentW = 0
    private var currentH = 0
    private var currentDpi = 0
    /** 创建者进程实例 token：跨进程复用意味着旧 surface 已死（僵尸屏不出帧），必须释放重建。 */
    private var sessionToken = ""

    init {
        // App 进程死亡/服务版本更替时 Shizuku 不一定回收旧 UserService 进程，
        // 其名下虚拟屏随之泄漏；新进程启动时清掉残留同名进程（同 uid 可发信号），
        // 屏会随 binder 死亡被 DMS 一并回收。
        killStaleServiceProcesses()
    }

    private fun killStaleServiceProcesses() {
        runCatching {
            val me = android.os.Process.myPid()
            java.io.File("/proc").listFiles { f -> (f.name.toIntOrNull() ?: me) != me }?.forEach { f ->
                runCatching {
                    val cmd = java.io.File(f, "cmdline").inputStream().use { ins ->
                        ins.readBytes().toString(Charsets.UTF_8).trim('\u0000', ' ', '\n')
                    }
                    if (cmd == "com.haoai.agent:shell") {
                        android.util.Log.w("HaoAIVD", "kill stale service process pid=${f.name}")
                        android.os.Process.killProcess(f.name.toInt())
                    }
                }
            }
        }
    }

    /** 同规格且同一创建进程才复用（只换 surface）；跨进程/规格变化先释放重建。失败返回 -1。 */
    @SuppressLint("BlockedPrivateApi", "SoonBlockedPrivateApi")
    private fun createTrustedDisplay(w: Int, h: Int, dpi: Int, token: String, surface: Surface): Int {
        if (currentDisplayId != -1 && currentW == w && currentH == h && currentDpi == dpi && token == sessionToken) {
            setSurface(currentDisplayId, surface)
            return currentDisplayId
        }
        if (currentDisplayId != -1 && !releaseDisplay(currentDisplayId)) {
            // 旧屏释放失败：服务进程即将退出回收，本次建屏按失败处理（客户端重试会重启服务）
            return -1
        }

        val idm = requireIDisplayManager() ?: return -1
        val callbackToken = Binder()
        val callbackClass = Class.forName("android.hardware.display.IVirtualDisplayCallback")
        val callbackProxy = java.lang.reflect.Proxy.newProxyInstance(
            callbackClass.classLoader, arrayOf(callbackClass)
        ) { _, method, args ->
            when (method.name) {
                "asBinder" -> callbackToken
                else -> when (method.returnType) {
                    Boolean::class.javaPrimitiveType -> false
                    Int::class.javaPrimitiveType -> 0
                    Long::class.javaPrimitiveType -> 0L
                    else -> null
                }
            }
        }

        // 公共 flag；API 33+ 起叠加 TRUSTED 等特权 flag（可信屏不受 untrusted 屏重挂载限制）
        var flags = DisplayManagerFlags.PUBLIC or DisplayManagerFlags.OWN_CONTENT_ONLY or
            DisplayManagerFlags.SUPPORTS_TOUCH or DisplayManagerFlags.SHOULD_SHOW_SYSTEM_DECORATIONS
        if (Build.VERSION.SDK_INT >= 33) {
            flags = flags or DisplayManagerFlags.TRUSTED or DisplayManagerFlags.OWN_DISPLAY_GROUP or
                DisplayManagerFlags.ALWAYS_UNLOCKED or DisplayManagerFlags.TOUCH_FEEDBACK_DISABLED
        }
        if (Build.VERSION.SDK_INT >= 34) {
            flags = flags or DisplayManagerFlags.OWN_FOCUS or DisplayManagerFlags.DEVICE_DISPLAY_GROUP or
                DisplayManagerFlags.STEAL_TOP_FOCUS_DISABLED
        }

        val displayId = if (Build.VERSION.SDK_INT >= 31) {
            createApi31(idm, callbackProxy, surface, w, h, dpi, flags)
        } else {
            return -1 // API 30/31 以下无 VirtualDisplayConfig 通路，shell 侧建屏仅支持 API 31+
        }
        if (displayId > 0) {
            currentDisplayId = displayId
            currentToken = callbackToken
            currentW = w; currentH = h; currentDpi = dpi
            sessionToken = token
        }
        return displayId
    }

    private fun setSurface(displayId: Int, surface: Surface) {
        val token = currentToken ?: return
        if (displayId != currentDisplayId) return
        runCatching {
            val idm = requireIDisplayManager() ?: return
            idm.javaClass.methods.firstOrNull { it.name == "setVirtualDisplaySurface" }?.let {
                it.isAccessible = true
                it.invoke(idm, token, surface)
            }
        }
    }

    /**
     * 释放并【验证】是否真正销毁（AOSP 直接成功；部分 ROM 裸 binder 释放静默失败）。
     * 验证失败 → 兜底退出服务进程：DMS 会随 binder 死亡回收其名下全部虚拟屏，
     * Shizuku 在客户端下次绑定时重启 UserService。返回 false=未能确认释放。
     */
    @Synchronized
    private fun releaseDisplay(displayId: Int): Boolean {
        if (displayId != currentDisplayId) return true
        val token = currentToken
        currentDisplayId = -1
        currentToken = null
        sessionToken = ""
        if (token == null) return true
        runCatching {
            val idm = requireIDisplayManager() ?: return true
            idm.javaClass.methods.firstOrNull { it.name == "releaseVirtualDisplay" }?.let {
                it.isAccessible = true
                it.invoke(idm, token)
            }
        }
        // DMS 侧销毁是异步的：轮询确认 displayId 已消失
        var released = false
        repeat(4) {
            if (!displayIdExists(displayId)) {
                released = true
                return@repeat
            }
            Thread.sleep(200)
        }
        if (released) return true
        android.util.Log.w("HaoAIVD", "release verified failed (display $displayId still alive), exit service as fallback")
        // 先让 binder 应答送达客户端，再退出进程触发 DMS 回收
        Thread {
            runCatching { Thread.sleep(400) }
            runCatching { android.os.Process.killProcess(android.os.Process.myPid()) }
        }.start()
        return false
    }

    /** shell uid 查询当前全部逻辑 displayId（getDisplayIds 为 hidden API，反射调用）。 */
    private fun displayIdExists(id: Int): Boolean = runCatching {
        val idm = requireIDisplayManager() ?: return true
        val ids = idm.javaClass.methods.firstOrNull { it.name == "getDisplayIds" }?.let {
            it.isAccessible = true
            it.invoke(idm) as? IntArray
        } ?: return true
        id in ids
    }.getOrDefault(true)

    private fun requireIDisplayManager(): Any? = runCatching {
        // ServiceManager 为 hidden 类：反射取 display 服务 binder
        val binder = Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java)
            .invoke(null, "display") as? IBinder ?: return null
        Class.forName("android.hardware.display.IDisplayManager\$Stub")
            .getMethod("asInterface", IBinder::class.java)
            .invoke(null, binder)
    }.getOrNull()

    /** shell 进程日志（logcat 被部分 ROM 压制时落盘诊断；UserService 为 shell uid，写 /data/local/tmp）。 */
    private fun shellLog(msg: String) {
        runCatching {
            val f = java.io.File("/data/local/tmp/haovd-shell.log")
            if (f.length() > 128 * 1024) f.delete()
            f.appendText("${System.currentTimeMillis()}  $msg\n")
        }
    }

    // ---------- 强制合成取帧（Operator-on-Android 同款：WMS captureDisplay） ----------

    /**
     * WMS captureDisplay 强制合成取帧：虚拟屏自身的 ImageReader 输出面在部分 ROM 上会被
     * 冻结成纯色/启动画面（合成器不再更新 VD 输出），而 captureDisplay 由 WMS 重合成一次。
     * Android 16 实测 IWindowManager.captureDisplay(int, ScreenCapture$CaptureArgs,
     * ScreenCapture$ScreenCaptureListener) 可用（SurfaceControl 镜像 API 16 已改/移除）。
     * 全反射；失败返回 null，客户端回退本地 ImageReader 帧。
     */
    private fun captureDisplay(displayId: Int, maxSide: Int, quality: Int): ByteArray? {
        return try {
            val wmBinder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java).invoke(null, "window") as? IBinder
                ?: run { shellLog("WMS: no window service"); return null }
            val wm = Class.forName("android.view.IWindowManager\$Stub")
                .getMethod("asInterface", IBinder::class.java).invoke(null, wmBinder)
                ?: run { shellLog("WMS: no IWindowManager"); return null }
            val info = wm.javaClass.methods.firstOrNull { it.name == "captureDisplay" }
                ?: run { shellLog("WMS: no captureDisplay method"); return null }
            shellLog("WMS: captureDisplay method ok")

            // CaptureArgs（Builder 嵌套类位置随版本变化：先查 CaptureArgs 自带的，再查外层）
            val argsCls = Class.forName("android.window.ScreenCapture\$CaptureArgs").also {
                shellLog("WMS: CaptureArgs ${it.name}")
            }
            val host = Class.forName("android.window.ScreenCapture")
            val bldr = (argsCls.declaredClasses + host.declaredClasses).firstOrNull { it.simpleName == "Builder" }
                ?: run { shellLog("WMS: no CaptureArgs Builder declared class"); return null }
            val b = bldr.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
            runCatching {
                bldr.getMethod("setSourceCrop", Rect::class.java).invoke(b, null)
            }
            runCatching {
                bldr.methods.firstOrNull { it.name == "setUseIdentityTransform" }?.invoke(b, true)
            }
            runCatching {
                bldr.methods.firstOrNull { it.name == "setUid" }?.invoke(b, -1)
            }
            val args = bldr.methods.first { it.name == "build" }.invoke(b)
            shellLog("WMS: CaptureArgs built ($bldr)")

            // 监听器：ScreenCaptureListener(ObjIntConsumer<ScreenshotHardwareBuffer>)——
            // Android 16 上是具体类非接口，Proxy 不可用；直接传函数式回调
            val listenerCls = Class.forName("android.window.ScreenCapture\$ScreenCaptureListener")
            val lock = Object()
            var buffer: android.hardware.HardwareBuffer? = null
            var colorSpace: android.graphics.ColorSpace? = null
            @Suppress("UNCHECKED_CAST")
            val ctor = listenerCls.getConstructor(java.util.function.ObjIntConsumer::class.java)
            val consumer = java.util.function.ObjIntConsumer<Any?> { res, _ ->
                try {
                    if (res != null) {
                        shellLog("WMS: callback result=${res.javaClass.name}")
                        synchronized(lock) {
                            if (buffer == null) {
                                buffer = res.javaClass.methods.first { it.name == "getHardwareBuffer" }
                                    .apply { isAccessible = true }.invoke(res) as? android.hardware.HardwareBuffer
                                colorSpace = res.javaClass.methods.firstOrNull { it.name == "getColorSpace" }
                                    ?.apply { isAccessible = true }?.invoke(res) as? android.graphics.ColorSpace
                            }
                            if (buffer != null) lock.notifyAll()
                        }
                    }
                } catch (e: Exception) {
                    shellLog("WMS: callback err ${e.javaClass.simpleName}: ${e.message}")
                }
            }
            val listener = ctor.newInstance(consumer)
            // 异步回调：轮询 3s 等缓冲
            info.invoke(wm, displayId, args, listener)
            val deadline = System.currentTimeMillis() + 3000
            while (buffer == null && System.currentTimeMillis() < deadline) Thread.sleep(50)
            val hb = buffer ?: run { shellLog("WMS: no buffer (display $displayId)"); return null }
            val bmp = android.graphics.Bitmap.wrapHardwareBuffer(
                hb, colorSpace ?: android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB)
            ) ?: run { shellLog("WMS: wrapHardwareBuffer failed"); return null }
            shellLog("WMS ok (display $displayId, ${bmp.width}x${bmp.height})")
            scaleAndJpeg(bmp, maxSide, quality)
        } catch (e: Exception) {
            shellLog("WMS capture failed (display $displayId): ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    /** 按最长边缩放并编译 JPEG。 */
    private fun scaleAndJpeg(src: Bitmap, maxSide: Int, quality: Int): ByteArray? {
        val scale = minOf(1f, maxSide.toFloat() / maxOf(src.width, src.height))
        val bmp = if (scale < 1f) {
            Bitmap.createScaledBitmap(src, (src.width * scale).toInt(), (src.height * scale).toInt(), true).also {
                if (it !== src) src.recycle()
            }
        } else src
        return try {
            val bos = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, quality, bos)
            bos.toByteArray()
        } finally {
            if (bmp !== src) bmp.recycle()
        }
    }

    private fun createApi31(
        idm: Any, callbackProxy: Any, surface: Surface,
        w: Int, h: Int, dpi: Int, flags: Int
    ): Int {
        val config = buildVirtualDisplayConfig(w, h, dpi, surface, flags)
        val method = idm.javaClass.methods
            .firstOrNull { it.name == "createVirtualDisplay" && it.parameterTypes.firstOrNull()?.simpleName == "VirtualDisplayConfig" }
            ?: throw NoSuchMethodException("createVirtualDisplay(VirtualDisplayConfig) not found")
        method.isAccessible = true
        // owner 显式传 com.android.shell：规避 DMS 的 uid/packageName 匹配校验
        return method.invoke(idm, config, callbackProxy, null, "com.android.shell") as Int
    }

    @SuppressLint("BlockedPrivateApi")
    private fun buildVirtualDisplayConfig(w: Int, h: Int, dpi: Int, surface: Surface, flags: Int): Any {
        val builderClass = Class.forName("android.hardware.display.VirtualDisplayConfig\$Builder")
        val builder = builderClass.getConstructor(
            String::class.java, Int::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!
        ).newInstance("HaoAIVirtualDisplay", w, h, dpi)
        builderClass.getDeclaredMethod("setSurface", Surface::class.java)
            .apply { isAccessible = true }.invoke(builder, surface)
        builderClass.getDeclaredMethod("setFlags", Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(builder, flags)
        return builderClass.getMethod("build").invoke(builder)
    }
}

/** VirtualDisplay flag 常量（部分为 hidden 值，按 AOSP 位序定义）。 */
private object DisplayManagerFlags {
    // PUBLIC/OWN_CONTENT_ONLY 必须取官方常量（OWN_CONTENT_ONLY=1<<3）——
    // 曾误用 1<<5（CAN_SHOW_WITH_INSECURE_KEYGUARD）导致 DMS 拒绝：
    // "Public display must not be marked as SHOW_WHEN_LOCKED_INSECURE"
    const val PUBLIC = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
    const val PRESENTATION = DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
    const val OWN_CONTENT_ONLY = DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
    // Operator 同款：位 6 官方名为 TURN_SCREEN_ON，虚拟屏语境下等效支持触摸事件路由
    const val SUPPORTS_TOUCH = 1 shl 6
    const val SHOULD_SHOW_SYSTEM_DECORATIONS = 1 shl 9
    const val TRUSTED = 1 shl 10
    const val OWN_DISPLAY_GROUP = 1 shl 11
    const val ALWAYS_UNLOCKED = 1 shl 12
    const val TOUCH_FEEDBACK_DISABLED = 1 shl 13
    const val OWN_FOCUS = 1 shl 14
    const val DEVICE_DISPLAY_GROUP = 1 shl 15
    const val STEAL_TOP_FOCUS_DISABLED = 1 shl 16
}
