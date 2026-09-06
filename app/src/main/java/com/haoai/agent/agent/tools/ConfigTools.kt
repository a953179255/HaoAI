package com.haoai.agent.agent.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * C6/C1 统一配置工具（对标 上游 `上游 config get/set`）：
 * 配置真源在状态目录（app 私有），agent 的 read/write/edit 摸不到——改配置唯一入口
 * 是这两个工具。config_set 每次调用都由引擎强制审批（executeCall 特判恒审批），
 * 批准后走 ConfigFileBridge 同源严格校验 + 钳制 + 掩码语义，当轮返回
 * 「已应用/被拒绝+原因」，agent 可即时自纠。
 */
class ConfigGetTool(private val renderCurrent: () -> String) : Tool {

    override val name = "config_get"
    override val description =
        "读取当前应用配置（JSON）：providers（模型服务清单，apiKey 一律掩码 ****）+ settings（可修改字段）。" +
            "准备用 config_set 修改配置前先调用本工具拿当前结构；provider 的 id 字段在修改时必须沿用。"
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {}
    }

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val text = runCatching { renderCurrent() }.getOrElse {
            return ToolResult("读取配置失败：${it.message}", true)
        }
        return ToolResult(text)
    }
}

/**
 * config_set（JSON patch 语义）：只传要改的部分，settings 字段全部平铺在补丁顶层——
 * - providers: 传要新增/更新的 provider 对象数组（按 id 合并，缺席的 provider 保留不动；
 *   删除需另传顶层 providers_removed=true，缺席者才会被删）
 * - providers_removed: true 时删除「当前存在但补丁 providers 里没有」的云端 provider
 * - 其余为 settings 白名单键平铺（reply_max_tokens / memory_enabled / permission_mode / …）
 * 每次调用都会弹审批；校验失败会整体拒绝并返回可读原因。
 */
class ConfigSetTool(private val mutator: suspend (JsonObject) -> ToolResult) : Tool {

    override val name = "config_set"
    override val description =
        "修改应用配置（JSON patch，每次都会弹窗请用户批准）。先 config_get 拿当前结构，再只传要改的字段：" +
            "改/加模型传 providers 数组（按 id 合并，新增必须带明文 apiKey；改已有模型的 baseUrl/protocol 也必须带明文 apiKey 重认证）；" +
            "删模型传 providers 数组 + providers_removed=true（缺席的云端模型会被删除，过半会警告）；" +
            "MCP 服务器传 mcp_servers 数组（按 id 合并；headers 值写 **** 沿用原值，新服务器须明文；" +
            "kind=http 须 url，kind=stdio 须 command；删除传 mcp_servers_removed=true）；" +
            "SSH 目标传 ssh_targets 数组（id/name/host/port/user，凭据仅设置页管理；删除传 ssh_targets_removed=true）；" +
            "改设置传平铺键（reply_max_tokens / context_length / local_context_length / memory_enabled / " +
            "auto_learn / deep_dream / permission_mode / fallback_chain / memory_extract_provider / " +
            "title_provider / summarize_provider / daily_token_budget_k / keep_alive / dream_provider / dream_idle_minutes / " +
            "theme_mode / theme_seed / amoled_mode / bubble_opacity / wallpaper_global / dynamic_color / reasoning_effort）。" +
            "用户要求「添加模型/换模型/调设置/换主题/加 MCP 服务器/加 SSH 目标」等时使用。"
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonArrayDesc("providers", "要新增/更新的 provider 对象数组（按 id 合并，缺席 provider 保留）")
            putDesc("providers_removed", "boolean", "true=删除当前存在但 providers 里没有的云端 provider（显式删除开关）")
            putJsonArrayDesc("mcp_servers", "要新增/更新的 MCP 服务器对象数组（按 id 合并；字段 id/name/url/kind/command/headers/enabled/approvalLevel/allowPlaintext）")
            putDesc("mcp_servers_removed", "boolean", "true=删除当前存在但 mcp_servers 里没有的服务器")
            putJsonArrayDesc("ssh_targets", "要新增/更新的 SSH 目标对象数组（按 id 合并；字段 id/name/host/port/user）")
            putDesc("ssh_targets_removed", "boolean", "true=删除当前存在但 ssh_targets 里没有的目标")
            putDesc("reply_max_tokens", "integer", "当前云端模型单次回复上限 (256-1000000)")
            putDesc("context_length", "integer", "当前云端模型上下文窗口 tokens (1024-10000000)")
            putDesc("local_context_length", "integer", "端侧推理上下文窗口 (2048-262144)，重启端侧服务生效")
            putDesc("memory_enabled", "boolean", "记忆系统总开关")
            putDesc("auto_learn", "boolean", "对话后自动学习")
            putDesc("deep_dream", "boolean", "闲置时深度整理记忆")
            putDesc(
                "permission_mode", "string",
                "权限模式 always_ask|ask_writes|yolo。改此字段=给自己调整审批级别，弹窗会特别提示用户确认"
            )
            putDesc("fallback_chain", "array", "降级链（有序 providerId，须已存在）")
            putDesc("memory_extract_provider", "string", "记忆提取模型 id（空=主模型，local=端侧）")
            putDesc("title_provider", "string", "会话标题模型 id（空=主模型，local=端侧）")
            putDesc("summarize_provider", "string", "上下文压缩模型 id（空=主模型，local=端侧）")
            putDesc("daily_token_budget_k", "integer", "每日 token 预算（千为单位，0=不限）")
            putDesc("keep_alive", "boolean", "后台保活")
            putDesc("dream_provider", "string", "记忆整理模型 id（空=主模型，local=端侧）")
            putDesc("dream_idle_minutes", "integer", "深度梦境闲置触发分钟 (5-240)")
            putDesc("theme_mode", "string", "主题 light|dark|system")
            putDesc("theme_seed", "integer", "主题色种子索引 (0-100)")
            putDesc("amoled_mode", "boolean", "AMOLED 纯黑模式（深色主题下生效）")
            putDesc("bubble_opacity", "number", "聊天气泡不透明度 (0.3-1.0)")
            putDesc("wallpaper_global", "boolean", "壁纸应用于所有页面")
            putDesc("dynamic_color", "boolean", "动态取色")
            putDesc("reasoning_effort", "string", "思考等级 空|low|medium|high（仅支持的云服务生效）")
        }
    }

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult = mutator(args)
}

/** OpenAI function schema 常用形态：单字段带描述。 */
private fun JsonObjectBuilder.putDesc(key: String, type: String, description: String) {
    putJsonObject(key) {
        put("type", type)
        put("description", description)
    }
}

/** 对象数组字段（providers/mcp_servers/ssh_targets）：items 为自由对象。 */
private fun JsonObjectBuilder.putJsonArrayDesc(key: String, description: String) {
    putJsonObject(key) {
        put("type", "array")
        put("description", description)
        // JSON Schema 规范：items 必须是单个 schema 对象，写成数组会被
        // GLM（b.ai 网关）直接 400 拒绝（宽容网关 MiniMax/DeepSeek 掩盖了它）
        putJsonObject("items") { put("type", "object") }
    }
}
