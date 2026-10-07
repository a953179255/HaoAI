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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 网站图标（favicon）组件 —— 链卡搜索/抓取步骤行用，对齐 rikkahub 的 Favicon/FaviconRow。
 *
 * 图标源：站点自身 /favicon.ico → Google s2 → favicone 多源降级（OkHttp 走系统代理，
 * Android 默认跟随）。全失败/超时 → 域名首字母色块兜底（色相按域名 hash，稳定不闪）。
 * **固定槽位**：兜底色块先占满 size，加载成功后叠在上头替换——不会"先空后跳版"。
 *
 * 刻意不引 Coil：项目无该依赖，favicon 是小尺寸静态图，OkHttp + BitmapFactory +
 * LruCache 足够（与 Markdown 图片块同一套路，见 Markdown.kt mdImageCache）。
 */

private class FaviconResult(val bmp: ImageBitmap?)
private val faviconCache = android.util.LruCache<String, FaviconResult>(80)
private val faviconClient by lazy {
    okhttp3.OkHttpClient.Builder()
        // 多源串行降级：单源超时要紧，否则全失败时字母色块要等 ~40s 才出现
        .connectTimeout(java.time.Duration.ofSeconds(4))
        .readTimeout(java.time.Duration.ofSeconds(5))
        .build()
}

private fun loadFavicon(domain: String): ImageBitmap? = runCatching {
    // 多源降级（真机反馈 2026-10-07）：单源 Google s2 在直连设备（不走代理的真机）被墙，
    // 全部域名只剩字母色块；模拟器走 PC 代理才正常。按序尝试：
    // ① 站点自身 /favicon.ico（apex 与 www 双变体，国内站直连可达，logo 最准）
    // ② api.iowen.cn（国内聚合，直连快）③ Google s2（代理环境）④ favicone.com
    val sources = listOf(
        "https://$domain/favicon.ico",
        "https://www.$domain/favicon.ico",
        "https://api.iowen.cn/favicon/$domain.png",
        "https://www.google.com/s2/favicons?domain=$domain&sz=64",
        "https://favicone.com/$domain?s=64"
    )
    for (url in sources) {
        val bytes = faviconClient.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) null else r.body?.bytes()
        } ?: continue
        decodeFaviconBytes(bytes)?.let { return@runCatching it }   // 非图片/解不开 → 下一源
    }
    null
}.getOrNull()

/**
 * favicon 字节 → 位图。BitmapFactory 直接能解（PNG/JPEG/WebP 响应，多数站点实际
 * 返回的 .ico URL 里装的是 PNG）就用；**ICO 容器 BitmapFactory 不支持**（真机反馈
 * "不少网站识别不到图标"的大头：zaobao/nikkei 等老牌媒体 favicon.ico 是真 ICO），
 * 手工解包目录取最大图像项，内嵌 PNG 直接解码（老式 BMP-in-ICO 解码高度翻倍，放弃落字母）。
 */
private fun decodeFaviconBytes(bytes: ByteArray): ImageBitmap? {
    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { return it.asImageBitmap() }
    if (bytes.size < 6 + 16 || bytes[2].toInt() != 1 || bytes[3].toInt() != 0) return null
    val count = (bytes[4].toInt() and 0xFF) or ((bytes[5].toInt() and 0xFF) shl 8)
    var bestOff = -1; var bestSize = 0
    for (i in 0 until count) {
        val off = 6 + 16 * i
        if (off + 16 > bytes.size) break
        val sz = (bytes[off + 8].toInt() and 0xFF) or
            ((bytes[off + 9].toInt() and 0xFF) shl 8) or
            ((bytes[off + 10].toInt() and 0xFF) shl 16) or
            ((bytes[off + 11].toInt() and 0xFF) shl 24)
        val dataOff = (bytes[off + 12].toInt() and 0xFF) or
            ((bytes[off + 13].toInt() and 0xFF) shl 8) or
            ((bytes[off + 14].toInt() and 0xFF) shl 16) or
            ((bytes[off + 15].toInt() and 0xFF) shl 24)
        if (sz > bestSize && dataOff in 1 until bytes.size && dataOff + sz <= bytes.size) {
            bestOff = dataOff; bestSize = sz
        }
    }
    if (bestOff < 0) return null
    val img = bytes.copyOfRange(bestOff, bestOff + bestSize)
    // PNG 签名（89 50 4E 47）才解：BMP-in-ICO 的 height 字段双倍，直接解会变形
    if (img.size >= 4 && img[0] == 0x89.toByte() && img[1] == 0x50.toByte() &&
        img[2] == 0x4E.toByte() && img[3] == 0x47.toByte()
    ) {
        return android.graphics.BitmapFactory.decodeByteArray(img, 0, img.size)?.asImageBitmap()
    }
    return null
}

/** favicon 专用 IO 协程域：预加载不占用调用方上下文。 */
private val faviconScope = kotlinx.coroutines.CoroutineScope(
    kotlinx.coroutines.SupervisorJob() + Dispatchers.IO
)
private val prefetchRequested = java.util.Collections.newSetFromMap(
    java.util.concurrent.ConcurrentHashMap<String, Boolean>()
)

/**
 * 会话打开/流式到达时预热缓存（真机反馈：等展开步骤才拉 logo，看得见加载过程）。
 * 重复调用幂等：已缓存/已发起的域名直接跳过；失败也按已处理记（与 Favicon 的
 * 不重试语义一致，进程内不再抖动）。
 */
fun prefetchFavicons(domains: List<String>) {
    domains.filter { it.isNotBlank() && faviconCache.get(it) == null && prefetchRequested.add(it) }
        .forEach { d -> faviconScope.launch { faviconCache.put(d, FaviconResult(loadFavicon(d))) } }
}

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
