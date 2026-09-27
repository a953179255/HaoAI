package com.haoai.pc

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * Git 面板的后端：给**人**用的 status / 暂存 / diff / 提交。
 *
 * 为什么单开一层而不是让模型用 `git` 工具：vibe coding 的日常是"它改完，我扫一眼 diff，
 * 挑两个文件提交，写一句人话的说明"。这一串走模型既慢又要反复审批，而**人点按钮这件事
 * 本身就是审批**，不该再弹一次框问用户要不要提交他刚按下的提交。
 *
 * 三条硬边界：
 * 一，只认这条会话工作区里的仓库（[repoFor]），面板上没有 push / reset --hard 这类动词；
 * 二，路径参数一律先过 [safePath]：不能为空、不能以 - 开头（否则就成了 git 的选项）、
 *     不能跑出仓库；
 * 三，读 git 一律要**机器格式**（--porcelain -z），不解析人类格式 ——
 *     中文文件名在默认输出里会被转义，按行切必错。
 */
object GitPanel {

    /** NUL 分隔符。写成 0.toChar() 而不是转义字面量：补丁工具会吃掉反斜杠。 */
    private val NUL: String = 0.toChar().toString()

    /** 同上：拼给界面看的换行。 */
    private val NL: String = 10.toChar().toString()

    data class Entry(val path: String, val index: String, val work: String, val untracked: Boolean) {
        /** 已暂存 = 第一列有内容且不是未跟踪（未跟踪时第一列是问号）。 */
        val staged: Boolean get() = !untracked && index != " "
    }

    /** 仓库根；不在仓库里回 null。界面上要说清"这里不是仓库"，不能摆一个空面板。 */
    fun repoFor(workspace: File): File? = Engine.gitRoot(workspace)?.let { File(it) }

    /**
     * `git status --porcelain=v1 -z`。
     *
     * `-z` 用 NUL 分隔且**不转义文件名**；代价是重命名会多带一个 NUL 段（旧路径），
     * 所以遇到 R/C 要多吞一段，否则下一条会被当成畸形行整条丢掉。
     */
    fun status(repo: File): List<Entry> {
        // 不能用 GitCli.out()：它对结果 trim()，而 porcelain 每行的**第一个字符就是状态列**
        // （未暂存的修改是 " M 路径"）—— trim 会把首行那个空格吃掉，
        // 于是第一条永远被解析成"已暂存 + 路径少一个字符"。测试就是这么抓出来的。
        val raw = exec(repo, listOf("status", "--porcelain=v1", "-z", "--untracked-files=normal")).second
        if (raw.isBlank()) return emptyList()
        val parts = raw.split(NUL).filter { it.isNotEmpty() }
        val out = ArrayList<Entry>()
        var i = 0
        while (i < parts.size) {
            val line = parts[i]
            if (line.length < 4) { i++; continue }
            val xy = line.substring(0, 2)
            val path = line.substring(3)
            out += Entry(path, xy.substring(0, 1), xy.substring(1, 2), xy == "??")
            i++
            // 重命名/复制：紧随其后的那一段是旧路径，只当作"已经吃掉"，不单独成行
            if ((xy == "R " || xy == "RM" || xy == "C " || xy == "CM") && i < parts.size) i++
        }
        return out
    }

    /** 分支名。没有 HEAD（新建还没提交的仓库）时明说，而不是显示一个错的。 */
    fun branch(repo: File): String {
        val b = GitCli.out(repo, listOf("rev-parse", "--abbrev-ref", "HEAD"))
        return when {
            b.isBlank() -> "(尚无提交)"
            b == "HEAD" -> "(游离 HEAD)"
            else -> b
        }
    }

    /** 最近一条提交（界面上"上次提交"那一行）；没有就回空串。 */
    fun lastCommit(repo: File): String =
        GitCli.out(repo, listOf("log", "-1", "--format=%h %s"))

    /**
     * 路径能不能用。**这是面板唯一的注入口**，所以宁可严：
     * 空、以 - 开头、绝对路径、跑出仓库的 `..`，一律拒。
     */
    fun safePath(repo: File, p: String): String? {
        var t = p.trim().replace('\\', '/').trimStart('/')
        while (t.startsWith("./")) t = t.substring(2)
        if (t.isEmpty() || t.startsWith("-")) return null
        if (File(t).isAbsolute) return null
        val f = runCatching { File(repo, t).canonicalFile }.getOrNull() ?: return null
        val root = runCatching { repo.canonicalFile }.getOrNull() ?: return null
        if (!f.path.startsWith(root.path + File.separator) && f.path != root.path) return null
        return t
    }

    // ---- 下面是给面板的四个动作，全部返回 JSON 串（前端只管画） ----

