package com.haoai.pc

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.buildJsonObject
import java.io.File
import java.io.OutputStream
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.Base64
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

    /** 待决请求：事件名、原始负载、属于哪条会话。见 [stateJson] 里的 pending 字段。 */
    private data class Waiter(val ev: String, val payload: String, val sid: String)

    private val pendingPayload = ConcurrentHashMap<String, Waiter>()
    private val subscribers: MutableList<OutputStream> = Collections.synchronizedList(mutableListOf())

    /**
     * 一条被托管的会话：引擎 + 它此刻是否在跑。
     *
     * `running` 必须是**每条会话一个**，不能是服务级的一把旗：
     * 上一版服务级旗子加"切会话就 409"的组合虽然挡住了孤儿任务，代价是一次只能跑一件事，
     * 而这恰恰是参考实现们（codex 的多 agent、opencode 的多 session）都不接受的限制。
     */
    private class Managed(val engine: Engine) {
        @Volatile
        var running = false
    }

    private val sessions = ConcurrentHashMap<String, Managed>()

    /** 前端 `/` 命令面板里的内置项：自定义技能不许占用这些名字（两份实现靠这份对齐）。 */
    private val BUILTIN_CMDS = setOf(
        "plan", "ask", "auto", "new", "model", "stop", "clear", "export", "theme", "status", "help"
    )

    /** 最近使用顺序（新的在前），用来在没指定 sid 时挑"当前会话"。 */
    private val order = Collections.synchronizedList(mutableListOf<String>())

    /** 同时最多跑几条。没有上限的话，用户可以一路开下去把网关和磁盘都刷爆。 */
    private val maxRunning = 4

    /**
     * 常驻内存的会话条数上限。
     *
     * 每条会话在内存里是一个引擎 + 它的全部历史，开二十条不去管就是几百 MB。
     * 超出时把**最久没碰且没在跑**的请出去：历史本来就落盘在 `sessions/pc-<id>.json`，
     * 下次 `/api/open` 会按原 id 重建，用户看不出差别。
     */
    private val maxResident = 12

    private fun liveCount(): Int = synchronized(order) { order.count { sessions[it]?.running == true } }

    private fun touch(id: String) {
        synchronized(order) {
            order.remove(id)
            order.add(0, id)
            while (order.size > maxResident) {
                val victim = order.lastOrNull { sessions[it]?.running != true } ?: break
                sessions.remove(victim)
                order.remove(victim)
            }
        }
        // computeIfAbsent 而不是 putIfAbsent(Managed(engineFor(id)))：后者每次都会先构造一个新引擎
        // 再把结果丢掉，于是同一个会话文件可能同时被两个引擎读写（一个在跑、一个刚被丢弃）。
        sessions.computeIfAbsent(id) { Managed(engineFor(id)) }
    }

    fun start(): Int {
        // 重启后要接上"上次用的那条会话"，而不是每次开一个空白新会话、
        // 把历史留在磁盘上吃灰（手机端与所有参考实现都是"继续上次"）。
        runCatching { SessionIndex.list(5).firstOrNull()?.let { touch(it.id) } }
            .onFailure { Env.log("web", "恢复上次会话失败：${it.message}") }
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
                "/md.js" -> sendFile(ex, "ui/md.js", "text/javascript; charset=utf-8")
                "/api/events" -> sse(ex)
                "/api/state" -> state(ex)
                "/api/task" -> task(ex)
                "/api/stop" -> stopTask(ex)
                "/api/decide" -> decide(ex)
                "/api/mode" -> mode(ex)
                "/api/new" -> newSession(ex)
                "/api/sessions" -> send(ex, 200, sessionsJson(queryOf(ex, "q")), "application/json; charset=utf-8")
                "/api/open" -> openSession(ex)
                "/api/rename" -> renameSession(ex)
                "/api/edit" -> editMessage(ex)
                "/api/cut" -> cutMessage(ex)
                "/api/regenerate" -> regenerate(ex)
                "/api/rollback" -> rollback(ex)
                "/api/attach" -> attach(ex)
                "/api/models" -> models(ex)
                "/api/rule" -> ruleEdit(ex)
                "/api/memory" -> memory(ex)
                "/api/skills" -> skills(ex)
                "/api/mcp" -> mcp(ex)
                "/api/files" -> files(ex)
                "/api/file" -> fileOne(ex)
                "/api/export" -> exportSession(ex)
                "/api/delete" -> deleteSession(ex)
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
        runCatching { write(os, "hello", "{}", null) }
        while (true) {
            Thread.sleep(15_000)
            try {
                write(os, "ping", "{}", null)
            } catch (e: Exception) {
                break
            }
        }
        subscribers.remove(os)
    }

    private fun write(os: OutputStream, event: String, data: String, sid: String?) {
        // sid 走 SSE 的 id: 字段 —— 浏览器把它挂到 MessageEvent.lastEventId 上，
        // 于是"这条事件属于哪个会话"不用改任何一个已有负载的格式就能带出去。
        val head = if (sid == null) "" else "id: $sid\n"
        os.write((head + "event: $event\ndata: $data\n\n").toByteArray(StandardCharsets.UTF_8))
        os.flush()
    }

    private fun publish(event: String, data: String, sid: String? = null) {
        val dead = mutableListOf<OutputStream>()
        synchronized(subscribers) {
            subscribers.forEach { os -> runCatching { write(os, event, data, sid) }.onFailure { dead += os } }
        }
        dead.forEach { subscribers.remove(it) }
    }

    /** 当前会话 = 最近使用列表的头一个。 */
    private fun currentId(): String? = synchronized(order) { order.firstOrNull { sessions.containsKey(it) } }

    private fun pick(sid: String): String = sid.ifBlank { currentId() ?: "" }

    /**
     * 给某条会话造（或复用）引擎。
     *
     * 磁盘上有历史就走 [SessionIndex.restore]（**必须用原 id**：Engine 的 init 按
     * `pc-<id>.json` 回读历史，换 id 就变成打开一条空会话）。
     */
    private fun engineFor(id: String): Engine {
        val meta = SessionIndex.list(400).firstOrNull { it.id == id }
        val session = if (meta != null) {
            SessionIndex.restore(meta, settings.workspaceFile())
        } else {
            Session(id, settings.workspaceFile())
        }
        session.mode = meta?.mode ?: settings.permissionMode
        // 闸口要能拿到"这条会话的引擎"，但引擎构造时还握不住自己的引用 —— 拿个可变槽位接上
        var made: Engine? = null
        val e = Engine(session, settings, allTools(), webGate(id) { made }, emit = { ev -> forward(id, ev) })
        made = e
        return e
    }

    private fun stateJson(sid: String = ""): String {
        val id = pick(sid)
        val managed = id.takeIf { it.isNotBlank() }?.let { sessions[it] }
        val e = managed?.engine
        val sb = StringBuilder()
        sb.append('{')
        sb.append("\"mode\":\"").append(esc(e?.session?.mode ?: settings.permissionMode)).append("\",")
        // 工作区要报**这条会话自己的**，不是全局设置里的那个：
        // 每条会话属于它创建时的那个工作区（每个 agent 都是这个语义），
        // 改了全局工作区之后老会话仍在旧目录里干活，头部却显示新路径就是骗人。
        sb.append("\"workspace\":\"").append(esc(e?.session?.workspace?.absolutePath
            ?: settings.workspaceFile().absolutePath)).append("\",")
        sb.append("\"model\":\"").append(esc(settings.model)).append("\",")
        sb.append("\"title\":\"").append(esc(e?.session?.title?.get() ?: "新会话")).append("\",")
        sb.append("\"sessionId\":").append(quote(id)).append(",")
        sb.append("\"running\":").append(managed?.running == true).append(",")
        sb.append("\"todos\":[")
        e?.session?.todos?.forEachIndexed { i, t ->
            if (i > 0) sb.append(',')
            sb.append("{\"text\":").append(quote(t.text)).append(",\"status\":\"").append(t.status).append("\"}")
        }
        sb.append("],\"usage\":{\"prompt\":").append(e?.totalPrompt ?: 0L)
        sb.append(",\"completion\":").append(e?.totalCompletion ?: 0L).append("},")
        // 上下文占用：分子是"这次真的发出去多少字"，分母是设置里可改的窗口。
        // 拆成几行是因为"快满了"这件事有两种完全相反的成因（历史太长 vs 工具说明太长）。
        sb.append("\"context\":{\"chars\":").append(e?.contextChars() ?: 0)
        sb.append(",\"window\":").append(settings.contextChars)
        sb.append(",\"parts\":[")
        e?.contextBreakdown()?.forEachIndexed { i, (label, chars) ->
            if (i > 0) sb.append(',')
            sb.append('[').append(quote(label)).append(',').append(chars).append(']')
        }
        sb.append("]},")
        /**
         * 挂着没答的审批/提问全交出去（不是只交一条）。
         *
         * 它们本来只通过 SSE 推一次：页面一刷新引擎还卡在 `fut.get(300s)` 上等一个
         * 不会再出现的按钮，表现是"agent 莫名卡死"，最后超时自动拒掉。
         * 原来这里只回第一条，因为弹窗是单实例、堆两个会互相盖掉。现在审批是消息流里的
         * 内联卡，一条会话摆一张，"只回一条"就变成了**另一条没人管** —— 所以全交。
         */
        sb.append("\"pending\":[")
        pendingPayload.values.filter { it.sid == id }.forEachIndexed { i, w ->
            if (i > 0) sb.append(',')
            sb.append("{\"ev\":").append(quote(w.ev)).append(",\"data\":").append(w.payload).append('}')
        }
        sb.append("],")
        sb.append("\"messages\":[")
        e?.messages()?.forEachIndexed { i, m ->
            if (i > 0) sb.append(',')
            sb.append("{\"role\":\"").append(m.role).append("\",\"content\":").append(quote(m.content ?: ""))
                .append(",\"name\":").append(quote(m.name.ifBlank { m.calls.firstOrNull()?.name ?: "" }))
                /*
                 * index 是"编辑重发 / 重新生成"的坐标。没有它，前端只能自己数可见消息，
                 * 而它数出来的下标和引擎历史的下标不是一回事 —— 压缩会把前面若干条
                 * 折进摘要，于是"改第三句"改到别的句子上。
                 */
                .append(",\"index\":").append(i)
                .append(",\"reasoning\":").append(quote(m.reasoning ?: ""))
                .append(",\"diff\":").append(quote(m.diff))
                .append(",\"note\":").append(quote(m.note))
                .append(",\"sub\":").append(quote(m.sub))
                .append(",\"pt\":").append(m.pt).append(",\"ct\":").append(m.ct).append(",\"ms\":").append(m.ms)
                .append(",\"calls\":").append(m.calls.joinToString(",", "[", "]") { c ->
                    """{"name":${quote(c.name)},"args":${quote(c.args)}}"""
                })
                .append('}')
        }
        sb.append("]}")
        return sb.toString()
    }

    private fun bodyOf(ex: HttpExchange): String =
        ex.requestBody.readBytes().toString(StandardCharsets.UTF_8)

    /**
     * 一次请求的 JSON 体。
     *
     * **必须在处理开头一次性读出来**：`HttpExchange.requestBody` 是流，读第二次就是空的。
     * 之前每个字段各调一次 `field(ex, …)`，于是只有第一个字段拿得到值 ——
     * `decide` 要读 id/decision/answer 三个，结果 `decision` 永远是空串，
     * "允许一次"在服务端看来是"没给决定"，直接落进拒绝分支；`rename` 同理。
     * 这类 bug 单测抓不到（测的是 SessionIndex/引擎，不是 HTTP 层），
     * 只能靠"两个字段都要用到"的接口在真机上走一遍。
     */
    private class Body(ex: HttpExchange) {
        private val obj: JsonObject = runCatching {
            Json.parseToJsonElement(
                // 不借道 bodyOf：Body 是嵌套类（非 inner），拿不到外层方法
                ex.requestBody.readBytes().toString(StandardCharsets.UTF_8)
            ).jsonObject
        }.getOrElse { buildJsonObject { } }

        fun str(key: String): String = obj[key]?.jsonPrimitive?.contentOrNull ?: ""
    }

    /**
     * 起一轮。发任务、编辑重发、重新生成三条入口共用这一份实现。
     *
     * 共用不是为了少写代码，是为了"同一条会话只跑一个""最多并行 4 条"
     * "清停止旗必须和置 running 在同一把锁里"这三条约束只有一处能写错 ——
     * 三条入口各写一遍的话，最先漏的永远是后加的那两条。
     *
     * 返回 sid to null 表示起来了；sid to "原因" 表示被拒。
     */
    private fun startRun(wantSid: String, text: String, cutTo: Int? = null): Pair<String, String?> {
        val sid = wantSid.ifBlank { currentId() ?: newSessionId() }
        val managed = synchronized(this) {
            val m = sessions[sid] ?: Managed(engineFor(sid)).also { sessions[sid] = it }
            touch(sid)
            when {
                m.running || liveCount() >= maxRunning -> null
                // 清停止旗要和置 running 在同一把锁里：见 Engine.beginRun 的注释
                else -> { m.engine.beginRun(); m.running = true; m }
            }
        }
        if (managed == null) {
            val why = if (sessions[sid]?.running == true) "这条会话正在跑，先按停止再发新任务"
            else "已经有 $maxRunning 条任务在跑，先停掉一个"
            return sid to why
        }
        /*
         * 截断放在"置了 running 之后"做：反过来会有一个小窗口 ——
         * 前一个请求刚读到历史，这条已经开始改它，两条任务同时往一段历史上写。
         * 截不动（比如前端给的下标不是用户消息）就把 running 还回去，别留一个跑着的空壳。
         */
        // startRun 只服务"改这一句重跑"（编辑重发 / 重新生成）：那一句自己也要让位给新的一句
        if (cutTo != null && !managed.engine.cutTo(cutTo, keepAt = false)) {
            managed.running = false
            return sid to "只能改「你说过的那一句话」，改不了模型的回复"
        }
        publish("user", quote(text), sid)
        val e = managed.engine
        Thread {
            publish("run", """{"running":true}""", sid)
            try {
                e.submit(text)
            } catch (err: Exception) {
                publish("err", quote("回合异常：${err.message ?: err.javaClass.simpleName}"), sid)
            } finally {
                managed.running = false
                publish("run", """{"running":false}""", sid)
                publish("sessions", "{}", sid)
            }
        }.apply { isDaemon = true; name = "haoai-run-$sid"; start() }
        return sid to null
    }

    private fun task(ex: HttpExchange) {
        val b = Body(ex)
        val text = b.str("text")
        val wantSid = b.str("sid")
        if (text.isBlank()) {
            send(ex, 200, """{"ok":false}""", "application/json; charset=utf-8"); return
        }
        /**
         * 一条会话同时只跑一个任务；不同会话之间可以并行（上限 [maxRunning]）。
         *
         * 同一条会话里排队是不行的：第二个任务会写进同一段历史，
         * 两条任务的工具卡串在一起，事后看不出哪条输出属于哪条。
         */
        val (sid, err) = startRun(wantSid, text)
        if (err != null) {
            send(ex, 409, """{"ok":false,"error":${quote(err)}}""", "application/json; charset=utf-8")
            return
        }
        send(ex, 200, """{"ok":true,"sid":${quote(sid)}}""", "application/json; charset=utf-8")
    }

    /**
     * `POST /api/edit` {sid,index,text} —— 改一句已经说过的话并重跑。
     *
     * 语义与移动端一致：**替换**那条用户消息，并把它之后的一切都丢掉。
     * 留着后半段不行：那些工具结果与新问题无关，模型会照着旧结论接着往下说。
     */
    private fun editMessage(ex: HttpExchange) {
        val b = Body(ex)
        val sid = pick(b.str("sid"))
        val index = b.str("index").toIntOrNull() ?: -1
        val text = b.str("text")
        if (text.isBlank() || sid.isBlank()) {
            send(ex, 200, """{"ok":false,"error":"缺 text 或 sid"}""",
                "application/json; charset=utf-8"); return
        }
        val (got, err) = startRun(sid, text, cutTo = index)
        if (err != null) {
            send(ex, 409, """{"ok":false,"error":${quote(err)}}""", "application/json; charset=utf-8"); return
        }
        send(ex, 200, """{"ok":true,"sid":${quote(got)},"cut":$index}""", "application/json; charset=utf-8")
    }

    /** `POST /api/cut` {sid,index} —— 丢掉某句之后的所有内容（"就到这里，别往下接了"）。 */
    private fun cutMessage(ex: HttpExchange) {
        val b = Body(ex)
        val sid = pick(b.str("sid"))
        val index = b.str("index").toIntOrNull() ?: -1
        val m = sessions[sid]
        if (m == null) {
            send(ex, 404, """{"ok":false,"error":"没有这条会话"}""", "application/json; charset=utf-8"); return
        }
        if (m.running) {
            send(ex, 409, """{"ok":false,"error":"这条会话正在跑，先停止再改历史"}""",
                "application/json; charset=utf-8"); return
        }
        // keep=0 是 /clear 用法（连第一条一起丢）；默认留着这一句，那是"删到这里"的语义
        if (!m.engine.cutTo(index, keepAt = b.str("keep") != "0")) {
            send(ex, 200, """{"ok":false,"error":"只能删「你说过的那一句话」之后的内容"}""",
                "application/json; charset=utf-8"); return
        }
        send(ex, 200, """{"ok":true,"cut":$index}""", "application/json; charset=utf-8")
        publish("sessions", "{}", sid)
    }

    /** `POST /api/regenerate` {sid} —— 把最后一条用户消息重问一遍，换个回答。 */
    private fun regenerate(ex: HttpExchange) {
        val sid = pick(Body(ex).str("sid"))
        val e = sessions[sid]?.engine
        if (e == null) {
            send(ex, 404, """{"ok":false,"error":"没有这条会话"}""", "application/json; charset=utf-8"); return
        }
        val idx = e.lastUserIndex()
        val text = e.textAt(idx)
        if (idx < 0 || text.isNullOrBlank()) {
            send(ex, 200, """{"ok":false,"error":"这条会话里还没有可重问的一句话"}""",
                "application/json; charset=utf-8"); return
        }
        val (got, err) = startRun(sid, text, cutTo = idx)
        if (err != null) {
            send(ex, 409, """{"ok":false,"error":${quote(err)}}""", "application/json; charset=utf-8"); return
        }
        send(ex, 200, """{"ok":true,"sid":${quote(got)},"index":$idx}""", "application/json; charset=utf-8")
    }

    /**
     * `POST /api/rollback` {sid,path} —— 把一个文件退回最近一份快照。
     *
     * 是"回滚这一个文件"，不是"撤销整个任务"：agent 一次跑十几步，
     * 用户想撤的往往就是刚才那一个文件，那就给他一个精确、看得见时间的动作。
     */
    private fun rollback(ex: HttpExchange) {
        val b = Body(ex)
        val sid = pick(b.str("sid"))
        val rel = b.str("path")
        val ws = sessions[sid]?.engine?.session?.workspace ?: settings.workspaceFile()
        if (rel.isBlank()) {
            send(ex, 200, """{"ok":false,"error":"没说要回滚哪个文件"}""",
                "application/json; charset=utf-8"); return
        }
        val at = Snapshots.restore(ws, rel)
        if (at == null) {
            send(ex, 404, """{"ok":false,"error":"没有这个文件的快照（写之前没留底，或快照开关当时关着）"}""",
                "application/json; charset=utf-8")
            return
        }
        send(ex, 200, """{"ok":true,"restored":${quote(at)}}""", "application/json; charset=utf-8")
        publish("notice", quote("已把 $rel 退回快照（$at）"), sid)
    }

    /**
     * `POST /api/attach` —— 把浏览器里选的文件落到工作区的 `.haoai-attach/`，回相对路径。
     *
     * 为什么不直接把内容塞进消息：模型要的是"一个能读的路径"。文本还好办，
     * 图片/二进制进文本历史只会变成乱码，而落到盘上之后 read、grep、
     * 视觉输入走的是同一条路。
     *
     * 文件名一律自己拼：清洗掉所有路径分隔符再加时间戳。**不能把用户给的名字交给
     * 文件系统** —— `..\..\x` 这种名字会把文件写到工作区外面去。
     */
    private fun attach(ex: HttpExchange) {
        val b = Body(ex)
        val sid = pick(b.str("sid"))
        val ws = sessions[sid]?.engine?.session?.workspace ?: settings.workspaceFile()
        val name = b.str("name").replace(Regex("[^A-Za-z0-9._\\-\\u4e00-\\u9fa5 ]"), "_").take(80)
        val data = b.str("data")
        if (name.isBlank() || data.isBlank()) {
            send(ex, 200, """{"ok":false,"error":"缺 name 或 data"}""",
                "application/json; charset=utf-8"); return
        }
        if (data.length > 12_000_000) {
            send(ex, 200, """{"ok":false,"error":"附件太大（上限约 9 MB）"}""",
                "application/json; charset=utf-8"); return
        }
        val bytes = runCatching { Base64.getDecoder().decode(data) }.getOrNull()
        if (bytes == null) {
            send(ex, 200, """{"ok":false,"error":"附件没读全（base64 解不开）"}""",
                "application/json; charset=utf-8"); return
        }
        val rel = ".haoai-attach/" + System.currentTimeMillis() + "-" + name
        val out = File(ws, rel)
        try {
            out.parentFile?.mkdirs()
            out.writeBytes(bytes)
        } catch (e: Exception) {
            send(ex, 200, """{"ok":false,"error":${quote("存不下：" + (e.message ?: e.javaClass.simpleName))}}""",
                "application/json; charset=utf-8"); return
        }
        send(ex, 200, """{"ok":true,"path":${quote(rel)},"bytes":${bytes.size}}""",
            "application/json; charset=utf-8")
    }

    /**
     * `GET /api/files?sid=&path=` —— 工作区文件列表，给右栏「产出」页签下的浏览区。
     *
     * 只许在工作区里面走：算完 canonical 之后不在工作区内的，一律退回根目录。
     * 这个服务只绑 127.0.0.1，但浏览器里**任何**页面都能对本机端口发请求，
     * 所以"路径不能逃出工作区"必须在服务端守住，不能指望前端不传 `..`。
     */
    private fun files(ex: HttpExchange) {
        val sid = pick(querySid(ex))
        val ws = (sessions[sid]?.engine?.session?.workspace ?: settings.workspaceFile()).canonicalFile
        val dir = runCatching { File(ws, queryOf(ex, "path")).canonicalFile }.getOrDefault(ws)
        val root = if (dir.path.startsWith(ws.path) && dir.isDirectory) dir else ws
        val list = root.listFiles()
            ?.sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })
            ?.filter { it.name != ".git" && !it.name.startsWith("node_modules") }?.take(300)
            ?: emptyList()
        val shown = if (root == ws) "" else root.relativeToOrSelf(ws).path.replace('\\', '/')
        val sb = StringBuilder("""{"path":${quote(shown)},"entries":[""")
        list.forEachIndexed { i, f ->
            if (i > 0) sb.append(',')
            sb.append("""{"n":${quote(f.name)},"d":${f.isDirectory},"s":${if (f.isDirectory) 0 else f.length()}}""")
        }
        sb.append("]}")
        send(ex, 200, sb.toString(), "application/json; charset=utf-8")
    }

    /** `GET /api/file?sid=&path=` —— 看一个文本文件的前 200 KB（二进制只报大小）。 */
    private fun fileOne(ex: HttpExchange) {
        val sid = pick(querySid(ex))
        val ws = (sessions[sid]?.engine?.session?.workspace ?: settings.workspaceFile()).canonicalFile
        val rel = queryOf(ex, "path")
        val f = runCatching { File(ws, rel).canonicalFile }.getOrNull()
        if (f == null || !f.path.startsWith(ws.path) || !f.isFile) {
            send(ex, 404, """{"ok":false,"error":"没有这个文件（或它在工作区外面）"}""",
                "application/json; charset=utf-8"); return
        }
        if (f.length() > 4_000_000) {
            send(ex, 200, """{"ok":false,"error":"文件太大（${f.length() / 1024} KB），只给前 200 KB 的预览"}""",
                "application/json; charset=utf-8"); return
        }
        val text = runCatching { f.readText() }.getOrNull()
        if (text == null) {
            send(ex, 200, """{"ok":true,"binary":true,"bytes":${f.length()}}""",
                "application/json; charset=utf-8"); return
        }
        val cut = text.take(200_000)
        send(ex, 200, """{"ok":true,"text":${quote(cut)},"truncated":${text.length > cut.length}}""",
            "application/json; charset=utf-8")
    }

    /**
     * `POST /api/rule` {op:'add'|'del', text:'shell(git push*) deny', sid} —— 规则表在界面上可增删。
     *
     * 之前只能看不能改（加规则要回 CLI 跑 `haoai allow`），而"以后这类都允许"这个按钮
     * 就在审批卡上 —— 用户既然能一键写规则，就得能看见并收回来的地方。
     * 规则是**按工作区**存的，所以改的是这条会话自己的工作区，不是全局那个。
     */
    private fun ruleEdit(ex: HttpExchange) {
        val b = Body(ex)
        val sid = pick(b.str("sid"))
        val ws = sessions[sid]?.engine?.session?.workspace ?: settings.workspaceFile()
        val text = b.str("text")
        val r = Rule.parse(text)
        if (r == null) {
            send(ex, 200, """{"ok":false,"error":"看不懂这条规则。写法如：shell(git push*) deny"}""",
                "application/json; charset=utf-8"); return
        }
        val ok = if (b.str("op") == "del") Policies.get().remove(ws, r.tool, r.pattern)
        else { Policies.get().add(ws, r); true }
        if (!ok) {
            send(ex, 200, """{"ok":false,"error":"没找到这条规则（可能已经被删了）"}""",
                "application/json; charset=utf-8"); return
        }
        send(ex, 200, """{"ok":true,"rule":${quote(r.render())}}""", "application/json; charset=utf-8")
        publish("settings", """{"mode":${quote(settings.permissionMode)}}""")
    }

    /**
     * `GET/POST /api/memory` —— 项目说明文件（AGENTS.md 那一类）的读与写。
     *
     * 读写都只碰**这条会话自己的工作区**，路径由服务端算（[Memory.target]），
     * 前端不能指定写哪儿 —— 否则就成了"用一个接口往任意路径写文本"。
     */
    private fun memory(ex: HttpExchange) {
        // 请求体只能读一次：分两次 new Body(ex) 的话第二次拿到的是空对象
        val b = Body(ex)
        val get = ex.requestMethod == "GET"
        val sid = if (get) querySid(ex) else b.str("sid")
        val scope = if (get) queryOf(ex, "scope") else b.str("scope")
        val id = pick(sid)
        val ws = sessions[id]?.engine?.session?.workspace ?: settings.workspaceFile()
        // scope=global 是跨项目的个人偏好（HAOAI_HOME/MEMORY.md），默认是这条会话工作区里的说明
        val f = if (scope == "global") Env.memoryFile else Memory.target(ws)
        if (get) {
            val text = runCatching { if (f.isFile) f.readText() else "" }.getOrDefault("")
            send(ex, 200,
                """{"ok":true,"path":${quote(f.absolutePath)},"exists":${f.isFile},"text":${quote(text)}}""",
                "application/json; charset=utf-8")
            return
        }
        val text = b.str("text")
        val done = runCatching { f.parentFile?.mkdirs(); f.writeText(text); f }
        if (done.isFailure) {
            send(ex, 200,
                """{"ok":false,"error":${quote("写不下去：" + (done.exceptionOrNull()?.message ?: ""))}}""",
                "application/json; charset=utf-8")
            return
        }
        send(ex, 200, """{"ok":true,"path":${quote(f.absolutePath)},"chars":${text.length}}""",
            "application/json; charset=utf-8")
        // 发 settings 而不是往流里插一条提示：页面上的"已存 N 字"与 toast 已经说完了，
        // 而顶栏那圈占用必须重算 —— 项目说明是系统提示的一部分，改了它占用就变了。
        publish("settings", """{"mode":${quote(settings.permissionMode)}}""")
    }

    /**
     * `GET/POST /api/skills` —— 自定义 `/命令`（技能）的读与增删。
     * `POST {op:'add'|'del', name, desc, text}`；同名算覆盖。
     */
    private fun skills(ex: HttpExchange) {
        val b = Body(ex)
        if (ex.requestMethod == "GET") {
            val items = Skills.load().joinToString(",", "[", "]") {
                """{"name":${quote(it.name)},"desc":${quote(it.desc)},"text":${quote(it.text)}}"""
            }
            send(ex, 200, """{"ok":true,"items":$items,"taken":${quote(BUILTIN_CMDS.joinToString(","))}}""",
                "application/json; charset=utf-8")
            return
        }
        val name = b.str("name").trim().trimStart('/')
        if (name.isEmpty()) {
            send(ex, 200, """{"ok":false,"error":"命令名是空的"}""",
                "application/json; charset=utf-8"); return
        }
        val list = Skills.load().toMutableList()
        if (b.str("op") == "del") {
            list.removeAll { it.name == name }
        } else {
            if (name.any { it.isWhitespace() }) {
                send(ex, 200, """{"ok":false,"error":"命令名不能含空格"}""",
                    "application/json; charset=utf-8"); return
            }
            if (name.lowercase() in BUILTIN_CMDS) {
                send(ex, 200, """{"ok":false,"error":"/$name 是内置命令，换一个名字"}""",
                    "application/json; charset=utf-8"); return
            }
            list.removeAll { it.name == name }
            list += Skill(name, b.str("desc").trim(), b.str("text"))
        }
        Skills.save(list)
        send(ex, 200, """{"ok":true,"count":${list.size}}""", "application/json; charset=utf-8")
    }

    /**
     * `GET/POST /api/mcp` —— 外部 MCP 服务器配置的读、改与重连。
     * `POST {op:'add'|'del', name, command, args}`（args 是空格分隔的一行）。
     *
     * 界面只能**改这份配置文件**：要跑哪个命令得由用户自己写下来。
     * 模型没有这条路 —— 它能调用已注册的外部工具，但拉不起一个新进程。
     */
    private fun mcp(ex: HttpExchange) {
        fun render(status: List<Map<String, String>>): String = status.joinToString(",", "[", "]") { row ->
            "{" + row.entries.joinToString(",") { (k, v) -> "\"$k\":${quote(v)}" } + "}"
        }
        if (ex.requestMethod == "GET") {
            send(ex, 200, """{"ok":true,"servers":${render(Mcp.status())}}""",
                "application/json; charset=utf-8")
            return
        }
        val b = Body(ex)
        val list = McpConfig.load().toMutableList()
        val name = b.str("name").trim()
        if (name.isEmpty()) {
            send(ex, 200, """{"ok":false,"error":"名字是空的"}""",
                "application/json; charset=utf-8"); return
        }
        if (b.str("op") == "del") list.removeAll { it.name == name }
        else {
            val cmd = b.str("command").trim()
            if (cmd.isEmpty()) {
                send(ex, 200, """{"ok":false,"error":"命令是空的"}""",
                    "application/json; charset=utf-8"); return
            }
            if (!name.all { it.isLetterOrDigit() || it == '_' || it == '-' }) {
                send(ex, 200, """{"ok":false,"error":"名字只能用字母、数字、_ 与 -"}""",
                    "application/json; charset=utf-8"); return
            }
            list.removeAll { it.name == name }
            list += McpServer(name, cmd.substringBefore(' '), cmd.substringAfter(' ', "")
                .split(' ').filter { it.isNotBlank() })
        }
        McpConfig.save(list)
        Mcp.reload()
        send(ex, 200, """{"ok":true,"servers":${render(Mcp.status())}}""",
            "application/json; charset=utf-8")
        // 工具表变了 → 顶栏那圈占用（工具说明那一行）必须重算
        publish("settings", """{"mode":${quote(settings.permissionMode)}}""")
    }

    /** `GET /api/models?sid=` —— 列网关上的模型，给顶栏的模型切换器用。 */
    private fun models(ex: HttpExchange) {
        val sid = pick(querySid(ex))
        val list = runCatching { sessions[sid]?.engine?.models() ?: emptyList() }.getOrDefault(emptyList())
        send(ex, 200, list.joinToString(",", "[", "]") { m ->
            """{"id":${quote(m)},"current":${m == settings.model}}"""
        }, "application/json; charset=utf-8")
    }

    /** `GET /api/export?sid=` —— 整条会话导成 markdown（贴进文档、发给别人时用）。 */
    private fun exportSession(ex: HttpExchange) {
        val sid = pick(querySid(ex))
        val e = sessions[sid]?.engine
        if (e == null) {
            send(ex, 404, "没有这条会话", "text/plain; charset=utf-8"); return
        }
        val sb = StringBuilder("# ").append(e.session.title.get()).append("\n\n")
        e.messages().forEach { m ->
            when {
                m.role == "user" -> sb.append("## 你\n\n").append(m.content ?: "").append("\n\n")
                m.role == "assistant" && !m.content.isNullOrBlank() ->
                    sb.append("### 助手\n\n").append(m.content).append("\n\n")
                m.role == "tool" -> sb.append("> `").append(m.name).append("` ")
                    .append((m.content ?: "").lineSequence().first().take(160)).append("\n\n")
            }
        }
        ex.responseHeaders.add("Content-Disposition", """attachment; filename="haoai-$sid.md"""")
        send(ex, 200, sb.toString(), "text/markdown; charset=utf-8")
    }

    private fun queryOf(ex: HttpExchange, key: String): String = ex.requestURI.query
        ?.splitToSequence('&')
        ?.firstOrNull { it.startsWith("$key=") }
        ?.substringAfter('=')
        ?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
        ?: ""

    private fun querySid(ex: HttpExchange): String = queryOf(ex, "sid")

    /** 开一条新会话（只登记并立刻落盘，不起线程、不跑任务）。 */
    private fun newSessionId(): String {
        val session = Session("pc" + System.nanoTime().toString(16).take(8), settings.workspaceFile())
        session.mode = settings.permissionMode
        var made: Engine? = null
        val e = Engine(session, settings, allTools(), webGate(session.id) { made },
            emit = { ev -> forward(session.id, ev) })
        made = e
        e.persistNow()
        sessions[session.id] = Managed(e)
        touch(session.id)
        return session.id
    }
    /**
     * `POST /api/stop` —— 停止**指定会话**（默认当前那条）的任务。
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
        val sid = pick(Body(ex).str("sid"))
        val managed = sessions[sid]
        val waiters = pendingPayload.entries.toList().filter { it.value.sid == sid }
        var approvals = 0
        waiters.forEach { (id, w) ->
            // 审批的 id 以 a 开头、提问以 q 开头：两者"被中止"的语义不一样，
            // 审批给 deny（fail-closed），提问给空串（模型会看到"用户没回答"）。
            if (w.ev == "ask") pending[id]?.complete("")
            else { pending[id]?.complete("deny"); approvals++ }
        }
        managed?.engine?.requestStop()
        publish(
            "notice", quote(
                if (managed?.running == true || waiters.isNotEmpty())
                    "已请求停止${if (approvals > 0) "（顺手拒掉 $approvals 个待确认）" else ""}，正在收尾…"
                else "这条会话现在没有正在跑的任务。"
            ), sid
        )
        send(ex, 200, """{"ok":true,"pending":${waiters.size}}""", "application/json; charset=utf-8")
    }

    private fun forward(sid: String, ev: Ev) {
        when (ev) {
            is Ev.TextDelta -> publish("delta", quote(ev.s), sid)
            is Ev.ReasoningDelta -> publish("reason", quote(ev.s), sid)
            is Ev.TurnStats -> publish(
                "stats",
                """{"pt":${ev.pt},"ct":${ev.ct},"ms":${ev.ms},"turns":${ev.turns}}""",
                sid
            )
            is Ev.TextDone -> publish("answer", quote(ev.s), sid)
            is Ev.ToolStart -> publish(
                "tool",
                """{"id":${quote(ev.id)},"name":${quote(ev.name)},"brief":${quote(ev.brief)},"state":"run","subject":${quote(ev.subject)}}""",
                sid
            )
            is Ev.ToolEnd -> publish(
                "tool",
                """{"id":${quote(ev.id)},"name":${quote(ev.name)},"ok":${ev.ok},"card":${quote(ev.card)},""" +
                    """"out":${quote(ev.out)},"diff":${quote(ev.diff)},"note":${quote(ev.note)},""" +
                    """"sub":${quote(ev.sub)}}""",
                sid
            )
            is Ev.Sub -> publish(
                "sub",
                """{"label":${quote(ev.label)},"kind":${quote(ev.kind)},"text":${quote(ev.text)}}""",
                sid
            )
            is Ev.Todo -> publish(                "todo", """{"items":${ev.items.joinToString(",", "[", "]") { quote(it) }}}""", sid
            )
            is Ev.Usage -> publish(
                "usage",
                """{"prompt":${ev.prompt},"completion":${ev.completion},"turns":${ev.turns}}""", sid
            )
            is Ev.Notice -> publish("notice", quote(ev.s), sid)
            is Ev.Err -> publish("err", quote(ev.s), sid)
            is Ev.Title -> publish("title", """{"title":${quote(ev.s)}}""", sid)
            else -> Unit
        }
    }

    /**
     * 网页壳的闸口。
     *
     * `engineOf` 由创建方给（引擎构造时还握不住自己的引用）。刻意**不**按 sid 去 `sessions`
     * 里查：那张表在会话超出驻留上限时会踢掉最久没用的那条，而被踢的引擎可能还在跑 ——
     * 那时闸口就该拿不到自己该通知的对象了。引用直接给，不绕地图。
     */
    private fun webGate(sid: String, engineOf: () -> Engine? = { sessions[sid]?.engine }): Gate = object : Gate {
        override fun approve(title: String, detail: String, kind: String): Boolean =
            approveRule(title, detail, kind, "", "*")

        override fun approveRule(
            title: String, detail: String, kind: String, tool: String, pattern: String
        ): Boolean {
            val id = "a${seq.incrementAndGet()}"
            val fut = java.util.concurrent.CompletableFuture<String>()
            pending[id] = fut
            if (tool.isNotBlank()) pendingRule[id] = tool to pattern
            val payload =
                """{"id":"$id","title":${quote(title)},"detail":${quote(detail)},"kind":"$kind",""" +
                    """"tool":${quote(tool)},"pattern":${quote(pattern)}}"""
            pendingPayload[id] = Waiter("approval", payload, sid)
            publish("approval", payload, sid)
            var timedOut = false
            val ans = try {
                fut.get(300, TimeUnit.SECONDS)
            } catch (e: TimeoutException) {
                timedOut = true
                publish("notice", quote("300 秒无人应答，按拒绝处理"), sid)
                "deny"
            } catch (e: Exception) {
                "deny"
            } finally {
                pending.remove(id)
                pendingRule.remove(id)
                pendingPayload.remove(id)
            }
            /*
             * 结论挂到紧接着落的那条工具消息上。
             * 之前它只随 SSE 流一次：内联卡答完就收起，刷新之后卡没了，
             * 于是"这个文件到底是用户点头写的、还是自动写的、还是超时被拒的"查不出来。
             */
            engineOf()?.markApproval(
                when {
                    timedOut -> "超时未答，按拒绝处理"
                    ans == "allow_once" -> "允许一次"
                    ans == "allow_session" -> "本任务都允许"
                    ans == "allow_rule" -> "写入规则并允许"
                    else -> "已拒绝"
                }
            )
            // allow_rule：把这条规则永久写进当前工作区的规则表（S2）
            if (ans == "allow_rule" && tool.isNotBlank()) {
                Policies.get().add(settings.workspaceFile(), Rule(tool, pattern, Decision.ALLOW))
                publish("notice", quote("已记住规则：$tool($pattern)"), sid)
            }
            return ans == "allow_once" || ans == "allow_session" || ans == "allow_rule"
        }

        override fun ask(question: String, options: List<String>): String {
            val id = "q${seq.incrementAndGet()}"
            val fut = java.util.concurrent.CompletableFuture<String>()
            pending[id] = fut
            val askPayload =
                """{"id":"$id","question":${quote(question)},"options":${options.joinToString(",", "[", "]") { quote(it) }}}"""
            pendingPayload[id] = Waiter("ask", askPayload, sid)
            publish("ask", askPayload, sid)
            return try {
                fut.get(900, TimeUnit.SECONDS)
            } catch (e: Exception) {
                ""
            } finally {
                pending.remove(id)
                pendingPayload.remove(id)
            }
        }
    }

    private fun decide(ex: HttpExchange) {
        val b = Body(ex)
        val id = b.str("id")
        val decision = b.str("decision")
        val answer = b.str("answer")
        val fut = pending[id]
        if (fut != null) fut.complete(if (answer.isNotBlank()) answer else decision)
        if (decision == "allow_session") {
            // "本任务都允许"只影响发起这条审批的那条会话，不该把别的会话也切成 auto
            val sid = pendingPayload[id]?.sid ?: currentId() ?: ""
            sessions[sid]?.engine?.session?.mode = "auto"
            publish("mode", """{"mode":"auto"}""", sid)
        }
        send(ex, 200, """{"ok":true}""", "application/json; charset=utf-8")
    }

    private fun mode(ex: HttpExchange) {
        val b = Body(ex)
        val m = b.str("mode")
        val sid = pick(b.str("sid"))
        var now = sessions[sid]?.engine?.session?.mode ?: settings.permissionMode
        if (m in listOf("plan", "ask", "auto")) {
            val target = sid.ifBlank { currentId() ?: newSessionId() }
            touch(target)
            sessions[target]?.engine?.session?.mode = m
            now = m
            publish("mode", """{"mode":"$m"}""", target)
        }
        send(ex, 200, """{"ok":true,"mode":${quote(now)}}""", "application/json; charset=utf-8")
    }

    /**
     * 旧会话列表。`?q=` 时连**消息正文**一起搜（与移动端同源：
     * 用户记得的是"我那天问过什么"，不是标题那几个字）。
     */
    private fun sessionsJson(q: String = ""): String {
        val cur = currentId()
        val needle = q.trim().lowercase()
        return SessionIndex.list(if (needle.isBlank()) 200 else 400).mapNotNull { m ->
            val hit = if (needle.isBlank()) null else SessionIndex.match(m, needle)
            if (needle.isNotBlank() && hit == null) return@mapNotNull null
            """{"id":${quote(m.id)},"title":${quote(m.title)},"workspace":${quote(m.workspace)},""" +
                """"mode":"${m.mode}","updated":${m.updated},"messages":${m.messages},""" +
                """"prompt":${m.prompt},"completion":${m.completion},""" +
                """"running":${sessions[m.id]?.running == true},"current":${m.id == cur},""" +
                """"hit":${quote(hit ?: "")}}"""
        }.joinToString(",", "[", "]")
    }

    /** `GET /api/state?sid=` —— 不带 sid 就是当前会话。 */
    private fun state(ex: HttpExchange) {
        val sid = ex.requestURI.query?.splitToSequence('&')
            ?.firstOrNull { it.startsWith("sid=") }?.substringAfter('=') ?: ""
        send(ex, 200, stateJson(sid), "application/json; charset=utf-8")
    }

    private fun newSession(ex: HttpExchange) {
        // 切会话不再需要"先停止"：每条会话有自己的引擎与 running，
        // 后台那条继续跑，事件按 sid 分流，停止也按 sid 打。
        //
        // 眼前这条如果还一条没说过，就**不要**再造一条：一路点"新任务"会在磁盘上
        // 留下一堆空的 pc-*.json，侧栏被自己几分钟前的手滑刷屏。
        val idle = synchronized(this) {
            currentId()?.let { sessions[it] }?.takeIf { m ->
                !m.running && m.engine.messages().isEmpty() && m.engine.session.todos.isEmpty()
            }
        }
        val id = idle?.engine?.session?.id ?: newSessionId()
        val title = idle?.engine?.session?.title?.get() ?: "新会话"
        val m = sessions[id]?.engine?.session?.mode ?: settings.permissionMode
        // 档位要一起回：新会话的档位**继承全局默认**，不是"上一条会话的档位"。
        // 前端只拿到 id 的话会沿用界面上那份旧档位显示出来（实测：全局是 ask，
        // 新建的会话顶上却亮着"自动"，用户以为自己在问模式下让它自动写了文件）。
        send(ex, 200, """{"ok":true,"id":${quote(id)},"mode":${quote(m)},"reused":${idle != null}}""",
            "application/json; charset=utf-8")
        publish("opened", """{"id":${quote(id)},"title":${quote(title)},"mode":${quote(m)}}""", id)
        publish("sessions", "{}", id)
    }

    private fun openSession(ex: HttpExchange) {
        val id = Body(ex).str("id")
        val meta = SessionIndex.list(400).firstOrNull { it.id == id }
        if (meta == null) {
            send(ex, 404, """{"ok":false,"error":"没有这个会话"}""", "application/json; charset=utf-8")
            return
        }
        // 引擎按 id 复用：正在跑的那条不会因为"打开它"而被重建
        val m = synchronized(this) {
            sessions.getOrPut(id) { Managed(engineFor(id)) }.also { touch(id) }
        }
        send(ex, 200, """{"ok":true,"id":${quote(id)}}""", "application/json; charset=utf-8")
        publish("opened", """{"id":${quote(id)},"title":${quote(meta.title)},"mode":${quote(m.engine.session.mode)}}""", id)
    }

    /**
     * `POST /api/rename` {id,title} —— 改会话标题。
     * 内存里那份也要改，否则下一回合结束 `persist()` 会把新标题又写回旧的。
     */
    private fun renameSession(ex: HttpExchange) {
        val b = Body(ex)
        val id = b.str("id")
        val title = b.str("title")
        val ok = SessionIndex.rename(id, title)
        if (ok) sessions[id]?.engine?.session?.title?.set(title.trim().take(60))
        send(ex, if (ok) 200 else 404,
            """{"ok":$ok}""", "application/json; charset=utf-8")
        if (ok) publish("sessions", "{}", id)
    }

    /**
     * `POST /api/delete` {id} —— 删会话（实为移进 `sessions/.trash`，见 [SessionIndex.delete]）。
     *
     * 一条硬约束：**跑着的会话不许删**。否则线程会在一个已经被移走的文件上
     * 继续 `persist()`，把文件又写回来，表现为"删了又出现"。
     */
    private fun deleteSession(ex: HttpExchange) {
        val id = Body(ex).str("id")
        if (id.isNotBlank() && sessions[id]?.running == true) {
            send(ex, 409, """{"ok":false,"error":"这个会话正在跑，先停止再删"}""",
                "application/json; charset=utf-8")
            return
        }
        val ok = SessionIndex.delete(id)
        if (ok) {
            sessions.remove(id)
            synchronized(order) { order.remove(id) }
        }
        send(ex, if (ok) 200 else 404, """{"ok":$ok}""", "application/json; charset=utf-8")
        if (ok) publish("sessions", "{}", null)
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
            """"model":${quote(st.model)},"mode":${quote(st.permissionMode)},"maxTokens":${st.maxTokens},""" +
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
        body["model"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { n = n.copy(model = it) }
        body["baseUrl"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { n = n.copy(baseUrl = it) }
        body["maxTokens"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()?.let { n = n.copy(maxTokens = maxOf(0, it)) }
        body["mode"]?.jsonPrimitive?.contentOrNull?.takeIf { it in listOf("plan", "ask", "auto") }?.let {
            n = n.copy(permissionMode = it)
            // 权限模式是全局设置，但要立刻反映到**每一个**活着的会话引擎上，
            // 否则切回后台那个会话时它会继续用旧模式跑。（这里刻意不用 `it`，
            // 外层 `let` 已经把模式字符串占掉了，嵌套 `forEach { it -> }` 会把外层值遮蔽掉。）
            sessions.values.forEach { m -> m.engine.session.mode = it }
        }
        body["workspace"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { n = n.copy(workspace = it) }
        body["flags"]?.jsonObject?.let { fo ->
            val merged = n.flags.toMutableMap()
            fo.forEach { (k, v) -> merged[k] = (v.jsonPrimitive.content == "true") }
            n = n.copy(flags = HaoFlag.compactOverrides(merged))
        }
        /*
         * 密钥单独一条路：它**不进 PcSettings**（那份会被 GET /api/settings 整体发回前端），
         * 只落 HAOAI_HOME/apikey。界面上给一个改密钥的入口是必要的 —— 之前只能回 CLI，
         * 而"网页里能改模型、改网关，唯独改不了 key"会让人以为哪儿配错了。
         */
        val key = body["key"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (key.isNotEmpty()) {
            val done = runCatching { Env.apiKeyFile.writeText(key) }
            if (done.isFailure) {
                send(ex, 200,
                    """{"ok":false,"error":${quote("密钥存不下：" + (done.exceptionOrNull()?.message ?: ""))}}""",
                    "application/json; charset=utf-8")
                return
            }
        }
        PcSettings.save(n)
        settings = n
        // 全局设置变了，**每条活着的会话都要跟上**：引擎各自握着构造时那份设置
        // （模型、baseUrl、压缩阈值都在里面），不换的话界面上显示新模型、发请求用旧的。
        sessions.values.forEach { m ->
            m.engine.useSettings(n)
            // 跑着的那条不改档位：它可能刚被"本任务都允许"提到 auto，
            // 中途被一次设置保存打回去，正等确认的工具会当场变成"被拒绝"。
            if (!m.running) m.engine.session.mode = n.permissionMode
        }
        publish("settings", """{"mode":${quote(n.permissionMode)}}""")
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
