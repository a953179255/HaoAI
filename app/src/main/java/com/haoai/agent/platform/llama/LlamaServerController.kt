package com.haoai.agent.platform.llama

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

sealed interface LlamaState {
    data object Stopped : LlamaState
    data class Starting(val detail: String = "") : LlamaState
    data class Running(val modelFile: String, val pid: Int = 0) : LlamaState
    data class Failed(val message: String) : LlamaState
}

class LlamaServerController(
    private val context: Context,
    private val okHttpClient: OkHttpClient
) {

    companion object {
        const val PORT = 8081
        const val LOCAL_BASE_URL = "http://127.0.0.1:$PORT"
        const val LOCAL_PROVIDER_ID = "local"
        const val LOCAL_PROVIDER_BASE = "local://llama"

        const val DEFAULT_MODEL_URL =
            "https://hf-mirror.com/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf"
        private const val HEALTH_TRIES = 240

        private const val HEALTH_INTERVAL_MS = 500L

        private const val MAX_LOG_LINES = 2000
    }

    private val _state = MutableStateFlow<LlamaState>(LlamaState.Stopped)
    val state = _state.asStateFlow()

    private val _downloadProgress = MutableStateFlow<Float?>(null)
    val downloadProgress = _downloadProgress.asStateFlow()

    @Volatile
    private var process: Process? = null

    /** 用户手动选择的端侧模型文件名；null 表示自动（取最大）。 */
    @Volatile
    var preferredModel: String? = null

    /** 当前已加载模型的绝对路径（未运行时为 null）；供「指定不同模型需重启」判断。 */
    @Volatile
    var currentModelPath: String? = null
        private set

    /** 端侧上下文窗口（-c 参数）。Agent 工具流需要大上下文，默认 64K；小内存设备可下调。 */
    @Volatile
    var contextSize: Int = 65536

    private val startMutex = kotlinx.coroutines.sync.Mutex()

    fun modelsDir(): File = File(context.getExternalFilesDir(null), "models").apply { mkdirs() }

    private fun internalModelsDir(): File = File(context.filesDir, "models")

    fun findModel(): File? {
        // 支持免拷贝直读：preferredModel 可以是手机上任意位置的绝对路径
        preferredModel?.let { pref ->
            if (pref.startsWith("/")) {
                val f = File(pref)
                if (f.exists() && f.length() > 1_000_000 && !isMmprojFile(f)) return f
            } else {
                listModels().firstOrNull { it.name == pref }?.let { return it }
            }
        }
        return listModels().maxByOrNull { it.length() }
    }

    fun listModels(): List<File> {
        val external = modelsDir().listFiles { f -> f.name.endsWith(".gguf") }?.toList() ?: emptyList()
        val internal = internalModelsDir().listFiles { f -> f.name.endsWith(".gguf") }?.toList() ?: emptyList()
        return (external + internal)
            .filter { it.length() > 1_000_000 && !isMmprojFile(it) }
            .sortedByDescending { it.length() }
    }

    /**
     * 扫描手机公共目录里已有的 GGUF 模型（Download/Documents/models 及存储根目录，
     * 含一级子目录），供「免拷贝直读」选择——不再复制一份浪费空间。
     * 需要已授予「所有文件访问」；未授权时返回空列表。
     */
    fun scanDeviceModels(): List<File> {
        val roots = listOfNotNull(
            java.io.File("/storage/emulated/0"),
            java.io.File("/storage/emulated/0/Download"),
            java.io.File("/storage/emulated/0/Documents"),
            java.io.File("/storage/emulated/0/models"),
            java.io.File("/storage/emulated/0/Models")
        ).distinctBy { it.absolutePath }
        val own = listOf(modelsDir().absolutePath, internalModelsDir().absolutePath)
        val out = LinkedHashSet<File>()
        for (r in roots) {
            runCatching {
                if (!r.isDirectory) return@runCatching
                r.listFiles { f -> f.isFile && f.name.endsWith(".gguf", true) }
                    ?.filter { it.length() > 50_000_000 }
                    ?.let { out.addAll(it) }
                r.listFiles { f -> f.isDirectory }?.take(30)?.forEach { sub ->
                    sub.listFiles { f -> f.isFile && f.name.endsWith(".gguf", true) }
                        ?.filter { it.length() > 50_000_000 }
                        ?.let { out.addAll(it) }
                }
            }
        }
        return out.filter { f -> own.none { prefix -> f.absolutePath.startsWith(prefix) } }
            .sortedByDescending { it.length() }
    }

    /** 视觉投影文件：.mmproj 后缀，或文件名含 mmproj 的 gguf（常见命名 X-mmproj.gguf）。 */
    fun isMmprojFile(f: File): Boolean =
        f.name.endsWith(".mmproj", ignoreCase = true) || f.name.contains("mmproj", ignoreCase = true)

    /**
     * 查找视觉投影文件：必须与模型同名（或以模型名为前缀）。
     * 绝不回退到「任意 mmproj」——不匹配的 mmproj 会让 server 启动即崩
     * （mtmd_init_from_file: mismatch between text model and mmproj n_embd）。
     */
    fun findMmproj(model: File): File? {
        val all = ((modelsDir().listFiles { f -> isMmprojFile(f) }?.toList() ?: emptyList()) +
            (internalModelsDir().listFiles { f -> isMmprojFile(f) }?.toList() ?: emptyList())).toMutableList()
        // 直读的设备模型：投影文件与模型同目录放置时也能被发现
        if (model.absolutePath.startsWith("/")) {
            model.parentFile?.listFiles { f -> isMmprojFile(f) }?.let { all += it.toList() }
        }
        if (all.isEmpty()) return null
        val base = model.name.removeSuffix(".gguf")
        return all.firstOrNull {
            it.nameWithoutExtension == base || it.nameWithoutExtension.startsWith("$base-")
        }
    }

    fun binaryPath(): String? {
        val f = File(context.applicationInfo.nativeLibraryDir, "libllamaserver.so")
        return if (f.exists()) f.absolutePath else null
    }

    suspend fun ensureStarted(preferredFile: String? = null): Boolean = withContext(Dispatchers.IO) {
        val current = _state.value
        if (current is LlamaState.Running) {
            // 已在运行：调用方要求其他模型（如记忆专用小模型）时重启切换
            if (preferredFile != null && currentModelPath != preferredFile) {
                stopInternal()
                _state.value = LlamaState.Stopped
            } else {
                return@withContext true
            }
        }

        // 并发调用（聊天/梦境/Worker 同时拉起）会起两个进程抢 8081 端口
        startMutex.withLock {
            if (_state.value is LlamaState.Running) {
                if (preferredFile != null && currentModelPath != preferredFile) {
                    stopInternal()
                    _state.value = LlamaState.Stopped
                } else {
                    return@withLock true
                }
            }

            val bin = binaryPath()
            if (bin == null) {
                _state.value = LlamaState.Failed("此设备缺少端侧推理组件（ABI 不支持）")
                return@withLock false
            }
            val model = preferredFile
                ?.let { pf -> listModels().firstOrNull { it.absolutePath == pf } ?: File(pf).takeIf { it.exists() && it.length() > 1_000_000 && !isMmprojFile(it) } }
                ?: findModel()
            if (model == null) {
                _state.value = LlamaState.Failed("未找到模型文件，请先在设置中下载")
                return@withLock false
            }

            val mmproj = findMmproj(model)
            val ok = launchServer(bin, model, mmproj)
            if (!ok && mmproj != null) {
                // 视觉投影与文本模型不匹配会让 server 启动即退出：去掉 mmproj 重试纯文本
                launchServer(bin, model, null)
            } else ok
        }
    }

    private suspend fun launchServer(bin: String, model: File, mmproj: File?): Boolean =
        withContext(Dispatchers.IO) {
            try {
                stopInternal()
                val cmd = mutableListOf(
                    bin,
                    "-m", model.absolutePath,
                    "--host", "127.0.0.1",
                    "--port", PORT.toString(),
                    "-c", contextSize.coerceIn(2048, 262_144).toString(),
                    // 线程留 2 核给 UI/系统：推理打满 CPU 会把界面与停止键「饿死」十几秒
                    "-t", (Runtime.getRuntime().availableProcessors() - 2).coerceIn(2, 4).toString(),
                    // 单 slot：默认多 slot 会把 KV 内存翻数倍（4 slot × 64K = 256K 总缓存），
                    // 且请求在 slot 间轮转，Agent 多轮的前缀缓存完全失效、每轮全量重算 prefill。
                    // 不加 --cache-reuse：实测会触发 KV 碎片整理导致 prefill 阶段性卡死数十秒
                    "-np", "1",
                    "--no-webui",
                    "--jinja",
                    "--reasoning", "off"
                )
                if (mmproj != null) {
                    cmd.addAll(listOf("--mmproj", mmproj.absolutePath))
                    _state.value = LlamaState.Starting("加载模型 ${model.name}（含视觉 ${mmproj.name}）…")
                } else {
                    _state.value = LlamaState.Starting("加载模型 ${model.name}…")
                }
                val pb = ProcessBuilder(cmd).redirectErrorStream(true)

                val logFile = File(modelsDir(), "server.log")
                val proc = pb.start()
                process = proc

                // 必须持续排水到 EOF：只读固定行数后关流，会让 server 写满管道冻结或
                // 因 SIGPIPE 直接死掉；日志仅落盘前 MAX_LOG_LINES 行防止无限增长。
                Thread {
                    runCatching {
                        proc.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                            java.io.PrintWriter(java.io.BufferedWriter(java.io.FileWriter(logFile, false))).use { writer ->
                                var written = 0
                                reader.forEachLine { line ->
                                    if (written++ < MAX_LOG_LINES) writer.println(line)
                                }
                                writer.flush()
                            }
                        }
                    }
                }.apply { isDaemon = true }.start()

                val healthClient = okHttpClient.newBuilder()
                    .connectTimeout(2, TimeUnit.SECONDS)
                    .readTimeout(2, TimeUnit.SECONDS)
                    .build()
                repeat(HEALTH_TRIES) { attempt ->
                    // 大模型加载可达数分钟：等待必须可被协程取消（停止键立即生效）
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    if (proc.exitValueOrNull() != null) throw IllegalStateException("server 进程退出，详见 server.log")
                    runCatching {
                        healthClient.newCall(
                            Request.Builder().url("$LOCAL_BASE_URL/health").build()
                        ).execute().use { resp ->
                            if (resp.isSuccessful) {
                                currentModelPath = model.absolutePath
                                _state.value = LlamaState.Running(model.name)
                                return@withContext true
                            }
                        }
                    }
                    _state.value = LlamaState.Starting("启动中… ${(attempt + 1) * HEALTH_INTERVAL_MS / 1000}s")
                    delay(HEALTH_INTERVAL_MS)
                }
                throw IllegalStateException("健康检查超时（${HEALTH_TRIES * HEALTH_INTERVAL_MS / 1000}s）")
            } catch (ce: kotlin.coroutines.cancellation.CancellationException) {
                throw ce
            } catch (e: Exception) {
                stopInternal()
                _state.value = LlamaState.Failed(e.message ?: e.javaClass.simpleName)
                false
            }
        }

    /**
     * UI 直接调用：状态立即置 Stopped，进程收割（destroy + 最多 5s 等待）放后台线程，
     * 不再阻塞主线程导致停止端侧模型时界面卡死。
     */
    fun stop() {
        val proc = process
        currentModelPath = null
        process = null
        _state.value = LlamaState.Stopped
        if (proc != null) {
            Thread { reapProcess(proc) }.apply { isDaemon = true }.start()
        }
    }

    /** 启动新 server 前必须同步释放端口，保持阻塞语义（仅在 IO 调度器上调用）。 */
    private fun stopInternal() {
        val proc = process ?: run { currentModelPath = null; return }
        currentModelPath = null
        process = null
        reapProcess(proc)
    }

    private fun reapProcess(proc: Process) {
        runCatching { proc.destroy() }
        runCatching {
            if (!proc.waitFor(3, TimeUnit.SECONDS)) {
                proc.destroyForcibly()
                proc.waitFor(2, TimeUnit.SECONDS)
            }
        }
    }

    suspend fun downloadModel(url: String, fileName: String? = null): Result<File> =
        withContext(Dispatchers.IO) {
            runCatching {
                val name = (fileName ?: url.substringAfterLast('/').substringBefore('?'))
                    .ifBlank { "model.gguf" }
                    .let { if (it.endsWith(".gguf")) it else "$it.gguf" }
                val target = File(modelsDir(), name)
                val tmp = File(modelsDir(), "$name.tmp")
                tmp.delete()

                val request = Request.Builder().url(url.trim()).build()
                okHttpClient.newBuilder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(60, TimeUnit.SECONDS)
                    .build()
                    .newCall(request)
                    .execute()
                    .use { resp ->
                        if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
                        val total = resp.body?.contentLength() ?: -1L
                        resp.body!!.byteStream().use { input ->
                            tmp.outputStream().use { output ->
                                val buf = ByteArray(256 * 1024)
                                var read: Int
                                var done = 0L
                                var lastPct = -1
                                while (input.read(buf).also { read = it } != -1) {
                                    output.write(buf, 0, read)
                                    done += read
                                    if (total > 0) {
                                        val pct = ((done * 100) / total).toInt()
                                        if (pct != lastPct) {
                                            lastPct = pct
                                            _downloadProgress.value = pct / 100f
                                        }
                                    }
                                }
                            }
                        }
                    }
                if (tmp.length() < 1_000_000) throw IllegalStateException("下载的文件过小（${tmp.length()} 字节），链接可能无效")
                tmp.renameTo(target)
                _downloadProgress.value = null
                target
            }.also { if (it.isFailure) _downloadProgress.value = null }
        }

    private fun Process.exitValueOrNull(): Int? =
        runCatching { exitValue() }.getOrNull()
}
