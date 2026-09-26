package com.haoai.pc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File

/**
 * PC 端的设置。字段命名与手机端 `AppSettings` 尽量对齐（baseUrl/model/permissionMode/
 * enabledFlags…），这样将来抽 `:core` 时是一份表，不是两份。
 *
 * 落库口径与手机端一致：只存"与默认值不同"的 flag 覆盖，见 [HaoFlag.compactOverrides]。
 */
data class PcSettings(
    val providerName: String = "sensenova",
    val baseUrl: String = "https://token.sensenova.cn/v1",
    val model: String = "glm-5.2",
    val workspace: String = "",
    /** plan=只读规划；ask=写与命令要人批；auto=全自动。默认 ask —— PC 能执行任意命令。 */
    val permissionMode: String = "ask",
    val maxTurns: Int = 60,
    val temperature: Double = 0.3,
    /** 工具结果落库上限 / 发请求上限，与手机端 STORED_CAP / REQ_CAP 同源同值。 */
    val storedCap: Int = 16_000,
    val reqCap: Int = 4_000,
    val flags: Map<String, Boolean> = emptyMap()
) {
    fun workspaceFile(): File {
        val t = workspace.trim()
        val f = if (t.isEmpty() || t == ".") File(System.getProperty("user.dir")) else File(t)
        return runCatching { f.canonicalFile }.getOrElse { f.absoluteFile }
    }

    companion object {
        val json = Json { ignoreUnknownKeys = true }

        fun load(): PcSettings {
            val f = Env.settingsFile
            if (!f.isFile) return PcSettings()
            return runCatching {
                val o = json.parseToJsonElement(f.readText()).jsonObject
                PcSettings(
                    providerName = o.str("providerName") ?: "sensenova",
                    baseUrl = o.str("baseUrl") ?: "https://token.sensenova.cn/v1",
                    model = o.str("model") ?: "glm-5.2",
                    workspace = o.str("workspace") ?: "",
                    permissionMode = o.str("permissionMode") ?: "ask",
                    maxTurns = o.int("maxTurns") ?: 60,
                    temperature = o.dbl("temperature") ?: 0.3,
                    storedCap = o.int("storedCap") ?: 16_000,
                    reqCap = o.int("reqCap") ?: 4_000,
                    flags = runCatching {
                        o["flags"]?.jsonObject?.mapValues { (_, v) ->
                            v.jsonPrimitive.content == "true"
                        }
                    }.getOrNull() ?: emptyMap()
                )
            }.onFailure { Env.log("settings", "解析失败，退回默认：${it.message}") }.getOrElse { PcSettings() }
        }

        fun save(s: PcSettings) {
            runCatching {
                Env.settingsFile.writeText(
                    buildJsonObject {
                        put("providerName", s.providerName)
                        put("baseUrl", s.baseUrl)
                        put("model", s.model)
                        put("workspace", s.workspace)
                        put("permissionMode", s.permissionMode)
                        put("maxTurns", s.maxTurns)
                        put("temperature", s.temperature)
                        put("storedCap", s.storedCap)
                        put("reqCap", s.reqCap)
                        put(
                            "flags",
                            buildJsonObject { s.flags.forEach { (k, v) -> put(k, v) } }
                        )
                    }.toString().indent()
                )
            }.onFailure { Env.log("settings", "写入失败：${it.message}") }
        }

        private fun String.indent(): String =
            replace("\",\"", "\",\n\"").replace("{\"", "{\n\"").replace("\"}", "\",\n}")
    }
}

/** 与手机端 `HaoFlag` 同名同语义的 PC 侧注册表：新能力先上再关，且能在 UI 里拨。 */
enum class HaoFlag(
    val key: String,
    val title: String,
    val what: String,
    val defaultOn: Boolean
) {
    /** 工具输出超过落库上限时把全文写进工作区 `.haoai-output/`，会话里只留头尾摘要 + 路径。 */
    TOOL_RESULT_SPILL(
        key = "tool_result_spill",
        title = "工具完整输出落文件",
        what = "命令/读取输出过长时，完整内容存进工作区 .haoai-output/，对话里只留头尾摘要和路径。",
        defaultOn = true
    ),

    /** 计划模式：只给只读工具集，写与命令一律拒绝，先出方案。 */
    PLAN_MODE(
        key = "plan_mode",
        title = "计划模式",
        what = "先只读调研并产出方案，经你确认后才允许改文件与执行命令。",
        defaultOn = true
    ),

    /** 破坏性操作前留快照（git 仓库里靠 git 自己，非 git 目录用影子副本）。 */
    SNAPSHOT_BEFORE_WRITE(
        key = "snapshot_before_write",
        title = "改文件前留快照",
        what = "覆盖/删除已有文件前先存一份到 .haoai-snap/，改坏了能回滚。",
        defaultOn = true
    );

    companion object {
        fun byKey(key: String): HaoFlag? = entries.firstOrNull { it.key == key }

        fun enabled(flag: HaoFlag, overrides: Map<String, Boolean>): Boolean =
            overrides[flag.key] ?: flag.defaultOn

        fun compactOverrides(overrides: Map<String, Boolean>): Map<String, Boolean> =
            entries.mapNotNull { flag ->
                val on = overrides[flag.key] ?: return@mapNotNull null
                if (on == flag.defaultOn) null else flag.key to on
            }.toMap()
    }
}

internal fun JsonObject.str(k: String): String? =
    this[k]?.let { if (it is kotlinx.serialization.json.JsonNull) null else it.jsonPrimitive.contentOrNullSafe() }

private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? =
    runCatching { content }.getOrNull()

internal fun JsonObject.int(k: String): Int? = this[k]?.jsonPrimitive?.intOrNull
internal fun JsonObject.dbl(k: String): Double? = this[k]?.jsonPrimitive?.let {
    it.content.toDoubleOrNull()
}

internal fun JsonObject.long(k: String): Long? = this[k]?.jsonPrimitive?.longOrNull
