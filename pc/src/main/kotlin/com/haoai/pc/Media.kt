package com.haoai.pc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * ffmpeg / ffprobe 的定位与调用。
 *
 * 为什么单开一份：定位规则（显式指定 → 环境变量 → HAOAI_HOME 里那行 → PATH → 常见解压目录）
 * 和"跑一条不会挂死进程的 ffmpeg"这两样，媒体工具与以后的录屏工具都要用。
 * 两处各写一遍，将来一定有一处忘了加候选目录。
 */
object Ffmpeg {

    /** 环境变量：指向放着 ffmpeg.exe 的那个 bin 目录。 */
    const val ENV_DIR = "HAOAI_FFMPEG_DIR"

    /**
     * 显式指定可执行文件绝对路径；空串＝**强制按"没装"处理**。
     * 给测试用（本机装了 ffmpeg，不设这个就没法验"没装时怎么说"），也给用户留一条
     * "我不想动 PATH"的后门。
     */
    @Volatile
    var ffmpegPath: String? = null
        set(v) {
            field = v
            cached = null
        }

    @Volatile
    private var cached: Pair<String?, String?>? = null

    fun ffmpeg(): String? = locate().first
    fun ffprobe(): String? = locate().second
    fun ready(): Boolean = ffmpeg() != null

    private fun locate(): Pair<String?, String?> =
        cached ?: synchronized(this) { cached ?: find().also { cached = it } }

    private val suffix: String get() = if (Env.isWindows) ".exe" else ""

    private fun find(): Pair<String?, String?> {
        ffmpegPath?.let { p ->
            val f = File(p)
            if (!f.isFile) return null to null
            return f.absolutePath to File(f.parentFile, "ffprobe$suffix").takeIf { it.isFile }?.absolutePath
        }
        for (d in dirs()) {
            val f = File(d, "ffmpeg$suffix")
            if (f.isFile) {
                return f.absolutePath to File(d, "ffprobe$suffix").takeIf { it.isFile }?.absolutePath
            }
        }
        return null to null
    }

    private fun dirs(): List<String> {
        val out = mutableListOf<String>()
        System.getenv(ENV_DIR)?.takeIf { it.isNotBlank() }?.let { out += it }
        // HAOAI_HOME/ffmpeg：一行内容，既可以是目录也可以是 ffmpeg.exe 的全路径
        runCatching {
            File(Env.home, "ffmpeg").takeIf { it.isFile }?.readText()?.lines()?.firstOrNull { l ->
                l.isNotBlank() && !l.startsWith("#")
            }?.trim()
        }.getOrNull()?.let { p ->
            if (File(p).isFile) out += File(p).parentFile?.absolutePath ?: p else out += p
        }
        System.getenv("PATH")?.split(File.pathSeparator)?.filter { it.isNotBlank() }?.let { out += it }
        // 本机实测：winget / 手动解压 / scoop / chocolatey 各走各的落点，四个都认一遍。
        // 这里统一用 / —— Windows 的 File 两种分隔符都认，而正斜杠不用在源码里写转义。
        listOfNotNull(
            System.getenv("ProgramFiles")?.let { "$it/ffmpeg/bin" },
            "C:/ffmpeg/bin",
            System.getenv("LOCALAPPDATA")?.let { "$it/Microsoft/WinGet/Links" },
            System.getenv("LOCALAPPDATA")?.let { "$it/Programs/ffmpeg/bin" },
            "C:/ProgramData/chocolatey/bin",
            System.getenv("USERPROFILE")?.let { "$it/scoop/shims" }
        ).forEach { out += it }
        return out
    }

    /** 没装时的那句话。静默失败是最坏结果：用户只会觉得 agent 在偷懒。 */
    fun missing(): String =
        "这台机器上找不到 ffmpeg（ffprobe 同理）。装一个就好，任选一路：" +
            "① winget install Gyan.FFmpeg ② choco install ffmpeg ③ 从 ffmpeg.org 下载 zip 解压。" +
            "装完重启 HaoAI-PC。不想动 PATH 的话，把放着 ffmpeg.exe 的目录写进环境变量 $ENV_DIR，" +
            "或者把那一行路径写进文件 " + Env.abs(File(Env.home, "ffmpeg")) + "。"

    data class Run(val exit: Int, val log: String, val timedOut: Boolean = false) {
        val ok: Boolean get() = exit == 0 && !timedOut
    }

    /**
     * 直接 argv 调用，**不经 shell**。
     *
     * Windows 上带引号/中文的命令交给 shell 会被二次解析吃掉引号（ShellTool 那条坑），
     * 而媒体参数里全是 `scale=1280:-2`、`-vf` 这种带冒号的串。实测把 argv 直接发给
     * ffmpeg.exe 时连中文文件名都正常（`测试素材.mp4` 探得到、转得出），所以这条路更稳。
     */
    fun run(exe: String, argv: List<String>, cwd: File, timeoutSec: Int): Run {
        val p = try {
            ProcessBuilder(listOf(exe) + argv)
                .directory(if (cwd.isDirectory) cwd else File(System.getProperty("user.dir")))
                .redirectErrorStream(true)
                .start()
        } catch (e: Exception) {
            return Run(-1, "启动失败：${e.message}")
        }
        // ffmpeg 会读 stdin 上的键盘指令（q 退出）：不关掉的话它可能一直等，白等一个超时
        runCatching { p.outputStream.close() }
        val log = StringBuilder()
        val reader = Thread {
            runCatching {
                BufferedReader(InputStreamReader(p.inputStream, Charsets.UTF_8)).use { br ->
                    while (true) {
                        val l = br.readLine() ?: break
                        synchronized(log) { log.append(l).append('\n') }
                    }
                }
            }
        }
        reader.isDaemon = true
        reader.start()
        if (!p.waitFor(timeoutSec.toLong(), TimeUnit.SECONDS)) {
            p.destroyForcibly()
            reader.join(1000)
            return Run(-1, "超过 ${timeoutSec}s 没跑完，已强制终止。\n$log", true)
        }
        reader.join(2000)
        return Run(p.exitValue(), log.toString())
    }
}

/** media 工具的内部结果：要么带着 argv，要么带着那句要说给模型听的话。 */
/**
 * 一条要跑的 ffmpeg 命令，外加"跑之前要先落到盘上的那几个文件"。
 *
 * writes 为什么要单独列：drawtext 的文字与 subtitles 的字幕都走**文件**而不是内联进过滤串 ——
 * 过滤串里 `:` `\` `'` `%` 每一样都要再转一层，中文标题里一个冒号就能把整条 filter 打断。
 * 而"先写文件"这件事必须发生在审批通过之后，否则人点了拒绝，盘上却多出两个临时文件。
 */
