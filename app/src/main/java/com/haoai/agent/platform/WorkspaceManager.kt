package com.haoai.agent.platform

import android.content.Context
import android.net.Uri
import android.os.Environment
import com.haoai.agent.data.HaoJson
import kotlinx.serialization.Serializable
import java.io.File

/**
 * 工作区偏好（2026-10-23 三档改造）：
 * - safUri：SAF 档（引擎保留兼容存量绑定，UI 已退役新绑定入口）
 * - rawPath：真实路径档（公共文档/自选位置共用通路，bash/文档同步全可用）
 * - rawKind：档位意图（"public"=公共文档含更换过的公共根 / "custom"=自选位置）——
 *   更换过根的公共档与自选档在引擎层无差别，radio 落位靠它记忆。
 */
@Serializable
private data class WorkspacePref(
    val safUri: String? = null,
    val rawPath: String? = null,
    val rawKind: String? = null,
    /** 公共交换目录（v3 定稿）：null = 默认 Documents/HaoAI；与工作区档位独立。 */
    val exchangePath: String? = null
)

class WorkspaceManager(private val context: Context) {

    enum class Mode { PRIVATE, PUBLIC, CUSTOM, SAF }

    private val prefFile = File(context.filesDir, "workspace.json")

    private val defaultRoot: File = File(
        context.getExternalFilesDir(null) ?: File(context.filesDir, "ext"),
        "workspace"
    ).apply { mkdirs() }

    var current: FileBackend? = null
        private set

    /** 当前真实路径档的根（Raw 系才有；与 current 同生命周期，restore/useRaw 时维护）。 */
    var currentRawPath: String? = null
        private set

    private var rawKind: String? = null

    /** SAF 绑定 URI（切专属/交换目录等重 persist 场景要保住存量绑定）。 */
    private var safUri: String? = null

    /** 公共交换目录偏好（null = 默认 Documents/HaoAI）。 */
    private var exchangePath: String? = null

    init {
        restoreOrDefault()
    }

    fun useDefault() {
        current = RawFileBackend(defaultRoot)
        currentRawPath = null
        rawKind = null
        safUri = null
        persist(WorkspacePref(exchangePath = exchangePath))
    }

