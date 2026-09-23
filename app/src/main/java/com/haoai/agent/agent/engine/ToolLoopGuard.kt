package com.haoai.agent.agent.engine

import java.security.MessageDigest

/**
 * 工具空转守卫（对标 Hermes tool_guardrails + OpenClaw tools.loopDetection）。
 *
 * 纯函数、无副作用：只观察每次工具收尾的 (tool, args, result) 观测，返回决策；
 * 是否注入引导、是否 hard stop 由 [AgentEngine.finishCall] 解释执行。
 *
 * 检测三类模式：
 * 1. **identical 连续**：同 tool+canonical(args)+result 连续 N 次 → warn，再涨到 block → hard stop
 * 2. **短周期循环**：A,B,A,B… 且 args/result 全同（周期 ≤4）→ 同样计 laps，防"换序绕过连续检测"
 * 3. **same-tool 失败**：同 tool 不同 args 连续失败 → 仅 warn（failure-tolerant 工具不 halt）
 *
 * 工具分类沿用 HaoAI 既有口径：READ=幂等只读；PARALLEL_SAFE 部分即幂等；
 * mutating = 非 READ；浏览器/网络导航类对"失败"更宽容（failure-tolerant）。
 */
class ToolLoopGuard(
    private val identicalWarnAfter: Int = 3,
    private val identicalBlockAfter: Int = 5,
    private val sameToolFailWarnAfter: Int = 3,
    private val sameToolFailHaltAfter: Int = 8,
    private val maxCyclePeriod: Int = 4,
    /** 无人值守/ YOLO 时 true：warn 阈值直接当 hard stop 语义收紧（由调用方设置）。 */
    private val hardStopEnabled: Boolean = false,
    /** 圈数级硬顶（与 EngineLimits 的 toolCallCap 对齐，guard 侧可独立更紧）。 */
    private val loopCap: Int = 0
) {
    sealed class Decision {
        /** 无动作。 */
        data object None : Decision()

        /** 注入一条只读引导（不打断循环）。 */
        data class Warn(val message: String) : Decision()

        /** 注入引导并置 forceFinish（下一轮纯文本收尾）。 */
        data class Halt(val message: String) : Decision()
    }

    private data class Obs(
        val tool: String,
        val argsHash: String,
        val resultHash: String,
        val isError: Boolean
    )

    /** 滚动观测窗口（足够覆盖 maxCyclePeriod 圈 + 余量）。 */
    private val history = ArrayDeque<Obs>()
    private val historyCap = 64

    private var identicalStreak = 0
    private var sameToolFailStreak = 0
    private var lastIdenticalKey: String? = null
    private var lastFailTool: String? = null
    private var cycleNotices = 0
    private var totalToolCalls = 0
    private var warnedThisTurn = false

    /** 回合开始时复位（TurnState 新建时调用）。 */
    fun reset() {
        history.clear()
        identicalStreak = 0
        sameToolFailStreak = 0
        lastIdenticalKey = null
        lastFailTool = null
        cycleNotices = 0
        totalToolCalls = 0
        warnedThisTurn = false
    }

    /**
     * 观察一次工具收尾并返回决策。同一 call 只应调用一次。
     *
     * @param toolName 工具名
     * @param argsJson 规范化前的参数 JSON 字符串（模型原文即可）
     * @param resultContent 工具结果正文（截断前或后均可，需稳定）
     * @param isError 结果是否错误
     * @param isRead 是否 READ 风险级（只读工具不做 same-tool-fail 计数）
     */
    fun observe(
        toolName: String,
        argsJson: String,
        resultContent: String,
        isError: Boolean,
        isRead: Boolean
    ): Decision {
        totalToolCalls++
        if (loopCap in 1 until totalToolCalls) {
            return Decision.Halt(
                "[空转守卫] 单轮工具调用已达上限（$loopCap）。请立即总结进度与剩余步骤并结束，不要再调用工具。"
            )
        }

        val argsHash = sha1(argsJson.trim())
        val resultHash = sha1(resultContent)
        val key = "$toolName|$argsHash|$resultHash"
        val obs = Obs(toolName, argsHash, resultHash, isError)

        // ── identical 连续（同参同果） ──
        if (key == lastIdenticalKey) {
            identicalStreak++
        } else {
            identicalStreak = 1
            lastIdenticalKey = key
        }

        // ── same-tool 连续失败（不同 args；idempotent 只读不计） ──
        if (isError && !isRead && toolName == lastFailTool) {
            sameToolFailStreak++
        } else if (isError && !isRead) {
            sameToolFailStreak = 1
            lastFailTool = toolName
        } else if (!isError) {
            // 成功 = 有进展，清失败连击（与 Hermes PROGRESS_RESET 同向）
            if (toolName == lastFailTool) {
                sameToolFailStreak = 0
                lastFailTool = null
            }
        }

        // ── 短周期检测（A,B,A,B… 全参全果相同） ──
        val cycleLap = detectCycleLap(obs)

        history.addLast(obs)
        while (history.size > historyCap) history.removeFirst()

        // ── 决策 ──
        val identicalThreshold = if (hardStopEnabled) {
            identicalBlockAfter.coerceAtMost(identicalWarnAfter)
        } else identicalWarnAfter
        val identicalHaltAt = if (hardStopEnabled) {
            identicalWarnAfter
        } else identicalBlockAfter

        if (identicalStreak >= identicalHaltAt && identicalHaltAt > 0) {
            return Decision.Halt(
                "[空转守卫] 已连续 $identicalStreak 次完全相同的「$toolName」调用（同参数同结果），判定无进展。" +
                    "请换一种参数/方法，或基于已有信息总结收尾，不要再重复该调用。"
            )
        }
        if (cycleLap >= 1 && cycleNotices == 0) {
            cycleNotices++
            warnedThisTurn = true
            return Decision.Warn(
                "[空转守卫] 检测到工具调用在短周期内重复（同参数同结果循环），这通常表示当前策略没有进展。" +
                    "请改变方法或总结已有结论并结束本轮。"
            )
        }
        if (identicalStreak >= identicalThreshold && identicalThreshold > 0 && !warnedThisTurn) {
            warnedThisTurn = true
            return Decision.Warn(
                "[空转守卫] 「$toolName」已连续 $identicalStreak 次相同调用。" +
                    "若仍无新信息，请更换参数或停止重试并总结。"
            )
        }
        if (!isRead && isError && sameToolFailStreak >= sameToolFailWarnAfter && !warnedThisTurn) {
            warnedThisTurn = true
            val extra = if (hardStopEnabled && sameToolFailStreak >= sameToolFailHaltAfter) {
                // hard stop 由上一层 identical 分支不够时，在此升级
                ""
            } else ""
            return Decision.Warn(
                "[空转守卫] 「$toolName」已连续失败 $sameToolFailStreak 次。" +
                    "请读取错误详情、换参数或换工具，不要原样重试。$extra"
            )
        }
        if (!isRead && isError && sameToolFailStreak >= sameToolFailHaltAfter && sameToolFailHaltAfter > 0) {
            return Decision.Halt(
                "[空转守卫] 「$toolName」连续失败 $sameToolFailStreak 次且无进展，强制收尾。" +
                    "请基于已有信息总结失败原因与下一步建议。"
            )
        }
        return Decision.None
    }

    /**
     * 检测窗口尾部是否构成完整的一圈周期（period 1..maxCyclePeriod）。
     * 全部历史 obs 都参与比较（tool+args+result 三元组必须字节级一致的 hash）。
     * @return 满一整圈返回 1（本观测使周期闭合），否则 0
     */
    private fun detectCycleLap(newObs: Obs): Int {
        val list = history.toList()
        if (list.size < 2) return 0
        // 含 newObs 在内的完整序列
        val seq = list + newObs
        for (period in 2..maxCyclePeriod) {
            if (seq.size < period * 2) continue
            val tail = seq.takeLast(period * 2)
            val firstHalf = tail.subList(0, period)
            val secondHalf = tail.subList(period, period * 2)
            if (firstHalf == secondHalf) {
                // 要求至少有一圈是"相对上一次闭合"的新 lap：用窗口内出现次数粗判
                return 1
            }
        }
        // period=1 已由 identicalStreak 覆盖
        return 0
    }

    private fun sha1(s: String): String =
        try {
            val d = MessageDigest.getInstance("SHA-1").digest(s.toByteArray(Charsets.UTF_8))
            buildString(d.size * 2) { d.forEach { append(String.format("%02x", it)) } }
        } catch (_: Exception) {
            s.hashCode().toUInt().toString(16)
        }
}
