package com.haoai.agent.ui.chat

import com.haoai.agent.ui.UiFilePath
import java.io.File
import java.io.RandomAccessFile

/**
 * 批3c：后台任务日志的纯读取层。
 *
 * 日志契约（引擎侧 BashTool 投递脚本定的，这里只是读方）：
 * `nohup … > /workspace/.haoai-jobs/<id>.log`，投递成功时 bash 结果文本带
 * "后台任务已投递：job_xxx"；任务结束时脚本往日志**末行**追加 `__JOB_DONE_<rc>`。
 * 所以 UI 读文件本身就能同时拿到输出与状态，不需要进程句柄。
 */

/** 尾部窗口读取的字节上限：构建日志可达数十 MB，UI 只贴最近一段。 */
internal const val JOB_LOG_TAIL_BYTES = 256 * 1024

/**
 * 只读文件末尾 [maxBytes] 字节并按行切；起始行可能是被截断的残片，丢弃到首个换行。
 * 文件不存在/读失败返回空表（调用方按"暂无输出"处理）。
 */
internal fun readTailLines(file: File, maxBytes: Int = JOB_LOG_TAIL_BYTES): List<String> {
    val len = if (file.isFile) file.length() else 0L
    if (len <= 0L) return emptyList()
    return runCatching {
        RandomAccessFile(file, "r").use { raf ->
            val start = maxOf(0L, len - maxBytes)
            raf.seek(start)
            val buf = ByteArray((len - start).toInt())
            raf.readFully(buf)
            var text = String(buf, Charsets.UTF_8)
            if (start > 0L) text = text.substringAfter('\n', "")
            text.lines().let { if (it.lastOrNull()?.isEmpty() == true) it.dropLast(1) else it }
        }
    }.getOrDefault(emptyList())
}

/** 任务状态：运行中 / 已结束（带退出码）。done 时剥掉标记行。 */
data class JobLogView(val lines: List<String>, val running: Boolean, val exitCode: String)

/** 从尾部行解析状态并剥掉 `__JOB_DONE_<rc>` 标记行。 */
internal fun parseJobLog(lines: List<String>): JobLogView {
    val last = lines.lastOrNull()
    return if (last != null && last.startsWith("__JOB_DONE_")) {
        JobLogView(lines.dropLast(1), running = false, exitCode = last.removePrefix("__JOB_DONE_").trim())
    } else {
        JobLogView(lines, running = true, exitCode = "")
    }
}

/** 轮询间隔：日志是文件追加，没有推送通道，2s 一尾窗与 job_output 工具同节奏。 */
internal const val JOB_LOG_POLL_MS = 2_000L

/**
 * 从 bash 结果文本解析后台任务日志定位：BashTool 投递成功回
 * "后台任务已投递：job_xxx"，先认这句前缀（模型跑 `echo job_fake` 之类的普通
 * 输出不该长出芯片），id 再按真实投递格式（job_ + base36）严格匹配。
 * root 为 null（SAF）同样 null。
 */
internal fun parseJobDispatch(text: String?, root: File?): UiFilePath? {
    if (text.isNullOrBlank() || root == null) return null
    if (!text.contains("后台任务已投递")) return null
    val id = Regex("job_[0-9a-z]+").find(text)?.value ?: return null
    return UiFilePath(
        relPath = ".haoai-jobs/$id.log",
        absPath = File(File(root, ".haoai-jobs"), "$id.log").absolutePath
    )
}
