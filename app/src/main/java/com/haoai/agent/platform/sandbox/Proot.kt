package com.haoai.agent.platform.sandbox

import android.os.Build
import java.io.File

/**
 * PRoot 二进制管理（3.1）：
 * - 可执行文件以 libproot.so 名义打包在 jniLibs（useLegacyPackaging 保留执行位），
 *   运行时从 applicationInfo.nativeLibraryDir 取绝对路径执行（无需 root/su）；
 * - 载入前校验 ABI 与 sha256（与打包时逐字节一致才放行）；
 * - termux 构建的 proot 动态依赖 libtalloc.so.2，APK 里只能以合法 so 名打包
 *   （libtalloc.so），执行前复制成 libtalloc.so.2 放私有 lib 目录供动态链接器解析；
 * - loader（proot 启动 guest 时的 ELF 加载器）通过 PROOT_LOADER 环境变量指定。
 */
object Proot {

    // 打包于 jniLibs 的 termux proot 5.1.107（deb sha256 见 commit body）
    private val EXPECTED_SHA = mapOf(
        "arm64-v8a" to "ea47e17da8e6ff4882c169c6508861e5b4be9227e477c6020f4f14facc85c10d",
        "x86_64" to "5c6b99c48ebb87580551afd654e49eb8d178fd45fef0b65397c99a1d901c417b"
    )

    data class Install(
        val binary: File,
        val loader: File,
        val libDir: File,
        val abi: String
    )

    fun abiOf(): String =
        if (Build.SUPPORTED_ABIS.isNotEmpty()) Build.SUPPORTED_ABIS[0] else Build.CPU_ABI

    /**
     * 就绪检查 + 运行时目录准备（幂等）：
     * 返回 null = 当前 ABI 无打包二进制或 sha256 不符（损坏），应引导重装 APK。
     */
    fun ensureReady(appFilesDir: File, nativeLibraryDir: String?): Install? {
        val abi = abiOf()
        val expected = EXPECTED_SHA[abi] ?: return null
        val binary = File(nativeLibraryDir ?: return null, "libproot.so")
        if (!binary.exists() || !binary.canExecute()) return null
        val actual = runCatching { sha256(binary) }.getOrNull() ?: return null
        if (!actual.equals(expected, ignoreCase = true)) return null
        val loader = File(nativeLibraryDir, "libproot_loader.so")
        // libtalloc.so → libtalloc.so.2（DT_NEEDED 名称；每次启动刷新，随版本更新）
        val libDir = File(appFilesDir, "proot/lib").apply { mkdirs() }
        val tallocSrc = File(nativeLibraryDir, "libtalloc.so")
        val tallocDst = File(libDir, "libtalloc.so.2")
        if (tallocSrc.exists() && (!tallocDst.exists() || tallocDst.length() != tallocSrc.length())) {
            runCatching {
                tallocSrc.copyTo(tallocDst, overwrite = true)
                tallocDst.setExecutable(false)
            }
        }
        val shmemSrc = File(nativeLibraryDir, "libandroid-shmem.so")
        if (shmemSrc.exists()) {
            val shmemDst = File(libDir, "libandroid-shmem.so")
            if (!shmemDst.exists() || shmemDst.length() != shmemSrc.length()) {
                runCatching { shmemSrc.copyTo(shmemDst, overwrite = true) }
            }
        }
        return Install(binary, loader, libDir, abi)
    }

    /** 构造 proot 进程的环境变量：loader 路径 + 自定义 so 搜索路径 + termux 兼容前缀。 */
    fun environmentOf(install: Install): Map<String, String> = mapOf(
        "PROOT_LOADER" to install.loader.absolutePath,
        "PROOT_LOADER32" to install.loader.absolutePath,
        "PROOT_TMP_DIR" to install.libDir.parent,
        "LD_LIBRARY_PATH" to install.libDir.absolutePath,
        "PROOT_NO_SECCOMP" to "1"
    )

    /**
     * 组装 proot 命令行（3.3 ProotBackend 复用）：
     * --kill-on-exit 保证进程清理；-0 伪装 root；-w 指定沙箱内初始工作目录；
     * -b 绑定目录（宿主:沙箱内路径）。
     */
    fun buildCommand(
        install: Install,
        rootfsDir: File,
        cmd: String,
        binds: List<Pair<String, String>> = emptyList(),
        workdir: String = "/root"
    ): List<String> = buildList {
        add(install.binary.absolutePath)
        add("--kill-on-exit")
        add("-r"); add(rootfsDir.absolutePath)
        add("-0")
        add("-w"); add(workdir)
        for ((host, guest) in binds) {
            add("-b"); add("$host:$guest")
        }
        add("/bin/sh")
        add("-c")
        add(cmd)
    }

    fun sha256(f: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
