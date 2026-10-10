@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.ui.text.ExperimentalTextApi::class
)

package com.haoai.agent.ui.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowRight
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.haoai.agent.agent.engine.ToolRunState
import com.haoai.agent.ui.UiTool
import com.haoai.agent.ui.ChainStep
import com.haoai.agent.ui.common.Favicon
import com.haoai.agent.ui.common.FaviconRow
import com.haoai.agent.ui.common.domainFromUrl
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight as FwSpan
import androidx.compose.ui.text.withStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * 思维链时间线卡（参考 RikkaHub ChainOfThought，2026-09-14 重构定稿）：
 * 思考步骤 + 工具步骤合成一张玻璃卡，左侧竖线时间轴，图标压线；
 * 正文永远在卡外独立气泡（取舍①）。
 *
 * 折叠三规则（对齐定稿规格图 chat-render-target-spec）：
 * 1. 思考步骤三态：live → Preview（88dp 渐隐预览、自动滚尾）；正文出现/完成
 *    → 收起为「深度思考 X.X 秒」行，点击展开全文；
 * 2. 步骤数 > 2 → 顶部控制条，折叠态只渲染最后 [COLLAPSED_VISIBLE] 步；
 * 3. finished（回合结束/历史消息）→ 整卡默认收成一行控制条
 *    「显示 N 个步骤 · 已思考 X.X 秒」，点击展开全链。
 *
 * 工具详情走行尾点击 → ModalBottomSheet（取舍②），不再行内展开。
 * 注意：本组件在 appLayer 采样子树内渲染，禁用 GlassPanel/drawBackdrop
 * （层采样自引用崩溃），磨砂质感用高透 Surface 同正文气泡手法。
 */

/** 折叠态保留的尾部步骤数（RikkaHub 同款阈值） */
private const val COLLAPSED_VISIBLE = 2

/**
 * 打开工具详情弹层的回调（ToolStep 点击 → ChatScreen 顶层在 appLayer 外渲染
 * ToolDetailSheet + GlassPanel 真玻璃）。独立 Popup/Dialog 窗口采样不到 appLayer
 * backdrop（实测弹层内为均匀死灰），必须在主窗口组合树内、appLayer 外渲染才出玻璃。
 */
val LocalOpenToolSheet = androidx.compose.runtime.staticCompositionLocalOf<(UiTool) -> Unit> { {} }

/**
 * 批2a：产物卡 [在浏览器打开] 的回调。ChatScreen 顶层提供——BrowserController.
 * navigate(file://…) 自带 uiOpener（没开界面时自动弹内置浏览器预览面板），
 * 无需经 ChainCard 参数层层下传。
 */
val LocalOpenHtmlFile = androidx.compose.runtime.staticCompositionLocalOf<(String) -> Unit> { {} }

/**
 * 链卡行按压反馈（替代裸 ripple，2026-09-15 v4 定稿）：
 * 默认 ripple 8dp 圆角与链卡 18dp 不匹配；v1-v3 给行自设圆角都错——
 * 两套几何必打架。定稿（规格图 chain-press-target）：**行通栏**（卡片不留
 * 水平/垂直 padding，内缩移到行内容上），高亮形状完全由链卡的 clip(18dp)
 * 裁切成型——首行自动圆上两角、末行自动圆下两角、中间行为直角带、
 * 单行卡=完整胶囊。观感="胶囊本身变暗"，轮廓与卡片逐像素重合。
 */
@Composable
private fun Modifier.chainPressable(enabled: Boolean = true, onClick: () -> Unit): Modifier {
    val ips = remember { MutableInteractionSource() }
    // 【轻点也要有反馈】原先直接用 collectIsPressedAsState：它是按帧合批的，快速轻点的
    // down/up 落在同一帧内时 pressed 从未渲染为 true → 观感"点一下没反应，按久才有"
    // （2026-09-19 用户实测反馈）。统一走 Glass 的 rememberPressFeedback 补一个短闪。
    val pressFb = com.haoai.agent.ui.common.rememberPressFeedback(ips)
    return this
        .background(
            if (pressFb.pressed && enabled) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)
            else Color.Transparent
        )
        .clickable(
            interactionSource = ips,
            indication = null,
            enabled = enabled,
            onClick = pressFb.wrap(onClick)
        )
}

