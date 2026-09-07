package com.haoai.agent.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.tanh

/**
 * 位于玻璃采样层（appLayer/layerBackdrop）内的内容层应设为 false，使内部玻璃组件
 * 退化为本地磨砂绘制（不 drawBackdrop 采样），避免自引用渲染循环崩溃。
 * 默认 true；内容层用 CompositionLocalProvider(LocalGlassRefract provides false) 包裹即可。
 */
val LocalGlassRefract = compositionLocalOf { true }

/**
 * 玻璃表面着色：浅色主题用白雾，深色主题用黑雾（白磨砂在暗色下发白发亮）。
 * 深色 alpha 需要放大补偿黑雾的低对比度。
 */
@Composable
internal fun glassSurfaceColor(alpha: Float): Color {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    return if (dark) Color(0xFF0A0D12).copy(alpha = (alpha * 1.8f).coerceAtMost(0.94f))
    else Color.White.copy(alpha = alpha)
}

/** 玻璃描边：暗色下白色描边收敛到发丝级，避免刺眼亮框。 */
@Composable
internal fun glassBorderColor(alpha: Float): Color {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    return if (dark) Color.White.copy(alpha = alpha * 0.35f) else Color.White.copy(alpha = alpha)
}

@Composable
fun rememberAppBackdrop(
    wallpaper: android.graphics.Bitmap? = null,
    dark: Boolean = false,
    baseTop: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color(0xFFEDF4EF),
    baseBottom: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color(0xFFD9E8DF)
): LayerBackdrop {
    // v0.18.1：ImageBitmap 包装提前到组合期——draw 块每帧执行，裸调 asImageBitmap()
    // 每帧每画布分配一次包装对象（3 个 backdrop × 60fps），白增 GC 压力
    val wpImage = remember(wallpaper) { wallpaper?.asImageBitmap() }
    return rememberLayerBackdrop {
        if (wpImage != null) {
            // 壁纸 cover-fit 铺满，给玻璃提供可折射的真实纹理（不加压层，保持通透）
            val canvasW = size.width
            val canvasH = size.height
            val scale = maxOf(canvasW / wpImage.width, canvasH / wpImage.height)
            val dw = wpImage.width * scale
            val dh = wpImage.height * scale
            drawImage(
                wpImage,
                dstOffset = androidx.compose.ui.unit.IntOffset(
                    ((canvasW - dw) / 2f).toInt().coerceAtMost(0),
                    ((canvasH - dh) / 2f).toInt().coerceAtMost(0)
                ),
                dstSize = androidx.compose.ui.unit.IntSize(dw.toInt(), dh.toInt())
            )
        } else if (dark) {
            // 默认暗色渐变（近黑深蓝，与暗色主题背景一致）
            drawRect(Brush.verticalGradient(listOf(Color(0xFF07090D), Color(0xFF0E131B))))
        } else {
            // 素色底：颜色由调用方按主题（支持动态取色）传入，
            // 玻璃透过的是主题背景色，而非固定的绿调
            drawRect(Brush.verticalGradient(listOf(baseTop, baseBottom)))
        }
        drawContent()
    }
}

fun Modifier.appLayer(backdrop: LayerBackdrop): Modifier = this.layerBackdrop(backdrop)

