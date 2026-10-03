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
 * 「默认专家」那张卡（对标 Octop 专家卡上那枚"默认"徽标）。
 *
 * 三段主张，缺一都会留下看得见的问题：
 * 1. 这个位**存得下也读得回**（`fields`/`parse` 两处都要有，漏一处的表现是
 *    "设了默认，重启之后默认没了"）；
 * 2. **一次只许有一张**默认：设 B 要把 A 清掉，否则点「新对话」用哪张全看文件顺序；
 * 3. 「新对话该挂哪张」这条决策（[Presets.presetForNewSession]）的每个分支都要钉住 ——
 *    尤其是"默认卡被关了/被删了"要**回落到没挂专家**，而不是静默换成别的卡。
 */
class DefaultPresetTest {

    companion object {
        @BeforeClass
        @JvmStatic
        fun setUp() {
            val tmp = Files.createTempDirectory("haoai-defhome").toFile()
            System.setProperty("haoai.home", File(tmp, "home").absolutePath)
        }
    }

    private fun card(id: String, name: String, enabled: Boolean = true): Preset {
        val p = Preset(id = id, name = name, persona = "你是$name，回答要短。",
            model = "", workspace = "", mode = "ask", enabled = enabled)
        Presets.update(p)
        return p
    }

    @Test
    fun `the default flag survives a save and a reload`() {
        File(Env.home, "presets.json").delete()
        val a = card("dA", "默认甲")
        assertEquals("", Presets.defaultId())
        Presets.setDefault(a.id)
        assertEquals("默认甲", Presets.find("dA")!!.name)
        assertEquals(a.id, Presets.defaultId())
        // 关着的那位不许被算成默认
        assertTrue(Presets.find(a.id)!!.defaultOne)
    }

    @Test
    fun `setting a new default clears the old one`() {
        File(Env.home, "presets.json").delete()
        val a = card("dB", "甲")
        val b = card("dC", "乙")
        Presets.setDefault(a.id)
        Presets.setDefault(b.id)
        assertEquals("一次只许有一张默认", "dC", Presets.defaultId())
        assertFalse("上一张默认没被清掉：两张默认会让「新对话」用哪张全看文件顺序",
            Presets.find("dB")!!.defaultOne)
        assertTrue(Presets.find("dC")!!.defaultOne)
        // 取消默认：id 给空串
        Presets.setDefault("")
        assertEquals("", Presets.defaultId())
        assertFalse(Presets.find("dC")!!.defaultOne)
    }

    @Test
    fun `pointing the default at a card that is not there is refused`() {
        File(Env.home, "presets.json").delete()
        card("dD", "在的")
        val (_, err) = Presets.setDefault("没有这张卡")
        assertTrue("要明说为什么拒：" + err, err.contains("没有这个角色卡"))
        assertEquals("", Presets.defaultId())
    }

    @Test
    fun `new-session card choice prefers the clicked card and never swaps in another`() {
        File(Env.home, "presets.json").delete()
        val a = card("dE", "开着甲")
        val b = card("dF", "关着乙", enabled = false)
        // ① 用户点了某张 → 永远用点的那张，哪怕设了默认
        Presets.setDefault(a.id)
        assertEquals("dF", Presets.presetForNewSession("dF", team = false))
        // ② 团队会话的主持人是显式选的，不许被默认卡顶掉
        assertEquals("", Presets.presetForNewSession("", team = true))
        // ③ 谁都没点 → 落到默认
        assertEquals(a.id, Presets.presetForNewSession("", team = false))
        // ④ 默认那张被关掉 → 回落到"没挂专家"，而不是静默换一张开着的
        Presets.update(a.copy(enabled = false))
        assertEquals("关着的默认卡不许被静默换成别的卡", "", Presets.presetForNewSession("", team = false))
        // ⑤ 默认那张被删了 → 同样回落，且不留悬空引用
        Presets.update(a.copy(enabled = true))
        Presets.remove(a.id)
        assertEquals("", Presets.presetForNewSession("", team = false))
        // ⑥ 点名要一张关着的卡：这是用户显式选的，必须**拒并说明**，不能悄悄没挂
        assertNotNull(Presets.usable(b.id))
    }
}
