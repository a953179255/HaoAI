package com.haoai.pc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 媒体工具（ffmpeg）的回归。
 *
 * 分两层，缺一不可：
 * ① **不依赖机器**的判定 —— 时间写法、参数白名单、输出后缀、没装 ffmpeg 时那句话。
 *    这批在任何机器上都能跑，因为工具"说清楚为什么不动"比"动成功了"更要紧。
 * ② **真跑 ffmpeg** —— 转码/抽帧/抽音轨/剪切各来一次，验文件真的长出来了、
 *    魔数认得、路径真的进了给界面的 `media`/`images`。本机装了就用 `assumeTrue` 跳过不了，
 *    没装的机器（CI）就整条跳过，而不是假装通过。
 */
class MediaTest {

    private class SpyGate : Gate {
        val asked = mutableListOf<String>()
        var allow = true
        override fun approve(title: String, detail: String, kind: String): Boolean {
            asked += title
            return allow
        }

        override fun ask(question: String, options: List<String>) = ""
    }

    private fun args(json: String) = Json.parseToJsonElement(json).jsonObject

    private fun ws(): File = Files.createTempDirectory("haoai-media").toFile().apply { mkdirs() }

    /** 本机有没有 ffmpeg；没有就把"真跑"那批整条跳过。 */
    private fun have(): Boolean = Ffmpeg.ffmpeg() != null && Ffmpeg.ffprobe() != null

    /**
     * 五秒、有声、可复现的测试素材（testsrc + sine），中文文件名是刻意的：
     * 素材就在用户的素材库里，名字十有八九是中文 —— 而这条路是 argv 直发，
     * 一旦哪天改成拼 shell 命令，这条测试会第一个红。
     */
    private fun clip(dir: File): File {
        val f = File(dir, "测试素材.mp4")
        val exe = Ffmpeg.ffmpeg() ?: return f
        val r = Ffmpeg.run(
            exe,
            listOf(
                "-y", "-hide_banner", "-loglevel", "error",
                "-f", "lavfi", "-i", "testsrc=duration=5:size=320x240:rate=15",
                "-f", "lavfi", "-i", "sine=frequency=440:duration=5",
                "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac", "-shortest", f.absolutePath
            ),
            dir, 120
        )
        assumeTrue("造测试素材失败：${r.log}", r.ok && f.isFile)
        return f
    }

    private fun probe(f: File): String {
        val exe = Ffmpeg.ffprobe() ?: return ""
        return Ffmpeg.run(
            exe,
            listOf("-v", "error", "-show_entries", "format=duration:stream=codec_type,width,height",
                "-of", "default=noprint_wrappers=1", f.absolutePath),
            f.parentFile, 60
        ).log
    }

    // ---- ① 不依赖机器的那半边 ----

    @Test
    fun `time shapes parse and bad ones are refused`() {
        assertEquals("12", MediaTool.parseTime("12"))
        assertEquals("12.5", MediaTool.parseTime("12.5"))
        assertEquals("65", MediaTool.parseTime("1:05"))
        assertEquals("3723", MediaTool.parseTime("1:02:03"))
        assertEquals("0", MediaTool.parseTime(null))
        assertEquals("0", MediaTool.parseTime("  "))
        assertNull(MediaTool.parseTime("3sec"))
        assertNull(MediaTool.parseTime("1:2:3:4"))
        assertNull(MediaTool.parseTime("-5"))
    }

    @Test
    fun `missing ffmpeg says how to install it`() {
        val keep = Ffmpeg.ffmpegPath
        try {
            Ffmpeg.ffmpegPath = ""      // 空串＝强制按"没装"处理
            val dir = ws()
            // 源文件必须真的存在：工具先验文件再验 ffmpeg，缺一个就只能收到那一句
            File(dir, "a.mp4").writeBytes(byteArrayOf(0, 1, 2, 3))
            val ctx = ToolCtx(dir, PcSettings(), "auto", SpyGate())
            val r = MediaTool().run(args("""{"sub":"transcode","input":"a.mp4"}"""), ctx)
            assertTrue(r.content, r.error)
            // 这句必须"能照着做"：给装法、给环境变量、给重启提示，而不是一个 exit=1
            assertTrue(r.content, r.content.contains("winget"))
            assertTrue(r.content, r.content.contains(Ffmpeg.ENV_DIR))
            assertTrue(r.content, r.content.contains("ffmpeg.org"))
        } finally {
            Ffmpeg.ffmpegPath = keep
        }
    }

