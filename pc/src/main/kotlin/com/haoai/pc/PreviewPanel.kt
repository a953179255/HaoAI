package com.haoai.pc

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.addJsonObject
import kotlin.math.roundToInt

/**
 * 浏览器预览面板的后端逻辑（CDP 调用本身在 `BrowserSession`）。
 *
 * 单拎一个对象出来是为了能测：坐标换算与网址白名单这两处光靠真浏览器跑一遍测不出来 ——
 * 真浏览器只会走"正常"那一条，而负数、零宽、`file://`、`javascript:` 这些才是会出事的那些。
 */
object PreviewPanel {

    /**
     * 只放行 http/https。
     *
     * `file://` 挡掉是因为这条通道能把任意本地路径渲染成一张图给人看，
     * 而面板上那个框的语义是"输个网址"；`javascript:` / `data:` 挡掉是因为
     * 它们会被当成网址导航，等于把脚本注进那个浏览器实例。
     * 没写协议时补 https://（人输 `example.com` 是常态）。
     */
    fun safeUrl(raw: String): String? {
        // 控制字符要在 trim **之前**查：换行藏在末尾时 trim 会把它抹掉，
        // 于是"带控制字符的网址"以干净的样子过关（真拿去导航就是 CRLF 注入的入口）。
        if (raw.length > MAX_URL || raw.any { it.code < 32 || it.code == 127 }) return null
        val t = raw.trim()
        if (t.isEmpty()) return null
        val low = t.lowercase()
        if (BAD_SCHEMES.any { low.startsWith(it) }) return null
        if (low.contains("://")) {
            if (!low.startsWith("http://") && !low.startsWith("https://")) return null
        }
        val withScheme = if (low.contains("://")) t else {
            // 没写协议时补哪个，看它像不像本机服务：localhost 或带端口的默认 http ——
            // 预览面板最常输的就是 `localhost:5173` 这种，补成 https 会连不上本地 dev server。
            val authority = t.substringBefore('/').substringBefore('?')
            val bare = authority.substringBefore(':').trim('[', ']').lowercase()
            val local = bare == "localhost" || bare.startsWith("127.") || bare == "::1" || bare == "0.0.0.0"
            if (local || authority.contains(':')) "http://$t" else "https://$t"
        }
        val host = withScheme.substringAfter("://").substringBefore('/')
        if (host.isBlank() || host.contains(' ') || host.contains('@')) return null
        return withScheme
    }

    private const val MAX_URL = 2000

    /**
     * 没有 `://` 的那串会被当成"主机名"补上 https://，所以危险协议必须逐个点名 ——
     * `javascript:alert(1)` 不带斜杠，光看"有没有 ://"会把它放过来变成
     * `https://javascript:alert(1)`：看着挡住了，其实只是把脏东西挪了个位置。
     */
    private val BAD_SCHEMES = listOf(
        "javascript:", "data:", "file:", "blob:", "vbscript:", "about:", "view-source:",
        "chrome:", "edge:", "chrome-extension:", "resource:", "jar:", "phar:"
    )
    /**
     * 图上的那个点 → 页面上的那个点。
     *
     * 画面是被 `<img>` 缩着显示的，而 CDP 吃的是页面 CSS 像素，所以按比例换算并夹进视口。
     * 尺寸有一项 ≤0（页面还没量出来）时返回 (0,0) 之外更稳的做法是原样返回 0 ——
     * 调用方靠 `vw > 0` 判断能不能送，这里只保证不会算出越界坐标。
     */
    fun mapClick(xInImg: Double, yInImg: Double, imgW: Int, imgH: Int, vw: Int, vh: Int): Pair<Int, Int> {
        if (imgW <= 0 || imgH <= 0 || vw <= 0 || vh <= 0) return 0 to 0
        val px = (xInImg * vw / imgW).roundToInt()
        val py = (yInImg * vh / imgH).roundToInt()
        return px.coerceIn(0, vw - 1) to py.coerceIn(0, vh - 1)
    }

    /** 开关没开时给的那句话 —— 要说清去哪开，别只说"不行"。 */
    const val OFF_NOTE =
        "浏览器控制没开。这是「实验特性」里的 `browser_control`：设置 → 实验特性 → 浏览器控制（CDP）。" +
            "它开的是\"这台服务能操作一台独立配置目录的浏览器\"，不碰你自己的 Edge 登录态。"

    fun stateJson(on: Boolean, note: String, url: String, title: String, vw: Int, vh: Int,
                  pages: List<Triple<String, String, String>>): String = buildJsonObject {
        put("ok", true)
        put("on", on)
        put("note", note)
        put("url", url)
        put("title", title)
        put("vw", vw)
        put("vh", vh)
        putJsonArray("pages") {
            pages.forEach { (id, t, u) ->
                addJsonObject { put("id", id); put("title", t); put("url", u) }
            }
        }
    }.toString()

    fun okJson(note: String): String = buildJsonObject {
        put("ok", true); put("note", note)
    }.toString()

    fun errJson(msg: String): String = buildJsonObject {
        put("ok", false); put("error", msg)
    }.toString()
}
