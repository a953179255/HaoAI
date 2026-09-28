package com.haoai.pc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.AfterClass
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

/**
 * 凭据条目的判据。
 *
 * 这一层唯一要守住的主张：**明文只进磁盘，绝不进任何响应**。
 * 所以最要紧的几条不是"能不能改"，而是"从界面能读到的每一个接口里搜不搜得到那串 key" ——
 * 泄密这类事一旦回退是静默的，只有把"搜不到"写成断言才会被发现。
 */
class SecretsTest {

    companion object {
        private lateinit var server: WebServer
        private lateinit var base: String
        private lateinit var home: File
        private val http = HttpClient.newHttpClient()
        private const val SECRET = "sk-super-secret-8842abcdef"

        @BeforeClass
        @JvmStatic
        fun setUp() {
            home = Files.createTempDirectory("haoai-secrets-home").toFile()
            System.setProperty("haoai.home", File(home, "home").absolutePath)
            File(home, "home").mkdirs()
            PcSettings.save(PcSettings(workspace = File(home, "ws").apply { mkdirs() }.absolutePath))
            server = WebServer(PcSettings.load(), port = 0)
            base = "http://127.0.0.1:${server.start()}"
            server.schedules()?.stopped = true
        }

        @AfterClass
        @JvmStatic
        fun tearDown() {
            runCatching { server.stop() }
            Secrets.slots().forEach { Secrets.set(it, "") }
        }
    }

    private fun post(path: String, body: String): String = http.send(
        HttpRequest.newBuilder(URI.create(base + path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
        HttpResponse.BodyHandlers.ofString()
    ).body()

    private fun get(path: String): String = http.send(
        HttpRequest.newBuilder(URI.create(base + path)).GET().build(), HttpResponse.BodyHandlers.ofString()
    ).body()

    private fun slotOf(id: String) = Secrets.find(id) ?: error("没有这个条目：$id")

    private fun items(resp: String) =
        Json.parseToJsonElement(resp).jsonObject["items"]?.jsonArray?.toList() ?: emptyList()

    private fun itemOf(resp: String, id: String) = items(resp).firstOrNull {
        it.jsonObject["id"]?.jsonPrimitive?.contentOrNull == id
    }?.jsonObject ?: error("响应里没有 $id：$resp")

    // ---- 存储与掩码 ----

    @Test
    fun `a key reads back exactly and only the mask is shown`() {
        assertTrue(Secrets.set(slotOf("apikey"), "  $SECRET  ").isSuccess)
        assertEquals("首尾空白要清掉，其余一字不差", SECRET, Secrets.read(slotOf("apikey")))
        val m = Secrets.mask(SECRET)
        assertEquals("掩码只给前 2 后 2 与长度", "sk…" + SECRET.takeLast(2) + "（共 ${SECRET.length} 位）", m)
        assertFalse("掩码里不许出现中段", m.contains(SECRET.substring(4, SECRET.length - 4)))
    }

    @Test
    fun `a short key is masked whole`() {
        assertEquals("••••••", Secrets.mask("abc123"))
        assertEquals("没设就是空串，界面上写「未设置」而不是一个假掩码", "", Secrets.mask(""))
    }

    @Test
    fun `the search key the tool reads is the same file we write`() {
        Secrets.set(slotOf("searchkey"), "bocha-key-1")
        assertEquals("只有一份真源：工具读的就是这个文件",
            "bocha-key-1", Env.searchKeyFile.readText())
    }

    // ---- 接口面：明文不许出现在任何响应里 ----

    @Test
    fun `no endpoint ever returns the plaintext`() {
        post("/api/secrets", """{"id":"apikey","value":"$SECRET"}""")
        val list = get("/api/secrets")
        val settings = get("/api/settings")
        assertFalse("/api/secrets 漏了明文：" + list.take(160), list.contains(SECRET))
        assertFalse("/api/settings 漏了明文", settings.contains(SECRET))
        assertFalse("连掩码用的中段都不该整段出现",
            settings.contains(SECRET.substring(4, SECRET.length - 4)))
        val row = itemOf(list, "apikey")
        assertEquals("条目要能看出设没设", true, row["set"]?.jsonPrimitive?.content?.toBoolean())
        assertTrue("要给出掩码供辨认：" + row["mask"], (row["mask"]?.jsonPrimitive?.contentOrNull ?: "").contains("…"))
        assertTrue("要给出文件位置，人才知道去哪手删",
            (row["path"]?.jsonPrimitive?.contentOrNull ?: "").endsWith("apikey"))
    }

    @Test
    fun `clearing deletes the file so hasKey stops lying`() {
        post("/api/secrets", """{"id":"apikey","value":"$SECRET"}""")
        assertTrue(Env.apiKeyFile.isFile)
        post("/api/secrets", """{"id":"apikey","value":""}""")
        assertFalse("撤掉=删文件，留个空文件会让 hasKey 说假话", Env.apiKeyFile.isFile)
        assertFalse(get("/api/settings").contains("\"hasKey\":true"))
        assertEquals(false, itemOf(get("/api/secrets"), "apikey")["set"]?.jsonPrimitive?.content?.toBoolean())
    }

    @Test
    fun `an unknown slot is a sentence not a crash`() {
        val r = post("/api/secrets", """{"id":"nope-not-a-key","value":"x"}""")
        assertTrue("要说清没有这个条目：" + r.take(90), r.contains("没有这个凭据条目"))
    }
}
