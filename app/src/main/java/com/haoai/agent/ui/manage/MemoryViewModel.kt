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
        items = c.memoryBank.all()
        days = c.journal.allDays()
        capacity = c.memoryBank.capacity()
        val st = c.settingsFlow.value
        lastConsolidationAt = st.lastConsolidationAt
        lastConsolidationReport = st.lastConsolidationReport
        lastBackupAt = st.lastMemoryBackupAt
        val bankContents = items.mapTo(HashSet()) { it.content }
        promotionCandidates = days.flatMap { it.items }
            .count { it.importance >= MemoryConsolidation.PROMOTE_THRESHOLD && it.content !in bankContents }
    }

    /** 最久未使用的记忆（健康度仪表盘的清理建议，最多 5 条）。 */
    fun oldestUnused(): List<Memory> = items
        .sortedBy { if (it.lastUsedAt > 0) it.lastUsedAt else it.createdAt }
        .take(5)

    fun journalCount(): Int = days.sumOf { it.items.size }

    fun delete(id: String) {
        c.memoryBank.forget(id)
        refresh()
        c.syncWorkspaceDocs()
    }

    fun clearAll() {
        c.memoryBank.clear()
        refresh()
        c.syncWorkspaceDocs()
    }

    fun clearJournal() {
        c.journal.clear()
        refresh()
        c.syncWorkspaceDocs()
    }

    fun tidy(): Int {
        val n = c.memoryBank.tidy()
        refresh()
        return n
    }

    /** 手动固化：按「记忆管理模型」设置执行（端侧或云端），失败回退规则；完成后回调描述文本。 */
    fun consolidate(onDone: (String) -> Unit) {
        if (consolidating) return
        consolidating = true
        viewModelScope.launch {
            var deep = false
            val report = runCatching {
                val st = c.settingsFlow.value
                if (st.deepDream && st.memoryEnabled) {
                    val wasRunning = c.llama.state.value is LlamaState.Running
                    val target = runCatching { c.resolveDreamTarget() }.getOrNull()
                    deep = target != null
                    val r = if (target != null) {
                        runCatching {
                            MemoryConsolidation.runDeep(
                                c.memoryBank, c.journal, c.client, target.provider, target.apiKey
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
            }.getOrDefault(MemoryConsolidation.Report(0, 0, 0))
            consolidating = false
            c.syncWorkspaceDocs()
            // 先持久化再刷新：仪表盘的上次固化/备份时间要读到本次结果
            persistConsolidation(report)
            refresh()
            onDone(report.describe())
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