@Composable
fun GlassPanel(
    backdrop: LayerBackdrop,
    modifier: Modifier = Modifier,
    radius: Dp = 24.dp,
    surfaceAlpha: Float = 0.16f,
    tint: Color? = null,
    shape: Shape? = null,
    lensRadius: Dp = radius,
    blurRadius: Dp = radius / 3f,
    chromaticAberration: Boolean = false,
    refract: Boolean? = null,
    redrawKey: (() -> Any?)? = null,
    border: Boolean = true,
    /** 表面附加绘制（画在磨砂与着色之上、内容之下）：如顶栏状态栏带的渐变补强。仅折射路径生效。 */
    surfaceOverlay: (androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val r = refract ?: LocalGlassRefract.current
    val surface = glassSurfaceColor(surfaceAlpha)
    val border2 = glassBorderColor(0.45f)
    val panelModifier = if (r) {
        modifier
            // v7.1 硬裁剪：drawBackdrop 的 blur/lens 与表面填充会溢出圆角外的方形区域
            // （平色背景上呈四角灰块，GlassCard 同款修复——小尺寸圆角面板上最明显）
            .clip(shape ?: RoundedCornerShape(radius))
            .drawBackdrop(
                backdrop = backdrop,
                shape = { shape ?: RoundedCornerShape(radius) },
                effects = {
                    // 绘制期调用 redrawKey lambda 读取其中的 State：值变化 →
                    // ObserverModifierNode 回调失效重绘 → 采样 offset 用最新布局
                    // 坐标重算。直接传值无效（读参数不注册快照订阅）——必须传
                    // 「读取 State 的 lambda」
                    redrawKey?.invoke()
                    vibrancy()
                    blur(blurRadius.toPx())
                    // lens 折射按统一内边距从每条边向内采样，在方角处会产生弧形高光"伪圆角"。
                    // 方角玻璃（如侧栏左缘）传 lensRadius = 0.dp 关闭它，保证角部利落。
                    if (lensRadius > 0.dp) {
                        // 环宽 12-20dp：过粗（2×radius=48dp）成白圈、过细（14dp）没折射感
                        lens(lensRadius.toPx() * 1.1f, (lensRadius * 1.2f).coerceIn(12.dp, 20.dp).toPx(), chromaticAberration = chromaticAberration)
                    }
                },
                onDrawSurface = {
                    drawRect(surface)
                    if (tint != null) {
                        drawRect(tint, blendMode = BlendMode.Hue)
                        drawRect(tint.copy(alpha = 0.35f))
                    }
                    surfaceOverlay?.invoke(this)
                }
            )
            // 发丝描边画在玻璃表面之上（后置 modifier 后绘制），与退化分支观感对齐
            .then(if (border) Modifier.border(1.5.dp, border2, shape ?: RoundedCornerShape(radius)) else Modifier)
    } else {
        // 位于玻璃采样层内时禁止 drawBackdrop（否则渲染自引用递归崩溃），退化为本地磨砂：
        // Modifier.blur 对自身内容做高斯模糊 + 着色底，观感接近真玻璃（非死板白底）。
        // 实现手法：外包一个离屏 Box 画 backdrop 的内容色近似（用 surface 深色版），
        // 内容层加 blur——这里用「背景模糊层+表面」两层组合
        modifier
            .clip(shape ?: RoundedCornerShape(radius))
            .background(surface)
            .then(if (border) Modifier.border(1.5.dp, border2, shape ?: RoundedCornerShape(radius)) else Modifier)
            .then(
                if (tint != null) Modifier.background(tint) else Modifier
            )
    }
    Box(panelModifier) {
        content()
    }
}

/**
 * 液态玻璃按压高亮（对齐 Kyant0/AndroidLiquidGlass 官方 Catalog 的 InteractiveHighlight）：
 * - pressProgress：按压缩放进度（spring 回弹）
 * - offset：指尖相对按下点的位移，驱动折射层跟随流动
 * - highlightModifier：表面白色辉光，跟随指尖位置与按压力度（API 33+ 用 AGSL 径向光斑）
 * - gestureModifier：按压/拖动手势采集；不消费事件，可与 clickable 并存
 */
class LiquidPressHighlight(private val animationScope: CoroutineScope) {

    private val pressProgressSpec = spring<Float>(0.5f, 300f, 0.001f)
    private val positionSpec =
        spring(0.5f, 300f, Offset.VisibilityThreshold)

    private val pressProgressAnim = Animatable(0f)
    private val positionAnim =
        Animatable(Offset.Zero, Offset.VectorConverter, Offset.VisibilityThreshold)

    private var startPosition = Offset.Zero

    val pressProgress: Float get() = pressProgressAnim.value
    val offset: Offset get() = positionAnim.value - startPosition

    /** 表面辉光：按压时泛起白色光斑并跟随指尖（纯 Compose 径向渐变，全版本可用）。 */
    val highlightModifier: Modifier =
        Modifier.drawWithContent {
            val progress = pressProgress
            if (progress > 0f) {
                val center = positionAnim.value
                val radius = size.minDimension * 1.2f
                drawRect(
                    Color.White.copy(0.08f * progress),
                    blendMode = BlendMode.Plus
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.White.copy(0.18f * progress),
                            Color.White.copy(0.06f * progress),
                            Color.Transparent
                        ),
                        center = center,
                        radius = radius
                    ),
                    radius = radius,
                    center = center,
                    blendMode = BlendMode.Plus
                )
            }

            drawContent()
        }

    /** 手势采集：按下→进度弹到 1、移动→折射层跟随、松开→spring 回弹归零。 */
    val gestureModifier: Modifier =
        Modifier.pointerInput(animationScope) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                startPosition = down.position
                animationScope.launch {
                    launch { pressProgressAnim.animateTo(1f, pressProgressSpec) }
                    launch { positionAnim.snapTo(startPosition) }
                }
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    animationScope.launch { positionAnim.snapTo(change.position) }
                    if (!change.pressed) break
                }
                animationScope.launch {
                    launch { pressProgressAnim.animateTo(0f, pressProgressSpec) }
                    launch { positionAnim.animateTo(startPosition, positionSpec) }
                }
            }
        }
}

