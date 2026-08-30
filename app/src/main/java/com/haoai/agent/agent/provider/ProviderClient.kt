package com.haoai.agent.agent.provider

import com.haoai.agent.data.ProviderConfig
import kotlinx.coroutines.flow.Flow

/**
 * 供应商协议客户端抽象（2.3）：引擎与内部 LLM 调用（压缩/记忆固化）只依赖此接口，
 * 由 AppContainer.clientFor(provider) 按配置的 protocol 选择实现。
 * 事件语义与 OpenAI 兼容流一致（SseEvent），协议差异在实现内部消化。
 */
interface ProviderClient {
    suspend fun chatStream(
        provider: ProviderConfig,
        apiKey: String,
        messages: List<ApiMessage>,
        tools: List<ApiTool>,
        reasoningEffort: String? = null
    ): Flow<SseEvent>

    suspend fun testConnection(provider: ProviderConfig, apiKey: String): Result<String>
}
