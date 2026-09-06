package com.haoai.agent.ui.manage

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.haoai.agent.HaoApplication
import com.haoai.agent.agent.schedule.ScheduleTask
import com.haoai.agent.agent.schedule.Scheduler
import com.haoai.agent.ui.common.GlassCard
import com.haoai.agent.ui.common.GlassPageBar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ScheduleScreen(
    backdrop: com.kyant.backdrop.backdrops.LayerBackdrop,
    onBack: () -> Unit,
    /** 全局壁纸开时传入：页面自带对齐的壁纸底 */
    wallpaper: android.graphics.Bitmap? = null
) {
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as HaoApplication
    val vm: ScheduleViewModel = viewModel(factory = viewModelFactory {
        initializer { ScheduleViewModel(app.container) }
    })
    val fmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.CHINA) }

    // 系统返回手势：回到设置根页，而不是把应用最小化
    androidx.activity.compose.BackHandler { onBack() }

    Box(
        Modifier
            .fillMaxSize()
            // 平移转场页面必须有实底：否则转场中卡片缝隙透空黑；
            // 全局壁纸开时铺对齐壁纸（与 backdrop 采样同源同位）
            .background(MaterialTheme.colorScheme.background)
    ) {
        if (wallpaper != null) {
            val wpImage = androidx.compose.runtime.remember(wallpaper) { wallpaper.asImageBitmap() }
            Image(
                bitmap = wpImage,
                contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.matchParentSize()
            )
        }
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            Spacer(Modifier.height(56.dp))
        Text(
            "到点后代理会在后台自动执行任务并通知结果。可在聊天里用 schedule 工具创建。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp)
        )

        if (vm.items.isEmpty()) {
            Text(
                "\n暂无定时任务。\n试试对代理说：「每天早上 9 点提醒我查看待办」",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp)
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(vm.items, key = { it.id }) { t ->
                TaskCard(t, fmt, backdrop = backdrop,
                    onToggle = { vm.toggle(t) },
                    onDelete = { vm.remove(t) }
                )
            }
        }
        }
        GlassPageBar(
            backdrop = backdrop,
            title = "定时任务（${vm.items.size}）",
            onBack = onBack,
            modifier = Modifier
                .align(Alignment.TopCenter)
        )
    }
}

@Composable
private fun TaskCard(
    t: ScheduleTask,
    fmt: SimpleDateFormat,
    backdrop: com.kyant.backdrop.backdrops.LayerBackdrop,
    onToggle: () -> Unit,
    onDelete: () -> Unit
) {
    GlassCard(
        onClick = onToggle,
        backdrop = backdrop,
        shape = RoundedCornerShape(16.dp),
        surfaceAlpha = 0.24f,
        lensRadius = 16.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 4.dp)) {
            Column(Modifier.weight(1f)) {
                Text(t.name, style = MaterialTheme.typography.titleSmall)
                Text(
                    "${Scheduler.specLabel(t.spec)} · ${if (t.enabled) "启用中" else "已停用"}" +
                        if (t.lastRunAt > 0) " · 上次 ${fmt.format(Date(t.lastRunAt))}" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (t.enabled) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Text(
                    t.prompt,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    modifier = Modifier.padding(top = 4.dp)
                )
                if (t.lastResult.isNotBlank()) {
                    Text(
                        "↳ ${t.lastResult}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
            com.haoai.agent.ui.common.LiquidToggle(
                checked = t.enabled,
                onCheckedChange = { onToggle() },
                backdrop = backdrop
            )
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "删除",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