/**
 * 可交互的液态玻璃按钮：按压时玻璃整体缩放、折射随指尖方向流动（tanh 软限位），
 * 松手 spring 回弹；表面可叠加一层着色用于强调状态。
 */
@Composable
fun LiquidGlassButton(
    onClick: () -> Unit,
    backdrop: LayerBackdrop,
    modifier: Modifier = Modifier,
    shape: Shape = CircleShape,
    enabled: Boolean = true,
    surfaceColor: Color? = null,
    refract: Boolean? = null,
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable BoxScope.() -> Unit
) {
    val animationScope = rememberCoroutineScope()
    val highlight = remember(animationScope) { LiquidPressHighlight(animationScope) }

    val r = refract ?: LocalGlassRefract.current
    val bgModifier = if (r) {
        Modifier.drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = {
                vibrancy()
                // Catalog LiquidButton 同款：blur(2.dp) + lens(12.dp, 24.dp) —
                // 适中折射强度，按压时 liquid 感最强
                blur(2.dp.toPx())
                lens(12.dp.toPx(), 24.dp.toPx())
            },
            layerBlock = {
                val progress = highlight.pressProgress
                val scale = lerp(1f, 1f + 2.dp.toPx() / size.height, progress)
                // 不做跟指平移：折射层跟随指尖在小按钮上读作「按钮可以被拖走」（两轮用户反馈）。
                // 交互反馈保留按压缩放 + 指尖辉光，位置完全静止
                scaleX = scale
                scaleY = scale
            },
            onDrawSurface = {
                if (surfaceColor != null) {
                    drawRect(surfaceColor)
                }
            }
        )
    } else {
        Modifier
            .clip(shape)
            .background(surfaceColor ?: glassSurfaceColor(0.25f))
            .border(1.5.dp, glassBorderColor(0.45f), shape)
    }

    Box(
        modifier
            // 禁用态整体降透明（0.45）：与 GlassAlertDialog 禁用文字的观感一致。
            // 原先只把 surfaceColor alpha 降到 40%，内容文字仍是全亮，读不出不可点
            .graphicsLayer { alpha = if (enabled) 1f else 0.45f }
            .then(bgModifier)
            .clickable(
                interactionSource = null,
                indication = null,
                role = Role.Button,
                enabled = enabled,
                onClick = onClick
            )
            .then(highlight.highlightModifier)
            .then(if (enabled) highlight.gestureModifier else Modifier),
        contentAlignment = contentAlignment
    ) {
        content()
    }
}

/**
 * 可点击的液态玻璃卡片（对齐 Catalog 的 RefractionCard / 按压缩放）：
 * - 玻璃表面采样背景折射 + 按压缩放回弹，表面可叠着色强调
 * - 卡内内容直接放在玻璃上方，卡体本身即可点击
 */