@Composable
fun ChainCard(
    /** 合并后的步骤链：Think 段与 Tool 步按到达顺序混排（B 方案，见 ChatViewModel.rebuildRows）。 */
    steps: List<ChainStep>,
    /** 思考是否仍在流（true → 末段 Preview 态渐隐下滚） */
    reasoningLive: Boolean,
    /** 工具行是否处于活动会话流式期（历史 = false：不转圈、不 shimmer） */
    toolsLive: Boolean,
    /** 回合是否结束（历史/结束态 → 整卡默认折叠为控制条 + 尾部毛边） */
    finished: Boolean,
    modifier: Modifier = Modifier
) {
    if (steps.isEmpty()) return
    val hasReasoning = steps.any { it is ChainStep.Think }
    // 整卡展开态（用户点控制条切换）。rememberSaveable key 带步骤数签名。
    // 对齐 rikkahub：默认收起（含流式期），折叠态只渲染尾部 COLLAPSED_VISIBLE 步 + 渐隐毛边，
    // 卡片高度稳定（控制条 + 尾部行）。想看全部点控制条展开。
    var chainOpen by rememberSaveable(finished, steps.size, hasReasoning) {
        mutableStateOf(false)
    }
    // 各思考段独立展开态（收起行点击展开全文；live 段也可点开阅读区）。
    // key=段在链中的下标：合并后一段链里可能有多段思考，各自独立开关。
    // 不能以 steps.size 为键——新段/新工具步到达会重建 map，把用户手动展开的
    // 阅读区无声收起（v8 定稿：手动打开要钉住，直到用户自己收起）。
    val reasoningOpen = remember(finished) {
        androidx.compose.runtime.mutableStateMapOf<Int, Boolean>()
    }

    val totalSteps = steps.size
    // 思考段序号（1 基，按到达顺序）：完成态摘要「深度思考 N」用
    val segNos = HashMap<Int, Int>()
    run { var n = 0; steps.forEachIndexed { i, s -> if (s is ChainStep.Think) segNos[i] = ++n } }
    val canCollapse = totalSteps > COLLAPSED_VISIBLE
    // 折叠态只显示尾部 COLLAPSED_VISIBLE 步（不 takeLast 删项——被删项会瞬间移除无过渡；
    // 改为始终组合全部、折叠掉的前段用 AnimatedVisibility 收起，见 StepToggle）。
    val firstShownIndex = if (!canCollapse || chainOpen) 0 else (totalSteps - COLLAPSED_VISIBLE)
    // 纯思考卡（无任何工具步）→ 收起时宽度自适应内容（rikkahub collapsedAdaptiveWidth 同款）
    val thinkingOnly = steps.none { it is ChainStep.Tool }

    // 纯思考卡（无任何工具步）且处于收起态 → 宽度自适应内容（rikkahub collapsedAdaptiveWidth 同款）；
    // 含工具步的卡时间轴要左对齐、必须撑满，不收窄。
    val slim = thinkingOnly && !chainOpen
    Column(
        modifier
            .then(if (slim) Modifier.wrapContentWidth() else Modifier.fillMaxWidth())
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = chatCardAlpha()))
            .border(
                1.dp,
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                RoundedCornerShape(18.dp)
            )
            .animateContentSize(
                animationSpec = tween(280, easing = androidx.compose.animation.core.CubicBezierEasing(0.4f, 0f, 0.2f, 1f))
            )
    ) {
        // ── 控制条：仅当步骤数超过阈值才出现（≤2 步直接显示，不折叠，rikkahub 同款）──
        if (canCollapse) {
            ChainControlRow(
                text = if (chainOpen) "收起" else buildString {
                    append("显示 $totalSteps 个步骤")
                },
                opened = chainOpen,
                onClick = { chainOpen = !chainOpen }
            )
        }
        val lineColor = MaterialTheme.colorScheme.onSurfaceVariant
        Box(
            Modifier.drawBehind {
                // 行通栏后内容自缩 12dp：竖线 x = 12(行内缩)+12(图标半宽)=24dp
                val x = 24.dp.toPx()
                val top = 18.dp.toPx()
                val bottom = size.height - 18.dp.toPx()
                // v2 导轨（效果图定稿 2026-10-06）：1dp 硬线 → 2dp 圆头 + 两端渐变淡出，
                // 线像从首枚图标生长、到末枚收回，不再是被剪断的实线
                drawLine(
                    brush = Brush.verticalGradient(
                        0f to lineColor.copy(alpha = 0f),
                        0.14f to lineColor.copy(alpha = 0.30f),
                        0.86f to lineColor.copy(alpha = 0.30f),
                        1f to lineColor.copy(alpha = 0f),
                        startY = top,
                        endY = bottom
                    ),
                    start = Offset(x, top),
                    end = Offset(x, bottom),
                    strokeWidth = 2.dp.toPx(),
                    cap = androidx.compose.ui.graphics.StrokeCap.Round
                )
            }
        ) {
            Column {
                steps.forEachIndexed { index, step ->
                    // m2：Think 步优先用上游给的稳定 id（历史=消息 id，直播=固定键），
                    // index 兜底只是防御 —— 原来 "reason_$index" 在新段插入/链卡合并时
                    // 会让后续步骤 index 整体后移，remember 展开状态全部失效重建。
                    val stepKey: Any = when (step) {
                        is ChainStep.Think -> step.id ?: "reason_$index"
                        is ChainStep.Tool -> step.tool.callId
                    }
                    StepToggle(
                        visible = index >= firstShownIndex,
                        // 新步骤入场只在流式期播（历史/静态渲染直出）
                        animateIn = (reasoningLive || toolsLive) && !finished,
                        stepKey = stepKey
                    ) {
                        when (step) {
                            is ChainStep.Think -> ReasoningStep(
                                text = step.text,
                                thinkingMs = step.ms,
                                segNo = segNos[index] ?: (index + 1),
                                live = reasoningLive && index == totalSteps - 1,
                                expanded = reasoningOpen[index] == true,
                                onToggle = { reasoningOpen[index] = !(reasoningOpen[index] == true) }
                            )
                            is ChainStep.Tool -> Column {
                                ToolStep(tool = step.tool, live = toolsLive)
// 批1e：编码向步骤行下长出结果正文卡（exit/耗时 + 可折叠 stdout）
// 显隐跟随该步自身的可见性（index >= firstShownIndex），不能只判 chainOpen：
// 步骤数 ≤ COLLAPSED_VISIBLE 时卡根本不提供展开入口（canCollapse=false），
// chainOpen 恒为 false，只看它会把正文卡一起藏掉。
// write/edit 例外：正文只有"已替换 N 处"一行，变更卡信息量全包含它，两张并排是噪音。
                                if (step.tool.body != null && step.tool.diff == null &&
                                    index >= firstShownIndex
                                ) {
                                    ToolBodyCard(step.tool)
                                }
                                // 批1f：write/edit 步骤行下长出变更卡（+N−M + 带行号的可折叠 diff）
                                if (step.tool.diff != null && index >= firstShownIndex) {
                                    ToolDiffCard(step.tool.diff)
                                }
                                // 批2a：HTML 产物卡（[预览]/[在浏览器打开] 双按钮）。
                                // 与变更卡并存不重复：变更卡回答"改了哪几行"，
                                // 产物卡回答"东西在哪、怎么看"。
                                if (step.tool.htmlFile != null && index >= firstShownIndex) {
                                    HtmlFileCard(step.tool.htmlFile)
                                }
                                // 搜索步：行下 favicon 叠排 + 结果数（对齐 rikkahub FaviconRow）
                                if (step.tool.hits.isNotEmpty()) {
                                    Row(
                                        Modifier.padding(start = 44.dp, bottom = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        FaviconRow(
                                            domains = step.tool.hits.map { it.domain },
                                            size = 18.dp
                                        )
                                        Text(
                                            "共 ${step.tool.hits.size} 条结果",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            // 收起态"毛边"：尾部渐隐遮罩（与思考 Preview 同一套渐隐语言），盖住最后一步下沿，
            // 提示"下面还有内容，点控制条展开"。展开态不画。
            if (canCollapse && !chainOpen) {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(30.dp)
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    Color.Transparent,
                                    MaterialTheme.colorScheme.surface.copy(alpha = chatCardAlpha())
                                )
                            )
                        )
                )
            }
        }
    }
}

/**
 * 单个步骤的显隐包装：**入场与收场都有过渡**。
 *
 * 为什么需要它：
 *  - 链卡内是普通 Column（非 LazyColumn），既没有 animateItem，框架也不给"新插入的项"补动画；
 *  - 折叠若直接 takeLast 删项，被删的项会瞬间从组合里移除，没有任何过渡——
 *    用户实测反馈"思考一结束卡片唰地变成显示 3 个步骤，没有过渡动画"。
 * 做法：始终组合全部步骤，只用 AnimatedVisibility 控可见性：
 * 首帧以不可见组合（animateIn）→ 下一帧翻真 → 播 enter；折叠时翻假 → 播 exit。
 * animateIn=false（历史/静态渲染）直出，不播入场。
 */
@Composable
private fun StepToggle(
    visible: Boolean,
    animateIn: Boolean,
    stepKey: Any,
    content: @Composable () -> Unit
) {
    var appeared by remember(stepKey) { mutableStateOf(!animateIn) }
    // m3（2026-10-10）：animateIn 加进 key。原来它只是启动快照——同一步骤从
    // 历史态转直播态（会话恢复运行）时值翻转但 effect 不重跑。当前动画结构下
    // 这个翻转不会产生可感知差异（appeared 已为 true，无法"重播"入场而不先播
    // 退场），加进 key 是消除"捕获过期快照"隐患的最小正确写法；行为暂无变化。
    LaunchedEffect(stepKey, animateIn) { if (animateIn) appeared = true }
    val ease = androidx.compose.animation.core.CubicBezierEasing(0.4f, 0f, 0.2f, 1f)
    AnimatedVisibility(
        visible = visible && appeared,
        enter = expandVertically(animationSpec = tween(220, easing = ease), expandFrom = Alignment.Top) +
            fadeIn(tween(160)),
        exit = shrinkVertically(animationSpec = tween(180, easing = ease), shrinkTowards = Alignment.Top) +
            fadeOut(tween(140))
    ) {
        content()
    }
}

/** 链卡顶/底控制条：箭头 + 主色文案，全行可点 */
@Composable
private fun ChainControlRow(text: String, opened: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .chainPressable(onClick = onClick)
            // 高亮通栏（形状由卡片 clip 成型），内容自缩 12dp
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) {
            Icon(
                if (opened) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp)
            )
        }
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}

/** 步骤图标底座（遮住时间轴竖线） */
@Composable
private fun StepIconBox(content: @Composable () -> Unit) {
    Box(
        Modifier
            .width(24.dp)
            .height(20.dp),
        contentAlignment = Alignment.Center
    ) {
        // 底色与卡面一致 → 视觉上"图标压断竖线"
        Box(
            Modifier
                .size(20.dp)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = chatCardAlpha()), CircleShape)
        )
        Box(Modifier.size(15.dp), contentAlignment = Alignment.Center) { content() }
    }
}

