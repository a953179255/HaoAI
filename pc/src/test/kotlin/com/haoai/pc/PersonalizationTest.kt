package com.haoai.pc

import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * B2/B5/B6 的判据：MBTI 判型、子智能体解析、记忆候选漏斗。
 *
 * 三条主张：
 * 1. **判型要卡题量**（<20 题返回 null——测个型随手点两下就出结果是自欺）；
 * 2. **description 是子智能体的命**（缺它直接跳过，选人依据不能空）；
 * 3. **候选晋升走 Memories.add**（去重与类型映射都复用那条老路，不另写一份记忆语义）。
 */
class PersonalizationTest {

    companion object {
        private lateinit var home: File
        @BeforeClass @JvmStatic
        fun setUp() {
            home = Files.createTempDirectory("haoai-personal-home").toFile()
            System.setProperty("haoai.home", File(home, "home").absolutePath)
        }
        @AfterClass @JvmStatic
        fun tearDown() { home.deleteRecursively() }
    }

    @Test
    fun `mbti scoring needs 20 answers and yields a valid code`() {
        val qs = Mbti.QUESTIONS
        assertEquals(28, qs.size)
        // 全选 A：每维多数极 = 第 1~4 题那组的 ap（前 4 题 ap 为该维第一字母）
        val allA = qs.associate { it.id to 0 }
        val code = Mbti.score(allA)
        assertNotNull("20+ 题必须出型", code)
        assertEquals(4, code!!.length)
        assertTrue(Mbti.profile(code) != null)
        // 少于 20 题拒绝
        assertNull(Mbti.score(allA.entries.take(19).associate { it.key to it.value }))
        // 答题覆盖到未知 id 不炸（脏键不计票）
        val mixed = allA + mapOf("xx9" to 1)
        assertEquals(code, Mbti.score(mixed))
    }

    @Test
    fun `subagent parse requires description`() {
        val ok = Subagents.parse("code-review",
            "---\nname: 代码审查员\ndescription: 审查改动并给问题清单\ncolor: \"#3E6FB0\"\nemoji: 🔍\n---\n\n你是代码审查员。逐文件审查。")
        assertNotNull(ok)
        assertEquals("代码审查员", ok!!.name)
        assertEquals("审查改动并给问题清单", ok.description)
        assertTrue(ok.body.contains("逐文件审查"))
        assertNull(Subagents.parse("bad", "---\nname: 没描述\n---\n正文"))
        assertNull(Subagents.parse("nohead", "没有头的纯正文"))
    }

    @Test
    fun `candidate extract parses leniently and promote dedupes into memories`() {
        // 模型爱加围栏：解析要能穿透 ```json … ```
        val raw = """```json
{"candidates":[{"type":"preference","title":"周报口径","assertion":"用户周报按绿色标完成项","quote":"周报表格用绿色标完成"}],"episode":{"mood":3,"summary":"用户在整理周报"}}
```"""
        val (cands, ep) = MemoryCandidates.parseExtract(raw)
        assertEquals(1, cands.size)
        assertEquals("preference", cands[0].type)
        assertNotNull(ep)
        assertEquals(3, ep!!.first)

        // 晋升：写进产生它的那条工作区
        val ws = Files.createTempDirectory("haoai-cand-ws").toFile()
        MemoryCandidates.addExtracted(listOf(cands[0].copy(ws = ws.absolutePath)), null, "sid1")
        val pend = MemoryCandidates.pending()
        assertTrue(pend.isNotEmpty())
        val id = pend.first().id
        assertTrue(MemoryCandidates.promote(id))
        val doc = Memories.load(Memories.fileFor(ws))
        assertTrue(doc.items.any { it.content.contains("周报") })
        // 同一句话不再进候选（刷屏保护）
        val n = MemoryCandidates.addExtracted(listOf(cands[0].copy(ws = ws.absolutePath)), null, "sid1")
        assertEquals(0, n)
        ws.deleteRecursively()
    }
}
