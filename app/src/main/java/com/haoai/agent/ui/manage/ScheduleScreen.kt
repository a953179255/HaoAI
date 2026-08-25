package com.haoai.agent.ui.manage

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.haoai.agent.HaoApplication
import com.haoai.agent.agent.schedule.ScheduleTask
import com.haoai.agent.agent.schedule.Scheduler
import com.haoai.agent.ui.common.GlassPageBar
import com.haoai.agent.ui.common.appLayer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ScheduleScreen(backdrop: com.kyant.backdrop.backdrops.LayerBackdrop, onBack: () -> Unit) {
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as HaoApplication
    val vm: ScheduleViewModel = viewModel(factory = viewModelFactory {
        initializer { ScheduleViewModel(app.container) }
    })
    val fmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.CHINA) }

    Box(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .appLayer(backdrop)
        ) {
            Spacer(Modifier.height(64.dp))
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
                TaskCard(t, fmt,
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
                .padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun TaskCard(
    t: ScheduleTask,
    fmt: SimpleDateFormat,
    onToggle: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle() }
    ) {
        Row(Modifier.padding(start = 14.dp, top = 12.dp, bottom = 12.dp)) {
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
            Switch(checked = t.enabled, onCheckedChange = { onToggle() })
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
