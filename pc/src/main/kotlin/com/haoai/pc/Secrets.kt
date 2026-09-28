package com.haoai.pc

import java.io.File

/**
 * 凭据条目：把"哪把 key 在哪个文件里、现在有没有设、什么时候改的、怎么撤掉"说清楚。
 *
 * 为什么单独一层：密钥一直刻意**不进 PcSettings**（那份会被 `GET /api/settings` 整体发回前端），
 * 散在两个裸文件里。结果是界面上只能"写"不能"看"也不能"删" ——
 * 换 key 时不知道旧的还在不在，撤销一把泄露的 key 只能回 CLI 手删文件。
 *
 * 这一层的规矩：
 * 1. 读接口只回**掩码**，绝不回明文；`GET /api/settings` 连掩码都不回（它每次都跟着界面一起拉）。
 * 2. 撤掉就是删文件，不是写空串 —— 留着空文件，`hasKey` 会算成"已设置"，那是假话。
 * 3. 一把 key 只有一个写入入口（这里），不再和 `/api/settings` 的 `key` 字段并存，
 *    否则"改了没生效"要查两处才知道是哪条路没走。
 */
object Secrets {

    data class Slot(val id: String, val label: String, val hint: String, val file: File)

    /** 现在有几把 key：模型网关与搜索服务。加新 key 就在这里加一行。 */
    fun slots(): List<Slot> = listOf(
        Slot("apikey", "模型网关", "发给网关的 Bearer 密钥（Authorization 头）", Env.apiKeyFile),
        Slot("searchkey", "搜索服务", "web_search 选了博查/智谱这类服务时才需要", Env.searchKeyFile)
    )

    fun find(id: String): Slot? = slots().firstOrNull { it.id == id }

    fun read(slot: Slot): String =
        runCatching { if (slot.file.isFile) slot.file.readText().trim() else "" }.getOrDefault("")

    /** 空值 = 撤掉（删文件）。写失败要如实报出去，密钥存不下是最不该静默的一类错。 */
    fun set(slot: Slot, value: String): Result<Unit> {
        val v = value.trim()
        return runCatching {
            if (v.isEmpty()) { if (slot.file.isFile) slot.file.delete(); return@runCatching }
            slot.file.parentFile?.mkdirs()
            slot.file.writeText(v)
        }
    }

    /**
     * 只够认出"是哪一把"：前 2 后 2。
     *
     * 短 key 整个遮掉 —— 一把 6 位的 key 露前 2 后 2 就等于把中间 2 位猜的空间砍到最小，
     * 那不是提示那是漏。
     */
    fun mask(v: String): String = when {
        v.isBlank() -> ""
        v.length < 10 -> "••••••"
        else -> v.take(2) + "…" + v.takeLast(2) + "（共 " + v.length + " 位）"
    }

    fun json(): String {
        val items = slots().joinToString(",") { s ->
            val v = read(s)
            val set = v.isNotBlank()
            """{"id":${q(s.id)},"label":${q(s.label)},"hint":${q(s.hint)},""" +
                """"path":${q(s.file.absolutePath)},"set":$set,""" +
                """"mask":${q(if (set) mask(v) else "")},""" +
                """"updated":${if (set && s.file.isFile) s.file.lastModified() else 0}}"""
        }
        return """{"ok":true,"items":[$items]}"""
    }

    private fun q(s: String): String = "\"" +
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "") + "\""
}
