package com.haoai.agent.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.haoai.agent.agent.tools.snapshot.FileSnapshot
import com.haoai.agent.platform.FileEntry
import com.haoai.agent.ui.ChatRow
import com.haoai.agent.ui.UiFilePath
import com.haoai.agent.ui.theme.haoIsDark

/**
 * 工作区共享壳（方案 B 落地；设计稿 `.test-work/sidebar-redesign/index.html` 2026-10-11 定稿）。
 *
 * 此前「目录/产出/版本」是三个各写一份的独立全屏 Dialog，`0xFF10151A` 深壳硬编码抄了三遍
 * ——浅色 App 里全是黑底，被用户点名"像硬缝上去的"。本版：一个壳三个 Tab；壳色**跟随 App
 * 深浅模式**（[workspaceShellTheme]）；绿色强调走 `colorScheme.primary`（换主题种子联动）。
 * 命名定稿：抽屉入口一行「工作区」；Tab 短词 目录/产出/历史。底部信息条已删——
 * 手机手势条会遮挡，统计改壳头副标题。
 *
 * 两种宿主：聊天页（全三 Tab，session 作用域的产出/历史）与设置·工作区页（tabs=[Tree]，
 * 空间作用域只看目录）——共用同一视图与管线（批3a listDir/walk/定位）。
 */
enum class WorkspaceTab { Tree, Outputs, History }

/** 壳色双档（深档=原三个 Dialog 抄写值；浅档=HaoLightColors 口径，diff 色与 DiffBlockView 同档）。 */
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
    val dark = haoIsDark()
    val primary = MaterialTheme.colorScheme.primary
    return if (dark) {
        WorkspaceShellTheme(
            bg = Color(0xFF10151A), text = Color(0xFFE8EEEA), sub = Color(0xFF9AA8A0),
            body = Color(0xFFC8D2CD), faint = Color(0xFF5C6A64), searchBg = Color(0xFF1B232C),
            folder = Color(0xFF7BC6A5), accent = primary, accentSoft = primary.copy(alpha = 0.20f),
            del = Color(0xFFE88A8A), delBg = Color(0x1FE88A8A),
            info = Color(0xFF8AB4F8), ctx = Color(0xFF8A968F),
        )
    } else {
        WorkspaceShellTheme(
            bg = Color(0xFFFDFEFF), text = Color(0xFF171B26), sub = Color(0xFF49526A),
            body = Color(0xFF3C445A), faint = Color(0xFF7A84A0), searchBg = Color(0xFFEDF1F7),
            folder = Color(0xFF2F7D4F), accent = primary, accentSoft = primary.copy(alpha = 0.12f),
            del = Color(0xFF8C1D18), delBg = Color(0x14C62828),
            info = Color(0xFF1A63C9), ctx = Color(0xFF67708A),
        )
    }
}

/**
 * 共享壳。Tab 态存活于打开期；查看器「在目录中定位」/「版本历史」上抛的
 * locateRel / historyFile 会自动把 Tab 切过去（壳没开就先开壳）。
 * 点文件开只读查看器（[FileViewerDialog]）叠在壳上，关掉回壳——各 Tab 共用一枚 viewer。
 */
@Composable
internal fun WorkspaceShellDialog(
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

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .background(theme.bg)
                .statusBarsPadding()
        ) {
            // 壳头：标题 + 副标题（统计随 Tab 换口径；底部信息条已删，统计在此） + 关闭
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp)
                    .padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "工作区",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = theme.text
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    subtitleOf(currentTab, workspaceName, backendReady, outputEntries),
                    fontSize = 11.sp,
                    color = theme.sub,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "关闭",
                    fontSize = 12.sp,
                    color = theme.sub,
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .clickable { onDismiss() }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
            // Tab 条（多于一个 Tab 才画——设置页宿主=纯目录，无条）
            if (tabs.size > 1) {
                Row(
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
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (on) theme.accentSoft else Color.Transparent)
                                .clickable { tab = t.name }
                                .padding(vertical = 6.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
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
            FileViewerDialog(ref = ref, onDismiss = { viewerRel = null })
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
