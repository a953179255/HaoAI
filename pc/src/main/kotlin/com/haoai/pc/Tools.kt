package com.haoai.pc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.TimeUnit

/** 与手机端 `TextCap` 同一套算法 —— 两端截断口径必须一致。 */
object TextCap {
    fun middle(text: String, max: Int): String {
        if (text.length <= max) return text
        val head = (max * 0.65).toInt()
        val tail = (max * 0.25).toInt()
        val omitted = text.length - head - tail
        return text.substring(0, head) + "\n…［中间省略约 $omitted 字符］…\n" + text.takeLast(tail)
    }

    fun head(text: String, max: Int): String = if (text.length <= max) text else text.substring(0, max) + "…"
}

data class ToolResult(
    val content: String,
    val error: Boolean = false,
    /** 渲染意图：generic | terminal | diff —— 前端按这个决定摆哪种卡（照 dsh 的插槽注册）。 */
    val card: String = "generic"
)

/** 审批与提问的出口。CLI 与 Web 各实现一份，工具层不关心前面是谁。 */
interface Gate {
    fun approve(title: String, detail: String, kind: String): Boolean
    fun ask(question: String, options: List<String>): String

    /**
     * 带"以后这类都允许"的审批。默认实现退化成普通 approve，
     * 这样测试与脚本化场景不用改；CLI 与网页各自覆盖它来落规则。
     */
    fun approveRule(
        title: String,
        detail: String,
        kind: String,
        tool: String,
        pattern: String
    ): Boolean = approve(title, detail, kind)
}

data class Todo(var text: String, var status: String = "pending")

class ToolCtx(
    val workspace: File,
    val settings: PcSettings,
    var mode: String,
    val gate: Gate,
    val todos: MutableList<Todo> = mutableListOf()
) {
    fun resolve(p: String): File {
        val clean = p.trim().replace('\\', '/')
        val f = if (File(clean).isAbsolute) File(clean) else File(workspace, clean)
        return runCatching { f.canonicalFile }.getOrElse { f.absoluteFile }
    }

    fun rel(f: File): String = runCatching {
        val ws = workspace.canonicalPath
        val abs = f.canonicalPath
        if (abs == ws) "." else if (abs.startsWith(ws + File.separator))
            abs.substring(ws.length + 1).replace('\\', '/') else abs
    }.getOrElse { f.toString() }

    fun outside(f: File): Boolean = !f.canonicalPath.startsWith(workspace.canonicalPath + File.separator)

    /**
     * 统一闸口：先查规则表（S2），再落到档位。
     *
     * 顺序很要紧 —— **规则先于档位**，否则 `auto` 会把用户专门写下的"这条要问我"给跳过。
     * 反过来 `plan` 是最高优先级：计划模式就是只读，规则表也放行不了写。
     *
     * @param tool 工具名（shell / write / edit …）
     * @param subject 这次动作的对象：shell 传整条命令，写类传相对路径
     */
    fun guard(tool: String, subject: String, title: String, detail: String): String? {
        val kind = if (tool == "shell") "exec" else "write"
        val verdict = Policies.get().decide(workspace, tool, subject)

        if (verdict?.decision == Decision.DENY) {
            return "规则拒绝：${verdict.why}。这条被显式禁掉了，别再重试，换方案或用 ask_user 问用户。"
        }
        if (mode == "plan") {
            return "计划模式（只读）下拒绝执行「$title」。要动手请先切到 ask/auto 档位。"
        }
        if (verdict?.decision == Decision.ALLOW) return null
        if (mode == "auto" && verdict?.decision != Decision.ASK) return null

        val extra = if (kind == "write" && outside(resolve(subject))) "（在工作区之外）" else ""
        val why = if (verdict != null) "\n为什么还要问：${verdict.why}" else ""
        val pattern = if (tool == "shell") PolicyStore.commandPrefix(subject) else subject
        val ok = gate.approveRule(title, detail + extra + why, kind, tool, pattern)
        return if (ok) null else "用户拒绝了这次「$title」。不要原样重试，换个方案或用 ask_user 问清楚。"
    }

    fun snapshotBefore(target: File) {
        if (!HaoFlag.enabled(HaoFlag.SNAPSHOT_BEFORE_WRITE, settings.flags)) return
        if (!target.isFile) return
        runCatching {
            val dir = File(workspace, ".haoai-snap").apply { mkdirs() }
            Env.excludeFromGit(workspace, ".haoai-snap")
            target.copyTo(File(dir, "${System.currentTimeMillis()}-${target.name}"), overwrite = true)
        }
    }
}

