package com.haoai.agent.agent.engine

import com.haoai.agent.agent.model.ChatMessage
import com.haoai.agent.agent.model.ToolCallData
import com.haoai.agent.agent.policy.PermissionMode
import com.haoai.agent.agent.policy.PolicyEngine
import com.haoai.agent.agent.provider.ApiMessage
import com.haoai.agent.agent.provider.ApiTool
import com.haoai.agent.agent.provider.ProviderClient
import com.haoai.agent.agent.provider.SseEvent
import com.haoai.agent.agent.skills.SkillStore
import com.haoai.agent.agent.tools.WebSearchTool
import com.haoai.agent.data.ProviderConfig
import com.haoai.agent.data.StoredMessage
import com.haoai.agent.data.StoredSession
import com.haoai.agent.data.StoredToolCall
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 引擎回合循环的回归锁。全部用脚本化 ProviderClient 驱动，不碰网络、不碰真机、不烧 token。
 * 覆盖的都是曾经真实坏过、且没有任何测试兜住的路径。
 */
class AgentEngineLoopTest {

    /** 记录每一轮出站的 messages 与工具清单，按脚本回放流式事件；failOn 里的调用序号抛瞬态网络错误。 */
    private class FakeClient(
        private val script: List<List<SseEvent>>,
        private val failOn: Set<Int> = emptySet(),
        /** 这些调用先按脚本吐完事件再断流：模拟"流到一半网关挂了"，屏幕上会留下残句。 */
        private val breakAfterEmit: Set<Int> = emptySet()
    ) : ProviderClient {
        val requests: MutableList<Pair<List<ApiMessage>, List<String>>> = mutableListOf()
        private var n = 0

        override suspend fun chatStream(
            provider: ProviderConfig,
            apiKey: String,
            messages: List<ApiMessage>,
            tools: List<ApiTool>,
            reasoningEffort: String?
        ): Flow<SseEvent> {
            requests += messages to tools.map { it.function.name }
            val idx = n++
            if (idx in failOn) throw java.io.IOException("connection reset")
            val events = script[minOf(idx, script.lastIndex)]
            if (idx in breakAfterEmit) return flow {
                events.forEach { emit(it) }
                throw java.io.IOException("connection reset")
            }
            return flow { events.forEach { emit(it) } }
        }

        override suspend fun testConnection(provider: ProviderConfig, apiKey: String) = Result.success("ok")
    }

    private fun tmpDir(): File = Files.createTempDirectory("haoai-test").toFile().apply { deleteOnExit() }

    /**
     * SkillStore 是全局单例，引擎拼系统提示时会读它的 promptIndex；
     * 不 init 就构造引擎会在 maybeCompact 里抛「SkillStore 未初始化」。
     */
    @org.junit.Before
    fun setUp() {
        SkillStore.init(tmpDir())
    }

    private fun engine(
        client: ProviderClient,
        dir: File,
        toolFailCap: Int = 8,
        session: StoredSession = StoredSession.create(null),
        contextLength: Int = 0,
        turnTokenCap: Int = 150_000,
        toolCallCap: Int = 80
    ): AgentEngine =
        AgentEngine(
            httpClient = client,
            provider = ProviderConfig(
                id = "fake", name = "fake", baseUrl = "http://127.0.0.1:1/v1",
                model = "fake-model", contextLength = contextLength
            ),
            apiKey = "fake-key",
            customPrompt = "",
            policy = PolicyEngine(PermissionMode.YOLO),
            approve = { true },
            session = session,
            persist = {},
            backend = null,
            appFilesDir = dir,
            workspaceLabel = "test",
            toolFailCap = toolFailCap,
            turnTokenCap = turnTokenCap,
            toolCallCap = toolCallCap
        )

    /** 每轮换参数，避免撞 P2-5 重放保护（同名同参紧邻会被判为重放而不真正执行）。 */
    private fun callingTool(name: String, id: String, argsJson: String) = listOf(
        SseEvent.Delta("思考中"),
        SseEvent.Completed(listOf(ToolCallData(id, name, argsJson))),
        SseEvent.Usage(100, 5)
    )

    private fun saying(text: String) =
        listOf(SseEvent.Delta(text), SseEvent.Usage(50, 10))

