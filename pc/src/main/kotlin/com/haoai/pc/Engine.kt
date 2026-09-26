package com.haoai.pc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.util.UUID

/** 引擎往外冒的事件。CLI 打成文本，Web 转成 SSE。 */
sealed class Ev {
    data class TextDelta(val s: String) : Ev()
    data class TextDone(val s: String) : Ev()
    data class ToolStart(val id: String, val name: String, val brief: String) : Ev()
    data class ToolEnd(val id: String, val name: String, val ok: Boolean, val out: String, val card: String) : Ev()
    data class ApprovalRequest(val id: String, val title: String, val detail: String, val kind: String) : Ev()
    data class AskRequest(val id: String, val question: String, val options: List<String>) : Ev()
    data class Usage(val prompt: Int, val completion: Int, val turns: Int) : Ev()
    data class Todo(val items: List<String>) : Ev()
    data class Notice(val s: String) : Ev()
    data class Err(val s: String) : Ev()
}

/** 一个会话：工作区 + 档位 + 待办 + 落库文件。 */
class Session(val id: String, val workspace: File) {
    @Volatile
    var mode: String = "ask"
    val todos = mutableListOf<Todo>()
    val file: File get() = File(Env.sessionsDir, "pc-$id.json")
    val title = java.util.concurrent.atomic.AtomicReference("新会话")
}

/**
 * PC 端的回合循环。
 *
 * 形状与手机端 `AgentEngine.runTurn` 一致（模型 → 工具 → 回填 → 再模型），三处刻意不同：
 * 1. **权限闸在引擎与工具共用的 [Gate] 上**，工具自己不问人 —— 这样 CLI 和 Web 只是两份 Gate 实现；
 * 2. **落库截断走 [capForStore]**，与手机端同一份溢出语义（全文进 `.haoai-output/`，会话留头尾+路径）；
 * 3. 请求前再按 REQ_CAP 截一遍，落库的全文仍可回读 —— 别让"发给模型的窗口"决定"用户能看到多少"。
 *
 * 审批是**同步阻塞**的：一次跑一个回合，前端不答就超时拒绝（fail-closed）。
 * 跨端"审批做成持久对象 + 每秒重播"是方案里 Phase 3 的事，这里先把单机闭环做扎实。
 */
