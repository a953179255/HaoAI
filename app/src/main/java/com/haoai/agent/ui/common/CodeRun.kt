package com.haoai.agent.ui.common

/**
 * 批3e：代码块内联运行的纯逻辑层（无 Compose/进程依赖，进 JVM 单测）。
 *
 * 语言→执行通道：shell 系直接喂后端 sh；python/js 走沙箱解释器；
 * 其余（编译型/未知）不可运行，UI 不给运行钮。后端只有 toybox（未装 Linux
 * 发行版）时，python/js 同样不可运行——诚实降级，不假装能跑。
 */

object CodeRun {

    /** 执行通道（决定解释器与临时文件后缀）。 */
    enum class Channel(val ext: String, val interpreter: String) {
        SHELL(".sh", "sh"),
        PYTHON(".py", "python3"),
        NODE(".js", "node")
    }

    /** 临时运行文件统一前缀（不含用户输入，cmdFor 校验用）。 */
    const val RUN_FILE_PREFIX = ".haoai_run_"

    /**
     * 围栏语言标签归一。口径：只认"直接可执行"的通道——console/prompt 块常带
     * `$ ` 前缀的示范输出（喂给 sh 会跑出莫名错误）、ts 需要编译器（guest 多数
     * 没装），宁可不给运行钮也不给误导。
     */
    fun channelOf(lang: String): Channel? = when (lang.trim().lowercase()) {
        "bash", "sh", "shell", "zsh" -> Channel.SHELL
        "python", "python3", "py" -> Channel.PYTHON
        "js", "javascript", "node" -> Channel.NODE
        else -> null
    }

    /**
     * 该通道在当前后端下能否运行：shell 只要有文件系统工作区就行（toybox 恒在）；
     * python/js 需要 Linux 沙箱解释器。
     */
    fun available(channel: Channel, hasSandbox: Boolean): Boolean =
        channel == Channel.SHELL || hasSandbox

    /**
     * 落成执行命令。脚本体由调用方（VM，IO 线程）直接写成工作区里的临时文件，
     * 这里只负责"跑它并透传退出码"——零引号地狱、零展开面（多行/引号/$ 的脚本
     * 原样进解释器）。文件名是调用方生成的一次性名（前缀 + 随机段，不含用户输入），
     * 相对路径同时适配 toybox（cwd=宿主工作区）与 proot（guest workdir=/workspace
     * 即同一绑定目录）；跑完即删，调用方 finally 再兜一层孤儿清扫。
     */
    fun cmdFor(channel: Channel, fileName: String): String {
        require(fileName.startsWith(RUN_FILE_PREFIX) && fileName.endsWith(channel.ext)) {
            "运行临时文件必须走统一命名"
        }
        val q = '\''
        return "${channel.interpreter} $q$fileName$q; rc=\$?; rm -f $q$fileName$q; exit \$rc"
    }

    /** 生成一次性临时文件名（进程内随机段防撞；纯逻辑便于单测形状）。 */
    fun tempFileName(channel: Channel): String =
        RUN_FILE_PREFIX + java.util.UUID.randomUUID().toString().take(12) + channel.ext

    /** 运行输出上限：超过取头尾各半，中间注明省略多少行（结果就地展开，不能刷屏）。 */
    fun capOutput(output: String, maxChars: Int = 6000): String {
        if (output.length <= maxChars) return output
        val half = maxChars / 2
        val head = output.take(half)
        val tail = output.takeLast(half)
        val dropped = output.lines().size - head.lines().size - tail.lines().size
        return "$head\n…（省略中间 $dropped 行）…\n$tail"
    }
}

/** 一次内联运行的结果（UI 就地展开用）。 */
data class RunOutcome(
    val exitCode: Int,
    /** 已截断的输出正文。 */
    val output: String,
    /** 实际后端 id（toybox/linux），标在结果卡头。 */
    val backend: String,
    val durationMs: Long
)

/**
 * 运行环境快照（ChatScreen 组合期用 IO 现算一次缓存）：
 * shellReady=工作区是文件系统后端（SAF 没有 shell）；sandboxReady=Linux 发行版已装。
 */
data class RunEnv(val shellReady: Boolean = false, val sandboxReady: Boolean = false) {
    fun supports(lang: String): Boolean {
        val ch = CodeRun.channelOf(lang) ?: return false
        return shellReady && CodeRun.available(ch, sandboxReady)
    }
}

/**
 * 批3e：代码块运行通道（ChatScreen 顶层 provide，与 LocalOpenToolSheet 同模式）。
 * 深层 CodeBlock 上抛 (lang, code) → VM 起后端执行；未 provide 的宿主（设置页
 * 预览等）默认实现返回 null + RunEnv 空态 → 运行钮根本不出现。
 */
val LocalRunCode = androidx.compose.runtime.staticCompositionLocalOf<
    suspend (String, String) -> RunOutcome?
> { { _, _ -> null } }

val LocalRunEnv = androidx.compose.runtime.staticCompositionLocalOf<RunEnv> { RunEnv() }
