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
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.offset
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
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
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
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
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
 * 折叠规则（2026-09-14 定稿 → 2026-10-13 修订）：
 * 1. 思考步骤三态：live → Preview（88dp 渐隐预览、自动滚尾）；正文出现/完成
 *    → 收起为「深度思考 X.X 秒」行，点击展开全文；
 * 2.（已退役）旧「步骤数 > 2 → 顶部控制条只渲染尾部 2 步」的链卡内折叠，与轮级
 *    汇总条职责重复、反而在执行期遮挡进度，整层拆除：步骤常显、无控制条、无毛边。
 *    收起整轮的职责上提到轮级「本轮过程」汇总条（见 ChatScreen.buildRenderSlots）。
 *
 * 工具详情走行尾点击 → ModalBottomSheet（取舍②），不再行内展开。
 * 注意：本组件在 appLayer 采样子树内渲染，禁用 GlassPanel/drawBackdrop
 * （层采样自引用崩溃），磨砂质感用高透 Surface 同正文气泡手法。
 */

/**
 * 打开工具详情弹层的回调（ToolStep 点击 → ChatScreen 顶层在 appLayer 外渲染
 * ToolDetailSheet + GlassPanel 真玻璃）。独立 Popup/Dialog 窗口采样不到 appLayer
 * backdrop（实测弹层内为均匀死灰），必须在主窗口组合树内、appLayer 外渲染才出玻璃。
 */
/** P4：第二参=初始选中的子代理 id（pill 点击带入；普通行点击传 null）。 */
val LocalOpenToolSheet = androidx.compose.runtime.staticCompositionLocalOf<(UiTool, String?) -> Unit> { { _, _ -> } }

/**
 * 批2a：产物卡 [在浏览器打开] 的回调。ChatScreen 顶层提供——BrowserController.
 * navigate(file://…) 自带 uiOpener（没开界面时自动弹内置浏览器预览面板），
 * 无需经 ChainCard 参数层层下传。
 */
val LocalOpenHtmlFile = androidx.compose.runtime.staticCompositionLocalOf<(String) -> Unit> { {} }

/**
 * 展开锚点通道（2026-10-13 用户拍板，效果图 fold-anchor-lab）：
 * 「往上顶」的根源=贴底跟随状态机对任何内容增高都吸底（follow 时逐帧 pinToBottom），
 * 流式期这是对的；但它**误伤用户主动点展开**——新内容插在被点的条下方，吸底把刚点的条顶出屏幕，
 * 观感方向反了（应向下掀）。
 * 解法：展开那一下置一个「豁免到期时间戳」expandParkUntil=now+750ms，跟随循环在窗口内跳过吸底，
 * 视口 value 原地、条及之前内容纹丝不动、新内容把后面正文/footer 往下推=锚定向下掀。
 * 为何用**独立时间戳**而不复用 parkedFollow：①点展开那一帧内容还 0 高、仍贴底，
 * parkedFollow 会被循环里「delta≤8 自动撤停」当场抹掉=锚定失效；②parkedFollow 还背着
 * 「用户滚开才置位」的滚动归因语义，混用会互相污染。独立窗口只影响吸底、不动跟随归因。
 * 收起方向**不调用**（吸底照常，footer 保持贴底不动，过程往上融）。窗口过后用户若仍贴底，
 * 下一帧自然恢复吸底；想看下面自己往下滚。750ms 覆盖最长展开动画（链卡 520ms + 余量）。
 * ChatScreen 顶层 provides 该 lambda；深层可折叠组件（思考阅读区/结果卡/diff 卡/轮级条）展开时调用。
 */
val LocalParkExpand = androidx.compose.runtime.staticCompositionLocalOf<() -> Unit> { {} }

/** 展开动作统一入口：起吸底豁免窗口（详见 LocalParkExpand 注释）。仅在「由收起翻展开」时调用。 */
@Composable
fun rememberParkOnExpand(): () -> Unit = LocalParkExpand.current

/**
 * 链卡内容默认展开策略（2026-10-15 用户拍板）：
 * ① 深度思考段默认**收起**为摘要行（旧=点开链卡只见大段思考墙）；
 * ② 变更卡/结果卡正文默认**收起**（旧=≤12 行短内容自动摊开）。
 * 两项各由设置开关控制（聊天显示区），默认关=收起；开关打开恢复旧行为。
 * 非 HaoApplication 宿主（预览/测试）回退 false=收起。
 */
@Composable
fun chainAutoExpand(): Pair<Boolean, Boolean> {
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext
        as? com.haoai.agent.HaoApplication ?: return false to false
    val s = app.container.settingsFlow.collectAsState().value
    return s.chainThinkAutoExpand to s.chainContentAutoExpand
}

/** diff 语义色（行尾变更计数与变更卡共用；2026-10-15 提取统一）。 */
@Composable
private fun diffAddInk(): Color =
    if (MaterialTheme.colorScheme.background.luminance() < 0.5f) Color(0xFF8FD9A6) else Color(0xFF146C2E)

