package com.haoai.agent.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.haoai.agent.agent.tools.snapshot.FileSnapshot
import com.haoai.agent.platform.FileEntry
import com.haoai.agent.ui.ChatRow
import com.haoai.agent.ui.UiFilePath
import com.haoai.agent.ui.common.GlassPageBar
import com.haoai.agent.ui.theme.haoIsDark

/**
 * 工作区共享壳（方案 B 落地；设计稿 `.test-work/sidebar-redesign/index.html` 2026-10-11 定稿）。
 *
 * 此前「目录/产出/版本」是三个各写一份的独立全屏 Dialog，`0xFF10151A` 深壳硬编码抄了三遍
 * ——浅色 App 里全是黑底，被用户点名"像硬缝上去的"。本版：一个壳、三个 Tab；壳色由
 * [workspaceShellTheme] **从 MaterialTheme 派生**，自动跟随 App 深浅模式与主题色种子。
 * 命名定稿：抽屉入口一行「工作区」、Tab 短词 目录/产出/历史；底部信息条删除（手机手势条
 * 遮挡），统计进副标题行。
 *
 * ⚠️ 形态=**实底覆盖层 + 本页自带采样画布**（照抄 SessionsScreen「全部会话」页架构，
 * 用户 2026-10-11 点名对齐）：独立 Dialog 窗口采不到 backdrop；而借宿主页面（聊天页）
 * 画布又会让顶栏玻璃透出聊天内容。正解=本页画布只录本页底/壁纸，顶栏 [GlassPageBar]
 * 磨砂与设置页/全部会话页**同一权威组件、同一观感**。宿主子树内绝不放玻璃（SIGSEGV 环）。
 */
enum class WorkspaceTab { Tree, Outputs, History }

/**
 * 壳色档：一律取 MaterialTheme.colorScheme（深浅随 App 主题自动翻），
 * diff 增删走语义色（primary 系加 / error 系删）。folder/info 两枚是装饰性
 * 类别色，深浅各一档常量，保证压在两种底上都有对比。
 */
class WorkspaceShellTheme(
    val bg: Color,
    val text: Color,
    val sub: Color,
    val body: Color,
    val faint: Color,
    val searchBg: Color,
    val folder: Color,
    val accent: Color,
    val accentSoft: Color,
    val del: Color,
    val delBg: Color,
    val info: Color,
    val ctx: Color,
)

@Composable
fun workspaceShellTheme(): WorkspaceShellTheme {
    val cs = MaterialTheme.colorScheme
    val dark = haoIsDark()
    return WorkspaceShellTheme(
        bg = cs.background,
        text = cs.onBackground,
        sub = cs.onSurfaceVariant,
        body = cs.onSurface,
        faint = cs.outline,
        searchBg = cs.surfaceVariant,
        folder = if (dark) Color(0xFF7BC6A5) else Color(0xFF2F7D4F),
        accent = cs.primary,
        accentSoft = cs.primary.copy(alpha = if (dark) 0.20f else 0.12f),
        del = if (dark) Color(0xFFE88A8A) else Color(0xFF8C1D18),
        delBg = if (dark) Color(0x1FE88A8A) else Color(0x14C62828),
        info = if (dark) Color(0xFF8AB4F8) else Color(0xFF1A63C9),
        ctx = cs.onSurfaceVariant,
    )
}

/**
 * 共享壳。Tab 态存活于打开期；查看器「在目录中定位」/「版本历史」上抛的
 * locateRel / historyFile 会自动把 Tab 切过去（壳没开就先开壳）。
 * 点文件开只读查看器（[FileViewerDialog]）叠在壳上，关掉回壳——各 Tab 共用一枚 viewer。
 *
 * 架构=照抄「全部会话」页（SessionsScreen 同款，用户 2026-10-11 点名对齐）：
 * 实底 Box + **本页自带采样画布**（WallpaperBackdropHost）+ 页内玻璃全部采自己的画布。
 * 首版借宿主页面（聊天）的 backdrop 采到了聊天消息流——顶栏玻璃"透到聊天界面"，
 * 且右上关闭与左上返回同义重复。现：顶栏只有 ← 返回（关壳=回侧边栏，语义由
 * 调用方 onDismiss 落地）；玻璃磨砂的是本页底/壁纸，与底下聊天页再无关系。
 *
 * @param wallpaper 全局壁纸（null=无壁纸，画布即纯色底）；与设置/会话页同源。
 */
