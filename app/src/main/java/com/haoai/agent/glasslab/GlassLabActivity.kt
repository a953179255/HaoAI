package com.haoai.agent.glasslab

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.haoai.agent.ui.common.GlassPanel
import com.haoai.agent.ui.common.HaoChip
import com.haoai.agent.ui.common.HaoGroup
import com.haoai.agent.ui.common.HaoRow
import com.haoai.agent.ui.theme.GlassTuning
import com.haoai.agent.ui.theme.HaoTone
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.haoai.agent.ui.common.appLayer

/**
 * 玻璃实验室：**拖滑杆 = 直接改全 App 的玻璃质感**（GlassTuning 单例，实时生效）。
 * 上面是"演示配方"参照（固定参数），下面是当前全项目正在用的参数。
 * 入口：设置首页 → 玻璃实验室；或 adb am start -n com.haoai.agent/.glasslab.GlassLabActivity
 */
class GlassLabActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // 套真实应用主题（跟随系统深浅）：此前裸 MaterialTheme=浅色紫基准，
            // 且页面文字全部写死深色，深色模式下整页糊底（2026-10-02 用户反馈）
            val dark = androidx.compose.foundation.isSystemInDarkTheme()
            com.haoai.agent.ui.theme.HaoTheme(darkTheme = dark) {
                GlassLab()
            }
        }
    }
}

