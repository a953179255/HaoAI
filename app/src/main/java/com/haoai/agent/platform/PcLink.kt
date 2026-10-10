package com.haoai.agent.platform

import com.haoai.agent.data.HaoJson
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 与电脑上那份 HaoAI 的连接 —— B4 跨端通道的**安卓这一半**。
 *
 * 协议不是新发明的：电脑端 `pc/src/main/kotlin/com/haoai/pc/Lan.kt` 早就实现了局域网那一面
 * （配对码换设备 token，之后每个请求带 `X-HaoAI-Token`），而 `pc/.../ui/phone.html`
 * 是同一套协议的第一份客户端。这里要的是"不打开浏览器也能被通知到、也能点那一下"。
 *
 * 两条刻意：
 * ① **只读 + 替人点决定**，本地不留可写状态 —— 09-26 那份协同方案定的是"PC 为权威、手机为节点"，
 *    节点不该有第二份能写的会话或配置；
 * ② 失败一律回一句人话（[PcOut.Fail]）而不是抛异常：调用方是通知服务与设置页，
 *    它们处理不了网络栈的异常，而"连不上那台电脑"本来就是要显示给人看的东西。
 */

/** 与电脑端 `Lan.DEFAULT_PORT` 同一个数；两边改了要一起改。 */
const val PC_LAN_DEFAULT_PORT = 8720

/**
 * 把人手工输入的那串东西规范化成 `http://host:port/`。
 *
 * 为什么值得单独钉：这是整条链路上唯一由人手打的一段，而"192.168.1.5:8720"、
 * "http://192.168.1.5:8720"、末尾带斜杠、只填 IP 这四种写法全都常见；拼错的表象是
 * "配对失败"，而那句错误是电脑端回的，人根本猜不到是地址少写了个协议。
 * 看不懂就返回 null 并让界面说清要什么，不要发一个注定错的请求。
 */
fun pcNormalizeBase(raw: String): String? {
    var s = raw.trim()
    if (s.isEmpty()) return null
    val hasScheme = s.startsWith("http://") || s.startsWith("https://")
    if (!hasScheme) s = "http://$s"
    s = s.trimEnd('/')
    val authority = s.substringAfter("://", "")
    if (authority.isBlank() || authority.any { it.isWhitespace() } || authority.contains('/')) return null
    val host = authority.substringAfterLast('@').substringBefore(':')
    // 只认 ASCII 形状的主机名/IPv4：`isLetter()` 对中文也返回 true，
    // 于是"主机名:8720"这种一看就不该发出去的输入会被当成合法地址（实测踩过，靠这条判据挡住）
    if (host.isEmpty() || !host.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '.' || it == '-' })
        return null
    if (!authority.contains(':')) s = "$s:$PC_LAN_DEFAULT_PORT"
    return "$s/"
}

sealed class PcOut<out T> {
    data class Ok<T>(val value: T) : PcOut<T>()

    /**
     * `unauthorized` 单独留一个口子：它不是一句"再试一次"的错误，而是"这台设备没配对 /
     * 配对被电脑上撤了"，界面要跳回配对页并清掉本地 token。
     */
    data class Fail(val message: String, val unauthorized: Boolean = false) : PcOut<Nothing>()
}

@Serializable
data class PcSession(
    val id: String = "", val title: String = "", val mode: String = "",
    val running: Boolean = false, val msgs: Int = 0, val ws: String = "", val updated: Long = 0L
)

@Serializable
data class PcMessage(val role: String = "", val name: String = "", val text: String = "")

@Serializable
data class PcPayload(
    val title: String = "", val detail: String = "",
    val risk: String = "", val riskLabel: String = "", val riskWhy: String = "",
    /**
     * `kind=="ask"` 时才有：候选项的 label 列表（通知按钮挂的就是它）。
     * B22 起电脑端发的是 `[{label,description}]` 对象，旧版发纯字符串 —— 两种都要收：
     * 收不下不是"少几个选项"，是**整份 pending 解码失败**，审批通知也一起哑掉
     * （`decode` 对形状不符回 Fail，轮询会把通知全撤了）。所以这里自定义解码。
     */
    @Serializable(PcOptListSerializer::class) val options: List<String> = emptyList(),
    /** T2 批量问卷：电脑上那句"提问"其实是一份题库，逐题作答要到网页上做。 */
    val batch: Boolean = false,
    val questionCount: Int = 0
)

/** 选项数组解码：字符串取原文、对象取 label；元素畸形就丢那一个，不许拖垮整份载荷。 */
object PcOptListSerializer : KSerializer<List<String>> {
    override val descriptor: SerialDescriptor = ListSerializer(String.serializer()).descriptor

    override fun deserialize(decoder: Decoder): List<String> {
        val json = decoder as? JsonDecoder
            ?: return decoder.decodeSerializableValue(ListSerializer(String.serializer()))
        val arr = json.decodeJsonElement() as? JsonArray ?: return emptyList()
        return arr.mapNotNull { el ->
            when (el) {
                is JsonPrimitive -> el.contentOrNull
                is JsonObject -> el["label"]?.let { (it as? JsonPrimitive)?.contentOrNull }
                else -> null
            }
        }
    }

