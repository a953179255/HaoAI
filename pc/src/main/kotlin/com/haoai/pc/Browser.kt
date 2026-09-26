package com.haoai.pc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * PC 浏览器控制 —— 桌面控制三步走的第一步（① 浏览器 ② 屏幕理解 ③ 点击级自动化）。
 *
 * 三条设计决定，都不是审美问题：
 *
 * 1. **一把 `browser` 工具 + 子命令**，不是一堆 `browser_click/browser_read/…`。
 *    手机端 19 把 `browser_*` 窄工具的教训写在缺口清单 S9：工具越多，模型选错得越多，
 *    schema 的固定开销也越大。ZCode 就是这么做的（一把 `js` + 一份 API 文档）。
 *
 * 2. **走 CDP，不引 Playwright/Selenium**。只用 `/json` 这一个 HTTP 接口 + 一条 WebSocket
 *    调试通道；WebSocket 自己实现（见 [CdpSocket]），因为这台机器上 Kotlin 编译器看不见
 *    `HttpClient.newWebSocket`（`javap` 看得见 —— 编译器与运行时的 API 视图不一致），
 *    为一个本地回环通道去引第三方库不值。
 *
 * 3. **独立 user-data-dir**。用用户自己的 Edge 配置目录会把登录态、历史、扩展全暴露给
 *    agent 的会话，而且 Chrome 不允许两个实例抢同一个 profile。
 *
 * 默认关：见 [HaoFlag.BROWSER_CONTROL]。能控制浏览器 = 能以你的身份点网页上任何按钮，
 * 包括"确认转账"那种，所以必须显式打开；打开后 navigate/click/type 仍逐条过权限闸。
 */
/** 把用户给的 selector / 文本安全地塞进 JS 字符串字面量。 */
private fun jsonQuote(s: String): String =
    "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

class BrowserTool : Tool(
    "browser",
    "控制一台本机浏览器（Edge/Chrome，走 CDP）。sub ∈ status|open|navigate|read|eval|click|type|screenshot|shot|tabs|close。shot = navigate + 截图一步到位。" +
        "read 拿正文，eval 跑 JS，screenshot 存 PNG 到工作区。首次调用会自动拉起一台用独立配置的浏览器。",
    buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("sub", buildJsonObject { put("type", "string") })
            put("url", buildJsonObject { put("type", "string") })
            put("selector", buildJsonObject { put("type", "string") })
            put("text", buildJsonObject { put("type", "string") })
            put("script", buildJsonObject { put("type", "string") })
            put("path", buildJsonObject { put("type", "string") })
            put("max_chars", buildJsonObject { put("type", "integer") })
        })
        put("required", kotlinx.serialization.json.buildJsonArray {
            add(kotlinx.serialization.json.JsonPrimitive("sub"))
        })
    },
    kind = "net",
    flag = HaoFlag.BROWSER_CONTROL
) {
    override fun run(args: JsonObject, ctx: ToolCtx): ToolResult {
        val sub = (req(args, "sub") ?: "").trim().lowercase()
        if (sub.isEmpty()) return fail("browser 缺少 sub。可用：${SUBS.joinToString("|")}")
        if (sub !in SUBS) return fail("不支持的 sub：$sub。可用：${SUBS.joinToString("|")}")

        val objectOf = req(args, "url") ?: req(args, "selector") ?: req(args, "script") ?: req(args, "path") ?: sub
        if (sub !in READ_ONLY) {
            val why = ctx.guard("browser", "$sub $objectOf", "浏览器操作：$sub", objectOf.take(300))
            if (why != null) return fail(why)
        }

        val browser = try {
            BrowserSession.getOrCreate()
        } catch (e: Exception) {
            return fail("拉不起浏览器：${e.message}")
        }

        return try {
            when (sub) {
                "status" -> ToolResult(browser.status())
                "tabs" -> ToolResult(browser.tabs())
                "open" -> ToolResult(browser.open())
                "navigate" -> {
                    val url = req(args, "url") ?: return fail("navigate 缺少 url")
                    ToolResult(browser.navigate(url))
                }
                "read" -> ToolResult(browser.read(req(args, "selector"), int(args, "max_chars", 8000)))
                "eval" -> {
                    val js = req(args, "script") ?: return fail("eval 缺少 script")
                    ToolResult(browser.eval(js))
                }
                "click" -> {
                    val sel = req(args, "selector") ?: return fail("click 缺少 selector")
                    ToolResult(browser.click(sel))
                }
                "type" -> {
                    val sel = req(args, "selector") ?: return fail("type 缺少 selector")
                    ToolResult(browser.type(sel, req(args, "text") ?: ""))
                }
                "shot" -> {
                    // 一条命令里做完 navigate + 等加载 + 截图：CLI 每次调用都是新进程，
                    // 分三条命令做会在"连哪个标签页"上出歧义（实测截到过旧页面）
                    val url = req(args, "url") ?: return fail("shot 缺少 url")
                    val rel = req(args, "path") ?: "browser-shot-${System.currentTimeMillis()}.png"
                    val nav = browser.navigate(url)
                    val f = ctx.resolve(rel)
                    ToolResult(nav + "\n" + browser.screenshot(f, rel))
                }
                "screenshot" -> {
                    val rel = req(args, "path") ?: "browser-shot-${System.currentTimeMillis()}.png"
                    ToolResult(browser.screenshot(ctx.resolve(rel), rel))
                }
                "close" -> {
                    browser.shutdown()
                    ToolResult("已关闭浏览器实例（配置目录留在临时目录，可随时删）")
                }
                else -> fail("不支持的 sub：$sub")
            }
        } catch (e: Exception) {
            fail("browser $sub 失败：${e.message ?: e.javaClass.simpleName}")
        }
    }

    companion object {
        private val SUBS = setOf(
            "status", "open", "navigate", "read", "eval", "click", "type", "screenshot", "shot", "tabs", "close"
        )
        private val READ_ONLY = setOf("status", "tabs", "read")
    }
}

