package com.haoai.agent.agent.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * 能力委派工具（P2，对齐 上游 的 Vision Group / Voice Group）：
 * 主模型缺某模态时，把媒体交给设置里配置的「委派模型」代看/代听，
 * 结果以文本回传主模型——能力缺失不等于任务失败。
 *
 * 委派实现由 VM 注入（runner 闭包持有 AppContainer：解析委派模型 → 读文件 →
 * 一次性请求 → 收集文本）；工具本身只负责参数校验与结果封装。
 *
 * - delegate_to_vision：图像代看（委派模型需支持 image-in）
 * - transcribe_audio：音频转写（委派模型需支持 audio-in）
 *
 * 未配置委派模型时 runner 返回以「委派…未配置」开头的指引文案，工具标记 isError，
 * Agent 据此退回 shell 绕行或告知用户。
 */
class DelegateVisionTool(
    private val runner: suspend (String, String) -> String
) : Tool {
    override val name = "delegate_to_vision"
    override val description =
        "把图片交给视觉委派模型代看（当前模型不支持图像输入时用）：传图片本机路径与想问的问题，" +
            "返回该模型对图片的详细文字描述（可用于截图/照片/图表理解）。未配置委派模型时会提示不可用。"
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("path") { put("type", "string"); put("description", "图片文件本机路径（绝对路径）") }
            putJsonObject("question") { put("type", "string"); put("description", "想从图片里了解什么") }
        }
        putJsonArray("required") { add(JsonPrimitive("path")); add(JsonPrimitive("question")) }
    }

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            val path = args.optString("path").trim()
            val question = args.optString("question").trim()
            if (path.isBlank()) return@withContext ToolResult("path 不能为空", true)
            runCatching { runner(path, question) }
                .fold(
                    onSuccess = { out -> ToolResult(out, isError = out.startsWith("委派")) },
                    onFailure = { ToolResult("委派看图失败：${it.message}", true) }
                )
        }
}

class TranscribeAudioTool(
    private val runner: suspend (String) -> String
) : Tool {
    override val name = "transcribe_audio"
    override val description =
        "把音频交给语音委派模型转写成文字（当前模型不支持音频输入时用）：传音频本机路径，" +
            "返回转写文本。视频可先用 shell ffmpeg 抽音轨再转写。未配置委派模型时会提示不可用。"
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("path") { put("type", "string"); put("description", "音频文件本机路径（wav/mp3/m4a 等）") }
        }
        putJsonArray("required") { add(JsonPrimitive("path")) }
    }

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            val path = args.optString("path").trim()
            if (path.isBlank()) return@withContext ToolResult("path 不能为空", true)
            runCatching { runner(path) }
                .fold(
                    onSuccess = { out -> ToolResult(out, isError = out.startsWith("委派")) },
                    onFailure = { ToolResult("委派转写失败：${it.message}", true) }
                )
        }
}
