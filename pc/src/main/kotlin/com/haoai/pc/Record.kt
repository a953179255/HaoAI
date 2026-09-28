package com.haoai.pc

import kotlinx.serialization.json.JsonObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 录屏（缺口地图 B14）。
 *
 * 为什么现在做：用户点名这台 PC 端后面要干"视频制作自动化 / 直播辅助"，
 * 而 B7 的 `media` 只能处理**已经在盘上的**文件 —— 没有"把刚才那段屏幕变成文件"这一步，
 * 整条链路就得让人手动开 OBS。OBS 仍然更好（场景、混音、推流），那是后置项；
 * 这里先给 agent 一条能自己走完的最小闭环：录 → 停 → 立刻能在界面里放 → 再交给 `media` 剪。
 *
 * 一个必须做对的细节：**停止要往 ffmpeg 的 stdin 送 `q`，不能直接杀进程**。
 * 硬杀会让 mp4 少掉尾部的 moov box —— 文件存在、大小正常，但播放器与 `media info` 都读不出时长，
 * 表现就是"录到了却打不开"，而且看着像 B7 那个播放器有 bug。
 */
object Recordings {

    class Live(
        val id: String,
        val proc: Process,
        val out: File,
        val began: Long,
        val maxMs: Long
    ) {
        @Volatile
        var tail = ""
    }

    /** 一次动作的结果：要么带 id/文件/说明，要么带一句人话的错误。 */
    data class Res(
        val id: String?, val file: File?, val note: String, val error: String?,
        /** 进程自己退的码。null = 还没退/拿不到。0 才说明 `q` 那套收尾真的走通了。 */
        val exit: Int? = null
    ) {
        val failed get() = error != null
    }

    private val map = ConcurrentHashMap<String, Live>()

    fun list(): List<Live> = map.values.sortedBy { it.began }

    fun start(exe: String, argv: List<String>, out: File, maxSeconds: Int): Res {
        out.parentFile?.mkdirs()
        val proc = runCatching {
            ProcessBuilder(argv).directory(out.parentFile).redirectErrorStream(true).start()
        }.getOrElse { return Res(null, null, "", "起不来 ffmpeg：${it.message}") }
        val id = "rec" + System.currentTimeMillis().toString(16).take(8)
        val live = Live(id, proc, out, System.currentTimeMillis(), maxSeconds * 1000L)
        map[id] = live
        // stdout+stderr 是"它到底在不在录"的唯一凭据，也是失败时要回给人看的话
        Thread {
            runCatching {
                proc.inputStream.bufferedReader().forEachLine { live.tail = (live.tail + "\n" + it).takeLast(600) }
            }
        }.apply { isDaemon = true; name = "haoai-rec-$id" }.start()
        // 到点自己停：忘了关的录制会把盘写满，这条是护栏不是装饰
        Thread {
            val left = live.maxMs - (System.currentTimeMillis() - live.began)
            if (left > 0) runCatching { Thread.sleep(left) }
            if (map[id] === live) stop(id)
        }.apply { isDaemon = true; name = "haoai-rec-watch-$id" }.start()
        return Res(id, out, "", null)
    }

    /** 优雅停止：送 `q` → 等它自己收尾 → 实在不退才强杀，并**如实说文件可能不完整**。 */
    fun stop(id: String): Res {
        val live = map[id]
            ?: return Res(null, null, "", "没有这场录制（id=$id）。用 record action=status 看现在在录什么。")
        runCatching { live.proc.outputStream.use { it.write("q".toByteArray()); it.flush() } }
        var graceful = live.proc.waitFor(6, TimeUnit.SECONDS)
        if (!graceful) {
            live.proc.destroy()
            graceful = live.proc.waitFor(4, TimeUnit.SECONDS)
            if (!graceful) live.proc.destroyForcibly()
        }
        map.remove(id)
        val size = if (live.out.isFile) live.out.length() else 0L
        val secs = (System.currentTimeMillis() - live.began) / 1000
        val note = if (!live.out.isFile || size == 0L) ""
        else "约 ${secs}s / $size 字节" + if (graceful) "" else "（强杀收尾，文件可能不完整）"
        val code = runCatching { live.proc.exitValue() }.getOrNull()
        return if (!live.out.isFile || size == 0L) {
            Res(id, live.out, "", "停了，但没留下文件（0 字节）。ffmpeg 最后说的：${live.tail.take(240)}", code)
        } else Res(id, live.out, note, null, code)
    }

    /** 服务退出时调用：别让一台机器留一堆孤儿 ffmpeg 在录屏。 */
    fun stopAll() {
        map.keys.toList().forEach { runCatching { stop(it) } }
    }
}

