package com.haoai.pc

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.io.OutputStreamWriter
import java.io.Writer
import java.util.Collections
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * S3 持久交互进程：`shell_open / shell_send / shell_read / shell_close / shell_list`。
 *
 * 为什么 PC 端必须有它：`shell` 工具是"跑一条命令拿一次输出"，遇到 `ssh`、`mysql>`、
 * `python -i`、`gradle` 的确认提示、`npm init` 这类**要边看边答**的场景就完全没法用。
 * 手机端不需要（它交互的对象是 App 界面），PC 端是刚需 —— codex 的 `unified_exec` +
 * `write_stdin`、dsh 的 `terminal_open/send/read/signal/close/list` 都是为这个存在的。
 *
 * 实现选择：**管道而不是伪终端**。真 PTY 在 Windows 上要引 ConPTY/JNI，而绝大多数交互式
 * CLI 用管道 stdin 就能喂（差别只在没有彩色 TTY 与行编辑）。先把"进程活着、能喂、能捞"
 * 做扎实，ConPTY 等真遇到需要 TTY 的程序再上。
 *
 * 生命周期：记 `lastUsed`，超过 [ProcRegistry.IDLE_TTL_MS] 没被碰过就回收 ——
 * 无人值守跑一晚上不能攒下几百个僵尸 shell。
 */
object ProcRegistry {

    const val IDLE_TTL_MS = 20L * 60 * 1000
    const val MAX_LIVE = 8

    class Live(
        val id: String,
        val label: String,
        val display: String,
        val proc: Process,
        val writer: Writer,
        val cwd: File
    ) {
        @Volatile
        var lastUsed = System.currentTimeMillis()

        @Volatile
        var closed = false

        /**
         * 输出的环形缓冲：每行带一个递增序号。
         *
         * 为什么不再是"一条谁读走就没了"的队列：面板（人看）与 `shell_read`（模型读）
         * 是**两个消费者**。共用一个队列的话，面板一刷新就把模型该看的行偷走了，
         * 表现是"模型说它没看到输出"而界面上明明有。改成带序号的缓冲 + 各自一个游标。
         */
        private val buf = java.util.ArrayDeque<Pair<Long, String>>()
        private var n = 0L

        /** 模型那一路读到哪了（面板的游标存在浏览器里，不占这里）。 */
        @Volatile
        var toolCur = 0L

        fun push(line: String): Unit = synchronized(buf) {
            n += 1
            buf.addLast(n to line)
            while (buf.size > TAIL) buf.pollFirst()
        }

        /** 序号大于 [after] 的行，最多 [max] 条；回 (新游标, 行)。 */
        fun since(after: Long, max: Int = 400): Pair<Long, List<String>> = synchronized(buf) {
            val take = buf.filter { it.first > after }.take(max)
            (if (take.isEmpty()) after else take.last().first) to take.map { it.second }
        }

        fun touch() { lastUsed = System.currentTimeMillis() }

        fun alive(): Boolean = !closed && proc.isAlive

        companion object {
            /** 每个进程最多留多少行：面板要能往回滚一点，又不能一晚上吃掉几百 MB。 */
            const val TAIL = 2000
        }
    }

    private val live = Collections.synchronizedList(mutableListOf<Live>())
    private val seq = AtomicLong()

    fun list(): List<Live> = live.toList()

    fun get(id: String): Live? = live.firstOrNull { it.id == id || it.label == id }

    /** 起一个常驻 shell。[initial] 非空就立刻喂进去（等价于用户打开终端后敲的第一行）。 */
    fun open(label: String, shell: String, cwd: File, initial: String): Result<Live> = runCatching {
        reapIdle()
        if (live.size >= MAX_LIVE) error("已有 ${live.size} 个常驻进程，先 shell_close 一个")
        val launcher = ShellLauncher.persistentForName(shell)
            ?: error("这台机器上找不到 $shell（可用：bash / pwsh / cmd）")
        val p = ProcessBuilder(listOf(launcher.first) + launcher.second)
            .directory(if (cwd.isDirectory) cwd else File(System.getProperty("user.dir")))
            .redirectErrorStream(true)
            .start()
        val id = "p" + seq.incrementAndGet()
        val rec = Live(
            id = id,
            label = label.ifBlank { id },
            display = "$shell @ ${cwd.name}",
            proc = p,
            writer = OutputStreamWriter(p.outputStream, Charsets.UTF_8),
            cwd = cwd
        )
        val t = Thread {
            runCatching {
                p.inputStream.bufferedReader(Charsets.UTF_8).forEachLine { line -> rec.push(line) }
            }
            rec.closed = true
        }
        t.isDaemon = true
        t.name = "haoai-proc-$id"
        t.start()
        live += rec
        if (initial.isNotBlank()) send(id, initial, true)
        rec
    }

    /** 往 stdin 写。[enter] 决定要不要补换行 —— 多数 CLI 在等一整行。 */
    fun send(id: String, text: String, enter: Boolean): Result<Unit> = runCatching {
        val l = get(id) ?: error("没有这个进程：$id（先用 shell_list 看现有的）")
        if (!l.alive()) error("进程 $id 已退出")
        l.writer.write(if (enter) text + "\n" else text)
        l.writer.flush()
        l.touch()
    }

