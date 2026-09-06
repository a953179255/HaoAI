package com.haoai.agent.data

/**
 * 统一能力解析器（对齐 上游 的「目录 > 启发式 > 默认」合并链，替代三套并存逻辑）。
 *
 * 解析优先级：
 *   1. 用户手动覆盖（capsSource=manual，UI 勾选的模态，最高优先级，不被自动检测覆盖）
 *   2. 条目已存模态（models.dev 检测写回的 inputModalities/outputModalities）
 *   3. 旧 vision 三态字段（历史数据兼容）
 *   4. 模型名启发式（vision/tools/reasoning 关键词推测）
 *   5. 保守默认（text-in / text-out，tools/reasoning=未知不门控）
 *
 * 模态一律存裸名（text/image/audio/video/pdf）；OpenAI 系 "image_input" 等后缀形态
 * 在 ModelCatalog.normalizeModality 已归一，此处再兜底一次。
 */
object CapabilityResolver {

    data class Caps(
        val inputs: List<String>,
        val outputs: List<String>,
        /** null=未知（不门控） */
        val tools: Boolean?,
        /** null=未知（不门控） */
        val reasoning: Boolean?,
        /** 来源标记：manual / models.dev / legacy / guess / default（UI 徽章与提示词用） */
        val source: String
    ) {
        val hasImage: Boolean get() = "image" in inputs
        val hasVideo: Boolean get() = "video" in inputs
        val hasAudio: Boolean get() = "audio" in inputs
        val hasPdf: Boolean get() = "pdf" in inputs
        val isTextOutput: Boolean get() = outputs.isEmpty() || "text" in outputs

        /** 输入模态徽章序列（UI 用）：image/audio/video/pdf 的支持三态（null=未知）。 */
        fun inputBadge(mod: String): Boolean? = inputs.takeIf { source != "default" && source != "guess" }?.contains(mod)
    }

    private fun norm(list: List<String>?): List<String>? = list
        ?.map { ModelCatalog.normalizeModality(it) }
        ?.distinct()
        ?.takeIf { it.isNotEmpty() }

    /** 模型名 → 输入模态启发式（未命中返回 null=未知）。 */
    fun guessInputs(modelId: String): List<String>? {
        val m = modelId.lowercase()
        if (Regex("vision|vl-|-vl|glm-4v|qwen.*vl|llava|pixtral|4o|omni|gemini|gpt-4\\.1|gpt-5").containsMatchIn(m)) {
            return if (Regex("gemini.*video|veo|sora|kling|video").containsMatchIn(m))
                listOf("text", "image", "video", "audio") else listOf("text", "image")
        }
        if (Regex("asr|whisper|transcrib|audio|speech").containsMatchIn(m)) return listOf("text", "audio")
        if (Regex("deepseek-chat|deepseek-v\\d|deepseek-r\\d|glm-4$|glm-5$|qwen2?\\.5$|text-embed|llama-3").containsMatchIn(m)) {
            return listOf("text")
        }
        return null
    }

    fun resolve(entry: ModelEntry?, modelId: String): Caps {
        val manual = entry?.capsSource == "manual"
        val tools = entry?.tools
        val reasoning = entry?.reasoning

        // 1+2：条目已有确定模态数据（manual 或检测写回）。
        // capsSource==null 的模态数据只可能来自 v2 迁移（vision 物化）——检测写回必带
        // capsSource="models.dev"，故此处标 legacy 而非误报 models.dev。
        norm(entry?.inputModalities)?.let { ins ->
            return Caps(
                ins, norm(entry?.outputModalities) ?: listOf("text"),
                tools, reasoning,
                if (manual) "manual" else (entry?.capsSource ?: "legacy")
            )
        }
        // 3：旧 vision 三态
        entry?.vision?.let { v ->
            return Caps(
                if (v) listOf("text", "image") else listOf("text"),
                norm(entry.outputModalities) ?: listOf("text"),
                tools, reasoning, "legacy"
            )
        }
        // 4：名称启发式
        guessInputs(modelId)?.let { g ->
            return Caps(g, listOf("text"), tools, reasoning, "guess")
        }
        // 5：保守默认——图像按乐观处理（与旧 providerSupportsVision 行为一致：
        // 未知型号 true，失败由 friendlyError 引导换模型），其余仅 text
        return Caps(listOf("text", "image"), listOf("text"), tools, reasoning, "default")
    }

    /**
     * 能力声明提示词片段（移植 上游 LLMModel.capabilityPromptFragment 思路，
     * 按 HaoAI 工具生态改写绕行建议）。全模态齐备时返回 null 不注入，省 token。
     */
    fun capabilityPromptFragment(caps: Caps, delegateAvailable: Boolean = false): String? {
        val hasAll = caps.hasImage && caps.hasVideo && caps.hasAudio && caps.hasPdf
        if (hasAll && caps.tools != false) return null
        val natives = buildList {
            if (caps.hasImage) add("图像")
            if (caps.hasVideo) add("视频")
            if (caps.hasAudio) add("音频")
            if (caps.hasPdf) add("PDF")
        }
        val missing = buildList {
            if (!caps.hasImage) add("图像")
            if (!caps.hasVideo) add("视频")
            if (!caps.hasAudio) add("音频")
            if (!caps.hasPdf) add("PDF")
        }
        return buildString {
            appendLine("## 当前模型能力声明")
            appendLine("- 你当前使用的模型可直接处理：文本${if (natives.isEmpty()) "" else "、" + natives.joinToString("、")}。")
            if (missing.isNotEmpty()) {
                appendLine("- 无法直接处理：${missing.joinToString("、")}。涉及这些内容时【不要中断任务、不要假装看到了】，按以下顺序绕行：")
                if (delegateAvailable) {
                    appendLine("  1. 用委派工具交给专用模型处理：delegate_to_vision(path, question) 代看图片、transcribe_audio(path) 转写音频；")
                    appendLine("  2. 用 shell 工具转码提取（如 ffmpeg 抽视频关键帧后逐帧当图像分析、抽音轨转文字；pdftotext/pdftoppm 处理 PDF）；")
                    appendLine("  3. 用 screen/无障碍等运行时工具获取等效信息（如界面内容读控件树而非截图）；")
                    appendLine("  4. 确实无路可走时，明确告知用户该限制并给出替代方案（如换用支持该模态的模型），而不是让任务报错终止。")
                } else {
                    appendLine("  1. 用 shell 工具转码提取（如 ffmpeg 抽视频关键帧后逐帧当图像分析、抽音轨转文字；pdftotext/pdftoppm 处理 PDF）；")
                    appendLine("  2. 用 screen/无障碍等运行时工具获取等效信息（如界面内容读控件树而非截图）；")
                    appendLine("  3. 确实无路可走时，明确告知用户该限制并给出替代方案（如换用支持该模态的模型），而不是让任务报错终止。")
                }
            }
            if (caps.tools == false) {
                appendLine("- 本模型不支持工具调用：你只能纯对话，不要输出工具调用格式的文本。需要操作类任务时引导用户切换模型。")
            }
        }.trim()
    }
}
