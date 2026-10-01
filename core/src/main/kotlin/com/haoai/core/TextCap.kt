package com.haoai.core

/**
 * 两端共用的截断口径（B15）：手机端原版为基准 —— 它比 PC 端旧版多了两件要紧事：
 * **UTF-16 代理对安全**（切在高代理上回退一位，宁少勿残）与 `tail`。
 *
 * "两端截断口径必须一致" 是老规矩（PC 端 Tools.kt 的注释原话），
 * 口径分家的代价是同一段溢出内容在电脑上是三行、在手机上是乱码半个 emoji。
 * PC 端从此经 typealias 用这一份（原地同名，调用点一个不改）。
 */
object TextCap {

    fun middle(text: String, max: Int): String {
        if (text.length <= max) return text
        val head = (max * 0.65).toInt()
        val tail = (max * 0.25).toInt()
        val omitted = text.length - head - tail
        return text.takeSafe(head) + "\n…［中间省略约 $omitted 字符］…\n" + text.takeLastSafe(tail)
    }

    fun tail(text: String, max: Int): String =
        if (text.length <= max) text else "…" + text.takeLastSafe(max)

    fun head(text: String, max: Int): String =
        if (text.length <= max) text else text.takeSafe(max) + "…"
}

/** UTF-16 安全截断（防切断 emoji 代理对）：截断点落在高代理上时回退一位，宁少勿残。 */
fun String.takeSafe(n: Int): String {
    if (length <= n) return this
    if (n <= 0) return ""
    return substring(0, if (Character.isHighSurrogate(this[n - 1])) n - 1 else n)
}

/** UTF-16 安全尾部截断：起点落在低代理上时丢弃孤儿半个 emoji，宁少勿残。 */
fun String.takeLastSafe(n: Int): String {
    if (length <= n) return this
    val start = length - n
    return substring(if (Character.isLowSurrogate(this[start])) start + 1 else start)
}
