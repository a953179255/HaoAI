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

    fun checkShellBlocked(command: String): String? =
        shellBlacklist.firstOrNull { it.containsMatchIn(command) }?.let {
            "命令被安全策略拦截：匹配高危模式「${it.pattern.take(40)}…」"
        }
}
