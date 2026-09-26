package com.haoai.pc

import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * S2 权限规则表的回归。
 *
 * 这层的价值全在**优先级**上，所以断言也照着优先级写：
 * plan 压过规则、alwaysAsk 压过 auto、后写的规则压过先写的、规则压过档位。
 * 少测一条，将来就会以"用户写了规则但不生效"或"auto 把该问的跳过了"的形式爆出来。
 */
class PolicyTest {

    private fun tmpWs(): File = Files.createTempDirectory("haoai-pol-ws").toFile().apply { mkdirs() }

    private fun freshStore(): File {
        val f = File.createTempFile("haoai-rules-", ".json")
        Policies.reset(f)
        return f
    }

    private class SpyGate : Gate {
        val asked = mutableListOf<String>()
        var allow = true
        var lastRule: Pair<String, String>? = null
        override fun approve(title: String, detail: String, kind: String): Boolean {
            asked += title
            return allow
        }

        override fun approveRule(
            title: String, detail: String, kind: String, tool: String, pattern: String
        ): Boolean {
            asked += title
            lastRule = tool to pattern
            return allow
        }

        override fun ask(question: String, options: List<String>): String = ""
    }

    private fun ctx(ws: File, mode: String, gate: Gate = SpyGate()) =
        ToolCtx(ws, PcSettings(), mode, gate)

    @Before
    fun setUp() {
        Files.createTempDirectory("haoai-pol-home")
        System.setProperty("haoai.home", Files.createTempDirectory("haoai-pol-home2").toFile().absolutePath)
        freshStore()
    }

    // ---- 命令前缀归约 ----

    @Test
    fun `command prefixes reduce to the rule-worthy head`() {
        assertEquals("git checkout", PolicyStore.commandPrefix("git checkout main"))
        assertEquals("git push", PolicyStore.commandPrefix("git push origin master --force-with-lease"))
        assertEquals("ls", PolicyStore.commandPrefix("ls -la /tmp"))
        assertEquals("npm", PolicyStore.commandPrefix("npm i -g foo"))
        assertEquals("npm install", PolicyStore.commandPrefix("npm install -g foo"))
        assertEquals("remove-item", PolicyStore.commandPrefix("Remove-Item -Recurse -Force x"))
        assertEquals("", PolicyStore.commandPrefix("   "))
    }

    @Test
    fun `rule grammar parses both forms`() {
        val r = Rule.parse("shell(git push*) DENY")
        assertNotNull(r)
        assertEquals("shell", r!!.tool)
        assertEquals("git push*", r.pattern)
        assertEquals(Decision.DENY, r.decision)
        assertEquals(Decision.ALLOW, Rule.parse("write(.env) ALLOW")!!.decision)
        assertNull(Rule.parse("shell(x)"))
    }

    // ---- 规则压过档位 ----

    @Test
    fun `an allow rule skips the prompt in ask mode`() {
        val ws = tmpWs()
        Policies.get().add(ws, Rule("write", "*", Decision.ALLOW))
        val gate = SpyGate()
        assertNull(ctx(ws, "ask", gate).guard("write", "a.txt", "写入文件 a.txt", "1 字符"))
        assertTrue("allow 规则命中后还在问人：${gate.asked}", gate.asked.isEmpty())
    }

    @Test
    fun `a deny rule blocks with the reason the model can act on`() {
        val ws = tmpWs()
        Policies.get().add(ws, Rule("write", "secret.env", Decision.DENY))
        val gate = SpyGate()
        val why = ctx(ws, "auto", gate).guard("write", "secret.env", "写入 secret.env", "x")
        assertNotNull("deny 规则没生效", why)
        assertTrue("理由里没说是哪条规则：$why", why!!.contains("规则拒绝") && why.contains("secret.env"))
        assertTrue("被规则拒了还去问人", gate.asked.isEmpty())
    }

    @Test
    fun `later rules win over earlier ones`() {
        val ws = tmpWs()
        Policies.get().add(ws, Rule("shell", "git*", Decision.ALLOW))
        Policies.get().add(ws, Rule("shell", "git push*", Decision.DENY))
        val v = Policies.get().decide(ws, "shell", "git push origin main")
        assertEquals(Decision.DENY, v?.decision)
        assertEquals(Decision.ALLOW, Policies.get().decide(ws, "shell", "git status")?.decision)
    }

    // ---- 两条红线 ----

