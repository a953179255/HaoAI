package com.haoai.pc

import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files

/**
 * 「专家市场」这条链在 HTTP 层是通的：界面上填地址 → 存进设置 → 清单按这个地址拉回来。
 *
 * 为什么单独一个类（[ExpertFeedTest] 只管解析）：那批只测了 `ExpertFeed.fetch`，
 * 于是两个"看着像功能坏了、其实压根没接线"的洞都没人判：
 * 1. `POST /api/settings` 不认 `expertFeed`（写不进去，市场永远空态）；
 * 2. `GET /api/settings` 不发这个键（前端与像素剧本判"开没开"读不到，
 *    就算写进去了判据也照样红 —— 和 `contextChars` 那次一模一样）。
 * 所以下面这条按**用户点的那条路**走：存 → 读回 → 拉清单 → 再清掉。
 */
class ExpertFeedApiTest {

    companion object {
        private lateinit var feed: HttpServer
        private lateinit var server: WebServer
        private lateinit var base: String
        private val http = HttpClient.newHttpClient()

        @BeforeClass
        @JvmStatic
        fun setUp() {
            val home = Files.createTempDirectory("haoai-feed-home").toFile()
            System.setProperty("haoai.home", File(home, "home").absolutePath)
            feed = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            feed.createContext("/experts.json") { ex ->
                val b = """{"cards":[{"id":"api-a","name":"接口来的卡","persona":"你是接口来的卡。"}]}"""
                    .toByteArray(Charsets.UTF_8)
                ex.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
                ex.sendResponseHeaders(200, b.size.toLong())
                ex.responseBody.use { it.write(b) }
                ex.close()
            }
            feed.start()
            PcSettings.save(PcSettings(
                workspace = File(home, "ws").apply { mkdirs() }.absolutePath,
                permissionMode = "auto"
            ))
            server = WebServer(PcSettings.load(), port = 0)
            base = "http://127.0.0.1:${server.start()}"
            server.schedules()?.stopped = true
        }

        @AfterClass
        @JvmStatic
        fun tearDown() {
            runCatching { server.stop() }
            runCatching { feed.stop(0) }
        }
    }

    private fun send(path: String, body: String?): String = http.send(
        HttpRequest.newBuilder(URI.create("$base$path"))
            .header("Content-Type", "application/json")
            .method(if (body == null) "GET" else "POST",
                if (body == null) HttpRequest.BodyPublishers.noBody()
                else HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString()
    ).body()

    @Test
    fun `the address the UI saves is the address the feed uses`() {
        val url = "http://127.0.0.1:${feed.address.port}/experts.json"
        send("/api/settings", """{"expertFeed":"$url"}""")
        val back = Json.parseToJsonElement(send("/api/settings", null)).jsonObject
        assertEquals("写进去的市场地址必须能从 GET 读回来（判「开没开」就读这个键）",
            url, back["expertFeed"]?.jsonPrimitive?.content)

        val d = Json.parseToJsonElement(send("/api/expertfeed", """{"op":"load"}""")).jsonObject
        assertTrue("市场该按刚存的地址拉到卡，实际回：" + d, d["ok"]?.jsonPrimitive?.content == "true")
        assertEquals("接口来的卡",
            d["cards"]!!.jsonArray[0].jsonObject["name"]?.jsonPrimitive?.content)

        // 空串是合法值：清掉之后这个页签就不该再发请求
        send("/api/settings", """{"expertFeed":""}""")
        assertEquals("清空要真能清掉，否则页签永远卡在旧源上", "",
            Json.parseToJsonElement(send("/api/settings", null)).jsonObject["expertFeed"]
                ?.jsonPrimitive?.content)
        val off = Json.parseToJsonElement(send("/api/expertfeed", """{"op":"load"}""")).jsonObject
        assertEquals("清空之后清单不该还回 ok", "false", off["ok"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a card imported from the feed is a local card with a fresh id`() {
        /*
         * 这一条判的是"市场→本地"那一步真的写了库（前端点的是 `/api/presets op:'import'`，
         * 参数就是清单里那张卡的原文）。只测 fetch 的话，"清单能看但装不上"这种半截功能
         * 会一路绿到像素剧本 —— 而像素剧本只有跑到那一步才红，归因要多花一小时。
         */
        val url = "http://127.0.0.1:${feed.address.port}/experts.json"
        send("/api/settings", """{"expertFeed":"$url"}""")
        val d = Json.parseToJsonElement(send("/api/expertfeed", """{"op":"load"}""")).jsonObject
        val card = d["cards"]!!.jsonArray[0].jsonObject["card"]?.jsonPrimitive?.content
        assertTrue("清单要把卡的原文一起交出来（导入用的是它，不是名字）：" + d, !card.isNullOrEmpty())
        val r = Json.parseToJsonElement(
            send("/api/presets", """{"op":"import","text":${quote(card!!)}}""")).jsonObject
        assertEquals("导入不该被拒：" + r, "true", r["ok"]?.jsonPrimitive?.content)
        val items = Json.parseToJsonElement(send("/api/presets", null)).jsonObject["items"]!!.jsonArray
        val mine = items.map { it.jsonObject }.firstOrNull { it["name"]?.jsonPrimitive?.content == "接口来的卡" }
        assertTrue("装进来之后「我的专家」里就该看得见它", mine != null)
        val newId = mine?.get("id")?.jsonPrimitive?.content
        assertTrue("而且必须换新 id，不能沿用市场里那个：$newId", newId != "api-a")
        send("/api/settings", """{"expertFeed":""}""")
    }

    /** 把一个字符串包成 JSON 字面量（卡原文里满是引号，不能手拼）。 */
    private fun quote(s: String): String = "\"" +
        s.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "").replace("\t", "\\t") + "\""
}
