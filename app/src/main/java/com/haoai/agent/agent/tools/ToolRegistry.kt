package com.haoai.agent.agent.tools

import com.haoai.agent.agent.provider.ApiFunctionCall
import com.haoai.agent.agent.provider.ApiTool
import com.haoai.agent.agent.provider.ApiToolCall
import com.haoai.agent.agent.provider.ApiToolDef

object ToolRegistry {

    /** E4b 工具分层：core 恒开；extended/mcp 需 tools_enable 启用（会话级保持，null=全开兼容旧会话）。 */
    const val GROUP_CORE = "core"
    const val GROUP_EXTENDED = "extended"
    const val GROUP_MCP = "mcp"
    val ALL_GROUPS = setOf(GROUP_CORE, GROUP_EXTENDED, GROUP_MCP)

    /** extended 组成员（a11y/内置浏览器/虚拟屏/相机定位/设备工具包/工作流）。 */
    private val EXTENDED_TOOLS = setOf(
        // 无障碍自动化（open_uri 在 core 组：直达导航是基础能力，常驻注入免 tools_enable 绕行）
        "screen", "tap", "swipe", "scroll", "find", "wait", "type_text", "key", "launch_app", "list_apps",
        // 系统浏览器 + 内置浏览器
        "browser_search", "browser_open", "browser_navigate", "browser_read", "browser_click",
        "browser_input", "browser_scroll", "browser_find", "browser_back", "browser_screenshot",
        // 虚拟屏后台自动化
        // vscreen_tap_xy / vscreen_swipe_xy 曾漏登记 → groupOf 判成 GROUP_CORE（恒开、免 tools_enable），
// 带 root 能力的触摸注入在 minimal 档位也会被注入。补齐。
        "vscreen_launch", "vscreen_screen", "vscreen_tap", "vscreen_tap_xy", "vscreen_swipe_xy",
        "vscreen_text", "vscreen_scroll", "vscreen_back", "vscreen_home", "vscreen_close",
        // 相机/定位
        "camera", "location",
        // 设备工具包
        "clipboard_read", "calendar_query", "calendar_create", "contacts_search",
        "alarm_set", "ocr_image", "notifications_read",
        // 工作流（C1/C6：设置修改统一走 config_get/config_set，属 core 组）
        "workflow_save"
    )

    /** E4b 工具名 → 组名；mcp_ 前缀归 mcp，未知工具默认 core（恒开，未来工具零迁移）。 */
    fun groupOf(name: String): String = when {
        name.startsWith("mcp_") -> GROUP_MCP
        name in EXTENDED_TOOLS -> GROUP_EXTENDED
        else -> GROUP_CORE
    }

    /**
     * **全部无条件注册的工具名**（不含按开关/上下文条件注册的 vscreen_*、
     * ask_user* / subagent_* / web_search / session_search / MCP 工具）。
     *
     * 用途：让 `PolicyEngine.TOOL_RISK` 的穷尽性可被单测断言。风险表漏登记的工具会落到
     * fail-safe 兜底（WRITE），代价是"多问一次批准"；但漏登也意味着风险等级**未被人reviewed**，
     * 而 `AgentEngineLoopTest` 那类测试普遍跑 YOLO 档（`requiresApproval` 恒 false），
     * 根本查不出等级错配。所以需要一份不依赖 Android Context 的名字清单做静态对账。
     *
     * 条件注册的工具在 [CONDITIONAL_TOOL_NAMES] 里，两者并起来才是完整期望集。
     */
    internal val CORE_TOOL_NAMES: List<String> = listOf(
        "read", "write", "edit", "grep", "glob", "web_fetch", "bash", "job_output",
        "todo", "memory", "screen", "tap", "swipe", "scroll", "find", "wait",
        "type_text", "key", "launch_app", "open_uri", "list_apps",
        "browser_search", "browser_open", "browser_navigate", "browser_read",
        "browser_click", "browser_input", "browser_scroll", "browser_find",
        "browser_back", "browser_screenshot",
        "schedule", "workflow_save", "skill", "app_status", "config_get", "config_set",
        "camera", "location", "clipboard_read", "calendar_query", "calendar_create",
        "contacts_search", "alarm_set", "ocr_image", "notifications_read"
    )

    /** 条件注册的工具名（开关/深度/上下文决定是否注入），同样必须登记风险等级。 */
    internal val CONDITIONAL_TOOL_NAMES: List<String> = listOf(
        "ask_user", "ask_user_batch",
        "vscreen_launch", "vscreen_screen", "vscreen_tap", "vscreen_tap_xy",
        "vscreen_swipe_xy", "vscreen_text", "vscreen_scroll", "vscreen_back",
        "vscreen_home", "vscreen_close",
        "web_search", "session_search",
        "spawn_agent", "spawn_agents", "stop_agent", "steer_agent", "collect_agent",
        "handoff", "tools_enable",
        "delegate_to_vision", "transcribe_audio"
    )

    /** 期望在 `PolicyEngine.TOOL_RISK` 里出现的全部工具名（去重）。 */
    internal val ALL_TOOL_NAMES: Set<String> = (CORE_TOOL_NAMES + CONDITIONAL_TOOL_NAMES).toSet()

