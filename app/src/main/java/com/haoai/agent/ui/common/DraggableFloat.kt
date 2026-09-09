package com.haoai.agent.ui.common

import android.content.Context
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.max

/**
 * 浮窗拖拽 + 位置持久化（用户要求 2026-09-10：进设置等其他界面再返回，
 * 浮窗还在上次拖到的位置）。
 *
 * - 位置以「窗口左上角相对容器」的归一化坐标存 SharedPreferences（键值 XML；
 *   仅两个浮窗，无需 DataStore 协程开销），跨界面/进程重建等比恢复；
 * - 首次出现无存档时用 defaultPosition（右上角），用户拖动后才落盘；
 * - 拖动中实时 clamp 在容器内；松手落盘。
 *
 * 用法：`Modifier.floatDraggable("browser_float") { sw, sh, ww, wh -> Offset(sw - ww - 32f, 160f) }`
 * 该 modifier 自带 offset 定位，调用处不要再包 offset/align。
 */
fun Modifier.floatDraggable(
    key: String,
    containerSize: androidx.compose.runtime.MutableState<Offset>,
    defaultPosition: (screenW: Float, screenH: Float, winW: Float, winH: Float) -> Offset
): Modifier = composed {
    val context = LocalContext.current
    val prefs = remember(key) { context.getSharedPreferences("float_windows", Context.MODE_PRIVATE) }
    val density = LocalDensity.current

    var norm by remember(key) {
        mutableStateOf(
            if (prefs.contains("${key}_x"))
                Offset(prefs.getFloat("${key}_x", 0f), prefs.getFloat("${key}_y", 0f))
            else null
        )
    }
    var offsetPx by remember(key) { mutableStateOf(Offset.Zero) }
    var winPx by remember(key) { mutableStateOf(Offset.Zero) }
    var placed by remember(key) { mutableStateOf(false) }

    fun recompute() {
        val cw = containerSize.value.x
        val ch = containerSize.value.y
        if (cw <= 0f || ch <= 0f || winPx.x <= 0f) return
        val n = norm ?: defaultPosition(cw, ch, winPx.x, winPx.y).let {
            Offset((it.x / cw).coerceIn(0f, 1f), (it.y / ch).coerceIn(0f, 1f))
        }
        offsetPx = Offset(
            (n.x * cw).coerceIn(0f, max(0f, cw - winPx.x)),
            (n.y * ch).coerceIn(0f, max(0f, ch - winPx.y))
        )
        placed = true
    }

    this
        .onGloballyPositioned { coords ->
            val size = Offset(coords.size.width.toFloat(), coords.size.height.toFloat())
            if (size != winPx) {
                winPx = size
                recompute()
            }
        }
        .offset { androidx.compose.ui.unit.IntOffset(offsetPx.x.toInt(), offsetPx.y.toInt()) }
        .pointerInput(key, containerSize.value) {
            detectDragGestures(
                onDragStart = { },
                onDrag = { change, drag ->
                    change.consume()
                    val d = with(density) { Offset(drag.x, drag.y) }
                    val maxX = max(0f, containerSize.value.x - winPx.x)
                    val maxY = max(0f, containerSize.value.y - winPx.y)
                    val nx = (offsetPx.x + d.x).coerceIn(0f, maxX)
                    val ny = (offsetPx.y + d.y).coerceIn(0f, maxY)
                    offsetPx = Offset(nx, ny)
                    if (containerSize.value.x > 0 && containerSize.value.y > 0) {
                        norm = Offset(nx / containerSize.value.x, ny / containerSize.value.y)
                    }
                },
                onDragEnd = {
                    norm?.let {
                        prefs.edit().putFloat("${key}_x", it.x).putFloat("${key}_y", it.y).apply()
                    }
                }
            )
        }
}

/**
 * 供「父容器上报尺寸」用：浮窗所在 Box 调用，把容器尺寸写进 state，
 * floatDraggable 内部据此 clamp。
 */
@Composable
fun rememberFloatContainerSize(): androidx.compose.runtime.MutableState<Offset> {
    val state = remember { androidx.compose.runtime.mutableStateOf(Offset.Zero) }
    return state
}

/** 容器尺寸上报 modifier：挂在浮窗父 Box 上。 */
fun Modifier.floatContainer(state: androidx.compose.runtime.MutableState<Offset>): Modifier =
    onGloballyPositioned {
        val v = Offset(it.size.width.toFloat(), it.size.height.toFloat())
        if (state.value != v) state.value = v
    }