    /**
     * E5 连续工具失败熔断必须在**第 N 次**失败触发。
     * 旧实现把判定排在 EscalationHook.after 的 conFailCount 自增之前，阈值 8 实际第 9 次才熔断。
     */
    @Test
    fun failureBreakerTripsOnExactThreshold() = runBlocking {
        val script = (1..10).map { callingTool("no_such_tool", "c$it", "{\"n\":$it}") } +
            listOf(saying("收尾"))
        val client = FakeClient(script)
        engine(client, tmpDir(), toolFailCap = 2).runTurn("开始", {}, {})
        // 阈值 2：两次失败即熔断，不再发起第三次请求；off-by-one 回归时这里会变成 3
        assertEquals("连续失败熔断应当恰好在阈值那次触发", 2, client.requests.size)
    }

    /**
     * tools_enable 换组后重建工具清单，必须仍是首轮那份超集。
     * 旧实现漏传 subagentControl，导致 stop/steer/collect_agent 从清单上消失，
     * 而 spawn_agent 的描述里还承诺可以用。
     */
    @Test
    fun toolsEnableKeepsSubagentControls() = runBlocking {
        val client = FakeClient(
            listOf(
                callingTool("tools_enable", "e1", "{\"group\":\"extended\"}"),
                saying("完成")
            )
        )
        engine(client, tmpDir()).runTurn("开始", {}, {})
        assertEquals(2, client.requests.size)
        val secondRound = client.requests[1].second
        for (name in listOf("spawn_agent", "spawn_agents", "stop_agent", "steer_agent", "collect_agent")) {
            assertTrue("tools_enable 之后 $name 不应从工具清单消失", name in secondRound)
        }
    }

    /** 出站请求必须把上一轮的 tool 结果按原 call id 配对带回（配对破了供应商直接 400）。 */
    @Test
    fun toolResultsArePairedBackWithOriginalCallId() = runBlocking {
        val client = FakeClient(
            listOf(
                callingTool("no_such_tool", "call_keep_1", "{}"),
                saying("完成")
            )
        )
        engine(client, tmpDir()).runTurn("开始", {}, {})
        val second = client.requests[1].first
        val toolMsg = second.last { it.role == "tool" }
        assertEquals("call_keep_1", toolMsg.toolCallId)
        assertTrue(
            "对应的 assistant.tool_calls 必须还在",
            second.any { it.role == "assistant" && it.toolCalls?.any { c -> c.id == "call_keep_1" } == true }
        )
    }

    /**
     * 「先取材再动笔」的判据在三层提示里口径必须一致。
     * 曾经三层各写一遍且只覆盖「查新闻/资料/对比/总结/预测」，
     * 结果写作类任务落在枚举外，模型直接凭记忆成文。
     */
    @Test
    fun researchCriterionStaysConsistentAcrossPromptLayers() {
        assertTrue(
            "系统提示需按产出物判定是否检索",
            SystemPrompt.PREFIX.contains("产出物") &&
                SystemPrompt.PREFIX.contains("不得凭模型记忆直接成文")
        )
        assertTrue(
            "研究预算要说清不是免检索许可",
            SystemPrompt.PREFIX.contains("只管检索广度")
        )
        val desc = WebSearchTool().description
        assertTrue("web_search 描述要与系统提示同判据", desc.contains("先搜索再动笔"))
    }

    /**
     * 后台子代理在**后续回合**仍须真能启动。
     * 曾经的错误做法是在回合收口时取消整个 subagentScope 的 Job：引擎实例被复用时，
     * 第二个回合的 launch 会静默不执行（不报错、不落转录、句柄都不注册）。
     */
    @Test
    fun backgroundSpawnStillLaunchesOnLaterTurns() = runBlocking {
        val client = FakeClient(
            listOf(
                saying("第一轮先结束"),
                callingTool("spawn_agent", "b1", "{\"task\":\"调研一下子代理\",\"background\":true}"),
                saying("兜底脚本：子代理与主循环都会走到这里收尾")
            )
        )
        val e = engine(client, tmpDir())
        e.runTurn("第一句", {}, {})
        e.runTurn("第二句", {}, {})
        // 子代理共用同一个 fake client：它走的是只读工具面（有 read、无 bash），据此判定它确实跑起来过
        val deadline = System.currentTimeMillis() + 5000
        fun researchRoundSeen() = client.requests.any { (_, tools) ->
            tools.contains("read") && !tools.contains("bash")
        }
        while (System.currentTimeMillis() < deadline && !researchRoundSeen()) {
            kotlinx.coroutines.delay(50)
        }
        assertTrue("第二个回合的 background 子代理必须真的启动过", researchRoundSeen())
    }

