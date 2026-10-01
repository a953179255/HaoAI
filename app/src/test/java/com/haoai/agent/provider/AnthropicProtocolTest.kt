package com.haoai.agent.provider

import com.haoai.agent.agent.provider.AnthropicClient
import com.haoai.agent.agent.provider.ApiMessage
import com.haoai.agent.agent.provider.ApiTool
import com.haoai.agent.agent.provider.ApiToolCall
import com.haoai.agent.agent.provider.ApiFunctionCall
import com.haoai.agent.agent.provider.ApiToolDef
import com.haoai.agent.agent.provider.SseEvent
import com.haoai.agent.data.HaoJson
import com.haoai.agent.data.ProviderConfig
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2.3 Anthropic 原生协议纯逻辑单测：消息/工具转换、请求 JSON 结构、SSE 流解析状态机。
 * 不发真实网络请求。
 */
class AnthropicProtocolTest {

    private fun obj(s: String) = HaoJson.json.parseToJsonElement(s).jsonObject

    // ---------- URL 规整 ----------

    @Test
    fun `normalizeMessagesUrl 补 v1 与 messages`() {
        assertEquals("https://api.anthropic.com/v1/messages", AnthropicClient.normalizeMessagesUrl("https://api.anthropic.com"))
        assertEquals("https://api.anthropic.com/v1/messages", AnthropicClient.normalizeMessagesUrl("https://api.anthropic.com/"))
        assertEquals("https://gw.example.com/api/v1/messages", AnthropicClient.normalizeMessagesUrl("https://gw.example.com/api/v1"))
        assertEquals("https://gw.example.com/v9/messages", AnthropicClient.normalizeMessagesUrl("https://gw.example.com/v9/messages"))
    }

    // ---------- 消息转换 ----------

    @Test
    fun `system 抽顶层 tool 消息转 tool_result 连续角色合并`() {
        val messages = listOf(
            ApiMessage("system", "你是助手"),
            ApiMessage("user", "你好"),
            ApiMessage("assistant", "", toolCalls = listOf(
                ApiToolCall("t1", function = ApiFunctionCall("read", "{\"path\":\"a.txt\"}"))
            )),
            ApiMessage("tool", "文件内容", toolCallId = "t1"),
            ApiMessage("tool", "第二条", toolCallId = "t2"),
            ApiMessage("user", "继续")
        )
        val c = AnthropicClient.convertMessages(messages)
        assertEquals("你是助手", c.system)
        // 连续 tool → 合并成一条 user；tool_result(user) 与后续 user 文本同样合并（Anthropic 要求交替）
        assertEquals(listOf("user", "assistant", "user"), c.messages.map { it.first })
        val toolUse = c.messages[1].second.single().toJson()
        assertEquals("tool_use", toolUse["type"]?.toString()?.trim('"'))
        val toolResult = c.messages[2].second[0].toJson()
        assertEquals("tool_result", toolResult["type"]?.toString()?.trim('"'))
        assertEquals("t1", toolResult["tool_use_id"]?.toString()?.trim('"'))
    }

    @Test
    fun `data URL 图片转 base64 source`() {
        val (mt, b64) = AnthropicClient.parseDataUrl("data:image/jpeg;base64,QUJD")!!
        assertEquals("image/jpeg", mt)
        assertEquals("QUJD", b64)
        assertNull(AnthropicClient.parseDataUrl("https://example.com/a.png"))

        val c = AnthropicClient.convertMessages(
            listOf(
                ApiMessage(
                    "user", parts = listOf(
                        com.haoai.agent.agent.provider.ApiContentPart(type = "text", text = "看图"),
                        com.haoai.agent.agent.provider.ApiContentPart(
                            type = "image_url",
                            imageUrl = com.haoai.agent.agent.provider.ApiImageUrl("data:image/png;base64,XYZ")
                        )
                    )
                )
            )
        )
        val blocks = c.messages.single().second
        assertEquals("image", blocks[1].toJson()["type"]?.toString()?.trim('"'))
        assertEquals("XYZ", blocks[1].toJson()["source"]?.jsonObject?.get("data")?.toString()?.trim('"'))
    }

