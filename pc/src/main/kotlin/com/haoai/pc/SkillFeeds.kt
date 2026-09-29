package com.haoai.pc

import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 技能订阅源：一条网址指向一份"这里有哪些技能可以装"的清单。
 *
 * 这是"插件市场"里被拆出来的那一半。不做账号、评分、付费、自动更新 ——
 * 那几样要的是运营，而真正缺的是两件事：**有个地方能列出来**，以及
 * **装之前看得见这份东西会用到哪些工具**。没有后者，"从网址装"就从
 * "我读过这份再决定"退化成"看见个名字就点"，那是把判断外包给一个陌生网页。
 *
 * 说清这一版**没有**的东西，免得把它当成有：
 * 没有来源校验、没有签名、没有白名单（`SkillDocs` 里 signature/trust/allowlist 零命中，这里也没加）。
 * 信任边界仍然是"用户自己看过正文"，[SkillPerms] 做的事是把"看"的成本从一份 20KB 正文
 * 降到一屏清单；它**不是**沙箱，也不假装是 —— 所以 [SkillPerms.NOTE] 那句话必须跟清单同屏。
 */
data class SkillFeed(val id: String, val name: String, val url: String)

/** 清单里的一项：一份可以装的技能，连同它来自哪条源。 */
data class FeedEntry(
    val name: String,
    val url: String,
    val desc: String,
    val feed: String,
    /** 装之前扫出来的"它会用到哪些工具"，元素形状见 [SkillPerms.scan]。 */
    val tools: List<String> = emptyList(),
    /** 正文没拉回来时在这里说明原因 —— 条目照样列出来，不许因为扫不到就消失。 */
    val scanError: String = ""
)

/** 装之前先看的这一份：清单 + 开头一段原文。 */
data class SkillPreview(
    val name: String,
    val desc: String,
    val url: String,
    val bytes: Int,
    val tools: List<String>,
    val head: String,
    val error: String = ""
) {
    val ok get() = error.isEmpty()
}

object SkillFeeds {
    const val MAX_FEEDS = 12
    const val MAX_ENTRIES = 40

    /** 清单正文上限：订阅源是索引不是仓库，超过这个数就当它不是清单。 */
    const val MAX_INDEX_BYTES = 400_000

    fun file(): File = File(Env.home, "skill-feeds.json")

    fun load(): List<SkillFeed> = runCatching {
        Json.parseToJsonElement(file().readText(StandardCharsets.UTF_8)).jsonArray.mapNotNull { e ->
            val o = runCatching { e.jsonObject }.getOrNull() ?: return@mapNotNull null
            val url = str(o, "url")
            if (url.isEmpty()) return@mapNotNull null
            SkillFeed(str(o, "id").ifBlank { "sf" + url.hashCode() }, str(o, "name"), url)
        }
    }.getOrDefault(emptyList())

    private fun save(list: List<SkillFeed>): Boolean = runCatching {
        file().writeText(jsonOf(list), StandardCharsets.UTF_8)
        true
    }.getOrDefault(false)

    /** 只认 http/https —— 与 `SkillDocs.importUrl` 同一条边界，别在这里开第二个口子。 */
    private fun usable(url: String): String? {
        val uri = runCatching { URI.create(url) }.getOrNull() ?: return "这个网址读不出来"
        if (uri.scheme != "http" && uri.scheme != "https") return "只支持 http/https 的清单地址"
        if (uri.host.isNullOrBlank()) return "这个网址没有主机名"
        return null
    }

    /** 加一条源。返回 (源, 错误)：错误非空时源为 null。 */
    fun add(name: String, rawUrl: String): Pair<SkillFeed?, String> {
        val url = rawUrl.trim()
        if (url.isEmpty()) return null to "先填清单地址"
        usable(url)?.let { return null to it }
        val list = load()
        if (list.size >= MAX_FEEDS) return null to "订阅源最多 $MAX_FEEDS 条，先删一条再加"
        if (list.any { it.url == url }) return null to "这条已经订过了"
        val nm = name.trim().ifBlank { hostOf(url) }
        val f = SkillFeed("sf" + System.nanoTime().toString(16).take(12), nm, url)
        return if (save(list + f)) f to "" else null to "写不进 ${file().absolutePath}"
    }