@Composable
fun GlassCard(
    onClick: () -> Unit,
    backdrop: LayerBackdrop,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(18.dp),
    surfaceAlpha: Float = 0.16f,
    tint: Color? = null,
    lensRadius: Dp = 18.dp,
    refract: Boolean? = null,
    pressScale: Boolean = true,
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable BoxScope.() -> Unit
) {
    val animationScope = rememberCoroutineScope()
    val highlight = remember(animationScope) { LiquidPressHighlight(animationScope) }

    val r = refract ?: LocalGlassRefract.current
    val cardSurface = glassSurfaceColor(surfaceAlpha)
    val bgModifier = if (r) {
        Modifier
            // 硬裁剪到卡片形状：drawBackdrop 的 blur/lens 与表面填充会溢出圆角外的
            // 方形区域（平色背景上呈灰角块，实测确认）；磨砂路径本就有 clip，补齐折射路径
            .clip(shape)
            .drawBackdrop(
                backdrop = backdrop,
                shape = { shape },
                effects = {
                    vibrancy()
                    blur(4.dp.toPx())
                    if (lensRadius > 0.dp) {
                        lens(lensRadius.toPx() * 1.1f, (lensRadius * 1.2f).coerceIn(12.dp, 20.dp).toPx())
                    }
                },
            layerBlock = {
                // 与 LiquidGlassButton 同理：不做跟指平移——卡片被按住拖动会读作「可拖拽」。
                // 折射静态呈现，按压缩放与指尖辉光由 highlight 提供
            },
            onDrawSurface = {
                drawRect(cardSurface)
                // tint 不能画在这里：onDrawSurface 画布与 shape 存在亚像素错位，
                // 圆角矩形边缘会露到 shape 外形成灰色矩形带（实测确认）；
                // 改为在下方 Box 内容层用 background(tint, shape) 精确裁剪
            }
        )
    } else {
        Modifier
            .clip(shape)
            .background(cardSurface)
            // 描边与折射路径（glassBorderColor）同源同值：refract 切换时描边不跳变。
            // 前景系描边在浅色磨砂卡上仍可辨（白卡上白描边才真的不可见）
            .border(1.5.dp, glassBorderColor(0.45f), shape)
            .then(if (tint != null) Modifier.background(tint, shape) else Modifier)
    }

    Box(
        modifier
            // 此层必须常驻（即使不做按压缩放）：它把 drawBackdrop 的折射渲染隔离在
            // 独立 RenderNode 内，否则 blur/lens 的渲染边界会直接暴露成卡片四角直角伪影
            .graphicsLayer {
                if (pressScale) {
                    val p = highlight.pressProgress
                    val s = lerp(1f, 1f - 0.015f, p)
                    scaleX = s
                    scaleY = s
                }
            }
            .then(bgModifier)
            .clickable(
                interactionSource = null,
                indication = null,
                role = Role.Button,
                onClick = onClick
            )
            .then(highlight.highlightModifier)
            .then(highlight.gestureModifier),
        contentAlignment = contentAlignment
    ) {
        // 着色层：叠在玻璃表面之上、内容之下；background(tint, shape) 按 shape
        // 精确裁剪，无 onDrawSurface 的亚像素错位泄漏（磨砂路径已在 bgModifier 内）
        if (r && tint != null) {
            Box(Modifier.matchParentSize().background(tint, shape))
        }
        content()
    }
}

/**
 * 渲染一个液态玻璃开关胶囊；无背景采样时退化为纯绘制磨砂胶囊。
 * 仅当开关自身处于 glass 采样层内（drawBackdrop 会自引用导致渲染递归崩溃）时，
 * 应传 refract=false，用本地绘制代替背景采样。
 */
private fun DrawScope.drawCapsule(fraction: Float, press: Float, accent: Color) {
    val pad = 2.dp.toPx()
    // 胶囊圆角：磨砂回退路径（对话框内 refract=false）与折射路径同形，杜绝直角矩形开关
    val corner = CornerRadius(size.height / 2f, size.height / 2f)

    // 轨道：关=雾白磨砂，开=主题色浸染（近实心，与按钮主绿饱和度一致）
    drawRoundRect(
        color = lerp(Color.White.copy(alpha = 0.18f), accent.copy(alpha = 0.95f), fraction),
        cornerRadius = corner
    )
    drawRoundRect(
        color = Color.White.copy(alpha = lerp(0.38f, 0.10f, fraction)),
        cornerRadius = corner,
        style = Stroke(1.dp.toPx())
    )

    // 滑块：镜面小球（径向高光 + 描边），按压时微微鼓起
    val r = size.height / 2f - pad
    val cx = pad + r + fraction * (size.width - 2 * pad - 2 * r)
    val cy = size.height / 2f
    val rr = r * (1f + 0.08f * press)
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                Color.White,
                Color.White.copy(alpha = 0.97f),
                Color(0xFFE4EAF2).copy(alpha = 0.94f)
            ),
            center = Offset(cx, cy - rr * 0.3f),
            radius = rr * 1.4f
        ),
        radius = rr,
        center = Offset(cx, cy)
    )
    drawCircle(
        color = Color.White.copy(alpha = 0.9f),
        radius = rr,
        center = Offset(cx, cy),
        style = Stroke(0.8.dp.toPx())
    )
}

/**
 * 液态玻璃开关（对齐 AndroidLiquidGlass Catalog 的 LiquidToggle）：
 * - 玻璃胶囊轨道 + 镜面滑块，整体折射壁纸背景
 * - 点击即切换；横向拖动滑块实时跟随，过半提交，spring 回弹归位
 * - 切换与拖动提交时 CLOCK_TICK 触感反馈
 * - refract=true 时用 drawBackdrop 采样背景（玻璃元素必须位于采样层之外，否则渲染自引用崩溃）；
 *   若被放在 layerBackdrop / appLayer 采样子树内，必须传 refract=false，改用本地磨砂绘制。
 */
