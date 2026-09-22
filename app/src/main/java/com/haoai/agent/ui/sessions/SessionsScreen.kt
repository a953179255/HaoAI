package com.haoai.agent.ui.sessions

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.haoai.agent.data.StoredSession
import com.haoai.agent.ui.ChatViewModel
import com.haoai.agent.ui.common.GlassAlertDialog
import com.haoai.agent.ui.common.GlassCard
import com.haoai.agent.ui.common.GlassPageBar
import com.haoai.agent.ui.common.SwipeRevealCard
import com.haoai.agent.ui.common.glassFieldColors
import com.kyant.backdrop.backdrops.LayerBackdrop
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import kotlin.math.abs
import java.util.Date
import java.util.Locale
import com.haoai.agent.ui.common.appLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.luminance
import com.haoai.agent.ui.common.CompactGlassField
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material.icons.filled.Restore

/** 独立「全部会话」页：搜索过滤（会话/回收站共用）+ 左右滑动切换 + 置顶/重命名/回收站管理。 */
@Composable
fun SessionsScreen(
    vm: ChatViewModel,
    backdrop: LayerBackdrop,
    onBack: () -> Unit,
    /** 全局壁纸开时传入：页面自带对齐的壁纸底（与其他二级页一致） */
    wallpaper: android.graphics.Bitmap? = null
) {
    val sessions by vm.sessions.collectAsState()
    val deletedSessions by vm.deletedSessions.collectAsState()
    val activeId = vm.session.collectAsState().value?.id

    var query by rememberSaveable { mutableStateOf("") }
    var showTrash by rememberSaveable { mutableStateOf(false) }
    // 会话↔回收站切换进度（顶层持有）：0=会话，1=回收站。
    // ⚠️必须放顶层——放在列表分支内部时，分支重组会重建该组合实例，
    // animateFloatAsState 直接取终值 = 无动画（实测：时长拉到 2000ms 仍 1 帧跳变）。
    val switchProgress by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (!showTrash) 0f else 1f,
        animationSpec = androidx.compose.animation.core.tween(340),
        label = "sessionsSwitch"
    )
    val fmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
    // 待彻底删除的会话：误触不可逆操作前先确认
    var pendingPurge by remember { mutableStateOf<StoredSession?>(null) }
    // 重命名目标；清空回收站确认
    var renameTarget by remember { mutableStateOf<StoredSession?>(null) }
    var confirmEmptyTrash by remember { mutableStateOf(false) }
    // 当前滑出操作按钮的会话（同时只允许一张）
    var openCardId by remember { mutableStateOf<String?>(null) }
    // 5.4 本会话用量弹窗目标
    var usageTarget by remember { mutableStateOf<StoredSession?>(null) }

    // 系统返回手势直接回聊天页
    androidx.activity.compose.BackHandler { onBack() }

    // 300ms 防抖：输入中不重算结果；query 变化会重启协程，旧 delay 自动作废
    var debouncedQuery by remember { mutableStateOf("") }
    LaunchedEffect(query) {
        if (query.isBlank()) {
            debouncedQuery = ""
        } else {
            delay(300)
            debouncedQuery = query.trim()
        }
    }
    val searching = query.isNotBlank() && query.trim() != debouncedQuery

    Box(
        Modifier
            .fillMaxSize()
            // 平移转场页面必须有实底：否则转场中本页滑入时透出下层聊天页
            // （壁纸/抽屉）；全局壁纸开时铺对齐壁纸（与 backdrop 采样同源同位）
            .background(MaterialTheme.colorScheme.background)
    ) {
        // ★ 采样宿主（2026-09-22 挂载铁律）：只录背景层，玻璃元素在宿主外。
        // 宿主子树内含玻璃 = RenderNode 成环 = SIGSEGV（详见 SettingsScreen 同款注释）
        // ★ 页面专属采样画布：转场中主页/子页并存若共用共享画布，两壳挂载节点每帧
        // 互相 record 覆盖 → 玻璃采样错乱 = 磨砂消失约 1 秒（转场结束恢复）。
        // 每页自建 rememberAppBackdrop，挂载与采样都在本页，互不干扰
        val localBackdrop = com.haoai.agent.ui.common.rememberAppBackdrop(
            wallpaper,
            dark = MaterialTheme.colorScheme.background.luminance() < 0.5f,
            baseTop = MaterialTheme.colorScheme.background,
            baseBottom = MaterialTheme.colorScheme.background
        )
        // ★ 首帧预热采样层：新页首帧采样层为空（挂载节点 draw 后才 record），
        // 转场动画中玻璃会消失几帧；组合提交时先 record 壁纸打底，首帧即磨砂
        var glassHostSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
        // 预热只做一次：切换/动画期页面每帧重组，若每次都 record 壁纸，会与宿主节点的
        // record 交替覆盖采样层 → 背景壁纸抽搐（用户实锤）
        var glassPreheated by remember { mutableStateOf(false) }
        val glassHostSizeDensity = androidx.compose.ui.platform.LocalDensity.current
        val glassHostSizeLayoutDir = androidx.compose.ui.platform.LocalLayoutDirection.current
        androidx.compose.runtime.SideEffect {
            if (!glassPreheated && wallpaper != null && glassHostSize.width > 0 && glassHostSize.height > 0) {
                val img = wallpaper.asImageBitmap()
                glassPreheated = true
                localBackdrop.graphicsLayer.record(glassHostSizeDensity, glassHostSizeLayoutDir, glassHostSize) {
                    drawImage(
                        img,
                        dstOffset = androidx.compose.ui.unit.IntOffset.Zero,
                        dstSize = androidx.compose.ui.unit.IntSize(glassHostSize.width, glassHostSize.height)
                    )
                }
            }
        }
        Box(Modifier.matchParentSize().appLayer(localBackdrop).onSizeChanged { glassHostSize = it }) {
            if (wallpaper != null) {
                val wpImage = androidx.compose.runtime.remember(wallpaper) { wallpaper.asImageBitmap() }
                Image(
                    bitmap = wpImage,
                    contentDescription = null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.matchParentSize()
                )
                // 壁纸压暗层：卡片之外直接压着壁纸的内容靠它恢复对比度
                com.haoai.agent.ui.common.HaoWallpaperScrim(wallpaper != null, Modifier.matchParentSize())
            }
        }
    Column(
        Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            // ⚠️不要加 padding(top)：顶栏玻璃必须从屏幕最顶端铺下来（通栏，
            // 状态栏文字浮在玻璃上）。加 6dp 会在状态栏与顶栏之间留一条"没磨砂"的缝
            // （用户截图实锤，与聊天页通栏顶栏不一致）
            .clickable(interactionSource = null, indication = null) {
                // 点页面空白：呼出中只收起呼出
                if (openCardId != null) openCardId = null
            }
    ) {
        GlassPageBar(
            backdrop = localBackdrop,
            title = if (showTrash) "回收站" else "全部会话",
            onBack = {
                // 呼出操作按钮期间点返回：只收起呼出，不离开页面
                if (openCardId != null) openCardId = null else onBack()
            },
            modifier = Modifier
        )

        // 搜索框常驻：两个视图共用（回收站内可搜索后精准彻底删除）
        Box {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = {
                Text(
                    "搜索标题与消息内容",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f)
                )
            },
            leadingIcon = {
                Icon(
                    Icons.Filled.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                )
            },
            trailingIcon = {
                if (searching) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )
                } else if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }, modifier = Modifier.size(34.dp)) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "清空",
                            tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                            modifier = Modifier.size(17.dp)
                        )
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            colors = glassFieldColors(),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        )
        if (openCardId != null) {
            Box(
                Modifier
                    .matchParentSize()
                    .clickable(interactionSource = null, indication = null) { openCardId = null }
            )
        }
        }

        // 视图切换（与滑动双向同步）+ 回收站清空入口
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            com.haoai.agent.ui.common.LiquidTabRow(
                tabs = listOf("会话 ${sessions.size}", "回收站 ${deletedSessions.size}"),
                selectedIndex = if (showTrash) 1 else 0,
                onSelected = { i ->
                    // 呼出中只收起呼出，不切页
                    if (openCardId != null) openCardId = null
                    showTrash = i == 1
                },
                backdrop = localBackdrop,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.weight(1f))
            // 常驻占位：若按条件增删，Row 权重空间重分配会让 LiquidTabRow 宽度
            // 跳变（切回收站胶囊缩小、切回又放大）。不可见时 alpha(0)+禁用，宽度恒定
            val trashActionVisible = showTrash && deletedSessions.isNotEmpty()
            TextButton(
                onClick = { confirmEmptyTrash = true },
                enabled = trashActionVisible,
                modifier = Modifier.alpha(if (trashActionVisible) 1f else 0f)
            ) {
                Text("清空", color = MaterialTheme.colorScheme.error)
            }
        }

        // 会话/回收站两页条件渲染 + 左右滑切页：不用 HorizontalPager——其页面
        // graphicsLayer 平移会被玻璃卡片 backdrop 采样，在卡片四角回画成矩形阴影
        // （与抽屉弃用 ModalNavigationDrawer、SwipeRevealCard 弃 graphicsLayer 同因）
        val sessionsPage = !showTrash
        val baseList = if (sessionsPage) sessions else deletedSessions
        // 双模式搜索：标题命中 + 内容命中（带摘要）。内存过滤，量级为个人会话数，
        // remember 缓存避免重组重算
        val searchResult = remember(debouncedQuery, baseList) {
            searchSessions(baseList, debouncedQuery)
        }
        val contentSnippets = remember(searchResult) {
            searchResult.contentHits.associate { it.session.id to it.snippet }
        }
        val list = if (debouncedQuery.isBlank()) baseList
        else searchResult.titleHits + searchResult.contentHits.map { it.session }
        // 手势闭包需读最新状态：pointerInput(Unit) 的 block 不随重组重启，
        // 直接闭包捕获会拿到旧值（Compose 经典坑）
        val openCardIdNow by androidx.compose.runtime.rememberUpdatedState(openCardId)
        val showTrashNow by androidx.compose.runtime.rememberUpdatedState(showTrash)
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .pointerInput(Unit) { detectTapGestures { openCardId = null } }
                .pointerInput(Unit) {
                    // 左滑→回收站，右滑→会话；卡片消费掉的水平拖拽不会到达此处
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var tx = 0f; var ty = 0f
                        while (true) {
                            val e = awaitPointerEvent()
                            val c = e.changes.firstOrNull { it.id == down.id } ?: break
                            if (c.changedToUp()) break
                            tx += c.positionChange().x; ty += c.positionChange().y
                            if (abs(tx) > viewConfiguration.touchSlop || abs(ty) > viewConfiguration.touchSlop) {
                                if (abs(tx) > abs(ty)) {
                                    // 方向语义（2026-09-22 用户定案：每页各自最顺手）：
                                    // · 全部会话页：**左滑 → 去回收站**；**右滑 → 呼出卡片**（按钮在左，
                                    //   卡片右移，与滑动方向一致）。
                                    // · 回收站页：**左滑 → 呼出卡片**（按钮在右，卡片左移）；
                                    //   **右滑 → 返回全部会话**（"右滑返回"符合直觉）。
                                    // 页面只做兜底：有卡片展开时，非呼出方向的滑动收起它
                                    //（卡片 visible 区靠 offset{} 位移、命中测试在原位，
                                    //  手指落在可见区外侧时无人接管 → 页面兜底，否则"滑了没反应"）。
                                    val cardOpen = openCardIdNow != null
                                    if (!showTrashNow && tx < 0f) {
                                        // 会话页左滑 → 回收站
                                        openCardId = null
                                        showTrash = true
                                    } else if (showTrashNow && tx > 0f) {
                                        // 回收站右滑 → 返回会话
                                        openCardId = null
                                        showTrash = false
                                    } else if (cardOpen) {
                                        openCardId = null
                                    }
                                }
                                break
                            }
                        }
                    }
                }
        ) {
                if (list.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            when {
                                !sessionsPage && query.isBlank() -> "回收站是空的。删除的会话会保留 7 天，之后自动清理。"
                                query.isBlank() -> "还没有历史会话"
                                else -> "没有匹配的标题或内容"
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 24.dp)
                        )
                    }
                } else {
                    Box(Modifier.fillMaxSize()) {
                    // 会话列表：向左滑出 + 淡出。
                    // ⚠️仅在动画期组合：稳定态只保留当前页一个列表 —— 两个 LazyColumn 常驻
                    // 会使 GPU 负担翻倍（模拟器实测 janky 79%/GPU 90th 4950ms 的元凶之一）
                    if (switchProgress < 0.995f) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                translationX = -switchProgress * size.width * 0.22f
                                alpha = 1f - switchProgress
                            }
                    ) {
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp)
                    ) {
                        items(list, key = { it.id }) { s ->
                            val active = s.id == activeId
                            SwipeRevealCard(
                                isOpen = openCardId == s.id,
                                anyOpen = openCardId != null,
                                onClick = {
                                    vm.selectSession(s.id)
                                    onBack()
                                },
                                onOpenChange = { open -> openCardId = if (open) s.id else null },
                                openWidth = 200.dp,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
                                actions = {
                                    FilledIconButton(
                                        onClick = { usageTarget = s; openCardId = null },
                                        modifier = Modifier.size(44.dp),
                                        // 按钮铁律：实底 + 对比字（此前 0.10 淡底几乎看不见——用户实锤）
                                        colors = IconButtonDefaults.filledIconButtonColors(
                                            containerColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f),
                                            contentColor = MaterialTheme.colorScheme.background
                                        )
                                    ) {
                                        Icon(
                                            Icons.Filled.BarChart,
                                            contentDescription = "本会话用量",
                                            modifier = Modifier.size(19.dp)
                                        )
                                    }
                                    FilledIconButton(
                                        onClick = {
                                            vm.pinSession(s.id)
                                            openCardId = null
                                        },
                                        modifier = Modifier.size(44.dp),
                                        colors = IconButtonDefaults.filledIconButtonColors(
                                            // 实底：未置顶=中性实底；已置顶=主色实底（当前处于置顶态）
                                            containerColor = if (s.pinned) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f),
                                            contentColor = if (s.pinned) MaterialTheme.colorScheme.onPrimary
                                            else MaterialTheme.colorScheme.background
                                        )
                                    ) {
                                        Icon(
                                            Icons.Filled.PushPin,
                                            contentDescription = if (s.pinned) "取消置顶" else "置顶",
                                            modifier = Modifier.size(19.dp)
                                        )
                                    }
                                    FilledIconButton(
                                        onClick = { renameTarget = s; openCardId = null },
                                        modifier = Modifier.size(44.dp),
                                        colors = IconButtonDefaults.filledIconButtonColors(
                                            containerColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f),
                                            contentColor = MaterialTheme.colorScheme.background
                                        )
                                    ) {
                                        Icon(Icons.Filled.Edit, contentDescription = "重命名", modifier = Modifier.size(19.dp))
                                    }
                                    FilledIconButton(
                                        onClick = {
                                            vm.deleteSession(s.id)
                                            openCardId = null
                                        },
                                        modifier = Modifier.size(44.dp),
                                        colors = IconButtonDefaults.filledIconButtonColors(
                                            containerColor = MaterialTheme.colorScheme.error,
                                            contentColor = MaterialTheme.colorScheme.onError
                                        )
                                    ) {
                                        Icon(Icons.Filled.Delete, contentDescription = "删除", modifier = Modifier.size(20.dp))
                                    }
                                },
                                content = { cardClick ->
                                    GlassCard(
                                        onClick = cardClick,
                                        backdrop = localBackdrop,
                                        shape = RoundedCornerShape(14.dp),
                                        // 与全项目内容卡同刻度（壁纸模式自动 0.82）；
                                        // 当前会话用品牌色 tint 区分，不再用"更透"区分
                                        surfaceAlpha = com.haoai.agent.ui.theme.haoCardSurfaceAlpha(),
                                        tint = if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.22f) else null,
                                        lensRadius = 14.dp,
                                        pressScale = true,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            Modifier.padding(start = 14.dp, end = 14.dp, top = 9.dp, bottom = 9.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(Modifier.weight(1f)) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    if (s.pinned) {
                                                        Icon(
                                                            Icons.Filled.PushPin,
                                                            contentDescription = "已置顶",
                                                            tint = MaterialTheme.colorScheme.primary,
                                                            modifier = Modifier
                                                                .size(12.dp)
                                                                .padding(end = 2.dp)
                                                        )
                                                        Spacer(Modifier.size(3.dp))
                                                    }
                                                    Text(
                                                        highlightedAnnotatedString(s.title, debouncedQuery),
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                                        color = if (active) MaterialTheme.colorScheme.primary
                                                        else MaterialTheme.colorScheme.onBackground,
                                                        maxLines = 1
                                                    )
                                                }
                                                Text(
                                                    "${fmt.format(Date(s.updatedAt))} · ${s.messages.size} 条",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f)
                                                )
                                                // 内容命中摘要：±50 字窗口，命中词高亮
                                                contentSnippets[s.id]?.let { snippet ->
                                                    Text(
                                                        highlightedAnnotatedString(snippet, debouncedQuery),
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                                                        maxLines = 2,
                                                        modifier = Modifier.padding(top = 2.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            )
                        }
                    }
                    }
                    }
                    }
                    // 回收站列表：从右滑入 + 淡入（同样只在动画期组合）
                    if (switchProgress > 0.005f) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                translationX = (1f - switchProgress) * size.width * 0.22f
                                alpha = switchProgress
                            }
                    ) {
                        LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp)
                    ) {
                        items(list, key = { it.id }) { s ->
                            val daysLeft = 7 - ((System.currentTimeMillis() - s.deletedAt) / (24 * 60 * 60 * 1000L)).toInt()
                            // 与会话页同一套滑动呼出 + 同一套 44dp 圆钮（主次分层：恢复=中性实底、
                            // 彻底删除=error 实底）。卡上不再放文字按钮——此前 TextButton 的触摸目标
                            // 把卡片撑高，与会话卡高度不一致（用户实锤）；移除后高度自动对齐
                            SwipeRevealCard(
                                isOpen = openCardId == s.id,
                                anyOpen = openCardId != null,
                                onClick = {},
                                onOpenChange = { open -> openCardId = if (open) s.id else null },
                                openWidth = 150.dp,
                                // 回收站：操作区在卡片右侧 → **左滑呼出**（右滑留给"返回会话"）
                                actionsAtEnd = true,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
                                actions = {
                                    FilledIconButton(
                                        onClick = {
                                            vm.restoreSession(s.id)
                                            openCardId = null
                                        },
                                        modifier = Modifier.size(44.dp),
                                        colors = IconButtonDefaults.filledIconButtonColors(
                                            containerColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f),
                                            contentColor = MaterialTheme.colorScheme.background
                                        )
                                    ) {
                                        Icon(Icons.Filled.Restore, contentDescription = "恢复", modifier = Modifier.size(20.dp))
                                    }
                                    FilledIconButton(
                                        onClick = {
                                            pendingPurge = s
                                            openCardId = null
                                        },
                                        modifier = Modifier.size(44.dp),
                                        colors = IconButtonDefaults.filledIconButtonColors(
                                            containerColor = MaterialTheme.colorScheme.error,
                                            contentColor = MaterialTheme.colorScheme.onError
                                        )
                                    ) {
                                        Icon(Icons.Filled.Delete, contentDescription = "彻底删除", modifier = Modifier.size(20.dp))
                                    }
                                },
                                content = { cardClick ->
                                    GlassCard(
                                        onClick = cardClick,
                                        backdrop = localBackdrop,
                                        shape = RoundedCornerShape(14.dp),
                                        surfaceAlpha = com.haoai.agent.ui.theme.haoCardSurfaceAlpha(),
                                        lensRadius = 14.dp,
                                        pressScale = true,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            Modifier.padding(start = 14.dp, end = 14.dp, top = 9.dp, bottom = 9.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(Modifier.weight(1f)) {
                                                Text(
                                                    highlightedAnnotatedString(s.title, debouncedQuery),
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = MaterialTheme.colorScheme.onBackground,
                                                    maxLines = 1
                                                )
                                                Text(
                                                    "${fmt.format(Date(s.deletedAt))} 删除 · 剩 $daysLeft 天 · ${s.messages.size} 条",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
                                                    maxLines = 1
                                                )
                                                contentSnippets[s.id]?.let { snippet ->
                                                    Text(
                                                        highlightedAnnotatedString(snippet, debouncedQuery),
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                                                        maxLines = 2,
                                                        modifier = Modifier.padding(top = 2.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            )
                        }
                    }
                    }
                    }
            }
            }
    }

    }

    // 5.4 本会话用量弹窗
    usageTarget?.let { target ->
        val su = remember(target.id) { com.haoai.agent.data.UsageLedger.summarize(target.id) }
        com.haoai.agent.ui.common.GlassAlertDialog(
            backdrop = backdrop,
            title = "本会话用量",
            confirmLabel = "好",
            onConfirm = { usageTarget = null },
            onDismiss = { usageTarget = null }
        ) {
            Column {
                Text("「${target.title}」", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Token 合计 ${su.monthIn + su.monthOut}（全量口径）\n输入 ${su.monthIn} · 输出 ${su.monthOut}\n调用记录 ${su.entries} 条",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    pendingPurge?.let { target ->
        GlassAlertDialog(
            backdrop = backdrop,
            title = "彻底删除会话",
            onDismiss = { pendingPurge = null },
            confirmLabel = "彻底删除",
            dismissLabel = "取消",
            danger = true,
            onConfirm = {
                vm.deleteSessionForever(target.id)
                pendingPurge = null
            }
        ) {
            Text(
                "「${target.title}」（${target.messages.size} 条消息）将被永久删除，无法恢复。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
        }
    }

    renameTarget?.let { target ->
        var renameValue by remember(target.id) { mutableStateOf(target.title) }
        GlassAlertDialog(
            backdrop = backdrop,
            title = "重命名会话",
            onDismiss = { renameTarget = null },
            confirmLabel = "保存",
            dismissLabel = "取消",
            onConfirm = {
                vm.renameSession(target.id, renameValue)
                renameTarget = null
            }
        ) {
            CompactGlassField(
                value = renameValue,
                onValueChange = { renameValue = it.take(50) },
                label = "会话名",
                modifier = Modifier.fillMaxWidth()
            )
        }
    }

    if (confirmEmptyTrash) {
        GlassAlertDialog(
            backdrop = backdrop,
            title = "清空回收站",
            onDismiss = { confirmEmptyTrash = false },
            confirmLabel = "全部彻底删除",
            dismissLabel = "取消",
            danger = true,
            onConfirm = {
                vm.emptyTrash()
                confirmEmptyTrash = false
            }
        ) {
            Text(
                "回收站内 ${deletedSessions.size} 个会话将被永久删除，无法恢复。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
        }
    }
}