    fun remove(id: String): Boolean {
        val list = load()
        val kept = list.filter { it.id != id }
        return kept.size < list.size && save(kept)
    }

    /**
     * 没填名字时拿主机名兜底，免得列表上一排空白。
     *
     * IP 不折成第一段 —— "127" 这种名字比空白更难认。
     */
    private fun hostOf(url: String): String {
        val h = runCatching { URI.create(url).host ?: "" }.getOrDefault("")
        if (h.isEmpty()) return url
        if (Regex("""\d+\.\d+\.\d+\.\d+""").matches(h)) return h
        return h.removePrefix("www.").substringBefore('.')
    }

    fun json(): String = jsonOf(load())

    private fun jsonOf(list: List<SkillFeed>): String =
        list.joinToString(",", "[", "]") {
            """{"id":${js(it.id)},"name":${js(it.name)},"url":${js(it.url)}}"""
        }

    /**
     * 抓一条源的清单。认两种形状：`{"skills":[{name,url,desc}]}` 与裸数组 `[{name,url}]`。
     *
     * 每一项**顺手把正文取回来扫一遍**（[SkillPerms.scan]）—— 这就是"装之前先看见"的代价：
     * 一次刷新要发 1+N 个请求。所以条目数卡死在 [MAX_ENTRIES]，且扫不动的条目照样列出来，
     * 只是带上 `scanError`：宁可清单不全，也不许因为扫不到就不让人看见有这个东西。
     */
    fun fetch(feed: SkillFeed, client: HttpClient? = null): Pair<List<FeedEntry>, String> {
        // 一次刷新共用一个 client：每条正文都新建一个会连带新建它自己的线程池，
        // 40 条就是 40 套，刷一次源把机器拖住的不是网络而是我自己
        val c = client ?: HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL).build()
        val (bytes, err) = get(feed.url, c)
        if (bytes == null) return emptyList<FeedEntry>() to err
        val text = String(bytes, StandardCharsets.UTF_8)
        val arr = runCatching { Json.parseToJsonElement(text).jsonArray }.getOrNull()
            ?: runCatching { Json.parseToJsonElement(text).jsonObject["skills"]?.jsonArray }.getOrNull()
            ?: return emptyList<FeedEntry>() to "这份清单既不是 JSON 数组，也没有 skills 字段"
        val out = ArrayList<FeedEntry>()
        for (e in arr.take(MAX_ENTRIES)) {
            val o = runCatching { e.jsonObject }.getOrNull() ?: continue
            val url = str(o, "url")
            if (url.isEmpty()) continue
            val name = str(o, "name").ifBlank { hostOf(url) }
            val (doc, scanErr) = get(url, client)
            val tools: List<String> = if (doc == null) emptyList()
            else SkillPerms.scan(String(doc, StandardCharsets.UTF_8))
            out += FeedEntry(
                name, url, str(o, "desc"), feed.name.ifBlank { feed.id }, tools,
                scanError = if (doc == null) scanErr else ""
            )
        }
        if (out.isEmpty()) return emptyList<FeedEntry>() to "这份清单里一个条目都没有（或者条目都没有 url）"
        return out to ""
    }

    /** 刷新一条源，返回 (条目, 错误)。 */
    fun refresh(id: String, client: HttpClient? = null): Pair<List<FeedEntry>, String> {
        val f = load().firstOrNull { it.id == id } ?: return emptyList<FeedEntry>() to "没有这条订阅源"
        return fetch(f, client)
    }

    /** 装之前先看这一份。 */
    fun preview(url: String, client: HttpClient? = null): SkillPreview {
        usable(url)?.let { return SkillPreview("", "", url, 0, emptyList(), "", it) }
        val (bytes, err) = get(url, client)
        if (bytes == null) return SkillPreview("", "", url, 0, emptyList(), "", err)
        val text = String(bytes, StandardCharsets.UTF_8)
        val (name, desc, body) = SkillDocs.parse(text)
        return SkillPreview(
            name.ifBlank { hostOf(url) }, desc, url, text.length,
            SkillPerms.scan(text), body.take(1200)
        )
    }

    /**
     * 一次抓取：scheme 闸 + 超时 + 体积上限。清单与正文两处共用这一个口，
     * 少一处检查就等于给外部输入留一个后门。
     */
    private fun get(rawUrl: String, client: HttpClient?): Pair<ByteArray?, String> {
        val uri = runCatching { URI.create(rawUrl.trim()) }.getOrNull()
            ?: return null to "这个网址读不出来"
        if (uri.scheme != "http" && uri.scheme != "https") return null to "只支持 http/https"
        return runCatching {
            val c = client ?: HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL).build()
            val req = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20))
                .header("User-Agent", "HaoAI-PC/$PC_VERSION").GET().build()
            val res = c.send(req, HttpResponse.BodyHandlers.ofByteArray())
            if (res.statusCode() !in 200..299) return null to "清单地址回了 ${res.statusCode()}"
            val b = res.body() ?: return null to "带回来的是空的"
            // 正文上限交给 SkillDocs，这里只卡清单；但一个"清单"大到这个数就不是清单了
            if (b.size > MAX_INDEX_BYTES) return null to "超过 ${MAX_INDEX_BYTES / 1000} KB，不像一份清单"
            b to ""
        }.getOrElse { null to "拉不下来：${it.message}" }
    }

    private fun str(o: kotlinx.serialization.json.JsonObject, k: String): String =
        runCatching { o[k]?.jsonPrimitive?.contentOrNull?.trim() ?: "" }.getOrDefault("")

    fun entriesJson(list: List<FeedEntry>): String =
        list.joinToString(",", "[", "]") {
            """{"name":${js(it.name)},"url":${js(it.url)},"desc":${js(it.desc)},"feed":${js(it.feed)},""" +
                """"scanError":${js(it.scanError)},"tools":[${it.tools.joinToString(",") { t -> js(t) }}]}"""
        }

    fun previewJson(p: SkillPreview): String =
        """{"ok":${p.ok},"name":${js(p.name)},"desc":${js(p.desc)},"url":${js(p.url)},""" +
            """"bytes":${p.bytes},"error":${js(p.error)},"head":${js(p.head)},""" +
            """"tools":[${p.tools.joinToString(",") { js(it) }}]}"""

    /** 与 `SkillDocs` 同款转义：这两处必须一字不差，否则中文正文里的引号能把整段 JSON 打散。 */
    private fun js(s: String): String = "\"" +
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "") + "\""
}

