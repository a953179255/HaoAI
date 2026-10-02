package com.haoai.pc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 定时任务**指定专家**（对标 Octop 的 `cron_jobs.agent_id`）。
 *
 * 这一批最容易出的三种"看着能用其实不能用"，每种都钉一条：
 * - 字段存进去了但读回来丢了（→ 到点还是裸句子）；
 * - 卡被删了静默降级成"没有角色"（→ 无人值守时用全局默认模型往别的目录里写文件）；
 * - 后端通了但界面上没有那颗下拉、卡片上不显示是谁在跑（→ 用户根本不知道能指定，
 *   也看不出这条任务用的是谁的目录）。
 */
class ScheduleExpertTest {

    companion object {
        @BeforeClass
        @JvmStatic
        fun home() {
            val dir = Files.createTempDirectory("haoai-schedexp-home").toFile()
            System.setProperty("haoai.home", File(dir, "home").absolutePath)
        }
    }

    @Before
    fun clear() {
        Schedules.file().delete()
        Presets.file().delete()
        Schedules.file().parentFile?.mkdirs()
    }

    private fun card(id: String = "ex1", name: String = "巡检", ws: String = "") =
        Preset(id = id, name = name, persona = "你是巡检专家", model = "m-small",
            workspace = ws, mode = "auto", desc = "看日志")

    @Test
    fun `the preset id round-trips through the store`() {
        Schedules.update(Schedule("s1", "早间巡检", "看一眼日志", kind = "daily", at = "08:00", preset = "ex1"))
        val back = Schedules.load().single()
        assertEquals("ex1", back.preset)
    }

    /** 老文件（没有这一键）必须照常读：不能因为加了一列就把用户已有的排期清空。 */
    @Test
    fun `a schedules json from before this field still loads`() {
        Schedules.file().writeText(
            """[{"id":"old1","name":"旧任务","prompt":"跑一下","kind":"interval","every":60,"at":"09:00",""" +
            """"days":"","runAt":0,"flow":"","created":1000,"enabled":true,"lastRun":0,"lastSid":"",""" +
            """"lastError":""}]""")
        val s = Schedules.load().single()
        assertEquals("", s.preset)
        assertEquals("created 不能被读成 0（interval 的第一次就靠它定基准）", 1000L, s.created)
    }

    /**
     * `save()` 里**每个字段都要写出去**。
     *
     * 这条是本轮自己踩出来的：加 `preset` 时把 `"created":${s.created}` 顶掉了，
     * 于是所有新建任务的 `created` 读回来都是"现在"，`interval` 的第一次触发被无限往后推 ——
     * 而单测如果只断"preset 在不在"，这条会一路绿到用户发现"到点没跑"。
     */
    @Test
    fun `save writes every field load reads back`() {
        val s = Schedule(
            id = "full", name = "整条", prompt = "整句", kind = "weekly", every = 30,
            at = "07:30", days = "1,3", runAt = 123L, flow = "wf9", preset = "ex7",
            created = 456L, enabled = false, lastRun = 789L, lastSid = "pc1", lastError = "错了一下"
        )
        Schedules.update(s)
        assertEquals("整条存一圈回来必须一个字段都不少", s, Schedules.load().single())
    }

    @Test
    fun `a live card resolves to itself and a missing one refuses to run`() {
        Presets.update(card())
        val ok = scheduleExpert(Schedule("s", "n", "p", preset = "ex1"))
        assertNull(ok.second)
        assertEquals("ex1", ok.first?.id)

        val none = scheduleExpert(Schedule("s", "n", "p", preset = "gone"))
        assertNotNull("卡被删了要给一句话，不能只给 null", none.second)
        assertTrue("要说清是专家卡的事：" + none.second, none.second!!.contains("专家卡"))
        assertNull(none.first)
    }

    /** 没指定 = 合法的"用全局默认"，不是错误。 */
    @Test
    fun `no preset is not an error`() {
        val r = scheduleExpert(Schedule("s", "n", "p"))
        assertNull(r.first)
        assertNull(r.second)
    }

    // ---- 线有没有接上（读产品源码本身）----

    @Test
    fun `the server validates the card and hands it to the run`() {
        val h = File("src/main/kotlin/com/haoai/pc/ServerAutomation.kt")
        assertTrue("找不到 " + h.path, h.isFile)
        val src = h.readText(Charsets.UTF_8)
        assertTrue("POST 要读 preset 并拒掉不存在的卡",
            src.contains("b.str(\"preset\")") && src.contains("Presets.find(presetId) == null"))
        assertTrue("存进去的 Schedule 要带上 preset", Regex("""preset = presetId""").containsMatchIn(src))
        assertTrue("三条对外读数都要露出专家（卡片与下拉要回填）",
            listOf("\"preset\":", "\"presetName\":", "\"presetMissing\":").all { src.contains(it) })
        val run = src.substringAfter("internal fun WebServer.runSchedule")
        assertTrue("跑之前先解析卡", run.contains("scheduleExpert(item)"))
        assertTrue("解析失败要把原因记在 lastError 上", run.contains("item.lastError = perr"))
        assertTrue("起会话时要把卡传进去（不然解析了个寂寞）", run.contains("preset = card"))
        // 顺序判据：lastRun 要在卡解析**之后**才写死 —— 否则卡没了也把这次算成"跑过了"，下一槽被推走
        assertTrue("卡不在时要把 lastRun 退回 0，修好卡下一槽照常触发",
            run.indexOf("item.lastRun = 0L") < run.indexOf("val sid = try"))
    }

    @Test
    fun `startRun applies the card to the fresh session including its directory`() {
        val src = File("src/main/kotlin/com/haoai/pc/Server.kt").readText(Charsets.UTF_8)
        assertTrue("startRun 要收 preset", Regex("""preset: Preset\? = null""").containsMatchIn(src))
        assertTrue("新建那条会话要走带卡的 newSessionId（人设/模型/档位都在里面）",
            Regex("""newSessionId\([\s\S]{0,120}?preset\)[\s\S]{0,40}?\}""").containsMatchIn(src))
        assertTrue("卡上的目录要用它（定时任务「每天整理那个仓库」靠的就是这一句）",
            src.contains("Presets.workspaceOf(it)"))
    }

    @Test
    fun `the interface can pick the expert and shows who runs each job`() {
        val ui = File("src/main/resources/ui/index.html").readText(Charsets.UTF_8)
        assertTrue("定时表单要有专家下拉", ui.contains("""id="crPreset"""))
        assertTrue("下拉要有「不指定」这一项（不指定是合法选择，不是空值）",
            ui.contains("不指定专家"))
        assertTrue("加任务时要把 preset 发出去",
            ui.contains("preset:($('#crPreset')||{}).value"))
        assertTrue("右栏每一行要显示是谁在跑", Regex("""专家：'\+esc\(s\.presetName\)""").containsMatchIn(ui))
        assertTrue("日程整页的卡片也要显示（两处都得看得见，不能只在一处）",
            Regex("""presetMissing\?""").findAll(ui).count() >= 2)
        assertTrue("下拉要在读到排期后刷新（新存的卡立刻可选）", ui.contains("loadCronExperts()"))
    }
}
