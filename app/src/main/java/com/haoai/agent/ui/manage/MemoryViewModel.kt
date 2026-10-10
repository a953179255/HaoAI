package com.haoai.agent.ui.manage

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.haoai.agent.agent.memory.JournalDay
import com.haoai.agent.agent.memory.Memory
import com.haoai.agent.agent.memory.MemoryConsolidation
import com.haoai.agent.data.AppContainer
import com.haoai.agent.platform.llama.LlamaState
import kotlinx.coroutines.launch

class MemoryViewModel(private val c: AppContainer) : ViewModel() {

    var items by mutableStateOf<List<Memory>>(emptyList())
        private set

    var days by mutableStateOf<List<JournalDay>>(emptyList())
        private set

    var consolidating by mutableStateOf(false)
        private set

    /** 仪表盘数据：容量、上次固化/备份时间与结果、晋升候选数。 */
    var capacity by mutableStateOf(200)
        private set

    var lastConsolidationAt by mutableStateOf(0L)
        private set

    var lastConsolidationReport by mutableStateOf("")
        private set

    var lastBackupAt by mutableStateOf(0L)
        private set

    var promotionCandidates by mutableStateOf(0)
        private set

    init {
        refresh()
    }

    fun refresh() {
        // M21/M19（2026-10-10）：all()/allDays() 都是同步读盘（MEMORY.md 全量解析 +
        // 日志目录全扫），原来直接跑在调用线程——init 时在主线程，delete/tidy 后也在
        // 主线程再读一遍。挪到 IO，完成后回写 Compose state（snapshotState 可跨线程写）。
        // 便宜的三个字段（settingsFlow 内存读）同步赋值，保持首帧不空。
        val st = c.settingsFlow.value
        lastConsolidationAt = st.lastConsolidationAt
        lastConsolidationReport = st.lastConsolidationReport
        lastBackupAt = st.lastMemoryBackupAt
        capacity = c.memoryBank.capacity()
        viewModelScope.launch {
            val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val its = c.memoryBank.all()
                val d = c.journal.allDays()
                val bankContents = its.mapTo(HashSet()) { it.content }
                val promo = d.flatMap { it.items }
                    .count { it.importance >= MemoryConsolidation.PROMOTE_THRESHOLD && it.content !in bankContents }
                Triple(its, d, promo)
            }
            items = r.first
            days = r.second
            promotionCandidates = r.third
        }
    }

    /** 最久未使用的记忆（健康度仪表盘的清理建议，最多 5 条）。 */
    fun oldestUnused(): List<Memory> = items
        .sortedBy { if (it.lastUsedAt > 0) it.lastUsedAt else it.createdAt }
        .take(5)

    fun journalCount(): Int = days.sumOf { it.items.size }

    fun delete(id: String) {
        // M21：forget 内部是"全量重写 MEMORY.md + .bak 拷贝"，主线程同步跑会卡
        viewModelScope.launch {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { c.memoryBank.forget(id) }
            refresh()
            c.syncWorkspaceDocs()
        }
    }

    fun clearAll() {
        viewModelScope.launch {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { c.memoryBank.clear() }
            refresh()
            c.syncWorkspaceDocs()
        }
    }

    fun clearJournal() {
        viewModelScope.launch {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { c.journal.clear() }
            refresh()
            c.syncWorkspaceDocs()
        }
    }

    /** M21：整理改为后台执行，结果经 [onDone] 回调（调用方显示提示文案）。 */
    fun tidy(onDone: (Int) -> Unit = {}) {
        viewModelScope.launch {
            val n = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { c.memoryBank.tidy() }
            refresh()
            onDone(n)
        }
    }

    /** 手动固化：按「记忆管理模型」设置执行（端侧或云端），失败回退规则；完成后回调描述文本。 */
    fun consolidate(onDone: (String) -> Unit) {
        if (consolidating) return
        consolidating = true
        viewModelScope.launch {
            var deep = false
            // m11：CancellationException 被 runCatching 吞掉 ⇒ ViewModel 销毁（用户离开页面）
            // 后协程仍继续跑完整轮 LLM 固化，中途不可中断、还会往 MEMORY.md 写结果。
            // 修法：取消异常先行重抛，让协程按结构化并发正常取消；其余异常仍走回退。
            val report = runCatching {
                val st = c.settingsFlow.value
                if (st.deepDream && st.memoryEnabled) {
                    val wasRunning = c.llama.state.value is LlamaState.Running
                    val target = runCatching { c.resolveDreamTarget() }.getOrNull()
                    deep = target != null
                    val r = if (target != null) {
                        runCatching {
                            MemoryConsolidation.runDeep(
                                c.memoryBank, c.journal, c.clientFor(target.provider), target.provider, target.apiKey,
                                onUsage = { pin, pout, ok ->
                                    com.haoai.agent.data.UsageLedger.add(
                                        com.haoai.agent.data.UsageLedger.Entry(
                                            kind = "llm", ts = System.currentTimeMillis(),
                                            purpose = "dream", model = target.provider.model,
                                            promptTokens = pin, completionTokens = pout, ok = ok
                                        )
                                    )
                                }
                            )
                        }.getOrElse { MemoryConsolidation.run(c.memoryBank, c.journal) }
                    } else MemoryConsolidation.run(c.memoryBank, c.journal)
                    if (target?.isLocal == true && !wasRunning) runCatching { c.llama.stop() }
                    com.haoai.agent.platform.WorkspaceDocs.appendDreamReport(c, r, deep)
                    r
                } else {
                    MemoryConsolidation.run(c.memoryBank, c.journal).also {
                        com.haoai.agent.platform.WorkspaceDocs.appendDreamReport(c, it, false)
                    }
                }
            }.getOrElse { e ->
                // m11：取消不是"失败"，必须继续往上抛，否则协程被取消后仍往下跑
                //（写 MEMORY.md、刷 UI、回调 onDone），且这段 LLM 调用不可中断。
                if (e is kotlinx.coroutines.CancellationException) throw e
                MemoryConsolidation.Report(0, 0, 0)
            }
            consolidating = false
            c.syncWorkspaceDocs()
            // 先持久化再刷新：仪表盘的上次固化/备份时间要读到本次结果
            persistConsolidation(report)
            refresh()
            onDone(report.describe())
        }.also { job ->
            // m11 补：协程被取消时 consolidating 不会复位，UI 会永久卡在"固化中"再也点不动。
            job.invokeOnCompletion { consolidating = false }
        }
    }

    /** 固化结果持久化（健康度仪表盘用）+ 本地自动备份。 */
    private suspend fun persistConsolidation(report: MemoryConsolidation.Report) {
        runCatching {
            c.updateSettings {
                it.copy(
                    lastConsolidationAt = System.currentTimeMillis(),
                    lastConsolidationReport = report.describe()
                )
            }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.haoai.agent.platform.MemoryBackupManager.autoBackup(c)
            }
        }
    }

    fun addManual(content: String, type: String, importance: Int) {
        c.memoryBank.remember(
            content.trim(), type = type, importance = importance.coerceIn(1, 5), source = "manual"
        )
        refresh()
        c.syncWorkspaceDocs()
    }

    /** 导出记忆全部数据（MEMORY.md 真源 + 日志 + 固化报告 + 镜像 + 设置快照）为 zip。 */
    fun exportTo(uri: android.net.Uri, onDone: (String) -> Unit) {
        viewModelScope.launch {
            val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.haoai.agent.platform.MemoryBackupManager.exportZip(c, uri)
            }
            result.fold(
                onSuccess = { n ->
                    c.updateSettings { it.copy(lastMemoryExportAt = System.currentTimeMillis()) }
                    onDone("已导出 $n 个文件")
                },
                onFailure = { onDone("导出失败：${it.message}") }
            )
        }
    }
}
