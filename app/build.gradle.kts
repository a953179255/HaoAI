import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.GZIPInputStream

plugins {
    alias(libs.plugins.android.application)
    // AGP 9.0 起内置 Kotlin 支持，kotlin.android 插件不再需要（kotl.in/gradle/agp-built-in-kotlin）
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Release 签名：keystore.properties（已 gitignore）存在则用专用证书，否则回退 debug（本地开发）。
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}
val hasReleaseKeystore = keystorePropertiesFile.exists() &&
    keystoreProperties.getProperty("storeFile") != null

// ── 端侧推理引擎（llama.cpp）官方预编译（2026-10-24 起）──
// 此前 arm64 引擎是本地 Ubuntu + Hexagon SDK 自编的 12.6MB 单文件，升级一次要开
// 虚拟机交叉编译 + 二进制安全传输 + 字节级验收，成本过高。改为直接消费 ggml-org
// 官方安卓预编译（snapdragon 版 = CPU + Adreno GPU(OpenCL) + Hexagon NPU，QNN
// host 已静态内嵌 libggml-hexagon.so，DSP skel 五代 v73~v85 全带）。运行时约定
// 与旧单文件一致（LD_LIBRARY_PATH/ADSP_LIBRARY_PATH 指向 nativeLibraryDir）。
// 升级 = 改 LLAMA_TAG + LLAMA_SHA256 两个常量重新构建。
// x86_64 模拟器仍用仓库内自编单文件（官方不发 x86_64 安卓包）。
val llamaTag = "b11800"
val llamaSha256 = "7a60d626f84b928a9e2e12703ba6e49f3d7381e188d182e7e3d730dfaba06a9c"
// tar 内路径 -> jniLibs/arm64-v8a 文件名（Android 打包要求 lib*.so 前缀，
// 所以 bin/llama-server 薄壳可执行文件改名 libllamaserver.so——沿用旧名，
// LlamaServerController.binaryPath() 不用动）
val llamaSubset: Map<String, String> = mapOf(
    "bin/llama-server" to "libllamaserver.so",
    "lib/libllama-server-impl.so" to "libllama-server-impl.so",
    "lib/libllama-common.so" to "libllama-common.so",
    "lib/libmtmd.so" to "libmtmd.so",
    "lib/libllama.so" to "libllama.so",
    "lib/libggml.so" to "libggml.so",
    "lib/libggml-base.so" to "libggml-base.so",
    "lib/libggml-cpu.so" to "libggml-cpu.so",
    "lib/libggml-rpc.so" to "libggml-rpc.so",
    "lib/libggml-opencl.so" to "libggml-opencl.so",
    "lib/libggml-hexagon.so" to "libggml-hexagon.so",
    "lib/libggml-htp-v73.so" to "libggml-htp-v73.so",
    "lib/libggml-htp-v75.so" to "libggml-htp-v75.so",
    "lib/libggml-htp-v79.so" to "libggml-htp-v79.so",
    "lib/libggml-htp-v81.so" to "libggml-htp-v81.so",
    "lib/libggml-htp-v85.so" to "libggml-htp-v85.so",
)

android {
    namespace = "com.haoai.agent"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.haoai.agent"
        minSdk = 26
        targetSdk = 36
        versionCode = 39
        versionName = "0.18.6"
        // 设置页「端侧推理」区块展示引擎版本，用户可核对当前官方预编译 tag
        buildConfigField("String", "LLAMA_ENGINE_TAG", "\"$llamaTag\"")
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (hasReleaseKeystore) {
                signingConfigs.getByName("release")
            } else {
                // 无专用证书时仍可出包（本地验证）；对外发布前务必配置 keystore.properties
                signingConfigs.getByName("debug")
            }
        }
        // 调试版与正式版必须同签名：AndroidKeyStore 密钥不可导出且绑定签名，
        // 换签名会让已存密文永久解不开。同签名才能用 adb install -r 覆盖安装而不动数据。
        debug {
            signingConfig = if (hasReleaseKeystore) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        // 引擎热点路径含 android.util.Log（压缩/账本/委派降级日志）：
        // JVM 单测里让框架方法返回默认值而非抛 "Stub!"，才能用脚本化 ProviderClient 驱动真实回合循环。
        unitTests.isReturnDefaultValues = true
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.add("-opt-in=kotlin.RequiresOptIn")
    }
}

// ───────────── llama.cpp 官方引擎同步 task ─────────────
// 路径/代理在配置期取值；doLast 里只用捕获的局部值和局部函数——configuration
// cache 不允许执行期引用脚本对象（顶层 val/fun 都算）。
val llamaCacheDir = File(projectDir, "llama-cache")
val llamaJniDir = File(projectDir, "src/main/jniLibs/arm64-v8a")
val llamaProxy = Properties().apply {
    val lp = rootProject.file("local.properties")
    if (lp.exists()) lp.inputStream().use { load(it) }
}.getProperty("llama.proxy")?.trim()?.takeIf { it.isNotEmpty() }

