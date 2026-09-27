package com.haoai.pc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.util.UUID

/** 引擎往外冒的事件。CLI 打成文本，Web 转成 SSE。 */
sealed class Ev {
    data class TextDelta(val s: String) : Ev()
    /** 模型的思考过程（流式）。与正文分开两条流，因为界面上是两种东西。 */
    data class ReasoningDelta(val s: String) : Ev()
    /** 一个回合的用量与耗时（不是会话累计），画在回答下面那行小字。 */
    data class TurnStats(val pt: Int, val ct: Int, val ms: Long, val turns: Int) : Ev()
    data class TextDone(val s: String) : Ev()
    data class ToolStart(
        val id: String,
        val name: String,
        val brief: String,
        /** 这次工具动的对象（写改类是路径，shell 是命令）。界面上的"回滚这次修改"要用它。 */
        val subject: String = ""
    ) : Ev()
    data class ToolEnd(
        val id: String, val name: String, val ok: Boolean, val out: String, val card: String,
        /** 行级 diff，只给界面（不进历史、不发模型）。 */
        val diff: String = "",
        /** 用户对这一步的审批结论，画在卡头。 */
        val note: String = "",
        /** 子任务的中间过程（task 工具专用），只给界面。 */
        val sub: String = ""
    ) : Ev()
    /** 子任务的动静：start/tool/err/done。界面上折进那张任务卡，不占正文。 */
    data class Sub(val label: String, val kind: String, val text: String) : Ev()
    data class ApprovalRequest(val id: String, val title: String, val detail: String, val kind: String) : Ev()
    data class AskRequest(val id: String, val question: String, val options: List<String>) : Ev()
    data class Usage(val prompt: Int, val completion: Int, val turns: Int) : Ev()
    data class Todo(val items: List<String>) : Ev()
    data class Notice(val s: String) : Ev()
    data class Err(val s: String) : Ev()