@Composable
private fun diffDelInk(): Color =
    if (MaterialTheme.colorScheme.background.luminance() < 0.5f) Color(0xFFE79A9A) else Color(0xFFA1281F)

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
    /** 回合是否结束（历史/结束态 → 步骤不再播入场动画、思考收起为摘要行；链卡本体不折叠） */
    finished: Boolean,
    modifier: Modifier = Modifier
) {
    if (steps.isEmpty()) return
    val thinkAuto = chainAutoExpand().first
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
    // ── 链卡内步骤折叠整体退役（2026-10-13 用户拍板，效果图 fold-anchor-lab）──
    // 旧：步骤 > COLLAPSED_VISIBLE(2) → 顶部「显示 N 个步骤」控制条 + 只渲染尾部 2 步 + 尾部毛边。
    // 新：步骤常显、无控制条、无毛边。执行期用户要看得见 Agent 干了什么；"收起整轮"的职责
    //     已上提到轮级汇总条（本轮过程），链卡这层折叠与之职责重复且反而遮挡进度，故拆除。
    //     保留：新步骤到达的入场动画（StepToggle animateIn）、思考阅读区/结果卡/diff 卡这类
    //     "内容级"折叠（收的是长正文，不是步骤数量，不受影响）。
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = chatCardAlpha()))
            .border(
                1.dp,
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                RoundedCornerShape(18.dp)
            )
    ) {
        // 轮级折叠改版（2026-10-13 用户拍板）：导轨线整条删除。
        // 线虽已减淡到 1dp，但它仍是「44dp 走廊」存在的唯一理由——结果卡/diff 卡的右侧信息
        // 被走廊顶到卡缘、左右不对称（用户截图点名）。线没了走廊一并回收：全部宽内容贴边 12dp。
        Box {
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
                        visible = true, // 步骤折叠退役（2026-10-13）：常显，StepToggle 只保留新步骤入场动画
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
                                expanded = reasoningOpen[index] ?: thinkAuto,
                                onToggle = { reasoningOpen[index] = !(reasoningOpen[index] == true) }
                            )
                            is ChainStep.Tool -> Column {
                                val openToolFn = LocalOpenToolSheet.current
                                ToolStep(tool = step.tool, live = toolsLive)
                                // P4 子代理 pill 组：spawn 行下挂，每路实时在干什么（ZCode「The pill」规格）
                                SubagentPillRow(step.tool) { subId -> openToolFn(step.tool, subId) }
// 批1e：编码向步骤行下长出结果正文卡（exit/耗时 + 可折叠 stdout）
// write/edit 例外：正文只有"已替换 N 处"一行，变更卡信息量全包含它，两张并排是噪音。
                                if (step.tool.body != null && step.tool.diff == null) {
                                    ToolBodyCard(step.tool)
                                }
                                // 批1f：write/edit 步骤行下长出变更卡（+N−M + 带行号的可折叠 diff）
                                if (step.tool.diff != null) {
                                    ToolDiffCard(step.tool.diff)
                                }
                                // 批2a：HTML 产物卡（[预览]/[在浏览器打开] 双按钮）。
                                // 与变更卡并存不重复：变更卡回答"改了哪几行"，
                                // 产物卡回答"东西在哪、怎么看"。
                                if (step.tool.htmlFile != null) {
                                    HtmlFileCard(step.tool.htmlFile)
                                }
                                // 搜索步：行下 favicon 叠排 + 结果数（对齐 rikkahub FaviconRow）
                                if (step.tool.hits.isNotEmpty()) {
                                    Row(
                                        Modifier.padding(start = 12.dp, bottom = 8.dp),
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

/**
 * 面板内滚动「边界钳制」共用修饰（2026-10-07 真机反馈首用于思考阅读区，
 * 2026-10-13 轮级折叠改版抽成共用补到结果卡/diff卡/题组列表）：
 * 内层滚到顶/底后继续拖，剩余滚动就地吃掉，不再透传给外层会话流——
 * 用户点名症状「展开代码或 diff 面板后一拖把整屏信息流带走」。
 * 内容本身不可滚（maxValue=0，短面板）时放行：拖动仍应滚外层对话。
 */
@Composable
internal fun Modifier.panelBoundaryClamp(scroll: androidx.compose.foundation.ScrollState): Modifier {
    val clamp = remember(scroll) {
        object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: androidx.compose.ui.input.nestedscroll.NestedScrollSource
            ): Offset = if (scroll.maxValue > 0) available else Offset.Zero
        }
    }
    return then(Modifier.nestedScroll(clamp))
}

/** 步骤图标列（导轨线删除后仅保留 24dp 窄列对齐图标；遮线底座随线退役） */
@Composable
private fun StepIconBox(content: @Composable () -> Unit) {
    Box(
        Modifier
            .width(24.dp)
            .height(20.dp),
        contentAlignment = Alignment.Center
    ) {
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
    // 展开锚点：阅读区「由收→展」置停车，不吸底（详见 LocalParkFollow 注释）
    val parkOnExpand = rememberParkOnExpand()
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .chainPressable(onClick = { if (!expanded) parkOnExpand(); onToggle() })
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
                // 折叠动画 2026-10-13 新方案：同枚箭头旋转 240ms（不再 ExpandLess/More 瞬切）
                val thinkRot by animateFloatAsState(
                    targetValue = if (expanded) 180f else 0f,
                    animationSpec = tween(240, easing = androidx.compose.animation.core.CubicBezierEasing(0.3f, 0.6f, 0.35f, 1f)),
                    label = "thinkChevron"
                )
                Icon(
                    Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "收起思考过程" else "展开思考过程",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(16.dp)
                        .graphicsLayer { rotationX = thinkRot }
                )
            }
        }
        // 阅读区：live 展开=跟尾滚动（用户上滚即让位细读），frozen 展开=自由回看全文
        // 折叠动画 2026-10-13 新方案（效果图实验室拍板）：
        // 旧=expandVertically 默认锚底（文字从下往上"飞进窗口"，方向拧）+ 淡入同起步（半遮整块发灰）。
        // 新=从顶栏向下掀开（expandFrom=Top）+ 文字晚 60ms 上浮淡入（先开门后进光）；
        //     收起时文字 110ms 快淡、容器 220ms 收尾，干脆不拖。
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(
                animationSpec = tween(280, easing = androidx.compose.animation.core.CubicBezierEasing(0.3f, 0.6f, 0.35f, 1f)),
                expandFrom = Alignment.Top
            ),
            exit = shrinkVertically(
                animationSpec = tween(220, easing = androidx.compose.animation.core.CubicBezierEasing(0.3f, 0.6f, 0.35f, 1f)),
                shrinkTowards = Alignment.Top
            ) + fadeOut(tween(160))
        ) {
            // 文字"后进光"：入场首帧 alpha=0，等 60ms 再 200ms 淡入；
            // 退场时内容仍在组合，110ms 快淡先走。
            // （animateFloatAsState 首组合直接取目标值 1，播不出入场淡入——必须 Animatable）
            val reveal = remember { androidx.compose.animation.core.Animatable(1f) }
            LaunchedEffect(expanded) {
                if (expanded) {
                    reveal.snapTo(0f)
                    kotlinx.coroutines.delay(60)
                    reveal.animateTo(1f, tween(200))
                } else {
                    reveal.animateTo(0f, tween(110))
                }
            }
            val scroll = rememberScrollState()
            // 边界钳制抽成共用（见 panelBoundaryClamp）
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
                    .padding(start = 12.dp, end = 12.dp, bottom = 8.dp)
                    .graphicsLayer {
                        alpha = reveal.value
                        translationY = (1f - reveal.value) * 6.dp.toPx()
                    }
                    .fillMaxWidth()
                    .heightIn(max = 240.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
                    .panelBoundaryClamp(scroll)
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
            // 快捷跳转收进域名文本本身（见下方 isFetch 分支）与弹层「打开网页」按钮。
            // P4：spawn 行运行中也可点（子代理 pill 透视需要边跑边看；其余工具维持运行中禁点）
            .chainPressable(enabled = !running || tool.subagents.isNotEmpty()) { openTool(tool, null) }
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
                        // 2026-10-15 去实心药丸改裸文字链接：与抓取步"域名文本可点"同分型，
                        // 行内两颗药丸（动词+文件）互相打架是"突兀"的根源；交互不变
                        .clip(RoundedCornerShape(7.dp))
                        .clickable { viewing = true }
                        .padding(horizontal = 4.dp, vertical = 2.dp)
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
                        // 与路径芯片同款改裸文字链接（同交互分型同步演进）
                        .clip(RoundedCornerShape(7.dp))
                        .clickable { showLog = true }
                        .padding(horizontal = 4.dp, vertical = 2.dp)
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
            // 2026-10-15 配色改 diff 语义（绿增红删）：此前一律主题绿，
            // "−删除"用动作色反而违和；纯删除文件也不再显示"+0"
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FwSpan.SemiBold)) {
                        if (diff.isNewFile) {
                            append("新建 ")
                            withStyle(SpanStyle(color = diffAddInk())) { append("${diff.added}") }
                            append(" 行")
                        } else {
                            if (diff.added > 0) withStyle(SpanStyle(color = diffAddInk())) { append("+${diff.added}") }
                            if (diff.added > 0 && diff.removed > 0) append(" ")
                            if (diff.removed > 0) withStyle(SpanStyle(color = diffDelInk())) { append("−${diff.removed}") }
                        }
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace
            )
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

    val (_, contentAuto) = chainAutoExpand()
    var expanded by rememberSaveable(tool.callId) {
        mutableStateOf(contentAuto && lines.size <= AUTO_EXPAND_MAX_LINES)
    }
    // 展开锚点：正文卡「由收→展」置停车，不吸底
    val parkOnExpand = rememberParkOnExpand()

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 6.dp),
        color = Color.Transparent,
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, border)
    ) {
        Column {
            // 头部：点整行折叠/展开（不用小字按钮——手机上目标太小）
            Row(
                Modifier
                    .fillMaxWidth()
                    .chainPressable { if (!expanded) parkOnExpand(); expanded = !expanded }
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
            // 折叠动画 2026-10-13 新方案（与思考阅读区同规格）：向下掀开 + 内容后到，
            // 不再是 if(expanded) 瞬删瞬现
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(
                    animationSpec = tween(280, easing = androidx.compose.animation.core.CubicBezierEasing(0.3f, 0.6f, 0.35f, 1f)),
                    expandFrom = Alignment.Top
                ),
                exit = shrinkVertically(
                    animationSpec = tween(220, easing = androidx.compose.animation.core.CubicBezierEasing(0.3f, 0.6f, 0.35f, 1f)),
                    shrinkTowards = Alignment.Top
                ) + fadeOut(tween(160))
            ) {
                val bodyScroll = rememberScrollState()
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = BODY_MAX_HEIGHT)
                        .background(bg)
                        .panelBoundaryClamp(bodyScroll)
                        .verticalScroll(bodyScroll)
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
    val addFg = diffAddInk()
    val delFg = diffDelInk()
    val numFg = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
    val bodyFg = if (dark) Color(0xFFD7DEE9) else Color(0xFF23272F)

    // 只渲染变更行 + 少量上下文：整份文件铺开会把对话冲垮，而用户要看的是"哪几行动了"。
    // 上下文取前后各 CONTEXT_LINES 行，让改动不至于悬空看不出位置。
    val shown = remember(diff.lines) { contextOf(diff.lines) }
    val (_, contentAuto) = chainAutoExpand()
    var expanded by rememberSaveable(diff.path) {
        mutableStateOf(contentAuto && shown.size <= AUTO_EXPAND_MAX_LINES)
    }
    // 展开锚点：diff 卡「由收→展」置停车，不吸底
    val parkOnExpand = rememberParkOnExpand()

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 6.dp),
        color = Color.Transparent,
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, border)
    ) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .chainPressable { if (!expanded) parkOnExpand(); expanded = !expanded }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 2026-10-15 v2 配色改 diff 语义绿增红删（ZCode/GitHub 同款约定）：
                // 上一版只分了步骤行尾计数，卡头 +N −M 仍整段主题绿——本处补齐。
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                            if (diff.isNewFile) {
                                append("新建文件 · ")
                                withStyle(SpanStyle(color = diffAddInk())) { append("${diff.added} 行") }
                            } else {
                                if (diff.added > 0) withStyle(SpanStyle(color = diffAddInk())) { append("+${diff.added}") }
                                if (diff.added > 0 && diff.removed > 0) append(" ")
                                if (diff.removed > 0) withStyle(SpanStyle(color = diffDelInk())) { append("−${diff.removed}") }
                            }
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    diff.path.substringAfterLast('/'),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                    // 2026-10-15 v2 摘掉 weight(1f, fill=false)：它与后面的 Spacer(weight(1f))
                    // 平分剩余权重，把「展开」从右缘挤到行中间（用户截图点名）。
                    // 推右职责只归 Spacer——与结果卡头部同款写法。
                )
                Spacer(Modifier.weight(1f))
                Text(
                    if (expanded) "收起" else "展开",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            // 折叠动画 2026-10-13 新方案（与思考阅读区/结果卡同规格）
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(
                    animationSpec = tween(280, easing = androidx.compose.animation.core.CubicBezierEasing(0.3f, 0.6f, 0.35f, 1f)),
                    expandFrom = Alignment.Top
                ),
                exit = shrinkVertically(
                    animationSpec = tween(220, easing = androidx.compose.animation.core.CubicBezierEasing(0.3f, 0.6f, 0.35f, 1f)),
                    shrinkTowards = Alignment.Top
                ) + fadeOut(tween(160))
            ) {
                val diffScroll = rememberScrollState()
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = BODY_MAX_HEIGHT)
                        .panelBoundaryClamp(diffScroll)
                        .verticalScroll(diffScroll)
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
                                // 轮级折叠改版（ZCode 式轻量 diff）：增/删行加 3dp 左缘色条——
                                // 底色 12% 透壁纸偏淡，色条让"哪几行动了"扫一眼即辨
                                .drawBehind {
                                    val barW = 3.dp.toPx()
                                    when (row.kind) {
                                        DiffRowKind.ADDED -> drawRect(addFg, size = Size(barW, size.height))
                                        DiffRowKind.REMOVED -> drawRect(delFg, size = Size(barW, size.height))
                                        else -> Unit
                                    }
                                }
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
            .padding(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 6.dp)
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
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier
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
                .panelBoundaryClamp(scroll)
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
    onStopSubagent: (String) -> Unit = {},
    /** P4：pill 点击带入的初始选中子代理 id；null=自动选第一路运行中的。 */
    initialSubagentId: String? = null
) {
    val verb = tool.brief.ifBlank { tool.name }.substringBefore('·').trim()
    val obj = tool.brief.substringAfter('·', "").trim()
    // 方案 A（2026-10-21）：spawn 类弹层内容长（子代理工作过程可超数屏），
    // 横杠可上拖展开全屏；短内容卡（read/fetch 几行字）不给上拖交互——
    // 判据 = 内容自然高 > 560dp 封顶。壳接管封顶后调用方不再自挂 heightIn。
    val isSubagentSheet = tool.subagents.isNotEmpty()
    val screenH = with(androidx.compose.ui.platform.LocalDensity.current) {
        androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp.toPx()
    }
    val expandedPx: (@Composable () -> Float)? =
        if (isSubagentSheet) ({ screenH * 0.92f }) else null
    // v8：滑入/滑出 + 拖横杠跟手收起（GlassBottomSheet 通用壳，横杠由壳提供）
    com.haoai.agent.ui.common.GlassBottomSheet(
        backdrop = backdrop,
        onDismiss = onDismiss,
        collapsedMax = 560.dp,
        expandedPx = expandedPx
    ) {
        Column(
            Modifier
                .fillMaxWidth()
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
                // 截图类工具简报只有动词本身，标题已含同名文字，再渲染一遍就是重复；
                // 子代理弹层同样跳过：brief=「并行子代理 · N 路」与标题+tab 计数重复，
                // 2026-10-21 用户点名「标题与子代理名之间空隙大」——冗余简报块是元凶之一。
                if (obj.isNotBlank() && !isSubagentSheet) {
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
                // 动作行有内容才渲染：空 Row 的 padding(vertical=12dp) 会白占 24dp，
                // 把标题与子代理 tab 之间的空隙撑大（2026-10-21 用户点名）。
                val hasActions = (tool.name.contains("fetch") && obj.startsWith("http")) ||
                    tool.imageData != null || canReview
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
                if (hasActions) {
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
                        // P4：独立「关闭」文字键删除（2026-10-21 用户拍板）——横杠拖拽/点遮罩已是
                        // 标准收起手势，再留一个文字键是冗余；「查看变更」保留（它有独立语义）
                    }
                }
                // P4 子代理过程面板（spawn 工具）：三路 tab + 选中路的派发/过程/中间结论/回传
                if (tool.subagents.isNotEmpty()) {
                    var selectedId by rememberSaveable(tool.callId, initialSubagentId) {
                        mutableStateOf(
                            initialSubagentId
                                ?: tool.subagents.firstOrNull { it.state == "RUNNING" }?.id
                                ?: tool.subagents.first().id
                        )
                    }
                    val sel = tool.subagents.firstOrNull { it.id == selectedId } ?: tool.subagents.first()
                    SubagentTabs(tool.subagents, sel.id) { selectedId = it }
                    Spacer(Modifier.size(8.dp))
                    SubagentTimeline(
                        state = sel.state,
                        task = sel.task.ifBlank { tool.brief },
                        steps = sel.steps,
                        lastAssistant = sel.lastAssistant,
                        finalResult = sel.finalResult,
                        tokensUsed = sel.tokensUsed,
                        onStop = onStopSubagent,
                        stopId = sel.id
                    )
                }
        }
    }
}

