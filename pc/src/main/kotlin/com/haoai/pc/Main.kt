package com.haoai.pc

import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

/**
 * PC 端入口。
 *
 * 两种壳共用同一个引擎（照 codex 的 app-server 思路：先有协议与内核，壳只是订阅者）：
 *   haoai chat            终端 REPL（vibe coding 时最快）
 *   haoai serve           本地网页（127.0.0.1:8712），带工具卡 / diff / 待办 / 审批弹窗
 *   haoai task "..."      跑一条就走（脚本化、可被别的 agent 调）
 *   haoai doctor          自检：状态根、密钥、模型连通、工作区
 */
fun main(args: Array<String>) {
    val cmd = args.firstOrNull() ?: "help"
    val rest = args.drop(1)
    val settings = PcSettings.load()

    when (cmd) {
        "doctor" -> doctor(settings)
        "key" -> setKey(rest)
        "init" -> init(settings, rest)
        "set" -> set(settings, rest)
        "flags" -> flags(settings, rest)
        "allow" -> addRule(settings, rest, Decision.ALLOW)
        "deny" -> addRule(settings, rest, Decision.DENY)
        "ask" -> addRule(settings, rest, Decision.ASK)
        "rules" -> listRules(settings, rest)
        "browser" -> browserCmd(settings, rest)
        "screen" -> screenCmd(settings, rest)
        "task" -> task(settings, rest)
        "chat" -> chat(settings)
        "serve" -> serve(settings, rest)
        "sessions" -> sessions()
        "help", "--help", "-h" -> help()
        else -> {
            println("未知命令：$cmd")
            help()
        }
    }
}

private fun help() {
    println(
        """
        HaoAI PC 端 $PC_VERSION
          haoai doctor                 自检（状态根 / 密钥 / 模型连通 / 工作区）
          haoai key <sk-...>           存密钥到 HAOAI_HOME（不进仓库）
          haoai init [目录]            设定工作区
          haoai set model=… base=… mode=plan|ask|auto
          haoai flags [on|off <key>]   看/拨实验特性
          haoai allow <模式>           记住"这类不用再问"，如 haoai allow "git push*"
          haoai deny  <模式>           记住"这类直接拒绝"
          haoai ask   <模式>           记住"这类必须问"（auto 也绕不过）
          haoai rules [clear]          看/清本工作区的规则表
          haoai task "…" [--auto]      跑一条任务就退出
          haoai chat                   终端对话
          haoai serve [--port 8712]    打开本地网页版
        """.trimIndent()
    )
}

/** 版本只有一个真源：界面顶栏那行以前自己写死着 v0.2.0，仓库其实已经走到 0.23。 */
const val PC_VERSION = "0.30.0-pc"

private fun doctor(s: PcSettings) {
    line("HaoAI PC $PC_VERSION")
    println("  状态根      ${Env.home.absolutePath}  (${if (Env.home.isDirectory) "可写" else "不可写"})")
    println("  设置        ${if (Env.settingsFile.isFile) "已存在" else "用默认值"}")
    println("  模型        ${s.providerName} · ${s.model}")
    println("  网关        ${s.baseUrl}")
    println("  工作区      ${s.workspaceFile().absolutePath}  (${if (s.workspaceFile().isDirectory) "存在" else "不存在"})")
    println("  权限档位    ${s.permissionMode}")
    println("  实验特性    " + HaoFlag.entries.joinToString { "${it.key}=${HaoFlag.enabled(it, s.flags)}" })
    val key = System.getenv("HAOAI_API_KEY")?.takeIf { it.isNotBlank() }
        ?: runCatching { Env.apiKeyFile.takeIf { it.isFile }?.readText()?.trim() }.getOrNull()
    println("  密钥        " + (key?.take(6) + if (key != null && key.length > 6) "…" else "")
        ?: "未设置（haoai key sk-…）")
    if (key.isNullOrBlank()) {
        println("  ! 没有密钥，跳过连通性测试")
        return
    }
    print("  连通性      测试中…")
    val (ok, msg) = Provider(s.baseUrl, key, s.model).ping()
    println((if (ok) "OK  " else "失败 ") + msg.take(120))
}

