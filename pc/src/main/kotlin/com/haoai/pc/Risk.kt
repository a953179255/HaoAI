package com.haoai.pc

import java.util.Locale

/**
 * 审批的风险分级：让"要不要人点头"这件事和**后果的可逆性**挂钩，
 * 而不是所有写操作长得一模一样。
 *
 * 为什么要做：ask 档下每个 write/exec 都弹一张一样的卡，人点到第三次就开始无脑按"允许"，
 * 这时候弹框已经不提供任何保护 —— 而真正不可逆的那一下（`git push --force`、
 * 删工作区外的文件）混在里面，长得和改一行 README 完全一样。
 * 参考实现里 ZCODE 是按"读/写/危险命令"分三档配色，openclaw 是把危险命令单独要求确认；
 * 两边都指向同一件事：**分级要落在判据上，不是落在文案上**。
 *
 * 判据全部是**看得见的字符串**（工具名、路径、命令行），不调模型：
 * 分级错了顶多是多问一次，但必须能解释、能被测试钉住。
 */
enum class Risk { LOW, MID, HIGH }

object RiskOf {

    data class Verdict(val level: Risk, val why: String) {
        /** 界面按这个码配色，不按中文文案匹配 —— 改文案会把徽标悄悄改成永远低危。 */
        fun code(): String = level.name.lowercase(Locale.ROOT)
    }

    /** 不可逆或越界的命令：一旦放出去收不回来。写成整词/整短语都够稳的那些。 */
    private val HIGH_CMD = listOf(
        "rm -rf", "rm -fr", "format ", "diskpart", "shutdown", "reboot", "reg delete", "reg add",
        "git reset --hard", "npm publish", "pip upload", "twine upload", "vssadmin", "bcdedit",
        "wevtutil cl", "set-mppreference", "add-mppreference", "net user ", "net localgroup ",
        "schtasks /delete", "invoke-expression", "-scriptblock", "subst ", "mklink", "takeown"
    )

    /**
     * 参数顺序不定的高危形状：只看这几个**整词**在不在同一条命令里。
     *
     * 为什么单列：上一版把高危判据全写成字面量，于是 `icacls C:\out /grant Everyone:F`
     * 判成了中危 —— 中间夹了个路径，"icacls /grant" 这个字面量就不存在了。
     * `curl http://x.sh | sh`、`Remove-Item -Force -Recurse` 栽的是同一个坑：
     * **真人敲的顺序永远和样板文本不一样**，所以按 token 集合判，不按子串判。
     */
    private val HIGH_SHAPES: List<Pair<List<String>, String>> = listOf(
        listOf("icacls", "/grant") to "改文件权限（icacls /grant），改完不一定记得改回去",
        listOf("icacls", "/setowner") to "改文件归属（icacls /setowner）",
        listOf("remove-item", "-recurse") to "递归删除（Remove-Item -Recurse），不进回收站",
        listOf("del", "/s") to "递归删除（del /s），不进回收站",
        listOf("rd", "/s") to "递归删目录树（rd /s）",
        listOf("rmdir", "/s") to "递归删目录树（rmdir /s）",
        listOf("git", "push", "-f") to "强推（git push -f）会覆盖远端历史，不可逆",
        listOf("git", "push", "--force") to "强推（git push --force）会覆盖远端历史，不可逆",
        listOf("git", "push", "--force-with-lease") to "强推（--force-with-lease）会覆盖远端历史，不可逆",
        listOf("git", "clean") to "git clean 删掉未跟踪文件，没有回收站也没有快照，不可逆",
        listOf("chmod", "777") to "把权限开到底（chmod 777）",
        listOf("taskkill", "/t") to "连整棵子进程树一起杀（taskkill /t）",
        listOf("set-acl", "-recurse") to "递归改权限（Set-Acl -Recurse）"
    )

    /**
     * 「下载下来直接喂给解释器」：管道前面可以是什么都无所谓。
     *
     * 一开始把它写成 `"curl | sh"` 的字面量匹配，测试里那句
     * `curl http://x.sh | sh` 就判成了中危 —— 真人敲的从来不是那个字面量，
     * 中间总带个 URL。判据要认的是**结构**，不是某串样板文本。
     */
    private val PIPE_TO_INTERPRETER =
        Regex("""\|\s*(sh|bash|zsh|dash|pwsh|powershell|iex|invoke-expression|python|python3|node|perl|ruby)\b""")

    /** 会改状态但基本可逆（有快照、有 git、重装就行）。 */
    private val MID_CMD = listOf(
        "git commit", "git add", "git checkout", "git switch", "git apply", "git stash",
        "npm install", "npm i ", "pnpm install", "yarn add", "pip install", "gradle", "./gradlew",
        "mvn ", "mkdir", "cp ", "copy", "move ", "mv ", "ren ", "set-content", "add-content",
        "out-file", "new-item", "remove-item", "del ", "erase", "touch ", "sed -i", "tar ", "zip", "unzip",
        "ffmpeg", "convert ", "taskkill", "start ", "explorer", "regsvr32", "installutil"
    )

