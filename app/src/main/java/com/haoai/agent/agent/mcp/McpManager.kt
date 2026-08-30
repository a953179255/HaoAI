package com.haoai.agent.agent.mcp

import com.haoai.agent.agent.policy.RiskLevel
import com.haoai.agent.agent.tools.Tool
import com.haoai.agent.agent.tools.ToolResult
import com.haoai.agent.platform.NetGuard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap

/** 单台服务器连接状态（UI 状态点 / 工具调用降级判断共用）。 */
sealed class McpConnState {
    data object Disconnected : McpConnState()
    data object Connecting : McpConnState()
    data class Ready(val toolCount: Int) : McpConnState()
    data class Error(val message: String) : McpConnState()
}

/**
 * MCP 子系统入口（AppContainer init 接线）：
 * - 持有全部服务器配置与活跃 client，冷启动对 enabled 服务器异步握手；
 * - 通过 [toolInstances] 把远端工具以 mcp_ 前缀注入 ToolRegistry（未连上时用 toolCache 占位）；
 * - 连接失败不阻塞主流程，调用时返回可读错误。
 */
object McpManager {

    private var http: OkHttpClient? = null

    @Volatile private var servers: List<McpServerConfig> = emptyList()

    private val clients = ConcurrentHashMap<String, McpClient>()

    private val stateMap = ConcurrentHashMap<String, McpConnState>()
    private val _states = MutableStateFlow<Map<String, McpConnState>>(emptyMap())
    val states: StateFlow<Map<String, McpConnState>> = _states

    /** 免审批（READ）工具全名集合，PolicyEngine 风险覆盖的数据源。 */
    @Volatile private var readToolNames: Set<String> = emptySet()

    private val saveMutex = Mutex()

    fun init(filesDir: java.io.File, okHttpClient: OkHttpClient) {
        McpServerStore.init(filesDir)
        http = okHttpClient
        servers = McpServerStore.load()
        refreshDerived()
        // 工具级风险覆盖：mcp_ 前缀默认 WRITE，降级名单内的按 READ 免审
        com.haoai.agent.agent.policy.PolicyEngine.riskOverride = { toolName ->
            if (!toolName.startsWith("mcp_")) null
            else if (toolName in readToolNames) RiskLevel.READ
            else RiskLevel.WRITE
        }
    }

    /** 配置变化后的统一重建：白名单、免审批名单落盘生效。 */
    private fun refreshDerived() {
        // NetGuard 白名单：仅用户显式允许明文的 server 的 origin 前缀
        NetGuard.httpWhitelist = servers
            .filter { it.allowPlaintext }
            .map { originOf(it.url) }
            .filter { it.isNotBlank() }
        readToolNames = servers
            .filter { it.approvalLevel == "read" }
            .flatMap { srv -> toolNamesFor(srv).map { localToolName(srv, it) } }
            .toSet()
        publishStates()
    }

    private fun publishStates() {
        val snapshot = servers.associate { cfg ->
            cfg.id to (stateMap[cfg.id] ?: McpConnState.Disconnected)
        }
        _states.value = snapshot
    }

    private fun originOf(url: String): String = runCatching {
        val uri = java.net.URI(url.trim())
        val port = if (uri.port in -1..0) "" else ":${uri.port}"
        "${uri.scheme ?: "http"}://${uri.host ?: return ""}$port"
    }.getOrDefault("")

    // ---------- 工具命名 ----------

    private fun shortName(cfg: McpServerConfig): String {
        val ascii = cfg.name.filter { it.isLetterOrDigit() && it.code < 128 }.take(12).lowercase()
        return ascii.ifBlank { "srv${cfg.id.take(4)}" }
    }

    /** 供 McpToolBridge 生成工具全名（与本地注册一致）。 */
    fun shortNameOf(cfg: McpServerConfig): String = shortName(cfg)

    private fun sanitizeToolName(raw: String): String =
        raw.map { if (it.isLetterOrDigit() || it == '_' || it == '-') it else '_' }
            .joinToString("").ifBlank { "tool" }

