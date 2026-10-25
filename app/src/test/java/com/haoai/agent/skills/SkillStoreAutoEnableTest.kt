package com.haoai.agent.skills

import com.haoai.agent.agent.skills.SkillStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 2026-10-25 候选态退役（对齐 Hermes 自由写 + openclaw 版本化回滚）：
 * agent 沉淀即生效进索引；人工治理 = 停用开关（disabled）+ 版本快照恢复；
 * 存量 validated:false 候选文件唤醒启用。文件 IO 走 @TempDir，不碰 Android 框架。
 */
class SkillStoreAutoEnableTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun initStore() {
        SkillStore.init(tmp.newFolder("brain"))
    }

    // ---------- 沉淀即生效 ----------

    @Test
    fun `agent沉淀含写入执行工具的技能也立即生效进索引`() {
        initStore()
        SkillStore.save(
            "deploy-flow", "部署流程",
            "用 bash 执行 deploy.sh，把结果 write 到 build/deploy.md；必要时 tap 确认弹窗",
            source = "agent"
        )
        val meta = SkillStore.list().first { it.name == "deploy-flow" }
        assertFalse("写入类 agent 技能不再进候选态", meta.disabled)
        assertTrue("沉淀即进系统提示索引", SkillStore.promptIndex().contains("deploy-flow"))
    }

    @Test
    fun `只读技能照常生效`() {
        initStore()
        SkillStore.save("research", "调研", "用 web_search 搜索，再 web_fetch 精读", source = "agent")
        assertTrue(SkillStore.promptIndex().contains("research"))
    }

    // ---------- 停用开关（hermes skills.disabled / pi disable-model-invocation 同语义） ----------

    @Test
    fun `停用出索引_库里仍可查_启用回索引并清reviewNotes`() {
        initStore()
        SkillStore.save("s1", "描述", "正文", source = "agent", reviewNotes = "危险命令")
        assertTrue(SkillStore.promptIndex().contains("s1"))

        assertTrue(SkillStore.setDisabled("s1", true))
        assertFalse("停用后不进索引", SkillStore.promptIndex().contains("s1"))
        assertTrue("库里仍可查", SkillStore.list().any { it.name == "s1" })
        assertEquals("停用不清 reviewNotes", "危险命令", SkillStore.list().first { it.name == "s1" }.reviewNotes)

        assertTrue(SkillStore.setDisabled("s1", false))
        assertTrue(SkillStore.promptIndex().contains("s1"))
        assertEquals("启用视为已阅，清 reviewNotes", null, SkillStore.list().first { it.name == "s1" }.reviewNotes)
    }

    @Test
    fun `停用状态跨覆盖保存保留`() {
        initStore()
        SkillStore.save("s2", "d", "v1", source = "user")
        SkillStore.setDisabled("s2", true)
        SkillStore.save("s2", "d", "v2 改进", source = "agent")
        assertTrue("agent 修订不得替用户解禁", SkillStore.list().first { it.name == "s2" }.disabled)
    }

    // ---------- 存量候选文件唤醒 ----------

    @Test
    fun `存量validated-false候选文件唤醒启用`() {
        initStore()
        val d = File(SkillStore.currentDir(), "legacy-cand").apply { mkdirs() }
        File(d, "SKILL.md").writeText(
            """
            ---
            name: legacy-cand
            description: 旧候选技能
            source: agent
            validated: false
            updatedAt: ${System.currentTimeMillis() - 86_400_000L}
            ---
            旧候选正文
            """.trimIndent()
        )
        val meta = SkillStore.list().first { it.name == "legacy-cand" }
        assertFalse("validated 字段退役，存量候选唤醒", meta.disabled)
        assertTrue(SkillStore.promptIndex().contains("legacy-cand"))
    }

    // ---------- 版本快照（openclaw 式事后回退） ----------

    @Test
    fun `覆盖保存产生快照_恢复上一版_再恢复回新文`() {
        initStore()
        SkillStore.save("snap", "d", "v1 body", source = "user")
        assertFalse("新建无快照", SkillStore.hasVersions("snap"))
        Thread.sleep(5)
        SkillStore.save("snap", "d", "v2 body", source = "user")
        assertTrue(SkillStore.hasVersions("snap"))

        assertTrue(SkillStore.restoreLastVersion("snap"))
        assertTrue("恢复到 v1", SkillStore.view("snap")!!.contains("v1 body"))
        assertTrue(SkillStore.restoreLastVersion("snap"))
        assertTrue("再按一次回到 v2（现文恢复前已再入快照）", SkillStore.view("snap")!!.contains("v2 body"))
    }

    @Test
    fun `恢复保留疗效遥测与frontmatter解析`() {
        initStore()
        SkillStore.save("snap2", "d", "v1", source = "agent")
        Thread.sleep(5)
        SkillStore.save("snap2", "d", "v2", source = "agent")
        // 快照含完整 frontmatter：恢复后 source 等元数据应从快照还原而非丢失
        assertTrue(SkillStore.restoreLastVersion("snap2"))
        val meta = SkillStore.list().first { it.name == "snap2" }
        assertEquals("agent", meta.source)
        assertEquals("恢复后 updatedAt 刷新为当前时间锚点", true, meta.updatedAt > 0)
    }

    @Test
    fun `无快照的技能恢复返回false不误伤`() {
        initStore()
        SkillStore.save("fresh", "d", "body", source = "user")
        assertFalse(SkillStore.restoreLastVersion("fresh"))
        assertFalse(SkillStore.restoreLastVersion("不存在的技能"))
        assertTrue("误恢复不得破坏现文", SkillStore.view("fresh")!!.contains("body"))
    }
}
