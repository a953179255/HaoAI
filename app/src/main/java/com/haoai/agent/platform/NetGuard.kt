package com.haoai.agent.platform

import java.io.IOException

/**
 * 明文流量门卫（C3）：manifest 层无法按目标收窄 cleartext，改在 OkHttp 拦截器强制——
 * http:// 仅允许本机回环与局域网地址（本地 llama.cpp / Ollama / LM Studio 场景），
 * 公网地址必须走 https。全部应用流量（对话/抓取/下载/健康检查）都经过这里。
 */
object NetGuard {

    private const val MSG = "明文 http 仅允许本机/局域网地址（127.x、192.168.x、10.x、172.16-31.x），公网请使用 https"

    /**
     * 用户显式放行的明文 http URL 前缀白名单（如自建 MCP 服务器）。
     * 仅在设置 UI 里逐条添加、默认为空；命中前缀即放行，不改变局域网/公网的默认规则。
     */
    @Volatile var httpWhitelist: List<String> = emptyList()

    fun isPrivateHost(host: String): Boolean {
        val h = host.trim().lowercase().removePrefix("[").removeSuffix("]")
        if (h == "localhost" || h == "::1") return true
        // 回环 127.x.x.x
        if (h.startsWith("127.")) return true
        // IPv4 私有段
        val parts = h.split('.').mapNotNull { it.toIntOrNull() }
        if (parts.size == 4 && parts.all { it in 0..255 }) {
            val (a, b) = parts
            return when {
                a == 10 -> true
                a == 192 && b == 168 -> true
                a == 172 && b in 16..31 -> true
                else -> false
            }
        }
        // 本地主机名（.local / 单主机名，如 PC 的 mDNS 名）
        return h.endsWith(".local") || h.endsWith(".lan")
    }

    fun check(url: String) {
        val http = url.trim().lowercase().startsWith("http://")
        if (!http) return
        // 用户显式白名单优先：命中 origin 前缀直接放行
        val target = url.trim()
        if (httpWhitelist.any { it.isNotBlank() && target.startsWith(it) }) return
        val host = runCatching { java.net.URI(url.trim()).host }.getOrNull()
            ?: throw IOException(MSG)
        if (!isPrivateHost(host)) throw IOException(MSG)
    }

    /** OkHttp 拦截器：请求发出前校验明文目标。 */
    fun interceptor() = okhttp3.Interceptor { chain ->
        NetGuard.check(chain.request().url.toString())
        chain.proceed(chain.request())
    }
}
