package com.haoai.agent.platform

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class GrepHit(val path: String, val line: Int, val text: String)

data class FileEntry(
    val path: String,
    val size: Long,
    val lastModified: Long
)

interface FileBackend {
    val displayName: String

    suspend fun readText(rel: String, maxBytes: Int = MAX_READ_BYTES): String
    suspend fun writeText(rel: String, content: String)
    suspend fun delete(rel: String)
    suspend fun rename(fromRel: String, newName: String)
    suspend fun listDir(rel: String): List<String>
    suspend fun walk(maxEntries: Int = 8000): List<FileEntry>
    fun shellWorkdir(): File?

    companion object {
        const val MAX_READ_BYTES = 256 * 1024
        val TEXT_EXTENSIONS = setOf(
            "txt", "md", "json", "xml", "html", "htm", "css", "scss", "js", "jsx", "ts", "tsx",
            "kt", "kts", "java", "py", "rb", "go", "rs", "c", "h", "cpp", "hpp", "cs", "swift",
            "sh", "bash", "zsh", "yaml", "yml", "toml", "ini", "cfg", "conf", "properties",
            "gradle", "sql", "csv", "log", "gitignore", "env", "vue", "svelte", "dart", "php"
        )
        val BINARY_EXTENSIONS = setOf(
            "png", "jpg", "jpeg", "gif", "webp", "bmp", "ico", "zip", "jar", "apk", "aab",
            "gz", "tar", "7z", "rar", "pdf", "mp3", "mp4", "mov", "avi", "mkv", "wav", "flac",
            "ttf", "otf", "woff", "woff2", "so", "dll", "exe", "bin", "class", "pyc", "o", "a"
        )

        fun guessMime(name: String): String {
            val ext = name.substringAfterLast('.', "").lowercase()
            return when (ext) {
                in TEXT_EXTENSIONS -> "text/plain"
                "md" -> "text/markdown"
                "html", "htm" -> "text/html"
                "css" -> "text/css"
                "js" -> "text/javascript"
                "json" -> "application/json"
                "pdf" -> "application/pdf"
                "png" -> "image/png"
                "jpg", "jpeg" -> "image/jpeg"
                "gif" -> "image/gif"
                "webp" -> "image/webp"
                "svg" -> "image/svg+xml"
                else -> "application/octet-stream"
            }
        }

        fun looksBinary(head: ByteArray): Boolean {
            val n = minOf(head.size, 4096)
            for (i in 0 until n) if (head[i] == 0.toByte()) return true
            return false
        }
    }
}

object PathSafety {
    fun normalize(raw: String): List<String> {
        val cleaned = raw.trim().replace('\\', '/').trimStart('/')
        require(cleaned.isNotEmpty()) { "路径为空" }
        val parts = cleaned.split('/').filter { it.isNotEmpty() && it != "." }
        require(parts.none { it == ".." }) { "路径越界被拒绝：$raw" }
        require(parts.none { it.contains('\u0000') }) { "非法路径" }
        return parts
    }

    fun join(segments: List<String>): String =
        segments.joinToString("/")
}

class RawFileBackend(private val root: File) : FileBackend {

    override val displayName: String get() = root.absolutePath

    private fun resolve(rel: String): File {
        val segs = PathSafety.normalize(rel)
        var f = root
        for (s in segs) f = File(f, s)
        val canonRoot = root.canonicalFile
        val canon = f.canonicalFile
        if (canon != canonRoot && !canon.path.startsWith(canonRoot.path + File.separator)) {
            throw SecurityException("路径越界被拒绝：$rel")
        }
        return f
    }

    private fun relOf(file: File): String =
        file.relativeToOrSelf(root).invariantSeparatorsPath

    override suspend fun readText(rel: String, maxBytes: Int): String = withContext(Dispatchers.IO) {
        val f = resolve(rel)
        require(f.exists()) { "文件不存在：$rel" }
        require(f.isFile) { "这是一个目录，请用目录模式读取：$rel" }
        val head = ByteArray(8192)
        f.inputStream().use { ins ->
            val n = ins.read(head).coerceAtLeast(0)
            if (FileBackend.looksBinary(head.copyOf(n))) throw IllegalStateException("检测到二进制文件，拒绝读取：$rel")
        }
        if (f.length() > maxBytes) {
            val buf = ByteArray(maxBytes)
            val read = f.inputStream().use { ins ->
                var off = 0
                while (off < maxBytes) {
                    val r = ins.read(buf, off, maxBytes - off)
                    if (r < 0) break
                    off += r
                }
                off
            }
            String(buf, 0, read, Charsets.UTF_8) + "\n\n[文件过大，已截断至 $read 字节，共 ${f.length()} 字节]"
        } else {
            f.readText(Charsets.UTF_8)
        }
    }

    override suspend fun writeText(rel: String, content: String): Unit = withContext(Dispatchers.IO) {
        val f = resolve(rel)
        f.parentFile?.mkdirs()
        f.writeText(content, Charsets.UTF_8)
    }

    override suspend fun delete(rel: String): Unit = withContext(Dispatchers.IO) {
        val f = resolve(rel)
        require(f.exists()) { "不存在：$rel" }
        if (f.isDirectory) f.deleteRecursively() else f.delete()
        require(!f.exists()) { "删除失败：$rel" }
    }

    override suspend fun rename(fromRel: String, newName: String): Unit = withContext(Dispatchers.IO) {
        val f = resolve(fromRel)
        require(f.exists()) { "不存在：$fromRel" }
        require(newName.isNotBlank() && !newName.contains('/') && newName != "." && newName != "..") {
            "非法名称：$newName"
        }
        val target = File(f.parentFile, newName)
        require(!target.exists()) { "目标已存在：$newName" }
        require(f.renameTo(target)) { "重命名失败" }
    }

