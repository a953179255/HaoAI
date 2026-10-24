package com.haoai.agent.ui.chat

import androidx.compose.foundation.background
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.material.icons.filled.Folder
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import com.haoai.agent.ui.common.FileTypeBadge
import com.haoai.agent.ui.common.FullscreenCodeDialog
import com.haoai.agent.ui.common.fileVisualOf
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
    /** 「在目录中定位」：仅链卡宿主给（非空才画钮，点了开浏览页并落到父目录）。
     *  批4b：「版本历史」钮随历史 Tab 退役（回滚走聊天 /undo 命令，快照引擎保留）。 */
    onLocate: ((String) -> Unit)? = null,
) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    // 批2d 分流：图片 → 解码预览（Fit）；文本 → 原路径；ZIP → 只读清单（2026-10-24 改版）
    val extLower = ref.relPath.substringAfterLast('.', "").lowercase()
    val isImage = extLower in setOf("png", "jpg", "jpeg", "webp", "gif")
    val isZip = extLower == "zip"
    var imageBitmap by remember(ref.absPath) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var imageMeta by remember(ref.absPath) { mutableStateOf("") }
    var result by remember(ref.absPath) {
        mutableStateOf<Pair<List<AnnotatedString>, String>?>(null)
    }
    /** ZIP 条目清单（name/原始大小/压缩后大小；目录条目名带尾 '/'）。null=非 zip 或读取中。 */
    var zipRows by remember(ref.absPath) { mutableStateOf<List<Triple<String, Long, Long>>?>(null) }
    var zipNote by remember(ref.absPath) { mutableStateOf("") }
    val screenW = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp
    LaunchedEffect(ref.absPath, dark) {
        if (isImage) {
            val r = withContext(Dispatchers.IO) { loadViewerImage(ref.absPath, screenW) }
            imageBitmap = r?.first
            imageMeta = r?.second ?: "图片"
            if (r == null) {
                // 解码失败要给说法（2026-10-24）：此前永远停「加载中…」误导排障
                result = Pair(
                    listOf(AnnotatedString("图片无法解码（文件损坏或格式不支持）")),
                    ""
                )
            }
        } else if (isZip) {
            val r = withContext(Dispatchers.IO) { loadZipEntries(ref.absPath) }
            zipRows = r?.first
            zipNote = r?.second ?: ""
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
        if (isZip) append("${zipRows?.size ?: 0} 项")
        else append("${lines.size} 行")
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
    // FileProvider 授权是逐条 URI 级的；文件不在 file_paths 覆盖目录（公共工作区、
    // 脑内档案等——工作区搬去公共目录后覆盖清单只剩 cache/shared 可用）时，先暂存
    // 到 cache/shared（覆盖清单内）再取 URI：查看器能读的文件就一定能外发，
    // 不再有「该位置暂不支持外发」死路（2026-10-24 用户实测撞上）。
    suspend fun externalUri(): android.net.Uri? = withContext(Dispatchers.IO) {
        val f = java.io.File(ref.absPath)
        runCatching {
            androidx.core.content.FileProvider.getUriForFile(
                context, context.packageName + ".fileprovider", f
            )
        }.getOrElse {
            val staged = java.io.File(java.io.File(context.cacheDir, "shared").apply { mkdirs() }, fileName)
            runCatching {
                f.inputStream().use { input -> staged.outputStream().use { input.copyTo(it) } }
                androidx.core.content.FileProvider.getUriForFile(
                    context, context.packageName + ".fileprovider", staged
                )
            }.getOrNull()
        }
    }
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
        ViewerAction.Share -> scope.launch {
            val uri = externalUri()
            if (uri == null) shareToast("外发失败：文件无法读取")
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
        ViewerAction.OpenWith -> scope.launch {
            val uri = externalUri()
            if (uri == null) shareToast("外发失败：文件无法读取")
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
    // ZIP 状态文案：条目数 + 展示说明（截断说明在 zipNote）
    val zipStatus = zipRows?.let { rows ->
        "${rows.size} 项 · 只读清单" + if (zipNote.isNotEmpty()) " · $zipNote" else ""
    }
    FullscreenCodeDialog(
        title = fileName,
        imageBitmap = imageBitmap,
        imageMeta = imageMeta,
        fileMeta = meta,
        fileChip = chip,
        // 类型徽标（2026-10-24）：彩底彩图标与文件列表同源；7z/rar 仍按二进制拒渲不进这里
        chipIcon = if (chip.isEmpty()) null else fileVisualOf(fileName).icon,
        statusLang = when {
            isImage -> ""
            isZip -> zipStatus ?: "读取中…"
            else -> chip.ifEmpty { "text" }
        },
        // 状态行上顶栏（2026-10-25）：可视行区间并入副标题实时更新，截断说明跟随
        statusPath = dirPath,
        statusNote = note,
        lines = if (isZip) emptyList() else lines,
        dark = dark,
        onDismiss = onDismiss,
        // 换乘钮退役到只剩「在目录中定位」，且仅在宿主传入 onLocate 时出现
        // （链卡宿主给→可跳浏览页；浏览页内查看器不给→就地无需跳）。「版本历史」钮
        // 随历史 Tab 一并退役，回滚能力仍在引擎（聊天 /undo）。
        trailing = if (onLocate != null) {
            {
                val primary = MaterialTheme.colorScheme.primary
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
                            onLocate(normalizeRel(ref.relPath))
                            onDismiss()
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        } else null,
        // ── 2026-10-24 改版：外发动作进悬浮控制簇 ──
        // 图片/ZIP：动作胶囊直接进簇（此时字号/换行已隐藏，有空间）；
        // 文本/HTML：簇里放不下字号控件+全量外发键 → 收进 ⋯ 溢出菜单（HTML 的
        // 「渲染看」是主操作，破例直接进簇置绿）。
        clusterActions = when {
            isImage -> {
                {
                    viewerActions(extLower).forEach { a ->
                        ClusterActionChip(
                            label = a.label(),
                            primary = a == ViewerAction.SaveToAlbum
                        ) { onViewerAction(a) }
                    }
                }
            }
            isZip -> {
                {
                    listOf(ViewerAction.Share, ViewerAction.SaveAs).forEach { a ->
                        ClusterActionChip(label = a.label(), primary = false) { onViewerAction(a) }
                    }
                }
            }
            extLower == "html" || extLower == "htm" -> {
                {
                    ClusterActionChip(
                        label = ViewerAction.RenderView.label(), primary = true
                    ) { onViewerAction(ViewerAction.RenderView) }
                }
            }
            else -> null
        },
        overflowMenu = if (isImage || isZip) null else {
            {
                // HTML 时渲染看已进簇，菜单里只留其余三键
                viewerActions(extLower)
                    .filter { it != ViewerAction.RenderView }
                    .forEach { a ->
                        OverflowActionRow(label = a.label()) { onViewerAction(a) }
                    }
            }
        },
        customContent = if (isZip) {
            { ZipListColumn(rows = zipRows ?: emptyList()) }
        } else null
    )
    if (htmlPreview) {
        com.haoai.agent.ui.common.HtmlPreviewDialog(
            title = fileName,
            source = com.haoai.agent.ui.common.HtmlPreviewSource.FilePage(fileUrl),
            onDismiss = { htmlPreview = false },
            // 换乘钮只在链卡宿主给（onLocate 非空=链卡宿主，那里才装了内置浏览器）：
            // 设置/浏览宿主页没装（LocalOpenHtmlFile 默认空实现），宁可不显示也不放死钮。
            onOpenInBrowser = if (onLocate != null) ({ openHtmlFile(fileUrl) }) else null,
        )
    }
}

/** 悬浮控制簇里的动作胶囊（2026-10-24 改版）：小号不等宽，主键绿底。 */
@Composable
private fun ClusterActionChip(label: String, primary: Boolean, onClick: () -> Unit) {
    val primaryC = MaterialTheme.colorScheme.primary
    val onVar = MaterialTheme.colorScheme.onSurfaceVariant
    Text(
        label,
        style = TextStyle(
            fontSize = 11.sp, lineHeight = 13.sp,
            fontWeight = FontWeight.SemiBold,
            platformStyle = PlatformTextStyle(includeFontPadding = false)
        ),
        color = if (primary) primaryC else onVar,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(
                if (primary) primaryC.copy(alpha = 0.16f)
                else onVar.copy(alpha = 0.08f)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    )
}

/** ⋯ 溢出菜单行（2026-10-24 改版）：玻璃小卡内的一行式动作。 */
@Composable
private fun OverflowActionRow(label: String, onClick: () -> Unit) {
    Text(
        label,
        style = TextStyle(
            fontSize = 12.5.sp, lineHeight = 16.sp,
            fontWeight = FontWeight.SemiBold,
            platformStyle = PlatformTextStyle(includeFontPadding = false)
        ),
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 11.dp)
    )
}

/** ZIP 只读清单（2026-10-24 改版提案落地）：目录前置 + 条目名/原始大小/压缩率。 */
@Composable
private fun ZipListColumn(rows: List<Triple<String, Long, Long>>) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(androidx.compose.foundation.rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        if (rows.isEmpty()) {
            Text(
                "读取中…", fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp)
            )
        }
        rows.forEachIndexed { i, (name, size, compressed) ->
            if (i > 0) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 0.dp)
                        .height(0.5.dp)
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f))
                )
            }
            val isDir = name.endsWith("/")
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isDir) {
                    Icon(
                        Icons.Filled.Folder, contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(15.dp)
                    )
                } else {
                    FileTypeBadge(name, 22.dp)
                }
                Spacer(Modifier.size(9.dp))
                Text(
                    name.trimEnd('/'),
                    fontSize = 11.5.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = if (isDir) FontWeight.SemiBold else FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (isDir) 0.95f else 0.8f),
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (!isDir) {
                    val pct = if (size > 0) (compressed * 100 / size).toInt() else 100
                    Text(
                        "${formatZipSize(size)} · 压缩 $pct%",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                    )
                }
            }
        }
    }
}

private fun formatZipSize(bytes: Long): String = when {
    bytes >= 1L shl 20 -> "%.1f MB".format(bytes / 1048576.0)
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}

/**
 * ZIP 条目读取（2026-10-24）：java.util.zip 原生 API 零新依赖；目录条目前置排序，
 * 截断到 800 条（查看器是"看一眼"不是解压工具）。失败（损坏/加密）返回 null 由
 * 调用方展示「读取中…」占位与空态。
 */
private fun loadZipEntries(path: String): Pair<List<Triple<String, Long, Long>>, String>? =
    runCatching {
        java.util.zip.ZipFile(java.io.File(path)).use { zf ->
            val all = zf.entries().asSequence().toList()
            val kept = all
                .sortedWith(compareBy({ !it.name.endsWith("/") }, { it.name }))
                .take(800)
                .map { Triple(it.name, it.size, it.compressedSize) }
            val note = if (all.size > 800) "前 800/${all.size} 项" else ""
            Pair(kept, note)
        }
    }.getOrNull()

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
