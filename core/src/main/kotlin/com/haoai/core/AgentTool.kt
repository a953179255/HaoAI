package com.haoai.core

import kotlinx.serialization.json.JsonObject

/**
 * 工具契约（B15：两端共用一份接口）。
 *
 * 两端的分歧点由泛型 [C] 吸收：PC 的上下文是 `ToolCtx`（审批闸、快照、工作区），
 * 手机端是它自己的 `ToolContext`（无障碍、TODO、网络缓存）——**接口共用、上下文各带各的**，
 * 这是能在一天内落地的边界。`suspend` 取手机端的形状（它跑在协程里）；
 * PC 的引擎是阻塞线程模型，在唯一那个调用点用 `runBlocking` 包一层
 * （pc Engine 的 tool 执行处），23 把工具的函数体照旧是普通阻塞代码。
 *
 * 为什么不是抽象类：手机端 69 把工具是 `interface + override val` 写法，
 * 抽象类会逼它们全部改成构造参数风格 —— 接口让两边的实现体**一行结构都不动**
 * （PC 侧的中间抽象类继续存在，只是多 implements 这一个接口）。
 */
interface AgentTool<in C> {
    /** 工具名：模型按它调用，去重与开关也按它。 */
    val name: String

    /** 一句话说明书（给模型看的 desc；provider 序列化时映射成 API 的 description）。 */
    val desc: String

    /** JSON Schema 参数表 —— 两端统一由 pc 的 `schema(...)` 帮助器产出（形状有 ToolSchemaTest 钉）。 */
    val params: JsonObject

    suspend fun run(args: JsonObject, ctx: C): ToolResult
}
