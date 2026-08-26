package com.haoai.agent.data

import kotlinx.serialization.json.Json
import java.io.File

object HaoJson {
    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
        explicitNulls = false
    }

    /**
     * 原子写入：先写临时文件再 rename，避免进程中途被杀留下截断的 JSON；
     * 同时把上一份完整内容保留为 .bak，供损坏时恢复。
     */
    fun writeAtomic(file: File, text: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        if (file.exists()) {
            val bak = File(file.parentFile, file.name + ".bak")
            bak.delete()
            if (!file.renameTo(bak)) {
                // rename 失败（极少见）直接覆盖写，保底不丢新数据
                file.writeText(text)
                tmp.delete()
                return
            }
        }
        if (!tmp.renameTo(file)) {
            runCatching { tmp.copyTo(file, overwrite = true) }
            tmp.delete()
        }
    }

    /** 读取：主文件缺失/损坏时回退 .bak；两者皆不可用返回 null。 */
    fun readTextSafe(file: File): String? {
        if (file.exists()) {
            runCatching { return file.readText() }
        }
        val bak = File(file.parentFile, file.name + ".bak")
        if (bak.exists()) {
            runCatching { return bak.readText() }
        }
        return null
    }
}
