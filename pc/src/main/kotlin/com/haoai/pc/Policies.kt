package com.haoai.pc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * S2 权限规则表：把"每次都问"变成"规则命中就不问"。
 *
 * 为什么 PC 端比手机端更需要这一层：手机上 agent 顶多点几下屏幕，PC 上它能 `rm -rf`。
 * 没有一张用户可写的规则表，`ask` 档位就只能是"前二十次很新鲜、之后开始乱点"，
 * 而 `auto` 档位又是全裸 —— 中间必须有一层，否则无人值守在 PC 上根本不成立。
 *
 * 语义抄的两家，各自解决一个问题：
 * - **有序 + 后覆盖前**（opencode `permission/index.ts` 的 `findLast`）：规则是追加式的，
 *   "先允许 git，再单独禁掉 git push"这种表达必须能写出来；
 * - **命令前缀归约**（opencode `permission/arity.ts` 约 180 条词典）：
 *   `git checkout main` 要能命中 `git checkout` 这条规则，否则规则表只能按整条命令写死，
 *   等于没有规则表。
 *
 * 两条红线：
 * 1. [ALWAYS_ASK] 里的前缀**任何档位都绕不过**，包括 auto —— 这是给"删库、改系统、装东西"
 *    留的最后一道人工闸（方案的 Phase 3 里明确写了 yolo 也绕不过）。
 * 2. 拒绝时把**原因和命中的规则**回给模型，而不是只回一个 deny —— 否则模型会换个写法重试。
 */

enum class Decision { ALLOW, ASK, DENY }

/** 一条规则：`decision` 作用在 `tool(pattern)` 上。 */
data class Rule(val tool: String, val pattern: String, val decision: Decision) {
    fun render() = "$tool($pattern) $decision"

    companion object {
        /**
         * 解析 `shell(git push*) deny`；不带括号时整串当工具名（= 该工具全部放行/拒绝）。
         *
         * 从**右边**摘决策词，因为 pattern 里可以有空格（`git push*`），
         * 按空白切三段就把一条规则切成了三条。
         */
        fun parse(s: String): Rule? {
            val t = s.trim()
            if (t.isEmpty()) return null
            val last = t.substringAfterLast(' ').uppercase()
            val decision = runCatching { Decision.valueOf(last) }.getOrNull() ?: return null
            val head = t.substringBeforeLast(' ').trim()
            if (head.isEmpty()) return null
            val open = head.indexOf('(')
            return if (open < 0 || !head.endsWith(")")) {
                Rule(head, "*", decision)
            } else {
                Rule(head.substring(0, open).trim(), head.substring(open + 1, head.length - 1), decision)
            }
        }
    }
}

/** 判定结果：给模型看的理由要能说清是哪条规则生效的。 */
data class Verdict(val decision: Decision, val why: String)

class PolicyStore(private val file: File = Env.rulesFile) {

    /** workspace 绝对路径（正斜杠）→ 该工作区的规则表。 */
    private var byWorkspace: MutableMap<String, MutableList<Rule>> = mutableMapOf()

    init {
        reload()
    }

    fun reload() {
        byWorkspace = runCatching {
            if (!file.isFile) return@runCatching mutableMapOf()
            val root = Json.parseToJsonElement(file.readText()).jsonObject
            root.mapValuesTo(mutableMapOf()) { (ws, el) ->
                el.jsonArray.mapNotNull { r ->
                    val o = r.jsonObject
                    Rule(
                        o.str("tool") ?: return@mapNotNull null,
                        o.str("pattern") ?: "*",
                        runCatching { Decision.valueOf(o.str("decision") ?: "ASK") }.getOrDefault(Decision.ASK)
                    )
                }.toMutableList()
            }
        }.getOrElse { mutableMapOf() }
    }

    private fun persist() {
        runCatching {
            file.parentFile?.mkdirs()
            val body = byWorkspace.entries.joinToString(",\n") { (ws, rules) ->
                val rs = rules.joinToString(",") { r ->
                    """{"tool":${jsonStr(r.tool)},"pattern":${jsonStr(r.pattern)},"decision":"${r.decision}"}"""
                }
                "${jsonStr(ws)}:[$rs]"
            }
            file.writeText("{\n$body\n}\n")
        }.onFailure { Env.log("policy", "规则落盘失败：${it.message}") }
    }

    fun rules(workspace: File): List<Rule> = byWorkspace[key(workspace)].orEmpty()

    fun add(workspace: File, rule: Rule) {
        val list = byWorkspace.getOrPut(key(workspace)) { mutableListOf() }
        list.removeAll { it.tool == rule.tool && it.pattern == rule.pattern }
        list += rule
        persist()
    }

    fun remove(workspace: File, tool: String, pattern: String): Boolean {
        val list = byWorkspace[key(workspace)] ?: return false
        val n = list.size
        list.removeAll { it.tool == tool && it.pattern == pattern }
        if (list.size != n) persist()
        return list.size != n
    }

    fun clear(workspace: File) {
        if (byWorkspace.remove(key(workspace)) != null) persist()
    }