abstract class Tool(
    val name: String,
    val desc: String,
    val params: JsonObject,
    val kind: String = "read",
    /**
     * 挂在这个工具上的实验特性。不为 null 且开关关着时，工具**不进 schema** ——
     * 模型看不见它，也就不会去调、不会调通了再收到一句"没权限"。
     * 这就是 S6 开关注册表存在的意义：新能力可以"先上代码，再按需给可见性"。
     */
    val flag: HaoFlag? = null
) {
    fun visibleWhen(settings: PcSettings): Boolean =
        flag == null || HaoFlag.enabled(flag, settings.flags)

    abstract fun run(args: JsonObject, ctx: ToolCtx): ToolResult

    fun req(args: JsonObject, k: String): String? = args[k]?.jsonPrimitive?.content
    fun int(args: JsonObject, k: String, dflt: Int): Int = args[k]?.jsonPrimitive?.content?.toIntOrNull() ?: dflt
    fun bool(args: JsonObject, k: String): Boolean = args[k]?.jsonPrimitive?.content == "true"

    protected fun fail(msg: String) = ToolResult(msg, error = true)
}

class ReadTool : Tool(
    "read", "读取文件内容，返回带行号。大文件用 offset/limit 分段读。",
    schema("path" to "string", "offset" to "integer", "limit" to "integer", required = arrayOf("path"))
) {
    override fun run(args: JsonObject, ctx: ToolCtx): ToolResult {
        val path = req(args, "path") ?: return fail("read 缺少 path")
        val f = ctx.resolve(path)
        if (!f.isFile) return fail("文件不存在：${ctx.rel(f)}")
        if (f.length() > 4_000_000) return fail("文件过大（${f.length()} 字节），先用 grep 定位再按行读")
        return try {
            val lines = f.readLines()
            val offset = (req(args, "offset")?.toIntOrNull() ?: 1).coerceAtLeast(1)
            val limit = (req(args, "limit")?.toIntOrNull() ?: 400).coerceIn(1, 2000)
            if (offset > lines.size) return fail("offset 超出总行数 ${lines.size}")
            val end = minOf(lines.size, offset + limit - 1)
            val sb = StringBuilder("[${ctx.rel(f)}] 共 ${lines.size} 行，显示 $offset-$end\n")
            for (i in offset..end) sb.append(String.format("%5d: %s%n", i, TextCap.head(lines[i - 1], 500)))
            ToolResult(sb.toString().trimEnd())
        } catch (e: Exception) {
            fail("读取失败：${e.message}")
        }
    }
}

class WriteTool : Tool(
    "write", "新建或整篇覆盖一个文件。改已有文件请优先用 edit，整篇覆盖会丢掉你没看到的行。",
    schema("path" to "string", "content" to "string", required = arrayOf("path", "content")),
    kind = "write"
) {
    override fun run(args: JsonObject, ctx: ToolCtx): ToolResult {
        val path = req(args, "path") ?: return fail("write 缺少 path")
        val content = args["content"]?.jsonPrimitive?.content ?: return fail("write 缺少 content")
        val f = ctx.resolve(path)
        val why = ctx.guard("write", ctx.rel(f), "写入文件 ${ctx.rel(f)}", "新建或覆盖，共 ${content.length} 字符")
        if (why != null) return fail(why)
        return try {
            ctx.snapshotBefore(f)
            f.parentFile?.mkdirs()
            f.writeText(content)
            ToolResult("已写入 ${ctx.rel(f)}（${content.length} 字符 / ${content.lines().size} 行）", card = "diff")
        } catch (e: Exception) {
            fail("写入失败：${e.message}")
        }
    }
}