/** 一个 CDP 会话。进程内单例：拉起一次，后续调用复用同一个浏览器。 */
class BrowserSession private constructor(
    private val proc: Process?,
    private val port: Int,
    private val profileDir: File?,
    private val exe: String
) {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    @Volatile
    private var sock: CdpSocket? = null

    @Volatile
    private var wsUrl: String? = null

    private val pending = ConcurrentHashMap<Int, java.util.concurrent.CompletableFuture<JsonObject>>()
    private var nextId = 1

    fun status(): String = runCatching {
        val v = get("/json/version")
        "浏览器在跑：${field(v, "Browser") ?: "?"}\n调试端口：127.0.0.1:$port\n可执行文件：$exe\n" +
            "独立配置目录：${profileDir?.absolutePath ?: "(复用了已有实例)"}"
    }.getOrElse { "CDP 无响应：${it.message}" }

    fun tabs(): String {
        val v = get("/json")
        val rows = Regex("""\{[^{}]*\}""").findAll(v).map { it.value }.filter { field(it, "type") == "page" }.toList()
        if (rows.isEmpty()) return "(没有页面)"
        return rows.joinToString("\n") { "- ${field(it, "title") ?: ""}  ${field(it, "url") ?: ""}" }
    }

    fun open(): String {
        val body = post("/json/new?about:blank")
        wsUrl = WS_URL.find(body)?.groupValues?.get(1)
        connect()
        return "已开一个新标签页"
    }

    fun navigate(url: String): String {
        val fixed = if (url.startsWith("http") || url.startsWith("file:") || url.startsWith("about:")) url
        else "https://$url"
        ensureConnected()
        var err = ""
        repeat(2) { attempt ->
            val r = call("Page.navigate", buildJsonObject { put("url", fixed) })
            err = r["result"]?.jsonObject?.get("errorText")?.jsonPrimitive?.content ?: ""
            // 首屏还在加载时导航会被 ERR_ABORTED 顶掉，等一下再试一次就过去了
            if (err != "net::ERR_ABORTED") return@repeat
            Thread.sleep(600)
            if (attempt == 0) return@repeat
        }
        waitForLoad()
        return "已打开 $fixed" + if (err.isBlank()) "" else "（$err）"
    }

    fun read(selector: String?, maxChars: Int): String {
        ensureConnected()
        val js = if (selector.isNullOrBlank()) {
            "document.title + String.fromCharCode(10) + document.body.innerText"
        } else {
            "(() => { const n = document.querySelector(" + jsonQuote(selector) + ");" +
                " return n ? (n.innerText || n.value || '') : '找不到元素：' + " + jsonQuote(selector) + "; })()"
        }
        return TextCap.middle(evaluateRaw(js), maxChars)
    }

    fun eval(js: String): String {
        ensureConnected()
        return evaluateRaw(js)
    }

    fun click(selector: String): String {
        ensureConnected()
        val js = "(() => { const n = document.querySelector(" + jsonQuote(selector) + ");" +
            " if (!n) return 'NOPE'; n.scrollIntoView(); n.click(); return 'OK'; })()"
        return if (evaluateRaw(js) == "OK") "已点击 $selector" else "点不到：$selector"
    }

    fun type(selector: String, text: String): String {
        ensureConnected()
        val js = "(() => { const n = document.querySelector(" + jsonQuote(selector) + ");" +
            " if (!n) return 'NOPE'; n.focus(); n.value = " + jsonQuote(text) + ";" +
            " n.dispatchEvent(new Event('input',{bubbles:true})); return 'OK'; })()"
        return if (evaluateRaw(js) == "OK") "已在 $selector 输入 ${text.length} 字" else "找不到输入框：$selector"
    }

    fun screenshot(target: File, rel: String): String {
        ensureConnected()
        val r = call("Page.captureScreenshot", buildJsonObject { put("format", "png") })
        val b64 = r["result"]?.jsonObject?.get("data")?.jsonPrimitive?.content
            ?: return "截图失败：CDP 没返回 data"
        target.parentFile?.mkdirs()
        target.writeBytes(Base64.getDecoder().decode(b64))
        return "截图已存 ${target.absolutePath}（${target.length()} 字节；再看内容用 path=\"$rel\"）"
    }

    fun shutdown() {
        runCatching { sock?.close() }
        sock = null
        wsUrl = null
        BrowserSession.forgetPort()
        proc?.let {
            it.destroy()
            if (!it.waitFor(3, TimeUnit.SECONDS)) it.destroyForcibly()
        }
    }

    /** 这个实例还活着吗（HTTP 端点能通就算）。 */
    fun probe(): Boolean = runCatching { get("/json/version").isNotBlank() }.getOrDefault(false)

    // ---- CDP 底层 ----

    private fun ensureConnected() {
        if (sock != null) return
        if (wsUrl == null) wsUrl = pickPageTarget(get("/json"))
        connect()
    }

    private fun connect() {
        if (sock != null) return
        val url = wsUrl ?: error("浏览器没有可调试的页面（先 browser open）")
        val u = URI(url)
        val s = CdpSocket.connect(u.host, if (u.port > 0) u.port else 80, if (u.path.isEmpty()) "/" else u.path)
        s.onMessage = { text -> dispatch(text) }
        sock = s
    }

    /**
     * 从 /json 里挑一个**真的能用的页面**当 target。
     *
     * Edge 开起来会同时挂着好几个 target：omnibox 扩展页、new tab 页、about:blank。
     * 连错的那个，表现是 navigate 回 net::ERR_ABORTED、read 拿到空串、screenshot 干脆不回 ——
     * 看着像"CDP 坏了"，其实是在跟一个不接受导航的 target 说话。
     */
    private fun pickPageTarget(list: String): String? {
        val blocks = Regex("""\{[^{}]*\}""").findAll(list).map { it.value }.toList()
        fun wsOf(b: String) = WS_URL.find(b)?.groupValues?.get(1)
        val pages = blocks.filter { field(it, "type") == "page" }
        return pages.firstOrNull {
            val u = field(it, "url") ?: ""
            u.isNotBlank() && u != "about:blank" && !u.startsWith("edge://") && !u.startsWith("chrome://")
        }?.let { wsOf(it) }
            ?: pages.firstOrNull { (field(it, "url") ?: "") == "about:blank" }?.let { wsOf(it) }
            ?: pages.firstOrNull()?.let { wsOf(it) }
            ?: WS_URL.find(list)?.groupValues?.get(1)
    }

    /** CDP 的回包按 id 配对；没有 id 的是事件，这里不订阅，直接丢。 */
    private fun dispatch(text: String) {
        val o = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        val id = o["id"]?.jsonPrimitive?.intOrNull ?: return
        pending.remove(id)?.complete(o)
    }

    private fun call(method: String, params: JsonObject): JsonObject {
        val s = sock ?: error("CDP 未连接")
        val id = nextId++
        val fut = java.util.concurrent.CompletableFuture<JsonObject>()
        pending[id] = fut
        s.send(buildJsonObject { put("id", id); put("method", method); put("params", params) }.toString())
        return try {
            fut.get(30, TimeUnit.SECONDS)
        } catch (e: Exception) {
            pending.remove(id)
            error("CDP $method 无响应：${e.message}")
        }
    }

    private fun evaluateRaw(expr: String): String {
        val r = call(
            "Runtime.evaluate",
            buildJsonObject {
                put("expression", expr)
                put("returnByValue", true)
                put("awaitPromise", true)
            }
        )
        r["exceptionDetails"]?.let { return "JS 抛错：${it.toString().take(300)}" }
        val res = r["result"]?.jsonObject?.get("result")?.jsonObject ?: return "(无结果)"
        return res["value"]?.jsonPrimitive?.content
            ?: res["description"]?.jsonPrimitive?.content
            ?: res["type"]?.jsonPrimitive?.content
            ?: "(无结果)"
    }

    private fun waitForLoad() {
        val deadline = System.currentTimeMillis() + 6000
        while (System.currentTimeMillis() < deadline) {
            val st = runCatching { evaluateRaw("document.readyState") }.getOrDefault("")
            if (st == "complete" || st == "interactive") return
            Thread.sleep(200)
        }
    }

    private fun get(path: String): String = http.send(
        HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path")).timeout(Duration.ofSeconds(8)).GET().build(),
        HttpResponse.BodyHandlers.ofString()
    ).body()

    private fun post(path: String): String = http.send(
        HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path"))
            .timeout(Duration.ofSeconds(8)).POST(HttpRequest.BodyPublishers.noBody()).build(),
        HttpResponse.BodyHandlers.ofString()
    ).body()

    private fun field(json: String, key: String): String? =
        Regex(""""$key"\s*:\s*"([^"]*)"""").find(json)?.groupValues?.get(1)

    companion object {
        private val WS_URL = Regex(""""webSocketDebuggerUrl"\s*:\s*"([^"]+)"""")

        @Volatile
        private var current: BrowserSession? = null

        /** 拉起（或复用）一台带 CDP 的浏览器。 */
        @Synchronized
        fun getOrCreate(): BrowserSession {
            current?.let { if (it.probe()) return it }
            // 端口记在状态目录里：CLI 每次是一条新进程（`haoai browser navigate` 然后 `screenshot`），
            // 不落盘就会每敲一条命令开一台新 Edge。agent 的 serve 进程重启后也能接上同一台。
            runCatching {
                val saved = Json.parseToJsonElement(portFile.readText()).jsonObject
                val p = saved["port"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                val exePath = saved["exe"]?.jsonPrimitive?.content ?: ""
                val prof = saved["profile"]?.jsonPrimitive?.content ?: ""
                if (p > 0 && attachable(p)) {
                    return BrowserSession(null, p, prof.ifBlank { null }?.let { File(it) }, exePath)
                        .also { current = it }
                }
            }
            val port = java.net.ServerSocket(0).use { it.localPort }
            val exe = browserExe() ?: error("没找到 msedge.exe / chrome.exe")
            /**
             * 配置目录**每次一个新号**（带端口）。共用一个目录看着省地方，实际是：
             * Chrome 不允许两个实例抢同一个 profile，上一个实例没退干净时新实例直接退出，
             * 表现就是"20 秒内 CDP 端口没起来"（本机实测踩过）。
             * 复用靠的是状态目录里的 browser.json 端口记录，不是靠共用 profile。
             */
            val profile = File(System.getProperty("java.io.tmpdir"), "haoai-browser-profile-$port")
            profile.mkdirs()
            val p = ProcessBuilder(
                exe, "--remote-debugging-port=$port",
                "--user-data-dir=${profile.absolutePath}",
                "--no-first-run", "--no-default-browser-check",
                "--disable-sync", "--disable-extensions",
                "about:blank"
            ).redirectErrorStream(true).start()
            val deadline = System.currentTimeMillis() + 20_000
            var ready = false
            val probeClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
            while (System.currentTimeMillis() < deadline && !ready) {
                ready = runCatching {
                    probeClient.send(
                        HttpRequest.newBuilder(URI("http://127.0.0.1:$port/json/version"))
                            .timeout(Duration.ofSeconds(2)).GET().build(),
                        HttpResponse.BodyHandlers.ofString()
                    ).statusCode() == 200
                }.getOrDefault(false)
                if (!ready) Thread.sleep(300)
            }
            if (!ready) {
                p.destroy()
                error("20 秒内 CDP 端口没起来（可能被组策略禁了远程调试，或有别的实例占着配置目录）")
            }
            runCatching {
                portFile.writeText(
                    buildJsonObject {
                        put("port", port)
                        put("exe", exe)
                        put("profile", profile.absolutePath)
                        put("started", System.currentTimeMillis())
                    }.toString()
                )
            }
            return BrowserSession(p, port, profile, exe).also { current = it }
        }

        private val portFile: File get() = File(Env.home, "browser.json")

        private fun attachable(port: Int): Boolean = runCatching {
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build().send(
                HttpRequest.newBuilder(URI("http://127.0.0.1:$port/json/version"))
                    .timeout(Duration.ofSeconds(2)).GET().build(),
                HttpResponse.BodyHandlers.ofString()
            ).statusCode() == 200
        }.getOrDefault(false)

        /** 关掉时把端口记录一起清掉，免得下次去连一个已经不存在的浏览器。 */
        fun forgetPort() {
            runCatching { portFile.delete() }
        }

        private fun browserExe(): String? = listOfNotNull(
            System.getenv("ProgramFiles(x86)")?.let { "$it\\Microsoft\\Edge\\Application\\msedge.exe" },
            System.getenv("ProgramFiles")?.let { "$it\\Microsoft\\Edge\\Application\\msedge.exe" },
            System.getenv("ProgramFiles")?.let { "$it\\Google\\Chrome\\Application\\chrome.exe" },
            System.getenv("ProgramFiles(x86)")?.let { "$it\\Google\\Chrome\\Application\\chrome.exe" }
        ).firstOrNull { File(it).isFile }
    }
}

/**
 * 极简 WebSocket 客户端（RFC6455 的一个子集），只为 CDP 服务。
 *
 * 只用得到：文本帧、客户端出帧必须掩码、服务端进帧不掩码、FIN 分片拼接、ping→pong。
 * 手写约 100 行的代价，远小于给一个本地回环调试通道引第三方库。
 */
class CdpSocket private constructor(
    private val sock: java.net.Socket,
    private val input: java.io.InputStream
) {

    private val output = sock.getOutputStream()
    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)

    @Volatile
    var onMessage: ((String) -> Unit)? = null

    init {
        val t = Thread { readLoop() }
        t.isDaemon = true
        t.name = "cdp-reader"
        t.start()
    }

    private fun readLoop() {
        val payload = java.io.ByteArrayOutputStream()
        try {
            while (!closed.get()) {
                val b1 = input.read()
                if (b1 < 0) break
                val fin = b1 and 0x80 != 0
                val op = b1 and 0x0F
                val b2 = input.read()
                if (b2 < 0) break
                var len = b2 and 0x7F
                if (len == 126) {
                    len = (input.read() shl 8) or input.read()
                } else if (len == 127) {
                    var l = 0L
                    repeat(8) { l = (l shl 8) or input.read().toLong() }
                    len = l.toInt()
                }
                if (len < 0) len = 0
                val buf = ByteArray(len)
                var got = 0
                while (got < len) {
                    val n = input.read(buf, got, len - got)
                    if (n < 0) break
                    got += n
                }
                when (op) {
                    0x1, 0x0, 0x2 -> payload.write(buf, 0, got)
                    0x8 -> {
                        closed.set(true)
                        return
                    }
                    0x9 -> sendFrame(0xA, buf)
                }
                if (fin && (op == 0x1 || op == 0x0 || op == 0x2)) {
                    val text = String(payload.toByteArray(), Charsets.UTF_8)
                    payload.reset()
                    if (text.isNotEmpty()) onMessage?.invoke(text)
                }
            }
        } catch (_: Exception) {
        } finally {
            closed.set(true)
        }
    }

    fun send(text: String) = sendFrame(0x1, text.toByteArray(Charsets.UTF_8))

    private fun sendFrame(op: Int, data: ByteArray) {
        if (closed.get() && op != 0x8) return
        val header = java.io.ByteArrayOutputStream()
        header.write(0x80 or op)
        val mask = ByteArray(4).also { java.security.SecureRandom().nextBytes(it) }
        when {
            data.size < 126 -> header.write(0x80 or data.size)
            data.size < 65536 -> {
                header.write(0x80 or 126)
                header.write((data.size shr 8) and 0xFF)
                header.write(data.size and 0xFF)
            }
            else -> {
                header.write(0x80 or 127)
                for (i in 7 downTo 0) header.write(((data.size.toLong() shr (8 * i)) and 0xFF).toInt())
            }
        }
        header.write(mask)
        val masked = ByteArray(data.size)
        for (i in data.indices) masked[i] = (data[i].toInt() xor mask[i % 4].toInt()).toByte()
        synchronized(output) {
            output.write(header.toByteArray())
            output.write(masked)
            output.flush()
        }
    }

    fun close() {
        if (closed.getAndSet(true)) return
        runCatching { sendFrame(0x8, ByteArray(0)) }
        runCatching { sock.close() }
    }

    companion object {
        fun connect(host: String, port: Int, path: String): CdpSocket {
            val sock = java.net.Socket()
            sock.connect(java.net.InetSocketAddress(host, port), 5000)
            val input = sock.getInputStream()
            val key = Base64.getEncoder().encodeToString(
                ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
            )
            val req = "GET $path HTTP/1.1\r\nHost: $host:$port\r\nUpgrade: websocket\r\n" +
                "Connection: Upgrade\r\nSec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\n\r\n"
            // 不能用 use：close() 会把整个 socket 关掉，握手之后就再也没连接了
            sock.getOutputStream().write(req.toByteArray(Charsets.US_ASCII))
            sock.getOutputStream().flush()
            /**
             * 头部**逐字节**读到 CRLFCRLF，不能用 BufferedReader。
             *
             * BufferedReader 会一次多读几 KB 进自己的缓冲区 —— 那里面很可能已经装着
             * 第一个 WebSocket 帧，之后从原始流里就再也读不到它了（表现为"第一条响应永远丢，
             * 调用超时"）。握手头只有几百字节，慢一点无所谓。
             */
            val head = StringBuilder()
            while (true) {
                val b = input.read()
                if (b < 0) error("CDP 握手无响应")
                head.append(b.toChar())
                if (head.length > 8000) error("CDP 握手响应异常地长")
                if (head.endsWith("\r\n\r\n")) break
            }
            val statusLine = head.toString().lineSequence().firstOrNull().orEmpty()
            if (!statusLine.contains("101")) error("CDP 握手失败：$statusLine")
            return CdpSocket(sock, input)
        }
    }
}