@Composable
private fun GlassLab() {
    val context = LocalContext.current
    val wallpaper = remember {
        runCatching {
            com.haoai.agent.platform.WallpaperStore.loadBitmapCover(context, 1080, 2400)
        }.getOrNull()?.asImageBitmap()
    }
    val backdrop = rememberLayerBackdrop()

    // 与主界面同一环境：壁纸模式开（这样调出来的就是真实观感）
    CompositionLocalProvider(
        com.haoai.agent.ui.theme.LocalOnWallpaper provides true
    ) {
        Box(
            Modifier
                .fillMaxSize()
                // 壁纸缺失时（新机/模拟器）兜底主题底色，否则浅色主题的深色文字糊在黑窗上
                .background(MaterialTheme.colorScheme.background)
        ) {
            wallpaper?.let {
                Image(
                    bitmap = it,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }
            // 采样宿主：壁纸挂进采样层（与演示 BackdropDemoScaffold 同构）
            Box(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
                wallpaper?.let {
                    Image(
                        bitmap = it,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }
            }

            Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 顶栏 + 返回（2026-09-22 用户反馈：此前无顶栏无返回键，
                // adb 冷启动场景下系统返回键会直接退出 App 而不是回设置页）
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 4.dp, end = 16.dp, top = 4.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    androidx.compose.material3.IconButton(
                        onClick = { (context as? android.app.Activity)?.finish() }
                    ) {
                        androidx.compose.material3.Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Text(
                        "玻璃实验室 —— 拖滑杆实时改全 App 玻璃质感",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                // ── 参照：演示 App 配方（固定参数）──
                Text(
                    "① 演示 App 配方  blur 4 / lens(16, 32) / 白雾 0.5",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                GlassPanel(
                    backdrop = backdrop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .height(110.dp),
                    radius = 24.dp,
                    surfaceAlpha = 0.5f,
                    blurRadius = 4.dp,
                    lensRadius = 16.dp,
                    lensAmountMul = 2f,
                    chromaticAberration = true,
                    floating = true
                ) { }

                // ── 当前配方（= 全项目正在用的，拖动全局变化）──
                Text(
                    "② 当前配方（= 设置页/聊天页正在用的，拖动全局变化）",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                HaoGroup(backdrop = backdrop, modifier = Modifier.padding(horizontal = 16.dp)) {
                    HaoRow(
                        title = "模型大脑",
                        subtitle = "商汤 · glm-5.2",
                        showChevron = true
                    )
                    HaoRow(
                        title = "权限与自动化",
                        subtitle = "全自动 · 无障碍未启用",
                        trailing = { HaoChip("待处理", HaoTone.Warn) },
                        showChevron = true,
                        divider = true
                    )
                    HaoRow(
                        title = "Linux 环境",
                        subtitle = "沙箱发行版 · Ubuntu 24.04 LTS",
                        trailing = { HaoChip("已就绪", HaoTone.Accent) },
                        showChevron = true,
                        divider = true
                    )
                }

                // ── 输入框预览（折射参数独立一组，对应聊天页底部输入栏）──
                Text(
                    "③ 输入框玻璃（聊天页底部输入栏同款，滚过文字看扭曲程度）",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .height(64.dp)
                ) {
                    // 背后放一条会动的文字带模拟"内容穿过玻璃"：静态壁纸上折射
                    // 环带不明显，滚动文字才看得见扭曲
                    Text(
                        "滚动内容穿过输入框 → 折射环带把字边掰弯",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 8.dp)
                    )
                    GlassPanel(
                        backdrop = backdrop,
                        modifier = Modifier.fillMaxSize(),
                        radius = 26.dp,
                        surfaceAlpha = com.haoai.agent.ui.theme.haoCardSurfaceAlpha(),
                        lensRadius = GlassTuning.inputLensHeight.dp,
                        lensAmountMul = GlassTuning.inputLensAmountMul
                    ) { }
                }

                LabControls()

                Text(
                    "定稿后点「复制当前参数」把数值发我，我固化成默认值。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
        }
    }
}

@Composable
private fun LabControls() {
    val context = LocalContext.current
    Column(Modifier.padding(horizontal = 16.dp)) {
        SliderRow("模糊 dp", GlassTuning.blur, 0f, 30f) { GlassTuning.blur = it }
        SliderRow("折射高度 dp（环带宽）", GlassTuning.lensHeight, 4f, 80f) { GlassTuning.lensHeight = it }
        SliderRow("强度倍数", GlassTuning.lensAmountMul, 0.5f, 8f) { GlassTuning.lensAmountMul = it }
        SliderRow("白雾 alpha", GlassTuning.veil, 0f, 0.9f) { GlassTuning.veil = it }
        SliderRow("圆角 dp", GlassTuning.corner, 8f, 40f) { GlassTuning.corner = it }
        Text(
            "输入框玻璃（聊天页底部，单独一组；倍数=0 即关折射）",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp)
        )
        SliderRow("输入框折射高度 dp", GlassTuning.inputLensHeight, 0f, 40f) { GlassTuning.inputLensHeight = it }
        SliderRow("输入框强度倍数", GlassTuning.inputLensAmountMul, 0f, 4f) { GlassTuning.inputLensAmountMul = it }
        Row(
            Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("整面折射", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
            Switch(checked = GlassTuning.lensFull, onCheckedChange = { GlassTuning.lensFull = it })
            Text("色差", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
            Switch(checked = GlassTuning.ca, onCheckedChange = { GlassTuning.ca = it })
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedButton(
                onClick = {
                    val text = "blur=${GlassTuning.blur} lensHeight=${GlassTuning.lensHeight} " +
                        "amountMul=${GlassTuning.lensAmountMul} veil=${GlassTuning.veil} " +
                        "corner=${GlassTuning.corner} lensFull=${GlassTuning.lensFull} ca=${GlassTuning.ca} " +
                        "inputLensHeight=${GlassTuning.inputLensHeight} inputAmountMul=${GlassTuning.inputLensAmountMul}"
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("玻璃参数", text))
                },
                modifier = Modifier.weight(1f)
            ) { Text("复制当前参数") }
            OutlinedButton(
                onClick = { GlassTuning.reset() },
                modifier = Modifier.weight(1f)
            ) { Text("还原默认") }
        }
    }
}

@Composable
private fun SliderRow(label: String, value: Float, min: Float, max: Float, onChange: (Float) -> Unit) {
    Column(Modifier.padding(vertical = 2.dp)) {
        Text(
            "$label   ${"%.2f".format(value)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Slider(value = value, onValueChange = onChange, valueRange = min..max)
    }
}
