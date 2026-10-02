package com.haoai.agent.ui

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * 「秒表的起点必须来自回合起点」的接线检查——扫源码，不是扫运行时。
 *
 * 起因：`ThinkingIndicator` 里那句 `val t0 = System.currentTimeMillis()` 是**在组合里造的起点**。
 * 移动端没有导航栈，换屏＝整棵树重建，所以"进设置再回聊天"会把 t0 重置成"现在"，
 * 屏幕上"已等 N 秒"从 0 重跳，看着像任务被重启了一次。真起点一直在 `ChatViewModel` 里，
 * 但那是个 `@Volatile private var`，界面拿不到 —— 拿不到就去自造，这就是缺陷的来路。
 *
 * `ElapsedClockTest` 只钉得住那个纯函数；**"界面到底有没有去取真起点"只能在源码里查**：
 * 回退成 `elapsedMs = System.currentTimeMillis() - t0` 时，纯函数一条都不会红。
 * 这和"wallpaper 参数收了没用"是同一类，判据形状也照它写：查声明、查使用、查调用点，
 * 并且**扫不到文件/扫到零处一律红**（一条什么都没扫到的检查不能算通过）。
 */
class ThinkingClockWiringTest {

    private fun sourceRoot(): File {
        val candidates = listOf(
            File("src/main/java"), File("app/src/main/java"), File("../app/src/main/java")
        )
        return candidates.firstOrNull { it.isDirectory && File(it, "com/haoai/agent").isDirectory }
            ?: error("找不到 app 的源码目录（试过 ${candidates.map { it.path }}）——扫不到源码时这条检查必须红")
    }

    private fun read(rel: String): String {
        val f = File(sourceRoot(), rel)
        assertTrue("找不到 $f —— 文件被挪走会让这条检查静默失效", f.isFile)
        return f.readText(Charsets.UTF_8)
    }

    /** 把 `fun Name(...)` 的函数体粗切出来：从参数表右括号到下一个顶层 `@Composable`/`private fun`。 */
    private fun bodyOf(text: String, fn: String): String {
        val at = text.indexOf("fun $fn(")
        assertTrue("源码里没有 $fn（改名了就要同步改这条判据，别让它空转）", at >= 0)
        val open = text.indexOf('(', at)
        var depth = 0
        var i = open
        while (i < text.length) {
            when (text[i]) {
                '(' -> depth++
                ')' -> { depth--; if (depth == 0) break }
            }
            i++
        }
        val rest = text.substring(i + 1)
        val end = Regex("\n(@Composable|private fun|internal fun|fun )").find(rest)?.range?.first
            ?: rest.length
        return rest.substring(0, end)
    }

    @Test
    fun `秒表读数一律走 ElapsedClock，不许自己减 t0`() {
        val hits = mutableListOf<String>()
        val seen = mutableListOf<String>()
        for (rel in listOf("com/haoai/agent/ui/chat/ChatScreen.kt", "com/haoai/agent/ui/chat/ChainOfThought.kt")) {
            val src = read(rel)
            Regex("""elapsed\w*\s*=[^=\n]*""").findAll(src).forEach { m ->
                val line = m.value.trim()
                if (line.contains("remember")) return@forEach        // 声明行，不是读数
                seen += rel
                if (!line.contains("ElapsedClock.ms(")) hits += "$rel → $line"
            }
        }
        assertTrue("一处秒表读数都没扫到：多半是变量改名了，这条判据已经空转", seen.isNotEmpty())
        if (hits.isNotEmpty()) fail("秒表又用组合里自造的起点算差值了（换屏回来会从 0 重跳）：\n" + hits.joinToString("\n"))
    }

    @Test
    fun `ThinkingIndicator 收了起点并且真的用了它`() {
        val body = bodyOf(read("com/haoai/agent/ui/chat/ChatScreen.kt"), "ThinkingIndicator")
        assertTrue("ThinkingIndicator 没声明 turnStartAt 参数", body.contains("turnStartAt"))
        // 声明 1 次之外还要有真实使用（参数表里那次 + 秒表里至少两次：remember key 与读数）
        val uses = Regex("turnStartAt").findAll(body).count()
        assertTrue("ThinkingIndicator 收了 turnStartAt 却只出现 $uses 次＝参数收了没用", uses >= 3)
    }

    @Test
    fun `调用点真的把回合起点传进去`() {
        val src = read("com/haoai/agent/ui/chat/ChatScreen.kt")
        val calls = Regex("ThinkingIndicator\\([^\\n]*").findAll(src).map { it.value.trim() }
            .filter { !it.startsWith("ThinkingIndicator(hint") }.toList()
        assertTrue("一个 ThinkingIndicator 调用点都没找到（判据空转）", calls.isNotEmpty())
        val missing = calls.filter { !it.contains("turnStartAt") }
        assertTrue("调用点没传起点，秒表会退回自造起点：" + missing.joinToString(" | "), missing.isEmpty())
        assertTrue("界面没订阅 ViewModel 的 turnStartAt（起点还是拿不到）",
            src.contains("vm.turnStartAt.collectAsState()"))
    }

    @Test
    fun `起点在 ViewModel 里是可订阅的，且开新轮时真的写`() {
        val src = read("com/haoai/agent/ui/ChatViewModel.kt")
        assertTrue("turnStartAt 不再是 StateFlow：界面订阅不到换轮这件事",
            src.contains("val turnStartAt = _turnStartAt.asStateFlow()"))
        assertTrue("还留着裸 @Volatile var turnStartAt（两份起点会漂）",
            !Regex("@Volatile[^\\n]*var turnStartAt").containsMatchIn(src))
        val writes = Regex("_turnStartAt\\.value\\s*=").findAll(src).count()
        // 两处真回合 + 一处 debug 假流式：少于 3 处说明有条路径忘了记起点
        assertTrue("只有 $writes 处写起点（应为 3 处：两条回合入口 + debug 假流式）", writes >= 3)
    }
}
