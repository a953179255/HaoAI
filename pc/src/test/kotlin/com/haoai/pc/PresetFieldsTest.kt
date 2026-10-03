package com.haoai.pc

import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 专家卡补齐那一批（对标 Octop 第 1 条）的判据。
 *
 * 三段主张：
 * - **每个字段都要活着落库、活着回来**：`save` / `json` 以前各抄一份字段清单，
 *   漏掉的那处的表现是"表单里填了、重开就没了"（`Schedules` 那次丢的是 `created`）。
 *   现在两处共用一份，这条判据把"共用"钉住 —— 加字段忘了读，这里必红。
 * - **老卡要还能读**：`quick` 从字符串数组变成 `{title,desc,prompt}` 是破坏性改动，
 *   没有迁移的话用户手上的 presets.json 会一夜之间变成空快捷提问。
 * - **哨兵值不能与合法值撞车**：温度 0 是"要确定性输出"，不是"没设"。
 */
class PresetFieldsTest {

    companion object {
        private lateinit var home: File

        @BeforeClass
        @JvmStatic
        fun setUp() {
            home = Files.createTempDirectory("haoai-preset-fields").toFile()
            System.setProperty("haoai.home", File(home, "home").absolutePath)
        }

        @AfterClass
        @JvmStatic
        fun tearDown() {
            home.deleteRecursively()
        }
    }

    private fun fresh() = File(Env.home, "presets.json").delete()

    private fun full() = Preset(
        id = "pf1", name = "口播稿", persona = "短句、有节奏。", model = "m1", workspace = "",
        mode = "ask", desc = "直播口述整理", icon = "🎙", color = "#112233", mbti = "ENFP",
        quick = listOf(
            QuickPrompt("理顺这段", "只调语序与断句，不改词", "把下面这段口述理顺，保留用词："),
            QuickPrompt("起三个标题")
        ),
        kbs = listOf("kbA"), enabled = false, welcome = "把口述贴进来就行。",
        temperature = 0.0, maxTokens = 1024, maxTurns = 7, created = 1234567L
    )

    @Test
    fun `every field survives save and load`() {
        fresh()
        val p = full()
        Presets.update(p)
        val back = Presets.find("pf1")!!
        // 逐字段比，不用 assertEquals(p, back)：整体比一次红，看不出是哪个字段丢的
        assertEquals(p.name, back.name)
        assertEquals(p.persona, back.persona)
        assertEquals(p.model, back.model)
        assertEquals(p.mode, back.mode)
        assertEquals(p.desc, back.desc)
        assertEquals(p.icon, back.icon)
        assertEquals(p.color, back.color)
        assertEquals(p.mbti, back.mbti)
        assertEquals(p.quick, back.quick)
        assertEquals(p.kbs, back.kbs)
        assertEquals(p.enabled, back.enabled)
        assertEquals(p.welcome, back.welcome)
        assertEquals(p.temperature, back.temperature, 1e-9)
        assertEquals(p.maxTokens, back.maxTokens)
        assertEquals(p.maxTurns, back.maxTurns)
        assertEquals(p.created, back.created)
    }

