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

    // 注：companion object（riskOverride + TOOL_RISK 风险表）在文件下方 riskOf 之后声明。

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

    /**
     * 工具风险等级登记表。
     *
     * **三条纪律**（2026-10-10 C2/M10 修复后固化）：
     *  1. **穷尽登记**：本表必须覆盖 [ToolRegistry] 注册的每一个工具名。漏一个，
     *     它就会掉进else 兜底；漏登的代价必须比不登记更小，所以 else 是 fail-safe。
     *  2. **else = WRITE（fail-safe）**，不是 READ。原先 `else -> READ` 等于
     *     "任何新工具默认免审批"——注册表加一行就是一次静默的权限降级，
     *     且没有任何测试会红（测试普遍跑 YOLO 档，恒免审）。
     *     改成 WRITE 后漏登的代价变成"多问一次批准"，而不是"用户不知情下被执行"。
     *  3. **有单测兜底**：`PolicyEngineRiskTableTest` 用反射断言
     *     "ToolRegistry 能注册的全部工具名 ⊆ 本表键集合"，下次新增工具漏登记时测试变红。
     *
     * 本表被三处直接读取（都不走 requiresApproval），改错会连带击穿：
     *  - `EngineToolRun.planGate`：Plan 模式只放行 READ。误判 WRITE ⇒ Plan 模式失能。
     *  - `AgentEngine` 并行分组：要求 `riskOf == READ && in PARALLEL_SAFE`。
     *    误判 WRITE ⇒ 同轮多文件搜索退化为串行。
     *  - `closeDanglingCalls`：决定恢复话术是"可安全重试"还是"禁止盲目重跑"。
     */
    /**
     * 工具 → 风险等级登记表（穷尽式；键集合由单测 `PolicyEngineRiskTableTest` 对着
     * ToolRegistry 的实际注册清单做包含性断言，新增工具漏登记时测试变红）。
     *
     * **两条纪律**（2026-10-10 C2/M10 修复后固化）：
     *  1. **穷尽登记**：每个能注册的工具名都必须在本表里出现一次。不在本表的走
     *     [RISK_FALLBACK]，代价是"多问一次批准"——这是刻意选的不对称：漏登的代价必须
     *     小于不判定的代价。原先的 `else -> READ` 反过来，等于"新工具默认免审批"，
     *     注册表加一行就是一次静默的权限降级，且没有任何测试会红（测试普遍跑 YOLO 档，
     *     `requiresApproval` 恒 false，查不出风险等级错配）。
     *  2. **本表被三处直接读取**（都不走 requiresApproval），改错会连带击穿：
     *     - `EngineToolRun.planGate`：Plan 模式只放行 READ。误判 WRITE ⇒ Plan 模式失能
     *     （不能读文件/看屏/压缩上下文/切工具组）。
     *  - `AgentEngine` 并行分组：要求 `riskOf == READ && in PARALLEL_SAFE`。
     *     误判 WRITE ⇒ 同轮多文件搜索退化为串行。
     *  - `closeDanglingCalls`：决定恢复话术是"只读工具可安全重试"还是"禁止盲目重跑"。
     *  - `WorkflowRunner`：无人值守工作流在 ASK_WRITES 档对非 READ 直接抛"审批拒绝"，
     *     误判 WRITE ⇒ 工作流整单失败。
     */
    fun riskOf(toolName: String): RiskLevel =
        riskOverride?.invoke(toolName)
            ?: TOOL_RISK[toolName]
    // fail-safe 兜底：未登记 =WRITE。理由见上方纪律 1。
            ?: RiskLevel.WRITE

    companion object {
        /**
         * 外部模块（MCP）注册的工具风险覆盖：返回 null 走 [TOOL_RISK]（未登记则 WRITE）。
         * MCP 工具名带 `mcp_` 前缀，这个通道对它们**永不返回 null** —— 用户显式降级为
         * read 级的 server 返 READ，其余一律 WRITE，不受本表兜底影响。
         */
        @Volatile var riskOverride: ((String) -> RiskLevel?)? = null

        /** EXEC：跑命令 / 系统级操作。 */
        private val EXEC = RiskLevel.EXEC
        private val WRITE = RiskLevel.WRITE
        private val READ = RiskLevel.READ

        /**
         * 工具名 → 风险等级。**新增工具必须在此登记**（见 [riskOf] 的纪律 1）。
         *
         * 分类口径：能否被"读一遍就还原"——能且只读 = READ；会改盘/改配置/改屏幕/发外部
         * 请求 = WRITE；跑命令、拉外部应用、系统破坏性操作 = EXEC。
         */
        internal val TOOL_RISK: Map<String, RiskLevel> = buildMap {
            // ── EXEC：执行类 ──
            put("bash", EXEC)
            put("tap", EXEC); put("swipe", EXEC)
            put("type_text", EXEC); put("key", EXEC)
            put("browser_search", EXEC); put("browser_open", EXEC)
            put("vscreen_launch", EXEC)
            put("alarm_set", EXEC)

   // ── WRITE：写盘 / 改配置 / 改屏幕状态 / 发外部请求 ──
            put("write", WRITE); put("edit", WRITE)
        // C6：配置写恒审批（引擎另有特判，YOLO 下同样强制弹窗）
            put("config_set", WRITE)
   // 起草与删除工作流均改配置：delete 曾因默认 READ 在 ASK_WRITES 下免审批
            //（可配合路径穿越删文件）
     put("workflow_save", WRITE)
  put("browser_click", WRITE); put("browser_input", WRITE)
  put("browser_scroll", WRITE); put("browser_back", WRITE)
    // scroll / find 会真改屏（swipe / 找不到时自动滑动），不是纯读
    put("scroll", WRITE); put("find", WRITE)
            // 拉起相机 App + 写 JPG 到外存
   put("camera", WRITE)
         // M10：todo 是**全量替换 + 写盘**语义（传完整清单覆盖 todos.json）。
        // 原先落到兜底（当时是 READ），在 ASK_WRITES 档下改任务清单全程免审批。
  // 任务清单是长任务的进度真源，被模型随意覆盖属于不可逆的状态改动。
     put("todo", WRITE)
 put("launch_app", WRITE)
      // open_uri 拉起系统页/外部组件，与 launch_app 同级
     put("open_uri", WRITE)
            put("calendar_create", WRITE)
       // spawn_* 会改工作区文件（work 模式）；skill 落盘技能包；
       // steer/stop_agent 改变运行中的子代理状态
        put("spawn_agent", WRITE); put("spawn_agents", WRITE)
            put("steer_agent", WRITE); put("stop_agent", WRITE)
       put("skill", WRITE)
         // memory 是混合语义：search/list/recall 纯读，save/journal/forget/merge 写盘。
            // 按 WRITE 登记（写盘分支不可逆），代价是"回忆也要批准"——与 todo 同级取舍。
  put("memory", WRITE)

   // vscreen_tap_xy / vscreen_swipe_xy 曾漏登记（C2）→ 落到兜底（当时是 READ），
         // 但它们走 root/shizuku 执行 `input -d <屏> tap|swipe`，与 vscreen_tap 同级。
            // 漏登记 = 带 root 能力的触摸注入在 minimal 档位也全程免审批。
        put("vscreen_tap", WRITE); put("vscreen_tap_xy", WRITE); put("vscreen_swipe_xy", WRITE)
  put("vscreen_text", WRITE); put("vscreen_scroll", WRITE)
            put("vscreen_back", WRITE); put("vscreen_home", WRITE); put("vscreen_close", WRITE)

            // ── READ：纯只读 ──
  // 文件与检索（PARALLEL_SAFE 的主力，误判 WRITE 会让并发退化为串行）
      put("read", READ); put("grep", READ); put("glob", READ)
            put("web_fetch", READ); put("web_search", READ)
            put("job_output", READ)
put("browser_read", READ); put("browser_find", READ)
  put("browser_navigate", READ); put("browser_screenshot", READ)
          put("vscreen_screen", READ)
            put("app_status", READ); put("config_get", READ)
  put("list_apps", READ); put("session_search", READ)
         // a11y 读屏与等待轮询
            put("screen", READ); put("wait", READ)
         put("clipboard_read", READ); put("calendar_query", READ)
       put("contacts_search", READ); put("notifications_read", READ)
    // 读图 + 本地 ML 推理，不发外部请求、不改本机状态
   put("ocr_image", READ)
          put("location", READ)
     put("schedule", READ) // 读定时任务列表
   // 委派与子代理状态查询：只读文件并发请求
    put("delegate_to_vision", READ); put("transcribe_audio", READ)
            put("collect_agent", READ)
            // handoff / tools_enable 改会话内部状态（压缩历史、切工具组）。
   // 保持 READ：误判 WRITE 会让 Plan 模式无法压缩上下文 / 无法切换工具组。
            put("handoff", READ); put("tools_enable", READ)
            // ask_user 主动提问永远免审：它是"问用户"本身，弹审批就死锁（batch 同理）
            put("ask_user", READ); put("ask_user_batch", READ)
    }
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