/** `record`：录屏幕。start / stop / status 三个动作。 */
class RecordTool : Tool(
    "record",
    "录屏（Windows 桌面抓取，产出 mp4）。action=start 开录（可选 fps 默认 15、maxSeconds 默认 300 上限 1800、" +
        "area=\"x,y,宽x高\" 只录一块、audio=\"dshow 设备名\" 顺带录声音）；" +
        "action=stop id=… 结束并收尾文件；action=status 看现在在录什么。" +
        "录像是长任务：开完继续做别的，要停了再 stop。",
    schema("action" to "string", "id" to "string", "fps" to "integer", "maxSeconds" to "integer",
        "area" to "string", "audio" to "string", required = arrayOf("action")),
    kind = "exec"
) {

    override fun run(args: JsonObject, ctx: ToolCtx): ToolResult {
        when ((req(args, "action") ?: "").trim().lowercase()) {
            "status" -> {
                val rows = Recordings.list()
                return ToolResult(if (rows.isEmpty()) "现在没有在录的。开一场：record action=start"
                else rows.joinToString("\n") {
                    "· ${it.id} 已录 ${(System.currentTimeMillis() - it.began) / 1000}s" +
                        "（上限 ${it.maxMs / 1000}s）→ ${ctx.rel(it.out)}"
                })
            }
            "stop" -> {
                // 只有一场在录时不必逼人回去抄 id：录屏的常态就是"开一场、干点事、停掉它"。
                var id = (req(args, "id") ?: "").trim()
                if (id.isBlank()) {
                    val live = Recordings.list()
                    if (live.size == 1) id = live[0].id
                    else return fail(if (live.isEmpty()) "现在没有在录的，不用停" 
                        else "有 ${live.size} 场在录，说清楚停哪一场（record action=status 能看到 id）")
                }
                val r = Recordings.stop(id)
                if (r.failed) return fail(r.error ?: "停不下来")
                val out = r.file
                val playable = out != null && out.isFile && MediaMime.sniff(out) != null
                return ToolResult(
                    content = "已停止：${if (out == null) "?" else ctx.rel(out)}，${r.note}" +
                        if (playable) "。界面里已经能播；要剪就用 media 工具（cut/frame/audio）。" else "",
                    media = if (out != null && playable) listOf(ctx.rel(out)) else emptyList(),
                    card = "terminal"
                )
            }
            "start" -> {
                if (!Env.isWindows) return fail("record 只会录 Windows 桌面（gdigrab），这台机器不是 Windows。")
                if (Recordings.list().size >= 2) return fail("已经在录 ${Recordings.list().size} 场了，先停一场")
                val exe = Ffmpeg.ffmpeg() ?: return fail(Ffmpeg.missing())
                val fps = int(args, "fps", 15).coerceIn(5, 30)
                val maxSec = int(args, "maxSeconds", 300).coerceIn(10, 1800)
                val area = (req(args, "area") ?: "").trim()
                if (area.isNotEmpty() && !AREA.matches(area)) {
                    return fail("area 要写成 \"x,y,宽x高\"（例：0,0,1280x720），现在是「$area」")
                }
                val audio = (req(args, "audio") ?: "").trim()
                if (audio.isNotEmpty() && (audio.length > 80 || audio.any { it == '"' || it == '\n' || it == '\r' })) {
                    return fail("audio 是 dshow 的设备名，别带引号与换行")
                }
                val out = uniqueOut(ctx)
                val argv = build(exe, fps, area, audio, maxSec, out)
                val why = ctx.guard(
                    "shell", "record ${fps}fps ${if (area.isEmpty()) "全屏" else area}" +
                        if (audio.isEmpty()) "" else " +音频",
                    "录屏（最多 ${maxSec}s）→ ${ctx.rel(out)}",
                    { "录屏到 ${ctx.rel(out)}（最多 ${maxSec}s）" }
                )
                if (why != null) return fail(why)
                val r = Recordings.start(exe, argv, out, maxSec)
                if (r.failed) return fail(r.error ?: "起不来")
                return ToolResult("开始录了：id=${r.id}，最多 ${maxSec}s，产出 ${ctx.rel(out)}。" +
                    "做完要录的事就 `record action=stop id=${r.id}` —— " +
                    "停止会把文件收尾好；中途强杀进程会录出一段放不出来的 mp4。")
            }
            else -> return fail("record 只认 start / stop / status")
        }
    }

    private fun uniqueOut(ctx: ToolCtx): File {
        val dir = File(ctx.workspace, "${Env.TOOL_OUTPUT_DIR}/media")
        dir.mkdirs()
        var n = 0
        var f = File(dir, "rec-${System.currentTimeMillis()}.mp4")
        while (f.exists()) f = File(dir, "rec-${System.currentTimeMillis()}-${++n}.mp4")
        return f
    }

    companion object {
        private val AREA = Regex("""^\d+,\d+,\d+x\d+$""")

        /** argv 直接拼、不经 shell：Windows 上带引号的命令走 shell 必被二次解析吃掉。 */
        fun build(exe: String, fps: Int, area: String, audio: String, maxSeconds: Int, out: File): List<String> {
            val a = mutableListOf(exe, "-hide_banner", "-loglevel", "error", "-y",
                "-f", "gdigrab", "-framerate", fps.toString())
            if (area.isEmpty()) {
                a += listOf("-i", "desktop")
            } else {
                val parts = area.split(',')
                val wh = parts[2].split('x')
                a += listOf("-offset_x", parts[0], "-offset_y", parts[1],
                    "-video_size", "${wh[0]}x${wh[1]}", "-i", "desktop")
            }
            if (audio.isNotEmpty()) a += listOf("-f", "dshow", "-i", "audio=$audio") else a += "-an"
            a += listOf("-t", maxSeconds.toString(),
                "-c:v", "libx264", "-preset", "veryfast", "-pix_fmt", "yuv420p", "-movflags", "+faststart")
            if (audio.isNotEmpty()) a += listOf("-c:a", "aac")
            a += out.absolutePath
            return a
        }
    }
}
