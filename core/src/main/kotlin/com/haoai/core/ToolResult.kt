package com.haoai.core

/**
 * 工具执行的结果（B15：两端共用一份）。
 *
 * 字段顺序沿用 **PC 端旧版** —— PC 的调用点有位置传参（`ToolResult(msg, true)`），
 * 顺序动一个就是全体红；移动端末尾的 `imageDataUrl` 是它自己的注入通路
 * （浏览器截图的 data URL，引擎收到后追加一条带图 user 消息），PC 走 images 文件路径，
 * 两边各用各的字段、互不干扰。
 *
 * 分字段的理由（都是踩过的坑，原注释在 PC Tools.kt）：
 * - [images]：**文件路径**，引擎在本轮工具跑完后单独补一条 user 消息把像素递给模型 ——
 *   "屏幕理解"以前是模型在猜 png 里有什么；
 * - [media]：音视频路径只给界面摆播放器（字节走 /api/media），与 images 分家是因为
 *   图片能塞上下文、mp4 不能；
 * - [card]：渲染意图 generic|terminal|diff，前端按它挑卡；
 * - [diff]/[sub]：只给界面、**不进历史** —— 塞给模型纯属几百行 token 的浪费。
 */
data class ToolResult(
    val content: String,
    val error: Boolean = false,
    val images: List<String> = emptyList(),
    val media: List<String> = emptyList(),
    val card: String = "generic",
    val diff: String = "",
    val sub: String = "",
    /** 端侧多模态：直接带 data URL 的图（移动端浏览器截图注入用）；PC 不用。 */
    val imageDataUrl: String? = null
)
