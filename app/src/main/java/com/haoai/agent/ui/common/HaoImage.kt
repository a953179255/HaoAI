package com.haoai.agent.ui.common

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 聊天图片统一排版件（2026-10-14 图片排版改版）：
 * 用户气泡与 Markdown 图片块共用同一套"骨架 → 解码 → 淡入"节奏与网格规格，
 * 避免两处各画各的导致观感漂移。
 *
 * · 单图 [HaoImageSingle]：贴合原比例展示；解码期 4:3 骨架先占住高度，到位后淡入
 *   ——"先转圈后撑高"的尺寸跳动就此消除。
 * · 多图 [HaoImageGrid]：2 列正方形网格，至多 5 格；满 5 格仍有更多时末格盖「+N」。
 * · 点击任何一格 → 全屏图集回调（横滑、缩放由宿主的 Lightbox 承担）。
 */

/** 缩略图位图缓存：气泡与 Markdown 两条管线共享（key = url 或哈希字符串）。 */
internal val haoThumbCache = android.util.LruCache<String, ImageBitmap>(32)

/** 骨架占位底色（浅玻璃上的柔灰，明暗主题都压得住）。 */
private val SkeletonColor = Color(0x14808AA0)

/** data URL（base64）→ 位图，采样解码防 OOM；失败返回 null。 */
internal suspend fun haoDecodeDataUrl(dataUrl: String): ImageBitmap? = withContext(Dispatchers.IO) {
    runCatching {
        val bytes = android.util.Base64.decode(
            dataUrl.substringAfter("base64,", ""), android.util.Base64.NO_WRAP
        )
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1080) sample *= 2
        val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.asImageBitmap()
    }.getOrNull()
}

/**
 * 矢量放大角标已退役（2026-10-14 用户拍板「G 无角标」）：整图即按钮，
 * 可点性改由「首图出现时的一次性提示胶囊」承担（[TapHintOverlay]），不再常驻脏图。
 */

/** 进程级「已学会点图放大」标志：出过一次提示或进过一次查看器即永不再提示。 */
internal object TapHint {
    @Volatile
    var learned = false
}

/**
 * 「点按查看大图」一次性提示胶囊（方案 G 的触屏等价物：悬停 → 首次出现）。
 * 盖在图片格中央，浮现 1.8s 自动淡出；[TapHint.learned] 为进程级单次闸门。
 */