private class Plan(
    val argv: List<String> = emptyList(), val hint: String = "", val err: String = "",
    val writes: List<Pair<File, String>> = emptyList(),
    val copies: List<Pair<File, File>> = emptyList()
)

/**
 * HTTP Range 解析，给 `/api/media` 用。
 *
 * `<video>` 拖进度条时浏览器会发 `Range: bytes=a-b`；服务端不懂就得整份重传，
 * 实测 Chrome 在无 Range 支持（且没 Content-Range）的响应上 seek 会失败。
 * 认三种写法：`a-b`、`a-`、`-n`（末尾 n 字节）。不合法就整份给。
 */
object Range {
    fun parse(header: String?, len: Long): Pair<Long, Long> {
        val h = header?.trim()?.lowercase()?.removePrefix("bytes=")?.takeIf { it.isNotEmpty() } ?: return 0L to len - 1
        val p = h.split('-')
        if (p.size != 2) return 0L to len - 1
        val a = p[0].trim().toLongOrNull()
        val b = p[1].trim().toLongOrNull()
        val pair = when {
            a != null && b != null -> a to minOf(b, len - 1)
            a != null -> a to len - 1
            // `bytes=-500`：最后 500 字节
            b != null -> maxOf(0L, len - b) to len - 1
            else -> return 0L to len - 1
        }
        return if (pair.first < 0 || pair.first > pair.second || pair.second >= len) 0L to len - 1 else pair
    }
}

/**
 * 音视频的魔数识别，给 `/api/media` 用。
 *
 * 为什么按内容而不是按扩展名：这个口子是"把本地文件的字节发给浏览器"，
 * 只看后缀就等于让一个改名成 .mp4 的文本文件混出去。与 [Images.mime] 同一套理由。
 */
object MediaMime {

    /** 单次能给的上限：再大的文件也不该由一个本机网页服务整份端着。 */
    const val MAX = 512L * 1024 * 1024

    fun sniff(f: File): String? = runCatching {
        val head = ByteArray(4096)
        val n = f.inputStream().use { it.read(head) }
        if (n < 12) return null
        val b = head.copyOfRange(0, n)
        val s8 = String(b, 0, minOf(n, 12), Charsets.ISO_8859_1)
        val four = String(b, 4, 4, Charsets.ISO_8859_1)
        when {
            four == "ftyp" -> {
                val brand = String(b, 8, 4, Charsets.ISO_8859_1)
                when {
                    brand.startsWith("M4A") || brand == "M4A " -> "audio/mp4"
                    brand.startsWith("qt") -> "video/quicktime"
                    else -> "video/mp4"
                }
            }
            s8.startsWith("OggS") -> "audio/ogg"
            s8.startsWith("ID3") -> "audio/mpeg"
            s8.startsWith("RIFF") && String(b, 8, 4, Charsets.ISO_8859_1) == "WAVE" -> "audio/wav"
            b[0] == 0x1A.toByte() && b[1] == 0x45.toByte() && b[2] == 0xDF.toByte() && b[3] == 0xA3.toByte() -> "video/webm"
            // MPEG 帧同步：11111111 111xxyyy
            b[0] == 0xFF.toByte() && (b[1].toInt() and 0xE0) == 0xE0 -> "audio/mpeg"
            b[0] == 'f'.code.toByte() && b[1] == 'L'.code.toByte() && b[2] == 'A'.code.toByte() -> "audio/flac"
            else -> null
        }
    }.getOrNull()
}

/**
 * 媒体工具：一把工具 + 子命令，不做六把窄工具。
 *
 * 理由与 GitTool 同源（手机端 19 把 `browser_*` 的教训）：工具越多，模型选错的概率越高，
 * schema 的固定开销也越大。
 *
 * 两条不可省的行为：① 没装 ffmpeg 时**把安装办法说出来**，不能静默失败或只回一个 exit=1；
 * ② 参数全部白名单化后由我们自己拼 argv —— 宽高是整数、时间是纯数字串、编码器名限字符集，
 * 于是模型传什么都拼不出第二条 filter 来。
 */
