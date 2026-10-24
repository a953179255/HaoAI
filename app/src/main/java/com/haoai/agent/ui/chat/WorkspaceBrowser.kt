package com.haoai.agent.ui.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.haoai.agent.agent.tools.snapshot.FileSnapshot
import com.haoai.agent.platform.FileEntry
import com.haoai.agent.ui.UiFilePath
import com.haoai.agent.ui.theme.HaoTone
import com.haoai.agent.ui.theme.haoIsDark
import com.haoai.agent.ui.theme.haoToneMain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 批4b（2026-10-23 文件管理瘦身·对标定稿）：工作区浏览单页——
 * 取代三 Tab 壳（目录/产出/历史），「Browse Chat Files」流派（OpenMinis）落地：
 * 扁平列表 + 面包屑钻入 + 搜索 + 长按菜单（复制路径/外部打开/删除）。
 *
 * 架构与退役壳同源（SessionsScreen 同款）：实底 Box + 本页自带采样画布
 * （WallpaperBackdropHost），玻璃只采本页底；顶栏只有 ← 返回。**宿主子树内
 * 不放玻璃层组件**（采样画布铁律，防 RenderNode 成环）。
 *
 * 只读口径不变：写通道只有聊天侧（审批流），本页仅删（带二次确认）；
 * 查看器复用 FileViewerDialog（链卡同款），产"主通路仍是消息流路径芯片。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun WorkspaceBrowserDialog(
    wallpaper: android.graphics.Bitmap?,
    workspaceName: String,
    backendReady: Boolean,
    listDir: suspend (String) -> List<String>,
    walk: suspend () -> List<FileEntry>,
    resolve: (String) -> UiFilePath?,
    /** 删除一行（宿主接 FileBackend.delete；返回 Result 供 toast）。 */
    deleteFile: suspend (String) -> Result<Unit>,
    /** 「在目录中定位」跳入：非空=切到其父目录并短暂高亮该文件。 */
    locateRel: String? = null,
    onLocateConsumed: () -> Unit = {},
    /** 空目录时的去向提示（设置页传「公共文档里还有 N 个文件…」；聊天侧不传）。 */
    emptyHint: String? = null,
    emptyActionLabel: String? = null,
    onEmptyAction: (() -> Unit)? = null,
    onDismiss: () -> Unit
) {
    val theme = workspaceShellTheme()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var curRel by remember { mutableStateOf("") }
    var names by remember { mutableStateOf<List<String>>(emptyList()) }
    var entries by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    /** 目录读取失败的真实原因（2026-10-24 改版：错误不再吞成「目录为空」假象）。 */
    var loadError by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var viewerRel by remember { mutableStateOf<String?>(null) }
    var menuRow by remember { mutableStateOf<BrowserRow?>(null) }
    var highlightRel by remember { mutableStateOf<String?>(null) }
    var toastMsg by remember { mutableStateOf<String?>(null) }
    var reloadTick by remember { mutableStateOf(0) }

    fun toast(s: String) { toastMsg = s }

    // 一次加载：当前目录名单 + 全量 walk（行元信息与统计与搜索都吃它）。
    // listDir 失败要浮出原因（listDir 自身已不吞错）；walk 失败只影响元信息，保持沉默。
    LaunchedEffect(curRel, query, reloadTick, backendReady) {
        if (!backendReady) { loading = false; return@LaunchedEffect }
        loading = true
        val n = runCatching { if (query.isEmpty()) listDir(curRel) else emptyList() }
        names = n.getOrDefault(emptyList())
        loadError = if (query.isEmpty()) n.exceptionOrNull()?.message else null
        val w = runCatching { withContext(Dispatchers.IO) { walk() } }.getOrDefault(emptyList())
        entries = w
        loading = false
    }

    // 定位换乘：切到目标文件父目录，短暂高亮后自清。
    LaunchedEffect(locateRel) {
        val rel = locateRel?.let { normalizeRel(it) } ?: return@LaunchedEffect
        curRel = parentOf(rel)
        reloadTick++
        onLocateConsumed()
        highlightRel = rel
        delay(1600)
        highlightRel = null
    }

    // 外部打开的另存兜底不在本页（长按菜单三件已覆盖需求），但分享仍要有：
    val searchActive = query.isNotBlank()
    val rows = remember(names, entries, curRel, searchActive) {
        if (searchActive) {
            searchPaths(entries, query).map {
                BrowserRow(it, it.substringAfterLast('/'), false,
                    entries.firstOrNull { e -> e.path.replace('\\', '/') == it }?.size ?: 0,
                    entries.firstOrNull { e -> e.path.replace('\\', '/') == it }?.lastModified ?: 0)
            }
        } else buildBrowserRows(names, entries, curRel)
    }
    val stats = remember(entries) { workspaceStatsOf(entries, System.currentTimeMillis()) }

    androidx.activity.compose.BackHandler(onBack = onDismiss)
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .clickable(interactionSource = null, indication = null) { }
    ) {
        // 采样画布宿主（子树内禁玻璃）——与退役壳同一铁律口径。
        val localBackdrop = com.haoai.agent.ui.common.WallpaperBackdropHost(wallpaper)
        Column(Modifier.fillMaxSize()) {
            com.haoai.agent.ui.common.GlassPageBar(
                backdrop = localBackdrop,
                title = "工作区",
                onBack = onDismiss
            )
            // 副标题统计行（壳定稿口径：统计进副标题，不放底栏）。
            Text(
                if (!backendReady) "未绑定工作空间"
                else formatWorkspaceStats(stats) + (if (curRel.isEmpty()) " · $workspaceName" else " · $curRel/"),
                fontSize = 11.sp,
                color = theme.sub,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
            )
            // 搜索框（目录 Tab 现件口径：sv 底 9 圆角 mono 12）。
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 6.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(theme.searchBg)
                    .padding(horizontal = 10.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Filled.Search, contentDescription = null,
                    tint = theme.faint, modifier = Modifier.size(15.dp)
                )
                Spacer(Modifier.size(8.dp))
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = TextStyle(
                        fontSize = 12.sp, lineHeight = 15.sp,
                        color = theme.body, fontFamily = FontFamily.Monospace,
                        platformStyle = PlatformTextStyle(includeFontPadding = false)
                    ),
                    cursorBrush = SolidColor(theme.accent),
                    modifier = Modifier.weight(1f),
                    decorationBox = { inner ->
                        Box {
                            if (query.isEmpty()) Text(
                                "搜索工作区文件…", fontSize = 12.sp, color = theme.faint,
                                maxLines = 1,
                                style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                            )
                            inner()
                        }
                    }
                )
                if (query.isNotEmpty()) {
                    Text(
                        "清空", fontSize = 11.5.sp, color = theme.accent,
                        style = TextStyle(
                            lineHeight = 13.sp, fontWeight = FontWeight.SemiBold,
                            platformStyle = PlatformTextStyle(includeFontPadding = false)
                        ),
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .clickable { query = "" }
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }
            // 面包屑（非搜索态才有效）。
            if (!searchActive) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    CrumbChip("工作区", curRel.isEmpty(), onClick = { curRel = "" })
                    val segs = crumbSegments(curRel)
                    segs.forEachIndexed { i, seg ->
                        Text("›", fontSize = 11.sp, color = theme.faint)
                        CrumbChip(seg, i == segs.lastIndex, onClick = {
                            curRel = segs.take(i + 1).joinToString("/")
                        })
                    }
                }
            }
            LazyColumn(Modifier.fillMaxSize()) {
                if (rows.isEmpty()) {
                    item {
                        // 空态三分类（2026-10-24 改版）：读取失败 / 真·空目录（带去向提示与
                        // 一键切回）/ 搜索无匹配——错误不许再伪装成「目录为空」。
                        Column(
                            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 36.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            when {
                                !backendReady -> Text(
                                    "未绑定工作空间", fontSize = 12.sp, color = theme.faint
                                )
                                loadError != null -> {
                                    Icon(
                                        Icons.Filled.Info, contentDescription = null,
                                        tint = haoToneMain(HaoTone.Warn),
                                        modifier = Modifier.size(26.dp)
                                    )
                                    Spacer(Modifier.size(10.dp))
                                    Text(
                                        "读取失败", fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold, color = theme.text
                                    )
                                    Spacer(Modifier.size(4.dp))
                                    Text(
                                        loadError ?: "", fontSize = 11.sp, color = theme.faint,
                                        textAlign = TextAlign.Center
                                    )
                                }
                                searchActive -> Text(
                                    "没有匹配「$query」的文件", fontSize = 12.sp, color = theme.faint
                                )
                                else -> {
                                    Icon(
                                        Icons.Filled.FolderOpen, contentDescription = null,
                                        tint = theme.faint, modifier = Modifier.size(26.dp)
                                    )
                                    Spacer(Modifier.size(10.dp))
                                    Text(
                                        "这个工作区是空的", fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold, color = theme.text
                                    )
                                    if (!emptyHint.isNullOrBlank()) {
                                        Spacer(Modifier.size(4.dp))
                                        Text(
                                            emptyHint, fontSize = 11.sp, color = theme.faint,
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                    if (emptyActionLabel != null && onEmptyAction != null) {
                                        Spacer(Modifier.size(12.dp))
                                        Text(
                                            emptyActionLabel, fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = theme.accent,
                                            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)),
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(999.dp))
                                                .background(theme.accentSoft)
                                                .clickable { onEmptyAction() }
                                                .padding(horizontal = 16.dp, vertical = 7.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                items(count = rows.size, key = { "r-" + rows[it].rel }) { idx ->
                    val row = rows[idx]
                    val hl = row.rel == highlightRel
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .then(
                                if (hl) Modifier
                                    .padding(horizontal = 8.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(theme.accentSoft)
                                    .padding(horizontal = 6.dp)
                                else Modifier.padding(horizontal = 14.dp)
                            )
                            .combinedClickable(
                                onClick = {
                                    if (row.isDir && !searchActive) curRel = row.rel
                                    else viewerRel = row.rel
                                },
                                onLongClick = { if (!searchActive) menuRow = row }
                            )
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (row.isDir) {
                            Icon(
                                Icons.Filled.Folder, contentDescription = null,
                                tint = theme.folder, modifier = Modifier.size(15.dp)
                            )
                            Spacer(Modifier.size(7.dp))
                            Text(
                                row.name + "/", fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                                color = theme.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                        } else {
                            Icon(
                                Icons.Filled.Description, contentDescription = null,
                                tint = theme.faint, modifier = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.size(7.dp))
                            Text(
                                row.name, fontSize = 13.sp, fontFamily = FontFamily.Monospace,
                                color = theme.body, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.size(10.dp))
                            Text(
                                formatRowSize(row.size) +
                                    (if (row.lastModified > 0) " · " + formatRowTime(row.lastModified, System.currentTimeMillis()) else ""),
                                fontSize = 10.sp, color = theme.faint, maxLines = 1,
                                style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                            )
                        }
                    }
                }
                // 底部呼吸位（长按弹层压在最底 12dp 不遮末行）。
                item { Spacer(Modifier.size(28.dp)) }
            }
        }

        // 长按菜单（底部白卡，对标效果图栏②）：复制绝对路径 / 用其他应用打开 / 删除。
        menuRow?.let { row ->
            Column(
                Modifier
                    .fillMaxSize()
                    .background(Color(0x59000000))
                    .clickable(interactionSource = null, indication = null) { menuRow = null },
                verticalArrangement = Arrangement.Bottom
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 14.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(if (haoIsDark()) theme.body.copy(alpha = 0.10f) else Color.White)
                ) {
                    Text(
                        row.rel, fontSize = 11.sp, color = theme.faint, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 11.dp)
                    )
                    MenuLine(Icons.Filled.ContentCopy, "复制绝对路径", theme) {
                        val abs = resolve(row.rel)?.absPath
                        if (abs == null) toast("文件已不存在")
                        else { FileExternal.copyPath(context, abs); toast("路径已复制") }
                        menuRow = null
                    }
                    MenuLine(Icons.Filled.OpenInNew, "用其他应用打开", theme) {
                        menuRow = null
                        val ref = resolve(row.rel)
                        if (ref == null) toast("文件已不存在")
                        else {
                            val ext = ref.relPath.substringAfterLast('.', "").lowercase()
                            val ok = FileExternal.openWith(
                                context, ref.absPath, FileBackendGuessMime(ext)
                            )
                            if (!ok) toast("没有能打开该类型的应用")
                        }
                    }
                    MenuLine(Icons.Filled.Delete, "删除", theme, danger = true) {
                        menuRow = null
                        scope.launch {
                            val r = deleteFile(row.rel)
                            toast(if (r.isSuccess) "已删除 ${row.name}" else "删除失败：${r.exceptionOrNull()?.message ?: "?"}")
                            reloadTick++
                        }
                    }
                }
            }
        }

        // 页内轻提示（复制/删除结果）。
        toastMsg?.let { msg ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                Text(
                    msg, fontSize = 12.sp, color = Color.White,
                    modifier = Modifier
                        .padding(bottom = 96.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color(0xE61C221C))
                        .padding(horizontal = 18.dp, vertical = 9.dp)
                )
            }
            LaunchedEffect(msg) { delay(1800); toastMsg = null }
        }

        // 查看器叠在本页上（关掉回列表；无换乘钮——浏览态就地可看）。
        viewerRel?.let { rel ->
            resolve(rel)?.let { ref ->
                FileViewerDialog(ref = ref, onDismiss = { viewerRel = null })
            }
        }
    }
}

@Composable
private fun CrumbChip(label: String, current: Boolean, onClick: () -> Unit) {
    val theme = workspaceShellTheme()
    Text(
        label, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
        fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
        color = if (current) theme.accent else theme.sub,
        style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)),
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (current) theme.accentSoft else Color.Transparent)
            .clickable(enabled = !current, onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 4.dp)
    )
}

@Composable
private fun MenuLine(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    theme: WorkspaceShellTheme,
    danger: Boolean = false,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null,
            tint = if (danger) theme.del else theme.sub, modifier = Modifier.size(16.dp))
        Spacer(Modifier.size(11.dp))
        Text(
            label, fontSize = 13.5.sp,
            color = if (danger) theme.del else theme.body,
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
    }
}

private fun FileBackendGuessMime(ext: String): String =
    com.haoai.agent.platform.FileBackend.guessMime("x.$ext")
