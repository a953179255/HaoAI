package com.haoai.pc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * hooks：把一段用户自己配的脚本挂到事件上。
 *
 * 事件面（S7，对标 ZCODE 的 7 事件）：`run-end / session-start / user-prompt /
 * pre-tool / post-tool / post-tool-fail` —— ZCODE 那 7 个里，`Stop` 就是我们的
 * `run-end`（一轮收尾），`PermissionRequest` **刻意不做**：它的语义是"替审批做决定"，
 * 要把异步审批回环接进钩子；而"要不要放行"这层已经有 [Risk.kt] 的确定性打分与
 * 审批闸口（fail-closed、可测试），再叠一个会跑脚本的决策层只会两头打架。要做也该等
 * S8 把审批等待状态机化之后再说。
 *
 * 两条触发形状：
 * - **异步只记账**（[fire]）：session-start / user-prompt / post-tool(-fail) / run-end ——
 *   钩子坏了最坏是"这次没记上"，绝不拖住、绝不打断会话。
 * - **同步闸**（[gate]）：只有 `pre-tool`。钩子**退出码 2 = 拦下这一步**，stdout 是给人看
 *   的理由；退出码 0 放行；**失败、超时、跑不起来都只记账不拦**（硬规矩 1 对闸也成立 ——
 *   钩子自己坏了不该把工具全锁死）。这是 ZCODE/Claude Code 的 PreToolUse 约定：
 *   合规检查、"这类命令我绝不允许"这类**用户自己的闸**，与 [Risk.kt] 打分互补
 *   （一个拦机器判的高危，一个拦用户自己定的规矩）。
 *
 * 三条硬规矩（都写进测试了）：
 * 1. **失败只记账，绝不打断会话** —— 钩子坏了最坏的结果是"这次没记上"，
 *    不能变成"我的 agent 跑挂了"。整段 `runCatching`，结果只写进条目的 `last`。
 * 2. **异步跑** —— 一条 30 秒的钩子不该让人盯着转圈等"跑完"。丢进单线程 daemon 池，
 *    顺带把多条钩子的写入串行化（同一份 hooks.json 不能两头改）。
 *    （`pre-tool` 的同步是**语义本身**：闸就是要等它出结论，超时上限有 timeoutSec。）
 * 3. **命令文本只有用户能加**（`/api/hooks` 只给界面用，模型侧没有这把工具）——
 *    所以它**不经审批闸口**：这是用户自己写下并保存的指令，
 *    而且触发时人往往不在电脑前，弹一张没人看的卡只会把会话钉死 300 秒。
 */
data class Hook(
    val id: String,
    val name: String,
    val event: String,
    val command: String,
    val shell: String = "pwsh",
    val timeoutSec: Int = 30,
    val enabled: Boolean = true,
    /** 上次跑的结果摘要（`exit=0 …` 或失败原因），0 = 还没跑过。 */
    val last: String = "",
    val lastAt: Long = 0L
)

object Hooks {

    /** 上限：钩子是"每次都跑"的东西，多了就没人记得自己配过什么。 */
    const val MAX = 8
    const val RUN_END = "run-end"
    const val SESSION_START = "session-start"
    const val USER_PROMPT = "user-prompt"
    const val PRE_TOOL = "pre-tool"
    const val POST_TOOL = "post-tool"
    const val POST_TOOL_FAIL = "post-tool-fail"
    val EVENTS = listOf(RUN_END, SESSION_START, USER_PROMPT, PRE_TOOL, POST_TOOL, POST_TOOL_FAIL)

    /** 事件名 → 界面上那句话。加新事件就在这里加一行。 */
    fun eventLabel(event: String): String = when (event) {
        RUN_END -> "一轮跑完"
        SESSION_START -> "会话开始"
        USER_PROMPT -> "话进来了"
        PRE_TOOL -> "工具执行前（退出码 2 = 拦下这一步）"
        POST_TOOL -> "工具执行后"
        POST_TOOL_FAIL -> "工具失败后"
        else -> event
    }

    data class Ctx(
        val sid: String, val runId: String, val title: String, val model: String,
        val workspace: File, val mode: String, val trigger: String,
        val stopped: Boolean, val finalText: String,
        /** 工具类事件才有值：pre/post 用。进 HAOAI_TOOL / HAOAI_TOOL_ARGS。 */
        val tool: String = "",
        val toolArgs: String = "",
        /** post 类事件带回去的工具产出摘要（进 HAOAI_TOOL_RESULT）。 */
        val result: String = ""
    )

    /** 同步闸的结论。`blocked=true` 时 [reason] 是给人和模型看的理由。 */
    data class Gate(val blocked: Boolean, val hook: String = "", val reason: String = "")

    private const val PWSH_UTF8_PREFIX = "[Console]::OutputEncoding=[System.Text.Encoding]::UTF8;"

