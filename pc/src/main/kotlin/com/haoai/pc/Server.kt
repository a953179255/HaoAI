package com.haoai.pc

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.OutputStream
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * 本地 HTTP 服务 + SSE。
 *
 * 只绑 127.0.0.1：PC 端"权威源"的角色不等于把 agent 开放到局域网。
 * 跨端（配对 token / Tailscale / 审批做成持久对象）是方案里 Phase 3 的事，
 * 这里先把"自己电脑上真能用"做出来。
 */
class WebServer(settings: PcSettings, port: Int) {

    @Volatile
    private var settings = settings


    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
    private val seq = AtomicInteger()
    private val pending = ConcurrentHashMap<String, java.util.concurrent.CompletableFuture<String>>()
    private val pendingRule = ConcurrentHashMap<String, Pair<String, String>>()
    private val subscribers: MutableList<OutputStream> = Collections.synchronizedList(mutableListOf())

    @Volatile
    private var engine: Engine? = null

    @Volatile
    private var running = false

    fun start(): Int {
        server.executor = Executors.newCachedThreadPool()
        server.createContext("/") { ex -> route(ex) }
        server.start()
        return server.address.port
    }

    fun stop() = server.stop(0)

    private fun route(ex: HttpExchange) {
        val path = ex.requestURI.path
        try {
            when (path) {
                "/", "/index.html" -> sendFile(ex, "ui/index.html", "text/html; charset=utf-8")
                "/api/events" -> sse(ex)
                "/api/state" -> send(ex, 200, stateJson(), "application/json; charset=utf-8")
                "/api/task" -> task(ex)
                "/api/stop" -> stopTask(ex)
                "/api/decide" -> decide(ex)
                "/api/mode" -> mode(ex)
                "/api/new" -> {
                    engine = null
                    send(ex, 200, """{"ok":true}""", "application/json; charset=utf-8")
                }
                "/api/sessions" -> send(ex, 200, sessionsJson(), "application/json; charset=utf-8")
                "/api/open" -> openSession(ex)
                "/api/settings" ->
                    if (ex.requestMethod == "POST") saveSettings(ex)
                    else send(ex, 200, settingsJson(), "application/json; charset=utf-8")
                else -> send(ex, 404, "not found", "text/plain; charset=utf-8")
            }
        } catch (e: Exception) {
            Env.log("web", "$path 处理失败：${e.message}")
            runCatching { send(ex, 500, "server error: ${e.message}", "text/plain; charset=utf-8") }
        } finally {
            if (path != "/api/events") runCatching { ex.close() }
        }
    }

    private fun sse(ex: HttpExchange) {
        ex.responseHeaders.add("Content-Type", "text/event-stream; charset=utf-8")
        ex.responseHeaders.add("Cache-Control", "no-cache")
        ex.sendResponseHeaders(200, 0)
        val os = ex.responseBody
        subscribers.add(os)
        runCatching { write(os, "hello", "{}") }
        while (true) {
            Thread.sleep(15_000)
            try {
                write(os, "ping", "{}")
            } catch (e: Exception) {
                break
            }
        }
        subscribers.remove(os)
    }

    private fun write(os: OutputStream, event: String, data: String) {
        os.write("event: $event\ndata: $data\n\n".toByteArray(StandardCharsets.UTF_8))
        os.flush()
    }

    private fun publish(event: String, data: String) {
        val dead = mutableListOf<OutputStream>()
        synchronized(subscribers) {
            subscribers.forEach { os -> runCatching { write(os, event, data) }.onFailure { dead += os } }
        }
        dead.forEach { subscribers.remove(it) }
    }