class EditTool : Tool(
    "edit", "文件内精确替换。old_string 必须在文件中唯一，除非 all=true。",
    schema(
        "path" to "string", "old_string" to "string", "new_string" to "string", "all" to "boolean",
        required = arrayOf("path", "old_string", "new_string")
    ),
    kind = "write"
) {
    override fun run(args: JsonObject, ctx: ToolCtx): ToolResult {
        val path = req(args, "path") ?: return fail("edit 缺少 path")
        val old = args["old_string"]?.jsonPrimitive?.content ?: return fail("edit 缺少 old_string")
        val new = args["new_string"]?.jsonPrimitive?.content ?: return fail("edit 缺少 new_string")
        val all = bool(args, "all")
        val f = ctx.resolve(path)
        if (!f.isFile) return fail("文件不存在：${ctx.rel(f)}")
        val why = ctx.guard("write", ctx.rel(f), "编辑 ${ctx.rel(f)}", "${old.length} → ${new.length} 字符")
        if (why != null) return fail(why)
        return try {
            val text = f.readText()
            val hits = text.split(old).size - 1
            if (hits == 0) return fail("没找到要替换的内容。先用 read 看清当前文本（注意缩进与换行是否一致）。")
            if (hits > 1 && !all) return fail("匹配到 $hits 处，不唯一。扩大 old_string 的上下文，或传 all=true。")
            val updated = if (hits == 1) text.replaceFirst(old, new) else text.replace(old, new)
            ctx.snapshotBefore(f)
            f.writeText(updated)
            ToolResult("已编辑 ${ctx.rel(f)}（替换 ${if (all) hits else 1} 处）\n${Diff.summary(old, new)}", card = "diff")
        } catch (e: Exception) {
            fail("编辑失败：${e.message}")
        }
    }
}

class GlobTool : Tool(
    "glob", "按通配找文件，如 **/*.kt。返回相对工作区的路径列表。",
    schema("pattern" to "string", "path" to "string", required = arrayOf("pattern"))
) {
    override fun run(args: JsonObject, ctx: ToolCtx): ToolResult {
        val pattern = req(args, "pattern") ?: return fail("glob 缺少 pattern")
        val root = req(args, "path")?.let { ctx.resolve(it) } ?: ctx.workspace
        if (!root.isDirectory) return fail("目录不存在：${ctx.rel(root)}")
        val rx = globToRegex(pattern)
        val out = mutableListOf<String>()
        for (f in walkFiles(root)) {
            if (rx.matches(ctx.rel(f))) out += ctx.rel(f)
            if (out.size >= 400) break
        }
        return ToolResult(if (out.isEmpty()) "(无匹配) $pattern" else out.sorted().joinToString("\n") + "\n— 共 ${out.size} 个")
    }
}

class GrepTool : Tool(
    "grep", "在文件内容里搜正则，返回 文件:行号:内容。找符号、定位问题用它，别整篇读。",
    schema(
        "pattern" to "string", "path" to "string", "glob" to "string", "ignore_case" to "boolean",
        required = arrayOf("pattern")
    )
) {
    override fun run(args: JsonObject, ctx: ToolCtx): ToolResult {
        val pattern = req(args, "pattern") ?: return fail("grep 缺少 pattern")
        val root = req(args, "path")?.let { ctx.resolve(it) } ?: ctx.workspace
        val fileRx = req(args, "glob")?.let { globToRegex(it) }
        val rx = runCatching {
            if (bool(args, "ignore_case")) Regex(pattern, RegexOption.IGNORE_CASE) else Regex(pattern)
        }.getOrElse { return fail("正则不合法：${it.message}") }
        val targets = if (root.isFile) listOf(root) else walkFiles(root)
        val sb = StringBuilder()
        var n = 0
        for (f in targets) {
            if (f.length() > 2_000_000) continue
            val rel = ctx.rel(f)
            if (fileRx != null && !fileRx.matches(rel)) continue
            var no = 0
            try {
                f.useLines { seq ->
                    seq.forEach { line ->
                        no++
                        if (rx.containsMatchIn(line)) {
                            n++
                            sb.append(rel).append(':').append(no).append(':')
                                .append(TextCap.head(line.trim(), 200)).append('\n')
                        }
                    }
                }
            } catch (_: Exception) {
            }
            if (n >= 500) break
        }
        return ToolResult(if (n == 0) "(无匹配) $pattern" else sb.toString().trimEnd() + "\n— 命中 $n 处")
    }
}

