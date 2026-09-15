package com.haoai.agent.agent.tools.shell

import com.haoai.agent.platform.ShellRunner
import com.haoai.agent.platform.sandbox.SandboxEnv
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.withLock
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

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
 * linux 后端：**每条命令一次性 proot 进程**（对齐 termux proot-distro 的 `run` 模型）。
 *
 * 为什么不再常驻（2026-09-15 重构）：旧实现维持一个常驻 guest shell、命令写 stdin
 * 用 BEGIN/END 标记协议取输出——真机实测「会话活着但僵死」：guest sh 卡 pipe_read、
 * 命令写进死管道石沉大海（用户反馈 Ubuntu「用不了」、手机端 Agent 报「无回显」的根因）。
 * proot 用 --kill-on-exit + ptrace，进程彻底退出即回收，一次性模型无死会话残留风险，
 * 也是业界（proot-distro/Termux）的标准做法。
 *
 * 顺序化：mutex 保证同一宿主进程内 proot 串行（满足「仅一个活跃 proot」的约束），
 * 每条命令 proot 进程结束后才起下一条，正常不会有并发 proot。
 */
class ProotBackend private constructor(
    private val sandbox: SandboxEnv.Sandbox
) : ShellBackend {

    override val id = "linux"

    // 串行化并发 exec（探测 / 用户命令）：同一时刻仅一个 proot 进程存活
    private val mutex = kotlinx.coroutines.sync.Mutex()

    /** 上次命令被超时/停止击杀（可能打断 dpkg 事务）→ 下次命令前先静默 dpkg --configure -a 自愈。 */
    @Volatile private var repairPending = false

    private fun envOf(): Map<String, String> =
        com.haoai.agent.platform.sandbox.Proot.environmentOf(sandbox.install) + mapOf(
            "PATH" to "/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin",
            "HOME" to "/root",
            "TMPDIR" to "/tmp"
        )

    override suspend fun exec(command: String, timeoutMs: Long): ShellBackend.ExecResult {
        val start = System.currentTimeMillis()
        return mutex.withLock {
            runInterruptible {
                // 自愈紧跟在击杀之后：单独跑一次 configure -a（自身也可能慢，给足预算）
                if (repairPending) {
                    repairPending = false
                    runOnce("[ -x /usr/bin/dpkg ] && dpkg --configure -a >/dev/null 2>&1; true", 90_000)
                }
                // 若仍有上次中断残留的 dpkg 事务，命令前先自愈（幂等、快）
                runOnce(command, timeoutMs, start)
            }
        }
    }

    /**
     * 起一个一次性 proot 跑 command：命令经引号 heredoc 落成 guest 临时脚本执行
     * （零展开、防注入），末尾 `; echo __HAOAI_RC_$?` 回传退出码，读进程输出直到退出，
     * 超时 destroyForcibly 杀进程（置 repairPending 供下次自愈）。
     */
    private fun runOnce(command: String, timeoutMs: Long, startMs: Long = System.currentTimeMillis()): ShellBackend.ExecResult {
        val token = "HAOAI_" + java.lang.Long.toString(System.currentTimeMillis(), 36)
        val guestScript = "/tmp/.haoai_$token.sh"
        val wrapped = buildString {
            append("cat > ").append(guestScript).append(" <<'EOF_HAOAI_CMD'\n")
            append(command)
            if (!command.endsWith("\n")) append("\n")
            appendLine("EOF_HAOAI_CMD")
            appendLine("cd /workspace 2>/dev/null || cd /root")
            append("sh ").append(guestScript).append(" 2>&1; rc=$?; rm -f ").append(guestScript)
            appendLine("; echo \"__HAOAI_RC_${token}_\$rc\"")
        }
        val argv = sandbox.buildArgs(wrapped)
        val env = envOf()
        val pb = ProcessBuilder(argv).redirectErrorStream(true)
        pb.directory(sandbox.install.libDir)
        pb.environment().apply { clear(); for ((k, v) in env) put(k, v) }
        val start = if (startMs == 0L) System.currentTimeMillis() else startMs
        val proc = try {
            pb.start()
        } catch (e: Exception) {
            return ShellBackend.ExecResult(-1, "proot 启动失败：${e.message}", System.currentTimeMillis() - start)
        }
        val reader = BufferedReader(InputStreamReader(proc.inputStream, Charsets.UTF_8), ShellRunner.MAX_OUTPUT)
        val endTag = "__HAOAI_RC_${token}_"
        val buf = StringBuilder()
        val exitRef = java.util.concurrent.atomic.AtomicInteger(-1)
        val done = java.util.concurrent.CountDownLatch(1)
        // 异步读：readLine 会阻塞，若 proot 既不输出又不退（挂死），同步读会让超时检查失效、
        // 工具调用被引擎层超时取消且拿不到任何输出（真机/模拟器「无回显」根因）。改由读线程
        // 负责读尽/命中 END，主线程按超时等待并在超时后强杀进程（杀进程会关流，读线程随之退出）。
        val readerThread = Thread {
            try {
                while (true) {
                    val line = reader.readLine() ?: break // 输出关闭 → 读尽
                    if (line.startsWith(endTag)) {
                        exitRef.set(line.removePrefix(endTag).trim().toIntOrNull() ?: -1)
                        break
                    }
                    if (buf.length < ShellRunner.MAX_OUTPUT) buf.appendLine(line)
                }
            } catch (_: Exception) {
                // 进程被杀/流中断：按已读输出返回
            } finally {
                done.countDown()
            }
        }.apply { isDaemon = true; name = "proot-read-$token" }
        readerThread.start()
        val finished = try {
            done.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            // 用户停止 / 协程取消：清理后原样抛出，交由上层识别为取消
            runCatching { proc.destroyForcibly() }
            runCatching { done.await(1, java.util.concurrent.TimeUnit.SECONDS) }
            throw e
        }
        val terminated = !finished
        if (terminated) repairPending = true // 未干净退出（超时）→ 可能打断 dpkg 事务，下次自愈
        if (proc.isAlive) {
            proc.destroy()
            if (!proc.waitFor(2_000, java.util.concurrent.TimeUnit.MILLISECONDS)) proc.destroyForcibly()
        }
        runCatching { done.await(2, java.util.concurrent.TimeUnit.SECONDS) } // 等读线程收尾
        runCatching { reader.close() }
        val exit = exitRef.get()
        val dur = System.currentTimeMillis() - start
        return if (terminated) {
            ShellBackend.ExecResult(
                -1,
                (if (buf.isNotEmpty()) buf.toString() + "\n" else "") + "[执行超时（${timeoutMs}ms），已终止]",
                dur
            )
        } else {
            ShellBackend.ExecResult(if (exit == -1 && buf.isBlank()) -1 else exit, buf.toString().ifBlank { "(无输出)" }, dur)
        }
    }

    companion object {
        /** 每 rootfs 一个实例（无残留进程状态，仅承载串行 mutex 与自愈标志）；
         *  换装/重装发行版（rootfs 路径变）自动切到新实例。 */
        @Volatile private var shared: ProotBackend? = null

        fun forSandbox(sandbox: SandboxEnv.Sandbox): ProotBackend {
            shared?.takeIf { it.sandbox.rootfs == sandbox.rootfs }?.let { return it }
            return synchronized(this) {
                shared?.takeIf { it.sandbox.rootfs == sandbox.rootfs }
                    ?: ProotBackend(sandbox).also { shared = it }
            }
        }

        /** 发行版安装/删除后调用：丢弃缓存实例（下一命令重建）。一次性进程无残留，无需杀会话。 */
        fun invalidate() {
            synchronized(this) { shared = null }
        }
    }
}
