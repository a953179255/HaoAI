package com.haoai.agent.agent.tools

import com.haoai.agent.platform.PermissionCenter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * 后台任务输出查看（3.4）：读 /workspace/.haoai-jobs/<id>.log 尾部。
 * 日志由 bash(background=true) 投递产生，落在宿主工作区（沙箱 /workspace 即宿主绑定），
 * 宿主侧直接读文件，无需起沙箱。完成后日志尾部有 __JOB_DONE_<rc> 标记。
 */
class JobOutputTool : Tool {

    override val name = "job_output"
    override val desc =
        "查看后台任务的输出日志（配合 bash 工具的 background=true 使用）。返回日志尾部与运行状态（运行中/已结束及退出码）。可省略 id 查看最近一个任务。"
    override val params = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("id") {
                put("type", "string")
                put("description", "bash 返回的任务 id（如 job_xxx）；省略时取最近投递的任务")
            }
            putJsonObject("tail_lines") {
                put("type", "integer")
                put("description", "返回末尾行数，默认 60，上限 500")
            }
        }
    }

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            val dir = ctx.shellDir
                ?: return@withContext ToolResult("当前工作空间不支持后台任务日志（SAF 目录）", true)
            val jobsDir = java.io.File(dir, ".haoai-jobs")
            if (!jobsDir.isDirectory) {
                return@withContext ToolResult("还没有后台任务（用 bash 的 background=true 投递）")
            }
            val id = args.optString("id").trim()
            // 路径安全：id 只允许真实投递格式（job_<base36>），杜绝 "../" 拼路径逃出 .haoai-jobs
            if (id.isNotBlank() && !Regex("job_[0-9a-z]+").matches(id)) {
                return@withContext ToolResult("非法任务 id：$id（应为 bash background=true 返回的 job_xxx）", true)
            }
            val logFile = if (id.isNotBlank()) {
                java.io.File(jobsDir, "$id.log").takeIf { it.isFile }
                    ?: return@withContext ToolResult("找不到任务日志：$id（jobs 目录：.haoai-jobs/，可用 ls 查看）", true)
            } else {
                jobsDir.listFiles { f -> f.isFile && f.name.endsWith(".log") }
                    ?.maxByOrNull { it.lastModified() }
                    ?: return@withContext ToolResult("还没有后台任务日志")
            }

            // 读文件前触发一次存储权限引导（工作区在共享存储的场景）
            PermissionCenter.ensureStorageIfOutside(
                ctx.appContext, logFile.name, dir.absolutePath
            )

            val tailN = (args.optInt("tail_lines") ?: 60).coerceIn(1, 500)
            // 尾部窗口读取：长构建日志可达数十 MB，整文件 readLines 每次轮询全量解析太重；
            // __JOB_DONE_ 标记恒为末行（投递脚本最后写入），窗口覆盖标记 + tail 即可
            val lines = readTail(logFile, maxOf(64 * 1024, (tailN + 16) * 256))
            var status = "运行中"
            var rcNote = ""
            val body = if (lines.lastOrNull()?.startsWith("__JOB_DONE_") == true) {
                status = "已结束"
                rcNote = "，退出码 ${lines.last().removePrefix("__JOB_DONE_").trim()}"
                lines.dropLast(1)
            } else lines
            val tail = body.takeLast(tailN)
            val head = "${logFile.nameWithoutExtension}（$status$rcNote）\n---\n"
            ToolResult(head + if (tail.isEmpty()) "(暂无输出)" else tail.joinToString("\n"))
        }

    /** 只读文件尾部 maxBytes 字节；起始行可能是被截断的残片，丢弃到首个换行。 */
    private fun readTail(file: java.io.File, maxBytes: Int): List<String> {
        val len = file.length()
        if (len <= 0L) return emptyList()
        val start = maxOf(0L, len - maxBytes)
        java.io.RandomAccessFile(file, "r").use { raf ->
            raf.seek(start)
            val buf = ByteArray((len - start).toInt())
            raf.readFully(buf)
            var text = String(buf, Charsets.UTF_8)
            if (start > 0L) text = text.substringAfter('\n', "")
            return text.lines().let { if (it.lastOrNull()?.isEmpty() == true) it.dropLast(1) else it }
        }
    }
}
