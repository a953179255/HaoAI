package com.haoai.pc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 全量数据备份（导出 + 恢复）。
 *
 * 包结构与**手机端 `DataBackupManager` 对齐**：zip 里一份 `manifest.json` 加
 * `payload/<范围>/<相对路径>`；manifest 记格式版本、包名、时间、勾选的范围、
 * 是否含明文 Key，以及每一条 payload 的大小与 sha256。
 * 恢复侧据此逐条校验，**任一条不匹配就整包拒绝** —— 不做"跳过坏文件继续"：
 * 那样恢复出来的是一半新的一半旧的会话库，比恢复失败更难排查。
 *
 * 为什么是 zip 不是目录：一份文件才好发给另一台机器/另一个端，
 * 而且"包里有没有被改过"是可校验的（目录形态没有边界）。
 *
 * 密钥的口径跟手机端一样：`apikey` / `searchkey` 默认**不进包**，
 * 只有显式勾选"包含明文 Key"才写进去（manifest 的 `keysIncluded` 里说清楚，
 * 别让两边各猜一套）。PC 端的 Key 是裸文件，所以这里要**主动剔**，不是"不写就没事"。
 */
object Backup {

    const val FORMAT_VERSION = 1
    const val KIND = "haoai-pc"
    const val MANIFEST_NAME = "manifest.json"
    const val DIR_NAME = "backups"

    /** 一包能独立看懂的一堆文件；恢复要么整包要么不恢复。 */
    val SCOPES = listOf("settings", "sessions", "memory", "skills", "tasks")

    private val SECRET_NAMES = setOf("apikey", "searchkey")

    data class Entry(val path: String, val size: Long, val sha256: String)

    data class Summary(
        val name: String, val entries: Int, val bytes: Long,
        val scopes: List<String>, val keysIncluded: Boolean
    )

    /** 列表里给界面用的一条备份：能看时间、范围、大小，以及是不是恢复前自动留的那张。 */
    data class Item(
        val name: String, val at: String, val bytes: Long,
        val entries: Int, val scopes: List<String>, val keysIncluded: Boolean, val reason: String
    )

    private fun backupsDir(): File = File(Env.home, DIR_NAME).apply { mkdirs() }