// ────────────────────────── P4 子代理透视组件 ──────────────────────────

/** 身份脸谱九色（ZCode WorkflowAgentFace.tsx 原版色板，按路序号循环取色）。 */
internal val SUBAGENT_FACE_COLORS = listOf(
    Color(0xFF54B9A6), Color(0xFFF19D38), Color(0xFF6464EF), Color(0xFF885CF5),
    Color(0xFF3C82F6), Color(0xFFED712E), Color(0xFFEB4699), Color(0xFF5BC67A),
    Color(0xFFEA4045)
)

internal fun subagentFaceColor(index: Int): Color =
    SUBAGENT_FACE_COLORS[((index - 1).mod(SUBAGENT_FACE_COLORS.size))]

/**
 * P4 子代理 pill 组（ZCode presentation.md「The pill」规格）：spawn 行下挂，
 * 每路一行「脸谱号 · 子代理 i/N · 实时在干什么 · 状态槽」。超过 ~4 路时在
 * 152dp 高度内滚动（2026-10-22 删底部 DstOut 渐隐：用户实锤白色渐隐遮挡
 * 子代理名字/内容且其他场景也有；≤4 路不溢出时渐隐纯属误伤末行）。
 */
@Composable
internal fun SubagentPillRow(tool: UiTool, onOpenSub: (String) -> Unit) {
    if (tool.subagents.isEmpty()) return
    // 2026-10-22 padding 改对称 12dp：原 start23/end12 不对称把整组推右
    // 5.5dp（用户点名「没居中、靠右」），现与 ToolStep 行左右边距对齐。
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, bottom = 6.dp)
    ) {
        // 2026-10-21：补挂 panelBoundaryClamp（此前漏挂 = pill 组滚到边界会带走
        // 外层会话流；主 Agent 思考过程/题组卡早已挂上，见 panelBoundaryClamp 注释）
        val pillScroll = rememberScrollState()
        Column(
            Modifier
                .heightIn(max = 152.dp)
                .panelBoundaryClamp(pillScroll)
                .verticalScroll(pillScroll),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            tool.subagents.forEach { sub ->
                SubagentPill(sub = sub, onClick = { onOpenSub(sub.id) })
            }
        }
    }
}

