package com.haoai.pc

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File

/**
 * 多 Agent：**专家实例注册表 + 跨专家收件箱**（对标 Octop 的 AgentManager 与 inbox）。
 *
 * ## 为什么不是"再造一套子任务"
 *
 * HaoAI 早就有 `task` 工具：父会话派一条子任务，子任务跑完把结论带回来。那是**父子**关系 ——
 * 子任务没有身份、没有自己的会话、没有状态，父会话结束它就没了，也不能被第二个人调用。
 *
 * Octop 那一层是**同事**关系：每个专家是一个有状态的实例（运行中 / 已停 / 失败），
 * 有自己的常驻会话与目录，别的专家可以给它发消息，消息进它的收件箱，
 * 同一个专家串行处理（否则两条会话同时改同一个目录会互相踩），不同专家并行。
 * 这一层补的就是那个关系。
 *
 * ## 三条刻意的设计
 *
 * 1. **执行口是注入的（[runner] / [followup]），不在这里起引擎。**
 *    和 `Gate` 同一套路：调度与状态归这一层，"真的去跑一句话"由壳（Web / CLI）实现。
 *    于是这整份逻辑能脱网、脱模型测 —— 判据打在状态机上，不是打在"模型答得好不好"上。
 * 2. **同一个 callee 串行，不同 callee 并行。**
 *    Octop 的团队文档就是这么写的（实测也最容易反直觉）：并行派给两个专家是快，
 *    并行派给同一个专家是两个会话在同一个工作区里抢着写文件。
 * 3. **后台投递不许静默丢。** 队列满了、专家停了、runner 没接 —— 都要回一句人话，
 *    而不是"消息发出去了然后什么都没有"。无人值守的链路里，沉默就是最坏的失败。
 *
 * 状态落盘在 `HAOAI_HOME/agents.json`：只存"哪个专家在跑、它的会话是哪条"，
 * 人设与模型仍然只在角色卡上（第二真源的老问题，团队编制那批已经踩过一次）。
 */

/** 一个跑起来的专家实例。 */
data class AgentState(
    val preset: String,
    var state: String = IDLE,
    /** 它自己那条常驻会话（第一次被调用时建，之后复用 —— 每次新建就等于没有"记忆"）。 */
    var sid: String = "",
    var lastAt: Long = 0L,
    var lastError: String = "",
    var turns: Int = 0
) {
    companion object {
        const val IDLE = "idle"
        const val RUNNING = "running"
        const val STOPPED = "stopped"
        const val FAILED = "failed"

        /** 给人看的那三个字：界面上不出现英文状态码。 */
        fun label(s: String): String = when (s) {
            RUNNING -> "运行中"
            STOPPED -> "已停止"
            FAILED -> "出错"
            else -> "待命"
        }
    }
}

/** 一条跨专家消息（Octop 的 InboxMessage 同构；单机单用户，所以没有 user_id 那一列）。 */
data class InboxMsg(
    val id: String,
    val to: String,
    /** 谁发的：另一张卡的 id，或 `user`（用户从界面上直接问某个专家）。 */
    val from: String,
    val text: String,
    val created: Long = System.currentTimeMillis(),
    var state: String = QUEUED,
    var reply: String = "",
    var error: String = "",
    var doneAt: Long = 0L
) {
    companion object {
        const val QUEUED = "queued"
        const val RUNNING = "running"
        const val DONE = "done"
        const val FAILED = "failed"
    }
}

object Agents {

    /** 收件箱上限：后台投递是"顺手托一件事"，不是消息队列中间件，堆满了就明说。 */
    const val MAX_INBOX = 200

    /**
     * 真的去跑一句话：`(专家卡 id, 文本) -> (回答, 错误)`。由壳注入。
     * 没注入时 `ask` 一律明确报错 —— 宁可报"这台机器还没接上执行口"，
     * 也不要返回一句"看起来成功了"的空字符串。
     */
    @Volatile
    var runner: ((String, String) -> Pair<String, String?>)? = null

    /** 后台消息跑完之后，把回信送回**发信人**那条会话（Octop 的 compose_followup 那一步）。 */
    @Volatile
    var followup: ((String, String) -> Unit)? = null

