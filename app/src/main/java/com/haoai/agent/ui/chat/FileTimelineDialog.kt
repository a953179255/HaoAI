package com.haoai.agent.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.haoai.agent.agent.tools.snapshot.FileSnapshot
import com.haoai.agent.ui.common.DiffType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 批3d：文件版本时间轴（全屏壳，恒深色——文件树/产出汇总同一语言）。
 *
 * 数据源是写前快照（FileSnapshot manifest），版本=每次变更的"变更后"全文；
 * 首次变更若存有"变更前"（文件非会话内新建）则多出一个"初始版"。
 * 点选两个版本 → 现算 diff 就地展开（绿增红删灰同）。
 * 只读面板：回滚走 /undo 与工具卡「查看变更」既有通道，不在这重复写操作。
 *
 * 断层如实呈现：manifest 有 200 条/50MB 淘汰上限，早期版本被清了就是不显示，
 * 不假装历史完整。
 */

/** 时间轴中的一个版本条目。 */
data class SnapVersion(
    /** callId:which 复合键（选择态用）。 */
    val key: String,
    val callId: String,
    /** "before"（初始版）或 "after"（某次变更后）。 */
    val which: String,
    val label: String,
    val ts: Long,
    val bytes: Int
)

/** 某文件的版本序列：首条存有 before → 前置"初始版"；其余按时间正序 V1..Vn。 */
internal fun buildVersions(
    fileMetas: List<FileSnapshot.Meta>,
    hasInitial: Boolean
): List<SnapVersion> {
    val out = ArrayList<SnapVersion>()
    if (fileMetas.isEmpty()) return out
    if (hasInitial) {
        val f = fileMetas.first()
        out += SnapVersion("${f.callId}:before", f.callId, "before", "初始版", f.ts, 0)
    }
    fileMetas.forEachIndexed { i, m ->
        out += SnapVersion("${m.callId}:after", m.callId, "after", "V${i + 1}", m.ts, m.bytes)
    }
    return out
}

@Composable
internal fun FileTimelineDialog(
    loadMetas: suspend () -> List<FileSnapshot.Meta>,
    readVersion: suspend (callId: String, which: String) -> String?,
    hasBefore: suspend (callId: String) -> Boolean,
    preselect: String?,
    onDismiss: () -> Unit
) {
    var metas by remember { mutableStateOf<List<FileSnapshot.Meta>?>(null) }
    var selectedFile by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { metas = loadMetas() }
    val files = remember(metas) { metas?.let { groupHistory(it) } }
    // 步骤一：文件清单（preselect 命中则直接进该文件时间轴）
    val startFile = preselect?.let { p ->
        val want = normalizeRel(p)
        files?.firstOrNull { it.relPath == want }?.relPath
    }
    LaunchedEffect(files) {
        if (selectedFile == null && !files.isNullOrEmpty()) selectedFile = startFile
    }
    val current = files?.firstOrNull { it.relPath == selectedFile }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0xFF10151A))
                .statusBarsPadding()
        ) {
            if (metas == null) {
                Text(
                    "读取快照…",
                    fontSize = 13.sp,
                    color = Color(0xFF9AA8A0),
                    modifier = Modifier.padding(24.dp)
                )
            } else if (files.isNullOrEmpty()) {
                Column(Modifier.padding(24.dp)) {
                    Text(
                        "本会话还没有文件变更",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFE8EEEA)
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(
                        "快照在 write/edit 执行前自动记录，\n写第一个文件后这里就有历史了。",
                        fontSize = 12.sp,
                        color = Color(0xFF5C6A64),
                        lineHeight = 17.sp
                    )
                }
            } else if (current == null) {
                // 文件清单
                Column(Modifier.fillMaxSize()) {
                    TimelineHeader("版本历史", "${files.size} 个文件有变更记录", onDismiss)
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(files, key = { it.relPath }) { f ->
                            val fmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.CHINA) }
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { selectedFile = f.relPath }
                                    .padding(horizontal = 16.dp, vertical = 11.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    f.relPath.substringAfterLast('/'),
                                    fontSize = 13.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Medium,
                                    color = Color(0xFFC8D2CD),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    "${f.versionCount} 个版本",
                                    fontSize = 11.sp,
                                    color = Color(0xFF7BD88F),
                                    modifier = Modifier.padding(end = 10.dp)
                                )
                                Text(
                                    fmt.format(Date(f.lastTs)),
                                    fontSize = 10.sp,
                                    color = Color(0xFF5C6A64)
                                )
                            }
                        }
                    }
                }
            } else {
                TimelineFileView(
                    file = current,
                    metas = metas!!,
                    readVersion = readVersion,
                    hasBefore = hasBefore,
                    onBack = { selectedFile = null },
                    onDismiss = onDismiss
                )
            }
        }
    }
}

