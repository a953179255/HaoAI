package com.haoai.agent.ui.common

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.Image
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 图片全屏查看器（批 1d）：聊天里任何图片（markdown 图片块 / 用户附件气泡）点击后
 * 进这里。双指缩放（1×~5×，放大后可拖动，超出边界钳制）、多图左右滑、存相册、分享。
 *
 * 形态对齐 FullscreenHtmlDialog：Dialog 全屏无系统默认宽 + 顶栏标题/关闭 + 底栏动作；
 * 黑底沉浸（图片查看器惯例，与深浅主题无关）。
 */
@Composable
fun ImageLightbox(
    images: List<Pair<String, String>>,   // (url, alt)
    currentUrl: String,
    onDismiss: () -> Unit,
) {
    // m20（2026-10-10）：空列表的退出改为在 LaunchedEffect 里调 onDismiss ——
    // 原来直接在组合体里调用（改父级状态），违反「组合无副作用」契约。
    // key 取 isEmpty：列表从非空变空（外部清掉图源）时恰好触发一次。
    androidx.compose.runtime.LaunchedEffect(images.isEmpty()) {
        if (images.isEmpty()) onDismiss()
    }
    if (images.isEmpty()) return
    val startIndex = images.indexOfFirst { it.first == currentUrl }
        .let { if (it < 0) 0 else it }
    val pagerState = rememberPagerState(initialPage = startIndex) { images.size }
    // 各页缩放态上提：当前页放大时禁横滑（否则翻页手势和拖动打架）
    val scales = remember { mutableStateMapOf<Int, Float>() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val current = images.getOrNull(pagerState.currentPage)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(Modifier.fillMaxSize().background(Color(0xF0000000))) {
            HorizontalPager(
                state = pagerState,
                userScrollEnabled = (scales[pagerState.currentPage] ?: 1f) <= 1.01f,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                LightboxImagePage(
                    url = images[page].first,
                    onScale = { s -> scales[page] = s },
                    onSingleTap = onDismiss
                )
            }

            // 顶栏：计数 + alt + 关闭
            Row(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (images.size > 1) "${pagerState.currentPage + 1} / ${images.size}  " else "",
                    color = Color.White, fontSize = 13.sp
                )
                Text(
                    text = current?.second?.take(24) ?: "",
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 12.sp,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    Icons.Filled.Close, contentDescription = "关闭", tint = Color.White,
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .clickable { onDismiss() }
                        .padding(2.dp)
                )
            }

            // 底栏：存相册 / 分享
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.End
            ) {
                Spacer(Modifier.weight(1f))
                current?.let { (url, _) ->
                    var busy by remember(url) { mutableStateOf(false) }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            "存相册", color = Color.White.copy(alpha = 0.9f), fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(enabled = !busy) {
                                    busy = true
                                    scope.launch {
                                        val msg = runCatching {
                                            val bytes = loadImageBytes(url)
                                                ?: error("图片未就绪")
                                            saveImageToGallery(context, bytes, url)
                                            "已保存到相册 Pictures/HaoAI"
                                        }.getOrElse { "保存失败：${it.message}" }
                                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                        busy = false
                                    }
                                }
                        )
                        Text(
                            "分享", color = Color.White.copy(alpha = 0.9f), fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(enabled = !busy) {
                                    runCatching { shareImage(context, url) }
                                        .onFailure { Toast.makeText(context, "分享失败：${it.message}", Toast.LENGTH_SHORT).show() }
                                }
                        )
                    }
                }
            }
        }
    }
}