    @Test
    fun `the api hands out every field the form can fill`() {
        fresh()
        Presets.update(full())
        val j = Presets.json()
        for (k in listOf("welcome", "enabled", "temperature", "maxTokens", "maxTurns",
            "quick", "title", "desc", "prompt", "kbs", "created"))
            assertTrue("接口没带 $k —— 表单打开就是空的", j.contains("\"$k\""))
        assertTrue("前端要知道哨兵是几，别把 -1 当值填进输入框", j.contains("noOverride"))
        // 快捷提问发的是对象，不是拼起来的字符串
        assertTrue(j.contains("""{"title":"理顺这段""""))
    }

    @Test
    fun `legacy string quick prompts still load`() {
        fresh()
        File(Env.home, "presets.json").writeText(
            """[{"id":"old1","name":"老卡","persona":"", "quick":["先看目录","再跑测试"]}]"""
        )
        val back = Presets.find("old1")!!
        assertEquals(listOf("先看目录", "再跑测试"), back.quick.map { it.title })
        // 老卡没有 prompt：发出去的就是标题本身
        assertEquals("先看目录", back.quick[0].text())
        // 缺省字段一律走默认值，不是 null / 0
        assertTrue(back.enabled)
        assertEquals(Preset.NO_OVERRIDE, back.maxTokens)
        assertEquals(Preset.NO_OVERRIDE.toDouble(), back.temperature, 1e-9)
    }

    @Test
    fun `defaults mean the card does not touch global settings`() {
        fresh()
        val p = Preset(id = "d1", name = "n", persona = "", model = "", workspace = "", mode = "")
        assertEquals("默认值必须等于哨兵", Preset.NO_OVERRIDE, p.maxTokens)
        assertEquals(Preset.NO_OVERRIDE.toDouble(), p.temperature, 1e-9)
        val s = PcSettings()
        assertEquals(s, Presets.applyTo(p, s))
    }

    @Test
    fun `applyTo only moves the fields the card set`() {
        fresh()
        val p = Preset(
            id = "d2", name = "n", persona = "", model = "", workspace = "", mode = "",
            temperature = 0.0, maxTokens = 512
        )
        val s = PcSettings()
        val n = Presets.applyTo(p, s)
        // 温度 0 是"要确定性输出"，不是"没设" —— 这条就是哨兵选 -1 的理由
        assertEquals(0.0, n.temperature, 1e-9)
        assertEquals(512, n.maxTokens)
        assertEquals("没设的那项要沿用全局", s.maxTurns, n.maxTurns)
        assertTrue("覆盖过就该是新对象", n !== s)
    }

    @Test
    fun `a disabled card is refused by name`() {
        fresh()
        Presets.update(full().copy(enabled = false))
        assertNull(Presets.usable(""))
        val why = Presets.usable("pf1")
        assertNotNull("关着的卡必须给出原因", why)
        assertTrue("要说清去哪打开：" + why, why!!.contains("已经关掉") && why.contains("专家"))
        Presets.update(full().copy(enabled = true))
        assertNull(Presets.usable("pf1"))
        assertTrue((Presets.find("pf1")!!.enabled))
        val gone = Presets.usable("nope")
        assertNotNull(gone)
        assertTrue("丢了的卡要说的是没了：" + gone, gone!!.contains("没有这个角色卡"))
    }

    @Test
    fun `duplicate takes a fresh id and keeps everything else`() {
        fresh()
        val p = full()
        Presets.update(p)
        val c = Presets.duplicate(p)
        assertFalse(c.id == p.id)
        assertTrue(c.name.contains("副本"))
        assertEquals(p.persona, c.persona)
        assertEquals(p.quick, c.quick)
        assertEquals(p.kbs, c.kbs)
        assertEquals(p.temperature, c.temperature, 1e-9)
        // 复制出来就该是能用的那张：关掉的是原卡的状态，不是"角色"的一部分
        assertTrue(c.enabled)
    }

    @Test
    fun `export then import round-trips with a new id`() {
        fresh()
        val p = full().copy(enabled = true)
        Presets.update(p)
        val (got, err) = Presets.importJson(Presets.exportJson(p))
        assertNull(err)
        assertNotNull(got)
        assertEquals(p.name, got!!.name)
        assertEquals(p.quick, got.quick)
        assertEquals(p.welcome, got.welcome)
        assertFalse("导入必须换 id，否则会把现有那张覆盖掉", got.id == p.id)
        assertEquals(1, Presets.load().size)
        Presets.update(got)
        assertEquals(2, Presets.load().size)
    }

    @Test
    fun `import refuses garbage instead of making an empty card`() {
        fresh()
        for (bad in listOf("", "not json", "[]", "{}", """{"name":""}""")) {
            val (p, err) = Presets.importJson(bad)
            assertNull("这种输入不该造出卡：" + bad, p)
            assertNotNull("且要说明为什么：" + bad, err)
        }
        assertEquals(0, Presets.load().size)
    }
}
