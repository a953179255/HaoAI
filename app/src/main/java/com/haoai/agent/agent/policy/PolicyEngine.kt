package com.haoai.agent.agent.policy

import kotlinx.serialization.Serializable

enum class RiskLevel { READ, WRITE, EXEC }

enum class PermissionMode { ALWAYS_ASK, ASK_WRITES, YOLO }

sealed class ApprovalRequest {
    abstract val title: String
    abstract val detail: String
    open val mono: Boolean get() = false

    data class ExecOp(val command: String) : ApprovalRequest() {
        override val title = "执行命令"
        override val detail = command
        override val mono = true
    }

    data class WriteOp(val tool: String, val path: String, val summary: String) : ApprovalRequest() {
        override val title = "写入文件（$tool）"
        override val detail = "$path\n\n$summary"
        override val mono = true
    }

    data class Generic(val tool: String, val summary: String) : ApprovalRequest() {
        override val title = "调用工具（$tool）"
        override val detail = summary
        override val mono = true
    }
}

class PolicyEngine(private val mode: PermissionMode) {

    private val shellBlacklist = listOf(
        Regex("(^|[;&|\\s])sudo\\s", RegexOption.IGNORE_CASE),
        Regex("\\brm\\s+[^\\n]*-[a-zA-Z]*[rf][a-zA-Z]*[rf][a-zA-Z]*\\s+(\"?/?\"?\\s*|/\\*|~/*)($|\\s)", RegexOption.IGNORE_CASE),
        Regex("\\brm\\s+-[a-zA-Z]*r[a-zA-Z]*f[a-zA-Z]*\\s+(/|~/|\\.\\./|\\.\\.\\\\)", RegexOption.IGNORE_CASE),
        Regex("\\bmkfs(\\.\\w+)?\\b", RegexOption.IGNORE_CASE),
        Regex("\\bdd\\s+[^\\n]*if=/dev/(zero|urandom|random)", RegexOption.IGNORE_CASE),
        Regex(":\\(\\)\\s*\\{[^}]*\\}\\s*;\\s*:"),
        Regex("(^|[;&|\\s])(shutdown|reboot|poweroff|halt)(\\s|$)", RegexOption.IGNORE_CASE),
        Regex("\\bchmod\\s+-R\\s+777\\s+/", RegexOption.IGNORE_CASE),
        Regex(">\\s*/dev/(sd|nvme|hd)[a-z]", RegexOption.IGNORE_CASE),
        Regex("\\bdel\\s+/[sq]\\s", RegexOption.IGNORE_CASE),
        Regex("\\bformat\\s+[a-zA-Z]:", RegexOption.IGNORE_CASE),
        Regex("\\bdiskutil\\s+eraseDisk", RegexOption.IGNORE_CASE)
    )

    /** 无论参数如何，基础命令本身即高危（分段提取命令字后匹配）。 */
    private val blockedCommands = setOf(
        "mkfs", "mkfs.ext2", "mkfs.ext3", "mkfs.ext4", "mkfs.vfat", "mkfs.exfat", "mkfs.f2fs",
        "shutdown", "reboot", "poweroff", "halt", "fdisk", "parted", "diskutil",
        "flash_image", "fastboot", "su"
    )

    fun riskOf(toolName: String): RiskLevel = when (toolName) {
        "bash" -> RiskLevel.EXEC
        "write", "edit" -> RiskLevel.WRITE
        "tap", "swipe", "type_text", "key" -> RiskLevel.EXEC
        "launch_app" -> RiskLevel.WRITE
        else -> RiskLevel.READ
    }

    fun requiresApproval(toolName: String): Boolean = when (mode) {
        PermissionMode.YOLO -> false
        PermissionMode.ASK_WRITES -> riskOf(toolName) != RiskLevel.READ
        PermissionMode.ALWAYS_ASK -> true
    }

    /**
     * 高危命令拦截。黑名单正则易被引号/变量/包装绕过，因此叠加结构化检查：
     * 按 ; && || | 换行与 $() 反引号切分命令段，剥掉 env 前缀后取命令字，
     * 命令字命中 blockedCommands 或「下载管道执行」模式即拦截。
     */
    fun checkShellBlocked(command: String): String? {
        shellBlacklist.firstOrNull { it.containsMatchIn(command) }?.let {
            return "命令被安全策略拦截：匹配高危模式「${it.pattern.take(40)}…」"
        }
        for (seg in splitSegments(command)) {
            val base = baseCommandOf(seg) ?: continue
            if (base.lowercase() in blockedCommands) {
                return "命令被安全策略拦截：禁止使用「$base」"
            }
        }
        // curl/wget … | sh|bash：远程代码执行
        if (Regex("(curl|wget)[^|;]*\\|\\s*(ba)?sh\\b", RegexOption.IGNORE_CASE).containsMatchIn(command)) {
            return "命令被安全策略拦截：不允许把下载内容直接管道给 shell 执行"
        }
        return null
    }

    /** 递归切分：运算符 + $() / 反引号内层命令都作为独立段检查。 */
    private fun splitSegments(command: String): List<String> {
        val out = mutableListOf<String>()
        fun walk(s: String) {
            s.split(';', '\n').forEach { part ->
                part.split("&&", "||", "|").forEach { seg ->
                    var t = seg.trim()
                    // 展开 $(...) 与 `...` 的内层
                    Regex("\\$\\((.*?)\\)").findAll(t).forEach { walk(it.groupValues[1]) }
                    Regex("`([^`]*)`").findAll(t).forEach { walk(it.groupValues[1]) }
                    t = t.replace(Regex("\\$\\(.*?\\)"), " ").replace(Regex("`[^`]*`"), " ")
                    if (t.isNotBlank()) out.add(t)
                }
            }
        }
        walk(command)
        return out
    }

    /** 取段内实际命令字：跳过 env 赋值前缀与 nohup/timeout 等包装。 */
    private fun baseCommandOf(segment: String): String? {
        var tokens = segment.trim().split(Regex("\\s+"))
        while (tokens.isNotEmpty()) {
            val first = tokens.first()
            if (first.contains('=') && !first.startsWith("-")) {
                tokens = tokens.drop(1); continue
            }
            if (first in setOf("nohup", "time", "exec", "nice")) {
                tokens = tokens.drop(1); continue
            }
            if ((first == "timeout" || first == "nice") && tokens.size > 1 && tokens[1].startsWith("-")) {
                tokens = tokens.drop(1); continue
            }
            if (first in setOf("bash", "sh", "ash", "zsh") && tokens.size > 1 &&
                (tokens[1] == "-c" || tokens[1] == "-lc")
            ) {
                // sh -c "<inner>"：内层命令才是真正要跑的，递归检查
                val inner = segment.substringAfter(tokens[1], "").trim()
                    .trim('"', '\'')
                if (inner.isNotBlank()) return baseCommandOf(inner) ?: first
            }
            return first.substringAfterLast('/')
        }
        return null
    }
}
