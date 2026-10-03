package com.haoai.pc

/**
 * `haoai set k=v …` 的那张键表（从 Main.kt 里搬出来的，原因见下面的退出码）。
 *
 * 为什么要单独一个对象：这张表以前藏在 Main.kt 的一个 private 函数里，于是
 * **测试碰不到它**。结果这一批踩的坑 —— 像素剧本用 `PRE_SET="expertFeed=http://…"`
 * 想先把市场地址写进设置，CLI 其实不认这个键、只打印一句"不认识的设置项"，
 * **却照常返回退出码 0**，脚本里那句 `|| exit 1` 的闸门就没闭上：
 * 24 条判据红了 20 条，症状看着像"市场功能整个坏了"，而设置压根没写进去。
 *
 * 两条规矩因此钉在这里：
 * 1. **可用键名只有一份**（[KEYS]，就是 when 的那些分支）—— 提示语里那份是它生成的，
 *    不会再出现"表能认、话说不到"的漂移；
 * 2. **没生效的项必须让调用方知道**：`apply` 把每一条问题原样带回去，
 *    Main 那边打印完照常保存能存的，然后 exitProcess(1)（退出码是脚本唯一的读回通道）。
 */
internal object SettingsCli {

    /**
     * CLI 认的设置项键名（含别名写法）。
     *
     * 这张表是"README 里写的 `haoai set …` 到底能不能用"的判据，
     * CliSetKeysTest 里那条用反射对着 [PcSettings] 的字段核一遍：
     * 新加一个设置项却忘了在这儿登记，表现是它在界面上能改、在命令行上是只读的。
     */
    val KEYS: List<String> = listOf(
        "model", "base", "baseUrl", "provider", "mode", "workspace",
        "maxTurns", "maxTokens", "contextChars", "storedCap", "reqCap",
        "compactTriggerChars", "compactKeepTail", "temperature",
        "reasoningEffort", "effort", "searchProvider", "embedUrl",
        "fallback", "fallbackChain", "expertFeed",
    )

    /**
     * 收一串 `k=v`，返回「改好的设置」+「每一条问题」（空 = 全部真的写进去了）。
     *
     * 数字项写错格式时**保持原值并留下一句问题**，不静默变成 0；
     * 不认识的键保持原样并说明 —— 但两种都会进 problems，所以脚本能靠退出码发现。
     */
    fun apply(s: PcSettings, rest: List<String>): Pair<PcSettings, List<String>> {
        var n = s
        val bad = ArrayList<String>()
        fun int(k: String, v: String, cur: Int): Int =
            v.toIntOrNull() ?: run { bad += "「$k」要的是整数，给的是「$v」—— 这项没改"; cur }
        rest.forEach { kv ->
            val parts = kv.split("=", limit = 2)
            if (parts.size != 2) { bad += "这一项不是 k=v 的形状：$kv"; return@forEach }
            val (k, v) = parts[0] to parts[1]
            n = when (k) {
                "model" -> n.copy(model = v)
                "base", "baseUrl" -> n.copy(baseUrl = v)
                "provider" -> n.copy(providerName = v)
                "mode" -> n.copy(permissionMode = v)
                "workspace" -> n.copy(workspace = v)
                "maxTurns" -> n.copy(maxTurns = int(k, v, n.maxTurns))
                "maxTokens" -> n.copy(maxTokens = int(k, v, n.maxTokens))
                "contextChars" -> n.copy(contextChars = int(k, v, n.contextChars))
                "reasoningEffort", "effort" -> n.copy(reasoningEffort = v.trim())
                "searchProvider" -> n.copy(searchProvider = v.trim().lowercase())
                // 语义检索端点（#6）：`haoai set embedUrl=http://127.0.0.1:8199/embeddings`，空串=关闭
                "embedUrl" -> n.copy(embedUrl = v.trim())
                // 专家市场清单（#136）：空串 = 这个功能等于不存在
                "expertFeed" -> n.copy(expertFeed = v.trim())
                // 降级链：`haoai set fallback=glm-4-flash,small@http://127.0.0.1:8080/v1`
                "fallback", "fallbackChain" -> n.copy(fallback = v.trim())
                "storedCap" -> n.copy(storedCap = int(k, v, n.storedCap))
                "reqCap" -> n.copy(reqCap = int(k, v, n.reqCap))
                "compactTriggerChars" -> n.copy(compactTriggerChars = int(k, v, n.compactTriggerChars))
                "compactKeepTail" -> n.copy(compactKeepTail = int(k, v, n.compactKeepTail))
                "temperature" -> n.copy(
                    temperature = v.toDoubleOrNull()
                        ?: run { bad += "「temperature」要的是小数，给的是「$v」—— 这项没改"; n.temperature }
                )
                else -> {
                    bad += "不认识的设置项：$k（可用：${KEYS.joinToString(" ")}）"
                    n
                }
            }
        }
        return n to bad
    }
}
