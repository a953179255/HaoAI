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

/**
 * 一条会话消息（B15 第二片：定义进了 `:core`，这里原地 typealias —— 同包同名，
 * pc 全部引用点零改动）。字段名 = PC 手写落盘格式，**不许改**（538 条里有持久化锁）；
 * 移动端独有字段（id/error/imageData/…）是 core 里的并集字段，PC 不写不读。
 */
typealias Msg = com.haoai.core.Msg

/** 一次工具调用（同上：core 定义、原地别名）。 */
typealias ToolCall = com.haoai.core.ToolCall

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
        m.role == "user" && m.images.isNotEmpty() -> {
            /*
             * OpenAI 兼容的多模态写法：user 的 content 换成数组。
             * 只给 user 角色用数组 —— tool 角色很多网关不接受数组 content，
             * 一张坏图不该把整轮对话弄发不出去，所以认不出类型/读不到的图直接跳过。
             */
            val parts = mutableListOf<JsonElement>()
            if (!m.content.isNullOrBlank()) parts += buildJsonObject {
                put("type", "text"); put("text", m.content)
            }
            m.images.forEach { p ->
                val url = Images.dataUrl(p)
                if (url != null) parts += buildJsonObject {
                    put("type", "image_url")
                    put("image_url", buildJsonObject { put("url", url) })
                }
            }
            if (parts.isEmpty()) put("content", m.content ?: "") else put("content", JsonArray(parts))
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
fun chatClient(s: PcSettings): ChatClient = chatClient(s, null)

/**
 * 按链上某一档造客户端。`slot=null` 就是主档（用设置里的 model 与 baseUrl）。
 *
 * 那把 key 只在**同一个主机**上才跟着走：备用地址是用户自己填的，可能是本机 llama-server，
 * 也可能是陌生人的网关。把 sensenova 的 key 发去陌生主机，等于为了"别断"而泄露凭据 ——
 * 本地 llama-server 本来就不看 key，所以不带也不影响它工作。
 */
fun chatClient(s: PcSettings, slot: ModelSlot?): ChatClient {
    val key = System.getenv("HAOAI_API_KEY")?.takeIf { it.isNotBlank() }
        ?: runCatching { Env.apiKeyFile.takeIf { it.isFile }?.readText()?.trim() }.getOrNull().orEmpty()
    val base = slot?.baseUrl?.takeIf { it.isNotBlank() } ?: s.baseUrl
    val sendKey = if (sameHost(base, s.baseUrl)) key else ""
    return Provider(base, sendKey, slot?.model?.takeIf { it.isNotBlank() } ?: s.model,
        s.temperature, s.maxTokens, s.reasoningEffort)
}

/** 降级链上的一档。`baseUrl` 空 = 沿用主网关。 */
data class ModelSlot(val model: String, val baseUrl: String = "")

/**
 * 解析 `fallback` 那串：`glm-5.2, tiny@http://127.0.0.1:8080/v1` → 两档。
 *
 * 主档不在这里（它由设置里的 model/baseUrl 决定），这里只解析"备胎"。
 * 认逗号、分号、换行三种分隔，是因为这串东西既可能在命令行里写、也可能在设置抽屉的
 * 单行输入框里粘贴 —— 只认一种就会"我明明填了三个，怎么只降了一次"。
 */
fun parseFallback(raw: String): List<ModelSlot> =
    raw.split(',', ';', '\n').map { it.trim() }.filter { it.isNotEmpty() }.map { e ->
        val at = e.indexOf('@')
        if (at < 0) ModelSlot(e, "")
        else ModelSlot(e.substring(0, at).trim(), e.substring(at + 1).trim())
    }.filter { it.model.isNotEmpty() }

/** 两个地址是不是同一台主机（协议、主机、端口三项都要一致：8080 与 8081 上是两套凭据）。 */
fun sameHost(a: String, b: String): Boolean {
    // 默认端口自己算，不用 URI.getDefaultPort()：这台机器的编译环境解析不到那个方法，
    // 而这里只需要 http/https 两种（别的协议一律按"没写端口 = -1"处理，两边一致就能比）。
    fun key(u: String): String = runCatching {
        val x = java.net.URI(u.trim())
        val scheme = x.scheme?.lowercase() ?: ""
        val port = if (x.port > 0) x.port else when (scheme) {
            "http" -> 80; "https" -> 443; else -> -1
        }
        "$scheme|${x.host?.lowercase()}|$port"
    }.getOrDefault("bad|$u")
    return key(a) == key(b)
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
    private val maxTokens: Int = 0,
    /**
     * 思考强度（OpenAI 的 `reasoning_effort`）。空 = 不发这个字段。
     *
     * 刻意可空：本地 llama-server 与不少兼容网关收到不认识的字段会直接 400，
     * "默认不发、用户显式选了才发"才不会把现有部署弄坏。
     */
    private var reasoningEffort: String = ""
) : ChatClient {


    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(20))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    private val endpoint: String get() = baseUrl.trimEnd('/') + "/chat/completions"

    /**
     * 请求头里能用的 key：先剥掉换行与首尾空白，再拦非 ASCII。
     *
     * 为什么必须自己拦：`HttpRequest` 遇到非法头值只抛
     * `invalid header value: "Bearer sk-…"` —— 从这句看不出是**复制粘贴把中文带进 key 里了**，
     * 用户会以为网关坏了。实测就是这么撞出来的（备份测试里拿中文当假 key 种数据）。
     */
    private fun headerKey(): String {
        val k = apiKey.lines().joinToString("") { it.trim() }   // 去换行与空白，刻意不写反斜杠转义
        if (k.isNotEmpty() && !k.all { it.code in 0x20..0x7e }) throw IllegalArgumentException(
            "API key 里有非 ASCII 或控制字符（多半是复制时带进了中文或换行），请重新粘贴一段纯 ASCII 的 key")
        return k
    }

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
            if (reasoningEffort.isNotBlank()) put("reasoning_effort", reasoningEffort)
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
            .header("Authorization", "Bearer " + headerKey())
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
            .header("Authorization", "Bearer " + headerKey())
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
