package com.haoai.agent.agent.provider

import com.haoai.agent.data.HaoJson
import com.haoai.agent.data.ProviderConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.encodeStructure
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
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

@Serializable(with = ApiMessageSerializer::class)
data class ApiMessage(
    val role: String,
    val content: String? = null,
    /** 多模态内容片段（text / image_url）；非空时以 content 数组序列化。 */
    val parts: List<ApiContentPart>? = null,
    @SerialName("tool_calls") val toolCalls: List<ApiToolCall>? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
    val name: String? = null
)

@Serializable
data class ApiContentPart(
    val type: String,
    val text: String? = null,
    @SerialName("image_url") val imageUrl: ApiImageUrl? = null
)

@Serializable
data class ApiImageUrl(val url: String)

/** content 字段按需序列化为字符串（纯文本）或数组（多模态）。 */
object ApiMessageSerializer : kotlinx.serialization.KSerializer<ApiMessage> {

    /** 仅用于生成正确的元素描述符。 */
    @kotlinx.serialization.Serializable
    private data class Shape(
        val role: String,
        val content: String? = null,
        @SerialName("tool_calls") val toolCalls: List<ApiToolCall>? = null,
        @SerialName("tool_call_id") val toolCallId: String? = null,
        val name: String? = null
    )

    override val descriptor: SerialDescriptor = Shape.serializer().descriptor

    override fun serialize(encoder: Encoder, value: ApiMessage) =
        encoder.encodeStructure(descriptor) {
            encodeStringElement(descriptor, 0, value.role)
            when {
                value.parts != null -> encodeSerializableElement(
                    descriptor, 1, JsonArray.serializer(),
                    buildJsonArray {
                        value.parts.forEach { p ->
                            addJsonObject {
                                put("type", p.type)
                                p.text?.let { put("text", it) }
                                p.imageUrl?.let { iu ->
                                    putJsonObject("image_url") { put("url", iu.url) }
                                }
                            }
                        }
                    }
                )
                else -> encodeStringElement(descriptor, 1, value.content.orEmpty())
            }
            if (!value.toolCalls.isNullOrEmpty()) {
                encodeSerializableElement(
                    descriptor, 2, ListSerializer(ApiToolCall.serializer()), value.toolCalls
                )
            }
            value.toolCallId?.let { encodeStringElement(descriptor, 3, it) }
            value.name?.let { encodeStringElement(descriptor, 4, it) }
        }

    override fun deserialize(decoder: Decoder): ApiMessage =
        throw SerializationException("ApiMessage 仅用于请求序列化")
}

@Serializable
data class ApiToolCall(
    val id: String,
    val type: String = "function",
    val function: ApiFunctionCall
)

@Serializable
data class ApiFunctionCall(
    val name: String,
    val arguments: String
)

@Serializable
data class ApiToolDef(
    val name: String,
    val description: String,
    val parameters: JsonObject
)

@Serializable
data class ApiTool(
    val type: String = "function",
    val function: ApiToolDef
)

@Serializable
private data class ChatCompletionRequest(
    val model: String,
    val messages: List<ApiMessage>,
    val tools: List<ApiTool>? = null,
    val stream: Boolean = true,
    val temperature: Double? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    @SerialName("stream_options") val streamOptions: StreamOptions? = null,
    @SerialName("reasoning_effort") val reasoningEffort: String? = null
)

@Serializable
private data class StreamOptions(val include_usage: Boolean = true)

@Serializable
private data class UsageInfo(
    @SerialName("prompt_tokens") val promptTokens: Int = 0,
    @SerialName("completion_tokens") val completionTokens: Int = 0
)

@Serializable
private data class StreamChunk(val choices: List<StreamChoice> = emptyList(), val usage: UsageInfo? = null)

@Serializable
private data class StreamChoice(
    val index: Int = 0,
    val delta: StreamDelta = StreamDelta(),
    @SerialName("finish_reason") val finishReason: String? = null
)

@Serializable
private data class StreamDelta(
    val content: String? = null,
    /** 推理过程增量（DeepSeek-R1 / OpenRouter reasoning models 等返回的 reasoning_content 字段）。 */
    @SerialName("reasoning_content") val reasoning: String? = null,
    @SerialName("tool_calls") val toolCalls: List<StreamToolCall>? = null
)

@Serializable
private data class StreamToolCall(
    val index: Int? = null,
    val id: String? = null,
    val function: StreamFunctionFragment? = null
)

@Serializable
private data class StreamFunctionFragment(
    val name: String? = null,
    val arguments: String? = null
)

sealed interface SseEvent {
    data class Delta(val text: String) : SseEvent

    /** 思考过程增量：来自 reasoning_content 字段或内容中的 <think>…</think> 标签。 */
    data class Reasoning(val text: String) : SseEvent
    data class Completed(val toolCalls: List<com.haoai.agent.agent.model.ToolCallData>) : SseEvent
    data class Usage(val promptTokens: Int, val completionTokens: Int) : SseEvent
}