/** 单颗 pill：脸谱 20dp + 「子代理 i/N」 + 实时简报 + 状态槽（运行环/完成勾/失败叉）。 */
@Composable
private fun SubagentPill(sub: com.haoai.agent.ui.SubagentLine, onClick: () -> Unit) {
    val running = sub.state == "RUNNING"
    Row(
        Modifier
            .fillMaxWidth()
            .height(30.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
            .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f), RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(start = 4.dp, end = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            Modifier
                .size(20.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(subagentFaceColor(sub.index)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "${sub.index}",
                fontSize = 10.sp,
                // 2026-10-21：关 includeFontPadding + lineHeight 匹配框高（20dp 框）——
                // 默认行框含字体上下 padding，数字视觉重心被压偏低（用户实锤反馈）
                lineHeight = 12.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White,
                style = TextStyle(
                    platformStyle = PlatformTextStyle(includeFontPadding = false)
                )
            )
        }
        Text(
            "子代理 ${sub.index}/${sub.total}",
            fontSize = 10.5.sp, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
        )
        Text(
            text = when {
                sub.state == "RUNNING" && sub.brief.isNotBlank() -> sub.brief
                sub.state == "DONE" && sub.brief.isNotBlank() -> sub.brief
                sub.state == "ERROR" -> "失败：${sub.brief}"
                else -> if (sub.task.isNotBlank()) sub.task else "等待开始"
            },
            fontSize = 10.sp,
            color = if (running) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        SubagentStateTail(sub.state)
    }
}

/** 状态槽 13dp：运行=绿顶旋转环；完成=绿圆勾；失败=红叉；其他=灰点。 */
@Composable
private fun SubagentStateTail(state: String) {
    Box(Modifier.size(13.dp), contentAlignment = Alignment.Center) {
        when (state) {
            "RUNNING", "STOPPING" -> {
                val angle = rememberInfiniteTransition(label = "subspin").animateFloat(
                    0f, 360f, infiniteRepeatable(tween(850, easing = LinearEasing)),
                    label = "subspinA"
                )
                Box(
                    Modifier
                        .size(11.dp)
                        .graphicsLayer { rotationZ = angle.value }
                        .border(
                            1.5.dp,
                            Brush.sweepGradient(
                                0f to MaterialTheme.colorScheme.primary,
                                0.35f to MaterialTheme.colorScheme.primary,
                                0.35f to MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f),
                                1f to MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)
                            ),
                            CircleShape
                        )
                )
            }
            "DONE" -> Box(
                Modifier
                    .size(12.dp)
                    .background(Color(0xFF3FAE5C), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.Check, contentDescription = null,
                    tint = Color.White, modifier = Modifier.size(8.dp)
                )
            }
            "ERROR" -> Text(
                "✕", fontSize = 10.sp, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.error
            )
            else -> Box(
                Modifier
                    .size(7.dp)
                    .background(Color(0xFFFFC46B), CircleShape)
            )
        }
    }
}

