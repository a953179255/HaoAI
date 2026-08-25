package com.haoai.agent.ui.manage

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.haoai.agent.agent.schedule.ScheduleStore
import com.haoai.agent.agent.schedule.ScheduleTask
import com.haoai.agent.agent.schedule.Scheduler
import com.haoai.agent.data.AppContainer

class ScheduleViewModel(private val c: AppContainer) : ViewModel() {

    var items by mutableStateOf<List<ScheduleTask>>(emptyList())
        private set

    private val store = ScheduleStore(c.appFilesDir)

    init {
        refresh()
    }

    fun refresh() {
        items = store.load().items.sortedBy { it.name }
    }

    fun toggle(task: ScheduleTask) {
        val state = store.load()
        state.items.find { it.id == task.id }?.let {
            it.enabled = !it.enabled
            if (it.enabled) Scheduler.enqueueNext(it) else Scheduler.cancel(it.id)
        }
        store.save(state)
        refresh()
    }

    fun remove(task: ScheduleTask) {
        val state = store.load()
        state.items.removeAll { it.id == task.id }
        store.save(state)
        Scheduler.cancel(task.id)
        refresh()
    }
}
