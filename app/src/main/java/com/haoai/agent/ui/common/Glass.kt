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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
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

@Composable
fun rememberAppBackdrop(wallpaper: android.graphics.Bitmap? = null): LayerBackdrop =
    rememberLayerBackdrop {
        if (wallpaper != null) {
            // 壁纸 cover-fit 铺满，给玻璃提供可折射的真实纹理（不加压层，保持通透）
            val canvasW = size.width
            val canvasH = size.height
            val scale = maxOf(canvasW / wallpaper.width, canvasH / wallpaper.height)
            val dw = wallpaper.width * scale
            val dh = wallpaper.height * scale
            drawImage(
                wallpaper.asImageBitmap(),
                dstOffset = androidx.compose.ui.unit.IntOffset(
                    ((canvasW - dw) / 2f).toInt().coerceAtMost(0),
                    ((canvasH - dh) / 2f).toInt().coerceAtMost(0)
                ),
                dstSize = androidx.compose.ui.unit.IntSize(dw.toInt(), dh.toInt())
            )
        } else {
            // 默认浅色渐变（绿调中性，呼应液态玻璃绿主色）：深色底会让玻璃上的文字难以辨认
            drawRect(Brush.verticalGradient(listOf(Color(0xFFD9E8DF), Color(0xFFEDF4EF))))
        }
        drawContent()
    }

fun Modifier.appLayer(backdrop: LayerBackdrop): Modifier = this.layerBackdrop(backdrop)