/** 三路切换 tab（场景二）：脸谱点 + 路号，选中态白底浮起。 */
@Composable
internal fun SubagentTabs(
    subs: List<com.haoai.agent.ui.SubagentLine>,
    selectedId: String,
    onSelect: (String) -> Unit
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        subs.forEach { sub ->
            val on = sub.id == selectedId
            Row(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (on) MaterialTheme.colorScheme.surface
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)
                    )
                    // 2026-10-22 用户反馈：tab 与磨砂背景糊成一片（未选中原仅 5% 深色
                    // 罩，定不住形）。补 1dp 描边——未选中 onSurface 16% 管定形，
                    // 选中 primary 45% 管语义强调；底色同步 5%→7% 让形状更实。
                    .border(
                        1.dp,
                        if (on) MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f),
                        RoundedCornerShape(8.dp)
                    )
                    .clickable { onSelect(sub.id) }
                    .padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(6.dp)
                        .background(subagentFaceColor(sub.index), CircleShape)
                )
                Spacer(Modifier.size(4.dp))
                Text(
                    "${sub.index} · ${if (sub.state == "RUNNING") "工作中" else if (sub.state == "DONE") "完成" else if (sub.state == "ERROR") "失败" else "已终止"}",
                    fontSize = 10.sp, fontWeight = FontWeight.Bold,
                    color = if (on) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
    }
}

/**
 * 单路过程面板（2026-10-21 改版定稿 · 方案 A「气泡流」，替代旧四块分区终端风）：
 * 派发（竖线 + 等宽任务词）→ 每步工具调用链卡 → 中间结论气泡 → 回传气泡
 * （统计行 + 来源标签）。设计语言对齐主 Agent 聊天界面（链卡 + 气泡），
 * 9.5sp 大字距分区标签与全等宽正文全部退役。
 * ToolDetailSheet（spawn 工具弹层）与 SubagentBadgeDetailSheet（浮标详情）共用。
 */
