package com.haoai.agent.ui.browser

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import kotlin.math.roundToInt

/**
 * 虚拟屏悬浮预览（用户定稿 2026-09-11）：与浏览器悬浮窗同款单形态。
 *
 * - 迷你窗：标题栏（虚拟屏 · displayId ｜ ⤢ 全屏查看 ｜ ⌄ 最小化）+ 帧画面
 *   （9:19.5 整屏比例，来帧即刷，纯观看遮罩——虚拟屏完全由 Agent 的
 *   vscreen_* 工具操作，用户不干预）。
 * - ⤢ = 全屏查看页：深色底 + 居中大帧（letterbox）+ 顶部 ↩ 收成小窗 +
 *   「Agent 独立操作」胶囊；同样纯观看。
 * - ⌄ = 最小化收起（VirtualScreenController.previewOpen=false），顶栏
 *   🖥️ 图标单击随时唤回。
 * - 拖动移动 / 双指捏合缩放 / 位置与缩放持久化（vscreen_*）/ 安全区夹取 /
 *   原地淡入——全部与浏览器悬浮窗同一套实现。
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

    // ROM 冻结 VD 输出面时 ImageReader 不出帧：面板打开期间周期性走 WMS 强制合成取帧
    LaunchedEffect(displayId) {
        if (displayId != null) {
            while (true) {
                VirtualScreenController.refreshPreview()
                kotlinx.coroutines.delay(2000)
            }
        }
    }

    var expanded by remember { mutableStateOf(false) }
    BackHandler(enabled = expanded) { expanded = false }
    BackHandler(enabled = !expanded) { onClose() }

    // 屏幕宽高比：虚拟屏规格 = 主屏 real size（ensureDisplay 同源），帧即整屏
    val screenAspect = remember {
        val dm = context.resources.displayMetrics
        if (dm.widthPixels > 0 && dm.heightPixels > 0) {
            dm.widthPixels.toFloat() / dm.heightPixels.toFloat()
        } else 9f / 19.5f
    }

    val density = context.resources.displayMetrics.density
    // 安全区：顶部让开顶栏、底部让开输入框
    val topInset = 96f * density
    val bottomInset = 104f * density

    val prefs = remember { context.getSharedPreferences("float_windows", android.content.Context.MODE_PRIVATE) }
    var containerW by remember { mutableStateOf(0f) }
    var containerH by remember { mutableStateOf(0f) }
    var miniW by remember { mutableStateOf(0f) }
    var miniH by remember { mutableStateOf(0f) }
    var offX by remember { mutableStateOf(0f) }
    var offY by remember { mutableStateOf(0f) }
    var zoom by remember { mutableFloatStateOf(prefs.getFloat("vscreen_zoom", 1f).coerceIn(MIN_ZOOM, MAX_ZOOM)) }
    var placed by remember { mutableStateOf(false) }

    fun clampOffsets() {
        if (containerW <= 0f || containerH <= 0f || miniW <= 0f || miniH <= 0f) return
        val maxX = maxOf(0f, containerW - miniW)
        val maxY = maxOf(0f, containerH - bottomInset - miniH)
        offX = offX.coerceIn(0f, maxX)
        offY = offY.coerceIn(topInset.coerceAtMost(maxOf(0f, containerH - miniH)), maxY.coerceAtLeast(topInset))
    }

    fun place() {
        if (placed || containerW <= 0f || containerH <= 0f || miniW <= 0f || miniH <= 0f) return
        placed = true
        val nx = prefs.getFloat("vscreen_x", -1f)
        val ny = prefs.getFloat("vscreen_y", -1f)
        if (nx >= 0f && ny >= 0f) {
            offX = nx * containerW
            offY = ny * containerH
            clampOffsets()
        } else {
            // 默认在浏览器小窗下方错开（两者同时出现时不叠在一起）
            offX = (containerW - miniW - 10f * density).coerceAtLeast(10f)
            offY = 210f * density
        }
    }

    fun onDrag(dx: Float, dy: Float) {
        if (containerW <= 0f || miniW <= 0f) return
        offX += dx
        offY += dy
        clampOffsets()
        prefs.edit()
            .putFloat("vscreen_x", if (containerW > 0) offX / containerW else 0f)
            .putFloat("vscreen_y", if (containerH > 0) offY / containerH else 0f)
            .apply()
    }

    fun onZoom(factor: Float) {
        if (factor <= 0f) return
        zoom = (zoom * factor).coerceIn(MIN_ZOOM, MAX_ZOOM)
        prefs.edit().putFloat("vscreen_zoom", zoom).apply()
    }

    val winAlpha by animateFloatAsState(
        targetValue = if (placed) 1f else 0f,
        animationSpec = tween(150),
        label = "vscreenIn"
    )

    val miniWidthDp = (MINI_BASE_DP * zoom).coerceAtMost(
        if (containerW > 0f) (containerW / density) - 16f else MINI_BASE_DP * MAX_ZOOM
    )

    if (expanded) {
        // ── 全屏查看页：深色底 + 居中大帧 + 收成小窗；纯观看 ──
        Column(
            Modifier
                .fillMaxSize()
                .background(Color(0xFF101410))
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { expanded = false }, modifier = Modifier.size(34.dp)) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        "收成小窗（返回小窗）",
                        tint = Color(0xFFEEEEEE),
                        modifier = Modifier.size(20.dp)
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        "虚拟屏",
                        style = MaterialTheme.typography.titleSmall,
                        color = Color(0xFFEEEEEE)
                    )
                    Text(
                        displayId?.let { "displayId=$it · 纯观看" } ?: "虚拟屏未启动",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF8A938A)
                    )
                }
                Text(
                    "Agent 独立操作",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF35C46A),
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color(0xFF17341F))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                val bmp = frame
                Box(
                    Modifier
                        .fillMaxHeight()
                        .aspectRatio(screenAspect)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFFF7F8F7))
                ) {
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
                            color = Color.White.copy(alpha = 0.7f),
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                    // 纯观看遮罩：虚拟屏不响应触摸
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
                }
            }
        }
        return
    }

    // ── 迷你窗（唯一形态，宽度已在上方统一计算）──
    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned {
                containerW = it.size.width.toFloat()
                containerH = it.size.height.toFloat()
                if (!placed) place() else clampOffsets()
            }
    ) {
        Column(
            Modifier
                .onGloballyPositioned {
                    miniW = it.size.width.toFloat()
                    miniH = it.size.height.toFloat()
                    if (!placed) place() else clampOffsets()
                }
                .width(miniWidthDp.dp)
                .offset { IntOffset(offX.roundToInt(), offY.roundToInt()) }
                .alpha(winAlpha)
                .shadowOrCreate()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.97f))
        ) {
            // 标题栏：拖动移动；⤢ 全屏查看（最小化左侧一位）、⌄ 最小化（最右）
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                    .padding(start = 8.dp, end = 2.dp, top = 2.dp, bottom = 2.dp)
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
                    Text(
                        "虚拟屏",
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1
                    )
                    Text(
                        displayId?.let { "displayId=$it" } ?: "未启动",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                        maxLines = 1
                    )
                }
                IconButton(
                    onClick = { expanded = true },
                    modifier = Modifier.size(30.dp)
                ) {
                    Icon(
                        Icons.Filled.OpenInFull,
                        "全屏查看",
                        modifier = Modifier.size(14.dp)
                    )
                }
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.size(30.dp)
                ) {
                    Icon(
                        Icons.Filled.KeyboardArrowDown,
                        "最小化悬浮窗",
                        modifier = Modifier
                            .size(17.dp)
                            .alpha(0.85f)
                    )
                }
            }
            // 帧画面（纯观看）：单指拖动移动、双指捏合缩放窗口；点按不穿透
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(screenAspect)
                    .background(Color(0xFF0E1210))
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoomChange, _ ->
                            // 双指期间只缩放不平移（与浏览器同款手感）
                            if (zoomChange == 1f) {
                                onDrag(pan.x, pan.y)
                            } else {
                                onZoom(zoomChange)
                            }
                        }
                    }
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
                        color = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                // 纯观看遮罩
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
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color.Black.copy(alpha = 0.5f))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
        }
    }
}