    /** 服务器下应注册的本地工具全名（mcp_<短名>_<工具名>）。 */
    private fun toolNamesFor(cfg: McpServerConfig): List<String> =
        cfg.toolCache.map { localToolName(cfg, it.name) }

    private fun localToolName(cfg: McpServerConfig, remoteTool: String): String =
        "mcp_${shortName(cfg)}_${sanitizeToolName(remoteTool)}"

    // ---------- 连接管理 ----------

    /** 冷启动/配置刷新：对全部 enabled 服务器并发握手（失败不抛，状态进 Error）。 */
    fun connectAll(scope: CoroutineScope) {
        servers.filter { it.enabled }.forEach { cfg -> scope.launch { connectServer(cfg.id) } }
    }

    /**
     * 连接单台服务器：initialize → tools/list → 更新 toolCache。
     * 指数退避重试共 3 次尝试（间隔 2s/4s）；最终失败标记 Error 带可读原因。
     */
    suspend fun connectServer(serverId: String) {
        val cfg = servers.find { it.id == serverId } ?: return
        if (!cfg.enabled) return
        val client = http ?: return
        setState(serverId, McpConnState.Connecting)
        var lastError: String = "未知错误"
        var attempt = 0
        while (attempt < 3) {
            if (attempt > 0) delay(1000L shl attempt) // 2s/4s
            attempt++
            val transport = StreamableHttpTransport(client, cfg.url, cfg.headers)
            val mcp = McpClient(transport)
            val initResult = mcp.initialize()
            if (initResult.isFailure) {
                lastError = initResult.exceptionOrNull()?.message ?: "initialize 失败"
                mcp.close()
                continue
            }
            val toolsResult = mcp.listTools()
            if (toolsResult.isFailure) {
                lastError = toolsResult.exceptionOrNull()?.message ?: "tools/list 失败"
                mcp.close()
                continue
            }
            val tools = toolsResult.getOrNull().orEmpty()
            clients[serverId] = mcp
            setState(serverId, McpConnState.Ready(tools.size))
            updateConfig(serverId) { it.copy(toolCache = tools.map { t -> McpToolInfoData(t.name, t.description, t.inputSchema.toString()) }) }
            return
        }
        clients.remove(serverId)
        setState(serverId, McpConnState.Error(lastError))
    }

    private fun setState(serverId: String, state: McpConnState) {
        stateMap[serverId] = state
        publishStates()
    }

    // ---------- 配置 CRUD（设置页调用） ----------

    fun listServers(): List<McpServerConfig> = servers

    fun server(id: String): McpServerConfig? = servers.find { it.id == id }

    suspend fun addServer(cfg: McpServerConfig) {
        upsert(cfg)
        if (cfg.enabled) connectServer(cfg.id)
    }

    suspend fun updateServer(cfg: McpServerConfig) {
        // URL/headers 变更后旧会话作废：断开重连
        clients.remove(cfg.id)?.close()
        stateMap[cfg.id] = McpConnState.Disconnected
        upsert(cfg)
        if (cfg.enabled) connectServer(cfg.id) else refreshDerived()
    }

    suspend fun removeServer(id: String) {
        clients.remove(id)?.close()
        stateMap.remove(id)
        saveMutex.withLock {
            servers = servers.filter { it.id != id }
            McpServerStore.save(servers)
        }
        refreshDerived()
    }

    suspend fun setEnabled(id: String, enabled: Boolean) {
        val cfg = servers.find { it.id == id } ?: return
        if (enabled) {
            upsert(cfg.copy(enabled = true))
            connectServer(id)
        } else {
            clients.remove(id)?.close()
            stateMap[id] = McpConnState.Disconnected
            upsert(cfg.copy(enabled = false))
            refreshDerived()
        }
    }

    private suspend fun upsert(cfg: McpServerConfig) {
        saveMutex.withLock {
            servers = servers.filterNot { it.id == cfg.id } + cfg
            McpServerStore.save(servers)
        }
        refreshDerived()
    }

