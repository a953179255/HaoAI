package com.haoai.core

import java.util.UUID

/**
 * 一条会话消息（B15 第二片：两端共用的**会话模型**）。
 *
 * 规范名 = PC 侧 —— PC 的历史是**手写 JSON** 落盘（`Engine.persist` 逐字段 put），
 * 字段名就是磁盘格式，动一个字 = 所有旧会话读不出来（538 条测试里有持久化锁）。
 * 移动端磁盘存的是独立的 `StoredMessage` DTO + 显式转换（`toModel/toStored`），
 * 运行时模型改名**碰不到磁盘** —— 所以"往哪边对齐"不是五五开：只能往 PC 对。
 *
 * 并集字段（PC 忽略、默认值让 PC 的手写序列化完全不受影响）：
 * - [id]：移动端长按操作/截断定位用；PC 不写不读；
 * - [error] / [imageData] / [audioPath] / [videoPath] / [model] / [ts]：移动端展示与附件通路。
 *
 * role ∈ system|user|assistant|tool。
 */
data class Msg(
    val role: String,
    val content: String?,
    val calls: List<ToolCall> = emptyList(),
    val callId: String? = null,
    /** 工具结果属于哪把工具。不存这个，历史回放时工具卡就只剩一个空壳。 */
    val name: String = "",
    /**
     * 模型的思考过程（DeepSeek/llama.cpp 的 `reasoning_content`）。
     *
     * 以前整个字段被直接丢掉：界面上看不到模型在想什么，而*长任务一旦答错，
     * 用户完全无从判断它是理解错了还是工具用错了*。移动端早就有思考链卡，
     * PC 端这次补上。存下来而不是只流一次，是为了刷新页面与重开会话还能展开看。
     */
    val reasoning: String? = null,
    /**
     * 工具卡上的行级 diff（只给界面，不发模型）。
     *
     * 存下来是为了"刷新之后还能审阅这次改了什么"——只随 SSE 流一次的话，
     * 页面一刷新 diff 就没了，而用户往往正是看完回答才回头去核对改动。
     */
    val diff: String = "",
    /** 子任务的中间过程（只给界面，不发模型）。 */
    val sub: String = "",
    /**
     * 用户对这一步的审批结论（"允许一次 / 本任务都允许 / 写了规则 / 拒绝"）。
     *
     * 之前只随 SSE 流一次：内联卡答完就地收起，刷新之后卡没了，
     * 于是"这个文件到底是用户点头写的还是自动写的"在历史里查不出来。
     */
    val note: String = "",
    /**
     * 这一轮里引擎发过的小字（重试、降级、达到上限…），只给界面，**不发模型**。
     *
     * 为什么要挂到消息上而不是只随 SSE 流一次：回合收尾时前端会 `hydrate` 重建这一屏，
     * 瞬时插进去的 `.notice` 节点跟着一起没 —— 实测是"插入 4 次、移除 4 次"，
     * 于是"这一轮是降级后的模型答的"这件事在人看完答案之后查不出来。
     * 同一套办法早就用在 [note] 与 [diff] 上。
     */
    val notice: String = "",
    /**
     * 随这条消息一起发给模型的图片（**存的是路径**，发请求时才编码成 data URL）。
     * 一张截图编码后 3~4 MB，会话文件会被自己撑爆，而图片本来就在工作区里躺着。
     */
    val images: List<String> = emptyList(),
    /** 工具产出的音视频文件路径（与 [images] 同样只存路径）：界面按它摆播放器。 */
    val media: List<String> = emptyList(),
    /** 这一回合自己的用量与耗时（不是会话累计），画在回答下面那行小字。 */
    val pt: Int = 0,
    val ct: Int = 0,
    val ms: Long = 0L,
    // ---- 以下为移动端并集（PC 不写不读，全部带默认值）----
    /** 消息唯一 id（移动端长按操作/截断/搜索定位用；旧 JSON 缺省时补新生成）。 */
    val id: String = UUID.randomUUID().toString(),
    /** 这条是不是一条失败的工具结果/消息（移动端渲染 ⚠ 用）。 */
    val error: Boolean = false,
    /** 用户附加图片的 data URL（base64），仅端侧多模态模型使用。 */
    val imageData: String? = null,
    /** 用户附加音频的本机文件路径；模型有 audio-in 时读取转 input_audio。 */
    val audioPath: String? = null,
    /** 用户附加视频的本机文件路径；模型没视频入时走 ffmpeg 抽帧绕行。 */
    val videoPath: String? = null,
    /** 生成该回复的模型名（统计行/操作面板元信息展示）。 */
    val model: String? = null,
    /** 这条消息思考段的耗时（reasoning 首字→尾字 delta 的墙钟差）。
     *  PC 不写不读；移动端思考摘要行「深度思考 N · X.X秒 · N字」用。null=未知。 */
    val reasoningMs: Long? = null,
    /** 消息时间戳（移动端列表展示）。 */
    val ts: Long = System.currentTimeMillis()
) {
    companion object {
        const val ROLE_SYSTEM = "system"
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"
        const val ROLE_TOOL = "tool"
    }
}

/** 一次工具调用（wire 上叫 tool_calls；参数是 JSON 字符串原样透传）。 */
data class ToolCall(val id: String, val name: String, val args: String)