@Composable
internal fun WorkspaceShellDialog(
    wallpaper: android.graphics.Bitmap?,
    workspaceName: String,
    backendReady: Boolean,
    listDir: suspend (String) -> List<String>,
    walk: suspend () -> List<FileEntry>,
    resolve: (String) -> UiFilePath?,
    rows: List<ChatRow> = emptyList(),
    loadMetas: suspend () -> List<FileSnapshot.Meta> = { emptyList() },
    readVersion: suspend (callId: String, which: String) -> String? = { _, _ -> null },
    hasBefore: suspend (callId: String) -> Boolean = { false },
    locateRel: String? = null,
    onLocateConsumed: () -> Unit = {},
    historyFile: String? = null,
    onHistoryFileConsumed: () -> Unit = {},
    onViewDiff: (callId: String) -> Unit = {},
    tabs: List<WorkspaceTab> = WorkspaceTab.entries.toList(),
    /** 换乘钮能力位（会话宿主=true）；纯目录设置宿主传 false，查看器隐藏两枚钮。 */
    sessionScoped: Boolean = true,
    onDismiss: () -> Unit
) {
    val theme = workspaceShellTheme()
    var tab by rememberSaveable { mutableStateOf(tabs.first().name) }
    val currentTab = tabs.firstOrNull { it.name == tab } ?: tabs.first()
    // 换乘请求：谁来了就把 Tab 切过去（locate→目录，版本→历史）
    LaunchedEffect(locateRel, historyFile) {
        if (historyFile != null && WorkspaceTab.History in tabs) tab = WorkspaceTab.History.name
        else if (locateRel != null && WorkspaceTab.Tree in tabs) tab = WorkspaceTab.Tree.name
    }
    val outputEntries = remember(rows) { buildOutputEntries(rows) }
    var viewerRel by remember { mutableStateOf<String?>(null) }
    // 快照 metas 懒加载：只在第一次进历史 Tab 时读盘（切会话重开壳=新壳新读）
    var metas by remember { mutableStateOf<List<FileSnapshot.Meta>?>(null) }
    LaunchedEffect(currentTab) {
        if (currentTab == WorkspaceTab.History && metas == null) metas = loadMetas()
    }

    // 「全部会话」页同款架构（SessionsScreen 逐参对照）：实底 Box + 本页自带采样画布
    // （WallpaperBackdropHost，preheat 默认开：覆盖层首帧画布为空，预热防顶栏玻璃空帧）。
    // 首版借聊天页 backdrop 采到了消息流——顶栏"透到聊天界面"就是这个；本页画布只含
    // 本页底/壁纸，与聊天内容再无关系。⚠️ 铁律：宿主子树内绝不能放玻璃（SIGSEGV 环），
    // GlassPageBar 必须挂在本宿主之外的 Column 里（宿主 helper 的 Box 只录它自己的底）。
    androidx.activity.compose.BackHandler(onBack = onDismiss)
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        val localBackdrop = com.haoai.agent.ui.common.WallpaperBackdropHost(wallpaper)
        Column(Modifier.fillMaxSize()) {
            // 权威顶栏=与设置页/全部会话页同一 GlassPageBar：← 返回（关壳）。
            // 右上"关闭"已删——与返回同义重复（用户 2026-10-11 点名功能冲突）。
            // 返回语义由调用方 onDismiss 落地：聊天宿主=关壳+重开侧边栏（对照
            // 全部会话页 onBack=enterChat+drawer.open()），设置宿主=关壳回设置页。
            GlassPageBar(
                backdrop = localBackdrop,
                title = "工作区",
                onBack = onDismiss
            )
            // Tab 条（多于一个才画——设置页宿主=纯目录无条）。
            // 文字 textAlign=Center 居中：首版漏设被用户点名（bounds 实测偏差 36/22/8px）。
            if (tabs.size > 1) {
                androidx.compose.foundation.layout.Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(theme.searchBg)
                        .padding(3.dp)
                ) {
                    tabs.forEach { t ->
                        val on = t == currentTab
                        Text(
                            when (t) {
                                WorkspaceTab.Tree -> "目录"
                                WorkspaceTab.Outputs -> "产出"
                                WorkspaceTab.History -> "历史"
                            },
                            fontSize = 12.sp,
                            fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                            color = if (on) theme.accent else theme.sub,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (on) theme.accentSoft else Color.Transparent)
                                .clickable { tab = t.name }
                                .padding(vertical = 7.dp),
                        )
                    }
                }
            }
            // 统计副行（原底部信息条的归宿，随 Tab 换口径）——放内容区顶部，不挤顶栏。
            Text(
                subtitleOf(currentTab, workspaceName, backendReady, outputEntries),
                fontSize = 11.sp,
                color = theme.sub,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            )
            when (currentTab) {
                WorkspaceTab.Tree -> WorkspaceTreePane(
                    theme = theme,
                    backendReady = backendReady,
                    listDir = listDir,
                    walk = walk,
                    resolve = resolve,
                    locateRel = locateRel,
                    onLocateConsumed = onLocateConsumed,
                    onOpenFile = { viewerRel = it }
                )
                WorkspaceTab.Outputs -> WorkspaceOutputsPane(
                    theme = theme,
                    entries = outputEntries,
                    resolve = resolve,
                    onViewDiff = { onViewDiff(it); onDismiss() },
                    onOpenFile = { viewerRel = it }
                )
                WorkspaceTab.History -> WorkspaceHistoryPane(
                    theme = theme,
                    metas = metas,
                    readVersion = readVersion,
                    hasBefore = hasBefore,
                    preselect = historyFile,
                    onPreselectConsumed = onHistoryFileConsumed
                )
            }
        }
    }

    // 点文件 → 只读查看器（叠在壳之上，关掉回壳；各 Tab 共用）
    viewerRel?.let { rel ->
        resolve(rel)?.let { ref ->
            FileViewerDialog(
                ref = ref,
                onDismiss = { viewerRel = null },
                showTransferActions = sessionScoped
            )
        }
    }
}

private fun subtitleOf(
    tab: WorkspaceTab,
    workspaceName: String,
    backendReady: Boolean,
    outputEntries: List<OutputEntry>
): String = when (tab) {
    WorkspaceTab.Tree -> if (backendReady) workspaceName else "未绑定工作空间"
    WorkspaceTab.Outputs ->
        if (outputEntries.isEmpty()) "本会话还没有文件产出"
        else "${outputEntries.size} 个文件 · " +
            "+${outputEntries.sumOf { it.added }} −${outputEntries.sumOf { it.removed }}"
    WorkspaceTab.History -> "写前快照自动记录 · 任意两版可对比"
}
