package com.haoai.pc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 备份导出/恢复。
 *
 * 判据只认**数据本身**：恢复之后文件内容要一字不差、被改过的包要整包拒绝、
 * 坏路径不许写出状态根 —— "接口回了 200"一条都不算数。
 */
class BackupTest {

    companion object {
        private lateinit var server: WebServer
        private lateinit var base: String
        private val http = HttpClient.newHttpClient()
        private lateinit var gateway: com.sun.net.httpserver.HttpServer
        private val gatewayHits = java.util.concurrent.atomic.AtomicInteger(0)
        private var gatewayErr = ""

        @BeforeClass
        @JvmStatic
        fun setUp() {
            val home = Files.createTempDirectory("haoai-backup-home").toFile()
            System.setProperty("haoai.home", File(home, "home").absolutePath)
            // "卡住"那一句永远不答：要验证"正在跑时不许动状态根"，就得真有一条在跑
            gateway = com.sun.net.httpserver.HttpServer.create(
                java.net.InetSocketAddress("127.0.0.1", 0), 0
            )
            gateway.createContext("/v1/chat/completions") { ex ->
                val body = String(ex.requestBody.readBytes(), Charsets.UTF_8)
                gatewayHits.incrementAndGet()
                // 6 秒就够：停止只在回合边界生效，60 秒会让"停不下来"变成对设计的误解
                if (body.contains("卡住")) Thread.sleep(6_000)
                val b = ("data: {\"choices\":[{\"index\":0,\"finish_reason\":\"stop\",\"delta\":{\"content\":\"好了\"}}]}" + "\n\ndata: [DONE]\n\n").toByteArray()
                ex.responseHeaders.add("Content-Type", "text/event-stream")
                ex.sendResponseHeaders(200, b.size.toLong())
                ex.responseBody.use { it.write(b) }
                ex.close()
            }
            gateway.executor = java.util.concurrent.Executors.newCachedThreadPool()
            gateway.start()
            PcSettings.save(PcSettings(
                workspace = File(home, "ws").apply { mkdirs() }.absolutePath,
                model = "mock",
                baseUrl = "http://127.0.0.1:${gateway.address.port}/v1"
            ))
            server = WebServer(PcSettings.load(), port = 0)
            base = "http://127.0.0.1:${server.start()}"
            server.schedules()?.stopped = true
        }

        private fun sha256(b: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

        /** 把 zip 里每个 payload 换成新内容（manifest 原样搬过去）——模拟"包被人改过"。 */
        private fun rewrite(zip: File, targetPath: String, newBytes: ByteArray): File {
            val out = File(zip.parentFile, "tampered.zip")
            val entries = mutableMapOf<String, ByteArray>()
            ZipInputStream(zip.inputStream().buffered()).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    if (!e.isDirectory) entries[e.name] = z.readBytes()
                }
            }
            entries[targetPath] = newBytes
            ZipOutputStream(out.outputStream().buffered()).use { z ->
                entries.forEach { (name, bytes) ->
                    z.putNextEntry(ZipEntry(name)); z.write(bytes); z.closeEntry()
                }
            }
            zip.delete()
            out.renameTo(zip)
            return zip
        }
    }

    private fun seedState() {
        File(Env.sessionsDir, "pc-demo.json").writeText(
            """{"id":"demo","title":"备份里的那条会话","messages":[{"role":"user","content":"你好"}]}""")
        File(Env.home, "MEMORY.md").writeText("# 记忆\n备份要认这一行。\n")
        File(Env.home, "rules.json").writeText("""[{"pattern":"git push","action":"ask"}]""")
        File(Env.home, "skills.json").writeText("""{"commands":{"desc":"技能"}}""")
        // key 必须是 ASCII：中文 key 会被 HttpRequest 判成非法头值（见 headerKey 那条注释）
        File(Env.home, "apikey").writeText("sk-test-not-a-real-key")
        File(Env.home, "searchkey").writeText("search-test-key")
    }

    @Test
    fun `export then restore brings every file back byte for byte`() {
        seedState()
        val before = File(Env.home, "MEMORY.md").readText()
        val s = Backup.export(Backup.SCOPES, withKeys = false)
        assertTrue("包没落地：" + s, s.entries > 4)

        // 制造"数据丢了"：改记忆、删一条会话
        File(Env.home, "MEMORY.md").writeText("# 被改坏了\n")
        File(Env.sessionsDir, "pc-demo.json").delete()

        val msg = Backup.restore(s.name)
        assertTrue("恢复失败：" + msg, msg.startsWith("ok"))
        assertEquals("记忆没回来", before, File(Env.home, "MEMORY.md").readText())
        assertTrue("会话没回来", File(Env.sessionsDir, "pc-demo.json").isFile)
        assertTrue("恢复前没给自己留后悔药", Backup.list().any { it.name.startsWith("pre-restore-") })
    }

    @Test
    fun `keys stay out of the package unless asked for`() {
        seedState()
        val noKeys = Backup.export(Backup.SCOPES, withKeys = false)
        val withKeys = Backup.export(Backup.SCOPES, withKeys = true)
        val paths = mutableSetOf<String>()
        ZipInputStream(File(File(Env.home, "backups"), noKeys.name).inputStream().buffered()).use { z ->
            while (true) { val e = z.nextEntry ?: break; paths += e.name }
        }
        assertFalse("默认包里混进了 apikey", paths.any { it.endsWith("/apikey") || it.endsWith("/searchkey") })
        val m = Json.parseToJsonElement(
            ZipInputStream(File(File(Env.home, "backups"), withKeys.name).inputStream().buffered()).use { z ->
                while (true) { val e = z.nextEntry ?: break; if (e.name == "manifest.json") return@use String(z.readBytes()) }
                ""
            }).jsonObject
        assertEquals("勾了含 Key 但 manifest 没说", "true", m["keysIncluded"]?.jsonPrimitive?.content)
    }

    @Test
    fun `one tampered byte refuses the whole package and touches nothing`() {
        seedState()
        val s = Backup.export(Backup.SCOPES, withKeys = false)
        val zip = File(File(Env.home, "backups"), s.name)
        // 改掉 payload 里的一条内容，manifest 原样不动（= 抢包的手法）
        rewrite(zip, "payload/memory/MEMORY.md", "# 偷偷换掉的\n".toByteArray())

        File(Env.home, "MEMORY.md").writeText("# 现在盘上的这份\n")
        val msg = Backup.restore(s.name)
        assertTrue("改过的包居然被恢复了：" + msg, msg.contains("拒绝"))
        assertEquals("拒绝恢复时不该动盘上一个字节",
            "# 现在盘上的这份\n", File(Env.home, "MEMORY.md").readText())
    }

    @Test
    fun `an entry trying to escape the state root is refused`() {
        seedState()
        val evil = "payload/settings/../../evil-outside.txt"
        val bytes = "跑到状态根外面去\n".toByteArray()
        val zip = File(File(Env.home, "backups"), "evil.zip")
        val manifest = buildJsonObject {
            put("format", 1)
            put("kind", "haoai-pc")
            put("name", "evil.zip")
            put("at", "20260101-000000")
            put("keysIncluded", false)
            put("scopes", buildJsonArray { add("settings") })
            put("entries", buildJsonArray {
                addJsonObject { put("path", evil); put("size", bytes.size); put("sha256", sha256(bytes)) }
            })
        }.toString()
        ZipOutputStream(zip.outputStream().buffered()).use { z ->
            z.putNextEntry(ZipEntry(evil)); z.write(bytes); z.closeEntry()
            z.putNextEntry(ZipEntry("manifest.json")); z.write(manifest.toByteArray()); z.closeEntry()
        }

        val msg = Backup.restore("evil.zip")
        assertTrue("越界路径居然被放行：" + msg, msg.contains("拒绝"))
        assertFalse("状态根外面被写出了文件", File(Env.home.parentFile, "evil-outside.txt").exists())
    }

    @Test
    fun `a package from a newer or foreign app is refused`() {
        seedState()
        val s = Backup.export(Backup.SCOPES, withKeys = false)
        val zip = File(File(Env.home, "backups"), s.name)
        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(zip.inputStream().buffered()).use { z ->
            while (true) { val e = z.nextEntry ?: break; if (!e.isDirectory) entries[e.name] = z.readBytes() }
        }
        val m = Json.parseToJsonElement(entries["manifest.json"]!!.toString(Charsets.UTF_8)).jsonObject
        val newer = buildJsonObject {
            m.forEach { (k, v) -> put(k, v) }
            put("format", 99)
        }.toString()
        ZipOutputStream(zip.outputStream().buffered()).use { z ->
            entries.forEach { (n, b) ->
                val payload = if (n == "manifest.json") newer.toByteArray() else b
                z.putNextEntry(ZipEntry(n)); z.write(payload); z.closeEntry()
            }
        }
        val msg = Backup.restore(s.name)
        assertTrue("更老的端读了更新格式的包：" + msg, msg.contains("拒绝"))
        assertTrue("拒绝理由要说清是格式/来源问题：" + msg,
            msg.contains("格式版本") || msg.contains("不是 PC 端的"))
    }

    @Test
    fun `http layer refuses while a session is running`() {
        seedState()
        // 先把 SSE 接上：submit 的 catch 只 publish("err")，光看 /api/state 看不见为什么没跑
        val evs = java.util.Collections.synchronizedList(mutableListOf<String>())
        val th = Thread {
            runCatching {
                val inp = java.net.URI.create(base + "/api/events").toURL().openStream()
                java.io.BufferedReader(java.io.InputStreamReader(inp, Charsets.UTF_8)).use { r ->
                    while (true) { val line = r.readLine() ?: break; evs += line }
                }
            }
        }
        th.isDaemon = true
        th.start()
        Thread.sleep(800)

        val started = obj(post("/api/task", "{\"text\":\"卡住：先别管这个\"}"))
        assertEquals("没起得来：" + started, "true", started["ok"]?.jsonPrimitive?.content)
        val sid = started["sid"]?.jsonPrimitive?.content ?: error("task 没回 sid：" + started)
        val runningBody = waitRunning(sid)
        if (runningBody == null) {
            error("等不到在跑。网关收到 " + gatewayHits.get() + " 次请求；SSE=" +
                evs.filter { it.contains("err") || it.contains("event") }.take(8) +
                "；最后状态=" +
                http.send(HttpRequest.newBuilder(URI.create(base + "/api/state?sid=" + sid)).GET().build(),
                    HttpResponse.BodyHandlers.ofString()).body().take(600))
        }

        // 导出与恢复都要动状态根，正在跑时引擎每轮 persist —— 边写边读出来的包是半轮的
        for (action in listOf("export", "restore")) {
            val r = obj(post("/api/backup", "{\"action\":\"" + action + "\",\"name\":\"x.zip\",\"scopes\":[\"memory\"]}"))
            assertEquals("$action 竟然在跑着时放行了：" + r, "false", r["ok"]?.jsonPrimitive?.content)
            val why = r["error"]?.jsonPrimitive?.content ?: ""
            assertTrue("$action 的拒绝理由要说清是在跑：" + why, why.contains("正在跑"))
        }

        post("/api/stop", "{\"sid\":\"" + sid + "\"}")
        assertTrue("停不下来", waitIdle(sid))
        val ok = obj(post("/api/backup", "{\"action\":\"export\",\"scopes\":[\"memory\"]}"))
        assertEquals("停下来之后该能导出：" + ok, "true", ok["ok"]?.jsonPrimitive?.content)
    }

    /** 非 ASCII 的 key 不能以"invalid header value"的面目出现 —— 得说是 key 的问题。 */
    @Test
    fun `a CJK api key fails with an actionable message`() {
        val p = Provider("http://127.0.0.1:1/v1", "sk-中文粘进来了", "mock", 0.3, 0, "")
        val r = runCatching { p.chat(listOf(Msg("user", "hi")), emptyList()) {} }
        val msg = r.exceptionOrNull()?.message ?: ""
        assertTrue("没有说清是 key 的问题：" + msg, msg.contains("非 ASCII"))
        assertFalse("不该走到网络（端口 1 连不上会是另一种错）：" + msg, msg.contains("连接失败"))
    }

    /** 最小实验：不经服务端，直接拿客户端打一次假网关 —— 分清"没到网关"是哪一头的锅。 */
    @Test
    fun `provider reaches the fake gateway directly`() {
        val hits0 = gatewayHits.get()
        val client = chatClient(PcSettings.load())
        val r = runCatching {
            client.chat(listOf(Msg("user", "卡住：直连一次")), emptyList()) { }
        }
        assertTrue("直连就失败了：" + r.exceptionOrNull()?.javaClass?.name + " " +
            r.exceptionOrNull()?.message, r.isSuccess)
        assertEquals("网关一次都没收到", hits0 + 1, gatewayHits.get())
    }

    private fun state(sid: String): JsonObject =
        obj(http.send(HttpRequest.newBuilder(URI.create(base + "/api/state?sid=" + sid)).GET().build(),
            HttpResponse.BodyHandlers.ofString()).body())

    private fun waitRunning(sid: String, ms: Long = 8_000): String? {
        val until = System.currentTimeMillis() + ms
        var last = ""
        while (System.currentTimeMillis() < until) {
            last = http.send(HttpRequest.newBuilder(URI.create(base + "/api/state?sid=" + sid)).GET().build(),
                HttpResponse.BodyHandlers.ofString()).body()
            if (obj(last)["running"]?.jsonPrimitive?.content == "true") return last
            Thread.sleep(150)
        }
        return null
    }

    private fun waitIdle(sid: String, ms: Long = 20_000): Boolean {
        val until = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < until) {
            if (state(sid)["running"]?.jsonPrimitive?.content != "true") return true
            Thread.sleep(150)
        }
        return false
    }

    private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject

    private fun post(path: String, body: String): String = http.send(
        HttpRequest.newBuilder(URI.create(base + path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
        HttpResponse.BodyHandlers.ofString()
    ).body()
}
