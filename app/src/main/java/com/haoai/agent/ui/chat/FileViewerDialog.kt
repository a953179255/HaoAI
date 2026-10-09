package com.haoai.agent.ui.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import com.haoai.agent.ui.UiFilePath
import com.haoai.agent.ui.common.CodeHighlight
import com.haoai.agent.ui.common.FullscreenCodeDialog
import com.haoai.agent.ui.common.splitAnnotatedPerLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 批2c：工作区文件只读查看器（路径芯片点开）。
 *
 * 形态零新造：读文件（后台线程）→ 按扩展名高亮 → 按行拆分 → 直接复用批2b 的
 * 全屏代码壳 [FullscreenCodeDialog]（行号槽/字号步进/换行开关同款）。
 * 只读是硬口径：这里没有任何写回路径，越界防线在定位层（locateWorkspaceFile）
 * 已经挡下工作区外路径，芯片只可能是工作区内的普通文件。
 *
 * 防护：≥1MB 的文件只读前 1MB（查看器是"看一眼"，不是文件管理器）；
 * 超 2000 行只展示前 2000 行并在标题标注；含 NUL 的字节流按二进制拒渲。
 */

private const val VIEWER_MAX_BYTES = 1L shl 20   // 1MB
private const val VIEWER_MAX_LINES = 2000

@Composable
internal fun FileViewerDialog(ref: UiFilePath, onDismiss: () -> Unit) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    var result by remember(ref.absPath) {
        mutableStateOf<Pair<List<AnnotatedString>, String>?>(null)
    }
    LaunchedEffect(ref.absPath, dark) {
        result = withContext(Dispatchers.IO) { loadViewerLines(ref, dark) }
    }
    val lines = result?.first ?: listOf(AnnotatedString("加载中…"))
    val note = result?.second.orEmpty()
    FullscreenCodeDialog(
        title = if (note.isEmpty()) ref.relPath else "${ref.relPath} · $note",
        lines = lines,
        dark = dark,
        onDismiss = onDismiss
    )
}

/**
 * 读文件 → (高亮按行拆分, 标题后缀说明)。失败/不存在/二进制都出一行提示而非崩。
 * 纯函数（无组合态），供 FileViewerDialog 的 IO 协程直调。
 */
private fun loadViewerLines(
    ref: UiFilePath,
    dark: Boolean
): Pair<List<AnnotatedString>, String> {
    val colors = if (dark) CodeHighlight.darkColors() else CodeHighlight.lightColors()
    val msg: (String) -> Pair<List<AnnotatedString>, String> = {
        Pair(listOf(AnnotatedString(it, spanStyles = listOf(
            androidx.compose.ui.text.AnnotatedString.Range(
                androidx.compose.ui.text.SpanStyle(color = colors.plain.copy(alpha = 0.8f)),
                0, it.length
            )
        ))), "")
    }
    return try {
        val f = java.io.File(ref.absPath)
        if (!f.isFile) return msg("文件不存在（可能已被删除或回退）")
        val size = f.length()
        val bytes = java.io.FileInputStream(f).use { input ->
            val cap = if (size > VIEWER_MAX_BYTES) VIEWER_MAX_BYTES.toInt() else size.toInt()
            val buf = ByteArray(cap)
            var read = 0
            while (read < cap) {
                val n = input.read(buf, read, cap - read)
                if (n < 0) break
                read += n
            }
            buf.copyOf(read)
        }
        if (bytes.take(4096).indexOf(0) >= 0) {
            return msg("二进制文件（约 ${size / 1024} KB），无法预览")
        }
        val text = String(bytes, Charsets.UTF_8)
        val allLines = if (text.isEmpty()) listOf("") else text.split('\n')
        val kept = if (allLines.size > VIEWER_MAX_LINES) allLines.take(VIEWER_MAX_LINES) else allLines
        val lang = ref.relPath.substringAfterLast('.', "")
        val ann = CodeHighlight.highlight(kept.joinToString("\n"), lang, colors)
        val note = buildString {
            if (allLines.size > VIEWER_MAX_LINES) append("前 $VIEWER_MAX_LINES/${allLines.size} 行")
            if (size > VIEWER_MAX_BYTES) {
                if (isNotEmpty()) append("，")
                append("截前 1MB/${size / 1024}KB")
            }
        }
        Pair(splitAnnotatedPerLine(ann), note)
    } catch (e: Exception) {
        msg("读取失败：${e.message}")
    }
}
