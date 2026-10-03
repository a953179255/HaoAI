package com.haoai.pc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 派工这笔账记在**谁**名下 —— `Engine.spawn` 里那两行 `s.role` / `s.preset`。
 *
 * 为什么单独钉一份：账本（`UsageLedger`）与 Token 统计的"按专家"完全依赖子会话上的
 * `role`（显示名）与 `preset`（卡 id），而这两行原来是用 `persona.isNotBlank()` 当条件的。
 * 那是个看起来合理实则错的条件：**卡可以只填名字不填人设**（「新建专家」只要求二者之一），
 * 于是"按卡派工"派出去的那一笔会记回主持人头上 —— 恰恰是那段注释声称要避免的事。
 * 另一半是 label：模型爱写什么写什么，拿它当专家名，Token 统计里就会冒出
 * "老张干活""小任务 2"这种假专家，而那支卡的名字是已知的。
 *
 * 四条主张：按 id 派工要记到那张卡；卡没写人设也要记到那张卡；label 不许造出假专家；
 * 没点卡的普通派工仍跟着父会话（别顺手改坏另一条路）。最后一条是拒法：卡不在了要说清。
 */
class SubAttributionTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun setUp() {
            val tmp = Files.createTempDirectory("haoai-subattr").toFile()
            System.setProperty("haoai.home", File(tmp, "home").absolutePath)
        }
    }

    private class Allow : Gate {
        override fun approve(title: String, detail: String, kind: String) = true
        override fun ask(question: String, options: List<String>) = options.firstOrNull() ?: ""
    }

    /** 一答就收的脚本客户端：子任务只需要吐一段结论文本。 */
    private class Scripted(private val text: String) : ChatClient {
        override fun chat(
            messages: List<Msg>, tools: List<ToolSchema>, onText: (String) -> Unit
        ): AssistantTurn {
            onText(text)
            return AssistantTurn(text, emptyList(), Usage(11, 4), "stop")
        }
    }

    private fun ws(): File = Files.createTempDirectory("haoai-subattr-ws").toFile()

    private fun card(id: String, name: String, persona: String) = Preset(
        id = id, name = name, persona = persona, model = "", workspace = "", mode = "ask"
    )

    private fun parent(workspace: File, role: String = "", preset: String = ""): Engine {
        val s = Session("p" + System.nanoTime(), workspace)
        s.role = role
        s.preset = preset
        val e = Engine(s, PcSettings(permissionMode = "auto"), builtinTools(), Allow(), {},
            Scripted("父会话这一轮不该被用到"))
        e.childClient = { Scripted("成员结论：第一行是 hello") }
        return e
    }

    /** 账本里最后一条子任务行（跑一次 spawn 就读一次，别把顺序写进断言里）。 */
    private fun lastSub(): UsageLedger.Row {
        val rows = UsageLedger.pick(kind = UsageLedger.SUB)
        assertTrue("账本里没有子任务行：派工这笔钱根本没记账，Token 统计的「按专家」会是空的",
            rows.isNotEmpty())
        return rows.last()
    }

    @Test
    fun `dispatch by card id books the spend to that expert`() {
        Presets.save(listOf(card("attr-a", "前端老张", "你是前端，负责页面与交互。")))
        val (res, _) = parent(ws()).spawn("老张干活", "读 a.txt 告诉我第一行",
            SubOpts(preset = "attr-a"))
        assertTrue("派工被拒：$res", res.indexOf("没有这张角色卡") < 0)
        val r = lastSub()
        assertEquals("按卡派工要把账记到那张卡", "attr-a", r.preset)
        assertEquals("专家名取卡上的名字", "前端老张", r.expert)
    }

    @Test
    fun `a card with only a name still books to that expert`() {
        // 「新建专家」只要求名字与人设二选一：只有名字的卡是合法形状。
        Presets.save(listOf(card("attr-b", "测试小李", "")))
        val (res, _) = parent(ws()).spawn("小李跑一下", "读 b.txt", SubOpts(preset = "attr-b"))
        assertTrue("派工被拒：$res", res.indexOf("没有这张角色卡") < 0)
        val r = lastSub()
        assertEquals("卡没写人设也得记到那张卡（原来这里用 persona 非空当条件，钱回到主持人头上）",
            "attr-b", r.preset)
        assertEquals("测试小李", r.expert)
    }

    @Test
    fun `a label cannot mint a fake expert`() {
        Presets.save(listOf(card("attr-c", "调研分析", "你是调研。")))
        // 模型写的 label 是"帮我查一下"这类随口话；拿它当专家名，统计里就多一个假专家。
        parent(ws()).spawn("帮我查一下", "读 c.txt", SubOpts(preset = "attr-c"))
        val r = lastSub()
        assertEquals("label 只是界面上那块标牌，专家名要取卡上的", "调研分析", r.expert)
        assertEquals("attr-c", r.preset)
    }

    @Test
    fun `a plain subtask follows the parent session`() {
        val workspace = ws()
        // 父会话自己是一张卡起的：没点卡的子任务仍记在这张卡名下（别顺手改坏另一条路）。
        Presets.save(listOf(card("attr-p", "口播稿", "你是文案。")))
        val (res, _) = parent(workspace, role = "口播稿", preset = "attr-p")
            .spawn("顺手查一下", "读 d.txt")
        assertTrue("派工被拒：$res", res.indexOf("没有这张角色卡") < 0)
        val r = lastSub()
        assertEquals("没点卡就跟着父会话", "attr-p", r.preset)
        assertEquals("口播稿", r.expert)
    }

    @Test
    fun `dispatch to a card that no longer exists is refused out loud`() {
        Presets.save(listOf(card("attr-q", "还在的卡", "…")))
        val before = UsageLedger.pick(kind = UsageLedger.SUB).size
        val (res, _) = parent(ws()).spawn("派给没了的卡", "读 e.txt", SubOpts(preset = "attr-gone"))
        assertTrue("要明说没有这张卡，而不是静默按主持人跑：$res", res.indexOf("没有这张角色卡") >= 0)
        assertTrue("错误里要带上是哪个 id，人才对得上：$res", res.contains("attr-gone"))
        assertEquals("被拒的派工不许留下一笔账", before, UsageLedger.pick(kind = UsageLedger.SUB).size)
    }

    @Test
    fun `two dispatches in one team session book two separate rows`() {
        Presets.save(listOf(card("attr-x", "前端老张", "你是前端。"), card("attr-y", "测试小李", "你是测试。")))
        val e = parent(ws())
        e.spawn("老张做页面", "写 a.txt", SubOpts(preset = "attr-x"))
        e.spawn("小李补用例", "写 b.txt", SubOpts(preset = "attr-y"))
        val rows = UsageLedger.pick(kind = UsageLedger.SUB)
            // 账本是整个临时 home 共用的（同 class 里前面几条测试也派过工），
            // 所以只筛这两支卡。第一版没筛，expected 两个名字却量出四个 —— 红在测试自己身上。
            .filter { it.preset == "attr-x" || it.preset == "attr-y" }
        val experts = rows.map { it.expert }.distinct().sorted()
        assertEquals("两次派工要留下两个不同的专家名（第二次盖掉第一次=按专家那张表少一行）",
            listOf("前端老张", "测试小李"), experts)
        assertEquals("两个卡 id 都要在账上", listOf("attr-x", "attr-y"),
            rows.map { it.preset }.distinct().sorted())
    }

    /**
     * 主持人提示里那句"一条接一条"必须与引擎真的做的一致。
     *
     * 上一版写的是「同一回合可以并行派多个成员」，而 `Engine` 那一圈是
     * `for (call in turn.calls)` —— 顺序执行。提示对模型承诺了一件系统不做的事，
     * 症状是"主持人一次派两条然后抱怨慢"，而没人会去查提示与循环谁在说谎。
     * 所以两头一起钉：文案不许出现"并行"，代码那一圈还得是那个 for（真有人并行化了，
     * 这条会红，逼着改文案的人与改引擎的人在同一处碰面）。
     */
    @Test
    fun `coordinator prompt and the tool loop agree that dispatches run one after another`() {
        val persona = teamCoordinatorPersona(
            Team(id = "t1", name = "冲刺小队", members = listOf("attr-x", "attr-y")),
            listOf(card("attr-x", "前端老张", "你是前端。"), card("attr-y", "测试小李", "你是测试。"))
        )
        assertTrue("提示不许承诺并行：回合内的工具是一条接一条跑的", !persona.contains("并行"))
        assertTrue("要说清是接着跑，否则模型会按「同时」来安排工作：$persona",
            persona.contains("一条接一条"))
        val loop = File("src/main/kotlin/com/haoai/pc/Engine.kt").readText()
        assertTrue("引擎那一圈还是顺序的 for（改了这里就要回去改提示）",
            loop.contains("for (call in turn.calls)"))
        assertTrue("别悄悄换成并行执行器而没人改文案",
            !Regex("turn\\.calls\\s*\\.\\s*(parallel|concurrent)|awaitAll|executorService").containsMatchIn(loop))
    }
}