private fun setKey(rest: List<String>) {
    val k = rest.firstOrNull { it.startsWith("sk-") } ?: System.getenv("HAOAI_API_KEY")
    if (k.isNullOrBlank()) {
        println("用法：haoai key sk-xxxx（或先 export HAOAI_API_KEY）")
        return
    }
    Env.apiKeyFile.writeText(k.trim())
    println("密钥已存到 ${Env.apiKeyFile.absolutePath}（这个目录不在仓库里，不会被提交）")
}

private fun init(s: PcSettings, rest: List<String>) {
    val dir = rest.firstOrNull()?.let { File(it) } ?: File(System.getProperty("user.dir"))
    val abs = runCatching { dir.canonicalFile }.getOrElse { dir.absoluteFile }
    if (!abs.isDirectory) {
        println("目录不存在：$abs")
        return
    }
    PcSettings.save(s.copy(workspace = abs.absolutePath))
    println("工作区已设为 $abs")
    val git = Engine.gitRoot(abs)
    println(if (git != null) "发现 Git 仓库根：$git" else "（不在 git 仓库里，改文件前会留快照到 .haoai-snap/）")
}

private fun set(s: PcSettings, rest: List<String>) {
    var n = s
    // 数字项统一走这个：写错格式要**保持原值并说一声**，不能静默变成 0
    fun int(k: String, v: String, cur: Int): Int =
        v.toIntOrNull() ?: run { println("「$k」要的是整数，给的是「$v」—— 这项没改"); cur }
    rest.forEach { kv ->
        val parts = kv.split("=", limit = 2)
        if (parts.size != 2) return@forEach
        val (k, v) = parts[0] to parts[1]
        n = when (k) {
            "model" -> n.copy(model = v)
            "base", "baseUrl" -> n.copy(baseUrl = v)
            "provider" -> n.copy(providerName = v)
            "mode" -> n.copy(permissionMode = v)
            "workspace" -> n.copy(workspace = v)
            /*
             * 这些都得能改：以前只认 maxTurns，于是新加的设置项在 CLI 上是只读的 ——
             * 用户照着 README 敲 `haoai set maxTokens=150` 只会得到一句"不认识的设置项"，
             * 而界面上没有的字段（压缩阈值、截断上限）就没有第二条路。
             */
            "maxTurns" -> n.copy(maxTurns = int(k, v, n.maxTurns))
            "maxTokens" -> n.copy(maxTokens = int(k, v, n.maxTokens))
            "contextChars" -> n.copy(contextChars = int(k, v, n.contextChars))
            "reasoningEffort", "effort" -> n.copy(reasoningEffort = v.trim())
            "storedCap" -> n.copy(storedCap = int(k, v, n.storedCap))
            "reqCap" -> n.copy(reqCap = int(k, v, n.reqCap))
            "compactTriggerChars" -> n.copy(compactTriggerChars = int(k, v, n.compactTriggerChars))
            "compactKeepTail" -> n.copy(compactKeepTail = int(k, v, n.compactKeepTail))
            "temperature" -> n.copy(
                temperature = v.toDoubleOrNull()
                    ?: run { println("「temperature」要的是小数，给的是「$v」—— 这项没改"); n.temperature }
            )
            else -> {
                println("不认识的设置项：$k（可用：model base mode workspace maxTurns maxTokens " +
                    "reasoningEffort storedCap reqCap compactTriggerChars compactKeepTail temperature provider）")
                n
            }
        }
    }
    PcSettings.save(n)
    println("已保存：model=${n.model} base=${n.baseUrl} mode=${n.permissionMode} " +
        "workspace=${n.workspace} maxTokens=${n.maxTokens}")
}

