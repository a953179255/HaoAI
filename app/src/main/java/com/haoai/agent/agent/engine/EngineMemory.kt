package com.haoai.agent.agent.engine

import com.haoai.agent.agent.model.ChatMessage
import com.haoai.agent.agent.provider.ApiMessage
import com.haoai.agent.agent.provider.SseEvent
import com.haoai.agent.agent.tools.TextCap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * 记忆提取与运行账本：回合结束后异步沉淀 + LLM/工具调用记账。
 * 两条都遵守同一条红线：**写失败绝不影响对话主流程**（全部 runCatching 包裹）。
 */
internal fun AgentEngine.maybeExtractMemory() {
    val bank = memoryBank ?: return
    val scope = backgroundScope ?: return
    if (!memoryEnabled || !autoLearn || depth != 0) return
    // 端侧模型跳过自动记忆提取：辅助请求会冲掉 llama-server 单 slot 前缀缓存，
    // 让 Agent 工具循环的每轮请求全量重算 prefill（手机上每轮多花几十秒）
    if (provider.baseUrl.contains("127.0.0.1") || provider.baseUrl.startsWith("local")) return
    val recent = session.messages
        .filter {
            (it.role == ChatMessage.ROLE_USER || it.role == ChatMessage.ROLE_ASSISTANT) &&
                it.content.isNotBlank()
        }
        .takeLast(8)
    // 门槛：没有实质用户输入（纯指令/太短）就不值得沉淀，直接跳过
    val userText = recent.lastOrNull { it.role == ChatMessage.ROLE_USER }?.content.orEmpty()
    if (userText.length < 12) return
    val transcript = recent
        .joinToString("\n") { m ->
            val who = if (m.role == ChatMessage.ROLE_USER) "用户" else "助手"
            "$who：${m.content.take(500)}"
        }
        .trim()
    if (transcript.length < 80) return
    // M-1 修复①：提取提示词携带现有记忆清单——换述级重复在源头就不让提
    // （remember() 的归一化去重只抓标点/空白差异，"的/。"差异与措辞改写必然穿透）
    val existingBlock = run {
        val actives = bank.all() // 已滤软失效，createdAt 降序：最近讨论过的事实正是重复风险最高的
        if (actives.isEmpty()) ""
        else "\n\n[已有记忆清单]（提取前逐条对照，与下列相同或仅措辞不同的内容一律不要输出）：" +
            actives.take(30).joinToString("\n") { "- ${it.content.take(60)}" }
    }
    scope.launch {
        // 5.3 记忆提取路由链（点6）：主+备用按序尝试，空配置回落主模型
        val memChain = runCatching { memoryTarget?.invoke() }.getOrNull() ?: emptyList()
        val memTargets = if (memChain.isEmpty()) listOf(provider to apiKey) else memChain
        for ((memProv, memKey) in memTargets) {
            val r = runCatching {
                val memClient = auxClientFor?.invoke(memProv) ?: httpClient
                val buf = StringBuilder()
                var mp = 0L; var mc = 0L
                val mstart = System.currentTimeMillis()
                memClient.chatStream(
                    memProv, memKey,
                    listOf(
                        ApiMessage(role = "system", content = EXTRACT_SYSTEM + existingBlock),
                        ApiMessage(role = "user", content = TextCap.middle(transcript, 4000))
                    ),
                    emptyList()
                ).collect { ev ->
                    when (ev) {
                        is SseEvent.Delta -> buf.append(ev.text)
                        is SseEvent.Usage -> { mp += ev.promptTokens; mc += ev.completionTokens }
                        else -> {}
                    }
                }
                ledgerLlm("memory", mp, mc, System.currentTimeMillis() - mstart, ok = true, model = memProv.model)
                parseMemories(buf.toString()).take(2).forEach { (content, tags) ->
                    // M-1 修复②：入库前近冲突闸门——与既有条目词面相近（换述）即跳过。
                    // auto 记忆 imp=2 定位是兜底而非主通道：真正的信息更新由模型在对话里
                    // 主动 save（M3 近冲突提示引导 merge），提取层漏记一条可接受、污染一条难清理。
                    val nearDup = bank.nearConflicts(content).firstOrNull()
                    if (nearDup != null) {
                        android.util.Log.i(
                            "HaoMemory",
                            "auto 提取跳过（与 ${nearDup.id} 近冲突）：${content.take(40)}"
                        )
                    } else if (turnTainted) {
                        // B2：本回合抓过网页/外部内容 → 沉淀一律 untrusted，不进启动注入
                        bank.remember(
                            content, tags, importance = 2, source = "auto",
                            origin = "untrusted"
                        )
                    } else {
                        bank.remember(
                            content, tags, importance = 2, source = "auto",
                            origin = "agent"
                        )
                    }
                }
            }
            if (r.isSuccess) break
            (r.exceptionOrNull() as? kotlinx.coroutines.CancellationException)?.let { throw it }
            android.util.Log.w("HaoMemory", "记忆提取目标 ${memProv.name}/${memProv.model} 失败，降级链上下一个")
        }
    }
}

/** 5.4 运行账本：LLM 调用记账（写失败静默，绝不影响主流程）。 */
internal fun AgentEngine.ledgerLlm(purpose: String, promptTokens: Long, completionTokens: Long, durationMs: Long, ok: Boolean, model: String? = null, sessionId: String? = null) {
    if (purpose != "chat") android.util.Log.d("HaoLedger", "llm purpose=$purpose model=${model ?: provider.model} pin=$promptTokens pout=$completionTokens ok=$ok")
    com.haoai.agent.data.UsageLedger.add(
        com.haoai.agent.data.UsageLedger.Entry(
            kind = "llm", ts = System.currentTimeMillis(),
            sessionId = sessionId ?: session.id,
            purpose = purpose, model = model ?: provider.model,
            promptTokens = promptTokens.toInt(), completionTokens = completionTokens.toInt(),
            durationMs = durationMs, ok = ok
        )
    )
}

internal fun AgentEngine.ledgerTool(name: String, durationMs: Long, ok: Boolean, decision: String) {
    com.haoai.agent.data.UsageLedger.add(
        com.haoai.agent.data.UsageLedger.Entry(
            kind = "tool", ts = System.currentTimeMillis(),
            sessionId = session.id,
            tool = name, risk = policy.riskOf(name).name,
            policyDecision = decision, durationMs = durationMs, ok = ok
        )
    )
}
