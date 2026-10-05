package com.haoai.agent.ui.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 网站图标（favicon）组件 —— 链卡搜索/抓取步骤行用，对齐 rikkahub 的 Favicon/FaviconRow。
 *
 * 图标源：Google s2 favicon 服务（sz=64，走系统代理，Android OkHttp 默认跟随）。
 * 失败/超时 → 域名首字母色块兜底（色相按域名 hash，稳定不闪）。
 * **固定槽位**：兜底色块先占满 size，加载成功后叠在上头替换——不会"先空后跳版"。
 *
 * 刻意不引 Coil：项目无该依赖，favicon 是小尺寸静态图，OkHttp + BitmapFactory +
 * LruCache 足够（与 Markdown 图片块同一套路，见 Markdown.kt mdImageCache）。
 */

private class FaviconResult(val bmp: ImageBitmap?)
private val faviconCache = android.util.LruCache<String, FaviconResult>(80)
private val faviconClient by lazy {
    okhttp3.OkHttpClient.Builder()
        .connectTimeout(java.time.Duration.ofSeconds(6))
        .readTimeout(java.time.Duration.ofSeconds(8))
        .build()
}

private fun loadFavicon(domain: String): ImageBitmap? = runCatching {
    val url = "https://www.google.com/s2/favicons?domain=$domain&sz=64"
    val bytes = faviconClient.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { r ->
        if (!r.isSuccessful) return@runCatching null
        r.body?.bytes()
    } ?: return@runCatching null
    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
}.getOrNull()

/** URL → 展示域名（去协议、去 www.、截断）。抓取步行文案与 favicon 都取它。 */
fun domainFromUrl(url: String): String =
    url.substringAfter("://", url).substringBefore('/').removePrefix("www.").take(40)

/** 域名 → 稳定色相的兜底色（同域名永远同色，避免闪变）。 */
private fun faviconFallbackColor(domain: String): Color {
    val hue = ((domain.hashCode() % 360 + 360) % 360).toFloat()
    return Color.hsv(hue, 0.52f, 0.80f)
}

private fun faviconLetter(domain: String): String =
    domain.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?"

@Composable
fun Favicon(
    domain: String,
    size: Dp,
    modifier: Modifier = Modifier,
    circle: Boolean = true,
) {
    val shape = if (circle) CircleShape else RoundedCornerShape(size / 4f)
    val cached = faviconCache.get(domain)?.bmp
    var bmp by remember(domain) { mutableStateOf(cached) }
    LaunchedEffect(domain) {
        if (faviconCache.get(domain) != null) return@LaunchedEffect  // 已尝试过（含失败），不重试
        val loaded = withContext(Dispatchers.IO) { loadFavicon(domain) }
        faviconCache.put(domain, FaviconResult(loaded))
        bmp = loaded
    }
    val current = bmp
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(if (current == null) faviconFallbackColor(domain) else Color.Transparent),
        contentAlignment = Alignment.Center
    ) {
        if (current != null) {
            Image(
                bitmap = current,
                contentDescription = null,
                modifier = Modifier.size(size).clip(shape),
                contentScale = ContentScale.Fit
            )
        } else {
            val fs = with(LocalDensity.current) { (size.toPx() * 0.52f).sp }
            Text(faviconLetter(domain), color = Color.White, fontSize = fs, fontWeight = FontWeight.Bold)
        }
    }
}

/**
 * favicon 叠排行（搜索步结果下方）：最多 [max] 个，彼此重叠 [size] 的 1/3。
 * 用 Box + offset 手动排布（Row 的负 spacedBy 不支持），总宽精确 = size + (n-1)·step。
 */
@Composable
fun FaviconRow(
    domains: List<String>,
    size: Dp,
    modifier: Modifier = Modifier,
    max: Int = 4,
) {
    val ds = domains.filter { it.isNotBlank() }.distinct().take(max)
    if (ds.isEmpty()) return
    val step = size - size * 0.34f
    val totalW = size + step * (ds.size - 1)
    Box(modifier = modifier.size(width = totalW, height = size)) {
        ds.forEachIndexed { i, d ->
            Favicon(
                domain = d,
                size = size,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = step * i)
            )
        }
    }
}
