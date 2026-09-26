package com.haoai.pc

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date

/**
 * 状态根与环境事实。
 *
 * 手机端所有 store 都认 `filesDir`，PC 端等价物就是这里 —— 一个可被 `HAOAI_HOME`
 * 覆盖的根目录。会话、设置、密钥、用量账本全在它下面，工作区**不在**它下面
 * （工作区是用户的代码目录，默认当前目录，由 `haoai init`/`--cwd` 指定）。
 *
 * 参照 hermes 在 Windows 上的做法：`%LOCALAPPDATA%\<App>`，不是 `%APPDATA%`
 * （后者会跟着漫游配置文件一起同步到域内其他机器，把密钥也带过去）。
 */
object Env {

    val home: File by lazy {
        // 测试用 -Dhaoai.home=… 把状态根指到临时目录；环境变量 HAOAI_HOME 给用户自己换盘用。
        val prop = System.getProperty("haoai.home")?.takeIf { it.isNotBlank() }
        val override = prop ?: System.getenv("HAOAI_HOME")?.takeIf { it.isNotBlank() }
        val dir = if (override != null) File(override) else File(defaultHome(), "HaoAI")
        dir.mkdirs()
        dir
    }

    private fun defaultHome(): File {
        val local = System.getenv("LOCALAPPDATA")
        if (!local.isNullOrBlank()) return File(local)
        val appData = System.getenv("APPDATA")
        if (!appData.isNullOrBlank()) {
            // APPDATA 在 Windows 上是 Roaming，退一步用它但换个非漫游味道的位置
            return File(File(appData).parentFile?.parentFile ?: File(appData), "AppData/Local")
        }
        return File(System.getProperty("user.home"), ".local/share")
    }

    val settingsFile: File get() = File(home, "settings.json")
    val apiKeyFile: File get() = File(home, "apikey")
    val sessionsDir: File get() = File(home, "sessions").apply { mkdirs() }
    val logsDir: File get() = File(home, "logs").apply { mkdirs() }
    val approvalsFile: File get() = File(home, "approvals.json")
    val memoryFile: File get() = File(home, "MEMORY.md")

    /** 工具溢出落文件的目录名 —— 与手机端 `.haoai-output/` 保持同名，便于两套实现对照。 */
    const val TOOL_OUTPUT_DIR = ".haoai-output"

    val osName: String get() = System.getProperty("os.name") ?: "unknown"
    val isWindows: Boolean get() = osName.lowercase().startsWith("win")

    /** 给用户看的"环境事实"，进系统提示，让模型知道自己在什么机器上干活。 */
    fun facts(workspace: File): String = buildString {
        appendLine("操作系统：$osName ${System.getProperty("os.arch")}")
        appendLine("用户目录：${System.getProperty("user.home")}")
        appendLine("工作区（绝对路径）：${abs(workspace)}")
        appendLine("状态根 HAOAI_HOME：${abs(home)}")
        appendLine("当前时间：${SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date())}")
        if (isWindows) {
            appendLine("注意：Windows 上路径分隔符是 \\，但工具入参用 / 也认（内部会归一）。")
            appendLine("shell 可选 pwsh（PowerShell 7+ 或 Windows PowerShell）与 bash（Git Bash）。")
        }
    }

    fun abs(f: File): String = runCatching { f.canonicalFile.absolutePath }.getOrElse { f.absolutePath }

    @Volatile
    var verbose: Boolean = false

    private val stamp = SimpleDateFormat("HH:mm:ss")

    /** 一切诊断都往这里走：手机端是 android.util.Log，PC 端是文件 + stderr。 */
    fun log(tag: String, msg: String) {
        val line = "${stamp.format(Date())} $tag: $msg"
        runCatching {
            val f = File(logsDir, "haoai-${SimpleDateFormat("yyyyMMdd").format(Date())}.log")
            f.appendText(line + "\n")
        }
        if (verbose) System.err.println(line)
    }
}
