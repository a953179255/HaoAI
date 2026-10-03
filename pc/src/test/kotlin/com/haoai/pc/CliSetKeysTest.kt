package com.haoai.pc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `haoai set k=v` 这张键表（SettingsCli）。
 *
 * 这批为什么要给它写测试：像素剧本用 `PRE_SET="expertFeed=http://…"` 想把市场地址写进设置，
 * 而 CLI 那时**根本不认这个键** —— 它打印一句"不认识的设置项"之后照常返回退出码 0，
 * 于是 `ui-shot.sh` 里 `|| exit 1` 那道闸门没闭上，24 条判据红了 20 条，
 * 症状看着像"市场功能整个坏了"，实际是设置压根没落盘。
 * 这五条钉的是同一类问题：**写没写进去要能被判出来，而不是靠人读输出**。
 */
class CliSetKeysTest {

    private fun set(vararg kv: String) = SettingsCli.apply(PcSettings(), kv.toList())
    private fun List<String>.unrecognized() = any { it.contains("不认识") }

    @Test
    fun `the market address really lands in settings`() {
        val v = "http://127.0.0.1:8791/experts.json"
        val (s, bad) = set("expertFeed=$v")
        assertEquals("写市场地址不该有意见：" + bad, emptyList<String>(), bad)
        assertEquals("CLI 说存了就得真存了", v, s.expertFeed)
    }

    @Test
    fun `an unknown key is reported as a problem instead of passing quietly`() {
        val (s, bad) = set("noSuchKey=1")
        assertEquals("不认识的键不该改坏设置", PcSettings(), s)
        assertEquals("不认识的键必须留下一条问题（调用方只看退出码）", 1, bad.size)
        assertTrue(bad[0].contains("不认识"))
        // 这条是这批的核心：提示里的可用清单由表生成，不能是另一份手抄的清单
        assertTrue("提示里要把 expertFeed 也列出来，否则下一次还是没人知道能用它：" + bad[0],
            SettingsCli.KEYS.all { bad[0].contains(it) })
    }

    @Test
    fun `a malformed number keeps the old value and says so`() {
        val base = PcSettings(maxTokens = 4096)
        val (s, bad) = SettingsCli.apply(base, listOf("maxTokens=abc"))
        assertEquals("写错的数字该保持原值，不能静默变 0", 4096, s.maxTokens)
        assertEquals(1, bad.size)
        assertTrue(bad[0].contains("整数"))
    }

    @Test
    fun `every advertised key is actually accepted`() {
        // 漂移的两个方向都抓：表里列了但 when 没认 → 这条红；when 认了但表里漏 → 下一条红
        val notAccepted = SettingsCli.KEYS.filter { set("$it=x").second.unrecognized() }
        assertEquals("这些键写在可用清单里却不认：" + notAccepted, emptyList<String>(), notAccepted)
    }

    @Test
    fun `every when branch is also listed in the advertised keys`() {
        // 认了却不写进清单 = 下一个敲命令的人还是不知道能用它（这次漏的是 expertFeed）
        val src = codeOnly()
        val branchKeys = src.lines().filter { "-> n.copy(" in it }
            .flatMap { line ->
                Regex("\"([A-Za-z]+)\"").findAll(line.substringBefore("->")).map { it.groupValues[1] }
            }.toSet()
        assertTrue("分支清单读空了 —— 判据自己坏了", branchKeys.isNotEmpty())
        assertEquals("这些键能认却没写在可用清单里：" + (branchKeys - SettingsCli.KEYS.toSet()),
            emptySet<String>(), branchKeys - SettingsCli.KEYS.toSet())
    }

    /**
     * 读源码之前先把行注释剃掉。
     *
     * 不是洁癖：第一版直接读原文，变异检查（把那行分支注掉）之后
     * 「每个字段都有入口」那条**照样绿** —— 因为注释里还留着 `n.copy(expertFeed =`，
     * 正则把它当成了真代码。判据读注释的话，"删行红"这条就永远不成立。
     */
    private fun codeOnly(): String = File("src/main/kotlin/com/haoai/pc/SettingsCli.kt").readLines()
        .map { it.substringBefore("//") }.joinToString("\n")

    @Test
    fun `every settings field has a way in from the command line`() {
        /*
         * 新加一个设置项却忘了登记到 CLI 的表现：**界面上能改、命令行与像素剧本改不了**，
         * 而剧本只会报"设置没生效"。所以这里对着 PcSettings 的字段核一遍，漏登记的当场红 ——
         * 而不是等下一个剧本用 20 条红来发现。
         *
         * 字段名走 JVM 反射而不是 Kotlin 反射：这个构建是 --offline 的，
         * 不为一条测试引入 kotlin-reflect。
         * 「哪个分支 copy 了哪个字段」读的是源码，所以 build.gradle 的 tasks.test 里
         * 得把 SettingsCli.kt 声明成 input —— 否则改了它却报 UP-TO-DATE 直接跳过。
         */
        val src = codeOnly()
        val copied = Regex("""n\.copy\(\s*([A-Za-z]+)\s*=""").findAll(src)
            .map { it.groupValues[1] }.toSet()
        assertTrue("copy 清单读空了 —— 判据自己坏了", copied.isNotEmpty())
        val fields = PcSettings::class.java.declaredFields
            .filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) && !it.name.contains("$") }
            .map { it.name }
        assertTrue("字段清单读空了 —— 判据自己坏了", fields.isNotEmpty())
        // 这两个有自己的命令：`haoai tool off …` / `haoai flags on …`，不进 k=v 那张表
        val missing = fields.filter { it !in setOf("toolsOff", "flags") && it !in copied }
        assertEquals("这些设置项在 CLI 上没有入口（登记到 SettingsCli.KEYS 并加一个 when 分支）：" + missing,
            emptyList<String>(), missing)
    }
}