    /** 状态变了要通知谁（壳里就是 publish("agents", …)）。 */
    @Volatile
    var notify: (() -> Unit)? = null

    private val lock = Any()
    private val states = LinkedHashMap<String, AgentState>()
    private val queue = ArrayList<InboxMsg>()
    /** 正在被占用的 callee：同一个专家一次只接一件。 */
    private val busy = HashSet<String>()
    private var worker: Thread? = null

    fun file(): File = File(Env.home, "agents.json")

    // ---- 注册表 ----

    /** 把它的常驻会话记到实例上（第一次被调用时新建，之后所有问题都问同一条 —— 这才叫"记得"）。 */
    fun attach(preset: String, sid: String) {
        synchronized(lock) {
            states.getOrPut(preset) { AgentState(preset) }.sid = sid
            persist()
        }
        notify?.invoke()
    }

    fun state(preset: String): AgentState? = synchronized(lock) { states[preset] }

    fun all(): List<AgentState> = synchronized(lock) { states.values.map { it.copy() } }

    /** 起一个实例：卡不存在就拒（不建"幽灵实例"，那种东西界面上关不掉）。 */
    fun start(preset: String, sid: String = ""): Pair<AgentState?, String?> {
        if (preset.isNotBlank() && Presets.find(preset) == null)
            return null to "没有这张专家卡：$preset"
        // 卡上那个"关"是目录级的：关着的卡既不该被启成实例，也不该被别的专家点名（见 ask）。
        // 不这么判的话，"停用实例"与"关掉卡"就是两套互相看不见的世界。
        Presets.find(preset)?.takeIf { !it.enabled }?.let {
            return null to "「${it.name}」已经关掉，去「专家」页打开再用"
        }
        val s = synchronized(lock) {
            val cur = states.getOrPut(preset) { AgentState(preset) }
            cur.state = AgentState.IDLE
            cur.lastError = ""
            if (sid.isNotBlank() && cur.sid.isBlank()) cur.sid = sid
            cur.lastAt = System.currentTimeMillis()
            cur.copy()
        }
        persist()
        notify?.invoke()
        return s to null
    }

    fun stop(preset: String): Boolean {        val hit = synchronized(lock) {
            states[preset]?.let { it.state = AgentState.STOPPED; it.lastAt = System.currentTimeMillis(); true }
                ?: false
        }
        if (hit) { persist(); notify?.invoke() }
        return hit
    }

    internal fun markBusy(preset: String): Boolean = synchronized(lock) {
        if (preset in busy) return false
        busy += preset
        states.getOrPut(preset) { AgentState(preset) }.let {
            it.state = AgentState.RUNNING; it.lastAt = System.currentTimeMillis()
        }
        persist(); notify?.invoke()
        true
    }

    internal fun markDone(preset: String, err: String?) = synchronized(lock) {
        busy -= preset
        states[preset]?.let {
            it.state = if (err == null) AgentState.IDLE else AgentState.FAILED
            it.lastError = err ?: ""
            it.lastAt = System.currentTimeMillis()
            it.turns += 1
        }
        persist(); notify?.invoke()
    }

    // ---- 收件箱 ----

    fun inbox(): List<InboxMsg> = synchronized(lock) { queue.map { it.copy() } }

    internal fun enqueue(m: InboxMsg): Pair<InboxMsg?, String?> = synchronized(lock) {
        if (queue.size >= MAX_INBOX) return null to "收件箱满了（上限 $MAX_INBOX 条），先处理几条再投"
        val stopped = states[m.to]?.state == AgentState.STOPPED
        if (stopped) return null to "「${Presets.nameOf(m.to)}」已经被停了，要它干活先去「专家」页把它启起来"
        queue += m
        wake()
        m to null
    }

    /** 取一条" callee 现在不忙"的消息（保持先来先到，只跳过被占住的那个 callee）。 */
    internal fun take(): InboxMsg? = synchronized(lock) {
        val i = queue.indexOfFirst { it.state == InboxMsg.QUEUED && it.to !in busy }
        if (i < 0) return null
        queue[i].state = InboxMsg.RUNNING
        queue[i]
    }

