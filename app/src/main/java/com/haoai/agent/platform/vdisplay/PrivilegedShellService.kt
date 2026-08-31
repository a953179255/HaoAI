package com.haoai.agent.platform.vdisplay

import android.annotation.SuppressLint
import android.hardware.display.DisplayManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Parcel
import android.view.Surface

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
                val surface = Surface.CREATOR.createFromParcel(data)
                val displayId = runCatching { createTrustedDisplay(w, h, dpi, surface) }
                    .onFailure { android.util.Log.e("HaoAIVD", "createTrustedDisplay failed", it) }
                    .getOrDefault(-1)
                reply?.writeNoException()
                reply?.writeInt(displayId)
                true
            }
            TRANSACTION_RELEASE_DISPLAY -> {
                data.enforceInterface(DESCRIPTOR)
                releaseDisplay(data.readInt())
                reply?.writeNoException()
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
        val text = (out.ifBlank { err }).ifBlank { "(no output)" }.trim()
        return "$rc\n$text"
    }

    // ---------- 可信虚拟屏（Operator-on-Android 同款机制） ----------

    private var currentDisplayId = -1
    private var currentToken: IBinder? = null
    private var currentW = 0
    private var currentH = 0
    private var currentDpi = 0

    /** 同规格重复调用幂等（只换 surface）；失败返回 -1。 */
    @SuppressLint("BlockedPrivateApi", "SoonBlockedPrivateApi")
    private fun createTrustedDisplay(w: Int, h: Int, dpi: Int, surface: Surface): Int {
        if (currentDisplayId != -1 && currentW == w && currentH == h && currentDpi == dpi) {
            setSurface(currentDisplayId, surface)
            return currentDisplayId
        }
        if (currentDisplayId != -1) releaseDisplay(currentDisplayId)

        val idm = requireIDisplayManager() ?: return -1
        val token = Binder()
        val callbackClass = Class.forName("android.hardware.display.IVirtualDisplayCallback")
        val callbackProxy = java.lang.reflect.Proxy.newProxyInstance(
            callbackClass.classLoader, arrayOf(callbackClass)
        ) { _, method, args ->
            when (method.name) {
                "asBinder" -> token
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
            currentToken = token
            currentW = w; currentH = h; currentDpi = dpi
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

    private fun releaseDisplay(displayId: Int) {
        if (displayId != currentDisplayId) return
        val token = currentToken ?: return
        runCatching {
            val idm = requireIDisplayManager() ?: return
            idm.javaClass.methods.firstOrNull { it.name == "releaseVirtualDisplay" }?.let {
                it.isAccessible = true
                it.invoke(idm, token)
            }
        }
        currentDisplayId = -1
        currentToken = null
    }

    private fun requireIDisplayManager(): Any? = runCatching {
        // ServiceManager 为 hidden 类：反射取 display 服务 binder
        val binder = Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java)
            .invoke(null, "display") as? IBinder ?: return null
        Class.forName("android.hardware.display.IDisplayManager\$Stub")
            .getMethod("asInterface", IBinder::class.java)
            .invoke(null, binder)
    }.getOrNull()

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