/**
 * 流式 <think>…</think> 内联推理标签解析器：
 * 增量可能把标签劈成任意位置，保留尾部疑似前缀凑齐判定；
 * 未闭合标签在流结束时按思考内容冲出。
 */
class ThinkTagParser {
    private val open = "<think>"
    private val close = "</think>"
    private var inThink = false
    private val buf = StringBuilder()

    /** @return first=可见正文增量 second=思考过程增量 */
    fun feed(chunk: String): Pair<String, String> {
        buf.append(chunk)
        val visible = StringBuilder()
        val reasoning = StringBuilder()
        while (true) {
            val tag = if (inThink) close else open
            val idx = buf.indexOf(tag)
            if (idx >= 0) {
                val content = buf.substring(0, idx)
                if (inThink) reasoning.append(content) else visible.append(content)
                buf.delete(0, idx + tag.length)
                inThink = !inThink
                continue
            }
            val hold = holdBackLen(buf, tag)
            val emitLen = buf.length - hold
            if (emitLen > 0) {
                val content = buf.substring(0, emitLen)
                if (inThink) reasoning.append(content) else visible.append(content)
                buf.delete(0, emitLen)
            }
            break
        }
        return visible.toString() to reasoning.toString()
    }

    /** @return first=可见正文残留 second=思考过程残留 */
    fun flush(): Pair<String, String> {
        if (buf.isEmpty()) return "" to ""
        val rest = buf.toString()
        buf.setLength(0)
        return if (inThink) "" to rest else rest to ""
    }

    private fun holdBackLen(s: StringBuilder, tag: String): Int {
        val max = minOf(s.length, tag.length - 1)
        for (len in max downTo 1) {
            var match = true
            for (i in 0 until len) {
                if (s[s.length - len + i] != tag[i]) { match = false; break }
            }
            if (match) return len
        }
        return 0
    }
}

class OpenAiCompatClient(private val okHttpClient: OkHttpClient) {

    suspend fun chatStream(
        provider: ProviderConfig,
        apiKey: String,
        messages: List<ApiMessage>,
        tools: List<ApiTool>,
        reasoningEffort: String? = null
    ): Flow<SseEvent> = flow {
        val url = normalizeUrl(provider.baseUrl)
        val isLocal = provider.baseUrl.contains("127.0.0.1")
        val requestJson = HaoJson.json.encodeToString(
            ChatCompletionRequest.serializer(),
            ChatCompletionRequest(
                model = provider.model,
                messages = messages.map { m ->
                    if (m.toolCalls.isNullOrEmpty()) m.copy(toolCalls = null) else m
                },
                tools = tools.ifEmpty { null },
                // 云端：用户配置的 maxTokens（0=供应商默认）；本地：默认 4096（原 1024 会硬截断回复）
                maxTokens = provider.effectiveMaxTokens().takeIf { it > 0 },
                streamOptions = StreamOptions(),
                reasoningEffort = reasoningEffort?.takeIf { it.isNotBlank() && !isLocal }
            )
        )
        val builder = Request.Builder()
            .url(url)
            .header("Accept", "text/event-stream")
            .post(requestJson.toRequestBody("application/json".toMediaType()))
        if (apiKey.isNotBlank()) builder.header("Authorization", "Bearer $apiKey")

        val call = okHttpClient.newCall(builder.build())
        val response = awaitResponse(call)
        response.use { resp ->
            if (!resp.isSuccessful) {
                val errBody = runCatching { resp.body?.string() }.getOrNull()?.take(400)
                throw IOException("HTTP ${resp.code}（${provider.name}）${errBody?.let { "：$it" } ?: ""}")
            }
            val source = resp.body?.source() ?: throw IOException("响应为空")

            var gotAnyContent = false
            var counter = 0
            var usage: UsageInfo? = null
            data class Pending(
                var id: String? = null,
                val name: StringBuilder = StringBuilder(),
                val args: StringBuilder = StringBuilder()
            )

            val pending = sortedMapOf<Int, Pending>()

            // 内容内联 <think>…</think> 解析（llama.cpp / Qwen3 等端侧模型把推理混在 content 里）。
            // 流式增量可能把标签劈成两半，保留尾部疑似标签前缀，凑齐后再判定。
            val thinkParser = ThinkTagParser()

            // 读循环是阻塞 IO：协程取消不会自动中断 socket 读（本地推理 prefill 数分钟无
            // token，停止键会卡到读超时）。挂 completion 钩子在取消时强制断开连接。
            val cancelHandle = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
                ?.invokeOnCompletion { call.cancel() }
            try {
                while (true) {
                    val line = source.readUtf8Line() ?: break
                    if (line.isBlank()) continue
                    if (!line.startsWith("data:")) continue
                    val payload = line.substring(5).trim()
                    if (payload == "[DONE]") break
                    val chunk = runCatching {
                        HaoJson.json.decodeFromString(StreamChunk.serializer(), payload)
                    }.getOrNull() ?: continue
                    chunk.usage?.let { if (it.promptTokens > 0 || it.completionTokens > 0) usage = it }

                    for (choice in chunk.choices) {
                        choice.delta.reasoning?.takeIf { it.isNotEmpty() }?.let {
                            gotAnyContent = true
                            emit(SseEvent.Reasoning(it))
                        }
                        choice.delta.content?.takeIf { it.isNotEmpty() }?.let {
                            gotAnyContent = true
                            val (visible, reasoning) = thinkParser.feed(it)
                            if (reasoning.isNotEmpty()) emit(SseEvent.Reasoning(reasoning))
                            if (visible.isNotEmpty()) emit(SseEvent.Delta(visible))
                        }
                        choice.delta.toolCalls?.forEach { tc ->
                            val idx = tc.index ?: pending.size
                            val p = pending.getOrPut(idx) { Pending() }
                            tc.id?.let { p.id = it }
                            tc.function?.name?.let { p.name.append(it) }
                            tc.function?.arguments?.let { p.args.append(it) }
                        }
                        if (!gotAnyContent && choice.delta.toolCalls != null && choice.delta.toolCalls.isNotEmpty()) {
                            gotAnyContent = true
                        }
                    }
                }
            } finally {
                cancelHandle?.dispose()
            }

            // 流结束：冲出残留缓冲（未闭合的 <think> 尾部按思考内容处理）
            val (tailVisible, tailReasoning) = thinkParser.flush()
            if (tailReasoning.isNotEmpty()) emit(SseEvent.Reasoning(tailReasoning))
            if (tailVisible.isNotEmpty()) emit(SseEvent.Delta(tailVisible))

            if (!gotAnyContent && pending.isEmpty()) {
                throw IOException("供应商未返回任何内容，请检查模型 ID 与 Base URL 是否匹配")
            }

            val calls = pending.entries.sortedBy { it.key }.mapIndexedNotNull { i, (_, p) ->
                val name = p.name.toString().ifBlank { return@mapIndexedNotNull null }
                com.haoai.agent.agent.model.ToolCallData(
                    id = p.id ?: "call_${counter++}_$i",
                    name = name,
                    argumentsJson = p.args.toString().ifBlank { "{}" }
                )
            }
            emit(SseEvent.Completed(calls))
            usage?.let { emit(SseEvent.Usage(it.promptTokens, it.completionTokens)) }
        }
    }.flowOn(Dispatchers.IO)

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

