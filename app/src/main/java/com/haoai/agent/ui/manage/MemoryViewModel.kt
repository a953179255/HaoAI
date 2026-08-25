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

    init {
        refresh()
    }

    fun refresh() {
        items = c.memoryBank.all()
        days = c.journal.allDays()
    }

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
            refresh()
            consolidating = false
            c.syncWorkspaceDocs()
            onDone(report.describe())
        }
    }

    fun addManual(content: String, type: String, importance: Int) {
        c.memoryBank.remember(content.trim(), type = type, importance = importance.coerceIn(1, 5))
        refresh()
        c.syncWorkspaceDocs()
    }
}
