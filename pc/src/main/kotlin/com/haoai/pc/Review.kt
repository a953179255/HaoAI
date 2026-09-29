package com.haoai.pc

import java.io.File

/**
 * 本地 code review：`haoai review <base>` —— 只在本机把 diff 读成三段人话
 * （改了啥 / 风险 / 建议），**永不外发**。
 *
 * 为什么是确定性规则而不是先上模型：§3.2 的结论——自动 review 在没做真模型验收之前
 * 质量不可控，机器人在别人 PR 上刷错意见比不审更糟；而本地这一半的价值是"提交前
 * 多一双眼睛看确定性的东西"（密钥、大段删除、发布面、测试变少），这类判据可解释、
 * 可测、不会两次给出不同意见。模型辅助的语义 review 等验收网补完再加。
 *
 * 外发（发 PR comment）永远要人点：本工具没有网络出口，连 Provider 都不构造。
 *
 * 判据与输出分开：[analyze] 是纯函数（喂 numstat + patch 文本），git 的 I/O 在 [collect]——
 * 于是单测不需要仓库也能钉住每一条规则。
 */
object Review {

    /** 一条风险。level ∈ 高/中/低（与 Risk.kt 同一档语言，但规则完全独立）。 */
    data class Finding(val level: String, val where: String, val why: String)

    /** 一个变更文件（numstat 一行）。binary 时 added/deleted 为 null。 */
    data class FileStat(val path: String, val added: Int?, val deleted: Int?, val binary: Boolean)

    data class Report(
        val base: String,
        val files: List<FileStat>,
        val added: Int,
        val deleted: Int,
        val findings: List<Finding>,
        val suggestions: List<String>
    ) {
        val high: Int get() = findings.count { it.level == "高" }
        val mid: Int get() = findings.count { it.level == "中" }
        val low: Int get() = findings.count { it.level == "低" }
    }

    // ---- 规则（纯判据，逐条都有测试钉住）----

    /** 新增行里的疑似密钥：宁可误报也不放过——密钥进仓库是要轮换的那种错。 */
    private val SECRET_PATTERNS = listOf(
        Regex("sk-[A-Za-z0-9_-]{16,}"),
        Regex("AKIA[0-9A-Z]{16}"),
        Regex("-----BEGIN [A-Z ]*PRIVATE KEY"),
        Regex("""(?i)(api[_-]?key|secret|passwd|password|token)["']?\s*[:=]\s*["'][^"'\s]{8,}["']""")
    )

    /** 发布/权限面：改这些文件的后果超出"改坏一个函数"。 */
    private val RELEASE_SURFACE = Regex(
        """(?i)(\.github/workflows/|\.jks$|\.keystore$|(^|/)gradle\.properties$|(^|/)AndroidManifest\.xml$)"""
    )

    private fun isTestPath(p: String) =
        p.contains("/test/") || p.contains("/androidTest/") || Regex("""Test\.kt$""").containsMatchIn(p)

    private fun isSourcePath(p: String) = p.endsWith(".kt") && !isTestPath(p)

