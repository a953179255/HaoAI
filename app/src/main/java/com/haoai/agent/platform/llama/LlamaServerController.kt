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
    /** progress：0-100 启动进度（由 server 日志里程碑驱动）；-1 = 未知（旧语义）。 */
    data class Starting(val detail: String = "", val progress: Int = -1) : LlamaState
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

        /** Phase 7 阶段2：最近一次 server 实际生效的后端（"cpu" / "htp-v73"…）。 */
        @Volatile
        var lastActiveBackend: String = "cpu"

        /**
         * Hexagon 后端架构选择（Phase 7 阶段2）：Build.SOC_MODEL/Build.HARDWARE
         * 映射 skel 版本；null = 非骁龙/未识别，走 CPU 兜底。
         * 8 Gen 2(SM8550)→v73；8 Gen 3/8s(SM8650/9200)→v75；8 Elite(SM8750/9300)→v79。
         */
        fun detectHexagonArch(): String? {
            val soc = (android.os.Build.SOC_MODEL + " " + android.os.Build.HARDWARE).lowercase()
            val isSnapdragon = listOf("qcom", "qualcomm", "sm8", "sm7", "sdm", "msm", "kalama", "sun", "pineapple", "taro")
                .any { soc.contains(it) } || android.os.Build.SOC_MODEL.contains("Snapdragon", true)
            if (!isSnapdragon) return null
            return when {
                listOf("sm8550", "8950", "kalama", "8 gen 2", "8g2").any { soc.contains(it) } -> "v73"
                listOf("sm8650", "9200", "pineapple", "8 gen 3", "8g3", "8s gen").any { soc.contains(it) } -> "v75"
                listOf("sm8750", "9300", "sun", "8 elite", "8g4").any { soc.contains(it) } -> "v79"
                else -> null
            }
        }

        /** jniLibs 里是否存在 Hexagon skel 库（仅 arm64 构建打包）。 */
        fun skelLibsAvailable(nativeDir: String?): Boolean =
            nativeDir?.let { dir -> java.io.File(dir, "libggml-htp-v73.so").exists() } == true

        const val DEFAULT_MODEL_URL =
            "https://hf-mirror.com/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf"
        private const val HEALTH_TRIES = 240

        private const val HEALTH_INTERVAL_MS = 500L

        private const val MAX_LOG_LINES = 2000
    }

    /** 本次 server 生效后端（镜像 companion.lastActiveBackend，设置页读取）。 */
    val activeBackend: String get() = lastActiveBackend

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

    /** ELF 完整性预检：可执行 .so 的 PT_DYNAMIC 必须含 DT_HASH(4)/DT_GNU_HASH(0x6ffffef5)，
     *  否则 bionic linker 报 "empty/missing DT_HASH" 拒绝加载。 */
    private fun isElfLinked(path: String): Boolean = runCatching {
        java.io.RandomAccessFile(path, "r").use { f ->
            val head = ByteArray(64)
            if (f.read(head) < 64) return@use false
            if (head[0] != 0x7F.toByte() || head[1] != 'E'.code.toByte() || head[2] != 'L'.code.toByte()) return@use false
            val buf = java.nio.ByteBuffer.wrap(head).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            val phoff = buf.getLong(0x20)
            val phentsize = buf.getShort(0x36).toInt()
            val phnum = buf.getShort(0x38).toInt()
            if (phoff <= 0 || phentsize < 56) return@use false
            repeat(phnum) { i ->
                val ph = ByteArray(56)
                f.seek(phoff + i.toLong() * phentsize)
                if (f.read(ph) < 56) return@use false
                val p = java.nio.ByteBuffer.wrap(ph).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                if (p.getInt(0) == 2) { // PT_DYNAMIC
                    val pOffset = p.getLong(0x08)
                    val pFilesz = p.getLong(0x20)
                    var j = 0L
                    while (j + 16 <= pFilesz) {
                        f.seek(pOffset + j)
                        val entry = ByteArray(16)
                        if (f.read(entry) < 16) return@use false
                        val e = java.nio.ByteBuffer.wrap(entry).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                        val tag = e.getLong(0)
                        if (tag == 0L) break
                        if (tag == 4L || tag == 0x6ffffef5L) return@use true
                        j += 16
                    }
                    return@use false
                }
            }
            false
        }
    }.getOrDefault(true)

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
            // 二进制完整性预检：坏 .so（缺动态符号表）bionic linker 直接拒绝，症状只是
            // "server 进程退出"难排查（e2e 2026-09-11 P3-4）。解析异常不拦截，保守放行走原错误路径。
            if (!isElfLinked(bin)) {
                _state.value = LlamaState.Failed("端侧推理组件损坏（缺少动态符号表），请卸载后重新安装 APK")
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
            // HTP 参数在 try 外声明：启动失败时 catch 块去掉 NPU 参数以 CPU 兜底重试
            var useHtp = false
            var hex: String? = null
            val nativeDir = context.applicationInfo.nativeLibraryDir
            // 完整命令在 try 内赋值、catch 兜底时读取（不得在 catch 里重新拼——无 -m/--port 必失败）
            var fullCmd: List<String>? = null
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
                    // --jinja 必须保留（OpenAI 端点工具调用依赖模板解析）；
                    // 不能加 --reasoning off：思考型模型（MiniCPM5 等）被强制跳过推理后会乱答
                    // （实测 2+3=4），保留思考则正确（2+3=5）且思考走 reasoning_content 独立字段，
                    // App 端 OpenAiCompatClient 已解析为「思考过程」展示
                    "--jinja"
                )
                if (mmproj != null) cmd.addAll(listOf("--mmproj", mmproj.absolutePath))
                // ── Hexagon NPU（Phase 7 阶段2）：arm64 + 骁龙 + skel 在位 → HTP 设备；
                // 启动/健康失败自动去 NPU 参数以 CPU 兜底重启（三后端单二进制）。
                hex = if (android.os.Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a" &&
                    skelLibsAvailable(nativeDir)
                ) detectHexagonArch() else null
                useHtp = hex != null
                if (useHtp) cmd.addAll(listOf("-ngl", "99", "-dev", "HTP0", "--device", "HTP0"))
                fullCmd = cmd
                // 进度（百分比方案）：由日志排水线程按 llama.cpp 里程碑推进，
                // 健康检查循环每 500ms 把当前百分比刷进 Starting 状态
                val progress = java.util.concurrent.atomic.AtomicInteger(4)
                val stage = java.util.concurrent.atomic.AtomicReference("启动服务进程")
                fun setP(pct: Int, note: String) {
                    progress.updateAndGet { if (pct > it) pct else it }
                    stage.set(note)
                }
                // 时间插值基线：server stdout 被管道全缓冲，日志里程碑要攒 4KB 才刷出来、
                // 解析会滞后——按模型体积估算加载时长做平滑推进（里程碑命中则向上跳变）
                val loadStartMs = android.os.SystemClock.elapsedRealtime()
                val durationEstMs = (model.length() / (50L * 1024 * 1024)).coerceIn(8L, 300L) * 1000
                _state.value = LlamaState.Starting(
                    if (useHtp) "加载模型 ${model.name}（Hexagon $hex NPU）… 4%" else "加载模型 ${model.name}（CPU）… 4%",
                    4
                )
                val pb = ProcessBuilder(cmd).redirectErrorStream(true)
                if (useHtp) {
                    val env = pb.environment()
                    // libOpenCL.so 在 vendor 分区（App 类加载器搜索路径不含 vendor），
                    // 二进制 DT_NEEDED 直接依赖它，必须显式加 /vendor/lib64
                    env["LD_LIBRARY_PATH"] = "$nativeDir:/vendor/lib64:" + (env["LD_LIBRARY_PATH"] ?: "")
                    env["ADSP_LIBRARY_PATH"] = nativeDir + ";" + (env["ADSP_LIBRARY_PATH"] ?: "")
                }

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
                                    // 里程碑 → 百分比（llama.cpp 稳定日志串）
                                    when {
                                        "build: " in line -> setP(10, "推理引擎已启动")
                                        "system_info" in line -> setP(14, "读取运行环境")
                                        "load_tensors: loading model tensors" in line -> setP(20, "读取模型文件")
                                        "load_tensors:" in line && "loaded" in line -> setP(70, "模型张量就绪")
                                        "warmup" in line -> setP(88, "预热")
                                        "listening" in line -> setP(96, "等待服务就绪")
                                    }
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
                                lastActiveBackend = if (useHtp) "htp-$hex" else "cpu"
                                _state.value = LlamaState.Running(model.name)
                                return@withContext true
                            }
                        }
                    }
                    // 百分比 = max(日志里程碑, 时间插值)：插值平滑、里程碑跳变兜底
                    val elapsed = android.os.SystemClock.elapsedRealtime() - loadStartMs
                    val timePct = 4 + ((91L * elapsed / durationEstMs).toInt().coerceIn(0, 91))
                    val pct = maxOf(progress.get(), if (progress.get() < 96) timePct else progress.get())
                    if (pct > progress.get()) progress.set(pct)
                    _state.value = LlamaState.Starting(
                        "启动 ${pct}%（${stage.get()}）", pct
                    )
                    delay(HEALTH_INTERVAL_MS)
                }
                throw IllegalStateException("健康检查超时（${HEALTH_TRIES * HEALTH_INTERVAL_MS / 1000}s）")
            } catch (ce: kotlin.coroutines.cancellation.CancellationException) {
                throw ce
            } catch (e: Exception) {
                stopInternal()
                // HTP 失败（skel 不兼容/无 HTP 权限）：去 NPU 参数 CPU 兜底重试一次
                if (useHtp) {
                    android.util.Log.w("HaoLlama", "Hexagon 启动失败（${e.message?.take(120)}），回退 CPU")
                    val base = fullCmd
                    if (base == null) {
                        android.util.Log.w("HaoLlama", "CPU 兜底缺完整命令行，跳过重试")
                    } else {
                    val retry = base.filterIndexed { i, arg ->
                        !(arg == "-ngl" || arg == "-dev" || arg == "--device" ||
                            (i > 0 && (base[i - 1] == "-ngl" || base[i - 1] == "-dev" || base[i - 1] == "--device")))
                    } + listOf("-ngl", "0")
                    val pb2 = ProcessBuilder(retry).redirectErrorStream(true)
                    val env2 = pb2.environment()
                    env2["LD_LIBRARY_PATH"] = "$nativeDir:/vendor/lib64:" + (env2["LD_LIBRARY_PATH"] ?: "")
                    _state.value = LlamaState.Starting("NPU 不可用，CPU 兜底加载 ${model.name}… 12%", 12)
                    val proc2 = pb2.start()
                    process = proc2
                    Thread {
                        runCatching {
                            proc2.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                                java.io.PrintWriter(java.io.BufferedWriter(java.io.FileWriter(File(modelsDir(), "server.log"), false))).use { w ->
                                    var n = 0
                                    reader.forEachLine { line -> if (n++ < MAX_LOG_LINES) w.println(line) }
                                    w.flush()
                                }
                            }
                        }
                    }.apply { isDaemon = true }.start()
                    val health2 = okHttpClient.newBuilder()
                        .connectTimeout(2, TimeUnit.SECONDS)
                        .readTimeout(2, TimeUnit.SECONDS)
                        .build()
                    repeat(HEALTH_TRIES) { attempt ->
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        if (proc2.exitValueOrNull() != null) {
                            // 处于 catch 块内，再抛异常会逃逸协程直接崩 App：
                            // 置 Failed 走 return，让调用方拿到 false 优雅降级
                            android.util.Log.w("HaoLlama", "CPU 兜底进程也退出（exit=${proc2.exitValueOrNull()}）")
                            lastActiveBackend = "cpu"
                            _state.value = LlamaState.Failed("端侧服务启动失败（NPU 与 CPU 均不可用），详见 server.log")
                            return@withContext false
                        }
                        runCatching {
                            health2.newCall(Request.Builder().url("$LOCAL_BASE_URL/health").build())
                                .execute().use { resp ->
                                    if (resp.isSuccessful) {
                                        currentModelPath = model.absolutePath
                                        lastActiveBackend = "cpu"
                                        _state.value = LlamaState.Running(model.name)
                                        return@withContext true
                                    }
                                }
                        }
                        delay(HEALTH_INTERVAL_MS)
                    }
                    }
                }
                lastActiveBackend = "cpu"
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