    private fun stateJson(): String {
        val e = engine
        val sb = StringBuilder()
        sb.append('{')
        sb.append("\"mode\":\"").append(esc(e?.session?.mode ?: settings.permissionMode)).append("\",")
        sb.append("\"workspace\":\"").append(esc(settings.workspaceFile().absolutePath)).append("\",")
        sb.append("\"model\":\"").append(esc(settings.model)).append("\",")
        sb.append("\"title\":\"").append(esc(e?.session?.title?.get() ?: "新会话")).append("\",")
        sb.append("\"running\":").append(running).append(",")
        sb.append("\"todos\":[")
        e?.session?.todos?.forEachIndexed { i, t ->
            if (i > 0) sb.append(',')
            sb.append("{\"text\":").append(quote(t.text)).append(",\"status\":\"").append(t.status).append("\"}")
        }
        sb.append("],\"usage\":{\"prompt\":").append(e?.totalPrompt ?: 0L)
        sb.append(",\"completion\":").append(e?.totalCompletion ?: 0L).append("},")
        sb.append("\"messages\":[")
        e?.messages()?.forEachIndexed { i, m ->
            if (i > 0) sb.append(',')
            sb.append("{\"role\":\"").append(m.role).append("\",\"content\":").append(quote(m.content ?: ""))
                .append(",\"name\":").append(quote(m.name.ifBlank { m.calls.firstOrNull()?.name ?: "" }))
                .append('}')
        }
        sb.append("]}")
        return sb.toString()
    }

    private fun bodyOf(ex: HttpExchange): String =
        ex.requestBody.readBytes().toString(StandardCharsets.UTF_8)

    private fun field(ex: HttpExchange, key: String): String = runCatching {
        Json.parseToJsonElement(bodyOf(ex)).jsonObject[key]?.jsonPrimitive?.content ?: ""
    }.getOrDefault("")

    private fun task(ex: HttpExchange) {
        val text = field(ex, "text")
        send(ex, 200, """{"ok":true}""", "application/json; charset=utf-8")
        if (text.isBlank()) return
        publish("user", quote(text))
        Thread {
            synchronized(this) {
                running = true
                publish("run", """{"running":true}""")
                try {
                    ensureEngine().submit(text)
                } catch (e: Exception) {
                    publish("err", quote("回合异常：${e.message ?: e.javaClass.simpleName}"))
                } finally {
                    running = false
                    publish("run", """{"running":false}""")
                }
            }
        }.apply { isDaemon = true; name = "haoai-run"; start() }
    }

    /**
     * `POST /api/stop` —— 停止当前任务。
     *
     * 两件必须一起做的事：
     * 1. 给引擎置位（它只在回合边界与工具边界看这个标志，所以是"收尾式"停止，
     *    不是掐断 HTTP 流 —— 理由见 [Engine.stopRequested] 的注释）；
     * 2. **把挂着的审批与提问一次性判掉**。漏了这条，按了停止引擎还卡在
     *    `fut.get(300s)` 上等一个不会来的点击，"停止"按钮就成了它自己要中止的那件事的受害者。
     *
     * 这里刻意**不去抢** `task()` 那把锁：抢了就等于"要停止必须先等本轮跑完"。
     */
    private fun stopTask(ex: HttpExchange) {
        val waiters = pending.entries.toList()
        var approvals = 0
        waiters.forEach { (id, fut) ->
            // 审批的 id 以 a 开头、提问以 q 开头：两者"被中止"的语义不一样，
            // 审批给 deny（fail-closed），提问给空串（模型会看到"用户没回答"）。
            if (id.startsWith("q")) fut.complete("") else { fut.complete("deny"); approvals++ }
        }
        engine?.requestStop()
        publish(
            "notice", quote(
                if (running || waiters.isNotEmpty())
                    "已请求停止${if (approvals > 0) "（顺手拒掉 $approvals 个待确认）" else ""}，正在收尾…"
                else "当前没有正在跑的任务。"
            )
        )
        send(ex, 200, """{"ok":true,"pending":${waiters.size}}""", "application/json; charset=utf-8")
    }

    private fun ensureEngine(): Engine = engine ?: synchronized(this) {
        engine ?: Sessions.create(
            settings, settings.workspaceFile(), webGate(),
            emit = { ev -> forward(ev) }
        ).also { engine = it }
    }

