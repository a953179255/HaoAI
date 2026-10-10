package com.haoai.agent.platform

import android.net.Uri
import com.haoai.agent.agent.skills.SkillStore
import com.haoai.agent.data.AppContainer
import com.haoai.agent.data.HaoJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** 备份范围。每一项都是"能独立看懂的一堆文件"，不做半个域的拆分（恢复要么整包要么不恢复）。 */
enum class BackupScope(val label: String, val hint: String) {
    SETTINGS("设置与供应商", "模型服务、协议、上下文长度、权限模式、思考等级"),
    SESSIONS("会话记录", "全部对话与工具轨迹，含压缩摘要与水位"),
    MEMORY("记忆与日志", "MEMORY.md、每日日志、固化报告"),
    SKILLS("技能", "工作区 skills 目录（含候选态与启用状态）"),
    TASKS("待办与用量账本", "各会话任务清单、按月 token 账本"),
    EXTRAS("界面与自动化", "聊天壁纸、工作流、定时任务")
}

/** 一次导出的结果统计。 */
data class BackupSummary(val entries: Int, val bytes: Long, val keysIncluded: Boolean)

/** 一份本机快照（破坏性操作前自动留的那批）。[at] 是定宽的 `yyyyMMdd-HHmmss`。 */
data class Snapshot(val file: File, val at: String, val reason: String, val sessionId: String)

/**
 * 全量数据备份（导出侧）。
 *
 * 包结构：`manifest.json` + `payload/<域>/<相对路径>`。manifest 记格式版本、包名、时间、
 * 勾选的范围、是否含明文 Key，以及**每一条 payload 的大小与 sha256**——恢复侧据此逐条校验，
 * 任一条不匹配就整包拒绝。不做"跳过坏文件继续"：那样恢复出来的是一半新的一半旧的会话库，
 * 比恢复失败更难排查。
 *
 * 为什么不直接照抄 settings.json：providers 里的 `apiKeyCipher` 是本机 AndroidKeyStore
 * 加出来的（[com.haoai.agent.data.KeystoreCipher]），密钥不可导出，换机后那串就是死字。
 * 所以默认**把所有密钥字段剔掉**再导出（恢复后重贴一次 Key），只有用户显式勾选
 * "包含明文 Key"才写入 `apiKey` 明文。注意配置桥的真源
 * `files/state/haoai.config.json` 里可能带明文 `apiKey`，不含 Key 时也必须一并脱敏，
 * 否则"默认不含"就成了假承诺。
 *
 * 明文导出的约定：`apiKeyCipher`/`apiKeyPoolCiphers` 被移除，改由 `apiKey`（字符串）与
 * `apiKeyPool`（数组）承载明文。恢复侧读到时必须重新加密入库——这个契约写在 manifest 的
 * `keysIncluded` 里，别让两边各猜一套。
 */
object DataBackupManager {

    const val FORMAT_VERSION = 1
    const val MANIFEST_NAME = "manifest.json"
    /** 明文 Key 映射里搜索后端的保留前缀（providerId 不可能是这个形状，不会撞）。 */
    internal const val SEARCH_KEY_PREFIX = "search:"
    /** MCP header 明文映射的 key 形态：`serverId + 本前缀 + headerName`。 */
    internal const val MCP_HEADER_KEY_PREFIX = "mcp-h:"
    /** PC 配对 token 在明文映射里的固定 key。 */
    internal const val PC_TOKEN_KEY = "pc:token"
    /** MCP header 密文前缀，必须与 McpServerStore.ENC_PREFIX 一致，否则恢复后 load() 认不出。 */
    private const val MCP_ENC_PREFIX = "enc:"
    /** PC 配对 token 密文前缀，必须与 PcStore.encPrefix 一致。 */
    private const val PC_ENC_PREFIX = "enc:v1:"
    private const val PAYLOAD = "payload/"
    private const val KEEP_SNAPSHOTS = 8

    /** 两份配置真源里出现过的密钥字段名（递归剔除，兼容以后新增的嵌套形状）。 */
    private val SECRET_KEYS = setOf(
        "apiKeyCipher", "apiKeyPoolCiphers", "apiKey", "apiKeyPool",
        // 搜索服务的 key 按后端分键存（Map<backend, 密文>），同一套规则：默认不出包
        "searchApiKeyCiphers", "searchApiKeys"
    )

    // ── 导出 ────────────────────────────────────────────────────────

    /** 导出到用户选好的位置（SAF Uri）。 */
    fun exportZip(
        c: AppContainer,
        uri: Uri,
        scopes: Set<BackupScope>,
        includeKeys: Boolean
    ): Result<BackupSummary> = runCatching {
        // 自检放在开流之前：openOutputStream 已经建好目标文件，中途抛错会留下一个
        // 0 字节的 .zip 躺在用户选的位置上（用户以为导出成功、实际打不开）。
        val gaps = auditBackupCoverage(c, scopes).filter { it.startsWith("落盘位置未纳入备份") }
        if (gaps.isNotEmpty()) {
            error("备份范围不完整，已中止导出：\n" + gaps.joinToString("\n"))
        }
        c.appContext.contentResolver.openOutputStream(uri)?.use { out ->
            val s = writeZip(c, out, scopes, includeKeys)
            c.updateSettings { it.copy(lastDataExportAt = System.currentTimeMillis()) }
            s
        } ?: error("无法打开导出目标")
    }