    @Test
    fun `bad sub and missing input are actionable`() {
        val ctx = ToolCtx(ws(), PcSettings(), "auto", SpyGate())
        val bad = MediaTool().run(args("""{"sub":"mixtape","input":"a.mp4"}"""), ctx)
        assertTrue(bad.content, bad.error)
        assertTrue(bad.content, bad.content.contains("info/transcode/cut/frame/audio/cover"))
        val noIn = MediaTool().run(args("""{"sub":"info"}"""), ctx)
        assertTrue(noIn.content, noIn.error)
        assertTrue(noIn.content, noIn.content.contains("input"))
    }

    @Test
    fun `plan mode refuses to write media`() {
        assumeTrue("这台机器上没有 ffmpeg", have())
        val dir = ws()
        val src = clip(dir)
        val gate = SpyGate()
        val ctx = ToolCtx(dir, PcSettings(), "plan", gate)
        val r = MediaTool().run(args("""{"sub":"audio","input":"${src.name}"}"""), ctx)
        assertTrue(r.content, r.error)
        assertTrue(r.content, r.content.contains("计划模式"))
        assertTrue("只该问一次都不问", gate.asked.isEmpty())
    }

    @Test
    fun `sniff ignores a text file wearing an mp4 hat`() {
        val f = File(ws(), "伪装.mp4")
        f.writeText("这其实是文本\n")
        assertNull(MediaMime.sniff(f))
    }

    @Test
    fun `range header parses`() {
        assertEquals(0L to 99L, Range.parse(null, 100))
        assertEquals(10L to 29L, Range.parse("bytes=10-29", 100))
        assertEquals(40L to 99L, Range.parse("bytes=40-", 100))
        assertEquals(90L to 99L, Range.parse("bytes=-10", 100))
        // 越界与乱写的都退回"整份"，不能报 500
        assertEquals(0L to 99L, Range.parse("bytes=200-", 100))
        assertEquals(0L to 99L, Range.parse("bytes=abc", 100))
    }

    // ---- ② 真跑 ffmpeg 的那半边 ----

    @Test
    fun `info reports the streams without writing anything`() {
        assumeTrue("这台机器上没有 ffmpeg", have())
        val dir = ws()
        val src = clip(dir)
        val gate = SpyGate()
        val ctx = ToolCtx(dir, PcSettings(), "ask", gate)
        val r = MediaTool().run(args("""{"sub":"info","input":"${src.name}"}"""), ctx)
        assertFalse(r.content, r.error)
        assertTrue(r.content, r.content.contains("codec_type=video"))
        assertTrue(r.content, r.content.contains("codec_type=audio"))
        assertTrue(r.content, r.content.contains("duration=5"))
        assertTrue("info 只读，不该问人：${gate.asked}", gate.asked.isEmpty())
        assertEquals(listOf<String>(), r.media)
    }

    @Test
    fun `frame extracts a real png and hands the pixels over`() {
        assumeTrue("这台机器上没有 ffmpeg", have())
        val dir = ws()
        val src = clip(dir)
        val ctx = ToolCtx(dir, PcSettings(), "auto", SpyGate())
        val r = MediaTool().run(args("""{"sub":"frame","input":"${src.name}","start":"2"}"""), ctx)
        assertFalse(r.content, r.error)
        assertEquals(1, r.images.size)
        val png = File(r.images[0])
        assertTrue(png.name, png.isFile)
        assertEquals("image/png", Images.mime(png))
        assertTrue("抽出来的帧进了 .haoai-output/media：${ctx.rel(png)}", ctx.rel(png).startsWith(".haoai-output/media"))
    }