/** 三点跳动（RikkaHub DotLoading 同款） */
@Composable
private fun DotLoading() {
    val t by com.haoai.agent.ui.common.rememberPulse(0f, 1f, 1200)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        for (i in 0..2) {
            val phase = (t + i * 0.18f) % 1f
            val alpha = if (phase in 0.3f..0.7f) 1f else 0.3f
            Box(
                Modifier
                    .size(4.dp)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = alpha), CircleShape)
            )
        }
    }
}

/** label 流光（shimmer）：渐变高光横扫，运行中步骤标题用 */
@Composable
private fun shimmerBrush(active: Boolean): Brush? {
    if (!active) return null
    val s by com.haoai.agent.ui.common.rememberPulse(0f, 1f, 1700)
    return Brush.linearGradient(
        colors = listOf(
            MaterialTheme.colorScheme.onSurfaceVariant,
            MaterialTheme.colorScheme.primary,
            MaterialTheme.colorScheme.onSurfaceVariant
        ),
        start = Offset((s * 2f - 0.5f) * 160f, 0f),
        end = Offset((s * 2f + 0.5f) * 160f, 0f)
    )
}

/** 思考段中文序号（效果图定稿「深度思考 一/二/三…」，超过十回退阿拉伯数字） */
private fun segCn(n: Int): String = when (n) {
    1 -> "一"; 2 -> "二"; 3 -> "三"; 4 -> "四"; 5 -> "五"
    6 -> "六"; 7 -> "七"; 8 -> "八"; 9 -> "九"; 10 -> "十"
    else -> n.toString()
}

/** 思考步骤（方案 v8 效果图定稿，2026-10-06）：
 *  live=行内单行 ticker（行高恒定零跳动；点行展开下方阅读区，展开时 ticker 让位、
 *  行尾靠右「▴ 收起」与阅读区右缘对齐）；完成=同一行原位变摘要
 *  「深度思考 N · X.X秒 · N字」，点击展开全文。手动展开会钉住：新段/新步骤到达不收，
 *  直到用户自己收起。 */