    /** P3-B 检索硬预算：只夹检索类工具、用完不消耗额度、也不把它算成工具失败。 */
    @Test
    fun retrievalBudgetCapsOnlyRetrievalTools() {
        val b = RetrievalBudget(3)
        repeat(3) { org.junit.Assert.assertNull("前 3 次检索应放行", b.admit("web_search")) }
        val denied = b.admit("web_fetch")
        assertTrue("第 4 次应被预算拦下", denied != null && denied.contains("检索预算已用完"))
        org.junit.Assert.assertNull("非检索工具不受预算影响", b.admit("read"))
        assertTrue("超额调用不应继续消耗额度", b.admit("web_search") != null)
        assertEquals(3, b.usedCount)
    }

    /**
     * 写前快照失败必须抛出来，而不是静默返回 null。
     * 快照是 /undo 的唯一依据：以前 SnapshotHook 用 runCatching 吞掉一切，
     * 用户以为改错了能回滚，其实底片根本没拍到，而真机 logcat 还被 Flyme 抑制。
     */
    @Test
    fun snapshotFailureIsNotSwallowed() = runBlocking {
        val parent = tmpDir()
        val blockedFilesDir = File(parent, "occupied").apply { writeText("我是文件不是目录") }
        val hook = SnapshotHook(blockedFilesDir, "session-1", null)
        val call = ToolCallData("c1", "write", """{"path":"a.txt","content":"hello"}""")
        val args = kotlinx.serialization.json.Json.parseToJsonElement(call.argumentsJson)
            as kotlinx.serialization.json.JsonObject
        val ctx = com.haoai.agent.agent.tools.ToolContext(
            null, null, com.haoai.agent.agent.tools.TodoStore(blockedFilesDir), blockedFilesDir
        )
        var threw: Exception? = null
        try {
            org.junit.Assert.assertNull("快照成功时应返回 null（不拦截执行）", hook.before(call, args, ctx))
        } catch (e: Exception) {
            threw = e
        }
        assertTrue("目录不可写时快照必须抛出，交给引擎显式标注不可回滚", threw != null)
    }

    /**
     * 造一段历史。chars 控制单条大小：
     * 默认 4000 字符（40 条 ≈ 4 万 token，足够让 keepRecentTokens=18000 的水位推进）；
     * 想单独验水位时把 chars 调小，让 token 预算远大于历史，排除选窗逻辑的干扰。
     */
    private fun longSession(n: Int = 40, chars: Int = 4000): StoredSession {
        val s = StoredSession.create(null)
        repeat(n) { i ->
            s.messages.add(
                com.haoai.agent.data.StoredMessage(
                    role = if (i % 2 == 0) ChatMessage.ROLE_USER else ChatMessage.ROLE_ASSISTANT,
                    content = "MSG$i|" + "x".repeat(chars)
                )
            )
        }
        return s
    }

    /**
     * 压缩必须是「追加式」：给会话打水位标记，而不是把旧消息物理删掉。
     * 旧实现压完直接 msgs.clear() 重写，摘要一旦写坏或压过头就没有任何补救办法，
     * 用户在聊天页也会看到历史凭空消失。
     */
    @Test
    fun compactionMarksWatermarkInsteadOfDeletingHistory() = runBlocking {
        val s = longSession()
        val client = FakeClient(listOf(saying("这是一段压缩摘要")))
        val before = s.messages.size
        val summary = engine(client, tmpDir(), session = s).compactNow()
        assertTrue("应生成摘要", !summary.isNullOrBlank())
        assertEquals("压缩后原文必须仍在会话里（可回查、可重压）", before, s.messages.size)
        org.junit.Assert.assertNotNull("水位标记应推进到被摘要覆盖的最后一句", s.compactedThroughId)
        assertTrue("水位应落在真实存在的消息上", s.messages.any { it.id == s.compactedThroughId })
    }

