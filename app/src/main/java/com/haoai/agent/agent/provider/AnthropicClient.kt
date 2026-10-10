package com.haoai.agent.agent.provider

import com.haoai.agent.agent.model.ToolCallData
import com.haoai.agent.agent.model.newFallbackCallId
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
            pendingTools.entries.sortedBy { it.key }.mapNotNull { (_, p) ->
                val name = p.name.ifBlank { return@mapNotNull null }
                ToolCallData(
                    // Anthropic 正常都带 toolu_ id；万一为空时兜底序号必须进程内单调，
                    // 用本轮下标会跨轮撞号（见 newFallbackCallId 注释）
                    id = p.id.ifBlank { newFallbackCallId() },
                    name = name,
                    args = p.args.toString().ifBlank { "{}" }
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
            maxTokensOverride: Int? = null,
            reasoningEffort: String? = null
        ): JsonObject = buildJsonObject {
            put("model", provider.model)
            // Anthropic 必填 max_tokens：云端未配置时 4096 兜底
            put("max_tokens", maxTokensOverride ?: provider.effectiveMaxTokens().takeIf { it > 0 } ?: 4096)
            // 采样参数开关：Anthropic 仅支持 temperature/top_p，penalty 无对应字段
            if (provider.sendTemperature) put("temperature", provider.temperature.toDouble())
            if (provider.sendTopP) put("top_p", provider.topP.toDouble())
            if (converted.system.isNotBlank()) put("system", converted.system)
            put("stream", stream)
            // M13：思考等级。此前 reasoningEffort 形参被接收后**全函数体无引用**——
            // 用户在设置里配了等级、界面显示已配置、实际请求里没有，静默丢弃。
            //
            // 关键：Anthropic 的字段是 `output_config.effort`，**不是** OpenAI 的
            // `reasoning_effort`，写错会直接 400。且并非所有 Claude 模型都支持
            // （老模型只有 thinking.budget_tokens 形态）⇒ 必须门控，不能无条件发。
            // 不支持的场景显式记日志，不再静默吞掉。
            anthropicEffort(provider.model, reasoningEffort)?.let { put("output_config", buildJsonObject { put("effort", it) }) }
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

        /**
         * M13：把内部统一的思考等级映射成 Anthropic 的 `output_config.effort`。
         *
         * 返回 null 表示"这次不发"。三种情况：
         * ① 上游没给等级（null / 空 / `off`）—— 本来就不该发；
         * ② 用户在模型能力里显式标了不支持（`caps().reasoning == false`）—— 尊重设置；
         * ③ 模型型号不在支持列表里—— 发了会 400，比不发更糟。
         *
         * [provider] 只用来看能力开关，型号取`provider.model`。
         */
        private fun anthropicEffort(model: String, effort: String?): String? {
            val e = effort?.trim()?.lowercase()?.takeIf { it.isNotEmpty() && it != "off" } ?: return null
            if (!supportsEffort(model)) {
                android.util.Log.w(
                    "HaoProvider",
                    "模型 $model 不支持 Anthropic output_config.effort，已忽略思考等级 $e" +
                        "（该型号只有 thinking.budget_tokens 形态，需要的话得另做映射）"
                )
                return null
            }
            // 官方枚举：low / medium / high / xhigh / max。未知值一律不发，
            // 宁可少发也不要发一个会被服务端拒的枚举。
            return e.takeIf { it in setOf("low", "medium", "high", "xhigh", "max") }
        }

        /**
         * 哪些 Claude 型号支持 `output_config.effort`（2026-10-10 查官方 Effort 页核定）。
         *
         * 官方列出的支持型号：Opus 5 全系（含 5.5）、Opus 4.8 / 4.7 / 4.6、Opus 4.5、
         * Sonnet 5、Sonnet 4.6、Fable 5 / 5.1、Mythos 5 / 5.1、Mythos Preview。
         * 更老的型号（Opus 4 / 4.1、Sonnet 4 / 4.5、Haiku 全系、3.x 系）**只有
         * `thinking.budget_tokens` 形态**，发 effort 会 400。
         *
         * ## 踩过的坑：别让正则把日期当成版本号
         *
         * 最初写成 `(family)[-_.]?(\d+)`，结果 `claude-3-5-sonnet-20241022` 里
         * 引擎会**跳过** `3-5` 去匹配 `sonnet-20241022`，把日期 `20241022`
         * 当成 major（≥5 ⇒ 放行）—— 于是一个明确不支持的老型号被判定为支持，
         * 真发出去就是 400。离线用 26 个用例扫才抓到，光看代码看不出来。
         *
         * 所以判据必须**先剥掉日期/版本后缀，再要求版本号紧跟 family 名**：
         * ① 先循环剥尾（`-20241022` / `@20260101` / `-v1` / `:1`，有的型号日期后又跟 -v1）；
         * ② 版本号限定 1 位（family 后的第一段永远是 1 位数：4 / 5），
         *    次段才是真正的 minor，允许 `.5` 和 `-5` 两种写法。
         */
        internal fun supportsEffort(model: String): Boolean {
            var m = model.lowercase()
            var prev = ""
            while (prev != m) {
                prev = m
                m = m.replace(Regex("""[-_.@]?(?:\d{8}|\d{6}|v\d+(?::\d+)?)$"""), "")
            }
            val vm = Regex("""(opus|sonnet|haiku|fable|mythos)[-_.](\d)(?:\.(\d)|[-_.](\d))?""")
                .find(m) ?: return false
            val major = vm.groupValues[2].toInt()
            val minor = (vm.groupValues[3].ifEmpty { vm.groupValues[4] }).takeIf { it.isNotEmpty() }?.toInt() ?: 0
            return when (vm.groupValues[1]) {
                "opus" -> major > 4 || (major == 4 && minor >= 5)
                // sonnet 5 / 4.6 支持；4.5 及更早不支持
                "sonnet" -> major >= 5 || (major == 4 && minor >= 6)
                // fable / mythos 仅 5 系
                "fable", "mythos" -> major >= 5
                else -> false
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
            buildRequestJson(provider, converted, tools, stream = true, reasoningEffort = reasoningEffort)
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