@Composable
fun LiquidToggle(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    backdrop: LayerBackdrop,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    checkedColor: Color? = null,
    refract: Boolean? = null
) {
    val animationScope = rememberCoroutineScope()
    val highlight = remember(animationScope) { LiquidPressHighlight(animationScope) }
    val view = LocalView.current
    val r = refract ?: LocalGlassRefract.current

    val width = 52.dp
    val height = 32.dp
    val travelPx = with(LocalDensity.current) { (width - height).toPx() }
    val accent = checkedColor ?: androidx.compose.material3.MaterialTheme.colorScheme.primary

    // 开关进度 0..1；拖动中用 dragFraction 覆盖显示，松手交还弹簧
    val progressAnim = remember { Animatable(if (checked) 1f else 0f) }
    var dragFraction by remember { mutableFloatStateOf(Float.NaN) }

    LaunchedEffect(checked) {
        if (dragFraction.isNaN()) {
            progressAnim.animateTo(if (checked) 1f else 0f, spring(0.55f, 380f))
        }
    }

    fun fraction(): Float =
        if (dragFraction.isNaN()) progressAnim.value else dragFraction

    val trackModifier = if (r) {
        Modifier.drawBackdrop(
            backdrop = backdrop,
            shape = { CircleShape },
            effects = {
                vibrancy()
                blur(2.dp.toPx())
                lens(8.dp.toPx(), 16.dp.toPx())
            },
            layerBlock = {
                // 折射层随进度轻微位移：内芯像液体一样滚向目标侧
                translationX = lerp(-2.dp.toPx(), 2.dp.toPx(), fraction())
            },
            onDrawSurface = {
                drawCapsule(fraction(), highlight.pressProgress, accent)
            }
        )
    } else {
        // 位于玻璃采样层内时禁止 drawBackdrop（否则渲染自引用递归崩溃），退化为本地磨砂绘制
        Modifier.drawWithContent {
            drawCapsule(fraction(), highlight.pressProgress, accent)
            drawContent()
        }
    }

    Box(
        modifier
            .size(width = width, height = height)
            .semantics {
                role = Role.Switch
                toggleableState = ToggleableState(checked)
            }
            .then(trackModifier)
            .then(highlight.highlightModifier)
            .pointerInput(enabled, checked) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var dragging = false
                    var aborted = false
                    var upChange: androidx.compose.ui.input.pointer.PointerInputChange? = null
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) { upChange = change; break }
                        val dx = change.position.x - down.position.x
                        val dy = change.position.y - down.position.y
                        if (!dragging && kotlin.math.abs(dx) > viewConfiguration.touchSlop) {
                            dragging = true
                        }
                        // 竖向滑动是在滚动外层列表：立即放弃手势，抬起时不能当成点击翻转开关
                        if (!dragging && kotlin.math.abs(dy) > viewConfiguration.touchSlop &&
                            kotlin.math.abs(dy) > kotlin.math.abs(dx)
                        ) {
                            aborted = true
                            break
                        }
                        if (dragging && enabled && onCheckedChange != null) {
                            dragFraction =
                                ((if (checked) 1f else 0f) + dx / travelPx).coerceIn(0f, 1f)
                            change.consume()
                        }
                    }
                    if (!aborted && enabled && onCheckedChange != null) {
                        if (dragging) {
                            val target = dragFraction >= 0.5f
                            dragFraction = Float.NaN
                            upChange?.consume()
                            if (target != checked) {
                                view.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                                onCheckedChange(target)
                            }
                        } else if (upChange?.isConsumed != true) {
                            // 纯点击也要消费 UP，否则外层可点击卡片会再触发一次（开关净效果为零）；
                            // UP 已被父级（列表滚动）消费时不算点击
                            upChange?.consume()
                            view.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                            onCheckedChange(!checked)
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
        content = {}
    )
}

/**
 * 子页面通用玻璃页头：返回键 + 标题 + 可选动作区。
 * 与聊天页 TopBar 同款通栏方角玻璃（含状态栏高度、贴边无圆角无描边），
 * 各二级页共用同一条视觉页头，仅换标题——保证全局一体性。
 */
@Composable
fun GlassPageBar(
    backdrop: LayerBackdrop,
    title: String,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    refract: Boolean? = null,
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {}
) {
    GlassPanel(
        backdrop = backdrop,
        modifier = modifier.fillMaxWidth(),
        radius = 0.dp,
        lensRadius = 0.dp,
        blurRadius = 12.dp,
        surfaceAlpha = 0.30f,
        border = false,
        refract = refract
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                // 玻璃含状态栏：内容排到状态栏下方，状态栏文字浮在玻璃上
                .statusBarsPadding()
                .padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (onBack != null) {
                androidx.compose.material3.IconButton(onClick = onBack) {
                    androidx.compose.material3.Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        tint = androidx.compose.material3.MaterialTheme.colorScheme.onBackground
                    )
                }
            }
            androidx.compose.material3.Text(
                title,
                style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f).padding(horizontal = 6.dp)
            )
            actions()
        }
    }
}

