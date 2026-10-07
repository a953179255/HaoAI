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
 * Android 默认跟随；请求带浏览器 UA，反爬站点才放行）。全失败/超时 → 域名首字母色块兜底
 * （色相按域名 hash，稳定不闪）。
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
    // ② Google s2（代理环境）③ favicone.com。iowen 聚合源实测全 404 已死，移除。
    val sources = listOf(
        "https://$domain/favicon.ico",
        "https://www.$domain/favicon.ico",
        "https://www.google.com/s2/favicons?domain=$domain&sz=64",
        "https://favicone.com/$domain?s=64"
    )
    for (url in sources) {
        val bytes = faviconClient.newCall(
            okhttp3.Request.Builder().url(url)
                // 浏览器 UA：默认 okhttp/* 被 nikkei(403)/reuters(401) 等反爬直接拦死
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36")
                .build()
        ).execute().use { r ->
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
 * 手工解包目录取最大图像项：内嵌 PNG 直接解码，BMP-in-ICO 走 [decodeBmpIco]。
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
    // PNG 签名（89 50 4E 47）：直接解
    if (img.size >= 4 && img[0] == 0x89.toByte() && img[1] == 0x50.toByte() &&
        img[2] == 0x4E.toByte() && img[3] == 0x47.toByte()
    ) {
        return android.graphics.BitmapFactory.decodeByteArray(img, 0, img.size)?.asImageBitmap()
    }
    // BITMAPINFOHEADER（前 4 字节小端=40）：BMP-in-ICO，手工解 BGRA/BGR 底上行序
    return decodeBmpIco(img)?.asImageBitmap()
}

/** ICO 内嵌 BMP（BITMAPINFOHEADER 起步、无文件头、底向上、高度字段含 AND 掩码双倍）。
 *  只接 24/32bpp（favicon 实际全在这两档），其余返回 null 落下一源/字母兜底。 */
private fun decodeBmpIco(img: ByteArray): android.graphics.Bitmap? = runCatching {
    fun i32(o: Int) = (img[o].toInt() and 0xFF) or ((img[o].toInt() and 0xFF) shl 8) or
        ((img[o].toInt() and 0xFF) shl 16) or ((img[o].toInt() and 0xFF) shl 24)
    val biSize = i32(0)
    if (biSize < 40) return@runCatching null
    val w = i32(4)
    val h = i32(8) / 2
    val bpp = (img[14].toInt() and 0xFF) or ((img[15].toInt() and 0xFF) shl 8)
    if (w <= 0 || h <= 0 || w > 256 || h > 256 || (bpp != 32 && bpp != 24)) return@runCatching null
    val dataOff = 4 + biSize
    val stride = (w * bpp / 8 + 3) / 4 * 4
    if (dataOff + stride * h > img.size) return@runCatching null
    val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
    for (y in 0 until h) {
        val row = dataOff + (h - 1 - y) * stride   // 底向上
        for (x in 0 until w) {
            val p = row + x * bpp / 8
            val b = img[p].toInt() and 0xFF
            val g = img[p + 1].toInt() and 0xFF
            val r = img[p + 2].toInt() and 0xFF
            var a = if (bpp == 32) img[p + 3].toInt() and 0xFF else 0xFF
            if (a == 0 && bpp == 32) a = 255       // 老式无 alpha 的 32bpp 条目：全 0 视为不透明
            bmp.setPixel(x, y, (a shl 24) or (r shl 16) or (g shl 8) or b)
        }
    }
    bmp
}.getOrNull()

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
            // 字号单位坑（真机反馈 2026-10-07）：旧写法 size.toPx()*0.52 把**像素值**当 sp 用，
            // 14dp 图标在 2.625 密度屏上算出 37sp 巨型字母溢出方块；toSp 才是"dp 当文字尺寸"的正解
            val fs = with(LocalDensity.current) { size.toSp() * 0.52f }
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
