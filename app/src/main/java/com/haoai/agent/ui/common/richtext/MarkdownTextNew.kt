package com.haoai.agent.ui.common.richtext

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup

/**
 * 方案 A 新 MarkdownText：DOM 管线版。
 *
 * 解析策略（上游 MarkdownBlock 同款）：
 * - 首帧同步解析防闪烁（remember 初始值）
 * - 文本变化时 snapshotFlow + mapLatest 在 Dispatchers.Default 后台重解析，
 *   陈旧任务自动取消（打字速率 >> 解析速率时天然合并）
 * - settledBoundary 冻结前缀退役：intellij-markdown 产全局 AST，不可拼接；
 *   全量解析 + 后台线程 + 40ms flusher 批处理已足够（上游 生产验证）
 *
 * 流式特效：
 * - 未闭合 $$ 虚拟闭合 → prepareStreamingSource()（避免 "$$ 字面闪现"）
 * - 打字机渐显 / 表格骨架 / 代码块"生成中…"挂载点在 HtmlNodeRenderer 段落与
 *   块级渲染器内，随渲染层 remember 键联动，不影响解析层
 */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    streaming: Boolean = false,
    showCursor: Boolean = false
) {
    // 首帧同步：remember 初始即解析（防"先空白后跳内容"）
    var html by remember { mutableStateOf(runCatching { MarkdownPipeline.toHtml(prepareStreaming(text)) }.getOrDefault("")) }
    val latest by androidx.compose.runtime.rememberUpdatedState(text)

    LaunchedEffect(Unit) {
        snapshotFlowOf { latest }
            .distinctUntilChanged()
            .mapLatest { src ->
                withContext(Dispatchers.Default) { MarkdownPipeline.toHtml(prepareStreaming(src)) }
            }
            .catch { it.printStackTrace() }
            .collect { html = it }
    }

    val document = remember(html) {
        runCatching { Jsoup.parse(html) }.getOrElse { Jsoup.parse("") }
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        document.body().childNodes().forEach { node ->
            HtmlNodeRenderer.renderNode(node)
        }
    }
}

/**
 * 流式期源文本预处理：未闭合 $$ 按保守策略补虚拟闭合（复用旧 settledBoundary 的
 * 围栏/公式状态扫描语义，避开代码块），闭合到达后自然定型，避免 "$$...字面闪现"。
 */
private fun prepareStreaming(src: String): String {
    var inFence = false
    var inMath = false
    val lines = src.split('\n')
    for (raw in lines) {
        val trimmed = raw.trim()
        if (trimmed.startsWith("```")) { inFence = !inFence; continue }
        if (!inFence) {
            if (trimmed == "$$") inMath = !inMath
        }
    }
    return if (inMath) src + "\n$$" else src
}

// snapshotFlow 简写（保持 import 面干净）
private fun <T> snapshotFlowOf(block: () -> T) =
    androidx.compose.runtime.snapshotFlow(block)
