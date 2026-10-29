package com.haoai.agent.platform.llama

import java.io.File
import java.io.RandomAccessFile

/**
 * GGUF 头部元数据轻解析（2026-10-30 端侧推理改版）：只为模型库徽标读
 * general.architecture / general.size_label / general.file_type / general.name，
 * 读到即止，绝不碰 tokenizer 等大数组（按字节宽度 seek 跳过）。
 * 文件非 GGUF / 头被截断时返回 null，调用方按「无法识别」展示，不阻塞入库。
 */
object GgufMeta {

    /** 从文件名/模型名推导量化名（Q4_K_M 等）；文件名是社区通行真源，file_type 只作兜底。 */
    private val QUANT_IN_NAME = Regex("(?i)(IQ\\d+_[A-Z0-9]+|Q\\d+_K_?(?:S|M|L|_S|_M|_L)?|Q\\d+_\\d|F16|BF16|F32)")

    data class Info(
        val architecture: String?,
        val sizeLabel: String?,
        val fileType: Int?,
        val name: String?,
    ) {
        val quantName: String?
            get() = QUANT_IN_NAME.find(name ?: "")?.value?.uppercase()
    }

    fun parse(file: File): Info? = runCatching {
        RandomAccessFile(file, "r").use { f ->
            if (f.length() < 32) return@use null
            val magic = ByteArray(4)
            if (f.read(magic) < 4 ||
                !magic.contentEquals(byteArrayOf(0x47, 0x47, 0x55, 0x46))
            ) return@use null
            val version = readLeInt(f)
            // v1 是 2023-08 上线前夜的过渡格式，字段宽度不同（u32 计数），不认
            if (version < 2 || version > 3) return@use null
            f.skipBytes(16) // tensor_count(u64) + kv_count(u64)：kv 循环自带边界，无需读
            var arch: String? = null
            var sizeLabel: String? = null
            var fileType: Int? = null
            var name: String? = null
            fun done() = arch != null && sizeLabel != null && fileType != null && name != null
            // 头部元数据通常 < 1KB；大数组（tokenizer）一律按宽度 seek 跳过。上限 4096 个 kv。
            repeat(4096) {
                if (done()) return@use Info(arch, sizeLabel, fileType, name)
                val key = readString(f) ?: return@use Info(arch, sizeLabel, fileType, name)
                when (readLeInt(f)) {
                    0, 1, 7 -> f.skipBytes(1)
                    2, 3 -> f.skipBytes(2)
                    4 -> if (key == "general.file_type") fileType = readLeInt(f) else f.skipBytes(4)
                    5, 6 -> f.skipBytes(4)
                    8 -> when (key) {
                        "general.architecture" -> arch = readString(f)
                        "general.size_label" -> sizeLabel = readString(f)
                        "general.name" -> name = readString(f)
                        else -> {
                            val n = readStringLength(f)
                            if (n >= 0) f.seek(f.filePointer + n) else return@use Info(arch, sizeLabel, fileType, name)
                        }
                    }
                    9 -> skipArray(f)
                    10, 11, 12 -> f.skipBytes(8)
                    else -> return@use Info(arch, sizeLabel, fileType, name)
                }
            }
            Info(arch, sizeLabel, fileType, name)
        }
    }.getOrNull()

    private fun readLeInt(f: RandomAccessFile): Int {
        val b = ByteArray(4)
        if (f.read(b) < 4) error("eof")
        return (b[0].toInt() and 0xFF) or
            ((b[1].toInt() and 0xFF) shl 8) or
            ((b[2].toInt() and 0xFF) shl 16) or
            ((b[3].toInt() and 0xFF) shl 24)
    }

    /** GGUF string = u64 长度 + UTF-8。返回长度；异常（>1MB/截断）返回 -1。 */
    private fun readStringLength(f: RandomAccessFile): Long {
        val b = ByteArray(8)
        if (f.read(b) < 8) return -1
        var v = 0L
        for (i in 7 downTo 0) v = (v shl 8) or (b[i].toLong() and 0xFF)
        return if (v in 1..(1 shl 20)) v else -1
    }

    private fun readString(f: RandomAccessFile): String? {
        val len = readStringLength(f)
        if (len < 0) return null
        val buf = ByteArray(len.toInt())
        if (f.read(buf) < len) return null
        return String(buf, Charsets.UTF_8)
    }

    /** 数组 = 类型(u32) + 数量(u64) + 元素。逐元素按宽度跳，嵌套字符串/数组递归。 */
    private fun skipArray(f: RandomAccessFile) {
        val elemType = readLeInt(f)
        val b = ByteArray(8)
        if (f.read(b) < 8) error("eof")
        var count = 0L
        for (i in 7 downTo 0) count = (count shl 8) or (b[i].toLong() and 0xFF)
        // tokenizer 词表量级 ~150k，防御上限给到千万级；超限视为损坏
        if (count > 10_000_000) error("array too large")
        repeat(count.toInt()) {
            when (elemType) {
                0, 1, 7 -> f.skipBytes(1)
                2, 3 -> f.skipBytes(2)
                4, 5, 6 -> f.skipBytes(4)
                8 -> {
                    val n = readStringLength(f)
                    if (n < 0) error("eof in array string")
                    f.seek(f.filePointer + n)
                }
                9 -> skipArray(f)
                10, 11, 12 -> f.skipBytes(8)
                else -> error("bad elem type $elemType")
            }
        }
    }
}