class ShellTool : Tool(
    "shell",
    "执行命令并返回 exit code + 合并的 stdout/stderr。shell=pwsh（默认）或 bash（Git Bash，" +
        "需要管道、heredoc、\$() 时用它）。超时用 timeout 秒控制。",
    schema("command" to "string", "shell" to "string", "timeout" to "integer", "cwd" to "string",
        required = arrayOf("command")),
    kind = "exec"
) {
    override fun run(args: JsonObject, ctx: ToolCtx): ToolResult {
        val command = req(args, "command") ?: return fail("shell 缺少 command")
        val shell = (req(args, "shell") ?: "pwsh").lowercase()
        val timeoutSec = int(args, "timeout", 180).coerceIn(1, 1800)
        val cwd = req(args, "cwd")?.let { ctx.resolve(it) } ?: ctx.workspace
        val launcher = launcherFor(shell) ?: return fail("这台机器上找不到 $shell，换一种 shell 或给绝对路径")

        val why = ctx.guard("shell", command, "执行命令（$shell）", command)
        if (why != null) return fail(why)

        /**
         * Windows 上**不能**把命令直接当 argv 塞给 bash/pwsh：
         * ProcessBuilder 会把参数拼成一条命令行字符串交给 CreateProcess，MSYS 的 bash 再按自己的
         * 规则二次解析 —— 结果就是命令里的引号被吃掉（实测 `python -c "print('x'*400)"` 到了 bash
         * 变成 `python -c print(x*400)`，syntax error）。凡是带引号的命令都会踩。
         *
         * 解法：命令写进临时脚本文件，让 shell 去**读文件**（`bash -l file.sh` / `pwsh -File file.ps1`）。
         * 脚本放系统临时目录，不污染用户仓库；跑完删掉。
         */
        val kind = when {
            launcher.first.endsWith("pwsh.exe") || launcher.first.endsWith("powershell.exe") -> "pwsh"
            launcher.first.endsWith("cmd.exe") -> "cmd"
            else -> "sh"
        }
        val script = File.createTempFile(
            "haoai-cmd-",
            if (kind == "pwsh") ".ps1" else if (kind == "cmd") ".bat" else ".sh",
            File(System.getProperty("java.io.tmpdir"))
        )
        script.writeText(if (kind == "pwsh") "$PWSH_UTF8_PREFIX $command" else command)

        return try {
            val pb = ProcessBuilder(listOf(launcher.first) + launcher.second + script.absolutePath)
                .directory(if (cwd.isDirectory) cwd else ctx.workspace)
                .redirectErrorStream(true)
            pb.environment()["PYTHONUTF8"] = "1"
            pb.environment()["PYTHONIOENCODING"] = "utf-8"
            val p = pb.start()
            val out = StringBuilder()
            val reader = Thread {
                BufferedReader(InputStreamReader(p.inputStream, Charsets.UTF_8)).use { br ->
                    while (true) {
                        val l = br.readLine() ?: break
                        out.append(l).append('\n')
                    }
                }
            }
            reader.isDaemon = true
            reader.start()
            if (!p.waitFor(timeoutSec.toLong(), TimeUnit.SECONDS)) {
                p.destroyForcibly()
                reader.join(1000)
                return ToolResult("超时 ${timeoutSec}s 已终止。已输出：\n${TextCap.middle(out.toString(), 4000)}", true)
            }
            reader.join(3000)
            ToolResult("exit=${p.exitValue()}\n---\n$out", card = "terminal")
        } catch (e: Exception) {
            fail("执行失败：${e.message}")
        } finally {
            runCatching { script.delete() }
        }
    }

    private fun launcherFor(shell: String): Pair<String, List<String>>? =
        // 与常驻进程共用同一套"在这台 Windows 上找到 shell"的逻辑（Pty.kt 的 ShellLauncher）：
        // 两处各写一份 bash 候选路径，将来一定有一份改了另一份没改。
        ShellLauncher.forName(shell)

    companion object {
        /** 不切代码页的话，中文输出在 PowerShell 下必乱码（本机实测）。 */
        private const val PWSH_UTF8_PREFIX = "[Console]::OutputEncoding=[System.Text.Encoding]::UTF8;"
    }
}