    @Test
    fun `audio track lands as a playable mp3`() {
        assumeTrue("这台机器上没有 ffmpeg", have())
        val dir = ws()
        val src = clip(dir)
        val ctx = ToolCtx(dir, PcSettings(), "auto", SpyGate())
        val r = MediaTool().run(args("""{"sub":"audio","input":"${src.name}"}"""), ctx)
        assertFalse(r.content, r.error)
        assertEquals(1, r.media.size)
        assertTrue(r.images.isEmpty())
        // 给界面的必须是工作区相对路径：/api/media 只在这条会话的工作区里取字节
        assertFalse("media 里是绝对路径：${r.media[0]}", File(r.media[0]).isAbsolute)
        val mp3 = File(ctx.workspace, r.media[0])
        assertTrue(mp3.name, mp3.isFile && mp3.length() > 1000)
        assertEquals("audio/mpeg", MediaMime.sniff(mp3))
    }

    @Test
    fun `files outside the workspace are not offered to the page`() {
        assumeTrue("这台机器上没有 ffmpeg", have())
        val dir = ws()
        val src = clip(dir)
        val outside = ws()      // 另一个临时目录＝工作区之外，模拟素材库在 D:\ 那种情形
        val ctx = ToolCtx(dir, PcSettings(), "auto", SpyGate())
        // 绝对路径要写成 / 分隔：塞进 JSON 字符串字面量的反斜杠是非法转义，
        // 而 Windows 的 File 与 ffmpeg 都认正斜杠
        val target = File(outside, "产物.mp3").invariantSeparatorsPath
        val r = MediaTool().run(
            args("""{"sub":"audio","input":"${src.name}","output":"$target"}"""), ctx
        )
        assertFalse(r.content, r.error)
        assertTrue("工作区外的文件不该进 media：" + r.media, r.media.isEmpty())
        assertTrue(r.content, r.content.contains("工作区外面"))
        assertTrue("但文件本身要真的转出来：${File(outside, "产物.mp3")}", File(outside, "产物.mp3").isFile)
    }

    @Test
    fun `cut keeps a short piece and says what copy costs`() {
        assumeTrue("这台机器上没有 ffmpeg", have())
        val dir = ws()
        val src = clip(dir)
        val ctx = ToolCtx(dir, PcSettings(), "auto", SpyGate())
        val r = MediaTool().run(
            args("""{"sub":"cut","input":"${src.name}","start":"1","dur":"2","output":"片断.mp4"}"""), ctx
        )
        assertFalse(r.content, r.error)
        val out = File(ctx.workspace, "片断.mp4")
        assertTrue(out.name, out.isFile && out.length() > 0)
        assertEquals("video/mp4", MediaMime.sniff(out))
        // -c copy 只保证"短于原片"，精确到帧是 reencode 的事，所以这里只卡上界
        val d = Regex("duration=([0-9.]+)").find(probe(out))?.groupValues?.get(1)?.toDoubleOrNull() ?: -1.0
        assertTrue("切出来 ${d}s", d > 0 && d < 4.5)
        assertTrue(r.content, r.content.contains("关键帧"))
    }

    @Test
    fun `transcode downscales and writes faststart`() {
        assumeTrue("这台机器上没有 ffmpeg", have())
        val dir = ws()
        val src = clip(dir)
        val ctx = ToolCtx(dir, PcSettings(), "auto", SpyGate())
        val r = MediaTool().run(
            args("""{"sub":"transcode","input":"${src.name}","output":"小.mp4","width":"160"}"""), ctx
        )
        assertFalse(r.content, r.error)
        val out = File(ctx.workspace, "小.mp4")
        assertTrue(out.name, out.isFile)
        assertEquals("video/mp4", MediaMime.sniff(out))
        assertTrue(probe(out), probe(out).contains("width=160"))
        // faststart：moov 必须在文件头，否则浏览器要等整份下完才起播
        val bytes = out.readBytes()
        val at = String(bytes, 0, minOf(2048, bytes.size), Charsets.ISO_8859_1)
        assertTrue("moov 不在前 2 KB：没加 faststart", at.contains("moov"))
    }

