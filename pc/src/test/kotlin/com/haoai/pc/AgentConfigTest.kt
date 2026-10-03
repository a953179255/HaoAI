package com.haoai.pc

import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 每专家配置（[AgentConfigs]）的判据。
 *
 * 三条主张：
 * 1. **critical 关不掉**——写入剔一次、生效（effectiveToolsOff）再剔一次，
 *    两处缺一处，手改 json 就能把引擎整个关死；
 * 2. **两层开关并集**——全局与专家各关各的，谁也不吞谁的；
 * 3. **round-trip**——枢纽里勾的每一个字段都要活着落盘又活着回来（老文件缺字段=默认值）。
 */
class AgentConfigTest {

    companion object {
        private lateinit var home: File

        @BeforeClass
        @JvmStatic
        fun setUp() {
            home = Files.createTempDirectory("haoai-agentcfg-home").toFile()
            System.setProperty("haoai.home", File(home, "home").absolutePath)
        }

        @AfterClass
        @JvmStatic
        fun tearDown() {
            home.deleteRecursively()
        }
    }

    @Test
    fun `fields survive save and load`() {
        AgentConfigs.save(
            AgentConfig(
                presetId = "ex1",
                providerName = "b-ai", baseUrl = "https://gw.example/v1",
                toolsOff = listOf("web_fetch", "shell"),
                skillsOff = listOf("git-commit"),
                subagents = listOf("code-review"),
                personaMbti = "intj",
                personaExtra = "说话先给结论。",
                knowledgeIds = listOf("kb1"),
            )
        )
        val c = AgentConfigs.of("ex1")
        assertEquals("b-ai", c.providerName)
        assertEquals("https://gw.example/v1", c.baseUrl)
        assertEquals(listOf("web_fetch", "shell"), c.toolsOff)
        assertEquals(listOf("git-commit"), c.skillsOff)
        assertEquals(listOf("code-review"), c.subagents)
        assertEquals("INTJ", c.personaMbti)       // 写入口归一成大写
        assertEquals("说话先给结论。", c.personaExtra)
        assertEquals(listOf("kb1"), c.knowledgeIds)
        AgentConfigs.remove("ex1")
        assertEquals("", AgentConfigs.of("ex1").providerName) // 删卡后回到默认
    }

    @Test
    fun `critical tools cannot be saved`() {
        AgentConfigs.save(AgentConfig(presetId = "ex2", toolsOff = listOf("read", "grep", "web_fetch")))
        val c = AgentConfigs.of("ex2")
        assertTrue("critical 必须被剔除", !c.toolsOff.contains("read") && !c.toolsOff.contains("grep"))
        assertTrue("非 critical 要留住", c.toolsOff.contains("web_fetch"))
        AgentConfigs.remove("ex2")
    }

    @Test
    fun `effective tools off is the union of global and per-expert`() {
        val s = Session("t1", File("."))
        s.agentConfig = AgentConfig(presetId = "ex3", toolsOff = listOf("shell"))
        val settings = PcSettings(toolsOff = listOf("web_search"))
        val off = AgentConfigs.effectiveToolsOff(s, settings)
        assertTrue(off.contains("shell") && off.contains("web_search"))
        // 两层都写 critical 也进不来
        s.agentConfig = s.agentConfig.copy(toolsOff = listOf("read"))
        val off2 = AgentConfigs.effectiveToolsOff(s, settings.copy(toolsOff = listOf("task")))
        assertTrue(!off2.contains("read") && !off2.contains("task"))
    }

    @Test
    fun `old file without new fields still loads`() {
        // 老版本只写了两个字段：读入不能炸，缺的字段一律默认
        File(Env.home, "agent-configs.json")
            .writeText("""[{"presetId":"old1","personaMbti":"ENTP"}]""")
        val c = AgentConfigs.of("old1")
        assertEquals("ENTP", c.personaMbti)
        assertTrue(c.toolsOff.isEmpty() && c.baseUrl.isEmpty())
    }
}
