package com.haoai.pc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import com.sun.net.httpserver.HttpExchange
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.nio.charset.StandardCharsets

/**
 * 「专家市场」（对标 Octop 专家页的第四个页签）：从一个远端 JSON 拉一批角色卡，
 * 看清它的人设与绑定之后再导入成自己的卡。
 *
 * 三条设计上的取舍，都是"宁可少做也不做错"：
 *
 * 1. **默认没有远端**。`settings.expertFeed` 空串 = 这个功能等于不存在，页签显示空态并说明
 *    怎么开。不预置任何官方源：那等于替用户决定信任谁。
 * 2. **只读 JSON，不执行任何东西**。市场里的一张卡能带进来的只有人设、模型名、工作目录、
 *    档位、绑哪几个知识库 —— 而这几样本身就够敏感（一份带 `workspace=C:\` 且 auto 档的卡，
 *    等于一份"在这儿替你跑命令"的说明书），所以导入前必须看得见它写了什么。
 * 3. **解析复用 [Presets.importJson]**，不在这里再写一份。两份解析器迟早行为不一致，
 *    而"市场导入的卡"与"文件导入的卡"形状不一样是最难查的那类错。
 */
object ExpertFeed {

    /** 一次最多给多少张：清单是给人看的，几百张的列表等于没有列表。 */
    const val MAX_CARDS = 40

    /** 响应体上限（字节）：没有这一条，一个坏源能把内存吃掉。 */
    const val MAX_BYTES = 200_000

    private const val TIMEOUT_MS = 10_000

    /**
     * 一张待导入的卡：`name`/`desc` 是给列表看的，`json` 是原文。
     *
     * 为什么把原文一起带回去而不是服务端直接存下：**导入必须是用户的一次明确动作**，
     * 而且要走 `/api/presets op:'import'` 那条已经验过的路（换新 id、拒掉没人设的卡）。
     */
    data class Entry(val name: String, val desc: String, val json: String)

    /**
     * 拉一份清单，返回条目 + 拒绝原因（空 = 成功）。
     *
     * 收 URL 而不是自己读设置：测试要能不联网地喂一份 fixture，
     * 而 Server 那边传的是 `settings.expertFeed`。
     */
    fun fetch(rawUrl: String): Pair<List<Entry>, String?> {
        val url = rawUrl.trim()
        if (url.isBlank())
            return emptyList<Entry>() to "还没配市场地址（haoai set expertFeed=http://… 或 https://…）"
        val low = url.lowercase()
        if (!low.startsWith("http://") && !low.startsWith("https://"))
            return emptyList<Entry>() to "市场地址只认 http/https，现在是「${url.take(40)}」"
        val text = runCatching {
            /*
             * 用 java.net.http.HttpClient —— 与 Embed / Lan / Browser 同一套。
             * 这里原来写的是 `URL(url).openConnection()`（老的 HttpURLConnection）：
             * 在这台机器上它对任何地址都拿不到响应，症状是 10 秒后 Read timed out，
             * 而同一份假服务被别的测试类用 java.net.http 打都是通的。
             * 结论：**这个仓库里发 HTTP 只走 java.net.http**，别再引入第二种客户端。
             */
            val client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(TIMEOUT_MS / 1000L))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build()
            val req = HttpRequest.newBuilder(java.net.URI.create(url))
                .timeout(Duration.ofSeconds(TIMEOUT_MS / 1000L)).GET().build()
            val resp = client.send(req, HttpResponse.BodyHandlers.ofByteArray())
            // 先看 Content-Length（能拒就不必先下载），没有这个头再按实际字节数拒
            val declared = runCatching { resp.headers().firstValueAsLong("Content-Length").orElse(-1L) }.getOrDefault(-1L)
            if (declared > MAX_BYTES)
                throw IllegalArgumentException("清单太大（$declared 字节，上限 $MAX_BYTES）")
            val body = resp.body() ?: ByteArray(0)
            if (body.size > MAX_BYTES)
                throw IllegalArgumentException("清单超过 $MAX_BYTES 字节上限")
            String(body, StandardCharsets.UTF_8)
        }.getOrElse { return emptyList<Entry>() to "拉不到这份清单：${it.message ?: it.javaClass.simpleName}" }
        return parse(text)
    }

    /**
     * 收两种形状：卡片数组，或 `{"cards":[…]}`（源作者通常想留个版本号之类的壳）。
     * 每张原样交回它的 JSON 文本，由调用方走 `op:'import'` —— 这里不做任何"看起来像就存下"的事。
     */
    fun parse(text: String): Pair<List<Entry>, String?> {
        val root = runCatching { Json.parseToJsonElement(text) }.getOrNull()
            ?: return emptyList<Entry>() to "这份清单不是合法 JSON"
        val arr: JsonArray = when (root) {
            is JsonArray -> root
            is JsonObject -> runCatching { root["cards"]?.jsonArray }.getOrNull()
                ?: return emptyList<Entry>() to "JSON 里没有 cards 数组"
            else -> return emptyList<Entry>() to "这份清单既不是数组，也没有 cards 字段"
        }
        val out = ArrayList<Entry>()
        for (el: JsonElement in arr) {
            if (out.size >= MAX_CARDS) break
            // 每张必须是对象：字符串/数字混进来（比如有人把清单写成 ["名字1","名字2"]）
            // 到 importJson 那一步会被拒，但整份清单一起失败等于一张都装不了，所以这里就跳过并说明
            val o = runCatching { el.jsonObject }.getOrNull() ?: continue
            out += Entry(name = strOf(o, "name"), desc = strOf(o, "desc"), json = el.toString())
        }
        if (out.isEmpty())
            return emptyList<Entry>() to "清单里没有任何一张能读的角色卡（要的是对象，不是名字列表）"
        val skipped = arr.size - out.size
        val note = if (skipped > 0) "（另有 $skipped 项不是卡片，已跳过）" else ""
        return out to if (note.isEmpty()) null else note
    }

    /** 取一个字符串字段：缺、null、写成对象一律给空串，不抛。 */
    private fun strOf(o: JsonObject, key: String): String =
        o[key]?.jsonPrimitive?.contentOrNull ?: ""
}

/**
 * `POST /api/expertfeed` —— 只有一件事：`{op:'load'}` 拉清单。
 *
 * **这里不装卡**：装走已有的 `/api/presets {op:'import', text}` —— 那条路会换新 id、
 * 会拒"既没名字也没人设"的卡，再开一条写库的近路只会让两条路迟早不一样。
 */
internal fun WebServer.expertFeed(ex: HttpExchange) {
    val (entries, note) = ExpertFeed.fetch(settings.expertFeed)
    if (entries.isEmpty()) {
        send(ex, 200, """{"ok":false,"error":${quote(note ?: "没拉到清单")},""" +
            """"from":${quote(settings.expertFeed)}}""", "application/json; charset=utf-8")
        return
    }
    val items = entries.joinToString(",") { e ->
        """{"name":${quote(e.name)},"desc":${quote(e.desc)},"card":${quote(e.json)}}"""
    }
    send(ex, 200, """{"ok":true,"from":${quote(settings.expertFeed)},""" +
        """"note":${quote(note ?: "")},"cards":[$items]}""", "application/json; charset=utf-8")
}
