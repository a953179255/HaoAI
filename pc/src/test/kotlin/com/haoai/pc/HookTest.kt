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
import org.junit.Assume
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files

/**
 * 钩子（事件挂脚本）的判据。
 *
 * 主张有两条，都得跑到真进程才算：
 * ① 一轮真的跑完了，用户配的那段命令**真的被执行**，而且看得见会话上下文；
 * ② 钩子失败**只记账**，会话照常收尾 —— 这条比①更要紧，
 *    因为它的反面是"回完一句话，会话反而挂了"。
 */
class HookTest {

    companion object {
        private lateinit var gateway: com.sun.net.httpserver.HttpServer
        private lateinit var server: WebServer
        private lateinit var base: String
        private lateinit var ws: File
        private val http = HttpClient.newHttpClient()
        private const val REPLY = "钩子判据的收尾结论"

        @BeforeClass
        @JvmStatic
        fun setUp() {
            val home = Files.createTempDirectory("haoai-hook-home").toFile()
            System.setProperty("haoai.home", File(home, "home").absolutePath)
            ws = File(home, "ws").apply { mkdirs() }
            gateway = com.sun.net.httpserver.HttpServer.create(
                java.net.InetSocketAddress("127.0.0.1", 0), 0
            )
            gateway.createContext("/v1/chat/completions") { ex ->
                ex.requestBody.readBytes()
                val b = ("data: " +
                    """{"choices":[{"index":0,"finish_reason":"stop","delta":{"content":"$REPLY"}}]}""" +
                    "\n\ndata: [DONE]\n\n").toByteArray()
                ex.responseHeaders.add("Content-Type", "text/event-stream")
                ex.sendResponseHeaders(200, b.size.toLong())
                ex.responseBody.use { it.write(b) }
                ex.close()
            }
            gateway.start()
            PcSettings.save(PcSettings(
                workspace = ws.absolutePath, permissionMode = "auto", model = "hook-test",
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

    private fun post(path: String, body: String): String = http.send(
        HttpRequest.newBuilder(URI.create(base + path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
        HttpResponse.BodyHandlers.ofString()
    ).body()

    private fun get(path: String): String = http.send(
        HttpRequest.newBuilder(URI.create(base + path)).GET().build(), HttpResponse.BodyHandlers.ofString()
    ).body()

    private fun items(resp: String) =
        Json.parseToJsonElement(resp).jsonObject["items"]!!.jsonArray.toList()

    private fun hasShell(): Boolean = ShellLauncher.forName("pwsh") != null

    /** 建一条新会话并说一句话，等它跑完。返回那条会话的 id。 */
    private fun runOne(text: String): String {
        val sid = Json.parseToJsonElement(post("/api/new", "{}")).jsonObject["id"]!!
            .jsonPrimitive.content
        post("/api/task", """{"sid":${quote(sid)},"text":${quote(text)}}""")
        val until = System.currentTimeMillis() + 30_000L
        while (System.currentTimeMillis() < until) {
            val st = get("/api/state?sid=$sid")
            val o = Json.parseToJsonElement(st).jsonObject
            if (o["running"]?.jsonPrimitive?.content == "false" &&
                st.contains(REPLY)) return sid
            Thread.sleep(200)
        }
        error("会话没收尾：" + get("/api/state?sid=$sid").take(200))
    }

    private fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun addHook(name: String, command: String, enabled: Boolean = true): String {
        val resp = post("/api/hooks",
            """{"op":"save","name":${quote(name)},"command":${quote(command)},""" +
                """"event":"run-end","shell":"pwsh","timeoutSec":"40"}""")
        assertTrue("加钩子该成功：" + resp.take(120), resp.contains("\"ok\":true"))
        val id = items(resp).last().jsonObject["id"]!!.jsonPrimitive.content
        if (!enabled) post("/api/hooks", """{"op":"toggle","id":${quote(id)}}""")
        return id
    }

    private fun rowOf(id: String) = items(get("/api/hooks")).firstOrNull {
        it.jsonObject["id"]?.jsonPrimitive?.content == id
    }?.jsonObject

    private fun awaitFile(f: File, ms: Long = 25_000L): Boolean {
        val until = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < until) { if (f.isFile) return true; Thread.sleep(200) }
        return f.isFile
    }

    @Test
    fun `the command text survives the round-trip`() {
        val nasty = "Write-Host \"引号\" 和 \\ 反斜杠 与 中文"
        val resp = post("/api/hooks",
            """{"op":"save","name":"回读","command":${quote(nasty)},"event":"run-end"}""")
        val back = items(resp).last().jsonObject["command"]!!.jsonPrimitive.content
        assertEquals("命令原样存、原样读（引号与反斜杠都不变形）", nasty, back)
        post("/api/hooks", """{"op":"del","id":${quote(items(resp).last().jsonObject["id"]!!.jsonPrimitive.content)}}""")
    }

    @Test
    fun `a run-end hook really runs and sees the session context`() {
        Assume.assumeTrue("这台机器上没有 pwsh，跳过真进程判据", hasShell())
        val marker = File(ws, "hook-sid.txt")
        marker.delete()
        val id = addHook("记一笔",
            "Set-Content -Path hook-sid.txt -Value (\$env:HAOAI_SID + '|' + \$env:HAOAI_TRIGGER)")
        try {
            val sid = runOne("说一句给钩子判据用的话")
            assertTrue("钩子没写出文件（跑完没触发？）", awaitFile(marker))
            val parts = marker.readText().trim().split('|')
            assertEquals("会话 id 通过环境变量到了脚本里：$parts", sid, parts[0])
            assertTrue("触发方式也该给：" + parts.getOrElse(1) { "" }, parts[1].isNotBlank())
        } finally {
            post("/api/hooks", """{"op":"del","id":${quote(id)}}"""); marker.delete()
        }
    }

    @Test
    fun `the final answer reaches the hook through a file`() {
        Assume.assumeTrue("这台机器上没有 pwsh，跳过真进程判据", hasShell())
        val copy = File(ws, "hook-out.txt")
        copy.delete()
        val id = addHook("抄一份", "Copy-Item -Path \$env:HAOAI_OUTFILE -Destination hook-out.txt -Force")
        try {
            runOne("说一句让钩子把结论抄走")
            assertTrue("钩子没拿到结论文件", awaitFile(copy))
            assertTrue("文件里就是那句结论：" + copy.readText(), copy.readText().contains(REPLY))
        } finally {
            post("/api/hooks", """{"op":"del","id":${quote(id)}}"""); copy.delete()
        }
    }

    @Test
    fun `a failing hook is only recorded and never breaks the session`() {
        Assume.assumeTrue("这台机器上没有 pwsh，跳过真进程判据", hasShell())
        val id = addHook("注定失败", "Write-Host 起手还行; exit 3")
        try {
            val sid = runOne("钩子会失败，但这句话该正常回完")
            val st = get("/api/state?sid=$sid")
            assertTrue("会话该正常收尾并带着那句结论", st.contains(REPLY))
            assertFalse("不该还在跑", st.contains("\"running\":true"))
            val until = System.currentTimeMillis() + 10_000L
            var row = rowOf(id)
            while (System.currentTimeMillis() < until &&
                row?.get("last")?.jsonPrimitive?.contentOrNull.isNullOrBlank()) {
                Thread.sleep(200); row = rowOf(id)
            }
            val last = row?.get("last")?.jsonPrimitive?.contentOrNull ?: ""
            assertTrue("失败要记在条目上：" + last, last.contains("exit=3"))
            assertEquals("ok 该是假", false, row?.get("ok")?.jsonPrimitive?.content?.toBoolean())
        } finally {
            post("/api/hooks", """{"op":"del","id":${quote(id)}}""")
        }
    }

    @Test
    fun `a disabled hook does not run`() {
        Assume.assumeTrue("这台机器上没有 pwsh，跳过真进程判据", hasShell())
        val marker = File(ws, "hook-off.txt"); marker.delete()
        val id = addHook("停用的", "Set-Content -Path hook-off.txt -Value x", enabled = false)
        try {
            runOne("这句话跑完不该有钩子动")
            val sid = runOne("这句话跑完不该有钩子动")
            val st = get("/api/state?sid=$sid")
            assertTrue(st.contains(REPLY))
            assertFalse("停用不该跑：文件出现了", marker.isFile)
            assertTrue("条目上也不该有运行记录：" + rowOf(id)?.get("last"),
                rowOf(id)?.get("last")?.jsonPrimitive?.contentOrNull.isNullOrBlank())
        } finally {
            post("/api/hooks", """{"op":"del","id":${quote(id)}}"""); marker.delete()
        }
    }

    @Test
    fun `empty command and unknown event are refused with a sentence`() {
        val empty = post("/api/hooks", """{"op":"save","name":"空的","command":"   "}""")
        assertTrue("要说清命令是空的：" + empty.take(90), empty.contains("命令是空的"))
        val unknown = post("/api/hooks", """{"op":"save","name":"乱事件","command":"x","event":"on-moon"}""")
        assertTrue("不认识的事件要拒绝：" + unknown.take(90), unknown.contains("还不认识事件"))
    }
}
