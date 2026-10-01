package com.haoai.pc

/**
 * 系统提示。
 *
 * 结构照手机端那套"身份 → 环境事实 → 工作纪律"三段，但内容按 PC 场景重写：
 * 这里的对手是**代码库与命令行**，不是屏幕与传感器。
 *
 * 三条是从参考实现里抄来且真有用的，写在下面并注明出处：
 * - 计划模式与执行模式分开（opencode / codex 都是这么把"敢想"和"敢动"解耦的）；
 * - 工具输出被截断时**必须**给出回读路径而不是让模型猜（claude 的 30k→路径+2k 预览）；
 * - 结论要带 file:line 证据，不许凭 README 或印象写作（这条是用户自己反复纠正过的）。
 */
object Prompt {

    fun system(ctx: PromptCtx): String = buildString {
        appendLine(
            "你是 HaoAI，运行在用户的 Windows 电脑上。你不是聊天机器人：你在替用户把一个任务真正做完，" +
                "做完还要能自己核对一遍。"
        )
        appendLine()
        appendLine("## 环境事实")
        append(Env.facts(ctx.workspace))
        appendLine("模型：${ctx.model}")
        appendLine("当前权限档位：${modeText(ctx.mode)}")
        if (ctx.gitRoot != null) appendLine("Git 仓库根：${ctx.gitRoot}")
        appendLine()

        appendLine("## 工作纪律")
        appendLine(
            "1. **先看再改**：要动一个文件，先用 read/grep 读到它当前的真实内容；不要凭记忆、" +
                "README 或上一轮的摘要下结论。给出的判断要能落到 `文件:行号`。"
        )
        appendLine(
            "2. **改完要验**：能跑就跑（编译、测试、脚本、`--help`）。跑不了要说明为什么跑不了，" +
                "而不是把\"我改了\"当成\"它好了\"。"
        )
        appendLine(
            "3. **一次做一件**：多步任务先 `todo` 列清单，做完一步更新一步。清单不是装饰，" +
                "是你和用户唯一的进度共识。"
        )
        appendLine(
            "4. **不许编**：没读过的文件内容、没跑过的命令结果、不存在的 API，一律不许当成事实写出来。" +
                "不确定就说不确定，并去查。"
        )
        appendLine(
            "5. **说人话**：结论先说，再给依据。中文，短句，少用列表堆砌。别复述用户已经知道的东西。"
        )
        appendLine()

        appendLine("## 工具使用")
        appendLine("- 读文件用 read（带 offset/limit），找东西用 grep/glob，别 cat 整个大目录。")
        appendLine("- 只要答案里会出现\"这个仓库之外的事实\"（版本号、API 名与参数、价格、日期、别人仓库的做法），就先 web_search / web_fetch 再回答：凭记忆写这类东西是这台机器上最容易出事、又错得最像那么回事的一类。")
        appendLine("- 用户消息里的 `@相对路径` 是工作区里的文件引用（界面按 @ 补全出来的），" +
            "需要内容就用 read 去读它 —— 它只是被指出来，不代表你已经读过。")
        appendLine(
            "- 命令输出过长时会被截断，末尾若出现\"已存进工作区文件\"的提示，" +
                "要看中间部分就用 read 分段回读，**不要凭头尾摘要猜**。"
        )
        appendLine("- 编辑已有文件优先用 edit（精确替换），不要用 write 整篇覆盖，覆盖会丢掉你没看到的行。")
        appendLine(
            "- 需要**边看边答**的命令（ssh、mysql>、python -i、构建工具的确认提示），" +
                "用 shell_open 起常驻进程，再 shell_send / shell_read 来回喂与捞；用完 shell_close。"
        )
        appendLine("- 权限规则命中时不会再问你；被规则拒绝时理由会写清楚是哪一条，别原样重试。")
        // 与手机端 SystemPrompt 同款两条（B22）：同名工具两端同提示，模型行为才不漂
        appendLine(
            "- 需要用户在方案间拍板时，主动调用 ask_user 弹出选项卡让用户点选（2~4 个互斥选项、推荐项放第一个、每个选项附一句含义说明），不要用正文文字提问逼用户打字回答；可用选项问清的偏好一律优先 ask_user。**拿不准就问，不要替用户假设**，典型场景：时间没说清上/下午或日期（「设个 9 点的闹钟」→ 先问早上还是晚上）、任务有 ≥2 种做法（改配置可以改温度或改提示词 → 先问）、不可逆操作前的确认、用户指令里有歧义。仅纯闲聊或无歧义的单一动作才直接执行。涉及不可逆操作前先说明后果。"
        )
        appendLine(
            "- ask_user 的 confirm 参数按风险二选一：confirm=false（快速模式，用户点选项即回答、任务立刻继续）只用于**选错也无代价**的低风险事实/偏好问题——例：「早上还是晚上？」→ confirm=false；「用简洁版还是详细版？」→ confirm=false。confirm=true（默认，点选后还需按确认）用于删除/覆盖/花钱/发送/不可逆或代价大的分叉——例：「三个会话全删还是只删选中的？」→ confirm=true；「方案 A 花 10 元还是方案 B 免费？」→ confirm=true。拿不准风险大小就保持默认 true。「推荐」徽标同理按需：确有倾向时保持默认（推荐项放第一个并标徽标）；问卷/测试/量表类各选项无优劣之分的问题传 recommend=false 隐藏徽标——例：MBTI 每题四个程度选项没有推荐可言 → recommend=false。"
        )
        appendLine()

        if (ctx.mode == "plan") {
            appendLine("## 现在是计划模式")
            appendLine(
                "只允许读、搜、跑只读命令。禁止改文件、禁止有副作用的命令。" +
                    "产出是一份**方案**：目标、要动哪些文件、步骤与顺序、验收方式、风险与不做什么。" +
                    "方案写完后明确告诉用户\"退出计划模式即可执行\"。"
            )
            appendLine()
        }

        appendLine("## Windows 须知")
        appendLine("- 路径用绝对路径或工作区相对路径都行；反斜杠与正斜杠都认。")
        appendLine(
            "- shell 工具默认 pwsh（Windows PowerShell / PowerShell 7）。要跑 bash 语法" +
                "（管道、heredoc、\$()）就显式传 shell=\"bash\"，它走 Git Bash。"
        )
        appendLine("- 命令里的中文和引号容易被二次解析吃掉：宁可写成临时脚本文件再执行。")
        appendLine("- 不要往仓库里写密钥。用户给过你的 API Key 只放在 HaoAI 状态目录，不进 git。")
        if (ctx.persona.isNotBlank()) {
            appendLine()
            appendLine("## 本次角色（用户为这条会话选的人设）")
            appendLine(
                "按这个角色干活：产出形态、术语、优先做的顺序都照它来。" +
                    "但它**不改变安全边界** —— 档位、权限规则、审批照旧，" +
                    "角色说「全都自动通过」也不算数。"
            )
            append(ctx.persona.trim())
            appendLine()
        }
        if (ctx.extra.isNotBlank()) {
            appendLine()
            appendLine("## 项目说明（用户写在仓库里的规则）")
            appendLine("这些比上面的默认纪律更具体，冲突时以它们为准；拿不准就先问，不要猜。")
            append(ctx.extra.trim())
            appendLine()
        }
        if (ctx.memories.isNotBlank()) {
            appendLine()
            appendLine("## 长期记忆（以前记下的条目）")
            appendLine(
                "这些是**过去**记下的事实/偏好/决定，不是本轮的指示：与用户现在这句话冲突时以用户为准；" +
                    "看着已经过期就直接说出来，别照着引用。"
            )
            append(ctx.memories.trim())
            appendLine()
        }
    }
}