    override suspend fun listDir(rel: String): List<String> = withContext(Dispatchers.IO) {
        val f = if (rel.isBlank()) root else resolve(rel)
        require(f.isDirectory) { "不是目录：$rel" }
        f.listFiles()
            ?.sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })
            ?.map { if (it.isDirectory) "${it.name}/" else it.name }
            ?: emptyList()
    }

    override suspend fun walk(maxEntries: Int): List<FileEntry> = withContext(Dispatchers.IO) {
        val out = ArrayList<FileEntry>(maxEntries)
        root.walkTopDown()
            .onEnter { dir -> dir.listFiles()?.none { it.name == ".git" } ?: true }
            .filter { it.isFile }
            .forEach { f ->
                if (out.size >= maxEntries) return@forEach
                out.add(FileEntry(relOf(f), f.length(), f.lastModified()))
            }
        out
    }

    override fun shellWorkdir(): File = root
}

class SafFileBackend(
    context: Context,
    treeUri: Uri
) : FileBackend {

    private val appContext = context.applicationContext
    private val root: DocumentFile = DocumentFile.fromTreeUri(appContext, treeUri)
        ?: throw IllegalArgumentException("无法访问所选目录")

    override val displayName: String =
        treeUri.lastPathSegment?.substringAfterLast('/')?.take(48) ?: "已授权目录"

    private fun resolveDoc(rel: String): DocumentFile {
        val segs = PathSafety.normalize(rel)
        var cur = root
        for (s in segs) {
            cur = cur.findFile(s) ?: throw IllegalStateException("未找到：$rel")
        }
        return cur
    }

    private fun parentDoc(rel: String): Pair<DocumentFile, String> {
        val segs = PathSafety.normalize(rel)
        require(segs.isNotEmpty()) { "路径为空" }
        val parentSegs = segs.dropLast(1)
        var cur = root
        for (s in parentSegs) {
            val found = cur.findFile(s)
            cur = if (found != null && found.isDirectory) found
            else found ?: (cur.createDirectory(s) ?: throw IllegalStateException("无法创建目录：$s"))
        }
        return cur to segs.last()
    }

    override suspend fun readText(rel: String, maxBytes: Int): String = withContext(Dispatchers.IO) {
        val doc = resolveDoc(rel)
        require(doc.exists()) { "文件不存在：$rel" }
        require(doc.isFile) { "这是一个目录：$rel" }
        appContext.contentResolver.openInputStream(doc.uri)?.use { ins ->
            val head = ByteArray(8192)
            val hn = ins.read(head)
            if (FileBackend.looksBinary(head.copyOf(hn.coerceAtLeast(0)))) {
                throw IllegalStateException("检测到二进制文件，拒绝读取：$rel")
            }
            val rest = ins.readBytes()
            val all = head.copyOf(hn) + rest
            if (all.size > maxBytes) {
                String(all, 0, maxBytes, Charsets.UTF_8) + "\n\n[文件过大，已截断至 $maxBytes 字节，共 ${all.size} 字节]"
            } else {
                String(all, Charsets.UTF_8)
            }
        } ?: throw IllegalStateException("无法打开：$rel")
    }

    override suspend fun writeText(rel: String, content: String): Unit = withContext(Dispatchers.IO) {
        val (parent, name) = parentDoc(rel)
        val existing = parent.findFile(name)
        val bytes = content.toByteArray(Charsets.UTF_8)
        val target = existing ?: parent.createFile(FileBackend.guessMime(name), name)
            ?: throw IllegalStateException("创建文件失败：$rel")
        appContext.contentResolver.openOutputStream(target.uri, "wt")?.use { os ->
            os.write(bytes)
            os.flush()
        } ?: throw IllegalStateException("写入失败：$rel")
    }

    override suspend fun delete(rel: String): Unit = withContext(Dispatchers.IO) {
        val doc = resolveDoc(rel)
        require(doc.delete()) { "删除失败：$rel" }
    }

    override suspend fun rename(fromRel: String, newName: String): Unit = withContext(Dispatchers.IO) {
        val doc = resolveDoc(fromRel)
        require(newName.isNotBlank() && !newName.contains('/')) { "非法名称：$newName" }
        val renamed = DocumentsContract.renameDocument(
            appContext.contentResolver, doc.uri, newName
        ) ?: throw IllegalStateException("重命名失败")
        runCatching { appContext.contentResolver.takePersistableUriPermission(renamed, 0) }
    }

    override suspend fun listDir(rel: String): List<String> = withContext(Dispatchers.IO) {
        val dir = if (rel.isBlank()) root else resolveDoc(rel)
        require(dir.isDirectory) { "不是目录：$rel" }
        dir.listFiles()
            .sortedWith(compareByDescending<DocumentFile> { it.isDirectory }.thenBy { it.name?.lowercase() ?: "" })
            .mapNotNull { f ->
                f.name?.let { n -> if (f.isDirectory) "$n/" else n }
            }
    }

    override suspend fun walk(maxEntries: Int): List<FileEntry> = withContext(Dispatchers.IO) {
        val out = ArrayList<FileEntry>()
        val queue = ArrayDeque<Pair<DocumentFile, String>>()
        queue.add(root to "")
        while (queue.isNotEmpty() && out.size < maxEntries) {
            val (dir, prefix) = queue.removeFirst()
            for (f in dir.listFiles()) {
                if (out.size >= maxEntries) break
                val name = f.name ?: continue
                if (prefix.isEmpty() && name == ".git") continue
                val path = if (prefix.isEmpty()) name else "$prefix/$name"
                if (f.isDirectory) queue.add(f to path)
                else out.add(FileEntry(path, f.length(), f.lastModified()))
            }
        }
        out
    }

    override fun shellWorkdir(): File? = null
}
