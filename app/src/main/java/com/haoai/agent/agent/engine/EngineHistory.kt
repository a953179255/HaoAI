package com.haoai.agent.agent.engine

import com.haoai.agent.agent.model.ChatMessage
import com.haoai.agent.data.StoredMessage

/**
 * 会话历史进请求前的两道净化（纯函数，不碰引擎状态）。
 *
 * 单独成文件的理由：这两段是"协议兼容性"的最后防线，出错表现是**供应商 400 且坏历史已落库、
 * 会话永久卡死**，跟回合循环没有任何关系，混在 2400 行的引擎里既读不清也测不到。
 */

    /**
     * 剔除因裁剪/压缩而失去配对的消息：
     * 1) 没有对应 assistant(tool_calls) 的孤儿 tool 结果；
     * 2) 其全部调用都缺少结果的 assistant(tool_calls)。
     * 保证发给供应商的历史始终满足严格的调用配对约束。
     */
    /**
     * 修复历史中遗留的空 tool call id（如 deepseek-v4-flash 偶发流式返回 "id": ""）。
     * 请求要求 assistant.tool_calls[].id 非空且与 tool.tool_call_id 严格配对：
     * 按最近 assistant 轮次的调用队列、同名优先，为空 id 派生一致的替代 id；
     * 配不上对的孤儿 tool 结果直接丢弃（后续 pairSanitized 也会兜底清理）。
     */
internal fun List<com.haoai.agent.data.StoredMessage>.repairBlankCallIds(): List<com.haoai.agent.data.StoredMessage> {
        val dirty = any { (it.role == ChatMessage.ROLE_ASSISTANT && it.toolCalls.any { c -> c.id.isBlank() }) ||
            (it.role == ChatMessage.ROLE_TOOL && it.toolCallId.isNullOrBlank()) }
        if (!dirty) return this
        var seq = 0
        val out = mutableListOf<com.haoai.agent.data.StoredMessage>()
        // 当前 assistant 轮次可分配的 id：name -> 待消费 id 队列
        var pendingIds = LinkedHashMap<String, MutableList<String>>()
        for (m in this) {
            when (m.role) {
                ChatMessage.ROLE_ASSISTANT -> {
                    pendingIds = LinkedHashMap()
                    val calls = m.toolCalls.map { c ->
                        val id = if (c.id.isBlank()) "call_fix_${seq++}" else c.id
                        pendingIds.getOrPut(c.name) { mutableListOf() }.add(id)
                        c.copy(id = id)
                    }
                    out += m.copy(toolCalls = calls)
                }
                ChatMessage.ROLE_TOOL -> {
                    if (!m.toolCallId.isNullOrBlank()) { out += m; continue }
                    val id = pendingIds[m.toolName]?.removeFirstOrNull()
                        ?: pendingIds.values.firstOrNull { it.isNotEmpty() }?.removeFirstOrNull()
                    if (id != null) out += m.copy(toolCallId = id)
                }
                else -> out += m
            }
        }
        return out
    }

internal fun List<com.haoai.agent.data.StoredMessage>.pairSanitized(): List<com.haoai.agent.data.StoredMessage> {
        val calledIds = flatMap { if (it.role == ChatMessage.ROLE_ASSISTANT) it.toolCalls.map { c -> c.id } else emptyList() }
            .toSet()
        val step1 = filterNot { it.role == ChatMessage.ROLE_TOOL && (it.toolCallId == null || it.toolCallId !in calledIds) }
        val answeredIds = step1.filter { it.role == ChatMessage.ROLE_TOOL }.mapNotNull { it.toolCallId }.toSet()
        // 逐 call 过滤：多工具调用被中途取消时会产生「部分回答」的 assistant 消息，
        // 未回答的 call 若保留会导致 API 400，且坏历史已持久化、会话永久卡死
        val out = mutableListOf<com.haoai.agent.data.StoredMessage>()
        for (m in step1) {
            if (m.role == ChatMessage.ROLE_ASSISTANT && m.toolCalls.isNotEmpty()) {
                val answered = m.toolCalls.filter { it.id in answeredIds }
                if (answered.isEmpty()) continue
                out += if (answered.size == m.toolCalls.size) m else m.copy(toolCalls = answered)
            } else {
                out += m
            }
        }
        return out
    }
