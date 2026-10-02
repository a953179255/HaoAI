package com.haoai.pc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Base64

/**
 * Codebase 语义索引（#6）。
 *
 * 为什么存在：`grep` 只认字面 —— 问"配置怎么加载"时，文件里写的是"读取 config 并初始化"，
 * 关键词一个都撞不上。语义索引把工作区切成块、向量化、按余弦给"词不相同但意思相近"的邻居。
 *
 * 边界（刻意的）：
 * - **只是 grep 的回退**：文本有命中就不掺和（0 命中才附语义近邻），不改变现有 grep 的任何输出；
 * - **端点不配就完全不存在**：`settings.embedUrl` 为空时查询直接返回"未配置"，grep 照旧；
 * - **失败不拖死搜索**：端点连不上/形状不对 → 返回原因，grep 照旧给文本结果；
 * - **增量**：缓存按文件 hash 存，只有新增/改动的文件重新向量化（第一次建索引会慢，之后只嵌查询那一句）。
 *
 * 缓存放状态根 `HAOAI_HOME/embed-index/<sha1(ws)>.json`：向量是派生物（模型/端点一换就该全弃），
 * 跟着状态根走而不是工作区 —— 不污染用户仓库，`dim`/`ver` 不匹配自动整体重建。
 *
 * 存储：每文件的向量拼成 FloatArray → Base64 一条字符串。**不**用 JSON 数字数组 ——
 * 2560 维 × 几百块 = 上百万 JsonPrimitive，对象树会把堆吃穿（量级算过，别再试）。
 *
 * 不做进程内缓存：会把"文件改了但进程还活着"变成永远陈旧的索引（比慢更糟）；
 * 每次查询读盘 + 重算 hash（几百个文件的顺序 IO），只有向量化那步是贵的，而它按 hash 跳过。
 */
object SemanticIndex {

    private const val VER = 1
    private const val BATCH = 16
    private const val MAX_FILES = 300
    private const val MAX_BYTES = 200_000
    private const val CHUNK_TARGET = 600     // 每块约 600 字符（按行攒）
    private const val SNIPPET = 160

    private val EXTS = setOf(
        "kt", "java", "py", "js", "ts", "tsx", "jsx", "sh", "ps1", "md", "json",
        "yml", "yaml", "toml", "gradle", "kts", "xml", "properties", "txt", "cfg", "ini"
    )
    private val SKIP_DIRS = setOf(
        ".git", "build", "dist", "out", "target", "node_modules", ".gradle", ".idea",
        ".haoai-output", ".haoai-attach", ".trash", ".kotlin", "bin"
    )

    data class Hit(val path: String, val score: Double, val snippet: String)

    /** 查询结果。[note] 非空 = 语义这轮没跑成/没配（hits 可能为空），调用方原样转告。 */
    data class Result(val hits: List<Hit>, val note: String?)

    // ---- 纯函数（单测直接钉这些，不碰网络）----

    /** 按行攒块：约 [CHUNK_TARGET] 字符一块；超长单行硬切。 */
    fun chunkText(text: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        for (line in text.lineSequence()) {
            if (sb.length >= CHUNK_TARGET) {
                out.add(sb.toString()); sb.setLength(0)
            }
            if (line.length > CHUNK_TARGET * 2) {
                var i = 0
                while (i < line.length) {
                    if (sb.length >= CHUNK_TARGET) { out.add(sb.toString()); sb.setLength(0) }
                    val end = (i + CHUNK_TARGET).coerceAtMost(line.length)
                    sb.append(line, i, end); i = end
                }
            } else {
                sb.append(line).append('\n')
            }
        }
        if (sb.isNotBlank()) out.add(sb.toString())
        return out.filter { it.isNotBlank() }
    }

    /** 该索引哪些文件：文本类扩展名 + 大小上限 + 目录黑名单，按路径排序（顺序确定，缓存才稳）。 */
    fun filesToIndex(ws: File): List<File> {
        val out = mutableListOf<File>()
        fun walk(d: File, depth: Int) {
            if (depth > 12 || out.size >= MAX_FILES * 2) return
            val kids = d.listFiles() ?: return
            for (f in kids.sortedBy { it.name }) {
                if (f.isDirectory) {
                    if (f.name !in SKIP_DIRS) walk(f, depth + 1)
                } else if (f.extension.lowercase() in EXTS && f.length() <= MAX_BYTES) {
                    out += f
                }
            }
        }
        walk(ws, 0)
        return out.sortedBy { it.path }.take(MAX_FILES)
    }

