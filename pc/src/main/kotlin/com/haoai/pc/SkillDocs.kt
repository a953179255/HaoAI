package com.haoai.pc

import java.io.ByteArrayInputStream
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.zip.ZipInputStream

/**
 * 技能目录：一份 `SKILL.md` = 一个技能，装在 `HAOAI_HOME/skills/<slug>/SKILL.md`。
 *
 * 为什么照手机端那个形状：`app/.../skills/SkillStore.kt` 早就定了
 * `<slug>/SKILL.md` + YAML 头里 `name` / `description`（agentskills.io 那套），
 * 还带 URL/zip 导入。两端各定一套格式，用户就得记两遍"技能长什么样" ——
 * 而技能正文往往是网上抄来的，只有一种形状能通用。
 *
 * 这一版的边界（说清楚，免得以为已经做完了）：
 * 技能是**用户用 `/` 唤起**的，不注入系统提示、也不给模型一把 `skill` 工具去自选。
 * 那两样要一起搬才有意义（手机端是 `promptIndex()` + `SkillTool` 成对做的），
 * 单做其一会出现"模型知道有这份技能却读不到正文"的半截状态。
 */
data class SkillDoc(
    val slug: String,
    val name: String,
    val desc: String,
    val body: String,
    val file: File
)

/** 一次导入的账：进了哪几份、跳过了哪几份、为什么。 */
data class ImportReport(
    val added: List<String> = emptyList(),
    val skipped: List<String> = emptyList(),
    val error: String = ""
) {
    val ok get() = error.isEmpty() && added.isNotEmpty()
}

object SkillDocs {

    /** 单份正文上限：技能是"一段说明书"，超过这个数就该拆或者别装。 */
    const val MAX_BYTES = 2_000_000
    const val MAX_DOCS = 60
    const val MAX_ENTRIES = 500

    fun dir(): File = File(Env.home, "skills").apply { mkdirs() }

    /**
     * 目录名只留字母（含中文）、数字与 `-_.`。
     *
     * 这是 zip 导入的**唯一**防线：压缩包里的条目名是外部输入，
     * `../../evil/SKILL.md` 只要漏过一个字符，装技能就能变成往状态根外面写文件。
     * 留中文是因为技能名常常就是中文 —— 全折成 `-` 的话"剪片子 v2"会变成 "v2"，
     * 列表上就没人认得出这是哪一份。
     */
    fun sanitize(raw: String): String {
        val s = raw.trim().lowercase()
            .replace(Regex("""[\\/]"""), "-")
            .replace(Regex("""[^\p{L}\p{N}._\-]"""), "-")
            .replace(Regex("""-+"""), "-")
            .trim('-', '.')
        return s.take(40).ifBlank { "skill" }
    }

    /** YAML 头只认两样：`name` 与 `description`。没有头就整篇当正文。 */
    fun parse(text: String): Triple<String, String, String> {
        val t = text.replace("\r\n", "\n").replace("\r", "\n")
        val lines = t.split('\n')
        if (lines.firstOrNull()?.trim() != "---") return Triple("", "", t.trim())
        var name = ""
        var desc = ""
        var i = 1
        while (i < lines.size && lines[i].trim() != "---") {
            val l = lines[i]
            val key = l.substringBefore(':', "").trim().lowercase()
            val val0 = l.substringAfter(':', "").trim().removeSurrounding("\"")
            when (key) {
                "name" -> name = val0
                "description" -> desc = val0
            }
            i++
        }
        val body = lines.drop(minOf(i + 1, lines.size)).joinToString("\n").trim()
        return Triple(name, desc, body)
    }

    fun list(): List<SkillDoc> = runCatching {
        val out = dir().listFiles { f -> f.isDirectory }?.sortedBy { it.name } ?: emptyList()
        out.mapNotNull { d ->
            val f = File(d, "SKILL.md")
            if (!f.isFile) return@mapNotNull null
            val text = runCatching { f.readText(Charsets.UTF_8) }.getOrDefault("")
            val (name, desc, body) = parse(text)
            SkillDoc(d.name, name.ifBlank { d.name }, desc, body, f)
        }
    }.getOrDefault(emptyList())

    fun find(slug: String): SkillDoc? = list().firstOrNull { it.slug == slug }

    /** 撞名就往后缀 `-2`、`-3`：导入一批时"覆盖上一个"是最难发现的丢数据方式。 */
    private fun freeSlug(base: String): String {
        var s = base
        var n = 2
        while (File(dir(), s).exists()) { s = "$base-${n++}"; if (n > 99) break }
        return s
    }

