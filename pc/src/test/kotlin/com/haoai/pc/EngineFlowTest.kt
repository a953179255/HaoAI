package com.haoai.pc

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * PC 端的端到端流程测试 —— **不联网**，把模型换成脚本，剩下的全是真的：
 * 真 Engine 回合循环、真工具、真文件系统、真审批闸、真落库与溢出。
 *
 * 这套做法是从手机端抄来的：`AgentEngineLoopTest` 早就证明了 HaoAI 的引擎
 * 可以脱离 Android 被驱动，PC 端一开始就该有同一级验收，否则"能跑"只能靠手点。
 */
class EngineFlowTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun setUp() {
            val tmp = Files.createTempDirectory("haoai-pc-test").toFile()
            System.setProperty("haoai.home", File(tmp, "home").absolutePath)
        }
    }

    private fun tempWorkspace(): File = Files.createTempDirectory("haoai-ws").toFile().apply { mkdirs() }

    private fun toolCall(id: String, name: String, argsJson: String) =
        listOf(ToolCall(id, name, argsJson))

    /** 脚本化模型：按队列吐出回合。 */
    private class Scripted(private val turns: MutableList<AssistantTurn>) : ChatClient {
        var lastTools: List<ToolSchema> = emptyList()
        var calls = 0
        override fun chat(messages: List<Msg>, tools: List<ToolSchema>, onText: (String) -> Unit): AssistantTurn {
            lastTools = tools
            calls++
            val t = if (turns.isNotEmpty()) turns.removeAt(0) else AssistantTurn("收尾", emptyList(), Usage(), "stop")
            onText(t.text)
            return t
        }
    }

    private fun turn(text: String, calls: List<ToolCall> = emptyList()) =
        AssistantTurn(text, calls, Usage(10, 5), if (calls.isEmpty()) "stop" else "tool_calls")

    private class RecordingGate(private val allow: Boolean) : Gate {
        val asked = mutableListOf<String>()
        var lastQuestion: String = ""
        override fun approve(title: String, detail: String, kind: String): Boolean {
            asked += title
            return allow
        }

        override fun ask(question: String, options: List<String>): String {
            lastQuestion = question
            return options.firstOrNull() ?: "就用第一个"
        }
    }

    private fun harness(
        ws: File,
        turns: MutableList<AssistantTurn>,
        mode: String = "auto",
        allow: Boolean = true
    ): Triple<Engine, Scripted, RecordingGate> {
        val scripted = Scripted(turns)
        val gate = RecordingGate(allow)
        val session = Session("t" + System.nanoTime(), ws)
        session.mode = mode
        val engine = Engine(session, PcSettings(permissionMode = mode), builtinTools(), gate, {}, scripted)
        return Triple(engine, scripted, gate)
    }

    @Test
    fun `write then read then edit round-trips a real file`() {
        val ws = tempWorkspace()
        val (engine, _, _) = harness(
            ws,
            mutableListOf(
                turn("先建文件", toolCall("c1", "write", """{"path":"a.txt","content":"hello\nworld"}""")),
                turn("再改一行", toolCall("c2", "edit", """{"path":"a.txt","old_string":"hello","new_string":"hi"}""")),
                turn("读回来", toolCall("c3", "read", """{"path":"a.txt"}""")),
                turn("完成")
            )
        )
        val out = engine.submit("建个 a.txt 把第一行改成 hi 再读给我")
        assertEquals("hi\nworld", File(ws, "a.txt").readText())
        assertEquals("完成", out)
        // 工具结果必须真进了历史：最后一条 tool 消息里能看到带行号的 read 输出
        val lastTool = engine.messages().last { it.role == "tool" }
        assertTrue("read 结果没落进历史：${lastTool.content}", (lastTool.content ?: "").contains("hi"))
    }

    @Test
    fun `plan mode refuses writes and hides write tools from the model`() {
        val ws = tempWorkspace()
        val (engine, scripted, _) = harness(
            ws,
            mutableListOf(
                turn("试着写", toolCall("c1", "write", """{"path":"b.txt","content":"x"}""")),
                turn("结束")
            ),
            mode = "plan"
        )
        engine.submit("计划模式下试着写文件")
        assertFalse("计划模式竟然写了文件", File(ws, "b.txt").exists())
        val toolMsg = engine.messages().last { it.role == "tool" }
        assertTrue("拒绝理由没带上原因：${toolMsg.content}", (toolMsg.content ?: "").contains("计划模式"))
        // 更关键：schema 里根本不该出现 write —— 模型看不见就不会去点
        assertFalse("plan 档位还把 write 告诉模型了", scripted.lastTools.any { it.name == "write" })
        assertTrue("plan 档位把 read 也藏了", scripted.lastTools.any { it.name == "read" })
    }

    @Test
    fun `ask mode blocks the write when the user denies`() {
        val ws = tempWorkspace()
        val (engine, _, gate) = harness(
            ws,
            mutableListOf(
                turn("要写", toolCall("c1", "write", """{"path":"c.txt","content":"x"}""")),
                turn("算了")
            ),
            mode = "ask",
            allow = false
        )
        engine.submit("写个 c.txt")
        assertEquals(listOf("写入文件 c.txt"), gate.asked)
        assertFalse("用户拒绝后还是写了", File(ws, "c.txt").exists())
    }

    @Test
    fun `ask mode writes when the user allows`() {
        val ws = tempWorkspace()
        val (engine, _, gate) = harness(
            ws,
            mutableListOf(
                turn("要写", toolCall("c1", "write", """{"path":"d.txt","content":"ok"}""")),
                turn("好了")
            ),
            mode = "ask",
            allow = true
        )
        engine.submit("写个 d.txt")
        assertEquals(1, gate.asked.size)
        assertEquals("ok", File(ws, "d.txt").readText())
    }

    @Test
    fun `long tool output spills to a file and the session keeps only the pointer`() {
        val ws = tempWorkspace()
        // 用 read 而不是 shell 造长输出：子进程与 PATH 无关，断言只反映引擎行为本身。
        File(ws, "big.txt").writeText((1..1000).joinToString("\n") { "line%04d-%s".format(it, "x".repeat(40)) })
        val (engine, _, _) = harness(
            ws,
            mutableListOf(
                turn("读一大段", toolCall("s1", "read", """{"path":"big.txt","offset":1,"limit":1000}""")),
                turn("结束")
            )
        )
        engine.submit("把 big.txt 整个读一遍")
        assertTrue("读到的内容本来就该超上限", File(ws, "big.txt").length() > 16_000)
        val dir = File(ws, Env.TOOL_OUTPUT_DIR)
        val spilled = dir.listFiles()?.filter { it.isFile && it.name.endsWith(".txt") } ?: emptyList()
        assertTrue("没有溢出文件，目录=${dir.absolutePath}", spilled.isNotEmpty())
        assertTrue("溢出文件比上限还小", spilled.first().length() > 16_000)
        val toolMsg = engine.messages().last { it.role == "tool" }
        val content = toolMsg.content ?: ""
        assertTrue("落库内容没提路径：${content.take(80)}", content.contains(Env.TOOL_OUTPUT_DIR))
        assertTrue("落库内容没收在预算内：${content.length}", content.length < 17_000)
        assertTrue("缺了回读指引", content.contains("read(path="))
    }

    @Test
    fun `shell tool executes a real command and reports the exit code`() {
        val ws = tempWorkspace()
        val ctx = ToolCtx(ws, PcSettings(), "auto", RecordingGate(true))
        val r = ShellTool().run(
            kotlinx.serialization.json.Json.parseToJsonElement(
                """{"command":"echo haoai-shell-ok","shell":"bash","timeout":60}"""
            ).let { it as kotlinx.serialization.json.JsonObject },
            ctx
        )
        assertFalse("shell 失败：${r.content}", r.error)
        assertTrue("输出里没有命令结果：${r.content.take(200)}", r.content.contains("haoai-shell-ok"))
        assertTrue("没带 exit：${r.content.take(60)}", r.content.startsWith("exit="))
    }

    @Test
    fun `session persists and a fresh engine sees the history`() {
        // 先自证测试没有写进用户真实的 %LOCALAPPDATA%：这条挂了就是隔离失效，
        // 之前 lazy 的 Env.home 让单测在用户目录里堆了 21 条测试会话。
        assertTrue(
            "测试状态根没隔离出去：" + Env.sessionsDir.absolutePath,
            Env.sessionsDir.absolutePath.contains(System.getProperty("java.io.tmpdir").removeSuffix("\\"))
        )
        val ws = tempWorkspace()
        val session = Session("persist-" + System.nanoTime(), ws)
        session.mode = "auto"
        val scripted = Scripted(mutableListOf(turn("第一轮", toolCall("c1", "write", """{"path":"p.txt","content":"1"}""")), turn("好了")))
        val e1 = Engine(session, PcSettings(), builtinTools(), RecordingGate(true), {}, scripted)
        e1.submit("写 p.txt")
        assertTrue(session.file.isFile)

        val restored = Engine(session, PcSettings(), builtinTools(), RecordingGate(true), {}, Scripted(mutableListOf(turn("第二轮"))))
        val before = restored.messages().size
        assertTrue("恢复后历史比原来还短：$before", before >= 3)
        restored.submit("继续")
        // 一轮无工具的回合会加两条：user + assistant
        assertEquals(before + 2, restored.messages().size)
    }

    @Test
    fun `todo tool updates the shared list and emits an event`() {
        val ws = tempWorkspace()
        val events = mutableListOf<Ev>()
        val scripted = Scripted(
            mutableListOf(
                turn("先列计划", toolCall("t1", "todo", """{"items":[{"text":"调研","status":"doing"},{"text":"落地","status":"pending"}]}""")),
                turn("结束")
            )
        )
        val session = Session("todo-" + System.nanoTime(), ws)
        session.mode = "auto"
        val engine = Engine(session, PcSettings(), builtinTools(), RecordingGate(true), { events += it }, scripted)
        engine.submit("列个清单")
        assertEquals(2, session.todos.size)
        assertEquals("doing", session.todos[0].status)
        assertTrue("没有往外冒 Todo 事件", events.any { it is Ev.Todo })
    }

    @Test
    fun `ask_user goes through the gate and back into history`() {
        val ws = tempWorkspace()
        val gate = RecordingGate(true)
        val (engine, _, g) = harness(
            ws,
            mutableListOf(
                turn("问一下", toolCall("q1", "ask_user", """{"question":"要哪种主题色？","options":["绿色","蓝色"]}""")),
                turn("收到")
            )
        )
        engine.submit("问我一句")
        assertEquals("要哪种主题色？", g.lastQuestion)
        assertTrue("用户的选择没回填给模型", (engine.messages().last { it.role == "tool" }.content ?: "").contains("绿色"))
        assertEquals("绿色", gate.lastQuestion.let { "绿色" })
    }

    @Test
    fun `grep and glob find what write put there`() {
        val ws = tempWorkspace()
        File(ws, "src").mkdirs()
        File(ws, "src/Main.kt").writeText("package x\nfun hello() = 1\n")
        val ctx = ToolCtx(ws, PcSettings(), "auto", RecordingGate(true))
        val g = GrepTool().run(buildJsonObject { put("pattern", "fun\\s+hello") }, ctx)
        assertFalse(g.error)
        assertTrue("grep 没命中：${g.content}", g.content.contains("src/Main.kt:2:"))
        val gl = GlobTool().run(buildJsonObject { put("pattern", "**/*.kt") }, ctx)
        assertTrue("glob 没命中：${gl.content}", gl.content.contains("src/Main.kt"))
    }

    @Test
    fun `unknown tool gets a helpful error instead of crashing the loop`() {
        val ws = tempWorkspace()
        val (engine, _, _) = harness(
            ws,
            mutableListOf(
                turn("调个不存在的", toolCall("x1", "no_such_tool", "{}")),
                turn("好吧")
            )
        )
        engine.submit("用 no_such_tool 做点事")
        val msg = engine.messages().last { it.role == "tool" }.content ?: ""
        assertTrue("没告诉模型可用工具：$msg", msg.contains("未知工具") && msg.contains("read"))
    }

    @Test
    fun `snapshot keeps the previous version before an overwrite`() {
        val ws = tempWorkspace()
        File(ws, "keep.txt").writeText("v1\n")
        val ctx = ToolCtx(ws, PcSettings(flags = mapOf("snapshot_before_write" to true)), "auto", RecordingGate(true))
        WriteTool().run(
            buildJsonObject {
                put("path", "keep.txt")
                put("content", "v2\n")
            },
            ctx
        )
        val snaps = File(ws, ".haoai-snap").listFiles()?.toList() ?: emptyList()
        assertTrue("没留快照", snaps.any { it.readText() == "v1\n" })
    }

    @Test
    fun `html to text keeps body copy and drops scripts`() {
        val t = htmlToText("<html><head><style>a{}</style></head><body><script>x()</script><h1>标题</h1><p>正文一段</p></body></html>")
        assertTrue(t.contains("标题"))
        assertTrue(t.contains("正文一段"))
        assertFalse("script 内容漏进来了：$t", t.contains("x()"))
    }

    @Test
    fun `glob to regex handles double star and literal dots`() {
        assertTrue(globToRegex("**/*.kt").matches("a/b/C.kt"))
        assertTrue(globToRegex("**/*.kt").matches("C.kt"))
        assertFalse(globToRegex("**/*.kt").matches("a/b/C.ktx"))
        assertTrue(globToRegex("pc/src/**/*Tools.kt").matches("pc/src/main/kotlin/com/haoai/pc/Tools.kt"))
    }

    @Test
    fun `agent artifact dirs get excluded from git locally, not via the user gitignore`() {
        val ws = tempWorkspace()
        File(ws, ".git").mkdirs()
        val exclude = File(ws, ".git/info/exclude")
        Env.excludeFromGit(ws, Env.TOOL_OUTPUT_DIR, ".haoai-snap")
        val text = exclude.readText()
        assertTrue("没写进 info/exclude：$text", text.contains("/${Env.TOOL_OUTPUT_DIR}/"))
        assertTrue(text.contains("/.haoai-snap/"))
        assertFalse("不该碰用户的 .gitignore", File(ws, ".gitignore").exists())
        // 再调一次不能重复追加
        Env.excludeFromGit(ws, Env.TOOL_OUTPUT_DIR, ".haoai-snap")
        assertEquals(1, exclude.readText().lines().count { it.trim() == "/${Env.TOOL_OUTPUT_DIR}/" })
    }

    @Test
    fun `diff summary counts changed lines`() {
        val d = Diff.summary("a\nb\nc", "a\nB\nc")
        assertTrue(d, d.contains("−1 行") && d.contains("+1 行"))
    }

    @Test
    fun `schema builder produces valid openai tool json`() {
        val schemas = builtinTools().map { ToolSchema(it.name, it.desc, it.params) }
        // 15 把：9 把基础 + 5 把常驻进程 + 1 把 git
        assertEquals(15, schemas.size)
        assertTrue(
            "常驻进程工具没注册进来",
            setOf("shell_open", "shell_send", "shell_read", "shell_close", "shell_list")
                .all { n -> schemas.any { it.name == n } }
        )
        val read = schemas.first { it.name == "read" }
        val obj = kotlinx.serialization.json.Json.parseToJsonElement(read.params.toString()).jsonObjectOf()
        assertEquals("object", obj["type"]?.let { it.toString().trim('"') })
        assertTrue(obj.containsKey("properties"))
        // 每条 schema 都得是合法 JSON 且带 properties，否则网关会整批拒
        schemas.forEach { s ->
            val o = kotlinx.serialization.json.Json.parseToJsonElement(s.params.toString())
            assertTrue("${s.name} 的 schema 不是 object", o is kotlinx.serialization.json.JsonObject)
        }
    }

    private fun kotlinx.serialization.json.JsonElement.jsonObjectOf(): Map<String, kotlinx.serialization.json.JsonElement> =
        (this as kotlinx.serialization.json.JsonObject).toMap()
}
