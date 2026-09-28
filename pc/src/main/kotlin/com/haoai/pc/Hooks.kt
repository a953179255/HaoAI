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
 * hooks：把一段用户自己配的脚本挂到事件上。第一个事件是 `run-end`（一轮跑完）。
 *
 * 为什么要：直播/巡检那类用法里，"跑完之后顺手记一笔 / 推个通知 / 把产物挪进目录"
 * 是每次都做的事，但不该占着会话里的一句话。OpenClaw 的 session-memory、
 * compaction-notifier、ZCODE 的 hooks 都是同一个形状。
 *
 * 三条硬规矩（都写进测试了）：
 * 1. **失败只记账，绝不打断会话** —— 钩子坏了最坏的结果是"这次没记上"，
 *    不能变成"我的 agent 跑挂了"。整段 `runCatching`，结果只写进条目的 `last`。
 * 2. **异步跑** —— 一条 30 秒的钩子不该让人盯着转圈等"跑完"。丢进单线程 daemon 池，
 *    顺带把多条钩子的写入串行化（同一份 hooks.json 不能两头改）。
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
    val EVENTS = listOf(RUN_END)

    /** 事件名 → 界面上那句话。加新事件就在这里加一行。 */
    fun eventLabel(event: String): String = if (event == RUN_END) "一轮跑完" else event

    data class Ctx(
        val sid: String, val runId: String, val title: String, val model: String,
        val workspace: File, val mode: String, val trigger: String,
        val stopped: Boolean, val finalText: String
    )

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
     * 事件触发。**不许抛，也不许拖住调用方**：外面是引擎收尾的那几行，
     * 钩子坏在这里就等于"跑完一句话反而把会话弄挂"。
     */
    fun fire(event: String, c: Ctx) {
        runCatching {
            val due = load().filter { it.event == event && it.enabled && it.command.isNotBlank() }
            if (due.isEmpty()) return
            due.forEach { h -> pool.submit { runCatching { runOne(h, c) } } }
        }
    }

    /** 同步跑一条（测试与"立刻试跑一次"走这条；fire 走线程池）。返回结果摘要。 */
    fun runOne(h: Hook, c: Ctx): String {
        val r = exec(h, c)
        update(h.copy(last = r.take(280), lastAt = System.currentTimeMillis()))
        return r
    }

    private fun exec(h: Hook, c: Ctx): String {
        val launcher = ShellLauncher.forName(h.shell)
            ?: return "这台机器上找不到 ${h.shell}，这条钩子没跑"
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
                return "超时 ${h.timeoutSec}s 已终止：${TextCap.middle(sb.toString(), 200)}"
            }
            reader.join(3000)
            "exit=${p.exitValue()} ${TextCap.middle(sb.toString().trim(), 200)}"
        } catch (e: Exception) {
            "跑不起来：${e.message}"
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
        return """{"ok":true,"items":[$items],"max":$MAX,"events":${EVENTS.joinToString(",", "[", "]") { js(it) }}}"""
    }

    private fun js(s: String): String = "\"" +
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "") + "\""
}
