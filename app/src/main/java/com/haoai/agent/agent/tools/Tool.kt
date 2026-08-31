package com.haoai.agent.agent.tools

import com.haoai.agent.agent.memory.DailyJournal
import com.haoai.agent.agent.memory.MemoryBank
import com.haoai.agent.platform.FileBackend
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

data class ToolContext(
    val backend: FileBackend?,
    val shellDir: java.io.File?,
    val todoStore: TodoStore,
    val appFilesDir: java.io.File,
    val sessionId: String = "",
    val memoryBank: MemoryBank? = null,
    val journal: DailyJournal? = null,
    val depth: Int = 0,
    val httpClient: okhttp3.OkHttpClient? = null,
    val appContext: android.content.Context? = null,
    /** 动态渲染自身运行状态（app_status 工具数据源），由 VM 层注入。 */
    val statusProvider: (() -> String)? = null,
    /** 白名单设置修改（update_settings 工具），由 VM 层注入。 */
    val configMutator: ((kotlinx.serialization.json.JsonObject) -> String)? = null,
    /** 工具状态变更回调（todo 修改后刷新 UI）。 */
    val onToolChange: (() -> Unit)? = null,
    /** 4.3 虚拟屏后台自动化总开关（设置页），关闭时 vscreen_* 不注册进工具清单。 */
    val vscreenEnabled: Boolean = false
)

data class ToolResult(
    val content: String,
    val isError: Boolean = false,
    /** 非空时引擎在工具结果后追加一条带图 user 消息（browser_screenshot 图像注入通路）。 */
    val imageDataUrl: String? = null
)

interface Tool {
    val name: String
    val description: String
    val parameters: JsonObject
    suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult
}

internal fun JsonObject.primitive(key: String): JsonPrimitive? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }

internal fun JsonObject.reqString(key: String): String {
    val v = primitive(key)?.contentOrNull
        ?: throw IllegalArgumentException("缺少必填参数「$key」")
    require(v.isNotBlank()) { "参数「$key」不能为空" }
    return v
}

internal fun JsonObject.optString(key: String, def: String = ""): String =
    primitive(key)?.contentOrNull ?: def

internal fun JsonObject.optInt(key: String): Int? =
    primitive(key)?.intOrNull

internal fun JsonObject.optDouble(key: String): Double? =
    primitive(key)?.doubleOrNull

internal fun JsonObject.optBool(key: String, def: Boolean = false): Boolean =
    primitive(key)?.booleanOrNull ?: def

fun globToRegex(pattern: String): Regex {
    val sb = StringBuilder("^")
    var i = 0
    while (i < pattern.length) {
        val c = pattern[i]
        when {
            c == '*' && i + 1 < pattern.length && pattern[i + 1] == '*' -> {
                if (i + 2 < pattern.length && pattern[i + 2] == '/') {
                    sb.append("(?:.*/)?")
                    i += 2
                } else {
                    sb.append(".*")
                    i++
                }
            }
            c == '*' -> sb.append("[^/]*")
            c == '?' -> sb.append("[^/]")
            else -> sb.append(Regex.escape(c.toString()))
        }
        i++
    }
    sb.append("$")
    return Regex(sb.toString())
}

object TextCap {

    fun middle(text: String, max: Int): String {
        if (text.length <= max) return text
        val head = (max * 0.65).toInt()
        val tail = (max * 0.25).toInt()
        val omitted = text.length - head - tail
        return text.take(head) + "\n…［中间省略约 $omitted 字符］…\n" + text.takeLast(tail)
    }

    fun tail(text: String, max: Int): String =
        if (text.length <= max) text else "…" + text.takeLast(max)

    fun head(text: String, max: Int): String =
        if (text.length <= max) text else text.take(max) + "…"
}
