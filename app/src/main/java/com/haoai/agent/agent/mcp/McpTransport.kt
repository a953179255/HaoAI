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

    /** initialize 协商出的版本回写：此后所有请求带 MCP-Protocol-Version 头（新版规范要求）。 */
    fun setProtocolVersion(v: String?) { protocolVersion = v }

    override suspend fun connect() { /* HTTP 无状态，握手由 McpClient 的 initialize 完成 */ }

    override suspend fun send(request: JsonObject): McpHttpResponse = withContext(Dispatchers.IO) {
        val payload = request.toString()
        val reqId = request["id"]
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
            val body = resp.body ?: throw IOException("响应无 body")
            // 上限前置检查：Content-Length 明示超限直接拒收，不启动读取
            body.contentLength().takeIf { it > MAX_RESPONSE_BYTES }?.let {
                throw IOException("响应体 ${it / 1024 / 1024}MB 超过上限（${MAX_RESPONSE_BYTES / 1024 / 1024}MB）")
            }
            if (resp.isSuccessful && contentType.contains("event-stream", ignoreCase = true)) {
                // SSE 流式逐帧解析：命中请求 id 的帧立即返回。规范（2025-06-18）允许
                // 服务器在真正的响应帧之前推送 notifications/progress——旧的"整流读到
                // EOF 再取第一条能 parse 的 JSON"会把通知帧当响应（缺 result → 报错 →
                // 触发拆链重连），且服务器保持流开放时会一直阻塞到 readTimeout。
                val matched = readSseUntilMatch(body.source(), reqId)
                    ?: throw IOException("SSE 流结束：未找到 id=${reqId?.toString()?.take(40)} 的响应帧")
                return@use McpHttpResponse(resp.code, matched)
            }
            val raw = readBounded(body)
            if (!resp.isSuccessful) {
                // 非 2xx：尽量把 body 里的错误信息带给上层，便于 UI 展示可读原因
                val detail = parseSseOrJson(raw)?.toString()?.take(300) ?: raw.take(300)
                throw IOException("HTTP ${resp.code}${if (detail.isBlank()) "" else "：$detail"}")
            }
            val parsed = parseSseOrJson(raw)
                ?: throw IOException("响应体不是有效 JSON 或 SSE（Content-Type: $contentType）")
            McpHttpResponse(resp.code, parsed)
        }
    }

    /**
     * 逐行读 SSE 流，每帧尝试解析并匹配请求 id，命中立即返回（不读完整个流）。
     * 通知类请求（无 id）沿用"第一条可解析 JSON"语义；带 id 的请求跳过服务器
     * 推送的 notification/progress 帧。累计读取量受 MAX_RESPONSE_BYTES 约束。
     */
    private fun readSseUntilMatch(
        source: okio.BufferedSource,
        reqId: kotlinx.serialization.json.JsonElement?
    ): JsonObject? {
        var total = 0L
        var pending = StringBuilder()
        while (!source.exhausted()) {
            val line = source.readUtf8Line() ?: break
            total += line.length + 1L
            if (total > MAX_RESPONSE_BYTES) {
                throw IOException("SSE 流超过 ${MAX_RESPONSE_BYTES / 1024 / 1024}MB 上限，已中止读取")
            }
            val l = line.trimEnd('\r')
            when {
                l.startsWith("data:") -> {
                    if (pending.isNotEmpty()) pending.append('\n')
                    pending.append(l.removePrefix("data:").trimStart())
                }
                l.isEmpty() -> {
                    trySseFrame(pending.toString(), reqId)?.let { return it }
                    pending = StringBuilder()
                }
                // event:/id:/retry: 及注释行忽略——只需要响应负载
            }
        }
        // 流结束（EOF）：最后一帧无尾随空行，同样要试
        return trySseFrame(pending.toString(), reqId)
    }

    private fun trySseFrame(
        data: String,
        reqId: kotlinx.serialization.json.JsonElement?
    ): JsonObject? {
        if (data.isEmpty()) return null
        val obj = runCatching {
            com.haoai.agent.data.HaoJson.json.parseToJsonElement(data) as? JsonObject
        }.getOrNull() ?: return null
        if (reqId == null) return obj
        return if (idMatches(obj["id"], reqId)) obj else null
    }

    /** JSON-RPC id 宽松匹配：数值/字符串同值视为相等（个别服务器会把 number 回显成 string）。 */
    private fun idMatches(
        a: kotlinx.serialization.json.JsonElement?,
        b: kotlinx.serialization.json.JsonElement?
    ): Boolean {
        if (a == b) return true
        val ap = a as? kotlinx.serialization.json.JsonPrimitive ?: return false
        val bp = b as? kotlinx.serialization.json.JsonPrimitive ?: return false
        return ap.content == bp.content
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
