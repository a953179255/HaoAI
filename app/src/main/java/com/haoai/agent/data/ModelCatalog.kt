package com.haoai.agent.data

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 模型能力目录（上游 同款数据源 catalog.上游.ai/models/v1/catalog.json）：
 * providers.<key>.models[] 含 contextWindow / maxTokens / input(多模态) / reasoning。
 * 进程内缓存 6 小时；下载上限 8MB。端侧模型不走此目录（本地无法联网探测）。
 */
object ModelCatalog {

    data class Capabilities(
        val modelId: String,
        val modelName: String,
        val providerKey: String,
        val contextWindow: Long,
        val maxOutput: Long,
        val inputModalities: List<String>,
        val reasoning: Boolean
    ) {
        fun describe(): String = buildString {
            append("${modelName}：上下文 ${if (contextWindow >= 1024) "${contextWindow / 1024}K" else "$contextWindow"}")
            if (maxOutput > 0) append(" · 输出上限 $maxOutput")
            val multi = inputModalities.filter { it != "text" }
            if (multi.isNotEmpty()) append(" · 多模态(${multi.joinToString("/")})")
            if (reasoning) append(" · 支持推理")
        }
    }

    private class Entry(
        val id: String,
        val name: String,
        val ctx: Long,
        val maxOut: Long,
        val inputs: List<String>,
        val reasoning: Boolean
    )

    private const val URL = "https://catalog.上游.ai/models/v1/catalog.json"
    private const val TTL_MS = 6 * 3600_000L
    private const val MAX_BYTES = 8L * 1024 * 1024

    @Volatile
    private var cache: Map<String, List<Entry>>? = null

    @Volatile
    private var cacheAt: Long = 0L

    /** baseUrl 关键字 → 目录 provider key（顺序即优先级）。 */
    private val hostMap = listOf(
        "deepseek" to "deepseek",
        "openrouter" to "openrouter",
        "moonshot" to "moonshotai",
        "bigmodel" to "zhipu",
        "zhipuai" to "zhipu",
        "anthropic" to "anthropic",
        "generativelanguage" to "google",
        "gemini" to "google",
        "x.ai" to "xai",
        "mistral" to "mistral",
        "groq" to "groq",
        "together" to "together-2",
        "dashscope" to "alibaba",
        "aliyuncs" to "alibaba",
        "minimax" to "minimax",
        "baichuan" to "baichuan",
        "siliconflow" to "siliconflow",
        "volces" to "volcengine",
        "openai" to "openai"
    )

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

                val providerKey = hostMap.firstOrNull { baseUrl.contains(it.first, true) }?.second
                var hit: Entry? = providerKey?.let { k -> catalog[k]?.let(::match) }
                var hitKey: String? = if (hit != null) providerKey else null
                if (hit == null) {
                    // 兜底：模型名一般自带厂商前缀（如 deepseek-chat），全目录模糊搜
                    for ((k, list) in catalog) {
                        match(list)?.let {
                            hit = it
                            hitKey = k
                            break
                        }
                    }
                }
                val e = hit ?: throw IllegalStateException("目录未收录「$modelId」，请手动填写上下文窗口")
                Capabilities(e.id, e.name.ifBlank { e.id }, hitKey ?: "", e.ctx, e.maxOut, e.inputs, e.reasoning)
            }
        }

    private fun load(okh: OkHttpClient): Map<String, List<Entry>> {
        cache?.let { c -> if (System.currentTimeMillis() - cacheAt < TTL_MS) return c }
        val client = okh.newBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
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
        val root = com.haoai.agent.data.HaoJson.json.parseToJsonElement(text).jsonObject
        val providers = root["providers"]?.jsonObject ?: throw IllegalStateException("目录缺少 providers")
        val parsed = HashMap<String, List<Entry>>()
        for ((key, pv) in providers) {
            val models = runCatching { pv.jsonObject["models"]?.jsonArray }.getOrNull() ?: continue
            val entries = models.mapNotNull { m ->
                runCatching {
                    val o = m.jsonObject
                    fun longOf(vararg keys: String): Long =
                        keys.firstNotNullOfOrNull { k ->
                            runCatching { o[k]?.jsonPrimitive?.content?.toLongOrNull() }.getOrNull()
                        } ?: 0L
                    Entry(
                        id = o["id"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                        name = runCatching { o["name"]?.jsonPrimitive?.content }.getOrNull() ?: "",
                        ctx = longOf("contextWindow", "context_window"),
                        maxOut = longOf("maxTokens", "max_tokens", "maxOutput"),
                        inputs = runCatching {
                            o["input"]?.jsonArray?.mapNotNull { runCatching { it.jsonPrimitive.content }.getOrNull() }
                        }.getOrNull() ?: listOf("text"),
                        reasoning = runCatching { o["reasoning"]?.jsonPrimitive?.content == "true" }.getOrDefault(false)
                    )
                }.getOrNull()
            }
            if (entries.isNotEmpty()) parsed[key] = entries
        }
        cache = parsed
        cacheAt = System.currentTimeMillis()
        return parsed
    }
}