    private fun forward(ev: Ev) {
        when (ev) {
            is Ev.TextDelta -> publish("delta", quote(ev.s))
            is Ev.TextDone -> publish("answer", quote(ev.s))
            is Ev.ToolStart -> publish(
                "tool",
                """{"id":${quote(ev.id)},"name":${quote(ev.name)},"brief":${quote(ev.brief)},"state":"run"}"""
            )
            is Ev.ToolEnd -> publish(
                "tool",
                """{"id":${quote(ev.id)},"name":${quote(ev.name)},"ok":${ev.ok},"card":${quote(ev.card)},"out":${quote(ev.out)}}"""
            )
            is Ev.Todo -> publish("todo", """{"items":${ev.items.joinToString(",", "[", "]") { quote(it) }}}""")
            is Ev.Usage -> publish("usage", """{"prompt":${ev.prompt},"completion":${ev.completion},"turns":${ev.turns}}""")
            is Ev.Notice -> publish("notice", quote(ev.s))
            is Ev.Err -> publish("err", quote(ev.s))
            else -> Unit
        }
    }

    private fun webGate(): Gate = object : Gate {
        override fun approve(title: String, detail: String, kind: String): Boolean =
            approveRule(title, detail, kind, "", "*")

        override fun approveRule(
            title: String, detail: String, kind: String, tool: String, pattern: String
        ): Boolean {
            val id = "a${seq.incrementAndGet()}"
            val fut = java.util.concurrent.CompletableFuture<String>()
            pending[id] = fut
            if (tool.isNotBlank()) pendingRule[id] = tool to pattern
            publish(
                "approval",
                """{"id":"$id","title":${quote(title)},"detail":${quote(detail)},"kind":"$kind",""" +
                    """"tool":${quote(tool)},"pattern":${quote(pattern)}}"""
            )
            val ans = try {
                fut.get(300, TimeUnit.SECONDS)
            } catch (e: TimeoutException) {
                publish("notice", quote("300 秒无人应答，按拒绝处理"))
                "deny"
            } catch (e: Exception) {
                "deny"
            } finally {
                pending.remove(id)
                pendingRule.remove(id)
            }
            // allow_rule：把这条规则永久写进当前工作区的规则表（S2）
            if (ans == "allow_rule" && tool.isNotBlank()) {
                Policies.get().add(settings.workspaceFile(), Rule(tool, pattern, Decision.ALLOW))
                publish("notice", quote("已记住规则：$tool($pattern)"))
            }
            return ans == "allow_once" || ans == "allow_session" || ans == "allow_rule"
        }

        override fun ask(question: String, options: List<String>): String {
            val id = "q${seq.incrementAndGet()}"
            val fut = java.util.concurrent.CompletableFuture<String>()
            pending[id] = fut
            publish(
                "ask",
                """{"id":"$id","question":${quote(question)},"options":${options.joinToString(",", "[", "]") { quote(it) }}}"""
            )
            return try {
                fut.get(900, TimeUnit.SECONDS)
            } catch (e: Exception) {
                ""
            } finally {
                pending.remove(id)
            }
        }
    }

    private fun decide(ex: HttpExchange) {
        val id = field(ex, "id")
        val decision = field(ex, "decision")
        val answer = field(ex, "answer")
        val fut = pending[id]
        if (fut != null) fut.complete(if (answer.isNotBlank()) answer else decision)
        if (decision == "allow_session") {
            engine?.session?.mode = "auto"
            publish("mode", """{"mode":"auto"}""")
        }
        send(ex, 200, """{"ok":true}""", "application/json; charset=utf-8")
    }

    private fun mode(ex: HttpExchange) {
        val m = field(ex, "mode")
        if (m in listOf("plan", "ask", "auto")) {
            ensureEngine().session.mode = m
            publish("mode", """{"mode":"$m"}""")
        }
        send(ex, 200, """{"ok":true,"mode":"${engine?.session?.mode ?: m}"}""", "application/json; charset=utf-8")
    }

    /** 旧会话列表：手机端侧栏有 48 条会话，PC 端少了这个就只能一次性对话。 */
    private fun sessionsJson(): String {
        val cur = engine?.session?.id
        return SessionIndex.list().joinToString(",", "[", "]") { m ->
            """{"id":${quote(m.id)},"title":${quote(m.title)},"workspace":${quote(m.workspace)},""" +
                """"mode":"${m.mode}","updated":${m.updated},"messages":${m.messages},""" +
                """"prompt":${m.prompt},"completion":${m.completion},"current":${m.id == cur}}"""
        }
    }

