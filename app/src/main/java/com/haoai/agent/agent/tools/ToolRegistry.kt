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
        "vscreen_launch", "vscreen_screen", "vscreen_tap", "vscreen_text",
        "vscreen_scroll", "vscreen_back", "vscreen_home", "vscreen_close",
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

    fun build(
        ctx: ToolContext,
        subAgentRunner: SubAgentRunner? = null,
        activeGroups: Set<String>? = null
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
        // MCP 外部工具：enabled 服务器全部展开（未连接时用 toolCache 占位，调用报不可用）
        addAll(com.haoai.agent.agent.mcp.McpManager.toolInstances())
        if (ctx.depth == 0 && subAgentRunner != null) {
            add(SubAgentTool(subAgentRunner))
            add(SubAgentsTool(subAgentRunner))
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
    function = ApiToolDef(name, description, sanitizeParameters(parameters))
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
