package com.haoai.pc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

/**
 * MCP 客户端：把外部 MCP server 的工具接进引擎的工具表。
 *
 * 为什么要这一层：参照列表里那几家（codex / claude-code / opencode / dsh）都靠 MCP
 * 把"我实现不了的能力"变成生态 —— 数据库、issue 系统、公司内部工具，不用每个 agent
 * 各写一遍。HaoAI 这边没有它，用户就只能用内置的那十几把工具。
 *
 * 协议：stdio 上跑**换行分隔**的 JSON-RPC 2.0（不是 LSP 那套 Content-Length 头）。
 * 流程 `initialize` → `notifications/initialized` → `tools/list` → `tools/call`。
 *
 * 安全边界（三条，都不许放宽）：
 * 1. 整条链路挂在 [HaoFlag.MCP_CLIENT] 上，**默认关**；关着时这些工具对模型不存在，
 *    不是"看得见但调不动"。理由和浏览器控制一样：外部 server 能干什么，我们不知道。
 * 2. 每个 server 由**用户自己写在配置文件里**（命令 + 参数），界面只能改这个文件，
 *    模型不能凭空拉起一个进程。
 * 3. 工具 `kind="write"`：计划模式一律挡住，其余档位每次调用都过权限闸。
 */
data class McpServer(
    val name: String,
    val command: String,
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap()
) {
    fun render(): String = (listOf(command) + args).joinToString(" ")
}

/** `HAOAI_HOME/mcp.json`：`{"servers":[{"name","command","args":[],"env":{}}]}`。 */
object McpConfig {
    fun file(): File = File(Env.home, "mcp.json")

