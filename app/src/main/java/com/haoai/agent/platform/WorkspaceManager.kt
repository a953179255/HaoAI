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
 * 三态判定：rawPath==null → 专属；rawPath==默认公共根 → 公共文档；其他 rawPath → 自选位置。
 */
@Serializable
private data class WorkspacePref(val safUri: String? = null, val rawPath: String? = null)

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

    init {
        restoreOrDefault()
    }

    fun useDefault() {
        current = RawFileBackend(defaultRoot)
        currentRawPath = null
        persist(WorkspacePref())
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
            persist(WorkspacePref(safUri = uriString))
            true
        }.getOrDefault(false)
    }

    /**
     * 三档改造（2026-10-23）：真实路径档——公共文档（Documents/HaoAI）与自选位置共用。
     * 目录不存在则尝试创建；创建/校验失败返回 false 不切换。
     */
    fun useRaw(path: String): Boolean {
        val f = File(path)
        return runCatching {
            if (!f.exists()) f.mkdirs()
            if (!f.isDirectory) return false
            current = RawFileBackend(f)
            currentRawPath = f.absolutePath
            persist(WorkspacePref(rawPath = f.absolutePath))
            true
        }.getOrDefault(false)
    }

    /** UI 三态判定：SAF 存量绑定单列（UI 不再提供新绑定入口，但如实显示现状）。 */
    fun mode(): Mode = when {
        current is SafFileBackend -> Mode.SAF
        currentRawPath == null -> Mode.PRIVATE
        currentRawPath == publicDefaultRoot().absolutePath -> Mode.PUBLIC
        else -> Mode.CUSTOM
    }

    /** 公共交换目录默认根：Documents/HaoAI（v3 效果图定稿）。 */
    fun publicDefaultRoot(): File = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
        "HaoAI"
    )

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