    @Test
    fun `alwaysAsk survives auto`() {
        val ws = tmpWs()
        val gate = SpyGate()
        val why = ctx(ws, "auto", gate).guard("shell", "rm -rf build", "执行命令（bash）", "rm -rf build")
        assertNull("gate 返回允许，所以最终应放行", why)
        assertEquals(1, gate.asked.size)
        assertEquals("auto 档位居然跳过了危险命令", "shell" to "rm", gate.lastRule)
    }

    @Test
    fun `force push is caught even though the reduced prefix is just git push`() {
        val ws = tmpWs()
        val gate = SpyGate()
        ctx(ws, "auto", gate).guard("shell", "git push --force origin main", "执行命令（bash）", "git push --force origin main")
        assertEquals("force push 必须问人", 1, gate.asked.size)
    }

    @Test
    fun `plan mode beats even an allow rule`() {
        val ws = tmpWs()
        Policies.get().add(ws, Rule("write", "*", Decision.ALLOW))
        val gate = SpyGate()
        val why = ctx(ws, "plan", gate).guard("write", "a.txt", "写入文件 a.txt", "x")
        assertNotNull("计划模式竟然被规则绕过了", why)
        assertTrue(why!!.contains("计划模式"))
    }

    @Test
    fun `the approval dialog is offered the rule it can remember`() {
        val ws = tmpWs()
        val gate = SpyGate()
        ctx(ws, "ask", gate).guard("shell", "git commit -m hi", "执行命令（bash）", "git commit -m hi")
        // 词典里没给 `git commit` 单列条目（它不属于"改系统/不可逆"那类），所以归约到 `git`
        assertEquals("shell" to "git", gate.lastRule)
    }

    // ---- 隔离与持久 ----

    @Test
    fun `rules are scoped per workspace`() {
        val a = tmpWs()
        val b = tmpWs()
        Policies.get().add(a, Rule("write", "*", Decision.ALLOW))
        assertEquals(1, Policies.get().rules(a).size)
        assertTrue("规则漏到另一个工作区了", Policies.get().rules(b).isEmpty())
    }

    @Test
    fun `rules survive a reload`() {
        val file = freshStore()
        val ws = tmpWs()
        Policies.get().add(ws, Rule("shell", "docker*", Decision.DENY))
        Policies.reset(file)
        val reloaded = Policies.get().rules(ws)
        assertEquals(1, reloaded.size)
        assertEquals(Decision.DENY, reloaded[0].decision)
        assertEquals("docker*", reloaded[0].pattern)
    }

    @Test
    fun `removing a rule restores the mode default`() {
        val ws = tmpWs()
        Policies.get().add(ws, Rule("write", "ok.txt", Decision.ALLOW))
        val gate = SpyGate()
        ctx(ws, "ask", gate).guard("write", "ok.txt", "写入 ok.txt", "x")
        assertTrue(gate.asked.isEmpty())
        Policies.get().remove(ws, "write", "ok.txt")
        ctx(ws, "ask", gate).guard("write", "ok.txt", "写入 ok.txt", "x")
        assertEquals(1, gate.asked.size)
    }

    // ---- 走一遍真引擎：确认规则真的接在回合链路上，不只是单元测试里的孤岛 ----

    @Test
    fun `engine writes without asking once the workspace allows it`() {
        val ws = tmpWs()
        val file = freshStore()
        Policies.get().add(ws, Rule("write", "*", Decision.ALLOW))
        val gate = SpyGate()
        val session = Session("pol-" + System.nanoTime(), ws)
        session.mode = "ask"
        val script = object : ChatClient {
            var n = 0
            override fun chat(m: List<Msg>, t: List<ToolSchema>, onText: (String) -> Unit): AssistantTurn {
                n++
                return if (n == 1) AssistantTurn(
                    "写", listOf(ToolCall("c1", "write", """{"path":"z.txt","content":"z"}""")), Usage(), "tool_calls"
                ) else AssistantTurn("好了", emptyList(), Usage(), "stop")
            }
        }
        val engine = Engine(session, PcSettings(), builtinTools(), gate, {}, script)
        engine.submit("写个 z.txt")
        assertEquals("z", File(ws, "z.txt").readText())
        assertTrue("命中 allow 规则却还在问人：${gate.asked}", gate.asked.isEmpty())
        assertFalse(file.readText().isBlank())
    }

    private fun JsonObject.has(k: String) = this[k] != null
}
