package com.haoai.agent.ui.chat

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.rememberLazyListState
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
import com.haoai.agent.ui.UiFilePath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 批3c：后台任务日志查看器（全屏壳，恒深色——文件树/产出汇总同一语言）。
 *
 * 日志是引擎投递脚本写的工作区文件（.haoai-jobs/<id>.log），这里只做只读尾随：
 * 运行中每 2s 拉一次尾窗（[JOB_LOG_POLL_MS]），读到末行 `__JOB_DONE_<rc>` 标记
 * 即转"已结束"并停止轮询——状态从文件本身判，不依赖进程句柄（引擎侧进程句柄
 * 早随 exec 调用返回被丢弃，这正是要读文件的原因）。
 * 自动滚到底：新行追加时跟随，用户手动上滚查看历史后不再强行拉回。
 */
@Composable
internal fun JobLogViewerDialog(ref: UiFilePath, onDismiss: () -> Unit) {
    var view by remember(ref.absPath) {
        mutableStateOf(parseJobLog(readTailLines(File(ref.absPath))))
    }
    val listState = rememberLazyListState()
    // 尾随循环：运行中 2s 一拉；结束或文件消失即退出协程
    LaunchedEffect(ref.absPath) {
        while (view.running) {
            delay(JOB_LOG_POLL_MS)
            val next = withContext(Dispatchers.IO) {
                parseJobLog(readTailLines(File(ref.absPath)))
            }
            view = next
        }
    }
    // 自动跟随最新行（用户上滚离开底部后不抢滚动）
    LaunchedEffect(view.lines.size) {
        if (view.lines.isNotEmpty() &&
            listState.firstVisibleItemIndex >= view.lines.size - 3
        ) {
            listState.animateScrollToItem(view.lines.size - 1)
        }
    }
    val jobId = ref.relPath.substringAfterLast('/').removeSuffix(".log")
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
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "后台日志",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFE8EEEA)
                    )
                    Text(
                        jobId,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFF9AA8A0),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    // 状态胶囊：运行中（呼吸绿点由文字代替，壳内无额外动画预算）/ 已结束+退出码
                    val status = if (view.running) "运行中" else "已结束 exit ${view.exitCode}"
                    val statusColor = if (view.running) Color(0xFF7BD88F) else Color(0xFF9AA8A0)
                    Text(
                        status,
                        fontSize = 11.sp,
                        color = statusColor,
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(statusColor.copy(alpha = 0.14f))
                            .padding(horizontal = 9.dp, vertical = 4.dp)
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
                if (view.lines.isEmpty()) {
                    Text(
                        if (view.running) "（任务刚投递，暂无输出…）" else "（日志为空）",
                        fontSize = 12.sp,
                        color = Color(0xFF5C6A64),
                        modifier = Modifier.padding(24.dp)
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 12.dp)
                    ) {
                        items(view.lines.size) { i ->
                            Text(
                                view.lines[i],
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                lineHeight = 16.sp,
                                color = Color(0xFFC8D2CD)
                            )
                        }
                    }
                }
            }
        }
    }
}