    internal fun complete(id: String, reply: String, err: String?) = synchronized(lock) {
        queue.firstOrNull { it.id == id }?.let {
            it.state = if (err == null) InboxMsg.DONE else InboxMsg.FAILED
            it.reply = reply; it.error = err ?: ""
            it.doneAt = System.currentTimeMillis()
        }
        // 收件箱只留最近这些：跑完的历史在运行账本与那条会话里都看得见，不在这份内存里堆
        while (queue.size > MAX_INBOX) queue.removeAt(0)
        notify?.invoke()
    }

    /**
     * 投递一条消息。`mode=sync` 就地等结果；`mode=background` 进队、立刻回一条编号。
     *
     * 返回 `(给人看的结果, 错误)`：同步时是对方那句回答，后台时是"已投进收件箱（#id）"。
     */
    fun ask(to: String, from: String, text: String, mode: String = "sync"): Pair<String, String?> {
        val p = Presets.find(to) ?: return "" to "没有这张专家卡：$to（先看 agent_list 里都有谁）"
        if (!p.enabled) return "" to "「${p.name}」已经关掉，问不动：让它在「专家」页打开"
        if (text.isBlank()) return "" to "要问的那句话是空的"
        val stopped = state(p.id)?.state == AgentState.STOPPED
        if (stopped) return "" to "「${p.name}」已经被停了，先把它启起来再问"
        if (mode != "sync") {
            val m = InboxMsg(
                id = "ib" + System.nanoTime().toString(16).take(8),
                to = p.id, from = from, text = text
            )
            val (_, err) = enqueue(m)
            return if (err != null) "" to err else "已投进「${p.name}」的收件箱（#${m.id}），它忙完就会回话" to null
        }
        if (!markBusy(p.id))
            return "「${p.name}」正在忙另一件事。要等它就说 background，别在这里排第二件。" to null
        val run = runner ?: run {
            markDone(p.id, "这台机器还没接上执行口（runner 未注入）")
            return "" to "这台机器的专家执行口没接上，问不了"
        }
        val (out, err) = try { run(p.id, text) } catch (e: Exception) {
            "" to (e.message ?: e.javaClass.simpleName)
        }
        markDone(p.id, err)
        if (err != null) return "" to "问「${p.name}」失败：$err"
        return out to null
    }

    // ---- 后台工作线程 ----

    /** 起消费线程（幂等）。壳在启动时调一次即可。 */
    fun ensureWorker() {
        synchronized(lock) {
            worker?.takeIf { it.isAlive }?.let { return }
            worker = Thread {
                while (true) {
                    val m = take()
                    if (m == null) {
                        // Runnable 的 lambda 里不能 return（非局部返回被禁）：break 出循环就是线程退出
                        try { Thread.sleep(400) } catch (e: InterruptedException) { break }
                        continue
                    }
                    if (!markBusy(m.to)) {           // 被别人抢先占了：放回去等下一轮
                        synchronized(lock) { m.state = InboxMsg.QUEUED }
                        continue
                    }
                    val run = runner
                    val (out, err) = if (run == null) "" to "执行口没接上"
                    else try { run(m.to, m.text) } catch (e: Exception) {
                        "" to (e.message ?: e.javaClass.simpleName)
                    }
                    markDone(m.to, err)
                    complete(m.id, out, err)
                    if (err == null && m.from.isNotBlank() && m.from != "user") {
                        // 回信送回发信人那条会话：这一步才是"同事之间会话说下去了"，
                        // 只把答案塞进队列里没人读，等于没回
                        runCatching { followup?.invoke(m.from, "【${Presets.nameOf(m.to)} 的回信】$out") }
                    }
                }
            }.apply { isDaemon = true; name = "haoai-inbox" }
            worker!!.start()
        }
    }

    private fun wake() { ensureWorker() }

    // ---- 读数 ----

