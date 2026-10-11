package com.haoai.agent.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.haoai.agent.ui.UiFilePath

/**
 * 工作区壳·产出 Tab（原 OutputSummaryDialog 主体，壳色改主题）。
 * 数据=buildOutputEntries(rows) 现算（成功的 write/edit 步）；点行开查看器，
 * 「查看变更」走 callId 快照 diff 通道（上抛给 ChatScreen，与壳关闭同帧）。
 */
@Composable
internal fun WorkspaceOutputsPane(
    theme: WorkspaceShellTheme,
    entries: List<OutputEntry>,
    resolve: (String) -> UiFilePath?,
    onViewDiff: (callId: String) -> Unit,
    onOpenFile: (String) -> Unit,
) {
    if (entries.isEmpty()) {
        Column(Modifier.padding(24.dp)) {
            Text(
                "本会话还没有产出",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = theme.text
            )
            Spacer(Modifier.size(8.dp))
            Text(
                "Agent 写入或修改工作区文件后，\n这里会汇总每次产出，可直接看差异。",
                fontSize = 12.sp,
                color = theme.faint,
                lineHeight = 17.sp
            )
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(entries, key = { it.relPath }) { e ->
            val canView = resolve(e.relPath) != null
            val name = e.relPath.substringAfterLast('/')
            val dir = e.relPath.removeSuffix(name).dropLast(1)
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(enabled = canView) { onOpenFile(e.relPath) }
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Description,
                        contentDescription = null,
                        tint = theme.faint,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.size(7.dp))
                    Text(
                        name,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium,
                        color = theme.body,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        if (e.isNewFile) "新建" else "修改",
                        fontSize = 10.sp,
                        color = theme.sub
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(
                        "+${e.added}",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = theme.accent
                    )
                    Spacer(Modifier.size(5.dp))
                    Text(
                        "−${e.removed}",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = theme.del
                    )
                }
                Row(
                    Modifier
                        .padding(top = 4.dp)
                        .fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (dir.isEmpty()) "根目录" else dir + "/",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = theme.faint,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "查看变更",
                        fontSize = 11.sp,
                        color = theme.info,
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .clickable { onViewDiff(e.callId) }
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                    if (canView) {
                        Text(
                            " 打开 ",
                            fontSize = 11.sp,
                            color = theme.accent,
                            modifier = Modifier.padding(start = 2.dp)
                        )
                    }
                }
            }
        }
    }
}