    /** 水位之前的历史不再进请求；水位之后照常发。 */
    @Test
    fun requestSkipsHistoryBeforeWatermark() = runBlocking {
        // chars 取小：让 token 预算（≈18 万）远大于整段历史（约 6 千），
        // 这样"发几条"只可能由水位决定，排除选窗预算的干扰。
        val s = longSession(chars = 600)
        s.compactedThroughId = s.messages[29].id   // 前 30 条视为已被摘要覆盖
        val client = FakeClient(listOf(saying("完成")))
        engine(client, tmpDir(), session = s, contextLength = 200_000).runTurn("继续", {}, {})
        // 主聊天那一轮 = 工具面最全的那次请求。按系统提示前缀挑会挑错：
        // 压缩前的记忆冲刷也带同一份系统提示，但它只挂 memory 工具，且按设计带整段历史做提取。
        val chat = client.requests.maxByOrNull { it.second.size }!!.first
        val sent = chat.joinToString("\n") { it.content.orEmpty() }
        val markers = (0..39).map { "MSG$it" }.filter { sent.contains("$it|") }
        assertEquals("只应发水位之后的 10 条：$markers", (30..39).map { "MSG$it" }, markers)
        assertTrue("水位之后的 MSG39 必须照发", sent.contains("MSG39|"))
    }

    /**
     * 续跑必须连着上次算轮数。
     * 此前"断点续跑"是重开一轮：每中断一次，MAX_TURNS 预算就重新给满，
     * 一个跑偏的长任务可以无限续下去而不触顶。
     */
    @Test
    fun resumedTurnCountsAgainstCumulativeRoundBudget() = runBlocking {
        // 每轮都调一个未知工具（确定失败、无副作用、不触网），把轮数一路推到上限
        val endless = listOf(
            listOf(
                SseEvent.Delta("再试一次"),
                SseEvent.Completed(listOf(ToolCallData("x", "no_such_tool", "{}"))),
                SseEvent.Usage(10, 1)
            )
        )
        val s = StoredSession.create(null)
        s.runTurnsUsed = 58
        val resuming = FakeClient(endless)
        // 关掉成本/圈数熔断，只留轮数上限这一条路径
        engine(resuming, tmpDir(), session = s, turnTokenCap = 0, toolCallCap = 0)
            .runTurn("继续", {}, {}, resuming = true)
        assertEquals("已用 58 轮 + 上限 60 → 只该再发 2 次请求", 2, resuming.requests.size)
        assertTrue(
            "封顶提示要说清是累计的",
            s.messages.any { (it.content ?: "").contains("含续跑") && (it.content ?: "").contains("上限（60）") }
        )

        val s2 = StoredSession.create(null)
        s2.runTurnsUsed = 58           // 上一轮遗留的计数
        val fresh = FakeClient(endless)
        // 不真跑满 60 轮（每轮重建上下文是平方级，会把套件拖到 6 分钟）：
        // 圈数熔断设 12，只要能证明轮次是从 0 起算、没被遗留的 58 卡住就够了。
        // toolFailCap=0：每轮都调未知工具，否则 8 次连续失败熔断会先到（那是另一条测试锁的行为）。
        engine(fresh, tmpDir(), session = s2, turnTokenCap = 0, toolCallCap = 12, toolFailCap = 0)
            .runTurn("新任务", {}, {})   // 不续跑：必须从 0 重新计
        assertTrue("新任务不该被遗留计数卡住，实际 ${fresh.requests.size} 轮", fresh.requests.size >= 12)
        assertTrue(
            "新任务的封顶文案不该带「含续跑」（那是续跑路径专属）",
            s2.messages.none { (it.content ?: "").contains("含续跑") }
        )
    }

