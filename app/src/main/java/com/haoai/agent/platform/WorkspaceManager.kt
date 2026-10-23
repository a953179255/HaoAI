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
     * 目标已有同名文件**不覆盖**（防毁新根现有内容/引擎文件）；目录递归。
     * 返回实际复制文件数；旧根不存在或不可读返回 0。
     */
    fun migrateInto(newRoot: File, fromRaw: File?): Int {
        if (fromRaw == null || !fromRaw.isDirectory) return 0
        return runCatching { copyDirTree(fromRaw, newRoot) }.getOrDefault(0)
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
 * 目录树复制（迁移器内核，顶层 internal 便于 JVM 单测）：
 * 目标同名文件不覆盖；单文件失败不中断整体；返回实际复制文件数。
 */
internal fun copyDirTree(src: File, dst: File): Int {
    var copied = 0
    val entries = src.listFiles() ?: return 0
    for (e in entries) {
        val target = File(dst, e.name)
        if (e.isDirectory) {
            target.mkdirs()
            copied += copyDirTree(e, target)
        } else if (!target.exists()) {
            if (runCatching { e.copyTo(target, overwrite = false) }.isSuccess) copied++
        }
    }
    return copied
}
