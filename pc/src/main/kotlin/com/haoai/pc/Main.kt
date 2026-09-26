package com.haoai.pc

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
          haoai task "…" [--auto]      跑一条任务就退出
          haoai chat                   终端对话
          haoai serve [--port 8712]    打开本地网页版
        """.trimIndent()
    )
}

private const val PC_VERSION = "0.1.0-pc"

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
    rest.forEach { kv ->
        val (k, v) = kv.split("=", limit = 2).let { if (it.size == 2) it[0] to it[1] else return@forEach }
        n = when (k) {
            "model" -> n.copy(model = v)
            "base", "baseUrl" -> n.copy(baseUrl = v)
            "provider" -> n.copy(providerName = v)
            "mode" -> n.copy(permissionMode = v)
            "workspace" -> n.copy(workspace = v)
            "maxTurns" -> n.copy(maxTurns = v.toIntOrNull() ?: n.maxTurns)
            else -> {
                println("不认识的设置项：$k")
                n
            }
        }
    }
    PcSettings.save(n)
    println("已保存：model=${n.model} base=${n.baseUrl} mode=${n.permissionMode} workspace=${n.workspace}")
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
    val engine = Sessions.create(settings, settings.workspaceFile(), cliGate(auto || plan), printer)
    // 正文由 printer 的 TextDelta/TextDone 事件负责，这里不再手动补一遍（会打印两次）
    engine.submit(text)
}

private fun chat(s: PcSettings) {
    line("HaoAI PC $PC_VERSION · ${s.model} · 档位 ${s.permissionMode}")
    println("  工作区 ${s.workspaceFile().absolutePath}")
    println("  /mode plan|ask|auto 切档位   /quit 退出   其余文本就是给 agent 的任务")
    val printer = printer()
    val engine = Sessions.create(s, s.workspaceFile(), cliGate(s.permissionMode == "auto"), printer)
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

private fun cliGate(auto: Boolean): Gate = object : Gate {
    private val br = BufferedReader(InputStreamReader(System.`in`, StandardCharsets.UTF_8))

    @Volatile
    private var allowAll = auto

    override fun approve(title: String, detail: String, kind: String): Boolean {
        if (allowAll) return true
        println("\n  ⚠ 需要确认（$kind）：$title")
        detail.lines().take(8).forEach { println("      $it") }
        print("  [y]允许一次 / [s]本次会话都允许 / [n]拒绝 > ")
        val a = br.readLine()?.trim()?.lowercase() ?: "n"
        return when (a) {
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