val syncLlamaEngine = tasks.register("syncLlamaEngine") {
    description = "下载/校验/瘦身 llama.cpp 官方安卓预编译并同步到 jniLibs/arm64-v8a"
    group = "build"
    inputs.property("tag", llamaTag)
    inputs.property("sha256", llamaSha256)
    outputs.dir(llamaJniDir)
    // CC 要求：配置期把脚本属性拷成本地值，doLast 只碰这些（顶层 val/fun 都算脚本对象）
    val tag = llamaTag
    val sha = llamaSha256
    val subset = llamaSubset.toMap()
    val cacheDir = llamaCacheDir
    val jniDir = llamaJniDir
    val proxyConf = llamaProxy
    doLast {

        // 等效 strip：动态链接器只认 program header 声明的加载段；截到最后一个
        // 含数据的段末尾、抹掉 section header 表（e_shoff=0）。symtab/strtab/
        // debug 全在非 ALLOC 区（截掉即剥离），PT_DYNAMIC 的动态符号表在 ALLOC
        // 段内原样保留，加载行为不变。官方包没剥符号（server 子集解压 222MB →
        // ~27MB）。本机无 NDK/llvm-strip，故在 task 里自做。
        fun truncate(f: File) {
            RandomAccessFile(f, "rw").use { raf ->
                val hdr = ByteArray(64)
                require(raf.read(hdr) == 64 && hdr[0] == 0x7f.toByte() && hdr[1] == 'E'.code.toByte()) {
                    "${f.name} 不是 ELF"
                }
                val bb = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN)
                val phoff = bb.getLong(0x20)
                val phentsize = bb.getShort(0x36).toInt()
                val phnum = bb.getShort(0x38).toInt()
                if (phoff <= 0 || phentsize < 56 || phnum <= 0) return // 无段表的非标准 ELF（如 Hexagon skel）不动
                var end = 0L
                repeat(phnum) { i ->
                    raf.seek(phoff + i.toLong() * phentsize)
                    val ph = ByteArray(56)
                    require(raf.read(ph) == 56) { "${f.name} 段头读不满" }
                    val p = ByteBuffer.wrap(ph).order(ByteOrder.LITTLE_ENDIAN)
                    val pOffset = p.getLong(0x08)
                    val pFilesz = p.getLong(0x20)
                    if (pFilesz > 0) end = maxOf(end, pOffset + pFilesz)
                }
                if (end !in 1..f.length()) return
                raf.setLength(end)
                // e_shoff(0x28)/e_shentsize(0x3A)/e_shnum(0x3C)/e_shstrndx(0x3E) 清零
                raf.seek(0x28); raf.writeLong(0L)
                raf.seek(0x3A); raf.writeShort(0); raf.writeShort(0); raf.writeShort(0)
            }
        }

        // 极简 tar.gz 读取器（只需常规文件条目）：512B 头 + 数据补齐 512 倍数。
        // skel 是 Hexagon DSP 侧 ELF（无标准段表、仅 0.8MB），不参与截断。
        fun extract(tgz: File, wanted: Map<String, String>, outDir: File) {
            GZIPInputStream(BufferedInputStream(FileInputStream(tgz))).use { gz ->
                val hdr = ByteArray(512)
                val remaining = wanted.toMutableMap()
                while (remaining.isNotEmpty()) {
                    var r = 0
                    while (r < 512) {
                        val n = gz.read(hdr, r, 512 - r)
                        if (n < 0) break
                        r += n
                    }
                    if (r < 512 || hdr.all { it == 0.toByte() }) break
                    val name = String(hdr, 0, 100).trimEnd(0.toChar())
                    val sizeOct = String(hdr, 124, 12).trimEnd(0.toChar(), ' ').trim()
                    val size = if (sizeOct.isEmpty()) 0L else sizeOct.toLong(8)
                    val type = hdr[156]
                    val prefix = String(hdr, 345, 155).trimEnd(0.toChar())
                    val full = if (prefix.isEmpty()) name else "$prefix/$name"
                    val key = remaining.keys.firstOrNull { full == it || full.endsWith("/$it") }
                    if (key != null && (type == '0'.code.toByte() || type == 0.toByte())) {
                        val dst = File(outDir, wanted.getValue(key))
                        FileOutputStream(dst).use { out ->
                            var left = size
                            val buf = ByteArray(1 shl 16)
                            while (left > 0) {
                                val n = gz.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                                if (n < 0) break
                                out.write(buf, 0, n)
                                left -= n
                            }
                        }
                        remaining.remove(key)
                    } else {
                        var left = size
                        val skip = ByteArray(1 shl 16)
                        while (left > 0) {
                            val n = gz.read(skip, 0, minOf(skip.size.toLong(), left).toInt())
                            if (n < 0) break
                            left -= n
                        }
                    }
                    val pad = ((512 - (size % 512)) % 512).toInt()
                    var skipped = 0
                    while (skipped < pad) {
                        val n = gz.read(hdr, 0, pad - skipped)
                        if (n < 0) break
                        skipped += n
                    }
                }
                require(remaining.isEmpty()) { "官方包里缺 ${remaining.keys}（上游结构变了？tag=$tag）" }
            }
        }

        cacheDir.mkdirs()
        val tgz = File(cacheDir, "llama-$tag-android-snapdragon.tar.gz")
        // 1. 下载（缓存命中跳过；本机直连 GitHub 不通，走 local.properties 的 llama.proxy）
        if (!tgz.isFile) {
            val proxy = proxyConf?.let { p ->
                val host = p.removePrefix("http://").substringBefore(':')
                val port = p.removePrefix("http://").substringAfter(':').toIntOrNull()
                    ?: throw GradleException("llama.proxy 格式应为 http://host:port，实得 $p")
                Proxy(Proxy.Type.HTTP, InetSocketAddress(host, port))
            }
            val url = URI(
                "https://github.com/ggml-org/llama.cpp/releases/download/" +
                    "$tag/llama-$tag-bin-android-arm64-snapdragon.tar.gz"
            ).toURL()
            var lastEx: IOException? = null
            for (attempt in 1..2) {
                try {
                    val conn = (if (proxy != null) url.openConnection(proxy) else url.openConnection())
                        as HttpURLConnection
                    conn.connectTimeout = 30_000
                    conn.readTimeout = 300_000
                    if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
                    val part = File(tgz.parentFile, tgz.name + ".part")
                    conn.inputStream.use { input ->
                        FileOutputStream(part).use { input.copyTo(it) }
                    }
                    if (!part.renameTo(tgz)) {
                        part.copyTo(tgz, overwrite = true)
                        part.delete()
                    }
                    lastEx = null
                    break
                } catch (e: IOException) {
                    lastEx = e
                }
            }
            lastEx?.let {
                throw GradleException(
                    "下载 llama.cpp $tag 失败：${it.message}。" +
                        "可在 local.properties 配 llama.proxy=http://host:port", it
                )
            }
        }
        // 2. sha256 校验（缓存命中也复查——防缓存被换包）
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(tgz).use { input ->
            val buf = ByteArray(1 shl 16)
            var n: Int
            while (input.read(buf).also { n = it } != -1) md.update(buf, 0, n)
        }
        val actual = md.digest().joinToString("") { "%02x".format(it) }
        if (actual != sha) {
            throw GradleException("llama.cpp 引擎包 sha256 不符（tag=$tag）：期望 $sha 实得 $actual")
        }
        // 3. 抽 server 子集直落 jniLibs + 截断瘦身（skel 除外，见 extract 注释）
        jniDir.mkdirs()
        extract(tgz, subset, jniDir)
        var outBytes = 0L
        subset.values.forEach { name ->
            val f = File(jniDir, name)
            if (!name.startsWith("libggml-htp-")) truncate(f)
            outBytes += f.length()
        }
        logger.lifecycle(
            "llama.cpp 引擎 $tag 就绪：${subset.size} 个文件，瘦身后共 %.1fMB".format(outBytes / 1e6)
        )
    }
}

// jniLibs 合并前必须先就位（preBuild 是所有变体任务的公共前置）
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(syncLlamaEngine) }

tasks.withType<Test>().configureEach {
    jvmArgs("-Dfile.encoding=UTF-8", "-Dsun.jnu.encoding=UTF-8")
    environment("JAVA_TOOL_OPTIONS", "-Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8")
}

dependencies {
    // OCR 中文捆绑版（2.4 ocr_image）：模型打进 APK（约 4MB/ABI），Apache-2.0
    // B15：两端共用的 :core —— Tool 契约 / ToolResult / TextCap 只有一份定义（与 PC 同源）。
    implementation(project(":core"))
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.icons.extended)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.okhttp)
    implementation(libs.coroutines.android)
    implementation(libs.serialization.json)
    implementation(libs.backdrop)
    implementation(libs.androidx.work)
    implementation(libs.jetbrains.markdown)
    implementation(libs.jlatexmath.android)
    implementation(libs.jlatexmath.android.font.greek)
    debugImplementation(libs.androidx.ui.tooling)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    testImplementation(libs.junit)
}
