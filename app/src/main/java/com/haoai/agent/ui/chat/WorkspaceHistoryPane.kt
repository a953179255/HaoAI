package com.haoai.agent.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.haoai.agent.agent.tools.snapshot.FileSnapshot
import com.haoai.agent.ui.common.DiffLine
import com.haoai.agent.ui.common.DiffType
import com.haoai.agent.ui.common.TextDiff
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 工作区壳·历史 Tab（原 FileTimelineDialog 主体，壳色改主题；metas 由壳懒加载传入）。
 *
 * 两级：文件清单（带版本点线）→ 某文件版本时间轴；点选两个版本现算 diff 就地展开。
 * 只读面板：回滚走 /undo 既有通道。断层如实——manifest 有 200 条/50MB 淘汰上限。
 */
@Composable
internal fun WorkspaceHistoryPane(
    theme: WorkspaceShellTheme,
    metas: List<FileSnapshot.Meta>?,
    readVersion: suspend (callId: String, which: String) -> String?,
    hasBefore: suspend (callId: String) -> Boolean,
    preselect: String?,
    onPreselectConsumed: () -> Unit,
) {
    val files = remember(metas) { metas?.let { groupHistory(it) } }
    var selectedFile by remember { mutableStateOf<String?>(null) }
    val startFile = preselect?.let { p ->
        val want = normalizeRel(p)
        files?.firstOrNull { it.relPath == want }?.relPath
    }
    LaunchedEffect(files, preselect) {
        if (selectedFile == null && startFile != null) {
            selectedFile = startFile
            onPreselectConsumed()
        }
    }
    val current = files?.firstOrNull { it.relPath == selectedFile }

    Column(Modifier.fillMaxSize()) {
        when {
            metas == null -> Text(
                "读取快照…",
                fontSize = 13.sp,
                color = theme.faint,
                modifier = Modifier.padding(24.dp)
            )
            files.isNullOrEmpty() -> Column(Modifier.padding(24.dp)) {
                Text(
                    "本会话还没有文件变更",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = theme.text
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    "快照在 write/edit 执行前自动记录，\n写第一个文件后这里就有历史了。",
                    fontSize = 12.sp,
                    color = theme.faint,
                    lineHeight = 17.sp
                )
            }
            current == null -> LazyColumn(Modifier.fillMaxSize()) {
                items(files, key = { it.relPath }) { f ->
                    val fmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.CHINA) }
                    val name = f.relPath.substringAfterLast('/')
                    val dir = f.relPath.removeSuffix(name).dropLast(1)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { selectedFile = f.relPath }
                            .padding(horizontal = 16.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                name,
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Medium,
                                color = theme.body,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.size(3.dp))
                            // 版本点线（效果图定稿）：每版一点，最近一版点亮
                            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                repeat(f.versionCount.coerceAtMost(12)) { i ->
                                    Box(
                                        Modifier
                                            .size(4.dp)
                                            .clip(CircleShape)
                                            .background(
                                                if (i == f.versionCount - 1) theme.accent
                                                else theme.faint.copy(alpha = 0.45f)
                                            )
                                    )
                                }
                            }
                        }
                        Text(
                            "${f.versionCount} 个版本",
                            fontSize = 11.sp,
                            color = theme.accent,
                            modifier = Modifier.padding(end = 10.dp)
                        )
                        Text(
                            fmt.format(Date(f.lastTs)),
                            fontSize = 10.sp,
                            color = theme.faint
                        )
                    }
                }
            }
            else -> HistoryFileView(
                theme = theme,
                file = current,
                metas = metas,
                readVersion = readVersion,
                hasBefore = hasBefore,
                onBack = { selectedFile = null }
            )
        }
    }
}

