package com.haoai.pc

import java.text.SimpleDateFormat
import java.util.Date

/**
 * Token 统计的**导出**：把 [UsageLedger] 的四种切片写成一份真 .xlsx（[Xlsx]）。
 *
 * 为什么单独一层而不是让前端拼：页面上切到哪个页签只看得见那一张表，
 * 而导出要的是一份"拿进 Excel 还能自己再算"的完整账 —— 五个 sheet（汇总 / 按天 /
 * 按专家 / 按模型 / 明细）一次给全，明细那份尤其重要：聚合数对不上时，
 * 人需要能逐行查是哪一笔。只导当前页签的"导出"是个半成品功能。
 *
 * 数字全部以**数字**类型写入（不是字符串）：这样在 Excel 里能直接求和、做透视。
 * 全列文本的 .xlsx 看着像导出了，实际还得用户手动"分列"一遍 —— 那是把活推回去。
 *
 * 导出的口径与页面**同一份**（都走 [UsageLedger.pick]）：页面上选了什么区间和专家，
 * 存下来的就是那一份。两套口径的导出迟早对不上账。
 */
object UsageExport {

    /** 明细 sheet 的行数上限：账本会长，一次导出几百万行既打不开也没人看。 */
    const val MAX_DETAIL = 5000

    private val TS = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
    }

    /** 文件名带区间，导出三份放一起时看得出是哪段时间的。 */
    fun filename(from: Long, to: Long, expert: String = ""): String {
        val f = UsageLedger.dayOf(if (from > 0) from else System.currentTimeMillis())
        val t = UsageLedger.dayOf(if (to > 0) to - 1 else System.currentTimeMillis())
        val who = if (expert.isBlank()) "" else "-" + expert.filter {
            it.isLetterOrDigit() || it in "-_·"
        }.take(12)
        return "haoai-tokens-$f~$t$who.xlsx"
    }

    /** 五张表。`from`/`to` 与页面同源（本地日切齐，含 `to` 那天整天）。 */
    fun sheets(from: Long, to: Long, expert: String, kind: String): List<Xlsx.Sheet> {
        val all = UsageLedger.rows()
        val (f, t) = UsageLedger.span(all, from, to)
        val sel = all.filter { it.t in f until t }
            .filter { UsageLedger.matchesExpert(it, expert) }
            .filter { kind.isEmpty() || it.kind == kind }
        val df = TS.get().format(Date(f))
        val dt = TS.get().format(Date(if (t > f) t - 1 else f))
        val total = sel.sumOf { it.prompt + it.completion }

        val summary = listOf(
            listOf("指标", "值", "说明"),
            listOf("时间范围", "$df ~ $dt", "本地时间，含首尾两天"),
            listOf("专家筛选", if (expert.isBlank()) "全部专家" else expert, "页面下拉选的那个"),
            listOf("类型筛选", when (kind) {
                UsageLedger.MAIN -> "只要主会话"
                UsageLedger.SUB -> "只要子任务"
                else -> "主会话 + 子任务"
            }, "子任务 = task 工具派出去的"),
            listOf("总 TOKENS", total, "输入 + 输出"),
            listOf("输入", sel.sumOf { it.prompt }, "发给网关的 prompt token（计费口径）"),
            listOf("输出", sel.sumOf { it.completion }, "模型生成的 token"),
            listOf("缓存输入", sel.sumOf { it.cached }, "命中上下文缓存的那部分输入，通常按折扣计费"),
            listOf("回合数", sel.size, "一次模型调用算一个回合"),
            listOf("成功回合", sel.count { it.ok }, "失败也入账：成功率不是装饰"),
            listOf("失败回合", sel.count { !it.ok }, "网关报错、超时、被中断"),
            listOf("总耗时(ms)", sel.sumOf { it.ms }, "模型侧耗时，不含工具执行"),
            listOf("活跃天数", sel.map { UsageLedger.dayOf(it.t) }.distinct().size, "有账的日子"),
            listOf("导出时间", TS.get().format(Date(System.currentTimeMillis())), ""),
            listOf("明细是否截断", if (sel.size > MAX_DETAIL) "是（只留最近 $MAX_DETAIL 笔）" else "否",
                "聚合四张表不受截断影响")
        )

        val byDay = listOf(listOf("日期", "总 TOKENS", "输入", "输出", "缓存输入", "回合数", "耗时(ms)")) +
            UsageLedger.daysInRange(f, t).map { d ->
                val rs = sel.filter { UsageLedger.dayOf(it.t) == d }
                listOf(d, rs.sumOf { it.prompt + it.completion }, rs.sumOf { it.prompt },
                    rs.sumOf { it.completion }, rs.sumOf { it.cached }, rs.size, rs.sumOf { it.ms })
            }

        val byExpert = listOf(listOf("专家", "卡 id", "总 TOKENS", "输入", "输出", "缓存输入",
            "回合数", "其中子任务", "用到的模型数", "耗时(ms)")) +
            sel.groupBy { it.expertKey() }.entries
                .sortedByDescending { it.value.sumOf { r -> r.prompt + r.completion } }
                .map { (name, rs) ->
                    listOf(name, rs.firstOrNull { it.preset.isNotBlank() }?.preset ?: "",
                        rs.sumOf { it.prompt + it.completion }, rs.sumOf { it.prompt },
                        rs.sumOf { it.completion }, rs.sumOf { it.cached }, rs.size,
                        rs.count { it.kind == UsageLedger.SUB }, rs.map { it.model }.distinct().size,
                        rs.sumOf { it.ms })
                }

        val byModel = listOf(listOf("模型", "总 TOKENS", "输入", "输出", "缓存输入", "回合数",
            "失败回合", "平均 token/秒", "耗时(ms)")) +
            sel.groupBy { it.model }.entries
                .sortedByDescending { it.value.sumOf { r -> r.prompt + r.completion } }
                .map { (m, rs) ->
                    val timed = rs.filter { it.ms > 0 && it.completion > 0 }
                    val sps = if (timed.isEmpty()) 0.0
                    else timed.sumOf { it.completion } * 1000.0 / timed.sumOf { it.ms }
                    listOf(m, rs.sumOf { it.prompt + it.completion }, rs.sumOf { it.prompt },
                        rs.sumOf { it.completion }, rs.sumOf { it.cached }, rs.size,
                        rs.count { !it.ok }, "%.1f".format(sps), rs.sumOf { it.ms })
                }

        val detail = listOf(listOf("时间", "日期", "专家", "卡 id", "模型", "会话 id", "类型",
            "输入", "输出", "缓存输入", "耗时(ms)", "成功")) +
            sel.sortedBy { it.t }.take(MAX_DETAIL).map { r ->
                listOf(TS.get().format(Date(r.t)), UsageLedger.dayOf(r.t), r.expertKey(), r.preset,
                    r.model, r.sid, if (r.kind == UsageLedger.SUB) "子任务" else "主会话",
                    r.prompt, r.completion, r.cached, r.ms, if (r.ok) "是" else "否")
            }

        return listOf(
            Xlsx.Sheet("汇总", summary),
            Xlsx.Sheet("按天", byDay),
            Xlsx.Sheet("按专家", byExpert),
            Xlsx.Sheet("按模型", byModel),
            Xlsx.Sheet("明细", detail)
        )
    }
}