    private fun sha256(f: File): String {
        val d = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                d.update(buf, 0, n)
            }
        }
        return d.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256(b: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    /** 某个范围在状态根下对应哪些文件；目录（会话）整目录进来。 */
    private fun scopeFiles(scope: String): List<File> = when (scope) {
        "settings" -> listOfNotNull(
            Env.settingsFile, Env.rulesFile, Env.approvalsFile,
            File(Env.home, "mcp.json"), File(Env.home, "browser.json")
        )
        "sessions" -> listOf(Env.sessionsDir)
        "memory" -> listOf(Env.memoryFile)
        "skills" -> listOf(Env.skillsFile)
        "tasks" -> listOf(File(Env.home, "schedules.json"), File(Env.home, "usage.jsonl"))
        else -> emptyList()
    }

    /** 展开成"包内路径 -> 文件"。相对路径一律用 `/`，zip 里没有反斜杠。 */
    private fun expand(scope: String, withKeys: Boolean): List<Pair<String, File>> {
        val out = mutableListOf<Pair<String, File>>()
        for (f in scopeFiles(scope)) {
            if (!f.exists()) continue
            if (f.isDirectory) {
                f.walkTopDown().filter { it.isFile }.forEach { file ->
                    val rel = file.relativeTo(f).invariantSeparatorsPath
                    out += "payload/$scope/$rel" to file
                }
            } else {
                out += "payload/$scope/${f.name}" to f
            }
        }
        if (withKeys && scope == "settings") {
            for (n in SECRET_NAMES) {
                val k = File(Env.home, n)
                if (k.isFile) out += "payload/$scope/$n" to k
            }
        }
        return out
    }

    /**
     * 导出到 `HAOAI_HOME/backups/<包名>.zip`。
     *
     * 会话正在跑时**拒绝导出**（调用方 [Server] 负责查 running）：
     * 引擎每轮都在 `persist()`，zip 读到一半被改写就得到一个"看着完整其实缺半轮"的包。
     */
    @Synchronized
    fun export(scopes: List<String>, withKeys: Boolean, name: String? = null): Summary {
        val use = (scopes.ifEmpty { SCOPES }).filter { it in SCOPES }.distinct()
        val at = SimpleDateFormat("yyyyMMdd-HHmmss").format(Date())
        var packName = name ?: "haoai-pc-$at.zip"
        var dest = File(backupsDir(), packName)
        // 同一秒里导出两次会拿到同一个名字 → 后一个覆盖前一个（用户点了两次就丢一份）。
        var dup = 2
        while (name == null && dest.exists()) {
            packName = "haoai-pc-$at-${dup++}.zip"
            dest = File(backupsDir(), packName)
        }
        val entries = mutableListOf<Entry>()
        ZipOutputStream(dest.outputStream().buffered()).use { z ->
            for (scope in use) {
                for ((zipPath, file) in expand(scope, withKeys)) {
                    val bytes = file.readBytes()
                    z.putNextEntry(ZipEntry(zipPath))
                    z.write(bytes)
                    z.closeEntry()
                    entries += Entry(zipPath, bytes.size.toLong(), sha256(bytes))
                }
            }
            val manifest = buildJsonObject {
                put("format", FORMAT_VERSION)
                put("kind", KIND)
                put("name", packName)
                put("at", at)
                put("app", PC_VERSION)
                put("keysIncluded", withKeys)
                put("scopes", buildJsonArray { use.forEach { add(it) } })
                put("entries", buildJsonArray {
                    entries.forEach { e ->
                        addJsonObject {
                            put("path", e.path); put("size", e.size); put("sha256", e.sha256)
                        }
                    }
                })
            }.toString()
            z.putNextEntry(ZipEntry(MANIFEST_NAME))
            z.write(manifest.toByteArray(Charsets.UTF_8))
            z.closeEntry()
        }
        return Summary(packName, entries.size, dest.length(), use, withKeys)
    }

    /** 读包里的 manifest；坏包返回 null（列表里就当它不存在，不当成"能恢复"）。 */
    fun readManifest(zip: File): JsonObject? = runCatching {
        var found: JsonObject? = null
        ZipInputStream(zip.inputStream().buffered()).use { z ->
            while (found == null) {
                val e = z.nextEntry ?: break
                if (e.name == MANIFEST_NAME) {
                    found = Json.parseToJsonElement(z.readBytes().toString(Charsets.UTF_8)).jsonObject
                }
            }
        }
        found
    }.getOrNull()

    fun list(): List<Item> = (backupsDir().listFiles { f -> f.isFile && f.name.endsWith(".zip") }
        ?: emptyArray())
        .mapNotNull { f ->
            val m = readManifest(f) ?: return@mapNotNull null
            val at = m["at"]?.jsonPrimitive?.contentOrNull ?: f.name
            val reason = m["reason"]?.jsonPrimitive?.contentOrNull ?: ""
            Item(
                name = f.name, at = at, bytes = f.length(),
                entries = m["entries"]?.jsonArray?.size ?: 0,
                scopes = m["scopes"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList(),
                keysIncluded = m["keysIncluded"]?.jsonPrimitive?.contentOrNull == "true",
                reason = reason
            )
        }.sortedByDescending { it.at }

    /**
     * 恢复一包：先把所有 payload 抽到临时目录并**逐条校验**，全部通过才写回状态根。
     *
     * 中途发现任何一条对不上（大小或 sha256），或者路径想跑出状态根（`../`、绝对路径），
     * 直接整包拒绝 —— 此时状态根一个字节都没动过。
     *
     * 写回之前会先用 [export] 给当前状态留一张 `pre-restore-*.zip`：
     * 恢复本身也是破坏性的（会覆盖现在的会话），"后悔药"必须自己备好，不能靠用户记得手动备份。
     */
    @Synchronized
    fun restore(packName: String): String {
        val zip = File(backupsDir(), packName)
        if (!zip.isFile) return "没有这个备份包：$packName"
        val manifest = readManifest(zip) ?: return "这个包没有可读的 manifest.json，拒绝恢复"
        val fmt = manifest["format"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
        if (fmt == null || fmt > FORMAT_VERSION)
            return "包的格式版本 ${fmt ?: "?"} 比当前支持的 $FORMAT_VERSION 新，拒绝恢复（先升级 PC 端）"
        if (manifest["kind"]?.jsonPrimitive?.contentOrNull?.takeIf { it != KIND } != null)
            return "这是 ${manifest["kind"]} 的备份包，不是 PC 端的，拒绝恢复"

        val wanted = manifest["entries"]?.jsonArray?.mapNotNull { el ->
            val o = el.jsonObject
            val path = o["path"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            Entry(path, o["size"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: -1,
                o["sha256"]?.jsonPrimitive?.contentOrNull ?: "")
        } ?: return "manifest 里没有 entries，拒绝恢复"
        if (wanted.isEmpty()) return "包是空的（0 条），拒绝恢复"

        val root = Env.home.canonicalFile
        val tmp = File(Env.home, ".restore-tmp").apply { deleteRecursively(); mkdirs() }
        try {
            val byPath = wanted.associateBy { it.path }
            val seen = mutableSetOf<String>()
            ZipInputStream(zip.inputStream().buffered()).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    if (e.isDirectory || e.name == MANIFEST_NAME) continue
                    val meta = byPath[e.name] ?: return "包里有 manifest 没写的条目：${e.name}，拒绝恢复"
                    // 路径安全：先归一，再确认落点仍在状态根（或临时目录）内
                    val target = File(tmp, e.name.removePrefix("payload/")).canonicalFile
                    if (!target.path.startsWith(tmp.canonicalPath)) return "路径想跑出状态根：${e.name}，拒绝恢复"
                    target.parentFile?.mkdirs()
                    val bytes = z.readBytes()
                    if (bytes.size.toLong() != meta.size)
                        return "第 ${wanted.indexOf(meta) + 1} 条大小对不上（${e.name}：登记 ${meta.size}，实际 ${bytes.size}），整包拒绝"
                    if (sha256(bytes) != meta.sha256)
                        return "第 ${wanted.indexOf(meta) + 1} 条 sha256 对不上（${e.name}），整包拒绝 —— 包被人改过或传输损坏"
                    target.writeBytes(bytes)
                    seen += e.name
                }
            }
            val missing = wanted.map { it.path } - seen
            if (missing.isNotEmpty())
                return "包里缺 ${missing.size} 条（第一个是 ${missing.first()}），整包拒绝"

            // 校验全过，先给当前状态留后悔药，再写回
            val safety = export(SCOPES, withKeys = true, name = "pre-restore-" +
                SimpleDateFormat("yyyyMMdd-HHmmss").format(Date()) + ".zip")
            for ((path, meta) in wanted) {
                val rel = path.removePrefix("payload/")
                val scope = rel.substringBefore('/')
                val rest = rel.substringAfter('/', "")
                if (rest.isEmpty()) continue
                val src = File(tmp, "$scope/$rest")
                val dst = when (scope) {
                    "sessions" -> File(Env.sessionsDir, rest)
                    else -> File(Env.home, rest)
                }
                if (!dst.canonicalFile.path.startsWith(root.path)) continue
                dst.parentFile?.mkdirs()
                src.copyTo(dst, overwrite = true)
            }
            return "ok：恢复 ${wanted.size} 条（范围 ${manifest["scopes"]}，当前状态已另存为 ${safety.name}）"
        } finally {
            tmp.deleteRecursively()
        }
    }

    /** 删一个备份包（回收站之外的主动删除，界面上要二次确认）。 */
    fun delete(packName: String): String {
        if (packName.startsWith("pre-restore-")) return "恢复前自动留的包不能删（后悔药）"
        val f = File(backupsDir(), packName)
        if (!f.isFile) return "没有这个备份包"
        return if (f.delete()) "ok" else "删不掉（文件被占用？）"
    }
}
