package com.haoai.pc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** 一条会话消息。role ∈ system|user|assistant|tool。 */
data class Msg(
    val role: String,
    val content: String?,
    val calls: List<ToolCall> = emptyList(),
    val callId: String? = null
)

data class ToolCall(val id: String, val name: String, val args: String)

data class ToolSchema(val name: String, val desc: String, val params: JsonObject)

data class Usage(val promptTokens: Int = 0, val completionTokens: Int = 0, val cachedTokens: Int = 0)

class ProviderError(message: String, val status: Int = 0, val transient: Boolean = false) : Exception(message)

/** 一次模型回合的结果。 */
data class AssistantTurn(
    val text: String,
    val calls: List<ToolCall>,
    val usage: Usage,
    val finishReason: String
)

/** OpenAI wire 格式：assistant 带 tool_calls；tool 消息带 tool_call_id。 */
internal fun msgJson(m: Msg): JsonObject = buildJsonObject {
    put("role", m.role)
    when {
        m.calls.isNotEmpty() -> {
            put("content", m.content ?: "")
            put(
                "tool_calls",
                JsonArray(m.calls.map { c ->
                    buildJsonObject {
                        put("id", c.id)
                        put("type", "function")
                        put("function", buildJsonObject {
                            put("name", c.name)
                            put("arguments", if (c.args.isBlank()) "{}" else c.args)
                        })
                    }
                })
            )
        }
        m.role == "tool" -> {
            put("tool_call_id", m.callId ?: "")
            put("content", m.content ?: "")
        }
        else -> put("content", m.content ?: "")
    }
}

/**
 * 模型网关的接缝。
 *
 * 为什么要这个接口而不是让 Engine 直接 new Provider：手机端那套引擎能在纯 JVM 单测里被驱动，
 * 靠的就是"把模型换成脚本"这一招（`AgentEngineLoopTest`）。PC 端一开始就留这个缝，
 * 工具层 / 审批 / 溢出 / 落库才能脱离密钥做端到端验证（见 `EngineFlowTest`）。
 */
interface ChatClient {
    fun chat(messages: List<Msg>, tools: List<ToolSchema>, onText: (String) -> Unit): AssistantTurn
}

/** 按设置造真实网关（含密钥解析）。 */
fun chatClient(s: PcSettings): ChatClient {
    val key = System.getenv("HAOAI_API_KEY")?.takeIf { it.isNotBlank() }
        ?: runCatching { Env.apiKeyFile.takeIf { it.isFile }?.readText()?.trim() }.getOrNull().orEmpty()
    return Provider(s.baseUrl, key, s.model, s.temperature)
}

/**
 * OpenAI 兼容网关（`/chat/completions`，流式）。
 *
 * 不复用手机端那份 provider：它绑在 OkHttp 与 Android 的 Base64/Log 上，而这里要的只是同一份
 * wire 契约。字段对齐即可，将来抽 `:core` 时这份当 PC 侧参考实现。
 *
 * 流式是**必须**的：PC 上跑一条命令动辄几十秒，非流式整个界面像死了。
 * tool_calls 在流式下分片到达（`arguments` 一段一段拼），这里按 index 归并。
 */
