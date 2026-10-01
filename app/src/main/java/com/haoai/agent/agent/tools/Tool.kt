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

/*
 * B15（两端共用的 :core）：工具契约、结果与截断口径的**定义**都在 `com.haoai.core`。
 * 这里用**原地 typealias**——同包同名，别的包 `import ...agent.tools.Tool / ToolResult /
 * TextCap` 的现有 import 一个都不用改（typealias 的名字同样可被 import）。
 * 取舍记在 core 的 KDoc 里：接口取 suspend（这边引擎是协程）、字段名取 PC 侧的
 * `desc/params/error`（那边位置传参不能动）、TextCap 取这边的原版（代理对安全 + tail）。
 */
typealias Tool = com.haoai.core.AgentTool<ToolContext>
typealias ToolResult = com.haoai.core.ToolResult
typealias TextCap = com.haoai.core.TextCap

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
    /** C6 config_set 落地（JSON patch → 桥校验入库），由 VM 层注入；null=配置修改不可用。 */
    val configMutator: (suspend (kotlinx.serialization.json.JsonObject) -> ToolResult)? = null,
    /** C6 config_get 数据源：渲染当前配置镜像（apiKey 掩码）。 */
    val configRender: (() -> String)? = null,
    /** ask_user 提问门：模型发起"暂停等用户拍板"，VM 层弹卡挂起直到用户回答。
     *  null=当前环境无法提问（定时/工作流等无人值守路径），工具按"自行取默认假设"指引收场。 */
    val askUser: (suspend (AskUserRequest) -> AskUserAnswer)? = null,
    /** 工具状态变更回调（todo 修改后刷新 UI）。 */
    val onToolChange: (() -> Unit)? = null,
    /** 4.3 虚拟屏后台自动化总开关（设置页），关闭时 vscreen_* 不注册进工具清单。 */
    val vscreenEnabled: Boolean = false,
    /** 4.3 虚拟屏画面码率档位（kbps，设置页可选 1500/3000/5000/10000），映射截图分辨率与画质。 */
    val vscreenBitrateKbps: Int = 3000,
    /** E7a 当前工具调用的 call id（executeCall 每次执行前更新），供子代理上报关联 UI 卡片。 */
    val currentCallId: String? = null,
    /** E7a 子代理进度上报（RUNNING/完成/失败 + token 用量），引擎桥接为 SubagentUpdate 事件。 */
    val onSubagentEvent: ((com.haoai.agent.agent.engine.SubagentReport) -> Unit)? = null,
    /** D 检索卫生：同一次任务内 web_fetch/web_search 的 TTL 去重缓存（key → (时间戳, 结果)）。
     *  引擎每回合新建 ToolContext，缓存随任务结束自然失效；容量硬顶防膨胀。 */
    val webCache: java.util.concurrent.ConcurrentHashMap<String, Pair<Long, String>> = java.util.concurrent.ConcurrentHashMap(),
    /**
     * 本轮已经返回过的搜索结果集签名 → 第几次搜索。
     *
     * 与 [webCache] 的区别：webCache 按 **query** 去重，而实测一次 15 轮的调研里 18 个**不同**
     * query 只拿到 5 种结果（换词没带来新页面，模型却一直在换词重试）。所以这里按**结果链接集合**
     * 建索引，命中就直说"这批链接刚给过你"，把无效重试掐掉——它同时是 token 的主要浪费源。
     */
    val searchSigs: java.util.concurrent.ConcurrentHashMap<String, Int> = java.util.concurrent.ConcurrentHashMap(),
    /**
     * 本轮抓过的页面开头 160 字 → 来源 url。
     * 不同 URL 抓回来的开头一字不差，说明本地挑容器挑到的是站点模板而不是文章，
     * 拿它当信号触发一次远端 reader 兜底（见 WebFetchTool）。
     */
    val fetchHeads: java.util.concurrent.ConcurrentHashMap<String, String> = java.util.concurrent.ConcurrentHashMap(),
    /**
     * 本轮已经失败过的搜索/抓取引擎 → 失败原因。国内实测：DuckDuckGo 不是"立刻报错"而是
     * 挂十几秒才超时，不记下来就会每次搜索都再等一遍（见 WebSearchTool）。
     */
    val deadEngines: java.util.concurrent.ConcurrentHashMap<String, String> = java.util.concurrent.ConcurrentHashMap(),
    /** 「设置 → 搜索服务」选定的主后端（含已解密 key）；null 或 builtin = 只走内置免 key 链。 */
    val searchProvider: SearchProviderConfig? = null,
    /** B4 会话搜索：由引擎注入 SessionStore.list → 标题/摘要投影；null=工具不注册。 */
    val sessionSearch: ((String) -> List<Pair<String, String>>)? = null
) {
    /** 命中未过期缓存则返回结果；过期条目顺带清除。 */
    fun webCacheGet(key: String, ttlMs: Long = 10 * 60_000L): String? {
        val hit = webCache[key] ?: return null
        if (System.currentTimeMillis() - hit.first > ttlMs) {
            webCache.remove(key)
            return null
        }
        return hit.second
    }

    fun webCachePut(key: String, value: String) {
        if (webCache.size >= 24) { // 硬顶 24 条：淘汰最早写入
            webCache.minByOrNull { it.value.first }?.let { webCache.remove(it.key) }
        }
        webCache[key] = System.currentTimeMillis() to value
    }
}

/** ask_user 单个选项：label 显示用短标签；description 给用户看的一句话补充（可空串）。 */
data class AskUserOption(val label: String, val description: String = "")

/** ask_user 请求：模型在运行中遇到决策分叉时发起的"暂停等用户拍板"。 */
data class AskUserRequest(
    val question: String,
    val options: List<AskUserOption>,
    /** false=只允许从选项里挑（不渲染自由输入行）。 */
    val allowFreeText: Boolean = true
)

/** ask_user 回答：optionIndex≥0 = 选中选项；否则取 freeText。 */
data class AskUserAnswer(val optionIndex: Int = -1, val freeText: String = "")

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
