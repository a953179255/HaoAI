package com.haoai.agent.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 批3c：后台任务日志读取层（尾窗/状态标记/投递文本解析）。 */
class JobLogicTest {

    private fun tmpFile(name: String, text: String): File {
        val f = File(System.getProperty("java.io.tmpdir"), "jobtest_$name")
        f.writeText(text)
        f.deleteOnExit()
        return f
    }

    @Test
    fun `尾窗读到全部行`() {
        val f = tmpFile("all.log", "line1\nline2\nline3\n")
        assertEquals(listOf("line1", "line2", "line3"), readTailLines(f))
    }

    @Test
    fun `尾窗截断丢弃残片行`() {
        // 尾部 8 字节从 "line2" 中段起 → 首个换行前的残片丢掉
        val f = tmpFile("cut.log", "aaaaaaaaaaaaaaaaaaaa\nline2\n")
        val tail = readTailLines(f, maxBytes = 8)
        assertEquals(listOf("line2"), tail)
    }

    @Test
    fun `不存在文件空表`() {
        assertTrue(readTailLines(File("G:/nonexistent_dir_xyz/job.log")).isEmpty())
    }

    @Test
    fun `末行DONE标记转已结束并剥行`() {
        val v = parseJobLog(listOf("out1", "out2", "__JOB_DONE_0"))
        assertFalse(v.running)
        assertEquals("0", v.exitCode)
        assertEquals(listOf("out1", "out2"), v.lines)
    }

    @Test
    fun `非末行的DONE标记不算结束`() {
        val v = parseJobLog(listOf("__JOB_DONE_0", "still running"))
        assertTrue(v.running)
    }

    @Test
    fun `空行列表视为运行中`() {
        val v = parseJobLog(emptyList())
        assertTrue(v.running)
        assertEquals("", v.exitCode)
    }

    @Test
    fun `投递文本解析出日志定位`() {
        val root = File("/ws")
        val ref = parseJobDispatch(
            "后台任务已投递：job_abc123\n日志：/workspace/.haoai-jobs/job_abc123.log（用 job_output 工具查看，id 传 \"job_abc123\"）",
            root
        )
        assertEquals(".haoai-jobs/job_abc123.log", ref?.relPath)
        assertTrue(ref!!.absPath.replace('\\', '/').endsWith("/ws/.haoai-jobs/job_abc123.log"))
    }

    @Test
    fun `非投递文本与SAF不给定位`() {
        assertNull(parseJobDispatch("exit=0\n---\nnormal output", File("/ws")))
        // 普通命令回显 job_xxx 文本——没有投递前缀，不认
        assertNull(parseJobDispatch("echo 输出 job_abc123", File("/ws")))
        assertNull(parseJobDispatch("后台任务已投递：job_ABC", File("/ws"))) // 大写不是 base36
        assertNull(parseJobDispatch("后台任务已投递：job_abc", null))
        assertNull(parseJobDispatch(null, File("/ws")))
    }
}
