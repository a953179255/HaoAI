package com.haoai.agent.data

import android.content.Context
import android.util.Log
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 模型能力目录（数据源 models.dev/api.json，对齐 上游 架构）：
 * 三级缓存——内存(48h TTL) → 磁盘(cacheDir/models-dev-cache) → 内置 asset(models-dev-api.json)，
 * 过期时后台刷新，离线也可用。端侧模型不走此目录。
 */
object ModelCatalog {

    data class Capabilities(
        val modelId: String,
        val modelName: String,
        val providerKey: String,
        val contextWindow: Long,
        val maxOutput: Long,
        val inputModalities: List<String>,
        val outputModalities: List<String>,
        val reasoning: Boolean,
        val toolCall: Boolean?,
        val effortValues: List<String>?
    ) {
        fun describe(): String = buildString {
            append("${modelName}：上下文 ${if (contextWindow >= 1024) "${contextWindow / 1024}K" else "$contextWindow"}")
            if (maxOutput > 0) append(" · 输出上限 $maxOutput")
            val multi = inputModalities.filter { it != "text" }
            if (multi.isNotEmpty()) append(" · 输入(${multi.joinToString("/")})")
            if (reasoning) append(" · 支持推理")
            if (toolCall == true) append(" · 工具调用")
        }
    }

    /** models.dev 单模型条目（仅保留本应用消费的字段）。 */
    class Entry(
        val id: String,
        val name: String,
        val ctx: Long,
        val maxOut: Long,
        val inputs: List<String>,
        val outputs: List<String>,
        val reasoning: Boolean,
        val toolCall: Boolean?,
        val effortValues: List<String>?
    )

    private const val TAG = "HaoModelCatalog"
    private const val URL = "https://models.dev/api.json"
    private const val TTL_MS = 48 * 3600_000L
    private const val MAX_BYTES = 12L * 1024 * 1024

    @Volatile
    private var cache: Map<String, List<Entry>>? = null

    @Volatile
    private var providerApis: Map<String, String> = emptyMap()

    @Volatile
    private var cacheAt: Long = 0L

    private val refreshing = AtomicBoolean(false)

    /** AppContainer 启动时注入（磁盘缓存与 asset 兜底需要）。 */
    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** baseUrl 关键字 → 目录 provider key（api 精确匹配失败时的兜底；顺序即优先级）。 */
    private val hostMap = listOf(
        "deepseek" to "deepseek",
        "openrouter" to "openrouter",
        "moonshot" to "moonshotai",
        "bigmodel" to "zhipuai",
        "zhipuai" to "zhipuai",
        "anthropic" to "anthropic",
        "generativelanguage" to "google",
        "gemini" to "google",
        "x.ai" to "xai",
        "mistral" to "mistral",
        "groq" to "groq",
        "together" to "togetherai",
        "dashscope" to "alibaba",
        "aliyuncs" to "alibaba",
        "minimax" to "minimax",
        "siliconflow" to "siliconflow",
        "volces" to "volcengine",
        "openai" to "openai"
    )

    /** 模态名归一：OpenAI 系 "image_input"/"text_output" → 裸名（对齐 上游 normalizeModalityName）。 */
    fun normalizeModality(raw: String): String =
        raw.lowercase().removeSuffix("_input").removeSuffix("_output")

    suspend fun lookup(okh: OkHttpClient, baseUrl: String, modelId: String): Result<Capabilities> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val catalog = load(okh)
                val q = modelId.trim().lowercase()
                require(q.isNotEmpty()) { "模型 ID 为空" }

                fun match(list: List<Entry>): Entry? =
                    list.firstOrNull { it.id.equals(q, true) }
                        ?: list.firstOrNull {
                            val id = it.id.lowercase()
                            (id.startsWith(q) || q.startsWith(id)) && kotlin.math.abs(id.length - q.length) <= 24
                        }
                        ?: list.firstOrNull { it.id.lowercase().contains(q) || q.contains(it.id.lowercase()) }

