package com.haoai.agent.agent.tools.shell

import com.haoai.agent.platform.ShellRunner
import com.haoai.agent.platform.sandbox.SandboxEnv
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.withLock
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter

/**
 * bash 多后端抽象（3.3）：toybox（Android 原生）/ linux（PRoot 沙箱）/ ssh（远程）。
 * 输出统一合并 stdout+stderr（与 toybox 现状一致保序，避免拆流后丢失交错信息）。
 */
interface ShellBackend {
    val id: String

    /** 执行命令；超时/用户停止（协程取消）时进程被杀。 */
    suspend fun exec(command: String, timeoutMs: Long): ExecResult

    data class ExecResult(val exitCode: Int, val output: String, val durationMs: Long)
}

/** toybox 后端：现状 BashTool 逻辑原样迁移（/system/bin/sh，工作目录=工作空间）。 */
class ToyboxBackend(private val workdir: File?) : ShellBackend {

    override val id = "toybox"

    override suspend fun exec(command: String, timeoutMs: Long): ShellBackend.ExecResult {
        val start = System.currentTimeMillis()
        val r = runInterruptible { ShellRunner.exec(workdir, command, timeoutMs) }
        return ShellBackend.ExecResult(r.exitCode, r.output, System.currentTimeMillis() - start)
    }
}

/**
 * linux 后端：**常驻 guest shell**（上游 hiddenExec 同款架构）。
 *
 * 为什么常驻：同一宿主进程内起第二个 proot 会话时，guest 内 fork/clone 会被
 * 内核拒绝（"can't fork: Function not implemented"）——首个会话一切正常，之后
 * 的会话恒定失败，疑似 proot 退出后 ptrace/tracer 状态未完全清理（API 36 模拟器
 * 实锤复现：首个会话 probe-ok，第二个会话必挂；真机单会话正常）。因此每宿主
 * 进程只启动一个 proot，所有命令写入同一 guest shell 的 stdin，用 BEGIN/END
 * 标记协议取回输出与退出码。
 */
class ProotBackend private constructor(
    private val sandbox: SandboxEnv.Sandbox
) : ShellBackend {

    override val id = "linux"

    private data class Session(
        val process: Process,
        val stdin: OutputStreamWriter,
        val stdout: BufferedReader
    )

    private var session: Session? = null
    private var seq = 0

    // 同一会话的 stdin/stdout 是单流水线：系统提示探测、用户命令等并发调用必须串行
    private val mutex = kotlinx.coroutines.sync.Mutex()

    private fun startSession(): Session {
        val argv = sandbox.buildSessionArgs()
        val env = com.haoai.agent.platform.sandbox.Proot.environmentOf(sandbox.install) +
            mapOf(
                "PATH" to "/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin",
                "HOME" to "/root",
                "TMPDIR" to "/tmp"
            )
        val pb = ProcessBuilder(argv).redirectErrorStream(true)
        pb.directory(sandbox.install.libDir)
        val pe = pb.environment()
        pe.clear()
        for ((k, v) in env) pe[k] = v
        val p = pb.start()
        return Session(
            p,
            OutputStreamWriter(p.outputStream, Charsets.UTF_8),
            BufferedReader(InputStreamReader(p.inputStream, Charsets.UTF_8), ShellRunner.MAX_OUTPUT)
        )
    }

    @Synchronized
    private fun ensureSession(): Session {
        val s = session
        if (s != null && s.process.isAlive) return s
        runCatching { s?.process?.destroy() }
        return startSession().also { session = it }
    }

    override suspend fun exec(command: String, timeoutMs: Long): ShellBackend.ExecResult {
        val start = System.currentTimeMillis()
        return mutex.withLock {
            runInterruptible {
                try {
                    runCommand(command, timeoutMs, start)
                } catch (e: java.io.IOException) {
                    // 会话已死（proot 被系统回收等）：重建一次重试
                    runCatching { session?.process?.destroy() }
                    session = null
                    runCommand(command, timeoutMs, System.currentTimeMillis())
                }
            }
        }
    }

    private fun runCommand(command: String, timeoutMs: Long, start: Long): ShellBackend.ExecResult {
        val s = ensureSession()
        seq += 1
        val token = "HAOAI_END_${seq}_${System.currentTimeMillis()}"
        // 上游 hiddenExec 同款：命令原文经引号 heredoc 写临时脚本（零展开、防注入），
        // 执行后回传 END 标记 + exit code
        val payload = buildString {
            append("cat > /tmp/.haoai_")
            append(token)
            appendLine(" <<'EOF_HAOAI_CMD'")
            append(command)
            appendLine()
            appendLine("EOF_HAOAI_CMD")
            appendLine("cd /workspace 2>/dev/null || cd /root")
            append("sh /tmp/.haoai_")
            append(token)
            appendLine(" 2>&1")
            appendLine("rc=$?")
            append("rm -f /tmp/.haoai_")
            append(token)
            appendLine()
            appendLine("echo \"__HAOAI_END_" + token + "_\$rc\"")
        }
        try {
            s.stdin.write(payload + "\n")
            s.stdin.flush()
        } catch (e: java.io.IOException) {
            throw e
        }
        return readUntilEnd(s, token, timeoutMs, start)
    }

    private fun readUntilEnd(s: Session, token: String, timeoutMs: Long, start: Long): ShellBackend.ExecResult {
        val buf = StringBuilder()
        val endTag = "__HAOAI_END_${token}_"
        while (true) {
            val elapsed = System.currentTimeMillis() - start
            if (elapsed > timeoutMs) {
                killSession(s)
                return ShellBackend.ExecResult(
                    -1,
                    (if (buf.isNotEmpty()) buf.toString() + "\n" else "") + "[执行超时（${timeoutMs}ms），已终止会话]",
                    elapsed
                )
            }
            if (!s.stdout.ready()) {
                if (!s.process.isAlive) {
                    session = null
                    return ShellBackend.ExecResult(
                        -1,
                        buf.toString().ifBlank { "proot 会话异常退出" },
                        elapsed
                    )
                }
                Thread.sleep(40)
                continue
            }
            val line = s.stdout.readLine() ?: run {
                session = null
                return ShellBackend.ExecResult(
                    -1,
                    buf.toString().ifBlank { "proot 会话已关闭" },
                    elapsed
                )
            }
            if (line.startsWith(endTag)) {
                val rc = line.removePrefix(endTag).trim().toIntOrNull() ?: -1
                return ShellBackend.ExecResult(rc, buf.toString().ifBlank { "(无输出)" }, elapsed)
            }
            if (buf.length < ShellRunner.MAX_OUTPUT) buf.appendLine(line)
        }
    }

    private fun killSession(s: Session) {
        runCatching { s.process.destroy() }
        runCatching { s.process.waitFor(2_000, java.util.concurrent.TimeUnit.MILLISECONDS) }
        if (s.process.isAlive) runCatching { s.process.destroyForcibly() }
        if (session === s) session = null
    }

    companion object {
        /** 进程级单例（核心约束：同一宿主进程只允许一个活跃 proot 会话，见类注释）。 */
        @Volatile private var shared: ProotBackend? = null

        fun forSandbox(sandbox: SandboxEnv.Sandbox): ProotBackend =
            shared ?: synchronized(this) {
                shared ?: ProotBackend(sandbox).also { shared = it }
            }

        /** 发行版安装/删除后调用：强制重建会话。 */
        fun invalidate() {
            synchronized(this) {
                shared?.let { it.killSession(it.session ?: return@synchronized) }
                shared = null
            }
        }
    }
}
