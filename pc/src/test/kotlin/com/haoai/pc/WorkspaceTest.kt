package com.haoai.pc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
 * 工作区切换器：`GET /api/workspaces` 与 `POST /api/new {ws}`。
 *
 * 为什么单独测：这两条都在**决定一条会话属于哪个目录**，而"文件写到哪儿去"是这台机器上
 * 最不能出错的一件事。判据因此都落在盘上（那条会话自己的 workspace），不是"接口回了 200"。
 */
class WorkspaceTest {

    companion object {
        private lateinit var server: WebServer
        private lateinit var base: String
        private lateinit var home: File
        private lateinit var wsA: File
        private lateinit var wsB: File
        private val http = HttpClient.newHttpClient()

        @BeforeClass
        @JvmStatic
        fun setUp() {
            home = Files.createTempDirectory("haoai-ws-home").toFile()
            System.setProperty("haoai.home", File(home, "home").absolutePath)
            wsA = File(home, "a").apply { mkdirs() }
            wsB = File(home, "b").apply { mkdirs() }
            PcSettings.save(PcSettings(workspace = wsA.absolutePath, permissionMode = "auto"))
            server = WebServer(PcSettings.load(), port = 0)
            base = "http://127.0.0.1:${server.start()}"
            server.schedules()?.stopped = true
        }

        @AfterClass
        @JvmStatic
        fun tearDown() {
            runCatching { server.stop() }
        }

        private fun post(path: String, body: String): String = http.send(
            HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString()
        ).body()

        private fun get(path: String): String = http.send(
            HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
            HttpResponse.BodyHandlers.ofString()
        ).body()

        private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject
        private fun items(s: String) = obj(s)["items"]!!.jsonArray
        private fun sessions() = Json.parseToJsonElement(get("/api/sessions")).jsonArray.size

        /** 临时目录在 Windows 上可能带 8.3 短名，比较一律先 canonical 化并把反斜杠归一。 */
        private fun canon(p: String): String =
            runCatching { File(p).canonicalFile.absolutePath }.getOrDefault(p).replace('\\', '/')

        private fun esc(p: String): String = p.replace("\\", "\\\\").replace("\"", "\\\"")
    }

    private fun newId(body: String = "{}"): String {
        val d = obj(post("/api/new", body))
        assertTrue("开不出来：" + d["error"]?.jsonPrimitive?.content, d["ok"]!!.jsonPrimitive.content.toBoolean())
        return d["id"]!!.jsonPrimitive.content
    }

    private fun wsOf(sid: String): String =
        canon(obj(get("/api/state?sid=$sid"))["workspace"]!!.jsonPrimitive.content)

    @Test
    fun `the default workspace is listed even with zero sessions`() {
        val d = obj(get("/api/workspaces"))
        val ps = items(d.toString()).map { canon(it.jsonObject["p"]!!.jsonPrimitive.content) }
        assertTrue("默认工作区要在列表里，第一次用的人只有它", ps.contains(canon(wsA.absolutePath)))
        // current 只能是列表里的某一个（别的测试可能已经建过 B 的会话，这里不假设顺序）
        assertTrue("current 要报得出来", ps.contains(canon(d["current"]!!.jsonPrimitive.content)))
    }

    @Test
    fun `new with a workspace puts that session there and does not reuse the idle one`() {
        val inA = newId("""{"ws":"${esc(wsA.absolutePath)}"}""")
        val d = obj(post("/api/new", """{"ws":"${esc(wsB.absolutePath)}"}"""))
        assertTrue(d["ok"]!!.jsonPrimitive.content.toBoolean())
        val id = d["id"]!!.jsonPrimitive.content
        assertNotEquals("指定了目录就必须新建一条，不能把手边那条空的挪走", inA, id)
        assertFalse("更不能报 reused", d["reused"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(canon(wsB.absolutePath), wsOf(id))
        // 上一条要留在原地：换工作区不许把已有会话一起搬走
        assertEquals(canon(wsA.absolutePath), wsOf(inA))
    }

    /** 打不开的目录要明确报错。悄悄退回默认工作区等于把人送进一个他没选的仓库里写文件。 */
    @Test
    fun `a workspace that cannot be opened is an error, not a silent fallback`() {
        val before = sessions()
        val d = obj(post("/api/new", """{"ws":"${esc(File(home, "nope-dir").absolutePath)}"}"""))
        assertFalse(d["ok"]!!.jsonPrimitive.content.toBoolean())
        assertTrue("要说清是哪个目录打不开", (d["error"]?.jsonPrimitive?.content ?: "").contains("nope-dir"))
        assertEquals("失败不许留下一条会话", before, sessions())
    }

    @Test
    fun `without a workspace the idle empty session is still reused`() {
        // 这条测的是"一路点新任务会刷出一堆空会话"那个修复没被这次改动弄坏
        val a = obj(post("/api/new", "{}"))
        val b = obj(post("/api/new", "{}"))
        assertEquals("同一条空会话不该造两次", a["id"]!!.jsonPrimitive.content, b["id"]!!.jsonPrimitive.content)
        assertTrue(b["reused"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `the list groups sessions by directory with counts`() {
        newId()                                       // A
        newId("""{"ws":"${esc(wsB.absolutePath)}"}""")  // B
        val ps = items(get("/api/workspaces")).associate {
            canon(it.jsonObject["p"]!!.jsonPrimitive.content) to it.jsonObject["n"]!!.jsonPrimitive.content.toInt()
        }
        assertTrue("B 至少要有一条：" + ps, (ps[canon(wsB.absolutePath)] ?: 0) >= 1)
        assertTrue("A 也要在：" + ps, (ps[canon(wsA.absolutePath)] ?: 0) >= 1)
    }

    /**
     * 服务端报出去的路径与名字必须是合法 JSON。
     *
     * Windows 不让你建一个名字里带引号的目录，所以这条改测同样会进属性值与 JSON 的模型名：
     * `quote()` 漏了转义，整份 `/api/settings` 就解析不出来，界面上每个抽屉都会静默变空。
     */
    @Test
    fun `odd characters in a reported field survive the round trip`() {
        val weird = "带\"引号 与\\反斜杠 和中文"
        post("/api/settings", """{"model":"${esc(weird)}"}""")
        assertEquals(weird, obj(get("/api/settings"))["model"]!!.jsonPrimitive.content)
        // 列表也还得解析得出来
        assertTrue(items(get("/api/workspaces")).size >= 1)
    }
}
