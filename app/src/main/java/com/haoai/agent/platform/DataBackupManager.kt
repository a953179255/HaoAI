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
import java.util.zip.ZipOutputStream

/** 备份范围。每一项都是"能独立看懂的一堆文件"，不做半个域的拆分（恢复要么整包要么不恢复）。 */
enum class BackupScope(val label: String, val hint: String) {
    SETTINGS("设置与供应商", "模型服务、协议、上下文长度、权限模式、思考等级"),
    SESSIONS("会话记录", "全部对话与工具轨迹，含压缩摘要与水位"),
    MEMORY("记忆与日志", "MEMORY.md、每日日志、固化报告"),
    SKILLS("技能", "工作区 skills 目录（含候选态与启用状态）"),
    TASKS("待办与用量账本", "各会话任务清单、按月 token 账本")
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
        }).filter { it.second.isFile }

    private fun File.listByExt(vararg ext: String): List<File> =
        listFiles { f -> f.isFile && f.extension in ext }?.sorted().orEmpty()

    private fun File.listByPrefix(prefix: String): List<File> =
        listFiles { f -> f.isFile && f.name.startsWith(prefix) }?.sorted().orEmpty()

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
        val bySearch = s.searchApiKeyCiphers.mapNotNull { (backend, cipher) ->
            val plain = c.cipher.decrypt(cipher)
            if (plain.isBlank()) null else SEARCH_KEY_PREFIX + backend to listOf(plain)
        }
        return (byProvider + bySearch).toMap()
    }

    /**
     * 重写 JSON 里的密钥：[plain] 为空就把密钥字段全部剔除；非空则在每个 provider 上
     * 写 `apiKey`/`apiKeyPool` 明文并移除密文字段。递归处理，配置桥那份额外形状也吃得下。
     */
    internal fun transformSecrets(text: String, plain: Map<String, List<String>>): String {
        val root = runCatching {
            HaoJson.json.parseToJsonElement(text)
        }.getOrNull() ?: return text      // 解析不动就整段原样带上（宁可多留一份也不静默丢数据）
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
        is JsonArray -> buildJsonArray { el.forEach { add(inlineSecrets(it, plain)) } }
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
}
