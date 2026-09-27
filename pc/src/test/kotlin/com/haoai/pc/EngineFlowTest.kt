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
        var lastMessages: List<Msg> = emptyList()
        var calls = 0
        override fun chat(messages: List<Msg>, tools: List<ToolSchema>, onText: (String) -> Unit): AssistantTurn {
            lastTools = tools
            lastMessages = messages
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

    /**
     * "删到这里"与"编辑重发"这两种截法的分界。
     *
     * 两者本来共用一个 cutTo，于是要么多删一句、要么少删一句：
     * 前者让整条会话看起来被清空（点"删到这里"结果连自己那句都没了），
     * 后者会让改后的那句和原句一起留在历史里，模型看到自己问了两遍。
     */
    @Test
    fun `cut keeps the chosen question while edit-resend replaces it`() {
        val (engine, _, _) = harness(
            tempWorkspace(),
            mutableListOf(turn("第一答"), turn("第二答"), turn("第三答"))
        )
        engine.submit("第一问")
        engine.submit("第二问")
        val second = engine.messages().indexOfLast { it.role == "user" }

        assertTrue(engine.cutTo(second, keepAt = true))
        assertEquals("删到这里把这一问也删掉了", "第二问", engine.messages()[second].content)
        assertEquals("该剩 问-答-问 三条", 3, engine.messages().size)

        assertTrue(engine.cutTo(second, keepAt = false))
        assertEquals("编辑重发没把原句让位给新句", 2, engine.messages().size)

        val (e2, _, _) = harness(
            tempWorkspace(),
            mutableListOf(
                turn("动手", toolCall("x1", "write", """{"path":"c.txt","content":"x"}""")),
                turn("好了")
            )
        )
        e2.submit("写个 c.txt")
        assertFalse("竟然允许从回合中间截断（下一次请求就是有调用没回复）", e2.cutTo(1))
    }

    /**
     * 项目说明（AGENTS.md 那一类）要真的到模型眼前。
     *
     * 判据不是"Memory.read 返回了字符串"，而是**发给模型的那条 system 消息里有没有它** ——
     * 这个功能最容易坏在中间：读到了没塞进 PromptCtx，或塞了但被压缩/截断吃掉。
     */
    @Test
    fun `AGENTS md reaches the model every turn`() {
        val ws = tempWorkspace()
        File(ws, "AGENTS.md").writeText("构建只用 gradlew.bat，不要直接调 gradle。")
        val (engine, scripted, _) = harness(ws, mutableListOf(turn("知道了"), turn("还在")))
        engine.submit("随便做点什么")
        val sys = scripted.lastMessages.first { it.role == "system" }.content ?: ""
        assertTrue("系统提示里没有项目说明：$sys", sys.contains("gradlew.bat"))
        assertTrue("项目说明没标出处，模型分不清是谁写的", sys.contains("AGENTS.md"))
        assertTrue("占用明细里该有「项目说明」这一行：${engine.contextBreakdown()}",
            engine.contextBreakdown().any { it.first == "项目说明" && it.second > 0 })
    }

    @Test
    fun `memory files are labelled capped and prefer AGENTS md`() {
        val ws = tempWorkspace()
        ws.mkdirs()
        File(ws, ".haoai").mkdirs()
        File(ws, ".haoai/memory.md").writeText("规则A")
        val one = Memory.read(ws)
        assertTrue("没有 AGENTS.md 时该读到 .haoai/memory.md：$one",
            one.contains("规则A") && one.contains(".haoai/memory.md"))
        File(ws, "AGENTS.md").writeText("规则B")
        val both = Memory.read(ws)
        assertTrue("两份都在就该都带上：$both", both.contains("规则A") && both.contains("规则B"))
        // 保存的目标：已经有 AGENTS.md 就顺着它写，别的 agent 也认这份
        assertEquals("AGENTS.md", Memory.target(ws).name)
        File(ws, "AGENTS.md").writeText("x".repeat(20_000))
        val big = Memory.read(ws)
        assertTrue("超长说明要把上下文挤没了：${big.length}", big.length < 14_000)
        assertTrue("截断了要说一声：$big", big.contains("已截"))
    }

    /** 技能存的是 JSON，而提示词里必然有换行与引号：转义错了就是把 skills.json 写坏。 */
    @Test
    fun `skills survive a save-load round trip with quotes and newlines`() {
        val text = "先看 diff，再改。\n注意：\"引号\"与 \\ 反斜杠 都要活着回来。\n第三行"
        Skills.save(listOf(Skill("review", "看改动", text), Skill("tidy", "整理", "  ")))
        val back = Skills.load()
        assertEquals("存两条就该读回两条", 2, back.size)
        assertEquals("换行/引号/反斜杠被转义弄坏了：<" + back[0].text + ">", text, back[0].text)
        assertEquals("看改动", back[0].desc)
        // 坏文件不能把界面搞死：读不出来就当没有
        Env.skillsFile.writeText("{这不是 JSON")
        assertTrue("坏文件应当读成空列表", Skills.load().isEmpty())
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

    /**
     * 实验特性关着的工具**不该出现在 schema 里**。
     *
     * 这是 S6 开关注册表真正值钱的地方：不是"调了再报没权限"，而是"关着就等于不存在"。
     * 浏览器控制默认关 —— 能以用户身份点网页按钮的能力，不该在用户没打开开关时被模型发现。
     */
    @Test
    fun `a flag-gated tool is invisible to the model while the flag is off`() {
        val ws = tempWorkspace()
        val noFlag = mutableListOf(turn("结束"))
        val (_, scriptedOff, _) = harness(ws, noFlag)
        // 默认 flags 是空 map → BROWSER_CONTROL 用 defaultOn=false → 关
        Engine(
            Session("flag-" + System.nanoTime(), ws), PcSettings(), builtinTools(),
            RecordingGate(true), {}, Scripted(mutableListOf(turn("完")))
        ).let { e ->
            val names = e.visibleToolNames()
            assertFalse("浏览器控制默认就开着？", names.contains("browser"))
            assertTrue("基础工具不见了", names.containsAll(listOf("read", "write", "git")))
        }
        val on = PcSettings(flags = mapOf("browser_control" to true))
        Engine(
            Session("flag2-" + System.nanoTime(), ws), on, builtinTools(),
            RecordingGate(true), {}, Scripted(mutableListOf(turn("完")))
        ).let { e ->
            assertTrue("拨开之后浏览器工具仍不可见", e.visibleToolNames().contains("browser"))
        }
        scriptedOff.calls
    }

    /**
     * 开关关着的工具，模型硬调也必须跑不动。
     *
     * 上面那条只覆盖了 schema 那一侧。`byName` 是全量注册的，模型凭训练记忆报出
     * `screen`（它在别的 agent 那儿见过）就能绕过"看不见"这道防线 ——
     * 而 desktop_control 关着的语义是"这台机器的鼠标键盘不归模型管"，
     * 那必须是执行侧的事实，不只是没写进说明书。
     */
    @Test
    fun `a flag-gated tool still cannot run when the model calls it anyway`() {
        val ws = tempWorkspace()
        val (engine, _, gate) = harness(
            ws,
            mutableListOf(
                turn("看看屏幕", toolCall("c1", "screen", """{"sub":"windows"}""")),
                turn("算了")
            )
        )
        engine.submit("列一下窗口")
        val toolMsg = engine.messages().last { it.role == "tool" }
        val why = toolMsg.content ?: ""
        assertTrue("关着的桌面控制居然执行了：$why", why.contains("没启用") && why.contains("desktop_control"))
        assertEquals("被开关挡掉的动作不该再弹审批", 0, gate.asked.size)
    }

    /**
     * 停止要成为数据，不是"悄悄不干了"。三条判据缺一条都算没做完：
     * ① 排到队里还没执行的工具**不会**被执行；
     * ② **每个 tool_call_id 仍要有一条 tool 回复** —— OpenAI 兼容协议要求成对，
     *    少一次下一次请求就 400，"中断"反而把会话写坏（手机端在 id 撞车那次付过学费）；
     * ③ 历史里要留下"这里被中断过"，否则下一轮模型以为事情做完了，接着往下编。
     */
    @Test
    fun `stopping mid-run skips the rest but keeps the protocol pairs intact`() {
        val ws = tempWorkspace()
        val session = Session("stop-" + System.nanoTime(), ws)
        session.mode = "auto"
        var ref: Engine? = null
        val stopper = object : ChatClient {
            override fun chat(
                messages: List<Msg>, tools: List<ToolSchema>, onText: (String) -> Unit
            ): AssistantTurn {
                onText("我先写两个文件")
                ref?.requestStop()   // 用户在这一刻按了停止
                return AssistantTurn(
                    "我先写两个文件",
                    listOf(
                        ToolCall("c1", "write", """{"path":"one.txt","content":"1"}"""),
                        ToolCall("c2", "write", """{"path":"two.txt","content":"2"}""")
                    ),
                    Usage(3, 4), "tool_calls"
                )
            }
        }
        val engine = Engine(
            session, PcSettings(permissionMode = "auto"), builtinTools(),
            RecordingGate(true), {}, stopper
        )
        ref = engine
        engine.submit("写两个文件")

        assertFalse("停止之后还是把 one.txt 写了", File(ws, "one.txt").exists())
        assertFalse("停止之后还是把 two.txt 写了", File(ws, "two.txt").exists())
        val msgs = engine.messages()
        val calls = msgs.last { it.role == "assistant" && it.calls.isNotEmpty() }.calls.map { it.id }
        val answered = msgs.filter { it.role == "tool" }.mapNotNull { it.callId }
        assertEquals(2, calls.size)
        assertTrue("有 tool_call 没回复（下一次请求会被网关判 400）：$calls vs $answered",
            calls.all { it in answered })
        assertTrue("历史里没留下「这里被中断过」：${msgs.map { (it.content ?: "").take(24) }}",
            msgs.any { (it.content ?: "").contains("停止") })
    }

    /** diff 是"审阅这次改了什么"的唯一依据：行数、+/- 前缀、上下文都得在，且不能把没变的报成变了。 */
    @Test
    fun `diff marks changed lines and keeps context`() {
        val d = Diff.unified("a.txt", "一\n二\n三\n四\n五\n六\n七", "一\n二\n改\n四\n五\n六\n七")
        assertTrue(d, d.contains("−1 行") && d.contains("+1 行") && d.contains("a.txt"))
        assertTrue("改动行要带 +/-：$d", d.contains("-三") && d.contains("+改"))
        assertTrue("上下文要带空格前缀：$d", d.contains(" 五"))
        assertEquals("内容没变就不该报差异",
            "−0 行 / +0 行（内容没变）", Diff.unified("a", "x\n", "x\n"))
    }

    @Test
    fun `schema builder produces valid openai tool json`() {
        val schemas = builtinTools().map { ToolSchema(it.name, it.desc, it.params) }
        // 17 把：9 把基础 + 5 把常驻进程 + git + browser + screen
        assertEquals(17, schemas.size)
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