    fun useSaf(uriString: String): Boolean {
        val uri = Uri.parse(uriString)
        return runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            val backend = SafFileBackend(context, uri)
            current = backend
            currentRawPath = null
            rawKind = null
            safUri = uriString
            persist(WorkspacePref(safUri = uriString, exchangePath = exchangePath))
            true
        }.getOrDefault(false)
    }

    /**
     * 三档改造（2026-10-23）：真实路径档——公共文档（Documents/HaoAI）与自选位置共用。
     * kind：档位意图（PUBLIC/CUSTOM），radio 落位记忆用。
     * 目录不存在则尝试创建；创建/校验失败返回 false 不切换。
     */
    fun useRaw(path: String, kind: Mode): Boolean {
        val f = File(path)
        return runCatching {
            if (!f.exists()) f.mkdirs()
            if (!f.isDirectory) return false
            current = RawFileBackend(f)
            currentRawPath = f.absolutePath
            rawKind = kind.name
            safUri = null
            persist(WorkspacePref(rawPath = f.absolutePath, rawKind = kind.name, exchangePath = exchangePath))
            true
        }.getOrDefault(false)
    }

    /** UI 三态判定：SAF 存量绑定单列（UI 不再提供新绑定入口，但如实显示现状）。 */
    fun mode(): Mode = when {
        current is SafFileBackend -> Mode.SAF
        currentRawPath == null -> Mode.PRIVATE
        rawKind == Mode.CUSTOM.name -> Mode.CUSTOM
        rawKind == Mode.PUBLIC.name -> Mode.PUBLIC
        // 存量 rawPath（无 kind 字段）按默认公共根兜底归类
        currentRawPath == publicDefaultRoot().absolutePath -> Mode.PUBLIC
        else -> Mode.CUSTOM
    }

    /** 公共交换目录默认根：Documents/HaoAI（v3 效果图定稿）。 */
    fun publicDefaultRoot(): File = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
        "HaoAI"
    )

    /** 当前公共交换目录（偏好优先，回落默认根）。 */
    fun exchangeRoot(): File = File(exchangePath ?: publicDefaultRoot().absolutePath)

    /**
     * 更换公共交换目录（v3 定稿：独立于工作区档位的「对外窗口」）。
     * 只改映射不改工作区绑定；目录不存在则尝试创建。调用方负责重写 AGENTS.md。
     */
    fun useExchange(path: String): Boolean {
        val f = File(path)
        return runCatching {
            if (!f.exists()) f.mkdirs()
            if (!f.isDirectory) return false
            exchangePath = f.absolutePath
            persist(
                WorkspacePref(
                    safUri = safUri, rawPath = currentRawPath,
                    rawKind = rawKind, exchangePath = f.absolutePath
                )
            )
            true
        }.getOrDefault(false)
    }

    val workspaceUriForSession: String?
        get() = when (current) {
            is SafFileBackend -> runCatching {
                HaoJson.json.decodeFromString(
                    WorkspacePref.serializer(),
                    prefFile.readText()
                ).safUri
            }.getOrNull()
            else -> null
        }

    /**
     * 切换前的旧根内容迁入新根（仅 Raw→Raw 有意义；v3 效果图「带迁移」定稿）。
     * 目标已有同名文件**不覆盖**（防毁新根现有内容/引擎文件）。
     * 2026-10-23 性能版：清单一次收集（总数精确，进度条不再挂「约」）→
     * 目录串行建 → 文件多路并行拷（碎文件拷贝是 syscall-bound，实测 7 万文件
     * 单线程要几分钟）。onProgress 内部按 120ms 节流（从 worker 线程调用）。
     * 返回实际复制文件数；旧根不存在或不可读返回 0。
     */
    fun migrateInto(
        newRoot: File,
        fromRaw: File?,
        onProgress: ((done: Int, total: Int) -> Boolean)? = null
    ): Int {
        if (fromRaw == null || !fromRaw.isDirectory) return 0
        return runCatching { parallelCopy(fromRaw, newRoot, onProgress) }.getOrDefault(0)
    }

    private fun persist(pref: WorkspacePref) {
        runCatching {
            // 原子写（2026-10-10 Nit：工作区偏好，裸 writeText 截断会丢当前工作区绑定）
            HaoJson.writeAtomic(
                prefFile,
                HaoJson.json.encodeToString(WorkspacePref.serializer(), pref)
            )
        }
    }

    private fun restoreOrDefault() {
        val saved = runCatching {
            if (prefFile.exists()) {
                HaoJson.json.decodeFromString(WorkspacePref.serializer(), prefFile.readText())
            } else null
        }.getOrNull()

        // 交换目录/SAF 绑定优先恢复（与档位判定独立）
        exchangePath = saved?.exchangePath
        safUri = saved?.safUri

        // SAF 优先（存量兼容）；权限失效则继续尝试 rawPath
        if (saved?.safUri != null) {
            val uri = Uri.parse(saved.safUri)
            val granted = runCatching {
                context.contentResolver.persistedUriPermissions.any {
                    it.uri == uri && it.isReadPermission && it.isWritePermission
                }
            }.getOrDefault(false)
            if (granted) {
                runCatching { current = SafFileBackend(context, uri) }
            }
        }
        if (current == null && saved?.rawPath != null) {
            val f = File(saved.rawPath)
            val ok = runCatching {
                if (!f.exists()) f.mkdirs()
                f.isDirectory
            }.getOrDefault(false)
            if (ok) {
                current = RawFileBackend(f)
                currentRawPath = f.absolutePath
                rawKind = saved.rawKind
            }
        }
        if (current == null) {
            current = RawFileBackend(defaultRoot)
            currentRawPath = null
        }
    }
}

/**
 * 目录树复制（旧串行内核，单线程；保留给单测与兜底路径）：
 * 目标同名文件不覆盖；单文件失败不中断整体；返回实际复制文件数。
 * onCopied(全局已完成数) 每复制一个文件回调一次，返回 false 即中止（返回 -1）。
 */
internal fun copyDirTree(src: File, dst: File, onCopied: ((Int) -> Boolean)? = null): Int {
    return copyDirTreeInner(src, dst, intArrayOf(0), onCopied)
}

