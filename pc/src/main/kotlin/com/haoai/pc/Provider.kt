package com.haoai.pc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
    val callId: String? = null,
    /** 工具结果属于哪把工具。不存这个，历史回放时工具卡就只剩一个空壳。 */
    val name: String = "",
    /**
     * 模型的思考过程（DeepSeek/llama.cpp 的 `reasoning_content`）。
     *
     * 以前整个字段被直接丢掉：界面上看不到模型在想什么，而**长任务一旦答错，
     * 用户完全无从判断它是理解错了还是工具用错了**。移动端早就有思考链卡，
     * PC 端这次补上。存下来而不是只流一次，是为了刷新页面与重开会话还能展开看。
     */
    val reasoning: String? = null,
    /**
     * 工具卡上的行级 diff（只给界面看，[requestMessages] 不会把它发给模型）。
     *
     * 存下来是为了"刷新之后还能审阅这次改了什么"——只随 SSE 流一次的话，
     * 页面一刷新 diff 就没了，而用户往往正是看完回答才回头去核对改动。
     */
    val diff: String = "",
    /** 子任务的中间过程（只给界面，不发模型）。 */
    val sub: String = "",
    /**
     * 用户对这一步的审批结论（"允许一次 / 本任务都允许 / 写了规则 / 拒绝"）。
     *
     * 之前只随 SSE 流一次：内联卡答完就地收起，刷新之后卡没了，
     * 于是"这个文件到底是用户点头写的还是自动写的"在历史里查不出来。
     */
    val note: String = "",
    /** 这一回合自己的用量与耗时（不是会话累计），画在回答下面那行小字。 */
    val pt: Int = 0,
    val ct: Int = 0,
    val ms: Long = 0L
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
    val finishReason: String,
    val reasoning: String = ""
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

    /**
     * 带**思考流**回调的版本。正文与思考在界面上是两种东西（一个进正文气泡，
     * 一个进可折叠的"思考过程"），所以必须是两条回调，不能合成一条流。
     *
     * 默认转调三参那版并丢掉思考：现有实现与测试里的假客户端因此一行都不用改。
     */
    fun chat(
        messages: List<Msg>,
        tools: List<ToolSchema>,
        onText: (String) -> Unit,
        onReasoning: (String) -> Unit
    ): AssistantTurn = chat(messages, tools, onText)

    /**
     * 网关上可选的模型 id（顶栏那个切换器要用）。
     *
     * 默认空：假客户端与不支持 /models 的网关（实测 sensenova 就返回空数组）
     * 都不该因此报错——切换器拿到空列表时退化成"手输模型名"。
     */
    fun models(): List<String> = emptyList()
}