    /** 轻量连接测试：非流式、1 token，返回成功时的模型名或失败原因。 */
    suspend fun testConnection(
        provider: ProviderConfig,
        apiKey: String
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val url = normalizeUrl(provider.baseUrl)
            val requestJson = HaoJson.json.encodeToString(
                TestRequest.serializer(),
                TestRequest(model = provider.model, maxTokens = 1)
            )
            val builder = Request.Builder()
                .url(url)
                .post(requestJson.toRequestBody("application/json".toMediaType()))
            if (apiKey.isNotBlank()) builder.header("Authorization", "Bearer $apiKey")
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

    /** 拉取供应商可用模型列表（GET /models）。 */
    suspend fun listModels(
        baseUrl: String,
        apiKey: String
    ): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            var url = baseUrl.trim().trimEnd('/')
            require(url.startsWith("http://") || url.startsWith("https://")) {
                "Base URL 必须以 http(s):// 开头"
            }
            if (!Regex("/v\\d+(/.*)?$").containsMatchIn(url)) url += "/v1"
            url += "/models"
            val builder = Request.Builder().url(url)
            if (apiKey.isNotBlank()) builder.header("Authorization", "Bearer $apiKey")
            okHttpClient.newCall(builder.build()).execute().use { resp ->
                val body = runCatching { resp.body?.string() }.getOrNull().orEmpty()
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                val el = HaoJson.json.parseToJsonElement(body)
                val arr = el.jsonObject["data"]?.jsonArray
                    ?: el.jsonArray
                    ?: throw IOException("响应格式无法解析")
                arr.mapNotNull { m ->
                    runCatching { m.jsonObject["id"]?.jsonPrimitive?.content }.getOrNull()
                }.filter { it.isNotBlank() }.distinct().sorted()
            }
        }
    }

    @Serializable
    private data class TestRequest(
        val model: String,
        val stream: Boolean = false,
        @SerialName("max_tokens") val maxTokens: Int = 1,
        val messages: List<TestMessage> = listOf(TestMessage("user", "ping"))
    )

    @Serializable
    private data class TestMessage(val role: String, val content: String)

    companion object {
        fun normalizeUrl(raw: String): String {
            var url = raw.trim().trimEnd('/')
            require(url.startsWith("http://") || url.startsWith("https://")) {
                "Base URL 必须以 http(s):// 开头"
            }
            if (!url.endsWith("/chat/completions")) {
                if (!Regex("/v\\d+(/.*)?$").containsMatchIn(url)) {
                    url += "/v1"
                }
                url += "/chat/completions"
            }
            return url
        }
    }
}