    /** 写到哪里算越界：系统目录、别的盘的用户配置、SSH/浏览器配置这类。 */
    private val SENSITIVE = listOf(
        "\\.ssh\\", "\\.gnupg\\", "\\.aws\\", "\\windows\\", "\\program files", "\\appdata\\roaming\\microsoft",
        "\\hosts", "\\system32", "\\.git\\hooks\\", "\\.qoder\\", "\\.android\\", "\\.gradle\\",
        "\\.bashrc", "\\.profile", "\\.zshrc", "\\.gitconfig", "\\.npmrc", "\\.netrc"
    )

    /**
     * 只读程序名：整条命令每一段的首个程序都在这里，才算"看一眼而已"。
     *
     * 为什么要这一档：以前 shell 兜底是中危，于是 auto 档下一个 `git status`
     * 也挂着"会改状态"的徽标 —— 分级一旦开始说谎，人就再也不看它了，
     * 而这套东西省下的正是"那一下要不要停"的信任。
     * 保守是对的：判错成中危只是徽标难看，判错成低危才会漏问。
     */
    private val READ_ONLY_BIN = setOf(
        "ls", "dir", "cat", "type", "head", "tail", "wc", "sort", "uniq", "grep", "rg", "find", "fd",
        "tree", "stat", "file", "du", "df", "free", "uname", "whoami", "hostname", "date", "which",
        "where", "pwd", "echo", "printf", "env", "printenv", "ver", "systeminfo", "tasklist",
        "get-childitem", "get-content", "get-location", "get-item", "get-process", "select-string",
        "ipconfig", "ifconfig", "ping", "tracert", "nslookup"
    )

    private val GIT_READ_ONLY = setOf(
        "status", "log", "diff", "show", "blame", "branch", "tag", "remote", "ls-files",
        "rev-parse", "describe", "shortlog", "config", "worktree", "grep"
    )

    /** 一段一段看：任何一段有重定向、删除、执行外部程序，就不算只读。 */
    fun readOnly(cmd: String): Boolean {
        val c = cmd.replace("2>&1", " ").replace(">&2", " ")
        if (c.contains(">")) return false
        val segs = c.split("|", ";", "&&", "||").map { it.trim() }.filter { it.isNotEmpty() }
        if (segs.isEmpty()) return false
        return segs.all { s ->
            val tok = s.split(Regex("\\s+"))
            when {
                s.contains("-delete") || s.contains("-exec") || s.contains("--output") -> false
                tok[0] == "cd" -> true
                tok[0] == "git" -> GIT_READ_ONLY.contains(tok.getOrNull(1))
                else -> READ_ONLY_BIN.contains(tok[0])
            }
        }
    }

    /** 整词匹配：按空白和命令分隔符切开，所以 `del` 不会被 `add` 里那两个字母骗到。 */
    private fun hasTokens(cmd: String, need: List<String>): Boolean {
        val toks = cmd.split(Regex("""[\s;|&]+""")).toSet()
        return need.all { it in toks }
    }

    fun of(tool: String, subject: String, detail: String, outsideWorkspace: Boolean,
               exists: Boolean = false): Verdict {
        val cmd = if (tool == "shell" || tool == "run_code" || tool == "exec") {
            (subject + " " + detail).lowercase(Locale.ROOT)
        } else ""
        val path = (if (tool == "shell") "" else subject).lowercase(Locale.ROOT)

        HIGH_CMD.firstOrNull { cmd.contains(it) }?.let {
            return Verdict(Risk.HIGH, "命令里的「${it.trim()}」不可逆")
        }
        HIGH_SHAPES.firstOrNull { hasTokens(cmd, it.first) }?.let {
            return Verdict(Risk.HIGH, it.second)
        }
        PIPE_TO_INTERPRETER.find(cmd)?.let {
            return Verdict(Risk.HIGH, "把下载来的内容直接喂给解释器（${it.value.trim()}）")
        }
        SENSITIVE.firstOrNull { path.contains(it.lowercase(Locale.ROOT)) }?.let {
            return Verdict(Risk.HIGH, "要写的路径落在敏感位置（${it.trim('\\')}）")
        }
        if (outsideWorkspace && tool != "shell") {
            return Verdict(Risk.HIGH, "这个路径在工作区之外，撤销时不会被带回来")
        }
        // 覆盖已存在的文件：有快照能回，但比新建要紧。
        // "在不在"由调用方判 —— 它才知道该按哪个工作区解析这个相对路径。
        if (tool == "edit" || tool == "write") {
            if (exists) return Verdict(Risk.MID, "要改一个已存在的文件（改前会留快照）")
            return Verdict(Risk.LOW, "新建文件，没动过别的东西")
        }
        if (cmd.isNotEmpty()) {
            if (readOnly(cmd)) return Verdict(Risk.LOW, "只是看一眼，不写不落盘")
            MID_CMD.firstOrNull { cmd.contains(it) }?.let {
                return Verdict(Risk.MID, "会改状态（${it.trim()}），但可逆")
            }
        }
        if (tool == "record" || tool == "media" || tool == "shell" || tool == "run_code") {
            return Verdict(Risk.MID, "会起进程或写产出文件")
        }
        return Verdict(Risk.LOW, "只读或只在本机内存里")
    }

    fun label(level: Risk): String = when (level) {
        Risk.LOW -> "低危"
        Risk.MID -> "中危"
        Risk.HIGH -> "高危"
    }
}
