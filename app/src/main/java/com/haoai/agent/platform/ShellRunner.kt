package com.haoai.agent.platform

import java.io.File
import java.util.concurrent.TimeUnit

object ShellRunner {

    data class Result(val exitCode: Int, val output: String)

    private const val MAX_OUTPUT = 64 * 1024

    fun exec(workdir: File?, command: String, timeoutMs: Long): Result {
        val pb = ProcessBuilder("/system/bin/sh", "-c", command)
            .redirectErrorStream(true)
        if (workdir != null) {
            pb.directory(workdir)
        }
        val process = try {
            pb.start()
        } catch (e: java.io.IOException) {
            return Result(-1, "无法启动 shell：${e.message}")
        }

        // 按字节累积、最后一次性解码：按 8KB 分块各自 decode 会把跨块的多字节
        // UTF-8 字符（如中文）劈成乱码
        val byteBuf = java.io.ByteArrayOutputStream()
        val pump = Thread {
            process.inputStream.use { ins ->
                val buf = ByteArray(8192)
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    synchronized(byteBuf) {
                        if (byteBuf.size() < MAX_OUTPUT) byteBuf.write(buf, 0, n)
                    }
                }
            }
        }
        pump.isDaemon = true
        pump.start()

        try {
            val finished = runCatching {
                process.waitFor(timeoutMs.coerceIn(1000, 300_000), TimeUnit.MILLISECONDS)
            }.getOrDefault(false)

            if (!finished) {
                process.destroy()
                runCatching { process.waitFor(2000, TimeUnit.MILLISECONDS) }
                if (process.isAlive) process.destroyForcibly()
                val partial = synchronized(byteBuf) { String(byteBuf.toByteArray(), Charsets.UTF_8) }
                return Result(-1, "$partial\n[执行超时（${timeoutMs}ms），已终止]")
            }

            runCatching { pump.join(1500) }
            val output = synchronized(byteBuf) { String(byteBuf.toByteArray(), Charsets.UTF_8) }
            return Result(process.exitValue(), output.ifBlank { "(无输出)" })
        } catch (ie: InterruptedException) {
            // 调用协程被取消（用户按停止）：必须杀掉子进程，否则命令继续在后台跑
            process.destroy()
            runCatching { process.waitFor(2000, TimeUnit.MILLISECONDS) }
            if (process.isAlive) process.destroyForcibly()
            throw ie
        }
    }
}
