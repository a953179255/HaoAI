package com.haoai.agent.agent.mcp

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.concurrent.atomic.AtomicInteger

/** tools/list 返回的单把远端工具描述（schema 以原始 JsonObject 透传给模型）。 */
data class McpToolInfo(
    val name: String,
    val description: String,
    val inputSchema: JsonObject
)

/** tools/call 的结果文本与错误标志。 */
data class McpToolResult(val content: String, val isError: Boolean)

/**
 * MCP JSON-RPC 2.0 客户端：initialize 握手 → notifications/initialized → tools/list / tools/call。
 * 单服务器请求经 [mutex] 串行化（Streamable HTTP 允许并发，但串行实现简单且足够）；
 * 网络层断连由 McpManager 触发重建，本类不自动重试。
 */
class McpClient(private val transport: McpTransport) {

    companion object {
        const val PROTOCOL_VERSION = "2025-06-18"
    }

    private val mutex = Mutex()
    private val nextId = AtomicInteger(1)

    /** 握手后记录服务器实际协商版本，供后续请求头使用。 */
    @Volatile var negotiatedVersion: String = PROTOCOL_VERSION
        private set

    private fun rpc(method: String, params: JsonObject?): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", nextId.getAndIncrement())
        put("method", method)
        if (params != null) put("params", params)
    }

    private fun notification(method: String, params: JsonObject?): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        put("method", method)
        if (params != null) put("params", params)
    }

    private fun errorOf(resp: McpHttpResponse): String? {
        val err = resp.body?.get("error") as? JsonObject ?: return null
        val msg = err["message"]?.jsonPrimitive?.contentOrNull ?: "未知错误"
        val code = err["code"]?.jsonPrimitive?.contentOrNull
        return if (code != null) "$msg（code $code）" else msg
    }

    /**
     * 握手：initialize → 收集 serverInfo/协议版本 → notifications/initialized（202 无体即成功）。
     * 服务器要求其他版本时给出可读错误。
     */
    suspend fun initialize(): Result<Unit> = mutex.withLock {
        try {
            // stdio 传输在此拉起子进程（HTTP 为 no-op）；失败直接给出可读原因
            transport.connect()
            val resp = transport.send(
                rpc(
                    "initialize",
                    buildJsonObject {
                        put("protocolVersion", PROTOCOL_VERSION)
                        putJsonObject("capabilities") { }
                        putJsonObject("clientInfo") {
                            put("name", "haoai")
                            put("version", "1.0")
                        }
                    }
                )
            )
            (errorOf(resp))?.let { return Result.failure(McpException("initialize 失败：$it")) }
            val result = resp.body?.get("result") as? JsonObject
                ?: return Result.failure(McpException("initialize 响应缺少 result"))
            val serverVersion = result["protocolVersion"]?.jsonPrimitive?.contentOrNull
            if (serverVersion != null && serverVersion != PROTOCOL_VERSION) {
                return Result.failure(
                    McpException("服务器要求的协议版本为 $serverVersion（本客户端支持 $PROTOCOL_VERSION）")
                )
            }
            negotiatedVersion = serverVersion ?: PROTOCOL_VERSION
            // initialized 是纯通知：202/空响应都算成功
            transport.send(notification("notifications/initialized", null))
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** 列出全部工具（自动翻页 nextCursor）。 */
    suspend fun listTools(): Result<List<McpToolInfo>> = mutex.withLock {
        try {
            val out = mutableListOf<McpToolInfo>()
            var cursor: String? = null
            do {
                val params = buildJsonObject {
                    cursor?.let { put("cursor", it) }
                }
                val resp = transport.send(rpc("tools/list", params))
                errorOf(resp)?.let { return Result.failure(McpException("tools/list 失败：$it")) }
                val result = resp.body?.get("result") as? JsonObject
                    ?: return Result.failure(McpException("tools/list 响应缺少 result"))
                val tools = result["tools"] as? JsonArray ?: JsonArray(emptyList())
                for (t in tools) {
                    val obj = t as? JsonObject ?: continue
                    val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: continue
                    val desc = obj["description"]?.jsonPrimitive?.contentOrNull ?: ""
                    val schema = obj["inputSchema"] as? JsonObject
                        ?: buildJsonObject { put("type", "object") }
                    out.add(McpToolInfo(name, desc, schema))
                }
                cursor = result["nextCursor"]?.jsonPrimitive?.contentOrNull
            } while (cursor != null)
            Result.success(out)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** 调用远端工具；result.isError 映射为错误结果，content 数组文本拼接。 */
    suspend fun callTool(name: String, arguments: JsonObject): Result<McpToolResult> = mutex.withLock {
        try {
            val resp = transport.send(
                rpc(
                    "tools/call",
                    buildJsonObject {
                        put("name", name)
                        put("arguments", arguments)
                    }
                )
            )
            errorOf(resp)?.let { return Result.failure(McpException(it)) }
            val result = resp.body?.get("result") as? JsonObject
                ?: return Result.failure(McpException("tools/call 响应缺少 result"))
            val isError = result["isError"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
            val content = result["content"] as? JsonArray ?: JsonArray(emptyList())
            val text = content.mapNotNull { block ->
                val obj = block as? JsonObject ?: return@mapNotNull null
                when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                    "text" -> obj["text"]?.jsonPrimitive?.contentOrNull
                    "image" -> "[图片内容（二进制，未展示）]"
                    "resource" -> "[内嵌资源]"
                    "audio" -> "[音频内容]"
                    else -> null
                }
            }.joinToString("\n").ifBlank { "（工具无文本返回）" }
            Result.success(McpToolResult(text, isError))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun close() {
        runCatching { transport.close() }
    }
}

/** MCP 层可读错误（区别于 OkHttp 原始异常文案）。 */
class McpException(message: String) : Exception(message)
