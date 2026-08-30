package com.haoai.agent.platform.sandbox

import okhttp3.Request
import org.json.JSONObject
import java.io.File

/**
 * 发行版管理器（3.2）：
 * - 内置 Ubuntu 24.04 base / Alpine 3.21 minirootfs 官方源（双架构，sha256 固定清单）；
 * - 安装 = 下载到 proot/download.tmp（进度回调）→ sha256 校验（不过即删并拒绝解压）
 *   → toybox tar 解压到 proot/distros/<id>/rootfs → 初始化（DNS/haoai-env/时区）→ /bin/sh 自检；
 * - 安装状态直接以文件系统为准（rootfs/bin/sh 探测），不额外存「已安装」标记，天然可恢复；
 * - tar 对 rootfs 里的设备节点（dev/null 等）在非 root 下无法 mknod，属预期内跳过：
 *   proot 运行期用 -b 绑定宿主 /dev，因此校验以 sha256（包完整性）+ /bin/sh 自检（解压成功）为准。
 */
class DistroManager(
    private val filesDir: File,
    private val http: okhttp3.OkHttpClient
) {

    data class Source(val url: String, val sha256: String)

    data class Distro(
        val id: String,
        val name: String,
        val desc: String,
        val sources: Map<String, Source> // abi -> 源
    )

    enum class State { NOT_INSTALLED, READY, DAMAGED }

    data class Status(
        val distro: Distro,
        val state: State,
        val sizeBytes: Long,
        val installedAt: Long
    )

    /** 内置发行版清单（sha256 采集自官方发布目录，2026-08-30）。 */
    val builtIns: List<Distro> = listOf(
        Distro(
            "ubuntu-24.04", "Ubuntu 24.04 LTS",
            "GNU 生态完整，glibc 兼容性最好（base 根文件系统）",
            mapOf(
                "arm64-v8a" to Source(
                    "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.4-base-arm64.tar.gz",
                    "04207713ece899c3740823d33690441ad3a7f0ded1101aca744e2b0f37ac7ff2"
                ),
                "x86_64" to Source(
                    "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.4-base-amd64.tar.gz",
                    "c1e67ef7b17a6300e136118bd1dc04725009cb376c1aad10abcf8cd453628d58"
                )
            )
        ),
        Distro(
            "alpine-3.21", "Alpine 3.21",
            "轻量（约 3 MB），apk 包管理，适合快速试验",
            mapOf(
                "arm64-v8a" to Source(
                    "https://dl-cdn.alpinelinux.org/alpine/v3.21/releases/aarch64/alpine-minirootfs-3.21.7-aarch64.tar.gz",
                    "d1d1a3fae5f4d6146e9742790a47fcb116199622cfb8439f218a4d5fbe5000da"
                ),
                "x86_64" to Source(
                    "https://dl-cdn.alpinelinux.org/alpine/v3.21/releases/x86_64/alpine-minirootfs-3.21.7-x86_64.tar.gz",
                    "8cba1ea3e8b500ea986a313d8eecf3d5952a2a0d23a69117bb81c023d9ceac05"
                )
            )
        )
    )

    fun distroById(id: String): Distro? = builtIns.find { it.id == id }

    /** 沙箱根目录（proot/lib 由 3.1 的运行时库占用，发行版放 distros/ 子目录避免混布）。 */
    fun rootfsDir(distroId: String): File =
        File(File(filesDir, "proot/distros/$distroId"), "rootfs")

    private fun metaFile(distroId: String): File =
        File(filesDir, "proot/distros/$distroId/meta.json")

    /**
     * /bin/sh 探测：alpine/ubuntu 的 sh 是绝对路径符号链接（sh -> /bin/busybox），
     * File.exists() 会跟随链接在 Android 真实根下解析而误判损坏，必须按 NOFOLLOW 判存在。
     */
    private fun shExists(root: File): Boolean {
        val sh = File(root, "bin/sh")
        if (sh.exists()) return true
        return runCatching { java.nio.file.Files.isSymbolicLink(sh.toPath()) }.getOrDefault(false)
    }

    private fun downloadTmp(): File = File(filesDir, "proot/download.tmp")

    /** 全部内置发行版的当前状态（size 递归统计，调用方放 IO 线程）。 */
    fun statuses(): List<Status> = builtIns.map { d ->
        val root = rootfsDir(d.id)
        val state = when {
            !root.exists() -> State.NOT_INSTALLED
            shExists(root) -> State.READY
            else -> State.DAMAGED
        }
        Status(d, state, sizeOf(root), metaFile(d.id).takeIf { it.exists() }?.let {
            runCatching { JSONObject(it.readText()).optLong("installedAt") }.getOrDefault(0L)
        } ?: 0L)
    }

    fun sizeOf(dir: File?): Long {
        if (dir == null || !dir.exists()) return 0L
        var total = 0L
        val stack = ArrayDeque<File>()
        stack.add(dir)
        while (stack.isNotEmpty()) {
            val list = stack.removeLast().listFiles() ?: continue
            for (c in list) {
                if (c.isDirectory) stack.add(c) else total += c.length()
            }
        }
        return total
    }

    /**
     * 安装（可重复调用 = 覆盖重装）。
     * @param customUrl 可选自定义镜像源；无论从哪下载，sha256 仍按官方清单校验。
     */
    suspend fun install(
        distro: Distro,
        onProgress: (read: Long, total: Long) -> Unit = { _, _ -> },
        customUrl: String? = null
    ): Result<Unit> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            val abi = Proot.abiOf()
            val src = distro.sources[abi] ?: error("当前架构（$abi）没有该发行版的官方包")
            val url = customUrl?.trim().takeUnless { it.isNullOrEmpty() } ?: src.url
            com.haoai.agent.platform.NetGuard.check(url)

            // 1. 下载到临时文件（流式，带进度）
            val tmp = downloadTmp()
            tmp.parentFile?.mkdirs()
            tmp.delete()
            val req = Request.Builder().url(url).build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("下载失败：HTTP ${resp.code}")
                val body = resp.body ?: error("下载失败：空响应")
                val total = body.contentLength()
                body.byteStream().use { ins ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(128 * 1024)
                        var read = 0L
                        while (true) {
                            val n = ins.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            read += n
                            onProgress(read, total)
                        }
                    }
                }
            }
            if (tmp.length() == 0L) error("下载失败：文件为空")

            // 2. sha256 校验：不匹配 → 删除临时文件并拒绝解压
            val actual = Proot.sha256(tmp)
            if (!actual.equals(src.sha256, ignoreCase = true)) {
                tmp.delete()
                error("sha256 校验失败（下载不完整或源被篡改），已删除，请重新下载")
            }

            // 3. 解压（toybox tar；dev 设备节点跳过，运行期由 proot 绑定宿主 /dev）
            val root = rootfsDir(distro.id)
            root.parentFile?.deleteRecursively()
            root.parentFile?.mkdirs()
            root.mkdirs()
            val tarBin = File("/system/bin/tar").takeIf { it.exists() }
            val cmd = buildList {
                add(if (tarBin != null) "/system/bin/tar" else "/system/bin/toybox")
                if (tarBin == null) add("tar")
                add("-xzf"); add(tmp.absolutePath)
                add("-C"); add(root.absolutePath)
                add("--exclude=./dev/*")
                add("--exclude=dev/*")
            }
            val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
            val tarOut = proc.inputStream.bufferedReader().use { it.readText() }
            val rc = proc.waitFor()
            tmp.delete()
            val healthy = shExists(root)
            if (rc != 0 && !healthy) {
                error("解压失败（tar exit=$rc）：${tarOut.take(200)}")
            }

            // 4. 初始化：DNS / haoai-env / 时区 / 基础目录
            initialize(root)

            // 5. 自检 + 记录元数据
            if (!shExists(root)) error("安装后自检失败：缺少 /bin/sh（rootfs 不完整）")
            metaFile(distro.id).writeText(
                JSONObject()
                    .put("id", distro.id)
                    .put("name", distro.name)
                    .put("abi", abi)
                    .put("url", url)
                    .put("sha256", src.sha256)
                    .put("installedAt", System.currentTimeMillis())
                    .toString()
            )
            Unit
        }
    }

    private fun initialize(root: File) {
        File(root, "etc").mkdirs()
        // DNS：proot 共享宿主网络栈，写公共 DNS（3.3 会按需绑定 resolv.conf 覆盖）
        File(root, "etc/resolv.conf").writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
        // POSIX TZ（glibc/musl 通认）：现代 Android 的 tzdata 是单一合并文件，
        // 没有逐时区文件可拷贝为 /etc/localtime，改按设备当前偏移生成（注意 POSIX 符号反转：东八区 = GMT-8）
        val tz = java.util.TimeZone.getDefault()
        val offMin = tz.getOffset(System.currentTimeMillis()) / 60000
        val sign = if (offMin >= 0) "-" else "+"
        val absMin = kotlin.math.abs(offMin)
        val posixTz = "GMT$sign${absMin / 60}" + (if (absMin % 60 > 0) ":%02d".format(absMin % 60) else "")
        // 沙箱环境包装器：后续 ProotBackend 以它启动命令（HOME/LANG/TERM/TZ/PATH）
        val env = File(root, "usr/local/bin/haoai-env")
        env.parentFile?.mkdirs()
        env.writeText(
            """#!/bin/sh
# HaoAI 沙箱环境（由发行版管理器生成）
export HOME=/root
export LANG=C.UTF-8
export TERM=xterm-256color
export TZ=$posixTz
export PATH=/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin
export TMPDIR=/tmp
cd "${'$'}HOME" 2>/dev/null || true
exec "${'$'}@"
"""
        )
        env.setExecutable(true, false)
        // 基础目录：/root 家目录、/tmp、以及运行期挂载点
        File(root, "root").mkdirs()
        val tmp = File(root, "tmp").apply { mkdirs() }
        runCatching { tmp.setExecutable(true, false); tmp.setWritable(true, false) }
        File(root, "dev").mkdirs()
        File(root, "proc").mkdirs()
        File(root, "sys").mkdirs()
    }

    /** 删除发行版（释放其全部空间；幂等）。 */
    fun delete(distroId: String): Boolean =
        File(filesDir, "proot/distros/$distroId").deleteRecursively()
}