    /**
     * 破坏性操作（手动压缩、彻底删除会话）前，把**受影响的那条会话**原样复制一份到
     * `files/backups/`，保留最近 [KEEP_SNAPSHOTS] 份。
     *
     * 刻意只存单文件、不打整包：这两种操作改坏的都只是这一条会话，整包会把全部会话
     * 重写一遍；记忆真源另有 [MemoryBackupManager.autoBackup] 在每轮固化后留快照。
     *
     * 也刻意**不做每日定时**：本机目录随卸载/清除数据一起消失，换机也带不走，定时全量备份
     * 救不了真正会丢数据的三种情况；而 Flyme 上 WorkManager 会被电池优化饿死（实测过：
     * 不进白名单时连数据库目录都不初始化），定时任务只会给出"以为有备份其实三个月没跑"的
     * 虚假安全感。按事件打快照才是确定会执行到的时机。失败静默——快照不该挡住用户的操作。
     */
    fun snapshotSession(c: AppContainer, sessionId: String, reason: String) {
        runCatching {
            if (!c.settingsFlow.value.backupSnapshots) return@runCatching
            val src = c.sessionStore.sessionsDir().resolve("$sessionId.json")
            if (!src.isFile) return@runCatching
            val dir = File(c.appFilesDir, "backups").apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.CHINA).format(Date())
            // 时间戳放在 reason 之前：否则 "pre-compact-…" 与 "pre-purge-…" 之间
            // 字典序按字母排，prune 与"最近一次"显示都会挑错文件
            src.copyTo(File(dir, "pre-$stamp-$reason-$sessionId.json"), overwrite = true)
            pruneSnapshots(dir)
        }
    }

    fun snapshots(c: AppContainer): List<Snapshot> =
        File(c.appFilesDir, "backups").listFiles { f ->
            f.isFile && f.name.startsWith("pre-") && f.name.endsWith(".json")
        }?.mapNotNull { parseSnapshot(it) }?.sortedByDescending { it.at }.orEmpty()

    /** 解析 `pre-<yyyyMMdd-HHmmss>-<reason>-<id>.json`；名字不合式的忽略（可能是别的机制写的）。 */
    internal fun parseSnapshot(f: File): Snapshot? {
        val parts = f.name.removePrefix("pre-").removeSuffix(".json").split('-', limit = 4)
        if (parts.size < 4) return null
        val stamp = "${parts[0]}-${parts[1]}"
        if (parts[0].length != 8 || parts[1].length != 6) return null
        return Snapshot(f, stamp, parts[2], parts[3])
    }

    internal fun pruneSnapshots(dir: File) {
        dir.listFiles { f -> f.isFile && f.name.startsWith("pre-") && f.name.endsWith(".json") }
            ?.mapNotNull { parseSnapshot(it) }      // 认不出的名字不动，避免误删别的机制留的文件
            ?.sortedByDescending { it.at }          // 定宽时间戳，字典序即时间序
            ?.drop(KEEP_SNAPSHOTS)
            ?.forEach { runCatching { it.file.delete() } }
    }

    // ── 体积（UI 上让用户看见"这一项有多大"再决定勾不勾）────────────────

    /**
     * 某个范围实际会进包的文件：`payload/` 下的相对路径 → 源文件。
     *
     * 打包与体积估算共用这一份清单——否则页面上"约 12 MB"和真正导出的内容会是两套口径，
     * 而这种偏差只会在用户拿包救命的当天暴露。settings 的两份 JSON 不在这里：它们要经
     * 密钥改写，源文件不能直接进包，只按原样计入大小。
     */
    private fun fileSources(c: AppContainer, scope: BackupScope): List<Pair<String, File>> =
        (when (scope) {
            BackupScope.SETTINGS -> listOf(
                "workspace.json" to File(c.appFilesDir, "workspace.json")
            )

            BackupScope.SESSIONS ->
                c.sessionStore.sessionsDir().listByExt("json").map { "sessions/${it.name}" to it }

            BackupScope.MEMORY -> buildList {
                add("memory/MEMORY.md" to c.memoryBank.storageFile())
                addAll(c.journal.currentStorageDir().listByExt("md")
                    .map { "memory/journal/${it.name}" to it })
                WorkspaceDocs.workspaceRoot(c)?.let { root ->
                    addAll(listOf("USER.md", "DREAMS.md").mapNotNull { n ->
                        root.resolve(n).takeIf { it.isFile }?.let { "workspace/$n" to it }
                    })
                    addAll(root.resolve("dreaming").listByExt("md")
                        .map { "workspace/dreaming/${it.name}" to it })
                }
            }

            BackupScope.SKILLS -> {
                val root = runCatching { SkillStore.currentDir() }.getOrNull()
                if (root == null || !root.isDirectory) emptyList()
                else root.walkTopDown().filter { it.isFile }.map {
                    "skills/" + it.relativeTo(root).path.replace(File.separatorChar, '/') to it
                }.toList()
            }

            BackupScope.TASKS -> listOf(
                File(c.appFilesDir, "todos").listByExt("json"),
                File(c.appFilesDir, "usage").listByPrefix("usage-")
            ).flatten().map {
                (if (it.extension == "json") "todos/" else "usage/") + it.name to it
            }

            BackupScope.EXTRAS -> buildList {
                addAll(
                    WallpaperStore.storedFile(c.appContext).takeIf { it.isFile }?.let {
                        listOf("extras/wallpaper.img" to it)
                    }.orEmpty()
                )
                File(c.appFilesDir, "workflows").listByExt("json")
                    .forEach { add("extras/workflows/${it.name}" to it) }
                File(c.appFilesDir, "schedules.json").takeIf { it.isFile }?.let {
                    add("extras/schedules.json" to it)
                }
            }
        }).filter { it.second.isFile }

    private fun File.listByExt(vararg ext: String): List<File> =
        listFiles { f -> f.isFile && f.extension in ext }?.sorted().orEmpty()

    private fun File.listByPrefix(prefix: String): List<File> =
        listFiles { f -> f.isFile && f.name.startsWith(prefix) }?.sorted().orEmpty()

    /**
     * **备份范围自检**（2026-10-10 方案 A 落地项）。
     *
     * 背景：MCP 服务器配置（`mcp/servers.json`，含鉴权头）与电脑联动配对（`pc-link.json`）
     * 曾长期不在任何备份域内 —— 换机后要一个个重填。这类漏项的成因不是"忘了写"，
     * 而是**没有任何机制在新增落盘位置时提醒要同步 backup**：初轮代码审查也是分区并行阅读，
     * 恰好两边都没人做"落盘位置 × 备份范围"的完整对账。
     *
     * 现在把这份对账固化成代码：[expectedCoverage] 是人工维护的"落盘位置 → 应归哪个域"
     * 清单，与 [fileSources] 的实际输出逐条比对，不一致就抛错 —— 让下次新增落盘位置时
     * 在编译后第一次导出就炸，而不是等用户换机才发现丢东西。
     *
     * 判据刻意保守：清单里列出的路径要么已覆盖，要么**该域根本不会被选择**
     * （未勾选）时不算漏；只有"这次真要打包、而清单里的文件没进包"才算不一致。
     */
    internal fun auditBackupCoverage(c: AppContainer, scopes: Set<BackupScope>): List<String> {
        val problems = mutableListOf<String>()

        // ① 人工清单里、但 fileSources 没覆盖到的落盘位置。
        val covered = mutableSetOf<String>()
        for (scope in BackupScope.entries) {
            if (scope !in scopes) continue
            val paths = fileSources(c, scope).map { it.first }.toMutableSet()
            // SETTINGS 域另有四个走密钥改写、不在 fileSources 里的固定 payload
            if (scope == BackupScope.SETTINGS) {
                paths += listOf(
                    "settings/settings.json",
                    "settings/mcp/servers.json",
                    "settings/pc-link.json",
                    "state/haoai.config.json"
                )
            }
            covered += paths
        }
        for ((label, rel) in expectedCoverage) {
            val inPackage = rel in covered ||
                rel.removePrefix("payload/") in covered ||
                covered.any { it.endsWith("/" + rel) || it.endsWith(rel) }
            if (!inPackage) problems += "落盘位置未纳入备份：$label（期望 $rel，当前所选范围未产出该文件）"
        }

        // ② 反向：包内出现了清单外的路径 = 有新落盘位置没登记进 expectedCoverage。
        // 只提示不抛错（新增功能不该让老版本直接崩），但导出摘要会带出去。
        val known = expectedCoverage.map { it.second }.toSet()
        for (scope in BackupScope.entries) {
            if (scope !in scopes) continue
            for ((path, _) in fileSources(c, scope)) {
                val bare = path.removePrefix("payload/")
                if (bare.isEmpty()) continue
                val registered = known.any { bare == it || bare.endsWith("/" + it) || it.endsWith("/" + bare) }
                if (!registered) problems += "备份清单未登记的新增文件：$bare（$scope 域，请补进 expectedCoverage）"
            }
        }
        return problems
    }

    /**
     * 人工维护的「落盘位置 → 备份相对路径」对照表。
     *
     * 新增任何写盘位置（新的 Store / 新的 *.json）时，**必须**在这里登记一行，
     * 否则 [auditBackupCoverage] 会在导出时报出来。这是有意设计的摩擦。
     */
    internal val expectedCoverage: List<Pair<String, String>> = listOf(
        "模型/搜索 Key 与供应商配置" to "settings/settings.json",
        "配置桥（config_set 落点）" to "state/haoai.config.json",
        "工作区文档定位" to "workspace.json",
        "MCP 服务器（含鉴权头）" to "settings/mcp/servers.json",
        "电脑联动配对令牌" to "settings/pc-link.json",
        "会话历史" to "sessions",
        "长期记忆" to "memory/MEMORY.md",
        "每日日志" to "memory/journal",
        "用户档案" to "workspace/USER.md",
        "梦境报告" to "workspace/DREAMS.md",
        "技能包" to "skills",
        "待办清单" to "todos",
        "用量账本" to "usage",
        "壁纸" to "extras/wallpaper.img",
        "工作流" to "extras/workflows",
        "定时任务" to "extras/schedules.json"
    )

    fun scopeSize(c: AppContainer, scope: BackupScope): Long = when (scope) {
        BackupScope.SETTINGS -> fileSources(c, scope).sumOf { it.second.length() } +
            listOf("settings/settings.json", "state/haoai.config.json")
                .map { File(c.appFilesDir, it) }
                .filter { it.isFile }.sumOf { it.length() }

        else -> fileSources(c, scope).sumOf { it.second.length() }
    }

    // ── 打包实现 ────────────────────────────────────────────────────

    /** 包内一条文件记录（manifest.items 的元素，恢复侧也用它逐条校验）。 */
    data class Item(val path: String, val size: Long, val sha256: String)

    /** 一个待打包条目。文件按流写（会话文件可能几 MB，不该整份进内存），改写过的内容才走字节。 */
    internal sealed interface Payload {
        val path: String
        data class OfFile(override val path: String, val file: File) : Payload
        data class OfBytes(override val path: String, val bytes: ByteArray) : Payload
    }

    private fun writeZip(
        c: AppContainer,
        out: OutputStream,
        scopes: Set<BackupScope>,
        includeKeys: Boolean
    ): BackupSummary {
        // 方案 A 自检：勾选的范围内若存在"该备份却没进包"的落盘位置，直接抛错中止导出。
        // 宁可不导出，也不能让用户拿着一份"看着成功、其实缺东西"的包去换机。
        val gaps = auditBackupCoverage(c, scopes)
        val gapsBlocking = gaps.filter { it.startsWith("落盘位置未纳入备份") }
        if (gapsBlocking.isNotEmpty()) {
            throw IllegalStateException(
                "备份范围不完整，已中止导出：\n" + gapsBlocking.joinToString("\n")
            )
        }
        if (gaps.isNotEmpty()) {
            android.util.Log.w("HaoBackup", "备份清单待更新：\n" + gaps.joinToString("\n"))
        }
        val payload = ArrayList<Payload>()
        if (BackupScope.SETTINGS in scopes) {
            val plain = if (includeKeys) plaintextKeys(c) else emptyMap()
            File(c.appFilesDir, "settings/settings.json").takeIf { it.isFile }?.let {
                payload += Payload.OfBytes(
                    PAYLOAD + "settings/settings.json",
                    transformSecrets(it.readText(), plain).toByteArray()
                )
            }
            // 配置桥真源：不含 Key 时同样脱敏（里面可能躺着明文 apiKey）
            File(c.appFilesDir, "state/haoai.config.json").takeIf { it.isFile }?.let {
                payload += Payload.OfBytes(
                    PAYLOAD + "state/haoai.config.json",
                    transformSecrets(it.readText(), plain).toByteArray()
                )
            }
            // MCP 服务器配置（2026-10-10 补入）：此前完全不在任何备份域内，换机后所有
            // MCP 服务器（含鉴权头）要一个个重填。headers 的值在 McpServerStore 里是
            // enc:密文，换机后旧 Keystore 密钥解不开 ⇒ 含 Key 时注入明文、恢复时重新加密。
            File(c.appFilesDir, "mcp/servers.json").takeIf { it.isFile }?.let {
                payload += Payload.OfBytes(
                    PAYLOAD + "settings/mcp/servers.json",
                    transformSecrets(it.readText(), plain).toByteArray()
                )
            }
            // 电脑联动配对（2026-10-10 补入）：token 是enc:v1密文，同上。
            File(c.appFilesDir, "pc-link.json").takeIf { it.isFile }?.let {
                payload += Payload.OfBytes(
                    PAYLOAD + "settings/pc-link.json",
                    transformSecrets(it.readText(), plain).toByteArray()
                )
            }
        }
        scopes.sortedBy { it.ordinal }.forEach { scope ->
            fileSources(c, scope).forEach { (entry, f) -> payload += Payload.OfFile(PAYLOAD + entry, f) }
        }
        return packEntries(out, payload) { items ->
            manifestJson(c, scopes, includeKeys, items, System.currentTimeMillis())
        }.let { BackupSummary(it.size, it.sumOf { i -> i.size }, includeKeys) }
    }

    /**
     * 纯 IO 的打包核心，不依赖 Android：逐条写出并累计 sha256，最后写 manifest。
     * manifest 只列 payload，不含它自己——恢复侧遍历 items 校验时不该撞上一条无法自证的记录。
     */
    internal fun packEntries(
        out: OutputStream,
        payload: List<Payload>,
        manifestFor: (List<Item>) -> String
    ): List<Item> {
        val items = ArrayList<Item>()
        ZipOutputStream(BufferedOutputStream(out)).use { zip ->
            payload.forEach { p ->
                when (p) {
                    is Payload.OfFile -> items += zipFile(p.path, p.file, zip)
                    is Payload.OfBytes -> items += zipBytes(p.path, p.bytes, zip)
                }
            }
            zip.putNextEntry(ZipEntry(MANIFEST_NAME))
            zip.write(manifestFor(items.toList()).toByteArray())
            zip.closeEntry()
            zip.finish()
        }
        return items
    }

    private fun zipFile(entry: String, f: File, zip: ZipOutputStream): Item {
        val digest = MessageDigest.getInstance("SHA-256")
        zip.putNextEntry(ZipEntry(entry))
        f.inputStream().buffered().use { ins -> DigestInputStream(ins, digest).copyTo(zip) }
        zip.closeEntry()
        return Item(entry, f.length(), digest.hex())
    }

    private fun zipBytes(entry: String, data: ByteArray, zip: ZipOutputStream): Item {
        val digest = MessageDigest.getInstance("SHA-256").digest(data)
        zip.putNextEntry(ZipEntry(entry))
        zip.write(data)
        zip.closeEntry()
        return Item(entry, data.size.toLong(), digest.joinToString("") { "%02x".format(it) })
    }

    private fun MessageDigest.hex(): String = digest().joinToString("") { "%02x".format(it) }

    private fun manifestJson(
        c: AppContainer,
        scopes: Set<BackupScope>,
        includeKeys: Boolean,
        items: List<Item>,
        createdAt: Long
    ): String = manifestJson(
        packageName = c.appContext.packageName,
        appVersion = runCatching {
            c.appContext.packageManager.getPackageInfo(c.appContext.packageName, 0).versionName
        }.getOrNull().orEmpty(),
        scopes = scopes, includeKeys = includeKeys, items = items, createdAt = createdAt
    )

    internal fun manifestJson(
        packageName: String,
        appVersion: String,
        scopes: Set<BackupScope>,
        includeKeys: Boolean,
        items: List<Item>,
        createdAt: Long
    ): String {
        val json = buildJsonObject {
            put("formatVersion", FORMAT_VERSION)
            put("packageName", packageName)
            put("createdAt", createdAt)
            put("appVersionName", appVersion)
            put("keysIncluded", includeKeys)
            putJsonArray("scopes") { scopes.forEach { add(it.name) } }
            putJsonArray("items") {
                items.forEach {
                    addJsonObject {
                        put("path", it.path); put("size", it.size); put("sha256", it.sha256)
                    }
                }
            }
        }
        return HaoJson.json.encodeToString(JsonObject.serializer(), json)
    }

    // ── 密钥处理 ────────────────────────────────────────────────────

    /** providerId → 该家全部明文 Key（主 Key + 备用池）；搜索后端的 key 以 [SEARCH_KEY_PREFIX] 打头同表存放。 */
    private fun plaintextKeys(c: AppContainer): Map<String, List<String>> {
        val s = c.settingsFlow.value
        val byProvider = s.providers.mapNotNull { p ->
            val all = (listOf(p.apiKeyCipher) + p.apiKeyPoolCiphers)
                .filter { it.isNotBlank() }
                .map { c.cipher.decrypt(it) }
                .filter { it.isNotBlank() }
            if (all.isEmpty()) null else p.id to all
        }
        // 勾选"含 Key"时搜索服务的 key 也要跟着出包——否则换机恢复后要重新逐家粘一遍，
        // 而界面上已经承诺了"包含密钥"
val bySearch: Map<String, List<String>> = s.searchApiKeyCiphers.mapNotNull { (backend, cipher) ->
      val plain = c.cipher.decrypt(cipher)
    if (plain.isBlank()) null else SEARCH_KEY_PREFIX + backend to listOf(plain)
    }.toMap()
        val byProviderMap: Map<String, List<String>> = byProvider.toMap()
   return LinkedHashMap<String, List<String>>().apply {
            putAll(byProviderMap)
            putAll(bySearch)
    putAll(mcpAndPcPlaintexts(c))
        }
    }

    /**
     * MCP header 与 PC 配对 token 的明文收集（2026-10-10 随"纳入备份"一并加入）。
     *
     * 这两处密钥的存放形态与 provider 不同：MCP 走 `McpServerStore` 自己的 Keystore 盒子
     * （值带 `enc:` 前缀），PC token 带 `enc:v1:` 前缀。**只有把它们一并解密出包，
     * 换机恢复后才真的不用重填**——否则备份里躺的还是旧机密文，新机 Keystore 解不开，
     * 等于没备份。
     *
     * 约定用带前缀的 key 传参，与 [SEARCH_KEY_PREFIX] 同风格：
     * `McpServerConfig.id + MCP_HEADER_KEY_PREFIX + headerName` → 明文。
     */
    private fun mcpAndPcPlaintexts(c: AppContainer): Map<String, List<String>> {
        val out = LinkedHashMap<String, List<String>>()
        runCatching {
            val cipher = com.haoai.agent.data.KeystoreCipher()
            for (srv in com.haoai.agent.agent.mcp.McpServerStore.load()) {
                for ((hName, hVal) in srv.headers) {
                    val plain = if (hVal.startsWith("enc:")) cipher.decrypt(hVal.removePrefix("enc:")) else hVal
                    if (plain.isNotBlank()) out[srv.id + MCP_HEADER_KEY_PREFIX + hName] = listOf(plain)
                }
            }
            val pcFile = File(c.appFilesDir, "pc-link.json")
            if (pcFile.isFile) {
                val ep = runCatching {
                    HaoJson.json.decodeFromString(
                        com.haoai.agent.platform.PcEndpoint.serializer(), pcFile.readText()
                    )
                }.getOrNull()
                val tok = ep?.token.orEmpty()
                val plain = if (tok.startsWith("enc:v1:")) cipher.decrypt(tok.removePrefix("enc:v1:")) else tok
                if (plain.isNotBlank()) out[PC_TOKEN_KEY] = listOf(plain)
            }
        }.onFailure {
            android.util.Log.w("HaoBackup", "收集 MCP/PC 密钥失败，这两处将以密文原样出包：${it.message}")
        }
        return out
    }

    /** 把 MCP header / PC token 的明文写回 JSON（供 [inlineSecrets] 用）。 */
    private fun inlineMcpSecrets(el: JsonElement, plain: Map<String, List<String>>): JsonElement = when (el) {
        is JsonArray -> buildJsonArray {
            el.forEach { item ->
                if (item is JsonObject) {
                    val id = (item["id"] as? JsonPrimitive)?.contentOrNull
                    val headers = item["headers"] as? JsonObject
                    if (id != null && headers != null && headers.isNotEmpty()) {
                        add(buildJsonObject {
                            item.forEach { (k, v) -> if (k != "headers") put(k, v) }
                            put("headers", buildJsonObject {
                                headers.forEach { (hName, _) ->
                                    plain[id + MCP_HEADER_KEY_PREFIX + hName]?.firstOrNull()?.let { put(hName, it) }
                                }
                            })
                        })
                    } else add(item)
                } else add(item)
            }
        }
        else -> el
    }

    /**
     * 重写 JSON 里的密钥：[plain] 为空就把密钥字段全部剔除；非空则在每个 provider 上
     * 写 `apiKey`/`apiKeyPool` 明文并移除密文字段。递归处理，配置桥那份额外形状也吃得下。
     *
     * 2026-10-10 修复（C4）：原实现解析失败时 `?: return text` **整段放行原文**，
     * 而调用点包含本身就可能躺明文 apiKey 的 `state/haoai.config.json` ⇒ JSON 一旦损坏，
     * 未脱敏原文直接进备份包。
     *
     * 修复不能改成"中止导出"——那会砸掉用户明确勾选的「一键恢复含密钥」。
     * 正确做法是**按 plain 是否为空分两种保守方向**：
     * · [plain] 为空（用户要的是不含密钥）：解析失败绝不能放行原文，改用正则逐行兜底剔除。
     * · [plain] 非空（用户已显式授权明文出包）：明文本就是这趟的目的，放行不构成新增泄露，
     *   保持原有行为，但补一条日志让"这次导出未经脱敏结构化处理"可追溯。
     */
    internal fun transformSecrets(text: String, plain: Map<String, List<String>>): String {
        val root = runCatching {
            HaoJson.json.parseToJsonElement(text)
        }.getOrNull()
        if (root == null) {
            if (plain.isEmpty()) {
                // 不含密钥路径：脱敏是硬边界，宁可这份文件不带也不能带原文
                android.util.Log.w(
                    "HaoBackup",
                    "配置文件 JSON 损坏，无法结构化脱敏，已按保守策略整份剔除密钥字段"
                )
                return regexStripSecrets(text)
            }
            android.util.Log.w(
                "HaoBackup",
                "配置文件 JSON 损坏；本次导出已勾选含密钥，按原文写出（用户已显式授权）"
            )
            return text
        }
        return HaoJson.json.encodeToString(
            JsonElement.serializer(), if (plain.isEmpty()) stripSecrets(root) else inlineSecrets(root, plain)
        )
    }

    private fun stripSecrets(el: JsonElement): JsonElement = when (el) {
        is JsonObject -> buildJsonObject {
            el.forEach { (k, v) -> if (k !in SECRET_KEYS) put(k, stripSecrets(v)) }
        }
        is JsonArray -> buildJsonArray { el.forEach { add(stripSecrets(it)) } }
        else -> el
    }

    /**
     * 结构化解析失败时的兜底脱敏（2026-10-10 随 C4 一同加入）。
     *
     * 用正则而非 JsonElement 是因为此刻 JSON 已经解析不了，唯一的目标是"别把原文带出去"。
     * 宁可漏掉（极少见的转义形态）也不能像原来那样整段放行。
     * 覆盖 [SECRET_KEYS] 里全部字段名 + MCP header 的 enc: 密文 + 配对 token。
     *
     * ## M1 修复（2026-10-10）
     *
     * 原实现只认 `enc:` / `enc:v1:` 前缀的密文，但 headers 里的凭据
     * **并不保证带这个前缀**：用户可以自己填 `Authorization: Bearer sk-xxx`，
     * 也可能是历史遗留的明文。这类值既不匹配 enc: 规则、键名也不在
     * [SECRET_KEYS] 里 ⇒ **原样进备份包**。这是本函数唯一还在漏的路径，
     * 而且恰恰是方案 A 里用户最在意的"一键恢复模型"配置。
     *
     * 修法：`headers` 对象里的每个 value 都是凭据（这是 MCP 协议本身决定的
     * —— header 就是鉴权载体），整块打码，**保留键名**：用户 restore 后还能
     * 从 `Authorization` / `X-Api-Key` 这些名字认出是哪个服务，
     * 不至于对着一堆 `***` 不知道要填什么。
     *
     * 只匹配 `{...}` 内不含嵌套花括号的平坦块：真有嵌套时宁可不改
     * （`enc:` 与 SECRET_KEYS 两条规则仍在生效），也不冒险截断。
     */
    private fun regexStripSecrets(text: String): String {
        var out = text
        for (key in SECRET_KEYS) {
            out = Regex("\"$key\"\\s*:\\s*(\"(?:[^\"\\\\]|\\\\.)*\"|\\[[^\\]]*\\]|\\{[^}]*\\}|null|true|false|[0-9.]+)")
                .replace(out, "\"$key\":\"***\"")
        }
        // MCP header 与配对 token 是 enc:/enc:v1: 前缀的密文，键名本身不敏感，值必须替换
        out = Regex("(\"(?:[^\"\\\\]|\\\\.)*\"\\s*:\\s*)\"(?:enc:|enc:v1:)[^\"]*\"")
            .replace(out) { m -> "${m.groupValues[1]}\"***\"" }
        // M1：`headers` 里每个 value 都是凭据（含不带 enc: 前缀的明文）。保留键名，只打码值。
        out = Regex("(\"headers\"\\s*:\\s*\\{)([^{}]*)(\\})")
            .replace(out) { m ->
                val masked = Regex("(:\\s*)(\"(?:[^\"\\\\]|\\\\.)*\"|[^,}\\s]+)")
                    .replace(m.groupValues[2]) { v -> "${v.groupValues[1]}\"***\"" }
                "${m.groupValues[1]}$masked${m.groupValues[3]}"
            }
        return out
    }

