package com.haoai.pc

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date

/**
 * 定时任务跑完之后的"结果在哪"。
 *
 * 为什么单独一层：到点起一条会话这件事本身已经能用了，但人真正想知道的是
 * **"它跑完了说什么"** —— 而答案原来只留在那条会话里，得回工位翻侧栏才能看到。
 * 手机联动的常态恰恰是"人不在电脑前"，所以这里把运行账本里 `trigger=定时` 的那些
 * 挑出来做成一份汇总：界面上能看、能导成 md 文件、手机那一端也能读。
 *
 * 刻意**不另存一份数据**：账本（`runs.jsonl`）已经记了时间、标题、结果与轮数，
 * 再抄一份就会出现"两份对不上"的第三种真相。这一层只是取数与排版。
 */
object Digest {

    data class Entry(
        val t: Long,
        val title: String,
        val sid: String,
        val verdict: String,
        val text: String
    ) {
        val at: String get() =
            if (t <= 0L) "?" else SimpleDateFormat("MM-dd HH:mm").format(Date(t))
    }

    /** 汇总里最多列几条：手机上屏就那么大，再多也翻不完。 */
    const val LIMIT = 40

    /** 定时触发的那些运行，最近的在前。手动/排队/续跑的不算（那些人在电脑前，看得见）。 */
    fun entries(limit: Int = LIMIT): List<Entry> =
        RunLedger.rows(400).filter { it.trigger == "定时" }.take(limit).map { r ->
            Entry(r.t, r.title.ifBlank { "(没名字)" }, r.sid, r.verdict, r.out)
        }

    fun json(limit: Int = LIMIT): String {
        val items = entries(limit).joinToString(",") { e ->
            """{"t":${e.t},"at":${js(e.at)},"title":${js(e.title)},"sid":${js(e.sid)},""" +
                """"verdict":${js(e.verdict)},"text":${js(e.text)}}"""
        }
        return """{"ok":true,"items":[$items],"count":${entries(limit).size}}"""
    }

    /** 导成 markdown：给人存档、也方便丢进视频脚本里当素材。 */
    fun markdown(limit: Int = LIMIT): String {
        val es = entries(limit)
        val head = "# 定时任务结果汇总\n\n" +
            "生成于 " + SimpleDateFormat("yyyy-MM-dd HH:mm").format(Date()) +
            " · 最近 " + es.size + " 条（只收定时触发的运行）\n"
        if (es.isEmpty()) return head + "\n还没有跑完过任何定时任务。\n"
        return head + "\n" + es.joinToString("\n") { e ->
            "## ${e.at} · ${e.title}\n\n" +
                "- 结果：${e.verdict}\n" +
                "- 会话：`${e.sid}`\n\n" +
                (if (e.text.isBlank()) "（这一条没有留下正文）\n" else e.text + "\n")
        } + "\n"
    }

    /**
     * 写到工作区的 `.haoai-output/schedule-log.md`，回（相对路径, 给人看的那句）。
     * 文件名是这里的常量，不接受调用方传名字 —— 否则这就成了一个任意路径写文件。
     */
    fun export(workspaceDir: File, limit: Int = LIMIT): Pair<String, String> {
        return try {
            val dir = File(workspaceDir, ".haoai-output")
            dir.mkdirs()
            val f = File(dir, "schedule-log.md")
            f.writeText(markdown(limit), Charsets.UTF_8)
            runCatching { Env.log("digest", "导出 " + f.absolutePath + "（" + entries(limit).size + " 条）") }
            ".haoai-output/schedule-log.md" to "已写出 " + entries(limit).size + " 条到 " + f.absolutePath
        } catch (e: Exception) {
            "" to "导出失败：" + (e.message ?: e.javaClass.simpleName)
        }
    }

    private fun js(s: String): String = "\"" +
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "") + "\""
}
