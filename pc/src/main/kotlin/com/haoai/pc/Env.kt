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

    /**
     * 状态根。
     *
     * 刻意**不用 lazy**：测试用 `-Dhaoai.home` / `System.setProperty` 把它指到临时目录，
     * 而一个 JVM 里多个测试类共享同一个 `Env` —— lazy 会让"第一个碰到它的那个类"定死全局，
     * 结果就是单测把会话写进用户真实的 `%LOCALAPPDATA%\HaoAI\sessions`（实测泄漏了 21 条）。
     * 每次按当前属性值重算，代价是一次 File 构造，换来测试永远污染不到用户数据。
     */
    @Volatile
    private var cachedHome: File? = null

    @Volatile
    private var cachedKey: String? = null

    val home: File
        get() {
            val prop = System.getProperty("haoai.home")?.takeIf { it.isNotBlank() }
            val override = prop ?: System.getenv("HAOAI_HOME")?.takeIf { it.isNotBlank() }
            val key = override ?: "<default>"
            cachedHome?.let { if (cachedKey == key) return it }
            synchronized(this) {
                cachedHome?.let { if (cachedKey == key) return it }
                val dir = if (override != null) File(override) else File(defaultHome(), "HaoAI")
                dir.mkdirs()
                cachedHome = dir
                cachedKey = key
                return dir
            }
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
    val rulesFile: File get() = File(home, "rules.json")
    val memoryFile: File get() = File(home, "MEMORY.md")

    /** 自定义 `/命令`（技能）：名字 + 一段提示词。见 [Skills]。 */
    val skillsFile: File get() = File(home, "skills.json")

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

    /**
     * 把 agent 自己的产物目录写进 `.git/info/exclude`。
     *
     * 为什么不用用户的 `.gitignore`：那是用户的文件，agent 往里加东西等于擅自改仓库。
     * `info/exclude` 是**本地**忽略，不入库、不产生 diff，正好放这种工具垃圾。
     * 不做这一步，用户跑完一次任务 `git status` 就会看到 `.haoai-snap/`、`.haoai-output/`
     * 两条永远不该出现的未跟踪项。
     */
    fun excludeFromGit(workspace: File, vararg names: String) {
        var dir: File? = workspace
        while (dir != null) {
            val git = File(dir, ".git")
            if (git.exists()) {
                val exclude = File(git, "info/exclude")
                runCatching {
                    exclude.parentFile?.mkdirs()
                    val have = if (exclude.isFile) exclude.readText() else ""
                    val missing = names.filter { !have.contains(it) }
                    if (missing.isNotEmpty()) {
                        exclude.appendText("\n# HaoAI PC 的产物目录（本地忽略，不入库）\n" +
                            missing.joinToString("\n") { "/$it/" } + "\n")
                    }
                }
                return
            }
            dir = dir.parentFile
        }
    }

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