    /**
     * fail-closed：写前快照打不出来时，改动不得执行。
     * 造失败的方式是把 appFilesDir/snapshots 占成一个普通文件（跨平台确定失败，
     * 不像 setWritable(false) 在 Windows 上会被忽略）。
     */
    @Test
    fun blockedSnapshotPreventsTheWrite() = runBlocking {
        val dir = tmpDir()
        File(dir, "snapshots").writeText("我是文件，不是目录")
        val s = StoredSession.create(null)
        val client = FakeClient(
            listOf(
                callingTool("write", "w1", """{"path":"a.txt","content":"hi"}"""),
                saying("收尾")
            )
        )
        engine(client, dir, session = s).runTurn("写个文件", {}, {})
        val toolMsg = s.messages.firstOrNull { it.role == "tool" && it.toolName == "write" }
        assertTrue("应被 fail-closed 拦下", toolMsg != null && (toolMsg.content ?: "").contains("已取消本次 write"))
        assertTrue("拦下时要说明原因", (toolMsg!!.content ?: "").contains("无法回滚"))
        assertTrue("文件不该被写出来", !File(dir, "a.txt").exists())
    }

    /**
     * 对偶锁：快照正常时 write 绝不能被 fail-closed 拦掉。
     * （backend=null 会让工具本身报"无工作区"，这里只断言没走到取消分支。）
     */
    @Test
    fun normalWriteIsNotBlockedWhenSnapshotWorks() = runBlocking {
        val dir = tmpDir()
        val s = StoredSession.create(null)
        val client = FakeClient(
            listOf(
                callingTool("write", "w1", """{"path":"a.txt","content":"hi"}"""),
                saying("收尾")
            )
        )
        engine(client, dir, session = s).runTurn("写个文件", {}, {})
        val toolMsg = s.messages.firstOrNull { it.role == "tool" && it.toolName == "write" }
        org.junit.Assert.assertNotNull("write 应有结果落库", toolMsg)
        assertTrue(
            "快照目录可正常创建时不该被拦：${toolMsg?.content?.take(80)}",
            !(toolMsg!!.content ?: "").contains("已取消本次")
        )
        assertTrue("快照确实拍到了", File(dir, "snapshots/${s.id}").listFiles()?.isNotEmpty() == true)
    }

    /** 预置调研技能是索引注入的一部分，触发词漏了「作文/报告」就永远不会被选中。 */
    @Test
    fun bundledResearchSkillCarriesWritingTrigger() {
        val ws = tmpDir()
        val marker = tmpDir()
        // init 把技能根定在 <ws>/skills，与下面的断言路径一致（useWorkspaceDir 传的是 skills 目录本身，别混）
        SkillStore.init(ws)
        SkillStore.seedBundledResearchSkill(marker)
        val file = File(ws, "skills/web-research/SKILL.md")
        assertTrue("预置技能应落到工作区 skills 目录：${file.absolutePath}", file.exists())
        val text = file.readText()
        assertTrue("技能正文需含产出物判据", text.contains("作文") && text.contains("判据"))
    }

    /** 主代理拿到的研究纪律不会自动传给子代理——子代理必须自带一份，否则它不收敛。 */
    @Test
    fun researchSubagentCarriesItsOwnResearchDiscipline() = runBlocking {
        val client = FakeClient(
            listOf(
                callingTool("spawn_agent", "s1", "{\"task\":\"调研主流 AI Agent 框架\",\"mode\":\"research\"}"),
                saying("子代理结论：LangGraph、AutoGen、CrewAI。"),
                saying("主代理收尾")
            )
        )
        engine(client, tmpDir()).runTurn("派个子代理", {}, {})
        val sub = client.requests.firstOrNull { (msgs, tools) ->
            !tools.contains("bash") &&
                msgs.any { it.role == "system" && it.content.orEmpty().startsWith("[SUBAGENT]") }
        }
        assertTrue("子代理应收到自己的系统提示", sub != null)
        val sys = sub!!.first.first { it.role == "system" }.content.orEmpty()
        assertTrue("子代理提示需含取材判据", sys.contains("取材判据") && sys.contains("web_search"))
        assertTrue("子代理提示需含收敛预算与劣质来源警示", sys.contains("研究预算") && sys.contains("导航站"))
    }

