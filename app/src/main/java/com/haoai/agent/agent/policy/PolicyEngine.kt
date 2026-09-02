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

    data class WriteOp(
        val tool: String,
        val path: String,
        val summary: String,
        /** 新建文件标记（无原文可 diff，弹窗显示"新建文件"）。 */
        val isNewFile: Boolean = false,
        /** 行级 diff（1.3 审查视图数据源）；空 = 无 diff 可展示。 */
        val diff: List<com.haoai.agent.ui.common.DiffLine> = emptyList()
    ) : ApprovalRequest() {
        override val title = "写入文件（$tool）"
        override val detail = "$path\n\n$summary"
        override val mono = true
    }

    data class Generic(val tool: String, val summary: String) : ApprovalRequest() {
        override val title = "调用工具（$tool）"
        override val detail = summary
        override val mono = true
    }

    /** C1/C6 配置变更审批：detail 为人话变更清单（新增/修改/删除模型服务、设置项 a → b）。 */
    data class ConfigChange(val summary: String) : ApprovalRequest() {
        override val title = "代理请求修改应用配置"
        override val detail = summary
        override val mono = false
    }
}

class PolicyEngine(private val mode: PermissionMode) {

    companion object {
        /**
         * 外部模块（MCP）注册的工具风险覆盖：返回 null 走默认表。
         * MCP 工具默认按 WRITE 询问；用户降级的只读 server 工具按 READ 免审。
         */
        @Volatile var riskOverride: ((String) -> RiskLevel?)? = null
    }

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
        "flash_image", "fastboot", "su", "sudo"
    )

    /** 递归删除的目标是否属于"灾难级"路径（根/主目录/系统分区），普通子路径不受影响。 */
    private fun isRootish(path: String): Boolean {
        val p = path.trim('"', '\'').trimEnd('/')
        return p.isEmpty() || p in setOf(
            "~", "~/*", ".", "..", "*", "/*",
            "/system", "/data", "/vendor", "/etc", "/etc/*",
            "/sdcard", "/storage", "/mnt"
        )
    }

    /** rm 的参数里是否带递归旗标（-r/-R/-rf 等短旗标组合或 --recursive）。 */
    private fun hasRecursiveFlag(args: List<String>): Boolean =
        args.any {
            (it.startsWith("-") && !it.startsWith("--") && it.any { c -> c == 'r' || c == 'R' }) ||
                it == "--recursive"
        }

    fun riskOf(toolName: String): RiskLevel =
        riskOverride?.invoke(toolName) ?: when (toolName) {
        "bash" -> RiskLevel.EXEC
        "write", "edit" -> RiskLevel.WRITE
        "config_set" -> RiskLevel.WRITE // C6：配置写恒审批（引擎另有特判，YOLO 下同样强制弹窗）
        "tap", "swipe", "type_text", "key" -> RiskLevel.EXEC
        "browser_search", "browser_open" -> RiskLevel.EXEC
        // 4.2 内置浏览器：导航/截图/读结构不打扰用户走 READ 免审；点击/输入/滚动/后退改页面状态按 WRITE 审批
        "browser_navigate", "browser_screenshot" -> RiskLevel.READ
        "browser_click", "browser_input", "browser_scroll", "browser_back" -> RiskLevel.WRITE
        // 4.3 虚拟屏后台自动化：读屏免审；启动目标 App 到屏 EXEC；其余改屏内状态按 WRITE
        "vscreen_launch" -> RiskLevel.EXEC
        "vscreen_screen" -> RiskLevel.READ
        "vscreen_tap", "vscreen_text", "vscreen_scroll", "vscreen_back", "vscreen_home", "vscreen_close" -> RiskLevel.WRITE
        "launch_app" -> RiskLevel.WRITE
        "calendar_create" -> RiskLevel.WRITE
        "alarm_set" -> RiskLevel.EXEC
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
        // 结构化兜底：正则挡不住的长选项/引号/多目标形态（rm --recursive --force "/" 等）
        for (seg in splitSegments(command)) {
            val tokens = seg.trim().split(Regex("\\s+"))
            when (tokens.firstOrNull()?.substringAfterLast('/')?.lowercase()) {
                "rm" -> {
                    val args = tokens.drop(1)
                    if (hasRecursiveFlag(args) &&
                        args.filterNot { it.startsWith("-") }.any { isRootish(it) }
                    ) {
                        return "命令被安全策略拦截：禁止递归删除根目录/系统分区/主目录"
                    }
                }
                "find" -> {
                    val args = tokens.drop(1)
                    if ("-delete" in args &&
                        args.firstOrNull { !it.startsWith("-") }?.let { isRootish(it) } == true
                    ) {
                        return "命令被安全策略拦截：禁止 find -delete 批量删除根级/系统目录"
                    }
                }
                else -> Unit
            }
        }
        // curl/wget … | sh|bash：远程代码执行
        if (Regex("(curl|wget)[^|;]*\\|\\s*(ba)?sh\\b", RegexOption.IGNORE_CASE).containsMatchIn(command)) {
            return "命令被安全策略拦截：不允许把下载内容直接管道给 shell 执行"
        }
        // bash <(curl …)：进程替换等价于下载即执行（不走管道，正则漏网形态）
        if (Regex("\\b(ba)?sh\\s+<\\([^)]*(curl|wget)", RegexOption.IGNORE_CASE).containsMatchIn(command)) {
            return "命令被安全策略拦截：不允许把下载内容直接交给 shell 执行"
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

    /**
     * 取段内实际命令字：跳过 env 赋值前缀与 nohup/timeout/env/xargs 等包装。
     * 全部按 basename 识别（/system/bin/sh、/usr/bin/timeout 不再绕过）；
     * sh -c 递归检查内层；timeout/xargs 跳过 flag 与时长后继续检查真正的命令。
     */
    private fun baseCommandOf(segment: String): String? {
        var tokens = segment.trim().split(Regex("\\s+"))
        while (tokens.isNotEmpty()) {
            val first = tokens.first()
            if (first.contains('=') && !first.startsWith("-")) {
                tokens = tokens.drop(1); continue
            }
            when (val head = first.substringAfterLast('/')) {
                "nohup", "time", "exec", "nice", "ionice", "env", "stdbuf", "setsid", "taskset" -> {
                    tokens = tokens.drop(1)
                    // nice -n 5 这类「flag + 独立值」：纯数字/时长也一并跳过
                    while (tokens.isNotEmpty() &&
                        (tokens.first().startsWith("-") || Regex("^\\d+[a-zA-Z]?$").matches(tokens.first()))
                    ) tokens = tokens.drop(1)
                    continue
                }
                "timeout" -> {
                    // timeout [FLAG]… DURATION CMD：之前只识别 timeout -FLAG 形式，
                    // timeout 10 su 会绕过检查——跳过 flag 与时长后继续
                    var i = 1
                    while (i < tokens.size && tokens[i].startsWith("-")) i++
                    if (i < tokens.size) i++ // duration（10 / 10s / 1m）
                    if (i < tokens.size) { tokens = tokens.drop(i); continue }
                    return head
                }
                "xargs" -> {
                    var i = 1
                    while (i < tokens.size && tokens[i].startsWith("-")) i++
                    if (i < tokens.size) { tokens = tokens.drop(i); continue }
                    return head
                }
                "bash", "sh", "ash", "zsh", "mksh", "dash" -> {
                    // sh -c "<inner>"：内层命令才是真正要跑的，递归检查
                    if (tokens.size > 1 && tokens[1].matches(Regex("^-[a-zA-Z]*c[a-zA-Z]*$"))) {
                        val inner = segment.substringAfter(tokens[1], "").trim()
                            .trim('"', '\'')
                        if (inner.isNotBlank()) return baseCommandOf(inner) ?: head
                    }
                    return head
                }
                else -> return head
            }
        }
        return null
    }
}
