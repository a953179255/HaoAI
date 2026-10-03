package com.haoai.pc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 用量账本：**每次模型回合**往 `HAOAI_HOME/usage.jsonl` 追加一行。
 *
 * 为什么不是"从会话文件里现算"（之前界面上那行「今天 / 近 7 天 / 全部」就是现算的）：
 * 会话只有一个累计总数和一个 `updated` 时间 —— 用 `updated` 判"今天"，
 * 于是昨天跑了三万 token、今天动了一下标题，那三万就全算成今天的了。
 * 按回合落账之后，"哪天花的、哪个模型花的、这次成没成"才是量出来的而不是猜的。
 *
 * 追加式 jsonl 而不是一个 JSON 数组：读的时候不用整份解析，写的时候不用整份重写，
 * 中途断电最坏只坏最后一行 —— 而坏行会被 `rows()` 丢掉，不会把整个看板变成 500。
 *
 * ## 2026-10-03：这四个新列必须在**写**的时候就有
 *
 * 对标 Octop 的 Token 统计页要"按专家"切片，而账本原来只有 `model/sid/prompt/completion`。
 * 三条能想到的省事路都被否了：
 * - **读的时候再从会话文件反查是谁**：会话会被删、会被改名，反查回来的是"现在叫什么"，
 *   不是"当时谁花的"。账本要的是后者。
 * - **把专家名塞进 `sid`**：sid 是会话 id，前端拿它跳会话，塞名字等于把两条职责焊死。
 * - **只给 `preset` id 不给名字**：卡删掉之后那一堆行只剩一串 id，人看不出是谁。
 *
 * 所以 `expert`（当时顶栏那个显示名）与 `preset`（卡 id）都落盘：**显示名留历史，id 留索引**。
 * `cached` 是第四条：网关早就回 `prompt_tokens_details.cached_tokens` 了
 * （[Provider] 解析进 `Usage.cachedTokens`），而账本把它丢了 —— 于是"缓存输入"这一格
 * 从来没量过。缓存命中直接决定这一轮多少钱，不记等于把最便宜的那部分账扔掉。
 * `kind` 分主会话与 `task` 派出来的子任务：团队会话贵在哪，要看得到是主持人花的还是成员花的。
 */
object UsageLedger {

    /** 这次改动之前落的行、以及没挂角色卡的会话，都归到这里 —— 明说，不猜一个。 */
    const val NO_EXPERT = "（未挂专家）"

    /** 用户直接聊的这条会话。 */
    const val MAIN = "main"

    /** `task` 工具派出来的子任务（团队会话里就是被派工的成员）。 */
    const val SUB = "sub"

    /**
     * 上下文压缩自己那一次模型调用。
     *
     * 为什么单独一档：它不是"用户说的一句话"，而是系统为了省窗口花的一笔钱。
     * 以前 `Engine.compactOnce` 只取 `AssistantTurn.text`，把 `usage` 丢了 ——
     * 于是这笔真实发生的消耗在账本、Token 统计页与导出的 xlsx 里全都不存在，
     * 而页面上写的是「总 TOKENS」。对标 Octop 那张图，它是挂一句
     * "部分消耗尚未纳入统计"盖过去；我们只有这一个非回合调用点，记进账本比免责诚实。
     */
    const val COMPACT = "compact"

    data class Row(
        val t: Long,
        val model: String,
        val sid: String,
        val prompt: Int,
        val completion: Int,
        val ms: Long,
        val ok: Boolean,
        /** 网关报回来的缓存命中输入 token；老行没有这一列 = 0。 */
        val cached: Int = 0,
        /** 这一轮顶栏上的名字：角色卡名、"团队 · X"，没挂就是空。 */
        val expert: String = "",
        /** 角色卡 id（团队会话为空：它是主持人，不是编制里任何一张卡）。 */
        val preset: String = "",
        /** [MAIN] / [SUB]。 */
        val kind: String = MAIN
    ) {
        /** 按专家切片时这一行归到谁名下。 */
        fun expertKey(): String = expert.ifBlank { NO_EXPERT }
    }

    fun file(): File = File(Env.home, "usage.jsonl")