class Provider(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val temperature: Double = 0.3
) : ChatClient {

    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(20))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    private val endpoint: String get() = baseUrl.trimEnd('/') + "/chat/completions"

    override fun chat(messages: List<Msg>, tools: List<ToolSchema>, onText: (String) -> Unit): AssistantTurn {
        val parts: List<JsonElement> = messages.map { msgJson(it) }
        val body = buildJsonObject {
            put("model", model)
            put("temperature", temperature)
            put("stream", true)
            put("stream_options", buildJsonObject { put("include_usage", true) })
            put("messages", JsonArray(parts))
            if (tools.isNotEmpty()) {
                put(
                    "tools",
                    JsonArray(tools.map { t ->
                        buildJsonObject {
                            put("type", "function")
                            put("function", buildJsonObject {
                                put("name", t.name)
                                put("description", t.desc)
                                put("parameters", t.params)
                            })
                        }
                    })
                )
            }
        }.toString()

        val req = HttpRequest.newBuilder(URI(endpoint))
            .timeout(Duration.ofMinutes(8))
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .header("Authorization", "Bearer $apiKey")
            .POST(HttpRequest.BodyPublishers.ofString(body, Charsets.UTF_8))
            .build()

        val resp = try {
            client.send(req, HttpResponse.BodyHandlers.ofInputStream())
        } catch (e: Exception) {
            throw ProviderError("连接失败：${e.message}", transient = true)
        }

        if (resp.statusCode() !in 200..299) {
            val st = resp.statusCode()
            val err = runCatching { resp.body().use { it.readBytes().toString(Charsets.UTF_8) } }
                .getOrDefault("")
            throw ProviderError("HTTP $st ${err.take(600)}", st, st == 408 || st == 429 || st in 500..599)
        }

        val acc = LinkedHashMap<Int, Array<String>>()
        val box = arrayOf("", Usage(), "")   // text, usage, finish

        /**
         * 按行解析 SSE。
         *
         * 这里用 `InputStreamReader` 而不是自己攒字节：JDK 的 StreamDecoder 会把跨块没读全的
         * 半个 UTF-8 序列留在内部缓冲区里等下一块，中文 arguments 不会被切成 U+FFFD
         * （`ProviderStreamTest` 用真 HTTP + 9 字节分片把这件事钉住了）。
         */
        java.io.BufferedReader(java.io.InputStreamReader(resp.body(), Charsets.UTF_8)).use { br ->
            while (true) {
                val line = br.readLine() ?: break
                if (handleLine(line.trimEnd('\r'), acc, box, onText)) break
            }
        }

        val calls = acc.entries.sortedBy { it.key }.mapIndexed { i, (_, v) ->
            ToolCall(
                id = if (v[0].isBlank()) "call_${i + 1}" else v[0],
                name = v[1],
                args = if (v[2].isBlank()) "{}" else v[2]
            )
        }.filter { it.name.isNotBlank() }

        val text = box[0] as String
        if (text.isBlank() && calls.isEmpty()) {
            throw ProviderError("模型没有返回内容", transient = true)
        }
        return AssistantTurn(text, calls, box[1] as Usage, box[2] as String)
    }

    /** 处理一行 SSE。返回 true 表示流结束（[DONE]）。 */
    private fun handleLine(
        line: String,
        acc: MutableMap<Int, Array<String>>,
        box: Array<Any>,
        onText: (String) -> Unit
    ): Boolean {
        if (line.isEmpty() || line.startsWith(":")) return false
        if (!line.startsWith("data:")) return false
        val payload = line.substring(5).trim()
        if (payload == "[DONE]") return true
        val obj = runCatching { Json.parseToJsonElement(payload).jsonObject }.getOrNull() ?: return false

        obj["usage"]?.jsonObject?.let { u ->
            val old = box[1] as Usage
            box[1] = Usage(
                promptTokens = u["prompt_tokens"]?.jsonPrimitive?.longOrNull?.toInt() ?: old.promptTokens,
                completionTokens = u["completion_tokens"]?.jsonPrimitive?.longOrNull?.toInt() ?: old.completionTokens,
                cachedTokens = u["prompt_tokens_details"]?.jsonObject?.get("cached_tokens")
                    ?.jsonPrimitive?.longOrNull?.toInt() ?: old.cachedTokens
            )
        }

        val choice = obj["choices"]?.jsonArray?.firstOrNull()?.jsonObject ?: return false
        choice["finish_reason"]?.jsonPrimitive?.content?.let { if (it.isNotBlank() && it != "null") box[2] = it }
        val delta = choice["delta"]?.jsonObject ?: return false

        delta["content"]?.jsonPrimitive?.content?.let { piece ->
            if (piece.isNotEmpty()) {
                box[0] = (box[0] as String) + piece
                onText(piece)
            }
        }

        delta["tool_calls"]?.jsonArray?.forEach { raw ->
            val tc = raw.jsonObject
            val idx = tc["index"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
            val slot = acc.getOrPut(idx) { arrayOf("", "", "") }
            tc["id"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }?.let { slot[0] = it }
            tc["function"]?.jsonObject?.get("name")?.jsonPrimitive?.content
                ?.takeIf { it.isNotBlank() }?.let { slot[1] += it }
            tc["function"]?.jsonObject?.get("arguments")?.jsonPrimitive?.content
                ?.let { slot[2] += it }
        }
        return false
    }

    /** `haoai doctor` 用：一次最小对话，确认 key / model / 网络三件事。 */
    fun ping(): Pair<Boolean, String> = try {
        val r = chat(listOf(Msg("user", "只回两个字：好了")), emptyList()) { }
        true to (r.text.ifBlank { "(空回复)" }.trim())
    } catch (e: Exception) {
        false to (e.message ?: e.javaClass.simpleName)
    }
}