@Composable
private fun BoxScope.TapHintOverlay(show: Boolean) {
    if (!show) return
    Box(
        Modifier
            .matchParentSize()
            .clickable(
                indication = null,
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            ) { TapHint.learned = true },
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.animation.AnimatedVisibility(
            visible = true,
            enter = androidx.compose.animation.fadeIn(tween(220)),
            exit = androidx.compose.animation.fadeOut(tween(300)),
        ) {
            Text(
                "点按查看大图",
                fontSize = 11.sp,
                color = Color.White,
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(Color(0x8C141A28))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }
    }
}

/** 首图提示状态：进程内第一张可点图淡入时置 true，1.8s 后收回并永久学习。 */
@Composable
internal fun rememberFirstTapHint(hasImage: Boolean): Boolean {
    if (TapHint.learned) return false
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(hasImage) {
        if (hasImage && !TapHint.learned) {
            shown = true
            kotlinx.coroutines.delay(1800)
            shown = false
            TapHint.learned = true
        }
    }
    return shown
}


/** 骨架扫光：柔光带从左到右扫过，只动渐变位移不改尺寸 → 不引起布局跳。 */
@Composable
private fun Shimmer(modifier: Modifier = Modifier) {
    val tr = rememberInfiniteTransition(label = "shimmer")
    val t by tr.animateFloat(0f, 1f,
        infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "shimmerT")
    Box(
        modifier.background(
            Brush.linearGradient(
                colors = listOf(Color(0x00FFFFFF), Color(0x1FFFFFFF), Color(0x00FFFFFF)),
                start = Offset((t * 1.6f - 0.3f) * 1000f, 0f),
                end = Offset((t * 1.6f - 0.3f) * 1000f + 500f, 300f)
            )
        )
    )
}

/**
 * 网格/详情用单格：固定比例（网格传 1f）、Crop、骨架→淡入。
 * key 决定 remember 身份；解码管线由调用方注入（http/data/文件 各家不同）。
 * 角标已退役（用户拍板「G 无角标」）；首格可挂一次性「点按查看大图」提示。
 */
@Composable
fun HaoImageTile(
    key: String,
    decode: suspend () -> ImageBitmap?,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    ratio: Float = 1f,
    contentScale: ContentScale = ContentScale.Crop,
    corner: Shape = RoundedCornerShape(10.dp),
    showTapHint: Boolean = false,
) {
    var bmp by remember(key) { mutableStateOf(haoThumbCache.get(key)) }
    var failed by remember(key) { mutableStateOf(false) }
    LaunchedEffect(key) {
        if (bmp != null || failed) return@LaunchedEffect
        val d = runCatching { decode() }.getOrNull()
        if (d != null) { haoThumbCache.put(key, d); bmp = d } else failed = true
    }
    val alpha by animateFloatAsState(
        targetValue = if (bmp != null) 1f else 0f,
        animationSpec = tween(260, easing = LinearEasing),
        label = "haoImgFade"
    )
    Box(
        modifier
            .aspectRatio(ratio)
            .clip(corner)
            .background(SkeletonColor),
        contentAlignment = Alignment.Center
    ) {
        val b = bmp
        if (b != null) {
            Image(
                b,
                contentDescription = if (onClick != null) "图片（点开放大）" else "图片",
                modifier = Modifier.fillMaxSize().alpha(alpha).then(
                    if (onClick != null) Modifier.clickable { onClick() } else Modifier
                ),
                contentScale = contentScale
            )
            if (showTapHint && onClick != null) {
                TapHintOverlay(show = rememberFirstTapHint(true))
            }
        } else if (failed) {
            androidx.compose.material3.Icon(
                HaoIcons.Alert, contentDescription = "图片加载失败",
                tint = Color(0x99808AA0), modifier = Modifier.size(17.dp)
            )
        } else {
            Shimmer(modifier = Modifier.fillMaxSize())
        }
    }
}

/**
 * 单图原比例展示：解码后按位图固有比例给尺寸（宽 ≤ maxWidth、高 ≤ maxHeight，
 * 双向钳制保比例）；解码期 4:3 骨架占位，图到位淡入承接——不再有"转圈→撑高"的跳动。
 */
@Composable
fun HaoImageSingle(
    key: String,
    decode: suspend () -> ImageBitmap?,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    maxWidth: Dp = 260.dp,
    maxHeight: Dp = 340.dp,
    corner: Shape = RoundedCornerShape(14.dp),
    contentDescription: String? = null,
) {
    var bmp by remember(key) { mutableStateOf(haoThumbCache.get(key)) }
    var failed by remember(key) { mutableStateOf(false) }
    LaunchedEffect(key) {
        if (bmp != null || failed) return@LaunchedEffect
        val d = runCatching { decode() }.getOrNull()
        if (d != null) { haoThumbCache.put(key, d); bmp = d } else failed = true
    }
    val alpha by animateFloatAsState(
        targetValue = if (bmp != null) 1f else 0f,
        animationSpec = tween(260, easing = LinearEasing),
        label = "haoImgSingleFade"
    )
    val b = bmp
    // 固有比例：宽不超上限、高不超上限，哪个先顶到就等比缩；未解码用 4:3 骨架
    val cellW: Dp
    val cellH: Dp
    if (b != null) {
        val r = b.width.toFloat() / b.height.toFloat().coerceAtLeast(1f)
        var w = maxWidth
        var h = w / r
        if (h > maxHeight) { h = maxHeight; w = h * r }
        cellW = w
        cellH = h
    } else {
        cellW = maxWidth
        cellH = maxWidth * 3 / 4
    }
    Box(
        modifier
            .size(cellW, cellH)
            .clip(corner)
            .background(SkeletonColor)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier),
        contentAlignment = Alignment.Center
    ) {
        if (b != null) {
            Image(
                b,
                contentDescription = contentDescription
                    ?: if (onClick != null) "图片（点开放大）" else "图片",
                modifier = Modifier.fillMaxSize().alpha(alpha),
                contentScale = ContentScale.Crop
            )
            if (onClick != null) {
                TapHintOverlay(show = rememberFirstTapHint(true))
            }
        } else if (failed) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                androidx.compose.material3.Icon(
                    HaoIcons.Alert, contentDescription = null,
                    tint = Color(0x99808AA0), modifier = Modifier.size(14.dp)
                )
                Text("图片加载失败", fontSize = 12.sp, color = Color(0x99808AA0))
            }
        } else {
            Shimmer(modifier = Modifier.fillMaxSize())
        }
    }
}

/** 多图 2 列正方形网格：≤[maxCells] 格，满格仍有更多时末格盖「+N」。空列表不渲染。 */
@Composable
fun HaoImageGrid(
    urls: List<String>,
    decodeOf: (String) -> suspend () -> ImageBitmap?,
    onOpen: (List<String>, String) -> Unit,
    modifier: Modifier = Modifier,
    maxCells: Int = 5,
    corner: Shape = RoundedCornerShape(12.dp),
) {
    if (urls.isEmpty()) return
    val visible = urls.take(maxCells)
    val extra = urls.size - visible.size
    val lastMore = extra > 0 && visible.size == maxCells
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        visible.chunked(2).forEachIndexed { rowIdx, rowUrls ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                rowUrls.forEachIndexed { colIdx, url ->
                    val cellIdx = rowIdx * 2 + colIdx
                    Box(Modifier.weight(1f)) {
                        HaoImageTile(
                            key = url,
                            decode = decodeOf(url),
                            onClick = { onOpen(urls, url) },
                            modifier = Modifier.fillMaxWidth(),
                            corner = corner,
                            showTapHint = cellIdx == 0
                        )
                        if (lastMore && cellIdx == visible.lastIndex) {
                            // 「+N」渐变蒙层（用户拍板「渐变」版）：上浅下深 + 文字投影，
                            // 比平涂黑更有层次、不糊图
                            Box(
                                Modifier.fillMaxSize().clip(corner)
                                    .background(
                                        Brush.verticalGradient(
                                            listOf(Color(0x1A141A28), Color(0x8C141A28))
                                        )
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "+$extra",
                                    color = Color.White,
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
                // 奇数行补空权重，保证两格等宽
                if (rowUrls.size == 1) Box(Modifier.weight(1f).aspectRatio(1f))
            }
        }
    }
}
