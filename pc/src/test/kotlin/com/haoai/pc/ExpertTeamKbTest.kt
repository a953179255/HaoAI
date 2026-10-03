package com.haoai.pc

import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 专家卡 / 团队 / 知识库这三块的判据。
 *
 * 拆三段主张：
 * - **专家卡的新字段必须活着落库又活着回来**：desc/icon/color/mbti/quick 少任何一样，
 *   「启用内置专家」就退化成普通的存名字 —— 用户在表单里填的东西悄悄丢了最伤。
 * - **团队只存编制、人设现拼**：主持人的系统提示里要能看见成员名单与派工规矩，
 *   但**不能**把成员人设全文抄进去（那是派工时经 task 的 persona 走的）。
 * - **KB 的语料名是展示名不是寻址名**：路径穿越与野扩展名要在收的时候就挡住。
 */
class ExpertTeamKbTest {

    companion object {
        private lateinit var home: File

        @BeforeClass
        @JvmStatic
        fun setUp() {
            home = Files.createTempDirectory("haoai-expert-home").toFile()
            System.setProperty("haoai.home", File(home, "home").absolutePath)
        }

        @AfterClass
        @JvmStatic
        fun tearDown() {
            home.deleteRecursively()
        }
    }

    // ---- 专家卡新字段：round-trip ----

    @Test
    fun `expert fields survive save and load`() {
        File(Env.home, "presets.json").delete()   // 用例各管各的文件，不背上一个用例的账
        val list = Presets.update(
            Preset(
                id = "ex1", name = "代码工程师", persona = "先读后写，小步修改。",
                model = "gpt-x", workspace = "", mode = "ask",
                desc = "架构 / 评审 / 重构", icon = "💻", color = "#D97A2E",
                mbti = "intj", quick = listOf(
                    QuickPrompt("先读结构", "只看目录与依赖，不动手"),
                    QuickPrompt("排查报错", "", "把这段报错的根因找出来，并给最小复现"))
            )
        )
        assertEquals(1, list.size)
        val back = Presets.find("ex1")!!
        assertEquals("架构 / 评审 / 重构", back.desc)
        assertEquals("💻", back.icon)
        assertEquals("#D97A2E", back.color)
        assertEquals("INTJ", back.mbti)          // 存进来就归一成大写
        assertEquals(listOf("先读结构", "排查报错"), back.quick.map { it.title })
        // 富快捷提问：按钮上的字与真正发出去的那句是两件事，两者都要活着回来
        assertEquals("只看目录与依赖，不动手", back.quick[0].desc)
        assertEquals("把这段报错的根因找出来，并给最小复现", back.quick[1].text())
        assertEquals("先读结构", back.quick[0].text())   // prompt 留空 = 就发标题
        assertEquals("💻", back.avatarChar())     // 头像字符 = icon 优先
        assertEquals(24, Presets.MAX)
    }

    @Test
    fun `old presets json without expert fields still loads`() {
        // 升级路径：老 presets.json 没有这些字段，读进来不能炸、也不能编字段
        File(Env.home, "presets.json").writeText(
            """[{"id":"old1","name":"老卡","persona":"p","model":"","workspace":"","mode":"auto","created":1}]"""
        )
        val back = Presets.find("old1")!!
        assertEquals("老卡", back.name)
        assertEquals("", back.desc)
        assertEquals("", back.icon)
        assertEquals("", back.color)
        assertEquals("", back.mbti)
        assertTrue(back.quick.isEmpty())
        assertTrue(back.avatarChar() == "老")   // 没有 icon 就回落到名字首字
    }

    // ---- 团队：存储与主持人人设 ----

    @Test
    fun `team roundtrip keeps member roster`() {
        Teams.update(Team(id = "t1", name = "冲刺小组", desc = "调研→实现→评审",
            icon = "🚀", color = "#3E7A1E", members = listOf("a", "b")))
        val back = Teams.find("t1")!!
        assertEquals(listOf("a", "b"), back.members)
        assertEquals("冲刺小组", back.name)
        Teams.remove("t1")
        assertNull(Teams.find("t1"))
    }

    @Test
    fun `coordinator persona lists members and rules but not full member personas`() {
        val team = Team(id = "t2", name = "冲锋队", members = listOf("m1", "m2"))
        val members = listOf(
            Preset(id = "m1", name = "代码工程师", persona = "先读后写。".repeat(60), model = "gpt-x",
                workspace = "", mode = "ask", desc = "写代码的", mbti = "INTJ"),
            Preset(id = "m2", name = "调研分析", persona = "多源交叉验证。", model = "",
                workspace = "", mode = "plan", desc = "查资料的")
        )
        val persona = teamCoordinatorPersona(team, members)
        assertTrue("成员名单要在", persona.contains("代码工程师") && persona.contains("调研分析"))
        assertTrue("派工规矩要在（label 用成员名）", persona.contains("label"))
        assertTrue("成员的模型要随派工说明", persona.contains("gpt-x"))
        assertTrue("成员人设只放摘要", !persona.contains("先读后写。".repeat(60)))
        assertTrue("persona 参数要被点到", persona.contains("persona"))
    }

    // ---- KB：语料名的信任边界 ----

    @Test
    fun `kb names reject path tricks and wild extensions`() {
        assertEquals("notes.md", kbSafeName("notes.md"))
        // 穿越**进不来**，不是被拒：函数契约就是"只留最后一个文件名本体"，
        // 落点永远是 .haoai-kb/ 本目录内 —— 所以这里断言的是中性化后的名字
        assertEquals("evil.md", kbSafeName("C:\\evil\\..\\notes.md".replace("notes.md", "evil.md")))
        assertEquals("data.csv", kbSafeName("/x/../data.csv"))
        assertNull(kbSafeName("报告.pdf"))                               // 解析器没有就不收
        assertNull(kbSafeName(".."))                                     // 本体本身就是 ".." 才拒
        assertNull(kbSafeName(".hidden"))
        assertNull(kbSafeName("没有扩展名"))
        assertNull(kbSafeName(""))
    }

    @Test
    fun `semantic index cache can be dropped for rebuild`() {
        val ws = Files.createTempDirectory("haoai-kb-ws").toFile()
        // dropCache 走的是 cacheFile(ws) 那条路：先造出一份缓存文件，再删，再确认没了
        val dir = File(Env.home, "embed-index").apply { mkdirs() }
        val cache = File(dir, SemanticIndex.hashOf(ws.absolutePath.toByteArray(Charsets.UTF_8)) + ".json")
        cache.writeText("""{"ver":1,"dim":4,"files":[]}""")
        assertTrue(cache.isFile)
        SemanticIndex.dropCache(ws)
        assertFalse(cache.exists())
    }
}