    /**
     * 判定一次工具调用。[subject] 是"这次动作的对象"：shell 用归约后的命令前缀，
     * 写类工具用相对路径。返回 null 表示规则表没意见，交给档位（plan/ask/auto）决定。
     */
    fun decide(workspace: File, tool: String, subject: String): Verdict? {
        val rules = rules(workspace)
        if (rules.isEmpty() && tool != "shell") return null

        // 后写的规则覆盖先写的（opencode findLast）：要找的是"**最后一条命中的**"，
        // 不是"最后一条该工具的规则再看它命不命中" —— 后者会让前面的宽规则被后面的窄规则
        // 无条件顶掉，`shell(git*) allow` + `shell(git push*) deny` 就表达不出来。
        val hit = rules.lastOrNull { (it.tool == tool || it.tool == "*") && it.matchesSubject(subject) }

        if (tool == "shell") {
            val prefix = commandPrefix(subject)
            // 既比归约后的前缀，也比整条命令：`git push` 与 `git push --force` 危险等级不同，
            // 只比前缀会把后者漏掉（而 force push 恰恰是最需要拦的那一个）。
            val hay = listOf(prefix, subject.trim().lowercase())
            val dangerous = ALWAYS_ASK.any { a ->
                hay.any { h -> h == a || h.startsWith("$a ") || h.startsWith("$a-") }
            }
            if (dangerous) {
                return Verdict(
                    Decision.ASK,
                    "「$prefix」在必须人工确认的清单里（任何档位都绕不过，包括 auto）"
                )
            }
        }
        return hit?.let {
            Verdict(it.decision, "命中规则 ${it.tool}(${it.pattern}) -> ${it.decision}")
        }
    }

    private fun Rule.matchesSubject(subject: String): Boolean {
        val s = if (tool == "shell") commandPrefix(subject) else subject
        val rx = globToRegex(pattern)
        return rx.matches(s) || (pattern.endsWith("*") && s.startsWith(pattern.dropLast(1)))
    }

    private fun key(workspace: File): String =
        runCatching { workspace.canonicalPath }.getOrElse { workspace.absolutePath }.replace('\\', '/')

    companion object {
        /**
         * 命令前缀归约：`git checkout main` → `git checkout`。
         *
         * 词典只放**真会改变系统或不可逆**的那些（对应 opencode arity.ts 的子集）。
         * 没命中的命令退回"前两个 token"，够用且不会误伤：`ls -la /x` → `ls`。
         */
        private val ARITY = listOf(
            "git push", "git reset", "git clean", "git checkout", "git revert", "git rebase", "git rm",
            "git tag", "git filter-branch", "git config",
            "rm", "rmdir", "del", "erase", "move", "mv", "cp", "chmod", "chown", "takeown",
            "taskkill", "kill", "pkill", "shutdown", "restart-computer", "stop-computer",
            "reg", "regedit", "sc", "netsh", "net user", "net localgroup", "schtasks",
            "format", "diskpart", "vssadmin", "bcdedit", "cipher",
            "npm install", "npm uninstall", "npm publish", "pnpm add", "yarn add",
            "pip install", "pip uninstall", "uv pip install", "conda install", "conda remove",
            "dotnet tool", "winget install", "winget uninstall", "choco install", "choco uninstall",
            "gradle clean", "gradle publish", "cargo publish", "docker rm", "docker system prune",
            "docker rmi", "kubectl delete", "aws", "az", "gcloud", "ssh", "scp",
            "curl", "wget", "invoke-expression", "iex", "set-executionpolicy", "new-item",
            "remove-item", "set-itemproperty", "start-process", "start-job"
        ).sortedByDescending { it.length }

        /** 必须人工确认的命令前缀，auto 也绕不过。 */
        val ALWAYS_ASK = setOf(
            "rm -rf", "rm", "del", "erase", "remove-item", "rmdir", "format", "diskpart",
            "git push --force", "git reset --hard", "git clean", "git filter-branch",
            "shutdown", "restart-computer", "stop-computer", "takeown", "vssadmin",
            "reg", "regedit", "schtasks", "set-executionpolicy", "net user", "netsh",
            "cipher", "bcdedit", "docker system prune", "kubectl delete", "winget uninstall"
        )

        fun commandPrefix(cmd: String): String {
            // Windows 的命令与别名大小写不敏感（Remove-Item / remove-item / RM 都算一条），
            // 归约前先统一小写，否则规则表形同虚设。
            val norm = cmd.trim().replace('\t', ' ').lowercase()
            if (norm.isEmpty()) return ""
            val noEnv = norm.removePrefix("sudo ").removePrefix("doas ")
            val tokens = noEnv.split(Regex("\\s+"))
            val two = tokens.take(2).joinToString(" ")
            val one = tokens.firstOrNull() ?: ""
            return ARITY.firstOrNull { two.startsWith("$it") || one == it } ?: one
        }

        private fun jsonStr(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }
}

/** 进程内单例：规则表很小，没必要每次工具调用都读盘。 */
object Policies {
    @Volatile
    private var store: PolicyStore? = null

    fun get(): PolicyStore = store ?: synchronized(this) {
        store ?: PolicyStore().also { store = it }
    }

    /** 测试用：换掉规则文件（或直接重置）。 */
    fun reset(file: File? = null) {
        store = if (file != null) PolicyStore(file) else null
    }
}
