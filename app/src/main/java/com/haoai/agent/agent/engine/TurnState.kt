package com.haoai.agent.agent.engine

/**
 * 一轮任务的循环状态。
 *
 * 为什么单独成对象：这些值此前散在 runTurn 的局部可变变量里（turns / turnPrompt / turnCompletion /
 * turnToolCalls / softWarned / turnBudgetWarned / forceFinish），而"跑到哪了"的另一半
 * （runTurnsUsed）却镜像在 StoredSession 上、runEndState 又在引擎字段上。三处各存一点，
 * 于是回合被系统杀掉之后没有任何一处能回答"这一轮已经用了多少"，
 * 所谓断点续跑只能重开一轮 —— 60 轮上限每续一次就重新给满，长任务可以无限续下去。
 *
 * 收拢后 [resumedTurnsUsed] 让续跑接着上一次的轮数往下走，上限才真正成立。
 */
class TurnState(
    /** 续跑时本轮已消耗过的轮数；非续跑传 0。 */
    resumedTurnsUsed: Int = 0
) {
    /** 本轮已发起的 LLM 请求轮数（含续跑之前的部分）。 */
    var turns: Int = resumedTurnsUsed
        private set

    var promptTokens = 0L
    var completionTokens = 0L

    /** 本轮累计工具调用次数（圈数熔断计数）。 */
    var toolCalls = 0

    /** 轮次预算 80% 软提醒只注入一次。 */
    var budgetWarned = false

    /** 成本 70% 软提醒只注入一次。 */
    var softWarned = false

    /** 熔断已触发：本轮不执行工具，下一轮无工具纯文本总结后结束。 */
    var forceFinish = false

    val totalTokens: Long get() = promptTokens + completionTokens

    /** 走完一整轮才计数（在发起请求前调用，与旧行为一致：先自增再判上限）。 */
    fun beginRound(): Int {
        turns += 1
        return turns
    }

    /** 续跑轮次要连着算：只剩 5 轮就说"还剩 5 轮"，不要说"上限 60"。 */
    fun capNotice(maxTurns: Int, resumed: Boolean): String =
        if (resumed)
            "本任务（含续跑）已累计 $turns 轮，达到上限（$maxTurns）。请拆分任务或开新会话继续。"
        else
            "已达单次任务轮数上限（$maxTurns）。请拆分任务或开新会话继续。"
}