    override fun serialize(encoder: Encoder, value: List<String>) {
        encoder.encodeSerializableValue(ListSerializer(String.serializer()), value)
    }
}

@Serializable
data class PcApproval(val id: String = "", val kind: String = "", val sid: String = "", val payload: PcPayload = PcPayload())

@Serializable
data class PcDigestItem(
    /** 那一轮的开始时刻（毫秒）。这份 JSON 里唯一的稳定主键 —— 差集全靠它，见 [PcDigestWatch]。 */
    val t: Long = 0L,
    val at: String = "", val title: String = "", val verdict: String = "", val text: String = ""
)

/** 电脑端几个口的信封一样（`{"ok":..,"items":[..]}`），共用一份。 */
@Serializable
data class PcItems<T>(val ok: Boolean = false, val items: List<T> = emptyList(), val error: String = "")

@Serializable
data class PcSessionDetail(
    val ok: Boolean = false, val sid: String = "", val title: String = "",
    val running: Boolean = false, val items: List<PcMessage> = emptyList(), val error: String = ""
)

@Serializable
data class PcPair(val ok: Boolean = false, val token: String = "", val device: String = "", val error: String = "")

@Serializable
data class PcNote(val ok: Boolean = false, val note: String = "", val error: String = "", val sid: String = "")

@Serializable
data class PcHealth(val ok: Boolean = false, val service: String = "")

