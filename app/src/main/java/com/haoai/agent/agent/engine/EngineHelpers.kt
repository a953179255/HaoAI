package com.haoai.agent.agent.engine
import com.haoai.core.takeSafe
import com.haoai.core.takeLastSafe

import com.haoai.agent.agent.model.ToolCallData
import com.haoai.agent.data.HaoJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * 引擎用到的纯文本/判定小工具（参数解析、脱敏、错误分类、记忆解析、开销估算、摘要行）。
 * 都不读引擎状态，所以从类里搬出来是零行为变化的搬运；留在类里只会让主循环文件更难读。
 */

    /** 参数解析：null = 原始 JSON 损坏（截断/编码错误），调用方必须显性报错而不是当空参执行。 */
internal fun parseArgs(json: String): JsonObject? =
        runCatching {
            HaoJson.json.parseToJsonElement(json.ifBlank { "{}" })
        }.getOrNull() as? JsonObject

    /**
     * C2 补口：config_set 补丁里的明文密钥不进会话 JSON 与压缩摘要上下文
     * （执行已按原始参数完成，历史回放只需协议有效的 arguments）。
     */
internal fun maskSecretArgs(calls: List<ToolCallData>): List<ToolCallData> =
        calls.map { c ->
            if (c.name == "config_set") {
                c.copy(argumentsJson = com.haoai.agent.data.ConfigFileBridge.maskApiKeys(c.argumentsJson))
            } else c
        }

    /**
     * 供应商瞬态错误（值得退避重试）：优先用异常携带的结构化状态码（429/408/5xx），
     * 无码时退回保守文本匹配（仅显式关键字，不再含 "http 5" 宽匹配——
     * 防 400 错误体里碰巧含 "http 500" 字样被误判而重发全上下文）。
     */
internal fun isTransientHttpError(e: Exception): Boolean {
        (e as? com.haoai.agent.agent.provider.ProviderHttpException)?.httpCode?.let { code ->
            return code == 429 || code == 408 || code >= 500
        }
        val m = e.message.orEmpty().lowercase()
        return "http 429" in m || "timeout" in m ||
            "timed out" in m || "connection reset" in m || "eofexception" in m || "stream stall" in m
    }

internal fun parseMemories(text: String): List<Pair<String, List<String>>> {
        val start = text.indexOf('[')
        val end = text.lastIndexOf(']')
        if (start < 0 || end <= start) return emptyList()
        return runCatching {
            val arr = HaoJson.json.parseToJsonElement(text.substring(start, end + 1)).jsonArray
            arr.mapNotNull { el ->
                val obj = el as? JsonObject ?: return@mapNotNull null
                val content = obj["content"]?.jsonPrimitive?.contentOrNull?.trim() ?: return@mapNotNull null
                if (content.isEmpty()) return@mapNotNull null
                val tags = runCatching {
                    obj["tags"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
                }.getOrDefault(emptyList())
                content.take(300) to tags
            }
        }.getOrDefault(emptyList())
    }

    /** E4a/E4b 工具定义 token 估算：真实序列化各工具 JSON 求和（兜底下限 3500）。 */
internal fun estimateToolsTokens(apiTools: List<com.haoai.agent.agent.provider.ApiTool>): Int = runCatching {
        apiTools.sumOf { t ->
            com.haoai.agent.ui.chat.ContextUsage.estimateStringTokens(
                com.haoai.agent.data.HaoJson.json.encodeToString(
                    com.haoai.agent.agent.provider.ApiTool.serializer(), t
                )
            )
        }
    }.getOrNull()?.coerceAtLeast(TOOLS_BASE_TOKENS) ?: TOOLS_BASE_TOKENS

    /** 从五段式交接文档提取「已完成/下一步」要点，作为当日事件落盘。 */
internal fun handoffEvent(summary: String): String {
        val picked = mutableListOf<String>()
        var section = ""
        for (raw in summary.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("#") -> section = line.trimStart('#').trim()
                line.startsWith("-") && (section.contains("已完成") || section.contains("下一步")) ->
                    picked.add(line.drop(1).trim())
            }
        }
        val body = picked.joinToString("；").take(200).ifBlank {
            summary.replace(Regex("[#*`>]"), "").lineSequence()
                .filter { it.isNotBlank() }.joinToString("；").take(180)
        }
        return "任务进展：$body"
    }

internal fun briefOf(call: ToolCallData): String =
        com.haoai.agent.agent.tools.ToolBrief.of(call.name, call.argumentsJson)

internal fun previewOf(content: String): String =
        content.lineSequence().firstOrNull()?.takeSafe(160) ?: ""

/**
 * 成本软提醒值不值得提。两个都成立才提：
 * - 本轮仍在调工具（否则下一轮就是最终回答，提了也没东西可省）；
 * - 任务清单还有未完成项（否则任务已经做完，提醒只会变成插在文末的噪音）。
 *
 * 判据单独成函数是为了能被测到——真实触发一次 70% 需要攒到十几万 token，
 * 设备上不构造就没有回归保护。
 */
internal fun budgetNudgeWorthIt(
    calledToolsThisRound: Boolean,
    todoHasOpenItems: Boolean
): Boolean = calledToolsThisRound && todoHasOpenItems