@Composable
private fun TimelineHeader(title: String, sub: String, onDismiss: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFFE8EEEA)
        )
        Spacer(Modifier.size(8.dp))
        Text(
            sub,
            fontSize = 11.sp,
            color = Color(0xFF9AA8A0),
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(
            "关闭",
            fontSize = 12.sp,
            color = Color(0xFF9AA8A0),
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .clickable { onDismiss() }
                .padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

/** 步骤二：某文件的版本列表 + 两版对比。 */
@Composable
private fun TimelineFileView(
    file: TimelineFile,
    metas: List<FileSnapshot.Meta>,
    readVersion: suspend (callId: String, which: String) -> String?,
    hasBefore: suspend (callId: String) -> Boolean,
    onBack: () -> Unit,
    onDismiss: () -> Unit
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
    var diffResult by remember(file.relPath) {
        mutableStateOf<Pair<List<com.haoai.agent.ui.common.DiffLine>, Int>?>(null)
    }
    LaunchedEffect(sel) {
        if (sel.size == 2) {
            val a = versions.firstOrNull { it.key == sel[0] }
            val b = versions.firstOrNull { it.key == sel[1] }
            if (a != null && b != null) {
                val ta = readVersion(a.callId, a.which)
                val tb = readVersion(b.callId, b.which)
                diffResult = if (ta == null && tb == null) null else {
                    val r = com.haoai.agent.ui.common.TextDiff.diffText(ta ?: "", tb ?: "")
                    r.lines to r.truncatedHead
                }
            } else diffResult = null
        } else diffResult = null
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "‹ 返回",
                fontSize = 13.sp,
                color = Color(0xFF9AA8A0),
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
                color = Color(0xFFE8EEEA),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                if (sel.size < 2) "选两个版本对比" else "",
                fontSize = 11.sp,
                color = Color(0xFF5C6A64)
            )
            Text(
                "关闭",
                fontSize = 12.sp,
                color = Color(0xFF9AA8A0),
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .clickable { onDismiss() }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .padding(start = 4.dp)
            )
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)
        ) {
            items(versions, key = { it.key }) { v ->
                val picked = v.key in sel
                val fmt = remember { SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA) }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 2.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(if (picked) Color(0x337BD88F) else Color.Transparent)
                        .border(
                            if (picked) 1.dp else 0.dp,
                            if (picked) Color(0xFF7BD88F) else Color.Transparent,
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
                        color = if (picked) Color(0xFF7BD88F) else Color(0xFFC8D2CD),
                        modifier = Modifier.size(width = 56.dp, height = 18.dp)
                    )
                    Text(
                        fmt.format(Date(v.ts)),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFF9AA8A0)
                    )
                    Spacer(Modifier.weight(1f))
                    if (v.bytes > 0) {
                        Text(
                            "${v.bytes} B",
                            fontSize = 10.sp,
                            color = Color(0xFF5C6A64)
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
                        color = Color(0xFF9AA8A0),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                    )
                    Row(
                        Modifier.padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("+$added", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Color(0xFF7BD88F))
                        Text("−$removed", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Color(0xFFE88A8A))
                    }
                }
                items(lines.size) { i ->
                    val d = lines[i]
                    val (mark, color, bg) = when (d.type) {
                        DiffType.ADDED -> Triple("+", Color(0xFF7BD88F), Color(0x1F7BD88F))
                        DiffType.REMOVED -> Triple("−", Color(0xFFE88A8A), Color(0x1FE88A8A))
                        else -> Triple(" ", Color(0xFF8A968F), Color.Transparent)
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

private fun selOfLabel(versions: List<SnapVersion>, sel: List<String>, i: Int): String =
    sel.getOrNull(i)?.let { k -> versions.firstOrNull { it.key == k }?.label } ?: "?"