class Engine(
    val session: Session,
    val settings: PcSettings,
    val tools: List<Tool>,
    private val gate: Gate,
    private val emit: (Ev) -> Unit,
    private val client: ChatClient = chatClient(settings)
) {

    private val history = mutableListOf<Msg>()
    private val byName: Map<String, Tool> = tools.associateBy { it.name }
    var totalPrompt = 0L
        private set
    var totalCompletion = 0L
        private set
    var lastError: String? = null
        private set

    init {
        history += restore(session.file)
    }

    fun messages(): List<Msg> = history.toList()

    /**
     * 用户中断。`@Volatile` 是因为按停止的是 HTTP 线程，跑回合的是 haoai-run 线程，
     * 而 `/api/stop` **绝不能**去抢那把跑任务的锁 —— 抢了就变成"点停止要等本轮跑完"，
     * 停止按钮本身就成了它要中止的那件事的受害者。
     *
     * 只在**回合边界与工具边界**检查，不在模型流式中途打断：一次 `chat()` 是有界的，
     * 打断它要往 Provider 里塞取消令牌，换来的只是少等几秒，代价是半截 tool_call
     * 落进历史（手机端就是这么把会话写坏的）。
     */
    @Volatile
    var stopRequested = false
        private set

    fun requestStop() {
        stopRequested = true
    }

    fun todoLines(): List<String> = session.todos.mapIndexed { i, t ->
        val mark = when (t.status) {
            "done" -> "[x]"; "doing" -> "[>]"; "cancelled" -> "[-]"; else -> "[ ]"
        }
        "$mark ${i + 1}. ${t.text}"
    }

    /** 一轮用户输入 → 若干次模型往返 → 最终文本。 */
    fun submit(userText: String): String {
        if (session.title.get() == "新会话") {
            session.title.set(userText.trim().replace('\n', ' ').take(24).ifBlank { "新会话" })
        }
        history += Msg("user", userText)
        val ctx = ToolCtx(session.workspace, settings, session.mode, gate, session.todos)
        var lastText = ""
        var turnNo = 0
        var retry = 0
        stopRequested = false
        val backoffs = longArrayOf(3_000, 8_000, 20_000)

        while (turnNo < settings.maxTurns) {
            if (stopRequested) {
                // 中断要**成为数据**，不能只是"停下来"：得留一条模型下一轮看得见的记录，
                // 否则它以为刚才那件事做完了，接着往下编。
                history += Msg(
                    "user",
                    "（用户在这一轮中途按了停止。之前请求的工具调用没有全部执行完，" +
                        "继续之前先确认现状，别假设计划已经跑完。）"
                )
                emit(Ev.Notice("已按你的要求中断。"))
                break
            }
            turnNo++
            val turn = try {
                client.chat(requestMessages(), schemas()) { piece -> emit(Ev.TextDelta(piece)) }
            } catch (e: ProviderError) {
                if (e.transient && retry < backoffs.size) {
                    retry++
                    emit(Ev.Notice("网关抖动（${e.message?.take(140)}），${backoffs[retry - 1] / 1000}s 后第 $retry 次重试"))
                    Thread.sleep(backoffs[retry - 1])
                    turnNo--
                    continue
                }
                lastError = "模型调用失败：${e.message?.take(400)}"
                emit(Ev.Err(lastError!!))
                break
            } catch (e: Exception) {
                lastError = "模型调用异常：${e.message ?: e.javaClass.simpleName}"
                emit(Ev.Err(lastError!!))
                break
            }
            retry = 0
            totalPrompt += turn.usage.promptTokens
            totalCompletion += turn.usage.completionTokens
            emit(Ev.Usage(totalPrompt.toInt(), totalCompletion.toInt(), turnNo))

            if (turn.calls.isEmpty()) {
                lastText = turn.text
                history += Msg("assistant", turn.text)
                break
            }

            if (turn.text.isNotBlank()) lastText = turn.text
            history += Msg("assistant", turn.text, calls = turn.calls)

            for (call in turn.calls) {
                if (stopRequested) {
                    // 注意是 continue 不是 break：**每个 tool_call_id 都必须有一条 tool 回复**，
                    // 少一条，下一次请求就被网关判 400（OpenAI 兼容协议的硬要求，
                    // 手机端在 tool_call id 撞车那次已经付过学费）。
                    val why = "（用户在执行到这一步之前按了停止，这个调用没有执行。）"
                    history += Msg("tool", why, callId = call.id, name = call.name)
                    emit(Ev.ToolEnd(call.id, call.name, false, "已中断，未执行", "generic"))
                    continue
                }
                val tool = byName[call.name]
                if (tool == null) {
                    val why = "未知工具：${call.name}。可用的是 ${byName.keys.joinToString()}"
                    history += Msg("tool", why, callId = call.id, name = call.name)
                    emit(Ev.ToolEnd(call.id, call.name, false, why, "generic"))
                    continue
                }
                /**
                 * 第二次可见性检查 —— 这次是**执行侧**的。
                 *
                 * [schemas] 已经按开关和档位过滤过一轮，但那只管"模型看不看得见"。
                 * 模型可以凭训练记忆报一个没给它的工具名（`screen` 被别的 agent 用过就记住了），
                 * 而 `byName` 是全量注册的：少了这道检查，"关着的危险能力"就等于
                 * 只是没写进说明书，实际上一直可调用。同一个道理见 [ToolCtx.guard] 的 plan 分支。
                 */
                if (!tool.visibleWhen(settings)) {
                    val why = "工具 ${call.name} 没启用（实验特性 ${tool.flag?.key} 是关的）。" +
                        "要用户执行 haoai flags on ${tool.flag?.key} 才行；现在换个能用的方案。"
                    history += Msg("tool", why, callId = call.id, name = call.name)
                    emit(Ev.ToolEnd(call.id, call.name, false, why, "generic"))
                    continue
                }
                if (ctx.mode == "plan" && !readOnlyTool(tool)) {
                    val why = "计划模式是只读的，${call.name} 不能用。把要做的事写进计划，或用 ask_user 确认切档。"
                    history += Msg("tool", why, callId = call.id, name = call.name)
                    emit(Ev.ToolEnd(call.id, call.name, false, why, "generic"))
                    continue
                }
                val args = parseArgs(call.args)
                emit(Ev.ToolStart(call.id, call.name, brief(args)))
                val res = try {
                    tool.run(args, ctx)
                } catch (e: Exception) {
                    ToolResult("工具内部异常：${e.message ?: e.javaClass.simpleName}", true)
                }
                if (tool.name == "todo") emit(Ev.Todo(todoLines()))
                val stored = capForStore(
                    res.content, settings.storedCap, session.workspace, call.id,
                    HaoFlag.enabled(HaoFlag.TOOL_RESULT_SPILL, settings.flags)
                )
                history += Msg("tool", stored, callId = call.id, name = call.name)
                emit(Ev.ToolEnd(call.id, call.name, !res.error, stored, res.card))
            }
            session.mode = ctx.mode
        }

        if (turnNo >= settings.maxTurns) emit(Ev.Notice("已达单轮工具调用上限 ${settings.maxTurns}，先收尾。"))
        persist()
        emit(Ev.TextDone(lastText))
        return lastText
    }

    fun apiKey(): String? = System.getenv("HAOAI_API_KEY")?.takeIf { it.isNotBlank() }
        ?: runCatching { Env.apiKeyFile.takeIf { it.isFile }?.readText()?.trim() }
            .getOrNull()?.takeIf { it.isNotEmpty() }

    /** 当前这一轮模型能看见哪些工具（设置页/测试用它核对开关效果）。 */
    fun visibleToolNames(): Set<String> = schemas().map { it.name }.toSet()

    /** 计划模式下允许的工具：只读类，加计划本身要用的两件。可见性与执行侧共用这一条判据。 */
    private fun readOnlyTool(t: Tool) =
        t.kind == "read" || t.name == "todo" || t.name == "ask_user"

    /** 工具可见集：先过实验特性开关（关着=不存在），再按档位收窄。 */
    private fun schemas(): List<ToolSchema> = tools.filter { t ->
        t.visibleWhen(settings) && if (session.mode == "plan") readOnlyTool(t) else true
    }.map { ToolSchema(it.name, it.desc, it.params) }

    /** 发给模型的窗口：system + 按字符预算从前往后裁的历史，tool 结果先过 REQ_CAP。 */
    private fun requestMessages(): List<Msg> {
        val sys = Msg(
            "system", Prompt.system(
                PromptCtx(
                    workspace = session.workspace,
                    model = settings.model,
                    mode = session.mode,
                    gitRoot = gitRoot(session.workspace)
                )
            )
        )
        val body = history.map { m ->
            val c = m.content
            if (m.role == "tool" && c != null && c.length > settings.reqCap) {
                Msg(m.role, TextCap.middle(c, settings.reqCap), callId = m.callId)
            } else m
        }
        val budget = 120_000
        var used = sys.content!!.length + body.sumOf { (it.content?.length ?: 0) }
        var dropFrom = 0
        while (used > budget && dropFrom < body.size - 6) {
            used -= (body[dropFrom].content?.length ?: 0)
            dropFrom++
        }
        return listOf(sys) + body.drop(dropFrom)
    }

    private fun brief(args: JsonObject): String {
        val v = args["command"] ?: args["path"] ?: args["url"] ?: args["pattern"] ?: args["question"]
        return v?.jsonPrimitive?.content?.take(180) ?: args.toString().take(140)
    }

    private fun parseArgs(raw: String): JsonObject = runCatching {
        val t = raw.trim().ifBlank { "{}" }
        Json.parseToJsonElement(t).jsonObject
    }.getOrElse { emptyObj }

    private fun persist() {
        runCatching {
            Env.sessionsDir.mkdirs()
            session.file.writeText(
                buildJsonObject {
                    put("id", session.id)
                    put("title", session.title.get())
                    put("workspace", session.workspace.absolutePath)
                    put("mode", session.mode)
                    put("model", settings.model)
                    put("updated", System.currentTimeMillis())
                    put("promptTokens", totalPrompt)
                    put("completionTokens", totalCompletion)
                    put("todos", buildJsonArray {
                        session.todos.forEach { add(buildJsonObject { put("text", it.text); put("status", it.status) }) }
                    })
                    put("messages", buildJsonArray {
                        history.forEach { m ->
                            add(
                                buildJsonObject {
                                    put("role", m.role)
                                    put("content", m.content ?: "")
                                    m.callId?.let { put("tool_call_id", it) }
                                    if (m.name.isNotBlank()) put("name", m.name)
                                    if (m.calls.isNotEmpty()) {
                                        put("tool_calls", buildJsonArray {
                                            m.calls.forEach { c ->
                                                add(
                                                    buildJsonObject {
                                                        put("id", c.id)
                                                        put("type", "function")
                                                        put("function", buildJsonObject {
                                                            put("name", c.name)
                                                            put("arguments", c.args)
                                                        })
                                                    }
                                                )
                                            }
                                        })
                                    }
                                }
                            )
                        }
                    })
                }.toString()
            )
        }.onFailure { Env.log("engine", "会话落库失败：${it.message}") }
    }

    private fun restore(f: File): List<Msg> {
        if (!f.isFile) return emptyList()
        return runCatching {
            val o = Json.parseToJsonElement(f.readText()).jsonObject
            o["messages"]?.jsonArray?.mapNotNull { el ->
                val m = el.jsonObject
                val role = m["role"]?.jsonPrimitive?.content ?: return@mapNotNull null
                Msg(
                    role = role,
                    content = m["content"]?.jsonPrimitive?.content,
                    callId = m["tool_call_id"]?.jsonPrimitive?.content,
                    calls = m["tool_calls"]?.jsonArray?.mapNotNull { c ->
                        val fn = c.jsonObject["function"]?.jsonObject
                        val id = c.jsonObject["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                        val n = fn?.get("name")?.jsonPrimitive?.content ?: return@mapNotNull null
                        ToolCall(id, n, fn["arguments"]?.jsonPrimitive?.content ?: "{}")
                    } ?: emptyList()
                )
            } ?: emptyList()
        }.getOrElse { emptyList() }
    }

    companion object {
        private val emptyObj: JsonObject = buildJsonObject { }

        fun gitRoot(dir: File): String? {
            var c: File? = dir
            while (c != null) {
                if (File(c, ".git").exists()) return c.absolutePath
                c = c.parentFile
            }
            return null
        }
    }
}

/**
 * 会话登记。一次只跑一个回合（审批是同步阻塞的），所以这里只是个 id → Engine 的表。
 */
object Sessions {
    private val map = LinkedHashMap<String, Engine>()

    fun create(
        settings: PcSettings,
        workspace: File,
        gate: Gate,
        emit: (Ev) -> Unit,
        client: ChatClient? = null
    ): Engine {
        val s = Session(UUID.randomUUID().toString().take(8), workspace)
        s.mode = settings.permissionMode
        val e = if (client == null) Engine(s, settings, builtinTools(), gate, emit)
        else Engine(s, settings, builtinTools(), gate, emit, client)
        map[s.id] = e
        return e
    }

    fun get(id: String): Engine? = map[id]
    fun all(): List<Engine> = map.values.toList()
}
