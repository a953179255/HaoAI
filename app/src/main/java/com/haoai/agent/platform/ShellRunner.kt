package com.haoai.agent.platform

import java.io.File
import java.util.concurrent.TimeUnit

object ShellRunner {

    data class Result(val exitCode: Int, val output: String)

    const val MAX_OUTPUT = 64 * 1024

    /** toybox 后端：/system/bin/sh -c <command>（3.3 前的既有入口，行为不变）。 */
    fun exec(workdir: File?, command: String, timeoutMs: Long): Result =
        execList(listOf("/system/bin/sh", "-c", command), workdir, timeoutMs)

    /**
     * 通用进程执行（3.3 多后端共用）：完整 argv + 可选环境变量 + 可中断。
     * 超时/中断均杀进程树；输出合并 stdout+stderr（与 toybox 现状一致，保序）。
     * @param clearEnv true 时清空继承环境（proot 沙箱用：app 的 ART/TMPDIR 等
     *   变量会泄漏进 guest，且实测会让 guest 内 fork 失效）。
     */
    fun execList(
        argv: List<String>,
        workdir: File?,
        timeoutMs: Long,
        env: Map<String, String> = emptyMap(),
        clearEnv: Boolean = false
    ): Result {
        val pb = ProcessBuilder(argv)
            .redirectErrorStream(true)
        if (workdir != null) {
            pb.directory(workdir)
        }
        if (clearEnv) {
            val pe = pb.environment()
            pe.clear()
        }
        if (env.isNotEmpty()) {
            val pe = pb.environment()
            for ((k, v) in env) pe[k] = v
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