    // ---------- 请求 JSON ----------

    @Test
    fun `请求 JSON 结构 system 顶层 tools 为 input_schema`() {
        val provider = ProviderConfig("p", "gw", "https://gw.example.com", "claude-x", protocol = "anthropic")
        val converted = AnthropicClient.convertMessages(listOf(ApiMessage("system", "sys"), ApiMessage("user", "hi")))
        val json = AnthropicClient.buildRequestJson(
            provider, converted,
            listOf(ApiTool(function = ApiToolDef("read", "读文件", obj("""{"type":"object"}""")))),
            stream = true
        )
        assertEquals("claude-x", json["model"]?.toString()?.trim('"'))
        assertEquals(4096, json["max_tokens"]?.toString()?.toInt()) // 云端未配置 max_tokens 时兜底 4096
        assertEquals("sys", json["system"]?.toString()?.trim('"'))
        assertEquals(true, json["stream"]?.toString()?.toBoolean())
        assertEquals("input_schema", json["tools"]?.jsonArray?.get(0)?.jsonObject?.keys?.find { it == "input_schema" })
    }

    // ---------- SSE 流解析 ----------

    @Test
    fun `SSE 解析 文本流 tool_use 分片 input_json_delta stop_reason 与 usage`() {
        val state = AnthropicClient.StreamState()
        val feed = listOf(
            """{"type":"message_start","message":{"usage":{"input_tokens":120}}}""",
            """{"type":"content_block_start","index":0,"content_block":{"type":"text"}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"你好"}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"思考"}}""",
            """{"type":"content_block_stop","index":0}""",
            """{"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"tu_1","name":"read"}}""",
            """{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"pat"}}""",
            """{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"h\":\"a\"}"}}""",
            """{"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":55}}""",
            """{"type":"message_stop"}"""
        )
        val deltas = StringBuilder()
        val reasonings = StringBuilder()
        for (line in feed) {
            for (ev in state.handle(obj(line))) {
                when (ev) {
                    is SseEvent.Delta -> deltas.append(ev.text)
                    is SseEvent.Reasoning -> reasonings.append(ev.text)
                    else -> Unit // Completed/Usage 在 chatStream 流结束时统一发，不经 handle
                }
            }
        }
        assertEquals("你好", deltas.toString())
        assertEquals("思考", reasonings.toString())
        val calls = state.buildToolCalls()
        assertEquals(1, calls?.size)
        assertEquals("tu_1", calls?.get(0)?.id)
        assertEquals("read", calls?.get(0)?.name)
        assertEquals("{\"path\":\"a\"}", calls?.get(0)?.args) // 分片 JSON 累积后完整
        assertEquals(120, state.inputTokens)
        assertEquals(55, state.outputTokens)
    }

    @Test
    fun `SSE error 事件转可读错误`() {
        val state = AnthropicClient.StreamState()
        state.handle(obj("""{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}"""))
        assertEquals("Overloaded", state.errorMessage)
    }

    @Test
    fun `ping 与未知事件被忽略`() {
        val state = AnthropicClient.StreamState()
        assertTrue(state.handle(obj("""{"type":"ping"}""")).isEmpty())
        assertTrue(state.handle(obj("""{"type":"unknown_future_event"}""")).isEmpty())
        assertNull(state.errorMessage)
    }

    // ---------- 工具定义映射 ----------

    @Test
    fun `空 argumentsJson 的 tool_use 转空对象 input`() {
        val b = AnthropicClient.Block.ToolUse("t1", "bash", "")
        val j = b.toJson()
        assertEquals("bash", j["name"]?.toString()?.trim('"'))
        assertTrue(j["input"]?.jsonObject?.isEmpty() == true)
    }
}
