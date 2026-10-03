package com.haoai.pc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * 知识库（对标 Octop 的 `knowledge_bases` / `knowledge_documents`）。
 *
 * ## 与工作区 `.haoai-kb/` 的分工（两套是有意留的）
 *
 * - **工作区语料**（[kb] 那个老口，v1）：项目自己的参考文档，跟着目录走、跟着备份走，
 *   语义索引本来就按工作区建，一行索引代码都不用加。
 * - **知识库**（这一层）：**跨项目**的资料 + 能绑到具体专家身上。
 *   "公司制度""剪辑规范""那本手册"不属于某一个仓库，属于"人"。
 *   混在一套里，要么项目目录被塞进无关材料，要么换项目就找不到东西。
 *
 * ## 目录形状
 *
 * `HAOAI_HOME/kb.json`（元数据）+ `HAOAI_HOME/kb/<库id>/src/<原名>`（原文）
 * + `HAOAI_HOME/kb/<库id>/txt/<原名>.md`（抽出来的正文）。
 * 正文单独落一份是必须的：docx/pptx 的原文是二进制，[SemanticIndex] 读不了；
 * 而"预览抽取结果"与"下载原文"这两个功能也要求两份都在。
 *
 * ## 两处刻意与 Octop 不一样（照抄会撒谎）
 *
 * 1. **没有 pending / processing 那两个进度态。** 它的索引是后台任务，所以有进度；
 *    我们解析是同步的（几毫秒到几百毫秒），向量索引是**第一次检索时按需建**（[SemanticIndex] 的既有行为）。
 *    界面上摆一个永远停在"就绪"的进度条，是给用户看假东西。状态只有 ready / failed，
 *    失败必须带原因。
 * 2. **没配 embedding 端点时不退化成"功能不可用"**：改用关键词扫正文。
 *    Octop 是"没模型就整个关掉"，但"能不能查到东西"和"用语义还是用字面"是两件事。
 */
object Knowledge {

    const val MAX_BASES = 20
    const val MAX_DOCS = 100
    /** 原文上限：docx/pptx 里常夹图，比纯文本宽，但也不能无界。 */
    const val MAX_RAW_BYTES = 8_000_000
    /** 抽出来的正文上限：与 [SemanticIndex] 的每文件上限同口径，再大它也会丢。 */
    const val MAX_TEXT_CHARS = 200_000

    const val READY = "ready"
    const val FAILED = "failed"

    /** 纯文本类：直接按 UTF-8 读。 */
    private val TEXT_EXT = setOf("md", "txt", "csv", "tsv", "json", "yml", "yaml", "toml", "log", "rst")
    /** 要解析的二进制类。 */
    private val OOXML_EXT = setOf("docx", "xlsx", "xlsm", "pptx")
    private val WEB_EXT = setOf("html", "htm", "xml")

    data class Doc(
        val name: String,
        val kind: String,
        val bytes: Int,
        val chars: Int,
        val chunks: Int,
        val hash: String,
        val status: String,
        val error: String = "",
        val added: Long = System.currentTimeMillis(),
        val updated: Long = System.currentTimeMillis()
    )

    data class Base(
        val id: String,
        val name: String,
        val desc: String = "",
        /** 每回合都自动带上这个库（不用在专家卡上一个个勾）。 */
        val defaultOpen: Boolean = false,
        val created: Long = System.currentTimeMillis(),
        val docs: List<Doc> = emptyList()
    )

    fun file(): File = File(Env.home, "kb.json")
    fun root(): File = File(Env.home, "kb")
    fun dir(id: String): File = File(root(), id)
    fun srcDir(id: String): File = File(dir(id), "src")
    fun txtDir(id: String): File = File(dir(id), "txt")

