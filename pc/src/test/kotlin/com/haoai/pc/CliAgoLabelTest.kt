package com.haoai.pc

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `lan status` / `lan devices` 那两行"手机上次活动"的可读性判据。
 *
 * 为什么值得钉：这两行是**装机复测唯一在读的数**——10-02 真机复测要看的是"心跳离现在几秒"，
 * 而它打出来的是 `上次 1790924465189`。人得先心算两个十三位数才判断得出"轮询到底死没死"，
 * 于是那一轮差点被判成"设备被 ROM 回收"。这属于**功能没坏、读数坏了**：
 * 断言不能只写"有输出"，要写"输出是人能直接判断的话"。
 */
class CliAgoLabelTest {

    // 固定 now，免得用例跑到午夜或跨时区时自己漂
    private val now = 1_790_924_465_000L

    private fun ago(ms: Long) = agoLabel(ms, now)

    @Test fun `从没活动过就说从没`() {
        assertEquals("从没", ago(0L))
        assertEquals("从没", ago(-1L))   // 盘上脏值不许变成"1790924465065 秒前"
    }

    @Test fun `还没到未来的时刻按零秒算，不出现负数`() {
        assertEquals("0 秒前", ago(now + 60_000L))
        assertFalse(ago(now + 60_000L).contains("-"))
    }

    @Test fun `秒档`() {
        assertEquals("0 秒前", ago(now))
        assertEquals("10 秒前", ago(now - 10_000L))
        assertEquals("59 秒前", ago(now - 59_000L))
    }

    @Test fun `分档边界：60 秒归分不留在 59 秒以上`() {
        assertEquals("1 分前", ago(now - 60_000L))
        assertEquals("59 分前", ago(now - 3_599_000L))
    }

    @Test fun `小时档`() {
        assertEquals("1 小时前", ago(now - 3_600_000L))
        assertEquals("23 小时前", ago(now - 86_399_000L))
    }

    /** 超过一天再报"500 小时前"就没用了，切成日期。 */
    @Test fun `跨过一天切成日期`() {
        val label = ago(now - 86_400_000L)
        assertFalse("跨过一天还在报小时：$label", label.contains("小时前"))
        assertTrue("日期档没带上月份和日子：$label", Regex("""\d{2}-\d{2} \d{2}:\d{2}""").matches(label))
    }

    /** 最要紧的一条：读数的行里不该再出现原始 epoch 毫秒。 */
    @Test fun `标签里绝不带原始毫秒串`() {
        for (ms in listOf(0L, now - 5_000L, now - 4_000_000L, now - 200_000_000L)) {
            assertFalse("把时间戳原样打出来了：" + ago(ms), ago(ms).contains(ms.toString()))
        }
    }

    @Test fun `默认用当前时间`() {
        // 不传 now 时按 System.currentTimeMillis() 算：刚刚 = 0 秒前，绝不该是"从没"
        assertEquals("0 秒前", agoLabel(System.currentTimeMillis()))
        assertEquals("从没", agoLabel(0L))
    }

    /**
     * 源码级判据：`lan status` / `lan devices` 那两行**必须**过 `agoLabel`。
     *
     * 上面几条只钉住了函数本身；回退发生在调用处（有人把 `${agoLabel(it.lastSeen)}`
     * 改回 `${it.lastSeen}`，单测一条都不会红）。这跟"参数收了没用"是同一类缺陷：
     * 做得对但没接上，只有读产品源码本身才看得见。
     */
    @Test fun `两处上次活动都接在 agoLabel 上`() {
        val main = File("src/main/kotlin/com/haoai/pc/Main.kt")
        assertTrue("找不到 ${main.path}（测试要在 pc/ 目录下跑）", main.isFile)
        val src = main.readText(Charsets.UTF_8)
        assertTrue("上次活动又打原始时间戳了",
            Regex("上次(?:活动)? \\$\\{it\\.lastSeen\\}").findAll(src).toList().isEmpty())
        val wired = Regex("agoLabel\\(it\\.lastSeen\\)").findAll(src).toList()
        assertTrue("lan status / lan devices 两处读数现在只接了 ${wired.size} 处（应为 2 处）",
            wired.size >= 2)
    }
}
