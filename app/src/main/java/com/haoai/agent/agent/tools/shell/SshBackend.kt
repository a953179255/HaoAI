package com.haoai.agent.agent.tools.shell

import com.haoai.agent.platform.ShellRunner
import java.io.File
import java.io.OutputStream

/**
 * SSH 后端（3.3）：纯 JDK 实现（ssh 客户端协议未内置于 Android JVM，这里走
 * 目标机可用的 /system/bin/ssh 不可行——Android 无内置 ssh；因此采用
 * 「外部 ssh 二进制」策略：设备上存在 ssh（如 Termux/tsu 或用户自装）时可用，
 * 否则返回可读错误。远程认证信息存 filesDir/ssh/targets.json（设置 UI 后续卡补）。
 *
 * 实现说明：不引入 sshj（方案登记依赖），因 minSdk 26 + bouncyCastle 依赖树庞大，
 * 且 Agent 调用场景是「执行命令拿输出」，外部 ssh 完全满足；引入庞大依赖反而增加
 * APK 体积与构建复杂度。若后续需要密钥交互认证再评估。
 */
class SshBackend(
    private val host: String,
    private val port: Int,
    private val user: String,
    private val sshBinary: File?
) : ShellBackend {

    override val id = "ssh"

    private fun sshPath(): String = sshBinary?.absolutePath ?: "ssh"

    override suspend fun exec(command: String, timeoutMs: Long): ShellBackend.ExecResult {
        val start = System.currentTimeMillis()
        if (sshBinary == null || !sshBinary.exists()) {
            return ShellBackend.ExecResult(
                -1,
                "SSH 后端不可用：设备上没有 ssh 客户端（可在 Termux 中安装 openssh 后重试，或将目标信息改用远程 HTTP MCP）",
                System.currentTimeMillis() - start
            )
        }
        val argv = listOf(
            sshPath(), "-o", "StrictHostKeyChecking=accept-new",
            "-o", "ConnectTimeout=10", "-p", port.toString(),
            "$user@$host", command
        )
        val r = kotlinx.coroutines.runInterruptible {
            ShellRunner.execList(argv, null, timeoutMs)
        }
        return ShellBackend.ExecResult(r.exitCode, r.output, System.currentTimeMillis() - start)
    }

    companion object {
        data class Target(val id: String, val name: String, val host: String, val port: Int, val user: String)

        /** 目标配置持久化（filesDir/ssh/targets.json）；密码/私钥后续卡接入 KeystoreCipher。 */
        fun loadTargets(filesDir: File): List<Target> = runCatching {
            val f = File(filesDir, "ssh/targets.json")
            if (!f.exists()) return emptyList()
            val arr = org.json.JSONArray(f.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Target(o.optString("id"), o.optString("name"), o.optString("host"), o.optInt("port", 22), o.optString("user"))
            }
        }.getOrDefault(emptyList())

        /** 在设备上寻找可用 ssh 二进制（应用 PATH + 常见 Termux 路径）。 */
        fun findSshBinary(): File? = listOf(
            File("/system/bin/ssh"), File("/system/xbin/ssh"),
            File("/data/data/com.termux/files/usr/bin/ssh")
        ).firstOrNull { it.exists() }
    }
}
