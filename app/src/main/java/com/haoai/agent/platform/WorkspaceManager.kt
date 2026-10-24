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
    val exchangePath: String? = null,
    /** 上次自选位置的路径（2026-10-24 改版）：切走后 radio 行尾的文件数徽标仍要指它。 */
    val customPath: String? = null
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

    /** 上次自选位置路径（radio 行尾文件数徽标指向它；null=从未自选过）。 */
    private var customPath: String? = null

    init {
        restoreOrDefault()
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
            persist(WorkspacePref(safUri = uriString, exchangePath = exchangePath, customPath = customPath))
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
            persist(
                WorkspacePref(
                    rawPath = f.absolutePath, rawKind = kind.name,
                    exchangePath = exchangePath,
                    customPath = if (kind == Mode.CUSTOM) f.absolutePath else customPath
                )
            )
            true
        }.getOrDefault(false)
    }

    /** UI 三态判定：SAF 存量绑定单列（UI 不再提供新绑定入口，但如实显示现状）。 */
    fun mode(): Mode = when {
        current is SafFileBackend -> Mode.SAF
        rawKind == Mode.PRIVATE.name -> Mode.PRIVATE
        currentRawPath == null -> Mode.PRIVATE
        rawKind == Mode.CUSTOM.name -> Mode.CUSTOM
        rawKind == Mode.PUBLIC.name ->
            // 2026-10-24 改版：公共档不再有「更换」入口，改过根的旧公共档（v3 时期经
            // 更换按钮落库的 rawKind=PUBLIC+自定义路径）按实际语义归入自选位置——
            // radio 落位/副标题/交换卡显隐全跟着走对；引擎绑定与路径不动、无迁移。
            if (currentRawPath == publicDefaultRoot().absolutePath) Mode.PUBLIC else Mode.CUSTOM
        // 存量 rawPath（无 kind 字段）按默认公共根兜底归类
        currentRawPath == publicDefaultRoot().absolutePath -> Mode.PUBLIC
        else -> Mode.CUSTOM
    }

    /** 旧默认工作区根（应用专属，外部私有）：脑分家的必经源——升级用户的脑在这。 */
    fun defaultWorkspaceRoot(): File = defaultRoot

    /** 公共交换目录默认根：Documents/HaoAI（v3 效果图定稿）。 */
    fun publicDefaultRoot(): File = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
        "HaoAI"
    )

    /** 当前公共交换目录（偏好优先，回落默认根）。 */
    fun exchangeRoot(): File = File(exchangePath ?: publicDefaultRoot().absolutePath)

    /** 上次自选位置路径（切走后仍记得；null=从未自选过）。 */
    fun workspaceCustomPath(): String? =
        customPath ?: currentRawPath?.takeIf { mode() == Mode.CUSTOM }

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
                    rawKind = rawKind, exchangePath = f.absolutePath,
                    customPath = customPath
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

        // 交换目录/SAF 绑定/上次自选路径优先恢复（与档位判定独立）
        exchangePath = saved?.exchangePath
        safUri = saved?.safUri
        customPath = saved?.customPath

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
            // 新装默认=公共文档（用户 2026-10-24 双档裁决：产出默认放公共目录）；
            // 无「所有文件访问」权限才回落应用专属沙盒（设置页有黄色横幅引导）。
            val pub = publicDefaultRoot()
            val pubOk = runCatching {
                if (!pub.exists()) pub.mkdirs()
                pub.isDirectory && android.os.Environment.isExternalStorageManager()
            }.getOrDefault(false)
            if (pubOk) {
                current = RawFileBackend(pub)
                currentRawPath = pub.absolutePath
                rawKind = Mode.PUBLIC.name
                persist(
                    WorkspacePref(
                        rawPath = pub.absolutePath,
                        rawKind = Mode.PUBLIC.name,
                        exchangePath = exchangePath,
                        customPath = customPath
                    )
                )
            } else {
                current = RawFileBackend(defaultRoot)
                currentRawPath = null
            }
        }
        // 双档裁决（2026-10-24）：应用专属退役为产出位置。存量用户升级后绑定停在私有
        // 沙盒（老升级保护遗留）→ 自动切到公共文档，零搬运：文件原地保留，由设置页
        // 「旧产出区」蓝条指路（只读浏览/一键清理）。无存储权限时保持原地 + 横幅引导。
        val livePath = (current as? RawFileBackend)?.shellWorkdir()?.absolutePath
        if (livePath != null && livePath == defaultRoot.absolutePath) {
            val pub = publicDefaultRoot()
            val pubOk = runCatching {
                if (!pub.exists()) pub.mkdirs()
                pub.isDirectory && android.os.Environment.isExternalStorageManager()
            }.getOrDefault(false)
            if (pubOk) {
                current = RawFileBackend(pub)
                currentRawPath = pub.absolutePath
                rawKind = Mode.PUBLIC.name
                persist(
                    WorkspacePref(
                        rawPath = pub.absolutePath,
                        rawKind = Mode.PUBLIC.name,
                        exchangePath = exchangePath,
                        customPath = customPath
                    )
                )
            }
        }
    }
}

