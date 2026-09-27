package com.haoai.pc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 运行历史的账本。
 *
 * 判据全部落在"读回来的那一条到底对不对"，不是"文件写没写"：
 * 这份东西存在的唯一理由是**半夜跑的那次能回看**，
 * 记错了时间、丢了触发方式、被一行坏数据拖垮整份，都等于没记。
 */
class RunLedgerTest {

    private fun home(): File {
        val tmp = Files.createTempDirectory("haoai-runs").toFile().apply { mkdirs() }
        System.setProperty("haoai.home", File(tmp, "home").absolutePath)
        return tmp
    }

    private fun add(goal: String, trigger: String = "手动", turns: Int = 3, stopped: Boolean = false) =
        RunLedger.add(
            sid = "pc1", title = "测试会话", goal = goal, trigger = trigger,
            turns = turns, ms = 1234L, stopped = stopped, out = "结论：做完了"
        )

    @Test
    fun `one run one row, newest first`() {
        home()
        add("第一条"); add("第二条"); add("第三条")
        val rows = RunLedger.rows()
        assertEquals("跑三次就该有三条：" + rows.map { it.goal }, 3, rows.size)
        assertEquals("最近的排最前", "第三条", rows.first().goal)
        assertEquals("第三条", rows[0].goal)
        assertEquals("触发方式要留着（定时和手动的区别全在这一列）", "手动", rows[0].trigger)
        assertEquals(3, rows[0].turns)
        assertEquals("1.2s", rows[0].dur)
        assertEquals("跑了 3 轮 · 1.2s", rows[0].verdict)
        assertTrue(rows[0].t > 0L)
    }

    @Test
    fun `stopped runs say so instead of pretending to be failures`() {
        home()
        add("被停掉的那次", turns = 5, stopped = true)
        val r = RunLedger.rows().first()
        assertTrue(r.stopped)
        assertTrue(r.verdict, r.verdict.contains("被停止"))
    }

    @Test
    fun `triggers are kept apart`() {
        home()
        add("手动那次", "手动"); add("定时那次", "定时"); add("重跑那次", "重跑")
        val byGoal = RunLedger.rows().associateBy { it.goal }
        assertEquals("定时", byGoal["定时那次"]?.trigger)
        assertEquals("重跑", byGoal["重跑那次"]?.trigger)
        assertEquals("手动", byGoal["手动那次"]?.trigger)
    }

    @Test
    fun `newlines in the answer do not break the jsonl`() {
        home()
        RunLedger.add("pc1", "标题", "问句", "手动", 2, 50L, false,
            "第一行" + 10.toChar() + "第二行" + 10.toChar() + "第三行")
        val rows = RunLedger.rows()
        assertEquals(1, rows.size)
        // 一行一条是 jsonl 的命根子：结论里带换行必须被压平，否则下一行就不是 JSON 了
        assertTrue(rows[0].out.indexOf(10.toChar()) < 0)
        assertTrue(rows[0].out, rows[0].out.contains("第二行"))
        assertEquals(1, RunLedger.file().readLines().count { it.isNotBlank() })
    }

    @Test
    fun `a corrupt line is skipped, not fatal`() {
        home()
        add("好的第一条")
        RunLedger.file().appendText("这行不是 JSON\n")
        add("好的第二条")
        val rows = RunLedger.rows()
        assertEquals("坏行只该丢掉它自己：" + rows.map { it.goal }, 2, rows.size)
        assertEquals("好的第二条", rows[0].goal)
    }

    @Test
    fun `the ledger prunes itself to the newest KEEP rows`() {
        home()
        // 直接灌一份超长的，再走一次 add 触发清理（比 add 505 次快得多）
        val line = """{"t":1,"sid":"s","title":"t","goal":"g","trigger":"手动","turns":1,"ms":1,"stopped":false,"out":"o"}"""
        RunLedger.file().parentFile?.mkdirs()
        RunLedger.file().writeText((1..RunLedger.KEEP + 40).joinToString(10.toChar().toString()) { line } + 10.toChar())
        add("最新的一条")
        val rows = RunLedger.rows(limit = RunLedger.KEEP + 10)
        assertTrue("该收在 " + RunLedger.KEEP + " 条，实际 " + rows.size, rows.size <= RunLedger.KEEP)
        assertEquals("最新的必须还在", "最新的一条", rows.first().goal)
    }

    @Test
    fun `the api payload has what the panel draws`() {
        home()
        add("做一段视频切片", "定时", turns = 7)
        val o = Json.parseToJsonElement(RunLedger.json()).jsonObject
        assertEquals("true", o["ok"]!!.jsonPrimitive.content)
        val items = o["items"]!!.jsonArray
        assertEquals(1, items.size)
        val it = items[0].jsonObject
        assertEquals("定时", it["trigger"]!!.jsonPrimitive.content)
        assertEquals("做一段视频切片", it["goal"]!!.jsonPrimitive.content)
        assertTrue(it["at"]!!.jsonPrimitive.content, it["at"]!!.jsonPrimitive.content.contains("-"))
        assertEquals("跑了 7 轮 · 1.2s", it["verdict"]!!.jsonPrimitive.content)
        assertEquals("total 要报账本全量而不是这一页", "1", o["total"]!!.jsonPrimitive.content)
    }
}
