package com.haoai.pc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 录屏的判据。
 *
 * 重心不在"能不能起 ffmpeg"，而在两件容易悄悄坏掉的事：
 * ① 停止必须走 stdin 的 `q` —— 硬杀会留下一个"存在但放不出来"的 mp4，
 *    那种文件比报错更糟（用户以为录到了）；
 * ② 审批被拒时不许已经把进程起起来。
 * ① 用一个假的 ffmpeg（一个读 stdin 的 .cmd）来验，**不依赖桌面能不能抓**：
 *    锁屏时 gdigrab 会失败，那种时候"必须录成功"的测试就会变成随机红。
 */
class RecordTest {

    private class YesGate : Gate {
        override fun approve(title: String, detail: String, kind: String) = true
        override fun ask(question: String, options: List<String>) = ""
    }

    private class NoGate : Gate {
        override fun approve(title: String, detail: String, kind: String) = false
        override fun ask(question: String, options: List<String>) = ""
    }

    private fun ctx(dir: File, gate: Gate = YesGate(), mode: String = "auto") =
        ToolCtx(dir, PcSettings(), mode, gate)

    private fun args(s: String): kotlinx.serialization.json.JsonObject =
        Json.parseToJsonElement(s).jsonObject

    /** 每条测试一个状态根 + 一个工作区（父目录放假 ffmpeg 与种子文件）。 */
    private fun ws(): File {
        val home = Files.createTempDirectory("haoai-rec").toFile()
        File(home, "state").mkdirs()
        System.setProperty("haoai.home", File(home, "state").absolutePath)
        return File(home, "ws").apply { mkdirs() }
    }

    @Test
    fun `the argv is a plain desktop grab with a faststart mp4 out`() {
        val out = File(ws(), "a.mp4")
        val a = RecordTool.build("ffmpeg.exe", 15, "", "", 30, out)
        assertTrue(a.toString(), a.contains("gdigrab"))
        assertTrue(a.toString(), a.contains("desktop"))
        assertTrue("不录声音要显式 -an，否则 ffmpeg 会去抓默认设备", a.contains("-an"))
        assertTrue(a.toString(), a.containsAll(listOf("-movflags", "+faststart")))
        assertEquals("最后一个参数才是输出路径", out.absolutePath, a.last())
        assertEquals("上限要发给 ffmpeg（-t）", "30", a[a.indexOf("-t") + 1])
    }

    @Test
    fun `a region and an audio device both reach the argv`() {
        val a = RecordTool.build("ffmpeg.exe", 24, "10,20,640x360", "麦克风 (Realtek)", 60, File(ws(), "b.mp4"))
        assertEquals("10", a[a.indexOf("-offset_x") + 1])
        assertEquals("20", a[a.indexOf("-offset_y") + 1])
        assertEquals("640x360", a[a.indexOf("-video_size") + 1])
        assertTrue("设备名整串交给 dshow，不经 shell", a.contains("audio=麦克风 (Realtek)"))
        assertTrue(a.toString(), a.contains("-c:a"))
        assertFalse("有音频时不该带 -an", a.contains("-an"))
    }

    @Test
    fun `a malformed area is refused with a sentence`() {
        val r = RecordTool().runB(args("""{"action":"start","area":"all"}"""), ctx(ws()))
        assertTrue(r.content, r.error)
        assertTrue(r.content, r.content.contains("x,y"))
    }

    @Test
    fun `an audio name with a newline is refused`() {
        val r = RecordTool().runB(args("""{"action":"start","audio":"a\nb"}"""), ctx(ws()))
        assertTrue(r.content, r.error)
        assertTrue(r.content, r.content.contains("设备名"))
    }

    @Test
    fun `no ffmpeg says where to get it instead of a stack trace`() {
        Ffmpeg.ffmpegPath = ""   // 空串 = 强制按"没装"处理
        try {
            val r = RecordTool().runB(args("""{"action":"start"}"""), ctx(ws()))
            assertTrue(r.content, r.error)
            assertTrue("要给出装的地方：" + r.content,
                r.content.contains("winget") || r.content.contains("ffmpeg.org"))
        } finally {
            Ffmpeg.ffmpegPath = null
        }
    }

    @Test
    fun `a refused approval never spawns a process`() {
        // ask 档才会问人；auto 档直接放行，那是设计不是漏
        val r = RecordTool().runB(args("""{"action":"start"}"""), ctx(ws(), NoGate(), "ask"))
        assertTrue(r.content, r.error)
        assertTrue("被拒之后不该有录制在跑", Recordings.list().isEmpty())
    }

    /**
     * 停止走 `q`。假 ffmpeg **只有真读到 stdin 那一行**才写文件并 exit 0，
     * 所以"文件在、退出码 0、能被认成音视频"三样一起成立，才证明 stop 用的是收尾而不是 kill。
     */
    @Test
    fun `stop hands q over stdin and the file it leaves is recognized as a video`() {
        val ws = ws()
        val dir = ws.parentFile
        val seed = File(dir, "seed.bin")
        // 4 字节 box 长度 + "ftypisom"：MediaMime 认 mp4 就是闻这个魔数
        seed.writeBytes(byteArrayOf(0, 0, 0, 0x18) + "ftypisom".toByteArray(Charsets.US_ASCII) + ByteArray(8))
        val script = File(dir, "fake-ffmpeg.cmd")
        script.writeText(
            "@echo off\r\n" + "set /p Q=\r\n" + "if \"%Q%\"==\"\" exit 2\r\n" +
                "copy /b \"%~dp0seed.bin\" \"%~1\" >nul\r\n" + "exit 0\r\n",
            Charsets.US_ASCII
        )
        val out = File(ws, ".haoai-output/media/out.mp4")
        out.parentFile.mkdirs()
        val started = Recordings.start(script.absolutePath,
            listOf(script.absolutePath, out.absolutePath), out, 60)
        assertFalse(started.error ?: "起不来", started.failed)
        Thread.sleep(600)
        val stopped = Recordings.stop(started.id!!)
        assertFalse(stopped.error ?: "停失败", stopped.failed)
        assertTrue("文件要落在盘上", out.isFile && out.length() > 0)
        assertNotNull("产出要能被认成音视频（界面才知道该摆播放器）", MediaMime.sniff(out))
        assertEquals("进程该是自己退的（exit 0），不是被杀的", 0, stopped.exit)
    }

    @Test
    fun `stopping an unknown id is answered in words`() {
        val r = Recordings.stop("nope")
        assertTrue(r.failed)
        assertTrue(r.error ?: "", r.error!!.contains("没有这场录制"))
    }

    @Test
    fun `status with nothing running says so`() {
        val r = RecordTool().runB(args("""{"action":"status"}"""), ctx(ws()))
        assertFalse(r.content, r.error)
        assertTrue(r.content, r.content.contains("没有在录"))
    }
}