                // 优先 provider api 精确匹配（models.dev 每个 provider 带 api 字段），再关键字表
                val providerKey = providerApis.entries.firstOrNull { (_, api) ->
                    api.isNotBlank() && baseUrl.trimEnd('/').lowercase()
                        .removeSuffix("/v1") == api.trimEnd('/').lowercase().removeSuffix("/v1")
                }?.key
                    ?: hostMap.firstOrNull { baseUrl.contains(it.first, true) }?.second
                var hit: Entry? = providerKey?.let { k -> catalog[k]?.let(::match) }
                var hitKey: String? = if (hit != null) providerKey else null
                if (hit == null) {
                    // 兜底：全目录搜。跨 provider 先精确 id（含 "vendor/model" 后缀形态），
                    // 再前缀/包含模糊；同级候选取信息最全的（有 effort/多模态优先）。
                    // 不能直接用 match() 的 contains 结果——HashMap 顺序不稳定，
                    // 曾把 bigmodel 的 glm-5.3-flash 匹配到 cloudflare 的 @cf/zai-org/… 条目。
                    val exact = catalog.entries.mapNotNull { (k, list) ->
                        list.firstOrNull {
                            val id = it.id.lowercase()
                            id == q || id.substringAfterLast('/') == q
                        }?.let { k to it }
                    }
                    val fuzzy = if (exact.isNotEmpty()) emptyList() else catalog.entries.mapNotNull { (k, list) ->
                        list.firstOrNull {
                            val id = it.id.lowercase()
                            (id.startsWith(q) || q.startsWith(id) || id.contains(q) || q.contains(id)) &&
                                kotlin.math.abs(id.length - q.length) <= 24
                        }?.let { k to it }
                    }
                    val cands = exact.ifEmpty { fuzzy }
                    val best = cands.firstOrNull { it.second.effortValues != null || it.second.inputs.size > 2 }
                        ?: cands.firstOrNull()
                    if (best != null) {
                        hit = best.second
                        hitKey = best.first
                    }
                }
                val e = hit ?: throw IllegalStateException("目录未收录「$modelId」，请手动填写或勾选模态")
                Capabilities(
                    e.id, e.name.ifBlank { e.id }, hitKey ?: "", e.ctx, e.maxOut,
                    e.inputs.ifEmpty { listOf("text") }, e.outputs.ifEmpty { listOf("text") },
                    e.reasoning, e.toolCall, e.effortValues
                )
            }
        }

    private fun load(okh: OkHttpClient): Map<String, List<Entry>> {
        cache?.let { c ->
            if (System.currentTimeMillis() - cacheAt < TTL_MS) return c
            scheduleBackgroundRefresh(okh)
            return c
        }
        // 磁盘缓存
        loadDisk()?.let { (parsed, ts) ->
            cache = parsed
            cacheAt = ts
            if (System.currentTimeMillis() - ts >= TTL_MS) scheduleBackgroundRefresh(okh)
            return parsed
        }
        // 内置 asset 兜底
        loadBundled()?.let { parsed ->
            cache = parsed
            cacheAt = System.currentTimeMillis()
            scheduleBackgroundRefresh(okh)
            return parsed
        }
        // 冷路径：同步网络
        return fetch(okh)
    }

    private fun scheduleBackgroundRefresh(okh: OkHttpClient) {
        if (!refreshing.compareAndSet(false, true)) return
        Thread {
            try {
                fetch(okh)
            } catch (_: Exception) {
            } finally {
                refreshing.set(false)
            }
        }.apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
    }

    private fun fetch(okh: OkHttpClient): Map<String, List<Entry>> {
        val client = okh.newBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
        val text = client.newCall(Request.Builder().url(URL).build()).execute().use { resp ->
            check(resp.isSuccessful) { "目录服务 HTTP ${resp.code}" }
            val body = resp.body ?: throw IllegalStateException("目录响应为空")
            val src = body.byteStream()
            val buf = ByteArray(64 * 1024)
            val out = java.io.ByteArrayOutputStream()
            var total = 0
            while (true) {
                val n = src.read(buf)
                if (n < 0) break
                total += n
                if (total > MAX_BYTES) throw IllegalStateException("目录文件过大")
                out.write(buf, 0, n)
            }
            out.toString("UTF-8")
        }
        val parsed = parse(text)
        check(parsed.isNotEmpty()) { "目录解析为空" }
        cache = parsed
        cacheAt = System.currentTimeMillis()
        saveDisk(text)
        return parsed
    }

    private fun parse(text: String): Map<String, List<Entry>> {
        val root = runCatching { HaoJson.json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: return emptyMap()
        val parsed = HashMap<String, List<Entry>>()
        val apis = HashMap<String, String>()
        for ((key, pv) in root) {
            val obj = runCatching { pv.jsonObject }.getOrNull() ?: continue
            obj["api"]?.jsonPrimitive?.content?.let { apis[key] = it }
            val models = runCatching { obj["models"]?.jsonObject }.getOrNull() ?: continue
            val entries = models.mapNotNull { (modelKey, mv) ->
                runCatching {
                    val o = mv.jsonObject
                    fun longOf(vararg keys: String): Long =
                        keys.firstNotNullOfOrNull { k ->
                            runCatching { o[k]?.jsonPrimitive?.content?.toLongOrNull() }.getOrNull()
                        } ?: 0L
                    val limit = runCatching { o["limit"]?.jsonObject }.getOrNull()
                    fun lim(k: String): Long =
                        runCatching { limit?.get(k)?.jsonPrimitive?.content?.toLongOrNull() }.getOrNull() ?: 0L
                    fun modal(key: String): List<String> = runCatching {
                        o["modalities"]?.jsonObject?.get(key)?.jsonArray
                            ?.mapNotNull { runCatching { it.jsonPrimitive.content }.getOrNull() }
                            ?.map { normalizeModality(it) }?.distinct()
                    }.getOrNull() ?: emptyList()
                    Entry(
                        id = modelKey,
                        name = runCatching { o["name"]?.jsonPrimitive?.content }.getOrNull() ?: "",
                        ctx = lim("context"),
                        maxOut = lim("output"),
                        inputs = modal("input").ifEmpty { listOf("text") },
                        outputs = modal("output").ifEmpty { listOf("text") },
                        reasoning = runCatching { o["reasoning"]?.jsonPrimitive?.content == "true" }.getOrDefault(false),
                        toolCall = runCatching { o["tool_call"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() }.getOrNull(),
                        effortValues = runCatching {
                            o["reasoning_options"]?.jsonArray?.firstOrNull { opt ->
                                runCatching { opt.jsonObject["type"]?.jsonPrimitive?.content == "effort" }.getOrDefault(false)
                            }?.let { opt ->
                                opt.jsonObject["values"]?.jsonArray
                                    ?.mapNotNull { runCatching { it.jsonPrimitive.content }.getOrNull() }
                            }
                        }.getOrNull()?.takeIf { it.isNotEmpty() }
                    )
                }.getOrNull()
            }
            if (entries.isNotEmpty()) parsed[key] = entries
        }
        providerApis = apis
        return parsed
    }

    private fun cacheFile(): File? = appContext?.let { ctx ->
        File(ctx.cacheDir, "models-dev-cache").let { d ->
            if (!d.exists()) d.mkdirs()
            File(d, "api.json")
        }
    }

    private fun loadDisk(): Pair<Map<String, List<Entry>>, Long>? {
        val f = cacheFile() ?: return null
        if (!f.exists()) return null
        return runCatching {
            val parsed = parse(f.readText())
            if (parsed.isEmpty()) null else Pair(parsed, f.lastModified())
        }.getOrNull()
    }

    private fun saveDisk(text: String) {
        runCatching { cacheFile()?.writeText(text) }
            .onFailure { Log.w(TAG, "目录磁盘缓存写入失败: ${it.message}") }
    }

    private fun loadBundled(): Map<String, List<Entry>>? {
        val ctx = appContext ?: return null
        return runCatching {
            val text = ctx.assets.open("models-dev-api.json").bufferedReader().readText()
            parse(text).takeIf { it.isNotEmpty() }
        }.onFailure { Log.w(TAG, "内置目录加载失败: ${it.message}") }.getOrNull()
    }
}
