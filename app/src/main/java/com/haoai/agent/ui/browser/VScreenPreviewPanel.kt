package com.haoai.agent.ui.browser

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.haoai.agent.platform.vdisplay.VirtualScreenController
import com.haoai.agent.ui.common.GlassPanel

/**
 * 4.3 增强：虚拟屏实时预览面板（上游 两段式第三段）。vscreen_launch 成功
 * 自动弹出，聊天区保持可见；帧来自 VirtualScreenController.previewFrame
 * （ImageReader 帧管线降采样），仅观看——节点操作仍由 Agent 的 vscreen_* 工具
 * 完成，用户要手动接管时可等任务结束 vscreen_close，或临时用系统分屏。
 */
@Composable
fun VScreenPreviewPanel(
    backdrop: com.kyant.backdrop.backdrops.LayerBackdrop,
    onClose: () -> Unit
) {
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

    var shown by remember { mutableStateOf(false) }
    var heightFraction by remember { mutableStateOf(0.62f) }
    val slide by animateFloatAsState(if (shown) 0f else 1f, tween(260), label = "vscreenSlide")
    androidx.compose.runtime.LaunchedEffect(Unit) { shown = true }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val fullH = constraints.maxHeight
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .navigationBarsPadding()
                .padding(bottom = 8.dp)
                .fillMaxHeight(heightFraction)
                .graphicsLayer { translationY = slide * size.height }
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(18.dp)
                    .pointerInput(Unit) {
                        detectVerticalDragGestures { _, dy ->
                            if (fullH > 0) {
                                heightFraction = (heightFraction - dy / fullH).coerceIn(0.35f, 0.88f)
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier
                        .size(width = 36.dp, height = 4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f))
                )
            }
            GlassPanel(
                backdrop = backdrop,
                modifier = Modifier.fillMaxWidth(),
                radius = 20.dp,
                surfaceAlpha = 0.72f
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onClose, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Filled.Close, "关闭预览", modifier = Modifier.size(18.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text("虚拟屏", style = MaterialTheme.typography.labelLarge)
                        Text(
                            displayId?.let { "displayId=$it · Agent 正在后台操作（仅观看）" }
                                ?: "虚拟屏未启动",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                            maxLines = 1
                        )
                    }
                }
            }
            // 帧画面：来帧即刷；无帧时显示等待提示
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(top = 6.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.Black.copy(alpha = 0.85f)),
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
                        "等待虚拟屏画面…（应用上屏后出帧）",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.alpha(0.9f)
                    )
                }
            }
        }
    }
}