/**
     * 三种密钥形态各走各的改写（递归处理，配置桥那份额外形状也吃得下）：
     * 1. provider：`apiKey`/`apiKeyPool` 明文 ↔ `apiKeyCipher`/`apiKeyPoolCiphers`
     * 2. 搜索后端：`searchApiKeys`(Map backend→明文) ↔ `searchApiKeyCiphers`
     * 3. MCP servers.json（顶层是数组）：header 明文 ↔ `enc:`密文；PC 配对：`token` 明文 ↔ `enc:v1:`
     */
    private fun inlineSecrets(el: JsonElement, plain: Map<String, List<String>>): JsonElement = when (el) {
        is JsonObject -> {
            val providers = el["providers"] as? JsonArray
            buildJsonObject {
                el.forEach { (k, v) ->
                    when {
                        k == "providers" && providers != null -> put(
                            "providers",
                            buildJsonArray { providers.forEach { p -> add(providerWithKey(p, plain)) } }
                        )
                        // 搜索后端的 key 是 backend → 密文 的映射，形状与 provider 不同，单独改写
                        k == "searchApiKeyCiphers" && v is JsonObject -> {
                            val out = v.keys.mapNotNull { backend ->
                                plain[SEARCH_KEY_PREFIX + backend]?.firstOrNull()?.let { backend to it }
                            }
                            if (out.isNotEmpty()) put(
                                "searchApiKeys",
                                buildJsonObject { out.forEach { (b, key) -> put(b, key) } }
                            )
                        }
                        k in SECRET_KEYS -> Unit
                        else -> put(k, inlineSecrets(v, plain))
                    }
                }
            }
        }
        // MCP servers.json 的顶层是数组（List<McpServerConfig>），没有 providers/search 分支，
        // 必须在这里整体做 header 明文回填，否则含 Key 导出时它仍是 enc: 密文
        is JsonArray -> buildJsonArray { el.forEach { add(inlineMcpSecrets(it, plain)) } }
        else -> el
    }

    private fun providerWithKey(p: JsonElement, plain: Map<String, List<String>>): JsonElement {
        if (p !is JsonObject) return p
        val id = (p["id"] as? JsonPrimitive)?.contentOrNull
        return buildJsonObject {
            p.forEach { (k, v) -> if (k !in SECRET_KEYS) put(k, stripSecrets(v)) }
            val keys = id?.let { plain[it] }.orEmpty()
            if (keys.isNotEmpty()) {
                put("apiKey", keys.first())
                if (keys.size > 1) putJsonArray("apiKeyPool") { keys.drop(1).forEach { add(it) } }
            }
        }
    }

    // ── 读回 manifest（恢复侧与 UI 校验共用）─────────────────────────

    data class Manifest(
        val formatVersion: Int,
        val packageName: String,
        val appVersionName: String,
        val createdAt: Long,
        val keysIncluded: Boolean,
        val scopes: List<String>,
        val items: List<Item>
    )

    fun readManifest(zipEntryText: String): Manifest? = runCatching {
        val o = HaoJson.json.parseToJsonElement(zipEntryText) as? JsonObject ?: return@runCatching null
        Manifest(
            formatVersion = (o["formatVersion"] as? JsonPrimitive)?.intOrNull() ?: 0,
            packageName = (o["packageName"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            appVersionName = (o["appVersionName"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            createdAt = (o["createdAt"] as? JsonPrimitive)?.long ?: 0L,
            keysIncluded = (o["keysIncluded"] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull() ?: false,
            scopes = (o["scopes"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty(),
            items = (o["items"] as? JsonArray)?.mapNotNull { it as? JsonObject }?.map {
                Item(
                    (it["path"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                    (it["size"] as? JsonPrimitive)?.long ?: 0L,
                    (it["sha256"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                )
            }.orEmpty()
        )
    }.getOrNull()

    private fun JsonPrimitive.intOrNull(): Int? = contentOrNull?.toIntOrNull()

    // ── 恢复 ────────────────────────────────────────────────────────

    /** 一次恢复的结果统计。[keysReEncrypted] 是用本机 Keystore 重新加密入库的明文 Key 数。 */
    data class RestoreSummary(val scopes: Set<BackupScope>, val entries: Int, val keysReEncrypted: Int)

    private fun newRestoreTmp(c: AppContainer): File =
        File(c.appContext.cacheDir, "restore-" + System.currentTimeMillis())

    /**
     * 只读校验：解包到缓存目录并按 manifest 逐条验 sha256，不碰任何在线数据。
     * UI 先用返回的 manifest 展示包信息，用户确认范围后再调 [restore]。
     */
    fun inspect(c: AppContainer, uri: Uri): Result<Manifest> = runCatching {
        val tmp = newRestoreTmp(c)
        try {
            extractAndValidate(c, uri, tmp)
        } finally {
            tmp.deleteRecursively()
        }
    }

    /**
     * 应用备份包：解包校验全部通过后才逐域落盘——校验不过就整包拒绝，绝不写一半。
     * 恢复完成后必须重启应用：会话/记忆/配置桥的内存缓存与定时器都要重载。
     *
     * ## M5 修复（2026-10-10）：多域之间补上回滚
     *
     * 原实现逐域顺序直写（SETTINGS → SESSIONS → MEMORY → SKILLS → TASKS → EXTRAS），
     * 任何一域抛异常都被外层`runCatching` 兜成`Result.failure`，但**前面已落盘的域
     * 一个都不会撤销**。用户看到的只是"恢复失败"，实际机器上却是半新半旧：
     * 配置是备份包里的、记忆是备份包里的、会话还是上周的 —— 这种混合状态比
     * 明确失败更难排查，且用户很可能在不知情的情况下继续用。
     *
     * 改成两阶段：[RestoreTx] 在每次覆盖前把目标文件原样备份到临时目录，
     * 全部成功才commit（删备份）；任一域抛错则rollback（逐个还原，原本不存在的
     * 文件删掉），把机器恢复到恢复前的样子。
     *
     * 成本可接受：恢复是用户手动触发的低频操作，且备份包解压本来就要全量落盘，
     * 多一份同量级的临时拷贝不改变量级。
     */
    fun restore(c: AppContainer, uri: Uri, scopes: Set<BackupScope>): Result<RestoreSummary> = runCatching {
        val tmp = newRestoreTmp(c)
        try {
            val manifest = extractAndValidate(c, uri, tmp)
            // 按 name 求交集而不是 intersect：manifest.scopes 是 List<String>，
            // 直接 intersect 会把泛型推到 BackupScope&String 的公共父类型上
            val applied = scopes.filter { it.name in manifest.scopes }.toSet()
            require(applied.isNotEmpty()) { "所选范围在这份备份包里都不存在" }
            var keys = 0
            val tx = RestoreTx(File(tmp, "_rollback"))
            try {
                if (BackupScope.SETTINGS in applied) keys += restoreSettings(c, tmp, manifest.keysIncluded, tx)
                if (BackupScope.SESSIONS in applied)
                    copyTree(File(tmp, "payload/sessions"), c.sessionStore.sessionsDir(), tx)
                if (BackupScope.MEMORY in applied) restoreMemory(c, tmp, tx)
                if (BackupScope.SKILLS in applied) {
                    val root = runCatching { SkillStore.currentDir() }.getOrNull()
                    if (root != null && (root.isDirectory || root.mkdirs()))
                        copyTree(File(tmp, "payload/skills"), root, tx)
                }
                if (BackupScope.TASKS in applied) {
                    copyTree(File(tmp, "payload/todos"), File(c.appFilesDir, "todos"), tx)
                    copyTree(File(tmp, "payload/usage"), File(c.appFilesDir, "usage"), tx)
                }
                if (BackupScope.EXTRAS in applied) {
                    copyTree(File(tmp, "payload/extras/workflows"), File(c.appFilesDir, "workflows"), tx)
                    copyFile(File(tmp, "payload/extras/schedules.json"), File(c.appFilesDir, "schedules.json"), tx)
                    copyFile(
                        File(tmp, "payload/extras/wallpaper.img"),
                        WallpaperStore.storedFile(c.appContext),
                        tx
                    )
                }
                tx.commit()
            } catch (t: Throwable) {
                tx.rollback(t)
                throw t
            }
            RestoreSummary(applied, manifest.items.size, keys)
        } finally {
            tmp.deleteRecursively()
        }
    }

    /**
     * M5：恢复事务。所有覆盖写都在 [record] 留一份原样备份，
     * [rollback] 逆序还原（目标原本不存在则删除该文件），[commit] 丢弃备份。
     *
     * 只记**文件**不记目录：目录本身没有内容，回滚后可能残留空目录，
     * 但不会有错误的文件留在里面 —— 空目录无害，而记录目录会引入
     * "本来就有别的文件在同目录"的判断歧义。
     */
    internal class RestoreTx(private val backupDir: File) {
        /** dst绝对路径 -> 该文件在 backupDir 里的备份副本；value 为 null 表示"恢复前不存在"。 */
        private val undo = LinkedHashMap<String, File?>()

        fun record(dst: File) {
            val key = dst.absolutePath
            // 同一文件可能被多个域写（如 sessions 与 extras 交集），只记第一次
            if (undo.containsKey(key)) return
            undo[key] = if (dst.isFile) {
                backupDir.resolve(undo.size.toString()).also { it.parentFile?.mkdirs() }
                    .also { dst.copyTo(it, overwrite = true) }
            } else null
        }

        fun commit() {
            undo.clear()
            backupDir.deleteRecursively()
        }

        fun rollback(cause: Throwable) {
            var restored = 0
            var failed = 0
            // 逆序还原：后面的域先回滚，状态最接近出错时刻
            for ((dst, backup) in undo.entries.reversed()) {
                runCatching {
                    val target = File(dst)
                    if (backup == null) {
                        if (target.exists()) target.delete()
                    } else {
                        target.parentFile?.mkdirs()
                        backup.copyTo(target, overwrite = true)
                    }
                    restored++
                }.onFailure { failed++ }
            }
            undo.clear()
            backupDir.deleteRecursively()
            val detail = if (failed == 0) "已全部还原（$restored 个文件）"
            else "还原失败 $failed 个文件（其余 $restored 个已还原）"
            android.util.Log.e("HaoBackup", "恢复失败已回滚：$detail；原始错误：${cause.message}", cause)
        }
    }

    private fun extractAndValidate(c: AppContainer, uri: Uri, tmp: File): Manifest {
        val ins = c.appContext.contentResolver.openInputStream(uri) ?: error("无法打开备份包")
        ins.use { return extractZipValidated(it, tmp, c.appContext.packageName) }
    }

    /**
     * 纯 IO 的解包校验核心（JVM 可测）：manifest 的每条 size+sha256 都要对得上真实字节，
     * 条目集合也要与 manifest 完全一致。任何不一致都在写在线数据之前抛出。
     */
    internal fun extractZipValidated(ins: InputStream, tmp: File, expectedPackage: String): Manifest {
        var manifestText: String? = null
        val digests = HashMap<String, Pair<Long, String>>()   // path -> (size, sha256)
        ZipInputStream(ins.buffered()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (e.isDirectory) continue
                val name = e.name
                if (name == MANIFEST_NAME) {
                    manifestText = zip.readBytes().decodeToString()
                    continue
                }
                require(name.startsWith(PAYLOAD)) { "备份包里混入了意外条目：$name" }
                // zip-slip 两道闸：① ".." 段一律拒绝（payload/../x 的 canonical 仍在目录内、
                // 只有 canonical 检查拦不住，导出器也从不写这种名字）；② canonical 逃逸兜底
                require(!name.contains("..")) { "备份包条目路径异常：$name" }
                val dst = tmp.resolve(name)
                require(dst.canonicalPath.startsWith(tmp.canonicalPath + File.separator)) {
                    "备份包条目路径异常：$name"
                }
                dst.parentFile?.mkdirs()
                val digest = MessageDigest.getInstance("SHA-256")
                var n = 0L
                dst.outputStream().buffered().use { out ->
                    val dis = DigestInputStream(zip, digest)
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val r = dis.read(buf)
                        if (r < 0) break
                        out.write(buf, 0, r)
                        n += r
                    }
                }
                digests[name] = n to digest.hex()
            }
        }
        val manifest = readManifest(manifestText ?: "") ?: error("备份包缺少 manifest.json 或格式不认识")
        require(manifest.formatVersion == FORMAT_VERSION) {
            "备份包格式版本不受支持：${manifest.formatVersion}（当前支持 $FORMAT_VERSION）"
        }
        require(manifest.packageName == expectedPackage) { "这是别的应用的备份包" }
        val byPath = manifest.items.associateBy { it.path }
        require(digests.keys == byPath.keys) {
            val extra = digests.keys - byPath.keys
            val missing = byPath.keys - digests.keys
            buildString {
                append("备份包与 manifest 不一致")
                if (extra.isNotEmpty()) append("；多出 $extra")
                if (missing.isNotEmpty()) append("；缺少 $missing")
            }
        }
        byPath.forEach { (path, item) ->
            val got = digests.getValue(path)
            require(got.first == item.size && got.second == item.sha256) {
                "校验失败：$path 与 manifest 记录不符"
            }
        }
        return manifest
    }

    /**
     * 落盘两份配置真源（settings.json + haoai.config.json，必须成对）。
     * manifest 声明含明文 Key 时用**本机** Keystore 重新加密——卸载/换机后 AndroidKeyStore
     * 旧密钥已不可恢复，旧密文就是死字，这正是"导出剥 Key、恢复重加密"这条契约存在的理由。
     * 做 JsonObject 手术而不是反序列化成 AppSettings：备份可能来自更新的版本，
     * 未知字段必须原样保留，交给下次启动的迁移逻辑处理。
     */
    private fun restoreSettings(c: AppContainer, tmp: File, keysIncluded: Boolean, tx: RestoreTx): Int {
        var keys = 0
        val encrypt: (String) -> String? = { plain -> runCatching { c.cipher.encrypt(plain) }.getOrNull() }
        // 三份配置真源：settings.json + haoai.config.json（必须成对）+ MCP/PC 联动（2026-10-10 补入）
        listOf(
            "payload/settings/settings.json" to File(c.appFilesDir, "settings/settings.json"),
            "payload/state/haoai.config.json" to File(c.appFilesDir, "state/haoai.config.json"),
            "payload/settings/mcp/servers.json" to File(c.appFilesDir, "mcp/servers.json"),
            "payload/settings/pc-link.json" to File(c.appFilesDir, "pc-link.json")
        ).forEach { (rel, dest) ->
            val src = File(tmp, rel)
            if (!src.isFile) return@forEach
            val text = src.readText()
            val out = if (keysIncluded) {
                val (t, n) = reEncryptConfigJson(text, encrypt)
                if (t.isEmpty()) {
                    // reEncryptConfigJson 解析失败会返回空串 = 该文件整体拒收，不落盘
                    android.util.Log.w("HaoBackup", "跳过恢复损坏的配置：${dest.name}")
                    return@forEach
                }
                keys += n; t
            } else text
            tx.record(dest)
            HaoJson.writeAtomic(dest, out)
        }
        return keys
    }

    /**
     * [inlineSecrets] 的逆操作。备份包里的密钥是明文，落盘前必须重新加密；
     * 同时把明文字段改名为密文字段，与各 Store 的读盘口径对齐：
     *  - provider：`apiKey` / `apiKeyPool` → `apiKeyCipher` / `apiKeyPoolCiphers`
     *  - MCP servers.json（顶层数组）：header 明文 → `enc:` 密文
     *  - PC 配对（pc-link.json）：顶层 `token` 明文 → `enc:v1:` 密文
     *
     * 任一密钥加密失败即整条剔除（不落明文）。返回改写后的 JSON 与重新加密的条数。
     *
     * 2026-10-10 修复：原先 `?: return text to 0` 让**解析失败时静默跳过重新加密**，
     * 于是备份包里的明文 Key 原样写进新机的 settings.json（正常路径是会加密的）。
     * 修法：解析失败一律按最坏情况处理 —— 该文件整体拒收、不落盘，
     * 宁可不恢复这份配置，也不能把明文落盘。
     */
    internal fun reEncryptConfigJson(text: String, encrypt: (String) -> String?): Pair<String, Int> {
        val root = runCatching { HaoJson.json.parseToJsonElement(text) }.getOrNull()
        if (root == null) {
            android.util.Log.w("HaoBackup", "备份内配置 JSON 损坏，已整体拒收该文件（不落盘任何内容）")
            return "" to 0
        }
        var count = 0

        // provider：apiKey / apiKeyPool 明文 → apiKeyCipher / apiKeyPoolCiphers
        fun providerRe(p: JsonElement): JsonElement {
            if (p !is JsonObject) return p
            val plain = (p["apiKey"] as? JsonPrimitive)?.contentOrNull
            val pool = (p["apiKeyPool"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
            if (plain == null && pool.isEmpty()) return p
            return buildJsonObject {
                p.forEach { (k, v) -> if (k != "apiKey" && k != "apiKeyPool") put(k, v) }
                plain?.let(encrypt)?.let { put("apiKeyCipher", it); count++ }
                val poolCiphers = pool.mapNotNull(encrypt)
                if (poolCiphers.isNotEmpty()) {
                    putJsonArray("apiKeyPoolCiphers") { poolCiphers.forEach { add(it) } }
                    count += poolCiphers.size
                }
            }
        }

        // PC 配对（pc-link.json）：token 明文 → enc:v1: 密文。
        // encrypt 失败就返回 null —— 调用方把 token 字段整条剔除（PcStore 解不开时
        // 本来就当"没配对"），宁可不配，也绝不把明文 token 写进落盘文件。
        fun pcTokenSealed(el: JsonObject): String? {
            val plain = (el["token"] as? JsonPrimitive)?.contentOrNull
            if (plain.isNullOrEmpty()) return null
            val cipherText = encrypt(plain) ?: return null
            count++
            return PC_ENC_PREFIX + cipherText
        }

        // MCP servers.json 的元素形如 {id, headers:{...}}：header 明文 → enc: 密文。
        // 前缀与 McpServerStore.ENC_PREFIX 一致，恢复后 load() 能直接识别并解密。
        fun mcpServerRe(item: JsonElement): JsonElement {
            if (item !is JsonObject) return item
            val headers = item["headers"] as? JsonObject ?: return item
            if (headers.isEmpty()) return item
            val sealed = buildJsonObject {
                headers.forEach { (hName, hVal) ->
                    val plain = (hVal as? JsonPrimitive)?.contentOrNull ?: return@forEach
                    // 加密失败就不带这个 header，绝不落明文
                    val ct = encrypt(plain) ?: return@forEach
                    put(hName, MCP_ENC_PREFIX + ct); count++
                }
            }
            if (sealed.isEmpty()) return item
            return buildJsonObject {
                item.forEach { (k, v) -> if (k != "headers") put(k, v) }
                put("headers", sealed)
            }
        }

        fun walk(el: JsonElement): JsonElement = when (el) {
            // pc-link.json 的顶层就是那个对象本身（token 与 base 同时在场即可判定）。
            // 它的 token 是顶层字段而非嵌套字段，且加密失败时要整条剔除而非留空，
            // 所以单独走一支，不混进下面的 providers/searchApiKeys 通用分支。
            is JsonObject ->
                if (el["token"] != null && el["base"] != null) {
                    buildJsonObject {
                        el.forEach { (k, v) -> if (k != "token") put(k, v) }
                        pcTokenSealed(el)?.let { put("token", it) }
                    }
                } else {
                    buildJsonObject {
                        el.forEach { (k, v) ->
                            when {
                                k == "providers" && v is JsonArray ->
                                    putJsonArray("providers") { v.forEach { add(providerRe(it)) } }
                                k == "searchApiKeys" && v is JsonObject -> {
                                    val out = buildJsonObject {
                                        v.forEach { (backend, key) ->
                                            val plain = (key as? JsonPrimitive)?.contentOrNull ?: return@forEach
                                            val cipherText = encrypt(plain) ?: return@forEach
                                            put(backend, cipherText); count++
                                        }
                                    }
                                    if (out.isNotEmpty()) put("searchApiKeyCiphers", out)
                                }
                                else -> put(k, walk(v))
                            }
                        }
                    }
                }
            // MCP servers.json 顶层是数组（List<McpServerConfig>），元素带 headers 才改写
            is JsonArray -> buildJsonArray {
                el.forEach { item ->
                    if (item is JsonObject && item["headers"] is JsonObject) add(mcpServerRe(item))
                    else add(walk(item))
                }
            }
            else -> el
        }
        return HaoJson.json.encodeToString(JsonElement.serializer(), walk(root)) to count
    }

    private fun restoreMemory(c: AppContainer, tmp: File, tx: RestoreTx) {
        copyFile(File(tmp, "payload/memory/MEMORY.md"), c.memoryBank.storageFile(), tx)
        runCatching { c.journal.currentStorageDir() }.getOrNull()?.let {
            copyTree(File(tmp, "payload/memory/journal"), it, tx)
        }
        WorkspaceDocs.workspaceRoot(c)?.let { root ->
            listOf("USER.md", "DREAMS.md").forEach { n ->
                copyFile(File(tmp, "payload/workspace/$n"), root.resolve(n), tx)
            }
            copyTree(File(tmp, "payload/workspace/dreaming"), root.resolve("dreaming"), tx)
        }
    }

    /** 单文件还原：源不存在就跳过（一个范围在包里可能只有部分文件），目标父目录自动建。 */
    private fun copyFile(src: File, dst: File, tx: RestoreTx? = null) {
        if (!src.isFile) return
        dst.parentFile?.mkdirs()
        tx?.record(dst)
        src.copyTo(dst, overwrite = true)
    }

    /** 目录级还原：src 不存在 = 该域在这份包里没有内容，静默跳过。 */
    private fun copyTree(src: File, dst: File, tx: RestoreTx? = null) {
        if (!src.isDirectory) return
        src.walkTopDown().filter { it.isFile }.forEach { f ->
            val target = dst.resolve(f.relativeTo(src).path)
            target.parentFile?.mkdirs()
            tx?.record(target)
            f.copyTo(target, overwrite = true)
        }
    }
}