@Composable
fun GlassPanel(
    backdrop: LayerBackdrop,
    modifier: Modifier = Modifier,
    radius: Dp = 24.dp,
    surfaceAlpha: Float = 0.26f,
    tint: Color? = null,
    shape: Shape? = null,
    lensRadius: Dp = radius,
    blurRadius: Dp = radius / 3f,
    chromaticAberration: Boolean = false,
    refract: Boolean? = null,
    content: @Composable () -> Unit
) {
    val r = refract ?: LocalGlassRefract.current
    val panelModifier = if (r) {
        modifier
            .drawBackdrop(
                backdrop = backdrop,
                shape = { shape ?: RoundedCornerShape(radius) },
                effects = {
                    vibrancy()
                    blur(blurRadius.toPx())
                    // lens 折射按统一内边距从每条边向内采样，在方角处会产生弧形高光"伪圆角"。
                    // 方角玻璃（如侧栏左缘）传 lensRadius = 0.dp 关闭它，保证角部利落。
                    if (lensRadius > 0.dp) {
                        lens(lensRadius.toPx() * 0.9f, lensRadius.toPx() * 2f, chromaticAberration = chromaticAberration)
                    }
                },
                onDrawSurface = {
                    drawRect(Color.White.copy(alpha = surfaceAlpha))
                    if (tint != null) {
                        drawRect(tint, blendMode = BlendMode.Hue)
                        drawRect(tint.copy(alpha = 0.35f))
                    }
                }
            )
    } else {
        // 位于玻璃采样层内时禁止 drawBackdrop（否则渲染自引用递归崩溃），退化为本地磨砂绘制
        modifier
            .clip(shape ?: RoundedCornerShape(radius))
            .background(Color.White.copy(alpha = surfaceAlpha.coerceAtLeast(0.28f)))
            .border(1.5.dp, Color.White.copy(alpha = 0.45f), shape ?: RoundedCornerShape(radius))
            .then(
                if (tint != null) Modifier.background(tint.copy(alpha = 0.15f)) else Modifier
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
                blur(3.dp.toPx())
                lens(10.dp.toPx(), 20.dp.toPx())
            },
            layerBlock = {
                val progress = highlight.pressProgress
                val scale = lerp(1f, 1f + 1.5.dp.toPx() / size.height, progress)

                val maxOffset = size.minDimension
                val initialDerivative = 0.05f
                val off = highlight.offset
                translationX = maxOffset * tanh(initialDerivative * off.x / maxOffset)
                translationY = maxOffset * tanh(initialDerivative * off.y / maxOffset)

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
            .background(surfaceColor ?: Color.White.copy(alpha = 0.25f))
            .border(1.5.dp, Color.White.copy(alpha = 0.45f), shape)
    }

    Box(
        modifier
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
    surfaceAlpha: Float = 0.26f,
    tint: Color? = null,
    lensRadius: Dp = 18.dp,
    refract: Boolean? = null,
    contentAlignment: Alignment = Alignment.TopStart,
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
                blur(4.dp.toPx())
                if (lensRadius > 0.dp) {
                    lens(lensRadius.toPx() * 0.9f, lensRadius.toPx() * 2f)
                }
            },
            layerBlock = {
                val off = highlight.offset
                val maxOffset = size.minDimension
                val initialDerivative = 0.04f
                translationX = maxOffset * tanh(initialDerivative * off.x / maxOffset)
                translationY = maxOffset * tanh(initialDerivative * off.y / maxOffset)
            },
            onDrawSurface = {
                drawRect(Color.White.copy(alpha = surfaceAlpha))
                if (tint != null) {
                    drawRect(tint, blendMode = BlendMode.Hue)
                    drawRect(tint.copy(alpha = 0.32f))
                }
            }
        )
    } else {
        Modifier
            .clip(shape)
            .background(Color.White.copy(alpha = surfaceAlpha.coerceAtLeast(0.28f)))
            .border(1.5.dp, Color.White.copy(alpha = 0.45f), shape)
            .then(if (tint != null) Modifier.background(tint.copy(alpha = 0.15f)) else Modifier)
    }

    Box(
        modifier
            .graphicsLayer {
                val p = highlight.pressProgress
                val s = lerp(1f, 1f - 0.015f, p)
                scaleX = s
                scaleY = s
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

    // 轨道：关=雾白磨砂，开=主题色浸染
    drawRect(lerp(Color.White.copy(alpha = 0.18f), accent.copy(alpha = 0.72f), fraction))
    drawRoundRect(
        color = Color.White.copy(alpha = lerp(0.38f, 0.14f, fraction)),
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

/** 子页面通用玻璃页头：返回键 + 标题 + 可选动作区，悬浮在内容层之上的液态玻璃条。 */
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
        radius = 24.dp,
        surfaceAlpha = 0.14f,
        refract = refract
    ) {
        Row(
            Modifier
                .fillMaxWidth()
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
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    // 系统返回手势先关弹窗：后注册的 handler 优先，覆盖屏幕级的返回导航
    androidx.activity.compose.BackHandler(onBack = onDismiss)
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
                        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (dismissLabel != null) {
                            TextButton(onClick = onDismiss) { Text(dismissLabel) }
                        }
                        if (onConfirm != null) {
                            LiquidGlassButton(
                                onClick = onConfirm,
                                backdrop = backdrop,
                                shape = RoundedCornerShape(percent = 50),
                                enabled = confirmEnabled,
                                surfaceColor = when {
                                    !confirmEnabled -> MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
                                    danger -> MaterialTheme.colorScheme.error.copy(alpha = 0.85f)
                                    // 半透明着色让振动/透镜折射透出来，保持液态玻璃质感
                                    else -> MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
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

/** 玻璃弹层里的输入框配色（半透明白容器，与 Onboarding 一致）。 */
@Composable
fun glassFieldColors() = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
    focusedTextColor = MaterialTheme.colorScheme.onBackground,
    unfocusedTextColor = MaterialTheme.colorScheme.onBackground,
    cursorColor = MaterialTheme.colorScheme.primary,
    focusedBorderColor = MaterialTheme.colorScheme.primary,
    unfocusedBorderColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.35f),
    focusedContainerColor = Color.White.copy(alpha = 0.32f),
    unfocusedContainerColor = Color.White.copy(alpha = 0.20f),
    focusedLabelColor = MaterialTheme.colorScheme.primary,
    unfocusedLabelColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
)
