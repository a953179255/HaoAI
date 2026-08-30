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