    /**
     * 子代理的模型请求遇瞬态错误须退避重试，而不是当场判 ERROR。
     * 此前一次网关抖动就废掉整路调研（spawn_agents 并行时表现为兄弟路正常、这一路凭空失败）。
     */
    @Test
    fun subagentRetriesTransientStreamError() = runBlocking {
        val session = StoredSession.create(null)
        // 序号 1 = 子代理的首次请求，让它connection reset；重试落到序号 2 拿到结论
        val client = FakeClient(
            script = listOf(
                callingTool("spawn_agent", "s1", "{\"task\":\"调研\",\"mode\":\"research\"}"),
                saying("这次会被丢掉"),
                saying("子代理结论：LangGraph、AutoGen、CrewAI。"),
                saying("主代理收尾")
            ),
            failOn = setOf(1)
        )
        engine(client, tmpDir(), session = session).runTurn("派个子代理", {}, {})
        val spawnMsg = session.messages.lastOrNull { it.role == "tool" && it.toolName == "spawn_agent" }
        assertTrue("spawn_agent 结果应落库", spawnMsg != null)
        val content = spawnMsg!!.content.orEmpty()
        assertTrue("瞬态错误应被重试掉，不该以执行失败收场：$content", !content.contains("执行失败"))
        assertTrue("重试后应拿到子代理结论", content.contains("LangGraph"))
        // 主1 + 子失败 + 子重试 + 主2 == 4：多一次即重试未生效，少一次即子代理没跑
        assertEquals(4, client.requests.size)
    }

    /**
     * 重发前必须把残句**连同上一截思考**一起丢掉，并通知 UI 撤气泡。
     *
     * 两个真实缺陷都在这里被锁住：
     * 1. 旧实现只清正文不清思考 → 重试成功的"完整答案"会挂着"旧思考链"落库，
     *    用户在思考区看到一段和答案无关的推理（overflow 重试路径当时就没清）。
     * 2. 引擎清了但 UI 不知道 → 屏幕上那段残句永远不撤，退避最长 25s 期间它
     *    冒充正文，而落库的那条消息里根本没有它（屏幕与历史分叉）。
     */
    @Test
    fun retryDropsStaleTextAndReasoning() = runBlocking {
        val session = StoredSession.create(null)
        val events = mutableListOf<TurnEvent>()
        val client = FakeClient(
            script = listOf(
                listOf(SseEvent.Reasoning("旧思考链"), SseEvent.Delta("半截答案")),
                saying("完整答案")
            ),
            breakAfterEmit = setOf(0)
        )
        engine(client, tmpDir(), session = session).runTurn("开始", {}, { events += it })
        val assistants = session.messages.filter { it.role == ChatMessage.ROLE_ASSISTANT }
        assertEquals("重发不该留下两条 assistant（残句那条不该落库）", 1, assistants.size)
        assertEquals("完整答案", assistants[0].content)
        org.junit.Assert.assertNull("重试答案不得挂着上一截思考链", assistants[0].reasoning)
        assertTrue("残句要发事件通知 UI 撤掉", events.contains(StreamReset))
    }

    /**
     * 出站历史必须是「旧→新」，最后一条就是本轮正在问的那句。
     *
     * d55ac4d 把"取最近 MAX_HISTORY 条"改写成 token 预算窗口时，只留了一层 asReversed
     * （原写法 `messages.asReversed().take(N).asReversed()` 的第二层是恢复正序用的），
     * 于是整段历史以**新→旧**发出：模型看到的最后一条变成会话里最老的那句。
     * 真机外显为"追问当没看见、把第一个问题又答一遍"，单测此前全都查不出来 ——
     * 只查成员/配对不查顺序的断言，对顺序回归是零保护。
     */
    @Test
    fun outboundHistoryKeepsChronologicalOrder() = runBlocking {
        val session = StoredSession.create(null)
        session.messages.add(StoredMessage(role = ChatMessage.ROLE_USER, content = "第一问"))
        session.messages.add(StoredMessage(role = ChatMessage.ROLE_ASSISTANT, content = "第一答"))
        session.messages.add(
            StoredMessage(
                role = ChatMessage.ROLE_ASSISTANT, content = "",
                toolCalls = listOf(StoredToolCall("c1", "bash", "{\"command\":\"ls\"}"))
            )
        )
        session.messages.add(
            StoredMessage(
                role = ChatMessage.ROLE_TOOL, content = "file.txt",
                toolCallId = "c1", toolName = "bash"
            )
        )
        // 工具调用要配上结果，否则 pairSanitized 会把这一对全丢掉（那是另一条测试关注点）
        val client = FakeClient(listOf(saying("第二答")))
        engine(client, tmpDir(), session = session).runTurn("第二问", {}, {})
        // 取"主聊天那一轮"：工具面最全的那次请求（记忆冲刷带同一份系统提示且按设计携带整段历史）
        val sent = client.requests.maxByOrNull { it.second.size }!!.first
            .filter { it.role != "system" }.map { it.role to it.content }
        assertEquals(
            listOf(
                "user" to "第一问",
                "assistant" to "第一答",
                "assistant" to null,
                "tool" to "file.txt",
                "user" to "第二问"
            ),
            sent
        )
    }

