package com.haoai.agent.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.haoai.agent.ui.ChatRow
import com.haoai.agent.ui.UiFilePath

/**
 * 批3b：本次产出汇总（全屏壳，恒深色——与文件树/全屏代码壳同一语言）。
 *
 * 数据现算自 [rows]（[buildOutputEntries]），无新存储、无新后端调用：
 * 产物=会话里成功的 write/edit 步（diff 摘要在批1f 已随链卡备好）。
 * 点文件行 → FileViewerDialog 只读查看（复用批2c 定位口径，SAF 解析为 null 时
 * 整行不出现查看出路、只留变更摘要）；「查看变更」→ 复用 1.3 的 callId 快照 diff。
 */
@Composable
internal fun OutputSummaryDialog(
    rows: List<ChatRow>,
    resolve: (String) -> UiFilePath?,
    onViewDiff: (callId: String) -> Unit,
    onDismiss: () -> Unit
) {
    val entries = remember(rows) { buildOutputEntries(rows) }
    val totalAdded = entries.sumOf { it.added }
    val totalRemoved = entries.sumOf { it.removed }
    var viewerRel by remember { mutableStateOf<String?>(null) }

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
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "产出汇总",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFE8EEEA)
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(
                        if (entries.isEmpty()) "本会话还没有文件产出"
                        else "${entries.size} 个文件 · +$totalAdded −$totalRemoved",
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
                if (entries.isEmpty()) {
                    Text(
                        "让 Agent 写点东西再来——\n成功的 write/edit 会列在这里",
                        fontSize = 13.sp,
                        color = Color(0xFF5C6A64),
                        modifier = Modifier.padding(24.dp)
                    )
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(entries, key = { it.relPath }) { e ->
                            OutputRow(
                                entry = e,
                                canView = resolve(e.relPath) != null,
                                onOpen = { viewerRel = e.relPath },
                                onDiff = { onViewDiff(e.callId) },
                                onClose = onDismiss
                            )
                        }
                    }
                }
            }
        }
    }

    // 点文件 → 只读查看器（叠在面板之上，关掉回到面板）
    viewerRel?.let { rel ->
        resolve(rel)?.let { ref ->
            FileViewerDialog(ref = ref, onDismiss = { viewerRel = null })
        }
    }
}

@Composable
private fun OutputRow(
    entry: OutputEntry,
    canView: Boolean,
    onOpen: () -> Unit,
    onDiff: () -> Unit,
    onClose: () -> Unit
) {
    val name = entry.relPath.substringAfterLast('/')
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = canView) { onOpen() }
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.Description,
                contentDescription = null,
                tint = Color(0xFF5C6A64),
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.size(7.dp))
            Text(
                name,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                color = Color(0xFFC8D2CD),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                if (entry.isNewFile) "新建" else "修改",
                fontSize = 10.sp,
                color = Color(0xFF9AA8A0)
            )
            Spacer(Modifier.size(8.dp))
            Text(
                "+${entry.added}",
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = Color(0xFF7BD88F)
            )
            Spacer(Modifier.size(5.dp))
            Text(
                "−${entry.removed}",
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = Color(0xFFE88A8A)
            )
        }
        Row(
            Modifier
                .padding(top = 4.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                entry.relPath,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = Color(0xFF5C6A64),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                "查看变更",
                fontSize = 11.sp,
                color = Color(0xFF8AB4F8),
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .clickable { onDiff(); onClose() }
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            )
            if (canView) {
                Text(
                    " 打开 ",
                    fontSize = 11.sp,
                    color = Color(0xFF7BD88F),
                    modifier = Modifier.padding(start = 2.dp)
                )
            }
        }
    }
}
