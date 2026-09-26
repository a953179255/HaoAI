package com.haoai.pc

/**
 * 上下文压缩：把太长的早期历史折成一条摘要，让"会话能开多久"不再受窗口大小限制。
 *
 * 为什么必须做：这一版之前只有两级截断（单条工具结果过 `REQ_CAP`、整窗超预算就
 * **从头部丢**）。丢掉是静默的 —— 模型不会告诉你"我不记得你最初的要求了"，
 * 它会用现在看得见的东西接着编。实测：今晚的 loop 测试把输入一路推到 148k token 还不收手。
 * 手机端在"压缩结构改造"那一批已经把这件事做成"追加式水位 + 预算窗口"，
 * PC 端抄同一套判据：**摘要要留水位；切点绝不能切断 tool_call 与它的回复**。
 *
 * 两条取向：
 * - 优先让模型自己压（它知道哪句重要）；但模型不可用/超时/返回空时**必须还能压** ——
 *   否则"省 token 的机制"自己成了一个新的故障点。所以有一份确定性兜底摘要。
 * - 摘要只列事实、不下结论：写成"结论是 X"的散文，后面会被模型当成证据继续引用。
 */
object Compactor {

    /** 给模型的压缩提示。要清单不要散文：散文会漏掉文件名与路径。 */
    fun promptFor(msgs: List<Msg>): String = buildString {
        appendLine("下面是一段更早的对话（含工具调用与结果）。请压成不超过 25 行的要点清单，")
        appendLine("只保留后续工作必须知道的事实，不要下结论、不要评价。必须包含：")
        appendLine("1) 用户最初要什么（原话要点）；2) 读过/写过/改过哪些文件路径；")
        appendLine("3) 执行过哪些命令与结果要点（成功/失败）；4) 还没做完的事。")
        appendLine("直接输出清单，不要客套话。")
        appendLine()
        msgs.forEach { m ->
            val body = (m.content ?: "").replace('\n', ' ').take(600)
            when {
                m.calls.isNotEmpty() -> {
                    val calls = m.calls.joinToString("; ") { "${it.name} ${it.args.take(120)}" }
                    appendLine("[${m.role}] $body ⟶ 调用: $calls")
                }
                m.role == "tool" -> appendLine("[tool:${m.name}] $body")
                else -> appendLine("[${m.role}] $body")
            }
        }
    }

    /**
     * 确定性机器摘要：模型不可用时的兜底。
     *
     * 只从**结构**里取信息（角色、工具名、参数里的路径/命令、失败痕迹），
     * 参数是原始 JSON 串，这里用正则粗提取 —— 宁可多列也别漏列，
     * 它不参与任何执行判断，只给模型当"我之前干过什么"的提示。
     */
    fun digest(msgs: List<Msg>): String = buildString {
        appendLine("（更早 ${msgs.size} 条对话的机器摘要：只列事实，不列结论）")

        val asks = msgs.filter { it.role == "user" }.takeLast(6)
        if (asks.isNotEmpty()) {
            appendLine("· 用户提过：")
            asks.forEach { appendLine("   - ${(it.content ?: "").replace('\n', ' ').take(120)}") }
        }

        val calls = msgs.flatMap { it.calls }
        if (calls.isNotEmpty()) {
            val counts = linkedMapOf<String, Int>()
            val paths = linkedSetOf<String>()
            val cmds = linkedSetOf<String>()
            calls.forEach { c ->
                counts[c.name] = (counts[c.name] ?: 0) + 1
                PATH_IN_ARGS.find(c.args)?.groupValues?.getOrNull(1)?.let { paths += it }
                CMD_IN_ARGS.find(c.args)?.groupValues?.getOrNull(1)?.let { cmds += it }
            }
            appendLine("· 调用统计：${counts.entries.joinToString("、") { "${it.key}×${it.value}" }}")
            if (paths.isNotEmpty()) appendLine("· 涉及文件：${paths.take(24).joinToString("、")}")
            if (cmds.isNotEmpty()) appendLine("· 执行过的命令（前 12 条）：${cmds.take(12).joinToString(" | ")}")
        }

        val failed = msgs.count { m ->
            val c = m.content ?: return@count false
            m.role == "tool" && (c.contains("规则拒绝") || c.contains("失败") ||
                c.contains("error") || c.contains("×"))
        }
        if (failed > 0) appendLine("· 有 $failed 次工具调用是失败或被拒的，重做之前先确认现状")
    }

    private val PATH_IN_ARGS = Regex("\"(?:path|old_path|new_path|file)\"\\s*:\\s*\"([^\"]{1,140})\"")
    private val CMD_IN_ARGS = Regex("\"command\"\\s*:\\s*\"([^\"]{1,90})\"")
}
