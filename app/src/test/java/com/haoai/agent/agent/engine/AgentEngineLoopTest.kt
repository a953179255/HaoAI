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
        private val breakAfterEmit: Set<Int> = emptySet(),
        /** 断流时抛什么异常（默认 IOException=瞬态会退避重试；可传 ProviderHttpException(400) 走非瞬态直断）。 */
        private val breakWith: (Int) -> Exception = { java.io.IOException("connection reset") },
        /**
         * 按请求特征命中的调用先挂起 [slowForMs] 毫秒：制造"主回合已收口、
         * 子代理仍在跑"的窗口。入参 = (消息列表, 工具名清单)——判别子代理要用
         * 系统提示前缀（[SUBAGENT]），工具面判别不可靠：主循环清单也含 read
         * 且未必含 bash（shell 工具不叫 bash），按工具面会把主回合自己一起慢住。
         */
        private val slowWhen: ((List<ApiMessage>, List<String>) -> Boolean)? = null,
        private val slowForMs: Long = 0,
        /** 按请求特征命中的调用先按脚本吐完事件再断流（抛 [slowBreakException]，默认非瞬态 400）。 */
        private val breakWhen: ((List<ApiMessage>, List<String>) -> Boolean)? = null,
        private val slowBreakException: Exception =
            com.haoai.agent.agent.provider.ProviderHttpException(400, "bad request")
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
            val toolNames = tools.map { it.function.name }
            requests += messages to toolNames
            val idx = n++
            slowWhen?.takeIf { it(messages, toolNames) }?.let { kotlinx.coroutines.delay(slowForMs) }
            if (idx in failOn) throw java.io.IOException("connection reset")
            val events = script[minOf(idx, script.lastIndex)]
            if (idx in breakAfterEmit || breakWhen?.invoke(messages, toolNames) == true) return flow {
                events.forEach { emit(it) }
                throw if (breakWhen?.invoke(messages, toolNames) == true) slowBreakException else breakWith(idx)
            }
            return flow { events.forEach { emit(it) } }
        }

        override suspend fun testConnection(provider: ProviderConfig, apiKey: String) = Result.success("ok")
    }

    /** 子代理请求的稳定判别器：系统提示以 [SUBAGENT] 开头（主循环/记忆冲刷都不带）。 */
    private val isSubagentRequest: (List<ApiMessage>, List<String>) -> Boolean =
        { msgs, _ -> msgs.firstOrNull()?.role == "system" && msgs.first().content.orEmpty().startsWith("[SUBAGENT]") }

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
        toolCallCap: Int = 80,
        steeringDrain: (String) -> Unit = {},
        backend: com.haoai.agent.platform.FileBackend? = null,
        flagOverrides: Map<String, Boolean> = emptyMap(),
        onBackgroundSubagentDone: ((String, SubagentHandle) -> Unit)? = null
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
            backend = backend,
            appFilesDir = dir,
            // v2：脑根与 TodoStore(dir) 同源（测试里的 todoStore 落 dir/todos，引擎必须读同一处）
            brainDir = dir,
            workspaceLabel = "test",
            toolFailCap = toolFailCap,
            turnTokenCap = turnTokenCap,
            toolCallCap = toolCallCap,
            steeringDrain = steeringDrain,
            flagOverrides = flagOverrides,
            onBackgroundSubagentDone = onBackgroundSubagentDone
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
     * 2026-10-16 用户实测修复：流式中异常中断（供应商 500/断网/看门狗）时，本轮已产出
     * 的正文必须补落历史——原先只留 error 消息，endStreaming 一清连带着丢失；用户随后
     * 发的"继续执行"落在空档处，观感像插到了旧工作过程前面（用户原话："显示在第一次
     * 发送消息的下面，Agent 工作过程之上"）。补落内容带"意外中断"标记，且排在 error
     * 消息之前（顺序=中断处 → 新消息）。
     * 用 ProviderHttpException(400) 断流：非瞬态不触发 5/12/25s 退避，测试秒级完成。
     */
    @Test
    fun streamFailureKeepsPartialAnswerInHistory() = runBlocking {
        val session = StoredSession.create(null)
        val client = FakeClient(
            listOf(
                listOf(
                    SseEvent.Delta("半截的答案正文-MARKER"),
                    SseEvent.Usage(50, 10)
                )
            ),
            breakAfterEmit = setOf(0),
            breakWith = { com.haoai.agent.agent.provider.ProviderHttpException(400, "gateway reset") }
        )
        engine(client, tmpDir(), session = session).runTurn("开始", {}, {})
        val assistants = session.messages.filter { it.role == ChatMessage.ROLE_ASSISTANT }
        // ① 半截正文必须补落（不再丢失）
        val partial = assistants.firstOrNull { it.content?.contains("半截的答案正文-MARKER") == true }
        assertTrue("异常中断必须补落本轮已产出的正文（防内容丢失）", partial != null)
        // ② 带意外中断标记（用户能看出这是被动中断而非正常结束）
        assertTrue(
            "补落内容应带意外中断标记",
            partial!!.content?.contains("意外中断") == true
        )
        // ③ error 消息仍在，且排在补落正文之后（用户后续发的消息会落到 error 后=中断处下方）
        val errIdx = assistants.indexOfFirst { it.error }
        assertTrue("失败轮应落 error 消息", errIdx >= 0)
        assertTrue(
            "error 消息应排在补落正文之后（保住'中断处→新消息'的顺序）",
            errIdx > assistants.indexOf(partial)
        )
    }

    /**
     * A3：供应商回 length（max_tokens 截断）时，本轮流出的工具调用必须整批按失败
     * 回填、零执行——参数可能是半截 JSON，截在合法边界的还会"看着像能跑"。
     * 回归锁：pi failToolCallsFromTruncatedMessage 同款语义。
     */
    @Test
    fun lengthTruncationFailsWholeBatchWithoutExecuting() = runBlocking {
        val client = FakeClient(
            listOf(
                // 流尾带 finish_reason=length：参数截断
                listOf(
                    SseEvent.Delta("正在写"),
                    SseEvent.Completed(
                        listOf(ToolCallData("trunc-1", "todo", "{\"op\":\"add\",\"text\":\"只写了一半")),
                        "length"
                    ),
                    SseEvent.Usage(100, 5)
                ),
                saying("收尾")
            )
        )
        val session = StoredSession.create(null)
        engine(client, tmpDir(), session = session).runTurn("开始", {}, {})
        // 工具未执行：历史里那条 tool 结果必须是 error 标记
        val toolMsgs = session.messages.filter { it.role == ChatMessage.ROLE_TOOL }
        assertTrue("截断轮不应执行任何工具", toolMsgs.size <= 1)
        assertTrue(
            "回填的 tool 结果必须标记 error（模型可见、可自愈）",
            toolMsgs.isNotEmpty() && toolMsgs.all { it.error }
        )
        // 且下一轮请求历史里出现截断提示（引导拆小重试）
        assertTrue(
            "应向模型说明截断并引导拆小",
            client.requests[1].first.any { m -> m.content?.contains("截断") == true }
        )
    }

    /**
     * A2 运行中插话：插话消息入队时已落进会话历史，引擎每轮组装请求天然带上——
     * 请求里必须出现该消息（模型可见）；且每环顶都会调 steeringDrain（销账入口）。
     * 防回归：把插话改成"只在回合结束后当新任务跑"会漏掉这条断言。
     */
    @Test
    fun interjectionIsVisibleToNextRoundRequest() = runBlocking {
        val session = StoredSession.create(null)
        // 模拟"用户跑动中插话"：消息已在历史里（VM 入队时落的）
        session.messages.add(
            StoredMessage(
                id = "steer-1", role = ChatMessage.ROLE_USER,
                content = "改成只统计 src 目录"
            )
        )
        var drainCalls = 0
        val client = FakeClient(
            listOf(
                callingTool("todo", "t1", "{\"op\":\"list\"}"),
                saying("收尾")
            )
        )
        engine(client, tmpDir(), session = session, steeringDrain = { _ -> drainCalls++ })
            .runTurn("开始", {}, {})
        // 第二轮请求的历史里必须含插话正文
        val secondRoundHistory = client.requests[1].first
        assertTrue(
            "插话消息应出现在下一轮请求历史里（模型看得见）",
            secondRoundHistory.any { m -> m.content?.contains("改成只统计 src 目录") == true }
        )
        // 环顶销账：两个请求轮 = 至少调两次
        assertTrue("steeringDrain 应每环顶被调（销账入口）", drainCalls >= 2)
    }

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
     * 供应商每轮回传同一个**位置型** call id 时，引擎必须把它改成会话内唯一。
     *
     * 症状（2026-09-20 设备实测）：商汤 glm-5.2 经网关给 `call_0_0 / call_1_1…`，15 轮只产出
     * 4 个不同 id；UI 行状态按 id 查 `liveTools`，上一轮的 ERROR 串到这一轮成功的调用上，
     * 一次调研 36 次调用里只有 3 次真失败，屏幕上却满屏「✕ 失败」。
     */
    @Test
    fun collidingCallIdsAreMadeUniqueAcrossRounds() = runBlocking {
        val client = FakeClient(
            listOf(
                callingTool("no_such_tool", "call_0_0", "{}"),
                callingTool("no_such_tool", "call_0_0", "{\"n\":2}"),
                saying("完成")
            )
        )
        val session = StoredSession.create(null)
        engine(client, tmpDir(), session = session).runTurn("开始", {}, {})
        val ids = session.messages
            .filter { it.role == ChatMessage.ROLE_ASSISTANT }
            .flatMap { it.toolCalls.map { c -> c.id } }
        assertEquals("两轮各落一条调用", 2, ids.size)
        assertEquals("跨轮撞号会让按 id 的查表互相顶名：$ids", ids.toSet().size, ids.size)
        // 改写必须两侧同步：出站请求里每条 tool 结果都要能配上同一份 assistant 调用
        val sent = client.requests.last().first
        val assistantIds = sent.filter { it.role == "assistant" }
            .flatMap { it.toolCalls?.map { c -> c.id } ?: emptyList() }.toSet()
        val toolIds = sent.filter { it.role == "tool" }.mapNotNull { it.toolCallId }
        assertEquals(2, toolIds.size)
        assertTrue("tool 结果配不上 assistant：$toolIds vs $assistantIds", toolIds.all { it in assistantIds })
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
        val desc = WebSearchTool().desc
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

    /**
     * P4 后台子代理跨回合存活：主回合收口（finishTurn）时后台句柄不得被取消——
     * 旧实现无差别 CANCELLED，后台模式名存实亡（子代理没干完就被杀，结果永远收不到）。
     * 回归锁：慢响应制造「回合已结束、子代理仍在跑」窗口，断言不被置 CANCELLED、
     * 最终 DONE、且 onBackgroundSubagentDone 以 (sessionId, handle) 触发一次。
     */
    @Test
    fun backgroundSubagentSurvivesTurnEndAndNotifies() = runBlocking {
        val session = StoredSession.create(null)
        val notified = java.util.concurrent.ConcurrentLinkedQueue<Pair<String, SubagentHandle>>()
        val client = FakeClient(
            script = listOf(
                // 主回合 1：派后台子代理
                callingTool("spawn_agent", "b1", "{\"task\":\"慢速调研\",\"background\":true}"),
                // 槽 1 与槽 2 都是"无工具调用的子代理结论"：子代理请求与主回合 2 的落位
                // 有先后竞态，但无论谁占哪个槽——主回合 2 无工具调用照常收口，
                // 子代理首请求拿到结论即 DONE（脚本按请求序号取槽，不能假设落位）。
                saying("后台结论-WAKE-MARKER"),
                saying("后台结论-WAKE-MARKER")
            ),
            slowWhen = isSubagentRequest,
            slowForMs = 800
        )
        val e = engine(client, tmpDir(), session = session) { sid, h ->
            notified.add(sid to h)
        }
        e.runTurn("派个后台子代理", {}, {})
        // 等句柄注册（后台协程从派发到 runSubAgent 有毫秒级窗口）
        var h: SubagentHandle? = e.activeSubagents["sa_1"]
        val regDeadline = System.currentTimeMillis() + 5000
        while (h == null && System.currentTimeMillis() < regDeadline) {
            kotlinx.coroutines.delay(20)
            h = e.activeSubagents["sa_1"]
        }
        org.junit.Assert.assertNotNull("后台句柄应已注册", h)
        assertTrue("句柄必须带后台标记", h!!.isBackground)
        // 回合刚收口：后台子代理必须还活着（旧实现此刻已是 CANCELLED）
        assertTrue(
            "回合结束后后台子代理不得被取消，实际 ${h.state}",
            h.state == "RUNNING"
        )
        // 等它自然跑完
        val deadline = System.currentTimeMillis() + 8000
        while (h.state == "RUNNING" && System.currentTimeMillis() < deadline) {
            kotlinx.coroutines.delay(50)
        }
        assertEquals("后台子代理应自然完成（而非被杀）", "DONE", h.state)
        // 完成上报恰一次，带会话 id 与结果
        val notifyDeadline = System.currentTimeMillis() + 5000
        while (notified.isEmpty() && System.currentTimeMillis() < notifyDeadline) {
            kotlinx.coroutines.delay(20)
        }
        assertEquals("完成回调应恰好触发一次", 1, notified.size)
        assertEquals(session.id, notified.peek()!!.first)
        assertTrue(
            "回调句柄的 finalResult 应带子代理结论",
            notified.peek()!!.second.finalResult.orEmpty().contains("WAKE-MARKER")
        )
    }

    /**
     * P4 后台子代理失败同样要通知：用户需要知道这路没干成（ZCode 后台任务失败同款通知）。
     * 非瞬态断流（400）→ runSubAgent ERROR 终态 → 回调 ok=false 且结果带失败摘要。
     */
    @Test
    fun backgroundSubagentFailureNotifiesToo() = runBlocking {
        val session = StoredSession.create(null)
        val notified = java.util.concurrent.ConcurrentLinkedQueue<Pair<String, SubagentHandle>>()
        val client = FakeClient(
            script = listOf(
                callingTool("spawn_agent", "b1", "{\"task\":\"会失败的调研\",\"background\":true}"),
                saying("已派发"),
                saying("收尾")
            ),
            // 子代理的模型请求非瞬态断流 → runSubAgent 落 ERROR 终态
            breakWhen = isSubagentRequest
        )
        val e = engine(client, tmpDir(), session = session) { sid, h ->
            notified.add(sid to h)
        }
        e.runTurn("派个后台子代理", {}, {})
        var h: SubagentHandle? = e.activeSubagents["sa_1"]
        val regDeadline = System.currentTimeMillis() + 5000
        while (h == null && System.currentTimeMillis() < regDeadline) {
            kotlinx.coroutines.delay(20)
            h = e.activeSubagents["sa_1"]
        }
        org.junit.Assert.assertNotNull(h)
        val deadline = System.currentTimeMillis() + 8000
        while (h!!.state == "RUNNING" && System.currentTimeMillis() < deadline) {
            kotlinx.coroutines.delay(50)
        }
        assertEquals("失败路应落 ERROR 终态", "ERROR", h.state)
        val notifyDeadline = System.currentTimeMillis() + 5000
        while (notified.isEmpty() && System.currentTimeMillis() < notifyDeadline) {
            kotlinx.coroutines.delay(20)
        }
        assertEquals("失败回调应恰好触发一次", 1, notified.size)
        assertTrue(
            "失败通知的结果应带失败说明（供唤醒回合向用户交代）",
            notified.peek()!!.second.finalResult.orEmpty().contains("执行失败")
        )
    }

    /**
     * A5（2026-10-15）：子代理结果必须与主循环工具结果走**同一条溢出管道**。
     * spawn_agent 的结果在父循环里就是一个普通工具结果（finishCall → capForStore）：
     * 开 tool_result_spill 后，超长的子代理结论溢出落工作区文件、落库内容带可 read 的路径，
     * 而不是被 16000 截断后永久丢失。防回归：未来若有人给子代理结果单独开截断旁路，
     * 这条断言会失败。
     */
    @Test
    fun subagentLongResultGoesThroughSpillPipeline() = runBlocking {
        val longConclusion = "CONCLUSION-MARKER-" + "z".repeat(20_000)
        val client = FakeClient(
            listOf(
                // 主循环轮1：同步 spawn_agent
                callingTool("spawn_agent", "s1", "{\"task\":\"写一份超长报告\"}"),
                // 子代理轮（无工具调用 → finalText = 超长结论）
                saying(longConclusion),
                // 主循环轮2：收尾
                saying("收尾")
            )
        )
        val backend = RecordingBackend()
        val e = engine(
            client, tmpDir(), backend = backend,
            flagOverrides = mapOf("tool_result_spill" to true)
        )
        e.runTurn("开始", {}, {})
        // 落库的 spawn_agent 工具结果必须带溢出路径提示（证明走了 capForStore）
        val stored = sessionMessagesOf(e)
        val toolMsg = stored.lastOrNull { it.role == ChatMessage.ROLE_TOOL && it.toolCallId == "s1" }
        assertTrue("子代理结果的落库消息应存在", toolMsg != null)
        assertTrue(
            "超长子代理结果应溢出落文件并带回查路径（而非纯截断）",
            toolMsg!!.content?.contains(".haoai-output/") == true
        )
        // 文件真的写进了工作区
        assertTrue(
            "溢出文件应已写入 backend",
            backend.files.keys.any { it.startsWith(".haoai-output/") }
        )
    }

    /** 记录型 FileBackend：捕获 writeText 的文件名，供断言。 */
    private class RecordingBackend : com.haoai.agent.platform.FileBackend {
        val files = LinkedHashMap<String, String>()
        override val displayName: String = "recording-test"
        override fun shellWorkdir(): java.io.File? = null
        override suspend fun readText(rel: String, maxBytes: Int): String = files[rel] ?: ""
        override suspend fun writeText(rel: String, content: String) { files[rel] = content }
        override suspend fun delete(rel: String) { files.remove(rel) }
        override suspend fun rename(fromRel: String, newName: String) {
            val c = files.remove(fromRel) ?: return
            files[fromRel.substringBeforeLast('/') + "/" + newName] = c
        }
        override suspend fun listDir(rel: String): List<String> =
            files.keys.filter { it.startsWith(rel.trimEnd('/') + "/") }
                .map { it.substringAfterLast('/') }
        override suspend fun countTree(todaySinceMillis: Long): com.haoai.agent.platform.TreeCount =
            com.haoai.agent.platform.TreeCount(files.size, files.values.sumOf { it.length.toLong() }, 0)
        override suspend fun walk(maxEntries: Int): List<com.haoai.agent.platform.FileEntry> =
            files.keys.take(maxEntries).map {
                com.haoai.agent.platform.FileEntry(
                    path = it,
                    size = files[it]?.length?.toLong() ?: 0L,
                    lastModified = 0L
                )
            }
    }

    private fun sessionMessagesOf(e: AgentEngine) = e.session.messages

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
        val args = kotlinx.serialization.json.Json.parseToJsonElement(call.args)
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
        val freed = engine(client, tmpDir(), session = s).compactNow()
        assertTrue("应报出释放量", freed != null && freed > 0)
        assertEquals("压缩后原文必须仍在会话里（可回查、可重压）", before, s.messages.size)
        org.junit.Assert.assertNotNull("水位标记应推进到被摘要覆盖的最后一句", s.compactedThroughId)
        assertTrue("水位应落在真实存在的消息上", s.messages.any { it.id == s.compactedThroughId })
        org.junit.Assert.assertNotNull("压缩前规模要留痕（诊断压得对不对）", s.compactedTokensBefore)
        org.junit.Assert.assertNull("水位推进后旧的真值锚点必须作废", s.usageAnchor)
    }

    /**
     * 整段历史都在"最近保留窗口"里时压不动：直接返回 null，不花那次模型调用。
     * 过去这里会照常写一段摘要，而摘要作为 system 消息永久挂在每次请求上 ——
     * 一个语义是"释放空间"的命令做出 token 不减反增的反效果（设备实测 6 条小消息会话）。
     */
    @Test
    fun compactIsNoOpWhenNothingFallsOutOfKeepWindow() = runBlocking {
        val s = StoredSession.create(null)
        s.messages.add(StoredMessage(role = ChatMessage.ROLE_USER, content = "只有一句话"))
        val client = FakeClient(listOf(saying("这是一段压缩摘要")))
        val e = engine(client, tmpDir(), session = s)
        org.junit.Assert.assertNull("小会话不该压缩", e.compactNow())
        assertEquals("一次模型调用都不该花", 0, client.requests.size)
        org.junit.Assert.assertNull(s.compactionSummary)
        org.junit.Assert.assertNull(s.compactedThroughId)
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
     * 轮数上限已移除（2026-10-25 对齐 hermes：MAX_TURNS=0 不限）。
     * 旧锁「续跑必须连着上次算 60 轮预算」随上限一起作废；新锁验证：
     * ① 遗留的 runTurnsUsed 计数不再卡住任何回合（跑过 58 也不触顶）；
     * ② 全程不得出现任何轮数封顶文案（capNotice 已是死代码）。
     * 循环终止交给显式传入的圈数熔断（toolCallCap），与主路径无关。
     */
    @Test
    fun turnsUnlimitedByDefault() = runBlocking {
        // 每轮都调一个未知工具（确定失败、无副作用、不触网），把轮数一路推上去
        val endless = listOf(
            listOf(
                SseEvent.Delta("再试一次"),
                SseEvent.Completed(listOf(ToolCallData("x", "no_such_tool", "{}"))),
                SseEvent.Usage(10, 1)
            )
        )
        val s = StoredSession.create(null)
        s.runTurnsUsed = 58           // 上一轮遗留的计数
        val resuming = FakeClient(endless)
        // toolFailCap=0：否则 8 次连续失败熔断会先到（那是另一条测试锁的行为）
        engine(resuming, tmpDir(), session = s, turnTokenCap = 0, toolCallCap = 10, toolFailCap = 0)
            .runTurn("继续", {}, {}, resuming = true)
        assertTrue(
            "遗留 58 轮计数不得卡住回合（实际 ${resuming.requests.size} 轮）",
            resuming.requests.size >= 10
        )
        assertTrue(
            "轮数上限已移除，不得出现轮数封顶文案",
            s.messages.none {
                val c = it.content ?: ""
                c.contains("轮数上限") || c.contains("单次任务轮数") || c.contains("含续跑")
            }
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
     * 上下文规模按供应商真值锚定，不再被字符估算带偏。
     *
     * 故意造一个「字符估算 ≫ 真值」的会话：6 万字中文按 1.5 字/token 能估出 9 万 token，
     * 而供应商报回的 promptTokens 只有 8000。压缩触发用的是前者 —— 等于凭空把上下文
     * 放大十倍，后果是远没到窗口就提前压缩（答得比应有的浅）。
     */
    @Test
    fun contextEstimateAnchorsOnProviderUsage() = runBlocking {
        val session = StoredSession.create(null)
        val big = "中".repeat(60_000)
        session.messages.add(StoredMessage(role = ChatMessage.ROLE_USER, content = big))
        val e = engine(FakeClient(listOf()), tmpDir(), session = session)
        val (sys, tools) = e.estimateOverheadTokens()
        assertTrue("样例本身要够大才有意义", e.estimateContextTokens() > 50_000)

        // 有锚点：只补锚点之后新增的消息，开销变化按当下口径加减
        session.usageAnchor = com.haoai.agent.data.UsageAnchor(8_000, session.messages.size, sys + tools)
        assertEquals(8_000, e.estimateContextTokens())
        session.messages.add(StoredMessage(role = ChatMessage.ROLE_USER, content = "又加了一条小消息"))
        val withNew = e.estimateContextTokens()
        assertTrue("新增消息要计进来但不该回到估算量级：$withNew", withNew in 8_001..9_000)

        // 锚点不可信（编辑重发/删除截断过历史）时退回全量估算，不能拿旧真值低估
        session.usageAnchor = com.haoai.agent.data.UsageAnchor(8_000, 9_999, sys + tools)
        assertTrue("锚点失效应退回估算口径", e.estimateContextTokens() > 50_000)

        // 供应商报回 Usage 时锚点要自动落到会话上（真值是从这里来的）
        val s2 = StoredSession.create(null)
        s2.messages.add(StoredMessage(role = ChatMessage.ROLE_USER, content = "小问题"))
        val client = FakeClient(listOf(listOf(SseEvent.Delta("好的"), SseEvent.Usage(8_000, 10))))
        engine(client, tmpDir(), session = s2).runTurn("继续", {}, {})
        assertEquals(8_000, s2.usageAnchor?.promptTokens)
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

    /**
     * 成本软提醒只该影响"接下来这一次请求"，不该落进会话历史。
     * 落库的代价是实测看到的：一条 ≈120 tokens 的提醒会被之后每一轮反复重传，
     * 一个成本提醒自己制造长期成本，语义正好相反。
     *
     * 每轮用量刻意压到 11（默认 callingTool 是 105）：cap=25 时 70% 线=17.5、100% 线=25
     * 落在**不同轮次**。同一轮同时越过两条线时熔断在前（:501 早于 :609），软提醒会被跳过——
     * 那是正确行为（都要停了没必要再提醒），但测不到这条通道，所以用量必须小。
     */
    @Test
    fun budgetNudgeGoesOutWithTheRequestButNotIntoHistory() = runBlocking {
        val dir = tmpDir()
        val session = StoredSession.create(null)
        com.haoai.agent.agent.tools.TodoStore(dir).save(
            session.id, listOf(com.haoai.agent.agent.tools.TodoItem("联网调研"))
        )
        fun callWith(id: String, args: String) = listOf(
            SseEvent.Delta("思考中"),
            SseEvent.Completed(listOf(ToolCallData(id, "no_such_tool", args))),
            SseEvent.Usage(10, 1)
        )
        val client = FakeClient(listOf(callWith("a", "{}"), callWith("b", "{\"n\":2}"), saying("完成")))
        engine(client, dir, session = session, turnTokenCap = 25).runTurn("开始", {}, {})
        assertTrue(
            "提醒不该进会话历史：" + session.messages.map { it.content?.take(14) },
            session.messages.none { (it.content ?: "").contains("[成本提醒]") }
        )
        assertTrue("第 2 轮末就该加上提醒", client.requests.size >= 3)
        assertTrue(
            "提醒要随下一次请求发出去，否则等于没提醒",
            client.requests[2].first.any {
                it.role == "user" && (it.content ?: "").contains("[成本提醒]")
            }
        )
    }

    /** 任务清单已全部完成时不该再弹：用户截图那次是"文章已交付"之后还插一句成本提醒。 */
    @Test
    fun budgetNudgeIsSilentWhenTheTaskIsAlreadyDone() = runBlocking {
        val dir = tmpDir()
        val session = StoredSession.create(null)
        com.haoai.agent.agent.tools.TodoStore(dir).save(
            session.id,
            listOf(com.haoai.agent.agent.tools.TodoItem("联网调研", status = "completed"))
        )
        fun callWith(id: String, args: String) = listOf(
            SseEvent.Delta("思考中"),
            SseEvent.Completed(listOf(ToolCallData(id, "no_such_tool", args))),
            SseEvent.Usage(10, 1)
        )
        val client = FakeClient(
            listOf(callWith("a", "{}"), callWith("b", "{\"n\":2}"), callWith("c", "{\"n\":3}"), saying("完成"))
        )
        engine(client, dir, session = session, turnTokenCap = 25).runTurn("开始", {}, {})
        assertTrue(
            "任务已做完还提醒，模型只能回一句'已交付'——纯噪音",
            client.requests.flatMap { it.first }.none {
                (it.content ?: "").contains("[成本提醒]")
            }
        )
    }

    /** 提醒判据的四种组合（真实触发一次 70% 要攒十几万 token，设备构造不了，靠这里兜）。 */
    @Test
    fun budgetNudgeNeedsBothToolsAndOpenTodos() {
        assertTrue(budgetNudgeWorthIt(calledToolsThisRound = true, todoHasOpenItems = true))
        assertTrue(!budgetNudgeWorthIt(calledToolsThisRound = false, todoHasOpenItems = true))
        assertTrue(!budgetNudgeWorthIt(calledToolsThisRound = true, todoHasOpenItems = false))
        assertTrue(!budgetNudgeWorthIt(calledToolsThisRound = false, todoHasOpenItems = false))
    }
}
