package com.haoai.agent.agent.mcp

import com.haoai.agent.data.HaoJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

/**
 * 单台 MCP 服务器的用户配置。toolCache 缓存最近一次 tools/list 结果，
 * 用于 server 尚未连上（冷启动/断网）时也能把工具注册给模型——调用时报"不可用"而非"未知工具"。
 */
@Serializable
data class McpServerConfig(
    val id: String,
    val name: String,
    val url: String,
    /** 自定义请求头（如 Authorization）。含敏感值，存 app 私有目录，不进工作区。 */
    val headers: Map<String, String> = emptyMap(),
    val enabled: Boolean = true,
    /** 工具审批级别：write=每次询问（默认）；read=免审批（仅建议只读 server）。 */
    val approvalLevel: String = "write",
    /** 用户显式允许该 server 走明文 http（NetGuard 白名单，默认关）。 */
    val allowPlaintext: Boolean = false,
    val toolCache: List<McpToolInfoData> = emptyList()
)

@Serializable
data class McpToolInfoData(
    val name: String,
    val description: String = "",
    /** 原始 inputSchema JSON 字符串（空 = 防御性默认 object）。 */
    val schemaJson: String = ""
)

/** 服务器配置持久化（filesDir/mcp/servers.json，HaoJson 原子写 + .bak 兜底）。 */
object McpServerStore {

    private lateinit var file: File

    fun init(filesDir: File) {
        file = File(filesDir, "mcp/servers.json")
    }

    fun load(): List<McpServerConfig> {
        if (!::file.isInitialized) return emptyList()
        val text = HaoJson.readTextSafe(file) ?: return emptyList()
        return runCatching {
            HaoJson.json.decodeFromString(ListSerializer(McpServerConfig.serializer()), text)
        }.getOrDefault(emptyList())
    }

    fun save(servers: List<McpServerConfig>) {
        if (!::file.isInitialized) return
        val text = HaoJson.json.encodeToString(
            ListSerializer(McpServerConfig.serializer()),
            servers
        )
        HaoJson.writeAtomic(file, text)
    }
}
