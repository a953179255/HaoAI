package com.haoai.agent.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * 「壁纸应用于所有页面」的接线检查——扫源码，不是扫运行时。
 *
 * 起因是真机上的一条缺陷：设置里把全局壁纸打开之后，**唯独「电脑联动」这一屏是白底**。
 * 查下来是 `PcLinkScreen` 收了 `wallpaper` 参数、却从头到尾没用过它：那一屏只有
 * `Column(...).background(MaterialTheme.colorScheme.background)` 一块实底，传进来的壁纸没人画，
 * 玻璃也就没东西可采样（定时任务/技能库/MCP/工作流/记忆库/全部会话那几屏是"收参数 +
 * 建页面本地采样画布 + 铺 Image"一整套，所以它们正常）。
 *
 * 这类"参数收了没用"编译不报错、跑起来也不报错，只有拿眼睛看像素才会发现；而且参数的默认值
 * 就是 `null`，"忘了传"与"故意不传"在编译期长得一模一样。所以写成两条静态判据：
 *  1. 凡是声明了 `wallpaper:` 参数的屏，函数体里必须真的引用它；
 *  2. MainActivity 渲染这些屏时，每个调用点都必须显式传 `wallpaper =`。
 * 判据自己防"什么都没扫到却算通过"：扫不到源码目录、扫到的屏数少于已知数量，一律红。
 */
class WallpaperWiringTest {

    /** 找 app 模块的源码根目录：单测的工作目录在模块目录或仓库根都可能，逐个试。 */
    private fun sourceRoot(): File {
        val candidates = listOf(
            File("src/main/java"), File("app/src/main/java"), File("../app/src/main/java")
        )
        return candidates.firstOrNull { it.isDirectory && File(it, "com/haoai/agent").isDirectory }
            ?: error("找不到 app 的源码目录（试过 ${candidates.map { it.path }}）——扫不到源码时这条检查必须红，不能当成通过")
    }

    private fun kotlinSources(): List<File> =
        sourceRoot().walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    /** 把 `fun Name(参数表` 的函数名与参数表切出来（参数表按括号配平，跨行也算）。 */
    private fun functionsWithWallpaperParam(text: String): List<String> {
        val names = mutableListOf<String>()
        var i = 0
        while (true) {
            val at = text.indexOf("fun ", i)
            if (at < 0) break
            val open = text.indexOf('(', at)
            if (open < 0) break
            var depth = 0
            var close = -1
            for (j in open until text.length) {
                when (text[j]) {
                    '(' -> depth++
                    ')' -> {
                        depth--; if (depth == 0) { close = j; break }
                    }
                }
            }
            if (close < 0) break
            val header = text.substring(at, open)
            val params = text.substring(open, close)
            val name = header.removePrefix("fun ").trim().split(Regex("\\s+")).lastOrNull() ?: ""
            // 只认屏（XxxScreen）：绘制壁纸的辅助函数（参数里也带 wallpaper）不该被要求"自己画一遍"
            if (params.contains("wallpaper:") && name.endsWith("Screen")) names += name
            i = close
        }
        return names
    }

    @Test
    fun `能扫到这些屏（前提检查，别在扫不到时空转）`() {
        val screens = kotlinSources().flatMap { functionsWithWallpaperParam(it.readText()) }.distinct()
        assertTrue("只扫到 ${screens.size} 个声明 wallpaper 参数的屏：$screens", screens.size >= 7)
        assertTrue("电脑联动页必须在名单里（它就是这条检查要盯的那一屏）", screens.contains("PcLinkScreen"))
    }

    @Test
    fun `声明了 wallpaper 参数的屏必须真的引用它`() {
        val offenders = mutableListOf<String>()
        for (f in kotlinSources()) {
            val text = f.readText()
            val declared = functionsWithWallpaperParam(text)
            if (declared.isEmpty()) continue
            // 参数只出现一次 == 只有声明、函数体一次都没用它（PcLinkScreen 原本就是这个状态）
            val uses = Regex("""\bwallpaper\b""").findAll(text).count()
            if (uses <= declared.size) offenders += "${f.name} 声明了 ${declared}，全文只出现 wallpaper $uses 次"
        }
        assertEquals(
            "这些屏收了 wallpaper 参数却从不使用，开了全局壁纸它们就是一块实底色：\n${offenders.joinToString("\n")}",
            emptyList<String>(), offenders
        )
    }

    @Test
    fun `MainActivity 渲染这些屏时每个调用点都显式传了 wallpaper`() {
        val root = sourceRoot()
        val screens = kotlinSources().flatMap { functionsWithWallpaperParam(it.readText()) }.distinct()
        val text = File(root, "com/haoai/agent/MainActivity.kt").readText()
        assertTrue("MainActivity 读不到", text.length > 1000)

        val missing = mutableListOf<String>()
        for (name in screens) {
            val calls = Regex("""\b$name\(""").findAll(text).toList()
            assertTrue("$name 声明了 wallpaper 参数却没在 MainActivity 里渲染，这条检查覆盖不到它", calls.isNotEmpty())
            for (call in calls) {
                val open = call.range.last            // '(' 的位置
                var depth = 0
                var close = open
                for (j in open until text.length) {
                    when (text[j]) {
                        '(' -> depth++
                        ')' -> {
                            depth--; if (depth == 0) { close = j; break }
                        }
                    }
                }
                val body = text.substring(open, close + 1)
                val line = text.substring(0, call.range.first).count { it == '\n' } + 1
                if (!body.contains("wallpaper")) missing += "$name 在 MainActivity:$line 这个调用点没传 wallpaper"
            }
        }
        assertEquals(missing.joinToString("\n"), emptyList<String>(), missing)
    }
}