    @Test
    fun `bad extension and bad params are refused before ffmpeg`() {
        assumeTrue("这台机器上没有 ffmpeg", have())
        val dir = ws()
        val src = clip(dir)
        val ctx = ToolCtx(dir, PcSettings(), "auto", SpyGate())
        val wrongExt = MediaTool().run(args("""{"sub":"frame","input":"${src.name}","output":"a.mp4"}"""), ctx)
        assertTrue(wrongExt.content, wrongExt.error)
        assertTrue(wrongExt.content, wrongExt.content.contains("只能输出"))
        val badFps = MediaTool().run(args("""{"sub":"transcode","input":"${src.name}","fps":"三十"}"""), ctx)
        assertTrue(badFps.content, badFps.error)
        assertTrue(badFps.content, badFps.content.contains("fps"))
        val badTime = MediaTool().run(args("""{"sub":"cut","input":"${src.name}","start":"3sec"}"""), ctx)
        assertTrue(badTime.content, badTime.error)
        assertTrue(badTime.content, badTime.content.contains("start"))
    }

    @Test
    fun `same file in and out is refused`() {
        assumeTrue("这台机器上没有 ffmpeg", have())
        val dir = ws()
        val src = clip(dir)
        val ctx = ToolCtx(dir, PcSettings(), "auto", SpyGate())
        val r = MediaTool().run(args("""{"sub":"cut","input":"${src.name}","output":"${src.name}"}"""), ctx)
        assertTrue(r.content, r.error)
        assertTrue(r.content, r.content.contains("同一个文件"))
        assertTrue("源文件必须还在", src.isFile)
    }

    // ---- 字幕 / 标题 / 拼接（视频创作那条链）----

    @Test
    fun `srt writes the exact shape players accept`() {
        val dir = ws()
        val ctx = ToolCtx(dir, PcSettings(), "auto", SpyGate())
        val items = """[{"start":"0","end":"2.5","text":"第一句：中文，带冒号 : 也无妨"},""" +
            """{"start":"1:05","end":"1:07.25","text":"第二句"}]"""
        val r = MediaTool().run(args("""{"sub":"srt","items":${json(items)}}"""), ctx)
        assertFalse(r.content, r.error)
        val f = File(dir, ".haoai-output/media/字幕.srt")
        assertTrue("字幕没落到默认位置：${r.content}", f.isFile)
        val text = f.readText(Charsets.UTF_8)
        // 序号从 1、毫秒前是**逗号**、块之间空一行 —— 任一处写错，播放器整条字幕不显示
        assertTrue(text, text.contains("1\n00:00:00,000 --> 00:00:02,500\n第一句"))
        assertTrue(text, text.contains("2\n00:01:05,000 --> 00:01:07,250\n第二句"))
        assertTrue("块之间必须空一行", text.contains("\n\n2\n"))
    }

    /** 把一段 JSON 塞进另一段 JSON 的字符串字段里：引号要转义，测试自己也得合法。 */
    private fun json(raw: String): String = "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    @Test
    fun `srt refuses bad cues and needs force to overwrite`() {
        val dir = ws()
        val ctx = ToolCtx(dir, PcSettings(), "auto", SpyGate())
        val badEnd = MediaTool().run(args("""{"sub":"srt","items":${json("""[{"start":"3","end":"2","text":"倒着走"}]""")}}"""), ctx)
        assertTrue(badEnd.content, badEnd.error)
        assertTrue(badEnd.content, badEnd.content.contains("不晚于"))
        val notArray = MediaTool().run(args("""{"sub":"srt","items":"第一句"}"""), ctx)
        assertTrue(notArray.content, notArray.error)
        val empty = MediaTool().run(args("""{"sub":"srt","items":"[]"}"""), ctx)
        assertTrue(empty.content, empty.error)

        val ok = MediaTool().run(args("""{"sub":"srt","output":"cap.srt","items":${json("""[{"start":"0","end":"1","text":"一句"}]""")}}"""), ctx)
        assertFalse(ok.content, ok.error)
        val again = MediaTool().run(args("""{"sub":"srt","output":"cap.srt","items":${json("""[{"start":"0","end":"1","text":"改了"}]""")}}"""), ctx)
        assertTrue("已存在的字幕默认不许盖", again.error)
        assertTrue(again.content, again.content.contains("force"))
        val forced = MediaTool().run(args("""{"sub":"srt","output":"cap.srt","force":true,"items":${json("""[{"start":"0","end":"1","text":"改了"}]""")}}"""), ctx)
        assertFalse(forced.content, forced.error)
        assertTrue(File(dir, "cap.srt").readText(Charsets.UTF_8).contains("改了"))
    }

