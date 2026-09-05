package com.haoai.agent.agent.skills

/**
 * 导入技能内容扫描（Phase 5）：约 8 条正则黑名单，命中不阻断——只产出标签
 * 记入 SkillMeta.reviewNotes 供人审（候选态本就不进系统提示索引）。
 * 设计取舍：高置信、低频规则；宁可漏报不误报，误报只多一行提示不拦导入。
 */
object SkillGuard {

    private val rules: List<Pair<String, Regex>> = listOf(
        // "无视以上指令"类：prompt 注入的最典型形态
        "指令覆写" to Regex(
            "(?i)(无视|忽略| disregard |ignore )[^\\n。;；]{0,16}" +
                "(以上|之前|上述|前面|先前|所有|all |previous |prior |above )?" +
                "(指令|指示|规则|约束|instructions?|rules?|prompts?|directives?)"
        ),
        // system:/developer: 行首覆写（YAML/Markdown 正文里伪装角色消息）
        "角色注入" to Regex("(?im)^\\s{0,8}(system|developer|assistant)\\s*[:：]"),
        // curl/wget … | sh 管道执行
        "管道执行" to Regex("(?i)\\b(curl|wget)\\b[^|\\n]{0,300}\\|\\s*(sudo\\s+)?(ba|z|da|fi)?sh\\b"),
        // 要求隐瞒行为
        "要求隐瞒" to Regex(
            "(?i)(不要告诉|别告诉|不要让(用户|主人|我)知道|别让(用户|主人|我)知道|隐瞒|" +
                "do\\s*n[o']t\\s+(tell|inform|reveal|mention)|hide\\s+(this|it)\\s+from|" +
                "keep\\s+(this|it)\\s+(secret|hidden))"
        ),
        // 危险命令
        "危险命令" to Regex("(?i)(sudo\\s+(rm|mkfs|dd)\\b|rm\\s+-rf\\s+[/~*]|\\bmkfs\\.\\w+|\\bdd\\s+if=)"),
        // 凭据外传：密钥/token + 发送/上传 组合
        "凭据外传" to Regex(
            "(?i)(api[_-]?key|access[_-]?token|密钥|密码|凭据)[^\\n]{0,40}" +
                "(发(送|给|到)|上传|外传|exfiltrat|send\\s+to|post\\s+to)"
        ),
        // 诱导泄露系统提示词
        "提示词泄露" to Regex(
            "(?i)(逐字|原样|完整|verbatim)[^\\n]{0,12}(输出|复述|打印|重复|repeat|print|reveal)" +
                "[^\\n]{0,24}(系统提示|system\\s*prompt|系统指令|instructions)"
        ),
        // 权限提升
        "权限提升" to Regex("(?i)(chmod\\s+[0-7]{3,4}\\s+/(etc|usr|bin)|chown\\s+root|提权|escalat\\w*\\s+privileg)")
    )

    /** 返回命中的规则标签列表；空列表=未命中。 */
    fun scan(text: String): List<String> =
        rules.filter { it.second.containsMatchIn(text) }.map { it.first }
}
