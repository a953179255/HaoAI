package com.haoai.pc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * MCP 客户端的验收 —— 对着**真协议**跑，不是对着自己的解析函数跑。
 *
 *  handshake 字段、通知与回包混在同一条流里、tools/list 的 schema 形状，
 * 任何一处错了症状都是"工具表是空的"这种不带堆栈的故障，光读代码看不出来。
 * 所以配了一个最小的真 MCP 服务器（tools/mock-mcp.js），走 stdio 与换行分隔 JSON-RPC。
 *
 * 三条判据分别对着三个不同的失败面：
 * ① 协议接对了（并且外部进程**真的**被调到 —— 它往磁盘写了标记，不是客户端自己编返回值）；
 * ② 一个坏 server 只让"外部工具"变空，不许把引擎带崩；
 * ③ 开关关着时外部工具对模型不存在（这是 MCP 敢默认关的前提）。
 */
class McpTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun setUp() {
            val tmp = Files.createTempDirectory("haoai-mcp-home").toFile()
            System.setProperty("haoai.home", File(tmp, "home").absolutePath)
        }

        @JvmStatic
        @AfterClass
        fun tearDown() {
            Mcp.reload()
            runCatching { McpConfig.save(emptyList()) }
        }

        private val script: File
            get() = File("tools/mock-mcp.js").absoluteFile

        private fun hasNode(): Boolean = runCatching {
            ProcessBuilder("node", "--version").redirectErrorStream(true).start().waitFor() == 0
        }.getOrDefault(false)
    }

    private fun mock(mark: File) =
        McpServer("mock", "node", listOf(script.absolutePath, "--mark", mark.absolutePath))

    @Test
    fun `client handshakes lists and really calls a stdio mcp server`() {
        assumeTrue("这台机器上没有 node，跳过真协议验收", hasNode())
        assertTrue("缺 mock-mcp.js", script.isFile)
        val mark = File.createTempFile("mcp-hit", ".txt")
        val c = McpClient(mock(mark)).start()
        try {
            assertTrue("握手没起来：" + c.error, c.running)
            val tools = c.listTools()
            assertEquals("应当列出 1 把外部工具（通知消息会干扰按 id 挑回包的逻辑）：$tools",
                1, tools.size)
            assertEquals("echo", tools[0].name)
            val args = Json.parseToJsonElement("""{"text":"你好 MCP"}""").jsonObject
            val (text, err) = c.callTool("echo", args)
            assertFalse("调用被判成错误：$text", err)
            assertTrue("返回值不对：$text", text.contains("外部工具收到：你好 MCP"))
            assertTrue("外部进程没真被调到（标记文件是空的）", mark.readText().contains("你好 MCP"))
        } finally {
            c.stop()
        }
        assertFalse("stop 之后进程还活着", c.running)
    }

    @Test
    fun `a broken server leaves the engine working with only builtin tools`() {
        McpConfig.save(listOf(McpServer("bad", "no-such-command-haoai-test-xyz")))
        Mcp.reload()
        assertTrue("坏 server 不该产出工具", Mcp.tools().isEmpty())
        assertEquals("引擎应当照常拿到内置那套工具", builtinTools().size, allTools().size)
        McpConfig.save(emptyList()); Mcp.reload()
    }

    @Test
    fun `mcp tools stay invisible while the flag is off`() {
        assumeTrue("这台机器上没有 node，跳过真协议验收", hasNode())
        val mark = File.createTempFile("mcp-hit", ".txt")
        McpConfig.save(listOf(mock(mark)))
        Mcp.reload()
        val tools = Mcp.tools()
        assertTrue("没连上外部 server：" + tools, tools.isNotEmpty())
        assertTrue("工具名该带 mcp_ 前缀：${tools.map { it.name }}",
            tools.all { it.name.startsWith("mcp_mock_") })

        val ws = Files.createTempDirectory("haoai-mcp-ws").toFile()
        val session = Session("mcp1", ws).apply { mode = "auto" }
        val gate = object : Gate {
            override fun approve(title: String, detail: String, kind: String) = true
            override fun ask(question: String, options: List<String>) = ""
        }
        val off = Engine(session, PcSettings(permissionMode = "auto"), allTools(), gate, {})
        assertFalse("开关关着却把外部工具告诉模型了",
            off.visibleToolNames().any { it.startsWith("mcp_") })
        val on = Engine(session, PcSettings(permissionMode = "auto"), allTools(), gate, {})
        on.useSettings(PcSettings(permissionMode = "auto", flags = mapOf("mcp_client" to true)))
        assertTrue("开关打开后应当看得见外部工具",
            on.visibleToolNames().any { it.startsWith("mcp_mock_") })
        McpConfig.save(emptyList()); Mcp.reload()
    }
}
