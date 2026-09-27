package com.haoai.pc

import kotlinx.serialization.json.JsonObject
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
private class Plan(val argv: List<String> = emptyList(), val hint: String = "", val err: String = "")

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
        "cut（按时间剪切）| frame（抽一帧成图，图本身会递给你看）| audio（抽音轨）| cover（封面图）。" +
        "input 是源文件（工作区相对路径或绝对路径）；output 可省，默认写进 .haoai-output/media/。" +
        "start/dur 认 12、12.5、1:05、1:02:03 四种写法。除 info 外都会写文件，按权限档走。",
    schema(
        "sub" to "string", "input" to "string", "output" to "string",
        "start" to "string", "dur" to "string", "width" to "integer", "height" to "integer",
        "fps" to "string", "crf" to "integer", "preset" to "string", "codec" to "string",
        "reencode" to "boolean", "timeout" to "integer",
        required = arrayOf("sub", "input")
    ),
    kind = "exec"
) {

    override fun run(args: JsonObject, ctx: ToolCtx): ToolResult {
        val sub = (req(args, "sub") ?: "").trim().lowercase()
        if (sub !in SUBS) return fail("media 不认的子命令：「$sub」。可用：$SUB_LIST")
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

        val plan = plan(sub, args, inp, out, start, dur)
        if (plan.err.isNotEmpty()) return fail(plan.err)
        val label = ctx.rel(out)
        val why = ctx.guard(
            "media", label, "生成媒体文件（$sub）",
            { "ffmpeg $sub → $label\n源文件：${ctx.rel(inp)}" + if (out.isFile) "\n注意：会覆盖已有文件" else "" }
        )
        if (why != null) return fail(why)

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

    private fun plan(sub: String, args: JsonObject, inp: File, out: File, start: String, dur: String?): Plan {
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
            else -> Plan(err = "不认的子命令：$sub")
        }
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
        listOf("width" to int(args, "width", 0), "height" to int(args, "height", 0)).forEach { (k, v) ->
            if (v != 0 && (v < 16 || v > 8192)) return "$k=$v 太离谱（16~8192 之间，或不给让它自动）"
        }
        return null
    }

    companion object {
        private val SUBS = setOf("info", "transcode", "cut", "frame", "audio", "cover")
        private const val SUB_LIST = "info/transcode/cut/frame/audio/cover"
        private val PICS = setOf("png", "jpg", "jpeg", "webp")
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
            "audio" to AUDIO_ONLY
        )
        private val DEFAULT_EXT = mapOf(
            "transcode" to "mp4", "cut" to "mp4", "frame" to "png", "cover" to "jpg", "audio" to "mp3"
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
