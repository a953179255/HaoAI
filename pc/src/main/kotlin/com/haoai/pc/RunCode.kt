package com.haoai.pc

import kotlinx.serialization.json.JsonObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * `run_code`：把一段代码写成临时文件、交给解释器跑，收回 stdout+stderr。
 *
 * 为什么值得单独一把而不是让模型用 `shell`：vibe coding 里"我想看看这段逻辑到底输出什么"
 * 一天几十次，走 shell 每次都要重新拼引号（Windows 上带引号的命令必被二次解析吃掉，
 * 见 ShellTool 那条注释），而代码里全是引号。直接给 argv + 临时脚本，两边都干净。
 *
 * 输出文件走**运行目录**：`.haoai-output/runs/<id>/`。跑之前不知道脚本会写什么，
 * 但写进这个目录的东西一定都是这次产出的 —— 不用扫工作区，也不会把用户自己的文件误认成产物。
 */
class RunCodeTool : Tool(
    "run_code",
    "跑一段代码并返回输出。lang=python（默认）或 node。代码写进临时文件再执行，" +
        "所以多行、引号、中文都没问题。要产出文件就写到 result 里告诉你的那个运行目录 " +
        "（.haoai-output/runs/…），那里面新出来的图片会直接给你看、音视频会在界面播放。" +
        "timeout 秒，默认 120，最多 900。",
    schema("code" to "string", "lang" to "string", "timeout" to "integer", "cwd" to "string",
        required = arrayOf("code")),
    kind = "exec"
) {

    override fun run(args: JsonObject, ctx: ToolCtx): ToolResult {
        val code = req(args, "code")?.takeIf { it.isNotBlank() }
            ?: return fail("run_code 缺少 code（要跑的那段代码本身）")
        val lang = (req(args, "lang") ?: "python").trim().lowercase()
        val spec = SPECS[lang]
            ?: return fail("run_code 只认 ${SPECS.keys.joinToString("/")}，现在是「$lang」")
        val exeEnv = lang.uppercase() + "_EXE"
        val exe = find(spec.exes)
            ?: return fail("这台机器上找不到 $lang。装一个（或把它的目录加进 PATH）；" +
                "不想动 PATH 就把解释器绝对路径写进环境变量 $exeEnv。")
        val timeout = int(args, "timeout", 120).coerceIn(5, 900)
        val cwd = req(args, "cwd")?.let { ctx.resolve(it) } ?: ctx.workspace
        val runDir = File(ctx.workspace, "${Env.TOOL_OUTPUT_DIR}/runs/" + System.currentTimeMillis())
        val script = File(runDir, "main" + spec.suffix)
        // 先过闸再落盘：被拒的那一次不该在用户工作区里留下目录和脚本
        val why = ctx.guard("shell", code.lines().first().take(120), "跑一段代码（$lang）",
            { "$lang 跑 ${ctx.rel(script)}（工作目录 ${ctx.rel(cwd)}）" })
        if (why != null) return fail(why)
        runCatching { runDir.mkdirs(); script.writeText(code, Charsets.UTF_8) }.getOrElse {
            return fail("临时脚本写不下去：${it.message}")
        }

        val r = exec(exe, listOf(script.absolutePath), cwd, timeout)
        Env.excludeFromGit(ctx.workspace, Env.TOOL_OUTPUT_DIR)
        val produced = runCatching {
            runDir.listFiles()?.filter { it.isFile && it != script }?.map { it.absolutePath } ?: emptyList()
        }.getOrDefault(emptyList())
        val pics = produced.filter { Images.mime(File(it)) != null }
        val av = produced.filter { MediaMime.sniff(File(it)) != null }
        val head = if (r.timedOut) "超时 ${timeout}s 已终止" else "exit=${r.exit}"
        val listTxt = if (produced.isEmpty()) "" else
            "\n产出（${produced.size} 个，都在 ${ctx.rel(runDir)}）：" +
                produced.joinToString(", ") { ctx.rel(File(it)) }
        return ToolResult(
            content = head + " · " + lang + listTxt + "\n---\n" + TextCap.middle(r.log, 12_000),
            images = pics,
            media = av,
            card = "terminal",
            error = r.exit != 0 && !r.timedOut
        )
    }

    private data class Spec(val exes: List<String>, val suffix: String)

    /** 找解释器：`<LANG>_EXE` 显式指定 → PATH → 几个常见安装位置（Windows 上 python 常常不在 PATH）。 */
    private fun find(names: List<String>): String? {
        names.forEach { n ->
            val p = System.getenv(n.uppercase() + "_EXE")
            if (!p.isNullOrBlank() && File(p).isFile) return p
        }
        val dirs = mutableListOf<String>()
        System.getenv("PATH")?.split(File.pathSeparator)?.filter { it.isNotBlank() }?.let { dirs += it }
        for (d in dirs) for (n in names) {
            val f = File(d, n + if (Env.isWindows) ".exe" else "")
            if (f.isFile) return f.absolutePath
        }
        // 常见安装位置（Windows 上 python 经常不在 PATH 里）
        val extra = listOfNotNull(
            System.getenv("LOCALAPPDATA")?.let { "$it/Programs/Python" },
            System.getenv("ProgramFiles")?.let { "$it/nodejs" },
            "C:/Python312", "C:/Python311"
        )
        for (d in extra) {
            val dir = File(d)
            if (!dir.isDirectory) continue
            val kids = runCatching { dir.listFiles()?.toList() }.getOrNull() ?: emptyList<File>()
            for (c in listOf(dir) + kids) {
                for (n in names) {
                    val f = File(c, n + if (Env.isWindows) ".exe" else "")
                    if (f.isFile) return f.absolutePath
                }
            }
        }
        return null
    }

    private data class R(val exit: Int, val log: String, val timedOut: Boolean)

    private fun exec(exe: String, argv: List<String>, cwd: File, timeoutSec: Int): R {
        val pb = ProcessBuilder(listOf(exe) + argv)
            .directory(if (cwd.isDirectory) cwd else File(System.getProperty("user.dir")))
            .redirectErrorStream(true)
        /*
         * 不强制 UTF-8 的话，Windows 上 python 往管道写的是**当前代码页**（本机 cp936），
         * 我们按 UTF-8 读回来就是一串乱码 —— 三条用例第一次全栽在这上面。
         * 与 ShellTool 同一套解法。
         */
        pb.environment()["PYTHONUTF8"] = "1"
        pb.environment()["PYTHONIOENCODING"] = "utf-8"
        val p = try {
            pb.start()
        } catch (e: Exception) {
            return R(-1, "启动失败：${e.message}", false)
        }
        runCatching { p.outputStream.close() }
        val log = StringBuilder()
        val t = Thread {
            runCatching {
                p.inputStream.bufferedReader(Charsets.UTF_8).forEachLine {
                    synchronized(log) { log.append(it).append(10.toChar()) }
                }
            }
        }
        t.isDaemon = true
        t.start()
        if (!p.waitFor(timeoutSec.toLong(), TimeUnit.SECONDS)) {
            p.destroyForcibly()
            t.join(1000)
            return R(-1, log.toString(), true)
        }
        t.join(2000)
        return R(p.exitValue(), log.toString(), false)
    }

    companion object {
        private val SPECS = mapOf(
            "python" to Spec(listOf("python", "python3", "py"), ".py"),
            "node" to Spec(listOf("node"), ".js")
        )
    }
}
