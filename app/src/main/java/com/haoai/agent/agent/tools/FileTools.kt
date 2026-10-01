package com.haoai.agent.agent.tools

import com.haoai.agent.platform.FileBackend
import com.haoai.agent.platform.FileEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

class ReadTool : Tool {

    override val name = "read"
    override val desc =
        "读取工作空间内文本文件（带行号），或列出目录内容。支持 offset/limit 行范围，大文件自动截断。"
    override val params = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("path") {
                put("type", "string")
            }
            putJsonObject("offset") { put("type", "integer") }
            putJsonObject("limit") { put("type", "integer") }
        }
        putJsonArray("required") { add(JsonPrimitive("path")) }
    }

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val backend = ctx.backend ?: return ToolResult("未绑定工作空间", true)
        val path = args.reqString("path")
        return try {
            withContext(Dispatchers.IO) { readFile(backend, path, args) }
        } catch (dir: IsDirectorySignal) {
            try {
                withContext(Dispatchers.IO) { listTree(backend, path.trimEnd('/')) }
            } catch (e: Exception) {
                ToolResult("列目录失败：${e.message}", true)
            }
        } catch (e: Exception) {
            ToolResult("读取失败：${e.message}", true)
        }
    }

    private class IsDirectorySignal(m: String) : Exception(m)

    private suspend fun readFile(backend: FileBackend, path: String, args: JsonObject): ToolResult {
        val text = try {
            backend.readText(path)
        } catch (e: IllegalStateException) {
            if (e.message?.contains("目录") == true) throw IsDirectorySignal(e.message ?: "目录")
            throw e
        }
        val lines = text.split('\n')
        val offset = (args.optInt("offset") ?: 1).coerceAtLeast(1)
        val limit = (args.optInt("limit") ?: 400).coerceIn(1, 1000)
        if (offset > lines.size) return ToolResult("offset 超出总行数 ${lines.size}", true)
        val end = minOf(lines.size, offset + limit - 1)
        val sb = StringBuilder()
        sb.append("[${path}] 共 ${lines.size} 行，显示 $offset-$end\n")
        for (i in offset..end) {
            sb.append(String.format("%5d: %s%n", i, TextCap.head(lines[i - 1], 500)))
        }
        return ToolResult(sb.toString().trimEnd())
    }

    private suspend fun listTree(backend: FileBackend, rel: String): ToolResult {
        val entries = backend.listDir(rel.ifBlank { "" })
        if (entries.isEmpty()) return ToolResult("(空目录) $rel")
        val shown = entries.take(200)
        val more = if (entries.size > 200) "\n… 共 ${entries.size} 项" else ""
        return ToolResult("$rel/\n" + shown.joinToString("\n") + more)
    }
}

class WriteTool : Tool {

    override val name = "write"
    override val desc = "新建或覆盖工作空间内的文件（UTF-8 文本，最大 256KB）。"
    override val params = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("path") { put("type", "string") }
            putJsonObject("content") { put("type", "string") }
        }
        putJsonArray("required") { add(JsonPrimitive("path")); add(JsonPrimitive("content")) }
    }

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val backend = ctx.backend ?: return ToolResult("未绑定工作空间", true)
        val path = args.reqString("path")
        val content = args.optString("content")
        val bytes = content.toByteArray(Charsets.UTF_8)
        if (bytes.size > 256 * 1024) return ToolResult("内容超过 256KB 上限（${bytes.size} 字节）", true)
        return try {
            withContext(Dispatchers.IO) { backend.writeText(path, content) }
            ToolResult("已写入 $path（${bytes.size} 字节）")
        } catch (e: Exception) {
            ToolResult("写入失败：${e.message}", true)
        }
    }
}

class EditTool : Tool {