    /**
     * 抽出来的正文落哪：`txt/<原名>.txt`。
     *
     * 加 `.txt` 而不是复用原扩展名，是为了让"这是抽出来的正文、不是原文"写在文件名上：
     * 索引器只该看 `txt/` 那份（二进制在那儿才有可读文本），而"预览抽取结果"与
     * "下载原文"是两个功能，两份文件必须分得清。
     */
    fun txtFile(id: String, name: String): File = File(txtDir(id), "$name.txt")

    // ---- 存储 ----

    fun load(): MutableList<Base> = runCatching {
        val root = Json.parseToJsonElement(file().readText())
        val arr = if (root is JsonArray) root else JsonArray(emptyList())
        arr.mapNotNull { el ->
            runCatching {
                val o = el.jsonObject
                val id = o["id"]?.jsonPrimitive?.contentOrNull ?: return@runCatching null
                Base(
                    id = id,
                    name = o["name"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { id },
                    desc = o["desc"]?.jsonPrimitive?.contentOrNull ?: "",
                    defaultOpen = o["defaultOpen"]?.jsonPrimitive?.contentOrNull == "true",
                    created = o["created"]?.jsonPrimitive?.longOrNull ?: System.currentTimeMillis(),
                    docs = o["docs"]?.jsonArray?.mapNotNull { d ->
                        runCatching {
                            val x = d.jsonObject
                            Doc(
                                name = x["name"]?.jsonPrimitive?.contentOrNull ?: return@runCatching null,
                                kind = x["kind"]?.jsonPrimitive?.contentOrNull ?: "",
                                bytes = x["bytes"]?.jsonPrimitive?.intOrNull ?: 0,
                                chars = x["chars"]?.jsonPrimitive?.intOrNull ?: 0,
                                chunks = x["chunks"]?.jsonPrimitive?.intOrNull ?: 0,
                                hash = x["hash"]?.jsonPrimitive?.contentOrNull ?: "",
                                status = x["status"]?.jsonPrimitive?.contentOrNull ?: READY,
                                error = x["error"]?.jsonPrimitive?.contentOrNull ?: "",
                                added = x["added"]?.jsonPrimitive?.longOrNull ?: 0L,
                                updated = x["updated"]?.jsonPrimitive?.longOrNull ?: 0L
                            )
                        }.getOrNull()
                    }.orEmpty()
                )
            }.getOrNull()
        }.toMutableList()
    }.getOrDefault(mutableListOf())

    fun save(list: List<Base>) {
        val body = list.joinToString(",", "[", "]") { b ->
            """{"id":${js(b.id)},"name":${js(b.name)},"desc":${js(b.desc)},""" +
                """"defaultOpen":${b.defaultOpen},"created":${b.created},"docs":[${
                b.docs.joinToString(",") { d ->
                    """{"name":${js(d.name)},"kind":${js(d.kind)},"bytes":${d.bytes},"chars":${d.chars},""" +
                        """"chunks":${d.chunks},"hash":${js(d.hash)},"status":${js(d.status)},""" +
                        """"error":${js(d.error)},"added":${d.added},"updated":${d.updated}}"""
                }}]}"""
        }
        runCatching { file().parentFile?.mkdirs(); Env.atomicWrite(file(), body) }
    }

    fun find(id: String): Base? = load().firstOrNull { it.id == id }

    fun create(name: String, desc: String = ""): Pair<Base?, String?> {
        val n = name.trim()
        if (n.isEmpty()) return null to "库得有个名字"
        val list = load()
        if (list.size >= MAX_BASES) return null to "知识库最多 $MAX_BASES 个，先删一个"
        if (list.any { it.name == n }) return null to "已经有同名的库了"
        val b = Base(id = "kb" + System.nanoTime().toString(16).take(8), name = n, desc = desc.trim())
        runCatching { srcDir(b.id).mkdirs(); txtDir(b.id).mkdirs() }
        list += b
        save(list)
        return b to null
    }

    fun remove(id: String): Boolean {
        val list = load()
        val kept = list.filterNot { it.id == id }
        if (kept.size == list.size) return false
        save(kept)
        runCatching { dir(id).deleteRecursively() }
        return true
    }

    fun rename(id: String, name: String? = null, desc: String? = null,
               defaultOpen: Boolean? = null): Base? {
        val list = load()
        val i = list.indexOfFirst { it.id == id }
        if (i < 0) return null
        val b = list[i]
        val nb = b.copy(
            name = name?.trim()?.ifBlank { b.name } ?: b.name,
            desc = desc?.trim() ?: b.desc,
            defaultOpen = defaultOpen ?: b.defaultOpen
        )
        list[i] = nb
        save(list)
        return nb
    }

    // ---- 文档 ----

    /** 展示名只留文件名本体：路径分隔符、`..`、开头点号一律挡掉。 */
    internal fun safeName(raw: String): String? {
        val n = raw.trim().replace('\\', '/').substringAfterLast('/')
        if (n.isEmpty() || n.startsWith(".") || n.contains("..") || n.length > 120) return null
        val ext = n.substringAfterLast('.', "").lowercase()
        return if (ext in TEXT_EXT || ext in OOXML_EXT || ext in WEB_EXT || ext == "pdf") n else null
    }

    /**
     * 收一份文档：解析正文 → 切块 → 原文与正文各落一份。
     * 解析失败**也要留下这一条**（status=failed + 原因）：用户要知道"我传的那个 pdf 没吃进去"，
     * 而不是列表里凭空少一项、以为传成功了。
     */
    fun addDoc(kbId: String, rawName: String, bytes: ByteArray): Pair<Doc?, String?> {
        val list = load()
        val bi = list.indexOfFirst { it.id == kbId }
        if (bi < 0) return null to "没有这个知识库"
        val name = safeName(rawName) ?: return null to "不收这个文件（要 " +
            (TEXT_EXT + OOXML_EXT + WEB_EXT + setOf("pdf")).sorted().joinToString("/") + "）"
        val b = list[bi]
        if (bytes.size > MAX_RAW_BYTES) return null to "文件太大（${bytes.size / 1024}KB，上限 ${MAX_RAW_BYTES / 1000000}MB）"
        if (b.docs.none { it.name == name } && b.docs.size >= MAX_DOCS)
            return null to "这个库最多 $MAX_DOCS 份文档，先删几份"
        val (text, err) = extract(name, bytes)
        val doc = Doc(
            name = name, kind = name.substringAfterLast('.').lowercase(), bytes = bytes.size,
            chars = text.length, chunks = chunkCount(text),
            hash = sha8(bytes),
            status = if (err == null && text.isNotBlank()) READY else FAILED,
            error = err ?: if (text.isBlank()) "没抽出文字来（空文件，或者只有图片？）" else "",
            added = b.docs.firstOrNull { it.name == name }?.added ?: System.currentTimeMillis(),
            updated = System.currentTimeMillis()
        )
        runCatching {
            srcDir(kbId).mkdirs(); txtDir(kbId).mkdirs()
            File(srcDir(kbId), name).writeBytes(bytes)
            if (doc.status == READY) txtFile(kbId, name).writeText(text)
            else txtFile(kbId, name).delete()
        }
        // 正文变了，向量缓存里还挂着旧块：删缓存，下一次检索重建（一个库就几份文档，重建不贵）
        SemanticIndex.dropCache(txtDir(kbId))
        list[bi] = b.copy(docs = b.docs.filterNot { it.name == name } + doc)
        save(list)
        return doc to doc.error.ifBlank { null }
    }

    fun dropDoc(kbId: String, name: String): Boolean {
        val list = load()
        val bi = list.indexOfFirst { it.id == kbId }
        if (bi < 0) return false
        val b = list[bi]
        val kept = b.docs.filterNot { it.name == name }
        if (kept.size == b.docs.size) return false
        runCatching {
            File(srcDir(kbId), name).delete()
            txtFile(kbId, name).delete()
            SemanticIndex.dropCache(txtDir(kbId))
        }
        list[bi] = b.copy(docs = kept)
        save(list)
        return true
    }

    /** 重新解析一份（原文还在盘上）：换了解析器、或当初失败现在想再试一次。 */
    fun reindex(kbId: String, name: String): Pair<Doc?, String?> {
        val f = File(srcDir(kbId), name)
        if (!f.isFile) return null to "原文不在了，重新索引不了：先删掉这条再重新导入"
        return addDoc(kbId, name, f.readBytes())
    }

    fun reindexAll(kbId: String): Int {
        val b = find(kbId) ?: return 0
        var ok = 0
        b.docs.forEach { if (reindex(kbId, it.name).first?.status == READY) ok++ }
        return ok
    }

    fun textOf(kbId: String, name: String): String? =
        runCatching { txtFile(kbId, name).takeIf { it.isFile }?.readText() }.getOrNull()

    fun srcFile(kbId: String, name: String): File? =
        File(srcDir(kbId), name).takeIf { it.isFile }

    // ---- 抽取 ----

    /**
     * 从一份文件里抽出正文。返回 `(文本, 错误)`。
     *
     * docx/xlsx/pptx 都是 zip 里装 XML —— 用 JDK 自带的 `ZipInputStream` 就能读，
     * 不必引 POI（离线构建是红线）。只取文字节点，样式、图、批注一律不要：
     * 知识库要的是"能查到那句话"，不是还原排版。
     */
    internal fun extract(name: String, bytes: ByteArray): Pair<String, String?> {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when {
            ext in TEXT_EXT -> String(bytes, Charsets.UTF_8).let {
                if (it.isBlank()) "" to "内容是空的" else it to null
            }
            ext in WEB_EXT -> stripTags(String(bytes, Charsets.UTF_8)) to null
            ext in OOXML_EXT -> ooxml(ext, bytes)
            ext == "pdf" -> pdf(bytes)
            else -> "" to "不认识这种文件"
        }
    }

    private fun ooxml(ext: String, bytes: ByteArray): Pair<String, String?> {
        val want = when (ext) {
            "docx" -> setOf("word/document.xml")
            "pptx" -> null            // 多份 slide，单独走
            else -> setOf("xl/sharedStrings.xml")
        }
        val parts = LinkedHashMap<String, ByteArray>()
        val texts = ArrayList<String>()
        runCatching {
            ZipInputStream(bytes.inputStream()).use { z ->
                var e = z.nextEntry
                while (e != null) {
                    val n = e.name
                    val take = want != null && n in want ||
                        (ext == "pptx" && n.startsWith("ppt/slides/slide") && n.endsWith(".xml"))
                    if (take) parts[n] = z.readBytes()
                    e = z.nextEntry
                }
            }
        }.onFailure { return "" to "它不是一个有效的 $ext（读 zip 失败：${it.message}）" }
        if (parts.isEmpty()) return "" to "这个 $ext 里没找到正文部件（可能是加密或损坏的文档）"
        // 文件名排序：pptx 的 slide1/2/10 要按数字顺序读，不然章节会乱
        parts.toSortedMap(compareBy { Regex("""(\d+)""").find(it)?.value?.toIntOrNull() ?: 0 })
            .values.forEach { xml ->
                texts += textNodes(String(xml, Charsets.UTF_8))
            }
        val out = texts.joinToString("\n").take(MAX_TEXT_CHARS)
        return if (out.isBlank()) "" to "抽不出文字（扫描版？只有图？）" else out to null
    }

    /**
     * 从一段 OOXML 里取文字节点。
     *
     * docx 是 `<w:t>`、pptx 是 `<a:t>`、xlsx 的共享字符串是 `<t>` —— 前缀不同但形状一样。
     * 段落结束（`</w:p>` / `</a:p>`）补一个换行，不然整篇文档会被挤成一行，
     * 而 [SemanticIndex] 是按行攒块的：挤成一行 = 一个巨大的块 = 检索粒度全丢。
     */
    internal fun textNodes(xml: String): String = buildString {
        var i = 0
        while (i < xml.length) {
            val lt = xml.indexOf('<', i)
            if (lt < 0) { append(xml.substring(i)); break }
            val gt = xml.indexOf('>', lt)
            if (gt < 0) break
            val tag = xml.substring(lt + 1, gt)
            when {
                tag.endsWith(":t") || tag == "t" -> {
                    val end = xml.indexOf("</" + tag.trimStart('/'), gt)
                    if (end > gt) {
                        append(unescapeXml(xml.substring(gt + 1, end)))
                        i = xml.indexOf('>', end) + 1
                        continue
                    }
                }
                // 段落结束补一个换行：整篇挤成一行会让按行攒块的索引器把整份文档变成一个块。
                // （`tag` 是从 `<` 到 `>` 之间的内容，本身不带 `>` —— 原来按 ":p>" 匹配永远不中）
                tag.startsWith("/") && tag.endsWith(":p") -> append('\n')
                tag.endsWith(":p/>") || tag.endsWith(":br/>") -> append('\n')
            }
            i = gt + 1
        }
    }

    private fun unescapeXml(s: String): String = s
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&apos;", "'").replace("&amp;", "&")

    private fun stripTags(html: String): String =
        unescapeXml(html.replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ")
            .replace(Regex("(?s)<[^>]+>"), "\n").replace(Regex("\n{2,}"), "\n")).trim()

    /**
     * PDF：只在本机有 `pdftotext`（poppler）时才抽。
     *
     * 为什么自己写一个：PDF 的文本流是压缩 + 字形编码 + 坐标定位，
     * 手写一个能用的解析器是几周的工作量，而且大概率在用户的中文 PDF 上出错。
     * **宁可明说"这台机器没装 pdftotext，这份没收进来"**，也不要收进来一份读不出文字的假语料 ——
     * 那种东西最坏的地方是列表里显示"已导入"，而模型永远查不到它。
     */
    private fun pdf(bytes: ByteArray): Pair<String, String?> {
        val tool = sequenceOf("pdftotext.exe", "pdftotext")
            .mapNotNull { c -> runCatching { File(c).absolutePath }.getOrNull() }
            .firstOrNull { which(it) != null }
            ?: return "" to "这台机器没有 pdftotext（poppler），PDF 收进来也读不出字：先转成 md/txt 再导入"
        val tmpIn = File.createTempFile("haoai-kb-", ".pdf")
        val tmpOut = File.createTempFile("haoai-kb-", ".txt")
        try {
            tmpIn.writeBytes(bytes)
            val p = ProcessBuilder(which(tool)!!, "-q", "-enc", "UTF-8", tmpIn.absolutePath,
                tmpOut.absolutePath).redirectErrorStream(true).start()
            if (!p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly()
                return "" to "pdftotext 30 秒没返回，这份 PDF 先不收"
            }
            val text = runCatching { tmpOut.readText(Charsets.UTF_8) }.getOrDefault("")
            return if (text.isBlank()) "" to "PDF 里没抽出文字（扫描版？要 OCR，这里不做）"
            else text.take(MAX_TEXT_CHARS) to null
        } catch (e: Exception) {
            return "" to "pdftotext 没跑成：" + (e.message ?: e.javaClass.simpleName)
        } finally {
            runCatching { tmpIn.delete(); tmpOut.delete() }
        }
    }

    private fun which(cmd: String): String? = (System.getenv("PATH") ?: "")
        .split(File.pathSeparator)
        .asSequence()
        .map { File(it, cmd) }
        .firstOrNull { it.isFile }?.absolutePath

    // ---- 切块与检索 ----

    /** 块数：与 [SemanticIndex] 同一思路（按行攒），这里只给"这份会被切成几块"的读数。 */
    internal fun chunkCount(text: String): Int {
        if (text.isBlank()) return 0
        var n = 0
        var i = 0
        while (i < text.length) {
            n++
            i += 600
        }
        return n
    }

    data class Found(val kb: String, val kbName: String, val doc: String, val score: Double,
                     val snippet: String, val how: String)

    /**
     * 在几个库里检索。`embedUrl` 空 → 关键词扫正文（**不是**"功能不可用"）。
     */
    fun search(kbIds: List<String>, q: String, embedUrl: String, top: Int = 5): Pair<List<Found>, String?> {
        val question = q.trim()
        if (question.isEmpty()) return emptyList<Found>() to "先写一句要查的"
        val hits = ArrayList<Found>()
        var note: String? = null
        for (id in kbIds) {
            val b = find(id) ?: continue
            if (embedUrl.isNotBlank()) {
                val r = SemanticIndex.query(txtDir(id), question, embedUrl, top)
                r.hits.forEach { h ->
                    hits += Found(id, b.name, h.path.removeSuffix(".md"), h.score, h.snippet, "语义")
                }
                if (r.note != null) note = r.note
            }
            if (hits.isEmpty() || embedUrl.isBlank()) {
                // 关键词：字面命中在"查一个确切的术语/表名"时常常比向量更准，不是凑数的降级
                b.docs.filter { it.status == READY }.forEach { d ->
                    val t = textOf(id, d.name) ?: return@forEach
                    val i = t.lowercase().indexOf(question.lowercase())
                    if (i >= 0) {
                        val from = (i - 60).coerceAtLeast(0)
                        hits += Found(id, b.name, d.name, 1.0,
                            t.substring(from, (i + question.length + 120).coerceAtMost(t.length))
                                .replace('\n', ' '), "字面")
                    }
                }
            }
        }
        return hits.sortedByDescending { it.score }.take(top) to note
    }

    /** 给系统提示用的目录（模型得知道有哪些库、库里有什么，才会去查）。 */
    fun catalog(ids: List<String>): String {
        val bs = ids.mapNotNull { find(it) }.filter { it.docs.isNotEmpty() }
        if (bs.isEmpty()) return ""
        return bs.joinToString("；") { b ->
            "「${b.name}」${b.docs.size} 份：" + b.docs.take(8).joinToString("、") { it.name } +
                (if (b.docs.size > 8) "…等 ${b.docs.size} 份" else "")
        }
    }

    fun boundTo(presetId: String): List<String> {
        if (presetId.isBlank()) return defaultOpenIds()
        return Presets.find(presetId)?.kbs.orEmpty().ifEmpty { defaultOpenIds() }
    }

    fun defaultOpenIds(): List<String> = load().filter { it.defaultOpen }.map { it.id }

    fun json(embedConfigured: Boolean): String {
        val list = load().joinToString(",", "[", "]") { b ->
            """{"id":${js(b.id)},"name":${js(b.name)},"desc":${js(b.desc)},""" +
                """"defaultOpen":${b.defaultOpen},"created":${b.created},""" +
                """"docs":[${b.docs.joinToString(",") { d ->
                """{"name":${js(d.name)},"kind":${js(d.kind)},"bytes":${d.bytes},"chars":${d.chars},""" +
                    """"chunks":${d.chunks},"status":${js(d.status)},"error":${js(d.error)},""" +
                    """"added":${d.added},"updated":${d.updated}}"""
            }}]}"""
        }
        return """{"ok":true,"items":$list,"maxBases":$MAX_BASES,"maxDocs":$MAX_DOCS,""" +
            """"embed":$embedConfigured,"pdf":${pdfAvailable()},""" +
            """"exts":${(TEXT_EXT + OOXML_EXT + WEB_EXT + setOf("pdf")).sorted()
                .joinToString(",", "[", "]") { js(it) }}}"""
    }

    fun pdfAvailable(): Boolean = which("pdftotext.exe") != null || which("pdftotext") != null

    private fun sha8(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }.take(16)

    private fun js(s: String): String = "\"" +
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "") + "\""
}
