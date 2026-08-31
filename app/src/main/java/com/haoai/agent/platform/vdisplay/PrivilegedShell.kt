package com.haoai.agent.platform.vdisplay

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.Parcel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

/**
 * 4.3 增强：特权 shell 通道（Shizuku 优先、root 兜底）。
 *
 * 虚拟屏上启动"其他 App 的 Activity"受 ActivityTaskManager 的 untrusted 屏检查
 * （系统 App 与严格 ROM 如 Flyme 直接 Permission Denial）；shell uid 天然豁免该
 * 检查——Shizuku 把 adb 的 shell 特权以 Binder 形式交给本 App，等价于
 * `adb shell am start --display`（模拟器已实证该路径能绕过拒绝）。
 *
 * Shizuku 依赖用户安装的 Shizuku 管理器（Apache-2.0，本工程仅依赖其 API 库）；
 * server 由用户经无线调试/root 启动。执行走 UserService（PrivilegedShellService，
 * 服务进程由 server 经 app_process 以 shell uid 拉起，手写 Binder 协议）；无特权
 * 源时回退直启。
 */
object PrivilegedShell {

    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    private const val USER_SERVICE_VERSION = 1

    enum class Status { NOT_INSTALLED, NOT_RUNNING, UNAUTHORIZED, GRANTED }

    val shizukuStatus = MutableStateFlow(Status.NOT_INSTALLED)
    val rootAvailable = MutableStateFlow(false)

    private var listenerRegistered = false
    private var bindRequested = false
    /** UserService 的裸 Binder（onServiceConnected 异步回填；失效置 null 下次重绑）。 */
    private var userServiceBinder: IBinder? = null

    private val connection = object : android.content.ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            userServiceBinder = binder
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            userServiceBinder = null
            bindRequested = false
        }
    }

    data class ExecResult(val exitCode: Int, val output: String) {
        val ok: Boolean get() = exitCode == 0
    }

    fun shizukuUsable(): Boolean = shizukuStatus.value == Status.GRANTED
    fun hasRoot(): Boolean = rootAvailable.value
    fun anyChannel(): Boolean = shizukuUsable() || hasRoot()

    fun shizukuInstalled(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
        true
    }.getOrDefault(false)

    /** 刷新两条通道状态；在设置页与工具调用前调用。失败静默降级。 */
    fun refresh(context: Context) {
        if (!shizukuInstalled(context)) {
            shizukuStatus.value = Status.NOT_INSTALLED
        } else if (runCatching { !Shizuku.pingBinder() }.getOrDefault(true)) {
            shizukuStatus.value = Status.NOT_RUNNING
            userServiceBinder = null
        } else {
            shizukuStatus.value = when (runCatching { Shizuku.checkSelfPermission() }.getOrDefault(-1)) {
                PackageManager.PERMISSION_GRANTED -> Status.GRANTED
                else -> Status.UNAUTHORIZED
            }
        }
        if (!listenerRegistered && shizukuStatus.value != Status.NOT_INSTALLED) {
            listenerRegistered = runCatching {
                Shizuku.addRequestPermissionResultListener(Shizuku.OnRequestPermissionResultListener { _, grantResult ->
                    if (shizukuStatus.value != Status.NOT_INSTALLED) {
                        shizukuStatus.value =
                            if (grantResult == PackageManager.PERMISSION_GRANTED) Status.GRANTED else Status.UNAUTHORIZED
                    }
                })
            }.isSuccess
        }
        if (!rootAvailable.value) {
            if (runCatching { suProbe() }.getOrDefault(false)) rootAvailable.value = true
        }
    }

    private fun suProbe(): Boolean {
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
        val out = p.inputStream.readBytes().decodeToString()
        p.waitFor()
        return p.exitValue() == 0 && out.contains("uid=0")
    }

    /** 弹 Shizuku 授权框（设置页按钮）。 */
    fun requestShizukuPermission(): String? = runCatching {
        if (!Shizuku.pingBinder()) return "Shizuku 未运行：请打开 Shizuku 应用启动服务"
        Shizuku.requestPermission(4301)
        null
    }.getOrElse { "Shizuku 不可用：${it.message}" }

    /** bindUserService 是异步回调模式：先发起绑定，调用方轮询等待回填。 */
    private fun requestUserService() {
        if (bindRequested) return
        bindRequested = true
        runCatching {
            val args = Shizuku.UserServiceArgs(
                ComponentName("com.haoai.agent", "com.haoai.agent.platform.vdisplay.PrivilegedShellService")
            )
                .version(USER_SERVICE_VERSION)
                .processNameSuffix("shell")
                .debuggable(false)
            Shizuku.bindUserService(args, connection)
        }.onFailure { bindRequested = false }
    }

    /** 手写 Binder 调用（协议见 PrivilegedShellService）。 */
    private fun callExec(binder: IBinder, cmd: String): String {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(PrivilegedShellService.DESCRIPTOR)
            data.writeString(cmd)
            binder.transact(PrivilegedShellService.TRANSACTION_EXEC, data, reply, 0)
            reply.readException()
            reply.readString().orEmpty()
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /** Shizuku（shell uid）执行；阻塞读，须在 IO 线程。 */
    suspend fun shizukuExec(cmd: String): ExecResult = withContext(Dispatchers.IO) {
        if (userServiceBinder == null) {
            requestUserService()
            val deadline = System.currentTimeMillis() + 4000
            while (userServiceBinder == null && System.currentTimeMillis() < deadline) delay(100)
        }
        val binder = userServiceBinder ?: return@withContext ExecResult(-1, "Shizuku UserService 绑定未完成，请重试一次")
        runCatching {
            val resp = callExec(binder, cmd)
            val code = resp.lineSequence().firstOrNull()?.toIntOrNull() ?: -1
            ExecResult(code, resp.lineSequence().drop(1).joinToString("\n"))
        }.getOrElse {
            userServiceBinder = null
            bindRequested = false
            ExecResult(-1, "Shizuku 执行失败：${it.message}")
        }
    }

    /** root（su）执行；阻塞读，须在 IO 线程。 */
    suspend fun rootExec(cmd: String): ExecResult = withContext(Dispatchers.IO) {
        runCatching {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            val out = p.inputStream.readBytes().decodeToString()
            val err = p.errorStream.readBytes().decodeToString()
            val code = p.waitFor()
            ExecResult(code, (out.ifBlank { err }).ifBlank { "（无输出）" }.trim())
        }.getOrElse { ExecResult(-1, "root 执行失败：${it.message}") }
    }
}