    /**
     * 落一行。**缺省参数一律写成"老行为"**：调用方没传专家就是没挂专家，
     * 不许在这里编一个默认名字 —— 编出来的统计比没有统计更害人。
     */
    fun add(
        model: String, sid: String, prompt: Int, completion: Int, ms: Long, ok: Boolean,
        cached: Int = 0, expert: String = "", preset: String = "", kind: String = MAIN
    ) {
        val line = kotlinx.serialization.json.buildJsonObject {
            put("t", System.currentTimeMillis())
            put("model", model)
            put("sid", sid)
            put("prompt", prompt)
            put("completion", completion)
            put("ms", ms)
            put("ok", ok)
            // 没有值就**不写这个键**：老行的形状与"新调用没传"时的形状保持同一份；
            // 而零缓存的会话每天几百行，每行挂四个空键纯属放大账本。
            if (cached > 0) put("cached", cached)
            if (expert.isNotBlank()) put("expert", expert)
            if (preset.isNotBlank()) put("preset", preset)
            if (kind != MAIN) put("kind", kind)
        }.toString()
        runCatching {
            file().parentFile?.mkdirs()
            file().appendText(line + "\n")
        }
    }

    private fun int(o: JsonObject, k: String): Int =
        o[k]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0

    private fun str(o: JsonObject, k: String): String =
        o[k]?.jsonPrimitive?.contentOrNull ?: ""

    fun rows(): List<Row> = runCatching {
        file().readLines().mapNotNull { l ->
            if (l.isBlank()) return@mapNotNull null
            runCatching {
                val o = Json.parseToJsonElement(l).jsonObject
                Row(
                    t = o["t"]?.jsonPrimitive?.longOrNull ?: 0L,
                    model = o["model"]?.jsonPrimitive?.contentOrNull ?: "（未知）",
                    sid = str(o, "sid"),
                    prompt = int(o, "prompt"),
                    completion = int(o, "completion"),
                    ms = o["ms"]?.jsonPrimitive?.longOrNull ?: 0L,
                    ok = o["ok"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: true,
                    cached = int(o, "cached"),
                    expert = str(o, "expert"),
                    preset = str(o, "preset"),
                    kind = str(o, "kind").ifBlank { MAIN }
                )
            }.getOrNull()        // 半行/坏行直接丢：看板不该因为一次断电就整块打不开
        }
    }.getOrDefault(emptyList())

    /** 本地日期的柱状标签（"天"是人看钟表的那个数，不是 UTC）。`internal` 是给导出层用的。 */
    internal fun dayOf(t: Long): String = DAY.get().format(Date(t))
    private fun dayOffset(days: Int): String =
        DAY.get().format(Date(System.currentTimeMillis() - days * DAY_MS))

    private val DAY = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat = SimpleDateFormat("yyyy-MM-dd")
    }

    internal const val DAY_MS = 86_400_000L

