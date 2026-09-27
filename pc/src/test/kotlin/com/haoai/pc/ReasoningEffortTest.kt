package com.haoai.pc

import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * 思考强度（`reasoning_effort`）这条线。
 *
 * 为什么单独测：这个字段是**默认不发**的。本机 llama-server 与不少兼容网关收到
 * 不认识的字段会直接 400，一旦"界面上有个下拉框"就顺手把它永远发出去，
 * 现有能跑的部署会当场全坏。所以判据是两条：选了要发、没选要**根本不出现这个键**。
 */
class ReasoningEffortTest {

    companion object {
        private lateinit var gateway: HttpServer
        private lateinit var bodies: MutableList<String>
        private lateinit var server: WebServer
        private lateinit var base: String
        private val http = HttpClient.newHttpClient()

        @BeforeClass
        @JvmStatic
        fun setUp() {
            val home = Files.createTempDirectory("haoai-eff-home").toFile()
            System.setProperty("haoai.home", File(home, "home").absolutePath)
            bodies = mutableListOf()
            gateway = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            gateway.createContext("/v1/chat/completions") { ex ->
                bodies += String(ex.requestBody.readBytes(), Charsets.UTF_8)
                val b = ("data: " +
                    """{"choices":[{"index":0,"finish_reason":"stop","delta":{"content":"ok"}}]}""" +
                    "\n\ndata: [DONE]\n\n").toByteArray()
                ex.responseHeaders.add("Content-Type", "text/event-stream")
                ex.sendResponseHeaders(200, b.size.toLong())
                ex.responseBody.use { it.write(b) }
                ex.close()
            }
            gateway.start()
            PcSettings.save(PcSettings(
                workspace = File(home, "ws").apply { mkdirs() }.absolutePath,
                permissionMode = "auto",
                baseUrl = "http://127.0.0.1:${gateway.address.port}/v1"
            ))
            server = WebServer(PcSettings.load(), port = 0)
            base = "http://127.0.0.1:${server.start()}"
            server.schedules()?.stopped = true
        }

        @AfterClass
        @JvmStatic
        fun tearDown() {
            runCatching { server.stop() }
            runCatching { gateway.stop(0) }
        }
    }

    private fun provider(effort: String): Provider = Provider(
        "http://127.0.0.1:${gateway.address.port}/v1", "k", "m", 0.3, 0, effort
    )

    private fun sentKey(body: String): String? =
        Json.parseToJsonElement(body).jsonObject["reasoning_effort"]?.jsonPrimitive?.content

    @Test
    fun `choosing an effort sends exactly that field`() {
        bodies.clear()
        provider("high").chat(listOf(Msg("user", "hi")), emptyList()) {}
        assertEquals(1, bodies.size)
        assertEquals("high", sentKey(bodies.last()))
    }

    /** 反面同样要紧：没选的时候这个键要完全不存在，而不是发个空串。 */
    @Test
    fun `empty effort keeps the field out of the request entirely`() {
        bodies.clear()
        provider("").chat(listOf(Msg("user", "hi")), emptyList()) {}
        assertTrue(bodies.isNotEmpty())
        assertFalse("没选就不该出现这个键（兼容网关会 400）",
            bodies.last().contains("reasoning_effort"))
        assertFalse(Json.parseToJsonElement(bodies.last()).jsonObject.containsKey("reasoning_effort"))
    }

    /**
     * 选中的强度要真的走到线上。
     *
     * 这里刻意不调任何 getter：引擎握着的是构造时那份设置，`useSettings` 不重建 client
     * 的话界面显示 medium、发出去还是旧的 —— 那种 bug 只有看请求体才看得见。
     */
    @Test
    fun `settings choice reaches the wire`() {
        PcSettings.save(PcSettings.load().copy(reasoningEffort = "medium"))
        assertEquals("medium", PcSettings.load().reasoningEffort)
        bodies.clear()
        chatClient(PcSettings.load()).chat(listOf(Msg("user", "hi")), emptyList()) {}
        assertEquals(1, bodies.size)
        assertEquals("medium", sentKey(bodies.last()))
    }

    @Test
    fun `the web settings endpoint reads and writes it`() {
        post("""{"reasoningEffort":"low"}""")
        assertEquals("low", Json.parseToJsonElement(get()).jsonObject["reasoningEffort"]!!
            .jsonPrimitive.content)
        post("""{"reasoningEffort":""}""")
        assertEquals("空串要能清掉，不然改错了没法退回默认", "",
            Json.parseToJsonElement(get()).jsonObject["reasoningEffort"]!!.jsonPrimitive.content)
    }

    /**
     * 抽屉里摆出来的每一格都必须"读得到、写得回"。
     *
     * 起因是像素里看到「上下文窗口」那格永远是空的：`GET /api/settings` 压根没发这个字段，
     * 而保存时 `num()` 把空串读成 0 —— 于是"打开设置点保存"就把窗口清零。
     * 一个字段只出现在界面上、不出现在这两个方向里，就是界面在骗人。
     */
    @Test
    fun `every drawer field round-trips through the endpoint`() {
        val sent = """{"model":"glm-4-air","maxTokens":"1234","contextChars":"150000","reasoningEffort":"low"}"""
        post(sent)
        val back = Json.parseToJsonElement(get()).jsonObject
        assertEquals("glm-4-air", back["model"]!!.jsonPrimitive.content)
        assertEquals("1234", back["maxTokens"]!!.jsonPrimitive.content)
        assertEquals("150000", back["contextChars"]!!.jsonPrimitive.content)
        assertEquals("low", back["reasoningEffort"]!!.jsonPrimitive.content)
        // 0 / 空不能把窗口清掉：那圈占用会永远显示 0%，比留空更骗人
        post("""{"contextChars":"0"}""")
        assertEquals("150000", Json.parseToJsonElement(get()).jsonObject["contextChars"]!!.jsonPrimitive.content)
    }

    private fun post(body: String): String {
        val r = http.send(
            HttpRequest.newBuilder(URI.create("$base/api/settings"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString()
        )
        return r.body()
    }

    private fun get(): String = http.send(
        HttpRequest.newBuilder(URI.create("$base/api/settings")).GET().build(),
        HttpResponse.BodyHandlers.ofString()
    ).body()
}