/**
 * 装之前那份"它会用到哪些工具"。
 *
 * 两个来源，分开标注：
 * ① YAML 头里作者**自己声明**的 `tools:` / `allowed-tools:`（写了就照它列 ——
 *    认不出的名字也列出来，那要么是新工具，要么是作者在暗示什么，两种都该让人看见）；
 * ② 正文里以**代码形式**点名的那几把会动东西的工具（`` `write` ``、`shell(`、"用 `media` 拼接"）。
 *
 * 为什么第 ② 类只报危险的那一批：技能正文是提示词，`read`、`grep` 这种词的出现概率接近 100%
 * （"先 read 一下再决定"），把它们列进"权限"等于给每个技能都盖一个"它要读文件"的章 ——
 * 那种清单看两次就没人看了。判定"危险"用 `Tool.kind != "read"`，也就是**复用权限闸那套分类**：
 * 新增一把 exec 类工具时它自动进清单，不需要有人记得回来改这张表（改漏过一次就是长期假绿）。
 *
 * 第 ① 类不挑：作者主动声明的，只读工具也照实列，标成"声明，只读"。
 */
object SkillPerms {

    /** 会改东西、起进程、出门联网的那几把 —— 从工具表推出来，不是手抄的。 */
    private val danger: Set<String> by lazy {
        builtinTools().filter { it.kind != "read" }.map { it.name }.toSet() +
            // task 的 kind 是 read（它自己不落盘），但它派生的子任务能用上全部工具，值得单独露面
            "task"
    }

