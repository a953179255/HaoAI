package com.haoai.pc

import java.io.File
import java.util.Base64

/**
 * 图片进上下文的那条路（OpenAI 兼容的 `image_url` + data URL）。
 *
 * 为什么要有这一份：`screen capture` 与浏览器截图一直只把 PNG **写到磁盘上、回一个路径**，
 * 模型拿到的是一串文件名 —— 于是"屏幕理解"这件事其实是模型在猜路径背后是什么。
 * 附件里那张截图也一样：界面注释写着"视觉输入走同一条路"，而那条路以前根本不存在。
 *
 * 两个刻意的做法：
 * - **历史里存路径，不存 base64**。一张 2.8 MB 的截图编码后 3.7 MB，落进会话文件就是
 *   每次截图多付 3.7 MB 磁盘与下次加载时的内存；路径只有几十字节，而图片本来就在工作区里。
 * - **发请求时才编码**，并且认不出类型/超尺寸就**跳过并在正文里说明**，
 *   而不是把请求整个弄坏：一条坏图不该让整轮对话发不出去。
 */
object Images {
    /** 单个文件编码上限：再大的图发给模型也是白付 token（网关普遍还会自己缩）。 */
    const val MAX_BYTES = 6_000_000L

    /** 按魔数认类型。扩展名会骗人（浏览器另存为经常给错后缀），魔数不会。 */
    fun mime(f: File): String? = runCatching {
        val head = ByteArray(12)
        f.inputStream().use { it.read(head) }
        when {
            head.startsWith(bytes(0x89, 'P'.code, 'N'.code, 'G'.code)) -> "image/png"
            head.startsWith(bytes(0xFF, 0xD8, 0xFF)) -> "image/jpeg"
            head.startsWith("GIF8".toByteArray()) -> "image/gif"
            head.startsWith("RIFF".toByteArray()) &&
                head.copyOfRange(8, 12).toString(Charsets.ISO_8859_1) == "WEBP" -> "image/webp"
            else -> null
        }
    }.getOrNull()

    private fun bytes(vararg parts: Int) = ByteArray(parts.size) { parts[it].toByte() }

    private fun ByteArray.startsWith(p: ByteArray): Boolean =
        p.size <= size && p.indices.all { this[it] == p[it] }

    private fun ByteArray.startsWith(p: String): Boolean = startsWith(p.toByteArray())

    /** 能不能发出去：存在、是文件、类型认得、大小在上限内。 */
    fun usable(path: String): File? {
        val f = File(path)
        if (!f.isFile) return null
        if (f.length() > MAX_BYTES) return null
        return if (mime(f) == null) null else f
    }

    /** `data:image/png;base64,…`；文件读不出来就回 null（调用方负责在正文里说明）。 */
    fun dataUrl(path: String): String? {
        val f = usable(path) ?: return null
        val mime = mime(f) ?: return null
        val b64 = runCatching { Base64.getEncoder().encodeToString(f.readBytes()) }.getOrNull() ?: return null
        return "data:$mime;base64,$b64"
    }

    /**
     * 这张图大概要占多少"字"（上下文占用那圈要用它来算，不然按路径算就是骗人：
     * 一张截图的真实成本是几 MB 的 base64，不是 40 个字符的路径）。
     */
    fun wireChars(path: String): Int {
        val f = File(path)
        if (!f.isFile) return 0
        return ((minOf(f.length(), MAX_BYTES) + 2) / 3 * 4).toInt() + 24
    }
}
