package com.haoai.agent.ui.browser

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.haoai.agent.platform.vdisplay.VirtualScreenController
import com.haoai.agent.ui.common.GlassPanel
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 虚拟屏悬浮预览（用户选型 2026-09-10）：缩略图 ↔ 浮窗两态 + 拖拽 + 位置记忆。
 * 与浏览器悬浮窗同体系（float_windows SharedPreferences），但**纯观看不可接管**——
 * 虚拟屏完全由 Agent 的 vscreen_* 工具独立操作，用户不干预（也无全屏入口，
 * 想手动操作等任务结束 vscreen_close 或临时用系统分屏）。
 * - 缩略态（vscreen_launch 成功自动弹出）：116dp 宽实时帧小窗，点击展开；
 * - 浮窗态：46% 容器宽（比浏览器窄：无地址栏操作区，纯观看），拖拽记忆同浏览器。
 * 帧管线不变：previewFrame（ImageReader 降采样）+ 打开期间 2s WMS 强制合成兜底。
 */
@Composable
fun VScreenPreviewPanel(
    backdrop: com.kyant.backdrop.backdrops.LayerBackdrop,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val frame by VirtualScreenController.previewFrame.collectAsState()
    val displayId = VirtualScreenController.displayId

    BackHandler(onBack = onClose)

    // ROM 冻结 VD 输出面时 ImageReader 不出帧：面板打开期间周期性走 WMS 强制合成取帧
    androidx.compose.runtime.LaunchedEffect(displayId) {
        if (displayId != null) {
            while (true) {
                VirtualScreenController.refreshPreview()
                kotlinx.coroutines.delay(2000)
            }
        }
    }

    val density = androidx.compose.ui.platform.LocalDensity.current.density
    var expanded by remember { mutableStateOf(false) }
    val prefs = remember { context.getSharedPreferences("float_windows", android.content.Context.MODE_PRIVATE) }
    var containerW by remember { mutableStateOf(0f) }
    var containerH by remember { mutableStateOf(0f) }
    var winW by remember { mutableStateOf(0f) }
    var winH by remember { mutableStateOf(0f) }
    var offX by remember { mutableStateOf(0f) }
    var offY by remember { mutableStateOf(0f) }

    fun place() {
        if (containerW <= 0f || containerH <= 0f || winW <= 0f || winH <= 0f) return
        // 默认在浏览器缩略图下方（错开右上竖排）；有存档用存档
        val nx = prefs.getFloat("vscreen_x", -1f)
        val ny = prefs.getFloat("vscreen_y", -1f)
        if (nx >= 0f && ny >= 0f) {
            offX = (nx * containerW).coerceIn(0f, max(0f, containerW - winW))
            offY = (ny * containerH).coerceIn(0f, max(0f, containerH - winH))
        } else {
            offX = (containerW - winW - 10f * density).coerceAtLeast(10f)
            offY = 210f * density
        }
    }

    fun onDrag(dx: Float, dy: Float) {
        if (containerW <= 0f || winW <= 0f) return
        offX = (offX + dx).coerceIn(0f, max(0f, containerW - winW))
        offY = (offY + dy).coerceIn(0f, max(0f, containerH - winH))
        prefs.edit()
            .putFloat("vscreen_x", if (containerW > 0) offX / containerW else 0f)
            .putFloat("vscreen_y", if (containerH > 0) offY / containerH else 0f)
            .apply()
    }

    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned {
                containerW = it.size.width.toFloat()
                containerH = it.size.height.toFloat()
                if (expanded) place()
            }
    ) {
        if (expanded) {
            // ── 浮窗态：标题行拖拽 + 帧画面 ──
            Column(
                Modifier
                    .onGloballyPositioned {
                        winW = it.size.width.toFloat()
                        winH = it.size.height.toFloat()
                        place()
                    }
                    .width((containerW * 0.46f / (androidx.compose.ui.platform.LocalDensity.current.density)).dp.coerceAtLeast(190.dp))
                    .offset { IntOffset(offX.roundToInt(), offY.roundToInt()) }
            ) {
                GlassPanel(
                    backdrop = backdrop,
                    radius = 16.dp,
                    surfaceAlpha = 0.72f,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 6.dp, vertical = 3.dp)
                            .pointerInput(Unit) {
                                detectDragGestures(
                                    onDragStart = { },
                                    onDrag = { change, drag ->
                                        change.consume()
                                        onDrag(drag.x, drag.y)
                                    }
                                )
                            },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("虚拟屏", style = MaterialTheme.typography.labelLarge)
                            Text(
                                displayId?.let { "displayId=$it · Agent 独立操作" }
                                    ?: "虚拟屏未启动",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                                maxLines = 1
                            )
                        }
                        IconButton(onClick = { expanded = false }, modifier = Modifier.size(30.dp)) {
                            Icon(Icons.Filled.Close, "收缩为缩略图", modifier = Modifier.size(14.dp))
                        }
                        IconButton(onClick = onClose, modifier = Modifier.size(30.dp)) {
                            Icon(Icons.Filled.Close, "关闭预览", modifier = Modifier.size(14.dp))
                        }
                    }
                }
                // 帧画面：来帧即刷；无帧显示等待提示。深色底（虚拟屏多为浅色 UI，对比清晰）
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .padding(top = 6.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.Black.copy(alpha = 0.88f)),
                    contentAlignment = Alignment.Center
                ) {
                    val bmp = frame
                    if (bmp != null) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "虚拟屏画面",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Text(
                            "等待虚拟屏画面…",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.7f)
                        )
                    }
                    // 纯观看遮罩：虚拟屏不允许用户干预（与浏览器的「可接管」区分）
                    Box(
                        Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                awaitPointerEventScope {
                                    while (true) {
                                        awaitPointerEvent().changes.forEach { it.consume() }
                                    }
                                }
                            }
                    )
                    Text(
                        "仅观看 · Agent 独立操作",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 6.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(Color.Black.copy(alpha = 0.5f))
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }
        } else {
            // ── 缩略态：116dp 实时帧小窗，点击展开 ──
            Column(
                Modifier
                    .onGloballyPositioned {
                        winW = it.size.width.toFloat()
                        winH = it.size.height.toFloat()
                        place()
                    }
                    .width(116.dp)
                    .offset { IntOffset(offX.roundToInt(), offY.roundToInt()) }
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f))
                    .clickable { expanded = true }
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.16f))
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "虚拟屏",
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        Icons.Filled.OpenInFull,
                        "展开浮窗",
                        modifier = Modifier
                            .padding(start = 3.dp)
                            .size(10.dp)
                            .alpha(0.6f)
                    )
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                        .background(Color.Black.copy(alpha = 0.85f)),
                    contentAlignment = Alignment.Center
                ) {
                    val bmp = frame
                    if (bmp != null) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "虚拟屏缩略",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Text(
                            "等待画面…",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        }
    }
}
