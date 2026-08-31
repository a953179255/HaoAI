package com.haoai.agent.agent.tools

import com.haoai.agent.agent.provider.ApiFunctionCall
import com.haoai.agent.agent.provider.ApiTool
import com.haoai.agent.agent.provider.ApiToolCall
import com.haoai.agent.agent.provider.ApiToolDef

object ToolRegistry {

    fun build(ctx: ToolContext, subAgentRunner: SubAgentRunner? = null): List<Tool> = buildList {
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
        add(ScheduleTool(ctx.appFilesDir))
        add(SkillTool(com.haoai.agent.agent.skills.SkillStore))
        add(AppStatusTool(ctx.statusProvider))
        add(UpdateSettingsTool(ctx.configMutator))
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
    function = ApiToolDef(name, description, parameters)
)

fun toolCallToApi(id: String, name: String, argumentsJson: String): ApiToolCall =
    ApiToolCall(id = id, function = ApiFunctionCall(name = name, arguments = argumentsJson))
