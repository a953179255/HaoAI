package com.haoai.pc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 会话改名与删除的回归。
 *
 * 这两件事都是**直接改写用户的历史文件**，所以判据是"没弄坏别的字段"而不是"返回值对不对"：
 * 改名要保住消息、摘要与水位；删除要保住可恢复（移进 `.trash`，不是就地抹掉）。
 */
class SessionIndexTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun setUp() {
            val tmp = Files.createTempDirectory("haoai-sess-home").toFile()
            System.setProperty("haoai.home", File(tmp, "home").absolutePath)
        }
    }

    private fun ws(): File = Files.createTempDirectory("haoai-sess-ws").toFile().apply { mkdirs() }

    /** 造一个有历史的会话：跑一轮真引擎，让落库路径也是真的。 */
    private fun makeSession(id: String): Pair<File, Session> {
        val dir = ws()
        val s = Session(id, dir)
        s.mode = "auto"
        val e = Engine(
            s, PcSettings(permissionMode = "auto"), builtinTools(),
            object : Gate {
                override fun approve(title: String, detail: String, kind: String) = true
                override fun ask(question: String, options: List<String>) = ""
            },
            {},
            object : ChatClient {
                override fun chat(m: List<Msg>, t: List<ToolSchema>, onText: (String) -> Unit) =
                    AssistantTurn("写好了", listOf(ToolCall("w1", "write",
                        """{"path":"a.txt","content":"hello"}""")), Usage(2, 2), "tool_calls")
                    .let { if (m.any { x -> x.role == "tool" }) AssistantTurn("完成", emptyList(), Usage(2, 2), "stop") else it }
            }
        )
        e.submit("建一个 a.txt")
        return Pair(SessionIndex.fileFor(id), s)
    }

    @Test
    fun `rename changes only the title and keeps the rest of the file`() {
        val id = "rn" + System.nanoTime()
        val (f, _) = makeSession(id)
        assertTrue("会话没落库", f.isFile)
        val before = f.readText()

        assertTrue(SessionIndex.rename(id, "  新的标题  "))
        val meta = SessionIndex.read(f)
        assertEquals("新的标题", meta?.title)
        val after = f.readText()
        assertEquals("改名不该改变文件结构", before.count { it == '{' }, after.count { it == '{' })
        assertTrue("历史被改坏了", after.contains("hello"))
        // 只看 title 字段：用户消息里本来就写着"建一个 a.txt"，整文件 contains 是测不准的
        assertEquals("新的标题", Regex("\"title\"\\s*:\\s*\"([^\"]*)\"").find(after)?.groupValues?.get(1))
    }

    @Test
    fun `rename keeps the summary and watermark that compaction wrote`() {
        val id = "rn2" + System.nanoTime()
        val (f, _) = makeSession(id)
        // 手工塞一份摘要进去，模拟"压过的会话"
        val withSummary = f.readText().let {
            it.replaceFirst("{", "{\"summary\":\"【摘要】早期内容\",\"compactedThrough\":9,")
        }
        f.writeText(withSummary)
        assertTrue(SessionIndex.rename(id, "改个名"))
        val reopened = SessionIndex.restore(
            SessionIndex.read(f)!!, ws()
        )
        assertEquals("改个名", reopened.title.get())
        val e = Engine(
            reopened, PcSettings(), builtinTools(),
            object : Gate {
                override fun approve(title: String, detail: String, kind: String) = true
                override fun ask(question: String, options: List<String>) = ""
            },
            {},
            object : ChatClient {
                override fun chat(m: List<Msg>, t: List<ToolSchema>, onText: (String) -> Unit) =
                    AssistantTurn("在", emptyList(), Usage(1, 1), "stop")
            }
        )
        assertNotNull("改名把摘要弄丢了", e.summary)
        assertTrue("摘要内容丢了：${e.summary}", e.summary!!.contains("早期内容"))
        assertEquals("水位丢了", 9, e.compactedCount)
    }

    @Test
    fun `blank or unknown rename is refused`() {
        val id = "rn3" + System.nanoTime()
        val (f, _) = makeSession(id)
        assertFalse("空标题不该被接受", SessionIndex.rename(id, "   "))
        assertFalse(SessionIndex.rename("no-such-id", "x"))
        assertTrue("被拒的改名还是动了文件", f.readText().isNotEmpty())
    }

    @Test
    fun `delete moves the file into trash and the list stops showing it`() {
        val id = "del" + System.nanoTime()
        val (f, _) = makeSession(id)
        assertNotNull(SessionIndex.list(200).firstOrNull { it.id == id })

        assertTrue(SessionIndex.delete(id))
        assertFalse("原文件还在", f.isFile)
        assertTrue("列表里还能看见", SessionIndex.list(200).none { it.id == id })

        val trash = File(Env.sessionsDir, ".trash")
        val moved = trash.listFiles()?.firstOrNull { it.name.endsWith("pc-$id.json") }
        assertNotNull("没进回收目录（那就是真删了）", moved)
        assertTrue("回收的那份是空的", moved!!.length() > 10)
    }

    @Test
    fun `deleting an unknown session says so instead of pretending`() {
        assertFalse(SessionIndex.delete("never-existed-" + System.nanoTime()))
    }

    /**
     * 重开会话的字段级回归。
     *
     * 落盘写了 name、读取时漏了 name —— 这种不对称在"能跑完"这件事上完全看不出来，
     * 但界面上是两样东西一起坏：工具卡全变成匿名的 "tool"，而"↩ 退回上一版"要靠
     * name 才能把路径配对上，于是整排按钮消失。pt/ct/ms 同理（每回合那行小字）。
     */
    @Test
    fun `reopening a session keeps each message's name and per-turn stats`() {
        val id = "rn4" + System.nanoTime()
        val (f, _) = makeSession(id)
        val e = Engine(
            SessionIndex.restore(SessionIndex.read(f)!!, ws()), PcSettings(), builtinTools(),
            object : Gate {
                override fun approve(title: String, detail: String, kind: String) = true
                override fun ask(question: String, options: List<String>) = ""
            },
            {},
            object : ChatClient {
                override fun chat(m: List<Msg>, t: List<ToolSchema>, onText: (String) -> Unit): AssistantTurn =
                    throw AssertionError("构造引擎不该问模型")
            }
        )
        val msgs = e.messages()
        val tool = msgs.filter { it.role == "tool" }
        assertTrue("这份历史里没有工具消息，测不到东西", tool.isNotEmpty())
        assertEquals("重开后工具消息丢了 name", listOf("write"), tool.map { it.name })
        val a = msgs.first { it.role == "assistant" }
        assertTrue("重开后每回合的 token 统计丢了：pt=${a.pt} ct=${a.ct} ms=${a.ms}", a.pt > 0 && a.ct > 0)
        assertEquals("重开后 assistant 的 tool_calls 丢了", 1, a.calls.size)
    }
}