@Composable
private fun ReasoningStep(
    text: String,
    thinkingMs: Long?,
    segNo: Int,
    live: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    val surface = MaterialTheme.colorScheme.surface.copy(alpha = chatCardAlpha())
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .chainPressable(onClick = onToggle)
                // 高亮通栏，内容自缩 12dp（图标中心落 24dp，与竖线对齐）
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StepIconBox {
                Icon(
                    Icons.Filled.Lightbulb,
                    contentDescription = null,
                    tint = if (live) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(15.dp)
                )
            }
            if (live && !expanded) {
                Text(
                    "深度思考中",
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontWeight = FontWeight.SemiBold,
                        brush = shimmerBrush(true)
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                // 行内单行 ticker：窗口宽度恒定，文本随流右移露出最新尾；右缘渐隐收口
                val hScroll = rememberScrollState()
                LaunchedEffect(text) { hScroll.scrollTo(hScroll.maxValue) }
                Box(Modifier.weight(1f)) {
                    Text(
                        text.replace('\n', ' ').trim(),
                        fontSize = 11.5.sp,
                        maxLines = 1,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                        modifier = Modifier.horizontalScroll(hScroll)
                    )
                    Box(
                        Modifier
                            .align(Alignment.CenterEnd)
                            .width(12.dp)
                            .height(16.dp)
                            .background(
                                Brush.horizontalGradient(listOf(Color.Transparent, surface))
                            )
                    )
                }
            } else if (live) {
                Text(
                    "深度思考中",
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontWeight = FontWeight.SemiBold,
                        brush = shimmerBrush(true)
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                // v8 定稿：收起提示靠右（与阅读区右缘对齐），不贴着标签
                Text(
                    "▴ 收起",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            } else {
                Text(
                    buildAnnotatedString {
                        append("深度思考 ${segCn(segNo)}")
                        thinkingMs?.let {
                            append(" · ")
                            withStyle(
                                SpanStyle(
                                    fontSize = 10.5.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                            ) {
                                append(
                                    String.format(
                                        Locale.US, "%.1f秒 · %d字",
                                        it / 1000.0, text.length
                                    )
                                )
                            }
                        }
                    },
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontWeight = FontWeight.SemiBold,
                        brush = SolidColor(MaterialTheme.colorScheme.onSurfaceVariant)
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "收起思考过程" else "展开思考过程",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
        // 阅读区：live 展开=跟尾滚动（用户上滚即让位细读），frozen 展开=自由回看全文
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(tween(220)) + fadeIn(tween(220)),
            exit = shrinkVertically(tween(180)) + fadeOut(tween(180))
        ) {
            val scroll = rememberScrollState()
            // 边界钳制（真机反馈 2026-10-07）：读到顶/底后继续拖，剩余滚动就地吃掉，
            // 不再透传给外层会话流（内容本身不可滚时放行，短文本上拖动仍滚对话）
            val clamp = remember(scroll) {
                object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
                    override fun onPostScroll(
                        consumed: Offset,
                        available: Offset,
                        source: androidx.compose.ui.input.nestedscroll.NestedScrollSource
                    ): Offset = if (scroll.maxValue > 0) available else Offset.Zero
                }
            }
            // 跟尾滞回（真机反馈 2026-10-07，与聊天流式同一套思路）：
            // 手指按下拖动=暂停跟随（上滚细读）；停止滚动且贴着底（80px 内）=恢复跟随，
            // 之后新思考内容到达自动滚到底——拖到中间则保持暂停，不被拽走
            var follow by remember { mutableStateOf(true) }
            LaunchedEffect(scroll) {
                launch {
                    scroll.interactionSource.interactions.collect { i ->
                        if (i is DragInteraction.Start) follow = false
                    }
                }
                snapshotFlow { !scroll.isScrollInProgress to scroll.value }.collect { (idle, v) ->
                    if (idle && v >= scroll.maxValue - 80) follow = true
                }
            }
            LaunchedEffect(text) {
                if (live && follow) scroll.scrollTo(scroll.maxValue)
            }
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                lineHeight = 17.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.92f),
                modifier = Modifier
                    .padding(start = 44.dp, end = 12.dp, bottom = 8.dp)
                    .fillMaxWidth()
                    .heightIn(max = 240.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
                    .nestedScroll(clamp)
                    .verticalScroll(scroll)
                    .padding(horizontal = 11.dp, vertical = 9.dp)
            )
        }
    }
}

/** 工具步骤：图标 + 动词对象 label + 状态 extra；点击 → 底部弹层详情 */
@Composable
private fun ToolStep(
    tool: UiTool,
    live: Boolean
) {
    val running = tool.state == ToolRunState.RUNNING && live
    val isError = tool.state == ToolRunState.ERROR
    val denied = tool.state == ToolRunState.DENIED
    // ask_user 已答步骤：渲染成"问题+所选答案"卡（AskUserTool 结果前缀解析，见 ChatViewModel.askDataOf）
    if (tool.name == "ask_user" && tool.ask != null) {
        AskStepCard(tool.ask, isError)
        return
    }
    // ask_user_batch 题组步骤：渲染成"标题+逐题作答"批量卡（见 ChatViewModel.askDataOfBatch）
    if (tool.name == "ask_user_batch" && tool.askBatch != null) {
        BatchAskStepCard(tool.askBatch, isError)
        return
    }
    val verb = tool.brief.ifBlank { tool.name }.substringBefore('·').trim()
    val obj = tool.brief.substringAfter('·', "").trim()
    // 抓取步（web_fetch）：obj 是裸 URL → 渲染成「加粗域名 + 灰路径」，比整串 URL 好读
    val isFetch = tool.name.contains("fetch") && obj.startsWith("http")
    // CompositionLocal 读取须在组合期（onClick 是普通 lambda，不能现场 .current）
    val openTool = LocalOpenToolSheet.current
    val ctx = LocalContext.current
    fun openUrl() {
        runCatching {
            ctx.startActivity(
                android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(obj))
            )
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            // 整行点击=详情弹层（2026-10-07 用户回访：整行直达网页太激进，恢复原交互）；
            // 快捷跳转收进域名文本本身（见下方 isFetch 分支）与弹层「打开网页」按钮
            .chainPressable(enabled = !running) { openTool(tool) }
            // 高亮通栏，内容自缩 12dp
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        StepIconBox {
            // 运行 ↔ 完成 的图标交叉淡入（此前是瞬时替换：三点"啪"地变成 ✓）
            AnimatedContent(
                targetState = when {
                    running -> 0
                    isError || denied -> 1
                    else -> 2
                },
                transitionSpec = { fadeIn(tween(120)) togetherWith fadeOut(tween(120)) },
                label = "stepIcon"
            ) { st ->
                when (st) {
                    0 -> DotLoading()
                    1 -> Icon(
                        Icons.Filled.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(15.dp)
                    )
                    else -> Icon(
                        toolIcon(tool.name),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                        modifier = Modifier.size(15.dp)
                    )
                }
            }
        }
        // 左侧时间轴已是地球图标，省掉"抓取网页 ·"前缀。其余工具维持"动词 对象"。
        val label: AnnotatedString = if (isFetch) {
            val dom = domainFromUrl(obj)
            val rest = obj.substringAfter("://", obj).removePrefix("www.").removePrefix(dom)
            buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FwSpan.SemiBold)) { append(dom) }
                withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))) {
                    append(rest)
                }
            }
        } else {
            AnnotatedString(verb + if (obj.isNotBlank()) " $obj" else "")
        }
        val labelStyle = MaterialTheme.typography.labelLarge.copy(
            fontWeight = FontWeight.SemiBold,
            brush = shimmerBrush(running)
                ?: SolidColor(MaterialTheme.colorScheme.onSurfaceVariant)
        )
        if (isFetch) {
            // 效果图定稿：抓取步行内 14dp 圆角小 favicon + 域名（时间轴地球图标保留表意）；
            // 域名文本本身可点直达网页（2026-10-07 定稿：点域名=跳转，点行其他=详情）
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Favicon(domain = domainFromUrl(obj), size = 14.dp, circle = false)
                Spacer(Modifier.size(6.dp))
                Text(
                    label, style = labelStyle,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(enabled = tool.state == ToolRunState.DONE) { openUrl() }
                )
            }
        } else if (tool.fileRef != null && obj.isNotBlank()) {
            // 批2c 路径芯片（效果图定稿 .chip）：动词留文本，对象（文件名）做成
            // 绿底等宽芯片 + ↗；点芯片 = 只读查看器，点行其余 = 详情弹层（行点击不吞，
            // 与抓取步"域名文本可点"同交互分型）。
            val ref = tool.fileRef
            var viewing by rememberSaveable(ref.absPath) { mutableStateOf(false) }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = verb, style = labelStyle,
                    maxLines = 1,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(Modifier.size(7.dp))
                Text(
                    text = "$obj ↗",
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .clip(RoundedCornerShape(7.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                        .clickable { viewing = true }
                        .padding(horizontal = 7.dp, vertical = 2.dp)
                )
            }
            if (viewing) {
                FileViewerDialog(ref = ref, onDismiss = { viewing = false })
            }
        } else if (tool.jobLog != null) {
            // 批3c：bash 后台投递成功（结果文本带 job_xxx）→ 「日志」芯片。
            // 与路径芯片同交互分型：点芯片=实时尾随查看器，点行其余=详情弹层。
            val log = tool.jobLog
            var showLog by rememberSaveable(log.absPath) { mutableStateOf(false) }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = verb, style = labelStyle,
                    maxLines = 1,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(Modifier.size(7.dp))
                Text(
                    text = "日志 ↗",
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    modifier = Modifier
                        .clip(RoundedCornerShape(7.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                        .clickable { showLog = true }
                        .padding(horizontal = 7.dp, vertical = 2.dp)
                )
            }
            if (showLog) {
                JobLogViewerDialog(ref = log, onDismiss = { showLog = false })
            }
        } else {
            Text(
                label, style = labelStyle,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
        // 子代理进度并入状态列（嵌套链走弹层）
        val subRunning = tool.subagents.count { it.state == "RUNNING" }
        if (tool.subagents.isNotEmpty()) {
            Text(
                "子代理 ${tool.subagents.size - subRunning}/${tool.subagents.size}",
                style = MaterialTheme.typography.labelSmall,
                color = if (subRunning > 0) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
        // 批1f：write/edit 行尾常驻变更量。此前要点头行进详情弹层、再点「查看变更」才看得到，
        // 而"这次到底改了哪几行"恰恰是协作时最常问的一句话——提到行首标签右侧，一眼可见。
        val diff = tool.diff
        if (diff != null) {
            if (diff.isNewFile) {
                Text(
                    "新建 ${diff.added} 行",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FwSpan.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
            } else {
                Text(
                    "+${diff.added} −${diff.removed}",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FwSpan.SemiBold,
                    color = if (diff.added > 0 || diff.removed > 0) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        }
        // 状态列：运行（无文字）↔ 完成/失败/拒绝 交叉淡入（此前是"啪"地冒出 ✓）
        AnimatedContent(
            targetState = when {
                running -> "run"
                isError -> "err"
                denied -> "deny"
                else -> "ok"
            },
            transitionSpec = { fadeIn(tween(120)) togetherWith fadeOut(tween(120)) },
            label = "stepState"
        ) { st ->
            if (st == "run") {
                Box(Modifier)
            } else {
                Text(
                    when (st) {
                        "err" -> "✕ 失败"
                        "deny" -> "已拒绝"
                        else -> "✓"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (st == "err" || st == "deny") MaterialTheme.colorScheme.error
                    else Color(0xFF3FAE5C).copy(alpha = 0.85f)
                )
            }
        }
        if (!running) {
            // 截图类工具（browser_screenshot / vscreen_*）：行尾换成缩略图，
            // 点缩略图或点整行 → 详情弹层看大图（方案 A，2026-09-15）
            val shot = tool.imageData
            if (shot != null) {
                ShotThumb(shot)
            } else {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowRight,
                    contentDescription = "查看工具详情",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}

/**
 * 批1e：工具结果正文卡（编码向六件套 + job_output）。
 *
 * 存在的理由是「一句话简报答不了协作的问题」：构建成功还是失败、读到了哪几行、
 * grep 命中了什么，此前在聊天流里一个字都看不到（引擎只送首行 160 字的 preview）。
 * 卡挂在步骤行下方、缩进与 label 对齐（44dp），形态对齐效果图 `.term`：
 * 头部 = 退出码 + 耗时 + 折叠钮；正文 = 等宽软换行，行按语义分色。
 *
 * 折叠策略：短输出直接展开（结果短到一眼看完，折叠反而是多余的点击）；
 * 超过 [AUTO_EXPAND_MAX_LINES] 行才默认收起，用户点头部「展开」再开。
 */
@Composable
private fun ToolBodyCard(tool: UiTool) {
    val body = tool.body?.takeIf { it.isNotBlank() } ?: return
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val bg = if (dark) Color(0xFF06080D).copy(alpha = 0.88f) else Color(0xFFF7F8FA)
    val fg = if (dark) Color(0xFFD7DEE9) else Color(0xFF23272F)
    val border = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)

    // bash 结果头是 "exit=N\n---\n<正文>"（BashTool.kt:164）；拆出退出码，
    // 正文里那个头部行就不重复显示了——头部已经用退出码徽标表达过。
    val parsed = remember(body, tool.name) { parseToolBody(tool.name, body) }
    val lines = remember(parsed.text) { parsed.text.lines() }
    val isError = tool.state == ToolRunState.ERROR || (parsed.exitCode ?: 0) != 0

    var expanded by rememberSaveable(tool.callId) {
        mutableStateOf(lines.size <= AUTO_EXPAND_MAX_LINES)
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 44.dp, end = 12.dp, top = 2.dp, bottom = 6.dp),
        color = Color.Transparent,
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, border)
    ) {
        Column {
            // 头部：点整行折叠/展开（不用小字按钮——手机上目标太小）
            Row(
                Modifier
                    .fillMaxWidth()
                    .chainPressable { expanded = !expanded }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val code = parsed.exitCode
                if (code != null) {
                    Text(
                        (if (code == 0) "✓ exit $code" else "✗ exit $code"),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = if (isError) MaterialTheme.colorScheme.error
                        else Color(0xFF1E9E4A)
                    )
                } else {
                    Text(
                        "结果",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
                if (tool.elapsedMs > 0) {
                    Text(
                        formatElapsed(tool.elapsedMs),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    if (expanded) "收起" else "展开 ${lines.size} 行",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            if (expanded) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = BODY_MAX_HEIGHT)
                        .background(bg)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    Column {
                        lines.forEach { line ->
                            Text(
                                text = line.ifEmpty { " " },
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    lineHeight = 17.sp
                                ),
                                color = toneColorOf(line, fg, dark),
                                softWrap = true
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 默认展开的行数上限：短结果直接摊开，长结果收起免得把对话冲垮。 */
private const val AUTO_EXPAND_MAX_LINES = 12

/** 展开态正文最大高度（超出内部滚动）——不让一条长输出占满整屏。 */
private val BODY_MAX_HEIGHT = 320.dp

/**
 * 批1f：write/edit 的内联变更卡（挂在步骤行下方，缩进与 ToolBodyCard 一致）。
 *
 * 与批1c 的 ```diff 围栏视图是同一套语言（行号槽 + ± 符号 + 增删底色），但数据源不同：
 * 那条路解析模型吐的 unified diff 文本，这条路拿写前快照现算（UiDiff），所以对
 * "模型压根没把 diff 吐出来"的真实改动同样有效——这正是移动端此前的状态。
 *
 * 折叠策略与结果卡一致：短 diff 直接摊开，长 diff 收起（头部常驻 +N−M 已够回答"改了多少"）。
 */
@Composable
private fun ToolDiffCard(diff: com.haoai.agent.ui.UiDiff) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val border = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)
    val addBg = if (dark) Color(0xFF16301F) else Color(0xFFE8F6EC)
    val delBg = if (dark) Color(0xFF3A1D1F) else Color(0xFFFDECEA)
    val addFg = if (dark) Color(0xFF8FD9A6) else Color(0xFF146C2E)
    val delFg = if (dark) Color(0xFFE79A9A) else Color(0xFFA1281F)
    val numFg = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
    val bodyFg = if (dark) Color(0xFFD7DEE9) else Color(0xFF23272F)

    // 只渲染变更行 + 少量上下文：整份文件铺开会把对话冲垮，而用户要看的是"哪几行动了"。
    // 上下文取前后各 CONTEXT_LINES 行，让改动不至于悬空看不出位置。
    val shown = remember(diff.lines) { contextOf(diff.lines) }
    var expanded by rememberSaveable(diff.path) {
        mutableStateOf(shown.size <= AUTO_EXPAND_MAX_LINES)
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 44.dp, end = 12.dp, top = 2.dp, bottom = 6.dp),
        color = Color.Transparent,
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, border)
    ) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .chainPressable { expanded = !expanded }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    if (diff.isNewFile) "新建文件 · ${diff.added} 行"
                    else "+${diff.added} −${diff.removed}",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    diff.path.substringAfterLast('/'),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(Modifier.weight(1f))
                Text(
                    if (expanded) "收起" else "展开",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            if (expanded) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = BODY_MAX_HEIGHT)
                        .verticalScroll(rememberScrollState())
                ) {
                    if (diff.truncatedHead > 0) {
                        Text(
                            "…（文件前 ${diff.truncatedHead} 行未变更）",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            color = numFg,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                        )
                    }
                    shown.forEach { row ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .background(
                                    when (row.kind) {
                                        DiffRowKind.ADDED -> addBg
                                        DiffRowKind.REMOVED -> delBg
                                        else -> Color.Transparent
                                    }
                                )
                                .height(IntrinsicSize.Min),
                            verticalAlignment = Alignment.Top
                        ) {
                            Text(
                                // 新增行在旧文件里不存在，没有行号——渲染成空白而不是 "null"
                                row.oldNo?.toString().orEmpty(),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    lineHeight = 17.sp
                                ),
                                color = numFg,
                                maxLines = 1,
                                textAlign = TextAlign.End,
                                modifier = Modifier
                                    .width(34.dp)
                                    .padding(end = 6.dp)
                            )
                            Text(
                                when (row.kind) {
                                    DiffRowKind.ADDED -> "+"
                                    DiffRowKind.REMOVED -> "−"
                                    else -> " "
                                },
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    lineHeight = 17.sp
                                ),
                                color = when (row.kind) {
                                    DiffRowKind.ADDED -> addFg
                                    DiffRowKind.REMOVED -> delFg
                                    else -> Color.Transparent
                                },
                                modifier = Modifier.width(14.dp)
                            )
                            Text(
                                row.text,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    lineHeight = 17.sp
                                ),
                                color = if (row.kind == DiffRowKind.SAME) bodyFg
                                else if (row.kind == DiffRowKind.ADDED) addFg else delFg,
                                softWrap = true,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(end = 8.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 一行变更视图的行类型。internal：contextOf() 要被 JVM 单测直接断言。 */
internal enum class DiffRowKind { SAME, ADDED, REMOVED }

/** 带行号的一行。oldNo = 旧文件行号（新增行为 null）。 */
internal data class DiffRow(val kind: DiffRowKind, val text: String, val oldNo: Int?)

/** 变更行前后各带的上下文行数——够看出"改在哪一段"，又不至于把整份文件铺出来。 */
private const val CONTEXT_LINES = 3

/**
 * 批1f：从 diff 结果里挑出「变更行 + 上下文」，并编出行号。
 * 纯函数（无 Compose 依赖），可直接单测。
 *
 * 行号口径：SAME/REMOVED 走旧文件行号，ADDED 无旧行号（旧文件里不存在）——和
 * 主流 diff 工具一致。变更块之间用一行 `⋯` 断开，避免读者以为上下是连续的。
 */
internal fun contextOf(
    lines: List<com.haoai.agent.ui.common.DiffLine>
): List<DiffRow> {
    val keep = BooleanArray(lines.size)
    lines.forEachIndexed { i, l ->
        if (l.type == com.haoai.agent.ui.common.DiffType.SAME) return@forEachIndexed
        for (j in (i - CONTEXT_LINES).coerceAtLeast(0)..(i + CONTEXT_LINES).coerceAtMost(lines.size - 1)) {
            keep[j] = true
        }
    }
    val out = ArrayList<DiffRow>()
    var oldNo = 0
    var skipping = false
    lines.forEachIndexed { i, l ->
        val kind = when (l.type) {
            com.haoai.agent.ui.common.DiffType.ADDED -> DiffRowKind.ADDED
            com.haoai.agent.ui.common.DiffType.REMOVED -> DiffRowKind.REMOVED
            else -> DiffRowKind.SAME
        }
        val isOld = kind != DiffRowKind.ADDED
        if (isOld) oldNo++
        if (!keep[i]) {
            skipping = true
            return@forEachIndexed
        }
        // 被跳过的区间只在前后都还有内容时补一条分隔标记（首尾不补，避免顶上多一行噪音）
        if (skipping && out.isNotEmpty()) {
            out.add(DiffRow(DiffRowKind.SAME, "⋯", null))
        }
        skipping = false
        out.add(DiffRow(kind, l.text, if (isOld) oldNo else null))
    }
    return out
}

// ===== 批2a：HTML 产物卡（write/edit 写出 .html → 双按钮两条出路） =====

/**
 * HTML 产物卡（效果图定稿 .art）：vibe coding 最常问的「东西写出来了，怎么看」——
 * 此前 write 出来的 .html 在聊天里只有简报 + diff，三条预览链路一条都够不着文件。
 * [预览] → 统一壳 HtmlPreviewDialog file:// 直开（就地看产物）；
 * [在浏览器打开] → 内置浏览器（交互测试，和 agent browser_* 同一视野）。
 */
@Composable
private fun HtmlFileCard(file: com.haoai.agent.ui.UiHtmlFile) {
    var preview by rememberSaveable(file.absPath) { mutableStateOf(false) }
    val openInBrowser = LocalOpenHtmlFile.current
    val fileUrl = remember(file.absPath) {
        android.net.Uri.fromFile(java.io.File(file.absPath)).toString()
    }
    val name = file.relPath.substringAfterLast('/')
    Surface(
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f),
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 44.dp, end = 12.dp, top = 2.dp, bottom = 6.dp)
    ) {
        Row(
            Modifier.padding(11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 66dp 绿渐变缩略 + </>（效果图定稿；点缩略同样开预览）
            Box(
                Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(Color(0xFF123B2A), Color(0xFF1EA84F), Color(0xFF7BD88F))
                        )
                    )
                    .clickable { preview = true },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "</>",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }
            Column(
                Modifier
                    .weight(1f)
                    .padding(start = 11.dp)
            ) {
                Text(
                    name,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    file.relPath,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
                Row(
                    Modifier.padding(top = 7.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        "预览",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(9.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                            .clickable { preview = true }
                            .padding(horizontal = 13.dp, vertical = 6.dp)
                    )
                    Text(
                        "在浏览器打开",
                        fontSize = 11.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .clip(RoundedCornerShape(9.dp))
                            .border(
                                1.dp,
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f),
                                RoundedCornerShape(9.dp)
                            )
                            .clickable { openInBrowser(fileUrl) }
                            .padding(horizontal = 13.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }
    if (preview) {
        com.haoai.agent.ui.common.HtmlPreviewDialog(
            title = name,
            source = com.haoai.agent.ui.common.HtmlPreviewSource.FilePage(fileUrl),
            onDismiss = { preview = false },
            // 预览起手、要交互测试 → 换乘内置浏览器（壳底栏的 ↗ 钮）
            onOpenInBrowser = {
                preview = false
                openInBrowser(fileUrl)
            }
        )
    }
}

/** 批1e：结果正文拆解结果。exitCode 只有 bash/job_output 这类有退出码的工具才非空。 */
internal data class ParsedToolBody(val exitCode: Int?, val text: String)

/**
 * 批1e：从落库结果文本里剥出 bash 的 `exit=N` 头。
 * BashTool 把结果拼成 `exit=N\n---\n<正文>`（BashTool.kt:164），头部行若留在正文里
 * 就是「exit 0」和卡片头部的退出码徽标重复一遍。其它工具（read/grep/glob/write/edit）
 * 原样返回——它们的正文没有这种前缀。
 */
internal fun parseToolBody(toolName: String, body: String): ParsedToolBody {
    if (toolName != "bash" && toolName != "job_output") return ParsedToolBody(null, body)
    val first = body.lineSequence().firstOrNull() ?: return ParsedToolBody(null, body)
    val head = first.trim().removePrefix("exit=")
    // 退出码后面可能跟着后端标注（BashTool.kt:152 的 note，如 "（linux 沙箱）"），
    // 所以只取开头那段数字，不能整行 toIntOrNull。
    val code = head.takeWhile { it.isDigit() }.toIntOrNull()
        ?: return ParsedToolBody(null, body)
    // 正文从分隔线之后开始；没有 `---` 行时保留原样（不猜格式，宁可多显示一行）
    val afterSep = body.substringAfter("---", "").trimStart('\n')
    return ParsedToolBody(code, afterSep.ifEmpty { body })
}

/** 毫秒 → 人类可读（对齐效果图「1.8s」；过长的构建用分钟计）。 */
internal fun formatElapsed(ms: Long): String = when {
    ms < 1000 -> "${ms}ms"
    ms < 60_000 -> String.format("%.1fs", ms / 1000.0)
    else -> "${ms / 60_000}m${(ms % 60_000) / 1000}s"
}

/**
 * 批1e：输出行语义分色。构建日志的关键信息全在这几类行上——
 * 成功标记、失败/报错、警告、进度噪声各自一个色，一屏里"成没成"不用逐行读。
 * 未命中任何规则的行走普通前景色（保持中性，不制造噪音）。
 */
private fun toneColorOf(line: String, fg: Color, dark: Boolean): Color {
    val t = line.trimStart()
    val good = if (dark) Color(0xFF6BD68C) else Color(0xFF1A7F37)
    val warn = if (dark) Color(0xFFE6C07B) else Color(0xFFA15C00)
    val bad = if (dark) Color(0xFFE06C75) else Color(0xFFB3261E)
    val dim = fg.copy(alpha = 0.55f)
    return when {
        t.startsWith("BUILD SUCCESSFUL") || t.contains("SUCCESS") -> good
        t.startsWith("BUILD FAILED") || t.startsWith("FAILURE:") -> bad
        t.startsWith("e: ") || t.startsWith("error:") || t.contains("Exception") -> bad
        t.startsWith("w: ") || t.startsWith("warning:") || t.contains("deprecated") -> warn
        // gradle/npm 这类进度行："> Task :app:xxx"、"[1/14] ..."，纯信息量噪音，压暗
        t.startsWith("> Task ") || t.startsWith("> ") || t.matches(Regex("^\\[\\d+/\\d+\\].*")) -> dim
        else -> fg
    }
}

/**
 * ask_user 已答步骤卡：问题 + 选项列表（用户选中的高亮"✓ 你的选择"）或自由输入回答。
 * 运行中（live，尚无 ask 数据）不走这里——普通步骤行 shimmer 显示"向你提问 · …"。
 */
@Composable
private fun AskStepCard(ask: com.haoai.agent.ui.UiAskData, isError: Boolean) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StepIconBox {
                Icon(
                    Icons.Filled.Lightbulb,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(15.dp)
                )
            }
            Text(
                ask.question,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
        }
        val picked = ask.pickedIndex
        ask.options.forEachIndexed { i, label ->
            val isPicked = picked == i
            Surface(
                color = if (isPicked) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f),
                shape = RoundedCornerShape(10.dp),
                border = if (isPicked) androidx.compose.foundation.BorderStroke(
                    1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                ) else null,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = if (isPicked) FontWeight.SemiBold else FontWeight.Normal
                        ),
                        color = if (isPicked) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    if (isPicked && !isError) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(13.dp)
                            )
                            Text(
                                "你的选择",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
        ask.freeText?.let { ft ->
            Surface(
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                shape = RoundedCornerShape(10.dp),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "你的回答：$ft",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 7.dp)
                )
            }
        }
        if (picked == null && ask.freeText == null) {
            Text(
                "未回答（运行中断或无效应答）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
    }
}

/**
 * ask_user_batch 已答题组卡：标题 + 逐题"题干 + ✓ 作答"。
 * 100 题级题组会撑爆消息流——列表限高内滚动（嵌套滚动与 LazyColumn 正常协作）。
 */
@Composable
private fun BatchAskStepCard(data: com.haoai.agent.ui.UiBatchAskData, isError: Boolean) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StepIconBox {
                Icon(
                    Icons.Filled.Lightbulb,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(15.dp)
                )
            }
            Text(
                data.title,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                "${data.questions.size} 题",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        val scroll = androidx.compose.foundation.rememberScrollState()
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 300.dp)
                .verticalScroll(scroll),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            data.questions.forEachIndexed { i, q ->
                val answer = data.answers.getOrNull(i)
                Surface(
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            "${i + 1}. ${q.question}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            when {
                                answer != null -> "✓ $answer"
                                isError -> "未回答（失败）"
                                else -> "未回答（运行中断）"
                            },
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.SemiBold
                            ),
                            color = if (answer != null && !isError) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                }
            }
        }
    }
}

/** 工具名 → 步骤图标（按语义关键字归类，未匹配走通用工具图标） */
private fun toolIcon(name: String): ImageVector = when {
    name.contains("browser") || name.contains("web") || name.contains("search") || name.contains("fetch") ->
        if (name.contains("search")) Icons.Filled.Search else Icons.Filled.Language
    name.contains("shell") || name.contains("exec") || name.contains("run") -> Icons.Filled.Terminal
    name == "write" || name == "edit" -> Icons.Filled.Description
    name == "read" || name.contains("file") || name.contains("grep") -> Icons.Filled.Article
    name.contains("spawn") || name.contains("agent") -> Icons.Filled.AccountTree
    name == "ask_user" || name == "ask_user_batch" -> Icons.Filled.Lightbulb
    else -> Icons.Filled.Build
}

// ═══════════ 截图展示（方案 A：步骤缩略图 + 弹层大图，2026-09-15）═══════════

/** 截图解码缓存：按 dataUrl 哈希键，容量 6 张（1024px JPEG 单张解码后约 1–4MB）。 */
private val shotCache = android.util.LruCache<String, ImageBitmap>(6)

/** data URL → ImageBitmap（Base64 解码放 IO 线程，结果进 LruCache，滚动不重复解码）。 */
private suspend fun decodeShot(dataUrl: String): ImageBitmap? {
    val key = dataUrl.hashCode().toString()
    shotCache.get(key)?.let { return it }
    val bmp = withContext(Dispatchers.IO) {
        runCatching {
            val b64 = dataUrl.substringAfter("base64,", "")
            if (b64.isEmpty()) return@runCatching null
            val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }.getOrNull()
    } ?: return null
    shotCache.put(key, bmp)
    return bmp
}

/** 步骤行缩略图 46×58（触控热区由整行 clickable 覆盖，≥48dp）。 */
@Composable
private fun ShotThumb(dataUrl: String) {
    var bmp by remember(dataUrl) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(dataUrl) { bmp = decodeShot(dataUrl) }
    Box(
        Modifier
            .size(width = 46.dp, height = 58.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .border(
                1.dp,
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f),
                RoundedCornerShape(8.dp)
            ),
        contentAlignment = Alignment.Center
    ) {
        bmp?.let {
            Image(
                it,
                contentDescription = "网页截图缩略图，点按查看大图",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

/**
 * 弹层大图：铺满宽度（最高 420dp），支持双指捏合 1×–4×、双击放大/复原、
 * 放大后拖动平移（位移按缩放倍数夹取，不会把图拖出视野）。
 */
@Composable
private fun ZoomableShot(dataUrl: String) {
    var bmp by remember(dataUrl) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(dataUrl) { bmp = decodeShot(dataUrl) }
    var scale by remember(dataUrl) { mutableFloatStateOf(1f) }
    var off by remember(dataUrl) { mutableStateOf(Offset.Zero) }
    var boxW by remember { mutableFloatStateOf(0f) }
    var boxH by remember { mutableFloatStateOf(0f) }
    fun clamp() {
        val maxX = ((scale - 1f).coerceAtLeast(0f)) * boxW / 2f
        val maxY = ((scale - 1f).coerceAtLeast(0f)) * boxH / 2f
        off = Offset(off.x.coerceIn(-maxX, maxX), off.y.coerceIn(-maxY, maxY))
    }
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 420.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .onSizeChanged { boxW = it.width.toFloat(); boxH = it.height.toFloat() }
            .pointerInput(dataUrl) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 4f)
                    off = if (scale <= 1.001f) Offset.Zero else Offset(off.x + pan.x, off.y + pan.y)
                    clamp()
                }
            }
            .pointerInput(dataUrl) {
                detectTapGestures(onDoubleTap = {
                    scale = if (scale > 1.001f) 1f else 2.5f
                    if (scale <= 1.001f) off = Offset.Zero
                    clamp()
                })
            },
        contentAlignment = Alignment.Center
    ) {
        bmp?.let {
            Image(
                it,
                contentDescription = "网页截图大图，可双指放大或双击缩放",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = off.x,
                        translationY = off.y
                    )
            )
        }
        if (bmp != null && scale <= 1.001f) {
            Text(
                "双指放大 · 双击缩放",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            )
        }
    }
}

/** 保存截图为相册图片（MediaStore，API 29+ 免权限写 Pictures/HaoAI）。 */
private fun saveShotToGallery(context: android.content.Context, dataUrl: String) {
    runCatching {
        val b64 = dataUrl.substringAfter("base64,", "")
        val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
        val values = android.content.ContentValues().apply {
            put(
                android.provider.MediaStore.MediaColumns.DISPLAY_NAME,
                "haoai_shot_${System.currentTimeMillis()}.jpg"
            )
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                put(
                    android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                    android.os.Environment.DIRECTORY_PICTURES + "/HaoAI"
                )
            }
        }
        val uri = context.contentResolver.insert(
            android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
        ) ?: error("无法在相册创建条目")
        context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            ?: error("无法写入相册")
        android.widget.Toast.makeText(
            context, "已保存到相册 Pictures/HaoAI", android.widget.Toast.LENGTH_SHORT
        ).show()
    }.onFailure {
        android.widget.Toast.makeText(
            context, "保存失败：${it.message}", android.widget.Toast.LENGTH_SHORT
        ).show()
    }
}

/**
 * 工具详情底部弹层（顶层渲染，取舍②）。由 ChatScreen 在 **appLayer 之外**挂载
 * （与 MessageActionPanel 同位）：backdrop 传入 GlassPanel 做真液态玻璃，透出下方
 * 消息流实时折射。Popup/Dialog 独立窗口采样不到 appLayer backdrop（实测弹层内为
 * 均匀死灰），故必须在主窗口组合树内、appLayer 外渲染。
 */
@Composable
fun ToolDetailSheet(
    tool: UiTool,
    backdrop: com.kyant.backdrop.backdrops.LayerBackdrop,
    onDismiss: () -> Unit,
    onViewDiff: (String) -> Unit = {},
    onStopSubagent: (String) -> Unit = {}
) {
    val verb = tool.brief.ifBlank { tool.name }.substringBefore('·').trim()
    val obj = tool.brief.substringAfter('·', "").trim()
    // v8：滑入/滑出 + 拖横杠跟手收起（GlassBottomSheet 通用壳，横杠由壳提供）
    com.haoai.agent.ui.common.GlassBottomSheet(
        backdrop = backdrop,
        onDismiss = onDismiss
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp)
                .padding(top = 4.dp, bottom = 16.dp)
        ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        toolIcon(tool.name),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(
                        verb.ifBlank { tool.name },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        tool.name,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
                Spacer(Modifier.size(10.dp))
                // 截图大图（可捏合/双击放大）——方案 A
                tool.imageData?.let { shot ->
                    ZoomableShot(shot)
                    Spacer(Modifier.size(10.dp))
                }
                // 参数简报：仅当简报带对象/参数（「动词 · 对象」的「·」后段）时才显示——
                // 截图类工具简报只有动词本身，标题已含同名文字，再渲染一遍就是重复
                if (obj.isNotBlank()) {
                    Text(
                        tool.brief,
                        fontSize = 11.5.sp,
                        lineHeight = 17.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
                            .padding(10.dp)
                    )
                }
                val canReview = (tool.name == "write" || tool.name == "edit") && tool.state == ToolRunState.DONE
                // 搜索结果卡列表（对齐 rikkahub SearchWebPreview）：图标+标题+摘要+域名，点击开浏览器
                if (tool.hits.isNotEmpty()) {
                    Spacer(Modifier.size(10.dp))
                    Text(
                        "共 ${tool.hits.size} 条结果",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                    val ctx = LocalContext.current
                    tool.hits.forEach { hit ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.07f))
                                .clickable {
                                    runCatching {
                                        ctx.startActivity(
                                            android.content.Intent(
                                                android.content.Intent.ACTION_VIEW,
                                                android.net.Uri.parse(hit.url)
                                            )
                                        )
                                    }
                                }
                                .padding(12.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Favicon(domain = hit.domain, size = 18.dp, circle = false)
                                Spacer(Modifier.size(10.dp))
                                Text(
                                    hit.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            if (hit.snippet.isNotBlank()) {
                                Text(
                                    hit.snippet,
                                    style = MaterialTheme.typography.bodySmall,
                                    lineHeight = 17.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                            Text(
                                hit.url,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 3.dp)
                            )
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)
                ) {
                    // 抓取步：直达网页（行点击之外的第二入口， DENIED/ERROR 时行不可达这里仍可开）
                    if (tool.name.contains("fetch") && obj.startsWith("http")) {
                        val sheetCtx = LocalContext.current
                        Text(
                            "打开网页",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f), RoundedCornerShape(999.dp))
                                .clickable {
                                    runCatching {
                                        sheetCtx.startActivity(
                                            android.content.Intent(
                                                android.content.Intent.ACTION_VIEW,
                                                android.net.Uri.parse(obj)
                                            )
                                        )
                                    }
                                }
                                .padding(horizontal = 14.dp, vertical = 6.dp)
                        )
                    }
                    tool.imageData?.let { shot ->
                        val ctx = LocalContext.current
                        Text(
                            "保存到相册",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f), RoundedCornerShape(999.dp))
                                .clickable { saveShotToGallery(ctx, shot) }
                                .padding(horizontal = 14.dp, vertical = 6.dp)
                        )
                    }
                    if (canReview) {
                        Text(
                            "查看变更",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f), RoundedCornerShape(999.dp))
                                .clickable { onDismiss(); onViewDiff(tool.callId) }
                                .padding(horizontal = 14.dp, vertical = 6.dp)
                        )
                    }
                    Text(
                        "关闭",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .clickable(onClick = onDismiss)
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                    )
                }
                // 子代理状态（spawn 工具）
                if (tool.subagents.isNotEmpty()) {
                    Column(Modifier.padding(bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        tool.subagents.forEach { sub ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    Modifier
                                        .size(7.dp)
                                        .background(
                                            when (sub.state) {
                                                "RUNNING" -> MaterialTheme.colorScheme.primary
                                                "DONE" -> Color(0xFF7BD88F)
                                                "STOPPED" -> Color(0xFFFFC46B)
                                                else -> MaterialTheme.colorScheme.error
                                            }, CircleShape
                                        )
                                )
                                Spacer(Modifier.size(6.dp))
                                Text(
                                    "子代理 ${sub.index}/${sub.total}" + if (sub.id.isNotEmpty()) " · ${sub.id}" else "",
                                    fontSize = 11.sp, fontWeight = FontWeight.SemiBold
                                )
                                Spacer(Modifier.size(6.dp))
                                Text(
                                    sub.brief, fontSize = 10.5.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                if (sub.state == "RUNNING" && sub.id.isNotEmpty()) {
                                    Text(
                                        "终止",
                                        fontSize = 10.5.sp,
                                        color = MaterialTheme.colorScheme.error,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .clickable { onStopSubagent(sub.id) }
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    }
                }
        }
    }
}
