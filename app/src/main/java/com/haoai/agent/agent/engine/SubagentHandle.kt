package com.haoai.agent.agent.engine

import java.util.concurrent.ConcurrentLinkedQueue

/**
 * P2 子代理句柄：steer/stop 与部分结果回收的锚点（生命周期=引擎实例，即一个回合）。
 * - state：RUNNING →（STOPPING→STOPPED | DONE | ERROR | CANCELLED）
 * - steps：已完成工具步骤摘要，失败/终止时随部分结果回传给主代理
 * - steering：主代理 steer_agent 注入的纠偏指令，子代理下一轮开始前消费
 */
class SubagentHandle(
    val id: String,
    /** 所属 spawn 工具调用 id（任务卡按 callId 聚合展示）。 */
    val callId: String?,
    val index: Int,
    val total: Int,
    val task: String,
    val job: kotlinx.coroutines.Job,
    @Volatile var state: String = "RUNNING",
    /** 当前正在执行的工具（P1 逐工具进度）。 */
    @Volatile var currentTool: String? = null,
    val steps: ConcurrentLinkedQueue<String> = ConcurrentLinkedQueue(),
    /** 最近一次模型中间输出（未定稿的结论草稿）。 */
    @Volatile var lastAssistant: String = "",
    val steering: ConcurrentLinkedQueue<String> = ConcurrentLinkedQueue()
) {
    /** P1.2 部分结果回收：失败/终止时不白干——已完成步骤+中间结论一并返回。 */
    fun partialSummary(): String = buildString {
        append("已完成步骤：")
        if (steps.isEmpty()) append("（无）") else steps.forEach { append("\n- ").append(it) }
        if (lastAssistant.isNotBlank()) {
            append("\n\n最近的中间结论（未经工具验证）：")
            append(lastAssistant.take(600))
        }
    }
}

/** P2 子代理干预接口：stop_agent/steer_agent 工具与任务卡终止按钮经此操作运行中的子代理。 */
interface SubagentControl {
    /** 按所属 spawn 调用 + 路序号定位（spawn 工具回收部分结果用）。 */
    fun find(callId: String?, index: Int): SubagentHandle?
    fun byId(id: String): SubagentHandle?

    /** 终止：协作标志 + 取消协程双保险；部分结果由 spawn 层从句柄读取。 */
    fun stop(id: String): Boolean

    /** 纠偏：消息入队，子代理下一轮开始前消费（不打断当前执行）。 */
    fun steer(id: String, message: String): Boolean
}