/**
 * 并行迁移内核（2026-10-23 性能版）：plan 一次收集清单 → 建目录 → N 路 worker 拷文件。
 * worker 数=min(8, max(4, 核数))——安卓 F2FS/sdcardfs 上多路并发能把碎文件
 * syscall 串行延迟摊平（真机实测单线程 7.2 万文件要数分钟）。
 * onProgress(done,total) 120ms 节流；返回 false=中止请求（已在途任务自然跑完）。
 */
internal fun parallelCopy(
    src: File,
    dst: File,
    onProgress: ((done: Int, total: Int) -> Boolean)?
): Int {
    val pairs = ArrayList<Pair<File, File>>()   // srcFile -> dstFile
    val dirs = ArrayList<Pair<File, File>>()    // srcDir -> dstDir
    collectTree(src, dst, pairs, dirs)
    for ((s, d) in dirs) runCatching { d.mkdirs() }
    val total = pairs.size
    if (total == 0) return 0
    onProgress?.invoke(0, total) // 开扫即推：UI 立刻拿到分母，进度条不空窗
    val copied = java.util.concurrent.atomic.AtomicInteger(0)    // 实际复制数（返回值）
    val processed = java.util.concurrent.atomic.AtomicInteger(0) // 已处理数（进度含跳过）
    val aborted = java.util.concurrent.atomic.AtomicBoolean(false)
    val lastPush = java.util.concurrent.atomic.AtomicLong(0L)
    val workers = (Runtime.getRuntime().availableProcessors() * 2).coerceIn(4, 8)
    // 进度推送（worker 线程调用）：120ms 节流 + 末个必推；返回 false=请求中止
    val push: (Int) -> Boolean = push@{ p ->
        if (onProgress == null) return@push true
        val now = System.currentTimeMillis()
        if (p >= total || now - lastPush.get() >= 120) {
            lastPush.set(now)
            val keep = onProgress(p, total)
            if (!keep) aborted.set(true)
            keep
        } else true
    }
    // worker 是自循环到队列空的常驻任务，数量固定 → 裸线程（比线程池语义贴切，
    // 无 core 满排队/拒绝之虞；start 失败极罕见，join 前不依赖 closeable，minSdk 26 安全）
    val queue = java.util.concurrent.ConcurrentLinkedQueue(pairs)
    val threads = Array(workers) {
        Thread {
            while (!aborted.get()) {
                val (s, d) = queue.poll() ?: break
                // 同名不覆盖（与串行内核同口径）；空文件照拷（保持树完整）。
                // 不再单独 exists() 预检——copyTo(overwrite=false) 自己会查并抛
                // FileAlreadyExistsException；预检=每个文件多付一次 FUSE stat
                // （真机单次数毫秒，2 万文件白烧一分钟级）。
                if (runCatching { s.copyTo(d, overwrite = false) }.isSuccess) {
                    copied.incrementAndGet()
                }
                // 无论复制还是同名跳过，都计 processed 让进度前进
                push(processed.incrementAndGet())
            }
        }
    }
    for (t in threads) t.start()
    for (t in threads) t.join()
    return copied.get()
}

/** 单遍收集：目录清单（先建）+ 文件清单（并行拷），顺序无关紧要。 */
private fun collectTree(
    src: File,
    dst: File,
    files: ArrayList<Pair<File, File>>,
    dirs: ArrayList<Pair<File, File>>
) {
    val entries = src.listFiles() ?: return
    for (e in entries) {
        val target = File(dst, e.name)
        if (e.isDirectory) {
            dirs.add(e to target)
            collectTree(e, target, files, dirs)
        } else {
            files.add(e to target)
        }
    }
}

/** 计数器数组跨递归共享；返回 -1 表示被中止（上层调用各自吞掉即可，已复制部分保留）。 */
private fun copyDirTreeInner(src: File, dst: File, counter: IntArray, onCopied: ((Int) -> Boolean)?): Int {
    var copied = 0
    val entries = src.listFiles() ?: return 0
    for (e in entries) {
        val target = File(dst, e.name)
        if (e.isDirectory) {
            target.mkdirs()
            val sub = copyDirTreeInner(e, target, counter, onCopied)
            if (sub < 0) return -1
            copied += sub
        } else if (!target.exists()) {
            if (runCatching { e.copyTo(target, overwrite = false) }.isSuccess) {
                copied++
                counter[0]++
                if (onCopied != null && !onCopied(counter[0])) return -1
            }
        }
    }
    return copied
}
