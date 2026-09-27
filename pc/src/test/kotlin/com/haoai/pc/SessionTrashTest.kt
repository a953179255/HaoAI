package com.haoai.pc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
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
 * 回收站：删掉的会话要能**从界面放回来**。
 *
 * 为什么单独测：`delete` 早就不是就地抹掉了（移进 `sessions/.trash`），
 * 但"可恢复"只写在 tooltip 上 —— 没有入口的恢复等于没有，删错一条只能去开文件管理器。
 * 判据不看接口返回 200，看磁盘上文件真的换了位置、内容一个字没变。
 */
class SessionTrashTest {

    companion object {
        private lateinit var server: WebServer
        private lateinit var base: String
        private lateinit var home: File
        private val http = HttpClient.newHttpClient()

        @BeforeClass
        @JvmStatic
        fun setUp() {
            home = Files.createTempDirectory("haoai-trash-home").toFile()
            System.setProperty("haoai.home", File(home, "home").absolutePath)
            PcSettings.save(PcSettings(
                workspace = File(home, "ws").apply { mkdirs() }.absolutePath,
                permissionMode = "auto", model = "mock",
                baseUrl = "http://127.0.0.1:1/v1"
            ))
            server = WebServer(PcSettings.load(), port = 0)
            base = "http://127.0.0.1:${server.start()}"
            server.schedules()?.stopped = true
        }

        @AfterClass
        @JvmStatic
        fun tearDown() {
            runCatching { server.stop() }
            runCatching { home.deleteRecursively() }
        }
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
    private fun arr(s: String) = Json.parseToJsonElement(s).jsonArray
    private fun ok(s: String) = obj(s)["ok"]?.jsonPrimitive?.content == "true"

    /* 带 ws 新建：/api/new 会复用"还空着的那条"，两次不传 ws 拿到的是同一个 id。 */
    private fun newSession(tag: String): String {
        val dir = File(home, "ws-" + tag).apply { mkdirs() }
        val ws = dir.absolutePath.replace("\\", "\\\\")
        val r = obj(post("/api/new", "{\"ws\":\"" + ws + "\"}"))
        return r["id"]!!.jsonPrimitive.content
    }

    private fun fileOf(id: String) = File(Env.sessionsDir, "pc-$id.json")

    private fun trashFile(id: String): File? =
        File(Env.sessionsDir, ".trash").listFiles()?.firstOrNull { it.name.endsWith("pc-$id.json") }

    @Test
    fun `delete moves the file into the trash and the list shows it`() {
        val id = newSession("t1")
        assertTrue("会话文件该先存在", fileOf(id).isFile)
        assertTrue(ok(post("/api/delete", """{"id":"$id"}""")))
        assertFalse("不该就地抹掉", fileOf(id).exists())
        val inTrash = arr(get("/api/trash")).firstOrNull {
            it.jsonObject["id"]?.jsonPrimitive?.content == id
        }?.jsonObject
        assertTrue("回收站列表里没有刚删的那条", inTrash != null)
        assertEquals(id, inTrash!!["id"]?.jsonPrimitive?.content)
    }

    /** 最值钱的一条：放回来之后内容一个字没变，而且又能被读到。 */
    @Test
    fun `restore puts the same file back with its history intact`() {
        val id = newSession("t2")
        assertTrue(ok(post("/api/delete", """{"id":"$id"}""")))
        val moved = trashFile(id) ?: error("删除后回收站里找不到文件")
        val bytes = moved.readBytes()
        assertTrue("放回前不该有同名文件", !fileOf(id).exists())
        val r = obj(post("/api/untrash", """{"name":"${moved.name}"}"""))
        assertTrue("放回失败：" + r["error"]?.jsonPrimitive?.content, ok(r.toString()))
        assertEquals(id, r["id"]?.jsonPrimitive?.content)
        assertTrue("文件没回到 sessions/", fileOf(id).isFile)
        assertEquals("内容不该在搬运中变一个字节", bytes.toList(), fileOf(id).readBytes().toList())
        assertFalse("回收站里不该还留一份", moved.exists())
        assertTrue("放回来的会话要重新出现在列表里",
            arr(get("/api/sessions")).any { it.jsonObject["id"]?.jsonPrimitive?.content == id })
    }

    /** `name` 是客户端给的：不能让它把任意文件搬进 sessions/ 目录。 */
    @Test
    fun `a name that points outside the trash is refused`() {
        val settings = File(Env.home, "settings.json")
        assertTrue("前提：状态根里得有 settings.json", settings.isFile)
        val r = obj(post("/api/untrash", """{"name":"../../settings.json"}"""))
        assertFalse("越界的名字居然被接了", ok(r.toString()))
        assertTrue((r["error"]?.jsonPrimitive?.contentOrNull ?: "").contains("回收站"))
        assertTrue("settings.json 被动过了", settings.isFile)
        assertFalse("sessions 目录里冒出了同名文件",
            File(Env.sessionsDir, "settings.json").exists())
    }

    @Test
    fun `restoring over a live session of the same id is refused`() {
        val id = newSession("t3")
        assertTrue(ok(post("/api/delete", """{"id":"$id"}""")))
        val moved = trashFile(id) ?: error("回收站里找不到")
        fileOf(id).writeText("""{"id":"$id","title":"外面那条","messages":[]}""")
        val r = obj(post("/api/untrash", """{"name":"${moved.name}"}"""))
        assertFalse("同 id 已经有一条在外面，放回不该把它覆盖掉", ok(r.toString()))
        assertTrue("覆盖不了就该说清楚原因：" + r["error"]?.jsonPrimitive?.content,
            (r["error"]?.jsonPrimitive?.contentOrNull ?: "").contains("同 id"))
        assertEquals("外面那条一个字都不该变", "外面那条",
            obj(fileOf(id).readText())["title"]?.jsonPrimitive?.content)
        assertTrue("回收站里那份要还在", moved.isFile)
    }

    @Test
    fun `purge really removes one entry`() {
        val id = newSession("t4")
        assertTrue(ok(post("/api/delete", """{"id":"$id"}""")))
        val moved = trashFile(id) ?: error("回收站里找不到")
        assertTrue(ok(post("/api/purge", """{"name":"${moved.name}"}""")))
        assertFalse("删干净之后文件不该还在", moved.exists())
        assertFalse("列表里不该还列着它",
            arr(get("/api/trash")).any { it.jsonObject["id"]?.jsonPrimitive?.content == id })
    }
}
