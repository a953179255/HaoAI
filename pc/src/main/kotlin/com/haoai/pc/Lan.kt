package com.haoai.pc

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64
import java.util.concurrent.Executors

/**
 * 手机联动（局域网通道）：PC 开一个只给自家内网用的端点，手机配对之后能
 * **只读看**桌面这几条会话，并且**替人批**那条正在等的审批。
 *
 * 为什么值得做（也是为什么默认关）：这台 PC 上的 agent 会跑长任务，人往往在手机上；
 * "跑一半要人点允许"如果只能回工位点，自动化就等于每晚停一次。
 * 但把端口开到局域网 = 家里任何人（或任何 malware）都能打，所以三道闸：
 * 1. 默认关，要 `haoai lan on` 或在界面里显式打开；
 * 2. 配对要一次性、120 秒有效、错 8 次作废的 6 位码；
 * 3. 之后每个请求都要设备 token，**盘上只存哈希**，比较走常量时间。
 */
data class Device(val hash: String, val name: String, val added: Long, val lastSeen: Long)

object LanStore {
    private val rnd = SecureRandom()
    private val json = Json { ignoreUnknownKeys = true }

    /** 单独一个文件，不塞进 settings.json：`GET /api/settings` 是整份回给前端的，
     *  联动这类带凭据的状态不该出现在那个对象里。 */
    val file: File get() = File(Env.home, "lan.json")

    private fun load(): JsonObject = runCatching {
        json.parseToJsonElement(file.readText(Charsets.UTF_8)).jsonObject
    }.getOrElse { buildJsonObject { } }

    fun enabled(): Boolean = load()["enabled"]?.jsonPrimitive?.booleanOrNull ?: false
    fun port(): Int = load()["port"]?.jsonPrimitive?.intOrNull ?: DEFAULT_PORT

    fun save(on: Boolean, port: Int) {
        val keep = devices()
        write(on, port, keep)
    }

    private fun write(on: Boolean, port: Int, devs: List<Device>) {
        file.parentFile?.mkdirs()
        val text = buildJsonObject {
            put("enabled", on)
            put("port", port)
            put("devices", buildJsonArray {
                devs.forEach { d ->
                    add(buildJsonObject {
                        put("hash", d.hash)
                        put("name", d.name)
                        put("added", d.added)
                        put("lastSeen", d.lastSeen)
                    })
                }
            })
        }.toString()
        file.writeText(text, Charsets.UTF_8)
    }