class MediaTool : Tool(
    "media",
    "用 ffmpeg/ffprobe 处理音视频素材。sub ∈ info（探测时长与流，只读）| transcode（转码/改分辨率）| " +
        "cut（按时间剪切）| frame（抽一帧成图，图本身会递给你看）| audio（抽音轨）| cover（封面图）| " +
        "caption（给封面/某一帧叠一行大标题，做视频封面与直播缩略图）| " +
        "srt（把「几点到几点说什么」写成字幕文件，不需要源文件也不碰 ffmpeg）| " +
        "subtitle（把 .srt 烧进画面，出的是新视频）| join（两段按顺序拼成一段，会重编码）| " +
        "speed（整段变速，rate=2 是两倍速，口播讲快了用它把时间压回去）| " +
        "fade（开头淡入、结尾淡出，切片首尾不生硬）| " +
        "mix（把一段 BGM 压在口播下面：input2 是音乐，gain 是音乐的音量倍数，默认 0.18）。" +
        "input 是源文件（工作区相对路径或绝对路径）；output 可省，默认写进 .haoai-output/media/。" +
        "start/dur 认 12、12.5、1:05、1:02:03 四种写法。caption 要 text，subtitle 要 subs，join 要 input2，" +
        "mix 也要 input2，speed 要 rate，fade 用 fadeIn/fadeOut（秒，默认各 1 秒），" +
        "srt 要 items（JSON 数组：[{\"start\":\"0\",\"end\":\"2.5\",\"text\":\"第一句\"}]）。除 info 外都会写文件，按权限档走。",
    schema(
        "sub" to "string", "input" to "string", "output" to "string",
        "start" to "string", "dur" to "string", "width" to "integer", "height" to "integer",
        "fps" to "string", "crf" to "integer", "preset" to "string", "codec" to "string",
        "reencode" to "boolean", "timeout" to "integer",
        "text" to "string", "items" to "string", "subs" to "string", "input2" to "string",
        "size" to "integer", "pos" to "string", "font" to "string", "force" to "boolean",
        "rate" to "string", "fadeIn" to "string", "fadeOut" to "string", "gain" to "string",
        required = arrayOf("sub")
    ),
    kind = "exec"
) {

    override suspend fun run(args: JsonObject, ctx: ToolCtx): ToolResult {
        val sub = (req(args, "sub") ?: "").trim().lowercase()
        if (sub !in SUBS) return fail("media 不认的子命令：「$sub」。可用：$SUB_LIST")
        // 字幕文件是"我们自己写文本"，既不需要源文件也不碰 ffmpeg：
        // 放在 input 存在性检查之前，否则"先写字幕、后录屏"这条正常顺序会被挡掉。
        if (sub == "srt") return srt(args, ctx)
        val src = req(args, "input")?.takeIf { it.isNotBlank() }
            ?: return fail("media 缺少 input（源文件路径，工作区相对或绝对都认）")
        val inp = ctx.resolve(src)
        if (!inp.isFile) return fail("没有这个文件：${ctx.rel(inp)}（绝对路径也认；先确认素材真的在盘上）")
        val timeout = int(args, "timeout", 180).coerceIn(5, 1800)
        if (sub == "info") return info(inp, ctx, timeout)

        val exe = Ffmpeg.ffmpeg() ?: return fail(Ffmpeg.missing())
        val start = parseTime(req(args, "start"))
            ?: return fail("start「${req(args, "start")}」看不懂：要的是秒或 时:分:秒，例如 12、12.5、1:05、1:02:03")
        val durRaw = req(args, "dur")?.trim()?.takeIf { it.isNotEmpty() }
        val dur = if (durRaw == null) null else parseTime(durRaw)
            ?: return fail("dur「$durRaw」不是正数时长（写法同 start）")
        if (dur != null && dur.toDouble() <= 0.0) return fail("dur 必须大于 0（给 0 等于什么都不切）")

        val out = pickOutput(args, ctx, sub, inp)
        val ext = ext(out)
        val allowed = EXT_OK[sub]!!
        if (ext !in allowed) return fail("sub=$sub 只能输出 ${allowed.joinToString("/")}，现在要写 .$ext")
        if (out.isFile && out.canonicalPath == inp.canonicalPath) return fail("输入与输出是同一个文件，会把自己覆盖掉")

        val plan = plan(sub, args, inp, out, start, dur, ctx)
        if (plan.err.isNotEmpty()) return fail(plan.err)
        val label = ctx.rel(out)
        val why = ctx.guard(
            "media", label, "生成媒体文件（$sub）",
            { "ffmpeg $sub → $label\n源文件：${ctx.rel(inp)}" + if (out.isFile) "\n注意：会覆盖已有文件" else "" }
        )
        if (why != null) return fail(why)
        // 审批过了才落这些"喂给过滤串的小文件"：点拒绝不该在盘上留东西
        plan.writes.forEach { (f, body) ->
            f.parentFile?.mkdirs()
            f.writeText(body, Charsets.UTF_8)
        }
        // 字体一份工作区只拷一次（十几 MB，每次画标题都重拷一遍太浪费）
        plan.copies.forEach { (from, to) ->
            to.parentFile?.mkdirs()
            if (!to.isFile || to.length() != from.length()) from.copyTo(to, overwrite = true)
        }

        val began = System.currentTimeMillis()
        val r = Ffmpeg.run(exe, plan.argv, ctx.workspace, timeout)
        val secs = (System.currentTimeMillis() - began) / 1000.0
        val cmd = shown(plan.argv, exe, inp.absolutePath to ctx.rel(inp), out.absolutePath to label)
        if (!r.ok || !out.isFile) {
            return fail(
                "ffmpeg 没成功（exit=${r.exit}${if (r.timedOut) " / 超时" else ""}）。\n命令：$cmd\n---\n" +
                    TextCap.middle(r.log.ifBlank { "（ffmpeg 没留下任何输出）" }, 2000) +
                    "\n（改一版上面这条的参数再试；怀疑源文件坏了就先跑 sub=info。）"
            )
        }
        if (label.startsWith(Env.TOOL_OUTPUT_DIR)) Env.excludeFromGit(ctx.workspace, Env.TOOL_OUTPUT_DIR)
        val pic = ext in PICS
        val hint = if (plan.hint.isBlank()) "" else "\n提示：${plan.hint}"
        val log = if (r.log.isBlank()) "" else "\nffmpeg：${TextCap.head(r.log.trim(), 600)}"
        /*
         * `media` 只给**工作区内**的文件，而且是相对路径：网页取字节走 `/api/media`，
         * 那个口子按 /api/img 的同一条规矩拒绝工作区外的文件（同机任意页面都能打这个端口）。
         * 素材库在工作区外时照样能转，只是界面不放 —— 路径与大小都在正文里说清了。
         */
        val playable = !pic && !ctx.outside(out)
        return ToolResult(
            content = "exit=0 · media $sub · ${"%.1f".format(secs)}s\n输出：$label（${kb(out.length())}）\n命令：$cmd$hint$log" +
                (if (!pic && !playable) "\n（这个文件在工作区外面，界面不放，只在文件管理器里看）" else ""),
            images = if (pic) listOf(out.absolutePath) else emptyList(),
            media = if (playable) listOf(label) else emptyList(),
            card = "terminal"
        )
    }

    private fun info(inp: File, ctx: ToolCtx, timeout: Int): ToolResult {
        val probe = Ffmpeg.ffprobe() ?: return fail(Ffmpeg.missing())
        val r = Ffmpeg.run(
            probe,
            listOf(
                "-v", "error", "-show_entries",
                "format=duration,size,bit_rate:stream=index,codec_type,codec_name,width,height,r_frame_rate,sample_rate,channels",
                "-of", "default=noprint_wrappers=1", inp.absolutePath
            ),
            ctx.workspace, timeout
        )
        if (!r.ok) return fail("ffprobe 没成功（exit=${r.exit}${if (r.timedOut) " / 超时" else ""}）：\n${TextCap.head(r.log, 800)}")
        return ToolResult("ffprobe ${ctx.rel(inp)}\n${TextCap.head(r.log.trim(), 3000)}")
    }

    private fun plan(sub: String, args: JsonObject, inp: File, out: File, start: String, dur: String?,
                     ctx: ToolCtx): Plan {
        badParams(args)?.let { return Plan(err = it) }
        val base = listOf("-y", "-hide_banner", "-loglevel", "error", "-nostats")
        val seek = if (start == "0") emptyList() else listOf("-ss", start)
        val span = dur?.let { listOf("-t", it) } ?: emptyList()
        val vf = videoFilter(args)
        val ext = ext(out)
        val src = listOf("-i", inp.absolutePath)
        return when (sub) {
            "transcode" -> {
                if (ext in AUDIO_ONLY)
                    return Plan(base + seek + src + listOf("-vn") + audioArgs(ext) + out.absolutePath,
                        hint = "目标是纯音频，视频流已丢")
                val codec = (req(args, "codec") ?: "").trim().lowercase().ifBlank { "libx264" }
                val enc = if (codec == "copy") listOf("-c:v", "copy") else listOf(
                    "-c:v", codec, "-preset",
                    req(args, "preset")?.trim()?.lowercase()?.takeIf { it in PRESETS } ?: "veryfast",
                    "-crf", int(args, "crf", 23).coerceIn(0, 51).toString()
                )
                Plan(base + seek + src + vf + enc + audioArgs(ext, codec) + faststart(ext) + out.absolutePath,
                    hint = "没给 dur 就是从 start 一直转到结尾")
            }
            "cut" -> {
                val re = bool(args, "reencode")
                val codec = when {
                    ext in AUDIO_ONLY && re -> listOf("-c:a", "libmp3lame")
                    ext in AUDIO_ONLY -> listOf("-c:a", "copy")
                    re -> listOf("-c:v", "libx264", "-preset", "veryfast", "-crf", "23", "-c:a", "aac")
                    else -> listOf("-c", "copy")
                }
                Plan(base + seek + src + span + codec + faststart(ext) + out.absolutePath,
                    hint = if (re || ext in AUDIO_ONLY) "已重编码，起止按帧精确"
                    else "-c copy 只能从关键帧切起，实际起点可能略早于 $start；要精确就设 reencode=true")
            }
            "frame" -> Plan(base + seek + src + listOf("-frames:v", "1") + vf + out.absolutePath, hint = "")
            "cover" -> Plan(
                base + seek + src + listOf("-frames:v", "1") +
                    (if (vf.isEmpty()) listOf("-vf", "scale=1280:-2") else vf) +
                    (if (ext == "jpg" || ext == "jpeg") listOf("-q:v", "3") else emptyList()) +
                    out.absolutePath,
                hint = "封面默认 1280 宽；start 不给就是从第一帧，多半是黑的"
            )
            "audio" -> {
                val c = AUDIO_CODEC[ext]
                    ?: return Plan(err = "音轨只能写成 ${AUDIO_CODEC.keys.joinToString("/")}，现在要写 .$ext")
                Plan(base + seek + src + listOf("-vn") + span + listOf("-c:a", c) +
                    (if (c == "libmp3lame") listOf("-b:a", "192k") else emptyList()) + out.absolutePath, hint = "")
            }
            /*
             * caption：在封面/某一帧上叠一行大标题（视频封面、直播缩略图就靠它）。
             * 文字走 textfile 而不是内联 text= —— 过滤串里 : \ ' % 每一样都要再转一层，
             * 中文标题里一个冒号就能把整条 filter 打断；textfile 让"文字内容"与"filter 语法"分家。
             */
            "caption" -> {
                val text = req(args, "text")?.trim()?.takeIf { it.isNotEmpty() }
                    ?: return Plan(err = "sub=caption 要 text（要叠在画面上的那行字）")
                if (text.length > 120) return Plan(err = "标题超过 120 字（${text.length}）：那是正文不是标题")
                val font = fontFile(req(args, "font")) ?: return Plan(
                    err = "这台机器上找不到能画中文的字体（试过 msyh.ttc / simhei.ttf / msyhbd.ttc）。" +
                        "用 font= 指一个 .ttf/.ttc 的绝对路径。没有合适字体时 drawtext 会画出一堆方块，" +
                        "比不画更糟 —— 所以这里宁可不跑"
                )
                val size = int(args, "size", 64).coerceIn(12, 400)
                val cap = File(out.parentFile, out.name.substringBeforeLast('.') + ".txt")
                // 字体不能直接把 C:/Windows/Fonts/... 写进过滤串：那个冒号怎么转义都还会和
                // "选项之间也用冒号"撞车（实测报 No option name near '/Windows/...'）。
                // 所以先把它拷进工作区，用不带盘符的相对路径引用 —— 转义这一层整个绕开。
                val fontHere = File(File(ctx.workspace, "${Env.TOOL_OUTPUT_DIR}/fonts"), font.name)
                val wh = if (int(args, "width", 0) > 0 || int(args, "height", 0) > 0) vf
                    else listOf("-vf", "scale=1280:-2")
                val draw = "drawtext=fontfile=${escFilter(rel(fontHere, ctx))}:textfile=${escFilter(rel(cap, ctx))}" +
                    ":fontsize=$size:fontcolor=white:borderw=3:bordercolor=black@0.65:" +
                    (if (req(args, "pos")?.trim()?.lowercase() == "top")
                        "x=(w-text_w)/2:y=text_h+60" else "x=(w-text_w)/2:y=h-text_h-60")
                val chain = if (wh.size == 2) listOf(wh[0], wh[1] + "," + draw) else listOf("-vf", draw)
                Plan(
                    base + seek + src + listOf("-frames:v", "1") + chain +
                        (if (ext == "jpg" || ext == "jpeg") listOf("-q:v", "3") else emptyList()) +
                        out.absolutePath,
                    hint = "标题默认压在画面下方、居中；pos=top 换到上方。图上的字是**烧进去的**，改字要重跑",
                    writes = listOf(cap to text.replace("\r\n", "\n").lines().take(3).joinToString("\n")),
                    copies = listOf(font to fontHere)
                )
            }
            // subtitle：把字幕烧进画面。字幕文件先复制成一个"安全名字"再交给过滤串 ——
            // 用户给的路径可能带空格、中文、盘符冒号，这些在 filter 里都要再转一层。
            "subtitle" -> {
                val raw = req(args, "subs")?.trim()
                if (raw.isNullOrEmpty()) return Plan(err = "sub=subtitle 要 subs：一个 .srt/.ass 的路径（没有就先跑 sub=srt）")
                val sf = ctx.resolve(raw)
                if (!sf.isFile) return Plan(err = "没有这个字幕文件：${ctx.rel(sf)}（subs 要指向盘上真存在的文件）")
                val se = ext(sf)
                if (se !in SUBEXT) return Plan(err = "subs 只认 ${SUBEXT.joinToString("/")}，现在是 .$se")
                val safe = File(out.parentFile, out.name.substringBeforeLast('.') + ".$se")
                val preset = req(args, "preset")?.trim()?.lowercase()?.takeIf { it in PRESETS } ?: "veryfast"
                Plan(
                    base + seek + src + span +
                        listOf("-vf", "subtitles=${escFilter(rel(safe, ctx))}") +
                        listOf("-map", "0:v:0", "-map", "0:a?") +
                        listOf("-c:v", "libx264", "-preset", preset, "-crf", int(args, "crf", 23).coerceIn(0, 51).toString()) +
                        listOf("-c:a", "copy") + faststart(ext) + out.absolutePath,
                    hint = "字幕是烧进画面的，播放器关不掉；要可关的字幕轨就得走容器内嵌（这条工具不做）",
                    writes = listOf(safe to sf.readText(Charsets.UTF_8))
                )
            }
            /*
             * join：两段按顺序拼成一段。用 concat **滤镜**而不是 concat 列表文件 ——
             * 列表文件里的路径解析规则（相对谁、怎么转义、非 ASCII）在中文工作区上最容易出事，
             * 代价是必然重编码（两段时间戳/编码参数对不上时 -c copy 会花屏，那比慢更糟）。
             */
            "join" -> {
                val raw2 = req(args, "input2")?.trim()
                if (raw2.isNullOrEmpty()) return Plan(err = "sub=join 要 input2：第二段文件（顺序是 input → input2）")
                val b = ctx.resolve(raw2)
                if (!b.isFile) return Plan(err = "没有这第二个文件：${ctx.rel(b)}")
                // 同一段拼两遍是合法用法（把一条 5 秒素材铺成 10 秒），所以不挡"input==input2"；
                // 真正会毁掉文件的是"输出等于输入"，那条在 run() 入口已经挡了。
                val aA = hasAudio(inp, ctx)
                val aB = hasAudio(b, ctx)
                if (aA == null || aB == null) return Plan(
                    err = "没探出这两段有没有声音流（ffprobe 没成功），不敢替你决定要不要保留声音 —— " +
                        "先各跑一次 sub=info 看看"
                )
                if (aA != aB) return Plan(
                    err = "两段的声音不一致（一段有音轨、一段没有），concat 拼不了。" +
                        "给缺音轨的那段补一条静音轨（media transcode + 自己带一路 aac），或两段都用同一种流结构"
                )
                val preset = req(args, "preset")?.trim()?.lowercase()?.takeIf { it in PRESETS } ?: "veryfast"
                val fc = if (aA) "[0:v][0:a][1:v][1:a]concat=n=2:v=1:a=1[v][a]"
                else "[0:v][1:v]concat=n=2:v=1:a=1[v]"
                val maps = if (aA) listOf("-map", "[v]", "-map", "[a]") else listOf("-map", "[v]")
                val enc = listOf("-c:v", "libx264", "-preset", preset, "-crf", int(args, "crf", 23).coerceIn(0, 51).toString()) +
                    (if (aA) listOf("-c:a", "aac", "-b:a", "192k") else emptyList())
                Plan(
                    base + listOf("-i", inp.absolutePath, "-i", b.absolutePath) +
                        listOf("-filter_complex", fc) + maps + enc + faststart(ext) + out.absolutePath,
                    hint = if (aA) "两段都要同分辨率同帧率才好看；不一样就先各自 transcode 再拼"
                    else "两段都没有音轨，拼出来也是无声的"
                )
            }
            /*
             * speed：整段变速。视频 setpts、音频 atempo，**两边必须一起动** ——
             * 只改画面不改声音，出来的是"默片配原速解说"那种废片。
             * atempo 只认 0.5~2.0，所以 4 倍速要串两级（2×2）：这是 ffmpeg 自己的限制，
             * 传超范围的数它直接报错退出，所以我们先在本地拆好。
             */
            "speed" -> {
                val rate = num(args, "rate")
                    ?: return Plan(err = "sub=speed 要 rate（倍速，如 1.25 / 2 / 0.5，只能是数字）")
                if (rate < 0.25 || rate > 8.0) return Plan(err = "rate=$rate 不在 0.25~8 之间")
                val a = hasAudio(inp, ctx)
                val v = "[0:v]setpts=PTS/${fmtNum(rate)}[v]"
                val fc = if (a == true) "$v;[0:a]${atempoChain(rate)}[a]" else v
                val maps = if (a == true) listOf("-map", "[v]", "-map", "[a]") else listOf("-map", "[v]")
                val enc = listOf("-c:v", "libx264", "-preset", "veryfast",
                    "-crf", int(args, "crf", 23).coerceIn(0, 51).toString()) +
                    (if (a == true) listOf("-c:a", "aac", "-b:a", "160k") else emptyList())
                Plan(base + src + listOf("-filter_complex", fc) + maps + enc + faststart(ext) + out.absolutePath,
                    hint = if (a == true) "时长会变成原来的 1/$rate（变速必然重编码）"
                    else "这段没有音轨，只变了画面")
            }
            /*
             * fade：首尾淡入淡出。结尾那一刀要知道总时长，所以先 ffprobe 一次 ——
             * 拿 `dur` 参数覆盖也行，但默认按文件真实长度算，省得模型记错长度把淡出算到片尾之外
             * （那样淡出永远不出现，而文件"看着是成功了"）。
             */
            "fade" -> {
                val fi = num(args, "fadeIn") ?: 1.0
                val fo = num(args, "fadeOut") ?: 1.0
                if (fi < 0 || fo < 0) return Plan(err = "fadeIn/fadeOut 不能是负数")
                if (fi == 0.0 && fo == 0.0) return Plan(err = "fadeIn 与 fadeOut 都是 0：那等于什么都不做")
                // dur 给了就以它为准（比如只处理片段）；不给才去探测整段长度。
                // 注意不能让空串走 parseTime —— 它会当成 0，于是"淡入淡出比整段长"这条判据第一次就红。
                val total = dur?.takeIf { it.isNotBlank() }?.let { parseTime(it) }?.toDoubleOrNull()
                    ?: durationOf(inp, ctx)
                    ?: return Plan(err = "没探出这段的时长（淡出要算结尾在哪）—— 先跑一次 sub=info，或直接给 dur")
                if (fi + fo >= total) return Plan(
                    err = "淡入 ${fi}s + 淡出 ${fo}s 已经不比整段 ${fmtNum(total)}s 短了：那样整段都是黑的")
                val v = mutableListOf<String>()
                val a = mutableListOf<String>()
                if (fi > 0) { v += "fade=t=in:st=0:d=${fmtNum(fi)}"; a += "afade=t=in:st=0:d=${fmtNum(fi)}" }
                if (fo > 0) {
                    val st = fmtNum(total - fo)
                    v += "fade=t=out:st=$st:d=${fmtNum(fo)}"; a += "afade=t=out:st=$st:d=${fmtNum(fo)}"
                }
                val has = hasAudio(inp, ctx)
                val fc = if (has == true) "[0:v]${v.joinToString(",")}[v];[0:a]${a.joinToString(",")}[a]"
                else "[0:v]${v.joinToString(",")}[v]"
                val maps = if (has == true) listOf("-map", "[v]", "-map", "[a]") else listOf("-map", "[v]")
                val enc = listOf("-c:v", "libx264", "-preset", "veryfast",
                    "-crf", int(args, "crf", 23).coerceIn(0, 51).toString()) +
                    (if (has == true) listOf("-c:a", "aac", "-b:a", "160k") else emptyList())
                Plan(base + src + listOf("-filter_complex", fc) + maps + enc + faststart(ext) + out.absolutePath,
                    hint = "整段按 ${fmtNum(total)}s 算的淡出起点；不对就给 dur 覆盖")
            }
            /*
             * mix：把一段 BGM 压在口播下面（直播切片、视频背景音都这一步）。
             * 两条口径：① 音乐先 `volume=gain` 再混，`normalize=0` 关掉 amix 默认的"按路数平分"，
                 否则口播会被一起拖小声（听感是"人声发虚"，而参数上看不出来）；
             * ② `duration=first` —— 音乐比口播长时**在口播结束处截断**，不能反过来把视频拉长。
             * 画面 `-c:v copy`：这一步只动声音，重编码画面是白花一分钟。
             */
            "mix" -> {
                val raw2 = req(args, "input2")?.trim()
                if (raw2.isNullOrEmpty())
                    return Plan(err = "sub=mix 要 input2：背景音乐那条（input 是口播/主音）")
                val b = ctx.resolve(raw2)
                if (!b.isFile) return Plan(err = "没有这条音乐：${ctx.rel(b)}")
                if (hasAudio(inp, ctx) != true) return Plan(err = "主素材自己没有音轨，压什么都还是没声音")
                if (hasAudio(b, ctx) != true) return Plan(err = "当音乐的那个文件没有音轨：${ctx.rel(b)}")
                val g = num(args, "gain") ?: 0.18
                if (g <= 0 || g > 4) return Plan(err = "gain=$g 不在 0~4 之间（音乐相对口播的倍数，默认 0.18）")
                val audioOnly = ext in AUDIO_ONLY
                val fc = "[0:a]aformat=sample_rates=44100:channel_layouts=stereo[v];" +
                    "[1:a]aformat=sample_rates=44100:channel_layouts=stereo,volume=${fmtNum(g)}[m];" +
                    "[v][m]amix=inputs=2:duration=first:dropout_transition=0:normalize=0[a]"
                val maps = if (audioOnly) listOf("-map", "[a]") else listOf("-map", "0:v", "-map", "[a]")
                val vcopy = if (audioOnly) emptyList() else listOf("-c:v", "copy")
                Plan(base + listOf("-i", inp.absolutePath, "-i", b.absolutePath) +
                    listOf("-filter_complex", fc) + maps + vcopy +
                    listOf("-c:a", if (ext == "mp3") "libmp3lame" else "aac", "-b:a", "192k") +
                    faststart(ext) + out.absolutePath,
                    hint = "音乐压到 ${fmtNum(g)}x，并在主音结束处截断")
            }
            else -> Plan(err = "不认的子命令：$sub")
        }
    }

    /** 只认"数字（可带小数）"的参数。宁可拒绝也不能把任意字符串拼进过滤串。 */
    private fun num(args: JsonObject, key: String): Double? {
        val s = req(args, key)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (!s.matches(Regex("^\\d{1,4}(\\.\\d{1,3})?$"))) return null
        return s.toDoubleOrNull()
    }

    /** 不带区域设置的数字格式化：`String.format` 在某些 locale 下会把小数点写成逗号，那会打断过滤串。 */
    private fun fmtNum(d: Double): String =
        java.math.BigDecimal(d.toString()).stripTrailingZeros().toPlainString()

    /** atempo 只吃 0.5~2.0，超出就串多级。 */
    private fun atempoChain(rate: Double): String {
        var left = rate
        val parts = mutableListOf<String>()
        while (left > 2.0) { parts += "atempo=2.0"; left /= 2.0 }
        while (left < 0.5) { parts += "atempo=0.5"; left /= 0.5 }
        parts += "atempo=${fmtNum(left)}"
        return parts.joinToString(",")
    }

    private fun durationOf(f: File, ctx: ToolCtx): Double? {
        val probe = Ffmpeg.ffprobe() ?: return null
        val r = Ffmpeg.run(probe, listOf("-v", "error", "-show_entries", "format=duration",
            "-of", "default=noprint_wrappers=1:nokey=1", f.absolutePath), ctx.workspace, 30)
        if (!r.ok) return null
        return r.log.trim().toDoubleOrNull()
    }

    /**
     * 把「几点到几点说什么」写成 SRT。不碰 ffmpeg —— 这一步只是文本，
     * 而 SRT 的格式（序号从 1 开始、时间用 `00:00:02,500 --> ` 这种逗号毫秒、块之间空一行）
     * 一旦手抖，整条字幕就对口型对不上，所以格式在这里一次算对并有 round-trip 测试。
     */
    private fun srt(args: JsonObject, ctx: ToolCtx): ToolResult {
        val raw = req(args, "items")?.trim()
        if (raw.isNullOrEmpty()) return fail(
            "sub=srt 要 items：一个 JSON 数组，形如 [{\"start\":\"0\",\"end\":\"2.5\",\"text\":\"第一句\"}]"
        )
        val rows = runCatching { Json.parseToJsonElement(raw).jsonArray }.getOrNull()
            ?: return fail("items 不是一个 JSON 数组，看不懂：${raw.take(80)}")
        if (rows.isEmpty()) return fail("items 是空数组：一句字幕都没有")
        if (rows.size > 400) return fail("一次最多 400 条字幕（现在 ${rows.size} 条），拆成几段再合")
        val cues = mutableListOf<Triple<Double, Double, String>>()
        rows.forEachIndexed { i, el ->
            val o = runCatching { el.jsonObject }.getOrNull()
                ?: return fail("第 ${i + 1} 条不是对象：每条都要 {start,end,text}")
            val a = parseTime(o["start"]?.jsonPrimitive?.contentOrNull)
                ?.toDoubleOrNull() ?: return fail("第 ${i + 1} 条的 start 看不懂（要秒或 时:分:秒）")
            val b = parseTime(o["end"]?.jsonPrimitive?.contentOrNull)
                ?.toDoubleOrNull() ?: return fail("第 ${i + 1} 条的 end 看不懂（写法同 start）")
            if (b <= a) return fail("第 ${i + 1} 条的 end（$b）不晚于 start（$a）：这条字幕一秒都不到")
            val t = (o["text"]?.jsonPrimitive?.contentOrNull ?: "").trim()
            if (t.isEmpty()) return fail("第 ${i + 1} 条 text 是空的：空字幕占着时间轴，不如不写")
            cues += Triple(a, b, t)
        }
        cues.sortBy { it.first }
        val given = req(args, "output")?.trim()
        val out = if (!given.isNullOrEmpty()) ctx.resolve(given) else {
            val dir = File(ctx.workspace, "${Env.TOOL_OUTPUT_DIR}/media").apply { mkdirs() }
            File(dir, (req(args, "input")?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { File(it).name.substringBeforeLast('.') } ?: "字幕") + ".srt")
        }
        if (ext(out) != "srt") return fail("sub=srt 只能写 .srt，现在要写 ${out.name}")
        out.parentFile?.mkdirs()
        if (out.isFile && out.length() > 0 && !bool(args, "force")) return fail(
            "${ctx.rel(out)} 已经存在（${kb(out.length())}）。要盖掉就带 force=true —— " +
                "字幕是人工对过时间的东西，静默覆盖代价太高"
        )
        val why = ctx.guard("media", ctx.rel(out), "生成字幕文件",
            { "写 ${cues.size} 条字幕 → ${ctx.rel(out)}" + if (out.isFile) "\n注意：会覆盖已有文件" else "" })
        if (why != null) return fail(why)
        val text = cues.mapIndexed { i, c ->
            "${i + 1}\n${srtTime(c.first)} --> ${srtTime(c.second)}\n${c.third.replace("\r\n", "\n")}\n"
        }.joinToString("\n")
        out.writeText(text, Charsets.UTF_8)
        if (ctx.rel(out).startsWith(Env.TOOL_OUTPUT_DIR)) Env.excludeFromGit(ctx.workspace, Env.TOOL_OUTPUT_DIR)
        return ToolResult(
            "media srt · ${cues.size} 条 · 末尾到 ${"%.1f".format(cues.last().second)}s\n" +
                "输出：${ctx.rel(out)}（${kb(out.length())}）\n" +
                "下一步：sub=subtitle 把它烧进画面，或 sub=transcode 时自己带字幕轨。"
        )
    }

    /** SRT 的时间：`00:01:02,500`。逗号不是笔误，是格式规定（写句号整条不认）。 */
    private fun srtTime(sec: Double): String {
        val ms = (sec * 1000).toLong().coerceAtLeast(0)
        return "%02d:%02d:%02d,%03d".format(ms / 3600000, ms / 60000 % 60, ms / 1000 % 60, ms % 1000)
    }

    /** 这台机器上能不能画中文。找不到就回 null，让上层明说而不是画出一堆方块。 */
    private fun fontFile(given: String?): File? {
        val g = given?.trim()?.takeIf { it.isNotEmpty() }
        if (g != null) return File(g).takeIf { it.isFile }
        return CJK_FONTS.map { File(it) }.firstOrNull { it.isFile }
    }

    /** 相对工作区的路径，统一用 `/`：过滤串里的反斜杠是转义符，留着它必出事。 */
    private fun rel(f: File, ctx: ToolCtx): String = ctx.rel(f).replace('\\', '/')

    /** 过滤串里的值：`\` 先换成正斜杠，再把 `:` 与 `'` 各挡一层。 */
    private fun escFilter(p: String): String = p.replace('\\', '/').replace(":", "\\:").replace("'", "\\'")

    /** 有没有音轨：true/false，探测失败回 null（上层据此拒绝，而不是替你猜）。 */
    private fun hasAudio(f: File, ctx: ToolCtx): Boolean? {
        val probe = Ffmpeg.ffprobe() ?: return null
        val r = Ffmpeg.run(
            probe, listOf("-v", "error", "-select_streams", "a:0",
                "-show_entries", "stream=index", "-of", "csv=p=0", f.absolutePath),
            ctx.workspace, 30
        )
        if (!r.ok) return null
        return r.log.trim().isNotEmpty()
    }

    /** 输出落在哪：给了就用给的，没给就进 `.haoai-output/media/` 并自动避重名。 */
    private fun pickOutput(args: JsonObject, ctx: ToolCtx, sub: String, inp: File): File {
        val given = req(args, "output")?.trim()
        if (!given.isNullOrEmpty()) {
            val f = ctx.resolve(given)
            f.parentFile?.mkdirs()
            return f
        }
        val dir = File(ctx.workspace, "${Env.TOOL_OUTPUT_DIR}/media").apply { mkdirs() }
        val stem = inp.name.substringBeforeLast('.')
        var n = 0
        var cand = File(dir, "$stem-$sub.${DEFAULT_EXT[sub]}")
        while (cand.exists() && n < 99) {
            n++
            cand = File(dir, "$stem-$sub-$n.${DEFAULT_EXT[sub]}")
        }
        return cand
    }

    /** 只有宽高/帧率是"我们算出来的串"，所以模型传不进第二条 filter。 */
    private fun videoFilter(args: JsonObject): List<String> {
        val w = int(args, "width", 0)
        val h = int(args, "height", 0)
        val parts = mutableListOf<String>()
        if (w > 0 || h > 0) parts += "scale=${if (w > 0) w else -2}:${if (h > 0) h else -2}"
        req(args, "fps")?.trim()?.takeIf { it.isNotEmpty() }?.let { parts += "fps=$it" }
        return if (parts.isEmpty()) emptyList() else listOf("-vf", parts.joinToString(","))
    }

    private fun audioArgs(ext: String, vcodec: String = ""): List<String> = when {
        vcodec == "copy" -> listOf("-c:a", "copy")
        ext == "mp3" -> listOf("-c:a", "libmp3lame", "-b:a", "192k")
        ext == "wav" -> listOf("-c:a", "pcm_s16le")
        ext == "ogg" -> listOf("-c:a", "libvorbis")
        ext == "opus" -> listOf("-c:a", "libopus")
        ext == "flac" -> listOf("-c:a", "flac")
        ext == "gif" || ext in PICS -> emptyList()
        else -> listOf("-c:a", "aac", "-b:a", "128k")
    }

    /** faststart 把 moov 挪到文件头：没有它，浏览器要等整份文件下完才起播。 */
    private fun faststart(ext: String): List<String> =
        if (ext == "mp4" || ext == "mov" || ext == "m4a") listOf("-movflags", "+faststart") else emptyList()

    private fun badParams(args: JsonObject): String? {
        req(args, "fps")?.trim()?.takeIf { it.isNotEmpty() }?.let {
            if (!it.matches(Regex("^\\d{1,3}(\\.\\d{1,3})?$"))) return "fps「$it」只能是数字，例如 30 或 29.97"
        }
        req(args, "codec")?.trim()?.takeIf { it.isNotEmpty() }?.let {
            if (!it.matches(Regex("^[A-Za-z0-9_.-]{2,32}$"))) return "codec「$it」不是合法的编码器名（字母数字与 . _ -）"
        }
        req(args, "preset")?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.let {
            if (it !in PRESETS) return "preset 只认 ${PRESETS.joinToString("/")}，现在是「$it」"
        }
        req(args, "pos")?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.let {
            if (it != "top" && it != "bottom") return "pos 只认 top/bottom（标题压在画面哪一边），现在是「$it」"
        }
        listOf("width" to int(args, "width", 0), "height" to int(args, "height", 0)).forEach { (k, v) ->
            if (v != 0 && (v < 16 || v > 8192)) return "$k=$v 太离谱（16~8192 之间，或不给让它自动）"
        }
        return null
    }

    companion object {
        private val SUBS = setOf(
            "info", "transcode", "cut", "frame", "audio", "cover", "caption", "subtitle", "srt", "join",
            "speed", "fade", "mix"
        )
        private const val SUB_LIST =
            "info/transcode/cut/frame/audio/cover/caption/subtitle/srt/join/speed/fade/mix"
        private val PICS = setOf("png", "jpg", "jpeg", "webp")
        private val SUBEXT = setOf("srt", "ass", "ssa", "vtt")
        private val CJK_FONTS = listOf(
            "C:/Windows/Fonts/msyh.ttc", "C:/Windows/Fonts/simhei.ttf", "C:/Windows/Fonts/msyhbd.ttc"
        )
        private val VIDEO_OK = setOf("mp4", "mov", "mkv", "webm")
        private val AUDIO_ONLY = setOf("mp3", "wav", "m4a", "aac", "ogg", "opus", "flac")
        private val PRESETS = setOf(
            "ultrafast", "superfast", "veryfast", "faster", "fast", "medium", "slow", "slower", "veryslow"
        )
        private val AUDIO_CODEC = mapOf(
            "mp3" to "libmp3lame", "wav" to "pcm_s16le", "m4a" to "aac", "aac" to "aac",
            "ogg" to "libvorbis", "opus" to "libopus", "flac" to "flac"
        )
        private val EXT_OK = mapOf(
            "info" to setOf("*"),
            "transcode" to (setOf("mp4", "mov", "mkv", "webm", "gif") + AUDIO_ONLY),
            "cut" to (setOf("mp4", "mov", "mkv", "webm") + AUDIO_ONLY),
            "frame" to setOf("png", "jpg", "jpeg", "webp"),
            "cover" to setOf("jpg", "jpeg", "png", "webp"),
            "caption" to setOf("jpg", "jpeg", "png", "webp"),
            "subtitle" to VIDEO_OK,
            "join" to VIDEO_OK,
            "speed" to VIDEO_OK, "fade" to VIDEO_OK, "mix" to (VIDEO_OK + AUDIO_ONLY),
            "srt" to setOf("srt"),
            "audio" to AUDIO_ONLY
        )
        private val DEFAULT_EXT = mapOf(
            "transcode" to "mp4", "cut" to "mp4", "frame" to "png", "cover" to "jpg", "audio" to "mp3",
            "caption" to "jpg", "subtitle" to "mp4", "join" to "mp4",
            "speed" to "mp4", "fade" to "mp4", "mix" to "mp4"
        )

        private fun ext(f: File): String = f.extension.lowercase().trimStart('.')

        fun kb(n: Long): String = when {
            n < 1024 -> "$n B"
            n < 1024 * 1024 -> "%.1f KB".format(n / 1024.0)
            else -> "%.1f MB".format(n / 1048576.0)
        }

        /**
         * 时间写法归一成 ffmpeg 要的秒数串。认 12、12.5、1:05、1:02:03，不认就回 null。
         *
         * 为什么不直接把它塞给 ffmpeg：`-ss hh:mm:ss.mmm` 看着宽容，实际给个 `3sec` 之类的
         * 会当成 0 —— 于是"抽第 3 秒"静默变成抽第一帧。在入口判掉，错了就说错在哪。
         */
        fun parseTime(raw: String?): String? {
            val s = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return "0"
            val parts = s.split(':')
            if (parts.size > 3) return null
            var total = 0.0
            for (p in parts) {
                val v = p.trim().toDoubleOrNull() ?: return null
                if (v < 0 || v >= 3600) return null
                total = total * 60 + v
            }
            if (!total.isFinite() || total < 0 || total > 86_400) return null
            if (total == 0.0) return "0"
            return String.format(java.util.Locale.US, "%.3f", total).trimEnd('0').trimEnd('.')
        }

        /** 给模型回显这条命令：绝对路径换成工作区相对路径，太长的值打省略。 */
        fun shown(argv: List<String>, exe: String, vararg aliases: Pair<String, String>): String {
            val name = File(exe).name
            val body = argv.map { a ->
                val v = aliases.firstOrNull { it.first == a }?.second ?: a
                if (v.length > 90) v.take(90) + "…" else if (v.any { it == ' ' || it == '"' }) "\"$v\"" else v
            }
            return (listOf(name) + body).joinToString(" ")
        }
    }
}
