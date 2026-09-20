package com.haoai.agent.agent.engine

import com.haoai.agent.agent.provider.ApiMessage
import com.haoai.agent.agent.provider.SseEvent
import com.haoai.agent.data.ProviderConfig
import java.io.File

/**
 * 能力委派：主模型看不了图/听不了音频时，把这一步交给专用模型代做。
 * 未配置委派目标时这些工具根本不注册（不占工具清单的 token）。
 */
/**
 * P2 委派：把图片交给视觉委派模型代看，返回描述文本。
 * 主模型不支持图像输入时，Agent 调 delegate_to_vision 走这里，任务不中断。
 */
internal suspend fun AgentEngine.delegateVisionRequest(path: String, question: String): String {
    val f = File(path)
    if (!f.exists() || !f.canRead()) return "委派失败：读不到文件 $path"
    if (f.length() > 8L * 1024 * 1024) return "委派失败：图片超过 8MB（${f.length() / 1024}KB）"
    val mime = when (path.substringAfterLast('.', "").lowercase()) {
        "png" -> "png"; "webp" -> "webp"; "gif" -> "gif"; else -> "jpeg"
    }
    val b64 = android.util.Base64.encodeToString(f.readBytes(), android.util.Base64.NO_WRAP)
    val q = question.ifBlank { "请详细描述这张图片的内容。" }
    return delegateCall("vision", "图像") { _ ->
        listOf(
            ApiMessage(
                role = "system",
                content = "你是视觉分析助手。仔细观察图片，用中文准确、详细地描述所见内容并回答用户的问题。只输出描述与结论，不要客套话。"
            ),
            ApiMessage(
                role = "user",
                content = null,
                parts = listOf(
                    com.haoai.agent.agent.provider.ApiContentPart(type = "text", text = q),
                    com.haoai.agent.agent.provider.ApiContentPart(
                        type = "image_url",
                        imageUrl = com.haoai.agent.agent.provider.ApiImageUrl("data:image/$mime;base64,$b64")
                    )
                )
            )
        )
    }
}

/** P2 委派：把音频交给语音委派模型转写，返回文本。 */
internal suspend fun AgentEngine.delegateAsrRequest(path: String): String {
    val f = File(path)
    if (!f.exists() || !f.canRead()) return "委派失败：读不到文件 $path"
    if (f.length() > 8L * 1024 * 1024) return "委派失败：音频超过 8MB"
    val fmt = when (path.substringAfterLast('.', "").lowercase()) {
        "wav" -> "wav"; "flac" -> "flac"; "ogg" -> "ogg"; "m4a", "mp4", "aac" -> "m4a"; else -> "mp3"
    }
    val b64 = android.util.Base64.encodeToString(f.readBytes(), android.util.Base64.NO_WRAP)
    return delegateCall("asr", "音频") { _ ->
        listOf(
            ApiMessage(
                role = "system",
                content = "你是语音转写引擎。把音频完整转写成文字，保留原始语言；无人声时输出 [无人声]。不要添加任何解释。"
            ),
            ApiMessage(
                role = "user",
                content = null,
                parts = listOf(
                    com.haoai.agent.agent.provider.ApiContentPart(
                        type = "input_audio",
                        inputAudio = com.haoai.agent.agent.provider.ApiInputAudio(data = b64, format = fmt)
                    )
                )
            )
        )
    }
}

/** 委派请求公共路径：按链序尝试（最多 2 个目标），收集文本；用量入账本。 */
internal suspend fun AgentEngine.delegateCall(
    kind: String,
    label: String,
    build: (ProviderConfig) -> List<ApiMessage>
): String {
    val chain = runCatching { delegateTarget?.invoke(kind) }.getOrNull().orEmpty()
    if (chain.isEmpty()) return "委派…未配置：请在 设置 → 模型大脑 → 模型切换 里指定${label}委派模型。"
    var lastErr = ""
    for ((dp, dkey) in chain.take(2)) {
        val r = runCatching {
            val client = auxClientFor?.invoke(dp) ?: httpClient
            val buf = StringBuilder()
            var pin = 0L
            var pout = 0L
            val t0 = System.currentTimeMillis()
            client.chatStream(dp, dkey, build(dp), emptyList()).collect { ev ->
                when (ev) {
                    is SseEvent.Delta -> buf.append(ev.text)
                    is SseEvent.Usage -> {
                        pin += ev.promptTokens.toLong()
                        pout += ev.completionTokens.toLong()
                    }
                    else -> {}
                }
            }
            ledgerLlm("delegate", pin, pout, System.currentTimeMillis() - t0, ok = true, model = dp.model)
            buf.toString().trim()
        }
        if (r.isSuccess && r.getOrNull().isNullOrBlank().not()) return r.getOrThrow()
        lastErr = r.exceptionOrNull()?.message?.take(120) ?: "委派模型返回空内容"
        android.util.Log.w("HaoDelegate", "委派($kind) 目标 ${dp.name}/${dp.model} 失败：$lastErr")
    }
    return "委派…失败：$lastErr（可改用 shell ffmpeg 等工具绕行）"
}
