package com.haoai.agent.agent.mcp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/** 一次 JSON-RPC 往返的原始响应：HTTP 状态码 + 解析出的 JSON 体（通知类 202 无体）。 */
data class McpHttpResponse(val code: Int, val body: JsonObject?)

/**
 * MCP 传输层抽象。2.1 只实现 Streamable HTTP；stdio 留到 3.5（Linux 沙箱就绪后拉起子进程）。
 */
interface McpTransport {
    /** 建立传输（HTTP 无需真连接，仅为接口统一；stdio 在此 spawn 进程）。 */
    suspend fun connect()

    /** 发送一条 JSON-RPC 消息，返回响应（通知消息响应可能为 null body）。 */
    suspend fun send(request: JsonObject): McpHttpResponse

    /** 释放底层资源（stdio 关进程；HTTP 可选发送 DELETE 结束会话）。 */
    suspend fun close()
}

/**
 * Streamable HTTP 传输（2025-06-18 规范）：
 * - 所有请求 POST JSON 到 endpoint，Accept 同时声明 json 与 event-stream；
 * - 响应可能是单个 JSON，也可能是 SSE 流（逐行解析 data:，取第一条含响应的帧）；
 * - initialize 响应的 Mcp-Session-Id 持久化，后续请求回传；
 * - initialize 之后所有请求须带 MCP-Protocol-Version 头（新版规范要求）。
 */
class StreamableHttpTransport(
    private val http: OkHttpClient,
    private val endpoint: String,
    /** 额外请求头（用户配置的自定义 headers，含 Bearer Token）。 */
    private val extraHeaders: Map<String, String> = emptyMap()
) : McpTransport {

    companion object {
        /** 响应体上限：正常 JSON-RPC 响应远小于此；超限视为服务异常，防超大响应拖垮内存。 */
        const val MAX_RESPONSE_BYTES = 8L * 1024 * 1024
    }

    @Volatile private var sessionId: String? = null
    @Volatile private var protocolVersion: String? = null

    override suspend fun connect() { /* HTTP 无状态，握手由 McpClient 的 initialize 完成 */ }

    override suspend fun send(request: JsonObject): McpHttpResponse = withContext(Dispatchers.IO) {
        val payload = request.toString()
        val builder = Request.Builder()
            .url(endpoint)
            .header("Accept", "application/json, text/event-stream")
            .header("Content-Type", "application/json")
            .post(payload.toRequestBody("application/json".toMediaType()))
        sessionId?.let { builder.header("Mcp-Session-Id", it) }
        protocolVersion?.let { builder.header("MCP-Protocol-Version", it) }
        extraHeaders.forEach { (k, v) -> builder.header(k, v) }

        http.newCall(builder.build()).execute().use { resp ->
            // 会话头持久化：initialize 的响应携带，之后每次回传
            resp.header("Mcp-Session-Id")?.let { sessionId = it }

            if (resp.code == 202) return@use McpHttpResponse(202, null)

            val contentType = resp.header("Content-Type") ?: ""
            // 上限前置检查：Content-Length 明示超限直接拒收，不启动读取
            resp.body?.contentLength()?.takeIf { it > MAX_RESPONSE_BYTES }?.let {
                throw IOException("响应体 ${it / 1024 / 1024}MB 超过上限（${MAX_RESPONSE_BYTES / 1024 / 1024}MB）")
            }
            val raw = resp.body?.let { readBounded(it) } ?: ""
            if (!resp.isSuccessful) {
                // 非 2xx：尽量把 body 里的错误信息带给上层，便于 UI 展示可读原因
                val detail = parseSseOrJson(raw)?.toString()?.take(300) ?: raw.take(300)
                throw IOException("HTTP ${resp.code}${if (detail.isBlank()) "" else "：$detail"}")
            }
            val body = parseSseOrJson(raw)
                ?: throw IOException("响应体不是有效 JSON 或 SSE（Content-Type: $contentType）")
            McpHttpResponse(resp.code, body)
        }
    }

    /** 有界读取：最多读 MAX_RESPONSE_BYTES+1 字节即判超限中止，等效替代无上限的 body.string()。 */
    private fun readBounded(body: okhttp3.ResponseBody): String {
        val src = body.source()
        val buf = okio.Buffer()
        val limit = MAX_RESPONSE_BYTES + 1
        while (buf.size < limit) {
            if (src.read(buf, limit - buf.size) == -1L) break
        }
        if (buf.size > MAX_RESPONSE_BYTES) {
            throw IOException("响应体超过 ${MAX_RESPONSE_BYTES / 1024 / 1024}MB 上限，已中止读取")
        }
        return buf.readUtf8()
    }

    override suspend fun close() {
        // 规范建议会话结束时 DELETE endpoint；失败不打扰用户（服务端会自行超时回收）
        val sid = sessionId ?: return
        runCatching {
            withContext(Dispatchers.IO) {
                val builder = Request.Builder().url(endpoint).delete()
                    .header("Mcp-Session-Id", sid)
                extraHeaders.forEach { (k, v) -> builder.header(k, v) }
                http.newCall(builder.build()).execute().close()
            }
        }
        sessionId = null
    }

    /**
     * 响应体两种形态统一解析：
     * - application/json：直接 parse；
     * - text/event-stream：逐行读，data: 行（可多行拼接）构成事件负载，
     *   取第一条能 parse 成 JSON 的 data（跳过 event: 注释行与服务器单向通知）。
     * 兼容 CRLF 与 BOM。
     */
    private fun parseSseOrJson(raw: String): JsonObject? {
        val text = raw.trimStart('\uFEFF')
        if (text.isEmpty()) return null
        if (text.startsWith("{") || text.startsWith("[")) {
            return runCatching {
                com.haoai.agent.data.HaoJson.json.parseToJsonElement(text) as? JsonObject
            }.getOrNull()
        }
        val dataLines = mutableListOf<StringBuilder>()
        var current = StringBuilder()
        for (rawLine in text.lines()) {
            val line = rawLine.trimEnd('\r')
            when {
                line.startsWith("data:") -> {
                    current.append(if (current.isEmpty()) "" else "\n")
                    current.append(line.removePrefix("data:").trimStart())
                }
                line.isEmpty() -> {
                    // 空行 = 事件帧结束
                    if (current.isNotEmpty()) { dataLines.add(current); current = StringBuilder() }
                }
                // event:/id:/retry: 及注释行忽略——我们只需要响应负载
            }
        }
        if (current.isNotEmpty()) dataLines.add(current)
        for (data in dataLines) {
            val obj = runCatching {
                com.haoai.agent.data.HaoJson.json.parseToJsonElement(data.toString()) as? JsonObject
            }.getOrNull()
            if (obj != null) return obj
        }
        return null
    }
}