class TodoTool : Tool(
    "todo", "维护本次任务的步骤清单（整体替换，每次传完整列表）。status ∈ pending|doing|done|cancelled",
    schema("items" to "array", required = arrayOf("items"))
) {
    override fun run(args: JsonObject, ctx: ToolCtx): ToolResult {
        val items = args["items"]?.jsonArray ?: return fail("todo 缺少 items")
        ctx.todos.clear()
        items.forEach { el ->
            val o = el.jsonObject
            val text = o["text"]?.jsonPrimitive?.content ?: o["title"]?.jsonPrimitive?.content ?: "(未命名)"
            val st = o["status"]?.jsonPrimitive?.content ?: "pending"
            ctx.todos += Todo(text, st)
        }
        val mark = mapOf("done" to "[x]", "doing" to "[>]", "cancelled" to "[-]")
        return ToolResult(
            if (ctx.todos.isEmpty()) "(空清单)"
            else ctx.todos.mapIndexed { i, t -> "${i + 1}. ${mark[t.status] ?: "[ ]"} ${t.text}" }.joinToString("\n")
        )
    }
}

class AskUserTool : Tool(
    "ask_user", "向用户提一个需要拿主意的问题。options 给 2-4 个候选（可空）。返回用户回答原文。",
    schema("question" to "string", "options" to "array", required = arrayOf("question"))
) {
    override fun run(args: JsonObject, ctx: ToolCtx): ToolResult {
        val q = req(args, "question") ?: return fail("ask_user 缺少 question")
        val opts = args["options"]?.jsonArray?.mapNotNull { it.jsonPrimitive?.content } ?: emptyList()
        val a = ctx.gate.ask(q, opts)
        return ToolResult(if (a.isBlank()) "(用户没回答，按最合理的默认继续，并在回复里说明你替他做了什么决定)" else a)
    }
}

class WebFetchTool : Tool(
    "web_fetch", "抓一个网页转成纯文本（max_chars 默认 8000，最多 30000）。",
    schema("url" to "string", "max_chars" to "integer", required = arrayOf("url")),
    kind = "net"
) {
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NORMAL).build()

    override fun run(args: JsonObject, ctx: ToolCtx): ToolResult {
        val url = req(args, "url") ?: return fail("web_fetch 缺少 url")
        if (!url.startsWith("http")) return fail("url 必须以 http/https 开头")
        val max = int(args, "max_chars", 8000).coerceIn(200, 30_000)
        return try {
            val r = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(30))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) HaoAI/0.1").GET().build()
            val resp = client.send(r, HttpResponse.BodyHandlers.ofString())
            val raw = resp.body()
            val text = if (raw.contains("<html", true) || raw.contains("<body", true)) htmlToText(raw) else raw
            ToolResult("${url}（HTTP ${resp.statusCode()}）\n\n${TextCap.middle(text, max)}")
        } catch (e: Exception) {
            fail("抓取失败：${e.message}")
        }
    }
}

/** 粗暴够用的 HTML→文本：去 script/style，块级标签换行，压空行，解常见实体。 */
internal fun htmlToText(html: String): String {
    var s = html.replace(Regex("(?is)<(script|style|noscript|svg|head)[^>]*>.*?</\\1>"), " ")
    s = s.replace(Regex("(?is)<br\\s*/?>"), "\n")
    s = s.replace(Regex("(?is)</(p|div|li|tr|h[1-6]|section|article)>"), "\n")
    s = s.replace(Regex("(?is)<[^>]+>"), " ")
    s = s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
        .replace("&gt;", ">").replace("&#39;", "'").replace("&quot;", "\"")
    s = s.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
    return s.replace(Regex("\n{3,}"), "\n\n")
}

/** 跳过的目录名 —— 不跳的话 grep 一次 .git 就能烧掉几十万行。 */
private val SKIP_DIRS = setOf(
    ".git", ".gradle", "build", "out", "node_modules", ".idea", ".kotlin",
    ".haoai-snap", Env.TOOL_OUTPUT_DIR, "__pycache__", ".venv", "dist"
)

/** 手写递归而不是 walkTopDown().onEnter：FileTreeWalk 在序列操作上的类型推断在 Kotlin 2.x 里不稳。 */
internal fun walkFiles(root: File, cap: Int = 20_000): List<File> {
    val out = ArrayList<File>()
    fun rec(d: File, depth: Int) {
        if (out.size >= cap || depth > 12) return
        val kids = d.listFiles() ?: return
        for (k in kids) {
            if (out.size >= cap) return
            if (k.isDirectory) {
                if (k.name in SKIP_DIRS || k.name.startsWith(".")) continue
                rec(k, depth + 1)
            } else if (k.isFile) {
                out += k
            }
        }
    }
    if (root.isFile) out += root else rec(root, 0)
    return out
}

