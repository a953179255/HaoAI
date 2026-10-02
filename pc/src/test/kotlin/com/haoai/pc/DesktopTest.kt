package com.haoai.pc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 桌面控制的回归。
 *
 * 这个工具的产出物是**一段生成出来的 PowerShell**，所以最容易坏在两个地方，
 * 而且都是"跑起来才知道"的那类：
 * 1. 占位符 `%%` 没换干净 / 布尔值写成了 PowerShell 不认的裸词 —— 脚本直接语法错；
 *    第一版的 `Cursor.Position` 编译失败就是这么发现的（在真机上）。
 * 2. 引号：标题、路径、控件名都是模型给的字符串，一个单引号就能把整条命令改写。
 * 这两类不需要屏幕、不需要权限，全部可以离线断言，所以放在单测里而不是靠手点。
 */
class DesktopTest {

    companion object {
        /** 别让任何一条测试有机会写到用户真实的 %LOCALAPPDATA%\HaoAI。 */
        @JvmStatic
        @org.junit.BeforeClass
        fun setUp() {
            val tmp = Files.createTempDirectory("haoai-desk-home").toFile()
            System.setProperty("haoai.home", File(tmp, "home").absolutePath)
        }
    }

    private class SpyGate(var allow: Boolean = true) : Gate {
        val asked = mutableListOf<Pair<String, String>>()
        override fun approve(title: String, detail: String, kind: String): Boolean {
            asked += title to detail
            return allow
        }

        override fun ask(question: String, options: List<String>) = ""
    }

    private fun args(json: String) = Json.parseToJsonElement(json).jsonObject

    private fun ctx(mode: String, gate: Gate, flagOn: Boolean = true): ToolCtx {
        val dir = Files.createTempDirectory("haoai-desk").toFile().apply { mkdirs() }
        val flags = if (flagOn) mapOf("desktop_control" to true) else emptyMap()
        return ToolCtx(dir, PcSettings(flags = flags), mode, gate)
    }

    private val allScripts: List<String>
        get() = listOf(
            Ps.capture("C:/x/y.png"),
            Ps.windows(),
            Ps.uiTree("Edge", 3, 50),
            Ps.click(10, 20),
            Ps.click(10, 20, right = true, double = true),
            Ps.invoke("Edge", "新建标签页"),
            Ps.focus(),
            Ps.typeText("a+b"),
            Ps.keys("{Enter}")
        )

    @Test
    fun `no placeholder survives into a generated script`() {
        for (s in allScripts) {
            assertFalse("漏了未替换的占位符：\n$s", s.contains("%%"))
            assertFalse("Kotlin 插值漏进脚本：\n$s", s.contains("\${"))
        }
        // 每条都以那行设置编码的前缀开头，否则中文输出会变乱码
        assertTrue(allScripts.all { it.startsWith("\$ErrorActionPreference") })
    }

    @Test
    fun `powershell booleans are variables, not barewords`() {
        // [M]::Click(1,2,true,false) 在 PowerShell 里是语法错误：表达式模式里没有裸词 true
        val call = Ps.click(1, 2).lines().first { it.contains("[Mouse]::Click") }.trim()
        assertEquals("%%r=[Mouse]::Click(1,2,\$false,\$false)".replace("%%", "\$"), call)
        assertEquals("%%r=[Mouse]::Click(1,2,\$true,\$false)".replace("%%", "\$"),
            Ps.click(1, 2, right = true).lines().first { it.contains("[Mouse]::Click") }.trim())
        assertEquals("%%r=[Mouse]::Click(1,2,\$true,\$true)".replace("%%", "\$"),
            Ps.click(1, 2, right = true, double = true).lines().first { it.contains("[Mouse]::Click") }.trim())
        assertFalse("裸词 true 会被 PowerShell 当命令名", Ps.click(1, 2).contains(",true,"))
    }

    @Test
    fun `the second switch keeps clicks off by default`() {
        val s = Ps.click(11, 22)
        assertTrue(s, s.contains("HAOAI_ALLOW_CLICK"))
        assertTrue("没 env 时也要说清做了什么：\n$s", s.contains("但没真点"))
        assertTrue(s, s.contains("右") && s.contains("双击"))
    }

