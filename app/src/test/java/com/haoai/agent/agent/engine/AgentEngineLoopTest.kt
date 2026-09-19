package com.haoai.agent.agent.engine

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
import com.haoai.agent.data.StoredSession
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

    /** 记录每一轮出站的 messages 与工具清单，按脚本回放流式事件。 */
    private class FakeClient(private val script: List<List<SseEvent>>) : ProviderClient {
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
            val events = script[minOf(n, script.lastIndex)]
            n++
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

    private fun engine(client: ProviderClient, dir: File, toolFailCap: Int = 8): AgentEngine =
        AgentEngine(
            httpClient = client,
            provider = ProviderConfig(
                id = "fake", name = "fake", baseUrl = "http://127.0.0.1:1/v1", model = "fake-model"
            ),
            apiKey = "fake-key",
            customPrompt = "",
            policy = PolicyEngine(PermissionMode.YOLO),
            approve = { true },
            session = StoredSession.create(null),
            persist = {},
            backend = null,
            appFilesDir = dir,
            workspaceLabel = "test",
            toolFailCap = toolFailCap
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
}
