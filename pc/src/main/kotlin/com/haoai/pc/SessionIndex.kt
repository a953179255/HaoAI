package com.haoai.pc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

    /** 恢复一个会话的壳（历史由 Engine 的 init 自己从同一个文件读回来）。 */
    fun restore(meta: Meta, fallbackWorkspace: File): Session {
        val ws = runCatching { File(meta.workspace) }.getOrNull()?.takeIf { it.isDirectory } ?: fallbackWorkspace
        val s = Session(meta.id, ws)
        s.mode = meta.mode
        s.title.set(meta.title)
        return s
    }
}