    fun devices(): List<Device> = load()["devices"]?.jsonArray?.mapNotNull {
        val o = runCatching { it.jsonObject }.getOrNull() ?: return@mapNotNull null
        val h = o["hash"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        Device(
            h,
            o["name"]?.jsonPrimitive?.contentOrNull ?: "",
            o["added"]?.jsonPrimitive?.longOrNull ?: 0L,
            o["lastSeen"]?.jsonPrimitive?.longOrNull ?: 0L
        )
    } ?: emptyList()

    fun remove(hashPrefix: String): Boolean {
        val all = devices()
        val hit = all.firstOrNull { it.hash.startsWith(hashPrefix) } ?: return false
        write(enabled(), port(), all.filterNot { it.hash == hit.hash })
        return true
    }

    fun newToken(): String {
        val b = ByteArray(32)
        rnd.nextBytes(b)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    }

    fun hash(token: String): String =
        MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /** 配对：发一个名字，换回一次性明文 token（盘上只留哈希）。 */
    fun pair(name: String): Pair<Device, String> {
        val token = newToken()
        val d = Device(hash(token), name.take(40), System.currentTimeMillis(), System.currentTimeMillis())
        write(enabled(), port(), devices() + d)
        return d to token
    }

    /**
     * 认 token。命中就顺手更新 lastSeen。
     *
     * 比较走 `MessageDigest.isEqual`（常量时间）而不是 String==：
     * 这里比的是凭据，短路比较的耗时差是能被同网段的机器量出来的。
     */
    fun verify(token: String): Device? {
        if (token.isBlank()) return null
        val h = hash(token)
        val all = devices()
        val hit = all.firstOrNull {
            MessageDigest.isEqual(it.hash.toByteArray(Charsets.UTF_8), h.toByteArray(Charsets.UTF_8))
        } ?: return null
        write(enabled(), port(), all.map { if (it.hash == hit.hash) it.copy(lastSeen = System.currentTimeMillis()) else it })
        return hit
    }

    // ---- 配对码：一次性、120 秒、错 8 次作废 ----

    @Volatile
    private var code: String = ""

    @Volatile
    private var codeUntil: Long = 0

    @Volatile
    private var wrong: Int = 0

    fun issueCode(): Pair<String, Long> {
        val c = "%06d".format(rnd.nextInt(1_000_000))
        code = c
        codeUntil = System.currentTimeMillis() + CODE_TTL_MS
        wrong = 0
        return c to codeUntil
    }

    fun codeState(): Pair<String, Long> =
        if (System.currentTimeMillis() > codeUntil || code.isEmpty()) "" to 0L else code to codeUntil

    /** 返回 null = 码不对/已过期；调用方据此回 403。成功则一次性消费掉。 */
    fun redeemCode(input: String): Boolean {
        val (c, until) = codeState()
        if (c.isEmpty() || System.currentTimeMillis() > until) return false
        if (input.trim() != c) {
            wrong += 1
            if (wrong >= CODE_MAX_WRONG) {
                code = ""
                codeUntil = 0
            }
            return false
        }
        code = ""
        codeUntil = 0
        wrong = 0
        return true
    }

    private fun quote(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    const val DEFAULT_PORT = 8720
    const val CODE_TTL_MS = 120_000L
    const val CODE_MAX_WRONG = 8
}

/** 局域网端点要向主服务要的四件事。抽成接口是为了能在测试里给一个假的。 */
interface LanHost {
    /** 只读会话列表：id / 标题 / 工作区目录名 / 档位 / 是否在跑 / 消息数。 */
    fun sessionsJson(): String

    /** 单条会话的正文（截断后的），只读镜像。 */
    fun sessionJson(sid: String): String

    /** 当前所有待批的审批（含是哪条会话发起的）。 */
    fun pendingJson(): String

    /** 人从手机上做的决定。回一句话说明成没成。 */
    fun decide(id: String, decision: String): String
}

/**
 * 局域网 HTTP 面。只绑 0.0.0.0 是显式选择（`haoai lan on` 或界面上打开），
 * 关掉时这个进程根本不存在，而不是"开着但拒绝"。
 */
class LanServer(private val host: LanHost, private val wantPort: Int = LanStore.port()) {
    private var srv: HttpServer? = null
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "haoai-lan").apply { isDaemon = true } }

    val boundPort: Int get() = srv?.address?.port ?: wantPort

    fun start(): Boolean = runCatching {
        srv?.let { return true }
        val s = HttpServer.create(InetSocketAddress(wantPort), 0)
        s.executor = pool
        s.createContext("/") { ex -> route(ex) }
        s.start()
        srv = s
        true
    }.getOrElse {
        Env.log("lan", "端口 $wantPort 起不来（被占了？）：${it.message}")
        false
    }

    fun stop() {
        runCatching { srv?.stop(0) }
        srv = null
    }

    val running: Boolean get() = srv != null

    private fun route(ex: HttpExchange) {
        val path = ex.requestURI.path
        try {
            when {
                // 手机网页端先于鉴权：不让人先把页面打开，他连配对码往哪填都不知道。
                // 这份 HTML 里没有任何凭据，数据全走下面那些要 token 的 /lan/* 口。
                path == "/" || path == "/index.html" || path == "/phone" -> page(ex)
                path == "/lan/health" -> send(ex, 200, """{"ok":true,"service":"haoai-pc"}""")
                path == "/lan/pair" && ex.requestMethod == "POST" -> pair(ex)
                else -> {
                    val dev = LanStore.verify(ex.requestHeaders.getFirst("X-HaoAI-Token") ?: "")
                    if (dev == null) {
                        send(ex, 401, """{"ok":false,"error":"没有有效的设备令牌：先在 PC 上生成配对码再配一次"}""")
                        return
                    }
                    when {
                        path == "/lan/sessions" -> send(ex, 200, host.sessionsJson())
                        path == "/lan/session" -> send(ex, 200, host.sessionJson(query(ex, "sid")))
                        path == "/lan/pending" -> send(ex, 200, host.pendingJson())
                        path == "/lan/decide" && ex.requestMethod == "POST" -> decide(ex)
                        path == "/lan/unpair" && ex.requestMethod == "POST" -> {
                            val ok = LanStore.remove(dev.hash)
                            val msg = if (ok) "已解除这台设备的配对" else "没找到这台设备"
                            send(ex, 200, """{"ok":$ok,"note":${q(msg)}}""")
                        }
                        else -> send(ex, 404, """{"ok":false,"error":"没有这个接口"}""")
                    }
                }
            }
        } catch (e: Exception) {
            runCatching { send(ex, 500, """{"ok":false,"error":${q("出错了：" + (e.message ?: ""))}}""") }
        } finally {
            runCatching { ex.close() }
        }
    }

