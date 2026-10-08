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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
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
import com.haoai.agent.ui.common.RefractionTestPattern
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.luminance
import android.graphics.BitmapFactory

/**
 * 玻璃实验室：**拖滑杆 = 直接改全 App 的玻璃质感**（GlassTuning 单例，实时生效）。
 * 上面是"演示配方"参照（固定参数），下面是当前全项目正在用的参数。
 * 入口：设置首页 → 玻璃实验室；或 adb am start -n com.haoai.agent/.glasslab.GlassLabActivity
 */
class GlassLabActivity : ComponentActivity() {
    // 退出实验室 = 把当前调参快照落盘（与主题外观页同一条 persistGlass 路径）；
    // 逐帧写盘是禁区（updateSettings=双文件写固定成本），只在离开时写一次
    override fun onPause() {
        super.onPause()
        runCatching {
            (applicationContext as com.haoai.agent.HaoApplication).container
                .updateSettings { it.copy(glass = com.haoai.agent.ui.theme.GlassTuning.snapshot()) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // 主题与主界面同源（2026-10-08）：此前裸 isSystemInDarkTheme()，
            // App 固定浅色+系统深色时实验室黑底、聊天页浅底，两边对不上；
            // 而且纯黑/纯色底上折射根本看不出——背景默认改用高对比测试图案
            val app = applicationContext as com.haoai.agent.HaoApplication
            val settings by app.container.settingsFlow.collectAsState()
            val dark = when (settings.themeMode) {
                "dark" -> true
                "light" -> false
                else -> androidx.compose.foundation.isSystemInDarkTheme()
            }
            com.haoai.agent.ui.theme.HaoTheme(
                darkTheme = dark,
                dynamicColor = settings.dynamicColor,
                seedIndex = settings.themeSeed,
                customSeed = settings.customSeedActive,
                amoled = settings.amoledMode
            ) {
                GlassLab()
            }
        }
    }
}

@Composable
private fun GlassLab() {
    val context = LocalContext.current
    val myWallpaper = remember {
        runCatching {
            com.haoai.agent.platform.WallpaperStore.loadBitmapCover(context, 1080, 2400)
        }.getOrNull()?.asImageBitmap()
    }
    // 背景三模式（2026-10-08）：纯色/黑底上折射根本看不出来（用户实测），
    // 默认「测试图案」= 程序画的高对比网格+色块+文字，折射一试即现形；
    // 「我的壁纸」= App 聊天壁纸（真实观感）；「相册选图」= 临时挑一张对比
    var bgMode by remember { mutableStateOf("test") }
    var pickedBitmap by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    val photoPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { ins ->
                    BitmapFactory.decodeStream(ins)?.let { bmp ->
                        pickedBitmap = bmp.asImageBitmap()
                        bgMode = "picked"
                    }
                }
            }
        }
    }
    val bgImage: androidx.compose.ui.graphics.ImageBitmap? = when (bgMode) {
        "wallpaper" -> myWallpaper
        "picked" -> pickedBitmap
        else -> null
    }
    val backdrop = rememberLayerBackdrop()

    // 与主界面同一环境：壁纸模式开（这样调出来的就是真实观感）
    CompositionLocalProvider(
        com.haoai.agent.ui.theme.LocalOnWallpaper provides true
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            // 采样宿主：背景挂进采样层（玻璃折射采的就是这一层）
            Box(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
                if (bgImage != null) {
                    Image(
                        bitmap = bgImage,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else if (bgMode == "test") {
                    RefractionTestPattern(
                        dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
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

                // ── 背景切换：折射只在有纹理的底上看得出（纯色底=看不出扭曲）──
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "背景",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    listOf(
                        "test" to "测试图案",
                        "wallpaper" to "我的壁纸",
                        "picked" to "相册选图"
                    ).forEach { (mode, label) ->
                        val selected = bgMode == mode
                        OutlinedButton(
                            onClick = {
                                when (mode) {
                                    "picked" -> if (pickedBitmap != null) bgMode = "picked" else photoPicker.launch("image/*")
                                    else -> bgMode = mode
                                }
                            },
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                horizontal = 12.dp, vertical = 6.dp
                            )
                        ) {
                            Text(
                                if (mode == "wallpaper" && myWallpaper == null) "$label(无)" else label,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (selected) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
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
                        surfaceAlpha = com.haoai.agent.ui.theme.haoInputSurfaceAlpha(),
                        // 与真输入框同配方：折射走全局统一值，模糊走输入框专属档
                        lensRadius = GlassTuning.lensHeight.dp,
                        lensAmountMul = GlassTuning.lensAmountMul,
                        blurRadius = GlassTuning.inputBlur.dp
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
        SliderRow("模糊 dp（全 App 玻璃统一）", GlassTuning.blur, 0f, 16f, 0.5f) { GlassTuning.blur = it }
        SliderRow("折射高度 dp（环带宽，统一）", GlassTuning.lensHeight, 0f, 40f, 0.5f) { GlassTuning.lensHeight = it }
        SliderRow("强度倍数（统一；0=关折射）", GlassTuning.lensAmountMul, 0f, 4f, 0.1f) { GlassTuning.lensAmountMul = it }
        SliderRow("白雾 alpha", GlassTuning.veil, 0f, 0.9f, 0.01f) { GlassTuning.veil = it }
        SliderRow("圆角 dp", GlassTuning.corner, 0f, 32f, 0.5f) { GlassTuning.corner = it }
        Text(
            "顶栏 / 输入框专属档（折射与全局一致，只有磨砂和白雾单独）",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp)
        )
        SliderRow("顶栏模糊 dp", GlassTuning.barBlur, 0f, 24f, 0.5f) { GlassTuning.barBlur = it }
        SliderRow("顶栏白雾 alpha", GlassTuning.barVeil, 0f, 0.9f, 0.01f) { GlassTuning.barVeil = it }
        SliderRow("输入框模糊 dp", GlassTuning.inputBlur, 0f, 16f, 0.5f) { GlassTuning.inputBlur = it }
        SliderRow("输入框白雾 alpha", GlassTuning.inputVeil, 0f, 0.9f, 0.01f) { GlassTuning.inputVeil = it }
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
                        "barBlur=${GlassTuning.barBlur} barVeil=${GlassTuning.barVeil} inputBlur=${GlassTuning.inputBlur} inputVeil=${GlassTuning.inputVeil}"
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
private fun SliderRow(
    label: String,
    value: Float,
    min: Float,
    max: Float,
    /** 步进（0=连续）：设置页定稿 0.5dp/0.1×/0.01α 后实验室同步，两边手感一致 */
    step: Float = 0f,
    onChange: (Float) -> Unit
) {
    Column(Modifier.padding(vertical = 2.dp)) {
        Text(
            "$label   ${"%.2f".format(value)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        val steps = if (step > 0f) ((max - min) / step).toInt() - 1 else 0
        Slider(value = value, onValueChange = onChange, valueRange = min..max, steps = steps)
    }
}

/**
 * 折射测试背景已提取到 ui/common/GlassPreview.kt（与设置页「主题外观」共用）。
 */