/**
 * 液态玻璃弹层壳：Popup + 进入/退出动画（淡入 + 缩放 0.92→1 + 轻微上滑）。
 * 退出先播动画，onDismiss 延迟到动画结束才真正卸载弹层；
 * content 的 close 参数供内容项点击后触发带动画的关闭。
 */
@Composable
fun GlassPopup(
    backdrop: LayerBackdrop,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    alignment: Alignment = Alignment.BottomEnd,
    offset: androidx.compose.ui.unit.IntOffset = androidx.compose.ui.unit.IntOffset.Zero,
    surfaceAlpha: Float = 0.52f,
    radius: Dp = 20.dp,
    content: @Composable (close: () -> Unit) -> Unit
) {
    var leaving by remember { mutableStateOf(false) }
    val progress by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (leaving) 0f else 1f,
        animationSpec = androidx.compose.animation.core.tween(durationMillis = 190),
        label = "glassPopupProgress"
    )
    LaunchedEffect(leaving) {
        if (leaving) {
            kotlinx.coroutines.delay(200)
            onDismiss()
        }
    }
    val slidePx = with(LocalDensity.current) { 10.dp.toPx() }
    androidx.compose.ui.window.Popup(
        alignment = alignment,
        offset = offset,
        onDismissRequest = { leaving = true },
        properties = androidx.compose.ui.window.PopupProperties(focusable = true)
    ) {
        GlassPanel(
            backdrop = backdrop,
            modifier = modifier.graphicsLayer {
                alpha = progress
                val s = 0.92f + 0.08f * progress
                scaleX = s
                scaleY = s
                translationY = (1f - progress) * slidePx
                // 变换原点取右上角：从来源控件（顶栏右侧）展开更自然
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(1f, 0f)
            },
            radius = radius,
            surfaceAlpha = surfaceAlpha
        ) {
            content { leaving = true }
        }
    }
}

/**
 * 液态玻璃弹窗壳：独立 Dialog 窗口采样不到 LayerBackdrop，
 * 用全屏半透明遮罩 + GlassPanel 承载（点遮罩关闭，内容区点击不穿透）。
 */
@Composable
fun GlassAlertDialog(
    backdrop: LayerBackdrop,
    title: String,
    onDismiss: () -> Unit,
    confirmLabel: String? = null,
    onConfirm: (() -> Unit)? = null,
    confirmEnabled: Boolean = true,
    dismissLabel: String? = null,
    danger: Boolean = false,
    contentMaxHeight: Dp = 420.dp,
    refract: Boolean? = null,
    /** 确认键通栏全宽（设计稿场景：单主操作弹窗，如「完成」） */
    fullWidthConfirm: Boolean = false,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    // 系统返回手势先关弹窗：后注册的 handler 优先，覆盖屏幕级的返回导航
    androidx.activity.compose.BackHandler(onBack = onDismiss)
    // 弹窗是独立于采样层的全屏遮罩：折射采样到的是弹窗「底下」的页面而非弹窗自身，
    // 按钮会显得「穿透背板 + 边缘光晕」。弹窗内统一退化为本地磨砂绘制。
    androidx.compose.runtime.CompositionLocalProvider(LocalGlassRefract provides false) {
    // 入场过渡：条件组合进入时 AnimatedVisibility 会从初始态播放 enter 动画
    androidx.compose.animation.AnimatedVisibility(
        visible = true,
        enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(140)) +
            androidx.compose.animation.scaleIn(
                initialScale = 0.92f,
                animationSpec = androidx.compose.animation.core.tween(180, easing = androidx.compose.animation.core.FastOutSlowInEasing)
            )
    ) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.30f))
            .clickable(interactionSource = null, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.Center
    ) {
        GlassPanel(
            backdrop = backdrop,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            radius = 28.dp,
            surfaceAlpha = 0.92f,
            blurRadius = 28.dp,
            chromaticAberration = true,
            refract = refract
        ) {
            Column(
                Modifier
                    .clickable(interactionSource = null, indication = null, onClick = {})
                    .padding(20.dp)
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Column(
                    Modifier
                        .padding(top = 12.dp)
                        .heightIn(max = contentMaxHeight)
                        .verticalScroll(rememberScrollState()),
                    content = content
                )
                if (onConfirm != null || dismissLabel != null) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp),
                        horizontalArrangement = if (fullWidthConfirm) Arrangement.Center
                        else Arrangement.spacedBy(12.dp, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (dismissLabel != null) {
                            // 取消=表面液态按钮（无着色）；确认=着色液态按钮（下方），Catalog LiquidButton 两款
                            LiquidGlassButton(
                                onClick = onDismiss,
                                backdrop = backdrop,
                                shape = RoundedCornerShape(percent = 50),
                                enabled = true,
                                refract = refract
                            ) {
                                Text(
                                    dismissLabel,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f),
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                                )
                            }
                        }
                        if (onConfirm != null) {
                            LiquidGlassButton(
                                onClick = onConfirm,
                                backdrop = backdrop,
                                shape = RoundedCornerShape(percent = 50),
                                enabled = confirmEnabled,
                                // 全宽模式：确认键铺满操作栏（单主操作弹窗）
                                modifier = if (fullWidthConfirm) Modifier.fillMaxWidth() else Modifier,
                                surfaceColor = when {
                                    !confirmEnabled -> MaterialTheme.colorScheme.primary.copy(alpha = 0.40f)
                                    danger -> MaterialTheme.colorScheme.error.copy(alpha = 0.92f)
                                    // 弹窗内按钮采样不到弹窗遮罩/面板（不在采样层里），透明度低了会直接透出壁纸
                                    else -> MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
                                },
                                refract = refract
                            ) {
                                Text(
                                    confirmLabel.orEmpty(),
                                    color = when {
                                        !confirmEnabled -> MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f)
                                        danger -> MaterialTheme.colorScheme.onError
                                        else -> MaterialTheme.colorScheme.onPrimary
                                    },
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    }
    }
}

/** 玻璃弹层里的输入框配色（半透明白容器，与 Onboarding 一致）。 */
@Composable
fun glassFieldColors() = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
    focusedTextColor = MaterialTheme.colorScheme.onBackground,
    unfocusedTextColor = MaterialTheme.colorScheme.onBackground,
    cursorColor = MaterialTheme.colorScheme.primary,
    focusedBorderColor = MaterialTheme.colorScheme.primary,
    unfocusedBorderColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.35f),
    focusedContainerColor = glassSurfaceColor(0.32f),
    unfocusedContainerColor = glassSurfaceColor(0.20f),
    focusedLabelColor = MaterialTheme.colorScheme.primary,
    unfocusedLabelColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
)

