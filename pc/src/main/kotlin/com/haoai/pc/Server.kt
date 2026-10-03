package com.haoai.pc

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
 * 前端 `/` 命令面板里的内置项：自定义技能不许占用这些名字
 * （技能重名闸 `skillsOp` 与下发给界面的 `taken` 字段都用它）。
 *
 * 以前这份只存在于服务端，前端 `index.html` 的 `CMDS` 表各写各的、**靠注释对齐**——
 * 2026-09-30 核对当天就漂了一处：前端 12 条、这份 11 条，少 `compact`。
 * 漂移的后果不是报错而是静默：技能可以叫 `compact`（重名闸拦不住），
 * 而前端内置在前，那个技能永远点不到。从这版起两份由 `UiContractTest` 钉住。
 */
internal val BUILTIN_CMDS = setOf(
    "plan", "ask", "auto", "new", "model", "stop", "clear", "compact", "export", "theme", "status", "help"
)

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
               private val maxRunning: Int = 4) : LanHost {

    @Volatile
    internal var settings = settings


    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)

    /** 局域网端点（手机联动）。默认不存在 —— 只有 lan.json 里显式打开才有。 */
    @Volatile
    private var lan: LanServer? = null

    /**
     * 审批/提问的等待状态机（S8）。原来散在这里的四个字段（seq / pending /
     * pendingRule / pendingPayload）与两份手写的 try/finally 全部收进
     * [ApprovalBroker] —— 超时因此变得可注入可测，死状态 `pendingRule` 顺手删掉。
     * 每台服务一个实例（测试同 JVM 起好几台，状态不许全局）。
     */
    private val approvals = ApprovalBroker({ ev, payload, sid -> publish(ev, payload, sid) })

    private val subscribers: MutableList<OutputStream> = Collections.synchronizedList(mutableListOf())

    /**
     * 一条被托管的会话：引擎 + 它此刻是否在跑。
     *
     * `running` 必须是**每条会话一个**，不能是服务级的一把旗：
     * 上一版服务级旗子加"切会话就 409"的组合虽然挡住了孤儿任务，代价是一次只能跑一件事，
     * 而这恰恰是参考实现们（codex 的多 agent、opencode 的多 session）都不接受的限制。
     */
    internal class Managed(val engine: Engine) {
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

    internal val sessions = ConcurrentHashMap<String, Managed>()

    /** 最近使用顺序（新的在前），用来在没指定 sid 时挑"当前会话"。 */
    internal val order = Collections.synchronizedList(mutableListOf<String>())

    
    /**
     * 常驻内存的会话条数上限。
     *
     * 每条会话在内存里是一个引擎 + 它的全部历史，开二十条不去管就是几百 MB。
     * 超出时把**最久没碰且没在跑**的请出去：历史本来就落盘在 `sessions/pc-<id>.json`，
     * 下次 `/api/open` 会按原 id 重建，用户看不出差别。
     */
    private val maxResident = 12

    internal fun liveCount(): Int = synchronized(order) { order.count { sessions[it]?.running == true } }

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
        // 把真端口留在盘上：`haoai lan code` 这类 CLI 子命令要找到活着的实例。
        // 写死 8712 是错的 —— --port 换一个端口、或者 8712 被占自动挪位之后，
        // CLI 会说"没有正在跑的服务"，而服务其实跑得好好的。
        runCatching { File(Env.home, "webport").writeText(server.address.port.toString()) }
        // 定时任务的线程跟着服务起落：daemon 线程，JVM 退了它自己就没了
        sched = Scheduler { s -> runSchedule(s) }.also { it.start() }
        // 多 Agent：读回实例状态、接上执行口、起收件箱线程（三件事必须一起做，见 wireAgents）
        wireAgents()
        // 上次开过就接着开：手机配对的 token 还在人手里，服务重启不该把人踢下线
        if (LanStore.enabled()) {
            lan = LanServer(this).also { if (!it.start()) lan = null }
        }
        return server.address.port
    }

    /** 调度线程。测试里也直接拿它 `tick(now)`，不用真等 5 秒。 */
    internal var sched: Scheduler? = null

    fun stop() {
        runCatching { File(Env.home, "webport").delete() }
        // 别把录屏的 ffmpeg 留成孤儿进程：那台机器会继续往盘上写
        runCatching { Recordings.stopAll() }
        runCatching { lan?.stop() }
        lan = null
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
    "/shared.js" -> sendFile(ex, "ui/shared.js", "text/javascript; charset=utf-8")
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
                "/api/rewindturn" -> rewindTurn(ex)
                "/api/regenerate" -> regenerate(ex)
                "/api/rollback" -> rollback(ex)
                "/api/attach" -> attach(ex)
                "/api/models" -> models(ex)
                "/api/model" -> modelSet(ex)
                "/api/rule" -> ruleEdit(ex)
                "/api/memory" -> memory(ex)
                "/api/memories" -> memories(ex)
                "/api/skills" -> skills(ex)
                "/api/mcp" -> mcp(ex)
                "/api/schedules" -> schedules(ex)
                "/api/sched/parse" -> schedParse(ex)
                "/api/files" -> files(ex)
                "/api/workspaces" -> workspaces(ex)
                "/api/compact" -> compactNow(ex)
                "/api/workflows" -> workflows(ex)
                "/api/presets" -> presets(ex)
                "/api/teams" -> teams(ex)
                "/api/agents" -> agents(ex)
                "/api/experts/library" -> expertLibrary(ex)
                "/api/kb" -> kb(ex)
                "/api/kbs" -> kbs(ex)
                "/api/kbfile" -> kbFile(ex)
                "/api/secrets" -> secrets(ex)
                "/api/hooks" -> hooks(ex)
                "/api/digest" -> send(ex, 200, Digest.json(),
                    "application/json; charset=utf-8")
                "/api/digest/export" -> digestExport(ex)
                "/api/runs" -> send(ex, 200, RunLedger.json(),
                    "application/json; charset=utf-8")
                "/api/usage" -> usageReport(ex)
                "/api/usage/export" -> usageExportXlsx(ex)
                "/api/file" -> fileOne(ex)
                "/api/img" -> imageFile(ex)
                "/api/media" -> mediaFile(ex)
                "/api/export" -> exportSession(ex)
                "/api/share" -> share(ex)
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
                "/api/gitstatus" -> gitStatus(ex)
                "/api/gitdiff" -> gitDiff(ex)
                "/api/gitstage" -> gitStage(ex)
                "/api/gitcommit" -> gitCommit(ex)
                "/api/checkpoints" -> checkpointsList(ex)
                "/api/rewind" -> rewindRun(ex)
                "/api/lan" -> lanStatus(ex)
                "/api/lan/toggle" -> lanToggle(ex)
                "/api/lan/code" -> lanCode(ex)
                "/api/lan/unpair" -> lanUnpair(ex)
                "/api/lan/allow" -> lanAllow(ex)
                "/api/shells" -> shellsList(ex)
                "/api/shell/tail" -> shellTail(ex)
                "/api/shell/open" -> shellOpen(ex)
                "/api/shell/send" -> shellSend(ex)
                "/api/shell/close" -> shellClose(ex)
                "/api/preview/state" -> previewState(ex)
                "/api/preview/frame" -> previewFrame(ex)
                "/api/preview/open" -> previewOpen(ex)
                "/api/preview/input" -> previewInput(ex)
                "/api/preview/pick" -> previewPick(ex)
                "/api/preview/close" -> previewClose(ex)
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

    internal fun write(os: OutputStream, event: String, data: String, sid: String?) {
        // sid 走 SSE 的 id: 字段 —— 浏览器把它挂到 MessageEvent.lastEventId 上，
        // 于是"这条事件属于哪个会话"不用改任何一个已有负载的格式就能带出去。
        val head = if (sid == null) "" else "id: $sid\n"
        os.write((head + "event: $event\ndata: $data\n\n").toByteArray(StandardCharsets.UTF_8))
        os.flush()
    }

    internal fun publish(event: String, data: String, sid: String? = null) {
        val dead = mutableListOf<OutputStream>()
        synchronized(subscribers) {
            subscribers.forEach { os -> runCatching { write(os, event, data, sid) }.onFailure { dead += os } }
        }
        dead.forEach { subscribers.remove(it) }
    }

    /** 当前会话 = 最近使用列表的头一个。 */
    private fun currentId(): String? = synchronized(order) { order.firstOrNull { sessions.containsKey(it) } }

    internal fun pick(sid: String): String = sid.ifBlank { currentId() ?: "" }

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
        val e = EngineFactory.build(session, settings, webGate(id) { made }, emit = { ev -> forward(id, ev) })
        made = e
        // 会话自己的模型与工具开关：构造时拿的是全局设置，恢复时补这一刀（工厂里）
        if (meta != null) EngineFactory.applySessionOverlay(e, settings, meta.model, meta.toolsOff)
        return e
    }

    internal fun stateJson(sid: String = ""): String {
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
        /*
         * 这条会话挂的是哪张专家卡也要报出去。
         * 之前只有落盘的会话文件里有 `preset`，`/api/state` 里没有 —— 于是前端拿到
         * "带角色的空会话"却不知道角色是谁，欢迎语与快捷提问永远画不出来
         * （字段在数据层有、接口不报，等于界面上没有）。
         */
        sb.append("\"preset\":\"").append(esc(e?.session?.preset ?: "")).append("\",")
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
        /*
         * 这一条**实际在用哪个模型**。降级是引擎在回合中间发生的：顶栏那颗芯片如果一直显示
         * 设置里那个模型，人回看界面就会以为"这句是主模型答的"，而它其实是备胎答的
         * （那条降级提示是 SSE 瞬时事件，跑完 hydrate 重画整屏就没了 —— 见 #111）。
         * 所以这里报的是**状态**而不是事件：刷新、切回来、 hydrate 之后它都还在。
         */
        sb.append("\"modelNow\":\"").append(esc(e?.modelNow ?: settings.model)).append("\",")
        sb.append("\"title\":\"").append(esc(e?.session?.title?.get() ?: "新会话")).append("\",")
        // 角色名要报**这条会话自己的**：顶栏那个标签如果显示的是"上一次选过的角色"，
        // 就等于在说一条没在跑的会话正在跑。
        sb.append("\"role\":").append(quote(e?.session?.role ?: "")).append(",")
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
        approvals.pendingFor(id).forEachIndexed { i, w ->
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
                .append(",\"notice\":").append(quote(m.notice))
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

    internal fun bodyOf(ex: HttpExchange): String =
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
    internal class Body(ex: HttpExchange) {
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

        /**
         * 对象数组（专家卡的快捷提问 `{title,desc,prompt}` 那类）。
         *
         * 为什么不让前端把三个字段拼成一个字符串塞进 `list`：那样按钮上的字与
         * 发出去的那句就又是同一句话了（Octop 分开是有道理的），而且拼串的分隔符
         * 早晚会撞进用户写的正文里。
         */
        fun objs(key: String): List<JsonObject> =
            obj[key]?.jsonArray?.mapNotNull { runCatching { it.jsonObject }.getOrNull() } ?: emptyList()

        /** 数字字段：缺失或写错类型都回落到给定的默认值（不猜）。 */
        fun num(key: String): Double? = obj[key]?.jsonPrimitive?.doubleOrNull
        fun bool(key: String): Boolean? = obj[key]?.jsonPrimitive?.booleanOrNull
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

    /**
     * 音视频附件：同样只收**这条会话工作区里**的文件，且必须魔数认得出。
     * 一条消息最多两个，单个上限与 /api/media 一致 —— 一个 4 GB 的原始录像
     * 不该被拖进会话（模型也不会看它，它只要路径）。
     */
    private fun insideMedia(sid: String, paths: List<String>): List<String> {
        if (paths.isEmpty()) return emptyList()
        val ws = runCatching {
            (sessions[pick(sid)]?.engine?.session?.workspace ?: settings.workspaceFile()).canonicalFile
        }.getOrNull() ?: return emptyList()
        return paths.mapNotNull { p ->
            val f = runCatching {
                if (File(p).isAbsolute) File(p).canonicalFile else File(ws, p).canonicalFile
            }.getOrNull() ?: return@mapNotNull null
            val inside = f.path == ws.path || f.path.startsWith(ws.path + File.separator)
            if (!inside || f.length() > MediaMime.MAX) null
            else if (MediaMime.sniff(f) == null) null else f.absolutePath
        }.take(2)
    }

    internal fun startRun(wantSid: String, text: String, cutTo: Int? = null,
                         named: String = "", fresh: Boolean = false,
                         images: List<String> = emptyList(),
                         goal: String? = null,
                         trigger: String = "手动",
                         media: List<String> = emptyList(),
                         /**
                          * 定时任务指定的那张专家卡（[Schedule.preset] 解析出来的）。
                          *
                          * 只在**新建会话**时生效：往一条已经存在的会话里塞句子时改人设，
                          * 等于把用户正在聊的那条悄悄换成另一个角色 —— 那比不换更糟。
                          * 定时任务永远 `fresh=true`，所以这条路一定会走到新建。
                          */
                         preset: Preset? = null): Pair<String, String?> {
        /*
         * 关着的卡不许跑**任何一条**路。/api/new 那边挡住了"新建会话"，但发任务、
         * 定时任务、重新生成三条入口都从这里进，只在一处判等于没判。
         * 返回 sid="" 是有意的：调用方按 "sid to null = 起来了" 解读，非空 reason 一律是拒。
         */
        if (preset != null && !preset.enabled)
            return "" to "「${preset.name}」已经关掉，去「专家」页打开再用"
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
                val target = use.ifBlank {
                    // 卡上写了目录就用它的：定时任务"每天整理那个仓库"靠的就是这一句
                    newSessionId(preset?.let { Presets.workspaceOf(it) }, preset)
                }
                val m = sessions[target] ?: Managed(engineFor(target)).also { sessions[target] = it }
                touch(target)
                // 清停止旗要和置 running 在同一把锁里：见 Engine.beginRun 的注释
                m.engine.beginRun()
                m.engine.runTrigger = trigger
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
        // 括号数是有测试的（ApprovalFlowTest 的"每一帧都得是合法 JSON"）：这里多打一个 }
        // 时浏览器 JSON.parse 抛异常，表现成"用户自己那句话不出现在对话流里"，而接口测试全绿。
        publish("user", """{"t":${quote(text)},"imgs":${images.joinToString(",", "[", "]") { quote(it) }},""" +
            """"media":${media.joinToString(",", "[", "]") { quote(it) }}}""", sid)
        val e = managed.engine
        Thread {
            publish("run", """{"running":true}""", sid)
            try {
                e.submit(text, images, goal, media)
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
                    val (_, qErr) = startRun(sid, next.first, null, "", false, next.second, trigger = "排队")
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
        val (sid, err) = startRun(
            wantSid, text,
            images = insideWorkspace(wantSid, b.list("images")),
            media = insideMedia(wantSid, b.list("media"))
        )
        if (err != null) {
            send(ex, 409, """{"ok":false,"error":${quote(err)}}""", "application/json; charset=utf-8")
            return
        }
        send(ex, 200, """{"ok":true,"sid":${quote(sid)}}""", "application/json; charset=utf-8")
    }

    internal fun queryOf(ex: HttpExchange, key: String): String = ex.requestURI.query
        ?.splitToSequence('&')
        ?.firstOrNull { it.startsWith("$key=") }
        ?.substringAfter('=')
        ?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
        ?: ""

    internal fun querySid(ex: HttpExchange): String = queryOf(ex, "sid")

    /**
     * 开一条新会话（只登记并立刻落盘，不起线程、不跑任务）。
     *
     * `preset` 是角色卡：模型、工作区、档位、人设一次带上。
     * 档位这里必须**当场定下来**并回给调用方 —— 界面上那个"新会话继承全局默认"的语义
     * 已经在 v0.44 修过一次（新会话顶上亮着上一条的档位＝骗人），带角色卡时同理。
     */
    // internal：团队会话（ServerExpert.newTeamSession）也要从这里起会话 —— 工厂/落盘/sessions
    // 登记只允许这一条路，放宽可见性而不是让第二处直连构造。
    internal fun newSessionId(at: File? = null, preset: Preset? = null): String {
        val session = Session("pc" + System.nanoTime().toString(16).take(8), at ?: settings.workspaceFile())
        session.mode = preset?.let { Presets.modeOf(it, settings.permissionMode) } ?: settings.permissionMode
        session.persona = preset?.persona.orEmpty()
        session.role = preset?.name.orEmpty()
        // 卡 id 也要落到会话上：Token 统计"按专家"按 id 归并，改名之后历史账还认得
        session.preset = preset?.id.orEmpty()
        var made: Engine? = null
        // 走工厂（S5/S7）：以前这里第三处直连 `Engine(...)`，`/api/new` 造完引擎就进了
        // sessions 表，engineFor 再也不会被调 —— session-start 钩子因此一声都不发
        // （HookTest 用 last="" 逮住的）。构造与会话级 overlay 只许在工厂里出现。
        val e = EngineFactory.build(session, settings, webGate(session.id) { made },
            emit = { ev -> forward(session.id, ev) })
        made = e
        // 角色卡带的模型：语义是"这条会话的初始模型"，toolsOff 不参与 ——
        // 用工厂的 overlay 表达（model 为空时它自己就跳过，与原来的 if 条件等价）。
        if (preset != null) {
            EngineFactory.applySessionOverlay(e, settings, preset.model, emptyList())
            /*
             * 卡上的运行参数（温度 / maxTokens / 每回合格子数）落到**这条会话**的设置副本上。
             * 走 useSettings 而不是直接赋 settings：那个 setter 会顺带重建 HTTP 客户端，
             * 而温度与 max_tokens 是构造客户端时烘进去的（Provider 的构造参数）——
             * 只改字段的话这条会话发出去的还是全局那份温度，卡上填的数字等于没填。
             */
            e.useSettings(Presets.applyTo(preset, e.settings))
        }
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
        // 判掉这条会话挂着的等待：审批给 deny（fail-closed），提问给空串（"用户没回答"）。
        // 语义与收摊都在 ApprovalBroker.abort 里，这里只拿账来写那句通知。
        val aborted = approvals.abort(sid)
        managed?.engine?.requestStop()
        publish(
            "notice", quote(
                if (managed?.running == true || aborted.total > 0)
                    "已请求停止${if (aborted.approvals > 0) "（顺手拒掉 ${aborted.approvals} 个待确认）" else ""}，正在收尾…"
                else "这条会话现在没有正在跑的任务。"
            ), sid
        )
        send(ex, 200, """{"ok":true,"pending":${aborted.total}}""", "application/json; charset=utf-8")
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
            // 这两类**不**从这条通用通道走：webGate 需要时直接 publish("approval"/"ask")
            // （:1944/:2002），连 pending 的 future 一起给。显式列出来是为了让这个 when
            // 保持穷尽 —— 以前这里是 else -> Unit，新加的 Ev 变体会被静默吞掉、编译器不吭声，
            // 表现就是"SSE 发了但前端永远收不到"（#111 的同族病）。见 UiContractTest。
            is Ev.ApprovalRequest -> Unit
            is Ev.AskRequest -> Unit
        }
    }

    /** 角色卡：存"人设 + 模型 + 工作区 + 档位"，新会话一键带上。见 [Presets]。 */
    private fun presets(ex: HttpExchange) {
        val b = Body(ex)
        if (ex.requestMethod != "GET") {
            /**
             * 数值型运行参数的读法：`-1` = "这张卡不管这项"。
             *
             * 三件事必须同时成立：界面上把输入框清空要能**清回** -1（不是留着旧值）、
             * 请求里根本没带这个键要**沿用**卡上原有的值（编辑弹窗只改了名字时，
             * 不能顺手把温度抹平）、越界的值要收进合理区间（模型不会因为你填了
             * 1e9 就高兴，只会在下一轮报"参数非法"）。
             */
            fun numOr(key: String, keep: Int, cap: Int): Int =
                b.num(key)?.toInt()?.let { if (it < 0) Preset.NO_OVERRIDE else it.coerceAtMost(cap) } ?: keep
            fun dblOr(key: String, keep: Double, cap: Double): Double =
                b.num(key)?.let { if (it < 0) Preset.NO_OVERRIDE.toDouble() else it.coerceAtMost(cap) } ?: keep
            when (b.str("op")) {
                "del" -> Presets.remove(b.str("id"))
                // 开关单独一条 op：卡"关掉"不是删，改天还要开回来，不该逼用户重填一遍表单
                "toggle" -> {
                    val p = Presets.find(b.str("id"))
                    if (p != null) Presets.update(p.copy(enabled = b.bool("on") ?: !p.enabled))
                }
                "dup" -> {
                    val p = Presets.find(b.str("id"))
                    if (p == null) {
                        send(ex, 200, """{"ok":false,"error":"那张卡不在了"}""",
                            "application/json; charset=utf-8"); return
                    }
                    if (Presets.load().size >= Presets.MAX) {
                        send(ex, 200, """{"ok":false,"error":${quote("角色卡最多 " + Presets.MAX + " 张，先删一张再复制")}}""",
                            "application/json; charset=utf-8"); return
                    }
                    Presets.update(Presets.duplicate(p))
                }
                "import" -> {
                    val (card, err) = Presets.importJson(b.str("text"))
                    if (card == null) {
                        send(ex, 200, """{"ok":false,"error":${quote(err ?: "导入失败")}}""",
                            "application/json; charset=utf-8"); return
                    }
                    if (Presets.load().size >= Presets.MAX) {
                        send(ex, 200, """{"ok":false,"error":${quote("角色卡最多 " + Presets.MAX + " 张，先删一张再导入")}}""",
                            "application/json; charset=utf-8"); return
                    }
                    Presets.update(card)
                }
                "export" -> {
                    // 导出的那份就是 importJson 能吃回来的那份 —— 一条判据钉着这个往返。
                    // 外面套一层 ok/card：前端的 post() 一律按 {ok,error} 解，裸卡片会被当成失败。
                    val p = Presets.find(b.str("id"))
                    if (p == null) {
                        send(ex, 200, """{"ok":false,"error":"那张卡不在了"}""",
                            "application/json; charset=utf-8"); return
                    }
                    send(ex, 200, """{"ok":true,"card":${Presets.exportJson(p)}}""",
                        "application/json; charset=utf-8"); return
                }
                else -> {
                    val name = b.str("name").trim()
                    val persona = b.str("persona").trim()
                    if (name.isBlank() && persona.isBlank()) {
                        send(ex, 200, """{"ok":false,"error":"至少给个名字或一段人设"}""",
                            "application/json; charset=utf-8"); return
                    }
                    val ws = b.str("workspace").trim()
                    // 现在就打不开的目录不许存进来：存进去之后每次用都要失败一次，
                    // 而失败发生在"我已经选好角色了"之后，最容易被当成角色卡坏了。
                    if (ws.isNotBlank() &&
                        runCatching { File(ws).canonicalFile }.getOrNull()?.isDirectory != true) {
                        send(ex, 200, """{"ok":false,"error":${quote("这个目录现在打不开：" + ws)}}""",
                            "application/json; charset=utf-8"); return
                    }
                    val id = b.str("id").ifBlank { Presets.newId() }
                    val old = Presets.find(id)
                    if (old == null && Presets.load().size >= Presets.MAX) {
                        send(ex, 200, """{"ok":false,"error":${quote("角色卡最多 " + Presets.MAX + " 张，先删一张")}}""",
                            "application/json; charset=utf-8"); return
                    }
                    // 绑定的知识库：只留**现在真存在**的那些。
                    // 为什么不因为"库不存在"就拒掉整次保存：库是会被人删的，
                    // 删了之后那张卡就打不开、改不动，比留着一个失效绑定更糟。
                    // 也不保留失效 id：界面上会摆一个点开是空的库名，看着像坏了。
                    val kbs = b.list("kbs").map { it.trim() }.filter { it.isNotEmpty() }
                        .filter { Knowledge.find(it) != null }.distinct().take(6)
                    val quick = b.objs("quick").mapNotNull { q ->
                        val t = (q["title"]?.jsonPrimitive?.contentOrNull ?: "").trim().take(40)
                        if (t.isEmpty()) null else QuickPrompt(
                            t,
                            (q["desc"]?.jsonPrimitive?.contentOrNull ?: "").trim().take(80),
                            (q["prompt"]?.jsonPrimitive?.contentOrNull ?: "").trim().take(600))
                    }.distinct().take(6)
                    Presets.update(
                        Preset(
                            id = id,
                            name = name.ifBlank { persona.take(12) },
                            persona = persona,
                            model = b.str("model").trim(),
                            workspace = ws,
                            mode = b.str("mode").trim().takeIf { it in Presets.MODES } ?: "",
                            // 专家卡的展示层字段：内置专家"启用"就是把这些一起带过来存成普通角色卡
                            desc = b.str("desc").trim(),
                            icon = b.str("icon").trim().take(4),
                            color = b.str("color").trim().take(9),
                            mbti = b.str("mbti").trim().uppercase().take(4),
                            quick = quick,
                            kbs = kbs,
                            // 表单没带 enabled 就沿用卡上原来的：编辑弹窗只管改名字，
                            // 顺手把一张关着的卡打开＝替用户做了他没做的决定
                            enabled = b.bool("enabled") ?: old?.enabled ?: true,
                            welcome = b.str("welcome").trim().take(400),
                            temperature = dblOr("temperature", old?.temperature ?: Preset.NO_OVERRIDE.toDouble(), 2.0),
                            maxTokens = numOr("maxTokens", old?.maxTokens ?: Preset.NO_OVERRIDE, 200000),
                            maxTurns = numOr("maxTurns", old?.maxTurns ?: Preset.NO_OVERRIDE, 500),
                            created = old?.created ?: System.currentTimeMillis()
                        )
                    )
                }
            }
        }
        send(ex, 200, Presets.json(), "application/json; charset=utf-8")
    }

    /**
     *
     * `engineOf` 由创建方给（引擎构造时还握不住自己的引用）。刻意**不**按 sid 去 `sessions`
     * 里查：那张表在会话超出驻留上限时会踢掉最久没用的那条，而被踢的引擎可能还在跑 ——
     * 那时闸口就该拿不到自己该通知的对象了。引用直接给，不绕地图。
     */
    private fun webGate(sid: String, engineOf: () -> Engine? = { sessions[sid]?.engine }): Gate = object : Gate {
        override fun approve(title: String, detail: String, kind: String): Boolean =
            approveRule(title, detail, kind, "", "*", null, null)

        override fun approveRule(
            title: String, detail: String, kind: String, tool: String, pattern: String,
            risk: RiskOf.Verdict?
        ): Boolean = approveRule(title, detail, kind, tool, pattern, risk, null)

        override fun approveRule(
            title: String, detail: String, kind: String, tool: String, pattern: String,
            risk: RiskOf.Verdict?, plan: HunkPlan?
        ): Boolean {
            // 等待（注册/推卡/超时/收摊）在状态机里；这里只做"答案怎么解读"：
            // 逐块勾选、落规则、把结论挂到紧接着的那条工具消息上。
            val res = approvals.awaitApproval(sid) { id ->
                approvalPayload(id, title, detail, kind, tool, pattern, risk, plan)
            }
            val timedOut = res.timedOut
            val ans = res.value
            /*
             * 逐块：把人勾的结果交回工具（写在 plan 上）。
             * 手机上点的永远是整条决定 ⇒ hunkKeep 给 null ⇒ 全部应用，与逐块功能出现之前一致。
             */
            val keep = hunkKeep(ans, plan)
            val noneKept = keep != null && keep.none { it }
            val allKept = keep != null && keep.all { it }
            if (keep != null && !noneKept) plan?.keep = keep
            val got = keep?.count { it } ?: 0
            val total = plan?.hunks?.size ?: 0
            /*
             * 结论挂到紧接着落的那条工具消息上。
             * 之前它只随 SSE 流一次：内联卡答完就收起，刷新之后卡没了，
             * 于是"这个文件到底是用户点头写的、还是自动写的、还是超时被拒的"查不出来。
             */
            engineOf()?.markApproval(
                when {
                    timedOut -> "超时未答，按拒绝处理"
                    noneKept -> "$total 块全被退回，这次一个字都没写"
                    keep != null && allKept -> "允许一次：$total 块全收"
                    keep != null -> "部分接受：$total 块里接了 $got 块、退了 ${total - got} 块"
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
            return !noneKept &&
                (keep != null || ans == "allow_once" || ans == "allow_session" || ans == "allow_rule")
        }

        /** 完整请求：对象选项 + 三个开关进载荷，桌面卡与 LAN 手机卡同读一份（B22）。 */
        override fun ask(req: AskReq): String =
            approvals.awaitAsk(sid, req).value

        /** 旧签名的壳（CLI/测试走默认桥也来这里）：缺省全按 true 处理。 */
        override fun ask(question: String, options: List<String>): String =
            ask(AskReq(question, options.map { AskOpt(it) }))

        /** 整批问卷：一条等待挂全部题，答案以数组 JSON 回流（见 [ApprovalBroker.parseBatchAnswers]）。 */
        override fun askBatch(title: String, questions: List<AskReq>, allowFree: Boolean): List<String> =
            ApprovalBroker.parseBatchAnswers(approvals.awaitAskBatch(sid, title, questions, allowFree).value)
    }

    private fun decide(ex: HttpExchange) {
        val b = Body(ex)
        val id = b.str("id")
        val decision = b.str("decision")
        val answer = b.str("answer")
        // isAsk/sid 都在 complete **之前**由状态机取好（complete 之后它可能已销号）
        val done = approvals.complete(id, if (answer.isNotBlank()) answer else decision)
        if (decision == "allow_session") {
            // "本任务都允许"只影响发起这条审批的那条会话，不该把别的会话也切成 auto。
            // 找不到那条等待时退回"当前会话"——与旧行为一致（stale 答复不该静默无效）。
            val sid = done.sid.ifBlank { currentId() ?: "" }
            sessions[sid]?.engine?.session?.mode = "auto"
            publish("mode", """{"mode":"auto"}""", sid)
        }
        send(ex, 200, """{"ok":true}""", "application/json; charset=utf-8")
    }

    internal fun mode(ex: HttpExchange) {
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
        val b = Body(ex)
        val want = b.str("ws")
        val presetId = b.str("preset")
        val preset = if (presetId.isBlank()) null else Presets.find(presetId)
        // 关着的卡与丢了的卡走同一条判定（Presets.usable）：静默改用别的卡、
        // 或者开一条没有角色的会话，都是把"我没生效"藏起来。
        val presetRefusal = Presets.usable(presetId)
        if (presetRefusal != null) {
            send(ex, 200, """{"ok":false,"error":${quote(presetRefusal)}}""",
                "application/json; charset=utf-8"); return
        }
        // 团队会话：成员不足/卡被删的报错在 newTeamSession 里说清（谁不见了）。
        // 团队不带工作区（成员各有所属），只有用户显式指定了 ws 才落到那个目录。
        val teamId = b.str("team")
        val team = if (teamId.isBlank()) null else Teams.find(teamId)
        if (teamId.isNotBlank() && team == null) {
            send(ex, 200, """{"ok":false,"error":"没有这个团队，可能已经被删掉了"}""",
                "application/json; charset=utf-8"); return
        }
        // 角色卡带了目录就必须用它的：打不开就明确报错，**不退回全局**。
        // 退回等于把用户送进一个他没选的仓库里写文件 —— 那比失败更糟。
        val presetWs = preset?.let { Presets.workspaceOf(it) }
        if (preset != null && preset.workspace.isNotBlank() && presetWs == null) {
            send(ex, 200, """{"ok":false,"error":${quote("这个角色卡的目录打不开：" + preset.workspace)}}""",
                "application/json; charset=utf-8"); return
        }
        if (want.isNotBlank() || preset != null || team != null) {
            val ok = if (want.isBlank()) presetWs else runCatching { File(want).canonicalFile }
                .getOrNull()?.takeIf { it.isDirectory }
            if (want.isNotBlank() && ok == null) {
                send(ex, 200, """{"ok":false,"error":${quote("这个目录打不开：" + want)}}""",
                    "application/json; charset=utf-8"); return
            }
            if (team != null) {
                // 响应（成功或失败原因）总在 newTeamSession 里发出
                newTeamSession(ex, team, ok)
                return
            }
            val newId = newSessionId(ok, preset)
            val eng = sessions[newId]?.engine
            val md = eng?.session?.mode ?: settings.permissionMode
            val role = quote(eng?.session?.role ?: "")
            send(ex, 200, """{"ok":true,"id":${quote(newId)},"mode":${quote(md)},""" +
                """"role":$role,"reused":false}""", "application/json; charset=utf-8")
            publish("opened", """{"id":${quote(newId)},"title":"新会话","mode":${quote(md)},"role":$role}""",
                newId)
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

    internal fun queueJson(sid: String, q: List<Pair<String, List<String>>>): String =
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
        val (got, err) = startRun(sid, prompt, goal = engine?.runGoal, trigger = "续跑")
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

    private fun lanStatusJson(): String {
        val (code, until) = LanStore.codeState()
        val devs = LanStore.devices().joinToString(",") { d ->
            """{"hash":${quote(d.hash.take(12))},"name":${quote(d.name)},"lastSeen":${d.lastSeen}}"""
        }
        val live = lan?.running == true
        return """{"ok":true,"enabled":${LanStore.enabled()},"running":$live,""" +
            """"port":${lan?.boundPort ?: LanStore.port()},"addr":${quote(LanServer.lanAddress())},""" +
            """"allowSend":${LanStore.allowSend()},""" +
            """"code":${quote(code)},"expires":$until,"devices":[$devs]}"""
    }

    private fun lanStatus(ex: HttpExchange) =
        send(ex, 200, lanStatusJson(), "application/json; charset=utf-8")

    private fun lanAllow(ex: HttpExchange) {
        val on = Body(ex).str("on") == "1"
        LanStore.saveAllowSend(on)
        send(ex, 200, lanStatusJson(), "application/json; charset=utf-8")
    }

    private fun lanToggle(ex: HttpExchange) {
        val want = Body(ex).str("on") == "1"
        val port = LanStore.port()
        if (!want) {
            runCatching { lan?.stop() }
            lan = null
            LanStore.save(false, port)
            send(ex, 200, """{"ok":true,"note":"已关掉局域网端点（手机那边会立刻连不上）"}""",
                "application/json; charset=utf-8")
            return
        }
        LanStore.save(true, port)
        val s = LanServer(this)
        if (!s.start()) {
            LanStore.save(false, port)
            send(ex, 200, """{"ok":false,"error":"端口 $port 起不来（被占了？换端口：haoai lan on --port 8899）"}""",
                "application/json; charset=utf-8")
            return
        }
        lan = s
        send(ex, 200, """{"ok":true,"note":"已开在 ${LanServer.lanAddress()}:$port —— 现在生成配对码给手机扫一次"}""",
            "application/json; charset=utf-8")
    }

    private fun lanCode(ex: HttpExchange) {
        if (lan?.running != true) {
            send(ex, 200, """{"ok":false,"error":"先打开局域网端点再生成配对码"}""",
                "application/json; charset=utf-8")
            return
        }
        val (c, until) = LanStore.issueCode()
        send(ex, 200, """{"ok":true,"code":${quote(c)},"expires":$until}""",
            "application/json; charset=utf-8")
    }

    private fun lanUnpair(ex: HttpExchange) {
        val prefix = Body(ex).str("hash")
        val ok = LanStore.remove(prefix)
        send(ex, 200, """{"ok":$ok,"note":${quote(if (ok) "已解除这台设备" else "没找到这台设备")}}""",
            "application/json; charset=utf-8")
    }

    // ---- LanHost：手机看到的三样东西（会话列表 / 单条正文 / 待批），全是只读镜像 ----

    /**
     * 镜像里刻意**不放**的东西：模型密钥（本来就不在会话里，但这里也不带任何设置）、
     * 工作区绝对路径（只给目录名）、工具原始输出（只给截断后的正文）。
     * 手机丢了的代价应该是"看不了这几条会话"，不是"读到 D 盘所有路径"。
     */
    override fun sessionsJson(): String {
        val rows = sessions.entries.sortedByDescending { it.value.engine.session.file.lastModified() }
            .joinToString(",") { (id, m) ->
                val e = m.engine
                """{"id":${quote(id)},"title":${quote(e.session.title.get())},""" +
                    """"mode":${quote(e.session.mode)},"running":${m.running},""" +
                    """"msgs":${e.messages().size},""" +
                    """"ws":${quote(e.session.workspace.name)},""" +
                    """"updated":${e.session.file.lastModified()}}"""
            }
        return """{"ok":true,"items":[$rows]}"""
    }

    override fun sessionJson(sid: String): String {
        val m = sessions[pick(sid)] ?: return """{"ok":false,"error":"没有这条会话"}"""
        val rows = m.engine.messages().takeLast(60).joinToString(",") { msg ->
            val text = (msg.content ?: "").take(600).replace('\u0000', ' ')
            """{"role":${quote(msg.role)},"name":${quote(msg.name)},"text":${quote(text)}}"""
        }
        return """{"ok":true,"sid":${quote(m.engine.session.id)},""" +
            """"title":${quote(m.engine.session.title.get())},"running":${m.running},""" +
            """"items":[$rows]}"""
    }

    /**
     * 手机上"在等人点"的那一份。**审批与提问都要给**：
     * 以前只挑 approval，于是 `ask_user` 那种"要绿色还是蓝色"在手机上根本不存在 ——
     * 而它和审批一样是把回合卡住的那一步，甚至更需要"人在外面随手答一句"。
     * 提问归一成 `{title, options:[..]}`，让两份手机客户端读同一组字段。
     */
    override fun pendingJson(): String {
        // mapNotNull 保住旧行为：不认识的事件 lanPendingRow 回 null，那一行不发给手机。
        val rows = approvals.rows()
            .mapNotNull { (id, w) -> lanPendingRow(id, w.ev, w.sid, w.payload) }
            .joinToString(",")
        return """{"ok":true,"items":[$rows]}"""
    }

    /** 跨端那一面要知道这条是审批还是提问（决定只能填那三个值，回答填的是文字本身）。 */
    override fun pendingKind(id: String): String = approvals.kind(id)

    /**
     * 手机上发来的活。`sid` 空 = 另起一条新会话（不去挤用户正在聊的那条）。
     * 这条口是"让这台电脑替手机动手"，所以三道闸：
     * ① LanStore.allowSend() 必须在电脑上显式勾过；② sid 先验格式再验存在
     * —— 它会变成 sessions 目录下的文件名，`../` 这种必须挡掉；
     * ③ 权限档位照旧生效：ask 档下这一样会冒出审批卡，手机批不了就卡在那儿等人。
     */
    override fun lanSend(sid: String, text: String): String {
        val want = sid.trim()
        if (want.isNotEmpty()) {
            if (!want.matches(Regex("^[A-Za-z0-9_-]{1,40}$")))
                return "会话 id 不对：在手机上重新点一条会话"
            if (sessions[want] == null && !SessionIndex.fileFor(want).isFile)
                return "没有这条会话了（电脑上大概已删掉），重新拉一次列表"
        }
        val (got, err) = startRun(want, text, fresh = want.isEmpty(), trigger = "手机")
        return err ?: "已交给电脑：会话 $got"
    }

    override fun digestJson(): String = Digest.json(20)

    override fun decide(id: String, decision: String): String {
        // isAsk 在 complete **之前**取好：complete 之后引擎可能立刻销号，
        // 事后补读 ev 会退化成审批那句回执（旧代码在注释里记过这个坑，状态机收进一处）。
        val done = approvals.complete(id, decision)
        if (!done.existed) return "这条已经不在等待了（可能刚在电脑上被处理）"
        if (done.already) return "这条已经答过了"
        return decideNote(done.isAsk, decision)
    }

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

    private fun sendFile(ex: HttpExchange, resource: String, type: String) {
        val bytes = javaClass.classLoader.getResourceAsStream(resource)?.readBytes()
            ?: "资源缺失：$resource".toByteArray(StandardCharsets.UTF_8)
        send(ex, 200, String(bytes, StandardCharsets.UTF_8), type)
    }

    internal fun send(ex: HttpExchange, code: Int, body: String, type: String) {
        val b = body.toByteArray(StandardCharsets.UTF_8)
        ex.responseHeaders.add("Content-Type", type)
        ex.sendResponseHeaders(code, b.size.toLong())
        ex.responseBody.use { it.write(b) }
    }

    private fun esc(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")
        .replace("\n", "\\n").replace("\r", "").replace("\t", "    ")

    internal fun quote(s: String): String = "\"" + esc(s) + "\""
}

/**
 * 一条待决 → 跨端那一面的一行 JSON。**这一段的写法本身是结论**：
 *
 * 它原来是手搓字符串拼出来的，ask 分支多写了一个 `}`。手机页 `r.json()` 当场抛，
 * 那句提示只在角落闪 9 秒，待批页看上去就是"电脑那边没在等人" —— 一个谁都不会怀疑到
 * 协议头上的症状。而 `LanTest` 那几条全绿，因为它断的是**假 host 手写的那份字符串**：
 * 桩和产品各错各的，两边一起把缺陷盖住。
 *
 * 所以两件事一起做：① 改用手感啰嗦但**结构上不可能括号不配对**的 JsonObject 构造；
 * ② 把它挪成顶层函数，让测试能对着产品真身（而不是桩）做 `Json.parseToJsonElement`。
 * 第三道保险在像素剧本 `ui-phoneask.json` 里：它把待批页"读不到"和"真的没有"分开判。
 */
internal fun lanPendingRow(id: String, ev: String, sid: String, payload: String): String? {
    val p = payload.takeIf { it.isNotBlank() } ?: "{}"
    val body: JsonObject = when (ev) {
        "approval" -> buildJsonObject {
            put("id", id); put("kind", "approval"); put("sid", sid)
            val a = runCatching { Json.parseToJsonElement(p).jsonObject }.getOrNull() ?: JsonObject(emptyMap())
            // 逐块的正文**不发到手机上**：那边只有三个整条按钮，把十几块 × 几十行 diff 塞进
            // 4 秒一次的轮询只是白占流量（手机用户关心的也不是每一块长什么样）。
            // 但数量要发 —— 不然人在手机上点"允许一次"时，并不知道自己是替 3 处改动一起点的。
            val n = runCatching { a["hunks"]?.jsonArray?.size ?: 0 }.getOrDefault(0)
            val light = a.filterKeys { it != "hunks" }.toMutableMap()
            if (n > 0) light["hunkCount"] = JsonPrimitive(n)
            put("payload", JsonObject(light))
        }
        // 提问归一成 {title, options:[{label,description}], 三开关}：两份手机客户端读同一组字段。
        // 旧字符串载荷（老夹具/旧会话重放）按 label 收下，不至于把整张卡弄空。
        // 批量问卷（batch:true）额外透传 questions + questionCount —— 手机通知据此改口
        // （"N 题问卷"），答题走网页；options 保持空数组（通知上不摆题面选项按钮）。
        "ask" -> buildJsonObject {
            val a = runCatching { Json.parseToJsonElement(p).jsonObject }.getOrNull()
            fun flag(k: String) = a?.get(k)?.jsonPrimitive?.contentOrNull != "false" // 缺省 = true
            put("id", id); put("kind", "ask"); put("sid", sid)
            put("payload", buildJsonObject {
                put("title", a?.get("question")?.jsonPrimitive?.contentOrNull ?: "")
                put("detail", "")
                put("options", buildJsonArray {
                    // 一个元素畸形也不能把整份列表带崩：这一句抛了就是 /lan/pending 500，
                    // 手机上表现成"待批页什么都看不见"，和本批要修的那个症状一模一样。
                    a?.get("options")?.jsonArray?.forEach { o ->
                        val lab = runCatching { o.jsonPrimitive.contentOrNull }.getOrNull()
                            ?: runCatching { o.jsonObject["label"]?.jsonPrimitive?.contentOrNull }.getOrNull()
                        if (lab.isNullOrEmpty()) return@forEach
                        val dsc = runCatching { o.jsonObject["description"]?.jsonPrimitive?.contentOrNull }
                            .getOrNull() ?: ""
                        add(buildJsonObject { put("label", lab); put("description", dsc) })
                    }
                })
                put("allowFreeText", flag("allowFreeText"))
                put("confirm", flag("confirm"))
                put("recommend", flag("recommend"))
                val batch = runCatching { a?.get("batch")?.jsonPrimitive?.contentOrNull == "true" }.getOrDefault(false)
                put("batch", batch)
                if (batch) {
                    put("questionCount", runCatching { a?.get("questions")?.jsonArray?.size ?: 0 }.getOrDefault(0))
                    // 题库整份过到手机页（本地循环的数据源）。broker 拼的载荷已经过过一轮
                    // JSON.parse，这里原样搬 —— 再手搓一遍就是第二次出错机会（S4 的教训）。
                    a?.get("questions")?.let { put("questions", it) }
                }
            })
        }
        else -> return null
    }
    return body.toString()
}

/**
 * 答完一句之后回给手机的那句话。审批与提问走的是同一个 `/lan/decide`，
 * 但话不能说反：像素验收里答完「绿色」之后，手机底下浮出来的曾是
 * 「已按你的决定放行：绿色」—— 那是"有人替某个操作开了绿灯"的意思，
 * 而这次只是回了一句选择题的答案。
 */
internal fun decideNote(isAsk: Boolean, value: String): String = when {
    // 批量问卷的答案是一串 JSON（`["选A","选B"]`）：把数组原文糊到手机上是内部格式外泄，
    // 人要的是"答完了"这个事实（题数从数组长度来，坏数据按 0 题只说"答完"）。
    isAsk && value.startsWith("[") ->
        "已把回答送回电脑（问卷答完 ${ApprovalBroker.parseBatchAnswers(value).size} 题）"
    isAsk -> "已把回答送回电脑：$value"
    // 逐块是**在电脑上**勾的：手机上那句"已按你的决定放行"这时候是假话（人只点了整条）。
    value.startsWith(HUNK_PREFIX) -> "已在电脑上逐块挑过，部分放行"
    // 点拒绝却回一句"已按你的决定放行：deny"是两处错：动词反了，还把内部码露给人看。
    // 手机屏幕上这句话是点完按钮唯一能看到的回执，它必须说人话。
    value == "deny" -> "已拒绝，这条不会执行"
    value in DECISION_CN -> "已按你的决定放行：" + DECISION_CN.getValue(value)
    else -> "已按你的决定送出：$value"
}

/** 那三个放行决定的中文名。手机端的按钮标签与这句回执都从这里说话，别再各写一份。 */
private val DECISION_CN = mapOf(
    "allow_once" to "允许一次",
    "allow_session" to "本任务都允许",
    "allow_rule" to "写入规则并允许"
)

/** 审批答复里带逐块勾选时的前缀。界面发的是 `partial:101`（一位一块，1=要）。 */
internal const val HUNK_PREFIX = "partial:"

/**
 * 审批卡的那份 JSON。
 *
 * 为什么不再手搓字符串：这份 payload 会被 `/lan/pending` 原样透给手机，而上一批正是
 * "手搓多一个括号"让手机页空白、`LanTest` 却因为断的是桩自己搓的那份而全绿。
 * 逐块之后 payload 里第一次带**多行 diff 文本**（含引号、反斜杠、中文），出事的面更大，
 * 所以从这一版起由 JsonObject 构造保证合法，测试直接 parse 产品真身。
 *
 * 分级在 payload 里仍是**两样东西**：`risk` 是给 CSS 挑颜色用的稳定码，`riskLabel` 是给人看的中文。
 * 合成一个字段就会有界面按中文匹配，于是"高危"改成"高风险"的同一分钟，红框静默消失。
 */
internal fun approvalPayload(
    id: String,
    title: String,
    detail: String,
    kind: String,
    tool: String,
    pattern: String,
    risk: RiskOf.Verdict?,
    plan: HunkPlan?
): String = buildJsonObject {
    put("id", id); put("title", title); put("detail", detail); put("kind", kind)
    put("tool", tool); put("pattern", pattern)
    if (risk != null) {
        put("risk", risk.code())
        put("riskLabel", RiskOf.label(risk.level))
        put("riskWhy", risk.why)
    }
    val hs = plan?.hunks ?: emptyList()
    // 只有一块的时候不摆勾选框（Hunks.plan 已经挡了，这里再兜一层）：界面上多一排勾选
    // 只会让人以为"这里有得挑"，而它能说的话"拒绝"按钮已经说了。
    if (hs.size >= Hunks.MIN) put("hunks", buildJsonArray {
        hs.forEach { h ->
            add(
                buildJsonObject {
                    put("no", h.no)
                    put("at", h.oldAt + 1)
                    put("stat", h.stat)
                    put("text", h.text())
                }
            )
        }
    })
}.toString()

/**
 * 从答复里取出逐块勾选。返回 null 有两种意思，都由调用方分：
 * 整条放行（`allow_once` 那几个）与**这张卡本来就没有勾选框**（plan 为 null）。
 *
 * 位数与块数对不上时一块都不应用：宁可少写也不能多写 —— 猜错方向的代价是把用户
 * 没点头的那几行盖进去，而那种写坏的现场是回不来的（快照只到上一版）。
 */
internal fun hunkKeep(ans: String, plan: HunkPlan?): List<Boolean>? {
    if (plan == null || !ans.startsWith(HUNK_PREFIX)) return null
    val bits = ans.substring(HUNK_PREFIX.length)
    if (bits.length != plan.hunks.size || bits.any { it != '0' && it != '1' }) return List(plan.hunks.size) { false }
    return bits.map { it == '1' }
}