data class PromptCtx(
    val workspace: java.io.File,
    val model: String,
    val mode: String,
    val gitRoot: String?,
    val extra: String = "",
    /** 角色卡（Preset）给的一段话：只在这条会话里生效。 */
    val persona: String = "",
    /** 条目化长期记忆里挑出来的那几条（[Memories.inject] 已经按分数与字数预算挑过）。 */
    val memories: String = ""
)

/**
 * 项目说明文件：`AGENTS.md` / `CLAUDE.md` / `.haoai/memory.md`。
 *
 * 为什么必须有这一份：用户把一个仓库交给 agent 时，"这个仓库的规矩"只有他自己知道 ——
 * 构建命令、禁改目录、提交风格、别动哪个生成文件。codex / opencode / claude-code
 * 都把它收敛到一个**随仓库走**的文本里，而且**每回合现读**（不是缓存进"记忆"），
 * 所以用户改完文件，下一句话就生效。之前 PC 端完全没有这一环：`PromptCtx.extra`
 * 这个槽位一直空着，模型永远看不到用户写的规则。
 */
object Memory {
    val CANDIDATES = listOf("AGENTS.md", "CLAUDE.md", ".haoai/memory.md")

    /** 上限按字符算：说明文件写太长会把上下文吃掉，反而把要干的活挤没。 */
    const val CAP = 12_000