@Composable
internal fun SubagentTimeline(
    state: String,
    task: String,
    steps: List<String>,
    lastAssistant: String,
    finalResult: String?,
    tokensUsed: Long,
    onStop: (String) -> Unit = {},
    stopId: String = ""
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // ── 派发：等宽任务词（它就是命令，与主界面用户消息角色对等）。
        // 2026-10-21 对齐修复：旧实现竖线 3dp+Spacer 8dp 把词块推到 29dp，比 tab/
        // 气泡（18dp）右移 11dp——用户点名错位。竖线退役为词块内嵌左饰条，层级
        // 语义不丢，所有块统一贴弹层 18dp 左边界。
        Column(Modifier.fillMaxWidth()) {
            Text(
                "派发任务",
                fontSize = 9.sp, fontWeight = FontWeight.ExtraBold,
                letterSpacing = 0.4.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.size(3.dp))
            val railColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.30f)
            Text(
                task.ifBlank { "（未记录任务词）" },
                fontSize = 10.sp, lineHeight = 15.sp, fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.045f))
                    .drawBehind {
                        drawRoundRect(
                            color = railColor,
                            topLeft = Offset(4.dp.toPx(), 8.dp.toPx()),
                            size = Size(3.dp.toPx(), size.height - 16.dp.toPx()),
                            cornerRadius = CornerRadius(1.5.dp.toPx(), 1.5.dp.toPx())
                        )
                    }
                    .padding(start = 12.dp, end = 9.dp, top = 7.dp, bottom = 7.dp)
            )
        }
        // ── 过程：每一步 = 一张小链卡（主 Agent 工具链卡同语言）。
        // 2026-10-21 对齐修复：外层 start=11dp 缩进退役，与派发块/气泡统一贴 18dp 左边界。
        if (steps.isEmpty()) {
            Text(
                if (state == "RUNNING") "正在读取任务…" else "（未留下步骤）",
                fontSize = 10.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                steps.forEach { s -> SubagentStepChip(s) }
            }
        }
        // ── 中间结论：assistant 气泡（非等宽，去掉终端感）──
        if (lastAssistant.isNotBlank()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(topStart = 13.dp, topEnd = 13.dp, bottomEnd = 13.dp, bottomStart = 4.dp))
                        .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.08f))
                        .padding(horizontal = 11.dp, vertical = 9.dp)
                ) {
                    Text(
                        "中间结论 · 未经工具验证",
                        fontSize = 9.sp, fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 0.4.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                    )
                    Spacer(Modifier.size(4.dp))
                    Text(
                        lastAssistant.take(600),
                        fontSize = 11.sp, lineHeight = 17.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
        // ── 回传：final 气泡（统计行 + 来源标签）──
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 13.dp, topEnd = 13.dp, bottomEnd = 13.dp, bottomStart = 4.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.07f))
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                        RoundedCornerShape(topStart = 13.dp, topEnd = 13.dp, bottomEnd = 13.dp, bottomStart = 4.dp)
                    )
                    .padding(horizontal = 11.dp, vertical = 9.dp)
            ) {
                Text(
                    when {
                        finalResult != null -> "回传 · 最终结果"
                        state == "RUNNING" -> "回传 · 仍在工作中"
                        else -> "回传"
                    },
                    fontSize = 9.sp, fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 0.4.sp,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.size(4.dp))
                when {
                    finalResult != null -> {
                        // 回传正文：限高 200dp 内滚 + 边界钳制（2026-10-21 补挂，
                        // 修复「滚到边界带走整个弹层/会话流」——用户实锤反馈）
                        val retScroll = rememberScrollState()
                        Text(
                            finalResult.take(4000),
                            fontSize = 10.5.sp, lineHeight = 16.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 200.dp)
                                .panelBoundaryClamp(retScroll)
                                .verticalScroll(retScroll)
                                .padding(end = 2.dp)
                        )
                        // 统计行：步数 + token（数据层无单路耗时字段，不造假）
                        Spacer(Modifier.size(7.dp))
                        val lineColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                        val tokText =
                            if (tokensUsed >= 1000) "${tokensUsed / 1000}k tok" else "$tokensUsed tok"
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .drawBehind {
                                    drawLine(
                                        color = lineColor,
                                        start = Offset(0f, 0f),
                                        end = Offset(size.width, 0f),
                                        strokeWidth = 1.dp.toPx()
                                    )
                                }
                                .padding(top = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                "${steps.size} 步 · $tokText",
                                fontSize = 9.sp, fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.75f)
                            )
                        }
                        // 来源标签：从步骤串里真实提取过的 URL（非编造）
                        val srcUrls = remember(steps) {
                            steps.mapNotNull { s ->
                                Regex("https?://[^\\s)\\]>，。；]+").find(s)?.value
                            }.distinct().take(4)
                        }
                        if (srcUrls.isNotEmpty()) {
                            Spacer(Modifier.size(7.dp))
                            val srcCtx = LocalContext.current
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                srcUrls.forEach { u ->
                                    Text(
                                        domainFromUrl(u),
                                        fontSize = 9.sp, fontWeight = FontWeight.SemiBold,
                                        color = Color(0xFF207A43),
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(999.dp))
                                            .background(Color(0xFF3FAE5C).copy(alpha = 0.10f))
                                            .clickable {
                                                runCatching {
                                                    srcCtx.startActivity(
                                                        android.content.Intent(
                                                            android.content.Intent.ACTION_VIEW,
                                                            android.net.Uri.parse(u)
                                                        )
                                                    )
                                                }
                                            }
                                            .padding(horizontal = 7.dp, vertical = 2.5.dp)
                                    )
                                }
                            }
                        }
                    }
                    state == "RUNNING" -> Text(
                        "仍在工作中，完成后自动回传并唤醒主代理…",
                        fontSize = 10.5.sp, lineHeight = 16.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                    else -> Text(
                        "（无结果回传）",
                        fontSize = 10.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
            }
        }
        // 状态行：运行中给终止键（与旧弹层同通路）
        if (state == "RUNNING" && stopId.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                Text(
                    "终止这一路",
                    fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .border(
                            1.dp,
                            MaterialTheme.colorScheme.error.copy(alpha = 0.3f),
                            RoundedCornerShape(999.dp)
                        )
                        .clickable { onStop(stopId) }
                        .padding(horizontal = 12.dp, vertical = 5.dp)
                )
            }
        }
    }
}

