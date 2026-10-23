package com.haoai.agent.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.SaveAs
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.haoai.agent.ui.UiFilePath
import com.haoai.agent.ui.common.CodeHighlight
import com.haoai.agent.ui.common.FullscreenCodeDialog
import com.haoai.agent.ui.common.splitAnnotatedPerLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
internal fun FileViewerDialog(
    ref: UiFilePath,
    onDismiss: () -> Unit,
    /** 换乘钮开关：会话宿主=true（可跳目录/版本）；纯目录设置宿主=false（本就在树内、无会话快照）。 */
    showTransferActions: Boolean = true,
) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val locateInTree = LocalLocateInTree.current
    val openTimeline = LocalOpenTimeline.current
    // 批2d 分流：图片 → 解码预览（Fit）；文本 → 原路径
    val extLower = ref.relPath.substringAfterLast('.', "").lowercase()
    val isImage = extLower in setOf("png", "jpg", "jpeg", "webp", "gif")
    var imageBitmap by remember(ref.absPath) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var imageMeta by remember(ref.absPath) { mutableStateOf("") }
    var result by remember(ref.absPath) {
        mutableStateOf<Pair<List<AnnotatedString>, String>?>(null)
    }
    val screenW = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp
    LaunchedEffect(ref.absPath, dark) {
        if (isImage) {
            val r = withContext(Dispatchers.IO) { loadViewerImage(ref.absPath, screenW) }
            imageBitmap = r?.first
            imageMeta = r?.second ?: "图片"
        } else {
            result = withContext(Dispatchers.IO) { loadViewerLines(ref, dark) }
        }
    }
    val lines = result?.first ?: listOf(AnnotatedString("加载中…"))
    val note = result?.second.orEmpty()
    // 批1 重构（2026-10-22）：壳层双行信息——主行文件名，副行「路径 · 行数 · 大小/截断说明」。
    // chip 取扩展名大写（KT/JSON/MD…，壳内映射装饰色）；状态行给底栏「行 X/Y · 语言」。
    val fileName = ref.relPath.substringAfterLast('/')
    val dirPath = ref.relPath.substringBeforeLast('/', "").ifEmpty { "workspace" }
    val meta = buildString {
        append(dirPath)
        append(" · ")
        append("${lines.size} 行")
        if (note.isNotEmpty()) append(" · $note")
    }
    val ext = ref.relPath.substringAfterLast('.', "").uppercase().take(4)
    val chip = when {
        ext.isEmpty() -> ""
        ext.startsWith("KT") -> "KT"
        ext.length <= 4 -> ext
        else -> ext.take(3)
    }
    // ── 批4a：外发动作行接线（分流表见 FileShareLogic；意图构造在此）──
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val mime = mimeFromViewerExt(extLower)
    var htmlPreview by remember(ref.absPath) { mutableStateOf(false) }
    val fileUrl = remember(ref.absPath) {
        android.net.Uri.fromFile(java.io.File(ref.absPath)).toString()
    }
    val openHtmlFile = LocalOpenHtmlFile.current
    // FileProvider 授权是逐条 URI 级的；文件不在 file_paths 覆盖目录（如未来的
    // 公共位置未加规则）时取不到 uri——此时外发两键降级为提示，「另存为」仍可用
    // （CreateDocument 走系统面板，与 FileProvider 无关）。
    val providerUri: android.net.Uri? = runCatching {
        androidx.core.content.FileProvider.getUriForFile(
            context, context.packageName + ".fileprovider", java.io.File(ref.absPath)
        )
    }.getOrNull()
    fun shareToast(s: String) =
        android.widget.Toast.makeText(context, s, android.widget.Toast.LENGTH_SHORT).show()
    val saveAsLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument(mime)
    ) { dest ->
        if (dest != null) scope.launch(Dispatchers.IO) {
            val ok = runCatching {
                java.io.File(ref.absPath).inputStream().use { input ->
                    context.contentResolver.openOutputStream(dest)?.use { out ->
                        input.copyTo(out)
                    } ?: 0L
                } > 0L
            }.getOrDefault(false)
            shareToast(if (ok) "已另存 $fileName" else "另存失败：目标不可写")
        }
    }
    fun onViewerAction(a: ViewerAction) = when (a) {
        ViewerAction.RenderView -> { htmlPreview = true }
        ViewerAction.Share -> {
            val uri = providerUri
            if (uri == null) shareToast("该位置暂不支持外发，请用另存为")
            else runCatching {
                val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = mime
                    putExtra(android.content.Intent.EXTRA_STREAM, uri)
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(
                    android.content.Intent.createChooser(send, "分享文件")
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }.onFailure { shareToast("没有可接收的应用") }
        }
        ViewerAction.SaveAs -> runCatching { saveAsLauncher.launch(fileName) }
            .onFailure { shareToast("另存启动失败") }
        ViewerAction.OpenWith -> {
            val uri = providerUri
            if (uri == null) shareToast("该位置暂不支持外发，请用另存为")
            else runCatching {
                val view = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, mime)
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(
                    android.content.Intent.createChooser(view, "打开方式")
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }.onFailure { shareToast("没有能打开该类型的应用") }
        }
        ViewerAction.SaveToAlbum -> scope.launch(Dispatchers.IO) {
            val ok = runCatching {
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mime)
                    if (android.os.Build.VERSION.SDK_INT >= 29) {
                        put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/HaoAI")
                    }
                }
                val u = context.contentResolver.insert(
                    android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
                )
                if (u == null) false
                else context.contentResolver.openOutputStream(u)?.use { out ->
                    java.io.File(ref.absPath).inputStream().use { it.copyTo(out) }
                    true
                } ?: false
            }.getOrDefault(false)
            shareToast(if (ok) "已存到相册 Pictures/HaoAI" else "存相册失败")
        }
    }
    FullscreenCodeDialog(
        title = fileName,
        imageBitmap = imageBitmap,
        imageMeta = imageMeta,
        fileMeta = meta,
        fileChip = chip,
        // 图片模式状态行走 imageMeta（壳层分支）；文本模式走语言标签
        statusLang = if (isImage) "" else chip.ifEmpty { "text" },
        lines = lines,
        dark = dark,
        onDismiss = onDismiss,
        // 批3a 闭环（效果图 .ovft 定稿按钮）+ 批3d 版本直达：查看器头部两枚换乘钮
        trailing = {
            if (showTransferActions) androidx.compose.foundation.layout.Row(
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp)
            ) {
                // 批1 主题化：版本历史=信息（灰胶囊）、在目录中定位=动作（绿胶囊，语义色）
                val primary = MaterialTheme.colorScheme.primary
                val onVar = MaterialTheme.colorScheme.onSurfaceVariant
                Text(
                    "版本历史",
                    style = androidx.compose.ui.text.TextStyle(
                        fontSize = 11.5.sp, lineHeight = 14.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                        platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false)
                    ),
                    color = onVar,
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(onVar.copy(alpha = 0.08f))
                        .clickable {
                            openTimeline(normalizeRel(ref.relPath))
                            onDismiss()
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                )
                Text(
                    "在目录中定位",
                    style = androidx.compose.ui.text.TextStyle(
                        fontSize = 11.5.sp, lineHeight = 14.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                        platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false)
                    ),
                    color = primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(primary.copy(alpha = 0.12f))
                        .clickable {
                            locateInTree(normalizeRel(ref.relPath))
                            onDismiss()
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        },
        // 批4a：底部外发动作行（渲染看仅 HTML / 存相册仅图片 / 其余三键恒在）
        bottomActions = {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                viewerActions(extLower).forEach { a ->
                    FileShareChip(a) { onViewerAction(a) }
                }
            }
        }
    )
    if (htmlPreview) {
        com.haoai.agent.ui.common.HtmlPreviewDialog(
            title = fileName,
            source = com.haoai.agent.ui.common.HtmlPreviewSource.FilePage(fileUrl),
            onDismiss = { htmlPreview = false },
            // 换乘钮只在会话宿主给（showTransferActions 同款口径）：设置宿主没装
            // 内置浏览器（LocalOpenHtmlFile 默认空实现），宁可不显示也不放死钮。
            onOpenInBrowser = if (showTransferActions) ({ openHtmlFile(fileUrl) }) else null,
        )
    }
}