class PcLink(
    base: String,
    private var token: String = "",
    private val http: OkHttpClient = defaultClient()
) {
    private val urlBase: String = pcNormalizeBase(base) ?: "http://127.0.0.1:$PC_LAN_DEFAULT_PORT/"

    fun token(): String = token

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()

        /** 与电脑端 `Lan.DECISIONS` 同一组值（写错了电脑会拒，但宁可这里先挡住）。 */
        val DECISIONS = setOf("allow_once", "allow_session", "deny")

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            // 局域网就该快：连不上要尽快说"连不上"，而不是让人盯着转圈
            .connectTimeout(4, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false).build()
    }

    private suspend fun call(path: String, body: String? = null): Pair<Int, String> =
        withContext(Dispatchers.IO) {
            val b = Request.Builder().url(urlBase + path)
            if (token.isNotEmpty()) b.header("X-HaoAI-Token", token)
            val req = if (body == null) b.get().build() else b.post(body.toRequestBody(JSON)).build()
            runCatching { http.newCall(req).execute().use { it.code to (it.body?.string() ?: "") } }
                .getOrElse { e ->
                    val why = (e as? IOException)?.message ?: e.message ?: "未知原因"
                    // 把 OkHttp 那句 "Failed to connect to /192.168.1.5:8720" 翻成人话：
                    // 用户在这里真正要确认的是"电脑上那个开关开了没、IP 与端口对不对"
                    0 to "连不上那台电脑（$why）。确认电脑上 HaoAI 的「手机联动」是开的，再核对 IP 与端口。"
                }
        }

    /** 先按信封判成没成（`ok`/`error` 两边都有），再解成具体形状。 */
    private inline fun <reified T> decode(status: Int, text: String): PcOut<T> {
        if (status == 401) return PcOut.Fail("这台设备还没配对，或者配对已经被电脑上移除", unauthorized = true)
        if (status == 0) return PcOut.Fail(text)
        if (status !in 200..299) return PcOut.Fail("电脑那边回了一个 $status，没读懂它")
        val root = runCatching { HaoJson.json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: return PcOut.Fail("电脑回的不是 JSON——地址对吗？还是被别的程序接走了")
        val ok = root["ok"]?.jsonPrimitive?.booleanOrNull ?: true
        if (!ok) return PcOut.Fail(root["error"]?.jsonPrimitive?.contentOrNull ?: "电脑上没说明为什么没成")
        val v = runCatching { HaoJson.json.decodeFromJsonElement(serializer<T>(), root) }.getOrNull()
            ?: return PcOut.Fail("电脑回的内容不是预期形状（两端版本不一致？）")
        return PcOut.Ok(v)
    }

    /** 电脑上那个开关开没开。不需要 token，配对页可以先用它探一下地址填对没。 */
    suspend fun health(): PcOut<PcHealth> {
        val (st, tx) = call("lan/health")
        if (st == 0) return PcOut.Fail(tx)
        val h = runCatching { HaoJson.json.decodeFromString(PcHealth.serializer(), tx) }.getOrNull()
            ?: return PcOut.Fail("应答不像 HaoAI 的电脑端")
        return if (h.ok && h.service == "haoai-pc") PcOut.Ok(h)
        else PcOut.Fail("连上了，但对面说它不是 HaoAI 的电脑端")
    }

    /** 用电脑上生成的六位配对码换一份设备 token（成功后 token 存在这个实例上）。 */
    suspend fun pair(code: String, name: String): PcOut<PcPair> {
        val (st, tx) = call("lan/pair", jsonObj("code" to code, "name" to name))
        val out = decode<PcPair>(st, tx)
        if (out is PcOut.Ok && out.value.token.isNotBlank()) token = out.value.token
        return out
    }

    suspend fun sessions(): PcOut<List<PcSession>> = list("lan/sessions")

    suspend fun pending(): PcOut<List<PcApproval>> = list("lan/pending")

    suspend fun digest(): PcOut<List<PcDigestItem>> = list("lan/digest")

    private suspend inline fun <reified R> list(path: String): PcOut<List<R>> {
        val (st, tx) = call(path)
        return when (val out = decode<PcItems<R>>(st, tx)) {
            is PcOut.Ok -> PcOut.Ok(out.value.items)
            is PcOut.Fail -> PcOut.Fail(out.message, out.unauthorized)
        }
    }

    suspend fun session(sid: String): PcOut<PcSessionDetail> {
        val (st, tx) = call("lan/session?sid=" + URLEncoder.encode(sid, "UTF-8"))
        return when (val out = decode<PcSessionDetail>(st, tx)) {
            is PcOut.Ok -> PcOut.Ok(out.value)
            is PcOut.Fail -> PcOut.Fail(out.message, out.unauthorized)
        }
    }

    /** 替人点那一下：`decision` 只能是 allow_once / allow_session / deny。 */
    suspend fun decide(id: String, decision: String): PcOut<String> {
        if (decision !in DECISIONS) return PcOut.Fail("决定只能是 ${DECISIONS.joinToString("/")}")
        val (st, tx) = call("lan/decide", jsonObj("id" to id, "decision" to decision))
        return when (val out = decode<PcNote>(st, tx)) {
            is PcOut.Ok -> PcOut.Ok(out.value.note.ifBlank { "已送到电脑上" })
            is PcOut.Fail -> PcOut.Fail(out.message, out.unauthorized)
        }
    }

    /** 从手机派一句活（电脑上「允许从手机派活」默认关；关着会拿到一句说明，不是异常）。 */
    suspend fun send(sid: String, text: String): PcOut<String> {
        val (st, tx) = call("lan/send", jsonObj("sid" to sid, "text" to text))
        return when (val out = decode<PcNote>(st, tx)) {
            is PcOut.Ok -> PcOut.Ok(out.value.sid.ifBlank { out.value.note })
            is PcOut.Fail -> PcOut.Fail(out.message, out.unauthorized)
        }
    }

    /**
     * 回答电脑上的一句提问（`ask_user`）。
     *
     * 与 [decide] 分开写：那边只能填那三个决定值，这边填的是**回答文字本身** ——
     * 引擎那边 `fut.complete(字符串)` 拿到的就是这句。合成一个方法的话，
     * 要么放开校验让"answer 蒙混过审批"成为可能，要么手机上永远答不了提问。
     */
    suspend fun answer(id: String, text: String): PcOut<String> {
        if (text.isBlank()) return PcOut.Fail("回答是空的，没发出去")
        val (st, tx) = call("lan/decide", jsonObj("id" to id, "answer" to text))
        return when (val out = decode<PcNote>(st, tx)) {
            is PcOut.Ok -> PcOut.Ok(out.value.note.ifBlank { "已把这句回答送到电脑上" })
            is PcOut.Fail -> PcOut.Fail(out.message, out.unauthorized)
        }
    }

    suspend fun unpair(): PcOut<String> {
        val (st, tx) = call("lan/unpair", "{}")
        return when (val out = decode<PcNote>(st, tx)) {
            is PcOut.Ok -> { token = ""; PcOut.Ok(out.value.note) }
            is PcOut.Fail -> PcOut.Fail(out.message, out.unauthorized)
        }
    }
}

/**
 * 构造 JSON 请求体。
 *
 * M7：原先是手拼字符串 + `esc()` 只处理 `\\` 与 `"`。JSON 字符串不允许裸
 * `\n` / `\r` / `\t` / <0x20>控制字符 —— 而 [answer] 的 text 是用户对
 * `ask_user` 提问的自由回答，**换行极常见**。一旦命中，含换行的回答让整个
 * 请求体变成非法 JSON，电脑端解析失败，而接收侧只`Log.w` 一句 ⇒
 * 用户视角是"点了没反应"。
 *
 * 改用 kotlinx.serialization 统一序列化，转义由库保证（同类问题在
 * `VirtualScreenController.launch` 已踩过一次并做了百分号编码兜底，此处漏了）。
 *
 * 放在文件顶层而非 `PcLink` 成员：`PcLinkJsonTest` 需要直接验**真源**，而不是
 * 在测试里抄一份副本 —— 抄副本等于给自己造一个"永远通过"的假断言。
 */
internal fun jsonObj(vararg pairs: Pair<String, String>): String =
    kotlinx.serialization.json.buildJsonObject {
        pairs.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
    }.toString()