    private fun page(ex: HttpExchange) {
        val html = javaClass.classLoader.getResourceAsStream("ui/phone.html")?.readBytes()
            ?: "手机网页端资源缺失（打包时没带上 ui/phone.html）".toByteArray(Charsets.UTF_8)
        ex.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
        ex.responseHeaders.add("Cache-Control", "no-store")
        ex.sendResponseHeaders(200, html.size.toLong())
        ex.responseBody.use { it.write(html) }
    }

    private fun pair(ex: HttpExchange) {
        val body = readBody(ex)
        val code = field(body, "code")
        val name = field(body, "name").ifBlank { "未命名设备" }
        if (!LanStore.redeemCode(code)) {
            send(ex, 403, """{"ok":false,"error":"配对码不对或已经过期（120 秒内有效、只能用一次）"}""")
            return
        }
        val (dev, token) = LanStore.pair(name)
        send(
            ex, 200,
            """{"ok":true,"token":${q(token)},"device":${q(dev.name)},"hint":"把 token 存好，之后每个请求都带在 X-HaoAI-Token 头上"}"""
        )
    }

    private fun decide(ex: HttpExchange) {
        val body = readBody(ex)
        val id = field(body, "id")
        val decision = field(body, "decision")
        if (id.isBlank() || decision !in DECISIONS) {
            send(ex, 200, """{"ok":false,"error":"要 id 和 decision（${DECISIONS.joinToString("/")}）"}""")
            return
        }
        val note = host.decide(id, decision)
        send(ex, 200, """{"ok":${note.startsWith("已")},"note":${q(note)}}""")
    }

    /** 只暴露"这台机器在局域网里是哪个地址"，别的什么都不给。 */
    private fun readBody(ex: HttpExchange): String = runCatching {
        ex.requestBody.readBytes().toString(Charsets.UTF_8).take(8192)
    }.getOrDefault("")

    companion object {
        val DECISIONS = setOf("allow_once", "allow_session", "deny")

        private fun q(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

        private fun field(body: String, key: String): String =
            Regex(""""$key"\s*:\s*"([^"]*)"""").find(body)?.groupValues?.get(1) ?: ""

        private fun query(ex: HttpExchange, key: String): String =
            URI("http://x" + ex.requestURI.rawQuery?.let { "?$it" } ?: "").let { u ->
                runCatching {
                    u.query.split('&').first { it.startsWith("$key=") }.substringAfter('=')
                }.getOrDefault("")
            }

        private fun send(ex: HttpExchange, code: Int, body: String) {
            val b = body.toByteArray(Charsets.UTF_8)
            ex.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
            ex.sendResponseHeaders(code, b.size.toLong())
            ex.responseBody.use { it.write(b) }
        }

        /** 内网地址：只挑站点本地（192.168/10./172.16-31），不给公网出口。 */
        fun lanAddress(): String = runCatching {
            java.net.NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { n -> n.inetAddresses.toList() }
                .filter { it is java.net.Inet4Address && it.isSiteLocalAddress }
                .map { it.hostAddress }
                .firstOrNull() ?: "127.0.0.1"
        }.getOrDefault("127.0.0.1")

        fun probe(port: Int): Boolean = runCatching {
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build().send(
                HttpRequest.newBuilder(URI("http://127.0.0.1:$port/lan/health"))
                    .timeout(Duration.ofSeconds(2)).GET().build(),
                HttpResponse.BodyHandlers.ofString()
            ).statusCode() == 200
        }.getOrDefault(false)
    }
}
