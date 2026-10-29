package com.haoai.agent.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.haoai.agent.platform.llama.LlamaServerController
import com.haoai.agent.platform.llama.LlamaState
import com.haoai.agent.ui.SettingsViewModel
import com.haoai.agent.ui.common.GlassAlertDialog
import com.haoai.agent.ui.common.GlassBottomSheet
import com.haoai.agent.ui.common.HaoNote
import com.haoai.agent.ui.common.HaoSwitch
import com.haoai.agent.ui.common.LiquidSlider
import com.haoai.agent.ui.common.LiquidTabRow
import com.haoai.agent.ui.common.glassFieldColors
import com.kyant.backdrop.backdrops.LayerBackdrop
import kotlinx.coroutines.launch

/**
 * 端侧推理区块（2026-10-30 改版）：状态卡 / 模型库 / 运行参数 / 引擎卡 + 「添加模型」「服务日志」
 * 两个玻璃底部弹层。效果图 haoai-local-llm-redesign.html 经用户确认后落地；
 * 目标是修掉旧版「报错误导（把模型架构不认识说成后端全坏）/ 添加模型必须挪目录 /
 * 有模型后下载入口消失 / 除上下文外零参数可调」四块硬伤。
 */

/** 官方预编译引擎已知最新 tag：构建脚本升级时同步（用于「已是最新」徽标，不做运行时联网检查）。 */
private const val LATEST_KNOWN_ENGINE = "b11982"

private fun engineNum(t: String): Long = t.filter { it.isDigit() }.toLongOrNull() ?: 0L

// ── 区块入口 ──────────────────────────────────────────────

internal fun LazyListScope.localInferenceCards(vm: SettingsViewModel, backdrop: LayerBackdrop) {
    item { SectionTitle("端侧推理（llama.cpp 本地运行）") }
    item { LocalStatusCard(vm, backdrop) }
    item { LocalModelLibraryCard(vm, backdrop) }
    item { LocalParamsCard(vm, backdrop) }
    item { LocalEngineCard(backdrop) }
    item {
        HaoNote(
            backdrop = backdrop,
            text = "完全离线运行，对话不出设备。模型越大越聪明但越慢，可按需切换。",
            modifier = Modifier.padding(vertical = 4.dp)
        )
    }
}

// ── 通用小件 ──────────────────────────────────────────────

private enum class Tone { Neutral, Ok, Warn, Bad }

@Composable
private fun Badge(text: String, tone: Tone = Tone.Neutral, mono: Boolean = false) {
    val (bg, fg) = when (tone) {
        Tone.Neutral -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
        Tone.Ok -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        Tone.Warn -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        Tone.Bad -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
    }
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = fg,
            fontFamily = if (mono) FontFamily.Monospace else null,
            maxLines = 1
        )
    }
}

private fun fmtSize2(bytes: Long): String =
    if (bytes >= 1073741824L) String.format("%.2fGB", bytes / 1073741824f)
    else "${bytes / (1024 * 1024)}MB"

private fun backendShort(label: String): String = when {
    label.startsWith("htp") -> "NPU"
    label == "gpu" -> "GPU"
    else -> "CPU"
}