    private fun openSession(ex: HttpExchange) {
        val id = field(ex, "id")
        val meta = SessionIndex.list(200).firstOrNull { it.id == id }
        if (meta == null) {
            send(ex, 404, """{"ok":false,"error":"没有这个会话"}""", "application/json; charset=utf-8")
            return
        }
        synchronized(this) {
            // 必须用**原来的 id** 构造 Session：Engine 的 init 按 pc-<id>.json 回读历史，
            // 走 Sessions.create 会发一个新 id，于是"打开旧会话"变成"开一个空会话"。
            val s = SessionIndex.restore(meta, settings.workspaceFile())
            engine = Engine(s, settings, builtinTools(), webGate(), emit = { ev -> forward(ev) })
        }
        send(ex, 200, """{"ok":true,"id":${quote(id)}}""", "application/json; charset=utf-8")
        publish("opened", """{"id":${quote(id)},"title":${quote(meta.title)},"mode":${quote(meta.mode)}}""")
    }

    private fun settingsJson(): String {
        val st = settings
        val flags = HaoFlag.entries.joinToString(",", "[", "]") { f ->
            """{"key":${quote(f.key)},"title":${quote(f.title)},"what":${quote(f.what)},""" +
                """"on":${HaoFlag.enabled(f, st.flags)}}"""
        }
        val rules = Policies.get().rules(st.workspaceFile()).joinToString(",", "[", "]") { r ->
            """{"text":${quote(r.render())}}"""
        }
        val key = System.getenv("HAOAI_API_KEY")?.takeIf { it.isNotBlank() }
            ?: runCatching { Env.apiKeyFile.takeIf { it.isFile }?.readText()?.trim() }.getOrNull()

        return """{"provider":${quote(st.providerName)},"baseUrl":${quote(st.baseUrl)},""" +
            """"model":${quote(st.model)},"mode":${quote(st.permissionMode)},""" +
            """"workspace":${quote(st.workspaceFile().absolutePath)},""" +
            """"hasKey":${key != null},"keyHint":${quote(key?.take(6) ?: "")},""" +
            """"flags":$flags,"rules":$rules}"""
    }

    private fun saveSettings(ex: HttpExchange) {
        val body = runCatching { Json.parseToJsonElement(bodyOf(ex)).jsonObject }.getOrNull()
        if (body == null) {
            send(ex, 400, """{"ok":false}""", "application/json; charset=utf-8"); return
        }
        var n = settings
        body["model"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }?.let { n = n.copy(model = it) }
        body["baseUrl"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }?.let { n = n.copy(baseUrl = it) }
        body["mode"]?.jsonPrimitive?.content?.takeIf { it in listOf("plan", "ask", "auto") }?.let {
            n = n.copy(permissionMode = it)
            engine?.session?.mode = it
        }
        body["workspace"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }?.let { n = n.copy(workspace = it) }
        body["flags"]?.jsonObject?.let { fo ->
            val merged = n.flags.toMutableMap()
            fo.forEach { (k, v) -> merged[k] = (v.jsonPrimitive.content == "true") }
            n = n.copy(flags = HaoFlag.compactOverrides(merged))
        }
        PcSettings.save(n)
        settings = n
        publish("mode", """{"mode":"${n.permissionMode}"}""")
        send(ex, 200, """{"ok":true}""", "application/json; charset=utf-8")
    }

    private fun sendFile(ex: HttpExchange, resource: String, type: String) {
        val bytes = javaClass.classLoader.getResourceAsStream(resource)?.readBytes()
            ?: "资源缺失：$resource".toByteArray(StandardCharsets.UTF_8)
        send(ex, 200, String(bytes, StandardCharsets.UTF_8), type)
    }

    private fun send(ex: HttpExchange, code: Int, body: String, type: String) {
        val b = body.toByteArray(StandardCharsets.UTF_8)
        ex.responseHeaders.add("Content-Type", type)
        ex.sendResponseHeaders(code, b.size.toLong())
        ex.responseBody.use { it.write(b) }
    }

    private fun esc(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")
        .replace("\n", "\\n").replace("\r", "").replace("\t", "    ")

    private fun quote(s: String): String = "\"" + esc(s) + "\""
}
