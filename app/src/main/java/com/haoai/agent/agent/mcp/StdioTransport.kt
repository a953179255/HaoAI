package com.haoai.agent.agent.mcp

import com.haoai.agent.platform.sandbox.Proot
import com.haoai.agent.platform.sandbox.SandboxEnv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader

/**
 * stdio 传输（3.5）：在 Linux 沙箱内拉起本地 MCP 服务器进程，stdin/stdout 走 JSON-RPC。
 * 架构规则沿用 3.3 发现（每宿主进程单 proot）：每台 stdio 服务器一个独立宿主进程
 * （ProcessBuilder 直拉 proot），MCP 服务器即长驻 guest 进程，stdio 经 proot 透明转发；
 * haoai-env 以 exec "$@" 收尾、ash 对单命令 exec 优化，guest 侧零 fork。
 *
 * 分帧：MCP stdio 规范为行分隔 JSON（主路径）；同时兼容 LSP 风格 Content-Length 分帧
 * （方案 3.5 要求，兼容个别以 LSP 方式包装的实现）。stderr 尾部留存用于进程崩溃时的可读报错。
 */
class StdioTransport(
    private val sandbox: SandboxEnv.Sandbox,
    /** 沙箱内启动命令（sh -c 解析，支持参数与重定向）。 */
    private val command: String,
    /** 单次请求的读超时（tools/call 可能长于 initialize）。 */
    private val responseTimeoutMs: Long = 120_000L
) : McpTransport {

private var process: Process? = null
    private val writeMutex = Mutex()
    private val stderrLines = ArrayDeque<String>()
    @Volatile private var stderrTail: String = ""
    private val watchdogPool = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "mcp-stdio-watchdog").apply { isDaemon = true }
    }

    /**
     * stdout 的行 reader（2026-10-10 随 m7 一同引入）。
     *
     * **必须跨请求复用同一个实例**：BufferedReader 构造时会预读 8KB 填满自己的缓冲，
     * 每次新建一个 reader 就等于把缓冲里"不属于本次响应"的字节永久丢掉。
     * 只在进程更换时重建；close() 里关闭。
     */
    private var reader: BufferedReader? = null
    private var readerProcess: Process? = null

    /**
     * readResponse 的串行锁。send() 已在 writeMutex 内，但 readResponse 由 watchdog 线程
     * 与请求协程共享 reader，必须自己再串行一层，否则两个读循环会抢同一个缓冲。
     */
    private val readerLock = Any()

    override suspend fun connect() {
        if (process != null) return // 幂等：重连场景先 close 再 connect
        val argv = sandbox.buildArgs(command) // [proot … /bin/sh -c command]
        val pb = ProcessBuilder(argv)
        pb.redirectErrorStream(false)
        // 宿主环境最小化：只给 proot 需要的 loader/so 路径（避免继承 app 全量环境）
        val env = pb.environment()
        env.clear()
        Proot.environmentOf(sandbox.install).forEach { (k, v) -> env[k] = v }
        val p = pb.start()
        process = p
        // stderr 守护读取：留存尾部 8 行（进程退出/无响应时给出可读原因）
        Thread {
            try {
                p.errorStream.bufferedReader().forEachLine { line ->
                    synchronized(stderrLines) {
                        stderrLines.addLast(line)
                        while (stderrLines.size > 8) stderrLines.removeFirst()
                    }
                }
            } catch (_: Exception) {}
        }.apply { isDaemon = true; name = "mcp-stdio-stderr" }.start()
    }

    override suspend fun send(request: JsonObject): McpHttpResponse = withContext(Dispatchers.IO) {
        val p = process ?: throw IOException("stdio 进程未启动")
        val id = request["id"]
        if (!p.isAlive) throw IOException("MCP 服务器进程已退出（exit ${exitOf(p)}）：${stderrText()}")
        // 通知（无 id，如 notifications/initialized）：服务器不回复，写入即成功
        // （HTTP 传输里对应 202 无体；等待会读到超时）
        if (id == null) {
            writeMutex.withLock {
                try {
                    p.outputStream.write((request.toString() + "\n").toByteArray(Charsets.UTF_8))
                    p.outputStream.flush()
                } catch (e: IOException) {
                    throw IOException("写入 MCP 服务器失败（进程可能已崩溃）：${stderrText()}", e)
                }
            }
            return@withContext McpHttpResponse(202, null)
        }
        writeMutex.withLock {
            try {
                p.outputStream.write((request.toString() + "\n").toByteArray(Charsets.UTF_8))
                p.outputStream.flush()
            } catch (e: IOException) {
                throw IOException("写入 MCP 服务器失败（进程可能已崩溃）：${stderrText()}", e)
            }
        }
// watchdog：读超时后 destroy 进程解除 readLine 阻塞（Linux 上 close 不唤醒阻塞 read，
        // 只有进程退出使 fd 到 EOF 才可靠；超时场景连接本就作废）
        //
      // 2026-10-10 修复（m8）：
        // ① [timedOut] 原是普通局部 var，由 watchdog 线程写、协程线程读且无 @Volatile
        //  → 可见性无保证，超时会退化成"读取失败"丢掉可读原因。改用 AtomicBoolean。
     // ② cancel(false) 对**已启动**的任务不生效也不中断。若响应恰在超时边界姗姗返回，
     //  watchdog 仍会执行 destroy() 把一个健康但慢的服务器杀掉。用 completed 标志互斥。
        val timedOut = java.util.concurrent.atomic.AtomicBoolean(false)
        val completed = java.util.concurrent.atomic.AtomicBoolean(false)
        val watchdog = watchdogPool.schedule({
      if (completed.compareAndSet(false, true)) {
      timedOut.set(true)
      runCatching { p.destroy() }
    }
        }, responseTimeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        try {
    val body = readResponse(p, id, deadline = responseTimeoutMs)
   completed.set(true)
            McpHttpResponse(200, body)
        } catch (e: IOException) {
         completed.set(true)
       throw if (timedOut.get()) IOException("等待 MCP 服务器响应超时（${responseTimeoutMs / 1000}s）：${stderrText()}", e)
            else e
        } finally {
       watchdog.cancel(false)
        }
    }

    override suspend fun close() {
    val p = process ?: return
        process = null
        synchronized(readerLock) {
     runCatching { reader?.close() }
       reader = null
       readerProcess = null
      }
    runCatching { p.outputStream.close() }
   runCatching { p.destroy() }
    // 给 500ms 优雅退出；proot --kill-on-exit 会清掉 guest 侧进程
   if (runCatching { p.waitFor(500, java.util.concurrent.TimeUnit.MILLISECONDS) }.getOrDefault(false)) {
     shutdownWatchdog()
       return
        }
        runCatching { p.destroyForcibly() }
        shutdownWatchdog()
    }

    /**
     * 关掉 watchdog 线程池（2026-10-10 随 m8 修复加入）。
     *
     * 原来 close() 只处理进程不关池子，而 StdioTransport 在 McpManager 的重试循环里
     * **每次尝试都新建一个实例**（3 次尝试 = 3 个池），每次工具调用失败重连又新建一个
     * ⇒ 线程单调泄漏。虽为 daemon 不挡进程退出，但持续吃线程额度与内存。
     */
    private fun shutdownWatchdog() {
    runCatching { watchdogPool.shutdownNow() }
    }

/**
     * 读循环直到出现匹配 id 的响应。跳过：通知（无 id）、服务器→客户端请求（能力不支持，静默忽略）、
     * 混进 stdout 的非 JSON 行；Content-Length 分帧与行 JSON 双兼容。
     * EOF / 读阻塞被 watchdog 解除 / 进程死亡都以 stderr 尾部给出可读原因。
     *
     * 2026-10-10 修复（m7）：原实现每次调用都 `BufferedReader(InputStreamReader(p.inputStream))`。
     * BufferedReader 构造时会预读（默认 8KB）填满自己的缓冲，命中 id 后reader 被丢弃，
     * **已被预读进缓冲的后续响应字节随之丢失** —— 服务器在响应后立刻推送下一条
     * （notifications/message、批量 tools/call）时，下一次 readResponse 从丢失处继续，
     * 只能等到 responseTimeoutMs（默认 120s）超时。
     * 修法：reader 提为实例字段，随 transport 生命周期创建/关闭，跨请求复用。
     */
    private fun readResponse(p: Process, id: kotlinx.serialization.json.JsonElement?, deadline: Long): JsonObject {
        val reader = readerFor(p)
        while (true) {
      val line = try {
        reader.readLine()
      } catch (_: Exception) {
       throw IOException("读取 MCP 服务器输出失败：${stderrText()}")
            } ?: throw IOException(
          "MCP 服务器关闭了输出流（exit ${exitOf(p)}）：${stderrText()}"
      )
            val trimmed = line.trim()
 if (trimmed.isEmpty()) continue
    if (trimmed.startsWith("Content-Length:", ignoreCase = true)) {
            // LSP 风格：读完头块（空行止），按声明长度读负载
        val payloadLen = trimmed.substringAfter(":").trim().toIntOrNull() ?: continue
     while (reader.readLine()?.isNotEmpty() == true) { /* 头块其余行 */ }
   val buf = CharArray(payloadLen)
  var off = 0
        while (off < payloadLen) {
   val n = reader.read(buf, off, payloadLen - off)
            if (n < 0) throw IOException("MCP 服务器输出中断：${stderrText()}")
        off += n
        }
       asResponseWithId(String(buf), id)?.let { return it }
    continue
  }
          asResponseWithId(trimmed, id)?.let { return it }
  }
    }

    /**
     * 取（并缓存）该进程的 reader。**必须复用同一个实例** —— 见 [readResponse] 的说明：
     * 每次新建 BufferedReader 会预读并丢弃缓冲里不属于本次响应的字节。
     */
    private fun readerFor(p: Process): BufferedReader {
        readerLock?.let { l ->
            synchronized(l) {
                if (reader == null || readerProcess !== p) {
                    runCatching { reader?.close() }
                    reader = BufferedReader(InputStreamReader(p.inputStream, Charsets.UTF_8))
                    readerProcess = p
                }
                return reader!!
            }
        }
        return BufferedReader(InputStreamReader(p.inputStream, Charsets.UTF_8))
    }

    /** 行文本 → 匹配 id 的响应 JSON（不匹配/不可解析返回 null，调用方继续读）。 */
    private fun asResponseWithId(text: String, id: kotlinx.serialization.json.JsonElement?): JsonObject? {
        if (!text.startsWith("{")) return null
        val obj = runCatching {
            com.haoai.agent.data.HaoJson.json.parseToJsonElement(text) as? JsonObject
        }.getOrNull() ?: return null
        if (id == null) return obj // 无 id 的请求（理论上不发生）：第一条 JSON 即响应
        val respId = obj["id"] ?: return null // 通知：跳过
        return if (respId == id) obj else null // 服务器→客户端请求或旧响应：跳过
    }

    private fun exitOf(p: Process): Int =
        runCatching { p.exitValue() }.getOrElse { -1 }

    private fun stderrText(): String =
        synchronized(stderrLines) { stderrLines.joinToString("\n") }.take(400).ifBlank { "（无 stderr 输出）" }
}