@Composable
private fun ParamLabel(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(top = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Text(
            value,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun StepperRow(
    value: Int,
    display: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    canMinus: Boolean = true,
    canPlus: Boolean = true
) {
    Row(
        Modifier.padding(top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(enabled = canMinus) { onMinus() }
                .padding(horizontal = 14.dp, vertical = 5.dp)
        ) { Text("−", style = MaterialTheme.typography.titleSmall) }
        Text(
            display,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 10.dp)
        )
        Box(
            Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(enabled = canPlus) { onPlus() }
                .padding(horizontal = 14.dp, vertical = 5.dp)
        ) { Text("＋", style = MaterialTheme.typography.titleSmall) }
    }
}

// ── 状态卡 ────────────────────────────────────────────────

@Composable
private fun LocalStatusCard(vm: SettingsViewModel, backdrop: LayerBackdrop) {
    val llama by vm.llamaState.collectAsState()
    val settings by vm.settings.collectAsState()
    // 失败时解析 server.log 的真实根因（旧版只报「三后端均不可用」，把模型问题栽给引擎）
    val archErr = remember(llama) { if (llama is LlamaState.Failed) vm.llamaArchError() else null }
    val sampling = remember(llama) { vm.localSampling() }
    GlassGroup(backdrop) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("本地推理", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Badge("llama.cpp · ${LlamaServerController.ENGINE_TAG}", mono = true)
            }
            Spacer(Modifier.padding(top = 8.dp))
            when (val s = llama) {
                is LlamaState.Running -> {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatusDot(Color(0xFF1EA84F))
                        Text("运行中", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                        Badge(backendShort(vm.backendLabel()), Tone.Ok)
                    }
                    Spacer(Modifier.padding(top = 10.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatBox("后端", backendShort(vm.backendLabel()), Modifier.weight(1f))
                        StatBox("上下文", "${vm.localContextLength() / 1024}K", Modifier.weight(1f))
                        StatBox("温度", String.format("%.2f", sampling.first), Modifier.weight(1f))
                        StatBox("线程", settings.localThreads.takeIf { it > 0 }?.toString() ?: "自动", Modifier.weight(1f))
                    }
                    ActionRow {
                        LiquidPillButton(
                            backdrop = backdrop, text = "停止服务",
                            level = com.haoai.agent.ui.theme.HaoButtonLevel.Danger
                        ) { vm.stopLlama() }
                        LiquidPillButton(backdrop, text = "日志", emphasized = false) { vm.openLlamaSheet("log") }
                    }
                }
                is LlamaState.Starting -> {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatusDot(MaterialTheme.colorScheme.primary)
                        Text("启动中 ${s.progress}%", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                    }
                    if (s.detail.isNotBlank()) {
                        Text(
                            s.detail, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2, modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    if (s.progress >= 0) {
                        LinearProgressIndicator(
                            progress = { (s.progress.coerceIn(0, 100)) / 100f },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            gapSize = 0.dp, drawStopIndicator = {}
                        )
                    }
                    ActionRow {
                        LiquidPillButton(backdrop, text = "停止", emphasized = false) { vm.stopLlama() }
                    }
                }
                is LlamaState.Failed -> {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatusDot(MaterialTheme.colorScheme.error)
                        Text("启动失败", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                    }
                    Spacer(Modifier.padding(top = 8.dp))
                    if (archErr != null) {
                        val (arch, raw) = archErr
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(MaterialTheme.colorScheme.errorContainer)
                                .padding(12.dp)
                        ) {
                            Text(
                                "引擎 ${LlamaServerController.ENGINE_TAG} 不支持 $arch 架构",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Text(
                                "${arch} 支持于 2026-10-27 起合入 llama.cpp 上游（需引擎 ≥ " +
                                    "${LlamaServerController.ARCH_MIN_ENGINE[arch] ?: "更新版本"}）。" +
                                    "更新 App 后模型文件无需任何改动即可使用。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.85f),
                                modifier = Modifier.padding(top = 5.dp)
                            )
                            Text(
                                raw,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.75f),
                                maxLines = 3,
                                modifier = Modifier.padding(top = 6.dp)
                            )
                        }
                    } else {
                        Text(
                            s.message, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3
                        )
                    }
                    ActionRow {
                        LiquidPillButton(backdrop, text = "查看日志", emphasized = false) { vm.openLlamaSheet("log") }
                        LiquidPillButton(backdrop, text = "更换模型", emphasized = false) { vm.openLlamaSheet("add", "dl") }
                    }
                }
                else -> {
                    val active = vm.localModelLibrary.firstOrNull { it.isActive }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatusDot(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                        Text("已停止", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                        if (active?.supported == false) {
                            Badge("当前模型需引擎 ≥ ${LlamaServerController.ARCH_MIN_ENGINE[active.meta?.architecture]}", Tone.Warn)
                        }
                    }
                    Text(
                        "模型：${vm.currentLocalModelLabel() ?: "未添加"} · 上下文 ${vm.localContextLength() / 1024}K",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    ActionRow {
                        LiquidPillButton(
                            backdrop = backdrop, text = "启动服务",
                            enabled = vm.llamaModelFile() != null
                        ) { vm.startLlama { vm.useLocalModel() } }
                        LiquidPillButton(backdrop, text = "日志", emphasized = false) { vm.openLlamaSheet("log") }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusDot(color: Color) {
    Box(
        Modifier
            .padding(2.dp)
            .clip(RoundedCornerShape(50))
            .background(color)
            .padding(4.dp)
    )
}

@Composable
private fun StatBox(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .padding(horizontal = 9.dp, vertical = 6.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium,
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ActionRow(content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) { content() }
}

// ── 模型库卡 ──────────────────────────────────────────────

@Composable
private fun LocalModelLibraryCard(vm: SettingsViewModel, backdrop: LayerBackdrop) {
    LaunchedEffect(Unit) { vm.refreshLocalModelLibrary() }
    val lib = vm.localModelLibrary
    var pendingDelete by remember { mutableStateOf<SettingsViewModel.LocalModelUi?>(null) }
    GlassGroup(backdrop) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("模型库", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Badge("${lib.size} 个")
            }
            if (lib.isEmpty()) {
                Text(
                    "还没有模型：点下方「添加模型」从广场下载，或「扫描手机」直读手机里已有的 GGUF（不复制、不占双份空间）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
            lib.forEachIndexed { i, m ->
                if (i > 0) {
                    HorizontalDivider(
                        Modifier.padding(vertical = 8.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.10f)
                    )
                }
                ModelRow(vm, m, onRequestDelete = { pendingDelete = m })
            }
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LiquidPillButton(backdrop, text = "添加模型") { vm.openLlamaSheet("add", "dl") }
                LiquidPillButton(backdrop, text = "扫描手机", emphasized = false) { vm.openLlamaSheet("add", "scan") }
            }
        }
    }
    pendingDelete?.let { m ->
        GlassAlertDialog(
            backdrop = backdrop,
            title = if (m.isDevice) "解除引用" else "删除模型",
            onDismiss = { pendingDelete = null },
            confirmLabel = if (m.isDevice) "解除引用" else "删除",
            danger = !m.isDevice,
            onConfirm = {
                vm.deleteLibraryModel(m)
                pendingDelete = null
            },
            dismissLabel = "取消"
        ) {
            Text(
                if (m.isDevice)
                    "停止引用手机存储上的 ${m.name}，源文件保留不动。"
                else
                    "将从应用目录删除 ${m.name}（${fmtSize2(m.sizeBytes)}），释放存储空间。"
            )
        }
    }
}

@Composable
private fun ModelRow(vm: SettingsViewModel, m: SettingsViewModel.LocalModelUi, onRequestDelete: () -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                m.name,
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (m.isActive) {
                Badge("使用中", Tone.Ok)
                Spacer(Modifier.padding(2.dp))
            } else {
                Text(
                    "设为当前",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { vm.useLibraryModel(m) }.padding(horizontal = 6.dp, vertical = 4.dp)
                )
            }
        }
        Row(
            Modifier.padding(top = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            m.meta?.architecture?.let { arch ->
                Badge(
                    if (m.supported == false) "$arch · 引擎不支持" else arch,
                    when (m.supported) {
                        false -> Tone.Bad
                        true -> Tone.Ok
                        null -> Tone.Neutral
                    },
                    mono = true
                )
            }
            m.meta?.quantName?.let { Badge(it, mono = true) }
            m.meta?.sizeLabel?.let { Badge(it) }
            Badge(fmtSize2(m.sizeBytes))
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                if (m.isDevice) "原路径直读 · ${m.path.removeSuffix("/${m.name}")}" else "应用目录 · models/",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                if (m.isDevice) "移除" else "删除",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.clickable { onRequestDelete() }.padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
    }
}

// ── 运行参数卡 ────────────────────────────────────────────

@Composable
private fun LocalParamsCard(vm: SettingsViewModel, backdrop: LayerBackdrop) {
    val settings by vm.settings.collectAsState()
    val scope = rememberCoroutineScope()
    // 滑杆走本地态 + onValueChangeFinished 一次性提交（updateSettings 是双文件写，逐帧提交会写放大）
    var temp by remember(settings.providers) { mutableStateOf(vm.localSampling().first) }
    var topP by remember(settings.providers) { mutableStateOf(vm.localSampling().second) }
    var topK by remember(settings.providers) { mutableStateOf(vm.localAdvancedSampling().first) }
    var rep by remember(settings.providers) { mutableStateOf(vm.localAdvancedSampling().second) }
    var seedText by remember(settings.localSeed) { mutableStateOf(settings.localSeed.toString()) }
    var advOpen by remember { mutableStateOf(false) }

    GlassGroup(backdrop) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("运行参数", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(
                    "恢复默认",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable {
                        vm.setLocalThreads(0); vm.setLocalBackend("auto"); vm.setLocalFlashAttn("auto")
                        vm.setLocalKvCache("f16"); vm.setLocalMmap(true); vm.setLocalSeed(-1)
                        vm.setLocalSampling(0.7f, 0.95f); vm.setLocalAdvancedSampling(40, 1.1f)
                        temp = 0.7f; topP = 0.95f; topK = 40; rep = 1.1f; seedText = "-1"
                    }.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }

            ParamLabel("上下文窗口 *", "${settings.localContextLength / 1024}K")
            val ctxOptions = listOf(32768, 65536, 131072, 262144)
            LiquidTabRow(
                tabs = ctxOptions.map { "${it / 1024}K" },
                selectedIndex = ctxOptions.indexOf(settings.localContextLength).coerceAtLeast(0),
                onSelected = { i -> vm.setLocalContextLength(ctxOptions[i]) },
                backdrop = backdrop,
                modifier = Modifier.padding(top = 6.dp)
            )

            ParamLabel("采样温度", String.format("%.2f", temp))
            LiquidSlider(
                value = { temp },
                onValueChange = { temp = it },
                valueRange = 0f..1.5f,
                visibilityThreshold = 0.01f,
                backdrop = backdrop,
                onValueChangeFinished = { scope.launch { vm.setLocalSampling(temp, topP) } },
                modifier = Modifier.padding(top = 4.dp)
            )

            ParamLabel("Top-P", String.format("%.2f", topP))
            LiquidSlider(
                value = { topP },
                onValueChange = { topP = it },
                valueRange = 0.05f..1f,
                visibilityThreshold = 0.01f,
                backdrop = backdrop,
                onValueChangeFinished = { scope.launch { vm.setLocalSampling(temp, topP) } },
                modifier = Modifier.padding(top = 4.dp)
            )

            ParamLabel("Top-K", if (topK <= 0) "服务器默认" else "$topK")
            StepperRow(
                value = topK,
                display = if (topK <= 0) "默认" else "$topK",
                onMinus = { topK = (topK - 5).coerceAtLeast(0); vm.setLocalAdvancedSampling(topK, rep) },
                onPlus = { topK = (topK + 5).coerceAtMost(200); vm.setLocalAdvancedSampling(topK, rep) },
                canPlus = topK < 200
            )

            ParamLabel("重复惩罚", if (rep <= 1.001f) "关闭" else String.format("%.2f", rep))
            LiquidSlider(
                value = { rep },
                onValueChange = { rep = it },
                valueRange = 1f..1.5f,
                visibilityThreshold = 0.005f,
                backdrop = backdrop,
                onValueChangeFinished = { scope.launch { vm.setLocalAdvancedSampling(topK, rep) } },
                modifier = Modifier.padding(top = 4.dp)
            )

            ParamLabel("推理线程 *", if (settings.localThreads <= 0) "自动（留 2 核给系统）" else "${settings.localThreads}")
            StepperRow(
                value = settings.localThreads,
                display = if (settings.localThreads <= 0) "自动" else "${settings.localThreads}",
                onMinus = { vm.setLocalThreads(settings.localThreads - 1) },
                onPlus = { vm.setLocalThreads(settings.localThreads + 1) },
                canMinus = settings.localThreads > 0,
                canPlus = settings.localThreads < 8
            )

            ParamLabel("运行后端 *", when (settings.localBackend) {
                "npu" -> "锁定 NPU"
                "gpu" -> "锁定 GPU"
                "cpu" -> "锁定 CPU"
                else -> "自动降级"
            })
            val backendOpts = listOf("auto" to "自动", "npu" to "NPU", "gpu" to "GPU", "cpu" to "CPU")
            LiquidTabRow(
                tabs = backendOpts.map { it.second },
                selectedIndex = backendOpts.indexOfFirst { it.first == settings.localBackend }.coerceAtLeast(0),
                onSelected = { i -> vm.setLocalBackend(backendOpts[i].first) },
                backdrop = backdrop,
                modifier = Modifier.padding(top = 6.dp)
            )

            Text(
                "高级参数（FlashAttention / KV 量化 / mmap / 种子）",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { advOpen = !advOpen }.padding(top = 12.dp, bottom = 2.dp)
            )
            AnimatedVisibility(advOpen) {
                Column {
                    ParamLabel("FlashAttention *", when (settings.localFlashAttn) { "on" -> "开"; "off" -> "关"; else -> "自动" })
                    val faOpts = listOf("auto" to "自动", "on" to "开", "off" to "关")
                    LiquidTabRow(
                        tabs = faOpts.map { it.second },
                        selectedIndex = faOpts.indexOfFirst { it.first == settings.localFlashAttn }.coerceAtLeast(0),
                        onSelected = { i -> vm.setLocalFlashAttn(faOpts[i].first) },
                        backdrop = backdrop,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                    ParamLabel("KV 缓存量化 *", when (settings.localKvCache) { "q8_0" -> "q8_0（省一半）"; "q4_0" -> "q4_0（省四分之三）"; else -> "f16" })
                    val kvOpts = listOf("f16" to "f16", "q8_0" to "q8_0", "q4_0" to "q4_0")
                    LiquidTabRow(
                        tabs = kvOpts.map { it.second },
                        selectedIndex = kvOpts.indexOfFirst { it.first == settings.localKvCache }.coerceAtLeast(0),
                        onSelected = { i -> vm.setLocalKvCache(kvOpts[i].first) },
                        backdrop = backdrop,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(top = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("mmap 读模型 *", style = MaterialTheme.typography.bodySmall)
                        HaoSwitch(checked = settings.localMmap, onCheckedChange = { vm.setLocalMmap(it) })
                    }
                    ParamLabel("随机种子", if (settings.localSeed < 0) "随机" else settings.localSeed.toString())
                    OutlinedTextField(
                        value = seedText,
                        onValueChange = {
                            seedText = it
                            it.toLongOrNull()?.let { v -> vm.setLocalSeed(v) }
                        },
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        label = { Text("-1 = 随机") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        textStyle = MaterialTheme.typography.labelMedium,
                        colors = glassFieldColors()
                    )
                }
            }

            Text(
                "* 需重启服务生效，其余随每次请求生效。上下文越大越吃内存：KV 缓存随窗口线性增长，256K 建议搭配 q8_0。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp)
            )
        }
    }
}

// ── 引擎卡 ────────────────────────────────────────────────

@Composable
private fun LocalEngineCard(backdrop: LayerBackdrop) {
    val tag = LlamaServerController.ENGINE_TAG
    val upToDate = engineNum(tag) >= engineNum(LATEST_KNOWN_ENGINE)
    GlassGroup(backdrop) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("推理引擎", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Badge(if (upToDate) "已是最新" else "有新版本", if (upToDate) Tone.Ok else Tone.Warn)
            }
            Row(
                Modifier.padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Badge("官方预编译 $tag", mono = true)
                Badge("CPU")
                Badge("Adreno GPU")
                Badge("Hexagon NPU")
            }
            Text(
                "引擎随 App 版本更新：构建时自动拉取官方预编译包并校验 sha256。" +
                    "LFM3 系列架构需引擎 ≥ b11982（2026-10-29 起）。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}

// ── 弹层 ──────────────────────────────────────────────────

@Composable
internal fun LocalLlamaSheets(vm: SettingsViewModel, backdrop: LayerBackdrop) {
    when (vm.llamaSheet) {
        "log" -> GlassBottomSheet(backdrop, onDismiss = { vm.closeLlamaSheet() }) {
            LogSheetContent(vm)
        }
        "add" -> GlassBottomSheet(backdrop, onDismiss = { vm.closeLlamaSheet() }) {
            AddModelSheetContent(vm, backdrop)
        }
    }
}

@Composable
private fun AddModelSheetContent(vm: SettingsViewModel, backdrop: LayerBackdrop) {
    val tabs = listOf("dl" to "模型广场", "scan" to "扫描手机", "file" to "本地文件")
    LiquidTabRow(
        tabs = tabs.map { it.second },
        selectedIndex = tabs.indexOfFirst { it.first == vm.llamaAddTab }.coerceAtLeast(0),
        onSelected = { i -> vm.llamaAddTab = tabs[i].first },
        backdrop = backdrop
    )
    Spacer(Modifier.padding(top = 8.dp))
    when (vm.llamaAddTab) {
        "scan" -> ScanTab(vm)
        "file" -> FileTab(vm, backdrop)
        else -> DownloadTab(vm, backdrop)
    }
}

private data class CatalogEntry(
    val name: String,
    val fileName: String,
    val url: String,
    val arch: String,
    val quant: String,
    val size: String,
)

private val CATALOG = listOf(
    CatalogEntry(
        "LFM3-1.7B · LiquidAI", "LFM3-1.7B-Q4_K_M.gguf",
        "https://www.modelscope.cn/models/LiquidAI/LFM3-1.7B-GGUF/resolve/master/LFM3-1.7B-Q4_K_M.gguf",
        "lfm3", "Q4_K_M", "1.02GB"
    ),
    CatalogEntry(
        "LFM3-1.7B · LiquidAI", "LFM3-1.7B-Q8_0.gguf",
        "https://www.modelscope.cn/models/LiquidAI/LFM3-1.7B-GGUF/resolve/master/LFM3-1.7B-Q8_0.gguf",
        "lfm3", "Q8_0", "1.85GB"
    ),
    CatalogEntry(
        "LFM3-1.7B · LiquidAI", "LFM3-1.7B-Q5_K_M.gguf",
        "https://www.modelscope.cn/models/LiquidAI/LFM3-1.7B-GGUF/resolve/master/LFM3-1.7B-Q5_K_M.gguf",
        "lfm3", "Q5_K_M", "1.26GB"
    ),
    CatalogEntry(
        "Qwen2.5-0.5B-Instruct（轻量兜底）", "qwen2.5-0.5b-instruct-q4_k_m.gguf",
        "https://hf-mirror.com/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf",
        "qwen2", "Q4_K_M", "470MB"
    ),
)

@Composable
private fun DownloadTab(vm: SettingsViewModel, backdrop: LayerBackdrop) {
    val dl by vm.llamaDownload.collectAsState()
    Text(
        "推荐模型（ModelScope / HF 镜像国内直链，下载完自动校验架构入库）；也可粘贴直链。",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    CATALOG.forEach { e ->
        Row(
            Modifier.fillMaxWidth().padding(vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(e.name, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Badge(
                        e.arch,
                        if (LlamaServerController.archSupported(e.arch)) Tone.Ok else Tone.Warn,
                        mono = true
                    )
                    Badge(e.quant, mono = true)
                    Badge(e.size)
                }
            }
            when {
                vm.llamaDownloadingName == e.fileName -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        LinearProgressIndicator(
                            progress = { dl ?: 0f },
                            modifier = Modifier.width(76.dp),
                            gapSize = 0.dp, drawStopIndicator = {}
                        )
                        Text(
                            "${((dl ?: 0f) * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 3.dp)
                        )
                    }
                }
                else -> Text(
                    "获取",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    color = if (vm.llamaDownloadingName == null) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier
                        .clickable(enabled = vm.llamaDownloadingName == null) {
                            vm.downloadCatalogModel(e.url, e.fileName)
                        }
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.08f))
    }
    var dlUrl by remember { mutableStateOf("") }
    OutlinedTextField(
        value = dlUrl,
        onValueChange = { dlUrl = it },
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        label = { Text("自定义 GGUF 直链") },
        singleLine = true,
        textStyle = MaterialTheme.typography.labelSmall,
        colors = glassFieldColors()
    )
    LiquidPillButton(
        backdrop = backdrop, text = "下载直链",
        enabled = dlUrl.isNotBlank() && vm.llamaDownloadingName == null,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
    ) {
        val raw = dlUrl.trim()
        val name = raw.substringAfterLast('/').substringBefore('?')
            .let { if (it.endsWith(".gguf", true)) it else "$it.gguf" }
        vm.downloadCatalogModel(raw, name)
    }
}

@Composable
private fun ScanTab(vm: SettingsViewModel) {
    LaunchedEffect(Unit) {
        if (vm.scannedDeviceModels == null && !vm.scanningModels) vm.scanDeviceModels()
    }
    Text(
        "扫描 Download / Documents / models / 存储根目录（含一级子目录）。引用原文件不复制、不占双份空间；架构徽标在入库前完成校验。",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.padding(top = 6.dp))
    when {
        vm.scanningModels -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CircularProgressIndicator(Modifier.padding(4.dp), strokeWidth = 2.dp)
            Text("扫描中…", style = MaterialTheme.typography.bodySmall)
        }
        vm.scannedDeviceModels.isNullOrEmpty() -> Text(
            "未找到大体积 GGUF。模型放在受限目录时，先到「权限与自动化 → 系统权限」授予「所有文件访问」。",
            style = MaterialTheme.typography.bodySmall
        )
        else -> Column {
            vm.scannedDeviceModels.orEmpty().forEach { sm ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            sm.file.name,
                            style = MaterialTheme.typography.labelMedium,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            sm.meta?.architecture?.let { arch ->
                                Badge(
                                    if (sm.supported == false) "$arch · 引擎不支持" else arch,
                                    when (sm.supported) { false -> Tone.Bad; true -> Tone.Ok; null -> Tone.Neutral },
                                    mono = true
                                )
                            }
                            Badge(fmtSize2(sm.file.length()))
                        }
                        Text(
                            sm.file.parent?.removePrefix("/storage/emulated/0/") ?: "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                    Text(
                        "引用原文件",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clickable {
                                vm.useDeviceModel(sm.file.absolutePath)
                                vm.closeLlamaSheet()
                            }
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.08f))
            }
        }
    }
}

@Composable
private fun FileTab(vm: SettingsViewModel, backdrop: LayerBackdrop) {
    val ctx = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.loadModelFile(ctx, it.toString()) }
    }
    Text(
        "从文件管理器选取任意位置的 GGUF：本机存储直读引用（不复制），受限位置自动复制到应用目录。不再要求把模型挪到指定文件夹。",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    LiquidPillButton(
        backdrop = backdrop, text = "选择 GGUF 文件",
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
    ) { launcher.launch(arrayOf("*/*")) }
    vm.importingModel?.let {
        Text(
            it, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

@Composable
private fun LogSheetContent(vm: SettingsViewModel) {
    var lines by remember { mutableStateOf(vm.llamaLogTail()) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("服务日志 · server.log", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        Text(
            "刷新",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable { lines = vm.llamaLogTail() }.padding(horizontal = 6.dp)
        )
        val clipboard = LocalClipboardManager.current
        Text(
            "复制",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clickable { clipboard.setText(AnnotatedString(lines.joinToString("\n"))) }
                .padding(horizontal = 6.dp)
        )
    }
    Surface(
        color = Color(0xFF0D1117),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
    ) {
        Text(
            if (lines.isEmpty()) "暂无日志：服务启动后这里会显示加载过程与报错。"
            else lines.joinToString("\n"),
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            lineHeight = 15.sp,
            color = Color(0xFFC9D4E4),
            modifier = Modifier
                .heightIn(max = 340.dp)
                .verticalScroll(rememberScrollState())
                .padding(12.dp)
        )
    }
    Text(
        "仅存应用私有目录，不上传。失败时优先看这里的真实报错。",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp)
    )
}
