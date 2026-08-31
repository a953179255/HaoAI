package com.haoai.agent.platform.vdisplay

import android.os.Binder
import android.os.IBinder
import android.os.Parcel

/**
 * Shizuku UserService：由 Shizuku server 经 app_process 以 shell uid 启动，
 * 进程内 Runtime.exec 即等价 adb shell。Binder 协议手写（两端同一 APK，
 * 描述符/事务码自洽；AIDL 在含中文的工程路径下 dep 文件解析有编码 bug，故不用）。
 */
class PrivilegedShellService : Binder() {

    companion object {
        const val DESCRIPTOR = "com.haoai.agent.platform.vdisplay.IPrivilegedShellService"
        const val TRANSACTION_EXEC = FIRST_CALL_TRANSACTION
        const val TRANSACTION_EXIT = FIRST_CALL_TRANSACTION + 1
    }

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
            else -> super.onTransact(code, data, reply, flags)
        }
    }

    private fun exec(cmd: String): String {
        val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
        val out = p.inputStream.readBytes().decodeToString()
        val err = p.errorStream.readBytes().decodeToString()
        val rc = p.waitFor()
        val text = (out.ifBlank { err }).ifBlank { "(no output)" }.trim()
        return "$rc\n$text"
    }

    private fun exit() {
        System.exit(0)
    }
}