private val REGEX_META = charArrayOf('.', '+', '(', ')', '|', '^', '$', '{', '}', '[', ']', '\\')

// 通配转正则：`**` 跨目录，`*` 不跨斜杠，`?` 单字符。
// （这里刻意不用 KDoc：注释里写 glob 样例会出现 `*/`，Kotlin 的块注释会就地被关掉。）
internal fun globToRegex(glob: String): Regex {
    val sb = StringBuilder("^")
    var i = 0
    while (i < glob.length) {
        val c = glob[i]
        when {
            c == '*' && i + 1 < glob.length && glob[i + 1] == '*' -> {
                sb.append(".*"); i += 2
                if (i < glob.length && glob[i] == '/') i++
                continue
            }
            c == '*' -> sb.append("[^/]*")
            c == '?' -> sb.append("[^/]")
            c in REGEX_META -> sb.append('\\').append(c)
            else -> sb.append(c)
        }
        i++
    }
    return Regex(sb.append('$').toString(), RegexOption.IGNORE_CASE)
}

private fun schema(vararg props: Pair<String, String>, required: Array<String> = emptyArray()): JsonObject =
    buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            props.forEach { (k, t) -> put(k, buildJsonObject { put("type", t) }) }
        })
        if (required.isNotEmpty()) {
            put("required", buildJsonArray { required.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
        }
    }

/** 溢出落文件：与手机端 `EngineToolSpill` 同构。 */
const val SPILL_KEEP_FILES = 40
const val SPILL_MAX_CHARS = 2_000_000

fun spillPreview(content: String, cap: Int, path: String?): String {
    val body = TextCap.middle(content, cap)
    if (content.length <= cap || path == null) return body
    return body + "\n\n［完整输出共 " + content.length + " 字符，已存进工作区文件 `" + path +
        "`。上面只是头尾摘要 —— 要看中间部分就用 read(path=\"" + path +
        "\", offset=起始行, limit=行数) 分段读，不要凭摘要猜内容。］"
}

fun capForStore(content: String, cap: Int, workspace: File, callId: String, spillOn: Boolean): String {
    if (content.length <= cap) return content
    if (!spillOn || content.length > SPILL_MAX_CHARS) return spillPreview(content, cap, null)
    val rel = "${Env.TOOL_OUTPUT_DIR}/${System.currentTimeMillis()}-$callId.txt"
    val written = runCatching {
        val f = File(workspace, rel)
        f.parentFile?.mkdirs()
        f.writeText(content)
        Env.excludeFromGit(workspace, Env.TOOL_OUTPUT_DIR)
        pruneSpillDir(File(workspace, Env.TOOL_OUTPUT_DIR))
    }.isSuccess
    return spillPreview(content, cap, if (written) rel else null)
}

private fun pruneSpillDir(dir: File) {
    if (!dir.isDirectory) return
    val files = dir.listFiles()?.filter { it.isFile } ?: return
    if (files.size <= SPILL_KEEP_FILES) return
    files.sortedBy { it.lastModified() }.take(files.size - SPILL_KEEP_FILES).forEach { runCatching { it.delete() } }
}

object Diff {
    /** 公共前后缀之外的部分算替换块，给 diff 卡用。 */
    fun summary(old: String, new: String): String {
        val a = old.lines()
        val b = new.lines()
        var p = 0
        while (p < a.size && p < b.size && a[p] == b[p]) p++
        var s = 0
        while (s < a.size - p && s < b.size - p && a[a.size - 1 - s] == b[b.size - 1 - s]) s++
        val removed = a.subList(p, a.size - s)
        val added = b.subList(p, b.size - s)
        return "−${removed.size} 行 / +${added.size} 行\n" +
            removed.take(12).joinToString("\n") { "-" + it } +
            (if (removed.size > 12) "\n-…" else "") + "\n" +
            added.take(12).joinToString("\n") { "+" + it } +
            (if (added.size > 12) "\n+…" else "")
    }
}

fun builtinTools(): List<Tool> = listOf(
    ReadTool(), WriteTool(), EditTool(), GlobTool(), GrepTool(),
    ShellTool(), ShellOpenTool(), ShellSendTool(), ShellReadTool(), ShellCloseTool(), ShellListTool(),
    GitTool(), TodoTool(), AskUserTool(), WebFetchTool(), BrowserTool()
)