/** 某文件的版本列表 + 两版对比（原 TimelineFileView，头部去掉关闭钮——壳头统一管）。 */
@Composable
private fun HistoryFileView(
    theme: WorkspaceShellTheme,
    file: TimelineFile,
    metas: List<FileSnapshot.Meta>,
    readVersion: suspend (callId: String, which: String) -> String?,
    hasBefore: suspend (callId: String) -> Boolean,
    onBack: () -> Unit,
) {
    val fileMetas = remember(metas, file.relPath) { versionsOf(metas, file.relPath) }
    var hasInitial by remember(file.relPath) { mutableStateOf(false) }
    LaunchedEffect(file.relPath, fileMetas) {
        hasInitial = fileMetas.isNotEmpty() &&
            withContext(Dispatchers.IO) { hasBefore(fileMetas.first().callId) }
    }
    val versions = remember(fileMetas, hasInitial) { buildVersions(fileMetas, hasInitial) }
    // 选择态：最多两个（FIFO），对比时现拉两版全文算 diff
    var sel by remember(file.relPath) { mutableStateOf<List<String>>(emptyList()) }
    var diffResult by remember(file.relPath) { mutableStateOf<Pair<List<DiffLine>, Int>?>(null) }
    LaunchedEffect(sel) {
        if (sel.size == 2) {
            val a = versions.firstOrNull { it.key == sel[0] }
            val b = versions.firstOrNull { it.key == sel[1] }
            if (a != null && b != null) {
                val ta = readVersion(a.callId, a.which)
                val tb = readVersion(b.callId, b.which)
                diffResult = if (ta == null && tb == null) null else {
                    val r = TextDiff.diffText(ta ?: "", tb ?: "")
                    r.lines to r.truncatedHead
                }
            } else diffResult = null
        } else diffResult = null
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "‹ 返回",
                fontSize = 13.sp,
                color = theme.sub,
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .clickable { onBack() }
                    .padding(horizontal = 8.dp, vertical = 5.dp)
            )
            Spacer(Modifier.size(6.dp))
            Text(
                file.relPath,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = theme.text,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                if (sel.size < 2) "选两个版本对比" else "",
                fontSize = 11.sp,
                color = theme.faint
            )
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            items(versions, key = { it.key }) { v ->
                val picked = v.key in sel
                val fmt = remember { SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA) }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 2.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(if (picked) theme.accentSoft else Color.Transparent)
                        .border(
                            if (picked) 1.dp else 0.dp,
                            if (picked) theme.accent else Color.Transparent,
                            RoundedCornerShape(9.dp)
                        )
                        .clickable { sel = toggleSelect(sel, v.key) }
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        v.label,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (picked) theme.accent else theme.body,
                        modifier = Modifier.size(width = 56.dp, height = 18.dp)
                    )
                    Text(
                        fmt.format(Date(v.ts)),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = theme.sub
                    )
                    Spacer(Modifier.weight(1f))
                    if (v.bytes > 0) {
                        Text(
                            "${v.bytes} B",
                            fontSize = 10.sp,
                            color = theme.faint
                        )
                    }
                }
            }
            diffResult?.let { (lines, truncatedHead) ->
                item {
                    val added = lines.count { it.type == DiffType.ADDED }
                    val removed = lines.count { it.type == DiffType.REMOVED }
                    Text(
                        "对比 ${selOfLabel(versions, sel, 0)} → ${selOfLabel(versions, sel, 1)}" +
                            if (truncatedHead > 0) "（已省略前 $truncatedHead 行）" else "",
                        fontSize = 11.sp,
                        color = theme.sub,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                    )
                    Row(
                        Modifier.padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("+$added", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = theme.accent)
                        Text("−$removed", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = theme.del)
                    }
                }
                items(lines.size) { i ->
                    val d = lines[i]
                    val (mark, color, bg) = when (d.type) {
                        DiffType.ADDED -> Triple("+", theme.accent, theme.accentSoft.copy(alpha = 0.5f))
                        DiffType.REMOVED -> Triple("−", theme.del, theme.delBg)
                        else -> Triple(" ", theme.ctx, Color.Transparent)
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(bg)
                            .padding(horizontal = 12.dp, vertical = 1.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(mark, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = color)
                        Text(
                            d.text,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = color,
                            maxLines = 4, overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

/** 选择键 → 版本标签（原 FileTimelineDialog 私有函数，随壳迁移）。 */
private fun selOfLabel(versions: List<SnapVersion>, sel: List<String>, i: Int): String =
    sel.getOrNull(i)?.let { k -> versions.firstOrNull { it.key == k }?.label } ?: "?"
