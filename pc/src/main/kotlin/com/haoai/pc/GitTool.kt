package com.haoai.pc

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * git 工具。
 *
 * 为什么单独一把而不是让模型用 `shell`：vibe coding 的九成回合都在跟 git 打交道
 * （看改了什么、比对 diff、提交、回滚），而 shell 那条路每次都要重新拼命令、
 * 还要过一遍引号解析。参照实现里 codex/ZCode/opencode 都把 git 当一等公民。
 *
 * 只做**一把**工具 + 子命令，不做八把窄工具：手机端 19 把 `browser_*` 的教训是
 * 工具越多，模型选错的概率越高、schema 的固定开销越大（见缺口清单 S9）。
 *
 * 安全：只读子命令（status/diff/log/show/blame）算 read；会改仓库的算 exec 并走
 * 同一张权限规则表，规则主体是 `git <子命令>` —— 于是 `deny "shell(git push*)"`
 * 这类规则对 git 工具同样生效。force push / reset --hard 落在 alwaysAsk 里，
 * 任何档位都要问。
 */
class GitTool : Tool(
    "git",
    "在工作区仓库里跑 git。sub ∈ status|diff|log|show|blame|add|commit|branch|checkout|restore|stash|rev-parse。" +
        "args 传该子命令的参数串。只读子命令不问；改仓库的按权限规则走。",
    schema("sub" to "string", "args" to "string", "cwd" to "string", required = arrayOf("sub")),
    kind = "exec"
) {

    private val readOnly = setOf("status", "diff", "log", "show", "blame", "rev-parse", "ls-files")

    override suspend fun run(args: JsonObject, ctx: ToolCtx): ToolResult {
        val sub = (req(args, "sub") ?: "").trim()
        if (sub.isEmpty()) return fail("git 缺少 sub（可用：${READABLE_SUBS}）")
        if (sub !in ALLOWED) return fail("不支持的 git 子命令：$sub。可用：$READABLE_SUBS")
        val extra = (req(args, "args") ?: "").trim()
        val cwd = req(args, "cwd")?.let { ctx.resolve(it) } ?: ctx.workspace
        val repo = Engine.gitRoot(cwd)
            ?: return fail("${ctx.rel(cwd)} 不在 git 仓库里。要我先 git init 吗（会用 ask 档问你）？")

        val full = "git $sub${if (extra.isEmpty()) "" else " $extra"}"
        if (sub !in readOnly) {
            val why = ctx.guard("shell", full, "改仓库：$full", "仓库 $repo")
            if (why != null) return fail(why)
        }
        return GitCli.run(File(repo), listOf(sub) + splitArgs(extra)).let {
            ToolResult(it, card = if (sub == "diff") "diff" else "terminal")
        }
    }

    companion object {
        private val ALLOWED = setOf(
            "status", "diff", "log", "show", "blame", "rev-parse", "ls-files",
            "add", "commit", "branch", "checkout", "restore", "stash"
        )
        private const val READABLE_SUBS =
            "status/diff/log/show/blame/rev-parse/ls-files（只读）、add/commit/branch/checkout/restore/stash（会改仓库）"

        /**
         * 极简 shell 式参数切分：按空格切，但尊重引号。
         * 不这么做的话 `commit -m "fix: 修好两件事"` 会被切成五个参数。
         */
        fun splitArgs(s: String): List<String> {
            val out = mutableListOf<String>()
            val cur = StringBuilder()
            var q: Char? = null
            for (c in s) {
                when {
                    q != null -> if (c == q) { q = null } else cur.append(c)
                    c == '"' || c == '\'' -> q = c
                    c == ' ' || c == '\t' -> if (cur.isNotEmpty()) { out += cur.toString(); cur.setLength(0) }
                    else -> cur.append(c)
                }
            }
            if (cur.isNotEmpty()) out += cur.toString()
            return out
        }
    }
}

/** git 的实际执行：与 ShellTool 分开，因为这里不需要 shell —— 直接 exec git.exe。 */
object GitCli {

    /** 测试与 doctor 用它判断这台机器到底有没有 git。 */
    fun available(): String? = findGit()

    /** 只要 stdout 的场合（数提交、拿 hash）。直接 exec，不套 shell。 */
    fun out(repo: File, argv: List<String>): String {
        val exe = findGit() ?: return ""
        return runCatching {
            val p = ProcessBuilder(listOf(exe, "-C", repo.absolutePath) + argv)
                .directory(repo).redirectErrorStream(false).start()
            val s = p.inputStream.bufferedReader(Charsets.UTF_8).readText()
            p.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)
            s.trim()
        }.getOrDefault("")
    }

    /** 只要退出码的场合（建测试仓库）。 */
    fun exit(repo: File, argv: List<String>): Int {
        val exe = findGit() ?: return -1
        return runCatching {
            ProcessBuilder(listOf(exe, "-C", repo.absolutePath) + argv)
                .directory(repo).redirectErrorStream(true).start()
                .let { p ->
                    p.inputStream.readBytes()
                    p.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)
                    p.exitValue()
                }
        }.getOrDefault(-1)
    }

    fun run(repo: File, argv: List<String>, timeoutSec: Int = 120): String {
        val exe = findGit() ?: return "找不到 git 可执行文件（不在 PATH 里）"
        return runCatching {
            val p = ProcessBuilder(listOf(exe, "-C", repo.absolutePath) + argv)
                .directory(repo)
                .redirectErrorStream(true)
                .start()
            val out = StringBuilder()
            val t = Thread {
                p.inputStream.bufferedReader(Charsets.UTF_8).forEachLine { out.append(it).append('\n') }
            }
            t.isDaemon = true
            t.start()
            if (!p.waitFor(timeoutSec.toLong(), java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly()
                return "git ${argv.firstOrNull()} 超时 ${timeoutSec}s"
            }
            t.join(2000)
            val code = p.exitValue()
            val body = out.toString().trimEnd()
            if (code == 0) body.ifBlank { "(无输出，exit=0)" }
            else "exit=$code\n$body"
        }.getOrElse { "git 执行失败：${it.message}" }
    }

    private fun findGit(): String? {
        val path = System.getenv("PATH")?.split(File.pathSeparator) ?: return null
        for (d in path) {
            val f = File(d, if (Env.isWindows) "git.exe" else "git")
            if (f.isFile) return f.absolutePath
        }
        return listOf(
            "C:\\Program Files\\Git\\cmd\\git.exe",
            "E:\\Program Files\\Git\\cmd\\git.exe",
            "E:\\Git\\cmd\\git.exe"
        ).firstOrNull { File(it).isFile }
    }
}