    /**
     * 捞新输出。先等 [waitMs] 让它安静下来再取，因为交互式命令的回显是陆续到的：
     * 取太早会拿到半截提示符，模型就误以为程序在等它输入，于是重复喂一次。
     */
    fun read(id: String, waitMs: Int, maxChars: Int): Result<String> = runCatching {
        val l = get(id) ?: error("没有这个进程：$id")
        val deadline = System.currentTimeMillis() + waitMs.coerceIn(0, 20_000)
        val got = StringBuilder()
        var cur = l.toolCur
        while (true) {
            val (next, lines) = l.since(cur)
            if (lines.isNotEmpty()) {
                cur = next
                lines.forEach { got.append(it).append('\n') }
                if (got.length >= maxChars) break
                continue
            }
            if (System.currentTimeMillis() >= deadline) break
            Thread.sleep(40)
        }
        l.toolCur = cur
        l.touch()
        val tail = if (!l.alive()) {
            "\n（进程已退出，exit=${runCatching { l.proc.exitValue() }.getOrDefault(-1)}）"
        } else ""
        got.toString().trimEnd() + tail
    }

    fun close(id: String): Result<String> = runCatching {
        val l = get(id) ?: return@runCatching "本来就没有这个进程：$id"
        live.remove(l)
        l.closed = true
        runCatching { l.writer.close() }
        if (l.proc.isAlive) {
            l.proc.destroy()
            if (!l.proc.waitFor(3, TimeUnit.SECONDS)) l.proc.destroyForcibly()
        }
        "已关闭 ${l.id}（${l.display}）"
    }

    fun reapIdle(): Int {
        val now = System.currentTimeMillis()
        val dead = live.filter { it.lastUsed + IDLE_TTL_MS < now || !it.alive() }
        dead.forEach { runCatching { close(it.id) } }
        return dead.size
    }

    fun closeAll() {
        list().forEach { runCatching { close(it.id) } }
    }
}

/** shell 启动器。常驻与一次性两条路共用同一套"在这台 Windows 上找到它"的逻辑。 */
object ShellLauncher {

    /** 一次性执行：参数后面要跟**脚本文件路径**（见 Tools.kt 里那条 Windows 引号坑）。 */
    fun forName(shell: String): Pair<String, List<String>>? = when (shell.lowercase()) {
        "bash", "sh" -> bashPath()?.let { it to listOf("-l") }
        "pwsh", "powershell" -> pwshPath()?.let { it to listOf("-NoProfile", "-NonInteractive", "-File") }
        "cmd" -> cmdPath()?.let { it to listOf("/c") }
        else -> null
    }

    /** 常驻交互：不给脚本，进程从 stdin 读命令，变量与当前目录在多次发送之间保留。 */
    fun persistentForName(shell: String): Pair<String, List<String>>? = when (shell.lowercase()) {
        "bash", "sh" -> bashPath()?.let { it to listOf("-l") }
        "pwsh", "powershell" -> pwshPath()?.let { it to listOf("-NoProfile", "-NonInteractive", "-Command", "-") }
        "cmd" -> cmdPath()?.let { it to emptyList<String>() }
        else -> null
    }

    fun isShellBinary(path: String): Boolean =
        path.endsWith("bash.exe", true) || path.endsWith("sh.exe", true)

    private fun bashPath(): String? = listOfNotNull(
        System.getenv("ProgramFiles")?.let { "$it\\Git\\bin\\bash.exe" },
        System.getenv("ProgramFiles")?.let { "$it\\Git\\usr\\bin\\bash.exe" },
        "E:\\Git\\bin\\bash.exe",
        "C:\\Program Files\\Git\\bin\\bash.exe"
    ).firstOrNull { File(it).isFile }

    private fun pwshPath(): String? =
        which("pwsh") ?: System.getenv("SystemRoot")?.let {
            File(it, "System32\\WindowsPowerShell\\v1.0\\powershell.exe")
        }?.takeIf { it.isFile }?.absolutePath

    private fun cmdPath(): String? =
        System.getenv("SystemRoot")?.let { File(it, "System32\\cmd.exe") }?.takeIf { it.isFile }?.absolutePath

    private fun which(cmd: String): String? {
        val path = System.getenv("PATH")?.split(File.pathSeparator) ?: return null
        val exts = if (Env.isWindows) listOf(".exe", ".cmd") else listOf("")
        for (d in path) for (e in exts) {
            val f = File(d, cmd + e)
            if (f.isFile) return f.absolutePath
        }
        return null
    }
}

private val noParams: JsonObject = buildJsonObject {
    put("type", "object")
    put("properties", buildJsonObject { })
}

private fun strProps(vararg names: String): JsonObject = buildJsonObject {
    names.forEach { put(it, buildJsonObject { put("type", "string") }) }
}

private fun required(vararg names: String) = buildJsonArray {
    names.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
}