    fun load(): List<McpServer> = runCatching {
        Json.parseToJsonElement(file().readText()).jsonObject["servers"]?.jsonArray?.mapNotNull { e ->
            val o = e.jsonObject
            val n = o["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val c = o["command"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (n.isEmpty() || c.isEmpty()) return@mapNotNull null
            McpServer(
                n, c,
                o["args"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty(),
                o["env"]?.jsonObject?.mapNotNull { (k, v) ->
                    val s = v.jsonPrimitive.contentOrNull
                    if (s == null) null else k to s
                }?.toMap().orEmpty()
            )
        }.orEmpty()
    }.getOrDefault(emptyList())

    fun save(list: List<McpServer>) {
        val body = list.joinToString(",", "[", "]") { s ->
            """{"name":${js(s.name)},"command":${js(s.command)},""" +
                """"args":[${s.args.joinToString(",") { js(it) }}],""" +
                """"env":{${s.env.entries.joinToString(",") { "${js(it.key)}:${js(it.value)}" }}}}"""
        }
        runCatching {
            file().parentFile?.mkdirs()
            file().writeText("""{"servers":$body}""")
        }
    }

    private fun js(s: String): String = "\"" +
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
}

/**
 * 一个 stdio MCP 连接。
 *
 * 读响应时**按 id 匹配**并跳过别人的消息：server 会主动推 `notifications/xxx` 这类通知，
 * （日志、进度），上一封请求的回包也可能晚到 —— 不按 id 挑就会把通知当答案解析，
 * 症状是"工具结果是一团看不懂的东西"。
 */
class McpClient(val server: McpServer) {
    private val json = Json { ignoreUnknownKeys = true }
    private var proc: Process? = null
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null
    private var seq = 0
    private var lastError = ""

    val running: Boolean get() = proc?.isAlive == true
    val error: String get() = lastError

    /** 起进程并握手。失败不抛异常，只记 error —— 一个坏 server 不该拖垮整个工具表。 */
    fun start(): McpClient {
        if (running) return this
        runCatching {
            val pb = ProcessBuilder(listOf(server.command) + server.args)
                .redirectErrorStream(false)
            server.env.forEach { (k, v) -> pb.environment()[k] = v }
            val p = pb.start()
            proc = p
            writer = BufferedWriter(OutputStreamWriter(p.outputStream, StandardCharsets.UTF_8))
            reader = BufferedReader(InputStreamReader(p.inputStream, StandardCharsets.UTF_8))
            val hello = request(
                "initialize", buildJsonObject {
                    put("protocolVersion", "2024-11-05")
                    put("capabilities", buildJsonObject { })
                    put("clientInfo", buildJsonObject {
                        put("name", "HaoAI-PC"); put("version", "0.1")
                    })
                }
            )
            if (hello == null) { stop(); lastError = "握手没回音"; return this }
            notify("notifications/initialized", buildJsonObject { })
        }.onFailure {
            lastError = it.message ?: it.javaClass.simpleName
            stop()
        }
        return this
    }

    fun stop() {
        runCatching { writer?.close() }
        runCatching { reader?.close() }
        runCatching { proc?.destroy() }
        writer = null; reader = null; proc = null
    }

    data class ToolInfo(val name: String, val desc: String, val schema: JsonObject)

    fun listTools(): List<ToolInfo> = runCatching {
        val r = request("tools/list", buildJsonObject { }) ?: return@runCatching emptyList()
        r["tools"]?.jsonArray?.mapNotNull { e ->
            val o = e.jsonObject
            val n = o["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            ToolInfo(
                n,
                o["description"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                (o["inputSchema"] ?: o["input_schema"])?.jsonObject
                    ?: buildJsonObject { put("type", "object") }
            )
        }.orEmpty()
    }.getOrDefault(emptyList())

    /** @return 文本结果与"是不是错误"。 */
    fun callTool(name: String, args: JsonObject): Pair<String, Boolean> {
        val r = request(
            "tools/call", buildJsonObject {
                put("name", name)
                put("arguments", args)
            }, CALL_MS
        ) ?: return lastError.ifBlank { "MCP 没有回音" } to true
        val err = r["isError"]?.jsonPrimitive?.content == "true"
        val text = r["content"]?.jsonArray?.joinToString("\n") { c ->
            val o = c.jsonObject
            o["text"]?.jsonPrimitive?.contentOrNull
                ?: (o["type"]?.jsonPrimitive?.contentOrNull ?: "结果") + "（非文本内容，未展开）"
        } ?: ""
        return (text.ifBlank { if (err) "工具报错但没有内容" else "(无输出)" }) to err
    }

    private fun notify(method: String, params: JsonObject) {
        write("""{"jsonrpc":"2.0","method":${quoteJson(method)},"params":$params}""")
    }

    private fun request(method: String, params: JsonObject, timeoutMs: Long = START_MS): JsonObject? {
        val id = ++seq
        write("""{"jsonrpc":"2.0","id":$id,"method":${quoteJson(method)},"params":$params}""")
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val line = runCatching { reader?.readLine() }.getOrNull()
            if (line == null) {
                lastError = "连接断了（进程退出或没起来）"
                stop(); return null
            }
            if (line.isBlank()) continue
            val o = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull() ?: continue
            if (o["id"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() != id) continue   // 别人的消息 / 通知
            if (o["error"] != null) {
                lastError = o["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.isNotBlank() } ?: "MCP 返回错误"
                return null
            }
            return o["result"]?.jsonObject
        }
        lastError = "等 MCP 回音超时（${timeoutMs / 1000}s），已断开"
        stop()
        return null
    }

    private fun write(s: String) = runCatching {
        val w = writer ?: return@runCatching
        w.write(s); w.write("\n"); w.flush()
    }

    private fun quoteJson(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    companion object {
        /**
         * 握手与列工具用短超时：设置抽屉打开时要同步问一遍每个 server 的状态，
         * 一个装死的外部进程不该把界面吊住 20 秒。真调用工具时才给长时间。
         */
        private const val START_MS = 6_000L
        private const val CALL_MS = 60_000L
    }
}

/**
 * MCP 工具的注册表：连上每个 server，把它报的工具包成引擎的 [Tool]。
 *
 * 名字统一成 `mcp_<server>_<tool>`：网关对函数名有字符集要求（`[A-Za-z0-9_-]`），
 * 而且加了前缀之后，界面上"这是外部工具"一眼能看出来，也不会和内置的重名。
 */
object Mcp {
    private val clients = LinkedHashMap<String, McpClient>()
    private var cached: List<Tool> = emptyList()
    private var loaded = false

    class McpToolImpl(
        val serverName: String,
        val remoteName: String,
        desc: String,
        schema: JsonObject,
        private val client: () -> McpClient?
    ) : Tool("mcp_${slug(serverName)}_${slug(remoteName)}".take(64), desc, schema, kind = "write",
        flag = HaoFlag.MCP_CLIENT) {
        override fun run(args: JsonObject, ctx: ToolCtx): ToolResult {
            val why = ctx.guard(
                "mcp", "$serverName/$remoteName",
                "调用外部工具 $serverName/$remoteName",
                "参数：$args", subjectIsPath = false
            )
            if (why != null) return fail(why)
            val c = client() ?: return fail("MCP server「$serverName」没在跑（看设置里的状态）")
            val (text, err) = c.callTool(remoteName, args)
            return ToolResult(text, error = err, card = "generic")
        }
    }

    /** 只留网关允许的字符，别的换成下划线。 */
    fun slug(s: String): String = s.map { if (it.isLetterOrDigit() || it == '_' || it == '-') it else '_' }
        .joinToString("").trim('_').ifBlank { "x" }

    /** 连接所有配置的 server 并生成工具表。结果缓存，[reload] 之后才重连。 */
    @Synchronized
    fun tools(): List<Tool> {
        if (loaded) return cached
        loaded = true
        cached = McpConfig.load().flatMap { s ->
            val client = McpClient(s).start()
            if (!client.running) return@flatMap emptyList()
            clients[s.name] = client
            client.listTools().map { t ->
                McpToolImpl(s.name, t.name, "[MCP:${s.name}] ${t.desc}", t.schema) { clients[s.name] }
            }
        }
        return cached
    }

    @Synchronized
    fun reload() {
        clients.values.forEach { it.stop() }
        clients.clear()
        cached = emptyList()
        loaded = false
    }

    /** 给界面看的状态：每个 server 在不在跑、有几把工具、错在哪。 */
    @Synchronized
    fun status(): List<Map<String, String>> {
        val servers = McpConfig.load()
        tools()   // 顺手把没连上的补上
        return servers.map { s ->
            val c = clients[s.name]
            mapOf(
                "name" to s.name,
                "command" to s.render(),
                "running" to (c?.running == true).toString(),
                "tools" to tools().count { t -> t is McpToolImpl && t.serverName == s.name }.toString(),
                "error" to (c?.error ?: if (c == null) "没连上（进程没起来或握手失败）" else "")
            )
        }
    }

    @Synchronized
    fun connected(name: String): McpClient? = clients[name]
}
