package com.haoai.agent.agent.provider

import com.haoai.agent.agent.model.ToolCallData
import com.haoai.agent.data.HaoJson
import com.haoai.agent.data.ProviderConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Anthropic Messages 原生协议客户端（2.3）：
 * POST {base}/v1/messages，x-api-key + anthropic-version 头，system 抽顶层；
 * OpenAI 消息/工具形态 ↔ Anthropic content blocks 双向转换在内部完成，
 * 对外输出与 OpenAiCompatClient 相同的 SseEvent 流，引擎无感切换。
 * 转换与流解析均为可测纯逻辑（单测见 AnthropicProtocolTest）。
 */
class AnthropicClient(private val okHttpClient: OkHttpClient) : ProviderClient {

    // ---------- 响应侧：SSE 事件解析状态机 ----------

    /** 一轮流解析的累计状态（content_block 分片 JSON / usage / stop_reason）。 */
    class StreamState {
        data class PendingTool(var id: String, var name: String, val args: StringBuilder = StringBuilder())

        val pendingTools = LinkedHashMap<Int, PendingTool>()
        var stopReason: String? = null
        var inputTokens = 0
        var outputTokens = 0
        var gotAnyContent = false
        var errorMessage: String? = null

        /** 处理一条 SSE data JSON，返回应向引擎发射的事件。 */
        fun handle(data: JsonObject): List<SseEvent> {
            val events = mutableListOf<SseEvent>()
            when (data["type"]?.jsonPrimitive?.content) {
                "message_start" -> {
                    val usage = data["message"]?.jsonObject?.get("usage") as? JsonObject
                    inputTokens = usage?.get("input_tokens")?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                }
                "content_block_start" -> {
                    val idx = data["index"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                    val block = data["content_block"] as? JsonObject ?: return events
                    if (block["type"]?.jsonPrimitive?.content == "tool_use") {
                        pendingTools[idx] = PendingTool(
                            id = block["id"]?.jsonPrimitive?.content ?: "toolu_$idx",
                            name = block["name"]?.jsonPrimitive?.content ?: ""
                        )
                        gotAnyContent = true
                    }
                }
                "content_block_delta" -> {
                    val delta = data["delta"] as? JsonObject ?: return events
                    when (delta["type"]?.jsonPrimitive?.content) {
                        "text_delta" -> delta["text"]?.jsonPrimitive?.content?.takeIf { it.isNotEmpty() }?.let {
                            gotAnyContent = true
                            events.add(SseEvent.Delta(it))
                        }
                        "thinking_delta" -> delta["thinking"]?.jsonPrimitive?.content?.takeIf { it.isNotEmpty() }?.let {
                            gotAnyContent = true
                            events.add(SseEvent.Reasoning(it))
                        }
                        "input_json_delta" -> {
                            val idx = data["index"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                            val buf = pendingTools.getOrPut(idx) { PendingTool("toolu_$idx", "") }
                            delta["partial_json"]?.jsonPrimitive?.content?.let { buf.args.append(it) }
                        }
                    }
                }
                "message_delta" -> {
                    data["delta"]?.jsonObject?.get("stop_reason")?.jsonPrimitive?.content?.let {
                        stopReason = it
                    }
                    val usage = data["usage"] as? JsonObject
                    usage?.get("output_tokens")?.jsonPrimitive?.content?.toIntOrNull()?.let { outputTokens = it }
                }
                "error" -> {
                    val err = data["error"] as? JsonObject
                    errorMessage = err?.get("message")?.jsonPrimitive?.content ?: "未知错误"
                }
                // message_stop / content_block_stop / ping / 未知事件无需处理
            }
            return events
        }

        /** 流结束后汇总工具调用（input_json_delta 的分片 JSON 在此整体 parse）。 */
        fun buildToolCalls(): List<ToolCallData> =
            pendingTools.entries.sortedBy { it.key }.mapIndexedNotNull { i, (_, p) ->
                val name = p.name.ifBlank { return@mapIndexedNotNull null }
                ToolCallData(
                    id = p.id.ifBlank { "call_$i" },
                    name = name,
                    argumentsJson = p.args.toString().ifBlank { "{}" }
                )
            }
    }

    // ---------- 请求侧：OpenAI ApiMessage → Anthropic messages ----------

    /** Anthropic content block（转换中间态）。 */
    sealed class Block {
        abstract fun toJson(): JsonObject

        data class Text(val text: String) : Block() {
            override fun toJson() = buildJsonObject {
                put("type", "text")
                put("text", text)
            }
        }

        data class Image(val mediaType: String, val base64: String) : Block() {
            override fun toJson() = buildJsonObject {
                put("type", "image")
                putJsonObject("source") {
                    put("type", "base64")
                    put("media_type", mediaType)
                    put("data", base64)
                }
            }
        }

        data class ToolUse(val id: String, val name: String, val argumentsJson: String) : Block() {
            override fun toJson() = buildJsonObject {
                put("type", "tool_use")
                put("id", id)
                put("name", name)
                put(
                    "input",
                    runCatching { HaoJson.json.parseToJsonElement(argumentsJson) }
                        .getOrDefault(buildJsonObject { })
                )
            }
        }

        data class ToolResult(val toolUseId: String, val content: String) : Block() {
            override fun toJson() = buildJsonObject {
                put("type", "tool_result")
                put("tool_use_id", toolUseId)
                put("content", content)
            }
        }
    }

    data class Converted(
        /** 顶层 system 文本（多段以 \n\n 连接）；空 = 无系统提示。 */
        val system: String,
        val messages: List<Pair<String, List<Block>>>
    )

    companion object {
        const val ANTHROPIC_VERSION = "2023-06-01"

        /** endpoint 规整：无 /vN 前缀自动补 /v1，追加 /messages。 */
        fun normalizeMessagesUrl(raw: String): String {
            var url = raw.trim().trimEnd('/')
            require(url.startsWith("http://") || url.startsWith("https://")) {
                "Base URL 必须以 http(s):// 开头"
            }
            if (!url.endsWith("/messages")) {
                if (!Regex("/v\\d+(/.*)?$").containsMatchIn(url)) url += "/v1"
                url += "/messages"
            }
            return url
        }

        /**
         * OpenAI 形态 → Anthropic 形态：
         * - role=system 抽到顶层；role=tool 转 user 的 tool_result 块；
         * - assistant 的 toolCalls 转 tool_use 块；
         * - data URL 图片转 base64 source；
         * - 合并连续同角色消息（Anthropic 要求 user/assistant 交替）。
         */
        fun convertMessages(messages: List<ApiMessage>): Converted {
            val systemParts = mutableListOf<String>()
            data class Msg(val role: String, val blocks: MutableList<Block>)

            val out = mutableListOf<Msg>()
            fun append(role: String, block: Block) {
                val last = out.lastOrNull()
                if (last != null && last.role == role) last.blocks.add(block)
                else out.add(Msg(role, mutableListOf(block)))
            }

            for (m in messages) {
                when (m.role) {
                    "system" -> m.content?.takeIf { it.isNotBlank() }?.let { systemParts.add(it) }
                    "tool" -> append("user", Block.ToolResult(m.toolCallId ?: "", m.content.orEmpty()))
                    "assistant" -> {
                        m.content?.takeIf { it.isNotBlank() }?.let { append("assistant", Block.Text(it)) }
                        m.toolCalls.orEmpty().forEach { tc ->
                            append("assistant", Block.ToolUse(tc.id, tc.function.name, tc.function.arguments))
                        }
                    }
                    else -> { // user
                        val parts = m.parts
                        if (parts != null) {
                            parts.forEach { p ->
                                when {
                                    p.type == "text" && p.text != null -> append("user", Block.Text(p.text))
                                    p.type == "image_url" && p.imageUrl != null ->
                                        parseDataUrl(p.imageUrl.url)?.let { (mt, b64) ->
                                            append("user", Block.Image(mt, b64))
                                        }
                                }
                            }
                        } else {
                            m.content?.takeIf { it.isNotBlank() }?.let { append("user", Block.Text(it)) }
                        }
                    }
                }
            }
            val msgs = out.filter { it.blocks.isNotEmpty() }.map { it.role to it.blocks.toList() }
            return Converted(systemParts.joinToString("\n\n"), msgs)
        }

        /** data URL → (media_type, base64)；非 data URL 返回 null。 */
        fun parseDataUrl(url: String): Pair<String, String>? {
            val m = Regex("^data:image/(\\w+);base64,(.+)$", RegexOption.DOT_MATCHES_ALL).find(url.trim())
                ?: return null
            val mt = m.groupValues[1].lowercase()
            val media = if (mt == "jpg") "jpeg" else mt
            return "image/$media" to m.groupValues[2].trim()
        }

        fun buildRequestJson(
            provider: ProviderConfig,
            converted: Converted,
            tools: List<ApiTool>,
            stream: Boolean,
            maxTokensOverride: Int? = null
        ): JsonObject = buildJsonObject {
            put("model", provider.model)
            // Anthropic 必填 max_tokens：云端未配置时 4096 兜底
            put("max_tokens", maxTokensOverride ?: provider.effectiveMaxTokens().takeIf { it > 0 } ?: 4096)
            // 采样参数开关：Anthropic 仅支持 temperature/top_p，penalty 无对应字段
            if (provider.sendTemperature) put("temperature", provider.temperature.toDouble())
            if (provider.sendTopP) put("top_p", provider.topP.toDouble())
            if (converted.system.isNotBlank()) put("system", converted.system)
            put("stream", stream)
            putJsonArray("messages") {
                converted.messages.forEach { (role, blocks) ->
                    addJsonObject {
                        put("role", role)
                        putJsonArray("content") { blocks.forEach { add(it.toJson()) } }
                    }
                }
            }
            if (tools.isNotEmpty()) {
                putJsonArray("tools") {
                    tools.forEach { t ->
                        addJsonObject {
                            put("name", t.function.name)
                            put("description", t.function.description)
                            put("input_schema", t.function.parameters)
                        }
                    }
                }
            }
        }
    }

    override suspend fun chatStream(
        provider: ProviderConfig,
        apiKey: String,
        messages: List<ApiMessage>,
        tools: List<ApiTool>,
        reasoningEffort: String?
    ): Flow<SseEvent> = flow {
        val url = normalizeMessagesUrl(provider.baseUrl)
        val converted = convertMessages(messages)
        val requestJson = HaoJson.json.encodeToString(
            JsonObject.serializer(),
            buildRequestJson(provider, converted, tools, stream = true)
        )
        val builder = Request.Builder()
            .url(url)
            .header("Accept", "text/event-stream")
            .header("anthropic-version", ANTHROPIC_VERSION)
            .post(requestJson.toRequestBody("application/json".toMediaType()))
        if (apiKey.isNotBlank()) builder.header("x-api-key", apiKey)

        val call = okHttpClient.newCall(builder.build())
        val response = awaitResponse(call)
        response.use { resp ->
            if (!resp.isSuccessful) {
                val errBody = runCatching { resp.body?.string() }.getOrNull()?.take(400)
                throw IOException("HTTP ${resp.code}（${provider.name}）${errBody?.let { "：$it" } ?: ""}")
            }
            val source = resp.body?.source() ?: throw IOException("响应为空")
            val state = StreamState()

            val cancelHandle = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
                ?.invokeOnCompletion { call.cancel() }
            try {
                while (true) {
                    val line = source.readUtf8Line() ?: break
                    if (line.isBlank() || !line.startsWith("data:")) continue
                    val payload = line.substring(5).trim()
                    if (payload == "[DONE]") break
                    val event = runCatching {
                        HaoJson.json.parseToJsonElement(payload) as? JsonObject
                    }.getOrNull() ?: continue
                    state.handle(event).forEach { emit(it) }
                    state.errorMessage?.let { throw IOException("$it（${provider.name}）") }
                }
            } finally {
                cancelHandle?.dispose()
            }

            if (!state.gotAnyContent && state.pendingTools.isEmpty()) {
                throw IOException("供应商未返回任何内容，请检查模型 ID 与 Base URL 是否匹配")
            }
            emit(SseEvent.Completed(state.buildToolCalls()))
            if (state.inputTokens > 0 || state.outputTokens > 0) {
                emit(SseEvent.Usage(state.inputTokens, state.outputTokens))
            }
        }
    }.flowOn(Dispatchers.IO)

    /** 轻量连接测试：非流式、1 token。 */
    override suspend fun testConnection(provider: ProviderConfig, apiKey: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = normalizeMessagesUrl(provider.baseUrl)
                val converted = Converted("", listOf("user" to listOf(Block.Text("ping"))))
                val requestJson = HaoJson.json.encodeToString(
                    JsonObject.serializer(),
                    buildRequestJson(provider, converted, emptyList(), stream = false, maxTokensOverride = 1)
                )
                val builder = Request.Builder()
                    .url(url)
                    .header("anthropic-version", ANTHROPIC_VERSION)
                    .post(requestJson.toRequestBody("application/json".toMediaType()))
                if (apiKey.isNotBlank()) builder.header("x-api-key", apiKey)
                okHttpClient.newCall(builder.build()).execute().use { resp ->
                    val body = runCatching { resp.body?.string() }.getOrNull().orEmpty()
                    if (resp.isSuccessful) {
                        val model = runCatching {
                            HaoJson.json.parseToJsonElement(body).jsonObject["model"]?.jsonPrimitive?.content
                        }.getOrNull()
                        model ?: provider.model
                    } else {
                        val err = runCatching {
                            HaoJson.json.parseToJsonElement(body).jsonObject["error"]
                                ?.jsonObject?.get("message")?.jsonPrimitive?.content
                        }.getOrNull()
                        throw IOException("HTTP ${resp.code}${err?.let { "：${it.take(200)}" } ?: ""}")
                    }
                }
            }
        }

    private suspend fun awaitResponse(call: Call): Response =
        suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onResponse(call: Call, response: Response) {
                    cont.resume(response)
                }

                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }
            })
        }
}