    fun build(
        ctx: ToolContext,
        subAgentRunner: SubAgentRunner? = null,
        activeGroups: Set<String>? = null,
        /** P2：子代理干预接口（stop/steer 工具与 spawn 同批注册，depth=0 且有 runner 时）。 */
        subagentControl: com.haoai.agent.agent.engine.SubagentControl? = null
    ): List<Tool> {
        val all = buildList {
        add(ReadTool())
        add(WriteTool())
        add(EditTool())
        add(GrepTool())
        add(GlobTool())
        add(WebFetchTool())
        add(BashTool())
        add(JobOutputTool())
        add(TodoTool())
        add(MemoryTool())
        // ask_user 主动提问：core 恒开（模型随时可用的交互通道）；子代理不注册（depth>0），
        // 提问由主代理负责——子代理挂起等用户会拖住整条任务线。
        // ask_user_batch（整批问卷）同组同理由，一起只在主代理注册
        if (ctx.depth == 0) {
            add(AskUserTool())
            add(AskUserBatchTool())
        }
        add(ScreenTool())
        add(TapTool())
        add(SwipeTool())
        add(ScrollTool())
        add(FindTool())
        add(WaitTool())
        add(TypeTextTool())
        add(KeyTool())
        add(LaunchAppTool())
        add(OpenUriTool())
        add(ListAppsTool())
        // 4.1 浏览器工具组：拉起系统浏览器，读取/操作复用上面 a11y 组合
        add(BrowserSearchTool())
        add(BrowserOpenTool())
        // 4.2 内置浏览器工具组：App 内 WebView 自动化（JS 渲染页可读可操作）
        add(BrowserNavigateTool())
        add(BrowserReadTool())
        add(BrowserClickTool())
        add(BrowserInputTool())
        add(BrowserPageScrollTool())
        add(BrowserFindTool())
        add(BrowserBackTool())
        add(BrowserScreenshotTool())
        // 4.3 虚拟屏后台自动化：总开关开启才注册（开关在设置页，API 30+ 门槛由引擎合并判定）
        if (ctx.vscreenEnabled) {
            add(VScreenLaunchTool())
            add(VScreenScreenTool())
            add(VScreenTapTool())
            add(VScreenTapXyTool())
            add(VScreenSwipeXyTool())
            add(VScreenTextTool())
            add(VScreenScrollTool())
            add(VScreenBackTool())
            add(VScreenHomeTool())
            add(VScreenCloseTool())
        }
        add(ScheduleTool(ctx.appFilesDir))
        // Phase 6 工作流：Agent 起草（workflow_save，待确认）/列表/删除
        add(WorkflowTool(ctx.appFilesDir))
        add(SkillTool(com.haoai.agent.agent.skills.SkillStore))
        add(AppStatusTool(ctx.statusProvider))
        // C6/C1 统一配置入口：config_get（READ 免审）/ config_set（引擎特判恒审批）
        add(ConfigGetTool(ctx.configRender ?: { "" }))
        add(ConfigSetTool(ctx.configMutator ?: { ToolResult("配置修改不可用", true) }))
        add(CameraTool())
        add(LocationTool())
        // 2.4 设备工具包
        add(ClipboardReadTool())
        add(CalendarQueryTool())
        add(CalendarCreateTool())
        add(ContactsSearchTool())
        add(AlarmSetTool())
        add(OcrImageTool())
        add(NotificationsReadTool())
        if (ctx.httpClient != null) add(WebSearchTool())
        // B4 会话内容检索（core 组 READ：跨会话回忆背景，不改状态）
        ctx.sessionSearch?.let { add(SessionSearchTool(it)) }
        // MCP 外部工具：enabled 服务器全部展开（未连接时用 toolCache 占位，调用报不可用）
        addAll(com.haoai.agent.agent.mcp.McpManager.toolInstances())
        if (ctx.depth == 0 && subAgentRunner != null) {
            add(SubAgentTool(subAgentRunner, subagentControl))
            add(SubAgentsTool(subAgentRunner, subagentControl))
            if (subagentControl != null) {
                add(StopAgentTool(subagentControl))
                add(SteerAgentTool(subagentControl))
                add(CollectAgentTool(subagentControl))
            }
        }
        }
        // E4b 分层过滤：core 恒开；activeGroups=null 视为全开（旧会话零感知）
        if (activeGroups == null) return all
        return all.filter { tool ->
            val g = groupOf(tool.name)
            g == GROUP_CORE || g in activeGroups
        }
    }

    fun readOnly(ctx: ToolContext): List<Tool> = listOf(
        ReadTool(),
        GrepTool(),
        GlobTool(),
        WebFetchTool(),
        MemoryTool(),
        AppStatusTool(ctx.statusProvider)
    ) + listOfNotNull(ctx.httpClient?.let { WebSearchTool() })
}

fun Tool.toApi(): ApiTool = ApiTool(
    function = ApiToolDef(name, desc, sanitizeParameters(params))
)

/**
 * 严格网关兼容（实测 b.ai glm-5.3-flash 上游）：无参工具的 `"properties":{}` 会被
 * 部分供应商校验器 400 拒绝（code 1210），删键只留 {"type":"object"} 即可通过。
 */
private fun sanitizeParameters(params: kotlinx.serialization.json.JsonObject): kotlinx.serialization.json.JsonObject {
    val props = params["properties"] as? kotlinx.serialization.json.JsonObject
    return if (props != null && props.isEmpty()) kotlinx.serialization.json.JsonObject(params - "properties") else params
}

fun toolCallToApi(id: String, name: String, argumentsJson: String): ApiToolCall =
    ApiToolCall(id = id, function = ApiFunctionCall(name = name, arguments = argumentsJson))