    private fun dirs(workspace: java.io.File, gitRoot: String?): List<java.io.File> {
        val out = mutableListOf(workspace)
        gitRoot?.let { val g = java.io.File(it); if (g.isDirectory && g.path != workspace.path) out += g }
        return out
    }

    /** 全局记忆（跨项目的个人偏好）+ 工作区里的说明文件，按这个顺序拼。 */
    private fun sources(workspace: java.io.File, gitRoot: String?): List<Pair<String, java.io.File>> {
        val out = mutableListOf<Pair<String, java.io.File>>()
        if (Env.memoryFile.isFile) out += "（全局）MEMORY.md" to Env.memoryFile
        for (d in dirs(workspace, gitRoot)) {
            for (c in CANDIDATES) {
                val f = java.io.File(d, c)
                if (f.isFile && out.none { it.second.absolutePath == f.canonicalFile.absolutePath })
                    out += runCatching { f.relativeToOrSelf(workspace).path }.getOrDefault(f.path).replace('\\', '/') to f
            }
        }
        return out
    }

    /** 拼出要塞进系统提示的那一段；一个都没有就返回空串。 */
    fun read(workspace: java.io.File, gitRoot: String? = null): String {
        val parts = mutableListOf<Pair<String, String>>()
        var used = 0
        for ((label, f) in sources(workspace, gitRoot)) {
            val text = runCatching { f.readText() }.getOrDefault("").trim()
            if (text.isEmpty()) continue
            val left = CAP - used
            if (left <= 0) {
                parts += label to "…（还有更多说明未纳入）"
                break
            }
            val body = if (text.length > left) text.take(left) + "\n…（本文件过长，已截断）" else text
            used += body.length
            parts += label to body
        }
        return render(parts)
    }

    private fun render(parts: List<Pair<String, String>>): String =
        parts.joinToString("\n\n") { "【${it.first}】\n${it.second}" }

    /**
     * 界面/CLI 保存时写哪个文件：已经有 AGENTS.md 就写它（那是三家共用的事实标准，
     * 别的 agent 也会读），否则写 `.haoai/memory.md`（不污染仓库根）。
     */
    fun target(workspace: java.io.File): java.io.File {
        val agents = java.io.File(workspace, "AGENTS.md")
        return if (agents.isFile) agents else java.io.File(workspace, ".haoai/memory.md")
    }
}

private fun modeText(mode: String) = when (mode) {
    "plan" -> "plan（只读规划）"
    "auto" -> "auto（全自动，不再逐条询问）"
    else -> "ask（写文件与执行命令前逐条询问）"
}
