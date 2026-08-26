package com.haoai.agent.platform.llama

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    fun modelsDir(): File = File(context.getExternalFilesDir(null), "models").apply { mkdirs() }

    private fun internalModelsDir(): File = File(context.filesDir, "models")

    fun findModel(): File? {
        val all = listModels()
        if (all.isEmpty()) return null
        val preferred = preferredModel?.let { name -> all.find { it.name == name } }
        return preferred ?: all.maxByOrNull { it.length() }
    }

    fun listModels(): List<File> {
        val external = modelsDir().listFiles { f -> f.name.endsWith(".gguf") }?.toList() ?: emptyList()
        val internal = internalModelsDir().listFiles { f -> f.name.endsWith(".gguf") }?.toList() ?: emptyList()
        return (external + internal)
            .filter { it.length() > 1_000_000 && !isMmprojFile(it) }
            .sortedByDescending { it.length() }
    }

    /** 视觉投影文件：.mmproj 后缀，或文件名含 mmproj 的 gguf（常见命名 X-mmproj.gguf）。 */
    fun isMmprojFile(f: File): Boolean =
        f.name.endsWith(".mmproj", ignoreCase = true) || f.name.contains("mmproj", ignoreCase = true)

    /** 查找视觉投影文件：优先与模型同名，否则取目录中任意一个。 */
    fun findMmproj(modelFileName: String): File? {
        val all = (modelsDir().listFiles { f -> isMmprojFile(f) }?.toList() ?: emptyList()) +
            (internalModelsDir().listFiles { f -> isMmprojFile(f) }?.toList() ?: emptyList())
        if (all.isEmpty()) return null
        val base = modelFileName.removeSuffix(".gguf")
        return all.firstOrNull { it.nameWithoutExtension == base || it.name.startsWith(base) }
            ?: all.maxByOrNull { it.lastModified() }
    }

    fun binaryPath(): String? {
        val f = File(context.applicationInfo.nativeLibraryDir, "libllamaserver.so")
        return if (f.exists()) f.absolutePath else null
    }

    suspend fun ensureStarted(): Boolean = withContext(Dispatchers.IO) {
        val current = _state.value
        if (current is LlamaState.Running) return@withContext true

        val bin = binaryPath()
        if (bin == null) {
            _state.value = LlamaState.Failed("此设备缺少端侧推理组件（ABI 不支持）")
            return@withContext false
        }
        val model = findModel()
        if (model == null) {
            _state.value = LlamaState.Failed("未找到模型文件，请先在设置中下载")
            return@withContext false
        }

        _state.value = LlamaState.Starting("加载模型 ${model.name}…")
        stopInternal()

        try {
            // 多模态：与模型同名或任一 .mmproj 视觉投影文件存在时启用图像识别
            val mmproj = findMmproj(model.name)
            val cmd = mutableListOf(
                bin,
                "-m", model.absolutePath,
                "--host", "127.0.0.1",
                "--port", PORT.toString(),
                "-c", "32768",
                "-t", Runtime.getRuntime().availableProcessors().coerceIn(2, 6).toString(),
                "--no-webui",
                "--jinja",
                "--reasoning", "off"
            )
            if (mmproj != null) {
                cmd.addAll(listOf("--mmproj", mmproj.absolutePath))
                _state.value = LlamaState.Starting("加载模型 ${model.name}（含视觉 ${mmproj.name}）…")
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
                if (proc.exitValueOrNull() != null) throw IllegalStateException("server 进程退出，详见 server.log")
                runCatching {
                    healthClient.newCall(
                        Request.Builder().url("$LOCAL_BASE_URL/health").build()
                    ).execute().use { resp ->
                        if (resp.isSuccessful) {
                            _state.value = LlamaState.Running(model.name)
                            return@withContext true
                        }
                    }
                }
                _state.value = LlamaState.Starting("启动中… ${(attempt + 1) * HEALTH_INTERVAL_MS / 1000}s")
                Thread.sleep(HEALTH_INTERVAL_MS)
            }
            throw IllegalStateException("健康检查超时（${HEALTH_TRIES * HEALTH_INTERVAL_MS / 1000}s）")
        } catch (e: Exception) {
            stopInternal()
            _state.value = LlamaState.Failed(e.message ?: e.javaClass.simpleName)
            false
        }
    }

    fun stop() {
        stopInternal()
        _state.value = LlamaState.Stopped
    }

    private fun stopInternal() {
        val proc = process ?: return
        process = null
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