/**
 * 弹窗内的紧凑输入框：48dp 定高（Material 默认 56dp，浮动标签上下留白占掉一大截）。
 * label 固定渲染在框内左侧（不浮动），值与标签并排——纵向密度优先：
 * 同一屏能多看一两个字段。直接基于 Foundation BasicTextField 自绘边框，
 * 不碰 Material 内部 API（版本间签名不稳）。
 */
@Composable
fun CompactGlassField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    singleLine: Boolean = true,
    visualTransformation: androidx.compose.ui.text.input.VisualTransformation =
        androidx.compose.ui.text.input.VisualTransformation.None,
    trailing: @Composable (() -> Unit)? = null
) {
    val interaction = androidx.compose.runtime.remember {
        androidx.compose.foundation.interaction.MutableInteractionSource()
    }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(11.dp)
    val borderColor = if (focused) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.30f)
    // 降明度而非提白度：弹窗面板本身已是 0.92 白（浅色主题），再叠白色容器就是
    // 「白叠白」，边界全靠描边硬撑。改用 onBackground 叠加（浅色下=压暗、
    // 深色下=提亮），容器与面板有真实明度差；聚焦时再加深一档
    val container = if (focused) MaterialTheme.colorScheme.onBackground.copy(alpha = 0.07f)
    else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.04f)

    androidx.compose.foundation.layout.Row(
        modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(container, shape)
            .border(if (focused) 1.5.dp else 1.dp, borderColor, shape)
            .padding(end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 固定标签：始终在内容左侧，与已填值并排
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (focused) 0.95f else 0.8f),
            maxLines = 1,
            modifier = Modifier.padding(start = 12.dp)
        )
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty() && placeholder != null) {
                Text(
                    placeholder,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.35f),
                    maxLines = 1
                )
            }
            androidx.compose.foundation.text.BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = singleLine,
                visualTransformation = visualTransformation,
                interactionSource = interaction,
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodySmall.copy(
                    color = MaterialTheme.colorScheme.onBackground
                ),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary)
            )
        }
        trailing?.invoke()
    }
}

/**
 * 弹窗内统一的次级文字按钮：磨砂容器 + 细描边（透明 TextButton 会和玻璃面板融在一起，
 * 看起来"没做样式"）。刻意不做 drawBackdrop 折射——弹窗独立于采样层，折射只会采样到
 * 弹窗底下的页面，表现为「穿透背板 + 边缘一圈光晕」，统一走本地磨砂绘制。
 * backdrop/refract 参数保留以兼容旧调用点，不再参与绘制。
 */
