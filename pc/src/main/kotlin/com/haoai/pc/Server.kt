package com.haoai.pc

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
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
class WebServer(settings: PcSettings, port: Int,
               /** 同时最多跑几条。没有上限的话用户可以一路开下去把网关和磁盘都刷爆。
                *  做成构造参数只为一件事能被测：满了时的一次失败发送不许留下空会话。 */
               private val maxRunning: Int = 4) {

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

        /**
         * 跑着的时候用户又发了一句 —— 先排队，本轮结束自动接上（见 `startRun` 的入队分支）。
         *
         * 以前这里直接 409："这条会话正在跑，先按停止再发新任务"。可是用户手打的下一句
         * 往往就是要紧接着说的，让他先停止等于打断正在跑的活；手机端与 codex 都是排队 + 可撤回。
         * 上限 8 句：再多说明用户在试错，不如下令停下来看一眼。
         */
        val queue = mutableListOf<Pair<String, List<String>>>()
    }

    private val sessions = ConcurrentHashMap<String, Managed>()

    /** 前端 `/` 命令面板里的内置项：自定义技能不许占用这些名字（两份实现靠这份对齐）。 */
    private val BUILTIN_CMDS = setOf(
        "plan", "ask", "auto", "new", "model", "stop", "clear", "export", "theme", "status", "help"
    )

    /** 最近使用顺序（新的在前），用来在没指定 sid 时挑"当前会话"。 */
    private val order = Collections.synchronizedList(mutableListOf<String>())

    
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
        // 定时任务的线程跟着服务起落：daemon 线程，JVM 退了它自己就没了
        sched = Scheduler { s -> runSchedule(s) }.also { it.start() }
        return server.address.port
    }

    /** 调度线程。测试里也直接拿它 `tick(now)`，不用真等 5 秒。 */
    private var sched: Scheduler? = null

    fun schedules(): Scheduler? = sched

    fun stop() {
        sched?.stopped = true
        sched = null
        server.stop(0)
    }

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
                "/api/model" -> modelSet(ex)
                "/api/rule" -> ruleEdit(ex)
                "/api/memory" -> memory(ex)
                "/api/skills" -> skills(ex)
                "/api/mcp" -> mcp(ex)
                "/api/schedules" -> schedules(ex)
                "/api/files" -> files(ex)
                "/api/workspaces" -> workspaces(ex)
                "/api/compact" -> compactNow(ex)
                "/api/usage" -> send(ex, 200, UsageLedger.report(),
                    "application/json; charset=utf-8")
                "/api/file" -> fileOne(ex)
                "/api/img" -> imageFile(ex)
                "/api/media" -> mediaFile(ex)
                "/api/export" -> exportSession(ex)
                "/api/delete" -> deleteSession(ex)
                "/api/unqueue" -> unqueue(ex)
                "/api/delmsg" -> deleteMessage(ex)
                "/api/pin" -> pinSession(ex)
                "/api/tool" -> toolToggle(ex)
                "/api/resume" -> resumeRun(ex)
                "/api/abandon" -> abandonRun(ex)
                "/api/backups" -> send(ex, 200, backupsJson(), "application/json; charset=utf-8")
                "/api/backup" -> backupOp(ex)
                "/api/substop" -> stopSubtask(ex)
                "/api/trash" -> trashList(ex)
                "/api/untrash" -> untrashSession(ex)
                "/api/purge" -> purgeTrash(ex)
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
        // 会话自己的模型与工具开关要在这里接上：引擎构造时拿的是全局设置，
        // 不补这一步，"这条会话用的是本地 7B、shell 已关掉"重启后就悄悄没了。
        if (meta != null && (meta.model.isNotBlank() || meta.toolsOff.isNotEmpty())) {
            e.useSettings(settings.copy(
                model = meta.model.ifBlank { settings.model },
                toolsOff = meta.toolsOff
            ))
        }
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
        // 模型要报**这条会话自己的**：从 v0.32 起可以只给一条会话换模型，
        // 还报全局那份的话，顶栏那个标签就在说谎（引擎实际用的和显示的不一样）。
        sb.append("\"model\":\"").append(esc(e?.settings?.model ?: settings.model)).append("\",")
        // 排队的句子要跟着 state 报出去：刷新页面之后"还有两句等着说"不能看不见
        sb.append("\"queue\":[").append(
            managed?.let { m ->
                synchronized(m.queue) {
                    m.queue.joinToString(",") { (t, im) ->
                        """{"t":${quote(t)},"imgs":${im.joinToString(",", "[", "]") { quote(it) }}}"""
                    }
                }
            } ?: ""
        ).append("],")
        // 工具清单要连着"这条会话关掉了哪几把"一起报，界面上才看得见开关的状态
        sb.append("\"tools\":[").append(
            e?.toolInfos()?.joinToString(",") { t ->
                """{"name":${quote(t.name)},"kind":${quote(t.kind)},"desc":${quote(t.desc.take(90))},""" +
                    """"off":${t.off},"gated":${t.gated}}"""
            } ?: ""
        ).append("],")
        // 没跑完的现场：重启后还在，就说明这条是被杀/断电打断的，界面上要举出来
        sb.append("\"runState\":").append(e?.runStateJson() ?: "null").append(',')
        // 正在跑的子任务：卡片是事件流里造的，刷新之后重放历史造不出它，
        // 不报出去就等于"刷新一次就再也停不掉那条调研"
        sb.append("\"subs\":[").append((e?.subNames() ?: emptyList())
            .joinToString(",") { quote(it) }).append("],")
        sb.append("\"version\":\"").append(esc(PC_VERSION)).append("\",")
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
        // 触发线与"已经折掉多少条"要报给界面：不报的话"现在压缩一次"这个按钮点下去
        // 到底是压了还是没到量，用户只能猜
        sb.append(",\"trigger\":").append(settings.compactTriggerChars)
        sb.append(",\"compactedThrough\":").append(e?.compactedCount ?: 0)
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
                .append(",\"images\":").append(m.images.joinToString(",", "[", "]") { quote(it) })
                .append(",\"media\":").append(m.media.joinToString(",", "[", "]") { quote(it) })
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

        /** 字符串数组（图片附件那类）；字段缺失或不是数组都当空表。 */
        fun list(key: String): List<String> =
            obj[key]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
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
    /**
     * 浏览器传来的图片路径必须留在**这条会话自己的工作区**里。
     *
     * 这个服务只绑 127.0.0.1，但同机任意页面都能对这个端口发请求；而图片会被 base64
     * 之后原样发给远端网关 —— 一条 `C:/Users/.../id_rsa` 只要被当"图片"递出去，
     * 就等于把读文件的口子开给了浏览器。认不出类型的（不是那四种魔数）一并丢掉，
     * 一条消息最多带 4 张：再多不是看图，是刷 token。
     */
    private fun insideWorkspace(sid: String, paths: List<String>): List<String> {
        if (paths.isEmpty()) return emptyList()
        val ws = runCatching {
            (sessions[pick(sid)]?.engine?.session?.workspace ?: settings.workspaceFile()).canonicalFile
        }.getOrNull() ?: return emptyList()
        return paths.mapNotNull { p ->
            val f = runCatching {
                if (File(p).isAbsolute) File(p).canonicalFile else File(ws, p).canonicalFile
            }.getOrNull() ?: return@mapNotNull null
            val inside = f.path == ws.path || f.path.startsWith(ws.path + File.separator)
            if (!inside || Images.usable(f.absolutePath) == null) null else f.absolutePath
        }.take(4)
    }

    private fun startRun(wantSid: String, text: String, cutTo: Int? = null,
                         named: String = "", fresh: Boolean = false,
                         images: List<String> = emptyList(),
                         goal: String? = null): Pair<String, String?> {
        /*
         * 先判「能不能跑」，再决定要不要新建会话：上一版是先 newSessionId() 再检查并行上限，
         * 于是四条槽都满时用户只是发送失败，列表里却多出一条空白的「新会话」——
         * 一次失败的发送不该留下任何痕迹。
         * `fresh` 是给定时任务用的：它必须另起一条，不能塞进用户正在聊的那条。
         * `named` 必须在这里定：Engine.submit 只在标题还是「新会话」时才自动取名，
         * 晚一步就被任务句子的前 24 字占了（定时任务的句子本来就是半截话）。
         */
        // 正在跑的那条又收到一句：不进并发判定，直接排队，本轮结束后由 run 线程接上。
        // 编辑重发 / 重新生成（带 cutTo）不排队 —— 它们要截断历史，排队过去位置就错了。
        if (cutTo == null && !fresh && wantSid.isNotBlank()) {
            sessions[wantSid]?.let { m ->
                if (m.running) {
                    val q = synchronized(m.queue) {
                        if (m.queue.size >= 8) null else { m.queue.add(text to images); m.queue.toList() }
                    }
                    if (q == null) return wantSid to "这条会话已经排了 8 句，先撤回几句"
                    publish("queue", queueJson(wantSid, q), wantSid)
                    return wantSid to null
                }
            }
        }
        val (sid, managed) = synchronized(this) {
            val use = if (fresh) "" else wantSid.ifBlank { currentId() ?: "" }
            if (liveCount() >= maxRunning) {
                use to null
            } else if (use.isNotEmpty() && sessions[use]?.running == true) {
                use to null
            } else {
                val target = use.ifBlank { newSessionId() }
                val m = sessions[target] ?: Managed(engineFor(target)).also { sessions[target] = it }
                touch(target)
                // 清停止旗要和置 running 在同一把锁里：见 Engine.beginRun 的注释
                m.engine.beginRun()
                m.running = true
                if (named.isNotEmpty()) {
                    m.engine.session.title.set(named)
                    // newSessionId 已经把这条落过一次盘（标题还是「新会话」），不补写一次，
                    // 侧栏立刻刷新出来看到的还是「新会话」，定时任务就像没起名。
                    m.engine.persistNow()
                }
                target to m
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
        // 负载从裸字符串变成对象：图片路径要一起出去，前端才画得出刚发出去的那张图。
        // 前端两种形状都认（见 index.html 的 on(user)），所以这一步不会打断旧页面。
        publish("user", """{"t":${quote(text)},"imgs":${images.joinToString(",", "[", "]") { quote(it) }}}""", sid)
        val e = managed.engine
        Thread {
            publish("run", """{"running":true}""", sid)
            try {
                e.submit(text, images, goal)
            } catch (err: Exception) {
                publish("err", quote("回合异常：${err.message ?: err.javaClass.simpleName}"), sid)
            } finally {
                managed.running = false
                publish("run", """{"running":false}""", sid)
                publish("sessions", "{}", sid)
                /*
                 * 本轮真的结束了才换下一句 —— 两条任务往同一段历史上写是最坏的情况。
                 * 一次只取一句：发出去之后 running 又是 true，再往后的句子由那条 run 线程接力。
                 */
                val stopped = e.stopRequested
                val next = if (stopped) null else synchronized(managed.queue) {
                    if (managed.queue.isEmpty()) null else managed.queue.removeAt(0)
                }
                if (stopped) {
                    /*
                     * 按停止 = "这条别再自己往下跑了"。这时候把排队的句子接着发出去，
                     * 用户看到的就是"我按了停止它又自己说起话来"。
                     * 队列在这里清空，文字由前端放回输入框（见 index.html 的 stopNow），
                     * 所以不是把用户打的话弄丢。
                     */
                    val dropped = synchronized(managed.queue) {
                        val n = managed.queue.size; managed.queue.clear(); n
                    }
                    if (dropped > 0) publish("queue", queueJson(sid, emptyList()), sid)
                }
                if (next != null) {
                    publish("queue", queueJson(sid, synchronized(managed.queue) { managed.queue.toList() }), sid)
                    val (_, qErr) = startRun(sid, next.first, null, "", false, next.second)
                    if (qErr != null) publish("err", quote("排队的那句没发出去：" + qErr), sid)
                }
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
        val (sid, err) = startRun(wantSid, text, images = insideWorkspace(wantSid, b.list("images")))
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
     * `GET /api/files?sid=&q=` —— 递归按片段搜，给输入框的 @ 提及用（返回相对路径）。
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
        val q = queryOf(ex, "q").trim()
        if (q.isNotEmpty()) {
            /*
             * `?q=` 是输入框里 @ 提及文件的递归搜索。三条边界都是必须的：
             * 只在 canonical 之后的工作区内走（这个服务绑 127.0.0.1，但浏览器里任何页面
             * 都能对本机端口发请求）；扫满 4000 个条目就收，免得在一份内核 checkout 里
             * 按一个字母就把盘扫穿；跳过 .git / node_modules / build 这类没人要的目录。
             */
            val skip = setOf(".git", ".gradle", ".idea", "build", "dist", "target", ".haoai-output", ".trash")
            val hits = mutableListOf<Pair<String, Boolean>>()
            val queue = ArrayDeque<Pair<File, Int>>()
            queue.add(ws to 0)
            var seen = 0
            val low = q.lowercase()
            while (queue.isNotEmpty() && hits.size < 40 && seen < 4000) {
                val (dir, depth) = queue.removeFirst()
                for (f in dir.listFiles()?.toList() ?: emptyList()) {
                    if (++seen > 4000) break
                    if (f.name in skip || f.name.startsWith("node_modules")) continue
                    val rel = f.relativeToOrSelf(ws).path.replace('\\', '/')
                    if (rel.lowercase().contains(low)) hits += rel to f.isDirectory
                    if (hits.size >= 40) break
                    if (f.isDirectory && depth < 6) queue.add(f to depth + 1)
                }
            }
            // 文件名开头就命中的排在前面，再按名字短的在前：打 "serv" 想看的第一个是 Server.kt，
            // 不是 xxx-service.txt，也不是某个深层同名文件
            val ranked = hits.sortedWith(
                compareByDescending<Pair<String, Boolean>> {
                    it.first.substringAfterLast('/').lowercase().startsWith(low)
                }.thenBy { it.first.substringAfterLast('/').length }.thenBy {
                    it.first.count { c -> c == '/' }
                }.thenBy { it.first.length }
            )
            send(ex, 200, ranked.joinToString(",", """{"path":"","entries":[""", "]}") { (rel, dir) ->
                """{"n":${quote(rel)},"d":$dir,"s":0}"""
            }, "application/json; charset=utf-8")
            return
        }
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
    /**
     * `GET /api/workspaces` —— 最近用过的工作区，给左栏那个切换器。
     *
     * 不另存一份"最近列表"：会话索引里本来就写着每条会话的工作区，再存一份就是两个真源，
     * 迟早对不上（手机端踩过这个）。这里按目录归组，条数与最后活动时间都从会话算出来。
     */
    /**
     * `POST /api/compact` {sid} —— 手动把早期消息折进摘要（不等触发线）。
     *
     * 跑着的那条不许压：历史正在被回合改，这时候算出来的切点等于两条任务往一段历史上写。
     */
    private fun compactNow(ex: HttpExchange) {
        val b = Body(ex)
        val sid = pick(b.str("sid"))
        val m = sessions[sid]
        if (m == null) {
            send(ex, 200, """{"ok":false,"error":"没有这条会话"}""", "application/json; charset=utf-8"); return
        }
        if (m.running) {
            send(ex, 200, """{"ok":false,"error":${quote("这条正在跑，等它收尾再压（不然切点会算错）")}}""",
                "application/json; charset=utf-8"); return
        }
        val before = m.engine.contextChars()
        val cut = runCatching { m.engine.compactNow() }.getOrElse { e ->
            send(ex, 200,
                """{"ok":false,"error":${quote("压缩失败：" + (e.message ?: e.javaClass.simpleName))}}""",
                "application/json; charset=utf-8")
            return
        }
        send(ex, 200, """{"ok":true,"cut":$cut,"before":$before,"after":${m.engine.contextChars()}}""",
            "application/json; charset=utf-8")
        publish("sessions", "{}", sid)
    }

    private fun workspaces(ex: HttpExchange) {
        val cur = pick(querySid(ex))
        val curWs = runCatching {
            (sessions[cur]?.engine?.session?.workspace ?: settings.workspaceFile()).canonicalFile.absolutePath
        }.getOrDefault("")
        val metas = SessionIndex.list(400)
        val grouped = LinkedHashMap<String, Pair<Int, Long>>()
        metas.forEach { m ->
            val key = runCatching { File(m.workspace).canonicalFile.absolutePath }.getOrDefault(m.workspace)
            if (key.isBlank()) return@forEach
            val (n, t) = grouped[key] ?: (0 to 0L)
            grouped[key] = (n + 1) to maxOf(t, m.updated)
        }
        // 全局默认那个即使一条会话都没有也要在列表里（第一次用的人只有它）
        val def = runCatching { settings.workspaceFile().canonicalFile.absolutePath }.getOrDefault("")
        if (def.isNotEmpty()) grouped.getOrPut(def) { 0 to 0L }
        val items = grouped.entries.sortedByDescending { it.value.second }
        send(ex, 200, items.joinToString(",", """{"current":${quote(curWs)},"items":[""", "]}") { (p, v) ->
            """{"p":${quote(p)},"n":${v.first},"t":${v.second},""" +
                """"cur":${p == curWs},"def":${p == def}}"""
        }, "application/json; charset=utf-8")
    }

    /**
     * `GET /api/img?sid=&path=` —— 把会话工作区里的图片原样发给浏览器，好让流里画得出缩略图。
     *
     * 历史里存的是路径，界面要显示就得有个取字节的口子。三条边界与 /api/files 同一套理由：
     * 这个服务只绑 127.0.0.1，但同机任意页面都能对这个端口发请求，所以路径必须 canonical
     * 之后仍在**这条会话自己的工作区**里，而且只放认得出的那四种图 ——
     * 否则它就成了一个"读任意本地文件并回显"的接口。
     */
    private fun imageFile(ex: HttpExchange) {
        val sid = pick(querySid(ex))
        val ws = runCatching {
            (sessions[sid]?.engine?.session?.workspace ?: settings.workspaceFile()).canonicalFile
        }.getOrNull()
        val given = queryOf(ex, "path")
        val f = if (ws == null) null else runCatching {
            // 工具产出的截图存的是绝对路径，用户附件是工作区相对路径：两种都要能取回
            (if (File(given).isAbsolute) File(given) else File(ws, given)).canonicalFile
        }.getOrNull()
        val inside = f != null && ws != null &&
            (f.path == ws.path || f.path.startsWith(ws.path + File.separator))
        val mime = if (inside && f != null && f.isFile) Images.mime(f) else null
        if (mime == null || f == null || f.length() > Images.MAX_BYTES) {
            send(ex, 404, "没有这张图（或它在工作区外面 / 太大 / 类型不认）",
                "text/plain; charset=utf-8"); return
        }
        val bytes = f.readBytes()
        ex.responseHeaders.add("Content-Type", mime)
        ex.responseHeaders.add("Cache-Control", "no-store")
        ex.sendResponseHeaders(200, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    /**
     * `GET /api/media?sid=&path=` —— 把这条会话工作区里的音视频发给浏览器，支持 Range。
     *
     * 三条边界与 /api/img 是同一套理由（这个服务只绑 127.0.0.1，但同机任意页面都能
     * 对这个端口发请求）：canonical 之后必须仍在这条会话自己的工作区内、类型必须由**魔数**
     * 认得出（只看后缀就等于让一个改名成 .mp4 的文本文件混出去）、单文件有上限。
     */
    private fun mediaFile(ex: HttpExchange) {
        val sid = pick(querySid(ex))
        val ws = runCatching {
            (sessions[sid]?.engine?.session?.workspace ?: settings.workspaceFile()).canonicalFile
        }.getOrNull()
        val given = queryOf(ex, "path")
        val f = if (ws == null) null else runCatching {
            (if (File(given).isAbsolute) File(given) else File(ws, given)).canonicalFile
        }.getOrNull()
        val inside = f != null && ws != null &&
            (f.path == ws.path || f.path.startsWith(ws.path + File.separator))
        val mime = if (inside && f != null && f.isFile && f.length() <= MediaMime.MAX) MediaMime.sniff(f) else null
        if (mime == null || f == null) {
            send(ex, 404, "没有这个媒体文件（或它在工作区外面 / 太大 / 类型不认）",
                "text/plain; charset=utf-8"); return
        }
        val len = f.length()
        val (from, to) = Range.parse(ex.requestHeaders.getFirst("Range"), len)
        val size = to - from + 1
        ex.responseHeaders.add("Content-Type", mime)
        ex.responseHeaders.add("Accept-Ranges", "bytes")
        ex.responseHeaders.add("Cache-Control", "no-store")
        val partial = from > 0 || to < len - 1
        if (partial) ex.responseHeaders.add("Content-Range", "bytes $from-$to/$len")
        ex.sendResponseHeaders(if (partial) 206 else 200, size)
        runCatching {
            java.io.RandomAccessFile(f, "r").use { raf ->
                val out = ex.responseBody
                val buf = ByteArray(64 * 1024)
                var left = size
                raf.seek(from)
                while (left > 0) {
                    val n = raf.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    left -= n
                }
                out.close()
            }
        }
    }

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
        /*
         * "是不是文本"不能靠 readText() 抛不抛异常 —— 它遇到坏字节是替换成 U+FFFD 而不是抛，
         * 于是一张 PNG 会以一屏乱码的形式被当成文本发回界面（实测就是这么露出来的）。
         * 改成自己判：图片魔数直接算二进制（界面走 /api/img 画出来），
         * 其余看前 8 KB 里有没有 NUL 字节（真正的文本文件不会有）。
         */
        val head = runCatching {
            val b = ByteArray(minOf(8192, f.length().toInt()))
            f.inputStream().use { it.read(b) }; b
        }.getOrDefault(ByteArray(0))
        val isImage = Images.mime(f) != null
        val isBinary = isImage || head.contains(0.toByte())
        if (isBinary) {
            send(ex, 200, """{"ok":true,"binary":true,"img":$isImage,"bytes":${f.length()}}""",
                "application/json; charset=utf-8"); return
        }
        val text = runCatching { f.readText() }.getOrNull()
        if (text == null) {
            send(ex, 200, """{"ok":true,"binary":true,"img":false,"bytes":${f.length()}}""",
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

    /**
     * `GET/POST /api/schedules` —— 定时任务的读与增删改。
     * `POST {op:'add'|'del'|'toggle'|'run', id?, name?, prompt?, kind?, every?, at?}`
     */
    private fun schedules(ex: HttpExchange) {
        val b = Body(ex)
        if (ex.requestMethod != "GET") {
            val list = Schedules.load()
            val id = b.str("id").ifBlank { "sc" + System.nanoTime().toString(16).take(8) }
            when (b.str("op")) {
                "del" -> Schedules.remove(id)
                "toggle" -> list.firstOrNull { it.id == id }?.let {
                    it.enabled = !it.enabled; Schedules.update(it)
                }
                "run" -> list.firstOrNull { it.id == id }?.let { runSchedule(it) }
                else -> {
                    val prompt = b.str("prompt").trim()
                    if (prompt.isEmpty()) {
                        send(ex, 200, """{"ok":false,"error":"要跑的那句话是空的"}""",
                            "application/json; charset=utf-8"); return
                    }
                    Schedules.update(
                        Schedule(
                            id = id,
                            name = b.str("name").trim().ifBlank { prompt.take(18) },
                            prompt = prompt,
                            kind = if (b.str("kind") == "daily") "daily" else "interval",
                            every = b.str("every").toIntOrNull()?.coerceIn(1, 7 * 24 * 60) ?: 60,
                            at = b.str("at").ifBlank { "09:00" }
                        )
                    )
                }
            }
        }
        send(ex, 200, """{"ok":true,"items":${schedulesJson()}}""", "application/json; charset=utf-8")
        publish("settings", """{"mode":${quote(settings.permissionMode)}}""")
    }

    private fun schedulesJson(): String = Schedules.load().joinToString(",", "[", "]") { s ->
        """{"id":${quote(s.id)},"name":${quote(s.name)},"prompt":${quote(s.prompt)},""" +
            """"kind":${quote(s.kind)},"every":${s.every},"at":${quote(s.at)},""" +
            """"enabled":${s.enabled},"lastRun":${s.lastRun},"lastSid":${quote(s.lastSid)},""" +
            """"lastError":${quote(s.lastError)},"nextDue":${Schedule.nextDue(s, System.currentTimeMillis())}}"""
    }

    /**
     * 到点（或用户点了"立刻跑一次"）：起一条**新会话**去跑这句话。
     *
     * 用新会话而不是塞进当前会话：定时任务是"另一件事"，混进用户正在聊的那条，
     * 除了把上下文搅乱之外没有别的好处；而参考实现（openclaw 那类双机部署）也是
     * 一次触发一条独立会话。标题定成任务名，否则列表里全是"每天早上…"这种半截句子。
     */
    private fun runSchedule(s: Schedule) {
        val item = s.copy()
        item.lastRun = System.currentTimeMillis()
        item.lastError = ""
        Schedules.update(item)
        val sid = try {
            val (newId, err) = startRun("", item.prompt, named = item.name, fresh = true)
            if (err != null) throw IllegalStateException(err)
            publish("title", """{"title":${quote(item.name)}}""", newId)
            // 侧栏要立刻刷出来：定时任务跑那几分钟里用户得看得见它在动，而不是"到点没反应"
            publish("sessions", "{}")
            newId
        } catch (e: Exception) {
            item.lastError = e.message ?: e.javaClass.simpleName
            Schedules.update(item)
            return
        }
        item.lastSid = sid
        Schedules.update(item)
    }

    /**
     * `POST /api/model` {sid, model, scope} —— 换模型。
     *
     * `scope` 缺省是 `session`：只改这一条引擎的设置。以前全局只有一个模型，
     * 于是"这条会话拿本地小模型试个简单问题、别影响另外三条"做不到，
     * 而 codex / opencode 这类参考实现都是按会话选的。
     * `scope=all` 才是原来的全局路径：存盘 + 所有活着的会话一起跟上。
     */
    private fun modelSet(ex: HttpExchange) {
        val b = Body(ex)
        val m = b.str("model").trim()
        if (m.isEmpty()) {
            send(ex, 200, """{"ok":false,"error":"模型名是空的"}""",
                "application/json; charset=utf-8"); return
        }
        val sid = pick(b.str("sid"))
        val all = b.str("scope") == "all"
        if (all) {
            val n = settings.copy(model = m)
            PcSettings.save(n)
            settings = n
            sessions.values.forEach { it.engine.useSettings(n) }
        } else {
            val e = sessions[sid]?.engine
            if (e == null) {
                send(ex, 200, """{"ok":false,"error":"没有这条会话，改不了模型"}""",
                    "application/json; charset=utf-8"); return
            }
            e.useSettings(e.settings.copy(model = m))
        }
        send(ex, 200, """{"ok":true,"model":${quote(m)},"sid":${quote(sid)},"scope":"${if (all) "all" else "session"}"}""",
            "application/json; charset=utf-8")
        publish("model", """{"model":${quote(m)},"scope":"${if (all) "all" else "session"}"}""", sid)
        if (all) publish("settings", """{"mode":${quote(settings.permissionMode)}}""")
    }

    /** `GET /api/models?sid=` —— 列网关上的模型，给顶栏的模型切换器用。 */
    private fun models(ex: HttpExchange) {
        val sid = pick(querySid(ex))
        val list = runCatching { sessions[sid]?.engine?.models() ?: emptyList() }.getOrDefault(emptyList())
        // "使用中"要按**这条会话**的模型标：按会话换模型之后，全局那个可能已经不是它在用的了，
        // 照全局标出来的结果是 chip 显示"本地 7B"、列表里打勾的却是另一行。
        val cur = sessions[sid]?.engine?.settings?.model ?: settings.model
        send(ex, 200, list.joinToString(",", "[", "]") { m ->
            """{"id":${quote(m)},"current":${m == cur}}"""
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
    private fun newSessionId(at: File? = null): String {
        val session = Session("pc" + System.nanoTime().toString(16).take(8), at ?: settings.workspaceFile())
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
                    """"sub":${quote(ev.sub)},"media":${ev.media.joinToString(",", "[", "]") { quote(it) }}}""",
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
                """"running":${sessions[m.id]?.running == true},"pinned":${m.pinned},"current":${m.id == cur},""" +
                """"hit":${quote(hit?.text ?: "")},"hitAt":${hit?.index ?: -1}}"""
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
        // 指定了工作区就**一定新建一条**：复用手边那条空的会把它悄悄挪到别的目录，
        // 用户下一条消息就落到了他没选的地方。目录打不开要明确报错，
        // 不能"退回默认工作区"—— 那等于把人送进一个他没选的仓库里写文件。
        val want = Body(ex).str("ws")
        if (want.isNotBlank()) {
            val ok = runCatching { File(want).canonicalFile }.getOrNull()?.takeIf { it.isDirectory }
            if (ok == null) {
                send(ex, 200, """{"ok":false,"error":${quote("这个目录打不开：" + want)}}""",
                    "application/json; charset=utf-8"); return
            }
            val newId = newSessionId(ok)
            val md = sessions[newId]?.engine?.session?.mode ?: settings.permissionMode
            send(ex, 200, """{"ok":true,"id":${quote(newId)},"mode":${quote(md)},"reused":false}""",
                "application/json; charset=utf-8")
            publish("opened", """{"id":${quote(newId)},"title":"新会话","mode":${quote(md)}}""", newId)
            publish("sessions", "{}", newId)
            return
        }
        val idle = synchronized(this) {
            currentId()?.let { s -> sessions[s] }?.takeIf { m ->
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

    private fun queueJson(sid: String, q: List<Pair<String, List<String>>>): String =
        """{"sid":${quote(sid)},"items":[""" + q.joinToString(",") { (t, im) ->
            """{"t":${quote(t)},"imgs":${im.joinToString(",", "[", "]") { quote(it) }}}"""
        } + "]}"

    /** `POST /api/unqueue` {sid,at} —— 撤回排在第 at 位那一句（还没发出去才算撤回）。 */
    private fun unqueue(ex: HttpExchange) {
        val b = Body(ex)
        val sid = pick(b.str("sid"))
        val at = b.str("at").toIntOrNull() ?: -1
        val m = sessions[sid]
        if (m == null) {
            send(ex, 200, """{"ok":false,"error":"没有这条会话"}""", "application/json; charset=utf-8"); return
        }
        val q = synchronized(m.queue) { if (at in m.queue.indices) m.queue.removeAt(at); m.queue.toList() }
        send(ex, 200, """{"ok":true}""", "application/json; charset=utf-8")
        publish("queue", queueJson(sid, q), sid)
    }

    /** `GET /api/trash` —— 回收站列表（删除其实是移进来的，见 [SessionIndex.delete]）。 */
    private fun trashList(ex: HttpExchange) {
        send(ex, 200, SessionIndex.listTrash().joinToString(",", "[", "]") { m ->
            """{"name":${quote(m.file.name)},"id":${quote(m.id)},"title":${quote(m.title)},"""+
                """"messages":${m.messages},"updated":${m.updated},"bytes":${m.file.length()}}"""
        }, "application/json; charset=utf-8")
    }

    /** `POST /api/untrash` {name} —— 把一条会话放回历史列表。 */
    private fun untrashSession(ex: HttpExchange) {
        val name = Body(ex).str("name")
        val r = SessionIndex.untrash(name)
        val id = r.getOrNull()
        send(ex, 200, if (id != null) """{"ok":true,"id":${quote(id)}}"""
        else """{"ok":false,"error":${quote(r.exceptionOrNull()?.message ?: "放不回去")}}""",
            "application/json; charset=utf-8")
        if (id != null) publish("sessions", "{}", null)
    }

    /** `POST /api/purge` {name} —— 彻底删掉回收站里的某一条（界面上要二次确认）。 */
    private fun purgeTrash(ex: HttpExchange) {
        val ok = SessionIndex.purge(Body(ex).str("name"))
        send(ex, 200, """{"ok":$ok}""", "application/json; charset=utf-8")
    }

    /**
     * `POST /api/delmsg` {sid,index} —— 删掉某一句和它带出来的一切（见 [Engine.deleteAt]）。
     *
     * 跑着的时候不许删：引擎线程正在往同一段历史上写，删完它下一轮读到的就不是刚才那份。
     */
    private fun deleteMessage(ex: HttpExchange) {
        val b = Body(ex)
        val sid = pick(b.str("sid"))
        val i = b.str("index").toIntOrNull() ?: -1
        val m = sessions[sid]
        val (n, err) = when {
            m == null -> 0 to "没有这条会话"
            m.running -> 0 to "这条会话正在跑，先停止再删"
            else -> m.engine.deleteAt(i)
        }
        send(ex, 200, if (err.isEmpty()) """{"ok":true,"removed":$n}"""
        else """{"ok":false,"error":${quote(err)}}""", "application/json; charset=utf-8")
        if (err.isEmpty()) publish("sessions", "{}", sid)
    }

    /** `POST /api/pin` {id,pinned} —— 置顶 / 取消置顶。 */
    private fun pinSession(ex: HttpExchange) {
        val b = Body(ex)
        val id = b.str("id")
        val on = b.str("pinned").let { it == "true" || it == "1" }
        val ok = SessionIndex.pin(id, on)
        send(ex, 200, if (ok) """{"ok":true}""" else """{"ok":false,"error":"没有这条会话"}""",
            "application/json; charset=utf-8")
        if (ok) publish("sessions", "{}", id)
    }

    /**
     * `POST /api/tool` {sid,name,on} —— 这条会话开/关一把工具。
     *
     * 按会话而不是全局：让 agent 只做只读调研的那条，不该手里还握着 shell；
     * 而"全局关掉 shell"太狠，另开一条正经干活的任务就没法用了。
     */
    private fun toolToggle(ex: HttpExchange) {
        val b = Body(ex)
        val sid = pick(b.str("sid"))
        val name = b.str("name")
        val on = b.str("on").let { it == "1" || it == "true" }
        val e = sessions[sid]?.engine
        if (e == null || name.isBlank()) {
            send(ex, 200, """{"ok":false,"error":"没有这条会话或工具名是空的"}""",
                "application/json; charset=utf-8"); return
        }
        val off = e.settings.toolsOff.toMutableList()
        if (on) off.remove(name) else if (name !in off) off.add(name)
        e.useSettings(e.settings.copy(toolsOff = off))
        e.persistNow()
        send(ex, 200, """{"ok":true,"off":${!on},"toolsOff":${off.joinToString(",", "[", "]") { quote(it) }}}""",
            "application/json; charset=utf-8")
        publish("tools", """{"sid":${quote(sid)}}""", sid)
    }

    /**
     * `POST /api/resume` {sid} —— 接着上次没跑完的那件事继续。
     *
     * 不重问：历史里已经有前面那几轮，发出去的是一句"接着做，别重做"。
     * 也不自动跑：只有用户点横幅上那颗按钮才走到这里。
     */
    private fun resumeRun(ex: HttpExchange) {
        val sid = pick(Body(ex).str("sid"))
        val engine = sessions[sid]?.engine
        val prompt = engine?.resumePrompt()
        if (prompt == null) {
            send(ex, 200, """{"ok":false,"error":"这条会话没有没跑完的现场"}""",
                "application/json; charset=utf-8"); return
        }
        // 横幅上沿用原来那句目标：续跑话术只是给模型的指令，
        // 拿它当"上次在做的事"显示，再被打断一次就会自指成绕口令。
        val (got, err) = startRun(sid, prompt, goal = engine?.runGoal)
        send(ex, 200, if (err == null) """{"ok":true,"sid":${quote(got)}}"""
        else """{"ok":false,"error":${quote(err)}}""", "application/json; charset=utf-8")
    }

    /** `POST /api/abandon` {sid} —— 丢掉那段没跑完的现场（不自动续跑）。 */
    private fun abandonRun(ex: HttpExchange) {
        val sid = pick(Body(ex).str("sid"))
        val e = sessions[sid]?.engine
        if (e == null) {
            send(ex, 200, """{"ok":false,"error":"没有这条会话"}""",
                "application/json; charset=utf-8"); return
        }
        e.clearRunState()
        send(ex, 200, """{"ok":true}""", "application/json; charset=utf-8")
        publish("sessions", "{}", sid)
    }

    /**
     * `POST /api/substop` {sid,label} —— 只停某一条子任务，父任务继续。
     *
     * 和"停止"那颗总闸的区别：总闸会把这一轮整个中止（包括正在写的正文），
     * 而这里只让那条跑偏的调研收尾 —— 父引擎拿到一句"子任务被中断"，接着干别的。
     */
    private fun stopSubtask(ex: HttpExchange) {
        val b = Body(ex)
        val sid = pick(b.str("sid"))
        val label = b.str("label")
        val e = sessions[sid]?.engine
        val ok = e != null && label.isNotBlank() && e.stopSub(label)
        val subs = (e?.subNames() ?: emptyList()).joinToString(",", "[", "]") { quote(it) }
        send(ex, 200, """{"ok":$ok,"error":${quote(if (ok) "" else "这条子任务已经不在跑了")},"subs":$subs}""",
            "application/json; charset=utf-8")
    }

    /** `GET /api/backups` —— 已有的备份包（含恢复之前自动留的那张"后悔药"）。 */
    private fun backupsJson(): String {
        val items = Backup.list()
        return "{\"ok\":true,\"items\":[" + items.joinToString(",") { b ->
            "{\"name\":" + quote(b.name) + ",\"at\":" + quote(b.at) + ",\"bytes\":" + b.bytes +
                ",\"entries\":" + b.entries + ",\"keysIncluded\":" + b.keysIncluded +
                ",\"reason\":" + quote(b.reason) + ",\"scopes\":[" +
                b.scopes.joinToString(",") { s -> quote(s) } + "]}"
        } + "]}"
    }

    /**
     * `POST /api/backup` {action:export|restore|delete, scopes, keys, name}
     *
     * 导出与恢复都动状态根：引擎每轮 `persist()`、恢复还会覆盖文件，
     * 所以**正在跑的会话一律挡掉**（让用户先停止）。这不是保守 ——
     * 边写边读出来的包"看着完整其实缺半轮"，而半轮恢复比失败更难查。
     *
     * 恢复成功之后要把内存里的引擎与设置丢掉：它们握着的是恢复前的文件，
     * 不丢就等于"恢复成功了，但界面还在用旧数据"。
     */
    private fun backupOp(ex: HttpExchange) {
        val b = Body(ex)
        val action = b.str("action")
        val busy = liveCount()
        if (action == "export" || action == "restore") {
            if (busy > 0) {
                val what = if (action == "export") "备份" else "恢复"
                send(ex, 200, "{\"ok\":false,\"error\":\"有 " + busy + " 条会话正在跑，先停止它们再" + what + "\"}",
                    "application/json; charset=utf-8")
                return
            }
        }
        when (action) {
            "export" -> {
                val r = runCatching { Backup.export(b.list("scopes"), b.str("keys") == "true") }
                if (r.isSuccess) {
                    val s = r.getOrThrow()
                    send(ex, 200, "{\"ok\":true,\"name\":" + quote(s.name) +
                        ",\"entries\":" + s.entries + ",\"bytes\":" + s.bytes + "}",
                        "application/json; charset=utf-8")
                } else {
                    send(ex, 200, "{\"ok\":false,\"error\":" + quote(r.exceptionOrNull()?.message ?: "导出失败") + "}",
                        "application/json; charset=utf-8")
                }
            }
            "restore" -> {
                val msg = Backup.restore(b.str("name"))
                if (msg.startsWith("ok")) {
                    synchronized(order) { order.clear() }
                    sessions.clear()
                    settings = PcSettings.load()
                    publish("sessions", "{}")
                    send(ex, 200, "{\"ok\":true,\"msg\":" + quote(msg) + "}",
                        "application/json; charset=utf-8")
                } else {
                    send(ex, 200, "{\"ok\":false,\"error\":" + quote(msg) + "}",
                        "application/json; charset=utf-8")
                }
            }
            "delete" -> {
                val msg = Backup.delete(b.str("name"))
                send(ex, 200, if (msg == "ok") "{\"ok\":true}"
                else "{\"ok\":false,\"error\":" + quote(msg) + "}", "application/json; charset=utf-8")
            }
            else -> send(ex, 200, "{\"ok\":false,\"error\":\"不认识的动作\"}",
                "application/json; charset=utf-8")
        }
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
            """"reasoningEffort":${quote(st.reasoningEffort)},""" +
            """"searchProvider":${quote(st.searchProvider)},""" +
            // 只报"有没有 key"，不报 key 本身：这份对象会整体发给浏览器
            """"hasSearchKey":${Search.key().isNotBlank()},""" +
            // 上下文窗口必须回得去：抽屉里那一格原来永远是空的，用户以为没配，
            // 而保存时 num() 把空串读成 0 —— 于是"打开设置再保存"就把窗口清零了。
            """"contextChars":${st.contextChars},""" +
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
        // 窗口只认正数：读成 0 会让"那圈占用"永远显示 0%，比留空更骗人
        body["searchProvider"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let {
            n = n.copy(searchProvider = it.trim().lowercase())
        }
        // 搜索 key 与 API 密钥同理：只落 HAOAI_HOME/searchkey，不进设置对象
        val skey = body["searchKey"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (skey.isNotEmpty()) runCatching { Env.searchKeyFile.writeText(skey) }
        body["contextChars"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()?.let {
            if (it > 0) n = n.copy(contextChars = it)
        }
        // 允许清空（"" = 不发 reasoning_effort），所以这里不判空
        body["reasoningEffort"]?.jsonPrimitive?.contentOrNull?.let {
            n = n.copy(reasoningEffort = it.trim().lowercase())
        }
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
