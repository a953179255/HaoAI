package com.haoai.agent.agent.skills

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.haoai.agent.agent.skills.SkillStore.ImportOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.zip.ZipInputStream

/**
 * 外部技能导入器（2.2）：三个通道（SAF 文件夹 / URL 直链 md|zip / 剪贴板文本）统一收集为
 * (建议名, 文本) 列表后批量导入。重名策略：批量通道自动加 -2/-3 后缀不覆盖；
 * 单个粘贴通道由 UI 弹确认决定覆盖。
 */
object SkillImporter {

    /** 批量导入汇总：imported=最终名列表（含重命名说明），failed=(来源名, 原因)。 */
    data class BatchResult(
        val imported: List<String>,
        val renamed: List<String>,
        val failed: List<Pair<String, String>>
    ) {
        val ok: Boolean get() = imported.isNotEmpty() || renamed.isNotEmpty()
        fun summary(): String = buildString {
            if (imported.isNotEmpty()) appendLine("成功导入 ${imported.size} 个：${imported.joinToString("、")}")
            if (renamed.isNotEmpty()) appendLine("重名自动改名 ${renamed.size} 个：${renamed.joinToString("、")}")
            if (failed.isNotEmpty()) appendLine("失败 ${failed.size} 个：${failed.joinToString("；") { (n, r) -> "$n（$r）" }}")
        }.trimEnd()
    }

    /** 单文件大小上限（防 zip 炸弹/超大下载）。 */
    private const val MAX_FILE_BYTES = 50L * 1024 * 1024
    private val SKILL_FILE_NAMES = setOf("skill.md")

    // ---------- 通道 1：SAF 文件夹 ----------

    /**
     * 扫描 SAF 目录树：根目录 + 一级子目录中的 SKILL.md（忽略大小写）。
     * 返回 null 表示目录不可读（用户取消了授权等）。
     */
    fun scanSafTree(context: Context, treeUri: Uri): List<Pair<String?, String>>? {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return null
        val out = mutableListOf<Pair<String?, String>>()
        val children = root.listFiles() ?: return null
        for (f in children) {
            val name = f.name ?: continue
            if (f.isFile && name.lowercase() in SKILL_FILE_NAMES) {
                readDoc(context, f)?.let { out.add(null to it) }
            } else if (f.isDirectory) {
                // 一级子目录：每个子目录中的 SKILL.md 识别为一个技能，目录名作为建议名
                f.listFiles().firstOrNull {
                    it.isFile && (it.name ?: "").lowercase() in SKILL_FILE_NAMES
                }?.let { skill ->
                    readDoc(context, skill)?.let { out.add(name to it) }
                }
            }
        }
        return out
    }

    private fun readDoc(context: Context, f: DocumentFile): String? = runCatching {
        context.contentResolver.openInputStream(f.uri)?.use { ins ->
            val bytes = ins.readBytes()
            if (bytes.size > MAX_FILE_BYTES) return null
            String(bytes, Charsets.UTF_8)
        }
    }.getOrNull()

    // ---------- 通道 2：URL 下载（.md 或 .zip 直链） ----------

    suspend fun downloadAndScan(http: OkHttpClient, url: String): Result<List<Pair<String?, String>>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val resp = http.newCall(Request.Builder().url(url.trim()).build()).execute()
                resp.use { r ->
                    if (!r.isSuccessful) throw Exception("HTTP ${r.code}")
                    val body = r.body?.bytes() ?: throw Exception("响应体为空")
                    if (body.size > MAX_FILE_BYTES) throw Exception("文件超过 50MB 上限")
                    val ct = (r.header("Content-Type") ?: "").lowercase()
                    val lower = url.substringBefore('?').lowercase()
                    when {
                        lower.endsWith(".zip") || ct.contains("zip") -> unzipToDocs(body)
                        lower.endsWith(".md") || ct.contains("text/markdown") ||
                            ct.contains("text/plain") -> listOf(null to String(body, Charsets.UTF_8))
                        else -> throw Exception("仅支持 .md 或 .zip 直链（当前 Content-Type: $ct）")
                    }
                }
            }
        }

    /** zip 解包扫描：任意层级的 SKILL.md 都识别，建议名取其直接父目录名。 */
    private fun unzipToDocs(bytes: ByteArray): List<Pair<String?, String>> {
        val out = mutableListOf<Pair<String?, String>>()
        var totalUncompressed = 0L
        var entryCount = 0
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entryCount++
                if (entryCount > 500) throw Exception("zip 内文件过多（>500），已中止")
                val path = entry.name.replace('\\', '/')
                if (!entry.isDirectory && path.substringAfterLast('/').lowercase() in SKILL_FILE_NAMES) {
                    val buf = java.io.ByteArrayOutputStream()
                    val chunk = ByteArray(8192)
                    while (true) {
                        val n = zip.read(chunk)
                        if (n < 0) break
                        totalUncompressed += n
                        if (totalUncompressed > MAX_FILE_BYTES) throw Exception("zip 解压总量超过 50MB 上限")
                        buf.write(chunk, 0, n)
                    }
                    val parts = path.trimEnd('/').split('/')
                    // 直接父目录名作为建议名；zip 根级的 SKILL.md 无父目录
                    val parent = if (parts.size >= 2) parts[parts.size - 2].takeIf { it.isNotBlank() } else null
                    out.add(parent to buf.toString("UTF-8"))
                }
                zip.closeEntry()
            }
        }
        return out
    }

    // ---------- 批量导入 ----------

    /**
     * 批量导入：重名自动加 -2/-3 后缀（不覆盖既有技能，导入物永不破坏用户已有库）。
     * docs 元素 = (建议名, 文本)。
     */
    fun importBatch(docs: List<Pair<String?, String>>, importSource: String): BatchResult {
        val imported = mutableListOf<String>()
        val renamed = mutableListOf<String>()
        val failed = mutableListOf<Pair<String, String>>()
        docs.forEachIndexed { idx, item ->
            val (suggested, text) = item
            val label = suggested ?: "文档${idx + 1}"
            when (val r = SkillStore.importDoc(text, importSource, suggestedName = suggested)) {
                is ImportOutcome.Done -> imported.add(r.name)
                is ImportOutcome.Conflict -> {
                    // 自动重命名：base-2、base-3…（nameOverride 强制改名，避免 frontmatter name 抢优先级）
                    var n = 2
                    while (SkillStore.exists("${r.name}-$n")) n++
                    val alt = "${r.name}-$n"
                    when (val r2 = SkillStore.importDoc(text, importSource, overwrite = true, nameOverride = alt)) {
                        is ImportOutcome.Done -> {
                            imported.add(r2.name)
                            renamed.add("${r.name} → ${r2.name}")
                        }
                        else -> failed.add(label to "重名且改名导入失败")
                    }
                }
                is ImportOutcome.Failed -> failed.add(label to r.message)
            }
        }
        return BatchResult(imported, renamed, failed)
    }
}
