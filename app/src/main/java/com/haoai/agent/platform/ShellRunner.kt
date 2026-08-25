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

        val buffer = StringBuilder()
        val pump = Thread {
            process.inputStream.use { ins ->
                val buf = ByteArray(8192)
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    synchronized(buffer) {
                        if (buffer.length < MAX_OUTPUT) {
                            buffer.append(String(buf, 0, n, Charsets.UTF_8))
                        }
                    }
                }
            }
        }
        pump.isDaemon = true
        pump.start()

        val finished = runCatching {
            process.waitFor(timeoutMs.coerceIn(1000, 300_000), TimeUnit.MILLISECONDS)
        }.getOrDefault(false)

        if (!finished) {
            process.destroy()
            runCatching { process.waitFor(2000, TimeUnit.MILLISECONDS) }
            if (process.isAlive) process.destroyForcibly()
            val partial = synchronized(buffer) { buffer.toString() }
            return Result(-1, "$partial\n[执行超时（${timeoutMs}ms），已终止]")
        }

        runCatching { pump.join(1500) }
        val output = synchronized(buffer) { buffer.toString() }
        return Result(process.exitValue(), output.ifBlank { "(无输出)" })
    }
}