    private val allNames: Set<String> by lazy { builtinTools().map { it.name }.toSet() }

    /** 给人看的那句免责。它必须跟着清单一起出现，不许只留清单。 */
    const val NOTE = "这份清单是扫出来的，不是沙箱保证：技能正文让模型做的事，不受这份清单限制。"

    /**
     * 扫一份 SKILL.md，返回要显示给人看的清单。
     *
     * 元素形状稳定：`名字（为什么）`。顺序也是稳定的 —— 声明的在前、扫出来的在后、
     * 认不出的名字最后（那条最需要人看一眼）。
     */
    fun scan(text: String, known: Set<String>? = null, dangerKnown: Set<String>? = null): List<String> {
        val all = known ?: allNames
        val dangerSet = dangerKnown ?: danger
        val (_, _, body) = SkillDocs.parse(text)
        val declared = LinkedHashSet<String>()
        val inferred = LinkedHashSet<String>()
        val weird = LinkedHashSet<String>()
        for (t in declaredTools(text)) {
            val base = t.substringBefore('(').trim()
            if (base.isEmpty()) continue
            when {
                base.startsWith("mcp__") || base == "mcp" -> declared += "$t（外部 MCP）"
                all.contains(base) && dangerSet.contains(base) -> declared += label(base, "作者声明")
                all.contains(base) -> declared += label(base, "作者声明，只读")
                else -> weird += "$base（作者写了这个名字，但本机没有这把工具）"
            }
        }
        for (t in dangerSet) {
            if (declared.any { it.startsWith("$t（") }) continue
            if (mentionedAsCode(body, t)) inferred += label(t, "正文里这么写")
        }
        if (Regex("""mcp__[A-Za-z0-9_.\-]+""").containsMatchIn(body))
            inferred += "mcp__*（外部 MCP 工具）"
        return (declared + inferred + weird).toList()
    }

    private fun label(tool: String, why: String): String = "$tool（$why）"

    /** YAML 头里的 `tools:` / `allowed-tools:`，逗号或空格分隔，也认 `- write` 那种列表写法。 */
    fun declaredTools(text: String): List<String> {
        val lines = text.replace("\r\n", "\n").replace("\r", "\n").split('\n')
        if (lines.firstOrNull()?.trim() != "---") return emptyList()
        val head = lines.drop(1).takeWhile { it.trim() != "---" }
        val out = LinkedHashSet<String>()
        var key = ""
        for (l in head) {
            val k = l.substringBefore(':').trim().lowercase()
            if (k in setOf("tools", "allowed-tools", "allowed_tools")) {
                key = k
                l.substringAfter(':', "").replace("[", " ").replace("]", " ").split(',', ' ')
                    .map { it.trim().trim('"', '\'') }.filter { it.isNotBlank() }.forEach { out += it }
                continue
            }
            // 缩进的 `- write`：只在刚碰到 tools 这一族键时才收，否则会把 description 的续行当工具名
            if (key.isNotEmpty() && l.trim().startsWith("-")) {
                l.trim().removePrefix("-").trim().trim('"', '\'').takeIf { it.isNotBlank() }?.let { out += it }
                continue
            }
            if (!l.startsWith(" ") && !l.startsWith("\t")) key = ""
        }
        return out.toList()
    }

    /**
     * "像代码"才算点名：反引号包着、后面紧跟 `(`、或者跟在"用/调用/执行/跑/通过/需要/使用"后面。
     *
     * 宁可多报：把"讲了 git 用法但没调 git"列进来，代价是人多看一眼；
     * 反过来漏报，代价是人在没看过的东西上点了"装"。
     */
    private fun mentionedAsCode(s: String, tool: String): Boolean {
        val q = Regex.escape(tool)
        return Regex("""`$q`""").containsMatchIn(s) ||
            Regex("""$q\s*\(""").containsMatchIn(s) ||
            Regex("""(用|调用|执行|跑|通过|需要|使用)\s*[`"“"]?$q""").containsMatchIn(s)
    }
}