class ShellOpenTool : Tool(
    "shell_open",
    "起一个**常驻**的交互式进程（bash/pwsh/cmd），之后用 shell_send 喂输入、shell_read 捞输出。" +
        "适合 ssh、mysql>、python -i、需要回答确认提示的构建命令。command 非空则启动后立刻执行。",
    buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("label", buildJsonObject { put("type", "string") })
            put("shell", buildJsonObject { put("type", "string") })
            put("command", buildJsonObject { put("type", "string") })
            put("cwd", buildJsonObject { put("type", "string") })
        })
    },
    kind = "exec"
) {
    override fun run(args: JsonObject, ctx: ToolCtx): ToolResult {
        val shell = req(args, "shell") ?: "bash"
        val label = req(args, "label") ?: ""
        val command = req(args, "command") ?: ""
        val cwd = req(args, "cwd")?.let { ctx.resolve(it) } ?: ctx.workspace
        val why = ctx.guard("shell", command.ifBlank { "shell $shell" }, "起常驻进程 $label（$shell）", "$shell 交互会话")
        if (why != null) return fail(why)
        return ProcRegistry.open(label, shell, cwd, command).fold(
            onSuccess = { l ->
                ToolResult(
                    // id 单独占一行：调用方（和测试）用 "已启动 (\S+)" 取 id，
                    // 后面紧跟中文括号会让 \S+ 一路吞进 label。
                    "已启动 ${l.id}\nlabel=${l.label} shell=$shell cwd=${ctx.rel(l.cwd)}\n" +
                        "shell_send(id=\"${l.id}\", text=…) 喂输入，shell_read(id=\"${l.id}\") 捞输出，" +
                        "用完 shell_close(id=\"${l.id}\")。空闲 20 分钟自动回收。"
                )
            },
            onFailure = { fail("起进程失败：${it.message}") }
        )
    }
}

class ShellSendTool : Tool(
    "shell_send", "往常驻进程的 stdin 写一行输入（enter=false 可发不带换行的原始字符）。",
    buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("id", buildJsonObject { put("type", "string") })
            put("text", buildJsonObject { put("type", "string") })
            put("enter", buildJsonObject { put("type", "boolean") })
        })
        put("required", required("id"))
    },
    kind = "exec"
) {
    override fun run(args: JsonObject, ctx: ToolCtx): ToolResult {
        val id = req(args, "id") ?: return fail("shell_send 缺少 id")
        val text = req(args, "text") ?: ""
        val enter = args["enter"]?.jsonPrimitive?.contentOrNull != "false"
        val why = ctx.guard("shell", text, "向常驻进程 $id 输入", text)
        if (why != null) return fail(why)
        return ProcRegistry.send(id, text, enter).fold(
            onSuccess = { ToolResult("已发送 ${text.take(80)}${if (enter) " ↵" else ""}") },
            onFailure = { fail(it.message ?: "发送失败") }
        )
    }
}

class ShellReadTool : Tool(
    "shell_read", "取常驻进程新产生的输出。wait_ms 是「等多久算这一轮说完」，默认 1200。",
    buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("id", buildJsonObject { put("type", "string") })
            put("wait_ms", buildJsonObject { put("type", "integer") })
            put("max_chars", buildJsonObject { put("type", "integer") })
        })
        put("required", required("id"))
    }
) {
    override fun run(args: JsonObject, ctx: ToolCtx): ToolResult {
        val id = req(args, "id") ?: return fail("shell_read 缺少 id")
        val wait = int(args, "wait_ms", 1200)
        val max = int(args, "max_chars", 8000)
        return ProcRegistry.read(id, wait, max).fold(
            onSuccess = { ToolResult(if (it.isBlank()) "(这段时间没有新输出)" else it, card = "terminal") },
            onFailure = { fail(it.message ?: "读取失败") }
        )
    }
}

class ShellCloseTool : Tool(
    "shell_close", "关掉一个常驻进程。shell_list 可看现有的；空闲 20 分钟会自动回收。",
    buildJsonObject {
        put("type", "object")
        put("properties", strProps("id"))
        put("required", required("id"))
    },
    kind = "exec"
) {
    override fun run(args: JsonObject, ctx: ToolCtx): ToolResult {
        val id = req(args, "id") ?: return fail("shell_close 缺少 id")
        return ProcRegistry.close(id).fold({ ToolResult(it) }, { fail(it.message ?: "关闭失败") })
    }
}

class ShellListTool : Tool("shell_list", "列出当前常驻进程：id、在跑什么、多久没被碰、是否还活着。", noParams) {
    override fun run(args: JsonObject, ctx: ToolCtx): ToolResult {
        ProcRegistry.reapIdle()
        val l = ProcRegistry.list()
        if (l.isEmpty()) return ToolResult("(没有常驻进程)")
        return ToolResult(
            l.joinToString("\n") {
                val idle = (System.currentTimeMillis() - it.lastUsed) / 1000
                "${it.id}  ${if (it.alive()) "运行中" else "已退出"}  空闲 ${idle}s  ${it.label}  ${it.display}"
            }
        )
    }
}