    /**
     * 点击前必须回读光标位置。
     *
     * 起因是实测：脚本报"已点击 -1613,810"，事后单独读光标却停在别处。
     * 一个"说了做但没做"的桌面工具比一个拒绝干活的危险得多 ——
     * 模型会以为界面已经变了，然后基于不存在的状态继续往下推。
     */
    @Test
    fun `a click verifies the cursor actually arrived before pressing`() {
        val s = Ps.click(11, 22)
        assertTrue("没回读光标位置：\n$s", s.contains("GetCursorPos"))
        assertTrue("位置不符时没有拒绝点击：\n$s", s.contains("if(!moved||ax!=x||ay!=y)"))
        assertTrue("拒绝时不说原因：\n$s", s.contains("没有点击"))
        // 报出来的三件事：请求坐标、实际坐标、DPI —— 少一个就没法定位坐标系问题
        assertTrue(s, s.contains("请求 ") && s.contains("光标实际 ") && s.contains("系统 DPI "))
    }

    @Test
    fun `a quote in a model-supplied string cannot break out of the literal`() {
        // 三个入口都是模型可控字符串：窗口标题、控件名、落盘路径
        val title = Ps.uiTree("it's \"x\"", 3, 50)
        assertTrue(title, title.contains("'it''s \"x\"'"))
        val inv = Ps.invoke("a'b", "c'd")
        assertTrue(inv, inv.contains("'a''b'") && inv.contains("'c''d'"))
        val cap = Ps.capture("C:/tmp/o'k.png")
        assertTrue(cap, cap.contains("Save('C:/tmp/o''k.png')"))
    }

    /**
     * 键入走 SendInput 的 Unicode 直发，不走 SendKeys。
     *
     * 回归的是实测事故：中文输入法开着时，SendKeys 发的 `HaoAI` 整个被吃掉、
     * `(x)` 被当拼音候选变成"（行）"、`^a` 触发的是输入法的中英切换。
     * 所以这里锁两件事：脚本里不许再出现 SendWait，且特殊字符必须原样进载荷。
     */
    @Test
    fun `typed text goes out as unicode packets, not sendkeys`() {
        val t = Ps.typeText("100% (x) ^a +b 键入测试")
        assertFalse("又用回 SendKeys 了：\n$t", t.contains("SendWait"))
        assertTrue("没走 KEYEVENTF_UNICODE：\n$t", t.contains("UNICODE"))
        assertTrue("载荷被改写过：\n$t", t.contains("'100% (x) ^a +b 键入测试'"))
        // 报告里要带上系统实际接收到的按键事件数，否则"发了"和"到了"是两回事
        assertTrue(t, t.contains("个按键事件"))
    }

    /**
     * 键盘输入的内容不允许被回显进 PowerShell 的双引号串。
     *
     * 单引号串里翻倍 `''` 就够了；双引号串里 `"` 和 `$` 都能改写这条命令。
     * 所以"把刚按的键打印出来"这件事必须留在 Kotlin 侧，不能拼进脚本。
     */
    @Test
    fun `keys text is never echoed into a double-quoted powershell string`() {
        val evil = "a\"';Write-Host pwned;" + "\$env:PATH"
        val expected = "a\"'';Write-Host pwned;" + "\$env:PATH" // 单引号翻倍，其余原样
        val k = Ps.keys(evil)
        assertEquals("注入文本出现了不止一次：\n$k", 2, k.split("pwned").size)
        assertEquals(expected, k.substringAfter("SendWait('").substringBefore("')"))
        val t = Ps.typeText(evil)
        assertEquals("注入文本出现了不止一次：\n$t", 2, t.split("pwned").size)
        assertEquals(expected, t.substringAfter("Type('").substringBefore("')"))
    }

    /**
     * `$true` / `$false` / `$null` 在 PowerShell 里是**只读**自动变量。
     *
     * `invoke` 的第一版写了 `$true = [Automation.Condition]::TrueCondition`，
     * 语法完全合法、编译不报错，跑起来才炸"无法覆盖变量 True"。
     * 这类坑靠读代码很容易漏，所以按模式扫一遍所有生成的脚本。
     */
    @Test
    fun `never assigns to a powershell read-only automatic variable`() {
        val assign = Regex("""\$(true|false|null)\s*=[^=]""")
        for ((i, s) in allScripts.withIndex()) {
            assertFalse("脚本 #$i 给只读自动变量赋值了：\n$s", assign.containsMatchIn(s))
        }
    }