    /** 存一份新技能。返回 null = 没存（空正文 / 目录满了 / 写不进去）。 */
    fun saveDoc(rawName: String, text: String): SkillDoc? {
        if (text.isBlank()) return null
        if (text.length > MAX_BYTES) return null
        if (list().size >= MAX_DOCS) return null
        val (fmName, desc, body) = parse(text)
        val name = fmName.ifBlank { rawName.trim() }
        val slug = freeSlug(sanitize(name.ifBlank { "skill" }))
        val d = File(dir(), slug)
        return runCatching {
            d.mkdirs()
            File(d, "SKILL.md").writeText(text, Charsets.UTF_8)
            SkillDoc(slug, name.ifBlank { slug }, desc, body, File(d, "SKILL.md"))
        }.getOrNull()
    }

    fun remove(slug: String): Boolean {
        val d = File(dir(), sanitize(slug))
        if (!d.isDirectory || !File(d, "SKILL.md").isFile) return false
        return runCatching { d.deleteRecursively() && !d.exists() }.getOrDefault(false)
    }

    /** 从一段 markdown 导入（界面上"粘贴 SKILL.md"走这条）。 */
    fun importText(text: String): ImportReport {
        if (text.isBlank()) return ImportReport(error = "内容是空的")
        val d = saveDoc("", text) ?: return ImportReport(error = "没存进去：正文为空、超过 ${MAX_BYTES / 1000} KB，或技能目录已满 $MAX_DOCS 份")
        return ImportReport(added = listOf(d.slug))
    }

    /**
     * 从 zip 字节导入：只认条目名以 `SKILL.md` 结尾的那些。
     *
     * 三道闸：条目数、单份大小、**目录名 sanitize**（zip 里的路径是外部输入）。
     * 被拒的条目要出现在 skipped 里 —— 静默丢掉一份技能，用户只会以为"没导入成功"。
     */
    fun importZip(bytes: ByteArray): ImportReport {
        if (bytes.size < 4 || bytes[0] != 'P'.code.toByte() || bytes[1] != 'K'.code.toByte())
            return ImportReport(error = "这不是 zip（开头不是 PK）")
        val added = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        var entries = 0
        runCatching {
            ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    entries++
                    if (entries > MAX_ENTRIES) { skipped += "条目超过 $MAX_ENTRIES 个，后面的没看"; break }
                    if (e.isDirectory) continue
                    val nm = e.name.replace('\\', '/')
                    if (!nm.endsWith("SKILL.md", true)) continue
                    if (nm.contains("..")) { skipped += "$nm：路径想跑出技能目录"; continue }
                    val raw = nm.substringBeforeLast('/', nm.substringBeforeLast('.'))
                    val buf = z.readNBytes(MAX_BYTES + 1)
                    if (buf.size > MAX_BYTES) { skipped += "$nm：超过 ${MAX_BYTES / 1000} KB"; continue }
                    val text = String(buf, Charsets.UTF_8)
                    val d = saveDoc(sanitize(raw), text)
                    if (d == null) skipped += "$nm：没存下（空、超限或目录已满）" else added += d.slug
                }
            }
        }.exceptionOrNull()?.let { return ImportReport(added, skipped, "解压失败：${it.message}") }
        if (added.isEmpty() && skipped.isEmpty())
            return ImportReport(added, skipped, "这个包里没有找到任何 SKILL.md")
        return ImportReport(added, skipped)
    }

    /**
     * 从网址导入。`.zip` 或 `PK` 开头走包，其余当一份 markdown。
     *
     * 只走 http/https，且**没有**任何"跟着 file: 跑"的余地 ——
     * 这个入口的输入来自剪贴板，谁也不知道里面粘过什么。
     */
    fun importUrl(url: String, client: HttpClient? = null): ImportReport {
        val uri = runCatching { URI.create(url.trim()) }.getOrNull()
            ?: return ImportReport(error = "这个网址读不出来")
        if (uri.scheme != "http" && uri.scheme != "https")
            return ImportReport(error = "只支持 http/https，不碰 ${uri.scheme ?: "这种"}地址")
        return runCatching {
            val c = client ?: HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL).build()
            val req = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30))
                .header("User-Agent", "HaoAI-PC/$PC_VERSION").GET().build()
            val res = c.send(req, HttpResponse.BodyHandlers.ofByteArray())
            if (res.statusCode() !in 200..299) return ImportReport(error = "网关回了 ${res.statusCode()}")
            val bytes = res.body() ?: return ImportReport(error = "带回来的是空的")
            val ct = (res.headers().firstValue("content-type").orElse("")).lowercase()
            if (ct.contains("zip") || (bytes.size > 1 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()))
                importZip(bytes)
            else importText(String(bytes, Charsets.UTF_8))
        }.getOrElse { ImportReport(error = "拉不下来：${it.message}") }
    }

    /** 给 `/api/skills` 用：技能目录里的东西，与手写的 /命令 同构（name/desc/text）。 */
    fun jsonItems(): String = list().joinToString(",") { d ->
        """{"name":${js(d.name.ifBlank { d.slug })},"desc":${js(d.desc)},""" +
            """"text":${js(d.body)},"slug":${js(d.slug)},"doc":true}"""
    }

    private fun js(s: String): String = "\"" +
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "") + "\""
}
