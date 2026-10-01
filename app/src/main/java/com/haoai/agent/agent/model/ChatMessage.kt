package com.haoai.agent.agent.model

import java.util.concurrent.atomic.AtomicLong

/*
 * B15 第二片：会话模型的**定义**进了两端共用的 `com.haoai.core.Msg`。
 * 这里原地 typealias —— 同包同名，别的包 `import ...model.ChatMessage / ToolCallData`
 * 的现有 import 一个都不用改（typealias 同样可被 import）。
 *
 * 字段名统一到 PC 侧的规范（calls/callId/name/pt/ct/ms）：PC 的历史是手写 JSON 落盘、
 * 字段名即格式（538 条测试里有持久化锁）；而移动端磁盘存的是独立的 StoredMessage DTO
 * + 显式转换（SessionStore.toModel/toStored）—— 运行时模型改名**碰不到磁盘**。
 * 所以这边约 300 处字段引用要跟着改名，DTO 那边一个字都不动（编译器会精确指认：
 * 只有 ChatMessage 类型的接收者会报 Unresolved，StoredMessage 同名字段照常解析）。
 */
typealias ChatMessage = com.haoai.core.Msg
typealias ToolCallData = com.haoai.core.ToolCall

/*
 * 兜底 tool_call id 的序号：**进程内单调**，绝不按轮/按响应重置。
 *
 * 为什么单独拎出来：不少供应商流式不带 tool_call id（deepseek-v4-flash 直接回 `"id": ""`，
 * 回传空 id 会被服务端 400 拒），客户端只能自己生成。原先两个客户端各自用「本轮下标」生成
 * `call_0_0 / call_1_1 …`，计数器每轮归零 → 一个 15 轮的回合只产出 4 个不同 id，
 * 引擎与 UI 的按-id 查表全部跨轮串号（后果与根治点见 engine 的 [uniquifyCallIds]）。
 */
private val toolCallIdSeq = AtomicLong()

/** 生成一个不会与上一轮重复的兜底 call id。 */
fun newFallbackCallId(prefix: String = "call"): String = "${prefix}_${toolCallIdSeq.incrementAndGet()}"