/** 按设置造真实网关（含密钥解析）。 */
fun chatClient(s: PcSettings): ChatClient {
    val key = System.getenv("HAOAI_API_KEY")?.takeIf { it.isNotBlank() }
        ?: runCatching { Env.apiKeyFile.takeIf { it.isFile }?.readText()?.trim() }.getOrNull().orEmpty()
    return Provider(s.baseUrl, key, s.model, s.temperature, s.maxTokens)
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
    private val temperature: Double = 0.3,
    /** 0 = 不发 max_tokens（有些网关不接受这个字段）。 */
    private val maxTokens: Int = 0
) : ChatClient {

    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(20))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    private val endpoint: String get() = baseUrl.trimEnd('/') + "/chat/completions"

    override fun chat(messages: List<Msg>, tools: List<ToolSchema>, onText: (String) -> Unit): AssistantTurn =
        chat(messages, tools, onText) { }

    override fun chat(
        messages: List<Msg>,
        tools: List<ToolSchema>,
        onText: (String) -> Unit,
        onReasoning: (String) -> Unit
    ): AssistantTurn {
        val parts: List<JsonElement> = messages.map { msgJson(it) }
        val body = buildJsonObject {
            put("model", model)
            put("temperature", temperature)
            if (maxTokens > 0) put("max_tokens", maxTokens)
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
        val box = arrayOf("", Usage(), "", "")   // text, usage, finish, reasoning

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
                if (handleLine(line.trimEnd('\r'), acc, box, onText, onReasoning)) break
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
        val reason = box[2] as String
        if (text.isBlank() && calls.isEmpty()) {
            /*
             * "被 max_tokens 截断"与"内容被安全策略挡掉"是**确定性结果**：
             * 重跑一百次也是这个结果。把它们当"网关抖动"去重试，等于白等 31 秒
             * （3+8+20 三档退避）再告诉用户一句没用的话 —— 这条是拿真模型把
             * maxTokens 调到 150 之后实测出来的（一轮全花在思考上，正文是空的）。
             * 交给引擎：它会带着 finish_reason 说一句"这轮被截断了，去改 maxTokens"。
             */
            val deterministic = reason.equals("length", true) || reason.equals("content_filter", true)
            if (!deterministic) {
                throw ProviderError(
                    "模型没有返回内容（finish_reason=${reason.ifBlank { "没有给" }}）",
                    transient = true
                )
            }
        }
        return AssistantTurn(text, calls, box[1] as Usage, reason, box[3] as String)
    }

    /** 只在对方确实给了字符串时才取值：JsonNull 会变成 "null" 字符串，对象会抛。 */
    private fun strOf(e: JsonElement?): String? = (e as? JsonPrimitive)?.contentOrNull

    /** 处理一行 SSE。返回 true 表示流结束（[DONE]）。 */
    private fun handleLine(
        line: String,
        acc: MutableMap<Int, Array<String>>,
        box: Array<Any>,
        onText: (String) -> Unit,
        onReasoning: (String) -> Unit
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
        /*
         * 这些字段全部要走 `contentOrNull`，不能用 `.jsonPrimitive?.content`。
         *
         * `JsonNull.jsonPrimitive.content` 返回的是**四个字符的字符串 "null"**，不是 null。
         * 而 OpenAI 兼容网关在"这一帧只有 tool_calls、没有正文"时，标准写法就是
         * `"content": null` —— 于是模型每调一次工具，历史里就多一句 "null"，
         * 界面上每轮都打印 "HaoAI > null"。
         * 第一次接真模型（本地 llama-server 的 Agents-A1-4B）才撞上：
         * 假网关从来不发 null，所以 85 条测试全绿也照不出来。
         */
        choice["finish_reason"]?.jsonPrimitive?.contentOrNull?.let {
            if (it.isNotBlank()) box[2] = it
        }
        val delta = choice["delta"]?.jsonObject ?: return false

        delta["content"]?.jsonPrimitive?.contentOrNull?.let { piece ->
            if (piece.isNotEmpty()) {
                box[0] = (box[0] as String) + piece
                onText(piece)
            }
        }

        /*
         * 思考过程：DeepSeek 与 llama.cpp 系放在 `reasoning_content`，也有网关用 `reasoning`，
         * 两个名字都认。以前这两个字段没人读，于是"模型想了半天"在界面上完全隐形 ——
         * 答错的时候用户分不清它是理解错了还是工具用错了。
         */
        (strOf(delta["reasoning_content"]) ?: strOf(delta["reasoning"]))?.let { r ->
            if (r.isNotEmpty()) {
                box[3] = (box[3] as String) + r
                onReasoning(r)
            }
        }

        delta["tool_calls"]?.jsonArray?.forEach { raw ->
            val tc = raw.jsonObject
            val idx = tc["index"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
            val slot = acc.getOrPut(idx) { arrayOf("", "", "") }
            tc["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { slot[0] = it }
            tc["function"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.isNotBlank() }?.let { slot[1] += it }
            tc["function"]?.jsonObject?.get("arguments")?.jsonPrimitive?.contentOrNull
                ?.let { slot[2] += it }
        }
        return false
    }

    /**
     * `GET /models`。OpenAI 兼容网关的返回形状不止一种
     * （`{"data":[{"id":…}]}` 与 llama-server 的 `{"models":[{"name":…}]}`），
     * 两种都认；再不行返回空列表，让界面上退化成手输。
     */
    override fun models(): List<String> {
        val url = baseUrl.trimEnd('/') + "/models"
        val req = HttpRequest.newBuilder(URI(url))
            .timeout(Duration.ofSeconds(12))
            .header("Authorization", "Bearer $apiKey")
            .GET().build()
        val body = runCatching {
            client.send(req, HttpResponse.BodyHandlers.ofString()).body()
        }.getOrDefault("")
        val o = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return emptyList()
        val arr = o["data"]?.jsonArray ?: o["models"]?.jsonArray ?: return emptyList()
        return arr.mapNotNull { e ->
            val m = e.jsonObject
            m["id"]?.jsonPrimitive?.contentOrNull ?: m["name"]?.jsonPrimitive?.contentOrNull
        }.distinct()
    }

    /** `haoai doctor` 用：一次最小对话，确认 key / model / 网络三件事。 */
    fun ping(): Pair<Boolean, String> = try {
        val r = chat(listOf(Msg("user", "只回两个字：好了")), emptyList()) { }
        true to (r.text.ifBlank { "(空回复)" }.trim())
    } catch (e: Exception) {
        false to (e.message ?: e.javaClass.simpleName)
    }
}
