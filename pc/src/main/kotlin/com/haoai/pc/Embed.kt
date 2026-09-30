package com.haoai.pc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * 向量端点客户端（#6 语义索引的第一块）。
 *
 * 实测（2026-09-29，见 ROADMAP §5.3 #6）：本机 llama-server 的 **legacy `/embeddings`** 可用、
 * `/v1/embeddings` 回 400；返回是 `[{index, embedding:[[…]]}]` —— 比 OpenAI 形状多了两层。
 * 所以解析同时认两种：
 *   - legacy：裸数组 `[{index, embedding:[…或[[…]]}]}]`（embedding 可能多包一层 batch 维，剥一层）
 *   - OpenAI：`{data: [{embedding: […]}]}`
 *
 * 失败一律返回 null（连不上、超时、形状不认识），**不抛** —— 调用方（grep 的语义回退）
 * 决定怎么说；语义检索是增强，不该把好好的文本搜索拖死。
 */
object Embed {

    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    /** 一批文本 → 按序向量。texts 为空或任何失败 → null。 */
    fun embed(url: String, texts: List<String>, timeoutSec: Int = 30): List<List<Double>>? {
        if (texts.isEmpty() || url.isBlank()) return null
        return try {
            val req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(timeoutSec.toLong().coerceAtMost(120)))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(encodeBody(texts)))
                .build()
            val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
            if (resp.statusCode() !in 200..299) return null
            parse(resp.body(), texts.size)
        } catch (e: Exception) {
            null
        }
    }

    private fun encodeBody(texts: List<String>): String = buildString {
        append("{\"model\":\"embed\",\"input\":")
        append(Json.encodeToString(kotlinx.serialization.json.buildJsonArray {
            texts.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
        }))
        append("}")
    }

    /** 解析端点返回；数量对不上 / 形状不认识 → null。 */
    fun parse(body: String, expect: Int): List<List<Double>>? = runCatching {
        val el = Json.parseToJsonElement(body)
        val raw: List<JsonArray> = when {
            el is JsonArray -> el.map { it as JsonObject }
                .let { list -> if (list.isNotEmpty() && list[0].containsKey("embedding")) list else return null }
                .map { it["embedding"]!!.jsonArray }
            el is JsonObject && el.containsKey("data") ->
                el["data"]!!.jsonArray.map { it.jsonObject["embedding"]!!.jsonArray }
            else -> return null
        }
        if (raw.size != expect) return null
        raw.map { flatten(it) }.also { vs ->
            if (vs.any { it.isEmpty() }) return null
        }
    }.getOrNull()

    /** legacy 的 embedding 可能是 `[[…]]`（多一层 batch 维）——剥到一维。 */
    private fun flatten(a: JsonArray): List<Double> {
        var v = a
        while (v.size == 1 && v[0] is JsonArray) v = v[0]!!.jsonArray
        return v.map { it.jsonPrimitive.content.toDouble() }
    }

    /** 余弦相似度。任一向量长度为 0 → 0.0。 */
    fun cosine(a: List<Double>, b: List<Double>): Double {
        if (a.size != b.size || a.isEmpty()) return 0.0
        var dot = 0.0; var na = 0.0; var nb = 0.0
        for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        val d = Math.sqrt(na) * Math.sqrt(nb)
        return if (d == 0.0) 0.0 else dot / d
    }
}