    override val name = "edit"
    override val desc =
        "精确替换文件中的文本片段。old_string 必须与文件内容完全一致（含缩进换行）；出现多次时需提供更多上下文或设置 replace_all=true。"
    override val params = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("path") { put("type", "string") }
            putJsonObject("old_string") { put("type", "string") }
            putJsonObject("new_string") { put("type", "string") }
            putJsonObject("replace_all") { put("type", "boolean") }
        }
        putJsonArray("required") {
            add(JsonPrimitive("path"))
            add(JsonPrimitive("old_string"))
            add(JsonPrimitive("new_string"))
        }
    }

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val backend = ctx.backend ?: return ToolResult("未绑定工作空间", true)
        val path = args.reqString("path")
        val old = args.optString("old_string")
        val new = args.optString("new_string")
        val replaceAll = args.optBool("replace_all")
        if (old.isEmpty()) return ToolResult("old_string 不能为空", true)
        return try {
            withContext(Dispatchers.IO) {
                val text = backend.readText(path)
                val count = text.split(old).size - 1
                when {
                    count == 0 -> ToolResult("未找到目标片段。请先用 read 确认最新内容（注意空白与换行差异）。", true)
                    count > 1 && !replaceAll -> ToolResult(
                        "该片段出现 $count 次，请补充上下文使其唯一，或设置 replace_all=true。",
                        true
                    )
                    else -> {
                        backend.writeText(path, if (replaceAll) text.replace(old, new) else text.replaceFirst(old, new))
                        ToolResult("已替换 $count 处：$path")
                    }
                }
            }
        } catch (e: Exception) {
            ToolResult("编辑失败：${e.message}", true)
        }
    }
}

class GrepTool : Tool {

    override val name = "grep"
    override val desc = "在工作空间内按正则搜索文件内容，返回「路径:行号: 内容」。可用 glob 过滤文件名。"
    override val params = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("pattern") { put("type", "string") }
            putJsonObject("glob") { put("type", "string") }
            putJsonObject("max_results") { put("type", "integer") }
        }
        putJsonArray("required") { add(JsonPrimitive("pattern")) }
    }

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val backend = ctx.backend ?: return ToolResult("未绑定工作空间", true)
        val regex = try {
            Regex(args.reqString("pattern"))
        } catch (e: Exception) {
            return ToolResult("正则无效：${e.message}", true)
        }
        val globRegex = args.optString("glob").takeIf { it.isNotBlank() }?.let { globToRegex(it) }
        val max = (args.optInt("max_results") ?: 50).coerceIn(1, 200)

        return try {
            withContext(Dispatchers.IO) {
                val hits = StringBuilder()
                var count = 0
                loop@ for (entry in backend.walk(maxEntries = 6000)) {
                    if (!matchable(entry)) continue
                    if (globRegex != null &&
                        !globRegex.matches(entry.path) &&
                        !globRegex.matches(entry.path.substringAfterLast('/'))
                    ) continue
                    val content = runCatching { backend.readText(entry.path, 1024 * 1024) }.getOrNull() ?: continue
                    val lines = content.split('\n')
                    for (i in lines.indices) {
                        if (regex.containsMatchIn(lines[i])) {
                            hits.append(entry.path).append(':').append(i + 1).append(": ")
                                .append(TextCap.head(lines[i].trim(), 240)).append('\n')
                            count++
                            if (count >= max) break@loop
                        }
                    }
                }
                if (count == 0) ToolResult("无匹配")
                else ToolResult(hits.toString().trimEnd() + if (count >= max) "\n（已达结果上限 $max）" else "")
            }
        } catch (e: Exception) {
            ToolResult("搜索失败：${e.message}", true)
        }
    }

    private fun matchable(e: FileEntry): Boolean {
        if (e.size > 1_000_000 || e.size == 0L) return false
        val ext = e.path.substringAfterLast('.', "").lowercase()
        return ext !in FileBackend.BINARY_EXTENSIONS && (ext in FileBackend.TEXT_EXTENSIONS || ext.isEmpty() || ext.length <= 6)
    }
}

class GlobTool : Tool {

    override val name = "glob"
    override val desc =
        "按通配模式查找文件（支持 **、*、?），按最近修改排序。示例：**/*.kt、src/*.java、*.md"
    override val params = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("pattern") { put("type", "string") }
        }
        putJsonArray("required") { add(JsonPrimitive("pattern")) }
    }

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val backend = ctx.backend ?: return ToolResult("未绑定工作空间", true)
        val pattern = args.reqString("pattern")
        val regex = globToRegex(pattern)
        return try {
            withContext(Dispatchers.IO) {
                val matched = backend.walk()
                    .filter { e ->
                        regex.matches(e.path) ||
                            ('/' !in pattern && regex.matches(e.path.substringAfterLast('/')))
                    }
                    .sortedByDescending { it.lastModified }
                    .take(300)
                if (matched.isEmpty()) ToolResult("无匹配文件")
                else ToolResult(matched.joinToString("\n") { it.path })
            }
        } catch (e: Exception) {
            ToolResult("查找失败：${e.message}", true)
        }
    }
}