    /** 那一天的 00:00（本地）。区间按"天"切，因为用户选的是哪一天，不是哪个毫秒。 */
    internal fun startOfDay(ms: Long): Long = java.util.Calendar.getInstance().apply {
        timeInMillis = ms
        set(java.util.Calendar.HOUR_OF_DAY, 0)
        set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis

    /**
     * 一个专家下拉项能不能匹配上这一行：卡 id 优先，显示名兜底（老行只有名字），
     * "未挂专家"单独判 —— 它匹配的是**空**，不是那五个字。
     */
    internal fun matchesExpert(r: Row, want: String): Boolean = when {
        want.isEmpty() -> true
        want == NO_EXPERT -> r.expert.isBlank()
        else -> r.preset == want || r.expert == want
    }

    /**
     * 区间切齐：`from`/`to` 按本地日取整，且 `to` 那天**整天**算在内
     * （人理解的"到 10-03"含 10-03 那天）。两个都不给 = 近 30 天，与 Octop 默认窗口一致。
     */
    internal fun span(all: List<Row>, from: Long, to: Long): Pair<Long, Long> {
        val now = System.currentTimeMillis()
        val f = if (from > 0L) startOfDay(from) else startOfDay(now) - 29 * DAY_MS
        val t = if (to > 0L) startOfDay(to) + DAY_MS else now
        return if (t <= f) f to f + DAY_MS else f to t
    }

    /** 按区间与筛选条件挑行（不含"跨区间累计"那种）。 */
    fun pick(from: Long = 0L, to: Long = 0L, expert: String = "", kind: String = ""): List<Row> {
        val all = rows()
        val (f, t) = span(all, from, to)
        return all.filter { it.t in f until t }
            .filter { matchesExpert(it, expert) }
            .filter { kind.isEmpty() || it.kind == kind }
    }

    /** 老键的形状（没有 cached/total）：右栏累计看板在读，形状不许漂。 */
    private fun agg(list: List<Row>): String =
        """{"n":${list.size},"prompt":${list.sumOf { it.prompt }},""" +
            """"completion":${list.sumOf { it.completion }},"ms":${list.sumOf { it.ms }}}"""

    /**
     * 新键的形状：多 `cached` 与 `total`（= 输入 + 输出，页面上最大的那个数），
     * 以及 `compact`（这一堆行里有几次是上下文压缩自己花的）。
     *
     * 收在这里而不是各切片自己补：`aggFull` 是 summary/byDay/byExpert/byModel **共用**的形状，
     * 口径只有一处，四个页签不会各算各的。
     */
    private fun aggFull(list: List<Row>): String =
        """{"n":${list.size},"prompt":${list.sumOf { it.prompt }},""" +
            """"completion":${list.sumOf { it.completion }},"cached":${list.sumOf { it.cached }},""" +
            """"total":${list.sumOf { it.prompt + it.completion }},"ms":${list.sumOf { it.ms }},""" +
            """"compact":${list.count { it.kind == COMPACT }}}"""

    /**
     * 上面那份去掉两头花括号的**片段**，给"在前后还要再接键"的切片用。
     *
     * 原来这里写的是 `aggFull(rs).substring(1)`（只掐头），于是尾巴那个 `}` 留在了中间：
     * `{"key":"pa",…,"ms":100},"sub":0}` —— 对象在 sub 之前就闭合了，整份报表变成非法 JSON。
     * 手搓字符串的账：借一段结构就得把两段都剪掉，剪一半比不剪更隐蔽。
     */
    private fun aggInner(list: List<Row>): String = aggFull(list).substring(1).dropLast(1)

    /**
     * 一次算齐界面上用量页要的所有数。
     *
     * **一个键只出现一次**：区间口径的数全部收进 `summary`，
     * 于是 `okRate`/`tps` 不会在顶层出现两遍 —— 顶层那两个是老的全量口径，
     * 两遍的话后写的赢，前端读到的是哪个全靠解析器心情。
     *
     * 老键（`today` / `week` / `all` / `models` / `days` / `okRate` / `tps`）一律按**全量**算，
     * 右栏那个累计看板读的是它们，语义不能跟着新区间漂；
     * 新键（`range` / `summary` / `byDay` / `byExpert` / `byModel` / `experts`）读的是**区间 + 筛选**。
     * 两套并存而不是把老的改掉，是因为"右栏那三个数"早就写进像素剧本的判据里了。
     *
     * 柱子按"补全 token"高度算 —— 人想知道的是"哪天真正在干活"，
     * 而输入 token 会被一次长上下文拉爆，看不出差别。
     */
    fun report(from: Long = 0L, to: Long = 0L, expert: String = "", kind: String = ""): String {
        val all = rows()
        val now = System.currentTimeMillis()
        val today = dayOf(now)
        val weekStart = dayOffset(6)
        val (f, t) = span(all, from, to)
        val sel = all.filter { it.t in f until t }
            .filter { matchesExpert(it, expert) }
            .filter { kind.isEmpty() || it.kind == kind }

        // ---- 区间内的三种切片 ----
        val byDay = daysInRange(f, t).map { d ->
            val rs = sel.filter { dayOf(it.t) == d }
            """{"d":${quote(d)},${aggInner(rs)}}"""
        }
        val byExpert = sel.groupBy { it.expertKey() }.entries
            .sortedByDescending { it.value.sumOf { r -> r.prompt + r.completion } }
            .map { (name, rs) ->
                val pid = rs.firstOrNull { it.preset.isNotBlank() }?.preset ?: ""
                // key 与下拉、与 matchesExpert 用的是**同一把钥匙**（分组名）：
                // 表上那一行是"小通"，下拉里选"小通"就必须筛出同一批行
                """{"key":${quote(name)},"name":${quote(name)},""" +
                    """"preset":${quote(pid)},${aggInner(rs)},""" +
                    """"sub":${rs.count { it.kind == SUB }},""" +
                    """"models":${rs.map { it.model }.distinct().size}}"""
            }
        val byModel = sel.groupBy { it.model }.entries
            .sortedByDescending { it.value.sumOf { r -> r.prompt + r.completion } }
            .map { (m, rs) -> """{"model":${quote(m)},${aggInner(rs)}}""" }
        // 下拉项：从**全量**账本出，不然选了区间就选不到"这个月没花过钱的那个专家"。
        // key 用的是**分组名**而不是卡 id —— 与 byExpert 同一把钥匙。
        // 用 id 会不一致：一个组里既有挂了卡的行也有改名前的裸行（preset 空），
        // 按 id 筛就漏掉裸行，于是"表上写着这个专家花了 31，筛完只剩 11"。
        // id 仍然给出去（preset 字段），要按卡精确 drill-down 的人拿得到。
        val expertOpts = all.groupBy { it.expertKey() }.entries
            .sortedBy { it.key }
            .map { (name, rs) ->
                val pid = rs.firstOrNull { it.preset.isNotBlank() }?.preset ?: ""
                """{"key":${quote(name)},"name":${quote(name)},"preset":${quote(pid)},""" +
                    """"total":${rs.sumOf { it.prompt + it.completion }}}"""
            }

        // ---- 老键：全量口径 ----
        val byDayAll = all.groupBy { dayOf(it.t) }
        val bars = (13 downTo 0).map { i ->
            val d = dayOffset(i)
            val rs = byDayAll[d].orEmpty()
            """{"d":"$d","prompt":${rs.sumOf { it.prompt }},""" +
                """"completion":${rs.sumOf { it.completion }},"n":${rs.size}}"""
        }
        val models = all.groupBy { it.model }.entries
            .sortedByDescending { it.value.sumOf { r -> r.prompt + r.completion } }
            .map { (m, rs) ->
                """{"model":${quote(m)},"n":${rs.size},"prompt":${rs.sumOf { it.prompt }},""" +
                    """"completion":${rs.sumOf { it.completion }},"ms":${rs.sumOf { it.ms }}}"""
            }
        val okN = all.count { it.ok }
        val tpsAll = rate(all)

        return buildString {
            append("""{"range":{"from":$f,"to":$t,""")
            append(""""fromText":${quote(dayOf(f))},"toText":${quote(dayOf(if (t > f) t - 1 else f))}},""")
            append(""""summary":${summary(sel)}""")
            append(""","byDay":[${byDay.joinToString(",")}]""")
            append(""","byExpert":[${byExpert.joinToString(",")}]""")
            append(""","byModel":[${byModel.joinToString(",")}]""")
            append(""","experts":[${expertOpts.joinToString(",")}]""")
            append(""","today":${agg(all.filter { dayOf(it.t) == today })}""")
            append(""","week":${agg(all.filter { dayOf(it.t) >= weekStart })}""")
            append(""","all":${agg(all)}""")
            append(""","models":[${models.joinToString(",")}]""")
            append(""","days":[${bars.joinToString(",")}]""")
            append(""","okRate":${num(if (all.isEmpty()) 0.0 else okN.toDouble() / all.size)}""")
            append(""","tps":${num(tpsAll)}""")
            append(""","rows":${all.size},"backfilled":${file().exists() && all.isNotEmpty()}}""")
        }
    }

    /** 区间汇总：页面上那四张卡（总 TOKENS / 输入 / 输出 / 缓存输入）读的就是这一份。 */
    private fun summary(list: List<Row>): String =
        """{"n":${list.size},"prompt":${list.sumOf { it.prompt }},""" +
            """"completion":${list.sumOf { it.completion }},"cached":${list.sumOf { it.cached }},""" +
            """"total":${list.sumOf { it.prompt + it.completion }},"ms":${list.sumOf { it.ms }},""" +
            """"dayCount":${list.map { dayOf(it.t) }.distinct().size},""" +
            // 系统自己花的那几次（上下文压缩）：口径要能在页面上说清，见 Engine.bookCompact
            """"compact":${list.count { it.kind == COMPACT }},""" +
            """"okRate":${num(if (list.isEmpty()) 0.0 else list.count { it.ok }.toDouble() / list.size)},""" +
            """"tps":${num(rate(list))}}"""

    /** 区间内的天（含没有账的空天）：趋势图与"按天"表要能看出"那几天没跑"。 */
    internal fun daysInRange(from: Long, to: Long): List<String> {
        val out = ArrayList<String>()
        var d = startOfDay(from)
        val last = startOfDay(if (to > from) to - 1 else from)
        var guard = 0
        while (d <= last && guard < 400) {          // 400 天封顶：再长就是前端把 0 传进来了
            out += dayOf(d)
            d += DAY_MS
            guard++
        }
        return out
    }

    private fun rate(list: List<Row>): Double {
        val timed = list.filter { it.ms > 0 && it.completion > 0 }
        if (timed.isEmpty()) return 0.0
        return timed.sumOf { it.completion }.toDouble() / (timed.sumOf { it.ms } / 1000.0)
    }

    /**
     * 小数一律按 `Locale.ROOT` 格式化：`"%.1f".format()` 跟默认语言走，
     * 换成逗号做小数点的语言就产出一份**非法 JSON**（`"tps":12,4`）。
     */
    private fun num(v: Double): String = String.format(Locale.ROOT, "%.3f", v)

    // 模型名可能是本地 gguf 的**完整 Windows 路径**（一串反斜杠），不转义就是一份非法 JSON
    private fun quote(s: String): String = "\"" +
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
}