    /**
     * git 侧取材。base 不存在（typo / 没这个分支）返回 null —— 由调用方报错退出，
     * 不把 git 的报错文本当成 diff 分析（那会产出一份"改了 xxx 行"的幻觉报告）。
     *
     * 未跟踪的新文件（`??`）也要进来：`git diff <base>` 天生看不见它们，而"新加的文件"
     * 恰恰是最该被审的那半（新文件里藏密钥是这类扫描器的经典漏网）。做法是把每个
     * untracked 文件**合成**成 numstat 行 + patch 分段喂给 [analyze]——分析端保持纯函数。
     * 上限：patch 只取前 4000 行/文件（判据够用），numstat 行数按全文件算（统计要准）。
     */
    fun collect(repo: File, base: String): Pair<String, String>? {
        if (GitCli.exit(repo, listOf("rev-parse", "--verify", "--quiet", base)) != 0) return null
        val numstat = StringBuilder(GitCli.run(repo, listOf("diff", "--numstat", base)))
        val patch = StringBuilder(GitCli.run(repo, listOf("diff", base)))
        if (numstat.startsWith("git ") || patch.startsWith("git ")) return null
        if (numstat.isNotEmpty() && numstat.last() != '\n') numstat.append('\n')
        if (patch.isNotEmpty() && patch.last() != '\n') patch.append('\n')
        for (rel in GitCli.out(repo, listOf("ls-files", "--others", "--exclude-standard")).lines().filter { it.isNotBlank() }) {
            val f = File(repo, rel)
            if (!f.isFile) continue
            val head = runCatching { f.inputStream().use { it.readNBytes(4096) } }.getOrNull() ?: continue
            if (head.contains(0.toByte())) {
                numstat.append("-\t-\t").append(rel).append('\n')
                patch.append("diff --git a/").append(rel).append(" b/").append(rel).append('\n')
                    .append("Binary files differ\n")
                continue
            }
            // 流式读：行数按全文件算（统计要准），patch 只攒前 4000 行（判据够用、内存有界）
            var lines = 0
            val body = StringBuilder()
            val ok = runCatching {
                f.bufferedReader(Charsets.UTF_8).use { r ->
                    while (true) {
                        val line = r.readLine() ?: break
                        lines++
                        if (lines <= 4000) body.append('+').append(line).append('\n')
                    }
                }
                true
            }.getOrDefault(false)
            if (!ok) continue
            numstat.append(lines).append("\t0\t").append(rel).append('\n')
            patch.append("diff --git a/").append(rel).append(" b/").append(rel).append('\n')
                .append("+++ b/").append(rel).append('\n').append(body)
        }
        return numstat.toString() to patch.toString()
    }

    /** 纯函数：numstat + patch → 报告。 */
    fun analyze(base: String, numstat: String, patch: String): Report {
        val files = parseNumstat(numstat)
        val findings = mutableListOf<Finding>()
        val suggestions = mutableListOf<String>()

        // 1) 新增行里的疑似密钥（按 patch 分段归属到文件，别把一处密钥算到每个文件头上）
        val addedByFile = linkedMapOf<String, MutableList<String>>()
        var curPath = ""
        for (ln in patch.lines()) {
            if (ln.startsWith("diff --git ")) {
                curPath = ln.substringAfterLast(" b/", "")
                continue
            }
            if (curPath.isNotEmpty() && ln.startsWith("+") && !ln.startsWith("+++")) {
                addedByFile.getOrPut(curPath) { mutableListOf() } += ln
            }
        }
        var sawSecret = false
        for ((path, lines) in addedByFile) {
            if (lines.any { line -> SECRET_PATTERNS.any { p -> p.containsMatchIn(line) } }) {
                sawSecret = true
                findings += Finding("高", path, "新增行里有疑似密钥/口令（sk-…、AKIA…、PRIVATE KEY 或赋值型 secret）")
            }
        }
        if (sawSecret) {
            suggestions += "疑似密钥已进 diff：先撤销并轮换，再确认没进过 git 历史（进了要改写历史，不是删一个 commit 的事）"
        }

        // 2) 大段删除：回滚成本高，值得人多看一眼
        for (f in files) {
            if ((f.deleted ?: 0) >= 200) {
                findings += Finding("中", f.path, "删了 ${f.deleted} 行——大段删除要么是重构搬走、要么是把判据删没了")
            }
        }

        // 3) 发布/权限面
        for (f in files) {
            if (RELEASE_SURFACE.containsMatchIn(f.path)) {
                findings += Finding("中", f.path, "发布/权限面文件（workflow、签名、gradle 属性、清单）——改坏它不是回滚代码那么简单")
            }
        }

        // 4) 测试文件整个被删（numstat: 只有删除）
        val testsGone = files.filter { isTestPath(it.path) && (it.added ?: 0) == 0 && (it.deleted ?: 0) > 0 && !it.binary }
        for (f in testsGone) {
            findings += Finding("中", f.path, "测试文件被整份删除——确认是重构合并，而不是把不通过的判据删掉了")
        }
        if (testsGone.isNotEmpty()) {
            suggestions += "测试变少了：对照删除理由确认判据还在（删掉的测试要能在别处找到等价物）"
        }

        // 5) 二进制变更
        for (f in files.filter { it.binary }) {
            findings += Finding("低", f.path, "二进制变更——仓库里放二进制会让 clone 与 diff 永远看不清它改了什么")
        }

        // 6) 源码动了但测试一条没动（低，是建议不是罪）
        if (files.any { isSourcePath(it.path) } && files.none { isTestPath(it.path) }) {
            suggestions += "这批改动没有配套测试：新行为补一条 Kotlin 测试（判据看结果，不看返回 200）"
        }

        // 恒有的收尾建议
        suggestions += "提交前跑 gradle test 与 node tools/ui-check.js；本工具只在本地读 diff，不外发、不替你提交"

        val added = files.sumOf { it.added ?: 0 }
        val deleted = files.sumOf { it.deleted ?: 0 }
        return Report(base, files, added, deleted, findings, suggestions.distinct())
    }