    fun statusJson(repo: File): String {
        val es = status(repo)
        return buildJsonObject {
            put("ok", true)
            put("isRepo", true)
            put("repo", Env.abs(repo))
            put("branch", branch(repo))
            put("last", lastCommit(repo))
            put("clean", es.isEmpty())
            put("count", es.size)
            put("stagedCount", es.count { it.staged })
            put("entries", kotlinx.serialization.json.buildJsonArray {
                es.forEach { e ->
                    add(buildJsonObject {
                        put("p", e.path)
                        put("i", e.index)
                        put("w", e.work)
                        put("staged", e.staged)
                        put("untracked", e.untracked)
                    })
                }
            })
        }.toString()
    }

    /** 暂存 / 取消暂存。[paths] 是界面上勾选的那些。 */
    fun stageJson(repo: File, paths: List<String>, unstage: Boolean): String {
        val r = stageResult(repo, paths, unstage)
        return if (r.first) okJson(r.second) else errJson(r.second)
    }

    /** 成不成功与那句要说的人话。给 commitJson 复用，省得去扫 JSON 里的 true/false。 */
    private fun stageResult(repo: File, paths: List<String>, unstage: Boolean): Pair<Boolean, String> {
        if (paths.isEmpty()) return false to "没有勾选任何文件"
        val ok = ArrayList<String>()
        for (p in paths) {
            ok += safePath(repo, p)
                ?: return false to "这个路径不能用：$p（不能为空、不能以 - 开头、不能跑出仓库）"
        }
        val argv = if (unstage) listOf("restore", "--staged", "--") + ok else listOf("add", "--") + ok
        val r = exec(repo, argv)
        return if (r.first == 0) true to "已${if (unstage) "取消暂存" else "暂存"} ${ok.size} 个文件"
        else false to "git ${argv[0]} 没成：${TextCap.head(r.second, 600)}"
    }

    /**
     * 提交。勾选了就先把它们暂存（一步到位，省得用户点两次才发现没暂存），
     * 没勾选就提交已在暂存区里的 —— 与命令行习惯一致。
     */
    fun commitJson(repo: File, message: String, paths: List<String>): String {
        val msg = message.trim()
        if (msg.isEmpty()) return errJson("提交说明是空的。写一句人话，比如「修好登录页按钮重叠」")
        if (paths.isNotEmpty()) {
            val s = stageResult(repo, paths, false)
            if (!s.first) return errJson(s.second)
        }
        if (status(repo).none { it.staged })
            return errJson("没有可提交的内容：先勾选文件点「暂存勾选」，或直接点「提交勾选」")
        val r = exec(repo, listOf("commit", "-m", msg))
        if (r.first != 0) return errJson("git commit 没成：${TextCap.head(r.second, 800)}")
        val head = lastCommit(repo)
        val left = status(repo).size
        return buildJsonObject {
            put("ok", true)
            put("committed", head)
            put("message", if (left == 0) "已提交：$head" else "已提交：$head；工作区还剩 $left 项未提交")
            put("left", left)
        }.toString()
    }

    /** 单文件 diff。未跟踪的新文件 git 没有基线可比，就直说并把内容给出来。 */
    fun diffJson(repo: File, path: String, staged: Boolean): String {
        val p = safePath(repo, path) ?: return errJson("这个路径不能看：$path")
        val e = status(repo).firstOrNull { it.path == p }
        if (e != null && e.untracked) {
            val head = runCatching { File(repo, p).readText(Charsets.UTF_8).lines().take(200) }
                .getOrDefault(emptyList())
            return buildJsonObject {
                put("ok", true)
                put("p", p)
                put("text", "（未跟踪的新文件：git 里还没有它的基线可比，下面是现在盘上的前 200 行）" +
                    NL + head.joinToString(NL))
            }.toString()
        }
        val argv = (if (staged) listOf("diff", "--cached") else listOf("diff")) + listOf("--", p)
        val r = exec(repo, argv)
        if (r.first != 0) return errJson("git diff 没成：${TextCap.head(r.second, 400)}")
        val blank = if (staged) "（暂存区里这个文件没有差异）" else "（没有差异 —— 改动多半还没暂存，切到「已暂存」再看看）"
        return buildJsonObject {
            put("ok", true)
            put("p", p)
            put("text", r.second.ifBlank { blank })
        }.toString()
    }

    /** 带退出码的执行：判成败要看 exit，不能靠扫输出里的 fatal 字样。 */
    private fun exec(repo: File, argv: List<String>): Pair<Int, String> {
        val exe = GitCli.available() ?: return -1 to "找不到 git 可执行文件（不在 PATH 里）"
        return runCatching {
            val p = ProcessBuilder(listOf(exe, "-C", repo.absolutePath) + argv)
                .directory(repo).redirectErrorStream(true).start()
            val body = p.inputStream.bufferedReader(Charsets.UTF_8).readText()
            if (!p.waitFor(120, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly()
                return -1 to "git ${argv.first()} 超时 120s"
            }
            p.exitValue() to body
        }.getOrElse { -1 to "git 执行失败：${it.message}" }
    }

    private fun okJson(m: String) = buildJsonObject {
        put("ok", true); put("message", m)
    }.toString()

    private fun errJson(m: String) = buildJsonObject {
        put("ok", false); put("error", m)
    }.toString()
}
