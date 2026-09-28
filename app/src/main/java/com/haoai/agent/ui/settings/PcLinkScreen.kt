package com.haoai.agent.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.haoai.agent.platform.PcLink
import com.haoai.agent.platform.PcOut
import com.haoai.agent.platform.PcWatchAction
import com.haoai.agent.platform.PcWatchdog
import com.haoai.agent.platform.pcNormalizeBase
import com.haoai.agent.ui.common.GlassCard
import com.haoai.agent.ui.common.GlassPageBar
import com.haoai.agent.ui.common.HaoChip
import com.haoai.agent.ui.common.HaoGroupLabel
import com.haoai.agent.ui.theme.HaoTone
import com.haoai.agent.ui.theme.haoCardSurfaceAlpha
import kotlinx.coroutines.launch

/**
 * 「电脑联动」页：把这台手机配到电脑上那台 HaoAI，并让它能在锁屏外替你点那一下。
 *
 * 页面上的三句话是刻意写的（跨端这东西最容易"看起来连上了其实没有"）：
 * ① 配对状态 + 上一次问的结果原文 —— 连不上时直接把那句人话摆出来，不藏进日志；
 * ② "每 20 秒问一次、只在保活服务活着时问" —— 不谎称任何情况下都收得到；
 * ③ 「现在问一次」—— 配完就能当场验证，不用等下一轮，也不用去猜它到底通没通。
 */
@Composable
fun PcLinkScreen(
    backdrop: com.kyant.backdrop.backdrops.LayerBackdrop,
    onBack: () -> Unit,
    wallpaper: android.graphics.Bitmap? = null
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val st by PcWatchdog.state.collectAsState()
    val scope = rememberCoroutineScope()
    var base by remember { mutableStateOf(st.base) }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }
    /** 上面那句是好消息还是坏消息（同一行文字，红绿得跟着事实走）。 */
    var noteOk by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            // 转场页面必须有实底，否则卡片缝隙透空黑（与设置页同一处理）
            .background(MaterialTheme.colorScheme.background)
    ) {
        GlassPageBar(backdrop = backdrop, title = "电脑联动", onBack = onBack)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                GlassCard(
                    onClick = {}, backdrop = backdrop, shape = RoundedCornerShape(16.dp),
                    surfaceAlpha = haoCardSurfaceAlpha(), pressScale = false
                ) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            HaoChip(if (st.paired) "已配对" else "没配对", if (st.paired) HaoTone.Accent else HaoTone.Neutral)
                            HaoChip(if (st.polling) "轮询中" else "没在轮询", HaoTone.Neutral)
                            if (st.waiting > 0) HaoChip("等 ${st.waiting} 条", HaoTone.Warn)
                        }
                        Text(
                            if (st.paired) "${st.base}　·　设备名 ${st.device.ifBlank { "未命名" }}"
                            else "在电脑上：HaoAI 网页界面 →「工具」页签 → 手机联动 → 生成配对码（六位、120 秒内有效、只能用一次）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (st.lastNote.isNotBlank()) {
                            Text(
                                "上一次问：" + st.lastNote +
                                    (if (st.lastAt > 0) "（${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(st.lastAt))}）" else ""),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }

            item { HaoGroupLabel("配对新电脑") }
            item {
                GlassCard(
                    onClick = {}, backdrop = backdrop, shape = RoundedCornerShape(16.dp),
                    surfaceAlpha = haoCardSurfaceAlpha(), pressScale = false
                ) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = base, onValueChange = { base = it }, singleLine = true,
                            label = { Text("电脑地址") },
                            placeholder = { Text("如 192.168.1.20:8720") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = code, onValueChange = { if (it.length <= 6) code = it }, singleLine = true,
                            label = { Text("配对码") },
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (note.isNotBlank()) {
                            // 成没成要用颜色分开说：这句是"配上了"还是"没配上"，人扫一眼就该知道
                            Text(note, style = MaterialTheme.typography.bodySmall,
                                color = if (noteOk) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.error)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                enabled = !busy && pcNormalizeBase(base) != null && code.length == 6,
                                onClick = {
                                    busy = true; note = ""
                                    scope.launch {
                                        // 先 pair 拿到 token，再落盘：中间任何一步失败都不该留下半份配置
                                        val pr = PcLink(base).pair(code, android.os.Build.MODEL ?: "手机")
                                        when (pr) {
                                            is PcOut.Ok -> {
                                                val sv = PcWatchdog.save(base, pr.value.token, pr.value.device)
                                                noteOk = sv is PcOut.Ok
                                                note = when (sv) {
                                                    is PcOut.Ok -> "配上了：${pr.value.device}"
                                                    is PcOut.Fail -> sv.message
                                                }
                                            }
                                            is PcOut.Fail -> { note = pr.message; noteOk = false }
                                        }
                                        busy = false
                                    }
                                }
                            ) { Text(if (busy) "配对中…" else "配对") }
                            OutlinedButton(
                                onClick = { note = "地址要的是 IP 或主机名，可以带端口（不填用 8720）。手机和电脑得在同一个网段。"; noteOk = false }
                            ) { Text("地址要求") }
                        }
                    }
                }
            }

            item { HaoGroupLabel("提醒") }
            item {
                GlassCard(
                    onClick = {}, backdrop = backdrop, shape = RoundedCornerShape(16.dp),
                    surfaceAlpha = haoCardSurfaceAlpha(), pressScale = false
                ) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                        ) {
                            Column(Modifier.fillMaxWidth(0.72f)) {
                                Text("后台提醒", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "电脑上有要批的东西时弹通知，通知上可直接点「允许一次 / 本任务都允许 / 拒绝」",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            com.haoai.agent.ui.common.HaoSwitch(
                                checked = st.polling,
                                onCheckedChange = { on ->
                                    if (on) { PcWatchdog.init(ctx); PcWatchdog.start() }
                                    else PcWatchdog.stop()
                                }
                            )
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "每 20 秒问一次，且只在后台保活服务活着的时候问 —— 省电策略把服务杀掉之后就不会提醒。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(enabled = !busy && st.paired, onClick = {
                                busy = true
                                scope.launch {
                                    val act = PcWatchdog.pollOnce()
                                    noteOk = true
                                    note = when (act) {
                                        is PcWatchAction.Notify -> "有 ${act.total} 条在等（其中 ${act.fresh} 条是新的），通知已弹"
                                        PcWatchAction.Clear -> "电脑上没有要批的了，通知已收回"
                                        PcWatchAction.Same -> "和上次一样，没有新的（不重复弹）"
                                    }
                                    busy = false
                                }
                            }) { Text("现在问一次") }
                            OutlinedButton(enabled = st.paired, onClick = {
                                scope.launch {
                                    noteOk = true
                                    // 电脑回的那句本身就说清了，别再冠一遍（实测拼出
                                    // "已解除这台设备的配对：已解除这台设备的配对"）
                                    note = PcWatchdog.forget().ifBlank { "已解除这台设备的配对" }
                                }
                            }) { Text("解除配对") }
                        }
                    }
                }
            }
        }
    }
}