    /**
     * 会话标题变了（第一句话会把它从"新会话"改成那句话的前 24 字）。
     *
     * 必须有这个事件：标题只在回合**结束**时随 `sessions` 事件一起刷新，
     * 于是一条正在跑的长任务在侧栏和顶栏上一直叫"新会话"，
     * 多会话并行时根本分不清哪条是哪条。
     */
    data class Title(val s: String) : Ev()
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
    settings: PcSettings,
    val tools: List<Tool>,
    private val gate: Gate,
    private val emit: (Ev) -> Unit,
    client: ChatClient = chatClient(settings),
    /** 0=用户直接说话的这条会话；>0 是被派出来的子任务。子任务不许再派子任务。 */
    val depth: Int = 0
) {

    /**
     * 设置与网关客户端都是**可换的**。
     *
     * 之前它们是 `val`：在设置页改了模型或 Base URL，已经建出来的引擎还在用旧的那一份，
     * 界面上写的"下一条消息起生效"其实是假话 —— 是"下一条新建会话起才生效"。
     * 多会话并行之后这条更要紧：四条会话共用一个全局设置，改一次就得四条一起跟上。
     */
    @Volatile
    var settings: PcSettings = settings
        private set

    @Volatile
    private var client: ChatClient = client

    /** 换用新的全局设置（顺带重建 HTTP 客户端：baseUrl/model 都在里面）。 */
    fun useSettings(s: PcSettings) {
        settings = s
        client = chatClient(s)
    }

    private val history = mutableListOf<Msg>()
    private val byName: Map<String, Tool> = tools.associateBy { it.name }
    var totalPrompt = 0L
        private set
    var totalCompletion = 0L
        private set
    var lastError: String? = null
        private set

    /**
     * 早期历史的摘要（压缩后的"前情"）。null = 还没压过。
     *
     * 公开是为了测试与设置页能核对"到底压没压"，也为了落库/回读。
     * **必须声明在 `init` 之前**：Kotlin 的属性初始化器按声明顺序执行，
     * 放后面的话 `init` 里刚从磁盘读回来的 summary 会立刻被 `= null` 覆盖掉
     * —— 表现是"重启后摘要丢了，同一段历史被重新压一遍"。
     */
    var summary: String? = null
        private set

    /** 累计被折进摘要的条数（水位）。恢复会话时要连它一起恢复，否则会重复压。 */
    var compactedCount = 0
        private set

    /**
     * 上次压缩**当时**的正文规模，用来做迟滞（见 [maybeCompact]）。
     *
     * 声明位置有讲究：必须在 `init` 之前。Kotlin 的属性初始化器按声明顺序执行，
     * 放后面的话 `init` 里刚设好的基线会被 `= 0` 抹掉，表现是"重开长会话立刻多压一次"。
     */
    private var lastCompactedChars = 0

    init {
        history += restore(session.file)
        restoreSummaryMeta(session.file)
    }

    fun messages(): List<Msg> = history.toList()

    /**
     * 子任务用的客户端工厂。默认按当前设置新建一份；测试里换成脚本。
     * 没有这个口子，父会话用假网关时子任务会去敲真网关，症状是"派了子任务就卡住"。
     */
    @Volatile var childClient: (() -> ChatClient)? = null

    /**
     * 派一个子任务：独立的历史与工具循环，共用同一个权限闸与设置，
     * 跑完只把最终回答当成工具结果交回父会话。
     *
     * 为什么要独立历史：并行调研时子任务的中间过程（几十次 read/grep）如果都灌进父会话，
     * 上下文立刻被挤满，而父会话需要的只有那句结论 —— 这正是 subagent 存在的理由。
     * 深度限一层：否则模型一句"帮我把所有模块都看一遍"能给自己开出一条流水线。
     */
    /**
     * 正在跑的子任务：`label -> 子引擎`。
     *
     * 存在的唯一理由：**停掉一条跑偏的调研不该以中止整轮为代价**。
     * 以前只有"停止"这一个总闸，按下去连正在写的正文一起断掉；
     * 而子任务恰恰是最容易跑偏的那部分（模型让它"把所有模块看一遍"，一看就是四十轮）。
     * 停止仍然只在回合/工具边界生效 —— 和主循环同一套规矩，不给子任务开特例。
     */
    private val liveSubs = java.util.concurrent.ConcurrentHashMap<String, Engine>()

    /** 界面上"哪几条子任务在跑"。 */
    fun subNames(): List<String> = liveSubs.keys.toList()

    /** 请求停掉某一条子任务：true 表示这条确实在跑（跑完的、名字错的都返回 false）。 */
    fun stopSub(label: String): Boolean {
        val c = liveSubs[label] ?: return false
        c.requestStop()
        return true
    }

    /** 同名子任务要各自可停：模型很爱给两条调研起同一个标签，撞名时"停掉它"会停错那条。 */
    private fun uniqueSubName(base: String): String {
        var k = base
        var n = 2
        while (liveSubs.containsKey(k)) { k = "$base $n"; n++ }
        return k
    }

    fun spawn(rawLabel: String, prompt: String): Pair<String, String> {
        if (depth >= MAX_DEPTH)
            return ("子任务不能再派子任务（深度上限 $MAX_DEPTH）。这件事你自己动手做。" to "")
        if (prompt.isBlank()) return ("子任务没有内容，不知道要它做什么。" to "")
        val label = uniqueSubName(rawLabel.ifBlank { "子任务" })
        val s = Session("sub" + System.nanoTime().toString(16).take(8), session.workspace)
        s.mode = session.mode
        val log = StringBuilder()
        val child = Engine(
            s, settings, tools, gate,
            { ev -> forwardSub(label, ev, log) },
            childClient?.invoke() ?: chatClient(settings),
            depth = depth + 1
        )
        liveSubs[label] = child
        emit(Ev.Sub(label, "start", prompt.take(200)))
        val out = try {
            child.submit(prompt)
        } catch (e: Exception) {
            "子任务失败：" + (e.message ?: e.javaClass.simpleName)
        } finally {
            liveSubs.remove(label, child)
        }
        // 被停掉的那条**要说清是被停掉的**：只回一句"没有给出结论"，
        // 模型会以为子任务白跑，通常原地再派一条一模一样的。
        val stopped = child.stopRequested
        val text = if (stopped) "子任务被中断（用户停掉了它）。中断前它说到：" +
            out.take(200).ifBlank { "（还没开口）" } + " 换个更小的问法，或者自己动手做这一步。"
        else out.ifBlank { "子任务没有给出结论" }
        emit(Ev.Sub(label, "done", text.take(400)))
        return (text to log.toString())
    }

    /** 子任务的动静：一边转成界面上的实时行，一边攒成一份过程记录跟着结论落库。 */
    private fun forwardSub(label: String, ev: Ev, log: StringBuilder) {
        when (ev) {
            is Ev.ToolStart -> {
                log.appendLine(ev.name + " " + ev.brief)
                emit(Ev.Sub(label, "tool", ev.name + " " + ev.brief))
            }
            is Ev.ToolEnd -> if (!ev.ok) {
                log.appendLine(ev.name + " 失败")
                emit(Ev.Sub(label, "err", ev.name + " 失败"))
            }
            is Ev.Err -> {
                log.appendLine("出错：" + ev.s)
                emit(Ev.Sub(label, "err", ev.s))
            }
            else -> Unit
        }
    }

    /**
     * 用户对上一次审批选了什么，挂在**紧接着落的那条 tool 消息**上。
     *
     * 审批发生在 `tool.run()` 里面的 guard，而那条 tool 消息就在 run 返回之后立刻追加，
     * 所以"下一个"就是它该挂的位置；取一次就清，免得串到下一条没弹过窗的工具上。
     */
    @Volatile private var pendingNote: String? = null
    fun markApproval(text: String) { pendingNote = text }
    private fun takeNote(): String = pendingNote?.also { pendingNote = null } ?: ""

    /**
     * 把历史截到第 index 条为止，供"删到这里 / 编辑重发 / 重新生成"三处用。
     *
     * 只允许截在 **user 消息**上：user 消息一定是回合边界。截在
     * `assistant(tool_calls)` 与它的 tool 回复之间，下一次请求就是"有调用没回复" → 网关 400，
     * 症状看起来像"编辑一次把会话弄坏了"。（同一条不变量见 maybeCompact 的切点规则。）
     *
     * keepAt=true 是"删到这里"：这一句留着，它之后的回答与工具调用全丢。
     * keepAt=false 是"改这一句重跑"：这一句本身也要拿掉，因为调用方紧接着会把
     * 新的一句话发进来。两边共用默认值会造出两种完全不同的错，所以调用方必须表态。
     */
    fun cutTo(index: Int, keepAt: Boolean = true): Boolean {
        if (index < 0 || index >= history.size) return false
        if (history[index].role != "user") return false
        // 曾经统一写成 `> index`：点"删到这里"连这一句一起删光，整个会话看起来被清空了
        val keep = if (keepAt) index + 1 else index
        while (history.size > keep) history.removeAt(history.size - 1)
        // 压缩水位要跟着退：前面可能被折进摘要的条数不能比剩下的历史还多
        compactedCount = minOf(compactedCount, history.size)
        persist()
        return true
    }

    /** 最后一条用户消息的位置（"重新生成"从它重跑）。 */
    fun lastUserIndex(): Int = history.indexOfLast { it.role == "user" }

    /**
     * 网关上可选的模型（顶栏切换器）。
     *
     * 走引擎而不是让前端直接连网关：密钥只在服务端读一次，
     * 前端拿不到也不该拿到。
     */
    fun models(): List<String> = runCatching { client.models() }.getOrDefault(emptyList())

    /**
     * 上下文构成，给界面上"用了多少"的明细。
     *
     * 为什么要拆开：只知道"用了 60%"没法定问题 —— 一条长会话涨到 60% 通常是
     * 某个工具把 4 万字输出灌进了历史（该看历史那一项），而新开就 40% 通常是
     * 工具说明太长（该关几个开关）。两件事的解法完全相反，所以必须分行给。
     *
     * 单位是**字符**，不是 token：token 只有网关知道（它回 usage 时才准），
     * 这里标成"字"而不是乘个系数假装精确。
     */
    fun contextBreakdown(): List<Pair<String, Int>> {
        val req = requestMessages()
        val sys = req.filter { it.role == "system" }.sumOf { it.content?.length ?: 0 }
        val hist = req.filter { it.role != "system" }.sumOf {
            (it.content?.length ?: 0) + it.calls.sumOf { c -> c.args.length } +
                // 图片按编码后的真实成本算：只按那 40 个字符的路径算，等于"贴十张截图还说只用了 400 字"
                it.images.sumOf { Images.wireChars(it) }
        }
        val tools = runCatching { schemas().toString().length }.getOrDefault(0)
        /*
         * 项目说明单列一行：它是用户自己写进仓库的东西，"上下文快满了"这件事
         * 到底是纪律太长还是用户的规矩太长，分开看才知道该去删哪个。
         * 它本来就含在系统提示里，所以从系统提示里减掉，两行加起来还是总数。
         */
        val mem = runCatching { Memory.read(session.workspace, gitRoot(session.workspace)).length }
            .getOrDefault(0).coerceAtMost(sys)
        val out = mutableListOf<Pair<String, Int>>()
        if (sys - mem > 0) out += "系统提示" to (sys - mem)
        if (mem > 0) out += "项目说明" to mem
        if (tools > 0) out += "工具说明" to tools
        if (hist > 0) out += "对话历史" to hist
        summary?.let { if (it.isNotBlank()) out += "前情摘要" to it.length }
        return out
    }

    /** 当前这一份历史占多少字（界面上的环形指示器的分子）。 */
    fun contextChars(): Int = contextBreakdown().sumOf { it.second }

    fun textAt(index: Int): String? = history.getOrNull(index)?.content

    /** 这次工具动的对象是什么 —— 界面上的回滚按钮按它找快照。 */
    private fun subjectOf(args: JsonObject): String =
        listOf("path", "file", "url", "command").firstNotNullOfOrNull { k ->
            args[k]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        } ?: ""

    /**
     * 用户中断。`@Volatile` 是因为按停止的是 HTTP 线程，跑回合的是 haoai-run 线程，
     * 而 `/api/stop` **绝不能**去抢那把跑任务的锁 —— 抢了就变成"点停止要等本轮跑完"，
     * 停止按钮本身就成了它要中止的那件事的受害者。
     *
     * 只在**回合边界与工具边界**检查，不在模型流式中途打断：一次 `chat()` 是有界的，
     * 打断它要往 Provider 里塞取消令牌，换来的只是少等几秒，代价是半截 tool_call
     * 落进历史（手机端就是这么把会话写坏的）。
     */
    /*
     * "跑到一半的现场"。正常收尾会清掉它 —— 所以重启之后**还留着**的那条，
     * 就是被杀进程/断电打断的那条。手机端 SessionStore 的 runState 是同一个语义。
     * 每轮多写一次会话文件（几十 KB）换"崩了也留得下痕迹"，这个代价值得。
     */
    @Volatile var runGoal: String? = null
        private set
    @Volatile var runTurn: Int = 0
        private set
    @Volatile var runStarted: Long = 0L
        private set

    fun runStateJson(): String? = runGoal?.let { g ->
        buildJsonObject {
            put("goal", g); put("turn", runTurn); put("started", runStarted)
        }.toString()
    }

    /** 续跑要发的那句话：把原目标带回去，并说清"接着做"，不是重头再来一遍。 */
    fun resumePrompt(): String? {
        val g = runGoal ?: return null
        return "接着上次没跑完的那件事继续（上次到第 $runTurn 轮被打断了）。原来的目标：$g。已经做完的部分别重做一遍。"
    }

    fun clearRunState() {
        runGoal = null; runTurn = 0; runStarted = 0L; persistNow()
    }

    @Volatile
    var stopRequested = false
        private set

    fun requestStop() {
        stopRequested = true
        /*
         * 中止整轮时正在跑的子任务要一起停。
         *
         * 不这么做的话：`task` 这把工具是**阻塞**的，父循环要等 spawn 返回才看得见
         * stopRequested —— 按下停止，那条调研还在自己转圈，界面几十秒不动。
         */
        liveSubs.values.forEach { it.requestStop() }
    }

    /**
     * 开跑前清旗子。**必须在服务端口把 running 置真的同一把锁里调用**，
     * 不能放在 `submit()` 里：那样"提交后立刻按停止"会被 submit 晚一步的清旗抹掉，
     * 表现是刚发出去的任务停不下来（多会话并行时更容易撞上，因为切过去切回来都在抢那点时间）。
     */
    fun beginRun() {
        stopRequested = false
    }

    /**
     * 超预算就把早期历史折成摘要。返回折掉的条数（0 = 没动）。
     *
     * 切点规则只有一条，但它是**不能错**的那条：切点必须落在非 tool 消息上 ——
     * 一个 `assistant(tool_calls)` 和它后面那些 `tool` 回复是一个整体，
     * 把它们切开，下一次请求就是"有调用没回复"，网关直接 400，
     * 而症状看起来像"压缩把会话弄坏了"。（同一个不变量见 [submit] 里的停止分支。）
     *
     * 优先让模型自己压，失败/超时/空返回则退回 [Compactor.digest] ——
     * 省 token 的机制自己不能成为新的故障点。
     */
    private fun maybeCompact(): Int = compactOnce(force = false)

    /** 界面上"现在压缩一次"走的这条：绕开触发线与迟滞，切点规则与兜底完全同一个实现。 */
    fun compactNow(): Int {
        val cut = compactOnce(force = true)
        // 手动压缩不在回合里，没人替它落盘：不写一次，刷新就回到压缩前的样子
        if (cut > 0) persistNow()
        return cut
    }

    /**
     * 删掉某一句。但 agent 历史里的"一句"从来不是孤零零一条：用户那句话带出了整轮
     * 回答与工具调用，assistant 那条带 calls 的后面跟着若干条 tool 回复。
     * 只抽走中间一条会留下孤儿 tool 回复，下一次请求直接 400 —— 和压缩那条"切点只能
     * 落在非 tool 消息上"是同一个道理。所以这里删的是**这一句和它带出来的一切**。
     *
     * 返回 (删掉的条数, 错误说明)；错误说明非空表示一条都没动。
     */
    fun deleteAt(i: Int): Pair<Int, String> {
        if (i < 0 || i >= history.size) return 0 to "没有这一条"
        val m = history[i]
        if (m.role == "tool") return 0 to "工具回复不能单独删：删它上面那条调用"
        var end = i + 1
        if (m.role == "user") {
            while (end < history.size && history[end].role != "user") end++
        } else if (m.calls.isNotEmpty()) {
            val ids = m.calls.map { it.id }.toSet()
            while (end < history.size && history[end].role == "tool" && history[end].callId in ids) end++
        }
        val n = end - i
        history.subList(i, end).clear()
        persistNow()
        return n to ""
    }

    private fun compactOnce(force: Boolean): Int {
        val bodyChars = history.sumOf { it.content?.length ?: 0 }
        if (!force && bodyChars <= settings.compactTriggerChars) return 0
        /**
         * 迟滞：压过一次之后，除非**又长出半个触发线**那么多，否则不再压。
         *
         * 为什么需要：保留窗口 `compactKeepTail` 里如果本来就是几条大结果
         * （实测 8 轮 read，每条 7k 字符 ⇒ 尾巴自己就 2 万字符），
         * 压完仍然超线。没有这条的话，每一轮都会白花一次模型调用去压一个
         * 压不动的东西 —— 表现就是"长会话突然开始每轮多一次请求、还不变快"。
         */
        if (!force && lastCompactedChars > 0 &&
            bodyChars < lastCompactedChars + settings.compactTriggerChars / 2) return 0
        val want = settings.compactKeepTail.coerceAtLeast(4)
        /*
         * 手动压缩时保留窗口不许比历史还长：默认 keep=14，一条 12 句的会话按下去会
         * "没得压"，而那恰恰是人最想压的时刻（刚跑完一段调研）。折一半，至少留 4 条。
         */
        val keep = if (force) minOf(want, (history.size / 2).coerceAtLeast(4)) else want
        var cut = (history.size - keep).coerceAtLeast(0)
        while (cut > 0 && history[cut].role == "tool") cut--
        if (cut < 4) return 0                       // 头部太短，压了反而更啰嗦
        val head = history.subList(0, cut).toList()
        val charsBefore = head.sumOf { it.content?.length ?: 0 }

        val fromModel = runCatching {
            client.chat(
                listOf(Msg("user", Compactor.promptFor(head))), emptyList()
            ) { }.text.trim()
        }.getOrNull().orEmpty()
        val piece = if (fromModel.length >= 40) fromModel else Compactor.digest(head)

        summary = mergeSummary(summary, piece)
        compactedCount += cut
        history.subList(0, cut).clear()
        lastCompactedChars = history.sumOf { it.content?.length ?: 0 }
        emit(
            Ev.Notice(
                "上下文压缩：把 $cut 条早期消息折进摘要（约 ${charsBefore} → " +
                    "${piece.length} 字符，${if (fromModel.length >= 40) "模型生成" else "机器兜底"}）。" +
                    "累计已折 $compactedCount 条。"
            )
        )
        return cut
    }

    /** 摘要是**追加式**的：新压的内容接在旧的后面，超限时从最上面裁行，不重写已有部分。 */
    internal fun mergeSummary(old: String?, piece: String): String {
        val cap = 8_000
        val joined = buildString {
            if (!old.isNullOrBlank()) { append(old.trimEnd()); append('\n') }
            append(piece.trim())
        }
        if (joined.length <= cap) return joined
        // 超限就保**最新**的那部分：摘要本身也是越新越有用，
        // 从后往前收集行，装不下就停，并在开头标一句"更早的已省略"。
        val lines = joined.lines()
        val kept = kotlin.collections.ArrayDeque<String>()
        var used = 0
        for (i in lines.indices.reversed()) {
            if (used + lines[i].length > cap) break
            kept.addFirst(lines[i]); used += lines[i].length + 1
        }
        return ("…（更早的部分已省略）\n" + kept.joinToString("\n")).trim()
    }

    fun todoLines(): List<String> = session.todos.mapIndexed { i, t ->
        val mark = when (t.status) {
            "done" -> "[x]"; "doing" -> "[>]"; "cancelled" -> "[-]"; else -> "[ ]"
        }
        "$mark ${i + 1}. ${t.text}"
    }

    /** 一轮用户输入 → 若干次模型往返 → 最终文本。 */
    fun submit(userText: String, images: List<String> = emptyList(),
                 goal: String? = null): String {
        var titled = false
        if (session.title.get() == "新会话") {
            session.title.set(userText.trim().replace('\n', ' ').take(24).ifBlank { "新会话" })
            titled = true
        }
        history += Msg("user", userText, images = images.filter { Images.usable(it) != null })
        /**
         * 一开口就先落一次盘。
         *
         * 之前只在回合结束时 `persist()`，于是"正在跑的任务"在会话列表里根本不存在
         * （实测：跑起来了，`/api/sessions` 还是只有旧的那几条）。
         * 顺带把手机端那条"落库止血"的规矩接上：进程被杀 / 断电时，
         * 至少用户问了什么还在。
         */
        runGoal = (goal ?: userText).trim().replace('\n', ' ').take(200)
        runTurn = 0
        runStarted = System.currentTimeMillis()
        persist()
        // 标题一落地就要说出去：侧栏与顶栏靠它区分并行的几条会话，
        // 等回合结束才刷新的话，一条跑十分钟的任务十分钟都还叫"新会话"。
        if (titled) emit(Ev.Title(session.title.get()))
        val ctx = ToolCtx(session.workspace, settings, session.mode, gate, session.todos)
        ctx.spawn = { label, prompt -> spawn(label, prompt) }
        var lastText = ""
        var turnNo = 0
        var retry = 0
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
            maybeCompact()
            val turnStart = System.currentTimeMillis()
            val ptBefore = totalPrompt
            val ctBefore = totalCompletion
            val turn = try {
                client.chat(
                    requestMessages(), schemas(),
                    onText = { piece -> emit(Ev.TextDelta(piece)) },
                    onReasoning = { piece -> emit(Ev.ReasoningDelta(piece)) }
                )
            } catch (e: ProviderError) {
                if (e.transient && retry < backoffs.size) {
                    retry++
                    emit(Ev.Notice("网关抖动（${e.message?.take(140)}），${backoffs[retry - 1] / 1000}s 后第 $retry 次重试"))
                    Thread.sleep(backoffs[retry - 1])
                    turnNo--
                    continue
                }
                lastError = "模型调用失败：${e.message?.take(400)}"
                UsageLedger.add(settings.model, session.id, 0, 0, 0, false)   // 失败也要入账：成功率不是装饰
                emit(Ev.Err(lastError!!))
                break
            } catch (e: Exception) {
                lastError = "模型调用异常：${e.message ?: e.javaClass.simpleName}"
                UsageLedger.add(settings.model, session.id, 0, 0, 0, false)
                emit(Ev.Err(lastError!!))
                break
            }
            retry = 0
            totalPrompt += turn.usage.promptTokens
            totalCompletion += turn.usage.completionTokens
            emit(Ev.Usage(totalPrompt.toInt(), totalCompletion.toInt(), turnNo))

            /*
             * 被 max_tokens 截断要**说出来**。
             * 不说，界面看起来就是"模型答完了"，而它其实说到一半被掐了 ——
             * 更糟的是这一轮没有 tool_calls，循环会当成最终回答直接收尾，
             * 用户拿到半句话还以为是结论。（真模型第一次跑就遇到：一轮生成 5792 token。）
             */
            if (turn.finishReason.equals("length", ignoreCase = true)) {
                emit(
                    Ev.Notice(
                        "这一轮说到一半被 max_tokens=${settings.maxTokens} 截断了，下面这段可能不完整。" +
                            "要放宽就在设置里改 maxTokens（0 = 交给网关），或把这一步拆小一点。"
                    )
                )
            }

            // 每回合自己的 token 与耗时：会话累计值看不出"哪一步最贵/最慢"，
            // 而这正是 PC 上排查一条长任务时最想要的两个数。
            val pt = (totalPrompt - ptBefore).toInt()
            val ct = (totalCompletion - ctBefore).toInt()
            val ms = System.currentTimeMillis() - turnStart
            if (turn.calls.isEmpty()) {
                lastText = turn.text
                history += Msg("assistant", turn.text, reasoning = turn.reasoning.ifBlank { null },
                    pt = pt, ct = ct, ms = ms)
                emit(Ev.TurnStats(pt, ct, ms, turnNo))
                UsageLedger.add(settings.model, session.id, pt, ct, ms, true)
                runTurn = turnNo
                break
            }

            if (turn.text.isNotBlank()) lastText = turn.text
            history += Msg("assistant", turn.text, calls = turn.calls,
                reasoning = turn.reasoning.ifBlank { null }, pt = pt, ct = ct, ms = ms)
            emit(Ev.TurnStats(pt, ct, ms, turnNo))
            UsageLedger.add(settings.model, session.id, pt, ct, ms, true)
            runTurn = turnNo
            persist()      // 每轮留一次现场：被打断时"跑到第几轮"才是量出来的

            val shotPaths = mutableListOf<String>()
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
                if (call.name in settings.toolsOff) {
                    // 可见性挡不住"模型硬调"：它可能凭记忆叫一把已经被关掉的工具。
                    // 执行侧必须再挡一次，并且说清楚去哪儿开 —— 只回一句"未知工具"会把人引偏。
                    val why = "工具 ${call.name} 在这条会话里被关掉了：右栏「工具」页签可以重新打开。"
                    history += Msg("tool", why, callId = call.id, name = call.name)
                    emit(Ev.ToolEnd(call.id, call.name, false, why, "generic"))
                    continue
                }
                val args = parseArgs(call.args)
                emit(Ev.ToolStart(call.id, call.name, brief(args), subjectOf(args)))
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
                val note = takeNote()
                history += Msg("tool", stored, callId = call.id, name = call.name, diff = res.diff, note = note,
                    sub = res.sub)
                shotPaths += res.images
                emit(Ev.ToolEnd(call.id, call.name, !res.error, stored, res.card, res.diff, note, res.sub))
            }
            /*
             * 工具产出的图片（screen capture / 浏览器截图）单独补一条 user 消息递给模型。
             * 为什么不塞进 tool 消息：OpenAI 兼容网关大多不接受 tool 角色的数组 content，
             * 而 user 角色带 image_url 是各家都认的写法。工具结果文本里已经有路径与说明，
             * 这条只是把像素本身给出去 —— 之前"屏幕理解"其实是模型在猜路径后面是什么。
             */
            if (shotPaths.isNotEmpty()) {
                val ok = shotPaths.filter { Images.usable(it) != null }
                history += if (ok.isNotEmpty())
                    Msg("user", "（上面这些工具产出了 ${ok.size} 张图片，现在把图本身给你看。）", images = ok)
                else
                    Msg("user", "（工具产出的图片没能递出去：类型不认、读不到，或超过 " +
                        "${Images.MAX_BYTES / 1_000_000} MB 上限。要看就先按路径自己处理。）")
            }
            session.mode = ctx.mode
        }

        if (turnNo >= settings.maxTurns) emit(Ev.Notice("已达单轮工具调用上限 ${settings.maxTurns}，先收尾。"))
        // 走到这里就是"这一条自己收的尾"（含用户按停止）：现场清掉，
        // 不然重启之后界面上还会举一条"上次跑到一半"的横幅。
        runGoal = null; runTurn = 0; runStarted = 0L
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

    /** 界面上「工具」页签要的清单：连被关掉的也列出来，否则"关掉"这件事看不见。 */
    data class ToolInfo(val name: String, val kind: String, val desc: String,
                        val off: Boolean, val gated: Boolean)

    fun toolInfos(): List<ToolInfo> = tools.map {
        ToolInfo(it.name, it.kind, it.desc, it.name in settings.toolsOff, !it.visibleWhen(settings))
    }

    /** 工具可见集：实验特性开关（关着=不存在）→ 这条会话的开关 → 档位收窄。 */
    private fun schemas(): List<ToolSchema> = tools.filter { t ->
        t.visibleWhen(settings) && t.name !in settings.toolsOff &&
            if (session.mode == "plan") readOnlyTool(t) else true
    }.map { ToolSchema(it.name, it.desc, it.params) }

    /** 发给模型的窗口：system + 前情摘要 + 按字符预算从前往后裁的历史，tool 结果先过 REQ_CAP。 */
    private fun requestMessages(): List<Msg> {
        val sys = Msg(
            "system", Prompt.system(
                PromptCtx(
                    workspace = session.workspace,
                    model = settings.model,
                    mode = session.mode,
                    gitRoot = gitRoot(session.workspace),
                    // 每回合现读，不缓存：用户改完 AGENTS.md，下一句话就该生效
                    extra = Memory.read(session.workspace, gitRoot(session.workspace))
                )
            )
        )
        val head = mutableListOf(sys)
        summary?.takeIf { it.isNotBlank() }?.let {
            head += Msg("user", "【前情摘要｜更早 $compactedCount 条对话已折叠，以下是压缩后的要点】\n$it")
        }
        val body = history.map { m ->
            val c = m.content
            if (m.role == "tool" && c != null && c.length > settings.reqCap) {
                Msg(m.role, TextCap.middle(c, settings.reqCap), callId = m.callId, name = m.name)
            } else m
        }
        val budget = 120_000
        var used = head.sumOf { it.content?.length ?: 0 } + body.sumOf { it.content?.length ?: 0 }
        var dropFrom = 0
        while (used > budget && dropFrom < body.size - 6) {
            used -= (body[dropFrom].content?.length ?: 0)
            dropFrom++
        }
        // 兜底裁剪也必须落在"块首"：从中间切断 assistant(tool_calls) 与它的 tool 回复，
        // 发出去就是一次 400，而症状看起来像"窗口一大就报错"。
        while (dropFrom > 0 && dropFrom < body.size && body[dropFrom].role == "tool") dropFrom++
        return head + body.drop(dropFrom)
    }

    private fun brief(args: JsonObject): String {
        val v = args["command"] ?: args["path"] ?: args["url"] ?: args["pattern"] ?: args["question"]
        return v?.jsonPrimitive?.contentOrNull?.take(180) ?: args.toString().take(140)
    }

    private fun parseArgs(raw: String): JsonObject = runCatching {
        val t = raw.trim().ifBlank { "{}" }
        Json.parseToJsonElement(t).jsonObject
    }.getOrElse { emptyObj }

    /**
     * 立刻落盘（空会话也写）。
     *
     * 单会话时代不需要这条：会话第一次 `submit()` 才写文件，反正界面上只有一条。
     * 多会话并行之后不一样了 —— 新建的那条如果只活在内存里，侧栏（读的是磁盘索引）
     * 就看不见它，用户切去别的会话就再也点不回来，而它还在后台跑并继续花钱。
     */
    fun persistNow() = persist()

    private fun persist() {
        // 子任务不落盘：它是一次性的调研，出现在左侧会话列表里只会碍事
        // （而且它的历史本来就被父会话压缩成了一句结论，重启后也接不回去）。
        if (depth > 0) return
        runCatching {
            Env.sessionsDir.mkdirs()
            session.file.writeText(
                buildJsonObject {
                    put("id", session.id)
                    put("title", session.title.get())
                    put("workspace", session.workspace.absolutePath)
                    put("mode", session.mode)
                    put("model", settings.model)
                    // 会话级开关要落盘：不然刷新/重开之后界面上还写着"已关掉"，
                    // 而引擎其实拿着全开的工具表在跑
                    put("toolsOff", kotlinx.serialization.json.JsonArray(
                        settings.toolsOff.map { kotlinx.serialization.json.JsonPrimitive(it) }))
                    runGoal?.let { g ->
                        put("runState", buildJsonObject {
                            put("goal", g)
                            put("turn", runTurn)
                            put("at", (history.size - 1).coerceAtLeast(0))
                            put("started", runStarted)
                        })
                    }
                    put("updated", System.currentTimeMillis())
                    put("promptTokens", totalPrompt)
                    put("completionTokens", totalCompletion)
                    // 摘要与水位必须跟着会话走：只存 messages 的话，
                    // 重开会话时那段被压掉的原文已经没了，而模型又看不到摘要 —— 事实就丢了。
                    summary?.let { put("summary", it) }
                    if (compactedCount > 0) put("compactedThrough", compactedCount)
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
                                    m.reasoning?.let { put("reasoning", it) }
                                    if (m.diff.isNotBlank()) put("diff", m.diff)
                                    // 图片只存路径（base64 存进来会把会话文件撑爆）
                                    if (m.images.isNotEmpty())
                                        put("images", buildJsonArray { m.images.forEach { add(JsonPrimitive(it)) } })
                                    if (m.note.isNotBlank()) put("note", m.note)
                                    if (m.sub.isNotBlank()) put("sub", m.sub)
                                    if (m.pt > 0) put("pt", m.pt)
                                    if (m.ct > 0) put("ct", m.ct)
                                    if (m.ms > 0) put("ms", m.ms)
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

    /** 回读摘要与水位。老会话文件里没这两个字段，读不到就按"没压过"处理。 */
    private fun restoreSummaryMeta(f: File) {
        if (!f.isFile) return
        runCatching {
            val o = Json.parseToJsonElement(f.readText()).jsonObject
            summary = o["summary"]?.jsonPrimitive?.contentOrNull
            compactedCount = o["compactedThrough"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
            o["runState"]?.jsonObject?.let { rs ->
                runGoal = rs["goal"]?.jsonPrimitive?.contentOrNull
                runTurn = rs["turn"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
                runStarted = rs["started"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
            }
            // 带着摘要恢复的会话，把迟滞基线设成当前正文规模：
            // 否则"重开一个本来就很长的会话"会立刻再压一次，白花一次模型调用。
            if (!summary.isNullOrBlank()) lastCompactedChars = history.sumOf { it.content?.length ?: 0 }
        }
    }

    private fun restore(f: File): List<Msg> {
        if (!f.isFile) return emptyList()
        return runCatching {
            val o = Json.parseToJsonElement(f.readText()).jsonObject
            o["messages"]?.jsonArray?.mapNotNull { el ->
                val m = el.jsonObject
                val role = m["role"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                Msg(
                    role = role,
                    content = m["content"]?.jsonPrimitive?.contentOrNull,
                    /*
                     * name 必须跟着回来：工具卡上那行"edit 已编辑 hello.txt"、以及"↩ 退回上一版"
                     * 按名字配对路径，都靠它。落盘时写了 name 而读取时漏了，表现是
                     * "刷新一下所有工具卡都变成匿名的 tool"，而且退回按钮整排消失。
                     */
                    name = m["name"]?.jsonPrimitive?.contentOrNull ?: "",
                    callId = m["tool_call_id"]?.jsonPrimitive?.contentOrNull,
                    images = m["images"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }
                        ?: emptyList(),
                    reasoning = m["reasoning"]?.jsonPrimitive?.contentOrNull,
                    diff = m["diff"]?.jsonPrimitive?.contentOrNull ?: "",
                    note = m["note"]?.jsonPrimitive?.contentOrNull ?: "",
                    sub = m["sub"]?.jsonPrimitive?.contentOrNull ?: "",
                    pt = m["pt"]?.jsonPrimitive?.intOrNull ?: 0,
                    ct = m["ct"]?.jsonPrimitive?.intOrNull ?: 0,
                    ms = m["ms"]?.jsonPrimitive?.longOrNull ?: 0L,
                    calls = m["tool_calls"]?.jsonArray?.mapNotNull { c ->
                        val fn = c.jsonObject["function"]?.jsonObject
                        val id = c.jsonObject["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                        val n = fn?.get("name")?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                        ToolCall(id, n, fn["arguments"]?.jsonPrimitive?.contentOrNull ?: "{}")
                    } ?: emptyList()
                )
            } ?: emptyList()
        }.getOrElse { emptyList() }
    }

    companion object {
        private val emptyObj: JsonObject = buildJsonObject { }

        /** 子任务能派几层：1 = 主会话可以派子任务，子任务不能再派。 */
        const val MAX_DEPTH = 1

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
        val e = if (client == null) Engine(s, settings, allTools(), gate, emit)
        else Engine(s, settings, allTools(), gate, emit, client)
        map[s.id] = e
        return e
    }

    fun get(id: String): Engine? = map[id]
    fun all(): List<Engine> = map.values.toList()
}