    @Test
    fun `the new subcommands say what they still need`() {
        assumeTrue("这台机器上没有 ffmpeg", have())
        val dir = ws()
        val src = clip(dir)
        val ctx = ToolCtx(dir, PcSettings(), "auto", SpyGate())
        listOf(
            """{"sub":"caption","input":"${src.name}"}""" to "text",
            """{"sub":"subtitle","input":"${src.name}"}""" to "subs",
            """{"sub":"subtitle","input":"${src.name}","subs":"没有这个.srt"}""" to "没有这个字幕文件",
            """{"sub":"join","input":"${src.name}"}""" to "input2",
            """{"sub":"join","input":"${src.name}","input2":"没这第二段.mp4"}""" to "没有这第二个文件",
            """{"sub":"caption","input":"${src.name}","text":"标题","pos":"中间"}""" to "pos"
        ).forEach { (body, want) ->
            val r = MediaTool().run(args(body), ctx)
            assertTrue("$body ⇒ ${r.content}", r.error)
            assertTrue("$body 该说到「$want」：${r.content}", r.content.contains(want))
        }
    }

    @Test
    fun `caption really paints a title over the frame`() {
        assumeTrue("这台机器上没有 ffmpeg", have())
        val dir = ws()
        val src = clip(dir)
        val ctx = ToolCtx(dir, PcSettings(), "auto", SpyGate())
        val plain = MediaTool().run(args("""{"sub":"cover","input":"${src.name}","output":"a.png","start":"1"}"""), ctx)
        assertFalse(plain.content, plain.error)
        val cap = MediaTool().run(
            args("""{"sub":"caption","input":"${src.name}","output":"b.png","start":"1","text":"第三期 : 冒号也要能画"}"""), ctx)
        assertFalse("caption 没跑成：${cap.content}", cap.error)
        val a = File(dir, "a.png"); val b = File(dir, "b.png")
        assertTrue("两张图都在", a.isFile && b.isFile)
        assertTrue("标题没改变任何一个像素 = 文字其实没画上去",
            !a.readBytes().contentEquals(b.readBytes()))
        assertTrue("图要递回给模型看", cap.images.isNotEmpty())
        assertTrue("给过滤串的 textfile 要在审批之后才落盘", File(dir, "b.txt").isFile)
    }

    @Test
    fun `srt then subtitle burns in and keeps the length`() {
        assumeTrue("这台机器上没有 ffmpeg", have())
        val dir = ws()
        val src = clip(dir)
        val ctx = ToolCtx(dir, PcSettings(), "auto", SpyGate())
        val s = MediaTool().run(args(
            """{"sub":"srt","output":"字幕.srt","input":"${src.name}","items":${json("""[{"start":"0.5","end":"2","text":"烧进去的字幕"}]""")}}"""), ctx)
        assertFalse(s.content, s.error)
        val b = MediaTool().run(args("""{"sub":"subtitle","input":"${src.name}","output":"out.mp4","subs":"字幕.srt"}"""), ctx)
        assertFalse("烧字幕没成功：${b.content}", b.error)
        val out = File(dir, "out.mp4")
        assertTrue(out.isFile)
        val p = probe(out)
        assertTrue("时长该还是 5 秒左右：$p", Regex("duration=([0-9.]+)").find(p)?.groupValues?.get(1)?.toDoubleOrNull()?.let { it in 4.0..6.5 } == true)
        assertTrue("画面流要在：$p", p.contains("codec_type=video"))
        assertTrue("声音不该丢：$p", p.contains("codec_type=audio"))
    }

    @Test
    fun `join doubles the duration and keeps audio`() {
        assumeTrue("这台机器上没有 ffmpeg", have())
        val dir = ws()
        val src = clip(dir)
        val ctx = ToolCtx(dir, PcSettings(), "auto", SpyGate())
        val r = MediaTool().run(args("""{"sub":"join","input":"${src.name}","input2":"${src.name}","output":"j.mp4"}"""), ctx)
        assertFalse("拼接没成功：${r.content}", r.error)
        val p = probe(File(dir, "j.mp4"))
        val d = Regex("duration=([0-9.]+)").find(p)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
        assertTrue("两段五秒该拼成十秒左右，实际 $d", d in 8.5..12.5)
        assertTrue("音轨要留着：$p", p.contains("codec_type=audio"))
    }
}