    private val pool = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "haoai-hook").apply { isDaemon = true }
    }

    fun file(): File = File(Env.home, "hooks.json")

    fun load(): MutableList<Hook> = runCatching {
        val root = Json.parseToJsonElement(file().readText())
        val arr = if (root is JsonArray) root else root.jsonObject["items"]?.jsonArray ?: JsonArray(emptyList())
        arr.mapNotNull { el ->
            val o = runCatching { el.jsonObject }.getOrNull() ?: return@mapNotNull null
            val id = o["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val event = o["event"]?.jsonPrimitive?.contentOrNull ?: ""
            Hook(
                id = id,
                name = o["name"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { id },
                event = event.takeIf { it in EVENTS } ?: RUN_END,
                command = o["command"]?.jsonPrimitive?.contentOrNull ?: "",
                shell = o["shell"]?.jsonPrimitive?.contentOrNull ?: "pwsh",
                timeoutSec = (o["timeoutSec"]?.jsonPrimitive?.intOrNull ?: 30).coerceIn(1, 600),
                enabled = o["enabled"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() != false,
                last = o["last"]?.jsonPrimitive?.contentOrNull ?: "",
                lastAt = o["lastAt"]?.jsonPrimitive?.longOrNull ?: 0L
            )
        }.toMutableList()
    }.getOrDefault(mutableListOf())

    fun save(list: List<Hook>) {
        val body = list.joinToString(",", "[", "]") { h ->
            """{"id":${js(h.id)},"name":${js(h.name)},"event":${js(h.event)},""" +
                """"command":${js(h.command)},"shell":${js(h.shell)},"timeoutSec":${h.timeoutSec},""" +
                """"enabled":${h.enabled},"last":${js(h.last)},"lastAt":${h.lastAt}}"""
        }
        runCatching {
            file().parentFile?.mkdirs()
            file().writeText(body)
        }
    }

    fun update(item: Hook): MutableList<Hook> {
        val list = load()
        val i = list.indexOfFirst { it.id == item.id }
        if (i >= 0) list[i] = item else { if (list.size >= MAX) return list; list += item }
        save(list)
        return list
    }

    fun remove(id: String): Boolean {
        val list = load()
        val kept = list.filterNot { it.id == id }
        if (kept.size == list.size) return false
        save(kept)
        return true
    }

    fun find(id: String): Hook? = load().firstOrNull { it.id == id }

    /**
     * 异步事件触发。**不许抛，也不许拖住调用方**：外面是引擎的那几行，
     * 钩子坏在这里就等于"跑完一句话反而把会话弄挂"。
     */
    fun fire(event: String, c: Ctx) {
        runCatching {
            val due = load().filter { it.event == event && it.enabled && it.command.isNotBlank() }
            if (due.isEmpty()) return
            due.forEach { h -> pool.submit { runCatching { runOne(h, c) } } }
        }
    }

    /**
     * 同步闸（只有 `pre-tool` 用）：按顺序跑该事件的每条钩子，
     * **任何一条退出码 2 就拦**；失败 / 超时 / 跑不起来只记账继续 —— 钩子自己坏了
     * 不该把工具全锁死（硬规矩 1 对闸同样成立）。不许抛。
     */
    fun gate(event: String, c: Ctx): Gate {
        val due = runCatching {
            load().filter { it.event == event && it.enabled && it.command.isNotBlank() }
        }.getOrDefault(emptyList())
        for (h in due) {
            val r = runCatching { runOneDetailed(h, c) }.getOrNull() ?: continue
            if (r.code == 2) {
                val why = r.out.trim().ifBlank { "（退出码 2，没给理由）" }
                return Gate(blocked = true, hook = h.name, reason = TextCap.middle(why, 200))
            }
        }
        return Gate(blocked = false)
    }

    /** 跑一条并记账，返回结果摘要（测试与"立刻试跑一次"走这条；fire 走线程池）。 */
    fun runOne(h: Hook, c: Ctx): String = runOneDetailed(h, c).summary

    /** 比 [runOne] 多带一个退出码 —— 闸（[gate]）只认这个码，摘要字符串是给人看的。 */
    fun runOneDetailed(h: Hook, c: Ctx): Exec {
        val r = exec(h, c)
        update(h.copy(last = r.summary.take(280), lastAt = System.currentTimeMillis()))
        return r
    }

    /** 一次执行的两种读法：[summary] 给界面与 `last`，[code] 给闸（null = 没跑出码来），
     *  [out] 是原始 stdout（退出码 2 时它就是拦下理由）。 */
    data class Exec(val summary: String, val code: Int?, val out: String = "")

    private fun exec(h: Hook, c: Ctx): Exec {
        val launcher = ShellLauncher.forName(h.shell)
            ?: return Exec("这台机器上找不到 ${h.shell}，这条钩子没跑", null, "")
        val kind = when {
            launcher.first.endsWith("pwsh.exe") || launcher.first.endsWith("powershell.exe") -> "pwsh"
            launcher.first.endsWith("cmd.exe") -> "cmd"
            else -> "sh"
        }
        // 与 ShellTool 同一套机制：命令进临时脚本文件、PowerShell 那份带 UTF-8 BOM。
        // 不复用它，是因为它前面挂着审批闸口（见文件头第 3 条）。
        val script = File.createTempFile(
            "haoai-hook-",
            if (kind == "pwsh") ".ps1" else if (kind == "cmd") ".bat" else ".sh",
            File(System.getProperty("java.io.tmpdir"))
        )
        // 结论全文走**文件**不走 argv：几 KB 的回答塞进命令行一定被截或被改形。
        val out = File.createTempFile("haoai-hook-out-", ".txt", File(System.getProperty("java.io.tmpdir")))
        return try {
            val body = if (kind == "pwsh") "$PWSH_UTF8_PREFIX ${h.command}" else h.command
            val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
            if (kind == "pwsh") script.writeBytes(bom + body.toByteArray(Charsets.UTF_8))
            else script.writeText(body)
            out.writeText(c.finalText)
            val pb = ProcessBuilder(listOf(launcher.first) + launcher.second + script.absolutePath)
                .directory(if (c.workspace.isDirectory) c.workspace else File(System.getProperty("user.home")))
                .redirectErrorStream(true)
            with(pb.environment()) {
                put("HAOAI_EVENT", h.event)
                put("HAOAI_HOOK", h.name)
                put("HAOAI_SID", c.sid)
                put("HAOAI_RUN", c.runId)
                put("HAOAI_TITLE", c.title)
                put("HAOAI_MODEL", c.model)
                put("HAOAI_MODE", c.mode)
                put("HAOAI_TRIGGER", c.trigger)
                put("HAOAI_STOPPED", if (c.stopped) "1" else "0")
                put("HAOAI_WS", c.workspace.absolutePath)
                put("HAOAI_OUTFILE", out.absolutePath)
                // 工具类事件（pre/post）：进环境变量的都截断 —— 写文件的 content 能有几 MB，
                // 环境变量块是有上限的，塞满的症状是"进程根本起不来"。
                put("HAOAI_TOOL", c.tool.take(200))
                put("HAOAI_TOOL_ARGS", c.toolArgs.take(4000))
                put("HAOAI_TOOL_RESULT", c.result.take(4000))
            }
            val p = pb.start()
            val sb = StringBuilder()
            val reader = Thread {
                BufferedReader(InputStreamReader(p.inputStream, Charsets.UTF_8)).use { br ->
                    while (true) {
                        val l = br.readLine() ?: break
                        sb.append(l).append('\n')
                    }
                }
            }
            reader.isDaemon = true
            reader.start()
            if (!p.waitFor(h.timeoutSec.toLong(), TimeUnit.SECONDS)) {
                p.destroyForcibly()
                reader.join(1000)
                // 超时没有退出码 —— 闸把它当"没结论"，不拦（硬规矩 1）
                return Exec("超时 ${h.timeoutSec}s 已终止：${TextCap.middle(sb.toString(), 200)}", null, sb.toString())
            }
            reader.join(3000)
            val code = p.exitValue()
            Exec("exit=$code ${TextCap.middle(sb.toString().trim(), 200)}", code, sb.toString().trim())
        } catch (e: Exception) {
            Exec("跑不起来：${e.message}", null, "")
        } finally {
            runCatching { script.delete() }
            runCatching { out.delete() }
        }
    }

    fun json(): String {
        val items = load().joinToString(",") { h ->
            """{"id":${js(h.id)},"name":${js(h.name)},"event":${js(h.event)},""" +
                """"eventLabel":${js(eventLabel(h.event))},"command":${js(h.command)},""" +
                """"shell":${js(h.shell)},"timeoutSec":${h.timeoutSec},"enabled":${h.enabled},""" +
                """"last":${js(h.last)},"ok":${h.last.startsWith("exit=0")},"lastAt":${h.lastAt}}"""
        }
        // 事件清单带中文标签：下拉框里摆"工具执行前（退出码 2 = 拦下这一步）"，
        // 比一串 `pre-tool` 自解释 —— 事件 id 是给机器的，标签是给人的。
        val labels = EVENTS.joinToString(",") { js(it) + ":" + js(eventLabel(it)) }
        return """{"ok":true,"items":[$items],"max":$MAX,"events":${EVENTS.joinToString(",", "[", "]") { js(it) }},""" +
            """"eventLabels":{$labels}}"""
    }

    private fun js(s: String): String = "\"" +
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "") + "\""
}
