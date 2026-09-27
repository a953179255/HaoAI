package com.haoai.pc

import kotlinx.serialization.json.Json
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
 * 输入框里 `@` 提及文件的那次搜索（`GET /api/files?q=`）。
 *
 * 为什么单独测：这条递归走的是**用户机器上的真实目录**，而它由浏览器里的一段输入触发。
 * 三件事必须钉住 —— 不逃出工作区、不扫穿整块盘（有界）、不把 .git/build 这些没人要的
 * 东西摆到补全面板上。少了断言，这类"只在真目录上才会出事"的分支第二天就会被改坏。
 */
class FileMentionTest {

    companion object {
        private lateinit var ws: File
        private lateinit var server: WebServer
        private lateinit var base: String
        private val http = HttpClient.newHttpClient()

        @BeforeClass
        @JvmStatic
        fun setUp() {
            val home = Files.createTempDirectory("haoai-at-home").toFile()
            System.setProperty("haoai.home", File(home, "home").absolutePath)
            ws = File(home, "ws").apply { mkdirs() }
            fun touch(p: String, text: String = "x") {
                val f = File(ws, p)
                f.parentFile.mkdirs()
                f.writeText(text)
            }
            touch("src/main/kotlin/Server.kt")
            touch("src/main/kotlin/Servant.kt")
            touch("notes/servicelog.txt")
            touch("notes/my-services-doc.txt")
            touch(".git/config")
            touch("node_modules/acorn/index.js")
            touch("build/Server.kt")
            touch("deep/a/b/c/Target.kt")
            // 八层深：这道界外的东西不该被找到（深度上限存在的理由就是"别把整块盘扫穿"）
            var p = "far"
            repeat(7) { p = "$p/n" }
            touch("$p/TooDeep.kt")
            PcSettings.save(PcSettings(workspace = ws.absolutePath, permissionMode = "auto"))
            server = WebServer(PcSettings.load(), port = 0)
            base = "http://127.0.0.1:${server.start()}"
            server.schedules()?.stopped = true
        }

        @AfterClass
        @JvmStatic
        fun tearDown() {
            runCatching { server.stop() }
        }

        private fun hits(q: String): List<String> {
            val body = http.send(
                HttpRequest.newBuilder(URI.create("$base/api/files?q=${java.net.URLEncoder.encode(q, "UTF-8")}"))
                    .GET().build(), HttpResponse.BodyHandlers.ofString()
            ).body()
            return Json.parseToJsonElement(body).jsonObject["entries"]!!.jsonArray
                .map { it.jsonObject["n"]!!.jsonPrimitive.content }
        }
    }

    @Test
    fun `a fragment finds nested files and returns workspace-relative paths`() {
        val r = hits("Target")
        assertEquals(listOf("deep/a/b/c/Target.kt"), r)
        assertTrue("路径要统一成正斜杠（前端要按 / 拆文件名）", r[0].contains('/') && !r[0].contains('\\'))
    }

    @Test
    fun `name-prefix matches rank above mid-path matches`() {
        val r = hits("serv")
        assertEquals("文件名以 serv 开头的排前面，其中名字短的更前",
            listOf("src/main/kotlin/Server.kt", "src/main/kotlin/Servant.kt", "notes/servicelog.txt"),
            r.take(3))
        assertEquals("只在路径中间命中的排最后", "notes/my-services-doc.txt", r.last())
    }

    @Test
    fun `junk directories stay out of the list`() {
        assertTrue(".git 不该出现在补全里", hits("config").isEmpty())
        assertTrue("node_modules 不该出现", hits("acorn").isEmpty())
        assertTrue("build 产物不该出现（同名文件会把人带偏）", hits("build").isEmpty())
    }

    @Test
    fun `the walk is depth bounded`() {
        assertEquals("八层深的东西扫不到是有意的", emptyList<String>(), hits("TooDeep"))
        assertTrue("三层内要扫得到", hits("Target").isNotEmpty())
    }

    /** 补全面板由浏览器里的输入触发：路径不能借一次输入逃出工作区。 */
    @Test
    fun `a sneaky query cannot reach outside the workspace`() {
        listOf("../", "..", "../..", "..\\..\\Windows", "/").forEach { q ->
            val r = hits(q)
            r.forEach {
                assertFalse("$q 探到了工作区外面：$it", it.startsWith("..") || it.startsWith("/"))
            }
        }
        // 这个服务只绑 127.0.0.1，但同一台机器上任何页面都能发这个请求，
        // 所以判据不是"没返回绝对路径"，而是"返回的东西都还在工作区里"
        val all = hits("k")
        assertTrue(all.all { File(ws, it).canonicalFile.path.startsWith(ws.canonicalFile.path) })
    }

    @Test
    fun `no q still means a plain one-level listing`() {
        val body = http.send(
            HttpRequest.newBuilder(URI.create("$base/api/files")).GET().build(),
            HttpResponse.BodyHandlers.ofString()
        ).body()
        val names = Json.parseToJsonElement(body).jsonObject["entries"]!!.jsonArray
            .map { it.jsonObject["n"]!!.jsonPrimitive.content }
        // 普通列目录只跳过 .git / node_modules（那是给人看的目录列表，build 产物也要看得见）；
        // 把 build 也跳掉的是 @ 搜索那条分支，两边语义不同是有意的
        assertEquals("根目录只列一层", listOf("build", "deep", "far", "notes", "src"), names.sorted())
    }
}