    /** `git diff --numstat` 一行一个：`12\t3\tpath`；二进制是 `- \t- \tpath`。 */
    fun parseNumstat(text: String): List<FileStat> =
        text.lines().mapNotNull { ln ->
            val parts = ln.split('\t')
            if (parts.size < 3) return@mapNotNull null
            val a = parts[0]; val d = parts[1]; val path = parts.drop(2).joinToString("\t")
            if (a == "-" || d == "-") FileStat(path, null, null, binary = true)
            else FileStat(path, a.toIntOrNull() ?: 0, d.toIntOrNull() ?: 0, binary = false)
        }

    /** 人读输出。三段：改了啥 / 风险 / 建议。 */
    fun render(r: Report): String = buildString {
        appendLine("本地 review：base=${r.base}（只读 diff，不外发）")
        appendLine()
        appendLine("改了啥（${r.files.size} 个文件，+${r.added} / -${r.deleted}）")
        if (r.files.isEmpty()) appendLine("  （没有变更——base 之后一条 diff 都没有）")
        r.files.take(50).forEach { f ->
            val stat = if (f.binary) "二进制" else "+${f.added} / -${f.deleted}"
            appendLine("  ${f.path}  $stat")
        }
        if (r.files.size > 50) appendLine("  …还有 ${r.files.size - 50} 个文件")
        appendLine()
        appendLine("风险（高 ${r.high} / 中 ${r.mid} / 低 ${r.low}）")
        if (r.findings.isEmpty()) appendLine("  （确定性规则下没有要拦的）")
        r.findings.forEach { appendLine("  [${it.level}] ${it.where}：${it.why}") }
        appendLine()
        appendLine("建议")
        r.suggestions.forEachIndexed { i, s -> appendLine("  ${i + 1}. $s") }
        appendLine()
        append(if (r.high > 0) "结论：有 ${r.high} 条高危，处理后再提交。" else "结论：没有高危项。")
    }

    /** `--json` 输出：结构化给脚本用（判据：字段名与 ApiDocTest 一个风格——先有名字再有人用）。 */
    fun toJson(r: Report): String = buildString {
        fun esc(s: String) = buildString {
            for (c in s) when (c) {
                '\\' -> append("\\\\"); '"' -> append("\\\""); '\n' -> append("\\n")
                '\r' -> append("\\r"); '\t' -> append("\\t")
                else -> if (c.code < 0x20) append("\\u%04x".format(c.code)) else append(c)
            }
        }
        append("""{"base":"${esc(r.base)}","files":${r.files.size},"added":${r.added},"deleted":${r.deleted}""")
        append(""","findings":[""")
        r.findings.forEachIndexed { i, f ->
            if (i > 0) append(',')
            append("""{"level":"${esc(f.level)}","where":"${esc(f.where)}","why":"${esc(f.why)}"}""")
        }
        append("]," + "\"high\":${r.high},\"mid\":${r.mid},\"low\":${r.low},\"suggestions\":[")
        r.suggestions.forEachIndexed { i, s -> if (i > 0) append(','); append("\"${esc(s)}\"") }
        append("]}")
    }
}