@Composable
fun GlassTextButton(
    text: String,
    onClick: () -> Unit,
    backdrop: LayerBackdrop? = null,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    refract: Boolean? = null
) {
    // Catalog 同款轻量玻璃按钮：无 surfaceColor 覆盖，纯描边 + 点击液感反馈。
    // 弹窗内 drawBackdrop 会自引用渲染崩溃（背景层包含自身像素），所以放弃
    // 在弹窗内做折射，统一走本地磨砂；但**关键不贴半透明白底**——
    // 之前 0.28f 的半透明在弹窗里让背景页面元素透出来，按钮边缘形成视觉上
    // 的「大阴影」，违背 Catalog 的轻量玻璃美学
    val shape = RoundedCornerShape(percent = 50)
    val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val tintAlpha = androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (isPressed && enabled) 0.10f else 0f,
        animationSpec = androidx.compose.animation.core.tween(120),
        label = "glassTextBtnTint"
    ).value

    Box(
        modifier
            .graphicsLayer {
                // 按压缩放：Catalog LiquidButton 标配（scale 1.0 → 0.97），
                // 反馈感强但视觉无负担（不放大、不摇晃）
                val scale = if (isPressed && enabled) 0.965f else 1f
                scaleX = scale
                scaleY = scale
                // 禁用观感：与 LiquidGlassButton 统一处理
                alpha = if (enabled) 1f else 0.45f
            }
            .background(
                MaterialTheme.colorScheme.onBackground.copy(alpha = tintAlpha),
                shape
            )
            .border(
                1.dp,
                if (enabled) glassBorderColor(0.55f)
                else glassBorderColor(0.25f),
                shape
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                enabled = enabled,
                onClick = onClick
            )
            .padding(horizontal = 14.dp, vertical = 9.dp)
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (enabled) MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f)
            else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.38f)
        )
    }
}

/**
 * 玻璃分段选项卡（对齐 Kyant0/AndroidLiquidGlass Catalog 的 LiquidBottomTabs）：
 * 玻璃胶囊容器 + 着色液态滑动指示器 + 弹性动画。替代 Material3 SegmentedButton。
 * tabs/selectedIndex 由调用方受控；切换时指示器以轻微果冻的 spring 滑过去。
 */
@Composable
fun LiquidTabRow(
    tabs: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    backdrop: LayerBackdrop,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.primary
) {
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.fillMaxWidth().height(44.dp)) {
        val tabWidth = maxWidth / tabs.size
        // 指示器位置：0..tabs.size-1 的连续值，spring 弹性滑动
        val pos = androidx.compose.animation.core.animateFloatAsState(
            targetValue = selectedIndex.toFloat(),
            animationSpec = androidx.compose.animation.core.spring(
                dampingRatio = 0.85f,
                stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow
            ),
            label = "tabIndicator"
        )
        // 容器：玻璃胶囊（细 lens 环）
        val containerSurface = glassSurfaceColor(0.30f)
        Box(
            Modifier
                .fillMaxSize()
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { RoundedCornerShape(50) },
                    effects = {
                        vibrancy()
                        blur(6.dp.toPx())
                        lens(12.dp.toPx(), 12.dp.toPx())
                    },
                    onDrawSurface = {
                        drawRect(containerSurface)
                    }
                )
                .clickable(interactionSource = null, indication = null) { }
        )
        // 着色液态指示器：Hue 混合把背景折射染成主题色（demo 同款双 drawRect）
        Box(
            Modifier
                .offset { androidx.compose.ui.unit.IntOffset((tabWidth.toPx() * pos.value).toInt(), 0) }
                .width(tabWidth)
                .fillMaxHeight()
                .padding(4.dp)
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { RoundedCornerShape(50) },
                    effects = {
                        vibrancy()
                        blur(4.dp.toPx())
                        lens(10.dp.toPx(), 12.dp.toPx())
                    },
                    onDrawSurface = {
                        drawRect(tint, blendMode = BlendMode.Hue)
                        drawRect(tint.copy(alpha = 0.75f))
                    }
                )
        )
        // 标签内容层（最后组合=绘制在最上）
        Row(Modifier.fillMaxSize()) {
            tabs.forEachIndexed { i, label ->
                val selected = i == selectedIndex
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(
                            interactionSource = null,
                            indication = null,
                            role = Role.Button
                        ) { onSelected(i) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelLarge,
                        // 两态同字重：选中态只靠着色胶囊 + onPrimary 区分。
                        // 此前 SemiBold/Medium 切换会让文字看起来忽大忽小
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        color = if (selected) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.72f)
                    )
                }
            }
        }
    }
}