    /**
     * 没有前台窗口时，输入类动作必须说"没有接收方"而不是"已发送"。
     *
     * 实测这台机器出现过 `GetForegroundWindow` 返回 NULL 的整段时间：
     * 那时 SendInput 照样回报"32 个事件全部接收"，可没有任何应用收到字。
     * 只看 API 的返回值会得出一个完全假绿的结论 —— 判据得按用例声明：
     * 没有接收方 = 失败。
     */
    @Test
    fun `typing reports failure when nothing has focus`() {
        for (s in listOf(Ps.typeText("x"), Ps.keys("{Enter}"))) {
            assertTrue("没检查前台窗口：\n$s", s.contains("没有前台窗口"))
            assertTrue("没说清后果：\n$s", s.contains("已被系统丢弃"))
        }
    }

    @Test
    fun `read-only subs still ask nobody`() {
        val gate = SpyGate()
        val r = ScreenTool().runB(args("""{"sub":"nope"}"""), ctx("ask", gate))
        assertTrue("不认识的 sub 该报错：" + r.content, r.error)
        assertTrue(r.content.contains("windows"))
        assertEquals(0, gate.asked.size)
    }

    @Test
    fun `missing arguments fail before any approval prompt`() {
        // 少参数却先弹框＝让人批一次注定失败的调用；而且框上写着"键入 0 个字符"
        for ((json, hint) in listOf(
            """{"sub":"click","x":"3"}""" to "x 与 y",
            """{"sub":"ui"}""" to "title",
            """{"sub":"invoke","title":"Edge"}""" to "name",
            """{"sub":"type","text":""}""" to "text",
            """{"sub":"key"}""" to "keys"
        )) {
            val gate = SpyGate()
            val r = ScreenTool().runB(args(json), ctx("ask", gate))
            assertTrue("$json 该失败：${r.content}", r.error)
            assertTrue("${json} 的提示不可照做：${r.content}", r.content.contains(hint))
            assertEquals("$json 不该弹审批", 0, gate.asked.size)
        }
    }

    @Test
    fun `plan mode refuses a click without going through the gate`() {
        val gate = SpyGate()
        val r = ScreenTool().runB(args("""{"sub":"click","x":"1","y":"2"}"""), ctx("plan", gate))
        assertTrue(r.error)
        assertTrue("没说是计划模式：${r.content}", r.content.contains("计划模式"))
        assertEquals("计划模式不该弹审批", 0, gate.asked.size)
    }

    @Test
    fun `a deny rule on screen blocks the click`() {
        val rules = Files.createTempFile("haoai-desk-rules-", ".json").toFile()
        val ws = Files.createTempDirectory("haoai-desk-ws").toFile().apply { mkdirs() }
        Policies.reset(rules)
        try {
            Policies.get().add(ws, Rule("screen", "click*", Decision.DENY))
            val gate = SpyGate()
            val r = ScreenTool().runB(
                args("""{"sub":"click","x":"1","y":"2"}"""), ToolCtx(ws, PcSettings(flags = mapOf("desktop_control" to true)), "auto", gate)
            )
            assertTrue("规则没拦住点击：${r.content}", r.error)
            assertTrue(r.content.contains("规则拒绝"))
            assertEquals(0, gate.asked.size)
        } finally {
            Policies.reset(null)
        }
    }

    @Test
    fun `ask mode shows which window would receive the action`() {
        // 审批卡上只写"按下按键 {F1}"是没有用的：键盘发给的是此刻的前台窗口。
        // 这条同时也是 Ps.focus() 的联机冒烟测试（它真的会跑一次 PowerShell）。
        // gate 用 allow=false：一按 F1 就可能打开某个窗口的帮助页，测试不该有副作用。
        val gate = SpyGate(allow = false)
        val r = ScreenTool().runB(args("""{"sub":"key","keys":"{F1}"}"""), ctx("ask", gate))
        assertTrue("拒绝了却还执行：${r.content}", r.error)
        assertEquals(1, gate.asked.size)
        val detail = gate.asked[0].second
        assertTrue("审批详情没写焦点窗口：$detail", detail.contains("当前焦点窗口"))
        assertTrue("标题里没说要按什么键：${gate.asked[0].first}", gate.asked[0].first.contains("{F1}"))
    }

    @Test
    fun `the tool is invisible while the flag is off`() {
        val t = ScreenTool()
        assertFalse(t.visibleWhen(PcSettings()))
        assertTrue(t.visibleWhen(PcSettings(flags = mapOf("desktop_control" to true))))
        // 26 把：24 + agent_list + ask_agent（多 Agent 那两把）。工具总数变了要同步改 EngineFlowTest 里那条断言
        assertEquals(26, builtinTools().size)
    }
}
