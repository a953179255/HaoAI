package com.haoai.agent.agent.tools

import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import com.haoai.agent.platform.PermissionCenter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

class LocationTool : Tool {

    override val name = "location"
    override val desc =
        "获取手机当前地理位置（经纬度、精度、定位来源与时间）。首次使用需要定位权限；系统定位服务需开启。"
    override val params = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") { }
    }

    @Suppress("DEPRECATION")
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val context = ctx.appContext ?: return ToolResult("此环境不支持定位", true)
        if (!PermissionCenter.ensure(context, PermissionCenter.LOCATION)) {
            return ToolResult(
                "定位权限未授权：请在系统弹窗中允许，或在 设置 → 权限与自动化 中开启后重试",
                true
            )
        }
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

        // 1. 先看最近缓存位置（5 分钟内视为可用）
        var cached: Location? = null
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)) {
            runCatching { lm.getLastKnownLocation(p) }.getOrNull()?.let { loc ->
                if (cached == null || loc.time > (cached?.time ?: 0L)) cached = loc
            }
        }
        cached?.let { if (System.currentTimeMillis() - it.time < 5 * 60_000L) return format(it) }

        // 2. 请求一次实时定位（20s 超时）
        val fresh = withContext(Dispatchers.Main) {
            withTimeoutOrNull(20_000) {
                suspendCancellableCoroutine<Location?> { cont ->
                    val done = AtomicBoolean(false)
                    lateinit var listener: LocationListener
                    listener = LocationListener { loc ->
                        if (done.compareAndSet(false, true)) {
                            runCatching { lm.removeUpdates(listener) }
                            if (cont.isActive) cont.resume(loc)
                        }
                    }
                    // 超时/用户停止时摘除监听器（requestSingleUpdate 只有收到首个 fix 才自移除，
                    // GPS 无 fix 会永久挂住，每次调用泄漏一个 listener）
                    cont.invokeOnCancellation { runCatching { lm.removeUpdates(listener) } }
                    runCatching {
                        lm.requestSingleUpdate(
                            LocationManager.NETWORK_PROVIDER, listener, Looper.getMainLooper()
                        )
                        lm.requestSingleUpdate(
                            LocationManager.GPS_PROVIDER, listener, Looper.getMainLooper()
                        )
                    }.onFailure {
                        if (done.compareAndSet(false, true)) cont.resume(cached)
                    }
                }
            } ?: cached
        }

        return fresh?.let(::format)
            ?: ToolResult("暂时拿不到位置：请确认系统「定位服务」已开启后重试", true)
    }

    private fun format(loc: Location): ToolResult {
        val ageSec = (System.currentTimeMillis() - loc.time) / 1000
        val acc = if (loc.hasAccuracy()) "%.0f 米".format(loc.accuracy) else "未知"
        val src = when (loc.provider) {
            LocationManager.GPS_PROVIDER -> "GPS"
            LocationManager.NETWORK_PROVIDER -> "网络"
            else -> loc.provider ?: "未知"
        }
        return ToolResult(
            "当前位置：纬度 %.6f，经度 %.6f\n来源：$src · 精度：约 $acc · $ageSec 秒前"
                .format(loc.latitude, loc.longitude)
        )
    }
}