private fun flags(s: PcSettings, rest: List<String>) {
    if (rest.isEmpty()) {
        println("实验特性（设置后重启进程生效）：")
        HaoFlag.entries.forEach { f ->
            println("  ${if (HaoFlag.enabled(f, s.flags)) "●" else "○"} ${f.key}  ${f.title}")
            println("      ${f.what}")
        }
        return
    }
    val on = rest[0].lowercase() == "on"
    val key = rest.getOrNull(1) ?: return println("用法：haoai flags on|off <key>")
    val f = HaoFlag.byKey(key) ?: return println("没有这个开关：$key")
    val merged = HaoFlag.compactOverrides(s.flags + (key to on))
    PcSettings.save(s.copy(flags = merged))
    println("${f.title} -> ${if (HaoFlag.enabled(f, merged)) "开" else "关"}")
}

/**
 * 加一条规则。两种写法：
 *   haoai allow "git push*"          —— 只给模式，工具靠猜（含 / \ 或扩展名算路径→write，否则 shell）
 *   haoai deny  "write(.env)"        —— 显式 `tool(pattern)`
 * 规则按工作区分开存，"在这个仓库里允许"不会漏到别的仓库。
 */
private fun addRule(s: PcSettings, rest: List<String>, d: Decision) {
    val raw = rest.joinToString(" ").trim()
    if (raw.isEmpty()) {
        println("用法：haoai allow \"git push*\" | haoai deny \"write(.env)\" | haoai ask \"shell(rm *)\"")
        return
    }
    val rule = if (raw.contains('(')) {
        Rule.parse("$raw ${d.name}")
    } else {
        val tool = if (raw.contains('/') || raw.contains('\\') || Regex("""\.\w+$""").containsMatchIn(raw)) "write" else "shell"
        Rule(tool, if (raw.contains('*')) raw else "$raw*", d)
    } ?: return println("规则写法不对：$raw")
    Policies.get().add(s.workspaceFile(), rule)
    println("已加入规则：${rule.render()}   （工作区 ${s.workspaceFile().absolutePath}）")
    if (rule.decision == Decision.ALLOW && PolicyStore.commandPrefix(rule.pattern.trimEnd('*')) in PolicyStore.ALWAYS_ASK) {
        println("  注意：这条在「必须人工确认」清单里，allow 不会生效 —— 那是最后一道闸，绕不过。")
    }
}

private fun listRules(s: PcSettings, rest: List<String>) {
    val ws = s.workspaceFile()
    if (rest.firstOrNull() == "clear") {
        Policies.get().clear(ws)
        println("已清空本工作区规则")
        return
    }
    val rs = Policies.get().rules(ws)
    println("工作区 ${ws.absolutePath} 的规则（后写的覆盖先写的）：")
    if (rs.isEmpty()) println("  （空 —— 全部交给档位决定）")
    rs.forEachIndexed { i, r -> println("  ${i + 1}. ${r.render()}") }
    println("\n任何档位都绕不过、必须问人的命令前缀：")
    println("  " + PolicyStore.ALWAYS_ASK.sorted().chunked(4).joinToString("\n  ") { it.joinToString(" · ") })
}

private fun task(s: PcSettings, rest: List<String>) {
    val text = rest.filter { !it.startsWith("--") }.joinToString(" ").ifBlank {
        println("用法：haoai task \"要做什么\" [--auto] [--plan]"); return
    }
    val auto = rest.contains("--auto")
    val plan = rest.contains("--plan")
    val settings = when {
        plan -> s.copy(permissionMode = "plan")
        auto -> s.copy(permissionMode = "auto")
        else -> s
    }
    val printer = printer()
    val engine = Sessions.create(settings, settings.workspaceFile(), cliGate(auto || plan, settings.workspaceFile()), printer)
    // 正文由 printer 的 TextDelta/TextDone 事件负责，这里不再手动补一遍（会打印两次）
    engine.submit(text)
}