/**
 * 过程步骤链卡（方案 A 2026-10-21）：把步骤串「fetch(url) → 404 [错误]」
 * 拆成动作段（等宽主文本）+ 结果段（右侧，错误红/正常灰），
 * 视觉与主 Agent 工具链卡同语言（图标 + 摘要 + 结果槽）。
 */
@Composable
private fun SubagentStepChip(step: String) {
    val action = step.substringBefore(" → ").trim().ifBlank { step }
    val result = step.substringAfter(" → ", "").trim()
    val isErr = result.contains("错误") || result.contains("404") || result.contains("失败")
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(
                1.dp,
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f),
                RoundedCornerShape(10.dp)
            )
            .padding(horizontal = 9.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Box(
            Modifier
                .size(17.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(
                    if (isErr) MaterialTheme.colorScheme.error.copy(alpha = 0.10f)
                    else Color(0xFF3FAE5C).copy(alpha = 0.12f)
                ),
            contentAlignment = Alignment.Center
        ) {
            if (isErr) {
                Text(
                    "✕", fontSize = 8.sp, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error
                )
            } else {
                Icon(
                    Icons.Filled.Check, contentDescription = null,
                    tint = Color(0xFF2E9B4F), modifier = Modifier.size(9.dp)
                )
            }
        }
        Text(
            action,
            fontSize = 10.sp, fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (result.isNotBlank()) {
            Text(
                result,
                fontSize = 9.sp,
                color = if (isErr) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

// ────────────────────────── P4 右上角浮标（效果图场景四） ──────────────────────────

/**
 * 右上角子代理浮标 v2（2026-10-21 重设计定稿，效果图场景四 v2 档）：
 * - chip：脸谱堆叠（≤3 路全露，更多叠 "+N"）+ 「N/M 进行中」/ 结束态文案
 *   + 底部 2dp 进度条（运行比例；有失败时进度条转红）
 * - 面板 238dp：汇总头（N 路 · M 进行中 · K 完成）+ 38dp 两行结构行
 *   （身份行 = i/N + 状态胶囊；简报行 = 当前工具/结果摘要）+ 150dp 内滚
 *   （已挂 panelBoundaryClamp）+ 底部合计 token 统计行
 * 状态源 = BgBadge（ChatViewModel 1s 轮询协调器轨迹；主回合运行时已让位为 null，
 * 由调用方决定不渲染）。挂载位置：顶栏 Column 内 TaskFloat 之后。
 */
@Composable
internal fun SubagentBadge(
    badge: com.haoai.agent.ui.BgBadge,
    onOpenDetail: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val tracks = badge.tracks
    val runN = tracks.count { it.state == "RUNNING" || it.state == "STOPPING" }
    val doneN = tracks.count { it.state == "DONE" }
    val errN = tracks.count { it.state == "ERROR" }
    val anyErr = errN > 0
    val totalTok = tracks.sumOf { it.tokensUsed }
    val prog = when {
        tracks.isEmpty() -> 0f
        badge.running -> runN.toFloat() / tracks.size
        else -> 1f
    }
    // drawBehind 里不能读 Theme——预先取色（2509 行同款坑）
    val progTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    val progFillColor =
        if (anyErr) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Column(horizontalAlignment = Alignment.End) {
        // ── chip：Box 承载底部进度条 ──
        Box(
            Modifier
                .height(32.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f))
                .border(
                    1.dp,
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
                    RoundedCornerShape(999.dp)
                )
                .drawBehind {
                    // 底部 2dp 进度条：轨道 + 运行/完成比例（圆头）
                    val h = 2.dp.toPx()
                    val y = size.height - 3.dp.toPx() - h / 2f
                    val x0 = 10.dp.toPx()
                    val x1 = size.width - 10.dp.toPx()
                    drawLine(progTrackColor, Offset(x0, y), Offset(x1, y), h, StrokeCap.Round)
                    if (prog > 0f) {
                        drawLine(
                            progFillColor, Offset(x0, y),
                            Offset(x0 + (x1 - x0) * prog.coerceIn(0f, 1f), y), h, StrokeCap.Round
                        )
                    }
                }
                .clickable { expanded = !expanded },
            contentAlignment = Alignment.CenterStart
        ) {
            Row(
                Modifier.padding(start = 7.dp, end = 9.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                SubagentFaceStack(tracks)
                Text(
                    if (badge.running) "$runN/${tracks.size} 进行中"
                    else if (anyErr) "${tracks.size} 路 · 有失败"
                    else "${tracks.size} 路已完成",
                    fontSize = 10.5.sp, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
                )
                Text("›", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        // ── 展开面板 ──
        androidx.compose.animation.AnimatedVisibility(
            visible = expanded,
            enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.expandVertically(),
            exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.shrinkVertically()
        ) {
            Column(
                Modifier
                    .padding(top = 6.dp)
                    .width(238.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
                        RoundedCornerShape(14.dp)
                    )
                    .padding(horizontal = 8.dp, vertical = 8.dp)
            ) {
                // 汇总头
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 2.dp, end = 2.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "子代理",
                        fontSize = 10.5.sp, fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        buildString {
                            append("${tracks.size} 路")
                            if (runN > 0) append(" · $runN 进行中")
                            if (doneN > 0) append(" · $doneN 完成")
                            if (errN > 0) append(" · $errN 失败")
                        },
                        fontSize = 9.5.sp, fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // 列表：150dp 内滚 + 边界钳制（2026-10-22 删 DstOut 渐隐：
                // 同 pill 组——白色渐隐误伤末行内容，用户要求去掉）
                Box(
                    Modifier
                        .fillMaxWidth()
                ) {
                    val listScroll = rememberScrollState()
                    Column(
                        Modifier
                            .heightIn(max = 150.dp)
                            .panelBoundaryClamp(listScroll)
                            .verticalScroll(listScroll),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        tracks.forEach { sub ->
                            BadgeRow(sub = sub) { onOpenDetail(sub.id) }
                        }
                    }
                }
                // 脚：合计 token + 查看完整过程
                val footLineColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .drawBehind {
                            drawLine(
                                color = footLineColor,
                                start = Offset(0f, 0f),
                                end = Offset(size.width, 0f),
                                strokeWidth = 1.dp.toPx()
                            )
                        },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (totalTok >= 1000) "合计 ${totalTok / 1000}k tok" else "合计 $totalTok tok",
                        fontSize = 9.sp, fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        "查看完整过程",
                        fontSize = 9.5.sp, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clickable { tracks.firstOrNull()?.let { onOpenDetail(it.id) } }
                            .padding(vertical = 3.dp)
                    )
                }
            }
        }
    }
}

/** chip 脸谱堆叠：≤3 路全露脸谱；超过 3 路第三个位置显示 "+N"。手算宽度的 Box 叠层。 */
@Composable
private fun SubagentFaceStack(tracks: List<com.haoai.agent.ui.UiSubagentDetail>) {
    val shown = tracks.take(3)
    if (shown.isEmpty()) return
    val step = 11.dp
    Box(
        Modifier
            .width(17.dp + step * (shown.size - 1))
            .height(17.dp)
    ) {
        shown.forEachIndexed { i, sub ->
            val more = i == 2 && tracks.size > 3
            Box(
                Modifier
                    .offset(x = step * i)
                    .size(17.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(
                        if (more) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                        else subagentFaceColor(sub.index)
                    )
                    .border(1.5.dp, MaterialTheme.colorScheme.surface, RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (more) "+${tracks.size - 2}" else "${sub.index}",
                    fontSize = if (more) 7.5.sp else 8.5.sp,
                    lineHeight = if (more) 9.sp else 10.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = if (more) MaterialTheme.colorScheme.onSurfaceVariant else Color.White,
                    style = TextStyle(
                        platformStyle = PlatformTextStyle(includeFontPadding = false)
                    )
                )
            }
        }
    }
}

/** 面板单行 v2（2026-10-21，38dp 两行结构）：脸谱 18dp + 身份行 + 简报行；运行中淡绿底。 */
@Composable
private fun BadgeRow(
    sub: com.haoai.agent.ui.UiSubagentDetail,
    onClick: () -> Unit
) {
    val running = sub.state == "RUNNING" || sub.state == "STOPPING"
    val (capLabel, capTint) = when (sub.state) {
        "RUNNING", "STOPPING" -> "工作中" to MaterialTheme.colorScheme.primary
        "DONE" -> "已完成" to Color(0xFF3FAE5C)
        "ERROR" -> "失败" to MaterialTheme.colorScheme.error
        else -> "已终止" to Color(0xFFF08A0C)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .height(38.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (running) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                else Color.Transparent
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Box(
            Modifier
                .size(18.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(subagentFaceColor(sub.index)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "${sub.index}",
                fontSize = 8.5.sp,
                // 2026-10-21：关 includeFontPadding + lineHeight 匹配框高（18dp 框）
                lineHeight = 10.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White,
                style = TextStyle(
                    platformStyle = PlatformTextStyle(includeFontPadding = false)
                )
            )
        }
        Column(Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Text(
                    "子代理 ${sub.index}/${sub.total}",
                    fontSize = 10.sp, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
                )
                Text(
                    capLabel,
                    fontSize = 8.5.sp, fontWeight = FontWeight.Bold,
                    color = capTint,
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(capTint.copy(alpha = 0.10f))
                        .padding(horizontal = 6.dp, vertical = 1.5.dp)
                )
            }
            Text(
                text = when {
                    running && !sub.currentTool.isNullOrBlank() -> sub.currentTool!!
                    sub.state == "ERROR" ->
                        "失败：${(sub.finalResult ?: sub.lastAssistant).ifBlank { sub.task }.take(26)}"
                    sub.state == "DONE" ->
                        sub.finalResult?.take(36)?.replace('\n', ' ')
                            ?: sub.lastAssistant.ifBlank { sub.task }.take(36).replace('\n', ' ')
                    sub.lastAssistant.isNotBlank() -> sub.lastAssistant.take(36).replace('\n', ' ')
                    sub.task.isNotBlank() -> sub.task
                    else -> "等待开始"
                },
                fontSize = 9.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        SubagentStateTail(sub.state)
    }
}

/**
 * 浮标详情弹层：单路 face 标题 + 状态胶囊 + 四块时间线（复用 SubagentTimeline）。
 * 数据源 = subagentDetailById（回合内链卡快照优先，回合外回落协调器轨迹活体，
 * 1s ticker 刷新 → 弹层内容实时跟进）。
 */
@Composable
internal fun SubagentBadgeDetailSheet(
    detail: com.haoai.agent.ui.UiSubagentDetail,
    backdrop: com.kyant.backdrop.backdrops.LayerBackdrop,
    onDismiss: () -> Unit,
    onStop: (String) -> Unit = {}
) {
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
                .padding(top = 4.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    Modifier
                        .size(24.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(subagentFaceColor(detail.index)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "${detail.index}",
                        fontSize = 11.sp,
                        // 2026-10-21：关 includeFontPadding + lineHeight 匹配框高（24dp 框）
                        lineHeight = 13.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.White,
                        style = TextStyle(
                            platformStyle = PlatformTextStyle(includeFontPadding = false)
                        )
                    )
                }
                Text(
                    "子代理 ${detail.index}/${detail.total}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                // 状态胶囊
                val (label, tint) = when (detail.state) {
                    "RUNNING", "STOPPING" -> "工作中" to MaterialTheme.colorScheme.primary
                    "DONE" -> "已完成" to Color(0xFF3FAE5C)
                    "ERROR" -> "失败" to MaterialTheme.colorScheme.error
                    else -> "已终止" to Color(0xFFF08A0C)
                }
                Text(
                    label,
                    fontSize = 10.sp, fontWeight = FontWeight.Bold,
                    color = tint,
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(tint.copy(alpha = 0.1f))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
            SubagentTimeline(
                state = detail.state,
                task = detail.task,
                steps = detail.steps,
                lastAssistant = detail.lastAssistant,
                finalResult = detail.finalResult,
                tokensUsed = detail.tokensUsed,
                onStop = onStop,
                stopId = if (detail.state == "RUNNING") detail.id else ""
            )
        }
    }
}