/** 单页：解码 + 双指缩放拖动 + 单击收起（放大时单击先复位）。 */
@Composable
private fun LightboxImagePage(
    url: String,
    onScale: (Float) -> Unit,
    onSingleTap: () -> Unit,
) {
    val key = remember(url) { url.hashCode().toString() }
    var bmp by remember(url) { mutableStateOf(mdImageCache.get(key)) }
    var failed by remember(url) { mutableStateOf(false) }
    LaunchedEffect(url) {
        if (bmp != null || failed) return@LaunchedEffect
        val loaded = withContext(Dispatchers.IO) { decodeMarkdownImage(url) }
        if (loaded != null) { mdImageCache.put(key, loaded); bmp = loaded } else failed = true
    }

    var scale by remember(url) { mutableFloatStateOf(1f) }
    var offset by remember(url) { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    LaunchedEffect(scale) { onScale(scale) }

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(url) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    if (bmp == null) return@detectTransformGestures
                    val newScale = (scale * zoom).coerceIn(1f, 5f)
                    // 钳制位移：放大后最多拖出超出视口的半差
                    val maxX = ((newScale - 1f) * size.width / 2f).coerceAtLeast(0f)
                    val maxY = ((newScale - 1f) * size.height / 2f).coerceAtLeast(0f)
                    offset = androidx.compose.ui.geometry.Offset(
                        (offset.x + pan.x).coerceIn(-maxX, maxX),
                        (offset.y + pan.y).coerceIn(-maxY, maxY)
                    )
                    scale = newScale
                }
            }
            // 单击：放大态先复位，正常态收起查看器（用轻量 tap 检测不抢缩放手势）
            .pointerInput(url) {
                detectTapGestures {
                    if (scale > 1.01f) { scale = 1f; offset = androidx.compose.ui.geometry.Offset.Zero }
                    else onSingleTap()
                }
            },
        contentAlignment = Alignment.Center
    ) {
        val b = bmp
        if (b != null) {
            Image(
                bitmap = b, contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp)
                    .graphicsLayer {
                        scaleX = scale; scaleY = scale
                        translationX = offset.x; translationY = offset.y
                        transformOrigin = TransformOrigin(0.5f, 0.5f)
                    }
            )
        } else if (!failed) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp), strokeWidth = 2.dp, color = Color.White
                )
            }
        } else {
            Text("图片加载失败", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
        }
    }
}

// ── 图片字节获取与出存（url 三形态：http(s)/绝对路径/dataUrl，与 decodeMarkdownImage 对齐）──

private suspend fun loadImageBytes(url: String): ByteArray? = withContext(Dispatchers.IO) {
    runCatching {
        when {
            url.startsWith("data:") ->
                android.util.Base64.decode(url.substringAfter("base64,", ""), android.util.Base64.DEFAULT)
            url.startsWith("http://") || url.startsWith("https://") ->
                mdImageClient.newCall(okhttp3.Request.Builder().url(url).build())
                    .execute().use { if (it.isSuccessful) it.body?.bytes() else null }
            url.startsWith("/") -> java.io.File(url).takeIf { it.canRead() }?.readBytes()
            else -> null
        }
    }.getOrNull()
}

/** 写系统相册 Pictures/HaoAI（API 29+ MediaStore 免权限；对齐 saveShotToGallery 的口径）。 */
private fun saveImageToGallery(context: android.content.Context, bytes: ByteArray, url: String) {
    val ext = url.substringBefore('?').substringAfterLast('.', "png").lowercase()
        .let { if (it in setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")) it else "png" }
    val mime = when (ext) {
        "jpg", "jpeg" -> "image/jpeg"; "gif" -> "image/gif"
        "webp" -> "image/webp"; "bmp" -> "image/bmp"
        else -> "image/png"
    }
    val values = android.content.ContentValues().apply {
        put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, "haoai_img_${System.currentTimeMillis()}.$ext")
        put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mime)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            put(
                android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                android.os.Environment.DIRECTORY_PICTURES + "/HaoAI"
            )
        }
    }
    val uri = context.contentResolver.insert(
        android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
    ) ?: error("无法在相册创建条目")
    context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("无法写入相册")
}

/**
 * 分享：网络图片直接分享链接（text/plain，收方在线看原图最保真）；
 * 本地/dataUrl 写 cache/shared 经 FileProvider 外发（按扩展名定 mime）。
 */
private fun shareImage(context: android.content.Context, url: String) {
    val intent = if (url.startsWith("http://") || url.startsWith("https://")) {
        android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_TEXT, url)
        }
    } else {
        val bytes = readImageBytesBlocking(url) ?: error("图片未就绪")
        val ext = url.substringBefore('?').substringAfterLast('.', "png").lowercase()
            .let { if (it in setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")) it else "png" }
        val mime = when (ext) {
            "jpg", "jpeg" -> "image/jpeg"; "gif" -> "image/gif"
            "webp" -> "image/webp"; "bmp" -> "image/bmp"
            else -> "image/png"
        }
        val dir = java.io.File(context.cacheDir, "shared").apply { mkdirs() }
        val file = java.io.File(dir, "haoai_img_${url.hashCode()}.$ext")
        file.writeBytes(bytes)
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context, context.packageName + ".fileprovider", file
        )
        android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = mime
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
    context.startActivity(
        android.content.Intent.createChooser(intent, "分享图片")
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}

/** 三形态同步取字节（分享在主线程调用，dataUrl/file 解码都很轻）。 */
private fun readImageBytesBlocking(url: String): ByteArray? = runCatching {
    when {
        url.startsWith("data:") ->
            android.util.Base64.decode(url.substringAfter("base64,", ""), android.util.Base64.DEFAULT)
        url.startsWith("/") -> java.io.File(url).takeIf { it.canRead() }?.readBytes()
        else -> null
    }
}.getOrNull()