private fun chat(s: PcSettings) {
    line("HaoAI PC $PC_VERSION · ${s.model} · 档位 ${s.permissionMode}")
    println("  工作区 ${s.workspaceFile().absolutePath}")
    println("  /mode plan|ask|auto 切档位   /quit 退出   其余文本就是给 agent 的任务")
    val printer = printer()
    val engine = Sessions.create(s, s.workspaceFile(), cliGate(s.permissionMode == "auto", s.workspaceFile()), printer)
    val br = BufferedReader(InputStreamReader(System.`in`, StandardCharsets.UTF_8))
    while (true) {
        print("\n你 > ")
        val line = br.readLine()?.trim() ?: break
        if (line.isEmpty()) continue
        if (line == "/quit" || line == "/exit") break
        if (line.startsWith("/mode ")) {
            val m = line.removePrefix("/mode").trim()
            if (m in listOf("plan", "ask", "auto")) {
                engine.session.mode = m
                println("档位 -> $m")
            }
            continue
        }
        val out = engine.submit(line)
        if (out.isBlank()) println("(本轮没有正文输出)")
    }
}

/**
 * `haoai browser <sub> [--url=…] [--selector=…] [--script=…] [--path=…]`
 *
 * 直通透传给 browser 工具。存在的理由有两个：① 没密钥时也能验收 CDP 这条路；
 * ② 出问题时人可以直接敲一条命令复现，不用先跟模型解释一遍。
 */
private fun browserCmd(s: PcSettings, rest: List<String>) {
    val sub = rest.firstOrNull { !it.startsWith("--") } ?: "status"
    val opts = rest.filter { it.startsWith("--") }.associate {
        it.removePrefix("--").substringBefore('=') to it.substringAfter('=', "")
    }
    if (!HaoFlag.enabled(HaoFlag.BROWSER_CONTROL, s.flags)) {
        println("浏览器控制现在是关的（这是默认）。先：haoai flags on ${HaoFlag.BROWSER_CONTROL.key}")
        return
    }
    val args = kotlinx.serialization.json.buildJsonObject {
        put("sub", sub)
        opts["url"]?.let { put("url", it) }
        opts["selector"]?.let { put("selector", it) }
        opts["script"]?.let { put("script", it) }
        opts["text"]?.let { put("text", it) }
        opts["path"]?.let { put("path", it) }
    }
    val ws = s.workspaceFile()
    val ctx = ToolCtx(ws, s, "auto", cliGate(true, ws))
    val r = BrowserTool().run(args, ctx)
    println((if (r.error) "× " else "") + r.content)
}

/**
 * `haoai screen <sub> [--path=] [--title=] [--name=] [--x=] [--y=] [--button=] [--double=] [--text=] [--keys=]`
 *
 * opts 直接透传成 JSON 参数（不再逐个列，免得新加一个 sub 参数就悄悄丢掉）。
 */
private fun screenCmd(s: PcSettings, rest: List<String>) {
    val sub = rest.firstOrNull { !it.startsWith("--") } ?: "windows"
    val opts = rest.filter { it.startsWith("--") }.associate {
        it.removePrefix("--").substringBefore('=') to it.substringAfter('=', "")
    }
    if (!HaoFlag.enabled(HaoFlag.DESKTOP_CONTROL, s.flags)) {
        println("屏幕与点击控制现在是关的（这是默认）。先：haoai flags on ${HaoFlag.DESKTOP_CONTROL.key}")
        return
    }
    val args = kotlinx.serialization.json.buildJsonObject {
        put("sub", sub)
        for ((k, v) in opts) if (v.isNotBlank()) put(k, v)
    }
    val ws = s.workspaceFile()
    val ctx = ToolCtx(ws, s, "auto", cliGate(true, ws))
    val r = ScreenTool().run(args, ctx)
    println((if (r.error) "× " else "") + r.content)
}

private fun serve(s: PcSettings, rest: List<String>) {
    val port = rest.indexOf("--port").takeIf { it >= 0 && it + 1 < rest.size }?.let { rest[it + 1].toIntOrNull() } ?: 8712
    val ws = WebServer(s, port)
    val actual = ws.start()
    line("HaoAI PC $PC_VERSION 已启动")
    println("  打开浏览器访问  http://127.0.0.1:$actual/")
    println("  工作区 ${s.workspaceFile().absolutePath}   模型 ${s.model}   档位 ${s.permissionMode}")
    println("  Ctrl+C 退出")
}