    fun hashOf(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }.take(16)

    /**
     * 丢掉这个工作区的向量缓存（下一轮查询全量重建）。增量重建本身能感知删除 ——
     * loadOrBuild 只沿用在盘上的文件 —— 这里是给调用方的"清场开关"：
     * 删语料后旧向量一并清掉，也兜住"换了向量化模型、维度不匹配"那种
     * 原本只能手动删 HAOAI_HOME/embed-index 的局面。
     */
    fun dropCache(ws: File) {
        runCatching { cacheFile(ws).delete() }
    }

    // ---- 端点无关的编解码：FloatArray ↔ Base64（小端）----

    fun encodeVecs(vecs: List<List<Float>>): String {
        val buf = ByteBuffer.allocate(vecs.sumOf { it.size } * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (v in vecs) for (f in v) buf.putFloat(f)
        return Base64.getEncoder().encodeToString(buf.array())
    }

    fun decodeVecs(b64: String, dim: Int, count: Int): List<List<Float>>? = runCatching {
        val bytes = Base64.getDecoder().decode(b64)
        if (bytes.size != count * dim * 4) return null
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        (0 until count).map { (0 until dim).map { buf.float } }
    }.getOrNull()

    // ---- 主流程 ----

    private class Entry(val path: String, val hash: String, val chunks: List<String>, val vecs: List<List<Float>>)

    @Volatile
    private var lastFailure: String? = null

    /**
     * 查语义近邻。[embedUrl] 为空 → note="未配置"；端点失败 → note=原因；
     * 成功且有结果 → hits 按余弦降序（同分按路径，顺序稳定）。
     */
    fun query(ws: File, question: String, embedUrl: String, top: Int = 3): Result {
        if (embedUrl.isBlank()) return Result(emptyList(), "未配置 embedUrl（haoai set embedUrl=…/embeddings）")
        if (question.isBlank()) return Result(emptyList(), "查询是空的")
        synchronized(this) {
            val built = loadOrBuild(ws, embedUrl)
                ?: return Result(emptyList(), lastFailure ?: "索引建立失败")
            val (dim, entries) = built
            if (entries.isEmpty()) return Result(emptyList(), "工作区没有可索引的文本文件")

            val qv = Embed.embed(embedUrl, listOf(question))?.firstOrNull()
                ?: return Result(emptyList(), "查询向量化失败（端点 $embedUrl）")
            if (qv.size != dim) {
                return Result(emptyList(), "查询向量维度 ${qv.size} ≠ 索引 $dim（模型换了？删 HAOAI_HOME/embed-index 重建）")
            }
            val hits = entries.flatMap { e ->
                e.chunks.indices.map { i ->
                    Hit(e.path, Embed.cosine(qv, e.vecs[i].map { it.toDouble() }), e.chunks[i].take(SNIPPET))
                }
            }.sortedWith(compareByDescending<Hit> { it.score }.thenBy { it.path })
                .filter { it.score > 0.05 }
                .take(top)
            return Result(hits, null)
        }
    }

    private class Stored(val hash: String, val chunks: List<String>, val vecsB64: String?)

    /** 读盘缓存 + 增量重建。失败返回 null 并记 [lastFailure]。 */
    private fun loadOrBuild(ws: File, embedUrl: String): Pair<Int, List<Entry>>? {
        val file = cacheFile(ws)

        // 读盘缓存（ver/dim 对不上就当没有）
        var storedVer = -1
        var storedDim = -1
        val stored = HashMap<String, Stored>()
        if (file.isFile) {
            runCatching {
                val root = Json.parseToJsonElement(file.readText()).jsonObject
                storedVer = root["ver"]!!.jsonPrimitive.content.toInt()
                storedDim = root["dim"]!!.jsonPrimitive.content.toInt()
                for (e in root["files"]!!.jsonArray) {
                    val o = e.jsonObject
                    stored[o["p"]!!.jsonPrimitive.content] = Stored(
                        o["h"]!!.jsonPrimitive.content,
                        o["c"]!!.jsonArray.map { it.jsonPrimitive.content },
                        o["v"]?.jsonPrimitive?.content
                    )
                }
            }.onFailure { stored.clear() }
        }
        if (storedVer != VER || storedDim <= 0) {
            stored.clear()
            storedDim = -1
        }

        // 现盘上该有的
        val contents = LinkedHashMap<String, String>()
        val hashes = HashMap<String, String>()
        for (f in filesToIndex(ws)) {
            val text = runCatching { f.readText() }.getOrNull() ?: continue
            if (text.indexOf(0.toChar()) >= 0) continue      // 混进来的二进制
            val rel = f.relativeToOrSelf(ws).path.replace('\\', '/')
            contents[rel] = text
            hashes[rel] = hashOf(text.toByteArray(Charsets.UTF_8))
        }

        // 需要（重新）向量化的：新增、hash 变了、或缓存里没有向量
        val need = contents.filter { (rel, _) ->
            stored[rel]?.hash != hashes[rel] || stored[rel]?.vecsB64 == null
        }

        if (need.isEmpty()) {
            if (contents.isEmpty()) return 0 to emptyList()
            // 全部命中缓存：解码即用
            val entries = mutableListOf<Entry>()
            for ((rel, _) in contents) {
                val s = stored[rel] ?: return null
                val vecs = decodeVecs(s.vecsB64 ?: return null, storedDim, s.chunks.size)
                    ?: run { lastFailure = "缓存向量解码失败（$rel）"; return null }
                entries += Entry(rel, s.hash, s.chunks, vecs)
            }
            return storedDim to entries
        }

        // 建/增量：need 的块拼批向量化；缓存里没动的文件沿用旧向量
        val pendingChunks = LinkedHashMap<String, List<String>>()
        val flat = mutableListOf<String>()
        val owner = mutableListOf<String>()               // flat[i] → "rel idx"
        for ((rel, text) in need) {
            val chunks = chunkText(text)
            if (chunks.isEmpty()) continue
            pendingChunks[rel] = chunks
            chunks.forEachIndexed { i, c -> flat += c; owner += rel + 0.toChar() + i }
        }
        val vecsByRel = HashMap<String, MutableList<List<Float>>>()
        var dim = storedDim.takeIf { it > 0 } ?: 0
        for (i in flat.indices step BATCH) {
            val batch = flat.subList(i, (i + BATCH).coerceAtMost(flat.size))
            val vecs = Embed.embed(embedUrl, batch)
                ?: run { lastFailure = "端点 $embedUrl 向量化失败（连不上/超时/形状不对）"; return null }
            for ((j, v) in vecs.withIndex()) {
                if (dim <= 0) dim = v.size
                if (v.size != dim) {
                    lastFailure = "维度不一致（新 ${v.size} vs 旧 $dim）——多半是换了模型，删 HAOAI_HOME/embed-index 重建"
                    return null
                }
                val rel = owner[i + j].substringBefore(0.toChar())
                vecsByRel.getOrPut(rel) { mutableListOf() } += v.map { it.toFloat() }
            }
        }
        if (dim <= 0) { lastFailure = "端点没给出向量"; return null }

        val entries = mutableListOf<Entry>()
        // 没动过的文件：沿用缓存
        for ((rel, s) in stored) {
            if (rel in pendingChunks || rel !in contents) continue
            val vecs = decodeVecs(s.vecsB64 ?: continue, storedDim.takeIf { it > 0 } ?: dim, s.chunks.size)
                ?: continue
            entries += Entry(rel, s.hash, s.chunks, vecs)
        }
        // 动过的文件：新向量
        for ((rel, chunks) in pendingChunks) {
            entries += Entry(rel, hashes[rel] ?: return null, chunks, vecsByRel[rel] ?: return null)
        }
        entries.sortBy { it.path }
        save(ws, dim, entries)
        lastFailure = null
        return dim to entries
    }

    private fun cacheFile(ws: File): File {
        val dir = File(Env.home, "embed-index")
        dir.mkdirs()
        return File(dir, hashOf(ws.absolutePath.toByteArray(Charsets.UTF_8)) + ".json")
    }

    private fun save(ws: File, dim: Int, entries: List<Entry>) = runCatching {
        val json = buildString {
            append("{\"ver\":").append(VER).append(",\"dim\":").append(dim).append(",\"files\":[")
            entries.forEachIndexed { i, e ->
                if (i > 0) append(',')
                append("{\"p\":").append(qStr(e.path))
                append(",\"h\":").append(qStr(e.hash))
                append(",\"c\":").append(Json.encodeToString(kotlinx.serialization.json.buildJsonArray {
                    e.chunks.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
                }))
                append(",\"v\":").append(qStr(encodeVecs(e.vecs))).append('}')
            }
            append("]}")
        }
        cacheFile(ws).writeText(json)
    }

    private fun qStr(s: String): String =
        Json.encodeToString(kotlinx.serialization.json.JsonPrimitive.serializer(), kotlinx.serialization.json.JsonPrimitive(s))
}
