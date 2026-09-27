package com.haoai.pc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File

/**
 * 磁盘上的会话索引。
 *
 * 单独一个文件而不是塞进 `Sessions`：`Sessions` 管的是**内存里正在跑的**引擎，
 * 这里管的是**落库的历史**。分开的好处是重启后还能翻旧会话接着聊 ——
 * 手机端从第一天就有这个能力（侧边栏那 48 条会话），PC 端少了它就只能一次性对话。
 */
object SessionIndex {

    data class Meta(
        val id: String,
        val title: String,
        val workspace: String,
        val mode: String,
        val updated: Long,
        val messages: Int,
        val prompt: Long,
        val completion: Long,
        val file: File
    )

    fun fileFor(id: String): File = File(Env.sessionsDir, "pc-$id.json")

    fun list(limit: Int = 50): List<Meta> {
        val files = Env.sessionsDir.listFiles { f -> f.isFile && f.name.startsWith("pc-") && f.name.endsWith(".json") }
            ?: return emptyList()
        return files.mapNotNull { f -> read(f) }.sortedByDescending { it.updated }.take(limit)
    }

    fun read(f: File): Meta? = runCatching {
        val o = Json.parseToJsonElement(f.readText()).jsonObject
        Meta(
            id = o["id"]?.jsonPrimitive?.content ?: f.name.removePrefix("pc-").removeSuffix(".json"),
            title = o["title"]?.jsonPrimitive?.content ?: "（无标题）",
            workspace = o["workspace"]?.jsonPrimitive?.content ?: "",
            mode = o["mode"]?.jsonPrimitive?.content ?: "ask",
            updated = o["updated"]?.jsonPrimitive?.content?.toLongOrNull() ?: f.lastModified(),
            messages = o["messages"]?.jsonArray?.size ?: 0,
            prompt = o["promptTokens"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
            completion = o["completionTokens"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
            file = f
        )
    }.getOrNull()

    /**
     * 搜一条会话：标题命中给标题，否则翻消息正文，返回命中处附近的一小段（给列表当摘要）。
     *
     * 为什么要翻正文：用户找旧会话时记得的是"我那天问过的那个报错"，
     * 而标题往往只是第一句话的前 24 个字，很多会话根本不含那个词。
     *
     * 读整个文件而不是走索引：会话文件都是几十 KB 量级，400 条全扫一遍也就几十毫秒，
     * 为此维护一份倒排索引是不划算的复杂度（而且它会和"改名只动 title 一个字段"打架）。
     */
    fun match(meta: Meta, needleLower: String): String? {
        if (needleLower.isBlank()) return null
        if (meta.title.lowercase().contains(needleLower)) return "标题命中"
        val text = runCatching {
            val f = meta.file
            if (!f.isFile || f.length() > 4L * 1024 * 1024) return null
            f.readText()
        }.getOrNull() ?: return null
        val o = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        o["messages"]?.jsonArray?.forEachIndexed { i, el ->
            val m = runCatching { el.jsonObject }.getOrNull() ?: return@forEachIndexed
            val body = m["content"]?.jsonPrimitive?.contentOrNull ?: return@forEachIndexed
            val at = body.lowercase().indexOf(needleLower)
            if (at >= 0) {
                val from = maxOf(0, at - 24)
                val snippet = body.substring(from, minOf(body.length, at + needleLower.length + 40))
                    .replace('\n', ' ')
                return "${m["role"]?.jsonPrimitive?.contentOrNull ?: "?"} 第 ${i + 1} 条：…$snippet…"
            }
        }
        return null
    }

    /** 恢复一个会话的壳（历史由 Engine 的 init 自己从同一个文件读回来）。 */
    fun restore(meta: Meta, fallbackWorkspace: File): Session {
        val ws = runCatching { File(meta.workspace) }.getOrNull()?.takeIf { it.isDirectory } ?: fallbackWorkspace
        val s = Session(meta.id, ws)
        s.mode = meta.mode
        s.title.set(meta.title)
        return s
    }

    /** 改标题。只动 `title` 一个字段，其余原样写回。 */
    fun rename(id: String, newTitle: String): Boolean {
        val f = fileFor(id)
        if (!f.isFile) return false
        val clean = newTitle.trim().replace('\n', ' ').take(60)
        if (clean.isEmpty()) return false
        return runCatching {
            val o = Json.parseToJsonElement(f.readText()).jsonObject
            val patched = JsonObject(o.toMutableMap().apply { put("title", JsonPrimitive(clean)) })
            f.writeText(patched.toString())
            true
        }.getOrDefault(false)
    }

    /**
     * 删会话 —— 其实是**移进回收目录**，不是就地抹掉。
     *
     * 理由：会话文件里是用户与 agent 的全部过程记录，误删一次的成本远高于多留一份垃圾；
     * 而 `.trash/` 在 `sessions/` 下面，`list()` 的前缀过滤看不到它，界面上就是"删掉了"。
     * 想彻底清空，删 `%LOCALAPPDATA%\HaoAI\sessions\.trash` 这个目录即可。
     */
    fun delete(id: String): Boolean {
        val f = fileFor(id)
        if (!f.isFile) return false
        return runCatching {
            val trash = File(Env.sessionsDir, ".trash").apply { mkdirs() }
            val dest = File(trash, "${System.currentTimeMillis()}-${f.name}")
            f.renameTo(dest) || run { f.copyTo(dest, overwrite = true); f.delete() }
        }.getOrDefault(false)
    }

    fun trashDir(): File = File(Env.sessionsDir, ".trash")

    /**
     * 回收站里有什么。文件名是 `<删除时间>-pc-<id>.json`，标题/条数从文件内容里读。
     *
     * 为什么界面上要看得见：删除是移进 `.trash` 而不是抹掉，但**没有入口的"可恢复"
     * 等于没有** —— 之前只有 tooltip 上那半句"（移进 sessions/.trash，可恢复）"，
     * 用户删错一条就只能去开文件管理器。
     */
    fun listTrash(limit: Int = 60): List<Meta> {
        val files = trashDir().listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return emptyList()
        return files.mapNotNull { read(it) }.sortedByDescending { it.updated }.take(limit)
    }

    /**
     * 把一条会话从回收站放回原位，返回它的 id；放不回来说明原因。
     *
     * `name` 是**客户端给的**，所以只认回收站里真实存在的那个文件名：
     * 先按 canonical 判还在不在 `.trash` 目录内，否则 `../../settings.json` 这种
     * 名字能把任意文件搬进 sessions/ 目录。
     */
    fun untrash(name: String): Result<String> {
        val dir = trashDir().canonicalFile
        val src = File(dir, name)
        if (!src.isFile || !src.canonicalFile.startsWith(dir)) return Result.failure(Exception("回收站里没有这个文件"))
        val meta = read(src) ?: return Result.failure(Exception("这个文件读不出会话内容"))
        val dest = fileFor(meta.id)
        if (dest.exists()) return Result.failure(Exception("已经有一条同 id 的会话在外面，放回会覆盖它"))
        return runCatching {
            if (!(src.renameTo(dest) || run { src.copyTo(dest, overwrite = false); src.delete() })) {
                throw Exception("搬不动")
            }
            meta.id
        }
    }

    /** 彻底删掉回收站里的某一条（用户点了"清空"才走到这）。 */
    fun purge(name: String): Boolean {
        val dir = trashDir().canonicalFile
        val f = File(dir, name)
        return f.isFile && f.canonicalFile.startsWith(dir) && f.delete()
    }
}
