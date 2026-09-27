package com.haoai.pc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 用量账本：按**回合**落账，再按本地日期聚合。
 *
 * 为什么单独测：界面上那行「今天 / 近 7 天 / 全部」以前是拿会话累计数除以 `updated` 现算的，
 * 于是昨天跑掉三万 token、今天只动了一下标题，三万就全算成"今天"。
 * 判据全部对着 `report()` 的 JSON 读，不对着内部变量 —— 界面要的就是这份 JSON。
 */
class UsageLedgerTest {

    companion object {
        @BeforeClass
        @JvmStatic
        fun home() {
            val dir = Files.createTempDirectory("haoai-ledger-home").toFile()
            System.setProperty("haoai.home", File(dir, "home").absolutePath)
        }
    }

    @Before
    fun clear() {
        UsageLedger.file().delete()
        UsageLedger.file().parentFile?.mkdirs()
    }

    private fun report() = Json.parseToJsonElement(UsageLedger.report()).jsonObject

    @Test
    fun `one line per turn and a corrupt line is dropped, not fatal`() {
        UsageLedger.add("m1", "s1", 100, 20, 500, true)
        UsageLedger.file().appendText("{这半行是断电留下的\n")
        UsageLedger.add("m1", "s1", 50, 10, 300, true)
        assertEquals("坏行不该把整本账带崩", 2, UsageLedger.rows().size)
        assertEquals(150, report()["all"]!!.jsonObject["prompt"]!!.jsonPrimitive.int)
    }

    /** 最值钱的一条：今天就是今天，不是"最后更新过的那条会话的全部"。 */
    @Test
    fun `yesterday's spend stays yesterday`() {
        val day = 86400_000L
        UsageLedger.add("昨天的模型", "s1", 30_000, 5_000, 9_000, true)
        // add() 用的是当前时间，这里直接改文件里的 t：判"按日期分桶"对不对只能自己造时间
        val lines = UsageLedger.file().readLines().toMutableList()
        lines[0] = Regex("\"t\":[0-9]+").replace(lines[0]) {
            "\"t\":" + (System.currentTimeMillis() - 10 * day)
        }
        UsageLedger.file().writeText(lines.joinToString("\n") + "\n")
        UsageLedger.add("今天的模型", "s2", 100, 20, 500, true)
        val r = report()
        assertEquals("今天只该有今天那一回合", 1, r["today"]!!.jsonObject["n"]!!.jsonPrimitive.int)
        assertEquals(100, r["today"]!!.jsonObject["prompt"]!!.jsonPrimitive.int)
        assertEquals("近 7 天不含 10 天前那条", 1, r["week"]!!.jsonObject["n"]!!.jsonPrimitive.int)
        assertEquals(2, r["all"]!!.jsonObject["n"]!!.jsonPrimitive.int)
        assertEquals("14 天的柱子必须凑齐 14 根", 14, r["days"]!!.jsonArray.size)
    }

    /** 本地 gguf 的模型名是一整条 Windows 路径，反斜杠不转义就是一份非法 JSON。 */
    @Test
    fun `a windows path as model name still produces valid json`() {
        val win = "G:\\AI\\AI Model\\Agents-A1-4B-Q4_K_M.gguf"
        UsageLedger.add(win, "s1", 10, 5, 100, true)
        val models = report()["models"]!!.jsonArray
        assertEquals(1, models.size)
        assertEquals("反斜杠要原样回来（不是被 JSON 吃掉一层）", win,
            models[0].jsonObject["model"]!!.jsonPrimitive.content)
    }

    @Test
    fun `models are ranked by tokens and the success rate counts failures`() {
        UsageLedger.add("小的", "s1", 10, 5, 100, true)
        UsageLedger.add("大的", "s1", 900, 400, 100, true)
        UsageLedger.add("大的", "s1", 0, 0, 0, false)
        val r = report()
        val names = r["models"]!!.jsonArray.map { it.jsonObject["model"]!!.jsonPrimitive.content }
        assertEquals("花得多的排前面", listOf("大的", "小的"), names)
        assertEquals(0.666, r["okRate"]!!.jsonPrimitive.doubleOrNull ?: -1.0, 0.01)
        assertTrue("平均速度该由有耗时的行算出来",
            (r["tps"]!!.jsonPrimitive.doubleOrNull ?: 0.0) > 0.0)
    }

    @Test
    fun `an empty ledger reports zeros instead of crashing`() {
        val r = report()
        assertEquals(0, r["all"]!!.jsonObject["n"]!!.jsonPrimitive.int)
        assertEquals(0.0, r["okRate"]!!.jsonPrimitive.doubleOrNull ?: -1.0, 0.0)
        assertEquals(14, r["days"]!!.jsonArray.size)
    }
}