    /**
     * 续跑前必须先把「已发起、无结果」的工具调用补成明确的未确认结果。
     *
     * 顺序与内容都关键：tool 消息必须紧跟它所属的 assistant（中间插一条 user 会被
     * OpenAI 兼容端以 400 拒掉）；不补的话 pairSanitized 会把那条 assistant 整条丢出
     * 请求（中断前做了什么模型完全看不见），而且写类工具会被原样重跑一遍 —— 重复副作用。
     * 三家参考实现的同一条纪律：绝不自动重放非幂等工具。
     */
    @Test
    fun resumedTurnClosesDanglingToolCalls() = runBlocking {
        val session = StoredSession.create(null)
        session.messages.add(
            StoredMessage(
                role = ChatMessage.ROLE_ASSISTANT, content = "",
                toolCalls = listOf(
                    StoredToolCall("c1", "bash", "{\"command\":\"rm -f note.txt\"}"),
                    StoredToolCall("c2", "web_search", "{\"query\":\"x\"}")
                )
            )
        )
        val client = FakeClient(listOf(saying("已核实并继续")))
        engine(client, tmpDir(), session = session)
            .runTurn("[系统恢复] 继续", {}, {}, resuming = true)
        assertEquals(
            "先补两条结果，再落恢复指令：${session.messages.map { it.role }}",
            listOf(
                ChatMessage.ROLE_ASSISTANT to null,
                ChatMessage.ROLE_TOOL to "c1",
                ChatMessage.ROLE_TOOL to "c2",
                ChatMessage.ROLE_USER to null
            ),
            session.messages.take(4).map { it.role to it.toolCallId }
        )
        val writeNote = session.messages[1].content
        val readNote = session.messages[2].content
        assertTrue("写类调用要禁止盲目重跑：$writeNote", writeNote.contains("禁止") && writeNote.contains("核实"))
        assertTrue("只读调用可以直接重试：$readNote", readNote.contains("重新调用"))
        // 补了结果，那条带调用的 assistant 才留得住（不补就会被 pairSanitized 丢掉）
        val sent = client.requests.maxByOrNull { it.second.size }!!.first
        assertTrue(sent.any { it.role == "assistant" && !it.toolCalls.isNullOrEmpty() })
    }

    /**
     * 排队消息提升为任务时不能再落一遍用户指令。
     * 入队那一刻它已经在历史里（用户在气泡里看到了），旧实现没有 appendUser 开关，
     * 于是一条排队指令在会话里出现 3 份（入队 1 + 提升时 VM 补 1 + 引擎再落 1），
     * 模型读到重复指令会把上一个任务又答一遍（模拟器实测）。
     */
    @Test
    fun promotedQueuedTaskDoesNotDuplicateUserMessage() = runBlocking {
        val session = StoredSession.create(null)
        session.messages.add(StoredMessage(role = ChatMessage.ROLE_USER, content = "排队中的指令"))
        val client = FakeClient(listOf(saying("好的")))
        engine(client, tmpDir(), session = session)
            .runTurn("排队中的指令", {}, {}, appendUser = false)
        assertEquals(1, session.messages.count { it.role == ChatMessage.ROLE_USER })
        assertEquals("好的", session.messages.last().content)
        // 不落库≠不干活：出站请求里必须仍带着这条指令，否则模型根本不知道要做什么
        assertTrue(client.requests.last().first.any { it.role == "user" && it.content == "排队中的指令" })

        // 对照：普通回合仍然要落（默认值没被改坏）
        val s2 = StoredSession.create(null)
        engine(FakeClient(listOf(saying("行"))), tmpDir(), session = s2).runTurn("新指令", {}, {})
        assertEquals(1, s2.messages.count { it.role == ChatMessage.ROLE_USER })
    }
}