private fun sessions() {
    val files = Env.sessionsDir.listFiles { f -> f.isFile && f.name.endsWith(".json") }?.toList() ?: emptyList()
    if (files.isEmpty()) {
        println("还没有会话记录：${Env.sessionsDir.absolutePath}")
        return
    }
    files.sortedByDescending { it.lastModified() }.take(20).forEach { f ->
        val txt = runCatching { f.readText() }.getOrDefault("")
        val title = Regex("\"title\":\"([^\"]*)\"").find(txt)?.groupValues?.get(1) ?: f.name
        val ws = Regex("\"workspace\":\"([^\"]*)\"").find(txt)?.groupValues?.get(1) ?: "?"
        println("  ${f.name}  ${title}  ${ws}  ${f.length()}B")
    }
}

// ---- 终端输出 ----

private fun printer(): (Ev) -> Unit {
    var streaming = false
    return fun(ev: Ev) {
        when (ev) {
            is Ev.TextDelta -> {
                if (!streaming) {
                    print("\nHaoAI > ")
                    streaming = true
                }
                print(ev.s)
                System.out.flush()
            }
            is Ev.ToolStart -> {
                streaming = false
                println("\n  · ${ev.name} ${ev.brief.replace("\n", " ").take(110)}")
            }
            is Ev.ToolEnd -> {
                val head = ev.out.lines().firstOrNull { it.isNotBlank() } ?: ""
                println("    ${if (ev.ok) "✓" else "✗"} ${head.take(150)}")
            }
            is Ev.Todo -> {
                println("  待办：")
                ev.items.forEach { println("    $it") }
            }
            is Ev.Notice -> println("  ! ${ev.s}")
            is Ev.Err -> println("  × ${ev.s}")
            is Ev.TextDone -> {
                if (!streaming && ev.s.isNotBlank()) println("HaoAI > ${ev.s}")
                streaming = false
                println()
            }
            else -> Unit
        }
    }
}

private fun cliGate(auto: Boolean, workspace: File): Gate = object : Gate {
    private val br = BufferedReader(InputStreamReader(System.`in`, StandardCharsets.UTF_8))

    @Volatile
    private var allowAll = auto

    override fun approve(title: String, detail: String, kind: String): Boolean = ask0(title, detail, kind, null, null)

    override fun approveRule(
        title: String, detail: String, kind: String, tool: String, pattern: String
    ): Boolean = ask0(title, detail, kind, tool, pattern)

    private fun ask0(
        title: String, detail: String, kind: String, tool: String?, pattern: String?
    ): Boolean {
        if (allowAll && tool == null) return true
        println("\n  ⚠ 需要确认（$kind）：$title")
        detail.lines().take(8).forEach { println("      $it") }
        val line = if (tool != null && pattern != null)
            "  [y]允许一次 / [r]以后「$tool($pattern)」都允许 / [s]本任务都允许 / [n]拒绝 > "
        else "  [y]允许一次 / [s]本次会话都允许 / [n]拒绝 > "
        print(line)
        val a = br.readLine()?.trim()?.lowercase() ?: "n"
        return when (a) {
            "r", "rule", "always" -> {
                if (tool != null && pattern != null) {
                    Policies.get().add(workspace, Rule(tool, pattern, Decision.ALLOW))
                    println("  已写入规则：$tool($pattern) ALLOW")
                }
                true
            }
            "s", "session" -> {
                allowAll = true
                true
            }
            "y", "yes", "" -> true
            else -> false
        }
    }

    override fun ask(question: String, options: List<String>): String {
        println("\n  ? $question")
        options.forEachIndexed { i, o -> println("      ${i + 1}. $o") }
        print("  回答 > ")
        val a = br.readLine()?.trim() ?: ""
        val idx = a.toIntOrNull()
        return if (idx != null && idx in 1..options.size) options[idx - 1] else a
    }
}

private fun line(t: String) {
    println("─".repeat(46))
    println("  $t")
    println("─".repeat(46))
}