    fun json(): String {
        val as_ = all().joinToString(",", "[", "]") { s ->
            """{"preset":${q(s.preset)},"name":${q(Presets.nameOf(s.preset))},""" +
                """"state":${q(s.state)},"stateName":${q(AgentState.label(s.state))},""" +
                """"sid":${q(s.sid)},"lastAt":${s.lastAt},"turns":${s.turns},""" +
                """"lastError":${q(s.lastError)}}"""
        }
        val ib = inbox().takeLast(50).joinToString(",", "[", "]") { m ->
            """{"id":${q(m.id)},"to":${q(m.to)},"toName":${q(Presets.nameOf(m.to))},""" +
                """"from":${q(m.from)},"text":${q(m.text.take(160))},""" +
                """"state":${q(m.state)},"created":${m.created},"doneAt":${m.doneAt},""" +
                """"error":${q(m.error)},"reply":${q(m.reply.take(400))}}"""
        }
        return """{"ok":true,"agents":$as_,"inbox":$ib,"busy":[${
            synchronized(lock) { busy.joinToString(",") { q(it) } }}]}"""
    }

    /** 给模型看的名单（`agent_list` 工具就读这份）。 */
    fun roster(): String = synchronized(lock) {
        val cards = Presets.load()
        // 关着的卡不进名单：这份名单是给模型派工用的，列一个问不动的名字只会换来
        // 一次失败的 ask_agent —— 而模型在那之后通常就自己上手了。
        val on = cards.filter { it.enabled }
        when {
            on.isNotEmpty() -> on.joinToString("\n") { c ->
                val s = states[c.id]
                "· ${c.name}（preset=${c.id}）：${c.desc.ifBlank { "没写专长" }}" +
                    " ｜" + (s?.let { AgentState.label(it.state) } ?: "没启动过") +
                    (s?.takeIf { it.state == AgentState.RUNNING }?.let { "（正在忙）" } ?: "")
            }
            cards.isNotEmpty() -> "（专家卡现在全被关着：去「专家」页打开要用的那张，或现建一张）"
            else -> "（还没有任何专家卡：去「专家」页建一张，或从内置库里启用一张）"
        }
    }

    private fun persist() {
        val body = states.values.joinToString(",", "[", "]") { s ->
            """{"preset":${q(s.preset)},"state":${q(s.state)},"sid":${q(s.sid)},""" +
                """"lastAt":${s.lastAt},"turns":${s.turns},"lastError":${q(s.lastError)}}"""
        }
        runCatching { file().parentFile?.mkdirs(); file().writeText(body) }
    }

    /** 重启后把上次的实例状态读回来（running 一律降级成 idle：进程已经没了，状态不能说谎）。 */
    fun restore() {
        runCatching {
            val root = kotlinx.serialization.json.Json.parseToJsonElement(file().readText())
            val arr = if (root is JsonArray) root else JsonArray(emptyList())
            val read = arr.mapNotNull { el ->
                runCatching {
                    val o = el.jsonObject
                    val p = o["preset"]?.jsonPrimitive?.contentOrNull ?: return@runCatching null
                    AgentState(
                        preset = p,
                        state = (o["state"]?.jsonPrimitive?.contentOrNull ?: IDLE)
                            .let { if (it == RUNNING) IDLE else it },
                        sid = o["sid"]?.jsonPrimitive?.contentOrNull ?: "",
                        lastAt = o["lastAt"]?.jsonPrimitive?.longOrNull ?: 0L,
                        turns = o["turns"]?.jsonPrimitive?.intOrNull ?: 0,
                        lastError = o["lastError"]?.jsonPrimitive?.contentOrNull ?: ""
                    )
                }.getOrNull()
            }
            synchronized(lock) { read.forEach { states[it.preset] = it } }
        }
    }

    fun reset() = synchronized(lock) {
        states.clear(); queue.clear(); busy.clear()
    }

    private fun q(s: String): String = "\"" +
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "") + "\""

    private const val IDLE = "idle"
    private const val RUNNING = "running"
}

/** 卡名解析：卡被删了也要给个能看的名字，不能让界面上出现空白或一串 id。 */
internal fun Presets.nameOf(id: String): String = when {
    id.isBlank() -> "（用户）"
    else -> find(id)?.name ?: "（卡已删除 $id）"
}
