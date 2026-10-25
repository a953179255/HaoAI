package com.haoai.agent.agent.engine.compaction

import com.haoai.agent.agent.model.ChatMessage
import com.haoai.agent.agent.provider.ApiMessage
import com.haoai.agent.agent.provider.ApiTool
import com.haoai.agent.agent.provider.ProviderClient
import com.haoai.agent.agent.provider.SseEvent
import com.haoai.agent.data.ProviderConfig
import com.haoai.agent.data.toModel
import com.haoai.agent.data.toStored
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A4 压缩改造的回归锁（2026-10-15）。
 * 覆盖：摘要输入分档装填的预算行为 + 摘要失败不炸回合循环。
 */
class CompactionTieringTest {

    /** 摘要调用必失败（模拟摘要模型不可用）。 */
    private class FailingSummaryClient : ProviderClient {
        override suspend fun chatStream(
            provider: ProviderConfig,
            apiKey: String,
            messages: List<ApiMessage>,
            tools: List<ApiTool>,
            reasoningEffort: String?
        ): Flow<SseEvent> = flow { throw java.io.IOException("summary model down") }

        override suspend fun testConnection(provider: ProviderConfig, apiKey: String) =
            Result.success("ok")
    }

    private fun user(text: String) =
        ChatMessage(role = ChatMessage.ROLE_USER, content = text).toStored().toModel()

    private fun toolResult(text: String) =
        ChatMessage(role = ChatMessage.ROLE_TOOL, content = text, callId = "c1", name = "bash")
            .toStored().toModel()

    private fun provider() = ProviderConfig(
        id = "fake", name = "fake", baseUrl = "http://127.0.0.1:1/v1", model = "m"
    )

    /**
     * 摘要模型失败时 compact 必须把原始异常上抛——由 compactWithChain 的链层捕获
     * （降级到链上下一个摘要目标；全失败返回 null，调用方走 A4 免摘要换窗）。
     * 旧版在 compact 内落 fallbackTruncate 把失败伪装成"成功"：链式降级/换窗
     * 两条兜底永不触发，垃圾摘要还会永久挂进 compactionSummary 每次请求重发。
     */
    @Test
    fun compactThrowsOnSummaryFailureForChainFallback() = runBlocking {
        val manager = CompactionManager(client = FailingSummaryClient())
        val msgs = (1..10).map { user("任务 $it") }
        val result = runCatching {
            manager.compact(msgs, null, provider(), "key", 32_768)
        }
        assertTrue("摘要失败必须上抛给链层降级（fallback 已移除）", result.isFailure)
        assertTrue(
            "抛的应是原始 IOException（保留链层日志与诊断信息）",
            result.exceptionOrNull() is java.io.IOException
        )
    }

    /**
     * 预修剪：旧工具结果被截到 toolOutputKeepChars，最近 6 条不动。
     * （A4 分档与预修剪叠加，保证总输入进一步收缩。）
     */
    @Test
    fun prePruneKeepsRecentAndTrimsOld() = runBlocking {
        val manager = CompactionManager(
            client = FailingSummaryClient(),
            settings = CompactionSettings(toolOutputKeepChars = 100)
        )
        val msgs = (1..10).map { toolResult("y".repeat(500)) }
        val pruned = manager.prePruneToolOutputs(msgs)
        assertEquals(10, pruned.size)
        // cutoff = len-6：最后 6 条不修剪
        assertTrue("最近的工具结果不得修剪", pruned.last().content!!.length == 500)
        assertTrue("旧工具结果应修剪到 100+标记", pruned.first().content!!.length < 200)
    }
}