    /** 保存 toolCache 而不动连接（tools/list 成功后回写）。 */
    private suspend fun updateConfig(serverId: String, edit: (McpServerConfig) -> McpServerConfig) {
        saveMutex.withLock {
            servers = servers.map { if (it.id == serverId) edit(it) else it }
            McpServerStore.save(servers)
        }
    }

    // ---------- 工具桥 ----------

    /**
     * 提供给 ToolRegistry 的 MCP 工具集：enabled 服务器逐台展开。
     * 连接中/失败时用 toolCache 占位（调用报"不可用"），保证模型看到的工具清单稳定。
     */
    fun toolInstances(): List<Tool> {
        val http = http ?: return emptyList()
        return servers.filter { it.enabled }.flatMap { cfg ->
            cfg.toolCache.map { info ->
                McpTool(cfg, info, this)
            }
        }
    }

    /** 引擎工具调用入口：未连接时现场重试一次握手，仍失败返回可读错误。 */
    suspend fun callTool(serverId: String, remoteTool: String, args: JsonObject): ToolResult {
        val cfg = servers.find { it.id == serverId }
            ?: return ToolResult("MCP 服务器已被删除", true)
        if (!cfg.enabled) return ToolResult("MCP 服务器「${cfg.name}」已停用", true)
        val client = clients[serverId]
        if (client == null) {
            // 现场重连一次：cold start 未完成 / 中途断线时的兜底
            connectServer(serverId)
            val retry = clients[serverId]
                ?: return ToolResult(
                    "MCP 服务器「${cfg.name}」不可用：${(stateMap[serverId] as? McpConnState.Error)?.message ?: "连接失败"}",
                    true
                )
            return invoke(retry, cfg, remoteTool, args)
        }
        return invoke(client, cfg, remoteTool, args)
    }

    private suspend fun invoke(
        client: McpClient,
        cfg: McpServerConfig,
        remoteTool: String,
        args: JsonObject
    ): ToolResult {
        // 会话可能已被服务端回收：首次失败先重连一次再重试，仍失败才返回错误
        val first = client.callTool(remoteTool, args)
        if (first.isSuccess) {
            val r = first.getOrThrow()
            return ToolResult(r.content, r.isError)
        }
        clients.remove(cfg.id)?.close()
        connectServer(cfg.id)
        val fresh = clients[cfg.id] ?: return ToolResult(
            "MCP 服务器「${cfg.name}」连接失败：${first.exceptionOrNull()?.message}",
            true
        )
        val second = fresh.callTool(remoteTool, args)
        if (second.isSuccess) {
            val r = second.getOrThrow()
            return ToolResult(r.content, r.isError)
        }
        return ToolResult("MCP 工具调用失败：${second.exceptionOrNull()?.message}", true)
    }

    // ---------- 测试连接（设置页"测试连接"按钮） ----------

    /** 独立临时客户端跑握手 + tools/list，返回工具数或可读错误；不占用 manager 连接池。 */
    suspend fun testConnection(cfg: McpServerConfig): Result<Int> {
        val http = http ?: return Result.failure(McpException("MCP 未初始化"))
        val mcp = McpClient(StreamableHttpTransport(http, cfg.url, cfg.headers))
        return try {
            val init = mcp.initialize()
            if (init.isFailure) return Result.failure(init.exceptionOrNull() ?: McpException("initialize 失败"))
            val tools = mcp.listTools()
            if (tools.isFailure) return Result.failure(tools.exceptionOrNull() ?: McpException("tools/list 失败"))
            Result.success(tools.getOrThrow().size)
        } finally {
            runCatching { mcp.close() }
        }
    }

    /** SystemPrompt 摘要：几个服务器、几把工具。 */
    fun promptSummary(): String {
        val enabled = servers.filter { it.enabled }
        if (enabled.isEmpty()) return ""
        val toolCount = enabled.sumOf { it.toolCache.size }
        return "已接入 $toolCount 把 MCP 外部工具（${enabled.size} 个服务器，mcp_ 前缀）；" +
            "优先使用内置工具完成常规任务，内置能力不足时再选择合适的 MCP 工具。"
    }
}