/** 外发键胶囊：等宽四键一行铺满；渲染看=绿主键（语义色），其余灰字。文案 11.5sp 无底衬。 */
@Composable
private fun RowScope.FileShareChip(action: ViewerAction, onClick: () -> Unit) {
    val prim = action == ViewerAction.RenderView
    val primaryC = MaterialTheme.colorScheme.primary
    val onVar = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(12.dp))
            .background(if (prim) primaryC.copy(alpha = 0.12f) else onVar.copy(alpha = 0.07f))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = when (action) {
                ViewerAction.RenderView -> Icons.Filled.Visibility
                ViewerAction.Share -> Icons.Filled.Share
                ViewerAction.SaveAs -> Icons.Filled.SaveAs
                ViewerAction.OpenWith -> Icons.Filled.OpenInNew
                ViewerAction.SaveToAlbum -> Icons.Filled.PhotoLibrary
            },
            contentDescription = action.label(),
            tint = if (prim) primaryC else onVar,
            modifier = Modifier.size(14.dp)
        )
        androidx.compose.foundation.layout.Spacer(Modifier.size(5.dp))
        Text(
            action.label(),
            style = TextStyle(
                fontSize = 11.5.sp, lineHeight = 13.sp,
                fontWeight = FontWeight.SemiBold,
                platformStyle = PlatformTextStyle(includeFontPadding = false)
            ),
            color = if (prim) primaryC else onVar,
            maxLines = 1
        )
    }
}

/** 图片解码（降采样到屏宽内）→ (bitmap, "格式 · W×H")。失败返回 null（UI 出提示）。 */
private fun loadViewerImage(path: String, screenW: Int): Pair<androidx.compose.ui.graphics.ImageBitmap, String>? {
    return runCatching {
        val f = java.io.File(path)
        if (!f.isFile) return null
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= screenW) sample *= 2
        val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = android.graphics.BitmapFactory.decodeFile(path, opts) ?: return null
        val fmt = path.substringAfterLast('.', "").uppercase()
        Pair(bmp.asImageBitmap(), "$fmt · ${bounds.outWidth}×${bounds.outHeight}")
    }.getOrNull()
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
